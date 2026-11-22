/* Aegis Smart Engine (lokal, tanpa kirim data). Penilai risiko host/URL: judi online (judol), scam/phishing,
   penyamaran merek, pemendek tautan. Algoritma IDENTIK dengan windows/internal/smart/smart.go
   (dijaga oleh shared/vectors.json). UMD: dipakai service worker, content script, halaman, dan Node. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.AegisSmart = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  var R = null, SLD = {}, TLDW = {}, TRUSTED = [], SHORT = {}, SHORTBR = {}, LURE = {};
  function init(rules) {
    R = rules; SLD = {}; TLDW = rules.tld_weights; TRUSTED = rules.trusted; SHORT = {}; SHORTBR = {}; LURE = {};
    rules.sld_markers.forEach(function (x) { SLD[x] = 1; });
    rules.shorteners.forEach(function (x) { SHORT[x] = 1; });
    rules.short_brand_tokens.forEach(function (x) { SHORTBR[x] = 1; });
    rules.lure_terms.forEach(function (x) { LURE[x] = 1; });
    return api;
  }
  function registrable(h) {
    var l = h.split('.'), n = l.length;
    if (n <= 2) return h;
    return (l[n - 1].length === 2 && SLD[l[n - 2]]) ? l.slice(-3).join('.') : l.slice(-2).join('.');
  }
  function isIP(h) { return /^\d{1,3}(\.\d{1,3}){3}$/.test(h) || h.indexOf(':') >= 0; }
  function isPrivateIP(h) {
    if (h.indexOf(':') >= 0) return h === '::1' || /^f[cd]/.test(h) || /^fe80/.test(h);
    var p = h.split('.').map(Number);
    return p[0] === 10 || p[0] === 127 || p[0] === 0 || (p[0] === 192 && p[1] === 168) || (p[0] === 172 && p[1] >= 16 && p[1] <= 31) || (p[0] === 169 && p[1] === 254);
  }
  function under(h, reg, d) { return reg === d || h === d || h.slice(-(d.length + 1)) === '.' + d; }
  function trusted(h, reg) { for (var i = 0; i < TRUSTED.length; i++) if (under(h, reg, TRUSTED[i])) return true; return false; }

  /** analyzeHost(host) -> {score, cls: trusted|ok|warn|scam|judol, reasons:[...], judol:Number} */
  function analyzeHost(host, url) {
    var h = String(host || '').toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '');
    var out = { score: 0, cls: 'ok', reasons: [], judol: 0 };
    if (!h) return out;
    var reg = registrable(h);
    if (trusted(h, reg)) { out.cls = 'trusted'; return out; }
    var score = 0, why = out.reasons;
    var labels = h.split('.'), n = labels.length;
    if (isIP(h)) {
      if (isPrivateIP(h)) return out;
      score += 45; why.push('alamat IP langsung'); out.score = Math.min(100, score); out.cls = out.score >= R.thresholds.block ? 'scam' : (out.score >= R.thresholds.warn ? 'warn' : 'ok'); return out; }
    var tld = labels[n - 1];
    var suffixLen = reg.split('.').length === 3 ? 2 : 1;
    var textLabels = labels.slice(0, Math.max(0, n - suffixLen));
    var sld = textLabels.length ? textLabels[textLabels.length - 1] : '';
    var tw = TLDW[tld] || 0; score += tw;
    for (var i = 0; i < labels.length; i++) if (labels[i].indexOf('xn--') === 0) { score += 25; why.push('domain punycode (mirip huruf lain)'); break; }
    if (n > 5) { score += 10; why.push('subdomain terlalu panjang'); }
    var hy = (sld.match(/-/g) || []).length; if (hy >= 3) { score += 10; why.push('banyak tanda hubung'); }
    var dg = (sld.match(/\d/g) || []).length; if (sld.length >= 6 && dg / sld.length > 0.4) { score += 8; why.push('banyak angka'); }
    if (sld.length > 30) { score += 8; why.push('nama domain sangat panjang'); }
    var text = textLabels.join('.');
    var tokens = text.split(/[^a-z]+/).filter(Boolean), compact = text.replace(/[^a-z]/g, '');
    var tokSet = {}; tokens.forEach(function (t) { tokSet[t] = 1; });
    // --- judol
    var j = 0;
    R.judol_terms.forEach(function (e) {
      if (e.m === 'sub' ? compact.indexOf(e.t) >= 0 : tokSet[e.t]) j += e.w;
    });
    if (j > 100) j = 100;
    if (j > 0 && /\d{2,4}$/.test(sld)) j += 10;
    if (j > 0) { score += j; why.push('pola situs judi online'); }
    out.judol = j + tw;
    // --- brand spoof
    var brandHit = false;
    for (var b in R.brands) {
      var present = SHORTBR[b] || b.length < 6 ? !!tokSet[b] : compact.indexOf(b) >= 0;
      if (!present) continue;
      var off = R.brands[b], ok = false;
      for (var k = 0; k < off.length; k++) if (under(h, reg, off[k])) { ok = true; break; }
      if (!ok) { brandHit = true; score += 45; why.push('menyamar sebagai "' + b + '"'); break; }
    }
    // --- lure
    var lures = 0; tokens.forEach(function (t, idx) { if (LURE[t] && tokens.indexOf(t) === idx) lures++; });
    if (lures) { score += Math.min(24, 8 * lures); why.push('kata pancingan (login/klaim/hadiah...)'); }
    if (brandHit && lures) score += 20;
    if (SHORT[reg]) { score += 30; why.push('pemendek tautan'); }
    // --- url extras
    if (url) {
      var u = String(url);
      var m = u.match(/^[a-z][a-z0-9+.-]*:\/\/([^\/?#]*)/i);
      if (m && m[1].indexOf('@') >= 0) { score += 30; why.push('mengandung @ (menyembunyikan tujuan asli)'); }
      if (/\.apk([?#]|$)/i.test(u.split('?')[0])) { score += 25; why.push('mengunduh file APK'); }
    }
    out.score = Math.min(100, score);
    if (out.judol >= R.judol_class_min) out.cls = 'judol';
    else if (out.score >= R.thresholds.block) out.cls = 'scam';
    else if (out.score >= R.thresholds.warn) out.cls = 'warn';
    return out;
  }
  function analyzeUrl(u) {
    try { var x = new URL(u); if (x.protocol !== 'http:' && x.protocol !== 'https:') return { score: 0, cls: 'ok', reasons: [], judol: 0 }; return analyzeHost(x.hostname, u); }
    catch (e) { return { score: 0, cls: 'ok', reasons: [], judol: 0 }; }
  }

  /* ---------- clickbait (teks) ---------- */
  var CB_PHRASES = ['ternyata', 'tidak menyangka', 'gak nyangka', 'nggak nyangka', 'wajib tonton', 'wajib baca', 'viral', 'heboh', 'bikin geger',
    'bikin kaget', 'bikin nangis', 'kamu pasti', 'anda pasti', 'rahasia', 'terungkap', 'mengejutkan', 'sungguh', 'jangan sampai', 'sebelum dihapus',
    'you won', 'will shock', 'you won\'t believe', 'this is why', 'what happens next', 'doctors hate', 'gone wrong', 'shocking', 'mind-blowing',
    'nomor 5', 'no. 5', 'bikin merinding', 'auto kaya', 'cara cepat kaya', 'jutaan rupiah'];
  function clickbait(t) {
    t = String(t || '').trim();
    if (t.length < 12) return { score: 0, reasons: [] };
    var s = 0, why = [], low = t.toLowerCase();
    var letters = t.replace(/[^A-Za-z]/g, ''), up = t.replace(/[^A-Z]/g, '');
    if (letters.length >= 12 && up.length / letters.length > 0.6) { s += 25; why.push('HURUF KAPITAL berlebihan'); }
    if (/[!?]{2,}/.test(t)) { s += 15; why.push('tanda seru/tanya berulang'); }
    var hits = 0; CB_PHRASES.forEach(function (p) { if (low.indexOf(p) >= 0) hits++; });
    if (hits) { s += Math.min(50, 25 * hits); why.push('frasa pancingan klik'); }
    if (/^\d{1,2}\s+(cara|fakta|alasan|hal|tips|things|ways|reasons|facts)/i.test(t)) { s += 15; why.push('judul daftar angka'); }
    if ((t.match(/\p{Extended_Pictographic}/gu) || []).length >= 3) { s += 10; why.push('emoji berlebihan'); }
    if (/\.\.\.$|…$/.test(t)) { s += 10; why.push('kalimat menggantung'); }
    return { score: Math.min(100, s), reasons: why };
  }

  var api = { init: init, analyzeHost: analyzeHost, analyzeUrl: analyzeUrl, registrable: registrable, clickbait: clickbait };
  return api;
});
