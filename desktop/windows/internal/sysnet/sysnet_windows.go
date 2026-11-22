//go:build windows

package sysnet

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// Simulated: false di Windows.
const Simulated = false

func run(timeout time.Duration, name string, args ...string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	c := exec.CommandContext(ctx, name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
	var out bytes.Buffer
	c.Stdout, c.Stderr = &out, &out
	err := c.Run()
	return out.Bytes(), err
}

// runSplit: seperti run, tetapi stdout dan stderr dipisah (PowerShell menulis "#< CLIXML" ke stderr bila keluaran dialihkan).
func runSplit(timeout time.Duration, name string, args ...string) (so, se []byte, err error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	c := exec.CommandContext(ctx, name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
	var o, e bytes.Buffer
	c.Stdout, c.Stderr = &o, &e
	err = c.Run()
	return o.Bytes(), e.Bytes(), err
}

func ps(script string) ([]byte, error) {
	// -EncodedCommand menghindari masalah kutip/escape. -OutputFormat Text + ProgressPreference mencegah CLIXML/progress.
	script = "$ProgressPreference='SilentlyContinue'\n" + script
	u := UTF16LE(script)[2:]
	so, se, err := runSplit(60*time.Second, "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
		"-OutputFormat", "Text", "-EncodedCommand", b64(u))
	if err != nil {
		return append(so, se...), err
	}
	return so, nil
}

func IsAdmin() bool {
	// fltmc hanya berhasil bila administrator (tidak bergantung layanan Server seperti `net session`).
	if _, err := run(10*time.Second, "fltmc.exe"); err == nil {
		return true
	}
	_, err := run(10*time.Second, "net.exe", "session")
	return err == nil
}

// Elevate menjalankan ulang program ini dengan UAC (hak administrator). Pemanggil harus keluar sesudahnya.
func Elevate(args []string) error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	q := make([]string, len(args))
	for i, a := range args {
		q[i] = `"` + strings.ReplaceAll(a, `"`, `\"`) + `"`
	}
	script := "Start-Process -FilePath '" + strings.ReplaceAll(exe, "'", "''") + "' -Verb RunAs"
	if len(args) > 0 {
		script += " -ArgumentList '" + strings.ReplaceAll(strings.Join(q, " "), "'", "''") + "'"
	}
	_, err = ps(script)
	return err
}

func BackupAdapters(dir string) ([]Adapter, error) {
	p := filepath.Join(dir, DNSBackup)
	if b, err := os.ReadFile(p); err == nil { // jangan menimpa cadangan asli bila sudah diarahkan ke Aegis
		if as, err := ParseAdapters(b); err == nil && len(as) > 0 {
			return as, nil
		}
	}
	out, err := ps(PSBackup)
	if err != nil {
		return nil, errors.New("gagal membaca DNS adaptor: " + strings.TrimSpace(string(out)))
	}
	as, err := ParseAdapters(out)
	if err != nil {
		return nil, err
	}
	if len(as) == 0 {
		return nil, errors.New("tidak ada adaptor jaringan yang cocok")
	}
	b, _ := json.MarshalIndent(as, "", " ")
	return as, os.WriteFile(p, b, 0o644)
}

func PointDNS(as []Adapter, failsafe string) error {
	out, err := ps(PSSetDNS(as, failsafe))
	if err != nil {
		return errors.New("gagal mengatur DNS: " + strings.TrimSpace(string(out)))
	}
	return nil
}

func RestoreDNS(dir string) error {
	b, err := os.ReadFile(filepath.Join(dir, DNSBackup))
	if err != nil {
		return errors.New("cadangan DNS tidak ditemukan")
	}
	as, err := ParseAdapters(b)
	if err != nil {
		return err
	}
	out, err := ps(PSRestoreDNS(as))
	if err != nil {
		return errors.New("gagal memulihkan DNS: " + strings.TrimSpace(string(out)))
	}
	return nil
}

func InstallTask(dir, exe string) error {
	p := filepath.Join(dir, "task.xml")
	if err := os.WriteFile(p, UTF16LE(TaskXML(exe, "run")), 0o644); err != nil {
		return err
	}
	if out, err := run(30*time.Second, "schtasks.exe", "/create", "/tn", TaskName, "/xml", p, "/f"); err != nil {
		return errors.New("schtasks gagal: " + strings.TrimSpace(string(out)))
	}
	return nil
}

func StartTask() error {
	out, err := run(30*time.Second, "schtasks.exe", "/run", "/tn", TaskName)
	if err != nil {
		return errors.New("schtasks run gagal: " + strings.TrimSpace(string(out)))
	}
	return nil
}

func StopAll() {
	run(20*time.Second, "schtasks.exe", "/end", "/tn", TaskName)
	// jangan membunuh diri sendiri (mis. saat `aegis.exe uninstall`)
	run(20*time.Second, "taskkill.exe", "/f", "/im", "aegis.exe", "/fi", "PID ne "+strconv.Itoa(os.Getpid()))
	time.Sleep(700 * time.Millisecond)
}

func RemoveTask() { run(30*time.Second, "schtasks.exe", "/delete", "/tn", TaskName, "/f") }

func SetDoHPolicy(off bool) {
	for _, k := range DoHPolicies {
		if off {
			run(15*time.Second, "reg.exe", "add", k, "/v", "DnsOverHttpsMode", "/t", "REG_SZ", "/d", "off", "/f")
		} else {
			run(15*time.Second, "reg.exe", "delete", k, "/v", "DnsOverHttpsMode", "/f")
		}
	}
}

func AddUninstallEntry(dir, exe, version string) {
	set := func(name, typ, val string) {
		run(15*time.Second, "reg.exe", "add", UninstKey, "/v", name, "/t", typ, "/d", val, "/f")
	}
	set("DisplayName", "REG_SZ", "Aegis AdBlock")
	set("DisplayVersion", "REG_SZ", version)
	set("Publisher", "REG_SZ", "Aegis")
	set("InstallLocation", "REG_SZ", dir)
	set("DisplayIcon", "REG_SZ", exe)
	set("UninstallString", "REG_SZ", `"`+exe+`" uninstall`)
	set("NoModify", "REG_DWORD", "1")
	set("NoRepair", "REG_DWORD", "1")
}

func RemoveUninstallEntry() { run(15*time.Second, "reg.exe", "delete", UninstKey, "/f") }

// StartMenuDir: folder Program di Start Menu untuk semua pengguna.
func StartMenuDir() string {
	return filepath.Join(os.Getenv("ProgramData"), `Microsoft\Windows\Start Menu\Programs\Aegis AdBlock`)
}

func DefaultInstallDir() string { return filepath.Join(os.Getenv("ProgramFiles"), "Aegis AdBlock") }
func DefaultDataDir() string    { return filepath.Join(os.Getenv("ProgramData"), "Aegis") }

// OpenURL membuka alamat di browser bawaan pengguna.
func OpenURL(u string) { exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", u).Start() }

// SelfDelete menghapus folder (termasuk exe yang sedang berjalan) setelah proses ini keluar.
func SelfDelete(dir string) {
	c := exec.Command("cmd.exe", "/c", "ping -n 4 127.0.0.1 >nul & rmdir /s /q \""+dir+"\"")
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000 | 0x00000008}
	c.Start()
}
