# Challenge engine specification

Version of this document: 1.2 (2026-10-07; aligned with the user decisions D13-D16, the defaults
F21-F27, the cross-document resolutions C37-C65 and the DATA_MODEL 9.8.1 event registry). Engine
modules specified here all start at `@1.0.0`.

This document specifies the generic, data-driven challenge engine: the Challenge Definition Schema
(CDS v1), the FitExpr rule language, the event-sourced enrollment lifecycle, the staged
event-condition-action (ECA) rule engine, the session materializer, the challenge week planner, the
integration contract with the general training engine, calendar matching and write-back, the
behaviour of the Challenges tab, and the safety and stall hooks. It contains no one-arm pull-up
(OAP) logic; OAP appears only as an example. The OAP program itself is described for the user in
`docs/OAP_CHALLENGE.md` and encoded in `docs/challenges/oap.challenge.v1.json`.

Binding inputs: `docs/DECISIONS.md` (D1-D16, F1-F27, C1-C65) is the single source of truth and is
cited by id throughout ("per C21"). Sibling specifications that this document must agree with:
`docs/DATA_MODEL.md` (tables, event types, reason codes, metric codes, region taxonomy; its section 8
value domains are canonical for every stored code, C38, and its 9.8.1 registry for telemetry event
names, C39), `docs/ANDROID.md` (calendar provider executor, marker edit policy in 16.7, Room schema in
section 8, permissions; C37, C40), `docs/ALGORITHMS.md` (LOAD-1 rounding, RX-1 caps, warnings),
`docs/ALGORITHMS_PLANNING.md` (TIME-1 and add-on capacity, `today`, `weekplan`, all `ui.*` functions;
C19, C49, C53), `docs/UX.md` (Challenges screens, Week tab, test-day wizard). Research inputs:
`docs/research/challenge_engine.md` (primary), `planning.md` (ChallengeConstraints.v1),
`app_benchmarks.md` (A13), `data_model.md` (S5), `critique.md`.

---

## 0. How to read this document

### 0.1 Audience

1. The user, who wants to know what the app will and will not do with a challenge.
2. Future Claude Code sessions that implement the engine in the `:algo` module and that periodically
   review challenge rules against logged data. Every rule, constant and threshold is therefore
   written down with its default value, bounds, evidence grade and source.

### 0.2 Conventions

