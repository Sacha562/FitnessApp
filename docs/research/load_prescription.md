# LENS load_prescription

## SUMMARY
Lens: sets, reps and load prescription, progression, autoregulation and the rest timer. Core design is one parametric rep-capacity model: cap(w) = 1 + k*(M/w - 1), where w is system load, M is the 1RM and k sets how flat the curve is. M and k are personal and tracked per user, exercise and side. M comes from a robust local-linear-trend Kalman filter in log space (it handles censored observations and decays during long breaks). k comes from a MAP grid fit with a class prior taken from Nuzzo 2024. A within-session fatigue model is calibrated to Nuzzo's set-by-set rep decline (to failure: 70, 55, 50, 45%). Prescription picks the loadable weight that best meets the target reps and RIR across all sets. Intra-session autoregulation uses hysteresis and stop rules. Every override reason has a fixed effect on the estimator. Timed holds use a power-law endurance model (Frey Law and Avin 2010). Warnings use stimulus ratios plus fixed rules at four severities. Stall and deload tiers, warm-up generation and rest timers come from Singer 2024, Schoenfeld 2016 and Grgic 2018. Worked examples on the user's data: the model gives the user k about 30.6, flatter than the NSCA textbook table (about 22-27). It reproduces the 'last two sets barely made' anchor at 4x5 +20 kg. It picks +27.5 kg for 6x2 at 2 RIR, the same load as the challenge plan. Different classic formulas turn the same 3RM into anything from +38.6 to +46.9 kg, so challenge gates must use tested singles and the app must show a confidence interval. Evidence base: hypertrophy works across a wide load range near failure, while strength depends on load (Schoenfeld 2017/2021, Lopez 2021). Hypertrophy improves closer to failure and strength does not (Robinson 2024; Refalo 2023/2024). People underestimate how many reps they have left by about 1 (Halperin 2022). Weekly volume has diminishing returns (Pelland 2024), with a per-session plateau around 11 fractional sets for hypertrophy and 2 direct sets for strength (Remmert preprint). Rest of 90 s or more is enough for hypertrophy and 2-3 min or more for strength. Many constants are expert opinion: they are flagged, versioned and logged so they can be tuned later. Web search budget ran out late, so a few citations (Lasevicius 2018, Schoenfeld 2017 volume, Sanchez-Medina 2011) are from memory and marked accordingly.

## FINDINGS
### Load vs adaptation (rep continuum) [strong]
Hypertrophy is similar across a wide range of loads when sets go near failure. Strength favours heavier loads. Lopez 2021 network meta-analysis (28 studies, 747 adults): no hypertrophy difference between low, moderate and high loads; strength gains for high and moderate loads beat low loads, and high was non-significantly better than moderate. Schoenfeld 2017 meta (low vs high load): strength favours high load (reported ES 2.30+-0.43 vs 1.23+-0.43). Schoenfeld 2021 'repetition continuum' re-examination: hypertrophy is obtainable across a wide spectrum of loads (about 6-30+ reps). Lasevicius 2018 (from memory): 20% 1RM gave less hypertrophy than 40/60/80% at equated volume load. App consequences: allow 5-30 rep ranges for hypertrophy; warn above 30 reps (cap at the prescribed load) and strongly above 40; default strength work to 2-6 reps at >=80% e1RM.
SRC: Lopez P et al. 2021 Med Sci Sports Exerc network meta-analysis https://jyx.jyu.fi/handle/123456789/75929 | Schoenfeld BJ et al. 2017 J Strength Cond Res low vs high load meta https://pubmed.ncbi.nlm.nih.gov/28834797/ | Schoenfeld BJ, Grgic J, Van Every DW, Plotkin DL 2021 Sports 9(2):32 https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7927075/ | Lasevicius T et al. 2018 Eur J Sport Sci (from memory)

### Proximity to failure [moderate]
Refalo 2023 Sports Med meta (15 studies): failure vs non-failure gives a trivial hypertrophy advantage (ES 0.19); the relationship may be non-linear. Robinson & Pelland 2024 Sports Med meta-regressions (55 hypertrophy and 67 strength studies; RIR estimated from study descriptions): hypertrophy rises as sets end closer to failure, strength shows a negligible RIR relationship, and load drives strength. Refalo 2024 within-subject RCT (n=18 trained, 8 wk): failure vs 1-2 RIR gave equal quadriceps thickness gains (0.181 vs 0.182 cm). Grgic 2022 JSHS meta (15 studies): no significant difference overall; in trained subjects failure was better for hypertrophy; when volume was not equated, non-failure was better for strength. App consequences: default RIR 1-2 for hypertrophy, 2 for strength; allow failure only on the last set of safe isolation or machine exercises.
SRC: Refalo MC et al. 2023 Sports Med https://openrepository.aut.ac.nz/items/78679612-6ff4-4d00-95d1-7920d072a760 | Robinson ZP, Pelland JC et al. 2024 Sports Med https://rke.abertay.ac.uk/en/publications/exploring-the-dose-response-relationship-between-estimated-resist/ ; OSF https://osf.io/7knsj/ | Refalo MC et al. 2024 J Sports Sci https://openrepository.aut.ac.nz/items/dbe641c7-a606-4e00-94bc-bb2fbe253afa | Grgic J et al. 2022 J Sport Health Sci https://doaj.org/article/dc36ba7ccd994798964f66b93bd8f662

### RIR/RPE accuracy [moderate]
Halperin 2022 Sports Med meta (13 publications, 12 studies, n=414, 262 effect sizes): people underpredict reps to failure by 0.95 reps (95% CI 0.17-1.73), I2 97.9%. Predictions improve closer to failure (beta -0.025) and are much worse when more than 12 reps remain to failure (beta 0.47 vs 0.06 for 12 or fewer). Later sets help only trivially (beta -0.07); experience has no clear effect. Steele 2017 PeerJ: standard errors of 2.64-3.38 reps when predicting reps to momentary failure. Refalo 2024 JSCR (trained, bench at 75% 1RM): error 0.65+-0.78 reps (via the Marzagao 2026 review). Zourdos 2016: the RIR-based RPE scale is valid (velocity-RPE r=-0.88 experienced, -0.77 novice). App consequences: treat reported RIR as noisy and biased; use a user bias b (prior +0.5); let the noise sd grow with RIR and rep count; schedule calibration sets on safe exercises.
SRC: Halperin I et al. 2022 Sports Med https://sportrxiv.org/index.php/server/preprint/view/67 | Steele J et al. 2017 PeerJ (Europe PMC) | Refalo MC et al. 2024 J Strength Cond Res 38(3):e78-e85 | Zourdos MC et al. 2016 J Strength Cond Res https://openrepository.aut.ac.nz/items/efef3b25-6701-4fb5-bb82-55fcd2a26027/full

### Reps-%1RM relationship is exercise- and person-specific [strong]
Nuzzo 2024 meta-regression (952 tests, 7,289 people, 269 studies): exercise type is the only meaningful moderator; sex, age and training status have minimal effect. Main model: about 5 reps at 90%, 8 at 80%, 15 at 70%. Bench press: about 4 / 9 / 14. Leg press: about 9 / 13 / 19. Between-person SD is 2.51 reps at 80% and 4.36 at 60%. Natural cubic splines fit better than classical forms. Converted to k in cap=1+k(1/p-1): main model k 28-36, bench 27-32, leg press 42-48 (up to 72 at 90%), NSCA textbook table about 22-27. Richens & Cleather 2014: endurance runners did 39.9 vs 17.9 reps for weightlifters at 70% on the leg press. Shimano 2006: more squat reps than bench or curl reps at 60%. App consequence: per-exercise, per-user k with a class prior (sigma_lnk 0.30).
SRC: Nuzzo JL et al. 2024 Sports Med https://pmc.ncbi.nlm.nih.gov/articles/PMC10933212 ; preprint https://sportrxiv.org/index.php/server/preprint/view/291 | Richens B, Cleather DJ 2014 Biol Sport (via Marzagao 2026 review) | Shimano T et al. 2006 J Strength Cond Res (via Marzagao 2026 review)

