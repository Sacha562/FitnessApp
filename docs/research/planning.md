# LENS planning

## SUMMARY
Lens: weekly planning + today recommender + rest days + muscle goals. Core conclusions:
1) Count weekly volume per muscle as FRACTIONAL hard sets (direct=1, indirect=0.5; Pelland 2026, best-fitting method). Hypertrophy vs volume = square-root shape (diminishing returns). Strength vs volume = reciprocal shape (saturates early). Frequency barely matters for hypertrophy once volume is equal, but it does help strength. Per-session ceiling is about 11 fractional sets per muscle (Remmert preprint). ACSM 2026: at least 10 sets/wk, at least 2 sessions/wk per muscle, 2-3 RIR is enough; returns fall off around 18-20 sets/wk.
2) Targets by goal: improve = 10/12/14 fractional sets/wk (novice/intermediate/advanced), +4 for focus muscles, capped at a per-muscle MRV (RP, expert opinion). Maintain = about 1/3 of that (3-4 sets/wk), at least 1 session/wk, and intensity must be kept (Bickel 2011, Spiering 2021). Skip = 0; direct exercises forbidden, indirect work penalised.
3) Readiness per muscle: deterministic exponential fatigue decay. Dose per set = contribution x RIR factor x eccentric factor x novelty factor. Time constants are 22-34 h by muscle class, which gives about 80-90% recovered at 48 h and over 90% at 72 h, consistent with recovery studies (Morán-Navarro 2017, Korak 2015, Soares 2015). Tendon/grip/elbow are tracked as separate channels with tau = 48 h. A systemic channel with tau = 48 h drives rest-day advice. All constants are tunable from logs.
4) Today recommender: candidates are combinations of the PUSH/PULL/LEGS families plus REST. Score = sum over muscles of weight x gain in concave utility (using projected weekly volume, which already counts upcoming challenge sessions) minus penalties: protected readiness before the next challenge session, run timing, consecutive days, low systemic readiness, skip-muscle spill-over. Output is a ranked list with an explanation for each item.
5) Weekly planner: exhaustive enumeration of the free days x candidate types x add-on options. This is under 400k simulations and takes under 1 s on a phone. Fixed anchors come from Google Calendar. The objective includes a per-session cost, so the planner also recommends how many sessions to do. Ties are broken deterministically.
6) Challenges are data-driven definitions. Each one declares its session templates, calendar title patterns, constraints (min days between sessions, rest day before session B, at most 3 heavy unilateral sessions per 7 days), protected muscles/tissues with time windows, add-on policy, block/deload structure, and safety modes (elbow-pain mode). Challenge sessions count toward weekly volume. With my coefficients, the OAP challenge alone gives roughly 25-30 fractional lat sets/wk, about 15-17 biceps, heavy forearm/grip load, about 3 chest/triceps, and 0 legs/side delts. So for this user the general engine will mostly fill LEGS + PUSH (+ side delts) and add no pulling or grip work.
7) Running counts only as leg fatigue, with no hypertrophy credit. Penalties: legs within ±8 h of a run (Sporer & Wenger 2003); heavy legs within 48 h before a hard run if running priority is medium or higher (Doma). Chronic interference is negligible for hypertrophy and max strength (Schumann 2022), but running is worse than cycling (Wilson 2012).
8) Worked example for this week (Mon 5 to Sun 11 Oct 2026; C on Tue, A on Thu, test day Sun 13:00, which is the OAP deload week): the expected recommendation for today (Wed) is LEGS+PUSH, machine-based with no heavy grip or elbow-flexor work, at re-entry dose (x0.6, RIR+1) because legs have been untrained for more than 21 days. Saturday is forced rest (rest day before test day). Friday is optional push-light or a second leg exposure, depending on the user's session-count preference.
Every algorithm is a pure, versioned function (algo_version stored with every recommendation) and logs its score breakdowns so the periodic Claude review can tune the constants.

## FINDINGS
### Volume dose-response (hypertrophy) [strong]
Schoenfeld 2017: 15 studies/34 groups; each extra weekly set adds ES 0.023, i.e. +0.37% gain; high vs low volume differs by ES 0.241 (+3.9%); trend across <5, 5-9, 10+ sets/muscle (P=0.074). Pelland 2026 (67 studies, 2058 participants): hypertrophy best fit is a square-root model of FRACTIONAL weekly sets; posterior probability of a positive slope is 100%; returns diminish but no plateau is seen in the data range (~0-40 sets). Algorithm consequence: utility U = sqrt(v/T), capped at MRV.
SRC: Schoenfeld, Ogborn, Krieger 2017 J Sports Sci 35(11):1073-1082 | Pelland JC, Remmert JF, Robinson ZP, Hinson SR, Zourdos MC 2026 Sports Med 56(2):481-505 doi:10.1007/s40279-025-02344-w | https://sportrxiv.org/index.php/server/preprint/view/460

### Fractional set counting [moderate]
Pelland 2026 compared three ways of counting indirect sets: total (1), fractional (0.5), direct-only (0). Fractional fit best for hypertrophy (2xlogBF ~10.8 vs direct, ~10.0 vs total = 'very strong'/'strong' on the Kass-Raftery scale) and for strength. The per-session preprint found 'direct' counting best for strength. Rule: direct contribution 1.0, indirect 0.5, stabiliser 0 for volume (0.25 for fatigue only). Strength volume = direct sets only.
SRC: Pelland et al. 2026 Sports Med (Figure 3 model comparison, preprint v2 2024) | Remmert, Pelland, Robinson, Hinson, Zourdos preprint https://sportrxiv.org/index.php/server/preprint/view/537

### Strength vs volume and frequency [moderate]
Pelland 2026: strength vs volume fits a reciprocal curve (strong diminishing returns). Frequency has a 100% posterior probability of a positive effect on strength, with diminishing returns, but <100% for hypertrophy (negligible). Grgic 2018: no frequency effect on strength when volume is equated (p=0.421). Per-session preprint: PUOS ~2 direct sets per session for strength. Androulakis-Korakakis 2021: minimum effective dose for powerlifters is ~3-6 sets of 1-5 reps per lift per week, >80% 1RM, RPE 7.5-9.5, spread over 1-3 sessions.
SRC: Pelland et al. 2026 | Grgic et al. 2018 Sports Med Open doi:10.1186/s40798-018-0149-9 | Androulakis-Korakakis et al. 2021 Front Sports Act Living doi:10.3389/fspor.2021.713655

### Per-session volume ceiling [weak]
Remmert et al. (preprint): per-session volume has a positive dose-response for both outcomes (posterior 100%). Point of undetectable outcome superiority (PUOS) is ~11 fractional sets/muscle/session for hypertrophy and ~2 direct sets for strength. Data are sparse at very high per-session volumes. Rule: per-session cap 10 fractional sets per muscle (improve); split the rest across sessions.
SRC: https://sportrxiv.org/index.php/server/preprint/view/537 | https://biolayne.com/reps/issue-36/too-much-muscle-math-finding-the-ideal-training-volume-per-session/

