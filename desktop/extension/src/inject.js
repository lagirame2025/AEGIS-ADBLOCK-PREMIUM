/* Aegis - skrip dunia-halaman (MAIN world, document_start). Membersihkan data iklan dari respons YouTube,
   memblokir pop-up/pop-under, dan menyediakan stub pustaka iklan agar skrip situs tidak error. */
(function () {
  if (window.__aegisMain) return; window.__aegisMain = 1;
  var host = location.hostname;
  var isYT = /(^|\.)youtube(-nocookie)?\.com$/.test(host);
  var SLD = { co: 1, com: 1, net: 1, org: 1, gov: 1, edu: 1, ac: 1, or: 1, go: 1, web: 1, sch: 1, my: 1, biz: 1, ne: 1, mil: 1 };
  function site(h) { var l = String(h).toLowerCase().split('.'), n = l.length; if (n <= 2) return l.join('.'); return (l[n - 1].length === 2 && SLD[l[n - 2]]) ? l.slice(-3).join('.') : l.slice(-2).join('.'); }

  /* ---------- YouTube: buang data iklan dari respons ---------- */
  var KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  var AD_ITEMS = ['adSlotRenderer', 'promotedSparklesWebRenderer', 'promotedSparklesTextSearchRenderer', 'promotedVideoRenderer', 'compactPromotedVideoRenderer',
    'bannerPromoRenderer', 'statementBannerRenderer', 'displayAdRenderer', 'inFeedAdLayoutRenderer', 'adBreakServiceRenderer', 'searchPyvRenderer',
    'videoMastheadAdV3Renderer', 'primetimePromoRenderer', 'brandVideoSingletonRenderer', 'brandVideoShelfRenderer', 'actionCompanionAdRenderer',
    'playerLegacyDesktopWatchAdsRenderer', 'compactPromotedItemRenderer', 'feedAdLayoutRenderer', 'adInfoDialogEndpoint'];
  var MARK = /adSlotRenderer|adPlacements|playerAds|promotedSparkles|promotedVideoRenderer|compactPromotedVideoRenderer|bannerPromoRenderer|displayAdRenderer|inFeedAdLayoutRenderer|videoMastheadAdV3Renderer|searchPyvRenderer|statementBannerRenderer|feedAdLayoutRenderer/;
  function hasAdKey(x) { for (var i = 0; i < AD_ITEMS.length; i++) if (AD_ITEMS[i] in x) return true; return false; }
  function isAd(x) {
    if (!x || typeof x !== 'object') return false;
    if (hasAdKey(x)) return true;
    var a = x.richItemRenderer; if (a && a.content && typeof a.content === 'object' && hasAdKey(a.content)) return true;
    var b = x.richSectionRenderer; if (b && b.content && typeof b.content === 'object' && hasAdKey(b.content)) return true;
    return false;
  }
  function deep(o, d) {
    if (!o || typeof o !== 'object' || d > 40) return;
    if (Array.isArray(o)) { for (var i = o.length - 1; i >= 0; i--) { if (isAd(o[i])) o.splice(i, 1); else deep(o[i], d + 1); } return; }
    for (var j = 0; j < KEYS.length; j++) if (KEYS[j] in o) { try { delete o[KEYS[j]]; } catch (e) { } }
    for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) { var v = o[k]; if (v && typeof v === 'object') deep(v, d + 1); }
  }
  if (isYT) {
    var _parse = JSON.parse;
    JSON.parse = function (t) {
      var r = _parse.apply(this, arguments);
      try { if (typeof t === 'string' && MARK.test(t)) deep(r, 0); } catch (e) { }
      return r;
    };
    if (window.Response && Response.prototype.json) {
      var _json = Response.prototype.json;
      Response.prototype.json = function () {
        var u = this.url || '';
        return _json.apply(this, arguments).then(function (r) { try { if (u.indexOf('/youtubei/') >= 0) deep(r, 0); } catch (e) { } return r; });
      };
    }
    if (window.Response && Response.prototype.text) {
      var _text = Response.prototype.text;
      Response.prototype.text = function () {
        var u = this.url || '';
        return _text.apply(this, arguments).then(function (t) {
          try { if (u.indexOf('/youtubei/') >= 0 && MARK.test(t)) { var o = _parse(t); deep(o, 0); return JSON.stringify(o); } } catch (e) { }
          return t;
        });
      };
    }
    if (window.XMLHttpRequest) {
      var _xo = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function (m, u) { try { this.__aegisU = String(u); } catch (e) { } return _xo.apply(this, arguments); };
      ['response', 'responseText'].forEach(function (p) {
        var d = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, p); if (!d || !d.get) return;
        Object.defineProperty(XMLHttpRequest.prototype, p, { configurable: true, enumerable: d.enumerable, get: function () {
          var v = d.get.call(this);
          try {
            if (this.__aegisU && this.__aegisU.indexOf('/youtubei/') >= 0 && this.readyState === 4) {
              if (typeof v === 'string' && MARK.test(v)) { var o = _parse(v); deep(o, 0); return JSON.stringify(o); }
              if (v && typeof v === 'object' && p === 'response') deep(v, 0);
            }
          } catch (e) { }
          return v;
        } });
      });
    }
    ['ytInitialPlayerResponse', 'ytInitialData'].forEach(function (name) {
      try {
        var val; Object.defineProperty(window, name, { configurable: true, get: function () { return val; }, set: function (v) { try { deep(v, 0); } catch (e) { } val = v; } });
      } catch (e) { }
    });
  }

  /* ---------- Pop-up / pop-under ---------- */
  var lastClick = { t: 0, a: null };
  document.addEventListener('click', function (ev) {
    try { lastClick = { t: Date.now(), a: ev.target && ev.target.closest ? ev.target.closest('a[href]') : null }; } catch (e) { }
  }, true);
  var POPUP_OK = /(accounts\.google\.com|appleid\.apple\.com|facebook\.com|login\.microsoftonline\.com|login\.live\.com|github\.com|twitter\.com|(^|\.)x\.com|paypal\.com|midtrans\.com|xendit\.co|doku\.com|stripe\.com|line\.me|linkedin\.com|whatsapp\.com|wa\.me|t\.me)/i;
  var _open = window.open;
  window.open = function (u) {
    try {
      var act = navigator.userActivation ? navigator.userActivation.isActive : true;
      if (!act) { window.__aegisBlocked = (window.__aegisBlocked || 0) + 1; return null; }
      if (u) {
        var h = new URL(String(u), location.href).hostname;
        if (site(h) !== site(host) && !POPUP_OK.test(h)) {
          // lintas-situs hanya boleh bila pengguna benar-benar mengeklik tautan yang menuju ke sana (bukan klik pada latar/overlay)
          var a = lastClick.a, fresh = Date.now() - lastClick.t < 1500, ok = false;
          try { ok = !!(a && fresh && site(new URL(a.href, location.href).hostname) === site(h)); } catch (e) { }
          if (!ok) { window.__aegisBlocked = (window.__aegisBlocked || 0) + 1; return null; }
        }
      }
    } catch (e) { }
    return _open.apply(this, arguments);
  };

  /* ---------- Stub pustaka iklan ---------- */
  try { if (!window.adsbygoogle) window.adsbygoogle = { loaded: true, push: function () { } }; } catch (e) { }
  try { window.canRunAds = true; window.canShowAds = true; } catch (e) { }
  try {
    if (!window.googletag) {
      var chain = new Proxy(function () { }, { get: function (t, p) { if (p === Symbol.toPrimitive) return function () { return ''; }; return chain; }, apply: function () { return chain; } });
      var fn = function () { return chain; };
      window.googletag = { apiReady: true, pubadsReady: true, cmd: { push: function (f) { try { if (typeof f === 'function') f(); } catch (e) { } return 1; } },
        pubads: fn, defineSlot: fn, defineOutOfPageSlot: fn, companionAds: fn, content: fn, sizeMapping: fn, enableServices: function () { }, display: function () { },
        destroySlots: function () { return true; }, setAdIframeTitle: function () { } };
    }
  } catch (e) { }
})();
