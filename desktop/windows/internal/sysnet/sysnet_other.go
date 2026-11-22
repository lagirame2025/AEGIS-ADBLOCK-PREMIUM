//go:build !windows

package sysnet

import (
	"os"
	"path/filepath"
	"sync"
)

// Simulated: true di OS non-Windows (mode pengembangan; tidak mengubah sistem).
const Simulated = true

var (
	simMu  sync.Mutex
	SimLog []string
)

func simf(s string) { simMu.Lock(); SimLog = append(SimLog, s); simMu.Unlock() }

func IsAdmin() bool                                { return true }
func Elevate(args []string) error                  { return nil }
func PointDNS(as []Adapter, failsafe string) error { simf("PointDNS"); return nil }
func InstallTask(dir, exe string) error            { simf("InstallTask " + exe); return nil }
func StartTask() error                             { simf("StartTask"); return nil }
func StopAll()                                     { simf("StopAll") }
func RemoveTask()                                  { simf("RemoveTask") }
func SetDoHPolicy(off bool)                        { simf("SetDoHPolicy") }
func AddUninstallEntry(dir, exe, version string)   { simf("AddUninstallEntry") }
func RemoveUninstallEntry()                        { simf("RemoveUninstallEntry") }
func OpenURL(u string)                             {}
func SelfDelete(dir string)                        { simf("SelfDelete " + dir) }
func StartMenuDir() string                         { return filepath.Join(os.TempDir(), "aegis-sim-startmenu") }
func DefaultInstallDir() string                    { return filepath.Join(os.TempDir(), "aegis-sim-install") }
func DefaultDataDir() string                       { return filepath.Join(os.TempDir(), "aegis-sim-data") }

func BackupAdapters(dir string) ([]Adapter, error) {
	simf("BackupAdapters")
	return []Adapter{{Index: 7, Alias: "Wi-Fi", Servers: []string{"192.168.1.1", "8.8.8.8"}, Dhcp: "Enabled"}}, nil
}
func RestoreDNS(dir string) error { simf("RestoreDNS"); return nil }
