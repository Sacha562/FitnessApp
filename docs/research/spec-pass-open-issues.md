
# algo-load
FILES: C:\Users\sivuc\projects\FitnessApp\docs\ALGORITHMS.md
DEVIATIONS:
- Interim only, pending OI-A09: the D10 load-jump caps (a safety rule) are not applied to challenge RULE-mode loads, so D8 A-tag rules can run exactly as written. The cap excess is reported as a WARN warning rather than enforced.
- Interim only, pending OI-A12: in DOUBLE mode, one minimal equipment step is exempt from the load-jump cap. Without the exemption, coarse equipment could never progress. For high-risk exercises it is only allowed when the ladder start at the new load is at least r_lo.
OPEN:
- OI-A01: Which document owns calendar.classify, safety, the selection modules and the offline tuner/sim was not stated. ALGORITHMS_PLANNING.md and CHALLENGE_ENGINE.md did not exist yet. I listed ALGORITHMS_PLANNING.md as the expected owner.
- OI-A02: Rules P10-P16 (including P13, no Double-to-text outside Canon) are not in ANDROID.md's AlgoPurityTest pattern table. Golden hash tests guard them for now.
- OI-A03: C22 defines only the kg, s and ratio grids. I added a REP grid (0.01) and a STAT grid (12 significant digits); without STAT, rounding to 1e-4 would wipe out covariances like 2.5e-5. This needs a DECISIONS changelog entry.
- OI-A04: The bundled params path differs between documents. ANDROID.md uses algo/src/main/resources/fitness/params/ and DATA_MODEL 9.1.10 uses algo/src/main/resources/params/. I followed ANDROID.md.
- OI-A05: The stack load scheme with unit=level has no kg-per-level field. Level stacks are treated as ordinal for now (DOUBLE mode on the level, SSE-1 skips them). Proposal: add level_kg.
- OI-A06: The challenge research's roundPin(nearest)=round_half_down breaks ties toward less assistance, which contradicts C2 (ties go to more assistance). FitExpr must call the LOAD-1 rounding functions.
- OI-A07: HOLD-1 needs a hold class per exercise. I propose tags hold_grip, hold_elbow, hold_shoulder, hold_trunk, hold_knee and hold_ankle. Without them, the OAP straight-arm hold (pattern skill_static) gets the elbow exponent instead of the shoulder one.
- OI-A08: Which historical OAP sets the cutover import feeds into RCM-1 is undecided. It changes the D7 gate value: k_used 30.0 gives +39.4 kg and 31.3 gives +39.1 kg from the same 3RM.
- OI-A09 (decision conflict): D10 lists load-jump caps as always-enforced safety rules, and the precedence is safety > challenge. But D8 A-tag OAP rules exceed the caps: pin 50->45 is +5.46 %, the -10 lb rule is about +9.8 %, and a 5 kg test recalculation is +5.5 % of system load. Interim: caps are not applied to challenge RULE loads, and the excess is reported as WE-S2 at WARN level. The user needs to decide.
- OI-A10: The data model has no per-exercise goal kind (strength vs hypertrophy). Interim rule: strength for challenge items and for exercise-scope preferences with fixed scheme and rep_max <= 6; hypertrophy otherwise.
- OI-A11: After a calibration probe set, the remaining sets are planned from the probe (filling an undetermined plan). Confirm this is compatible with D10's 'mid-session load changes are suggestions'.
- OI-A12: DOUBLE mode may advance by exactly one loadable step even when that step exceeds the up-cap (for example dumbbells 8 to 10 kg, +25 %). Confirm this exemption from D10's cap rule.
- OI-A13: Rest between superset members is 60 s in the REST-1 research but ORD-1 assumes a 15 s transfer. Interim: REST-1's 60 s, and TIME-1/ORD-1 must read rest from REST-1.
- OI-A14: The too_tired reason code had no semantics in the research. Interim: treated like feeling_bad.
- OI-A15: C16's 'first set |ΔRIR| >= 2' does not say what it compares against. Interim: q_true minus predicted RIR (the model basis); 'reported minus aim' is a selectable param.
- OI-A16: Review metrics need one-step-ahead prediction keys in estimation.sse outputs (n_eff_pred, z_pred_lo/hi, l_prior, p_ll_prior). These are additive and need adding to DATA_MODEL section 10.2.
- OI-A17: STALL-1 tier actions change user-owned fields (sets, reps, RIR) only through a WE-T1 suggestion, to respect D10. Confirm.
- OI-A18: The fixture belt inventory, the weigh-in date 2026-10-04 and the 70.9 kg test weigh-in are assumptions. Replace them with real checklist data at cutover.
- OI-A19: Several new expert constants are not from any research lens: hold default range 20-45 s, pin_max_unknown 100 lb, global_min_active 5, double_entry_below 4, rest.adapt_cooldown_days 7, equipment_missing and busy_slot thresholds. All are marked tunable.

