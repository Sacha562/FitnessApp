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
| D2 | Phone | **Xiaomi / OnePlus / Oppo class device** (most aggressive background killers). The rest timer and session service must use every reliability measure: foreground service, `setAlarmClock`, battery-optimisation exemption, autostart guidance, on-device Doze test. Confirmed at M0 (2026-10-08): **OnePlus CPH2747, Android 16 (API 36)**, so Android 16 Live Updates are available and the OnePlus (OxygenOS) battery guidance applies. |
| D3 | Users | **Allowlist with only the user for now, built generically.** No hardcoded OAP logic, no sex/age-specific defaults that silently assume a young male, onboarding works for any user. Adding a friend later = one allowlist row. The other 5 accounts in the shared chess project are blocked by RLS. |
| D4 | OAP source of truth | **The app takes over.** At cutover the current OAP state is imported (phase, block, week, loads, pins, test history with validity flags). The app's rule engine recalculates loads after each session and test day. The Todoist session tasks are retired (only after explicit user confirmation at cutover), the artifact is frozen as a reference document, and the home habits (daily hang, plank) become in-app reminders. Until cutover the existing chat workflow continues unchanged. |
| D5 | Extra (non-challenge) training | **Separate legs/push sessions are allowed on challenge gap days.** The "≥1 rest day between gym sessions" rule applies only to sessions that load pull / grip / elbow flexors. Never a gym session the day before session B or a test day. Never heavy grip or elbow-flexor work in protected windows. Optional add-on blocks at the end of C (~35 min free) and A (~20 min free). Default maximum gym visits per week: 4 (setting). |
| D6 | Muscle goals | **User-configured in the app.** No goal is decided now. Onboarding asks; presets cover the examples from the brief (legs only, no legs, pull focus with push+legs maintained, balanced). Muscles owned by an active challenge show as "covered by challenge". |
| D7 | OAP strength gates | **Personal rep curve.** The gate metric (`wpu_e1rm_added`) is computed from the test-day 3RM through the user's fitted rep-capacity curve (RCM-1 `k` for the weighted pull-up), not through the fixed ×1.10 rule. The app shows the ×1.10 value and the 80 % interval next to it for transparency. No extra max single is added to test days. |
| D8 | Undefined / risky OAP rules | **Proposals tagged `P`, each approved individually** in the challenge setup. Rules already written in the artifact or the Todoist task descriptions (tag `A`) are executed exactly as written unless the user **approves** a `P` proposal that replaces them. Undecided proposals are inactive. (Wording corrected 2026-10-07, see C48. Setup default changed by D35: proposals start at the definition's recommendation and can be changed one by one.) |
| D9 | Set logging | **Tapping the RIR chip logs the set.** Weight/reps are prefilled; the row "reps left in the tank: 0 1 2 3 4 5+" is the log action, on every set, without prefilling or highlighting the RIR. Timed holds use "Max / Hard / OK / Easy" (seconds-in-reserve) chips as the log action. |
| D10 | Evidence vs preference | **Warn, keep the user's preference.** Non-blocking warning with a Why sheet (numbers, evidence grade, citations) and a one-tap "use suggested". Safety rules are always enforced (no failure on weighted / one-arm pulls or loaded dips, load-jump caps, pain protocol, challenge hard constraints). Mid-session load changes are one-tap suggestions, never automatic. |
| D11 | Calendar | **Read + auto-write.** Read the phone's synced calendars via `CalendarContract` (no Google OAuth), primary calendar only by default, all-day events and the Todoist calendar ignored. The app automatically creates and updates challenge sessions and test days for the current and next ISO week in the primary calendar, each with a marker line so it only ever edits its own events. Recommended general sessions are written when the user accepts them ("Plan it", one tap). Events without a marker are never modified unless the user taps "adopt" (needed once for the events Claude created by hand). |
| D12 | Language / numbers | **English UI, decimal point** (42.8 kg). Dutch calendar titles (`sessie C`, `één arm zwaar`, `testdag`, `hardlopen`) are still recognised. Exercise names stored in English and Dutch; **Dutch names are shown and searched only when the phone's language is Dutch** (user, 2026-10-11). |
| D13 | Load caps vs the user's own challenge rules | **The user's approved challenge rules win; the app warns.** Generic load-jump caps do not limit challenge RULE-mode loads; a jump above the generic cap produces a non-blocking WARN warning and a log entry (see C45). |
| D14 | In-session challenge rules | **Auto-apply with Undo.** Challenge in-session rules (e.g. session A first-set pin calibration ±5 lb, 90° hold failure → +5 lb, B grind → −2.5 kg) change the remaining sets immediately; the set card shows "Rule: … · Undo". General (non-challenge) in-session changes remain one-tap suggestions (D10). See C46. |
| D15 | Day before session A | **Allowed freely.** A general legs/push session may be planned on any gap day including the day before A; no whole-body (systemic) penalty term in v1. The 24 h protected window before A (no lats / elbow flexors / forearms) still applies (C12). See C50. |
| D16 | Android Auto Backup | **Off.** Supabase sync and the monthly JSON export are the backups; avoids restoring a stale local database that conflicts with sync. |
| D17 | Server data recovery | **Manual "Re-push all data"** (2026-10-08). A Sync status action re-queues every local row of every pushed table in dependency order; the user triggers it. Automatic detection of an emptied server is deferred. Closes ANDROID OI-25 and DATA_MODEL open issue 12. |
| D18 | Set prefill | **The full `ui.prefill` module ships in M1** (2026-10-08), implemented to ALGORITHMS_PLANNING §19 with "last time" as its only source (right side capped at the left, hold steps, no prefill across load modes); M3 adds the `rx` targets as a second source. Amends the build order of C73 / ALGORITHMS_PLANNING. |
| D19 | Catalog review | **Summary review before seeding** (2026-10-08). Claude authors the ~150-exercise catalog from the specs and free-exercise-db; the CAT-1 validator must pass; the user approves a summary page (exercises, equipment, main muscles per exercise) before migration 009 is applied. Coefficients are not reviewed line by line. |
| D20 | Milestone M1 | **Split into M1a and M1b** (2026-10-08) with the same total scope: M1a makes real sessions loggable as early as possible, M1b completes history editing, export, the reliability screen and the remaining telemetry (PLAN.md 11). |
| D21 | Engine modules in M1a | **LOAD-1 and BW-1 in full, REST-1 as its base policy** (2026-10-08). Set logging needs the single LOAD function (effective load, assist kg, loadable steps, plate breakdown) and the bodyweight snapshot, so `load` and `bodyweight` (ALGORITHMS B2, B3) move from M3 into M1a. The rest timer takes its durations from `rest` (B11) in a base form: warm-up rests, challenge item values, the user's `rest_pref_s`, the class table `rest.base` with the strength-heavy rule and the clamps. The outcome modifiers, the fatigue modifier (needs FAT-1), the between-sides and superset formulas that need TIME-1, the live readout and the adaptation follow in M3 as `rest@1.1.0`. |
| D22 | Session start in M1a | **Empty session only** (2026-10-08). Until the challenge engine (M2) materializes challenge sessions, every session starts empty and exercises are added from the catalog; no "repeat a past session" or user template is added. Amended by D49: from M3.4 a general session can also start as a repeat of a past one. |
| D23 | Prefill within a session | **Today's change carries forward** (2026-10-08). When a set logged today differs from last time's set at the same index, the next set of that exercise is prefilled from today's previous set (`prefill_source = last_prev_index`); otherwise "last time" at the same index applies as before. Order: accepted adjustment, target, today's changed previous set, last time same index, last time previous index, default. Amends ALGORITHMS_PLANNING 19.7. |
| D24 | OAP card types | **Eccentric (negative) and top-hold round cards ship with M2** (2026-10-08), with the challenge engine; until then OAP sessions stay on the chat workflow (D4). M1a covers external, dumbbell, stack, bodyweight, belt, assisted-pin, timed-hold and left/right cards. |
| D25 | Warm-up block | **Moves from M1a to M2** (2026-10-11). With empty sessions only (D22) nothing creates a general warm-up block yet; it is built with the challenge sessions and templates that materialize one, and OI-27 (alarm-backed interval countdown or the in-app fallback) is decided then. Per-exercise warm-up sets stay in M1a. |
| D26 | Muscle map | **Front and back silhouettes ship in M1b** (2026-10-11), as UX 13.3 specifies: a custom vector map shaded by group coefficient, next to the group list that is its accessible equivalent. Design tools are free to use (Blender is installed on the dev machine). |
| D27 | Custom exercises | **Move to M3** (2026-10-11), together with the selection modules that use them. M1b ships the catalog list, exercise detail and five-state preferences only; the "Custom" filter and "Create custom exercise" appear in M3. |
| D28 | History editing | **A past session can also gain a forgotten exercise** (2026-10-11), with its sets, on top of the UX 15.3 actions (edit, add and delete a set, edit the quick check, reopen an abandoned session, delete a session). Logging a whole missed session after the fact is not added. |
| D29 | Idle protection and review bundle | **Idle protection ships in M1b** (2026-10-11): ANDROID 11.5 was specified for M1a but not built; M1b adds N-IDLE with Finish / Keep open, the idle stop of the foreground service and `session_idle_prompted`. **The review-bundle export moves to M5** with the review tooling; the full export already contains every session, set and event. |
| D30 | Preference changes | **No Undo snackbar for a preference change** (user, 2026-10-11): the change applies immediately and the segmented control itself is the way back (one tap). Exception to UX P6; `preference_changed` is still logged, and `action_undone` is no longer written for preferences. |
| D31 | Visual style | **"Bento" dark design, dark only** (user, 2026-10-11, picked from four mock directions; replaces F16). Blue-black ground `#0B0D12`, layered tiles (`#151922`, inner `#1E2330`) with large soft corners (24 dp tiles, pill buttons and chips), one periwinkle accent `#A9A4FF`, advisory amber `#F5B85C`, safety red `#FF8A80`. Font: Manrope (variable, SIL OFL), bundled, tabular figures everywhere. No light theme, no dynamic colour, no System / Light / Dark setting; the `profiles.theme` and `profiles.dynamic_color` columns stay but are ignored. Compose Material 3 remains the component base (D1), restyled through the theme and a small set of Bento components. Tokens and rules: UX section 4. |
| D32 | M2 split | **M2 is split into M2a and M2b** (user, 2026-10-20), as M1 was (D20); the total scope is unchanged. **M2a, engine core**: the pure `:algo` challenge modules `challenge.fitexpr`, `challenge.cds` (validation stages 1 and 2), `challenge.enrollment`, `challenge.eca`, `challenge.materialize`, `challenge.plan` and `challenge.contract`, plus the `:sim challenge validate` and `simulate` commands with the committed golden persona simulations (stage 3). M2a changes no screen and no database object. **M2b, app and cutover**: the setup wizard with the P01-P27 decisions and the import (D4), challenge storage and sync on the phone, the Challenges tab, the test-day wizard, the elbow check and pain protocol, habit reminders, the general warm-up block (D25, OI-27), `calendar.classify` and `challenge.calendar` with the write-back executor (D11), Today, and the cutover. The M2 done-when is checked at the end of M2b. |
| D33 | M2b calls | **Four M2b decisions** (user, 2026-10-20). (1) **Warm-up interval countdown is alarm-backed** (resolves UX and ANDROID OI-27): the rest timer gains `purpose = interval` (ANDROID 12.3), so the "Row 3:00" countdown alerts with the screen off. (2) **The one-arm plank is logged through a "Start" chip** (resolves UX OI-15): a home mini-session with seconds per side and the hip-rotation flag, which `prog.plank` needs; the daily hang keeps quick-done. (3) **P20 stays in `oap@1.0.0`** (resolves OAP-OI-14): it has no effect since C65, and keeping it keeps the P01-P27 references valid; retiring it waits for a later definition version. (4) **Migration 018 (server JSON-Schema CHECK) is deferred** (F5): the app validates every definition (stages 1-3), the server guard keeps a published definition immutable, and only reviewed migrations publish; `cds.v1.schema.json` and 018 return with a second challenge or in-app publishing. **Build order of M2b**: M2b.1 storage and sync (bundled definition, migration 016, Room tables, refold, tick catch-up); M2b.2 Challenges tab and setup wizard (import, proposals, dashboard, rules, history, pause); M2b.3 challenge sessions (Today, materialized session start, warm-up block, in-session rules with Undo, readiness and pain sheets, home habits and reminders); M2b.4 test-day wizard and recalculation report; M2b.5 calendar (`calendar.classify`, `challenge.calendar`, write-back on the sandbox calendar first, ANDROID 16.10); M2b.6 cutover with the user (D4). |
| D34 | Pause and correction reasons | **Reason codes for pausing and correcting** (user, 2026-10-20). `fitness.reason_codes` gains the chip contexts `pause` and `correction` and five codes: `holiday`, `illness` and `injury` (pause; UX 14.7) and `loaded_different` and `value_wrong` (Correct a value); `other` joins both new contexts, `too_hard` and `too_easy` join `correction`. Migration 021 (DATA_MODEL 9.1.3). Before it these reasons were stored as the nearest existing code (`other`, `feeling_bad`, `pain`). `injury` is not a safety code: the safety rules start from a pain report, not from a pause reason (C33). |
| D35 | Less text, fewer inputs | **Defaults first** (user, 2026-10-21, after the M2b phone checks: "way too much input needed and too much text"). (1) A screen asks only for what has no sensible default. Everything with a default (tuning, overrides, program position, proposals) sits behind a collapsed "More" or "Customize" section that a user who keeps the defaults never has to open. Helper text is at most one short line, and user-visible text never shows decision ids, rule ids or engine terms. (2) **Challenge setup has three steps**: Overview (with the grip, the only required parameter), Starting point, and the first two weeks with Start. The equipment, gate-method, schedule and calendar steps are dropped; that information lives where it is used (You → Equipment, the test-day report, the dashboard, calendar setup). Pressing Start is the cutover confirmation of D4; there is no separate checkbox. (3) **The starting point has two choices**: "Start with a test week" (the default; nothing to type, the test sets the loads) and "Enter my numbers" (the onboarding values that the definition's state vars are initialised from; the other onboarding values under "More test values"; D4's import of the program position and current loads under "I'm mid-program"). (4) **Proposals start at the definition's recommendation** (replaces D8's "each approved individually" for setup): setup records one decision per proposal from its `recommended` field and shows one line with a "Customize" link; the Proposals screen lists each proposal as a switch with its title, details on tap. C48 is unchanged: an A rule runs as written when its proposal is declined, and an undecided proposal (one added by a later definition version) is inactive and flagged on the dashboard. Safety rules are not proposals and stay fixed. |
| D36 | Test-day protocols | **Structured test protocols** (user, 2026-10-21, M2b.4). A test item of a challenge template may carry `steps` (always) or `stepsIfApproved` (with its `protocolProposal` approved, like `protocolIfApproved`): `rampOf` (the expected total load the warm-up ramp is a fraction of), `rampFractions`, `rampReps`, `start` (the first attempt's load: added kg or a machine pin), `step` (the signed change after a clean attempt) and `maxAttempts`, each a FitExpr. The materializer evaluates them into the plan, so the test-day wizard computes the 3RM ramp, the first attempt and the assistance ladder exactly as the protocol says, and the definition owns every number (OAP: P21's ramp at 50, 70 and 85 % of the expected 3RM, +2.5 kg steps and at most 3 attempts; the ladder from the A pin + 10 lb in 5 lb steps, at most 5 attempts per arm). Without steps the wizard prefills from the previous valid test. `oap@1.1.0` (minor: new params, CHALLENGE_ENGINE 2.2) adds these fields and their params (migration 023); `challenge.cds` and `challenge.materialize` go to 1.1.0 and `ENGINE_VERSION` to 1.1.0. The wizard itself follows D35 (UX 14.9): one card per step with one main action, protocol text folded, the review and the report in a few plain lines, the D7 extras behind a folded row. |
| D37 | First test sets the loads | **The first valid test fills every empty program value** (user, 2026-10-21, after the M2b.4 phone checks). A challenge started with "Start with a test week" has no loads yet, and one started with "Enter my numbers" lacked the gate e1RM (its init read a value setup never asks). `oap@1.2.0`: (1) `e1rmAdded` (and the x1.10 display value) are initialised from the entered 3RM on the personal curve (D7) when no value is given. (2) New engineering rules (tag E) on `test.completed` fill what is still empty: session C's anchor is the load for `anchorReps` + `firstAnchorRir` reps on the personal curve (5 reps with 2 in reserve, the user's choice; a new tunable param, default 2), `anchorFraction` is frozen from it (no longer static), A and B follow with the slope of P10 (or the artifact's 3RM / (max reps - 3) when P10 is declined), the negatives with the artifact's 105 % rule, and the machine pins with P11's formula when P11 is declined. (3) The test rules that need an earlier value (`data.test.loads`, `data.test.loads.curve`, `data.test.neg.keep`) skip instead of failing while it is empty; nothing changes once every value is set, which the persona replay proves. The app also resolves the grip-dependent exercises with the definition's defaults and the setup's choices before an enrollment exists, so the creation's facts carry the weighted pull-up's `k` (without it `anchorFraction` failed to initialise). |
| D38 | Session order | **No fixed weekday or order for the test and sessions A, B and C** (user, 2026-10-22: "just pick the most logical ordering first and do test day first when needed; respect rest days"). The test no longer has to fall on a Sunday (the hard `testWeekday` constraint is dropped; Sunday stays a soft preference). The planner keeps C, A, B / test as its default order where it fits and relaxes it otherwise (unchanged). A definition can put a slot first in its week while a condition holds (`schedule.order.first`); OAP puts the test first while the program has no loads yet (`!defined(s.wpuC)`), so a test-week start tests first and C and A follow with real loads. Rest-day rules (a rest day before B and the test, one day between heavy pulling sessions) stay hard. Setup's test week can test from today. `oap@1.3.0`, `challenge.cds` 1.2.0, `challenge.plan` 1.1.0, `ENGINE_VERSION` 1.2.0. |
| D39 | Calendar in M2b.5 | **Calendar UI and test scope before the Week tab** (user, 2026-10-22, M2b.5). (1) Until the Week tab (M4) the calendar lives in You → Calendars (UX 15.6: permission, calendar selection, this week as the app sees it with kinds and scores, the app's events, the adopt list, the overrides, Refresh) and the calendar event sheet (UX 16.11); the challenge dashboard's This week row shows per slot whether its event is the app's, needs Adopt, or was moved or edited by the user, and opens the sheet. (2) While writes go to the device-only test calendar (ANDROID 16.10), the debug build reads the user's primary calendar read-only together with the test calendar; Adopt and every write on a real calendar are refused by the executor's target guard until the user allows real writes after T17. (3) The event sheet has every correction choice of UX 16.11 (challenge slots, gym types, run, not a gym session) with the scope "this event" or "the whole series" (`series_key`); the title-fingerprint scope is not built. |
| D40 | Cutover state (M2b.6) | **Import the real state as an elbow protocol that has just run its course, with a gentle return** (user, 2026-10-22, at the start of M2b.6). (1) The 11 October test did not happen: the user has had an elbow complaint (right upper arm / elbow) since 2026-10-08, pain-free since 2026-10-12; the chat workflow ran reduced "elleboog" sessions from 13 to 21 October. The import is phase 1, block 1, week 5, mode `injury`, `injuryStart` 2026-10-08, week ordinal 8 (ISO W36-W43), the last pre-injury values from the calendar of 25 September to 6 October (equal to the Todoist tasks): C 20, A 22.5, B 27.5 kg, negatives 47.5 / reset 45 kg, pins A 40, C 50, 90° hold 35 lb, straight-arm hold 6 s, plank 20 s with no plate, one-arm hang 10 s, three or more session-B days with negatives in block 1 (`blockItemCounts.neg` 3), and the historical tests of OAP_CHALLENGE 11.2; at Start the user marked all three (30 August, 19 September, 29 September) as counting for the gates. These values are the block-1 snapshot the protocol's exit restores. Bodyweight at the import: 69.9 kg. (2) **Re-entry after the elbow protocol** (proposal P29, recommended on): the exit no longer jumps straight back to the full loads. It sets a `reentry` mode that runs to the end of the first week of the new block: no eccentric work, every assistance pin 10 lb higher and every belt load 5 kg lower (params, rounded on the belt down), progression frozen, the daily hang halved; then `normal`. Declined, the exit is as before. (3) **Pallof press in every gym session** (user's request in the chat workflow, 2026-10-19): 3 x 10 on the cable with 2 reps in reserve, starting at 11.3 kg, in sessions A, B and C; in B it replaces the gym one-arm plank (the home plank on Tuesday and Thursday stays). Proposal P28 (recommended on) reads "clearly easier" as 4 or more reps in reserve on every set and then adds one stack step. The cable stack is still the default and is confirmed at the gym. (4) The build's test data (invented sessions, sets, observations and test days) is wiped before the cutover: phone and server rows of the user's account in the three fitness schemas, the SQL shown first; equipment and exercise preferences stay. (5) Setup's "I'm mid-program" gains the elbow-protocol mode with its start date, the week ordinal, the negatives done in the block and the historical tests (pre-filled, each confirmed). (6) The schedule editor (PLAN 15) stays deferred: the definition's preferred times (C Wed 19:45, A Fri 19:45, B Sun 11:00, hang 17:15, plank Tue / Thu 17:30) already match the real rhythm. (7) Todoist stays as a backup during the first app week; then Claude removes the recurrence and completes the six tasks (reversible); deleting them is the user's. (8) The user declined P22 (plank plate 5 kg) at Start. `oap@1.4.0`; started 2026-10-22 (enrollment `01a1644f`). |
| D41 | Previous test per measurement | **The comparison baseline keeps the newest gate-valid value of each measurement** (user, 2026-10-23, after the D40 cutover). Until now `pt` (the previous valid test, CHALLENGE_ENGINE 11.2) was the single newest test that counts for the gates. The cutover made 29 September (a one-arm hang only, no bodyweight) that test, so the next test day would have found no `pt.bw` (the "valid test: new block with new loads" rule would not fire) and nothing to compare progress with. From engine 1.4.0 (`challenge.enrollment` 1.2.0) the state keeps, per measurement, the newest value of any gate-valid test (imported or logged) with its date and protocol code; a value marked not comparable (another protocol) never enters it, and a value with another protocol code than the new test's is skipped as before. `pt`, and the test day's "last time" column, read this baseline. The phase gates stay judged on one test day with that day's bodyweight (C62): they still read the newest gate-valid test as a whole. Existing enrollments get the baseline on their next refold; nothing is redone. |
| D42 | Train now at any time of day | **A challenge session can be started at any time of the day it is done** (user, 2026-10-25, after the phone test of 23 October). The user opened the app at 21:35 on Friday; session A could no longer be suggested (latest start 20:30 plus the 60-minute lead), so the week plan dropped A and "train now" only tried the slots the plan kept, which offered B. The time in the calendar or the suggestion window must not decide whether a session can start. From engine 1.5.0 (`challenge.plan` 1.2.0, CHALLENGE_ENGINE 7.6 step 2) "train now" tries every slot of the week that is not done yet, kept by the plan or dropped from it, as a session starting now (no lead time). A trial counts when that session breaks no hard constraint and the week then drops no slot the plan kept; of those, the trial that leaves the fewest slots out wins, then the one that leaves out the less important slots (`slotPriority`), then the program's order. Late on Friday this offers A with B on Sunday; late on a Sunday with A and B both missed it offers B. The suggestion windows and the lead time still shape the suggested plan and the calendar write-back. Hard constraints (gaps, rest days) are unchanged. |
| D43 | Stack setup in a session | **Each machine's weight stack can be set up from the live session** (user, 2026-10-25). The exercise menu of a card whose machine has a kg or lb stack gains "Stack setup": a sheet with the unit (kg or lb), the step, the lightest and the heaviest value, read off that machine. Saving changes only that machine's `gym_equipment` row (`calibration_rev` + 1, `equipment_calibration_changed`, `confirmed_at` now) and marks it `calibration_source = 'measured'`; the card is rebuilt on the new stack, its target snapped to the new steps. The Equipment page's "Machine stacks" setting stays the default for every machine still on `default` and never overwrites a `measured` stack. This is the per-machine stack step of UX 15.5, built in the session first. |
| D44 | No cardio in the warm-up; definition updates | **The OAP warm-up has no rowing machine, and a running enrollment takes a new definition version through the previewed update of CHALLENGE_ENGINE 4.12** (user, 2026-10-25). (1) `oap@1.5.0` drops `wu_row` (3 minutes on the rower) from `warmup10`, and `rower_erg` from the required equipment and the exercises; the warm-up starts with the face pulls. No warm-up of the definition holds cardio. (2) Versioning (CHALLENGE_ENGINE 2.2): removing a block item that no rule, state var, gate, count or habit reads is a minor change; "removed key" in the major row means a key something reads. (3) From engine 1.6.0 (`challenge.enrollment` 1.3.0) the fold handles `definition.migrated`: the events before it fold under the old version, the event itself and every later event under the new one (each event row carries its version). State vars the new version adds take `migrations[fromVersion].defaults` or their `init`, computed when the update is made and stored in the event's `var_map`, so a refold never recomputes them; vars it no longer declares are dropped. A non-empty `varMap` is refused until an engine supports it. An update is offered only for a newer version of the same slug whose `migrations` names the enrollment's version. (4) The app shows "Update available" on the library card and in the dashboard menu. The preview materializes this week's and next week's planned sessions under both versions and lists the items removed, added and changed, the rules added, removed and changed, and the new proposals; "Update" writes the event with the preview's hash. Every update goes through the preview: the text-only auto-apply and the major-version rules of 4.12 are not built yet. (5) The running enrollment (`oap@1.4.0`, D40) moves to 1.5.0 only when the user taps Update. |
| D45 | Pain at the back of the elbow and in the forearm | **Two new pain regions, both an elbow trace in OAP** (user, 2026-10-25, after the phone test of 23 October: pain 2/10, dull ache, right, during weighted pull-ups, logged as "Other" with the note "forearm/back of elbow" because the Pain sheet had no such region, so no elbow rule reacted). (1) `fitness.body_regions` gains `elbow_posterior` ("Elbow, back") with the INJ-1 joint keys {elbow_medial, elbow_lateral} and `forearm` ("Forearm") with {elbow_medial, elbow_lateral, wrist}, both conservative (migration 032, the bundled reference file, the Pain sheet). (2) In OAP, `safety.elbow.traceFromPain` also fires on these two regions: no negatives and no daily hang until the next elbow check. They do not start the elbow protocol and do not count for its exit or extension (the user chose the trace, not the full protocol). (3) The Pallof press items of A, B and C are per side (`perSide`), as the user does them (25 lb each side on 23 October); the catalog already lists it as unilateral. `oap@1.6.0` (migration 033), on top of 1.5.0 (D44). |
| D46 | One notification at rest end | **The rest end posts no separate heads-up; the session notification is the only one** (user, 2026-10-25, after a session in which the live status and a "Rest over" notification showed side by side). The ongoing notification stays on the LOW channel `session_ongoing`, so it cannot pop up, and a separate HIGH notification was the only way to get a heads-up; the user prefers one entry. At a rest end the app vibrates the waveform (F20, always) and plays the optional tone as before, and the session notification switches to overtime ("Rest over", count-up from the rest end, the next set in its text). An interval (D33) ends with the same vibration and the notification returns to the session count-up. `N-REST-DONE` is retired: the timer self-test also only vibrates, and nothing posts or logs it any more. The channel `rest_done` is no longer created on new installs and is not deleted on existing ones (ANDROID 13.1: channels are never deleted); its id and notification id 1002 stay reserved. Cost accepted: with the screen on in another app there is no pop-up and nothing new on the lock screen; the vibration is the alert. App only, no engine or params change. |
| D47 | Prehab in deload weeks | **Prehab keeps its sets in deload weeks** (user, 2026-10-25, after the session B of 23 October). The deload halving `max(1, ceil(sets / 2))` exempted only warm-ups, so the three prehab items (reverse wrist curls 2 x 15, pronation-supination 2 x 10 per side, face pulls 2 x 15) dropped to one set in every `deload`, `test_pending` and `extra_deload` week, including the re-entry week right after the elbow protocol, the week the prevention work matters most. The artifact's "halve all sets" is about training stress; the prehab is 5 minutes of light work at 3-4 reps in reserve meant for every session (OAP_CHALLENGE 9), and the elbow protocol already doubles it. From `oap@1.7.0` the `deload` mode's `exemptRoles` is `warmup` and `prehab`; `test_pending` and `extra_deload` inherit it. Cost accepted: about 2-3 minutes more per deload session. Injury mode (prehab doubled) and every other mode are unchanged; no engine or params change. The running enrollment moves to 1.7.0 only through the previewed update (D44). |
| D48 | Correcting the imported earlier tests | **A second `enrollment.created` that supersedes the first may correct the imported earlier tests, and nothing else** (user, 2026-10-25, closing the M2b.6 open item). The D40 setup dropped every per-arm value of the earlier tests (fixed in `SetupImport` since), so the stored import lacks the 30 August hang 3 / 8 s (old protocol, not comparable), top hold 0.5 / 1 s and least assistance, and the 29 September hang 15 / 20 s; the next test's `testProgress` could only compare the e1RM. The events table is append-only and "Correct a value" (4.9) covers state vars, not tests. (1) From engine 1.7.0 (`challenge.enrollment` 1.4.0, CHALLENGE_ENGINE 4.11) the fold always starts from the original creation (the one without `supersedes_event_id`) and, when the newest creation that supersedes it differs from it only in `import.past_tests`, folds the creation with those tests: `lastValidTest`, `validTestCount` and the comparison baseline (D41) come from the corrected list. A correction that changes anything else (mode, vars, onboarding values, params, definition version) is ignored with the warning `creation_amendment_ignored`. History shows it as "Earlier tests corrected". (2) It is written by the debug action `amend_import` (the setup draft's test format through `SetupImport`) and synced like any event; there is no screen for it, because setup no longer loses the values. (3) The 30 August least assistance was recorded as 22.5 kg effective with no arm or pin; it is imported as pin 35 lb for each arm (35 lb + the machine's 6.5 kg ≈ 22.4 kg), as in the cutover draft, because the definition's metric is the pin. (4) No working load or state var changes: they were imported on their own (D40). Migration 035 registers the module. |
| D49 | M3 scope and build order | **M3 prescribes the exercises you pick; automatic session building moves to M4** (user, 2026-10-27, at the start of M3). (1) **Scope.** M3 builds the estimators (`fatigue`, `rcm`, `rircal`, `sse`, `hold`), the prescription modules (`reason`, `rx`, the `warmup` set generator, `rest@1.1.0`, `autoreg`, `stall`, `warnings`), the preference model `pref`, the joint-stress filter `injury`, the hard filters of `elig` (ALGORITHMS_PLANNING 12.3 without the necessity gate, which needs the allocator's targets) and `substitute`, together with custom exercises (D27) and the Preferences screen's Sets and reps section and RIR check setting (UX 15.4). `select`, `order`, `time`, the necessity gate of `elig`, the Muscle goals and the other Sessions settings of the Preferences screen, and the session-building screens move to M4, where the allocator supplies the per-group targets SEL-1 needs; no stand-in target table per session type is built. Until ORD-1 exists, SUB-1's "Do {k} now" offers the next exercise not yet done in the session's own order. (2) **Starting a general session.** D22 is amended: a general session can start as a repeat of a past finished general session ("Repeat" lists them newest first). The copy keeps the exercises, their order, equipment and planned set counts; loads, reps and RIR targets come from `rx`, or from last time where `rx` gives none. `template_source` gains the value `repeat` (DATA_MODEL E9; the migration is part of M3.4 and is shown before it is applied), and `session_started` carries the source session's id. There are no named templates. (3) **Delegated challenge items** (resolves ALGORITHMS OI-A20): the OAP B row and the dips (`progression: delegate:rx`) take their load from RX-1 in `model` or `double` mode, with the item's sets, reps and RIR target as the scheme. The cap table applies, because they are not RULE loads (D13); the first exposure is the B9.8 probe that the definition's `bootstrap` selects; AUTO-1 may suggest changes within the C16 limit; challenge rules keep reading the raw RIR (C17). (4) **Pain stop** (resolves ALGORITHMS OI-A25): a pain score at or above `reason.pain_stop_score` (4) removes, for the rest of the day, every remaining exercise whose catalog joint stress `js_<joint>` is at least `reason.pain_stop_js_min` (2, a new expert-opinion param) for a joint key of the reported body region. An exercise with only light stress (1) on that joint stays. Mild pain (2) still never stops anything; the challenge pain protocol and the `safety` joint flags for the following days are unchanged. (5) **Build order**, in six steps, each ending with a green build and checks on the phone: **M3.1** the estimators in `:algo`; **M3.2** estimator storage and the session-end update (Room `exercise_model_state` with sync, the L8 update when a session finishes, snapshots, the exercise detail's e1RM sparkline, and RCM-1's `k` as the OAP's `userK` fact under C59); **M3.3** the prescription engine in `:algo` (`reason`, `rx`, the warm-up set generator, `rest@1.1.0`, `stall`; the params gain `reason.pain_stop_js_min`); **M3.4** targets in the live session (set-card targets as the second prefill source of D18, the first-set probe flow, generated warm-up sets, the delegated OAP items, Summary "Next time", the Why sheet, Repeat); **M3.5** suggestions, warnings and preferences (`autoreg` cards, `warnings` with `ui.warndisplay`, stall-tier suggestions, the Preferences screen's Sets and reps, the opt-in RIR check set of F14); **M3.6** substitution and custom exercises (`pref`, `injury`, the `elig` hard filters, `substitute` with the transfer ratios, the exercise detail's Alternatives, custom exercises). Defaults kept: OI-A10 (the goal kind is derived, no new column), OI-A22 (a test-day record raises no WE-S1 notice), OI-A23 (an added set is an estimator observation but not a ladder set), F14 off until the user switches it on. **Done when**: after three exposures, a legs or push session the user assembles or repeats gets loadable prescriptions that land within ±1 RIR of target most of the time (`v_prescription_error`). |

---

## 2. Defaults chosen by Claude (overrule any time)

| ID | Topic | Default |
|----|-------|---------|
| F1 | Supabase schema | New schema `fitness` (exposed to the Data API), plus `fitness_private` (helper functions, allowlist; not exposed) and `fitness_review` (analysis views for Claude Code reviews; not exposed, no grants). Grants to `authenticated` and `service_role` only, never `anon`. |
| F2 | Exposing the schema | The **user** adds `fitness` under Dashboard → Data API → Exposed schemas, keeping `public` and `graphql_public` (current value verified by probe: `public, graphql_public`). Claude never runs `supabase config push`, `supabase db push`, `migration repair`, or `alter role authenticator set pgrst.db_schemas` on this shared project. Verification afterwards with the PGRST106 probe. |
| F3 | Migrations | SQL files in `supabase/migrations/` (names prefixed `fitness_`), every object schema-qualified, applied through the Supabase MCP `apply_migration` **after the user has seen each migration**. `get_advisors` (security + performance) after each one; zero new fitness findings expected. `supabase db dump --schema fitness --data-only` before every migration once data exists. |
| F4 | Free plan | The project is on the Free plan (no backups, read-only above 500 MB, pause after 7 quiet days). Mitigation: the phone database is the source of truth, monthly in-app JSON export, size guard view, chess usage keeps the project awake. |
| F5 | Optional extension | Install `pg_jsonschema` in the `extensions` schema for structural validation of challenge definitions. **Approved by the user 2026-10-08**: installed by migration `fitness_007_jsonschema_extension` (M0); the CHECK on `challenge_definitions` would follow in `fitness_018_jsonschema_constraint`, deferred by D33. |
| F6 | Running | Calendar events classified as runs (`hardlopen`, `run`, `duurloop`, …) count as leg fatigue only (no hypertrophy credit) plus timing penalties. Running priority setting default `low`. Health Connect (runs, weight, sleep) is a later milestone. |
| F7 | Equipment | First-run equipment checklist with Basic-Fit defaults (all editable): plate sizes (one list, 1.25 kg included only when the user adds it; gym plates are assumed unlimited, so no count per size is asked), dumbbell list, machine stack step in kg or lb (default 10 lb; lb stacks show the kg equivalent), Smith bar weight unknown → ask, assisted machine `{unit lb, step 5, offsetKg 6.5, ratio 1.0}` (no name field; the catalog label is used), own dip belt with the gym plate sizes (unlimited per size, capped by the belt maximum and items on the belt). `requires_station` on every exercise. |
| F8 | Deloads | General training deloads in the same week as the active challenge's deload/test week (sets ×0.5, load kept, RIR +2), plus reactive triggers. Without an active challenge: reactive only. |
| F9 | Test dates | Block-relative: each block is 5 weeks counted from the test actually done (matches the Todoist "every 5 weeks" recurrence). Offered as a `P` rule because the artifact's table lists fixed dates. |
| F10 | Test order | From the Todoist test-day task (tag `A`): weigh-in → warm-up → weighted 3RM → max strict reps → one-arm dead hang L/R (fixed neutral handle) → one-arm top hold L/R → least assistance for one rep L/R. A `P` proposal moves max reps to the end (grip fatigue). |
| F11 | Session time budget | **Revised 2026-10-07 (C49):** calendar gym events are training time (travel happens before them — the existing events say "leave 19:35" for a 19:45 start), so budget = event duration − 5 min changeover; without an event, the user's default (75 min). Time-budget chips 45 / 60 / 75 / 90+. |
| F12 | Exercise stability | Main lifts locked for one mesocycle (5 weeks, aligned with the challenge block). At most one third of accessories rotated per mesocycle, only systematically (stalled, disliked, new region coverage). No randomness. |
| F13 | Supersets | Offered only when the plan exceeds the time budget or the user enables them; only pairs with stimulus-vector cosine ≤ 0.2. |
| F14 | RIR calibration sets | Opt-in, at most one per week, only on machine/cable/isolation exercises, never on challenge lifts. |
| F15 | Feedback prompts | Optional 1-tap readiness (Worse/Normal/Better) + elbow check (0–3) before gym sessions, session effort (sRPE 0–10) + joint pain after, soreness asked at the next session for the muscles about to be trained. Tap budget ≤ 6 per session outside set logging. The elbow check is never reduced while a challenge pain rule is active. |
| F16 | Visual style | **Superseded by D31 (2026-10-11).** Was: Material 3, seed colour teal `#0B6A60` (continuity with the OAP artifact), advisory amber `#9C5217`, safety red `#8E3226`; follows system light/dark; dynamic colour optional. Still valid: calm standard motion. |
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
| C74 | Catalog flags | `exercises.flags_offered text[]` (subset of E6) holds the set flags the logger offers for an exercise (UX 11.9 "catalog flags"); challenge items may add flags through the definition overlay. Added 2026-10-08 in migration 008 because DATA_MODEL 9.2.1 had no column for it. |

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
7. Optionally change any `P` rule in the OAP challenge setup or later on the Proposals screen; they start at the recommendation (D35).
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
- 2026-10-08 — M1 planning decisions (user Q&A after M0): D17 manual re-push, D18 full `ui.prefill` in M1,
  D19 catalog summary review before seeding, D20 M1 split into M1a / M1b. D2 updated with the confirmed
  phone (OnePlus CPH2747, Android 16). PLAN.md, ALGORITHMS_PLANNING build order, ANDROID OI-25 and
  DATA_MODEL open issue 12 updated accordingly.
- 2026-10-08 — M1a build decisions (user Q&A): D21 (LOAD-1 and BW-1 in full and REST-1 as its base policy
  move into M1a), D22 (empty session only until M2). Migrations 008 and 010-015 are reviewed by the user as one
  batch; 009 follows the catalog summary approval (D19). Docker Desktop is installed on the dev machine, so the
  two-user RLS tests that need a second allowlisted user run on a local Supabase stack (DATA_MODEL open issue 14
  resolved). ALGORITHMS build order, ALGORITHMS_PLANNING build order and DATA_MODEL open issue 14 updated.
  C74 added (catalog column `flags_offered`); DATA_MODEL 9.2.1 updated.
- 2026-10-08 — Catalog 1.0.0 review (D19, user comments on the summary page and Q&A): grip-specific coefficients for
  the pull-up family, the assisted machine split into three grips, decline presses, leg press feet high and wide,
  cross-cable lateral raise, active hang and a general one-arm dead hang added; hang exercises keep forearms 0.5 (the
  hold factor stays the tuning knob). ALGORITHMS_PLANNING 10.8 records the released seed (158 rows).
- 2026-10-08 — OAP definition v1 (unpublished draft) corrected on the user's instruction: the assisted one-arm
  pull-up on the machine offers no `torso_rotation` flag and its C1/A1 items lose `stopSetOn: torso_rotation` (the
  knee platform prevents rotation); the finger-assisted variant keeps the flag. `ui.reasonchips` offers the
  `torso_rotation` reason only when the exercise offers that flag (ALGORITHMS_PLANNING 19.2, UX 11.9, 19.3, GF-UX-07/14).
- 2026-10-08 — M1a live-session decisions (user Q&A): D23 (a set changed today carries forward to the next set's
  prefill; ALGORITHMS_PLANNING 19.7 updated) and D24 (eccentric and top-hold round cards move to M2).
- 2026-10-09 — F7 checklist simplified (user feedback on the M1a build): no "plates per size" (gym plates are
  unlimited; the checklist writes `count` 20 per size, the top of the range), 1.25 kg is part of the plate-size list
  instead of a separate Yes / No / Not sure question, the machine stack step is entered in kg or lb (default 10 lb,
  stored as a `stack` scheme with `unit` lb), and the assisted machine has no name field. Belt plates are
  unlimited per size too: the checklist writes `count` = `max_items`, so only `max_kg` and `max_items` limit a belt
  load, and LOAD-1 already picks the fewest plates. The algorithm already supports lb stacks (ALGORITHMS B2.4). UX 8.7 and DATA_MODEL 9.3.3 updated.
- 2026-10-09 — Params 1.0.1 (user approval): `load.default_plate_inventory` counts 4 -> 20 per size, so the
  default barbell (used without checklist rows) also treats plates as unlimited. Params-only release: no module or
  engine version change (ALGORITHMS A7). Seeded by migration `fitness_019_algo_params_1_0_1`, which supersedes
  1.0.0. ALGORITHMS A8.1, B2.4, Part C and DATA_MODEL 9.1.10, 9.3.3, 13.2 updated.
- 2026-10-09 — Catalog 1.0.1 (user Q&A after the first gym test: the ab crunch machine showed "no loadable values"):
  stack stations `ab_crunch_machine`, `preacher_curl_machine`, `triceps_extension_machine` and `dip_machine` are added
  and the four machine exercises require them (the dip machine previously required only the bodyweight dip station).
  Chosen over an app-side generic stack so each machine keeps its own calibration. Existing checklists gain the new
  rows at app start, built from the saved stack settings and left unconfirmed. Migration
  `fitness_020_catalog_1_0_1`; ALGORITHMS_PLANNING 10.8 and DATA_MODEL 13.2 updated.
- 2026-10-11 — Live-session fixes from the second gym test (user feedback): no rest timer or alarm after the working
  set that finishes the session's planned work (UX 19.8 timer table); the session notification is reposted by
  `RestTimerController` from the stored timer state, so the first rest no longer shows the count-up.
- 2026-10-11 — M1a closed (user Q&A during the verification): D25 moves the general warm-up block to M2. The
  on-device rest-timer checklist (ANDROID 15.6) passed T1, T2, T4, T5, T7, T8, T11, T13 and T14 (T3 and T5 on the
  user's report); the self-test now uses the real rest cap (`rest.timer_hard_max_s`) so the notification's +15 s
  works as in a session. PLAN.md M1a result and README M1a log updated.
- 2026-10-11 — M1b scope decisions (user Q&A at the start of M1b): D26 (silhouette muscle map in M1b), D27 (custom
  exercises move to M3), D28 (a past session can gain a forgotten exercise), D29 (idle protection in M1b, review
  bundle in M5). Defaults for M1b, following the milestone order: exercise detail shows the last five sessions of an
  exercise without an e1RM sparkline until SSE-1 logs estimates (M3, C3: the UI never recomputes e1RM); its
  Alternatives section waits for SUB-1 (M3) and "Used by" for the challenge engine (M2); the free-exercise-db
  instructions are not bundled yet. Telemetry events whose feature arrives later are emitted with that feature:
  `pr_detected` with `ui.progress` (M3), `warmup_item_done` with the warm-up block (M2, D25), `rest_tone_skipped`
  and `focus_mode_toggled` with the rest tone and focus mode, `session_overrun_shown` with TIME-1 (M3), and the
  Today, Week, calendar and challenge events with their screens.
- 2026-10-11 — M1b build notes (defaults, no user decision needed): Room v2 adds `user_exercise_prefs` and `exports`
  through an `AutoMigration` 1 -> 2, covered by `FitnessDatabaseMigrationTest`. The full export writes timestamps
  exactly as the sync DTOs do (milliseconds; `server_updated_at` as returned) instead of the microseconds DATA_MODEL
  15.5 printed; DATA_MODEL 15.5 updated. N-SYNC fires for dead letters or an outbox entry older than
  `sync.staleNoticeDays` (7, new `PlatformConfig` key; ANDROID 25.4) rather than "no sync for 7 days", because the
  phone keeps no durable last-sync time. `hold_interrupted` waits until a hold's state survives process death (the
  timer lives in memory today). Migration 017 lists `user_id` first in `v_data_quality` (R-V3); DATA_MODEL 14 and
  19.9 updated (view sentinels V-09 to V-20).
- 2026-10-11 — D30 (user, during the M1b phone checks): changing an exercise preference shows no Undo snackbar; the
  segmented control is the undo. UX 6.5 and 13.4 updated; the `action_undone` registry row no longer lists preference
  changes.
- 2026-10-11 — D12 (user, during the M1b phone checks): Dutch exercise names appear (exercise detail header, the
  add-exercise sheet) and match in search only when the phone's language is Dutch (`nl`); otherwise the app shows and
  searches English names and aliases only, and the search hints no longer mention Dutch. Calendar titles are
  unchanged (F25). UX 5, 13.2, 13.3, 23 and 24.1 and ANDROID 18 updated.
- 2026-10-11 — UX changes (user, after M1b): the catalog list drops the Pattern filter (muscle, equipment,
  preference, "Available here" and sort remain; the pattern still shows on exercise detail). The quick check in
  session detail keeps one layout before and after its 24 h edit window: "Session effort: 7" with a "Change" link,
  the pain reports with "Add pain", the note with "Change", and "Editable until Mon 12 Oct 11:10"; after 24 h the
  links and the hint disappear. Pain is add-only in History: removing a report stays limited to the 10 s "Undo
  (wrong tap)" (C33, safety). `pain_reported` gains the context value `history`. UX 13.2, 15.3 and 20 updated.
- 2026-10-11 — D31 (user): the UI moves to the "Bento" dark design, chosen from four mock directions (Mono, Volt,
  Instrument, Bento), and the app becomes dark only; F16 is superseded. Defaults taken with it: Manrope replaces
  Roboto Flex (one variable font used at weights 500 to 800, bundled, `tnum` verified); section labels are sentence case
  instead of uppercase overlines; the rest countdown sits in a ring instead of above a bar; the bottom navigation is a
  floating tile; the RIR chips keep D9 (no prefill, no highlight), so the mock's "aim" marker is not built. The
  `profiles.theme` and `profiles.dynamic_color` columns are kept and ignored (no migration). UX 2, 3, 4, 11.7.3, 11.13.2,
  15.9, 15.12, 23, 24 and 25, ANDROID 2 and 27, DATA_MODEL 9.3, CHALLENGE_ENGINE 5.7 and PLAN.md 8 updated.
  Found in the phone check (OnePlus, 363 x 792 dp): the set card pushed the RIR row below the fold, so the log row is
  now pinned in its own tile under the set card (hidden while a rest counts down, 500 ms input guard) and the
  exercise list only takes the height it needs (UX 4.3).
- 2026-10-20 — D32 (user): M2 is split into M2a (the pure challenge engine in `:algo` and `:sim`) and M2b (the app,
  calendar and cutover). Engineering defaults taken with it, all within CHALLENGE_ENGINE: validation stage 1 is a
  strict typed decoding of the definition in Kotlin (required keys, types, enums and no unknown keys); the JSON Schema
  file `cds.v1.schema.json` and the optional server CHECK (migration `fitness_018_jsonschema_constraint`, F5) are
  written in M2b, when the definition is first published. The contract's coverage fields need `setcredit` and arrive
  with it (M4); M2a exports the reserved sessions, blocked days, gap rule, protected windows, owned muscles and mode
  effects. Items with `progression: delegate:rx` (the B row and dips) leave the materializer without a load until
  RX-1 exists (M3); the logger prefills them from last time (D18). TIME-1 (M4) is an input of the materializer, so
  add-on capacity is computed only when an estimate is supplied. PLAN.md 11 updated.
- 2026-10-20 — M2a built (D32): the challenge engine in `:algo` and the persona simulations in `:sim`; how it was
  built is CHALLENGE_ENGINE 16.1 (version 1.3). Two corrections to the unpublished `oap@1.0.0`: `struct.resume.interrupt`
  compares `event.gapDays >= 1d` (Days do not compare with a plain number, 3.3), and `safety.elbow.stop` only enters
  injury from outside it, so a new stop signal during the protocol no longer restarts the 14 days; persisting pain is
  handled by P14 (extension) as OAP_CHALLENGE 4.1 always said ("ends the protocol even if pain persists"). One engine
  guarantee added with it: no rule outside the safety stage takes the mode out of injury, and a block rollover during
  injury keeps the injury mode (4.8, 11.1).
- 2026-10-20 — M2a review fixes (D32): an independent read of the engine against CHALLENGE_ENGINE found and M2a fixed:
  "train now" could break hard constraints (the trial is now a fixed session charged with every violation it causes);
  with P16 approved a right-arm-only rep did not pass the phase-3 gate (unseen Bool sides now read false); a late
  `resume` could revive an abandoned or completed enrollment; a non-ASCII letter hung the FitExpr lexer; Undo of an
  in-session rule wiped a later rule's change; an amendment cycle hung the fold. Also aligned with the spec:
  `restDayBefore` scope and runs, derived events once per root, a block start claims the `$mode` write, `pause` rules
  see the pre-event state, pins keep their step. Home habits for a day (`HabitPlanner`) were missing and are added.
  The challenge modules stay at 1.0.0: nothing outside the repo has used them yet, and the persona snapshots are
  unchanged. CHALLENGE_ENGINE 16.1 lists the choices.
- 2026-10-20 — D33 (user): alarm-backed warm-up interval (OI-27 resolved; ANDROID 12.2, 12.3 and 7.10 `rest_started`
  updated, UX 11.7.6 and 19.9 rows updated), the plank Start chip (UX OI-15 resolved), P20 kept in `oap@1.0.0`
  (OAP-OI-14 resolved), migration 018 deferred (F5, DATA_MODEL 13.2). PLAN.md 11 lists the M2b build order.
- 2026-10-20 — D34 (user): reason codes for pause and correction (contexts `pause`, `correction`; codes `holiday`,
  `illness`, `injury`, `loaded_different`, `value_wrong`); DATA_MODEL 9.1.3 seed and the migration table (021), the
  bundled `reason_codes.json`, CHALLENGE_ENGINE 16.2 "Reason codes" updated.
- 2026-10-21 — Habit reminders arm in two stages (ANDROID 17 updated, `reminders.preLeadMin` added to 25): on the
  phone `setExactAndAllowWhileIdle` became an inexact alarm up to 1 h late, so a 10 min-window pre-alarm 15 min
  ahead now arms `setAlarmClock` for the reminder time; the next-alarm icon shows only for those minutes.
- 2026-10-21 — D35 (user): defaults first, less text. Challenge setup shrinks from ten steps to three (Overview with
  grip, Starting point, first two weeks with Start), the starting point offers "Start with a test week" or "Enter my
  numbers" (import of the program position under "I'm mid-program"), proposals start at the definition's
  recommendation with a "Customize" link instead of 27 required decisions, and the Proposals screen shows switches.
  D8 annotated; UX 2 (principle P15), 14.4, 14.8 and the telemetry table, CHALLENGE_ENGINE 10.2 and 10.3, OAP_CHALLENGE 6 and PLAN.md updated.
- 2026-10-21 — D35 applied app-wide (UX 2, principle P15): helper texts cut to one line or removed, ids, codes and
  engine terms replaced by plain words (gate rows show "Goals"; the x1.10 estimate and 80 % range of D7 moved behind
  "Other estimates"), and optional inputs moved behind collapsed rows: equipment details, profile birth year and name,
  the live set card's reason and flag chips, the Summary quick check, set-edit reasons and flags, the pause previews,
  muscle coefficients and the alarm service details. No required input, data, rule or safety behaviour changed. The
  Challenges card shows the first sentence of the definition's summary (no evidence note), and the disabled "Log test
  day" button is hidden until the test-day wizard exists. UX wireframes that still show longer text yield to P15.
- 2026-10-21 — `oap@1.0.1` (D35): a text-only patch of the OAP definition. Summary, gate labels ("Weighted pull-up
  strength" instead of "Weighted 1RM >= 0.65 x BW - 2 kg (gate e1RM, personal curve)"), all 27 proposal texts,
  messages and rule explanations are shorter and free of ids and engine terms; placeholders are unchanged. Proof that
  nothing computed changes: `DefinitionTextPatchTest` (the two files are equal once texts, `version` and `migrations`
  are set aside) and `TextPatchReplayTest` (all 16 persona runs end in identical states and weeks under both versions).
  1.0.0 is frozen as `docs/challenges/oap.challenge.v1.0.0.json` and stays bundled for enrollments pinned to it;
  migration 022 publishes 1.0.1 (DATA_MODEL 13.2). Existing enrollments stay on 1.0.0 until the in-app upgrade of
  CHALLENGE_ENGINE 4.12 exists; during the test phase a new setup uses 1.0.1.
- 2026-10-21 — D36 (user): structured test protocols and the test-day wizard (M2b.4). Test items may carry `steps` /
  `stepsIfApproved` (CHALLENGE_ENGINE 2.4.7); `challenge.cds` and `challenge.materialize` 1.1.0, `ENGINE_VERSION` 1.1.0
  (migration 024 registers them). `oap@1.1.0` adds P21's five params and the `stepsIfApproved` of `str` and `assist`
  (minor, CHALLENGE_ENGINE 2.2; 1.0.1 frozen as `oap.challenge.v1.0.1.json` and bundled); migration 023 publishes it by
  patching the 1.0.1 row, proven byte-identical by the server checksum. UX 14.9 rewritten to the built wizard (D35:
  one card per step, protocol text folded, a few plain lines in the review and report, the D7 extras folded), UX 14.2's
  action row, CHALLENGE_ENGINE 2.4.7, 6.3 step 12 and 16.2, DATA_MODEL 13.2 (023-025). The first test day showed that
  `challenge_rule_events.rule_tag` lacked `D` (D7 rules): migration 025 (user approved) allows it, DATA_MODEL 9.7.2.
- 2026-10-21 — D37 (user: the first anchor is 5 reps with 2 in reserve on the personal curve): the first valid test fills
  every empty load. `oap@1.2.0` (1.1.0 frozen as `oap.challenge.v1.1.0.json` and bundled), migration 026; the setup's
  facts resolve grip-dependent exercises with the param defaults. CHALLENGE_ENGINE 16.2, OAP_CHALLENGE header, DATA_MODEL
  13.2 and README updated; `:sim` gains the persona `test_week_start` and an invariant that no block value is empty after
  a valid test.
- 2026-10-22 — D38 (user): no fixed weekday or order for the test and sessions A, B and C; the test goes first while there
  are no loads; rest-day rules stay. `oap@1.3.0` (1.2.0 frozen and bundled), `schedule.order.first`, engine 1.2.0
  (`challenge.cds` 1.2.0, `challenge.plan` 1.1.0), migration 027. Setup's test week is due from today (UX 14.8 step 2).
  The 2026-10-06 calendar rhythm in section 4 ("test day Sun") describes the program before D38. Also: the dashboard's
  phase names are centred under their numbers, and setup refuses a mid-program phase, block or week outside the
  definition and the engine's longest block (a typed week 51 had produced rows the server rejects).
- 2026-10-22 — D39 (user): the M2b.5 calendar UI lives in You → Calendars, the event sheet and the dashboard's This week
  row until the Week tab; the debug build reads the primary calendar read-only plus the test calendar while writes go to
  the test calendar; the event sheet has every correction choice with the scopes "this event" and "the whole series".
  PLAN.md M2b.5 lists the build steps. Built the same day: CHALLENGE_ENGINE 16.2 (calendar rows), README M2b log. Spec
  corrections from the build: the 9.3 example and GF-CE-25 weigh the C and A events 0.95 (the gym-hours term was left
  out), and ANDROID 16.7's guards select `_id = ?` on `events`, because the provider refuses a selection on `events/<id>`.
- 2026-10-22 — D40 (user, start of M2b.6): the cutover imports the real state as a finished elbow protocol (started
  2026-10-08, pain-free since 2026-10-12; the 11 October test did not happen) with the last pre-injury loads from the
  calendar; P29 adds a re-entry mode after the protocol, P28 the Pallof press progression; the Pallof press joins
  sessions A, B and C (replacing B's gym plank); the build's test data is wiped before the cutover; setup's mid-program
  import gains the injury mode, week ordinal, block counts and historical tests; the schedule editor stays deferred
  because the definition's times match; Todoist is closed after the first app week. `oap@1.4.0`.
- 2026-10-22 — D40 cutover done: migration 028 (oap@1.4.0, engine 1.3.0), the build's test data wiped (server SQL in
  `supabase/ops/20261022_cutover_reset.sql`, the app reinstalled), the real enrollment started at 69.9 kg with the user
  marking all three earlier tests as counting for the gates and P22 declined. Found after Start: the setup dropped the
  per-arm values of the earlier tests (fixed in the app, `SetupImport`), so the stored import lacks the 30 August hang,
  top hold and least help and the 29 September hang; and the newest gate-valid test (29 September) has no bodyweight, so
  the next test's comparisons need a decision before then.
- 2026-10-23 — D41 (user): `pt` is the newest gate-valid value per measurement instead of the newest gate-valid test, so a
  partial test (29 September, hang only) no longer hides the bodyweight and strength values of earlier tests; gates stay
  per test day (C62). Engine 1.4.0, `challenge.enrollment` 1.2.0.
- 2026-10-25 — D42 (user): "train now" tries every slot not done yet, including slots the plan dropped, starting now at any
  time of day; a trial may not make the week drop a slot the plan kept. Engine 1.5.0, `challenge.plan` 1.2.0 (migration 030).
- 2026-10-25 — D43 (user): "Stack setup" in the live exercise menu edits that machine's stack (unit, step, lightest,
  heaviest); the row becomes `measured` and the Equipment page's stack setting no longer overwrites it. App only.
- 2026-10-25 — D44 (user): no rowing machine in the OAP warm-up (`oap@1.5.0`); the fold handles `definition.migrated` and
  the app offers a previewed "Update available" for running enrollments. Engine 1.6.0, `challenge.enrollment` 1.3.0
  (migration 031).
- 2026-10-25 — D45 (user): pain regions `elbow_posterior` and `forearm` (migration 032); in OAP both set the elbow trace and the
  Pallof press is per side (`oap@1.6.0`, migration 033).
- 2026-10-25 — D46 (user): no separate "Rest over" heads-up at the rest end (`N-REST-DONE` retired); vibration plus the
  session notification switching to overtime is the alert. Channel `rest_done` no longer created. App only.
- 2026-10-25 — D47 (user): prehab is exempt from the deload set halving (`deload`, `test_pending`, `extra_deload`);
  `oap@1.7.0` (migration 034). No engine change.
- 2026-10-25 — D48 (user): a superseding `enrollment.created` may correct the imported earlier tests and nothing else; it
  restores the per-arm values the D40 setup dropped (30 August hang, top hold, least assistance as pin 35 lb; 29 September
  hang). Engine 1.7.0, `challenge.enrollment` 1.4.0 (migration 035); written by the debug action `amend_import`.
- 2026-10-27 — D49 (user, Q&A at the start of M3): M3 prescribes the exercises the user picks; `select`, `order`, `time`
  and the necessity gate of `elig` move to M4. A general session can start as a repeat of a past one (amends D22;
  `template_source` `repeat`). The OAP's delegated items (B row, dips) get RX-1 loads (resolves ALGORITHMS OI-A20). The
  pain stop removes only exercises with joint stress 2 or more on the painful joint (`reason.pain_stop_js_min`, resolves
  OI-A25). M3 is built in six steps, M3.1 to M3.6. PLAN.md 11, ALGORITHMS (B12.3, Part C, open issues, build order),
  ALGORITHMS_PLANNING 15.1 and 26, UX 27.2, DATA_MODEL E9 and OAP_CHALLENGE (OAP-OI-05 resolved) updated.
- 2026-10-27 — M3.1 built (D49): `fatigue`, `rcm`, `rircal`, `sse` and `hold` in `:algo`, with the 15 golden fixtures
  of ALGORITHMS D2 "Estimation" and unit tests on every worked example of B4-B8 (all reproduce; GF-SSE-02's 107.65 is
  107.655, so 107.66 on the KG grid). Build notes (defaults, no user decision needed): the estimator column of REASON-1
  (classes and the missing-RIR imputation, B12.2-B12.4) is built now because the estimators need it; the rest of
  REASON-1 stays in M3.3. A session that moves no SSE-1 or HOLD-1 state (only a lower bound below its gate) keeps the
  stored state, because a re-prediction would change the decaying trend (ALGORITHMS B6.4). RIRCAL-1 keeps the number of
  checks in the last check week next to the week (B4.1); the implicit update uses `reps + q_rep + b` as the capacity
  of every earlier set and `fatigue.rest_ref_s` when no rest is known (B4.4). RCM-1 writes `sd_lnk = sigma_lnk` and a
  new `pooled` flag when no window is informative, and the pooled class fit uses `k0` for the per-set sd (B5.1,
  DATA_MODEL 10.2). `GoldenRunner.run` takes the params view (the self-test and `sim golden` pass the bundled params).
  The app does not call the estimators before M3.2, so `ENGINE_VERSION` stays 1.7.0.
- 2026-10-27 — M3.2 built (D49): Room v7 adds `params_snapshots`, `recommendations` and `exercise_model_state` (an
  `AutoMigration`, tables only), all three push-only: a new install rebuilds them from the facts (DATA_MODEL 16 R2). The
  L8 layer is a pure replay in `:algo` (`pipeline/EstimationReplay`: per session RCM-1 for the exercises it contains,
  then SSE-1 with the new `k`, HOLD-1, and RIRCAL-1's updates effective from the next session) and a runner in `:data`
  (`engine/EstimationRunner`) that replays every finished session in order whenever a finished session, its sets or a
  challenge event change (a debounced Room flow, plus app start and the daily maintenance run). Build notes (defaults,
  no user decision needed): estimation snapshots get content-derived ids, so a replay of unchanged facts writes nothing
  and an edit adds superseding rows (DATA_MODEL 9.5.2); a challenge session is held back until its `session.completed`
  (and a test day's `test.completed`) exists, for at most 6 hours, so the challenge's engine facts never contain the
  session's own L8 update (CHALLENGE_ENGINE 3.9); the challenge facts' `userK` now come from the cached `rcm` state
  (`k_used`, `sd_lnk`, informative windows, the state hash as `fit_run_id`), the class prior only before an exercise's
  first fit (C59); a set's `pain_flag` without a reason code counts as the reason `pain`; RCM-1's pooled class value is
  used as B5.1 specifies, so another exercise of the class can move `k` before the exercise's own window (within the
  rate limit); the nightly FAT-1 personalisation (B7.5) does not run yet, every class uses the default `c_ref` and `d`.
  The exercise detail shows SSE-1's level and 80 % band per session as added kg (`ui.progress.window_weeks`,
  `ui.progress.band_opacity`); holds keep the list only. No server migration; `ENGINE_VERSION` stays 1.7.0 (no module
  version changes).
- 2026-10-27 — M3.3 built (D49): `reason` (the rest of REASON-1: the table's session and future effects, the pain stop of
  D49, the history rules), `rx` (RX-1 with scheme resolution, MODEL, DOUBLE, RULE with the shadow, the probe, deload and
  re-entry, the cap table, HOLD dispatch, the fallback and the unilateral combine), the `warmup` set generator, `rest@1.1.0`
  (outcome and fatigue modifiers, alternating pair, supersets, readout, adaptation) and `stall` (B14 with erfc), with 16
  golden fixtures (GF-RX-01 to 09 and 11, GF-WU-01, GF-REST-01/02, GF-REASON-01, GF-STALL-01/02) and unit tests on the
  Part C and Part D examples. `ENGINE_VERSION` 1.8.0 (`rest` 1.1.0; the other modules start at 1.0.0). Params 1.0.2 adds
  `reason.pain_stop_js_min` (D49); `:algo` tests use it, while the app and `:sim` stay on 1.0.1 until M3.4 brings the
  data migration that registers params 1.0.2 and `rest@1.1.0` on the server (shown before it is applied). Build notes
  (defaults, no user decision needed): `rir_pred[]` is computed at `M_use`, as C3.1 and C3.3 do, so review metric M4
  reads a too large `z_c` as a positive mean; a transfer prior without a state starts the prescription without a probe
  (B9.4 step 4, as SUB-1 does); without an SSE-1 state the warm-up uses the mid tier for compounds; the global stall
  minimum of 5 active exercises gates T1. Found while checking the fixtures: B9.4's coarse-step rule puts most machine and
  barbell exercises in DOUBLE (a 5 kg step at 125 kg on the leg press is 2.2 reps), as C15 intends; GF-RX-03 to 05 test
  MODEL itself, and GF-RX-03 needs C3.3's unrounded `M`. GF-WU-01's rests are 45 and 90 s (the last warm-up set takes
  the rest to work).
- 2026-10-27 — M3.4 built (D49): the live session shows RX-1's targets. `:algo` `pipeline/Prescribing` turns the stored
  facts of one session exercise into RX-1's input (B9.2: the SSE-1 state predicted to the session start, RCM-1's `k`,
  RIRCAL-1's state for `q_true`, the earlier exposures newest first with the prescriptions they were logged against, the
  loadables in `w_sys`, the transfer prior) and runs RX-1 and WARMUP-1; `:data` `PrescriptionRunner` stores the
  `rx.prescription` snapshot (scope `session_exercise:<id>`, content-derived id, the warm-up sets in its outputs) and pins
  it on the session exercise (`recommendation_id`, `progression_mode`, the planned columns). The set card prefills the
  target as UI-04's second source with "Target ... · aim: N in reserve" and (i) for the Why sheet (summary, numbers,
  module and params, evidence label, Copy trace); generated warm-up sets come first with Done; a probe asks for a weight
  and, once logged, a follow-up prescription supersedes the first (B9.8 build note); the B row and the dips
  (`delegate:rx`) take the item's reps and RIR as their scheme (D49); set rows now store `planned_rir`,
  `planned_rep_min/max` and `planned_effective_load_kg`. Summary "Next time" runs the L8 update and RX-1's preview for the
  next day (not stored) and lists changes with a Why. Today gains "Repeat" (D49): the finished general sessions newest
  first; the copy keeps exercises, order, load modes, sides, equipment and planned set counts (`template_source` `repeat`,
  `session_started.source_session_id`). The app and `:sim` run params 1.0.2. Migration 036 registers params 1.0.2,
  `rest@1.1.0`, the `repeat` CHECK value and the payload key; it is shown to the user and applied before the M3.4 APK.
  Found and fixed: the push order of M3.2 put `recommendations` last, against DATA_MODEL 11.3; a session exercise that
  points at its prescription needs it pushed first (FK), so `recommendations` now follows `params_snapshots`. Build notes
  (defaults, no user decision needed): scheme preferences are empty until the Preferences screen (M3.5), so schemes are
  the engine defaults or the item's; S is the session exercise's planned set count; "used DOUBLE this mesocycle" reads
  as "the last prescription was DOUBLE" until mesocycles exist (M4); a unilateral exercise keeps each side's own targets
  (B9.13 `independent`); timed holds and eccentric-only items keep "last time" until HOLD-1's prescription is wired
  (M3.5); RULE items keep their plan rows and no shadow snapshot is stored yet; "Next time" shows at most six lines
  without "Show all".
