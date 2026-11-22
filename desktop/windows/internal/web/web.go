// Package web: dasbor dan API lokal (hanya loopback).
package web

import (
	_ "embed"
	"encoding/json"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"aegis/internal/core"
	"aegis/internal/lists"
	"aegis/internal/smart"
)

//go:embed index.html
var indexHTML []byte

// Backend: yang dibutuhkan dasbor dari aplikasi.
type Backend interface {
	Filter() *core.Filter
	Engine() *core.Engine
	Resolver() *core.Resolver
	SaveSettings()
	UpdateLists() bool
	Updating() bool
	Info() map[string]interface{}
}

func hostOK(r *http.Request) bool {
	h, _, err := net.SplitHostPort(r.Host)
	if err != nil {
		h = r.Host
	}
	return h == "127.0.0.1" || h == "localhost" || h == "[::1]" || h == "::1"
}

func Handler(b Backend) http.Handler {
	mux := http.NewServeMux()
	js := func(w http.ResponseWriter, v interface{}) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		json.NewEncoder(w).Encode(v)
	}
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Content-Security-Policy", "default-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:")
		w.Write(indexHTML)
	})
	mux.HandleFunc("/api/health", func(w http.ResponseWriter, r *http.Request) { js(w, map[string]bool{"ok": true}) })
	mux.HandleFunc("/api/stats", func(w http.ResponseWriter, r *http.Request) {
		f := b.Filter()
		ads, jd, sc := f.Sizes()
		info := b.Info()
		out := map[string]interface{}{
			"total": f.Total.Load(), "blocked": f.Blocked.Load(), "cacheHits": f.CacheHits.Load(),
			"hedged": b.Resolver().Hedged.Load(), "cats": f.Categories(),
			"lists":  map[string]int{"ads": ads, "judol": jd, "scam": sc},
			"strict": f.Strict.Load(), "smart": f.Smart.Load(), "doh": f.BlockDoH.Load(),
			"pausedUntil": f.PausedUntil(), "now": time.Now().Unix(), "updating": b.Updating(),
			"upstreams": b.Resolver().Health(), "info": info, "last": lists.Last(infoDir(info)),
		}
		js(w, out)
	})
	mux.HandleFunc("/api/recent", func(w http.ResponseWriter, r *http.Request) {
		n, _ := strconv.Atoi(r.URL.Query().Get("n"))
		if n <= 0 || n > 300 {
			n = 100
		}
		js(w, b.Filter().Recent(n))
	})
	mux.HandleFunc("/api/rules", func(w http.ResponseWriter, r *http.Request) {
		js(w, map[string][]string{"block": b.Filter().CustomList(false), "allow": b.Filter().CustomList(true)})
	})
	mux.HandleFunc("/api/check", func(w http.ResponseWriter, r *http.Request) {
		h := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("host")))
		if h == "" {
			http.Error(w, "host?", 400)
			return
		}
		v := b.Filter().Check(h)
		js(w, map[string]interface{}{"host": h, "verdict": v, "smart": smart.AnalyzeHost(h)})
	})
	post := func(path string, fn func(w http.ResponseWriter, body map[string]interface{})) {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			// Anti-CSRF/DNS-rebinding: POST harus JSON + header khusus + Host loopback.
			if r.Method != "POST" || r.Header.Get("X-Aegis") != "1" || !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
				http.Error(w, "dilarang", 403)
				return
			}
			var body map[string]interface{}
			json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&body)
			if body == nil {
				body = map[string]interface{}{}
			}
			fn(w, body)
		})
	}
	post("/api/toggle", func(w http.ResponseWriter, body map[string]interface{}) {
		v, _ := body["value"].(bool)
		f := b.Filter()
		switch body["key"] {
		case "strict":
			f.Strict.Store(v)
		case "smart":
			f.Smart.Store(v)
		case "doh":
			f.BlockDoH.Store(v)
		default:
			http.Error(w, "kunci?", 400)
			return
		}
		b.Engine().C.Clear()
		b.SaveSettings()
		js(w, map[string]bool{"ok": true})
	})
	post("/api/pause", func(w http.ResponseWriter, body map[string]interface{}) {
		m, _ := body["minutes"].(float64)
		if m <= 0 {
			b.Filter().Resume()
		} else {
			if m > 24*60 {
				m = 24 * 60
			}
			b.Filter().Pause(time.Duration(m) * time.Minute)
		}
		b.Engine().C.Clear()
		js(w, map[string]bool{"ok": true})
	})
	post("/api/rule", func(w http.ResponseWriter, body map[string]interface{}) {
		d, _ := body["domain"].(string)
		allow, _ := body["allow"].(bool)
		ok := true
		if body["action"] == "remove" {
			b.Filter().RemoveCustom(allow, d)
		} else {
			ok = b.Filter().AddCustom(allow, d)
		}
		b.Engine().C.Clear()
		js(w, map[string]bool{"ok": ok})
	})
	post("/api/update", func(w http.ResponseWriter, body map[string]interface{}) {
		js(w, map[string]bool{"started": b.UpdateLists()})
	})
	return mux
}

func infoDir(i map[string]interface{}) string { s, _ := i["dir"].(string); return s }
