# LENS exercise_selection

## SUMMARY
Lens: exercise catalog and exercise selection. Main conclusions:

(1) Seed data: don't import an open dataset wholesale. Hand-curate about 150 Basic-Fit-realistic exercises in a versioned JSON seed with our own muscle taxonomy and coefficients.
- free-exercise-db (Unlicense, 876 entries fetched today, 17 coarse muscles) is the only license-clean optional source, and only for instruction text and cross-reference IDs.
- wger data is CC-BY-SA (765 of 918 entries are CC-BY-SA 4). Its muscle labels are poor: 15 muscles, 157 entries have no primary muscle, and some labels are wrong. Only 49 entries have Dutch names.
- ExerciseDB is now AscendAPI: commercial, media terms unclear. Not suitable.

(2) Taxonomy has two levels. 18 user-facing groups carry goals and volume (front, side and rear delts are separate). About 32 regions carry only a soft variety bonus (for example rectus femoris, long-length hamstrings, triceps long head, gastrocnemius, upper chest).

(3) Stimulus coefficients come in discrete levels: 1.0 / 0.75 / 0.5 / 0.25. The 0.5 level for indirect work follows Pelland 2026, whose fractional counting fit best. EMG is not used (Vigotsky 2022). Where trials exist, they override the default, e.g. squat and leg press give 0 hamstrings (Kubo 2019), rows give biceps about 0.5 (Mannarino 2021), seated calf raise gives little gastrocnemius (Kinoshita 2023). Each exercise also gets a long-length emphasis attribute from Maeo 2021 and 2023, Pedrosa 2022, Kassiano 2023 and Wolf 2023/2025.

(4) Selection uses a deterministic beam search (width 12) over at most 25 candidates. Each exercise is credited with its share of useful muscle coverage, multiplied by the user's preference multiplier and a small quality term. Penalties apply for overshooting targets, time, spinal load and injuries. Bonuses apply for region variety and for keeping exercises within a mesocycle. Sets are then adjusted by at most 1 around the user's preferred set count.

(5) Banned exercises are never suggested. Disliked exercises are allowed only when a necessity check unlocks them: no non-disliked option trains the muscle directly. Even then they carry a 0.6 value multiplier.

(6) Ordering: challenge and skill work first, then compound before isolation, then the main lift for an 'improve' muscle first, then heavier spinal load first (Nunes 2021). Supersets only pair exercises with non-overlapping muscles (Zhang 2025).

(7) When equipment is occupied: first offer to reorder, then a substitute ranked by 0.60 x cosine similarity + 0.15 x pattern match + 0.15 x preference + 0.10 x setup speed.

(8) Rotation: lock main lifts for one mesocycle (5 weeks, aligned with the OAP challenge blocks). Rotate at most a third of accessories, and only systematically: stalled or disliked ones (Kassiano 2022).

(9) One exercise covers assisted, bodyweight and weighted modes through a single effective load: L_eff = f_bw x BW + added - k x assist. This keeps one continuous progression curve, which matters for pull-ups and the OAP challenge.

A JavaScript prototype in the scratchpad (sel.js) reproduced sensible legs and push plans with these constants.

## FINDINGS
### Dataset: yuhonas/free-exercise-db [engineering-fact]
License: Unlicense (public domain). Derived from wrkout/exercises.json (Ollie Jennings, also Unlicense). dist/exercises.json fetched 2026-10-07 has 876 entries; the README says 800+. Fields: id, name, force{static,pull,push,null}, level{beginner,intermediate,expert}, mechanic{isolation,compound,null}, equipment (13 values: body only, machine, other, foam roll, kettlebells, dumbbell, cable, barbell, bands, medicine ball, exercise ball, e-z curl bar, null), primaryMuscles/secondaryMuscles (17 values: abdominals, abductors, adductors, biceps, calves, chest, forearms, glutes, hamstrings, lats, lower back, middle back, neck, quadriceps, shoulders, traps, triceps), instructions[], category (strength 584, stretching 123, plyometrics 61, powerlifting 38, olympic 35, strongman 21, cardio 14), images[] (about 2,620 files). Weaknesses: 'shoulders' lumps front, side and rear delts together; some muscle mappings are wrong vs trials (Leg Press lists hamstrings and calves as secondary, contradicting Kubo 2019); no Dutch names. The origin of the images is undocumented. Use it only for instruction text and cross-reference IDs; don't redistribute the images.
SRC: https://github.com/yuhonas/free-exercise-db | https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/schema.json | https://github.com/wrkout/exercises.json

### Dataset: wger [engineering-fact]
Code is AGPL-3.0. Exercise data is licensed per entry; of 918 entries fetched via /api/v2/exerciseinfo on 2026-10-07: CC-BY-SA 4 = 765, CC-BY-SA 3 = 132, CC0 = 21. Share-alike plus attribution apply to adapted data. Only 15 muscles (anterior deltoid only, no side or rear delts). 157 of 918 have no primary muscle; 276 have images; 49 have Dutch translations. Labels are inconsistent (e.g. 'Side Lateral Raise (Cable)' primary = Lats; face pull primary = Lats). 12 equipment types, 8 categories, 5 license types. The REST API is public, but depending on it at runtime adds a network dependency and CC-BY-SA obligations. Not recommended as the source of muscle coefficients.
SRC: https://github.com/wger-project/wger | https://wger.de/api/v2/exerciseinfo/ | https://wger.de/api/v2/muscle/ | https://wger.de/api/v2/license/

### Dataset: ExerciseDB (now AscendAPI) [engineering-fact]
Repo code is AGPL-3.0. Paid tier: 11,000+ exercises with videos, images and GIFs. Free V1: 1,500 exercises with GIFs, no API key. The terms for media and data are not stated on the free page, and the project markets commercial plans. Licensing is unclear, it needs the network and the GIFs are heavy, so reject it.
SRC: https://github.com/ExerciseDB/exercisedb-api | https://oss.exercisedb.dev/

### Seed strategy [expert-opinion]
Hand-curate about 150 exercises in a repo JSON seed (catalog_version semver), upserted to Supabase. Proposed count per category: legs/quads compounds about 14, hinge/hamstrings about 10, glutes/abductors/adductors about 9, calves about 5, chest about 15, shoulders about 14, back about 22, arms about 19, forearms/grip/prehab about 11, core about 11, OAP-challenge-specific about 9 (assisted one-arm pull-up, one-arm top hold, 90-degree hold, straight-arm hold, weighted negative, one-arm scapular pull, one-arm dead hang, archer and typewriter flagged as needing a wide bar), warm-up about 2. Each entry gets our own region coefficients with an evidence note, attributes, EN and NL names, and an optional fedb_id. User custom exercises are created from pattern prototypes. CI tests check the catalog: every group has at least 3 exercises at Basic-Fit including at least one at coefficient 1.0 and at least two equipment classes; every non-skill exercise has a substitute with similarity of at least 0.6; coefficients only take the values {0, 0.25, 0.5, 0.75, 1}.
SRC: design recommendation; see dataset findings

