// Prototype of SEL-1 (beam search exercise selection) to sanity-check constants.
'use strict';
const PREF_MULT = { banned: 0, r1: 0.60, r2: 0.85, r3: 1.00, r4: 1.15, r5: 1.30 };
const FAV_MULT = 1.15;
const W_GOAL = { improve: 1.0, maintain: 0.6, skip: 0 };
const C = {
  overCredit: 0.25, overTol: 1.25, lamOver: 0.5, lamSkip: 3.0, lamPref: 0.6,
  bStretch: 0.05, bSfr: 0.04, regionBonus: 0.3, consistency: 0.5, stallPen: 0.5,
  timePenPerMin: 0.5, axialCap: 4, lamAxial: 0.3, beam: 12, maxEx: 8, topK: 25,
  sMin: 2, lamTime: 0.05, sMaxCompound: 5, sMaxIso: 4, transition: 60, tempo: 3, dupCos: 0.95,
};
function prefMult(e) { if (e.banned) return 0; let m = PREF_MULT['r' + (e.rating || 3)]; if (e.fav) m *= FAV_MULT; return m; }
function cos(a, b) { let d = 0, na = 0, nb = 0; const ks = new Set([...Object.keys(a), ...Object.keys(b)]); for (const k of ks) { const x = a[k] || 0, y = b[k] || 0; d += x * y; na += x * x; nb += y * y; } return na && nb ? d / Math.sqrt(na * nb) : 0; }
function exTime(e, s) { const reps = e.reps || 10; const work = reps * C.tempo + 5; const w = e.uni ? 2 * work + 10 : work; return (e.setup + s * (w + e.rest) - e.rest + C.transition) / 60; }
function allocSets(e, R, sPref) {
  let need = 0;
  for (const [g, c] of Object.entries(e.stim)) if (c >= 0.75 && R[g] > 0) need = Math.max(need, R[g] / c);
  if (need === 0) for (const [g, c] of Object.entries(e.stim)) if (R[g] > 0) need = Math.max(need, R[g] / c);
  const sMax = e.iso ? C.sMaxIso : C.sMaxCompound;
  const s0 = Math.min(sMax, Math.max(C.sMin, sPref));
  return Math.min(sMax, Math.max(C.sMin, Math.min(s0, Math.ceil(need - 0.25))));
}
function tuneSets(S, ctx) {
  S = S.map(x => ({ ...x }));
  for (let it = 0; it < 20; it++) {
    let improved = false;
    for (let i = 0; i < S.length; i++) {
      const e = S[i].e; const sMax = e.iso ? C.sMaxIso : C.sMaxCompound;
      const lo = Math.max(C.sMin, ctx.sPref - 1), hi = Math.min(sMax, ctx.sPref + 1);
      for (const d of [1, -1]) {
        const ns = S[i].s + d; if (ns < lo || ns > hi) continue;
        const base = evaluate(S, ctx).J; const T = S.map((x, j) => j === i ? { e: x.e, s: ns } : x);
        if (evaluate(T, ctx).J > base + 0.05) { S = T; improved = true; }
      }
    }
    if (!improved) break;
  }
  return S;
}
function evaluate(S, ctx) {
  const cov = {}; let t = 0, P = 0, Q = 0, K = 0, ax = 0;
  for (const { e, s } of S) {
    for (const [g, c] of Object.entries(e.stim)) cov[g] = (cov[g] || 0) + c * s;
    t += exTime(e, s);
    if (ctx.history.includes(e.id)) K += C.consistency; ax += (e.axial || 0);
  }
  // share of useful credit per group
  const share = {}; let O = 0;
  for (const [g, x] of Object.entries(cov)) {
    const T = ctx.target[g] || 0; const goal = ctx.goals[g] || 'maintain';
    if (goal === 'skip') { O += C.lamSkip * x; share[g] = 0; continue; }
    const useful = Math.min(x, T) + C.overCredit * Math.max(0, Math.min(x, T * C.overTol) - T);
    share[g] = x > 0 ? useful / x : 0;
    O += Math.max(0, x - Math.max(C.overTol * T, T + 1.0));
  }
  let B = 0;
  for (const { e, s } of S) {
    let be = 0;
    for (const [g, c] of Object.entries(e.stim)) be += (W_GOAL[ctx.goals[g] || 'maintain'] || 0) * c * s * share[g];
    const qual = C.bStretch * e.stretch + C.bSfr * (e.sfr - 3);
    B += be * prefMult(e) * (1 + qual);
    P += be * (prefMult(e) - 1); Q += be * qual;
  }
  // region variety bonus
  let RB = 0;
  for (const r of ctx.regionsWanted) if (S.some(({ e }) => (e.regions || []).includes(r))) RB += C.regionBonus;
  const Tm = Math.max(0, t - ctx.budget) * C.timePenPerMin;
  const Fax = C.lamAxial * Math.max(0, ax - C.axialCap);
  const J = B - C.lamOver * O + RB + K - Tm - Fax - C.lamTime * t;
  return { J, B, O, P, Q, RB, K, t, cov };
}
function eligible(cands, ctx) {
  const ok = cands.filter(e => !e.banned && !ctx.unavailable.includes(e.id) && !(ctx.injury && (e.axial || 0) >= ctx.injury.axialMax));
  // necessity gating for disliked (rating 1)
  const liked = ok.filter(e => (e.rating || 3) > 1);
  const disliked = ok.filter(e => (e.rating || 3) === 1);
  const unlocked = [];
  for (const [g, T] of Object.entries(ctx.target)) {
    const goal = ctx.goals[g]; if (goal === 'skip' || T < 1) continue;
    const thr = goal === 'improve' ? 0.75 : 0.5;
    if (!liked.some(e => (e.stim[g] || 0) >= thr)) {
      const best = disliked.filter(e => (e.stim[g] || 0) >= thr).sort((a, b) => (b.stim[g] - a.stim[g]) || (b.sfr - a.sfr) || a.id.localeCompare(b.id))[0];
      if (best && !unlocked.includes(best)) unlocked.push(best), best.unlockReason = g;
    }
  }
  return { pool: [...liked, ...unlocked], unlocked };
}
function select(cands, ctx) {
  const { pool, unlocked } = eligible(cands, ctx);
  // pre-rank singletons
  const single = pool.map(e => ({ e, J: evaluate([{ e, s: allocSets(e, ctx.target, ctx.sPref) }], ctx).J }))
    .sort((a, b) => b.J - a.J || a.e.id.localeCompare(b.e.id)).slice(0, C.topK).map(x => x.e);
  let beam = [{ S: [], J: evaluate([], ctx).J }]; let best = beam[0];
  for (let depth = 0; depth < C.maxEx; depth++) {
    const next = [];
    for (const st of beam) {
      const covNow = evaluate(st.S, ctx).cov;
      const R = {}; for (const [g, T] of Object.entries(ctx.target)) R[g] = Math.max(0, T - (covNow[g] || 0));
      for (const e of single) {
        if (st.S.some(x => x.e.id === e.id)) continue;
        if (st.S.some(x => cos(x.e.stim, e.stim) >= C.dupCos && x.e.pattern === e.pattern)) continue;
        if (st.S.filter(x => x.e.pattern === e.pattern).length >= 2) continue;
        const s = allocSets(e, R, ctx.sPref);
        const S2 = [...st.S, { e, s }].sort((a, b) => a.e.id.localeCompare(b.e.id));
        const key = S2.map(x => x.e.id).join('|');
        if (next.some(n => n.key === key)) continue;
        const ev = evaluate(S2, ctx);
        if (ev.t > ctx.budget * 1.10) continue;
        next.push({ S: S2, J: ev.J, key });
      }
    }
    if (!next.length) break;
    next.sort((a, b) => b.J - a.J || a.key.localeCompare(b.key));
    beam = next.slice(0, C.beam);
    if (beam[0].J > best.J) best = beam[0];
  }
  const tuned = tuneSets(best.S, ctx); return { best: { S: tuned }, ev: evaluate(tuned, ctx), unlocked };
}
// ---------- LEGS ----------
const legs = [
  { id: 'leg_press_45', stim: { quads: 1, glutes: 0.5, adductors: 0.5 }, stretch: 1, sfr: 5, axial: 0, setup: 90, rest: 150, pattern: 'squat_machine', rating: 5, fav: true, reps: 10 },
  { id: 'hack_squat', stim: { quads: 1, glutes: 0.5, adductors: 0.5 }, stretch: 2, sfr: 4, axial: 1, setup: 90, rest: 150, pattern: 'squat_machine', rating: 3, reps: 8 },
  { id: 'back_squat', stim: { quads: 1, glutes: 0.75, adductors: 0.75, lower_back: 0.25 }, stretch: 2, sfr: 3, axial: 3, setup: 150, rest: 180, pattern: 'squat', rating: 3, reps: 6 },
  { id: 'walking_lunge_db', stim: { quads: 1, glutes: 0.75, adductors: 0.5 }, stretch: 2, sfr: 3, axial: 1, setup: 60, rest: 120, pattern: 'lunge', rating: 1, uni: true, reps: 10 },
  { id: 'bulgarian_split_squat_db', stim: { quads: 1, glutes: 0.75, adductors: 0.5 }, stretch: 2, sfr: 3, axial: 1, setup: 90, rest: 120, pattern: 'lunge', rating: 3, uni: true, reps: 10 },
  { id: 'leg_extension', stim: { quads: 1 }, regions: ['rectus_femoris'], stretch: 1, sfr: 5, axial: 0, setup: 45, rest: 90, pattern: 'knee_ext', rating: 4, iso: true, reps: 12 },
  { id: 'seated_leg_curl', stim: { hamstrings: 1 }, regions: ['hams_biarticular_long'], stretch: 2, sfr: 5, axial: 0, setup: 60, rest: 90, pattern: 'knee_flex', rating: 4, iso: true, reps: 10 },
  { id: 'lying_leg_curl', stim: { hamstrings: 1 }, stretch: 1, sfr: 4, axial: 0, setup: 60, rest: 90, pattern: 'knee_flex', rating: 3, iso: true, reps: 10 },
  { id: 'rdl_barbell', stim: { hamstrings: 1, glutes: 0.5, adductors: 0.5, lower_back: 0.5 }, regions: ['hams_biarticular_long'], stretch: 2, sfr: 3, axial: 2, setup: 120, rest: 150, pattern: 'hinge', rating: 3, reps: 8 },
  { id: 'hip_thrust_smith', stim: { glutes: 1, quads: 0.25, hamstrings: 0.25 }, stretch: 0, sfr: 3, axial: 1, setup: 120, rest: 120, pattern: 'hip_ext', rating: 3, reps: 10 },
  { id: 'hip_abduction_machine', stim: { abductors: 1, glutes: 0.25 }, stretch: 1, sfr: 5, axial: 0, setup: 30, rest: 60, pattern: 'hip_abd', rating: 3, iso: true, reps: 15 },
  { id: 'hip_adduction_machine', stim: { adductors: 1 }, stretch: 1, sfr: 5, axial: 0, setup: 30, rest: 60, pattern: 'hip_add', rating: 3, iso: true, reps: 12 },
  { id: 'standing_calf_smith', stim: { calves: 1 }, regions: ['gastrocnemius'], stretch: 2, sfr: 4, axial: 1, setup: 60, rest: 60, pattern: 'calf', rating: 3, iso: true, reps: 12 },
  { id: 'leg_press_calf', stim: { calves: 1 }, regions: ['gastrocnemius'], stretch: 2, sfr: 4, axial: 0, setup: 45, rest: 60, pattern: 'calf', rating: 3, iso: true, reps: 12 },
  { id: 'back_extension_45', stim: { lower_back: 0.5, glutes: 0.5, hamstrings: 0.5 }, stretch: 1, sfr: 4, axial: 0, setup: 30, rest: 90, pattern: 'hinge', rating: 3, reps: 12 },
];
const legCtx = {
  target: { quads: 6, glutes: 5, hamstrings: 6, adductors: 2, abductors: 2, calves: 3, lower_back: 0 },
  goals: { quads: 'improve', glutes: 'improve', hamstrings: 'improve', adductors: 'maintain', abductors: 'maintain', calves: 'maintain', lower_back: 'maintain' },
  regionsWanted: ['rectus_femoris', 'hams_biarticular_long', 'gastrocnemius'], history: [], budget: 65, sPref: 3, unavailable: [],
};
function show(name, r) {
  console.log('== ' + name + ' J=' + r.ev.J.toFixed(2) + ' time=' + r.ev.t.toFixed(1) + 'min unlocked=' + r.unlocked.map(e => e.id + '(' + e.unlockReason + ')').join(','));
  for (const { e, s } of r.best.S) console.log('  ', e.id.padEnd(26), s + ' sets', exTime(e, s).toFixed(1) + 'min');
  console.log('   sets', r.best.S.reduce((a,x)=>a+x.s,0), 'cov', JSON.stringify(Object.fromEntries(Object.entries(r.ev.cov).map(([k, v]) => [k, +v.toFixed(2)]))));
}
show('LEGS base', select(legs, legCtx));
show('LEGS hip thrust occupied + low-back flag', select(legs, { ...legCtx, unavailable: ['hip_thrust_smith'], injury: { axialMax: 2 } }));
const legs2 = legs.map(e => e.id === 'bulgarian_split_squat_db' ? { ...e, rating: 1 } : e);
show('LEGS + BSS disliked + hip thrust occupied + low-back flag', select(legs2, { ...legCtx, unavailable: ['hip_thrust_smith'], injury: { axialMax: 2 } }));
// ---------- PUSH ----------
const push = [
  { id: 'bench_barbell', stim: { chest: 1, front_delts: 0.5, triceps: 0.5 }, stretch: 1, sfr: 3, axial: 1, setup: 120, rest: 180, pattern: 'horiz_push', rating: 3, reps: 6 },
  { id: 'db_bench', stim: { chest: 1, front_delts: 0.5, triceps: 0.5 }, stretch: 2, sfr: 4, axial: 0, setup: 60, rest: 150, pattern: 'horiz_push', rating: 3, reps: 8 },
  { id: 'incline_db_press', stim: { chest: 1, front_delts: 0.5, triceps: 0.5 }, regions: ['chest_clavicular'], stretch: 2, sfr: 4, axial: 0, setup: 60, rest: 150, pattern: 'incline_push', rating: 4, reps: 8 },
  { id: 'machine_chest_press', stim: { chest: 1, front_delts: 0.5, triceps: 0.5 }, stretch: 1, sfr: 5, axial: 0, setup: 45, rest: 120, pattern: 'horiz_push', rating: 4, reps: 10 },
  { id: 'smith_incline_press', stim: { chest: 1, front_delts: 0.5, triceps: 0.5 }, regions: ['chest_clavicular'], stretch: 1, sfr: 4, axial: 0, setup: 90, rest: 150, pattern: 'incline_push', rating: 3, reps: 8 },
  { id: 'pec_deck', stim: { chest: 1, front_delts: 0.25 }, stretch: 2, sfr: 5, axial: 0, setup: 30, rest: 90, pattern: 'chest_fly', rating: 4, iso: true, reps: 12 },
  { id: 'cable_fly', stim: { chest: 1, front_delts: 0.25 }, stretch: 2, sfr: 4, axial: 0, setup: 60, rest: 90, pattern: 'chest_fly', rating: 3, iso: true, reps: 12 },
  { id: 'dip_bw', stim: { chest: 1, triceps: 0.75, front_delts: 0.5 }, stretch: 2, sfr: 3, axial: 0, setup: 30, rest: 150, pattern: 'dip', rating: 3, reps: 10 },
  { id: 'machine_shoulder_press', stim: { front_delts: 1, side_delts: 0.5, triceps: 0.5 }, stretch: 1, sfr: 4, axial: 0, setup: 45, rest: 120, pattern: 'vert_push', rating: 3, reps: 10 },
  { id: 'db_lateral_raise', stim: { side_delts: 1 }, stretch: 0, sfr: 4, axial: 0, setup: 15, rest: 60, pattern: 'lateral_raise', rating: 4, iso: true, reps: 15 },
  { id: 'cable_lateral_raise', stim: { side_delts: 1 }, stretch: 1, sfr: 5, axial: 0, setup: 45, rest: 60, pattern: 'lateral_raise', rating: 3, iso: true, uni: true, reps: 15 },
  { id: 'triceps_pushdown', stim: { triceps: 1 }, stretch: 0, sfr: 5, axial: 0, setup: 30, rest: 60, pattern: 'elbow_ext', rating: 4, iso: true, reps: 12 },
  { id: 'overhead_cable_triceps_ext', stim: { triceps: 1 }, regions: ['triceps_long'], stretch: 2, sfr: 4, axial: 0, setup: 45, rest: 60, pattern: 'elbow_ext_overhead', rating: 3, iso: true, reps: 12 },
];
const pushCtx = {
  target: { chest: 6, side_delts: 4, triceps: 4, front_delts: 2 },
  goals: { chest: 'improve', side_delts: 'improve', triceps: 'improve', front_delts: 'maintain' },
  regionsWanted: ['chest_clavicular', 'triceps_long'], history: [], budget: 60, sPref: 3, unavailable: [],
};
show('PUSH base', select(push, pushCtx));
show('PUSH pec deck + chest press occupied', select(push, { ...pushCtx, unavailable: ['machine_chest_press','pec_deck'] }));
module.exports = { select, evaluate, cos };