| Convention | Meaning |
|---|---|
| `challenge.eca@1.0.0` | Module id and semantic version, registered in `fitness.algorithm_versions` (C30). |
| `challenge.plan.busy_buffer_min` | Engine constant key in the versioned params JSON (`<module>.<constant>` with the registry module id and a lower snake_case constant name, C41; DATA_MODEL E53). Engine constants live in the params JSON, never in code. |
| `p.gate1GapKg` | A parameter declared inside a challenge definition (definition data, versioned with the definition). |
| Tag **A** | Rule or value taken from the user's existing program (artifact or Todoist task descriptions); executed exactly as written unless the user approves the P proposal that replaces it (D8, C48). |
| Tag **P** | Proposal; active only after the user approved it individually (D8). Undecided proposals are inactive. |
| Tag **D** | Fixed by DECISIONS.md (user decision or canonical resolution). |
| Tag **E** | Engineering default needed to execute a rule deterministically; does not change training content. |
| Evidence grades | `strong`, `moderate`, `weak`, `expert-opinion`, `engineering-fact`. |
| Tunable | A constant that is an assumption. Every tunable constant names the logged data that tunes it. Tuning happens offline in `tuner` (ModelTuner) and lands in `user_algo_params` (after the user approves the review's change list as a batch, F26) or in a new definition version after review; never silently on the device. |
| Times | `Instant` for elapsed hours, `LocalDate` in the profile zone (Europe/Amsterdam, F18) for days and ISO weeks, via the single `clock` module (C31). |
| Numbers | FitExpr uses decimal arithmetic (C22). Values crossing module boundaries are rounded to the canonical grid: kg 0.01, s 0.1, ratio 1e-4, reps 0.01 (C42), pin 5 lb. |
| Stored codes | Every stored code (load modes, set kinds, sides, set flags, patterns, laterality, grips, reason codes, metric codes) is a DATA_MODEL section 8 value (C38), for example the set flag `grip_slip_before_last`. Definition JSON field names stay camelCase (`loadMode`); their values are these codes. |

### 0.3 Glossary

| Term | Definition |
|---|---|
| Definition | One immutable CDS v1 JSON document (`slug@version`), for example `oap@1.0.0`. |
| Enrollment | One user's participation in one definition version. Its state is a deterministic fold over its input events. |
| Slot | A recurring session role in a week (OAP: `C`, `A`, `B`, `TEST`). A template is the concrete content of a slot in a phase. |
| Block | A fixed number of weeks (OAP: 5) ending in a deload/test week. |
| Mode | The enrollment's training mode: `normal`, `deload`, `test_pending`, `extra_deload`, `injury` (the fixed set of DATA_MODEL enum E26; a definition configures their transforms but cannot add modes in v1). |
| Item | One exercise entry inside a template, addressed by its `key` (for example `wpu`, `oaa`). |
| Proposal | A P-tagged change (one or more rules, params, templates) approved or declined as a unit. |
| Contract | The `ChallengeContract` the challenge layer exports to the general engine each week (reserved sessions, blocked days, protected windows, owned muscles, coverage, add-on slots). |
| Marker | The last description line `[fitapp:v1 <uuid> <slotKey>]` the app writes into its own calendar events (D11, C37); `<uuid>` is the enrollment id for challenge events. |

---

## 1. Purpose, scope and position

### 1.1 What the challenge layer does

A challenge is a program with a goal, written entirely as data. The engine reads the definition and
the user's logged facts and produces, deterministically:

1. The enrollment state: status, phase, block, week, mode, state variables (loads, pins, hold
   targets, streak counters, flags) and per-block snapshots.
2. Materialized challenge sessions: sets, reps, loads, pins, hold times, rest, side order, flags to
   offer, in-session rules.
3. A week placement of the required challenge sessions under hard constraints and preferences.
4. A `ChallengeContract` that tells the general engine which days, muscles and time windows the
   challenge owns, and how much add-on room exists.
5. Calendar matching of existing events to challenge slots, and an automatic write plan for the
   app's own marked events (D11).
6. An audit row for every rule whose condition was true or errored, and plain-language explanations.

It does **not**: estimate strength (that is `rcm`, `sse`), count volume (that is `setcredit`), decide
general sessions (`allocator`, `today`, `weekplan`), select general exercises (`select`), or decide
safety state (`safety`). It reads those modules' outputs through narrow, snapshotted interfaces.

### 1.2 Module registry (challenge group)

All ids start at `1.0.0` and live in the pure Kotlin JVM module `:algo` (no `android.*`, no system
clock, no randomness, no IO; D1).

| Module id | Responsibility | Main inputs | Main outputs |
|---|---|---|---|
| `challenge.cds` | Load, validate and compile a definition (structural + semantic validation, template resolution, expression compilation). | Definition JSON, catalog, metric defs, region taxonomy | `CompiledDefinition` or a list of `ValidationError` |
| `challenge.fitexpr` | Parse, type-check and evaluate FitExpr expressions in decimal arithmetic. | Expression strings, typed context | Typed values or `EvalError` |
| `challenge.enrollment` | Event-sourced enrollment: fold, tick synthesis, week/block/phase bookkeeping, pause/resume/abandon, overrides, approvals, migrations. | `challenge_events`, compiled definition | `EnrollmentState`, derived events |
| `challenge.eca` | Staged snapshot evaluation of definition rules for one event; locks; first-writer-wins; bounded cascade; audit rows. | One event, pre-state, compiled rules | State diff, notifications, suggestions, audit rows |
| `challenge.materialize` | Turn template x phase x mode x state into a concrete `SessionPlan`; run in-session rules; compute `valid(itemKey)`. | State, definition, slot, date, equipment, preferences | `SessionPlan`, in-session rule applications (auto-applied with Undo, D14) |
| `challenge.plan` | Place this ISO week's required challenge sessions on days and times; answer whether today is a challenge day. | State, matched calendar anchors, busy blocks, done sessions | `ChallengeWeekPlan`, `ChallengeTodayAdvice` |
| `challenge.contract` | Build the `ChallengeContract` for the general engine (C11, C12, C34, C10). | Week plan, materialized sessions, `setcredit`, `time` | `ChallengeContract` |
| `challenge.calendar` | Match classified calendar events to slots (C20); build the write plan for the app's own events (D11, C36). | Output of `calendar.classify`, week plan, `calendar_link` rows | `SlotMatches`, `CalendarWritePlan`, adopt and rename prompts |

Core modules this group calls (specified elsewhere): `clock`, `load`, `bodyweight`,
`calendar.classify`, `safety`; `setcredit`; `rcm`; `time`; `select` (add-on blocks are built by the
general selector, C34); `warnings`.

### 1.3 Position in the pipeline and precedence

Precedence is **safety > challenge > user hard constraints > general**. The pipeline order is
L0 normalize -> L1 safety -> **L2 challenge** -> L3 accounting/estimation -> L4 targets ->
L5 planning -> L6 session build -> L7 in-session -> L8 post-session.

```
L0  clock, load, bodyweight, calendar.classify      (normalized facts, typed calendar anchors)
L1  safety (PainEvent -> SafetyState)                (joint flags, forbidden tags, challenge mode hints)
L2  challenge.enrollment + challenge.eca            (state after every input event)
    challenge.materialize                           (challenge sessions of this and next ISO week)
    challenge.plan                                  (week placement, today advice)
    challenge.calendar                              (slot matches, write plan)
    challenge.contract                              -> ChallengeContract
L3  setcredit, readiness, rircal, rcm, sse, ...     (rcm/sse read logged challenge sets too)
L4  targets (owned muscles = covered; C31 mesocycle week = challenge block week)
L5  allocator, today, weekplan (read the contract; never re-derive challenge rules)
L6  session build: pinned challenge block first, then general/add-on block (C34)
L7  in-session: REASON-1 semantics -> challenge inSessionRules -> UI-06/AUTO-1 (non-challenge items)
L8  post-session: session.completed -> challenge.eca; estimators update; replan; snapshot; sync
```

Rules for interaction:

1. `safety` runs first on every event (C33). A `PainEvent` that matches a definition's stop signals is
   forwarded to the enrollment as a `pain.reported` event; the definition's safety-stage rules then
   set the challenge mode. The safety module never needs to know challenge rules.
2. The challenge layer may forbid things the general engine would do (protected windows, blocked
   days, forbidden tags). The general engine may never change challenge prescriptions.
3. User hard constraints (for example a `never` preference) rank below the challenge: a challenge
   item that the user rated `never` is still materialized, with a conflict warning (section 6.7),
   because the challenge is something the user enabled deliberately. A user who disagrees pauses or
   abandons the challenge, or declines the P rule involved.
4. Within the challenge, the definition's stage order (safety < data < structural < progression)
   decides conflicts (section 5).
5. The user's approved challenge rules win over the generic load-jump caps (D13, C45): the caps of
   RX-1 (ALGORITHMS B9.14) never clamp a challenge RULE-mode load; a jump above the generic cap raises
   the non-blocking warning WE-S2 at WARN severity and is logged (section 6.6). All other safety rules
   still bind challenges: the pain protocol, no failure on high-risk lifts, and the protected windows.
6. Challenge in-session rules are applied automatically with a visible Undo (D14, C46, section 6.10);
   general (non-challenge) in-session changes stay one-tap suggestions (D10, C16).

### 1.4 Determinism rules

| Rule | Detail |
|---|---|
| No ambient time | Every entry point receives `now: Instant` and the profile `ZoneId` from the caller; `clock` derives `LocalDate`, ISO week and elapsed hours (C31). |
| No randomness | Ties are broken by explicit keys (ids, list order, lexicographic dates). |
| Decimal rules | FitExpr evaluates in `BigDecimal` with `MathContext(34, HALF_EVEN)` (C22). |
| Canonical grid | Values written to state, audit rows or hashes are rounded: kg 0.01, s 0.1, ratio 1e-4, reps 0.01, pin to the machine step (5 lb) (C22, C42). |
| Stable iteration | Maps are `LinkedHashMap` or sorted; rule order is a total order (section 5.3). |
| Snapshotted engine facts | Values that come from other modules (BW(t), RCM-1 `k`, SetCredit tables, TIME-1 estimates) are frozen into the event payload or the evaluation context before evaluation, so a replay does not depend on the current state of those modules (section 3.9). |
| Versioned inputs | Every evaluation records `ENGINE_VERSION`, module id@version, `params_hash`, `catalog_version` and the definition's `jcs_sha256` (C24, C30). |

### 1.5 Persistence mapping (from DATA_MODEL.md)

| Concept | Table | Notes |
|---|---|---|
| Definitions | `fitness.challenge_definitions` | Immutable once published; `jcs_sha256` (RFC 8785 JCS + SHA-256) is the hash used in snapshots; `server_checksum` is never compared with it. |
| Enrollment cache | `fitness.challenge_enrollments` | Cached fold (`state`, `state_rev`, `state_hash`, `fold_generation`), `params`, `rule_decisions` (keyed by proposal id, C48). Never the source of truth. |
| Input events | `fitness.challenge_events` | Append-only; synthesized events have deterministic UUIDv5 ids and a `dedupe_key`. |
| Rule audit | `fitness.challenge_rule_events` | One row per rule whose condition was true or errored; includes bindings, proposals, applied diff, suppression reason. |
| Test results | `fitness.metric_observations` | `valid_for_gate`, `protocol_code`, `derivation`, `calc_params` (D7 method, `k`, alternatives, 80 % interval). |
| Recommendations | `fitness.recommendations` | Snapshots of materialized sessions, week plans and contracts (C24). |
| Calendar links | `calendar_link` (Room, local-only) | Maps an app-written event (marker uuid plus slot key, C37) to the provider event and the last-written values (ANDROID.md 16.6; the Room schema is owned by ANDROID.md section 8, C40). |

---

## 2. CDS v1: the Challenge Definition Schema (`challenge.cds@1.0.0`)

### 2.1 Lifecycle of a definition

1. **Authoring.** Definitions are JSON files in the repo (`docs/challenges/<slug>.challenge.v<major>.json`
   during design; the build copies them to `algo/src/main/resources/fitness/challenges/`, next to the
   bundled params in `algo/src/main/resources/fitness/params/`; ANDROID.md 4, C40). Claude Code
   authors and reviews them; the user approves content. There is no in-app editor in v1.
2. **Validation.** Three stages (section 2.6): structural JSON Schema, semantic validator, golden
   simulation. All three run in CI (`:sim` and `:algo` tests). A definition that fails any stage is
   never published.
3. **Publishing.** A reviewed migration inserts the definition into `fitness.challenge_definitions`
   with `status = 'published'`, `id = UUIDv5("challenge_definition:<slug>@<version>")`, and the
   `jcs_sha256` computed by the authoring tool (RFC 8785 JCS + SHA-256). The guard trigger forbids
   any later `UPDATE` of `definition` (DATA_MODEL 9.4.1). Content changes always produce a new
   version row.
4. **Bundling.** The APK bundles every published definition as a fallback (offline first run). On
   pull, the app recomputes the JCS hash; a mismatch is rejected and logged (DATA_MODEL section 7).
5. **Activation.** A pulled definition version is used only by enrollments that pin it. New versions
   reach an existing enrollment only through an explicit, previewed migration (section 4.12).
   Definitions pulled mid-session activate at the next session start.

### 2.2 Versioning rules

| Change | Version bump | Enrollment effect |
|---|---|---|
| Text only (`messages`, names, notes, explanations) | patch (`1.0.1`) | Offered as a migration with an empty state map; may be auto-accepted because it cannot change any computed value (the validator proves it: identical compiled ASTs and constants). |
| New P proposal, new param with a default, new template for a later phase, changed default of a P param | minor (`1.1.0`) | Migration preview; new proposals arrive undecided (inactive) until the user decides. |
| Changed A rule, changed state var semantics, removed key, renamed item key or state var | major (`2.0.0`) | Migration with an explicit `varMap`; requires user confirmation after a diff preview; the engine range may change. |

`engine` is a semver range (for example `>=1.0.0 <2.0.0`). An app whose `ENGINE_VERSION` is outside
the range refuses to enroll and shows "Update the app to use this challenge version".

### 2.3 Top-level keys

Every key below is required unless marked optional. Unknown top-level keys are a validation error
(no silent extensions; new keys need a schema version).

| Key | Type | Purpose | Key validation (beyond the JSON Schema types) |
|---|---|---|---|
| `schema` | string | Schema id, exactly `fitapp.challenge/1`. | Must equal the engine's supported schema. |
| `id` | string | Slug, for example `oap`. | `^[a-z][a-z0-9_]{1,31}$`; equals the table's `slug`. |
| `version` | string | Semantic version of this definition. | SemVer 2.0 without build metadata. |
| `engine` | string | Supported engine range. | Valid semver range; must include the validating engine. |
| `title`, `summary` | i18n object | Display texts. | `en` required (D12); `nl` optional; 1-120 / 1-600 characters. |
| `provenance` | object | Sources, evidence note, tag legend. | `sources[]` with `id`, `ref`, `asOf`, `role`. |
| `challengeRules` | object | Descriptive rules of the challenge itself (start position, implements, forbidden aids, finish), shown in the UI and used as the attempt checklist. | `attemptChecklist[]` of i18n strings is required when any exercise has `type: attempt`. |
| `proposals` | array | P proposals, each approved or declined as a unit (D8). | Unique ids `^P[0-9]{2,3}$`; each has a param `approved_<id>`; `supersedes[]` names existing rule ids; `affects` references exist. |
| `params` | object | Definition parameters (name -> declaration, section 2.4.1). | Names `^[A-Za-z][A-Za-z0-9_]{0,63}$`; defaults type-check; `min <= default <= max`. |
| `equipment` | object | `required[]` and `optional[]` equipment, calibration bindings, unlocks. | Ids exist in `fitness.equipment_types` (DATA_MODEL 9.1.7). |
| `exercises` | array | Exercise entries: `catalogRef` or challenge-local entries (C10). | Section 2.4.2. |
| `substitutions` | array | Live substitutions (for example machine busy -> finger assistance). | `from`/`to` exist; `whenReason` is a reason code (DATA_MODEL 9.1.3). |
| `metrics` | array | Test and gate metrics. | Ids exist in `fitness.metric_defs` with matching unit and side mode; derived metrics type-check. |
| `derived` | array | Named facts computed per event (for example `weekCounts`). | Unique names; `events[]` subset of event types; expression type-checks. |
| `state` | object | State variables, snapshot list and snapshot trigger. | Section 2.4.5. |
| `structure` | object | Week start, block shape, advance rule, modes and their transforms. | Section 2.4.6. |
| `phases` | array | Ordered phases with slot -> template maps and exit gates. | Ids 1..n consecutive; every slot used in `schedule.slotsPerWeek` maps to a template in every phase. |
| `blocks` | object | Reusable item lists (warm-up, prehab, attempts). | Items valid; no nested `block` references (one level only). |
| `templates` | object | Session templates (gym, test, home) with inheritance. | Section 2.4.7. |
| `habits` | array | Home habits (tiny sessions, C34). | Section 2.4.8. |
| `schedule` | object | Slots per week per mode, slot priority, order, hard constraints, preferred times, test-day rule. | Section 2.4.9. |
| `calendar` | object | Title patterns per slot, slot aliases, slot titles, write-back config (D11). | Section 2.4.10. |
| `integration` | object | Owned muscles, coverage, protected windows, session tag rules, add-on slots, general-session policy, mode effects. | Section 2.4.11. |
| `rules` | array | ECA rules (section 5). | Section 2.4.12. |
| `inSessionRules` | array | Rules evaluated after a set or a side pair is logged (section 6.10). | Section 2.4.13. |
| `messages` | object | Message key -> i18n text with `{placeholders}`. | Every `notify.msg` and in-session `msg` key exists; placeholders resolve. |
| `onboarding` | object | Steps of the enable wizard. | Section 2.4.14. |
| `importSpec` | object | Fields accepted by the cutover import (D4). | Section 2.4.15. |
| `migrations` | object | Map `fromVersion -> {varMap, defaults, notes}` (empty in a first version). | Each `varMap` target is a declared state var of this version. |

### 2.4 Key-by-key specification

#### 2.4.1 `params`

```
paramName: {
  type: "bool" | "int" | "number" | "mass" | "pin" | "duration" | "days" | "enum" | "map" | "list" | "date",
  default: <literal of that type>,          // never an expression in v1
  min?, max?,                               // numeric types; validator enforces min <= default <= max
  values?: [..],                            // enum
  valueType?: <type>,                       // map and list
  tag: "A" | "P" | "D" | "E",
  proposal?: "Pnn",                         // required when tag = P
  source?: string, grade?: <evidence grade>,
  tunable?: bool, tunedBy?: string,         // tunable = true requires tunedBy
  userEditable?: bool,                      // shown in the challenge settings
  note?: string
}
```

Units: `mass` is kg (decimal), `pin` is the machine's native unit (lb for the Basic-Fit machine, F7)
and must be a multiple of the machine step, `duration` is seconds, `days` is whole days. Effective
param values for an enrollment = definition default, overridden by `challenge_enrollments.params`
(user choices at setup, for example `grip`), overridden by the proposal decisions (`approved_<id>`).
The resolved map is hashed into every evaluation snapshot.

Proposal switches: for every proposal `Pnn` there is a param `approved_Pnn: {type: bool, default:
false, tag: P, proposal: Pnn}`. Its effective value comes only from the user's decision
(`rules.approval_changed` events, section 4.10). An undecided proposal is inactive. Decisions are
stored per proposal id; one proposal may switch several rules, params and templates (C48).

#### 2.4.2 `exercises`

Every entry has a definition-local `key` (used by templates and FitExpr). Two forms (C10):

1. **Catalog reference.** `{"key": "dip_neutral", "catalogRef": "dip_neutral"}` or a parametrised
   reference `{"key": "weighted_pullup", "catalogRef": {"byParam": "grip", "map": {"supinated":
   "chinup_weighted", ...}}}`. An optional `overlay` may add `tags` and `flagsOffered`; it may never
   change coefficients, load mode or curve class.
2. **Challenge-local entry** (`"local": true`). Must pass the same catalog validator (CAT-1) as
   catalog entries: region coefficients only in {0, 0.25, 0.5, 0.75, 1}; region keys from the C9
   taxonomy (canonical ids of `fitness.muscle_regions`; aliases such as `forearm_flexors` are
   rewritten to canonical ids and reported as a warning); at least one region >= 0.75 unless the
   entry is tagged `prehab`, `skill` or `hang`; `requiresStation` keys exist in `equipment_types`.

Local entry fields:

| Field | Type | Meaning |
|---|---|---|
| `name` | i18n | English and Dutch names (D12). |
| `type` | enum | `weighted_bodyweight_reps`, `bodyweight_reps`, `assisted_bodyweight_reps`, `assisted_ordinal_reps`, `eccentric_reps`, `isometric_hold`, `assisted_isometric_hold`, `timed_hold`, `attempt`, `checklist`. |
| `pattern` | enum | Movement pattern key from the catalog pattern list (for example `vert_pull`, `skill_static`, `hang`, `core_anti_rot`). |
| `laterality` | enum | DATA_MODEL E37: `bilateral`, `unilateral` or `alternating` (unilateral side rows count 0.5 each, C7). |
| `grip` | string | Fixed grip (DATA_MODEL E43: `supinated`, `neutral`, `pronated`, `mixed`, `none`), or `param:<name>` to follow a definition param. |
| `implement` | string | Equipment key, or `floor` / `none` for no equipment. |
| `loadMode` | enum | DATA_MODEL E3 (C38): `external`, `bodyweight`, `bodyweight_plus_external` (belt load), `assisted_machine` (machine pin), `assisted_ordinal` (finger levels). Drives the single `LOAD` function (C1, C2). |
| `fBw` | number | Bodyweight fraction (C1: 1.00 for the pull-up family; 0 for floor holds). |
| `regions` | map | Region key -> discrete coefficient. Group coefficients are the maximum over the group's regions (no double counting). |
| `tags` | list | Free tags used by modes, windows and the contract (`unilateral_pull`, `heavy_unilateral`, `eccentric`, `skill`, `hang`, `challenge`, `elbow_stress_high`, ...). |
| `flagsOffered` | list | Per-set boolean flags the logger offers, as DATA_MODEL E6 codes (C38): `grip_slip_before_last`, `jerky`, `hand_shift`, `torso_rotation`, `hip_rotation`, `technique_breakdown`, `failed_rep`, `used_straps`. Any other value is a validation error (V18). |
| `logAs` | enum | `reps`, `eccentric`, `hold`, `attempt`. Reps items log with the RIR chips; holds log with the D9 seconds-in-reserve chips (Max / Hard / OK / Easy); eccentric items log the lowering seconds plus the same effort chips and never an RIR chip (C66, section 6.8). Stored as DATA_MODEL E38 measure and E39 contraction: `reps` -> `reps` + `dynamic`; `eccentric` -> `reps` + `eccentric_only`; `hold` -> `duration` + `isometric` (`hang` when `holdKind` is `hang`); `attempt` -> `attempt`. |
| `holdClass` | enum | Required for hold types: `grip`, `elbow`, `shoulder`, `trunk`, `knee` or `ankle` (catalog attribute `hold_class`, C43; selects the HOLD-1 exponent). |
| `entersSse` | bool | Whether sets feed SSE-1/RCM-1. Must be false for eccentric-only, isometric and finger-assisted entries (C8). |
| `curveClass` | enum | RCM-1 class prior when `entersSse` (C3). |
| `jointStress`, `gripDemand` | map, int 0-3 | Inputs for INJ-1 and the session tag rules. |
| `requiresStation`, `setupS` | list, int | Station requirements (F7) and TIME-1 setup seconds. |
| `holdKind`, `effortChips`, `clockStopsOn`, `attemptSeconds`, `assistScale`, `angles`, `records`, `checklist` | various | Type-specific details. |

#### 2.4.3 `metrics` and `derived`

A metric declares `id` (a `metric_defs.code` of DATA_MODEL 9.1.4, C38; for example `wpu_3rm_added`,
`oa_neg5_unassisted`, and `bw` for the test-day weigh-in), `type`, `sides` (`none` or `LR`), `better`
(`higher`, `lower`, `true`, `none`), a human `protocol` and a `protocolCode` (stored on every
observation, so tests under a changed protocol are not compared). A derived metric has a FitExpr
`derived` expression evaluated once when the test is completed; its value is stored with
`derivation = calculated:<method>` (DATA_MODEL E30, for example `calculated:personal_curve`) and
`calc_params` with the DATA_MODEL 9.6.6 keys (for D7: `method`, `k`, `m3`, `bw_kg`, `alternatives`
with the x1.10 value as `multiplier3rm`, and the 80 % interval as `ci80 {lo, hi}`; `sd_lnk` may be
added as an extra key). Derived values are never recomputed later (C3); a replay reads them from
the stored observation unless the replay explicitly asks for a counterfactual. Display-only derived
metrics (`displayOnly: true`) can never appear in gates or rule conditions (validator).

`derived[]` entries are named facts evaluated on the listed event types before rules run (they read
the pre-state, like rule conditions) and are addressed as `d.<name>`. They let a P proposal change a
definition ("easy", "missed week", "progress") without duplicating rules.

#### 2.4.4 `substitutions`

`{id, from, whenReason, to, <fixed item fields>, progressionData: "excluded" | "included", tag,
source}`. When the user logs reason `whenReason` on item `from` (for example `occupied`), the
materializer swaps in `to` for the remaining sets; the swapped sets are excluded from progression
(`valid(key)` is false) unless `progressionData` is `included`.

#### 2.4.5 `state`

```
state: {
  vars: [ { name, type, init: <FitExpr over onboard.*, p.*, s.* (earlier vars only)>, min?, max?,
            nullable?, values?, static?, tag, proposal?, note? } ],
  snapshotVars: [ names ],            // copied into snapshots[blockIndex] at snapshotOn
  snapshotOn: "block.started"
}
```

`init` expressions run once at `enrollment.created`, in declaration order; a var may reference only
vars declared before it. `onboard.*` are the values entered or imported in the wizard;
`onboard.x ?? <fallback>` supplies a default when the user had no value. Every state var must be
written by at least one rule or be marked `"static": true` (V10).

#### 2.4.6 `structure`

| Field | Meaning |
|---|---|
| `weekStart` | `MON` (ISO week, F18). Only `MON` is supported in v1. |
| `block.weeks` | Block length (FitExpr, usually `p.blockWeeks`). |
| `block.testSlot`, `block.testReplacesSlot` | The test slot and the build slot it replaces in the test week. |
| `advance` | `{mode: "completionDriven", weekCounts: <bool FitExpr>, onMissed: "repeatWeek"}`. The only advance mode in v1 (section 4.7). |
| `modes.<name>` | Mode transform: `inherits`, `inheritsIfDeclined` (fallback when the mode's proposal is declined), `sets` (FitExpr over `sets`), `loadFactor`, `holdSecondsFactor`, `exemptRoles`, `habitScale`, `excludeItemTags`, `disableHabits`, `prehabSetsFactor`, `progression` (`active` or `frozen`), `countsAsBlockWeek`, plus mode-specific overrides. Mode names are fixed in v1 (DATA_MODEL E26): `normal`, `deload`, `test_pending`, `extra_deload`, `injury`; an unknown mode name is a validation error. `progression` is fixed by C47: `deload`, `test_pending`, `extra_deload` and `injury` are always `frozen` (also when `test_pending` inherits `normal` because its proposal was declined), and `normal` is always `active`; a definition that declares otherwise fails V14. |

#### 2.4.7 `templates`, `blocks` and items

Template fields: `slot`, `name` (i18n), `kind` (`gym` | `test` | `home`; C34), `bookedMin`, `tags`
(session tags such as `pull_heavy`, `grip_heavy`, `elbow_flexor_heavy`, `heavy_unilateral`,
`unilateral_pull`), `requiresReadiness` (for example `["elbow"]`: the readiness elbow check is
mandatory before this session, F15), `validity` (test templates: FitExpr deciding whether the test
counts for gates), `items[]` or the inheritance operators `extends`, `replace`, `add`, `prepend`,
`override` (section 6.2), and `tag`/`proposal`/`source`.

Item fields (all optional unless noted):

| Field | Type | Meaning |
|---|---|---|
| `key` | string, required except for `block` items | Stable item key, unique within the resolved template. Rules address logged sets as `ex.<key>.*`. |
| `block` | string | Expands a reusable block from `blocks` in place. |
| `exercise` | key, `{anyOf: [keys], pick: "preference"}` or `{expr: <FitExpr returning a key>}` | Exercise to perform. |
| `metric`, `record`, `attempts` | | Test items: which metric the item records and how (`max`, `heaviestClean`, `lowestClean`, `bool`). |
| `role` | enum | `warmup`, `main`, `prehab`, `test`, `attempt`. Roles listed in a mode's `exemptRoles` keep their sets; warm-ups never count for coverage. Stored set kinds (DATA_MODEL E2, C38): `warmup` -> `warmup`; `main` and `prehab` -> `working`; `test` -> `test`; `attempt` -> `attempt`. |
| `sets`, `reps`, `repsRange`, `rounds`, `attemptsPerRound` | int / range / FitExpr | Volume prescription. |
| `hold_s`, `ecc_s_target`, `duration_s` | duration, `[min, max]` or FitExpr | Time prescriptions. |
| `added`, `assistPin`, `assist`, `fingers`, `load` | FitExpr / literal | Load prescription (added kg on the belt, machine pin, `none`, finger level, `light`). |
| `perSide`, `sideOrder`, `sideRule` | bool, `["L","R"]`, enum | Per-side expansion (section 6.5); `matchLeft`: the right side's target is capped at the left side's clean reps. |
| `rirTarget` | int | Target reps in reserve shown to the user. |
| `creditRir` | int | Planned RIR used by SetCredit for planned coverage (C6). |
| `rest_s` | int | Challenge rest; overrides REST-1 (C18). |
| `stopSetOn` | list | Flags (DATA_MODEL E6 codes, for example `torso_rotation`) that end the set (the logger shows the instruction). |
| `calibrationFirstSet` | bool | The first set (pair) is a calibration set; in-session rules adjust the remaining sets automatically (D14, section 6.10). Its rows are stored as `working` sets: it is not an RIRCAL-1 `calibration` set, which is never used on challenge lifts (F14). |
| `progression` | `rule` / `none` / `delegate:rx` | `rule`: the definition's rules progress it (RULE mode, C15; RX-1 runs in shadow and is logged). `delegate:rx`: the general RX-1 owns progression of this item. Stored `progression_mode` (DATA_MODEL E13): `rule` -> `rule`; `none` -> `fixed`; `delegate:rx` -> the mode RX-1 chooses (`model` or `double`). |
| `records`, `validForGate` | | A training item may record a metric observation; it is valid for gates only on a valid test day and only when `validForGate` is true. |
| `skipIf`, `enabledIf` | FitExpr | Skip (shown as "skipped, reason") or exclude (shown as "not available, reason"). |
| `position`, `after` | | Ordering hints and insertion point for `add`. `position` is `first`, `last`, `lastMain`, `inPlace` (keep the list position), `after:<itemKey>`, or `{expr}` returning one of these. The resolved order is fixed by the definition; the test-day wizard follows it (C65). |
| `replacesHabitToday` | habit id | The habit is skipped on a day with this session. |
| `variantVar`, `variants`, `variantFixed` | | Variant switch driven by a state var (for example 6 x 2 versus 1-2-3 ladders). |
| `bootstrap` | enum | First-exposure behaviour for delegated items (for example a bodyweight calibration set, C27; the calibration set then plans the remaining sets of that exercise automatically, C57). |
| `chalk`, `toFailure`, `note`, `protocol`, `protocolIfApproved`, `protocolProposal` | | Instructions; `protocolIfApproved` replaces `protocol` when its proposal is approved. |

#### 2.4.8 `habits`

`{id, name, where: home | anywhere, schedule: {rrule | timesPerWeek + preferredWeekdays + avoid,
time}, skipIf, enabledIf, disabledInModes, prescription (an item or a checklist), measure (metric,
frequency, weekday, protocol), tag, source}`. A habit occurrence is a tiny session with
`workout_sessions.kind = 'home'` (C34); completing it emits `habit.completed`. Habit reminders are
local notifications owned by `:app`. Mode `habitScale` multiplies sets and time; `disabledInModes`
mutes the habit.

#### 2.4.9 `schedule`

| Field | Meaning |
|---|---|
| `slotsPerWeek` | Mode -> ordered list of slots required that week. |
| `slotPriority` | Order in which slots are kept when not all fit (highest first). |
| `order` | `{sequence: [[slots], ...], strictness: "strict" or "relaxable"}`: in-week order. |
| `hard[]` | Hard constraints `{id, type, ..., tag, source}`. Types in v1: `minGapDays {days, appliesToTags}`, `restDayBefore {slots, scope: all_gym or all_training or tags, runs: warn or forbid}`, `maxPerWeek {sessionTag, n}`, `slotWeekday {slot, weekday}`. |
| `preferred[]` | `{slot, weekday, start, durMin}`: default day and time per slot (editable in setup). |
| `testDay` | `{weekday, postponeIf, postponeTo, postponedSlotBecomes}` (C36). |

The scope of every gap or rest rule is declared here (C11); the planner never hardcodes it.

#### 2.4.10 `calendar`

| Field | Meaning |
|---|---|
| `patterns[]` | `{slot, title: <regex>}` applied to the normalized title (section 9.2). |
| `slotAliases` | Groups of slots that share a calendar identity (for example `[B, TEST]`: the test day takes the place of B). |
| `slotTitles` | Slot -> i18n short title used in written event titles. |
| `writeBack` | `{mode: auto, button or off; horizonIsoWeeks; titleTemplate; testTitleTemplate; location; description; marker (documentation only, the engine owns the format: `[fitapp:v1 {enrollmentId} {slug}/{slot}/{isoWeek}]`, C37); humanLine; titleLocaleDefault}` (D11). Challenge events use the definition's title template (Dutch for OAP, F25). |

#### 2.4.11 `integration`

| Field | Meaning |
|---|---|
| `ownsMuscles` | Group keys (C9) whose weekly volume the challenge covers; the general engine treats them as covered. |
| `coverage` | `{method: "setcredit", plannedRir, warmups: "excluded"}` (C6, C7, C8, C10). |
| `protectedWindows[]` | `{id, relation: before or after, slots, hours, mode: hard or penalty, groups?, regions?, sessionTags?, scope?, rMin?, tissueRMin?, tag, proposal?}` (C12). |
| `sessionTagRules` | How the general engine tags its own sessions so the gap rule and windows apply (section 8.3). |
| `addOnSlots[]` | `{slot, position: end or none, capacity: "timeModel" or minutes, bufferMin, allowPatterns, forbidGroups, forbidSessionTags, maxGripDemand, maxSets, minRir, generalWarmup}` (D5, C34). `forbidGroups` is a list of C9 group keys or the string `"ownsMuscles"` (the definition's `ownsMuscles` list, as the OAP definition writes it). `bufferMin` is the C49 changeover (5 min); it is subtracted once, not on top of the session budget's changeover (section 6.9, V13). |
| `generalSessions` | `{allowedOnGapDaysIfNoTags, neverOn, forbidGroupsInHardWindows}` (D5, C11). The C50 gap-day policy (legs/push on any gap day including the day before A, light pull only under the C50 filter, one gym visit per day) is engine behaviour, not definition data (section 8.4). |
| `modeEffects` | Mode -> extra constraints exported to the general engine (for example injury forbids grip-heavy tags). |

#### 2.4.12 `rules`

```
{ id, tag: A | P | D | E, proposal?, uses?: [Pnn], supersededBy?: Pnn,
  stage: safety | data | structural | progression, prio: int (higher first within a stage),
  on: [event types], if: <bool FitExpr>, let?: [{name, expr}], then: [actions],
  allowInFrozen?: bool, explain: i18n template, source }
```

Rule ids match `^[A-Za-z][A-Za-z0-9_.+-]{0,79}$` (DATA_MODEL 9.7.2). An A rule whose definition of a
term comes from a proposal lists it in `uses` (its audit `rule_tag` is `A+P`). Actions are listed in
section 5.2.

#### 2.4.13 `inSessionRules`

```
{ id, tag, proposal?, item: <itemKey> | "*", slots?: [slots], on: "set.logged" | "pair.logged",
  if: <bool FitExpr over set.* or pair.*>, then: [suggest | notify | capTarget], source }
```

In-session rules never write enrollment state. They change the remaining planned sets of the item
immediately, with a visible "Rule: ... · Undo" (D14, C46), show instructions, or cap targets (section
6.10). The action key `suggest` keeps its name in CDS v1 for compatibility with existing definitions;
its effect is the automatic application described in 6.10.

#### 2.4.14 `onboarding`

`steps[]` with `{id, required, fields[] | shows[] | modes[], requiresDecisionOnEveryProposal?}`. The
wizard renders these steps in order plus the app-level steps (calendar, cutover; section 10.2).

#### 2.4.15 `importSpec`

Fields the cutover import may set (D4): `phase`, `blockIndex`, `blockWeek`, `mode`, `weekOrdinal`,
`plannedTestDate`, `vars` (any state var by name), `pastTests[]` (date, metric values, per-metric
validity and comparability flags, protocol codes, notes), `blockItemCounts` (valid sessions per item
key already done in the imported block before cutover; added to `validInBlock()` for that block
only), `importedTestHandling` (`historyOnly` or `recalculate` for a test done before cutover),
`snapshotCurrentAsBlockStart` (default true).

The import never creates historical set rows for RCM-1 (C59): past tests become metric observations
(`source = import`) with their validity and comparability flags, so the first gate uses the class
prior `k = 30` until RCM-1 has informative windows of logged sessions.

### 2.5 JSON Schema file

The structural schema is a JSON Schema (draft 2020-12) document, `cds.v1.schema.json`, in
`algo/src/main/resources/fitness/cds/` (bundled data lives under `resources/fitness/`, ANDROID.md 4, C40). The same file is used by (1) the app-side structural validator,
(2) CI, and (3) the optional server-side CHECK constraint
`extensions.jsonb_matches_schema('<schema>'::json, definition)` on
`fitness.challenge_definitions.definition`, if the user approves installing `pg_jsonschema` (F5,
migration `fitness_018_jsonschema_constraint`). The client validator is authoritative; the server check is a
second line of defence. Expression strings are typed as `string` at this stage.

### 2.6 Validation stages

**Stage 1, structural.** JSON Schema validation: required keys, types, enums, patterns, no unknown
keys. Output: list of `(jsonPointer, message)`.

**Stage 2, semantic validator.** Runs on the parsed definition with the catalog, region taxonomy,
metric defs, reason codes and equipment types of the target `catalog_version`. Every check has an
id so CI output and review notes can cite it.

| Check | Rule |
|---|---|
| V01 | Every FitExpr string parses (section 3.2) and type-checks in the scope where it appears (rule `if` on its `on` events, item fields at materialization, gates at test completion). |
| V02 | Every identifier resolves: `p.*` declared params; `s.*` declared state vars; `t.*`/`pt.*`/`m.*` declared metrics; `d.*` declared derived facts; `ex.<key>.*` keys that exist in at least one template the rule's guard can select (by `sess.slot`, `phase`); `event.*`, `set.*`, `pair.*`, `sess.*`, `week.*`, `block.*` fields of the declared payload types (section 3.7). |
| V03 | Functions are on the whitelist with correct arity and argument types (section 3.8). |
| V04 | Every `set`/`adjust` target is a declared state var with matching type and dimension; enum assignments use declared values. |
| V05 | Template inheritance is acyclic; `replace`/`override` keys exist in the parent; `add.after` keys exist; resolved item keys are unique per template. |
| V06 | Every slot in `schedule.slotsPerWeek` has a template in every phase (or in `templatesIfNotApproved` when the phase's templates depend on a proposal). |
| V07 | Every metric used in an exit gate is produced by a test template of the same phase, or by an `attempt` item when the gate source is `anyValidObservation` (C65: for OAP this requires the unassisted 5 s one-arm negative per arm in the test template). |
| V08 | Exercises: `catalogRef` targets exist (for every value of a `byParam` map); local entries pass CAT-1; `entersSse` false for eccentric, isometric and ordinal-assist types (C8). |
| V09 | Proposals: ids unique; each has `approved_<id>`; every rule/param/template tagged P names its proposal; every `supersedes` id is an A rule whose `supersededBy` names the proposal back; an A rule and its P replacement have mutually exclusive guards (`!p.approved_Pnn` versus `p.approved_Pnn` as top-level conjuncts). |
| V10 | Every state var is written by a rule, a mode transform or an import, or is marked static. |
| V11 | Messages: every `notify.msg` and in-session `msg` key exists; placeholders resolve to identifiers available at that rule. |
| V12 | Calendar patterns compile in the engine's regex dialect (section 9.2) and do not match the empty string; each slot of `slotsPerWeek` has a pattern. |
| V13 | Integration: `ownsMuscles` are C9 group keys; window `groups`/`regions` are canonical keys; `sessionTags` are known; add-on `allowPatterns` exist in the catalog pattern list (DATA_MODEL E36); add-on `bufferMin` equals the C49 changeover (5 min); windows declare no systemic (whole-body) term (D15). |
| V14 | Progression-stage rules that write snapshot vars either have a mode guard excluding frozen modes or set `allowInFrozen` (section 5.5); the validator lists them for review. Mode `progression` values follow C47 (section 2.4.6). |
| V15 | Unit sanity: no comparison or arithmetic across dimensions; pins combine only with pins and integer factors; masses are rounded only through `roundLoad`. |
| V16 | Size limits: definition <= 512 KB; <= 300 rules; <= 100 templates; each expression within the FitExpr limits (section 3.11). |
| V17 | Every param with `tunable: true` has `tunedBy`; every P param names its proposal; every A param has a `source`. |
| V18 | Vocabulary (C38): every stored code the definition uses is a DATA_MODEL section 8 value: load modes (E3), set flags in `flagsOffered`, `stopSetOn`, `clockStopsOn` and FitExpr flag fields (E6), laterality (E37), patterns (E36), grips (E43), reason codes (9.1.3) and metric codes (9.1.4); every `catalogRef` is a snake_case catalog slug present in the catalog seed (C38). camelCase flag names such as `gripSlipBeforeLast` are rejected. |

**Stage 3, golden simulation.** The `:sim` harness enrolls synthetic personas and replays scripted
event streams for 26 weeks through the real engine. Each persona's state trajectory (per-week vars,
mode, phase, block/week, rule firings) is written to a snapshot file committed to the repo; CI fails
on any diff that is not accompanied by an updated snapshot and a review note. Each persona runs
twice: all proposals approved, and all proposals declined.

| Persona | Script | Invariants checked |
|---|---|---|
| `all_success` | Every session complete, every progression condition met, tests improve 1-2 % per block. | Phase gates pass in order; loads never jump more than the definition's recalculation cap per test; no hard-constraint violation. |
| `stall` | Sessions complete but test metrics flat for 4 tests. | Stall stages 1 then 2 at the right tests; ladder variant and extra deload inserted once; revert after progress (when that proposal is approved). |
| `grip_slips` | Hand slips on weighted sets in 30 % of sessions. | No weighted-load increase after a session with a slip (lock); nothing else changes. |
| `elbow_pain_wk7` | Medial elbow ache reported in week 7; pain-free from day 10; second scenario with pain until day 16. | Injury mode for >= 14 days; unilateral and eccentric items absent; restore to the snapshot two blocks back; extension by 7 days in scenario 2 (when approved). |
| `missed_weeks` | Weeks 3, 9 and 10 with 0-1 sessions; a 9-day and a 30-day pause. | Week repeats with identical loads; test postponed until a clean session; pause repeat/restart behaviour. |
| `machine_busy` | Assisted machine occupied in 25 % of A sessions. | Finger substitution; those sessions do not move the pin. |
| `dst_and_late_ticks` | App not opened for 12 days spanning 2026-10-25; events arriving late (offline device). | Ticks synthesized once per date; the 25-hour day yields one `day.tick`; a refold produces the same final state as in-order delivery. |

Global invariants for every persona: deterministic (two runs produce byte-identical outputs and
hashes); every prescribed load is loadable with the persona's equipment; no gym session is placed on
a day blocked by a hard constraint; no protected-window violation in the contract; state vars stay
within declared bounds; an audit row exists for every applied change.

---

## 3. FitExpr: the rule expression language (`challenge.fitexpr@1.0.0`)

### 3.1 Design goals

FitExpr is a small, typed, side-effect-free infix language (C21). Expressions are stored as strings
inside the definition JSON, compiled once to a typed AST, and evaluated against a frozen context.
Goals: readable by the user and by Claude Code reviews; statically checkable against the declared
params, state, metrics and items; dimension-safe (kg, lb pins, seconds and days cannot be mixed by
accident); deterministic decimal arithmetic (C22); guaranteed termination (no loops, no recursion,
no user-defined functions). Actions are not expressions: they are declarative JSON objects (section
5.2). JSON-Logic, CEL and embedded scripting were rejected (research challenge_engine, C21).

### 3.2 Lexical structure and grammar

Tokens:

| Token | Form | Notes |
|---|---|---|
| Number | `[0-9]+(\.[0-9]+)?` | Decimal literal, parsed with `BigDecimal(String)` (exact). |
| Unit suffix | directly after a number, no space: `kg`, `lb`, `s`, `min`, `h`, `d`, `%` | `kg` -> Mass; `lb` -> Pin; `s`, `min`, `h` -> Duration in seconds; `d` -> Days; `%` -> Num divided by 100. |
| String | `'...'` with `\'` and `\\` escapes | Single quotes, so expressions can live inside JSON double-quoted strings. Max 256 characters. |
| Keywords | `true`, `false`, `null`, `in` | |
| Identifier | `[A-Za-z_][A-Za-z0-9_]*` | Scopes in section 3.7. |
| Operators | `? : ?? \|\| && ! == != < <= > >= in + - * / . [ ] ( ) ,` | There is no modulo operator; use `mod(a, b)`. |

Grammar (EBNF, lowest precedence first):

```
expr        = ternary ;
ternary     = coalesce [ "?" expr ":" expr ] ;            (* right-associative *)
coalesce    = or { "??" or } ;
or          = and { "||" and } ;
and         = not { "&&" not } ;
not         = "!" not | comparison ;                     (* ! binds looser than comparison *)
comparison  = additive [ ( "==" | "!=" | "<" | "<=" | ">" | ">=" | "in" ) additive ] ;  (* non-associative *)
additive    = multiplicative { ( "+" | "-" ) multiplicative } ;
multiplicative = unary { ( "*" | "/" ) unary } ;
unary       = "-" unary | postfix ;
postfix     = primary { "." ident | "[" expr "]" | "(" [ expr { "," expr } ] ")" } ;
primary     = number [ unit ] | string | "true" | "false" | "null" | ident
            | "(" expr ")" | "[" [ expr { "," expr } ] "]" ;
```

Notes: `!a == b` parses as `!(a == b)` (deliberate: negation applies to a whole comparison);
comparisons do not chain (`a < b < c` is a parse error); a call is only allowed on a bare function
name from the whitelist (no calls on member results); `x in list` tests membership.

Precedence table (high to low): postfix (`.`, `[]`, call) > unary `-` > `*` `/` > `+` `-` >
comparison and `in` > `!` > `&&` > `||` > `??` > `?:`.

### 3.3 Types and dimensions

| Type | Meaning | Literal examples |
|---|---|---|
| `Num` | Dimensionless decimal | `1.05`, `65%` |
| `Int` | Integer subtype of `Num` | `3` |
| `Mass` | Kilograms (decimal) | `2.5kg`, `0kg` |
| `Pin` | Machine-native assistance units (lb for the Basic-Fit machine); integer multiple of the machine step | `5lb`, `0lb` |
| `Duration` | Seconds (decimal, canonical grid 0.1 s) | `6s`, `2min` |
| `Days` | Whole days | `14d` |
| `Date` | `LocalDate` in the profile zone | `today`, `s.injuryStart` |
| `Bool`, `Str` | | `true`, `'too_easy'` |
| `Enum(values)` | Declared enum; compared with string literals that must be declared values | `mode == 'injury'` |
| `List<T>` | Homogeneous list | `[4, 3, 2, 1]`, `ex.oaa.reps` |
| `SidePair<T>` | Left/right pair (`.L`, `.R`) of a metric with `sides: LR` | `t.oa_hang_s` |
| `Map<K,V>` | Read-only map (params of type `map`) | `p.thresholdRatioByGrip` |
| `Null` | Absence | `null` |

Dimension rules (anything not listed is a compile-time `TypeError`):

| Operation | Result |
|---|---|
| `Mass ± Mass`, `Duration ± Duration`, `Pin ± Pin`, `Days ± Days`, `Num ± Num` | same type |
| `Mass * Num`, `Num * Mass`, `Mass / Num` | `Mass` (same for `Duration`) |
| `Mass / Mass`, `Duration / Duration` | `Num` |
| `Pin * Int`, `Int * Pin` | `Pin` (a non-integer factor is a `TypeError`; use `roundPin`) |
| `Date ± Days` | `Date`; `Date - Date` -> `Days` |
| `Int` op `Int` (`+ - *`) | `Int`; `Int / Int` -> `Num` |
| Comparison | Both sides of the same dimension (an `Int` compares with a `Num`); `Enum` with a declared string literal; `== null` / `!= null` allowed on any type |
| `Pin` -> `Mass` | Only through `assistKg(pin)` or `lbToKg(pin)` |
| Unary `-` | Allowed on every numeric dimension (`-p.pinDropLb` is a negative `Pin` step) |

### 3.4 Lists, broadcasting and explicit quantifiers

- `List<T> op Scalar` and `Scalar op List<T>` broadcast element-wise and return a list.
- `List op List` requires equal lengths (`EvalError list_length_mismatch` otherwise).
- `x in list` returns `Bool`; `list in list` broadcasts the left side and returns `List<Bool>`.
- A `List<Bool>` in a boolean position (`if`, `&&`, `||`, `!`, the condition of `?:`) is a compile
  error: it must be wrapped in `all()` or `any()` (deliberate; Liftoscript's implicit "all" hides
  intent).
- Aggregates skip `null` elements: `count` counts non-null elements, `len` counts all elements.
  `min`/`max`/`avg`/`first`/`last` of an empty (or all-null) list raise `empty_aggregate`. `all([])`
  is `true` and `any([])` is `false`; rules that must not pass on no data add a `count(...) >= n`
  conjunct (the OAP rules do).

`SidePair` values: `.L` and `.R` give the sides; `min(pair)` / `max(pair)` take the weaker/stronger
side; `defined(pair)` is true only when both sides are present; arithmetic between two pairs is
side-wise.

### 3.5 Null, missing and errors at runtime

| Situation | Behaviour |
|---|---|
| Identifier path absent at runtime (for example `t.oa_hang_s` before any valid test) | `EvalError missing:<path>` |
| Arithmetic on `null` | `EvalError null:<path>` |
| `defined(x)` | Special form: evaluates `x`; returns `false` on `missing` or `null` instead of raising. |
| `a ?? b` | Special form: returns `b` when `a` is missing or null. |
| `&&`, `\|\|`, `?:`, `??` | Short-circuit; the unevaluated branch cannot raise. |
| Error in a rule condition | Condition is `false`; an audit row with `outcome = error` and `eval_error` is written. |
| Error in a `let` binding or in any action value | The whole rule is dropped (atomic per rule); audit `outcome = error`. Other rules are unaffected. |
| Error in a materialization field | The item is shown with the last good value from state and a warning "could not compute"; never a crash. |

### 3.6 Numerics (C22)

- All arithmetic in `java.math.BigDecimal` with `MathContext(34, RoundingMode.HALF_EVEN)`.
  Comparisons are exact (`compareTo`, never `equals`, so `2.50 == 2.5`).
- Literals are parsed from their decimal text (never through `double`).
- Values that come from the statistical engine (RCM-1 `k`, `sd_lnk`, BW EWMA) are produced with
  `double` and `StrictMath` by those modules and are rounded to the canonical grid at the boundary
  (ratio 1e-4, kg 0.01) before they enter FitExpr. `userKQuantile` computes its exponential with
  `StrictMath.exp` and rounds to 1e-4.
- Rounding happens only through explicit functions (`round`, `floor`, `ceil`, `roundLoad`,
  `roundPin`) and once more when a value is written to state (kg 0.01, s 0.1, ratio 1e-4, reps 0.01,
  pin step; C22, C42). `roundLoad` and `roundPin` delegate to LOAD-1 (section 3.8).

### 3.7 Identifier scopes

| Root | Type | Available in | Meaning |
|---|---|---|---|
| `p.<name>` | per declaration | everywhere | Effective param value (definition default -> enrollment params -> proposal decisions). |
| `s.<name>` | per declaration | everywhere | State var, pre-event value (snapshot semantics). |
| `t.<metric>` | per metric | rules on `test.completed`, gates, derived metrics | The test being processed (for gates: the test that triggered the gate check). `t.bw` is that test day's raw weigh-in (metric `bw`, C32), so gates are evaluated on the test-day bodyweight (C62). |
| `pt.<metric>` | per metric | same | The previous valid test of the enrollment (for comparisons). |
| `m.<metric>` | per metric | gates with source `anyValidObservation`, rules on `attempt.logged` | Bool metrics: true if any valid observation since the phase started is true (per side); numeric: latest valid observation. |
| `d.<name>` | per derived | rules on the derived fact's events | Derived fact (section 2.4.3). |
| `ex.<itemKey>.<field>` | `List<T>` | rules on `session.completed` | Logged rows of that item in the event's session, in logging order. Fields: `reps`, `rir` (raw reported RIR, C17), `added`, `pin`, `assist`, `fingers`, `hold_s`, `hold_reserve_s`, `ecc_s`, `effort` (`max`/`hard`/`ok`/`easy`), `side`, `kind`, and one boolean field per DATA_MODEL E6 set flag under its snake_case code (`grip_slip_before_last`, `jerky`, `hand_shift`, `torso_rotation`, `hip_rotation`, `technique_breakdown`, `failed_rep`, `used_straps`; C38), plus scalars `plannedRows` (Int, rows prescribed after mode transforms), `targetReps` (Int), `targetHold_s`. `ex.<key>.L` / `.R` restrict to one side. Each field maps to a stored column (table below). |
| `week.ex.<itemKey>.<field>` | `List<T>` | rules on `week.closed` | Same, over all sessions and habit occurrences of the closing week; plus `days` (Int, number of distinct dates with at least one row). |
| `sess.*` | scalars | session events | `slot`, `templateId`, `kind`, `date`, `completionRatio` (logged working rows / planned working rows, warm-ups excluded), `painReported` (Bool), `durationMin`. |
| `set.*` | scalars | in-session `set.logged` | The just-logged row: `reps`, `rir`, `added`, `pin`, `hold_s`, `side`, `index` (1-based within the item), `targetReps`, and the E6 flags under their snake_case codes (for example `set.grip_slip_before_last`). |
| `pair.*` | `List` of 2 / scalars | in-session `pair.logged` | The L and R rows of one per-side set: `reps`, `hold_s`, `pin`, ..., plus `index` (1-based pair number) and `targetReps`. |
| `event.*` | per event type | rules on that event | Payload fields: `painKinds` (List<Str>), `location`, `side`, `severity`, `painScore`, `elbowDiscomfort` (Int 0-3), `gapDays`, `reason`, ...; each maps to a stored payload key (second table below). |
| `week.*` | scalars | week events | `iso` (Str `YYYY-Www`), `start` (Date), `ordinal` (Int). |
| `block.*` | scalars | everywhere | `index` (Int >= 1), `week` (Int >= 1), `startDate` (Date). |
| `phase` | Int | everywhere | Current phase id. |
| `mode` | Enum | everywhere | Current mode. |
| `bw` | Mass | everywhere | BW(t) EWMA for prescriptions (C32), from the engine-fact snapshot. |
| `today` | Date | everywhere | Local date of the event in the profile zone. |
| `test.valid` | Bool | `test.completed` | Result of the test template's `validity` expression. |
| `onboard.*` | per field | `state.vars[].init` only | Values entered or imported in the wizard. |
| `item.*` | scalars | materialization fields | The item being materialized (`sets`, `reps`, ...), used by mode transforms (`sets` alone is a shorthand for `item.sets` inside `structure.modes.*.sets`). |
| `let` names | per expression | after the binding | Bound in order by `let[]`; may reference earlier bindings. |

Row fields map to DATA_MODEL `set_logs` columns (9.6.4) and value domains (section 8), so rules read
exactly what was stored (C38):

| FitExpr field | Type | Stored as |
|---|---|---|
| `reps` | Int | `reps` |
| `rir` | Num | `rir_reported` (raw, C17; the "5+" chip is 5 with `rir_is_lower_bound`) |
| `added` | Mass | `added_kg` |
| `pin` | Pin | `assist_pin_native` in `assist_unit` |
| `assist` | Mass | `assist_kg` (computed by LOAD-1 `assistKg`, C2) |
| `fingers` | Int | `assist_ordinal` (ordinal only, C2) |
| `hold_s` | Duration | `duration_s` |
| `hold_reserve_s` | Duration | `hold_reserve_s` |
| `effort` | Enum | Derived from `hold_reserve_s` with the HOLD-1 chip mapping (Max 0 s, Hard 2 s, OK 5 s, Easy 10+ s) |
| `ecc_s` | Duration | `ecc_s_actual`; for a multi-rep eccentric row this is the shortest lowering of the set (UX 11.7.5), which is what the rules compare with the target |
| `side` | Enum | `side` (E1: `L`, `R`, `both`) |
| `kind` | Enum | `set_kind` (E2) |
| flag fields | Bool | membership of the code in `flags` (E6) |
| `reasonOn()` | Str | The latest of the rows' `deviation_reason_code` and the session exercise's `status_reason_code` / `substitution_reason_code` (codes of DATA_MODEL 9.1.3) |

Event fields map to the snake_case keys of the stored `challenge_events` payload (section 4.2), whose
coded values are DATA_MODEL codes (for pain and readiness events, those of the underlying
`wellness_logs` row, 9.6.5; C38). The FitExpr names stay camelCase because definitions already use
them:

| FitExpr field | Type | Stored payload key and domain |
|---|---|---|
| `event.painKinds` | List<Str> | `symptom_codes` (codes of `fitness.symptom_codes`, DATA_MODEL 9.1.6) |
| `event.location` | Str | `body_region` (codes of `fitness.body_regions`, 9.1.5; for example `elbow_medial`, as in `daysSincePain('elbow_medial')`) |
| `event.side` | Enum | `side` (`L`, `R`, `both`, `none`) |
| `event.severity` | Enum | `severity` (E20: `mild`, `moderate`, `sharp`) |
| `event.painScore` | Int | `pain_score` (0-10; chips 2 / 5 / 8, stop threshold 4, C64) |
| `event.elbowDiscomfort` | Int | `elbow_score` (0-3, F15) |
| `event.gapDays` | Days | `gap_days` (on `resume`) |
| `event.reason` | Str | `reason_code` (DATA_MODEL 9.1.3) |

### 3.8 Function whitelist

All functions are pure. Signatures use the types of section 3.3; `T` is any numeric dimension.

| Function | Signature | Semantics and errors |
|---|---|---|
| `min`, `max` | `(T, T, ...)`, `(List<T>)`, `(SidePair<T>)` -> `T` | n-ary or aggregate; `empty_aggregate` on an empty list. |
| `clamp` | `(x: T, lo: T, hi: T)` -> `T` | `min(max(x, lo), hi)`; when `lo > hi` the result is `hi` and the audit notes `clamp_inverted`. |
| `abs` | `(T)` -> `T` | |
| `floor`, `ceil`, `round` | `(x: T [, step: T])` -> `T` | To a multiple of `step` (default 1 unit of the dimension). `round` ties: half-even. |
| `mod` | `(Int, Int)` -> `Int` | Mathematical modulo; `div_zero` when the divisor is 0. |
| `all`, `any` | `(List<Bool>)` -> `Bool` | Explicit quantifiers. |
| `count`, `len` | `(List)` -> `Int` | Non-null elements / all elements. |
| `sum`, `avg` | `(List<T>)` -> `T` | |
| `first`, `last` | `(List<T>)` -> `T` | |
| `defined` | `(any)` -> `Bool` | Special form (section 3.5). |
| `assistKg` | `(Pin)` -> `Mass` | LOAD-1's `assistKg` (ALGORITHMS B2.3): `pin x unitKg x ratio + offsetKg` from the bound machine calibration record (C2). For the Basic-Fit default `{unit lb, offsetKg 6.5, ratio 1.0}`: 40 lb -> 24.64 kg. |
| `oneArmLoad` | `(Pin)` -> `Mass` | `fBw x bw - assistKg(pin)` (C1: `fBw` = 1.00 for the pull-up family). |
| `lbToKg`, `kgToLb` | `(Pin)` -> `Mass`, `(Mass)` -> `Num` | Factor 0.45359237 (exact). `kgToLb` returns a plain number; turning it into a pin requires `roundPin`. |
| `roundLoad` | `(m: Mass, profile: Str, mode: 'down' \| 'up' \| 'nearest')` -> `Mass` | Calls the LOAD-1 rounding functions (ALGORITHMS B2.5; OI-A06) on the loadable set of the named load profile (for example `'belt'`: the user's belt plates and dumbbells, F7), with LOAD-1's tolerance `load.epsilon_kg` (0.005 kg): `down` = `easierOrEqual` (largest achievable <= `m + eps`, or the smallest achievable when none is); `up` = `harderOrEqual` (smallest achievable >= `m - eps`, or the largest when none is); `nearest` = `nearest`, ties to the easier (lower) load. `unloadable` only when the profile has no achievable value. FitExpr never implements its own rounding. |
| `roundPin` | `(x: Pin \| Num, mode: 'down' \| 'up' \| 'nearest')` -> `Pin` | Calls the same LOAD-1 functions after mapping the pin to system load, so `up` (more assistance, easier) = `easierOrEqual`, `down` (less assistance) = `harderOrEqual`, and `nearest` ties go to **more** assistance (C2, ALGORITHMS B2.5). Result is a multiple of the machine step within the calibration record's `[min, max]` (`max` unknown: LOAD-1's `load.pin_max_unknown_native`). |
| `e1rmTotal` | `(totalKg: Mass, reps: Int, k: Num)` -> `Mass` | RCM-1 with zero reserve: `totalKg x (1 + (reps - 1) / k)` (C3). Example: `e1rmTotal(103.2kg, 3, 30)` = 110.08 kg. `domain` error when `k <= 0` or `reps < 1`. |
| `loadForReps` | `(T1: Mass, reps: Int, k: Num)` -> `Mass` | Inverse: `T1 / (1 + (reps - 1) / k)`. |
| `kgPerRepAt` | `(totalKg: Mass, reps: Int, k: Num)` -> `Mass` | Slope of the curve at a point: `totalKg / (k + reps - 1)` (magnitude of dT/dr). Example: `kgPerRepAt(90.7kg, 5, 30)` = 2.67 kg per rep. |
| `userK` | `(exerciseKey: Str)` -> `Num` | Engine-exposed: RCM-1 `k_used` of the user for the catalog exercise that the definition's exercise key resolves to (through `catalogRef`, including `byParam`). This is the **same** `k` the general engine uses, which is what D7 requires. Read from the engine-fact snapshot of the event (section 3.9); never computed inside the rule. |
| `userKQuantile` | `(exerciseKey: Str, q: Num)` -> `Num` | `exp(ln k_used + z(q) x sd_lnk)` clamped to `[12, 90]` (RCM-1 grid bounds), with `z(0.1) = -1.2815516`, `z(0.9) = +1.2815516`; other `q` in (0, 1) use the inverse normal CDF (Acklam's algorithm in `StrictMath`). A low `k` means a steeper curve and therefore a **higher** e1RM, so the upper end of the e1RM interval uses `q = 0.1`. |
| `has` | `(equipmentId: Str)` -> `Bool` | Equipment present in the user's profile (snapshot). |
| `todayHas` | `(slot: Str)` -> `Bool` | The challenge week plan has that slot on `today` (planned or done). |
| `valid` | `(itemKey: Str)` -> `Bool` | Section 6.11. |
| `reasonOn` | `(itemKey: Str)` -> `Str` or `null` | The last reason code logged on that item in the event's session (REASON-1 codes, for example `too_easy`). |
| `validInBlock` | `(itemKey: Str)` -> `Int` | Number of sessions in the current block where `valid(itemKey)` held, plus the import's `blockItemCounts[itemKey]` when the current block is the imported block (pre-cutover sessions). |
| `sessionsDone` | `(minRatio: Num)` -> `Int` | On `week.closed`: number of distinct required challenge slots of the closing week with a completed session whose `completionRatio >= minRatio` (with `minRatio = 0`: at least one logged working row). Test sessions count for their slot. |
| `daysSince` | `(Date)` -> `Days` | `today - date`; `null:` error when the date is null. |
| `daysSincePain` | `(location: Str)` -> `Days` | Days since the last pain report at that body region (from `safety` facts in the snapshot); `9999d` if never. |
| `isoWeekOf` | `(Date)` -> `Str` | `YYYY-Www` (ISO 8601). |
| `isoWeekday` | `(Date)` -> `Int` | 1 = Monday ... 7 = Sunday. |
| `nextFixedTestDate` | `(from: Date)` -> `Date` or `null` | First date in `p.fixedTestDates` that is `>= from` and at least `p.fixedDateMinGapDays` after the last valid test. The validator requires both params when this function is used. |
| `phaseGatePasses` | `(phase: Int)` -> `Bool` | Evaluates the phase's `exitGate` (`all` of its criteria) against its declared source (`sameValidTestDay`: all criteria on the same valid test, with that test day's weigh-in `t.bw`, C62; `anyValidObservation`: `m.*`). |

Adding a function requires an engine minor version, a whitelist entry here, unit tests and a
conformance table row.

### 3.9 Engine-fact snapshot (replay safety)

Functions that expose other modules' values (`userK`, `userKQuantile`, `bw`, `has`, `daysSincePain`,
`todayHas`, `assistKg` calibration, `roundLoad` profiles) never call those modules during a fold.
Instead, when an input event is created, `:app` attaches an `engineFacts` object to its payload:

```
engineFacts: {
  capturedAt: Instant,
  bwKg: 70.70,                         // BW(t) EWMA (C32)
  userK: { "weighted_pullup": { catalogId: "chinup_weighted", k: 30.0000, sdLnk: 0.3000,
                                 fitRunId: "...", informativeWindows: 0 } },
  painLastDate: { "elbow": null },
  equipment: { has: ["pullup_bar", "fixed_neutral_handle", ...],
               machines: { "assisted_pullup_machine": { unit: "lb", step: 5, min: 0, max: null,
                                                         offsetKg: 6.5, ratio: 1.0, rev: 1 } },
               loadProfiles: { "belt": { achievableKg: [0, 1.25, 2.5, ...], rev: 3 } } },
  weekPlanSlots: { "2026-10-11": ["TEST"] }
}
```

The object is shown with its Kotlin property names; like every stored payload it is serialized with
snake_case keys (`engine_facts`, `captured_at`, `bw_kg`, `user_k`, `sd_lnk`, `pain_last_date`,
`load_profiles`, `achievable_kg`, `week_plan_slots`, ...; section 4.2). Map keys that are data
(definition exercise keys, equipment ids, dates) are kept as they are.

The `userK` entries are captured **before** the session's own post-session estimator update (L8),
so a test-day 3RM never feeds its own gate `k`. Only the facts that the definition's expressions can
read are captured (the validator lists them per event type), which keeps payloads small (<= 64 KB,
DATA_MODEL 9.7.1). A replay that wants counterfactual engine values (for example "what if k had been
33") substitutes the `engineFacts` object explicitly and labels the run as counterfactual.

### 3.10 Compilation and static checking

1. Parse each string to an AST with source positions.
2. Resolve identifiers against the scope of the field (section 3.7), the declared params, state,
   metrics, derived facts and item keys.
3. Infer types bottom-up; apply the dimension rules; reject `List<Bool>` in boolean positions.
4. Check function arity and argument types; constant-fold literal sub-expressions.
5. Store the typed AST in the `CompiledDefinition` cache keyed by `(jcs_sha256, json pointer)`.

### 3.11 Limits

| Constant | Default | Grade | Note |
|---|---|---|---|
| `challenge.fitexpr.max_ast_nodes` | 512 | engineering-fact | Per expression. |
| `challenge.fitexpr.max_depth` | 32 | engineering-fact | |
| `challenge.fitexpr.max_list_length` | 1000 | engineering-fact | Runtime lists longer than this raise `budget_exceeded`. |
| `challenge.fitexpr.max_string_length` | 256 | engineering-fact | |
| `challenge.fitexpr.max_call_args` | 8 | engineering-fact | |
| `challenge.fitexpr.max_eval_steps` | 10000 | engineering-fact | Node visits per evaluation; exceeding it raises `budget_exceeded`. |
| `challenge.fitexpr.math_context_digits` | 34 | engineering-fact | C22. |

These are engineering limits, not tunable training constants. The load-matching tolerance of
`roundLoad` is LOAD-1's `load.epsilon_kg` (0.005 kg, ALGORITHMS B2.4), not a FitExpr constant.

### 3.12 Error codes

| Code | Stage | Meaning |
|---|---|---|
| `parse` | compile | Syntax error with position and expected token. |
| `type` | compile | Dimension or type mismatch, `List<Bool>` in a boolean position, unknown enum literal. |
| `unknown_identifier`, `unknown_function`, `arity` | compile | Unresolvable name or wrong argument count. |
| `missing:<path>` | runtime | Identifier path absent (for example no previous test). |
| `null:<path>` | runtime | Arithmetic or ordering comparison on null. |
| `div_zero`, `domain` | runtime | Division by zero; `ln`/`k` out of domain. |
| `empty_aggregate`, `list_length_mismatch`, `side_missing` | runtime | Aggregate of nothing; broadcasting mismatch; `.L`/`.R` absent. |
| `unloadable` | runtime | `roundLoad` or `roundPin` has no achievable value in the requested direction. |
| `budget_exceeded` | runtime | Evaluation step or list limit exceeded. |

### 3.13 Worked examples (types in brackets)

| Expression | Result |
|---|---|
| `e1rmTotal(t.bw + t.wpu_3rm_added, 3, userK('weighted_pullup')) - t.bw` with BW 70.7 kg, 3RM +32.5 kg, k 30 | (70.7 + 32.5) x (1 + 2/30) - 70.7 = **+39.38 kg** [Mass] |
| same with `userKQuantile('weighted_pullup', 0.1)` (k = 30 x e^(-1.2816 x 0.30) = 20.42) | **+42.61 kg** [Mass] (upper end of the 80 % interval) |
| `p.thresholdRatioByGrip[p.grip] * t.bw - p.gate1GapKg` with 0.65, 70.7 kg, 2.0 kg | 45.955 - 2.0 = **43.955 kg** [Mass] |
| `min(t.oa_hang_s) >= p.gate1HangS` with L 15 s, R 20 s, 20 s | **false** [Bool] |
| `all(ex.wpu.reps >= ex.wpu.targetReps)` with reps [2,2,2,2,2,2], target 2 | **true** [Bool] |
| `ex.wpu.reps >= 2 && true` | compile error `type`: a `List<Bool>` needs `all()` or `any()` |
| `s.pinA - 5lb` with pin 40 lb | **35 lb** [Pin] |
| `s.pinA - 2.5kg` | compile error `type`: Pin minus Mass |
| `roundLoad(22.17kg, 'belt', 'nearest')` with a 2.5 kg belt grid | **22.5 kg** |
| `roundLoad(48.5kg, 'belt', 'down')` | **47.5 kg** (20 + 20 + 5 + 2.5) |
| `roundPin(35.27, 'up')` | **40 lb** (more assistance) |
| `today - p.injuryDays + p.injuryExtendDays` on 2026-10-21 with 14 d and 7 d | **2026-10-14** [Date] |

---

## 4. Enrollment lifecycle (`challenge.enrollment@1.0.0`)

### 4.1 Event sourcing in one paragraph

An enrollment has no mutable truth. Its state is `fold(initialState(definition, params, onboard,
import), inputEvents)`, where the input events are the append-only rows of `fitness.challenge_events`
in a total order. The cached row in `challenge_enrollments` (state, `state_rev`, `state_hash`) is a
rebuildable cache. This gives exact replays under any engine or definition version (the `:sim`
replay CLI), a complete audit, robust offline behaviour (a late event triggers a refold, section
4.11) and a trivial implementation of "back to the level of two blocks ago" (snapshots).

### 4.2 Input event types

The allowed `event_type` values are fixed by DATA_MODEL 9.7.1. These are domain input events in dot
notation; they are distinct from the telemetry stream `session_events`, whose snake_case event names
come only from the DATA_MODEL 9.8.1 registry (C39; the telemetry this engine causes is listed in
sections 9.8 and 13). Payloads are self-contained (they carry every fact the rules may read, plus the
engine-fact snapshot of section 3.9), carry logged rows under their `set_logs` column names (C38,
section 6.12) and never contain free text (C39). This section owns the payload format (UX 14.4 and
20.11 reference it): stored payload keys are snake_case, and every coded value is a DATA_MODEL code
(C38). Kotlin code uses camelCase properties that map one to one (`proposalId` -> `proposal_id`).

| Event | Emitted by | Key payload fields (stored keys) |
|---|---|---|
| `enrollment.created` | Setup wizard (section 10.2) | `params`, `onboard` values, `import` block (D4), initial proposal decisions (none in v1: decisions are separate events), `definition_version`, `jcs_sha256` |
| `rules.approval_changed` | Proposal list (D8) | `proposal_id`, `decision` (`approved` or `declined`, the values DATA_MODEL 9.4.2 stores), `previous?`, `decided_at`; one event per proposal, not per rule (C48) |
| `session.started` | Live session start | `session_id`, `slot`, `template_id`, `mode`, `local_date`, `started_at` |
| `session.completed` | Session finish (L8), including free sessions started from a challenge template (C35) | `session_id`, `slot`, `template_id`, `kind`, `local_date` (of the start), `completion_ratio`, `pain_reported`, all logged rows per item key with targets, flags, reasons and substitutions (section 6.12), `engine_facts` |
| `session.amended` | Editing a finished session | Same as `session.completed`; `supersedes_event_id` points at the original |
| `set.logged` | Live session, only for items that have in-session rules | `session_id`, `item_key` and the row (section 6.12); used for the audit of in-session rule applications and Undos (in-session rules never change enrollment state) |
| `habit.completed` | Habit tiny session (C34) | `habit_id`, `local_date`, rows, `measure` value if any |
| `readiness.checked` | Pre-session readiness sheet (F15) | `elbow_score` (0-3), `symptom_codes[]`, `readiness_level?` (E18) |
| `pain.reported` | Pain sheet, forwarded by `safety` (C33) | `wellness_log_id`, `body_region` (DATA_MODEL 9.1.5), `side`, `symptom_codes[]` (9.1.6), `severity` (E20 `mild`, `moderate`, `sharp`), `pain_score` (C64: 2, 5, 8 on the 0-10 scale; stop threshold 4), `context` |
| `test.due` | Tick synthesis on the planned test date | `date` |
| `test.completed` | Test-day wizard submit | `test_session_id`, `bw_kg`, metric values per side (`metric_defs` codes), `validity` inputs, attempts, `engine_facts` |
| `attempt.logged` | Attempt items (for example phase-3 attempts) | `metric` (for example `oa_rep`), `side`, `success`, checklist answers, `session_id?` |
| `week.closed`, `week.started`, `day.tick` | Tick synthesis (section 4.6) | `iso_week` / `date`; `week.closed` carries the week's session summary |
| `pause`, `resume` | Challenges tab | `reason_code` (DATA_MODEL 9.1.3); `resume` carries `gap_days` (whole days between pause date and resume date) |
| `abandon` | Challenges tab | `reason_code?` |
| `manual.override` | Challenge settings, "Correct a value" | `target` (state var or structural field), `value`, `reason_code` (required, DATA_MODEL 9.1.3); an optional note stays in the local History entry and is never part of the payload (C39) |
| `definition.migrated` | Migration sheet (section 4.12) | `from_version`, `to_version`, `var_map` result, `preview_hash` |

Derived events (`phase.changed`, `block.started`, `mode.changed`) are never stored as inputs; they
are produced during evaluation and appear only in `challenge_rule_events` (`derived_event_type`).

### 4.3 Event order

Events are folded in the order `(occurred_at, id)` (DATA_MODEL 9.7.1). Synthesized boundary events
use fixed local times so that this order is always the intended one:

| Event | `occurred_at` (profile zone) | Reason |
|---|---|---|
| `day.tick(d)` | `d 00:00:00.002` | Daily rules run at the start of the day. |
| `test.due(d)` | `d 00:00:00.003` | After the day tick; before any session that day. |
| `week.closed(W)` | Monday after `W` at `challenge.enrollment.week_close_local_time` (default 04:00:00.000) | A session that started on Sunday evening and was finished after midnight still counts for week `W` (its `session.completed` has an earlier `occurred_at`, see below). 04:00 always exists on a Monday in Europe/Amsterdam (DST changes happen on Sundays). |
| `week.started(W+1)` | the same Monday at 04:00:00.001 | Strictly after `week.closed(W)`. |

`session.completed.occurred_at` is the session **end**; the session's week attribution uses the local
date of its **start**. A session that starts before Monday 04:00 and ends after it is rare; when it
happens, the engine moves the `week.closed` evaluation after that session by refolding (section
4.11), because the week summary must include it.

### 4.4 State model

```
EnrollmentState {
  status: onboarding | active | paused | completed | abandoned          // E25
  mode: normal | deload | test_pending | extra_deload | injury           // E26
  modeSince: LocalDate
  phase: Int, phaseSince: LocalDate
  block: { index: Int >= 1, week: Int 1..10, startDate: LocalDate }
  weekOrdinal: Int >= 1                // active ISO weeks since start (calendar progress)
  vars: Map<name, Value>               // declared state vars, canonical grid
  snapshots: Map<blockIndex, Map<name, Value>>
  plannedTest: { date: LocalDate?, basis: blockRelative | fixedList | postponed | none,
                 postponedFrom: LocalDate? }
  lastValidTestSessionId: Uuid?, validTestCount: Int
  proposalDecisions: Map<proposalId, { decision, decidedAt }>
  pausedAt: LocalDate?, lastTickDate: LocalDate, lastWeekBoundary: LocalDate (a Monday)
  notificationsSent: Set<(msgKey, scopeKey)>   // dedupe (section 5.7)
  definitionVersion: String, definitionSha: String
}
```

`state_hash` = JCS SHA-256 of this object (C24). The cached JSON must stay <= 64 KB (DATA_MODEL
9.4.2); snapshots older than 12 blocks are pruned from the cache (they can be rebuilt by refold).

### 4.5 Statuses

| From | Event | To | Effect |
|---|---|---|---|
| (none) | `enrollment.created` | `active` (the wizard row is `onboarding` until the event is written) | Init vars, apply import, snapshot of the current block, `lastTickDate = created date - 1`. |
| `active` | `pause` | `paused` | `pausedAt = local date`. While paused: ticks are still synthesized and folded, but only safety-stage rules run (so an injury protocol keeps counting calendar days); `week.closed` has no structural effect; `weekOrdinal` does not advance; habits are muted; the contract is empty (`modeEffects.paused`); app-written future calendar events are removed (D11; only unmodified ones, the others get a prompt, C37 and section 9.4). |
| `paused` | `resume` | `active` | `gapDays = resumeDate - pausedAt`. The definition's `on: ["resume"]` rules decide what happens (for OAP: same week and a clean session before the test, or the P15 repeat/restart behaviour). The engine itself changes nothing else: because weeks did not advance while paused, "the same week" is automatic. |
| `paused` | `session.completed` for a challenge slot | `active` | The app first writes a `resume` event (reason `session_logged`) so the session is evaluated normally. |
| `active` or `paused` | `abandon` | `abandoned` | Terminal. Events and history kept; contract empty; future app-written events removed (unmodified ones only, section 9.4). "Restart" creates a new enrollment whose import is seeded from the last snapshot. |
| `active` | gate with `onExit: complete` passes | `completed` | Terminal. Celebration; contract empty; habits stop; future app-written events removed (unmodified ones only, section 9.4); logging challenge exercises stays possible as general training. |

A user can have at most one enrollment per definition slug in `onboarding`, `active` or `paused`
(partial unique index, DATA_MODEL 9.4.2).

### 4.6 Tick synthesis

Ticks are synthesized by `challenge.enrollment.synthesizeTicks(lastTickDate, today, plannedTest)`,
called on app start, on `daily-maintenance` (05:00, ANDROID.md 7.8, where tick synthesis runs first), after every session finish and
before any planning call. It is idempotent:

```
for d in dates (lastTickDate + 1 .. today) in the profile zone:          // LocalDate iteration, DST-safe
    emit day.tick(d)            dedupe_key "day.tick:<d>"
    if plannedTest.date == d: emit test.due(d)       dedupe_key "test.due:<d>"
lastTickDate = today
for m in Mondays (lastWeekBoundary + 7 days .. today):                   // separate cursor
    if now < instant(m, week_close_local_time): break                       // never emit a future event
    W = isoWeek(m - 1 day)
    emit week.closed(W)          dedupe_key "week.closed:<W>"   payload: week summary (sessions per slot, completion ratios)
    emit week.started(isoWeek(m)) dedupe_key "week.started:<isoWeek(m)>"
    lastWeekBoundary = m
```

No synthesized event ever has an `occurred_at` later than `now`: opening the app on Monday at 01:00
emits that Monday's `day.tick` but leaves `week.closed` for the next synthesis after 04:00 (the
`daily-maintenance` run at 05:00 normally makes it happen). Late synthesis is acceptable: when the
phone defers or kills the `daily-maintenance` job (aggressive OEM battery rules, D2), the boundary
events are emitted at the next app start, session finish, planning call or job run instead. Catch-up
is idempotent (dedupe keys) and ordered (each event carries its boundary `occurred_at`, and the fold
sorts by `(occurred_at, id)`, section 4.3), so a late tick produces the same state as a punctual one;
it only delays the change, never loses or reorders it (answers ANDROID OI-32). Event ids are UUIDv5 of
`challenge_events:<enrollment_id>:<dedupe_key>` (DATA_MODEL), so two devices synthesizing the same
tick produce one row. Iterating `LocalDate`s (not 24-hour steps) makes the 25-hour day 2026-10-25
produce exactly one `day.tick`. Week-dependent planning between Monday 00:00 and 04:00 treats the
new ISO week as started for display, but the state change happens at the boundary event. When one
synthesis run emits more than one `day.tick` (a gap), `:app` logs the telemetry event `tick_catchup
{enrollment_id, from_date, to_date, ticks}` (DATA_MODEL 9.8.1 registry, C39); the synthesizer itself
only returns the events.

Absence prompt: when `today - lastChallengeSessionDate >= challenge.enrollment.absence_prompt_days`
(default 14) and the enrollment was not paused, the app asks "Were you away? Mark the time since
<date> as a pause" before synthesizing. Accepting writes `pause` (backdated to the day after the
last session) and `resume` (today) events, so the definition's pause rules apply. Declining
synthesizes the ticks normally (every empty week is a missed week: same loads, no advance).

### 4.7 Completion-driven week and block advance

The definition decides on `week.closed` (OAP: `struct.week.close`, `struct.block.rollover`) by
emitting these structural actions; the engine executes them:

| Action | Engine semantics |
|---|---|
| `advanceWeek` | If the closing week's mode has `countsAsBlockWeek: true`, `block.week += 1`; otherwise `block.week` is unchanged (an extra deload week does not consume a build week). If the result would exceed `challenge.enrollment.max_block_weeks` (10, the DATA_MODEL check), the week repeats instead and a `block_too_long` warning is raised. |
| `repeatWeek` | `block.week` unchanged; loads unchanged ("no make-up, same weights"). |
| `startNewBlock {firstWeekMode}` | `block.index += 1`, `block.week = 1`, `mode = firstWeekMode` (default `normal`), `block.startDate` = the Monday that follows, `plannedTest = none`; emits derived `block.started`, which takes the snapshot of `snapshotVars` (section 4.13). |
| `restartBlock` | `block.week = 1`, `mode = normal`, `plannedTest = none`; `block.index` unchanged; no new snapshot (the block's start snapshot stays valid). Used together with `restoreSnapshot(block.index)`. |

`weekOrdinal += 1` on every `week.started` while `active`. Test dates are therefore block-relative
by construction; a definition that wants fixed dates expresses that with a rule (OAP's
`struct.deload.enter.fixed`, used only when P07 is declined).

### 4.8 Modes

Mode changes come only from rules (`setMode`, `startNewBlock`, `restartBlock`) and are structural
writes to the pseudo-variable `$mode`. Each change emits a derived `mode.changed` event. Mode
transforms (sets, loads, holds, excluded tags, habits) are applied by the materializer (section 6.4);
`progression: frozen` is enforced by the ECA engine (section 5.5). `injury` is entered only by
safety-stage rules and cannot be left through `manual.override` (D10: the pain protocol is always
enforced); it ends only through the definition's exit rule.

### 4.9 Manual override

`manual.override {target, value, reasonCode}` corrects a value (for example the user loaded 25 kg
instead of 22.5 kg for weeks and wants the state to reflect it). It is evaluated as its own event:
the target is type-checked, bound-checked and rounded to the canonical grid, then written; the audit
row has `rule_id = "manual.override"`, `rule_tag = "E"`. Allowed targets: any state var, `phase`
(only forward by one, or back by one), `block.week` (1..`max_block_weeks`), `plannedTest.date`. Not
allowed: `mode` out of `injury`, `status` (use pause/resume/abandon). Overrides appear in History
with the reason (and the local note, if any).

### 4.10 Proposal decisions (D8)

`rules.approval_changed {proposalId, decision}` sets `proposalDecisions[proposalId]` and therefore the
effective `p.approved_<proposalId>`. Decisions are recorded per **proposal id**, the unit the user
approves; the audit field `rule_decision` of each rule row is derived from the proposal the rule
belongs to (`proposal`, `supersededBy` or `uses`). The cached `challenge_enrollments.rule_decisions`
is keyed by proposal id (C48). An A rule runs exactly as written unless the P proposal that replaces it
is approved; undecided proposals are inactive (D8 as corrected, C48). Rules:

1. Setup requires an explicit decision on every proposal before the enrollment starts
   (`requiresDecisionOnEveryProposal`); there is no "accept all" (UX 14.4).
2. A decision can be changed later from the Proposals screen. It applies to events after the
   decision; past evaluations are not redone (the decision event sits at its own position in the
   fold, so a refold reproduces exactly the same history).
3. Proposals introduced by a definition migration arrive undecided and inactive; the Challenges tab
   shows a banner until they are decided.

### 4.11 Late, amended and out-of-order events

An event whose `(occurred_at, id)` sorts before the last folded event (offline device, edited
session, late sync) triggers a refold:

1. Find the latest fold checkpoint (cached state at a `block.started`) before the event's position.
2. Refold from there with the full ordered event list; `fold_generation += 1`.
3. Audit rows of the previous generation stay in the table but are excluded from reviews (reviews use
   only the current generation, DATA_MODEL 9.7.2).
4. Notifications are not re-sent if their dedupe key was already sent; new consequences (for example
   a different load) produce one summary notification "Your challenge was recalculated after a late
   session".
5. The calendar write plan and the contract are recomputed.

`session.amended` replaces the event it supersedes at the original position. A pain report retracted
with "Undo (wrong tap)" (UX 20.11) is written the same way: a new `pain.reported` with `retracted: true`
and `supersedes_event_id`; the refold then runs as if the original report had never been folded.

### 4.12 Definition upgrade and migration

1. A newer published version of the same slug appears. The Challenges tab shows "Update available".
2. Preview: the engine materializes the current and next ISO week under the old and the new version
   (new state from `migrations[fromVersion].varMap` applied to the current state) and shows a diff of
   loads, sets, rules and new proposals.
3. On "Update", the app writes `definition.migrated {fromVersion, toVersion, varMap, previewHash}`.
   Events before it stay evaluated under the old version; events after it use the new version. A
   replay pins each range to its version.
4. Patch versions that only change text may be applied without a preview (validator-proven no-op).
5. A major version is never applied automatically.

### 4.13 Snapshots

At `block.started` the engine copies the current values of `state.snapshotVars` into
`snapshots[block.index]`. `restoreSnapshot(n)` proposes writes of all snapshot vars from
`snapshots[n]`; if `n` is missing it uses the highest existing index `< n`, and if none exists, the
initial values (`init` expressions). At enrollment the import's current values become
`snapshots[importedBlockIndex]` when `snapshotCurrentAsBlockStart` is true (OAP cutover: block 1 =
the imported loads, because the true block-1 start loads are not part of the import).

### 4.14 Engine constants (enrollment)

| Key | Default | Bounds | Grade | Tunable | Note |
|---|---|---|---|---|---|
| `challenge.enrollment.week_close_local_time` | 04:00 (stored as 240 min after local midnight, ALGORITHMS A8.2) | 00:00-06:00 | engineering-fact | no | Grace for sessions finishing after midnight. |
| `challenge.enrollment.max_block_weeks` | 10 | 6-10 | engineering-fact | no | Matches the DATA_MODEL check on `block_week`. |
| `challenge.enrollment.absence_prompt_days` | 14 | 7-28 | expert-opinion | yes | Tuned by how often the prompt is accepted versus dismissed (`prompt_answered` / `prompt_skipped` in `session_events`). |
| `challenge.enrollment.snapshot_cache_blocks` | 12 | 4-50 | engineering-fact | no | Older snapshots rebuilt by refold. |
| `challenge.enrollment.checkpoint_every` | `block.started` | | engineering-fact | no | Refold checkpoints. |

---

## 5. ECA rule engine (`challenge.eca@1.0.0`)

### 5.1 Principles (C21)

1. **Snapshot semantics.** Every condition, `let` binding and action value of one evaluation reads
   the frozen **pre-event** state. Rules cannot see each other's writes. Results therefore do not
   depend on the order in which rules are listed.
2. **Total order.** Rules are sorted by stage (`safety` 0 < `data` 1 < `structural` 2 <
   `progression` 3), then `prio` descending, then `id` ascending (ordinal string comparison). There is
   no "same priority runs in parallel".
3. **First writer wins.** When two fired rules write the same variable, the first in the total order
   wins; later writes are suppressed and audited.
4. **Locks** collected from all fired rules before any write is applied, so a guard (for example a
   grip-slip lock at prio 60) blocks an increase (prio 50) regardless of position.
5. **Safety dominance.** If a fired safety-stage rule enters `injury`, every progression-stage
   proposal of the same evaluation is suppressed.
6. **Bounded cascade.** Derived events are evaluated recursively with a depth limit.
7. **Full audit.** Every rule whose condition was true or errored produces one audit row.

### 5.2 Actions

| Action (JSON) | Stage use | Semantics |
|---|---|---|
| `{"set": "s.x", "to": expr}` | any | Propose `s.x := value(expr)` (rounded to the canonical grid of its type). |
| `{"adjust": "s.x", "by": expr, "round": {"profile", "mode"}}` | any | Propose `s.x := round(s.x + value(expr))`; Mass rounds through `roundLoad(profile, mode)`, Pin through `roundPin(mode)`. |
| `{"lock": ["s.x", ...], "kind": "noIncrease" \| "noDecrease" \| "freeze"}` | any | Lock for this evaluation only. Lock direction is numeric (for a pin, "increase" means more assistance). |
| `{"setMode": name}` | safety, structural | Propose `$mode := name`; emits derived `mode.changed` when applied. |
| `{"notify": kind, "msg": key, "once": "perDay" \| "perBlock" \| "ever"}` | any | Queue a notification (`stop`, `warn`, `info`, `celebrate`, `prompt`); `once` defaults to `perDay`. |
| `{"restoreSnapshot": intExpr}` | safety, structural | Propose writes of every snapshot var from `snapshots[n]` (fallback rules in 4.13). |
| `{"advancePhase": true}` | structural | Propose `$phase := phase + 1`, or `$status := completed` when the phase has `onExit: complete`; emits `phase.changed`. |
| `{"advanceWeek": true}`, `{"repeatWeek": true}` | structural | Section 4.7. |
| `{"startNewBlock": {"firstWeekMode": expr}}`, `{"restartBlock": true}` | structural, safety | Section 4.7; `startNewBlock` emits `block.started`. |
| `{"scheduleTest": "testWeekday" \| "fixedDate"}` | structural | `plannedTest.date` := the definition's `slotWeekday` of the test slot in the current ISO week, or `nextFixedTestDate(today)`. |
| `{"postponeTest": "firstTestWeekdayAfterCleanSession"}` | structural | `plannedTest := {date: null, basis: postponed, postponedFrom}`; the planner places the test slot on the first test weekday after the next clean session; the slot on the original date becomes `schedule.testDay.postponedSlotBecomes` (C36). |
| `{"if": expr, "do": [actions], "else": [actions]}` | any | Conditional proposals, evaluated on the same frozen context. Nesting depth <= 4. |
| `{"suggest": {...}, "msg": key}`, `{"capTarget": {...}}` | in-session only | Section 6.10 (`suggest` is applied automatically with Undo, D14). Not allowed in `rules`. |

Writes to structural pseudo-variables (`$mode`, `$phase`, `$status`, `$block`, `$plannedTest`)
follow the same first-writer-wins and lock rules as state vars.

### 5.3 Evaluation algorithm

```
evaluate(event E, pre-state S0, compiled definition D, depth = 0, seen = {}):
    ctx = freeze(S0, E.payload, E.engineFacts, effectiveParams(S0))
    for f in D.derived where E.type in f.events:  ctx.d[f.name] = eval(f.expr, ctx)   // errors -> missing
    R = [r in D.rules where E.type in r.on and gateOpen(r, ctx)]
    sort R by (stageRank(r.stage), -r.prio, r.id)
    if S0.status == paused and E.type not in {pain.reported, readiness.checked, resume}:
        R = [r in R where r.stage == safety]
    fired = []
    for r in R:
        c = tryEval(r.if, ctx)
        if c is EvalError: audit(r, outcome = error, evalError = c); continue
        if c == false: continue                                   // no audit row for false
        b = bindLets(r.let, ctx)          // EvalError -> audit error, rule dropped (atomic)
        props = expandActions(r.then, ctx + b)   // conditional actions resolved here; errors -> drop rule
        fired += (r, props, b)
    locks = union(lock actions of all fired rules)
    injuryEntered = any(fired r with r.stage == safety and props contain setMode('injury'))
    frozen = modeTransform(S0.mode).progression == frozen
    written = {}
    for (r, props, b) in fired:                                    // total order
        for w in props.writes:
            if injuryEntered and r.stage == progression:  suppress(w, suppressed_injury, by = <safety rule id>)
            elif frozen and r.stage == progression and w.var in D.state.snapshotVars and not r.allowInFrozen:
                                                           suppress(w, suppressed_lock, by = "mode:" + S0.mode)
            elif w.var in written:                         suppress(w, suppressed_first_writer, by = written[w.var])
            elif violates(locks[w.var], S0[w.var], w.new): suppress(w, suppressed_lock, by = lockOwner(w.var))
            elif w.new == S0[w.var]:                       mark(w, no_effect)
            else: apply(w); written[w.var] = r.id
        audit(r, outcome = summarize(props), bindings = b + identifiers read, ...)
    S1 = commit(S0, applied writes); enforce declared bounds (min/max clamp, audited as clamp)
    queue notifications (dedupe, section 5.7)
    for derived event X produced by applied structural writes (mode.changed, block.started, phase.changed):
        if depth < cascade_max_depth and X.type not in seen:
            S1 = evaluate(X, S1, D, depth + 1, seen + {X.type})
    return S1
```

`gateOpen(r, ctx)` implements proposal gating (section 5.4). `summarize` returns `applied` when at
least one write was applied, otherwise the first suppression kind, otherwise `no_effect`.

### 5.4 Proposal gating

The engine enforces D8 independently of how carefully a rule's `if` was written:

| Rule shape | Evaluated only when |
|---|---|
| `tag: P, proposal: Pnn` | `p.approved_Pnn` is true. |
| `supersededBy: Pnn` (an A rule) | `p.approved_Pnn` is false (undecided counts as false). |
| `uses: [Pnn]` (an A rule using a P definition) | Always; the rule's expressions branch on `p.approved_Pnn` themselves (for example `d.cEasy`). Audit `rule_tag = A+P`. |
| Templates and modes with `proposal` | The materializer uses the P version only when approved, otherwise `templatesIfNotApproved` / `inheritsIfDeclined`. |

The explicit guards inside the OAP rules (`p.approved_P10 && ...`) are redundant with this gating;
validator check V09 requires them anyway, so the JSON reads correctly on its own. The same gating
applies to `inSessionRules` (a P in-session rule runs only when its proposal is approved).

### 5.5 Locks, first-writer-wins and frozen modes in detail

- `noIncrease`: a write is suppressed when `new > old` (numeric order of the variable). `noDecrease`
  the opposite. `freeze`: any change is suppressed. Locks live for one evaluation; a rule that wants a
  longer block writes a state flag instead.
- First writer wins is per variable. Two rules adjusting the same var are an authoring smell that
  the validator reports (V14 lists them); the engine still behaves deterministically.
- `progression: frozen` (deload, test_pending, extra_deload, injury; C47, whether or not
  `test_pending` inherits the deload transforms): a progression-stage write to a **snapshot var** is
  suppressed (`suppressed_lock`, `suppressed_by = mode:<mode>`) unless the rule sets
  `allowInFrozen: true`. Rules marked `allowInFrozen` must only
  make the prescription easier in frozen modes (the validator lists them for human review). The
  OAP definition marks three protective rules this way: `prog.B.neg.reset`, `prog.C.pin.hard`,
  `prog.A.pin.gated` (whose harder branch is itself guarded by `mode == 'normal'`).
- Non-snapshot vars (streak counters, flags) are not frozen; rules guard them explicitly.
- Data-stage rules (test recalculation) are never frozen: the test happens in the deload week by
  design.

### 5.6 Derived events and cascade bound

Derived events are `mode.changed`, `block.started` and `phase.changed`. They are produced only by
applied structural writes, evaluated immediately against the post-state, and may trigger rules that
list them in `on`. Bounds: depth <= `challenge.eca.cascade_max_depth` (3) and each derived type at most
once per root event. A cascade that would exceed the bound is cut and audited (`outcome = error`,
`eval_error = cascade_limit`). The OAP definition has no rules on derived events; snapshots on
`block.started` are taken by the engine itself.

### 5.7 Notifications

Each `notify` becomes a `ChallengeNotification {kind, msgKey, params, ruleId, scopeKey}`. Dedupe key:
`(msgKey, scopeKey)` where `scopeKey` is the local date (`perDay`), the block index (`perBlock`) or a
constant (`ever`). Kinds map to the UI: `stop` (safety red F16, full-screen sheet, cannot be
dismissed without reading), `warn` (amber banner), `info` (History entry plus a small card on Today
when relevant), `celebrate` (expressive card), `prompt` (a question with buttons; the answer becomes
an event). Placeholders (`{weaker}`, `{kgPerRep}`, ...) are filled from the rule's bindings.

### 5.8 Audit rows

One `fitness.challenge_rule_events` row per rule with a true or erroring condition (DATA_MODEL
9.7.2): `evaluation_id`, `fold_generation`, `rule_id`, `rule_tag` (`A`, `P`, `A+P`, or `E` for
engine-written rows such as `manual.override`), `proposal_id` (the proposal the rule belongs to
through `proposal`, `supersededBy` or `uses`; null for plain A and E rows, C48), `rule_decision`
(`approved`, `declined`, `not_required`; the decision of `proposal_id` in force), `stage` (E28,
`in_session` for in-session rules), `prio`, `outcome` (E29: `applied`, `suppressed_first_writer`,
`suppressed_lock`, `suppressed_injury`, `no_effect`, `error`), `cond_value`, `eval_error`, `bindings`
(every identifier read and its value), `proposals`, `applied` (`{var: {old, new}}`),
`suppressed_by`, state revisions and hashes before and after, `cascade_depth`,
`derived_event_type`, definition slug, version and hash, `module_version` (`challenge.eca@1.0.0`),
`engine_version`. Rules whose condition was false produce no row (counterfactual reviews use the
replay CLI instead). Bindings let a review re-evaluate a rule with a different threshold offline.

### 5.9 Explanations

Every applied write has a plain-language explanation built from the rule's `explain` template and
its bindings, shown in History and in the Why sheet (F19), for example "B: all 6 x 2 with at least 2
reps in reserve -> +2.5 kg next week (prog.B.wpu, from your program)". Suppressed proposals are shown
muted with their reason ("+2.5 kg not applied: grip slip"). The rule id, tag and evidence grade of
the underlying params are always reachable from the Why sheet.

### 5.10 Engine constants (ECA)

| Key | Default | Grade | Tunable | Note |
|---|---|---|---|---|
| `challenge.eca.cascade_max_depth` | 3 | engineering-fact | no | C21. |
| `challenge.eca.max_conditional_nesting` | 4 | engineering-fact | no | |
| `challenge.eca.stage_order` | `safety, data, structural, progression` | engineering-fact | no | C21. |
| `challenge.eca.notify_default_once` | `perDay` | engineering-fact | no | |

---

## 6. Session materializer (`challenge.materialize@1.0.0`)

### 6.1 Inputs and outputs

Inputs: enrollment state (pre-session), compiled definition, slot, date, the profile's equipment
(stations, belt load profile, machine calibration records), exercise preferences (PREF-1, C26), live
in-session events (substitution reasons, logged rows), BW(t) (C32), TIME-1 (C19).

Output:

```
SessionPlan {
  slot, templateId, phase, mode, date, kind (gym | test | home), bookedMin, budgetMin,
  blocks: [ { kind: challenge_block | general_block, items: [PlannedItem] } ],      // C34
  estMin (TIME-1), addOnCapacityMin, warnings[], explanations[],
  snapshot: { definition sha, engine version, params hash, catalog version, state_rev }
}
PlannedItem {
  key, exerciseId (catalog id or definition-local key), role, status: planned | skipped(reason) | excluded(reason),
  rows: [ { side: L | R | both (E1), setKind (E2), setIndex, pairIndex?, targetReps? | targetHold_s? | targetEcc_s?,
            added_kg?, plateBreakdown?, pin?, assistKg?, oneArmLoadKg?, fingers?, rest_s,
            logMode: done | rir_chips | hold_chips | ecc_seconds | attempt_checklist | test_record } ],
  rirTarget?, flagsOffered[], stopSetOn[], notes (i18n), progressionMode: rule | none | delegate:rx,
  inSessionRuleIds[], calibrationFirstSet
}
```

The plan is snapshotted to `fitness.recommendations` (C24) when the session starts; the logger
pre-fills weight, reps and pins from it (D9). The planned values are written to `set_logs` with
`planned_source = challenge_rule` (also after an in-session rule changed them, section 6.10).

### 6.2 Template selection and resolution

1. Template id = `phases[phase].templates[slot]`; if the phase's templates depend on a proposal
   (`templatesIfNotApproved`) and that proposal is not approved, use the fallback map. A slot
   postponed under C36 uses the slot named in `schedule.testDay.postponedSlotBecomes`.
2. Resolve inheritance from the root ancestor down. For each child, apply in this fixed order:
   1. `replace`: map `itemKey -> fields`; a shallow merge into the parent item (given fields replace
      fields of the same name; a field set to `null` is removed). Replacing `exercise` keeps the item
      key, so progression history continues under the same key.
   2. `add`: list of items; each is inserted after the item named in its `after` field, otherwise at
      the end of the main items (before trailing `prehab` items).
   3. `prepend`: list of block names or items inserted at the first position after the leading
      `warmup` items (attempts come first "when fresh", but never before the warm-up).
   4. `override`: map `itemKey -> fields`; a final shallow merge applied after `add` and `prepend`;
      it may not change `exercise` (validator V05).
3. Expand `block` references in place (one level). Block items keep their own keys; a key collision
   with a template item is a validation error.

### 6.3 Item evaluation order

For every resolved item, in template order:

| Step | Rule | Shown as |
|---|---|---|
| 1 | `enabledIf` false | excluded, with the expression's explanation (for example "typewriters: no wide bar") |
| 2 | Required station or implement missing (`has()` false), or equipment marked broken today | excluded ("needs: wide bar") |
| 3 | Mode excludes one of the item's exercise tags (`excludeItemTags`) | excluded ("elbow protocol") |
| 4 | `skipIf` true | skipped, with reason ("negatives skipped: elbow trace today") |
| 5 | Exercise choice: `anyOf` with `pick: preference` (section 6.7), `{expr}`, or `catalogRef.byParam` | |
| 6 | Live substitution (section 2.4.4) if a reason was logged on this item in this session | substituted, "progression data excluded" |
| 7 | Variant: `variants[value(variantVar)]` merged into the item (`variantFixed` wins) | |
| 8 | Evaluate numeric fields (`sets`, `reps`, `hold_s`, `ecc_s_target`, `added`, `assistPin`, `fingers`) with FitExpr against the state | errors keep the last good value and warn |
| 9 | Mode transform (section 6.4) | "deload: 2 of 4 sets" |
| 10 | Per-side expansion (section 6.5) | pairs L/R |
| 11 | Load resolution (section 6.6) | kg, plates, pin with effective kg |
| 12 | Rest (`rest_s`, overriding REST-1 per C18), flags, log mode, notes (`protocolIfApproved` when its proposal is approved) | |
| 13 | Ordering hints (`position`: `first`, `last`, `lastMain`, `inPlace`, `after:<itemKey>` or `{expr}`, section 2.4.7) applied as a stable reorder; the result is the order the logger and the test-day wizard use (C65) | |

### 6.4 Mode transforms

| Mode | Transform (definition data; OAP values shown) |
|---|---|
| `normal` | none |
| `deload` | For items whose role is not in `exemptRoles` (OAP: `warmup`) and not `test`: `sets := max(1, ceil(sets / 2))` (5 -> 3, 4 -> 2, 6 -> 3, 3 -> 2, 1 -> 1); the same formula applies to `rounds` and to ladder counts; `loadFactor` 1.0 (load kept); `holdSecondsFactor` 1.0; habits scaled by `habitScale` (OAP daily hang: sets x0.5, time x0.5, rounded up to whole sets and whole seconds); progression frozen (section 5.5). |
| `test_pending` | Inherits `deload` when its proposal (OAP P19) is approved, otherwise `normal` (sets and loads); progression is frozen in both cases (C47). Does not count as a block week. |
| `extra_deload` | Inherits `deload`; does not count as a block week. |
| `injury` | Items carrying an excluded tag (OAP: `unilateral_pull`, `eccentric`) are excluded; habits in `disableHabits` are muted; prehab sets x `prehabSetsFactor` (2); progression frozen; the `lightBilateral` sub-transform applies when its proposal (OAP P27) is approved (sets halved, minimum RIR 3, listed load overrides such as weighted pull-ups at bodyweight); when declined, the remaining items keep their loads and show a "keep it light" note. |

The general engine applies its own synced deload in the same week (F8, C13): it reads the mode from
the contract.

### 6.5 Per-side expansion

`perSide: true` turns each prescribed set into a pair of rows in `sideOrder` (default `["L", "R"]`).
"3 x 5 per arm" means 3 pairs = 6 rows; for volume each row counts 0.5 set (C7). The rest timer runs
after the pair (the alternation between sides is the rest for the other side). `sideRule:
matchLeft` caps the right side's target at the left side's clean reps of the same pair (applied in
session, section 6.10). A single machine pin applies to both arms (one pin state var per item); the
weaker side therefore sets the pace, and per-side results are kept for asymmetry tracking.

### 6.6 Load resolution

All load arithmetic (loadable sets, rounding, plate breakdown, system load, assistance) is done by
LOAD-1 (ALGORITHMS B2); the materializer only calls it.

- Weighted items (`loadMode: bodyweight_plus_external`): `added` comes from state (already rounded by
  the rule that wrote it). The materializer re-checks it against the current belt load profile; if it
  is no longer loadable (inventory changed) it rounds `down` (LOAD-1 `easierOrEqual`) and warns. The
  plate breakdown is LOAD-1's (fewest items, ties to the larger plates, verified to sum exactly;
  ALGORITHMS B2.8). Display: "+22.5 kg (20 + 2.5)", and the system load `fBw x BW + added` (C1) for
  transparency.
- Assisted items (`assisted_machine`): the pin comes from state; the materializer clamps it to the
  machine's `[min, max]`, shows `assistKg(pin)` and the one-arm load `fBw x BW - assistKg(pin)` (C2;
  40 lb -> 24.64 kg assistance, 46.06 kg one-arm load at 70.7 kg). Assist kg is displayed with one
  decimal ("24.6 kg", C54) and stored on the 0.01 kg grid. Set logs store the raw pin, unit, machine
  id and computed assist kg (C2).