- 2026-10-27 — M3.5 built (D49): suggestions, warnings and preferences. `:algo` gains `autoreg` (AUTO-1 with the UI-06
  trigger on the model basis, C57: `delta_ex` after every working set, the proposal from RX-1 re-run at
  `M_use * exp(delta_ex)` behind the 1.5 RIR hysteresis and the -8 %/+6 % bounds, the safety drop, the stop advice, the
  RULE shadow, `delta_today`) and `warnings` (WE-1: WE-S1 to S5, WE-P1 to P7, P9 to P11, WE-T1, WE-T2, WE-V1, WE-V3,
  ranked and de-duplicated), both at 1.0.0 as registered by 005, so ENGINE_VERSION stays 1.8.0; golden fixtures
  GF-AUTO-01/02 and GF-WE-01 to 07 reproduce the spec's numbers and join the on-device self-test (47 fixtures). `:app`
  gains `ui.warndisplay` (UI-10). Room v8 adds `rep_preferences` (pushed and pulled, LWW; the server table exists since
  010, so there is no server migration). The live session shows the AUTO-1 card in the rest area (Apply, Keep, Why;
  logging the next set is an implicit Keep; Apply makes the proposal UI-04's accepted adjustment, with Undo; a stop card
  ends the exercise with a reason), the SAFETY notices (acknowledged with OK), one advisory banner, "N more notes" and a
  dot on the affected target; `autoreg.suggestion` is stored for each shown card and `warnings.evaluation` at finish.
  You > Preferences has the Sets and reps section (all exercises, compound, isolation, per-exercise overrides; each with
  its evidence note and "Use suggested", D10) and the RIR check sets switch (F14, off by default). With the switch on,
  the first prescription of a session lets RIRCAL-1 choose the check exercise and marks its last working set; the card
  asks for the predicted reserve, then the extra reps to failure, and logs `set_kind` `calibration` with
  `calib_extra_reps`, which L8 already reads (B4.3). A sets preference sets S for exercises the user picked; the
  preferences enter the `rx.prescription` inputs. Build notes (defaults, no user decision needed): the joint-flag
  input of the check choice is false until `safety`'s flags are stored; the warnings that need M4's targets, time model
  or week volume are not fed (B15 build note); WE-S3's repeat advice waits for the wellness read; stall tiers are
  applied through RX-1's scheme transforms only once STALL-1's weekly evaluation is stored (it needs four valid
  exposures in 42 days, so it would not fire before then); timed holds, eccentric-only items and the RULE shadow keep
  their M3.4 behaviour; a suggestion on a unilateral exercise is evaluated after the pair, per side.