# algo-planning
FILES: C:\Users\sivuc\projects\FitnessApp\docs\ALGORITHMS_PLANNING.md
DEVIATIONS:

OPEN:
- OI-01 Calendar marker format conflict: oap.challenge.v1.json writeBack.marker uses a semicolon format that the DATA_MODEL calendar_events.marker CHECK rejects. This doc uses the DATA_MODEL/UX format fitapp:<owner>:<key>:<isoWeek>. The challenge definition needs an update.
- OI-02 C17 reading: SetCredit accounts volume with the reported RIR (the integer C6 table). The RIRCAL bias is applied only in the estimators. Needs confirmation.
- OI-03 Coefficients of 0.25 give no fractional-set credit (fatigue only) in SetCredit, the volume bars, challenge coverage and SEL-1. The exercise-selection lens counted them. Needs confirmation.
- OI-04 C5 does not list abductors (this doc uses τ 30 h, F_REF 6). It lists 'obliques', which is a region of abs under C9.
- OI-05 Deload and re-entry both apply in the real week. This doc does not stack them: set factor = min (0.5), RIR offset = max (+2). Needs confirmation.
- OI-06 Add-on capacity versus D5's '~35 min after C / ~20 min after A'. With TIME-1 and F11's 15-min travel buffer, normal weeks leave about 9 min after C and none after A; D5's figures are reached only in deload weeks (33.5 and 19.3 min). Ask the user whether the 19:45-21:15 events are training time only.
- OI-07 The OAP penalty windows have no systemic (whole-body) term. Adding systemicRMin 0.85 would move this week's general session from Wed to Fri, because a Wednesday session lowers G at A from 0.90 to 0.74. Needs a user or challenge-doc decision.
- OI-08 UX.md's contents list §19 'Deterministic UI functions', which overlaps the ui.* specs here; that section is not written in UX.md yet. Decide which document is authoritative (proposed: this doc, with UX §19 referencing it).
- OI-09 Default training age when onboarding has no answer: novice (BASE 10). The fixture assumes intermediate.
- OI-10 The user's muscle goals are undecided (D6). The worked example assumes the balanced preset; §21.11 shows how the result changes per preset.
- OI-11 Light pull on gap days: the definition's sessionTagRules make pull_heavy ≥ 4 hard sets, so the planner recommends 2 sets of reverse pec deck on Wednesday between C and A. D5 and the UX wireframe suggest 'no pull'. Confirm, or lower the threshold in the definition.
- OI-12 The tie band (0.05) with 'earlier session first' is new planning policy, and it decides Wed versus Fri this week. Needs confirmation.
- OI-13 One gym visit per day: on a challenge day, extra work is only possible as an add-on. Needs confirmation.
- OI-14 calendar.classify delegates per-week slot capacity, B/TEST aliasing and the C36 attention to challenge.calendar.matchSlots. The challenge document must provide that function with the signature given in §0.5 and §1.4.
- OI-15 Run systemic load is 0.5 per 40 min (the planning lens used 1.0). To be reviewed once runs are logged.
- OI-16 The §21/§22 selection outputs use a prototype catalog that differs from the seed in a few coefficients. Regenerate the golden values from catalog-1.0.0.json before freezing the tests.
- OI-17 Region count: 34 active + neck versus C9's '~32'. This is consistent with DATA_MODEL (its open issue 2).
- OI-18 Soreness scale mapping: three UI answers mapped onto the four-level ramp table and onto readiness points. To be reviewed after 8 weeks of answers.