### e1RM formulas and their spread [engineering-fact]
Formulas, with w = load and r = reps: Epley 1RM = w(1+r/30) (from a 1985 poundage chart); Brzycki = w/(1.0278-0.0278r) = 36w/(37-r); Lombardi = w*r^0.10; Mayhew = 100w/(52.2+41.9e^(-0.055r)); Wathen = 100w/(48.8+53.8e^(-0.075r)); O'Conner = w(1+0.025r); Lander = 100w/(101.3-2.67123r). RIR adjustment: use r+RIR in place of r (diagonals of the Tuchscherer RPE chart). Validation: LeSuer 1997 found all equations underestimated deadlift; Reynolds 2006 found 5RM most accurate (R2 0.993 bench, 0.974 leg press); accuracy degrades above about 10 reps. User's 3RM +32.5 kg at BW 70.7 (system 103.2 kg) converts to +38.6 (Brzycki), +39.9 (Lander), +40.2 (O'Conner), +41.2 (Tuchscherer 92.2%), +41.8 (Wathen), +42.8 (Epley), +44.5 (Lombardi), +46.9 (Mayhew), and +39.2 with the personal fit k=30.6. The spread is 8.3 kg and straddles the +44/+46 challenge gates, so gates must be checked with tested singles.
SRC: Marzagao T 2026 arXiv:2603.17495 literature review section https://arxiv.org/abs/2603.17495 | LeSuer DA et al. 1997 J Strength Cond Res | Reynolds JM et al. 2006 J Strength Cond Res | Tuchscherer RPE chart https://fitnessvolt.com/rpe-training/guides/tuchscherer-chart-explained

### Weight-dependent k (big app-log data) [weak]
Marzagao 2026 (arXiv preprint, single author, 303,494 near-failure Fitbod sets, 388 exercises) proposes 1RM = w(1 + (r-1)^0.85 / (-2.55 + 4.58 ln w)). It is 17-22% more internally consistent than Brzycki, Epley, Wathen and Mayhew, and better for all 183 exercises with at least 50 tuples. 91% of the gain comes from the weight-dependent k, and lighter exercises have steeper curves (k 8 at 10 kg, 20.4 at 150 kg). Caveats: the metric is consistency, not accuracy against tested 1RMs; bodyweight and assisted exercises were excluded; the absolute k values are far below lab values, probably because logged sets were not truly to failure. Use: only as evidence that k varies by exercise; supports a learned per-exercise k and a lower prior for isolation lifts.
SRC: Marzagao T 2026 arXiv:2603.17495 https://arxiv.org/pdf/2603.17495

### User's personal rep curve (computed) [engineering-fact]
With cap = 1 + k(M/w - 1), the two maximal points BW x18 (w 70.7) and 3RM +32.5 (w 103.2) give k = 30.6 and M = 109.9 kg system (+39.2 kg). If strength rose 2% between 30 Aug and 19 Sep, k = 32.8, so k should only be fitted from data within about 14 days. The NSCA table implies k of about 22-27, so the user is flatter than the table, matching the artifact's note. Model check: 4x5 at +20 kg (w 90.7, rest 150 s) predicts fresh capacity 7.5 reps and per-set RIR 2.5, 1.6, 0.85, 0.14, which matches 'last two sets just made'. The 6x2 search at 180 s rest with RIR at least 2 on every set picks +27.5 kg (last set RIR 1.98), the same as the plan.
SRC: Artifact data (OAP challenge HTML, local copy) | Computed in scratchpad (python), constants per specs RCM-1/FAT-1

### Weekly and per-session volume [moderate]
Pelland et al. 2024/2026 meta-regressions (67 studies, 2,058 participants; indirect sets counted 0.5 as 'fractional'): both hypertrophy and strength rise with weekly sets with diminishing returns, more so for strength; the best fit is square-root-like. Frequency mattered for strength (posterior 100%) but not clearly for hypertrophy. Remmert, Pelland, Robinson, Hinson & Zourdos per-session preprint: the point where extra sets stop showing a detectable benefit (PUOS) is about 11 fractional sets per session for hypertrophy and about 2 direct sets per session for strength. Schoenfeld 2017 volume meta (from memory): about +0.37% muscle growth per extra weekly set; 10+ sets/week beat <5. App defaults: 'improve' muscle 10-20 fractional sets/week; per-session INFO above 11, WARN above 15; strength exercises 2-4 sets.
SRC: Pelland JC et al. dose-response preprint https://sportrxiv.org/index.php/server/preprint/view/460 | Remmert JF et al. per-session volume preprint https://sportrxiv.org/index.php/server/preprint/view/537 | Schoenfeld BJ, Ogborn D, Krieger JW 2017 J Sports Sci (from memory)

### Maintenance and detraining [moderate]
Spiering 2021 review: strength and size are maintained for up to 32 weeks with 1 session/week and 1 set per exercise, provided the relative load is kept (young adults); older adults may need 2 sessions/week with 2-3 sets. Bickel 2011: one-third and one-ninth volume kept hypertrophy in 20-35 year olds but not in 60-75 year olds. Bosquet 2013 meta (103 studies): strength is mostly unaffected for up to 28 days (g<0.2), then losses grow with duration (pooled g 0.31 for younger adults); older adults lose about twice as much. Retraining takes about half the layoff length (SBS synthesis). App consequences: 'maintain' means at least 1 session/week and 1-3 hard sets at usual load; the estimator has a 21-day grace period, then decays about 0.75%/week (expert estimate) with growing uncertainty.
SRC: Spiering BA et al. 2021 J Strength Cond Res https://pubmed.ncbi.nlm.nih.gov/33629972/ | Bickel CS et al. 2011 Med Sci Sports Exerc (Europe PMC) | Bosquet L et al. 2013 Scand J Med Sci Sports via https://www.strongerbyscience.com/detraining/

### Inter-set rest [moderate]
Singer et al. 2024 Bayesian meta-analysis (9 studies, 19 measurements): small benefit for rest over 60 s and no appreciable hypertrophy difference beyond 90 s; when sets are equated, shorter rest may mean fewer reps. Schoenfeld 2016 (21 trained men, 8 weeks, 3x8-12RM, 7 exercises): 3-min rest beat 1-min for strength and thigh muscle thickness. Grgic 2018 review: rest over 2 min is needed to maximise strength in trained lifters. Senna 2011 / Willardson 2006: with 1-min rest, reps drop from set 2; with 3-5 min, from sets 3-4. Defaults: 180 s strength, 150 s lower-body compounds, 120 s upper-body compounds, 90 s isolation, never below 60 s.
SRC: Singer A et al. 2024 Front Sports Act Living 10.3389/fspor.2024.1429789 https://public-pages-files-2025.frontiersin.org/journals/sports-and-active-living/articles/10.3389/fspor.2024.1429789/text | Schoenfeld BJ et al. 2016 J Strength Cond Res 30(7):1805-1812 | Grgic J et al. 2018 Sports Med 48:137-151 https://vuir.vu.edu.au/38537/ | Senna G et al. 2011; Willardson JM 2006 (via Marzagao 2026 review)

### Rep decline across sets [moderate]
Nuzzo secondary analysis (repeated sets to failure at the same load): sets 2-6 reach about 70%, 55%, 50%, 45% and 45% of set-1 reps; the decline is curvilinear and longer rest softens it. Fatigue rises disproportionately as sets approach failure (velocity-loss literature, e.g. Sanchez-Medina & Gonzalez-Badillo 2011, from memory). The FAT-1 recursion (c 0.30, d 0.75, gamma 2) reproduces 1.00/0.70/0.565/0.504/0.477 to failure.
SRC: Nuzzo JL et al. secondary analysis https://ro.ecu.edu.au/ecuworks2022-2026/4232 | Sanchez-Medina L, Gonzalez-Badillo JJ 2011 Med Sci Sports Exerc (from memory)

### Progression method [moderate]
Plotkin 2022 PeerJ (43 trained, 8 weeks): adding reps and adding load gave equal hypertrophy; strength slightly favoured load (+2.0 kg, CI -2.4 to 7.8). So double progression (reps first, then load) is valid when load steps are coarse, for bodyweight exercises, and for isolation lifts.
SRC: Plotkin D et al. 2022 PeerJ https://peerj.com/articles/14142

### Autoregulation [moderate]
Helms 2018 (8 weeks, trained men; RPE vs %1RM with matched sets and reps): RPE-based loading gave a small strength advantage (bench +10.70 vs +9.64 kg; squat +17.05 vs +13.91 kg; non-significant). Zhang 2021 meta (8 studies, 166 subjects) and Larsen 2021 review: RPE/RIR and velocity-based autoregulation are at least as good as fixed percentages. This supports daily readiness adjustment from first-set residuals.
SRC: Helms ER et al. 2018 Front Physiol 9:247 https://www.ncbi.nlm.nih.gov/pmc/articles/PMC5877330/ | Zhang X et al. 2021 Front Physiol 10.3389/fphys.2021.651112 | Larsen S et al. 2021 PeerJ https://peerj.com/articles/10663

### RPE chart = load change per RIR [moderate]
Tuchscherer chart, % of 1RM by reps+RIR: 1: 100, 2: 95.5, 3: 92.2, 4: 89.2, 5: 86.3, 6: 83.7, 7: 81.1, 8: 78.6, 9: 76.2, 10: 73.9; 10 reps at RPE 6.5 = 64.0. That is about 3.0-3.7% per RIR at 1-5 reps and about 2.4-3.2% at 8-11. The model's d ln w / dn = 1/(k+n-1) gives the same: 3.1% at k=30, n=3 and 2.6% at n=10, so one formula handles intra-session load changes and RIR conversion.
SRC: Tuchscherer RPE chart https://fitnessvolt.com/rpe-training/guides/tuchscherer-chart-explained | Helms ER et al. 2016 Strength Cond J (RIR-based RPE application)

### Bodyweight load fractions [moderate]
Ebben 2011 peak ground reaction force as % BW: regular push-up 64; knee 49; feet raised 30.48 cm 70, 60.96 cm 74; hands raised 30.48 cm 55, 60.96 cm 41. Suprak 2011: push-up 69.16% (up) and 75.04% (down); knee 53.56 / 61.80%. de Leva 1996 segment masses (male): hand 0.61%, forearm 1.62%, upper arm 2.71% of BW, so in a pull-up about 4.5% of BW (both hands and forearms) stays on the bar and the anatomical fraction is about 0.955. Suspension rows: about 50.4% BW on the straps at 0 degrees with elbows extended and 75.3% with elbows flexed. Assisted machines: the stack value may not equal the true assistance (pulley or lever ratio), so calibrate per machine.
SRC: Ebben WP et al. 2011 J Strength Cond Res https://ojs.ub.uni-konstanz.de/cpa/article/view/4457 | Suprak DN et al. 2011 J Strength Cond Res via https://www.cooperinstitute.org/blog/how-much-weight-is-really-lifted-during-a-push-up | de Leva P 1996 J Biomech (segment table) https://exrx.net/Kinesiology/Segments.html | Giancotti GF et al. 2021 JSCR inverted row https://ricerca.unich.it/bitstream/11564/842538/1/Giancotti%20G.F.%20et%20al.%202021_JSCR.pdf

### Day-to-day noise of maximal strength [strong]
Grgic 2020 systematic review of 1RM test-retest reliability: ICC median 0.97 (range 0.64-0.99); CV median 4.2% (range 0.5-12.1%), whatever the experience, exercise or sex. The estimator therefore uses a day-level sd of 0.035 (ln units) on top of RIR and model error.
SRC: Grgic J et al. 2020 Sports Med Open 10.1186/s40798-020-00260-z (Europe PMC abstract)

### Isometric hold endurance [moderate]
Frey Law & Avin 2010 Ergonomics meta (194 publications, 369 data points): endurance time ET = b0*f^b1 (f = fraction of maximal voluntary contraction, MVC). b0/b1 by joint: general 21.92/-1.98, hand/grip 33.55/-1.61, elbow 17.98/-2.21, shoulder 14.86/-1.83, trunk 22.69/-2.27, knee 19.38/-1.88, ankle 34.71/-2.06. The exponent converts load changes into time changes for holds (one-arm hang = grip; top and 90-degree holds = elbow). Absolute b0 values do not fit individuals, so only the exponent is used and the scale is learned per user.
SRC: Frey Law LA, Avin KG 2010 Ergonomics 53(1) https://pmc.ncbi.nlm.nih.gov/articles/PMC2891087/

### Deloads [moderate]
Coleman 2024 (39 young adults, 9 weeks): a 1-week deload gave no difference in lower-body hypertrophy, local endurance or power, and continuous training gave better strength. Bell 2023 Delphi consensus definition: a deload is a period of reduced training stress to reduce fatigue and promote recovery; experts use both planned and reactive deloads. App consequence: use reactive deloads triggered by stall detection, and planned deloads only when the user or a challenge defines them (the OAP plan has a deload every 5th week).
SRC: Coleman M et al. 2024 PeerJ 'Gaining more from doing less?' (Europe PMC) | Bell L et al. 2023 Sports Med Open Delphi deloading (Europe PMC)

### Warm-up [weak]
Ribeiro systematic review (11 studies): specific warm-ups generally help resistance performance; whether to add a general warm-up is not agreed. Warm-up sets should be brief and low-fatigue (reps at most half of capacity).
SRC: Ribeiro B et al. Motricidade systematic review https://revistas.rcaap.pt/motricidade/article/view/21143 | Barroso R et al. 2013 J Strength Cond Res general warm-up leg press 1RM https://repositorio.usp.br/item/002306941

### Deterministic reproducibility [engineering-fact]
For replays by Claude Code reviewers to give bit-identical results across JVM and Android, use java.lang.StrictMath (fdlibm) for log, exp and pow, round stored outputs to 0.01 kg / 0.01 rep, iterate in a stable order, and break ties toward the lighter load. Every output should store algo_version, a hash of the parameters and a snapshot of the inputs.
SRC: Java SE StrictMath documentation (fdlibm reproducibility)

## ALGORITHMS
### RCM-1 Personal rep-capacity curve (generalized Epley, per-user k)
PURPOSE: Convert (system load, reps, RIR) to and from estimated 1RM and reps-to-failure; personalise how flat the curve is per user and exercise (the user's curve is flatter than tables).
IN: Working sets of the last 56 days: w_sys (kg), reps, calibrated RIR q_true (RIRCAL-1), fatigue F_i (FAT-1), flags/reason class, date, side; exercise curve_class prior k0.
OUT: k_used, sd_lnk, number of informative windows, and the functions cap(w;M,k), load(n;M,k), e1RM(w,r,q;k), pct_per_rep(n,k).
PSEUDO:
MODEL (w = system load kg; n = reps to failure from fresh):
cap(w;M,k) = 1 + k*(M/w - 1)
load(n;M,k) = M / (1 + (n-1)/k)
e1RM(w,r,q) = w*(1 + (r + q - 1)/k)
d ln w / d n = -1/(k+n-1)  => % load per rep = 100/(k+n-1)  (k30,n3: 3.1%; n10: 2.6%; matches Tuchscherer chart)
FIT k (end of every session, per user x exercise, sides pooled):
 D = working sets last 56 d, not excluded, (q_rep<=4 or failure), n_eff<=20
 n_fresh_i = n_eff_i/(1-F_i)
 split D into 14-d windows; window informative if >=2 sets and max(n_fresh)-min(n_fresh) >= 3
 if no informative window: k_used = pooled class k of this user (same procedure over all exercises of class) else k0
 grid k_g = exp(ln12 + (ln90-ln12)*g/79), g=0..79
 J(k_g) = sum_win sum_i huber((e_i - mu_win)/s_i, 1.5) + (ln k_g - ln k0)^2/(2*0.30^2)
   e_i = ln w_i + ln(1+(n_fresh_i-1)/k_g); mu_win = sum(e_i/s_i^2)/sum(1/s_i^2); s_i from SSE-1
 k_hat = argmin J (tie -> closer to k0); sd_lnk = sqrt(1/second finite difference of J in ln k)
 ln k_used = (m*ln k_hat + 2*ln k0)/(m+2), m = #informative windows
 k_used = clamp(k_used, 0.9*k_prev, 1.1*k_prev) then clamp [12, 90]
WORKED EXAMPLE (weighted pull-up, w_sys = BW + added, BW 70.7): BW x18 (w 70.7, n 18) and 3RM +32.5 (w 103.2, n 3): 103.2(1+2/k) = 70.7(1+17/k) -> k 30.6, M 109.9 -> 1RM +39.2 kg. With +2% strength between dates -> k 32.8 (hence 14-d windows). Anchor 4x5 @ +20 (w 90.7): cap 7.5 -> with FAT-1 rest 150 s per-set RIR 2.5/1.6/0.85/0.14 = 'last two sets barely'.
CONST: k0 by curve_class (n-1 form): upper_compound (bench/press/row/pulldown/pull-up/dip) 30 [Nuzzo 2024 bench k 27-32]; lower_machine_compound (leg press/hack/pendulum) 45 [Nuzzo leg press k 42-48]; lower_free_compound (squat/RDL/hip thrust/lunge) 36 [Shimano 2006, interpolation, expert]; isolation_upper (curl/triceps/lateral raise/fly/face pull) 26 [expert; Marzagao 2026 lighter = steeper]; isolation_lower (leg ext/curl/calf/adductor) 32 [expert]. sigma_lnk 0.30 [Nuzzo between-person SD 2.51 reps at 80% = CV ~0.31]. Lookback 56 d; window 14 d; n_eff <= 20 [accuracy drops above about 10-12 reps: Reynolds 2006, Halperin 2022]; informative spread >= 3 reps; Huber delta 1.5; grid 80 log points 12-90; shrink pseudo-count 2; rate limit 10% per fit.
LOG: Table fit_runs: user, exercise, side, algo_version, params_hash, date, k_prev, k_hat, k_used, sd_lnk, m_informative, n_sets, set_ids, J_min, J(k0). Per set: w_sys, reps, q_rep, q_true, F_i, n_fresh, residual (e_i - mu_win). Replay lets reviewers compare alternatives (Epley r-form, Marzagao (r-1)^0.85 with k(w), splines) by one-step-ahead reps prediction error.

### SSE-1 Strength-state estimator (log-space local-linear-trend Kalman, robust and censored)
PURPOSE: Track each exercise's 1RM (system load) and its weekly trend (the progression curve), with uncertainty, outlier handling, reason flags and detraining decay.
IN: Prior state (L = ln M, g = ln/week, 2x2 covariance P, k, t_last); session sets (w_sys, reps, q_rep, reasons, rest, set order); b_user and s_q_user (RIRCAL-1); F_i (FAT-1); BW_used.
OUT: Posterior L, g, P; M = exp(L); 80% CI exp(L +- 1.28*sqrt(P_LL)); forecast M(t+h) = exp(L + g*h/7); added-load 1RM = M - f_bw*BW; innovation stats.
PSEUDO:
PREDICT to session date (dd days, wk = dd/7):
 if dd <= 21: L += g*wk; g *= rho^wk; P = F P F' + diag(qL^2*wk, qg^2*wk), F = [[1,wk],[0,rho^wk]]
 else: L += g*3 - min(0.15, lam*(dd-21)/7); g = 0; P_LL += qL^2*wk + 0.02^2*(dd-21)/7; P_gg = sg0^2; P_Lg = 0
SESSION UPDATE (on finish):
 per working set i: q_true = clamp(q_rep + b_user, 0, 10) (missing RIR -> REASON-1 imputation, sigma_q += 1.0)
   n_eff = reps + q_true; skip if n_eff > 25
   n_fresh = n_eff/(1 - F_i)
   z_i = ln w_sys + ln(1 + (n_fresh-1)/k)
   s_i^2 = (sigma_q/(k+n_fresh-1))^2 + (0.005*max(0, n_fresh-5))^2 + [i>1]*(0.03*F_i/0.3)^2
   class_i (REASON-1): normal | lower_bound | downweight m (s_i^2 *= m) | excluded
 normal: zbar = sum(z/s^2)/sum(1/s^2); R = sd_day^2 + 1/sum(1/s^2)   // day noise once: sets in one session are correlated
 nu = zbar - L; S = P_LL + R; if |nu| > 3*sqrt(S): R *= (|nu|/(3*sqrt(S)))^2; S = P_LL + R
 K = [P_LL, P_Lg]/S; L += K0*nu; g += K1*nu; P -= K*[P_LL, P_Lg]
 lower_bound: zlb = max z_i; if zlb > L + 0.5*sqrt(P_LL): same update with R = 2*(sd_day^2 + s^2)
COLD START: first calibration set -> L = z_1, P_LL = 0.08^2, g = 0, P_gg = sg0^2; or transfer L = ln(ratio*M_related), P_LL = 0.15^2
WORKED EXAMPLE (weighted pull-up): prior M 109.94 (sd 3%), g 0.3%/wk; +7 d -> M_pred 110.27, sd 3.18%. Session 4x5 @ +22.5 (w 93.2), rest 150 s, RIR 2/1/1/0, b 0.5 -> n_eff 7.5/6.5/6.5/5.5; F 0/.117/.225/.289; n_fresh 7.5/7.36/8.38/7.73; M_obs 113.0/112.5/115.7/113.7; zbar 113.6; R 0.0016; K 0.387 -> M_post 111.5 kg (+40.8 kg 1RM, 80% CI +37.3..+44.4), g 0.26%/wk.
CONST: sd_day 0.035 [Grgic 2020 1RM CV median 4.2%]; qL 0.010 per sqrt(week) and qg 0.001 per sqrt(week) [expert]; rho 0.85/week trend decay [expert]; sg0 0.005 (0.5%/week) [expert]; detraining grace 21 d [Bosquet 2013: <=28 d trivial loss], lam 0.0075/week with cap 0.15, plus variance 0.02^2/week [expert]; sigma_q base 1.0 (n<=12), 1.5 (12<n<=20), 2.5 (>20), plus 0.15*q_rep [Halperin 2022; Steele 2017; Refalo 2024]; form error 0.005 per rep above 5 [LeSuer 1997, Reynolds 2006]; fatigue error 0.03 [expert]; Huber gate 3 sd; n_eff cap 25; lower-bound gate 0.5 sd; cold-start sd 0.08 / transfer sd 0.15.
LOG: Per session update: state before/after (L, g, P, k), zbar, R, nu, K, per-set z_i, s_i, class_i, F_i, n_fresh, BW_used, algo_version, params_hash. Review metrics: one-step-ahead error of the next session's first-set n_eff (MAE in reps), 80% CI coverage, bias by rep bucket and by reason, Huber trigger rate.

### RIRCAL-1 RIR bias and noise calibration
PURPOSE: Correct the user's systematic RIR misjudgement and estimate its noise so that e1RM and prescriptions are not biased.
IN: Calibration-set logs (predicted RIR at the prescribed reps, actual extra reps to technical failure); unintended failures; reasons.
OUT: b_user (and per-class b_c once there are 5 or more samples), s_q_user; schedule of calibration sets.
PSEUDO:
init b = 0.5, s_q_user = 1.0
EXPLICIT check: eligible exercise safety_class in {machine, cable, isolation}, not challenge-flagged, last working set. App asks q_pred after r* reps; user continues to technical failure; extra = total_reps - r*
 e = extra - q_pred
 b <- clamp(b + 0.2*(e - b), -1, 3)
 s_q_user <- clamp(sqrt(0.8*s_q_user^2 + 0.2*(e - b)^2), 0.5, 3)
IMPLICIT: unintended failure (reps < target with 'failed rep') -> e = (actual n_eff with q=0) - (FAT-1 predicted cap with reported RIR), weight 0.1 instead of 0.2
SCHEDULE: at most 1 check per week; rotate classes; class cooldown 21 d; user can opt out
APPLY: q_true = q_rep + (b_c if n_c >= 5 else b); sigma_q = base(n)*s_q_user + 0.15*q_rep
REASON hook: 'too_easy' in 2 consecutive sessions with reported RIR <= q* -> b += 0.25
CONST: b0 0.5 [between Halperin 2022 pooled 0.95 underprediction and Refalo 2024 trained error 0.65]; learning rate 0.2 (implicit 0.1); b clamp [-1, 3]; s_q clamp [0.5, 3]; at most 1 check/week; 21-d cooldown; per-class b after 5 samples.
LOG: Table calibration_checks: exercise, class, date, r*, q_pred, extra, e, b before/after, s_q before/after, type (explicit/implicit). Reviewers can check whether the bias depends on rep count or load (Halperin moderators) and whether to make b a function of n.

### LOAD-1 Effective load model and loadable increments
PURPOSE: Put barbell, dumbbell, stack, cable, assisted, belt and bodyweight exercises on one system-load scale and enumerate exactly loadable options (with lb/kg handling).
IN: Exercise (f_bw, implement mass), equipment profile (type, native unit, inventory/steps, ratio), BW weigh-ins, side.
OUT: w_sys for any external setting; sorted list of loadable (native value, kg, w_sys).
PSEUDO:
w_sys = f_bw*BW_used + m_impl + added_kg - assist_kg
assist_kg = A_native*unit_kg*ratio_machine (lb -> 0.45359237)
BW_used = EWMA of weigh-ins (alpha 0.3); last weigh-in older than 14 d -> prompt; BW changes move the prescribed added/assist automatically at constant w_sys
LOADABLES:
 barbell/Smith: bar + 2*sum(plates per side) over inventory counts (default 25,20,15,10,5,2.5,1.25 kg, 4 each)
 dumbbell: explicit list from gym profile
 stack/cable: base + i*step (+ add-ons {0,1.25,2.5} if owned); store pulley ratio
 assisted: A = i*step_native (pin 5 lb), i = 0..imax
 belt: subset sums of belt inventory (own + gym plates), at most 4 items
 bodyweight-only: {0}
store native value + unit + kg; display native; compute in kg
UNILATERAL: separate state per side, same equipment
EXAMPLES: assisted one-arm pull-up pin 40 lb -> 18.14 kg -> w_sys 52.56/arm; pin 35 lb -> 54.82 (+4.3%) = 0.042*(k+n-1) ~ 1.5 reps per pin step (k 30.6, n 5) -> coarse step -> DOUBLE/RULE progression. Belt 47.5 = 20+20+5+2.5.
CONST: f_bw: pull-up/chin/neutral/one-arm 1.00 (convention matching challenge %BW thresholds; anatomical ~0.955 per de Leva 1996: hand 0.61% + forearm 1.62% per side); dip 1.00; push-up 0.64; knee push-up 0.49; feet raised 30 cm 0.70, 61 cm 0.74; hands raised 30 cm 0.55, 61 cm 0.41 [Ebben 2011; Suprak 2011 alternative 0.69-0.75]; inverted row 0.60 (range 0.50-0.75 by elbow angle) [weak]; plank / hanging leg raise not load-modelled. ratio_machine default 1.0 [unknown; calibrate]; BW EWMA alpha 0.3; staleness 14 d.
LOG: Every set: native load + unit, kg, BW_used, f_bw, ratio, equipment_id, the ideal continuous load vs the chosen loadable (rounding gap in reps = gap%*(k+n-1)). Gym inventory changes are versioned.

### FAT-1 Within-session fatigue (rep capacity across sets)
PURPOSE: Predict how reps-to-failure capacity falls across sets as a function of reps done, proximity to failure and rest; used for prescription, fatigue-corrected e1RM and the live rest readout.
IN: cap_fresh, per-set reps, planned or actual rest (s), earlier sets today on overlapping muscles.
OUT: F_j and cap_j per set; predicted reps/RIR per set.
PSEUDO:
F_1 = min(0.25, 0.04*sum over earlier sets today on exercises sharing muscles (overlap 1.0 primary / 0.5 secondary) of (r/cap)^gamma)
cap_j = cap_fresh*(1 - F_j)
F_{j+1} = d*F_j + c(rest_j)*(min(1, reps_j/cap_j))^gamma*(1 - F_j)
c(rest) = c_ref*clamp((rest/120)^(-0.6), 0.4, 1.6)  // 60 s 0.455; 120 s 0.30; 150 s 0.262; 180 s 0.235; 300 s 0.173
CHECK vs Nuzzo (to failure, c 0.30): 1.00, 0.70, 0.565, 0.504, 0.477, 0.46 vs observed 1.00, 0.70, 0.55, 0.50, 0.45, 0.45
PERSONALISE (per user x class, nightly): grid c_ref 0.10..0.50 step 0.02 minimising sum (n_eff_obs_j - n_eff_pred_j)^2 over the last 60 multi-set exercises; shrink c = (m*c_hat + 30*0.30)/(m+30), m = sets; same for d (grid 0.5..0.9) once m >= 100
CONST: c_ref 0.30, d 0.75 [fitted to Nuzzo secondary analysis]; gamma 2.0 [expert; near-failure sets cost disproportionately more fatigue, velocity-loss literature]; rest exponent -0.6, clamp [0.4, 1.6] [Willardson 2006, Senna 2011: 1-min rest declines from set 2, 3-5 min from sets 3-4; expert fit]; carry-over 0.04 per prior hard-set equivalent, cap 0.25 [expert]; shrink pseudo-count 30 sets.
LOG: Per set: actual rest from timestamps, predicted cap_j and RIR, observed n_eff_j, F_j, overlap carry-over; residuals by rest bucket (<75, 75-105, 105-150, 150-210, >210 s) and by RIR bucket, for refitting c_ref, d, gamma and the rest exponent.

### RX-1 Next-session prescription and load selection (with progression modes)
PURPOSE: Choose load, reps and sets for each planned exercise from state, preferences and equipment; handle coarse increments, bodyweight, unilateral work and challenge rules.
IN: Slot: exercise, S, scheme (RANGE r_lo..r_hi or FIXED r*), q* (RIR target applying to every set), goal, planned rest, progression_mode; SSE-1 state, k, delta_today; loadables; last performed load; reason history.
OUT: Per set: native load, w_sys, target reps, predicted RIR; mode used; caps applied; candidate table (for the log).
PSEUDO:
M_use = exp(L_pred - z_c*sqrt(P_LL) + delta_today)
candidates = loadables with w_sys in [0.35*M_use, 1.0*M_use]
for each w: simulate FAT-1 with planned rest:
 cap_j = cap(w)*(1 - F_j)
 reps_j = RANGE: clamp(round_half_down(cap_j - q*), r_lo, r_hi) | FIXED: r*
 rir_j = cap_j - reps_j
RANGE score J = sum (rir_j - q*)^2 + 4*sum max(0, q* - 0.75 - rir_j)^2 + 2*sum(reps outside range)^2 + 100*[any rir_j < 0]; pick min J, ties -> lower w
FIXED (strength): feasible if min_j rir_j >= q* - 0.25; pick heaviest feasible; none feasible -> lightest candidate, S -= 1 (logged)
CAPS: w* <= w_last_done*(1+cu) (lifted if last set rir >= 4); w* >= 0.90*w_last_done unless pain/detraining/'too hard'
MODE: step_rel = (next loadable above w* - w*)/w*; if step_rel*(k + cap_1 - 1) > 1.5 or bodyweight-only -> DOUBLE: keep last load until every set reaches r_hi at rir >= q*, then next loadable
RULE (challenge exercises): load/assist from challenge rule engine; model runs in shadow and is logged
BODYWEIGHT-only: reps = clamp(round(cap - q*), r_lo, r_hi_bw); cap - q* > r_hi_bw in 2 sessions -> suggest harder variant (higher f_bw) or added load
UNILATERAL: match_weaker (default for strength/skill): load from the weaker side's state, weaker side first, stronger side's reps capped at the weaker side's achieved reps; otherwise independent per side
WORKED EXAMPLES:
(1) weighted pull-up FIXED 6x2, q* 2, rest 180, M 109.9, k 30.6, BW 70.7: +25 -> RIR 3.56..3.01; +26.25 -> 3.10..2.50; +27.5 -> 2.66,2.46,2.30,2.17,2.07,1.98 (feasible, heaviest); +28.75 -> 2.23..1.47 (infeasible) => +27.5 kg = challenge plan.
(2) leg press RANGE 3x8-10, q* 2, rest 150, calibration 120 kg x12 at RIR 3 -> n_eff 15.5 -> M 158.7 (k0 45); 5 kg stack: 120 -> 10/10/10 RIR 5.5/3.8/2.4 J 15.8; 125 -> 10/9/8 RIR 3.1/2.1/1.7 J 1.37; 130 -> 9/8/8 RIR 1.9/1.0/-0.4 infeasible => 125 kg x 10, 9, 8.
CONST: z_c 0.5 (0 if sd < 2%; 1.0 for 2 sessions after 2x 'too hard') [expert]; cu 0.05 compound / 0.10 isolation; down cap -10%; RANGE weights 1/4/2/100, under-target tolerance 0.75 RIR; FIXED tolerance 0.25 RIR; coarse threshold 1.5 reps; candidate window 0.35-1.0 M [below ~30-35% 1RM inferior: Lasevicius 2018]; default q*: hypertrophy compounds 2, isolation/machines 1 (0 allowed on last set if safe), strength 2 [Robinson 2024], maintain 2-3; default schemes: hypertrophy RANGE 8-12 or user preference [Schoenfeld 2021], strength FIXED 2-5 at >=80% [Schoenfeld 2017, Lopez 2021]; bodyweight r_hi 20.
LOG: Table prescriptions: input snapshot (L, P, g, k, b, delta_today, BW, F_1, rest plan), full candidate table (w, predicted reps/RIR per set, J/feasibility), chosen w, caps applied, mode, algo_version, params_hash; joined to actual sets -> prescription error (rir_actual - q*) per set, % sets inside the rep range, rate of caps applied.

### AUTO-1 Intra-session autoregulation and stop rules
PURPOSE: Adapt the rest of today's session to today's readiness without over-reacting to noise.
IN: Each completed working set (reps, RIR, reason), FAT-1 state, SSE-1 predicted state, planned remaining sets, time budget.
OUT: Updated load/reps for the remaining sets, readiness offset delta, stop decisions with reason codes, and suggestions (accepted in one tap or automatically per user setting).
PSEUDO:
after working set j:
 if reason pain -> REASON-1 stop path
 eps_j = ln w + ln(1 + (n_eff_j/(1-F_j) - 1)/k) - L_pred
 delta_ex <- delta_ex + Ks*(eps_j - delta_ex), Ks = P_LL/(P_LL + s_j^2) (typ 0.3-0.5); clamp [-0.08, +0.06]
 delta_today for other exercises = 0.5*mean(delta_ex of finished exercises), only after >= 2 exercises agree in sign
 re-run RX-1 for remaining sets with M_use*exp(delta_ex) and current F
 HYSTERESIS: change load only if |rir_pred_next(current w) - q*| >= 1.5 or rir_pred_next < q* - 1 (safety drop always allowed); otherwise change reps +-1 inside range; raise by at most 1 loadable step (strength) or 5% per set
 STOP: (a) best candidate >= 0.85*w_planned predicts reps < r_lo at rir >= max(0, q*-1) -> end exercise (fatigue stop); (b) 2 consecutive sets with reps < r_lo at RIR 0; (c) 2x technique_breakdown; (d) time budget exceeded -> REASON-1 no_time
WORKED EXAMPLE: 6x2 @ +27.5, set 1 reported RIR 4 (n_eff 6.5) -> M_obs 115.9 (+5.4%); s 0.057, P_LL 0.0016 -> Ks 0.33 -> delta +1.7% -> predicted set-2 RIR at +27.5 = 3.0, deviation +1.0 < 1.5 -> keep load; the long-term update happens in SSE-1.
CONST: transfer 0.5; delta clamp -8% / +6%; hysteresis 1.5 RIR; safety drop trigger q* - 1; fatigue-stop load floor 0.85 of planned; per-set raise at most 1 step / 5% [expert; autoregulation evidence: Helms 2018, Zhang 2021, Larsen 2021].
LOG: Per set: pre-set prediction (cap, RIR), residual eps, delta_ex/delta_today, decision code (keep/raise/lower/reps/stop) plus trigger, suggestion shown vs user action (accepted/overrode), time stamps. Lets reviewers tune hysteresis, transfer and clamps from the hit rate of post-change predictions.

### REASON-1 Override / skip reason semantics
PURPOSE: Give every in-gym override reason a fixed effect on the estimator, today's session and future prescriptions, and impute missing RIR.
IN: Override event (field, from, to, reason code, optional text, pain location/score), set data.
OUT: Estimator class per set (normal / lower_bound / downweight m / excluded), session adjustments, future flags, adherence records.
PSEUDO:
reason -> estimator | today | future
too_easy -> normal; RIR missing -> impute q = q*+2 (sigma_q 2.0) | AUTO-1 may raise | 2 consecutive sessions with reported RIR <= q* -> b_user += 0.25
too_hard -> normal; impute q = max(0, q*-2) if reps >= target else 0 | AUTO-1 lower | 2 consecutive -> z_c 1.0 for next 2 sessions
feeling_good -> normal | delta_today allowed up to +6% | none
feeling_bad -> downweight m 4 (sd x2) | delta_today = -0.05 for remaining exercises, offer -1 set/exercise | >= 3 in 14 d -> recovery check prompt + STALL-1 candidate
no_time -> skipped sets: no observation; done sets normal | drop by priority (accessories first, keep challenge/primary), rest at floors | no make-up volume; adherence = time
occupied -> no observation | offer substitute (same primary muscles, preference-ranked) or reorder | log equipment_busy(eq, weekday, hour); busy >= 50% over 4 visits at that slot -> deprioritise as first choice
pain(location, 0-10) -> excluded | stop exercise; >= 4/10 -> stop all exercises loading that joint | next 2 exposures: load -10%, q* >= 3, or substitute; 2 events in 14 d -> advise stopping + professional check; challenge protocol hook (e.g. medial elbow)
technique_breakdown -> normal with q_true = 0 at reps done | next set -5% or -1 rep | none
grip_failure -> lower_bound (can only raise) | suggest straps/grip; excluded from 'all sets hit' progression checks (challenge: hand slip -> no increase) | >= 3 events -> suggest grip work
equipment_issue/setup -> no observation
other(text) -> normal if numbers logged, sd x1.5
skip (no reason) -> adherence only
MISSING RIR (no reason): q = clamp(plan-predicted RIR, 0, 5) if reps >= target; 0 if reps < target - 1; sigma_q 2.0
CONST: impute offsets +-2 RIR, sigma_q 2.0; feeling_bad m 4 and delta -0.05; too_easy b step 0.25; too_hard z_c 1.0 for 2 sessions; pain stop threshold 4/10, 2 events/14 d, -10% load, q* >= 3; equipment busy 50% over 4 visits; feeling_bad alert 3 in 14 d [all expert-opinion, tunable].
LOG: Table overrides: set_id, field (sets/reps/load/skip/add/remove/rest), from, to, reason, text, pain location/score, timestamp, derived effect codes applied. Reviewers check whether 'feeling_bad' sets really underperform (residual mean), and how often 'too_easy' disagrees with reported RIR.

### HOLD-1 Timed isometric holds (hangs, top holds, 90-degree holds, planks)
PURPOSE: Prescribe and progress time-based holds by time and assistance with the same estimator machinery.
IN: Hold sets: seconds held t_done, seconds-in-reserve chip s_res, load/assistance, side, rest; joint class.
OUT: Target seconds per set, assistance/load, progression events, per-side capacity T.
PSEUDO:
t_cap(w) = T*(w/w_ref)^beta, w_ref = BW (or first-session w_sys)
state ln T per side via SSE-1 machinery (sd_day 0.10)
observation z = ln(t_done + s_res) - beta*ln(w/w_ref); s_res chips {0, 2, 5, 10+ -> lower_bound}; sigma = 0.2 + 2/(t_done + s_res)
fatigue: FAT-1 with ratio t/t_cap, c_ref_hold 0.25
prescribe: range t_lo..t_hi, reserve s* = max(2, 0.2*t*); adjustable load -> choose w with t_cap(w)*(1-F) - s* inside [t_lo, t_hi], prefer heavier; fixed (BW) -> t* = clamp(floor(t_cap*(1-F) - s*), t_lo, t_hi)
progress: all sets reach t_hi with reserve >= s* -> next load step (e.g. pin -5 lb) if predicted t >= t_lo
WORKED EXAMPLE: 90-degree one-arm hold at 35 lb assist (w 54.82): 8 s + 2 s left -> T 10 s; 30 lb (w 57.09, +4.1%) -> 10*1.0414^-2.21 = 9.1 s -> 6-8 s target with 2 s reserve feasible -> step down. One-arm dead hang (BW, grip): 10 s + 4 left -> cap 14 s -> progress time toward the 20 s phase criterion.
CONST: beta from Frey Law & Avin 2010 (ET = b0*f^b1): grip/hang -1.61; elbow-flexion holds -2.21; shoulder/straight-arm -1.83; trunk/plank -2.27; knee -1.88; general -1.98. sd_day_hold 0.10, reserve 20% / >= 2 s, c_ref_hold 0.25 [expert].
LOG: Hold sets: t_done, s_res, load/assist (native + kg), side, rest, F, residual; beta refit once 2 or more load levels exist within 14 d (user-specific beta).

### WARN-1 Preference-conflict warnings
PURPOSE: Keep the user's preferred sets/reps/RIR, but flag schemes that evidence says are clearly worse, override only on safety, and quantify the expected loss.
IN: User preferences per exercise/goal, weekly plan (sets per muscle, fractional), prescribed loads (cap, p = w/M), rest settings, muscle goals (improve/maintain/none).
OUT: Warnings {rule_id, severity INFO/WARN/STRONG/OVERRIDE, message with numbers, suggested alternative}; logged user response.
PSEUDO:
STIMULUS:
 hypertrophy set h = f_rir(q)*f_load(cap); f_rir: q<=1 1.00, 2 0.95, 3 0.85, 4 0.70, >=5 0.50; f_load: cap<=30 1.0, 30-40 0.85, >40 0.60
 weekly muscle H_m = sqrt(sum fractional sets*h) (primary 1.0, secondary 0.5)
 strength per exercise per session S = (1 - exp(-sets/1.5))*f_int(p); f_int: p>=0.85 1.0, 0.80-0.85 0.95, 0.70-0.80 0.85, 0.60-0.70 0.70, <0.60 0.50
COMPARE user scheme vs best scheme with SAME set count and +-10% session time (vary reps/q*/load only): ratio < 0.85 -> WARN; < 0.70 -> STRONG (one-tap 'use suggested'); preference kept unless an OVERRIDE rule fires
RULES:
 R1 cap at prescribed load > 30 reps WARN, > 40 STRONG
 R2 hypertrophy q* >= 4 WARN, >= 5 STRONG
 R3 q* = 0 on high-risk (loaded free-weight compounds, weighted/one-arm pull-ups, loaded dips) -> OVERRIDE to 1; elsewhere INFO
 R4 'improve' muscle weekly fractional sets < 6 WARN, < 3 STRONG; 'maintain' 0 sets in 10 d WARN
 R5 per-session fractional sets per muscle > 11 INFO, > 15 WARN
 R6 strength-goal exercise r_lo > 6 WARN, > 10 STRONG
 R7 rest < 60 s compound WARN; < 90 s strength goal WARN
 R8 range width < 2 and load step > 1.5 reps INFO (widen range)
 R9 load jump above cap -> OVERRIDE clamp
 R10 'none' muscle receiving primary sets INFO
WORKED EXAMPLES: 3x8-10 at RIR 2 (hypertrophy) -> ratio 1.0, no warning. Friend 2x12 at RIR 2 -> ratio 1.0, but an 'improve' muscle trained 2x/week = 4 sets/week -> R4 WARN: '+1 set/session = sqrt(6/4) = +22% expected stimulus'. 2x12 on a strength-goal weighted pull-up: p 0.70 -> f_int 0.85 vs 2x4 at p 0.87 -> ratio 0.85 -> WARN (borderline).
CONST: f_rir and f_load tables [expert ordinal fit to Robinson 2024, Refalo 2023, Lasevicius 2018]; sqrt volume response [Pelland 2024 square-root fit]; strength set saturation tau 1.5 sets [Remmert per-session PUOS ~2 direct sets]; f_int table [Schoenfeld 2017, Lopez 2021]; thresholds: 0.85/0.70 ratios, 30/40 reps, RIR 4/5, 6/3 weekly sets [Schoenfeld 2017 volume; Pelland 2024], 11/15 session sets [Remmert], 6/10 r_lo strength, 60/90 s rest [Singer 2024; Grgic 2018].
LOG: warnings_shown: rule_id, severity, inputs and numbers, suggestion, user response (accept/keep/force), timestamp; outcome tracking: e1RM slope and weekly sets for exercises under warned vs unwarned schemes.

### STALL-1 Stall, regression and deload detection
PURPOSE: Detect stalled or regressing exercises and global fatigue, then apply tiered fixes (deload, stimulus change, global deload with a lifestyle check).
IN: SSE-1 slope g and covariance per exercise, exposures, readiness residuals (delta_today), reason history, challenge deload calendar.
OUT: Per-exercise status (progressing/stall/regress) and action; global deload recommendation; checklist prompt.
PSEUDO:
weekly (Sunday night) and after each session, per exercise:
 E = exposures in last 42 d excluding sessions flagged pain or feeling_bad
 STALL if |E| >= 4 and g_mean < 0.001/week and P(g > 0.003) < 0.2
 REGRESS if g_mean < -0.003/week and delta residual < 0 in >= 3 of last 4 exposures
 Tier1 (first stall in 8 wk): next exposure sets = max(1, floor(0.6*S)), load kept (strength) or -10% (hypertrophy), q* + 1; then resume the model
 Tier2 (2nd stall in 8 wk): change stimulus for 4-6 wk: shift rep range by 4 (8-10 -> 4-6 or 12-15) or swap to the next-preferred variant for the same muscles
GLOBAL: >= 40% of active exercises STALL/REGRESS within 14 d, or delta_today <= -0.04 in >= 3 of last 4 sessions, or feeling_bad >= 3 in 14 d -> recommend deload week (sets x0.5; strength loads kept; accessory q* + 2) + checklist (sleep, food, stress, BW trend)
planned deloads only when the user or a challenge defines them
CONST: window 42 d; minimum 4 exposures; stall slope 0.1%/week; regress -0.3%/week; P(g>0.3%) < 0.2; tier1 sets x0.6; tier2 rep shift 4 for 4-6 weeks; global 40% / -4% / 3 feeling_bad in 14 d [expert; Coleman 2024 argues against frequent planned deloads; Bell 2023 consensus definition].
LOG: stall_evaluations: exercise, date, g_mean, g_sd, exposures, status, action, and the outcome 4 weeks later (slope after the intervention) for evaluating tier efficacy.

### WARMUP-1 Warm-up set generator
PURPOSE: Generate brief, low-fatigue specific warm-up sets before working sets, scaled to intensity.
IN: First working w_sys and M (p_w = w/M), exercise class, muscles already trained today, loadables, BW/assist availability, challenge warm-up block if any.
OUT: List of (native load, reps, rest) warm-up sets.
PSEUDO:
warm = muscle already trained today with >= 2 sets
if isolation or p_w < 0.60 or warm: list = (first exercise for muscle and not warm) ? [(0.50,10)] : []
elif p_w < 0.80: [(0.50,8), (0.75,3)]
else: [(0.45,6), (0.65,3), (0.85,1)] + [(0.92,1)] if test/singles day
each (frac, reps): w = frac*w_work (system), snap DOWN to loadable; for BW exercises with w < f_bw*BW use BW (or assisted if available)
reps = min(reps, floor(0.5*cap(w))); dedupe equal loads (keep higher reps); rest 45-60 s between, 90 s before the first working set
challenge-supplied warm-up block replaces the generator for that session
WORKED EXAMPLE: weighted pull-up working +27.5 (w 98.2, p_w 0.89): 0.45 -> 44 kg < BW -> BW x4 (cap 18 -> max 9); 0.65 -> BW (deduped); 0.85 -> 83.5 -> +12.5 x2 => BW x4, +12.5 x2, then +27.5.
CONST: fractions and reps above [expert; Ribeiro review: brief specific warm-up helps]; 50% of capacity cap; rests 45-60 s / 90 s; p_w thresholds 0.60 / 0.80.
LOG: Warm-up sets suggested vs done, time spent, first working set residual (eps) with vs without warm-up, to test whether warm-up variants change performance.

### REST-1 Rest timer and between-session recovery
PURPOSE: Give deterministic inter-set rest defaults that adapt to set outcome and user behaviour, show the predicted readiness trade-off live, and recommend rest days between hard sessions per muscle.
IN: Exercise class, scheme, last set outcome (reps vs target, RIR), F, superset/unilateral layout, actual rest history, user overrides; hard-set history per muscle.
OUT: Timer duration (s), live 'start now vs wait' prediction, per-exercise rest defaults; per-muscle readiness and 'ready in X h'.
PSEUDO:
base: strength_heavy (FIXED reps <= 5 or p >= 0.85) 180; compound_lower 150; compound_upper 120; isolation 90; core/holds 90; warm-up 45-60; last warm-up -> work 90
mods: +30 if rir_last <= 0.5 or reps_last < target - 1; +15 if F_next > 0.25; -15 if rir_last >= q* + 2
superset antagonist pair: 60 s between members, full base after the pair; unilateral alternating: per-side timer, rest_L = base - duration(R set) - transition
clamp [60, 300]; +-15 s buttons; per-exercise override persisted
live readout: predicted next-set reps at the current elapsed rest via FAT-1 c(rest) (e.g. 'now: 8 at RIR 2; at 2:00: 9')
adapt (per user x class, after >= 10 sets): >= 8/10 sets started >= 20 s early and mean fatigue residual >= -0.5 rep -> base -15 (floor 60/90/120 by class); mean residual <= -1 rep with rest >= prescribed -> base +15 (cap 300)
BETWEEN SESSIONS: FU_m += 1.0 primary / 0.5 secondary per hard set (q <= 4), x1.3 if q <= 1 or eccentric-emphasis; FU_m(t) = FU_m*0.5^(dt/h_m); h_m: small (biceps, triceps, delts, calves, forearms) 24 h, large upper (chest, lats, upper back) 30 h, legs (quads, glutes, hams) 36 h; recommend hard work for m only if FU_m <= 3 (about 48 h after 8-10 sets); show 'ready in X h'; refit h_m from first-set residual vs FU_m at session start
CONST: 180 s strength [Grgic 2018; Willardson 2006]; 150/120 s compounds [Schoenfeld 2016 3 vs 1 min; Singer 2024 >60 s, nothing extra beyond 90 s]; 90 s isolation; 60 s minimum [Singer 2024]; +30/+15/-15 modifiers, adaptation thresholds, FU half-lives and threshold 3 [expert; common 48-72 h guidance].
LOG: Per set: prescribed rest, actual rest (timestamps), timer edits, next-set residual; per session: FU_m per muscle at start and first-set residuals; used to refit rest-to-fatigue curve (FAT-1), base rests and h_m.

## DESIGN OPTIONS
- Rep-max curve model: [Fixed classic formula (Epley/Brzycki) / Tuchscherer RPE table lookup / Generalized Epley cap = 1 + k(M/w - 1) with per-user per-exercise k (MAP grid fit, class prior) / Marzagao weight-dependent k(w) = -2.55 + 4.58 ln w with (r-1)^0.85 / Per-user nonparametric spline] -> Generalized Epley with personal k (RCM-1), and a replay harness to compare alternatives. WHY: One interpretable parameter; class priors reproduce Nuzzo tables; fits the user's flatter curve (k 30.6 vs table ~22-27); one formula gives % per rep for autoregulation. Fixed formulas disagree by 8 kg on the user's 3RM. Marzagao excludes bodyweight/assisted and is a single-author preprint scored on consistency. Splines need far more data.
- Strength-state estimator: [Best e1RM of last session / EWMA of e1RM / Local-linear-trend Kalman in log space with robust/censored updates and detraining / 2D grid Bayes over (M,k)] -> Local-linear-trend Kalman (SSE-1), with k fitted separately (RCM-1). WHY: Gives level plus slope (the 'progression curve') and uncertainty, handles reason-based down-weighting and lower bounds, cheap and explainable on a phone. The last-best approach chases noise (1RM CV ~4%). The 2D grid is heavier and harder to audit.
- What the RIR target means and the rep scheme: [RIR on last set only, same reps each set / RIR target on every set; reps drop inside the range (e.g. 10/9/8) / Fixed reps with a minimum-RIR constraint on every set] -> RANGE scheme (constant effort, reps drop within range) for hypertrophy; FIXED reps with min-RIR for strength and challenge work. WHY: It uses the user's range (3x8-10) naturally, keeps effort in the effective RIR 1-3 zone (Robinson 2024), and matches the challenge rule 'all 6x2 with 2 RIR'. The last-set-only approach leaves early sets at RIR 4+.
- Progression mode per exercise: [Pure model-driven load / Pure double progression / Hybrid: model by default, auto-switch to double progression when one load step is worth more than 1.5 reps or the exercise is bodyweight-only, plus RULE mode for challenge-defined exercises] -> Hybrid with automatic switching plus RULE mode (the model runs in shadow and is logged). WHY: Assisted pin steps (~1.5 reps) and dumbbell jumps are too coarse for fine load control. Plotkin 2022 shows rep progression works. Challenge rules must stay as defined, and shadow logging lets the reviewer compare them with the model.
- Bodyweight fraction for pull-ups/dips: [1.00 (convention) / 0.955 (anatomical: hands + forearms stay on bar) / Per-user calibrated] -> 1.00. WHY: Matches the challenge's '+kg' and '%BW' thresholds and coaching sources; any constant error cancels in ratios; simpler for the user to verify.
- RIR input UX: [Mandatory on every set / Mandatory on first and last working set, optional otherwise with model imputation / None (reps only)] -> One-tap chips (0,1,2,3,4,5+, failed) mandatory on the first and last working set; others optional with imputation. WHY: First set drives today's readiness, last set drives progression; cuts friction; imputation has larger sigma_q so the estimator stays honest.
- In-session load changes: [Auto-apply / Suggest with one-tap accept / Off] -> Suggest with one-tap accept by default, with hysteresis (1.5 RIR); auto-apply as a setting. WHY: The user keeps control in the gym; acceptance is logged for tuning; hysteresis avoids fiddly plate swaps.
- Conservatism under uncertainty: [Use the posterior mean / Mean minus 0.5 sd / Lower 80% bound] -> Mean minus 0.5 sd (z_c 0.5), 0 when sd < 2%, 1.0 after repeated 'too hard'. WHY: Protects new or noisy exercises from overshoot without permanently under-loading well-known lifts.
- Rest timer adaptivity: [Static per class / Rule-based modifiers + user override + slow learning / Fully learned per user] -> Rule-based modifiers + override + slow learning (REST-1). WHY: Deterministic and explainable; the evidence gives class defaults (Singer 2024, Grgic 2018); learning only after 10 or more sets keeps it stable.
- Engine location and versioning: [Compute in Supabase (SQL/Edge) / On-device pure Kotlin engine module, parameters from a DB table, every output logged with algo_version + params_hash + input snapshot] -> On-device pure Kotlin module (StrictMath, fixed rounding, golden-file tests); Supabase stores logs, parameter sets and states. WHY: Works offline in the gym; deterministic replay by Claude Code reviewers; parameter changes are versioned without app releases.
- RIR calibration sets: [None / Scheduled at most 1/week on safe machine/isolation exercises, never on challenge lifts / Every session] -> Scheduled weekly on safe exercises, opt-out available. WHY: RIR bias (~1 rep, Halperin 2022) is the main error source; occasional cheap checks fix it without safety risk.
- Warning policy: [Silently override when evidence disagrees / Warn only / Tiered INFO/WARN/STRONG with quantified stimulus ratio; OVERRIDE only for safety] -> Tiered, OVERRIDE only for safety rules (no failure on high-risk lifts, load-jump caps). WHY: Honours the user's stated preferences while quantifying evidence-based trade-offs; safety needs to be enforced.

## RISKS
- RIR self-report is noisy and biased (about 1 rep; up to 2.6-3.4 reps SEM in less trained people), so the estimator can drift; mitigated by calibration sets, robust and lower-bound updates and the conservative z_c.
- The 1RM extrapolation depends on the formula (+38.6 to +46.9 kg from the same 3RM). Challenge phase gates (+44/+46 kg) must use tested singles or in-model thresholds with CI, not one formula's point estimate.
- k is only identifiable with sets near failure at different rep counts within about 14 days; if the user always trains in one band, k stays at the prior (fine for local prescription, poor for 1RM display).
- Fatigue model (gamma, rest exponent, carry-over) and many thresholds are expert-opinion calibrated on to-failure data; they must be tuned from logs, so log richly from day 1.
- Machines differ (cams, friction, lb vs kg, pulley ratios); the same exercise on another machine needs a separate state; the assisted-machine true assistance is unknown (ratio 1.0 assumed).
- Bodyweight fluctuations of 1-2 kg shift prescriptions for bodyweight exercises by 1-2%; use a smoothed BW and prompt for weigh-ins.
- Over-autoregulation can chase noise; hysteresis, clamps and session-level aggregation are needed.
- Feedback loop: overly conservative prescriptions produce sets far from failure, which carry little information and lead to stagnation; mitigate with calibration sets and slope-based stall detection.
- Safety: failure or heavy eccentric work on one-arm and weighted pull-ups stresses the elbow; enforce RIR >= 1 and the pain protocol; the app is not medical advice.
- Evidence quality: Robinson 2024 RIR values were estimated from study descriptions; Marzagao is a single-author preprint; several values here are from memory (Lasevicius 2018, Schoenfeld 2017 volume, Sanchez-Medina 2011) because the web-search budget ran out.
- Determinism: floating-point differences between JVM and Android, or a Node replay, can break bit-exact replay; use StrictMath, fixed rounding and stable iteration order.
- Unit errors (lb pins vs kg plates, per-side vs total plate loading) can produce wrong loads; store native value and unit and validate against equipment profiles.
- Cold start on new exercises can overshoot; use a calibration ramp, load-jump caps and a larger initial P.

## QUESTIONS
- RIR entry: is one tap on the first and last working set of each exercise OK, or do you want to enter RIR on every set (more accurate, more taps)?
- Would you accept an occasional 'RIR check' set (at most once a week, only on a machine or isolation exercise, never on challenge lifts) where you go to technical failure, so the app can calibrate your RIR estimates?
- In-session changes: should the app only suggest load or rep changes (one tap to accept), or apply them automatically?
- Default rep scheme for hypertrophy work: 'reps drop within your range at the same weight' (e.g. 10/9/8 at constant effort), or 'same reps every set' (effort rises toward the last set)?
- Should pull-ups and dips count 100% of bodyweight plus added kg (matches your challenge's +kg and %BW numbers)?
- Equipment at Basic-Fit Ierse Pond: smallest plates (1.25 kg?), dumbbell steps, stack step sizes of the machines you use, and is the leg press selectorized or plate-loaded (sled weight)? Which plates do you own for the dip belt?
- Assisted machine: is it always the same machine, and is it OK to assume the pin value equals the real assistance? (A quick check with a bathroom scale on the platform is possible.)
- Bodyweight: will you enter weigh-ins manually (how often?), or should the app read a smart scale through Health Connect?
- Rest timer: vibration, sound or lock-screen notification? Auto-start when you log a set? For alternating one-arm work, should rest count per arm?
- When you log 'feeling bad today', should the app automatically lighten the rest of the session (about -5% load, -1 set per exercise) or only suggest it?
- Are automatic safety overrides OK: no failure sets on weighted or one-arm pull-ups and loaded dips, and a +5% per-session load-jump cap on compounds?
- Pain logging: do you want a body-location picker (e.g. medial elbow, wrist, shoulder) and a 0-10 score, so challenge and general pain protocols can trigger?
- Which exercises should count as 'strength' (low reps, heavy) versus 'hypertrophy'? Default proposal: challenge lifts = strength, everything else = hypertrophy unless you say otherwise.