### Recommended weekly ranges (guidelines) [strong]
ACSM 2026 Position Stand (Currier et al., MSSE 58(4):851-872; 137 systematic reviews, >30k participants): at least 2 sessions/wk per major muscle group; hypertrophy at least 10 sets/wk; diminishing returns beyond ~2-3 sets/exercise (strength) and ~18-20 weekly sets (hypertrophy); training to failure not needed, 2-3 RIR is enough; load 30-100% 1RM is equivalent for hypertrophy, at least 80% for strength; periodisation is not clearly superior. Baz-Valle 2022: 12-20 sets/wk per muscle is a good standard for trained young men (no moderate vs high difference for quads or biceps; triceps favoured >20). ACSM 2009: 2-3 d/wk novice, 3-4 intermediate, 4-5 advanced.
SRC: Currier BS et al. 2026 Med Sci Sports Exerc 58(4):851-872 https://pmc.ncbi.nlm.nih.gov/articles/PMC12965823 | Baz-Valle et al. 2022 J Hum Kinet https://pmc.ncbi.nlm.nih.gov/articles/PMC8884877/ | ACSM 2009 Progression Models in Resistance Training, MSSE 41(3)

### Volume landmarks (MV/MEV/MAV/MRV) [expert-opinion]
RP (Israetel) landmarks are expert opinion and the numbers vary between article versions. Back: MV ~6-10, MEV ~10-13, MAV ~15-22, MRV ~22-35, 2-4 sessions/wk. Triceps (direct only, alongside normal pressing): MV 0-4, MEV 4-6, MAV 6-16, MRV 16-20 (priority 20-26+). General RP: ~6 sets/wk maintains when trained at least 2x/wk; add 2-3 sets/wk if recovery is good, 1 if mixed, 0 if poor; deload ~50% volume. Use only as MRV caps and ramp rules, never as primary evidence.
SRC: https://rpstrength.com/blogs/articles/back-hypertrophy-training-tips | https://rpstrength.com/triceps-hypertrophy-training-tips/ | https://rpstrength.com/training-volume-landmarks-muscle-growth/

### Frequency for hypertrophy [strong]
Schoenfeld 2016 (10 studies): 2x/wk beats 1x/wk (ES 0.49 vs 0.30, P=0.002), but volume was not always equated. Schoenfeld 2019: with volume equated, no meaningful frequency difference. Schoenfeld 2015 (trained men): total-body 3x/wk gave more muscle thickness than a 1x/wk split. Saric 2019 and Colquhoun 2018: 3 vs 6x/wk equal when volume is equated. Ramos-Campo 2024 (14 studies, 392 people): split = full-body when volume is equated. Rule: frequency is a tool to spread volume under the per-session cap; small bonus for 2x/wk per improve muscle; strength goals need 2x/wk or more.
SRC: Schoenfeld, Ogborn, Krieger 2016 Sports Med (PMID 27102172) | Schoenfeld, Grgic, Krieger 2019 J Sports Sci | Schoenfeld et al. 2015 JSCR | Saric et al. 2019 JSCR (PMID 30363041) | Colquhoun et al. 2018 JSCR | Ramos-Campo et al. 2024 (split vs full-body meta)

### Maintenance volume and detraining [moderate]
Bickel 2011: 16 wk at 3 d/wk, then 32 wk at 1/3 or 1/9 of the dose. Both doses preserved hypertrophy in young adults (1/3 even added myofiber growth) but not in older adults (60-75). Spiering 2021: strength and size held up to 32 wk with 1 session/wk and 1 set/exercise IF intensity (relative load) is kept; older adults may need 2 sessions/wk and 2-3 sets/exercise. Ogasawara 2013: 3-week breaks inside 24 wk gave the same hypertrophy as continuous training (gains resume fast). Bosquet 2013: stopping training hurts submaximal strength (SMD -0.62), max force (-0.46) and power (-0.20). Rule: maintain = ~1/3 of the improve target (at least 3 sets), at least 1 hard set/wk at normal load; age 60+: about 1/2 and 2x/wk; re-entry ramp after more than 21 days off.
SRC: Bickel, Cross, Bamman 2011 MSSE (PMID 21131862) | Spiering et al. 2021 JSCR (PMID 33629972) | Ogasawara et al. 2013 Eur J Appl Physiol doi:10.1007/s00421-012-2511-9 | Bosquet et al. 2013 Scand J Med Sci Sports

### Recovery time course (muscle) [moderate]
MacDougall 1995: muscle protein synthesis +50% at 4 h, +109% at 24 h, back within 14% of baseline at ~36 h. Damas 2015/2016: the MyoPS response shrinks with training (week 1 > weeks 3 and 10) and tracks hypertrophy only once damage has faded, so the first bouts are 'damage-heavy'. Morán-Navarro 2017 (trained): failure sets (3x10[10]) recover clearly slower between 24 and 48 h than the same volume not to failure. Pareja-Blanco 2019: 40% velocity loss and lighter loads recover more slowly (CMJ down until 24 h). Korak 2015: at 48 h, 80% of people were within 1 rep of baseline, except bench (70%) and deadlift (60%); suggests 72 h for multi-joint barbell work in slow recoverers. Soares 2015: single-joint elbow-flexor work dropped torque 26.8% vs 15.1% for multi-joint, was still -8.4% at 24 h, and gave more soreness at 24-72 h. Fitbod: per-muscle 0-100% recovery, full reset after 7 days (formula not public). Rule: exponential decay with tau 22-34 h, heavier dose for failure, eccentric and novel exercises.
SRC: MacDougall et al. 1995 Can J Appl Physiol (PMID 8563679) | Damas et al. 2016 J Physiol | Morán-Navarro et al. 2017 Eur J Appl Physiol 117(12):2387-2399 | Pareja-Blanco et al. 2019 Sports (Basel) https://pmc.ncbi.nlm.nih.gov/articles/PMC6473797 | Korak, Green, O'Neal 2015 Int J Exerc Sci 8(1) https://digitalcommons.wku.edu/ijes/vol8/iss1/10 | Soares et al. 2015 JSCR (single vs multi-joint) | https://fitbod.me/blog/muscle-recovery/

### Connective tissue / elbow channel [weak]
Miller 2005: after exercise, tendon collagen synthesis is up at 6 h, peaks at 24 h (0.077%/h) and is still raised at 72 h. Tendon adapts and recovers more slowly than muscle, hence a separate tissue channel with tau 48 h. Baar: engineered ligaments respond to ~6-10 min of loading, then need a 6-8 h refractory period (in-vitro, extrapolated). This supports short daily hangs being fine and suggests no heavy grip or elbow-flexor loading in the window before challenge sessions.
SRC: Miller et al. 2005 J Physiol (PMID 16002437) | https://humanperformancealliance.org/playbook/how-to-improve-our-tendon-health-with-training/

### Proximity to failure and stimulus weighting [moderate]
Robinson 2024 (Sports Med 54(9):2209-2231): hypertrophy improves as sets end closer to failure (negative RIR slope, CI excludes null); strength is similar across a wide RIR range. ACSM 2026: 2-3 RIR is enough. Rule: hypertrophy credit drops with RIR (1.0 at RIR 0-1 down to 0 at RIR 6 or more); strength credit is flat for RIR 4 or less.
SRC: Robinson ZP et al. 2024 Sports Med doi:10.1007/s40279-024-02069-2