### EMG cannot set stimulus coefficients [strong]
Acute surface EMG amplitude is not a validated predictor of hypertrophy (Vigotsky 2022, Sports Med 52:193-199). A concrete example: the hip thrust showed higher glute EMG than the squat, yet glute growth was similar (Plotkin 2023). So coefficients come from training studies, the fractional set rule and joint mechanics, not EMG rankings.
SRC: Vigotsky AD, Halperin I, Trajano GS, Vieira TM 2022 Sports Med 52(2):193-199 | Plotkin D et al. 2023 Front Physiol 10.3389/fphys.2023.1279170

### Fractional set counting (direct 1.0, indirect 0.5) [strong]
Pelland, Remmert, Robinson, Hinson, Zourdos: dose-response meta-regressions over 67 studies and 2,058 participants. Of the ways to count indirect sets (total = 1, fractional = 0.5, direct only = 0), fractional had the strongest relative evidence. Volume shows diminishing returns for hypertrophy, and more pronounced ones for strength. Frequency matters for strength but its effect on hypertrophy is compatible with negligible. Published in Sports Med 2026;56(2):481-505 (SportRxiv 2024 preprint). This supports the 0.5 synergist level. The 0.25 and 0.75 levels are our own interpolations.
SRC: https://sportrxiv.org/index.php/server/preprint/view/460 | Pelland JC et al. Sports Med 2026;56(2):481-505

### Compound lifts give synergists about half the direct stimulus [moderate]
Mannarino 2021, untrained men, 8 weeks: biceps thickness grew 11.06% with dumbbell curls vs 5.16% with dumbbell rows, a ratio of about 0.47. This supports row/pulldown to biceps = 0.5. Lat pulldowns can match curls in untrained people (Gentil 2015), so 0.5 is conservative for trained users.
SRC: Mannarino P et al. 2021 Asian J Sports Med (Single-joint exercise results in higher hypertrophy of elbow flexors than multi-joint) | Gentil P, Soares S, Bottaro M 2015 Asian J Sports Med

### Squat and leg press muscle profile [moderate]
Kubo 2019: full squat (140 degrees of knee flexion) vs half squat (90 degrees), 10 weeks. Quads grew similarly; hamstrings and rectus femoris did not grow in either group. Glutes grew +6.7% (full) vs +2.2% (half); adductors +6.2% vs +2.7%. Rules: squat-pattern exercises give hamstrings 0 and rectus femoris 0.25; deep barbell squat gives glutes and adductors 0.75, leg press and hack squat give glutes and adductors 0.5 (expert extrapolation). Plotkin 2023: hip thrust and squat gave similar glute growth; the squat gave more quad and adductor growth. Rules: hip thrust gives glutes 1.0, quads 0.25.
SRC: Kubo K et al. 2019 Eur J Appl Physiol (squat depth) | https://www.strongerbyscience.com/squat-depth/ | Plotkin D et al. 2023 Front Physiol 10.3389/fphys.2023.1279170

### Long-length (stretched-position) emphasis [moderate]
- Maeo 2021 (MSSE 53(4):825-837): seated leg curl vs prone, whole hamstrings +14% vs +9%; biceps femoris long head +14.4% vs +6.5%.
- Maeo 2023 (Eur J Sport Sci): overhead triceps extension gave about 1.4x the triceps growth of pushdowns.
- Pedrosa 2022: leg extension partials at long length (100-65 degrees of knee flexion) grew the distal quads more than full ROM.
- Kassiano 2023: calf raises in the initial (stretched) partial ROM, medial gastrocnemius +15.2% vs full ROM +6.7% vs final ROM +3.4%.
- Kinoshita 2023: standing calf raise beat seated; the gastrocnemius did not grow with seated raises, soleus growth was similar.
- Wolf 2023 meta: full vs partial ROM trivial (SMD 0.12, 95% CI -0.02 to 0.26); long-length partials vs full ROM -0.28 (CI -0.81 to 0.16), i.e. leaning toward long-length partials.
- Wolf 2025 systematic review (8 studies, 120 participants): training at longer muscle lengths consistently gave more hypertrophy, mostly at distal quad sites; study quality mostly fair or poor.

App use: stretch_emphasis 0-2 adds a small quality bonus (+0.05 per level); region definitions (triceps_long, hams_biarticular, gastrocnemius) earn a variety bonus.
SRC: https://pubmed.ncbi.nlm.nih.gov/33009197/ | Maeo S et al. 2023 Eur J Sport Sci (overhead vs neutral triceps) | Pedrosa GF et al. 2022 Eur J Sport Sci | https://pubmed.ncbi.nlm.nih.gov/37015016/ | https://pmc.ncbi.nlm.nih.gov/articles/PMC10753835 | https://doi.org/10.47206/ijsc.v3i1.182 | https://pmc.ncbi.nlm.nih.gov/articles/PMC12869050

### Regional hypertrophy justifies region-level variety [moderate]
- Chaves 2020 (IJES): incline press grew the upper (clavicular) chest most.
- Larsen 2024: rectus femoris grew about 50-170% more in leg extensions at 40 degrees of hip flexion than at 90 degrees.
- Ema 2013: knee extension training targets the rectus femoris, which squats barely grow (Kubo 2019).
- Fonseca 2014: varied lower-body exercises grew all four quad heads; squat-only missed vastus medialis and rectus femoris.
SRC: https://pmc.ncbi.nlm.nih.gov/articles/PMC7449336 | Larsen S et al. 2024 (hip flexion angle, leg extension) SportRxiv 10.51224/SRXIV.407 | Ema R et al. 2013 Eur J Appl Physiol | Fonseca RM et al. 2014 J Strength Cond Res

### Exercise variation and rotation [moderate]
Kassiano 2022 systematic review (8 studies, 241 men): systematic variation can improve regional hypertrophy and maximal dynamic strength; excessive or random variation, and redundant exercises, may hurt gains. Baz-Valle 2019: exercises randomized by an app gave similar gains and better motivation in trained men. App policy: lock main lifts for a mesocycle and rotate only up to a third of accessories, chosen systematically (stalled, disliked, or adding new region coverage). Use no randomness; prefer region-gain rotation.
SRC: Kassiano W et al. 2022 J Strength Cond Res (Does varying resistance exercises promote superior muscle hypertrophy and strength gains?) | https://pmc.ncbi.nlm.nih.gov/articles/PMC6934277

### Exercise order [strong]
Nunes 2021 meta (11 studies, European Journal of Sport Science): strength gains are largest for exercises done first in the session; hypertrophy is similar regardless of order. Simão 2012 (Sports Med) agrees: order mainly affects reps on later exercises. Rules: skill and challenge work first; the main lift for an 'improve' goal first; order is free for hypertrophy-only accessories.
SRC: Nunes JP et al. 2021 Eur J Sport Sci (exercise order meta) | Simao R et al. 2012 Sports Med 42(3):251-265

