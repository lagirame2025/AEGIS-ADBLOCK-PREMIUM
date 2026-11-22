/* Aegis - content script (dunia terisolasi). Fitur: lewati iklan YouTube, sembunyikan iklan berlabel di media sosial,
   saring komentar judol, tandai tautan scam/judol, tandai clickbait, banner phishing, hapus overlay jebakan klik,
   dan tembok "matikan adblock". Semua analisis dilakukan lokal. */
(function () {
  if (window.__aegisContent) return; window.__aegisContent = 1;
  var api = typeof browser !== 'undefined' ? browser : chrome;
  var S = self.AegisSmart && self.AEGIS_RULES ? self.AegisSmart.init(self.AEGIS_RULES) : null;
  var T = self.AegisText;
  var R = self.AEGIS_RULES || { brands: {} };
  var host = location.hostname.toLowerCase(), isTop = window.top === window;
  var isYT = /(^|\.)youtube(-nocookie)?\.com$/.test(host);
  var CFG = { enabled: true, social: true, comments: true, links: true, clickbait: 'mark', phishing: true, overlays: true, youtube: true, walls: true, smartAds: true };
  var SLD = { co: 1, com: 1, net: 1, org: 1, gov: 1, edu: 1, ac: 1, or: 1, go: 1, web: 1, sch: 1, my: 1, biz: 1, ne: 1, mil: 1 };
  function reg(h) { var l = String(h).toLowerCase().split('.'), n = l.length; if (n <= 2) return l.join('.'); return (l[n - 1].length === 2 && SLD[l[n - 2]]) ? l.slice(-3).join('.') : l.slice(-2).join('.'); }
  var myReg = reg(host), hiddenCount = 0, reported = 0;

  function el(tag, css, text) { var e = document.createElement(tag); if (css) e.style.cssText = css; if (text !== undefined) e.textContent = text; return e; }
  function hide(e) { try { e.style.setProperty('display', 'none', 'important'); e.setAttribute('data-aegis', '1'); hiddenCount++; } catch (x) { } }

  /* ================= YouTube: lewati iklan video ================= */
  var inAd = false, prevMuted = false, prevRate = 1, obsP = null;
  var SKIP = '.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button,.ytp-ad-skip-button-slot button,.ytm-skip-ad-button,.videoAdUiSkipButton';
  var AD_SLOTS = 'ytm-ad-slot-renderer,ytd-ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-in-feed-ad-layout-renderer,ytm-statement-banner-renderer,ytm-brand-video-singleton-renderer,ytd-promoted-sparkles-web-renderer,ytd-in-feed-ad-layout-renderer';
  function yt() { try { yt0(); } catch (e) { } }
  function yt0() {
    var p = document.querySelector('.html5-video-player');
    var v = (p && p.querySelector('video.html5-main-video')) || (p && p.querySelector('video')) || document.querySelector('video');
    var ad = !!(p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));
    if (p && p !== obsP) { obsP = p; try { new MutationObserver(yt).observe(p, { attributes: true, attributeFilter: ['class'] }); } catch (e) { } }
    if (ad && v) {
      if (!inAd) { inAd = true; prevMuted = v.muted; prevRate = v.playbackRate || 1; }
      v.muted = true;
      try { if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.1) v.currentTime = v.duration; } catch (e) { }
      try { v.playbackRate = 16; } catch (e) { }
      if (v.paused) { try { v.play(); } catch (e) { } }
    } else if (inAd && v) { inAd = false; v.muted = prevMuted; try { v.playbackRate = prevRate; } catch (e) { } }
    var sb = document.querySelectorAll(SKIP); for (var i = 0; i < sb.length; i++) { try { sb[i].click(); } catch (e) { } }
    var o = document.querySelector('.ytp-ad-overlay-close-button'); if (o) { try { o.click(); } catch (e) { } }
    var slots = document.querySelectorAll(AD_SLOTS.split(',').map(function (s) { return s + ':not([data-aegis])'; }).join(','));
    for (var j = 0; j < slots.length; j++) {
      var w = slots[j].closest('ytm-rich-item-renderer,ytd-rich-item-renderer,ytm-reel-video-renderer,ytd-reel-video-renderer,ytm-item-section-renderer,ytm-rich-section-renderer,ytd-rich-section-renderer') || slots[j];
      hide(w); slots[j].setAttribute('data-aegis', '1');
    }
    var e = document.querySelector('ytd-enforcement-message-view-model,ytm-enforcement-message-view-model');
    if (e) {
      var dlg = e.closest('tp-yt-paper-dialog') || e; try { dlg.remove(); } catch (x) { }
      var bd = document.querySelector('tp-yt-iron-overlay-backdrop'); if (bd) try { bd.remove(); } catch (x) { }
      if (v && v.paused) try { v.play(); } catch (x) { }
    }
  }

  /* ================= Iklan berlabel di media sosial ================= */
  var LABEL = /^(sponsored|disponsori|bersponsor|bersponsor oleh|iklan|ad|ads|advertisement|promoted|dipromosikan|promosi|gesponsert|patrocinado|sponsorisé|sponsorizzato|реклама|広告|광고|مُموَّل|إعلان|ได้รับการสนับสนุน|được tài trợ|quảng cáo|reklam|sponsorlu|publicidad|publicité)$/i;
  var SOCIAL = [
    { re: /(^|\.)facebook\.com$/, items: '[role="article"],div[data-pagelet^="FeedUnit"]', extra: 'a[href*="/ads/about"],a[href*="ad_id="],[aria-label="Sponsored"],[aria-label="Disponsori"],[aria-label="Bersponsor"]' },
    { re: /(^|\.)instagram\.com$/, items: 'article', extra: 'a[href*="/ads/"],a[href*="ad_id="]' },
    { re: /(^|\.)(x|twitter)\.com$/, items: 'article[data-testid="tweet"],[data-testid="cellInnerDiv"]', extra: '[data-testid="placementTracking"]' },
    { re: /(^|\.)tiktok\.com$/, items: '[data-e2e="recommend-list-item-container"],article,div[class*="DivItemContainer"]', extra: 'a[href*="ads.tiktok.com"],[data-e2e*="feed-ad"]' },
    { re: /(^|\.)reddit\.com$/, items: 'shreddit-post,article,shreddit-ad-post', extra: 'shreddit-ad-post,[data-testid="ad-post"]' },
    { re: /(^|\.)linkedin\.com$/, items: '.feed-shared-update-v2,div[data-urn]', extra: '' },
    { re: /(^|\.)threads\.(net|com)$/, items: 'div[data-pressable-container]', extra: '' },
    { re: /(^|\.)(pinterest\.[a-z.]+|quora\.com)$/, items: 'div[data-test-id="pin"],div.q-box', extra: '[data-test-id="pinrep-ad"]' }
  ];
  function social() {
    for (var c = 0; c < SOCIAL.length; c++) {
      var cfg = SOCIAL[c]; if (!cfg.re.test(host)) continue;
      var items = document.querySelectorAll(cfg.items.split(',').map(function (s) { return s + ':not([data-aegis])'; }).join(','));
      for (var i = 0; i < items.length && i < 120; i++) {
        var it = items[i], tries = +(it.getAttribute('data-aegis-s') || 0);
        if (tries >= 3) continue; it.setAttribute('data-aegis-s', tries + 1);
        var bad = false;
        if (cfg.extra) { try { bad = !!(it.matches(cfg.extra) || it.querySelector(cfg.extra)); } catch (e) { } }
        if (!bad) {
          var leafs = it.querySelectorAll('span,a,div,p,b'), n = 0;
          for (var k = 0; k < leafs.length && n < 80; k++) {
            var L = leafs[k]; if (L.children.length) continue; n++;
            var t = (L.textContent || '').trim(); if (t.length > 0 && t.length <= 24 && LABEL.test(t)) { bad = true; break; }
          }
        }
        if (bad) hide(it);
      }
    }
  }

  /* ================= Komentar judol ================= */
  var COMMENT_ITEMS = 'ytd-comment-thread-renderer,ytd-comment-view-model,ytm-comment-thread-renderer,ytm-comment-renderer,shreddit-comment,[data-testid="comment"],[data-e2e*="comment-level"],li[class*="comment" i],div[class*="comment-item" i],div[class*="CommentItem"],article[class*="comment" i]';
  function revealable(node, what, why) {
    var ph = el('div', 'margin:4px 0;padding:6px 10px;border:1px dashed #b3261e;border-radius:8px;font:12px system-ui,sans-serif;color:#b3261e;background:rgba(179,38,30,.06)', '🛡 Aegis menyembunyikan ' + what + (why ? ' (' + why + ')' : '') + ' · ');
    var b = el('button', 'all:unset;cursor:pointer;text-decoration:underline', 'Tampilkan');
    b.onclick = function (ev) { ev.preventDefault(); ev.stopPropagation(); node.style.removeProperty('display'); ph.remove(); };
    ph.appendChild(b); node.parentNode && node.parentNode.insertBefore(ph, node); hide(node);
  }
  function comments() {
    if (!T) return;
    var items = document.querySelectorAll(COMMENT_ITEMS.split(',').map(function (s) { return s + ':not([data-aegis]):not([data-aegis-c])'; }).join(','));
    for (var i = 0; i < items.length && i < 150; i++) {
      var it = items[i], body = it.querySelector('#content-text,.yt-core-attributed-string,[class*="comment-text" i],[class*="CommentText"],[data-e2e*="comment-text"],p');
      var txt = ((body || it).innerText || '').slice(0, 600);
      if (txt.length < 6) continue; it.setAttribute('data-aegis-c', '1');
      var r = T.judolText(txt);
      if (r.score >= 60) revealable(it, 'komentar judol', r.reasons.slice(0, 2).join(', '));
      else { var sc = T.scamText(txt); if (sc.score >= 50) revealable(it, 'komentar penipuan', sc.reasons[0]); }
    }
  }

  /* ================= Tautan scam / judol ================= */
  var linkCache = {}, pendingHosts = {}, linkTimer = 0;
  function markLink(a, info, why) {
    a.setAttribute('data-aegis-risk', info); a.title = 'Aegis: tautan berisiko (' + why + ')';
    if (!a.__aegisClk) {
      a.__aegisClk = 1;
      a.addEventListener('click', function (ev) {
        if (!a.getAttribute('data-aegis-risk')) return;
        ev.preventDefault(); ev.stopImmediatePropagation();
        var go = confirm('⚠ Aegis: tautan ini berisiko (' + why + ').\n\nTujuan: ' + a.href + '\n\nTetap buka?');
        if (go) { a.removeAttribute('data-aegis-risk'); if (a.target === '_blank') window.open(a.href, '_blank', 'noopener'); else location.href = a.href; }
      }, true);
    }
  }
  var TEXTLIKE = /^(https?:\/\/)?([a-z0-9-]+\.)+[a-z]{2,}(\/\S*)?$/i;
  function links() {
    var as = document.querySelectorAll('a[href]:not([data-aegis-l])'), want = {}, list = [];
    for (var i = 0; i < as.length && i < 250; i++) {
      var a = as[i]; a.setAttribute('data-aegis-l', '1');
      var u; try { u = new URL(a.href); } catch (e) { continue; }
      if (u.protocol !== 'http:' && u.protocol !== 'https:') continue;
      var h = u.hostname.toLowerCase(); if (reg(h) === myReg) continue;
      list.push([a, h, u]); if (!(h in linkCache)) want[h] = 1;
    }
    var hs = Object.keys(want);
    var apply = function () {
      list.forEach(function (x) {
        var a = x[0], h = x[1], c = linkCache[h];
        if (c && (c.cls === 'scam' || c.cls === 'judol' || c.cls === 'phish')) { markLink(a, c.cls, c.why || c.cls); return; }
        var txt = (a.textContent || '').trim();
        if (TEXTLIKE.test(txt) && S) { // teks tampak seperti alamat X, tetapi tujuan sebenarnya Y
          var th = txt.replace(/^https?:\/\//i, '').split('/')[0].toLowerCase();
          if (reg(th) !== reg(h)) { var sc = S.analyzeHost(h); if (sc.score >= 20 || sc.reasons.indexOf('pemendek tautan') >= 0) markLink(a, 'mismatch', 'teks menampilkan ' + th + ' tapi menuju ' + h); }
        }
      });
    };
    if (!hs.length) { apply(); return; }
    try {
      api.runtime.sendMessage({ type: 'classify', hosts: hs.slice(0, 80) }).then(function (res) {
        if (res && res.map) for (var k in res.map) linkCache[k] = res.map[k];
        hs.forEach(function (h) { if (!(h in linkCache)) linkCache[h] = { cls: 'ok' }; });
        apply();
      }, function () { });
    } catch (e) { }
  }

  /* ================= Clickbait ================= */
  var CB_SEL = 'h1,h2,h3,#video-title,a#video-title-link,.title,[class*="headline" i],[class*="judul" i]';
  function clickbait() {
    if (!S || CFG.clickbait === 'off') return;
    var hs = document.querySelectorAll(CB_SEL.split(',').map(function (s) { return s + ':not([data-aegis-cb]):not([data-aegis])'; }).join(','));
    for (var i = 0; i < hs.length && i < 150; i++) {
      var h = hs[i], t = (h.textContent || '').trim(); h.setAttribute('data-aegis-cb', '0');
      if (t.length < 14 || t.length > 200) continue;
      var r = S.clickbait(t); if (r.score < 50) continue;
      if (CFG.clickbait === 'hide') { var card = h.closest('ytd-rich-item-renderer,ytd-video-renderer,ytm-rich-item-renderer,article,li') || h; hide(card); }
      else { h.setAttribute('data-aegis-cb', String(r.score)); h.title = 'Aegis: kemungkinan clickbait (' + r.reasons.join(', ') + ')'; }
    }
  }

  /* ================= Banner phishing ================= */
  var phishShown = false;
  function under(h, d) { return h === d || h.slice(-(d.length + 1)) === '.' + d; }
  function phish() {
    if (!isTop || phishShown || !S) return;
    var pw = document.querySelector('input[type=password]'); if (!pw) return;
    var a = S.analyzeHost(host); if (a.cls === 'trusted') return;
    var meta = document.querySelector('meta[property="og:site_name"]');
    var imgs = Array.prototype.slice.call(document.querySelectorAll('img[alt]')).slice(0, 12).map(function (i) { return i.alt; }).join(' ');
    var h1 = document.querySelector('h1');
    var compact = (document.title + ' ' + (h1 ? h1.textContent : '') + ' ' + (meta ? meta.content : '') + ' ' + imgs).toLowerCase().replace(/[^a-z]/g, '');
    var score = a.score + 10, why = a.reasons.slice(), brand = '';
    for (var b in R.brands) {
      if (b.length < 5 || compact.indexOf(b) < 0) continue;
      var ok = R.brands[b].some(function (d) { return under(host, d); });
      if (!ok) { brand = b; score += 45; why.push('mengaku sebagai "' + b + '"'); break; }
    }
    var forms = document.querySelectorAll('form'); for (var i = 0; i < forms.length; i++) {
      if (!forms[i].querySelector('input[type=password]')) continue;
      try { var fa = new URL(forms[i].action, location.href); if (reg(fa.hostname) !== myReg && fa.protocol.indexOf('http') === 0) { score += 25; why.push('formulir mengirim sandi ke ' + fa.hostname); break; } } catch (e) { }
    }
    if (location.protocol === 'http:') { score += 20; why.push('tanpa HTTPS'); }
    if (score < 60) return;
    phishShown = true;
    var bar = el('div', 'position:fixed;top:0;left:0;right:0;z-index:2147483647;background:#b3261e;color:#fff;font:600 14px/1.4 system-ui,sans-serif;padding:10px 14px;display:flex;gap:12px;align-items:center;box-shadow:0 2px 12px rgba(0,0,0,.35)');
    bar.appendChild(el('span', '', '⚠ AEGIS: Halaman ini kemungkinan PHISHING' + (brand ? ' yang menyamar sebagai "' + brand + '"' : '') + '. Jangan masukkan kata sandi. (' + why.slice(0, 3).join('; ') + ')'));
    var x = el('button', 'margin-left:auto;cursor:pointer;border:1px solid #fff;background:transparent;color:#fff;border-radius:6px;padding:3px 10px', 'Tutup');
    x.onclick = function () { bar.remove(); }; bar.appendChild(x);
    (document.body || document.documentElement).appendChild(bar);
  }

  /* ================= Overlay jebakan klik & tembok anti-adblock ================= */
  function overlays() {
    if (!document.body) return;
    var vw = innerWidth, vh = innerHeight, st = document.elementsFromPoint ? document.elementsFromPoint(vw / 2, vh / 2) : [];
    for (var i = 0; i < st.length; i++) {
      var e = st[i]; if (e === document.body || e === document.documentElement || e.hasAttribute('data-aegis')) continue;
      var cs = getComputedStyle(e); if (cs.position !== 'fixed') continue;
      var r = e.getBoundingClientRect(); if (r.width < vw * 0.9 || r.height < vh * 0.9) continue;
      var z = parseInt(cs.zIndex, 10) || 0; if (z < 1000) continue;
      var m = cs.backgroundColor.match(/rgba?\(([^)]+)\)/), alpha = 1;
      if (m) { var p = m[1].split(','); alpha = p.length > 3 ? parseFloat(p[3]) : 1; }
      var invisible = parseFloat(cs.opacity) < 0.05 || alpha < 0.05;
      if (invisible && !(e.innerText || '').trim() && !e.querySelector('input,button,form,video,canvas,img,iframe[src]')) { hide(e); }
    }
  }
  var AB = /(ad[\s\-]?block|pemblokir iklan|blokir iklan|anti[\s\-]?adblock|matikan (ad ?block|pemblokir)|nonaktifkan (ad ?block|pemblokir)|disable (your )?ad[\s\-]?block|turn off (your )?ad[\s\-]?block|izinkan iklan|allow ads)/i;
  function walls() {
    if (!document.body || !document.elementsFromPoint) return;
    var vw = innerWidth, vh = innerHeight, did = false, st = document.elementsFromPoint(vw / 2, vh / 2);
    for (var i = 0; i < st.length; i++) {
      var e = st[i]; if (e === document.body || e === document.documentElement || e.hasAttribute('data-aegis')) continue;
      var cs = getComputedStyle(e); if (cs.position !== 'fixed' && cs.position !== 'absolute' && cs.position !== 'sticky') continue;
      var r = e.getBoundingClientRect(); if (r.width < vw * 0.45 || r.height < vh * 0.25) continue;
      var tx = e.textContent || ''; if (tx.length > 1500) continue;
      if (AB.test(tx)) { hide(e); did = true; }
    }
    if (did) {
      [document.documentElement, document.body].forEach(function (n) { try { n.style.setProperty('overflow', 'auto', 'important'); n.style.setProperty('position', 'static', 'important'); } catch (e) { } });
      var v = document.querySelector('video'); if (v && v.paused) { try { v.play(); } catch (e) { } }
    }
  }
  var ADHOST = /(doubleclick|googlesyndication|googleadservices|adservice\.|adnxs|taboola|outbrain|criteo|amazon-adsystem|adsterra|propeller|popads|exoclick|rubiconproject|pubmatic|openx\.net|smartadserver|teads\.tv|mgid\.com|revcontent|adsrvr|casalemedia|adform\.net|juicyads|clickadu|hilltopads)/i;
  var ADID = /(google_ads|aswift|ad_iframe|adframe|(^|[-_ ])ads?([-_ ]|\d|$))/i;
  function adFrames() {
    var list = document.querySelectorAll('iframe:not([data-aegis]),ins.adsbygoogle:not([data-aegis])');
    for (var i = 0; i < list.length; i++) {
      var f = list[i], src = f.src || f.getAttribute('data-src') || '', idn = (f.id || '') + ' ' + (f.name || '');
      if (ADHOST.test(src) || ADID.test(idn)) hide(f);
    }
  }

  /* ================= Smart Ads: pengklasifikasi iklan berbasis fitur ================= */
  var AA = self.AegisAds;
  function smartAds() {
    if (!AA || isYT || !document.body) return;
    var res = AA.scan(document, { site: myReg.split('.').slice(-2).join('.'), vw: innerWidth || 1, vh: innerHeight || 1 });
    for (var i = 0; i < res.length; i++) { hide(res[i].el); res[i].el.setAttribute('data-aegis-why', res[i].res.why.slice(0, 3).join(', ')); }
  }

  /* ================= penjadwal ================= */
  var tm = 0, tick = 0, lastSmart = 0;
  function run() {
    tm = 0; tick++;
    try {
      if (isYT && CFG.youtube) yt();
      adFrames();
      if (CFG.smartAds && tick < 400 && Date.now() - lastSmart > 900) { lastSmart = Date.now(); smartAds(); }
      if (CFG.walls && tick < 60) walls();
      if (CFG.overlays && tick % 3 === 1 && tick < 80) overlays();
      if (CFG.social) social();
      if (CFG.comments) comments();
      if (CFG.links) links();
      if (CFG.clickbait !== 'off') clickbait();
      if (CFG.phishing && tick < 40) phish();
    } catch (e) { }
    if (hiddenCount !== reported) { reported = hiddenCount; try { api.runtime.sendMessage({ type: 'hid', n: hiddenCount }).catch(function () { }); } catch (e) { } }
  }
  function sched() { if (tm) return; tm = setTimeout(run, 350); }
  function start() {
    run();
    try { new MutationObserver(sched).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) { }
    setInterval(function () { if (isYT && CFG.youtube) yt(); }, 120);
    /* halaman statis tidak memicu MutationObserver: pindai ulang berkala selama 30 dtk pertama dan saat load selesai */
    var sweeps = 0, iv = setInterval(function () { sched(); if (++sweeps > 20) clearInterval(iv); }, 1500);
    addEventListener('load', function () { setTimeout(sched, 200); });
    document.addEventListener('yt-navigate-finish', yt, true);
    document.addEventListener('focusin', function (e) { if (e.target && e.target.type === 'password') setTimeout(phish, 300); }, true);
  }
  function boot() { if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start(); }

  try {
    api.storage.local.get(['settings', 'siteOff']).then(function (st) {
      var s = st.settings || {}; for (var k in s) CFG[k] = s[k];
      var off = st.siteOff || [];
      if (!CFG.enabled || off.indexOf(myReg) >= 0 || off.indexOf(host) >= 0) return;
      boot();
    }, boot);
  } catch (e) { boot(); }
})();
