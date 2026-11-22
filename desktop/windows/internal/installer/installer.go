// Package installer: pasang/copot Aegis di Windows (dipakai AegisSetup.exe dan `aegis.exe uninstall`).
package installer

import (
	"archive/zip"
	"bytes"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"aegis/internal/core"
	"aegis/internal/sysnet"
)

const Version = "1.8.1"

type Options struct {
	InstallDir string `json:"installDir"`
	DataDir    string `json:"dataDir"`
	Failsafe   bool   `json:"failsafe"`  // DNS cadangan di adaptor bila Aegis berhenti
	DoHPolicy  bool   `json:"dohPolicy"` // matikan DoH di Chrome/Edge/Brave lewat kebijakan
	DNSAddr    string `json:"-"`         // alamat tes DNS (default 127.0.0.1:53)
	WebAddr    string `json:"-"`         // alamat tes dasbor (default 127.0.0.1:8053)
	SkipTask   bool   `json:"-"`         // tes: jangan jalankan task (proses diurus pemanggil)
}

func DefaultOptions() Options {
	o := Options{InstallDir: sysnet.DefaultInstallDir(), DataDir: sysnet.DefaultDataDir(), Failsafe: true, DoHPolicy: true,
		DNSAddr: "127.0.0.1:53", WebAddr: "127.0.0.1:8053"}
	if sysnet.Simulated { // hanya untuk pengembangan/tes di OS non-Windows
		if v := os.Getenv("AEGIS_DNS_ADDR"); v != "" {
			o.DNSAddr = v
		}
		if v := os.Getenv("AEGIS_WEB_ADDR"); v != "" {
			o.WebAddr = v
		}
	}
	return o
}

// Payload: berkas yang dibawa installer.
type Payload struct {
	Exe    []byte
	Extras map[string][]byte // nama berkas (mis. "ext-chromium.zip") -> isi
}

func unzipTo(data []byte, dest string) error {
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return err
	}
	base := filepath.Clean(dest) + string(os.PathSeparator)
	for _, f := range zr.File {
		p := filepath.Join(dest, f.Name)
		if !strings.HasPrefix(p, base) { // zip-slip
			return errors.New("zip tidak aman: " + f.Name)
		}
		if f.FileInfo().IsDir() {
			os.MkdirAll(p, 0o755)
			continue
		}
		os.MkdirAll(filepath.Dir(p), 0o755)
		rc, err := f.Open()
		if err != nil {
			return err
		}
		out, err := os.Create(p)
		if err != nil {
			rc.Close()
			return err
		}
		_, err = io.Copy(out, rc)
		out.Close()
		rc.Close()
		if err != nil {
			return err
		}
	}
	return nil
}