- Generic load-jump caps (D13, C45): the RX-1 cap table (ALGORITHMS B9.14) never clamps a RULE-mode
  load. When a rule's new load or pin is a larger step than the generic cap allows (for example the
  OAP C pin 50 -> 45 lb, +5.46 % system load against the 5 % compound cap), the prescription is used
  as written, the item shows the non-blocking warning WE-S2 at WARN severity with its Why sheet, and
  the excess is logged (RX-1 shadow output in `recommendations`, `warning_shown` in `session_events`;
  ALGORITHMS B9.11, B15.2). A RULE-mode target RIR of 0 on a high-risk lift still becomes 1 (WE-S1).
- Ordinal assistance (`assisted_ordinal`, fingers): shown as an ordinal level only, never converted
  to kg (C2, C8).
- Holds and eccentrics: target seconds; logging uses the hold chips (D9) or the measured seconds.
- Delegated items (`progression: delegate:rx`): load from RX-1 (C15) with the item's sets, reps
  and `rirTarget`; `bootstrap` handles a first exposure (C27). They are not RULE-mode loads, so RX-1's
  caps are enforced on them as on general items, including C45's DOUBLE exemption (one loadable step
  even above the cap; for high-risk exercises only when the ladder start at the new load is >= r_lo;
  ALGORITHMS B9.14).