### RIR report accuracy [moderate]
Halperin 2022 meta: people underpredict reps to failure by 0.95 reps (95% CI 0.17-1.73; I2 97.9%). Accuracy is better close to failure and with 12 reps or fewer (beta 0.06 for ≤12 vs 0.47 for >12). Zourdos 2016: RIR-based RPE is valid (velocity r=-0.88 experienced, -0.77 novice); the gap is larger far from failure (RPE 5: 5.15 reps off; RPE 9: 2.05). Refalo 2024: trained lifters predict intraset RIR accurately on bench press. Rule: trust reported RIR ≤3 on sets of ≤12 reps; for >12 reps or RIR ≥4 use inferred RIR = max(reported, 1) and widen the uncertainty in logs.
SRC: Halperin et al. 2022 Sports Med (PMID 34542869) | Zourdos et al. 2016 JSCR | Refalo et al. 2024 JSCR doi:10.1519/jsc.0000000000004653

### Repeated bout effect / novelty [moderate]
A first eccentric or novel bout causes much larger strength loss and soreness. McHugh & Tetro 2003: ~86% of baseline torque on day 2 after the first bout, minimal change after a repeat bout 2 weeks later. Protection lasts at least 2 weeks (Kwiecien 2020) and is stronger in young adult males (Gorianovas 2013). Rule: novelty fatigue multiplier 1.5 if the exercise was not done in the last 21 days (1.25 if 14-21 days), plus a re-entry dose ramp.
SRC: McHugh & Tetro 2003 J Sports Sci | Gorianovas et al. 2013 BioMed Res Int | Kwiecien et al. 2020 Eur J Appl Physiol

### Concurrent running [moderate]
Wilson 2012 (21 studies, 422 ES): hypertrophy ES 1.23 for strength training alone vs 0.85 concurrent; strength 1.76 vs 1.44; power 0.91 vs 0.55. Running (not cycling) reduced hypertrophy and strength. Endurance frequency (r -0.26 to -0.35) and duration (r -0.29 to -0.75) correlate negatively. Schumann 2022 (43 studies): no interference for max strength (SMD -0.06) or hypertrophy (-0.01); explosive strength lower (-0.28), more so in the same session (p=0.043) vs ≥3 h apart; no moderation by run vs cycle. Sporer & Wenger 2003: aerobic work 4-8 h before cut leg-press reps, not at 24 h, and the effect is local. Doma: strength training leaves residual fatigue that impairs running for 24-48 h. Rule: runs add leg fatigue only, with timing penalties.
SRC: Wilson JM et al. 2012 JSCR 26(8) | Schumann M et al. 2022 Sports Med 52(3):601-612 | Sporer & Wenger 2003 JSCR | Doma & Deakin (JCU) https://researchonline.jcu.edu.au/58904/1/Doma-s40798-019-0195-y.pdf

### Fitness-fatigue modelling [weak]
Banister impulse-response: performance = k1*e^(-t/tau1) - k2*e^(-t/tau2). Common defaults: tau_fitness ~42 d, tau_fatigue ~7 d (TrainingPeaks CTL/ATL). Needs performance data per person to fit 4 parameters, so it is unreliable per muscle. Use only the short-term fatigue part (per-muscle tau in hours) now. A fitness term can be added later once there are at least 12 weeks of e1RM data.
SRC: https://arxiv.org/html/2505.20859v1 | Busso/Morton refinements cited therein

### Deloads [expert-opinion]
Bell 2023 Delphi (Sports Med Open; 34, 29 and 21 coaches across rounds): deloads are usually every 4-6 weeks for ~7 days (range: 1 session to 2 weeks). They can be pre-planned, autoregulated, or both (100% agreement). Volume can be cut by fewer days, sets or reps. Intensity can stay the same only if volume drops (95.2%). Proximity to failure can be reduced (100%). Deload when physically and mentally fatigued whatever the week (100%). Rule: sync the general deload with the challenge block (OAP week 5); autoregulated trigger as well.
SRC: Bell L, Strafford BW, Coleman M, Androulakis Korakakis P, Nolan D 2023 Sports Med Open doi:10.1186/s40798-023-00633-0 (PMC10511399)

### OAP challenge structure (from artifact data) [engineering-fact]
3 gym sessions/wk with at least 1 day between them, a FULL rest day before session B (heaviest), and never more than 3 heavy unilateral sessions/wk. Daily 2-arm hang of 2-3 min (skipped on A day). Home one-arm plank 2x/wk. Weekdays at 19:45-21:15 after dinner, weekends 09:00-16:00. Monday evening is chess (until 30 Nov). 5-week blocks: deload week = sets x0.5, same load, hang time x0.5, test day replaces B on Sunday. Elbow is the main injury risk: medial elbow pain, crease pain, ulnar tingling. All full-extension one-arm hangs are done on a neutral handle. Missed weeks are never made up. Test days planned 11-10, 08-11, 13-12-2026, 17-01, 21-02, 28-03, 02-05-2027. Calendar this week: C Tue, A Thu, test day Sun 13:00. Days move from week to week, so the planner must read anchors from the calendar, not fixed weekdays.
SRC: Local artifact HTML (OAP challenge page), C:\Users\sivuc\.claude\projects\C--Users-sivuc-projects-FitnessApp\23e9a33b-aa17-4d4a-80e1-fef330676224\tool-results\artifact-518dd69c-1791372722-e794.html

### OAP weekly volume estimate (derived) [weak]
Using the proposed coefficients (pull-up: lats 1, biceps 0.5, mid_back 0.5, forearms 0.5; isometric x0.5; unilateral side-set = 0.5): C ≈ lats 8, biceps 3.5. A ≈ lats 12, biceps 7. B ≈ lats 10 (incl. rows 0.5), mid_back 4+, biceps 6, chest 3, triceps 3, front delt 1.5. Weekly ≈ lats 25-30, biceps 15-17, mid_back ~12, forearms/grip very high, chest/triceps ~3, legs 0, side delts 0. So with challenge volume counted as committed, PULL is 'covered/saturated' and the general engine fills LEGS + PUSH + side/rear delts. Challenge volume must be exempt from MRV warnings: it is low-rep, heavy, high-RIR work, so set counts overstate the hypertrophy-style fatigue.
SRC: Derived from artifact + proposed coefficients (to be validated with logs)

### Time-efficient sessions [expert-opinion]
Narrative evidence: at least ~4 weekly sets/muscle (6-15RM) still produces gains. Supersets save ~1/3 of session time with slightly lower volume (Andersen 2022: the traditional session took 23 min / 66% longer, with 4.2% more volume and +1.3 RPE for supersets). Use this for the 'no time' path and short add-on blocks.
SRC: Iversen et al. 2021 Sports Med 'No Time to Lift?' (narrative) | Andersen et al. 2022 Front Psychol

