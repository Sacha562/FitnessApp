# LENS app_benchmarks

## SUMMARY
Lens: reverse-engineer existing algorithmic training apps, borrow proven deterministic mechanisms.

What the apps actually do:
- Fitbod: per-muscle recovery 0-100% from summed sets/reps/load. Muscles back to 100% after about 6-7 days. Exercise score combines recovery, goal, preferences, split and equipment. Weights come from an Epley e1RM. "Recommend more often" gives the exercise 40-60% more appearances; "less often" gives 20-40% fewer. Complaints: weights you cannot load, bad first-time weights for new exercises, overrides not learned, too much exercise rotation.
- RP Hypertrophy: start at MEV and add sets from soreness + performance scores (published 1-4 x 1-4 table). RIR goes 3 to 0 across the mesocycle. Load goes up about 2-2.5% per week; reps are added when the next load step is too big. Deload is about 50% volume. Reverse-engineering reports say the logic is simple and the joint-pain answer does not change anything.
- Alpha Progression and Gravl: per-set targets from e1RM, snapped to the weights the gym really has. Prefer adding reps when the next weight jump is large (example: 7.5 to 10 kg is +33%). Gravl uses a rep ladder (8-7-6 becomes 8-8-6, then 8-8-7, ...), lowers the working max about 7% for the second exercise on the same muscle, and caps changes at about 10% per session.
- Hevy Trainer: raise weight only when the top of the rep range is hit on all sets.
- Liftosaur: lp/dp/sum progressions plus per-set "update" scripts and saved state variables. This is the best template for data-driven challenges.
- Simple failure rules from fixed programs: Stronglifts (3 fails, then -10%), GZCLP (on failure switch rep scheme, e.g. 5x3 to 6x2 to 10x1, then reset), 5/3/1 (training max 90%; missed reps, then -10-15%), Juggernaut (training max moves by reps over standard x 2.5 lb upper / 5 lb lower, at most 10 extra reps), APRE (rep-out table), Dr. Muscle (when 1RM drops: sets -50%, load -10%).
- RTS: e1RM = load / RPE-chart percentage.

What the research says:
- The reps-vs-%1RM curve differs by exercise and by person (Nuzzo 2024). So fit a personal curve constant k. The user's own two data points give k of about 0.0327 (modified Epley).
- People guess their reps in reserve about 1 rep too low (Halperin 2022).
- Training closer to failure helps muscle growth but not strength (Robinson 2024). Training to failure slows recovery by 24-48 h (Morán-Navarro 2017).
- More volume has diminishing returns. Count indirect sets at 0.5 (Pelland 2026).
- A +20% volume increase grew muscle as well as +120% (Camargo 2026).
- A 1-week full stop mid-programme reduced strength gains but not muscle growth (Coleman 2023). So a deload should reduce training, not stop it.
- Maximum strength barely drops for up to 28 days off (Bosquet 2013).
- Strength and muscle can be kept with 1 session per week, 1 set per exercise, at the same intensity (Spiering 2021).

Recommended combined design (fully deterministic, versioned):
- A per-muscle fatigue model with exponential decay that adapts to the user (Fitbod-like).
- Session type chosen by need x readiness x goal weight, with a look-ahead that protects challenge sessions.
- Exercise choice by greedy set-cover with preference multipliers and a fallback that allows a disliked exercise when it is the only one for a needed muscle. Exercises stay fixed for the mesocycle (RP), not rotated every session.
- Loads from a personal e1RM with personal k, snapped to real equipment. Double progression with a RIR check and a rep ladder. A failure counter cuts load 7.5% after 2 failures.
- Within a session, remaining sets are recalculated when the reps+RIR error is 2 or more, capped at ±10%.
- Weekly sets per muscle follow the RP table, filled in automatically from logged performance; increases capped at +20%.
- 4+1 week blocks synced with the challenge, plus reactive deload triggers.
- Override reasons change how much each set counts in the estimates.
- Missed-session and detraining load reductions based on gap length.
- Challenges as JSON rule definitions (Liftosaur-style progress/update hooks, state, prompts, protocols).
- Every decision is logged with inputs and candidate scores, so Claude Code can tune constants later.

## FINDINGS
### Fitbod: recovery model [moderate]
Per muscle group recovery 0-100% from sets x reps x load of all exercises hitting that muscle (sum). 48-72 h typical between same-muscle sessions; muscles marked fully recovered after ~6-7 days rest. Cardio (incl. synced runs) counted in recovery. <50% = 'mostly fatigued'. Prioritizes 'fresh' (80-100%) muscles. No formula/decay curve published; primary vs secondary weighting undisclosed. Fitbod itself says 'no magic number'. Borrow: exponential per-muscle fatigue accumulator, cardio-as-fatigue, heat-map UI.
SRC: https://fitbod.me/blog/fitbod-algorithm | https://fitbod.me/blog/muscle-recovery/ | https://fitbod.me/blog/tracking-volume-intensity-and-recovery-with-fitbod/ | https://fitbod.me/blog/how-fitbods-ai-knows-exactly-when-you-should-lift-heavier-and-when-to-recover/

### Fitbod: selection + capability recommender [moderate]
Two engines: Exercise Selector (recovery %, goal/experience, feedback history incl. swaps/favorites, split compatibility, equipment) and Capability Recommender (sets/reps/weight). Strength goal: 1-6 reps @ ~85-100% 1RM, 3-5 min rest. Hypertrophy: mainly 6-12 reps (+ some 1-5 and 15-25), 10-20 sets/muscle/week. 1RM via Epley; new exercises seeded from population data; periodic 'Max Effort Days' (AMRAP-to-failure calibration of 1-2 exercises). Preference: 'more often' ~40-60% more appearances, 'less often' ~20-40% fewer, 'exclude' removes (beta 2022). Claims users increasing weekly volume ~10-15% gained strength fastest (observational, marketing). Lowers recommendations after breaks; tells users to log actual numbers to recalibrate after layoff.
SRC: https://fitbod.me/blog/fitbod-algorithm | https://fitbod.me/blog/recommend-more-less-exclude/ | https://fitbod.me/blog/how-fitbods-ai-knows-exactly-when-you-should-lift-heavier-and-when-to-recover/

### Fitbod: user complaints (what to avoid) [weak]
(1) Unloadable weights (e.g., barbell suggestions 17.5/22 lb below empty bar). (2) Cold-start weights for unfamiliar exercises badly off (30 lb kettlebell halos 3x11 too hard; 5 lb DB rows after user had done 15 lb) -> no cross-exercise transfer. (3) After user overrides, app reportedly keeps its own original recommendation instead of learning. (4) Many experienced users ignore recommendations, use as logger. (5) High exercise rotation hampers progressive overload tracking. Design implications: equipment-aware snapping, transfer priors + calibration set, immediate learning from overrides, exercise stability per mesocycle.
SRC: https://talk.macpowerusers.com/t/gym-iphone-app-recommendation/32667 | https://redlib.belloworld.it/r/xxfitness/comments/1fvy3gt/fitbod_app (search snippet)