### 6.7 Exercise choice and preferences

`{anyOf: [...], pick: "preference"}` picks, among options whose equipment is available, the one
with the highest PREF-1 multiplier (C26: never 0 / dislike 0.6 / neutral 1.0 / like 1.15 /
favorite 1.495); ties keep list order. Options rated `never` are skipped. If every available option
is `never`, the first available option is still used and a conflict warning explains that the
challenge prescribes it (precedence: challenge above user hard constraints); the user can change the
rating, decline the related proposal, or pause the challenge. Fixed (non-`anyOf`) challenge
exercises are always used regardless of rating, with the same warning when rated `never`.

### 6.8 Logging modes

| Item type | Log action (D9) |
|---|---|
| Warm-up rows (`role: warmup`) | Logged with "Done"; no RIR is asked (C63; D9 applies to working sets). |
| Reps (weighted, bodyweight, assisted) | Weight/reps/pin prefilled; tapping an RIR chip `0 1 2 3 4 5+` logs the row. Challenge rules read the **raw** reported RIR (C17). |
| Holds and hangs | Timer with lead-in; tapping a seconds-in-reserve chip `Max / Hard / OK / Easy` logs the row; measured seconds editable. |
| Eccentrics (eccentric-only sets, C66) | Measured lowering seconds (timer or entry) plus the effort chips `Max / Hard / OK / Easy` (as for holds) and flags (`jerky`, `hand_shift`); no RIR chip (RIR is meaningless for negatives). The shortest lowering of the set is stored in `set_logs.ecc_s_actual`, which FitExpr `ex.<key>.ecc_s` reads (section 3.7). |
| Attempts | Checklist from `challengeRules.attemptChecklist`; success only when every point is ticked. |
| Test items | Test-day wizard entry per `record` mode with the "Record" action (UX 14.9, C63); a 3RM attempt is stored with RIR 0 and `rir_source = protocol` (DATA_MODEL E5). |

