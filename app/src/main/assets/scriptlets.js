(function(){
 if(window.__aegis) return; window.__aegis=1;
 var host=location.hostname;
 var isYT=/(^|\.)youtube(-nocookie)?\.com$/.test(host);
 var SLD={co:1,com:1,net:1,org:1,gov:1,edu:1,ac:1,or:1,go:1,web:1,sch:1,my:1,biz:1,ne:1,mil:1};
 function site(h){var l=String(h).toLowerCase().split('.'),n=l.length;if(n<=2)return l.join('.');
  return (l[n-1].length===2&&SLD[l[n-2]])?l.slice(-3).join('.'):l.slice(-2).join('.');}

 /* util: injeksi CSS lewat constructable stylesheet (lolos CSP style-src), fallback ke <style> */
 var sheets={};
 var OFFKEY='aegis_cosmetic_off';
 try{ var t0=+localStorage.getItem(OFFKEY); if(t0&&Date.now()-t0<6048e5) window.__aegisOff=1; }catch(e){}  /* 7 hari */
 window.__aegisCss=function(id,css){
  if(window.__aegisOff) return;
  try{
   var sh=sheets[id]||(sheets[id]=new CSSStyleSheet()); sh.replaceSync(css);
   var cur=document.adoptedStyleSheets||[]; if(cur.indexOf(sh)<0) document.adoptedStyleSheets=cur.concat([sh]);
   return;
  }catch(e){}
  try{
   var s=document.getElementById('aegis-'+id); if(!s){s=document.createElement('style');s.id='aegis-'+id;}
   s.textContent=css; var p=document.head||document.documentElement;
   if(p){ if(!s.parentNode)p.appendChild(s); } else setTimeout(function(){window.__aegisCss(id,css);},0);
  }catch(e){}
 };

 /* scriptlet: json-prune (membuang data iklan dari respons pemutar & feed YouTube) */
 var KEYS=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];
 var AD_ITEMS=['adSlotRenderer','promotedSparklesWebRenderer','promotedSparklesTextSearchRenderer','promotedVideoRenderer',
  'compactPromotedVideoRenderer','bannerPromoRenderer','statementBannerRenderer','displayAdRenderer','inFeedAdLayoutRenderer',
  'adBreakServiceRenderer','searchPyvRenderer','videoMastheadAdV3Renderer','primetimePromoRenderer','brandVideoSingletonRenderer',
  'brandVideoShelfRenderer','actionCompanionAdRenderer','playerLegacyDesktopWatchAdsRenderer','compactPromotedItemRenderer','feedAdLayoutRenderer'];
 var MARK=/adSlotRenderer|adPlacements|playerAds|promotedSparkles|promotedVideoRenderer|compactPromotedVideoRenderer|bannerPromoRenderer|displayAdRenderer|inFeedAdLayoutRenderer|videoMastheadAdV3Renderer|searchPyvRenderer|statementBannerRenderer|feedAdLayoutRenderer/;
 function prune(o){
  if(!o||typeof o!=='object') return;
  for(var i=0;i<KEYS.length;i++){ if(KEYS[i] in o){ try{delete o[KEYS[i]];}catch(e){} } }
  if(o.playerResponse) prune(o.playerResponse);
 }
 function hasAdKey(x){ for(var i=0;i<AD_ITEMS.length;i++){ if(AD_ITEMS[i] in x) return true; } return false; }
 function isAd(x){
  if(!x||typeof x!=='object') return false;
  if(hasAdKey(x)) return true;
  var a=x.richItemRenderer; if(a&&a.content&&typeof a.content==='object'&&hasAdKey(a.content)) return true;
  var b=x.richSectionRenderer; if(b&&b.content&&typeof b.content==='object'&&hasAdKey(b.content)) return true;
  return false;
 }
 function deep(o,d){ /* buang slot iklan di mana pun dalam respons (feed, hasil cari, next, player) */
  if(!o||typeof o!=='object'||d>40) return;
  if(Array.isArray(o)){
   for(var i=o.length-1;i>=0;i--){ if(isAd(o[i])) o.splice(i,1); else deep(o[i],d+1); }
   return;
  }
  for(var j=0;j<KEYS.length;j++){ if(KEYS[j] in o){ try{delete o[KEYS[j]];}catch(e){} } }
  for(var k in o){ if(Object.prototype.hasOwnProperty.call(o,k)){ var v=o[k]; if(v&&typeof v==='object') deep(v,d+1); } }
 }
 var _parse=JSON.parse;
 JSON.parse=function(t){
  var r=_parse.apply(this,arguments);
  try{ if(isYT&&typeof t==='string'&&MARK.test(t)) deep(r,0); else prune(r); }catch(e){}
  return r;
 };
 if(window.Response&&Response.prototype.json){
  var _json=Response.prototype.json;
  Response.prototype.json=function(){
   var u=this.url||'';
   return _json.apply(this,arguments).then(function(r){
    try{ if(isYT&&u.indexOf('/youtubei/')>=0) deep(r,0); else prune(r); }catch(e){}
    return r;
   });
  };
 }
 if(isYT&&window.Response&&Response.prototype.text){
  var _text=Response.prototype.text;
  Response.prototype.text=function(){
   var u=this.url||'';
   return _text.apply(this,arguments).then(function(t){
    try{ if(u.indexOf('/youtubei/')>=0&&MARK.test(t)){ var o=_parse(t); deep(o,0); return JSON.stringify(o); } }catch(e){}
    return t;
   });
  };
 }
 if(isYT&&window.XMLHttpRequest){
  var _xo=XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open=function(m,u){ try{this.__aegisU=String(u);}catch(e){} return _xo.apply(this,arguments); };
  ['response','responseText'].forEach(function(p){
   var d=Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype,p); if(!d||!d.get) return;
   Object.defineProperty(XMLHttpRequest.prototype,p,{configurable:true,enumerable:d.enumerable,get:function(){
    var v=d.get.call(this);
    try{
     if(this.__aegisU&&this.__aegisU.indexOf('/youtubei/')>=0&&this.readyState===4){
      if(typeof v==='string'&&MARK.test(v)){ var o=_parse(v); deep(o,0); return JSON.stringify(o); }
      if(v&&typeof v==='object'&&p==='response') deep(v,0);
     }
    }catch(e){}
    return v;
   }});
  });
 }
 try{
  var _v;
  Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,
   get:function(){return _v;}, set:function(v){ try{ deep(v,0); }catch(e){} _v=v; }});
  var _d;
  Object.defineProperty(window,'ytInitialData',{configurable:true,
   get:function(){return _d;}, set:function(v){ try{ deep(v,0); }catch(e){} _d=v; }});
 }catch(e){}

 /* scriptlet: blokir pop-up. Tanpa interaksi pengguna = blok; lintas-situs hanya untuk login/pembayaran sah. */
 var _open=window.open;
 var POPUP_OK=/(accounts\.google\.com|appleid\.apple\.com|facebook\.com|login\.microsoftonline\.com|github\.com|twitter\.com|(^|\.)x\.com|paypal\.com|midtrans\.com|xendit\.co|doku\.com)/i;
 window.open=function(u){
  try{
   var act=navigator.userActivation?navigator.userActivation.isActive:true;
   if(!act) return null;
   if(u){
    var h=new URL(String(u),location.href).hostname;
    if(site(h)!==site(host)&&!POPUP_OK.test(h)) return null;
   }
  }catch(e){}
  return _open.apply(this,arguments);
 };

 /* scriptlet: stub iklan agar skrip situs tidak error / memicu "anti-adblock" saat pustaka iklan diblokir */
 try{ window.adsbygoogle={loaded:true,push:function(){}}; }catch(e){}
 try{ window.canRunAds=true; window.canShowAds=true; }catch(e){}
 try{
  if(!window.googletag){
   var chain=new Proxy(function(){},{
    get:function(t,p){ if(p===Symbol.toPrimitive) return function(){return '';}; return chain; },
    apply:function(){ return chain; }
   });
   var fn=function(){ return chain; };
   window.googletag={apiReady:true,pubadsReady:true,
    cmd:{push:function(f){ try{ if(typeof f==='function') f(); }catch(e){} return 1; }},
    pubads:fn,defineSlot:fn,defineOutOfPageSlot:fn,companionAds:fn,content:fn,sizeMapping:fn,
    enableServices:function(){},display:function(){},destroySlots:function(){return true;},
    setAdIframeTitle:function(){}};
  }
 }catch(e){}

 /* privasi: buang parameter pelacak (utm_*, fbclid, gclid, ...) dari URL halaman dan dari tautan yang diklik */
 var TRK=/^(utm_[a-z0-9_]+|fbclid|gclid|dclid|gbraid|wbraid|msclkid|yclid|igshid|mc_eid|mc_cid|_hsenc|_hsmi|vero_id|ttclid|twclid)$/i;
 function cleanUrl(u){
  try{
   var x=new URL(u,location.href); if(x.protocol!=='http:'&&x.protocol!=='https:') return null;
   var ks=[]; x.searchParams.forEach(function(v,k){ if(TRK.test(k)) ks.push(k); });
   if(!ks.length) return null;
   for(var i=0;i<ks.length;i++) x.searchParams.delete(ks[i]);
   return x.href;
  }catch(e){ return null; }
 }
 if(!isYT){
  try{ var cu=cleanUrl(location.href); if(cu&&new URL(cu).origin===location.origin) history.replaceState(history.state,'',cu); }catch(e){}
  document.addEventListener('click',function(ev){
   try{
    var a=ev.target&&ev.target.closest&&ev.target.closest('a[href]'); if(!a) return;
    var c=cleanUrl(a.href); if(c) a.href=c;
   }catch(e){}
  },true);
 }

 /* banner persetujuan cookie: disembunyikan; kunci scroll yang ditinggalkannya dibuka */
 var CMP=['#onetrust-consent-sdk','#onetrust-banner-sdk','.onetrust-pc-dark-filter','#CybotCookiebotDialog','#CybotCookiebotDialogBodyUnderlay',
  '.cc-window','.cc-banner','#cookie-law-info-bar','#cookiebanner','.cookie-banner','.cookie-notice','#cookie-notice','.cookie-consent',
  '#cookieConsent','#truste-consent-track','.truste_overlay','#didomi-host','.fc-consent-root','.qc-cmp2-container','#usercentrics-root',
  '[id^="sp_message_container_"]','.sp_veil','#cmpbox','#cmpwrapper','.osano-cm-window','#hs-eu-cookie-confirmation'];
 try{ window.__aegisCss('cmp',CMP.map(function(x){return x+'{display:none!important}';}).join('\n')); }catch(e){}
 function unlockScroll(){
  try{
   var seen=false; for(var i=0;i<CMP.length&&!seen;i++){ if(document.querySelector(CMP[i])) seen=true; }
   if(!seen) return;
   [document.documentElement,document.body].forEach(function(n){
    if(n&&getComputedStyle(n).overflow==='hidden') n.style.setProperty('overflow','auto','important');
   });
  }catch(e){}
 }

 /* penjaga kerusakan: bila filter kosmetik membuang >65% teks halaman, filter dimatikan untuk situs ini (7 hari) */
 var guarded=0;
 function setCss(on){
  try{
   var mine=[]; for(var k in sheets) mine.push(sheets[k]);
   var rest=(document.adoptedStyleSheets||[]).filter(function(x){ return mine.indexOf(x)<0; });
   document.adoptedStyleSheets=on?rest.concat(mine):rest;
  }catch(e){}
  try{ var st=document.querySelectorAll('style[id^="aegis-"]'); for(var i=0;i<st.length;i++) st[i].disabled=!on; }catch(e){}
 }
 function visLen(){ return document.body?(document.body.innerText||'').length:0; }
 function guard(){
  try{
   if(isYT||window.__aegisOff||window.top!==window||!document.body||guarded>=2) return;
   var on=visLen(); if(on<0) return;
   setCss(false); var off=visLen(); setCss(true);
   if(off>=800&&on<off*0.35){
    guarded=9; window.__aegisOff=1; setCss(false);
    try{ localStorage.setItem(OFFKEY,String(Date.now())); }catch(e){}
    try{ console.warn('[Aegis] filter kosmetik dimatikan di situs ini: terlalu banyak isi halaman tersembunyi'); }catch(e){}
   } else guarded++;
  }catch(e){}
 }

 /* heuristik DOM: iframe/elemen iklan yang lolos dari daftar, dan tembok "matikan adblock" */
 var SIZES={'300x250':1,'728x90':1,'320x50':1,'300x600':1,'160x600':1,'970x250':1,'336x280':1,'320x100':1,'468x60':1,'970x90':1,'120x600':1,'300x50':1};
 var ADHOST=/(doubleclick|googlesyndication|googleadservices|adservice\.|adnxs|taboola|outbrain|criteo|amazon-adsystem|adsterra|propeller|popads|exoclick|rubiconproject|pubmatic|openx\.net|smartadserver|teads\.tv|mgid\.com|revcontent|adsrvr|casalemedia|adform\.net|juicyads|clickadu|hilltopads)/i;
 var ADID=/(google_ads|aswift|ad_iframe|adframe|(^|[-_ ])ads?([-_ ]|\d|$))/i;
 function hide(el){ try{ el.style.setProperty('display','none','important'); el.setAttribute('data-aegis','1'); }catch(e){} }
 function scanAds(){
  var list=document.querySelectorAll('iframe:not([data-aegis]),ins.adsbygoogle:not([data-aegis])');
  for(var i=0;i<list.length;i++){
   var f=list[i], src=f.src||f.getAttribute('data-src')||'';
   var idn=(f.id||'')+' '+(f.name||'');
   var bad=ADHOST.test(src)||ADID.test(idn);
   if(!bad&&SIZES[(f.offsetWidth|0)+'x'+(f.offsetHeight|0)]&&(!src||src==='about:blank')){
    var p=f.parentElement; if(p&&ADID.test((p.id||'')+' '+(typeof p.className==='string'?p.className:''))) bad=true;
   }
   if(bad) hide(f);
  }
 }
 var AB=/(ad[\s\-]?block|pemblokir iklan|blokir iklan|anti[\s\-]?adblock|matikan (ad ?block|pemblokir)|nonaktifkan (ad ?block|pemblokir)|disable (your )?ad[\s\-]?block|turn off (your )?ad[\s\-]?block|izinkan iklan|allow ads)/i;
 function scanWalls(){
  if(!document.body||!document.elementsFromPoint) return;
  var vw=window.innerWidth,vh=window.innerHeight,hidden=false;
  var st=document.elementsFromPoint(vw/2,vh/2);
  for(var i=0;i<st.length;i++){
   var el=st[i]; if(el===document.body||el===document.documentElement||el.getAttribute('data-aegis')) continue;
   var cs=getComputedStyle(el);
   if(cs.position!=='fixed'&&cs.position!=='absolute'&&cs.position!=='sticky') continue;
   var r=el.getBoundingClientRect(); if(r.width<vw*0.45||r.height<vh*0.25) continue;
   var tx=el.textContent||''; if(tx.length>1500) continue;
   if(AB.test(tx)){ hide(el); hidden=true; }
  }
  if(hidden){
   [document.documentElement,document.body].forEach(function(n){
    try{ n.style.setProperty('overflow','auto','important'); n.style.setProperty('position','static','important'); }catch(e){}
   });
   var v=document.querySelector('video'); if(v&&v.paused){ try{ v.play(); }catch(e){} }
  }
 }
 var tm=0,tries=0;
 function sched(){ if(tm) return; tm=setTimeout(function(){ tm=0; try{ scanAds(); if(tries++<40){ scanWalls(); unlockScroll(); } }catch(e){} },600); }
 function start(){
  sched(); setTimeout(guard,1500); setTimeout(guard,5000);
  try{
   new MutationObserver(sched).observe(document.documentElement,{childList:true,subtree:true});
  }catch(e){}
 }
 if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',start); else start();

 /* YouTube: lewati iklan video yang lolos + singkirkan dialog "ad blocker dilarang".
    Memakai <video> milik pemutar utama (bukan pratinjau feed) dan memulihkan mute/kecepatan milik pengguna. */
 if(isYT){
  var inAd=false,prevMuted=false,prevRate=1;
  function yt(){
   var p=document.querySelector('.html5-video-player');
   var v=(p&&p.querySelector('video'))||document.querySelector('video');
   var ad=!!(p&&(p.classList.contains('ad-showing')||p.classList.contains('ad-interrupting')));
   if(ad&&v){
    if(!inAd){ inAd=true; prevMuted=v.muted; prevRate=v.playbackRate||1; }
    v.muted=true;
    try{ if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.1) v.currentTime=v.duration; }catch(e){}
    try{ v.playbackRate=16; }catch(e){}
    if(v.paused){ try{ v.play(); }catch(e){} }
   } else if(inAd&&v){
    inAd=false; v.muted=prevMuted; try{ v.playbackRate=prevRate; }catch(e){}
   }
   var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button,.ytm-skip-ad-button,.videoAdUiSkipButton');
   if(b) try{ b.click(); }catch(e){}
   var o=document.querySelector('.ytp-ad-overlay-close-button'); if(o) try{ o.click(); }catch(e){}
   var e=document.querySelector('ytd-enforcement-message-view-model');
   if(e){
    var dlg=e.closest('tp-yt-paper-dialog')||e; try{ dlg.remove(); }catch(x){}
    var bd=document.querySelector('tp-yt-iron-overlay-backdrop'); if(bd) try{ bd.remove(); }catch(x){}
    if(v&&v.paused) try{ v.play(); }catch(x){}
   }
  }
  setInterval(yt,250);
  document.addEventListener('yt-navigate-finish',yt,true);
 }
})();
