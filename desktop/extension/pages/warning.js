(function () {
  var api = typeof browser !== 'undefined' ? browser : chrome, q = new URLSearchParams(location.search), $ = function (s) { return document.getElementById(s); };
  var u = q.get('u') || '', cls = q.get('cls') || 'scam', why = q.get('w') || '';
  var T = { judol: ['Situs judi online dicegat', 'Aegis memblokir situs ini karena terindikasi judi online (judol).'], scam: ['Situs berbahaya dicegat', 'Situs ini terindikasi penipuan, phishing, atau penyebar malware. Jangan memasukkan sandi, OTP, atau data pribadi.'] };
  var t = T[cls] || T.scam; $('t').textContent = t[0]; $('d').textContent = t[1]; $('u').textContent = u || '(alamat tidak diketahui)'; $('why').textContent = why ? 'Alasan: ' + why : '';
  $('back').onclick = function () { if (history.length > 1) history.back(); else location.href = 'about:blank'; };
  $('go').onclick = function () {
    var h = ''; try { h = new URL(u).hostname; } catch (e) { }
    if (!h) return;
    api.runtime.sendMessage({ type: 'allow', host: h, minutes: 60 }).then(function () { location.replace(u); });
  };
})();