## ALGORITHMS
### SetCredit.v1 (volume + fatigue accounting)
PURPOSE: Turn each logged set into per-muscle hypertrophy credit (fractional sets), strength credit (direct sets), fatigue dose, and tissue dose. Every other algorithm uses these numbers.
IN: set log {exercise_id, side L|R|both, reps, load_kg, assist_kg, rir_reported?, reason?, kind, ts, completed}; exercise meta {contrib[m]∈{1.0,0.5,0.25}, kind∈{dynamic,eccentric_only,isometric,hang}, ecc_emphasis bool, unilateral bool, tissue[k]∈[0,1]}; prescription {reps_min, rir}; last_done_date per exercise
OUT: per set: Vhyp[m], Vstr[m], D[m] (fatigue), L[k] (tissue); aggregated per ISO week and per rolling 7 d
PSEUDO:
STIM_RIR(q) = [1.00,1.00,0.95,0.90,0.80,0.50][q] for q<=5 else 0
FAT_RIR(q)  = [1.35,1.15,1.00,0.90,0.80][q] for q<=4 else 0.50
KIND = {dynamic:(stim 1.0,fat 1.0), eccentric_only:(1.0,1.3), isometric:(0.5,0.6), hang:(0 except forearms 0.5, 0.4)}
inferRIR(s,p): if s.rir_reported!=null: q=clamp(s.rir_reported,0,10); if s.reps>12 or q>=4: q=max(q,1) (Halperin bias)
  elif s.reps < p.reps_min: q=0
  elif s.reason=='too_easy': q=p.rir+2
  elif s.reason=='too_hard': q=max(0,p.rir-1)
  else q=p.rir
credit(s,ex): if !s.completed: return 0 (log skip+reason)
  q=inferRIR(s,p); side = ex.unilateral?0.5:1.0
  ds = days_since(ex.last_done); nov = ds>21?1.5 : ds>=14?1.25 : 1.0
  for (m,c) in ex.contrib:
    if c>=0.5: Vhyp[m] += side*c*STIM_RIR(q)*KIND[ex.kind].stim
    if c==1.0 and q<=4 and ex.kind in {dynamic,eccentric_only}: Vstr[m] += side
    D[m] += side*c*FAT_RIR(q)*KIND[ex.kind].fat*(ex.ecc_emphasis?1.3:1.0)*nov
  for (k,w) in ex.tissue: L[k] += side*w*FAT_RIR(q)*(ex.ecc_emphasis?1.3:1.0)
Runs (calendar/logged): per minute D: quads .05, calves .06, hamstrings .03, glutes .03; x intensity {easy 1.0, tempo 1.5, intervals 1.8, race 2.0}; x1.3 if >75 min; Vhyp=0.
CONST: Contribution: 1.0 direct, 0.5 indirect (Pelland 2026 fractional), 0.25 stabiliser (fatigue only). STIM_RIR follows Robinson 2024 (hypertrophy rises as sets end closer to failure; RIR≥6 counts as warm-up). FAT_RIR: failure slows recovery 24-48 h (Morán-Navarro 2017). Eccentric 1.3, novelty 1.5/1.25 at 21/14 days (repeated bout effect, McHugh 2003). Isometric stim 0.5 (assumption, weak). Unilateral side-set = 0.5 so that '5x3/arm' counts as 5 sets. Run coefficients are assumptions: a 40-min easy run is about 2 quad and 2.4 calf set-equivalents of fatigue.
LOG: Every set with reported vs inferred RIR, reps, load, reason, kind; exercise contrib version; weekly Vhyp/Vstr per muscle; run events with duration and intensity label; fraction of sets with RIR missing.

### MuscleReadiness.v1 (per-muscle fatigue decay + tissue + systemic)
PURPOSE: Deterministic 0-100% recovery per muscle, tissue channels (elbow, grip, etc.), and global systemic readiness. Drives today's choice, rest days, dose scaling and protection of challenge sessions.
IN: D[m] and L[k] events with timestamps (UTC Instants); session system loads; optional check-in (soreness per region 0-3, sleep hours); per-user kappa[m]
OUT: R_m(t)∈[0,1], Rt_k(t)∈[0,1], G(t)∈[0,1], next_ready(m,Rtarget) timestamp
PSEUDO:
TAU_H = {biceps,triceps,side_delt,rear_delt,forearms,calves,abs,obliques,upper_traps:22; chest,front_delt,lats,mid_back:26; quads,glutes,adductors:30; hamstrings,lower_back:34}
F_REF = 8 for {chest,lats,mid_back,quads,glutes,hamstrings}; 6 otherwise
F_m(t) = Σ_{e: 0<=t-t_e<=240h} D_m(e) * exp(-(t-t_e)/(TAU_H[m]*kappa[m]))
R_m(t) = clamp(1 - F_m(t)/F_REF[m], 0, 1)
Tissue: Ft_k(t)=Σ L_k(e)*exp(-(t-t_e)/48h); Rt_k = clamp(1 - Ft_k/6, 0, 1)
Systemic: SL_session = Σ_hardsets sysw / 15, sysw: lower-body compound 1.0, upper compound 0.7, isolation 0.4; run SL = minutes/40 * intensity
S(t)=Σ SL_i*exp(-(t-t_i)/48h); G(t)=clamp(1-0.5*S(t),0,1)
Check-in (optional): R_m *= (1-0.12*soreness_region(m)); if sleep<6h: G*=0.9
next_ready(m,Rt): F=F_m(now); thr=F_REF[m]*(1-Rt); return F<=thr ? now : now + TAU_H[m]*kappa[m]*ln(F/thr) hours
UI recovery % = round(100*R_m)
CONST: tau gives half-lives of 15/18/21/24 h. Residual fraction at 48 h: 0.11/0.16/0.20/0.24; at 72 h: 0.04/0.06/0.09/0.12. Consistent with 48-72 h recovery (Korak 2015, Morán-Navarro 2017, Soares 2015, MacDougall 1995). F_REF = 'a full hard session for that muscle'. Tissue tau 48 h (Miller 2005: collagen synthesis still raised at 72 h). Systemic tau 48 h, scale 0.5 (assumption). kappa default 1.0, bounds [0.7,1.5]. Rtarget: 0.85 improve, 0.75 maintain. Use UTC Instants for elapsed hours (DST ends 2026-10-25 in Europe/Amsterdam).
LOG: Snapshot of R_m, Rt_k, G, F_m at every session start; per exercise first hard set performance index PI = e1RM_set / best e1RM in the prior 28 d, with RIR-adjusted e1RM = load_total*(1+(reps+RIR)/30); soreness/sleep check-ins; reasons 'feeling bad' / 'too hard'.

