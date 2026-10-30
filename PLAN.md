# FitnessApp — master plan

Personal Android gym app with deterministic, explainable recommendations, Google Calendar integration,
in-gym logging, and data-driven challenges (first: the one-arm pull-up challenge).

*Plan date: 2026-10-07. Status: M0 done on 2026-10-08, M1 done on 2026-10-11 (logs in `README.md`). M2a done on 2026-10-20. M2b in progress: steps M2b.1-M2b.3 built on 2026-10-20, M2b.4 (test day) on 2026-10-21 (D32, D33, D36), M2b.5 (calendar) on 2026-10-22 against the test calendar (D39), M2b.6 cutover on 2026-10-22 (D40: real enrollment started; Todoist closed after the first app week, real calendar writes after T17). M3 started on 2026-10-27 with its scope and six steps fixed by D49; M3.1 (estimators), M3.2 (estimator storage and the L8 update), M3.3 (prescription engine) and M3.4 (targets in the live session; migration 036 applied) and M3.5 (suggestions, warnings and preferences; no server migration) built the same day, followed by D50 (rest multiplier and goal presets; migration 037). M3.6 (substitution and custom exercises, D53; no server migration) built on 2026-10-28.*

---

## 0. How to read this

This file is the overview. It explains what will be built, how the parts fit together, the order of
work, and the problems I foresee. The details live in separate specs:

| Document | What it contains |
|---|---|
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | **Single source of truth** for every decision: your answers (D1–D12), my defaults (F1–F20), and the canonical technical resolutions (C1–C36). |
| [`docs/ALGORITHMS.md`](docs/ALGORITHMS.md) | Engine contract (determinism, versioning, params, snapshots), the pipeline, and load / estimation / prescription algorithms with every constant. |
| [`docs/ALGORITHMS_PLANNING.md`](docs/ALGORITHMS_PLANNING.md) | Calendar classification, safety, volume accounting, readiness, weekly targets, today/week planning, catalog, exercise selection, UI-side deterministic functions, tuner and simulator. |
| [`docs/CHALLENGE_ENGINE.md`](docs/CHALLENGE_ENGINE.md) | Generic challenge engine: definition schema, expression language, enrollment state machine, rule engine, calendar write-back. |
| [`docs/OAP_CHALLENGE.md`](docs/OAP_CHALLENGE.md) | Your one-arm pull-up program as encoded, the full rule table with the proposals (`P`) you approve one by one, and the import plan. |
| [`docs/challenges/oap.challenge.v1.json`](docs/challenges/oap.challenge.v1.json) | The machine-readable OAP challenge definition. |
| [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md) | Supabase schemas, tables, security (RLS, allowlist), sync protocol, migrations, review views. |
| [`docs/UX.md`](docs/UX.md) | Screens, flows, wireframes, visual design, telemetry events. |
| [`docs/ANDROID.md`](docs/ANDROID.md) | Toolchain, project layout, libraries, rest-timer reliability, calendar read/write, permissions, testing, M0 bootstrap commands. |
| [`docs/research/`](docs/research/) | Raw research notes from the 9-agent research pass (with sources), the critique, prototypes and the public-domain exercise dataset. Reviews of the algorithms start here. |

---

## 1. Goals and scope

### 1.1 What you asked for, and where each part is handled

| # | Requirement | Handled by |
|---|---|---|
| R1 | List recommended gym sessions for this week | `weekplan` (WeeklyPlanner) on the Week tab |
| R2 | List planned gym sessions this week (Google Calendar) | `calendar.classify` reading the phone's calendar provider |
| R3 | "I want to go today — what should I train?" (push / pull / legs / combination) | `today` (TodayRecommender) on the Today tab |
| R4 | Recommend exercises for the chosen session | `elig` → `select` → `order` (+ `substitute`, `rotate`) |
| R5 | Exercise catalog with favourites/ratings; disliked exercises only when they are the only option for a needed muscle | `pref` (5-state scale) + `elig` necessity gate |
| R6 | Sets, reps and weight per exercise, including unweighted bodyweight work | `rx` + `load` (bodyweight, weighted, assisted, timed holds) |
| R7 | Save weights, sets and reps done | `set_logs` (local first, synced to Supabase) |
| R8 | Override sets/reps/weight or skip, with a reason; report reps left after each set | Live-session UI + `reason` semantics + RIR-chip logging |
| R9 | Add or remove exercises during a session | Live-session UI; residual targets recomputed |
| R10 | Start and finish a session | Session lifecycle + foreground service |
| R11 | Update weights for next sessions, or even the current one, from the progression curve | `sse` + `rcm` (between sessions), `autoreg` (within a session) |
| R12 | Set/rep preferences, overridden only with a warning when research shows something much better | `warnings` (warn-only, D10) |
| R13 | Muscle goals: improve / maintain / don't train | `targets` with presets |
| R14 | Recommend rest days; rest timer after each set, overridable | `readiness` + `weekplan` (rest days), `rest` + Android timer service |
| R15 | Very clean UI | `docs/UX.md` |
| R16 | Purely deterministic, complex algorithms; no LLM in the app; periodic reviews by Claude Code | Engine contract, snapshots, replay, review loop (§10) |
| R17 | Supabase project `nuvjdvpqibqeyafzgaye`, new schema | Schema `fitness` (+ `fitness_private`, `fitness_review`) |
| R18 | Challenges tab; your one-arm pull-up challenge as a toggle, not hardcoded | Challenge engine + OAP definition |

### 1.2 Out of scope for v1

iOS, a web app, social features, Wear OS, home-screen widget, nutrition tracking, video. Health Connect
(bodyweight, runs, sleep) is planned for a later milestone.

---

## 2. Decisions at a glance

Full list with reasoning: [`docs/DECISIONS.md`](docs/DECISIONS.md).

| Area | Decision |
|---|---|
| Platform | Native **Kotlin + Jetpack Compose**, Material 3. Built from the command line on your PC (JDK 21 + Android SDK command-line tools, no Android Studio), installed over Wi-Fi with adb. |
| Phone | **Xiaomi / OnePlus / Oppo class**: aggressive battery management, so the rest timer gets every reliability measure. |
| Users | **Only you** (allowlist), but everything is built generically so a friend can be added later with one row. |
| OAP program | **The app becomes the source of truth.** Current state is imported at cutover; Todoist session tasks are retired (after you confirm); the artifact becomes a frozen reference. |
| Extra training | **Separate legs/push sessions are allowed** on challenge gap days, never the day before session B or a test day. Optional add-on blocks after C and A. Default max 4 gym visits/week. |
| Muscle goals | **You set them in the app** (presets: legs only, no legs, pull focus with push+legs maintained, balanced, custom). |
| OAP strength gates | **Your personal rep curve** decides the gate, not the ×1.10 rule. |
| OAP rule gaps | **Proposals tagged `P`, each approved by you.** Everything you already wrote down runs as written. |
| Set logging | **Tapping "reps left in the tank" (0–5+) logs the set.** One tap per set; RIR on every set. |
| Research vs your preference | **Warn, keep your preference.** Safety rules always enforced. General in-session changes are one-tap suggestions. |
| Your program vs generic caps | **Your approved OAP rules win**; when one jumps load faster than the generic cap, the app warns and logs it. |
| Your program's in-session rules | **Applied automatically with Undo** (e.g. session A's first-set pin calibration, 90° hold failure → +5 lb, B grind → −2.5 kg). |
| Day before session A | **Legs/push allowed freely** on any gap day; only lats, elbow flexors and forearms are protected in the 24 h before A. |
| Session time budget | Calendar gym events count as training time (you travel before them), so the budget is the event length minus 5 min. |
| Android Auto Backup | **Off**; Supabase sync and the monthly export are the backups. |
| Calendar | **Read + auto-write.** Challenge sessions and test days for this and next week are written automatically, with a marker so the app only ever edits its own events. General sessions are written when you accept them. |
| Language | **English, decimal point.** Dutch calendar titles are still recognised. |
| Database | New schema `fitness` in the shared (Free plan) project; you add it to the Data API's exposed schemas; I apply each migration through the Supabase MCP after you have seen it. |

