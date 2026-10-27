# Algorithms specification v1: engine contract, estimation and prescription

Status: draft v1 for review, written 2026-10-07. Applies to `ENGINE_VERSION` 1.0.0, every module version
1.0.0 and params set 1.0.0.

Readers: (a) the user, (b) Claude Code sessions that implement the `:algo` module and later review its
algorithms against logged data.

Single source of truth: `docs/DECISIONS.md` (IDs D1-D16, F1-F27, C1-C36 and the cross-document resolutions
C37-C65 of its section 3.1). This document follows it and cites the IDs. Where this document had to fill a gap
that DECISIONS does not cover, the gap is listed in [Open issues](#open-issues) and the interim behaviour is
stated there; nothing is decided silently. Revised 2026-10-07 after the spec pass to apply D13-D16, the
revised F11 and C37-C65.

Companion documents:

| Document | What it owns |
|----------|--------------|
| `docs/ALGORITHMS_PLANNING.md` | The core modules `calendar.classify` (section 1) and `safety` (section 2), accounting (`setcredit`, `readiness`), targets (`targets`, `runs`), planning (`allocator`, `today`, `weekplan`), the selection layer (`catalog`, `pref`, `elig`, `select`, `order`, `substitute`, `rotate`, `time`, `injury`), the specs of all `ui.*` deterministic functions (section 19, C53) and the offline tools `tuner`, `sim` and `replay` (section 20). |
| `docs/CHALLENGE_ENGINE.md` | All `challenge.*` modules, FitExpr, the staged ECA engine including the in-session rules (D14, C46), the engine-fact snapshot that exposes `userK` to rules (section 3.9) and the OAP definition semantics. |
| `docs/DATA_MODEL.md` | Tables, value domains (section 8, canonical for every stored code, C38), the event-type registry (section 9.8.1, C39), the `recommendations` envelope and the review keys (section 10.2, C58). |
| `docs/ANDROID.md` | Gradle module boundaries, purity rules P1-P9 and their enforcement (section 6), the Room schema (section 8, C40), the engine gateway and params pinning (section 7.6), the platform rest timer and the platform constants in `PlatformConfig` (C54). |
| `docs/UX.md` | Screens and display details; its section 19 references the `ui.*` specs of ALGORITHMS_PLANNING (C53). |

---

## Contents

- [0. Conventions](#0-conventions)
- [Part A. Engine contract](#part-a-engine-contract)
  - A1 Scope, A2 Purity, A3 Numerics and the canonical grid, A4 Ordering and ties, A5 Time,
    A6 Shapes, A7 Versioning, A8 Params JSON, A9 Snapshots and hashing, A10 Replay guarantee,
    A11 Enforcement tests, A12 Pipeline L0-L8
- [Part B. Module specifications](#part-b-module-specifications)
  - B1 `clock`, B2 `load`, B3 `bodyweight`, B4 `rircal`, B5 `rcm`, B6 `sse`, B7 `fatigue`, B8 `hold`,
    B9 `rx`, B10 `warmup`, B11 `rest`, B12 `reason`, B13 `autoreg`, B14 `stall`, B15 `warnings`
- [Part C. Params JSON v1 and OAP worked examples](#part-c-params-json-v1)
- [Part D. Golden fixtures, tests and review metrics](#part-d-golden-fixtures-tests-and-review-metrics)
- [Open issues](#open-issues)
- [Implementation notes for Claude Code](#implementation-notes-for-claude-code)

---

## 0. Conventions

### 0.1 Evidence grades

Every constant carries one grade. The grade describes the support for the **value**, not for the idea.

| Grade | Meaning |
|-------|---------|
| strong | Meta-analyses or several consistent trials support the value directly. |
| moderate | One good meta-analysis, or consistent trials that support the value approximately. |
| weak | Preprints, single studies, extrapolation from a different population or outcome. |
| expert-opinion | Coach heuristics, app reverse engineering, or a value chosen by this design. Always tunable. |
| engineering-fact | A unit conversion, a platform behaviour or a definition. Not tunable. |

"Tunable" means the value lives in the params JSON, every use is logged, and Part C names the logged data
that a review uses to refit it. A tuned value reaches the phone only through `user_algo_params` after the
user approves it (A8.6).

### 0.2 Notation

| Symbol | Meaning | Unit / grid |
|--------|---------|-------------|
| `w`, `w_sys` | System load: the mass the target muscles move (C1, C2, B2) | kg, 0.01 |
| `BW` | Bodyweight used for the calculation (C32, B3) | kg, 0.01 |
| `f_bw` | Bodyweight fraction of the exercise (C1) | ratio, 1e-4 |
| `M` | One-repetition maximum on the system-load scale | kg, 0.01 |
| `L` | `ln M`, the SSE-1 level | STAT (A3) |
| `g` | SSE-1 trend, ln units per week | STAT |
| `P` | 2x2 SSE-1 covariance `[[P_LL, P_Lg], [P_Lg, P_gg]]` | STAT |
| `k` | Rep-capacity curve constant of RCM-1 (C3); app_benchmarks' `kappa = 1/k` is an alias only | ratio |
| `n` | Reps to failure from a fresh state at load `w` (`cap(w)`) | rep, 0.01 |
| `q_rep` | RIR as reported (chip, D9); raw value used by challenge rules (C17) | 0.5 steps |
| `q_true` | RIR after the RIRCAL-1 bias correction | rep, 0.01 |
| `q*` | Target RIR for every set of the prescription | 0.5 steps |
| `n_eff` | `reps + q_true` (reps to failure at the moment of the set) | rep, 0.01 |
| `F_j` | Within-session fatigue fraction before set `j` (FAT-1) | ratio |
| `n_fresh` | `n_eff / (1 - F_j)`: capacity corrected to a fresh state | rep, 0.01 |
| `S` | Number of working sets | int |
| `r_lo`, `r_hi`, `r*` | Rep range (RANGE) or fixed reps (FIXED) | int |
| `T` | HOLD-1 capacity: seconds a hold could last at the reference load | s, 0.1 |
| `s_res` | Seconds in reserve reported by the hold chips (D9) | s, 0.1 |
| `dd`, `wk` | Elapsed days and weeks between two Instants (`dd = hours / 24`, `wk = dd / 7`) | STAT |

Kotlin snippets are sketches: names and shapes are normative, syntax details are not.

### 0.3 Module map

All ids are registered in `fitness.algorithm_versions` (DATA_MODEL 9.1.9) at 1.0.0. The anchor column is
the value for `algorithm_versions.spec_ref`.

| Layer | Registry id | Spec id | Specified in | Anchor |
|-------|-------------|---------|--------------|--------|
| core | `clock` | CLOCK-1 | this document, B1 | `docs/ALGORITHMS.md#clock-1` |
| core | `load` | LOAD-1 | this document, B2 | `#load-1` |
| core | `bodyweight` | BW-1 | this document, B3 | `#bw-1` |
| core | `calendar.classify` | CalendarClassifier.v1 (C20) | ALGORITHMS_PLANNING.md section 1 | |
| core | `safety` | SafetyState (C33) | ALGORITHMS_PLANNING.md section 2; interface used here in A12 | |
| challenge | `challenge.cds`, `challenge.fitexpr`, `challenge.enrollment`, `challenge.eca`, `challenge.materialize`, `challenge.plan`, `challenge.contract`, `challenge.calendar` | CDS v1, FitExpr, ECA | CHALLENGE_ENGINE.md | |
| accounting | `setcredit`, `readiness` | SetCredit.v1, MuscleReadiness | ALGORITHMS_PLANNING.md | |
| estimation | `rircal` | RIRCAL-1 | B4 | `#rircal-1` |
| estimation | `rcm` | RCM-1 | B5 | `#rcm-1` |
| estimation | `sse` | SSE-1 | B6 | `#sse-1` |
| estimation | `fatigue` | FAT-1 | B7 | `#fat-1` |
| estimation | `hold` | HOLD-1 | B8 | `#hold-1` |
| estimation | `stall` | STALL-1 | B14 | `#stall-1` |
| targets | `targets`, `runs` | WeeklyTargets + DeloadPolicy + re-entry, RunInterference | ALGORITHMS_PLANNING.md | |
| planning | `allocator`, `today`, `weekplan` | | ALGORITHMS_PLANNING.md | |
| selection | `catalog`, `pref`, `elig`, `select`, `order`, `substitute`, `rotate`, `time`, `injury` | CAT-1, PREF-1, ELIG-1, SEL-1, ORD-1, SUB-1, ROT-1, TIME-1, INJ-1 | ALGORITHMS_PLANNING.md sections 10-18 | |
| prescription | `rx` | RX-1 | B9 | `#rx-1` |
| prescription | `warmup` | WARMUP-1 | B10 | `#warmup-1` |
| prescription | `rest` | REST-1 | B11 | `#rest-1` |
| prescription | `reason` | REASON-1 | B12 | `#reason-1` |
| prescription | `autoreg` | AUTO-1 (+ UI-06 trigger) | B13 | `#auto-1` |
| prescription | `warnings` | WarningEngine WE-1 | B15 | `#we-1` |
| ui | `ui.todaycard`, `ui.prefill`, `ui.reasonchips`, `ui.prompts`, `ui.volumebars`, `ui.warndisplay`, `ui.progress` | UI-01, UI-04, UI-05, UI-07, UI-09, UI-10, UI-11 | ALGORITHMS_PLANNING.md section 19 (C53); UX.md section 19 adds display details only | |
| offline | `tuner`, `sim`, `replay` | ModelTuner, SimulationHarness, Replay | tools: ALGORITHMS_PLANNING.md section 20; the replay guarantees R1/R2 that the tools rely on: A10 here | `#a10-replay-guarantee` |

---

# Part A. Engine contract

The contract applies to every registry module, including those specified in other documents, and to the
`ui.*` pure functions that live in `:app` (ANDROID.md 6.2).

## A1. Scope

Every recommendation the app shows (session type today, weekly plan, exercise list, sets, reps, loads,
rest, warm-ups, in-session suggestions, warnings, estimates) is the output of a **pure function**

```
(EngineInput, resolved params) -> (output, DecisionTrace, warnings)
```

of logged facts and versioned params. "Pure" means: the same input bytes and the same params bytes give the
same output bytes on the JVM (tests, `:sim`) and on ART (the phone), today and in any later replay that runs
the same module version. Everything outside this function (Room, Supabase, the calendar provider, the
alarm manager, the system clock) belongs to `:data` and `:app`.

## A2. Purity rules

ANDROID.md 6.2 defines rules P1-P9 (no Android imports, no system clock, no randomness, no IO, StrictMath for
transcendental functions, no hash-ordered collections, no mutable globals, locale-free formatting, API-26
JDK surface) and 6.3 defines their enforcement (`verifyAlgoDependencies`, the source scanner
`AlgoPurityTest`, the ART determinism self-test). They are not repeated here. This document adds the
algorithm-level rules P10-P16, which apply to the same code:

| Rule | Statement | Why | Enforced by |
|------|-----------|-----|-------------|
| P10 | Every list in an `EngineInput` payload is in **canonical order** (A4.1) before the module sees it. Modules may still sort, but must not depend on caller order. | Room query order is not a contract. | `ShuffleInvarianceTest` (A11) |
| P11 | All sorts are stable (`sortedWith`, `sortedBy`) and every comparator ends with a total tie-break on a stable id (`exercise.slug`, then UUID string). | Equal scores must give the same order everywhere. | Code review + `ShuffleInvarianceTest` |
| P12 | Score comparisons use `Det.cmpScore(a, b)` with tie tolerance `TIE_EPS = 1e-9` (absolute); within the tolerance the documented tie-break decides. Threshold comparisons (`>=`, `<`) use plain doubles. | Avoids "ties" that differ only in the last bit between two code paths. | Unit tests |
| P13 | No `Double` is converted to text inside `:algo` except through `Canon` (A3.4). No string templates or `toString()` on doubles in outputs, traces or keys. | `Double.toString` differs between JDK 17, JDK 19+ and ART for some values. | Source scanner patterns in `AlgoPurityTest` (ANDROID.md 6.3) + golden hash test |
| P14 | `NaN` and infinities never leave a module. A module that would produce one returns a typed `EngineError` (for example `EngineError.InvalidInput("sse.w_sys<=0")`), which the gateway logs; it never throws to the UI. | Undefined comparisons and JSON. | Property tests |
| P15 | Modules read params only through `ParamView` and only keys they declare in `paramSpecs` (A6). | Makes `paramsRead` in the trace complete and lets the registry check the params file. | `ParamsCoverageTest` |
| P16 | A module never calls another module's internal functions; it calls the other module's public pure API, and the callee's `ModuleRef` is recorded in `trace.deps`. | Version attribution in reviews (C30). | Code review |

## A3. Numerics and the canonical grid

### A3.1 Number types

- The statistical engine (all modules in this document) computes in IEEE-754 `double`. Since Java 17 the
  basic operations are strict (JEP 306). Transcendental functions go through `StrictMath` (P5), wrapped in
  `Det.exp`, `Det.ln`, `Det.pow`, `Det.sqrt` (sqrt is exact in both, the wrapper keeps the code uniform).
- FitExpr (challenge rules) evaluates in `BigDecimal` with `MathContext(34, HALF_EVEN)` (C21, C22); its
  values cross into this document's modules only through grid-rounded fields.
- Integers (reps, set counts, pins in native steps) are `Int`.

### A3.2 Canonical grid (C22 and C42)

Every value that crosses a module boundary, is stored, or is hashed is rounded to its grid first.

| Grid id | Applies to | Rule | Source |
|---------|-----------|------|--------|
| KG | masses: `w_sys`, added load, assist kg, M, BW, plate sums | round to 0.01 kg | C22 |
| S | durations: rest, hold seconds, set duration | round to 0.1 s | C22 |
| RATIO | dimensionless fractions and constants: `f_bw`, `F_j`, `p = w/M`, `k`, `K` gains, multipliers | round to 1e-4 | C22 |
| REP | continuous rep-valued quantities: `n_eff`, `n_fresh`, predicted RIR, predicted reps, residuals in reps | round to 0.01 | C42 |
| STAT | statistical state and scores: `L`, `g`, `P` entries, `z_i`, `s_i`, `J` values, `ln k`, `sd_lnk` | round to 12 significant digits (`MathContext(12, HALF_EVEN)`) | C42 |
| NATIVE | native equipment values (pin lb, stack level, dumbbell kg) | the equipment's own step; stored as `numeric(7,2)` | DATA_MODEL E4 |

Why C42 adds the REP and STAT grids to C22's kg, s and ratio grids: rounding a covariance entry such as
`P_gg = 2.5e-5` to the ratio grid (1e-4) would set it to 0 and break the filter, so statistical state needs a
relative grid; predicted reps on a 1e-4 grid would be over-precise noise in snapshots.

### A3.3 Rounding procedure

```kotlin
object Grid {
    // Exact binary value of the double, then decimal rounding: identical on JVM and ART.
    fun kg(x: Double): Double    = BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()
    fun s(x: Double): Double     = BigDecimal(x).setScale(1, RoundingMode.HALF_EVEN).toDouble()
    fun ratio(x: Double): Double = BigDecimal(x).setScale(4, RoundingMode.HALF_EVEN).toDouble()
    fun rep(x: Double): Double   = BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()
    fun stat(x: Double): Double  = BigDecimal(x).round(MathContext(12, RoundingMode.HALF_EVEN)).toDouble()
}
```

- `BigDecimal(double)` (the exact constructor) is mandatory; `BigDecimal.valueOf(double)` goes through
  `Double.toString` and is forbidden (P13).
- Consequence worth knowing: the double nearest to 45.245 is 45.24499999..., so `Grid.kg(45.245) = 45.24`.
  PostgreSQL `round(45.245, 2)` gives 45.25; this is why `set_logs.effective_load_kg` is engine-written and
  only verified by the server with a 0.01 kg tolerance (DATA_MODEL 9.6.4).
- Equipment rounding (to loadable values) is a different operation, defined in B2.4; it happens before grid
  rounding.

### A3.4 Canonical number text (`Canon`)

Hashing uses RFC 8785 (JCS), which serialises numbers with the ECMAScript `Number.prototype.toString`
algorithm. Because every hashed double is first rounded to a grid with at most 15 significant digits, the
ECMAScript text can be produced from the decimal without any double-to-string routine:

```
Canon.esNumber(x: Double, grid): String
  d = grid-rounded BigDecimal of x, stripTrailingZeros(); if d == 0 return "0"   // also for -0.0
  s = unscaled digits of |d| (no leading zeros), k = len(s), n = k - scale(d)    // value = 0.s * 10^n
  if k <= n <= 21:   out = s + "0" * (n - k)
  elif 0 < n <= 21:  out = s[0:n] + "." + s[n:]
  elif -6 < n <= 0:  out = "0." + "0" * (-n) + s
  else:              out = s[0] + (k > 1 ? "." + s[1:] : "") + "e" + (n - 1 >= 0 ? "+" : "-") + |n - 1|
  return (d < 0 ? "-" : "") + out
```

Examples: 45.24 -> `45.24`; 100.00 -> `100`; 2.5E-5 (STAT) -> `0.000025`; 2.5E-7 -> `2.5e-7`;
1.2E21 -> `1.2e+21`. The implementation must pass the number vectors of RFC 8785 Appendix B that are
representable on the grids, plus the cases above (`CanonNumberTest`).

### A3.5 Units

- Mass is computed in kg. `1 lb = 0.45359237 kg` exactly (engineering-fact). Native values (pins in lb,
  stacks in levels) are kept as entered and converted only through B2 (C2, DATA_MODEL S3).
- Time inside modules: `Instant` (UTC) for elapsed time, `LocalDate` in the user's zone for days and weeks
  (C31, B1). Durations in seconds as `Double` on the S grid.

## A4. Deterministic ordering and ties

### A4.1 Canonical input order

The `EngineInputAssembler` (`:data`) and the `:sim` loaders produce lists in this order; modules assert it
in debug builds (`require(isCanonical(list))`).

| List | Canonical order |
|------|-----------------|
| sets of a session | `(session started_at, session_exercise position, set_index, side order L before R before both, round_index, attempt_index, set id)` |
| sessions | `(started_at or planned_start_at, id)` |
| weigh-ins and metric observations | `(observed_at, id)` |
| exercises in a plan | plan position, then `slug` |
| catalog candidates | `slug` |
| muscle groups and regions | taxonomy id (C9) ascending |
| calendar instances | `(begin Instant, instance key)` |
| params keys | Unicode code point order (same as JCS) |

### A4.2 Tie-breaks (documented per module, summarised)

| Situation | Tie-break |
|-----------|-----------|
| Two loads with equal score (RX-1, WARMUP-1, rounding) | the **easier** one: lower `w_sys` (less external load or more assistance, C2) |
| Two exercises with equal score | `slug` ascending, then UUID |
| RCM-1 grid points with equal objective | closer to `ln k0`, then lower `k` |
| Two sides with equal strength (unilateral) | `L` before `R` |
| Two warnings with equal rank | severity, then magnitude descending, then `rule_id`, then entity id |

## A5. Time

- `:algo` never reads a clock (P2). The gateway passes `ClockSnapshot(now: Instant, zone: ZoneId,
  tzdbVersion: String)` in every input; B1 derives local dates, ISO weeks and elapsed times from it.
- Elapsed durations (fatigue decay, SSE-1 time steps, rest) use Instants; calendar logic (which day, which
  ISO week, "the day before the test") uses local dates. A day boundary is local midnight.
- Time-zone rules: `java.time` zone rules on the JVM and on ART come from different tzdb copies. For
  Europe/Amsterdam the 2026 rules are identical in all current tzdb versions, but replay must not depend on
  that: every snapshot input carries `local_date`, `iso_week` and `tz_offset_min` of the relevant Instants as
  computed at decision time, and replay uses those stored values instead of recomputing them
  (`ClockSnapshot.fromStored`). `tzdbVersion` is logged in the input for diagnosis only and is not part of
  the canonical input hash (it is outside the payload).

## A6. Shapes

```kotlin
package dev.fitnessapp.algo.core

@JvmInline value class ModuleId(val value: String)          // E49, e.g. "rx", "calendar.classify"
data class SemVer(val major: Int, val minor: Int, val patch: Int)
data class ModuleRef(val id: ModuleId, val version: SemVer)  // rendered "rx@1.0.0"

data class ClockSnapshot(
    val now: Instant,
    val zone: ZoneId,                 // profiles.timezone (F18 default Europe/Amsterdam)
    val localDate: LocalDate,         // derived by clock (B1) or taken from storage on replay
    val isoWeek: String,              // "2026-W41" (E52)
    val tzOffsetMin: Int,
)

/** Built by the EngineGateway (:data) or by :sim. Never hashed as a whole (see A9). */
data class EngineContext(
    val engineVersion: SemVer,        // ENGINE_VERSION (C30)
    val params: ParamView,            // resolved + validated + read-tracking (A8)
    val paramsHash: String,           // JCS SHA-256 of the resolved {key: value} map
    val catalogVersion: SemVer,
    val challengeDefSha256: List<String>,  // sorted (C24)
    val tzdbVersion: String,          // diagnostic only
    val pinnedSessionId: String?,     // non-null while params are pinned to a session (A8.5)
)

/** The hashable input of one module call: everything the module reads, nothing else. */
@Serializable data class EngineInput<P>(
    val module: String,               // "rx@1.0.0"
    val scopeKey: String,             // DATA_MODEL 10.1, e.g. "session_exercise:<uuid>"
    val clock: ClockSnapshotDto,      // now (ISO-8601 UTC, millisecond precision), zone id, local_date, iso_week, tz_offset_min
    val payload: P,                   // module-specific; ids + grid-rounded numbers; no free text
)

data class ModuleResult<O>(
    val output: O,                    // @Serializable, grid-rounded (value classes Kg, Sec, Ratio, Rep, Stat)
    val trace: DecisionTrace,
    val warnings: List<Warning>,      // WE-1 output, or safety notices from rx/autoreg
)

@Serializable data class DecisionTrace(
    val module: String,               // "rx@1.0.0"
    val deps: List<String>,           // sub-module refs used, sorted, e.g. ["fatigue@1.0.0","load@1.0.0"]
    val paramsRead: List<String>,     // keys read through ParamView, sorted
    val steps: List<TraceStep>,       // execution order
    val chips: List<ReasonChip>,      // at most 3, F19 layer 1 (rendered by ui.reasonchips / Why sheet)
)
@Serializable data class TraceStep(
    val code: String,                 // stable code, e.g. "rx.candidates", "rx.cap.up", "sse.huber"
    val ruleId: String? = null,       // e.g. "WE-S2", challenge rule id, "C15"
    val values: JsonObject,           // grid-rounded numbers, enums, ids; never free text
)
@Serializable data class ReasonChip(val code: String, val args: JsonObject)

enum class Severity { INFO, WARN, STRONG, SAFETY }
enum class EvidenceGrade { STRONG, MODERATE, WEAK, EXPERT_OPINION, ENGINEERING_FACT }
@Serializable data class Warning(
    val ruleId: String,               // "WE-V1" (B15)
    val severity: Severity,
    val entity: EntityRef,            // {type: muscle_group|exercise|preference|session|set|plan, id}
    val magnitude: Double,            // 0..1 on the RATIO grid
    val evidenceGrade: EvidenceGrade,
    val citations: List<String>,      // short citation keys, e.g. "Pelland2024"
    val numbers: JsonObject,          // the values the copy shows
    val suggestion: Suggestion?,      // the one-tap "use suggested" payload, if any
    val enforced: Boolean,            // true only for SAFETY rules that the engine applied (B15.3)
)

interface EngineModule<P, O> {
    val ref: ModuleRef
    val paramSpecs: List<ParamSpec>   // keys "<module>.<constant>" this module owns (A8.2)
    fun run(input: EngineInput<P>, ctx: EngineContext): Outcome<ModuleResult<O>>   // Outcome = result or EngineError
}
```

Rules for payloads: identifiers are UUID strings or slugs; numbers are grid-rounded; enums are lower
snake-case strings from DATA_MODEL section 8; free text (notes, `deviation_note`, event titles) never enters
an input (DATA_MODEL S2). Target size of a JCS input is at most 16 KB (hard limit 64 KB, DATA_MODEL 9.5.2);
history slices are truncated to the window the module actually reads (for example SSE-1 needs only the
prior state plus this session's sets).

## A7. Versioning (C30)

- `ENGINE_VERSION` is one semver constant in `dev.fitnessapp.algo.EngineVersion` (ANDROID.md 27.1, C68),
  independent of the app version. It starts at 1.0.0.
- Every registry module has its own `id@semver`, starting at 1.0.0, registered in
  `fitness.algorithm_versions` with its `constants_hash` (JCS SHA-256 of the module's slice of the default
  params) **before** an APK using it is installed (DATA_MODEL 9.1.9 release rule).
- Bump rules for a module version:

| Change | Bump |
|--------|------|
| Output or trace schema changes incompatibly (field removed, meaning changed) | MAJOR |
| Any output byte can change for some valid input (formula, constant default moved into code, tie-break, new branch) | MINOR |
| No output byte changes for any input: refactor, performance, extra trace step codes that are additive | PATCH |

- `ENGINE_VERSION` bumps by the largest bump of any module in the release (a MINOR in one module makes the
  engine MINOR). Params-only changes do **not** bump any version; they change `params_hash`.
- Old module versions are not kept side by side in the code base. Replay of an old version builds `:algo`
  from the git tag recorded in `algorithm_versions.git_sha` (A10).

## A8. Params JSON

### A8.1 File

One JSON file per params version, bundled at
`algo/src/main/resources/fitness/params/default-params-<version>.json` (currently 1.0.1; C40; ANDROID.md 4 and 7.6) and seeded
into `fitness.algo_params` by migration 005:

```json
{
  "params_version": "1.0.0",
  "engine_version_range": ">=1.0.0 <2.0.0",
  "params": {
    "sse.sd_day": {
      "value": 0.035, "unit": "ln", "min": 0.01, "max": 0.10,
      "evidence": "strong", "source": "Grgic 2020 Sports Med Open (1RM CV median 4.2 %)",
      "tunable": true,
      "tuned_by": "one-step-ahead first-set n_eff error and 80 % CI coverage (D2, D3)"
    }
  }
}
```

### A8.2 Keys, types and validation

- Key = `<module>.<constant>` (C41, E53): the module part is a registry module id (dots allowed) and the
  constant name is lower snake_case, for example `rx.cap_up_compound`. The module part is the longest
  registered module id that prefixes the key, so `calendar.classify.accept_score` belongs to
  `calendar.classify`. Every key in Part C follows this form.
- Platform constants (rest-timer, notification, reminder and sync-backoff values such as the timer adjust
  step) are not params: they live in `:app` `PlatformConfig`, outside `params_hash`, and are logged in event
  payloads (C54, ANDROID.md section 25). The REST-1 policy values that decide durations (including the timer
  hard maximum `rest.timer_hard_max_s`, which the platform timer receives through the engine output) stay
  params.
- Allowed value types: number, integer, boolean, string enum, array of numbers, array of enum strings,
  object whose values are numbers or arrays of numbers (for class tables such as `rcm.k0` and for tiers
  such as `warmup.tier_high = {"fracs": [...], "reps": [...]}`), and `regex` (a string that must
  compile as a Java regular expression, at most 512 characters; the entry carries `"type": "regex"`; used
  for the calendar lexicons `calendar.classify.lex.*`, user decision 2026-10-08). Every number must be
  exactly representable as a short decimal (at most 10 significant digits). Times of day are integers in
  minutes after local midnight (0..1439, unit `min after local midnight`), never "HH:MM" strings.
- Each module declares `ParamSpec(key, type, unit, min, max, enumValues)`. At load time the
  `ParamsValidator` (pure, in `:algo`; called by the loader in `:data` and `:sim`) checks: every declared
  key is present, no undeclared key exists, types match, `min <= value <= max` (element-wise for tables).
  A defaults file that fails validation is a build failure (`ParamsCoverageTest`); a downloaded
  `algo_params` row that fails is ignored and the bundled defaults stay active (event `params_rejected`;
  not yet in the DATA_MODEL 9.8.1 registry, OI-A24).
- Metadata (`unit`, `evidence`, `source`, `tunable`, `tuned_by`, bounds) is documentation for reviews and
  validation; only `value` participates in the hash.

### A8.3 Hashes

- `params_hash` = lowercase hex SHA-256 of the UTF-8 bytes of JCS(`{key: value}`) of the **resolved**
  map (defaults overlaid with user overrides, A8.6). The app recomputes the hash of a downloaded defaults
  row and refuses a mismatch (DATA_MODEL 9.1.10).
- `constants_hash` of a module = the same over the module's slice of the defaults.

### A8.4 Resolution order

```
resolveParams(at: Instant, sessionStart: Instant?):
  base = newest algo_params row with status 'active' whose engine_version_range admits ENGINE_VERSION
         and whose params_hash verifies; else the bundled file
  t = sessionStart ?: at
  for each key in base (code point order):
     o = latest user_algo_params row for key with deleted_at null, approved (if source in {review, fitted}),
         valid_from <= t, engine_version_range admits ENGINE_VERSION (or null)
     value = o?.value ?: base.value
     if value fails the key's ParamSpec bounds: keep base.value, record params_override_rejected(key)   // event, OI-A24
  return ParamView(map, hash = sha256(JCS(map)))
```

### A8.5 Pinning per session

- At session start the resolved map is computed once, stored in `params_snapshots` (content-addressed by
  hash) and its hash on `workout_sessions.params_hash` (ANDROID.md 7.6). Every engine call during that
  session uses the pinned map, including in-session suggestions, rest, warm-ups and the post-session
  updates that run at finish.
- Outside a session (Today, Week, nightly jobs) the map is resolved at the call's `now`.
- A params change that arrives during a session activates at the next session start (event
  `params_activated`).

### A8.6 Per-user overrides (`user_algo_params`)

- Written only by the review tooling or the ModelTuner through MCP, after the user approved the change
  (DATA_MODEL 9.3.7). The user approves the proposed change list of a review as a batch, each change listed
  with its reason; nothing is written without that approval (F26). Each row: `param_key`, `value`,
  `valid_from`, `source` (`fitted`, `review`, `user`), `review_ref`.
- `valid_from` applies from the next session that starts at or after it. Overrides never change history:
  replay uses the params snapshot referenced by each recommendation row.
- Every tunable constant in Part C names the logged data that tunes it.

## A9. Snapshots and hashing (C24)

Each module call that produces something the user sees or that later modules consume writes one
`fitness.recommendations` row (envelope in DATA_MODEL 9.5.2, kinds in 10.1):

| Field | Content |
|-------|---------|
| `kind`, `scope_key` | DATA_MODEL 10.1, e.g. `rx.prescription` / `session_exercise:<uuid>` |
| `module_id`, `module_version`, `engine_version` | C30 |
| `params_hash`, `catalog_version`, `challenge_def_sha256[]` | C24 |
| `inputs` | `EngineInput` JSON (payload + clock + module + scope) |
| `inputs_hash` | `sha256(JCS(inputs))` lowercase hex |
| `outputs`, `trace`, `warnings` | `ModuleResult` parts, grid-rounded |

- JCS (RFC 8785): object members sorted by UTF-16 code units of the keys, no whitespace, strings in the
  JSON minimal escape form, numbers per A3.4. Arrays keep their (canonical, A4.1) order.
- Reuse rule: if the latest row for `(kind, scope_key)` has the same `(module_version, params_hash,
  inputs_hash)`, no new row is written (DATA_MODEL 9.5.2).
- Hash producers: only the client computes `inputs_hash`, `params_hash` and challenge `jcs_sha256`. The
  server's `jsonb::text` checksums are never compared with them (data_model research, "Hashing").
- What the snapshot does **not** contain: the output of other modules is passed in as payload values (for
  example RX-1 receives the SSE-1 state as numbers), so each snapshot is replayable on its own.

## A10. Replay guarantee

<a id="a10-replay-guarantee"></a>

**Guarantee R1 (exact replay).** For every `recommendations` row whose `inputs` is not pruned:
`Module[module_id@module_version].run(inputs, params_snapshots[params_hash])` built from the git tag of
`engine_version` returns `outputs`, `trace` and `warnings` whose JCS bytes equal the stored ones.

**Guarantee R2 (platform independence).** The JVM (`:algo:test`, `:sim`) and ART (the phone) produce the
same JCS bytes for every golden fixture (ANDROID.md 6.3 item 4, release blocker).

**Counterfactual replay (not a guarantee, a tool).** `:sim replay --module rx --version <candidate>
--params <file>` runs a newer module version or other params on stored inputs and writes a diff report
(load deltas, mode changes, warnings, prescription error against the actual `set_logs`). If the input
schema changed between versions, the candidate module ships an `InputMigrator` from the old payload schema;
a missing migrator is reported, never guessed.

Failure handling: a replay mismatch under R1 is a P1 bug (determinism broken); the review stops and reports
the module, row id and first differing JSON path.

## A11. Enforcement tests (in addition to ANDROID.md 6.3)

| Test | What it checks |
|------|----------------|
| `CanonNumberTest` | A3.4 formatting, RFC 8785 Appendix B vectors on the grids. |
| `JcsConformanceTest` | Key ordering, string escaping and nested structures against RFC 8785 examples. |
| `GridRoundingTest` | A3.3 cases including 45.245 -> 45.24 and STAT on 2.5e-5, 2.5e-7. |
| `DoubleRunTest` | Every golden fixture run twice in one JVM gives identical bytes (catches hidden state, P7). |
| `ShuffleInvarianceTest` | Payload lists shuffled with a seeded permutation, then re-canonicalised by the assembler helper, give identical output (P10/P11). |
| `ParamsCoverageTest` | Defaults file validates; every key is declared by exactly one module; no module reads an undeclared key (P15). |
| `GoldenFixtureTest` | Part D fixtures: output bytes and output hash equal the stored expected values. |
| `ReplayDeterminismTest` | `:sim replay` over `fixtures/replay/` reproduces stored outputs (R1); runs once real exports exist. |

## A12. Pipeline L0-L8

### A12.1 Precedence

`safety > challenge > user hard constraints > general`. Concretely, every consumer applies its inputs in
this order:

1. **Safety** (`SafetyState`, C33): forbidden joints/tags, stop lists and challenge injury modes remove or
   clamp candidates first. A challenge rule that conflicts with safety is suppressed (audit
   `suppressed_injury`, CHALLENGE_ENGINE). The D10 safety rules (no failure on high-risk lifts, load-jump
   caps, pain protocol, challenge hard constraints) are enforced on engine prescriptions (B15.3). One
   exception is decided by the user (D13, C45): the generic load-jump caps do **not** limit challenge
   RULE-mode loads; a challenge load above the generic cap raises WE-S2 at WARN severity (non-blocking) and
   is logged. The other safety rules (pain protocol, no failure on high-risk lifts, protected windows) still
   bind challenges.
2. **Challenge** (`ChallengeContract`, C11, C12, C34): reserved sessions, gap-rule scope, protected
   windows (`hard` ones as constraints, `penalty` ones as score terms), owned muscles, add-on slots and
   RULE-mode items are fixed before general planning.
3. **User hard constraints**: `never` preferences (C26), unavailable equipment, availability windows,
   `max_gym_visits_per_week` (D5), the hard part of the time budget (F11 as revised by C49: a calendar gym
   event is training time and the budget is the event duration minus 5 min).
4. **General**: targets, readiness, preferences as weights, evidence-based defaults; user soft
   preferences (rep/set preferences) are kept and warned about, never overridden (D10).

### A12.2 Stages

| Stage | Modules (owner) | Reads | Produces (type passed on) | Snapshot kinds |
|-------|-----------------|-------|---------------------------|----------------|
| L0 Normalize | `clock` (B1), `load` (B2), `bodyweight` (B3), `calendar.classify` | profile, equipment, raw set rows, weigh-ins, calendar instances, `now` | `NormalizedFacts { clock: ClockSnapshot; bw: BwSnapshot; sets: List<NormalizedSet(w_sys, LoadPoint, contraction, flags, q_rep, reason)>; loadables: Map<equipmentId, Loadables>; anchors: List<CalendarAnchor> }` | none (calendar classification is a `session_events` row, C20) |
| L1 Safety | `safety` | `NormalizedFacts`, pain and elbow `wellness_logs`, active challenge protocols | `SafetyState { jointFlags: Map<joint, {severity, until}>, forbiddenTags, stopList: List<exerciseId>, challengeModes }` | `safety.state` |
| L2 Challenge | `challenge.*` (CHALLENGE_ENGINE) | `challenge_events` fold, definitions, `SafetyState`, anchors | `ChallengeLayer { enrollment: EnrollmentState; contract: ChallengeContract; plans: List<ChallengeSessionPlan(RULE items)>; weekPlan }` | `challenge.contract`, `challenge.weekplan`, `challenge.session_plan` |
| L3 Accounting and estimation | `setcredit`, `readiness` (PLANNING); `rircal`, `rcm`, `sse`, `fatigue`, `hold`, `stall` (this doc) | `NormalizedFacts`, cached `exercise_model_state` | `ModelStates { rircal: {b, b_c, s_q}; rcm: Map<exerciseId, {k_used, sd_lnk}>; sse: Map<(exerciseId, side), SseState>; fatigue: Map<curveClass, {c_ref, d}>; hold: Map<(exerciseId, side), HoldState>; stall: StallReport }`, `AccountingWeek`, `ReadinessSnapshot` | `accounting.week`, `readiness.snapshot`, `stall.weekly` |
| L4 Targets | `targets`, `runs` (PLANNING) | `ModelStates`, `AccountingWeek`, goals, contract, `StallReport.global` | `WeeklyTargets { groups; deload: DeloadDecision; reentry: Map<exerciseId or group, {setsMult, rirAdd, remainingExposures}> }` | `targets.weekly` |
| L5 Planning | `allocator`, `today`, `weekplan` (PLANNING) | targets, readiness, contract, anchors | `WeekPlan`, `TodayOptions` | `weekplan.plan`, `today.options` |
| L6 Session build | `elig`, `select`, `order`, `time`, `injury`, `rotate` (PLANNING) then `rx` (B9), `warmup` (B10), `rest` (B11), `warnings` (B15) | chosen template or option, all of the above | `SessionPlan { blocks: List<Block{kind, items: List<PlannedItem{exercise, mode, sets: List<PlannedSet{side, targetReps or targetSeconds, rirTarget, rirPred, load: LoadPoint, restAfterS}>, warmups, shadow?}>}>, estMin, warnings }` | `session.plan`, `rx.prescription`, `warmup.sets`, `warnings.evaluation` |
| L7 In-session | `reason` (B12), `challenge.eca` in-session rules (auto-applied to the remaining sets with Undo, D14, C46; CHALLENGE_ENGINE 6.10), `autoreg` (B13; general items only, RULE items in shadow), `substitute`, `rest` (B11), `warnings` S-rules (B15) | each logged set (`SetOutcome`), the pinned `SessionPlan`, `ModelStates` at session start | `InSessionDecision { estimatorClass, restS, suggestion?: Suggestion, stop?: StopAdvice, safetyNotices }` | `autoreg.suggestion` |
| L8 Post-session | `rircal`, `rcm`, `sse`, `hold`, `stall` (per exercise) in that order; then `challenge` `session.completed` (its rules read `userK` from the engine facts captured **before** this L8 run, CHALLENGE_ENGINE 3.9); then `setcredit`, `readiness`, `weekplan` replan | the finished session's facts | updated `ModelStates` (persisted to `exercise_model_state` caches) | `estimation.rircal`, `estimation.rcm`, `estimation.sse`, `estimation.hold`, `stall.weekly` (weekly), `estimation.fatigue` (nightly) |

### A12.3 Triggers

| Trigger | Stages run |
|---------|-----------|
| App open, daily tick (first run after local midnight) | L0-L5; nightly jobs (FAT-1 personalisation, REST-1 adaptation) |
| Calendar change (ContentObserver, debounced) | L0 (classification), L2, L5 |
| Pain report or elbow check | L1 first, then L2, L5 (and L6/L7 if a session is running) |
| Session start | L0-L3 for the session's exercises, pin params, L6 |
| Set logged | L7 |
| Session finish | L8, then L5 |
| Week close (synthesised tick on Monday at `challenge.enrollment.week_close_local_time`, default 04:00 local, run after the challenge's `week.closed` / `week.started` events; CHALLENGE_ENGINE 4.3 and 4.6) | `stall` weekly + global, `targets` (deload decision), `weekplan` |

Why 04:00 and not midnight: a session that started on Sunday evening and finished after midnight still
belongs to the closing week (B1 `sessionDate`), and the contract's mode for the new week (for example a
deload week, F8) is only known after the challenge's boundary event, which CHALLENGE_ENGINE places at
04:00. Between Monday 00:00 and the tick, Today and Week display the new ISO week, but the weekly
evaluations above have not run yet (OI-A21, resolved).

---

# Part B. Module specifications

Each module section has the same structure: purpose, inputs, outputs, algorithm, constants (keys are listed
with values; the full table with bounds, evidence and tuning sources is in Part C), edge cases, logging and
tests. Snapshot kinds and review keys follow DATA_MODEL section 10.

<a id="clock-1"></a>
## B1. `clock` (CLOCK-1): local days, ISO weeks, elapsed time, mesocycle week

**Purpose.** The single Clock/Week utility of C31. Every module that needs "today", "this week", "days
since" or "the mesocycle week" calls it; no module rolls its own date logic (critique gap 1).

**Inputs.** `ClockSnapshot` (A6); for the mesocycle: the `ChallengeContract` (if an enrollment is active),
`profiles.onboarding_completed_at`, end and pause dates of past enrollments.

**API (pure functions).**

| Function | Definition |
|----------|-----------|
| `localDate(i, zone)` | `i.atZone(zone).toLocalDate()` |
| `isoWeek(d)` | ISO week-based year and week, `YYYY-Www` from `IsoFields.WEEK_BASED_YEAR` and `IsoFields.WEEK_OF_WEEK_BASED_YEAR`, zero-padded with integer arithmetic (never a locale-dependent formatter, P8) |
| `isoWeekStart(d)` | the Monday on or before `d` |
| `elapsedHours(a, b)` | `(b.toEpochMilli() - a.toEpochMilli()) / 3_600_000.0` |
| `elapsedDays(a, b)` | `elapsedHours(a, b) / 24`; used for SSE-1 `dd` and every decay model; never calendar days |
| `calendarDays(d1, d2)` | `ChronoUnit.DAYS.between(d1, d2)` on local dates; used for "the day before", staleness and cooldowns |
| `atLocal(d, t, zone)` | `ZonedDateTime.ofLocal(LocalDateTime.of(d, t), zone, null).toInstant()`: a time inside the spring gap moves forward by the gap; a time inside the autumn overlap takes the **earlier** offset (java.time behaviour, engineering-fact) |
| `sessionDate(session)` | `localDate(started_at ?: planned_start_at)`; a session belongs to the local date and ISO week of its start, even when it ends after midnight |
| `mesocycle(date)` | below |

**Mesocycle week (C31).**

```
mesocycle(date):
  if an enrollment is active (status = active) at date:
     return Meso(index = contract.blockIndex, week = contract.blockWeek,
                 isDeloadWeek = contract.isDeloadOrTestWeek, source = "challenge")
     // repeated weeks and inserted deload weeks are included because the contract reports them (C31)
  anchor = isoWeekStart(max(localDate(onboarding_completed_at),
                            the day after the last enrollment became paused, completed or abandoned))
  w = calendarDays(anchor, isoWeekStart(date)) / 7            // integer division, >= 0
  return Meso(index = w / clock.meso_weeks + 1, week = w % clock.meso_weeks + 1,
              isDeloadWeek = false, source = "calendar")       // F8 / C13: no planned deload without a challenge
```

**DST rules (F18).** Europe/Amsterdam leaves summer time on Sunday 2026-10-25: 03:00 CEST becomes 02:00 CET.
Tests must pin these consequences: the local day 2026-10-25 lasts 25 hours; Saturday 19:45 to Sunday 19:45
is 25 elapsed hours; 02:30 on 2026-10-25 occurs twice and `atLocal` maps it to 00:30Z (the earlier, CEST
occurrence); decay models use elapsed hours, so fatigue decays for 25 h across that night, not 24 h.

**Constants.** `clock.meso_weeks` = 5 (F12, C31). The week start (ISO Monday) and the default zone
(Europe/Amsterdam) are fixed by F18 and are not params.

**Logging.** Every snapshot input carries `local_date`, `iso_week` and `tz_offset_min` (A5).

**Tests.** `ClockDstTest`, `IsoWeekTest`, `MesocycleTest` (fixtures GF-CLK-01..08 in Part D).

<a id="load-1"></a>
## B2. `load` (LOAD-1): system load, assistance, loadable values, rounding

**Purpose.** One function that puts every exercise (barbell, Smith, dumbbell, stack, cable, plate-loaded
machine, assisted machine, dip belt, bodyweight) on the system-load scale `w_sys`, and one enumeration of
exactly loadable options, with native units preserved (C1, C2, critique contradictions 1 and 2). Every
module, including FitExpr's `roundLoad`, `roundPin`, `assistKg` and `oneArmLoad`, calls this module;
nothing else implements load arithmetic.

### B2.1 Inputs

- Exercise attributes (DATA_MODEL 9.2.1): `bw_fraction` (`f_bw`, C1), `implement_base_kg`, `laterality`,
  `allowed_load_modes`, `load_mode`.
- Equipment record (`gym_equipment`, DATA_MODEL 9.3.3): `scheme_type`, `load_scheme`, `calibration_rev`.
- Bodyweight `BW` from B3 (the session snapshot).
- For logged sets: the native input (`input_value`, `input_unit`), `implement_count`, `assist_pin_native`.

### B2.2 The LOAD function

```
w_sys = f_bw * BW + implement_base_kg + added_kg - assist_kg            (KG grid)

added_kg  = external mass in kg as defined per scheme in B2.4 (both dumbbells for a bilateral DB lift)
assist_kg = assistKg(pin_native, calibration) for load_mode assisted_machine, else 0
w_sys is undefined (null) for load_mode assisted_ordinal (finger assistance, C2),
   when BW is needed (f_bw > 0) but missing, or when implement_base_kg is needed but unknown.
```

This is the identity that `set_logs` checks with a 0.01 kg tolerance (DATA_MODEL 9.6.4). The engine writes
`effective_load_kg` with it; the server only verifies.

`f_bw` values (C1; stored per exercise in the catalog, never in code): pull-up, chin-up, neutral-grip and
one-arm pull-up family and dips 1.00; push-up 0.64; knee push-up 0.49; feet raised 30 cm 0.70, 61 cm 0.74;
hands raised 30 cm 0.55, 61 cm 0.41 (Ebben 2011, moderate); inverted row 0.60 (weak). Plank-type holds and
hanging leg raises have no load model (`f_bw = 0`; B8 then uses the time-only model). The anatomical value
0.955 for the pull and dip family (de Leva 1996) exists only as the review option
`load.review_f_bw_pull_dip`, read by `:sim` counterfactuals and never by the live engine.

### B2.3 Assistance (C2)

```
assistKg(pin_native, cal) = pin_native * unit_kg(cal.unit) * cal.ratio + cal.offset_kg     (KG grid)
unit_kg("lb") = 0.45359237 ; unit_kg("kg") = 1.0
oneArmLoad(pin) = f_bw * BW - assistKg(pin)          // f_bw = 1.00 for the one-arm family
```

- The calibration record per machine is `{unit lb, step 5, min 0, max, offset_kg 6.5, ratio 1.0}` (F7,
  `calibration_source = user_estimate`). Pin 0 still assists `offset_kg`; true unassisted work is a
  different load mode (bodyweight), and finger assistance is ordinal only (C2).
- `set_logs` stores the raw pin, the unit, the machine id, the calibration revision and the computed assist
  kg (C2, DATA_MODEL 9.6.4).
- Numbers at BW 70.7 (C2 offset included): pin 50 lb -> assist 29.18 kg -> `w_sys` 41.52 kg (58.7 % BW);
  45 -> 26.91 -> 43.79; 40 -> 24.64 -> 46.06 (65.1 % BW); 35 -> 22.38 -> 48.32; 30 -> 20.11 -> 50.59;
  0 -> 6.50 -> 64.20.
- **Calibration change.** When `calibration_rev` of a machine changes (for example the offset is measured
  with a bathroom scale), the stored facts stay as logged, but L3 rebuilds every SSE-1, RCM-1 and HOLD-1
  state that used the machine, recomputing `assist_kg` of past sets with the **new** calibration (the
  machine did not change, only our knowledge of it). The rebuild is logged as trace step
  `sse.rebuild.calibration` with the old and the new revision.

### B2.4 Loadable sets per scheme type

Every set is sorted ascending by `w_sys`, de-duplicated on the KG grid with tolerance `load.epsilon_kg`
(0.005 kg), and every element is a `LoadPoint` (B2.8).

| `scheme_type` | Native value shown | `added_kg` / `assist_kg` | Enumeration |
|---------------|-------------------|--------------------------|-------------|
| `plates` (barbell) | total including the bar, e.g. "60 kg" | `added_kg = bar_kg + 2 * sum(pairs)`, `implement_base_kg = 0` | pairs per plate size `p_i` = `floor(count_i / 2)`; all totals `bar_kg + 2 * sum(j_i * p_i)` with `0 <= j_i <= pairs_i`. Default inventory while unconfirmed: bar 20, plates 25/20/15/10/5/2.5/1.25 x 20 each (unlimited, params 1.0.1) (`load.default_plate_inventory`, unverified, F7) |
| `plates` (Smith, plate-loaded leg press) | plates only | `added_kg = 2 * sum(pairs)`, `implement_base_kg` = Smith bar or sled mass | as above without the bar; if `implement_base_kg` is unknown (null; F7 asks), `w_sys` is undefined and RX-1 uses DOUBLE on the native value (B9.6) |
| `belt` | added kg, e.g. "BW + 22.5 kg" | `added_kg` = sum of the chosen plates (plus a dumbbell) | all multisets of the belt inventory with at most `max_items` items and sum <= `max_kg`; if `dumbbells_on_belt`, also `d + s` for each dumbbell value `d` and multiset `s` with at most `max_items - 1` items. Defaults `max_kg` 60, `max_items` 4 (expert-opinion, editable in the checklist) |
| `dumbbells` | per hand, e.g. "22.5 kg" | `added_kg = implement_count * v` | the inventory list `values`; `implement_count` = 2 for bilateral lifts, 1 for each unilateral or alternating side row (B2.6) |
| `stack` | the stack label in its unit | `added_kg = (v + addon) * unit_kg * ratio` | `v = start + i * step`, `i = 0..floor((max - start) / step)`; at most one add-on from `add_on_steps` per set (add-ons are mutually exclusive). For `unit = level` the stack may carry `level_kg` (C43), and then `unit_kg(level) = level_kg`; a level stack **without** `level_kg` is ordinal: `w_sys` is undefined, RX-1 uses DOUBLE on the level and SSE-1 skips it (C43) |
| `pin_assist` | the pin in lb, e.g. "Pin 40 lb" | `assist_kg = assistKg(pin)` | pins from `min` to `max` in `step`; `max = null` (not yet asked) uses `load.pin_max_unknown_native` (100 lb) |
| `bodyweight` | "BW" | 0 | `{0}` |
| `fixed` | the implement | `kg` | `{kg}` |

### B2.5 Rounding directions

All directions are defined on `w_sys`, which unifies external load and assistance: **easier = lower
`w_sys`** (less external load or more assistance).

| Function | Definition | Used by |
|----------|-----------|---------|
| `easierOrEqual(W, t)` ("down") | `max{w in W : w <= t + eps}`; if none, `min W` | WARMUP-1, SUB-1 start loads, FitExpr `roundLoad(x, 'down')` |
| `harderOrEqual(W, t)` ("up") | `min{w in W : w >= t - eps}`; if none, `max W` | FitExpr `roundLoad(x, 'up')` |
| `nearest(W, t)` | `argmin abs(w - t)`; ties within `eps` go to the **easier** value | FitExpr `roundLoad(x, 'nearest')`, re-targeting after a BW change |
| `nextHarder(W, w)`, `nextEasier(W, w)` | the neighbour in the sorted set | RX-1 DOUBLE, AUTO-1, STALL-1 |

Pins are rounded through the same functions after mapping pin to `w_sys`, so "nearest pin" breaks ties
toward **more assistance** (C2). The challenge research's `roundPin(x, 'nearest') = round_half_down`
(ties toward less assistance) contradicted C2; CHALLENGE_ENGINE 3.8 now defines FitExpr's `roundPin` and
`roundLoad` with the same tie rules as this section, and their implementation calls these functions.

RX-1 never rounds a continuous optimum; it enumerates `W` directly (B9), so its choices have no rounding
gap. The gap between the continuous ideal and the chosen loadable is still logged in reps:
`gap_reps = (w_chosen - w_ideal) / w_ideal * (k + n - 1)` (RCM-1 slope, B5).

### B2.6 Unilateral work and the dumbbell convention

- Unilateral exercises have one row per side (`side = L | R`, C7) and separate estimator states per side
  (SSE-1, HOLD-1). `w_sys` is per side: one-arm assisted pull-up `f_bw * BW - assist`; one-arm dumbbell row
  `v` (one implement).
- Dumbbell values are always **per hand** in the UI and in `input_value`; `implement_count` is 2 for
  bilateral lifts (DB bench: `added_kg = 2v`, `w_sys = 2v`) and 1 per side row for unilateral or
  alternating lifts (`w_sys = v` per side). Volume load for reviews follows `added_kg` (DATA_MODEL 9.6.4).
- The pin applies to both sides by default (one pin per set, UX 11.7.1); a per-side pin is stored per row.

### B2.7 One scale across modes

An exercise whose `allowed_load_modes` contains several modes (for example the assisted, bodyweight and
weighted pull-up) keeps **one** SSE-1 series on `w_sys`; a mode switch is just a load change. Example at
BW 70.7: assisted pin 20 lb -> 55.13 kg; pin 0 -> 64.20 kg (the 6.5 kg offset still assists); bodyweight
-> 70.70 kg; belt +5 -> 75.70 kg. The pin-0-to-bodyweight jump is 6.5 kg (+10 %), which is why RX-1 treats
such transitions as coarse steps (B9.6).

### B2.8 Outputs

```kotlin
data class LoadPoint(
    val mode: LoadMode,                                  // E3
    val nativeValue: Double?, val nativeUnit: String?,   // E4; null for bodyweight
    val implementCount: Int?, val perImplementKg: Double?,
    val addedKg: Double, val implementBaseKg: Double,
    val assistPinNative: Double?, val assistKg: Double?, val assistOrdinal: Int?,
    val bwKg: Double?, val fBw: Double,
    val wSys: Double?,                                   // KG grid; null when undefined (B2.2)
    val plateBreakdown: List<Double>,                    // descending; per side for barbells, total for belts
    val equipmentId: String?, val calibrationRev: Int?,
)
```

**Plate breakdown.** Among the multisets of the inventory that sum exactly to the target (per side for a
barbell, total for a belt), choose the one with the fewest items; ties go to the lexicographically larger
sequence when sorted descending (prefer big plates). A deterministic DP over plate sizes in descending
order computes it; a target without an exact multiset is not loadable and yields
`EngineError.NotLoadable`. Examples with the fixture belt inventory of Part D (20 x 2, 10, 5, 2.5,
1.25 x 2): 47.5 = 20 + 20 + 5 + 2.5; 27.5 = 20 + 5 + 2.5; 22.5 = 20 + 2.5; 26.25 = 20 + 5 + 1.25.

### B2.9 Validation at L0

For every logged set the normaliser recomputes `w_sys` from the stored components (with the calibration of
its `equipment_calibration_rev`, or with the current revision after a recalibration, B2.3) and compares it
with `effective_load_kg`. A difference above 0.01 kg is recorded as trace step `load.mismatch` and the
recomputed value is used. Sets whose `w_sys` is undefined or `<= 0` stay facts but are excluded from SSE-1,
RCM-1 and RIRCAL-1.

### B2.10 Constants

`load.lb_kg` 0.45359237; `load.epsilon_kg` 0.005; `load.default_plate_inventory`;
`load.belt_max_kg_default` 60; `load.belt_max_items_default` 4; `load.pin_max_unknown_native` 100;
`load.review_f_bw_pull_dip` 0.955. Bounds, evidence and tuning in Part C.

### B2.11 Logging and tests

Logged per set (facts, DATA_MODEL 9.6.4): native value and unit, implement count, added kg, raw pin, assist
kg, calibration revision, BW, `f_bw`, `effective_load_kg`. In `rx.prescription` traces: the continuous
ideal, the chosen loadable and `gap_reps`. Tests: `LoadModelTest` (identity; C2 numbers 35/40/50 lb ->
22.38/24.64/29.18 kg), `LoadablesTest` (each scheme), `RoundingDirectionTest` (ties toward easier),
`PlateBreakdownTest`, `CalibrationRebuildTest`.

<a id="bw-1"></a>
## B3. `bodyweight` (BW-1): one `BW(t)` (C32)

**Purpose.** One bodyweight function: smoothed for prescriptions and estimation, raw for gates, a snapshot
on every session and set, and a staleness prompt (critique gap "bodyweight handling is fragmented").

**Inputs.** `metric_observations` with `metric_code = 'bw'`, not deleted, value inside
`[bodyweight.min_kg, bodyweight.max_kg]` (20-300); `profiles.bodyweight_fallback_kg`; the session (kind,
start Instant, test weigh-in if any).

**Algorithm.**

```
dayMeans = for each local date with observations at or before t (canonical order):
              the mean of that date's values                   // two weigh-ins on one day count once
BW_ewma(t): E = dayMeans[0]; for each later day mean x: E = E + alpha * (x - E)    (alpha = bodyweight.ewma_alpha)
            return Grid.kg(E)

sessionSnapshot(session):
  if session.kind == test and a bw observation exists in this session before its first set:
       return (value = that raw value, source = test_weighin)               // C32: raw value for gates
  if any observation exists at or before the session start: return (BW_ewma(start), ewma)
  if a profile fallback exists:                              return (fallback, fallback)
  return (null, none)
  stale = calendarDays(local date of the last observation, session local date) > bodyweight.stale_days

BW_gate(test session) = the raw test weigh-in (above); none -> the challenge marks the gate input missing
```

- Every set in a session uses the session snapshot (constant within the session) and stores it in
  `set_logs.bodyweight_kg`; the session row stores value, source and staleness (DATA_MODEL 9.6.1).
- On a test day the prescription BW and the gate BW are the same raw value (the wizard asks for the
  weigh-in first, F10). After the test the EWMA includes the new observation.
- No BW at all: exercises with `f_bw > 0` have an undefined `w_sys`; RX-1 falls back to "repeat the last
  native values" (B9.10) and SSE-1 skips their sets until a weigh-in exists. A weigh-in prompt is raised
  (UI-07).
- The OAP threshold is stored as `ratio x BW` and evaluated with `BW_gate` (C32): a phase gate uses the
  test-day bodyweight, and the dashboard also shows the forward threshold at the current `BW_ewma(t)` (C62).
  That evaluation belongs to CHALLENGE_ENGINE; this module only supplies both values.
- Linear interpolation between weigh-ins (data_model research S4) is a review-view option only.

**Example.** One weigh-in of 70.7 kg on 2026-10-04 (fixture assumption): `BW_ewma` = 70.70 for every
session until the next weigh-in; on 2026-10-19 the snapshot is stale (15 days > 14). A raw test weigh-in of
70.9 kg on 2026-10-11 (fixture assumption) gives gate BW 70.90; the EWMA afterwards is
`70.7 + 0.3 * (70.9 - 70.7) = 70.76`.

**Constants.** `bodyweight.ewma_alpha` 0.3 (C32), `bodyweight.stale_days` 14 (C32), `bodyweight.min_kg` 20,
`bodyweight.max_kg` 300.

**Logging and tests.** Session and set snapshots are facts. `BodyweightTest`: EWMA, day merge, staleness at
14 and 15 days, the test-day raw value, the no-data path.

<a id="rircal-1"></a>
## B4. `rircal` (RIRCAL-1): RIR bias and noise calibration (C17)

**Purpose.** Correct the user's systematic misjudgement of reps in reserve and estimate its noise, so that
e1RM estimates and prescriptions are not biased. People under-predict reps to failure by about 0.95 reps on
average (Halperin 2022, 95 % CI 0.17-1.73, moderate); accuracy is better near failure and much worse above
12 reps to failure. Trained lifters near failure err by about 0.65 reps (Refalo 2024).

**Scope (C17, C44).** The estimators and prescription modules of this document (SSE-1, RCM-1, FAT-1, RX-1,
AUTO-1, STALL-1) use `q_true`. Volume accounting does not: SetCredit (ALGORITHMS_PLANNING section 3) counts
sets with the reported RIR, and the bias is applied only inside the estimators (C44). Challenge rules always
evaluate the raw `rir_reported` (CHALLENGE_ENGINE); RIRCAL-1 never changes a stored fact.

### B4.1 State

`exercise_model_state` rows with `model = 'rircal'`: one user row (`class_key = 'user'`) with `{b, s_q, n}`
and one row per curve class (`class_key = <curve_class>`) with `{b_c, n_c, last_check_date}`; plus
`last_check_week` (ISO week of the last explicit check) with the number of checks in that week, so that
`rircal.max_checks_per_week` above 1 stays meaningful. Initial values: `b = rircal.b0` (0.5),
`s_q = rircal.sq0` (1.0), `b_c = b0`, `n = n_c = 0`.

### B4.2 Applying the calibration

```
b_eff(class) = (n_c >= rircal.class_min_samples) ? b_c : b

q_true(set):
  if set ended in failure:            // flag failed_rep, or set_kind in {test, amrap} with q_rep == 0
       return 0                       // (a test-day 3RM is recorded with RIR 0 by protocol, C63),
                                      // or the total reps of an explicit calibration set (B4.3)
  if rir missing:  return REASON-1 imputation (B12.4)
  q = q_rep + b_eff(class of exercise)
  return clamp(q, 0, 10)              // "5+" chip: q_rep = 5 and the set is a lower bound (SSE-1 class lower_bound)

sigma_q(set) = base(n) * s_q + rircal.sigma_per_rir * q_rep          (q_rep = 0 when imputed)
   n = reps + q_true; base(n) = 1.0 if n <= 12; 1.5 if 12 < n <= 20; 2.5 if n > 20   (keys sse.sigma_q_base_*)
   imputed RIR: sigma_q = max(sigma_q, reason.impute_sigma_q)
```

Within one session every module uses the `b`, `b_c` and `s_q` in effect at session start (loaded with the
other model states when the params are pinned, A8.5). An explicit or implicit update computed in L8 takes
effect from the next session, so SSE-1 and RCM-1 at the end of the same session still use the session's
values (simpler replay, no order dependence inside L8).

### B4.3 Explicit calibration check (F14)

- **Opt-in and eligibility** (evaluated at L6 for the planned session): `profiles.rir_calibration_opt_in`;
  exercise `safety_class` in `rircal.eligible_safety_classes` (`machine`, `cable`, `isolation`); not tagged
  `challenge`, not in RULE mode, not `high_risk`; no SafetyState flag on its joints; not a deload week; no
  explicit check yet in the current ISO week (`rircal.max_checks_per_week` = 1); the exercise's curve class
  has had no check in the last `rircal.class_cooldown_days` (21).
- **Choice.** Among eligible exercises in plan order, the one whose curve class has the oldest last check
  (never checked counts as oldest); ties by plan position. The check is that exercise's **last** working
  set, `set_kind = calibration`, prescribed normally (target reps `r*`).
- **Protocol (UX).** At `r*` reps the user taps the RIR chip (this is `q_pred`, stored as `rir_reported`),
  keeps going to technical failure and enters the extra reps (`calib_extra_reps`).
- **Update.**

```
e      = extra - q_pred                       // true minus predicted reps in reserve
r      = e - b_before
b      = clamp(b_before + rircal.lr_explicit * r, rircal.b_min, rircal.b_max)
s_q    = clamp(sqrt(rircal.sq_decay * s_q^2 + (1 - rircal.sq_decay) * r^2), rircal.sq_min, rircal.sq_max)
b_c    = clamp(b_c + rircal.lr_explicit * (e - b_c), b_min, b_max); n_c += 1     // for the exercise's class
n     += 1
```

- The calibration set itself is a to-failure observation for SSE-1 and RCM-1: reps `r* + extra`, `q_true = 0`.

**Example.** `b = 0.5`, `s_q = 1.0`; leg extension, `r* = 12`, `q_pred = 2`, extra = 4: `e = 2`, `r = 1.5`,
`b = 0.5 + 0.2 * 1.5 = 0.80`, `s_q = sqrt(0.8 * 1 + 0.2 * 2.25) = 1.118`.

### B4.4 Implicit update (unintended failure)

Applies once per exercise per session, at the first set `j >= 2` with `q_rep_j = 0` and either the flag
`failed_rep` or `reps_j < planned_reps_j`, when set `j - 1` has a chip RIR at the same load, and neither set
has a reason in {`grip_failure`, `pain`, `technique_breakdown`, `torso_rotation`, `occupied`,
`equipment_missing`, `no_time`}:

```
F_{j-1}, F_j from FAT-1 (estimation recursion, B7.3) with cap_i = reps_i + q_rep_i + b for every i < j
         (a missing rest takes fatigue.rest_ref_s, where c(rest) = c_ref)
n_{j-1}^implied = reps_j * (1 - F_{j-1}) / (1 - F_j)     // set j ended at true failure (q = 0)
e_impl = (n_{j-1}^implied - reps_{j-1}) - q_rep_{j-1}    // true RIR of set j-1 minus the reported one
b = clamp(b + rircal.lr_implicit * (e_impl - b), b_min, b_max)         // s_q is not updated
```

### B4.5 Reason hook

REASON-1 (`too_easy`): if `too_easy` was given on the same exercise in two consecutive exposures while the
reported RIR was `<= q*`, then `b = clamp(b + reason.too_easy_b_step, b_min, b_max)` (update type
`reason_hook`).

### B4.6 Outputs, logging, tests

Snapshot `estimation.rircal` (scope `user`): `b_before`, `b_after`, `s_q_before`, `s_q_after`, `e`, `type`
(`explicit`, `implicit`, `reason_hook`), `class`, `n_c` (DATA_MODEL 10.2 keys). A review checks whether the
bias depends on reps or load (Halperin's moderators) and whether `b` should become a function of `n`.
Tests: `RirCalTest` (example above, clamps, per-class switch at 5 samples, implicit path, hook,
eligibility filters, one check per ISO week across the 2026-10-25 DST change).

<a id="rcm-1"></a>
## B5. `rcm` (RCM-1): personal rep-capacity curve (C3)

**Purpose.** Convert between load, reps and RIR and the 1RM, with a curve flatness `k` that is personal per
user and exercise. The model (C3) is the generalised Epley form

```
cap(w; M, k)     = 1 + k * (M / w - 1)              // reps to failure from fresh at load w
load(n; M, k)    = M / (1 + (n - 1) / k)            // load that allows exactly n reps
e1RM(w, r, q; k) = w * (1 + (r + q - 1) / k)        // 1RM from r reps with q in reserve
pctPerRep(n, k)  = 1 / (k + n - 1)                  // relative load change per rep: d ln w / d n = -1/(k + n - 1)
```

`pctPerRep` reproduces the RPE chart (Tuchscherer): 3.1 % per rep at `k = 30, n = 3` and 2.6 % at `n = 10`,
so one formula converts RIR differences into load changes (AUTO-1, RX-1). App_benchmarks' `%1RM = 1 / (1 +
kappa (r - 1))` is the same model with `kappa = 1/k` (alias only, C3). Review SQL never recomputes e1RM; it
reads the engine-logged per-set values (C3, DATA_MODEL 10.2).

**Class priors (C3; Nuzzo 2024 meta-regression, 952 tests, 7,289 people: exercise is the main moderator).**

| `curve_class` | Examples | `k0` | Evidence |
|---------------|----------|------|----------|
| `upper_compound` | bench, presses, rows, pulldowns, pull-ups, dips | 30 | moderate (Nuzzo bench k 27-32) |
| `lower_machine_compound` | leg press, hack squat, pendulum squat | 45 | moderate (Nuzzo leg press k 42-48) |
| `lower_free_compound` | squat, RDL, hip thrust, lunge | 36 | expert-opinion (interpolation, Shimano 2006) |
| `upper_isolation` | curls, triceps, lateral raise, fly, face pull | 26 | expert-opinion (Marzagao 2026, weak: lighter exercises have steeper curves) |
| `lower_isolation` | leg extension, leg curl, calf, adductor | 32 | expert-opinion |

`sigma_lnk = 0.30` (C3; Nuzzo between-person SD of 2.51 reps at 80 % is a CV of about 0.31).

### B5.1 Fit (L8, after every session that contains the exercise; per user x exercise, sides pooled)

```
D = sets of this exercise in the last rcm.lookback_days (56) before the fit Instant with:
      contraction dynamic, w_sys defined and > 0, set_kind in {working, top, backoff, amrap, calibration, test},
      REASON-1 class normal or downweight (lower_bound and excluded sets are not used),
      (q_rep <= rcm.max_q_rep (4) or the set ended in failure), n_eff <= rcm.max_n_eff (20)
for each set i: n_fresh_i = n_eff_i / (1 - F_i)        // F from FAT-1 estimation recursion (B7.3)
window(i) = floor(calendarDays(local date of set i, fit local date) / rcm.window_days)   // 14-day windows, 0..3
a window is informative if it has >= rcm.min_sets_per_window (2) sets and
   max(n_fresh) - min(n_fresh) >= rcm.min_spread_reps (3)
m = number of informative windows
if m == 0:
   k_used = pooledClassK(user, curve_class) ?: k0(curve_class)    // same procedure pooled over the user's
   go to RATE LIMIT                                               // exercises of the class, groups = (exercise, window)
grid: k_g = exp(ln 12 + (ln 90 - ln 12) * g / 79), g = 0..79           (rcm.grid_n 80, rcm.k_min 12, rcm.k_max 90)
J(k_g) = sum over informative windows W, sets i in W of huber((e_i - mu_W) / s_i, rcm.huber_delta)
         + (ln k_g - ln k0)^2 / (2 * sigma_lnk^2)
   e_i  = ln w_i + ln(1 + (n_fresh_i - 1) / k_g)
   mu_W = sum(e_i / s_i^2) / sum(1 / s_i^2)         over i in W
   s_i  = SSE-1 per-set sd (B6.3) evaluated with k_prev (keeps J a function of k_g only through e_i)
   huber(x, d) = x^2 / 2 if |x| <= d else d * (|x| - d / 2)
k_hat  = argmin_g J (ties: closer to ln k0, then lower k)
sd_lnk = sqrt(1 / J'') with J'' = (J(g+1) - 2 J(g) + J(g-1)) / h^2, h = (ln 90 - ln 12) / 79;
         if k_hat is a grid edge or J'' <= 0: sd_lnk = sigma_lnk
ln k_used = (m * ln k_hat + rcm.shrink_pseudo * ln k0) / (m + rcm.shrink_pseudo)          (pseudo-count 2)
RATE LIMIT: k_used = clamp(k_used, (1 - rcm.rate_limit) * k_prev, (1 + rcm.rate_limit) * k_prev)   (10 %)
            then clamp to [rcm.k_min, rcm.k_max];  k_prev = k0 at the first fit
```

**Outputs.** Snapshot `estimation.rcm` (scope `exercise:<uuid>`): `k_prev`, `k_hat`, `k_used`, `sd_lnk`,
`m`, `n_sets`, `rate_limited`, `pooled` (the pooled class value was used), `j_min` (`J` at `k_hat`), `j_k0` (`J` at `k0`), and `sets[]: {set_id, w_sys,
n_eff, fatigue_f, n_fresh, window, residual = e_i - mu_W}` (review keys, D5). Functions exported to other
modules: `cap`, `load`, `e1RM`, `pctPerRep`, and

```
e1rmInterval(w, r, q, k, sd_lnk, z = sse.ci_z) =
   [ e1RM(w, r, q; k * exp(+z * sd_lnk)), e1RM(w, r, q; k * exp(-z * sd_lnk)) ]   // higher k -> lower e1RM
```

With `m = 0` the snapshot carries `k_hat`, `j_min` and `j_k0` as null and `sd_lnk = sigma_lnk`; `residual` is null
for a set outside an informative window. `pooledClassK` evaluates the per-set sd with the class prior `k0`, shrinks
like the exercise fit and is not rate limited; the exercise's rate limit applies afterwards (M3.1 build note).

**D7 gate input.** CHALLENGE_ENGINE computes the OAP gate metric `wpu_e1rm_added` from the test-day 3RM
with `k_used` of `weighted_pullup` (FitExpr `userK('weighted_pullup')` reads this module's value) and shows
the x1.10 value and the 80 % interval next to it (D7, C4). The rules read `k_used` and `sd_lnk` from the
engine-fact snapshot that is captured **before** the test session's own L8 update (CHALLENGE_ENGINE 3.9), so
a test-day 3RM never feeds its own gate `k`. A test day that contains both the 3RM and the bodyweight
max-reps set is an informative window (spread of about 15 reps); RCM-1 fits it in that session's L8 run, and
the result is used from the next gate on.

**Cutover (C59).** The cutover import feeds no historical set rows to RCM-1 (historical tests are imported
as metric observations with validity flags, CHALLENGE_ENGINE `importSpec`). RCM-1 therefore starts at
`k_prev = k0 = 30` with `sd_lnk = sigma_lnk = 0.30`, and the OAP gate uses this class prior until RCM-1 has
an informative window (B5.2, C3.6).

### B5.2 Worked user example and its fragility

The two maximal points used by the research: bodyweight x 18 (`w` 70.7, 30 Aug) and 3RM +32.5 kg
(`w` 103.2, 19 Sep), both to failure (`q = 0`, `F = 0` assumed):

```
103.2 * (1 + 2/k) = 70.7 * (1 + 17/k)   ->   32.5 = 995.5 / k   ->   k = 30.63
M = 103.2 * (1 + 2/30.63) = 109.94 kg  ->  1RM = BW + 39.24 kg
```

- With strength 2 % higher on the second date, the same arithmetic gives `k = 32.80`: two points 20 days
  apart confound strength change with curve shape, which is why RCM-1 only pairs sets inside a 14-day
  window. Under RCM-1's own rules these two points sit in **different** windows and would not be paired.
- The 18-rep point is above 12 reps, where rep-to-failure accuracy and form degrade (Reynolds 2006;
  Halperin 2022). Both points are plausible but fragile (critique, UNSUPPORTED): the app presents the
  estimate as a range, never as a correction of the user's numbers.
- Full RCM-1 on this pair as if it were one window: `k_hat = 30.06` (grid point; the prior pulls toward
  30), `sd_lnk = 0.198`, `k_used = 30.02` after shrinkage with `m = 1`; e1RM from the 3RM +32.5 is
  +39.38 kg with an 80 % interval of +37.84 to +41.36 kg. The artifact's x1.10 rule gives +42.82 kg. This
  matches the critique's "+37 to +42.8" range.
- What the cutover import contains is settled by C59: no historical set rows reach RCM-1, so the first gate
  after cutover uses the class prior `k = 30`. A 3RM of +32.5 kg at BW 70.7 then maps to **+39.38 kg** with
  an 80 % interval of +37.18 to +42.61 kg (`sd_lnk` 0.30; the same numbers as CHALLENGE_ENGINE GF-CE-02).
  For reference only: had the 30-08 test day (3RM +26 at BW 71.0 and BW x 18) been imported as set rows, it
  would form one informative window by itself (two-point `k` = 38.96; RCM-1 `k_hat = 34.14`,
  `sd_lnk = 0.202`, `k_used = 31.32`, so +39.09 kg with 80 % interval +37.58 to +41.04). Golden fixture
  GF-RCM-03 keeps this case as a test of the fitting procedure, not of the cutover state.

**Tests.** `RcmFitTest` (two-point closed form, grid fit, tie rule, edge `sd_lnk`, shrink, rate limit,
pooled fallback, no-informative-window path), `RcmFunctionsTest` (inverse identities `cap(load(n)) = n`,
`pctPerRep` values).

<a id="sse-1"></a>
## B6. `sse` (SSE-1): strength-state estimator (C4, C27)

**Purpose.** The single general strength estimator (C4): for each user, exercise and side it tracks the 1RM
level `L = ln M` and its weekly trend `g` (the progression curve), with uncertainty, robust outlier handling,
reason-based weighting (REASON-1), censored lower bounds and detraining. It is a local-linear-trend Kalman
filter in log space. App_benchmarks' A5 weights survive only as REASON-1 variance multipliers (C4).

### B6.1 State and eligibility

State per `(user, exercise, side)` in `exercise_model_state` (`model = 'sse'`):
`{L, g, P_LL, P_Lg, P_gg, k, t_last (Instant), n_obs}` on the STAT grid.

Only sets that pass the C8 filter enter SSE-1: `contraction = dynamic`; `load_mode` in {`external`,
`bodyweight`, `bodyweight_plus_external`, `assisted_machine`}; `w_sys` defined and `> 0`; `set_kind` in
{`working`, `top`, `backoff`, `amrap`, `calibration`, `test`}; `reps >= 1`. Eccentric-only sets (the 47.5 kg
negatives, and the unassisted 5 s one-arm negative per arm of the OAP test day, C65), isometric and hang
sets and finger-assisted sets never enter (C8); they go to HOLD-1 or to challenge metrics. Eccentric-only
sets carry no RIR (C66: lowering seconds in `set_logs.ecc_s_actual` plus effort chips). Warm-up sets
(`set_kind = warmup`, logged with "Done" and no RIR, C63) never enter either. Sets whose `n_eff` exceeds `sse.max_n_eff` (25) are skipped with trace `sse.skip.n_eff`.

### B6.2 Predict (to the session start Instant, or to `now` for prescriptions)

```
dd = elapsedDays(t_last, t); wk = dd / 7
predictTrend(wk):  L = L + g * wk;  g = g * rho^wk
                   P = F P F^T + diag(q_L^2 * wk, q_g^2 * wk),  F = [[1, wk], [0, rho^wk]]
if dd <= sse.grace_days (21):  predictTrend(wk)
else:                                                    // C27 detraining: load comes only from this decay
   predictTrend(sse.grace_days / 7)                      // trend continues through the grace period
   wd  = (dd - sse.grace_days) / 7                       // weeks beyond the grace period
   L   = L - min(sse.detrain_cap, sse.detrain_per_week * wd)
   g   = 0
   P_LL = P_LL + q_L^2 * wd + sse.detrain_var_per_week * wd
   P_Lg = 0;  P_gg = sse.sg0^2
```

This is the research formula restated so that it is continuous at `dd = 21`. Volume and RIR re-entry after
more than 21 untrained days (sets x 0.6, RIR + 1 for 2 sessions) belong to `targets` (C27); challenge pause
rules override for challenge exercises (CHALLENGE_ENGINE).

### B6.3 Per-set observation

```
q_true   = RIRCAL-1 (B4.2); missing RIR -> REASON-1 imputation (B12.4)
n_eff    = reps + q_true
F_i      = FAT-1 estimation recursion over this exercise and side in this session (B7.3), incl. carry-over
n_fresh  = n_eff / (1 - F_i)
z_i      = ln w_sys + ln(1 + (n_fresh - 1) / k)                   // ln of the fatigue-corrected e1RM
s_i^2    = (sigma_q / (k + n_fresh - 1))^2                        // RIR noise, converted to ln-load units
         + (sse.form_err_per_rep * max(0, n_fresh - sse.form_err_free_reps))^2   // high-rep extrapolation error
         + (sse.fatigue_err * F_i / 0.3)^2                        // fatigue-model error (F = 0.3 -> 0.03)
class_i  = REASON-1 estimator class (B12.2): normal | downweight m (s_i^2 *= m) | lower_bound | excluded
           ("5+" chip -> lower_bound)
```

The research version applied the fatigue term only for `i > 1`; here it applies whenever `F_i > 0`, because
carry-over from earlier exercises makes the first set's `F` uncertain too. In the L8 update `k` is RCM-1's
`k_used` from this session's fit (RCM-1 runs first, A12.2); in-session uses (AUTO-1, B13) use the `k` of
the session start.

### B6.4 Session update (L8, at session finish)

```
NORMAL (if the session has >= 1 set of class normal or downweight):
  zbar = sum(z_i / s_i^2) / sum(1 / s_i^2)
  R    = sse.sd_day^2 + 1 / sum(1 / s_i^2)        // day-level noise once: sets of one session are correlated
  nu   = zbar - L;  S = P_LL + R
  if |nu| > sse.huber_gate_sd * sqrt(S):          // robust gate (trace sse.huber)
       R = R * (|nu| / (sse.huber_gate_sd * sqrt(S)))^2;  S = P_LL + R
  K    = [P_LL / S, P_Lg / S]
  L    = L + K0 * nu;  g = g + K1 * nu
  // covariance update, all right-hand sides use the pre-update values:
  P_LL' = P_LL - P_LL^2 / S;  P_Lg' = P_Lg - P_LL * P_Lg / S;  P_gg' = P_gg - P_Lg^2 / S
LOWER BOUND (if lower_bound sets exist; applied after NORMAL):
  z_lb = max z_i over them, s_lb its sd
  if z_lb > L + sse.lb_gate_sd * sqrt(P_LL):
       same update with nu = z_lb - L and R = sse.lb_r_mult * (sd_day^2 + s_lb^2)     // can only raise L
t_last = session start Instant; n_obs += number of used sets; k = current RCM-1 k_used
```

When no set of the session moves the state (only a lower bound below its gate), the stored state is kept as it
was, trace `sse.no_update`: predicting it to the session start and storing that would change later predictions,
because the decaying trend `g * rho^wk` is not additive over split intervals (M3.1 build note).

**Cold start.** No state yet: if a transfer prior exists (`exercise_transfer_ratios` from an exercise with
state), `L = ln(ratio * M_related)`, `P_LL = sse.transfer_sd^2` (0.15^2), `g = 0`, `P_gg = sg0^2`, and the
session updates it normally. Otherwise the first eligible normal set initialises `L = z_1`,
`P_LL = sse.cold_start_sd^2` (0.08^2), `g = 0`, `P_gg = sg0^2`, and the remaining sets of the session update
it. The first exposure of a new or long-unused exercise is a calibration probe (C27, B9.8).

### B6.5 Outputs

Snapshot `estimation.sse` (scope `session:<uuid>`) with the DATA_MODEL 10.2 keys:
`sets[]: {set_id, q_true, n_eff, n_fresh, fatigue_f, z, s, estimator_class, e1rm_kg = exp(z), e1rm_added_kg
= exp(z) - f_bw * BW - implement_base_kg, k_used, residual = z - L_prior}` and
`states[]: {exercise_id, side, m_kg = exp(L), ci80_lo_kg = exp(L - z80 * sqrt(P_LL)), ci80_hi_kg, g_per_week,
huber_applied, lb_applied}` (the two flags are output keys for review metric M5), plus the innovation
statistics `{nu, S, K0, K1}` in the trace. For the review
metrics of Part D every set additionally carries the one-step-ahead prediction made from the **prior** state
before this session's update: `n_eff_pred = cap(w; exp(L_prior), k) * (1 - F_i)` and the predictive 80 %
interval `z_pred_lo/hi = L_prior -/+ z80 * sqrt(P_LL_prior + s_i^2 + sd_day^2)`, and every state carries
`l_prior` and `p_ll_prior`. These one-step-ahead keys (`n_eff_pred`, `z_pred_lo`, `z_pred_hi`, `l_prior`,
`p_ll_prior`) are review keys listed in DATA_MODEL 10.2 (C58); the full review-key list of this document's
snapshots is in D5. Derived display
values: added-load 1RM (`M - f_bw * BW - implement_base_kg`), for assisted work the assistance needed for one
rep (`f_bw * BW - M` when positive), forecast `M(t + h) = exp(L + g * h / 7)`.

The UI shows the SSE-1 e1RM and the challenge gate value side by side, labelled (C4).

### B6.6 Worked example (weighted pull-up)

Prior after the RCM example: `M = 109.94` kg (sd 3 %, `P_LL = 0.0009`), `g = 0.30 %` per week,
`P_gg = 0.005^2`, `P_Lg = 0`, `k = 30.63`. One week later (`wk = 1`): `M_pred = 110.27`, sd 3.20 %,
`g = 0.255 %`. Session 4 x 5 at +22.5 kg (`w_sys = 93.20`), rest 150 s, reported RIR 2/1/1/0, `b = 0.5`,
`F_1 = 0` (illustration; in session A the assisted one-arm sets before it add a small carry-over):

| Set | `q_true` | `n_eff` | `F` | `n_fresh` | `exp(z)` kg | `s` |
|-----|---------|---------|-----|-----------|-------------|-----|
| 1 | 2.5 | 7.5 | 0.0000 | 7.50 | 112.98 | 0.0372 |
| 2 | 1.5 | 6.5 | 0.1166 | 7.36 | 112.55 | 0.0352 |
| 3 | 1.5 | 6.5 | 0.2246 | 8.38 | 115.66 | 0.0413 |
| 4 | 0.5 | 5.5 | 0.2889 | 7.73 | 113.69 | 0.0417 |

`exp(zbar) = 113.58`, `R = 0.00160`, `nu = 0.0296`, `S = 0.002622` (no Huber), `K = (0.391, 0.0081)`:
posterior `M = 111.55` kg = BW + 40.85 kg, 80 % CI +37.34 to +44.48 kg, `g = 0.28 %` per week.

### B6.7 Tests

`SseUpdateTest` (example above to the grid), `SsePredictTest` (grace, detraining continuity at 21 days, cap
at 15 %, 25-hour DST night), `SseClassesTest` (downweight, lower bound only raises, excluded), `SseColdStartTest`,
`SseEligibilityTest` (negatives, holds, finger assistance, `n_eff > 25` all excluded).

<a id="fat-1"></a>
## B7. `fatigue` (FAT-1): within-session fatigue

**Purpose.** Predict how reps-to-failure capacity falls across sets as a function of reps done, proximity
to failure and rest. Used by RX-1 (prescription), SSE-1 and RCM-1 (fatigue-corrected observations), AUTO-1
and the REST-1 live readout. Between-session fatigue is not this module's job (C5: MuscleReadiness only).

### B7.1 Model

```
c(rest) = fatigue.c_ref * clamp((rest / fatigue.rest_ref_s)^fatigue.rest_exp, fatigue.rest_clamp_lo, fatigue.rest_clamp_hi)
F_{j+1} = d * F_j + c(rest_j) * min(1, reps_j / cap_j)^gamma * (1 - F_j)
cap_j   = cap_fresh * (1 - F_j)
```

| Rest | 60 s | 90 s | 120 s | 150 s | 180 s | 240 s | 300 s |
|------|------|------|-------|-------|-------|-------|-------|
| `c(rest)` | 0.455 | 0.357 | 0.300 | 0.262 | 0.235 | 0.198 | 0.173 |

Check against Nuzzo's secondary analysis (repeated sets to failure, about 120 s rest): the model gives
1.00, 0.70, 0.565, 0.504, 0.477, 0.465 of set-1 reps; observed 1.00, 0.70, 0.55, 0.50, 0.45, 0.45
(moderate). `gamma = 2` makes sets near failure cost disproportionately more (velocity-loss literature,
expert-opinion).

### B7.2 Prescription recursion (RX-1, WARMUP-1, AUTO-1)

`cap_fresh = cap(w; M_use, k)`, planned reps `r_j`, planned rest `rest_j`; `F_1` from carry-over (B7.4).

### B7.3 Estimation recursion (SSE-1, RCM-1, RIRCAL-1)

Same recursion with `cap_j` = the observed `n_eff_j` (reps + `q_true`) and `rest_j` = the actual rest
before set `j + 1` (`set_logs.rest_before_s` of the next set; planned rest when missing). For alternating
unilateral work each side has its own recursion; a side's rest is the time since that side's previous set.

### B7.4 Carry-over from earlier exercises

```
F_1(e) = min(fatigue.carry_cap, fatigue.carry_per_set *
             sum over working sets s of earlier exercises e' today: ov(e', e) * side_w(s) * min(1, r_s / cap_s)^gamma)
ov(e', e) = 1.0 if some muscle group g has groupCoeff(e', g) >= 0.75 and groupCoeff(e, g) >= 0.75
            0.5 else if some group has both >= 0.5
            0   otherwise                                  (group coefficients from the catalog, C9)
side_w(s) = 0.5 for a side row of a unilateral exercise (C7), else 1
cap_s     = observed n_eff (estimation) or predicted capacity (prescription)
```

Warm-up sets are not counted (they are designed to cost almost nothing: at most half of capacity).

### B7.5 Nightly personalisation (per user x curve class)

```
data = the last fatigue.fit_window_exercises (60) exercise instances of the class with >= 2 eligible normal sets
for c in {0.10, 0.12, ..., 0.50}:
   predicted n_eff_j (j >= 2) = n_fresh_1 * (1 - F_j), F via B7.3 with observed reps and rests and c_ref = c
   SSE(c) = sum (n_eff_obs_j - n_eff_pred_j)^2
c_hat  = argmin (ties: closer to the default c_ref, then lower); m = number of sets j >= 2 used
c_used = (m * c_hat + fatigue.shrink_pseudo_sets * c_ref_default) / (m + shrink_pseudo_sets)
if m >= fatigue.d_fit_min_sets (100): fit d on {0.50, 0.52, ..., 0.90} with c fixed at c_used, same shrink toward d_default
```

Snapshot `estimation.fatigue` (scope `class:<curve_class>`): `c_hat`, `c_used`, `d_hat`, `d_used`, `m`,
`resid_by_rest_bucket` (mean residual per rest bucket `lt75`, `b75_105`, `b105_150`, `b150_210`, `gt210` s),
`resid_by_rir_bucket` (`q0_1`, `q2_3`, `q4_plus`) and `resid_by_set_index` (`s2`, `s3`, `s4`, `s5_plus`)
(review keys, D5).

### B7.6 Worked example and live readout

4 x 5 at +20 kg (`w_sys` 90.70, `M` 109.94, `k` 30.63, rest 150 s): `cap_fresh = 7.50`; predicted RIR per set
2.50, 1.62, 0.85, 0.14 with `F` = 0, 0.117, 0.220, 0.314. This reproduces the anchor description "the last
two sets were just made" and is golden fixture GF-FAT-02. Live readout after set 1 (`predictNext(elapsed)`
for REST-1): capacity of set 2 = 5.98 reps at 60 s, 6.31 at 90 s, 6.50 at 120 s, 6.62 at 150 s, 6.71 at
180 s, so "now: 4 reps at RIR 2; at 2:30: 4.6".

**Tests.** `FatigueTest` (Nuzzo check, `c` table, carry-over cap, unilateral sides, DST-independent),
`FatigueFitTest` (grid, shrink, `d` gating at 100 sets).

<a id="hold-1"></a>
## B8. `hold` (HOLD-1): timed isometric holds and hangs

**Purpose.** Estimate per-side hold capacity and prescribe and progress timed holds (dead hangs, top holds,
90-degree holds, straight-arm holds, planks) by time and by load or assistance, with the same machinery as
SSE-1. For OAP holds the challenge rules decide (RULE mode) and HOLD-1 runs in shadow; its estimates are
logged and shown as trends.

### B8.1 Model

```
t_cap(w) = T * (w / w_ref)^beta          // endurance time falls with relative load (Frey Law and Avin 2010)
```

Frey Law and Avin (2010; 194 publications, 369 data points; moderate) fit `ET = b0 * f^b1` per joint. Only
the exponent is used (`beta = b1`); the scale `T` is learned per user and side because absolute `b0` values
do not fit individuals.

| Hold class | `beta` | Typical exercises |
|-----------|--------|-------------------|
| grip | -1.61 | dead hangs (two-arm, one-arm), grip holds |
| elbow | -2.21 | top hold, 90-degree hold, flexed-arm hang |
| shoulder | -1.83 | straight-arm hold, scapular holds |
| trunk | -2.27 | planks, side planks, one-arm plank |
| knee | -1.88 | wall sit |
| ankle | -2.06 | calf holds |
| general | -1.98 | anything else |

**Hold class of an exercise.** The catalog attribute `hold_class` in {`grip`, `elbow`, `shoulder`, `trunk`,
`knee`, `ankle`} of every hold exercise selects the exponent (C43); the OAP straight-arm hold therefore
carries `hold_class = shoulder` although its pattern is `skill_static`. DATA_MODEL requires `hold_class` on
every isometric or hang exercise (E55), so the pattern rule below is a defensive fallback only (an input
without the attribute, for example a hand-built test payload): `hang`, `grip_hold` -> grip;
`straight_arm_pull` -> shoulder; `skill_static`, `elbow_flex` -> elbow;
`core_*` -> trunk; `squat`, `squat_machine`, `knee_ext`, `lunge` -> knee; `calf` -> ankle; otherwise the
`general` exponent (trace `hold.class.fallback`).

`w` is `w_sys` from B2 (one-arm holds: `BW - assist`). `w_ref` is fixed at the first observation of the
state. Holds without a load model (`f_bw = 0` and no external load, for example planks) use the time-only
model (`beta` term = 0); adding a plate to such a hold starts a new state segment keyed by the external load.

### B8.2 Observation and state

```
chip -> s_res: Max 0 s, Hard 2 s, OK 5 s, Easy 10 s as a lower bound (hold.chip_reserve_s, D9, UX 6.2)
F_i     = FAT-1 recursion with ratio t_done / t_cap and c_ref = hold.c_ref (0.25)
z_i     = ln((t_done + s_res) / (1 - F_i)) - beta * ln(w_i / w_ref)
sigma_i = hold.obs_sd_base + hold.obs_sd_timing_s / (t_done + s_res)     // reaction-time error is ~2 s absolute
class   = Easy chip -> lower_bound; flag torso_rotation or reason technique_breakdown -> s_res = 0; pain -> excluded
```

The state `{lnT, g, P, t_last}` per `(user, exercise, side)` uses SSE-1's predict and update equations
(B6.2, B6.4) with `sd_day = hold.sd_day` (0.10) and `s_i = sigma_i`; all other constants are SSE-1's.
Top-hold rounds (several short attempts) use the best attempt of each round as one observation.

### B8.3 Prescription (general holds; shadow for RULE items)

```
target range t_lo..t_hi (rep_preferences, else hold.default_t_lo_s / hold.default_t_hi_s)
reserve rule s* = max(hold.reserve_min_s, hold.reserve_frac * t*), solved in closed form:
   t_avail(w) = t_cap(w) * (1 - F)
   t_target(w) = min(t_avail - hold.reserve_min_s, t_avail / (1 + hold.reserve_frac))
adjustable load or assistance: choose the hardest loadable w with t_target(w) in [t_lo, t_hi]; t* = floor to 0.5 s
fixed load (bodyweight only): t* = clamp(floor(t_target), t_lo, t_hi)
progress: when every set reached t_hi with reserve >= s*, move to the next harder loadable if its
          t_target >= t_lo; otherwise keep the load and raise the time target (DOUBLE-style)
```

### B8.4 Worked examples (C2 offset included)

- **90-degree one-arm hold at pin 35 lb** (`w` = 70.7 - 22.38 = 48.32 kg): 8 s held with "Hard" (2 s) ->
  observation 10 s, so `T = 10` s at `w_ref = 48.32`. At pin 30 lb (`w` = 50.59 kg, +4.69 %):
  `t_cap = 10 * 1.0469^-2.21 = 9.04` s; `t_target = min(9.04 - 2, 9.04 / 1.2) = 7.04` s, inside the 6-8 s
  range, so the next harder pin is feasible. (The research example used 54.82 kg because it omitted the
  6.5 kg offset of C2.)
- **One-arm dead hang** (bodyweight, grip): 10 s held with "OK" (5 s) -> observation 15 s; for a 10-15 s
  range: `t_target = min(15 - 2, 15 / 1.2) = 12.5`, so `t* = 12` s.

**Outputs, logging, tests.** Snapshot `estimation.hold` (scope `session:<uuid>`): `sets[]: {set_id, t_done,
s_res, fatigue_f, w_sys, z, sigma, estimator_class}` and `states[]: {exercise_id, side, hold_class, t_s,
ci80_lo_s, ci80_hi_s, g_per_week, beta, w_ref_kg}` (review keys, D5).
A per-user `beta` refit is allowed once two or more loads exist within 14 days (review). Tests: `HoldTest`
(examples, chip mapping, lower bound, time-only segments, DST).

<a id="rx-1"></a>
## B9. `rx` (RX-1): sets, reps and load for each planned exercise (C15)

**Purpose.** Turn the estimator state, the user's preferences, the equipment and the challenge rules into
concrete per-set targets: load (native value and `w_sys`), reps (or seconds), target RIR, predicted RIR and
rest. RX-1 is the primary between-session progression mechanism (C15): progression happens because the
SSE-1 state moves and RX-1 re-optimises, bounded by one cap table.

### B9.1 Modes

| Mode (`progression_mode`, E13) | When | What decides the load |
|-------------------------------|------|-----------------------|
| `model` (RANGE or FIXED scheme) | default for loadable exercises with an SSE-1 state and fine load steps | B9.5 optimisation over loadables |
| `double` | one load step is worth more than 1.5 reps, or the exercise is bodyweight-only, or `w_sys` is undefined but a native ladder exists (C15) | B9.6 Gravl rep ladder, then the next loadable |
| `rule` | challenge items with `progression: rule` (C15) | the challenge (CHALLENGE_ENGINE); the model runs in shadow and is logged (B9.11). Challenge items with `progression: delegate:rx` (CHALLENGE_ENGINE 6.6; OAP: the B row and the dips) are not RULE items: RX-1 chooses their load in `model` or `double` with the item's sets, reps and RIR target (OI-A20) |
| `fixed` | the user fixed load and reps explicitly for this exercise (exercise-scope preference with a fixed load) | the user; RX-1 only predicts RIR and warns |
| `none` | no usable model and no ladder (finger assistance, missing BW) | "repeat last" prefill (B9.10) |

Timed holds (`measure = duration`) are prescribed by HOLD-1 (B8.3); RX-1 dispatches to it and records
`hold@1.0.0` in `trace.deps`. The `rx.prescription` snapshot is written for every measure.

### B9.2 Inputs (payload of `rx.prescription`, scope `session_exercise:<uuid>`)

- Slot from L6 selection: exercise (catalog attributes), side mode, number of working sets `S` (SEL-1,
  adjusted by targets), block kind, position in the session, planned rest per set (REST-1 base, B11).
- Resolved scheme (B9.3) with the source of each field (`user:<scope>` or `engine_default`).
- SSE-1 state predicted to the session start (`L_pred`, `P_LL`, `g`), RCM-1 `k_used`, FAT-1 `c_ref`, `d`
  for the class, RIRCAL-1 `b_eff`.
- Loadables `W` and the BW snapshot (B2, B3).
- Last valid exposure summary (date, mode, top load, per-set reps, `q_true`, reasons) and the previous
  `rx.prescription` output of this exercise and side (ladder targets, extension).
- Flags: deload (DeloadDecision from targets), re-entry `{setsMult, rirAdd, remainingExposures}` (targets,
  C27), STALL-1 status and pending tier action, fail counter, REASON-1 follow-ups (`too_hard` boost,
  pain follow-up), SafetyState flags for the exercise's joints, the `evidence_mode` setting (D10).
- For RULE mode: the materialised challenge item (per-set reps or seconds, load or pin, RIR target, rest).
- Planned carry-over: the predicted working sets of the exercises placed before this one (ORD-1 order).

### B9.3 Scheme resolution

```
goalKind(exercise) = strength  if the exercise is a RULE-mode challenge item, or the exercise-scope rep_preference has
                               rep_scheme = fixed and rep_max <= 6
                     hypertrophy otherwise                                  (OI-A10: no explicit column)
resolve each field (sets_pref, rep_min, rep_max, rir_target, rep_scheme, rest) independently from
rep_preferences: exercise > muscle_group (primary group of the exercise) > mechanics > goal > global;
rest is one field in two forms (D50): the most specific scope with rest_pref_s or rest_pref_mult wins;
fill remaining nulls from engine defaults:
   hypertrophy, compound:            RANGE 8-12, q* = 2
   hypertrophy, isolation/machine:   RANGE 8-12, q* = 1
   strength:                         FIXED r* = 5, q* = 2
   maintain goal of the primary group: q* = 2 (sets come from targets)
   bodyweight-only reps cap:         r_hi <= rx.bw_r_hi (20)
r_lo == r_hi  ->  FIXED with r* = r_lo   (e.g. a "2 x 12" preference)
SAFETY clamp (B15.3, always applied, logged as enforced):
   safety_class == high_risk  ->  q* = max(q*, rx.min_q_high_risk = 1)     (D10: no failure on weighted/one-arm pulls, loaded dips)
engine defaults never use q* = 0; a user preference q* = 0 on any other exercise is kept (D10) with WE-P9 (INFO)
transforms (in this order): pain follow-up q* = max(q*, reason.pain_min_q); re-entry q* += rirAdd;
   deload handled in B9.9 (deload and re-entry never stack, C51: in a week with both, only the combined
   offset of B9.9 is added); STALL tier 1: q* += stall.tier1_q_add
```

Every resolved field keeps its source. User-owned fields are never changed by evidence rules under
`evidence_mode = warn_only`; WE-1 warns instead (D10, B15.4). Safety clamps apply regardless of the source.

### B9.4 Mode selection (first match wins)

1. Challenge item with `progression: rule` -> `rule`. (A `delegate:rx` item continues with steps 2-7 like a
   general item, with the item's sets, reps and RIR target as its scheme.)
2. `measure = duration` -> HOLD-1.
3. `w_sys` undefined: a native ladder exists (level stack without `level_kg`, C43; Smith with unknown bar) ->
   `double` on the native value; otherwise (finger assistance, no BW) -> `none`.
4. No SSE-1 state and no transfer prior, or more than `rx.calibration_gap_days` (21) since the last exposure
   -> calibration probe (B9.8) inside the mode chosen by steps 5-7. With a transfer prior and no state there is no
   probe: the prescription starts from `ratio * M_related` (as SUB-1 15.1 starts a substitute); B9.8's "else from the
   transfer prior" applies to a probe after a gap.
5. Bodyweight-only today (one loadable) -> `double`.
6. Coarse-step check at the MODEL choice `w*` (B9.5):
   `reps_per_step = (nextHarder(W, w*) - w*) / w* * (k + cap_1(w*) - 1)`; if `reps_per_step >
   rx.coarse_reps_per_step` (1.5) -> `double` (C15). No harder loadable -> `model` at the top load (trace
   `rx.top_of_equipment`).
7. Otherwise `model`.

Stickiness: an exercise that used `double` in the current mesocycle (B1) stays `double` until the mesocycle
ends, so the mode cannot flap between sessions; `model` switches to `double` immediately.

### B9.5 MODEL mode

```
z_c   = rx.z_c (0.5);  0 if sqrt(P_LL) < rx.z_c_sd_floor (0.02);  rx.z_c_after_too_hard (1.0) during the
        rx.z_c_after_too_hard_exposures (2) exposures after a REASON-1 too_hard trigger
M_use = exp(L_pred - z_c * sqrt(P_LL) + delta)          // delta = 0 at L6; AUTO-1 delta_ex in-session (B13)
C     = { w in W : rx.window_lo * M_use <= w <= rx.window_hi * M_use }   (0.35 .. 1.0)
        empty -> the loadable nearest to the window (trace rx.window.empty)
for each w in C (ascending):
   cap_1 = cap(w; M_use, k) * (1 - F_1)          // F_1: planned carry-over (B7.4)
   FAT-1 prescription recursion (B7.2) over S sets with planned rests, giving cap_j
   RANGE: ideal_j = cap_j - q*;  reps_j = clamp(roundHalfDown(ideal_j), r_lo, r_hi);  rir_j = cap_j - reps_j
          J(w) = rx.range_w_dev   * sum (rir_j - q*)^2
               + rx.range_w_under * sum max(0, q* - rx.range_under_tol - rir_j)^2
               + rx.range_w_clamp * sum (reps_j - roundHalfDown(ideal_j))^2        // reps moved by the clamp
               + rx.range_w_negative * [any rir_j < 0]
   FIXED: reps_j = r*;  rir_j = cap_j - r*;  feasible(w) = min_j rir_j >= q* - rx.fixed_tol
roundHalfDown(x) = ceil(x - 0.5)                       // 9.5 -> 9; ties toward fewer reps
choice:
   RANGE: argmin J (Det.cmpScore), ties -> easier w
   FIXED: heaviest feasible w; none feasible -> easiest candidate and S = max(1, S - 1) (trace rx.fixed.infeasible)
CAPS (B9.14) are applied as a band on C before the choice; the unconstrained optimum is logged.
```

The RANGE scheme keeps effort constant: reps drop inside the range at a fixed load (for example 10/9/8),
so early sets are not left at RIR 4 or more (Robinson 2024 favours proximity to failure for hypertrophy).
FIXED with a minimum-RIR constraint on every set matches strength and challenge work ("all 6 x 2 with 2 in
reserve").

### B9.6 DOUBLE mode (Gravl rep ladder, C15)

State: the previous `rx.prescription` output for this exercise and side holds `targets[]` and `ext`
(extension of the top of the range); the load is the last valid exposure's load `L_last`.

```
valid exposure: not a deload or probe; all planned sets logged; no set with reason pain, grip_failure,
                occupied, equipment_missing or no_time
                (sets added with "Add a set", C63, have no planned target: they are estimator observations,
                 but the ladder and the fail counter look only at the planned sets j = 1..S; OI-A23)
success_j = reps_j >= targets_j and q_true_j >= q* - rx.double_success_rir_tol (0.5)
top       = r_hi + ext

if no valid previous exposure at L_last:      targets = ladderStart(L_last), ext = 0
elif all j: reps_j >= top and success_j:      // top of the (extended) range at the target effort
     L_new = nextHarder(W, L_last); start = ladderStart(L_new)
     if L_new exists and start[1] >= r_lo - rx.double_entry_below (4):
           advance: load = L_new, targets = start, ext = 0        // exactly one loadable step (cap exemption, C45, B9.14)
     elif ext < rx.double_max_extension (4):
           ext = ext + rx.double_extension_step (2); targets = ladderStep(reps)
     else: keep load and targets; WE-P7 "range too narrow for this equipment step" (once per mesocycle)
elif all j: success_j:                         targets = ladderStep(reps)
else:                                          targets = reps (repeat); a failed exposure feeds the fail counter (B9.7)

ladderStart(L): with a model: t_j = clamp(roundHalfDown(cap_j - q*), 1, r_hi) via FAT-1 at M_use;
                without a model: t_j = r_lo for all j
ladderStep(a):  t = a capped at top; find the first j >= 2 with t_j < t_{j-1}: t_j += 1;
                if none and t_1 < top: t_1 += 1
```

Gravl's example with `r_hi = 8`: 8-7-6 -> 8-8-6 -> 8-8-7 -> 8-8-8 -> next load step (each step about 1-2 %
more work). Plotkin 2022 (moderate) found adding reps and adding load equally effective for hypertrophy,
which justifies rep progression when load steps are coarse.

**Example (dumbbell lateral raise, isolation, `k0` 26, RANGE 12-15, `q*` 1, 3 sets, rest 90 s).** At 8 kg per
hand (`w_sys` 16) after 15/15/15 at RIR 1 (`n_eff` 16.5, `M` 25.54): the next dumbbell is 10 kg (+25 %),
worth `0.25 * (26 + 15.5 - 1) = 10.4` reps, so the exercise is in DOUBLE. At 15/15/15 the ladder is complete
but `ladderStart(10 kg)` gives 7/5/4 (first set 7 < `r_lo - 4` = 8): extend to 17. After 17/17/17
(`M` 26.77) the start at 10 kg is 9/6/5 (9 >= 8): advance to 10 kg with targets 9/6/5, which the ladder
then fills.

### B9.7 Fail counter (STALL-1 fast path, C15)

```
failed exposure = valid exposure (B9.6 definition, and not feeling_bad / too_tired) in which some working
                  set has reps < its target (RANGE: r_lo; FIXED: r*; DOUBLE: t_j) and q_true <= 0.5
counter = number of consecutive failed exposures of this exercise and side; any successful valid exposure resets it
if counter >= stall.fail_threshold (2):
     next exposure: w <= easierOrEqual(W, w_last * (1 - stall.fail_load_cut))   (7.5 %); ladder restarts;
     counter = 0 after that exposure; trace stall.fast_path
```

In MODEL mode the fast path is an upper bound on `w`, not an extra cut on top of the SSE-1 estimate. The
counter is derived from facts (no hidden state) and cached in `exercise_model_state` (`model = 'stall'`).

### B9.8 Calibration probe (C27)

For a new exercise (no SSE-1 state) or one not trained for more than 21 days, the **first working set** is a
probe: target reps `r_hi` (RANGE) or `r*` (FIXED), target RIR `q* + rx.probe_rir_add` (2), load from the
SSE-1 prediction after decay, else from the transfer prior, else `null` with the instruction "pick a weight
you could lift about `r_hi + q* + 2` times" (prefill: last native value if any). Sets 2..S are planned as
"derived after set 1": after the probe is logged, RX-1 re-runs MODEL or DOUBLE with AUTO-1's in-session
`delta_ex` (B13.2) and the result automatically becomes the plan for the remaining sets (C57; trace
`rx.probe.followup`, shown as the prefill "from your first set"). This fills an undetermined plan, so it is
not an AUTO-1 suggestion and does not count toward the C16 limit. SSE-1 cold-starts from the probe at
session end. For a `delegate:rx` challenge item, the definition's `bootstrap` (CHALLENGE_ENGINE 2.4.7)
selects this probe.

M3.4 build note: until AUTO-1 exists (M3.5), the follow-up's estimate is SSE-1's own update with the probe as the only
observation (from the state predicted to the session start, or a cold start without one), which on the first set is
the same Kalman step as `delta_ex` from 0 (B13.2); the probe's fatigue is carried into sets 2..S as `F_1`. A
unilateral exercise follows up once both sides of set 1 are logged.

### B9.9 Deload, re-entry, pain follow-up

| Transform | Source | Effect in RX-1 |
|-----------|--------|----------------|
| Deload week | `targets` DeloadPolicy (F8, C13): sets x 0.5 (set count arrives already reduced), load kept, RIR + 2 | load = `w_last` of the last non-deload exposure (MODEL and DOUBLE); reps per set `max(1, min(planned_j, floor(cap_j - (q* + 2))))` with FAT-1; without a model `max(1, t_j - 2)`. Deload exposures never advance a ladder, never count as failures and are excluded from STALL-1 exposure counts. |
| Re-entry (> 21 days untrained) | `targets` (C27): sets x 0.6, RIR + 1 for 2 sessions | `q* += rirAdd`; load from SSE-1 decay only (no extra cut); the first set is a probe (B9.8) |
| Deload and re-entry in the same week | `targets` (C51): never stacked; set factor = min (0.5), RIR offset = max (+2) | the set count arrives already reduced once; RX-1 adds the single combined RIR offset (`q* + 2`), never `+2` and `+1` together; load from SSE-1 decay as in the re-entry row (`w_last` is more than 21 days old); the first set of a re-entry exercise is still a probe |
| Pain follow-up | REASON-1 (B12): next 2 exposures | `w <= easierOrEqual(W, w_last * (1 - reason.pain_load_cut))` (10 %), `q* >= reason.pain_min_q` (3); SUB-1 may substitute instead (planning) |
| STALL-1 tier 1 | B14 | one exposure: `S = max(1, floor(stall.tier1_sets_mult * S))`; strength goal: load kept; hypertrophy: `w <= easierOrEqual(W, w_last * (1 - stall.tier1_load_cut))` (C13: among deload-type transforms only tier 1 cuts load); `q* += 1` |
| STALL-1 tier 2 | B14 | rep range shifted by `stall.tier2_rep_shift` (4) for `stall.tier2_weeks`; variant swap proposals go to ROT-1 at the next mesocycle start (F12) |

D10, C57: tier actions and other evidence-based changes apply directly only to engine-owned fields; a STALL-1
action on a field the user set explicitly (sets, reps, RIR) is surfaced as a WE-T1 suggestion. Only when
the user has chosen `evidence_mode = auto_adjust_with_warning` (E35) is it applied to the plan with a
warning that offers "use mine" (B15.4). Load is always engine-owned unless mode `fixed`.

Example (deload): leg press at 125 kg, 2 sets instead of 3, `q* + 2 = 4`: set 1 `cap` 13.12 -> 9 reps
(RIR 4.12); set 2 `cap` 11.50 -> 7 reps (RIR 4.50).

### B9.10 Fallback `none` ("repeat last")

When no model and no ladder exist (finger-assisted sets, missing BW), RX-1 outputs the native values of the
last exposure per set (`planned_source = prefill_last`), no predicted RIR, and the chip "No estimate yet"
(trace `rx.fallback.prefill_last`).

### B9.11 RULE mode and the shadow model

- The plan copies the challenge item exactly (`planned_source = challenge_rule`): per-set reps or seconds,
  added load or pin, target RIR when the item defines one, rest. Challenge rules evaluate raw RIR (C17).
- RX-1 computes, when an SSE-1 state exists, the **predicted RIR per set** for the challenge load (used by
  AUTO-1 in shadow and shown in the Why sheet) and a **shadow prescription**: MODEL with the item's scheme
  (FIXED `r*` = the item's reps, `q*` = the item's RIR target or `rx.default_q_strength`), the same `S`,
  rest and loadables. Both go into `outputs.shadow` of `rx.prescription`; the review compares challenge
  trajectories with the model's.
- SAFETY rule S1 applies to RULE items through the target RIR field (a target of 0 on a high-risk lift
  becomes 1, logged as enforced). The pain protocol (WE-S3, `safety`) and the protected windows and other
  hard constraints (WE-S4) bind RULE items exactly as general items (C45).
- Load-jump caps (D13, C45): the generic cap table (B9.14) does **not** limit RULE-mode loads; the user's
  approved challenge rules win. RX-1 compares the challenge load with `w_last` of the same item and side
  (B9.14 definition); when the jump exceeds the generic cap that would apply to a general prescription, it
  emits WE-S2 at **WARN** severity (`enforced = false`, non-blocking, shown under the UI-10 display policy)
  and logs the excess in `caps_applied[]` with `type: rule_excess` (no clamp). Example: session C pin
  50 -> 45 lb is +5.46 % of `w_sys` against the 5 % compound cap (C3.5, GF-WE-05). WE-S2 is not evaluated
  at all for HOLD-1 items (plank plate, timed holds and hangs, pins on holds); hold progression follows its
  own rules (B8, C70).
- In-session challenge rules (D14, C46; CHALLENGE_ENGINE 6.10) are auto-applied to the remaining sets of the
  item, with a visible "Rule: ... · Undo" on the set card; they are limited per rule per item. The
  auto-apply is logged as `challenge_rule_applied`; an Undo is logged as `challenge_rule_undone`
  `{rule_id, session_exercise_id, reason_code, ms_since}` (not as `override_applied`). After an auto-applied change (or its
  Undo), RX-1 recomputes the predicted RIR and the shadow for the remaining sets at the new planned load
  (trace `rx.rule.replan`), so AUTO-1's shadow and the review compare against what was actually planned.

### B9.12 Bodyweight exercises and variant switching

- Bodyweight-only exercises cannot change load, so reps carry the progression: `double` with
  `r_hi <= rx.bw_r_hi` (20).
- When `cap - q*` exceeds `rx.bw_r_hi` in `rx.bw_switch_exposures` (2) consecutive exposures, RX-1 attaches a
  suggestion (never automatic, D10): first choice the weighted mode of the same exercise if it allows
  `bodyweight_plus_external` and a belt is available (same SSE-1 series, B2.7; load from MODEL in the user's
  range); otherwise the harder variant = same pattern and family, bodyweight mode, smallest `f_bw` above the
  current one, eligible under ELIG-1. Symmetrically, when predicted reps at `q*` fall below `r_lo`, the
  assisted mode (if allowed and the machine exists) or the easier variant (largest `f_bw` below) is
  suggested. Example: push-up (`f_bw` 0.64) -> feet raised 30 cm (0.70) -> feet raised 61 cm (0.74).

### B9.13 Unilateral exercises

Unilateral policy (a fixed rule, not a param): `match_weaker` for strength-goal exercises and exercises
tagged `skill`, `challenge` or `heavy_unilateral`; `independent` otherwise.

- `match_weaker`: the weaker side is the one with the lower `M_use` (ties: L). Both sides get the weaker
  side's load; the weaker side goes first; the stronger side's rep target is capped at the weaker side's
  achieved reps (the UI prefill uses this, UX 11.7.2). Estimation stays per side.
- `independent`: each side is optimised separately; when the equipment allows only one setting per set
  (one pin for both arms), the weaker side's setting is used for both.

### B9.14 Cap table (C15, one table in params)

| Key | Default | Applies to |
|-----|---------|-----------|
| `rx.cap_up_compound` | 0.05 | MODEL: `w <= w_last * 1.05` for compound exercises |
| `rx.cap_up_isolation` | 0.10 | MODEL: isolation and machine isolation |
| `rx.cap_up_fast` | 0.10 | MODEL: replaces the two above when the last exposure's last working set had `q_true >= q* + rx.cap_fast_rir_margin` (2), except `high_risk` (A7 fast lane) |
| `rx.cap_down` | 0.10 | MODEL: `w >= w_last * 0.90`, except pain follow-up, detraining, `too_hard` boost, fail-counter and tier-1 targets |
| `stall.fail_load_cut` | 0.075 | fast path (B9.7) |
| `stall.tier1_load_cut` | 0.10 | tier 1, hypertrophy (C13) |
| `reason.pain_load_cut` | 0.10 | pain follow-up |
| `autoreg.delta_min`, `autoreg.delta_max` | -0.08, +0.06 | in-session load changes (C16) |

`w_last` = the heaviest working `w_sys` of the last valid exposure (same side). When the cap band contains
no loadable, MODEL keeps `w_last` upward and allows `nextEasier(W, w_last)` downward. In DOUBLE an advance
by exactly one loadable step is exempt from the up caps even when that step exceeds them (C15 "next step",
C45); for `high_risk` exercises only when `ladderStart[1] >= r_lo` at the new load (C45). The exemption is
logged as `caps_applied[]` type `double_one_step`. Caps on engine prescriptions (MODEL and DOUBLE, general
items and `delegate:rx` challenge items) are SAFETY rules (D10, WE-S2): they are enforced and logged in
`caps_applied[]` `{type: up|down|fast|fail|tier1|pain|double_one_step|rule_excess, limit_w, unclamped_w}`.
They do not apply to RULE-mode loads (D13, C45; B9.11), which are only compared with the cap and reported.
The cap table and WE-S2 do not apply to HOLD-1 items at all (C70): plate, seconds and pin changes on holds
(e.g. the plank plate) are governed by hold progression (B8), so WE-S2 is not evaluated for them.

### B9.15 Outputs and logging

`rx.prescription` outputs (DATA_MODEL 10.2 keys first): `mode`; `sets[]: {set_index, side, target_reps,
rir, effective_load_kg}` where `rir` is the **target** RIR; `caps_applied[]`; plus `rir_pred[]`,
`load_point` (B2.8, with plate breakdown), `scheme {kind, r_lo, r_hi, r_star, q_star, sources}`,
`double {targets, ext}`, `probe`, `fail_counter`, `variant_suggestion`, `shadow` (RULE), `rest_s[]`. `rir_pred[]` is computed at
`M_use` (with `z_c` and `delta`), the estimate the prescription was made with, so review metric M4 shows a too large
`z_c` as a positive mean (M3.3 build note; C3.1 and C3.3 give their RIR the same way).
Trace: `M_use` parts (`L_pred`, `sd`, `z_c`, `delta`), the candidate table (`w`, `cap_fresh`, per-set reps
and RIR, `J` or feasibility), the coarse-step check, `gap_reps`, transforms applied. Prescription error
(`q_true - rir_pred` per set) is computed later by the review from `set_logs` (Part D).

Tests: `RxModelTest` (Part C examples: 6 x 2, leg press), `RxCapsTest` (including the RULE excess as WE-S2
WARN without a clamp, D13), `RxDoubleLadderTest` (Gravl sequence, extension, entry rule, one-step cap
exemption, C45), `RxFailCounterTest`, `RxProbeTest`, `RxRuleShadowTest` (including the re-plan after an
auto-applied in-session rule and its Undo, C46), `RxDeloadTest` (including deload plus re-entry, C51),
`RxUnilateralTest`, `RxSchemeResolutionTest`, `RxBodyweightVariantTest`, `RxDelegatedItemTest`
(`delegate:rx` items).

<a id="warmup-1"></a>
## B10. `warmup` (WARMUP-1): specific warm-up sets

**Purpose.** Brief, low-fatigue specific warm-up sets before the working sets, scaled to intensity. Specific
warm-ups generally help performance; whether to add a general warm-up is not agreed (Ribeiro systematic
review, weak). A challenge-supplied warm-up block replaces the generator for that session (OAP `warmup10`,
UX 11.5), and add-on blocks get no second general warm-up, only specific sets (C34).

**Algorithm.**

```
p_w  = w_work / M                 // first working w_sys over the SSE-1 posterior mean (not M_use)
warm = the exercise's primary group already received >= warmup.warm_min_sets (2) working sets today
       from exercises with group coefficient >= 0.5
if mechanics == isolation or p_w < warmup.p_mid (0.60) or warm:
     list = (first exercise today for this primary group and not warm) ? warmup.tier_low : []
elif p_w < warmup.p_high (0.80):  list = warmup.tier_mid
else:                              list = warmup.tier_high (+ warmup.tier_singles if r* <= 3 or session kind test)
tiers (fraction of w_work, reps):  low [(0.50, 10)]; mid [(0.50, 8), (0.75, 3)];
                                   high [(0.45, 6), (0.65, 3), (0.85, 1)]; singles [(0.92, 1)]
for each (frac, reps):
   w = easierOrEqual(W, frac * w_work)
   bodyweight family: if frac * w_work < f_bw * BW: use the assisted mode when the exercise allows it and the
        machine exists (w = easierOrEqual over pins), else plain bodyweight (w = f_bw * BW)
   reps = min(reps, floor(warmup.max_cap_frac * cap(w; M, k)))      // at most half of capacity
de-duplicate equal loads, keeping the higher reps; drop a set whose w >= w_work
rest: warmup.rest_light_s (45) after sets with frac < 0.65, warmup.rest_heavy_s (60) otherwise,
      warmup.rest_to_work_s (90) after the last warm-up set
```

Without an SSE-1 state `p_w` is unknown: a compound gets the mid tier, an isolation exercise the low tier when it is
the first of its group today (M3.3 build note).

**Example (weighted pull-up at +27.5 kg, OAP B load, outside a challenge session).** `w_work` = 98.20,
`M` = 109.94, `p_w` = 0.893 (high tier). 0.45 -> 44.2 kg < BW -> bodyweight, `cap(70.7)` = 18.0 -> 6 reps;
0.65 -> 63.8 kg < BW -> bodyweight x 3, de-duplicated into BW x 6; 0.85 -> 83.47 kg -> belt +12.5
(83.20 kg, `cap` 10.84) -> 1 rep. Result: **BW x 6, +12.5 kg x 1**, then +27.5. (The research note said
"BW x 4, +12.5 x 2", which its own table does not produce.) In an OAP session B the challenge's warm-up block
(including its pull-up ramp) is used instead.

**Outputs, logging, tests.** Snapshot `warmup.sets` (scope `session_exercise:<uuid>`): `tier`, `p_w` and
`sets[]: {load_point, reps, rest_s}` (review keys, D5). Warm-up sets are logged with "Done" and no RIR (C63;
D9's RIR chip applies to working sets), as `set_kind = warmup`. Logged: suggested versus done, time spent,
first working set residual with and without warm-up (review). `WarmupTest` (tiers, de-dup, bodyweight path,
assisted path, challenge override).

<a id="rest-1"></a>
## B11. `rest` (REST-1): rest-timer duration policy (C18)

**Purpose.** The single source of inter-set rest durations (C18): a class table, outcome modifiers,
challenge overrides, user preferences, slow per-user adaptation and a live "rest now vs wait" readout.
Rest is presented as a time-versus-performance trade-off, not as a hypertrophy requirement (ACSM 2026:
rest intervals shorter or longer than one minute show no consistent effect on hypertrophy). The platform
timer (alarm, notification, foreground service, DST-safe arithmetic) is specified in ANDROID.md and UX 11.13;
this module only returns durations. The between-session part of the research REST-1 is dropped (C5).

**Evidence.** Singer 2024 Bayesian meta-analysis (moderate): small benefit of rest above 60 s and no
appreciable hypertrophy difference beyond 90 s. Schoenfeld 2016 (moderate): 3 min beat 1 min for strength
and thigh thickness. Grgic 2018 review (moderate): more than 2 min to maximise strength in trained lifters.
Willardson 2006, Senna 2011: with 1 min rest reps drop from set 2, with 3-5 min from sets 3-4.

### B11.1 Inputs and output

Inputs: exercise `rest_class` (E41), the prescription (scheme, target reps, `p = w / M`), the last logged
set (reps, target, `q_true`, reason), `F_next` (FAT-1 prediction before the next set), side mode and
superset membership, the challenge item's rest values, the resolved user rest preference (`rest_pref_s` or
`rest_pref_mult`, D50), the adapted class bases. Output `RestDecision {timer_s, between_sides_s, base_s, source (challenge|user|engine),
modifiers[], readout}`.

### B11.2 Algorithm

```
if the next set is a warm-up set, or the previous set was one:
     timer = WARMUP-1 rest (45 / 60 s, 90 s before the first working set)
elif the challenge item defines rest (rest_s, restBetweenSidesS):
     timer = that value exactly; no modifiers; only the hard maximum applies          // C18 override
else:
     cls  = strength_heavy if (target reps <= rest.strength_heavy_max_reps (5)) or (p >= rest.strength_heavy_min_p (0.85))
            else exercise.rest_class
     base = user rest_pref_s if resolved,
            else adaptedBase(user, cls) x (user rest_pref_mult if resolved, else 1)   // D50
     mods = 0
     if q_true_last <= rest.mod_hard_rir (0.5) or reps_last < target_last - rest.mod_short_reps (1): mods += rest.mod_hard_s (30)
     elif q_true_last >= q* + rest.mod_easy_rir_margin (2):                                      mods -= rest.mod_easy_s (15)
     if F_next > rest.mod_fatigue_f (0.25):                                                      mods += rest.mod_fatigue_s (15)
     timer = no user preference ? clamp(base + mods, rest.min_s (60), rest.max_s (300))
                                : clamp(base + mods, rest.user_min_s (30), rest.user_max_s (600))  // D10: the user's base is kept
UI adjustments (steps timer.adjustStepS / timer.longPressStepS in ANDROID PlatformConfig, C54) are bounded by
rest.timer_hard_max_s (900) (C18)
```

Default bases (`rest.base`, C18): strength_heavy 180 s, compound_lower 150 s, compound_upper 120 s,
isolation 90 s, hold 90 s, core 60 s; warm-ups 45-60 s.

**Unilateral.** Sequential sides (all left sets, then all right sets): the normal per-set timer. Alternating
sides: `between_sides_s = rest.between_sides_s` (0 = "switch now", UX 11.7.2) unless the challenge item sets
one; after the pair `timer_pair = max(rest.min_s, timer - work(second side) - rest.side_switch_s)`, where
`work` is TIME-1's set duration (reps x tempo + 5 s) and `rest.side_switch_s` = 10 s, so the first side
still gets about `timer` of rest.

**Supersets (F13).** Between members `rest.superset_between_s` (60 s); after the pair
`max(rest.min_s, max(base_a - between - work_b, base_b - between - work_a))`, so each muscle gets roughly
its own base. TIME-1 and ORD-1 read these rest durations from REST-1 (C56; the research's 15 s ORD-1
transfer is superseded).

### B11.3 Live readout (the trade-off display)

`readout(elapsed_s)` = predicted capacity of the next set at the planned load with
`F_next(elapsed) = d * F_j + c(elapsed) * min(1, reps_j / cap_j)^gamma * (1 - F_j)`, returned for "now"
and for the timer end as reps at the target RIR (REP grid). Example after set 1 of 4 x 5 at +20 kg:
4.0 reps at RIR 2 after 60 s, 4.5 after 120 s, 4.6 after 150 s, 4.7 after 180 s (B7.6). The Why sheet copy
(UX 11.13.1) states the ACSM 2026 framing.

### B11.4 Adaptation (nightly, per user x class, engine bases only)

```
after >= rest.adapt_min_sets (10) logged timed sets of the class since the last change:
   early = share of the last 10 sets with rest_before_s <= timer_s - rest.adapt_early_s (20)
   resid = mean over those sets (j >= 2) of (n_eff_obs - n_eff_pred), FAT-1 with the ACTUAL rest
   if early >= rest.adapt_early_frac (0.8) and resid >= rest.adapt_resid_ok (-0.5):
        base -= rest.adapt_step_s (15), floor rest.adapt_floor[class] (isolation, hold, core 60; compound_upper 90;
        compound_lower and strength_heavy 120)
   elif resid <= rest.adapt_resid_bad (-1.0) and mean actual rest >= timer_s:
        base += rest.adapt_step_s, cap rest.max_s
   at most one change per class per rest.adapt_cooldown_days (7); never for a scope with a user rest_pref_s
   (a rest_pref_mult scales the adapted base, so the adaptation keeps running under it, D50)
```

Snapshot `rest.adapt` (scope `class:<key>`): `rest_class`, `base_old_s`, `base_new_s`, `early_share`,
`resid_mean`, `n_sets` (review keys, D5).

### B11.5 Examples

- OAP session C weighted pull-up 4 x 5: the challenge item says 150 s -> timer 150 s, no modifiers.
- Weighted pull-up 6 x 2 outside a challenge: FIXED `r* = 2 <= 5` -> strength_heavy 180 s.
- Leg press 3 x 8-10 (`q*` 2): after set 1 reported RIR 3 (`q_true` 3.5): no modifier (3.5 < 4) -> 150 s.
  After set 2 reported RIR 0 (`q_true` 0.5 <= 0.5): +30; predicted `F` before set 3 is 0.26 > 0.25: +15
  -> 195 s.

**Logging and tests.** Per set (facts): the rest columns of DATA_MODEL 9.6.4 `set_logs`, whose names are
canonical (C55): `rest_timer_planned_s` (this module's `timer_s`, before user adjustments), `rest_adjust_s`,
`rest_timer_overridden`, the actual rest `rest_before_s` with its `rest_source`, and `rest_end_reason`; plus
the `rest_*` events of the DATA_MODEL 9.8.1 registry. `RestPolicyTest` (table, modifiers, exclusivity,
clamps, challenge override, user base, unilateral pair, superset), `RestAdaptTest`.

<a id="reason-1"></a>
## B12. `reason` (REASON-1): override and skip reason semantics (C16)

**Purpose.** Give every in-gym reason a fixed, deterministic effect on (a) the estimators, (b) today's
session and (c) future prescriptions, and impute missing RIR. REASON-1 is the only source of reason
semantics (C16); app_benchmarks' A8/A10 tables are superseded. Every value below is expert-opinion and
tunable. The 13 codes and their UI contexts are seeded in `fitness.reason_codes` (DATA_MODEL 9.1.3).

### B12.1 Contexts

A reason is attached in one of five contexts (DATA_MODEL `ui_contexts`): `easier` / `harder` (a logged set
that deviates from its target, `set_logs.deviation_reason_code`), `skip` (exercise skipped,
`session_exercises.status_reason_code`), `session_end` (session finished or abandoned with pending work),
`substitute` (exercise swapped). An untouched optional chip is `none`.

### B12.2 Estimator classes

| Class | Effect in SSE-1 / RCM-1 / HOLD-1 |
|-------|----------------------------------|
| `normal` | used with its computed `s_i` |
| `downweight m` | used with `s_i^2 * m` |
| `lower_bound` | can only raise the estimate (B6.4); not used by RCM-1 |
| `excluded` | no observation; the set stays a fact |

### B12.3 Reason table

| Code | Set context: estimator class | Today (session effect) | Future effect | Skip / end semantics |
|------|------------------------------|------------------------|---------------|----------------------|
| `too_easy` | normal; if RIR is missing impute `q = rir_pred + reason.impute_offset` (2) | UI-06 trigger (d); AUTO-1 may propose a heavier load (within +6 %) | two consecutive exposures of the exercise with `too_easy` and reported RIR `<= q*`: `b += 0.25` (B4.5) | not offered |
| `too_hard` | normal; if RIR is missing impute `max(0, q* - 2)` when reps >= target, else 0 | UI-06 trigger (d); AUTO-1 may propose a lighter load (within -8 %) | two consecutive exposures with `too_hard`: `z_c = 1.0` for the next 2 exposures (B9.5) | not offered |
| `feeling_good` | normal | `delta_today` may rise to +6 % for the remaining exercises (AUTO-1 transfer allowed with one finished exercise) | none | not offered |
| `feeling_bad` | downweight `m = reason.feeling_bad_m` (4, i.e. sd x 2) | session offer (one card, D10): remaining exercises `delta = reason.feeling_bad_delta` (-5 %) and -1 set each | counts toward the recovery check: `feeling_bad + too_tired >= 3` in 14 days -> WE-T2 prompt and STALL-1 global trigger T3; excluded from STALL-1 exposure counts and the fail counter | skip: adherence only, counts toward the 3-in-14-days rule |
| `too_tired` (same effect as `feeling_bad`, C57) | downweight `m = reason.too_tired_m` (4) | same offer as `feeling_bad` | same counter as `feeling_bad`; readiness input for planning (ALGORITHMS_PLANNING) | as `feeling_bad` |
| `no_time` | done sets normal; sets not done have no observation | remaining work dropped by priority (UI-02 / TIME-1; accessories first, challenge and primary lifts last), rest at the floor 60 s | no make-up volume; not a failure; excluded from the fail counter and STALL-1 counts | skip / end: adherence = time-limited |
| `occupied` | no observation for the affected exercise | SUB-1: reorder first ("do k now"), then up to 3 substitutes; the `occupied` reason fact (with the registry events `exercise_skipped` / `exercise_substituted` and their `reason_code`, DATA_MODEL 9.8.1, C39) is the busy record; station, weekday and local hour are derived from it | station busy in >= `reason.busy_frac` (50 %) of the last `reason.busy_visits` (4) visits in the same weekday and hour (+/- 1 h) -> SEL-1 deprioritises it as first choice at that slot | skip: adherence only, not a failure |
| `equipment_missing` | no observation | SUB-1 substitute | `reason.equipment_missing_events` (2) in `reason.equipment_missing_days` (28) -> prompt "mark as unavailable?" in the equipment checklist (never automatic) | skip: adherence only |
| `pain` | excluded | the exercise ends (remaining sets skipped with `pain`); the PainEvent goes to `safety` (C33, ALGORITHMS_PLANNING section 2), which owns the joint flags for the following days; the severity chips map to Mild 2 / Moderate 5 / Sharp 8 on the 0-10 scale (C64). The stop threshold is applied here (ALGORITHMS_PLANNING 2.2 leaves it to REASON-1): a score >= `reason.pain_stop_score` (4, C64) removes, for the rest of today, every remaining exercise that loads that joint clearly (catalog joint-stress key `js_<joint>` >= `reason.pain_stop_js_min` (2) for a joint key of the reported body region; D49, OI-A25), so Moderate and Sharp stop and Mild does not, and an exercise with only light stress (1) on that joint stays; the challenge pain protocol may start | next `reason.pain_followup_exposures` (2) exposures of the exercise: load -10 %, `q* >= 3`, or a lower-stress substitute; `reason.pain_repeat_events` (2) events in the same body region within `reason.pain_repeat_days` (14) -> WE-S3 advice to stop and get a professional check (not medical advice) | skip / end with pain: same PainEvent path |
| `grip_failure` | lower_bound (the muscle could do more) | INFO line: straps suggested when the exercise allows `used_straps` and is not a challenge grip item | excluded from "all sets hit" progression checks (no ladder step, no fail count); `reason.grip_events` (3) in `reason.grip_days` (28) -> WE-P10 (grip work) | not offered |
| `torso_rotation` | normal with `q_true = 0` at the reps done (technique-limited); holds: `s_res = 0`; flag `torso_rotation` implied | AUTO-1 may propose -1 rep or -5 % for the next set; counts toward the technique stop (B13.4) | none in the general engine; challenge rules read the flag | not offered |
| `technique_breakdown` | normal with `q_true = 0` at the reps done; flag `technique_breakdown` implied | as `torso_rotation` | none | not offered |
| `other` | normal if numbers were logged, downweight `m = reason.other_m` (2.25, sd x 1.5) | none | none; free text never enters telemetry or engine inputs | skip / end: adherence only |
| `none` (untouched) | normal | none | none | `not_done` at session finish: adherence only |

### B12.4 Missing-RIR imputation (no chip value; legacy, import, or `rir_source = missing`)

```
reps >= target:        q = clamp(rir_pred, 0, 5)
reps == target - 1:    q = clamp(min(1, rir_pred), 0, 5)
reps <= target - 2:    q = 0
sigma_q = max(computed sigma_q, reason.impute_sigma_q (2.0))
```

Imputed values live only in the engine trace (DATA_MODEL E5); facts keep `rir_source = missing`. Under D9
every logged dynamic working set has a chip RIR, so imputation is rare. Eccentric-only sets have no RIR
by design (C66: lowering seconds with the `jerky` flag plus the effort chips Max / Hard / OK / Easy); a
missing RIR on them is expected, they are never imputed, and they are excluded from the imputed-RIR share
of M5. Warm-up sets are logged with "Done" and no
RIR (C63); they never enter an estimator and are never imputed, and they are excluded from the imputed-RIR
share of review metric M5. Test-day attempts entered with "Record" carry their protocol RIR (a 3RM is
stored with RIR 0, C63) and are not imputed either.

**Logging and tests.** The reason code is a fact on the set or exercise row; every derived effect is a
trace step code (for example `reason.downweight`, `reason.pain.followup`) in the consuming module's
snapshot. Reviews check whether `feeling_bad` sets really underperform (mean residual) and how often
`too_easy` disagrees with the reported RIR. `ReasonSemanticsTest` covers each row of the table.

<a id="auto-1"></a>
## B13. `autoreg` (AUTO-1 with the UI-06 trigger): in-session suggestions (C16)

**Purpose.** Adapt the rest of today's session to today's readiness without chasing noise. UI-06 decides
**when** a suggestion is shown; AUTO-1 computes **what** it proposes; REASON-1 supplies reason semantics.
AUTO-1 serves general items (including `delegate:rx` challenge items, OI-A20): default action Keep, never
auto-applied, at most one card per exercise per session (D10, C16; C46 limits this rule to general items).
Challenge in-session rules are a different mechanism (auto-applied with Undo, D14, C46; B13.5). RPE/RIR
autoregulation is at least as good as fixed
percentages (Helms 2018, Zhang 2021, Larsen 2021; moderate); the clamps and hysteresis are expert-opinion.

M3.5 build note: on the phone AUTO-1 serves the exercises RX-1 prescribes (general items and `delegate:rx` items);
RULE items have no `rx.prescription` there, so their shadow `delta_ex` waits for the RULE shadow snapshot and the
session readiness of B13.5 reads the general items only. After a probe, AUTO-1 reads the follow-up's input (the
estimate updated with the probe). `delta_today` is computed when an exercise's first set is evaluated, from the
exercises whose working sets all came before it.

### B13.1 Trigger (UI-06)

```
evaluated after each logged working set j of exercise e (unilateral: after each completed pair, on the side
whose state set the load: the weaker side under match_weaker, each side under independent)
eligible: mode model or double (RULE items: shadow only, B13.5); not a warm-up; not the probe (B9.8);
          reason != pain; remaining sets > 0; cards shown for e this session < autoreg.max_suggestions_per_exercise (1)
Delta_j = q_true_j - rir_pred_j                        (autoreg.trigger_basis = "model", C57: the bias-corrected
                                                       reported RIR against the model-predicted RIR)
          alternative basis "raw_vs_aim": q_rep_j - q*  (kept only for :sim counterfactual comparisons)
fire if any:
  (a) j == 1 and |Delta_1| >= autoreg.first_set_delta (2.0)
  (b) j >= 2 and Delta_j, Delta_{j-1} have the same sign and both |Delta| >= autoreg.consec_delta (1.0)
  (c) reps_j <= target_j - autoreg.reps_short (2)
  (d) reason_j in {too_easy, too_hard}
```

### B13.2 Readiness offset (updated after every working set, shown or not)

```
eps_j  = ln w + ln(1 + (n_eff_j / (1 - F_j) - 1) / k) - L_pred        // z_j - L_pred (B6.3)
s_j^2  = SSE-1 per-set variance (B6.3) + sse.sd_day^2
K_s    = P_LL / (P_LL + s_j^2)                                        // typically 0.3-0.5
delta_ex = clamp(delta_ex + K_s * (eps_j - delta_ex), autoreg.delta_min (-0.08), autoreg.delta_max (+0.06))
delta_ex starts at delta_today when the exercise starts
delta_today for exercises not yet started = autoreg.transfer (0.5) * mean(delta_ex of finished exercises)
            if >= autoreg.transfer_min_agree (2) finished exercises agree in sign (feeling_good: 1 suffices), else 0;
            a feeling_bad / too_tired offer, when applied, sets delta_today = min(delta_today, reason.feeling_bad_delta)
```

The long-term update happens only in SSE-1 at session finish; `delta_ex` is a same-day offset.

### B13.3 Proposal (when the trigger fires)

```
re-run RX-1 (B9.5 or B9.6) for the remaining sets with M_use * exp(delta_ex) and the actual F state
rir_next(w) = predicted RIR of set j+1 at load w
if rir_next(w_cur) < q* - autoreg.safety_drop_margin (1.0):    load change allowed (safety drop, no -8 % floor)
elif |rir_next(w_cur) - q*| >= autoreg.hysteresis_rir (1.5):   load change allowed
else:                                                          no load change; RANGE: propose reps +/-1 inside
                                                               the range if it moves predicted RIR toward q* by >= 0.5
bounds for a load change (relative to the planned load w_plan of the exercise):
   w_new <= w_plan * (1 + autoreg.delta_max)                          // +6 %
   w_new >= w_plan * (1 + autoreg.delta_min)                          // -8 %, except a safety drop
   raise per suggestion: FIXED / strength goal at most nextHarder(W, w_cur); otherwise w_new <= w_cur * (1 + autoreg.raise_max_rel (0.05))
if w_new == w_cur and no reps change: no card (a Keep outcome never renders)
```

### B13.4 Stop advice (one card, counts toward the limit)

(a) Fatigue stop: no candidate `w >= autoreg.fatigue_stop_floor (0.85) * w_plan` predicts reps `>= r_lo` at
RIR `>= max(0, q* - 1)` -> "end this exercise". (b) Two consecutive sets with reps `< r_lo` at reported RIR 0.
(c) Two technique events (`technique_breakdown` or `torso_rotation`) in the exercise. (d) Time budget
exceeded -> the `no_time` flow (UI-02 trimming, REASON-1). Safety notices (WE-S1, WE-S5) are WarningEngine
outputs and do not count toward the limit.

### B13.5 RULE items

For RULE-mode challenge items AUTO-1 updates `delta_ex` and logs what it would have proposed (shadow), but
never shows a card. What changes a RULE item's remaining sets in the session is the challenge's in-session
rules (CHALLENGE_ENGINE 6.10): per D14 and C46 they are **auto-applied** to the remaining sets with a
visible "Rule: ... · Undo" on the set card, limited per rule per item; an Undo is logged as an override
with its reason. They are not subject to the C16 one-suggestion limit, and AUTO-1's shadow continues from
the re-planned rows (B9.11). The session readiness signal (mean `delta_ex`) includes RULE items, which
feeds STALL-1 trigger T2.

### B13.6 Worked example (illustration with `P_LL = 0.0016`)

6 x 2 at +27.5 kg (`w` 98.20), `M` 109.94, `k` 30.63, `q*` 2, rest 180 s. Set 1 reported RIR 4:
`q_true` 4.5, `n_eff` 6.5, `exp(z)` = 115.85 kg, `eps` = +5.22 %, `s_j` = 0.0569, `K_s` = 0.330,
`delta_ex` = +1.73 %. Predicted RIR of set 2 at +27.5 with `M * e^0.0173`: 3.14, deviation +1.14 < 1.5 ->
keep the load. Trigger: `Delta_1 = 4.5 - 2.66 = 1.84 < 2` (model basis, C57) -> no trigger; with the raw
basis (a `:sim` counterfactual only) `4 - 2 = 2` the trigger would fire, but the proposal is Keep, so no card
would appear either way. (In the OAP session B
this item is RULE mode; the challenge rule decides next week's load.)

**Outputs, logging, tests.** Snapshot `autoreg.suggestion` (scope `session_exercise:<uuid>`):
`trigger_rule`, `deltas[]` (the `Delta_j` values), `delta_ex`, `kind` (`load`, `reps` or `stop`),
`proposed {load_point, reps[]}`, `rir_next_cur`, `rir_next_new` (predicted RIR of the next set at the
current and the proposed load; review keys, D5) and the hysteresis numbers; user response in
`session_events` (`suggestion_shown`, `suggestion_applied`, `suggestion_kept`, implicit Keep). Review: hit
rate of post-change predictions, acceptance rate. `AutoregTest` (triggers a-d, limit 1 for general items,
no card for RULE items, hysteresis, bounds, safety drop, transfer, example).

<a id="stall-1"></a>
## B14. `stall` (STALL-1): stall, regression, tiers and the global deload trigger (C13, C15)

**Purpose.** Detect stalled or regressing exercises and global fatigue, apply tiered fixes per exercise,
and feed the reactive part of the one DeloadPolicy owned by `targets` (C13). Planned deloads are not this
module's business (F8: synced with the challenge block). Coleman 2024 (PeerJ; moderate) found a one-week
training cessation gave no hypertrophy benefit and slightly worse strength, which argues for reactive rather
than frequent planned deloads; Bell 2023 (Delphi) defines a deload as reduced training stress.

### B14.1 Per exercise (after each session and weekly)

```
for each exercise and side with an SSE-1 state, excluding RULE items (evaluated in shadow, reported, never acted on):
  E = valid exposures in the last stall.window_days (42), excluding exposures with pain, feeling_bad or
      too_tired, deload weeks and probes
  p_prog = P(g > stall.slope_progress) = 1 - Phi((stall.slope_progress - g) / sqrt(P_gg))
  status = INSUFFICIENT_DATA                 if |E| < stall.min_exposures (4)
           REGRESS                           if g < stall.slope_regress (-0.003 / week) and the session residual
                                             (zbar - L_pred) was < 0 in >= stall.regress_resid_count (3) of the last 4 exposures
           STALL                             if g < stall.slope_stall (0.001 / week) and p_prog < stall.p_progress_max (0.2)
           PROGRESSING                       otherwise
  a stall event = a transition into STALL or REGRESS; events in the last stall.tier_window_weeks (8):
     first  -> tier 1 action for the next exposure (B9.9): sets x 0.6, strength load kept / hypertrophy -10 %, q* + 1
     second -> tier 2 for stall.tier2_weeks (5): rep range shifted by stall.tier2_rep_shift (4), down if r_lo - 4 >= 3
               else up; a variant swap is proposed to ROT-1 for the next mesocycle (F12)
  a new event needs >= stall.min_exposures valid exposures after the previous action
Phi(x) = 0.5 * erfc(-x / sqrt(2)), erfc by the Numerical Recipes rational approximation (fractional error < 1.2e-7),
         exp through StrictMath (deterministic)
```

The fail counter (two failed exposures -> -7.5 %) is STALL-1's fast path and is specified with RX-1 (B9.7).

### B14.2 Global trigger (weekly at week close; feeds targets' DeloadPolicy)

```
active = non-RULE exercises with >= 2 valid exposures in the last 28 days; rule off if |active| < stall.global_min_active (5)
T1: >= stall.global_frac (40 %) of active exercises had status STALL or REGRESS at some evaluation in the last 14 days
T2: session readiness (mean delta_ex of the session, RULE items included) <= stall.global_delta (-0.04) in
    >= stall.global_delta_sessions (3) of the last 4 sessions
T3: feeling_bad + too_tired reasons >= stall.global_bad_events (3) in the last 14 days
(the minimum of stall.global_min_active active exercises applies to T1, the rule that counts them)
any -> globalDeloadRecommended {triggers[]} + a checklist prompt (sleep, food, stress, BW trend; UI-07)
```

`targets` takes the union with planning's reactive triggers, evaluates it weekly and applies the one deload
transform (sets x 0.5, load kept, RIR + 2, C13; never stacked with re-entry, C51); STALL-1 also reports
`plannedDeloadWithin7Days` (snapshot key `planned_deload_within_7_days`) so targets can merge a reactive
recommendation into an upcoming challenge-synced deload week.

### B14.3 Outputs, logging, tests

Snapshot `stall.weekly` (scope `week:<iso_week>`): `exercises[]: {exercise_id, side, status, g, sd_g, p_prog,
n_exposures, event, tier_action}`, `fail_counters[]: {exercise_id, side, count}` and `global: {triggers[],
recommended, planned_deload_within_7_days}` (review keys, D5). RULE items appear with `tier_action = none`
(shadow). The review records the slope four weeks after each action to judge tier efficacy.
`StallTest` (status boundaries, tier sequence, cooldown, global triggers including the minimum of 5 active
exercises, erfc determinism).

<a id="we-1"></a>
## B15. `warnings` (WE-1): the one WarningEngine (C14, D10)

**Purpose.** One engine for every evidence and safety warning (C14): it merges the load-prescription WARN-1
rules R1-R10 and the planning rules W1-W8, uses four severities (INFO, WARN, STRONG, SAFETY), keeps the
user's preferences (D10 warn-only) and enforces only the safety rules. Display (banner, dot, "your choice",
Why sheet) is UI-10 (`ui.warndisplay`, specified in ALGORITHMS_PLANNING 19.5 per C53, display details in UX
17); this module only evaluates.

M3.5 build note: the rules that read weekly targets, the time model or the week plan's volume (WE-V2, WE-V4 to V7,
WE-P8, WE-L1) arrive with M4; WE-V1 and WE-V3 are implemented for their fixtures but not fed yet. WE-S3's repeat-pain
advice waits for the wellness history read. WE-S4 on the phone covers the rest day before B or TEST, the hard
protected windows, the add-on block's forbidden groups and tags, and the gap-day tags with the C50 light-pull filter
(a pull-family exercise outside that filter is WARN, a session tag the gap day keeps out is SAFETY). An enforced SAFETY
(the engine clamped its own output) is shown in the notes with a dot, not in the safety slot, because there is
nothing to acknowledge.

### B15.1 Inputs

`WeeklyTargets` and projected weekly volumes per group (targets, setcredit; fractional hard sets as
SetCredit computes them, including challenge committed dose, C6, C14; per C44 coefficient levels 1.0 / 0.75 /
0.5 earn credit equal to the coefficient, 0.25 counts toward fatigue only, and SetCredit uses the reported
RIR), the `SessionPlan` and `rx` outputs
(predicted capacity, caps, modes), resolved rep preferences with their sources, rest settings, goals,
14-day history (maintenance intensity, frequency), the challenge contract (covered groups, exemptions, hard
constraints), in-session events (logged RIR on high-risk lifts, manual load changes, "add anyway"), STALL-1
and REASON-1 events, `evidence_mode`.

### B15.2 Rule table

`x` = the measured quantity; magnitude is on the RATIO grid in 0..1.

| Rule id | Name | Origin | Condition and severity | Magnitude | Evidence | Suggestion |
|---------|------|--------|------------------------|-----------|----------|-----------|
| WE-S1 | no_failure_high_risk | R3, D10, C45 | prescription or preference with `q* < 1` on a `high_risk` exercise (challenge items included, through their target RIR) -> clamped, SAFETY (enforced); a logged RIR 0 or `failed_rep` on a `high_risk` working set -> SAFETY notice. Eccentric-only sets have no RIR (C66), so the logged-RIR check does not apply to them. Test-day attempts entered with "Record" (`set_kind` `test` or `attempt`; a 3RM is stored with RIR 0 by protocol, C63) produce no notice (OI-A22) | 1 | expert-opinion (elbow and tendon risk; D10 decision) | next set at a load predicted to leave `q*` in reserve (AUTO-1 machinery, outside the one-card limit) |
| WE-S2 | load_jump_cap | R9, C15, D10, D13, C45 | engine prescription (MODEL, DOUBLE) above a cap -> clamped, SAFETY (enforced), except the DOUBLE one-step advance (C45, B9.14); a manual load above the cap -> SAFETY notice (logging never blocked); a challenge RULE-mode load above the generic cap -> **WARN** (not enforced, non-blocking, logged; D13, C45); not evaluated for HOLD-1 items (holds, plank plate; C70) | `(w - w_cap) / w_cap` | expert-opinion | the capped load (general); none for RULE items (the approved challenge rule wins) |
| WE-S3 | pain_protocol | C33, REASON-1 | PainEvent -> stop exercise or joint (enforced by `safety`); 2 events in 14 days in one region -> advice | 1 | expert-opinion; not medical advice | stop / substitute |
| WE-S4 | challenge_hard_constraint | C11, C12, D10 | the user adds an exercise with a tag forbidden in a hard protected window, or a gym session on a rest-day-before-B/TEST day -> SAFETY notice `rule_override` (the engine never recommends it) | 1 | per challenge definition | remove / move |
| WE-S5 | predicted_failure_next_set | D10 | in session, `high_risk` exercise, `rir_next(w_cur) < rx.min_q_high_risk` (1) -> SAFETY | `(1 - rir_next) / 1` clamped | expert-opinion | easier load |
| WE-V1 | improve_weekly_low | W1 + R4, C14 | goal improve: `x` = projected weekly fractional sets; `x < 10` INFO, `x < 6` WARN, `x < 3` STRONG | `(10 - x) / 10` | moderate (Pelland 2024/2026; ACSM 2026; Schoenfeld 2017 from memory, C29) | +1 set per exposure or one more exposure; shows `sqrt(x_new / x)` expected stimulus |
| WE-V2 | maintain_insufficient | R4 + W5 | goal maintain: 0 hard sets in 10 days -> WARN; no set at RIR <= 3 and >= 85 % of the usual working load in 14 days -> WARN | 1 / `(0.85 - p) / 0.85` | moderate (Spiering 2021) | one hard set at usual load |
| WE-V3 | session_volume_high | R5 + W2, C14 | per session and group `x > 11` INFO, `x > 15` WARN; labelled "preliminary evidence" | `(x - 11) / 11` clamped | weak (Remmert preprint, C29) | split across sessions |
| WE-V4 | weekly_above_mrv | W3 | projected weekly `x > MRV` (targets' table) and the group is not challenge-exempt -> WARN | `(x - MRV) / MRV` | expert-opinion (RP illustrative; Pelland diminishing returns) | reduce sets |
| WE-V5 | frequency_low | W4 | improve or strength goal, target > 10 and fewer than 2 exposures per week -> INFO | 0.5 | moderate (ACSM 2026; Pelland strength frequency) | split over 2 sessions |
| WE-V6 | skip_group_trained | R10 | a `skip` group receives sets with group coefficient >= 0.75 -> INFO | `x / 3` clamped | engineering-fact (goal mismatch) | substitute |
| WE-V7 | family_all_skip | W6 | push, pull or legs entirely `skip` -> INFO, once (dismiss forever) | 0.3 | moderate (ACSM 2026 health guideline) | none |
| WE-P1 | reps_too_high | R1 + W7 | `cap` at the prescribed load > 30, or preferred `r_hi > 30` -> WARN; > 40 -> STRONG | `(x - 30) / 30` clamped | moderate (Lopez 2021; Schoenfeld 2021; Lasevicius 2018 from memory) | heavier load in the user's range |
| WE-P2 | rir_too_high | R2 + W7 | hypertrophy `q* >= 4` WARN, `>= 5` STRONG | `(q* - 3) / 2` clamped | moderate (Robinson 2024; Refalo 2023) | `q*` 2 |
| WE-P3 | single_set | W7 | preferred sets per exercise = 1 -> INFO | 0.3 | moderate (ACSM 2026) | 2 sets |
| WE-P4 | high_rep_rir_accuracy | W7 | preferred reps > 12 -> INFO "RIR estimates are less accurate" | 0.2 | moderate (Halperin 2022) | none |
| WE-P5 | strength_reps_high | R6 | strength-goal exercise `r_lo > 6` WARN, `> 10` STRONG | `(r_lo - 6) / 6` clamped | moderate (Schoenfeld 2017; Lopez 2021) | FIXED 3-5 |
| WE-P6 | rest_short | R7 | rest < 60 s on a compound WARN; < 90 s on a strength-goal exercise WARN; copy frames rest as a performance trade-off (C18) | `(60 - x) / 60` | moderate for strength (Grgic 2018), weak for hypertrophy (ACSM 2026) | 120-180 s |
| WE-P7 | range_too_narrow | R8 + B9.6 | range width < 2 and `reps_per_step > 1.5`, or the DOUBLE extension is exhausted -> INFO | 0.3 | engineering-fact | widen the range or finer increments |
| WE-P8 | stimulus_ratio | WARN-1 compare | ratio of the user's scheme to the best scheme with the same set count and +/-10 % time (B15.5): `< 0.85` WARN, `< 0.70` STRONG | `1 - ratio` | expert-opinion (ordinal fit to Robinson 2024, Refalo 2023, Pelland 2024) | the best scheme |
| WE-P9 | failure_on_safe | R3 | `q* = 0` on a non-high-risk exercise -> INFO | 0.2 | moderate (Refalo 2023/2024: trivial benefit) | `q*` 1 |
| WE-P10 | grip_limited | REASON-1 | >= 3 `grip_failure` events in 28 days -> INFO | 0.3 | expert-opinion | grip work or straps |
| WE-P11 | bodyweight_reps_high | B9.12 | `cap - q* > 20` for 2 exposures -> INFO | 0.3 | expert-opinion | weighted mode or harder variant |
| WE-L1 | no_rest_days | W8 | the week plan has no rest day or >= 4 consecutive training days (challenge sessions and hard runs included) -> INFO | 0.3 | weak (Bell 2023) | move a session |
| WE-T1 | stall_action | B14 | a STALL-1 tier action would change a user-owned field -> INFO with "use suggested" | 0.5 | expert-opinion | the tier change |
| WE-T2 | recovery_check | B14 T3, REASON-1 | `feeling_bad + too_tired >= 3` in 14 days, or a global deload trigger -> INFO with the checklist | 0.5 | expert-opinion | checklist |

RP MEV numbers are never compared with fractional counts (C14); WE-V4 uses the MRV caps owned by `targets`.

### B15.3 Which rules override (SAFETY)

Only WE-S1 to WE-S5 are SAFETY. They implement D10's list "no failure on weighted / one-arm pulls or loaded
dips, load-jump caps, pain protocol, challenge hard constraints". For engine outputs they are **enforced**
(`enforced = true`, the prescription is clamped, the planner and SEL-1 never produce the violation). For user
actions they produce SAFETY notices that persist until acknowledged; logging is never blocked (UX 11.12,
16.2). For challenge RULE items (D13, C45; B9.11): WE-S2 is not enforced (generic load-jump caps do not
limit RULE-mode loads; an excess yields WE-S2 at WARN severity and a log entry), WE-S2 is not
evaluated for HOLD-1 items at all (C70), while WE-S1 (through the
target RIR field), WE-S3 (pain protocol) and WE-S4 (protected windows and other hard constraints) still bind
challenge sessions.

### B15.4 D10 policy (warn, keep the user's preference)

| `evidence_mode` | Non-safety rule with a suggestion, field owned by the user | Field owned by the engine |
|-----------------|------------------------------------------------------------|---------------------------|
| `warn_only` (default, D10) | keep the user's value; show the warning with "use suggested" (writes the suggestion as a new preference, event `warning_use_suggested`) and "use mine" (marks "your choice") | the engine uses its evidence-based default directly; no warning needed |
| `auto_adjust_with_warning` | WARN and STRONG suggestions are applied to the plan (not to the stored preference) and the warning offers "use mine" to revert for this plan | same |

### B15.5 Stimulus model for WE-P8

```
hypertrophy (per set): h = stim_rir(q) * f_load(cap)
    stim_rir = setcredit.stim_rir (C6: RIR 0..5 -> 1.00, 1.00, 0.95, 0.90, 0.80, 0.50; above 5 -> 0)
    f_load(cap) = 1.0 if cap <= 30; 0.85 if 30 < cap <= 40; 0.60 if cap > 40          (warnings.f_load)
    group value H_g = sqrt(V_other + V_e) with V_e = sum over the week's exposures of coeff * h, where a
        coefficient below setcredit.min_hyp_coeff (0.5) earns no credit (C44: 0.25 counts toward fatigue only)
    ratio_hyp = H_g(user scheme) / H_g(best scheme)
strength (per exercise and session): S = (1 - exp(-sets / warnings.strength_tau_sets (1.5))) * f_int(p), p = w / M
    f_int = 1.0 (p >= 0.85), 0.95 (0.80-0.85), 0.85 (0.70-0.80), 0.70 (0.60-0.70), 0.50 (< 0.60)   (warnings.f_int)
    ratio_str = S(user) / S(best)
candidate schemes (warnings.compare_schemes): hypertrophy RANGE {5-8, 6-10, 8-12, 10-15, 12-20, 15-25} x q* {1, 2, 3};
    strength FIXED r* {2, 3, 4, 5, 6} x q* {1, 2, 3}; same set count; TIME-1 duration within +/- 10 %;
    each candidate is evaluated with RX-1 MODEL (load, predicted RIR per set)
```

### B15.6 Ranking, de-duplication, outputs

Warnings are ranked by severity (SAFETY > STRONG > WARN > INFO), then magnitude descending, then rule id,
then entity id, and de-duplicated per `(rule_id, entity)` within one evaluation. Snapshot
`warnings.evaluation` (scope `session:<uuid>` or `week:<iso_week>`) holds the list `warnings[]` with the
review keys `{rule_id, severity, entity, magnitude, evidence_grade, enforced}` (D5, C58) plus `citations`,
`numbers` and `suggestion`. User responses are `session_events` (`warning_shown`, `warning_dismissed`, `warning_use_mine`,
`warning_use_suggested`, `warning_why_opened`). The review compares outcomes (e1RM slope, weekly sets) for
warned and unwarned schemes.

### B15.7 Examples

- 3 x 8-10 at RIR 2, improve muscle, 2 exposures: ratio 1.0, no WE-P8; WE-V1 depends on the week total.
- A friend's "2 x 12 at RIR 2" for an improve muscle trained twice a week: `x = 4` fractional sets ->
  WE-V1 **WARN** (4 < 6, magnitude 0.60) with "+1 set per session: sqrt(6/4) = +22 % expected stimulus".
- The same "2 x 12" on a strength-goal weighted pull-up: load for 12 reps at RIR 2 is 77.18 kg (`p` 0.702,
  `f_int` 0.85) versus the best "2 x 4" at 94.51 kg (`p` 0.860, `f_int` 1.0): ratio 0.85, not below 0.85, so
  no WE-P8; but WE-P5 fires **STRONG** (`r_lo` 12 > 10).
- `q* = 0` preferred on weighted dips: WE-S1, clamped to 1 (enforced), shown as SAFETY.

**Tests.** `WarningEngineTest` (each rule's thresholds and severities, D10 policy in both modes, SAFETY
enforcement flags, WE-S2 at WARN without enforcement for RULE items (D13, C45), no WE-S1 notice for recorded
test attempts, ranking and de-dup, examples).

---


<a id="part-c-params-json-v1"></a>
# Part C. Params JSON v1

## C1. Conventions of the table

- One row per key of `default-params-1.0.0.json` for the modules of Part B. Keys of `setcredit`,
  `readiness`, `targets`, `runs`, the planning and selection modules, `calendar.classify`, `safety` and the
  `challenge.*` modules are defined by their own documents; this document reads `setcredit.stim_rir` (C6)
  and `targets.mrv` from them.
- **Bounds** are the validator's `min..max` (A8.2); a tuned value outside them is rejected.
- **Evidence**: strong / moderate / weak / expert-opinion / engineering-fact (0.1). Every value that is not
  `engineering-fact` is `tunable: true`.
- **Tuned by** names the logged data (tables, snapshot keys, review metrics M1-M12 of Part D) that a review
  uses to refit the value. "Decision" means the value comes from DECISIONS.md and changes only there.
- Citations marked "from memory" in the research (Lasevicius 2018, Schoenfeld 2017 volume, Sanchez-Medina
  2011, the APRE table) must be verified before a review tightens a constant that cites them (C29).

## C2. Table

### `clock`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `clock.meso_weeks` | 5 | 3..8 | weeks | expert-opinion | F12, C31 (5-week blocks aligned with the OAP block) | Decision |

### `load`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `load.lb_kg` | 0.45359237 | fixed | kg/lb | engineering-fact | international pound definition | not tunable |
| `load.epsilon_kg` | 0.005 | 0.001..0.01 | kg | engineering-fact | half of the KG grid | not tunable |
| `load.default_plate_inventory` | `{"bar_kg": 20, "25": 20, "20": 20, "15": 20, "10": 20, "5": 20, "2.5": 20, "1.25": 20}` (params 1.0.1: gym plates unlimited, F7; 1.0.0 had 4) | counts 0..20 | kg, count | expert-opinion (unverified gym inventory) | load_prescription LOAD-1; F7 checklist overrides | the equipment checklist (user); `equipment_calibration_changed` events (DATA_MODEL 9.8.1) |
| `load.belt_max_kg_default` | 60 | 20..100 | kg | expert-opinion | challenge research (P) | user checklist |
| `load.belt_max_items_default` | 4 | 1..8 | plates | expert-opinion | load_prescription LOAD-1 | user checklist |
| `load.pin_max_unknown_native` | 100 | 50..300 | lb | expert-opinion | this document (used until the checklist asks the machine maximum) | user checklist |
| `load.review_f_bw_pull_dip` | 0.955 | 0.90..1.00 | ratio | moderate | de Leva 1996 segment masses (review option only, C1) | `:sim` counterfactual of k and M fits |

### `bodyweight`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `bodyweight.ewma_alpha` | 0.3 | 0.05..1.0 | - | expert-opinion | C32 | one-step-ahead weigh-in residual (`metric_observations` bw) |
| `bodyweight.stale_days` | 14 | 3..60 | days | expert-opinion | C32; data_model S4 | weigh-in frequency vs BW variance |
| `bodyweight.min_kg` | 20 | fixed | kg | engineering-fact | DATA_MODEL range | not tunable |
| `bodyweight.max_kg` | 300 | fixed | kg | engineering-fact | DATA_MODEL range | not tunable |

### `rircal`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `rircal.b0` | 0.5 | -1..3 | reps | moderate | between Halperin 2022 (0.95) and Refalo 2024 (0.65) | explicit checks (`estimation.rircal`), M3 bias by RIR bucket |
| `rircal.sq0` | 1.0 | 0.5..3 | - | moderate | Halperin 2022; Steele 2017 | explicit checks |
| `rircal.lr_explicit` | 0.2 | 0.05..0.5 | - | expert-opinion | load_prescription RIRCAL-1 | stability of `b` across checks |
| `rircal.lr_implicit` | 0.1 | 0.0..0.3 | - | expert-opinion | load_prescription RIRCAL-1 | agreement of implicit and explicit `e` |
| `rircal.b_min` | -1 | -3..0 | reps | expert-opinion | load_prescription | explicit checks |
| `rircal.b_max` | 3 | 1..5 | reps | expert-opinion | load_prescription | explicit checks |
| `rircal.sq_decay` | 0.8 | 0.5..0.95 | - | expert-opinion | load_prescription | explicit checks |
| `rircal.sq_min` | 0.5 | 0.2..1 | - | expert-opinion | load_prescription | explicit checks |
| `rircal.sq_max` | 3.0 | 2..5 | - | moderate | Steele 2017 SE 2.6-3.4 reps | explicit checks |
| `rircal.sigma_per_rir` | 0.15 | 0..0.5 | per RIR | moderate | Halperin 2022 (error grows with distance from failure) | M2 coverage by RIR bucket |
| `rircal.max_checks_per_week` | 1 | 0..2 | checks | expert-opinion | F14 | Decision |
| `rircal.class_cooldown_days` | 21 | 7..56 | days | expert-opinion | load_prescription | check acceptance (opt-out rate) |
| `rircal.class_min_samples` | 5 | 3..20 | checks | expert-opinion | load_prescription | per-class `b` stability |
| `rircal.eligible_safety_classes` | `["machine", "cable", "isolation"]` | subset of E42 | - | expert-opinion | F14 | Decision |

### `rcm`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `rcm.k0` | `{"upper_compound": 30, "lower_machine_compound": 45, "lower_free_compound": 36, "upper_isolation": 26, "lower_isolation": 32}` | each 12..90 | reps | moderate (upper compound, lower machine), expert-opinion (others) | C3; Nuzzo 2024; Shimano 2006; Marzagao 2026 (weak) | pooled class `k_hat` across the user's exercises (`estimation.rcm`) |
| `rcm.sigma_lnk` | 0.30 | 0.1..0.6 | ln | moderate | C3; Nuzzo 2024 between-person SD | distribution of fitted `k` across exercises |
| `rcm.lookback_days` | 56 | 28..112 | days | expert-opinion | load_prescription | M1 MAE with alternative lookbacks (`:sim`) |
| `rcm.window_days` | 14 | 7..28 | days | expert-opinion | the user's two-point example (B5.2) | M1 with alternative windows |
| `rcm.max_n_eff` | 20 | 12..30 | reps | moderate | Reynolds 2006; Halperin 2022 | M3 bias in the 13-20 bucket |
| `rcm.max_q_rep` | 4 | 2..6 | RIR | expert-opinion | load_prescription | M3 bias by RIR bucket |
| `rcm.min_spread_reps` | 3 | 2..8 | reps | expert-opinion | load_prescription | `sd_lnk` of accepted windows |
| `rcm.min_sets_per_window` | 2 | 2..4 | sets | engineering-fact | identifiability needs two points | not tunable |
| `rcm.huber_delta` | 1.5 | 1..3 | sd | expert-opinion | robust-statistics convention | residual distribution in `estimation.rcm` |
| `rcm.grid_n` | 80 | 40..200 | points | engineering-fact | grid resolution about 2.4 % per step | not tunable |
| `rcm.k_min` | 12 | 8..20 | reps | expert-opinion | load_prescription | fitted `k` near the edge |
| `rcm.k_max` | 90 | 60..120 | reps | expert-opinion | Nuzzo leg press up to 72 | fitted `k` near the edge |
| `rcm.shrink_pseudo` | 2 | 0..10 | windows | expert-opinion | load_prescription | M1 with alternative shrinkage |
| `rcm.rate_limit` | 0.10 | 0.02..0.3 | ratio | expert-opinion | load_prescription | `k` stability (M7) |

### `sse`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `sse.sd_day` | 0.035 | 0.01..0.10 | ln | strong | Grgic 2020 (1RM CV median 4.2 %) | M1, M2 coverage |
| `sse.q_l` | 0.010 | 0.002..0.05 | ln per sqrt(week) | expert-opinion | load_prescription | M2 coverage, innovation variance |
| `sse.q_g` | 0.001 | 0.0001..0.005 | ln per week per sqrt(week) | expert-opinion | load_prescription | trend residual autocorrelation |
| `sse.rho` | 0.85 | 0.5..1.0 | per week | expert-opinion | load_prescription | forecast error at 2-4 weeks |
| `sse.sg0` | 0.005 | 0.001..0.02 | ln per week | expert-opinion | load_prescription | cold-start trend error |
| `sse.grace_days` | 21 | 7..42 | days | moderate | C27; Bosquet 2013 (no meaningful loss up to 28 days) | first-exposure residual after gaps (M1 by gap) |
| `sse.detrain_per_week` | 0.0075 | 0..0.03 | ln per week | expert-opinion | C27 | first-exposure residual after gaps > 21 days |
| `sse.detrain_cap` | 0.1625 | 0.05..0.4 | ln | expert-opinion | C27 (cap 15 %: ln(1/0.85); 2026-10-08) | same |
| `sse.detrain_var_per_week` | 0.0004 | 0..0.004 | ln^2 per week | expert-opinion | load_prescription (0.02^2) | M2 coverage after gaps |
| `sse.sigma_q_base_le12` | 1.0 | 0.3..3 | reps | moderate | Halperin 2022; Refalo 2024 | M2 by rep bucket |
| `sse.sigma_q_base_le20` | 1.5 | 0.5..4 | reps | moderate | Halperin 2022 (worse above 12) | M2 by rep bucket |
| `sse.sigma_q_base_gt20` | 2.5 | 1..6 | reps | moderate | Halperin 2022; Steele 2017 | M2 by rep bucket |
| `sse.form_err_per_rep` | 0.005 | 0..0.02 | ln per rep | moderate | LeSuer 1997; Reynolds 2006 | M3 bias at high reps |
| `sse.form_err_free_reps` | 5 | 1..10 | reps | moderate | Reynolds 2006 (5RM most accurate) | M3 |
| `sse.fatigue_err` | 0.03 | 0..0.1 | ln at F = 0.3 | expert-opinion | load_prescription | residuals by set index |
| `sse.huber_gate_sd` | 3.0 | 2..5 | sd | engineering-fact | robust Kalman convention | M5 Huber rate |
| `sse.max_n_eff` | 25 | 15..40 | reps | expert-opinion | load_prescription | M3 |
| `sse.lb_gate_sd` | 0.5 | 0..2 | sd | expert-opinion | load_prescription | lower-bound usage vs later residuals |
| `sse.lb_r_mult` | 2.0 | 1..5 | - | expert-opinion | load_prescription | same |
| `sse.cold_start_sd` | 0.08 | 0.03..0.2 | ln | expert-opinion | load_prescription | first 3 exposures' residuals |
| `sse.transfer_sd` | 0.15 | 0.05..0.4 | ln | expert-opinion | load_prescription | residuals after substitutions (`v_set_estimates`) |
| `sse.ci_z` | 1.2816 | fixed | z | engineering-fact | 80 % two-sided normal quantile | not tunable |

### `fatigue`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `fatigue.c_ref` | 0.30 | 0.10..0.50 | - | moderate | fitted to Nuzzo's set-by-set decline | nightly fit (`estimation.fatigue`) |
| `fatigue.d` | 0.75 | 0.50..0.90 | - | moderate | same fit | nightly fit after 100 sets |
| `fatigue.gamma` | 2.0 | 1..3 | - | expert-opinion | velocity-loss literature (Sanchez-Medina 2011, from memory) | residuals by RIR bucket (M9) |
| `fatigue.rest_ref_s` | 120 | fixed | s | engineering-fact | normalisation point of `c_ref` | not tunable |
| `fatigue.rest_exp` | -0.6 | -1.2..-0.2 | - | expert-opinion | fit to Willardson 2006 and Senna 2011 | residuals by rest bucket (M9) |
| `fatigue.rest_clamp_lo` | 0.4 | 0.2..0.8 | - | expert-opinion | load_prescription | residuals at long rests |
| `fatigue.rest_clamp_hi` | 1.6 | 1.2..2.5 | - | expert-opinion | load_prescription | residuals at short rests |
| `fatigue.carry_per_set` | 0.04 | 0..0.1 | per set equivalent | expert-opinion | load_prescription | first-set residual vs prior same-muscle work |
| `fatigue.carry_cap` | 0.25 | 0.1..0.5 | - | expert-opinion | load_prescription | same |
| `fatigue.overlap_primary_coeff` | 0.75 | 0.5..1 | coeff | expert-opinion | C9 coefficient levels | same |
| `fatigue.overlap_secondary_coeff` | 0.5 | 0.25..0.75 | coeff | expert-opinion | C9 | same |
| `fatigue.overlap_secondary_weight` | 0.5 | 0..1 | - | expert-opinion | load_prescription | same |
| `fatigue.unilateral_side_weight` | 0.5 | 0.25..1 | - | expert-opinion | C7 (side row = 0.5 set) | same |
| `fatigue.fit_window_exercises` | 60 | 20..200 | instances | expert-opinion | load_prescription | fit stability |
| `fatigue.shrink_pseudo_sets` | 30 | 0..100 | sets | expert-opinion | load_prescription | fit stability |
| `fatigue.c_grid` | `{"min": 0.10, "max": 0.50, "step": 0.02}` | inside `c_ref` bounds | - | engineering-fact | grid definition | not tunable |
| `fatigue.d_grid` | `{"min": 0.50, "max": 0.90, "step": 0.02}` | inside `d` bounds | - | engineering-fact | grid definition | not tunable |
| `fatigue.d_fit_min_sets` | 100 | 50..500 | sets | expert-opinion | load_prescription | fit stability |

### `hold`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `hold.beta` | `{"grip": -1.61, "elbow": -2.21, "shoulder": -1.83, "trunk": -2.27, "knee": -1.88, "ankle": -2.06, "general": -1.98}` | each -4..-0.5 | - | moderate | Frey Law and Avin 2010; the entry is selected by the catalog's `hold_class` (C43), `general` only as the fallback of B8.1 | per-user refit when >= 2 loads within 14 days (`estimation.hold`) |
| `hold.sd_day` | 0.10 | 0.03..0.3 | ln | expert-opinion | load_prescription | hold residual coverage |
| `hold.obs_sd_base` | 0.20 | 0.05..0.5 | ln | expert-opinion | load_prescription | same |
| `hold.obs_sd_timing_s` | 2.0 | 0.5..5 | s | expert-opinion | load_prescription (reaction time) | `hold_timer_s` vs entered result |
| `hold.chip_reserve_s` | `{"max": 0, "hard": 2, "ok": 5, "easy": 10}` | 0..30 | s | expert-opinion | load_prescription HOLD-1; D9; UX 6.2 | hold residuals by chip |
| `hold.reserve_frac` | 0.20 | 0..0.5 | - | expert-opinion | load_prescription | hold prescription error |
| `hold.reserve_min_s` | 2 | 0..10 | s | expert-opinion | load_prescription | same |
| `hold.c_ref` | 0.25 | 0.05..0.5 | - | expert-opinion | load_prescription | hold set-to-set decline |
| `hold.default_t_lo_s` | 20 | 5..120 | s | expert-opinion | this document (general holds such as planks) | user edits of hold targets |
| `hold.default_t_hi_s` | 45 | 10..300 | s | expert-opinion | this document | same |

### `rx`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `rx.z_c` | 0.5 | 0..1.5 | sd | expert-opinion | load_prescription | M4 prescription bias, overshoot share |
| `rx.z_c_sd_floor` | 0.02 | 0..0.05 | ln | expert-opinion | load_prescription | same |
| `rx.z_c_after_too_hard` | 1.0 | 0.5..2 | sd | expert-opinion | load_prescription (REASON-1) | `too_hard` recurrence |
| `rx.z_c_after_too_hard_exposures` | 2 | 1..5 | exposures | expert-opinion | load_prescription | same |
| `rx.window_lo` | 0.35 | 0.2..0.6 | ratio of M | weak (citation from memory, verify per C29) | Lasevicius 2018: below about 30-35 % 1RM inferior | rarely binding; review only |
| `rx.window_hi` | 1.0 | 0.9..1.0 | ratio of M | engineering-fact | cannot exceed the 1RM | not tunable |
| `rx.range_w_dev` | 1 | 0..10 | - | expert-opinion | load_prescription | M4 |
| `rx.range_w_under` | 4 | 0..20 | - | expert-opinion | load_prescription | M4 overshoot share |
| `rx.range_under_tol` | 0.75 | 0..2 | RIR | expert-opinion | load_prescription | M4 |
| `rx.range_w_clamp` | 2 | 0..10 | - | expert-opinion | load_prescription ("reps outside range", defined here as the clamp distance) | share of sets inside the range |
| `rx.range_w_negative` | 100 | 10..1000 | - | engineering-fact | infeasibility penalty | not tunable |
| `rx.fixed_tol` | 0.25 | 0..1 | RIR | expert-opinion | load_prescription | M4 for FIXED schemes |
| `rx.coarse_reps_per_step` | 1.5 | 0.8..3 | reps | expert-opinion | C15 | ladder vs model outcomes (`:sim`) |
| `rx.cap_up_compound` | 0.05 | 0.02..0.10 | ratio | expert-opinion | C15 | first exposure after an increase: success rate (target 70-85 %) |
| `rx.cap_up_isolation` | 0.10 | 0.05..0.20 | ratio | expert-opinion | C15; Gravl about 10 % | same |
| `rx.cap_up_fast` | 0.10 | 0.05..0.20 | ratio | expert-opinion | app_benchmarks A7 fast lane | same |
| `rx.cap_fast_rir_margin` | 2 | 1..4 | RIR | expert-opinion | load_prescription ("RIR >= 4" at `q*` 2) | same |
| `rx.cap_down` | 0.10 | 0.05..0.25 | ratio | expert-opinion | C15 | recovery after reductions |
| `rx.default_hyp_r_lo` | 8 | 3..30 | reps | moderate | Schoenfeld 2021 (wide effective range); load_prescription | user preference adoption, M4 |
| `rx.default_hyp_r_hi` | 12 | 4..40 | reps | moderate | same | same |
| `rx.default_q_hyp_compound` | 2 | 0..4 | RIR | moderate | Robinson 2024; Refalo 2024 | M4, outcomes |
| `rx.default_q_hyp_isolation` | 1 | 0..4 | RIR | moderate | Robinson 2024; Refalo 2023 | same |
| `rx.default_strength_r_star` | 5 | 1..8 | reps | moderate | Schoenfeld 2017; Lopez 2021 (heavier loads for strength) | same |
| `rx.default_q_strength` | 2 | 1..4 | RIR | moderate | Robinson 2024 (strength not RIR-dependent) | same |
| `rx.default_q_maintain` | 2 | 1..4 | RIR | moderate | Spiering 2021 (intensity maintains) | maintain-group trends |
| `rx.bw_r_hi` | 20 | 12..40 | reps | expert-opinion | load_prescription | variant-switch acceptance |
| `rx.bw_switch_exposures` | 2 | 1..5 | exposures | expert-opinion | load_prescription | same |
| `rx.min_q_high_risk` | 1 | 1..3 | RIR | expert-opinion | D10 (safety) | Decision (safety) |
| `rx.double_success_rir_tol` | 0.5 | 0..1.5 | RIR | expert-opinion | app_benchmarks A7 (`RIR_t - 0.5`) | ladder failure rate |
| `rx.double_entry_below` | 4 | 0..6 | reps | expert-opinion | this document (ladder entry below `r_lo`) | first exposure after an advance |
| `rx.double_extension_step` | 2 | 1..4 | reps | expert-opinion | app_benchmarks A7 (Alpha Progression) | same |
| `rx.double_max_extension` | 4 | 0..8 | reps | expert-opinion | app_benchmarks A7 | same |
| `rx.calibration_gap_days` | 21 | 14..60 | days | expert-opinion | C27 (same as the re-entry threshold) | probe residuals vs gap length |
| `rx.probe_rir_add` | 2 | 1..4 | RIR | expert-opinion | app_benchmarks A12 | probe overshoot rate |

### `warmup`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `warmup.p_mid` | 0.60 | 0.4..0.8 | ratio of M | weak | Ribeiro review; load_prescription | first-set residual with vs without warm-up |
| `warmup.p_high` | 0.80 | 0.6..0.95 | ratio of M | weak | same | same |
| `warmup.tier_low` | `{"fracs": [0.50], "reps": [10]}` | fracs 0.3..0.95, reps 1..15 | - | weak / expert-opinion | load_prescription | same; time spent |
| `warmup.tier_mid` | `{"fracs": [0.50, 0.75], "reps": [8, 3]}` | same | - | weak / expert-opinion | load_prescription | same |
| `warmup.tier_high` | `{"fracs": [0.45, 0.65, 0.85], "reps": [6, 3, 1]}` | same | - | weak / expert-opinion | load_prescription | same |
| `warmup.tier_singles` | `{"fracs": [0.92], "reps": [1]}` | same | - | expert-opinion | load_prescription | test-day first-attempt success |
| `warmup.max_cap_frac` | 0.5 | 0.3..0.7 | of capacity | expert-opinion | load_prescription | same |
| `warmup.warm_min_sets` | 2 | 1..5 | sets | expert-opinion | load_prescription | same |
| `warmup.rest_light_s` | 45 | 30..90 | s | expert-opinion | C18 (45-60 s) | rest adherence |
| `warmup.rest_heavy_s` | 60 | 30..120 | s | expert-opinion | C18 | same |
| `warmup.rest_heavy_from_frac` | 0.65 | 0.5..0.9 | ratio | expert-opinion | this document | same |
| `warmup.rest_to_work_s` | 90 | 60..180 | s | expert-opinion | load_prescription REST-1 | first-set residual |

### `rest`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `rest.base` | `{"strength_heavy": 180, "compound_lower": 150, "compound_upper": 120, "isolation": 90, "hold": 90, "core": 60}` | each 30..300 | s | moderate (strength, compounds), weak (hypertrophy relevance, ACSM 2026) | C18; Grgic 2018; Schoenfeld 2016; Singer 2024 | adaptation (B11.4), M10 |
| `rest.strength_heavy_max_reps` | 5 | 3..8 | reps | expert-opinion | C18 | Decision |
| `rest.strength_heavy_min_p` | 0.85 | 0.75..0.95 | ratio of M | expert-opinion | C18 | Decision |
| `rest.mod_hard_s` | 30 | 0..90 | s | expert-opinion | C18 | next-set residual after hard sets |
| `rest.mod_hard_rir` | 0.5 | 0..1.5 | RIR | expert-opinion | C18 | same |
| `rest.mod_short_reps` | 1 | 0..3 | reps | expert-opinion | load_prescription ("reps < target - 1") | same |
| `rest.mod_fatigue_s` | 15 | 0..60 | s | expert-opinion | C18 | same |
| `rest.mod_fatigue_f` | 0.25 | 0.1..0.5 | F | expert-opinion | C18 | same |
| `rest.mod_easy_s` | 15 | 0..60 | s | expert-opinion | C18 | early-start share |
| `rest.mod_easy_rir_margin` | 2 | 1..4 | RIR | expert-opinion | C18 | same |
| `rest.min_s` | 60 | 30..120 | s | moderate | C18; Singer 2024 (benefit above 60 s) | Decision |
| `rest.max_s` | 300 | 180..600 | s | expert-opinion | C18 | Decision |
| `rest.user_min_s` | 30 | fixed | s | engineering-fact | DATA_MODEL `rest_pref_s` domain | not tunable |
| `rest.user_max_s` | 600 | fixed | s | engineering-fact | DATA_MODEL domain | not tunable |
| `rest.timer_hard_max_s` | 900 | fixed | s | engineering-fact | C18 | not tunable |
| `rest.between_sides_s` | 0 | 0..120 | s | expert-opinion | UX 11.7.2 ("switch now") | per-side residuals |
| `rest.side_switch_s` | 10 | 0..30 | s | expert-opinion | exercise_selection TIME-1 | set timestamps |
| `rest.superset_between_s` | 60 | 0..120 | s | expert-opinion | C56; load_prescription REST-1 | superset rep drop |
| `rest.adapt_min_sets` | 10 | 5..30 | sets | expert-opinion | load_prescription | adaptation stability |
| `rest.adapt_early_s` | 20 | 5..60 | s | expert-opinion | load_prescription | same |
| `rest.adapt_early_frac` | 0.8 | 0.5..1 | share | expert-opinion | load_prescription | same |
| `rest.adapt_resid_ok` | -0.5 | -2..0 | reps | expert-opinion | load_prescription | same |
| `rest.adapt_resid_bad` | -1.0 | -3..0 | reps | expert-opinion | load_prescription | same |
| `rest.adapt_step_s` | 15 | 5..30 | s | expert-opinion | load_prescription | same |
| `rest.adapt_floor` | `{"isolation": 60, "hold": 60, "core": 60, "compound_upper": 90, "compound_lower": 120, "strength_heavy": 120}` | each 30..240 | s | expert-opinion | load_prescription | same |
| `rest.adapt_cooldown_days` | 7 | 1..28 | days | expert-opinion | this document | same |

### `reason`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `reason.impute_offset` | 2 | 0..4 | RIR | expert-opinion | load_prescription REASON-1 | residuals of imputed sets |
| `reason.impute_sigma_q` | 2.0 | 1..4 | reps | expert-opinion | load_prescription | same |
| `reason.feeling_bad_m` | 4 | 1..16 | variance multiplier | expert-opinion | load_prescription | mean residual of `feeling_bad` sets |
| `reason.too_tired_m` | 4 | 1..16 | variance multiplier | expert-opinion | C57 (same effect as `feeling_bad`) | mean residual of `too_tired` sets |
| `reason.other_m` | 2.25 | 1..9 | variance multiplier | expert-opinion | load_prescription (sd x 1.5) | residuals of `other` sets |
| `reason.feeling_bad_delta` | -0.05 | -0.10..0 | ln | expert-opinion | load_prescription | acceptance of the offer, residuals after it |
| `reason.too_easy_b_step` | 0.25 | 0..1 | reps | expert-opinion | load_prescription | `too_easy` vs reported RIR disagreement |
| `reason.too_easy_exposures` | 2 | 1..5 | exposures | expert-opinion | load_prescription | same |
| `reason.too_hard_exposures` | 2 | 1..5 | exposures | expert-opinion | load_prescription | same |
| `reason.recovery_events` | 3 | 2..10 | events | expert-opinion | load_prescription | prompt usefulness (answers) |
| `reason.recovery_days` | 14 | 7..28 | days | expert-opinion | load_prescription | same |
| `reason.pain_stop_score` | 4 | 1..10 | score 0-10 | expert-opinion | C64 (stop threshold 4; chips Mild 2 / Moderate 5 / Sharp 8); load_prescription | Decision (C64) |
| `reason.pain_stop_js_min` | 2 | 1..3 | joint-stress level | expert-opinion | D49 (only clear joint stress stops an exercise; light stress 1 stays) | Decision (D49); pain reports versus later tolerance of the exercise (v_wellness_safety) |
| `reason.pain_followup_exposures` | 2 | 1..6 | exposures | expert-opinion | load_prescription | pain reports versus later tolerance of the exercise (v_wellness_safety, v_override_stats) |
| `reason.pain_load_cut` | 0.10 | 0.05..0.3 | ratio | expert-opinion | load_prescription | pain reports versus later tolerance of the exercise (v_wellness_safety, v_override_stats) |
| `reason.pain_min_q` | 3 | 1..5 | RIR | expert-opinion | load_prescription | pain reports versus later tolerance of the exercise (v_wellness_safety, v_override_stats) |
| `reason.pain_repeat_events` | 2 | 2..5 | events | expert-opinion | load_prescription; app_benchmarks (14-day pain flag) | pain reports versus later tolerance of the exercise (v_wellness_safety, v_override_stats) |
| `reason.pain_repeat_days` | 14 | 7..42 | days | expert-opinion | same | pain reports versus later tolerance of the exercise (v_wellness_safety, v_override_stats) |
| `reason.busy_frac` | 0.5 | 0.2..0.9 | share | expert-opinion | load_prescription | substitution acceptance |
| `reason.busy_visits` | 4 | 2..12 | visits | expert-opinion | load_prescription | same |
| `reason.busy_slot_hours` | 1 | 0..3 | h | expert-opinion | this document | same |
| `reason.equipment_missing_events` | 2 | 1..5 | events | expert-opinion | this document | prompt acceptance |
| `reason.equipment_missing_days` | 28 | 7..90 | days | expert-opinion | this document | same |
| `reason.grip_events` | 3 | 2..10 | events | expert-opinion | load_prescription | grip-work adoption |
| `reason.grip_days` | 28 | 7..90 | days | expert-opinion | this document | same |
| `reason.technique_next_cut` | 0.05 | 0..0.1 | ratio | expert-opinion | load_prescription | next-set residual after technique events |

### `autoreg`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `autoreg.trigger_basis` | `"model"` | `model`, `raw_vs_aim` | enum | expert-opinion | C57 (bias-corrected reported RIR vs model-predicted RIR) | Decision (C57); `raw_vs_aim` only in `:sim` counterfactuals |
| `autoreg.first_set_delta` | 2.0 | 1..4 | RIR | expert-opinion | C16; UX UI-06 | acceptance, post-change hit rate (M8) |
| `autoreg.consec_delta` | 1.0 | 0.5..3 | RIR | expert-opinion | C16 | same |
| `autoreg.reps_short` | 2 | 1..5 | reps | expert-opinion | C16 | same |
| `autoreg.max_suggestions_per_exercise` | 1 | 1..3 | cards | expert-opinion | C16 (general items only, C46) | Decision |
| `autoreg.delta_min` | -0.08 | -0.2..0 | ratio | expert-opinion | C16 | M8 |
| `autoreg.delta_max` | 0.06 | 0..0.15 | ratio | expert-opinion | C16 | M8 |
| `autoreg.hysteresis_rir` | 1.5 | 0.5..3 | RIR | expert-opinion | C16 | M8 (changes that were needed vs not) |
| `autoreg.safety_drop_margin` | 1.0 | 0.5..2 | RIR | expert-opinion | load_prescription AUTO-1 | M8 |
| `autoreg.transfer` | 0.5 | 0..1 | - | expert-opinion | load_prescription | residual of later exercises |
| `autoreg.transfer_min_agree` | 2 | 1..4 | exercises | expert-opinion | load_prescription | same |
| `autoreg.raise_max_rel` | 0.05 | 0.01..0.1 | ratio | expert-opinion | load_prescription | M8 |
| `autoreg.raise_max_steps_strength` | 1 | 1..3 | steps | expert-opinion | load_prescription | M8 |
| `autoreg.fatigue_stop_floor` | 0.85 | 0.6..0.95 | ratio | expert-opinion | load_prescription | stop acceptance |
| `autoreg.stop_consec_short` | 2 | 1..4 | sets | expert-opinion | load_prescription | same |
| `autoreg.technique_stop_events` | 2 | 1..4 | events | expert-opinion | load_prescription | same |

### `stall`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `stall.window_days` | 42 | 21..84 | days | expert-opinion | load_prescription | slope after interventions (M11) |
| `stall.min_exposures` | 4 | 3..10 | exposures | expert-opinion | load_prescription | same |
| `stall.slope_stall` | 0.001 | 0..0.005 | ln per week | expert-opinion | load_prescription | same |
| `stall.slope_progress` | 0.003 | 0.001..0.01 | ln per week | expert-opinion | load_prescription | same |
| `stall.p_progress_max` | 0.2 | 0.05..0.5 | probability | expert-opinion | load_prescription | same |
| `stall.slope_regress` | -0.003 | -0.02..0 | ln per week | expert-opinion | load_prescription | same |
| `stall.regress_resid_count` | 3 | 2..4 | of 4 exposures | expert-opinion | load_prescription | same |
| `stall.tier_window_weeks` | 8 | 4..16 | weeks | expert-opinion | load_prescription | same |
| `stall.tier1_sets_mult` | 0.6 | 0.3..0.9 | ratio | expert-opinion | load_prescription | same |
| `stall.tier1_load_cut` | 0.10 | 0..0.2 | ratio | expert-opinion | C13 (among deload-type transforms only tier 1 cuts load) | same |
| `stall.tier1_q_add` | 1 | 0..3 | RIR | expert-opinion | load_prescription | same |
| `stall.tier2_rep_shift` | 4 | 2..8 | reps | expert-opinion | load_prescription | same |
| `stall.tier2_weeks` | 5 | 4..6 | weeks | expert-opinion | load_prescription (4-6 weeks), aligned to F12 | same |
| `stall.fail_threshold` | 2 | 2..4 | exposures | expert-opinion | C15 (A7; Stronglifts uses 3) | success after the cut |
| `stall.fail_load_cut` | 0.075 | 0.025..0.15 | ratio | expert-opinion | C15 (between Stronglifts/Dr Muscle 10 % and microloading) | same |
| `stall.global_frac` | 0.40 | 0.2..0.8 | share | expert-opinion | load_prescription | deload outcomes (rebound after 2 weeks) |
| `stall.global_min_active` | 5 | 3..15 | exercises | expert-opinion | this document | same |
| `stall.global_active_window_days` | 28 | 14..56 | days | expert-opinion | this document | same |
| `stall.global_window_days` | 14 | 7..28 | days | expert-opinion | load_prescription | same |
| `stall.global_delta` | -0.04 | -0.1..0 | ln | expert-opinion | load_prescription | same |
| `stall.global_delta_sessions` | 3 | 2..4 | of 4 sessions | expert-opinion | load_prescription | same |
| `stall.global_bad_events` | 3 | 2..10 | events | expert-opinion | load_prescription | same |

### `warnings`

| Key | Default | Bounds | Unit | Evidence | Source | Tuned by |
|-----|---------|--------|------|----------|--------|----------|
| `warnings.v1_info_lt` | 10 | fixed by C14 | sets/week | moderate | C14; ACSM 2026; Pelland 2024 | Decision |
| `warnings.v1_warn_lt` | 6 | fixed by C14 | sets/week | moderate | C14 | Decision |
| `warnings.v1_strong_lt` | 3 | fixed by C14 | sets/week | moderate | C14 | Decision |
| `warnings.v2_no_sets_days` | 10 | 7..21 | days | moderate | load_prescription R4; Spiering 2021 | maintain-group trends |
| `warnings.v2_intensity_days` | 14 | 7..28 | days | moderate | planning W5 | same |
| `warnings.v2_intensity_p` | 0.85 | 0.7..0.95 | ratio | moderate | planning W5; Spiering 2021 | same |
| `warnings.v2_intensity_rir` | 3 | 1..5 | RIR | moderate | planning W5 | same |
| `warnings.v3_info_gt` | 11 | fixed by C14 | sets/session | weak | C14; Remmert preprint | Decision |
| `warnings.v3_warn_gt` | 15 | fixed by C14 | sets/session | weak | C14 | Decision |
| `warnings.v5_min_freq` | 2 | 1..3 | exposures/week | moderate | planning W4; ACSM 2026 | outcomes by frequency |
| `warnings.v5_min_target` | 10 | 6..20 | sets/week | moderate | planning W4 | same |
| `warnings.p1_warn_cap` | 30 | 20..40 | reps | moderate | load_prescription R1 | outcomes of high-rep schemes |
| `warnings.p1_strong_cap` | 40 | 30..60 | reps | moderate | load_prescription R1 | same |
| `warnings.p2_warn_rir` | 4 | 3..5 | RIR | moderate | load_prescription R2 | same |
| `warnings.p2_strong_rir` | 5 | 4..6 | RIR | moderate | load_prescription R2 | same |
| `warnings.p4_reps` | 12 | 8..20 | reps | moderate | planning W7; Halperin 2022 | M3 |
| `warnings.p5_warn_rlo` | 6 | 4..8 | reps | moderate | load_prescription R6 | strength outcomes |
| `warnings.p5_strong_rlo` | 10 | 8..15 | reps | moderate | load_prescription R6 | same |
| `warnings.p6_compound_min_s` | 60 | 30..90 | s | moderate | load_prescription R7; Singer 2024 | same |
| `warnings.p6_strength_min_s` | 90 | 60..180 | s | moderate | load_prescription R7; Grgic 2018 | same |
| `warnings.p7_min_width` | 2 | 1..4 | reps | engineering-fact | load_prescription R8 | not tunable |
| `warnings.p8_warn_ratio` | 0.85 | 0.7..0.95 | ratio | expert-opinion | load_prescription WARN-1 | warned vs unwarned outcomes |
| `warnings.p8_strong_ratio` | 0.70 | 0.5..0.85 | ratio | expert-opinion | load_prescription WARN-1 | same |
| `warnings.p8_time_tol` | 0.10 | 0.05..0.3 | ratio | expert-opinion | load_prescription WARN-1 | same |
| `warnings.f_load` | `{"cap_1": 30, "cap_2": 40, "w_1": 1.0, "w_2": 0.85, "w_3": 0.60}` | weights 0..1 | - | expert-opinion | ordinal fit to Lopez 2021, Lasevicius 2018 (from memory) | same |
| `warnings.f_int` | `{"p_1": 0.85, "p_2": 0.80, "p_3": 0.70, "p_4": 0.60, "w_1": 1.0, "w_2": 0.95, "w_3": 0.85, "w_4": 0.70, "w_5": 0.50}` | weights 0..1 | - | expert-opinion | Schoenfeld 2017; Lopez 2021 | same |
| `warnings.strength_tau_sets` | 1.5 | 0.5..4 | sets | weak | Remmert preprint (about 2 direct sets per session) | same |
| `warnings.compare_hyp_ranges` | `{"r_lo": [5, 6, 8, 10, 12, 15], "r_hi": [8, 10, 12, 15, 20, 25]}` | reps 1..40 | reps | expert-opinion | Schoenfeld 2021 range | not tunable by data (design list) |
| `warnings.compare_strength_reps` | `[2, 3, 4, 5, 6]` | 1..8 | reps | expert-opinion | Schoenfeld 2017 | same |
| `warnings.compare_q` | `[1, 2, 3]` | 0..5 | RIR | expert-opinion | Robinson 2024 | same |
| `warnings.l1_max_consecutive` | 4 | 3..7 | days | weak | planning W8; Bell 2023 | outcomes after dense weeks |

Constants that this document reads from other modules: `setcredit.stim_rir` = `[1.00, 1.00, 0.95, 0.90, 0.80,
0.50]` for RIR 0-5 and 0 above (C6); `targets.mrv` (MRV caps per group); TIME-1 tempo and set overhead
(C19).

## C3. OAP-relevant worked examples

All examples use BW 70.7 kg, the two-point fit `M` = 109.94 kg and `k` = 30.63 (B5.2) unless stated, and are
golden fixtures in Part D.

### C3.1 6 x 2 at +27.5 kg (session B load) selected by the model

FIXED `r* = 2`, `q* = 2`, rest 180 s (`c` = 0.2352), feasibility `min RIR >= 1.75`. Candidate added loads
from the fixture belt inventory (1.25 kg steps):

| Added kg | `w_sys` | `cap_fresh` | Predicted RIR sets 1-6 | Min | Feasible |
|----------|---------|-------------|------------------------|-----|----------|
| +25.00 | 95.70 | 5.56 | 3.56, 3.39, 3.26, 3.15, 3.07, 3.01 | 3.01 | yes |
| +26.25 | 96.95 | 5.10 | 3.10, 2.92, 2.77, 2.66, 2.57, 2.50 | 2.50 | yes |
| **+27.50** | 98.20 | 4.66 | 2.66, 2.46, 2.30, 2.17, 2.07, 1.98 | 1.98 | **yes (heaviest)** |
| +28.75 | 99.45 | 4.23 | 2.23, 2.01, 1.83, 1.68, 1.56, 1.47 | 1.47 | no |

The model reproduces the challenge's +27.5 kg when it uses the posterior mean (`z_c * sd = 0`). As a
shadow of the RULE item it would run with the SSE-1 uncertainty: with sd 3 % and `z_c` = 0.5,
`M_use = 109.94 * e^-0.015 = 108.30` kg and the heaviest feasible load is **+26.25 kg** (RIR 2.59 ... 1.90;
+27.5 drops to 1.37). The shadow log will therefore show the model 1.25 kg more conservative than the
challenge, which is expected and is what the review compares.

### C3.2 4 x 5 at +20 kg (session C anchor) per-set RIR

`w_sys` 90.70, rest 150 s (`c` = 0.2624), `cap_fresh` = 7.50:

| Set | `F` | Capacity | Predicted RIR |
|-----|-----|----------|---------------|
| 1 | 0.000 | 7.50 | 2.50 |
| 2 | 0.117 | 6.62 | 1.62 |
| 3 | 0.220 | 5.85 | 0.85 |
| 4 | 0.314 | 5.14 | 0.14 |

This is the artifact's description of the anchor ("the last two sets were just made"). The live readout
after set 1 is 4.0 / 4.3 / 4.5 / 4.6 / 4.7 reps at RIR 2 after 60 / 90 / 120 / 150 / 180 s. A shadow MODEL
with FIXED 5 and `q*` 2 would choose +16.25 kg (posterior mean) or +15 kg (`z_c` 0.5, sd 3 %), because the
challenge deliberately takes the last sets close to failure; the same holds for session A 4 x 4 at +22.5
(predicted RIR 2.50, 1.92, 1.43, 1.01; shadow +20 kg). These divergences are expected and are logged, not
"fixed" (C15 RULE mode).

### C3.3 Leg press 3 x 8-10 (general add-on example)

Calibration exposure 120 kg x 12 at reported RIR 3 (`b` 0.5): `n_eff` = 15.5, `k0` = 45 (lower machine
compound), `M` = 120 * (1 + 14.5/45) = 158.67 kg. RANGE 8-10, `q*` 2, rest 150 s, 5 kg stack, cap +5 % from
`w_last` 120 (125 allowed, +4.2 %):

| Load | Reps sets 1-3 | Predicted RIR | `J` |
|------|---------------|---------------|-----|
| 120 | 10, 10, 10 | 5.50, 3.81, 2.33 | 41.62 |
| **125** | **10, 9, 8** | **3.12, 2.12, 1.71** | **3.35** |
| 130 | 9, 8, 8 | 1.92, 0.98, -0.41 | 128.13 (negative RIR) |

Choice: **125 kg x 10, 9, 8** with the posterior mean. The research gave `J` = 1.37 for 125 kg because it
omitted the clamp term (set 1's ideal 11 reps is clamped to 10); the choice is the same. As a true cold start
(`P_LL = 0.08^2`, `z_c` 0.5) `M_use` = 152.45 and the choice is **120 kg x 10, 9, 8** (RIR 3.17, 2.17, 1.77,
`J` 3.44). In this week's deload (F8: 2 sets, load kept, RIR + 2) the add-on becomes **125 kg x 9, 7**
(RIR 4.12, 4.50).

### C3.4 Warm-up before +27.5 kg

`p_w` = 98.20 / 109.94 = 0.893 (high tier): **BW x 6, +12.5 kg x 1**, then the working sets (B10). In OAP
session B the challenge's own warm-up block applies instead (C34, UX 11.5).

### C3.5 Assisted pin step = coarse step -> DOUBLE / RULE

One 5 lb pin step at BW 70.7 with the C2 offset:

| Step | `w_sys` before -> after | Relative change | Reps per step (`k` 30.6, `n` 5) | Mode |
|------|-------------------------|-----------------|------------------------------------|------|
| 50 -> 45 lb (session C pin) | 41.52 -> 43.79 | +5.46 % | 1.89 | coarse |
| 40 -> 35 lb (session A pin) | 46.06 -> 48.32 | +4.92 % | 1.70 | coarse |
| 35 -> 30 lb (90-degree hold pin) | 48.32 -> 50.59 | +4.69 % | 1.63 | coarse |

Every step exceeds `rx.coarse_reps_per_step` = 1.5, so a general assisted exercise runs in DOUBLE (C15),
and the OAP assisted items run in RULE mode with the model's DOUBLE proposal in shadow. The research
figure "about 1.5 reps per step" (4.3 %) omitted the 6.5 kg offset of C2; with the offset the step is
coarser, which strengthens the conclusion. At these steps the general cap table would also be exceeded by
the 50 -> 45 lb step (+5.46 % > 5 %); for RULE items this is reported as WE-S2 at WARN severity and
logged, never clamped (D13, C45; GF-WE-05). A general assisted exercise in DOUBLE may make this one-step
advance even above the cap, because it is the smallest loadable step; for a `high_risk` exercise (one-arm
pulls) only when the ladder start at the new pin is at least `r_lo` (C45, B9.14).

### C3.6 Gate e1RM range (D7 display)

3RM +32.5 kg at BW 70.7, first gate after cutover (C59: no historical set rows in RCM-1, class prior
`k` 30, `sd_lnk` 0.30): **+39.38 kg** (80 % interval +37.18 to +42.61, matching CHALLENGE_ENGINE GF-CE-02);
x1.10 rule -> +42.82 kg (shown next to it, D7). Fitted-curve cases for comparison (B5.2): `k_used` 30.02
from the forced one-window pair -> +39.38 kg (+37.84 to +41.36); the 30-08 test window as the only
informative window -> +39.09 kg (+37.58 to +41.04).

---

<a id="part-d-golden-fixtures-tests-and-review-metrics"></a>
# Part D. Golden fixtures, tests and review metrics

## D1. Fixture context: the real week 2026-W41

All golden fixtures of this document share one context, built from the user's real week (critique,
contradiction 35). Values marked "assumption" are fixture choices that must be replaced by the user's real
data at cutover; they do not change any algorithm.

| Item | Value |
|------|-------|
| Zone | Europe/Amsterdam (F18); offset +02:00 (CEST) until 2026-10-25 03:00, then +01:00 (CET) |
| `now` ("today") | 2026-10-07T08:00:00Z = Wednesday 2026-10-07 10:00 local; ISO week 2026-W41 |
| Calendar | C Tuesday 2026-10-06 19:45 local (17:45Z), done; A Thursday 2026-10-08 19:45 (17:45Z); TEST Sunday 2026-10-11 13:00 (11:00Z) |
| Challenge state | OAP, block 1, week 5 = deload / test week (contract `isDeloadOrTestWeek = true`); mesocycle (C31) index 1, week 5, deload |
| Bodyweight | weigh-in 70.70 kg on 2026-10-04 (date: assumption); test-day raw weigh-in 70.90 kg on 2026-10-11 (assumption) |
| Weighted pull-up loads | C 20 kg, A 22.5 kg, B 27.5 kg added; weighted negatives 47.5 kg added |
| Assisted pins | A 40 lb, C 50 lb, 90-degree hold 35 lb; machine calibration `{unit lb, step 5, min 0, max null, offset_kg 6.5, ratio 1.0}` |
| Belt inventory | 20 x 2, 10 x 1, 5 x 1, 2.5 x 1, 1.25 x 2; `max_items` 4, `max_kg` 60 (assumption; F7 checklist) |
| Estimator state | weighted pull-up: `M` 109.94 kg, `k` 30.63 (B5.2), sd 3 % unless stated, `g` 0.30 % per week, `b` 0.5, `s_q` 1.0 (module-test assumption; at the real cutover no historical set rows are imported, C59, so RCM-1 starts at `k0` 30 and SSE-1 cold-starts from the first logged session) |
| DST check | 2026-10-25 (Sunday of 2026-W43) |

Fixture files live in `fixtures/golden/algo/<id>.json` (ANDROID.md 4) with the shape
`{id, module, description, input: EngineInput, params: "default-1.0.0" + overrides, expected: {outputs,
outputs_jcs_sha256}}`; `-PupdateGoldens` rewrites expected values, diffs are reviewed in git, and the ART
self-test compares hashes on the phone (ANDROID.md 6.3 item 4). Numbers below are on their grids.

## D2. Fixture catalogue

### `clock`

| Id | Input | Expected |
|----|-------|----------|
| GF-CLK-01 | `isoWeek` of 2026-10-05, -06, -07, -08, -11 / 2026-10-12 / 2026-10-25 / 2026-10-26 / 2026-12-31 / 2027-01-03 / 2027-01-04 | 2026-W41 (all five) / 2026-W42 / 2026-W43 / 2026-W44 / 2026-W53 / 2026-W53 / 2027-W01 |
| GF-CLK-02 | `localDate` and offset of 2026-10-06T17:45Z and 2026-10-11T11:00Z | 2026-10-06 19:45 (+120 min); 2026-10-11 13:00 (+120 min) |
| GF-CLK-03 | `elapsedHours` C -> A, A -> TEST, `now` -> A | 48.0; 65.25; 33.75 |
| GF-CLK-04 | Saturday 2026-10-24 19:45 local (17:45Z) -> Sunday 2026-10-25 19:45 local (18:45Z) | 25.0 h; offset after the change +60 min |
| GF-CLK-05 | `atLocal(2026-10-25, 02:30)` (ambiguous) | 2026-10-25T00:30Z (earlier offset, +02:00) |
| GF-CLK-06 | `atLocal(2027-03-28, 02:30)` (spring gap) | 2027-03-28T01:30Z (shifted forward to 03:30 CEST) |
| GF-CLK-07 | `mesocycle(2026-10-07)` with the OAP contract block 1 week 5 | `Meso(index 1, week 5, isDeloadWeek true, source challenge)` |
| GF-CLK-08 | `calendarDays(2026-10-04, 2026-10-18)` and `(2026-10-04, 2026-10-19)` | 14 (not stale), 15 (stale) |

### `load` and `bodyweight`

| Id | Input | Expected |
|----|-------|----------|
| GF-LOAD-01 | `w_sys` of C, A, B, negatives at BW 70.7 | 90.70, 93.20, 98.20, 118.20 (negatives: eccentric, never in SSE-1) |
| GF-LOAD-02 | `assistKg` and `w_sys` for pins 50, 45, 40, 35, 30, 0 lb | assist 29.18, 26.91, 24.64, 22.38, 20.11, 6.50; `w_sys` 41.52, 43.79, 46.06, 48.32, 50.59, 64.20 |
| GF-LOAD-03 | belt loadables of the fixture inventory | 42 values: 0 to 37.5 in 1.25 steps (31 values), 40, 41.25, 42.5, 43.75, 45, 46.25, 47.5, 50, 51.25, 52.5, 55 |
| GF-LOAD-04 | plate breakdowns 47.5, 27.5, 22.5, 26.25 | [20, 20, 5, 2.5]; [20, 5, 2.5]; [20, 2.5]; [20, 5, 1.25] |
| GF-LOAD-05 | `nearest(belt, 22.17)`, `nearest(belt, 26.51)`, `easierOrEqual(belt, 48.5)` | 22.5; 26.25 (the research's 27.5 assumed 2.5 kg steps); 47.5 |
| GF-LOAD-06 | pin target exactly between 40 lb (46.06) and 35 lb (48.32): `w` 47.19 | pin 40 (tie -> more assistance, C2) |
| GF-LOAD-07 | `Grid.kg(45.245)`, `Grid.stat(2.5e-5)`, `Canon.esNumber` of 100.00, 2.5e-7 | 45.24; 2.5e-5; `100`; `2.5e-7` |
| GF-BW-01 | weigh-in 70.7 on 2026-10-04; sessions on 2026-10-08, 2026-10-19; test 2026-10-11 with raw 70.9 | 70.70 (not stale); stale on 2026-10-19; test snapshot 70.90 `test_weighin`; EWMA afterwards 70.76 |

### Estimation

| Id | Input | Expected |
|----|-------|----------|
| GF-RCM-01 | closed form BW x 18 (70.7) and 3RM +32.5 (103.2); and with +2 % strength on the second date | `k` 30.63, `M` 109.94, +39.24 kg; `k` 32.80 |
| GF-RCM-02 | RCM-1 fit with the two points forced into one window, `k_prev = k0 = 30` | `k_hat` 30.06, `sd_lnk` 0.198, `k_used` 30.02; e1RM of 3RM +32.5 = +39.38 (80 %: +37.84 .. +41.36) |
| GF-RCM-03 | fitting procedure on the 30-08 test window alone (3RM +26 at BW 71.0 and BW x 18 at 71.0; not the cutover state, C59) | two-point `k` 38.96; `k_hat` 34.14, `sd_lnk` 0.202, `k_used` 31.32; 3RM +32.5 -> +39.09 (80 %: +37.58 .. +41.04) |
| GF-RCM-04 | sets 20 days apart only (different windows) | `m = 0`, `k_used = k0` (or the pooled class value) |
| GF-RCM-05 | cutover state (C59): no set rows; gate input for a 3RM of +32.5 at BW 70.7 | `k_used` 30, `sd_lnk` 0.30; +39.38 (80 %: +37.18 .. +42.61), equal to CHALLENGE_ENGINE GF-CE-02 |
| GF-RIR-01 | explicit check `r*` 12, `q_pred` 2, extra 4, `b` 0.5, `s_q` 1.0 | `b` 0.80, `s_q` 1.118 |
| GF-RIR-02 | two checks in one ISO week across 2026-10-25 | the second is not scheduled |
| GF-FAT-01 | `c(rest)` for 60/90/120/150/180/240/300 s; to-failure sets at 120 s | 0.455/0.357/0.300/0.262/0.235/0.198/0.173; 1.00, 0.70, 0.565, 0.504, 0.477, 0.465 |
| GF-FAT-02 | 4 x 5 at +20, rest 150 | RIR 2.50, 1.62, 0.85, 0.14; `F` 0, 0.117, 0.220, 0.314; readout after set 1: 3.98 / 4.50 / 4.62 / 4.71 reps at 60 / 120 / 150 / 180 s |
| GF-SSE-01 | B6.6 session (4 x 5 at +22.5, RIR 2/1/1/0, one week after the prior) | `M_pred` 110.27 (sd 3.20 %); posterior 111.55 (+40.85; 80 % +37.34 .. +44.48), `g` 0.28 %/week, `K` (0.391, 0.0081), no Huber |
| GF-SSE-02 | detraining: the same prior, next session 49 days later | after the 21-day grace `M` 110.93; final `M_pred` 107.66 (107.655 before KG rounding), sd 5.85 %, `g` 0 |
| GF-SSE-03 | eligibility: negatives 47.5 x 2, one-arm hang, finger-assisted set, a set with `n_eff` 26 | all excluded, each with its trace code |
| GF-SSE-04 | lower bound: one "5+" set above `L + 0.5 sd` and one below | the first raises `L`, the second has no effect |
| GF-HOLD-01 | 90-degree hold pin 35: 8 s + "Hard"; then pin 30 | `T` 10.0 s at `w_ref` 48.32; `t_cap(50.59)` 9.04 s; `t_target` 7.04 -> step feasible for 6-8 s |
| GF-HOLD-02 | one-arm dead hang 10 s + "OK", range 10-15 s | observation 15 s; `t*` 12 s |

### Prescription

| Id | Input | Expected |
|----|-------|----------|
| GF-RX-01 | 6 x 2, `q*` 2, rest 180, `M` 109.94, `z_c * sd = 0` | +27.5 kg; RIR 2.66 .. 1.98 (C3.1 table) |
| GF-RX-02 | same with sd 3 %, `z_c` 0.5 | `M_use` 108.30; +26.25 kg (min RIR 1.90) |
| GF-RX-03 | leg press 3 x 8-10, `M` 158.67, `k` 45, `w_last` 120, posterior mean | 125 kg x 10, 9, 8; RIR 3.12, 2.12, 1.71; `J` 3.35 (MODEL itself: B9.4's coarse-step check puts a 5 kg step at 125 kg, 2.2 reps, in DOUBLE, GF-RX-06; `M` unrounded, 120 x (1 + 14.5 / 45), because at 158.67 set 1's ideal at 120 kg crosses a rounding edge) |
| GF-RX-04 | same as a cold start (`P_LL` 0.0064, `z_c` 0.5) | 120 kg x 10, 9, 8; `J` 3.44 |
| GF-RX-05 | leg press in the W41 deload (2 sets, load kept, RIR + 2) | 125 kg x 9, 7 (RIR 4.12, 4.50) |
| GF-RX-06 | coarse-step check for pins 50 -> 45, 40 -> 35, 35 -> 30; the leg press of GF-RX-03 | 1.89, 1.70, 1.63 reps per step -> `double` (general); the leg press's 5 kg step at 125 kg is 2.2 reps -> `double` |
| GF-RX-07 | DOUBLE ladder, `r_hi` 8, achieved 8-7-6 at target effort, repeated | targets 8-8-6, 8-8-7, 8-8-8, then the next loadable |
| GF-RX-08 | lateral raise 12-15 at 8 kg per hand, 15/15/15 then 17/17/17 | first: extension to 17 (start at 10 kg would be 7/5/4); second: advance to 10 kg with targets 9/6/5 |
| GF-RX-09 | RULE item A: weighted pull-up 4 x 4 at +22.5, rest 180 (deload set count as materialised by the challenge) | plan copied exactly; predicted RIR 2.50, 1.92 (2 sets) or 2.50, 1.92, 1.43, 1.01 (4 sets); shadow MODEL +20 kg (mean) / +18.75 kg (`z_c` 0.5, sd 3 %) |
| GF-RX-10 | high-risk exercise with a user preference `q* = 0` | `q*` clamped to 1, WE-S1 SAFETY `enforced = true` |
| GF-RX-11 | fail counter: two consecutive failed valid exposures at 60 kg (barbell) | next load <= 55.5 -> 55 kg; counter reset; ladder restart |
| GF-WU-01 | warm-up before +27.5 (`p_w` 0.893) | BW x 6, +12.5 x 1; rests 45 after the first, 90 before work (the last warm-up set always takes `warmup.rest_to_work_s`) |
| GF-REST-01 | C weighted pull-up (challenge 150 s); 6 x 2 general; leg press after RIR 3 and after RIR 0 | 150; 180 (strength_heavy); 150; 195 |
| GF-REST-02 | alternating one-arm pair, per-side timer 120 s, second side 5 reps | between sides 0 s; after the pair `max(60, 120 - (5 x 3 + 5) - 10)` = 90 s |
| GF-REASON-01 | one set per reason code | estimator class and session/future effects of the B12.3 table |
| GF-AUTO-01 | 6 x 2 at +27.5, set 1 reported RIR 4, `P_LL` 0.0016 | `delta_ex` +1.73 %; next-set RIR 3.14; no card (model basis; Keep under raw basis) |
| GF-AUTO-02 | leg press 125 kg plan 10/9/8, set 1: 10 reps chip "5+", `P_LL` 0.0016 | trigger (a), `Delta_1` 2.38; `eps` +4.08 %, `K_s` 0.228, `delta_ex` +0.93 %; at 125 kg set 2 predicted RIR 3.16 (< 1.5 from `q*` -> no load change); card: reps 10, 8 for sets 2-3 (predicted RIR 2.16, 2.38) |
| GF-STALL-01 | 5 valid exposures, `g` 0.0005 per week, `sd_g` 0.002 | `p_prog` 0.106 < 0.2 -> STALL, tier 1 on the next exposure |
| GF-STALL-02 | 4 active exercises, 2 stalled | global rule off (fewer than 5 active) |
| GF-WE-01 | improve group, 2 x 12 at RIR 2, twice a week | WE-V1 WARN (`x` 4, magnitude 0.60, suggestion +22 %) |
| GF-WE-02 | strength-goal weighted pull-up 2 x 12 at RIR 2 | WE-P8 ratio 0.85 (no warning); WE-P5 STRONG |
| GF-WE-03 | chest 12 fractional sets in one session | WE-V3 INFO ("preliminary evidence") |
| GF-WE-04 | W41: the user adds a gym session on Saturday 2026-10-10 (day before TEST) | WE-S4 SAFETY `rule_override`; never produced by the planner |
| GF-WE-05 | RULE item: session C assisted one-arm pin 50 -> 45 lb (`w_sys` 41.52 -> 43.79, compound cap 5 %) | plan keeps pin 45 (no clamp); `w_cap` = 41.52 x 1.05 = 43.60; WE-S2 **WARN**, `enforced = false`, magnitude 0.0044 (`(43.79 - 43.60) / 43.60`); `caps_applied[]` type `rule_excess` with `limit_w` 43.60, `unclamped_w` 43.79 (D13, C45) |
| GF-WE-06 | test day: weighted pull-up 3RM recorded with RIR 0 (`set_kind = test`) | no WE-S1 notice (C63, OI-A22); the same RIR 0 on a working set -> WE-S1 SAFETY notice |
| GF-WE-07 | HOLD-1 item: plank plate raised from 10 to 20 kg between exposures (+100 %) | no WE-S2 evaluation (no warning, no `caps_applied[]` entry); hold progression rules only (C70) |

## D3. End-to-end scenario 2026-W41 (`GoldenWeekW41Test`)

Run the pipeline on the D1 context at `now` = Wednesday 10:00 and assert, for the modules of this
document:

1. `clock`: week 2026-W41; mesocycle week 5, deload; A is 33.75 h ahead; the rest day before TEST is
   Saturday 2026-10-10 (the contract's `restDayBefore` scope `all_gym` is evaluated by the planner).
2. `bodyweight`: snapshot 70.70 `ewma`, not stale.
3. Session A on Thursday (RULE items): every challenge item copied exactly; RX-1 shadow and predicted RIR as
   GF-RX-09; rest timers equal the challenge values (150 s assisted one-arm, 180 s weighted pull-up,
   90 s holds); no AUTO-1 card for RULE items. If the scripted first pair triggers the session A pin
   calibration rule, the change is auto-applied to the remaining rows with Undo (D14, C46; its rule
   behaviour is tested in CHALLENGE_ENGINE), and RX-1's shadow and predicted RIR follow the re-planned rows
   (B9.11).
4. A general session on Wednesday (a gap day, allowed by D15 and C50; one gym visit per day) or an add-on
   block after A (if the planner proposes either, D5): every general prescription is a deload prescription
   (sets x 0.5, load kept, RIR + 2), for example GF-RX-05.
5. Nothing in the plan violates a SAFETY rule; adding a Saturday session yields WE-S4.
6. Test day Sunday: the BW snapshot is the raw weigh-in (70.90). The gate reads `userK` from the engine
   facts captured before the test session's L8 run (CHALLENGE_ENGINE 3.9). In this scenario RCM-1 is in
   the C59 cutover state (no informative window yet), so `userK` is the class prior 30 (GF-RCM-05); the
   `k` 30.63 of D1 is only the module-test value of the prescription fixtures. L8 then runs RCM-1: the 3RM
   and BW max-reps sets form the first informative window (`k_prev` 30, rate limit +/- 10 %), which the
   next gate uses.
7. Replaying every snapshot of the scenario reproduces identical JCS bytes (R1), and the hash list equals
   the stored one (R2 on the phone).

A second scenario `GoldenWeekW43DstTest` runs Saturday 2026-10-24 to Monday 2026-10-26: fatigue and SSE-1
predictions across the 25-hour Sunday use elapsed hours; ISO weeks W43/W44; the RIRCAL weekly limit is
per ISO week.

## D4. Invariants (property tests and `:sim` personas)

| Invariant | Test |
|-----------|------|
| Same input bytes -> same output bytes | `DoubleRunTest`, R2 self-test |
| Output independent of input list order after canonicalisation | `ShuffleInvarianceTest` |
| Every prescribed load is in the loadable set of its equipment | property test over random equipment schemes |
| MODEL/DOUBLE load changes per exposure stay within the cap table (except the one-step DOUBLE exemption, C45) | property test + `:sim` |
| A RULE-mode load jump above the generic cap is never clamped and always carries a WE-S2 WARN and a `rule_excess` log entry (D13, C45) | property test |
| `no_time`, `occupied`, `equipment_missing`, `grip_failure` never count as failures and never advance a ladder | `ReasonSemanticsTest` |
| No high-risk prescription with target RIR < 1 | property test |
| No NaN / infinity leaves a module | property test with extreme inputs (BW 20/300, pins at max, `n_eff` 0.5) |
| Eccentric, isometric, hang and finger-assisted sets never change SSE-1 | `SseEligibilityTest` |
| Detraining is continuous at 21 days and capped at 0.15 ln | `SsePredictTest` |
| Deload exposures never change ladders, fail counters or stall counts | `RxDeloadTest`, `StallTest` |

## D5. Review metrics

The review (milestone M5, first after 4-6 weeks of logs) reads only engine-logged values from
`recommendations` (`estimation.sse`, `rx.prescription`, `autoreg.suggestion`, `estimation.rircal`,
`estimation.rcm`, `estimation.fatigue`, `stall.weekly`, `warnings.evaluation`) joined with `set_logs` and
`session_events`; SQL never recomputes e1RM (C3). `:sim replay` recomputes counterfactuals.

| Id | Metric | Definition | Target / action |
|----|--------|------------|-----------------|
| M1 | One-step-ahead reps MAE | For each session and exercise (and side) with a prior state: `err = n_eff_obs - n_eff_pred` of the **first** working set (`n_eff_pred` from the prior state, B6.5). MAE overall, by curve class, by gap length | MAE <= 1.5 reps (app_benchmarks target). Above: inspect M3, refit `k` priors, `sd_day`, `q_l` |
| M2 | 80 % interval coverage | Share of first-set observations with `z` inside `[z_pred_lo, z_pred_hi]` (B6.5) | 0.75-0.85. Below 0.70: intervals too narrow (raise `q_l`, `sd_day`, `sigma_q`); above 0.90: too wide |
| M3 | Bias by rep bucket | Mean `err` in buckets of `n_eff_pred`: 1-5, 6-8, 9-12, 13-20, > 20; also by RIR bucket (`q_rep` 0-1, 2-3, 4+), by reason class, by curve class | abs(bias) <= 0.5 rep. A trend across rep buckets means `k` is off (RCM-1); a trend across RIR buckets means the RIR bias depends on distance from failure (RIRCAL-1 per-`n` bias) |
| M4 | Prescription error | Per working set: `q_true - rir_pred` (from `rx.prescription.rir_pred[]` joined to `set_logs`). Mean, MAE, share of sets inside the prescribed rep range, share with `q_true < q* - 1` (overshoot), share with `q_true > q* + 2` (too easy), rate of caps applied | mean 0 +/- 0.5; MAE <= 1.0; inside range >= 80 %; overshoot <= 10 %. Persistent positive mean: `z_c` too high or `b` too high |
| M5 | Robustness usage | Huber trigger rate per update; lower-bound updates applied; imputed RIR share | Huber < 5 %; imputation rare under D9 |
| M6 | RIR calibration | `b` and `s_q` trajectories, number of explicit checks, implicit vs explicit `e` agreement, `e` against `n` | `b` stable within +/- 0.3 over 4 checks |
| M7 | Curve stability | Per exercise: `abs(ln k_used - ln k_prev)` per fit, `m`, `sd_lnk`, share of fits at the rate limit | rate-limited fits < 20 % |
| M8 | Autoregulation quality | Cards per exercise-session, acceptance rate, implicit Keep rate, hit rate (predicted RIR of the next set within +/- 1 of the observed after Apply vs Keep) | acceptance > 50 %; hit rate after Apply >= hit rate after Keep |
| M9 | Fatigue fit | Residual `n_eff_obs - n_eff_pred` for sets 2+ by rest bucket and by set index (from `estimation.fatigue`) | abs(mean) <= 0.5 rep per bucket |
| M10 | Rest adherence | `rest_before_s / (rest_timer_planned_s + rest_adjust_s)` (DATA_MODEL 9.6.4 column names, C55), early-start share, skip share (`rest_end_reason`), alarm latency (C28) | informational; feeds REST-1 adaptation |
| M11 | Stall interventions | Time from stall event to action; slope 4 weeks after tier 1 / tier 2 / fast path; deload rebound 2 weeks after | slope after > slope before |
| M12 | Warnings | Shown, dismissed, "use suggested", "use mine" rates per rule; outcome (e1RM slope, weekly sets) for warned vs unwarned schemes | rules with > 80 % dismissal and no outcome difference are candidates for INFO or removal |

**Review keys (C58).** The snapshot outputs of this document's modules include every key that M1-M12 read,
so review SQL never recomputes an engine value. DATA_MODEL 10.2 is the registry of these keys; the names
below are the ones the modules emit, and DATA_MODEL 10.2 lists them unchanged (OI-A26).

| kind | Review keys in `outputs` (in addition to those DATA_MODEL 10.2 already lists) | Used by |
|------|-------------------------------------------------------------------------------|---------|
| `estimation.sse` | `sets[]: {n_eff_pred, z_pred_lo, z_pred_hi}`; `states[]: {l_prior, p_ll_prior, huber_applied, lb_applied}` (B6.5) | M1, M2, M3, M5 |
| `estimation.rcm` | `k_prev`, `k_hat`, `k_used`, `sd_lnk`, `m`, `n_sets`, `rate_limited`, `pooled`, `j_min`, `j_k0`; `sets[]: {set_id, w_sys, n_eff, fatigue_f, n_fresh, window, residual}` (B5.1) | M7, M3 |
| `estimation.rircal` | `type`, `class`, `n_c` (B4.6) | M6 |
| `estimation.fatigue` | `c_hat`, `c_used`, `d_hat`, `d_used`, `m`, `resid_by_rest_bucket`, `resid_by_rir_bucket`, `resid_by_set_index` (B7.5) | M9 |
| `estimation.hold` | `sets[]: {set_id, t_done, s_res, fatigue_f, w_sys, z, sigma, estimator_class}`; `states[]: {exercise_id, side, hold_class, t_s, ci80_lo_s, ci80_hi_s, g_per_week, beta, w_ref_kg}` (B8.4) | hold trends, Part C `hold.*` tuning |
| `rx.prescription` | `rir_pred[]`, `scheme: {kind, r_lo, r_hi, r_star, q_star}`, `probe`, `caps_applied[].type` including `double_one_step` and `rule_excess` (B9.14, B9.15) | M4 |
| `autoreg.suggestion` | `trigger_rule`, `deltas[]`, `delta_ex`, `kind`, `rir_next_cur`, `rir_next_new` (B13.6) | M8 |
| `stall.weekly` | `exercises[]: {exercise_id, side, status, g, sd_g, p_prog, n_exposures, event, tier_action}`, `fail_counters[]`, `global: {triggers[], recommended, planned_deload_within_7_days}` (B14.3) | M11 |
| `warmup.sets` | `tier`, `p_w`, `sets[]: {load_point, reps, rest_s}` (B10) | warm-up review (B10) |
| `rest.adapt` | `rest_class`, `base_old_s`, `base_new_s`, `early_share`, `resid_mean`, `n_sets` (B11.4) | M10 |
| `warnings.evaluation` | `warnings[]: {rule_id, severity, entity, magnitude, evidence_grade, enforced}` (B15.6) | M12 |

**Review procedure.** (1) Export the review bundle (UX "Export review bundle"). (2) Run R1 replay over all
snapshots (any mismatch stops the review). (3) Compute M1-M12. (4) For candidate changes run `:sim replay`
with the candidate params or module version and compare M1-M4 on the same data. (5) Propose the changes as
one list, each with its numbers and reason; after the user approves the list as a batch (F26), write
`user_algo_params` rows with `valid_from` (A8.6) or ship a new module version (A7). (6) Record the review in
`docs/reviews/<date>.md` and reference it in `review_ref`.

---

<a id="open-issues"></a>
# Open issues

Items where DECISIONS.md is silent, where two documents disagree, or where this document had to choose an
interim behaviour. None of them is decided silently: each states the interim behaviour used in this spec
and who has to decide. After the DECISIONS update of 2026-10-07 (D13-D16, F21-F27, C37-C65), resolved rows
are kept for traceability and marked **Resolved by ...** in the last column; the text body of this document
already follows the resolution.

| Id | Issue | Interim behaviour in this document | Needs |
|----|-------|------------------------------------|-------|
| OI-A01 | My assignment covers the Part B modules only. The owner document for `calendar.classify`, `safety`, the selection modules (`catalog` ... `injury`) and the offline `tuner` and `sim` was not stated when this was written. | ALGORITHMS_PLANNING.md now exists and specifies `calendar.classify` (section 1), `safety` (section 2), the selection modules (sections 10-18), the `ui.*` functions (section 19, C53) and `tuner`, `sim`, `replay` (section 20); CHALLENGE_ENGINE.md owns the `challenge.*` modules. Module map (0.3) updated. | **Resolved** (owner documents exist; C53 for `ui.*`). |
| OI-A02 | Rule P13 (no `Double` to text inside `:algo` except `Canon`) and P10-P16 are not in ANDROID.md's `AlgoPurityTest` pattern table. | Stated here; enforced by `GoldenFixtureTest` hashes until the scanner has a pattern (e.g. string templates with a `Double` receiver cannot be caught by regex reliably, so the hash tests are the real guard). ANDROID.md 6.3 now scans for the line-detectable parts of P11, P13 and P16 (P16 relies on the `impl` sub-package convention); P10, P12, P14, P15 and the type-dependent parts stay with the A11 tests. | **Resolved** (ANDROID.md 6.3). |
| OI-A03 | C22 defines grids for kg, s and ratio only. Statistical state (variances such as `P_gg` = 2.5e-5) would be destroyed by the 1e-4 ratio grid, and continuous rep values need their own grid. | Two added grids: REP 0.01 and STAT 12 significant digits (A3.2). | **Resolved by C42.** |
| OI-A04 | Bundled params path: ANDROID.md 4 / 7.6 use `algo/src/main/resources/fitness/params/`, DATA_MODEL 9.1.10 names `algo/src/main/resources/params/default-params-1.0.0.json`. | This document uses `algo/src/main/resources/fitness/params/default-params-1.0.0.json`. | **Resolved by C40** (DATA_MODEL 9.1.10 and the 13.2 migration-005 row both use this path). |
| OI-A05 | `gym_equipment.load_scheme` of type `stack` with `unit = level` has no kg-per-level field, so `w_sys` is undefined for level stacks. | With `level_kg` the stack is a normal load scale; without it, ordinal: RX-1 DOUBLE on the level, SSE-1 skips it (B2.4). | **Resolved by C43** (DATA_MODEL 9.3.3 stack shape has `level_kg`). |
| OI-A06 | The challenge research's `roundPin(x, 'nearest') = round_half_down` breaks ties toward **less** assistance; C2 says ties go to **more** assistance. | LOAD-1 rounding (B2.5) is the only implementation; FitExpr's `roundPin` / `roundLoad` call it. | **Resolved by C2** (CHALLENGE_ENGINE 3.8 now breaks `nearest` ties toward more assistance). |
| OI-A07 | HOLD-1 needs a hold class per exercise (grip, elbow, shoulder, trunk, ...). The catalog has no such attribute; by pattern the OAP straight-arm hold (`skill_static`) would get the elbow exponent instead of the shoulder one. | The catalog attribute `hold_class` (grip, elbow, shoulder, trunk, knee, ankle) selects the exponent; the pattern rule is only a fallback (B8.1). | **Resolved by C43** (attribute instead of tags; DATA_MODEL E55 and `exercises.hold_class`). |
| OI-A08 | Which historical OAP data the cutover import feeds into RCM-1 / SSE-1 (only the test days, or also the artifact's session logs). The D7 gate value depends on it (`k_used` 30.0 vs 31.3 gives +39.4 vs +39.1 kg from the same 3RM). | No historical set rows reach RCM-1; the gate uses the class prior `k` 30 until an informative window exists (B5.1, GF-RCM-05). | **Resolved by C59.** |
| OI-A09 | **Conflict between D10 / pipeline precedence and D8.** D10 lists load-jump caps among the always-enforced safety rules and the precedence is safety > challenge, but several OAP rules executed "exactly as written" (D8, tag A) exceed the general caps: C pin -5 lb at pin 50 = +5.46 % (cap 5 %), the A rule "-10 lb when the calibration negative is controlled" = about +9.8 %, and test recalculations of up to 5 kg added (+5.5 % of `w_sys` at the C load of 90.7 kg). Enforcing the caps would freeze or alter challenge progressions. | Caps are **not** applied to RULE-mode challenge loads; an excess raises WE-S2 at WARN severity and is logged (`rule_excess`; B9.11, B15.2, GF-WE-05). General prescriptions are capped as specified. | **Resolved by D13 / C45.** |
| OI-A10 | The data model has no explicit per-exercise goal kind (strength vs hypertrophy); RX-1 needs one for defaults, WE-P5 and rest classes. | `strength` for RULE-mode challenge items and for exercises with an exercise-scope preference `rep_scheme = fixed` and `rep_max <= 6`; otherwise `hypertrophy` (B9.3). | Open. User / DATA_MODEL owner: confirm or add a column to `rep_preferences`. |
| OI-A11 | Calibration probe (C27): after the probe set, the remaining sets of that exercise are planned from the probe (B9.8). This fills an undetermined plan; it is not a change of an existing plan, so it is not presented as an AUTO-1 suggestion. | As described; the derived plan is shown as the prefill with "from your first set". | **Resolved by C57.** |
| OI-A12 | DOUBLE mode advances by exactly one loadable step even when that step exceeds the up-cap (for example dumbbells 8 -> 10 kg = +25 %); otherwise coarse equipment could never progress. | Exempt, logged as `caps_applied[]` type `double_one_step`; for `high_risk` exercises only when the ladder start at the new load is >= `r_lo` (B9.14). | **Resolved by C45.** |
| OI-A13 | Superset rest between members: the research REST-1 says 60 s, ORD-1's time model uses a 15 s transfer. C18 makes REST-1 the single rest source and C19 makes TIME-1 the single duration model, but neither fixes this number. | REST-1 `rest.superset_between_s` = 60 s; TIME-1 and ORD-1 take rest values from REST-1 (B11.2). | **Resolved by C56.** |
| OI-A14 | `too_tired` is one of the 13 reason codes but has no semantics in the research (REASON-1 predates it). | Same estimator, session and recovery-counter effects as `feeling_bad` (B12.3), separate variance key `reason.too_tired_m`. | **Resolved by C57.** |
| OI-A15 | C16's trigger "first set abs(Delta RIR) >= 2" does not say against what. | `Delta = q_true - rir_pred` (model basis, `autoreg.trigger_basis = "model"`); "reported minus aim" survives only for `:sim` counterfactuals; the UI copy shows reported vs aim (UX 11.10). | **Resolved by C57.** |
| OI-A16 | Review metrics M1-M3 need the one-step-ahead prediction per set; DATA_MODEL 10.2 does not list `n_eff_pred`, `z_pred_lo/hi`, `l_prior`, `p_ll_prior`. | Keys of `estimation.sse` (B6.5, D5 review-key table). | **Resolved by C58** (DATA_MODEL 10.2 lists them; see OI-A26 for the other modules' keys). |
| OI-A17 | STALL-1 tier actions change sets, reps or RIR; D10 says user preferences are kept with a warning. | Tier actions are applied directly only to engine-owned fields; for user-owned fields they are WE-T1 suggestions, applied with a warning only when the user chose `auto_adjust_with_warning` (B9.9, B15.4). The tier-1 load cut is applied (load is engine-owned). | **Resolved by C57.** |
| OI-A18 | Fixture assumptions: belt inventory, weigh-in date 2026-10-04 and test weigh-in 70.9 kg are not the user's real data. | Used only in Part D fixtures. The estimator state of D1 (`k` 30.63, `M` 109.94) is likewise a module-test value; the real cutover state follows C59 (GF-RCM-05). | Open. Replace with checklist data at M1 / cutover (no algorithm change). |
| OI-A19 | New expert constants that no research lens supplied: `hold.default_t_lo_s` / `_hi_s` (20 / 45 s), `load.pin_max_unknown_native` (100 lb), `stall.global_min_active` (5), `rx.double_entry_below` (4), `rest.adapt_cooldown_days` (7), `reason.equipment_missing_*`, `reason.busy_slot_hours`. | Marked expert-opinion and tunable in Part C. | Open (informational). Review after data. |
| OI-A20 | Challenge items with `progression: delegate:rx` (CHALLENGE_ENGINE 2.4.7 and 6.6; OAP: the B row and the dips, OAP-OI-05) are challenge items whose load RX-1 owns. C15 and C45/C46 speak only of RULE-mode items and general items. | Treated as general items for load: RX-1 `model` / `double` with the definition's sets, reps and RIR target as the scheme; the cap table applies (they are not RULE loads, D13 does not exempt them); AUTO-1 cards with the C16 limit; the first exposure is the B9.8 probe selected by `bootstrap`. STALL-1 evaluates them, but a tier action on the definition-owned sets, reps or RIR is only a WE-T1 suggestion (as for user-owned fields, C57). Challenge rules still read their raw RIR (C17). | **Resolved by D49** (confirmed as described; also closes OAP-OI-05). |
| OI-A21 | Week-close timing. This document's general week-close tick was Monday 00:00; CHALLENGE_ENGINE 4.3 places `week.closed` / `week.started` at Monday `challenge.enrollment.week_close_local_time` (default 04:00) so that late Sunday sessions count for their week. DATA_MODEL 19.8 (DST fixture) shows `week.closed:2026-W43` at 2026-10-25T23:00Z (Monday 00:00 CET), while CHALLENGE_ENGINE GF-CE-12 has 2026-10-26T03:00Z (04:00 CET). | The general tick (STALL-1 weekly and global, `targets` deload decision, `weekplan`) runs after the challenge's boundary event at the same local time (A12.3). | **Resolved by DATA_MODEL 19.8 and ALGORITHMS_PLANNING OI-23** (19.8 now shows 2026-10-26T03:00Z; ALGORITHMS_PLANNING 5.4.1 runs `targets` on the same tick, `weekplan` replans on its own triggers). |
| OI-A22 | WE-S1 (no failure on high-risk lifts) raises a SAFETY notice for a logged RIR 0, but C63 stores a test-day 3RM with RIR 0 by protocol, so every test day would raise a notice. | Test-day attempts entered with "Record" (`set_kind` `test` or `attempt`) produce no WE-S1 notice; RIR 0 on a working set of a high-risk lift still does (B15.2, GF-WE-06). | User: confirm. |
| OI-A23 | C63 adds an "Add a set" action; DOUBLE ladders and the fail counter compare logged sets with planned targets. | Added sets are estimator observations (SSE-1, RCM-1, RIRCAL-1, FAT-1 like any working set), but the ladder and the fail counter look only at the planned sets (B9.6). | User / UX owner: confirm. |
| OI-A24 | Event names used here that the DATA_MODEL 9.8.1 registry (canonical, C39) does not list: `params_rejected` (A8.2) and `params_override_rejected` (A8.4). The former `equipment_busy` and `wrong_weight_available` were replaced by registry events (B12.3, Part C). | Kept as `config`-category events. | **Resolved by C39** (the DATA_MODEL 9.8.1 registry lists both). |
| OI-A25 | Pain stop rule (C64: Mild 2 / Moderate 5 / Sharp 8, stop threshold 4). ALGORITHMS_PLANNING 2.2 now stores the chips as these scores, maps them to its severities 1 / 2 / 3 for the joint flags, and leaves the stop threshold to REASON-1. What "an exercise loading that joint" means for the same-day stop was never defined. | REASON-1 applies the stop (B12.3): every remaining exercise of today with catalog joint-stress key `js_<joint> >= 1` for a joint key of the reported body region (`body_regions.joint_keys`) is removed for the rest of the day. | **Resolved by D49**: `js_<joint>` >= `reason.pain_stop_js_min` (2); light stress (1) does not stop an exercise. |
| OI-A26 | C58 says the review output keys of `hold`, `stall`, `rcm`, `fatigue`, `autoreg`, `warmup`, `rest` (and `sse`) are listed in DATA_MODEL 10.2; at the time of this revision 10.2 lists, for this document's modules, only the `estimation.sse` base keys, `rx.prescription` and `estimation.rircal`. Some output keys were renamed to lower snake_case in this revision (`J_min` -> `j_min`, `F` -> `fatigue_f`, `T_s` -> `t_s`, `Delta[]` -> `deltas[]`). | The D5 review-key table is the list this document's modules emit. | **Resolved by C58** (DATA_MODEL 10.2 now lists these keys). |

---

<a id="implementation-notes-for-claude-code"></a>
# Implementation notes for Claude Code

**Packages** (`algo/src/main/kotlin/dev/fitnessapp/algo/`, package prefix `dev.fitnessapp.algo`, one package per registry module, ANDROID.md 27.1, C68):

| Package | Contents |
|---------|----------|
| `core/det` | `Det` (StrictMath wrappers, `cmpScore`, `TIE_EPS`), `Grid`, `Canon` (A3.4), `Jcs` (RFC 8785 writer over `JsonElement`), `Sha256Hex`, `Erfc` (B14) |
| `core/params` | `ParamSpec`, `ParamView` (read tracking), `ParamsValidator`, `ParamsResolver` (A8.4; pure, receives rows) |
| `core/trace` | `DecisionTrace`, `TraceStep`, `ReasonChip`, `Warning`, `Severity`, `EvidenceGrade`, `EngineError` |
| (package root) | `EngineVersion.kt`: `dev.fitnessapp.algo.EngineVersion` (`ENGINE_VERSION = "1.0.0"`, A7, C68) |
| `core/registry` | `ModuleRef`, `ModuleRegistry` (all 51 ids, versions, param ownership) |
| `core/calendar` | the one shared calendar marker render/parse implementation (`dev.fitnessapp.algo.core.calendar`, C68); no caller keeps its own copy |
| `core/clock` | `Clock` interface (adapters only), `ClockSnapshot`, `Week` (B1) |
| `core/load` | `LoadModel`, `AssistCalibration`, `Loadables`, `Rounding`, `PlateBreakdown` (B2) |
| `core/bodyweight` | `Bodyweight` (B3) |
| `estimation/rircal`, `estimation/rcm`, `estimation/sse`, `estimation/fatigue`, `estimation/hold`, `estimation/stall` | B4-B8, B14 (as built in M3.1: the shared set and fact types and the snapshot writers in the `estimation` package root, the trend filter shared by SSE-1 and HOLD-1 in `estimation/sse/Trend.kt`) |
| `prescription/rx`, `prescription/warmup`, `prescription/rest`, `prescription/reason`, `prescription/autoreg`, `prescription/warnings` | B9-B13, B15 |
| `pipeline` | pure stage helpers that assemble module calls in the A12 order (no IO) |

**Resources.** Under `algo/src/main/resources/fitness/` (params, catalog, presets, challenges; C68).
`algo/src/main/resources/fitness/params/default-params-1.0.0.json` is generated from Part C
(one source table; a small generator test asserts the JSON and this document's keys agree:
`ParamsDocSyncTest` greps the Part C tables).

**Build order (milestone M3).** `core/det` + JCS conformance -> params -> clock -> load -> bodyweight ->
fatigue -> rcm -> rircal -> sse -> hold -> reason -> rx -> warmup -> rest -> autoreg -> stall -> warnings.
Each step lands with its golden fixtures from Part D before the next starts. RULE mode needs only
pass-through plus shadow, so M2 (challenge engine) can ship before M3 using `rx` in RULE-only form.
D21 moves `load` and `bodyweight` (complete) and the base policy of `rest` (warm-up rests, challenge values,
user preference, class table with the strength-heavy rule, clamps; registered as `rest@1.0.0`) into M1a; the
remaining `rest` parts ship in M3 as `rest@1.1.0`. D49 groups the remaining order into the M3 steps: M3.1 the
estimators (`fatigue` to `hold`), M3.3 the prescription engine (`reason`, `rx`, `warmup`, `rest`, `stall`), M3.5
`autoreg` and `warnings`; `reason.pain_stop_js_min` joins the params file with M3.3.

**Test classes** (`algo/src/test/kotlin/dev/fitnessapp/algo/...`): `CanonNumberTest`, `JcsConformanceTest`,
`GridRoundingTest`, `DoubleRunTest`, `ShuffleInvarianceTest`, `ParamsCoverageTest`, `ParamsDocSyncTest`,
`ClockDstTest`, `IsoWeekTest`, `MesocycleTest`, `LoadModelTest`, `LoadablesTest`, `RoundingDirectionTest`,
`PlateBreakdownTest`, `CalibrationRebuildTest`, `BodyweightTest`, `RirCalTest`, `RcmFitTest`,
`RcmFunctionsTest`, `SseUpdateTest`, `SsePredictTest`, `SseClassesTest`, `SseColdStartTest`,
`SseEligibilityTest`, `FatigueTest`, `FatigueFitTest`, `HoldTest`, `RxModelTest`, `RxCapsTest`,
`RxDoubleLadderTest`, `RxFailCounterTest`, `RxProbeTest`, `RxRuleShadowTest`, `RxDeloadTest`,
`RxUnilateralTest`, `RxSchemeResolutionTest`, `RxBodyweightVariantTest`, `RxDelegatedItemTest`, `WarmupTest`, `RestPolicyTest`,
`RestAdaptTest`, `ReasonSemanticsTest`, `AutoregTest`, `StallTest`, `WarningEngineTest`,
`GoldenFixtureTest` (runs every `fixtures/golden/algo/*.json`), `GoldenWeekW41Test`,
`GoldenWeekW43DstTest`, `ReplayDeterminismTest` (once exports exist). `AlgoPurityTest` belongs to
ANDROID.md 6.3.

**Practical rules.**

- Write the value classes and `Grid` first; make every `@Serializable` output field a grid value class, so
  nothing un-rounded can be serialised. As built from M1 on, the modules keep plain `Double`s rounded with `Grid`
  and write outputs through `JsonNumbers.grid` (the estimators of M3.1 included, `estimation/EstimationJson.kt`);
  no value classes exist.
- Keep each module's internal functions in an `impl` sub-package of its package; other modules import only
  the public API, which is what the P16 pattern of `AlgoPurityTest` checks (ANDROID.md 6.3 and 27.1).
- Snapshot output keys are lower snake_case and must match the D5 review-key table and DATA_MODEL 10.2
  (C58); a renamed key is a breaking change for the review views.
- Never call `kotlin.math.ln/exp/pow`; use `Det`. Never format a `Double` with a template; use `Canon`.
- Every module returns `Outcome<ModuleResult<O>>`; the gateway (`:data` `engine/EngineGateway.kt`) writes
  the snapshot. Modules never know about Room or Supabase.
- Keep worked-example numbers of this document as fixtures, not as comments; when a constant changes, the
  fixtures change in the same commit and the diff is reviewed.
- When something here is ambiguous, check the Open issues table first; do not invent a new constant
  without adding it to Part C with evidence `expert-opinion` and a tuning source.
