# Decisions log

This file is the single source of truth for design decisions. Every other document in `docs/` and the
master `PLAN.md` must agree with it. When a decision changes, change it here first, add a dated entry
to the changelog at the bottom, then update the dependent documents.

Status legend: **USER** = decided by the user (2026-10-07 Q&A). **DEFAULT** = chosen by Claude as a
sensible default; the user can overrule it at any time. **CANON** = technical resolution of a
contradiction between research lenses (see `docs/research/critique.md`).

---

## 1. User decisions (2026-10-07)

| ID | Topic | Decision |
|----|-------|----------|
| D1 | Stack | **Native Kotlin + Jetpack Compose (Material 3).** Claude installs Temurin JDK 21 and the Android SDK command-line tools (no Android Studio). The user accepts the SDK license and enables wireless debugging on the phone once. Algorithms live in a pure Kotlin JVM module. |
| D2 | Phone | **Xiaomi / OnePlus / Oppo class device** (most aggressive background killers). The rest timer and session service must use every reliability measure: foreground service, `setAlarmClock`, battery-optimisation exemption, autostart guidance, on-device Doze test. Exact model and Android version still to be confirmed (affects Android 16 Live Updates). |
| D3 | Users | **Allowlist with only the user for now, built generically.** No hardcoded OAP logic, no sex/age-specific defaults that silently assume a young male, onboarding works for any user. Adding a friend later = one allowlist row. The other 5 accounts in the shared chess project are blocked by RLS. |
| D4 | OAP source of truth | **The app takes over.** At cutover the current OAP state is imported (phase, block, week, loads, pins, test history with validity flags). The app's rule engine recalculates loads after each session and test day. The Todoist session tasks are retired (only after explicit user confirmation at cutover), the artifact is frozen as a reference document, and the home habits (daily hang, plank) become in-app reminders. Until cutover the existing chat workflow continues unchanged. |
| D5 | Extra (non-challenge) training | **Separate legs/push sessions are allowed on challenge gap days.** The "≥1 rest day between gym sessions" rule applies only to sessions that load pull / grip / elbow flexors. Never a gym session the day before session B or a test day. Never heavy grip or elbow-flexor work in protected windows. Optional add-on blocks at the end of C (~35 min free) and A (~20 min free). Default maximum gym visits per week: 4 (setting). |
| D6 | Muscle goals | **User-configured in the app.** No goal is decided now. Onboarding asks; presets cover the examples from the brief (legs only, no legs, pull focus with push+legs maintained, balanced). Muscles owned by an active challenge show as "covered by challenge". |
| D7 | OAP strength gates | **Personal rep curve.** The gate metric (`wpu_e1rm_added`) is computed from the test-day 3RM through the user's fitted rep-capacity curve (RCM-1 `k` for the weighted pull-up), not through the fixed ×1.10 rule. The app shows the ×1.10 value and the 80 % interval next to it for transparency. No extra max single is added to test days. |
| D8 | Undefined / risky OAP rules | **Proposals tagged `P`, each approved individually** in the challenge setup. Rules already written in the artifact or the Todoist task descriptions (tag `A`) are executed exactly as written unless the user **approves** a `P` proposal that replaces them. Undecided proposals are inactive. (Wording corrected 2026-10-07, see C48.) |
| D9 | Set logging | **Tapping the RIR chip logs the set.** Weight/reps are prefilled; the row "reps left in the tank: 0 1 2 3 4 5+" is the log action, on every set, without prefilling or highlighting the RIR. Timed holds use "Max / Hard / OK / Easy" (seconds-in-reserve) chips as the log action. |
| D10 | Evidence vs preference | **Warn, keep the user's preference.** Non-blocking warning with a Why sheet (numbers, evidence grade, citations) and a one-tap "use suggested". Safety rules are always enforced (no failure on weighted / one-arm pulls or loaded dips, load-jump caps, pain protocol, challenge hard constraints). Mid-session load changes are one-tap suggestions, never automatic. |
| D11 | Calendar | **Read + auto-write.** Read the phone's synced calendars via `CalendarContract` (no Google OAuth), primary calendar only by default, all-day events and the Todoist calendar ignored. The app automatically creates and updates challenge sessions and test days for the current and next ISO week in the primary calendar, each with a marker line so it only ever edits its own events. Recommended general sessions are written when the user accepts them ("Plan it", one tap). Events without a marker are never modified unless the user taps "adopt" (needed once for the events Claude created by hand). |
| D12 | Language / numbers | **English UI, decimal point** (42.8 kg). Dutch calendar titles (`sessie C`, `één arm zwaar`, `testdag`, `hardlopen`) are still recognised. Exercise names stored in English and Dutch. |
| D13 | Load caps vs the user's own challenge rules | **The user's approved challenge rules win; the app warns.** Generic load-jump caps do not limit challenge RULE-mode loads; a jump above the generic cap produces a non-blocking WARN warning and a log entry (see C45). |
| D14 | In-session challenge rules | **Auto-apply with Undo.** Challenge in-session rules (e.g. session A first-set pin calibration ±5 lb, 90° hold failure → +5 lb, B grind → −2.5 kg) change the remaining sets immediately; the set card shows "Rule: … · Undo". General (non-challenge) in-session changes remain one-tap suggestions (D10). See C46. |
| D15 | Day before session A | **Allowed freely.** A general legs/push session may be planned on any gap day including the day before A; no whole-body (systemic) penalty term in v1. The 24 h protected window before A (no lats / elbow flexors / forearms) still applies (C12). See C50. |
| D16 | Android Auto Backup | **Off.** Supabase sync and the monthly JSON export are the backups; avoids restoring a stale local database that conflicts with sync. |