func writeRetry(path string, data []byte) error {
	var err error
	for i := 0; i < 8; i++ {
		if err = os.WriteFile(path, data, 0o755); err == nil {
			return nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	return err
}

// SelfTestDNS memastikan Aegis menjawab query (sebelum DNS sistem diarahkan ke sana).
func SelfTestDNS(addr string) error {
	var last error
	for i := 0; i < 20; i++ {
		c, err := net.DialTimeout("udp", addr, time.Second)
		if err == nil {
			c.SetDeadline(time.Now().Add(1500 * time.Millisecond))
			c.Write(core.BuildQuery(0x5A5A, core.SelfTestName, 1))
			b := make([]byte, 1500)
			n, rerr := c.Read(b)
			c.Close()
			if rerr == nil && n >= 12 && b[0] == 0x5A && b[1] == 0x5A {
				return nil
			}
			last = rerr
		} else {
			last = err
		}
		time.Sleep(500 * time.Millisecond)
	}
	if last == nil {
		last = errors.New("tidak ada jawaban")
	}
	return last
}

func healthy(web string) bool {
	c := http.Client{Timeout: 2 * time.Second}
	r, err := c.Get("http://" + web + "/api/health")
	if err != nil {
		return false
	}
	r.Body.Close()
	return r.StatusCode == 200
}

func Install(o Options, p Payload, say func(string)) error {
	if o.DNSAddr == "" {
		o.DNSAddr = "127.0.0.1:53"
	}
	if o.WebAddr == "" {
		o.WebAddr = "127.0.0.1:8053"
	}
	if len(p.Exe) < 1024 {
		return errors.New("berkas program tidak ada di installer")
	}
	step := func(s string) { say(s) }
	step("Menyiapkan folder…")
	for _, d := range []string{o.InstallDir, o.DataDir, filepath.Join(o.InstallDir, "extensions")} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return fmt.Errorf("buat folder %s: %w", d, err)
		}
	}
	step("Menghentikan versi lama (bila ada)…")
	sysnet.StopAll()
	exe := filepath.Join(o.InstallDir, "aegis.exe")
	step("Menyalin program…")
	if err := writeRetry(exe, p.Exe); err != nil {
		return fmt.Errorf("salin aegis.exe: %w", err)
	}
	step("Menyalin ekstensi browser…")
	for name, data := range p.Extras {
		os.WriteFile(filepath.Join(o.InstallDir, "extensions", name), data, 0o644)
		if strings.HasPrefix(name, "ext-") && strings.HasSuffix(name, ".zip") {
			dest := filepath.Join(o.InstallDir, "extensions", strings.TrimSuffix(strings.TrimPrefix(name, "ext-"), ".zip"))
			os.RemoveAll(dest)
			if err := unzipTo(data, dest); err != nil {
				say("peringatan: ekstrak " + name + ": " + err.Error())
			}
		}
	}
	step("Membaca DNS asli jaringan (untuk cadangan & pemulihan)…")
	as, err := sysnet.BackupAdapters(o.DataDir)
	if err != nil {
		return err
	}
	orig := sysnet.OriginalIPv4(as)
	os.WriteFile(filepath.Join(o.DataDir, sysnet.UpstreamsTx), []byte(strings.Join(orig, "\n")+"\n"), 0o644)
	if !o.SkipTask {
		step("Mendaftarkan layanan otomatis (jalan saat Windows menyala)…")
		if err := sysnet.InstallTask(o.DataDir, exe); err != nil {
			return err
		}
		if err := sysnet.StartTask(); err != nil {
			return err
		}
	}
	step("Menguji filter DNS lokal…")
	if err := SelfTestDNS(o.DNSAddr); err != nil {
		return fmt.Errorf("Aegis tidak menjawab DNS (%v). DNS sistem TIDAK diubah, internet Anda aman. Penyebab umum: port 53 dipakai program lain (mis. ICS/Hyper-V/Docker)", err)
	}
	fs := ""
	if o.Failsafe {
		fs = sysnet.FailsafeIP(orig)
	}
	step("Mengarahkan DNS Windows ke Aegis…")
	if err := sysnet.PointDNS(as, fs); err != nil {
		return err
	}
	if o.DoHPolicy {
		step("Mematikan DNS-over-HTTPS di browser (agar tidak melewati filter)…")
		sysnet.SetDoHPolicy(true)
	}
	step("Mendaftarkan di Pengaturan Windows & Start Menu…")
	sysnet.AddUninstallEntry(o.InstallDir, exe, Version)
	sm := sysnet.StartMenuDir()
	os.MkdirAll(sm, 0o755)
	os.WriteFile(filepath.Join(sm, "Dasbor Aegis.url"), []byte("[InternetShortcut]\r\nURL=http://"+o.WebAddr+"/\r\n"), 0o644)
	if !healthy(o.WebAddr) && !o.SkipTask {
		say("peringatan: dasbor belum merespons; coba buka beberapa detik lagi")
	}
	step("Selesai.")
	return nil
}

func Uninstall(o Options, say func(string)) error {
	say("Memulihkan DNS Windows…")
	if err := sysnet.RestoreDNS(o.DataDir); err != nil {
		say("peringatan: " + err.Error())
	}
	say("Menghapus kebijakan DoH…")
	sysnet.SetDoHPolicy(false)
	say("Menghentikan layanan…")
	sysnet.StopAll()
	sysnet.RemoveTask()
	sysnet.RemoveUninstallEntry()
	os.RemoveAll(sysnet.StartMenuDir())
	os.RemoveAll(o.DataDir)
	sysnet.SelfDelete(o.InstallDir)
	say("Selesai. Aegis dicopot.")
	return nil
}
