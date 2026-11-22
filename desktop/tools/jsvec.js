// Jalankan: node tools/jsvec.js  -> cetak hasil JS untuk shared/vectors.json dan bandingkan dengan harapan.
const fs=require('fs'),path=require('path');
const S=require('../extension/src/smart.js').init(JSON.parse(fs.readFileSync(path.join(__dirname,'../shared/smart-rules.json'))));
const V=JSON.parse(fs.readFileSync(path.join(__dirname,'../shared/vectors.json')));
let bad=0;
for(const v of V.hosts){const r=S.analyzeHost(v.host);const okc=r.cls===v.cls,oks=(v.minScore===undefined||r.score>=v.minScore)&&(v.maxScore===undefined||r.score<=v.maxScore);
 if(!okc||!oks){bad++;console.log('GAGAL',v.host,'dapat',r.cls,r.score,'harapan',v.cls,v.minScore,v.maxScore,r.reasons.join(';'));}}
for(const v of (V.out||[])){ } 
if(process.argv[2]==='dump') console.log(JSON.stringify(V.hosts.map(v=>{const r=S.analyzeHost(v.host);return [v.host,r.cls,r.score,r.judol]})));
console.log(bad?`${bad} gagal`:`semua ${V.hosts.length} vektor lulus (JS)`);process.exit(bad?1:0);
