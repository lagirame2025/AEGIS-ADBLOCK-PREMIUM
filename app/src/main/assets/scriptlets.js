(function(){
 if(window.__aegis) return; window.__aegis=1;
 /* scriptlet: json-prune (membuang data iklan dari respons pemutar YouTube) */
 var KEYS=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams'];
 function prune(o){
  if(!o||typeof o!=='object') return;
  for(var i=0;i<KEYS.length;i++){ if(KEYS[i] in o){ try{delete o[KEYS[i]];}catch(e){} } }
  if(o.playerResponse) prune(o.playerResponse);
 }
 var _parse=JSON.parse;
 JSON.parse=function(){ var r=_parse.apply(this,arguments); try{prune(r);}catch(e){} return r; };
 if(window.Response&&Response.prototype.json){
  var _json=Response.prototype.json;
  Response.prototype.json=function(){ return _json.apply(this,arguments).then(function(r){ try{prune(r);}catch(e){} return r; }); };
 }
 try{
  var _v;
  Object.defineProperty(window,'ytInitialPlayerResponse',{configurable:true,
   get:function(){return _v;}, set:function(v){ try{prune(v);}catch(e){} _v=v; }});
 }catch(e){}
 /* scriptlet: blokir pop-up tanpa interaksi pengguna */
 var _open=window.open;
 window.open=function(){
  if(navigator.userActivation&&navigator.userActivation.isActive) return _open.apply(this,arguments);
  return null;
 };
 /* scriptlet: adsbygoogle no-op */
 try{ window.adsbygoogle={loaded:true,push:function(){}}; }catch(e){}
 /* cadangan: lewati iklan video YouTube yang lolos */
 var muted=false;
 function yt(){
  var p=document.querySelector('.html5-video-player');
  var ad=p&&p.classList.contains('ad-showing');
  var v=document.querySelector('video');
  if(ad&&v){ muted=true; v.muted=true; try{ if(isFinite(v.duration)&&v.duration>0) v.currentTime=v.duration; }catch(e){} v.playbackRate=16; }
  else if(muted&&v){ muted=false; v.muted=false; v.playbackRate=1; }
  var b=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytm-skip-ad-button,.videoAdUiSkipButton');
  if(b) b.click();
  var o=document.querySelector('.ytp-ad-overlay-close-button'); if(o) o.click();
 }
 setInterval(yt,250);
})();
