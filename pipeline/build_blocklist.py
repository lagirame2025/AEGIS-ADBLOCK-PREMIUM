#!/usr/bin/env python3
"""Ambil sumber blocklist publik, normalisasi, buang duplikat/allowlist/subdomain redundan, tulis blocklist.txt.
Juga membuat cosmetic.txt: selektor generik + aturan per-situs (format EasyList 'situs.com##selektor')."""
import json, os, re, sys, time, urllib.request, pathlib
root = pathlib.Path(__file__).parent
DOM = re.compile(r'^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+([a-z]{2,}|xn--[a-z0-9-]+)$')
# Opsi filter AdGuard/ABP yang masih aman dijadikan blokir level-DNS. Opsi lain ($script, $image, $dnsrewrite,
# $client=..., $badfilter, ...) membatasi cakupan aturan, jadi memblokir seluruh domain akan salah sasaran.
SAFE_OPTS = {"important", "third-party", "3p", "all"}

def lines(url, tries=3):
    for n in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "aegis-pipeline"})
            return urllib.request.urlopen(req, timeout=60).read().decode("utf-8", "ignore").splitlines()
        except Exception as e:
            print(f"gagal ({n + 1}/{tries})", url, e, file=sys.stderr)
            if n + 1 < tries: time.sleep(3 * (n + 1))
    return []

def extract(l):
    l = l.strip().lower()
    if not l or l[0] in "#!;[@/": return None
    if l.startswith("||"):
        m = re.match(r'^\|\|([^/^$*|\s]+)\^(?:\$(.*))?$', l)
        if not m: return None
        opts = {o.strip() for o in (m.group(2) or "").split(",") if o.strip()}
        return m.group(1) if opts <= SAFE_OPTS else None
    p = l.split("#")[0].split()          # buang komentar inline
    if len(p) >= 2 and p[0] in ("0.0.0.0", "127.0.0.1"): d = p[1]
    elif len(p) == 1: d = p[0]
    else: return None
    return d[2:] if d.startswith("*.") else d.lstrip(".")

def drop_redundant(domains):
    """Buang subdomain yang parent-nya sudah ada (Blocklist.kt mencocokkan parent) -> memori HP jauh lebih kecil."""
    s = set(domains); out = []
    for d in sorted(s):
        parts = d.split(".")
        if any(".".join(parts[i:]) in s for i in range(1, len(parts) - 1)): continue
        out.append(d)
    return out


# Domain induk yang TIDAK boleh diblokir (varian "www." otomatis ikut). Jaring pengaman bila sumber upstream
# salah/diracuni: entri persis ini dibuang dari hasil. Subdomain iklan (ads.google.com dst.) tetap bisa diblokir.
_NEVER = """google.com gstatic.com googleapis.com googleusercontent.com youtube.com youtu.be ytimg.com googlevideo.com
facebook.com instagram.com whatsapp.com whatsapp.net apple.com icloud.com microsoft.com live.com office.com
windowsupdate.com amazon.com amazonaws.com cloudflare.com wikipedia.org github.com githubusercontent.com
twitter.com x.com tiktok.com netflix.com spotify.com telegram.org t.me paypal.com tokopedia.com shopee.co.id
gojek.com grab.com traveloka.com bca.co.id klikbca.com bri.co.id bankmandiri.co.id bni.co.id dana.id ovo.id linkaja.id""".split()
NEVER = set(_NEVER) | {"www." + d for d in _NEVER}

def drop_never(domains):
    """Buang entri terlarang. Mengembalikan (domain_aman, daftar_yang_dibuang)."""
    bad = {d for d in domains if d in NEVER}
    return set(domains) - bad, sorted(bad)

def degraded_sources(per_source, prev, ratio=0.5, floor=1000):
    """Sumber yang hasilnya anjlok > (1-ratio) dibanding hitungan sukses terakhirnya (down / format berubah)."""
    return [(u, prev[u], n) for u, n in per_source.items() if prev.get(u, 0) >= floor and n < prev[u] * ratio]


_STRUCT = {"body", "html", "main", "article", "header", "footer", "nav", "section", "#main", "#content", "#page",
           "#wrapper", "#container", ".content", ".main", ".container", ".wrapper", ".page", ".row", ".col", ".site", ".layout"}
_SHORT_ATTR = re.compile(r"""\[[\w-]+\s*[*^$~|]?=\s*["']?[^"'\]]{0,3}["']?\s*\]""")

