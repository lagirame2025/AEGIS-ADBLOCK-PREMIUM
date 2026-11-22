package core

import (
	"bufio"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"aegis/internal/smart"
)

// Kategori hasil blokir.
const (
	CatAds   = "iklan"
	CatJudol = "judol"
	CatScam  = "scam"
	CatUser  = "aturan-sendiri"
	CatDoH   = "doh"
)

type Verdict struct {
	Blocked bool
	Cat     string
	Why     string // daftar | heuristik | smart | aturan | doh
	Score   int
}

type listSet struct {
	ads, judol, scam, allow, userBlock, userAllow map[string]struct{}
}

var (
	adLabel  = regexp.MustCompile(`^(ads?|adv|adsrv|adserv(er|ice)?s?|adsystem|adtech|adnet(work)?|adtrack(ing)?|adclick|adimg|adx|pagead\d*|pubads|adverts?|advertising|popunder|popads?|telemetry|beacon|pixel)\d{0,3}$`)
	adLabel2 = regexp.MustCompile(`^ads?[-_](server|service|delivery|track|click|serve|api|cdn|img|network|exchange|manager|engine|stat)s?\d{0,3}$`)
	sldSet   = map[string]bool{"co": true, "com": true, "net": true, "org": true, "gov": true, "edu": true, "ac": true, "or": true, "go": true, "web": true, "sch": true, "my": true, "biz": true, "ne": true, "mil": true}
)

const dohCanary = "use-application-dns.net"

var dohHosts = map[string]bool{
	dohCanary: true, "dns.google": true, "dns.google.com": true, "cloudflare-dns.com": true, "mozilla.cloudflare-dns.com": true,
	"one.one.one.one": true, "dns.quad9.net": true, "doh.opendns.com": true, "dns.nextdns.io": true, "doh.dns.sb": true,
	"doh.cleanbrowsing.org": true, "dns.alidns.com": true, "doh.pub": true, "doh.mullvad.net": true, "doh.libredns.gr": true,
	"dns.adguard-dns.com": true, "dns.adguard.com": true, "chrome.cloudflare-dns.com": true, "dns64.dns.google": true,
}

// Domain yang tidak boleh diblokir oleh daftar/heuristik apa pun (Windows tetap bisa update, login, dll.).
const builtinAllow = `googleapis.com gstatic.com github.com githubusercontent.com whatsapp.net whatsapp.com msftconnecttest.com captive.apple.com
detectportal.firefox.com msftncsi.com clients3.google.com time.android.com nmcheck.gnome.org connectivity-check.ubuntu.com
microsoft.com windowsupdate.com windows.com windows.net live.com office.com office365.com microsoftonline.com azure.com sharepoint.com
msedge.net edge.microsoft.com apple.com icloud.com mozilla.org mozilla.com firefox.com cloudflare.com localhost aegis.invalid`

type Filter struct {
	dir      string
	lists    atomic.Pointer[listSet]
	Strict   atomic.Bool
	Smart    atomic.Bool
	BlockDoH atomic.Bool
	paused   atomic.Int64 // unix detik sampai jeda berakhir

	Total, Blocked, CacheHits, Hedged atomic.Int64
	catMu                             sync.Mutex
	catCnt                            map[string]int64
	recMu                             sync.Mutex
	recent                            []Event
}

type Event struct {
	T    time.Time `json:"t"`
	Name string    `json:"name"`
	Cat  string    `json:"cat"`
	Why  string    `json:"why"`
}

func NewFilter(dir string) *Filter {
	f := &Filter{dir: dir, catCnt: map[string]int64{}}
	f.Strict.Store(true)
	f.Smart.Store(true)
	f.BlockDoH.Store(true)
	f.Reload()
	return f
}

func readList(path string) map[string]struct{} {
	m := map[string]struct{}{}
	fh, err := os.Open(path)
	if err != nil {
		return m
	}
	defer fh.Close()
	sc := bufio.NewScanner(fh)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		l := strings.ToLower(strings.TrimSpace(sc.Text()))
		if l == "" || l[0] == '#' {
			continue
		}
		if i := strings.IndexAny(l, " \t"); i > 0 { // format hosts: "0.0.0.0 domain"
			l = strings.TrimSpace(l[i:])
		}
		m[strings.TrimPrefix(l, "*.")] = struct{}{}
	}
	return m
}

// Reload membaca ulang semua daftar dari direktori data (aman dipanggil kapan saja).
func (f *Filter) Reload() {
	ls := &listSet{
		ads:       readList(filepath.Join(f.dir, "blocklist.txt")),
		judol:     readList(filepath.Join(f.dir, "judol.txt")),
		scam:      readList(filepath.Join(f.dir, "scam.txt")),
		userBlock: readList(filepath.Join(f.dir, "custom_block.txt")),
		userAllow: readList(filepath.Join(f.dir, "custom_allow.txt")),
		allow:     map[string]struct{}{},
	}
	for _, a := range strings.Fields(builtinAllow) {
		ls.allow[a] = struct{}{}
	}
	for _, a := range strings.Fields(strings.ReplaceAll(readFileString(filepath.Join(f.dir, "allow.txt")), "\r", "")) {
		if !strings.HasPrefix(a, "#") {
			ls.allow[strings.ToLower(a)] = struct{}{}
		}
	}
	f.lists.Store(ls)
}

func readFileString(p string) string { b, _ := os.ReadFile(p); return string(b) }

