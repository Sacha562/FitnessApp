# Params 1.0.0: generation notes

File: `algo/src/main/resources/fitness/params/default-params-1.0.0.json` (authored 2026-10-08, M0).
Generated from the spec tables, not typed by hand: ALGORITHMS Part C (C2), ALGORITHMS_PLANNING §23
(+ §1.5, §2.4, §7.4, §7.6, §10.3-10.4, §17.5, §0.6), CHALLENGE_ENGINE 3.11 / 4.14 / 5.10 / 7.8 / 8.8 / 9.9,
UX 19.1.4 / 19.7.4. Validated: JSON parses, 621 keys, all match E53, all module prefixes are registry
ids (longest-prefix match), code-point order (top level, params, entry fields, table members), ≤ 10
significant digits, no exponents, no nulls, `min ≤ value ≤ max` element-wise, UTF-8, LF, trailing newline.

## Key count per module (621)

| Module | n | Module | n | Module | n |
|---|---|---|---|---|---|
| clock | 1 | rircal | 14 | select | 23 |
| load | 7 | rcm | 14 | order | 4 |
| bodyweight | 4 | sse | 22 | substitute | 9 |
| calendar.classify | 32 | fatigue | 18 | rotate | 6 |
| safety | 31 | hold | 10 | time | 14 |
| challenge.fitexpr | 7 | stall | 22 | injury | 3 |
| challenge.enrollment | 5 | targets | 29 | rx | 34 |
| challenge.eca | 4 | runs | 10 | warmup | 12 |
| challenge.plan | 15 | allocator | 5 | rest | 26 |
| challenge.contract | 4 | today | 6 | reason | 25 |
| challenge.calendar | 9 | weekplan | 18 | autoreg | 16 |
| setcredit | 22 | catalog | 6 | warnings | 31 |
| readiness | 19 | pref | 10 | ui.todaycard | 8 |
| ui.prefill | 9 | ui.reasonchips | 2 | ui.prompts | 8 |
| ui.volumebars | 1 | ui.warndisplay | 4 | ui.progress | 7 |
| tuner | 18 | sim | 11 | | |

No keys (none specified anywhere): `challenge.cds`, `challenge.materialize`, `replay`.
Sources: Part C 256, PLANNING §23 271 (+42 expanded/derived, below), CHALLENGE_ENGINE 44, UX 8.

## Keys not written verbatim in a table row (expanded or derived) — 42

- `calendar.classify.lex.*` (§23.1 wildcard) → 14 keys from §1.5: `gym_title, neg, run, run_race,
  run_intervals, run_tempo, run_long, type.push, type.pull, type.legs, type.core, type.upper,
  type.lower, type.full`. Regexes copied verbatim (markdown `\|` unescaped).
- `safety.joint_forbid_tags` (§23.2 "table §2.4") → 24 keys `safety.joint_forbid_tags.<joint>.<1|2|3>`
  (key pattern given in §2.4). Severity rows are cumulative ("+ supinated_curl"), arrays sorted; "—" = `[]`.
- **Derived (flag)**: `setcredit.stim_rir_above` = 0 and `setcredit.fat_rir_above` = 0.50 (C6 table
  tails "0 above RIR 5" / "0.50 above RIR 4", otherwise hard-coded); `sim.set_drop_rir_max` = 2 and
  `sim.detrain_after_days` = 10 (values embedded in the §23.20 default text of `sim.set_drop` /
  `sim.detrain_rate`). These four need rows in ALGORITHMS_PLANNING §23.

## Value encodings chosen (spec gave prose or a table reference)

| Key | Encoding |
|---|---|
| `readiness.tau_h`, `readiness.f_ref`, `targets.mrv`, `allocator.sys_rate` | object over all 18 groups (sys_rate "others 0.4" written out) |
| `readiness.kappa_default` | `{"t22","t26","t30","t34": 1.0}` (§23 "per class"; §4.5 writes a scalar) |
| `allocator.indirect` | `{"source:target": [ratio, share]}` (§7.4 table) |
| `allocator.tissue_rate` | `{"group:tissue": w}`, absent = 0 (§7.6) |
| `time.group_profile` | `{group: [reps, rest_s, setup_s]}` (§17.5) |
| `targets.ramp_table` | `{"p4": -2, "s4_or_p3": 0, "s1_and_p1": 2, "p_le2_and_s_le2": 1, "missing": 0}` (rule order stays in code) |
| `catalog.derived_tags` | thresholds only: `grip_heavy_min_grip_demand` 2, `elbow_flexor_heavy_min_biceps_coeff` 1.0, `elbow_flexor_heavy_min_js_elbow_medial` 2, `pull_heavy_min_lats_or_upper_back_coeff` 0.75, `axial_heavy_min_axial` 3, `overhead_press_heavy_min_js_shoulder_overhead` 2 |
| `catalog.sfr_defaults` / `axial_defaults` / `setup_defaults_s` | class names derived from the prose (`machine_cable_isolation`, `dumbbell_compound`, `standing_ohp`, `dip_belt_add`, …) |
| `today.reason_codes` | the 34 codes of §0.6 in table order |
| `challenge.*` times (`week_close_local_time`, `weekday_window_start`, `weekday_window_latest_start`, `weekend_open`, `weekend_close`) | integer minutes after local midnight (240, 1050, 1230, 540, 960); "HH:MM" strings are not an A8.2 type |
| Values with units in the cell (`30 min`, `0.3 per stress unit per set`, `3 per week`, …) | number only; unit moved to `unit` |

