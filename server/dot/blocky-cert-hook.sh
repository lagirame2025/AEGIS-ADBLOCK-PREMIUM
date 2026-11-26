#!/bin/sh
# Deploy-hook certbot: salin sertifikat ke ./certs (dibaca container UID 100) lalu restart Blocky.
# Pemakaian:  blocky-cert-hook.sh dot.contoh.com
# Saat dipanggil certbot, variabel RENEWED_LINEAGE berisi folder live/<domain>; dipakai bila ada.
set -eu
DOMAIN="${1:?isi nama domain, mis. dot.contoh.com}"
SRC="${RENEWED_LINEAGE:-/etc/letsencrypt/live/$DOMAIN}"
DST="$(cd "$(dirname "$0")" && pwd)/certs"

install -d -m 750 -o 100 -g 100 "$DST"
install -m 640 -o 100 -g 100 "$SRC/fullchain.pem" "$DST/fullchain.pem"
install -m 640 -o 100 -g 100 "$SRC/privkey.pem"   "$DST/privkey.pem"

# Restart hanya bila container sudah ada (pada penerbitan pertama belum ada).
docker restart blocky >/dev/null 2>&1 || true
