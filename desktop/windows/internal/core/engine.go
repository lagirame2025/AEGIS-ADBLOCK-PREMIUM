package core

import (
	"encoding/binary"
	"io"
	"net"
	"strings"
	"sync"
	"time"
)

const (
	cloakTTL   = 10 * time.Minute
	staleFor   = time.Hour
	maxUDPWork = 512
)

// SelfTestName dijawab langsung oleh Aegis (dipakai installer & watchdog).
const SelfTestName = "selftest.aegis.invalid"

var blockMark = []byte{1}

// Engine: filter -> cache -> upstream -> deteksi CNAME cloaking. Inti yang sama dengan versi Android.
type Engine struct {
	F *Filter
	C *Cache
	R *Resolver

	fl sync.Map // key -> *flight
}

type flight struct {
	done chan struct{}
	res  []byte
}

func NewEngine(f *Filter, r *Resolver) *Engine { return &Engine{F: f, C: NewCache(8000), R: r} }

// Answer menjawab satu pesan DNS. nil bila pesan tidak valid.
func (e *Engine) Answer(q []byte, tcp bool) []byte {
	qq, ok := ParseQuestion(q)
	if !ok {
		return nil
	}
	if qq.Type == 1 && strings.EqualFold(qq.Name, SelfTestName) { // uji kesehatan lokal, tidak butuh internet
		return LocalReply(q, qq.End, qq.Type, [4]byte{127, 0, 0, 1})
	}
	e.F.Total.Add(1)
	if v := e.F.Check(qq.Name); v.Blocked {
		return e.blockReply(q, qq, v)
	}
	return e.resolve(q, qq, tcp)
}

func (e *Engine) blockReply(q []byte, qq Question, v Verdict) []byte {
	e.F.NoteBlocked(qq.Name, v)
	if strings.EqualFold(qq.Name, dohCanary) {
		return NXReply(q, qq.End)
	}
	return BlockedReply(q, qq.End, qq.Type, 120)
}

func setID(r, q []byte) []byte {
	out := append([]byte(nil), r...)
	out[0], out[1] = q[0], q[1]
	return out
}

func (e *Engine) resolve(q []byte, qq Question, tcp bool) []byte {
	key := strings.ToLower(qq.Name) + "/" + itoa(int(qq.Type))
	if e.C.Get("B/"+key) != nil {
		return e.blockReply(q, qq, Verdict{true, CatAds, "cname", 100})
	}
	if hit := e.C.Get(key); hit != nil {
		e.F.CacheHits.Add(1)
		r := setID(WithTTL(hit.Data, hit.TTLLeft), q)
		if hit.Stale || hit.Prefetch {
			cp := append([]byte(nil), q...)
			go e.fetchOnce(key, cp, qq, false)
		}
		return r
	}
	return e.fetchOnce(key, q, qq, tcp)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b [12]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	return string(b[i:])
}

// fetchOnce: query identik yang sedang berjalan digabung (singleflight).
func (e *Engine) fetchOnce(key string, q []byte, qq Question, tcp bool) []byte {
	fk := key
	if tcp {
		fk += "|t"
	}
	mine := &flight{done: make(chan struct{})}
	if prior, loaded := e.fl.LoadOrStore(fk, mine); loaded {
		p := prior.(*flight)
		select {
		case <-p.done:
		case <-time.After(3500 * time.Millisecond):
		}
		if p.res != nil {
			return setID(p.res, q)
		}
		return e.queryUpstreams(key, q, qq, tcp)
	}
	defer func() { e.fl.Delete(fk); close(mine.done) }()
	r := e.queryUpstreams(key, q, qq, tcp)
	mine.res = r
	return r
}

func clamp(d, lo, hi time.Duration) time.Duration {
	if d < lo {
		return lo
	}
	if d > hi {
		return hi
	}
	return d
}

func (e *Engine) queryUpstreams(key string, q []byte, qq Question, tcp bool) []byte {
	var r []byte
	if tcp {
		r = e.R.QueryTCP(q)
	} else {
		r = e.R.QueryUDP(q)
	}
	if r == nil {
		return FailReply(q, qq.End)
	}
	a, ok := ParseAnswer(r)
	if !ok {
		return r
	}
	if len(a.CNames) > 0 && !e.F.IsAllowed(qq.Name) {
		for _, c := range a.CNames {
			if v := e.F.CheckList(c); v.Blocked {
				e.C.Put("B/"+key, blockMark, cloakTTL, 0)
				v.Why = "cname"
				return e.blockReply(q, qq, v)
			}
		}
	}
	if !a.Truncated && !(tcp && len(r) > 512) {
		if a.Rcode == 0 && a.Answers > 0 {
			e.C.Put(key, r, clamp(time.Duration(a.MinTTL)*time.Second, 30*time.Second, 15*time.Minute), staleFor)
		} else if a.Rcode == 3 || (a.Rcode == 0 && a.Answers == 0) {
			nt := time.Duration(a.NegTTL) * time.Second
			if a.NegTTL <= 0 {
				nt = 30 * time.Second
			}
			e.C.Put(key, r, clamp(nt, 10*time.Second, 5*time.Minute), 0)
		}
	}
	return r
}

// ---------- server ----------

// ServeUDP melayani DNS UDP pada pc sampai ditutup.
func (e *Engine) ServeUDP(pc net.PacketConn) {
	sem := make(chan struct{}, maxUDPWork)
	buf := make([]byte, 4096)
	for {
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			if isTimeout(err) {
				continue
			}
			if strings.Contains(err.Error(), "closed") {
				return
			}
			time.Sleep(5 * time.Millisecond) // mis. ICMP reset di Windows: lanjut
			continue
		}
		q := append([]byte(nil), buf[:n]...)
		select {
		case sem <- struct{}{}:
		default:
			continue // banjir: buang, klien akan mengulang
		}
		go func() {
			defer func() { <-sem; recover() }()
			r := e.Answer(q, false)
			if r == nil {
				return
			}
			if len(r) > ClientUDPSize(q) {
				if qq, ok := ParseQuestion(q); ok {
					r = TruncatedReply(r, qq.End)
				}
			}
			pc.WriteTo(r, from)
		}()
	}
}

// ServeTCP melayani DNS-over-TCP pada ln sampai ditutup.
func (e *Engine) ServeTCP(ln net.Listener) {
	for {
		c, err := ln.Accept()
		if err != nil {
			if strings.Contains(err.Error(), "closed") {
				return
			}
			time.Sleep(5 * time.Millisecond)
			continue
		}
		go func() {
			defer func() { recover(); c.Close() }()
			for {
				c.SetReadDeadline(time.Now().Add(15 * time.Second))
				var lb [2]byte
				if _, err := io.ReadFull(c, lb[:]); err != nil {
					return
				}
				n := int(binary.BigEndian.Uint16(lb[:]))
				if n < 12 {
					return
				}
				q := make([]byte, n)
				if _, err := io.ReadFull(c, q); err != nil {
					return
				}
				r := e.Answer(q, true)
				if r == nil {
					return
				}
				out := make([]byte, 2+len(r))
				binary.BigEndian.PutUint16(out, uint16(len(r)))
				copy(out[2:], r)
				c.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if _, err := c.Write(out); err != nil {
					return
				}
			}
		}()
	}
}