## Conflicts and how they were resolved

1. **Lexicons are free strings** (regex), which A8.2's type list does not allow. Kept as strings
   because §23.1 makes them params; A8.2 / `ParamSpec` needs a `string` (regex) type, or the lexicons
   move elsewhere. Needs a decision.
2. **`calendar.classify.refresh_debounce_ms`** (§23.1) duplicates ANDROID §25.2 `calendar.observerDebounceMs`
   (2000 ms, ContentObserver debounce). C54 + ANDROID ownership of platform behaviour win → **excluded**.
   `ui.prefill.*` keypad timings kept (UX 19 lists them as params; not timer/notification/reminder/sync).
3. **Tunable flag rules disagree.** ALGORITHMS 0.1: engineering-fact = not tunable. PLANNING §23:
   weak/expert-opinion or `[this doc]` = tunable; engineering-fact with "—" = fixed. Applied per owner:
   Part C → `tunable = evidence ≠ engineering-fact`, except fixed bounds (`warnings.v1_*`, `v3_*`, C14)
   and the design lists `warnings.compare_*` ("not tunable by data") → false. PLANNING → EF + "—" → false
   (wins over `[this doc]`: `weekplan.k_cap`, `max_evals`, `min_slot_min`, `today.ui_scale`,
   `pref.implicit_window_d`, `readiness.checkin_valid_h`); EF with a tuner rule (T-CAL, review) → true.
   CHALLENGE_ENGINE / UX → their explicit column ("No data needed" → false).
4. `sse.huber_gate_sd`: engineering-fact but tuned by "M5 Huber rate" → tunable false, tuned_by kept.
5. **C27 vs `sse.detrain_cap` 0.15 ln**: C27 says "cap 15 %"; 0.15 in ln space = 13.9 % loss
   (`detrain_per_week` 0.0075 ln ≈ 0.75 %, negligible). Kept Part C's 0.15 (it cites C27); user to
   confirm or change to 0.1625.
6. `weekplan.tie_band` (PLANNING: 0-0.2, tunable, T-WEEK) vs `challenge.plan.tie_band` (fixed, C52).
   Value 0.05 in both (C52). Kept each owner's bounds/tunability; a T-WEEK change needs a C52 change first.
7. Duplicated constants in two modules (same value, different bounds): `clock.meso_weeks` (3-8) /
   `rotate.mesocycle_weeks` (4-8); `weekplan.busy_buffer_min` (0-60) / `challenge.plan.busy_buffer_min`
   (0-90); `weekplan.protect_w` 5 / `challenge.contract.window_penalty_weight` 5.0;
   `calendar.classify.confirm_score` / `challenge.calendar.confirm_below_score` 0.8;
   `time.side_switch_s` / `rest.side_switch_s` 10. All kept (each owner names its key); review could
   drop one of each pair.
8. ALGORITHMS A8.2 cites `calendar.classify.accept_score` as an example; no such key exists (the real one
   is `challenge.calendar.accept_score`). PLANNING §0.2 cites `readiness.tau_h.quads`; §23 defines one
   table key `readiness.tau_h` → single key used (§23 owns the registry).
9. `readiness.kappa_default`: §4.5 scalar 1.0 vs §23 "per class" → per-class object (T-KAPPA tunes per class).
10. `catalog.setup_defaults_s` / `sfr_defaults`: §10.3 gives ranges (pin machine 30-45, Smith 90-120,
    dumbbells 30-60, machine compound 4-5); §23.8 point values (40, 105, 45, 4.5) used.
11. `catalog.coeff_levels` bounds "fixed" on an array → element bounds 0.25..1.0, tunable false (C10).
12. `today.reason_codes` (§0.6) lacks `NOTHING_TO_TRAIN` and `NO_ELIGIBLE_EXERCISE`, which §7.4 emits.
    Enum written as §0.6 defines it; PLANNING should add them or rename.
13. `rotate.rotate_fraction` 0.3333 (§23) is slightly below F12's 1/3 (the §16.1 algorithm uses
    `ceil(n/3)`, so the key is not obviously read). Kept 0.3333.