func (f *Filter) Sizes() (ads, judol, scam int) {
	l := f.lists.Load()
	return len(l.ads), len(l.judol), len(l.scam)
}

func (f *Filter) Pause(d time.Duration) { f.paused.Store(time.Now().Add(d).Unix()) }
func (f *Filter) Resume()               { f.paused.Store(0) }
func (f *Filter) PausedUntil() int64    { return f.paused.Load() }

func has(m map[string]struct{}, k string) bool { _, ok := m[k]; return ok }

// AddCustom menambah domain ke aturan sendiri (allow=true: izinkan). Menulis file dan memuat ulang.
func (f *Filter) AddCustom(allow bool, domain string) bool {
	d := strings.ToLower(strings.TrimSpace(domain))
	d = strings.TrimPrefix(d, "*.")
	if len(d) < 3 || len(d) > 253 || !strings.Contains(d, ".") || strings.ContainsAny(d, " /\\:@?#\t") {
		return false
	}
	name := "custom_block.txt"
	if allow {
		name = "custom_allow.txt"
	}
	p := filepath.Join(f.dir, name)
	cur := readList(p)
	if _, ok := cur[d]; !ok {
		fh, err := os.OpenFile(p, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
		if err != nil {
			return false
		}
		fh.WriteString(d + "\n")
		fh.Close()
	}
	f.Reload()
	return true
}

func (f *Filter) RemoveCustom(allow bool, domain string) {
	name := "custom_block.txt"
	if allow {
		name = "custom_allow.txt"
	}
	p := filepath.Join(f.dir, name)
	cur := readList(p)
	delete(cur, strings.ToLower(strings.TrimSpace(domain)))
	var sb strings.Builder
	for k := range cur {
		sb.WriteString(k + "\n")
	}
	os.WriteFile(p, []byte(sb.String()), 0o644)
	f.Reload()
}

func (f *Filter) CustomList(allow bool) []string {
	name := "custom_block.txt"
	if allow {
		name = "custom_allow.txt"
	}
	var out []string
	for k := range readList(filepath.Join(f.dir, name)) {
		out = append(out, k)
	}
	return out
}

func adHeuristic(name string) bool {
	labels := strings.Split(name, ".")
	n := len(labels)
	if n < 3 {
		return false
	}
	tail := 2
	if len(labels[n-1]) == 2 && sldSet[labels[n-2]] {
		tail = 3
	}
	for i := 0; i < n-tail; i++ {
		if adLabel.MatchString(labels[i]) || adLabel2.MatchString(labels[i]) {
			return true
		}
	}
	return false
}

// Check memutuskan apakah nama harus diblokir. Allowlist (bawaan/pengguna) menang atas semua daftar.
func (f *Filter) Check(name string) Verdict {
	if time.Now().Unix() < f.paused.Load() {
		return Verdict{}
	}
	d := strings.ToLower(strings.TrimSuffix(name, "."))
	if d == "" {
		return Verdict{}
	}
	ls := f.lists.Load()
	doh := f.BlockDoH.Load()
	cur := d
	var v Verdict
	for {
		if has(ls.userAllow, cur) || has(ls.allow, cur) {
			return Verdict{}
		}
		if !v.Blocked {
			switch {
			case has(ls.userBlock, cur):
				v = Verdict{true, CatUser, "aturan", 100}
			case has(ls.scam, cur):
				v = Verdict{true, CatScam, "daftar", 100}
			case has(ls.judol, cur):
				v = Verdict{true, CatJudol, "daftar", 100}
			case has(ls.ads, cur):
				v = Verdict{true, CatAds, "daftar", 100}
			case doh && dohHosts[cur]:
				v = Verdict{true, CatDoH, "doh", 100}
			}
		}
		i := strings.IndexByte(cur, '.')
		if i < 0 {
			break
		}
		cur = cur[i+1:]
	}
	if v.Blocked {
		return v
	}
	if f.Strict.Load() && adHeuristic(d) {
		return Verdict{true, CatAds, "heuristik", 60}
	}
	if f.Smart.Load() {
		r := smart.AnalyzeHost(d)
		switch {
		case r.Cls == "judol":
			return Verdict{true, CatJudol, "smart", r.Score}
		case r.Cls == "scam" && r.Score >= 80:
			return Verdict{true, CatScam, "smart", r.Score}
		}
	}
	return Verdict{}
}

func (f *Filter) NoteBlocked(name string, v Verdict) {
	f.Blocked.Add(1)
	f.catMu.Lock()
	f.catCnt[v.Cat]++
	f.catMu.Unlock()
	f.recMu.Lock()
	f.recent = append([]Event{{time.Now(), strings.ToLower(strings.TrimSuffix(name, ".")), v.Cat, v.Why}}, f.recent...)
	if len(f.recent) > 300 {
		f.recent = f.recent[:300]
	}
	f.recMu.Unlock()
}

func (f *Filter) Recent(n int) []Event {
	f.recMu.Lock()
	defer f.recMu.Unlock()
	if n > len(f.recent) {
		n = len(f.recent)
	}
	return append([]Event(nil), f.recent[:n]...)
}

func (f *Filter) Categories() map[string]int64 {
	f.catMu.Lock()
	defer f.catMu.Unlock()
	out := map[string]int64{}
	for k, v := range f.catCnt {
		out[k] = v
	}
	return out
}