# challenge
FILES: C:\Users\sivuc\projects\FitnessApp\docs\CHALLENGE_ENGINE.md, C:\Users\sivuc\projects\FitnessApp\docs\OAP_CHALLENGE.md, C:\Users\sivuc\projects\FitnessApp\docs\challenges\oap.challenge.v1.json
DEVIATIONS:
- F11 (session budget = calendar slot minus 15 min) is not applied to challenge sessions' add-on capacity, because doing so would contradict the user decision D5 (~35 min after C, ~20 after A). Flagged as CE-OI-05.
- C16 ('max 1 suggestion per exercise per session') is applied to general items only; challenge in-session rules are limited per rule per item, so the Todoist-A first-set calibration and P13 can both act on session A's assisted item. Flagged as CE-OI-04.
OPEN:
- CE-OI-01: ANDROID.md 16.5 marker parse regex allows only lower-case slot keys ([a-z0-9_./-]), but its own examples and test G11 use 'oap/C/2026-W42'. This spec uses [A-Za-z0-9_./-]{1,64}; ANDROID.md should adopt it.
- CE-OI-02: UX.md 10.4 names the general-session marker 'fitapp:general:<recommendation_id>:<isoWeek>'; ANDROID.md 16.5 uses '[fitapp:v1 <uuid> gen/<isoYear>-W<ww>/<dow>]'. This spec follows ANDROID.md; UX.md needs alignment.
- CE-OI-03: DATA_MODEL 9.4.2 describes rule_decisions as keyed by rule id, but D8 decisions are made per proposal (P05 and P09 each switch several rules). This spec keys decisions by proposal id. UX.md 14.4's example also uses an outdated rule id (prog.A.pin.fail).
- CE-OI-04: C16 allows max 1 suggestion per exercise per session, but the OAP A calibration rule (pair 1) and P13 (later pairs) can both suggest on the same item. This spec applies C16 to general items and a per-rule-per-item limit to challenge items. Needs confirmation.
- CE-OI-05: Applying F11's 15-min changeover to challenge sessions would cut D5's add-on room (TIME-1 estimate about 38 min after C, 20 after A) to about 23 and 6 min. This spec does not apply it to challenge sessions. Needs confirmation.
- CE-OI-06: D8's wording 'executed exactly as written unless the user declines a P replacement' reads inverted. Implemented as: the A rule runs unless its P replacement is approved; undecided proposals are inactive. The user should fix the wording in DECISIONS.md.
- CE-OI-07: 'progression: frozen' in deload, test-pending, extra-deload and injury weeks suppresses progression writes to snapshot vars unless a rule has allowInFrozen (three protective OAP rules). This is an interpretation of 'keep the weight' and needs user confirmation.
- CE-OI-08: The catalog seed does not exist yet. The OAP definition references 15 catalog ids (chinup_weighted, neutral_grip_pullup_weighted, pullup_weighted, chinup_bw, neutral_grip_pullup_bw, pullup_bw, dip_neutral, barbell_row_pronated, seated_cable_row_wide_pronated, chest_supported_row_pronated, reverse_wrist_curl_db, db_pronation_supination, face_pull_cable, rower_erg, scap_pull_bar). DATA_MODEL's UUIDv5 example 'exercise:weighted_pullup' uses the definition key, not a catalog id.
- CE-OI-09: Whether the cutover import creates set rows that RCM-1 can fit. Default here is no, so the first gate uses class prior k = 30 (+39.38 kg) rather than about 30.6 (+39.25 kg). The estimation spec owner should decide.
- CE-OI-10: Language of app-written challenge calendar titles (ANDROID OI-08 / UX OI-09). Default nl for OAP, switchable at setup.
- CE-OI-11: Synthesized boundary events use fixed local times (week.closed at Monday 04:00 via challenge.enrollment.weekCloseLocalTime) so that ordering by (occurred_at, id) works; DATA_MODEL documents the dedupe keys but not these times.
- OAP-OI-02: 'Starts from a dead hang' (unlocked when the fresh weaker-arm one-arm hang exceeds 15 s on a test day) has no prescription; the app only notifies and the exercise needs a definition update.
- OAP-OI-03: The 2026-08-30 least assistance (22.5 kg effective, about pin 35 lb) was recorded without an arm; it is imported for both arms.
- OAP-OI-04: Values to confirm at cutover: whether C on Tue 2026-10-06 was clean, sthS (6 or 8 s), plankS (20 or 30 s), C easy streak, and the number of block-1 B sessions with negatives (blockItemCounts).
- OAP-OI-05: The B row and dips have no progression rule in the artifact and are delegated to the general RX-1 (C15); the user should confirm.
- OAP-OI-07: Phase 2/3 templates (P18) are drafts to refine with the user before the phase-1 gate is realistically in reach.
- OAP-OI-08/09: The belt plate inventory, whether a dumbbell is ever hung on the belt, and whether it is always the same assisted machine are unknown; the fixtures assume a 2.5 kg grid.