### Supersets save time [strong]
Zhang, Weakley, Li, Li, Garcia-Ramos 2025 Sports Med (19 studies, 313 participants): supersets cut session time without hurting volume or chronic gains in hypertrophy, strength or strength endurance. Agonist-antagonist supersets allowed more reps (SMD 0.68); supersets of biomechanically similar exercises reduced volume load (SMD -1.08); supersets raise perceived exertion and muscle damage. Rule: pair only exercises whose stimulus vectors have cosine similarity of 0.2 or less, and only when the user allows it or the plan runs over the time budget. Iversen 2021 (Sports Med) covers time-efficient designs in general.
SRC: https://eprints.leedsbeckett.ac.uk/id/eprint/11908 | Iversen VM, Norum M, Schoenfeld BJ, Fimland MS 2021 Sports Med 51:2079-2095

### Machines vs free weights [strong]
Haugen 2023 meta (13 studies, 1,016 participants): no difference in hypertrophy; strength is specific to the modality tested. So machines and cables (high SFR, fast setup, low skill) cost nothing for hypertrophy. Exercises that challenge criteria test directly (e.g. weighted pull-ups) stay specific.
SRC: Haugen ME et al. 2023 BMC Sports Sci Med Rehabil (free-weight vs machine)

### Bodyweight load fractions [moderate]
Push-up variations as % of body mass (Ebben 2011): regular 64%, feet on 30.48 cm box 70%, feet on 60.96 cm box 74%, hands on 30.48 cm box 55%, hands on 60.96 cm box 41%, knees 49%. Pull-up, chin-up and dip: the hands and forearms stay roughly fixed, so effective load is about 0.955 x BW (de Leva 1996 male segment masses: hand 0.61% + forearm 1.62% per side, x2 = 4.46%). The inverted row fraction (about 0.6) is unverified expert opinion. Combined with dip belt and assisted modes, this gives a single effective load per exercise.
SRC: https://ojs.ub.uni-konstanz.de/cpa/article/view/4457 | de Leva P 1996 J Biomech 29(9):1223-1230

### Isometric holds count toward volume [weak]
Isometric training does produce hypertrophy, more so at long muscle lengths and high intent (Oranchuk 2019 review). The OAP challenge has many timed holds (top hold, 90-degree hold, straight-arm hold, dead hang). Proposal: a hold set of at least 10 s at near-max intent counts 0.5 x coefficient as a fractional set; shorter or submaximal holds count 0.25. These numbers are expert opinion and can be tuned.
SRC: Oranchuk DJ, Storey AG, Nelson AR, Cronin JB 2019 Scand J Med Sci Sports 29(4):484-503

### Maintenance dose [moderate]
Strength and hypertrophy can be maintained with about one session per week and reduced volume (about a third, or 1 set per exercise in young people) if intensity is kept (Spiering 2021). The volume-planner lens supplies the targets. In selection, maintain goals get weight 0.6 vs 1.0 for improve.
SRC: Spiering BA, Mujika I, Sharp MA, Foulis SA 2021 J Strength Cond Res 35(5):1449-1458

### Muscle taxonomy recommendation [expert-opinion]
Two levels.

Groups (18, user goals and volume): chest, front_delts, side_delts, rear_delts, lats, upper_back (mid/lower traps, rhomboids), upper_traps, biceps (elbow flexors), triceps, forearms (grip, wrist, pronation/supination), abs (rectus + obliques), lower_back, glutes, abductors (glute med/min), adductors, quads, hamstrings, calves. Optional: neck. Prehab-only: rotator_cuff, serratus, tibialis.

Regions (about 32, soft variety bonus only): chest_clavicular, chest_sternal, delt_anterior, delt_lateral, delt_posterior, lats, traps_mid, traps_lower, rhomboids, traps_upper, biceps_brachii, brachialis, brachioradialis, triceps_long, triceps_lat_med, wrist_flexors_grip, wrist_extensors, pronators_supinators, rectus_abdominis, obliques, anti_rotation, erectors, glute_max, glute_med_min, adductors, vasti, rectus_femoris, hams_biarticular, bf_short_head, gastrocnemius, soleus, rotator_cuff_ER, serratus_anterior, tibialis_anterior.

Tradeoff: coarse (free-exercise-db 17, wger 15) misses side vs rear delts and the OAP-relevant forearm and rotator-cuff detail. Fully fine taxonomies need guessed coefficients and sparse data. Hard constraints at group level with soft region bonuses gets both. The UI groups goals as Push / Pull / Legs / Core with bulk toggles (legs only, no legs, pull focus).
SRC: design synthesis of the regional findings above

### SFR, stability, axial load, setup time [expert-opinion]
There are no trials of stimulus-to-fatigue ratio per exercise; it is coach heuristics (Renaissance Periodization / Israetel; Nuckols). Default scale 1-5: machine or cable isolation 5; machine compound 4-5; dumbbell compound 4; barbell compound 3; heavy hinge or deadlift 2; unilateral free weight 3. Axial load 0-3 (machines and supported positions 0; standing DB or hip thrust 1; RDL or barbell row 2; back squat, deadlift, standing OHP 3). Stability 1-3, skill 1-3. Setup defaults: pin machine 30-45 s, plate-loaded machine 90 s, barbell in rack 150 s, Smith 90-120 s, dumbbells 30-60 s, cable attachment change 45 s, dip belt +60 s. All tunable from logged timestamps.
SRC: Israetel M, Hoffmann J, Smith CW: Scientific Principles of Hypertrophy Training (RP) - expert | https://www.strongerbyscience.com/free-weights-vs-machines/

### Preference and autonomy [weak]
Direct evidence that choosing preferred exercises improves hypertrophy is weak. Randomized variation and self-selected order show similar or trivially different performance with better motivation or enjoyment (Baz-Valle 2019; PeerJ 2020 self-selected exercise order in youth hockey players). Adherence is the plausible mechanism. So preference should be a substantial modifier (disliked x0.6, favorite 5-star x1.495), but never override muscle coverage. A disliked exercise needs about 1.67x the useful coverage of a neutral one to be picked.
SRC: https://pmc.ncbi.nlm.nih.gov/articles/PMC6934277 | https://peerj.com/articles/10361

### Solver choice [engineering-fact]
Exercise selection is a weighted budgeted set cover (NP-hard; greedy gives an ln(n) approximation, Chvatal 1979). For at most 25 filtered candidates and at most 8 exercises, a deterministic beam search (width 12) costs about 2,400 objective evaluations, which takes milliseconds on a phone, with no solver dependency. Check it against exhaustive search on small golden fixtures in unit tests. Break every tie by id, ascending; use no RNG.
SRC: Chvatal V 1979 Math Oper Res 4(3):233-235

### Basic-Fit Amersfoort Ierse Pond facts [engineering-fact]
Basic-Fit's site: Ierse Pond 2 (Vathorst), more than 1,100 m2. Zones: strength, cardio, free weight, functional, stretch, virtual cycling. Hours Mon-Fri 07:00-22:30, Sat-Sun 09:00-16:00. The machine list and brand could not be verified online. The app therefore needs a one-time gym inventory checklist (stations plus dumbbell, plate and stack increments), with 'requires_station' on every exercise. From the user: assisted machine pin moves in 5-lb steps; the pull-up bar is too narrow for archer or typewriter; machines are sometimes occupied; the user brings a dip belt.
SRC: https://www.basic-fit.com/nl-nl/clubs/basic-fit-amersfoort-ierse-pond-b628ec024312459389bce3e9bcf24022.html | user context

