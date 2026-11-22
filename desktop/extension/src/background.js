/* Aegis - background (service worker di Chromium, skrip latar di Firefox/Safari).
   Tugas: pengaturan, pembaruan daftar blokir -> aturan DNR dinamis, penjaga navigasi (halaman scam/judol/phishing
   -> halaman peringatan), klasifikasi tautan untuk content script, statistik & lencana. Semua lokal. */
var api = typeof browser !== 'undefined' ? browser : chrome;
if (typeof importScripts === 'function' && !self.AegisSmart) { try { importScripts('rules.js', 'smart.js'); } catch (e) { } }
var R = self.AEGIS_RULES, S = self.AegisSmart.init(R);

var DEFAULTS = { enabled: true, social: true, comments: true, links: true, clickbait: 'mark', phishing: true, overlays: true,
  youtube: true, walls: true, smartAds: true, guard: true, autoUpdate: true };

/* Sumber daftar. c = kategori: ads (diblokir sebagai subresource), scam / judol (subresource diblokir + halaman diberi peringatan). */
var SOURCES = [
  { n: 'StevenBlack', u: 'https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts', c: 'ads' },
  { n: 'AdGuard DNS', u: 'https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt', c: 'ads' },
  { n: 'HaGeZi Pro', u: 'https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/pro.txt', c: 'ads' },
  { n: 'HaGeZi Pop-up Ads', u: 'https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/popupads.txt', c: 'ads' },
  { n: 'HaGeZi Gambling', u: 'https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/gambling.txt', c: 'judol' },
  { n: 'HaGeZi Fake/Scam', u: 'https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/fake.txt', c: 'scam' },
  { n: 'HaGeZi Threat Intel', u: 'https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/tif.mini.txt', c: 'scam' },
  { n: 'URLhaus', u: 'https://urlhaus.abuse.ch/downloads/hostfile/', c: 'scam' },
  { n: 'Phishing Army', u: 'https://phishing.army/download/phishing_army_blocklist_extended.txt', c: 'scam' }
];
var BASE = { ads: 1, scam: 200000, judol: 400000 };      // rentang ID aturan dinamis per kategori
var CHUNK = 1000;                                         // domain per aturan
var CAP = { ads: 250000, scam: 250000, judol: 250000 };
/* Tidak pernah diblokir oleh daftar (mencegah daftar yang keliru merusak layanan penting). */
var ALLOW = new Set(['localhost', 'googleapis.com', 'gstatic.com', 'github.com', 'githubusercontent.com', 'whatsapp.net', 'whatsapp.com',
  'google.com', 'youtube.com', 'ytimg.com', 'googlevideo.com', 'ggpht.com', 'msftconnecttest.com', 'captive.apple.com',
  'detectportal.firefox.com', 'clients3.google.com', 'microsoft.com', 'live.com', 'apple.com', 'icloud.com', 'cloudflare.com',
  'amazonaws.com', 'akamaihd.net', 'fastly.net', 'cloudfront.net', 'jsdelivr.net', 'cdnjs.cloudflare.com', 'unpkg.com']);

/* ---------- util ---------- */
var memSession = {};
function sget(keys) { return api.storage.local.get(keys); }
function sset(o) { return api.storage.local.set(o); }
function ssGet(k) {
  try { if (api.storage.session) return api.storage.session.get(k).then(function (r) { return r[k]; }); } catch (e) { }
  return Promise.resolve(memSession[k]);
}
function ssSet(k, v) {
  try { if (api.storage.session) { var o = {}; o[k] = v; return api.storage.session.set(o); } } catch (e) { }
  memSession[k] = v; return Promise.resolve();
}
function getSettings() { return sget('settings').then(function (r) { var s = {}, u = r.settings || {}; for (var k in DEFAULTS) s[k] = DEFAULTS[k]; for (var j in u) s[j] = u[j]; return s; }); }
function hostOf(u) { try { return new URL(u).hostname.toLowerCase(); } catch (e) { return ''; } }
function validDomain(d) { return d.length > 3 && d.length < 254 && /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$/.test(d) && !/^\d+(\.\d+){3}$/.test(d); }
function allowedDomain(d) {
  while (d) { if (ALLOW.has(d)) return true; var i = d.indexOf('.'); if (i < 0) break; d = d.slice(i + 1); }
  return false;
}
function trustedHost(h) { var a = S.analyzeHost(h); return a.cls === 'trusted'; }

