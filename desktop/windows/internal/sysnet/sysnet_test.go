package sysnet

import (
	"encoding/xml"
	"strings"
	"testing"
)

func TestParseAdapters(t *testing.T) {
	single := `{"Index":7,"Alias":"Wi-Fi","Servers":["192.168.1.1","8.8.8.8"],"Dhcp":"Enabled"}`
	arr := `[` + single + `,{"Index":9,"Alias":"Ethernet","Servers":[],"Dhcp":"Disabled"}]`
	for name, in := range map[string]string{"single": single, "arr": arr, "bom": "\xef\xbb\xbf" + arr} {
		as, err := ParseAdapters([]byte(in))
		if err != nil || len(as) == 0 || as[0].Index != 7 {
			t.Fatalf("%s: %v %+v", name, err, as)
		}
	}
	if as, _ := ParseAdapters([]byte("null")); len(as) != 0 {
		t.Fatal("null harus kosong")
	}
	as, _ := ParseAdapters([]byte(arr))
	o := OriginalIPv4(append(as, Adapter{Servers: []string{"127.0.0.1", "::1", "192.168.1.1", "1.1.1.1"}}))
	if strings.Join(o, ",") != "192.168.1.1,8.8.8.8,1.1.1.1" {
		t.Fatalf("original %v", o)
	}
	if FailsafeIP(o) != "192.168.1.1" || FailsafeIP(nil) != "1.1.1.1" {
		t.Fatal("failsafe")
	}
}

func TestScriptsAndXML(t *testing.T) {
	as := []Adapter{{Index: 7, Servers: []string{"192.168.1.1"}, Dhcp: "Enabled"}, {Index: 9, Servers: []string{"9.9.9.9", "127.0.0.1"}, Dhcp: "Disabled"}}
	set := PSSetDNS(as, "192.168.1.1")
	if !strings.Contains(set, "-InterfaceIndex 7 -ServerAddresses @('127.0.0.1','::1','192.168.1.1')") {
		t.Fatal(set)
	}
	rs := PSRestoreDNS(as)
	if !strings.Contains(rs, "-InterfaceIndex 7 -ResetServerAddresses") || !strings.Contains(rs, "-InterfaceIndex 9 -ServerAddresses @('9.9.9.9')") {
		t.Fatal(rs)
	}
	x := TaskXML(`C:\Program Files\Aegis AdBlock\aegis.exe`, "run")
	var v struct{ XMLName xml.Name }
	if err := xml.Unmarshal([]byte(strings.Replace(x, `encoding="UTF-16"`, `encoding="UTF-8"`, 1)), &v); err != nil || v.XMLName.Local != "Task" {
		t.Fatalf("xml tidak valid: %v", err)
	}
	b := UTF16LE("Aé")
	if len(b) != 6 || b[0] != 0xFF || b[1] != 0xFE || b[2] != 'A' || b[4] != 0xE9 {
		t.Fatalf("utf16 %v", b)
	}
}

func TestParseAdaptersCLIXML(t *testing.T) {
	in := "#< CLIXML\r\n<Objs Version=\"1.1.0.1\" xmlns=\"http://schemas.microsoft.com/powershell/2004/04\"><Obj S=\"progress\" RefId=\"0\"><TN RefId=\"0\"><T>System.Management.Automation.PSCustomObject</T></TN></Obj></Objs>\r\n[{\"Index\":7,\"Alias\":\"Wi-Fi\",\"Servers\":[\"192.168.1.1\"],\"Dhcp\":\"Enabled\"}]\r\n"
	as, err := ParseAdapters([]byte(in))
	if err != nil || len(as) != 1 || as[0].Alias != "Wi-Fi" {
		t.Fatalf("CLIXML tidak dibersihkan: %v %+v", err, as)
	}
	as, err = ParseAdapters([]byte("#< CLIXML\n<Objs Version=\"1.1.0.1\"></Objs>\n{\"Index\":3,\"Alias\":\"Ethernet\",\"Servers\":[],\"Dhcp\":\"Disabled\"}"))
	if err != nil || len(as) != 1 || as[0].Index != 3 {
		t.Fatalf("objek tunggal: %v %+v", err, as)
	}
	if as, err = ParseAdapters([]byte("#< CLIXML\n<Objs></Objs>\nnull")); err != nil || as != nil {
		t.Fatalf("null: %v %+v", err, as)
	}
}
