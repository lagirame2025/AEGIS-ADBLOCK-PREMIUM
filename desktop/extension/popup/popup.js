(function () {
  var api = typeof browser !== 'undefined' ? browser : chrome, $ = function (s) { return document.querySelector(s); };
  var DEF = { enabled: true, social: true, comments: true, links: true, clickbait: 'mark', phishing: true, overlays: true, youtube: true, walls: true, smartAds: true, guard: true, autoUpdate: true };
  var settings = {}, tab = null, host = '';
  function save() { return api.storage.local.set({ settings: settings }); }
  function fmt(t) { return t ? new Date(t).toLocaleString('id-ID') : 'belum pernah'; }
  function paintLists(m) {
    var el = $('#lists');
    if (!m || !m.counts) { el.textContent = 'Belum diunduh (memakai aturan bawaan). Klik "Perbarui sekarang".'; return; }
    var c = m.counts; el.textContent = 'Iklan ' + (c.ads || 0).toLocaleString('id-ID') + ' · Judol ' + (c.judol || 0).toLocaleString('id-ID') + ' · Scam ' + (c.scam || 0).toLocaleString('id-ID') + ' domain. Diperbarui: ' + fmt(m.t);
  }
  function load() {
    Promise.all([api.storage.local.get(['settings', 'siteOff']), api.tabs.query({ active: true, currentWindow: true })]).then(function (r) {
      settings = Object.assign({}, DEF, r[0].settings || {}); tab = r[1][0] || {};
      var off = r[0].siteOff || [];
      $('#enabled').checked = settings.enabled; $('#clickbait').value = settings.clickbait;
      document.querySelectorAll('[data-k]').forEach(function (i) { i.checked = !!settings[i.dataset.k]; });
      try { host = new URL(tab.url).hostname; } catch (e) { host = ''; }
      $('#host').textContent = host || 'halaman ini';
      $('#siteOff').checked = off.indexOf(host) >= 0; $('#siteOff').disabled = !host;
      api.runtime.sendMessage({ type: 'status', tabId: tab.id, url: tab.url || '' }).then(function (s) {
        if (!s || s.error) return;
        $('#nHid').textContent = s.hidden || 0; $('#nBlk').textContent = s.blocked || 0;
        $('#nTot').textContent = ((s.stats || {}).blk_scam || 0) + ((s.stats || {}).blk_judol || 0);
        paintLists(s.meta);
        if (s.risk && (s.risk.cls === 'scam' || s.risk.cls === 'judol' || s.risk.cls === 'warn')) {
          var r2 = $('#risk'); r2.classList.remove('hidden'); if (s.risk.cls === 'warn') r2.classList.add('warn');
          $('#riskT').textContent = s.risk.cls === 'judol' ? '⚠ Situs judi online' : s.risk.cls === 'scam' ? '⛔ Situs berisiko tinggi (scam/phishing)' : '⚠ Situs mencurigakan';
          $('#riskW').textContent = s.risk.why || (s.reasons || []).join('; ');
        }
      });
    });
  }
  $('#enabled').onchange = function () { settings.enabled = this.checked; save(); };
  $('#clickbait').onchange = function () { settings.clickbait = this.value; save(); };
  document.querySelectorAll('[data-k]').forEach(function (i) { i.onchange = function () { settings[i.dataset.k] = i.checked; save(); }; });
  $('#siteOff').onchange = function () { api.runtime.sendMessage({ type: 'siteOff', host: host, off: this.checked }).then(function () { if (tab && tab.id) api.tabs.reload(tab.id); }); };
  $('#upd').onclick = function () {
    var b = this; b.disabled = true; b.textContent = 'Mengunduh daftar…';
    api.runtime.sendMessage({ type: 'update' }).then(function (m) { paintLists(m); b.textContent = (m && m.report) ? 'Selesai' : 'Gagal'; setTimeout(function () { b.disabled = false; b.textContent = 'Perbarui sekarang'; }, 2000); },
      function () { b.textContent = 'Gagal'; b.disabled = false; });
  };
  load();
})();