/** Baca daftar dalam format hosts / adblock (||domain^) / satu domain per baris. */
function parseList(text) {
  var out = new Set(), lines = text.split(/\r?\n/);
  for (var i = 0; i < lines.length; i++) {
    var l = lines[i].trim(); if (!l || l[0] === '#' || l[0] === '!' || l[0] === '[') continue;
    var d = '';
    if (l.slice(0, 2) === '||') {
      var m = l.match(/^\|\|([a-z0-9.-]+)\^(\$.*)?$/i); if (!m) continue;
      if (m[2] && !/^\$(all|third-party|3p|document|popup|important)(,(all|third-party|3p|document|popup|important))*$/i.test(m[2])) continue;
      d = m[1];
    }
    else if (/^(0\.0\.0\.0|127\.0\.0\.1|::1?)\s+/.test(l)) d = l.split(/\s+/)[1] || '';
    else if (/^[a-z0-9.-]+$/i.test(l)) d = l;
    else continue;
    d = d.toLowerCase().replace(/^\*\./, '').replace(/\.$/, '');
    if (validDomain(d) && d !== 'localhost') out.add(d);
  }
  return out;
}

/* ---------- pembaruan daftar -> aturan DNR dinamis ---------- */
function chunkRules(cat, domains) {
  var arr = Array.from(domains), rules = [], id = BASE[cat];
  for (var i = 0; i < arr.length; i += CHUNK) {
    rules.push({ id: id++, priority: 1, action: { type: 'block' }, condition: { requestDomains: arr.slice(i, i + CHUNK) } });
  }
  return rules;
}
async function applyRules(cat, domains) {
  var cur = await api.declarativeNetRequest.getDynamicRules();
  var lo = BASE[cat], hi = lo + 150000;
  var rm = cur.filter(function (r) { return r.id >= lo && r.id < hi; }).map(function (r) { return r.id; });
  var add = chunkRules(cat, domains);
  if (rm.length) await api.declarativeNetRequest.updateDynamicRules({ removeRuleIds: rm });
  for (var i = 0; i < add.length; i += 25) await api.declarativeNetRequest.updateDynamicRules({ addRules: add.slice(i, i + 25) });
  return add.length;
}

var updating = null;
function updateLists(force) {
  if (updating) return updating;
  updating = (async function () {
    var meta = (await sget('listMeta')).listMeta || {};
    if (!force && meta.t && Date.now() - meta.t < 20 * 3600 * 1000) return meta;
    var cats = { ads: new Set(), scam: new Set(), judol: new Set() }, rep = {};
    await Promise.all(SOURCES.map(async function (s) {
      try {
        var r = await fetch(s.u, { cache: 'no-store' });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        var set = parseList(await r.text());
        set.forEach(function (d) { cats[s.c].add(d); });
        rep[s.n] = 'ok ' + set.size;
      } catch (e) { rep[s.n] = 'gagal: ' + (e && e.message || e); }
    }));
    var counts = meta.counts || {}, any = false;
    for (var c in cats) {
      var set = new Set();
      cats[c].forEach(function (d) { if (set.size < CAP[c] && !allowedDomain(d)) set.add(d); });
      var old = counts[c] || 0;
      if (!set.size || (old > 1000 && set.size < old * 0.5)) { rep['_' + c] = 'dipertahankan (hasil baru terlalu kecil: ' + set.size + ')'; continue; }
      await applyRules(c, set); counts[c] = set.size; any = true;
      if (c !== 'ads') { await sset((c === 'scam' ? { lst_scam: Array.from(set).join('\n') } : { lst_judol: Array.from(set).join('\n') })); sets = null; }
    }
    var m = { t: any ? Date.now() : (meta.t || 0), tried: Date.now(), counts: counts, report: rep };
    await sset({ listMeta: m });
    return m;
  })().then(function (m) { updating = null; return m; }, function (e) { updating = null; throw e; });
  return updating;
}