### RP Hypertrophy app / RP set progression algorithm [moderate]
Feedback: pump, soreness (at next exposure), workload/difficulty, joint pain. Published weekly set algorithm: Soreness 1=no soreness, 2=healed well before next session, 3=healed just in time, 4=still sore. Performance 1=exceeded targets easily, 2=hit targets, 3=struggled, 4=couldn't match previous week. Rules: both 1 -> +2-3 sets; 2s or mix 1-2 -> +1 set; any 3 (or soreness 3-4) -> maintain; performance 4 -> recovery session/deload. Meso: start at MEV, +~1 set/muscle/week, 4-6 wk accumulation + 1 wk deload ~50% volume; RIR typically 3->2->1->0. Example ramp MEV12->14->16->18->20 then deload 6. MV ~6 sets/muscle/week. Weight ~+2-2.5%/session-week, if next loadable increment too big app adds a rep instead. Reverse-engineering users: logic is simple, joint-pain rating does not change calculation. Train each muscle >=2x/wk. Complaints: simplistic; volume ramps hard; soreness-driven logic over-adds sets for experienced lifters who rarely get sore.
SRC: https://rpstrength.com/blogs/articles/training-volume-landmarks-muscle-growth | https://rpstrength.com/training-volume-landmarks-muscle-growth/ | https://rpstrength.com/blogs/articles/in-defense-of-set-increases-within-the-hypertrophy-mesocycle | https://help.rpstrength.com/hc/en-us/articles/32600173777815-Hypertrophy-App-Progressions (403; search snippet) | https://arvo.guru/resources/methods/rp-training | https://www.boostcamp.app/vs/rp-hypertrophy

### RP illustrative volume landmarks (sets/week, 4-wk ramp) [expert-opinion]
Wk1(MEV)/Wk2/Wk3(MRV)/Deload: Back 10-12/12-14/16-20/8-10; Chest 8-10/10-12/14-18/6-8; Quads 8-10/10-12/14-18/6-8; Hamstrings 6-8/8-10/12-16/4-6; Side delts 8-10/12-14/18-22/6-8; Biceps 8-10/10-12/14-18/6-8; Triceps 6-8/8-10/12-14/4-6; Calves 8-10/10-12/14-16/6-8. Explicitly 'illustrative, calibrate per person'. Sets counted only where muscle is prime mover (RP) vs fractional 0.5 (Pelland 2026).
SRC: https://coachway.io/articles/dr-mike-israetel-how-many-sets-for-coaches/ | https://rpstrength.com/training-volume-landmarks-muscle-growth/

### Alpha Progression [moderate]
Per working set recommends weight + reps (+ optional RIR, half-values allowed e.g. 1.5) from previous performance, current-workout progress and the gym's actual available weights. Double progression: prefers extra reps when next weight step is large (example 7.5->10 kg DB = +33%). Deload weeks suppress progression. RIR optional; progression works without it. Constants not published (deliberately).
SRC: https://alphaprogression.com/en/blog/alpha-progression-guide | https://alphaprogression.com/en/blog/best-workout-progression-system | https://alphaprogression.com/llms.txt

### Gravl [weak]
e1RM from logged history -> prescribe next session. Rep ladder double progression: 8-7-6 -> 8-8-6 -> 8-8-7 -> 8-8-8 then weight +1 equipment increment, ladder resets; each step ~1-2% more work. Snaps loads to plates/DB/stack and adds/removes a rep to keep difficulty constant. Working max ~7% lower for second exercise on same muscle in a session (fatigue discount). Easy/Ideal/Hard set rating; Easy -> bigger recalibration; e1RM increase capped ~10% per session. Tracks muscle recovery.
SRC: https://www.sensai.fit/blog/gravl-app-review-2026 | https://alphaprogression.com/en/blog/alpha-progression-vs-gravl

### Hevy Trainer / Boostcamp / Caliber / JuggernautAI [weak]
Hevy Trainer: weight increases only when top of rep range hit on all prescribed sets; nudges if stalled; starting weights only if exercise logged before; priority muscles get slightly more volume. Boostcamp: executes published templates; AMRAP last set drives auto-progression between cycles (GZCL 1:2:3 T1:T2:T3 rep ratio). Caliber: Strength Score relative to age/sex; 'Strength Balance' across muscle groups (formula undisclosed). JuggernautAI: adapts set-to-set..block-to-block from feedback; mechanics undisclosed. Takeaway: undisclosed = not borrowable; Hevy rule = simple floor for double progression.
SRC: https://www.hevyapp.com/announcing-hevy-trainer/ | https://help.hevyapp.com/hc/en-us/articles/38385724273047 (403; snippet) | https://www.boostcamp.app/methodology/gzcl | https://www.juggernautai.app/

### Liftosaur / Liftoscript (model for challenge engine) [engineering-fact]
Built-ins: lp(increment, increaseAttempts=1, current, decrement, decreaseAttempts, current) — success = all prescribed sets x reps done; dp(increment, minReps, maxReps) — climb reps then reset + add weight; sum(repsThreshold, increment) — add weight when total reps across sets > threshold. Custom 'progress' script runs after workout; 'update' script runs before first set (setIndex 0) and after every completed set, can change weights/reps/RPE/timers/numberOfSets of FUTURE sets only. Persistent state vars (custom(attempt: 0)), user-prompted vars (shouldBumpWeight+: asks after last set), set variations switched by setVariationIndex (GZCLP 5x3/6x2/10x1), week/day context, AMRAP '+', RPE '@8' load via RPE table, logged RPE '@8+', timed sets 'setTimer|restTimer' (e.g. plank 60s|30s). Example deload rule: 3 failures -> -15 lb.
SRC: https://www.liftosaur.com/doc/liftoscript | https://www.liftosaur.com/programs/gzclp

### Classic program auto-rules (Stronglifts, GZCLP, 5/3/1, Juggernaut) [expert-opinion]
Stronglifts: fail all-sets 3 sessions in row on an exercise -> that exercise only -10% (e.g., 200->180 lb), then +5 lb/2.5 kg per workout again; configurable. GZCLP T1: 5x3(+) -> fail -> 6x2 same weight -> fail -> 10x1 -> fail -> new 5RM, restart at 85% (or -10%); +5 lb upper/+10 lb lower per success. T2: 3x10 -> 3x8 -> 3x6; fail stage 3 -> last stage-1 weight +15 lb. T3: 3x15 with last AMRAP; >=25 reps -> +5 lb. 5/3/1: TM=90% 1RM; wk1 65/75/85%x5+, wk2 70/80/90%x3+, wk3 75/85/95%x5/3/1+, wk4 40/50/60%x5; +2.5 kg upper/+5 kg lower per cycle; missed prescribed reps -> TM -10-15%. Juggernaut 2.0: TM 90%; waves 10s/8s/5s/3s each accumulation/intensification/realization(AMRAP)/deload; new TM = old TM + (AMRAP reps - wave standard) x 2.5 lb (upper) or 5 lb (lower), extra reps capped 10, no increase if standard not exceeded.
SRC: https://support.stronglifts.com/article/22-increments | https://www.liftosaur.com/programs/gzclp | https://arvo.guru/resources/methods/wendler-531 | https://www.liftosaur.com/programs/juggernaut-method | https://arvo.guru/resources/methods/juggernaut-method

### RTS / Tuchscherer RPE chart, e1RM [expert-opinion]
%1RM by reps x RPE: e.g., 1@10=100%, 1@8=92.2%, 3@10=92.2%, 3@9=89.2%, 5@10=86.3%, 5@8=81.1%, 8@10=78.6%, 8@8=73.9%, 10@10=73.9%, 10@8=68.0%; each RPE step or extra rep ~2-3%. e1RM = load / chart%. Example 315x3@9 -> 315/0.892 = 353 lb. RTS back-off via 'fatigue percent' (load drop / repeats until RPE target reached) — practitioner method, ~expert.
SRC: https://fitnessvolt.com/rpe-training/guides/tuchscherer-chart-explained | https://elitefts.com/blogs/training/rate-of-perceived-exertion-rpe

### Dr. Muscle [weak]
DUP: heavy (4-6) / moderate (8-12) / high (12-15+) rotation; adjusts weight after each session from target-rep hit + reported RIR; sets up to ~6 work sets per exercise. Auto-deload when e1RM declines: sets -50%, load -10%, then rebuild from next workout (cites Pritchard 2015: reduce volume 30-70% for 1-4 wk; Rippetoe: -10%). Example: 1%/workout x 3/wk recovers old 1RM after ~10 workouts.
SRC: https://dr-muscle.com/deload/ | https://dr-muscle.com/progressive-overload-app-gain-muscle-strength/ | https://aitoolsbakery.com/blog/dr-muscle-review/

