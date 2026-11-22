// AegisSetup: installer Aegis AdBlock untuk Windows x64. UI berjalan di browser (server lokal), bisa juga senyap:
//
//	AegisSetup.exe /S            pasang tanpa UI
//	AegisSetup.exe /UNINSTALL    copot
//	opsi: /NOFAILSAFE /NODOH /DIR=C:\path
package main

import (
	"embed"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"aegis/internal/installer"
	"aegis/internal/sysnet"
)

//go:embed all:payload
var payloadFS embed.FS

//go:embed setup.html
var page []byte

func payload() installer.Payload {
	p := installer.Payload{Extras: map[string][]byte{}}
	ents, _ := payloadFS.ReadDir("payload")
	for _, e := range ents {
		if e.IsDir() || e.Name() == ".keep" {
			continue
		}
		b, _ := payloadFS.ReadFile("payload/" + e.Name())
		if e.Name() == "aegis.exe" {
			p.Exe = b
		} else {
			p.Extras[e.Name()] = b
		}
	}
	return p
}

type state struct {
	mu   sync.Mutex
	log  []string
	done bool
	err  string
	busy bool
	mode string
}

func (s *state) say(m string) { s.mu.Lock(); s.log = append(s.log, m); s.mu.Unlock() }

func main() {
	opt := installer.DefaultOptions()
	silent, uninstall, noBrowser := false, false, false
	for _, a := range os.Args[1:] {
		u := strings.ToUpper(a)
		switch {
		case u == "/S" || u == "-S" || u == "--SILENT":
			silent = true
		case u == "/UNINSTALL" || u == "--UNINSTALL":
			uninstall, silent = true, true
		case u == "/NOFAILSAFE":
			opt.Failsafe = false
		case u == "/NODOH":
			opt.DoHPolicy = false
		case u == "/NOBROWSER":
			noBrowser = true
		case strings.HasPrefix(u, "/DIR="):
			opt.InstallDir = a[5:]
		}
	}
	if !sysnet.IsAdmin() {
		sysnet.Elevate(os.Args[1:]) // UAC; proses ini keluar, proses elevated melanjutkan
		return
	}
	st := &state{}
	if silent {
		say := func(m string) { st.say(m) }
		var err error
		if uninstall {
			err = installer.Uninstall(opt, say)
		} else {
			err = installer.Install(opt, payload(), say)
		}
		logPath := os.TempDir() + `\aegis-setup.log`
		os.WriteFile(logPath, []byte(strings.Join(st.log, "\n")+fmt.Sprintf("\nerr=%v\n", err)), 0o644)
		if err != nil {
			os.Exit(1)
		}
		return
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(page)
	})
	js := func(w http.ResponseWriter, v interface{}) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(v)
	}
	mux.HandleFunc("/api/info", func(w http.ResponseWriter, r *http.Request) {
		_, err := os.Stat(opt.InstallDir + `\aegis.exe`)
		js(w, map[string]interface{}{"version": installer.Version, "dir": opt.InstallDir, "installed": err == nil, "sim": sysnet.Simulated})
	})
	mux.HandleFunc("/api/progress", func(w http.ResponseWriter, r *http.Request) {
		st.mu.Lock()
		defer st.mu.Unlock()
		js(w, map[string]interface{}{"log": st.log, "done": st.done, "err": st.err, "busy": st.busy, "mode": st.mode})
	})
	start := func(mode string, o installer.Options) bool {
		st.mu.Lock()
		if st.busy {
			st.mu.Unlock()
			return false
		}
		st.busy, st.done, st.err, st.log, st.mode = true, false, "", nil, mode
		st.mu.Unlock()
		go func() {
			var err error
			if mode == "uninstall" {
				err = installer.Uninstall(o, st.say)
			} else {
				err = installer.Install(o, payload(), st.say)
			}
			st.mu.Lock()
			st.busy, st.done = false, true
			if err != nil {
				st.err = err.Error()
			}
			st.mu.Unlock()
		}()
		return true
	}
	post := func(path string, fn func(w http.ResponseWriter, body map[string]interface{})) {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			if r.Method != "POST" || r.Header.Get("X-Aegis") != "1" {
				http.Error(w, "dilarang", 403)
				return
			}
			var b map[string]interface{}
			json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&b)
			if b == nil {
				b = map[string]interface{}{}
			}
			fn(w, b)
		})
	}
	post("/api/install", func(w http.ResponseWriter, b map[string]interface{}) {
		o := opt
		if v, ok := b["failsafe"].(bool); ok {
			o.Failsafe = v
		}
		if v, ok := b["doh"].(bool); ok {
			o.DoHPolicy = v
		}
		js(w, map[string]bool{"started": start("install", o)})
	})
	post("/api/uninstall", func(w http.ResponseWriter, b map[string]interface{}) {
		js(w, map[string]bool{"started": start("uninstall", opt)})
	})
	post("/api/open", func(w http.ResponseWriter, b map[string]interface{}) {
		sysnet.OpenURL("http://" + opt.WebAddr + "/")
		js(w, map[string]bool{"ok": true})
	})
	post("/api/quit", func(w http.ResponseWriter, b map[string]interface{}) {
		js(w, map[string]bool{"ok": true})
		go func() { time.Sleep(300 * time.Millisecond); os.Exit(0) }()
	})

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		os.Exit(1)
	}
	url := fmt.Sprintf("http://%s/", ln.Addr().String())
	if !noBrowser {
		sysnet.OpenURL(url)
	} else {
		fmt.Println(url)
	}
	// keluar otomatis bila tab penginstal ditutup lama tanpa aktivitas selesai
	srv := &http.Server{Handler: mux}
	srv.Serve(ln)
}
