// Package sysnet: integrasi sistem (DNS adaptor, tugas terjadwal, kebijakan browser). Bagian khusus Windows ada di
// sysnet_windows.go; di OS lain hanya simulasi (untuk pengembangan/tes).
package sysnet

import (
	"bytes"
	"encoding/json"
	"fmt"
	"html"
	"net"
	"strings"
	"unicode/utf16"
)

const (
	TaskName    = "AegisAdblock"
	UninstKey   = `HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall\AegisAdblock`
	DNSBackup   = "dns-backup.json"
	UpstreamsTx = "upstreams.txt"
)

// Adapter: satu antarmuka jaringan beserta DNS aslinya (untuk dipulihkan).
type Adapter struct {
	Index   int      `json:"Index"`
	Alias   string   `json:"Alias"`
	Servers []string `json:"Servers"`
	Dhcp    string   `json:"Dhcp"`
}

// cleanPS membuang sampah keluaran PowerShell di depan/belakang JSON: BOM, baris "#< CLIXML", blok <Objs>…</Objs>
// (progress/error terserialisasi), lalu mengambil dari "[" / "{" pertama (atau literal null).
func cleanPS(b []byte) []byte {
	b = bytes.TrimSpace(bytes.TrimPrefix(b, []byte("\xef\xbb\xbf")))
	for {
		i := bytes.Index(b, []byte("<Objs"))
		if i < 0 {
			break
		}
		j := bytes.Index(b[i:], []byte("</Objs>"))
		if j < 0 {
			b = b[:i]
			break
		}
		b = append(append([]byte{}, b[:i]...), b[i+j+len("</Objs>"):]...)
	}
	var keep [][]byte
	for _, ln := range bytes.Split(b, []byte("\n")) {
		t := bytes.TrimSpace(ln)
		if len(t) == 0 || bytes.HasPrefix(t, []byte("#<")) {
			continue
		}
		keep = append(keep, ln)
	}
	b = bytes.TrimSpace(bytes.Join(keep, []byte("\n")))
	if len(b) > 0 && b[0] != '[' && b[0] != '{' && string(b) != "null" {
		if i := bytes.IndexAny(b, "[{"); i >= 0 {
			b = b[i:]
		}
	}
	return b
}

// ParseAdapters menerima keluaran ConvertTo-Json PowerShell (objek tunggal ATAU larik; null -> kosong).
func ParseAdapters(b []byte) ([]Adapter, error) {
	b = cleanPS(b)
	if len(b) == 0 || string(b) == "null" {
		return nil, nil
	}
	if b[0] == '{' {
		var a Adapter
		if err := json.Unmarshal(b, &a); err != nil {
			return nil, err
		}
		return []Adapter{a}, nil
	}
	var as []Adapter
	if err := json.Unmarshal(b, &as); err != nil {
		return nil, err
	}
	return as, nil
}

// OriginalIPv4 mengumpulkan DNS IPv4 asli yang bukan loopback, tanpa duplikat, urutan tetap.
func OriginalIPv4(as []Adapter) []string {
	seen := map[string]bool{}
	var out []string
	for _, a := range as {
		for _, s := range a.Servers {
			ip := net.ParseIP(strings.TrimSpace(s))
			if ip == nil || ip.To4() == nil || ip.IsLoopback() || ip.IsUnspecified() || seen[ip.String()] {
				continue
			}
			seen[ip.String()] = true
			out = append(out, ip.String())
		}
	}
	return out
}

// FailsafeIP: DNS cadangan di adaptor agar internet tetap jalan bila Aegis berhenti (DNS asli pertama, atau 1.1.1.1).
func FailsafeIP(orig []string) string {
	if len(orig) > 0 {
		return orig[0]
	}
	return "1.1.1.1"
}

// TaskXML membuat definisi Scheduled Task (jalan saat boot sebagai SYSTEM, restart otomatis bila crash).
func TaskXML(exe, args string) string {
	e := func(s string) string { return html.EscapeString(s) }
	return `<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.4" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo><Description>Aegis AdBlock - filter DNS lokal</Description></RegistrationInfo>
  <Triggers><BootTrigger><Enabled>true</Enabled></BootTrigger></Triggers>
  <Principals><Principal id="Author"><UserId>S-1-5-18</UserId><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowHardTerminate>true</AllowHardTerminate>
    <StartWhenAvailable>true</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <Hidden>true</Hidden>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <Priority>6</Priority>
    <RestartOnFailure><Interval>PT1M</Interval><Count>999</Count></RestartOnFailure>
  </Settings>
  <Actions Context="Author"><Exec><Command>` + e(exe) + `</Command><Arguments>` + e(args) + `</Arguments></Exec></Actions>
</Task>`
}

