// node tools/adtest.js : uji pengklasifikasi iklan (fitur -> keputusan)
const A=require('../extension/src/adscore.js');
const cases=[
 ['slot iklan 300x250 iframe lintas situs + nama kelas',{token:1,iab:1,crossFrame:1},true],
 ['iframe jaringan iklan ukuran standar',{adHost:1,crossFrame:1,iab:1},true],
 ['kotak "Iklan" berisi gambar menuju situs luar',{token:1,label:1,outboundImg:1,empty:1},true],
 ['bar sticky bawah bernama ad + label',{token:1,sticky:1,label:1},true],
 ['widget data-ad berisi iframe lintas-situs',{dataAttr:1,crossFrame:1},true],
 ['artikel panjang dengan kelas "ad" kebetulan',{token:1,mainContent:1,longText:1},false],
 ['formulir login dengan id ad',{token:1,form:1},false],
 ['pemutar video bernama ad-player',{token:1,video:1,crossFrame:1},false],
 ['navigasi berlabel Sponsored (menu)',{label:1,navLike:1},false],
 ['hanya ukuran 300x250 (foto biasa)',{iab:1},false],
 ['hanya iframe lintas-situs (embed peta)',{crossFrame:1,empty:1},false],
 ['hanya nama kelas ad (satu sinyal)',{token:1},false],
];
let bad=0;for(const [n,f,exp] of cases){const r=A.score(f);if(r.hide!==exp){bad++;console.log('GAGAL',n,r.z.toFixed(2),r.why.join(','));}}
console.log(bad?bad+' gagal':'semua '+cases.length+' kasus pengklasifikasi iklan lulus');process.exit(bad?1:0);
