#!/usr/bin/env bash
# Bangun semuanya (Linux/macOS/WSL): ekstensi + aegis.exe + AegisSetup.exe (Windows x64).
# Butuh: Go >= 1.21, Node >= 18, zip.  Hasil: ./dist
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-$PWD/dist}"; VER=$(node -p "require('./extension/version.json').version")
mkdir -p "$OUT"
echo "== tes mesin JS =="; node tools/jsvec.js; node tools/textvec.js; node tools/adtest.js
echo "== tes Go ==";      (cd windows && go test -count=1 ./...)
echo "== ekstensi ==";    node tools/build.js --out "$OUT"
echo "== aegis.exe + AegisSetup.exe (windows/amd64) =="
cd windows
export GOOS=windows GOARCH=amd64 CGO_ENABLED=0
find cmd/setup/payload -type f ! -name .keep -delete
go build -trimpath -ldflags "-s -w -H=windowsgui -X main.version=$VER" -o "$OUT/aegis.exe" ./cmd/aegis
cp "$OUT/aegis.exe" cmd/setup/payload/aegis.exe
for t in chromium firefox safari; do cp "$OUT/ext-$t.zip" "cmd/setup/payload/ext-$t.zip"; done
cp ../PASANG-EKSTENSI.html cmd/setup/payload/PASANG-EKSTENSI.html
go build -trimpath -ldflags "-s -w -H=windowsgui" -o "$OUT/AegisSetup.exe" ./cmd/setup
find cmd/setup/payload -type f ! -name .keep -delete
(cd "$OUT" && sha256sum AegisSetup.exe aegis.exe ext-*.zip > SHA256SUMS.txt)
echo "selesai:"; ls -la "$OUT"