# data-model-continue
FILES: C:\Users\sivuc\projects\FitnessApp\docs\DATA_MODEL.md
DEVIATIONS:
- F3 says zero new fitness advisor findings are expected after each migration. Section 17.3 allows two documented exceptions instead of failing the migration: INFO unused_index on new fitness indexes during their first 8 weeks, which appears for every new index until it is used, and a possible security-definer lint on fitness_private.is_allowed(), which is not exposed. This is flagged as open issue 22 for the user to confirm or record in DECISIONS.md.
OPEN:
- 1 Effective-load CHECK tolerance (0.01 kg) depends on how the LOAD spec rounds (open issue 1, pre-existing reference).
- 2 The muscle_regions seed has 35 rows; C9 says about 32 regions.
- 3 The OAP definition JSON (docs/challenges/oap.challenge.v1.json) uses camelCase set flags, `bodyweight_plus`/`assisted_pin` load modes and catalog slugs such as chinup_weighted that differ from E3/E6 and the migration 007 slug list; this blocks migrations 007 and 014.
- 4 Whether the optional numeric pain_score (0-10) is used by any rule.
- 5 The mapping from soreness_code to the readiness 0-3 scale has no values yet.
- 6 The Room schema in DATA_MODEL section 12 and ANDROID.md section 8 differ: local table names, timestamp representation (epoch microseconds vs epoch ms with server_updated_at as text), the outbox payload copy vs read-at-push, and the bundled params path.
- 7 Approval policy for user_algo_params review writes (per parameter or per review).
- 8 Export container: ANDROID.md writes .json.gz, UX.md says JSON zip, and the exports.format CHECK allows only json_zip/json. Proposal: add json_gz.
- 9 Telemetry event names differ across UX.md, ANDROID.md and the 9.8.1 registry (review_bundle_exported, data_exported, app_crash, why_opened payload, ...); must be merged before migration 005.
- 10 Section 10.2 lists no output keys for hold, stall, rcm, fatigue, safety.state, autoreg, warmup, rotate, rest.adapt or time.calibration, so v1 has no review views for those modules.
- 11 SQLSTATE 25006 (Free-plan read-only mode) is missing from the 11.8 and ANDROID.md 9.5 error tables; behaviour is defined in 15.2.
- 12 The full re-push restore action (scenario R1) is not specified in ANDROID.md or UX.md.
- 13 Restore from export file is a later milestone; until then scenario R3 relies on a Claude-run :sim ExportRestore tool.
- 14 Live three-actor RLS tests need consent to use one existing non-allowlisted account UUID as a stand-in inside a rolled-back transaction; otherwise use a local Docker stack (Docker availability not verified).
- 15 No user-facing fitness-only erase action; the 16.6 script is run by the user.
- 16 A database CHECK for the calendar_events.features code pattern is only proposed.
- 17 Android Auto Backup (ANDROID.md OI-05) needs the user's decision because it sends health data to Google backup.
- 18 Fixture assumptions to replace at cutover: event end times, titles, gym location, belt plate inventory, weigh-in date, started_on, template id A1.
- 19 The paused Free-project restore window figure from the research is unverified.
- 20 No automatic size guard (no cron job, no RPC); the check runs only during reviews and migrations.
- 21 Imported historical test values and validity flags come from the research import summary and must be confirmed at the D4 cutover.
- 22 F3 expects zero new advisor findings, but the INFO unused_index lint will flag every new fitness index; 17.3 allows it for 8 weeks, plus a possible security-definer lint on is_allowed(). This interpretation needs confirmation.

# ux-continue
FILES: C:\Users\sivuc\projects\FitnessApp\docs\UX.md
DEVIATIONS:

OPEN:
- OI-01: Phone model and Android version unknown (D2). They decide whether Android 16 Live Updates exist and which phone-maker battery instructions apply.
- OI-02: The OAP v1 test template T1 adds an unassisted 5 s negative (gate criterion 3) that F10 does not list, and P08/P20 change the order. The wizard follows the definition's order.
- OI-03: In-session A-tagged program rules are shown as one-tap suggestions (D10), which may clash with D8's 'executed exactly as written'. Needs user confirmation.
- OI-04: Warm-up items are logged with Done and no RIR. This reads D9's 'on every set' as working sets only. Needs user confirmation.
- OI-05: Soreness chip labels map to DATA_MODEL codes never_sore / healed / still_sore; the readiness-scale mapping is still DATA_MODEL open issue 5.
- OI-06: `today` must output a verdict (TRAIN / REST_SOFT / REST_HARD) and classed, typed reason codes. UI-01's REST_SCORE and CLOSE_MARGIN are replaced by a relative CLOSE_REL of 0.10.
- OI-07: Test-day entries use a Record button, and 3RM attempts are stored with RIR 0 by protocol. This is an interpretation of D9.
- OI-08: Platform controller constants live in :app config objects, outside the params JSON and params_hash (same question as ANDROID.md OI-06).
- OI-09: Language of calendar titles the app writes is not decided (same as ANDROID.md OI-08).
- OI-10: No 'forgot password' in v1.
- OI-11: What a rule_override ('Log a session anyway', 'Add anyway') means for the challenge is the challenge engine's decision.
- OI-12: Pain severity map Mild 2 / Moderate 5 / Sharp 8 is an assumption. Moderate already stops all exercises loading the joint (stop threshold 4/10).
- OI-13: 'Shortest lowering' stores the minimum eccentric time of the set in ecc_s_actual. Needs confirmation from the challenge spec and DATA_MODEL.
- OI-14: Deload transform of the top-hold rounds is owned by challenge.materialize (the fixture assumes 2 rounds).
- OI-15: The one-arm plank uses a Start chip, not quick-done. Needs user confirmation.
- OI-16: Params key casing: DATA_MODEL E53 requires lower case, while section 1's example and ANDROID.md use upper or camel case. Proposed: lower-cased constant names.
- OI-17: Event names and categories must be reconciled with the DATA_MODEL 9.8.1 registry seed (table 20.13).
- OI-18: Rest fields and formula: set_logs column names, the rest_before_s estimate, 60 s long-press steps and the 10 s minimum timer differ between UX and ANDROID.md.
- OI-19: The 'Standard exact' alarm option threshold is 20 rests in sections 11.13.4/15.7 and 30 in ANDROID.md (plus a revert window of 20).
- OI-20: The 10 s rest pre-warning is delivered in the background per UX but only with the UI in the foreground per ANDROID.md.
- OI-21: Notification channel ids, habit-reminder alarm type and copy ('Rest over' vs 'Rest done') differ between UX section 18 and ANDROID.md. Channel ids must be final before the first install.
- OI-22: UX lists fitapp:// deep links; ANDROID.md uses intent extras with no intent filters. Proposed: treat fitapp:// strings as internal route names.
- OI-23: Assist-kg decimals: 2 in UX section 5, 1 in ANDROID.md G7.
- OI-24: Rule ids in the section 11-14 wireframes come from the draft definition; oap.challenge.v1.json renamed them (ins.*, prog.gripSlip.lock, ...). Informational only; the UI renders ids from the definition.
- OI-25: Gate threshold basis: the gate is evaluated on the test-day bodyweight, the dashboard shows a forward threshold at current BW(t). The challenge spec must expose both.
- OI-26: Calendar marker format differs between UX 10.4, ANDROID.md 16.5 and the challenge research.
- OI-27: The warm-up row countdown needs RestTimerController support for purpose = interval.
- OI-28: Sections 11.6/11.12 have no 'Add a set' action. Proposed: an exercise-menu item logged as override_applied.
- OI-29: manual.override has no v1 UI, and the format of a pain-report retraction in challenge_events is undefined.
- OI-30: The ANDROID.md session idle prompt (N-IDLE) is missing from UX section 18.
- OI-31: The Live Session time-overrun line has no threshold. Proposed: 10 min over budget (assumption).