/* ---------- himpunan scam/judol (untuk klasifikasi tautan & penjaga navigasi) ---------- */
var sets = null;
async function getSets() {
  if (sets) return sets;
  var r = await sget(['lst_scam', 'lst_judol']);
  sets = { scam: new Set((r.lst_scam || '').split('\n').filter(Boolean)), judol: new Set((r.lst_judol || '').split('\n').filter(Boolean)) };
  return sets;
}
function inSet(set, h) {
  while (h) { if (set.has(h)) return h; var i = h.indexOf('.'); if (i < 0) break; h = h.slice(i + 1); }
  return '';
}
/** Gabungan: daftar (scam/judol) + heuristik. Hasil {cls: ok|trusted|warn|scam|judol, score, why, src} */
async function classifyHost(h, url) {
  if (!h) return { cls: 'ok', score: 0, why: '' };
  var a = S.analyzeHost(h, url);
  if (a.cls === 'trusted') return { cls: 'trusted', score: 0, why: '' };
  if (!allowedDomain(h)) {
    var st = await getSets(), hit;
    if ((hit = inSet(st.judol, h))) return { cls: 'judol', score: 100, why: 'ada di daftar situs judi online (' + hit + ')', src: 'list' };
    if ((hit = inSet(st.scam, h))) return { cls: 'scam', score: 100, why: 'ada di daftar situs penipuan/phishing/malware (' + hit + ')', src: 'list' };
  }
  return { cls: a.cls, score: a.score, why: a.reasons.slice(0, 3).join('; '), src: 'heuristic' };
}

/* ---------- penjaga navigasi ---------- */
function warnUrl(u, c) {
  return api.runtime.getURL('pages/warning.html') + '?u=' + encodeURIComponent(u) + '&cls=' + encodeURIComponent(c.cls) + '&s=' + c.score + '&w=' + encodeURIComponent(c.why || '');
}
async function isTempAllowed(h) {
  var m = (await ssGet('allow')) || {}, now = Date.now(), d = h;
  while (d) { if (m[d] && m[d] > now) return true; var i = d.indexOf('.'); if (i < 0) break; d = d.slice(i + 1); }
  return false;
}
if (api.webNavigation) {
  api.webNavigation.onBeforeNavigate.addListener(function (d) {
    if (d.frameId !== 0 || !/^https?:/i.test(d.url)) return;
    (async function () {
      var st = (await ssGet('nav')) || {}; st[d.tabId] = d.url; await ssSet('nav', st);
      var s = await getSettings(); if (!s.enabled || !s.guard) return;
      var h = hostOf(d.url); if (!h || await isTempAllowed(h)) return;
      var off = (await sget('siteOff')).siteOff || []; if (off.indexOf(h) >= 0) return;
      var c = await classifyHost(h, d.url);
      if (c.cls === 'scam' || c.cls === 'judol') {
        var k = 'blk_' + c.cls; var n = (await sget('stats')).stats || {}; n[k] = (n[k] || 0) + 1; await sset({ stats: n });
        api.tabs.update(d.tabId, { url: warnUrl(d.url, c) }).catch(function () { });
      }
    })().catch(function () { });
  });
}

