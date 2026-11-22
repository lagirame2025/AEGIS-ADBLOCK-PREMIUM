/* Aegis Smart Ads (lokal, tanpa kirim data): pengklasifikasi iklan berbasis fitur (regresi logistik berbobot).
   Mengenali elemen iklan yang lolos dari daftar filter: slot iklan bernama aneh, banner native, iklan "disamarkan"
   sebagai konten. Memberi skor tiap kandidat dari sinyal struktural (nama, label, ukuran IAB, iframe lintas-situs,
   tautan sponsor, posisi menempel) dikurangi sinyal konten asli (artikel, formulir, pemutar video, navigasi).
   score() murni (bisa diuji di Node); extract() membaca DOM. UMD. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.AegisAds = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  var BIAS = -2.2, THRESHOLD = 1.0; // z >= 1.0  <=>  p >= ~0.73
  var W = { token: 1.5, label: 1.3, dataAttr: 2.3, iab: 1.3, crossFrame: 1.0, adHost: 2.2, sticky: 0.9, sponsoredLink: 1.0,
    outboundImg: 0.8, empty: 0.5, trackingLink: 0.7,
    mainContent: -2.0, form: -1.5, video: -1.2, navLike: -1.0, longText: -1.0, weakToken: 0.0 };
  var IAB = { '300x250': 1, '728x90': 1, '320x50': 1, '300x600': 1, '160x600': 1, '970x250': 1, '336x280': 1, '320x100': 1, '468x60': 1, '970x90': 1, '120x600': 1, '300x50': 1, '250x250': 1, '200x200': 1, '580x400': 1 };
  var TOKEN = /^(ad|ads|adv|advert|adverts|advertisement|advertising|adslot|adunit|adbox|adbanner|adwrap|adwrapper|adcontainer|iklan|sponsor|sponsored|sponsors|dfp|gpt|banneriklan|promoted)$/;
  var LABEL = /^(sponsored|disponsori|bersponsor|iklan|ad|ads|advertisement|advertisements|promoted|dipromosikan|sponsor|iklan\s*:?|publicidad|werbung|annonce|anzeige)$/i;
  var ADHOST = /(doubleclick|googlesyndication|googleadservices|adservice\.|adnxs|taboola|outbrain|criteo|amazon-adsystem|adsterra|propeller|popads|exoclick|rubiconproject|pubmatic|openx\.net|smartadserver|teads\.tv|mgid\.com|revcontent|adsrvr|casalemedia|adform\.net|juicyads|clickadu|hilltopads|zedo|yllix|adskeeper|dable\.io|bidvertiser)/i;
  var TRACK = /(\/aclk|\/click\?|adclick|[?&](utm_medium=(cpc|paid|display)|gclid=|fbclid=|dclid=|clickid=|ad_id=))/i;

  /** f: fitur numerik/boolean -> {z, p, hide, why[]} */
  function score(f) {
    var z = BIAS, why = [];
    function add(k, label) { if (f[k]) { z += W[k]; if (W[k] > 0 && label) why.push(label); } }
    add('token', 'nama kelas/id iklan'); add('label', 'label "Iklan/Sponsored"'); add('dataAttr', 'atribut data-ad');
    add('iab', 'ukuran slot iklan standar'); add('crossFrame', 'iframe lintas-situs'); add('adHost', 'host jaringan iklan');
    add('sticky', 'menempel di layar'); add('sponsoredLink', 'tautan sponsor'); add('outboundImg', 'gambar menuju situs luar');
    add('empty', 'tanpa teks'); add('trackingLink', 'tautan berparameter iklan');
    ['mainContent', 'form', 'video', 'navLike', 'longText'].forEach(function (k) { if (f[k]) z += W[k]; });
    var p = 1 / (1 + Math.exp(-z));
    return { z: z, p: p, hide: z >= THRESHOLD && why.length >= 2, why: why };
  }

  function tokens(s) { return String(s || '').toLowerCase().split(/[^a-z0-9]+/).filter(Boolean); }
  function site(h) { var l = String(h).toLowerCase().split('.'); return l.length <= 2 ? l.join('.') : l.slice(-2).join('.'); }

  /** Kandidat murah: elemen yang punya sinyal nama/atribut/label; sisanya diabaikan agar ringan. */
  var CAND = 'iframe,ins,div[id],div[class],aside,section[id],section[class],a[target="_blank"][rel*="sponsored"],[data-ad],[data-ad-slot],[data-ad-client],[data-google-query-id],[data-advertisement]';

  function extract(el, ctx) {
    var cs, r, f = {}, tag = el.tagName.toLowerCase();
    var idc = (el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : '');
    var tk = tokens(idc), i;
    for (i = 0; i < tk.length; i++) if (TOKEN.test(tk[i])) { f.token = 1; break; }
    for (i = 0; i < el.attributes.length; i++) { var n = el.attributes[i].name; if (/^data-(ad|adslot|ad-|google-query|advertis)/.test(n)) { f.dataAttr = 1; break; } }
    try { cs = getComputedStyle(el); r = el.getBoundingClientRect(); } catch (e) { return null; }
    if (!r || r.width < 20 || r.height < 12) return null;
    var size = Math.round(r.width) + 'x' + Math.round(r.height);
    if (IAB[size] || IAB[(Math.round(r.width / 2) * 2) + 'x' + (Math.round(r.height / 2) * 2)]) f.iab = 1;
    var txt = (el.innerText || '').trim();
    if (tag === 'iframe') {
      var src = el.src || el.getAttribute('data-src') || '';
      try { if (src && src !== 'about:blank' && site(new URL(src, location.href).hostname) !== ctx.site) f.crossFrame = 1; } catch (e) { }
      if (ADHOST.test(src)) f.adHost = 1;
    } else {
      // label pendek ("Iklan", "Sponsored") di dalam elemen
      var leaves = el.querySelectorAll('span,p,div,small,b,label,h6'), c = 0;
      for (i = 0; i < leaves.length && c < 40; i++) {
        var L = leaves[i]; if (L.children.length) continue; c++;
        var t = (L.textContent || '').trim(); if (t.length && t.length <= 20 && LABEL.test(t)) { f.label = 1; break; }
      }
      var ifr = el.querySelector('iframe[src]');
      if (ifr) { try { var u = new URL(ifr.src, location.href); if (site(u.hostname) !== ctx.site) f.crossFrame = 1; if (ADHOST.test(u.href)) f.adHost = 1; } catch (e) { } }
      var as = el.querySelectorAll('a[href]');
      for (i = 0; i < as.length && i < 12; i++) {
        var a = as[i], rel = (a.getAttribute('rel') || '').toLowerCase();
        if (rel.indexOf('sponsored') >= 0) f.sponsoredLink = 1;
        if (TRACK.test(a.href)) f.trackingLink = 1;
        try { if (a.querySelector('img') && site(new URL(a.href, location.href).hostname) !== ctx.site && txt.length < 60) f.outboundImg = 1; } catch (e) { }
      }
      if (ADHOST.test(el.innerHTML.slice(0, 4000))) f.adHost = 1;
    }
    if (txt.length < 24 && (tag === 'iframe' || el.querySelector('img,iframe,ins'))) f.empty = 1;
    if ((cs.position === 'fixed' || cs.position === 'sticky') && r.height < 180 && r.width > ctx.vw * 0.5) f.sticky = 1;
    var anc = el.closest('main,article,[role=main],nav,header,footer,[role=navigation],[role=banner],[role=contentinfo]');
    if (anc) { var at = anc.tagName.toLowerCase(), rl = anc.getAttribute('role') || ''; if (at === 'main' || at === 'article' || rl === 'main') f.mainContent = f.mainContent || (txt.length > 400 ? 1 : 0); else f.navLike = 1; }
    if (txt.length > 600) f.longText = 1;
    if (el.querySelector('form,input,textarea,select,button[type=submit]')) f.form = 1;
    var vd = el.querySelector('video,audio'); if (vd && (vd.controls || vd.getAttribute('controls') !== null || r.width > 300)) f.video = 1;
    if (r.width * r.height > ctx.vw * ctx.vh * 0.5) return null; // elemen sebesar halaman bukan iklan
    return f;
  }

  /** Pindai satu dokumen; kembalikan daftar {el, res} yang layak disembunyikan. */
  function scan(doc, ctx, done) {
    var out = [], list = doc.querySelectorAll(CAND), n = 0;
    for (var i = 0; i < list.length && n < 400; i++) {
      var e = list[i]; if (e.hasAttribute('data-aegis')) continue;
      var tr = +(e.getAttribute('data-aegis-a') || 0); if (tr >= 3) continue; // beri 3 kesempatan (konten bisa dimuat belakangan)
      e.setAttribute('data-aegis-a', String(tr + 1)); n++;
      var f = extract(e, ctx); if (!f) continue;
      if (!(f.token || f.label || f.dataAttr || f.adHost || f.sticky || f.sponsoredLink)) continue;
      var res = score(f); if (res.hide) out.push({ el: e, res: res });
    }
    return out;
  }

  return { score: score, extract: extract, scan: scan, THRESHOLD: THRESHOLD, W: W, BIAS: BIAS };
});
