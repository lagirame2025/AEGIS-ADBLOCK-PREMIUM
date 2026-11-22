// Package smart: penilai risiko host (judi online, scam/phishing, penyamaran merek).
// Algoritma IDENTIK dengan extension/src/smart.js; dijaga oleh shared/vectors.json (lihat smart_test.go).
package smart

import (
	_ "embed"
	"encoding/json"
	"regexp"
	"strings"
)

//go:embed smart-rules.json
var rulesJSON []byte

type term struct {
	T string `json:"t"`
	W int    `json:"w"`
	M string `json:"m"`
}

type rules struct {
	SLDMarkers  []string                  `json:"sld_markers"`
	TLDWeights  map[string]int            `json:"tld_weights"`
	JudolTerms  []term                    `json:"judol_terms"`
	LureTerms   []string                  `json:"lure_terms"`
	Brands      map[string][]string       `json:"brands"`
	ShortBrand  []string                  `json:"short_brand_tokens"`
	Shorteners  []string                  `json:"shorteners"`
	Trusted     []string                  `json:"trusted"`
	Thresholds  struct{ Block, Warn int } `json:"thresholds"`
	JudolClsMin int                       `json:"judol_class_min"`
}

// Result: Cls = trusted|ok|warn|scam|judol.
type Result struct {
	Score   int      `json:"score"`
	Cls     string   `json:"cls"`
	Reasons []string `json:"reasons"`
	Judol   int      `json:"judol"`
}

var (
	R        rules
	sld      = map[string]bool{}
	shorts   = map[string]bool{}
	shortBr  = map[string]bool{}
	lure     = map[string]bool{}
	ipv4     = regexp.MustCompile(`^\d{1,3}(\.\d{1,3}){3}$`)
	nonAlpha = regexp.MustCompile(`[^a-z]+`)
	digSuf   = regexp.MustCompile(`\d{2,4}$`)
)

func init() {
	if err := json.Unmarshal(rulesJSON, &R); err != nil {
		panic("smart-rules.json rusak: " + err.Error())
	}
	for _, x := range R.SLDMarkers {
		sld[x] = true
	}
	for _, x := range R.Shorteners {
		shorts[x] = true
	}
	for _, x := range R.ShortBrand {
		shortBr[x] = true
	}
	for _, x := range R.LureTerms {
		lure[x] = true
	}
}

// Registrable mengembalikan domain terdaftar kasar (example.co.id, example.com).
func Registrable(h string) string {
	l := strings.Split(h, ".")
	n := len(l)
	if n <= 2 {
		return h
	}
	if len(l[n-1]) == 2 && sld[l[n-2]] {
		return strings.Join(l[n-3:], ".")
	}
	return strings.Join(l[n-2:], ".")
}

func isIP(h string) bool { return ipv4.MatchString(h) || strings.Contains(h, ":") }

func isPrivateIP(h string) bool {
	if strings.Contains(h, ":") {
		return h == "::1" || strings.HasPrefix(h, "fc") || strings.HasPrefix(h, "fd") || strings.HasPrefix(h, "fe80")
	}
	p := strings.Split(h, ".")
	num := func(i int) int {
		n := 0
		for _, c := range p[i] {
			n = n*10 + int(c-'0')
		}
		return n
	}
	a, b := num(0), num(1)
	return a == 10 || a == 127 || a == 0 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31) || (a == 169 && b == 254)
}

func under(h, reg, d string) bool { return reg == d || h == d || strings.HasSuffix(h, "."+d) }

func trusted(h, reg string) bool {
	for _, t := range R.Trusted {
		if under(h, reg, t) {
			return true
		}
	}
	return false
}

func countRune(s string, r rune) int { return strings.Count(s, string(r)) }