### WeeklyTargets.v1 (goal → per-muscle target, caps, ramp, deload)
PURPOSE: Convert the user's improve/maintain/skip goals, training age and mesocycle state into weekly fractional-set targets, per-session caps and frequency needs.
IN: goal[m]∈{improve, improve_focus, maintain, skip}; training_age∈{novice<1y, intermediate 1-3y, advanced>3y}; age; mesocycle week w; last-week signals (mean R at session start, e1RM trend, override reasons); challenge committed dose per muscle; deload flags (challenge or autoregulated)
OUT: T[m] (fractional sets/wk), MRV[m], CAP_S[m] (per session), MIN_TOUCH[m], FREQ_TARGET[m], W[m] (weights), flags (covered_by_challenge, reentry)
PSEUDO:
BASE = {novice:10, intermediate:12, advanced:14}
MRV = {chest,lats+mid_back(each),quads,glutes,hamstrings:22; biceps,triceps,calves,abs:20; side_delt,rear_delt:24}
SPECIAL_IMPROVE = {front_delt:6, forearms:6, lower_back:4, adductors:6, upper_traps:6, obliques:4}
for m:
  switch goal[m]:
   improve|improve_focus: T = SPECIAL_IMPROVE[m] ?: BASE[age]; if focus: T+=4; T=min(T,MRV[m]); W = focus?1.5:1.0; CAP_S=10; MIN_TOUCH=2; FREQ_TARGET = (T>CAP_S or strength_goal[m]) ? 2 : 1 (bonus at 2)
   maintain: T = max(3, round(BASE/3)); if age>=60: T=max(6,round(BASE/2)), FREQ_TARGET=2; W=0.6; CAP_S=4; MIN_TOUCH=1; require ≥1 set/wk RIR<=3 at ≥85% of the 8-wk working load
   skip: T=0; W=0; forbid exercises with contrib[m]==1.0; indirect penalty 0.3 per fractional set
  ramp (improve only, from week 2 of the mesocycle): if meanR_start[m]>=0.85 and no e1RM drop: T+=1 (max +1/wk, ≤MRV); if mixed: +0; if e1RM drop ≥3% on ≥2 exercises for m or ≥2 'too hard'/'feeling bad' in the week: T-=2 (≥BASE-2)
  deload (challenge deload week if sync_deload, or autoregulated): T*=0.5, prescribed RIR+=2, load unchanged
  autoregulated deload trigger: (≥2 key exercises e1RM -3% over 2 consecutive exposures) OR (G<0.6 at 2 consecutive session starts) OR (≥2 consecutive sessions 'feeling bad'); default planned deload every 5th week (range 4-6)
  re-entry: if days_since_trained(m)>21: first 2 sessions dose*=0.6, RIR+1
  challenge: committed_m = Σ planned challenge session Vhyp for the rest of the week; if committed_m+done_m >= T: flag covered_by_challenge (no general volume added; no MRV warning from challenge volume)
CONST: BASE 10/12/14 (ACSM 2026 ≥10; Baz-Valle 12-20; ACSM diminishing returns ~18-20). Focus +4. CAP_S 10 (Remmert PUOS ~11). Maintain 1/3 (Bickel 1/3 and 1/9; Spiering 1 session/wk if intensity kept). Ramp +1/wk (RP +1 to +3, conservative). Deload x0.5 and RIR+2 every 5 wk (Bell 2023: 4-6 wk, ~7 d; OAP block of 5). Re-entry 0.6 for >21 d (Ogasawara 3-wk breaks; repeated bout effect). MRV values are RP expert opinion, used only as caps.
LOG: Weekly T, planned vs achieved Vhyp per muscle (attainment %), ramp decisions and reasons, deload triggers that fired, per-muscle e1RM trend over 4-8 wk vs volume (personal dose-response), maintain-muscle intensity checks.

### SessionDoseAllocator.v1
PURPOSE: For a candidate session type on a given day and time budget, decide how many fractional sets each included muscle gets. The exercise-selection lens then turns these numbers into exercises.
IN: candidate C (set of families), day/time t, budget_min, T[m], done_m (this ISO week), committed_m (fixed future sessions this week), opportunities_m (remaining planned sessions incl. this one that include m), R_m(t), CAP_S, MIN_TOUCH, deload/reentry flags, expected indirect ratios per family
OUT: alloc[m] fractional sets, est_minutes, dropped muscles with reason
PSEUDO:
FAMILIES: PUSH={chest,front_delt,side_delt,triceps}, PULL={lats,mid_back,rear_delt,biceps,forearms,upper_traps}, LEGS={quads,hamstrings,glutes,adductors,calves}, CORE={abs,obliques,lower_back}
M_C = union(families(C)) minus skip muscles
for m in M_C order by (W desc, deficit desc, m.name asc):
  rem = max(0, T[m] - done_m - committed_m)
  if rem==0: alloc=0; continue
  if R_m(t)<0.5: alloc=0; reason='recovering'; continue
  a = clamp(ceil_half(rem/max(1,opportunities_m)), MIN_TOUCH[m], CAP_S[m])
  if 0.5<=R_m<0.8: a = round_half(a*R_m)
  apply deload/reentry multipliers; alloc[m]=a
indirect: subtract the expected indirect credit before direct allocation (triceps -= 0.5*chest_compound_sets; biceps -= 0.5*pull_compound_sets; front_delt -= 0.5*press_sets; glutes -= 0.5*quad_compound_sets)
est_minutes = 10 (warm-up, or 0 if add-on) + Σ_m alloc[m]*(0.6 + rest_m) + 1.5*ceil(Σalloc/3)
rest_m: compound heavy 3.0, compound moderate 2.0, isolation 1.5 (min)
while est_minutes > budget_min: reduce the lowest-priority muscle (maintain before improve, lowest W, then lowest deficit) by 1 set, never below MIN_TOUCH for improve; then drop that muscle entirely; recompute
if Σalloc < 3: candidate infeasible
CONST: R thresholds 0.5 (exclude) and 0.8 (scale). Rest times are defaults (rest-timer lens may refine). Warm-up 10 min (matches OAP warm-up). Set execution 0.6 min. Transition 1.5 min per ~3 sets. Indirect ratio 0.5 (Pelland fractional).
LOG: Allocated vs performed sets per muscle; est_minutes vs actual session duration (start/finish timestamps); muscles dropped for time or recovery; exercises skipped with reason 'no time'/'occupied'.

### TodayRecommender.v1 (what to train today)
PURPOSE: When the user says 'I want to go today', rank candidates (push, pull, legs, combinations, full body, add-on to a challenge session, rest) and explain each one.
IN: now, budget_min (user input or calendar free slot), R_m, Rt_k, G, T/W/goals, done+committed volume, fixed upcoming sessions (challenge/planned) with protect specs, runs (logged ±8 h, planned 48 h), consecutive training days, challenge hard rules, user split preferences
OUT: ranked list [{type, score, alloc[m], est_minutes, explanation[]}], REST option with reason
PSEUDO:
CANDS = [LEGS, PUSH, PULL, PUSH+LEGS, PULL+LEGS, PUSH+PULL(UPPER), FULL, CORE_ACCESSORY, REST]; if a challenge session is today: CANDS = [CHALLENGE, CHALLENGE+addon(F) for F in challenge.addon_policy.allowed]
U_improve(v)=sqrt(min(v,MRV)/T) - 0.5*max(0,v-MRV)/T; U_maint(v)=min(v,T)/T + 0.05*max(0,v-T)/T; Vproj[m]=done_m+committed_m
for C in CANDS:
  if hard_violation(C): skip  # blocked slot; challenge rest_day_before rule; >max_heavy_unilateral_7d; elbow_mode forbids family tags
  alloc = SessionDoseAllocator(C, now, budget); if infeasible: skip
  s=0
  for m: dv = alloc[m]*(0.5+0.5*R_m); s += W[m]*(U_m(Vproj[m]+dv)-U_m(Vproj[m]))
          if goal improve and distinct_sessions_m_week==1 and alloc[m]>=2: s += 0.15*W[m]
  for skip muscles: s -= 0.3*indirect_sets[m]/4
  for X in fixed sessions with t_X-now <= 72h: for m in X.protect_muscles: s -= 5*max(0, X.Rmin - R_m(t_X | +C))
                                         for k in X.protect_tissues: s -= 5*max(0, X.Rmin_t - Rt_k(t_X | +C))
  if LEGS in C: if run within [now-8h, now+8h]: s-=0.5; if hard run planned within 48h and run_priority>=medium: s-=0.4
  c = consecutive training days incl today; if c>=4: s -= 0.5*(c-3)
  if G(now)<0.55: s -= 0.5
  s += 0.05*user_pref(C)