### APRE (Autoregulatory Progressive Resistance Exercise) [moderate]
Mann et al. 2010 (JSCR, D1 football, n=23, 6 wk): APRE > linear periodization for 1RM bench, e1RM squat, reps at 225 lb. Protocol (6RM version): set1 10 reps @50% 6RM, set2 6 @75%, set3 max reps @100%, set4 load adjusted from set3 reps: 0-2 -> -5-10 lb; 3-4 -> -0-5 lb; 5-7 -> same; 8-12 -> +5-10 lb; 13+ -> +10-15 lb; set4 reps set next week's load by same table (table from Mann/Siff; recalled, verify before shipping). Huang 2025 network MA: APRE ranked highest for bench 1RM (SUCRA 97.1%). Deterministic rep-out -> load table = good model for calibration sets.
SRC: Mann JB et al. 2010 JSCR doi:10.1519/jsc.0b013e3181def4a6 | Huang et al. 2025 J Exerc Sci Fit doi:10.1016/j.jesf.2025.07.006

### Autoregulation vs fixed loading [moderate]
Hickmott et al. 2022 MA (15 studies): no significant difference autoregulated vs standardized for 1RM (SMD 0.21); velocity-loss <=25% better strength (SMD 0.23) but less hypertrophy. Larsen 2021 SR (14 studies, n=356): small-large strength ES for RPE/velocity autoregulation. Graham & Cleather 2021: RIR autoregulation > fixed loading over 12 wk. Conclusion: autoregulation at least as good, safer for daily variance; do not over-engineer.
SRC: Hickmott 2022 Sports Med Open doi:10.1186/s40798-021-00404-9 | Larsen 2021 PeerJ doi:10.7717/peerj.10663 | Graham & Cleather 2021 JSCR doi:10.1519/JSC.0000000000003164

### Rep-%1RM curve is individual and exercise-specific [strong]
Nuzzo et al. 2024 meta-regression (952 tests, 7,289 people): reps at given %1RM have large SD; leg press yields more reps than bench across loads; sex/age/training status minor moderators. STMr (Jovanovic) models: Epley %1RM = 1/(1+k*reps) k=0.0333; modified Epley %1RM = 1/(1+kmod*(reps-1)) kmod=0.0353; linear/Brzycki %1RM = (klin-(reps-1))/klin klin=33; individual k estimated by nonlinear regression on reps-to-failure (reps+RIR). User data check: 3RM +32.5 kg (system 103.2 kg) and 18 BW reps (70.7 kg) -> two-point modified-Epley fit k = 32.5/995.5 = 0.0327, e1RM_system = 103.2*(1+2*0.0327) = 109.9 kg (+39.2 kg), lower than artifact's +42.8 (which used Epley 3RMx1.10). => 1RM estimates are model-dependent; challenge thresholds must be judged on measured test-day values, not estimates.
SRC: Nuzzo JL et al. 2024 Sports Med doi:10.1007/s40279-023-01937-7 | https://cran.r-project.org/web/packages/STMr/refman/STMr.html

### RIR accuracy [strong]
Halperin et al. 2022 MA (12 studies, n=414): people underestimate reps-to-failure by ~1 rep; accuracy better closer to failure and with heavier loads (fewer reps); experience not a meaningful moderator. Zourdos 2016 RIR-based RPE scale is the standard. Implication: trust RIR more on last set/low-rep sets; apply bias correction (learned per user from AMRAP/calibration sets).
SRC: Halperin I et al. 2022 Sports Med doi:10.1007/s40279-021-01559-x | Zourdos MC et al. 2016 JSCR 30(1):267-275

### Proximity to failure, fatigue, recovery [strong]
Robinson et al. 2024 meta-regression: hypertrophy increases as RIR decreases (closer to failure); strength gains not related to RIR. Refalo 2023 MA: failure vs non-failure trivial hypertrophy advantage (ES 0.19). Refalo 2023 SMO: 4-min post velocity loss -25% (failure) vs -13% (1 RIR) vs -8% (3 RIR) -> linear fatigue with proximity. Morán-Navarro 2017: 3x10 to failure slowed recovery up to 24-48 h vs same volume non-failure. Use: RIR multiplier in fatigue model; strength work at 1-3 RIR, hypertrophy isolation may approach 0-1 RIR late meso.
SRC: Robinson ZP et al. 2024 Sports Med doi:10.1007/s40279-024-02069-2 | Refalo MC et al. 2023 Sports Med doi:10.1007/s40279-022-01784-y | Refalo MC et al. 2023 Sports Med Open doi:10.1186/s40798-023-00554-y | Morán-Navarro R et al. 2017 Eur J Appl Physiol doi:10.1007/s00421-017-3725-7

### Volume dose-response, fractional sets, volume increments [strong]
Pelland et al. 2026 meta-regression (67 studies, n=2,058): volume positive for hypertrophy and strength with diminishing returns (stronger diminishing for strength); fractional counting (indirect set = 0.5) predicted best; frequency negligible for hypertrophy, reliable (diminishing) benefit for strength. Camargo et al. 2026 (within-subject, 8 wk, trained): +120% vs +20% above habitual volume -> no hypertrophy difference -> modest volume increments suffice.
SRC: Pelland JC et al. 2026 Sports Med 56(2):481-505 doi:10.1007/s40279-025-02344-w | https://biolayne.com/reps/issue-48/smashing-the-volume-button-might-not-improve-hypertrophy/

### Deloads [moderate]
Bell et al. 2023 Delphi: deload = period of reduced training stress (effort, volume, duration or frequency) to reduce fatigue; Bell 2024 practical review: commonly pre-planned every 4-8 weeks, or autoregulated reactively. Coleman et al. 2023 RCT (n=39 trained, 9 wk): 1-wk full cessation at midpoint -> similar hypertrophy but less lower-body strength gain than continuous -> deload should reduce, not stop. RP/OAP artifact practice: halve sets, keep load.
SRC: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC10511399/ | https://shura.shu.ac.uk/35313/ | https://sportrxiv.org/index.php/server/preprint/view/302

### Detraining and maintenance [strong]
Bosquet et al. 2013 MA (103 studies): maximal strength essentially unaffected (g<0.2) up to 28 days cessation; losses accelerate after; older adults lose ~2x. Long layoffs: SBS review — e.g., Staron 1991 only ~13% strength loss after 30 wk off; return plan weeks 1-2 at 30-60% of old maxes, ramp over ~2-3 months. Spiering et al. 2021: strength + size maintained up to 32 wk with 1 session/wk and 1 set/exercise if relative intensity kept (older: 2 sessions, 2-3 sets). Intensity is the key maintenance variable.
SRC: Bosquet L et al. 2013 Scand J Med Sci Sports (meta-analysis) | https://www.strongerbyscience.com/detraining/ | Spiering BA et al. 2021 JSCR doi:10.1519/jsc.0000000000003964

### Distilled borrow list [expert-opinion]
(a) Session type: Fitbod per-muscle recovery + RP weekly volume targets -> need x readiness scoring; queue/rotation tie-break (GZCLP rotating days) because user's days shift weekly. (b) Exercise selection: greedy set-cover with Fitbod-like preference multipliers (more often ~x1.5, less often ~x0.7), RP-style exercise lock per mesocycle, Gravl fatigue discount for 2nd exercise same muscle. (c) Load: RTS e1RM with individual k (STMr), equipment snapping (Alpha/Gravl), Hevy top-of-range rule + Gravl rep ladder, Stronglifts/Dr Muscle failure -> -7.5-10%, Liftosaur-style per-set update, Gravl +-10%/session cap. (d) Volume: RP soreness x performance table automated with logged data; +20% cap (Camargo); deload = 50% sets same load (RP/OAP). (e) Feedback: soreness at next exposure (RP), joint pain per exercise (but make it DO something, unlike RP), session RPE; performance computed not asked. (f) Missed/detraining: no make-up, hold loads <=2 wk (OAP rule, Bosquet), graded reductions after.
SRC: synthesis of above

