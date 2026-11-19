#!/usr/bin/env python3
"""Ambil sumber blocklist publik, normalisasi, buang duplikat & allowlist, tulis blocklist.txt."""
import re, sys, urllib.request, pathlib
root = pathlib.Path(__file__).parent
DOM = re.compile(r'^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$')
def lines(url):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "aegis-pipeline"})
        return urllib.request.urlopen(req, timeout=60).read().decode("utf-8", "ignore").splitlines()
    except Exception as e:
        print("skip", url, e, file=sys.stderr); return []
def extract(l):
    l = l.strip().lower()
    if not l or l[0] in "#!;[@": return None
    m = re.match(r'^\|\|([^/^$*|]+)\^', l)
    if m: return m.group(1)
    p = l.split()
    if len(p) >= 2 and p[0] in ("0.0.0.0", "127.0.0.1"): return p[1]
    if len(p) == 1: return p[0]
    return None
allow = {x.strip().lower() for x in (root/"allowlist.txt").read_text().splitlines() if x.strip()}
out = set()
for u in (root/"sources.txt").read_text().split():
    for l in lines(u):
        d = extract(l)
        if d and DOM.match(d) and not any(d == a or d.endswith("." + a) for a in allow): out.add(d)
if len(out) < 1000: sys.exit("terlalu sedikit domain, batal menimpa")
(root.parent/"blocklist.txt").write_text("\n".join(sorted(out)) + "\n")
print(len(out), "domain")

# --- filter kosmetik (selektor CSS generik dari EasyList) untuk browser Aegis ---
BAD = (":-abp", "+js", "#?#", "has-text", "xpath", "{", "}", "\\", ":matches", ":upward", ":remove")
cos = set()
for u in (root/"cosmetic_sources.txt").read_text().split():
    for l in lines(u):
        l = l.strip()
        if l.startswith("##") and 2 < len(l) < 120:
            sel = l[2:]
            if not any(b in sel for b in BAD): cos.add(sel)
if len(cos) >= 100:
    (root.parent/"cosmetic.txt").write_text("\n".join(sorted(cos)[:8000]) + "\n"); print(len(cos), "selektor")
else:
    print("selektor kosmetik terlalu sedikit, dilewati", file=sys.stderr)