// UTF16LE menyandikan teks sebagai UTF-16 little-endian dengan BOM (format yang diminta schtasks /xml).
func UTF16LE(s string) []byte {
	u := utf16.Encode([]rune(s))
	b := make([]byte, 0, 2+len(u)*2)
	b = append(b, 0xFF, 0xFE)
	for _, c := range u {
		b = append(b, byte(c), byte(c>>8))
	}
	return b
}

// PowerShell: skrip bantu (string tetap, tanpa interpolasi masukan pengguna).
const PSBackup = `$ErrorActionPreference='Stop'
$bad='Loopback|isatap|Teredo|vEthernet|VMware|VirtualBox|Hyper-V|Bluetooth|Npcap|TAP-|Wintun|WireGuard|Tailscale|ZeroTier|Docker|WSL'
$ad = Get-NetAdapter | Where-Object { $_.InterfaceDescription -notmatch $bad -and $_.Name -notmatch $bad }
$ii = Get-NetIPInterface -AddressFamily IPv4
$dns = Get-DnsClientServerAddress -AddressFamily IPv4
$o = foreach($a in $ad){
  $d = $dns | Where-Object { $_.InterfaceIndex -eq $a.ifIndex } | Select-Object -First 1
  $i = $ii | Where-Object { $_.InterfaceIndex -eq $a.ifIndex } | Select-Object -First 1
  if($d -and $i){ [pscustomobject]@{Index=[int]$a.ifIndex;Alias=[string]$a.Name;Servers=@($d.ServerAddresses);Dhcp=[string]$i.Dhcp} }
}
ConvertTo-Json -InputObject @($o) -Compress`

// PSSetDNS membuat skrip yang mengarahkan adaptor ke Aegis (+DNS cadangan opsional).
func PSSetDNS(as []Adapter, failsafe string) string {
	var sb strings.Builder
	sb.WriteString("$ErrorActionPreference='Stop'\n")
	for _, a := range as {
		addr := "'127.0.0.1','::1'"
		if failsafe != "" {
			addr += ",'" + failsafe + "'"
		}
		fmt.Fprintf(&sb, "Set-DnsClientServerAddress -InterfaceIndex %d -ServerAddresses @(%s)\n", a.Index, addr)
	}
	sb.WriteString("Clear-DnsClientCache\n")
	return sb.String()
}

// PSRestoreDNS mengembalikan DNS: DHCP -> otomatis; statis -> alamat semula.
func PSRestoreDNS(as []Adapter) string {
	var sb strings.Builder
	sb.WriteString("$ErrorActionPreference='Continue'\n")
	for _, a := range as {
		var v4 []string
		for _, s := range a.Servers {
			if ip := net.ParseIP(s); ip != nil && ip.To4() != nil && !ip.IsLoopback() {
				v4 = append(v4, "'"+ip.String()+"'")
			}
		}
		if strings.EqualFold(a.Dhcp, "Enabled") || len(v4) == 0 {
			fmt.Fprintf(&sb, "Set-DnsClientServerAddress -InterfaceIndex %d -ResetServerAddresses\n", a.Index)
		} else {
			fmt.Fprintf(&sb, "Set-DnsClientServerAddress -InterfaceIndex %d -ServerAddresses @(%s)\n", a.Index, strings.Join(v4, ","))
		}
	}
	sb.WriteString("Clear-DnsClientCache\n")
	return sb.String()
}

// DoHPolicies: kunci kebijakan browser yang mematikan DNS-over-HTTPS (agar tidak melewati filter DNS).
var DoHPolicies = []string{
	`HKLM\SOFTWARE\Policies\Google\Chrome`,
	`HKLM\SOFTWARE\Policies\Microsoft\Edge`,
	`HKLM\SOFTWARE\Policies\BraveSoftware\Brave`,
	`HKLM\SOFTWARE\Policies\Chromium`,
}
