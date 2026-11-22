// Package app: merangkai filter, resolver, server DNS, dasbor web, dan pembaruan daftar menjadi satu layanan.
package app

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"aegis/internal/core"
	"aegis/internal/lists"
	"aegis/internal/web"
)

//go:embed seed_ads.txt
var seedAds string

//go:embed seed_allow.txt
var seedAllow string

type Options struct {
	Dir     string
	Listen  string // alamat DNS, default 127.0.0.1:53
	Listen6 string // default [::1]:53 ("" = nonaktif)
	Web     string // default 127.0.0.1:8053
	DNS     []string
	Version string
}

type Settings struct {
	Strict   bool `json:"strict"`
	Smart    bool `json:"smart"`
	BlockDoH bool `json:"blockDoh"`
}

type App struct {
	Opt Options
	F   *core.Filter
	E   *core.Engine
	R   *core.Resolver

	Started  time.Time
	updating atomic.Bool
	setMu    sync.Mutex
	logf     func(string, ...interface{})
	Errors   []string
	errMu    sync.Mutex
}

func (a *App) addErr(s string) { a.errMu.Lock(); a.Errors = append(a.Errors, s); a.errMu.Unlock() }

func New(opt Options) (*App, error) {
	if opt.Listen == "" {
		opt.Listen = "127.0.0.1:53"
	}
	if opt.Web == "" {
		opt.Web = "127.0.0.1:8053"
	}
	if err := os.MkdirAll(opt.Dir, 0o755); err != nil {
		return nil, err
	}
	a := &App{Opt: opt, Started: time.Now(), logf: log.Printf}
	a.seed()
	a.F = core.NewFilter(opt.Dir)
	st := a.LoadSettings()
	a.F.Strict.Store(st.Strict)
	a.F.Smart.Store(st.Smart)
	a.F.BlockDoH.Store(st.BlockDoH)
	a.R = core.NewResolver(a.readUpstreams())
	a.E = core.NewEngine(a.F, a.R)
	return a, nil
}

func (a *App) seed() {
	write := func(name, content string) {
		p := filepath.Join(a.Opt.Dir, name)
		if st, err := os.Stat(p); err != nil || st.Size() == 0 {
			os.WriteFile(p, []byte(content), 0o644)
		}
	}
	write("blocklist.txt", seedAds) // daftar kecil bawaan agar langsung berguna sebelum unduhan pertama selesai
	write("allow.txt", seedAllow)
}

func (a *App) settingsPath() string { return filepath.Join(a.Opt.Dir, "settings.json") }

func (a *App) LoadSettings() Settings {
	s := Settings{Strict: true, Smart: true, BlockDoH: true}
	if b, err := os.ReadFile(a.settingsPath()); err == nil {
		json.Unmarshal(b, &s)
	}
	return s
}

func (a *App) SaveSettings() {
	a.setMu.Lock()
	defer a.setMu.Unlock()
	s := Settings{a.F.Strict.Load(), a.F.Smart.Load(), a.F.BlockDoH.Load()}
	b, _ := json.MarshalIndent(s, "", " ")
	os.WriteFile(a.settingsPath(), b, 0o644)
}

func (a *App) readUpstreams() []string {
	if len(a.Opt.DNS) > 0 {
		return a.Opt.DNS
	}
	b, err := os.ReadFile(filepath.Join(a.Opt.Dir, "upstreams.txt"))
	if err != nil {
		return nil
	}
	return strings.Fields(strings.ReplaceAll(string(b), ",", " "))
}

// UpdateLists menjalankan pembaruan daftar; mengembalikan false bila sudah berjalan.
func (a *App) UpdateLists() bool {
	if !a.updating.CompareAndSwap(false, true) {
		return false
	}
	go func() {
		defer a.updating.Store(false)
		rep := lists.Update(a.Opt.Dir, nil, lists.Sources, a.logf)
		a.F.Reload()
		a.E.C.Clear()
		ads, j, s := a.F.Sizes()
		a.logf("daftar diperbarui: iklan=%d judol=%d scam=%d (%v)", ads, j, s, rep.Kept)
	}()
	return true
}

func (a *App) Updating() bool { return a.updating.Load() }

func listen(ctx context.Context, addr string) (net.PacketConn, net.Listener, error) {
	var lc net.ListenConfig
	pc, err := lc.ListenPacket(ctx, "udp", addr)
	if err != nil {
		return nil, nil, err
	}
	ln, err := lc.Listen(ctx, "tcp", addr)
	if err != nil {
		pc.Close()
		return nil, nil, err
	}
	return pc, ln, nil
}

// Run menjalankan semua layanan sampai ctx dibatalkan.
func (a *App) Run(ctx context.Context) error {
	pc, ln, err := listen(ctx, a.Opt.Listen)
	if err != nil {
		a.addErr("Port DNS " + a.Opt.Listen + " tidak bisa dipakai (mungkin dipakai program lain): " + err.Error())
		return fmt.Errorf("listen dns: %w", err)
	}
	go a.E.ServeUDP(pc)
	go a.E.ServeTCP(ln)
	if a.Opt.Listen6 != "" {
		if pc6, ln6, err := listen(ctx, a.Opt.Listen6); err == nil {
			go a.E.ServeUDP(pc6)
			go a.E.ServeTCP(ln6)
			defer pc6.Close()
			defer ln6.Close()
		} else {
			a.logf("IPv6 loopback tidak aktif: %v", err)
		}
	}
	srv := &http.Server{Addr: a.Opt.Web, Handler: web.Handler(a), ReadHeaderTimeout: 10 * time.Second}
	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			a.addErr("Dasbor tidak bisa dibuka di " + a.Opt.Web + ": " + err.Error())
			a.logf("web: %v", err)
		}
	}()
	// pembaruan daftar: saat mulai bila basi, lalu tiap 12 jam
	go func() {
		if lists.Age(a.Opt.Dir) > 20*time.Hour {
			time.Sleep(3 * time.Second)
			a.UpdateLists()
		}
		t := time.NewTicker(12 * time.Hour)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				a.UpdateLists()
			}
		}
	}()
	// muat ulang upstream bila berkas berubah
	go func() {
		var last time.Time
		t := time.NewTicker(2 * time.Minute)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				if st, err := os.Stat(filepath.Join(a.Opt.Dir, "upstreams.txt")); err == nil && st.ModTime().After(last) {
					last = st.ModTime()
					a.R.SetSystem(a.readUpstreams())
				}
			}
		}
	}()
	a.logf("Aegis %s berjalan: DNS %s, dasbor http://%s", a.Opt.Version, a.Opt.Listen, a.Opt.Web)
	<-ctx.Done()
	pc.Close()
	ln.Close()
	shut, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	srv.Shutdown(shut)
	return nil
}

// ---- implementasi web.Backend ----

func (a *App) Filter() *core.Filter     { return a.F }
func (a *App) Engine() *core.Engine     { return a.E }
func (a *App) Resolver() *core.Resolver { return a.R }
func (a *App) Info() map[string]interface{} {
	a.errMu.Lock()
	errs := append([]string(nil), a.Errors...)
	a.errMu.Unlock()
	return map[string]interface{}{"version": a.Opt.Version, "dir": a.Opt.Dir, "uptime": int(time.Since(a.Started).Seconds()), "errors": errs}
}
