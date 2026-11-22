package core

import (
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func encName(n string) []byte {
	var b []byte
	for _, l := range strings.Split(n, ".") {
		b = append(b, byte(len(l)))
		b = append(b, l...)
	}
	return append(b, 0)
}

// fakeDNS: A apa pun -> 1.2.3.4; "cloak.site.test" -> CNAME tracker.ads.test.
func fakeDNS(t *testing.T) (*net.UDPConn, int) {
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := c.ReadFromUDP(buf)
			if err != nil {
				return
			}
			q := append([]byte(nil), buf[:n]...)
			qq, ok := ParseQuestion(q)
			if !ok {
				continue
			}
			r := append([]byte(nil), q[:qq.End]...)
			r[2], r[3], r[7] = 0x81, 0x80, 1
			var rr []byte
			if qq.Name == "cloak.site.test" {
				tgt := encName("tracker.ads.test")
				rr = append([]byte{0xC0, 0x0C, 0, 5, 0, 1, 0, 0, 0, 60, 0, byte(len(tgt))}, tgt...)
			} else {
				rr = []byte{0xC0, 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 1, 2, 3, 4}
			}
			c.WriteToUDP(append(r, rr...), from)
		}
	}()
	return c, c.LocalAddr().(*net.UDPAddr).Port
}

func TestEngineEndToEnd(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "blocklist.txt"), []byte("0.0.0.0 ads.example.test\ntracker.ads.test\n"), 0o644)
	os.WriteFile(filepath.Join(dir, "custom_allow.txt"), []byte("allowed.ads.example.test\n"), 0o644)
	f := NewFilter(dir)
	up, port := fakeDNS(t)
	defer up.Close()
	r := NewResolver(nil)
	u := &Upstream{Host: "127.0.0.1", Addr: &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}, ewma: 5}
	r.mu.Lock()
	r.sys, r.base = []*Upstream{u}, nil
	r.mu.Unlock()
	e := NewEngine(f, r)

	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	go e.ServeUDP(pc)

	ask := func(name string) []byte {
		c, _ := net.Dial("udp4", pc.LocalAddr().String())
		defer c.Close()
		c.SetDeadline(time.Now().Add(4 * time.Second))
		c.Write(BuildQuery(0xAB12, name, 1))
		b := make([]byte, 1500)
		n, err := c.Read(b)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		return b[:n]
	}
	last4 := func(b []byte) [4]byte { var o [4]byte; copy(o[:], b[len(b)-4:]); return o }

	if got := last4(ask("sub.ads.example.test")); got != [4]byte{0, 0, 0, 0} {
		t.Errorf("daftar tidak memblokir: %v", got)
	}
	if got := last4(ask("allowed.ads.example.test")); got != [4]byte{1, 2, 3, 4} {
		t.Errorf("izin pengguna harus menang: %v", got)
	}
	if got := last4(ask("www.normal.test")); got != [4]byte{1, 2, 3, 4} {
		t.Errorf("domain normal: %v", got)
	}
	ask("www.normal.test")
	if f.CacheHits.Load() < 1 {
		t.Errorf("cache tidak dipakai")
	}
	if got := last4(ask("cloak.site.test")); got != [4]byte{0, 0, 0, 0} {
		t.Errorf("CNAME cloaking lolos: %v", got)
	}
	if got := last4(ask("slot-gacor-maxwin.xyz")); got != [4]byte{0, 0, 0, 0} {
		t.Errorf("judol tidak diblokir smart: %v", got)
	}
	if got := last4(ask("ads.situs.test")); got != [4]byte{0, 0, 0, 0} {
		t.Errorf("heuristik ads.* lolos: %v", got)
	}
	if b := ask("use-application-dns.net"); b[3]&0x0F != 3 {
		t.Errorf("canary harus NXDOMAIN, rcode=%d", b[3]&0x0F)
	}
	cats := f.Categories()
	if cats[CatJudol] < 1 || cats[CatAds] < 2 {
		t.Errorf("kategori salah: %v", cats)
	}
}

func TestHedgeFastBackup(t *testing.T) {
	slow, _ := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)}) // diam: tidak menjawab
	defer slow.Close()
	fast, port := fakeDNS(t)
	defer fast.Close()
	r := NewResolver(nil)
	us := &Upstream{Host: "s", Addr: slow.LocalAddr().(*net.UDPAddr), ewma: 10}
	uf := &Upstream{Host: "f", Addr: &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}, ewma: 50}
	r.mu.Lock()
	r.sys, r.base = []*Upstream{us, uf}, nil
	r.mu.Unlock()
	t0 := time.Now()
	resp := r.QueryUDP(BuildQuery(7, "a.b.test", 1))
	if resp == nil || time.Since(t0) > 700*time.Millisecond {
		t.Fatalf("hedge gagal: resp=%v dur=%v", resp != nil, time.Since(t0))
	}
	if r.Hedged.Load() != 1 {
		t.Errorf("hedge tidak tercatat")
	}
}