score(REST) = 0.10 + (G<0.55 ? 0.4 : 0)
rank by score desc; tie-break: oldest last-trained family first, then fixed order [LEGS,PUSH,PULL,PUSH+LEGS,PULL+LEGS,UPPER,FULL,CORE,REST]
if best non-rest score < 0.15: recommend REST
explanation per item: top 3 muscle deltas ('quads 0→6/12 sets, 100% recovered'), exclusions ('Pull excluded: OAP session A tomorrow 19:45 protects lats/biceps/forearms/elbow'), warnings
CONST: W: focus 1.5, improve 1.0, maintain 0.6. Quality factor 0.5+0.5R. Frequency bonus 0.15 (ACSM ≥2/wk, Schoenfeld 2016). Protection penalty 5 per unit of readiness deficit; default Rmin 0.85 for muscles, 0.90 for tissues; protect window 30 h before any challenge session, 48 h before max-strength or test sessions. Run windows ±8 h (Sporer & Wenger) and 48 h before hard runs (Doma). Consecutive-day penalty from 4 days. G<0.55 penalty. Rest base 0.10; go-threshold 0.15. All constants are versioned (algo_version 'today.v1').
LOG: Full candidate list with score components; option chosen by the user (accepted rank or override); actual session performed vs recommended; readiness snapshot; next-session performance of protected muscles (did the challenge session go as planned: e.g., B 6x2 all at RIR 2).

### WeeklyPlanner.v1 (recommended sessions + rest days this week)
PURPOSE: Produce this ISO week's recommended schedule: fixed sessions from the calendar plus recommended extra sessions or add-ons on free days, rest days, and the recommended session count.
IN: ISO week Mon-Sun (Europe/Amsterdam), today; logs for past days; fixed events from CalendarMapper (challenge, planned gym, runs, busy blocks); availability windows per weekday (e.g., Mon evening blocked, weekend 09:00-16:00, weekday 19:45-21:15 default); N_desired (optional) or session cost; budget per session; all WeeklyTargets outputs
OUT: day list [{date, kind: fixed_challenge|fixed_planned|recommended|rest, type, addon?, est_minutes, alloc[m], reasons[]}], projected weekly Vhyp vs T per muscle, recommended N, warnings
PSEUDO:
free = [d in today..Sun if no fixed session and slot available]
fixed_future = challenge/planned sessions >= today; addon_opts(X) = [none] + X.challenge.addon_policy.allowed families (each limited to max_addon_minutes)
k_max = N_desired? clamp(N_desired - count(fixed this week incl done), 0, |free|) : min(3,|free|)
best=null
for k in 0..k_max:
 for days in combinations(free,k) (lexicographic):
  for types in product(CANDS_no_rest, repeat=k):
   for addons in product(addon_opts(X) for X in fixed_future):
     if hard_violation(plan): continue  # challenge rules, rest_day_before, max_heavy_unilateral_7d, blocked slots, large lower-body hard dose (≥4 sets) <40h apart
     sim = simulate days in order: allocate with SessionDoseAllocator (opportunities from this plan), update F/L/S
     J = Σ_m W_m*U_m(sim.V_m) + Σ_{improve m} 0.15*W_m*[freq_m>=FREQ_TARGET]
         - Σ_{challenge X} 5*protect_deficit(X) - Σ run_conflicts - 0.5*max(0,maxConsecutive-3)
         - (N_desired? 0.25*|n-N_desired| : 0.20*n_extra) - (rest_days==0 ? 0.3 : 0) - 0.3*Σ skip indirect/4
     if J > best.J + 1e-9 or (|J-best.J|<=1e-9 and tiebreak(plan,best)): best=plan
tiebreak: fewer sessions > fewer add-ons > earlier first rest day > lexicographic types
rest days = days not used; reason per rest day: challenge rule / recovery (show next_ready per family) / no slot
recommended N = n of best plan when N_desired is unset
replan on: app open on a new day, session finish, calendar change, goal change; past days locked
CONST: Candidates 8 (non-rest). With |free|≤5, k≤3 and ≤4 add-on options per fixed session: Σ C(5,k)*8^k = 5801 plans x ≤64 add-on combos ≈ 371k simulations, under 1 s in Kotlin. Per-extra-session cost 0.20 (time cost, tunable). Need ≥1 full rest day/wk (soft, 0.3). Max 3 consecutive days (soft, 0.5/day). 40 h minimum between hard large lower-body doses. Floating-point: round utilities to 1e-6 before comparing.
LOG: Each generated plan (algo_version, inputs hash, J breakdown), which days the user actually trained, deviation reasons, weekly attainment vs plan, plan churn (replans per week), user-set N_desired history.

### CalendarMapper.v1 (Google Calendar → anchors)
PURPOSE: Deterministically turn calendar events into challenge sessions, planned gym sessions, runs and busy blocks. Also ignores stale Todoist all-day 'Gym' items.
IN: events from user-selected calendars (id, calendarId, title, start, end, allDay, location, status, description); active challenge title patterns; keyword lists; per-event manual override table
OUT: typed anchors [{event_id, kind: challenge_session(key)|planned_gym(type|auto)|run(intensity, minutes)|busy, start, end, confidence}]
PSEUDO:
for e in events(week): if e.status=='cancelled' or e.allDay or e.calendarId in ignore_list: continue
  t = normalize(e.title): lowercase, strip diacritics (één→een), replace dashes/em-dash with space, collapse spaces
  if override[e.id]: emit override; continue
  for ch in active_challenges, for s in ch.sessions: if any regex in s.title_patterns matches t: emit challenge_session(ch,s.key), conf=high; next e
  if t matches /\b(gym|training|workout|fitness|sportschool)\b/ or e.location contains 'basic-fit': type = first match of {push|duw→PUSH, pull|trek→PULL, legs|benen→LEGS, upper|boven→UPPER, lower|onder→LEGS+CORE, full|fullbody→FULL} else AUTO; emit planned_gym
  elif t matches /(hardlopen|run|running|loop|duurloop)/: intensity = interval|tempo|wedstrijd|race|lange → mapped else easy; minutes = end-start; emit run
  else emit busy
dedupe same kind within 120 min (keep earliest)
OAP default patterns: C: ['sessie c\b','volume en techniek'], A: ['sessie a\b','een arm zwaar'], B: ['sessie b\b','maximale kracht'], TEST: ['testdag']
CONST: Ignore all-day events by default (removes the 2022-2023 Todoist 'Gym' tasks). Patterns live in challenge data, not code. Busy blocks reduce free slots: a free slot needs at least budget_min + 15 min travel.
LOG: Unmatched events the user manually tagged (pattern-learning list for review), mapping confidence, overrides.