def risky_selector(sel):
    """Selektor terlalu luas yang berpotensi menyembunyikan isi halaman. Yang dinilai elemen TARGET (bagian terakhir
    tiap alternatif; target telanjang hanya ditolak bila tanpa konteks leluhur): tag/universal telanjang, wadah struktural umum, atau atribut bernilai pendek (<=3 huruf,
    mis. [class*="ad"] yang juga cocok dgn 'loading'/'header')."""
    alts = [x.strip() for x in sel.split(",") if x.strip()]
    if not alts: return True
    for alt in alts:
        toks = [x for x in re.split(r"[>+~ ]+", alt.lower()) if x]
        if not toks: return True
        last = toks[-1]
        if _SHORT_ATTR.search(last): return True
        # target telanjang/struktural hanya berbahaya bila berdiri sendiri; dengan konteks leluhur (".ad-box a") sudah terbatas
        if len(toks) == 1 and (re.fullmatch(r"[a-z0-9*]+", last) or last in _STRUCT): return True
    return False

def load_stats():
    try: return json.loads((root/"stats.json").read_text())
    except Exception: return {}

def save_stats(per_source, total, generic=None):
    data = {"sources": per_source, "total": total}
    if generic is not None: data["cosmetic_generic"] = generic
    (root/"stats.json").write_text(json.dumps(data, indent=1, sort_keys=True) + "\n")

def main():
    allow = {x.strip().lower() for x in (root/"allowlist.txt").read_text().splitlines() if x.strip()}
    def allowed(d): return any(d == a or d.endswith("." + a) for a in allow)
    out = set(); per_source = {}
    for u in (root/"sources.txt").read_text().split():
        got = 0
        for l in lines(u):
            d = extract(l)
            if d and DOM.match(d) and not allowed(d): out.add(d); got += 1
        per_source[u] = got
        print(f"{got:>8}  {u}", file=sys.stderr)
    force = os.environ.get("FORCE") == "1"
    out, removed = drop_never(out)
    if removed: print("PERINGATAN: entri terlarang dibuang dari sumber:", *removed, sep="\n  ", file=sys.stderr)
    bad = degraded_sources(per_source, load_stats().get("sources", {}))
    if bad and not force:
        sys.exit("sumber anjlok >50% dari hitungan terakhir, batal menimpa (jalankan manual dengan force=true bila sah):\n" +
                 "\n".join(f"  {u}: {a} -> {b}" for u, a, b in bad))
    dead = [u for u, n in per_source.items() if n == 0]
    if dead: print("PERINGATAN: sumber kosong/gagal:", *dead, sep="\n  ", file=sys.stderr)
    if len(out) < 1000: sys.exit("terlalu sedikit domain, batal menimpa")
    final = drop_redundant(out)
    dest = root.parent/"blocklist.txt"
    # Pengaman: bila hasil baru menyusut >30% dari daftar sebelumnya (mis. banyak sumber down sekaligus), batal menimpa.
    if dest.exists():
        old = sum(1 for _ in dest.open(encoding="utf-8"))
        if old > 1000 and len(final) < old * 0.7:
            sys.exit(f"daftar menyusut drastis ({old} -> {len(final)}), batal menimpa")
    dest.write_text("\n".join(final) + "\n")
    print(len(out), "domain mentah ->", len(final), "setelah buang subdomain redundan")
    save_stats(per_source, len(final))
    cosmetic()

def cosmetic():
    # --- filter kosmetik untuk browser Aegis ---
    BAD = (":-abp", "+js", "#?#", "has-text", "xpath", "{", "}", "\\", ":matches", ":upward", ":remove", ":style", "url(", "javascript:")
    generic, perdom = set(), set()
    for u in (root/"cosmetic_sources.txt").read_text().split():
        for l in lines(u):
            l = l.strip()
            if "#@#" in l or "#?#" in l or "#$#" in l or "#%#" in l or "##^" in l: continue
            i = l.find("##")
            if i < 0 or not (2 < len(l) < 160): continue
            sel = l[i + 2:]
            if not sel or any(b in sel for b in BAD) or risky_selector(sel): continue
            if i == 0:
                generic.add(sel)
            else:
                doms = [x for x in l[:i].split(",") if x and not x.startswith("~") and "*" not in x and "/" not in x and " " not in x]
                if doms: perdom.add(",".join(doms) + "##" + sel)
    if len(generic) >= 100:
        body = sorted(generic)[:8000] + sorted(perdom)[:60000]
        dest = root.parent/"cosmetic.txt"
        # Pengaman: satu sumber kosmetik down tidak boleh memangkas aturan per-situs secara drastis.
        if dest.exists():
            old = sum(1 for _ in dest.open(encoding="utf-8"))
            if old > 1000 and len(body) < old * 0.7:
                print(f"cosmetic menyusut drastis ({old} -> {len(body)}), batal menimpa", file=sys.stderr); return
        dest.write_text("\n".join(body) + "\n")
        print(len(generic), "selektor generik +", len(perdom), "aturan per-situs")
    else:
        print("selektor kosmetik terlalu sedikit, dilewati", file=sys.stderr)

if __name__ == "__main__":
    main()