/* ---------- statistik & lencana ---------- */
var hidden = {};
function badge(tabId) {
  var n = 0, t = hidden[tabId]; if (t) for (var f in t) n += t[f];
  try { (api.action || api.browserAction).setBadgeText({ tabId: tabId, text: n ? String(n > 999 ? '999+' : n) : '' }); (api.action || api.browserAction).setBadgeBackgroundColor({ tabId: tabId, color: '#1b6b3a' }); } catch (e) { }
}
if (api.tabs) {
  api.tabs.onRemoved.addListener(function (id) { delete hidden[id]; });
  if (api.webNavigation) api.webNavigation.onCommitted.addListener(function (d) { if (d.frameId === 0) { delete hidden[d.tabId]; badge(d.tabId); } });
}

/* ---------- pesan dari content script / popup / halaman peringatan ---------- */
api.runtime.onMessage.addListener(function (m, sender, send) {
  if (!m || !m.type) return;
  var run = (async function () {
    switch (m.type) {
      case 'classify': {
        var map = {}, hs = (m.hosts || []).slice(0, 100);
        for (var i = 0; i < hs.length; i++) {
          var c = await classifyHost(hs[i]);
          map[hs[i]] = { cls: c.cls === 'scam' ? 'scam' : c.cls === 'judol' ? 'judol' : 'ok', why: c.why };
        }
        return { map: map };
      }
      case 'hid': {
        if (sender.tab) { var t = hidden[sender.tab.id] || (hidden[sender.tab.id] = {}); t[sender.frameId || 0] = m.n; badge(sender.tab.id); }
        return { ok: 1 };
      }
      case 'status': {
        var url = m.url || '', h = hostOf(url), n = 0, t2 = hidden[m.tabId];
        if (t2) for (var f in t2) n += t2[f];
        var blocked = 0;
        try { var mr = await api.declarativeNetRequest.getMatchedRules({ tabId: m.tabId }); blocked = (mr.rulesMatchedInfo || []).length; } catch (e) { }
        var meta = (await sget(['listMeta', 'stats'])), cls = h && /^https?:/.test(url) ? await classifyHost(h, url) : null;
        var ana = h ? S.analyzeUrl(url) : null;
        return { host: h, hidden: n, blocked: blocked, meta: meta.listMeta || null, stats: meta.stats || {}, risk: cls, reasons: ana ? ana.reasons : [] };
      }
      case 'update': return await updateLists(true);
      case 'navinfo': {
        var st = (await ssGet('nav')) || {}; return { url: st[m.tabId] || '' };
      }
      case 'allow': {
        var h2 = (m.host || '').toLowerCase(); if (!h2) return { ok: 0 };
        var al = (await ssGet('allow')) || {}; al[h2] = Date.now() + (m.minutes || 60) * 60000; await ssSet('allow', al); return { ok: 1 };
      }
      case 'siteOff': {
        var off = (await sget('siteOff')).siteOff || [], h3 = (m.host || '').toLowerCase(), ix = off.indexOf(h3);
        if (m.off && ix < 0) off.push(h3); if (!m.off && ix >= 0) off.splice(ix, 1);
        await sset({ siteOff: off }); return { ok: 1 };
      }
    }
  })();
  run.then(send, function (e) { send({ error: String(e && e.message || e) }); });
  return true;
});

/* ---------- siklus hidup ---------- */
function setup() {
  try { api.declarativeNetRequest.setExtensionActionOptions({ displayActionCountAsBadgeText: false }); } catch (e) { }
  try { if (api.alarms) api.alarms.create('aegis-update', { periodInMinutes: 360 }); } catch (e) { }
}
if (api.alarms) api.alarms.onAlarm.addListener(function (a) {
  if (a.name !== 'aegis-update') return;
  getSettings().then(function (s) { if (s.enabled && s.autoUpdate) updateLists(false).catch(function () { }); });
});
api.runtime.onInstalled.addListener(function (d) {
  setup();
  getSettings().then(function (s) { sset({ settings: s }); if (s.autoUpdate) updateLists(true).catch(function () { }); });
});
api.runtime.onStartup && api.runtime.onStartup.addListener(function () { setup(); getSettings().then(function (s) { if (s.enabled && s.autoUpdate) updateLists(false).catch(function () { }); }); });