An "Add a set" action exists on challenge items too (C63): the extra row is logged as an override
(`override_applied`), appears in `ex.<key>.*`, and does not raise `completionRatio` above 1.

Flags in `flagsOffered` (E6 codes) appear as small toggles on the row; `stopSetOn` flags end the set
and show the instruction.

### 6.9 Duration and add-on capacity

`estMin` comes from TIME-1 (C19), the only duration model, owned by ALGORITHMS_PLANNING section 17:
per-rep tempo, per-set overhead, transitions, setup per equipment, the challenge's own warm-up block,
holds, eccentric reps and top-hold attempts as prescribed, the challenge's rest values, and TIME-1's
EWMA calibration (alpha 0.3). The materializer passes the materialized items; it does not estimate
durations itself.

`bookedMin` = the matched calendar event's duration, else the template's `bookedMin` (which is also
the duration the app writes into the calendar, section 9.4). Calendar gym events are training time
(F11 as revised, C49: travel happens before them), so the session budget is
`budgetMin = bookedMin - 5` (the 5-min changeover).

`addOnCapacityMin = max(0, floor(bookedMin - estMin - addOnSlot.bufferMin))` with `bufferMin` = the
same 5-min changeover (V13), which is C49's "booked minutes - TIME-1 estimate - 5 min" and equals
`budgetMin - estMin`. The changeover is subtracted once. No add-on is offered below the `time`
module's minimum add-on length (`time.min_addon_min`, 8 min, ALGORITHMS_PLANNING 17.6).

D5's "~35 min after C / ~20 min after A" are indicative only (C49); the real capacity comes from
TIME-1 and is recomputed every time and calibrated from logs. The worked OAP values are owned by
ALGORITHMS_PLANNING 17.6, whose "training time only" column is the C49 one
(uncalibrated TIME-1, 90-min events: about 24 min after C1 and about 5 min
after A1 in a normal week, so no add-on after A until calibration says otherwise; about 48 and 34 min
in a deload week). B and TEST have no add-on slot by definition.

### 6.10 In-session rules

After each logged row (`set.logged`) or completed side pair (`pair.logged`) of an item, the engine
evaluates the definition's `inSessionRules` whose `item` matches (`*` matches every item) and whose
`slots` contain the session's slot.

| Action | Effect |
|---|---|
| `suggest {field, scope: remainingSets, op: add \| set, value, round?}` + `msg` | Applied **automatically** to the remaining planned rows of this item in this session (D14, C46); the set card shows "Rule: <message> · Undo" (for example "Rule: hold under 6 s, remaining sets pin 35 -> 40 lb · Undo"). Mass values round through `roundLoad`, pins through `roundPin` (LOAD-1). The actual pin/load is what the logger records, and progression rules read the actual values (for example `last(ex.oaa.pin)`). |
| `notify` | An instruction toast (for example "Torso rotated: the set stops here"). |
| `capTarget {scope: nextSet(R), field, value}` | Changes the displayed target of the named next row (for example the right side matches the left). |

Behaviour (D14, C46):

1. **Auto-apply.** A firing `suggest` changes the remaining planned rows at once (planned values
   written with `planned_source = challenge_rule`); the user is never asked to accept it. The rule id,
   tag and message are shown on the card and in the Why sheet. Each application is logged as
   `challenge_rule_applied {rule_id, rule_tag, proposal_id?, session_exercise_id, field, from, to,
   sets_affected}` in `session_events` (DATA_MODEL 9.8.1 registry, C39).
2. **Undo.** Undo restores the planned values the rows had before this application and is recorded as
   an override with a reason (C46): `session_exercises.user_override` with `override_reason_code`
   (DATA_MODEL 9.1.3) and `override_fields` (DATA_MODEL 9.6.3, 9.6.4), plus the telemetry event
   `challenge_rule_undone {rule_id, session_exercise_id, reason_code, ms_since}` (registry 9.8.1; not
   `override_applied`, which is for "Add a set" and other user changes to a prescription). The undone
   rule does not fire again on this item in this session.
3. **Limit per rule per item (C71).** Each in-session rule that changes load or reps (`suggest`)
   applies at most once per item per session. `capTarget` and notify-only rules are not limited and
   may act after every set (for example `ins.scap.matchLeft`, which caps each right-side target at
   the left side's reps). Different rules may each apply once to the same
   item, so the OAP session-A calibration rule (pair 1) and P13 (a later pair) can both act on the
   assisted item. When two rules fire on the same logged row and target the same field, they are
   applied in rule-id order (ordinal) and the later one is audited as `suppressed_first_writer`
   (it has not used its one application and may still fire on a later row).
4. **Scope.** This applies only to challenge items, which run in RULE mode (C15). UI-06/AUTO-1
   suggestions are not shown for them (AUTO-1 still runs in shadow and is logged). The C16 limit (at
   most one suggestion per exercise per session, never auto-applied, D10) applies to general items
   only, including the general add-on block of a challenge session.
5. **Gating.** P in-session rules run only when their proposal is approved (section 5.4).

In-session rules never write enrollment state; their applications and Undos are audited through
`set.logged` events (`challenge_rule_events` with stage `in_session`, E28).

### 6.11 `valid(itemKey)`

`valid(key)` is true when **all** of the following hold for the event's session:

1. The item was part of the session plan and not excluded or skipped.
2. At least one working row was logged for it.
3. It was performed with the prescribed exercise: no substitution whose `progressionData` is
   `excluded`, and no user exercise swap.
4. No pain was reported during the session (`sess.painReported` false).
5. No reason from {`feeling_bad`, `too_tired`, `no_time`, `occupied`, `equipment_missing`, `pain`}
   was logged on the item (DATA_MODEL reason codes; `too_tired` has the same effect as `feeling_bad`,
   C57). Performance reasons (`too_easy`, `too_hard`,
   `grip_failure`, `torso_rotation`, `technique_breakdown`, `feeling_good`, `other`) do not invalidate:
   they are information the rules may read through `reasonOn()` and flags.

Rules additionally check completeness themselves (for example `count(ex.wpu.reps) >= ex.wpu.plannedRows`).

### 6.12 Session payload for `session.completed`

The payload holds, per item key: `rows[]` under their `set_logs` column names (C38; section 3.7
mapping): `side`, `set_kind`, `set_index`, `round_index`, `attempt_index`, `reps`, `rir_reported`,
`rir_is_lower_bound`, `added_kg`, `assist_pin_native`, `assist_unit`, `assist_kg`, `assist_ordinal`,
`duration_s`, `hold_reserve_s`, `ecc_s_actual`, `flags` (E6), `deviation_reason_code`, timestamps;
`plannedRows`, `targetReps`, `targetHold_s`, `status` (done, partial, skipped, excluded,
substituted); and for the session: `slot`, `templateId`, `kind`, `localDate` (start), `startedAt`,
`endedAt`, `completionRatio` (logged working rows / planned working rows, warm-up role excluded,
capped at 1, rounded to 1e-4), `painReported`, and `engineFacts`. Habit occurrences produce the same structure in
`habit.completed`. Free sessions started from a challenge template (C35) produce the same payload;
the slot comes from the chosen template, not from the calendar.

---

## 7. Challenge week planner (`challenge.plan@1.0.0`)

### 7.1 Purpose, inputs, outputs

Place the challenge sessions that are still required this ISO week on days and start times,
respecting the definition's hard constraints and preferences, honouring what is already fixed
(done sessions, calendar events), and answer "is today a challenge day?". It plans **only**
challenge sessions; general sessions are planned afterwards by `weekplan` (L5) under the contract.

Inputs: `now`, profile zone, enrollment state (mode, plannedTest, flags), compiled definition,
sessions done this week and the last gym session of the previous week, `SlotMatches` from
`challenge.calendar` (section 9), the other classified calendar anchors from `calendar.classify`
(planned general gym sessions with their tags, runs, busy blocks), the profile's availability
windows and gym opening hours.

Output:

```
ChallengeWeekPlan {
  isoWeek, mode, projected: bool,
  sessions: [ { slot, templateId, date, start, end, source: done | calendar_marked | calendar_unmarked | suggested,
                eventRef?, slotTypeMismatch?: bool, constraintWarnings: [ruleId] } ],
  blockedDays: [ { date, scope: all_gym | all_training | tags[], reason (i18n), ruleId } ],
  partial: bool, droppedSlots: [slot], relaxations: [ "order" | "drop:<slot>" ],
  score, scoreBreakdown
}
ChallengeTodayAdvice { kind: challenge | general_allowed | rest, slot?, start?, allowedFamilies?, reasons[] }
```

### 7.2 Required slots

```
required = schedule.slotsPerWeek[mode at week start]           // OAP normal: C, A, B; deload/test_pending: C, A, TEST
if plannedTest.basis == postponed and plannedTest.date not in this week: replace TEST by testDay.postponedSlotBecomes (B)
remaining = required - slots done this week - slots fixed by matched calendar events (future)
```

A done session is identified by its template slot (C35), not by the calendar. A matched calendar
event fixes its slot at the event's time; the planner never moves user-placed events (it reports
violations as warnings).

