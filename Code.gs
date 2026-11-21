// Buku Cha Tea Solo - backend Google Sheets (versi Karyawan + Owner).
// Tempel di Extensions > Apps Script. Ganti KUNCI, jalankan setup(), lalu Deploy sebagai Web app (Anyone).
const KUNCI = 'GANTI-KUNCI-KARYAWAN';        // dipakai aplikasi Karyawan
const KUNCI_OWNER = 'GANTI-KUNCI-OWNER-BEDA';  // dipakai aplikasi Owner SAJA (harus berbeda)
const REQ_TTL = 30 * 60 * 1000;                // kode persetujuan berlaku 30 menit
const RP = '"Rp. "#,##0';
const HD = ['Tanggal','Shift','Uang makan','Uang gaji','Pengeluaran lain','Keterangan','Cup','Omzet','QRIS','Tunai','Total pengeluaran','Total tunai bersih'];

// ---------- fungsi murni (tanpa Google API) ----------
const SAFE = /^[A-Za-z0-9_-]{1,40}$/, DAY = /^\d{4}-\d{2}-\d{2}$/;
function num(v, lo, hi, d) { v = Number(v); return isFinite(v) ? Math.min(hi, Math.max(lo, v)) : d; }
// Bersihkan transaksi dari HP: hanya tipe & karakter aman (mencegah kode berbahaya & angka aneh).
function cleanTx(t) {
  if (!t || typeof t !== 'object' || !SAFE.test(String(t.id))) return null;
  return {id: String(t.id), m: String(t.m || '').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 40), n: String(t.n || '').slice(0, 60),
    p: num(t.p, 0, 100000000, 0), q: Math.round(num(t.q, 1, 999, 1)), pay: t.pay === 'qris' ? 'qris' : 'tunai',
    c: ['B','K','T'].indexOf(t.c) >= 0 ? t.c : 'B', s: String(t.s || '').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 30),
    u: num(t.u, 0, 1000, 1), t: String(t.t || '').replace(/[^0-9:]/g, '').slice(0, 5)};
}
function cleanRows(a) { return (Array.isArray(a) ? a : []).slice(0, 50).map(function (e) { return {n: String((e && e.n) || '').slice(0, 80), a: num(e && e.a, 0, 1000000000, 0)}; }); }
// Harga/nama/bahan transaksi karyawan dipaksa sama dengan menu milik owner (anti-palsu harga).
function priceFix(t, cfg) {
  const m = cfg && (cfg.menus || []).filter(function (x) { return x && x.id === t.m; })[0];
  if (!m) return t;
  t.p = num(m.p, 0, 100000000, 0); t.n = String(m.n).slice(0, 60); t.s = String(m.s || '').replace(/[^A-Za-z0-9_-]/g, '').slice(0, 30); t.u = num(m.u, 0, 1000, 1);
  if (t.c !== 'T') t.c = m.c === 'K' ? 'K' : 'B';
  return t;
}
// Gabungkan data transaksi dari banyak HP tanpa saling menimpa.
// allow == null  -> OWNER (dipercaya): boleh hapus; boleh mengubah transaksi yang tercantum di inc.mod.
// allow = {edit:{tid:rid}, del:{tid:rid}} -> KARYAWAN: transaksi lama TERKUNCI. Hanya boleh menambah transaksi baru,
// kecuali ada persetujuan owner (kode sekali pakai yang sudah diverifikasi) untuk transaksi itu.
function mergeE(old, inc, allow, cfg) {
  old = old || {}; inc = inc || {};
  const trusted = !allow, applied = [], del = {};
  (old.del || []).forEach(function (i) { del[i] = 1; });
  (inc.del || []).filter(function (i) { return SAFE.test(String(i)); }).forEach(function (i) {
    if (trusted) del[i] = 1;
    else if (allow.del && allow.del[i]) { del[i] = 1; applied.push({tid: i, act: 'del', rid: allow.del[i]}); }
  });
  const m = {};
  (old.tx || []).forEach(function (t) { if (t && t.id) m[t.id] = t; });
  (inc.tx || []).forEach(function (t) {
    t = cleanTx(t); if (!t) return; if (!trusted) t = priceFix(t, cfg);
    if (del[t.id]) return;
    if (!m[t.id]) { m[t.id] = t; return; }                                     // transaksi baru: selalu boleh
    if (trusted) { if ((inc.mod || []).indexOf(t.id) >= 0) m[t.id] = t; return; }  // owner: hanya yang diubah owner
    if (allow.edit && allow.edit[t.id] && JSON.stringify(m[t.id]) !== JSON.stringify(t)) {
      m[t.id] = t; applied.push({tid: t.id, act: 'edit', rid: allow.edit[t.id]});
    }
  });
  Object.keys(del).forEach(function (k) { delete m[k]; });
  const tx = Object.keys(m).map(function (k) { return m[k]; }).sort(function (a, b) { return String(a.id).localeCompare(String(b.id), undefined, {numeric: true}); });
  const r = {tx: tx, del: Object.keys(del), exp: old.exp || [], kx: old.kx || [], applied: applied};
  if (inc.ex) { r.exp = cleanRows(inc.exp); r.kx = cleanRows(inc.kx); }
  return r;
}
// ---- permintaan persetujuan (kode sekali pakai) ----
function newCode() { return String(100000 + Math.floor(Math.random() * 900000)); }
function reqCreate(b, now) {
  return {rid: String(b.rid), day: b.day, tid: b.tid, act: b.act === 'del' ? 'del' : 'edit', desc: String(b.desc || '').slice(0, 160),
          reason: String(b.reason || '').slice(0, 200), st: 'pending', at: now, code: null, exp: now + 12 * 3600 * 1000, tries: 0};
}
function reqApprove(q, now, code) {
  if (q.st !== 'pending' && q.st !== 'approved') return {ok: false, err: 'status ' + q.st};
  q.st = 'approved'; q.code = code; q.exp = now + REQ_TTL; q.tries = 0; return {ok: true};
}
function reqRedeem(q, code, now) {
  if (q.st === 'locked') return {ok: false, err: 'terkunci, minta kode baru'};
  if (q.st !== 'approved') return {ok: false, err: 'belum disetujui'};
  if (now > q.exp) { q.st = 'expired'; return {ok: false, err: 'kode kedaluwarsa'}; }
  if (String(code) !== String(q.code)) { q.tries = (q.tries || 0) + 1; if (q.tries >= 5) q.st = 'locked'; return {ok: false, err: 'kode salah'}; }
  q.st = 'redeemed'; q.exp = now + REQ_TTL; return {ok: true};
}
function reqAllow(qs, day, now) {
  const a = {edit: {}, del: {}};
  qs.forEach(function (q) { if (q.day === day && q.st === 'redeemed' && now <= q.exp) a[q.act][q.tid] = q.rid; });
  return a;
}
// status efektif: permintaan yang masa berlakunya habis dianggap 'expired'
function reqLive(q, now) { return ['pending','approved','redeemed'].indexOf(q.st) >= 0 && now <= q.exp; }
function reqEff(q, now) { return (['pending','approved','redeemed'].indexOf(q.st) >= 0 && now > q.exp) ? 'expired' : q.st; }
function reqPublic(q, now) { return {rid: q.rid, day: q.day, tid: q.tid, act: q.act, desc: q.desc, reason: q.reason, st: reqEff(q, now), at: q.at}; }
// Susun data semua hari dari baris sheet "_data": [kunci, json]. E:tanggal = karyawan, O:tanggal = pemilik.
function buildDays(rows) {
  const E = {}, O = {}, L = {}; let cfg = null;
  rows.forEach(function (r) {
    const k = String(r[0]); let j; try { j = JSON.parse(r[1]); } catch (x) { return; }
    if (k === 'cfg') { cfg = j; return; }
    const m = k.match(/^([EO]):(\d{4}-\d{2}-\d{2})$/);
    if (m) (m[1] === 'E' ? E : O)[m[2]] = j; else if (/^\d{4}-\d{2}-\d{2}$/.test(k)) L[k] = j;
  });
  const days = {};
  [].concat(Object.keys(E), Object.keys(O), Object.keys(L)).forEach(function (d) {
    if (days[d]) return;
    const l = L[d] || {}, e = E[d], o = O[d] || {}, x = Object.assign({}, l, o);
    if (e) { x.tx = e.tx || []; x.exp = (e.exp && e.exp.length) ? e.exp : (l.exp || o.exp); x.kx = (e.kx && e.kx.length) ? e.kx : (l.kx || o.kx); }
    days[d] = x;
  });
  return {days: days, cfg: cfg};
}

