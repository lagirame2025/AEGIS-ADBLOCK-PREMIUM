/* Aegis Text Engine (lokal): mendeteksi spam judi online (judol) di komentar/pesan, termasuk yang disamarkan dengan
   huruf unicode "fancy" (𝗦𝗟𝗢𝗧), leetspeak (g4c0r), spasi/titik disisipkan, dan homoglif Sirilik. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.AegisText = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  var CYR = { 'а': 'a', 'в': 'b', 'е': 'e', 'з': '3', 'к': 'k', 'м': 'm', 'н': 'h', 'о': 'o', 'р': 'p', 'с': 'c', 'т': 't', 'у': 'y', 'х': 'x', 'і': 'i', 'ѕ': 's', 'ј': 'j', 'ԁ': 'd', 'ɡ': 'g', 'ɑ': 'a', 'ο': 'o', 'ρ': 'p', 'ν': 'v', 'ι': 'i', 'α': 'a' };
  var LEET = { '0': 'o', '3': 'e', '4': 'a', '5': 's', '7': 't', '@': 'a', '$': 's', '!': 'i', '1': 'i' };

  /** Lipat teks "fancy" ke ASCII huruf kecil. Mengembalikan {text, fancy} (fancy = jumlah huruf yang disamarkan). */
  function fold(s) {
    s = String(s || '');
    var fancy = 0, out = '';
    for (var i = 0; i < s.length;) {
      var cp = s.codePointAt(i), ch = String.fromCodePoint(cp); i += ch.length;
      if (cp > 0x7f) {
        var n = ch.normalize('NFKD').replace(/[̀-ͯ​-‏⁠﻿]/g, '');
        if (n !== ch && /[A-Za-z0-9]/.test(n)) fancy++;
        else if (CYR[ch.toLowerCase()]) { fancy++; n = CYR[ch.toLowerCase()]; }
        out += n;
      } else out += ch;
    }
    return { text: out.toLowerCase(), fancy: fancy };
  }
  function deleet(t) { return t.replace(/[03457@$!1]/g, function (c) { return LEET[c]; }); }

  // [kata, bobot]. Strong = sangat khas judol; sisanya pendukung.
  var STRONG = [['maxwin', 45], ['togel', 45], ['sbobet', 50], ['slotgacor', 50], ['gacor', 30], ['rtpslot', 45], ['judionline', 45], ['judislot', 45], ['judibola', 45],
    ['bandarq', 45], ['dominoqq', 45], ['pokerqq', 45], ['bocoran', 20], ['scatterhitam', 45], ['gatesofolympus', 40], ['mahjongways', 40], ['sweetbonanza', 35],
    ['starlight', 10], ['zeusslot', 40], ['slotonline', 35], ['slotdemo', 40], ['situsslot', 45], ['linkalternatif', 35], ['bonusnewmember', 45], ['newmember', 20],
    ['depositpulsa', 35], ['depopulsa', 35], ['tanpapotongan', 30], ['wdcepat', 35], ['wdlancar', 35], ['sultan', 8], ['cuanmaksimal', 30], ['autojp', 40],
    ['jackpot', 18], ['jepe', 15], ['scatter', 18], ['slot', 15], ['judi', 20], ['casino', 20], ['kasino', 20], ['poker', 8], ['taruhan', 20], ['betting', 12]];
  var SUPPORT = [['deposit', 10], ['depo', 8], ['bonus', 8], ['daftar', 6], ['rtp', 14], ['winrate', 12], ['menang', 6], ['cuan', 8], ['wd', 5], ['member', 5],
    ['gratis', 4], ['freespin', 20], ['spin', 6], ['hoki', 6], ['pasaran', 12], ['prediksi', 10], ['angka', 3], ['bet', 6], ['viral', 2], ['terpercaya', 6], ['resmi', 4]];
  var CONTACT = /(wa\.me|whatsapp\.com\/send|t\.me\/|bit\.ly|tinyurl|linktr\.ee|s\.id\/|cutt\.ly|rebrand\.ly|shorturl|lnk\.bio|https?:\/\/\S+\.(xyz|top|click|vip|bet|site|online|win|icu|cc|live)\b)/i;
  var PHONE = /(\+?62|0)8\d{2}[\s.\-]?\d{3,4}[\s.\-]?\d{3,5}/;

  /** judolText(teks) -> {score 0..100, reasons[]} */
  function judolText(raw) {
    raw = String(raw || '');
    if (raw.length < 6) return { score: 0, reasons: [] };
    var f = fold(raw), low = f.text;
    var compact = low.replace(/[^a-z0-9@$!]/g, ''), letters = deleet(compact).replace(/[^a-z]/g, '');
    var plain = low.replace(/[^a-z]/g, '');
    var s = 0, why = [], seen = {};
    function scan(list, hay, label) {
      for (var i = 0; i < list.length; i++) {
        var w = list[i][0];
        if (seen[w] || hay.indexOf(w) < 0) continue;
        // kata pendek (<=4) harus berdiri sendiri sebagai token agar "slotting"/"bet"-ter tidak terhitung
        if (w.length <= 4 && !new RegExp('(^|[^a-z])' + w + '([^a-z]|$)').test(deleet(low))) continue;
        seen[w] = 1; s += list[i][1]; why.push(label + ' "' + w + '"');
      }
    }
    scan(STRONG, letters, 'kata judol');
    scan(SUPPORT, letters, 'kata pendukung');
    if (plain !== letters && letters.indexOf('gacor') >= 0 && plain.indexOf('gacor') < 0) { s += 8; why.push('disamarkan leetspeak'); }
    var hit = s > 0;
    if (hit && f.fancy >= 4 && f.fancy / Math.max(1, raw.length) > 0.3) { s += 25; why.push('huruf unicode disamarkan'); }
    if (hit && CONTACT.test(raw)) { s += 18; why.push('tautan/kontak ajakan'); }
    if (hit && PHONE.test(raw)) { s += 12; why.push('nomor telepon'); }
    if (hit && (raw.match(/\p{Extended_Pictographic}/gu) || []).length >= 4) { s += 8; why.push('emoji berlebihan'); }
    if (hit && /(.)\1{4,}/.test(low)) s += 4;
    return { score: Math.min(100, s), reasons: why };
  }

  /** Teks ajakan scam/penipuan umum (hadiah, OTP, transfer, APK). */
  var SCAM = [[/(selamat|congrat)\w*.{0,40}(menang|hadiah|won|prize)/i, 40, 'klaim hadiah'], [/(kirim|berikan|share).{0,25}\b(otp|kode verifikasi|pin)\b/i, 50, 'meminta OTP/PIN'],
    [/(akun|rekening).{0,30}(diblokir|dibekukan|ditangguhkan|suspended)/i, 35, 'ancaman akun diblokir'], [/(transfer|top ?up|biaya admin).{0,40}(klaim|cairkan|hadiah|pinjaman)/i, 40, 'biaya di muka'],
    [/\b(pinjaman|dana).{0,20}(cair|tanpa jaminan|tanpa bi checking)/i, 25, 'pinjaman cepat mencurigakan'], [/(invest|investasi).{0,40}(untung|profit|cuan).{0,20}(pasti|jamin|100%|harian)/i, 45, 'investasi menjanjikan untung pasti'],
    [/\.apk\b/i, 25, 'ajakan pasang APK'], [/(verify|verifikasi).{0,25}(account|akun).{0,40}(24|jam|hours|segera|immediately)/i, 35, 'desakan verifikasi'],
    [/(paket|kurir|jne|j&t|sicepat|pos indonesia).{0,50}(gagal|tertahan|alamat).{0,40}(klik|link|tautan)/i, 40, 'modus paket'], [/\b(undangan|surat) (pernikahan|nikah).{0,10}\.apk/i, 60, 'modus undangan APK']];
  function scamText(raw) {
    raw = String(raw || ''); var s = 0, why = [];
    SCAM.forEach(function (r) { if (r[0].test(raw)) { s += r[1]; why.push(r[2]); } });
    return { score: Math.min(100, s), reasons: why };
  }
  return { fold: fold, judolText: judolText, scamText: scamText };
});