### 7.3 Candidate start times

For every day `d` from today to Sunday (today only if a start later than `now + challenge.plan.lead_min`
is possible):

1. If `d`'s weekday equals the slot's preferred weekday, its preferred start.
2. Starts inside the profile's availability window for that weekday: weekdays
   `challenge.plan.weekday_window_start` to `weekday_window_latest_start` in `weekday_step_min` steps;
   weekends from gym opening to closing minus the slot duration in `weekend_step_min` steps.
3. Remove starts whose interval `[start, start + durMin]` widened by `busy_buffer_min` overlaps a busy
   block (for example chess on Monday evenings until 2026-11-30) or another gym session, or falls
   outside gym opening hours.

### 7.4 Hard constraints (read from the definition, C11)

| Type | Feasibility test for a full assignment (fixed + done + candidates + previous week's last gym session) |
|---|---|
| `minGapDays {days, appliesToTags}` | For any two gym sessions that both carry at least one tag of `appliesToTags` (challenge templates carry tags; general sessions get tags from `sessionTagRules`, section 8.3), their dates differ by at least `days + 1`. Sessions without those tags are not constrained (D5). |
| `restDayBefore {slots, scope, runs}` | The calendar day before each listed slot contains no session in `scope` (`all_gym`: any gym session; `all_training`: also runs; `tags`: sessions with those tags). With `runs: warn`, a run that day is a warning, not a violation. |
| `maxPerWeek {sessionTag, n}` | Sessions carrying `sessionTag` in the ISO week <= n. |
| `slotWeekday {slot, weekday}` | The slot falls on that weekday (OAP: TEST on Sunday). A postponed test uses the first such weekday after a clean session. |
| `order` (when `strictness: strict`) | Slots respect the sequence (OAP: C before A before B or TEST). |

Protected windows (C12) bind general sessions, but a **fixed** general session that would fall in a
hard window of a candidate challenge slot makes that candidate infeasible (the challenge moves
around the user's fixed plans where it can).

### 7.5 Search and score

Exhaustive backtracking over the remaining slots (at most 7 days x a few starts x 3 slots), in slot
order. For each feasible full assignment:

```
score = sum over placed slots of (
          + w_pref_day      if weekday == preferred weekday
          + w_pref_start    if start == preferred start
          - w_day_distance * circularDayDistance(weekday, preferred weekday)
          - w_run_same_day   if a run is on the same day
          - w_after_busy    if start is within 2 h after another busy block )
        - w_gap_evenness * sum_i |gap_i - mean gap|          // gaps in days between consecutive gym sessions
```

Pick the maximum score. Assignments whose scores are within 0.05 of the maximum are treated as ties
(the C52 tie band, shared with `weekplan`) and ordered: earlier sessions first (the lexicographically
smallest list of dates in slot order), then the smallest start times, then lexicographic order of the
slot list. (C52's "fewer sessions" key does not arise here: every full assignment places the same
slots.) If no assignment is feasible, relax in this order and retry:

1. The `order` constraint, when `strictness: relaxable`.
2. Drop the lowest-priority remaining slot (reverse `slotPriority`; OAP drops C first, then A; `TEST`
   and `B` are never dropped automatically) and mark the week `partial`.

Hard constraints are never relaxed. The first relaxation that yields a plan is reported with the
blocking constraint ("Session C does not fit this week: Saturday is the rest day before the test and
Friday holds session A").

### 7.6 Today advice

1. Today has a done, matched or planned challenge session: `challenge` with slot and time.
2. Otherwise, if the user asks to train now: try each remaining slot in `order`; the first slot for
   which a feasible full week exists with that slot fixed today is recommended.
3. Otherwise, if today is not blocked for general training (section 8.4): `general_allowed` with the
   families the contract allows (for example "legs or push only, no grip-heavy work").
4. Otherwise `rest` with the rule ("Saturday before the test day: rest day").

The general `today` module (TodayRecommender, ALGORITHMS_PLANNING section 9) consumes this advice
first (precedence) through the contract's reserved sessions and blocked days. The user-facing verdict
`TRAIN / REST_SOFT / REST_HARD` with typed reason codes is composed by `today` and `ui.todaycard`,
which ALGORITHMS_PLANNING owns (C53): a `challenge` advice is a planned session (TRAIN), a `rest`
advice caused by a hard constraint is `REST_HARD` (reason `REST_BEFORE`), and `general_allowed`
leaves the choice between TRAIN and REST_SOFT to `today`.

### 7.7 Next-week projection

The calendar write-back horizon includes the next ISO week (D11), so the planner also plans next
week on a **projected** state: the enrollment is folded forward on a copy assuming every planned
session of the current week happens as planned and counts, and a planned test is valid. The result is
labelled `projected: true`. Every real state change (session logged, test postponed, pause)
recomputes both weeks; the write plan then updates the app's own events.

### 7.8 Engine constants (planner)

| Key | Default | Bounds | Grade | Tunable | Tuned by |
|---|---|---|---|---|---|
| `challenge.plan.busy_buffer_min` | 30 | 0-90 | expert-opinion | yes | Sessions that started later than planned after a busy block (`session_events` vs calendar). |
| `challenge.plan.lead_min` | 60 | 0-240 | expert-opinion | yes | How often a same-day suggestion is accepted. |
| `challenge.plan.weekday_window_start` | 17:30 | profile | expert-opinion | yes (profile) | Accepted versus moved suggestions. |
| `challenge.plan.weekday_window_latest_start` | 20:30 | profile | expert-opinion | yes (profile) | Same. The artifact's habit is 19:45 after dinner. |
| `challenge.plan.weekday_step_min` | 15 | 5-60 | engineering-fact | no | |
| `challenge.plan.weekend_open`, `weekend_close` | 09:00, 16:00 | profile | engineering-fact | no | Basic-Fit weekend hours from the artifact; editable in the gym profile. |
| `challenge.plan.weekend_step_min` | 30 | 15-60 | engineering-fact | no | |
| `challenge.plan.w_pref_day` | 3.0 | 0-10 | expert-opinion | yes | Accepted versus moved app-written events (calendar `user_moved` links). |
| `challenge.plan.w_pref_start` | 1.0 | 0-5 | expert-opinion | yes | Same. |
| `challenge.plan.w_day_distance` | 1.0 | 0-5 | expert-opinion | yes | Same. |
| `challenge.plan.w_run_same_day` | 2.0 | 0-5 | expert-opinion | yes | Challenge performance on run days (reps vs target). |
| `challenge.plan.w_after_busy` | 1.0 | 0-5 | expert-opinion | yes | Late starts after busy blocks. |
| `challenge.plan.w_gap_evenness` | 0.5 | 0-3 | expert-opinion | yes | Performance versus gap length. |
| `challenge.plan.tie_band` | 0.05 | fixed | engineering-fact | no | Tag D: fixed by C52, same band as `weekplan`. |

---

## 8. Integration contract (`challenge.contract@1.0.0`)

### 8.1 Contract structure

```
ChallengeContract {
  enrollmentId, definitionSha, isoWeek, status, mode, phase,
  blockIndex, blockWeek, mesocycleWeek (= blockWeek, C31), deloadWeek: bool (F8, C13),
  reservedSessions: [ { date, start, end, slot, templateId,
                        source: done | calendar_marked | calendar_unmarked | suggested,   // section 7.1
                        bookedMin, budgetMin (= bookedMin - 5, C49), estMin, sessionTags[],
                        addOnSlot?: { capacityMin, allowPatterns[], forbidGroups[], forbidSessionTags[],
                                      maxGripDemand, maxSets, minRir, generalWarmup: false } } ],
  blockedDays: [ { date, scope: all_gym | all_training | { tags: [...] }, reason, ruleId } ],
  gapRule: { days, appliesToTags[] },
  protectedWindows: [ { id, from: Instant, to: Instant, mode: hard | penalty, groups[], regions[],
                        sessionTags[], scope?, rMin?, tissueRMin?, tag, ruleRef } ],
  ownsMuscles: [group],
  coverage: { planned: {group: sets}, performed: {group: sets},
              committed: {group: sets},          // planned rows of sessions not yet done this ISO week
              bySide: {group: {L, R}} },
  sessionTagRules: {...},
  forbiddenTags: [tag], jointFlags: {joint: level},       // from mode effects (for example injury)
  generalSessions: { allowedOnGapDaysIfNoTags[], neverOn[], forbidGroupsInHardWindows,
                     oneGymVisitPerDay: true,                                          // C50
                     lightPullOnGapDays: { maxGripDemand: 1,                           // C50 light-pull filter
                                           groupCoefficientBelow: { biceps: 0.5 },
                                           outsideProtectedWindows: true, onlyIfGoalsAsk: true } }
}
```

ALGORITHMS_PLANNING 0.5 holds the planning-side view of this structure; where field names differ,
this section is the owner and the planning view is a mapping (CE-OI-12, resolved).

Precedence: safety > challenge > user hard constraints > general. The general modules (`targets`,
`allocator`, `today`, `weekplan`, `select`, `injury`, `warnings`) read the contract and never
re-derive challenge rules. A paused, completed or abandoned enrollment exports an empty contract.

### 8.2 Coverage (C6, C7, C8, C10)

Coverage is computed by the `setcredit` module (SetCredit.v1) on materialized rows, never from
inline challenge numbers:

- **Planned** coverage: every planned working row of the week's challenge sessions and habits, with
  the item's `creditRir` as planned RIR (C6 stimulus table `[1.00, 1.00, 0.95, 0.90, 0.80, 0.50]` for
  RIR 0-5, 0 above), warm-ups excluded, unilateral side rows 0.5 each (C7), holds with `hold_factor`
  0.5 when >= 10 s at seconds-in-reserve <= 2 else 0.25 (C8), hangs credited to forearms only (C8),
  group coefficient = maximum over the group's regions (C9). Coefficient levels 1.0 / 0.75 / 0.5
  earn fractional-set credit equal to the coefficient; 0.25 counts toward fatigue only (C44).
- **Performed** coverage: the same on logged rows with the reported RIR or hold chip (SetCredit uses
  the reported RIR; the RIRCAL bias applies only inside the estimators, C44).
- **Committed** coverage: the planned coverage of the week's challenge sessions and habits that are
  not done yet; the general planner adds it to the performed volume (ALGORITHMS_PLANNING's
  `coverageCommitted`).
- Owned muscles (`ownsMuscles`) count as covered for the general engine regardless of the user's goal;
  if the user's goal for an owned muscle is `skip`, the UI shows a conflict ("covered by challenge",
  D6) and the challenge wins while active.

### 8.3 Session tags for general sessions

The gap rule and the windows are expressed in session tags. Challenge templates declare their tags.
The general engine tags its own planned sessions with the definition's `sessionTagRules`, evaluated
on the planned general block:

| Tag | Rule (OAP defaults, tag E) | Tunable, tuned by |
|---|---|---|
| `pull_heavy` | >= 4 hard sets (SetCredit, RIR <= 4) credited to groups `lats` or `upper_back` | yes: challenge performance (A/B reps vs target) after general sessions with 2-6 such sets |
| `elbow_flexor_heavy` | >= 3 hard sets credited to `biceps`, or any exercise with `jointStress.elbow_medial >= 2` | yes: elbow check scores (F15) after such sessions |
| `grip_heavy` | >= 2 hard sets of exercises with `gripDemand >= 2` | yes: grip slips (`grip_slip_before_last`) in the next challenge session |

The tags decide the gap rule and the windows. On a challenge gap day, pull work is additionally
limited by the C50 light-pull filter (section 8.4), so a general session can be untagged and still
exclude, for example, lat pulldowns.

### 8.4 Blocked days

Derived from the hard constraints and the week plan:

| Source | Blocked day | Scope |
|---|---|---|
| `restDayBefore` | The day before each B or TEST | `all_gym` (a run is a warning only, C11) |
| `minGapDays` | The days adjacent to each challenge session that carries one of `appliesToTags` | `{tags: [pull_heavy, grip_heavy, elbow_flexor_heavy]}`: general sessions without these tags are allowed (D5), including on the day before A (D15) |
| Mode effects | Injury: none extra (the forbidden tags apply every day) | |

D5 also fixes: never a gym session the day before session B or a test day; never heavy grip or
elbow-flexor work in protected windows; default maximum gym visits per week 4 (a `weekplan` setting
that counts challenge sessions too).

Gap-day policy (D15, C50), exported in `generalSessions` and applied by `weekplan`, `allocator`,
`elig` and `select`:

1. A general legs/push session may be planned on any challenge gap day, including the day before A.
   There is no systemic (whole-body) penalty term in v1. The 24 h hard window before A (no lats,
   biceps group, forearms, grip-heavy or elbow-flexor-heavy work) still applies to the part of that
   session that falls inside it (C12, section 8.5).
2. One gym visit per day: on a challenge day, extra general work is only possible as an add-on block
   of the challenge session (section 8.6).
3. Light pull work on a gap day is allowed only for exercises with `grip_demand <= 1` and a biceps-group
   coefficient below 0.5, outside protected windows (for example a reverse pec deck), and only when
   the user's goals ask for it. Other exercises crediting pull-family groups are excluded on gap days
   even when the session's tags would allow them.

### 8.5 Protected windows (C12)

Windows are converted to `Instant` intervals with the `clock` module (C31): `before`:
`[slotStart - hours, slotStart)`, `after`: `[slotEnd, slotEnd + hours)`. Using instants keeps a
24-hour window 24 real hours across the DST change of 2026-10-25 (wall-clock arithmetic would give 25
hours).

| Mode | Consumer obligation |
|---|---|
| `hard` | `weekplan`, `select` and `injury` place no exercise credited to the listed groups or regions, and no session carrying the listed session tags, inside the window; with `scope: all_gym`, no gym session at all. A user who logs such work anyway gets a warning, logged as `rule_override {rule_id, enrollment_id, action, exercise_id?}` with the window's id (DATA_MODEL 9.8.1). |
| `penalty` | The planner adds a penalty when predicted readiness (MuscleReadiness, C5) of a listed group at the slot start is below `rMin`, or of a listed tissue below `tissueRMin` (planning lens: -5 x deficit). There is no systemic (whole-body) readiness term in v1 (D15, C50); windows have no `systemicRMin` field. |

OAP defaults (C12): hard 24 h before A for lats, biceps (incl. brachialis) and forearms, plus grip-
and elbow-flexor-heavy sessions; hard 24 h all-gym before B and TEST; hard 24 h after B for elbow
flexors (proposal P23); penalty windows 30 h before A and 48 h before B/TEST with `rMin` 0.85 and
elbow tissue 0.90. The windows' hours and `rMin` values are assumptions (critique UNSUPPORTED):
tunable, tuned by challenge performance (reps and RIR versus target in A/B/TEST) after general
sessions at different distances.

### 8.6 Add-on slots (C34, D5)

A challenge session can carry a general block at its end:

- Capacity = `addOnCapacityMin` from section 6.9 (booked minutes - TIME-1 estimate - 5 min, C49);
  recomputed live during the session from the actual elapsed time. Below the `time` module's minimum
  add-on length no add-on is offered (ALGORITHMS_PLANNING 17.6).
- This is the only way to add general work on a challenge day (one gym visit per day, C50).
- Built by SEL-1 at L6 with: budget = capacity; `allowPatterns`; `forbidGroups` (OAP: the owned
  muscles lats, biceps, forearms); `forbidSessionTags`; exercises with `gripDemand > maxGripDemand`
  excluded; at most `maxSets` sets (OAP: 6 after C, 4 after A); every set at RIR >= `minRir` (2); no
  general warm-up (specific warm-up sets only, C34).
- B and TEST have no add-on slot (OAP: B already contains rows and dips).
- The add-on block is a `general_block` in the same `workout_sessions` row; its own recommendation
  snapshot and rest timers; its sets never count for challenge rules (`valid()` reads only challenge
  item keys).
- Overrun (session longer than budget) is reported back to `time` for calibration; the Live Session
  shows its overrun line when the session is more than 10 min over `budgetMin` (C63).

### 8.7 Conflicts between two active challenges

Enabling a second challenge combines the contracts: union of reserved sessions, blocked days and
windows; both gap rules apply; `maxPerWeek` tags count across both. The enable check (section 10.4)
blocks the second challenge when (a) the combined required gym sessions exceed the user's maximum gym
visits per week, or (b) a feasible week plan does not exist for a typical week, and warns when (c)
owned muscles overlap or (d) heavy-unilateral counts add up beyond a definition's cap.

### 8.8 Engine constants (contract)

| Key | Default | Grade | Tunable | Note |
|---|---|---|---|---|
| `challenge.contract.window_penalty_weight` | 5.0 | expert-opinion | yes | Planner penalty per unit readiness deficit in penalty windows (planning lens); tuned with the windows. |
| `challenge.contract.add_on_recompute_every_set` | true | engineering-fact | no | Live capacity update during the session. |
| `challenge.contract.gap_day_pull_max_grip_demand` | 1 | engineering-fact | no | Tag D: C50 light-pull filter on gap days. |
| `challenge.contract.gap_day_pull_biceps_coef_below` | 0.5 | engineering-fact | no | Tag D: C50; strict inequality (a coefficient of 0.5 is excluded). |

---

## 9. Calendar matching and write-back (`challenge.calendar@1.0.0`)

### 9.1 Division of labour

| Concern | Module |
|---|---|
| Reading provider rows, permissions, observers, executing writes with guards | `:app` calendar executor (ANDROID.md 16) |
| Normalizing titles, classifying every instance into `{challenge_session(slot), planned_gym(type), run(intensity, minutes), busy, ignored}`, detecting markers, applying the challenge title patterns supplied by active definitions | `calendar.classify` (core, C20) |
| Assigning classified events to this week's challenge slots, C36 slot-type changes, deciding which app-owned events to create, update or delete, adopt and rename prompts, rendering and parsing the marker | `challenge.calendar` (this section) |

Only the primary calendar is written (D11). All-day events and calendars named like Todoist are
ignored by the classifier (D11).

### 9.2 Normalization and patterns

```
normalize(s) = s |> NFKC
                 |> lowercase(Locale.ROOT)
                 |> NFD, remove combining marks (U+0300..U+036F)     // "één" -> "een"
                 |> replace [U+2010..U+2015, U+2212] with "-"         // em dash "—" -> "-"
                 |> collapse whitespace to one space, trim
```

Patterns are Java regular expressions (`java.util.regex`, flags none; the input is already
lower-case) applied with `find()` to the normalized title. The OAP patterns, for example
`\bsessie c\b|\bsession c\b|volume en techniek|volume and technique`, match the user's existing
titles "Gym — sessie C (volume en techniek)" and "Gym — sessie A (één arm zwaar)" (normalized
"gym - sessie a (een arm zwaar)"), and "Gym — testdag 1 (meten)" for TEST (D12: Dutch titles are
recognised).

### 9.3 Slot assignment per ISO week

Edges between events of the week (window: the ISO week, Monday 00:00 to next Monday 00:00 local) and
the week's required slots:

| Evidence | Weight | Note |
|---|---|---|
| Marker with this enrollment's id and the slot key `<slug>/<slot>/<isoYear>-W<ww>` of this slot and this ISO week (C37) | 1.0 (fixed edge, wins) | The marker is authoritative. |
| Title matches `patterns[slot]` | 0.6 | Required for any non-marker edge. |
| Gym evidence: title contains a gym keyword (classifier list, for example `gym`, `basic-fit`, `sportschool`) or the location matches the gym profile | +0.2 | |
| `abs(duration - template.bookedMin) <= 45 min` | +0.1 | |
| Weekday equals the slot's preferred weekday | +0.05 | |
| Start within gym opening hours | +0.05 | |
| Edge via `slotAliases` (for example a "sessie B" event for the TEST slot in a test week, or a "testdag" event for B when the test is postponed) | weight of the aliased pattern minus `alias_penalty` (0.1) | Sets `slotTypeMismatch` (C36). |

Accept an edge when its weight >= `challenge.calendar.accept_score` (0.6). Capacity: each required
slot of the week (from `slotsPerWeek[mode]`) can take one event. The assignment is the maximum-weight
bipartite matching, computed by brute force (at most about 10 events x 4 slots); ties go to the earlier
start, then the lexicographically smaller `instanceKey` (DATA_MODEL 9.7.3 `instance_key`, stable
across devices). Manual corrections ("this event is session A", stored per event, C20) override the
matching; the user can undo them.

Entry point used by `calendar.classify` (ALGORITHMS_PLANNING 1.4, stage B):

```
challenge.calendar.matchSlots(enrollment: EnrollmentView, candidates: List<Anchor>) -> List<SlotMatch>
  EnrollmentView = { enrollmentId, slug, isoWeeks[], slotsPerWeek[isoWeek] (mode of that week),
                     patterns, slotAliases, preferred[], gymHours, manualOverrides }
  Anchor         = the classifier's gym candidates and marked events of those weeks
                   (instanceKey, beginUtc, endUtc, normalized title, location match, parsed marker?)
  SlotMatch      = { instanceKey, isoWeek, slot, weight, method: marker | title_pattern | manual | adopted,
                     attention: null | slot_mismatch }      // attention values of DATA_MODEL 9.7.3
```

The function is pure, owns the per-week slot capacity and the B/TEST aliasing, and returns
`attention = slot_mismatch` for alias edges (C36). Candidates it does not assign stay `planned_gym`.

Results: `SlotMatches {slot -> eventRef, weight, method: marker | title_pattern | manual | adopted, slotTypeMismatch}`
(method values as DATA_MODEL E23);
unmatched required slots go to the planner; gym events that match no slot (for example a second
"sessie C") stay `planned_gym` for the general engine; matched events that violate a hard constraint
(for example A and B on consecutive days) produce warnings, never automatic moves of unmarked events.

Example (real week 2026-W41): "Gym — sessie C (volume en techniek)" Tue 19:45-21:15 -> C, weight
0.6 + 0.2 + 0.1 = 0.9; "Gym — sessie A (één arm zwaar)" Thu 19:45-21:15 -> A, 0.9; "Gym — testdag 1
(meten)" Sun 13:00 (120 min) -> TEST, 0.6 + 0.2 + 0.1 + 0.05 (Sunday) + 0.05 (gym hours) = 1.0 if
the duration is within 45 min of 120, else 0.9; the stale all-day Todoist "Gym" items are ignored.

### 9.4 Write-back (D11)

**Scope.** Challenge sessions and test days of the **current and next ISO week**
(`writeBack.horizonIsoWeeks` = 2), in the primary calendar, when `writeBack.mode = auto` and
`WRITE_CALENDAR` is granted. The app never writes outside this horizon automatically, never edits or
deletes an event without its marker, and never touches past events. The `sandbox` build flavour has
calendar writes disabled (F27): it computes and shows the write plan but never executes it against
the user's calendar.

**Desired events.** From the two week plans (section 7; next week projected): every required,
not-yet-done slot with a date and time yields a desired event:

| Field | Value |
|---|---|
| Title | `writeBack.titleTemplate` (or `testTitleTemplate` for the test slot) in the enrollment's title locale (`calendar.titleLocale`, default from `writeBack.titleLocaleDefault`; OAP: `nl`, matching the user's existing events, F25), with `{slot}`, `{slotTitle}` and `{testNo}` (= the block index of the test) filled. Example: "Gym — sessie C (volume en techniek)", "Gym — testdag 2 (meten)". The description lines are English (F25). |
| Start / end | The planned local date and time in the profile zone converted to UTC (`ZonedDateTime.of(...).toInstant()`); end = start + `bookedMin` (or the matched duration). `EVENT_TIMEZONE` = Europe/Amsterdam. |
| Location | The gym profile's location string. |
| Description | In English (F25): one line per planned item from the materialized session (for example "Weighted pull-ups 4 x 4 @ +22.5 kg (20 + 2.5)", "Assisted one-arm pull-up 5 x 3 per arm, pin 40 lb (about 24.6 kg help)"), a blank line, the definition's `humanLine`, then the marker line as the last line. |
| Marker | The C37 format, `[fitapp:v1 <uuid> <slotKey>]`: `<uuid>` = the **enrollment id**; `<slotKey>` = `<slug>/<slot>/<isoYear>-W<ww>` (for example `oap/C/2026-W42`, `oap/TEST/2026-W46`). General "Plan it" events use the same format with the recommendation id and `gen/<isoYear>-W<ww>/<isoDow>`. Slot keys match `[A-Za-z0-9_./-]{1,64}` (C37; upper-case slot letters are allowed). Parse regex, applied to the last non-empty description line (the same text as the DATA_MODEL 9.7.3 `calendar_events.marker` CHECK and `calendar.classify`, ALGORITHMS_PLANNING 1.4 and ANDROID 16.5; one shared implementation in `dev.fitnessapp.algo.core.calendar`, C68): `^\[fitapp:v1 ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) ([A-Za-z0-9_./-]{1,64})\]$` (enrollment and recommendation ids are device-generated UUIDv7, DATA_MODEL 6.4, but the parser does not depend on the version digit). The pair (uuid, slot key) identifies one event; the local `calendar_link` row (ANDROID.md 16.6) maps it to the provider event and the last-written values. |

