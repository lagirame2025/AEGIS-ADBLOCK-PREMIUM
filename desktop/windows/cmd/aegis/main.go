// aegis: filter DNS lokal (iklan, judol, scam) untuk Windows. Subperintah: run | uninstall | version.
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"syscall"

	"aegis/internal/app"
	"aegis/internal/installer"
	"aegis/internal/sysnet"
)

var version = "1.8.1"

func main() {
	cmd := "run"
	if len(os.Args) > 1 {
		cmd = os.Args[1]
	}
	switch cmd {
	case "version", "-v", "--version":
		fmt.Println("Aegis AdBlock", version)
	case "uninstall":
		if !sysnet.IsAdmin() {
			sysnet.Elevate(os.Args[1:])
			return
		}
		if err := installer.Uninstall(installer.DefaultOptions(), func(s string) {}); err != nil {
			os.Exit(1)
		}
	case "run":
		run(os.Args[min(2, len(os.Args)):])
	default:
		fmt.Println("pakai: aegis run [-dir DIR] [-dns IP,IP] [-listen ADDR] [-web ADDR] | uninstall | version")
		os.Exit(2)
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func run(args []string) {
	opt := app.Options{Dir: sysnet.DefaultDataDir(), Listen: "127.0.0.1:53", Listen6: "[::1]:53", Web: "127.0.0.1:8053", Version: version}
	for i := 0; i < len(args); i++ {
		next := func() string {
			if i+1 < len(args) {
				i++
				return args[i]
			}
			return ""
		}
		switch args[i] {
		case "-dir":
			opt.Dir = next()
		case "-dns":
			opt.DNS = strings.Split(next(), ",")
		case "-listen":
			opt.Listen = next()
		case "-listen6":
			opt.Listen6 = next()
		case "-web":
			opt.Web = next()
		}
	}
	os.MkdirAll(opt.Dir, 0o755)
	lp := filepath.Join(opt.Dir, "aegis.log")
	if st, err := os.Stat(lp); err == nil && st.Size() > 2<<20 {
		os.Remove(lp)
	}
	if f, err := os.OpenFile(lp, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644); err == nil {
		log.SetOutput(f)
	}
	a, err := app.New(opt)
	if err != nil {
		log.Fatalf("mulai gagal: %v", err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	if err := a.Run(ctx); err != nil {
		log.Printf("berhenti: %v", err)
		os.Exit(1) // Scheduled Task akan memulai ulang (RestartOnFailure)
	}
}