---

## 3. What the research found (the parts that shape the algorithms)

The research pass used 9 agents (8 lenses plus a critic) and roughly 590 tool calls; notes and sources
are in `docs/research/`. Evidence grades are attached to every constant in the specs. The headline
findings:

1. **Volume is counted in fractional hard sets.** A set counts 1.0 for the muscle it directly trains
   and 0.5 for synergists; this fit the data best in the largest dose-response meta-regression (Pelland
   et al. 2026, 67 studies). Gains rise with weekly volume with diminishing returns (roughly square-root
   shaped for hypertrophy, flatter for strength). The ACSM 2026 position stand recommends at least
   ~10 sets per muscle per week, at least 2 sessions per muscle per week, and says 2–3 reps in reserve
   is close enough to failure. *Strong evidence.*
2. **Maintaining a muscle needs far less**: about a third of the building volume, at least one hard
   set per week, as long as the load stays heavy (Bickel 2011, Spiering 2021). *Moderate.*
3. **Load range barely matters for growth when sets are near failure** (roughly 6–30 reps), while
   strength needs heavy loads (≥ ~80 % 1RM). Closer to failure helps growth more than strength
   (Robinson 2024). *Strong/moderate.*
4. **People misjudge reps in reserve by about 1 rep** (they leave more in the tank than they think),
   and accuracy is poor above ~12 reps (Halperin 2022). The app therefore learns a personal RIR bias
   and treats high-rep or high-RIR reports as low-confidence. *Moderate.*
5. **The reps-vs-%1RM curve is personal and exercise-specific** (Nuzzo 2024, 7,289 people). Your own
   numbers confirm it: your curve is flatter than textbook tables. One parametric curve with a personal
   flatness constant `k` (≈ 30.6 for your weighted pull-up, from two points) explains your data and
   reproduces the loads in your program (it picks +27.5 kg for 6×2 at 2 RIR, same as the plan).
6. **The same 3RM gives anything from +38.6 to +46.9 kg depending on the formula.** Your ×1.10 rule
   gives +42.8 kg; your own data imply about +39 kg. You chose the personal curve for gates, so the
   app will show about +39.4 kg at cutover (80 % interval ≈ +37.2 to +42.6) and the phase-1 gate
   (+44 kg) is about 4.6 kg away, not 1.1. Your working loads do not change, because they are anchored
   to the measured 20 kg × 4×5.
7. **Recovery** is roughly 80–90 % after 48 h and > 90 % after 72 h for most muscles; failure sets,
   eccentric-heavy and new exercises take longer. Tendons (your elbow) recover more slowly than muscle,
   so they get their own channel. *Moderate; constants are tunable.*
8. **Rest between sets**: ≥ 60–90 s is enough for growth, 2–3 min helps strength and the reps on later
   sets; ACSM 2026 finds no consistent effect of rest length on growth. Rest is presented as a
   time-vs-performance trade-off. *Moderate.*
9. **Deloads**: halving volume while keeping the load (what your program already does) matches the
   evidence; full cessation costs some strength (Coleman 2024). *Moderate.*
10. **Running** barely interferes with growth or max strength long-term (Schumann 2022), but legs are
    weaker for 4–8 h after a run and lifting leaves residual fatigue for 24–48 h. Runs are counted as
    leg fatigue plus timing penalties, nothing more. *Moderate.*
11. **What existing apps do** (Fitbod, RP Hypertrophy, Alpha Progression, Gravl, Liftosaur, classic
    programs): per-muscle recovery maps, equipment-aware load snapping, rep-before-load progression when
    the next weight jump is large, mesocycle-locked exercises, failure counters, and data-driven program
    scripts (Liftosaur). Their most common complaints — unloadable weights, bad first-time loads,
    ignoring overrides, too much exercise rotation — are designed out.

Where the evidence is thin (per-session volume ceiling, isometric credit, recovery time constants,
OAP thresholds, many app "facts"), the constant is marked weak/expert-opinion and tuned from your logs.

---

## 4. System architecture

```
┌────────────────────────────── Android phone ───────────────────────────────┐
│                                                                            │
│  :app  (Jetpack Compose UI, ViewModels, services)                          │
│   ├─ Tabs: Today · Week · Exercises · Challenges · You                     │
│   ├─ Live Session flow  ── SessionForegroundService (type health)          │
│   ├─ Rest timer  ── AlarmManager.setAlarmClock + chronometer notification  │
│   ├─ CalendarContract reader/writer (primary Google calendar, marker)      │
│   └─ Habit reminders (daily hang, plank)                                   │
│        │ calls (pure functions, injected clock)                            │
│        ▼                                                                   │
│  :algo  (pure Kotlin JVM — the deterministic engine)                       │
│   L0 normalize → L1 safety → L2 challenge → L3 accounting/estimation       │
│   → L4 targets → L5 planning → L6 session build → L7 in-session → L8 post  │
│        │ reads/writes                                                      │
│        ▼                                                                   │
│  :data  (Room = source of truth on the phone, outbox, sync worker)         │
│        │ HTTPS (publishable key + your JWT), offline-tolerant              │
└────────┼───────────────────────────────────────────────────────────────────┘
         ▼
┌──────── Supabase project nuvjdvpqibqeyafzgaye (shared with chess) ─────────┐
│  fitness          tables + RLS (allowlist), exposed to the Data API        │
│  fitness_private  allowlist + helper functions (not exposed)               │
│  fitness_review   analysis views for Claude Code reviews (not exposed)     │
│  public/assistant chess app — never touched                               │
└────────────────────────────────────────────────────────────────────────────┘
         ▲
         │  Supabase MCP (read views, apply reviewed migrations, write params)
   Claude Code on your PC  ──  :sim (simulation + replay CLI, same :algo code)
```

Key properties:

- **Offline-first.** Every set is written to the phone's database in one transaction and synced later.
  The gym's signal and the Free-plan pause risk cannot lose data.
- **One engine, three runners.** The same `:algo` code runs on the phone, in unit tests on your PC, and
  in the `:sim` replay tool that Claude Code uses during reviews. Determinism rules (no system clock, no
  randomness, `StrictMath`, stable ordering, canonical rounding) guarantee identical results.
- **Algorithms are code; constants are data.** All tunable numbers live in a versioned params JSON. A
  review can change constants (stored in `fitness.user_algo_params`, effective from the next session)
  without a new APK. Structural changes need a new build.
- **Every recommendation is a snapshot.** Inputs, outputs, module versions, params hash and a decision
  trace are stored, so any recommendation can be replayed and compared against what you actually did.

---

## 5. The deterministic engine

Full specs: [`docs/ALGORITHMS.md`](docs/ALGORITHMS.md) and
[`docs/ALGORITHMS_PLANNING.md`](docs/ALGORITHMS_PLANNING.md). Precedence when rules disagree:
**safety > challenge > your hard constraints > general recommendations.**

### 5.1 Pipeline

