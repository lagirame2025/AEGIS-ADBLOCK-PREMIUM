package core

import "strings"

// ---- parsing & pembuatan pesan DNS (port dari Dns.kt Aegis Android) ----

type Question struct {
	Name string
	End  int
	Type uint16
}

func u16(d []byte, p int) int { return int(d[p])<<8 | int(d[p+1]) }

// ParseQuestion membaca pertanyaan pertama; ok=false bila pesan tidak valid.
func ParseQuestion(d []byte) (Question, bool) {
	if len(d) < 17 || u16(d, 4) < 1 {
		return Question{}, false
	}
	p := 12
	var sb strings.Builder
	for p < len(d) {
		l := int(d[p])
		if l == 0 {
			p++
			break
		}
		if l&0xC0 != 0 || p+l >= len(d) {
			return Question{}, false
		}
		if sb.Len() > 0 {
			sb.WriteByte('.')
		}
		sb.Write(d[p+1 : p+1+l])
		p += l + 1
	}
	if p+4 > len(d) {
		return Question{}, false
	}
	return Question{Name: sb.String(), End: p + 4, Type: uint16(u16(d, p))}, true
}

func readName(d []byte, start int) (string, int, bool) {
	p, next, jumps := start, -1, 0
	var sb strings.Builder
	for {
		if p >= len(d) {
			return "", 0, false
		}
		l := int(d[p])
		if l == 0 {
			p++
			break
		}
		if l&0xC0 == 0xC0 {
			if p+1 >= len(d) {
				return "", 0, false
			}
			if next < 0 {
				next = p + 2
			}
			p = (l&0x3F)<<8 | int(d[p+1])
			jumps++
			if jumps > 16 {
				return "", 0, false
			}
			continue
		}
		if l&0xC0 != 0 || p+1+l > len(d) {
			return "", 0, false
		}
		if sb.Len() > 0 {
			sb.WriteByte('.')
		}
		sb.Write(d[p+1 : p+1+l])
		p += l + 1
	}
	if next >= 0 {
		return sb.String(), next, true
	}
	return sb.String(), p, true
}

type Answer struct {
	Rcode     int
	Answers   int
	MinTTL    int64
	CNames    []string
	NegTTL    int64
	Truncated bool
}

func u32(d []byte, p int) int64 { return int64(u16(d, p))<<16 | int64(u16(d, p+2)) }

func ParseAnswer(d []byte) (Answer, bool) {
	var a Answer
	if len(d) < 12 {
		return a, false
	}
	a.Rcode = int(d[3] & 0x0F)
	qd, an, ns := u16(d, 4), u16(d, 6), u16(d, 8)
	p := 12
	for k := 0; k < qd; k++ {
		_, e, ok := readName(d, p)
		if !ok {
			return a, false
		}
		p = e + 4
	}
	a.MinTTL = -1
	for k := 0; k < an; k++ {
		_, e, ok := readName(d, p)
		if !ok {
			return a, false
		}
		p = e
		if p+10 > len(d) {
			return a, false
		}
		typ, ttl, rdlen := u16(d, p), u32(d, p+4), u16(d, p+8)
		p += 10
		if p+rdlen > len(d) {
			return a, false
		}
		if a.MinTTL < 0 || ttl < a.MinTTL {
			a.MinTTL = ttl
		}
		if typ == 5 {
			if t, _, ok := readName(d, p); ok {
				a.CNames = append(a.CNames, t)
			}
		}
		p += rdlen
	}
	if a.MinTTL < 0 {
		a.MinTTL = 0
	}
	a.Answers = an
	for k := 0; k < ns; k++ {
		_, e, ok := readName(d, p)
		if !ok {
			break
		}
		p = e
		if p+10 > len(d) {
			break
		}
		typ, ttl, rdlen := u16(d, p), u32(d, p+4), u16(d, p+8)
		p += 10
		if p+rdlen > len(d) {
			break
		}
		if typ == 6 {
			if _, e2, ok := readName(d, p); ok {
				if _, e3, ok := readName(d, e2); ok && e3+20 <= len(d) {
					min := u32(d, e3+16)
					if ttl < min {
						min = ttl
					}
					a.NegTTL = min
				}
			}
		}
		p += rdlen
	}
	a.Truncated = d[2]&0x02 != 0
	return a, true
}