### Prototype check: worked examples [engineering-fact]
I implemented SEL-1, ORD-1 and SUB-1 in a scratch prototype (not in the project; scratchpad sel.js, sel2.js, ord.js) and ran these cases.

LEGS. Targets: quads 6, glutes 5, hamstrings 6 (improve); adductors 2, abductors 2, calves 3 (maintain). Budget 65 min, preferred 3 sets. Leg press is favorite 5-star; walking lunge is 1-star (disliked). Ordered output:
- Leg press 45 degrees x3
- Barbell RDL x2
- Smith hip thrust x2
- Leg extension x4 (rectus femoris region)
- Seated leg curl x4 (long-length hamstrings)
- Hip abduction machine x2
- Calf press on leg press x3

That is 20 sets in about 50 min plus a 10-minute warm-up. Coverage: quads 7.5, glutes 5, hamstrings 6.5, adductors 2.5, abductors 2, calves 3. Lunges were never used.

LEGS variants:
- Hip thrust occupied plus a low-back flag (spinal load 2 or more excluded): Bulgarian split squat (neutral) x2 and 45-degree back extension x4 replace RDL and hip thrust.
- Bulgarian split squat also disliked: it is unlocked as the only direct glute option, but the optimizer still prefers leg press plus back extension (glutes 4.75 of 5) and leaves it out.
- Adductors set to improve, adduction machine disliked, low-back flag on: the adduction machine is unlocked and used for 2 sets, labelled as needed for adductors.

PUSH. Targets: chest 6, side delts 4, triceps 4 (improve); front delts 2 (maintain). Output:
- Incline DB press x3
- Machine chest press x3
- DB lateral raise x4
- Overhead cable triceps extension x2

That is 12 sets in 27.6 min. Shoulder press and pec deck were excluded because pressing already gives front delts 3 fractional sets and chest is at target. If the chest press and pec deck are occupied, DB bench x3 replaces the chest press. Substitute ranking for an occupied chest press: DB bench 0.934, barbell bench 0.884, dips 0.85. For an occupied leg press: hack squat 0.884, Bulgarian split squat 0.80, back squat 0.739.
SRC: C:\Users\sivuc\AppData\Local\Temp\claude\C--Users-sivuc-projects-FitnessApp\23e9a33b-aa17-4d4a-80e1-fef330676224\scratchpad\sel.js

## ALGORITHMS
### CAT-1 Catalog model and stimulus-coefficient assignment (v1.0)
PURPOSE: Store every exercise with deterministic attributes and region-level stimulus coefficients that all other algorithms use (volume counting, selection, substitution, rotation).
IN: Curated seed JSON (catalog_version), pattern prototypes, user custom exercises (pattern + equipment + optional edits), gym inventory.
OUT: Tables: exercise; exercise_stimulus(exercise_id, region, coeff, note); derived group vector; validation report.
PSEUDO:
REGION_COEFF levels L = {1.0 prime mover through ROM and likely set-limiter; 0.75 strong synergist (override only, cite evidence); 0.5 dynamic synergist (Pelland fractional); 0.25 minor dynamic or isometric stabilizer; 0}
group_coeff(e,g) = max over regions r in g of coeff(e,r)   # max, not sum, so no double counting
fractional_sets(e,g,set) = group_coeff(e,g) * hold_factor(set) * 1   # 1 per-side set of a unilateral exercise = 1 set
hold_factor = 1 for dynamic; 0.5 for timed_hold >=10 s at near-max intent; 0.25 otherwise
EVIDENCE OVERRIDES (seed notes):
  squat/leg press/hack: vasti 1.0, rectus_femoris 0.25, hamstrings 0, glute_max 0.5 (deep barbell squat 0.75), adductors 0.5 (deep barbell squat 0.75)  [Kubo 2019]
  hip thrust: glute_max 1.0, quads 0.25, hamstrings 0.25  [Plotkin 2023]
  leg extension: vasti 1.0, rectus_femoris 1.0  [Ema 2013; Larsen 2024]
  seated leg curl: hams_biarticular 1.0, bf_short_head 1.0, stretch 2; lying curl: same coefficients, stretch 1  [Maeo 2021]
  RDL: hams_biarticular 1.0, glute_max 0.5, adductors 0.5, erectors 0.5, grip 0.5, stretch 2
  rows/pulldowns/pull-ups: lats 1.0 (rows: traps_mid/rhomboids 1.0, lats 0.5), biceps_brachii 0.5, brachialis 0.5, delt_posterior 0.25-0.5, grip 0.25-0.5  [Mannarino 2021]
  presses: chest 1.0, delt_anterior 0.5, triceps 0.5; incline: chest_clavicular 1.0, chest_sternal 0.75  [Chaves 2020]
  overhead triceps ext: triceps_long 1.0, stretch 2; pushdown: triceps_lat_med 1.0, triceps_long 0.75  [Maeo 2023]
  standing calf: gastrocnemius 1.0, soleus 1.0; seated calf: soleus 1.0, gastrocnemius 0.25  [Kinoshita 2023]
ATTRIBUTES per exercise: pattern (about 35 values: squat, squat_machine, lunge, hinge, hip_ext, knee_ext, knee_flex, hip_abd, hip_add, calf, horiz_push, incline_push, vert_push, dip, chest_fly, lateral_raise, rear_delt_fly, horiz_pull, vert_pull, straight_arm_pull, shrug, elbow_flex, elbow_ext, elbow_ext_overhead, wrist_flex, wrist_ext, forearm_rot, grip_hold, ext_rotation, core_flex, core_anti_ext, core_anti_rot, hang, skill_static, carry); family (pattern groups for substitution); equipment[]; requires_station[]; load_type; bw_fraction; laterality; increment_profile; stretch_emphasis 0-2; stability 1-3; skill 1-3; axial 0-3; systemic 1-3; sfr 1-5; setup_s; rep_class; joint_stress{elbow_medial, elbow_lateral, shoulder_ant, shoulder_overhead, wrist, lumbar, knee, hip} 0-3; grip_demand 0-3; tags{challenge, prehab, skill}; names{en,nl}; external_refs{fedb_id, wger_uuid}; evidence_note
CUSTOM: new_exercise = copy(pattern_prototype[pattern][equipment_class]); user may edit coefficients (advanced); custom=true
VALIDATE (CI + on save): coeff in L; at least one region >= 0.75 unless tag in {prehab, skill, hang}; for each group, at Basic-Fit default inventory: at least 3 exercises, at least 1 with group_coeff 1.0, at least 2 equipment classes; each non-skill exercise has a substitute with sim >= 0.6
CONST: Levels {1.0, 0.75, 0.5, 0.25, 0}: 0.5 from Pelland 2026, the rest interpolation (expert). Hold factor 0.5 / 0.25, 10 s threshold (expert, from Oranchuk 2019). SFR defaults: machine/cable isolation 5, machine compound 4-5, DB compound 4, barbell compound 3, heavy hinge 2, unilateral free weight 3. Axial defaults as in findings. Setup defaults: pin 30-45 s, plate machine 90 s, rack barbell 150 s, Smith 90-120 s, DB 30-60 s, cable attachment 45 s, dip belt +60 s.
LOG: catalog_version and algo_version on every plan and session; history of coefficient changes with reason; custom exercise definitions; per-exercise e1RM and rep trends (to spot bad coefficients, e.g. a muscle whose 'credited' volume is high but which never progresses); the user's region goals.

