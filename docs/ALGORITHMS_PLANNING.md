# Planning, accounting, selection and UI algorithms

| Field | Value |
|---|---|
| Document | `docs/ALGORITHMS_PLANNING.md` |
| Version | 1.1.0 (2026-10-07), specification for implementation (M1 to M5, C73); 1.1.0 aligns the document with DECISIONS D13-D16, the revised F11, F21-F27 and the cross-document resolutions C37-C65 |
| Engine modules | every module in §0.1 at `1.0.0` (nothing has shipped yet, so the module versions are unchanged) |
| Source of truth | `docs/DECISIONS.md` (D1-D16, F1-F27, C1-C65). Where this document cites an id, that decision is binding. Where this document had to choose something DECISIONS.md does not cover, the choice is marked **[this doc]** and listed again in §25 Open issues. Where a C-resolution names another document as the owner of a topic (for example ANDROID for the calendar edit policy, DATA_MODEL for stored vocabularies and the telemetry registry), this document references the owner instead of restating it. |
| Research inputs | `docs/research/planning.md` (primary), `exercise_selection.md` (primary for selection), `app_benchmarks.md`, `ux_ui.md`, `tech_stack.md`, `challenge_engine.md` (integration contract), `critique.md` |
| Related documents | `docs/DATA_MODEL.md` (tables, enums E1-E54, snapshot kinds), `docs/UX.md` (screens), `docs/challenges/oap.challenge.v1.json` (the OAP definition and its contract data) |
| Readers | the user, and future Claude Code sessions that implement the app and periodically review its algorithms |

This document specifies, at implementation level, every deterministic algorithm that decides **what to train, when, and
with which exercises**: calendar classification, the safety state, volume and fatigue accounting, readiness, weekly
targets, running interference, the session dose allocator, the weekly planner and the "what to train today"
recommender, the exercise catalog and the selection chain (preferences, eligibility, beam search, ordering,
substitution, rotation, time fitting, injury filter), the pure UI-side functions that present these decisions, and the
offline tools (tuner, simulation harness, replay) that let future Claude Code sessions review and tune the constants.

It does **not** specify load prescription (RX-1, AUTO-1, REST-1, WARMUP, REASON-1, the WarningEngine), the estimators
(RIRCAL-1, RCM-1, SSE-1, FAT-1, HOLD-1, STALL-1), the load and bodyweight functions (LOAD, BW), or the challenge engine
(CDS, FitExpr, enrollment, ECA, materialization, the challenge planner, the contract, calendar matching and
write-back). Those live in their own documents; this one states only the interface it consumes (§0.5).

---

## 0. How to read this document

### 0.1 Modules specified here

| Module id (`id@1.0.0`) | Registry area | Pipeline layer | Kotlin home | Section |
|---|---|---|---|---|
| `calendar.classify` | core | L0 normalize | `:algo` | §1 |
| `safety` | core | L1 safety | `:algo` | §2 |
| `setcredit` | accounting | L3 | `:algo` | §3 |
| `readiness` | accounting | L3 | `:algo` | §4 |
| `targets` (WeeklyTargets + DeloadPolicy + re-entry) | targets | L4 | `:algo` | §5 |
| `runs` (RunInterference) | targets | L4 | `:algo` | §6 |
| `allocator` (SessionDoseAllocator) | planning | L5 | `:algo` | §7 |
| `weekplan` (WeeklyPlanner) | planning | L5 | `:algo` | §8 |
| `today` (TodayRecommender) | planning | L5 | `:algo` | §9 |
| `catalog` (CAT-1) | selection | L0 data + CI | `:algo` (+ seed JSON) | §10 |
| `pref` (PREF-1) | selection | L6 | `:algo` | §11 |
| `elig` (ELIG-1) | selection | L6 | `:algo` | §12 |
| `select` (SEL-1) | selection | L6 | `:algo` | §13 |
| `order` (ORD-1) | selection | L6 | `:algo` | §14 |
| `substitute` (SUB-1) | selection | L7 | `:algo` | §15 |
| `rotate` (ROT-1) | selection | L6 (mesocycle start) | `:algo` | §16 |
| `time` (TIME-1 + UI-02 trimming) | selection | L6 / L7 | `:algo` | §17 |
| `injury` (INJ-1) | selection | L6 / L7 | `:algo` | §18 |
| `ui.todaycard` (UI-01) | ui-deterministic | presentation | `:app` (pure Kotlin, no Android types) | §19.1 |
| `ui.reasonchips` (UI-05) | ui-deterministic | presentation | `:app` (pure Kotlin) | §19.2 |
| `ui.prompts` (UI-07) | ui-deterministic | presentation | `:app` (pure Kotlin) | §19.3 |
| `ui.volumebars` (UI-09) | ui-deterministic | presentation | `:app` (pure Kotlin) | §19.4 |
| `ui.warndisplay` (UI-10) | ui-deterministic | presentation | `:app` (pure Kotlin) | §19.5 |
| `ui.progress` (UI-11) | ui-deterministic | presentation | `:app` (pure Kotlin) | §19.6 |
| `ui.prefill` (UI-04) | ui-deterministic | presentation | `:app` (pure Kotlin) | §19.7 |
| `tuner` (ModelTuner) | offline | review | `:sim` | §20.1 |
| `sim` (SimulationHarness) | offline | review / CI | `:sim` | §20.2 |
| `replay` | offline | review / CI | `:sim` | §20.3 |

Pipeline precedence (binding): **safety > challenge > user hard constraints > general**. Evaluation order: L0 normalize
→ L1 safety → L2 challenge → L3 accounting/estimation → L4 targets → L5 planning → L6 session build → L7 in-session →
L8 post-session. A module never reads an output of a later layer of the same evaluation; where a later layer feeds an
earlier one (for example a logged set feeding readiness), it does so through the next evaluation.

What counts as a "user hard constraint" here: the maximum number of gym visits per week (D5, default 4), the
availability windows, exercises set to `never` (C26), equipment that is not present (F7), and calendar events the user
put in the calendar (they are anchors, never moved by the app, D11).

### 0.2 Conventions

**Units and numbers.** Mass in kg (`Double`), durations inside a session in seconds, readiness time in hours, budgets in
minutes. Every value that crosses a module boundary or is stored is rounded to the canonical grid of C22 (kg 0.01,
s 0.1, ratio, coefficient and score 1e-4) and C42 (reps 0.01; internal statistics such as variances at 12 significant
digits) before hashing or comparison. The planning and statistical math uses
`double` and `StrictMath` (C22). Scores compared for ranking are first rounded to 1e-6; two scores are equal when the
rounded values are equal; ties are then broken by the explicit tie-break order given in each module (never by
iteration order). Two helpers are used throughout: `ceil_half(x) = ceil(2x − 1e-9)/2` and
`round_half(x) = floor(2x + 0.5)/2` (planning quantities are in steps of half a set).

**Time (C31, F18).** One injected `Clock/Week` utility from `core:clock`. Elapsed hours (decay, windows, "within 8 h")
are always computed from `Instant`s. Calendar days, ISO weeks (Monday to Sunday) and "the day before" are computed
from `LocalDate` in the user's zone (default Europe/Amsterdam). DST ends on **2026-10-25** (03:00 CEST becomes 02:00
CET); that local day has 25 hours, and golden tests must span it. While a challenge is active, the general mesocycle
week equals the challenge's block week (repeated and extra weeks included); otherwise a 5-week calendar cadence (C31).

**Determinism.** `:algo` has no `android.*`, no system clock, no randomness and no IO (lint test). Collections that are
iterated for decisions are sorted lists or `TreeMap`s keyed by stable ids. Group order is the canonical order of the 18
groups in §0.3. Exercise order is by `slug` (ASCII). Every module returns its output plus a `DecisionTrace` (inputs
used, candidate scores, chosen option, reason codes) that is stored in `fitness.recommendations` (C24) with
`module id@version`, `ENGINE_VERSION`, `params_hash`, `catalog_version`, the challenge definition sha256 and
`inputs_hash` (RFC 8785 JCS + SHA-256) (C30). Snapshot kinds and their scope keys are those of DATA_MODEL §10.1; the
output keys that the review views read are listed in DATA_MODEL §10.2, which is the owner of that list (C58).

**Parameters.** Every constant lives in the versioned params JSON under the key `<module>.<constant>`: the registry
module id (dots allowed, for example `calendar.classify` or `ui.todaycard`) followed by a lower snake_case constant
name (C41; E53: `^[a-z]+(\.[a-z0-9_]+)+$`), for example `readiness.tau_h.quads`, `calendar.classify.w_title` or
`ui.todaycard.close_rel`. The bundled defaults live under `algo/src/main/resources/fitness/params/` (C40). In
pseudo-code constants are written in UPPER_CASE for readability (this is also the Kotlin constant name); `W_TITLE` in §1
is the key `calendar.classify.w_title`. Code never hardcodes a constant. Params are pinned per session (they change only at the
next session start). The complete registry for this document, with default, bounds, evidence grade, source and the
logged data that tunes each constant, is §23.

**Evidence grades.** `strong`, `moderate`, `weak`, `expert-opinion`, `engineering-fact`. A constant marked **tunable**
is an assumption; its row in §23 names the logged data that tunes it and the tuner rule (§20.1) that may propose a
change. No tuned value is applied silently: the tuner proposes, the user approves, the params version bumps.

**Explanations (F19).** Every recommendation carries up to three reason chips (codes from §0.6), a Why-sheet payload
(numbers, rule id, evidence grade, citations, and "use mine anyway" where a user preference was overridden) and the
trace.

### 0.3 Shared vocabulary

**Muscle groups (C9).** 18 groups, in this canonical order:
`chest, front_delts, side_delts, rear_delts, lats, upper_back, upper_traps, biceps, triceps, forearms, abs, lower_back,
glutes, abductors, adductors, quads, hamstrings, calves` (+ optional `neck`, inactive by default). Goals, targets,
volume and readiness are kept per group. Regions (34 active + optional `neck`, C44) are defined in §10.2 and are used
only for variety bonuses, challenge mapping and substitution similarity; `obliques` is a region of the `abs` group (C44).

**Families.** Session families are unions of groups (the same mapping as `muscle_groups.family`, DATA_MODEL E46):

| Family | Groups |
|---|---|
| PUSH | chest, front_delts, side_delts, triceps |
| PULL | lats, upper_back, rear_delts, biceps, forearms, upper_traps |
| LEGS | quads, hamstrings, glutes, abductors, adductors, calves |
| CORE | abs, lower_back |

**Session types (candidates).** `LEGS, PUSH, PULL, PUSH+LEGS, PULL+LEGS, UPPER (= PUSH+PULL), FULL (= PUSH+PULL+LEGS),
CORE`, plus `REST`. Every type except CORE includes the CORE groups as optional riders (they receive sets only if they
still need volume, are recovered and time allows). Fixed type order for ties: the order just listed. The user-visible
label of a type is derived from the families that actually receive sets (§9.5), so a FULL session whose pull groups
are all covered by a challenge is shown as "Legs + push", never as "Full body".

**Two volume units.** This distinction is used everywhere:

* **FS (fractional sets)**: the physical planning unit. One working set of an exercise with group coefficient
  `c ≥ 0.5` for group `g` contributes `c` FS to `g`; a unilateral side row contributes `0.5·c` (one L+R pair = one set,
  C7); a timed hold contributes `c·hold_factor` (C8). Coefficients of 0.25 contribute 0 FS (they count for fatigue
  only). The allocator and SEL-1 work in FS.
* **Vhyp (stimulus-weighted volume)**: FS multiplied by the stimulus weight of the set's RIR from the C6 table
  (`1.00, 1.00, 0.95, 0.90, 0.80, 0.50` for RIR 0-5, 0 above). Weekly targets, volume bars, warnings and challenge
  coverage are in Vhyp (C6). Conversion for planning: a planned set at the default target RIR `q_base` (2, from RX-1)
  is worth `STIM(2) = 0.95` Vhyp per FS.

**Hard set** = RIR ≤ 4 (C6). **Covered group** = a group that an active challenge owns (D6, `integration.ownsMuscles`
of the definition, mapped to groups through §10.2); it receives no general volume and is shown as "covered by
challenge".

**Tags.** Exercise tags are either stored on the catalog row (the reserved list of DATA_MODEL 9.2.1: `challenge,
prehab, skill, pull_heavy, grip_heavy, elbow_flexor_heavy, supinated_curl, unilateral, heavy_unilateral, eccentric,
elbow_stress_high, unstrapped_deadlift, no_grip`) or derived deterministically by CAT-1 from the attributes (§10.4:
`grip_heavy`, `elbow_flexor_heavy`, `axial_heavy`, `overhead_press_heavy`, `supinated_curl`). The effective tag set of
an exercise is `stored ∪ derived`. **Session tags** (used by the gap rule, C11, and by protected windows, C12) are
computed from a session's planned or logged sets with the `integration.sessionTagRules` of the active challenge
definition (OAP: `pull_heavy` = at least 4 hard sets for lats + upper_back; `elbow_flexor_heavy` = at least 3 hard sets
for biceps, or any exercise with elbow-medial stress ≥ 2; `grip_heavy` = at least 2 hard sets of exercises with grip
demand ≥ 2), plus the template tags of a challenge session.

### 0.4 Data facts this document reads

| Fact | Owner | Fields used here |
|---|---|---|
| `set_logs` | DATA_MODEL 9.6.4 | exercise, side (L/R/both), reps, load fields, assist pin + unit + machine, hold seconds, felt chip, `rir_reported`, reason code, `set_kind`, timestamps (Instant), completed, session and block ids |
| `workout_sessions`, `session_blocks` | DATA_MODEL 9.6.1-9.6.2 | `kind ∈ {gym, home, test}` (C34), challenge slot, start/end Instants, `time_budget_min`, `is_deload`, `mesocycle_week` |
| `wellness_logs` | DATA_MODEL 9.6.5 | readiness level, elbow score, pain (the PainEvent fact, C33), soreness per group, sRPE, sleep |
| `calendar_events` + `CalendarContract.Instances` | DATA_MODEL 9.7.3 / `:app` | see §1.2 |
| `profiles` | DATA_MODEL 9.3.1 | `timezone`, `training_age` (null → novice, C64), `birth_year`, `max_gym_visits_per_week` (D5, 4), `default_session_min` (F11, 75), `changeover_min` (F11 revised, C49: 5 min, written `changeoverMin` in this document's pseudo-code; it replaces the earlier `travel_buffer_min`), `availability`, `running_priority` (F6), `deload_sync_with_challenge` (F8), `allow_supersets` (F13), `evidence_mode` (D10), prompt toggles (F15), calendar settings (D11), `muscle_goal_preset` (D6) |
| `muscle_goals`, `rep_preferences`, `user_exercise_prefs` | DATA_MODEL 9.3.4-9.3.6 | goal per group + focus, set/rep preferences by scope, 5-state preference (C26) |
| `gyms`, `gym_equipment` | DATA_MODEL 9.3.2-9.3.3 | stations present, load schemes, opening hours, location keywords (F7, C2) |
| `exercises`, `exercise_stimulus` | DATA_MODEL 9.2 | catalog (CAT-1, §10) |

### 0.5 Interfaces consumed from other documents

These are the **planning-side views** of modules owned elsewhere. If the owning document defines a field differently,
the owning document wins and this document must be updated (§25).

**ChallengeContract (owned by `challenge.contract`; one per active enrollment).** The field names follow the
`schedule`, `calendar` and `integration` sections of `docs/challenges/oap.challenge.v1.json`.

```
ChallengeContract {
  enrollmentId, challengeId, definitionSha256,
  blockIndex, blockWeek, mode ∈ {normal, deload, test_pending, extra_deload, injury},
                                           // a paused, completed or abandoned enrollment exports an empty contract
                                           // (CHALLENGE_ENGINE §8.1): no owned muscles, no reserved sessions, no rules
  isDeloadWeek: Bool                       // true when mode ∈ {deload, test_pending, extra_deload} (F8)
  reservedSessions[]: {                    // this ISO week and the next (D11)
     slot ("C" | "A" | "B" | "TEST"), templateId, date, start: Instant, end: Instant,
     source ∈ {done, calendar_marked, calendar_unmarked, suggested},
     templateTags[],                       // e.g. unilateral_pull, heavy_unilateral, pull_heavy, grip_heavy, elbow_flexor_heavy
                                           // planned sets are not a contract field (§8.1 does not export them): the
                                           // planner reads them from challenge.materialize's SessionPlan of the
                                           // reserved session (exercise, side, reps | hold_s | ecc_s, creditRir, role;
                                           // warm-ups excluded)
     estMinutes /*TIME-1*/, bookedMinutes, // booked = matched event length, else the template's bookedMin
     budgetMinutes,                        // = bookedMinutes − 5 (C49)
     addOnSlot?: { position: "end", capacityMinutes /*= booked − TIME-1 − 5, floored; C49, §17.6*/, allowPatterns[],
                   forbidGroups[], forbidSessionTags[], maxGripDemand, maxSets, minRir, generalWarmup: false }
  }
  hardRules[]: minGapDays {days, appliesToTags[]}            // C11; OAP: 1, [pull_heavy, grip_heavy, elbow_flexor_heavy]
               restDayBefore {slots[], scope ∈ {all_gym, all_training}, runs ∈ {warn, forbid}}  // OAP: [B, TEST], all_gym, warn
               maxPerWeek {sessionTag, n}                    // OAP: heavy_unilateral ≤ 3
  protectedWindows[]: { id, relation ∈ {before, after}, slots[], hours, mode ∈ {hard, penalty},
                        groups[], regions[], sessionTags[], scope? = all_gym, rMin?, tissueRMin?{tissue: r} }   // C12
                        // no systemic (whole-body) term in v1 (D15, C50)
  generalSessions    // C50 gap-day policy: lightPullOnGapDays {maxGripDemand 1, groupCoefficientBelow {biceps 0.5},
                     //   outsideProtectedWindows, onlyIfGoalsAsk}; neverOn restDayBefore(B|TEST); §12.3
  sessionTagRules    // see §0.3
  ownsMuscles[]      // groups (or regions mapped to groups) → "covered" (D6)
  coverageCommitted  // per group Vhyp of materialized, not yet done challenge sets this ISO week (SetCredit, C10)
  modeEffects        // e.g. injury: forbidSessionTags, forbidExerciseTags, jointFlags (merged by safety, §2)
}
```

C12 defaults for OAP (the contract supplies them; repeated here only for the worked example): hard 24 h before A for
lats, biceps, forearms and the brachialis region plus the `grip_heavy` and `elbow_flexor_heavy` session tags; hard
24 h all-gym before B and TEST; hard 24 h after B for elbow flexors (P-tagged proposal P23); penalty windows of 30 h
before A and 48 h before B/TEST with `rMin 0.85` and elbow `tissueRMin 0.90`. P-tagged items apply only after the user
approves the proposal that carries them in the challenge setup (D8, C48); until then the contract omits them. There is
no whole-body (systemic) protection of A, B or TEST in v1: a general legs/push session may be planned on any gap day,
including the day before A (D15, C50); the hard 24 h window before A still applies.

The field names above are this document's planning view. CHALLENGE_ENGINE §8.1 owns the contract and names several
fields differently: `definitionSha` for `definitionSha256`, `deloadWeek` for `isDeloadWeek`, `sessionTags` for
`templateTags`, `bookedMin` / `budgetMin` / `estMin` for the minute fields, `addOnSlot.capacityMin` for
`capacityMinutes`, `gapRule` and `blockedDays` (with `generalSessions.neverOn`) instead of `hardRules[]`, and
`coverage.committed` for `coverageCommitted` (computed by SetCredit with the C6 stimulus table, so it is Vhyp). Where
they differ, the owner's names are used in code and this view is a mapping. The planner needs no `intensityClass`: the
protection of challenge sessions comes from the windows, tags and gap rule. Planned challenge doses (readiness
projection, §4) come from `challenge.materialize` per reserved session (§25 OI-20).

**Other interfaces.**

| Interface | Owner | Used by | Signature (planning view) |
|---|---|---|---|
| `LOAD.systemLoad(exercise, set, bw)`, `assistKg(pin, calib)` | `load` (C1, C2) | ui.prefill, ui.progress, substitute | kg on the 0.01 grid |
| `LOAD.loadableSet(scheme)`, `nearestLoadable(target, mode)` | `load` | ui.prefill | ties go to the easier load or more assistance (C2) |
| `BW(t)` | `bodyweight` (C32) | ui.progress | EWMA for prescriptions, raw test-day weigh-in for gates |
| `REST-1.baseSeconds(exercise, setContext)`, `REST-1.supersetRest(a, b)` | `rest` (C18, C56; ALGORITHMS B11.2) | time, order | 180 / 150 / 120 / 90 / 60 s classes, clamp 60-300 s; superset: `rest.superset_between_s` (60 s) between members and the after-pair rest of REST-1 |
| `RX-1.defaultTargetRir`, per-exercise target RIR | `rx` | allocator, select, setcredit (planned credit) | default 2 |
| `WARMUP.specificSets(exercise, workingLoad)` | `warmup` | time | count and reps of specific warm-up sets |
| `WarningEngine.evaluate(...)` | `warnings` (C14) | ui.volumebars, ui.warndisplay | `{ruleId, severity INFO/WARN/STRONG/SAFETY, entity, magnitude, evidenceGrade, citations, numbers, suggestion?}` |
| REASON-1 reason semantics, imputed RIR | `reason` (C16) | setcredit, ui.reasonchips | reason vocabulary (DATA_MODEL 9.1.3) and effects |
| STALL-1 flags | `stall` | targets (reactive deload), rotate, select | per exercise `stalled`, `regressing`; global `deloadRecommended` |
| HOLD-1 chip semantics | `hold` | setcredit | felt chip → seconds in reserve (Max 0, Hard 2, OK 5, Easy 10+) |
| SSE-1 engine-logged per-set e1RM and filtered state | `sse` (C3, C4) | ui.progress, rotate, targets (performance signal) | never recomputed here |
| `challenge.plan` week placement | challenge | weekplan | reserved sessions incl. suggested placements for slots not in the calendar |
| `challenge.calendar.matchSlots(enrollment, candidates)` and write-back | challenge (CHALLENGE_ENGINE §9.3; edit policy ANDROID §16.7, C37) | calendar.classify (matching), weekplan (hand-off, §8.9) | max-weight assignment of gym events to slots (`SlotMatch {instanceKey, isoWeek, slot, weight, method, attention}`); writes and updates marked events |
| `challenge.materialize` | challenge | time (add-on capacity), setcredit (committed), readiness and weekplan (planned challenge doses) | `SessionPlan` with sets, rest, holds per reserved session |
| `challenge.contract` add-on capacity | challenge (CHALLENGE_ENGINE §6.9, C49) | weekplan, allocator, select | `addOnCapacityMin = max(0, floor(bookedMin − estMin − 5))`; `estMin` from TIME-1 (§17.6) |

### 0.6 Reason codes (shared by today, weekplan, allocator, select and `ui.todaycard`)

Each code has typed parameters, a precedence class and a signed weight taken from the term of the objective that
produced it, so chips can be ranked (§9.6, §19.1). The precedence classes follow the pipeline precedence of §0.1 and are
numbered as in UX §19.0: `SAFETY` 0, `CHALLENGE` 1, `USER` 2 (the user's own hard constraints: calendar plans, maximum
visits, time budget), `GENERAL` 3. Codes are a versioned enum (`today.reason_codes`, §23) owned by this document
(C53). The English templates below are indicative; the normative strings are display details owned by UX §19.1.1 and
§24 (D12; decimal point). Version 1.1.0 of the enum adopts the UX names for five codes that 1.0.0 named differently
(`RECOVERED` for `FRESH`, `PROTECT_REST` for `PROTECTS_NEXT`, `CONSECUTIVE` for `CONSECUTIVE_DAYS`, `RUN_NEAR` for
`RUN_TIMING`, `SAFETY_JOINT` for `SAFETY_FLAG`) and adds `SAFETY_PROTOCOL`, `NEXT_FIXED`, `BUDGET_FIT`, `FREQ_BONUS` and
`HEAVY_LOWER_GAP`.

| Code | Class | Produced by | Typed params | Template (EN, indicative) |
|---|---|---|---|---|
| `SAFETY_JOINT` | SAFETY | safety | joint, severity, effect | "{joint} rests today" |
| `SAFETY_PROTOCOL` | SAFETY | safety (challenge mode) | protocol, day, days | "{protocol} · day {day} of {days}" |
| `REST_BEFORE` | CHALLENGE | weekplan, today | slot, when, ruleId | "Rest before {slot} {when}" |
| `PROTECT_REST` | CHALLENGE | today, weekplan, allocator | family, groups[], slot, when, windowId | "{family} rests for {slot} {when}" |
| `GAP_DAY` | CHALLENGE | weekplan, today | slot, ruleId | "Gap day next to {slot}: light pull only, no grip or elbow-flexor work" |
| `CHALLENGE_DUE` | CHALLENGE | today | challenge, slot, done, total | "{slot} due ({done}/{total} this week)" |
| `NEXT_FIXED` | CHALLENGE | today | slot, at | "{slot} {weekday} {time}" |
| `DELOAD_WEEK` | CHALLENGE when synced to a challenge block (F8), else GENERAL | targets, today | source | "Deload week: half sets, 2 more reps in reserve" |
| `COVERED_BY_CHALLENGE` | CHALLENGE | targets, allocator | group, challenge | "{group} covered by {challenge}" |
| `RUN_ON_REST_DAY` | CHALLENGE | runs | slot | "Run on the rest day before {slot} (allowed, take it easy)" |
| `PLANNED_CAL` | USER | today | at | "Planned {time}" |
| `MAX_VISITS` | USER | weekplan, today | used, max | "{used} of {max} gym visits used" |
| `BUDGET_FIT` | USER | today | minutes | "Fits {minutes} min" |
| `NO_SLOT` | USER | weekplan, today | reason | "No free slot ({reason})" |
| `TIME_SHORT` | USER | time, allocator | budget | "Shortened to fit {budget} min" |
| `VOLUME_DEFICIT` | GENERAL | today, weekplan | group, done, after, target | "{group} {done}→{after}/{target} sets this week" |
| `RECOVERED` | GENERAL | today | family, pct | "{family} {pct}% recovered" |
| `HOURS_SINCE` | GENERAL | today | subject, hours | "{subject} {hours} h ago" |
| `REENTRY` | GENERAL | targets, today | family, days | "First {family} in {days} days: lighter start" |
| `BETTER_OTHER_DAY` | GENERAL | today | type, day, dJ | "{type} fits better on {day}" |
| `BETTER_THIS_DAY` | GENERAL | weekplan | day, reason | "Better today than {day}: {reason}" |
| `FREQ_BONUS` | GENERAL | today, weekplan | group, n | "{group}: 2nd session this week" |
| `LOW_SYSTEMIC` | GENERAL | today | pct | "Whole-body readiness {pct}%" (the session's own start readiness, §8.6 `LOWG_W`; not a protection term) |
| `RECOVERING` | GENERAL | allocator, today | group, pct, readyAt | "{group} still recovering ({pct}%)" |
| `SORE` | GENERAL | readiness | group | "{group} still sore" |
| `HEAVY_LOWER_GAP` | GENERAL | weekplan, today | hours, session | "Heavy legs {hours} h ago ({session})" (H6) |
| `CONSECUTIVE` | GENERAL | weekplan | days | "{days} days in a row" |
| `RUN_NEAR` | GENERAL | runs | when | "Run {when}: legs placed away from it" |
| `TARGETS_MET` | GENERAL | allocator | group | "{group} target reached this week" |
| `ONLY_OPTION` | GENERAL | elig | group | "Only option for {group}" |
| `PREF_LIKED` | GENERAL | select | exercise | "You like {exercise}" |
| `PAST_DAY` | GENERAL | weekplan | — | "Past" |
| `NOT_NEEDED` | GENERAL | weekplan | — | "Targets on track without an extra visit" |
| `ADDON` | GENERAL | weekplan, today | family, slot, minutes | "{family} add-on after {slot} (~{minutes} min)" |
| `NO_ELIGIBLE_EXERCISE` | GENERAL | allocator | group | "No suitable exercise for {group} today" |
| `NOTHING_TO_TRAIN` | GENERAL | allocator, today | — | "Nothing worth training in this slot" |

The last two codes were added on 2026-10-08 (params 1.0.0 authoring found that §7.4 emits them but this table did
not list them).

---

## 1. `calendar.classify` — CalendarClassifier.v1 (C20, D11, D12, F6)

### 1.1 Purpose

Turn the phone's calendar instances into typed anchors for the planner: challenge sessions (with slot), planned
general gym sessions (with type), runs (with intensity and minutes), busy blocks, and ignored events. There is exactly
one classifier (C20). It combines the feature scoring of the tech-stack lens, the challenge title patterns that come
from each active challenge definition, marker detection for events the app wrote itself (D11), per-event overrides,
the max-weight slot matching (delegated to `challenge.calendar.matchSlots`, which owns the per-week slot capacity), and
deduplication. Runs are their own kind and are never rejected (C20, F6). It reads through `CalendarContract` only (D11,
no Google OAuth) and never writes; writing is done by `challenge.calendar` (challenge sessions and test days, written
automatically) and by the `:app` calendar writer for general sessions accepted with "Plan it" (§8.9).

### 1.2 Inputs

| Input | Description |
|---|---|
| `instances` | `CalendarContract.Instances` rows for the window `[Monday 00:00 of the current ISO week − 1 day, Sunday 24:00 of the next ISO week + 1 day)` in the user zone, converted to Instants (as built in M2b.5 the app reads ANDROID 16.4's wider window, Monday − 7 days to Monday + 14 days, so last week's sessions can be linked, C35). Fields: `beginUtc, endUtc, eventId, calendarId, calendarName, calendarAccountType, calendarIsPrimary, title, description, location, allDay, status, selfAttendeeStatus, availability`. Recurring events arrive as instances (the Instances table expands recurrence rules and exceptions). |
| `allowlist` | Calendar ids to read. Default (D11, `profiles.calendar_scope = primary_only`): the primary calendar only (`IS_PRIMARY = 1` on a `com.google` account; fallback `OWNER_ACCOUNT == ACCOUNT_NAME`). With `selected`, the user's chosen calendars. |
| `ignoreCalendarNamePatterns` | `profiles.calendar_ignore_name_patterns`, default `(?i)todoist` (D11): the Todoist calendar is ignored even if the user adds it to the allowlist. |
| `ignoreAllDay` | `profiles.calendar_ignore_all_day`, default `true` (D11). |
| `gymLocationKeywords` | `gyms.location_keywords` of the user's gyms, normalised; default `["basic-fit", "basicfit", "basic fit", "ierse pond"]`. |
| `challengeCalendarConfigs` | For each active enrollment, from its definition's `calendar` section: per-slot title patterns (OAP C: `\bsessie c\b\|\bsession c\b\|volume en techniek\|volume and technique`), `slotAliases` (OAP `[B, TEST]`), and the enrollment's ISO-week slot capacity (`schedule.slotsPerWeek` for the week's mode). Patterns are data, never code (C20). |
| `overrides` | Per-event user corrections (DATA_MODEL `calendar_events.override_kind/override_slot`), keyed by `instance_key` (one instance) or `series_key` (whole series): `{kind, payload}`, or `adopt` (D11: an unmarked event becomes app-managed). |
| `catalogNames` | Exercise names and aliases in English and Dutch (D12) for the description feature. |
| `lexicons` | Versioned regular expressions (params `calendar.classify.lex.*`, §1.5). |
| `now`, `zone` | From `core:clock`. |

### 1.3 Outputs

```
CalendarClassification {
  status ∈ {ok, calendar_unavailable, permission_denied},
  anchors[]: Anchor {
    instanceKey  = sha256(accountType | accountName | calendarSyncId | eventSyncId-or-_ID | beginUtcMs)   // DATA_MODEL 9.7.3
    seriesKey    = same hash without beginUtcMs,
    eventId, calendarId, beginUtc, endUtc, localDate (date of beginUtc in zone), startLocal, endLocal,
    kind ∈ {challenge_session, planned_gym, run, busy, ignored},
    payload:  challenge_session → {enrollmentId, slot, matchScore, method ∈ {marker, title_pattern, manual, adopted}}
              planned_gym       → {type ∈ {PUSH, PULL, LEGS, CORE, PUSH+LEGS, PULL+LEGS, UPPER, FULL, LEGS+CORE, AUTO},
                                   marker?: {recommendationId, slotKey}, unmatchedChallengeCandidate: Bool}
              run               → {intensity ∈ {easy, tempo, intervals, race}, minutes, long: Bool}
              busy              → {}
              ignored           → {reason ∈ {todoist, cancelled, declined, all_day, availability_free, duplicate_of:<instanceKey>}}
    score (integer feature score), features[] (codes), confidence ∈ {high, medium},
    needsConfirm: Bool, classifierVersion
  }
}
```

Storage mapping (DATA_MODEL E21/E22/E23): `busy` is never stored on the server; `ignored` is stored only as a user
override without title. A run is stored with `run_intensity = long` when `long = true` and the intensity is `easy`,
otherwise with its intensity; `run_minutes = minutes`. `method` maps to `match_method` (`title_pattern` covers the
regex + matching path; `score` is used for planned gym events).

### 1.4 Algorithm

**Normalisation `N(s)`** (identical for titles, descriptions, locations, calendar names and keyword lists):

1. `null` → `""`.
2. Unicode NFKD, then remove every combining mark (category Mn): `één → een`, `ë → e`, `ö → o`.
3. Lowercase with `Locale.ROOT`.
4. Replace U+2010, U+2011, U+2012, U+2013 (en dash), U+2014 (em dash), U+2015 and U+2212 with ASCII `-`.
5. Replace every whitespace run (including U+00A0, tabs and line breaks) with one space; trim.

So `"Gym — sessie A (één arm zwaar)"` becomes `"gym - sessie a (een arm zwaar)"`. Challenge patterns are written
against normalised text.

**Marker grammar (C37).** One format everywhere (the DATA_MODEL `calendar_events.marker` CHECK, the ANDROID parser, UX,
this document and the challenge definition's `writeBack`), rendered and parsed by the one shared `Marker` in
`dev.fitnessapp.algo.core.calendar` (C68). The marker is the last non-empty line of the raw
description, trimmed, matching

```
^\[fitapp:v1 (?<uuid>[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) (?<slotKey>[A-Za-z0-9_./-]{1,64})\]$
```

`uuid` is the enrollment id for challenge events and the accepted recommendation id for general sessions. `slotKey` is
`<challengeSlug>/<slot>/<isoYear>-W<ww>` for challenge sessions and test days (for example `oap/C/2026-W42`,
`oap/TEST/2026-W46`) and `gen/<isoYear>-W<ww>/<isoDayOfWeek>` for general sessions (for example `gen/2026-W41/5`).
`parseMarker` returns `{uuid, slotKey, kind ∈ {challenge, general}, slot?, isoWeek, isoDow?}`: `general` when the slot
key starts with `gen/`, `challenge` when `uuid` is an active enrollment id and the first segment is its definition slug;
any other well-formed marker is treated as an app-owned event of an inactive enrollment (classified like an unmarked
event, never written). A marked event is app-owned; an unmarked event is never modified unless the user taps "adopt"
(D11). The edit policy (user time edits win; a user-edited title or description stops app updates for that event; the
app deletes only its own unmodified future events; user deletions are respected) is owned by ANDROID §16.7 (C37) and
is not restated here.

```
classify(instances, ctx):
  out = []
  for inst in instances sorted by (beginUtc asc, eventId asc, calendarId asc):
    if inst.calendarId ∉ ctx.allowlist:            continue                 // not read, not logged
    if any(p.matches(N(inst.calendarName)) for p in ignoreCalendarNamePatterns): out += ignored(todoist); continue
    if inst.status == CANCELED:                    out += ignored(cancelled); continue
    if inst.selfAttendeeStatus == DECLINED:        out += ignored(declined); continue
    if inst.allDay and ctx.ignoreAllDay:           out += ignored(all_day); continue        // D11
    t = N(inst.title); d = N(inst.description); l = N(inst.location)
    ov = overrides[instanceKey] ?: overrides[seriesKey]
    if ov != null and ov.kind != adopt: out += fromOverride(inst, ov, method = manual, confidence = high); continue
    m = parseMarker(lastNonEmptyLine(inst.description))
    if m != null:  out += fromMarker(inst, m, confidence = high); continue  // slot type reconciled by challenge.calendar (C36)
    dur = minutesBetween(beginUtc, endUtc)
    s = 0; f = []
    if lex.gym_title.find(t):                          s += W_TITLE;           f += "title_kw"
    if any(k in l for k in gymLocationKeywords):       s += W_LOC;             f += "location"
    if any(p.find(t) for p in allChallengeSlotPatterns): s += W_CHALLENGE_TITLE; f += "challenge_title"
    if distinctCatalogNames(d) >= 2:                   s += W_DESC;            f += "desc_exercises"
    if inst.allDay:                                    s += W_ALLDAY;          f += "all_day"   // only if ignoreAllDay = false
    s += (DUR_MIN <= dur <= DUR_MAX) ? W_DUR_OK : W_DUR_BAD
    if lex.neg.find(t):                                s += W_NEG;             f += "neg_kw"
    if lex.run.find(t) and not lex.gym_title.find(t):                           // runs are their own kind (C20, F6)
       out += run(intensity = runIntensity(t + " " + d),
                  minutes   = clamp(dur, RUN_MIN_MINUTES, RUN_MAX_MINUTES),
                  long      = dur > RUN_LONG_MINUTES or lex.run_long.find(t),
                  confidence = high); continue
    if s >= THRESH:
       type = typeFrom(t) ?: typeFrom(d) ?: AUTO
       out += gymCandidate(inst, type, s, f, adopted = (ov?.kind == adopt))
    elif inst.availability == AVAILABILITY_FREE: out += ignored(availability_free)
    else: out += busy(inst, s, f)

  // Stage B: challenge slots (matching owned by challenge.calendar; called, not re-implemented)
  for enr in activeEnrollments sorted by enrollmentId:
     cands = [a in out where a.kind == gymCandidate and a.localDate in enr.isoWeeks]
     assign = challenge.calendar.matchSlots(enr, cands)   // max-weight bipartite matching per ISO week,
                                                          // capacity from slotsPerWeek, accept ≥ its acceptScore,
                                                          // aliases (B ↔ TEST) allowed with attention = slot_mismatch,
                                                          // ties: earlier start, then lower eventId
     for (a, slot, score, attention) in assign:
        a := challenge_session(enr.enrollmentId, slot, score, method = a.adopted ? adopted : title_pattern, attention)
  for a in out where a.kind == gymCandidate:
     a := planned_gym(a.type, unmatchedChallengeCandidate = "challenge_title" in a.features)

  // Stage C: dedupe
  for each localDate:
     G = gym anchors (challenge_session, planned_gym) of that date sorted by beginUtc
     cluster anchors whose intervals overlap or whose starts differ by ≤ DEDUPE_MINUTES
     keep per cluster the first by (method rank manual < marker < adopted < title_pattern < score,
                                    score desc, beginUtc asc, eventId asc)
     others := ignored(duplicate_of = keeper.instanceKey)
     runs of that date that overlap each other: keep the longest, then the earliest; others := ignored(duplicate_of)

  // Stage D: confidence
  for a in out:
     if a.kind == challenge_session: a.confidence = (method != title_pattern or matchScore >= CONFIRM_SCORE) ? high : medium
     if a.kind == planned_gym:       a.confidence = (marker or override or score >= HIGH_SCORE) ? high : medium
     a.needsConfirm = a.confidence == medium and today <= a.localDate <= today + 7 days
  return out
```

`runIntensity(x)`: `race` if `lex.run_race` matches, else `intervals` if `lex.run_intervals`, else `tempo` if
`lex.run_tempo`, else `easy`. `typeFrom(x)`: the set `M` of family keys whose `lex.type.*` regex matches; `LOWER →
LEGS+CORE`, `UPPER → PUSH+PULL`, `FULL → PUSH+PULL+LEGS`; if `|M ∩ {PUSH, PULL, LEGS, CORE}| ≥ 2` → that combination
(mapped to the nearest session type of §0.3); if one → that family; if only UPPER/LOWER/FULL → that; else `null`.

**Post-hoc session link (C35).** A pure helper `linkSession(session, anchors)` returns the gym anchor on the same local
date whose `beginUtc` is within ± `LINK_WINDOW_H` (3 h) of the session start, preferring (same challenge slot, then
smallest time difference, then lower eventId). The link is stored on `calendar_events.linked_session_id`
(`link_method = auto_window`); the user can change it in the Session Summary. Challenge `session.completed` always fires
on the session's template slot, never on the calendar (C35).

**Degraded mode.** Missing permission or a provider error → `status = calendar_unavailable`, `anchors = []`. The
planner then plans without calendar anchors (every day inside the availability windows is available), challenge
sessions come only from `challenge.plan` suggestions, and the Week tab shows the degraded banner (UX 10.6).

**Refresh triggers.** App foreground; `ContentObserver` on Instances/Events with a 2000 ms debounce; a daily run at
05:00 local; immediately after any write-back. Each run reclassifies the whole window (tens of events; cheap).

### 1.5 Lexicons (params, versioned with `calendar.classify`)

| Key | Default (applied to normalised text) |
|---|---|
| `calendar.classify.lex.gym_title` | `(^\|[^a-z0-9])(gym\|sportschool\|fitness\|krachttraining\|workout\|training\|basic-?fit\|basic fit)([^a-z0-9]\|$)` |
| `calendar.classify.lex.neg` | `(^\|[^a-z0-9])(schaak\w*\|schaken\|chess\|zwem\w*\|swim\w*)([^a-z0-9]\|$)` |
| `calendar.classify.lex.run` | `(^\|[^a-z0-9])(hardlopen\|hardloop\w*\|duurloop\|tempoloop\|intervalloop\|run\|running\|jog\|joggen\|parkrun\|marathon\|10 ?km\|5 ?km\|10k\|5k)([^a-z0-9]\|$)` |
| `calendar.classify.lex.run_race` | `(wedstrijd\|race\|parkrun\|marathon\|10 ?km\|5 ?km\|10k\|5k)` |
| `calendar.classify.lex.run_intervals` | `(interval\w*\|fartlek\|repeats\|herhaling\w*\|[0-9]{3,4} ?m([^a-z]\|$))` |
| `calendar.classify.lex.run_tempo` | `(tempo\w*\|drempel\w*\|threshold)` |
| `calendar.classify.lex.run_long` | `(lange duurloop\|long run\|lange loop)` |
| `calendar.classify.lex.type.push` | `(push\|duw\w*\|borst\|chest\|schouder\w*\|shoulders?\|triceps)` |
| `calendar.classify.lex.type.pull` | `(pull\|trek\w*\|rug\|back\|biceps\|optrek\w*\|pull-?ups?)` |
| `calendar.classify.lex.type.legs` | `(legs?\|benen\|been\|squat\w*\|leg day)` |
| `calendar.classify.lex.type.core` | `(core\|buik\w*\|abs)` |
| `calendar.classify.lex.type.upper` / `.lower` / `.full` | `(upper\|bovenlichaam)` / `(lower\|onderlichaam)` / `(full ?body\|volledig\|hele lichaam)` |

Numeric constants (`W_TITLE 3, W_LOC 3, W_CHALLENGE_TITLE 2, W_DESC 1, W_ALLDAY −4, W_DUR_OK 1, W_DUR_BAD −1, W_NEG −5,
THRESH 3, DUR_MIN 30, DUR_MAX 180, HIGH_SCORE 6, CONFIRM_SCORE 0.8, DEDUPE_MINUTES 120, RUN_MIN_MINUTES 10,
RUN_MAX_MINUTES 240, RUN_LONG_MINUTES 75, LINK_WINDOW_H 3`) are in §23.1. Rationale: a title keyword or a gym location
alone (3) suffices; a challenge title alone (2) needs a plausible duration (+1); the chess penalty (−5) beats a stray
"training". The weights and threshold are the tech-stack lens's (engineering choice, tunable from corrections); the
challenge-title weight and the confirm threshold are [this doc].

### 1.6 Logged data

`session_events` of type `calendar_classified` (C20, C24), written only when the classification fingerprint of an
instance changes (payload below). Titles are stored (in `calendar_events`) only for challenge sessions, planned gym
events and runs; busy and ignored events keep only the hash and kind (privacy, DATA_MODEL 9.7.3). User corrections (override created or removed, adopt)
are `session_events` of type `calendar_correction` / `calendar_event_adopted` (a changed session link is
`calendar_link_changed`). Event names and payload keys are those of the DATA_MODEL §9.8.1 registry, which is canonical
(C39); payloads never contain free text. The registered `calendar_classified` payload is `{instance_key, kind, slot?,
type?, score, features, classifier_version, method}`; `confidence` and `needs_confirm` are not logged because they are
recomputed from `method`, `score` and the date. These logs give precision and recall per feature for the tuner (§20.1,
rule T-CAL).

### 1.7 Tests

| Test | Input | Expected |
|---|---|---|
| `normalisesDutchTitle` | `"Gym — sessie A (één arm zwaar)"` | `"gym - sessie a (een arm zwaar)"` |
| `classifiesRealWeek41` | fixture F-CAL-41 (§24) | Tue C, Thu A, Sun TEST as `challenge_session`; chess `busy`; Todoist all-day "Gym" `ignored(todoist)` or not read |
| `runIsNeverRejected` | `"Hardlopen"` 45 min | `run(easy, 45)`; `"Hardlopen intervallen"` → `intervals`; `"Lange duurloop"` 90 min → `easy`, `long` |
| `chessIsBusy` | `"Training chess"` 90 min | score 3 − 5 + 1 = −1 → `busy` |
| `bareSessieNeedsConfirm` | `"sessie C"` 90 min, no location | score 2 + 1 = 3 → gym candidate; with no matching capacity → `planned_gym(AUTO)`, `unmatchedChallengeCandidate`, `needsConfirm` |
| `markerWins` | description ends with `[fitapp:v1 <enrollment uuid> oap/A/2026-W41]` | `challenge_session(A)`, method `marker`, no scoring |
| `generalMarker` | description ends with `[fitapp:v1 <recommendation uuid> gen/2026-W41/5]` | `planned_gym` with `marker = {recommendationId, slotKey gen/2026-W41/5}` |
| `markerUpperCaseSlot` | `[fitapp:v1 0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b oap/TEST/2026-W46]` | parses (upper-case slot letters are allowed, C37); a marker that is not the last non-empty line is ignored |
| `dedupeTwoGymEvents` | two gym events 19:45 and 20:00 the same day | one kept, the other `ignored(duplicate_of)` |
| `midnightSpan` | 23:30-00:30 Saturday | `localDate` = Saturday |
| `dstSunday` | event at 2026-10-25T01:30Z | `localDate` 2026-10-25, start 02:30 CET (not CEST) |
| `permissionDenied` | provider throws | `status = calendar_unavailable`, no anchors |
| `linkWithin3h` | session started 20:10, event 19:45 same day | linked; session at 23:00 with event 19:45 → linked (3 h 15 min → not linked) |
| `deterministicOrder` | the same instances shuffled | byte-identical output |

---

## 2. `safety` — SafetyState.v1 (C33, D10)

### 2.1 Purpose

Turn every pain report and every elbow/readiness check into one `SafetyState` that is evaluated **before anything
else** on every event (C33) and exported to the planner, ELIG-1/INJ-1 and the session UI. It owns the general joint
flags. Challenge safety modes (for example the OAP elbow protocol) are computed by the challenge engine's safety stage
(L2) from the same `PainEvent` facts; `safety` merges them (the definition's `integration.modeEffects`) into the
exported state so that downstream modules read one object. The app shows a "not medical advice" disclaimer wherever a
safety notice appears (C33). Safety rules are always enforced, whatever the user's evidence-mode setting (D10).

### 2.2 Inputs

| Input | Description |
|---|---|
| `PainEvent[]` (facts) | `wellness_logs` rows of kind `pain`, plus derived events from `elbow_check`: `{id, ts: Instant, source ∈ {pain_sheet, skip_reason_pain, set_reason_pain, post_session_check, readiness_elbow_check}, bodyRegion (DATA_MODEL 9.1.5 code), side ∈ {L, R, both, none}, severity ∈ {1 mild, 2 moderate, 3 sharp}, symptomCodes[] (e.g. medial_elbow_ache_after, elbow_crease_pain_on_extension, ulnar_tingling), exerciseId?, sessionId?}` |
| `PainClear[]` (facts) | The user taps "pain gone" for a joint: `{id, ts, joint}` (a `wellness_logs` row of kind `pain` with severity `none` is not used; clears are explicit) |
| `challengeModes[]` | From L2 (read-only): `{enrollmentId, mode, since, modeEffects: {forbidSessionTags[], forbidExerciseTags[], jointFlags{joint: severity}}}` |
| `bodyRegions` | `fitness.body_regions` (DATA_MODEL 9.1.5): body region code → joint keys |
| `now`, `zone` | From `core:clock` |

`JointKey` = the eight CAT-1 joint-stress keys (`exercises.js_*`): `elbow_medial, elbow_lateral, shoulder_ant,
shoulder_overhead, wrist, lumbar, knee, hip`. A pain report's body region expands to joint keys through
`body_regions.joint_keys` (for example `elbow_anterior → {elbow_medial, elbow_lateral}`, conservative; `shoulder →
{shoulder_ant, shoulder_overhead}`; `neck`, `upper_back`, `ankle_foot`, `other` → no joint key: a notice, no flag).

UI input mapping (fixed): the pain sheet chips Mild / Moderate / Sharp are stored as pain scores 2 / 5 / 8 on the 0-10
scale (C64; the stop threshold 4 is applied by REASON-1 and the challenge pain rules, not here) and map to severity
1 / 2 / 3 (a 0-10 score maps as 1-3 → 1, 4-6 → 2, 7-10 → 3). Readiness elbow check (F15, 0-3:
none/trace/mild/pain) → no event for 0; severity 1 for 1 and 2; severity 2 for 3; body region `elbow_anterior` (both
elbow keys). Symptom codes that the active challenge treats as stop signals are handled by the challenge's own rules;
they reach `safety` as a `challengeModes` change.

### 2.3 Outputs

```
SafetyState {
  asOf: Instant,
  jointFlags[]: {joint, severity 1..3, since: Instant, until: Instant, sourceEventIds[]},   // sorted by joint
  forbidSessionTags: SortedSet<Tag>,     // union of joint-derived tags and challenge modeEffects.forbidSessionTags
  forbidExerciseTags: SortedSet<Tag>,    // challenge modeEffects.forbidExerciseTags (e.g. unilateral_pull, eccentric)
  challengeModes[]: copied from L2,
  notices[]: {code, severity: safety, joint?, text},   // e.g. "sharp pain: consider seeing a professional"
  disclaimerRequired: true,
  fingerprint: sha256 of (flags, tags, modes)           // replan trigger
}
```

### 2.4 Algorithm

```
evaluate(painEvents, clears, challengeModes, now):
  flags = {}
  for joint in JointKeys (canonical order):
    E = [e for e in painEvents if joint ∈ expand(e.bodyRegion) and now − LOOKBACK_DAYS < e.ts <= now]
    if E empty: continue
    sev = max(e.severity for e in E)
    if count(e in E where e.ts > now − ESCALATE_WINDOW_DAYS) >= ESCALATE_COUNT: sev = min(3, sev + 1)
    last = max(e.ts for e in E)
    until = endOfLocalDay(last + HOLD_DAYS[sev] days)
    c = latest clear for joint with c.ts > last
    if c != null and (sev == 1 or localDaysBetween(last, c.ts) >= PAIN_FREE_MIN_DAYS): until = c.ts
    if now < until: flags[joint] = {sev, since = min(e.ts in E), until, ids(E)}
  for m in challengeModes: for (joint, sev) in m.modeEffects.jointFlags:                // e.g. OAP injury: elbow_medial 3
    flags[joint] = maxBySeverity(flags[joint], {sev, since = m.since, until = +∞ while the mode lasts, ids = []})
  sessTags = {}; exTags = {}
  for (joint, fl) in flags: sessTags ∪= JOINT_FORBID_TAGS[joint][fl.severity]           // params table below
  for m in challengeModes: sessTags ∪= m.modeEffects.forbidSessionTags; exTags ∪= m.modeEffects.forbidExerciseTags
  notices = [sharp_pain_notice(j) for j in flags if flags[j].severity == 3] + [challenge mode notices]
  return SafetyState(now, flags sorted, sessTags, exTags, challengeModes, notices)
```

`JOINT_FORBID_TAGS` (params `safety.joint_forbid_tags.<joint>.<severity>`, expert-opinion, tunable):

| Joint | Severity 1 | Severity 2 | Severity 3 |
|---|---|---|---|
| elbow_medial, elbow_lateral | `elbow_flexor_heavy, grip_heavy` | + `supinated_curl` | + `pull_heavy` |
| wrist | — | `grip_heavy` | `grip_heavy` |
| lumbar | — | `axial_heavy` | `axial_heavy` |
| shoulder_ant, shoulder_overhead | — | `overhead_press_heavy` | `overhead_press_heavy` |
| knee, hip | — | — | — |

The elbow is stricter than the other joints because it is the main injury risk of the OAP program (critique; challenge
lens: medial elbow pain, crease pain, ulnar tingling). Exercise exclusion by joint-stress level is done by INJ-1 (§18)
from the same flags; this table only adds session-tag exclusions (applied by the allocator and the planner).

**When it runs.** On every app event before any other module: pain reported, readiness check answered, set logged with
reason `pain`, session start, day rollover, app open. A change of the fingerprint invalidates the cached week plan,
today's options and the current session plan (replan trigger, §8.7). During a live session, a new flag makes INJ-1
re-filter the remaining exercises; already logged sets are never altered, and excluded remaining exercises are shown
with "Skip suggested (pain)" with one tap to confirm; the app never deletes them silently.

### 2.5 Constants

`LOOKBACK_DAYS 28, HOLD_DAYS {1: 7, 2: 14, 3: 14}, ESCALATE_COUNT 3, ESCALATE_WINDOW_DAYS 14, PAIN_FREE_MIN_DAYS 3`
(§23.2). The 14-day hold matches the OAP elbow protocol (A, 14 days) and UI-07's pain guard (14 days); the 7-day mild
hold and the escalation rule are expert-opinion and tunable from pain recurrence logs.

### 2.6 Logged data

Snapshot `safety.state` (scope `day:<date>`, DATA_MODEL 10.1) on every change, with the triggering event id; every
`PainEvent` and `PainClear` as facts; INJ-1 exclusions caused by each flag (in the session-build trace). Review use:
pain recurrence after a flag expired (tunes `HOLD_DAYS`), general sessions that preceded pain reports (tunes protected
windows and tissue weights).

### 2.7 Tests

| Test | Expected |
|---|---|
| `elbowTraceFromReadinessCheck` | elbow check 1 → elbow_medial and elbow_lateral flags severity 1 for 7 days; `forbidSessionTags ⊇ {elbow_flexor_heavy, grip_heavy}` |
| `sharpPainNotice` | pain sheet sharp at `elbow_medial` → severity 3, notice present, `pull_heavy` forbidden in general sessions |
| `escalation` | three mild wrist reports in 14 days → severity 2 → `grip_heavy` forbidden |
| `clearRequiresPainFreeDays` | moderate knee flag, clear after 1 day → flag stays; clear after 3 days → flag ends |
| `challengeModeMerged` | OAP injury mode → `forbidSessionTags ⊇ {grip_heavy, elbow_flexor_heavy}`, `forbidExerciseTags ⊇ {unilateral_pull, eccentric}`, elbow_medial severity 3 |
| `evaluatedFirst` | engine pipeline test: a pain event and a set log in the same tick → SafetyState computed before readiness and plan |
| `dstHold` | mild elbow report 2026-10-20 18:00 CEST → `until` = end of 2026-10-27 local (CET), 7 local days |

---

## 3. `setcredit` — SetCredit.v1 (C6, C7, C8, C10)

### 3.1 Purpose

Turn every logged set (and every planned set, for projections and challenge coverage) into per-group hypertrophy
credit (FS and Vhyp), strength credit (direct hard sets), fatigue dose, tissue dose and systemic load. This is the one
accounting function of the app: volume bars (UI-09), weekly targets and warnings (C14), readiness (C5), challenge
coverage (C10) and SEL-1 coverage all call it, so the numbers the user sees in the Week tab are exactly the numbers
the planner optimises. Runs are not sets: they produce fatigue only and are handled by `runs` (§6, F6).

### 3.2 Inputs

| Input | Description |
|---|---|
| `set` | One `set_logs` row: `exercise_id, side ∈ {both, L, R}, reps, duration_s, felt ∈ {max, hard, ok, easy}` (holds, D9), `rir_reported` (0-5, "5+" stored as 5), `reason_code`, `set_kind` (E2), `completed`, `ts` (Instant of the log tap) |
| `exercise` | Catalog row (§10): group coefficients `c(e, g)` (max over the group's regions, CAT-1), `contraction ∈ {dynamic, eccentric_only, isometric, hang}`, `laterality`, `ecc_emphasis`, joint stress `js_*` (0-3), `grip_demand` (0-3), `systemic` (1-3) |
| `plannedRir` | The prescription's target RIR for this set (RX-1, or `creditRir` for challenge items) |
| `history` | Date of the last completed working set of the same exercise before `set.ts` (novelty) |

For planned sets (`plannedCredit`), the same function is called with `rir_reported = null`, `completed = true`,
`q = plannedRir` (for challenge items the definition's `creditRir`, C10), `duration_s` = the target hold (a range
uses its midpoint), and `felt` derived from `creditRir` for holds (≤ `HOLD_NEAR_MAX_SIR` → near max).

### 3.3 Outputs

```
SetCredit {
  setId, qUsed, qSource ∈ {reported, felt, test, imputed_reason, planned, default},
  fs[g], vhyp[g], vstr[g], fat[g],            // per group, canonical order, 1e-4 grid
  tissue[k],                                  // k ∈ TISSUES (below)
  sys,                                        // systemic load units (already divided by SYS_DIVISOR)
  hard: Bool,
  sideFs[g] = {L, R}                          // per-side counts for asymmetry (unilateral only)
}
WeekAccount (per ISO week, and per rolling 7 days) {
  done[g]      = {fs, vhyp, vstr, fat},       // completed sets with ts in the window and ts ≤ now
  committed[g] = vhyp of not-yet-done challenge sets this ISO week (contract.coverageCommitted, C10)
  plannedGeneral[g] = vhyp of the week plan's remaining general sessions (projection, §8)
  sessionsTraining[g]   // distinct sessions (done + committed + planned) with session vhyp_g ≥ TRAINED_MIN_CREDIT
  daysSinceTrained[g]   // from the last done session with vhyp_g ≥ TRAINED_MIN_CREDIT; ∞ if none (C27)
  perSide[g] = {L, R}, asymmetry[g] = |L − R| / max(L, R)
}
```

Snapshot `accounting.week` (DATA_MODEL 10.1/10.2): `groups: {<g>: {vhyp_done, vstr_done, fatigue_done, committed}}`
and `sets[]: {set_id, credits: {<g>: vhyp}}`, written after each session and at week close.

### 3.4 Algorithm

```
TISSUES = [elbow_medial, elbow_lateral, shoulder_ant, shoulder_overhead, wrist, lumbar, knee, hip, grip]

qOf(set, plannedRir):
  if contraction ∈ {isometric, hang}: return (FELT_TO_Q[set.felt ?: ok], felt)
  if set.rir_reported != null:        return (clamp(set.rir_reported, 0, 5), reported)    // chip "5+" = 5
  if set.set_kind == test:            return (0, test)                                     // max-effort test set
  r = REASON-1.imputedRir(set)                                                             // e.g. too_easy → q* + 2
  if r != null:                       return (r, imputed_reason)
  if plannedRir != null:              return (plannedRir, planned)
  return (Q_MISSING, default)

credit(set, ex, plannedRir, history):
  if not set.completed or set.set_kind == warmup: return ZERO
  (q, src) = qOf(set, plannedRir)
  side = (ex.laterality == unilateral and set.side ∈ {L, R}) ? SIDE_FACTOR : 1.0          // C7: pair = 1 set
  k = ex.contraction
  if k ∈ {isometric, hang}:
     nearMax = set.duration_s >= HOLD_MIN_S and HOLD-1.sir(set.felt) <= HOLD_NEAR_MAX_SIR   // C8
     hf = nearMax ? HOLD_FACTOR_LONG : HOLD_FACTOR_SHORT
  nov = novelty(ex, set.ts, history)          // 1.5 if never or > 21 days; 1.25 if 14-21 days; else 1.0
  kf  = KIND_FAT[k]                           // dynamic 1.0, eccentric_only 1.3, isometric 0.6, hang 0.4
  ecc = (k == dynamic and ex.ecc_emphasis) ? ECC_FAT : 1.0
  for g in GROUPS:
     c = groupCoeff(ex, g); if c == 0: continue
     credited = c >= MIN_HYP_COEFF and set.set_kind != attempt
     if k ∈ {dynamic, eccentric_only}:
        fs[g]   = credited ? c * side : 0
        vhyp[g] = fs[g] * STIM[q]                                    // STIM(q) = 0 for q ≥ 6
        vstr[g] = (c == 1.0 and q <= HARD_RIR_MAX and set.set_kind != attempt) ? side : 0
     elif k == isometric:
        fs[g] = credited ? c * side * hf : 0;  vhyp[g] = fs[g]        // hold factor replaces STIM (C8)
     elif k == hang:                                                  // dead hangs credit forearms only (C8)
        fs[g] = (credited and g == forearms) ? c * side * hf : 0;  vhyp[g] = fs[g]
     fat[g] = c * side * FAT[q] * kf * ecc * nov                      // all coefficients, 0.25 included
     if ex.laterality == unilateral and set.side ∈ {L, R} and c >= MIN_HYP_COEFF: sideFs[g][set.side] += c
  for t in TISSUES:
     w = (t == grip) ? GRIP_W[ex.grip_demand] : LEVEL_W[ex.js[t]]
     tissue[t] = w * side * FAT[q] * kf * ecc                          // no novelty factor on tissue
  hard = (k ∈ {isometric, hang}) ? set.felt ∈ {max, hard, ok} : q <= HARD_RIR_MAX
  sys  = hard ? SYS_W[ex.systemic] * (k ∈ {isometric, hang} ? HOLD_SYS : 1.0) * side / SYS_DIVISOR : 0
  return round to grid
```

`STIM = [1.00, 1.00, 0.95, 0.90, 0.80, 0.50]` for q = 0..5 and 0 above; `FAT = [1.35, 1.15, 1.00, 0.90, 0.80]` for
q = 0..4 and 0.50 above (C6, one table `SetCredit.v1` in params). `FELT_TO_Q = {max: 0, hard: 1, ok: 3, easy: 5}`.

**Which RIR.** Accounting uses the RIR as reported (the chip the user tapped, D9), not the RIRCAL-1 bias-corrected
value: the C6 table is integer-indexed, the dose-response literature (Robinson 2024) is expressed in reported or
prescribed proximity to failure, and the volume bars must match what the user logged. The bias correction of C17 is
applied only inside the estimators (SSE-1). This reading of C17 is confirmed by C44.

**Why 0.25 coefficients give no FS.** Pelland 2026 fitted direct (1.0) and indirect (0.5) counting; stabiliser
contributions (0.25) were not part of the fractional model and the planning lens counts them for fatigue only. SEL-1
uses the same rule (§13), so selection, volume bars and challenge coverage agree. C44 confirms it: coefficient levels
1.0 / 0.75 / 0.5 earn fractional-set credit equal to the coefficient, 0.25 counts toward fatigue only.

**Weekly aggregation.**

```
account(week, now):
  done      = Σ credit(set) over completed sets with week.start <= set.ts <= now (local ISO week, C31)
  committed = contract.coverageCommitted         // computed by challenge.contract with plannedCredit, C10
  plannedGeneral = Σ plannedCredit over the week plan's remaining general sessions (projection only)
  sessionsTraining[g] = #{sessions S (done, committed, planned) : Σ_{set ∈ S} vhyp_S[g] >= TRAINED_MIN_CREDIT}
  daysSinceTrained[g] = localDaysBetween(last done session with vhyp[g] ≥ TRAINED_MIN_CREDIT, today), ∞ if none
```

Home habit sessions (`kind = home`, C34) are accounted like gym sessions (the daily two-arm hang credits forearms only;
the one-arm plank credits abs). Test-day sets (`kind = test`) are accounted with q = 0 unless an RIR was reported.

### 3.5 Constants

`STIM`, `FAT`, `HARD_RIR_MAX 4` (C6); `SIDE_FACTOR 0.5` (C7); `HOLD_FACTOR_LONG 0.5`, `HOLD_FACTOR_SHORT 0.25`,
`HOLD_MIN_S 10`, `HOLD_NEAR_MAX_SIR 2` (C8, weak, review constant); `MIN_HYP_COEFF 0.5`; `KIND_FAT {dynamic 1.0,
eccentric_only 1.3, isometric 0.6, hang 0.4}`; `ECC_FAT 1.3`; `NOVELTY {>21 d: 1.5, 14-21 d: 1.25}` (repeated-bout
effect, McHugh & Tetro 2003); `FELT_TO_Q`; `Q_MISSING 3`; `LEVEL_W [0, 0.1, 0.5, 1.0]` and `GRIP_W [0, 0, 0.5, 1.0]`
(tissue weight per stress level, expert-opinion); `SYS_W {1: 0.4, 2: 0.7, 3: 1.0}`, `HOLD_SYS 0.5`, `SYS_DIVISOR 15`
(planning lens: lower compound 1.0, upper compound 0.7, isolation 0.4, a "full hard session" ≈ 15 hard sets);
`TRAINED_MIN_CREDIT 1.0`. Full rows in §23.3.

### 3.6 Logged data

Per set, in the `accounting.week` snapshot: `qUsed`, `qSource`, `vhyp` per group, `fat` per group; the per-week
fraction of sets with `qSource ∈ {imputed_reason, planned, default}` (missing RIR); per-side counts and asymmetry. The
catalog version and SetCredit params hash ride on the snapshot envelope, so a coefficient change can be replayed
(§20.3).

### 3.7 Tests (fixture exercises from §10.5 and the OAP definition)

| Test | Input | Expected |
|---|---|---|
| `weightedChinupRir2` | chin-up (lats 1.0, biceps 0.5, forearms 0.5, rear_delts 0.25, upper_back 0.25), 5 reps, RIR 2 | fs lats 1.0, biceps 0.5, forearms 0.5; vhyp 0.95 / 0.475 / 0.475; vstr lats 1; fat lats 1.0, biceps 0.5, forearms 0.5, rear_delts 0.25, upper_back 0.25; hard |
| `assistedOapPair` | `oap_assisted_machine` L and R rows, 3 reps, RIR 1 | per row fs lats 0.5, biceps 0.25, forearms 0.25, abs 0.25; pair lats 1.0 FS, vstr lats 1.0; fat per row lats 0.575 |
| `oneArmHang10sHard` | `oa_dead_hang` L row, 10 s, felt Hard | fs forearms 0.5·0.5·0.5 = 0.125, every other group 0; fat forearms 0.5·0.5·1.15·0.4 = 0.115 |
| `topHoldShort` | `oa_top_hold` round of 4 × 2 s attempts (8 s), felt Max | hold factor 0.25 (< 10 s) → per side lats 0.0625 FS |
| `planks20sOk` | `one_arm_plank` 20 s per side, felt OK | per side abs 1.0·0.5·0.25 = 0.125 FS |
| `rir5Credit` | leg extension 12 reps, RIR 5+ | vhyp quads 0.50; not hard; vstr 0; sys 0 |
| `warmupZero` / `skippedZero` | warm-up set; not completed set | all zero |
| `noveltyFirstExposure` | first ever leg press set, RIR 2 | fat quads 1.0·1.0·1.5 = 1.5 |
| `stabiliserNoCredit` | back squat (lower_back 0.25), RIR 2 | fs lower_back 0, fat lower_back 0.25 |
| `committedEqualsPlanned` | contract coverage for a deload C1 | equals Σ `plannedCredit` of its materialized sets (C10) |
| `gridRounding` | any | all outputs on the 1e-4 grid; byte-identical across JVM and ART (StrictMath) |

---

## 4. `readiness` — MuscleReadiness.v1 (C5)

### 4.1 Purpose

Deterministic 0-100 % recovery per muscle group, per tissue channel (elbow, grip, wrist, shoulder, lumbar, knee, hip)
and one systemic (whole-body) channel. It drives which families can be trained today, how much volume a recovering
group gets (allocator), the protection of challenge sessions (penalty windows, C12), rest-day advice and the "ready in
X h" hints. This is the only fatigue model of the app (C5); load-prescription's between-session REST-1 part and the
app-benchmarks A1 model are dropped, and soreness-driven adaptation of the time constants goes to the offline tuner
(§20.1), not to this live model (C5).

### 4.2 Inputs

| Input | Description |
|---|---|
| `doses[]` | `{t: Instant, fat[g], tissue[k], sys}` from SetCredit (logged sets at their `ts`), from planned sessions (projection, at `start + estMinutes`) and from runs (§6, at the run's end) |
| `kappa[class]` | Per-user multipliers of the time constants by class (default 1.0; changed only by an approved tuner proposal, §20.1) |
| `checkIn?` | The latest pre-session answers (F15), valid for `CHECKIN_VALID_H`: readiness level (worse/normal/better), soreness per group (`never_sore / healed / still_sore`, DATA_MODEL E19), elbow score 0-3, optional sleep hours |
| `t` | Evaluation time (Instant) |

### 4.3 Outputs

```
Readiness(t) {
  R[g] ∈ [0, 1], F[g] (fatigue units),            // per group, canonical order
  Rt[k] ∈ [0, 1] for k ∈ TISSUES, Rt["elbow"] = min(Rt[elbow_medial], Rt[elbow_lateral]),
  G ∈ [0, 1] (systemic), S (systemic load units),
  modifiersApplied[]                             // which check-in modifiers were used
}
nextReady(g, rTarget) → Instant      // earliest time R[g] ≥ rTarget assuming no new dose
label(R) = R ≥ 0.8 "fresh" | 0.5 ≤ R < 0.8 "recovering" | R < 0.5 "fatigued";  UI percent = round(100·R)
```

Snapshot `readiness.snapshot` at every session start (scope `session:<uuid>`): all of the above for the start time.

### 4.4 Algorithm

```
F[g](t) = Σ_{d ∈ doses, 0 ≤ t − d.t ≤ WINDOW_H} d.fat[g] · exp(−(t − d.t)_h / (TAU_H[g] · kappa[class(g)]))
R[g](t) = clamp(1 − F[g](t) / F_REF[g], 0, 1)

Ft[k](t) = Σ d.tissue[k] · exp(−Δh / TISSUE_TAU_H);     Rt[k] = clamp(1 − Ft[k] / TISSUE_REF, 0, 1)
S(t)     = Σ d.sys      · exp(−Δh / SYS_TAU_H);        G     = clamp(1 − SYS_SCALE · S, 0, 1)

// check-in modifiers (only when a check-in exists within CHECKIN_VALID_H before t; never raise above the model)
if checkIn.soreness[g] present: R[g] *= (1 − SORENESS_FACTOR · SORENESS_POINTS[checkIn.soreness[g]])
if checkIn.readiness == worse:  G *= READINESS_WORSE_G
if checkIn.sleepHours != null and checkIn.sleepHours < SLEEP_SHORT_H: G *= SLEEP_SHORT_G
if checkIn.elbowScore > 0: Rt[elbow_medial] *= (1 − ELBOW_CHECK_FACTOR · elbowScore); same for elbow_lateral

nextReady(g, rT):                                // exact, because all doses of g share one time constant
  F = F[g](now) without modifiers; thr = F_REF[g] · (1 − rT)
  return F ≤ thr ? now : now + TAU_H[g] · kappa · ln(F / thr) hours
```

Elapsed time `Δh` is computed from Instants, so the DST change of 2026-10-25 makes a "Saturday 20:00 to Sunday 20:00"
interval 25 h, not 24 h (C31). `Rtarget` used by the planner and the UI: `R_TARGET_IMPROVE 0.85`,
`R_TARGET_MAINTAIN 0.75`; the allocator excludes a group below `R_EXCLUDE 0.5` and scales its sets between 0.5 and
`R_FULL 0.8` (C5).

**Time constants and reference fatigue (C5).**

| Class (kappa key) | Groups | `TAU_H` | Half-life | Residual at 48 h / 72 h | `F_REF` |
|---|---|---|---|---|---|
| `t22` (small) | biceps, triceps, side_delts, rear_delts, forearms, upper_traps, calves, abs | 22 h | 15.2 h | 0.11 / 0.04 | 6 |
| `t26` (upper large) | chest, front_delts, lats, upper_back | 26 h | 18.0 h | 0.16 / 0.06 | 8 (chest, lats, upper_back), 6 (front_delts) |
| `t30` (lower) | quads, glutes, adductors, abductors | 30 h | 20.8 h | 0.20 / 0.09 | 8 (quads, glutes), 6 (adductors, abductors) |
| `t34` (posterior) | hamstrings, lower_back | 34 h | 23.6 h | 0.24 / 0.12 | 8 (hamstrings), 6 (lower_back) |

C5 lists "obliques" in the 22 h class; obliques is a region of the `abs` group (C44), so `abs` carries it. Abductors
are in the 30 h class with `F_REF` 6 (C44). `F_REF` is "one
full hard session for that muscle"; 8 hard sets at RIR 2 of a large group give `R = 0` right after the session and
`R ≈ 0.80` 48 h later for quads (residual 0.20), consistent with the 48-72 h recovery data (Korak 2015, Morán-Navarro
2017, Soares 2015, MacDougall 1995).

### 4.5 Constants

`TAU_H` and `F_REF` as above (C5, moderate evidence for the 48-72 h range, the exact values are assumptions);
`WINDOW_H 240`; `TISSUE_TAU_H 48`, `TISSUE_REF 6` (C5: tissue channels τ = 48 h; Miller 2005 collagen synthesis is
still raised at 72 h, weak); `SYS_TAU_H 48`, `SYS_SCALE 0.5` (C5, assumption); `R_EXCLUDE 0.5`, `R_FULL 0.8` (C5);
`R_TARGET_IMPROVE 0.85`, `R_TARGET_MAINTAIN 0.75`; `KAPPA_DEFAULT 1.0`, bounds 0.7-1.5; `SORENESS_FACTOR 0.12`,
`SORENESS_POINTS {never_sore 0, healed 1, still_sore 3}`; `READINESS_WORSE_G 0.90`; `SLEEP_SHORT_H 6`,
`SLEEP_SHORT_G 0.90`; `ELBOW_CHECK_FACTOR 0.10`; `CHECKIN_VALID_H 12`. Full rows in §23.4.

### 4.6 Logged data

`readiness.snapshot` at every session start (R, F, Rt, G, modifiers, kappa); the performance index of the first hard
set of each exercise (`PI = e1RM_set / best e1RM of the prior 28 days`, from the SSE-1 engine-logged per-set e1RM, C3);
soreness answers at the next exposure (F15); "feeling bad" / "too hard" reasons. These are the inputs of tuner rule
T-KAPPA (§20.1): readiness at session start should predict PI.

### 4.7 Tests

| Test | Input | Expected |
|---|---|---|
| `decay48hQuads` | fat quads 4.0 at t0; query t0 + 48 h | F = 4·e^(−48/30) = 0.8076 → R = 0.899 |
| `nextReadyQuads` | fat quads 8.0 at t0; rTarget 0.85 | thr 1.2 → t0 + 30·ln(8/1.2) = t0 + 56.9 h |
| `dstSpan` | fat quads 8.0 at Sat 2026-10-24 20:00 CEST (18:00Z); query Sun 2026-10-25 20:00 CET (19:00Z) | Δ = 25 h → F = 3.477, R = 0.565 (not 0.551, the 24 h value) |
| `windowCut` | a dose 241 h ago | ignored |
| `sorenessModifier` | R 0.9, soreness still_sore | 0.9·(1 − 0.36) = 0.576 |
| `checkInExpires` | check-in 13 h before t | no modifier |
| `elbowComposite` | Rt elbow_medial 0.84, elbow_lateral 1.0 | Rt["elbow"] 0.84 |
| `kappaScales` | kappa t30 = 1.2, same dose as `decay48hQuads` | τ 36 h → R = 1 − 4·e^(−48/36)/8 = 0.868 |
| `orderIndependent` | the same doses in any order | identical output (summation in time order, then group order) |

---

## 5. `targets` — WeeklyTargets.v1 + DeloadPolicy + re-entry (C13, C14, C27, D6, F8)

### 5.1 Purpose

Convert the user's muscle goals (improve / maintain / skip, optional focus; D6), training age, the challenge contract
and the recent history into, per group and per ISO week: a weekly volume target, a recovery cap, per-session caps, a
frequency target, a value weight for the planner, and the session transforms that apply this week (deload, re-entry).
It also defines the utility function `U` that the allocator, the planner and the recommender maximise. Groups owned by
an active challenge are "covered by challenge" (D6) and get no general volume.

### 5.2 Inputs

| Input | Description |
|---|---|
| `goals` | `muscle_goals` rows (goal, focus) per group, usually created by a preset (§5.6) |
| `trainingAge` | `profiles.training_age ∈ {novice, intermediate, advanced}`; null (onboarding skipped) → `DEFAULT_TRAINING_AGE` = novice (C64) |
| `age` | from `profiles.birth_year`, optional (only used for the 60+ maintenance rule; no default depends on age or sex, D3) |
| `contract` | ChallengeContract (§0.5): `ownsMuscles`, `mode`, `isDeloadWeek`, `coverageCommitted`, block index and week |
| `account` | WeekAccount (§3) for this week, plus `daysSinceTrained` and `sessionsTraining` |
| `previousTargets` | The `targets.weekly` snapshot of the previous ISO week (ramp state) |
| `signals` | SSE-1 per-exercise e1RM changes per exposure (engine-logged), soreness answers (UI-07), readiness snapshots (G at session starts), sessions with `feeling_bad` or `too_tired` reasons (C57) or `worse` readiness, STALL-1 `deloadRecommended` |
| `settings` | `deload_sync_with_challenge` (F8, default true), `evidence_mode` (D10) |

### 5.3 Outputs

```
WeeklyTargets {
  isoWeek, base = BASE_BY_AGE[trainingAge], ref = base, mesocycleWeek (C31), status ∈ {ok, setup_needed},
  groups[g]: { goal, focus, status ∈ {improve, maintain, skip, covered},
               target T (normal week), targetEffective (after this week's deload), mrv, capS, minTouch,
               freqTarget, w, coveredByChallenge, metByChallenge, conflictNote?,
               reentry {active, sessionsRemaining}, rampDelta, intensityCheck {ok, lastQualifyingSetId?} },
  deload { active, source ∈ {challenge, reactive, none}, setFactor, rirOffset, triggers[] },
  reentryTransform { setFactor, rirOffset },
  combineRule = "min_factor_max_offset"
}
```

Snapshot `targets.weekly` (scope `week:<iso_week>`) with the DATA_MODEL 10.2 keys `groups: {<g>: {goal, target, mrv,
cap_s, covered_by_challenge, reentry, deload}}` plus the fields above.

### 5.4 Algorithm

```
if no muscle_goals rows exist: return status = setup_needed        // Today shows SETUP_NEEDED (UX 9.3); no general plans
BASE = BASE_BY_AGE[trainingAge ?: DEFAULT_TRAINING_AGE]           // 10 / 12 / 14 (C14)
owned = groupsOf(contract.ownsMuscles)                             // empty for a paused enrollment (empty contract, §0.5)
for g in GROUPS (canonical order):
  goal = goals[g].goal; focus = goals[g].focus
  if g ∈ owned:
     status = covered; T = 0; W = 0                                 // D6; challenge volume is exempt from MRV warnings
     if goal == skip: conflictNote = "Your challenge trains this group while it is active"   // challenge wins (precedence)
     continue
  switch goal:
   improve:  T = min((SPECIAL_IMPROVE[g] ?: BASE) + (focus ? FOCUS_BONUS : 0), MRV[g])
             W = focus ? W_FOCUS : W_IMPROVE; capS = CAP_S_IMPROVE; minTouch = MIN_TOUCH_IMPROVE
             freqTarget = FREQ_TARGET_IMPROVE
   maintain: T = max(MAINTAIN_MIN, round(BASE / MAINTAIN_DIVISOR)); freqTarget = FREQ_TARGET_MAINTAIN
             if age != null and age >= AGE_60: T = max(MAINTAIN_MIN_60, round(BASE / 2)); freqTarget = 2
             W = W_MAINTAIN; capS = CAP_S_MAINTAIN; minTouch = MIN_TOUCH_MAINTAIN
             intensityCheck = exists hard set (q ≤ HARD_RIR_MAX) in the current ISO week (C14: ≥ 1 hard set per week
                              at normal load) on an exercise with c(e, g) ≥ 0.75 at ≥ MAINTAIN_LOAD_FRAC of its
                              8-week working load
   skip:     T = 0; W = 0                                           // ELIG-1 removes c ≥ 0.5 exercises (§12)
  if goal == improve: T = applyRamp(g, T, previousTargets, signals)     // §5.4.1
  metByChallenge = (status ∈ {improve, maintain}) and account.done[g] + contract.coverageCommitted[g] ≥ T
deload  = deloadPolicy(contract, settings, signals)                 // §5.4.2
for g: targetEffective = deload.active ? round_half(T · deload.setFactor) : T
for g: reentry[g] = account.daysSinceTrained[g] > REENTRY_GAP_DAYS  → {active, sessionsRemaining = REENTRY_SESSIONS − sessions since the gap}
```

**Utility (used by §7-§9).** With `REF = BASE`:

```
U_g(V) = 0                                                                   if status ∈ {skip, covered}
U_g(V) = sqrt(min(V, MRV_g) / REF) − OVER_MRV_SLOPE · max(0, V − MRV_g) / REF       if improve
U_g(V) = sqrt(min(V, T_g) / REF) + MAINTAIN_OVER_SLOPE · max(0, V − T_g) / REF      if maintain
value of a week = Σ_g W_g · U_g(V_g)
```

The square-root shape is the best-fitting dose-response for hypertrophy (Pelland 2026, strong for the shape). A
common `REF` keeps groups comparable and keeps the utility scale unchanged in deload weeks (the deload is a session
transform, not a change of `U`). Improve groups keep gaining (with diminishing returns) up to MRV, then lose value;
maintain groups gain little beyond `T` (Bickel 2011; Spiering 2021). The allocator never assigns more than the
remaining target `T − done − committed` (§7), so the improve curve above `T` only values incidental volume.

#### 5.4.1 Ramp (C14)

Evaluated at the close of each ISO week for the next week, improve groups only, from mesocycle week 2 onward, never in
a deload week or the week right after it. The week-close evaluations of `targets` (this ramp and the reactive deload
triggers of §5.4.2) run on the same tick as the challenge's `week.closed` event (Monday at
`challenge.enrollment.week_close_local_time`, default 04:00 local, CHALLENGE_ENGINE 4.3) and after it, like STALL-1's weekly evaluation (ALGORITHMS A12.3), so a late
Sunday session counts for its own ISO week. `weekplan` itself is not tied to that tick: it replans on its triggers
(§8.7).

```
P_g = round(mean over this week's valid exposures of exercises with c(e, g) ≥ 0.75 of perfScore(Δe1RM))
       perfScore: Δ ≥ +2 % → 1;  −1 % ≤ Δ < +2 % → 2;  −3 % ≤ Δ < −1 % → 3;  Δ < −3 % → 4
       (Δ = SSE-1 engine-logged e1RM of the exposure vs the previous exposure; exposures with pain,
        feeling_bad, too_tired (same effect as feeling_bad, C57), no_time or occupied reasons are excluded)
S_g = max soreness score of this week's answers for g: never_sore → 1, healed → 2, still_sore → 4
delta = P_g == 4                        → −2   (also a reactive-deload signal)
        S_g == 4 or P_g == 3            →  0
        S_g == 1 and P_g == 1           → +2
        P_g ≤ 2 and S_g ≤ 2             → +1
        P_g or S_g missing              →  0
delta = min(delta, max(1, floor(RAMP_CAP_FRAC · T)))            // +20 %/week cap (C14; Camargo 2026 preprint, weak)
T_next = clamp(T + delta, T_base − 2, MRV_g)                    // T_base = the goal-derived T before any ramp
at a new mesocycle (week 1): T = clamp(round(0.5 · (T_lastTolerated + T_base)), T_base, MRV_g)
       T_lastTolerated = T of the last non-deload week whose P_g ≠ 4
```

This automates the RP soreness × performance table (app-benchmarks A9, expert-opinion) without asking pump or workload
questions; performance is computed, soreness comes from the one optional question (F15). RP MEV values are never used
(C14).

#### 5.4.2 DeloadPolicy (C13, F8)

```
deloadPolicy(contract, settings, signals):
  if contract active and settings.deload_sync_with_challenge and contract.isDeloadWeek:
       return {active, source = challenge, setFactor = DELOAD_SET_FACTOR, rirOffset = DELOAD_RIR_OFFSET}
  if this week was flagged by a reactive trigger at the previous week close and the user did not tap "Keep normal week":
       return {active, source = reactive, ...same transform...}
  return {active = false, source = none}

reactive triggers, evaluated at each ISO week close for the next week (union of the planning and STALL-1 global rules):
  R1  ≥ REACTIVE_KEY_EXERCISES (2) mesocycle anchors (ROT-1) with e1RM ≤ −3 % on 2 consecutive exposures
  R2  G < REACTIVE_G (0.6) at 2 consecutive session starts
  R3  ≥ 2 consecutive gym sessions with any feeling_bad or too_tired reason (C57) or a "worse" readiness answer
  R4  STALL-1 global deloadRecommended (≥ 40 % of active exercises stalled/regressing within 14 d, or
      delta_today ≤ −0.04 in ≥ 3 of the last 4 sessions, or feeling_bad ≥ 3 in 14 d)
  skip if next week is already a planned (challenge) deload week
```

Transform (C13): sets × 0.5, load kept, RIR + 2. The −10 % load cut belongs only to STALL-1 tier 1 for a single
exercise. Without an active challenge there is no planned deload (F8: reactive only). A reactive deload is shown as a
week banner with its trigger and a one-tap "Keep normal week" (logged); it is a recommendation (D10), never a lock.

#### 5.4.3 Re-entry (C27)

Groups with `daysSinceTrained > REENTRY_GAP_DAYS` (21; `∞` when the app has no history for the group) get, for the next
`REENTRY_SESSIONS` (2) sessions that train them, set factor 0.6 and RIR + 1. Loads come only from SSE-1 decay (C27, not
specified here). The first exposure of a new or long-unused exercise is a calibration set (RX-1). Challenge pause rules
override for challenge exercises (C27).

**Combining deload and re-entry (C51).** When both apply to a group, they are never stacked: the set factor
is the minimum (`min(0.5, 0.6) = 0.5`) and the RIR offset is the maximum (`max(+2, +1) = +2`). Stacking would give
sets × 0.3, which no source supports.

### 5.5 Constants

`BASE_BY_AGE {novice 10, intermediate 12, advanced 14}` (C14; ACSM 2026 ≥ 10 sets/week, strong; Baz-Valle 2022 12-20);
`DEFAULT_TRAINING_AGE novice` (C64); `FOCUS_BONUS 4` (C14); `SPECIAL_IMPROVE {front_delts 6, forearms 6,
lower_back 4, adductors 6, abductors 6, upper_traps 6}` (planning lens, expert-opinion; abductors [this doc]);
`MRV {chest, lats, upper_back, quads, glutes, hamstrings 22; side_delts, rear_delts 24; biceps, triceps, calves, abs,
front_delts, upper_traps, forearms, abductors, adductors 20; lower_back 16}` (RP expert opinion, used only as caps,
C14); `MAINTAIN_MIN 3`, `MAINTAIN_DIVISOR 3` (C14; Bickel 2011), `MAINTAIN_MIN_60 6`, `AGE_60 60`;
`W_FOCUS 1.5, W_IMPROVE 1.0, W_MAINTAIN 0.6`; `CAP_S_IMPROVE 10, CAP_S_MAINTAIN 4` (Remmert preprint PUOS ≈ 11, weak);
`MIN_TOUCH_IMPROVE 2, MIN_TOUCH_MAINTAIN 1`; `FREQ_TARGET_IMPROVE 2` (ACSM 2026, strong), `FREQ_TARGET_MAINTAIN 1`
(Spiering 2021); `OVER_MRV_SLOPE 0.5`, `MAINTAIN_OVER_SLOPE 0.05`; ramp table and `RAMP_CAP_FRAC 0.20`;
`DELOAD_SET_FACTOR 0.5`, `DELOAD_RIR_OFFSET 2` (C13); `REACTIVE_*`; `REENTRY_GAP_DAYS 21`, `REENTRY_SET_FACTOR 0.6`,
`REENTRY_RIR_OFFSET 1`, `REENTRY_SESSIONS 2` (C27); `MAINTAIN_LOAD_FRAC 0.85`. Full rows in
§23.5.

### 5.6 Goal presets (D6)

Onboarding offers these presets (the membership is the UX default of UX 8.9; the user edits freely afterwards). Groups
owned by an active challenge are shown as "covered by challenge" whatever the preset says.

| Preset (`profiles.muscle_goal_preset`) | Improve | Maintain | Skip |
|---|---|---|---|
| `balanced` | chest, side_delts, rear_delts, lats, upper_back, biceps, triceps, quads, hamstrings, glutes | front_delts, upper_traps, forearms, abs, lower_back, abductors, adductors, calves | none |
| `legs_only` | quads, hamstrings, glutes, adductors, abductors, calves | abs, lower_back | all upper-body groups |
| `no_legs` | chest, side_delts, rear_delts, lats, upper_back, biceps, triceps | front_delts, upper_traps, forearms, abs, lower_back | quads, hamstrings, glutes, adductors, abductors, calves |
| `pull_focus` ("pull focus, push + legs maintained") | lats (focus), upper_back (focus), biceps, rear_delts, forearms | chest, front_delts, side_delts, triceps, quads, hamstrings, glutes, calves, abs, lower_back, upper_traps, adductors, abductors | none |
| `custom` | user's choice per group | | |

Resulting weekly targets (normal week, no ramp yet, no challenge):

| Preset | intermediate (BASE 12) | novice (BASE 10) |
|---|---|---|
| `balanced` | 10 improve groups × 12 = 120; 8 maintain groups × 4 = 32 → 152 FS-equivalent Vhyp/week | 10 × 10 + 8 × 3 = 124 |
| `legs_only` | quads, hamstrings, glutes 12; adductors, abductors 6; calves 12; abs, lower_back 4 → 68 | 30 + 12 + 10 + 6 = 58 |
| `no_legs` | 7 × 12 + 5 × 4 = 104 | 7 × 10 + 5 × 3 = 85 |
| `pull_focus` | lats 16, upper_back 16, biceps 12, rear_delts 12, forearms 6; 13 maintain × 4 = 52 → 114 | 14 + 14 + 10 + 10 + 6 + 39 = 93 |

With the OAP challenge active, lats, biceps and forearms are covered (their targets drop out of the general engine), so
`balanced` asks the general engine for 152 − 12 − 12 − 4 = 124 (intermediate).

**Evidence warnings tied to goals (via the WarningEngine, C14).** A whole family set to skip → one-time INFO "train all
major muscle groups at least twice a week for health" (ACSM 2026). An improve target that the plan cannot reach is a
volume warning (weekly < 10 INFO, < 6 WARN, < 3 STRONG, C14), computed against `targetEffective` in deload weeks so
that a planned deload does not raise warnings.

### 5.7 Logged data

`targets.weekly` every ISO week and on every goal change: T, targetEffective, status, W, ramp inputs (P, S, delta,
cap hit), deload source and triggers, re-entry state; attainment (achieved Vhyp / T) per group at week close; the
maintain intensity check. Review use: tuner rule T-TARGET (§20.1) and the personal dose-response (e1RM slope versus
weekly Vhyp per group over 4-8 weeks).

### 5.8 Tests

| Test | Expected |
|---|---|
| `balancedIntermediateWithOap` | covered = {lats, biceps, forearms}; chest T 12, W 1.0; calves T 4, W 0.6; upper_back T 12 (not owned) |
| `focusAndMrv` | pull_focus intermediate: lats T 16 (12 + 4 ≤ 22) |
| `maintain60` | maintain, age 64, BASE 12 → T 6, freqTarget 2 |
| `skipOwnedConflict` | goal skip on lats with OAP active → status covered, conflictNote set |
| `deloadSyncedWeek41` | contract mode test_pending (isDeloadWeek) → deload active, source challenge, setFactor 0.5, rirOffset 2; chest targetEffective 6 |
| `noChallengeNoPlannedDeload` | no contract → deload only from reactive triggers |
| `reactiveTriggerR2` | G 0.55 and 0.58 at two consecutive session starts → next week deload, source reactive |
| `reentryLegsFirstWeek` | no leg history → quads reentry active, sessionsRemaining 2 |
| `deloadReentryNoStack` | both active → set factor 0.5, RIR +2 |
| `rampCap` | T 12, P 1, S 1 → delta +2 (cap max(1, floor(2.4)) = 2); T 6 → delta capped to 1 |
| `utilityShape` | improve, REF 12: U(3) = 0.5, U(12) = 1.0, U(22) = 1.354, U(24) = 1.354 − 0.083 = 1.271 |

---

## 6. `runs` — RunInterference.v1 (F6)

### 6.1 Purpose

Model running (from calendar events classified as runs, and later from Health Connect, M6) as leg fatigue and timing
conflicts, with **no hypertrophy credit** (F6). Running priority is a user setting with default `low` (F6).

### 6.2 Inputs

| Input | Description |
|---|---|
| `runs[]` | `{start: Instant, end: Instant, minutes, intensity ∈ {easy, tempo, intervals, race}, long: Bool, source ∈ {calendar, logged}}`. Past calendar runs are assumed done unless the user marked them "didn't run" (an override to `ignored`). |
| `runPriority` | `profiles.running_priority ∈ {low, medium, high}` (F6, default low) |
| `contract.restDayBefore` | `runs: warn \| forbid` (C11; OAP `warn`) |

### 6.3 Outputs

* `doses[]`: per run, a readiness dose at `run.end`: `fat[g]` for quads, calves, hamstrings, glutes, and `sys`.
* `penalty(sessionInterval, legFs)` → `{value, reasons[]}` for planner and recommender candidates that give at least
  one FS to a LEGS group.
* `warnings[]`: `RUN_ON_REST_DAY` when a run falls on a challenge rest day whose `runs` policy is `warn` (never blocks).

### 6.4 Algorithm

```
dose(run):
  f = INTENSITY[run.intensity] · ((run.long or run.minutes > RUN_LONG_MINUTES) ? LONG_FACTOR : 1.0)
  fat[g] = PER_MIN[g] · run.minutes · f          for g ∈ {quads, calves, hamstrings, glutes}
  sys    = SYS_PER_40MIN · (run.minutes / 40) · INTENSITY[run.intensity]
  return {t = run.end, fat, tissue = {}, sys}

penalty(session [s, e], legFs):
  if legFs == 0: return 0
  m = {low: 1.0, medium: 1.0, high: 2.0}[runPriority]
  p = 0
  for r in runs:
    if r.end <= s and s − r.end <= WINDOW_H:             p += RUN_BEFORE_PEN · m; reason RUN_NEAR
    if r.start >= e and r.start − e <= WINDOW_H:         p += RUN_AFTER_PEN · m;  reason RUN_NEAR
    hard = r.intensity ∈ {tempo, intervals, race} or r.long
    if runPriority != low and hard and e < r.start <= e + HARD_RUN_H:
                                                          p += HARD_RUN_PEN · m;   reason RUN_NEAR
  return p
```

Upper-body sessions get no run penalty. A run on the rest day before B or TEST is allowed with a warning only (C11,
critique: "a run that day is a warning only").

### 6.5 Constants

`PER_MIN {quads 0.05, calves 0.06, hamstrings 0.03, glutes 0.03}` per minute (planning lens, assumption: a 40-min easy
run ≈ 2 quad and 2.4 calf set-equivalents of fatigue); `INTENSITY {easy 1.0, tempo 1.5, intervals 1.8, race 2.0}`;
`LONG_FACTOR 1.3`, `RUN_LONG_MINUTES 75` (the classifier's key `calendar.classify.run_long_minutes`, shared with §1.4); `SYS_PER_40MIN 0.5` (the planning lens used 1.0, which would make a 40-min
easy run as taxing as a full 15-set lower-body session; halved [this doc], tunable); `WINDOW_H 8` (Sporer & Wenger
2003: leg-press volume down 4-8 h after aerobic work, not at 24 h; moderate); `RUN_BEFORE_PEN 0.5`,
`RUN_AFTER_PEN 0.3`; `HARD_RUN_H 48` (Doma: residual strength-training fatigue impairs running for 24-48 h; moderate),
`HARD_RUN_PEN 0.4`. Chronic interference for hypertrophy and maximal strength is negligible (Schumann 2022), so there
is no hypertrophy discount. Full rows in §23.6.

### 6.6 Logged data

Run anchors (intensity, minutes, long), penalties applied to candidates (in the today/weekplan traces), the
performance index of leg sessions preceded by a run within 24 h versus not, and user overrides that cite running. Tuner
rule T-RUN (§20.1).

### 6.7 Tests

| Test | Expected |
|---|---|
| `easy40` | 40 min easy → fat quads 2.0, calves 2.4, hamstrings 1.2, glutes 1.2; sys 0.5 |
| `longIntervals90` | 90 min intervals → factor 1.8·1.3 = 2.34; quads 0.05·90·2.34 = 10.53 |
| `legsAfterRun6h` | run ends 13:00, LEGS session 19:00 → penalty 0.5 |
| `upperNoPenalty` | PUSH session 2 h after a run → 0 |
| `hardRunPriorityLow` | tempo run 30 h after a LEGS session, priority low → no 48-h penalty; medium → 0.4; high → 0.8 |
| `runOnRestDay` | run on Sat before TEST → `RUN_ON_REST_DAY` warning, plan unaffected |

---

## 7. `allocator` — SessionDoseAllocator.v1

### 7.1 Purpose

For one candidate session (a session type on a given day and start time with a time budget, or an add-on block after
a challenge session), decide how many fractional sets each included muscle group gets, respecting the remaining weekly
targets, readiness, per-session caps, the contract's hard windows and gap-rule tag limits, the safety state, the time
budget (TIME-1) and this week's session transforms (deload, re-entry). SEL-1 later turns these numbers into exercises
(§13). The planner and the recommender call the allocator for every candidate they evaluate, so it must be cheap
(no exercise selection inside).

### 7.2 Inputs

| Input | Description |
|---|---|
| `type` | A session type of §0.3, or `ADDON(F)` with `F ∈ {LEGS, PUSH, CORE}` |
| `start`, `budgetMin` | Planned start Instant; budget = calendar event duration − `changeoverMin` (5 min; calendar gym events are training time, F11 revised, C49), else the default 75 min (F11); for add-ons the slot's `capacityMinutes` (§17.6, C49) |
| `Vbefore[g]` | Vhyp already accounted for the ISO week before this session: done + committed challenge coverage + projected Vhyp of earlier general sessions of the plan being evaluated |
| `opp[g]` | Number of standalone general sessions in the plan, from this one onward, whose type includes `g` (≥ 1). Add-on blocks are not counted: they take what is left |
| `readiness` | `Readiness(start)` including all earlier doses of the plan (§4) |
| `targets` | §5: status, `T`, `W`, `capS`, `minTouch`, deload and re-entry state |
| `dayConstraints` | `forbiddenGroups` (groups of hard protected windows overlapping the session, C12, plus covered groups), `forbiddenSessionTags` (gap rule for this date, C11; hard-window session tags; `safety.forbidSessionTags`, C33), `gapDay` (the C50 light-pull filter applies, §12.3), `available[g]` from `elig.groupAvailability(dayConstraints)` (§12.4) |
| `addOnSlot?` | `allowPatterns` → allowed groups, `forbidGroups`, `maxSets`, `minRir`, `maxGripDemand` |
| `q_base` | RX-1 default target RIR (2) |

### 7.3 Outputs

```
Allocation {
  type, label (§9.5), feasible, infeasibleReason?,
  alloc[g]       // target FS before indirect credit and time fitting (trace)
  normal[g]      // direct FS per primary group after indirect credit, tag caps and the time fit, before transforms
  direct[g]      // after the session transforms (deload / re-entry); what SEL-1 receives as targets
  credit[g]      // direct + expected indirect FS (what the session is expected to credit)
  setFactor[g], rirOffset[g],
  estMinutes (TIME-1 dose estimator, §17.5), normalMinutes,
  predictedTags[],                      // from sessionTagRules applied to credit
  dropped[]: {g, reason ∈ {TARGETS_MET, RECOVERING, PROTECT_REST, COVERED_BY_CHALLENGE, NO_ELIGIBLE_EXERCISE, TIME_SHORT, SAFETY_JOINT}},
  dose            // projected readiness dose of this session (§7.6)
}
```

### 7.4 Algorithm

```
allocate(type, start, budgetMin, Vbefore, opp, ctx):
  R = ctx.readiness(start)
  M = groups(families(type)) ∪ (type ∉ {CORE} and not addon ? CORE riders : {})
  if addon: M = M ∩ groupsReachable(slot.allowPatterns) − slot.forbidGroups
  a = {}
  for g in M (canonical order):
    if status_g ∈ {skip, covered}:            dropped += (g, COVERED_BY_CHALLENGE if covered); continue
    if g ∈ ctx.forbiddenGroups:               dropped += (g, PROTECT_REST); continue
    if not ctx.available[g]:                  dropped += (g, NO_ELIGIBLE_EXERCISE); continue
    remFS = max(0, T_g − Vbefore_g) / STIM(q_base)          // Vhyp → FS at the default RIR (0.95)
    if remFS ≤ 0:                             dropped += (g, TARGETS_MET); continue
    if R_g < R_EXCLUDE:                       dropped += (g, RECOVERING); continue
    x = clamp(ceil_half(remFS / max(1, opp_g)), minTouch_g, capS_g)
    if R_g < R_FULL: x = round_half(x · R_g)                 // C5: scale between 0.5 and 0.8
    a[g] = x
  if Σ a < MIN_TOTAL[addon ? addon : standalone]: return infeasible(NOTHING_TO_TRAIN)

  // 1. expected indirect credit: primaries first, then the rest in canonical order
  normal = {}
  for g in PRIMARY_ORDER ∩ keys(a), then other keys(a) in canonical order:
     normal[g] = max(0, ceil_half(a[g] − indirectTo(g, normal)))
  // 2. tag caps: the session must not acquire a forbidden session tag (C11, C12, C33)
  for tag in ctx.forbiddenSessionTags ∩ {pull_heavy, elbow_flexor_heavy}:
     rule = contract.sessionTagRules[tag]                    // e.g. pull_heavy: lats + upper_back ≥ 4 hard sets
     while Σ_{g ∈ rule.groups} credit(normal)[g] > rule.minHardSets − 1:
        remove 0.5 FS from the rule group with (lowest W, then largest value, then canonical order)
  // grip_heavy is enforced at exercise level: ELIG removes exercises with grip_demand ≥ 2 (§12)
  // 3. time fit (TIME-1 dose estimator, §17.5)
  while estDose(normal, warm = !addon) > budgetMin:
     for each g with normal[g] > 0: candidate = normal with g reduced by 0.5 (to 0 if credit_g < minTouch_g)
     choose the candidate with the smallest (Δutility / Δminutes), ties by canonical order    // utility below
     if no candidate saves time: return infeasible(TIME_SHORT)
  // 4. session transforms (C13, C27; no stacking, §5.4.3)
  for g in normal:
     f = min(deload.active ? DELOAD_SET_FACTOR : 1, reentry_g.active ? REENTRY_SET_FACTOR : 1)
     direct[g] = (f < 1) ? max(1, round_half(normal[g] · f)) : normal[g]
     rirOffset[g] = max(deload.active ? DELOAD_RIR_OFFSET : 0, reentry_g.active ? REENTRY_RIR_OFFSET : 0)
  if addon: while Σ direct > slot.maxSets: remove 0.5 FS from the group with (lowest W, largest value, canonical)
            rir target = max(q_base + rirOffset, slot.minRir)
  credit = direct + indirect(direct)
  if Σ credit < MIN_TOTAL[...]: return infeasible(NOTHING_TO_TRAIN)
  predictedTags = sessionTagRules(credit)                  // must be ∩ forbiddenSessionTags = ∅ by construction
  return Allocation(...)
```

`utility(d) = Σ_g W_g · (U_g(Vbefore_g + credit(d)_g · STIM(q_base) · (0.5 + 0.5·R_g)) − U_g(Vbefore_g))`, with `U`
from §5.4 and the quality factor `0.5 + 0.5·R` (a set done on a partly recovered muscle is worth less; planning lens).
The time fit removes the cheapest utility per minute first, so maintain riders and low-weight groups go before improve
groups.

Implementation note (M4.3): on a gap day the rule groups of `pull_heavy` (lats, upper back) have no direct sets, so the
tag cap of step 2 removes half sets from the rule groups and from the groups that credit them through the indirect table
(rear delts → upper back), by the same order. With that reading the code reproduces §21.8 exactly (`alloc`, `normal`
74.8 min, `direct` 49.6 min, and the Thursday add-on's 32.5 → 13.8 min).

**Indirect credit table** (`allocator.indirect`, expert-opinion; `ratio` 0.5 is the fractional rule of Pelland 2026,
`share` is the expected fraction of the source group's sets done with a compound that trains the target):

| Source → target | ratio | share | Typical exercise |
|---|---|---|---|
| chest → triceps | 0.5 | 1.0 | presses |
| chest → front_delts | 0.5 | 1.0 | presses |
| quads → glutes | 0.5 | 0.5 | leg press, squat (Kubo 2019: glute growth with squats) |
| quads → adductors | 0.5 | 0.5 | leg press, squat |
| hamstrings → glutes | 0.5 | 0.33 | RDL (curls give none) |
| upper_back → rear_delts | 0.5 | 1.0 | rows |
| rear_delts → upper_back | 0.5 | 1.0 | reverse fly, face pull |
| front_delts → triceps | 0.5 | 1.0 | overhead press |
| front_delts → side_delts | 0.5 | 1.0 | overhead press |
| lats → biceps | 0.5 | 1.0 | pulldowns, pull-ups (Mannarino 2021 ≈ 0.47) |
| upper_back → biceps | 0.5 | 0.5 | rows |

`PRIMARY_ORDER = chest, quads, hamstrings, lats, upper_back, front_delts`.

### 7.5 Session label (used by §9.5 and the UI)

The label lists the families that receive direct FS in the order Legs, Push, Pull, joined with " + " and sentence-cased
("Legs + push"). For PULL: if its direct groups are a subset of {rear_delts} the label word is "rear delts"; a subset of
{rear_delts, upper_back} → "upper back"; otherwise "pull". CORE riders never appear in the label; a CORE-only session
is "Core". An add-on is labelled "{label} add-on".

### 7.6 Projected dose of a planned general session

Used by the planner to update readiness and weekly volume while it simulates the week (exercises are not chosen yet).

```
t_dose = start + estMinutes
q_g    = q_base + rirOffset[g]
Vhyp_g (projected) = credit_g · STIM(q_g) · (0.5 + 0.5·R_g(start))
fat_g  = credit_g · FAT(q_g) · (reentry_g.active ? NOVELTY_21D : 1)       // first exposures are novel (§3)
tissue_k = Σ_g direct_g · TISSUE_RATE[g][k] · FAT(q_g)
sys    = Σ_g direct_g · SYS_RATE[g] / SYS_DIVISOR
```

`TISSUE_RATE` (expected tissue weight per direct FS, from the typical exercises of the group, expert-opinion): chest
{shoulder_ant 0.1}; front_delts {shoulder_ant 0.5}; side_delts {shoulder_overhead 0.1}; triceps {elbow_lateral 0.1};
upper_traps {grip 0.5}; quads {knee 0.5}; hamstrings {knee 0.1}; glutes, abductors, adductors {hip 0.1}; lower_back
{lumbar 0.5}; abs {lumbar 0.1}; lats {grip 0.5, elbow_medial 0.1}; upper_back {grip 0.5}; biceps {elbow_medial 0.5};
forearms {grip 1.0}; rear_delts and calves none. `SYS_RATE` (expected systemic weight per direct FS): quads 0.7,
hamstrings 0.6, glutes 0.8, chest 0.7, lats 0.7, upper_back 0.7, front_delts 0.6, upper_traps 0.5, lower_back 0.5, all
other groups 0.4. When the session is actually built (SEL-1) or logged, these projections are replaced by the exact
SetCredit doses.

### 7.7 Constants

`MIN_TOTAL {standalone 3, addon 2}` (planning lens: fewer than 3 sets is not a session); the indirect table,
`PRIMARY_ORDER`, `TISSUE_RATE`, `SYS_RATE` (expert-opinion, tunable by comparing the allocator's `credit` with the
credit SEL-1 actually achieved, tuner rule T-ALLOC); readiness thresholds from §4; transforms from §5. Full rows in
§23.7.

### 7.8 Logged data

Every allocation evaluated by the planner is summarised in the planner trace (`alloc`, `normal`, `direct`, `dropped`,
minutes). For the chosen sessions: allocated versus SEL-1-selected versus performed FS per group, estimated versus
actual minutes (TIME-1 calibration), and groups dropped for time or recovery.

### 7.9 Tests

| Test | Expected |
|---|---|
| `recoveringExcluded` | R_quads 0.45 → quads dropped `RECOVERING` |
| `partialScale` | x 4.0, R 0.7 → round_half(2.8) = 3.0 |
| `remainingSplitAcrossOpportunities` | rem 12 Vhyp (12.63 FS), opp 2, capS 10 → ceil_half(6.32) = 6.5 |
| `indirectFirst` | chest 6, triceps 6 → normal chest 6, triceps max(0, ceil_half(6 − 3)) = 3 |
| `pullHeavyCap` | gap day with a synthetic light-pull exercise that passes the C50 filter and credits upper_back 1.0 (grip 1, biceps 0), alloc upper_back 6 → capped so credited lats + upper_back ≤ 3 |
| `gapDayNoRows` | OAP gap day with the seed catalog: upper_back has no eligible direct exercise (every row has a biceps coefficient of 0.5, C50) → dropped `NO_ELIGIBLE_EXERCISE`; rear_delts stays available (reverse pec deck) |
| `deloadMinOneSet` | normal calves 1.0, deload → direct 1.0 (not 0.5) |
| `deloadReentryNoStack` | normal quads 3.5, deload + re-entry → round_half(1.75) = 2.0 |
| `addonMaxSets` | A slot maxSets 4, direct legs 5.5 after transform → reduced to 4 |
| `infeasibleUnder3` | only abs with rem 1 → infeasible `NOTHING_TO_TRAIN` |
| `wedFixture` | §21 (the "train anyway" option): Wed 19:45 FULL → dropped upper_back and upper_traps `NO_ELIGIBLE_EXERCISE` (C50), abs `TARGETS_MET`; normal {chest 8, side_delts 3.5, rear_delts 3, lower_back 1, abductors 1, quads 3.5, hamstrings 4, calves 1} (74.6 min) → direct {chest 4, side_delts 2, rear_delts 1.5, lower_back 1, abductors 1, quads 2, hamstrings 2, calves 1}, 48.6 min |
| `friFixture` | §21.8: Fri 19:45 FULL after the Thursday legs add-on → normal {chest 8.5, side_delts 3.5, rear_delts 5, lower_back 1, abductors 1, quads 2.5, hamstrings 3, calves 1} (74.8 min) → direct {chest 4.5, side_delts 2, rear_delts 2.5, lower_back 1, abductors 1, quads 1.5, hamstrings 1.5, calves 1}, 49.6 min; readiness at the start includes the add-on dose (quads 0.86, hamstrings 0.85) |

---

## 8. `weekplan` — WeeklyPlanner.v1 (D5, D11, D15, C11, C12, C31, C49, C50, C52, F8, F11)

### 8.1 Purpose

Produce the recommended schedule of the ISO week: the fixed sessions (challenge sessions and test days from the
calendar or from `challenge.plan`, general gym events the user planned, runs), the recommended general sessions on
free days, optional add-on blocks after challenge sessions (D5, C34), and a reason for every day without a session. It
also recommends how many gym visits to make (never more than the user's maximum, default 4, D5) and hands the plan to
the calendar writers (D11). The planner is an exhaustive enumeration with an explicit objective, so its choice is
globally optimal for the model and fully explainable; it is deterministic and fast enough for a phone.

### 8.2 Inputs

| Input | Description |
|---|---|
| `isoWeek`, `now`, `today` | From `core:clock` (Europe/Amsterdam, ISO Monday-Sunday, F18) |
| `anchors` | `calendar.classify` output for the week (§1) |
| `doneSessions` | Logged sessions of the week (past days are locked) |
| `contracts` | One ChallengeContract per active enrollment (§0.5): reserved sessions (calendar-matched or suggested by `challenge.plan`), hard rules, protected windows, add-on slots, session tag rules, owned muscles, coverage |
| `runs` | §6 |
| `profile` | `availability` (default: the gym's opening hours intersected with the usual training windows, weekdays 19:45-21:15 and weekends 09:00-16:00 in the fixture), `max_gym_visits_per_week` (D5, 4), `default_session_min` (F11, 75), `changeoverMin` (F11 revised, C49: 5 min) |
| `targets`, `account`, `readiness doses`, `safety` | §5, §3, §4, §2 |
| `previousPlan` | The last `weekplan.plan` snapshot of this ISO week (stability term) |

### 8.3 Outputs

```
WeekPlan {
  isoWeek, planId, provisional: Bool,              // next week's plan is provisional (D11 shows it; general items only after "Plan it")
  days[]: { date, status ∈ {past, fixed, free, blocked, no_slot},
            items[]: { kind ∈ {challenge_session, test, planned_gym, recommended_general, addon, run, done},
                       slot?/type, label, start, end, estMinutes, direct?, credit?, rirOffset?,
                       writePolicy ∈ {auto, on_plan_it, in_parent_description, never}, source },
            restReason?: {code, params},          // for days without a gym item
            allowedFamilies?: [..] },             // "Free (legs/push only)" style hints
  visits: {done, fixed, recommended, max}, recommendedN,
  projected[g]: {done, committed, plannedGeneral, end, target, targetEffective},
  objective: {J, terms{...}}, band: {jStar, tieBand, candidatesInBand, tieKeyUsed},
  warnings[]                                         // from runs (RUN_ON_REST_DAY), contract conflicts, max visits
}
```

Snapshot `weekplan.plan` (scope `week:<iso_week>`, DATA_MODEL 10.2 keys `days[]: {date, kind, type, est_minutes}` and
`recommended_n`).

### 8.4 Day model

```
for date d in isoWeek (Monday..Sunday):
  if d < today:                        status = past (locked); items = done sessions; restReason = PAST_DAY
  elif gym anchors or done sessions on d (challenge session, test, planned_gym, logged session):
                                       status = fixed            // one gym visit per day (C50)
  elif d is a restDayBefore day of a contract (scope all_gym):   status = blocked; restReason = REST_BEFORE
  else:
     W_d = availability windows of d minus busy anchors (± BUSY_BUFFER_MIN) minus (d == today ? [.., now) : ∅)
     seg = first segment of W_d with length ≥ MIN_SLOT_MIN + changeoverMin
     if none:                          status = no_slot; restReason = NO_SLOT(reason)
     else: status = free; start_d = seg.start (today: max(seg.start, now rounded up to 5 min));
           budget_d = min(default_session_min, seg.length − changeoverMin)          // F11 revised, C49
```

A day with a user-planned gym event is `fixed`; its budget is the event duration − `changeoverMin` (C49), because
calendar gym events are training time (travel happens before them). The day before session A is an ordinary gap day:
a general legs/push session may be planned on it (D15, C50); only the hard 24 h window before A restricts which muscles
it may train.

### 8.5 Hard constraints

Checked before (static) or during (dynamic) the simulation; a plan that violates any is discarded.

| Id | Rule | Source |
|---|---|---|
| H1 | Gap rule: a session whose tags intersect `minGapDays.appliesToTags` must have no other such session within `minGapDays` calendar days. Implemented by passing `forbiddenSessionTags = appliesToTags` to the allocator for every date adjacent to a tagged session (OAP: Wed and Fri this week), plus a pairwise check between general sessions. On such a gap day, pull work is limited to the C50 light-pull filter (grip demand ≤ 1, biceps-group coefficient < 0.5, outside protected windows, only for pull groups the user's goals ask for; ELIG §12.3). | C11, D5, C50 |
| H2 | No gym on a `restDayBefore` date (scope `all_gym`); a run that day only warns. | C11, D5 |
| H3 | Hard protected windows: the allocator excludes their groups, regions and session tags; a window with `scope = all_gym` excludes any overlapping session. | C12 |
| H4 | `maxPerWeek` session-tag caps (OAP: `heavy_unilateral ≤ 3`): general sessions never carry `heavy_unilateral` (ELIG removes such exercises from general blocks), so only challenge sessions count; the planner warns if they exceed the cap. | C11 |
| H5 | Gym visits (done + fixed + recommended general) ≤ `max_gym_visits_per_week`. Add-on blocks are not visits. | D5 |
| H6 | Two sessions (general or add-on) each with ≥ `HEAVY_LOWER_FS` credited FS in quads + hamstrings + glutes must start ≥ `HEAVY_LOWER_MIN_H` apart. | planning lens (expert-opinion) |
| H7 | Safety: `forbidSessionTags` and joint flags (allocator + ELIG) | C33 |
| H8 | General sessions only on `free` days (one gym visit per day); on a challenge day extra work is only possible as an add-on. | C50 |

### 8.6 Enumeration

```
plan(isoWeek):
  free = [d : status_d == free and d ≥ today]
  kMax = min(max_gym_visits − (#done + #fixed gym visits this week), |free|, K_CAP)
  slots = [X ∈ fixed future challenge sessions with addOnSlot.position == end]
  results = []
  for k in 0..kMax:
    for days in combinations(free, k)                      (lexicographic by date):
      for types in product(TYPE_ORDER, repeat = k)          (8 types per day):
        for addons in product([none] ∪ allowedFamilies(X) for X in slots):
           if staticViolation(days, types, addons): continue                // H1 dates, H2, H5, H8
           sim = simulate(fixed ∪ zip(days, types) ∪ addons)               // chronological, below
           if sim.infeasible or dynamicViolation(sim): continue            // H1 pairs, H3, H6
           results += (objective(sim), sim)
  jStar = max J over results
  band  = [r ∈ results : round(r.J, 6) ≥ round(jStar, 6) − TIE_BAND]      // scores differing by ≤ 0.05 tie (C52)
  chosen = min over band by tieKey(r) = (start of the first standalone general session (earlier first; a plan
                                         without one sorts last), #general sessions (fewer first),
                                         −round(J, 6) (higher J first),
                                         dates tuple, TYPE_ORDER index tuple, add-on tuple (none < LEGS < PUSH < CORE))
                                                                     // C52: earlier, fewer, higher J, then lexicographic
```

`simulate` walks the week's sessions in time order (fixed challenge sessions with their materialized doses, done
sessions with their logged doses, runs, and the candidate general sessions and add-ons). For each general session it
computes `opp_g` (standalone general sessions of this plan, from this one on, that include `g`), calls the allocator
(§7) with `Vbefore` = done + committed + projected Vhyp of earlier plan sessions and the readiness at its start, and
adds the projected dose (§7.6). `allowedFamilies(X)` are the families from {LEGS, PUSH, CORE} that have at least one
group reachable through the slot's `allowPatterns` and not in its `forbidGroups`.

**Why a tie band (C52).** Plans whose objective differs by at most `TIE_BAND` (0.05, about 1 % of a typical weekly
`J`) are not meaningfully different given the precision of the constants. They are ordered by the C52 key: the earlier
session first (an opportunity in hand; later free days stay available as buffers if a challenge session has to move),
then fewer sessions, then the higher `J`, then lexicographically (dates, type order of §0.3, add-on family). This is
explainable ("Wednesday and Friday are about equal; today keeps Friday free") and stable. The band is defined relative
to `jStar`, so the choice is deterministic. A plan with a lower `J` can win inside the band only through the
earlier-session or fewer-sessions key; between plans with the same first session and session count the higher `J`
wins (C52 as amended 2026-10-07; this resolved OI-19). The lexicographic key only separates plans with equal rounded `J`.

**Cost.** This user's real week: 2 free days, `kMax = 1`, one add-on slot with 4 options → (1 + 2·8)·4 = 68 plans.
A user without a challenge and 4 visits over 7 free days: Σ_{k≤4} C(7, k)·8^k = 162,681 plans; `K_CAP = 5` gives at
most 850,809. Implementation: depth-first over days with prefix sharing (the simulated state after each session is
reused), memoised allocator calls keyed by `(day, type, hash(Vbefore), hash(readiness))`, off the main thread; the
result is cached until a replan trigger (§8.7). If the plan count before simulation exceeds `MAX_EVALS`, `kMax` is
reduced by one until it fits (deterministic, logged).

**Objective.**

```
J = Σ_g W_g · U_g(V_g^end)                                   // V^end = done + committed + projected general Vhyp
  + FREQ_BONUS · Σ_{g improve} W_g · [sessionsTraining_g ≥ freqTarget_g]
  − PROTECT_W · Σ_{X fixed challenge session} Σ_{penalty windows w of X} marginalDeficit(w, plan)
  − Σ_{general sessions and add-ons} runs.penalty(session)
  − CONSEC_W · max(0, maxConsecutiveGymDays − CONSEC_FREE)
  − SESSION_COST · #general − ADDON_COST · #addons
  − NO_REST_W · [the ISO week has no day without gym]
  − SKIP_W · Σ_{g skip} spill_g / 4                          // indirect FS landing on skip groups
  − LOWG_W · #{general sessions starting with G < G_LOW}
  + STABILITY_W · #{days whose recommended item equals previousPlan's item}

marginalDeficit(w of X) = Σ_{g ∈ w.groups} [max(0, rMin − R_g(t_w | plan)) − max(0, rMin − R_g(t_w | plan − S_w))]
                        + Σ_{k ∈ w.tissueRMin} [same for Rt_k]          // no systemic (G) term in v1 (D15, C50)
   S_w = the general sessions and add-ons starting inside w; t_w = X.start for "before" windows,
   the window end for "after" windows
```

The penalty is **marginal**: deficits that the challenge sessions cause by themselves (for example the elbow channel
before TEST after session A) are not charged to any general candidate, so they do not bias the planner towards rest.
Whole-body (systemic) readiness does not protect challenge sessions in v1 (D15, C50): a legs/push session the day
before A costs nothing beyond its muscle and tissue deficits. The `LOWG_W` term is different: it penalises a general
session that would itself start with low systemic readiness (G < 0.55), whatever comes after it.

### 8.7 Rest-day reasons and replanning

Every day without a gym item gets exactly one reason, chosen in this order: `PAST_DAY`; `REST_BEFORE` (a contract rule,
with the rule id); `NO_SLOT` (busy or outside availability); `MAX_VISITS` (H5 bound in the chosen plan); `RECOVERING`
(every family's best group is below `R_TARGET_*` at the day's start; the reason shows `nextReady` per family);
`NOT_NEEDED` (the chosen plan does not need this day). A free day that only some families can use carries
`allowedFamilies` (for example a gap day next to session A: "Free (legs / push / core; light pull only)", because the
gap rule forbids pull-heavy, grip-heavy and elbow-flexor-heavy work and C50 allows only light pull such as the reverse
pec deck) (UX 10.3).

**Replan triggers.** App open on a new local day; session finish; calendar change (classification fingerprint);
goal, preference or settings change; `SafetyState` fingerprint change; challenge state change (contract hash); params
change at a session start. Past days are always locked. The stability term (`STABILITY_W` per unchanged day) keeps
plans from flickering when inputs change slightly; the number of replans per week that changed a day is logged (plan
churn).

### 8.8 Next week

The planner also runs for the next ISO week with that week's contract (challenge sessions placed by the calendar or
`challenge.plan`) and `Vbefore = 0`, starting from the projected readiness at the end of the current week. Its general
items are provisional (shown in the Week tab, written only on "Plan it").

### 8.9 Calendar hand-off (D11)

| Plan item | Written to the primary calendar | By | Marker |
|---|---|---|---|
| Challenge session (C, A, B) of the current and next ISO week | automatically, when no event is matched to that slot (created) or when a matched event carries the app's marker (updated within the ANDROID §16.7 edit policy, C37) | `challenge.calendar` | `[fitapp:v1 <enrollmentId> <slug>/<slot>/<isoYear>-W<ww>]` (C37) |
| Test day (TEST) | automatically, same rules; when the test is postponed, the app's own event is rewritten to session B (C36) | `challenge.calendar` | same |
| Matched event without marker | never modified; "Adopt" offered (needed once for the events created by hand) | user action | appended on adopt |
| Recommended general session | only when the user taps "Plan it" (one tap) | `:app` calendar writer | `[fitapp:v1 <recommendationId> gen/<isoYear>-W<ww>/<isoDayOfWeek>]` (C37) |
| Add-on block | not a separate event; its summary line is appended to the parent challenge event's description **only if that event carries the app's marker** | `challenge.calendar` | parent's marker |
| Rest days, blocked days, runs | never | — | — |

"Plan it" writes: title `Gym — {label}` (English, D12), location from the gym profile, start = the item's planned
start, duration = estimated minutes + `changeoverMin` (5 min) rounded up to whole 5 minutes, so that the event is
training time and its budget (duration − 5 min, C49) covers the plan; title in English (F25), description = exercise
lines (from SEL-1 for the chosen budget) plus the marker as the last line. A general event written this way becomes a `planned_gym` anchor at the next
classification (method `marker`); if the planner later stops recommending it, the Week tab shows "planned (not
recommended)" with a Why and an option to remove the app's event; the app never moves it on its own. The tap is logged
as `plan_it_tapped` and the write as `calendar_event_written` (DATA_MODEL §9.8.1). In the `sandbox` build flavour
calendar writes are disabled (F27); the planner output is the same, and the write policies are shown but not executed.

### 8.10 Constants

`K_CAP 5`, `MAX_EVALS 1,000,000`, `TIE_BAND 0.05`, `FREQ_BONUS 0.15` (ACSM 2026 ≥ 2 sessions/week per muscle;
Schoenfeld 2016), `PROTECT_W 5` (planning lens), `CONSEC_W 0.5`, `CONSEC_FREE 3` (app-benchmarks A2: at most 3
consecutive training days), `SESSION_COST 0.20` (time cost of a visit, planning lens), `ADDON_COST 0.10` [this doc],
`NO_REST_W 0.3`, `SKIP_W 0.3`, `LOWG_W 0.5`, `G_LOW 0.55`, `STABILITY_W 0.05` [this doc], `HEAVY_LOWER_FS 4`,
`HEAVY_LOWER_MIN_H 40`, `MIN_SLOT_MIN 45`, `BUSY_BUFFER_MIN 30` (challenge lens). All tunable; full rows in §23.9.

### 8.11 Logged data

`weekplan.plan` per replan: inputs hash, every evaluated plan's `J` and terms in compressed form (top 20 by `J` plus the
chosen), the band and tie key, rest reasons, write policies. Plus: which days the user actually trained, deviation
reasons, weekly attainment versus plan, plan churn, "Plan it" taps and calendar writes. Tuner rules T-ACCEPT and
T-WEEK (§20.1).

### 8.12 Tests

| Test | Expected |
|---|---|
| `realWeek41` | §21: chosen plan = Fri "Legs + push + rear delts" (FULL type, J 4.7829) + A legs add-on; band = that plan only (best Wednesday plan 4.6643); Wed rest `MAX_VISITS` with allowedFamilies {LEGS, PUSH, CORE} + light pull; Sat `REST_BEFORE` (OAP restBefore, TEST); recommendedN 4 |
| `bandPrefersEarlier` | Wed and Fri plans within 0.05 (fixture variant with a 19-min A add-on capacity, §21.11) → Wed chosen by the C52 key; tie key logged |
| `bandHigherJ` | two Wednesday plans with one session each within 0.05 (the novice variant of §21.11) → the higher `J` wins (Wed FULL + A push add-on, 5.2453, over Wed PUSH+LEGS + A push add-on, 5.2084), not the earlier type in `TYPE_ORDER` (C52 amended) |
| `bandLexicographic` | two Wednesday plans with one session each and equal rounded `J` → the earlier type in `TYPE_ORDER` wins (C52) |
| `gapRuleTagCap` | a PULL candidate on Wed gets ≤ 3 credited FS for lats + upper_back, no grip-heavy exercise and no exercise with a biceps coefficient ≥ 0.5 (C50) |
| `dayBeforeAAllowed` | a LEGS candidate on the day before A carries no protection or systemic penalty (D15, C50); only the hard window's groups are excluded |
| `maxVisitsBinds` | max 3 → no general session; Wed and Fri `MAX_VISITS` |
| `noChallengeUser` | balanced, 4 visits, all days free → 3-4 sessions spread with ≥ 1 rest day; no two heavy-lower sessions < 40 h apart |
| `addonNotAVisit` | an add-on never changes `visits.recommended` |
| `pastLocked` | replanning on Thu never changes Mon-Wed items |
| `deterministic` | identical inputs → identical `planId` and output bytes |
| `dstWeek44` | week 2026-W43/W44 crossing 2026-10-25: readiness and windows computed on Instants; Sunday has 25 h; plan identical when replayed |
| `evalGuard` | 7 free days, max visits 7 → kMax reduced to fit `MAX_EVALS`, logged |

---

## 9. `today` — TodayRecommender.v1

### 9.1 Purpose

Answer "do I train today, and what?" (push, pull, legs, combinations, the challenge session with or without an add-on,
or rest) with a ranked list and reasons. It uses **the same objective as the week planner**: the value of training
type `C` today is the best weekly objective with today pinned to `C`, compared with the best weekly objective with no
session today. This makes "Legs fits better on Friday" and "rest today" fall out of one model and keeps Today and the
Week tab consistent.

### 9.2 Inputs

Everything the planner reads (§8.2), plus: the time-budget chip (F11: 45 / 60 / 75 / 90+ min, or the calendar budget),
and whether the user is starting now (start = now) or asking ahead (start = today's slot start or calendar event).

### 9.3 Outputs (consumed by `ui.todaycard`, §19.1)

```
TodayOptions {
  date, budgetMin, budgetSource ∈ {calendar, chip, default},
  verdict ∈ {TRAIN, REST_SOFT, REST_HARD},               // C53; decided here, never re-thresholded by ui.todaycard
  planned?: {kind ∈ {challenge, test, general}, slot | type, start, end, anchorId, preview},
  options[]: {optionId, sessionType, label, score, dJ, estMinutes, direct, credit, addon?, reasons[]},
  primaryOptionId,                                        // TRAIN: the option "Start" starts; REST_SOFT: the "Train anyway" default
  restReasons[]: {code, class, params, weight},           // empty for TRAIN (§9.6)
  restAdvice: {flag = verdict ≠ TRAIN, hard = verdict == REST_HARD, reasonCode, params},   // the first rest reason
  excluded[]: {type | family, reasonCode, params},       // e.g. PULL: PROTECT_REST / GAP_DAY; LEGS: HEAVY_LOWER_GAP
  weekPlanId                                              // the plan Today is consistent with
}
```

`score = 0.5 + dJ / UI_SCALE`, on the 1e-4 grid and **not clamped**: 0.5 means "as good as resting today and training
on the plan's other days", above 0.5 is better than resting. The score is unbounded because `ui.todaycard` uses a
relative close-call test (C53, `ui.todaycard.close_rel`); a score clamped at 1.0 would make every strong option look
equally good.

Snapshot `today.options` (scope `day:<date>`, DATA_MODEL 10.2 keys `options[]: {option_id, session_type, score}`,
`rest_advice`, `verdict`, `reasons[]: {code, class}`).

### 9.4 Algorithm

```
today(now, ctx):
  hard = hardRestReason(today)          // contract restDayBefore (C11), safety mode forbidding gym (C33), no slot left today
  X = challenge session or test reserved for today (calendar-matched or suggested)
  if X exists:
     opts = [X + F for F ∈ [none] ∪ allowedFamilies(X.addOnSlot)]       // TEST and B: no add-on
     J_F = best weekly J with today = X + F      // infeasible (for example H6) → excluded with its reason
     score(F) = 0.5 + (J_F − J_none) / UI_SCALE
     primary = X + (the add-on of the chosen week plan, or none)
     verdict = (a safety or challenge mode forbids X today) ? REST_HARD : TRAIN
     return planned = X, options = opts ranked by (J desc, add-on order none < LEGS < PUSH < CORE)
  E = planned_gym event today (user's own calendar entry, or the app's marked "Plan it" event)
  start = E?.start ?: (now ≥ todaySlot.start ? now : todaySlot.start);  budget = chip ?: E?.budget ?: todaySlot.budget
  J_REST = best weekly J with no general session today
  for C in TYPE_ORDER:
     J_C = best weekly J with today = C at (start, budget)       // allocator infeasible or H1-H8 → excluded with its reason
     dJ_C = J_C − J_REST;  score_C = 0.5 + dJ_C / UI_SCALE
  chosen = weekplan.choose(all plans)                            // §8.6 band + tie key (C52)
  if hard:
     verdict = REST_HARD; restReasons = [REST_BEFORE | SAFETY_JOINT | SAFETY_PROTOCOL | NO_SLOT]
  elif E exists:                                                 // the user's plan is a hard user constraint (§0.1)
     primary = (E.type != AUTO) ? E.type : the type of the chosen plan's session today
     verdict = (every type is infeasible at E) ? REST_SOFT : TRAIN; restReasons = [RECOVERING | NOT_NEEDED] if REST_SOFT
  elif chosen has a general session today:
     verdict = TRAIN; primary = its type
  else:
     verdict = REST_SOFT; primary = argmax_C J_C (ties by TYPE_ORDER)                      // the "Train anyway" default
     restReasons = [BETTER_OTHER_DAY {type, day} (the chosen plan trains on day d instead) | MAX_VISITS | RECOVERING |
                    NOT_NEEDED,  NEXT_FIXED {slot, at} of the next fixed gym item]
  restAdvice = {flag = verdict ≠ TRAIN, hard = verdict == REST_HARD, first of restReasons}
  options sorted by (score desc, TYPE_ORDER index); the primary option is listed first
```

The primary option is the engine's choice, not necessarily the best-scored option: inside the tie band the C52 key can
choose a plan with a slightly lower `J` through its earlier-session or fewer-sessions keys (between plans with the same
first session and session count the higher `J` wins, so the §21.11 novice case now picks FULL), and a user-planned event
fixes the type. `ui.todaycard` uses
`primaryOptionId` and never re-sorts the options to find a primary (§19.1). `today` decides the verdict; `ui.todaycard`
only maps it to a card state (PLANNED, OPTIONAL clear or close, REST soft or hard, ...) and applies no thresholds of its
own except the display-only close-call test (C53).

### 9.5 Labels

From the allocation (§7.5): "Legs + push", "Legs + push + rear delts", "Push + rear delts", "Core", "Session A + legs
add-on". A type whose allocation is identical to a smaller type's (for example FULL when every pull group is covered
or forbidden) is shown once, under the smaller type's label (dedupe by `direct`, keeping the earlier type in
TYPE_ORDER).

### 9.6 Reasons (F19)

Each option carries reasons with a precedence class (§0.6) and a signed weight. `today` attaches at most one reason of
each kind below to an option (the one with the largest `|weight|` within the kind, ties by the order of the codes in
the table), so that the chips mix why to train
with what limits the session. `ui.todaycard` then orders them by (precedence class, −|weight|, code), keeps one chip per
code and subject, and shows at most `MAX_CHIPS` (§19.1; UX §19.1 adds only the templates).

| Kind | Codes | Weight |
|---|---|---|
| volume | `VOLUME_DEFICIT` for the group with the largest `W_g·ΔU_g` (params `group, done, after, target`) | `W_g·ΔU_g` |
| transform | `DELOAD_WEEK`, `REENTRY` | `TRANSFORM_CHIP_WEIGHT` (0.5) |
| constraint | `PROTECT_REST`, `GAP_DAY`, `RUN_NEAR`, `BETTER_OTHER_DAY`, `MAX_VISITS`, `LOW_SYSTEMIC`, `RECOVERING`, `HEAVY_LOWER_GAP` | the penalty or `|dJ|` in J, or `CONSTRAINT_CHIP_WEIGHT` (0.4) for hard exclusions |
| context | `PLANNED_CAL`, `CHALLENGE_DUE`, `NEXT_FIXED`, `RECOVERED`, `HOURS_SINCE`, `ADDON`, `BUDGET_FIT` | `CONTEXT_CHIP_WEIGHT` (0.1) |

A rest verdict carries `restReasons` built the same way: the rest reason chosen in §9.4 (constraint kind, or the
SAFETY / CHALLENGE code of a hard rest) plus `NEXT_FIXED` for the next fixed gym item (context kind).

`excluded[]` explains families that are not offered or are restricted today, for example "Pull: lats, biceps and
forearms are protected — Session A tomorrow 19:45" (`PROTECT_REST`, hard window from today 19:45), "between C and A:
light pull only, no grip or elbow-flexor work" (`GAP_DAY`, C50) and "Legs: heavy legs 23 h ago (A + legs add-on)"
(`HEAVY_LOWER_GAP`, H6).

### 9.7 Constants

`UI_SCALE 2.0` (a `dJ` of +1 maps to a score of 1.0; 0.5 = "as good as resting today and training later"; the score is
not clamped, §9.3), `TRANSFORM_CHIP_WEIGHT 0.5`, `CONSTRAINT_CHIP_WEIGHT 0.4`, `CONTEXT_CHIP_WEIGHT 0.1`,
`MAX_BUDGET_MIN 180` (the "90+" chip). Full rows in §23.10.

### 9.8 Logged data

`today.options` on every Today render with changed inputs; `today_card_shown` and `today_action {action, rank,
latency_ms}` (UX); the option the user started versus the primary; sessions started from a non-primary option with the
reason codes that preceded them; REST overrides ("train anyway") followed by poor sRPE or pain. Tuner rule T-ACCEPT.

### 9.9 Tests

| Test | Expected |
|---|---|
| `wed20261007` | §21.9: verdict `REST_SOFT`; restReasons [`BETTER_OTHER_DAY` {type "Legs + push + rear delts", day Fri}, `NEXT_FIXED` {A, Thu 19:45}]; "Train anyway" options FULL "Legs + push + rear delts" (score 0.441, primary), PUSH+LEGS "Legs + push" (0.438), PULL+LEGS "Legs + rear delts" (0.274); excluded lats/biceps/forearms `PROTECT_REST`, upper back `GAP_DAY` (C50); CORE infeasible |
| `thu20261008` | planned challenge A, verdict `TRAIN`; options A + legs add-on (primary, score 0.839), A + push add-on (0.703), A only (0.500); CORE add-on infeasible (abs met) |
| `fri20261009` | Thu logged as planned, no "Plan it" event: verdict `TRAIN`; primary FULL "Legs + push + rear delts" (1.840), then UPPER "Push + rear delts" (1.599), PUSH (1.469), PULL "Rear delts" (0.812); LEGS, PUSH+LEGS and PULL+LEGS excluded `HEAVY_LOWER_GAP` (the Thursday legs add-on 23 h earlier, H6) |
| `sat20261010` | verdict `REST_HARD`, `REST_BEFORE` (TEST Sun 13:00); every option excluded |
| `sun20261011` | planned TEST, verdict `TRAIN`, no add-on options |
| `capacity19Variant` | F-W41 with the A add-on capacity at 19 min (the pre-C49 value): the chosen plan trains Wednesday (Wed FULL + A push add-on, J 4.6214, inside the band of J* 4.6659, earlier session first, C52) → verdict `TRAIN`; FULL 0.478, PUSH+LEGS 0.471 |
| `budgetChip45` | "Train anyway" on Wed with the 45-min chip → the same type, fewer sets (time fit), `TIME_SHORT` in the trace |
| `userPlannedEventWins` | calendar event "Gym - legs" today → primary LEGS even if FULL scores higher |

---

## 10. `catalog` — CAT-1 catalog model, coefficients and validator (C1, C9, C10, D12, F7)

### 10.1 Purpose

Store every exercise with deterministic attributes and region-level stimulus coefficients, so that volume accounting
(SetCredit), selection, substitution, rotation and challenge coverage all read the same numbers. The catalog is a
hand-curated, versioned seed (`catalog_version`, semver) of about 150 Basic-Fit-realistic exercises with optional
cross-references to free-exercise-db (Unlicense) for instruction text; it is validated in CI and on every user edit.
Challenge exercises are catalog entries too (C10): either `catalogRef` rows or challenge-local rows that pass the same
validator.

### 10.2 Taxonomy (C9)

Two levels. **Groups** carry goals, targets, volume and readiness; **regions** carry only variety bonuses, challenge
mapping and substitution similarity. The tables are `fitness.muscle_groups` and `fitness.muscle_regions` (DATA_MODEL
9.1.1-9.1.2); validators reject unknown keys and rewrite aliases to canonical ids.

| Group (family) | Regions | Region aliases accepted from drafts |
|---|---|---|
| chest (push) | chest_clavicular, chest_sternal | |
| front_delts (push) | delt_anterior | front_delt |
| side_delts (push) | delt_lateral | side_delt |
| rear_delts (pull) | delt_posterior | rear_delts |
| lats (pull) | lats | |
| upper_back (pull) | traps_mid, traps_lower, rhomboids | mid_back, mid_traps, lower_traps |
| upper_traps (pull) | traps_upper | |
| biceps (pull; "elbow flexors") | biceps_brachii, brachialis, brachioradialis | biceps, elbow_flexors |
| triceps (push) | triceps_long, triceps_lat_med | |
| forearms (pull) | wrist_flexors_grip, wrist_extensors, pronators_supinators | forearm_flexors, grip |
| abs (core) | rectus_abdominis, obliques, anti_rotation | core_antirotation |
| lower_back (core) | erectors | |
| glutes (legs) | glute_max | |
| abductors (legs) | glute_med_min | |
| adductors (legs) | adductors | |
| quads (legs) | vasti, rectus_femoris | |
| hamstrings (legs) | hams_biarticular, bf_short_head | |
| calves (legs) | gastrocnemius, soleus | |
| (prehab only, no group) | rotator_cuff_er, serratus_anterior, tibialis_anterior | rotator_cuff_ER |
| neck (other, inactive) | neck | |

That is 31 grouped regions plus 3 prehab-only regions (34 active) and the optional, inactive `neck`, which is the count
C44 fixes (it supersedes C9's "~32"); `obliques` is a region of the `abs` group (C44). The muscle-map SVG of the UI is
drawn from the group list (C9).

### 10.3 Attributes

Stored per exercise (DATA_MODEL 9.2.1): `slug`, `name_en`, `name_nl` (D12: stored in English and Dutch; required for
seed rows, optional for custom rows), `aliases`, `pattern` (E36, 37 values since D54), `family` (substitution family, §10.6),
`equipment[]` (E45), `requires_station[]` (F7; keys of `equipment_types`), `load_mode` and `allowed_load_modes` (E3;
one exercise can hold assisted / bodyweight / weighted modes on one load scale), `bw_fraction` (C1), `implement_base_kg`,
`laterality` (E37), `measure` (E38), `contraction` (E39), `hold_class` (E55, C43: `grip`, `elbow`, `shoulder`, `trunk`,
`knee` or `ankle`; set exactly for isometric and hang exercises, it selects the HOLD-1 exponent, for example `shoulder`
for the OAP straight-arm hold and `grip` for dead hangs), `ecc_emphasis`, `mechanics`, `curve_class` (E40, C3 priors),
`rest_class` (E41, C18), `safety_class` (E42), `grip` (E43), `stretch_emphasis` 0-2, `stability` 1-3, `skill` 1-3,
`axial` 0-3, `systemic` 1-3, `sfr` 1-5, `setup_s`, `grip_demand` 0-3, joint stress `js_elbow_medial, js_elbow_lateral,
js_shoulder_ant, js_shoulder_overhead, js_wrist, js_lumbar, js_knee, js_hip` 0-3, `tags[]`, `external_refs {fedb_id}`,
`evidence_note`. Region coefficients live in `exercise_stimulus` (absent row = 0).

Defaults when a seed author leaves an attribute open (expert-opinion, from the exercise-selection lens): `sfr` machine
or cable isolation 5, machine compound 4-5, dumbbell compound 4, barbell compound 3, heavy hinge 2, unilateral free
weight 3; `axial` machines and supported positions 0, standing dumbbell or hip thrust 1, RDL or barbell row 2, back
squat, deadlift, standing overhead press 3; `setup_s` pin machine 30-45, plate-loaded machine 90, barbell in a rack 150,
Smith 90-120, dumbbells 30-60, cable attachment change 45, dip belt + 60. All `setup_s` values are calibrated per user
by TIME-1 (§17).

### 10.4 Coefficient levels, group coefficient and derived tags

```
coefficient levels L = {1.0 prime mover through the range and likely set limiter;
                        0.75 strong synergist (only with a cited evidence override);
                        0.5 dynamic synergist (fractional set, Pelland 2026);
                        0.25 minor dynamic or isometric stabiliser (fatigue only, §3);
                        0 (no row)}
groupCoeff(e, g) = max over regions r of g of coeff(e, r)      // max, not sum: no double counting
```

Derived tags (computed at load, never stored; the effective tag set is `stored ∪ derived`):

| Tag | Rule |
|---|---|
| `grip_heavy` | `grip_demand ≥ 2` |
| `elbow_flexor_heavy` | `groupCoeff(biceps) = 1.0` or `js_elbow_medial ≥ 2` |
| `pull_heavy` | `max(groupCoeff(lats), groupCoeff(upper_back)) ≥ 0.75` and `contraction ∈ {dynamic, eccentric_only}` |
| `supinated_curl` | `pattern = elbow_flex` and `grip = supinated` |
| `axial_heavy` | `axial = 3` |
| `overhead_press_heavy` | `pattern = vert_push` and `mechanics = compound` and `js_shoulder_overhead ≥ 2` |
| `heavy_unilateral` | stored only (challenge-local exercises such as the assisted one-arm pull-up) |

Session tags are computed from sets with the contract's `sessionTagRules` (§0.3), not from these exercise tags; the
exercise tags are used by ELIG (exclusion when a tag is forbidden) and by the allocator's group availability.

### 10.5 Evidence overrides (seed notes; Vigotsky 2022: EMG is not used)

| Exercise class | Coefficients | Evidence |
|---|---|---|
| Squat, leg press, hack squat | vasti 1.0, rectus_femoris 0.25, hamstrings 0, glute_max 0.5 (deep barbell squat 0.75), adductors 0.5 (deep barbell squat 0.75) | Kubo 2019 (moderate) |
| Hip thrust | glute_max 1.0, vasti 0.25, hams_biarticular 0.25 | Plotkin 2023 (moderate) |
| Leg extension | vasti 1.0, rectus_femoris 1.0 | Ema 2013; Larsen 2024 (moderate) |
| Seated leg curl / lying leg curl | hams_biarticular 1.0, bf_short_head 1.0; stretch 2 / 1 | Maeo 2021 (moderate) |
| RDL | hams_biarticular 1.0, glute_max 0.5, adductors 0.5, erectors 0.5, wrist_flexors_grip 0.5; stretch 2 | expert-opinion |
| Rows | traps_mid 1.0, rhomboids 1.0, lats 0.5, biceps_brachii 0.5, brachialis 0.5, delt_posterior 0.5, wrist_flexors_grip 0.25-0.5 | Mannarino 2021 (rows ≈ 0.47 of curls for biceps, moderate) |
| Pulldowns and pull-ups | lats 1.0, biceps_brachii 0.5, brachialis 0.5, brachioradialis 0.25-0.5, wrist_flexors_grip 0.25-0.5, traps_lower 0.25, delt_posterior 0.25 | Mannarino 2021; Gentil 2015 |
| Presses | chest 1.0 (flat: sternal 1.0, clavicular 0.75; incline: clavicular 1.0, sternal 0.75), delt_anterior 0.5, triceps 0.5 | Chaves 2020 (moderate) |
| Overhead triceps extension / pushdown | triceps_long 1.0, triceps_lat_med 0.75, stretch 2 / triceps_lat_med 1.0, triceps_long 0.75 | Maeo 2023 (moderate) |
| Standing / seated calf raise | gastrocnemius 1.0, soleus 1.0 / soleus 1.0, gastrocnemius 0.25 | Kinoshita 2023 (moderate) |
| Long-length emphasis | `stretch_emphasis` 0-2; +0.05 quality per level in SEL-1 | Maeo 2021/2023, Pedrosa 2022, Kassiano 2023, Wolf 2023/2025 (moderate) |
| Bodyweight fractions | pull-up / chin-up / one-arm / dip family 1.00; push-up 0.64; knee push-up 0.49; feet raised 30 cm 0.70, 61 cm 0.74; hands raised 30 cm 0.55, 61 cm 0.41; inverted row 0.60 | C1 (Ebben 2011 moderate; inverted row weak) |

### 10.6 Substitution families

`family` groups patterns for SUB-1's pattern-match term (§15): `press` = {horiz_push, incline_push, dip}; `squat` =
{squat, squat_machine, lunge}; `hinge` = {hinge, hip_ext}; `vpull` = {vert_pull, straight_arm_pull}; `hpull` =
{horiz_pull, rear_delt_fly}; `raise` = {lateral_raise}; `fly` = {chest_fly}; `vpush` = {vert_push}; `curl` =
{elbow_flex}; `ext` = {elbow_ext, elbow_ext_overhead}; `legiso` = {knee_ext, knee_flex}; `hip` = {hip_abd, hip_add};
`calf` = {calf}; `trap` = {shrug}; `forearm` = {wrist_flex, wrist_ext, forearm_rot, grip_hold, carry}; `core` =
{core_flex, core_anti_ext, core_anti_rot, core_rotation}; `skill` = {hang, skill_static}; `cardio` = {conditioning}.

### 10.7 Validator (CI on the seed; on save for custom and challenge-local rows)

| Code | Rule |
|---|---|
| V1 | Every coefficient ∈ {0.25, 0.5, 0.75, 1.0} (0 = no row) (C10) |
| V2 | Every region key exists in `muscle_regions` (aliases rewritten first); unknown keys are errors, never silently dropped (C9) |
| V3 | At least one region ≥ 0.75 unless the exercise is tagged `prehab`, `skill`, `hang` or has pattern `conditioning` |
| V4 | A 0.75 coefficient needs a non-empty `evidence_note` |
| V5 | `pattern` ∈ E36; `family` matches §10.6; `load_mode ∈ allowed_load_modes`; `bw_fraction > 0` iff a bodyweight mode is allowed (C1) |
| V6 | `requires_station` keys exist in `equipment_types` (F7) |
| V7 | `contraction = dynamic` ⇒ `curve_class` set (C3); holds/hangs ⇒ `measure = duration` and `hold_class` set, other contractions ⇒ no `hold_class` (C43, DATA_MODEL `exercises` CHECK) |
| V8 | Seed rows have `name_en` and `name_nl` (D12) |
| V9 (catalog-level) | For every group, with the Basic-Fit default inventory (F7): at least 3 exercises, at least one with group coefficient 1.0, at least 2 equipment classes |
| V10 (catalog-level) | Every exercise not tagged `skill`/`challenge`/`prehab` has at least one substitute with similarity ≥ 0.6 (§15) |
| V11 | Derived tags are consistent with stored reserved tags (a stored `grip_heavy` on a `grip_demand < 2` exercise is an error) |
| V12 | Challenge-local rows carry the `challenge` tag and are excluded from general blocks (ELIG) |

The CI test writes a validation report (counts per group, equipment classes, substitute similarity matrix summary).

### 10.8 Seed strategy and planned seed

Hand-curate about 150 exercises in `algo/src/main/resources/fitness/catalog/catalog-1.0.0.json` (bundled data lives under
`algo/src/main/resources/fitness/{params,catalog,presets,challenges}/`, ANDROID 4, C40, C68; upserted to `fitness.exercises`
and `fitness.exercise_stimulus` by migration with `catalog_version`). Do not import any dataset wholesale:
free-exercise-db (876 entries, Unlicense) is used only for `external_refs.fedb_id` and optional instruction text, never
for muscles (its muscle labels are coarse and partly wrong, for example leg press with hamstrings); wger is CC-BY-SA with
poor labels; ExerciseDB/AscendAPI has unclear terms (exercise-selection lens, engineering-fact). Images are not bundled
(text cues plus the muscle-map SVG).

Planned seed (148 rows). Stimulus shown as `region coefficient`; `fedb` is the free-exercise-db id ("—" when none
matches closely). Attributes not shown follow §10.3 defaults.

**Quads and squat patterns (14)**

| slug | EN / NL | fedb | pattern | stimulus |
|---|---|---|---|---|
| leg_press_45 | 45° leg press / Beenpers 45° | Leg_Press | squat_machine | vasti 1, rectus_femoris .25, glute_max .5, adductors .5 |
| leg_press_seated | Seated leg press / Zittende beenpers | Narrow_Stance_Leg_Press | squat_machine | as leg_press_45 |
| hack_squat_machine | Hack squat / Hack squat | Hack_Squat | squat_machine | as leg_press_45 |
| smith_squat | Smith machine squat / Squat in Smith-machine | Smith_Machine_Squat | squat | vasti 1, rectus_femoris .25, glute_max .5, adductors .5, erectors .25 |
| back_squat_barbell | Back squat / Squat met halter | Barbell_Squat | squat | vasti 1, rectus_femoris .25, glute_max .75, adductors .75, erectors .25 (Kubo 2019) |
| front_squat_barbell | Front squat / Front squat | Front_Barbell_Squat | squat | vasti 1, rectus_femoris .25, glute_max .5, adductors .5, erectors .25 |
| goblet_squat_db | Goblet squat / Goblet squat | Goblet_Squat | squat | vasti 1, glute_max .5, adductors .5 |
| bulgarian_split_squat_db | Bulgarian split squat / Bulgaarse split squat | Split_Squat_with_Dumbbells | lunge | vasti 1, glute_max .75, adductors .5 (evidence note: long-length glute work) |
| walking_lunge_db | Walking lunge / Lopende lunge | Dumbbell_Lunges | lunge | vasti 1, glute_max .75, adductors .5 |
| reverse_lunge_db | Reverse lunge / Achterwaartse lunge | Dumbbell_Rear_Lunge | lunge | vasti 1, glute_max .75, adductors .5 |
| step_up_db | Dumbbell step-up / Step-up met dumbbells | — | lunge | vasti 1, glute_max .75 |
| smith_split_squat | Smith split squat / Split squat in Smith | Smith_Single-Leg_Split_Squat | lunge | vasti 1, glute_max .75, adductors .5 |
| leg_extension | Leg extension / Beenstrekken | Leg_Extensions | knee_ext | vasti 1, rectus_femoris 1 |
| single_leg_extension | Single-leg extension / Eenbenig beenstrekken | — | knee_ext | vasti 1, rectus_femoris 1 |

**Hinge and hamstrings (10)**

| slug | EN / NL | fedb | pattern | stimulus |
|---|---|---|---|---|
| rdl_barbell | Romanian deadlift / Roemeense deadlift | Romanian_Deadlift | hinge | hams_biarticular 1, glute_max .5, adductors .5, erectors .5, wrist_flexors_grip .5 |
| rdl_db | Dumbbell Romanian deadlift / Roemeense deadlift met dumbbells | Stiff-Legged_Dumbbell_Deadlift | hinge | as rdl_barbell |
| deadlift_barbell | Deadlift / Deadlift | Barbell_Deadlift | hinge | glute_max .75, hams_biarticular .5, vasti .5, erectors .75, adductors .5, wrist_flexors_grip .5, traps_upper .25 |
| single_leg_rdl_db | Single-leg RDL / Eenbenige RDL | — | hinge | hams_biarticular 1, glute_max .75, glute_med_min .25, anti_rotation .25 |
| seated_leg_curl | Seated leg curl / Zittende leg curl | Seated_Leg_Curl | knee_flex | hams_biarticular 1, bf_short_head 1 (stretch 2) |
| lying_leg_curl | Lying leg curl / Liggende leg curl | Lying_Leg_Curls | knee_flex | hams_biarticular 1, bf_short_head 1 (stretch 1) |
| glute_ham_raise | Glute-ham raise / Glute-ham raise | Glute_Ham_Raise | knee_flex | hams_biarticular 1, bf_short_head 1, glute_max .25 (ecc_emphasis) |
| back_extension_45 | 45° back extension / Rugstrekken 45° | Hyperextensions_Back_Extensions | hip_ext | erectors .75, glute_max .5, hams_biarticular .5 |
| cable_pull_through | Cable pull-through / Pull-through aan de kabel | Pull_Through | hip_ext | glute_max 1, hams_biarticular .5, erectors .25 |
| good_morning_smith | Good morning (Smith) / Good morning (Smith) | Good_Morning | hinge | hams_biarticular 1, erectors .75, glute_max .5 |

**Glutes, abductors, adductors (9)**

| slug | EN / NL | fedb | pattern | stimulus |
|---|---|---|---|---|
| hip_thrust_smith | Hip thrust (Smith) / Hip thrust (Smith) | Barbell_Hip_Thrust | hip_ext | glute_max 1, vasti .25, hams_biarticular .25 |
| hip_thrust_barbell | Hip thrust (barbell) / Hip thrust (halter) | Barbell_Hip_Thrust | hip_ext | as hip_thrust_smith |
| hip_thrust_machine | Hip thrust machine / Hip thrust machine | — | hip_ext | as hip_thrust_smith |
| glute_bridge_barbell | Glute bridge / Glute bridge | Barbell_Glute_Bridge | hip_ext | glute_max 1, hams_biarticular .25 |
| cable_kickback | Cable glute kickback / Kickback aan de kabel | One-Legged_Cable_Kickback | hip_ext | glute_max 1 |
| hip_abduction_machine | Hip abduction machine / Abductiemachine | Thigh_Abductor | hip_abd | glute_med_min 1, glute_max .25 |
| cable_hip_abduction | Cable hip abduction / Heupabductie aan de kabel | — | hip_abd | glute_med_min 1 |
| hip_adduction_machine | Hip adduction machine / Adductiemachine | Thigh_Adductor | hip_add | adductors 1 |
| cable_hip_adduction | Cable hip adduction / Heupadductie aan de kabel | Cable_Hip_Adduction | hip_add | adductors 1 |

**Calves (5)**

| slug | EN / NL | fedb | pattern | stimulus |
|---|---|---|---|---|
| standing_calf_raise_machine | Standing calf raise / Staande kuitheffing | Standing_Calf_Raises | calf | gastrocnemius 1, soleus 1 |
| smith_calf_raise | Smith calf raise / Kuitheffing in Smith | Smith_Machine_Calf_Raise | calf | gastrocnemius 1, soleus 1 |
| leg_press_calf_raise | Calf press on the leg press / Kuitpers op de beenpers | Calf_Press_On_The_Leg_Press_Machine | calf | gastrocnemius 1, soleus 1 |
| seated_calf_raise | Seated calf raise / Zittende kuitheffing | Seated_Calf_Raise | calf | soleus 1, gastrocnemius .25 |
| single_leg_calf_raise_db | Single-leg calf raise / Eenbenige kuitheffing | Calf_Raise_On_A_Dumbbell | calf | gastrocnemius 1, soleus 1 |

**Chest (15)**

| slug | EN / NL | fedb | pattern | stimulus |
|---|---|---|---|---|
| bench_press_barbell | Bench press / Bankdrukken | Barbell_Bench_Press_-_Medium_Grip | horiz_push | chest_sternal 1, chest_clavicular .75, delt_anterior .5, triceps_lat_med .5, triceps_long .25 |
| incline_bench_barbell | Incline bench press / Schuin bankdrukken | Barbell_Incline_Bench_Press_-_Medium_Grip | incline_push | chest_clavicular 1, chest_sternal .75, delt_anterior .5, triceps_lat_med .5 |
| db_bench_press | Dumbbell bench press / Bankdrukken met dumbbells | Dumbbell_Bench_Press | horiz_push | as bench_press_barbell |
| incline_db_press | Incline dumbbell press / Schuin drukken met dumbbells | Incline_Dumbbell_Press | incline_push | as incline_bench_barbell |
| machine_chest_press | Chest press machine / Chest press (machine) | Leverage_Chest_Press | horiz_push | as bench_press_barbell |
| incline_machine_press | Incline chest press machine / Schuine chest press | Leverage_Incline_Chest_Press | incline_push | as incline_bench_barbell |
| smith_bench_press | Smith bench press / Bankdrukken in Smith | Smith_Machine_Bench_Press | horiz_push | as bench_press_barbell |
| smith_incline_press | Smith incline press / Schuin drukken in Smith | Smith_Machine_Incline_Bench_Press | incline_push | as incline_bench_barbell |
| pec_deck | Pec deck / Vlindermachine | Butterfly | chest_fly | chest_sternal 1, chest_clavicular .75, delt_anterior .25 |
| cable_fly_high_low | Cable fly, high to low / Cable fly van hoog naar laag | Cable_Crossover | chest_fly | chest_sternal 1, chest_clavicular .5, delt_anterior .25 |
| cable_fly_low_high | Cable fly, low to high / Cable fly van laag naar hoog | Low_Cable_Crossover | chest_fly | chest_clavicular 1, chest_sternal .5, delt_anterior .25 |
| db_fly | Dumbbell fly / Dumbbell fly | Dumbbell_Flyes | chest_fly | chest_sternal 1, chest_clavicular .75, delt_anterior .25 (stretch 2) |
| push_up | Push-up / Opdrukken | Pushups | horiz_push | chest_sternal 1, chest_clavicular .75, delt_anterior .5, triceps_lat_med .5, anti_rotation .25 (bw 0.64) |
| push_up_feet_raised | Feet-elevated push-up / Opdrukken met voeten verhoogd | Push-Ups_With_Feet_Elevated | incline_push | chest_clavicular 1, chest_sternal .75, delt_anterior .5, triceps_lat_med .5 (bw 0.70 at 30 cm, 0.74 at 61 cm) |
| dip_chest | Dip, chest version / Dips voor de borst | Dips_-_Chest_Version | dip | chest_sternal 1, triceps_lat_med .75, delt_anterior .5 (bw 1.00) |

**Shoulders (14)**

| slug | EN / NL | fedb | pattern | stimulus |
|---|---|---|---|---|
| machine_shoulder_press | Shoulder press machine / Schouderpers (machine) | Machine_Shoulder_Military_Press | vert_push | delt_anterior 1, delt_lateral .5, triceps_lat_med .5 |
| db_shoulder_press | Seated dumbbell shoulder press / Schouderdrukken met dumbbells | Dumbbell_Shoulder_Press | vert_push | as machine_shoulder_press |
| ohp_barbell | Standing overhead press / Staand drukken (halter) | Standing_Military_Press | vert_push | delt_anterior 1, delt_lateral .5, triceps_lat_med .5, erectors .25 (axial 3) |
| smith_shoulder_press | Smith shoulder press / Schouderdrukken in Smith | Smith_Machine_Overhead_Shoulder_Press | vert_push | as machine_shoulder_press |
| landmine_press | Landmine press / Landmine press | — | vert_push | delt_anterior 1, chest_clavicular .5, triceps_lat_med .5, serratus_anterior .25 |
| db_lateral_raise | Dumbbell lateral raise / Zijwaarts heffen met dumbbells | Side_Lateral_Raise | lateral_raise | delt_lateral 1 |
| cable_lateral_raise | One-arm cable lateral raise / Zijwaarts heffen aan de kabel | Cable_Seated_Lateral_Raise | lateral_raise | delt_lateral 1 (stretch 1) |
| machine_lateral_raise | Lateral raise machine / Zijwaarts heffen (machine) | — | lateral_raise | delt_lateral 1 |
| lean_away_lateral_raise | Lean-away lateral raise / Zijwaarts heffen, wegleunend | One-Arm_Incline_Lateral_Raise | lateral_raise | delt_lateral 1 (stretch 2) |
| cable_upright_row_wide | Wide cable upright row / Upright row breed (kabel) | Standing_Dumbbell_Upright_Row | lateral_raise | delt_lateral 1, traps_upper .5 (js_shoulder_overhead 2) |
| reverse_pec_deck | Reverse pec deck / Omgekeerde vlindermachine | Reverse_Machine_Flyes | rear_delt_fly | delt_posterior 1, traps_mid .5, rhomboids .5 |
| db_rear_delt_fly | Bent-over rear delt fly / Voorovergebogen achterwaarts heffen | Seated_Bent-Over_Rear_Delt_Raise | rear_delt_fly | delt_posterior 1, traps_mid .5, rhomboids .25 |
| cable_rear_delt_fly | Cable rear delt fly / Achterste schouder fly aan de kabel | Cable_Rear_Delt_Fly | rear_delt_fly | delt_posterior 1, traps_mid .5 |
| face_pull_cable | Face pull / Face pull | Face_Pull | rear_delt_fly | delt_posterior 1, traps_mid .5, rotator_cuff_er .5 (also prehab; OAP warm-up) |

**Back (24)**

| slug | EN / NL | fedb | pattern | stimulus |
|---|---|---|---|---|
| lat_pulldown_wide | Lat pulldown, wide / Lat pulldown breed | Wide-Grip_Lat_Pulldown | vert_pull | lats 1, biceps_brachii .5, brachialis .5, brachioradialis .25, delt_posterior .25, traps_lower .25, wrist_flexors_grip .25 |
| lat_pulldown_neutral | Lat pulldown, neutral close grip / Lat pulldown neutraal smal | V-Bar_Pulldown | vert_pull | lats 1, biceps_brachii .5, brachialis .5, brachioradialis .5, traps_lower .25, wrist_flexors_grip .25 |
| lat_pulldown_supinated | Lat pulldown, underhand / Lat pulldown onderhands | Underhand_Cable_Pulldowns | vert_pull | lats 1, biceps_brachii .5, brachialis .5, traps_lower .25, wrist_flexors_grip .25 |
| single_arm_pulldown_cable | Single-arm cable pulldown / Eenarmige pulldown | One_Arm_Lat_Pulldown | vert_pull | lats 1, biceps_brachii .5, brachialis .5 |
| pullup_bw | Pull-up / Optrekken bovenhands | Pullups | vert_pull | lats 1, biceps_brachii .5, brachialis .5, brachioradialis .5, wrist_flexors_grip .5, traps_lower .25, delt_posterior .25 (bw 1.00) |
| chinup_bw | Chin-up / Optrekken onderhands | Chin-Up | vert_pull | lats 1, biceps_brachii .5, brachialis .5, brachioradialis .25, wrist_flexors_grip .5, traps_lower .25, delt_posterior .25 (bw 1.00) |
| neutral_grip_pullup_bw | Neutral-grip pull-up / Optrekken neutraal | V-Bar_Pullup | vert_pull | as pullup_bw |
| pullup_weighted | Weighted pull-up / Gewogen optrekken | Weighted_Pull_Ups | vert_pull | as pullup_bw (bodyweight_plus_external) |
| chinup_weighted | Weighted chin-up / Gewogen chin-up | Chin-Up | vert_pull | as chinup_bw (OAP `weighted_pullup` for grip = supinated) |
| neutral_grip_pullup_weighted | Weighted neutral-grip pull-up / Gewogen optrekken neutraal | V-Bar_Pullup | vert_pull | as pullup_bw |
| assisted_pullup_machine_2arm | Assisted pull-up machine / Optrekken met assistentie (machine) | Band_Assisted_Pull-Up | vert_pull | as pullup_bw (assisted_machine, C2) |
| seated_cable_row_neutral | Seated cable row, neutral / Roeien aan de kabel neutraal | Seated_Cable_Rows | horiz_pull | traps_mid 1, rhomboids 1, lats .5, biceps_brachii .5, brachialis .5, delt_posterior .5, wrist_flexors_grip .25 |
| seated_cable_row_wide_pronated | Seated cable row, wide pronated / Roeien aan de kabel breed bovenhands | — | horiz_pull | as seated_cable_row_neutral (OAP B option) |
| chest_supported_row_pronated | Chest-supported row, pronated / Roeien met borststeun bovenhands | Leverage_High_Row | horiz_pull | as seated_cable_row_neutral, wrist_flexors_grip .25 (OAP B option) |
| barbell_row_pronated | Barbell row / Voorovergebogen roeien (halter) | Bent_Over_Barbell_Row | horiz_pull | traps_mid 1, rhomboids 1, lats .5, biceps_brachii .5, brachialis .5, delt_posterior .5, erectors .5, wrist_flexors_grip .5 (axial 2; OAP B option) |
| db_row_single_arm | One-arm dumbbell row / Eenarmig roeien met dumbbell | One-Arm_Dumbbell_Row | horiz_pull | traps_mid 1, rhomboids 1, lats .5, biceps_brachii .5, brachialis .5, delt_posterior .5, wrist_flexors_grip .5 |
| machine_row | Seated row machine / Roeimachine | Leverage_Iso_Row | horiz_pull | as seated_cable_row_neutral |
| tbar_row | T-bar row / T-bar row | T-Bar_Row_with_Handle | horiz_pull | as barbell_row_pronated |
| straight_arm_pulldown | Straight-arm pulldown / Pulldown met gestrekte armen | Straight-Arm_Pulldown | straight_arm_pull | lats 1, triceps_long .25, delt_posterior .25 |
| inverted_row_smith | Inverted row (Smith) / Inverted row (Smith) | Inverted_Row | horiz_pull | traps_mid 1, rhomboids 1, lats .5, biceps_brachii .5, delt_posterior .5 (bw 0.60, weak) |
| db_shrug | Dumbbell shrug / Schouderophalen met dumbbells | Dumbbell_Shrug | shrug | traps_upper 1, wrist_flexors_grip .5 (grip_demand 2) |
| cable_shrug | Cable shrug / Schouderophalen aan de kabel | Cable_Shrugs | shrug | traps_upper 1, wrist_flexors_grip .5 (grip_demand 2) |
| smith_shrug | Smith shrug / Schouderophalen in Smith | — | shrug | traps_upper 1, wrist_flexors_grip .5 (grip_demand 2) |
| scap_pull_bar | Scapular pull-up / Scapulaire pull-up | Scapular_Pull-Up | vert_pull | traps_lower .5, lats .25, wrist_flexors_grip .5 (tag `prehab`; OAP warm-up) |

**Arms (19)**

| slug | EN / NL | fedb | pattern | stimulus |
|---|---|---|---|---|
| barbell_curl | Barbell curl / Biceps curl met halter | Barbell_Curl | elbow_flex | biceps_brachii 1, brachialis .75, brachioradialis .5 (grip supinated) |
| ez_bar_curl | EZ-bar curl / Biceps curl met EZ-stang | Close-Grip_EZ_Bar_Curl | elbow_flex | biceps_brachii 1, brachialis .75, brachioradialis .5 |
| db_curl | Dumbbell curl / Biceps curl met dumbbells | Dumbbell_Bicep_Curl | elbow_flex | biceps_brachii 1, brachialis .75, brachioradialis .5 |
| incline_db_curl | Incline dumbbell curl / Schuine biceps curl | Incline_Dumbbell_Curl | elbow_flex | biceps_brachii 1, brachialis .75 (stretch 2) |
| hammer_curl_db | Hammer curl / Hamer curl | Hammer_Curls | elbow_flex | brachialis 1, brachioradialis 1, biceps_brachii .5 (grip neutral) |
| cable_curl | Cable curl / Biceps curl aan de kabel | Standing_Biceps_Cable_Curl | elbow_flex | biceps_brachii 1, brachialis .75 |
| preacher_curl_machine | Preacher curl machine / Preacher curl (machine) | Machine_Preacher_Curls | elbow_flex | biceps_brachii 1, brachialis .75 (stretch 1) |
| bayesian_cable_curl | Bayesian cable curl / Bayesian curl aan de kabel | — | elbow_flex | biceps_brachii 1, brachialis .75 (stretch 2) |
| reverse_curl_ez | Reverse curl / Reverse curl met EZ-stang | Reverse_Barbell_Curl | elbow_flex | brachioradialis 1, brachialis .75, biceps_brachii .25, wrist_extensors .5 (grip pronated) |
| triceps_pushdown_rope | Triceps pushdown, rope / Triceps pushdown met touw | Triceps_Pushdown_-_Rope_Attachment | elbow_ext | triceps_lat_med 1, triceps_long .75 |
| triceps_pushdown_bar | Triceps pushdown, bar / Triceps pushdown met stang | Triceps_Pushdown | elbow_ext | triceps_lat_med 1, triceps_long .75 |
| overhead_cable_triceps_ext | Overhead cable triceps extension / Triceps extensie boven het hoofd (kabel) | Cable_Rope_Overhead_Triceps_Extension | elbow_ext_overhead | triceps_long 1, triceps_lat_med .75 (stretch 2) |
| overhead_db_triceps_ext | Overhead dumbbell triceps extension / Triceps extensie boven het hoofd (dumbbell) | Dumbbell_One-Arm_Triceps_Extension | elbow_ext_overhead | triceps_long 1, triceps_lat_med .75 (stretch 2) |
| skull_crusher_ez | EZ-bar skull crusher / Skull crusher met EZ-stang | EZ-Bar_Skullcrusher | elbow_ext | triceps_long 1, triceps_lat_med 1 (js_elbow_medial 1) |
| close_grip_bench | Close-grip bench press / Bankdrukken smalle greep | Close-Grip_Barbell_Bench_Press | horiz_push | triceps_lat_med 1, triceps_long .5, chest_sternal .75, delt_anterior .5 |
| dip_neutral | Dip, neutral handles / Dips aan neutrale handvatten | Dips_-_Triceps_Version | dip | triceps_lat_med 1, triceps_long .75, chest_sternal .75, delt_anterior .5 (bw 1.00; OAP B) |
| dip_machine | Dip machine / Dipmachine | Dip_Machine | dip | triceps_lat_med 1, triceps_long .75, chest_sternal .75 |
| single_arm_cable_pushdown | Single-arm cable pushdown / Eenarmige pushdown | — | elbow_ext | triceps_lat_med 1, triceps_long .75 |
| triceps_extension_machine | Triceps extension machine / Triceps machine | Machine_Triceps_Extension | elbow_ext | triceps_lat_med 1, triceps_long .75 |

**Forearms, grip and prehab (11)**

| slug | EN / NL | fedb | pattern | stimulus |
|---|---|---|---|---|
| wrist_curl_db | Dumbbell wrist curl / Polscurl met dumbbells | Palms-Up_Dumbbell_Wrist_Curl_Over_A_Bench | wrist_flex | wrist_flexors_grip 1 |
| reverse_wrist_curl_db | Reverse wrist curl / Omgekeerde polscurl | Palms-Down_Dumbbell_Wrist_Curl_Over_A_Bench | wrist_ext | wrist_extensors 1 (tag `prehab`; OAP prehab block) |
| db_pronation_supination | Dumbbell pronation-supination / Pronatie-supinatie met dumbbell | Dumbbell_Lying_Pronation | forearm_rot | pronators_supinators 1 (tag `prehab`; OAP) |
| wrist_roller | Wrist roller / Wrist roller | Wrist_Roller | wrist_ext | wrist_extensors 1, wrist_flexors_grip .5 |
| farmer_carry_db | Farmer's carry / Farmer's walk | Farmers_Walk | carry | wrist_flexors_grip 1, traps_upper .5, anti_rotation .25 (grip_demand 3) |
| plate_pinch | Plate pinch / Plate pinch | Plate_Pinch | grip_hold | wrist_flexors_grip 1 (grip_demand 3) |
| cable_external_rotation | Cable external rotation / Exorotatie aan de kabel | External_Rotation_with_Cable | ext_rotation | rotator_cuff_er 1 (prehab) |
| band_external_rotation | Band external rotation / Exorotatie met elastiek | External_Rotation_with_Band | ext_rotation | rotator_cuff_er 1 (prehab, home) |
| scap_push_up | Scapular push-up / Scapulaire push-up | — | horiz_push | serratus_anterior 1 (prehab) |
| tibialis_raise | Tibialis raise / Tibialis raise | — | calf | tibialis_anterior 1 (prehab) |
| two_arm_dead_hang_bar | Two-arm dead hang / Dead hang aan twee armen | — | hang | wrist_flexors_grip .5 (hang, C8; the OAP home habit uses the challenge-local `two_arm_dead_hang`) |

**Core (11)**

| slug | EN / NL | fedb | pattern | stimulus |
|---|---|---|---|---|
| cable_crunch | Kneeling cable crunch / Kabelcrunch knielend | Cable_Crunch | core_flex | rectus_abdominis 1, obliques .5 |
| machine_crunch | Ab crunch machine / Buikspiermachine | Ab_Crunch_Machine | core_flex | rectus_abdominis 1 |
| hanging_knee_raise | Hanging knee raise / Hangend knieheffen | Hanging_Leg_Raise | core_flex | rectus_abdominis 1, obliques .5, wrist_flexors_grip .5 (grip_demand 2) |
| captains_chair_knee_raise | Knee raise in the dip station / Knieheffen in de dipstation | — | core_flex | rectus_abdominis 1, obliques .5 (grip_demand 0: the grip-free alternative on OAP gap days) |
| lying_leg_raise | Lying leg raise / Liggend beenheffen | Flat_Bench_Lying_Leg_Raise | core_flex | rectus_abdominis 1 |
| reverse_crunch | Reverse crunch / Omgekeerde crunch | Reverse_Crunch | core_flex | rectus_abdominis 1 |
| ab_wheel_rollout | Ab wheel rollout / Ab wheel rollout | Ab_Roller | core_anti_ext | rectus_abdominis 1, anti_rotation .5, lats .25 |
| plank | Plank / Plank | Plank | core_anti_ext | rectus_abdominis 1, anti_rotation .5 (isometric) |
| side_plank | Side plank / Zijplank | Side_Bridge | core_anti_rot | obliques 1, anti_rotation .5 (isometric) |
| pallof_press | Pallof press / Pallof press | Pallof_Press | core_anti_rot | anti_rotation 1, obliques .5 |
| dead_bug | Dead bug / Dead bug | Dead_Bug | core_anti_ext | rectus_abdominis 1, anti_rotation .5 |

**OAP challenge-local rows (13, tag `challenge`, from `docs/challenges/oap.challenge.v1.json`; validated by the same rules)**

`oap_assisted_machine`, `oap_finger_assisted`, `oa_negative`, `weighted_negative_pullup`, `oa_top_hold`,
`oa_hold90_machine`, `oa_hold_angle`, `oa_straight_arm_hold`, `oa_scap_pull`, `oa_dead_hang`, `oap_attempt`,
`typewriter_pullup`, `one_arm_plank` (and the habit hang `two_arm_dead_hang`). Their regions are those of the
definition, for example `oap_assisted_machine`: lats 1, biceps_brachii .5, brachialis .5, brachioradialis .5,
wrist_flexors_grip .5, anti_rotation .5, delt_posterior .25, traps_lower .25; holds and hangs credit through C8.

**Warm-up / conditioning (2)**: `rower_erg` (Rowing_Stationary, pattern `conditioning`, zero stimulus; OAP warm-up),
`bike_erg` (Bicycling_Stationary).

Counts: quads 14, hinge 10, glutes/hip 9, calves 5, chest 15, shoulders 14, back 24, arms 19, forearms/prehab 11, core 11,
OAP 13, warm-up 2 → 147 (plus the habit hang). **Seed as released (catalog 1.0.0, user review 2026-10-08, DECISIONS changelog):** 158 rows. The bundled
`catalog-1.0.0.json` is authoritative; differences from the plan above: grip-specific pull coefficients (supinated:
biceps brachii 0.75; neutral: brachialis and brachioradialis 0.75; grip in the English names); the assisted machine split
into `assisted_pullup_machine`, `assisted_neutral_pullup_machine` and `assisted_chinup_machine`; added
`side_lying_hip_abduction` (V9 abductors), `decline_bench_barbell`, `decline_db_press`, `decline_smith_press`,
`leg_press_high_wide`, `cable_lateral_raise_two_arm`, `active_hang_bar`, `one_arm_dead_hang_bar`; `back_extension_45`
credits erectors 1.0 (V9 lower back); `one_arm_plank` uses load mode `external`; `flags_offered` per row (C74). V9 holds for every group at the default Basic-Fit inventory as long as
the checklist confirms the stations; a missing station (for example no hip-thrust machine) only removes options.

**Catalog 1.0.1 (2026-10-09, user decision, migration 020).** `machine_crunch`, `preacher_curl_machine`,
`triceps_extension_machine` and `dip_machine` had no loadable station (the first three none at all, the dip machine
only the bodyweight `dip_station`), so LOAD-1 found no stack values. Four `stack` stations were added
(`ab_crunch_machine`, `preacher_curl_machine`, `triceps_extension_machine`, `dip_machine`) and the four rows now
require them. `catalog-1.0.1.json` is the bundled file; a CAT-1 test requires a loadable station for every externally
loaded machine exercise. The app adds rows for new equipment types from the saved checklist at start-up (unconfirmed).

### 10.9 Custom exercises

`new = copy(prototype[pattern][equipmentClass])` with `prototype_exercise_id` set; the user may edit coefficients
(advanced screen) within the validator rules; `custom = true` rows are owned by the user (DATA_MODEL MIX class).
Prototypes: one per (pattern, equipment class) pair present in the seed, taken from the seed row with the lowest slug.

### 10.10 Logged data

`catalog_version` on every snapshot; coefficient changes with reason and evidence note (seed diffs in git; custom
exercises as `exercise_custom_created` events, DATA_MODEL §9.8.1); custom exercise definitions; per-group e1RM and rep trends to spot bad coefficients (a group whose
credited volume is high but never progresses).

### 10.11 Tests

| Test | Expected |
|---|---|
| `validatorRejectsUnknownRegion` | `forearm_flexor` (typo) → V2 error |
| `aliasRewritten` | challenge draft key `mid_traps` → `traps_mid` |
| `groupMaxNotSum` | overhand pull-up biceps = max(0.5, 0.5, 0.5) = 0.5, not the sum 1.5 |
| `seedCoverage` | V9 for all 18 groups at the default inventory |
| `substituteExists` | V10 for every non-skill row |
| `derivedTags` | `db_shrug` → `grip_heavy`; `barbell_curl` → `elbow_flexor_heavy`, `supinated_curl`; `lat_pulldown_wide` → `pull_heavy`; `ohp_barbell` → `axial_heavy`, `overhead_press_heavy` |
| `nlNamesPresent` | every seed row has `name_nl` |

---

## 11. `pref` — PREF-1 preference model (C26)

### 11.1 Purpose

Turn the user's 5-state preference per exercise (C26) and, only where no explicit preference exists, a small implicit
signal from behaviour, into a value multiplier for SEL-1 and SUB-1 and a tier for ELIG-1's necessity gate. Preferences
never override muscle coverage: a disliked exercise is still used when it is the only option for a needed muscle;
`never` is never used automatically.

### 11.2 Inputs and outputs

Inputs: `user_exercise_prefs` rows (`preference ∈ {never, dislike, neutral, like, favorite}`, `unavailable_until`);
events of the last `IMPLICIT_WINDOW_D` days: `exercise_added` (manual add), `exercise_swapped` (swap away, with
reason), `exercise_skipped` (with reason) (DATA_MODEL §9.8.1 names, C39). Outputs per exercise: `m_e` (multiplier), `tier`, `hidden` (until a date),
and pending prompts.

### 11.3 Algorithm

```
MULT = {never: 0, dislike: 0.6, neutral: 1.0, like: 1.15, favorite: 1.495}            // C26
if row exists: tier = row.preference; m = MULT[tier]
else:                                                                                  // implicit only without a row
   z = Σ_{ev} w(ev) · 0.5^(ageDays(ev) / IMPLICIT_HALF_LIFE_D)
       w: manual add +W_ADD; swap away with a non-external reason −W_SWAP; skip with reason `other` −W_SKIP
       external reasons (never count): occupied, equipment_missing, pain, no_time, feeling_bad, too_tired
   z = clamp(z, −IMPLICIT_CLAMP, +IMPLICIT_CLAMP); tier = neutral; m = 1.0 · (1 + z)    // tier unchanged: no gate effect
hidden = row?.unavailable_until > today                                               // not a preference; hidden from pools
prompt "Lower your preference for {exercise}?" when ≥ PROMPT_SWAPS swap-aways with non-external reasons in PROMPT_WINDOW_D
       (also for explicit rows); never changes anything automatically
"Apply to similar" (user-confirmed): when the user sets a preference, offer the same value for exercises with the same
       pattern and cosine similarity ≥ SIMILAR_COS (SUB-1 vector, §15)
```

The multipliers mean that a disliked exercise needs about 1.67 × the useful coverage of a neutral one to be picked, and
a favourite gets about 1.5 × (exercise-selection lens; preference evidence is weak: Baz-Valle 2019 shows similar gains
and better motivation with autonomy, so adherence is the mechanism). A manual add of a `never` exercise in a live
session is allowed (it is the user's action) and logged; the engine still never suggests it.

### 11.4 Constants, logging, tests

Constants (§23.11): `MULT`, `W_ADD 0.10`, `W_SWAP 0.15`, `W_SKIP 0.10`, `IMPLICIT_HALF_LIFE_D 56`,
`IMPLICIT_WINDOW_D 168`, `IMPLICIT_CLAMP 0.3`, `PROMPT_SWAPS 3`, `PROMPT_WINDOW_D 42`, `SIMILAR_COS 0.9` (all
expert-opinion, tunable). Logged: preference changes (`preference_changed`), swaps and skips with reasons, prompts shown
and accepted, whether unlocked disliked exercises were done or skipped (tuner rule T-PREF). Tests: `neverIsZero`;
`implicitOnlyWithoutRow` (an explicit neutral row ignores swaps); `externalReasonIgnored` (swap with `occupied` → z 0);
`clamp` (ten manual adds → m 1.3); `promptAfterThreeSwaps`.

---

## 12. `elig` — ELIG-1 eligibility filter and necessity gate (C26, C11, C12, C33, C34)

### 12.1 Purpose

Build the candidate pool for one block of a session. `never` exercises, missing equipment, injury exclusions, safety
tags, challenge constraints and skip goals remove exercises; disliked exercises enter the pool only when no other
exercise can train a muscle that needs work (the C26 necessity gate). It also tells the allocator which groups can be
trained at all under a day's constraints.

### 12.2 Inputs and outputs

Inputs: catalog (§10), gym inventory (stations with `available = true`, F7), `pref` (§11), goals and this block's group
targets (the allocator's `credit`), weekly coverage, `injury` exclusions (§18), `SafetyState` (§2), the block context
(`general` or `addon` with its slot; challenge blocks are materialized by the challenge engine, not by ELIG), the active
hard protected windows and forbidden session tags for the block's time (C11, C12, C33). Outputs: `pool`, `unlocked[]
{exercise, group, reason ONLY_OPTION}`, `coverageGaps[] {group}`, `filtered{reason: count}`, and
`groupAvailability[g]`.

### 12.3 Algorithm

```
pool = catalog rows (global + the user's custom), not retired, not hidden (pref.unavailable_until)
remove e if
  pref(e) == never                                                        // C26
  some station of e.requires_station is not available at the gym          // F7
  injury.excludes(e)                                                      // §18 (joint flags)
  e.tags ∩ safety.forbidExerciseTags ≠ ∅                                  // C33 (e.g. OAP injury mode: unilateral_pull, eccentric)
  e has tag `challenge` or `heavy_unilateral` (general and add-on blocks) // V12, H4
  effectiveTags(e) ∩ forbiddenSessionTags ∩ {grip_heavy, elbow_flexor_heavy} ≠ ∅
                                                                          // C11 gap days, C12 hard windows, C33; pull_heavy is a set count enforced by SEL-1
  ∃ g ∈ hardWindow.groups with groupCoeff(e, g) ≥ FORBID_COEFF,
     or ∃ r ∈ hardWindow.regions with coeff(e, r) ≥ FORBID_COEFF          // C12
  gapDay and e credits a PULL-family group (lats, upper_back, rear_delts, biceps, forearms, upper_traps) at
     ≥ FORBID_COEFF, unless all of                                        // C50 light pull, contract generalSessions.lightPullOnGapDays
        e.grip_demand ≤ lightPull.maxGripDemand (1),
        groupCoeff(e, biceps) < lightPull.groupCoefficientBelow.biceps (0.5; strict, so 0.5 is removed),
        every PULL-family group that e credits at ≥ FORBID_COEFF has goal improve or maintain and is not covered
                                                                          // onlyIfGoalsAsk: reverse pec deck kept; rows, pulldowns, shrugs removed
  ∃ g with goal skip and groupCoeff(e, g) ≥ SKIP_COEFF                    // incidental 0.25 allowed
  addon block: e.pattern ∉ slot.allowPatterns, or e.grip_demand > slot.maxGripDemand,
               or ∃ g ∈ slot.forbidGroups with groupCoeff(e, g) ≥ FORBID_COEFF,          // C34
               or primaryGroup(e) ∉ groups(F) of the add-on family F                   // a legs add-on holds legs exercises
               (primaryGroup = the group with the largest coefficient, ties by canonical order)
liked = {e ∈ pool : tier(e) ≠ dislike};  dis = {e ∈ pool : tier(e) = dislike}
for g in GROUPS (canonical) with target_g ≥ 1 and goal_g ∈ {improve, maintain}:
  thr = goal_g == improve ? GATE_IMPROVE : GATE_MAINTAIN                  // C26: 0.75 / 0.5
  if no e ∈ liked has groupCoeff(e, g) ≥ thr:
     cand = {e ∈ dis : groupCoeff(e, g) ≥ thr and (not unlockedThisWeek(e) or weeklyCov_g < UNLOCK_REPEAT_FRAC · T_g)}
     if cand non-empty: u = first by (groupCoeff(·, g) desc, sfr desc, slug asc); unlock u with reason ONLY_OPTION(g)
     elif no e ∈ pool has groupCoeff(e, g) ≥ thr: coverageGap(g)          // WarningEngine COVERAGE_GAP
return liked ∪ unlocked            // unlocked keep m = 0.6 in SEL-1, so they are used only if they still help
```

`gapDay` is true for a general block on a date where the gap rule (H1, C11) forbids the contract's `appliesToTags`,
that is a date adjacent to a challenge session that carries one of them (OAP: the days next to C, A and B). C50's
"outside protected windows" is read as: a light-pull exercise must also pass the hard-window rule above, so it may not
credit a protected group or region inside a window, but it is not banned from the rest of the window. This is the
reading of CHALLENGE_ENGINE GF-CE-36 (reverse pec deck allowed on the day before A, when the goals ask for it). Add-on
blocks are on challenge days, not gap days; their own slot filters apply.

### 12.4 Group availability (for the allocator)

`groupAvailability(dayConstraints)[g] = ∃ e in the pool built with the day's constraints (preferences ignored except
never) with groupCoeff(e, g) ≥ AVAIL_COEFF (0.75)`. A group without a direct trainer under the day's constraints (for
example `upper_traps` on an OAP gap day, because every shrug variant has grip demand ≥ 2 and `grip_heavy` is forbidden;
and `upper_back`, because every row has a biceps-group coefficient of 0.5 and the C50 filter removes it) is dropped by
the allocator with `NO_ELIGIBLE_EXERCISE`; it can still receive indirect credit (the reverse pec deck credits upper back
0.5).

### 12.5 Constants, logging, tests

Constants (§23.12): `GATE_IMPROVE 0.75`, `GATE_MAINTAIN 0.5` (C26), `FORBID_COEFF 0.5`, `SKIP_COEFF 0.5`,
`UNLOCK_REPEAT_FRAC 0.5`, `AVAIL_COEFF 0.75`. Logged: unlock events (exercise, group, residual), whether used, the user's
reaction (done, skipped, swapped), coverage gaps, filtered counts by reason. Tests: `neverNeverUnlocked`;
`dislikedUnlockedOnlyWhenNeeded` (adduction machine disliked and adductors improve with no other direct option →
unlocked; when the cable adduction exists and is neutral → not unlocked); `skipGroupIncidentalAllowed` (glutes skip:
leg press with glute 0.5 removed, leg extension kept); `gapDayGripHeavy` (Wed fixture: shrugs, RDL (grip 2) and hanging
knee raise removed; captains-chair knee raise kept); `gapDayLightPull` (C50, Wed and Fri fixture: seated cable row,
chest-supported row and lat pulldowns removed for their biceps coefficient 0.5; reverse pec deck and face pull kept;
with rear delts and upper back set to skip, the reverse pec deck is removed too); `hardWindowBrachialis` (hammer curl
removed before A); `addonPatterns` (A add-on: rows removed, leg press kept).

---

## 13. `select` — SEL-1 session exercise selection (beam search + set tuning)

### 13.1 Purpose

Pick the exercises and set counts of one general block (a standalone general session or an add-on block after a
challenge session, C34) so that the block meets the allocator's per-group targets, respects preferences, the time
budget, injuries and challenge constraints, and stays stable across the mesocycle, deterministically and explainably.
The challenge block itself is materialized by the challenge engine and is pinned (it is never selected or reordered by
SEL-1).

### 13.2 Inputs

| Input | Description |
|---|---|
| `pool` | ELIG-1 output for this block (§12) |
| `target[g]`, `goal[g]`, `capS[g]` | The allocator's `credit` per group (FS) for this block, goals and per-session caps (§5, §7) |
| `regionNeeds` | Regions of improve groups not covered in the last mesocycle (variety, §16) |
| `budgetMin` | Block budget (general session: budget − warm-up; add-on: slot capacity, §17.6) |
| `sPref` | Preferred sets per exercise from `rep_preferences` (most specific scope wins: exercise > muscle_group > mechanics > goal > global), default 3 |
| `mult` | Session transform factor (deload / re-entry, §5.4.3); 1 in a normal week |
| `pinned` | User must-include exercises for this block (with their sets) |
| `anchors` | ROT-1 anchors of the families in this block (§16) |
| `history` | Exercises used for this family in the current mesocycle; STALL-1 `stalled` flags |
| `m_e`, penalties | PREF-1 multipliers; INJ-1 per-set penalties and penalty-window penalties (§18) |
| `forbiddenSessionTags` | For the `pull_heavy` count cap (C11) |
| `evidenceMode` | `warn_only` (default, D10) or `auto_adjust_with_warning` |

### 13.3 Objective

```
cov_g  = Σ_{e ∈ S, c_eg ≥ MIN_HYP_COEFF} c_eg · s_e · hf_e        // the planned-set FS of §3 (unilateral pair = 1 set; holds hf)
raw_g  = Σ_{e ∈ S} c_eg · s_e                                     // all coefficients, for the skip penalty
for g with cov_g > 0:
   if goal_g == skip or target_g == 0: share_g = 0; if goal_g != skip: O += max(0, cov_g − 1)     // e.g. covered groups
   else: useful_g = min(cov_g, T_g) + OVER_CREDIT · max(0, min(cov_g, OVER_TOL · T_g) − T_g)
         share_g = useful_g / cov_g
         O += max(0, cov_g − max(OVER_TOL · T_g, T_g + 1)) + (LAM_CAP / LAM_OVER) · max(0, cov_g − capS_g)
for g with goal skip: O += (LAM_SKIP / LAM_OVER) · raw_g
V  = Σ_e [Σ_g w(goal_g) · c_eg · s_e · share_g] · m_e · (1 + B_STRETCH · stretch_e + B_SFR · (sfr_e − 3))
     w: improve 1.0 (focus 1.5), maintain 0.6
RB = REGION_BONUS · #{r ∈ regionNeeds : ∃ e ∈ S with coeff(e, r) ≥ 0.5}
K  = CONSISTENCY · #{e ∈ S with B_e > 0 used for this family in this mesocycle} − STALL_PEN · #{e ∈ S stalled}
     (B_e = the bracket of V for e; an exercise that adds no useful coverage earns no consistency bonus)
X  = LAM_AXIAL · max(0, Σ_e axial_e − AXIAL_CAP) + injury and window penalties (§18)
t  = TIME-1 minutes of S (§17)
J(S) = V − LAM_OVER · O + RB + K − X − LAM_TIME · t − LAM_TIME_OVER · max(0, t − budgetMin)
```

### 13.4 Search

```
setBounds(e): lo = max(1, sPref − 1); hi = min(SMAX[e.mechanics], sPref + 1)          // D10: preference ± 1
              evidenceMode == auto_adjust_with_warning → lo = max(1, sPref − 2), hi = min(SMAX, sPref + 2) (+ warning when used)
              mult < 1 → lo = max(1, ceil(lo · mult)); hi = max(lo, ceil(hi · mult))
alloc(e, R):  need = max over g with c_eg ≥ 0.75 and R_g > 0 of R_g / c_eg   (fallback: c_eg ≥ 0.5)
              s0 = mult < 1 ? hi : clamp(sPref, lo, hi)
              return clamp(min(s0, ceil(need − 0.25)), lo, hi)
S0 = pinned ∪ {a ∈ anchors : a ∈ pool and ∃ g with c_ag ≥ 0.75 and target_g > 0}     // with alloc(a, target)
single(e) = J(S0 + (e, alloc(e, target − cov(S0)))), ties by slug
topK = for g with target_g ≥ 1 (canonical): the PER_GROUP_K best e ∈ pool \ S0 with c_eg ≥ 0.75 by single(e)   // D56
       then the best remaining e by single(e) until |topK| = TOP_K; kept in single(e) order
beam = [S0]; best = S0
repeat up to MAX_EX − |S0| times:
  next = {}
  for S in beam:
    R = max(0, target − cov(S))
    for e in topK, e ∉ S:
      skip if S has 2 exercises with e.pattern
      skip if ∃ x ∈ S with x.pattern == e.pattern and cos(v_x, v_e) ≥ DEDUP_COS
      S' = S + (e, alloc(e, R)); key = sorted slugs of S'
      skip if key ∈ next, or t(S') > OVERRUN_CAP · budgetMin, or S' acquires a forbidden session tag (pull_heavy count)
      next[key] = S'
  if next empty: break
  beam = top BEAM_WIDTH of next by (J desc, key asc); if J(beam[0]) > J(best): best = beam[0]
TUNE: up to TUNE_PASSES passes over best in slug order: try s + 1, then s − 1 within setBounds; accept if ΔJ > TUNE_EPS
TIME_FIT: while t(best) > budgetMin: remove one set (not below lo, never from pinned) with the smallest ΔJ per minute;
          if no set can go, drop the non-pinned exercise with the smallest ΔJ
return ORD-1(best)
```

The per-group quota (D56) exists because the seed's 160 rows put about 30 leg compounds ahead of every calf, abduction
and lateral-raise row, so a plain top-25 cut left maintain and isolation groups without a candidate. A beam search over
about 25 candidates and 8 exercises costs about 2,400 objective evaluations (milliseconds on a phone); unit tests compare it with exhaustive search on small fixtures (exercise-selection lens; set cover is NP-hard,
Chvatal 1979). Every tie is broken by slug; no randomness.

**Add-on blocks (C34).** The same search with `budgetMin = capacity` (§17.6), the slot-filtered pool, no general warm-up
(TIME-1 counts only the specific warm-up sets of the first compound), a hard cap `Σ s_e ≤ slot.maxSets`, and the slot's
`minRir` passed to RX-1 as an RIR floor.

**Evidence and preferences (D10).** If the targets cannot be met inside `sPref ± 1`, the block keeps the preference and
the WarningEngine receives a `SETS_PREFERENCE_LIMITS_VOLUME` input (numbers: achieved versus target FS per group); with
`auto_adjust_with_warning` the bounds widen to ± 2 and the warning says so.

### 13.5 Outputs and logging

`session.plan` snapshot (scope `block:<uuid>`; DATA_MODEL 10.2 keys `est_min, budget_min, exercises[]: {exercise_id,
sets, est_s}`), plus the trace: inputs (targets, goals, budget, pinned, anchors), the top 10 single candidates with their
`J`, per chosen exercise `B_e`, `share`, `m_e`, quality term and unlock reason, and the `J` terms (`V, O, RB, K, X, t`).
After the session: the user's edits (add, remove, swap, set changes, with reasons), sets done versus planned, minutes
used versus predicted, and weekly group progress (tuner rules T-ALLOC, T-PREF, T-TIME).

### 13.6 Constants and tests

Constants (§23.13): `OVER_CREDIT 0.10`, `OVER_TOL 1.25`, `LAM_OVER 0.5`, `LAM_CAP 1.0`, `LAM_SKIP 3.0`,
`B_STRETCH 0.05`, `B_SFR 0.04`, `REGION_BONUS 0.3`, `CONSISTENCY 0.5`, `STALL_PEN 0.5`, `AXIAL_CAP 4`, `LAM_AXIAL 0.3`,
`LAM_TIME 0.05`, `LAM_TIME_OVER 0.5`, `OVERRUN_CAP 1.10`, `BEAM_WIDTH 12`, `TOP_K 25`, `PER_GROUP_K 2` (D56), `MAX_EX 8`,
`SMAX {compound 5, isolation 4}`, `DEDUP_COS 0.95`, `MAX_PER_PATTERN 2`, `TUNE_PASSES 20`, `TUNE_EPS 0.05`. All are
expert-opinion values tuned on the prototype fixtures (§22). `OVER_CREDIT` is 0.10 rather than the lens's 0.25: with the
canonical rule that 0.25 coefficients give no FS, 0.25 made a pec deck attractive after chest had reached its target in
the PUSH fixture; 0.10 reproduces the documented prototype plan. Tests: §22 fixtures as golden tests
(`legsPrototypeCase`, `pushPrototypeCase`, `legsHipThrustOccupiedLumbar`, `legsBssDisliked`,
`legsAdductorsUnlocked`, `pushChestPressOccupied`, `fri20261009FullDeload`, `wed20261007TrainAnyway`,
`thu20261008LegsAddon`; §21.8), `beamVsExhaustive` (≤ 10 candidates,
identical result), `deterministicTies`, `addonMaxSets`, `pullHeavyCapOnGapDay`.

---

## 14. `order` — ORD-1 exercise ordering and optional supersets (F13)

### 14.1 Algorithm

```
key(e) = (pinnedRank (challenge or skill items first, in the order their block defines; else 99),
          class (0 multi-joint, 1 single-joint, 2 core / prehab / conditioning),
          −Σ_{g improve} c_eg · s_e,
          −axial_e, −skill_e, slug)
sort by key (stable)
fix-ups, at most 3 passes:
 (a) pre-fatigue: a single-joint e_i before a compound e_j whose coefficient for e_i's primary group is ≥ 0.5 → move e_i after e_j
 (b) grip: grip_demand(e_i) ≥ 2 before a pinned or anchor e_j with grip_demand ≥ 2 → move e_i after e_j
 (c) same-station chaining: e_j uses e_i's station (leg press → leg-press calf raise) and shares no group with c ≥ 0.5
     with the exercises in between → place e_j right after e_i and set its setup to CHAIN_SETUP_S
blocks: challenge block first (its own order), then the general or add-on block
```

Stations (M4.3): chaining (c) and the superset rule "different stations" compare only the `requires_station` keys whose
`equipment_types.category` is `station`; implements such as dumbbells and the barbell are shared freely, so a lateral raise
is not chained to a dumbbell press.

Rationale: strength gains are largest for exercises done first; hypertrophy is similar whatever the order (Nunes 2021,
strong; Simão 2012). Skill and challenge work goes first, the main lift of an improve group next.

### 14.2 Supersets (F13)

Offered only when the plan exceeds its budget or the user enabled supersets (`allow_supersets`); pairs must have
stimulus-vector cosine ≤ `PAIR_COS_MAX` 0.2, both non-pinned, axial ≤ 1, skill ≤ 1, different stations that are both
free. `dt(a, b) = t(a) + t(b) − t_pair`, `t_pair = setup_a + setup_b + n · (work_a + between + work_b + after) − after +
TRANSITION_S` with `n = max(s_a, s_b)`. The two rest values come from REST-1 (C56, ALGORITHMS B11.2): `between` =
`rest.superset_between_s` (60 s) between the members, `after` = `max(rest.min_s, max(base_a − between − work_b, base_b −
between − work_a))` after the pair, so each muscle still gets about its own base rest. ORD-1 has no rest constants of
its own. Greedily take the pair with the largest `dt` (ties by slug pair) until the plan fits or no pair remains. Evidence: supersets cut session
time without hurting chronic gains; similar-biomechanics pairs reduce volume load (SMD −1.08), hence the cosine rule
(Zhang 2025, strong).

### 14.3 Constants, logging, tests

Constants (§23.14): `CHAIN_SETUP_S 15`, `PAIR_COS_MAX 0.2`, `FIXUP_PASSES 3`; the superset rest values are REST-1's
(`rest.superset_between_s`, `rest.min_s`; C56). Logged: final order versus the order actually performed, superset use
and rep drop on the second exercise. Tests: `legsOrder` (§22: leg press, RDL, hip thrust, seated leg curl, leg
extension, hip abduction, leg-press calf raise chained right after the leg press because nothing in between trains
calves; final order in §22.1), `preFatigueFix`, `gripFix`, `supersetCosineRule` (lateral raise + leg curl allowed;
chest press + dip rejected), `supersetRestFromRest1` (lateral raise 3 × 15 reps + seated leg curl 3 × 10 reps: 60 s
between the members, the after-pair rest from REST-1's formula, and `t_pair` uses exactly those values).

---

## 15. `substitute` — SUB-1 substitution when occupied, missing or unwanted

### 15.1 Algorithm

```
substitute(e, reason, session):
  if reason == occupied and ∃ k not done, not pinned, station(k) ≠ station(e):
      offer "Do {k} now, then come back to {e}" (k = first by ORD-1 order; until ORD-1 exists in M4, the next
      exercise not done in the session's own order, D49); defer_count(e) += 1
      after MAX_DEFERS deferrals (UI-05) go straight to the candidates below
  v(x) = [groupCoeff(x, g) for the 18 groups] ⊕ [REGION_WEIGHT · coeff(x, r) for the 34 regions]
  C = {c ∈ ELIG pool with the session's constraints : c ∉ session, station(c) not marked occupied this session}
  for c in C:
     sim = cos(v(e), v(c)); if sim < SIM_MIN: skip
     pm = 1 if c.pattern == e.pattern; 0.5 if same family (§10.6); else 0
     pn = clamp((ln m_c − ln 0.6) / (ln 1.495 − ln 0.6), 0, 1)
     sp = setup_c ≤ 60 s ? 1 : setup_c ≤ 120 s ? 0.5 : 0
     inj = max over flagged joints j of (js_j(c) / 3) · (severity_j / 3)
     score = W_SIM·sim + W_PATTERN·pm + W_PREF·pn + W_SETUP·sp − W_INJ·inj
  disliked candidates only if no non-disliked candidate has sim ≥ SIM_MIN; `never` never
  return top 3 by (score desc, slug asc) + "skip (choose a reason)" + "search catalog"
start prescription of the chosen substitute: the user's own history (RX-1), else load(to) = ratio · load(from) from
  `exercise_transfer_ratios`, else a calibration first set aimed at target RIR + 2 (C27)
the remaining plan keeps its exercises and sets (no reshuffle); coverage and minutes are recomputed and logged
```

### 15.2 Constants, logging, tests

Constants (§23.15): `W_SIM 0.60`, `W_PATTERN 0.15`, `W_PREF 0.15`, `W_SETUP 0.10`, `W_INJ 0.10`, `SIM_MIN 0.60`,
`REGION_WEIGHT 0.5`, `MAX_DEFERS 2` (expert-opinion). Logged: from, to, reason, rank chosen (1-3 or search), similarity
and score of the shown options, reorder accepted or not, performance on the substitute versus the original's
prediction, station occupancy by weekday and hour (REASON-1 also deprioritises stations busy ≥ 50 % at a slot). Tests:
§22 substitution rankings (`legPressOccupied`: hack squat first; `chestPressOccupied`: dumbbell bench first),
`reorderFirst`, `dislikedOnlyIfNoOther`, `neverNeverOffered`.

---

## 16. `rotate` — ROT-1 mesocycle anchors and systematic rotation (F12, C31)

### 16.1 Algorithm

```
mesocycle: while a challenge is active, the general mesocycle week = the challenge block week (C31); a new mesocycle
starts with block week 1; otherwise a 5-week calendar cadence (4 build + 1 deload when reactive, F8)
at mesocycle start, for each family F ∈ {LEGS, PUSH, PULL, CORE}:
  anchors_F = the top ANCHORS_PER_FAMILY multi-joint exercises of SEL-1 run for F alone at the family's typical targets
              with K = 0, keeping the previous anchors unless they stalled in ≥ 2 consecutive mesocycles,
              became `never`, or are injury-excluded
  for each accessory e used for F in the previous mesocycle:
     keep(e) = +1 if e1RM slope ≥ +PROGRESS_SLOPE per week (or reps at the same load rising)
               −1 if stalled (≥ STALL_EXPOSURES consecutive exposures without an e1RM or rep PR)
               −1 if preference ≤ dislike;  −0.5 if marked "bored";  +0.5 if preference ≥ like
  rotate the n = min(#{keep < 0}, ceil(#accessories / 3)) lowest-keep accessories (ties by slug)       // F12: ≤ 1/3
  replacement = SUB-1 ranking + REGION_GAIN · [replacement covers a region not covered in the last mesocycle]
during the mesocycle: SEL-1's K term rewards exercises already used for F (+CONSISTENCY each)
no randomness; any variety option uses a deterministic hash(weekIndex, slug) order
```

Main lifts are locked for one mesocycle (F12) because systematic variation helps and random variation can hurt
(Kassiano 2022, moderate), and stable lifts give usable progression curves. Accessories rotate only for a reason
(stalled, disliked, new region coverage).

### 16.2 Constants, logging, tests

Constants (§23.16): `ANCHORS_PER_FAMILY 2`, `PROGRESS_SLOPE 0.005` (0.5 %/week), `STALL_EXPOSURES 3`,
`ROTATE_FRACTION 1/3` (F12), `REGION_GAIN 0.2`. Logged: `rotate.mesocycle` snapshot (anchors with reasons, keep scores,
rotated pairs), progress before and after rotation, user overrides of anchors. Tests: `anchorsLockedInMesocycle`,
`rotateAtMostThird`, `stalledAccessoryRotated`, `deterministicHashOrder`, `challengeBlockAligned` (block 2 week 1 →
new mesocycle).

---

## 17. `time` — TIME-1 duration model, UI-02 trimming and add-on capacity (C19, C34, F11)

### 17.1 Purpose

TIME-1 is the only duration model of the app (C19): it predicts how long a block or session takes, fits plans into the
time budget, trims a planned session when the user picks a shorter time chip (UI-02 tiers), computes the add-on
capacity after a challenge session (C34), and learns the user's real setup, tempo, rest and transition times from set
timestamps (EWMA, α 0.3).

### 17.2 Inputs

A plan (exercises with sets, reps or hold seconds, eccentric seconds, attempts, laterality, rest seconds from REST-1 or
the challenge definition, setup seconds), the warm-up policy (challenge block, general, or add-on), the user's
calibrated parameters (`exercise_model_state`, model `time`), and the budget (F11).

### 17.3 Model

```
work(set):  reps · TEMPO_S + PER_SET_S                       // dynamic
            reps · (ecc_s + ECC_EXTRA_S) + PER_SET_S          // eccentric-only reps (e.g. 5 s negatives)
            hold_s + HOLD_OVERHEAD_S                          // timed hold (a range uses its midpoint)
            attempts · (hold_s + ATTEMPT_RESET_S)             // OAP top-hold rounds
unilateral pair (L then R): 2 · work + SIDE_SWITCH_S
t(e, s) = setup_e + s · (work + rest_e) − rest_e + TRANSITION_S
warm-up:  challenge block with its own warm-up (OAP warmup10 = 600 s)  → that block's duration
          standalone general session  → GENERAL_WARMUP_S + specific warm-up sets of the first compound
          add-on block                 → specific warm-up sets only (no second general warm-up, C34)
          specific warm-up sets = WARMUP-1 list (default SPECIFIC_WARMUP_SETS × (8 · TEMPO_S + PER_SET_S + 60 s))
T(block) = warm-up + Σ_e t(e, s_e) (+ the template's prehab block)
budget = calendar event duration − changeover_min (5 min; calendar gym events are training time, F11 revised, C49)
         | time chip (45 / 60 / 75 / 90+ = MAX_BUDGET_MIN) | default_session_min (75, F11)
```

`rest_e` is REST-1's base for the exercise class (C18: 180 s strength-heavy, 150 s lower compound, 120 s upper
compound, 90 s isolation and holds, 60 s core) unless the challenge definition gives a rest; prehab items use the
isolation class. Superset members use REST-1's between-member and after-pair rests (C56, §14.2). Rest is a time versus
performance trade-off, not a hypertrophy requirement (C18, ACSM 2026). During a live session the remaining estimate of
TIME-1 also feeds the Live Session overrun line, which UX shows when the session is more than 10 min over budget (C63).

### 17.4 Calibration (C19)

After at least `CALIB_MIN_EXPOSURES` (3) exposures of an exercise, EWMA with α = `EWMA_ALPHA` (0.3), stored in
`exercise_model_state` (model `time`):

* `setup_e` ← first set start − previous exercise's last set end (per exercise);
* `tempo_user` ← set duration / reps (per user and rest class);
* `rest_ratio_user` ← actual rest / prescribed rest, read from the canonical `set_logs` columns `rest_before_s` and
  `rest_timer_planned_s` (C55) (used for predictions only; the timer still uses REST-1);
* `transition_user` ← gaps between exercises not explained by setup.

The `time.calibration` snapshot (scope `user`) is written after each session (DATA_MODEL 10.1).

### 17.5 Dose estimator for the allocator

Before exercises are chosen, the allocator estimates minutes from group-level FS with typical parameters
(`time.group_profile`, expert-opinion, calibrated by tuner rule T-TIME from SEL-1 plans):

```
estDose(direct, warm) = (warm ? GENERAL_WARMUP_S : 0) + SPECIFIC_WARMUP_SETS · (8 · TEMPO_S + PER_SET_S + 60)
                        + Σ_g [ x_g · (reps_g · TEMPO_S + PER_SET_S + rest_g) − n_g · rest_g + n_g · (setup_g + TRANSITION_S) ]
  with n_g = ceil(x_g / 3) exercises assumed per group (about 3 sets each)
```

| Group | reps | rest s | setup s | | Group | reps | rest s | setup s |
|---|---|---|---|---|---|---|---|---|
| quads | 10 | 150 | 90 | | chest | 8 | 120 | 60 |
| hamstrings | 10 | 90 | 60 | | front_delts | 10 | 120 | 45 |
| glutes | 10 | 150 | 120 | | side_delts | 15 | 90 | 15 |
| calves | 12 | 90 | 45 | | triceps | 12 | 90 | 45 |
| abductors | 15 | 90 | 30 | | rear_delts | 15 | 90 | 30 |
| adductors | 12 | 90 | 30 | | upper_traps | 12 | 90 | 45 |
| lower_back | 12 | 90 | 30 | | lats | 8 | 120 | 45 |
| abs | 12 | 60 | 45 | | upper_back | 10 | 120 | 60 |
| | | | | | biceps | 10 | 90 | 30 |
| | | | | | forearms | 15 | 90 | 30 |

### 17.6 Add-on capacity (C34, C49)

Calendar gym events are training time (travel happens before them; the user's events say "leave 19:35" for a 19:45
start), so the booked minutes are the session's own time and only the 5-minute changeover is deducted, once (F11
revised, C49). The formula is owned by CHALLENGE_ENGINE §6.9 (`addOnCapacityMin`); this module supplies the TIME-1
estimate and the minimum:

```
capacity(X) = max(0, floor(bookedMin(X) − T(materialized challenge block of X, TIME-1) − addOnSlot.bufferMin))
   bookedMin = the matched calendar event's duration, else the template's bookedMin
   bufferMin = the 5-min changeover (C49; CHALLENGE_ENGINE V13 requires bufferMin = 5), so capacity = budgetMin − estMin
no add-on is offered when capacity < MIN_ADDON_MIN (8 min)
during the session the capacity is recomputed from the actual elapsed time (CHALLENGE_ENGINE §8.6)
```

OAP values with TIME-1 (19:45-21:15 events: 90 booked minutes, session budget 85 min):

| Session | Mode | TIME-1 estimate | Capacity (90 − est − 5, floored) |
|---|---|---|---|
| C1 | normal | 60.8 min (warm-up 10.0, assisted 8.2, weighted pull-up 10.8, scap pulls 8.4, hangs 8.2, prehab 15.2) | 24 min (24.2) |
| C1 | deload | 36.5 min | 48 min (48.5) |
| A1 | normal | 79.7 min (warm-up 10.0, assisted 14.8, negative 1.9, weighted 12.1, top holds 11.5, 90° holds 7.4, straight-arm holds 6.8, prehab 15.2) | none (5.3 → 5 min, below the 8-min minimum) |
| A1 | deload | 50.7 min | 34 min (34.3) |

D5's "~35 min free at C and ~20 min at A" are indicative only (C49): they came from the challenge lens's 4-s-per-rep
model. With TIME-1 a normal week leaves about 24 minutes after C and no add-on after A; a deload week leaves 48 and 34
minutes. The add-on's set cap (OAP: 6 sets after C, 4 after A) usually binds before the minutes do. TIME-1 is
calibrated from set timestamps (C19, §17.4), so these figures move towards the user's real pace after a few sessions.
The same values are in CHALLENGE_ENGINE GF-CE-35.

### 17.7 UI-02 trimming (time chips)

When the user picks a shorter budget for a planned session, `select` is re-run with the new budget for general blocks
(anchors keep it stable), then this trimming runs on the whole session (challenge blocks included):

```
tier(e) = 0 challenge items, prehab and safety work (never trimmed)
          1 compound of an improve group
          2 exercise whose primary group is a maintain group
          3 isolation / accessory of an improve group
minSets(e) = tier 1: MIN_SETS_IMPROVE (2) · mult; tier 2: MIN_SETS_MAINTAIN (1); tier 3: 0 (removable)
while T(session) > budget:
   cands = [e : tier(e) ≥ 1] ordered by (−tier, preference asc, engine priority asc, slug)
   if cands empty: coreDoesNotFit = true; break
   e = cands[0]; if s_e > minSets(e): s_e −= 1 else remove e (trimmed, reason TIME_SHORT)
if coreDoesNotFit: offer "Short version" (tier-0 sets = ceil(sets / 2), loads unchanged, the challenge deload rule)
                   or "Pick another day"
```

### 17.8 Constants, logging, tests

Constants (§23.17): `TEMPO_S 3`, `PER_SET_S 5`, `ECC_EXTRA_S 2`, `HOLD_OVERHEAD_S 15`, `ATTEMPT_RESET_S 15`,
`SIDE_SWITCH_S 10`, `TRANSITION_S 60`, `GENERAL_WARMUP_S 600`, `SPECIFIC_WARMUP_SETS 2`, `EWMA_ALPHA 0.3`,
`CALIB_MIN_EXPOSURES 3`, `MIN_ADDON_MIN 8`, `MIN_SETS_IMPROVE 2`, `MIN_SETS_MAINTAIN 1`, the group profile. All are
starting guesses (exercise-selection lens TIME-1; the UX lens's 4 s/rep, 20 s setup and 90 s transition are replaced
by these, C19), fitted from timestamps. Logged: session, exercise and set start/end timestamps; predicted versus actual
minutes per exercise and per session; trimmed items (`session_trimmed`); chosen chip (`time_budget_selected`). Tests:
`timeC1Deload` (36.5 min), `timeA1Normal` (79.7 min), `addonCapacityA1Deload` (90-min event → 34 min),
`addonCapacityC1Normal` (24 min), `noAddonBelow8` (A1 normal: 5 min → no add-on), `budgetIsEventMinus5` (a 19:45-21:15
event → budget 85 min, labelled as the calendar budget; no event → 75 min), `trimOrder` (accessory before
maintain before improve compound), `coreDoesNotFit` (45-min chip on session A → short version offered),
`ewmaCalibration` (three exposures with 60 s actual setup and 30 s prior → 30 + 0.3·30 = 39, 39 + 0.3·21 = 45.3, 45.3 +
0.3·14.7 = 49.7 s).

---

## 18. `injury` — INJ-1 joint-stress filter and challenge window penalties (C12, C33)

### 18.1 Purpose

Keep selection safe and compatible with active challenges, driven by data: the `SafetyState` joint flags (C33) exclude
or penalise exercises by their joint-stress attributes, the contract's penalty windows (C12) add per-set penalties,
and the contract's mode effects forbid tags. Hard protected windows are enforced as exclusions in ELIG-1 (§12).

### 18.2 Algorithm

```
for each joint flag (joint j, severity v) in SafetyState:
   v = 3: exclude e if js_j(e) ≥ 1
   v = 2: exclude e if js_j(e) ≥ 2
   v = 1: SEL-1 penalty JOINT_PEN · js_j(e) · s_e;  SUB-1 inj term (§15)
for each penalty window w (mode penalty) active at the block's time:
   per set of e: WINDOW_SET_PEN if (∃ g ∈ w.groups with groupCoeff(e, g) ≥ 0.5)
                                 or (∃ r ∈ w.regions with coeff(e, r) ≥ 0.5)
                                 or (grip_demand(e) ≥ 2 and w protects forearms or a grip tissue)
weekly caps from the contract (maxPerWeek session tags): general blocks never carry them (ELIG removes the tags)
mode effects (forbidExerciseTags) reach ELIG through SafetyState
output: excluded[] {exercise, joint | window, reason}, penalties[] {exercise, perSet}, warnings[] (SAFETY severity for
        active flags, shown as the safety banner)
in-session: a new flag re-filters the remaining exercises; excluded ones show "Skip suggested (pain)" with one-tap confirm
```

The planner's readiness-based penalty (§8.6) protects the challenge session through the muscles and tissues; INJ-1's
per-set penalty steers exercise choice inside a block that is allowed to happen in the window.

### 18.3 Constants, logging, tests

Constants (§23.18): `JOINT_PEN 0.3` per stress unit per set, `WINDOW_SET_PEN 0.5` per set (exercise-selection lens,
expert-opinion). Logged: flag history, exclusions and penalties applied (session-build trace), pain reports per session
(joint, severity), constraint violations the user overrode ("Add anyway", logged as `rule_override`). Tests:
`elbowSev2ExcludesSkullCrusher` (js_elbow_medial 1 → kept; a curl with js 2 → excluded), `lumbarSev3` (back squat
excluded, leg press kept), `windowPenaltyBeforeA` (penalty window 30 h before A: a hammer curl gets 0.5 per set if it was
not already excluded by the hard window), `inSessionRefilter`.

---

## 19. Deterministic UI functions (`ui.*`)

These functions live in `:app` but are pure Kotlin (no Android types, no clock, injected `now`), so they are unit-tested
like `:algo` code and replayable. They present engine outputs; they never compute training decisions themselves. They
live in the package `dev.fitnessapp.app.uidet`, which the purity test scans (ANDROID 27.1). This section owns the
specification of all seven `ui.*` functions (C53); UX §19 references it and adds only display details (copy
templates, layout, opacities, week strip, secondary actions). Their constants are `ui.<function>.<constant>` keys in
lower snake_case (C41).

### 19.1 `ui.todaycard` (UI-01)

**Inputs.** `TodayOptions` (§9: verdict, options with scores, `primaryOptionId`, restReasons, excluded), the session
store (`activeSession?`, sessions finished today), today's classified gym events (§1, with the challenge slot match),
`targets.status`, an engine-error flag, the next fixed item (`weekplan`), the safety banner state, and the budget
context (`profiles.default_session_min` 75, `profiles.changeover_min` 5, the selected chip; F11, C49).

**Output.** `TodayCard {state, variant, primary?, alternatives[≤ MAX_ALTS], alternativesInline, chips[≤ MAX_CHIPS],
notToday[], budgetChips, whyPayload}` with the states of UX 9.3: `SETUP_NEEDED, IN_PROGRESS, DONE, PLANNED (challenge |
test | general), PLANNED_BUT_REST (soft | hard), OPTIONAL (clear | close), REST (soft | hard), ENGINE_ERROR`. Titles,
start labels, preview lines, the week strip and the secondary actions are display details of UX §19.1.

```
if activeSession:                                          return IN_PROGRESS (elapsed, sets done/total, rest state)
if today == null and engineError:                          return ENGINE_ERROR
if targets.status == setup_needed and no active challenge: return SETUP_NEEDED
planned = today's challenge_session / planned_gym events not yet linked to a finished session, by (start, instanceKey)
if planned is empty and a session finished today:          return DONE (+ next fixed item from weekplan)
v = today.verdict                                          // TRAIN | REST_SOFT | REST_HARD, decided by `today` (§9.4)
if planned is not empty:
   e = planned[0]; variant = TEST if e.slot == TEST, CHALLENGE if e is a challenge_session, else GENERAL
   if v == REST_HARD and variant != TEST:   return PLANNED_BUT_REST(HARD), no primary, chips from restReasons
   if v == REST_SOFT and variant == GENERAL: return PLANNED_BUT_REST(SOFT), "Start anyway" = the option of e,
                                                   chips from restReasons
   return PLANNED(variant), primary = the option whose slot or type matches e (else primaryOptionId)
if v != TRAIN:   return REST(HARD if v == REST_HARD else SOFT), no primary, chips from restReasons;
                 REST SOFT offers "Train anyway" with the options in today's order (primaryOptionId first)
primary = options[primaryOptionId]                         // the engine's choice; never re-sorted here (§9.4)
second  = the best other option by (score desc, TYPE_ORDER)
close   = second != null and primary.score > 0 and second.score ≥ primary.score · (1 − CLOSE_REL)       // C53
return OPTIONAL(close ? CLOSE : CLEAR), primary, alternatives = the next MAX_ALTS options, inline when close
chips(reasons) = reasons sorted by (precedence class of §0.6, −|weight|, code), one per (code, subject),
                 first MAX_CHIPS; text = UX §19.1.1 template(code).format(params) (EN, decimal point, D12)
notToday = today.excluded sorted by (precedence class, family order)
whyPayload = {all options with score and dJ, reasons with weights, excluded families, inputs hash, module versions}
```

The test-day card ignores `REST_HARD`, because the test slot is the challenge's own decision (C36); a safety state on a
test day is shown by the safety banner and the test wizard's validity step. `ui.todaycard` never re-thresholds the
verdict (C53). Its one threshold, `CLOSE_REL`, is relative because `today`'s scores are unbounded (§9.3): two options
are a close call when the second is within 10 % of the primary's score. It replaces UI-01's absolute `CLOSE_MARGIN`
(0.15). With an active challenge but no muscle goals yet, the card still shows the challenge state (the challenge
works without goals), and general options appear once the goals are set.

Constants (§23.19): `CLOSE_REL 0.10` (C53), `MAX_CHIPS 3`, `MAX_ALTS 3` (Hick's law: few choices; ux lens). Logged:
`today_card_shown` with the registry payload (`state`, `verdict`, `variant`, `options`, `chips`, `confidence?`,
`primary_option_id?`, `calendar_instance_keys`, `unknown_codes`, `inputs_hash`; DATA_MODEL §9.8.1), `today_action`,
`why_opened`. Tuner rule T-ACCEPT uses how often a non-primary option is started.

Tests: `wedFixtureSoftRest` (§21.9: REST soft, no primary, chips ["A Thu 19:45"] ["Legs + push + rear delts fits
better on Fri"], "Train anyway" lists "Legs + push + rear delts", "Legs + push", "Legs + rear delts");
`capacity19CloseCall` (the §21.11 variant: OPTIONAL close, 0.471 ≥ 0.478 × 0.90 = 0.430, alternatives inline, chips
[Deload week] [Pull rests for A tomorrow] [Chest 0 → 3.2 / 6]); `friOptionalClear` (§21.10: 1.599 < 1.840 × 0.90 =
1.656 → CLEAR); `noviceCloseCall` (novice variant of §21.11: primary "Legs + push + rear delts" at 0.652, chosen by the
higher-`J` key of the amended C52, "Legs + push" 0.634 ≥ 0.652 × 0.90 = 0.587 inline; CLOSE);
`primaryIsEnginesChoice` (a user-planned event with type PUSH+LEGS: primary "Legs + push" although "Legs + push + rear
delts" scores higher; the card keeps the engine's primary and never re-sorts); `satHardRest` (REST hard, no primary);
`thuPlannedChallenge` (PLANNED challenge, primary "Session A + legs add-on"); `testIgnoresRestHard`; `inProgressWins`;
`doneShowsNext`.

### 19.2 `ui.reasonchips` (UI-05)

**Inputs.** The deviation direction from `ui.prefill` (easier, harder, skip, session end) or a swap from the
exercise menu without a skip (substitute), whether the exercise offers the `torso_rotation` flag (catalog
`flags_offered` or the challenge item, C74), the active pain flags, the exercise's defer count. **Output.** An ordered chip list (at most
`MAX_VISIBLE` visible plus "More"), the routing action for the chosen code. Codes are the DATA_MODEL `reason_codes`
vocabulary and each list holds exactly the codes whose `ui_contexts` contain that context (C38); their semantics
belong to REASON-1 (C16; `too_tired` has the same effect as `feeling_bad`, C57). Choosing `torso_rotation` or
`technique_breakdown` also sets the set flag of the same name (DATA_MODEL `implies_flag`).

```
easier:      [too_easy, feeling_good, other]
harder:      [too_hard, feeling_bad, too_tired, pain, grip_failure, torso_rotation (only when the exercise offers that flag),
              technique_breakdown, no_time, occupied, other]
skip:        [no_time, occupied, pain, feeling_bad, too_tired, equipment_missing, other]   // one tap completes the skip
session end: [no_time, pain, other]
substitute:  [occupied, equipment_missing]          // swap from the exercise menu without a skip (C69); optional
chips are optional for non-skip deviations (untouched → none)
occupied on a skip → DO_LATER: move the exercise after the next not-done exercise; deferCount += 1;
   deferCount ≥ MAX_DEFERS → open the Swap sheet (SUB-1)
pain → Pain sheet → PainEvent → safety (C33); an active challenge pain rule decides its own protocol
other → optional free text (never required, never copied into telemetry)
```

Constants (§23.19): `MAX_VISIBLE 6`, `MAX_DEFERS 2`. Logged (DATA_MODEL §9.8.1 events `reason_selected`,
`exercise_skipped`, `exercise_deferred`, `swap_sheet_shown`, `exercise_swapped`, `pain_reported`): code, chip position,
untouched-chip rate, defers, defer-to-done versus defer-to-skip, swap rank, pain location and severity. Tests:
`harderOrderRotationFlag`
(torso_rotation present when the flag is offered), `harderOrderNoRotationFlag` (absent, also for the assisted one-arm
pull-up on the machine, whose knee platform prevents rotation), `skipOneTap`, `occupiedDeferThenSwap`, `painRoutesToSafety`,
`substituteContext` (a menu swap shows exactly [occupied, equipment_missing]).

### 19.3 `ui.prompts` (UI-07, F15)

**Inputs.** The session plan (groups and planned FS, challenge slot), the last sessions per group, prompt history,
pain history, whether an active challenge has a pain rule, the prompt toggles. **Output.** `PRE[]` and `POST[]` prompt
lists with expected taps.

```
PRE (gym sessions only; one optional sheet before the first exercise):
  readiness  [Worse][Normal][Better]                              (1 tap)
  elbow check [0 None][1 Trace][2 Mild][3 Pain] (+ kind at ≥ 2)    (1 tap; the symptom-kind follow-up at ≥ 2 is a
                                                                   safety tap outside TAP_BUDGET, C69)
  soreness for planned groups (direct FS > 0) trained within SORE_WINDOW_H, top SORE_MAX by planned FS: [No][Was, fine now][Yes]
POST (Session Summary):
  sRPE 0-10 (Foster 2001)                                          (1 tap)
  joint pain [None] or location → severity [Mild][Moderate][Sharp] (1 tap for None)
budget: expected taps of PRE + POST ≤ TAP_BUDGET (safety follow-up taps not counted); drop in reverse priority
        (priority pain > elbow check > sRPE > readiness > soreness; soreness rows dropped one group at a time,
         lowest planned FS first, ties: the group later in the canonical order of §0.3 first)
adaptive: a prompt skipped SKIP_STREAK sessions in a row is shown every REDUCED_EVERY sessions; restored after
          RESTORE_AFTER answers
never reduced: the pain prompt if pain was reported within PAIN_GUARD_D days or a challenge pain rule is active;
               the elbow check while a challenge pain rule is active (F15)
home habit sessions: no PRE prompts; POST pain only
answers: readiness, soreness → readiness check-in (§4); elbow check → safety (§2) and the challenge (readiness.checked);
         sRPE → session load (sRPE × minutes); pain → PainEvent (C33)
```

Constants (§23.19): `SORE_WINDOW_H 96`, `SORE_MAX 3`, `TAP_BUDGET 6` (F15), `SKIP_STREAK 3`, `REDUCED_EVERY 3`,
`RESTORE_AFTER 3`, `PAIN_GUARD_D 14`, `PAIN_SEVERITY_MAP {mild 2, moderate 5, sharp 8}` (the 0-10 scale used by
REASON-1, whose stop threshold is 4, C64). Evidence: subjective measures track training response well (Saw 2016,
strong); sRPE is valid (Foster 2001, moderate). Logged: `prompt_shown`, `prompt_answered`, `prompt_skipped` (DATA_MODEL
§9.8.1), latency, answers versus next-session performance. Tests: `friPrompts` (§21: the Friday session plans rear
delts 2.5, quads 1.5 and hamstrings 1.5 FS, all trained within 96 h (Tue C prehab, the Thu add-on) → readiness, elbow,
soreness rear delts and quads, sRPE, pain = 6 taps; hamstrings dropped, tied with quads and later in canonical
order), `thuPromptsBudget` (A + legs add-on:
soreness candidates lats 6.5, forearms 5.3, biceps 3.5 FS → biceps dropped → 6 taps), `elbowNeverReducedWithOap`, `painGuard`,
`elbowFollowUpOutsideBudget` (elbow score 2 on the Friday sheet → the kind tap is asked and no soreness row is dropped
for it).

### 19.4 `ui.volumebars` (UI-09)

**Inputs.** `WeekAccount` (§3: done, committed challenge coverage, planned general), `WeeklyTargets` (§5), WarningEngine
results, goals. **Output.** Rows `{group, goal, status, done, planned, target, normalTarget, mrv, fillDone,
fillPlanned, state ∈ {DEFICIT, OK, OVER}, flag, note}`.

```
for g in GROUPS:
  if status covered: row "covered by challenge" (no bar; challenge volume exempt from MRV warnings, D6); continue
  if status skip: collect into one line "Not trained: …"; continue
  done = account.done[g].vhyp; planned = committed[g] + plannedGeneral[g]       // Vhyp, the C6 table via SetCredit
  tgt = targetEffective[g] (deload weeks) else target[g]; normalTarget shown as a faint tick in deload weeks
  fillDone = min(done / tgt, BAR_CAP); fillPlanned = min((done + planned) / tgt, BAR_CAP) − fillDone
  state = (WarningEngine has a WARN or STRONG volume deficit for g) ? DEFICIT
        : (done + planned > mrv[g]) ? OVER : OK
  flag  = state != OK ? "!" : none
sort: improve rows, then maintain rows, each by (done + planned) / tgt ascending, then canonical order;
      covered rows next; the skip line last
numbers: one decimal, "2.5 (+3) / 6"
```

Constants (§23.19): `BAR_CAP 1.25`. Fractional counting with stimulus weights (Pelland 2026; C6). Logged: weekly done,
planned and target per group (in the `accounting.week` and `targets.weekly` snapshots), row taps
(`volume_row_opened`). Tests: `coveredRowNoBar`, `deloadUsesEffectiveTarget`, `sortOrder`, `wedBeforeSession` (§21:
chest 0 (+3.6) / 6 on Wednesday, with the recommended Friday session counted as planned).

### 19.5 `ui.warndisplay` (UI-10)

**Inputs.** WarningEngine violations `{ruleId, severity ∈ {INFO, WARN, STRONG, SAFETY}, entity, magnitude 0-1,
evidenceGrade, citations, appliedOverride, suggestion?}`; the dismissal store; "use mine anyway" flags; the screen
scope. **Output.** At most one banner, the "N more notes" count, inline markers per entity, Why payloads.

```
visible = []; listOnly = []
for v in violations within the screen's scope:
  if v.severity == SAFETY: visible += v; continue                          // persistent until acknowledged
  if useMineAnyway(v.ruleId, v.entity): marker(entity, "your choice"); continue
  if v.severity == INFO: dot(entity, muted); listOnly += v; continue       // never a banner
  dot(entity, amber)
  if v.magnitude < MIN_MAGNITUDE: listOnly += v; continue
  d = dismissal(v.ruleId, v.entity)
  if d and now − d.at < SNOOZE_D days and v.magnitude < d.magnitude · (1 + WORSEN_FACTOR): listOnly += v; continue
  visible += v
sort visible by (severity SAFETY > STRONG > WARN, magnitude desc, ruleId)
banner = first non-SAFETY visible (the safety banner has its own slot above it, UX 7.3)
moreNotes = |visible| − 1 + |listOnly|
```

Constants (§23.19): `MIN_MAGNITUDE 0.2`, `SNOOZE_D 28`, `WORSEN_FACTOR 0.25`, `MAX_BANNERS 1`. Evidence: on-demand,
layered explanations (Tintarev & Masthoff 2012; Laato 2022, moderate); D10 keeps the user's preference and offers "use
suggested". Logged: `warning_shown`, `warning_dismissed`, `warning_use_mine_toggled`, `warning_use_suggested`,
`warning_why_opened` (DATA_MODEL §9.8.1), plus outcomes in the following weeks. Tests: `safetyAlwaysShown`, `infoNeverBanner`,
`snoozeAndResurface` (dismissed at magnitude 0.4; 0.45 stays hidden, 0.55 resurfaces), `useMineMarker`.

### 19.6 `ui.progress` (UI-11)

**Inputs.** Per session, the working sets of an exercise with the engine-logged per-set e1RM (C3: never recomputed in
the UI or in SQL; SSE-1 output), the SSE-1 filtered state per session (level and 80 % interval, engine-logged), the
set's system load from LOAD (C1, C2) and its `BW(t)` snapshot (C32), challenge gate observations
(`metric_observations` with `derivation`, D7), the display toggle. **Output.** Points, the SSE-1 band, PR markers,
test-day markers, labelled challenge-gate series.

```
y_i = max over the session's working sets of the engine e1RM (system kg); one point per session (and side)
band_i = the SSE-1 posterior level with its 80 % interval at that session (C4: SSE-1 is the single general estimator,
         so there is no UI-side smoothing)
display: "added kg" = y − f_bw · BW snapshot of that set (assisted lifts become negative on the same axis) | "system kg" = y
PR: y_i > max(previous y) · (1 + PR_MIN_GAIN); holds: PR on seconds per side, gain ≥ HOLD_PR_MIN_GAIN_S; assisted: PR
    on the lowest effective assistance (C2 assistKg) for one clean rep; eccentric-only, isometric and finger-assisted
    sets never give an e1RM PR (C8)
    rep PR: more reps than ever before at the same effective load (0.01 kg grid) (C69)
    no PR of a metric before PR_MIN_HISTORY previous sessions of that metric (no PR on the first exposure) (C69)
    a set with a pain reason never gives a PR (C69)
default window WINDOW_WEEKS; test-day points as filled diamonds;
the challenge gate metric (D7 personal-curve value) and the ×1.10 value are shown next to the SSE-1 estimate, labelled (C4);
the gate threshold is evaluated on the test-day bodyweight, and the forward threshold at the current BW(t) is shown too (C62)
```

Display details (chart ranges, opacities, the personal-best list of the Session Summary and the layout of the gate
rows) are in UX §19.7.

Load examples for the fixture (C1, C2; `assistKg = pin_lb · 0.45359237 · 1.0 + 6.5`, BW 70.7 kg): weighted C
anchor +20 kg → 90.70 kg system; A +22.5 → 93.20; B +27.5 → 98.20; negatives +47.5 → 118.20 (not an SSE-1 input, C8);
assisted A pin 40 lb → assist 24.64 kg → 46.06 kg per arm; C pin 50 lb → 29.18 → 41.52 kg; 90° hold pin 35 lb → 22.38
→ 48.32 kg.

Constants (§23.19): `PR_MIN_GAIN 0.005`, `HOLD_PR_MIN_GAIN_S 0.5`, `PR_MIN_HISTORY 1` (C69), `WINDOW_WEEKS 12`
(UI-11's EWMA `ALPHA` is dropped: the band is SSE-1's).
Logged: `progress_viewed`, `progress_axis_toggled`, `progress_point_opened`, `pr_detected` (DATA_MODEL §9.8.1; PR events
check that the PR rate is not noise), BW staleness. Tests: `assistedNegativeAxis`, `prThreshold`, `neverRecomputes`
(the function has no access to e1RM formulas: it takes per-set values only), `bandIsSse1` (the band equals the logged
SSE-1 level and interval; no smoothing), `addedKgUsesSetSnapshot` (a BW change after the session does not move old
points), `gateSideBySide`, `gateTwoThresholds` (C62), `repPr` (8 reps at a load whose best was 7 → PR),
`holdPrMinGain` (1.0 → 1.5 s per side → PR; 1.0 → 1.4 s → none), `noPrFirstExposure`, `noPrWithPainReason`.

### 19.7 `ui.prefill` (UI-04)

**Inputs.** Targets for set k (RX-1 or the challenge item), an accepted in-session adjustment (UI-06 / AUTO-1, C16),
the last actual values of the same exercise and set index, the equipment load scheme (F7), the exercise's load mode.
**Output.** Displayed values, stepper steps, the snapped value, a deviation flag with direction.

```
prefill(field) = first non-null(acceptedAdjustment[k], target[k], todayChanged[k−1], lastActual[k], lastActual[k−1], default)
   // D23: todayChanged[k−1] = today's set k−1 of this exercise when it differs from lastActual[k−1] (or no
   // lastActual[k−1] exists); logged as last_prev_index
   // a challenge in-session rule (D14, C46) rewrites target[k] of the remaining sets itself ("Rule: … · Undo");
   // an Undo restores the previous targets; general suggestions arrive only as an accepted adjustment (D10, C16)
lastActual: only sets of the same exercise in the same load mode (bodyweight, weighted, assisted, ...); no prefill
   history crosses load modes, e.g. after "+ add weight" on a bodyweight exercise (C69)
side rule: when the item has the side rule, the right-side reps prefill is capped at the left side's logged reps (C69)
RIR chips and felt chips are never prefilled or highlighted: tapping one IS the log action on working sets (D9);
   warm-up sets are logged with "Done" and no RIR; test-day attempts use "Record" and a 3RM is stored with RIR 0 (C63)
step: barbell 2 × smallest plate; dumbbells the next value in the list; stack the stack step; dip belt the smallest
      owned plate; assisted pin 5 lb (label "lower = harder"); fingers 1; bodyweight none ("BW", "+ add weight");
      reps 1; hold 0.5 s below a 10 s target, otherwise 1 s (the top-hold attempt fields use the same step, C69)
long press: after HOLD_DELAY_MS repeat every REPEAT_MS; after ACCEL_AFTER repeats the step × ACCEL_FACTOR
keypad: chips [nearest lower loadable] [nearest higher loadable]; "Set" snaps with LOAD.nearestLoadable;
        ties go to the easier load (lower external load or more assistance, C2)
deviation: |w − w_target| ≥ 0.5 · step, or reps outside the target range, or hold < HOLD_SHORT_FRAC · target, or a skip,
           or a changed set count → direction easier | harder for ui.reasonchips
           hold ranges [lo, hi] are judged against lo (target = lo above) (C69)
post-log RIR deviation: |logged RIR − target RIR| ≥ POST_RIR_DELTA → direction easier (RIR higher) | harder (lower);
           the optional reason chips appear in the rest card (C69)
display: native unit first ("Pin 40 lb"), effective kg second ("~24.6 kg assist", 1 decimal, C54); decimal point (D12)
```

Constants (§23.19): `HOLD_DELAY_MS 400`, `REPEAT_MS 120`, `ACCEL_AFTER 8`, `ACCEL_FACTOR 5`, `HOLD_SHORT_FRAC 0.8`
(Android auto-repeat conventions; engineering-fact / expert-opinion), `POST_RIR_DELTA 2` (C69). Logged in `set_logged`: `prefill_source`,
`deviation`, `input_method`, `taps`, `ms_from_rest_end`. Tests: `prefillOrder`, `noRirPrefill`, `assistedTieMoreAssist`
(a typed 37.5 lb is equally near 35 and 40 lb → 40 lb, more assistance), `beltStep` (owned
plates {1.25, 2.5, 5, 10, 20} → step 1.25 kg), `fixtureC` ("BW + 20.0 kg × 5", "Pin 50 lb (~29.2 kg assist)"),
`holdStepBelow10` (target 8 s → step 0.5 s; target 12 s → 1 s), `rightCappedAtLeft` (left logged 6 reps, right target
8 → right prefill 6), `postRirDelta` (target RIR 2, logged 4 → easier; logged 3 → no deviation), `holdRangeLowerBound`
(range [10, 15] s, 9 s logged ≥ 0.8 · 10 → no deviation), `noHistoryAcrossLoadModes` (first weighted set after
bodyweight sets → prefill from target, not the bodyweight reps).

---

## 20. Offline tools (`:sim`)

### 20.1 `tuner` — ModelTuner.v1 (proposals only, never silent)

**Purpose.** Re-estimate personal constants from logs and propose a new params version for review. Every proposal is a
diff of the params JSON with the evidence (numbers, sample sizes, before/after fit) and is applied only after the user
approves it. The user approves the proposed change list of one review as a batch, each change listed with its reason
(F26); nothing is written before that approval. An approved batch becomes a new `user_algo_params` row with
`valid_from`, and the params version bumps (DATA_MODEL 9.3.7). Run by Claude
Code during periodic reviews (`gradlew :sim:run --args="tune --from 2026-10-07"`), never on the phone automatically.

**Inputs.** At least the minimum samples per rule; the `fitness_review` views (engine-logged values, never recomputed),
`recommendations` snapshots, `session_events`, `set_logs`, `wellness_logs`, `calendar_events`.

| Rule | Tunes | Data | Method | Accept when |
|---|---|---|---|---|
| T-KAPPA | `readiness` kappa per class (t22, t26, t30, t34) | readiness snapshots at session start, first-hard-set performance index PI = e1RM_set / best e1RM of the prior 28 d, soreness answers | grid kappa ∈ [0.7, 1.5] step 0.1: recompute R with τ·kappa, fit PI = a + b·R by OLS, keep the kappa with the lowest SSE; soreness (still sore while R ≥ 0.8, or never sore while R < 0.6) reported as a secondary confusion matrix (C5: soreness feeds the tuner, not the live model) | n ≥ 12 per class, b > 0, SSE improves ≥ 5 % versus kappa 1.0, change ≤ ±0.2 per review |
| T-TIME | TIME-1 profile, `TEMPO_S`, `TRANSITION_S`, setup defaults, `time.group_profile` | set and exercise timestamps of the last 6 weeks | medians of observed values per class; compare with the EWMA state | abs(Δ) ≥ 10 % and n ≥ 20 sets per class |
| T-TARGET | `targets` per group (proposed T change) | attainment (achieved Vhyp / T) per week; `too_easy` share per group | attainment < 70 % for 3 consecutive weeks → propose T − 2 with the reason (skips, time, planning); `too_easy` > 30 % of a group's sets for 2 weeks → propose a +2 ramp | always a proposal with the cause attached |
| T-ACCEPT | today / weekplan weights (`W`, penalties, `SESSION_COST`) | `today_action`, plan versus performed sessions | per candidate type, acceptance rate | rate < 50 % over ≥ 6 recommendations → flag for review (no automatic numeric change) |
| T-WEEK | `STABILITY_W`, `TIE_BAND` | replans per week that changed a day (churn) | | churn > 3 per week for 3 weeks → propose +0.05 stability |
| T-CAL | `calendar.classify` weights and lexicons | classification log + user corrections | precision/recall per feature | a feature with false-positive rate > 20 % over ≥ 10 corrections → propose weight change; recurring unmatched titles → propose lexicon additions |
| T-RUN | `runs` per-minute doses | leg-session PI after a run within 24 h versus not | ratio of PI means | n ≥ 8 per arm; propose scaling `PER_MIN` by the ratio, clamped ±30 % |
| T-ALLOC | allocator indirect shares, `SYS_RATE`, `TISSUE_RATE` | allocator `credit` versus SEL-1 achieved credit and logged credit | mean ratio per (source, target) | n ≥ 10 sessions; abs(log ratio) ≥ 0.15 |
| T-PREF | `pref` multipliers | unlocked disliked exercises done / skipped / swapped; swaps by preference tier | rates | skip rate of unlocked disliked > 60 % over ≥ 5 unlocks → propose 0.5; review only |
| T-SUB | SUB-1 weights | rank chosen in the swap sheet | distribution | top-1 chosen < 40 % over ≥ 10 swaps → flag |
| T-HOLD | `setcredit` hold factors | e1RM or hold progress of groups whose volume is mostly holds | review only (weak-evidence constants, C8) | never automatic |

**Output.** `tuner_report.md` (tables per rule, evidence, sample sizes, before/after), `proposed-params-<version>.json`
(only changed keys, each with the reason and the data window), and a replay diff (§20.3) of the last 8 weeks under the
proposal.

### 20.2 `sim` — SimulationHarness.v1

**Purpose.** Fast offline regression testing of the deterministic algorithms over long synthetic histories, invariant
checking, and golden snapshots. Runs on the JVM in seconds (`gradlew :sim:run --args="simulate --persona all --weeks
26 --seeds 1..50"`).

**Synthetic athlete** (tech-stack lens, all constants assumptions to be replaced by fitted values after about 8 weeks
of real logs): per exercise a true e1RM and a personal rep-curve constant; reps achieved = min(prescribed, true reps to
failure − fatigue drop per set); reported RIR = clamp(round(true RIR + N(0, SIGMA_RIR = 1.0)), 0, 5) (Halperin 2022);
adaptation `e1RM += G · effective sets · (1 − e1RM / CAP)`, detraining after 10 missed days; adherence probability;
overrides with reasons (10 %), occupied stations (5 % per exercise), skipped sessions; calendar variability (challenge
sessions moved ± 1 day, 30 % of weeks); runs for the runner persona; rare pain events (elbow 1 per 12 weeks for the
OAP persona) to exercise the safety path; weeks spanning DST changes (2026-10-25, 2027-03-28). The RNG is
`SplittableRandom(seed)` in `:sim` only; `:algo` stays RNG-free.

**Personas.**

| Persona | Profile | Goals and challenge | Visits | Preferences |
|---|---|---|---|---|
| `oap_user` (this user) | BW 70.7 kg, training age intermediate (assumed onboarding answer; skipped → novice, C64), weighted pull-up C 20 / A 22.5 / B 27.5 kg, negatives 47.5 kg, pins A 40 / C 50 / 90° hold 35 lb, rep-curve k = 30 class prior at cutover (no historical set rows feed RCM-1, C59) | OAP active from block 1 week 5 (test Sun 2026-10-11), balanced preset (lats, biceps, forearms covered) | max 4 | leg press favourite, walking lunge disliked; s_pref 3, reps 8-10 |
| `friend_2x12` | BW 75 kg, novice | balanced, no challenge | 3 | s_pref 2, reps 12 (triggers the WarningEngine set-preference input, D10) |
| `legs_only` | BW 62 kg, intermediate (no sex or age defaults, D3) | `legs_only` preset | 3 | hip thrust favourite |
| `no_legs` | BW 80 kg, advanced | `no_legs` preset | 4 | |
| `pull_focus` | BW 72 kg, intermediate | `pull_focus` preset (push + legs maintained), once without and once with the OAP challenge | 4 | |
| `runner` | BW 68 kg, novice | balanced; 2 calendar runs per week (one easy 40 min, one intervals 50 min), running priority medium | 3 | |

**Invariants (must have 0 violations).**

1. Determinism: the same inputs give byte-identical outputs (all modules; inputs shuffled where order should not matter).
2. No gym session on a contract `restDayBefore` date; no general session with a forbidden session tag on gap days (C11); on gap days no pull exercise outside the C50 light-pull filter; at most one gym visit per day, extra work on a challenge day only as an add-on (C50).
3. No exercise with a hard-window group or region coefficient ≥ 0.5 in a block overlapping a hard window (C12).
4. Gym visits per ISO week ≤ the user's maximum (D5); add-ons never count as visits.
5. Skip groups receive 0 FS from exercises with coefficient ≥ 0.5; covered groups receive 0 general direct FS (D6).
6. `never` exercises are never auto-selected; disliked exercises appear only with `ONLY_OPTION` or when the user added them (C26).
7. No group with R < 0.5 at session start is allocated sets.
8. Every day without a gym item has exactly one rest reason.
9. Safety: with an active elbow flag (severity ≥ 2), no exercise with `js_elbow_medial ≥ 2` is planned; injury mode removes the challenge's forbidden tags.
10. DST: weeks crossing 2026-10-25 have 7 local dates; elapsed-hour quantities are computed from Instants (25-hour Sunday).
11. Tie-band choices are stable under input permutation.
12. (Prescription invariants owned by the prescription document: loadable loads only; Δload within caps for general items, while a challenge RULE-mode load above the cap raises WE-S2 at WARN and is logged (D13, C45) and DOUBLE mode may advance one loadable step (C45); `no_time` / `occupied` never change progression state.)

**Metrics.** Attainment per group and goal, session minutes versus budget, rest days per week, distribution of session
types, add-ons per week, plan churn, unlock frequency, warnings per week.

**Golden snapshots.** `sim/src/test/resources/golden/<case>.json` with `{inputs, expected outputs, expected trace}`;
`-PupdateGoldens` rewrites; diffs are reviewed in git.

### 20.3 `replay`

**Purpose.** Re-run logged decisions under a different `ENGINE_VERSION` and/or params and report the differences, so
that a proposed change can be judged on the user's real history before it ships.

```
replay(from, to, moduleFilter, engineVersionB, paramsB):
  for snapshot in fitness.recommendations where created_at in [from, to] and module ∈ moduleFilter (ordered by created_at, id):
     inputs = snapshot.inputs ?: reconstructFromFacts(snapshot)          // pruned inputs are rebuilt from facts
     assert JCS-SHA256(inputs) == snapshot.inputs_hash (when inputs are present)
     outA = snapshot.outputs; outB = Engine[engineVersionB].module(inputs, paramsB)
     diff = structuralDiff(outA, outB)                                    // e.g. session type, per-group FS, exercise and set changes, chip changes
  report: counts per diff kind, the 20 largest diffs with context, and per-week summaries
```

CI runs `replay` on the golden fixtures (§24) with the current engine and params: the diff must be empty unless the
change is intentional and the goldens are updated in the same commit.

---

## 21. Worked example: the real week 2026-W41 (Mon 5 to Sun 11 October 2026)

All numbers below were produced by the reference prototype of this specification (`week2.py`, `sel_k.py`; for version
1.1.0 re-run as `week3.py` and `sel_w41n.py`; §26) from the fixture F-W41 of §24. They are the expected values of the
golden tests, up to the catalog differences noted in §22.

**What changed in version 1.1.0.** Two decisions changed the result for this week. Calendar gym events are training
time, so the add-on after Thursday's deload session A has 34 minutes instead of 19 (C49). On gap days only light pull
is allowed, so upper back has no direct exercise on Wednesday or Friday (every row has a biceps coefficient of 0.5,
C50). D15 confirmed that there is no whole-body protection term, as version 1.0.0 already assumed. With the larger
add-on, the best week puts a legs add-on
after session A on Thursday and the general session on **Friday**. A Wednesday session cannot be combined with a legs
add-on on Thursday, because both would be heavy-legs sessions 25 hours apart (H6), and the best Wednesday plan is 0.12
lower, outside the 0.05 tie band (C52). The expected Today card for Wednesday 7 October is a soft rest day with
"Train anyway" options (§21.9). Version 1.0.0 recommended a Wednesday session.

### 21.1 Fixture facts and stated assumptions

| Item | Value | Source |
|---|---|---|
| Today, now | Wed 2026-10-07, 12:00 Europe/Amsterdam (CEST, UTC+2) | task |
| Calendar (primary) | Tue 2026-10-06 19:45-21:15 "Gym — sessie C (volume en techniek)"; Thu 2026-10-08 19:45-21:15 "Gym — sessie A (één arm zwaar)"; Sun 2026-10-11 13:00 "Gym — testdag 1 (meten)" (end 15:00 assumed: TEST books 120 min); Mon 19:30 "Schaken"; stale all-day "Gym" items in the Todoist calendar | DECISIONS §4, research |
| Challenge | OAP block 1 week 5: deload + test week (`isDeloadWeek`; mode `deload`, or `test_pending` if P19 is approved); slots this week C, A, TEST | DECISIONS §4 |
| Body and loads | BW 70.7 kg; weighted chin-up C 20 / A 22.5 / B 27.5 kg; negatives 47.5 kg; assisted pins A 40 / C 50 / 90° hold 35 lb | task |
| Profile (assumptions until onboarding answers, D6) | preset `balanced`; training age intermediate (BASE 12; the assumed onboarding answer, given the user's loads; if onboarding is skipped the default is novice, C64, see §21.11); max visits 4 (D5); availability weekdays 19:45-21:15, weekends 09:00-16:00; default budget 75 min without a calendar event, calendar gym events are training time with a 5-min changeover (F11 revised, C49); running priority low, no runs this week; no pain events; no P rules approved yet | assumptions |
| Logged so far | Tue C (deload): assisted one-arm 2 × 5/arm at 50 lb RIR 2; weighted chin-up 2 × 5 at +20 kg RIR 1; one-arm scapular pulls 2 × 3/arm RIR 2; one-arm hangs 2 × 10 s/arm felt OK; prehab 1 set each; daily two-arm hang Mon and Tue (2 sets, deload); one-arm plank Tue (2 × 20 s/side) | fixture |
| App history | No general training logged → every non-challenge group has `daysSinceTrained = ∞` → re-entry (C27) | fixture |
| Committed | Thu A (deload): assisted 3 × 3/arm at 40 lb RIR 1; one 5-s calibration negative per arm; weighted chin-up 2 × 4 at +22.5 kg RIR 1; top holds 2 rounds × 4 attempts/arm; 90° holds 2 × 7 s/arm at 35 lb; straight-arm holds 2 × 6 s/arm; prehab; daily hang Wed, Fri, Sat, Sun; plank Thu; TEST Sun | definition (deload transform) |

### 21.2 Calendar classification (§1)

| Event | Normalised title | Features (score) | Result |
|---|---|---|---|
| Tue 19:45 | `gym - sessie c (volume en techniek)` | title_kw 3, challenge_title 2, duration 1 (= 6; + location 3 when the event has the Basic-Fit location) | `challenge_session(C)`, accepted by `challenge.calendar.matchSlots` (≈ 0.9 with the challenge lens's weights: gym 0.2 + title 0.6 + duration 0.1), method `title_pattern`, unmarked → "Adopt" offered |
| Thu 19:45 | `gym - sessie a (een arm zwaar)` | same | `challenge_session(A)`, ≈ 0.9 |
| Sun 13:00 | `gym - testdag 1 (meten)` | same | `challenge_session(TEST)`, ≥ 0.8 (Sunday is the preferred test weekday) |
| Mon 19:30 | `schaken` | neg −5, duration 1 (= −4) | `busy` |
| Todoist all-day "Gym" | | | not read (calendar not allowlisted), or `ignored(todoist)` |

The three challenge events are unmarked, so the app offers "Adopt" and never edits them before that (D11, C37). Each
19:45-21:15 event books 90 minutes of training time, so its session budget is 85 minutes (C49).

### 21.3 Targets (§5)

Covered by the OAP challenge (D6): lats, biceps, forearms. Improve, `T = 12`, `W = 1.0`: chest, side_delts, rear_delts,
upper_back, triceps, quads, hamstrings, glutes. Maintain, `T = 4`, `W = 0.6`: front_delts, upper_traps, abs,
lower_back, abductors, adductors, calves. Deload from the challenge (F8, C13): `targetEffective` 6 (improve) and 2
(maintain), sets × 0.5, RIR + 2. Re-entry for every non-challenge group (sets × 0.6, RIR + 1) does not stack with the
deload (C51, §5.4.3): factor 0.5, RIR + 2. Prescribed RIR this week: 2 + 2 = 4. With training age unanswered the
engine uses novice (C64): improve `T = 10`, maintain `T = 3` (§21.11).

### 21.4 Accounting and readiness (§3, §4)

Vhyp by Wed 12:00 (done) and committed for the rest of the week (challenge sessions and habits):

| Group | Done | Committed | Before any general session |
|---|---|---|---|
| rear_delts | 0.90 | 0.90 | 1.80 |
| upper_back | 1.40 | 0.45 | 1.85 |
| abs | 1.45 | 2.98 | 4.42 (≥ T 4 → met) |
| lats / biceps / forearms (covered) | 4.85 / 1.95 / 5.25 | 6.45 / 3.48 / 6.33 | exempt from MRV warnings |
| every other group | 0 | 0 | 0 |

Readiness (projected, no general session yet):

| Time | Pull groups | Push and legs | Tissues | G |
|---|---|---|---|---|
| Wed 19:45 | lats 0.73, biceps 0.88, forearms 0.63, rear_delts 0.89, upper_back 0.87, abs 0.86 | all 1.00 | elbow 0.65, grip 0.39 | 0.86 |
| Thu 19:45 (A) | lats 0.89, biceps 0.96, forearms 0.88 | all 1.00 | elbow 0.79 | 0.90 |
| Fri 19:45 (after A) | lats 0.52, biceps 0.70, forearms 0.50, rear_delts 0.85, upper_back 0.88, abs 0.71 | all 1.00; with the Thursday legs add-on quads 0.86, hamstrings 0.85, glutes 0.94, adductors 0.95 | elbow 0.11, grip 0.05 (knee 0.90 with the add-on) | 0.79 (0.74 with the add-on) |
| Sun 13:00 (TEST) | lats 0.90, biceps 0.95, forearms 0.90 | all 1.00 | elbow 0.62, grip 0.56 | 0.90 |
| Sun 13:00 (TEST), with the chosen plan (§21.7) | lats 0.90, biceps 0.95, forearms 0.90 | chest 0.86, hamstrings 0.89, quads 0.91, all others ≥ 0.90 | elbow 0.62, grip 0.56 | 0.76 |

The elbow channel is below the 0.90 `tissueRMin` before A and before TEST because of the challenge sessions
themselves; the marginal penalty (§8.6) charges none of this to general candidates. The chosen plan leaves the
muscles and tissues that the test uses unchanged; it lowers the whole-body readiness `G` at the test from 0.90 to 0.76,
which costs nothing in `J` because v1 has no systemic protection term (D15, C50).

### 21.5 Day model and constraints (§8.4, §8.5)

| Day | Status | Constraints for general work |
|---|---|---|
| Mon 5 | past | chess evening (busy) |
| Tue 6 | fixed: C done | |
| Wed 7 (today) | free, start 19:45, budget 75 min (default; no calendar event) | the day before A, allowed for legs / push (D15, C50); gap day between C and A (C11): no `pull_heavy`, `grip_heavy`, `elbow_flexor_heavy` session, and pull work only under the C50 light-pull filter (reverse pec deck yes, rows and shrugs no); hard window from 19:45 (24 h before A, C12): no lats, biceps, forearms, brachialis exercises (coefficient ≥ 0.5); penalty window from 13:45 (30 h before A), muscles and elbow only |
| Thu 8 | fixed: A (event 90 min, budget 85 min) | add-on slot after A: capacity floor(90 − 50.7 − 5) = 34 min (C49), max 4 sets, RIR ≥ 2, patterns legs / push / core, no owned groups, grip demand ≤ 1 |
| Fri 9 | free, start 19:45, budget 75 min (default) | gap day after A (C11) with the same C50 light-pull filter; penalty window from Fri 13:00 (48 h before TEST); H6: no heavy-legs session within 40 h of a heavy-legs add-on on Thursday |
| Sat 10 | blocked | rest day before TEST (C11, all gym; a run would only warn) |
| Sun 11 | fixed: TEST 13:00 | no add-on |

Visits: done 1 (C) + fixed 2 (A, TEST) → at most 1 general visit (`kMax = 1`, D5). Plans evaluated: (1 + 2 × 8) × 4
add-on options = 68.

### 21.6 Planner results (§8.6)

| Plan | Σ W·U | Frequency bonus | Costs | J |
|---|---|---|---|---|
| No general session, no add-on | 1.1274 | 0 | 0 | 1.1274 |
| Wed FULL, no add-on | 4.2957 | 0 | −0.20 | 4.0957 |
| **Fri FULL + A legs add-on** (J*, chosen) | 4.7829 | +0.30 (quads, hamstrings reach 2 sessions) | −0.30 | **4.7829** |
| Wed FULL + A legs add-on | | | | violates H6 (4.83 credited heavy-legs FS on Wednesday and in the add-on, 25 h apart); without H6 it would score 4.7194, still outside the band |
| Wed FULL + A push add-on (best Wednesday plan) | 4.6643 | +0.30 (chest, side delts reach 2 sessions) | −0.30 | 4.6643 |
| Wed PUSH+LEGS + A push add-on | 4.6593 | +0.30 | −0.30 | 4.6593 |
| Fri FULL + A push add-on | 4.6611 | +0.15 (chest) | −0.30 | 4.5111 |
| Fri PUSH+LEGS + A push add-on | | | | 4.4711 |

The tie band is [4.7329, 4.7829] and holds only the chosen plan: **Thursday legs add-on after A, Friday FULL**. The best
Wednesday plan (with a push add-on, because H6 rules out the legs add-on) is 0.1187 lower. Even without H6, the
Friday plan would beat Wed FULL + legs add-on by 0.0635, because there the add-on comes first: the Friday allocation
sees the leg volume already done, gives the legs only what is left (quads and hamstrings 1.5 each, against 2 each on
Wednesday) and spends the rest of the budget on chest and rear delts (4.5 and 2.5 FS against 4 and 1.5), which spreads
the week's volume more evenly, and the square-root utility rewards that (§5.4). Protection penalties are 0 for every plan above (legs,
push and rear-delt work do not change the deficits of lats, biceps, forearms or the elbow channel at A or TEST), and
no plan pays for whole-body fatigue (D15, C50).

### 21.7 Expected week plan

| Day | Recommended | Calendar | Write policy (D11) | Reason / note |
|---|---|---|---|---|
| Mon 5 | — | Schaken 19:30 (busy) | never | `PAST_DAY` |
| Tue 6 | ✓ OAP session C (done, deload) | sessie C 19:45, unmarked | `auto` after "Adopt" | |
| Wed 7 (today) | Rest | — | never | `MAX_VISITS` in the plan (4 visits = your maximum); free for legs / push / core and light pull (gap day, C50); Today shows a soft rest with `BETTER_OTHER_DAY` Fri and "Train anyway" (§21.9) |
| Thu 8 | OAP session A (deload) + **legs add-on** ~14 min (leg press 2, seated leg curl 2; capacity 34 min, set cap 4) | sessie A 19:45, unmarked | session `auto` after "Adopt"; add-on line only in a marked event | `ADDON` |
| Fri 9 | **Legs + push + rear delts**, 19:45, ~50 min, deload + first exposure (half sets, RIR 4) | — | `on_plan_it` | `VOLUME_DEFICIT` chest 0 → 3.6 / 6 |
| Sat 10 | Rest (blocked) | — | never | `REST_BEFORE` test Sun 13:00 (OAP `restBefore`) |
| Sun 11 | OAP test day 13:00 | testdag 13:00, unmarked | `auto` after "Adopt" | weigh in first (C32) |

`recommendedN = 4` visits (3 challenge + 1 general) plus 1 add-on. If the user taps "Plan it", the Friday session is
written as `Gym — Legs + push + rear delts`, 19:45-20:40 (49.6 + 5 min, rounded up to 55 min), with the marker
`[fitapp:v1 <recommendationId> gen/2026-W41/5]` as the last description line (C37, §8.9). Next week (W42, block 2 week 1) is provisional:
`challenge.plan` places C, A, B (preferred Wed, Fri, Sun 11:00 unless the calendar says otherwise) and
`challenge.calendar` writes them as marked events (D11).

### 21.8 The Thursday add-on and the Friday session

**Thursday legs add-on after A (§17.6, C34).** Capacity 34 min (90 − 50.7 − 5, floored), at most 4 sets, RIR ≥ 2, no
second general warm-up. Allocation (`ADDON(LEGS)`): `normal` quads 4, hamstrings 5.5, abductors 1, calves 1 (32.5 min)
→ after the transform and the 4-set cap `direct` quads 2, hamstrings 2 (credit glutes 0.83, adductors 0.5), 13.8 min.
SEL-1 with the slot's patterns and 31 min of exercise budget (34 − 3 specific warm-up): 45° leg press × 2, seated leg
curl × 2 (10.8 min + specific warm-up ≈ 13.8 min), RIR 4 (deload + re-entry; ≥ the slot's minimum 2). The set cap
binds, not the minutes.

**Friday allocator (§7).** Readiness at Fri 19:45 includes the add-on (quads 0.86, hamstrings 0.85; rear delts 0.85
after A), all ≥ `R_FULL`, so no set is scaled down. `alloc` (FS before indirect credit and time fit): chest 10,
front_delts 4, side_delts 10, rear_delts 10, triceps 10, lower_back 4, glutes 10, abductors 4, adductors 4, quads 10,
hamstrings 10, calves 4; dropped: upper_back and upper_traps (`NO_ELIGIBLE_EXERCISE`: on a gap day every row has a
biceps coefficient of 0.5 and every shrug a grip demand of 2, C50), abs (`TARGETS_MET`); lats, biceps and forearms are
covered by the challenge. `normal` after indirect credit and the 75-min time fit: chest 8.5, side_delts 3.5,
rear_delts 5, lower_back 1, abductors 1, quads 2.5, hamstrings 3, calves 1 (74.8 min). `direct` after the transform
(× 0.5, minimum 1): chest 4.5, side_delts 2, rear_delts 2.5, lower_back 1, abductors 1, quads 1.5, hamstrings 1.5,
calves 1; expected credit including indirect: front_delts 2.25, triceps 2.25, upper_back 1.25, glutes 0.62, adductors
0.38; estimated 49.6 min. Session tags: credited lats + upper_back = 1.25 < 4, no grip-heavy or elbow-flexor-heavy
work → the gap rule holds. Heavy-legs check (H6): credited quads + hamstrings + glutes = 3.62 < 4, so the session may
follow the Thursday add-on (4.83) after 23 hours.

**SEL-1 (§13) and ORD-1 (§14)** on the prototype catalog (§22.4) with the gap-day filters (RDL and walking lunge
removed for grip demand 2; rows removed for their biceps coefficient 0.5, C50; shrugs removed), `sPref` 3 and `mult`
0.5 (set bounds 1-2), budget 62 min of exercise time (75 − 10 general warm-up − 3 specific warm-up):

| # | Exercise | Sets | Minutes | Note |
|---|---|---|---|---|
| 1 | Incline dumbbell press | 2 | 5.0 | liked; upper-chest region |
| 2 | 45° leg press | 2 | 6.2 | favourite |
| 3 | Calf press on the leg press | 1 | 2.4 | chained to the leg press (same station) |
| 4 | Machine chest press | 2 | 4.9 | liked |
| 5 | Dip (bodyweight) | 1 | 2.1 | chest and triceps; bodyweight only, not a loaded dip |
| 6 | Reverse pec deck | 2 | 4.7 | rear delts; light pull (grip demand 1, no biceps), C50 |
| 7 | Dumbbell lateral raise | 2 | 4.4 | liked |
| 8 | Seated leg curl | 2 | 4.7 | liked; long-length hamstrings |

14 sets, 34.3 min of exercise + about 13 min of warm-up ≈ 47 min. Coverage (FS): chest 5, triceps 2.75, front_delts
2.5, side_delts 2, rear_delts 2, upper_back 1, quads 2, hamstrings 2, glutes 1, adductors 1, calves 1. The maintain
riders lower back and abductors (1 FS each in the allocation) were not worth their minutes at weight 0.6. Every
exercise is a first exposure: RX-1 prescribes a calibration first set (C27, C57) at RIR 4.

**Wednesday, if the user trains anyway.** The best plan with a Wednesday session is Wed FULL + a **push** add-on after A
(J 4.6643): a legs add-on would break H6. The Wednesday allocation is `normal` chest 8, side_delts 3.5, rear_delts 3,
lower_back 1, abductors 1, quads 3.5, hamstrings 4, calves 1 (74.6 min) → `direct` chest 4, side_delts 2, rear_delts
1.5, lower_back 1, abductors 1, quads 2, hamstrings 2, calves 1 (48.6 min), and SEL-1 gives incline dumbbell press 2,
leg press 2, calf press 1, machine chest press 2, reverse pec deck 2, lateral raise 2, seated leg curl 2, overhead
cable triceps extension 1 (14 sets, 34.7 min). The Thursday add-on becomes chest 2 + side delts 2 (12.3 min), and
Friday becomes a `MAX_VISITS` rest day.

### 21.9 Expected Today recommendation for Wed 2026-10-07

`today` (§9): no challenge session today, no calendar event, no hard rule. `J_REST` (the best plan with no general
session today) = 4.7829, the chosen plan (Thu legs add-on, Fri FULL). The chosen plan does not train today, so the
verdict is **`REST_SOFT`** with `BETTER_OTHER_DAY {type "Legs + push + rear delts", day Fri}`, and the options are the
"Train anyway" list:

| Option | Best plan with today = type | J | dJ | score | Label, minutes |
|---|---|---|---|---|---|
| FULL ("Train anyway" default) | + A push add-on | 4.6643 | −0.1187 | 0.441 | Legs + push + rear delts, ~49 min |
| PUSH+LEGS | + A push add-on | 4.6593 | −0.1236 | 0.438 | Legs + push, ~48 min |
| PULL+LEGS | + A push add-on | 4.3315 | −0.4514 | 0.274 | Legs + rear delts, ~47 min |
| UPPER | + A legs add-on | 4.3029 | −0.4801 | 0.260 | Push + rear delts, ~45 min |
| PUSH | + A legs add-on | 4.0396 | −0.7434 | 0.128 | Push, ~44 min |
| LEGS | + A push add-on | 4.0331 | −0.7499 | 0.125 | Legs, ~44 min |
| PULL | + A push add-on | 2.7983 | −1.9846 | −0.492 | Rear delts (rear delts and lower back only), ~29 min |
| CORE | infeasible | | | | abs met; lower back alone < 3 FS |

`ui.todaycard` (§19.1): state **REST, soft**, no primary Start button. Chips (precedence class, then weight): ["A Thu
19:45"] (`NEXT_FIXED`, CHALLENGE) ["Legs + push + rear delts fits better on Fri"] (`BETTER_OTHER_DAY`, GENERAL). Not
today: "Pull: lats, biceps and forearms are protected; Session A is tomorrow 19:45" (`PROTECT_REST`) and "Upper back:
light pull only between C and A" (`GAP_DAY`, C50). "Train anyway" offers "Legs + push + rear delts ~49 min", "Legs +
push ~48 min", "Legs + rear delts ~47 min", with the time chips 45 / 60 / (75) / 90+ (no calendar event today). The
week strip and the Week tab show Friday as the recommended day; "Plan it" writes it (§21.7). The scores sit below 0.5
because training today is worse than the Friday plan: a Wednesday session takes the legs out of Thursday's add-on.

### 21.10 The other days' Today cards

The days after Wednesday assume that the user rested on Wednesday and did Thursday as planned.

| Day | Card | Content |
|---|---|---|
| Thu 8 | PLANNED (challenge), verdict `TRAIN` | "Gym today 19:45 · OAP Session A", "Deload week: sets halved, loads kept", primary "Session A + legs add-on (~14 min)" (score 0.839); alternatives "Session A + push add-on" (0.703), "Session A only" (0.500); budget chip "85 cal" (90 − 5, C49) |
| Fri 9 | OPTIONAL **clear**, verdict `TRAIN` (PLANNED general if "Plan it" was tapped; then the budget chip is "50 cal", 55 − 5) | primary "Legs + push + rear delts ~50 min" (1.840); alternatives "Push + rear delts" (1.599), "Push" (1.469); clear because 1.599 < 1.840 × 0.90; chips [Deload week] [Gap day next to A: light pull only] [Chest 0 → 3.6 / 6]; not today: legs-heavy types (`HEAVY_LOWER_GAP`, the Thursday add-on 23 h earlier) |
| Sat 10 | REST (hard), verdict `REST_HARD` | "Test day tomorrow 13:00. OAP rule: no gym the day before a test." |
| Sun 11 | PLANNED (test), verdict `TRAIN` | "Test day today 13:00", weigh in before the warm-up; no budget chips (fixed protocol) |

### 21.11 Sensitivity (what would change the recommendation)

| Change | Planner result |
|---|---|
| Training age unanswered → novice (BASE 10, the C64 default) | Wed FULL + A push add-on, J* 5.2453, is chosen. Wed PUSH+LEGS (chest 4, side 2, quads 2.5, hamstrings 3, abductors 1, calves 1, lower back 1; 48.7 min) + A push add-on (chest 1.5, side 1.5, triceps 1), J 5.2084, is in the band with the same first session and session count, so the higher-`J` key of the amended C52 picks FULL (before the amendment the lexicographic key picked PUSH+LEGS; OI-19, resolved). The best Friday plan (4.9406) is outside the band. Today: verdict `TRAIN`, primary "Legs + push + rear delts" (0.652), "Legs + push" (0.634) inline as a close alternative (test `noviceCloseCall`) |
| Preset `legs_only` | Wed LEGS (quads 3, hamstrings 4, abductors 2, adductors 1.5, calves 2, lower back 1; 46.3 min) + A legs add-on (quads 1, hamstrings 1, abductors 0.5, adductors 0.5, calves 1; 13.3 min), J 2.9162; the Friday equivalent is 0.0012 lower and the earlier session wins (C52) |
| Preset `no_legs` | Wed UPPER (chest 5, side 3.5, rear delts 4, lower back 1; 44.6 min) + A push add-on (chest 1.5, side 1.5, triceps 1; 12.4 min), J 3.9774; best Friday plan 3.8248 |
| Preset `pull_focus` | Wed FULL (chest 2, front 1, side 1, rear delts 5, lower back 1, abductors 1, adductors 1, quads 1, hamstrings 1.5, calves 1; 49.9 min) + A legs add-on (13.3 min), J 3.9910; lats and biceps stay covered by the challenge, and upper back gets no direct sets on a gap day (C50), only the indirect credit of the reverse pec deck |
| Max visits 5 | Wed FULL (chest 4, side 2, rear delts 1.5, quads 2, hamstrings 2, …; 48.6 min) + A push add-on (chest 2, side 2) + Fri FULL (chest 2.5, rear delts 3.5, quads 2.5, hamstrings 3, lower back 1, abductors 1, calves 1; 48.8 min), J 6.3453 |
| A add-on capacity 19 min (the pre-C49 value with the old 15-min buffer) | J* is Fri FULL + A push add-on (4.6659); Wed FULL + A push add-on (4.6214) is inside the band and wins by the earlier-session key (C52), so Wednesday is a `TRAIN` day: FULL 0.478, PUSH+LEGS 0.471 (with a legs add-on), `ui.todaycard` OPTIONAL close (test `capacity19Variant`) |

Version 1.0.0 also showed a variant with a whole-body term in the penalty windows (`systemicRMin 0.85`). D15 and C50
removed that term from v1, so the variant is gone.

---

## 22. Prototype outputs: a LEGS day and a PUSH day

These are the outputs of the SEL-1 + ORD-1 + SUB-1 reference prototype with the canonical constants of this document
(`sel_k.py`), on the exercise-selection lens's fixture inputs. They differ slightly from the lens's own prototype run
because of three canonical rules: coefficients of 0.25 give no FS (§3), `OVER_CREDIT` is 0.10 (§13.6) and the lower set
bound is `sPref − 1` (not 2). They become golden tests (§24) once re-run on the seed rows; the prototype catalog (§22.4)
differs from the seed in a few coefficients (for example RDL grip 0.25 there versus `wrist_flexors_grip 0.5` in the
seed, and the back extension's pattern).

### 22.1 LEGS day

Inputs: targets quads 6, glutes 5, hamstrings 6 (improve); adductors 2, abductors 2, calves 3 (maintain); lower_back 0;
exercise budget 65 min; `sPref` 3; regions wanted rectus_femoris, hams_biarticular, gastrocnemius; preferences: leg
press favourite, leg extension and seated leg curl liked, walking lunge disliked; no history.

| # | Exercise | Sets | Minutes |
|---|---|---|---|
| 1 | 45° leg press | 4 | 12.3 |
| 2 | Calf press on the leg press (chained) | 3 | 6.8 |
| 3 | Romanian deadlift (barbell) | 2 | 6.5 |
| 4 | Hip thrust (Smith) | 2 | 6.7 |
| 5 | Seated leg curl | 4 | 8.8 |
| 6 | Leg extension | 2 | 4.6 |
| 7 | Hip abduction machine | 2 | 4.7 |

19 sets, 50.4 min of exercise (+ about 13 min warm-up), `J = 27.09`. Coverage FS: quads 6, glutes 5, hamstrings 6,
adductors 3, abductors 2, calves 3, lower_back 1. The disliked walking lunge is never used. (The lens prototype had leg
press × 3 and leg extension × 4; with 0.25 coefficients giving no FS, the hip thrust no longer credits quads, and the
leg press takes the extra set.)

Variants:

| Case | Result |
|---|---|
| Hip thrust occupied + lumbar flag severity 2 (RDL and back squat excluded) | leg press × 4, Bulgarian split squat × 2, back extension × 3, seated leg curl × 4, hip abduction × 2, calf press × 3 (18 sets, 46.9 min; glutes 5, hamstrings 5.5, lower_back 2.25) |
| Same, and the Bulgarian split squat disliked | the split squat is unlocked (`ONLY_OPTION` glutes: the only remaining direct glute option ≥ 0.75) but not chosen: leg press × 4, back extension × 4, seated leg curl × 4, leg extension × 2, hip abduction × 2, calf press × 3 (19 sets, 46.0 min; glutes 4.0 of 5) |
| Adductors set to improve (T 4), adduction machine disliked, lumbar flag | the adduction machine is unlocked (`ONLY_OPTION` adductors) and used × 2: leg press × 4, Bulgarian split squat × 2, back extension × 3, seated leg curl × 4, adduction × 2, abduction × 2, calf press × 3 (20 sets, 51.2 min; adductors 5.0) |

### 22.2 PUSH day

Inputs: targets chest 6, side_delts 4, triceps 4 (improve); front_delts 2 (maintain); exercise budget 60 min; `sPref` 3;
regions wanted chest_clavicular, triceps_long; liked: incline dumbbell press, machine chest press, pec deck, dumbbell
lateral raise, triceps pushdown.

| # | Exercise | Sets | Minutes |
|---|---|---|---|
| 1 | Incline dumbbell press | 3 | 7.5 |
| 2 | Machine chest press | 3 | 7.5 |
| 3 | Dumbbell lateral raise | 4 | 9.1 |
| 4 | Overhead cable triceps extension | 2 | 4.6 |

12 sets, 28.7 min (+ about 13 min warm-up), `J = 18.47`. Coverage FS: chest 6, front_delts 3, side_delts 4, triceps 5.
The shoulder press and the pec deck are left out: pressing already gives the front delts 3 FS and chest is at its
target. Variant: chest press and pec deck occupied → dumbbell bench press × 3, incline dumbbell press × 3, lateral raise
× 4, overhead triceps extension × 2 (28.6 min).

### 22.3 Substitution rankings (SUB-1)

| Occupied | Session already contains | Ranked substitutes (score, similarity) |
|---|---|---|
| 45° leg press | RDL, hip thrust, seated leg curl, leg extension, hip abduction, calf press | hack squat (0.934, 1.000), goblet squat (0.857, 0.996), Bulgarian split squat (0.848, 0.981) |
| Machine chest press | incline dumbbell press, lateral raise, overhead triceps extension | dumbbell bench press (0.934, 1.000), push-up (0.920, 0.977), decline dumbbell press (0.919, 0.975) |

Before these lists, SUB-1 first offers "do the next exercise now and come back" (reorder first). The prototype vectors
used group coefficients plus 0.5 × emphasised-region indicators; the rows above are regenerated with catalog 1.0.1's full
region vectors (§15) and pinned in `SelectionTest` (M3.6, 2026-10-28): the top 3 by score, no occupied stations reused.

### 22.4 Prototype catalog (fixture rows)

| id | group coefficients | pattern | rest s | setup s | reps | stretch | sfr | axial | grip | fixture preference |
|---|---|---|---|---|---|---|---|---|---|---|
| leg_press_45 | quads 1, glutes 0.5, adductors 0.5 | squat_machine | 150 | 90 | 10 | 1 | 5 | 0 | 1 | favorite |
| hack_squat | quads 1, glutes 0.5, adductors 0.5 | squat_machine | 150 | 90 | 8 | 2 | 4 | 1 | 1 | neutral |
| back_squat | quads 1, glutes 0.75, adductors 0.75, lower_back 0.25 | squat | 150 | 150 | 6 | 2 | 3 | 3 | 1 | neutral |
| walking_lunge_db | quads 1, glutes 0.75, adductors 0.5 | lunge | 150 | 60 | 10 | 2 | 3 | 1 | 2 | dislike |
| bulgarian_split_squat_db | quads 1, glutes 0.75, adductors 0.5 | lunge | 150 | 90 | 10 | 2 | 3 | 1 | 1 | neutral |
| leg_extension | quads 1 | knee_ext | 90 | 45 | 12 | 1 | 5 | 0 | 0 | like |
| seated_leg_curl | hamstrings 1 | knee_flex | 90 | 60 | 10 | 2 | 5 | 0 | 0 | like |
| lying_leg_curl | hamstrings 1 | knee_flex | 90 | 60 | 10 | 1 | 4 | 0 | 0 | neutral |
| rdl_barbell | hamstrings 1, glutes 0.5, adductors 0.5, lower_back 0.5, forearms 0.25 | hinge | 150 | 120 | 8 | 2 | 3 | 2 | 2 | neutral |
| hip_thrust_smith | glutes 1, quads 0.25, hamstrings 0.25 | hip_ext | 150 | 120 | 10 | 0 | 3 | 1 | 0 | neutral |
| hip_abduction_machine | abductors 1, glutes 0.25 | hip_abd | 90 | 30 | 15 | 1 | 5 | 0 | 0 | neutral |
| hip_adduction_machine | adductors 1 | hip_add | 90 | 30 | 12 | 1 | 5 | 0 | 0 | neutral |
| standing_calf_smith | calves 1 | calf | 90 | 60 | 12 | 2 | 4 | 1 | 1 | neutral |
| leg_press_calf | calves 1 | calf | 90 | 45 | 12 | 2 | 4 | 0 | 0 | neutral |
| back_extension_45 | lower_back 0.75, glutes 0.5, hamstrings 0.5 | hip_ext (prototype: own pattern) | 90 | 30 | 12 | 1 | 4 | 0 | 1 | neutral |
| bench_barbell | chest 1, front_delts 0.5, triceps 0.5 | horiz_push | 120 | 120 | 6 | 1 | 3 | 1 | 1 | neutral |
| db_bench | chest 1, front_delts 0.5, triceps 0.5 | horiz_push | 120 | 60 | 8 | 2 | 4 | 0 | 1 | neutral |
| incline_db_press | chest 1, front_delts 0.5, triceps 0.5 | incline_push | 120 | 60 | 8 | 2 | 4 | 0 | 1 | like |
| machine_chest_press | chest 1, front_delts 0.5, triceps 0.5 | horiz_push | 120 | 45 | 10 | 1 | 5 | 0 | 1 | like |
| smith_incline_press | chest 1, front_delts 0.5, triceps 0.5 | incline_push | 120 | 90 | 8 | 1 | 4 | 0 | 1 | neutral |
| pec_deck | chest 1, front_delts 0.25 | chest_fly | 90 | 30 | 12 | 2 | 5 | 0 | 0 | like |
| cable_fly | chest 1, front_delts 0.25 | chest_fly | 90 | 60 | 12 | 2 | 4 | 0 | 1 | neutral |
| dip_bw | chest 1, triceps 0.75, front_delts 0.5 | dip | 120 | 30 | 10 | 2 | 3 | 0 | 1 | neutral |
| machine_shoulder_press | front_delts 1, side_delts 0.5, triceps 0.5 | vert_push | 120 | 45 | 10 | 1 | 4 | 0 | 1 | neutral |
| db_lateral_raise | side_delts 1 | lateral_raise | 90 | 15 | 15 | 0 | 4 | 0 | 1 | like |
| cable_lateral_raise | side_delts 1 (unilateral) | lateral_raise | 90 | 45 | 15 | 1 | 5 | 0 | 1 | neutral |
| triceps_pushdown | triceps 1 | elbow_ext | 90 | 30 | 12 | 0 | 5 | 0 | 1 | like |
| overhead_cable_triceps_ext | triceps 1 | elbow_ext_overhead | 90 | 45 | 12 | 2 | 4 | 0 | 1 | neutral |
| reverse_pec_deck | rear_delts 1, upper_back 0.5 | rear_delt_fly | 90 | 30 | 15 | 1 | 5 | 0 | 1 | neutral |
| cable_rear_delt_fly | rear_delts 1, upper_back 0.5 | rear_delt_fly | 90 | 45 | 15 | 1 | 4 | 0 | 1 | neutral |
| face_pull_cable | rear_delts 1, upper_back 0.5 | rear_delt_fly | 90 | 45 | 15 | 0 | 4 | 0 | 1 | neutral |
| seated_cable_row_neutral | upper_back 1, lats 0.5, biceps 0.5, rear_delts 0.5, forearms 0.25 | horiz_pull | 120 | 45 | 10 | 1 | 4 | 0 | 1 | neutral |
| db_shrug | upper_traps 1, forearms 0.5 | shrug | 90 | 30 | 12 | 0 | 4 | 0 | 2 | neutral |
| captains_chair_knee_raise | abs 1 | core_flex | 60 | 15 | 12 | 0 | 4 | 0 | 0 | neutral |
| hanging_knee_raise | abs 1, forearms 0.5 | core_flex | 60 | 15 | 12 | 0 | 4 | 0 | 2 | neutral |
| cable_crunch | abs 1 | core_flex | 60 | 45 | 12 | 0 | 5 | 0 | 1 | neutral |

---

## 23. Params registry (all modules of this document)

Keys follow C41 and E53 (`<module>.<constant>`: the registry module id, dots allowed, then a lower snake_case
constant name); map- and list-valued constants are single keys with a JSON value. Every row goes into
`algo/src/main/resources/fitness/params/default-params-1.0.0.json` (C40, C68) with the entry spec of
DATA_MODEL 9.1.10 (`value, unit, min, max, evidence, source, tunable, tuned_by`). "Tuned by" names the logged data and
the tuner rule of §20.1; "review" means the value is changed only by a reviewed decision, never by a tuner proposal
alone. [this doc] marks values chosen here (also in §25). Every row whose evidence is `weak` or `expert-opinion`, or
whose source is [this doc], is an assumption and carries `tunable: true` in the params JSON; rows that are
`engineering-fact` with "—" in the last column are fixed design constants (`tunable: false`).

### 23.1 `calendar.classify`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `calendar.classify.w_title` | 3 | 0-6 | engineering-fact (design) | tech-stack lens | corrections, T-CAL |
| `calendar.classify.w_loc` | 3 | 0-6 | engineering-fact | tech-stack lens | T-CAL |
| `calendar.classify.w_challenge_title` | 2 | 0-6 | expert-opinion | [this doc]; UX lens UI-08 (+2) | T-CAL |
| `calendar.classify.w_desc` | 1 | 0-3 | engineering-fact | tech-stack lens | T-CAL |
| `calendar.classify.w_allday` | −4 | −10-0 | engineering-fact | tech-stack lens (only if all-day is not ignored) | T-CAL |
| `calendar.classify.w_dur_ok` | 1 | 0-3 | engineering-fact | tech-stack lens | T-CAL |
| `calendar.classify.w_dur_bad` | −1 | −3-0 | engineering-fact | tech-stack lens | T-CAL |
| `calendar.classify.w_neg` | −5 | −10-0 | engineering-fact | tech-stack lens | T-CAL |
| `calendar.classify.thresh` | 3 | 1-6 | engineering-fact | tech-stack lens | T-CAL |
| `calendar.classify.dur_min` | 30 min | 10-60 | engineering-fact | tech-stack lens | T-CAL |
| `calendar.classify.dur_max` | 180 min | 90-300 | engineering-fact | tech-stack lens | T-CAL |
| `calendar.classify.high_score` | 6 | 3-10 | expert-opinion | [this doc] | T-CAL |
| `calendar.classify.confirm_score` | 0.8 | 0.6-1.0 | expert-opinion | critique (confirm below 0.8) | T-CAL |
| `calendar.classify.dedupe_minutes` | 120 | 30-240 | expert-opinion | planning lens | T-CAL |
| `calendar.classify.run_min_minutes` | 10 | 5-30 | engineering-fact | [this doc] | review |
| `calendar.classify.run_max_minutes` | 240 | 120-480 | engineering-fact | [this doc] | review |
| `calendar.classify.run_long_minutes` | 75 | 60-120 | weak | planning lens (long-run factor > 75 min) | T-RUN |
| `calendar.classify.link_window_h` | 3 | 1-6 | engineering-fact | C35 | review |
| `calendar.classify.refresh_debounce_ms` | 2000 | 500-10000 | engineering-fact | tech-stack lens | review |
| `calendar.classify.lex.*` | §1.5 | — | engineering-fact | §1.5 (EN + NL, D12) | T-CAL lexicon additions |

### 23.2 `safety`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `safety.lookback_days` | 28 | 14-56 | expert-opinion | [this doc] | pain recurrence, review |
| `safety.hold_days` | {"1": 7, "2": 14, "3": 14} | 3-28 | expert-opinion | OAP elbow protocol 14 d (A); UI-07 pain guard | pain recurrence after expiry, review |
| `safety.escalate_count` | 3 | 2-5 | expert-opinion | [this doc] | review |
| `safety.escalate_window_days` | 14 | 7-28 | expert-opinion | [this doc] | review |
| `safety.pain_free_min_days` | 3 | 1-7 | expert-opinion | OAP P-rule (3 pain-free days) | review |
| `safety.severity_map` | {"mild": 1, "moderate": 2, "sharp": 3} | — | engineering-fact | UX pain sheet; the chips are stored as pain scores 2 / 5 / 8 (C64), and a 0-10 score maps as 1-3 → 1, 4-6 → 2, 7-10 → 3 | — |
| `safety.elbow_check_severity` | {"1": 1, "2": 1, "3": 2} | — | expert-opinion | [this doc] | review |
| `safety.joint_forbid_tags` | table §2.4 | — | expert-opinion | [this doc]; critique (elbow main risk) | review |

### 23.3 `setcredit`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `setcredit.stim_rir` | [1.00, 1.00, 0.95, 0.90, 0.80, 0.50], 0 for RIR ≥ 6 | 0-1, non-increasing | moderate (shape) | C6; Robinson 2024 | review (e1RM trend versus volume) |
| `setcredit.fat_rir` | [1.35, 1.15, 1.00, 0.90, 0.80], 0.50 for RIR ≥ 5 | 0.3-2.0 | moderate (shape) | C6; Morán-Navarro 2017; Refalo 2023 | T-KAPPA residuals |
| `setcredit.hard_rir_max` | 4 | 3-5 | moderate | C6 | review |
| `setcredit.side_factor` | 0.5 | fixed | engineering-fact | C7 | — |
| `setcredit.min_hyp_coeff` | 0.5 | 0.25-0.75 | moderate | Pelland 2026 fractional counting; C44 (0.25 counts toward fatigue only) | review |
| `setcredit.hold_factor_long` | 0.5 | 0.25-1.0 | weak | C8; Oranchuk 2019 | T-HOLD (review only) |
| `setcredit.hold_factor_short` | 0.25 | 0-0.5 | weak | C8 | T-HOLD (review only) |
| `setcredit.hold_min_s` | 10 | 5-30 | weak | C8 | review |
| `setcredit.hold_near_max_sir` | 2 | 0-5 | weak | C8; HOLD-1 chips | review |
| `setcredit.kind_fat` | {"dynamic": 1.0, "eccentric_only": 1.3, "isometric": 0.6, "hang": 0.4} | 0.2-2.0 | expert-opinion | planning lens | T-KAPPA |
| `setcredit.ecc_fat` | 1.3 | 1.0-2.0 | moderate | eccentric damage; McHugh & Tetro 2003 | T-KAPPA |
| `setcredit.novelty` | {"over_21d": 1.5, "d14_21": 1.25} | 1.0-2.0 | moderate | repeated-bout effect (McHugh & Tetro 2003; Kwiecien 2020) | T-KAPPA |
| `setcredit.felt_to_q` | {"max": 0, "hard": 1, "ok": 3, "easy": 5} | — | expert-opinion | [this doc] (D9 chips) | review |
| `setcredit.q_missing` | 3 | 0-5 | expert-opinion | [this doc] | missing-RIR share, review |
| `setcredit.level_w` | [0, 0.1, 0.5, 1.0] | 0-1 | expert-opinion | [this doc] | pain reports versus tissue load, review |
| `setcredit.grip_w` | [0, 0, 0.5, 1.0] | 0-1 | expert-opinion | [this doc] | review |
| `setcredit.sys_w` | {"1": 0.4, "2": 0.7, "3": 1.0} | 0-1.5 | expert-opinion | planning lens | T-KAPPA (G versus performance) |
| `setcredit.hold_sys` | 0.5 | 0-1 | expert-opinion | [this doc] | review |
| `setcredit.sys_divisor` | 15 | 8-25 | expert-opinion | planning lens | review |
| `setcredit.trained_min_credit` | 1.0 | 0.5-2.0 | expert-opinion | [this doc] | review |

### 23.4 `readiness`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `readiness.tau_h` | {biceps, triceps, side_delts, rear_delts, forearms, upper_traps, calves, abs: 22; chest, front_delts, lats, upper_back: 26; quads, glutes, adductors, abductors: 30; hamstrings, lower_back: 34} | 12-60 | moderate (48-72 h range), values assumed | C5; Korak 2015, Morán-Navarro 2017, Soares 2015, MacDougall 1995; abductors C44 | via kappa, T-KAPPA |
| `readiness.f_ref` | 8 for chest, lats, upper_back, quads, glutes, hamstrings; 6 otherwise | 4-12 | expert-opinion | C5 | T-KAPPA |
| `readiness.window_h` | 240 | 168-336 | engineering-fact | planning lens | — |
| `readiness.tissue_tau_h` | 48 | 24-96 | weak | C5; Miller 2005 | pain versus tissue load, review |
| `readiness.tissue_ref` | 6 | 3-12 | expert-opinion | planning lens | review |
| `readiness.sys_tau_h` | 48 | 24-96 | weak | C5 | T-KAPPA |
| `readiness.sys_scale` | 0.5 | 0.2-1.0 | weak | planning lens | T-KAPPA |
| `readiness.r_exclude` | 0.5 | 0.3-0.7 | expert-opinion | C5 | T-KAPPA |
| `readiness.r_full` | 0.8 | 0.6-0.95 | expert-opinion | C5 | T-KAPPA |
| `readiness.r_target_improve` | 0.85 | 0.7-0.95 | expert-opinion | planning lens | review |
| `readiness.r_target_maintain` | 0.75 | 0.6-0.9 | expert-opinion | planning lens | review |
| `readiness.kappa_default` | 1.0 (per class t22, t26, t30, t34) | 0.7-1.5 | expert-opinion | C5 | T-KAPPA |
| `readiness.soreness_factor` | 0.12 | 0-0.25 | expert-opinion | planning lens | review |
| `readiness.soreness_points` | {"never_sore": 0, "healed": 1, "still_sore": 3} | — | expert-opinion | [this doc] (DATA_MODEL open issue 5) | review |
| `readiness.readiness_worse_g` | 0.90 | 0.7-1.0 | expert-opinion | [this doc] | readiness answers versus PI |
| `readiness.sleep_short_h` | 6 | 4-7 | expert-opinion | planning lens | review |
| `readiness.sleep_short_g` | 0.90 | 0.7-1.0 | expert-opinion | planning lens | review |
| `readiness.elbow_check_factor` | 0.10 | 0-0.25 | expert-opinion | [this doc] | review |
| `readiness.checkin_valid_h` | 12 | 4-24 | engineering-fact | [this doc] | — |

### 23.5 `targets`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `targets.base_by_age` | {"novice": 10, "intermediate": 12, "advanced": 14} | 6-20 | strong (≥ 10/week), moderate (12-20) | C14; ACSM 2026; Baz-Valle 2022 | T-TARGET |
| `targets.default_training_age` | "novice" | enum | expert-opinion | C64 | onboarding answer |
| `targets.focus_bonus` | 4 | 0-8 | expert-opinion | C14 | T-TARGET |
| `targets.special_improve` | {"front_delts": 6, "forearms": 6, "lower_back": 4, "adductors": 6, "abductors": 6, "upper_traps": 6} | 2-12 | expert-opinion | planning lens; abductors [this doc] | T-TARGET |
| `targets.mrv` | chest, lats, upper_back, quads, glutes, hamstrings 22; side_delts, rear_delts 24; biceps, triceps, calves, abs, front_delts, upper_traps, forearms, abductors, adductors 20; lower_back 16 | 10-35 | expert-opinion (RP; caps only) | C14; planning lens; others [this doc] | T-TARGET |
| `targets.maintain_min` | 3 | 1-6 | moderate | C14; Bickel 2011; Spiering 2021 | T-TARGET |
| `targets.maintain_divisor` | 3 | 2-9 | moderate | Bickel 2011 (1/3 and 1/9 doses) | review |
| `targets.maintain_min_60` | 6 | 3-10 | moderate | Bickel 2011 (older adults); Spiering 2021 | review |
| `targets.age_60` | 60 | — | moderate | Bickel 2011 | — |
| `targets.w` | {"focus": 1.5, "improve": 1.0, "maintain": 0.6} | 0-3 | expert-opinion | planning lens | T-ACCEPT |
| `targets.cap_s` | {"improve": 10, "maintain": 4} | 4-15 | weak | Remmert preprint (PUOS ≈ 11), C14, C29 | T-TARGET |
| `targets.min_touch` | {"improve": 2, "maintain": 1} | 1-4 | expert-opinion | planning lens | review |
| `targets.freq_target` | {"improve": 2, "maintain": 1, "maintain_60": 2} | 1-3 | strong (≥ 2/week) / moderate | ACSM 2026; Spiering 2021 | review |
| `targets.over_mrv_slope` | 0.5 | 0-2 | expert-opinion | planning lens | review |
| `targets.maintain_over_slope` | 0.05 | 0-0.2 | expert-opinion | planning lens | review |
| `targets.ramp_table` | P4 → −2; S4 or P3 → 0; S1 and P1 → +2; P ≤ 2 and S ≤ 2 → +1; missing → 0 | — | expert-opinion | RP via app-benchmarks A9; C14 | T-TARGET |
| `targets.perf_thresholds` | {"p1": 0.02, "p2": −0.01, "p3": −0.03} | — | expert-opinion | app-benchmarks A9 | review |
| `targets.soreness_scores` | {"never_sore": 1, "healed": 2, "still_sore": 4} | — | expert-opinion | [this doc] | review |
| `targets.ramp_cap_frac` | 0.20 | 0.1-0.3 | weak | C14; Camargo 2026 preprint (C29) | review |
| `targets.ramp_start_week` | 2 | 1-3 | expert-opinion | planning lens | review |
| `targets.deload_set_factor` | 0.5 | 0.3-0.7 | expert-opinion | C13; Bell 2023 Delphi; OAP artifact | review |
| `targets.deload_rir_offset` | 2 | 1-3 | expert-opinion | C13 | review |
| `targets.reactive` | {"key_exercises": 2, "e1rm_drop": 0.03, "consecutive_exposures": 2, "g": 0.6, "g_sessions": 2, "feeling_bad_sessions": 2} | — | expert-opinion | C13 (planning + STALL-1 global rules) | deload outcomes, review |
| `targets.reentry_gap_days` | 21 | 14-35 | moderate | C27; Ogasawara 2013; Bosquet 2013 | re-entry first-exposure residuals |
| `targets.reentry_set_factor` | 0.6 | 0.4-0.8 | expert-opinion | C27 | review |
| `targets.reentry_rir_offset` | 1 | 0-2 | expert-opinion | C27 | review |
| `targets.reentry_sessions` | 2 | 1-4 | expert-opinion | C27 | review |
| `targets.combine_rule` | "min_factor_max_offset" | enum | expert-opinion | C51 | review |
| `targets.maintain_load_frac` | 0.85 | 0.7-0.95 | moderate | Spiering 2021 (intensity kept) | review |

### 23.6 `runs`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `runs.per_min` | {"quads": 0.05, "calves": 0.06, "hamstrings": 0.03, "glutes": 0.03} | 0-0.2 | weak | planning lens | T-RUN |
| `runs.intensity` | {"easy": 1.0, "tempo": 1.5, "intervals": 1.8, "race": 2.0} | 0.5-3 | weak | planning lens | T-RUN |
| `runs.long_factor` | 1.3 | 1-2 | weak | planning lens | T-RUN |
| `runs.sys_per_40min` | 0.5 | 0.2-1.5 | weak | planning lens used 1.0; halved [this doc] | T-RUN |
| `runs.window_h` | 8 | 4-24 | moderate | Sporer & Wenger 2003 | T-RUN |
| `runs.run_before_pen` | 0.5 | 0-2 | expert-opinion | planning lens | T-RUN |
| `runs.run_after_pen` | 0.3 | 0-2 | expert-opinion | planning lens | T-RUN |
| `runs.hard_run_h` | 48 | 24-72 | moderate | Doma (24-48 h residual fatigue) | T-RUN |
| `runs.hard_run_pen` | 0.4 | 0-2 | expert-opinion | planning lens | T-RUN |
| `runs.priority_mult` | {"low": 1.0, "medium": 1.0, "high": 2.0} | — | expert-opinion | planning lens (high doubles) | review |

### 23.7 `allocator`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `allocator.min_total` | {"standalone": 3, "addon": 2} | 1-5 | expert-opinion | planning lens; add-on [this doc] | review |
| `allocator.indirect` | table §7.4 (ratio 0.5; shares 1.0 / 0.5 / 0.33) | ratio 0-1, share 0-1 | moderate (ratio), expert-opinion (shares) | Pelland 2026; Mannarino 2021; Kubo 2019 | T-ALLOC |
| `allocator.primary_order` | [chest, quads, hamstrings, lats, upper_back, front_delts] | — | engineering-fact | planning lens | — |
| `allocator.tissue_rate` | §7.6 | 0-1 | expert-opinion | [this doc] | T-ALLOC |
| `allocator.sys_rate` | §7.6 (default 0.4) | 0-1.5 | expert-opinion | [this doc] (consistent with `setcredit.sys_w`) | T-ALLOC |

### 23.8 `catalog`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `catalog.coeff_levels` | [0.25, 0.5, 0.75, 1.0] | fixed | moderate (0.5), expert-opinion (others) | C10; Pelland 2026 | review |
| `catalog.derived_tags` | rules §10.4 | — | expert-opinion | [this doc] | review |
| `catalog.sfr_defaults` | machine/cable isolation 5, machine compound 4.5, DB compound 4, barbell compound 3, heavy hinge 2, unilateral free weight 3 | 1-5 | expert-opinion | exercise-selection lens | review |
| `catalog.axial_defaults` | machine 0, standing DB / hip thrust 1, RDL / barbell row 2, back squat / deadlift / standing OHP 3 | 0-3 | expert-opinion | exercise-selection lens | review |
| `catalog.setup_defaults_s` | pin machine 40, plate machine 90, rack barbell 150, Smith 105, dumbbells 45, cable change 45, dip belt +60 | 0-600 | expert-opinion | exercise-selection lens | T-TIME (per user via `time`) |
| `catalog.validator` | {"min_per_group": 3, "min_direct_per_group": 1, "min_equipment_classes": 2, "min_sub_similarity": 0.6} | — | engineering-fact | exercise-selection lens | review |

### 23.9 `weekplan`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `weekplan.k_cap` | 5 | 1-7 | engineering-fact | [this doc] (complexity) | — |
| `weekplan.max_evals` | 1000000 | 1e5-1e7 | engineering-fact | [this doc] | — |
| `weekplan.tie_band` | 0.05 | 0-0.2 | expert-opinion | C52 (ties ordered earlier session, fewer sessions, higher J, lexicographic) | T-WEEK |
| `weekplan.freq_bonus` | 0.15 | 0-0.5 | strong (≥ 2/week), value expert | ACSM 2026; Schoenfeld 2016 | T-ACCEPT |
| `weekplan.protect_w` | 5 | 1-20 | expert-opinion | planning lens | challenge session outcomes after general sessions in windows |
| `weekplan.consec_w` | 0.5 | 0-2 | expert-opinion | planning lens | T-ACCEPT |
| `weekplan.consec_free` | 3 | 2-5 | expert-opinion | app-benchmarks A2 | review |
| `weekplan.session_cost` | 0.20 | 0-1 | expert-opinion | planning lens | T-ACCEPT |
| `weekplan.addon_cost` | 0.10 | 0-1 | expert-opinion | [this doc] | T-ACCEPT (add-ons accepted) |
| `weekplan.no_rest_w` | 0.3 | 0-1 | expert-opinion | planning lens | review |
| `weekplan.skip_w` | 0.3 | 0-1 | expert-opinion | planning lens | review |
| `weekplan.lowg_w` | 0.5 | 0-2 | expert-opinion | planning lens | T-KAPPA |
| `weekplan.g_low` | 0.55 | 0.4-0.7 | expert-opinion | planning lens | T-KAPPA |
| `weekplan.stability_w` | 0.05 | 0-0.2 | expert-opinion | [this doc] | T-WEEK |
| `weekplan.heavy_lower_fs` | 4 | 2-8 | expert-opinion | planning lens | review |
| `weekplan.heavy_lower_min_h` | 40 | 24-72 | expert-opinion | planning lens | review |
| `weekplan.min_slot_min` | 45 | 30-60 | engineering-fact | [this doc] (smallest time chip, F11) | — |
| `weekplan.busy_buffer_min` | 30 | 0-60 | expert-opinion | challenge lens | review |

### 23.10 `today`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `today.ui_scale` | 2.0 | 0.5-5 | engineering-fact | [this doc] | — |
| `today.transform_chip_weight` | 0.5 | 0-1 | engineering-fact | [this doc] | chip taps |
| `today.constraint_chip_weight` | 0.4 | 0-1 | engineering-fact | [this doc] | chip taps |
| `today.context_chip_weight` | 0.1 | 0-1 | engineering-fact | [this doc] | chip taps |
| `today.max_budget_min` | 180 | 90-300 | engineering-fact | F11 ("90+") | — |
| `today.reason_codes` | enum v1 (§0.6) | — | engineering-fact | F19 | — |

### 23.11 `pref`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `pref.mult` | {"never": 0, "dislike": 0.6, "neutral": 1.0, "like": 1.15, "favorite": 1.495} | 0-2 | weak (preference / autonomy evidence) | C26; Baz-Valle 2019 | T-PREF |
| `pref.w_add` | 0.10 | 0-0.3 | expert-opinion | exercise-selection lens | T-PREF |
| `pref.w_swap` | 0.15 | 0-0.3 | expert-opinion | exercise-selection lens | T-PREF |
| `pref.w_skip` | 0.10 | 0-0.3 | expert-opinion | [this doc] (no "don't like" reason code exists) | T-PREF |
| `pref.implicit_half_life_d` | 56 | 14-180 | expert-opinion | exercise-selection lens | review |
| `pref.implicit_window_d` | 168 | 56-365 | engineering-fact | [this doc] | — |
| `pref.implicit_clamp` | 0.3 | 0-0.5 | expert-opinion | exercise-selection lens | review |
| `pref.prompt_swaps` | 3 | 2-6 | expert-opinion | exercise-selection lens | prompt acceptance |
| `pref.prompt_window_d` | 42 | 14-90 | expert-opinion | exercise-selection lens | prompt acceptance |
| `pref.similar_cos` | 0.9 | 0.8-0.99 | expert-opinion | exercise-selection lens | review |

### 23.12 `elig`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `elig.gate_improve` | 0.75 | 0.5-1.0 | expert-opinion | C26 | T-PREF |
| `elig.gate_maintain` | 0.5 | 0.25-0.75 | expert-opinion | C26 | T-PREF |
| `elig.forbid_coeff` | 0.5 | 0.25-0.75 | expert-opinion | [this doc] (C12 windows, add-on forbid groups) | review |
| `elig.skip_coeff` | 0.5 | 0.25-0.75 | expert-opinion | exercise-selection lens (incidental 0.25 allowed) | review |
| `elig.unlock_repeat_frac` | 0.5 | 0-1 | expert-opinion | exercise-selection lens | T-PREF |
| `elig.avail_coeff` | 0.75 | 0.5-1.0 | expert-opinion | [this doc] | review |

### 23.13 `select`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `select.over_credit` | 0.10 | 0-0.5 | expert-opinion | exercise-selection lens 0.25, set to 0.10 [this doc] (§13.6) | T-ALLOC |
| `select.over_tol` | 1.25 | 1.0-1.5 | expert-opinion | exercise-selection lens | T-ALLOC |
| `select.lam_over` | 0.5 | 0-2 | expert-opinion | exercise-selection lens | T-ALLOC |
| `select.lam_cap` | 1.0 | 0-3 | expert-opinion | [this doc] (per-session cap, C14) | review |
| `select.lam_skip` | 3.0 | 0-10 | expert-opinion | exercise-selection lens | review |
| `select.b_stretch` | 0.05 | 0-0.2 | moderate (long-length direction), value expert | Maeo 2021/2023; Wolf 2023/2025 | review |
| `select.b_sfr` | 0.04 | 0-0.1 | expert-opinion | exercise-selection lens | review |
| `select.region_bonus` | 0.3 | 0-1 | moderate (regional hypertrophy), value expert | Chaves 2020; Larsen 2024; Fonseca 2014 | review |
| `select.consistency` | 0.5 | 0-2 | moderate (systematic variation) | Kassiano 2022; F12 | T-SUB / rotation outcomes |
| `select.stall_pen` | 0.5 | 0-2 | expert-opinion | exercise-selection lens | review |
| `select.axial_cap` | 4 | 2-8 | expert-opinion | exercise-selection lens | review |
| `select.lam_axial` | 0.3 | 0-1 | expert-opinion | exercise-selection lens | review |
| `select.lam_time` | 0.05 | 0-0.2 | expert-opinion | exercise-selection lens | T-TIME |
| `select.lam_time_over` | 0.5 | 0-2 | expert-opinion | exercise-selection lens | T-TIME |
| `select.overrun_cap` | 1.10 | 1.0-1.3 | expert-opinion | exercise-selection lens | T-TIME |
| `select.beam_width` | 12 | 4-50 | engineering-fact | exercise-selection lens | beam-versus-exhaustive tests |
| `select.top_k` | 25 | 10-60 | engineering-fact | exercise-selection lens | same |
| `select.per_group_k` | 2 | 0-5 | expert-opinion | D56 (params 1.0.3) | beam-versus-exhaustive tests, review |
| `select.max_ex` | 8 | 4-12 | expert-opinion | exercise-selection lens | review |
| `select.smax` | {"compound": 5, "isolation": 4} | 2-8 | expert-opinion | exercise-selection lens | review |
| `select.dedup_cos` | 0.95 | 0.8-1.0 | expert-opinion | exercise-selection lens | review |
| `select.max_per_pattern` | 2 | 1-4 | expert-opinion | exercise-selection lens | review |
| `select.tune_passes` | 20 | 1-50 | engineering-fact | exercise-selection lens | — |
| `select.tune_eps` | 0.05 | 0-0.5 | engineering-fact | exercise-selection lens | — |

### 23.14 `order`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `order.chain_setup_s` | 15 | 0-60 | engineering-fact | exercise-selection lens | T-TIME |
| `order.pair_cos_max` | 0.2 | 0-0.5 | strong (supersets of dissimilar exercises keep volume) | Zhang 2025; F13 | review |
| `order.fixup_passes` | 3 | 1-5 | engineering-fact | exercise-selection lens | — |
| `order.grip_threshold` | 2 | 1-3 | expert-opinion | exercise-selection lens | review |

Superset rests are REST-1's keys (`rest.superset_between_s` 60 s, `rest.min_s`; ALGORITHMS B11.2, C56); version 1.0.0's
`order.pair_transfer_s` and `order.pair_rest_floor_s` are removed.

### 23.15 `substitute`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `substitute.w_sim` | 0.60 | 0-1 | expert-opinion | exercise-selection lens | T-SUB |
| `substitute.w_pattern` | 0.15 | 0-1 | expert-opinion | exercise-selection lens | T-SUB |
| `substitute.w_pref` | 0.15 | 0-1 | expert-opinion | exercise-selection lens | T-SUB |
| `substitute.w_setup` | 0.10 | 0-1 | expert-opinion | exercise-selection lens | T-SUB |
| `substitute.w_inj` | 0.10 | 0-1 | expert-opinion | exercise-selection lens | review |
| `substitute.sim_min` | 0.60 | 0.4-0.9 | expert-opinion | exercise-selection lens | T-SUB |
| `substitute.region_weight` | 0.5 | 0-1 | expert-opinion | exercise-selection lens | review |
| `substitute.setup_fast_s` | {"fast": 60, "medium": 120} | — | engineering-fact | exercise-selection lens | T-TIME |
| `substitute.max_defers` | 2 | 1-4 | expert-opinion | UX lens UI-05 | defer-to-done rate |

### 23.16 `rotate`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `rotate.mesocycle_weeks` | 5 (= challenge block when active) | 4-8 | expert-opinion | F12, C31; Bell 2023 | review |
| `rotate.anchors_per_family` | 2 | 1-3 | expert-opinion | exercise-selection lens | review |
| `rotate.progress_slope` | 0.005 per week | 0-0.02 | expert-opinion | exercise-selection lens | review |
| `rotate.stall_exposures` | 3 | 2-6 | expert-opinion | exercise-selection lens | review |
| `rotate.rotate_fraction` | 0.3333 | 0-0.5 | moderate (systematic variation) | F12; Kassiano 2022 | review |
| `rotate.region_gain` | 0.2 | 0-1 | expert-opinion | exercise-selection lens | review |

### 23.17 `time`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `time.tempo_s` | 3 | 2-6 | expert-opinion | C19 (TIME-1) | T-TIME (EWMA per user) |
| `time.per_set_s` | 5 | 0-20 | expert-opinion | C19 | T-TIME |
| `time.ecc_extra_s` | 2 | 0-5 | expert-opinion | challenge lens | T-TIME |
| `time.hold_overhead_s` | 15 | 5-30 | expert-opinion | UX lens UI-02 | T-TIME |
| `time.attempt_reset_s` | 15 | 5-30 | expert-opinion | challenge lens | T-TIME |
| `time.side_switch_s` | 10 | 0-30 | expert-opinion | exercise-selection lens | T-TIME |
| `time.transition_s` | 60 | 20-180 | expert-opinion | C19 | T-TIME |
| `time.general_warmup_s` | 600 | 0-1200 | engineering-fact | C19 (OAP warm-up 10 min) | T-TIME |
| `time.specific_warmup_sets` | 2 | 0-4 | expert-opinion | WARMUP-1 default | T-TIME |
| `time.ewma_alpha` | 0.3 | 0.1-0.6 | engineering-fact | C19 | — |
| `time.calib_min_exposures` | 3 | 1-6 | engineering-fact | exercise-selection lens | — |
| `time.min_addon_min` | 8 | 5-15 | expert-opinion | [this doc] | add-on acceptance |
| `time.min_sets` | {"improve": 2, "maintain": 1, "accessory": 0} | — | expert-opinion | UX lens UI-02 | trimming acceptance |
| `time.group_profile` | table §17.5 | — | expert-opinion | [this doc] | T-TIME |

### 23.18 `injury`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `injury.joint_pen` | 0.3 per stress unit per set | 0-1 | expert-opinion | exercise-selection lens | pain recurrence, review |
| `injury.window_set_pen` | 0.5 per set | 0-2 | expert-opinion | exercise-selection lens | challenge outcomes, review |
| `injury.exclude_levels` | {"3": 1, "2": 2} (exclude when stress ≥ level) | 1-3 | expert-opinion | exercise-selection lens | review |

### 23.19 `ui.*`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `ui.todaycard.close_rel` | 0.10 | 0-0.5 | expert-opinion | C53 (relative close call; replaces UI-01's `close_margin` 0.15) | T-ACCEPT (non-primary starts, split by CLOSE / CLEAR) |
| `ui.todaycard.max_chips` | 3 | 1-5 | expert-opinion | F19 | — |
| `ui.todaycard.max_alts` | 3 | 1-5 | expert-opinion | UX lens (Hick's law) | — |
| `ui.reasonchips.max_visible` | 6 | 3-9 | engineering-fact | UX lens | chip position data |
| `ui.reasonchips.max_defers` | 2 | 1-4 | expert-opinion | UX lens | defer outcomes |
| `ui.prompts.sore_window_h` | 96 | 48-168 | expert-opinion | UX lens | answer usefulness |
| `ui.prompts.sore_max` | 3 | 1-5 | expert-opinion | UX lens | — |
| `ui.prompts.tap_budget` | 6 | 3-10 | expert-opinion | F15 | skip rates |
| `ui.prompts.skip_streak` | 3 | 2-6 | expert-opinion | UX lens | — |
| `ui.prompts.reduced_every` | 3 | 2-6 | expert-opinion | UX lens | — |
| `ui.prompts.restore_after` | 3 | 1-6 | expert-opinion | UX lens | — |
| `ui.prompts.pain_guard_d` | 14 | 7-28 | expert-opinion | UX lens; OAP protocol 14 d | review |
| `ui.prompts.pain_severity_map` | {"mild": 2, "moderate": 5, "sharp": 8} | 0-10 | expert-opinion | C64 (stop threshold 4, applied by REASON-1) | review |
| `ui.volumebars.bar_cap` | 1.25 | 1-2 | engineering-fact | UX lens UI-09 | — |
| `ui.warndisplay.min_magnitude` | 0.2 | 0-0.5 | expert-opinion | UX lens UI-10 | dismissal rates |
| `ui.warndisplay.snooze_d` | 28 | 7-90 | expert-opinion | UX lens | dismissal rates |
| `ui.warndisplay.worsen_factor` | 0.25 | 0-1 | expert-opinion | UX lens | — |
| `ui.warndisplay.max_banners` | 1 | 1-2 | expert-opinion | UX lens | — |
| `ui.progress.pr_min_gain` | 0.005 | 0-0.02 | expert-opinion | UX lens (loadable-rounding noise) | PR rate |
| `ui.progress.hold_pr_min_gain_s` | 0.5 | 0-2 | expert-opinion | C69 (hold PR per side) | PR rate |
| `ui.progress.pr_min_history` | 1 | 0-5 | expert-opinion | C69 (no PR on the first exposure) | — |
| `ui.progress.window_weeks` | 12 | 4-52 | engineering-fact | UX lens | — |
| `ui.prefill.hold_delay_ms` | 400 | 200-800 | engineering-fact | Android auto-repeat conventions | keypad use |
| `ui.prefill.repeat_ms` | 120 | 50-300 | engineering-fact | same | — |
| `ui.prefill.accel_after` | 8 | 4-20 | engineering-fact | same | — |
| `ui.prefill.accel_factor` | 5 | 2-10 | engineering-fact | same | — |
| `ui.prefill.hold_short_frac` | 0.8 | 0.5-1 | expert-opinion | UX lens UI-04 | deviation rates |
| `ui.prefill.post_rir_delta` | 2 | 1-4 | expert-opinion | C69 (post-log RIR deviation) | deviation rates |
| `ui.prefill.hold_step_s` | 1.0 | 0.5-2 | expert-opinion | C69 (hold stepper step) | keypad use on hold steppers |
| `ui.prefill.hold_step_fine_s` | 0.5 | 0.1-1 | expert-opinion | C69 (fine step for short holds) | keypad use on short holds |
| `ui.prefill.hold_fine_below_s` | 10 | 5-20 | expert-opinion | C69 (threshold for the fine step) | keypad use on short holds |

### 23.20 `tuner`, `sim`, `replay`

| Key | Default | Bounds | Evidence | Source | Tuned by |
|---|---|---|---|---|---|
| `tuner.kappa_grid` | {"min": 0.7, "max": 1.5, "step": 0.1} | — | engineering-fact | planning lens | — |
| `tuner.min_samples` | 12 | 6-50 | engineering-fact | planning lens | review |
| `tuner.min_improvement` | 0.05 | 0.01-0.2 | engineering-fact | planning lens | review |
| `tuner.max_kappa_step` | 0.2 | 0.05-0.5 | engineering-fact | [this doc] | review |
| `tuner.attainment_floor` | 0.70 | 0.5-0.9 | expert-opinion | planning lens | review |
| `tuner.attainment_weeks` | 3 | 2-6 | expert-opinion | planning lens | review |
| `tuner.too_easy_share` | 0.30 | 0.1-0.5 | expert-opinion | planning lens | review |
| `tuner.acceptance_floor` | 0.5 | 0.2-0.8 | expert-opinion | planning lens | review |
| `tuner.acceptance_min_n` | 6 | 3-20 | engineering-fact | planning lens | review |
| `tuner.churn_limit` | 3 per week | 1-7 | expert-opinion | [this doc] | review |
| `tuner.cal_fp_rate` | 0.2 | 0.05-0.5 | expert-opinion | [this doc] | review |
| `tuner.cal_min_corrections` | 10 | 5-50 | engineering-fact | [this doc] | review |
| `tuner.run_min_n` | 8 | 4-20 | engineering-fact | [this doc] | review |
| `tuner.run_max_scale` | 0.3 | 0.1-0.5 | expert-opinion | [this doc] | review |
| `tuner.alloc_min_sessions` | 10 | 5-30 | engineering-fact | [this doc] | review |
| `tuner.alloc_log_ratio` | 0.15 | 0.05-0.5 | engineering-fact | [this doc] | review |
| `tuner.time_delta` | 0.10 | 0.05-0.3 | engineering-fact | [this doc] | review |
| `tuner.time_min_sets` | 20 | 10-100 | engineering-fact | [this doc] | review |
| `sim.sigma_rir` | 1.0 | 0.5-2 | moderate | Halperin 2022 (≈ 0.95 reps) | fitted from logs |
| `sim.set_drop` | 1 rep per set at RIR ≤ 2 | 0-3 | weak | tech-stack lens | fitted from logs |
| `sim.g` | 0.004 per effective set | 0-0.02 | weak | tech-stack lens | fitted from logs |
| `sim.cap_mult` | 1.4 | 1.1-2 | weak | tech-stack lens | — |
| `sim.detrain_rate` | 0.005 per week after 10 missed days | 0-0.02 | weak | tech-stack lens; Bosquet 2013 | — |
| `sim.p_override` | 0.10 | 0-0.5 | weak | tech-stack lens | fitted from logs |
| `sim.p_occupied` | 0.05 | 0-0.3 | weak | [this doc] | station occupancy logs |
| `sim.seeds` | 50 | 1-500 | engineering-fact | tech-stack lens (1..200) | — |
| `sim.weeks` | 26 | 4-104 | engineering-fact | critique (26-week personas) | — |

---

## 24. Golden fixtures and tests

### 24.1 Fixtures

All fixtures are JSON files under `algo/src/test/resources/fixtures/<id>/` with `inputs/*.json` and
`expected/*.json`; times are stored as UTC Instants plus the zone `Europe/Amsterdam`.

**F-W41: the real week (Mon 2026-10-05 to Sun 2026-10-11).**

| File | Content |
|---|---|
| `clock.json` | now = 2026-10-07T10:00:00Z (Wed 12:00 CEST); zone Europe/Amsterdam |
| `calendar.json` | primary calendar instances: C 2026-10-06T17:45Z-19:15Z "Gym — sessie C (volume en techniek)"; A 2026-10-08T17:45Z-19:15Z "Gym — sessie A (één arm zwaar)"; TEST 2026-10-11T11:00Z-13:00Z "Gym — testdag 1 (meten)"; chess 2026-10-05T17:30Z-20:30Z "Schaken"; plus a Todoist calendar with all-day "Gym" items (to prove they are ignored) |
| `profile.json` | BW 70.7 kg; preset balanced; training age intermediate (variant `profile-novice.json` with `training_age` null → novice, C64); max visits 4; availability weekdays 19:45-21:15, weekends 09:00-16:00; `default_session_min` 75, `changeover_min` 5 (C49); running priority low; prompts on; evidence mode warn_only |
| `challenge.json` | OAP enrollment: block 1, week 5, mode deload (or test_pending), slots C, A, TEST; loads C 20 / A 22.5 / B 27.5 kg; negatives 47.5 kg; pins A 40 / C 50 / 90° hold 35 lb; no P rules approved; the contract (§0.5) materialized for the week |
| `sets.json` | the Tue C session as logged (§21.1) with set timestamps from 17:45Z, the daily hangs Mon and Tue, the plank Tue |
| `equipment.json` | Basic-Fit default checklist (F7), all stations available; belt plates 1.25, 2.5, 5, 10, 20 kg |
| `catalog.json` | the seed (`catalog-1.0.0.json`) |
| `expected/` | `calendar.classify`, `safety.state`, `accounting.week`, `readiness` at Wed 19:45 / Thu 19:45 / Fri 19:45 / Sun 13:00, `targets.weekly`, `weekplan.plan`, `today.options` for each day, `session.plan` for the Thu add-on, the Fri block and the Wed "train anyway" block, `ui.todaycard` per day |

**F-CAL-41**: the calendar subset with Dutch titles, the em dash, diacritics, a declined event, a cancelled event, an
all-day Todoist item, a run ("Hardlopen" 45 min; "Intervaltraining hardlopen" 50 min), a marked app event, and two
overlapping gym events (dedupe).

**F-DST-W43**: ISO week 2026-W43 (Mon 19 to Sun 25 October 2026), OAP block 2 week 2 with A on Fri 2026-10-23 19:45 CEST
and B on Sun 2026-10-25 11:00 CET; a general session on Sat is blocked (rest day before B). DST ends Sun 03:00 CEST →
02:00 CET.

**F-SEL**: the prototype selection cases of §22 (to be regenerated on the seed rows).

**F-PERSONAS**: the six personas of §20.2 with 26-week horizons and seeds 1-50.

### 24.2 End-to-end golden tests

| Test | Fixture | Expected |
|---|---|---|
| `W41_calendar` | F-W41 | Tue C, Thu A, Sun TEST `challenge_session` (accepted by `matchSlots`, unmarked, Adopt offered); chess `busy`; Todoist ignored |
| `W41_targets` | F-W41 | §21.3: covered {lats, biceps, forearms}; improve T 12, maintain T 4; deload source challenge; re-entry for all non-challenge groups; set factor 0.5, RIR + 2 |
| `W41_accounting` | F-W41 | §21.4 table (Vhyp done and committed, 1e-2 tolerance until the seed is final) |
| `W41_readiness` | F-W41 | §21.4 readiness table (±0.01) |
| `W41_weekplan` | F-W41 | §21.6-21.7: Thu legs add-on (capacity 34 min) + Fri FULL ("Legs + push + rear delts"), J 4.7829; band holds only this plan (best Wednesday plan 4.6643); Wed `MAX_VISITS` with allowedFamilies legs / push / core + light pull; Sat `REST_BEFORE`; recommendedN 4; Wed FULL + Thu legs add-on rejected by H6 |
| `W41_addon_thu` | F-W41 | §21.8: capacity 34 min (C49); `direct` quads 2, hamstrings 2 (13.8 min); leg press × 2, seated leg curl × 2 |
| `W41_allocation_fri` | F-W41 | §21.8 `alloc`, `normal` (74.8 min), `direct` (49.6 min); upper back and upper traps dropped `NO_ELIGIBLE_EXERCISE` (C50) |
| `W41_select_fri` | F-W41 | §21.8 exercise list (regenerate on the seed; until then the prototype catalog of §22.4) |
| `W41_today_wed` | F-W41 | §21.9: verdict `REST_SOFT`, `BETTER_OTHER_DAY` Fri; "Train anyway" FULL 0.441, PUSH+LEGS 0.438, PULL+LEGS 0.274; CORE infeasible; `ui.todaycard` REST soft with the two chips |
| `W41_today_thu` | F-W41 + Wed rested | PLANNED challenge, verdict `TRAIN`, primary "Session A + legs add-on" (0.839) |
| `W41_today_fri` | same + Thu logged as planned | verdict `TRAIN`, OPTIONAL clear, primary FULL (1.840); legs-heavy types excluded `HEAVY_LOWER_GAP` |
| `W41_today_sat` | same + Fri logged | verdict `REST_HARD`, `REST_BEFORE` (TEST Sun 13:00) |
| `W41_today_sun` | same | PLANNED test |
| `W41_trainAnywayWed` | F-W41 + the Wed FULL "train anyway" session logged | Thu primary "Session A + push add-on" (the legs add-on is excluded `HEAVY_LOWER_GAP`, H6); Fri `MAX_VISITS` |
| `W41_planItFri` | F-W41 + "Plan it" on the Friday item | event `Gym — Legs + push + rear delts` Fri 19:45-20:40 with marker `gen/2026-W41/5` as the last description line; reclassified as `planned_gym` (method `marker`); Fri card PLANNED general with the calendar budget 50 min |
| `W41_elbowCheck2` | F-W41 + elbow check 2 before the Fri session | elbow flags severity 1 for 7 days; Fri plan unchanged except penalties on elbow-stress exercises |
| `W41_testPostponed` | F-W41 with challenge state needsCleanSession | Sunday slot becomes B (C36); Saturday still blocked; calendar attention `slot_mismatch` on the unmarked test event |
| `W41_capacity19Variant` | F-W41 with the A add-on capacity at 19 min | Wed FULL + A push add-on chosen inside the band over J* Fri FULL + A push add-on (C52, earlier session first), §21.11 |
| `W41_noviceVariant` | F-W41 with `profile-novice.json` | Wed FULL + A push add-on (J* 5.2453) chosen over Wed PUSH+LEGS + A push add-on (5.2084) inside the band by the higher-`J` key (C52 amended), §21.11 |
| `DST_W43_windows` | F-DST-W43 | the hard window "24 h before B" starts Sat 2026-10-24 12:00 CEST (= 2026-10-25T10:00Z − 24 h), not 11:00; Saturday blocked by the rest-day rule anyway |
| `DST_W43_readiness` | F-DST-W43 | a quads dose at Sat 20:00 CEST read at Sun 20:00 CET → 25 h elapsed (R 0.565 for dose 8) |
| `DST_W43_week` | F-DST-W43 | 7 local dates, ISO week 2026-W43 for all of them; the Sunday has 25 hours |
| `determinism` | all | outputs byte-identical under input permutations and across JVM / ART (StrictMath) |
| `goldenReplay` | all snapshots | `replay` diff empty |

Module-level tests are listed in each section (§1.7 … §19.7). Persona invariants are in §20.2.

---

## 25. Open issues

Each item is a decision DECISIONS.md does not cover or a conflict between documents. This document uses the stated
default until the issue is resolved; nothing here was decided silently. Items resolved by the cross-document
resolutions of DECISIONS §3.1 are kept, marked, so the history stays traceable.

| Id | Issue | Status / default used here | Needs |
|---|---|---|---|
| OI-01 | Calendar marker format differed between the challenge definition, DATA_MODEL and UX. | **Resolved by C37**: `[fitapp:v1 <uuid> <slotKey>]` everywhere (§1.4, §8.9). | — |
| OI-02 | Which RIR SetCredit uses (C17). | **Resolved by C44**: the reported RIR; the RIRCAL bias applies only inside the estimators (§3.4). | — |
| OI-03 | Do 0.25 coefficients give fractional-set credit? | **Resolved by C44**: no, 0.25 counts toward fatigue only (§3.4). | — |
| OI-04 | C5 did not list abductors and listed obliques as a muscle. | **Resolved by C44**: abductors τ 30 h, `F_REF` 6; obliques is a region of `abs` (§4.4). | — |
| OI-05 | Deload and re-entry in the same week. | **Resolved by C51**: never stacked; set factor = min, RIR offset = max (§5.4.3). | — |
| OI-06 | Add-on capacity versus D5's "~35 / ~20 min". | **Resolved by C49**: calendar events are training time; capacity = booked − TIME-1 − 5, floored: 24 / 48 min after C and none / 34 min after A in normal / deload weeks (§17.6). CHALLENGE_ENGINE CE-OI-18 can close. | — |
| OI-07 | Whole-body (systemic) protection of A, B and TEST. | **Resolved by D15 / C50**: no systemic term in v1; the day before A is open for legs / push (§8.6). | — |
| OI-08 | UX §19 overlapped the `ui.*` specs here. | **Resolved by C53**: this document owns the seven `ui.*` specs; UX §19 references them and adds display details (remaining difference: OI-21). | — |
| OI-09 | Default training age when onboarding has no answer. | **Resolved by C64**: novice (BASE 10). The fixture assumes the onboarding answer intermediate; §21.11 shows the novice result. | — |
| OI-10 | **This user's goals and training age are not decided yet** (D6). The worked example uses the `balanced` preset and intermediate; §21.11 shows the result per preset and for novice. | Open: balanced, intermediate (fixture only) | user in M4.2 (D55): You → Preferences, Muscle goals and Sessions |
| OI-11 | Light pull work on gap days. | **Resolved by C50**: only exercises with grip demand ≤ 1 and a biceps-group coefficient below 0.5, when the goals ask for it (reverse pec deck yes, rows no; §12.3). | — |
| OI-12 | Tie band and "earlier session first". | **Resolved by C52** (amended): band 0.05; earlier session, then fewer sessions, then higher `J`, then lexicographic (§8.6). Follow-up OI-19 resolved. | — |
| OI-13 | One gym visit per day. | **Resolved by C50** (H8). | — |
| OI-14 | Slot matching interface for `calendar.classify`. | **Resolved**: CHALLENGE_ENGINE §9.3 defines `challenge.calendar.matchSlots(enrollment, candidates) → List<SlotMatch>` as used in §1.4. | — |
| OI-15 | **Run systemic load.** The planning lens's 1.0 per 40 min would make an easy 40-min run as taxing as a full 15-set lower-body session. | Open: 0.5 per 40 min | review after runs are logged (T-RUN) |
| OI-16 | **Prototype catalog versus seed.** The §21/§22 selection outputs come from a prototype catalog (§22.4) with a few coefficients that differ from the seed (§10.8). | Open: prototype values documented | regenerate the golden values on catalog 1.0.2 before freezing them (D55, per M4 step); M4.1 froze the §21.3-21.4 goldens (GF-W41-ACCOUNTING, -READINESS, -TARGETS); M4.3 froze the allocator and selection goldens (GF-W41-ADDON-THU, GF-W41-ALLOC-FRI, GF-W41-SELECT-FRI, GF-SEL-*), with the D56 candidate list; the planner and Today goldens follow in M4.5 |
| OI-17 | Region count ("~32" in C9). | **Resolved by C44**: 34 regions + optional `neck` (§10.2). | — |
| OI-18 | **Soreness scale.** UI chips give three answers; the RP-style ramp table uses four levels. | Open: never_sore 1, healed 2, still_sore 4 (ramp) and 0 / 1 / 3 points (`readiness.soreness_points`, which DATA_MODEL E19 points to) | review after 8 weeks of answers |
| OI-19 | C52 put the lexicographic key before `J` (novice case of §21.11). | **Resolved by C52 amendment** (2026-10-07): higher `J` before the lexicographic key; the novice case now picks Wed FULL. | — |
| OI-20 | `ChallengeContract` field names and fields (CHALLENGE_ENGINE CE-OI-12). | **Resolved on this side**: §0.5 maps its names to CHALLENGE_ENGINE §8.1, drops mode `paused` (a paused enrollment exports an empty contract), `intensityClass` (not needed) and `materializedSets[]` (not exported by §8.1), and reads planned challenge doses from `challenge.materialize` per reserved session. CHALLENGE_ENGINE marks CE-OI-12 resolved. | — |
| OI-21 | **UX GF-UX-01 and the Today / Week wireframes for Wednesday 7 October assume a training day** (verdict `TRAIN`, Wednesday "recommended", Friday "free, limited"). With C49 and C50 this document's engine gives `REST_SOFT` on Wednesday and recommends Friday (§21.9, §21.10). GF-UX-01's inputs are synthetic, so the composer test itself stays valid; only the real-week story differs. | this document's engine output | UX (UX §9 wireframes, §25 GF-UX-01): label GF-UX-01 as synthetic or add the real-week Wednesday (REST soft) |
| OI-22 | Package and resource layout (ANDROID OI-23). | **Resolved on this side**: §26 uses ANDROID 27.1's `dev.fitnessapp.algo.*` and `dev.fitnessapp.app.uidet`, one shared `Marker` in `dev.fitnessapp.algo.core.calendar`, and resources under `algo/src/main/resources/fitness/` (C40); confirmed by C68. | ANDROID: close OI-23 (a)-(c) |
| OI-23 | Week-close tick for `targets` (ALGORITHMS OI-A21). | **Resolved on this side**: the `targets` week-close evaluations run on the challenge's `week.closed` tick (§5.4.1); `weekplan` replans on its own triggers. | ALGORITHMS: close this part of OI-A21 |

---

## 26. Implementation notes for Claude Code

**Packages and files (`:algo`, pure Kotlin JVM; no `android.*`, no clock, no randomness, no IO).** The layout follows
ANDROID 27.1 and C68 (application id `dev.fitnessapp`, F22; all packages under `dev.fitnessapp.*`; `ENGINE_VERSION` lives in `dev.fitnessapp.algo.EngineVersion`): one package per registry module under
`algo/src/main/kotlin/dev/fitnessapp/algo/`, grouped by the registry area of §0.1.

| Package (`dev.fitnessapp.algo.` …) | Classes |
|---|---|
| `core.calendar` | `Marker` (render, parse, like-pattern): the one shared marker implementation, which `core.calendar.classify` and `challenge.calendar` both use (ANDROID 27.1, C37, C68) |
| `core.calendar.classify` | `CalendarClassifier`, `TitleNormalizer`, `CalendarLexicons`, `SessionLinker` |
| `core.safety` | `SafetyEvaluator`, `SafetyState`, `JointForbidTags` |
| `accounting.setcredit`, `accounting.readiness` | `SetCredit`, `PlannedCredit`, `WeekAccount`; `ReadinessModel`, `Readiness`, `NextReady` |
| `targets`, `targets.runs` | `WeeklyTargetsCalculator`, `DeloadPolicy`, `Reentry`, `Ramp`, `Utility`, `GoalPresets`; `RunInterference` |
| `planning.allocator`, `planning.weekplan`, `planning.today` | `SessionDoseAllocator`, `DoseEstimator`; `WeeklyPlanner`, `PlanSimulator`, `PlanObjective`, `TieBreak`; `TodayRecommender`, `ReasonCodes`, `SessionLabel` |
| `selection.catalog`, `.pref`, `.elig`, `.select`, `.order`, `.substitute`, `.rotate`, `.time`, `.injury` | `Catalog`, `CatalogValidator`, `DerivedTags`; `PreferenceModel`; `Eligibility`; `BeamSelector`; `Ordering`, `Supersets`; `Substitution`; `Rotation`; `TimeModel`, `TimeTrimmer`; `InjuryFilter` |
| `common` | `Rounding` (`ceilHalf`, `roundHalf`, grid rounding), `Groups` (canonical order, families), `Ids` |

UI functions in `:app` under `dev.fitnessapp.app.uidet` (C68; `TodayCardComposer`, `ReasonChipOrder`, `PromptPlanner`,
`VolumeBars`, `WarningDisplay`, `ProgressSeries`, `Prefill`), pure Kotlin, unit-tested on the JVM and scanned by the
purity test (ANDROID 27.1). Offline tools in `:sim` under `dev.fitnessapp.sim.tuner`, `dev.fitnessapp.sim.harness`,
`dev.fitnessapp.sim.replay`.

**Resources.** Bundled data lives under `algo/src/main/resources/fitness/` (ANDROID 4, C40, C68):
`fitness/params/default-params-1.0.0.json` (every key of §23, entry spec of DATA_MODEL 9.1.10),
`fitness/catalog/catalog-1.0.0.json` (§10.8 seed), `fitness/presets/goal-presets-1.0.0.json` (§5.6); challenge
definitions under `fitness/challenges/` are owned by CHALLENGE_ENGINE. The same files
are bundled in the APK as offline fallbacks and inserted by migration.

**Determinism checklist.** `StrictMath.exp/log/sqrt`; `TreeMap` or sorted lists for every iteration that affects a
decision; all ties by the documented keys; round to the C22 grid before comparisons and hashing; utilities and `J`
rounded to 1e-6 before ranking; elapsed hours from `Instant`s, days and weeks from `LocalDate` via the injected
`Clock/Week`; no `HashMap` iteration order in outputs; JCS + SHA-256 for `inputs_hash`. The purity test (ANDROID 6.3,
`AlgoPurityTest`) fails the build if `:algo` or `dev.fitnessapp.app.uidet` imports `android.*`,
`java.time.Clock.system*`, `kotlin.random` or `java.util.Random`.

**Performance.** `WeeklyPlanner` runs on `Dispatchers.Default`, depth-first over days with prefix sharing and an
allocator memo; results are cached by `inputs_hash` until a replan trigger (§8.7). `TodayRecommender` reuses the
planner's enumeration (one pass gives every "today = type" optimum). Target: < 1 s for 200k plans on the phone; the
`evalGuard` test covers the worst case.

**Test names (JUnit 5, one class per module).** `CalendarClassifierTest` (§1.7), `SafetyEvaluatorTest` (§2.7),
`SetCreditTest` (§3.7), `ReadinessModelTest` (§4.7), `WeeklyTargetsTest` (§5.8), `RunInterferenceTest` (§6.7),
`SessionDoseAllocatorTest` (§7.9), `WeeklyPlannerTest` (§8.12), `TodayRecommenderTest` (§9.9), `CatalogValidatorTest`
(§10.11), `PreferenceModelTest` (§11.4), `EligibilityTest` (§12.5), `BeamSelectorTest` (§13.6, including
`beamVsExhaustive`), `OrderingTest` (§14.3), `SubstitutionTest` (§15.2), `RotationTest` (§16.2), `TimeModelTest`
(§17.8), `InjuryFilterTest` (§18.3), `TodayCardComposerTest` … `PrefillTest` (§19), and the golden suites
`W41GoldenTest`, `DstW43GoldenTest`, `SelectionGoldenTest`, `PersonaInvariantTest` (§20.2), `ReplayGoldenTest`.

**Build order (milestones of the critique's MVP; C73, D18, D21).** M1 (logger): `catalog` (seed + validator in CI),
`ui.reasonchips`, `ui.prefill` (D18: "last time" as the only source; M3 adds the `rx` targets), and from
ALGORITHMS.md `load`, `bodyweight` and the base policy of `rest` (D21). M2: `calendar.classify`, `safety`. M3 (D49): `pref`, `injury`, the hard filters of `elig` (§12.3 without the
necessity gate), `substitute`, the `rx` source of `ui.prefill`, `ui.warndisplay`. M4: the necessity gate of `elig`,
`select`, `order`, `time` (including the challenge add-on capacity), `setcredit`, `readiness`, `targets`, `runs`,
`allocator`, `weekplan`, `today`, `rotate`, `ui.todaycard`, `ui.prompts`, `ui.volumebars`, `ui.progress`. M5: `tuner`,
`sim`, `replay`.

**Reproducing §21.** The numbers of §21 and §22 came from Python reference prototypes written for this specification
(kept outside the repository; version 1.1.0 re-ran them with C49's 34-minute add-on capacity, the C50 light-pull
filter, the C52 tie key and H6 counted on credited FS). Port the fixture first (F-W41), implement the modules, and
compare with §21: allow a 1e-3 tolerance on `J` and readiness while the catalog seed is being finalised, then freeze
the goldens (OI-16).