// ---------- Google Sheets ----------
function sh(n, hd) {
  const ss = SpreadsheetApp.getActive(); let s = ss.getSheetByName(n);
  if (!s) { s = ss.insertSheet(n);
    if (hd) { s.getRange(1,1,1,hd.length).setValues([hd]).setFontWeight('bold').setBackground('#1f6b4a').setFontColor('#ffffff'); s.setFrozenRows(1); } }
  return s;
}
function setup() {
  sh('Transaksi',['Tanggal','Jam','Menu','Qty','Harga','Jumlah','Bayar','ID']).getRange('A:A').setNumberFormat('@');
  sh('Pengeluaran',['Tanggal','Jenis','Keterangan','Jumlah']).getRange('A:A').setNumberFormat('@');
  sh('Pengeluaran').getRange('D:D').setNumberFormat(RP);
  sh('Harian',HD).getRange('A:A').setNumberFormat('@');
  sh('Stok',['Tanggal','Bahan','Awal','Tambah','Terpakai','Sisa']).getRange('A:A').setNumberFormat('@');
  sh('_data',['Kunci','JSON']).getRange('A:A').setNumberFormat('@'); sh('_data').hideSheet();
  sh('_req',['rid','json']).hideSheet(); sh('Persetujuan',['Waktu','ID','Tanggal','Aksi','Transaksi','Alasan','Status']);
  sh('Stok Lainnya',['Tanggal','Barang','Satuan','Masuk','Keluar manual','Pakai otomatis','Sisa']).getRange('A:A').setNumberFormat('@');
  sh('Daftar Belanja',['Barang','Sisa','Satuan','Pakai/hari','Habis dalam (hari)','Perkiraan habis','Saran beli','Status']);
  sh('Transaksi').getRange('E:F').setNumberFormat(RP);
  const h = sh('Harian'); h.getRange('C:E').setNumberFormat(RP); h.getRange('H:L').setNumberFormat(RP);
  const m = sh('Laporan Menu'); m.clear();
  m.getRange('A1').setFormula('=QUERY(Transaksi!C2:F,"select Col1,sum(Col2),sum(Col4) where Col1 is not null group by Col1 order by sum(Col4) desc label Col1 \'Menu\', sum(Col2) \'Qty\', sum(Col4) \'Omzet\'",0)');
  m.getRange('C:C').setNumberFormat(RP);
  const d = sh('Dashboard'); d.clear(); d.getCharts().forEach(function (c) { d.removeChart(c); });
  const k = [['Omzet hari ini','=SUMIFS(Harian!H2:H,Harian!A2:A,TEXT(TODAY(),"yyyy-mm-dd"))'],['Total omzet','=SUM(Harian!H2:H)'],['Total cup','=SUM(Harian!G2:G)'],['Total QRIS','=SUM(Harian!I2:I)'],['Total pengeluaran','=SUM(Harian!K2:K)'],['Total tunai bersih','=SUM(Harian!L2:L)']];
  d.getRange(1,1,6,2).setValues(k.map(function (x) { return [x[0], '']; })); d.getRange(1,2,6,1).setFormulas(k.map(function (x) { return [x[1]]; }));
  d.getRange('A1:A6').setFontWeight('bold'); d.getRange('B1:B6').setNumberFormat(RP); d.getRange('B3').setNumberFormat('0');
  d.insertChart(d.newChart().setChartType(Charts.ChartType.COLUMN).addRange(h.getRange('A1:A500')).addRange(h.getRange('H1:H500')).setNumHeaders(1).setOption('title','Omzet per hari').setPosition(8,1,0,0).build());
  d.insertChart(d.newChart().setChartType(Charts.ChartType.BAR).addRange(m.getRange('A1:A30')).addRange(m.getRange('C1:C30')).setOption('title','Omzet per menu').setPosition(8,6,0,0).build());
}
function out(o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); }
function doPost(e) {
  let b; try { b = JSON.parse(e.postData.contents); } catch (x) { return out({ok:false,err:'format'}); }
  const role = b.key === KUNCI_OWNER ? 'owner' : (b.key === KUNCI ? 'emp' : null);
  if (!role) return out({ok:false,err:'kunci salah'});
  const ownerOnly = {pullAll:1, listReq:1, approve:1, reject:1, shop:1};
  if (ownerOnly[b.action] && role !== 'owner') return out({ok:false,err:'khusus owner'});
  const lock = LockService.getScriptLock(); lock.waitLock(25000);
  try { return out(handle(b, role, Date.now())); } catch (x) { return out({ok:false,err:String(x)}); } finally { lock.releaseLock(); }
}
function handle(b, role, now) {
  if (b.day !== undefined && !DAY.test(String(b.day))) return {ok:false, err:'tanggal tidak valid'};
  if (b.rid !== undefined && !SAFE.test(String(b.rid))) return {ok:false, err:'id tidak valid'};
  if (b.tid !== undefined && !SAFE.test(String(b.tid))) return {ok:false, err:'id tidak valid'};
  if (b.action === 'pullRange' && !(DAY.test(String(b.from)) && DAY.test(String(b.to)))) return {ok:false, err:'rentang tidak valid'};
  switch (b.action) {
    case 'ping': return {ok:true, role:role};
    case 'push': return {ok:true, E:push(b, role, now)};
    case 'pullAll': { const r = buildDays(allRows()); return {ok:true, days:r.days, cfg:r.cfg, reqs:openReqs(now)}; }
    case 'pullEmp': return {ok:true, cfg:loadKey('cfg'), E:loadKey('E:' + b.day), reqs:qAll().filter(function (q) { return q.day === b.day; }).map(function (q) { return reqPublic(q, now); })};
    case 'pullRange': return {ok:true, days:rangeE(b.from, b.to)};
    case 'listReq': return {ok:true, reqs:openReqs(now)};
    case 'shop': return shopWrite(b.rows);
    case 'request': return reqNew(b, now);
    case 'cancel': { const q = qGet(b.rid); if (q && q.st === 'pending') { q.st = 'cancelled'; qPut(q); logReq(q); } return {ok:true}; }
    case 'approve': { const q = qGet(b.rid); if (!q) return {ok:false,err:'tidak ditemukan'}; const r = reqApprove(q, now, newCode()); if (!r.ok) return r; qPut(q); logReq(q); return {ok:true, q:q}; }
    case 'reject': { const q = qGet(b.rid); if (!q) return {ok:false,err:'tidak ditemukan'}; q.st = 'rejected'; qPut(q); logReq(q); return {ok:true, q:q}; }
    case 'redeem': { const q = qGet(b.rid); if (!q) return {ok:false,err:'tidak ditemukan'}; const r = reqRedeem(q, b.code, now); qPut(q); if (r.ok) logReq(q); return r.ok ? {ok:true, act:q.act, tid:q.tid} : r; }
  }
  return {ok:true};
}
function rep(n, date, rows, w) {
  const s = sh(n), v = s.getLastRow() > 1 ? s.getRange(2,1,s.getLastRow()-1,1).getValues() : [];
  for (let i = v.length - 1; i >= 0; i--) if (String(v[i][0]) === date) s.deleteRow(i + 2);
  if (rows.length) s.getRange(s.getLastRow()+1, 1, rows.length, w).setValues(rows.map(function (x) { return [date].concat(x); }));
}
function allRows() { const s = sh('_data',['Kunci','JSON']); return s.getLastRow() > 1 ? s.getRange(2,1,s.getLastRow()-1,2).getValues() : []; }
// Cache 6 jam: polling HP karyawan (tiap 15 detik) jadi cepat dan hemat kuota Apps Script.
function cget(k) { try { const v = CacheService.getScriptCache().get(k); return v ? JSON.parse(v) : undefined; } catch (x) { return undefined; } }
function cput(k, j) { try { const s = JSON.stringify(j); if (s.length < 90000) CacheService.getScriptCache().put(k, s, 21600); else CacheService.getScriptCache().remove(k); } catch (x) {} }
function loadKey(k) {
  const c = cget(k); if (c !== undefined) return c;
  const r = allRows().filter(function (x) { return String(x[0]) === k; });
  try { const j = r.length ? JSON.parse(r[0][1]) : null; if (j !== null) cput(k, j); return j; } catch (x) { return null; }
}
function store(k, j) {
  const s = sh('_data',['Kunci','JSON']), v = s.getLastRow() > 1 ? s.getRange(2,1,s.getLastRow()-1,1).getValues() : []; let r = 0;
  for (let i = 0; i < v.length; i++) if (String(v[i][0]) === k) { r = i + 2; break; }
  if (!r) r = s.getLastRow() + 1; s.getRange(r,1,1,2).setValues([[k, JSON.stringify(j)]]); cput(k, j);
}
function fixHarian() {
  const h = sh('Harian'), n = h.getLastRow() - 1; if (n < 1) return;
  h.getRange(2,1,n,6).sort(1);
  const f = []; for (let r = 2; r < n + 2; r++) f.push(['=SUMIFS(Transaksi!D:D,Transaksi!A:A,A'+r+')','=SUMIFS(Transaksi!F:F,Transaksi!A:A,A'+r+')','=SUMIFS(Transaksi!F:F,Transaksi!A:A,A'+r+',Transaksi!G:G,"QRIS")','=H'+r+'-I'+r,'=C'+r+'+D'+r+'+E'+r,'=H'+r+'-I'+r+'-K'+r]);
  h.getRange(2,7,n,6).setFormulas(f);
}
function push(b, role, now) {
  const d = b.day, owner = role === 'owner', old = loadKey('E:' + d);
  const allow = owner ? null : reqAllow(qAll(), d, now);
  const E = mergeE(old, b.E, allow, loadKey('cfg')), applied = E.applied; delete E.applied;
  store('E:' + d, E);
  applied.forEach(function (a) { const q = qGet(a.rid); if (q) { q.st = 'done'; qPut(q); logReq(q); } });
  rep('Transaksi', d, E.tx.map(function (t) { return [t.t, t.n, t.q, t.p, t.q * t.p, String(t.pay).toUpperCase(), t.id]; }), 8);
  const px = [];
  (E.exp || []).forEach(function (e) { if (e.n || e.a) px.push(['Operasional', e.n, Number(e.a) || 0]); });
  (E.kx || []).forEach(function (e) { if (e.n || e.a) px.push(['Karyawan', e.n, Number(e.a) || 0]); });
  rep('Pengeluaran', d, px, 4);
  if (owner && b.O) {   // hanya aplikasi Owner (hitungan lengkap)
    store('O:' + d, b.O); if (b.cfg) store('cfg', b.cfg);
    rep('Stok', d, b.stok || [], 6); rep('Stok Lainnya', d, b.stok2 || [], 7); rep('Harian', d, [b.hari], 6); fixHarian();
  } else {
    const h = sh('Harian'), v = h.getLastRow() > 1 ? h.getRange(2,1,h.getLastRow()-1,1).getValues() : [];
    if (!v.some(function (x) { return String(x[0]) === d; })) { rep('Harian', d, [['',0,0,0,'']], 6); fixHarian(); }
  }
  return E;
}
function rangeE(from, to) {
  const days = {};
  allRows().forEach(function (r) {
    const m = String(r[0]).match(/^E:(\d{4}-\d{2}-\d{2})$/);
    if (m && m[1] >= from && m[1] <= to) { try { const j = JSON.parse(r[1]); days[m[1]] = {tx: j.tx || [], exp: j.exp || [], kx: j.kx || []}; } catch (x) {} }
  });
  return days;
}
// ---- permintaan persetujuan: penyimpanan ----
function qSheet() { return sh('_req', ['rid', 'json']); }
function qAll() {
  const c = cget('QALL'); if (c !== undefined) return c;
  const s = qSheet(), v = s.getLastRow() > 1 ? s.getRange(2,1,s.getLastRow()-1,2).getValues() : [], L = [];
  v.forEach(function (r) { try { L.push(JSON.parse(r[1])); } catch (x) {} });
  cput('QALL', L); return L;
}
function qGet(rid) { const r = qAll().filter(function (q) { return q.rid === String(rid); }); return r.length ? r[0] : null; }
function qPut(q) {
  const s = qSheet(), v = s.getLastRow() > 1 ? s.getRange(2,1,s.getLastRow()-1,1).getValues() : []; let r = 0;
  for (let i = 0; i < v.length; i++) if (String(v[i][0]) === q.rid) { r = i + 2; break; }
  if (!r) r = s.getLastRow() + 1;
  s.getRange(r,1,1,2).setValues([[q.rid, JSON.stringify(q)]]);
  try { CacheService.getScriptCache().remove('QALL'); } catch (x) {}
}
function openReqs(now) {
  const t = now - 24 * 3600 * 1000;
  return qAll().filter(function (q) { return reqLive(q, now) || q.at > t; }).map(function (q) { return Object.assign({}, q, {st: reqEff(q, now)}); });
}
function reqNew(b, now) {
  const E = loadKey('E:' + b.day);
  if (!E || !(E.tx || []).some(function (t) { return t.id === b.tid; })) return {ok:false, err:'transaksi tidak ditemukan'};
  const act = qAll().filter(function (q) { return q.tid === b.tid && q.act === (b.act === 'del' ? 'del' : 'edit') && reqLive(q, now); });
  if (act.length) return {ok:true, q:reqPublic(act[0], now)};
  const open = qAll().filter(function (q) { return q.day === b.day && reqLive(q, now) && q.st === 'pending'; });
  if (open.length >= 30) return {ok:false, err:'terlalu banyak permintaan'};
  const q = reqCreate(b, now); qPut(q); logReq(q); return {ok:true, q:reqPublic(q, now)};
}
function logReq(q) {
  try { sh('Persetujuan',['Waktu','ID','Tanggal','Aksi','Transaksi','Alasan','Status']).appendRow([new Date(), q.rid, q.day, q.act === 'del' ? 'HAPUS' : 'EDIT', q.desc, q.reason, q.st]); } catch (x) {}
}

// Daftar belanja otomatis (dikirim aplikasi Owner): ditulis ulang setiap kali berubah.
function shopWrite(rows) {
  const s = sh('Daftar Belanja', ['Barang','Sisa','Satuan','Pakai/hari','Habis dalam (hari)','Perkiraan habis','Saran beli','Status']);
  const last = s.getLastRow(); if (last > 1) s.getRange(2, 1, last - 1, 8).clearContent();
  const R = (Array.isArray(rows) ? rows : []).slice(0, 200).map(function (r) {
    return [String(r[0] || '').slice(0, 60), r[1], String(r[2] || '').slice(0, 20), r[3], r[4], String(r[5] || ''), r[6], String(r[7] || '').slice(0, 20)];
  });
  if (R.length) s.getRange(2, 1, R.length, 8).setValues(R);
  return {ok: true, n: R.length};
}