### LOAD-1 Effective load and loadable increments (v1.0)
PURPOSE: One continuous load scale across assisted, bodyweight and weighted modes, and rounding of any target to a weight that can actually be loaded.
IN: Exercise load_type and bw_fraction; today's bodyweight (latest log, else profile 70.7 kg); set entry (load in native unit, mode); gym inventory increment profiles; k_assist calibration.
OUT: L_eff (kg) per set for progression models; nearest loadable value in native units for display.
PSEUDO:
L_eff(e,x):
  external: x.load_kg (dumbbells stored per hand; volume load x2 if bilateral DB)
  bodyweight: f_bw*BW
  bodyweight_plus: f_bw*BW + x.added_kg
  assisted: f_bw*BW - k_assist*assist_kg, where assist_kg = pin_lb*0.45359237 when the stack is in lb
  one-arm variants (OAP): per-arm L_eff = f_bw*BW - k_assist*assist_kg (+ added)
  timed_hold: primary metric seconds; L_eff as above if loaded or assisted
One exercise holds modes [assisted, bodyweight, weighted]; mode switches keep a single e1RM series on L_eff
loadable_set(profile):
  barbell: bar_kg + 2*sum(multiset of plate pairs from inventory {25,20,15,10,5,2.5,1.25})
  smith: smith_bar_kg (user-calibrated, default unknown so ask) + plates
  dumbbell: inventory list
  stack: {unit kg|lb|level, start, step, max, add_on_step?} stored natively, optional kg_factor
  assisted: lb, start 0, step 5
nearest_loadable(target, set, mode='down'): c = max{L in set : L <= target*1.02}; if none, c = min(set); mode 'nearest' = argmin |L-target|, ties to the lower value
CONST: f_bw: pull-up/chin-up/dip/OAP 0.955 (de Leva 1996); push-up 0.64, feet on 30 cm 0.70, feet on 61 cm 0.74, knees 0.49, hands on 30 cm 0.55, hands on 61 cm 0.41 (Ebben 2011); inverted row 0.60 (expert, unverified). k_assist default 1.0 (calibrate per machine; some have lever ratios). lb-to-kg 0.45359237. Rounding tolerance 2%.
LOG: Per set: native load and unit, mode, L_eff, bodyweight used, station id. Per machine: user-entered calibration. Reps achieved vs predicted after mode switches (to fit k_assist and bw_fraction per user).

### PREF-1 Preference model (v1.0)
PURPOSE: Turn explicit ratings and favorites, plus implicit behaviour, into a value multiplier and a tier, without ever overriding muscle needs.
IN: rating r in {1..5 | null}, favorite bool, banned bool, unavailable_until; events: manual_add, swap_away (with reason), skip (with reason), completion.
OUT: m_e (multiplier), tier in {BANNED, DISLIKED, NEUTRAL, LIKED, FAVORITE}, prompts.
PSEUDO:
if banned: tier=BANNED; m=0   # never auto-suggested or unlocked; manual add in session still allowed
base = {1:0.60, 2:0.85, 3:1.00, null:1.00, 4:1.15, 5:1.30}[r]
if favorite: base *= 1.15 (favorite with r null: base = 1.15*1.15)
tier = DISLIKED if r == 1, else by base
implicit (only when r is null): z = sum over events of w_ev * 0.5^(age_days/56); w: manual_add +0.10, swap_away without external reason (not occupied/equipment/injury) -0.15, skip with reason 'dont_like' -0.25; z = clamp(z, -0.3, +0.3); m = base*(1+z)
if r not null and >= 3 swap_aways without external reason in 42 d: prompt 'Lower rating of X?' (never auto-change)
'apply to similar': for e' with the same pattern and cos(v_e, v_e') >= 0.9, set the same rating (user-confirmed)
unavailable_until: hidden from the pool until the date, not a preference
CONST: Multipliers 0.60/0.85/1.00/1.15/1.30, favorite x1.15 (max 1.495): a disliked exercise needs >= 1.67x the useful coverage of a neutral one. Implicit weights 0.10/-0.15/-0.25, half-life 56 d, clamp ±0.3, prompt threshold 3 events in 42 d. All expert opinion; tune from logs.
LOG: Rating changes with timestamps; every swap and skip with reason; plan edits (removed, added); whether unlocked disliked exercises were done or skipped; prompts shown and accepted.

### ELIG-1 Eligibility filter and necessity gate (v1.0)
PURPOSE: Build the candidate pool. Banned exercises are never used. Disliked exercises are used only when no non-disliked exercise can train a muscle that needs work.
IN: Catalog; gym inventory and stations; unavailable list; goals per group (improve/maintain/skip); today's targets T_g (fractional sets, from the volume lens); PREF-1 tiers; INJ-1 flags; challenge constraints; session type.
OUT: pool (eligible exercises), unlocked set with reason group, COVERAGE_GAP warnings.
PSEUDO:
pool = catalog + custom
remove e if: tier == BANNED; requires_station not in inventory; e in unavailable; INJ-1 excludes it; challenge marks e exclusive (e.g. assisted OAP) and the session is not that challenge's session
remove e if some g has goal_g == skip and group_coeff(e,g) >= 0.5   # incidental 0.25 allowed
liked = {e in pool : tier != DISLIKED}; dis = {e in pool : tier == DISLIKED}
for g sorted by id, with T_g >= 1 and goal_g in {improve, maintain}:
  thr = 0.75 if goal_g == improve else 0.5
  if no e in liked has group_coeff(e,g) >= thr:
    cand = {e in dis : group_coeff(e,g) >= thr and not unlocked_this_week(e) unless weekly_cov_g < 0.5*weekly_target_g}
    if cand: u = first by (group_coeff desc, sfr desc, id asc); unlock u; reason[u] = g
    else if no e in pool has group_coeff >= thr: warn COVERAGE_GAP(g)
return liked + unlocked   # unlocked keep m = 0.6 in SEL-1, so they are used only if they help
CONST: Direct threshold 0.75 for improve, 0.5 for maintain; incidental tolerance 0.25 for skip groups; each disliked exercise unlocked at most once per week unless the group is below 50% of its weekly target.
LOG: Unlock events (exercise, group, residual), whether used, and user reaction (done / skipped / swapped); COVERAGE_GAP warnings; filtered-out counts by reason.

