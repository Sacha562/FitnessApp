const fs=require('fs');
// load prototype without its demo output
const src=fs.readFileSync('sel.js','utf8').split('// ---------- LEGS ----------')[0];
const m={exports:{}};new Function('module','require',src+'\nmodule.exports={select,evaluate,cos,exTime,prefMult};')(m,require);
const {select,cos,prefMult}=m.exports;
const all=fs.readFileSync('sel.js','utf8');
const legs=eval(all.match(/const legs = (\[[\s\S]*?\n\]);/)[1]);
const push=eval(all.match(/const push = (\[[\s\S]*?\n\]);/)[1]);
const ctx={target:{quads:6,glutes:5,hamstrings:6,adductors:4,abductors:2,calves:3,lower_back:0},goals:{quads:'improve',glutes:'improve',hamstrings:'improve',adductors:'improve',abductors:'maintain',calves:'maintain',lower_back:'maintain'},regionsWanted:['rectus_femoris','hams_biarticular_long','gastrocnemius'],history:[],budget:65,sPref:3,unavailable:[],injury:{axialMax:2}};
const L=legs.map(e=>e.id==='hip_adduction_machine'?{...e,rating:1}:e);
const r=select(L,ctx);
console.log('ADDUCTORS improve, adduction machine disliked, low-back flag; unlocked:',r.unlocked.map(e=>e.id+'('+e.unlockReason+')').join(','));
r.best.S.forEach(({e,s})=>console.log('  ',e.id,s));
console.log('  cov',JSON.stringify(r.ev.cov));
// ORDER
const order=S=>[...S].sort((a,b)=>{const k=x=>[x.e.pinned?0:1, x.e.iso?1:0, -(x.e.axial||0), -Object.values(x.e.stim).reduce((p,c)=>p+c,0), x.e.id];const ka=k(a),kb=k(b);for(let i=0;i<ka.length;i++){if(ka[i]<kb[i])return -1;if(ka[i]>kb[i])return 1;}return 0;});
const base={target:{quads:6,glutes:5,hamstrings:6,adductors:2,abductors:2,calves:3,lower_back:0},goals:{quads:'improve',glutes:'improve',hamstrings:'improve',adductors:'maintain',abductors:'maintain',calves:'maintain',lower_back:'maintain'},regionsWanted:['rectus_femoris','hams_biarticular_long','gastrocnemius'],history:[],budget:65,sPref:3,unavailable:[]};
const rb=select(legs,base);
console.log('ORDERED legs:',order(rb.best.S).map(x=>x.e.id+' x'+x.s).join(' -> '));
// SUBSTITUTION for leg_press_45 occupied
const tgt=legs.find(e=>e.id==='leg_press_45');
const inSession=new Set(rb.best.S.map(x=>x.e.id));
const fam={squat:'squat',squat_machine:'squat',lunge:'squat',hinge:'hinge',hip_ext:'hinge'};
const subs=legs.filter(c=>c.id!==tgt.id&&!inSession.has(c.id)&&c.rating!==1).map(c=>{const sim=cos(tgt.stim,c.stim);const pm=c.pattern===tgt.pattern?1:(fam[c.pattern]&&fam[c.pattern]===fam[tgt.pattern]?0.5:0);const pn=(Math.log(prefMult(c))-Math.log(0.6))/(Math.log(1.495)-Math.log(0.6));const fast=c.setup<=60?1:c.setup<=120?0.5:0;return {id:c.id,sim:+sim.toFixed(3),score:+(0.6*sim+0.15*pm+0.15*pn+0.10*fast).toFixed(3)}}).filter(x=>x.sim>=0.6).sort((a,b)=>b.score-a.score||a.id.localeCompare(b.id));
console.log('SUBS for leg_press_45:',JSON.stringify(subs.slice(0,4)));
const tp=push.find(e=>e.id==='machine_chest_press');
const subsP=push.filter(c=>c.id!==tp.id).map(c=>{const sim=cos(tp.stim,c.stim);const pm=c.pattern===tp.pattern?1:((['horiz_push','incline_push','dip'].includes(c.pattern))?0.5:0);const pn=(Math.log(prefMult(c))-Math.log(0.6))/(Math.log(1.495)-Math.log(0.6));const fast=c.setup<=60?1:c.setup<=120?0.5:0;return {id:c.id,sim:+sim.toFixed(3),score:+(0.6*sim+0.15*pm+0.15*pn+0.10*fast).toFixed(3)}}).filter(x=>x.sim>=0.6).sort((a,b)=>b.score-a.score||a.id.localeCompare(b.id));
console.log('SUBS for machine_chest_press:',JSON.stringify(subsP.slice(0,5)));
