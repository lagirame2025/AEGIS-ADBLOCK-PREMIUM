package lists

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParse(t *testing.T) {
	in := `# komentar
127.0.0.1 localhost
0.0.0.0 ads.one.test  two.test # inline
||three.test^
||four.test^$important
||path.test/ads^
@@||five.test^
||wild*.test^
six.test
*.seven.test
10.0.0.1
bad_chars$.test
`
	got := strings.Join(Parse(strings.NewReader(in)), ",")
	want := "ads.one.test,two.test,three.test,four.test,six.test,seven.test"
	if got != want {
		t.Fatalf("got %s want %s", got, want)
	}
}

func TestUpdateAndShrinkGuard(t *testing.T) {
	dir := t.TempDir()
	body := "0.0.0.0 a.test\n0.0.0.0 b.test\n"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/bad" {
			w.WriteHeader(500)
			return
		}
		w.Write([]byte(body))
	}))
	defer srv.Close()
	src := []Source{{"ok", srv.URL + "/ok", "blocklist.txt"}, {"bad", srv.URL + "/bad", "scam.txt"}, {"j", srv.URL + "/j", "judol.txt"}}
	rep := Update(dir, srv.Client(), src[:1], t.Logf)
	if rep.Counts["blocklist.txt"] != 2 {
		t.Fatalf("count %v", rep.Counts)
	}
	b, _ := os.ReadFile(filepath.Join(dir, "blocklist.txt"))
	if string(b) != "a.test\nb.test\n" {
		t.Fatalf("isi %q", b)
	}
	// perlindungan penyusutan: tulis daftar lama besar, lalu update dengan hasil kecil
	var sb strings.Builder
	for i := 0; i < 300; i++ {
		sb.WriteString("d" + strings.Repeat("x", i%7) + string(rune('a'+i%26)) + string(rune('a'+(i/26)%26)) + ".test\n")
	}
	os.WriteFile(filepath.Join(dir, "blocklist.txt"), []byte(sb.String()), 0o644)
	rep = Update(dir, srv.Client(), src[:1], t.Logf)
	if len(rep.Kept) != 1 {
		t.Fatalf("seharusnya dipertahankan: %+v", rep)
	}
}