### SEL-1 Session exercise selection, beam search plus set tuning (v1.0)
PURPOSE: Pick exercises and sets for today's session that meet per-muscle targets, respect preferences, time, injuries and challenge blocks, deterministically and explainably.
IN: ELIG-1 pool; T_g and goal_g; region_needs_week (regions of improve groups not yet covered this week); budget_min; s_pref (user's preferred sets, e.g. 3; the 3x8-10 vs 2x12 example); pinned blocks (challenge exercises with their sets, plus user must-includes); mesocycle anchors; stall flags; history; PREF-1 m_e; INJ-1 and challenge penalties; TIME-1 model.
OUT: Ordered plan [(exercise, sets)] via ORD-1, plus an explanation per exercise (useful-set credit, share, multiplier, unlock reason, region bonus) and objective breakdown.
PSEUDO:
S0 = pinned + eligible anchors (their own sets; count toward coverage)
alloc(e,R): need = max over g with c >= 0.75 of R_g/c (fallback: any c > 0); s0 = clamp(s_pref, 2, smax(e)); return clamp(min(s0, ceil(need - 0.25)), 2, smax(e)); smax = 5 compound, 4 isolation
J(S):
  cov_g = sum over e of c_eg*s_e*hold_factor
  useful_g = min(cov_g, T_g) + 0.25*clip(cov_g - T_g, 0, 0.25*T_g)
  share_g = useful_g/cov_g (0 if cov_g == 0 or goal_g == skip)
  B_e = sum over g of w(goal_g)*c_eg*s_e*share_g; w: improve 1.0, maintain 0.6
  q_e = 0.05*stretch_e + 0.04*(sfr_e - 3)
  V = sum over e of B_e*m_e*(1 + q_e)
  O = sum over g of max(0, cov_g - max(1.25*T_g, T_g + 1)) + 3*sum over skip groups of cov_g
  RB = 0.3 * #{r in region_needs_week covered by some e in S with coeff >= 0.5}
  K = 0.5*#{e in S used in this session type this mesocycle} - 0.5*#{e in S stalled}
  X = 0.3*max(0, sum of axial_e - 4) + INJ-1 penalties + challenge penalties
  J = V - 0.5*O + RB + K - X - 0.05*t(S) - 0.5*max(0, t(S) - budget)
topK = 25 best e in pool by J(S0 + (e, alloc(e, T))), ties by id asc
beam = [S0]; best = S0
repeat up to maxEx - |S0| times:
  next = []
  for S in beam: R = max(0, T - cov(S))
    for e in topK not in S:
      skip if S already has 2 exercises with e.pattern
      skip if some x in S has x.pattern == e.pattern and cos(v_x, v_e) >= 0.95
      S' = S + (e, alloc(e, R)); skip if t(S') > 1.10*budget
      add S' (dedupe by sorted id key)
  beam = top 12 of next by (J desc, key asc); if beam empty break; if J(beam[0]) > J(best): best = beam[0]
TUNE: up to 20 passes, exercises in id order: try s +1 then -1 within [max(2, s_pref-1), min(smax, s_pref+1)]; accept if dJ > 0.05
TIME_FIT: while t > budget: remove 1 set (not below 2, not from pinned) with smallest dJ per minute; if none possible, drop the non-pinned exercise with the smallest dJ
return ORD-1(best)
# Sets above s_pref + 1 or below s_pref - 1 only via the prescription lens's research override, shown as a warning
CONST: Goal weights improve 1.0 / maintain 0.6; over-target credit 0.25 up to 1.25*T; overshoot slack max(1.25T, T+1), lambda_over 0.5; skip penalty 3/set; stretch bonus 0.05/level; SFR 0.04/point; region bonus 0.3; consistency +0.5, stall -0.5; axial cap 4, 0.3 per unit; time cost 0.05/min, overrun 0.5/min, hard cap 1.10*budget; beam 12; topK 25; maxEx 8 (legs default 7); sMin 2; sMax 5 compound / 4 isolation; dedupe cosine 0.95; max 2 per pattern. Per-session ceiling: implicit via 1.25*T; an expert guideline of about 6-10 hard sets per muscle per session (weak evidence) is an optional constant per_session_cap_g = 10. All expert, tuned by prototype.
LOG: Full plan record: inputs (T_g, goals, budget, pinned, anchors), top 10 candidates with single scores, the chosen set with per-exercise B_e/share/m/q, J terms (V, O, RB, K, X, time), algo_version, constants hash; user edits before and during the session (add, remove, swap, set changes and reasons); actual sets done vs planned; time used vs predicted; later muscle-group progress per week.

### ORD-1 Exercise ordering and optional superset pairing (v1.0)
PURPOSE: Order the selected exercises: skill and challenge first, main lifts for improve goals when fresh, no pre-fatigue of synergists; optionally pair exercises to save time.
IN: Selected plan S with sets, goals, exercise attributes (class, axial, skill, grip_demand, station, zone), rest times (rest lens), user setting allow_supersets, budget.
OUT: Ordered list, possibly with superset pairs; predicted time.
PSEUDO:
key(e) = (pinned_rank (challenge or skill blocks first, in the order the challenge defines; else 99),
          class (0 multi-joint, 1 single-joint, 2 core/prehab/conditioning),
          -(sum over improve groups g of c_eg*s_e),
          -axial_e, -skill_e, id)
sort by key (stable)
fix-ups (max 3 passes):
  (a) pre-fatigue: if single-joint e_i comes before compound e_j and the primary group of e_i has c >= 0.5 in e_j, move e_i after e_j
  (b) grip: if grip_demand(e_i) >= 2 comes before a pinned or anchor e_j with grip_demand >= 2, move e_i after e_j
  (c) same-station chaining: if e_j uses e_i's station (e.g. leg press then leg-press calf) and shares no group c >= 0.5 with the exercises in between, place e_j right after e_i and set setup_j = 15 s
supersets (only if allow_supersets or t(S) > budget):
  pairs (a,b): both non-pinned, cos(v_a, v_b) <= 0.2, axial <= 1, skill <= 1, same zone
  dt(a,b) = t(a) + t(b) - t_pair; t_pair = setup_a + setup_b + max(s_a, s_b)*(work_a + 15 + work_b + rest_pair); rest_pair = max(60, max(rest_a, rest_b) - min(work_a, work_b))
  greedily take the pair with the largest dt (ties by id pair) until within budget or no pairs remain
CONST: Inter-exercise transfer 15 s; rest_pair floor 60 s; pairing cosine threshold 0.2 (Zhang 2025: similar-biomechanics supersets lowered volume load, SMD -1.08); grip threshold 2; 3 fix-up passes.
LOG: Final order vs order actually performed (user reorders), superset usage and rep drop on the second exercise, first-exercise performance trends.

