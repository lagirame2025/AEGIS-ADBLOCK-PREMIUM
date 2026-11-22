package core

import (
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Upstream: satu server DNS dengan skor kesehatan (latensi EWMA + bias + penalti gagal), port dari AdBlockVpnService.kt.
type Upstream struct {
	Host string
	Addr *net.UDPAddr
	Bias float64

	mu       sync.Mutex
	ewma     float64
	fails    int
	lastFail time.Time
}

func NewUpstream(host string, bias, e0 float64) (*Upstream, error) {
	ip := net.ParseIP(strings.TrimSpace(host))
	if ip == nil || ip.To4() == nil {
		return nil, errors.New("bukan IPv4: " + host)
	}
	return &Upstream{Host: ip.String(), Addr: &net.UDPAddr{IP: ip.To4(), Port: 53}, Bias: bias, ewma: e0}, nil
}

func (u *Upstream) Score(now time.Time) float64 {
	u.mu.Lock()
	defer u.mu.Unlock()
	if u.fails > 0 && now.Sub(u.lastFail) > 30*time.Second {
		u.fails = 0
	}
	return u.ewma + u.Bias + float64(u.fails)*400
}
func (u *Upstream) EWMA() float64 { u.mu.Lock(); defer u.mu.Unlock(); return u.ewma }
func (u *Upstream) OK(d time.Duration) {
	u.mu.Lock()
	u.ewma = u.ewma*0.8 + float64(d.Milliseconds())*0.2
	u.fails = 0
	u.mu.Unlock()
}

// Slow: kalah dari hedge -> naikkan latensi saja, jangan dihukum sebagai gagal.
func (u *Upstream) Slow(d time.Duration) {
	u.mu.Lock()
	u.ewma = u.ewma*0.8 + float64(d.Milliseconds())*0.2
	u.mu.Unlock()
}
func (u *Upstream) Fail() {
	u.mu.Lock()
	if u.fails < 5 {
		u.fails++
	}
	u.lastFail = time.Now()
	u.mu.Unlock()
}

// Resolver memilih upstream terbaik: DNS asli jaringan (disimpan installer) diutamakan agar CDN memberi server
// terdekat, lalu Cloudflare/Google/AdGuard/Quad9 sebagai cadangan.
type Resolver struct {
	mu     sync.RWMutex
	sys    []*Upstream
	base   []*Upstream
	Hedged atomic.Int64
}

func NewResolver(sysHosts []string) *Resolver {
	r := &Resolver{}
	for _, b := range []struct {
		h    string
		bias float64
		e0   float64
	}{{"1.1.1.1", 10, 70}, {"8.8.8.8", 15, 70}, {"94.140.14.14", 60, 80}, {"9.9.9.9", 80, 80}} {
		if u, err := NewUpstream(b.h, b.bias, b.e0); err == nil {
			r.base = append(r.base, u)
		}
	}
	r.SetSystem(sysHosts)
	return r
}

// SetSystem mengganti daftar DNS asli; skor upstream yang sama dipertahankan. Alamat loopback diabaikan (cegah loop).
func (r *Resolver) SetSystem(hosts []string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	old := map[string]*Upstream{}
	for _, u := range r.sys {
		old[u.Host] = u
	}
	var ns []*Upstream
	seen := map[string]bool{}
	for _, h := range hosts {
		ip := net.ParseIP(strings.TrimSpace(h))
		if ip == nil || ip.To4() == nil || ip.IsLoopback() || ip.IsUnspecified() || seen[ip.String()] {
			continue
		}
		seen[ip.String()] = true
		if u, ok := old[ip.String()]; ok {
			ns = append(ns, u)
		} else if u, err := NewUpstream(ip.String(), 0, 60); err == nil {
			ns = append(ns, u)
		}
	}
	r.sys = ns
}

func (r *Resolver) pick() []*Upstream {
	r.mu.RLock()
	all := make([]*Upstream, 0, len(r.sys)+len(r.base))
	all = append(all, r.sys...)
	for _, b := range r.base {
		dup := false
		for _, s := range r.sys {
			if s.Host == b.Host {
				dup = true
			}
		}
		if !dup {
			all = append(all, b)
		}
	}
	r.mu.RUnlock()
	now := time.Now()
	sc := make(map[*Upstream]float64, len(all))
	for _, u := range all {
		sc[u] = u.Score(now)
	}
	sort.SliceStable(all, func(i, j int) bool { return sc[all[i]] < sc[all[j]] })
	if len(all) > 4 {
		all = all[:4]
	}
	return all
}

// Health mengembalikan ringkasan upstream untuk dasbor.
func (r *Resolver) Health() []map[string]interface{} {
	var out []map[string]interface{}
	for _, u := range r.pick() {
		out = append(out, map[string]interface{}{"host": u.Host, "ms": int(u.EWMA())})
	}
	return out
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

// QueryUDP: balapan dengan hedging memakai satu socket (kirim ke tercepat, bila lambat ~1,6x latensi kirim ke berikutnya).
func (r *Resolver) QueryUDP(q []byte) []byte {
	ups := r.pick()
	if len(ups) == 0 || len(q) < 12 {
		return nil
	}
	pc, err := net.ListenPacket("udp4", ":0")
	if err != nil {
		return nil
	}
	defer pc.Close()
	var used []*Upstream
	var starts []time.Time
	launched := 0
	launch := func() bool {
		u := ups[launched]
		launched++
		if _, err := pc.WriteTo(q, u.Addr); err != nil {
			u.Fail()
			return false
		}
		used = append(used, u)
		starts = append(starts, time.Now())
		return true
	}
	t0 := time.Now()
	deadline := t0.Add(3 * time.Second)
	launch()
	h := time.Duration(ups[0].EWMA()*1.6) * time.Millisecond
	if h < 120*time.Millisecond {
		h = 120 * time.Millisecond
	} else if h > 600*time.Millisecond {
		h = 600 * time.Millisecond
	}
	hedgeAt := t0.Add(h)
	buf := make([]byte, 4096)
	for {
		now := time.Now()
		if !now.Before(deadline) {
			break
		}
		if (!now.Before(hedgeAt) || len(used) == 0) && launched < len(ups) {
			if launch() && len(used) > 1 {
				r.Hedged.Add(1)
			}
			hedgeAt = now.Add(400 * time.Millisecond)
			continue
		}
		if len(used) == 0 {
			break
		}
		next := deadline
		if launched < len(ups) && hedgeAt.Before(deadline) {
			next = hedgeAt
		}
		pc.SetReadDeadline(next)
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			continue // timeout -> loop memeriksa hedge/deadline; ICMP unreachable (Windows) diabaikan
		}
		if n < 12 || buf[0] != q[0] || buf[1] != q[1] {
			continue
		}
		ua, ok := from.(*net.UDPAddr)
		if !ok {
			continue
		}
		for i, u := range used {
			if u.Addr.IP.Equal(ua.IP) && ua.Port == u.Addr.Port {
				done := time.Now()
				u.OK(done.Sub(starts[i]))
				if i > 0 {
					used[0].Slow(done.Sub(starts[0]))
				}
				return append([]byte(nil), buf[:n]...)
			}
		}
	}
	for _, u := range used {
		u.Fail()
	}
	return nil
}

// QueryTCP: lewat TCP (RFC 7766) supaya balasan besar tidak terpotong.
func (r *Resolver) QueryTCP(q []byte) []byte {
	ups := r.pick()
	if len(ups) > 3 {
		ups = ups[:3]
	}
	for _, u := range ups {
		t0 := time.Now()
		c, err := net.DialTimeout("tcp4", u.Addr.String(), 2*time.Second)
		if err != nil {
			u.Fail()
			continue
		}
		c.SetDeadline(time.Now().Add(3 * time.Second))
		msg := make([]byte, 2+len(q))
		binary.BigEndian.PutUint16(msg, uint16(len(q)))
		copy(msg[2:], q)
		var resp []byte
		if _, err = c.Write(msg); err == nil {
			var lb [2]byte
			if _, err = io.ReadFull(c, lb[:]); err == nil {
				resp = make([]byte, binary.BigEndian.Uint16(lb[:]))
				_, err = io.ReadFull(c, resp)
			}
		}
		c.Close()
		if err == nil && len(resp) >= 12 && resp[0] == q[0] && resp[1] == q[1] {
			u.OK(time.Since(t0))
			return resp
		}
		u.Fail()
	}
	return nil
}
