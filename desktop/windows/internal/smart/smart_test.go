package smart

import (
	"encoding/json"
	"os"
	"testing"
)

func TestVectors(t *testing.T) {
	b, err := os.ReadFile("../../../shared/vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v struct {
		Hosts []struct {
			Host     string `json:"host"`
			Cls      string `json:"cls"`
			MinScore *int   `json:"minScore"`
			MaxScore *int   `json:"maxScore"`
		} `json:"hosts"`
	}
	if err := json.Unmarshal(b, &v); err != nil {
		t.Fatal(err)
	}
	for _, e := range v.Hosts {
		r := AnalyzeHost(e.Host)
		if r.Cls != e.Cls || (e.MinScore != nil && r.Score < *e.MinScore) || (e.MaxScore != nil && r.Score > *e.MaxScore) {
			t.Errorf("%s: dapat %s/%d, harapan %s", e.Host, r.Cls, r.Score, e.Cls)
		}
	}
	if len(brandOrder) < 20 {
		t.Fatalf("brandOrder tidak terbaca: %d", len(brandOrder))
	}
}

// Skor Go harus sama persis dengan JS (dump dari tools/jsvec.js dump).
func TestParityWithJS(t *testing.T) {
	b, err := os.ReadFile(os.Getenv("JS_DUMP"))
	if err != nil {
		t.Skip("JS_DUMP tidak diset")
	}
	var rows [][]interface{}
	if err := json.Unmarshal(b, &rows); err != nil {
		t.Fatal(err)
	}
	for _, r := range rows {
		got := AnalyzeHost(r[0].(string))
		if got.Cls != r[1].(string) || got.Score != int(r[2].(float64)) || got.Judol != int(r[3].(float64)) {
			t.Errorf("%s: Go %s/%d/%d vs JS %v/%v/%v", r[0], got.Cls, got.Score, got.Judol, r[1], r[2], r[3])
		}
	}
}
