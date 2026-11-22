package core

import "strings"

// CheckList: hanya daftar (tanpa heuristik/smart); dipakai untuk target CNAME agar CDN sah tidak terkena tebakan nama.
func (f *Filter) CheckList(name string) Verdict {
	cur := strings.ToLower(strings.TrimSuffix(name, "."))
	ls := f.lists.Load()
	var v Verdict
	for cur != "" {
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
			}
		}
		i := strings.IndexByte(cur, '.')
		if i < 0 {
			break
		}
		cur = cur[i+1:]
	}
	return v
}

// IsAllowed: izin eksplisit (bawaan/pengguna) untuk nama atau induknya.
func (f *Filter) IsAllowed(name string) bool {
	cur := strings.ToLower(strings.TrimSuffix(name, "."))
	ls := f.lists.Load()
	for cur != "" {
		if has(ls.userAllow, cur) || has(ls.allow, cur) {
			return true
		}
		i := strings.IndexByte(cur, '.')
		if i < 0 {
			break
		}
		cur = cur[i+1:]
	}
	return false
}