// ClientUDPSize: ukuran payload UDP yang diiklankan klien lewat EDNS (RR OPT), default 512; dibatasi 1232 (aman MTU).
func ClientUDPSize(q []byte) int {
	if len(q) < 12 {
		return 512
	}
	qd, an, ns, ar := u16(q, 4), u16(q, 6), u16(q, 8), u16(q, 10)
	p := 12
	for k := 0; k < qd; k++ {
		_, e, ok := readName(q, p)
		if !ok {
			return 512
		}
		p = e + 4
	}
	for k := 0; k < an+ns+ar; k++ {
		_, e, ok := readName(q, p)
		if !ok || e+10 > len(q) {
			return 512
		}
		typ, class, rdlen := u16(q, e), u16(q, e+2), u16(q, e+8)
		if typ == 41 {
			if class < 512 {
				return 512
			}
			if class > 1232 {
				return 1232
			}
			return class
		}
		p = e + 10 + rdlen
	}
	return 512
}

func header(q []byte, end int, flags2, flags3 byte) []byte {
	h := make([]byte, end)
	copy(h, q[:end])
	h[2], h[3] = flags2, flags3
	for k := 6; k <= 11; k++ {
		h[k] = 0
	}
	return h
}

// BlockedReply: A=0.0.0.0, AAAA=::, tipe lain: kosong (NOERROR).
func BlockedReply(q []byte, end int, qtype uint16, ttl int) []byte {
	head := header(q, end, 0x81, 0x80)
	var rdlen int
	switch qtype {
	case 1:
		rdlen = 4
	case 28:
		rdlen = 16
	default:
		return head
	}
	head[7] = 1
	ans := make([]byte, 12+rdlen)
	ans[0], ans[1] = 0xC0, 0x0C
	ans[3] = byte(qtype)
	ans[5] = 1
	ans[8], ans[9] = byte(ttl>>8), byte(ttl)
	ans[11] = byte(rdlen)
	return append(head, ans...)
}

// LocalReply menjawab A dengan IPv4 tertentu (dipakai self-test watchdog).
func LocalReply(q []byte, end int, qtype uint16, ip [4]byte) []byte {
	if qtype != 1 {
		return header(q, end, 0x81, 0x80)
	}
	head := header(q, end, 0x81, 0x80)
	head[7] = 1
	ans := []byte{0xC0, 0x0C, 0, 1, 0, 1, 0, 0, 0, 5, 0, 4, ip[0], ip[1], ip[2], ip[3]}
	return append(head, ans...)
}

func NXReply(q []byte, end int) []byte   { return header(q, end, 0x81, 0x83) }
func FailReply(q []byte, end int) []byte { return header(q, end, 0x81, 0x82) }

// TruncatedReply: TC=1 hanya berisi question, agar klien mengulang lewat TCP.
func TruncatedReply(r []byte, end int) []byte {
	if end > len(r) {
		end = len(r)
	}
	if end < 12 {
		end = 12
	}
	h := make([]byte, end)
	copy(h, r[:end])
	h[2] |= 0x82
	for k := 6; k <= 11; k++ {
		h[k] = 0
	}
	return h
}

// WithTTL menyalin balasan dengan TTL tiap RR dibatasi maksimal ttl detik (RR OPT dilewati).
func WithTTL(d []byte, ttl int64) []byte {
	out := make([]byte, len(d))
	copy(out, d)
	if len(d) < 12 {
		return out
	}
	p := 12
	qd := u16(d, 4)
	total := u16(d, 6) + u16(d, 8) + u16(d, 10)
	for k := 0; k < qd; k++ {
		_, e, ok := readName(d, p)
		if !ok {
			return out
		}
		p = e + 4
	}
	for k := 0; k < total; k++ {
		_, e, ok := readName(d, p)
		if !ok {
			return out
		}
		p = e
		if p+10 > len(d) {
			return out
		}
		typ, rdlen := u16(d, p), u16(d, p+8)
		if typ != 41 {
			old := u32(d, p+4)
			nt := old
			if ttl < nt {
				nt = ttl
			}
			out[p+4], out[p+5], out[p+6], out[p+7] = byte(nt>>24), byte(nt>>16), byte(nt>>8), byte(nt)
		}
		p += 10 + rdlen
		if p > len(d) {
			return out
		}
	}
	return out
}

// BuildQuery membuat query DNS sederhana (untuk tes & watchdog).
func BuildQuery(id uint16, name string, qtype uint16) []byte {
	b := []byte{byte(id >> 8), byte(id), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}
	for _, l := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		b = append(b, byte(len(l)))
		b = append(b, l...)
	}
	return append(b, 0, byte(qtype>>8), byte(qtype), 0, 1)
}