| Stage | What happens | Main modules |
|---|---|---|
| L0 Normalize | Time zone, ISO weeks, bodyweight at time t, one load scale for weighted / bodyweight / assisted / timed work, loadable weights per equipment, calendar events classified (challenge session, planned gym, run, busy). | `clock`, `load`, `bodyweight`, `calendar.classify` |
| L1 Safety | Pain reports and the pre-session elbow check become a `SafetyState` that forbids tags (e.g. unilateral, eccentric, heavy grip) everywhere downstream. | `safety` |
| L2 Challenge | Active challenges run their rules and produce a **contract**: reserved sessions, blocked days, protected windows, owned muscles, committed volume, add-on slots. | `challenge.*` |
| L3 Accounting & estimation | Every logged set becomes fractional volume, fatigue and tissue load; per-muscle readiness; per-exercise strength state (1RM level + trend + uncertainty) and personal rep curve; RIR bias. | `setcredit`, `readiness`, `rircal`, `rcm`, `sse`, `fatigue`, `hold`, `stall` |
| L4 Targets | Your muscle goals become weekly targets (minus what the challenge already covers), with ramping, deload weeks and re-entry after breaks. | `targets`, `runs` |
| L5 Planning | The week plan (which days, which session types, rest days) and the "train today?" ranking. | `allocator`, `weekplan`, `today` |
| L6 Session build | Choose exercises and sets, order them, fit them into the time budget, prescribe load/reps/RIR, warm-ups and rest, and attach warnings. | `elig`, `select`, `order`, `time`, `rx`, `warmup`, `rest`, `warnings` |
| L7 In-session | Each logged set updates today's estimate; suggestions for the remaining sets; substitutions when equipment is occupied; challenge in-session rules; rest timer. | `reason`, `autoreg`, `substitute`, `challenge.eca` |
| L8 Post-session | Estimator updates, challenge progression rules, readiness, replanning, snapshots, sync, calendar update. | all of the above |

### 5.2 How each of your questions gets answered

**"Which sessions should I do this week?" (R1).** The weekly planner takes the fixed anchors from your
calendar (challenge sessions, other planned gym sessions, runs, busy evenings such as chess), the
challenge contract (blocked days, protected windows), your goals and current readiness. It enumerates
every combination of free days × session types (push, pull, legs and their combinations, full body) ×
optional add-on blocks — a few hundred thousand cheap simulations, under a second on a phone — and
picks the plan with the best score: weekly volume utility per muscle (square-root shaped, weighted by
your goals) minus penalties for violating protected windows, consecutive days, run timing and an
extra-session cost. The cost term means the planner also tells you *how many* sessions are worth it
(never more than your maximum, default 4). Every rest day carries a reason ("rest before session B",
"quads 62 % recovered", "no free slot").

**"What is planned this week?" (R2).** The app reads your phone's synced Google Calendar through
Android's calendar provider — no Google login needed. Events are classified with a scoring rule
(title keywords incl. Dutch, Basic-Fit location, plausible duration, challenge title patterns such as
`sessie C` / `testdag`; all-day items and the old Todoist "Gym" tasks are ignored). Recommended and
planned sessions are shown side by side.

**"I want to go today — what should I train?" (R3).** The same scorer ranks today's options (e.g.
"Legs + core — quads 100 % recovered, 0 of 12 sets this week", "Push", "Pull — not today, session A is
tomorrow and protects lats/biceps/forearms") with up to three reason chips each, or recommends rest.
If a challenge session is due today it is shown first, optionally with an add-on block.

**"Which exercises?" (R4, R5).** Exercises come from a curated catalog of ~150 exercises realistic for
Basic-Fit, each with muscle coefficients on two levels (18 muscle groups for goals, 34 regions for
variety). A beam search picks the set of exercises that best covers today's per-muscle targets,
weighted by your preferences (Never 0 / Dislike 0.6 / Neutral 1.0 / Like 1.15 / Favourite 1.495),
exercise quality (stretch emphasis, stimulus-to-fatigue), time, spinal load and injury flags. A
**disliked exercise is only unlocked when no non-disliked exercise trains a muscle that needs work**,
and even then it must earn its place; **Never** is never auto-suggested. Main lifts stay fixed for a
5-week block so progress is measurable; accessories rotate only for a reason (stalled, disliked, new
region). When a machine is occupied the app first offers to do the next exercise and come back, then
ranks substitutes by muscle-profile similarity.

**"How many sets, reps and how much weight?" (R6, R11).** Each exercise has a strength state: a 1RM
estimate on the *system load* (bodyweight + added − assistance, so assisted, bodyweight and weighted
pull-ups sit on one continuous scale), its weekly trend and its uncertainty, tracked with a robust
Kalman filter in log space. Your personal rep curve converts that into "this load for these reps at
this RIR", while a within-session fatigue model predicts how reps drop across sets at the planned
rest. The prescriber tries every *actually loadable* weight (your plates, dumbbells, stack steps,
dip-belt plates, assisted pin in 5-lb steps) and picks the one that best hits your rep range and
target RIR across all sets. When the next available weight is a big jump (more than ~1.5 reps), it
switches to double progression with a rep ladder (8-7-6 → 8-8-6 → … → next weight). Bodyweight-only
exercises progress by reps until a weighted or harder variant makes sense.