14. "same" in the Source / Tuned-by columns was resolved literally to the previous row of the same
    module table. Side effect worth a look: `reason.pain_followup_exposures`, `pain_load_cut`,
    `pain_min_q`, `pain_repeat_events`, `pain_repeat_days` inherit "Decision (C64)" from
    `reason.pain_stop_score`, which is probably a table slip (only the stop score is C64).

## Evidence grades (compound cells → one enum value: the weakest grade, the grade of the *value*)

`rcm.k0`, `warmup.tier_low/mid/high`, `allocator.indirect`, `catalog.coeff_levels` → expert-opinion;
`rest.base` → weak; `targets.base_by_age`, `targets.freq_target` → moderate; `readiness.tau_h`
("moderate range, values assumed"), `weekplan.freq_bonus`, `select.b_stretch`, `select.region_bonus`
("value expert") → expert-opinion; `calendar.classify.w_title` "engineering-fact (design)" →
engineering-fact. `ui.todaycard.budget_min_chip` keeps UX's grade `assumption`.

## Bounds chosen where the spec gave none, "—", "profile" or bounds that cannot be single numbers

- Spec had "—"/none: `safety.severity_map`, `safety.elbow_check_severity` 1..3; `setcredit.felt_to_q`,
  `readiness.soreness_points`, `targets.soreness_scores` 0..5; `targets.ramp_table` -3..3;
  `targets.perf_thresholds` -0.1..0.1; `targets.reactive` 0..10; `targets.age_60` 60..60 (fixed);
  `runs.priority_mult` 0..3; `catalog.validator` 0..10; `catalog.derived_tags` 0..3;
  `substitute.setup_fast_s` 0..600; `time.min_sets` 0..5; `time.group_profile` 0..600;
  `tuner.kappa_grid` 0..2; `allocator.indirect` 0..1; `challenge.calendar.w_*` 0..1;
  `challenge.contract.window_penalty_weight` 1..20 (as `weekplan.protect_w`); `challenge.fitexpr.*`,
  `challenge.eca.*` numbers fixed (min = max); time-of-day "profile" bounds 0..1439.
- Per-field bounds collapsed to one element-wise range (A8.2 allows only one min/max):
  `warmup.tier_*` "fracs 0.3..0.95, reps 1..15" → 0.3..15; `warnings.f_load` "weights 0..1" but holds
  caps 30/40 → 0..60; `fatigue.c_grid` / `d_grid` "inside c_ref / d bounds" but `step` 0.02 → 0.02..0.5 /
  0.02..0.9. The validator cannot check the per-field limits; consider per-field ParamSpecs.
- Derived keys: `setcredit.stim_rir_above` 0..0.5, `fat_rir_above` 0.3..2.0, `sim.set_drop_rir_max`
  0..5, `sim.detrain_after_days` 3..28 (expert-opinion bounds).

## Constants with no value anywhere

None found: every module section that needs a constant has a value somewhere. No expert-opinion values
were invented.

## Inline numbers with no key (not added; decide whether each needs a key)

targets §5.4: ramp floor `T_base − 2`, new-mesocycle blend 0.5, age-60 divisor `BASE / 2`;
allocator §7.4/§7.6: quality factor `0.5 + 0.5·R`; weekplan §8.6/§8.9: `spill / 4`, "Plan it" 5-min
rounding; today §9.4: base score 0.5; select §13.4: set bounds ±1 / ±2, `ceil(need − 0.25)`;
order §14.2: superset axial ≤ 1, skill ≤ 1; substitute §15.1: same-family pattern score 0.5; injury
§18.2: window coefficient ≥ 0.5; time §17.3/§17.5/§17.7: warm-up set model `8 reps + 60 s`,
`n_g = ceil(x/3)`, short version `ceil(sets/2)`; warmup B10: singles when `r* ≤ 3`; rest B11.4: "last
10 sets"; readiness §4.6: PI window 28 d; challenge.plan §7.5: "within 2 h after another busy block";
tuner §20.1: PI 28 d, soreness R 0.8 / 0.6, T-TIME 6-week window, T-TARGET `T − 2` / `+2`, too_easy for
2 weeks, churn for 3 weeks → `+0.05`, run within 24 h, T-PREF 60 % / 5 unlocks / 0.5, T-SUB 40 % / 10
swaps; sim §20.2: calendar moves ±1 day in 30 % of weeks, elbow pain 1 per 12 weeks. Code-level, not
params: `TIE_EPS` 1e-9 (A2 P12), grids (C22/C42).

## Hashes

`params_hash` / `constants_hash` are not stored in this file (A8.3: computed over `{key: value}` by the
generator / migration 005). Note that any re-encoding above (for example minutes vs "HH:MM") changes them.