### SUB-1 Substitution when occupied, missing or unwanted (v1.0)
PURPOSE: In-gym replacement when equipment is occupied (reason 'occupied'), broken, injured or not wanted: offer a reorder first, then the most similar acceptable exercises.
IN: Exercise e being replaced, reason, current session state (done and remaining), ELIG-1 pool, stations occupied now, PREF-1, INJ-1, history for load transfer.
OUT: Reorder suggestion and up to 3 ranked substitutes with starting sets and load; updated residual targets.
PSEUDO:
if reason == occupied and a not-done, non-pinned exercise k with a different station remains: offer 'Do k now, then come back to e' (k = first by ORD-1 order)
v(x) = normalize(group vector (18 dims) + 0.5*region vector (about 32 dims))
for c in pool not in session and station(c) not occupied:
  sim = cos(v_e, v_c); if sim < 0.60: skip
  pm = 1 if same pattern; 0.5 if same family (horiz_push/incline_push/dip; squat/squat_machine/lunge; hinge/hip_ext; vert_pull/straight_arm_pull; horiz_pull/rear_delt_fly); else 0
  pn = (ln m_c - ln 0.6)/(ln 1.495 - ln 0.6), clamped to [0,1]
  sp = 1 if setup_c <= 60 s; 0.5 if <= 120 s; else 0
  score = 0.60*sim + 0.15*pm + 0.15*pn + 0.10*sp - 0.1*inj_stress_norm
disliked candidates only if no non-disliked candidate has sim >= 0.60; banned never
sort by (score desc, id asc); show top 3 + 'skip (choose reason)' + 'search catalog'
sets(c) = s_e; start load: own history (prescription lens) else transfer ratio r(e->c) from the seed table else calibration (first set aimed at RIR target + 2)
recompute residual R for the remaining plan; log
CONST: Weights 0.60/0.15/0.15/0.10; similarity floor 0.60; region weight 0.5 in the vector; setup thresholds 60/120 s; family table in the seed.
LOG: From, to, reason, rank chosen (1-3 or catalog search), similarity and score of the chosen and shown options, whether the reorder was accepted, performance on the substitute vs the original's prediction, how often each station is occupied by time of day.