## ALGORITHMS
### A1 MuscleFatigueModel (Fitbod-style recovery %)
PURPOSE: Per-muscle fatigue/recovery state used by session-type selection, weekly planner, rest-day advice and heat-map UI. Includes challenge sets and runs.
IN: All logged sets (exercise_id, reps, load, RIR, timestamp, reason), exercise->muscle contribution matrix c(e,m) in {1.0 primary, 0.5 secondary, 0 else}, eccentric flag per exercise, external activities (runs from calendar/Health Connect: km, timestamp), per-user muscle params tau_m, cap_m.
OUT: F_m(t) fatigue units, recovery_m(t) in [0,100]%, readiness class {ready >=80, ok 60-80, fatigued <60}, systemic fatigue index.
PSEUDO:
stress(set,m) = c(e,m) * fRIR(RIR) * fEcc(e)
  fRIR: {0:1.30, 1:1.15, 2:1.00, 3:0.85, >=4:0.70}; RIR missing -> use prescribed RIR
  fEcc = 1.2 if exercise eccentric-emphasised (negatives, RDL, lunges, Nordic) else 1.0
run_stress(km,m) = km * runCoef[m]  (quads 0.25, calves 0.30, hamstrings 0.15, glutes 0.15 set-equiv per km)
F_m(t) = SUM_i stress_i,m * exp(-(t - t_i)/tau_m)   over last 14 days
recovery_m(t) = 100 * clamp(1 - F_m(t)/cap_m, 0, 1)
systemic(t) = mean over large muscles {quads,hams,glutes,chest,lats,upper_back} of F_m/cap_m
Adaptive tau (weekly, deterministic): at each soreness answer for m:
  if answer in {still sore} and model recovery_m >= 80 -> tau_m *= 1.10
  if answer in {never sore, healed a while ago} and model recovery_m < 60 -> tau_m *= 0.90
  clamp tau_m to [0.6, 1.6] x default; max one update per muscle per 7 days.
CONST: tau_m default hours: quads 40, hamstrings 44, glutes 40, lower_back 44, chest 36, lats 36, upper_back 36, traps 30, front_delts 30, side_delts 24, rear_delts 24, biceps 30, triceps 30, forearms_grip 24, calves 24, abs 20. cap_m: 8.5 set-units large muscles (quads, hams, glutes, chest, lats, upper_back), 7.0 medium (delts, biceps, triceps, traps, lower_back), 6.0 small (forearms, calves, abs). Calibration: 6 hard sets (RIR2, primary) on large muscle -> 29% right after, ~79% at 48 h, ~88% at 72 h, ~100% at 7 d (matches Fitbod 48-72 h and 6-7 d full recovery). fRIR from velocity-loss gradient -8%/-13%/-25% at 3/1/0 RIR (Refalo 2023) and +24-48 h recovery for failure (Morán-Navarro 2017). Run coefficients: guesses (weak), tune.
LOG: Per session start: snapshot of F_m and recovery_m for all muscles, soreness answers per muscle at next exposure, performance score per muscle (A9), tau_m changes with timestamp and trigger. Tuning target: correlation between model recovery_m at session start and same-session performance delta (e1RM vs previous exposure); soreness-vs-model confusion matrix.

### A2 TodaySessionTypeSelector
PURPOSE: When user wants to go today: recommend push / pull / legs / combination / challenge session / rest, with explanation.
IN: Templates T (PUSH{chest,front_delts,side_delts,triceps}, PULL{lats,upper_back,rear_delts,biceps,forearms}, LEGS{quads,hams,glutes,calves,abs}, UPPER, LOWER, FULL, PUSH+LEGS, PULL+LEGS, CHALLENGE sessions, CHALLENGE+ADDON), A1 recovery, goal per muscle (improve/maintain/none), weekly targets from A9, rolling 7-day fractional sets (incl. challenge), sets already planned next 6 days (calendar + challenge schedule), time budget, last date each template done.
OUT: Ranked top-3 templates with score and per-muscle contribution breakdown; or REST advice with reason.
PSEUDO:
1. If active challenge has a session due today (challenge schedule rules satisfied, e.g., day allowed, min gap, heavy-unilateral count <3/wk) -> rank CHALLENGE first; optionally CHALLENGE+ADDON where addon muscles have c<0.5 overlap with challenge primary muscles and duration fits budget.
2. For each muscle m: wgoal = {improve:1.0, maintain:0.6, none:0}; target_m = weekly target (A9); done7_m = SUM fractional sets last 7 d; planned_m = sets in fixed sessions next 6 d; need_m = max(0, target_m - done7_m - planned_m)/max(target_m,1).
3. readiness_m = recovery_m(now); if a fixed session within next 48 h has m primary with heavy load (challenge A/B, or any RIR<=1 compound) -> readiness_m *= 0.5 (protect).
4. g(r) = 0 if r<60; (r-60)/20 if 60<=r<80; 1 if r>=80.
5. score(T) = SUM_{m in T} wgoal_m * need_m * g(readiness_m) / |T|^0.3 ; drop T if est_duration(T) > time_budget (est = SUM sets*(45 s + rest(A14))).
6. REST if max score < 0.15 OR systemic > 0.60 OR >=3 consecutive training days (incl. challenge & hard runs) OR challenge rule forbids training today.
7. Ties (|delta| < 0.02): least-recently-done template first, then fixed order [LEGS, PUSH, PULL, UPPER, LOWER, FULL, PUSH+LEGS, PULL+LEGS].
CONST: wgoal 1.0/0.6/0; readiness thresholds 60/80% (Fitbod 'fresh' 80-100%, <50% fatigued); protect window 48 h, factor 0.5; size penalty exponent 0.3; rest threshold 0.15; systemic 0.60; max 3 consecutive days; set time 45 s. All tunable.
LOG: Every request: timestamp, all template scores and per-muscle terms, chosen template by app vs actually trained template (acceptance), time budget, rest advice given/ignored, subsequent performance score per muscle. Tune thresholds by acceptance rate and by performance outcomes after 'ok' (60-80%) vs 'ready' sessions.

### A3 WeeklySessionPlanner (recommended sessions this week)
PURPOSE: List recommended gym sessions for the current ISO week, merging fixed calendar/challenge sessions with recommended general sessions.
IN: Calendar events this week (mapped to session types via user rules: keyword/regex -> template or challenge session), user availability (blocked evenings e.g. chess Monday, gym opening hours weekend 09:00-16:00), target sessions/week, A1 state, A2 scoring, challenge schedule.
OUT: Day-by-day list: fixed (calendar/challenge) sessions, recommended sessions (type + est. duration), rest days, warnings (e.g., conflict with challenge protection).
PSEUDO:
state = A1 snapshot now; plan = []
for day d from today..Sunday:
  if fixed session on d: plan += fixed; simulate its sets into state at its start time; continue
  if d unavailable or count(plan sessions) >= target_freq: plan += REST; advance decay; continue
  ranked = A2(state at typical start time of d, lookahead includes remaining fixed sessions)
  if ranked is REST or top.score < 0.35: plan += REST
  else plan += top; simulate top's planned sets (from A4/A9) into state
return plan  # re-run on every log/calendar change; deterministic given inputs
CONST: theta_session 0.35; typical start time = user default (e.g., 19:45 weekdays, 11:00 weekend); target_freq default = user setting (e.g., 4-5 incl. challenge).
LOG: Weekly plan snapshots (version, inputs hash), planned vs performed sessions, missed sessions and reasons, calendar mapping hits/misses.