**Diff.** The write plan compares desired events with `calendar_link` rows and the matches. The edit
policy is owned by ANDROID.md 16.7 (C37): user time edits win; a user-edited title or description
stops app updates for that event; the app deletes only its own unmodified future events; user
deletions are respected. The rows below apply that policy:

| Situation | Operation |
|---|---|
| Slot has no matched event (marked or unmarked) in that week | `insert` with a new link (`active`). |
| Slot matched by a marked event, link `active` | `update` times, title and description (times only if they still equal the last-written times; the marker line is always kept). |
| Slot matched by a marked event, link `user_moved` | The user's time wins: no time change, never moved back; title and description are still updated. |
| Slot matched by a marked event, link `user_edited` | The user edited the title or description, so the app stops updating that event; content changes (for example a C36 slot-type change) become a one-line "Rename?" prompt. |
| Marked future event whose slot is no longer required in that week (pause, abandon, completion, structural change) | `delete` only if the link is `active` (unmodified) and `DTSTART > now`; otherwise a one-tap prompt "Remove ... from your calendar?". |
| Slot matched by an **unmarked** event | No insert (no duplicates). Offer "Adopt" (section 9.5). |
| Link `user_deleted` | Do not re-create that slot for that week unless the user taps "Add to calendar". |

The plan is a pure function of its inputs, so re-running it after the executor applied it yields no
operations (idempotence; the provider's own change notifications produce identical inputs). The
executor applies one plan in one `applyBatch` and refuses any operation that violates its guards
(marker guard, week guard, past guard; ANDROID.md 16.7).

### 9.5 Adopt flow

Events the user (or Claude, before the app existed) created by hand carry no marker. When such an
event is matched to a slot, the Week tab and setup step 7 show "Adopt". On tap the executor appends
"\n\n" + marker (enrollment id and the matched slot's key, C37) to the existing description with the
atomic guard "description has no fitapp marker" (the user's text is kept). From then on the app owns title and description and the user
owns the time (ANDROID.md 16.7). Adopting is per event; the app never adopts on its own.

### 9.6 Test postponement and slot-type changes (C36)

Challenge state wins for the slot type:

1. On `test.due` with `needsCleanSession` (OAP `struct.test.postpone`): the planned TEST becomes the
   slot `postponedSlotBecomes` (B, deload form) on that day, and the test moves to the first test
   weekday after a clean session.
   - Marked TEST event that day, link `active`: the write plan updates its title and description to
     session B ("Gym — sessie B (maximale kracht)"); the Week tab shows an info line.
   - Marked event with a user-edited title: prompt "Rename to session B?".
   - Unmarked TEST event: prompt "Your calendar says test day; your challenge says session B today
     (no clean session yet). Adopt and rename / Keep as is".
2. When the new test date falls inside the horizon, a TEST event is created (or an existing B event of
   that week is rewritten to TEST, same ownership rules).
3. The reverse case (a week becomes a test week through block-relative dates while a marked B event
   exists) rewrites B to TEST.

A slot-type rewrite also rewrites the marker's slot key (for example `oap/TEST/2026-W41` ->
`oap/B/2026-W41`) in the same description update; the executor's atomic guard selects the event by
its old marker, so a user who deleted the marker in the meantime is respected (ANDROID.md 16.7).

### 9.7 Conflict handling

| Case | Behaviour |
|---|---|
| User moved an app event | Link `user_moved`; the new time is a fixed anchor for the planner; never moved back; hard-constraint violations become warnings on the Week tab (rule id and plain text). |
| Two events match the same slot | The higher weight wins; the other becomes `planned_gym` and the Week tab flags the duplicate. |
| Event in a read-only extra calendar | Matched like others; never written; no adopt (adopt requires the primary calendar). |
| `WRITE_CALENDAR` missing or revoked | The write plan is computed and shown ("Not in your calendar"), not executed; banner with a one-tap permission request (ANDROID.md 16.2). |
| Calendar unavailable | Degraded mode: the planner plans without anchors; banner; nothing written. |
| Calendar sync lag | Writes go to the provider immediately; the Google sync adapter uploads later; nothing to do. |

### 9.8 Logging

`session_events` with the DATA_MODEL 9.8.1 registry names and payload keys (C39; this section adds
no names): `calendar_classified` (C20; privacy rules of ANDROID.md 16.4), `calendar_event_written`
(per executed write: `op`, `kind`, `instance_key?`, `marker_key` = the C37 slot key, `cause`,
`provider_rows`; no `result` key, since only executed writes produce it), `calendar_write_failed`
(refused or failed op, with `error_class`, for example `marker_missing`), `calendar_event_adopted`, `calendar_correction` (user corrections of a match or a
classification), `calendar_conflict_resolved` (the answer to a C36 rename or adopt prompt),
`calendar_unavailable`; the week plan and the write plan are snapshotted in `recommendations`.

### 9.9 Engine constants (calendar)

| Key | Default | Bounds | Grade | Tunable | Tuned by |
|---|---|---|---|---|---|
| `challenge.calendar.accept_score` | 0.6 | 0.4-0.9 | expert-opinion | yes | Manual corrections (false positives and negatives) in `calendar_correction` events. |
| `challenge.calendar.w_pattern` | 0.6 | | expert-opinion | yes | Same. |
| `challenge.calendar.w_gym_evidence` | 0.2 | | expert-opinion | yes | Same. |
| `challenge.calendar.w_duration` | 0.1 | | expert-opinion | yes | Same. |
| `challenge.calendar.duration_tolerance_min` | 45 | 15-90 | expert-opinion | yes | Same. |
| `challenge.calendar.w_pref_weekday` | 0.05 | | expert-opinion | yes | Same. |
| `challenge.calendar.w_gym_hours` | 0.05 | | expert-opinion | yes | Same. |
| `challenge.calendar.alias_penalty` | 0.1 | 0-0.3 | expert-opinion | yes | Same. |
| `challenge.calendar.confirm_below_score` | 0.8 | 0.6-1.0 | expert-opinion | yes | Matches below this show "Is this session C?" once (critique). |

---

## 10. Challenges tab behaviour

The screens are specified in UX.md section 14; this section fixes the engine behaviour behind them.

### 10.1 Library and dashboard

- The library lists active enrollments, then published definitions the user can start (with an
  equipment check: every `equipment.required` id present, or "Needs: ..."), then past enrollments.
- The dashboard reads `EnrollmentState` (status, mode, phase, block, week, next test and its basis),
  the current week plan, and gate progress: one row per criterion of the current phase's exit gate,
  per side where the metric has sides, using only observations that are valid for gates; the latest
  non-valid observation is shown separately with its reason. The strength gate row shows the D7 gate
  value with its `k`, the x1.10 value and the 80 % interval, and the SSE-1 training estimate (C4),
  each labelled.
- Gate threshold basis (C62): a criterion is evaluated, and its met/not-met result stored, on the
  test-day bodyweight (`t.bw`, the raw weigh-in, C32). The engine exposes per criterion and side the
  stored result `met` and a pure `thresholdAt(bwKg)` evaluator, so the dashboard can also show the
  forward threshold at the current BW(t) (for example 0.65 x 70.7 - 2.0 = 43.96 kg), labelled with that
  bodyweight. UX 19.7.3 calls this per-criterion view `GateCriterion` (`met`, `thresholdAt(bw)`).
  Display and bar logic belong to `ui.progress` (ALGORITHMS_PLANNING 19.6, UX 19.7.3; C53); a stored
  `met` is never re-evaluated at a later bodyweight.

### 10.2 Enable wizard

The wizard renders the definition's `onboarding.steps` in order, adding the app-level steps
"gate method" (D7 information), "calendar" (D11) and "cutover" (D4). Each step saves locally; the
enrollment is created only at the end.

| Step | Engine behaviour |
|---|---|
| Overview | Shows `summary`, `challengeRules`, the provenance evidence note and the safety summary with the not-medical-advice disclaimer (C33). |
| Rules / params | User-editable params (`userEditable: true`), for example `grip`. Stored in `challenge_enrollments.params`. |
| Equipment | Confirms the calibration records bound by `equipment.required[].binding` (machine `{unit, step, min, max, offsetKg, ratio}`, belt plate inventory). Missing optional equipment excludes the items it unlocks. |
| Starting point | Three modes: import current state (D4, validated against `importSpec`), enter known values (baseline fields), or schedule a baseline test (materializes the test template as the first session). |
| Gate method | Information only (D7): the gate uses the personal curve; no extra single is added to test days. |
| Schedule | `schedule.preferred` per slot, habit times, reminders. |
| Calendar | Permission request (READ + WRITE together); shows this week's matched events with "Adopt"; title locale (default from the definition, Dutch for OAP, F25). |
| Proposals | Every proposal listed individually with title, rationale, what happens if declined, recommendation and evidence; Continue only when every proposal has a decision (D8). Each decision is one per proposal and applies to every rule the proposal switches (C48). |
| Review | The current and next ISO week materialized under the chosen decisions; contract warnings (for example the D5 gap-day rule in plain words). |
| Cutover | Confirmation checklist (D4). "Start" writes `enrollment.created`, then one `rules.approval_changed` per proposal, in that order, with the same `occurred_at` base and increasing ids. |

### 10.3 Proposal decisions after start

The Proposals screen (dashboard menu) lets the user change any decision at any time (D8). A change
writes `rules.approval_changed` and takes effect for later events. The screen shows, per proposal,
which rules it switches, which A rule it replaces, and how often those rules fired.

### 10.4 Starting a second challenge

Before setup of a second definition, `challenge.contract` runs the conflict check of section 8.7 on a
typical week (the preferred schedule of both). Blocking results stop the setup with the reason;
warnings are shown and must be acknowledged.

### 10.5 Pause, resume, abandon, restart

| Action | Engine effect | Preview shown before confirming |
|---|---|---|
| Pause (reason chip) | `pause` event; section 4.5. | "Back within N days: ..." computed by dry-running the definition's `resume` rules for gaps of 1, 7 and 28 days (only approved proposals). |
| Resume | `resume {gapDays}` event; definition rules decide. | The consequence for the actual gap (dry run). |
| Abandon | `abandon` event; future app-written events removed. | Confirmation dialog. |
| Restart | New enrollment, import seeded from the last snapshot of the abandoned one; proposals decided again. | Setup wizard with prefilled values. |

### 10.6 Test day

The test-day wizard runs the test template's items in the materialized order, which is the order the
definition gives (section 6.3 step 13; C65); it is a `kind = test` session (C34). The template must
contain every item a gate criterion of the current phase needs on a test day (V07): for OAP this
includes the unassisted 5 s one-arm negative per arm (metric `oa_neg5_unassisted`, gate criterion 3),
which F10's list does not name. Entries use the "Record" action, warm-ups "Done", and a 3RM attempt
is stored with RIR 0 (C63). On submit it writes metric observations (with `protocol_code`, side, attempts,
`valid_for_gate` = the template's `validity` result) and a `test.completed` event; derived metrics
(D7 gate value) are computed once and stored with `calc_params` (C3). The recalculation report
(old -> new loads and pins, with each rule's explanation) is shown from the resulting audit rows.

---

## 11. Safety and stall protocol hooks

### 11.1 Safety (C33, D10)

1. `safety` turns every pain report and readiness check into a `PainEvent` -> `SafetyState` (joint
   flags, forbidden tags) before anything else (L1). The pain sheet offers the symptom codes of
   `fitness.symptom_codes`; a challenge only decides which codes are its stop signals (rule data).
2. For every active enrollment, `:app` forwards the event as `pain.reported` / `readiness.checked`
   with the symptom codes, location, side and elbow score (F15: 0-3), plus `engineFacts`.
3. The definition's safety-stage rules set the challenge mode (OAP: `injury` on any of the three
   elbow stop signals; an elbow trace >= 1 skips eccentric items and the daily hang).
4. The engine guarantees, independent of the definition: injury cannot be left by a manual override;
   progression proposals of an evaluation that enters injury are suppressed; `stop` notifications
   are shown full-screen; templates with `requiresReadiness: ["elbow"]` cannot start without the
   elbow check (F15: never reduced while a challenge pain rule is active).
5. The contract exports the injury mode's `forbidSessionTags`, `forbidExerciseTags` and `jointFlags`
   to ELIG-1, INJ-1 and the planner, so general curls and grip work are blocked as well (critique gap
   "pain safety is split").
6. Texts say "not medical advice"; persistent pain leads to "consider a physiotherapist" (OAP P14).
7. Load-jump caps are the one D10 safety rule that does not bind challenge RULE-mode loads (D13,
   C45): a jump above the generic cap is applied as written and raises WE-S2 at WARN severity
   (section 6.6). The pain protocol, no failure on high-risk lifts (WE-S1) and the protected windows
   keep binding the challenge.

### 11.2 Stall protocols

Definitions express stall handling as test-day rules with a derived `progress` fact comparing `t.*`
with `pt.*` (OAP: `struct.stall`, `d.testProgress`). The engine supplies `pt` (the previous valid
test) and the metric comparability rule: observations with a different `protocol_code` are never
compared (for example the 2026-08-30 one-arm hang under the old protocol). The general STALL-1 module
does not act on challenge items (RULE mode, C15); its global reactive deload triggers (C13) read the
contract and do not stack an extra deload on top of a challenge deload week (F8).

### 11.3 Habits as safety levers

Habits declare `disabledInModes` and `skipIf` (OAP: the daily hang is the first thing dropped on elbow
symptoms). Muted habits do not send reminders; the dashboard shows why.

---

## 12. Engine constants index

All engine constants live in the versioned params JSON (bundled at
`algo/src/main/resources/fitness/params/`, C40) under the keys listed in the per-module tables, written
`<module>.<constant>` with lower snake_case constant names (C41, DATA_MODEL E53): FitExpr limits
(3.11), enrollment (4.14), ECA (5.10), planner (7.8), contract (8.8), calendar (9.9).
Definition constants (thresholds, steps, offsets, proposal parameters) live in the definition's
`params` with tag, grade, source and tuning notes (the OAP values are tabulated in OAP_CHALLENGE.md).
No challenge constant may be hardcoded in Kotlin; a test scans `:algo` for numeric literals in the
`challenge` package outside an allow-list (0, 1, array indices).

---

## 13. Logging and review surface

| What | Where | Used for |
|---|---|---|
| Every input event with payload and engine facts | `challenge_events` | Replay; counterfactuals. |
| Every rule with a true or erroring condition | `challenge_rule_events` | Fire/suppress/error counts per rule and week; threshold counterfactuals from bindings. |
| Materialized sessions, week plans, contracts, write plans | `recommendations` (C24) | Plan versus actual; prescription error. |
| Logged rows with flags, reasons, pins, assist kg, hold chips, timestamps | `set_logs` | Rule inputs; TIME-1 calibration; asymmetry. |
| Test results with validity and protocol | `metric_observations` | Gates; stall; recalculation outcomes. |
| UI and platform events, with the DATA_MODEL 9.8.1 registry names only (C39): `challenge_setup_step`, `challenge_proposal_viewed` / `challenge_proposal_decided` (C48), `challenge_cutover_confirmed`, `challenge_rule_viewed`, `challenge_rule_applied` / `challenge_rule_undone` (auto-applied in-session rules and their Undo, D14, C46), `override_applied` ("Add a set", C63), `rule_override` (logging over a hard window), `warning_shown` (WE-S2 on RULE loads), `prompt_shown` / `prompt_answered` / `prompt_skipped` (absence prompt), `test_submitted`, `calendar_event_written`, `calendar_write_failed`, `calendar_event_adopted`, `calendar_correction`, `calendar_conflict_resolved`, `tick_catchup` | `session_events` | Friction, Undo rates per in-session rule, acceptance rates, tuning of calendar and planner weights. |

Review questions the data must answer (per definition version): How often did each rule fire, and
how often was it suppressed and why? How often was each auto-applied in-session rule undone (D14)?
How often did a RULE load exceed the generic cap (WE-S2, D13)? After each load change, what were reps and RIR in the next two
sessions (over- or undershoot)? How many weeks were repeated, how many tests postponed and by how
many days? Which constraints forced relaxations? How often did users move app-written events (planner
weights)? The `:sim replay` CLI answers "what if" questions by replaying the exported events under a
candidate definition or params version and diffing trajectories (section 16).

---

## 14. Golden fixtures and tests

### 14.1 Shared fixture: the real week

All fixtures below use this input unless stated otherwise.

| Item | Value |
|---|---|
| Now | Wed 2026-10-07 12:00 Europe/Amsterdam (CEST, UTC+2) |
| ISO weeks | 2026-W41 = Mon 2026-10-05 .. Sun 2026-10-11; W42 = 10-12 .. 10-18; W43 = 10-19 .. 10-25 (DST ends Sun 2026-10-25 03:00 CEST -> 02:00 CET, instant 2026-10-25T01:00:00Z); W44 = 10-26 .. 11-01 |
| Calendar (primary, unmarked, created by hand) | "Gym — sessie C (volume en techniek)" Tue 2026-10-06 19:45-21:15; "Gym — sessie A (één arm zwaar)" Thu 2026-10-08 19:45-21:15; "Gym — testdag 1 (meten)" Sun 2026-10-11 13:00-15:00; stale all-day "Gym" items in a Todoist calendar; chess Monday evenings (busy) until 2026-11-30 |
| Enrollment (OAP import, D4) | phase 1, block 1, block week 5, mode `test_pending`, weekOrdinal 6, plannedTest 2026-10-11 (basis postponed from 2026-10-04), `needsCleanSession` true |
| State vars | wpuC 20 kg, wpuA 22.5 kg, wpuB 27.5 kg, negB 47.5 kg, negReset 45 kg, pinA 40 lb, pinC 50 lb, pinHold90 35 lb, sthS 6 s, plankS 20 s, plankLoadKg 0 kg, oaHangS 10 s, kgPerRep 2.1667 kg |
| Bodyweight | 70.7 kg (EWMA and test-day raw) |
| Machine calibration | `{unit lb, step 5, min 0, max null, offsetKg 6.5, ratio 1.0}` (F7, C2) |
| Belt load profile | Achievable added loads on a 2.5 kg grid from 0 to 60 kg (assumed inventory 20, 15, 10, 5, 2.5 kg plates x2; the real inventory is asked at setup) |
| Engine facts | `userK('weighted_pullup')`: k 30.0000, sd_lnk 0.3000 (class prior: no informative RCM-1 window yet) |

### 14.2 Fixture list

| Id | Module | Input | Expected |
|---|---|---|---|
| GF-CE-01 | fitexpr | `e1rmTotal(70.7kg + 32.5kg, 3, 30) - 70.7kg` | `39.38 kg` (exact decimal 103.2 x 32/30 = 110.08) |
| GF-CE-02 | fitexpr | `userKQuantile('weighted_pullup', 0.1)`, `(…, 0.9)` with k 30, sd 0.30 | `20.4244`, `44.0649`; the corresponding e1RM added `+42.61 kg` and `+37.18 kg` |
| GF-CE-03 | fitexpr | `(70.7kg + 32.5kg) * 1.10 - 70.7kg` | `42.82 kg` (the x1.10 display value) |
| GF-CE-04 | fitexpr | `0.65 * 70.7kg - 2.0kg` | `43.955 kg` (stored 43.96 at the kg grid) |
| GF-CE-05 | fitexpr | `assistKg(40lb)`, `oneArmLoad(40lb)`, `assistKg(35lb)`, `assistKg(50lb)` | `24.64 kg`, `46.06 kg`, `22.38 kg`, `29.18 kg` |
| GF-CE-06 | fitexpr | `roundLoad(22.1667kg,'belt','nearest')`, `roundLoad(26.5kg,'belt','nearest')`, `roundLoad(48.5kg,'belt','down')`, `roundLoad(44.75kg,'belt','down')` | `22.5`, `27.5` (tie between 25 and 27.5 is impossible here: 26.5 is 1.0 from 27.5), `47.5` (20+20+5+2.5), `42.5` |
| GF-CE-06b | fitexpr | LOAD-1 delegation (OI-A06): `roundPin(37.5, 'nearest')`, `roundLoad(23.75kg,'belt','nearest')`, `roundLoad(-1kg,'belt','down')` | `40 lb` (tie goes to more assistance, C2), `22.5 kg` (tie goes to the easier load), `0 kg` (LOAD-1 `easierOrEqual` returns the smallest achievable value, no error) |
| GF-CE-07 | fitexpr | `ex.wpu.reps >= 2 && true` | compile error `type` (List<Bool> needs all()/any()) |
| GF-CE-08 | fitexpr | `s.pinA - 2.5kg` | compile error `type` (Pin minus Mass) |
| GF-CE-09 | fitexpr | `today - 14d + 7d` with today 2026-10-21 | `2026-10-14` |
| GF-CE-10 | enrollment | Tick synthesis, lastTickDate 2026-10-06, today 2026-10-07 | exactly one event `day.tick:2026-10-07`, occurred_at 2026-10-06T22:00:00.002Z |
| GF-CE-11 | enrollment | Tick synthesis 2026-10-08 .. 2026-10-12, plannedTest 2026-10-11 | `day.tick` x5; `test.due:2026-10-11` (2026-10-10T22:00:00.003Z); `week.closed:2026-W41` at 2026-10-12T02:00:00.000Z (04:00 CEST); `week.started:2026-W42` at 2026-10-12T02:00:00.001Z |
| GF-CE-12 | enrollment | Tick synthesis 2026-10-24 .. 2026-10-26 (DST) | `day.tick:2026-10-25` once (2026-10-24T22:00:00.002Z) and `day.tick:2026-10-26` (2026-10-25T23:00:00.002Z); `week.closed:2026-W43` at 2026-10-26T03:00:00.000Z (04:00 CET) |
| GF-CE-13 | enrollment | Two devices synthesize `day.tick:2026-10-25` | identical UUIDv5 ids; one stored row |
| GF-CE-13b | enrollment | Synthesis at Mon 2026-10-12 01:00 CEST (2026-10-11T23:00Z), then again at 05:00 CEST | First run: `day.tick:2026-10-12` only; no `week.closed:2026-W41` (its time 02:00Z is in the future). Second run: `week.closed:2026-W41` and `week.started:2026-W42`; no duplicate day tick |
| GF-CE-14 | enrollment | `struct.week.close` in mode `extra_deload` with `advanceWeek` | `block.week` unchanged (mode does not count as a block week); mode `normal`; `weekOrdinal + 1` |
| GF-CE-15 | enrollment | Late `session.completed` for Tue C (offline) arriving after Thu A's events | refold, `fold_generation + 1`; final state identical to in-order delivery |
| GF-CE-16 | eca | B session: 6 x 2 at +27.5 kg, RIR [2,2,2,3,2,2], grip slip flagged on set 4, mode normal | `prog.gripSlip.lock` applied (lock noIncrease on wpuA/B/C); `prog.B.wpu` outcome `suppressed_lock`, wpuB stays 27.5; two audit rows |
| GF-CE-17 | eca | Synthetic definition: rules R1 (prio 50) and R2 (prio 40) both set `s.x` | R1 applied; R2 `suppressed_first_writer` with `suppressed_by = R1` |
| GF-CE-18 | eca | Synthetic definition: on one event, a safety rule enters injury and a progression rule adjusts a var | progression proposal `suppressed_injury` |
| GF-CE-19 | eca | Deload-week C (frozen): oaa reps per side [4,3] in pair 2; then the same in mode `test_pending` with P19 declined | `prog.C.pin.hard` applies (`allowInFrozen`): pinC 50 -> 55 lb; a synthetic progression rule without the flag writing a snapshot var is `suppressed_lock` with `suppressed_by = mode:deload`, and with `mode:test_pending` in the second case (C47: frozen even when `test_pending` inherits `normal`) |
| GF-CE-20 | eca | Proposal gating: `prog.A.pin.weekly` (A, supersededBy P01) and `prog.A.pin.gated` (P01) on the same A session, P01 approved, then declined | Only the gated rule is evaluated when approved; only the weekly rule when declined or undecided |
| GF-CE-21 | materialize | A1 in mode `test_pending` with P19 approved | oaa 3 pairs (5 -> 3), wpu 2 x 4 @ +22.5 kg, top 2 rounds, h90 2 pairs, sth 2 pairs, warm-up unchanged; loads and pins unchanged |
| GF-CE-22 | materialize | A1 normal; `has('wide_bar')` false; phase 2 C2 | typewriter item `excluded` ("needs: wide bar") |
| GF-CE-23 | materialize | B1 row item `anyOf` with preferences barbell `never`, cable `like`, chest-supported `neutral` | seated cable row chosen; if all `never`: barbell row with a conflict warning |
| GF-CE-24 | materialize | In-session A (pin 40 lb): first pair reps L 2 / R 4; then Undo | `ins.A.oaa.calibrate` applies automatically (D14): remaining pairs planned at 45 lb with `planned_source = challenge_rule`, card "Rule: ... · Undo", `challenge_rule_applied` logged; only this session's remaining rows change. Undo restores 40 lb for the remaining pairs, sets `session_exercises.user_override` with a reason code, logs `challenge_rule_undone` with the rule id, and the rule does not fire again on `oaa` in this session (C46) |
| GF-CE-24b | materialize | In-session A, P13 approved: pair 1 L 2 / R 4 (calibration +5 lb), pair 3 below target by the P13 margin | two different rules each apply once to `oaa`: 40 -> 45 lb after pair 1, 45 -> 50 lb after pair 3; no "one suggestion per exercise" limit (that C16 limit is for general items only) |
| GF-CE-25 | calendar | Match W41 events (14.1) | C <- Tue event (0.9), A <- Thu event (0.9), TEST <- Sun event (>= 0.9); Todoist all-day ignored; no inserts for W41; "Adopt" offered for the A and TEST events (C is in the past) |
| GF-CE-26 | calendar | Write plan W42 (projected: test on 10-11 valid, block 2 week 1, slots C, A, B at preferred times) | 3 inserts: C Wed 2026-10-14 19:45-21:15 CEST (17:45Z), A Fri 2026-10-16 19:45-21:15, B Sun 2026-10-18 11:00-12:30; titles "Gym — sessie C (volume en techniek)", "Gym — sessie A (één arm zwaar)", "Gym — sessie B (maximale kracht)"; markers with the enrollment id and slot keys `oap/C/2026-W42`, `oap/A/2026-W42`, `oap/B/2026-W42` (C37) |
| GF-CE-27 | calendar | Marker render and parse with enrollment id `0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b` and `oap/C/2026-W42` | `[fitapp:v1 0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b oap/C/2026-W42]`; round trip; upper-case slot letters parse; the general marker `[fitapp:v1 <recommendation id> gen/2026-W42/5]` parses with the same regex (C37) |
| GF-CE-28 | calendar | `test.due` 2026-10-11 with `needsCleanSession` true (A on Thu not clean) | mode `test_pending`; TEST slot on Sun becomes B; the unmarked "testdag" event gets a rename/adopt prompt, never an automatic edit; the next TEST is placed on the first Sunday after a clean session |
| GF-CE-29 | plan + contract | W42 plan (C Wed, A Fri, B Sun) | blocked days: Mon 10-12 and Tue 10-13 and Thu 10-15 for tags `pull_heavy, grip_heavy, elbow_flexor_heavy` (gap rule, general legs/push allowed, D5; Thu is the day before A and allowed too, D15); Sat 10-17 `all_gym` (rest before B). Hard windows: before A [2026-10-15T17:45Z, 2026-10-16T17:45Z) for lats/biceps/forearms and grip/elbow-flexor-heavy tags; before B [2026-10-17T09:00Z, 2026-10-18T09:00Z) all gym; after B (P23) [2026-10-18T10:30Z, 2026-10-19T10:30Z) for elbow flexors |
| GF-CE-30 | contract | A protected window before a B on Sun 2026-10-25 11:00 CET (10:00Z) | hard window [2026-10-24T10:00Z, 2026-10-25T10:00Z): starts Sat 12:00 CEST, exactly 24 real hours (wall-clock arithmetic would wrongly start at 11:00 CEST) |
| GF-CE-31 | cds | Validator negative cases: local exercise with coefficient 0.8; unknown region `core`; P rule without `p.approved_` guard; A rule with `supersededBy` whose proposal does not list it; `List<Bool>` in an `if` | V08, V08, V09, V09, V01 errors respectively |
| GF-CE-32 | cds | `oap.challenge.v1.json` | passes stages 1 and 2 once the catalog seed contains the referenced catalog slugs (C38) (the T1 `neg` position values are fixed, CE-OI-15); the definition already uses the DATA_MODEL codes (V18) |
| GF-CE-33 | sim | Personas of section 2.6 on OAP, 26 weeks, proposals all approved and all declined | snapshot files unchanged; global invariants hold |
| GF-CE-34 | materialize | C session after the rule lowered pinC 50 -> 45 lb (system load 41.52 -> 43.79 kg at 70.7 kg, +5.46 % against the 5 % compound cap) | pin 45 lb is prescribed as written (no clamp, D13); the item carries WE-S2 at WARN severity with its Why sheet; the RX-1 shadow output and `warning_shown` are logged (C45) |
| GF-CE-35 | materialize | C1 matched to a 90-min event, TIME-1 stubbed at 60.8 min; A1 matched to a 90-min event, TIME-1 stubbed at 79.7 min | C1: `budgetMin` 85, `addOnCapacityMin` 24; A1: `addOnCapacityMin` 5, below the `time` minimum, so no add-on is offered. Only one 5-min changeover is subtracted (C49) |
| GF-CE-36 | contract | W42 contract (C Wed, A Fri 19:45 CEST); general candidates on Thu 10-15 (day before A) | legs/push exercises allowed; no systemic penalty term (D15); lat pulldown (biceps-group coefficient 0.5) excluded by the C50 light-pull filter; reverse pec deck (grip demand <= 1, biceps-group coefficient 0) allowed only when the user's goals ask for it; nothing crediting lats, biceps or forearms inside [2026-10-15T17:45Z, 2026-10-16T17:45Z); Fri 10-16 offers general work only as the A add-on (one visit per day) |
| GF-CE-37 | fitexpr + dashboard | Phase-1 strength criterion evaluated on a test with `t.bw` 70.7 kg and gate e1RM +44.00 kg; later BW(t) 71.5 kg | `met = true` against 43.96 kg (test-day basis, C62) and stays true; `thresholdAt(71.5kg)` = 44.475 -> 44.48 kg (HALF_EVEN), shown as the forward threshold |
| GF-CE-38 | materialize + cds | OAP T1 with P08 and P20 declined; then T1 without the `neg` item | order: weigh-in, warm-up, 3RM, max reps, one-arm hang L/R, top hold L/R, least assistance L/R, unassisted 5 s negative L/R (C65); without `neg`, V07 fails for phase-1 gate criterion 3 |
| GF-CE-39 | cds | Local exercise with `flagsOffered: ["gripSlipBeforeLast"]` or `loadMode: "bodyweight_plus"`; the same with `grip_slip_before_last` / `bodyweight_plus_external` | V18 errors for the camelCase flag and the old load mode; the snake_case codes pass (C38) |

### 14.3 Property and determinism tests

- `parse(print(ast)) == ast` for generated ASTs; evaluation of the same AST and context twice yields
  byte-identical results.
- Fold of a random permutation of independent events sorted by `(occurred_at, id)` equals the fold of
  the original order.
- JCS hash of `EnrollmentState` stable across JVMs; no `HashMap` iteration in outputs.
- `:algo` lint test: no `android.*`, `java.time.Clock.system*`, `Instant.now`, `Random`, `Math.` (use
  `StrictMath`) in the challenge packages.

---

## 15. Open issues

| Id | Issue | Proposed handling | Status / needs |
|---|---|---|---|
| CE-OI-01 | ANDROID.md 16.5's marker parse regex allows only lower-case slot keys (`[a-z0-9_./-]`) but its own examples (`oap/C/2026-W42`, test G11) contain upper case. | Slot keys match `[A-Za-z0-9_./-]{1,64}` everywhere (section 9.4). | **Resolved by C37.** |
| CE-OI-02 | UX.md 10.4 names the general-session marker `fitapp:general:<recommendation_id>:<isoWeek>`, ANDROID.md 16.5 uses `[fitapp:v1 <uuid> gen/<isoYear>-W<ww>/<isoDayOfWeek>]`. | One format for both kinds: `[fitapp:v1 <uuid> <slotKey>]`, uuid = enrollment id or recommendation id. | **Resolved by C37.** |
| CE-OI-03 | DATA_MODEL 9.4.2 describes `rule_decisions` as keyed by rule id; D8 decisions are made per proposal (one proposal can switch several rules, for example P05 and P09). UX.md 14.4's example uses an older rule id (`prog.A.pin.fail`). | Key by proposal id (`P01` ...); audit rows carry `proposal_id` and derive `rule_decision` from it. | **Resolved by C48** (DATA_MODEL 9.4.2 / 9.7.2 and UX 14.4 now aligned). |
| CE-OI-04 | C16 limits suggestions to one per exercise per session, but the OAP A rule (first-set calibration) and P13 (later pairs) can both suggest on the same item. | Challenge in-session rules auto-apply with Undo, limited per rule per item; C16's limit applies to general items only (section 6.10). | **Resolved by D14 / C46.** |
| CE-OI-05 | F11 subtracted a 15-min changeover from a calendar slot; applied to challenge sessions it would cut D5's add-on room. | Budget = event duration - 5 min; add-on capacity = booked minutes - TIME-1 - 5 min; D5's figures are indicative (section 6.9). | **Resolved by C49** (F11 revised). |
| CE-OI-06 | D8's wording "executed exactly as written unless the user declines a P replacement" read inverted. | An A rule runs as written unless the P proposal replacing it is approved; undecided proposals are inactive. | **Resolved by C48** (D8 wording corrected). |
| CE-OI-07 | `progression: frozen` is an engine interpretation of the artifact's "keep the weight". | Frozen in deload, test_pending, extra_deload and injury, except `allowInFrozen` protective rules (sections 2.4.6, 5.5). | **Resolved by C47.** |
| CE-OI-08 | The catalog seed does not exist yet; the OAP definition references catalog ids (`chinup_weighted`, `neutral_grip_pullup_weighted`, `pullup_weighted`, `chinup_bw`, `neutral_grip_pullup_bw`, `pullup_bw`, `dip_neutral`, `barbell_row_pronated`, `seated_cable_row_wide_pronated`, `chest_supported_row_pronated`, `reverse_wrist_curl_db`, `db_pronation_supination`, `face_pull_cable`, `rower_erg`, `scap_pull_bar`). DATA_MODEL's UUIDv5 example `exercise:weighted_pullup` uses the definition key, not a catalog id. | The M1 catalog seed must contain every slug a challenge definition references; V08/V18 enforce it. | **Resolved by C38** (remaining work is the M1 seed itself). |
| CE-OI-09 | Whether the cutover import creates set rows that RCM-1 may fit. | No historical set rows; the first gate uses the class prior k = 30 (+39.38 kg) (section 2.4.15). | **Resolved by C59.** |
| CE-OI-10 | Language of app-written challenge titles (ANDROID OI-08, UX OI-09). | Definition title template, Dutch for OAP; descriptions in English (section 9.4). | **Resolved by F25.** |
| CE-OI-11 | Event ordering uses `(occurred_at, id)` with synthesized boundary events at fixed local times (`week.closed` Monday 04:00). DATA_MODEL documented the dedupe keys but not these times. | As specified in 4.3. | **Resolved by C41**: DATA_MODEL 9.7.1 lists the times with this document (4.3) as owner and spells the key `challenge.enrollment.week_close_local_time`. |
| CE-OI-12 | ALGORITHMS_PLANNING 0.5 describes `ChallengeContract` with other field names (`isDeloadWeek`, `hardRules[]`, `coverageCommitted`, `definitionSha256`, `bookedMinutes`, `capacityMinutes`, `templateTags`, mode `paused`) and reads two fields that 8.1 does not export: `intensityClass` (volume / heavy / max / test) and `materializedSets[]`. | Section 8.1 is the owner; ALGORITHMS_PLANNING 0.5 now says so and maps its names to it (its OI-25). This version adds `source`, `bookedMin`, `budgetMin` and `coverage.committed` and drops any systemic term (D15, C50); a paused enrollment exports an empty contract rather than mode `paused`. `materializedSets` can be served from the session plan of each reserved session; `intensityClass` needs a definition field or a derivation rule. | **Resolved by ALGORITHMS_PLANNING OI-20**: its 0.5 maps its names to 8.1, drops mode `paused` and `intensityClass`, and reads planned doses from `challenge.materialize`. |
| CE-OI-13 | D14 makes in-session rules automatic, but the CDS action key is still `suggest`; earlier drafts of OAP_CHALLENGE.md and UX 11.10 described program rules as one-tap suggestions, and the registry had no event for an auto-applied rule. | Keep the `suggest` key in CDS v1 with auto-apply semantics (sections 2.4.13, 6.10); a later CDS version may rename it to `apply`. Log `challenge_rule_applied` and `challenge_rule_undone` (registry 9.8.1) plus `session_exercises.user_override` on Undo (C46). | **Resolved by D14 / C46 and the C39 registry.** New conflict: UX 11.10 ("logged as `override_applied {field, from, to, rule_id, reason_code}`") and ALGORITHMS B9.11 still name the Undo event `override_applied`; the registry name is `challenge_rule_undone` (C39). Owners of UX 11.10 and ALGORITHMS B9.11 to align. |
| CE-OI-14 | `challenge_events` payloads: UX 20.11 uses snake_case keys and `item_id` with decisions `accept` / `decline` for `rules.approval_changed`; this document used (Kotlin) camelCase keys with `proposalId` and `approved` / `declined` (the values DATA_MODEL 9.4.2 and 9.7.2 store). | Applied here (section 4.2 owns the format, as UX 14.4 states): stored keys snake_case, `rules.approval_changed {proposal_id, decision: approved \| declined, previous?, decided_at}`, one event per proposal (C48); rows use `set_logs` column names (section 6.12); `event.*` fields map to these keys (section 3.7). | **Resolved by C48 / C39**: UX 20.11 now uses `proposal_id`, `approved` / `declined` and `set_logs` column names (`deviation_reason_code`); UX 20.10 uses the registry names. |
| CE-OI-15 | `docs/challenges/oap.challenge.v1.json` used pre-C37/C38 vocabulary: camelCase flags, load modes `bodyweight_plus` / `assisted_pin`, grip `pronated_or_neutral`, the marker template `{linkUuid}`, position values `afterAssist` / `freeOrder`, and no `holdClass` on hold exercises (C43). | Rewrite to DATA_MODEL codes (C38), `{enrollmentId}` (C37), `after:assist` / `inPlace` (C65, section 2.4.7) and add `holdClass`; V18 rejects the old values. | **Resolved by C65**: the T1 `neg` position expression now returns `after:assist` / `inPlace`. |
| CE-OI-16 | Under C37 the marker uuid is the enrollment id, so several events share one uuid; ANDROID.md 16.5-16.7 described the uuid as the `calendar_link` row id and the atomic guard selected on the uuid alone. | The pair (uuid, slot key) identifies an event (section 9.4); the executor's guards must match the full marker, and a C36 slot-type rewrite changes the slot key (section 9.6). | **Resolved by C37** (ANDROID 16.5-16.7 and the DATA_MODEL 9.7.3 CHECK now aligned). ANDROID OI-29 still says this document describes the uuid as the link row id; it does not (section 9.4), so that ANDROID issue can be closed. |
| CE-OI-17 | C37 fixes the slot-key class but not the uuid part of the marker regex. The DATA_MODEL 9.7.3 CHECK, ALGORITHMS_PLANNING 1.4 and this document (9.4) use the generic form `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}`; ANDROID 16.5 uses a UUIDv7-only form (`-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-`) and its OI-20 says CHALLENGE_ENGINE uses that form, which it does not. | Use the generic form everywhere: the stored CHECK already accepts it, every app-generated enrollment and recommendation id is a UUIDv7 anyway (so both forms accept the test vectors), and a v7-only parser would treat a marker the database accepts as foreign and never edit it. | **Resolved by C37 / C68**: ANDROID 16.5 uses the generic uuid pattern (its OI-20 closed); one shared parser in `dev.fitnessapp.algo.core.calendar`. |
| CE-OI-18 | ALGORITHMS_PLANNING 17.6 (owner of TIME-1 and the worked add-on values) still computes capacity as event length - 15-min travel buffer - TIME-1 - 5 and calls 9.2 min after C1 and none after A1 the normal-week values. Under F11 as revised and C49, events are training time: capacity = booked - TIME-1 - 5, so C1 about 24 min and A1 about 5 min (below `time.min_addon_min`, no add-on), which is what section 6.9, GF-CE-35 and the OAP definition's add-on notes use. | Section 6.9 follows C49 and points at 17.6's "training time only" column. | **Resolved by C49**: ALGORITHMS_PLANNING 17.6 uses training-time capacity (C1 about 24 min, none after A1; its OI-06 closed). |
| CE-OI-19 | Section 4.6 relies on the `daily-maintenance` job (05:00) to synthesize challenge ticks, so that `week.closed` happens on Monday even when the app is not opened. ANDROID's job table (`daily-maintenance`: calendar refresh and write plan, reminders, export reminder, backup pruning, dead-letter summary) does not list tick synthesis. | `daily-maintenance` calls `challenge.enrollment.synthesizeTicks` for every active or paused enrollment before the calendar write plan. | **Resolved by ANDROID 7.8 (its OI-32)**: `daily-maintenance` runs tick synthesis first; late runs are acceptable (section 4.6). |

---

## 16. Implementation notes for Claude Code

**Packages (`:algo`, pure Kotlin JVM).** `dev.fitnessapp.algo.challenge` (application id
`dev.fitnessapp`, F22; package layout of ANDROID.md 4) with sub-packages:

| Package | Main types |
|---|---|
| `cds` | `DefinitionLoader`, `StructuralValidator` (JSON Schema), `SemanticValidator` (checks V01-V18, each a function with its id), `TemplateResolver`, `CompiledDefinition`, `ValidationError(id, pointer, message)` |
| `fitexpr` | `Lexer`, `PrattParser`, `Ast`, `TypeChecker`, `Evaluator`, value classes (`NumV`, `MassV`, `PinV`, `DurationV`, `DaysV`, `DateV`, `BoolV`, `StrV`, `EnumV`, `ListV`, `SidePairV`, `MapV`, `NullV`), `FunctionRegistry` (whitelist), `EngineFacts`, `EvalError` |
| `enrollment` | `EnrollmentState`, `EnrollmentFold`, `EventOrder`, `TickSynthesizer`, `Snapshots`, `MigrationApplier`, `ResumeDryRun` |
| `eca` | `RuleEvaluator`, `ActionExpander`, `LockSet`, `WriteArbiter` (first-writer-wins, frozen modes, injury suppression), `AuditRow`, `NotificationQueue` |
| `materialize` | `Materializer`, `ModeTransforms`, `SideExpansion`, `LoadResolver` (calls LOAD-1 only), `InSessionRuleRunner` (auto-apply and Undo, D14), `Validity` |
| `plan` | `ChallengeWeekPlanner`, `HardConstraints`, `PlanScorer`, `TodayAdvisor`, `WeekProjection` |
| `contract` | `ContractBuilder`, `WindowBuilder` (Instants via `clock`), `SessionTagger`, `ConflictCheck` |
| `calendar` | `TitleNormalizer`, `SlotMatcher` (brute-force max-weight matching), `WritePlanner`; marker render/parse is not implemented here but uses the one shared `Marker` in `dev.fitnessapp.algo.core.calendar` (C68), the same code the general planner and the ANDROID 16.5 executor use |

Resources (C40, ANDROID.md 4; loaded outside `:algo`, which receives parsed objects):
`algo/src/main/resources/fitness/cds/cds.v1.schema.json` (to be written at M2),
`algo/src/main/resources/fitness/challenges/oap.challenge.v1.json` (copied from `docs/challenges/`),
and the challenge engine constants inside the params file in `algo/src/main/resources/fitness/params/`.
Use `kotlinx.serialization` for JSON; RFC 8785 JCS from the shared hashing utility (C24); a JVM JSON
Schema validator only after checking its licence and Maven Central version at scaffold time
(critique: pin library versions only after verification).

**`:sim` CLI.** `sim challenge validate <definition.json>`; `sim challenge simulate --definition
<file> --persona <name|all> --weeks 26 --proposals approved|declined`; `sim challenge replay
--events <export.json> --definition <file> [--params <file>] [--engine-facts <override.json>]`;
`sim challenge diff --a <defA> --b <defB> --events <export.json>` (which rules fire differently, var
trajectories, gate dates).

**Test classes (JUnit 5, names map to fixtures).** `FitExprParserTest`, `FitExprTypeCheckTest`
(GF-CE-07, 08), `FitExprNumericsTest` (GF-CE-01 .. 06, 06b, 09), `FitExprFunctionsTest`,
`TickSynthesizerTest` (GF-CE-10 .. 13), `EnrollmentFoldTest` (GF-CE-14, 15), `EcaSnapshotSemanticsTest`
(GF-CE-16 .. 20), `MaterializerModeTest` (GF-CE-21 .. 23), `InSessionRuleTest` (GF-CE-24, 24b),
`RuleLoadCapWarningTest` (GF-CE-34), `AddOnCapacityTest` (GF-CE-35), `SlotMatcherTest` (GF-CE-25),
`WritePlannerTest` (GF-CE-26, 28), `MarkerTest` (GF-CE-27), `ContractWindowsTest` (GF-CE-29, 30),
`GapDayPolicyTest` (GF-CE-36), `GateBasisTest` (GF-CE-37), `TestTemplateOrderTest` (GF-CE-38),
`SemanticValidatorTest` (GF-CE-31, 32, 39), `ChallengePersonaGoldenTest` (GF-CE-33),
`ChallengeDeterminismPropertyTest`, `AlgoPurityLintTest`.

**Order of work (M2).** FitExpr (with its tests) -> CDS validator -> enrollment fold + ticks -> ECA ->
materializer -> import + setup wizard -> plan -> contract -> calendar matching (read) -> write-back
behind a setting (D11 auto-write after the T17 device test of ANDROID.md).