### ROT-1 Mesocycle anchors and systematic rotation (v1.0)
PURPOSE: Keep main lifts stable long enough to measure progress, and rotate accessories systematically, not randomly (Kassiano 2022).
IN: Mesocycle calendar (default 5 weeks: 4 build + 1 deload, aligned with OAP blocks if active), SEL-1 results per session type, e1RM and rep trends, ratings, 'bored' flags.
OUT: anchors[session_type] (locked), the accessory keep or rotate list, consistency flags for the K term.
PSEUDO:
at mesocycle start, for each session type tau:
  anchors_tau = top 2 multi-joint exercises from SEL-1(tau) run with K = 0, keeping the previous anchors unless (stalled 2 consecutive mesocycles or banned or injury-excluded)
  for accessory e from the previous mesocycle:
    keep = +1 if e1RM slope >= +0.5%/week (or reps at the same load rising); -1 if stalled (>= 3 consecutive exposures without e1RM or rep PR); -1 if rating <= 2; -0.5 if 'bored'; +0.5 if rating >= 4
  rotate the n = min(#{keep < 0}, ceil(#accessories/3)) lowest-keep accessories (ties by id)
  replacement = SUB-1 ranking + 0.2*region_gain (region not covered in the last mesocycle)
during the mesocycle: K = +0.5 for exercises already used in tau this mesocycle
no RNG; any variety option uses a deterministic hash(week_index, id) order
CONST: Mesocycle 5 weeks (4 + 1 deload); 2 anchors per session type; progress threshold +0.5%/week; stall = 3 exposures; rotation cap of a third of accessories; region gain 0.2.
LOG: Anchor decisions with reasons, keep scores, rotated pairs, progress before and after rotation, user overrides of anchors.

### TIME-1 Session time estimation and calibration (v1.0)
PURPOSE: Predict session duration to fit the time budget (calendar slot or user input), and learn the user's real setup and rest times.
IN: Plan, reps target, rest per exercise (rest lens), setup_s, laterality, warm-up policy, calendar slot length; logged set timestamps.
OUT: Predicted minutes per exercise and total; calibrated per-user parameters.
PSEUDO:
work(e) = reps*tempo + 5 (unilateral: 2*work + 10)
t(e,s) = setup_e + s*(work + rest_e) - rest_e + 60 (transition)
T_session = warmup (10 min general, per the OAP plan) + 2*(work + 60) specific warm-up sets for the first anchor + sum of t(e,s)
budget = calendar_slot - travel_buffer (default 15 min) or user input
calibrate after >= 3 exposures: setup_e = EWMA_0.3(first set start - previous exercise end); tempo_user = EWMA_0.3(set duration/reps); rest_user_ratio = EWMA_0.3(actual rest/prescribed rest), used in predictions only
CONST: Tempo 3 s/rep; +5 s per set; transition 60 s; unilateral switch 10 s; general warm-up 10 min; travel buffer 15 min; EWMA alpha 0.3; default rest from the rest lens (about 180 s heavy compound, 150 compound, 90 isolation, 60 small isolation/core).
LOG: Timestamps for session start/end, exercise start/end, and each set start/end; timer overrides; predicted vs actual minutes.

### INJ-1 Joint-stress filter and challenge constraints (v1.0)
PURPOSE: Keep selection safe and compatible with active challenges (e.g. OAP elbow protection, grip freshness before heavy one-arm days), driven by data, not hardcoded.
IN: Injury flags {joint, severity 1-3, since, until}; exercise joint_stress and grip_demand; challenge constraint list from the active challenge definition; this week's challenge schedule.
OUT: Exclusions and per-set penalties for ELIG-1 and SEL-1; warnings.
PSEUDO:
for each flag (joint j, severity v):
  v = 3: exclude e if stress_j(e) >= 1
  v = 2: exclude e if stress_j(e) >= 2
  v = 1: penalty 0.3*stress_j(e)*s_e in J
challenge constraints (JSON in the challenge definition), e.g.
  {type:'window', tag:'grip_demand>=2', before_sessions:['A','B'], window_h:24, action:'penalize', per_set:0.5}
  {type:'weekly_cap', tag:'unilateral_heavy_pull', max_sessions:3}
  {type:'weekly_cap', tag:'elbow_flexor_heavy', max_sets:N}
  {type:'protocol', trigger:'elbow_pain', effect:'exclude tag unilateral for 14 d; light bilateral only'}
apply windows using the planned calendar (Google Calendar plus app plan); caps count challenge sets plus planned sets
CONST: Severity thresholds 1/2; penalty 0.3 per stress unit per set; window 24 h, 0.5 per set; caps come from the challenge (OAP: max 3 heavy unilateral sessions per week).
LOG: Flag history; exclusions and penalties applied; pain reports per session (joint, 0-10); constraint violations the user overrode.

## DESIGN OPTIONS
- Seed exercise data source: [Import free-exercise-db wholesale (876 entries, Unlicense, coarse and partly wrong muscles) / Use the wger API live or as an import (918 entries, CC-BY-SA, inconsistent muscles, 49 Dutch) / ExerciseDB/AscendAPI (commercial, media terms unclear) / Hand-curate about 150 entries with our own coefficients, cross-referenced to free-exercise-db IDs for optional instruction text] -> Hand-curate about 150 entries with free-exercise-db cross-references. WHY: The recommender is only as good as its coefficients, and both open datasets are too coarse or wrong (e.g. leg press listed as working hamstrings). A curated, versioned seed is reviewable by Claude Code, testable in CI, and license-clean. The Unlicense text can be reused freely.
- Muscle taxonomy granularity: [Coarse 12-15 groups / Two levels: 18 groups (goals and volume) plus about 32 regions (soft variety) / Fine 40+ muscles used as hard constraints] -> Two levels. WHY: Front, side and rear delts and the OAP-relevant forearm and rotator-cuff detail need more than coarse groups. Fine-grained hard constraints would multiply guessed coefficients and required sets. Regions with soft bonuses still capture the regional-hypertrophy evidence (rectus femoris, triceps long head, gastrocnemius, upper chest).
- Coefficient model: [Binary primary/secondary / Discrete levels 1/0.75/0.5/0.25 with evidence overrides / Continuous EMG-derived values] -> Discrete levels with evidence overrides. WHY: Pelland's fractional counting (0.5) fit best; EMG is not valid for hypertrophy (Vigotsky 2022; Plotkin 2023). Discrete levels are explainable and easy to review.
- Rating UI: [Favorite heart only / 1-5 stars plus favorite heart plus 'never' (ban) / 5-state chips: Never / Dislike / Neutral / Like / Favorite] -> 5-state chips internally mapped to multipliers 0 / 0.6 / 1.0 / 1.15 / 1.495, with optional stars. WHY: Cleaner UI and enough resolution. The explicit 'Never' vs 'Dislike' difference is what the necessity rule needs.
- Disliked-exercise handling: [Hard exclude / Multiplier only / Multiplier plus necessity gate (unlock only if no non-disliked direct option exists)] -> Multiplier plus necessity gate. WHY: Matches the requirement exactly: disliked exercises appear only when they are the only direct trainer of a needed muscle, and even then the optimizer prefers good indirect coverage. Banned exercises never appear.
- Selection solver: [Greedy ratio / Deterministic beam search plus set tuning / ILP library (glpk/OR-tools) / Exhaustive enumeration] -> Beam search (width 12, top 25 candidates) with exhaustive checks on test fixtures. WHY: Milliseconds on the phone, no dependency, deterministic, and gives an objective breakdown to explain each pick. An ILP library adds weight without much quality gain at this scale.
- Variation policy: [Fixed exercises forever / Random each session / Mesocycle-locked anchors plus systematic accessory rotation] -> Mesocycle anchors plus systematic rotation of at most a third of accessories. WHY: Kassiano 2022: systematic variation helps; excessive random variation can hurt. Stable anchors also make progression curves usable.
- Occupied equipment: [Substitute only / Reorder first, then substitute] -> Reorder first, then up to 3 similarity-ranked substitutes. WHY: Waiting a few minutes keeps the planned exercise and its progression; substitution is the fallback.
- Bodyweight and assisted modelling: [Separate exercises per mode (assisted pull-up, pull-up, weighted pull-up) / One exercise with modes and an effective load L_eff = f_bw*BW + added - k*assist] -> One exercise with modes and an effective load. WHY: Gives one progression and rep curve from assisted to weighted, which is essential for OAP progressions and the user's flatter rep curve.
- Machine load units: [Force kg everywhere / Native units per machine (kg, lb or stack level) with optional kg calibration] -> Native units with optional calibration. WHY: Basic-Fit stacks and the assisted machine use different units (lb pin in 5-lb steps); progression only needs consistency within a machine.
- Exercise media: [No images, text cues plus a muscle-map SVG / free-exercise-db images / Video links] -> Text cues plus an SVG muscle map; optional external video link. WHY: Clean UI, small app, and avoids the uncertain image provenance.

## RISKS
- The coefficients are expert-assigned for most exercises. Systematic bias skews volume accounting (e.g. glute credit from leg press). Mitigate with evidence notes, CI tests, periodic Claude review, and per-muscle progress checks.
- Licensing: free-exercise-db image origin is undocumented; wger CC-BY-SA requires attribution and share-alike if its data is adapted; ExerciseDB media terms are unclear. Avoid bundling third-party images.
- The Basic-Fit inventory is unverified (hack squat, seated calf raise, hip thrust machine, lateral raise machine, chest-supported row, back extension bench, dumbbell range and steps, plate sizes, Smith bar weight). A wrong inventory gives impossible plans, so a first-run inventory checklist is needed.
- Too much preference weight could under-train muscles; too little annoys the user. The 0.6 multiplier and unlock thresholds need tuning from skip and swap logs.
- The beam search can miss the global optimum. Use deterministic tie-breaks and compare against exhaustive search on fixtures.
- Challenge interplay: double counting or ignoring OAP volume (lats, elbow flexors, grip). Extra curls or chin-ups could overload the medial elbow. Challenge sets must feed weekly coverage and joint caps.
- Time model error early on (default setup and rest guesses) can make plans overrun the 90-minute calendar slots until calibration kicks in.
- Machine units and cable pulley ratios make cross-machine load transfer unreliable. Treat them as a calibration problem and use conservative first sets.
- Isometric hold credit (0.5/0.25) and the per-session volume ceiling rest on weak evidence. Keep them as tunable constants.
- Taxonomy changes after launch need migration and versioning of historical volume calculations, so store catalog_version with each plan and recompute from raw sets.

## QUESTIONS
- Which machines does Basic-Fit Ierse Pond actually have? Options: hack squat, seated or standing calf machine, hip thrust or glute machine, hip abduction and adduction, lateral raise machine, chest-supported or T-bar row, 45-degree back extension, preacher bench, number of cable stations. Dumbbell range and step (e.g. 2-40 kg in 2 kg steps), smallest plates (1.25 kg?), Smith bar weight. Or would you prefer to tick a checklist in the app on first use?
- Rating style: is a 5-state choice (Never / Dislike / Neutral / Like / Favorite) enough, or do you want both a favorite heart and 1-5 stars?
- When you dislike an exercise (e.g. walking lunges), should the app offer to apply it to similar variants too (reverse lunges, split squats, step-ups)?
- Exercise names: English, Dutch, or both? Your challenge plan is in Dutch.
- Images: is a text cue plus a small muscle-map diagram enough, or do you want exercise images or video links?
- Are supersets OK when time is short, or only on request?
- Time budget: should the app derive available training time from the calendar event length (90-minute slots) minus travel and changing time? How many minutes is that buffer for you?
- Any injuries or joints to protect besides the elbow precautions in the OAP plan (shoulders, knees, lower back, wrists)?
- For muscles set to 'don't train', is incidental work acceptable, e.g. lower back during rows or quads slightly in hip thrusts? I propose allowing incidental contributions of 0.25 or less.
- Should main lifts stay locked for 5-week blocks aligned with the OAP challenge blocks, or rotate on a different schedule?
- For brand-new exercises, do you accept a 'calibration' first set (the app suggests a conservative weight and you report reps in reserve), or would you rather enter your own starting weight?