### ChallengeConstraints.v1 (data-driven challenge integration)
PURPOSE: Let any challenge (OAP now) declare sessions, calendar patterns, volume contributions, scheduling rules, protections, add-on policy, block/deload and safety modes, with no hardcoding. The planner and recommender only read this data.
IN: challenge definition JSON (versioned row in DB), enable toggle, current block week, safety flags (elbow_pain reported date)
OUT: fixed-session templates with committed muscle/tissue dose; hard rules; protect specs; addon policy; deload/test calendar; safety mode effects
PSEUDO:
schema: {id, version, name, enabled, sessions:[{key, name, title_patterns[], exercises[{exercise_id, sets, reps|seconds, per_side, load_rule}], intensity_class: heavy|max|volume|test, heavy_unilateral bool, default_weekday, duration_min}],
 rules:{min_rest_days_between_sessions:1, rest_day_before:[{session:'B', scope:'all_gym'},{session:'TEST', scope:'all_gym'}], max_heavy_unilateral_per_7d:3, no_makeup_missed:true},
 protect:{muscles:[lats,mid_back,biceps,forearms,rear_delt], tissues:[elbow_medial,biceps_tendon,grip], window_h:{default:30, max:48, test:48}, Rmin:0.85, Rmin_tissue:0.90},
 addon_policy:{allowed_families:[LEGS,PUSH,CORE], forbidden_tags:[grip_heavy, elbow_flexor_heavy, supinated_curl, unstrapped_deadlift], max_addon_minutes:25},
 block:{length_weeks:5, deload_week:5, deload:{sets:0.5, load:1.0, hang:0.5}, test_replaces:'B', sync_general_deload:true},
 safety:{elbow_pain:{duration_days:14, general_forbid_tags:[elbow_flexor_heavy, grip_heavy, supinated_curl, elbow_extension_heavy?], note:'mirror challenge: no unilateral, light bilateral'}},
 volume_warning_exempt:true}
committed dose: run SetCredit on the template's prescribed sets with prescribed RIR (deload-adjusted) → committed_m, committed tissue
hard_violation(plan): any gym on a rest_day_before day (scope all_gym); challenge sessions <min_rest_days apart (warn only, since the user controls the calendar); heavy_unilateral count >3 in any 7-day window; forbidden_tags inside the protect window
elbow mode active: general recommender removes forbidden tags for duration_days; challenge sessions follow their own rules (outside this lens)
CONST: OAP values come from the artifact rules (at least 1 day between sessions, full rest day before B and test day, max 3 heavy unilateral sessions per 7 days, 5-week block with deload x0.5 sets, elbow mode 14 days). Protect windows of 30 h and 48 h and the tissue Rmin of 0.90 are assumptions to tune. Add-on max of 25 min keeps the 19:45-21:15 slot realistic (assumption).
LOG: Challenge session outcomes (e.g., B: all 6x2 at RIR 2 yes/no, negatives seconds), whether a general session in the protect window preceded a failed or downgraded challenge session, elbow-pain reports and their timing vs prior general sessions, add-on durations.

### GoalPreferenceWarnings.v1
PURPOSE: Show non-blocking, evidence-referenced warnings when goals, preferences or the plan fall outside evidence-based ranges. Covers the 'app may override, show warning' requirement for this lens.
IN: T, projected weekly Vhyp/Vstr, per-session alloc, freq_m, goals, user set/rep preference, age, maintain-intensity checks, challenge exemptions
OUT: warnings [{code, severity info|warn, muscle?, text, source}] (each dismissable; logged)
PSEUDO:
W1 improve & projected Vhyp<10 (or <T-2) → warn 'below ~10 sets/wk growth minimum' [ACSM 2026; Schoenfeld 2017]
W2 any session alloc[m]>11 → info 'beyond ~11 sets/session little extra growth; split' [Remmert/Pelland]
W3 projected Vhyp>MRV[m] and not challenge-exempt → warn 'very high volume; diminishing returns, recovery risk' [Pelland 2026; RP]
W4 improve or strength goal & freq_m<2 when T>10 → info 'train ≥2x/wk' [ACSM 2026; Pelland strength frequency]
W5 maintain & no set at RIR≤3 and ≥85% working load in 14 d → warn 'maintenance needs intensity' [Spiering 2021]
W6 a major family all 'skip' → info once 'ACSM recommends all major muscle groups ≥2 d/wk for health' (dismiss forever)
W7 user preference sets/exercise==1 → info 'two sets beat one' [ACSM 2026]; preferred reps>30 or RIR≥5 → info 'low stimulus' [Robinson 2024]; reps>12 → info 'RIR estimates less accurate' [Halperin 2022]
W8 rest days==0 in plan or ≥4 consecutive → info [Bell 2023 fatigue; recovery data]
Override policy: the engine changes the user preference only when W1/W3 severity is warn AND the user enabled 'allow evidence overrides'; otherwise warn only
CONST: Thresholds: 10 sets, 11 per session, MRV table, 14 days, 85% load. Show each code at most once per week per muscle.
LOG: Warnings shown, dismissed, acted on (preference changed), and whether outcomes (e1RM trend) differ for muscles with active warnings.