**"I'm in the gym and it's off." (R8, R9, R11).** You can change weight/reps/sets, skip, add, remove
or reorder exercises at any time. When you deviate, context-ordered reason chips appear ("too easy",
"too hard", "feeling good", "feeling bad", "no time", "occupied", "pain", "grip failure", "torso
rotation", …); each reason has a fixed, documented effect (e.g. "occupied" never counts as a
performance data point, "feeling bad" down-weights the set, "pain" stops the exercise and can start
the challenge's elbow protocol). After a set that is clearly off target (≥ 2 RIR from the target, or
two sets in a row), a one-tap suggestion for the remaining sets appears ("Next sets: +2.5 kg — RIR 4
vs 2"), never more than once per exercise. Rules from your own challenge program that act during a
session (e.g. session A's first-set pin calibration) are applied straight away and shown as
"Rule: … · Undo".

**"I like 3×8–10; my friend does 2×12." (R12).** Your preferences are used as given. A warning engine
compares your scheme with the best scheme at the same number of sets and the same time, and checks
weekly volume per goal muscle. Warnings are non-blocking, at most one banner per screen, with a Why
sheet (numbers, evidence grade, citations) and "use suggested". Only safety rules override you.

**"Improve legs, keep push, don't train X." (R13).** Goals per muscle group become weekly targets:
improve ≈ 10–14 fractional sets (by training age; +4 for focus muscles; capped), maintain ≈ a third of
that with at least one heavy hard set per week, skip = no direct work. Muscles an active challenge
already trains are marked "covered by challenge" and the general planner doesn't add to them.

**"When should I rest?" (R14).** Per-muscle readiness (fatigue decaying exponentially, with separate
channels for tendons and whole-body fatigue) drives rest days and session choice. After every set a
rest timer starts automatically (180 s for heavy strength work, 150 s lower-body compounds, 120 s
upper-body compounds, 90 s isolation/holds, 60 s core, adjusted by how hard the last set was;
challenge-defined rests take priority), with −15 / +15 / Skip and a lock-screen countdown.

---

## 6. Challenges and the one-arm pull-up

Full specs: [`docs/CHALLENGE_ENGINE.md`](docs/CHALLENGE_ENGINE.md),
[`docs/OAP_CHALLENGE.md`](docs/OAP_CHALLENGE.md), definition
[`docs/challenges/oap.challenge.v1.json`](docs/challenges/oap.challenge.v1.json).

### 6.1 The engine (not OAP-specific)

A challenge is a **versioned JSON definition**, never app code. It declares: rules of the challenge
itself, parameters, equipment (incl. assisted-machine calibration), exercises (catalog references),
metrics (with left/right sides), state variables, block structure (e.g. 4 build weeks + 1 deload),
phases with gate criteria, session templates (with inheritance for later phases), home habits,
scheduling constraints and preferences, calendar title patterns, an integration contract with the
general engine, progression/safety rules, in-session rules and onboarding steps.

- **Expressions** use a small typed language (FitExpr): readable infix (`all(ex.wpu.rir >= 2)`),
  units (kg, lb, s), decimal arithmetic, explicit `all()/any()`, whitelisted pure functions — no
  `eval`, no loops. It shares the general engine's rep curve, so challenge gates and general
  prescriptions never disagree about your strength.
- **Enrollment state is event-sourced**: every session, set, test, pain report and pause is an event;
  the state is a deterministic fold over them. That makes "go back to the level of two blocks ago"
  trivial and lets Claude Code replay your history under a new rule version before publishing it.
- **Rules run in stages** (safety → data → structural → progression) with first-writer-wins, locks
  (e.g. a grip slip locks "no increase" regardless of rule order) and a full audit row per firing, so
  every change shows a plain-language reason ("+2.5 kg on B because all 6×2 had ≥ 2 RIR").
- **Weeks advance on completion, not the calendar**: a missed week repeats with the same weights, which
  is exactly your "niets inhalen" rule; test days are postponed until after a clean session.
- **Toggle in the Challenges tab**: enable (setup wizard with rule approvals), pause/resume, abandon,
  restart from a snapshot. Two challenges can coexist if their constraints allow it.

### 6.2 Your OAP program in the app

- **Faithful by default.** Every rule from the artifact and the Todoist task descriptions is encoded as
  written and tagged `A`, including the details the artifact omits (session A's first-set calibration
  ±5 lb, the −10 lb bonus after a controlled calibration negative, session C's +5 lb when below 4
  reps, straight-arm hold 6 → 8 s, plank 20 → 30 s → plate, test order). Where one of your rules raises
  the load faster than the app's generic safety cap, your rule still runs and the app shows a warning.
- **Proposals** (tag `P`, D8) start at the recommendation and can be switched off one by one (D35). The most important ones:
  - session A's assisted pin drops 5 lb **only when all 5×3/arm were completed** (+5 lb after two
    failed weeks) instead of every week regardless — the unconditional version means roughly +4.9 %
    one-arm load per week, faster than strength can adapt, and the elbow pays;
  - definitions for "missed week" (fewer than 2 of 3 sessions), "easy C session", and "progress"
    (for the two-tests-without-progress rule);
  - in-block progression for the A 4×4 and C 4×5 weighted pull-ups, and a one-arm dead-hang target
    derived from your latest fresh weaker-arm hang;
  - test dates move with the block (each block 5 weeks from the real test), and optionally max reps
    moved to the end of the test battery so it doesn't tire your grip before the hang test;
  - negatives keep their own 8 s / 5 s rule and are not lowered by the personal-curve recalculation;
  - after a test, working loads are recalculated through your personal curve with a ±5 kg clamp,
    keeping the anchor relationship (A = anchor + 1 rep, B = anchor + 3 reps).
- **Personal-curve gates (D7).** Gate progress shows your personal-curve e1RM with its uncertainty,
  plus the ×1.10 value for comparison.
- **Safety first.** The pre-session elbow check (0–3, one tap) skips negatives at any trace; the three
  stop signals start the 2-week protocol (no unilateral work, light bilateral, prehab doubled, daily
  hang dropped first) and block heavy grip/elbow-flexor work in the general planner too.
- **Coexistence with general training (D5).** The challenge owns lats, elbow flexors and forearms
  (≈ 20+ hard sets/week), so the general planner fills what you choose for legs, push and core: on
  free gap days (never the day before B or a test day, never heavy grip within 24 h of A) and/or as
  add-on blocks after C and A. Add-on room is computed by the session-time model (booked event length
  − estimated challenge work − 5 min): roughly 20–35 min after C and 0–20 min after A depending on the
  week (more in deload weeks), recalibrated from your real set timestamps. A legs/push session on the day
  before A is fine (your choice); only lats, elbow flexors and forearms are protected for 24 h before A.
- **Calendar (D11).** Challenge sessions and test days for this and next week are written to your
  primary calendar automatically, with the exercise list and loads in the description (as Claude does
  now) and a marker line. When loads change, the description is updated; when a test is postponed,
  the event becomes session B. The events Claude created by hand (no marker) are adopted once with
  one tap.
- **Habits.** Daily two-arm hang (skipped on A day, halved in deload week) and the Tue/Thu plank become
  in-app reminders with one-tap logging.

### 6.3 Cutover from today's workflow (D4)

Your test day on **Sunday 11 October** will happen before the app exists; keep using the chat workflow
until the app's challenge milestone (M2) is ready. At cutover I import: phase, block/week, all working
loads and pins, plank and hang targets, and the test history with validity flags (30-08 valid but its
hang values used an old protocol; 19-09 was a 3RM-only re-test, not a test day; 29-09 hang was not a
test day; 11-10 if done). Then — only after you confirm — the Todoist session/test/hang/plank tasks are
completed or deleted and the artifact is marked as frozen.

---

## 7. Data, sync and security

Full spec: [`docs/DATA_MODEL.md`](docs/DATA_MODEL.md).

- **Live project facts** (checked read-only): project "ChessAnalysis", eu-west-1, Postgres 17, **Free
  plan**; the chess app talks to the database server-side only; Data API exposes `public,
  graphql_public`; only the new publishable key is active; your account (sivucinec@gmail.com) already
  exists, so you sign in with your existing credentials.
- **Schemas**: `fitness` (exposed, tables with RLS), `fitness_private` (allowlist + helpers),
  `fitness_review` (analysis views for reviews). Grants only to `authenticated`/`service_role`; nothing
  to `anon`. Every policy is `user_id = (select auth.uid())` **and** "user is on the allowlist". Child
  rows use composite `(parent_id, user_id)` foreign keys so no row can attach to someone else's data.
- **About 38 tables**: reference data (muscles, regions, exercises, stimulus coefficients, reason
  codes, metric definitions, algorithm versions, default params), your configuration (profile, goals,
  rep preferences, exercise preferences, gym equipment with calibrations, personal params), facts
  (sessions, session exercises, set logs with every field the rules need, metric observations incl.
  test results and bodyweight, wellness/pain logs, classified calendar events), challenges
  (definitions, enrollments, input events, rule audit), and the review trail (recommendation
  snapshots, telemetry events, a rebuildable estimator cache).
- **Sync**: the phone is the source of truth; an outbox pushes changes with plain upserts; a server
  trigger resolves conflicts by last-writer-wins on the client timestamp; pulls use a server cursor
  with a 120 s overlap. Sign-out and reinstall are blocked while unsynced data exists.
- **Migrations**: SQL files in the repo, applied through the Supabase MCP after you've seen them; never
  `db push` or `config push` (they would collide with the chess app's 55 migrations and settings);
  advisors checked after each one.
- **Backups**: the Free plan has none, so the app offers a monthly JSON export, and I take a
  `db dump` of the `fitness` schema before every migration.

---

## 8. User experience

Full spec with wireframes: [`docs/UX.md`](docs/UX.md).

- **Five tabs**: **Today** (decide and start), **Week** (recommended vs planned, muscle volume bars),
  **Exercises** (catalog and preferences), **Challenges** (toggle, progress, tests, rules), **You**
  (progress, history, settings, transparency). A running session is a full-screen flow; when minimised
  it stays visible as a mini-bar.
- **Logging in one tap**: the current-set card shows target and last time, large 64 dp steppers, and
  the row "reps left in the tank: 0 1 2 3 4 5+" which *is* the log button. Undo instead of confirm
  dialogs. Built for one thumb, sweaty fingers and bright gym light.
- **Challenge-specific UI**: left/right pairs (left first, "right ≤ left" hint), timed holds with a
  3-2-1 lead-in, beeps and a quick correction afterwards (your hand is on the bar), assisted pin in lb
  with effective kg underneath ("lower = harder"), weighted shown as "BW + 27.5 kg", grip-slip /
  torso-rotation flags, the elbow check, a test-day wizard in your test order.
- **Explanations in layers**: ≤ 3 reason chips → Why sheet (numbers, evidence grade, citations,
  algorithm version) → trace export for reviews.
- **Look**: "Bento" dark design (D31): blue-black layered tiles with soft corners, one periwinkle accent,
  Manrope with tabular numbers, calm motion, dark only.

---

## 9. Android platform specifics

Full spec: [`docs/ANDROID.md`](docs/ANDROID.md).

- **Rest timer you can trust on a Xiaomi/OnePlus/Oppo phone**: timer state stored as timestamps (not a
  ticking counter), an exact `setAlarmClock` alarm (never deferred by Doze), a chronometer countdown
  notification with −15/+15/Skip actions (an Android 16 Live Update where available), a foreground
  service for the whole session, a battery-optimisation exemption and a guided "Autostart / No
  restrictions" setup screen, plus alarm-latency logging and an on-device Doze test.
- **Calendar**: read through `CalendarContract` (instances, change observer); write into your primary
  Google calendar with a marker line; the app only edits or deletes its own future events.
- **Toolchain**: JDK 21 (your system Java 25 stays untouched), Android SDK command-line tools, Gradle
  wrapper; install with `gradlew installDebug` over Wi-Fi adb; UI checked with JVM screenshot tests and
  `adb screencap`. A release signing key is created at M0 and backed up (losing it forces a reinstall
  that would wipe unsynced data).

---

## 10. The algorithm review loop

You said you will periodically ask Claude Code to review the algorithms after usage. The app is built
to make those reviews concrete rather than impressionistic.

**What gets logged** (from day one): every set with planned vs actual values, RIR, reasons, flags,
rest planned vs taken, timestamps; every recommendation as a snapshot with its inputs, outputs,
module versions and params hash; every suggestion shown and whether you accepted it; every warning and
what you did with it; every challenge rule firing; calendar classifications and corrections; prompts
answered or skipped; alarm latency.

**What a review does** (runbook to be written in M5 as `docs/REVIEW.md`):

1. Pull the review views through the Supabase MCP (read-only): prescription error per exercise
   (bias, MAE in reps), RIR calibration, one-step-ahead prediction error of the strength estimator and
   its interval coverage, recommendation acceptance by module and reason, override and skip reasons,
   weekly volume vs targets, adherence (recommended vs planned vs done), rest compliance, challenge
   rule firings and gate margins, readiness vs next-session performance.
2. Replay history with `:sim` under the current and candidate algorithm versions/params and diff the
   outputs against what actually happened.
3. Propose changes as a short report: constants (go into `user_algo_params`, effective next session)
   or code changes (new engine version, new APK). Nothing is applied without your approval; every
   change is versioned so before/after can be compared.
4. Challenge-rule changes go through a new definition version, simulated over your real history
   first; your enrollment is migrated only after you approve the preview.

**Cadence**: first review after 4–6 weeks of logs (enough sessions to fit your rep curve and RIR
bias), then roughly every mesocycle (5 weeks) or after a test day.

---

## 11. Milestones

Each milestone ends with something you can use. Sizes are rough, in Claude Code working sessions.

### M0 — Foundations (1–2 sessions) — done 2026-10-08

- Install Temurin JDK 21 and Android SDK command-line tools; you accept the SDK licenses and pair the
  phone over Wi-Fi (`docs/ANDROID.md` has the exact commands).
- `git init`; Gradle project with `:app`, `:algo`, `:data`, `:sim`; version catalog; purity test for
  `:algo`; golden-fixture harness; release keystore created and backed up.
- Supabase: migrations 001–00x (schemas, grants, allowlist, LWW trigger, reference tables, reason
  codes, metric definitions, algorithm versions) — shown to you, applied via MCP, advisors clean; you
  add `fitness` to the exposed schemas; probe verifies.
- **Done when**: an empty app installs on your phone, signs in with your existing account, and a test
  row round-trips to Supabase; `gradlew :algo:test` runs green.
- **Result**: all done-when checks passed. Deviations, each recorded in `docs/DECISIONS.md` (changelog
  2026-10-08): the JDK is a portable Temurin zip and the Android SDK lives in `C:\Users\sivuc\Android\Sdk`
  (Windows packaging of the Claude app); compileSdk 37 with targetSdk 36; `profiles` and `gyms` moved
  into M0 (migration 006) so the first sign-in proves the write path; `pg_jsonschema` installed
  (migration 007); the full params set 1.0.0 (621 keys) is seeded already; later migrations shifted by two.

### M1 — Gym logger you can use (3–5 sessions, split into M1a and M1b, D20)

M1 is split so that real sessions can be logged as early as possible; the total scope is unchanged.

#### M1a — Log real sessions — done 2026-10-11

- **Catalog**: about 150 curated exercises with English and Dutch names and muscle coefficients, every
  slug the OAP definition references (C38), the CAT-1 validator in CI, and the bundled catalog JSON that
  `seedgen` turns into migration 009. You approve a summary page (exercises, equipment, main muscles)
  before it is seeded (D19).
- **Database**: migrations 008-015 (catalog, user configuration, challenge core, snapshots, sessions and
  sets, challenge events and calendar, telemetry, caches and exports), each shown to you, applied through
  the MCP and verified with the DATA_MODEL section 17 checklist and two-user RLS tests.
- **Phone storage and sync**: the Room v1 schema (ANDROID section 8), the outbox with push, pull and
  last-writer-wins merge, the sync worker, the sync status line and the manual "Re-push all data" action
  (D17). The M0 profile write moves onto the outbox.
- **Equipment and onboarding**: the Basic-Fit equipment checklist with assisted-machine calibration and
  your dip belt, the disclaimer, and the permission and reliability setup for your OnePlus (notifications,
  exact alarms, battery-optimisation exemption, OxygenOS background settings).
- **Live session**: start and finish a session; add, remove, reorder, skip and "do later"; the current-set
  card with RIR-chip logging (D9) and the reason chips (`ui.reasonchips`); left/right pairs; timed holds
  with lead-in; assisted pins and dip-belt loads; per-exercise warm-up sets; prefill from the full `ui.prefill`
  module with "last time" as its source (D18). The general warm-up block moved to M2 (D25).
- **Rest timer** with every reliability measure (foreground service, `setAlarmClock`, countdown
  notification with an Android 16 Live Update), tested on your phone with Doze, the screen off and the
  app killed.
- **Session summary** with session effort (sRPE) and joint pain.
- **Done when**: you log a full real session (any type) without signal and it appears in Supabase
  afterwards; the rest timer fires on time with the screen off.
- **Result**: both done-when checks passed (offline session 2026-10-09 arrived complete; rest alarms 4-10 ms late
  with the screen off, in forced Doze and after a process crash, ANDROID 15.6 log in the README). Changes from the
  first gym tests, each in `docs/DECISIONS.md` (changelog 2026-10-09 and 2026-10-11): the F7 checklist simplified
  (unlimited plates, 1.25 kg in the plate list, stack step in kg or lb, no machine name); params 1.0.1 (migration
  019) and catalog 1.0.1 with four machine stations (migration 020); sync status per UX 7.1; the Live Update chip
  text for OxygenOS; no rest after the set that finishes the planned work; the timer self-test on the production
  path. Deferred: the general warm-up block to M2 (D25). Still open from the 15.6 checklist (not done-when items):
  T6 reboot, T9/T10 denied permissions, T12 overnight idle (read from the next session's events).

#### M1b — Complete the logger — done 2026-10-11

- Room v2: the first real Room migration (ANDROID 8.5; tables for the exercise preferences and the export
  bookkeeping), with the no-destructive-fallback guard and a migration test.
- History with editing of past sessions and sets.
- Exercises tab: the catalog list, exercise detail and the five-state preferences (stored now, used by
  the selection modules in M3).
- The monthly JSON export (`.json.gz`) and its reminder.
- The Reliability screen with alarm-latency statistics, and the remaining telemetry events of the
  DATA_MODEL 9.8.1 registry.
- The `fitness_review` views (migration 017), so the first algorithm review can start after 4-6 weeks of
  logs.
- **Done when**: you can correct a past session, export your data, and the review views return your
  logged sessions.
- **Result**: all three done-when checks passed on 2026-10-11 (README M1b log): a past-session edit reached
  `set_logs` and `v_set_facts`, the export file's SHA-256 equals the synced `exports` row, and `v_session_summary`
  returns both logged sessions. Decisions taken during M1b, each in `docs/DECISIONS.md`: the muscle map is built now
  (D26), custom exercises move to M3 (D27), past sessions can get a forgotten exercise (D28), idle protection is in
  M1b and the review bundle moves to M5 (D29), a preference change has no Undo snackbar (D30), and Dutch exercise names appear only on a Dutch phone (D12). Deferred (changelog "M1b scope decisions"): the e1RM sparkline,
  Alternatives, "Used by" and instructions on exercise detail, and the telemetry events that need later modules.
  The three 2026-10-09 ab-crunch sets got a test load of 30 lb in History (the data is test data in this phase), which
  cleared DQ06.

### M2 — Challenge engine + your OAP program (4–6 sessions, split into M2a and M2b, D32)

M2 is split so that the engine can be built and simulated before any screen depends on it; the total
scope is unchanged and the done-when is checked at the end of M2b.

#### M2a — Challenge engine core — done 2026-10-20

- FitExpr (parser, type checker, decimal evaluator, function whitelist), the definition loader and
  validator (stages 1 and 2, checks V01-V18), the event-sourced enrollment with tick synthesis and
  refold, the staged rule engine (locks, first writer wins, frozen modes, injury dominance, audit rows),
  the materializer (templates, mode transforms, per-side rows, loads through LOAD-1, in-session rules
  with Undo, `valid()`), the challenge week planner with today advice, and the contract (reserved
  sessions, blocked days, protected windows; coverage follows `setcredit` in M4).
- The OAP definition v1 passes validation against the catalog and reference data.
- `:sim challenge validate` and `:sim challenge simulate`; golden simulations of 26 weeks × personas
  (all-success, stall, grip slips, elbow pain in week 7, missed weeks, machine busy, DST and late ticks),
  each with all proposals approved and all declined, committed to the repo.
- **Done when**: `gradlew :algo:test :sim:test` runs green with the GF-CE fixtures that need no app
  (FitExpr, validator, enrollment, rule engine, materializer, planner, contract), and the persona
  snapshots replay byte-identically.
- **Result**: both done-when checks passed on 2026-10-20 (README M2a log): 149 `:algo` and 23 `:sim` tests, 16 persona
  snapshots that replay byte-identically and hold every global invariant. How the engine was built is
  CHALLENGE_ENGINE 16.1. The simulations found two program issues, fixed in the unpublished `oap@1.0.0` and the engine
  (changelog 2026-10-20): a new elbow report restarted the 14-day protocol, and a block rollover could end injury mode.
  Moved on: the next-week projection (7.7) comes with the calendar write-back in M2b (built in M2b.5); contract coverage with
  `setcredit` in M4.

#### M2b — Your OAP program in the app

- The general warm-up block (moved from M1a, D25) with the warm-up row countdown; OI-27 is decided here
  (alarm-backed `purpose = interval` or the in-app fallback).
- Setup wizard with the proposals at their recommendation and a Customize list (D35, replacing per-proposal approval); import of your current state; challenge storage and sync;
  Challenges tab (gates per arm, block/week, next test, rule history); test-day wizard; elbow check and
  pain protocol; habit reminders.
- Calendar: read + classify; auto-write challenge sessions and test days with marker; adopt existing
  events; Today shows the challenge session.
- **Cutover** (with your confirmation): Todoist tasks retired, artifact frozen.
- **Done when**: a real A, B, C week and a test day run fully in the app, and next week's loads match
  what the rules say.

M2b is built in six steps (D33), each ending with a green build and checks on the phone:

1. **M2b.1 Storage and sync.** The OAP definition is bundled with the app and published to the database
   by migration 016 (shown to you before it is applied). Room gets the enrollment, event, rule-audit and
   calendar tables with sync, the local refold after a bootstrap, and tick catch-up at app start and in
   the daily maintenance job.
2. **M2b.2 Challenges tab and setup wizard.** The setup wizard (three steps since D35) with the import
   and the P01-P27 decisions, the dashboard with gate rows, the rules, proposals, history and tests tabs, Correct a value,
   and pause, resume and abandon with their previews.
3. **M2b.3 Challenge sessions.** Today shows the planned challenge session; Start materializes it with the
   warm-up block (the alarm-backed row countdown, D33), the challenge card types, in-session rules with
   Undo, the readiness and elbow check, the pain sheet, and the home habits with their reminders.
4. **M2b.4 Test day** (built 2026-10-21). The test-day wizard in the definition's order, validity, the review screen
   and the recalculation report, with the Record and attempt cards (also used by the attempts block of phase 3); the
   test protocols are structured in the definition (D36, `oap@1.1.0`).
5. **M2b.5 Calendar.** `calendar.classify` and `challenge.calendar`: reading and classifying events,
   matching challenge slots, Adopt, and the write-back executor, first against the sandbox calendar
   (ANDROID 16.10); writes to your real calendar start only after those tests pass and you agree.
   The UI and test scope are fixed by D39. M2b.5 is built in five parts, each with a green build:
   - **5a Pure engine (`:algo`).** The shared `core.calendar` package (the C37 `Marker`, the title normalisation,
     the instance and series keys, the provider row type), `calendar.classify` with its four stages and the
     lexicons from params, and `challenge.calendar` with `matchSlots`, the desired events of the current and next
     ISO week, the write-plan diff, Adopt and the C36 slot-type rewrite, plus the post-hoc session link (C35).
     Tests: ALGORITHMS_PLANNING 1.7, fixture F-CAL-41, and GF-CE-25 to 28.
   - **5b Storage.** Room v6 with the local `calendar_instance` cache and `calendar_link` table and the synced
     `calendar_events` table (the server table exists since migration 014), sync and the drift test.
   - **5c Reading.** The calendar permissions, the provider gateway (calendars, instances), the device-only
     "FitnessApp TEST" calendar, the refresh controller (app start, a debounced observer, daily maintenance, after
     challenge changes), the stored classification and its telemetry, and the planner fed with matched slots, busy
     blocks and runs; a matched event also sets the session's time budget.
   - **5d Writing.** The event descriptions from the materialized sessions, the executor with the six guards of
     ANDROID 16.7 in one `applyBatch`, the link states and their synced mapping, Adopt, and the redirect to the
     test calendar.
   - **5e Screens and checks.** You → Calendars, the event sheet with corrections, the dashboard's This week row,
     the degraded banner; then the phone checks against the test calendar (ANDROID T17, first half).
6. **M2b.6 Cutover** (started 2026-10-22, D40). With your confirmation: the real import values, the Todoist tasks retired, the
   artifact frozen, and the first real week in the app. Built for it: `oap@1.4.0` (Pallof press, P28, P29 lighter week after
   the elbow protocol), engine 1.3.0 (setup decisions fold with the creation), setup's mid-program import (elbow protocol,
   week ordinal, block counts, earlier tests), the debug `setup_draft` and `calendar_delete_test` actions, the test-data wipe.
   Todoist closed on 2026-10-30 after the first app week: the recurrence removed and the six tasks completed (D40).
   Still open: T17 and real calendar writes, and freezing the artifact. The earlier tests' missing per-arm values (D40 changelog) were restored on 2026-10-26 (D48, engine 1.7.0).

### M3 — General load prescription (3–4 sessions)

- RIR bias, personal rep curve, strength estimator, fatigue model, holds, prescription with double progression,
  warm-up sets, rest durations, reason semantics, in-session suggestions, stall handling (load and bodyweight were built
  in M1a, D21).
- Set/rep preferences and the warning engine (warn-only).
- The exercises are the ones you pick: a general session starts empty or as a repeat of a past session (D49, amending
  D22). Exercise preferences (5-state, captured in M1b) feed substitution when a machine is occupied, missing or
  unwanted; custom exercises (D27) arrive with it. Building a session automatically for a session type (eligibility
  gate, selection, ordering, time fitting) moves to M4 (D49).
- The OAP's delegated items, the B row and the dips, get their loads from the same prescription engine (D49).
- **Done when**: after 3 exposures, a legs or push session you assemble or repeat gets loadable prescriptions that land
  within ±1 RIR of target most of the time (measured with `v_prescription_error`).

M3 is built in six steps (D49), each ending with a green build and checks on the phone:

1. **M3.1 Estimators** (built 2026-10-27). The pure `:algo` modules `fatigue`, `rcm`, `rircal`, `sse` and `hold`, the
   estimator column of REASON-1 they need (reason classes and the missing-RIR imputation), and the 15 golden fixtures
   of ALGORITHMS Part D (GF-FAT, GF-RCM, GF-RIR, GF-SSE, GF-HOLD). No screen changes; the app does not call them
   until M3.2, so the engine version stays 1.7.0.
2. **M3.2 Estimator storage and the session-end update** (built 2026-10-27). Room v7 with `params_snapshots`,
   `recommendations` and `exercise_model_state` (push-only sync), the L8 update when a session finishes (a replay of every
   finished session from `set_logs`, so an edit of a past set is picked up too), the snapshots and the review views they fill, the exercise detail's
   e1RM sparkline (deferred since M1b), and RCM-1's `k` as the OAP's `userK` fact. Under C59 the OAP gate keeps the
   prior `k` of 30 until RCM-1 has informative windows; from then on the gate value and the test recalculation use
   your fitted `k`.
3. **M3.3 Prescription engine** (built 2026-10-27). The pure `:algo` modules `reason` (with the pain stop of D49 and the missing-RIR
   imputation), `rx` (model, double, the first-set probe, deload and re-entry, the shadow model for rule items, delegated
   challenge items, unilateral and bodyweight variants, the cap table), the warm-up set generator, `rest@1.1.0` and
   `stall`, checked against the golden weeks W41 and W43 (DST).
4. **M3.4 Targets in the live session** (built 2026-10-27). Set-card targets as the second prefill source (D18), the first-set probe that
   plans the remaining sets, generated warm-up sets, the OAP's B row and dips with prescribed loads, Summary "Next time",
   the Why sheet with the traces, and "Repeat" on the session start (the `template_source` migration is shown first).
5. **M3.5 Suggestions, warnings and preferences** (built 2026-10-27). In-session suggestion cards (`autoreg`, at most one per C16), the
   warning engine with its display (including grip-heavy or pull work added on an OAP gap day), stall-tier suggestions,
   the Preferences screen's Sets and reps section, and the opt-in RIR check set (F14).
6. **M3.6 Substitution and custom exercises** (built 2026-10-28, scope fixed by D53). `pref`, `injury`, the hard filters of
   `elig` and `substitute`: "Occupied" offers the next exercise first, then three ranked alternatives with a starting load
   from the transfer ratios; the exercise detail's Alternatives section; custom exercises (all five form steps, Room v10).

### M4 — Planning (4–6 sessions)

- Volume accounting, readiness, runs from the calendar, weekly targets and goal presets, challenge
  contract consumption, add-on blocks, Today recommender with reason chips, weekly planner (Week tab
  with recommended vs planned and volume bars), deload policy synced to the challenge, mesocycle
  exercise rotation, "Plan it" for general sessions.
- Building a general session automatically (moved from M3 by D49): the eligibility filter's necessity gate, selection,
  ordering and supersets, and the time model with trimming, fed by the allocator's per-group targets; the Preferences
  screen's Muscle goals and Sessions settings.
- Feedback prompts and the readiness sheet, mesocycle rotation, optional supersets, and You → Progress (D55).
- **Done when**: Today and Week give sensible, explained recommendations for the real week (golden
  fixture: Wed 2026-10-07 with C Tue, A Thu, test Sun) and respect every challenge constraint.

M4 is built in seven steps (D55), each ending with a green build and checks on the phone. Session building comes before
the planner, so generated legs and push sessions are usable on gap days as early as possible; the planner reuses the
allocator. Before a module's goldens are frozen, the expected values of F-W41, F-DST-W43 and F-SEL are regenerated on
catalog 1.0.2 (OI-16).

1. **M4.1 Accounting and targets** (built 2026-10-29). The pure `:algo` modules `safety` (one `SafetyState` that merges the general joint
   flags with the challenge's safety modes), `setcredit` (done and committed volume per muscle group, including the
   challenge contract coverage deferred from M2a), `readiness`, `targets` (weekly targets, the ramp, the DeloadPolicy
   synced to the challenge block, re-entry, the goal presets file) and `runs`. Tests: the module tests of
   ALGORITHMS_PLANNING 2-6 and the goldens W41_targets, W41_accounting, W41_readiness and DST_W43_readiness and
   DST_W43_week. No screen changes, so the engine version stays.
2. **M4.2 Goals, settings, prompts and this week's volume** (built 2026-10-29, phone checks open). Storage and sync for the profile fields and per-group goals
   (the migration is shown first). Two new Preferences sections: Muscle goals (the presets, per-group improve, maintain or
   skip, groups owned by the OAP shown as covered) and Sessions (training age, maximum visits, availability windows,
   default session length, running priority), opened once from a Today card "Set your goals". The feedback prompts after
   a session and the pre-session readiness sheet with You → Prompts, so soreness answers start to accumulate. Runs are
   read from the classified calendar. The Week tab arrives with only its muscle volume bars: done and committed volume
   against this week's targets.
3. **M4.3 Session-building engine** (built 2026-10-29). The pure `:algo` modules `time` (duration model, calibration, the dose estimator,
   the add-on capacity of C49, the time-chip trimming), `allocator`, the `elig` necessity gate and group availability,
   `select` (beam search with set tuning, checked against an exhaustive search; candidates per D56) and `order` with the
   optional supersets. Goldens: W41_addon_thu, W41_allocation_fri, W41_select_fri, the Wednesday train-anyway block and
   the F-SEL cases. Params 1.0.3 adds `select.per_group_k` (D56); the app moves to it with the M4.4 migration.
4. **M4.4 Building a session in the app.** A general session can start for a session type (Legs, Push, Legs + push,
   Full and the others of ALGORITHMS_PLANNING 0.3) with time chips; the exercises, order and loads come from `select`,
   `order` and `rx`. The add-on blocks after C and A are offered at the end of the challenge session, with Skip on the
   block header. SUB-1's "Do {k} now" follows ORD-1, and busy stations count in the ranking (deferred by D53).
5. **M4.5 Planner and Today.** `weekplan` (the day model, hard constraints, enumeration, the tie band, rest-day reasons,
   replanning, next week) and `today`, with `ui.todaycard`: the verdict (train, rest soft, rest hard), the primary option
   and alternatives with reason chips, "Train anyway" and the week strip. Goldens: W41_weekplan, the W41_today days,
   W41_trainAnywayWed, the capacity and novice variants, W41_elbowCheck2, W41_testPostponed and DST_W43_windows.
6. **M4.6 Week tab and Plan it.** Before this step, T17 and the switch to real calendar writes (the open M2b.6 items) are
   done with your agreement. The Week tab shows recommended versus planned per day with the reasons, the day sheet, Plan
   it (a marked calendar event that is then read back as a planned gym session and sets the session's time budget) and
   next week. Golden: W41_planItFri.
7. **M4.7 Rotation and progress.** `rotate`: main lifts are locked for a mesocycle that follows the challenge block, and
   accessories rotate only when stalled or disliked. You → Progress with `ui.progress`: e1RM charts, personal bests and
   the gate bars.

### M5 — Review tooling (2–3 sessions)

- `fitness_review` views, `:sim` replay and counterfactual CLI, synthetic personas, ModelTuner
  proposals, `user_algo_params` flow, `docs/REVIEW.md` runbook.
- First real review after 4–6 weeks of logs.

### M6 — Optional

- Health Connect (bodyweight, runs, sleep), home-screen widget, GitHub Actions + Obtainium for
  installs away from the PC, Material 3 Expressive polish, onboarding polish for a friend.

### Ordering rationale

The logger comes first because nothing else works without clean data, and you can use it immediately.
The challenge comes before general prescriptions because three of your weekly sessions are OAP
sessions: that is where the app is most valuable now. Planning comes last because it depends on
everything else and its constants need real data.

---

## 12. Testing and quality

- **`:algo` unit and property tests** (JUnit 5 + kotest-property): every module, plus invariants
  (same input → identical output bytes; never an unloadable weight; load change per session within
  caps; "no time"/"occupied" never change the strength state; "don't train" muscles get no direct
  sets; challenge-reserved days never double-booked; protected windows never violated).
- **Golden fixtures**: your real week (Wed 2026-10-07; C Tue 19:45, A Thu 19:45, test Sun 13:00; BW
  70.7 kg; 20/22.5/27.5/47.5 kg; pins 40/50/35 lb) and the DST change on 2026-10-25.
- **Simulation**: 26–52-week synthetic histories for several personas (you, "friend 2×12",
  legs-only, no-legs, pull-focus); metrics and invariant checks.
- **Challenge golden simulations** committed to the repo; any rule change shows a diff.
- **Database**: RLS tests with two users (and a non-allowlisted one), grants, advisors after every
  migration.
- **UI**: Roborazzi screenshot tests at font scale 1.0/1.3/2.0, light/dark; on-device checklist for the
  timer, permissions and calendar writes.

---

## 13. Risks and mitigations

| Risk | Mitigation |
|---|---|
| **Scope**: ~45 algorithms specified. Building all before you can log a set would take too long, and most constants are unvalidated anyway. | One module per concern (contradictions resolved in C1–C36), milestone order logger → challenge → prescription → planning, every constant in params with an evidence grade. |
| **Elbow injury** (supinated one-arm work, negatives, a too-fast assisted pin progression, general curls/grip work near A/B). | Safety stage first, one-tap elbow check, pain protocol shared with the general planner, gated pin progression proposed, protected windows, no failure on high-risk lifts, disclaimer. |
| **Model-dependent 1RM** (+38 to +47 kg from one 3RM). | Personal curve with an interval, ×1.10 shown alongside, gate margins logged, method documented. |
| **Shared Free-plan Supabase project** (chess app, no backups, 500 MB cliff, pausing). | Own schemas, reviewed MCP migrations only, you change the exposed-schema setting, allowlist + RLS + composite FKs, no triggers on `auth.users`, size guard, exports, offline-first. |
| **Logging friction** (L/R × sets × RIR × flags × seconds). | One-tap RIR logging, prefilled values, hands-free hold timer, ≤ 6 extra taps per session, skip rates logged, conservative imputation (missing data never increases load). |
| **Rest timer killed by the phone's battery manager.** | Foreground service, `setAlarmClock`, battery exemption + autostart guidance, timestamp state, latency logging, on-device tests. |
| **Calendar misclassification or editing your own events.** | Primary calendar only, all-day/Todoist ignored, Dutch normalisation, challenge patterns, confirm when uncertain, per-event correction; writes only to events with the app's marker. |
| **Determinism breaks** (math library differences, map ordering, locale, clock). | Purity test for `:algo`, `StrictMath`, canonical rounding, injected clock, golden replays in tests. |
| **Overfitting personal constants** on little data. | Priors with shrinkage, minimum-sample gates, ±10 % change limits per fit, review-approved params only. |
| **Toolchain / signing** (no SDK yet, Java 25, lost keystore). | JDK 21 for Gradle only, documented bootstrap, keystore backup, reinstall blocked while unsynced data exists. |

---

## 14. Problems I foresee, and suggestions

1. **Your e1RM is probably lower than +42.8 kg.** With the personal curve you chose, the app will show
   about +39 kg. That doesn't change your working loads, but it does move the phase-1 gate further away.
   Expect the first test days in the app to look less rosy than the artifact — that's a measurement
   change, not lost strength.
2. **Session A's weekly −5 lb is the riskiest rule in the program.** Four build weeks equal ~+20 %
   one-arm load per block. I strongly suggest approving the gated version (P rule).
3. **The test battery is long and the order tires your grip early** (max reps second, hang third). A
   P rule offers max reps last; consider it so hang and top-hold numbers are comparable between tests.
4. **The artifact's fixed test dates and Todoist's "every 5 weeks" disagree** after the postponement
   (8 Nov vs 15 Nov). The proposal is block-relative dates (15 Nov), which keeps every block 5 weeks.
5. **No free gym day without relaxing the gap rule** — you chose to allow legs/push days on gap days;
   the planner will still keep the day before B/test free and avoid grip work 24 h before A.
6. **Your days move each week** (this week C Tue / A Thu instead of Wed / Fri). The app treats the
   calendar as the truth and only uses the preferred days as a fallback when it auto-writes sessions.
7. **Logging discipline matters more than any algorithm.** Most of the app's intelligence depends on
   honest RIR taps and reasons; the first weeks are partly calibration. An occasional RIR-check set on a
   machine (opt-in, ≤ 1/week) speeds that up.
8. **Free plan**: consider upgrading only if pausing or the 500 MB limit ever bites; the app works
   offline regardless.
9. **The assisted machine's 6.5 kg offset is an estimate.** A one-time check with a bathroom scale on
   the platform would make every assisted number exact; it's editable per machine.
10. **Bodyweight**: the OAP threshold moves with bodyweight (65 % × BW). Weekly weigh-ins (or Health
    Connect later) keep loads and gates honest.

---

## 15. Open items

Settled at M0: the phone is a OnePlus CPH2747 on Android 16 (API 36), so Live Updates are available and
the OnePlus battery guidance applies; the application id `dev.fitnessapp` is confirmed and installed;
`pg_jsonschema` is installed.

- Running frequency and priority (setting; default low).
- Muscle goals (set in onboarding, D6).
- Approve/decline each OAP `P` rule (M2 setup).
- Real calendar writes (D39): switched on by you in You → Calendars after the primary-calendar week of T17; until
  then the app writes only to the test calendar on the phone.
- Editing the challenge schedule (preferred days and times) and the habit times: moved out of setup by D35 and not
  built yet; needed before or at the cutover (M2b.6).
- Health Connect, widget, GitHub Actions + Obtainium (M6).
- Adding your friend as a user (allowlist row + onboarding pass).

---

## 16. Glossary

- **RIR** — reps in reserve: how many more reps you could have done.
- **e1RM** — estimated one-rep max. In this app it is on *system load* (bodyweight + added − assistance)
  for bodyweight exercises.
- **Personal rep curve (`k`)** — one number describing how many reps you get at a given fraction of your
  1RM; higher `k` = flatter curve = more reps at the same percentage.
- **Fractional sets** — volume counting where a set counts 1.0 for the target muscle and 0.5 for helpers.
- **Mesocycle / block** — 5 weeks: 4 build + 1 deload (aligned with the OAP block).
- **A / P rules** — challenge rules taken from your program (`A`) vs proposals awaiting your approval (`P`).
- **Contract** — what an active challenge tells the general planner: reserved sessions, blocked days,
  protected windows, owned muscles, add-on slots.
- **Snapshot** — a stored copy of a recommendation's inputs and outputs, so it can be replayed.