---

## 2. Defaults chosen by Claude (overrule any time)

| ID | Topic | Default |
|----|-------|---------|
| F1 | Supabase schema | New schema `fitness` (exposed to the Data API), plus `fitness_private` (helper functions, allowlist; not exposed) and `fitness_review` (analysis views for Claude Code reviews; not exposed, no grants). Grants to `authenticated` and `service_role` only, never `anon`. |
| F2 | Exposing the schema | The **user** adds `fitness` under Dashboard → Data API → Exposed schemas, keeping `public` and `graphql_public` (current value verified by probe: `public, graphql_public`). Claude never runs `supabase config push`, `supabase db push`, `migration repair`, or `alter role authenticator set pgrst.db_schemas` on this shared project. Verification afterwards with the PGRST106 probe. |
| F3 | Migrations | SQL files in `supabase/migrations/` (names prefixed `fitness_`), every object schema-qualified, applied through the Supabase MCP `apply_migration` **after the user has seen each migration**. `get_advisors` (security + performance) after each one; zero new fitness findings expected. `supabase db dump --schema fitness --data-only` before every migration once data exists. |
| F4 | Free plan | The project is on the Free plan (no backups, read-only above 500 MB, pause after 7 quiet days). Mitigation: the phone database is the source of truth, monthly in-app JSON export, size guard view, chess usage keeps the project awake. |
| F5 | Optional extension | Install `pg_jsonschema` in the `extensions` schema for structural validation of challenge definitions. **Approved by the user 2026-10-08**: installed by migration `fitness_007_jsonschema_extension` (M0); the CHECK on `challenge_definitions` follows in `fitness_018_jsonschema_constraint` (M2). |
| F6 | Running | Calendar events classified as runs (`hardlopen`, `run`, `duurloop`, …) count as leg fatigue only (no hypertrophy credit) plus timing penalties. Running priority setting default `low`. Health Connect (runs, weight, sleep) is a later milestone. |
| F7 | Equipment | First-run equipment checklist with Basic-Fit defaults (all editable): plates, dumbbell list, stack steps, Smith bar weight unknown → ask, assisted machine `{unit lb, step 5, offsetKg 6.5, ratio 1.0}`, own dip belt and belt plates. `requires_station` on every exercise. |
| F8 | Deloads | General training deloads in the same week as the active challenge's deload/test week (sets ×0.5, load kept, RIR +2), plus reactive triggers. Without an active challenge: reactive only. |
| F9 | Test dates | Block-relative: each block is 5 weeks counted from the test actually done (matches the Todoist "every 5 weeks" recurrence). Offered as a `P` rule because the artifact's table lists fixed dates. |
| F10 | Test order | From the Todoist test-day task (tag `A`): weigh-in → warm-up → weighted 3RM → max strict reps → one-arm dead hang L/R (fixed neutral handle) → one-arm top hold L/R → least assistance for one rep L/R. A `P` proposal moves max reps to the end (grip fatigue). |
| F11 | Session time budget | **Revised 2026-10-07 (C49):** calendar gym events are training time (travel happens before them — the existing events say "leave 19:35" for a 19:45 start), so budget = event duration − 5 min changeover; without an event, the user's default (75 min). Time-budget chips 45 / 60 / 75 / 90+. |
| F12 | Exercise stability | Main lifts locked for one mesocycle (5 weeks, aligned with the challenge block). At most one third of accessories rotated per mesocycle, only systematically (stalled, disliked, new region coverage). No randomness. |
| F13 | Supersets | Offered only when the plan exceeds the time budget or the user enables them; only pairs with stimulus-vector cosine ≤ 0.2. |
| F14 | RIR calibration sets | Opt-in, at most one per week, only on machine/cable/isolation exercises, never on challenge lifts. |
| F15 | Feedback prompts | Optional 1-tap readiness (Worse/Normal/Better) + elbow check (0–3) before gym sessions, session effort (sRPE 0–10) + joint pain after, soreness asked at the next session for the muscles about to be trained. Tap budget ≤ 6 per session outside set logging. The elbow check is never reduced while a challenge pain rule is active. |
| F16 | Visual style | Material 3, seed colour teal `#0B6A60` (continuity with the OAP artifact), advisory amber `#9C5217`, safety red `#8E3226`; follows system light/dark; dynamic colour optional; calm standard motion. |
| F17 | Distribution | Debug/dev builds via `gradlew installDebug` over adb Wi-Fi. Stable release keystore created at M0 and backed up (losing it forces a reinstall, which wipes unsynced local data). GitHub Actions + Obtainium is optional later. |
| F18 | Week | ISO week Monday–Sunday, time zone Europe/Amsterdam (DST ends 2026-10-25; golden tests must span it). |
| F19 | Explanations | Layered: ≤ 3 reason chips inline → Why sheet (numbers, rule id, evidence grade, citations, "use mine anyway") → trace export for reviews. |
| F20 | Rest-timer alert | Vibration waveform always; optional tone that ducks music; keep-screen-on during session default ON (toggle). |
| F21 | Auto Backup | Off (D16): `android:allowBackup="false"` / data-extraction rules exclude everything. |
| F22 | Application id | `dev.fitnessapp` (sandbox flavour `dev.fitnessapp.sandbox`). Changing it later means a new app and a data-wiping migration, so confirm before the first install. |
| F23 | SDK levels | `minSdk 26`, `compileSdk 37`, `targetSdk 36` (amended 2026-10-08: current AndroidX libraries require compileSdk 37; the target stays 36 so Android 17 runtime behaviour changes do not apply yet); revisit targetSdk when the phone's Android version is known. |
| F24 | Local DB encryption | No SQLCipher in v1 (relies on Android file-based encryption). |
| F25 | Calendar titles written by the app | Challenge events use the definition's title template (Dutch for OAP, matching the existing events, e.g. "Gym — sessie C (volume en techniek)"); general sessions use English labels; descriptions in English. |
| F26 | Review-param approval | After a review, the user approves the proposed `user_algo_params` change list as a batch (each change listed with its reason); nothing is written without that approval. |
| F27 | Build variants | `debug` and `release` share the application id and the release signing key; `debug` is the daily app and syncs normally; the `sandbox` flavour has sync and calendar writes disabled for experiments. |