### RunInterference.v1
PURPOSE: Model running (from calendar 'Hardlopen' or manual logs) as leg fatigue and timing conflicts, without giving it hypertrophy credit.
IN: run events {start, minutes, intensity label}, run_priority∈{low,medium,high} (user setting)
OUT: leg fatigue doses (into MuscleReadiness), systemic load, scheduling penalties
PSEUDO:
D per run as in SetCredit (per-minute coefficients x intensity x long-run factor); SL = minutes/40*intensity
penalty(LEGS-containing session at t): if |t - t_run| <= 8h: 0.5 (if the run is before) / 0.3 (if the run comes after within 8h); if hard run (tempo/intervals/race/long>75') in (t, t+48h] and run_priority>=medium: 0.4; if run_priority==high: double the penalties
UPPER sessions: no run penalty
CONST: 8 h window (Sporer & Wenger 2003: leg-press volume down 4-8 h after, not at 24 h). 48 h before hard runs (Doma: residual strength-training fatigue 24-48 h). Running gets no hypertrophy credit (Schumann 2022: no hypertrophy interference; trained lifters gain little size from running). Coefficients are assumptions.
LOG: Leg-session performance index when preceded by a run within 24 h vs not; run labels; user overrides citing running.

### ModelTuner.v1 (offline, used by periodic Claude review)
PURPOSE: Deterministic re-estimation of personal constants from logs. Results are proposed as a new algo_version for review and are never applied silently.
IN: ≥8 weeks of logs: readiness snapshots, PI per exercise, weekly V vs e1RM trends, recommendation acceptance, override reasons, session durations
OUT: proposed kappa[m-class], ramp/target adjustments, time-model corrections, report table
PSEUDO:
for class in {small_upper, large_upper, lower, posterior}: obs = (R_class at session start, PI); need n>=12
  for kappa in [0.7..1.5 step 0.1]: recompute R with tau*kappa; fit PI = a + b*R by OLS; record SSE
  choose the kappa with min SSE; accept only if b>0 and SSE improves by ≥5% vs kappa=1.0
time model: rest_m and set time = median of actual (finish-start)/sets per exercise type over the last 6 weeks
targets: if attainment[m]<70% for 3 consecutive weeks → propose T[m]-=2 + reason; if 'too easy' >30% of sets of a muscle for 2 weeks → ramp +2
acceptance: per candidate type, acceptance rate <50% over ≥6 recommendations → flag weights W/penalties for review
emit diff + versioned constants JSON
CONST: Minimum samples 12; grid step 0.1; 5% improvement threshold; 70% attainment; 30% too-easy share.
LOG: All of the above logs; store algo_version on every recommendation/plan row so effects can be split by version.

## DESIGN OPTIONS
- Per-muscle fatigue/recovery model: [Fitbod-like: per-muscle % with a fixed 7-day linear reset / Exponential decay per muscle with tau by muscle class + tissue + systemic channels / Full Banister fitness-fatigue per muscle (k1,k2,tau1,tau2 fitted)] -> Exponential decay (option 2) now; add a Banister fitness term later once ≥12 weeks of e1RM data exist. WHY: Deterministic, explainable ('quads 77% recovered'), only ~4 tunable constants per class, matches the 24-72 h recovery literature. Banister needs per-person fitting and is poorly validated per muscle.
- How to count weekly volume: [Direct sets only / Total sets (indirect=1) / Fractional (direct 1, indirect 0.5, stabiliser 0)] -> Fractional for hypertrophy targets; direct-only for strength credit. WHY: Best model fit in the Pelland 2026 meta-regression (hypertrophy and strength) and the per-session preprint (direct best for strength).
- Week boundary for 'this week': [ISO week Mon-Sun / Rolling 7 days / Hybrid: ISO week for targets/display, rolling windows for readiness, frequency and max-unilateral rules] -> Hybrid. WHY: The user thinks in calendar weeks and the challenge rules are weekly; readiness and '≤3 heavy unilateral per 7 d' need rolling windows to avoid Monday-reset artefacts. No carry-over of missed volume (matches the challenge's 'no make-up' rule).
- Session taxonomy: [Fixed templates (PPL, upper/lower, full) / Family combinations generated on the fly (PUSH/PULL/LEGS/CORE unions) + add-ons / Pure muscle-level free-form selection] -> Family combinations (≤3 families) + challenge add-on blocks. WHY: Covers push/pull/legs/'pull + legs' etc. naturally and keeps labels the user understands. Free-form selection is harder to explain.
- Weekly schedule optimiser: [Greedy day-by-day / Exhaustive enumeration with constraint pruning / ILP/CP solver library] -> Exhaustive enumeration (≤~400k simulations). WHY: Deterministic, globally optimal for the small search space, no dependency, fast enough on a phone. Easy to log the objective breakdown.
- Where extra (non-challenge) work goes: [Separate sessions on free days / Add-on blocks (≤25 min) appended to challenge sessions / Both, chosen by the optimiser] -> Both, chosen by the optimiser, with the user able to disable add-ons. WHY: Challenge sessions already take 90 min. Legs and push don't interfere locally with pulling, so add-ons save gym trips, but time and systemic fatigue matter. Ask the user.
- Deload policy for general training while a challenge runs: [Independent general deload schedule / Sync general deload with the challenge deload week / Autoregulated only] -> Sync with the challenge block (week 5) + autoregulated triggers. WHY: One recovery week for the whole body fits the test-day logic. Bell 2023 consensus allows pre-planned + autoregulated.
- Running modelling: [Ignore runs / Leg-fatigue + timing penalties only / Full concurrent-training model with hypertrophy discount] -> Leg-fatigue + timing penalties. WHY: Chronic interference on hypertrophy/max strength is negligible (Schumann 2022). The practical issue is acute leg fatigue and timing (Sporer & Wenger 2003; Doma).
- Where algorithms execute: [On-device pure Kotlin module / Supabase SQL/Edge Functions / Both (device computes, server stores)] -> On-device pure-function module (versioned, golden-file unit tests); Supabase stores logs, plans, constants and algo_version. WHY: Works offline in the gym, deterministic and testable; server-side reproducibility comes from storing inputs hash + algo_version + constants JSON.
- Calendar integration direction: [Read-only / Read + write recommended sessions as events / Read + optional write on user confirm] -> Read-only for v1; optional write on confirm later. WHY: Avoids clutter and duplicate 'Gym' events (the Todoist sync already caused stale ones); writing needs a broader OAuth scope.
- Session count: [User sets N_desired per week / Planner recommends N via per-session cost / User sets max, planner recommends ≤ max] -> User sets a max (and preferred days); planner recommends N ≤ max. WHY: Respects life constraints (chess, runs) while still recommending rest days.

## RISKS
- Recovery time constants, F_REF, run coefficients, isometric/hang credits and protect windows are extrapolations or expert choices. Early recommendations may be off until ModelTuner has ≥12 observations per class.
- RIR self-reports are biased (~1 rep underprediction, worse above 12 reps or far from failure), which adds noise to both stimulus and fatigue.
- Set-count accounting overstates the volume of OAP challenge work (low-rep, heavy, isometrics, hangs). This can cause false 'over MRV' warnings and over-protection of pull muscles, hence the challenge volume_warning_exempt flag.
- Treating challenge sessions as committed volume assumes they happen. Missed or shifted sessions need an immediate replan, otherwise pull muscles look covered when they are not.
- Calendar parsing fragility: Dutch titles with diacritics, days moving each week, stale all-day Todoist events, the user renaming events. Manual tagging and overrides are needed.
- Elbow injury risk: general push work (dips, heavy triceps extension) also loads the elbow. The tissue channel and elbow mode must be conservative, and the app must not give medical diagnoses.
- Adding legs and push volume on top of a demanding challenge may hurt OAP progress through systemic fatigue and sleep. The challenge must have priority, and G and test-day outcomes must be monitored.
- Too many warnings or too much numeric detail can clutter the clean UI. Show at most 1-2 warnings and progressive disclosure of score breakdowns.
- Determinism pitfalls: floating-point ties, time zones (DST change 2026-10-25), unstable sort orders. Use UTC Instants, fixed rounding (1e-6) and explicit tiebreak orders.
- Small-sample personal tuning can overfit. Require minimum samples and improvement thresholds, and have every constant change reviewed (algo_version bump).
- Evidence mostly comes from young, often untrained or male cohorts. Targets for other users (the friend, women focusing on legs) may need different defaults; maintain rules differ by age (≥60).

## QUESTIONS
- How many gym sessions per week in total (including the 3 OAP sessions) are you willing to do: 3 (OAP plus short add-ons only), 4, or 5? And which days are realistically possible (e.g., is Monday before chess an option)?
- May the app append short (≤20-25 min) leg/push blocks to OAP sessions, or should OAP sessions stay pure and extra work go on separate days?
- The OAP plan has a rest day before session B/test day. Should that be a full no-gym day (no legs either), or only no upper-body or pulling?
- What are your current muscle goals outside the challenge: legs (improve/maintain/skip), chest/shoulders/triceps (improve/maintain), core? Is pull simply 'covered by the challenge'?
- How much running do you do (sessions/week, typical duration, any intervals/races), and does running take priority over leg training (e.g., no heavy legs within 48 h before a hard run)?
- How many years of general (non-challenge) lifting experience do you have, and when did you last train legs consistently? This sets the default targets and re-entry ramp.
- Which Google calendars should the app read? Is it fine to ignore all-day events (the old Todoist 'Gym' tasks)? Should the app ever write recommended sessions to your calendar?
- Should general training deload in sync with the OAP deload week (this week, with test day on Sun 11 Oct)?
- Default time budget per session (60, 75 or 90 min), and do you want supersets offered when time is short?
- Do you want an optional 10-second check-in at session start (sleep, soreness) to adjust readiness, or should everything be inferred from logs?
- Week start: Monday-Sunday for 'this week'?
- When the evidence clearly disagrees with your preference (e.g., a muscle below ~10 sets/wk while marked 'improve'), should the app only warn, or auto-adjust with a warning?