### A4 ExerciseSelector (preference-weighted set cover + only-option fallback)
PURPOSE: Choose exercises and set counts for a session template; prefer liked exercises but include disliked one when it is the only way to train a needed muscle; keep exercises stable within mesocycle.
IN: Template muscles and session set targets per muscle (from A9 weekly target / remaining exposures), exercise catalog with c(e,m), equipment availability, user rating 1-5, favorite flag, hard-exclude flag (injury/can't), active pain flags, mesocycle slot locks, challenge constraints, time budget.
OUT: Ordered exercise list with sets per exercise and flags ('locked', 'needed: only option for m', 'substitute').
PSEUDO:
need_m = session_sets_m for m in template (session_sets_m = clamp(round(remaining_weekly_m / remaining_exposures_m), 2, 10))
C = catalog filtered: equipment ok AND not hard-excluded AND no pain flag (moderate+ in last 14 d)
pref(e) = {1:0.35, 2:0.65, 3:1.0, 4:1.3, 5:1.6}[rating or 3] * (1.15 if favorite)
cont(e) = 1.5 if e locked in current meso for this template slot else 1.0
score(e) = [SUM_m c(e,m)*wgoal_m*min(need_m, 4)  - 0.5*SUM_{m: wgoal_m=0} c(e,m)] * pref(e) * cont(e)
loop while any need_m > 0.5 and len(list) < max_ex(time_budget):
  e* = argmax score (tie -> lower exercise_id)
  n = min(4, ceil(need_{primary(e*)} / c(e*,primary)))
  add (e*, n); need_m -= c(e*,m)*n for all m; recompute scores
Fallback: for each m with need_m >= 1 and wgoal_m>0: cand = exercises in catalog (incl. rating<=2, excluding hard-excluded/pain) with c(e,m)=1.0; if all cand have rating<=2 -> add best-coverage cand with n=min(3,ceil(need_m)), flag 'needed: only exercise for m'.
Second exercise on same primary muscle: predicted load *0.93 (Gravl fatigue discount) unless e1RM data already from that position.
Order: challenge/heavy unilateral first, then compounds by systemic demand desc, then isolation; tie by need desc, id.
Meso lock: chosen (template, slot)->exercise stored at meso start; mid-meso changes only via user swap / pain / equipment busy (substitute = best-scoring e with same primary muscle and movement pattern, not counted as failure).
Meso rotation: at meso end swap slot if e1RM slope < +0.25%/wk over the meso OR rating<=2 and alternative with rating>=3 exists.
CONST: Rating multipliers 0.35/0.65/1.0/1.3/1.6 (approx. Fitbod more-often +40-60%, less-often -20-40%); favorite x1.15; continuity x1.5; per-exercise max 4 sets; per-muscle per-session 2-10 sets (diminishing returns, Pelland 2026; RP ~8/session practical); penalty 0.5 for hitting 'none' muscles; fatigue discount 0.93; stall slope 0.25%/wk.
LOG: Candidate list with scores, chosen list, fallback triggers (which muscle forced disliked exercise), user swaps (from->to, reason), skip rates per exercise, ratings over time, e1RM slope per exercise per meso.

### A5 e1RM estimator with individual rep curve
PURPOSE: Robust per-exercise strength estimate on SYSTEM load, with personal rep-curve constant k (user's curve is flatter than tables).
IN: Sets: external load, bodyweight (latest weigh-in), assistance (assisted machine, lbs->kg x0.4536), reps, RIR, reason, timestamp, set index; exercise bw_frac; k priors per exercise class.
OUT: e1RM_e (system kg), external-load equivalent, k_user_class, confidence (n_eff).
PSEUDO:
L_sys = external + bw_frac(e)*BW - assistance
RTF = reps + RIR (+ RIR_bias_user, see below)
e1RM_i = L_sys * (1 + k*(RTF - 1))            # modified Epley, %1RM = 1/(1+k(RTF-1))
weight w_i = 0.5^(age_days/21) * setPos (1.0 first working set, 0.7 later) * reasonW (normal 1.0, too easy 1.0, too hard 1.0, feeling good 0.8, feeling bad 0.5, no time/occupied 1.0 if completed set) * rirConf (RIR 0-3: 1.0, 4: 0.6, >=5: 0.3, missing: 0.5) * (0.25 if RTF > 20)
e1RM_e = weighted median of e1RM_i over last 12 eligible sets within 42 days
k fit (weekly): for each class (upper-compound, lower-compound, isolation, bodyweight-hang/support), pairs of sets same exercise within 14 d with |RTF_a - RTF_b| >= 4; grid search k in [0.015, 0.060] step 0.0005 minimizing SUM w*(ln L_a + ln(1+k(RTF_a-1)) - ln L_b - ln(1+k(RTF_b-1)))^2; k_post = (10*k_prior + n_pairs*k_fit)/(10 + n_pairs)
RIR bias: from calibration/AMRAP sets where predicted RTF known: bias = median(actual RTF - (reps+reported RIR)) over last 10 such sets, clamp [-1, +2]; default +0 (Halperin: underestimation ~1 rep; learn rather than assume).
Example user: 103.2 kg x3 RTF3 and 70.7 kg x18 RTF18 -> k = 0.0327, e1RM 109.9 kg system.
CONST: k_prior: 0.0353 (STMr modified Epley) for upper-compound & isolation; 0.030 lower-compound/leg press (Nuzzo 2024: more reps in leg press); 0.0353 bodyweight-hang. bw_frac: pull-up/chin-up/dip 1.0 (consistent with user's BW+added convention), push-up 0.64, inverted row 0.5 (estimate), assisted machine: 1.0 - assistance. Recency half-life 21 d; window 42 d / 12 sets; shrinkage n0 = 10 pairs; k bounds 0.015-0.060.
LOG: Every set: predicted RTF at prescribed load (from e1RM, k) vs actual reps+RIR (prediction error), e1RM before/after, k per class history, bias history. Tuning metric: mean absolute RTF prediction error per exercise class (target <1.5 reps).

### A6 Prescription (sets/reps/load + equipment snapping + user preference guardrails)
PURPOSE: Turn e1RM, rep-range preference, target RIR and set count into concrete loadable prescriptions; warn when overriding user preferences.
IN: e1RM_e, k, user rep range [r_lo, r_hi] and sets preference (global or per class), target RIR (A9 week), sets n (A4), progression state (rep ladder), equipment increment model (barbell 20 kg + pairs of 1.25 kg -> 2.5 kg steps; DB list e.g. 2,4,...,10,12.5...; stack step 5 kg (+2.5 add-on); assisted machine 5 lb steps; dip belt plates), bodyweight-only flag, goal of muscle.
OUT: Per set: load (or assistance), reps target, RIR target, optional rep-range extension, warnings.
PSEUDO:
r_t = ladder target for set j (default r_lo for all sets at new load)
L_ideal = e1RM/(1 + k*(r_t + RIR_t - 1)) - bw_frac*BW (+assist logic)
L = nearest loadable to L_ideal (tie -> lower)
r_pred = floor((e1RM/L_sys - 1)/k + 1 - RIR_t)
if r_pred in [r_lo, r_hi+2]: reps = clamp(r_pred, r_lo, r_hi) else pick adjacent loadable giving r_pred in range
set decay: target reps set j = max(r_lo, r_pred - d*(j-1)), d = 0.5 if RIR_t>=2 else 1.0 (learned per user later)
Bodyweight-only: reps = clamp(floor(RTF_pred - RIR_t),1,cap); if reps >= r_hi+5 and weighted variant available (dip belt) -> suggest weighted variant at load giving r_hi; if reps < r_lo and assisted variant available -> assisted.
Guardrails (warnings, apply only if 'auto-adjust' on): improve-goal muscle weekly sets = sets_pref*exposures < MEV_m*0.5 -> warn 'below minimum effective volume'; reps > 30 or < 3 for hypertrophy -> clamp; > 10 sets/muscle/session -> warn split; heavy unilateral challenge exercises never below RIR 1.
CONST: Rep tolerance r_hi+2; default d 0.5/1.0 reps per set; bodyweight cap = r_hi+5 before variant switch; MEV defaults from RP illustrative table (back 10, chest 8, quads 8, hams 6, side delts 8, biceps 8, triceps 6, calves 8); min RIR for heavy unilateral 1.
LOG: Prescribed vs performed per set, snapping delta (L - L_ideal)/L_ideal, warnings shown/accepted/dismissed, variant switches.

### A7 BetweenSessionProgression (double progression + RIR guard + failure counter)
PURPOSE: Decide next exposure's load/reps per exercise from performance; deterministic, bounded.
IN: Last exposure sets (reps, RIR, load, reasons), prescription, rep range, step size, fail_count state, updated e1RM (A5).
OUT: Next exposure load, rep-ladder targets, fail_count, event log (increase/hold/ladder/reset).
PSEUDO:
valid = sets not tagged no_time/occupied/pain
hard_ok = all valid sets RIR >= RIR_t - 0.5
if all valid reps >= r_hi and hard_ok:
   step = next_loadable(L) - L; jump = step/L
   if jump <= maxJump(class): L_next = L + step; ladder = [r_lo]*n
   elif r_hi_eff < r_hi + 4: r_hi_eff += 2 (stay at L)          # Alpha Progression: reps before big jump
   else: L_next = L + step; ladder = [r_lo]*n
elif all valid reps >= r_lo: ladder: lowest set target += 1 (Gravl 8-7-6 -> 8-8-6)
elif failure (any valid set reps < r_lo AND RIR <= 0.5 AND reason not in {feeling bad, grip-limited}):
   fail_count += 1; if fail_count >= 2: L_next = snap(L*0.925); fail_count = 0; ladder reset
fast lane: if first set RTF >= target RTF + 3 (too easy) -> L_next = A6(e1RM_new) but clamp L_next <= L*1.10
always clamp L_next to [L*0.90, L*1.10] (Gravl cap ~10%)
grip-limited (e.g., 'hand slipped'): hold load, no failure count.
CONST: maxJump: upper compound 5%, lower compound 7.5%, isolation/DB 10%, bodyweight-system 5%; rep extension up to r_hi+4 in +2 steps; fail threshold 2 consecutive exposures (Stronglifts uses 3); reset -7.5% (between Stronglifts/Dr Muscle -10% and 5/3/1 TM -10-15% vs. microload); session change cap +-10%.
LOG: Per exposure: decision branch taken, jump %, fail_count, resets, subsequent exposure success. Tune maxJump and reset % by success rate of first exposure after increase (target 70-85%).

### A8 InSessionAutoregulation (Liftosaur 'update' hook)
PURPOSE: Update remaining sets in current session when performance deviates (too easy/hard), including after manual overrides.
IN: Completed set j (load, reps, RIR, reason), plan for sets j+1..n, e1RM_prior, k, d.
OUT: Revised loads/reps for remaining sets; optional set count change; log.
PSEUDO:
RTF_exp_j = reps_target_j + RIR_t ; RTF_act = reps_j + RIR_j + d*(j-1)
delta = RTF_act - RTF_exp_j
if |delta| >= 2 or reason in {too easy, too hard}:
   e1RM_today = 0.5*e1RM_prior + 0.5*L_sys*(1 + k*(RTF_act - 1))
   re-run A6 for remaining sets with e1RM_today; clamp new loads within +-10% of original plan
elif |delta| == 1: keep load; remaining reps targets += sign(delta)*1 (within [r_lo, r_hi+2])
if RIR_j == 0 and reps_j < r_lo - 1: next set load = one step lower (RTS load drop)
if reason == feeling bad: remaining sets -= 1 (min 1), RIR_t += 1 for remaining
if reason == feeling good and delta >= 1: allow +1 set only if muscle weekly target not yet met
user manual override of load/reps: treat override values as new plan for remaining sets unless user taps 'only this set'.
CONST: Trigger |delta|>=2 reps; blend 0.5/0.5; cap +-10%; load drop 1 step when >=2 reps short at RIR0.
LOG: Each update event: set index, delta, old/new plan, user acceptance of revised plan, final session outcome.

### A9 VolumeProgression + Mesocycle + Deload (RP table, automated)
PURPOSE: Weekly sets per muscle by goal; mesocycle RIR schedule; planned + reactive deloads; synced with challenge blocks.
IN: Goal per muscle, history of fractional weekly sets, soreness answers (1-4), computed performance scores, session RPE, joint pain flags, challenge block calendar.
OUT: Weekly set target per muscle, RIR target per week, deload flag and deload transforms.
PSEUDO:
Meso = 5 weeks (4 accumulation + 1 deload), aligned to active challenge block if any.
Start S_m (improve) = clamp(avg fractional sets last 14 d, MEV_m, MAV_lo_m); no history -> MEV_m
Maintain: S_m = max(MV_m, ceil(0.33*S_ref_m)), RIR_t <= 2, load kept (Spiering 2021). None: 0 direct.
Performance P per exposure: e1RM change vs previous exposure: >= +2% -> 1; -1..+2% -> 2; -3..-1% -> 3; < -3% -> 4 (exposures tagged feeling bad/no time excluded). P_m = round(mean).
Soreness S_m_score from next-exposure question (1 never sore, 2 healed early, 3 just in time, 4 still sore).
Weekly delta: if P_m==4 -> next week S_m *= 0.75 (recovery); elif soreness>=3 or P_m==3 -> 0; elif soreness==1 and P_m==1 -> +2; else -> +1.
Caps: delta <= max(1, floor(0.20*S_m)); S_m <= MRV_m; per-session <= 10 sets.
RIR schedule: wk1 3, wk2 2, wk3 2, wk4 1 (isolation may 0 if user allows), deload 4.
Planned deload wk5: sets = ceil(0.5*S_m), load unchanged, RIR >= 3.
Reactive deload (earliest wk3): any of (a) >=2 muscles P=4 same week, (b) >=2 key lifts e1RM < 0.97*meso peak on 2 consecutive exposures, (c) session RPE >= 9 three sessions in a row with no P=1, (d) same joint pain >= moderate 2 sessions in a row. Reactive deload: sets 0.5x, load -10%.
Next meso start: S0_next = round(0.5*(S_final_tolerated + S0_prev)), >= MEV_m.
CONST: Meso 4+1 (matches OAP blocks; Bell 2024: deload every 4-8 wk); RP table mapping; +20% cap (Camargo 2026); performance thresholds +2%/-1%/-3%; MV default 4-6 sets (RP ~6); MRV defaults back 22, chest 20, quads 18, hams 16, side delts 22, biceps 18, triceps 14, calves 16 (RP illustrative upper ranges); per-session cap 10.
LOG: Weekly per muscle: target, performed, soreness, P, delta applied, caps hit; deload triggers fired (which), outcomes 2 weeks after deload (e1RM rebound %). Tune by e1RM trend vs volume per muscle (dose-response per user).

### A10 OverrideReasonHandling
PURPOSE: Make override/skip reasons change data weighting and logic deterministically (fixes Fitbod 'ignores overrides').
IN: Per set/exercise override: proposed vs chosen sets/reps/load/skip, reason code, optional RIR.
OUT: Weights for A5, failure eligibility for A7, effects on A4/A8/A9, challenge prompts.
PSEUDO:
reason table -> (e1RM weight, counts as failure?, effect):
 too_easy -> (1.0, no, A8 re-estimate now)
 too_hard -> (1.0, yes if reps<r_lo, A8 re-estimate now)
 feeling_good -> (0.8, no, may add 1 set if weekly need)
 feeling_bad -> (0.5, no, -1 set remaining, RIR+1; excluded from P score)
 no_time -> (1.0 for done sets, no, unfinished sets = volume shortfall only)
 occupied/equipment_busy -> (n/a, no, A4 substitute same primary muscle/pattern; no pref change)
 pain_discomfort(joint) -> (0.5, no, exercise pain-flag 14 d, A9 trigger (d), challenge protocol hook)
 grip_limited / technique_breakdown -> (0.7, no, hold load)
 other(text) -> (0.8, no, none)
Skip exercise: same codes; 3 skips in 30 d with dislike-like reasons -> suggest rating <=2.
CONST: Weights as listed; pain flag 14 days (matches OAP elbow 2-week rule); 3 skips/30 d prompt.
LOG: Override rate per exercise/reason, prediction error on overridden vs non-overridden sets, reason distribution by time of day/weekday.

### A11 MissedSessions + Detraining
PURPOSE: Deterministic handling of missed planned sessions and layoffs per exercise/muscle.
IN: Days since last exposure per exercise (gap), missed fixed sessions, challenge rules.
OUT: Load/volume/RIR modifiers for re-entry, planner adjustments.
PSEUDO:
gap <= 10 d: no change
11-21 d: hold load (no progression this exposure), RIR_t + 1
22-35 d: load x0.95, sets x0.75, RIR_t + 1, first set = calibration (A12)
36-60 d: load x0.90, sets x0.60, RIR_t + 2, 2 re-entry exposures before A7 progression
> 60 d: load x0.80 (x0.60 if > 120 d), RIR 4, recalibrate via A12, volume ramps 60%->100% over 3 weeks
Missed week session: no make-up; queue keeps missed template at head of rotation; A3 never adds > +1 set/muscle/session to compensate.
Challenge-defined rules override (e.g., OAP: missed week -> same weights; test day postponed to first Sunday after normal pain-free session).
CONST: Breakpoints 10/21/35/60/120 days; factors 0.95/0.90/0.80/0.60 (Bosquet 2013: max strength ~unchanged <=28 d; SBS: return at 30-60% after months — we use milder since re-entry is RIR-calibrated).
LOG: Gap length, modifiers applied, first-exposure RTF prediction error after gap (calibrates breakpoints).

### A12 Calibration + ColdStart transfer
PURPOSE: Initial loads for new exercises and periodic e1RM re-estimation without max testing.
IN: Catalog transfer ratios between related exercises, reference e1RMs, user flags.
OUT: Starting load with safety factor; calibration set definitions; e1RM corrections.
PSEUDO:
New exercise e: if related ref with e1RM: e1RM_e = ratio(e,ref)*e1RM_ref*0.85; else population/bodyweight default *0.80; first exposure RIR_t + 2; first set target r_hi; A8 re-estimates after set 1.
Periodic: meso week 1, first working set of each locked compound = 'RIR-1 calibration' (stop at 1 RIR); isolation/machines: last set AMRAP allowed. Update e1RM from A5 with clamp +-10% (Gravl/APRE).
Training-max style (optional for main lifts): TM += (reps - standard)*inc, extra reps capped 10, inc = 1.25 kg upper / 2.5 kg lower (Juggernaut 2.5/5 lb).
CONST: Safety 0.85 (with ref) / 0.80 (none); RIR +2 first exposure; clamp 10%; Juggernaut increments metric-equivalent.
LOG: Transfer-ratio predicted vs observed e1RM after 3 exposures (refit ratios), calibration set results, RIR bias estimates.

### A13 ChallengeRuleEngine (data-driven, Liftosaur-style)
PURPOSE: Run challenges like the OAP program from a JSON definition (no hardcoding): phases, blocks, sessions, prescriptions (reps, holds, eccentrics, assisted), progression rules, prompts, safety protocols, test days.
IN: Challenge definition JSON (versioned), challenge state (phase, block week, state vars), logged sets incl. hold seconds / eccentric seconds / assistance, user prompt answers, pain flags, calendar.
OUT: Due sessions + prescriptions, post-session state/prescription updates, phase transitions, protocol activations, test-day scheduling; muscle contributions fed to A1/A9.
PSEUDO:
Definition: {phases:[{id, entry_criteria(expr), exit_criteria(expr)}], block:{weeks:5, deload_week:5, deload_transform:{sets:x0.5, load:x1.0, hold:x0.5}}, sessions:[{id:'A', constraints:{min_gap_h:48, weekday_pref:[...], counts_heavy_unilateral:true}, exercises:[{id, type:'reps|hold|eccentric|assisted', sets, reps|seconds, load|assist, per_side:bool}]}], rules:[{hook:'post_session'|'post_set'|'weekly'|'block_end', when:<expr>, do:[actions]}], prompts:[{id:'hand_slip', ask_after:'exercise:B_wpu', type:bool}], protocols:[{id:'elbow', trigger:<expr>, duration_days:14, transforms:[...], exit:{restore:'loads_from_blocks_ago:2'}}], tests:[{schedule:'deload_week.sunday', metrics:[bw,3RM,1RM,max_reps,hang_L/R,top_hold_L/R,min_assist]}], weekly_limits:{heavy_unilateral_max:3}}
Expr = JSONLogic-like over whitelisted vars (sets[], reps, rir, load, seconds, ecc_seconds, prompts.*, state.*, week, block, phase, history(metric, n)).
Actions: add_load(kg), set_load(kg), sub_assist(lb), set_sets, set_hold, set_state, switch_variation, start_protocol, schedule_test, flag(msg).
Examples: B_wpu post_session: when all(sets, reps>=2 && rir>=2) && !prompts.hand_slip -> add_load(2.5). Negatives: when min(ecc_seconds)>=8 -> add_load(5); when min(ecc_seconds)<5 || prompts.jerky -> set_load(45). A_assisted weekly -> sub_assist(5). C_assisted post_session: when all(rir>=2) -> state.easy+=1 else state.easy=0; when state.easy>=2 -> sub_assist(5), state.easy=0. block_end: when no 1RM progress 2 blocks -> flag('check sleep/food') then switch_variation(B:'ladder_1_2_3') + extra deload.
Evaluation order deterministic: rules by (hook, priority, id); actions applied atomically; full before/after state logged.
CONST: No numeric constants beyond those in definition; engine limits: max rule depth 8, whitelisted functions only; definitions versioned (semver) and immutable once active (new version for edits).
LOG: Rule firings (rule id, inputs, actions), state snapshots, prompt answers, protocol activations, test-day metrics, phase transitions; enables Claude review of challenge rules separately from general algorithms.

### A14 RestTimer recommendation
PURPOSE: Default rest per set, overridable; adjusts after hard sets.
IN: Exercise class, reps, RIR achieved, RTF delta, challenge-defined rest, per-side flag.
OUT: Rest seconds for timer; logged actual rest.
PSEUDO:
base = 180 s if compound and (reps<=6 or RIR<=1); 150 s compound 7-12 reps; 90 s isolation; 75 s if reps>=15; per-side unilateral: base applies after both sides, 60 s between sides for holds
if last set RTF delta <= -2: +30 s; challenge exercise rest from definition overrides
user override -> store; if user overrides same exercise 3 times in same direction, set new default = median of overrides.
CONST: 180/150/90/75 s (Fitbod strength 3-5 min; longer rest >60-90 s favorable for hypertrophy per Schoenfeld 2016, Singer 2024 MA — moderate), +30 s, 3-override learning.
LOG: Prescribed vs actual rest, next-set RTF error as function of actual rest (personal rest-performance curve).

## DESIGN OPTIONS
- Split/session-type model: [Fixed weekday split (Stronglifts/5-3-1 style) / Rotation queue (GZCLP A1/B1/A2/B2), day-independent / Fully dynamic recovery-driven (Fitbod) / Hybrid: need x readiness x goal scoring with queue tie-break and challenge protection] -> Hybrid (A2/A3).. WHY: User's days shift weekly (calendar), challenge occupies 3 pull-heavy sessions, goals vary per muscle; pure Fitbod is opaque/random, pure fixed split breaks on shifted days. Scoring stays deterministic and explainable.
- Exercise stability: [Rotate every session (Fitbod) / Lock per mesocycle, rotate at meso end (RP) / Never rotate] -> Lock per mesocycle with swap on pain/equipment/user; rotate stalled or disliked slots at meso end.. WHY: Progressive overload + clean e1RM trends need repeated exposures; Fitbod rotation is a common complaint; occasional rotation keeps novelty and addresses stalls.
- Load estimator: [Last-session double progression only (Hevy/Liftosaur dp) / e1RM model only (Fitbod/RTS) / Hybrid: personal-k e1RM drives prescription, double progression + failure counter as bounded rules] -> Hybrid (A5-A7).. WHY: e1RM transfers across rep ranges/exercises and handles 'flatter curve' via personal k; double-progression rules give predictable, explainable steps and guard against noisy RIR.
- Effort input granularity: [No RIR (Alpha optional mode) / RIR on every set (one-tap chips 0/1/2/3/4+, prefilled with target) / RIR only on last set per exercise / Session-level Easy/Ideal/Hard (Gravl)] -> Per-set one-tap RIR prefilled with target; mandatory only on last set of each exercise; session RPE once at finish.. WHY: Requirement explicitly asks RIR after each set; prefill keeps friction low; last-set RIR is most accurate (Halperin 2022).
- Volume progression across meso: [Constant volume, progress load only / RP-style +sets weekly from soreness/pump/workload questions / Automated RP table using computed performance + one soreness question, capped +20%] -> Automated RP table with caps (A9).. WHY: Keeps RP's proven feedback loop but removes most questions (performance is computed from logs); Camargo 2026 shows modest increases are enough; caps prevent RP's volume creep.
- Deload policy: [Fixed every N weeks / Reactive only (Dr Muscle 1RM decline) / Planned week 5 synced to challenge + reactive triggers] -> Planned 4+1 synced with challenge blocks + reactive triggers from week 3; deload = 50% sets, same load (not cessation).. WHY: Aligns whole-body fatigue with OAP deload/test week; Coleman 2023 shows cessation hurts strength; Bell 2024 supports planned or autoregulated.
- Challenge implementation: [Hardcoded OAP module / JSON rule DSL (JSONLogic-like conditions + typed actions, hooks post_set/post_session/weekly/block_end, state, prompts, protocols) / Embedded scripting language (Liftoscript/JS sandbox)] -> JSON rule DSL, versioned, with whitelisted variables/actions.. WHY: Deterministic, safe, validatable, reviewable by Claude Code, editable without app release; Liftoscript proves the hook/state/prompt model covers complex programs; full scripting adds attack surface and debugging cost.
- Pre-session readiness check: [None / Questionnaire (sleep, stress, soreness, motivation) / Single tap good/normal/bad that only reduces volume/RIR, never e1RM] -> Single tap, optional; in-session RIR autoregulation does the heavy lifting.. WHY: Readiness questionnaires add friction with weak predictive value; requirement's reasons (feeling good/bad) map directly.
- Calibration method: [Max Effort Days to failure (Fitbod) / AMRAP/rep-out sets (5/3/1, Juggernaut, APRE) / RIR-1 calibration set in meso week 1 + continuous RIR] -> RIR-1 calibration on compounds, AMRAP allowed on machines/isolation; true max tests only inside challenges' test days.. WHY: Safer for heavy unilateral/elbow-sensitive user; still gives fresh e1RM and RIR-bias data.
- Muscle set counting: [Prime mover only (RP) / Fractional: primary 1.0, secondary 0.5 (Pelland 2026) / Continuous EMG-like weights] -> Fractional 1.0/0.5.. WHY: Best-supported by Pelland 2026; simple, deterministic; avoids double counting challenge pull volume while still counting it.

## RISKS
- RIR misestimation (~1 rep underestimation, worse far from failure) biases e1RM; mitigate with RIR bias learning from calibration sets and weighting last-set RIR higher.
- Personal k overfitting to few/fatigued sets (e.g., 18 BW reps done fresh vs 3RM later) -> require RTF spread >=4, 14-day pairing, shrinkage n0=10.
- Model-dependent 1RM: artifact's +42.8 kg (Epley x1.10) vs +39.2 kg from personal-k fit; challenge phase gates must use measured test values or same formula consistently, else false phase transitions.
- Bodyweight variation changes system load of pull-ups/dips; need regular weigh-ins (weekly) or e1RM noise; assisted machine counterweight non-linear/friction, lbs vs kg unit mixing.
- Soreness is a weak recovery signal in experienced lifters (RP over-adds sets); keep soreness as only one input and cap increments.
- Feedback fatigue: too many questions -> skipped/garbage inputs; keep to RIR chips + 1 soreness tap per muscle + session RPE.
- Double counting or under counting challenge volume in general planner (pull muscles) if challenge exercises lack muscle mapping.
- Running and other activities (calendar 'Hardlopen') affect legs; coefficients are guesses; mis-tuning could block leg sessions or overfatigue.
- Calendar parsing fragile (Dutch titles, shifted days, stale Todoist 'Gym' all-day events from 2022-2023) -> must use explicit user mapping rules and ignore all-day stale recurrences.
- Forgotten logging looks like missed sessions/detraining -> add 'log retroactively' and 'trained elsewhere' options before applying A11 reductions.
- Determinism risks: floating-point rounding/snapping differences across devices/versions; use fixed rounding rules, decimal kg with 0.01 precision, versioned constants and input hashes.
- Equipment increments wrong (Basic-Fit DB steps, stack steps) -> unloadable recommendations (top Fitbod complaint); require equipment profile per gym.
- Opaque-app 'facts' (RP percentages, Gravl 7%/10%, Fitbod 40-60%) come from marketing/reviews, not code; treat as starting constants only.
- Elbow/tendon injury risk with heavy unilateral pulling: general planner must respect challenge protocols and weekly heavy-unilateral cap, and never push pulling volume on top.

## QUESTIONS
- RIR input: OK to tap reps-in-reserve after every set (prefilled with target), or only after the last set of each exercise?
- Will you answer one soreness question per trained muscle at the start of the next session that trains it (RP-style), or should the app rely only on logged performance?
- Exercise variety: keep the same exercises for a whole 5-week block (better progression tracking) or prefer variety every session?
- Should general (non-challenge) training follow the same 5-week block and deload/test week as the OAP challenge?
- Typical time budget per gym session, and do you want add-on blocks (e.g., push/legs) after a challenge session if time allows?
- How many total gym sessions per week (including the 3 challenge sessions) are you willing to do?
- Should runs from your calendar count as leg fatigue (affecting leg-day recommendations)? Roughly how many km per week?
- Exact equipment increments at Basic-Fit Ierse Pond: dumbbell steps (2 kg? 2.5 kg?), machine stack steps, available plates for dip belt, microplates?
- Allow going to 0 RIR (failure) on isolation exercises in the last accumulation week, or keep minimum 1 RIR everywhere?
- For 1RM estimates and challenge thresholds, which formula should be canonical: the artifact's Epley (3RM x 1.10) or the app's personal rep-curve fit (currently gives ~+39 kg vs +42.8 kg)?
- Muscle goals now: which groups improve / maintain / none (e.g., pull covered by challenge; legs maintain?)?
