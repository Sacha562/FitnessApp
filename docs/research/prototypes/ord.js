const fs=require('fs');
const src=fs.readFileSync('sel.js','utf8').split('// ---------- LEGS ----------')[0];
const m={exports:{}};new Function('module','require',src+'\nmodule.exports={select,evaluate,cos,exTime,prefMult};')(m,require);
const {select,exTime}=m.exports;const all=fs.readFileSync('sel.js','utf8');
const legs=eval(all.match(/const legs = (\[[\s\S]*?\n\]);/)[1]);const push=eval(all.match(/const push = (\[[\s\S]*?\n\]);/)[1]);
const W={improve:1,maintain:0.6};
function order(S,ctx){const k=x=>[x.e.pinned?0:1,x.e.iso?1:0,-Object.entries(x.e.stim).reduce((p,[g,c])=>p+(ctx.goals[g]==='improve'?c*x.s:0),0),-(x.e.axial||0),x.e.id];
return [...S].sort((a,b)=>{const ka=k(a),kb=k(b);for(let i=0;i<ka.length;i++){if(ka[i]<kb[i])return -1;if(ka[i]>kb[i])return 1;}return 0;});}
const lctx={target:{quads:6,glutes:5,hamstrings:6,adductors:2,abductors:2,calves:3,lower_back:0},goals:{quads:'improve',glutes:'improve',hamstrings:'improve',adductors:'maintain',abductors:'maintain',calves:'maintain',lower_back:'maintain'},regionsWanted:['rectus_femoris','hams_biarticular_long','gastrocnemius'],history:[],budget:65,sPref:3,unavailable:[]};
const pctx={target:{chest:6,side_delts:4,triceps:4,front_delts:2},goals:{chest:'improve',side_delts:'improve',triceps:'improve',front_delts:'maintain'},regionsWanted:['chest_clavicular','triceps_long'],history:[],budget:60,sPref:3,unavailable:[]};
for(const [n,c,x] of [['LEGS',legs,lctx],['PUSH',push,pctx]]){const r=select(c,x);console.log(n,'ordered:',order(r.best.S,x).map(y=>y.e.id+' x'+y.s+' ('+exTime(y.e,y.s).toFixed(1)+'m)').join(' -> '),'| total',r.ev.t.toFixed(1),'min');}