---

## 3. Canonical technical resolutions

These resolve the contradictions listed in `docs/research/critique.md`. Each algorithm spec must use
exactly these values (all numeric values live in the versioned params JSON, not in code).

| ID | Area | Resolution |
|----|------|------------|
| C1 | Bodyweight fraction | `f_bw = 1.00` for pull-up / chin-up / one-arm / dip family (matches the challenge's "+kg" and "%BW" conventions). Push-up 0.64, knee push-up 0.49, feet raised 30 cm 0.70 / 61 cm 0.74, hands raised 30 cm 0.55 / 61 cm 0.41 (Ebben 2011), inverted row 0.60 (weak). Stored per exercise; anatomical 0.955 only as a review option. One `LOAD` function used everywhere. |
| C2 | Assistance | `assistKg(pin) = pin_native × unit_kg × ratio + offsetKg` from a per-machine calibration record `{machine_id, unit lb, step 5, min 0, max, offsetKg 6.5, ratio 1.0}`. `set_logs` stores raw pin, unit, machine id and computed assist kg. Rounding ties go to **more** assistance. Finger-assist levels are ordinal only, never used in load maths. |
| C3 | Rep-capacity curve | RCM-1: `cap(w) = 1 + k·(M/w − 1)`, `load(n) = M / (1 + (n−1)/k)`, `e1RM(w, r, q) = w·(1 + (r + q − 1)/k)`. Class priors `k0`: upper compound 30, lower machine compound 45, lower free compound 36, upper isolation 26, lower isolation 32; `σ_lnk = 0.30`. Same model as app_benchmarks' `1/(1+κ(r−1))` with κ = 1/k (alias only). Review SQL views never recompute e1RM; they read the engine-logged per-set values. |
| C4 | Strength estimator | SSE-1 (log-space local-linear-trend Kalman, robust/censored) is the single general estimator. App_benchmarks' A5 weighting ideas become SSE-1 variance multipliers via REASON-1. OAP gates use D7 (personal curve via RCM-1 `k` of `weighted_pullup`, applied to the test-day 3RM). The UI shows SSE-1 e1RM and the challenge gate value side by side, labelled. |
| C5 | Fatigue / readiness | Only the planning lens's MuscleReadiness: per-muscle exponential decay, τ = 22 h (small upper, calves, abs, obliques, upper traps), 26 h (chest, front delt, lats, upper back), 30 h (quads, glutes, adductors), 34 h (hamstrings, lower back); `F_REF` 8 (chest, lats, upper back, quads, glutes, hamstrings) / 6 otherwise; tissue channels τ = 48 h; systemic channel τ = 48 h. Exclude a muscle below R = 0.5, scale 0.5–0.8. Soreness-driven κ adaptation (A1) feeds the offline ModelTuner, not the live model. LP's between-session REST-1 part and A1 are dropped. |
| C6 | RIR tables | One table in params (`SetCredit.v1`): stimulus `[1.00, 1.00, 0.95, 0.90, 0.80, 0.50]` for RIR 0–5, 0 above; fatigue `[1.35, 1.15, 1.00, 0.90, 0.80]` for RIR 0–4, 0.50 above. Hard set = RIR ≤ 4. Used by volume bars, warnings, readiness and challenge coverage. |
| C7 | Unilateral volume | One L+R pair = 1 set for the muscle; each side row counts 0.5. Per-side volume and asymmetry tracked separately. |
| C8 | Isometrics | `hold_factor` = 0.5 for a hold ≥ 10 s at seconds-in-reserve ≤ 2, else 0.25 (weak evidence, review constant). Dead hangs credit forearms only. Eccentric-only, isometric and finger-assisted sets never enter SSE-1; they go to HOLD-1 or challenge metrics. |
| C9 | Taxonomy | Two levels from the exercise-selection lens: 18 groups (chest, front_delts, side_delts, rear_delts, lats, upper_back, upper_traps, biceps, triceps, forearms, abs, lower_back, glutes, abductors, adductors, quads, hamstrings, calves; optional neck) for goals and volume; ~32 regions for variety bonuses and challenge mapping (e.g. brachialis → biceps group, forearm_flexors → forearms, mid/lower traps → upper_back, core_antirotation → abs). Validators reject unknown keys. Muscle-map SVG drawn from the group list. |
| C10 | Challenge exercises | Must be catalog entries (`catalogRef`) or challenge-local entries that pass the same catalog validator (coefficients only in {0, 0.25, 0.5, 0.75, 1}). Coverage computed by SetCredit on materialized sets. |
| C11 | Gap-rule scope | Per D5: challenge definition declares scope; OAP: `minGapDays 1` applies to sessions tagged `pull_heavy | grip_heavy | elbow_flexor_heavy`; `restDayBefore B|TEST` scope `all_gym` (a run that day is a warning only). The planner reads scope from the contract, never hardcodes it. |
| C12 | Protected windows | Live only in the challenge contract (`protectedWindows[]` with mode `hard|penalty`, hours, muscles/tissues/tags, Rmin). OAP defaults: hard 24 h before A for lats/biceps/brachialis/forearms; hard 24 h all-gym before B/TEST; 24 h after B for elbow flexors (P); soft readiness penalty out to 30 h (48 h before B/TEST). |
| C13 | Deload | One DeloadPolicy: planned general deload synced to the challenge block (F8); reactive triggers = union of planning and STALL-1 global rules, evaluated weekly; transform sets ×0.5, load kept, RIR +2. The −10 % load cut applies only to STALL-1 tier 1 for a single exercise. |
| C14 | Targets & warnings | WeeklyTargets: improve BASE 10/12/14 (novice/intermediate/advanced), focus +4, maintain max(3, round(BASE/3)) with ≥ 1 hard set/week at normal load, skip 0; per-muscle MRV caps; ramp from the soreness × performance signal, capped at +20 %/week (Camargo preprint, weak). One WarningEngine: weekly improve < 10 INFO, < 6 WARN, < 3 STRONG; per-session > 11 INFO, > 15 WARN; display policy UI-10. RP MEV values are never applied directly to fractional counts. |
| C15 | Between-session progression | RX-1 is primary (model mode, caps +5 % compound / +10 % isolation, −10 % floor). DOUBLE mode (when one load step > 1.5 reps or bodyweight-only) advances with the Gravl rep ladder (8-7-6 → 8-8-6 → 8-8-7 → 8-8-8 → next step). A7's fail counter (2 failed exposures → −7.5 %) is STALL-1's fast path. RULE mode for challenge items (model runs in shadow and is logged). One cap table in params. |
| C16 | In-session autoregulation | UI-06 decides when to show a suggestion (first set \|ΔRIR\| ≥ 2; two consecutive sets same sign ≥ 1; ≥ 2 reps short; TOO_EASY/TOO_HARD reason), AUTO-1 computes the new load (clamp −8 % / +6 %, hysteresis 1.5 RIR), max 1 suggestion per exercise per session, default action Keep, never auto-applied (D10). REASON-1 is the only source of reason semantics. |
| C17 | RIR bias | The general engine applies RIRCAL-1's bias `b` (init 0.5, learned, clamp −1..3). Challenge rules evaluate the raw reported RIR (rules were written against what the user perceives). |
| C18 | Rest | REST-1 table is the single source: 180 s strength-heavy (≤ 5 reps or ≥ 85 % e1RM), 150 s lower compound, 120 s upper compound, 90 s isolation and holds, 60 s core, 45–60 s warm-ups; modifiers +30 s (RIR ≤ 0.5 or reps short), +15 s (next-set fatigue > 0.25), −15 s (RIR ≥ target + 2); clamp 60–300 s (timer hard max 900 s); challenge rest values override. Presented as a time-vs-performance trade-off (ACSM 2026), not a hypertrophy requirement. |
| C19 | Time model | TIME-1 is the only duration model (3 s/rep, +5 s per set, 60 s transition, setup per equipment, warm-up from the challenge or 10 min), calibrated by EWMA (α 0.3) from set timestamps. UI-02 trimming and challenge add-on capacity call TIME-1. |
| C20 | Calendar | One `CalendarClassifier.v1` with typed outputs `{challenge_session(slot), planned_gym(type), run(intensity, minutes), busy, ignored}`: tech-stack lens scoring + challenge title patterns from the definition + max-weight matching to challenge slots + marker detection. Runs are their own kind (never rejected). Per-event overrides stored; classification logged as `session_events` type `calendar_classified`. |
| C21 | Rule language | FitExpr (typed infix, units kg/lb/s, decimal arithmetic, explicit `all()/any()`, whitelisted pure functions) + declarative actions, staged ECA semantics (safety < data < structural < progression; first-writer-wins; locks; bounded cascade). No JSON-Logic variant. |
| C22 | Numerics | FitExpr evaluates in decimal (BigDecimal, MathContext 34, HALF_EVEN). Statistical engine uses `double` + `StrictMath`. Every value crossing a module boundary or stored is rounded to a canonical grid (kg 0.01, s 0.1, ratio 1e-4) before hashing/comparison. |
| C23 | Sync | Data-model lens trigger-based LWW: plain PostgREST upsert, server `BEFORE UPDATE` trigger drops stale rows by `client_updated_at` (tie-break `device_id`), pull cursor on `server_updated_at` with 120 s overlap. Append-only tables use ignore-duplicates. |
| C24 | Telemetry tables | `recommendations` (snapshot: module id@version, ENGINE_VERSION, params_hash, catalog_version, challenge definition sha256, inputs_hash (RFC 8785 JCS + SHA-256), inputs, outputs, trace) + `session_events` (all UI, lifecycle, rest-timer and sync events) + `challenge_events` (event-sourced enrollment input) + `challenge_rule_events` (ECA audit) + `set_logs` rest fields. No separate `ui_event`, `rest_event`, `recommendation_log` or `calendar_classification_log` tables. |
| C25 | Exposed schemas | New value `public,graphql_public,fitness`; re-probe before and after. |
| C26 | Preferences | 5-state enum `never / dislike / neutral / like / favorite` → multipliers 0 / 0.6 / 1.0 / 1.15 / 1.495. No separate stars. Disliked-necessity gate (ELIG-1): unlock a disliked exercise only when no non-disliked option has group coefficient ≥ 0.75 (improve) / ≥ 0.5 (maintain) for a muscle that needs work; `never` is never auto-used. |
| C27 | Detraining / re-entry | Load comes only from SSE-1 decay (21-day grace, then −0.75 %/week, cap 15 %, growing variance). Volume/RIR from the planning re-entry rule (> 21 days untrained: sets ×0.6, RIR +1 for 2 sessions). First exposure of a new or long-unused exercise is a calibration set. Challenge pause rules override for challenge exercises. A11's breakpoint table is dropped. |
| C28 | Rest timer alarm | `setAlarmClock` by default (exact, Doze-exempt); `setExactAndAllowWhileIdle` only as a user option when logged p95 latency ≤ 2 s. `USE_EXACT_ALARM` (sideloaded APK) with a `canScheduleExactAlarms()` fallback. FGS type `health` (needs ACTIVITY_RECOGNITION) with `specialUse` fallback. |
| C29 | Citations | Coleman et al. **2024** PeerJ (deload). Camargo 2026 is a preprint (weak). Remmert per-session PUOS is a preprint (weak). Marzagao 2026 is a single-author preprint (weak). Several load-prescription citations were from memory (Lasevicius 2018, Schoenfeld 2017 volume, Sanchez-Medina 2011, APRE table): verify before encoding constants that cite them. |
| C30 | Engine versioning | One `ENGINE_VERSION` semver for `:algo` plus per-module ids (e.g. `readiness@1.0.0`) registered in `fitness.algorithm_versions` with their constants hash. Every recommendation row stores module id@version, params_hash, catalog_version and challenge definition sha256. |
| C31 | Clock & weeks | One `Clock/Week` utility in `:algo`: `Instant` for elapsed hours, `LocalDate` in the user's zone for day/week, explicit `isoWeek()`, injected clock. While a challenge is active, the general mesocycle week = the challenge's block week (repeated/extra weeks included); otherwise a 5-week calendar cadence. |
| C32 | Bodyweight | One `BW(t)`: EWMA (α 0.3) of weigh-ins for prescriptions; the raw test-day weigh-in for gates; snapshot stored on every set and session; weigh-in prompt when > 14 days stale. OAP threshold stored as a ratio × BW, never a fixed kg. |
| C33 | Safety first | A single `PainEvent` fact → `SafetyState` (joint flags + challenge modes), evaluated before anything else on every event; exports forbidden tags to ELIG-1/INJ-1 and the planner. Not medical advice (disclaimer in app). |
| C34 | Session structure | A session has one or more blocks `{challenge_block, general_block}`. A general/add-on block is built by SEL-1 with budget = add-on capacity (TIME-1), allowed patterns and forbidden muscles from the contract, no second general warm-up (specific warm-up sets only). Home habits are tiny sessions (`workout_sessions.kind ∈ {gym, home, test}`). |
| C35 | Free / unplanned sessions | Start session → pick a template (challenge slot, family combination, or empty). A calendar event is linked post hoc (same day, ±3 h). Challenge `session.completed` fires on the template slot, not on the calendar. |
| C36 | Calendar vs challenge state | Challenge state wins for slot type: if a test day is postponed, the app rewrites its own (marked) event to session B, or flags an unmarked event for the user to adopt/rename. |

### 3.1 Cross-document resolutions (added 2026-10-07 after the spec pass)

These settle conflicts found between the detailed specs. Owner = the document that is authoritative
for the topic; other documents reference it instead of restating it.

| ID | Area | Resolution |
|----|------|------------|
| C37 | Calendar marker | One format everywhere: last line of the event description `[fitapp:v1 <uuid> <slotKey>]`, where `<uuid>` is the enrollment id (challenge events) or the recommendation id (general sessions) and `<slotKey>` matches `[A-Za-z0-9_./-]{1,64}`: challenge `oap/<slot>/<isoYear>-W<ww>` (e.g. `oap/C/2026-W42`), general `gen/<isoYear>-W<ww>/<isoDow>`. Same regex in the DATA_MODEL `calendar_events.marker` CHECK, the ANDROID parser, UX, ALGORITHMS_PLANNING and the challenge definition's `writeBack`. Edit policy (owner ANDROID §16.7): user time edits win; a user-edited title/description stops app updates for that event; the app deletes only its own unmodified future events; user deletions are respected. |
| C38 | Stored vocabularies | DATA_MODEL §8 value domains are canonical for every stored code (load modes, set kinds, sides, set flags in snake_case such as `grip_slip_before_last`, `torso_rotation`, `hip_rotation`, `jerky`, `technique_breakdown`, session kinds, reason codes, metric codes). Challenge definitions and all docs use these codes. Catalog slugs are snake_case; the catalog seed (M1) must contain every slug referenced by a challenge definition, and the DATA_MODEL migration slug list is that union. |
| C39 | Telemetry vocabulary | DATA_MODEL §9.8.1 event-type registry is canonical (snake_case `<area>_<event>`); it is the union of the UX §20 and ANDROID event names after renaming to that scheme. UX and ANDROID reference the registry. Payloads never contain free text. |
| C40 | Local schema & files | ANDROID §8 owns the Room schema (timestamps, outbox payload policy); DATA_MODEL §12 summarises and references it. Bundled params live at `algo/src/main/resources/fitness/params/`. Data export container is `.json.gz` (`json_gz` allowed in the `exports.format` CHECK). |
| C41 | Params keys | `<module>.<constant>` with registry module ids (dots allowed) and lower snake_case constant names, e.g. `rx.cap_up_compound`. |
| C42 | Extra numeric grids | In addition to C22: reps grid 0.01; internal statistics (variances, covariances) at 12 significant digits. |
| C43 | Catalog attributes | `hold_class ∈ {grip, elbow, shoulder, trunk, knee, ankle}` per hold exercise (selects the HOLD-1 exponent); level-based stacks may carry `level_kg` (without it they are ordinal: DOUBLE mode, excluded from SSE-1). |
| C44 | Volume credit details | Coefficient levels 1.0 / 0.75 / 0.5 earn fractional-set credit equal to the coefficient; 0.25 counts toward fatigue only. SetCredit uses the reported RIR (the RIRCAL bias is applied only inside the estimators). Abductors: τ 30 h, F_REF 6. Obliques are a region of the abs group. Regions: 34 + optional neck (supersedes "~32" in C9). |
| C45 | Caps vs challenge rules (D13) | Generic load-jump caps do not apply to challenge RULE-mode loads; any excess raises warning WE-S2 at WARN severity and is logged. DOUBLE mode may advance exactly one loadable step even above the cap; for high-risk exercises only when the ladder start at the new load is ≥ r_lo. Other SAFETY rules (pain protocol, no failure on high-risk lifts, protected windows) still bind challenges. |
| C46 | In-session challenge rules (D14) | Auto-applied to the remaining sets with a visible "Rule: … · Undo"; limited per rule per item; an Undo is logged as an override with reason. The C16 limit (max 1 suggestion per exercise per session, never auto-applied) applies to general items only. |
| C47 | Frozen progression | In deload, test_pending, extra_deload and injury modes, progression-stage writes to snapshot variables are suppressed, except rules marked `allowInFrozen` (protective rules). |
| C48 | Proposal activation | Rule decisions are stored per proposal id (one proposal may switch several rules). An `A` rule runs unless the `P` proposal replacing it is approved; undecided proposals are inactive. |
| C49 | Session budget | Supersedes the 15-min buffer in F11: calendar gym events are training time; budget = event duration − 5 min. Challenge add-on capacity = booked minutes − TIME-1 estimate − 5 min. The "~35 / ~20 min" figures in D5 are indicative; the real capacity comes from TIME-1 and is calibrated from logs. |
| C50 | Gap days (D15) | General legs/push may be planned on any challenge gap day, including the day before A; no systemic penalty term in v1. One gym visit per day: on a challenge day extra work is only possible as an add-on. Light pull work on gap days is allowed only for exercises with `grip_demand ≤ 1` and biceps-group coefficient < 0.5 outside protected windows (e.g. reverse pec deck), and only when the user's goals ask for it. |
| C51 | Deload + re-entry | Never stacked: set factor = min of the two, RIR offset = max. |
| C52 | Planner ties | Plans whose scores differ by ≤ 0.05 are ordered: earlier session first, then fewer sessions, then higher score J, then lexicographic. (Amended 2026-10-07: J added so a lower-scoring plan never wins inside the band by accident.) |
| C53 | UI deterministic functions | ALGORITHMS_PLANNING owns the specs of all `ui.*` functions; UX §19 references them and only adds display details. `today` outputs a verdict `TRAIN / REST_SOFT / REST_HARD` with typed reason codes and uses a relative closeness margin `ui.todaycard.close_rel = 0.10`. |
| C54 | Platform constants & behaviour | Timer, notification, reminder and sync-backoff constants live in `:app` `PlatformConfig`, outside `params_hash`, and are logged in event payloads. ANDROID is authoritative for platform behaviour: "standard exact" alarm option offered after 30 logged rests, rest pre-warning only while the app/FGS is in the foreground, notification channel ids as in ANDROID, internal route names instead of deep links, assist kg shown with 1 decimal. |
| C55 | Rest fields | DATA_MODEL `set_logs` column names (`rest_before_s`, `rest_timer_planned_s`, `rest_timer_overridden`, …) are canonical; UX and ANDROID use them. |
| C56 | Superset rest | 60 s between superset members (REST-1); TIME-1 and ORD-1 read rest durations from REST-1. |
| C57 | Small semantics | `too_tired` has the same effect as `feeling_bad`. C16's first-set ΔRIR compares the bias-corrected reported RIR with the model-predicted RIR. STALL-1 actions on user-owned fields (sets, reps, RIR) are surfaced as suggestions (D10). A first-exposure calibration set automatically plans the remaining sets of that exercise. |
| C58 | Review outputs | Snapshot outputs include the keys the review metrics need: `sse` one-step-ahead keys (`n_eff_pred`, `z_pred_lo`, `z_pred_hi`, `l_prior`, `p_ll_prior`) and output keys for hold, stall, rcm, fatigue, safety, autoreg, warmup, rotate, rest and time, all listed in DATA_MODEL §10.2. |
| C59 | Cutover import | No historical set rows are fed to RCM-1; the OAP gate uses the class prior k = 30 until RCM-1 has informative windows. Historical tests are imported as metric observations with validity flags; the 2026-08-30 least-assistance value (no arm recorded) is imported for both arms. |
| C60 | Advisor exceptions | Allowed after a migration: INFO `unused_index` on new fitness indexes during their first 8 weeks, and a security-definer lint on `fitness_private.is_allowed()` (not exposed). Everything else must be zero. |
| C61 | RLS tests | Use synthetic UUIDs inside rolled-back transactions; never real ids of other users of the shared project. |
| C62 | Gate threshold basis | A phase gate is evaluated on the test-day bodyweight; the dashboard also shows the forward threshold at the current BW(t). |
| C63 | Logging details | Warm-up sets are logged with "Done" (no RIR); D9 applies to working sets. Test-day attempts use "Record"; a 3RM is stored with RIR 0. An "Add a set" action exists. The Live Session shows an overrun line when > 10 min over budget. |
| C64 | Defaults | Training age default novice when onboarding is skipped. Pain severity chips map to Mild 2 / Moderate 5 / Sharp 8 on the 0–10 scale (stop threshold 4). |
| C65 | OAP test content | The test template includes the unassisted 5 s one-arm negative per arm (needed for gate criterion 3, which the artifact requires on a test day), in the order given by the definition. |
| C66 | Eccentric-only sets | Logged by lowering time (seconds, with the `jerky` flag) plus the effort chips Max / Hard / OK / Easy, like holds; no RIR chip (RIR is meaningless for negatives). D9 covers dynamic working sets. `set_logs.ecc_s_actual` stores the shortest lowering of the set; FitExpr `ex.<key>.ecc_s` reads it. |
| C67 | Pulling append-only tables | Incremental pulls (DATA_MODEL §11.6 owner) for `challenge_events`, `challenge_rule_events`, `params_snapshots` and `exports`, not only at bootstrap. |
| C68 | Code locations | Packages `dev.fitnessapp.*`; `dev.fitnessapp.algo.EngineVersion`; one shared calendar marker render/parse implementation in `dev.fitnessapp.algo.core.calendar`; resources under `algo/src/main/resources/fitness/` (params, catalog, presets, challenges). |
| C69 | UI function details | ALGORITHMS_PLANNING adopts the UX requests: `ui.prefill` hold step 0.5 s below a 10 s target (1 s otherwise), right side capped at the left side's reps, post-log RIR deviation `ui.prefill.post_rir_delta = 2`, hold ranges judged against their lower bound, no prefill history across load modes; a `substitute` context in `ui.reasonchips`; the elbow symptom follow-up tap is a safety tap outside the tap budget; `ui.progress` PR rules: rep bests, `ui.progress.hold_pr_min_gain_s = 0.5`, `ui.progress.pr_min_history = 1`, no PR from a set with a pain reason. |
| C70 | Holds and caps | WE-S2 (load-jump cap warning) is not evaluated for HOLD-1 items; hold progression (seconds, plates, pins on holds) is governed by its own rules. |
| C71 | Challenge in-session limits | The once-per-item-per-session limit applies only to in-session rules that change load or reps; `capTarget` and notify-only rules may act after every set (e.g. `ins.scap.matchLeft`). |
| C72 | Platform/telemetry details | Rest pre-warning only while the app UI is visible (ANDROID §12.4). `instance_key` is the full 64-hex key (DATA_MODEL §9.7.3). The access check is a read probe, not an RPC. The telemetry payload string pattern allows `/` (marker slot keys). `fgs_started.fgs_type` includes `none` (untyped service below API 34). |
| C73 | Milestone placement | Catalog seed + validator and `ui.reasonchips` ship in M1 (logger); `rotate` ships in M4 (planning). |

---

## 4. Facts imported from the user's existing program (for the OAP definition)

Sources: artifact `B56eCk6wQMfAgSXBTCwmpW` (updated 2026-10-07) and the Todoist project "One-arm
pull-up" task descriptions (read 2026-10-07). Todoist adds rules the artifact does not state:

- **Session A, assisted one-arm pull-up:** the first set is a calibration set — 1–2 reps → +5 lb on the
  pin, ≥ 5 reps → −5 lb, 3 clean reps with one in reserve is right. Then −5 lb every week. If the
  end-of-session one-arm calibration negative (5 s, no help) is controlled, the pin drops 10 lb
  instead of 5. Warning noted at 35 lb (≈ 22.4 kg effective ≈ the 30-08 one-rep maximum).
- **Session A, straight-arm hold:** 3 rounds × 6 s per arm on the fixed neutral handle; when all six
  attempts are clean at 6 s → 8 s.
- **Session C, assisted one-arm pull-up:** 3×5/arm at 50 lb, RIR 2; two easy sessions in a row → −5 lb;
  fewer than 4 reps → +5 lb.
- **Session C, weighted pull-ups 4×5 @ 20 kg** is the measured anchor; A = anchor + ~1 rep (22.5 kg),
  B = anchor + 3 reps (27.5 kg), 2.17 kg per rep.
- **Plank:** 3×20 s/side; when all six sets on all three days are clean → 30 s → then a plate on the upper back.
- **Daily two-arm hang:** 4–5 sets, 2–3 min total, pronated or neutral (never supinated), ≥ half on a
  fixed bar/handle, chalk, thumb around, stop 5–10 s before slipping; once a week measure the best time
  fresh; skipped on A day; halved in the deload week; first thing dropped on elbow symptoms.
- **Test-day order:** 3RM (supinated) → max strict reps (supinated) → one-arm dead hang per arm (fixed
  neutral handle) → one-arm top hold per arm (supinated) → least assistance for one rep (add the
  machine's 6–7 kg). Test day replaces session B. Todoist recurrence: every 5 weeks.
- **Current week ordering in calendar:** C Tue 2026-10-06, A Thu 2026-10-08, test day Sun 2026-10-11
  13:00 (the documented rhythm is C Wed / A Fri / B Sun; days move week to week).

---

## 5. User actions required (not things Claude can do)

1. Accept the Android SDK licenses when `sdkmanager --licenses` runs (M0).
2. Enable Developer options → Wireless debugging on the phone and pair once with `adb pair` (M0).
3. Add `fitness` to Supabase Dashboard → Data API → Exposed schemas (keep `public`, `graphql_public`) (M0).
4. Review each migration before Claude applies it (M0 onward).
5. Grant on the phone: notifications, exact alarms (if asked), calendar read/write, activity recognition,
   battery-optimisation exemption, and on Xiaomi/OnePlus/Oppo the "Autostart" / "No restrictions"
   battery setting (M1).
6. Confirm the Basic-Fit equipment checklist once (M1).
7. Approve or decline each `P` rule in the OAP challenge setup (M2).
8. Confirm the cutover: which Todoist tasks to complete/delete and when the artifact is frozen (M2).

---

## Changelog

- 2026-10-07 — Initial decisions after research workflow (9 agents) and three Q&A rounds.
- 2026-10-07 — After the detailed spec pass: user decisions D13–D16 (fourth Q&A round), D8 wording
  corrected, F11 revised, defaults F21–F27, cross-document resolutions C37–C65.
- 2026-10-07 — After the reconcile + verification pass: C52 amended (J in tie order), C66–C73 added.
- 2026-10-08 — M0 build decisions (user Q&A): (1) F5 approved, `pg_jsonschema` installed at M0.
  (2) `profiles` and `gyms` pulled forward into M0 as migration `fitness_006_profiles_gyms` so the first
  sign-in proves the write path; with the new `fitness_007_jsonschema_extension`, the later migrations
  shift by two (catalog 008/009 … review views 017, jsonschema constraint 018); DATA_MODEL 13.2,
  CHALLENGE_ENGINE and OAP_CHALLENGE updated. (3) The full `default-params-1.0.0.json` is authored at M0
  and seeded by migration 005 as planned. (4) JDK 21 is the portable Temurin zip in
  `%LOCALAPPDATA%\Programs\Temurin-21` (the quiet MSI needs admin rights; ANDROID 3.2 option A fails
  non-elevated), registered for Gradle via `org.gradle.java.installations.paths` in the user-level
  `~/.gradle/gradle.properties`. (5) Versions at scaffold: Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21,
  KSP 2.3.12, Roborazzi 1.76.0 (others as ANDROID 3.8). (6) The Android SDK root is
  `C:\Users\sivuc\Android\Sdk`, not `%LOCALAPPDATA%\Android\Sdk` (ANDROID 3.4): the Claude desktop app is an
  MSIX-packaged app, so new folders its tools create under `AppData\Local` are redirected into the app's
  private `Packages\Claude_…\LocalCache` and are invisible to the user's own terminal. Toolchain folders
  that the user must also use live outside `AppData`. (7) F23 amended (user Q&A): compileSdk 37 with targetSdk 36,
  because Compose BOM 2026.09.00 and core-ktx 1.19.1 require compileSdk 37.
- 2026-10-08 — Params 1.0.0 authoring decisions (user Q&A): params gain a `regex` value type for the
  calendar lexicons (ALGORITHMS A8.2); times of day are stored as minutes after local midnight;
  `sse.detrain_cap` = 0.1625 ln so the cap is exactly the 15 % of C27; `today.reason_codes` gains
  `NOTHING_TO_TRAIN` and `NO_ELIGIBLE_EXERCISE` (ALGORITHMS_PLANNING §0.6). Generation notes and the
  remaining flagged items: `docs/research/params-1.0.0-notes.md`.
