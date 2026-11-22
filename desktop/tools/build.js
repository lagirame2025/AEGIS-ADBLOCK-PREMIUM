#!/usr/bin/env node
// Bangun paket ekstensi: node tools/build.js [--out dist]
// Hasil: dist/ext/{chromium,firefox,safari}/ + dist/ext-{chromium,firefox,safari}.zip
// chromium = Chrome, Edge, Opera, Brave, Vivaldi, dll (Manifest V3).
const fs = require('fs'), path = require('path'), cp = require('child_process');
const ROOT = path.join(__dirname, '..'), EXT = path.join(ROOT, 'extension');
const outArg = process.argv.indexOf('--out'); const OUT = path.resolve(outArg > 0 ? process.argv[outArg + 1] : path.join(ROOT, 'dist'));
const VERSION = JSON.parse(fs.readFileSync(path.join(EXT, 'version.json'))).version;

function rmrf(p) { fs.rmSync(p, { recursive: true, force: true }); }
function mkdir(p) { fs.mkdirSync(p, { recursive: true }); }
function copyDir(a, b) { mkdir(b); for (const e of fs.readdirSync(a, { withFileTypes: true })) { const s = path.join(a, e.name), d = path.join(b, e.name); e.isDirectory() ? copyDir(s, d) : fs.copyFileSync(s, d); } }

// ---------- aturan DNR statis ----------
const ALLOW = new Set(['googleapis.com', 'gstatic.com', 'github.com', 'githubusercontent.com', 'whatsapp.net', 'whatsapp.com', 'google.com', 'youtube.com', 'msftconnecttest.com', 'captive.apple.com', 'detectportal.firefox.com', 'clients3.google.com']);
function readDomains(file) { return fs.readFileSync(file, 'utf8').split(/\r?\n/).map(s => s.trim().toLowerCase()).filter(s => s && !s.startsWith('#') && /^[a-z0-9.-]+\.[a-z]{2,}$/.test(s) && !ALLOW.has(s)); }
function staticRules() {
  const ads = [...new Set(readDomains(path.join(ROOT, 'windows/internal/app/seed_ads.txt')))];
  const extra = path.join(EXT, 'data/extra_ads.txt'); if (fs.existsSync(extra)) ads.push(...readDomains(extra));
  const rules = []; let id = 1;
  for (let i = 0; i < ads.length; i += 1000) rules.push({ id: id++, priority: 1, action: { type: 'block' }, condition: { requestDomains: ads.slice(i, i + 1000) } });
  const generic = []; id = 1;
  const g = (cond) => generic.push({ id: id++, priority: 1, action: { type: 'block' }, condition: cond });
  // iklan YouTube (permintaan statistik/pelacakan iklan dan pemutar)
  for (const p of ['||youtube.com/api/stats/ads', '||youtube.com/pagead/', '||youtube.com/ptracking', '||youtube.com/get_midroll_info', '||youtube.com/youtubei/v1/player/ad_break', '||youtube.com/api/stats/atr'])
    g({ urlFilter: p, resourceTypes: ['xmlhttprequest', 'image', 'ping', 'script', 'other', 'sub_frame'] });
  // pola umum lintas-situs
  for (const p of ['/pagead/js/', '/pagead/ads', '/pagead/viewthroughconversion', '/adsbygoogle.js', '/ads/ga-audiences', '/pcs/activeview', '/gpt.js', '/show_ads_impl', '/prebid.js', '/prebid-', '/adframe.', '/popunder.', '/popads.'])
    g({ urlFilter: p, domainType: 'thirdParty', resourceTypes: ['script', 'xmlhttprequest', 'sub_frame', 'image', 'other'] });
  return { ads: rules, generic };
}

// ---------- manifest ----------
function manifest(target) {
  const m = {
    manifest_version: 3, name: 'Aegis AdBlock — Iklan, Judol & Scam', short_name: 'Aegis', version: VERSION,
    description: 'Pemblokir iklan agresif dengan AI lokal: iklan YouTube & media sosial, pop-up, clickbait, situs judol, tautan scam & phishing. Tanpa kirim data.',
    icons: { 16: 'icons/icon16.png', 32: 'icons/icon32.png', 48: 'icons/icon48.png', 128: 'icons/icon128.png' },
    action: { default_title: 'Aegis AdBlock', default_popup: 'popup/popup.html', default_icon: { 16: 'icons/icon16.png', 32: 'icons/icon32.png', 48: 'icons/icon48.png' } },
    permissions: ['declarativeNetRequest', 'declarativeNetRequestFeedback', 'storage', 'webNavigation', 'alarms', 'tabs'],
    host_permissions: ['<all_urls>'],
    content_scripts: [
      { matches: ['<all_urls>'], js: ['src/inject.js'], run_at: 'document_start', all_frames: true, match_about_blank: true, world: 'MAIN' },
      { matches: ['<all_urls>'], js: ['src/rules.js', 'src/smart.js', 'src/textsmart.js', 'src/adscore.js', 'src/content.js'], run_at: 'document_start', all_frames: true, match_about_blank: true }
    ],
    declarative_net_request: { rule_resources: [
      { id: 'ads', enabled: true, path: 'data/rules_ads.json' },
      { id: 'generic', enabled: true, path: 'data/rules_generic.json' } ] }
  };
  if (target !== 'safari') m.permissions.push('unlimitedStorage');
  if (target === 'chromium') { m.minimum_chrome_version = '111'; m.background = { service_worker: 'src/background.js' }; }
  if (target === 'firefox') {
    m.background = { scripts: ['src/rules.js', 'src/smart.js', 'src/background.js'] };
    m.browser_specific_settings = { gecko: { id: 'aegis-adblock@aegis.local', strict_min_version: '128.0', data_collection_permissions: { required: ['none'] } } };
  }
  if (target === 'safari') m.background = { scripts: ['src/rules.js', 'src/smart.js', 'src/background.js'], persistent: false };
  return m;
}

function zip(dir, file) {
  rmrf(file);
  try { cp.execFileSync('zip', ['-qr', '-X', file, '.'], { cwd: dir }); }
  catch (e) { console.error('peringatan: perintah "zip" tidak ada, lewati', file); }
}

function build() {
  rmrf(path.join(OUT, 'ext')); mkdir(path.join(OUT, 'ext'));
  const rules = JSON.parse(fs.readFileSync(path.join(ROOT, 'shared/smart-rules.json')));
  const sr = staticRules();
  for (const t of ['chromium', 'firefox', 'safari']) {
    const d = path.join(OUT, 'ext', t); mkdir(d);
    for (const sub of ['src', 'popup', 'pages', 'icons', 'stubs']) copyDir(path.join(EXT, sub), path.join(d, sub));
    mkdir(path.join(d, 'data'));
    fs.writeFileSync(path.join(d, 'src/rules.js'), 'self.AEGIS_RULES=' + JSON.stringify(rules) + ';\n');
    fs.writeFileSync(path.join(d, 'data/rules_ads.json'), JSON.stringify(sr.ads));
    fs.writeFileSync(path.join(d, 'data/rules_generic.json'), JSON.stringify(sr.generic));
    fs.writeFileSync(path.join(d, 'manifest.json'), JSON.stringify(manifest(t), null, 2));
    zip(d, path.join(OUT, 'ext-' + t + '.zip'));
    console.log('ok', t, '->', path.relative(process.cwd(), d));
  }
}
build();
