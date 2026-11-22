// node tools/textvec.js : uji mesin teks (judol/scam) terhadap contoh positif & negatif
const T=require('../extension/src/textsmart.js');
const pos=['SLOT GACOR hari ini maxwin! daftar di wa.me/628123456789','𝗦𝗟𝗢𝗧 𝗚𝗔𝗖𝗢𝗥 𝗠𝗔𝗫𝗪𝗜𝗡 bonus new member 100% https://bit.ly/abc',
 'g4c0r b4nget s1t3 sl0t 0nline depo 10rb wd cepat t.me/slotku','Ⓢⓛⓞⓣ ɢᴀᴄᴏʀ link alternatif terpercaya 😍🔥💰🎰','TOGEL hk sgp prediksi jitu bocoran angka main 0812-3456-7890','s l o t  g a c o r  m a x w i n','sІоt gасоr mахwіn daftar sekarang'];
const neg=['Video yang bagus, terima kasih sudah berbagi tutorial ini','Motor saya gacor banget suaranya setelah ganti knalpot','I lost a lot at the casino in Vegas last year lol','Judi Dench is a great actress','Bagus bang, lanjutkan kontennya','Slotting the pieces together took me an hour','Please add a bonus chapter to the book','Deposit bank saya sudah masuk, terima kasih min'];
let bad=0;
pos.forEach(t=>{const r=T.judolText(t); if(r.score<60){bad++;console.log('FN',r.score,t,r.reasons)}});
neg.forEach(t=>{const r=T.judolText(t); if(r.score>=60){bad++;console.log('FP',r.score,t,r.reasons)}});
const sc=['Selamat! Anda menang hadiah 50 juta, kirim OTP untuk klaim','Paket Anda tertahan, alamat tidak lengkap, klik link ini','Undangan pernikahan.apk silakan pasang'];
sc.forEach(t=>{const r=T.scamText(t); if(r.score<35){bad++;console.log('SCAM FN',r.score,t)}});
['Halo, paket saya sudah sampai, terima kasih kurirnya'].forEach(t=>{const r=T.scamText(t); if(r.score>=35){bad++;console.log('SCAM FP',r.score,t)}});
const cb=require('../extension/src/smart.js').init(JSON.parse(require('fs').readFileSync(__dirname+'/../shared/smart-rules.json')));
['10 FAKTA MENGEJUTKAN!!! Ternyata artis ini bikin geger...','You won\'t believe what happens next','Cara memasang router WiFi di rumah dengan benar'].forEach((t,i)=>{const r=cb.clickbait(t);if((i<2)!==(r.score>=45)){bad++;console.log('CB',r.score,t)}});
console.log(bad?bad+' gagal':'semua tes teks lulus');process.exit(bad?1:0);