// AnalyzeHost menilai satu hostname.
func AnalyzeHost(host string) Result {
	h := strings.ToLower(host)
	h = strings.TrimPrefix(h, "[")
	h = strings.TrimSuffix(h, "]")
	h = strings.TrimSuffix(h, ".")
	out := Result{Cls: "ok"}
	if h == "" {
		return out
	}
	reg := Registrable(h)
	if trusted(h, reg) {
		out.Cls = "trusted"
		return out
	}
	score := 0
	why := []string{}
	labels := strings.Split(h, ".")
	n := len(labels)
	min := func(a, b int) int {
		if a < b {
			return a
		}
		return b
	}
	cls := func(s int) string {
		if s >= R.Thresholds.Block {
			return "scam"
		}
		if s >= R.Thresholds.Warn {
			return "warn"
		}
		return "ok"
	}
	if isIP(h) {
		if isPrivateIP(h) {
			return out
		}
		score += 45
		why = append(why, "alamat IP langsung")
		out.Score = min(100, score)
		out.Cls = cls(out.Score)
		out.Reasons = why
		return out
	}
	tld := labels[n-1]
	suffixLen := 1
	if len(strings.Split(reg, ".")) == 3 {
		suffixLen = 2
	}
	end := n - suffixLen
	if end < 0 {
		end = 0
	}
	textLabels := labels[:end]
	sldLabel := ""
	if len(textLabels) > 0 {
		sldLabel = textLabels[len(textLabels)-1]
	}
	tw := R.TLDWeights[tld]
	score += tw
	for _, l := range labels {
		if strings.HasPrefix(l, "xn--") {
			score += 25
			why = append(why, "domain punycode (mirip huruf lain)")
			break
		}
	}
	if n > 5 {
		score += 10
		why = append(why, "subdomain terlalu panjang")
	}
	if countRune(sldLabel, '-') >= 3 {
		score += 10
		why = append(why, "banyak tanda hubung")
	}
	dg := 0
	for _, c := range sldLabel {
		if c >= '0' && c <= '9' {
			dg++
		}
	}
	if len(sldLabel) >= 6 && float64(dg)/float64(len(sldLabel)) > 0.4 {
		score += 8
		why = append(why, "banyak angka")
	}
	if len(sldLabel) > 30 {
		score += 8
		why = append(why, "nama domain sangat panjang")
	}
	text := strings.Join(textLabels, ".")
	tokens := []string{}
	for _, t := range nonAlpha.Split(text, -1) {
		if t != "" {
			tokens = append(tokens, t)
		}
	}
	var cb strings.Builder
	for _, c := range text {
		if c >= 'a' && c <= 'z' {
			cb.WriteRune(c)
		}
	}
	compact := cb.String()
	tokSet := map[string]bool{}
	for _, t := range tokens {
		tokSet[t] = true
	}
	// judol
	j := 0
	for _, e := range R.JudolTerms {
		if e.M == "sub" {
			if strings.Contains(compact, e.T) {
				j += e.W
			}
		} else if tokSet[e.T] {
			j += e.W
		}
	}
	if j > 100 {
		j = 100
	}
	if j > 0 && digSuf.MatchString(sldLabel) {
		j += 10
	}
	if j > 0 {
		score += j
		why = append(why, "pola situs judi online")
	}
	out.Judol = j + tw
	// brand spoof: urutan kunci JSON tidak dijamin di Go -> iterasi terurut berdasarkan urutan file
	brandHit := false
	for _, b := range brandOrder {
		var present bool
		if shortBr[b] || len(b) < 6 {
			present = tokSet[b]
		} else {
			present = strings.Contains(compact, b)
		}
		if !present {
			continue
		}
		ok := false
		for _, o := range R.Brands[b] {
			if under(h, reg, o) {
				ok = true
				break
			}
		}
		if !ok {
			brandHit = true
			score += 45
			why = append(why, "menyamar sebagai \""+b+"\"")
			break
		}
	}
	// lure
	seen := map[string]bool{}
	lures := 0
	for _, t := range tokens {
		if lure[t] && !seen[t] {
			seen[t] = true
			lures++
		}
	}
	if lures > 0 {
		score += min(24, 8*lures)
		why = append(why, "kata pancingan (login/klaim/hadiah...)")
	}
	if brandHit && lures > 0 {
		score += 20
	}
	if shorts[reg] {
		score += 30
		why = append(why, "pemendek tautan")
	}
	out.Score = min(100, score)
	out.Reasons = why
	switch {
	case out.Judol >= R.JudolClsMin:
		out.Cls = "judol"
	default:
		out.Cls = cls(out.Score)
	}
	return out
}

// brandOrder = urutan kemunculan kunci "brands" di smart-rules.json (sama dengan urutan iterasi JS).
var brandOrder []string

func init() {
	dec := json.NewDecoder(strings.NewReader(string(rulesJSON)))
	// telusuri token untuk mengambil urutan kunci objek "brands"
	depth, inBrands, expectKey := 0, false, false
	for {
		t, err := dec.Token()
		if err != nil {
			break
		}
		switch v := t.(type) {
		case json.Delim:
			if v == '{' || v == '[' {
				depth++
				if inBrands && v == '{' {
					expectKey = true
				}
			} else {
				depth--
				if inBrands && depth == 1 {
					return
				}
			}
		case string:
			if depth == 1 && v == "brands" {
				inBrands = true
				continue
			}
			if inBrands && depth == 2 && expectKey {
				brandOrder = append(brandOrder, v)
				expectKey = false
			}
		}
		if inBrands && depth == 2 {
			if d, ok := t.(json.Delim); ok && d == ']' {
				expectKey = true
			}
		}
	}
}
