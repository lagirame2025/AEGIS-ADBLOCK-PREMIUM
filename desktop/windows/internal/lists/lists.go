// Package lists: mengunduh, menormalkan, dan menyimpan daftar blokir (iklan, judol, scam).
package lists

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type Source struct{ Name, URL, File string }

// File tujuan: blocklist.txt (iklan/pelacak), judol.txt (judi online), scam.txt (phishing/malware/penipuan).
var Sources = []Source{
	{"StevenBlack", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", "blocklist.txt"},
	{"AdGuard DNS", "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt", "blocklist.txt"},
	{"AdAway", "https://adaway.org/hosts.txt", "blocklist.txt"},
	{"HaGeZi Pro", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt", "blocklist.txt"},
	{"HaGeZi Pop-up Ads", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/popupads.txt", "blocklist.txt"},
	{"HaGeZi Gambling", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/gambling.txt", "judol.txt"},
	{"HaGeZi Fake/Scam", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/fake.txt", "scam.txt"},
	{"HaGeZi Threat Intel", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/tif.mini.txt", "scam.txt"},
	{"URLhaus (malware)", "https://urlhaus.abuse.ch/downloads/hostfile/", "scam.txt"},
	{"Phishing Army", "https://phishing.army/download/phishing_army_blocklist_extended.txt", "scam.txt"},
}

var skipNames = map[string]bool{"localhost": true, "localhost.localdomain": true, "local": true, "broadcasthost": true,
	"ip6-localhost": true, "ip6-loopback": true, "0.0.0.0": true, "127.0.0.1": true, "::1": true}

func validDomain(d string) bool {
	if len(d) < 4 || len(d) > 253 || !strings.Contains(d, ".") || skipNames[d] {
		return false
	}
	if d[0] == '.' || d[0] == '-' || d[len(d)-1] == '.' || d[len(d)-1] == '-' {
		return false
	}
	allDigit := true
	for i := 0; i < len(d); i++ {
		c := d[i]
		switch {
		case c >= 'a' && c <= 'z', c == '-', c == '_':
			allDigit = false
		case c >= '0' && c <= '9', c == '.':
		default:
			return false
		}
	}
	return !allDigit // buang alamat IP
}

// Parse membaca format hosts ("0.0.0.0 domain"), satu-domain-per-baris, dan AdGuard/ABP ("||domain^").
func Parse(r io.Reader) []string {
	var out []string
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		l := strings.TrimSpace(sc.Text())
		if l == "" || l[0] == '#' || l[0] == '!' || l[0] == '[' || strings.HasPrefix(l, "@@") {
			continue
		}
		if i := strings.Index(l, " #"); i > 0 {
			l = strings.TrimSpace(l[:i])
		}
		if strings.HasPrefix(l, "||") {
			l = l[2:]
			end := strings.IndexAny(l, "^/$|*")
			if end < 0 {
				continue
			}
			if l[end] != '^' || (end+1 < len(l) && strings.ContainsAny(l[end+1:], "/*")) {
				continue
			}
			if rest := l[end+1:]; rest != "" && !strings.HasPrefix(rest, "$") {
				continue
			}
			l = l[:end]
			if d := strings.ToLower(l); validDomain(d) {
				out = append(out, d)
			}
			continue
		}
		f := strings.Fields(l)
		if len(f) == 0 {
			continue
		}
		if len(f) >= 2 && (f[0] == "0.0.0.0" || f[0] == "127.0.0.1" || f[0] == "::" || f[0] == "::1") {
			for _, d := range f[1:] {
				if d = strings.ToLower(strings.TrimPrefix(d, "*.")); validDomain(d) {
					out = append(out, d)
				}
			}
			continue
		}
		if len(f) == 1 {
			tok := strings.TrimPrefix(f[0], "*.")
			if !strings.ContainsAny(tok, "/:*^$|") {
				if d := strings.ToLower(tok); validDomain(d) {
					out = append(out, d)
				}
			}
		}
	}
	return out
}

type Report struct {
	Time    time.Time         `json:"time"`
	Counts  map[string]int    `json:"counts"`  // per file
	Sources map[string]string `json:"sources"` // nama -> "ok N" | error
	Kept    []string          `json:"kept"`    // file yang dipertahankan karena unduhan menyusut/gagal
}

func countLines(path string) int {
	f, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()
	n := 0
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		if t := strings.TrimSpace(sc.Text()); t != "" && t[0] != '#' {
			n++
		}
	}
	return n
}

func writeAtomic(path string, set map[string]struct{}) error {
	keys := make([]string, 0, len(set))
	for k := range set {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	tmp := path + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	w := bufio.NewWriterSize(f, 1<<20)
	for _, k := range keys {
		w.WriteString(k)
		w.WriteByte('\n')
	}
	if err := w.Flush(); err != nil {
		f.Close()
		return err
	}
	f.Close()
	return os.Rename(tmp, path)
}

// Update mengunduh semua sumber dan menulis ulang berkas daftar. Berkas lama dipertahankan bila hasil baru
// menyusut >50% (sumber rusak/diblokir) supaya perlindungan tidak turun diam-diam.
func Update(dir string, client *http.Client, sources []Source, logf func(string, ...interface{})) Report {
	if client == nil {
		client = &http.Client{Timeout: 90 * time.Second}
	}
	rep := Report{Time: time.Now(), Counts: map[string]int{}, Sources: map[string]string{}}
	sets := map[string]map[string]struct{}{}
	for _, s := range sources {
		req, _ := http.NewRequest("GET", s.URL, nil)
		req.Header.Set("User-Agent", "AegisAdblock/1.8")
		var doms []string
		var err error
		for try := 0; try < 3; try++ {
			var resp *http.Response
			resp, err = client.Do(req)
			if err == nil {
				if resp.StatusCode != 200 {
					err = fmt.Errorf("HTTP %d", resp.StatusCode)
				} else {
					doms = Parse(io.LimitReader(resp.Body, 80<<20))
				}
				resp.Body.Close()
			}
			if err == nil {
				break
			}
			time.Sleep(time.Duration(try+1) * 2 * time.Second)
		}
		if err != nil || len(doms) == 0 {
			if err == nil {
				err = fmt.Errorf("kosong")
			}
			rep.Sources[s.Name] = "gagal: " + err.Error()
			logf("sumber %s gagal: %v", s.Name, err)
			continue
		}
		if sets[s.File] == nil {
			sets[s.File] = map[string]struct{}{}
		}
		for _, d := range doms {
			sets[s.File][d] = struct{}{}
		}
		rep.Sources[s.Name] = fmt.Sprintf("ok %d", len(doms))
	}
	for file, set := range sets {
		p := filepath.Join(dir, file)
		old := countLines(p)
		if old > 100 && len(set)*2 < old {
			rep.Kept = append(rep.Kept, file)
			rep.Counts[file] = old
			logf("%s: hasil baru (%d) menyusut jauh dari lama (%d), dipertahankan", file, len(set), old)
			continue
		}
		if err := writeAtomic(p, set); err != nil {
			logf("tulis %s gagal: %v", file, err)
			continue
		}
		rep.Counts[file] = len(set)
	}
	if b, err := json.MarshalIndent(rep, "", " "); err == nil {
		os.WriteFile(filepath.Join(dir, "lists.json"), b, 0o644)
	}
	return rep
}

// Age: umur pembaruan terakhir (besar bila belum pernah).
func Age(dir string) time.Duration {
	b, err := os.ReadFile(filepath.Join(dir, "lists.json"))
	if err != nil {
		return 1000 * time.Hour
	}
	var r Report
	if json.Unmarshal(b, &r) != nil {
		return 1000 * time.Hour
	}
	return time.Since(r.Time)
}

func Last(dir string) *Report {
	b, err := os.ReadFile(filepath.Join(dir, "lists.json"))
	if err != nil {
		return nil
	}
	var r Report
	if json.Unmarshal(b, &r) != nil {
		return nil
	}
	return &r
}
