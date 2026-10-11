# UX and UI specification

| Field | Value |
|---|---|
| Document | `docs/UX.md` |
| Version | 1.1.0 (2026-10-07; aligned with D13-D16, the revised F11, F21-F27 and the cross-document resolutions C37-C65) |
| Status | Draft for implementation (M1 onward) |
| Source of truth | `docs/DECISIONS.md` (D1-D16, F1-F27, C1-C65). Where this document and DECISIONS.md disagree, DECISIONS.md wins and this document is the one that is wrong. Where DECISIONS.md section 3.1 names another document as the owner of a topic (for example ALGORITHMS_PLANNING for the `ui.*` functions, C53; ANDROID for platform behaviour, C54; DATA_MODEL for stored vocabularies, event names and column names, C38, C39, C55), this document references the owner instead of restating it. |
| Research inputs | `docs/research/ux_ui.md` (primary), `planning.md`, `challenge_engine.md`, `load_prescription.md`, `tech_stack.md`, `exercise_selection.md`, `data_model.md`, `critique.md` |
| Readers | The user (product owner) and future Claude Code sessions that implement the app and periodically review its algorithms |

This document specifies everything the user sees and touches: design principles, information
architecture, every screen and bottom sheet (purpose, content, states, actions, wireframe, empty /
error / offline behaviour, and which engine module feeds it), the visual system, haptics, audio,
accessibility, copy rules, the display side of the deterministic UI functions (`ui.*` modules, whose
logic is specified in `docs/ALGORITHMS_PLANNING.md` section 19, C53), the user-visible behaviour of the
platform controllers for the rest timer and hold timer (whose mechanics `docs/ANDROID.md` owns, C54),
and the telemetry events the UI emits into `session_events` (C24; event names follow the DATA_MODEL
section 9.8.1 registry, C39).

It does **not** specify the training algorithms themselves. Where a screen shows an algorithm's
output, this document names the module id from the registry (for example `today`, `rx`, `rest`,
`challenge.plan`) and lists, in section 22, exactly which fields the UI expects from it. If an
algorithm specification does not provide such a field, that is an interface gap and is listed in
the Open issues section.

## Contents

1. Conventions used in this document
2. Design principles
3. Information architecture and navigation
4. Visual design system (tokens, typography, spacing, shape, icons, motion, dark mode, contrast, font scale)
5. Number, unit, date and language formatting
6. Interaction components and patterns (steppers, chips, keypad, snackbar, sheets, haptics, audio)
7. Global states: sync, offline, safety banners, permission warnings, errors
8. Screens: onboarding
9. Screens: Today
10. Screens: Week
11. Screens: Live Session (full-screen flow), including the rest timer
12. Screens: Session Summary
13. Screens: Exercises (catalog)
14. Screens: Challenges, Challenge setup, Test-day wizard
15. Screens: You
16. Bottom sheets (Why, Pain, Readiness, Skip, Swap, Add exercise, Keypad, Weigh-in, Template picker, Day, Calendar event, Notes, Finish, Set edit)
17. Warnings UI (UI-10 display policy)
18. Notifications and reminders catalogue
19. Deterministic UI functions (`ui.todaycard`, `ui.prefill`, `ui.reasonchips`, `ui.prompts`, `ui.volumebars`, `ui.warndisplay`, `ui.progress`: references to ALGORITHMS_PLANNING section 19 plus display details) and platform controllers (rest timer, hold timer, telemetry writer)
20. Telemetry event catalogue (when the UI emits each `session_events` type of the DATA_MODEL registry, and the UI-originated `challenge_events`)
21. Screen to module map
22. Interfaces the UI requires from engine modules
23. Accessibility
24. Copy guidelines
25. Golden fixtures and tests (the real week of 2026-10-05 to 2026-10-11, plus DST end 2026-10-25)
26. Open issues
27. Implementation notes for Claude Code

---

## 1. Conventions used in this document

**Evidence grades.** Every constant and every design rule that rests on evidence carries one of:
`strong`, `moderate`, `weak`, `expert-opinion`, `engineering-fact`. A constant that is a guess or a
convention is marked **tunable** and the table says which logged data would tune it.

**Constant keys.** Constants of the deterministic UI functions live in the versioned params JSON
under the key `<module>.<constant>` with a lower snake_case constant name (C41), for example
`ui.todaycard.close_rel`; they are registered in ALGORITHMS_PLANNING section 23.19 (C53). Constants of
the platform controllers (rest timer, hold timer, telemetry writer, reminders) are not engine
parameters because they never change a recommendation; they live in `:app` `PlatformConfig`, outside
`params_hash`, are logged in event payloads and are versioned through `app_version` (C54; register in
ANDROID section 25).

**Module ids.** Module ids are exactly those of the registry: `clock`, `load`, `bodyweight`,
`calendar.classify`, `safety`, `challenge.cds`, `challenge.fitexpr`, `challenge.enrollment`,
`challenge.eca`, `challenge.materialize`, `challenge.plan`, `challenge.contract`,
`challenge.calendar`, `setcredit`, `readiness`, `rircal`, `rcm`, `sse`, `fatigue`, `hold`, `stall`,
`targets`, `runs`, `allocator`, `today`, `weekplan`, `catalog`, `pref`, `elig`, `select`, `order`,
`substitute`, `rotate`, `time`, `injury`, `rx`, `warmup`, `rest`, `reason`, `autoreg`, `warnings`,
`ui.todaycard`, `ui.prefill`, `ui.reasonchips`, `ui.prompts`, `ui.volumebars`, `ui.warndisplay`,
`ui.progress`, `tuner`, `sim`, `replay`. Each is versioned `id@semver`, all start at `1.0.0`.

**Mapping of the UX research lens ids (UI-01 ... UI-12) to registry modules.**

| Lens id | What it was | Where it lives now |
|---|---|---|
| UI-01 | Today verdict composer + reason chips | `ui.todaycard`, specified in ALGORITHMS_PLANNING section 19.1 (C53); display details in 19.1 here. It does not re-threshold rest advice; it reads `today`'s verdict `TRAIN / REST_SOFT / REST_HARD` and uses the relative close margin `ui.todaycard.close_rel` = 0.10 (C53). |
| UI-02 | Time-budget trimming | `time` (TIME-1) executes the tier order (C19; ALGORITHMS_PLANNING section 17.7). The UI only offers the chips. |
| UI-03 | Rest timer state machine | Platform controller `RestTimerController` in `:app`; behaviour owned by ANDROID section 12 (C54), user-visible behaviour in 19.8. Durations come from `rest` (REST-1, C18). |
| UI-04 | Prefill, snapping, steppers | `ui.prefill`, specified in ALGORITHMS_PLANNING section 19.7 (C53); display details in 19.2. |
| UI-05 | Reason chips, skip/defer | `ui.reasonchips`, specified in ALGORITHMS_PLANNING section 19.2 (C53); display details in 19.3. Reason semantics belong to `reason` (REASON-1, C16). |
| UI-06 | In-session suggestion trigger | Inside `autoreg` (AUTO-1 + UI-06 trigger, C16), for general items only (C46). The UI only renders the card. |
| UI-07 | Prompt budget | `ui.prompts`, specified in ALGORITHMS_PLANNING section 19.3 (C53), with F15 values; display details in 19.4. |
| UI-08 | Calendar classifier + matcher | `calendar.classify` (C20) and `challenge.calendar`. The UI renders and corrects. |
| UI-09 | Weekly volume bars | `ui.volumebars`, specified in ALGORITHMS_PLANNING section 19.4 (C53), reading `setcredit` (C6) and `targets` (C14); display details in 19.5. |
| UI-10 | Warning display policy | `ui.warndisplay`, specified in ALGORITHMS_PLANNING section 19.5 (C53), reading the single `warnings` engine (C14); display details in 19.6. |
| UI-11 | Progress charts + PR detection | `ui.progress`, specified in ALGORITHMS_PLANNING section 19.6 (C53), reading `sse` (C4) and engine-logged per-set e1RM (C3); display details in 19.7. |
| UI-12 | UI telemetry logger | Platform `TelemetryWriter` writing `session_events` (C24; no `ui_event` table; event names from the DATA_MODEL section 9.8.1 registry, C39). |

**Screen ids.** Every screen and sheet has an id that is written into the `screen` field of every
telemetry event: tabs `today`, `week`, `exercises`, `challenges`, `you`; flows `onboarding.<step>`,
`session.live`, `session.summary`, `test.wizard`, `challenge.setup`; sub-screens such as
`exercises.detail`; sheets `sheet.<name>`.

**Wireframes** are ASCII, drawn for a 393 x 873 dp portrait phone (a typical Xiaomi / OnePlus /
Oppo 1080 x 2400 panel, D2). Symbols: `( - )` / `( + )` steppers, `[ x ]` buttons or chips, `(i)`
info / Why, `:` overflow menu, `v` minimise or collapse, `>` navigate, `*` planned, `o`
recommended, `v` (in lists) done, `x` blocked, `-` rest, `T` test day, `!` advisory, `!!` safety.

---

## 2. Design principles

The user asked for a "very clean" UI. In this app "clean" has a precise meaning: on every screen the
user can tell within one second what the single most important thing is and where to tap for it,
and nothing on the screen is decoration.

| # | Principle | Concrete rules | Evidence | Source |
|---|---|---|---|---|
| P1 | One primary action per screen | Exactly one filled (primary) button or one primary chip row per screen. Everything else is tonal, outlined or text. Live Session: the primary action is the RIR chip row (D9). Today: "Start ...". | expert-opinion | M3 guidance; Hick's law |
| P2 | One accent per screen | The teal primary colour marks the one primary action and progress. Amber (advisory) appears only when there is advice. Red (safety) appears only when there is a safety state. No colour is ever decorative and colour never carries meaning alone (always icon + text). | expert-opinion; WCAG 1.4.1 | F16; ux_ui visual spec |
| P3 | Thumb zone | Every in-gym action sits in the bottom 40 % of the screen. The top of the screen shows passive information only (titles, progress, the exercise list). Bottom-centre is easiest to reach; top corners are hardest. | moderate | Hoober 2013 (1,333 observations: 49 % one-handed, 36 % cradled) |
| P4 | Big targets in the gym | Minimum 48 x 48 dp everywhere with 8 dp between targets. Primary in-session controls 56-64 dp (steppers 64 dp, RIR chips 64 dp tall). | moderate | Parhi, Karlson & Bederson 2006: 9.2-9.6 mm thumb targets = 58-60 dp; WCAG 2.5.8 |
| P5 | Wet and sweaty fingers | No precision gestures: no swipe-to-complete, no swipe-to-delete, no tiny icons. A second tap within 300 ms on the same control is ignored. Controls that appear in the place where the user just tapped ignore input for 500 ms (input guard). | moderate | RainCheck (Wobbrock lab, ICMI 2018): wet-touch filtering improved accuracy 47.9 % |
| P6 | Undo instead of confirm | Actions are applied immediately and offered for undo in a snackbar (6 s default, 10 s for session-level actions). Confirmation dialogs are used only for: abandoning a challenge, deleting a custom exercise that has history, signing out, and discarding an unsynced local database. | expert-opinion | ux_ui; Material guidance |
| P7 | Layered explanations (F19) | Layer 1: at most 3 reason chips inline. Layer 2: the Why sheet (numbers, rule id, module version, evidence grade, citations, "use mine anyway" / "use suggested"). Layer 3: trace export for Claude Code reviews. Nothing more is ever shown inline. | moderate | Tintarev & Masthoff 2012; Laato et al. 2022 |
| P8 | Honest about evidence | Every claim is labelled with its evidence grade. Coach heuristics are called heuristics. Preprints are called preliminary. No "science says". | expert-opinion | C29; critique UNSUPPORTED list |
| P9 | The user's preference wins, safety always wins | Evidence conflicts produce a non-blocking warning with a one-tap "use suggested" (D10). Safety rules are always enforced and are shown in red with the rule that enforced them. General (non-challenge) mid-session load changes are suggestions, never automatic (D10, C16). The in-session rules of the user's approved challenge are the exception: they change the remaining sets immediately and show "Rule: ... · Undo" (D14, C46). The user's approved challenge rules also win over the generic load-jump caps; the excess is shown as a non-blocking warning (D13, C45). | (decision) | D10, D13, D14, C16, C33, C45, C46 |
| P10 | Never block logging | The phone database is the source of truth (F4). Nothing in a session ever waits for the network, the calendar or an engine computation. If the engine fails, the user can still log an empty session. | engineering-fact | F4, C23 |
| P11 | Precedence is visible | When messages compete for space they are ordered safety > challenge > the user's hard constraints > general advice, the same precedence the pipeline uses. | (decision) | Pipeline order L1-L8 |
| P12 | Determinism | Everything the UI decides (ordering of chips, card state, which banner shows) is a pure function of logged inputs and versioned constants, so a review can replay exactly what the user saw. | engineering-fact | C22, C24, C30 |
| P13 | Calm motion | Standard M3 springs. Expressive bounce only on two moments: set logged and challenge gate achieved. Honour "remove animations". | moderate | Google M3 Expressive research (restraint in utility contexts) |
| P14 | Ask little, ask once | At most 6 taps per session outside set logging (F15). Prompts that the user keeps skipping are shown less often, except safety prompts. | strong (that subjective measures are useful); expert-opinion (the budget) | Saw, Main & Gastin 2016; Foster 2001; F15 |

---

## 3. Information architecture and navigation

### 3.1 Destinations

Five bottom-navigation destinations (M3 navigation bar, icon + label, 5 destinations is the M3
maximum):

| Tab | Id | Question it answers | Main content |
|---|---|---|---|
| Today | `today` | "Do I train today, and what?" | Today card (verdict, primary option, alternatives, time-budget chips, Start), home habits checklist, week strip, prompts such as weigh-in |
| Week | `week` | "What does my week look like?" | Recommended vs planned per day, challenge / blocked / rest reasons, Plan it, app-written event markers, adopt, muscle volume bars |
| Exercises | `exercises` | "What can I do and what do I like?" | Catalog search and filters, exercise detail (muscle map, preference, history, loading, alternatives), custom exercise creation |
| Challenges | `challenges` | "Where am I in my challenge?" | Library, active dashboard (phase, block/week, gates per arm, next test, this week), rules, history, tests, report pain, pause/resume/abandon |
| You | `you` | "How am I progressing, and how is the app set up?" | Progress, History, Preferences, Equipment, Calendars, Rest and alerts, Prompts, Appearance, Transparency, Data and sync, Account |

Full-screen flows hide the navigation bar:

| Flow | Id | Entered from | Exits to |
|---|---|---|---|
| Onboarding | `onboarding.*` | First launch; "Redo setup" in You | Today |
| Live Session | `session.live` | Today Start; Week day sheet Start; template picker; session notification; mini-bar | Session Summary (finish), Today (abandon or minimise) |
| Session Summary | `session.summary` | Finish in Live Session | Today |
| Test-day wizard | `test.wizard` | Today on a test day; Challenges dashboard "Log test day" | Challenges dashboard (recalculation report) |
| Challenge setup | `challenge.setup` | Challenges library; onboarding last step | Challenges dashboard |

### 3.2 Navigation graph

```
                 +-------------------- Onboarding (first run) --------------------+
                 v                                                                 |
+--------+  +--------+  +-----------+  +------------+  +------+                    |
| Today  |  | Week   |  | Exercises |  | Challenges |  | You  |   bottom nav       |
+---+----+  +---+----+  +-----+-----+  +-----+------+  +--+---+                    |
    |           |             |              |            |                        |
    | Start     | day sheet   | detail       | dashboard  | Progress / History /   |
    v           v             v              v            | Preferences / ...      |
+-----------------------+  +----------+  +-------------+  |                        |
| Live Session (full)   |  | Custom   |  | Rules /     |  +-> Session detail (edit)|
|  - minimise -> mini-bar  | exercise |  | History /   |                           |
|  - Finish -> Summary  |  +----------+  | Tests       |                           |
+-----------+-----------+                +------+------+                           |
            v                                   | Log test day / Setup            |
   +------------------+                         v                                  |
   | Session Summary  |               +--------------------+   +----------------+  |
   +------------------+               | Test-day wizard    |   | Challenge setup|<-+
                                      +--------------------+   +----------------+
Sheets (modal, any screen): Why, Pain, Readiness, Skip, Swap, Add exercise, Keypad, Weigh-in,
Template picker, Day, Calendar event, Notes, Finish, Set edit.
```

### 3.3 Mini-bar while a session is minimised

When the Live Session is minimised (chevron `v` top-left, or system back from the Live Session),
a persistent mini-bar sits directly above the navigation bar on every tab:

```
+-------------------------------------+
| * Session A    32:10   Rest 1:42  > |   56 dp, tonal surface, whole bar = tap target
+-------------------------------------+
| Today  Week  Exercises  Chall.  You |
+-------------------------------------+
```

* Left: a filled dot in primary colour (a session is running) and the session name.
* Centre: elapsed session time (counts up, from the session's start Instant).
* Right: rest countdown if a rest timer runs; "+0:23" in advisory colour when overtime; nothing
  when no timer runs.
* Tapping anywhere on the bar returns to the Live Session at the current set.
* The bar is not dismissable while the session is in progress; it disappears on finish or abandon.

### 3.4 Back behaviour, deep links, restoration

* System back on a tab returns to Today; on Today it leaves the app (session keeps running in the
  foreground service).
* System back in Live Session minimises it (it never ends a session). Predictive back animation is
  the standard one.
* There are no deep links (C54). Notification taps, notification actions that open the app and the
  `AlarmClockInfo` show-intent open `MainActivity` with one string extra `route` whose value is an
  **internal route name**; ANDROID section 7.4 owns the parser and the mapping to Navigation3 keys. The
  names look like URIs but are not deep links: no intent filter declares the `fitapp` scheme, nothing is
  exported and the strings never leave the app. An unknown route opens Today. Route names used by this
  document (as in ANDROID section 7.4):

  | Route name | Opens |
  |---|---|
  | `fitapp://session/current` | Live Session at the current set (`LiveSessionKey`); Today if no session is in progress |
  | `fitapp://session/current/rest` | Live Session with the rest card in focus |
  | `fitapp://today` | Today |
  | `fitapp://habit/<habit_id>` | the home mini-session screen of that habit (`HabitSessionKey`); the `kind = home` session starts when the user taps Start there |
  | `fitapp://challenge/<enrollment_id>/test` | the test-day wizard |
  | `fitapp://you/data` | You > Data and sync (sync problems, export reminder) |
  | `fitapp://challenge/<enrollment_id>` | the Challenges dashboard (`ChallengeDetailKey`), used by N-PROGRAM, N-SAFETY and N-CELEBRATE (section 18) |
* Process death: the session, every logged set and the rest timer's absolute end time are persisted
  on every change. On relaunch with a session in progress, the app opens straight into Live Session
  at the current set; the rest timer resumes (or shows overtime if its end time has passed), and the
  event `session_restored` is emitted (section 20).
* The selected tab, scroll positions, open filters and unsent drafts (notes) are kept in saved
  state. Unsaved drafts never block anything.

### 3.5 Global elements

| Element | Where | Shown when |
|---|---|---|
| Safety banner (red) | Top of Today, Week, Challenges, Live Session | A SafetyState is active (pain protocol, injury mode) or a safety warning is unacknowledged (section 7.3). |
| Sync status chip | Top-right of Today header and You > Data and sync | Only when something is wrong or pending for long (section 7.1). Hidden when all is fine. |
| Offline indicator | Same chip | Device offline and the outbox is not empty. |
| Permission warning row | Top of Today; inside Live Session header as an icon | Notifications, exact alarm, battery optimisation or calendar permission missing (section 7.4). |
| Mini-bar | Above the navigation bar | A session is minimised. |
| Disclaimer | Pain sheet, injury banners, onboarding, You > Account | Always present in those places: "This app does not give medical advice." (C33) |

---

## 4. Visual design system

### 4.1 Colour tokens (F16)

The Material 3 tonal scheme is generated once, at build time, from the seed teal `#0B6A60` (for
secondary, tertiary and surface-variant roles) and committed as constants, so the colours are
deterministic and do not depend on a library at runtime. The roles below are then **pinned** to the
values taken from the user's OAP artifact for visual continuity. Dynamic colour (Material You) is an
optional setting, default **off**; when it is on, only the neutral and primary roles follow the
wallpaper, never `advisory` or `safety`.

| Role | Light | Dark | Use |
|---|---|---|---|
| `background` | `#F1F4F1` | `#0F1413` | Screen ground |
| `surface` | `#FFFFFF` | `#161D1C` | Cards, sheets |
| `onSurface` (ink) | `#131918` | `#E9EEEB` | Body text and numbers |
| `onSurfaceVariant` (muted) | `#4A5653` | `#B4C0BC` | Secondary text, maintain bars |
| `outline` | `#7A8683` | `#87938F` | Borders, dividers, skip-goal bars (dashed) |
| `primary` (teal, the one accent) | `#0B6A60` | `#4FB5A6` | Primary action, progress, done, improve bars |
| `onPrimary` | `#FFFFFF` | `#00201C` | Text on primary |
| `primaryContainer` | `#E2EDEC` | `#1E322F` | Selected chips, mini-bar |
| `advisory` (amber) | `#9C5217` | `#D08B4E` | Evidence warnings, overtime, deficit bars |
| `advisoryContainer` | `#F3EAE3` | `#302C23` | Advisory banners |
| `safety` (red) | `#8E3226` | `#D4796C` | Pain, injury mode, enforced safety rules |
| `safetyContainer` | `#F1E6E5` | `#312A27` | Safety banners |
| `pureBlack` (opt-in OLED) | n/a | `#000000` background, `#0B0F0E` surface | Only if "Pure black" is enabled |

Containers are the role colour mixed over `surface` at 12 % (light) and 14 % (dark).

**Verified contrast ratios** (WCAG 2.x relative-luminance formula, computed 2026-10-07; text needs
4.5:1, UI parts and chart lines 3:1):

| Pair | Light | Dark |
|---|---|---|
| ink on background / surface | 16.06 / 17.80 | 15.84 / 14.59 |
| primary on background / surface | 5.84 / 6.47 | 7.52 / 6.92 |
| advisory on background / surface | 5.22 / 5.78 | 6.63 / 6.10 |
| safety on background / surface | 7.19 / 7.97 | 5.98 / 5.50 |
| muted on surface | 7.64 | 9.14 |
| outline on surface (UI parts only) | 3.77 | 5.38 |
| onPrimary on primary | 6.47 | 6.94 |
| role text on its container (primary / advisory / safety) | 5.41 / 4.87 / 6.53 | 5.47 / 4.96 / 4.53 |

Because containers differ from `surface` by only about 1.2:1 in dark mode, every banner also has a
leading icon in the role colour (at least 3:1, so the banner is identifiable without the fill) and
a 1 dp outline in the role colour at 40 % opacity.

**High-contrast option** (You > Appearance): `onSurfaceVariant` becomes `onSurface`, outlines
become 2 dp, chart lines become 3 dp, chip fills become solid. Gym lighting (fluorescent, sometimes
glare) is the reason; test outdoors and under fluorescent light (ux_ui, engineering-fact).

**Muscle-goal colours** (Week bars, goal editor, muscle map): improve = `primary`; improve with focus
= `primary` with a 2 dp outline; maintain = `onSurfaceVariant`; skip = `outline`, dashed; covered by
challenge = `primary` at 50 % with a small challenge icon. Never colour alone: each row also has a
text label.

### 4.2 Typography

* Font: **Roboto Flex** (SIL Open Font License), bundled as static instances at weights 400 and 600,
  subsetted to Latin. Reason: Xiaomi, OnePlus and Oppo replace the system font (MiSans, OnePlus
  Sans, OPPO Sans) and those may not offer tabular figures; bundling guarantees identical numerals
  on the user's phone, in Roborazzi screenshots and in reviews. Implementation must verify that the
  bundled instances contain the `tnum` feature (engineering assumption).
* Only two weights: 400 (regular) and 600 (semibold).
* All numbers use tabular figures (`fontFeatureSettings = "tnum"`) so counting digits do not jitter.
* Uppercase only for small overlines (12 sp, letter-spacing 0.8 sp), never for sentences.

| Style | Size / line height | Weight | Used for |
|---|---|---|---|
| Rest countdown (custom) | 96 sp / 104 sp | 600, tnum | Rest timer and hold timer digits |
| Display M | 45 sp / 52 sp | 600, tnum | Current-set load and reps values |
| Headline S | 24 sp / 32 sp | 600 | Session Summary title, wizard step titles |
| Title L | 22 sp / 28 sp | 600 | Card titles, exercise name on the set card |
| Title M | 16 sp / 24 sp | 600 | List row titles |
| Body L | 16 sp / 24 sp | 400 | Body text |
| Body M | 14 sp / 20 sp | 400 | Secondary text |
| Label L | 14 sp / 20 sp | 600 | Buttons, chips |
| Label M | 12 sp / 16 sp | 600 | Overlines (uppercase), badges |

Font scaling uses Android 14+ non-linear scaling. Fixed-size exceptions: none. Every screen is
tested at font scale 1.0, 1.3 and 2.0 (section 4.8).

### 4.3 Spacing and layout

| Token | Value |
|---|---|
| Grid | 4 dp |
| Screen side margin | 16 dp (12 dp inside the RIR chip row, see 6.2) |
| Card padding | 16 dp |
| Gaps | 8 / 12 / 16 / 24 dp |
| Minimum touch target | 48 x 48 dp, 8 dp apart |
| In-session primary targets | 56-64 dp |
| Docked primary button | 56 dp tall, full width minus margins |
| Navigation bar | 64 dp (M3 flexible navigation bar), active indicator 56 dp |
| Mini-bar | 56 dp |
| Bottom sheet top inset | 28 dp corner radius, drag handle 32 x 4 dp |

The Live Session screen is split: the top area (about 40 %) scrolls and holds passive information;
the bottom area (about 60 %) is fixed and holds the current-set card or the rest card.

### 4.4 Shape

The research lens noted that the OAP artifact uses 3 px corners while M3 uses 8-28 dp and asked to
pick one. This spec picks **M3 corners**, because every M3 component uses them by default and a
custom shape system would cost effort without improving usability (UX default, the user can
overrule).

| Element | Corner |
|---|---|
| Cards | 16 dp (M3 large) |
| Chips | 8 dp (M3 small) |
| Buttons (primary, tonal, outlined) | full (pill) |
| Stepper buttons | full (circle, 64 dp) |
| Bottom sheets | 28 dp top corners |
| Dialogs | 28 dp |
| Text fields | 4 dp top (M3 filled) |

### 4.5 Icons

Material Symbols Rounded, weight 400, optical size 24, grade 0. Filled variant only for selected
or done states. Icons are always paired with a text label in navigation, chips and banners. Icon
choices that carry meaning:

| Meaning | Symbol |
|---|---|
| Done | `check_circle` (filled) |
| Planned in calendar | `event` |
| Written by the app (marker) | `edit_calendar` |
| Recommended (not in calendar) | `lightbulb` (outlined) |
| Rest day | `bedtime` |
| Blocked by a challenge rule | `block` |
| Test day | `flag` |
| Advisory warning | `info` in advisory colour |
| Safety | `health_and_safety` in safety colour |
| Why / explanation | `help` (outlined) inside a 48 dp target |
| Offline / sync pending | `cloud_off` / `cloud_sync` |
| Challenge | `emoji_events` |
| Home habit | `home` |
| Unilateral | `back_hand` (left) and a mirrored copy (right) |
| Timed hold | `timer` |
| Lower = harder (assisted) | `arrow_downward` with the text "harder" |

### 4.6 Motion

| Motion | Spec | Evidence |
|---|---|---|
| Spatial, default | spring damping 0.9, stiffness 700 | engineering-fact (M3 tokens) |
| Spatial, fast | damping 0.9, stiffness 1400 | engineering-fact |
| Spatial, slow | damping 0.9, stiffness 300 | engineering-fact |
| Effects, default | damping 1.0, stiffness 1600 | engineering-fact |
| Effects, fast / slow | damping 1.0, stiffness 3800 / 800 | engineering-fact |
| Set-logged micro-confirm (expressive) | chip scales 1.0 -> 1.08 -> 1.0 with an expressive spring (damping 0.6, stiffness 800), 1 time | expert-opinion |
| Gate achieved (expressive) | progress bar fills, then the gate row bounces once (damping 0.6, stiffness 500) | expert-opinion |
| Set card -> rest card | crossfade + 16 dp vertical slide, effects default spring | expert-opinion |

When the system setting "Remove animations" is on (`ANIMATOR_DURATION_SCALE == 0`), every
transition is instant and the expressive moments are replaced by the haptic alone.

### 4.7 Dark mode

* The theme follows the system light/dark setting by default (You > Appearance: System / Light /
  Dark). QA is dark-first because gym use is often in the evening (19:45 sessions).
* Dark surfaces are dark grey, not black (shadows stay visible, less glare). Elevation is shown by
  lighter tonal surfaces, not shadows. Pure black (OLED) is opt-in.
* Charts and bars use the same role tokens in both themes; line widths at least 2 dp.

### 4.8 Font scale and screen-size tests

Every screen has Roborazzi screenshot tests at:

| Config | Width x height (dp) | Font scale | Theme |
|---|---|---|---|
| Reference | 393 x 873 | 1.0 | light and dark |
| Large text | 393 x 873 | 1.3 | dark |
| Maximum text | 393 x 873 | 2.0 | dark |
| Narrow phone | 360 x 800 | 1.0 and 1.3 | light |

Responsive rules that the tests must prove:

1. The RIR chip row switches from one row of six to a 2 x 3 grid when the computed chip width is
   below 52 dp or the font scale is 1.5 or more (section 6.2).
2. Display M (45 sp) values shrink with auto-size down to 32 sp before the stepper buttons would
   overlap; the stepper buttons never shrink below 56 dp.
3. The 96 sp countdown shrinks down to 64 sp at font scale 2.0 if needed to keep the three timer
   buttons on one row; if that still does not fit, the buttons wrap to two rows (Skip on its own row).
4. No text is truncated in buttons; labels wrap to two lines instead.
5. No horizontal scrolling on any screen at any tested size.

---

## 5. Number, unit, date and language formatting

**Language (D12).** The UI is English. Exercise names are stored in English and Dutch; the UI
shows English. Only when the phone's language is Dutch (`nl`) does the Dutch name appear as secondary
text and does search match it too; otherwise Dutch names are neither shown nor searched. Dutch calendar titles (`sessie C`, `een arm zwaar` /
`één arm zwaar`, `testdag`, `hardlopen`) are recognised by `calendar.classify`; the UI shows the
event's own title verbatim.

**Decimal point always (D12).** Numbers are formatted with `Locale.ROOT`-style rules (point as
decimal separator, no thousands separator) regardless of the phone's locale. A Dutch phone locale
(`nl-NL`) must still show `42.8 kg`, never `42,8 kg`. Input fields accept both `.` and `,` and
normalise to a point.

| Quantity | Format | Examples |
|---|---|---|
| Load, kg (external, added, bodyweight) | Shortest of 0, 1 or 2 decimals that represents the value exactly on the 0.01 grid (C22) | `20 kg`, `22.5 kg`, `1.25 kg`, `70.7 kg` |
| Derived kg (assist kg, one-arm load, e1RM) | 1 decimal on screen (assist kg per ANDROID G7, C54; one-arm load and e1RM the same for consistency). The stored and compared values stay on the 0.01 kg grid (C22); only the display rounds. | `24.6 kg`, `46.1 kg`, `+39.2 kg` |
| Added load on bodyweight exercises | `BW + x kg`; `BW` alone when x = 0; assisted shown as pin, never as negative kg | `BW + 22.5 kg` |
| Machine pin (lb machines) | Native unit, integer, `lb`; kg equivalent secondary with `≈` and 1 decimal (C54) | `Pin 40 lb  ≈ 24.6 kg assist` |
| Reps | Integer | `5 reps` |
| RIR | Integer 0-5, `5+` for five or more | `2 in reserve` |
| Hold time | `s` with 1 decimal below 10 s if not integer, integer otherwise; `m:ss` from 60 s | `0.5 s`, `8 s`, `1:30` |
| Rest timer | `m:ss`; overtime `+m:ss` | `2:18`, `+0:23` |
| Session elapsed | `mm:ss` below one hour, `h:mm:ss` above | `32:10`, `1:04:12` |
| Fractional sets | 1 decimal, `.0` dropped | `9.5`, `12` |
| Percent | Integer | `65 %` |
| Dates | `EEE d MMM` in English | `Wed 7 Oct` |
| Times | 24-hour `HH:mm`, local time zone Europe/Amsterdam (F18) | `19:45` |
| ISO week | `Week 41` | |

**Units (D12, F7).** All loads are kilograms. Equipment whose native unit is pounds (the assisted
pull-up machine; any lb stack the user marks as such) is displayed in its native unit with the kg
equivalent as secondary text. Pin values are never auto-converted in the primary display (critique,
units gap). Storage keeps the native value, the unit, the machine id and the computed kg (C2).

---

## 6. Interaction components and patterns

### 6.1 Steppers

```
  ( - )        BW + 22.5 kg        ( + )
  64 dp           45 sp            64 dp
```

* Circular 64 dp buttons with a minus and a plus, the value between them in Display M.
* Step size per load type is computed by `ui.prefill` (section 19.2): one rep; the loadable
  increment for loads (next dumbbell, next stack step, next belt combination, 5 lb pin step); 0.5 s
  or 1 s for holds.
* Non-uniform loadable sets (dumbbells, belt plates) move to the next loadable value up or down,
  not by a fixed amount.
* Long-press: after 400 ms the step repeats every 120 ms; after 8 repeats the step is multiplied by
  5 (or jumps 5 entries in non-uniform lists). `CLOCK_TICK` haptic per step.
* At the end of the range the button is disabled and a `REJECT` haptic plays on press.
* Assisted machines label the buttons by effect: `( - ) harder` and `easier ( + )`, because a lower
  pin means less help (C2, ux_ui).
* Tapping the value opens the Keypad sheet (section 16.7).

### 6.2 Chip rows used as log actions (RIR and Felt)

**RIR chips (D9).** Label above the row: "Reps left in the tank?". Chips: `0 1 2 3 4 5+`. Tapping a
chip logs the set with the prefilled load and reps and that RIR. There is no separate Log button,
no prefilled RIR and no highlighted "target" chip (D9: avoid anchoring). The target RIR appears
only in the prescription line above ("aim: 2 in reserve").

Layout: chips are equal width, height 64 dp, 6 dp apart, inside a row with 12 dp side padding:
`chipWidth = (screenWidth - 2 * 12 - 5 * 6) / 6`. At 393 dp this is 56.5 dp; at 360 dp it is 51 dp.
If `chipWidth < 52 dp` or font scale >= 1.5, the row becomes a 2 x 3 grid (`0 1 2` / `3 4 5+`),
each chip one third of the width. The 6 dp gap is below the M3 recommendation of 8 dp; it is a
deliberate trade-off (an adjacent mis-tap is an off-by-one RIR, which is within the ~1 rep
self-report error of Halperin 2022, and Undo is available). Tunable: `ui.prefill` logs `rir_chip`
and later edits; a high rate of immediate RIR edits by one step means the chips are too close.

**Felt chips for timed holds (D9).** "How did it feel?" `Max  Hard  OK  Easy`. Tapping logs the
hold. Mapping to seconds-in-reserve for HOLD-1 (`hold`): Max = 0 s, Hard = 2 s, OK = 5 s, Easy =
10 s or more (logged as a lower bound). These four values are the HOLD-1 chip set from
`load_prescription.md` (expert-opinion, tunable from hold residuals).

**Debounce.** The first tap on a chip in a log row disables the whole row for 300 ms and then
replaces it (with the rest card or the next side). A second tap within 300 ms
(`ui.doubleTapDebounceMs`, ANDROID section 25.1) is dropped and counted in telemetry
(`log_double_tap_dropped`).

**Other log controls (C63).** D9 applies to working sets. Warm-up block items and specific warm-up
sets are logged with one "Done" tap and no RIR (11.5). Test-day entries are metric observations and
use a "Record" button; a 3RM attempt is stored with RIR 0 by protocol (14.9). Eccentric-only sets
(negatives) are logged with the lowering seconds and the felt chips Max / Hard / OK / Easy, never with
RIR chips (C66, 11.7.5). An "Add a set" action in
the exercise menu adds one more set to the current exercise (11.12).

### 6.3 Reason chips (only on deviation)

Reason chips never appear unless the user deviated from the target (section 19.3). They are
optional for deviations and logged as `none` if untouched; a skip requires exactly one tap on a
reason. At most 6 chips visible (two rows of 3 at 48 dp), the rest behind "More".

### 6.4 Flag toggles

Exercise-specific stop flags (from the catalog entry or the challenge exercise's `flagsOffered`):
`Grip slipped before the last rep`, `Torso rotated`, `Jerky lowering`, `Hand shifted`, `Hips rotated`,
stored as the DATA_MODEL set-flag codes `grip_slip_before_last`, `torso_rotation`, `jerky`,
`hand_shift`, `hip_rotation` (E6, C38). Rendered as 48 dp outlined toggle chips above the RIR row, off by default, only for exercises
that define them. A flag is not a reason; it is a fact about the set that rules read (e.g.
`prog.gripSlip.lock`).

### 6.5 Undo snackbar

| Action | Text | Duration |
|---|---|---|
| Set logged | "Set 2 logged · Undo" | 6 s |
| Side logged (unilateral) | "Left logged · Undo" | 6 s |
| Exercise skipped / removed / moved later | "Leg press skipped · Undo" | 6 s |
| Suggestion applied (general item) | "Next sets: BW + 25 kg · Undo" | 6 s |
| Challenge rule applied automatically (D14) | not a snackbar: the set card shows "Rule: pin 35 -> 40 lb · Undo" (11.10) | until the item's last set is logged |
| Set added ("Add a set", C63) | "Set 4 added · Undo" | 6 s |
| Session abandoned | "Session abandoned, 8 sets kept · Undo" | 10 s |
| Calendar event written (Plan it) | "Added to calendar · Undo" | 6 s |
| Pain reported (protocol started) | "Elbow protocol started · Undo (wrong tap)" | 10 s |

The snackbar sits at the top edge of the fixed action area so it never covers the chip row. Undo
reverts the domain write (soft delete or previous value) and emits the matching `*_undone` event.

### 6.6 Bottom sheets

Modal bottom sheets, 28 dp top corners, drag handle, scrim. They open to the height of their
content (maximum 90 % of the screen) and close by drag down, scrim tap or back. Sheets used in the
gym put their actions at the bottom. A sheet never opens another sheet on top of itself; it replaces
its own content instead (for example Skip -> Occupied -> Do later / Swap happens inside one sheet).

### 6.7 Haptics

`View.performHapticFeedback` (no permission needed) for touch feedback; `Vibrator` with a waveform
(needs `VIBRATE`) for alerts.

| Moment | Haptic | Notes |
|---|---|---|
| Set or side logged | `CONFIRM` (API 30+; `VIRTUAL_KEY` below) | plus the micro-confirm animation |
| Stepper step | `CLOCK_TICK` | each step, including auto-repeat |
| Invalid entry, end of range, unloadable value | `REJECT` | |
| Long-press drag start (reorder) | `LONG_PRESS` | |
| Rest pre-warning (10 s left) | one `CLOCK_TICK`-like short pulse, in-app only | only while the app UI is in the foreground (ANDROID section 12.4, C54); no alarm, nothing in the background |
| Rest end | waveform `[0, 300, 150, 300, 150, 600]` ms with `USAGE_ALARM` attributes | always on (F20), felt in a pocket |
| Overtime reminder (60 s over) | one 300 ms pulse | once |
| Hold timer: every 5 s | 40 ms tick | with the beep |
| Hold timer: target reached | two 120 ms pulses 100 ms apart | with the double beep |
| Gate achieved | `CONFIRM` twice, 150 ms apart | |
| Safety banner appears | `REJECT` | once |

### 6.8 Audio

* Rest end tone: optional, default **off** (F20). When on, a 600 ms tone plays through
  `USAGE_ASSISTANCE_SONIFICATION` after requesting `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, so music in
  earbuds ducks instead of stopping; focus is abandoned right after.
* Hold timer cues: default **on** (the hand is on the bar, the phone is on the floor): 3-2-1 lead-in
  beeps (one per second), a short beep every 5 s while holding, a double beep at the target time.
  Same ducking focus request.
* The app never plays audio when the phone is in silent mode, except that the vibration still fires.

---

## 7. Global states

### 7.1 Sync status

Sync is the outbox mechanism of C23 (owned by `:data`). The UI shows sync state only when it
matters:

| State | Condition | Indicator | Text in You > Data and sync |
|---|---|---|---|
| OK | Outbox empty or last push < 15 min ago | none | "All changes uploaded · last sync 19:52" |
| Pending (offline) | Device offline and outbox non-empty | `cloud_off` icon chip, muted | "Offline. 23 changes are saved on this phone and will upload automatically." |
| Pending long | Outbox has an entry older than 24 h while online | `cloud_sync` chip, advisory | "Upload is retrying. Oldest change waiting: 26 h." |
| Server paused | Push fails with the "project paused" pattern (F4: Free plan pauses after 7 quiet days) | `cloud_off` chip, advisory | "The server is paused. Your data is safe on this phone. To wake it: open the Supabase dashboard and restore the project." |
| Dead letter | Any row rejected with a non-transient 4xx (RLS, schema) | `error` chip, safety colour | "3 changes could not be uploaded (server rejected them). They are kept on this phone. Export a review bundle and let Claude Code look at it." |
| Last sync > 7 days | No successful sync for 7 days | advisory chip | "No sync for 8 days. The free server may pause; open the app with internet to keep it awake." |

Sync never blocks a screen. Sign-out and reinstall-related actions are blocked while the outbox is
non-empty (critique gap; confirmation dialog explains why).

### 7.2 Offline behaviour

Offline is the normal case in the gym. Everything works offline except:

| Needs network | Offline behaviour |
|---|---|
| First sign-in and the allowlist check | "You need internet for the first sign-in." Retry button. |
| Pulling newer params, catalog or challenge definitions | Bundled defaults (default params, ~150-exercise catalog, OAP definition) are used; the app pins the params version per session (critique gap). |
| Upload | Queued in the outbox. |

Calendar reading and writing use the on-device CalendarContract provider and work offline; Google
syncs the events when the phone is online.

### 7.3 Safety banners

A red banner at the top of Today, Week, Challenges and Live Session when `safety` reports an active
state. It is persistent (never auto-hidden) and shows: icon, one-line title, one-line consequence,
"Details" (opens the Why sheet with the rule id, the protocol text and the disclaimer).

```
+-------------------------------------+
| !! Elbow protocol · day 3 of 14     |
|    No one-arm work, light bilateral |
|    only. Not medical advice. Details|
+-------------------------------------+
```

Safety warnings from the WarningEngine (for example "0 in reserve logged on a weighted pull-up") use
the same style but have an "Understood" action that acknowledges them; acknowledgement is logged.

### 7.4 Permission and reliability warnings

Shown as a single compact row on Today (and an icon in the Live Session header) when any of these
is false. Tapping opens the matching onboarding permission step.

| Check | API | Warning text |
|---|---|---|
| Notifications allowed | `NotificationManagerCompat.areNotificationsEnabled()` | "Rest alerts are off: notifications are blocked." |
| Exact alarms | `AlarmManager.canScheduleExactAlarms()` | "Rest alerts may be late: exact alarms are not allowed." |
| Battery optimisation exemption | `PowerManager.isIgnoringBatteryOptimizations()` | "Your phone may stop the rest timer: battery optimisation is on for this app." |
| Activity recognition | runtime permission | "Session runs in a fallback mode." (FGS `specialUse` instead of `health`, C28) |
| Calendar read | runtime permission | "Planning without your calendar." (degraded mode, see 10.6) |
| Calendar write | runtime permission | "Challenge sessions cannot be added to your calendar." |
| Promoted notifications (Android 16+) | `canPostPromotedNotifications()` | Not a warning; Live Updates simply fall back to a normal ongoing notification. |

### 7.5 Error states (catalogue)

| Error | Where | What the user sees | Recovery |
|---|---|---|---|
| Engine exception in a module | Any screen that shows a recommendation | "Couldn't work out a recommendation (module `today@1.0.0`). You can still train." + "Start empty session" | Event `engine_error` with module id, version, inputs hash; review later |
| Params or definition failed validation | Startup | Silent fallback to the last valid version; You > Transparency shows "Using params v12 (v13 rejected)". | Event `params_rejected` |
| Calendar provider error | Week, Today | Degraded mode banner (10.6) | Retry on next resume |
| Calendar write failed | Week (Plan it), challenge write-back | Snackbar "Couldn't add to calendar" + "Retry" | Event `calendar_write_failed` |
| Alarm could not be scheduled | Live Session | Rest card shows "Alarm unavailable: keep the app open" in advisory colour | Event `rest_alarm_failed` |
| Database write failed (disk full) | Anywhere | Red banner "Phone storage is full. New sets cannot be saved." | Event cannot be stored; shown only |
| Allowlist rejected | Onboarding | See 8.3 | Sign out |
| Sign-in failed | Onboarding | Inline field error | Retry |
| Session restored after the app was killed | Live Session | Info snackbar "Session restored. Rest timer kept running." | none needed |

---

## 8. Screens: onboarding

### 8.1 Overview

Onboarding is a full-screen, resumable flow. Each step is saved when completed, so the app can be
killed mid-way and resumes at the same step. Every step except sign-in can be revisited later from
You (the "Revisit in" column). Nothing in onboarding assumes the user is the current owner: there
are no sex- or age-specific defaults and no OAP-specific assumptions outside the challenge offer
(D3).

| Order | Step id | Required | Can skip | Revisit in |
|---|---|---|---|---|
| 1 | `onboarding.signin` | yes | no | You > Account |
| 2 | `onboarding.rejected` (only if not allowlisted) | - | - | - |
| 3 | `onboarding.about` | yes (acknowledge disclaimer) | no | You > Account |
| 4 | `onboarding.profile` | bodyweight recommended | yes | You > Preferences > Profile |
| 5 | `onboarding.permissions` (6 sub-steps + timer self-test) | no, but warned | each sub-step | You > Rest and alerts > Reliability |
| 6 | `onboarding.equipment` (F7) | confirm once (DECISIONS 5.6) | "Later" (then bodyweight and machine-only plans until confirmed) | You > Equipment |
| 7 | `onboarding.setrep` | no (defaults apply) | yes | You > Preferences > Sets and reps |
| 8 | `onboarding.goals` (D6) | yes (one preset tap is enough) | no | You > Preferences > Muscle goals |
| 9 | `onboarding.calendars` | no | yes (planning without calendar) | You > Calendars |
| 10 | `onboarding.defaults` | no | yes | You > Preferences, Rest and alerts, Prompts |
| 11 | `onboarding.challenge` | no | "Not now" | Challenges tab |
| 12 | `onboarding.done` | - | - | - |

Progress is shown as a thin bar at the top ("Step 5 of 11"). The primary button is docked at the
bottom ("Continue"); "Skip" is a text button next to it where skipping is allowed.

### 8.2 Sign in (`onboarding.signin`)

**Purpose.** Sign in with the user's existing email account in the shared Supabase project
(nuvjdvpqibqeyafzgaye). There is no sign-up in the app: accounts are created by the project owner,
and access to the fitness schemas is controlled by an allowlist (D3, F1).

```
+-------------------------------------+
|                                     |
|  Fitness                            |
|  Sign in with your existing account |
|                                     |
|  Email                              |
|  [ you@example.com              ]   |
|  Password                           |
|  [ ************           (show) ]  |
|                                     |
|  You need internet for the first    |
|  sign-in. After that the app works  |
|  offline.                           |
|                                     |
| [=========== Sign in =============] |
+-------------------------------------+
```

* Fields: email (keyboard type email, autofill hint `emailAddress`), password (autofill hint
  `password`, show/hide toggle). Password managers fill these through Android autofill; the app
  never stores the password, only the Supabase session (supabase-kt session manager).
* States: idle, submitting (button shows a progress indicator, fields disabled), error.
* Errors (inline under the field, never a dialog): "Email or password is wrong." / "No internet. The
  first sign-in needs a connection." / "Too many attempts. Wait a minute and try again." / "Server
  unreachable (it may be paused). Try again later."
* No "forgot password" link in v1 (Open issue OI-10).
* Emits: `onboarding_step_completed {step: signin, duration_ms}`, `signin_failed {error_class}` (no
  email in any payload).
* Fed by: Supabase auth (`:data`).

### 8.3 Allowlist rejection (`onboarding.rejected`)

After a successful sign-in the app checks the allowlist with a read probe on a seeded reference table,
because the `fitness` schema has no RPC functions (ANDROID section 10.4, DATA_MODEL section 5.4). If the
account is not allowlisted:

```
+-------------------------------------+
|                                     |
|  This account isn't enabled for     |
|  the fitness app yet.               |
|                                     |
|  Your sign-in worked, but the app   |
|  is limited to invited accounts.    |
|  Ask the owner to add this email    |
|  to the allowlist.                  |
|                                     |
|  Signed in as y***@e***.com         |
|                                     |
|  [ Check again ]                    |
| [=========== Sign out =============]|
+-------------------------------------+
```

* Nothing is written locally (no profile, no outbox rows) until the account is allowlisted.
* "Check again" re-runs the access probe (adding a friend later is one allowlist row, D3).
* Emits: `allowlist_rejected {}` (no email; the user id is in the envelope).

### 8.4 About and disclaimer (`onboarding.about`)

One screen, three short paragraphs and one checkbox:

1. "The app plans your week, picks exercises and suggests weights from what you log. Every
   recommendation can be explained (tap Why) and overridden."
2. "It is offline-first: everything is saved on this phone and uploaded when you have internet."
3. "This app does not give medical advice. If something hurts, stop and get it checked."

Checkbox: "I understand this is not medical advice." Continue is enabled when checked. Emits
`disclaimer_acknowledged {version}`.

### 8.5 Profile (`onboarding.profile`)

| Field | Input | Default | Used by |
|---|---|---|---|
| Bodyweight | keypad, kg, 0.1 step | empty (last known shown if imported) | `bodyweight` (C32: EWMA for prescriptions), `load` (C1) |
| Training experience | 3 choices with definitions: "Under 1 year", "1-3 years", "More than 3 years" of regular lifting | none preselected; novice ("Under 1 year") when the step is skipped (C64) | `targets` BASE 10/12/14 (C14) |
| Age | optional number | empty | `targets` (maintenance rule for 60+ only) |
| Display name | optional | email prefix | UI greeting only |

There is no sex field: no default in the app depends on it (D3). Copy under age: "Optional. Only
used to adjust maintenance volume if you are 60 or older."

Emits `onboarding_step_completed {step: profile, fields_set: [..]}` and a bodyweight observation
(domain fact) if bodyweight was entered.

### 8.6 Permissions walkthrough (`onboarding.permissions`)

**Purpose.** Make the rest timer and the session reliable on a Xiaomi / OnePlus / Oppo class phone
(D2), which are among the most aggressive background killers. One sub-step per item, each with a
plain reason, a Grant button and a live status line that re-checks when the user returns from
Settings.

```
+-------------------------------------+
| Step 5 of 11 · Reliability    2 / 7 |
|                                     |
|  Exact alarms                       |
|  The rest timer uses an alarm-clock |
|  alarm so it goes off on time, even |
|  with the screen off.               |
|                                     |
|  Status:  Allowed                   |
|                                     |
|  [ Open settings ]                  |
| [=========== Continue =============]|
+-------------------------------------+
```

| # | Item | How | Reason text | If refused |
|---|---|---|---|---|
| 1 | Notifications (Android 13+) | `POST_NOTIFICATIONS` runtime request | "Shows the rest countdown on the lock screen and alerts you when rest is over." | Warning row on Today (7.4); the in-app timer still works while the app is open |
| 2 | Exact alarms | Check `canScheduleExactAlarms()`. The sideloaded build declares `USE_EXACT_ALARM` (auto-granted). If it still reports false, deep link `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. | "So the rest alarm fires on time, even when the phone is idle." | Inexact fallback, warning row (C28) |
| 3 | Activity recognition | `ACTIVITY_RECOGNITION` runtime request | "Lets the workout session run as a fitness session in the background. The app does not count steps or read sensors." | Session service uses the `specialUse` type (C28) |
| 4 | Calendar | `READ_CALENDAR` + `WRITE_CALENDAR` | "Reads your gym sessions from your calendar and adds your challenge sessions to it. The app only edits events it created itself." (D11) | Planning without calendar (10.6); no write-back |
| 5 | Battery optimisation | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) | "Stops Android from pausing the timer to save battery." | Warning row |
| 6 | Phone-maker settings | Instructions per manufacturer (below) + "Open settings" best-effort intent + "I've done this" confirmation | "Your phone (Xiaomi) has its own battery manager that can stop apps even when the steps above are done." | Warning row stays until confirmed |
| 7 | Timer self-test | Runs a real 20 s rest timer through the production timer path | "Lock your phone now. It should vibrate in 20 seconds." | Shows step 6 again with "The alarm was 9 s late" |

**Phone-maker instructions (step 6).** Detected from `Build.MANUFACTURER` / `Build.BRAND`. Menu
paths differ between OS versions; the texts below follow the commonly documented paths
(dontkillmyapp.com) and must be verified on the user's actual phone at M1 (D2: model and Android
version still to be confirmed; Open issue OI-01). Evidence: weak (community documentation).

| Maker (brand values) | Instructions shown | Best-effort intent (wrapped in try/catch, falls back to `ACTION_APPLICATION_DETAILS_SETTINGS`) |
|---|---|---|
| Xiaomi (`xiaomi`, `redmi`, `poco`; MIUI / HyperOS) | 1. Settings > Apps > Manage apps > Fitness > Autostart: on. 2. Same page > Battery saver: No restrictions. 3. Open Recents, long-press the Fitness card, tap the lock. | `com.miui.securitycenter / com.miui.permcenter.autostart.AutoStartManagementActivity` |
| OnePlus (`oneplus`; OxygenOS) | 1. Settings > Apps > App management > Fitness > Battery usage: Allow background activity, Allow auto-launch. 2. Settings > Battery > Battery optimisation > Fitness: Don't optimise. 3. Lock the app in Recents. | `com.oneplus.security / com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity` (older OxygenOS), else app details |
| Oppo / Realme (`oppo`, `realme`; ColorOS) | 1. Settings > Apps > App management > Fitness > Battery usage: Allow auto-launch, Allow background activity. 2. Settings > Battery > More settings > Optimise battery use > Fitness: Don't optimise. 3. Lock the app in Recents. | `com.coloros.safecenter / com.coloros.safecenter.permission.startup.StartupAppListActivity`, else app details |
| Other | Generic text: "If your phone has its own battery manager, allow this app to run in the background." | app details |

**Timer self-test (step 7).** Starts the session service in a test mode, schedules a 20 s rest via
`RestTimerController` (section 19.8), records the alarm latency (`fired_at - end_at`), then asks
"Did you feel the vibration?" `[Yes] [No]`. Pass = alarm latency of at most 2 s and "Yes". The result
is kept and shown in You > Rest and alerts > Reliability, with a "Run again" button. This
complements, and does not replace, the adb Doze test that Claude Code runs at M1 (D2).

Emits: `permission_requested {permission}`, `permission_result {permission, granted, can_ask_again}`,
`exact_alarm_status {allowed}`, `battery_optimization_status {ignoring}`, `oem_settings_opened
{maker, intent_ok}`, `oem_settings_confirmed {maker}`, `timer_selftest {latency_ms, felt, screen_off,
alarm_mode, passed}`.

### 8.7 Equipment checklist (`onboarding.equipment`, F7)

**Purpose.** Make every prescribed load loadable on the user's real equipment, and know which
stations exist (`requires_station` on every exercise). Basic-Fit defaults are prefilled; every value
is editable; unknown values are asked, never guessed silently.

```
+-------------------------------------+
| Step 6 of 11 · Your gym             |
| Basic-Fit Amersfoort Ierse Pond   > |
| Mon-Fri 07:00-22:30 · Sat-Sun 09-16 |
|                                     |
| STATIONS (untick what's missing)    |
| [x] Pull-up bar (narrow)            |
| [x] Fixed neutral handle            |
| [x] Assisted pull-up machine      > |
| [x] Dip station   [x] Cable station |
| [x] Leg press     [x] Leg extension |
| ...                    Show all   > |
| PLATES  25 20 15 10 5 2.5    Edit > |
| DUMBBELLS 2-10 by 2, 12.5-40 by 2.5 |
|                              Edit > |
| MACHINE STACKS  [kg] [lb]  step 10  |
| SMITH BAR  Ask me the first time  > |
| YOUR OWN  [x] Dip belt  [x] Chalk   |
|           Belt plates             > |
| [======== Confirm equipment =======]|
+-------------------------------------+
```

| Item | Default (editable) | Status | Notes |
|---|---|---|---|
| Gym | Basic-Fit Amersfoort Ierse Pond, hours Mon-Fri 07:00-22:30, Sat-Sun 09:00-16:00 | engineering-fact (Basic-Fit site) | Hours feed the gym-hours constraint of `challenge.plan` |
| Stations | Common Basic-Fit stations ticked | unverified (machine list not verifiable online) | User unticks missing ones; "narrow bar: no archer / typewriter" noted |
| Gym plates | 25, 20, 15, 10, 5, 2.5 kg | unverified | One editable list of sizes; the user adds 1.25 kg if the gym has it. Plates are assumed unlimited (no count per size, F7) |
| Dumbbells | 2-10 kg in 2 kg steps, then 12.5-40 kg in 2.5 kg steps | unverified, expert guess | Must be confirmed in the gym; first use of a dumbbell exercise asks "Is X kg available?" while unconfirmed |
| Machine stacks | 10 lb steps; unit kg or lb | user (F7, 2026-10-09) | Most stacks are marked in lb; the set card shows the lb value with the kg equivalent. First use of a machine asks for its smallest step while unconfirmed |
| Smith bar weight | unknown -> "Ask me the first time" | F7 | Smith exercises are prescribed only after it is entered |
| Assisted pull-up machine | `{unit lb, step 5, min 0, max: ask, offsetKg 6.5, ratio 1.0}` | F7, C2; the offset is the user's estimate ("6-7 kg"), the ratio is assumed | Detail screen explains: "assist = pin x 0.4536 x ratio + 6.5 kg; at pin 0 the machine still helps about 6.5 kg." |
| Dip belt | own: yes; maximum belt load 60 kg | F7 (the maximum is a P default from the challenge) | |
| Belt plates | the gym plates above, unlimited per size (F7, 2026-10-09) | user | Limited only by the belt maximum and the items-on-belt cap; each load uses the fewest plates (so 47.5 = 20 + 20 + 5 + 2.5) |
| Chalk | yes | | The hang rules mention chalk |

"Confirm equipment" writes the versioned gym profile and emits `equipment_confirmed {profile_version,
changed_from_default: [..]}` (DECISIONS 5.6). "Later" keeps the defaults but marks them unconfirmed;
Today then shows a one-line reminder until confirmed.

Fed by / writes: `load` (loadable sets), `catalog` (`requires_station`), `elig`.

### 8.8 Set and rep preferences (`onboarding.setrep`)

**Purpose.** Capture how the user likes to train; the engine keeps the preference and only warns
when evidence clearly disagrees (D10).

```
+-------------------------------------+
| Step 7 of 11 · How you like to train|
|                                     |
| Sets per exercise                   |
|   [ 2 ]  ( 3 )  [ 4 ]               |
| Reps                                |
|  [6-8] (8-10) [8-12] [10-15] [12-20]|
| Effort: reps left in the tank       |
|   [ 1 ]  ( 2 )  [ 3 ]               |
| Within a range                      |
|  (o) Same weight, reps may drop     |
|      (10 / 9 / 8)                   |
|  ( ) Same reps every set            |
|                                     |
| Different for isolation exercises > |
| [=========== Continue =============]|
+-------------------------------------+
```

* Defaults: 3 sets, 8-10 reps, 2 in reserve, "same weight, reps may drop" (the RX-1 RANGE scheme).
* Per-class override (compound / isolation) here, per-exercise override later in exercise detail.
* Evidence notes come from `warnings` evaluated on the preference alone (for example W7: 1 set per
  exercise -> INFO "two sets per exercise usually beat one" (ACSM 2026); reps above 30 -> WARN;
  target RIR 4 or more -> WARN). Rendering follows `ui.warndisplay`; a note appears under the
  control that caused it, with Why. Notes never block Continue.
* Emits `rep_preference_changed {scope, field, from, to, warning_rule_ids}`.

### 8.9 Muscle goals (`onboarding.goals`, D6)

**Purpose.** Per muscle group (the 18 groups of C9), choose Improve / Maintain / Skip, plus an
optional Focus on improve groups. Presets make this one tap.

```
+-------------------------------------+
| Step 8 of 11 · Muscle goals         |
| Start from a preset:                |
| [Balanced] [Legs only] [No legs]    |
| [Pull focus, push + legs maintained]|
|                                     |
| GROUP       GOAL                    |
| Lats        [Impr.|Maint.|Skip] Foc |
| Upper back  [Impr.|Maint.|Skip] Foc |
| Biceps      [Impr.|Maint.|Skip] Foc |
| ...                                 |
| Quads       [Impr.|Maint.|Skip] Foc |
| (i) Covered by your challenge:      |
|     lats, biceps, forearms, ...     |
| [=========== Continue =============]|
+-------------------------------------+
```

Preset definitions (UX defaults; the user edits freely after applying one):

| Preset | Improve | Maintain | Skip |
|---|---|---|---|
| Balanced | chest, side_delts, rear_delts, lats, upper_back, biceps, triceps, quads, hamstrings, glutes | front_delts, upper_traps, forearms, abs, lower_back, abductors, adductors, calves | none |
| Legs only | quads, hamstrings, glutes, adductors, abductors, calves | abs, lower_back | all upper-body groups |
| No legs | chest, side_delts, rear_delts, lats, upper_back, biceps, triceps | front_delts, upper_traps, forearms, abs, lower_back | quads, hamstrings, glutes, adductors, abductors, calves |
| Pull focus, push + legs maintained | lats (focus), upper_back (focus), biceps, rear_delts, forearms | chest, front_delts, side_delts, triceps, quads, hamstrings, glutes, calves, abs, lower_back, upper_traps, adductors, abductors | none |

* Groups owned by an active challenge show "Covered by challenge" next to the control (D6; contract
  obligation 1 of `challenge.contract`). If the user picks Skip for an owned group, an advisory note
  says "Your challenge trains this group while it is active."
* Evidence notes from `warnings`: for example, a whole family set to Skip gives the one-time INFO
  "Guidelines recommend training all major muscle groups at least twice a week for health
  (ACSM 2026)" (dismiss forever).
* Writes `muscle_goals`; emits `goal_changed {group, from, to, focus_from, focus_to, via: preset|manual,
  preset_id}`.
* Fed by: `targets` (the resulting weekly targets appear in the Week tab, not here).

### 8.10 Calendars (`onboarding.calendars`)

```
+-------------------------------------+
| Step 9 of 11 · Calendars            |
| Read gym sessions from:             |
| [x] you@gmail.com (primary)         |
| [ ] Family                          |
| [ ] Todoist     ignored: old all-day|
|                 tasks               |
| All-day events are always ignored.  |
| New challenge sessions are added to |
| your primary calendar.              |
|                                     |
| THIS WEEK AS THE APP SEES IT        |
| Tue 19:45 Gym - sessie C   -> C   > |
| Thu 19:45 Gym - sessie A   -> A   > |
| Sun 13:00 Gym - testdag   -> Test > |
| Mon 19:30 Schaken          busy   > |
| [=========== Continue =============]|
+-------------------------------------+
```

* Default: primary calendar only (D11). Calendars whose name matches `todoist` are unchecked and
  labelled. All-day events are ignored (fixed rule). The write target is the primary calendar
  (fixed, D11).
* Titles of the events the app writes (F25): challenge events use the challenge definition's title
  template (Dutch for OAP, matching the existing events, for example "Gym — sessie C (volume en
  techniek)"); general sessions use English labels ("Gym — Legs + push"); descriptions are English.
  The step states this in one line under the calendar list.
* The preview runs `calendar.classify` on the current ISO week and lists each event with its typed
  classification (`challenge_session(slot)`, `planned_gym(type)`, `run`, `busy`, `ignored`, C20).
  Tapping a row opens the Calendar event sheet (16.11) to correct it; corrections are stored as
  per-event overrides (C20).
* Emits `calendar_selection_saved {count_selected, primary_selected}`, `calendar_correction {...}`.

### 8.11 Session defaults (`onboarding.defaults`)

| Setting | Default | Source |
|---|---|---|
| Default time budget (no calendar event) | 75 min | F11 |
| Changeover subtracted from a calendar gym event (the event is training time; travel happens before it) | 5 min | F11, C49 |
| Maximum gym visits per week | 4 | D5 |
| Usual training windows | Weekdays 19:45-21:15, weekends 09:00-16:00 | planning lens facts (editable) |
| Running priority | Low | F6 |
| Supersets | Only when a plan does not fit the time (otherwise off) | F13 |
| RIR check sets | Off (opt-in, at most 1 per week, machine / cable / isolation only, never challenge lifts) | F14 |
| Rest alert | Vibration always; tone off | F20 |
| Keep screen on | During session | F20 |
| Feedback prompts | Readiness on, elbow check on, soreness on, session effort on, joint pain on | F15 |

An info card at the bottom states the fixed choices: "Weights are in kilograms. Machines marked in
pounds (like the assisted pull-up machine) are shown in pounds with kilograms next to them. The app
is in English and uses a decimal point (42.8 kg)." (D12, F7)

### 8.12 Challenge offer (`onboarding.challenge`) and done

Lists the published challenge definitions (currently one: "One-arm pull-up"). "Set up" opens the
Challenge setup flow (14.8); "Not now" goes to Done. Done shows "You're set. Today shows what to
train." and opens Today.

**Empty, error and offline for the whole flow.** Every step after sign-in works offline (bundled
catalog, params and challenge definition). The calendar steps work offline (on-device provider).

---

## 9. Screens: Today (`today`)

### 9.1 Purpose

Answer "do I train today, and what?" in one glance and start the session with one tap. Today is the
default destination.

### 9.2 Layout (top to bottom)

1. Header: date (`Wed 7 Oct`), ISO week (`Week 41`), the sync chip if needed, Why `(i)` for the
   whole card.
2. Block line (only with an active challenge): `OAP block 1 · week 5 · deload + test`.
3. Safety banner (if any) and the permission warning row (if any).
4. The Today card (one of the states in 9.3), with reason chips (at most 3, F19) and time-budget
   chips.
5. Prompt rows that are not part of a session: weigh-in when the last weigh-in is older than 14
   days (C32), "Confirm your equipment" while unconfirmed.
6. HOME: the home-habit checklist (9.6).
7. Week strip (9.7).
8. Docked primary button above the navigation bar: "Start Session A" / "Start legs + push" /
   "Resume session" / "Log test day". It is docked so that it is always in the thumb zone (P3). It is
   absent in the REST and DONE states.

### 9.3 Card states

The state is computed by `ui.todaycard` (specified in ALGORITHMS_PLANNING section 19.1, C53; display
details in 19.1 here) from `today`, `weekplan`, `calendar.classify`, `challenge.plan`, `safety` and the
session store. `today` provides the verdict `TRAIN`, `REST_SOFT` or `REST_HARD` with typed reason codes
(C53); the card never re-thresholds it.

| State | When | Card title | Primary action | Secondary |
|---|---|---|---|---|
| `SETUP_NEEDED` | Onboarding incomplete (goals missing) | "Finish setting up" | "Continue setup" | none |
| `IN_PROGRESS` | A session is running | "Session A in progress" + elapsed + sets done / total + rest state | "Resume session" | none |
| `DONE` | A gym or test session finished today and nothing else is planned today | "Session A done · 68 min · 13 sets" | none | "Next: Sun 11 Oct, Test day 13:00", "View summary" |
| `PLANNED` (challenge) | Calendar has a challenge session today (matched slot) | "Gym today 19:45 · OAP Session A" + mode line ("Deload week: sets halved, loads kept") + item preview | "Start Session A" | "Other options", time chips |
| `PLANNED` (test) | Today's matched slot is TEST | "Test day today 13:00" + validity line | "Log test day" (opens the Test-day wizard) | "Postpone" (if the challenge allows it) |
| `PLANNED` (general) | Calendar has a planned general gym event today | "Gym today 18:00 · Legs + push" | "Start legs + push" | "Other options", time chips |
| `PLANNED_BUT_REST` soft | Planned general event today but the verdict is `REST_SOFT` | "Planned 19:45, but rest is advised" + reason | "Start anyway" (tonal, not primary colour) | "Rest today" (records the choice) |
| `PLANNED_BUT_REST` hard | Planned event today but the verdict is `REST_HARD` (a safety or challenge hard rule forbids it) | "Planned 19:45, but today is a rest day" + rule | none | "Why"; "Log a session anyway" in the overflow |
| `OPTIONAL` clear | No gym planned; verdict `TRAIN`; clear winner | "No gym planned. If you go today:" + option 1 | "Start legs + push" | "Other options", "Plan it in calendar" |
| `OPTIONAL` close | Same, but the second option scores at least (1 - `close_rel`) of the first, `close_rel` 0.10 (C53, 19.1) | Same, with options 1-3 listed inline | "Start <selected option>" | tap another option to make it the Start target |
| `REST` soft | Verdict `REST_SOFT` (recovery, maximum visits, better another day) | "Rest today" + reason | none | "Train anyway" (opens the ranked list with warnings) |
| `REST` hard | Verdict `REST_HARD`: a challenge hard constraint or a safety state forbids gym today | "Rest today" + rule text | none | "Why"; "Log a session anyway" in the overflow (never blocked, P10) |
| `ENGINE_ERROR` | The `today` module failed | "Couldn't work out a recommendation" | "Start empty session" | "Retry" |

"Log a session anyway" (hard rest) opens the template picker with a red banner: "This breaks the
OAP rule 'no gym the day before a test'. The session will be logged; your challenge decides what it
means for the test." The challenge engine shows a warning and logs `rule_override` (CHALLENGE_ENGINE
section 8.5); any further consequence (for example for the test's validity) is decided by the
definition's rules. This
respects D10 (challenge hard constraints are always enforced in what the app recommends and plans)
and P10 (logging is never blocked).

**Wireframe: PLANNED, challenge session (Thu 8 Oct, deload week).**

```
+-------------------------------------+
| Thu 8 Oct              Week 41  (i) |
| OAP block 1 · week 5 · deload + test|
| +---------------------------------+ |
| | GYM TODAY 19:45 · OAP SESSION A | |
| | Deload week: sets halved,       | |
| | loads kept                      | |
| | Assisted 1-arm  3x3/arm  40 lb  | |
| | Weighted pull-up 2x4 BW+22.5 kg | |
| | Top hold, 90-degree hold, ...   | |
| | ~55 min                         | |
| | [Planned 19:45] [C 48 h ago]    | |
| | [Test Sun 13:00]          Why > | |
| | Time: (85 cal) 45 60 75 90+     | |
| | Other options >                 | |
| +---------------------------------+ |
| HOME                                |
| [-] 2-arm hang   skipped: A day     |
| [Start] 1-arm plank 17:30 3x20 s/sd |
| WEEK  M  T  W  T  F  S  S           |
|       -  v  -  *  -  x  T           |
| [========= Start Session A ========]|
+-------------------------------------+
| Today  Week  Exercises  Chall.  You |
+-------------------------------------+
```

The item preview lists at most 3 exercise lines plus "...". Exact sets, loads and the remaining
items come from `challenge.materialize` in the current mode (`test_pending`, which inherits the
deload transform). "85 cal" is the calendar budget: the 19:45-21:15 event is training time (travel
happens before it; the user's events say "leave 19:35"), so its 90 min minus the 5 min changeover
(F11, C49). Because 85 is not a standard chip, it is shown first and preselected (19.1.2).

**Wireframe: OPTIONAL, close call (Wednesday with the synthetic inputs of GF-UX-01).**

```
+-------------------------------------+
| Wed 7 Oct              Week 41  (i) |
| OAP block 1 · week 5 · deload + test|
| +---------------------------------+ |
| | NO GYM PLANNED. IF YOU GO TODAY:| |
| | (o) Legs + push     ~55 min   > | |
| |   [Quads 0/6 wk] [Deload week]  | |
| |   [Pull rests for A tomorrow]   | |
| | ( ) Legs            ~45 min   > | |
| | ( ) Push            ~40 min   > | |
| | Not today: Pull. Session A is   | |
| | tomorrow 19:45; lats, biceps    | |
| | and forearms are protected. Why>| |
| | Time:  45  60  (75)  90+        | |
| | Plan it in calendar             | |
| +---------------------------------+ |
| HOME                                |
| [ ] 2-arm hang  17:15  deload: half |
| WEEK  M  T  W  T  F  S  S           |
|       -  v  o  *  -  x  T           |
| [======= Start legs + push ========]|
+-------------------------------------+
```

The radio marks which option the Start button starts; tapping another option row moves it. The
chevron `>` opens that option's preview (exercise list from `select` + `rx`, est. minutes from
`time`).

**Wireframe: REST, soft (the real Wed 7 Oct, ALGORITHMS_PLANNING section 21.9, GF-UX-27).** With the
real week's inputs the planner puts the general session on Friday, because a Wednesday session would take
the legs out of Thursday's add-on. Wednesday, the day before session A, is still allowed for legs and
push (D15, C50): "Train anyway" lists those options.

```
+-------------------------------------+
| Wed 7 Oct              Week 41  (i) |
| OAP block 1 · week 5 · deload + test|
| +---------------------------------+ |
| | REST TODAY                      | |
| | [A Thu 19:45]                   | |
| | [Legs + push + rear delts fits  | |
| |  better on Fri]                 | |
| | Not today: Pull. Session A is   | |
| | tomorrow 19:45; lats, biceps    | |
| | and forearms are protected.     | |
| | Upper back: light pull only.Why>| |
| | Train anyway >                  | |
| +---------------------------------+ |
| HOME                                |
| [ ] 2-arm hang  17:15  deload: half |
| WEEK  M  T  W  T  F  S  S           |
|       -  v  -  *  o  x  T           |
+-------------------------------------+
```

**Wireframe: REST, hard (Sat 10 Oct).**

```
+-------------------------------------+
| Sat 10 Oct             Week 41  (i) |
| +---------------------------------+ |
| | REST TODAY                      | |
| | Test day tomorrow 13:00.        | |
| | OAP rule: no gym the day before | |
| | a test.                   Why > | |
| +---------------------------------+ |
| HOME                                |
| [ ] 2-arm hang  17:15  deload: half |
| WEEK  M  T  W  T  F  S  S           |
|       -  v  -  v  -  x  T           |
+-------------------------------------+
```

**Wireframe: DONE.**

```
| +---------------------------------+ |
| | SESSION A DONE                  | |
| | 68 min · 13 sets · 1 new best   | |
| | Next: Sun 11 Oct, Test day 13:00| |
| | View summary >                  | |
| +---------------------------------+ |
```

**Wireframe: IN_PROGRESS** (also shown while the mini-bar is visible):

```
| +---------------------------------+ |
| | SESSION A IN PROGRESS           | |
| | 32:10 · 6 of 13 sets · Rest 1:42| |
| +---------------------------------+ |
| [========== Resume session ========]|
```

### 9.4 Time-budget chips (F11)

* Chips: an optional calendar chip, then `45 60 75 90+` minutes. "90+" means no trimming.
* If today's planned event has a length, the calendar budget is `event duration - 5 min changeover`
  (F11, C49: calendar gym events are training time; travel happens before them). When that equals a
  standard value, the standard chip is preselected and labelled (an 80-min event gives `75 cal`);
  otherwise an extra chip is shown first and preselected (the usual 19:45-21:15 events give `85 cal`).
* Without a calendar event, the user's default (75 min) is preselected.
* Selecting a chip re-runs session build for the selected option (L6: `select` with the budget, then
  `time` trimming in the UI-02 tier order, C19). The card updates its est. minutes and shows what
  was trimmed as one line: "To fit 60 min: 1 set fewer on lateral raise; calf raise left out."
* If the core (tier 0: challenge items, prehab, safety work) does not fit, the card shows the two
  choices provided by `time`: "Short version (core sets halved)" or "Pick another day".
* Emits `time_budget_selected {minutes, source: calendar|chip|default, trimmed_count,
  core_does_not_fit}`.

### 9.5 Other options and the template picker (C35)

"Other options" opens the Template picker sheet (16.9), which lists:

1. The ranked options from `today`, in score order, with their reason chips.
2. Challenge slots (A / B / C / Test) with a status per slot ("due this week", "done Tue", "not
   allowed today: rest day before test").
3. Family combinations (Push, Pull, Legs, Push + Legs, Pull + Legs, Upper, Full body, Core) built on
   demand by `select`, with notes from `warnings` and `safety` (for example "Pull: protected window
   before Session A").
4. "Empty session" (log anything).

Starting any template creates a session whose challenge `session.completed` event later fires on
the template slot, not on the calendar (C35). A calendar event on the same local date within +/- 3 h
is linked after the session (C35); the link can be changed in the Session Summary.

### 9.6 Home habits checklist

Home habits are tiny sessions (`workout_sessions.kind = home`, C34) defined by the active challenge
(D4: the daily hang and the plank become in-app reminders). Each habit row:

```
| [ ] 2-arm hang   17:15   4-5 sets, 1-1.5 min total (deload: half) |
```

* The checkbox is a 48 dp target. Its meaning depends on the habit:
  * Daily two-arm hang: checking it logs the habit as done as prescribed (quick-done), because the
    hang's progression does not depend on per-set results. Undo snackbar.
  * One-arm plank: the checkbox is replaced by a "Start" chip, because the plank progression rule
    needs per-set seconds and the hip-rotation flag (quick-done would feed the rule assumed-clean
    data). Open issue OI-15 asks the user to confirm.
* Tapping the row (not the checkbox) opens the home mini-session (a Live Session with
  `kind = home`): hold timer, felt chips, flags, and no rest alarm unless the habit defines a rest.
* Rows show the habit's state for today: due, done (time), skipped with the reason from the
  definition ("skipped: A day", "off: elbow protocol"), or scaled ("deload: half").
* A weekly "measure your best time fresh" instance of the hang (from the Todoist rule imported in
  D4) shows a "Measure today" badge on the day the definition schedules it.
* Fed by: `challenge.materialize` (habits for today, skip and scale rules), `safety` (disabled in
  injury mode). Writes a home session; the challenge receives `habit.completed` (challenge_events).
* The habit reminder notification (N-HABIT, section 18) never logs completion itself: its actions are
  Start (opens the home mini-session, route `fitapp://habit/<habit_id>`), Snooze 30 min and Skip today
  (ANDROID section 17, C54).
* Emits `habit_toggled {habit_id, done, via: checkbox|session}`.

### 9.7 Week strip

Seven 48 dp cells, Monday to Sunday (ISO week, F18), today outlined. Symbols, each with a text label
for accessibility: `v` done, `*` planned in the calendar, `o` recommended but not in the calendar, `x`
blocked (hard rest), `-` rest or nothing, `T` test day, `!` conflict (for example the calendar says
test day but the test is postponed, C36). Tapping the strip opens the Week tab scrolled to that day.
Fed by `weekplan` and the session store.

### 9.8 Starting a session

1. The user taps Start.
2. If any pre-session prompt is due (section 19.4), the Readiness sheet (16.3) opens: readiness,
   elbow check, soreness. It has its own "Start" button; every answer is optional.
3. Answers are applied before the first exercise is shown: an elbow trace (score 1 or more) makes
   the challenge skip negatives today (rule `safety.elbow.trace`, enforced); a stop-signal kind starts
   the pain protocol (rule `safety.elbow.stop`, enforced, red banner). "Worse" readiness produces a
   suggestion card ("Feeling worse: 1 set fewer per exercise today? Apply / Keep"), never an automatic
   change (D10).
4. The session service starts (foreground service type `health`, fallback `specialUse`, C28), the
   params version is pinned for the session, and Live Session opens.

### 9.9 Empty, error and offline states

* No goals and no challenge: SETUP_NEEDED.
* No calendar permission: the card works without planned events; a muted line says "Not reading your
  calendar" with "Connect".
* Engine error: the ENGINE_ERROR state (7.5).
* Offline: no visible difference (everything is local).

### 9.10 Fed by

`today` (options, verdict, excluded families, reason codes), `weekplan` (week strip, next session),
`calendar.classify` + `challenge.calendar` (today's events and slot matches), `challenge.plan` +
`challenge.materialize` (challenge session, mode, habits), `select` / `rx` / `time` (option
previews, trimming), `safety` (banners, hard rest), `bodyweight` (weigh-in staleness),
`ui.todaycard` (composition; ALGORITHMS_PLANNING section 19.1).

### 9.11 Emits

`screen_viewed`, `today_card_shown`, `today_action`, `time_budget_selected`, `why_opened`,
`habit_toggled`, `prompt_shown` / `prompt_answered` (weigh-in), `session_started`.

---

## 10. Screens: Week (`week`)

### 10.1 Purpose

Show the current ISO week as recommended by the planner next to what is actually in the calendar,
explain every rest or blocked day, let the user put a recommended session into the calendar with one
tap ("Plan it", D11), and show weekly muscle volume against targets.

### 10.2 Layout

```
+-------------------------------------+
| Week 41 · 5-11 Oct          <   >   |
| OAP block 1 · week 5 · deload + test|
| Gym visits: 4 planned of max 4      |
|      RECOMMENDED      CALENDAR      |
| Mon  - Past           Schaken 19:30 |
|        (busy)                       |
| Tue  v C (done)       [E] sessie C  |
|                       19:45         |
| Wed  - Rest: better   -             |
|        Fri; free for                |
|        legs / push                  |
| Thu  * OAP A + legs   [E] sessie A  |
|        add-on ~14 min 19:45  Adopt  |
| Fri  o Legs + push +  -   [Plan it] |
|        rear delts ~50               |
| Sat  x Rest           -             |
|        day before test              |
| Sun  T OAP test       [E] testdag   |
|                       13:00  Adopt  |
|                                     |
| MUSCLE VOLUME (hard sets this week) |
| Quads   ::::::------  0 (+3) / 6  ! |
| Chest   ##::--------  2 (+2) / 6    |
| Lats    covered by challenge        |
| Calves  ##########    3 / 3    mnt  |
+-------------------------------------+
| Today  Week  Exercises  Chall.  You |
+-------------------------------------+
```

`[E]` = an event from the user's calendar without the app's marker; an app-written event shows
`[App]` with the `edit_calendar` icon. In the real fixture week the C, A and test events were created
by hand (by Claude, before the app existed), so they are unmarked and show "Adopt" (D11).

The rows follow the expected week plan of ALGORITHMS_PLANNING section 21.7 (the volume numbers are
illustrative). Wednesday is the day before session A. It is an ordinary gap day (D15, C50): a general
legs and push session may be recommended and planned there, and there is no whole-body penalty for it
in v1. Only the 24 h protected window before A applies (no lats, biceps, brachialis or forearm work,
C12), so the Wednesday row and its day sheet say "free for legs / push" and never "blocked". In this
week the planner prefers Friday (a Wednesday session would take the legs out of Thursday's add-on), so
Wednesday is a soft rest day with the reason "better Friday"; in a week where Wednesday scores best, the
row shows `o Legs + push` with Plan it, as in the synthetic GF-UX-01. Light pull work with low grip
demand (for example the reverse pec deck for the rear delts) may appear on a gap day only when the
user's goals ask for it (C50). The rows that are blocked are the day before B or a test (Saturday here)
and any day a safety state forbids. Tuesday's C event is in the past, so it is not offered for adoption
(ANDROID section 24.2, G2).

### 10.3 Day rows

Each row has three parts: the day, RECOMMENDED (from `weekplan`, including challenge sessions from
`challenge.plan`) and CALENDAR (from `calendar.classify` + `challenge.calendar` matches).

| Row kind | Recommended column | Calendar column | Actions |
|---|---|---|---|
| Done | `v` + session name + "(done)" | linked event title, if any | Tap: session summary |
| Challenge, matched | `*` slot name | event title + time; `[App]`, or `[E]` + Adopt | Tap: day sheet |
| Challenge, not in calendar | `*` slot name | "Adding..." until written | The app writes it automatically for the current and the next ISO week (D11) |
| Recommended general, not planned | `o` type + est. minutes | "-" + `[Plan it]` | Plan it (10.4) |
| Recommended general, planned | `o` type | event title + time | Tap: day sheet |
| Planned by the user, not recommended | "-" | event + "planned (not recommended)" | Tap: Why (which constraint, or why it scores low) |
| Rest (soft) | `-` "Rest" + reason (recovery, no slot, maximum visits reached) | | Tap: Why |
| Blocked (hard) | `x` "Rest" + rule ("day before test", "protected window before A") | | Tap: Why (rule id, challenge) |
| Free with limits | `-` "Free (no pull)" or "Free (legs/push only)" | | Tap: day sheet with the allowed families |
| Busy | reason from the calendar (for example chess) | event title | none |
| Run | `run` badge + minutes | event title | none; runs count as leg fatigue only (F6) |
| Conflict (C36) | "Test postponed: no clean session yet" | "testdag 13:00" | `[App]` event: already rewritten to session B (info line). `[E]` event: "Rename to session B" / "Keep" |

Reasons use the same template strings as reason chips (19.1), and every row has a Why.

### 10.4 Plan it (D11)

* One tap on `[Plan it]` writes the recommended general session to the primary calendar: title
  `Gym — Legs + push` (English labels for general sessions, F25; challenge events use the
  definition's Dutch title template), location = the gym profile location, start = the planner's
  suggested start (inside the usual training window), duration = est. minutes plus the 5 min
  changeover, because a calendar gym event is training time (F11, C49; the write itself is specified
  in ALGORITHMS_PLANNING section 8.9), description = exercise lines (English) + a final marker line.
* The marker format is the single format of C37, shared with `challenge.calendar`, DATA_MODEL and
  ANDROID, so the app only ever edits its own events (D11): the last line of the description is
  `[fitapp:v1 <uuid> <slotKey>]`. For a general session `<uuid>` is the recommendation id and
  `<slotKey>` is `gen/<isoYear>-W<ww>/<isoDow>`, for example `gen/2026-W41/3` for Wednesday 7 October;
  challenge events use the enrollment id and `oap/<slot>/<isoYear>-W<ww>` (for example
  `oap/C/2026-W42`). The slot key matches `[A-Za-z0-9_./-]{1,64}`. Parsing and the edit policy are
  owned by ANDROID sections 16.5 and 16.7 (C37).
* Snackbar "Added to calendar · Undo" (Undo deletes the app's event while it is still unmodified).
* Without WRITE_CALENDAR, Plan it opens the permission sub-step first.
* Emits `plan_it_tapped {date, type, recommendation_id}` and `calendar_event_written {op: insert,
  kind: general, instance_key, marker_key: gen/2026-W41/3, cause: plan_it, provider_rows}` (a refused or
  failed write is `calendar_write_failed {op, error_class}` instead).

### 10.5 App-written events, adopt, and challenge write-back

* Challenge sessions and test days for the current and next ISO week are written and updated
  automatically by `challenge.calendar` (D11), with the definition's title template (Dutch for OAP,
  for example "Gym — sessie C (volume en techniek)", F25) and an English description. The Week tab
  shows them with `[App]`. When a recalculation changes a session's content, the description is updated
  silently (unless the user edited it, see below) and a one-line info appears on that row for 24 h:
  "Calendar updated: loads changed after test day".
* Unmarked events are never modified (D11). An unmarked event that matches a challenge slot shows
  "Adopt". Adopting appends the marker line to the event's description after a one-line explanation
  in a sheet ("The app will keep this event up to date: title, time and exercise list." `[Adopt]`
  `[Not now]`). This is needed once for the events Claude created by hand.
* User edits to an `[App]` event follow the edit policy owned by ANDROID section 16.7 (C37), and the
  Week tab only shows its result:
  * A time the user moved wins: the app never moves the event back, and the planner treats the new
    time as the anchor. The row shows "Moved by you".
  * A title or description the user edited stops all app updates for that event (C36 slot-type
    changes then appear as a "Rename?" prompt). The row shows "Edited by you: the app no longer
    updates this event".
  * The app deletes only its own unmodified future events; for any other event it asks once ("Remove
    'Gym — sessie B' from your calendar?").
  * An event the user deleted is respected: the app does not re-create that slot for that week; the
    row offers "Add to calendar".
* Emits `calendar_event_adopted {slot, instance_key}`, `calendar_event_written {op, kind, ...}`.

### 10.6 Degraded mode (no calendar)

If calendar permission is missing or the provider fails, a muted banner shows "Planning without your
calendar. Challenge sessions are placed on their preferred days. [Connect]". The CALENDAR column
shows "-" everywhere and Plan it is replaced by "Connect calendar".

### 10.7 Week navigation

* `<` and `>` move by one ISO week. Past weeks are read-only (done sessions, volume achieved). The
  current and next week are interactive. Further weeks show the challenge block structure only
  ("Block 2 · week 3") with no recommendations.
* Swiping between weeks is not offered (P5); the arrows are 48 dp targets.

### 10.8 Muscle volume bars

Rendered by `ui.volumebars` (section 19.5). One row per muscle group with goal Improve or Maintain;
Skip groups collapse into one line. Each row: group name, bar (done solid, planned remaining dotted,
target tick, MRV marker), numbers `done (+planned) / target`, goal tag, and a `!` when the
WarningEngine reports a deficit (amber) or an overshoot. Groups owned by an active challenge show
"covered by challenge" instead of a bar (D6; challenge volume is exempt from MRV warnings). Tapping a
row opens a sheet with the per-exercise contributions this week and the Why for the target.

### 10.9 Day sheet

Opens from any day row: the date, the recommended session (exercise list preview, est. minutes,
reasons), the calendar event (title, time, marker status, classification and "Correct"), a readiness
preview for the families involved ("legs 100 % recovered"), and actions: Start (today only), Plan
it, Adopt, Remove app event (with undo), Correct classification.

### 10.10 Empty, error and offline

* No challenge and no goals: only calendar events are shown; the recommendations column says "Set
  your muscle goals to get recommendations".
* Planner error: the RECOMMENDED column shows "-" and one row says "Couldn't plan this week (weekplan
  error)". The CALENDAR column still works.
* Offline: no change.

### 10.11 Fed by

`weekplan`, `challenge.plan`, `challenge.contract` (blocked days, protected windows, add-on slots),
`calendar.classify`, `challenge.calendar`, `today` (today's row), `setcredit`, `targets`,
`warnings`, `ui.volumebars`, `runs`.

### 10.12 Emits

`screen_viewed {iso_week_offset}`, `week_day_opened`, `plan_it_tapped`, `calendar_event_written`,
`calendar_event_adopted`, `calendar_correction`, `volume_row_opened`, `why_opened`.

---

## 11. Screens: Live Session (`session.live`, full-screen flow)

### 11.1 Purpose

Log every set with as few taps as possible (typical working set: one tap), follow the prescription,
deviate freely with reasons captured in zero or one tap, rest with a reliable timer, and never lose
data. This is the most-used screen; every rule in section 2 applies here most strictly.

### 11.2 Screen structure

```
+-------------------------------------+  -
| v  Session A     32:10  Finish   :  |   | header 56 dp (passive + 2 actions)
| =========------------  6 / 13 sets  |   | progress 8 dp
| WARM-UP                       done  |   |
| OAP · SESSION A                     |   | top area (about 40 %), scrolls:
| v Assisted 1-arm (L/R)      3/3     |   | block headers + compact exercise
| > Weighted pull-up          1/2     |   | list. Tap = jump; long-press =
| o Top hold (L/R)          0/2 rnd   |   | drag to reorder
| o 90-degree hold (L/R)      0/2     |   |
| ADD-ON · LEGS  ~15 min       Skip   |   |
| o Leg press                 0/2     |   |
| + Add exercise                      |  -
+-------------------------------------+  -
| WEIGHTED PULL-UP   Set 2 of 2    :  |   |
| Target BW + 22.5 kg x 4             |   |
|   aim: 2 in reserve            (i)  |   |
| Last  BW + 22.5 x 4 4 4 3 · 1 Oct   |   | action area (about 60 %), fixed:
|                                     |   | current-set card, or rest card,
|  ( - )     BW + 22.5 kg     ( + )   |   | or hold card, or block card
|            20 + 2.5 on belt         |   |
|  ( - )        4 reps        ( + )   |   |
|                                     |   |
| [ Grip slipped before last rep ]    |   |
| Reps left in the tank?              |   |
| [ 0 ][ 1 ][ 2 ][ 3 ][ 4 ][ 5+ ]     |   |
+-------------------------------------+  -
```

The navigation bar is hidden. Status bar stays visible (time, battery, the alarm icon during rest
when `setAlarmClock` is used).

### 11.3 Header and session menu

* `v` (48 dp): minimise. Returns to the previous tab and shows the mini-bar (3.3).
* Title: session name ("Session A", "Legs + push", "Free session").
* Elapsed time since the session's start Instant (mm:ss, then h:mm:ss).
* "Finish" (text button, 48 dp). See 11.14.
* `:` session menu: Add exercise · Report pain · Time budget (shows the 45/60/75/90+ chips again) ·
  Notes · Show warm-up · Abandon session.
* Status icons left of Finish, only when relevant: `cloud_off` (offline with pending changes, muted),
  advisory icon if a reliability check fails (alarm or notifications), safety icon while a safety
  state is active (the red banner is shown below the header in that case).

The progress bar counts working sets (warm-up sets excluded; a unilateral L+R pair counts as 1 set,
matching C7).

**Overrun line (C63).** When the elapsed time plus the remaining estimate from `time` (TIME-1) exceeds
the session budget (the selected time chip) by more than 10 min, a muted line appears under the
progress bar: "About 12 min over your 85 min. Options: Time budget". Tapping it opens the time-budget
chips (9.4), so `time` can trim the rest of the session. It is shown once per session and logged as
`session_overrun_shown`. The 10 min margin is fixed by C63; it is the `PlatformConfig` key
`session.overrunLineMin` (ANDROID section 25.5, outside `params_hash`, C54), not a params key. Whether it works is visible in the share
of sessions that still end more than 10 min over budget after the line was shown; a different margin
needs a change of C63 first.

### 11.4 Exercise list (top area)

| Row state | Marker | Text | Tap |
|---|---|---|---|
| Done | `v` filled | name + "3/3" | expands to show the logged sets; tapping a set opens Set edit (16.14) |
| Current | `>` primary | name + "1/2", bold | none (already current) |
| Pending | `o` | name + "0/2" | makes it current (jump) |
| Deferred | `o` + "later" tag | name | makes it current |
| Skipped | strike-through, muted | name + reason ("occupied") | "Undo skip" |
| Ended early | `v` muted | name + "2/3, pain" | expands |
| Excluded by a rule | muted, lock icon | name + "skipped today: elbow trace" | Why (enforced safety rule, no override) |

Block headers (C34): "WARM-UP", "OAP · SESSION A" (challenge block), "ADD-ON · LEGS · ~15 min"
(general block built by `select` with the add-on capacity from `time`, the contract's allowed
patterns and forbidden muscles). The add-on header has "Skip" (one-tap reason sheet, `no_time`
first). A general session has a single block without a header.

Long-press (300 ms) on a pending row starts drag-to-reorder with a `LONG_PRESS` haptic. Accessible
alternative: "Move up" / "Move down" in the exercise's `:` menu. Moving an item of the challenge
block out of the program's order shows an advisory line once: "Order changed from the program
(Session A does one-arm work first, while fresh)."

"+ Add exercise" at the end opens the Add exercise sheet (16.6).

### 11.5 Blocks

**Warm-up block.** For challenge sessions, the challenge's fixed warm-up (OAP `warmup10`: 3 min row,
20 face pulls, 10 scap pulls, 2 ramp pull-up sets). For general sessions, an optional "General
warm-up · 10 min" row (TIME-1 budgets it, C19). The block card replaces the set card while it is
current:

```
| WARM-UP · 10 min                    |
| Row 3:00            [ Start 3:00 ]  |
| Face pulls x 20     [   Done    ]   |
| Scap pulls x 10     [   Done    ]   |
| Pull-up ramp  BW x 3 [Done] x 2 [Done]|
| [========== All done =============] |
```

Warm-up items are logged with one "Done" tap each, without RIR (C63: D9 applies to working sets); the
rows carry `rir_source = missing` (DATA_MODEL E5) and never enter an estimator. Specific warm-up sets
generated by `warmup` (WARMUP-1) for a
working exercise appear as the first rows of that exercise's card ("Warm-up BW x 4 [Done]",
"Warm-up +12.5 kg x 2 [Done]"), also without RIR. Add-on blocks have no second general warm-up, only
specific warm-up sets (C34).

**Challenge block.** Materialized by `challenge.materialize` for the current phase and mode
(deload, test pending, injury). Items excluded by rules are shown, not hidden ("Calibration
negative: skipped today, elbow trace reported").

**General / add-on block.** Built by `select` (+ `order`, `rx`, `warmup`, `rest`). Exercises in it
follow the user's preferences and the contract (forbidden muscles never appear).

### 11.6 The current-set card: common anatomy

From top to bottom:

1. **Title row**: exercise name (Title L, wraps to 2 lines rather than truncating), "Set 2 of 4",
   side badge for unilateral work, `:` exercise menu (Swap · Skip · Do later · Add a set (C63) · End
   exercise · Remove (only with 0 logged sets) · Move up / down · Note · Exercise details).
2. **Target line**: the prescription from `rx` (or the challenge item in RULE mode, C15), for example
   "Target BW + 22.5 kg x 4 · aim: 2 in reserve", with `(i)` opening the Why sheet for this
   prescription (RX-1 mode MODEL / DOUBLE / RULE, rule id, numbers). An amber dot appears when the
   WarningEngine has a note on this item (for example "3 sets (you prefer 2)", or a challenge rule load
   above the generic load-jump cap, D13 / C45). When a challenge in-session rule changed the remaining
   sets, a "Rule: ... · Undo" line sits directly under the target line (11.10).
3. **Last line** (muted): the last performance of this exercise, same set index first:
   "Last BW + 22.5 x 4 4 4 3 · Thu 1 Oct".
4. **Load stepper row** (if the load type has a load) and a secondary line (plate breakdown, assist
   kg, one-arm load).
5. **Reps stepper row** (or the hold result row).
6. **Reason chips** (only when deviating, 11.8).
7. **Flag toggles** (only for exercises that define flags, 6.4).
8. **Log row**: label + RIR chips (D9), or Felt chips for holds.

Values are prefilled by `ui.prefill` (section 19.2). A typical set therefore needs zero edits and one
tap.

### 11.7 Card variants by load type

| Variant | Load row | Secondary line | Step | Notes |
|---|---|---|---|---|
| External (barbell, dumbbell, machine, cable) | `( - ) 60 kg ( + )` | plates per side for barbells ("20 + 5 per side"), "per hand" for dumbbells | loadable increment | Dumbbell loads are per hand |
| Bodyweight only | none; "BW" label with "+ add weight" text button | | | "+ add weight" turns the row into weighted bodyweight |
| Weighted bodyweight (dip belt) | `( - ) BW + 22.5 kg ( + )` | plate breakdown on the belt "20 + 2.5" | next loadable belt combination | System load is never shown here; it is in Why (BW 70.7 + 22.5 = 93.2 kg) |
| Assisted, pin (lb machine) | `( - ) harder  Pin 40 lb  easier ( + )` | "≈ 24.6 kg assist · one-arm load 46.1 kg" or for two-arm "effective 46.1 kg" (1 decimal, C54) | 5 lb | Lower pin = harder; arrow icon + text. Native lb shown, kg secondary (C2, D12) |
| Assisted, fingers (ordinal) | `( - ) harder  3 fingers  easier ( + )` | "towel / finger assist" | 1 finger | Ordinal only, never converted to kg (C2) |
| Timed hold | none or pin / added load if the hold is loaded | implement note ("fixed neutral handle") | | Hold card (11.7.3) |
| Eccentric (negatives) | as weighted or assisted | plate breakdown | as above | Extra "Shortest lowering" stepper (11.7.5) |
| Top-hold rounds | none | "Round 2 of 3 · 4 attempts" | 0.5 s | Round entry (11.7.4) |

#### 11.7.1 Assisted pin, two-arm or one-arm

```
| ASSISTED ONE-ARM PULL-UP  Set 2 of 3 :|
| Target 5 reps/arm · aim: 2 in reserve |
| Last  50 lb  L 5 5 4 · R 5 4 4 · 29 Sep|
|                                     |
| ( - ) harder   Pin 50 lb  easier ( + )|
|   ≈ 29.2 kg assist                  |
|   one-arm load 41.5 kg  (59 % BW)   |
```

The pin applies to both sides by default (one pin per set, set by the program). "Different pin for
right" in the exercise menu allows a per-side pin; each side row stores its own raw pin, unit,
machine id and computed assist kg (C2).

#### 11.7.2 Unilateral pairs (left first, right no more than left)

```
| ASSISTED ONE-ARM PULL-UP  Set 2 of 3 :|
| Target 5 reps/arm · aim: 2 in reserve |
| ( - ) harder   Pin 50 lb  easier ( + )|
|        LEFT (first)      RIGHT      |
| reps  ( - ) 5 ( + )    ( - ) 5 ( + )|
|        active            waiting    |
| [ Torso rotated ]                   |
| LEFT · reps left in the tank?       |
| [ 0 ][ 1 ][ 2 ][ 3 ][ 4 ][ 5+ ]     |
```

Flow:

1. The left side is active (side order from the definition, `sideOrder: [L, R]`; default left first).
2. Tapping a RIR chip logs the left side row (`side = L`, counts 0.5 set, C7). Snackbar "Left logged ·
   Undo".
3. If `rest` returns a between-sides rest of at least 10 s (`timer.minTimerS`, ANDROID section 12.3,
   C54), a short inline countdown runs; otherwise the card shows "Switch to your right arm" for 1.5 s
   (no timer, no alarm).
4. The right side becomes active. If the exercise has the side rule "right does no more clean reps
   than left" (the challenge's target-only in-session rule `ins.scap.matchLeft`), the right reps
   prefill becomes `min(target, left reps)` and a hint shows: "Right ≤ left: 4 clean reps".
5. If the user raises the right reps above the left, an advisory line appears: "Right above left
   (program rule: right ≤ left)". It never blocks.
6. Tapping a RIR chip logs the right side row; the pair is complete; the rest timer starts with the
   after-pair rest.

Per-side values and asymmetry are stored per row; the UI never merges sides.

#### 11.7.3 Timed holds (hands on the bar, phone on the floor)

Four phases in one card:

```
READY                                  LEAD-IN
| ONE-ARM DEAD HANG   LEFT  1 of 4  : | |              3                      |
| Target 10 s · stop when your torso  | |   get on the bar · beeps 3, 2, 1    |
| turns · fixed neutral handle        | |            [ Cancel ]               |
| Last  L 10 10 9 10 · 1 Oct          |
| [====== START (5 s lead-in) ======] |

HOLDING                                RESULT
|             00:07                   | | Result   ( - )  10 s  ( + )         |
|   ==========-----  target 10 s      | |   (timer ran 12.4 s)                |
|   TAP ANYWHERE TO STOP              | | [ Torso rotated ]                   |
|   beep every 5 s, double at 10 s    | | How did it feel?                    |
|                                     | | [ Max ][ Hard ][  OK  ][ Easy ]     |
```

* **Lead-in.** Default 5 s with audible and haptic 3-2-1 for the last three seconds (the user needs
  time to put the phone down and grip the bar). Options 3 / 5 / 10 s in You > Rest and alerts.
* **Holding.** Count-up in 96 sp, a bar filling toward the target, a beep and 40 ms tick every 5 s, a
  double beep and double pulse at the target. While holding, the whole screen except the header is
  one stop target ("tap anywhere"), because the user taps with a chalky or sweaty hand after
  letting go. Beeps stop after the target.
* **Result prefill.** For prescribed holds that do not go to failure: if the timer ran at least as
  long as the target, the result is prefilled with the target (the double beep is the release cue);
  otherwise with the elapsed time rounded down to the step. For maximum holds (test day, or items
  marked `toFailure`): the elapsed time rounded down. The user corrects with the stepper; the raw
  timer value is logged too (`hold_timer_s`) so reaction delay can be studied.
* **Step.** 0.5 s when the target is below 10 s, otherwise 1 s.
* **Felt chips log the hold** (D9): Max = 0 s in reserve, Hard = 2 s, OK = 5 s, Easy = 10 s or more
  (lower bound) for `hold` (HOLD-1).
* **Stop condition.** The stop condition from the definition is printed in the target line ("stop
  when your torso turns"); the torso-rotation flag is offered in the result phase.
* Unilateral holds run left then right, as in 11.7.2.
* A hold longer than 300 s auto-stops (`hold.maxHoldS` of `PlatformConfig`, ANDROID section 25.5,
  19.9, C54).

#### 11.7.4 Top-hold rounds x attempts

OAP session A has top holds as 3 rounds x 4 short attempts per arm. Running a lead-in for every
0.5-8 s attempt would be slower than the attempts themselves, so rounds use a compact entry:

```
| ONE-ARM TOP HOLD   LEFT · round 2/3 :|
| 4 short attempts · no help          |
| Attempt  1      2      3      4      |
|        (-)1.0(+) (-)0.5(+) ...        |
| [ Count seconds aloud: off ]        |
| How did the round feel?             |
| [ Max ][ Hard ][  OK  ][ Easy ]     |
```

Attempt fields are prefilled from the previous round of the same arm (or 0), step 0.5 s; the
optional "count aloud" plays one beep per second while the user does the round. The felt chip logs
four rows with `round_index` and `attempt_index`. The deload transform of rounds is decided by
`challenge.materialize`: rounds are halved and rounded up like sets (CHALLENGE_ENGINE section 6.4), so
the 3 rounds become 2 in the deload and test-pending weeks.

#### 11.7.5 Eccentric sets (negatives)

```
| WEIGHTED NEGATIVE   Set 1 of 3     : |
| Target 2 reps · lower in 5 s or more |
|  ( - )    BW + 47.5 kg     ( + )    |
|           20 + 20 + 5 + 2.5         |
|  ( - )       2 reps        ( + )    |
|  Shortest lowering ( - ) 6 s ( + )  |
| [ Count 1 per second ]  [ Jerky ]   |
| How did the set feel?               |
| [ Max ][ Hard ][  OK  ][ Easy ]     |
```

* "Shortest lowering" stores the minimum lowering time over the set's reps in `set_logs.ecc_s_actual`,
  because the program's rules use the minimum (`min(ex.neg.ecc_s) >= 8` to progress, `< 5` to reset);
  CHALLENGE_ENGINE section 3.7 maps `ecc_s` to this value.
* "Count 1 per second" plays a lead-in and then one beep per second, to pace the lowering.
* The flags come from the item (`flagsOffered`): `jerky` on the weighted negatives, `jerky` and
  `hand_shift` ("Hand shifted") on the one-arm negative.
* Negatives are logged by lowering time plus the effort chips Max / Hard / OK / Easy, like holds; there
  is no RIR chip, because RIR is meaningless for negatives (C66; D9 covers dynamic working sets). The
  effort chip is the log action, as on hold cards (`set_logged.log_control = felt_chip`); the shortest
  lowering goes to `set_logs.ecc_s_actual` and the `jerky` flag to the set's flags.
* Eccentric sets never enter SSE-1 (C8); that is the engine's concern, not the UI's.

#### 11.7.6 Timed cardio item (warm-up row)

"Row 3:00 [Start 3:00]" runs a simple countdown in the block card with the rest-end alert at zero. An
alarm-backed countdown would need `RestTimerController` with `purpose = interval`, which the ANDROID
section 12.3 reducer does not support in v1 (its OI-27). Until it does, the row countdown is an in-app
countdown that keeps the screen on while it runs (like the hold timer, 19.9) and vibrates at zero only
while the app is visible (Open issue OI-27).

### 11.8 Logging, deviation and reason chips

**What one RIR tap stores** (domain row in `set_logs`, written in one Room transaction together with
the `set_logged` event, C23/C24):

* targets (`planned_*`) and actuals: load in native value + unit + kg, machine id and computed assist
  kg (C2), added kg, reps or hold seconds, side (`both | L | R`), set kind (`working`), round and
  attempt index where relevant, eccentric seconds, flags[].
* `rir_reported` exactly as tapped (0-5; "5+" stored as 5 with `rir_is_lower_bound = true`),
  `rir_source = chip`. The raw value is what challenge rules evaluate (C17); the general engine applies
  RIRCAL-1's bias itself.
* bodyweight snapshot (C32) and the timestamps `completed_at` (the tap) and `started_at` (11.13.8).
* rest fields with the DATA_MODEL section 9.6.4 `set_logs` column names, which are canonical (C55):
  `rest_timer_planned_s`, `rest_adjust_s`, `rest_timer_overridden`, `rest_before_s`, `rest_source` and
  `rest_end_reason` (11.13.8).
* `deviation_reason_code` if a reason chip was chosen (null when untouched, counted as `none` in
  reviews).
* Column names, set kinds, sides and set flags are the DATA_MODEL section 8 codes (C38).

**Deviation detection** is computed by `ui.prefill` (`deviation(set)`, section 19.2): the load is at
least half a step away from the target, reps outside the target (or the range), a hold below 80 % of
the target, or the side rule broken. When the user changes a value so that the set deviates, reason
chips appear above the flags, ordered by `ui.reasonchips` (section 19.3):

```
| Why the change? (optional)          |
| [Too easy] [Feeling good] [Other]   |
```

After a set is logged, a RIR at least 2 away from the target RIR also counts as a deviation; the
optional chips then appear in the rest card for that logged set ("4 in reserve, aim 2. Why?").
Untouched chips are logged as `none`. Chips never block logging.

**Undo.** "Set 2 logged · Undo" for 6 s. Undo soft-deletes the row, cancels the rest timer it started,
restores the card with the same values, and emits `set_undone`.

**Edits later.** Tapping a logged set in the list opens Set edit (16.14). Edits are stored
(last-writer-wins row, C23) and every edit emits `set_edited {field, from, to}`.

### 11.9 Flags

Flags offered depend on the exercise (catalog `flags` or challenge exercise `flags`):

| Flag | Stored code (E6, C38) | Shown for | Effect owner |
|---|---|---|---|
| Grip slipped before the last rep | `grip_slip_before_last` | weighted pull-up and other grip-limited pulls | `challenge.eca` (`prog.gripSlip.lock`: no increase this time; in-session `ins.wpu.gripStop`: "end the set" info line) |
| Torso rotated | `torso_rotation` | one-arm hangs, scap pulls, finger-assisted one-arm pulls (not the assisted machine: the knee platform prevents rotation) | challenge rules (`ins.rotationStop` info line) and `hold` |
| Jerky lowering | `jerky` | negatives | challenge (`prog.B.neg.reset`) |
| Hips rotated | `hip_rotation` | one-arm plank | challenge (`prog.plank`; `ins.plank.hipStop` info line) |
| Hand shifted | `hand_shift` | one-arm negative | challenge (the negative is not controlled, for example on test day: `oa_neg5_unassisted` = no) |

When grip slip is flagged, the rest card shows an info line: "Grip slip noted: no load increase from
this session (rule prog.gripSlip.lock)". Rule ids are rendered from the definition, never hardcoded.

### 11.10 In-session suggestions (C16) and challenge in-session rules (D14, C46)

Two different mechanisms change the remaining sets of an exercise during a session. General
(non-challenge) items get **suggestions** that the user applies or keeps (D10, C16). Items of the
active challenge get the user's approved **challenge rules**, which apply automatically with an Undo
(D14, C46). The two never mix: challenge items run in RULE mode (C15), so `autoreg` runs only in shadow
for them and shows no suggestion card.

**Engine suggestions (general items only).** After each logged working set of a general item,
`autoreg` (AUTO-1 with the UI-06 trigger) decides whether to suggest a change for the remaining sets.
Trigger and amount are the engine's (C16: first set |ΔRIR| >= 2, comparing the bias-corrected reported
RIR with the model-predicted RIR (C57); two consecutive sets with the same sign >= 1; >= 2 reps short;
TOO_EASY / TOO_HARD reason; clamp -8 % / +6 %; hysteresis 1.5 RIR; at most one suggestion per exercise
per session, general items only, C46). The UI renders it inside the rest card:

```
| SUGGESTION                      (i) |
| Next sets: BW + 25 kg x 4           |
| You had 4 in reserve, aim was 2.    |
|      [ Apply ]        [ Keep ]      |
```

* Never auto-applied (D10, C16). "Keep" is the default: if the user logs the next set without
  tapping either button, that counts as Keep (`suggestion_kept {implicit: true}`).
* Apply sets the accepted adjustment that `ui.prefill` uses for the remaining sets of that exercise;
  snackbar with Undo.
* Pain reason bypass: a Pain reason does not produce a suggestion; it opens the Pain sheet (11.11).
* Not a suggestion (C57): after a first-exposure calibration set, `rx` plans the remaining sets of
  that exercise from it automatically, because it fills an undetermined plan rather than changing one.
  The target line then reads "Planned from your first set" with a Why.

**Challenge in-session rules (D14, C46).** The in-session rules of the user's approved challenge
change the remaining sets of that item immediately; there is no Apply / Keep choice. For OAP these are
the session-A calibration `ins.A.oaa.calibrate` (first assisted pair: 1-2 reps -> pin +5 lb, 5 or more
-> pin -5 lb, tag A), `ins.h90.fail` (a 90-degree hold below 6 s -> remaining sets pin +5 lb, tag A)
and, only when their proposals are approved, `ins.A.oaa.fail` (P13, pin +5 lb after a failed set) and
`ins.B.wpu.grind` (P12, -2.5 kg on B after a grind). The rule ids, texts and amounts are rendered from
the definition, never hardcoded. The current-set card shows one line under the target (and the rest
card shows the same line for the set that triggered it):

```
| ASSISTED ONE-ARM PULL-UP  Set 2 of 3 :|
| Target 3 reps/arm · aim: 1 in reserve |
| Rule: pin 40 -> 45 lb (easier) for    |
|   the remaining sets (A)   [ Undo ] > |
| ( - ) harder   Pin 45 lb  easier ( + )|
```

* The prefill of every remaining set of that item already shows the new value; the "Rule: ..." line
  stays on each of those sets so the user always sees why the value changed. Tapping the line (`>`)
  opens the Why sheet with the rule text, its tag (A = from your program, P = approved proposal), the
  bindings it read and the rule id (for example `ins.A.oaa.calibrate`).
* Every application is logged as `challenge_rule_applied {rule_id, rule_tag, proposal_id?,
  session_exercise_id, field, from, to, sets_affected}` (DATA_MODEL section 9.8.1 registry, C39); the
  changed rows carry `planned_source = challenge_rule`.
* **Undo** restores the previous targets of the remaining sets and is logged as an override with a
  reason (C46). Tapping Undo turns the line into "Undo this rule? Why:" with the reason chips of 19.3
  for the direction of the undone change (for example `too_easy`, `too_hard`, `other`) and "Cancel";
  one tap on a reason performs the Undo with that reason, so every Undo carries one. The line then reads
  "Rule undone (too hard)". The Undo is available until the item's last set is logged. It sets the
  session exercise's `user_override` with `override_reason_code` and is logged as
  `challenge_rule_undone {rule_id, session_exercise_id, reason_code, ms_since}` (DATA_MODEL sections
  9.6.3 and 9.8.1; CHALLENGE_ENGINE section 6.10).
* Limits (owner CHALLENGE_ENGINE section 6.10): each in-session rule applies at most once per item per
  session, notify-only rules may fire once per logged row, and a rule that was undone does not fire
  again on that item in that session. Different rules may each act once on the same item (for example
  the session-A calibration on pair 1 and P13 on a later pair). The "one suggestion per exercise" limit
  of C16 does not apply to challenge items.
* A load from a challenge rule that jumps more than the generic load-jump cap is applied anyway (D13,
  C45); the target shows the amber dot of the WarningEngine note WE-S2 at WARN severity ("Bigger jump
  than the general cap; your program rule applies") with a Why. It never blocks.
* Target-only rules (the right-side rep cap `ins.scap.matchLeft`) set the prefill and show their hint
  (11.7.2); they need no Undo because the user can still change the value. Notify-only rules
  (`ins.wpu.gripStop`, `ins.rotationStop`, `ins.plank.hipStop`) show an info line in the rest card.
* Safety rules are not in-session challenge rules: the pain protocol and the other SAFETY rules are
  enforced and cannot be undone here (C33, C45).

### 11.11 Pain during a session

* Entry: the Pain reason chip, the session menu "Report pain", or the flag-less "Something hurts"
  link in the exercise menu.
* The Pain sheet (16.2) collects location, side, severity and (for elbows with an active challenge
  pain rule) the symptom kind.
* After submit, enforced by `safety` (C33, D10), with a 10 s "Undo (wrong tap)":
  1. The current exercise ends; its remaining sets are marked skipped with reason `pain`.
  2. Severity moderate or sharp (mapped to 5 or 8 on 0-10, C64; at or above REASON-1's stop threshold
     of 4/10; mild maps to 2): remaining exercises that load the same joint are removed from today's
     plan, listed in a red banner.
  3. A challenge stop-signal kind (OAP: medial elbow ache, crease pain on extension, ulnar tingling)
     starts the challenge's pain protocol (rule `safety.elbow.stop`): the remaining challenge items
     are re-materialized in injury mode and the banner says "Elbow protocol started · 14 days".
* The disclaimer is shown in the banner and the sheet.

### 11.12 Skip, occupied, do later, swap, add, remove, reorder

**Skip** (exercise menu > Skip, or the add-on header Skip): opens the Skip sheet (16.4). One tap on
a reason completes the skip. Reasons and order come from `ui.reasonchips` (skip direction).

**Occupied** inside the Skip sheet does not skip; the sheet changes to:

```
| Leg press is occupied               |
| [===== Do later (after leg curl) ==]|
| [ Swap ]                            |
| Do leg curl now, come back after  > |
| Skip anyway                         |
```

* "Do later" moves the exercise after the next not-done exercise (UI-05) and increments its defer
  count. After 2 defers (`ui.reasonchips.max_defers`), Swap becomes the primary choice.
* The reorder suggestion comes from `substitute` (SUB-1: "Do k now, then come back").
* "Swap" opens the Swap sheet (16.5) with the top 3 substitutes from `substitute`.
* An occupied event is logged with station, weekday and hour so SUB-1 can learn busy times.

**Add** opens the Add exercise sheet (16.6). In a challenge session, added exercises go into the
general block (never the challenge block). Exercises that a hard rule forbids today (for example
biceps work in the protected window before Session A, D5 / C12) are shown with a red "Not today"
label; adding one needs a second tap "Add anyway" and is logged as `rule_override {rule_id}` (the app
never recommends it; logging is never blocked). The consequences are the challenge engine's (a
warning and the `rule_override` record, CHALLENGE_ENGINE section 8.5).

**Add a set** (exercise menu, C63) appends one more set to the current exercise with the targets of its
last set (for a unilateral item, one more L/R pair). Snackbar "Set 4 added · Undo". It is logged as
`override_applied {field: sets, from, to}`. Inside a challenge block the extra set is logged as a
deviation from the program; the challenge rules decide whether it affects the session's validity. It
never changes the general engine's plan for other exercises.

**Remove** is offered only for exercises with no logged sets (undo). For exercises with logged sets
the menu offers **End exercise**: logged sets stay, remaining sets are marked skipped with a reason
(one tap).

**Reorder**: drag (long-press) or Move up / down (11.4).

### 11.13 Rest timer

#### 11.13.1 When it starts and how long

* Logging a working set (or the second side of a pair) starts the rest timer automatically.
* The duration is `rest` (REST-1, C18): 180 s strength-heavy, 150 s lower compound, 120 s upper
  compound, 90 s isolation and holds, 60 s core, 45-60 s warm-ups, with modifiers, clamped to
  60-300 s; challenge rest values override (for example OAP session C weighted pull-ups 150 s); between
  the members of a superset (F13) it is 60 s (C56). The UI never has its own rest table (C18).
* Between sides of a unilateral pair: `rest` returns a separate between-sides value; 0 means "switch
  now" without a timer.
* Rest is presented as a trade-off, not a requirement: the Why sheet says "Longer rest keeps the next
  set stronger; for muscle growth, rest beyond about 1-2 minutes matters little (ACSM 2026). Skip it
  if you're short of time." (C18).

#### 11.13.2 Inline rest card

```
+-------------------------------------+
| REST · weighted pull-up   next: 2/2 |
|                                     |
|               2:18                  |
| ======================--------      |
|  [ -15 s ]   [  Skip  ]  [ +15 s ]  |
|                                     |
| Next: BW + 22.5 kg x 4              |
| Set 1: 4 reps · 2 in reserve   Undo |
| (suggestion card, if any)           |
+-------------------------------------+
```

* Countdown in 96 sp tabular digits, depleting bar, "next: set 2 of 2" label.
* Buttons 64 dp tall, tonal (not primary): -15 s, Skip, +15 s. They ignore input for 500 ms after the
  rest card appears (input guard, P5), because they occupy the screen region where the RIR chip was
  just tapped.
* Next-set preview line; last-set summary line with Undo while the snackbar window is open.
* Slot for an engine suggestion card or a challenge "Rule: ... · Undo" line (11.10) and for post-log
  reason chips (11.8).
* Tapping the countdown toggles **focus mode**: a full-screen dark rest view with the same digits and
  buttons (useful with the phone on a bench). Default off; remembered per user.
* **Overtime**: when the time is up, the digits switch to "+0:23" counting up in advisory colour, the
  bar disappears, and the card shows the next set's card directly below the overtime line, so the
  user can log the next set immediately. One reminder pulse at +60 s.

#### 11.13.3 Notification during the session (foreground service)

The session runs a foreground service (type `health` when ACTIVITY_RECOGNITION is granted, otherwise
`specialUse`, C28) with one ongoing notification on channel `session_ongoing`
(IMPORTANCE_LOW, silent). Its structure, modes and behaviour are owned by ANDROID section 13.3 (C54);
its wording is this document's (strings of 24.6; ANDROID section 13.1 says its own texts illustrate
structure only). The rows below are the content for the Thursday fixture (Session A, weighted
pull-up):

| Session state | Title | Text | Chronometer | Actions |
|---|---|---|---|---|
| Between sets, no rest | "Session A · OAP" | "Set 2 of 2 · Weighted pull-up" | count-up from the session start | Open |
| Resting | "Rest · next: Weighted pull-up 2/2" | "BW + 22.5 kg × 4" | countdown to the rest end (`setUsesChronometer(true)`, `setChronometerCountDown(true)`, `setWhen(end_wall)`) | -15 s · Skip · +15 s |
| Overtime | "Rest over · Set 2 of 2" | "Weighted pull-up · BW + 22.5 kg × 4" | count-up from the rest end | Skip |

* There is deliberately **no "log set" action** on the notification: logging must capture RIR (D9),
  which three notification buttons cannot do. Tapping the notification opens Live Session at the
  current set (internal route `fitapp://session/current`, 3.4; no deep links, C54).
* A challenge rule that changed the remaining sets (11.10) only changes the next-set text; the
  notification has no Undo action (ANDROID section 12.5). Undo lives on the set card.
* `setOnlyAlertOnce(true)`; the notification is updated only on state changes (the system draws the
  countdown).
* **Live Update (Android 16+).** When `canPostPromotedNotifications()` is true, the same notification
  requests promotion: `setRequestPromotedOngoing(true)`, `setOngoing(true)`, content title set,
  standard style, not colorized, no custom views, channel importance above MIN, manifest permission
  `POST_PROMOTED_NOTIFICATIONS`. The status-bar chip then shows the countdown (chronometer). If the
  user dismisses promotion or the OEM build lacks it, the standard ongoing notification is used.
  Whether the user's phone supports it is unknown until the model is confirmed (D2, Open issue
  OI-01).

#### 11.13.4 Rest-end alert

* An exact alarm at the rest end Instant: `AlarmManager.setAlarmClock` by default (exact, never
  deferred in Doze; side effect: an alarm icon in the status bar during rest), C28. The user may
  switch to `setExactAndAllowWhileIdle` (no icon) in You > Rest and alerts, but the option is offered
  only after at least 30 alarm-clock rest alarms have been logged on this device with a p95 latency
  of at most 2 s (`timer.exactIdleMinSamples` = 30, ANDROID section 12.4, C28, C54). While it is on,
  the app reverts to the alarm clock by itself if the p95 of the last 20 alarms of that mode exceeds
  2 s, and says why.
* When the alarm fires: the app vibrates the waveform `[0, 300, 150, 300, 150, 600]` ms itself with
  alarm usage (always, F20, independent of channel settings), plays the optional tone with ducking
  (6.8), and posts a heads-up notification on channel `rest_done` (IMPORTANCE_HIGH, channel sound
  and vibration disabled because the app plays them; ANDROID section 12.4) with this document's
  wording (ANDROID section 13.1 leaves the user-visible wording to section 18 and 24.6): title "Rest
  over", text "Set 2: BW + 22.5 kg × 4".
* Pre-warning at 10 s left: one short haptic tick, only while the app UI is in the foreground, driven
  by the UI's own countdown (no alarm). There is no pre-warning in the background (ANDROID section
  12.4, C54); the end alarm covers that case.
* If `canScheduleExactAlarms()` is false: inexact `setAndAllowWhileIdle`, the rest card shows "Alarm
  may be late (exact alarms off)" and Today keeps the warning row.

#### 11.13.5 Keep screen on and lock screen

* Keep screen on (F20, default on): `During session` (default) / `During rest only` / `Off`.
* "Show session on lock screen" (default on): while a session runs, the Live Session window may show
  over the lock screen (`setShowWhenLocked`), so pressing the power button after the rest alert shows
  the set card without unlocking. Turning it off requires unlocking to log.

#### 11.13.6 Adjust, skip, limits

| Action | Effect | Limits |
|---|---|---|
| -15 s | end moves 15 s earlier | at least 5 s must remain (`timer.minLeftAfterAdjustS`, ANDROID section 25.1) |
| +15 s | end moves 15 s later | total rest at most 900 s (C18 timer hard max); +15 disabled with `REJECT` beyond it |
| Long-press -15 / +15 | repeats in 60 s steps (one ±60 s adjustment per repeat, `timer.longPressStepS` = 60, with the stepper repeat timing of 6.1); taps and notification actions send ±15 s (ANDROID sections 12.3 and 25.1, C54) | same limits |
| Skip | rest ends now, next set card shows | logged with remaining seconds |
| Logging or starting the next set during rest | rest ends as "next set early" | |
| Undo of the set that started the rest | timer cancelled, not logged as a rest | |

All adjustments are applied to the persisted absolute end time and re-schedule the alarm and the
notification.

#### 11.13.7 Robustness

* State is stored as timestamps, never as a counter: `{timer_id, set_id, planned_s, start_wall_ms,
  start_elapsed_ms, boot_count, end_wall_ms, adjustments[]}`, written before the UI updates.
* Remaining time = `start_elapsed + (end_wall - start_wall) - now_elapsed` while the boot count is
  unchanged, otherwise `end_wall - now_wall` (tech_stack RestTimerScheduler).
* On process restart: a timer whose end has passed is shown as overtime and closed as
  `ELAPSED_WHILE_DEAD` if its alarm never fired; a future end is re-scheduled.
* All arithmetic uses epoch milliseconds and `elapsedRealtime`; local wall-clock time is used only for
  display. A rest that spans the DST change on 2026-10-25 (F18) shows the correct remaining time
  (golden test GF-UX-08).

#### 11.13.8 Logged rest data

Column names are the DATA_MODEL section 9.6.4 `set_logs` names (C55); the values are written by the
rest-timer reducer of ANDROID section 12.3 (C54) when the next set is logged:

* `rest_timer_planned_s` = the prescribed duration of the rest that preceded this set (after clamping,
  before user adjustments), `rest_adjust_s` = the net sum of adjustments, `rest_timer_overridden` = any
  adjustment or skip happened (a pair that nets to 0 still counts), `rest_end_reason` = how the rest
  ended (DATA_MODEL E44: `elapsed`, `skipped`, `next_set_early`, `elapsed_while_dead`, `none`),
  `rest_before_s` = the actual rest before this set and `rest_source` = how it was obtained (E56).
* Actual rest: the formula is `setStartEstimate` of 19.8, which this document owns (DATA_MODEL section
  9.6.4 and ANDROID section 12.3 reference it). For holds it is measured (the hold start,
  `rest_source = measured`); for dynamic sets it is estimated from the earliest of the first touch on
  the set card after the rest, the Skip tap and the log time minus the TIME-1 set-duration estimate
  (`time`, C19), never earlier than the previous set's log time (`rest_source = estimated`). The raw
  instants (`first_touch_at`, `skip_at`, `rest_end_at`, `completed_at`) are also in the `rest_ended`
  and `set_logged` payloads, so a review can evaluate another estimate without changing the stored
  column.

### 11.14 Finish and abandon

* **Finish** (header): if every exercise is done, the session finishes and the Session Summary opens.
  If exercises remain, the Finish sheet (16.13) lists them with one-tap reasons ("No time" preselected)
  and two actions: "Finish (mark as no time)" and "Keep going".
* Finishing stops the foreground service, cancels alarms, runs the post-session pipeline (L8:
  estimators, challenge `session.completed` rules, readiness, replan, snapshot) and opens the Summary.
* **Abandon** (session menu): immediate, snackbar "Session abandoned, 8 sets kept · Undo" for 10 s.
  Logged sets are kept; the session status becomes `abandoned`; Today returns. History allows
  "Reopen" on the same local date.

### 11.15 States

| State | Behaviour |
|---|---|
| Normal | as above |
| Restored after process death | opens at the current set; snackbar "Session restored. Rest timer kept running." |
| Safety interrupt | red banner under the header; items removed by safety are shown as excluded, not hidden |
| Offline | no difference (cloud-off icon in the header if changes are pending) |
| Engine unavailable mid-session | the set card falls back to "last time" values (prefill source `last_same_index`); no suggestions; a muted line "Suggestions unavailable" |
| Empty session (no plan) | the action area shows "Add your first exercise" with the Add sheet open |
| Home mini-session (`kind = home`) | same screen, title "Home · 2-arm hang", no add-on blocks, no Finish sheet (auto-finishes after the last item) |

### 11.16 Fed by

`challenge.materialize` (challenge block, modes, in-session rules via `challenge.eca`), `select` /
`order` / `rx` / `warmup` / `rest` / `time` (general block, prescriptions, rests, time), `autoreg`
(suggestions), `reason` (reason semantics), `substitute` (reorder and swaps), `elig` / `pref` /
`injury` (what may be added), `safety` (pain, exclusions), `load` + `bodyweight` (loadables, assist
kg, BW snapshot), `warnings` + `ui.warndisplay` (amber dots), `ui.prefill`, `ui.reasonchips`,
`ui.prompts` (pre-session sheet).

### 11.17 Emits

`session_started`, `session_minimised`, `session_resumed`, `session_restored`, `exercise_focused`,
`set_logged`, `set_undone`, `set_edited`, `set_deleted`, `log_double_tap_dropped`, `deviation_shown`,
`reason_selected`, `flag_toggled`, `hold_started`, `hold_stopped`, `hold_corrected`,
`suggestion_shown`, `suggestion_applied`, `suggestion_kept`, `challenge_rule_applied`,
`challenge_rule_undone`, `override_applied` ("Add a set"), `exercise_skipped`, `exercise_deferred`,
`exercise_swapped`, `swap_sheet_shown`, `exercise_added`, `exercise_removed`, `exercise_ended`,
`exercise_reordered`, `block_addon_skipped`, `warmup_item_done`, `rest_started`, `rest_adjusted`,
`rest_skipped`, `rest_prewarn`, `rest_alarm_fired`, `rest_overtime_reminder`, `rest_ended`,
`focus_mode_toggled`, `session_overrun_shown`, `pain_reported`, `rule_override`, `session_finished`,
`session_abandoned`, `session_abandon_undone`, `notification_action`. Names, categories and payload
keys are those of the DATA_MODEL section 9.8.1 registry (C39); triggers and value details in section
20.

---

## 12. Screens: Session Summary (`session.summary`)

### 12.1 Purpose

Close the session in under 20 seconds: show what was achieved, what changes next time and why, and
collect the optional post-session feedback (F15).

### 12.2 Wireframe

```
+-------------------------------------+
| Session A done                      |
| 68 min · 13 sets · Thu 8 Oct        |
| Linked to: Gym - sessie A 19:45  >  |
|                                     |
| NEW BEST                            |
| Top hold R 1.5 s (was 1 s)          |
|                                     |
| NEXT TIME                           |
| Assisted 1-arm: stays pin 40 lb (i) |
|   deload week: progression frozen   |
| Weighted pull-up: stays BW + 22.5(i)|
|   deload week: no progression       |
| 90-degree hold: stays pin 35 lb (i) |
|                                     |
| QUICK CHECK (optional)              |
| Whole session effort, 0-10          |
| [0][1][2][3][4][5]                  |
| [6][7][8][9][10]                    |
| Any joint pain?                     |
| [None][Elbow][Shoulder][Wrist]      |
| [Knee][Back][Other]                 |
| Note ...                            |
| [============== Done ==============]|
+-------------------------------------+
```

### 12.3 Content

| Section | Content | Source |
|---|---|---|
| Header | session name, duration (start to finish Instants), working sets, date | session store |
| Calendar link | event linked post hoc (same local date, +/- 3 h, C35) or "Not in your calendar"; "Change" opens a picker of today's events | `calendar.classify`, C35 |
| New best | at most 3 PRs: e1RM PR (engine-logged e1RM), rep PR at a load, hold PR per side, assistance PR (lowest effective assist for a clean rep) | `ui.progress` PR detection (19.7) |
| Next time | for each exercise whose next prescription changed, and for every challenge item: "from -> to" plus one line of reason with the rule or mode id; `(i)` opens the Why sheet | `rx` (next prescriptions), `challenge.eca` rule firings (`challenge_rule_events` after `session.completed`), `stall` |
| Quick check | sRPE 0-10 chips (Foster 2001), joint pain chips -> side + severity (Pain sheet), note | `ui.prompts` |
| Done | saves and returns to Today | |

* "Next time" shows changes only, plus a "stays" line for challenge items so the user sees that the
  program was evaluated (for example "stays: deload week"; in deload, test-pending, extra-deload and
  injury modes progression is frozen except protective rules, C47). At most 6 lines; more behind
  "Show all".
* A challenge rule change that jumps more than the generic load-jump cap is shown as decided by the
  program (D13) with the amber WarningEngine dot (WE-S2, WARN, C45) and a Why; it never blocks.
* STALL-1 actions that would change user-owned fields (sets, reps or reserve) appear as suggestion
  lines with Apply / Keep, not as decided changes (C57, D10).
* Effort and pain are optional. Pain answers other than "None" open the Pain sheet; a stop-signal kind
  starts the protocol exactly as in 11.11.
* The quick check can be edited in History for 24 h after the session (for example if the user
  prefers to rate effort 30 min later, as Foster 2001 recommends).
* The note is free text stored on the session; it is never copied into telemetry payloads.

### 12.4 States

* Post-session pipeline still running (rare, it is local and fast): "Next time" shows a small
  progress indicator; the rest of the screen is usable.
* Post-session pipeline failed: "Next-time changes will appear when ready" and an `engine_error`
  event.
* Abandoned sessions do not show a Summary.

### 12.5 Fed by and emits

Fed by `rx`, `challenge.eca`, `stall`, `ui.progress`, `ui.prompts`, `calendar.classify`. Emits
`session_summary_shown {prs, next_changes_count, pipeline_ms}`, `prompt_shown`, `prompt_answered` (srpe, joint_pain),
`prompt_skipped`, `pain_reported`, `calendar_link_changed`, `why_opened`.

---

## 13. Screens: Exercises (`exercises`)

### 13.1 Purpose

Browse and search the catalog (about 150 curated exercises plus custom ones, CAT-1), set a
preference per exercise with one tap, see how an exercise trains which muscles, its history, how it
is loaded and what can replace it, and create custom exercises.

### 13.2 Catalog list

```
+-------------------------------------+
| Exercises                           |
| [ Search exercises               ]  |
| [Muscle v][Equipment v][Pattern v]  |
| [Preference v] [x Available here]   |
|                                     |
| * Leg press          Quads Glutes M |
|   Leg extension      Quads        M |
|   Seated calf raise  Calves       M |
|   Bulgarian split sq Quads Glutes DB|
|                      Dislike        |
|   Weighted pull-up   Lats Biceps  B |
|                      OAP challenge  |
| x Smith squat        not available  |
| ...                                 |
| + Create custom exercise            |
+-------------------------------------+
| Today  Week  Exercises  Chall.  You |
+-------------------------------------+
```

* **Search**: matches English names and aliases, plus Dutch names when the phone's language is Dutch
  (D12; the placeholder then reads "Search exercises (EN / NL)"), case- and diacritic-insensitive
  (`één` = `een`). Ranking: exact name, then name prefix, then word prefix, then alias, then
  substring; ties by name. Results update 150 ms after the last keystroke. The query text is never
  logged (only its length).
* **Filters** (chips with menus): muscle group (the 18 groups of C9), equipment (barbell, dumbbell,
  machine, cable, bodyweight, assisted machine, belt), pattern (about 35 patterns grouped by family),
  preference (Favorite, Like, Neutral, Dislike, Never), "Available here" (default on; hides
  exercises whose `requires_station` is missing from the gym profile), "Custom".
* **Sort**: A-Z (default), most used (last 8 weeks), preference.
* **Row**: name; up to two primary groups (group coefficient 1.0); equipment abbreviation; a
  preference badge when not Neutral (`*` favorite, thumbs-up like, thumbs-down dislike, block icon
  never); "challenge" badge when an active challenge uses it; "not available" when the station is
  missing.
* **Empty search**: "No exercise matches 'xyz'." + "Create a custom exercise".

### 13.3 Exercise detail (`exercises.detail`)

```
+-------------------------------------+
| <  Leg press                        |
|    Beenpers · machine · pattern:    |
|    squat (machine)                  |
|  +-------+  +-------+               |
|  | front |  | back  |  muscle map   |
|  |  ##   |  |  ##   |  shaded by    |
|  +-------+  +-------+  coefficient  |
| Quads 1.0 · Glutes 0.5 · Adduct. 0.5|
|                                     |
| YOUR PREFERENCE                     |
| [Fav][Like][Neutral][Dislike][Never]|
| Neutral: no preference.             |
|                                     |
| HISTORY            See progress  >  |
|  ._.-'-._.-''  e1RM 158.7 kg        |
|  Thu 1 Oct  125 x 10 9 8            |
| LOADING                             |
|  Stack, 5 kg steps (Edit)           |
| REST  about 2:30 (lower compound)   |
| SETS AND REPS  your default (3x8-10)|
| ALTERNATIVES                        |
|  Hack squat        same muscles  >  |
|  Bulgarian split sq  disliked    >  |
+-------------------------------------+
```

| Section | Content | Source |
|---|---|---|
| Header | English name, Dutch name (only when the phone's language is Dutch, D12), equipment, pattern | `catalog` |
| Muscle map | Front and back silhouettes drawn from the 18 groups (C9) as a custom Compose ImageVector (about 22 regions, including lats, side delts and forearm flexors that off-the-shelf maps lack). Shading: group coefficient 1.0 = full primary colour, 0.75 = 70 %, 0.5 = 45 %, 0.25 = 20 %. Tapping a group shows its regions and coefficients. | `catalog` (group coefficient = max over regions, CAT-1) |
| Coefficients | Group list with values; "Show regions" expands the region-level table with evidence notes | `catalog` |
| Preference | 5-state segmented control with helper text (13.4) | `pref` (C26) |
| History | Sparkline of the SSE-1 estimate (e1RM) or, for holds, best seconds per side; the last 5 sessions | `sse`, `hold`, session store; `ui.progress` |
| Loading | Load type, equipment, increments in native units, bodyweight fraction for bodyweight exercises ("counts 64 % of bodyweight", C1), link to the assisted machine calibration | `load`, equipment profile |
| Rest | REST-1 class default ("about 2:00, upper compound") | `rest` |
| Sets and reps | The effective preference (global, class or exercise override) with "Change for this exercise" | rep preferences + `warnings` notes |
| Flags | Unilateral (L/R), timed, stop flags the logger will offer | `catalog`, challenge definitions |
| Alternatives | Up to 5 ranked substitutes with tags ("same muscles", "liked", "free equipment", "disliked") | `substitute` (SUB-1 ranking without "occupied" context) |
| Instructions | Optional text from free-exercise-db (Unlicense) where cross-referenced | `catalog` |
| Used by | Challenges that use this exercise | `challenge.cds` |

### 13.4 The 5-state preference (C26)

Segmented control, 48 dp tall, labels "Fav", "Like", "Neutral", "Dislike", "Never". The helper text
under it changes with the selection:

| State | Multiplier (Why sheet) | Helper text |
|---|---|---|
| Favorite | 1.495 | "Picked first whenever it fits the session." |
| Like | 1.15 | "Preferred over similar exercises." |
| Neutral | 1.0 | "No preference." |
| Dislike | 0.6 | "Avoided. Still used if it is the only good way to train a muscle you want to train." |
| Never | 0 | "Never suggested. You can still add it yourself. If this leaves a muscle without options, you'll see a warning." |

* A change applies immediately, without an Undo snackbar: tapping the previous state is the way back (D30).
* Setting **Never** on an exercise that is the last option with a sufficient coefficient for an
  improve or maintain group shows the WarningEngine's coverage note right away ("Adductors (improve)
  now have no exercise that trains them well. Pick another or change the goal.", ELIG-1
  COVERAGE_GAP).
* When a **disliked** exercise is used in a plan (the disliked-necessity gate of ELIG-1: no
  non-disliked option with group coefficient >= 0.75 for an improve group or >= 0.5 for a maintain
  group), the session item carries the chip "Only option for adductors" and its Why explains the gate
  (C26).
* Never-preferred exercises are never auto-used (C26), including as substitutes.
* Emits `preference_changed {exercise_id, from, to, surface: detail|list|session}`.

### 13.5 Custom exercise creation (`exercises.custom`, CAT-1)

A five-step full-screen form; every custom exercise is copied from a pattern prototype so that its
coefficients are valid from the start.

1. **Pattern**: pick one of about 35 patterns, grouped by family with plain names ("Squat (machine)",
   "Hip hinge", "Horizontal pull", "Elbow flexion", ...).
2. **Equipment**: barbell, dumbbell, machine (stack or plate-loaded), cable, bodyweight, assisted
   machine, belt. The station it needs (`requires_station`), chosen from the gym profile or "new
   station".
3. **Prototype preview**: the copied group and region coefficients, shown on the muscle map, with the
   note "Estimated from similar exercises (expert estimate). Adjust only if you know better."
4. **Details**: English name (required), Dutch name (optional), load type, unilateral yes/no, timed
   yes/no, flags to offer.
5. **Advanced (optional)**: edit region coefficients; each value is one of 0, 0.25, 0.5, 0.75, 1.0
   (C10, CAT-1 levels).

Validation on save (CAT-1): at least one region at 0.75 or more unless the exercise is tagged prehab,
skill or hang; values only from the allowed levels; a unique name. Errors are shown inline next to
the field. Saved as `custom = true`, synced like other user data. Emits `exercise_custom_created
{exercise_id, pattern, equipment, coefficients_edited}`.

### 13.6 Empty, error and offline

* No results: as in 13.2.
* Catalog newer version not yet pulled: the bundled catalog is used; You > Transparency shows the
  catalog version.
* Offline: no difference.

### 13.7 Fed by and emits

Fed by `catalog`, `pref`, `elig` (availability, unlock reasons), `substitute`, `sse`, `hold`, `load`,
`rest`, `warnings`, `ui.progress`. Emits `screen_viewed`, `catalog_searched {query_length,
results}`, `catalog_filter_changed`, `exercise_opened`, `preference_changed`,
`exercise_custom_created`, `exercise_alternatives_opened`.

---

## 14. Screens: Challenges, Challenge setup, Test-day wizard

### 14.1 Challenges library (`challenges`)

```
+-------------------------------------+
| Challenges                          |
| ACTIVE                              |
| +---------------------------------+ |
| | One-arm pull-up       Test pend.| |
| | Phase 1 of 3 · Block 1 · Wk 5/5 | |
| | Next test: Sun 11 Oct 13:00     | |
| | Gates 1 of 4 met              > | |
| +---------------------------------+ |
| AVAILABLE                           |
| (none other yet)                    |
| PAST                                |
| (none)                              |
+-------------------------------------+
```

* Active enrollments first (a card each), then available definitions (title, one-line summary,
  sessions per week, typical session length, equipment badge: "Equipment OK" or "Needs: wide bar"),
  then past ones (completed, abandoned).
* Definitions carry an evidence note on their card when relevant: "Gate thresholds are coach
  heuristics, not research" (critique; the OAP definition's provenance).
* Starting a second challenge runs the conflict check of `challenge.contract` (combined sessions per
  week, owned-muscle overlap, heavy-unilateral cap) and shows its result before setup.
* An active enrollment whose definition has a newer published version shows "Update available" on its
  card (definition update, 14.2).
* Empty: "No challenge running. Challenges are programs with goals, like the one-arm pull-up."

### 14.2 Active dashboard (`challenges.dashboard`)

```
+-------------------------------------+
| <  One-arm pull-up               :  |
|    Test pending · Phase 1 of 3      |
| (1)----(2)----(3)                   |
| Heavy pulling   Less help  Attempts |
|                                     |
| Block 1 · week 5 of 5 · deload+test |
| Next test: Sun 11 Oct 13:00         |
|   needs a clean session first:      |
|   Session A on Thu counts           |
| THIS WEEK  C v Tue · A Thu · T Sun  |
|                                     |
| GATE TO PHASE 2          0 of 4 met |
| Weighted pull-up e1RM >= +43.96 kg  |
|  +32.5  ################------      |
|  x1.10 rule +35.7 · training est.   |
|  +40.8 (+37.3 to +44.4)        (i)  |
| One-arm hang >= 20 s (both arms)    |
|  L  no valid value                  |
|  R  no valid value                  |
|  latest (not a test): L 15  R 20 s  |
| 5 s negative, no help (both)        |
|  L  not tested   R  not tested      |
| Top hold >= 8 s (both arms)         |
|  L 0.5 #-------   R 1 #-------      |
|                                     |
| [ Log test day ]  [ Report pain ]   |
| Overview | Rules | History | Tests  |
+-------------------------------------+
```

The numbers in this wireframe follow golden fixture GF-UX-10, which defines exact inputs and outputs:
the gate value comes from the 30 Aug test through the personal curve with the class prior `k` = 30,
because no historical set rows are fed to RCM-1 at cutover and the gate uses the prior until RCM-1 has
informative windows (C59).

| Part | Content | Source |
|---|---|---|
| Status and mode | Status (active / paused / completed / abandoned) and mode chip (Normal, Deload, Test pending, Extra deload, Injury) | `challenge.enrollment` |
| Phase stepper | 3 phases with names (from the definition, shown in English where the definition provides it, else the stored name) | `challenge.cds` |
| Block line | block index, week of the block, mode | `challenge.enrollment` (C31: general mesocycle week follows this) |
| Next test | date and time, with its basis: "block-relative" (F9, proposal P07 approved) or "fixed table" (declined), and any precondition ("needs a clean session first") | `challenge.plan`, `challenge.enrollment` |
| This week | the week's slots with status | `challenge.plan`, session store |
| Gates | one row per criterion of the current phase's exit gate, per arm where the metric has sides, with progress bars from 0 to the threshold. Each value states its source test date. Values from tests not valid for gates are never used for the bar; the latest non-valid observation is shown on a separate muted line with its reason. | `challenge.eca` (gate evaluation), metric observations |
| e1RM gate row (D7, C4) | Main value = the gate metric `wpu_e1rm_added` computed from the test-day 3RM through the personal rep curve (RCM-1 `k` of the weighted pull-up), shown with its `k` ("k 30, class prior" while the prior is used, C59). Next to it, labelled: the x1.10 value, the 80 % range, and the training estimate from SSE-1 (C4). | `challenge.eca` / `rcm` / `sse` (CHALLENGE_ENGINE section 10.1) |
| Threshold text | "+43.96 kg = 65 % of 70.7 kg - 2.0 kg"; thresholds are stored as a ratio of bodyweight (C32), so the text names the bodyweight used. The met / not met tick is evaluated on the test-day bodyweight; the threshold shown is the forward threshold at the current BW(t) (C62, 19.7.3) | definition params, `bodyweight` |
| Actions | "Log test day" (enabled on the test day and while the test is pending), "Report pain" | |
| Menu `:` | Pause, Resume, Proposals (P rules; decisions can be changed at any time, D8), Correct a value, Definition version (with "Update available" when a newer version is published), Abandon | `challenge.enrollment` (CHALLENGE_ENGINE sections 4.9, 4.12, 10.3) |

**Correct a value** (CHALLENGE_ENGINE section 4.9): a sheet that lists the correctable values (state
variables such as the session loads and pins, the block week, the planned test date, the phase by one
step forward or back) with their current value; the user picks one, enters the new value with a stepper
or the keypad (snapped to the loadable grid), and must tap one reason chip. An optional note stays in
the local History entry. "Save" writes `manual.override`; the injury mode and the status cannot be
changed here (use the pain protocol, pause, resume or abandon). The change appears in History with its
reason.

**Definition update** (CHALLENGE_ENGINE section 4.12): when a newer version of the definition is
published, the Challenges library card and the menu show "Update available". The preview shows the
current and next ISO week under the old and the new version side by side (loads, sets, rules) and any
new proposals; "Update" writes `definition.migrated`. Text-only patch versions apply without a preview;
a major version is never applied automatically.

A gate that is met shows a filled check and, the first time it is met, the expressive "gate
achieved" moment (4.6). A phase change shows a full-width celebration card once ("Phase 2: less
help") and an entry in History.

### 14.3 Rules tab (`challenges.rules`)

* Rules grouped by stage: Safety, Data, Structure, Progression, In-session (C21 stage order).
* Each row: plain-language text from the definition, rule id in monospace, tag `A` ("from your
  program") / `P` ("proposed, your decision") / `A+P`, and status: Active, Not approved (the
  program's original rule applies), or Waiting for your decision (inactive until approved; meanwhile
  the program's original rule applies, D8, C48). A rule switched by a proposal shows the proposal id
  ("via P01"); one proposal may switch several rules, and the decision is stored per proposal (C48).
* Tapping a rule opens its detail: full text, what it replaces, the FitExpr source behind "Show rule
  source" (monospace, read-only), how often it fired and when it last fired.
* A banner "2 proposals waiting for your decision" links to the proposal list (14.4) while any P
  item is undecided.

### 14.4 Proposal list (P rules, D8)

Each P proposal is decided individually; there is no "approve all". Per D8 (corrected wording) and
C48, a rule tagged `A` runs exactly as written unless the user approves the `P` proposal that replaces
it, and an undecided proposal is inactive. Decisions are stored per proposal id; one proposal may
switch several rules and parameters.

```
| PROPOSAL P01 (P)                    |
| Gate the weekly session-A pin       |
| decrease                            |
| The pin drops 5 lb only after a     |
| session A with completed sets, and  |
| goes back up 5 lb after two failed  |
| weeks.                              |
| If you decline: the pin drops 5 lb  |
| after every session A (10 lb after  |
| a controlled calibration negative), |
| as your program says.               |
| Why proposed: -5 lb every week adds |
| about 4.9 % one-arm load per week.  |
| Switches: prog.A.pin.weekly ->      |
|   prog.A.pin.gated                  |
| Evidence: expert opinion            |
|        [ Approve ]  [ Decline ]     |
```

* Groups: Safety, Progression, Structure, Test day (F9 block-relative test dates, F10 max reps
  moved to the end), Later phases (draft templates for phases 2 and 3).
* The list is rendered from the definition's proposals (`id`, title, rationale, `ifDeclined`,
  `supersedes`, `affects`); this document does not fix its content. The wireframe text paraphrases
  P01 of `oap.challenge.v1.json`.
* A decision is recorded per proposal id through `challenge.enrollment` (`rules.approval_changed`
  with the proposal id and the decision `approved` or `declined`, one event per proposal, 20.11; the
  format is owned by CHALLENGE_ENGINE section 4.10 and DATA_MODEL section 9.7.1, C48) and applies to
  rule evaluations after it. Emits `challenge_proposal_viewed {proposal_id}` and
  `challenge_proposal_decided {proposal_id, decision, previous?}` (telemetry) in addition to the domain
  event.

### 14.5 History tab (`challenges.history`)

A timeline of `challenge_rule_events` and enrollment events in plain language, newest first:

```
| Thu 8 Oct · Session A                |
|   Assisted pin stays 40 lb           |
|   deload week: progression frozen    |
| Tue 6 Oct · Session C                |
|   Clean session: test can go ahead   |
|   struct.cleanSession                |
| Mon 5 Oct · Week 5 started           |
|   Deload week; test pending          |
| (muted) +2.5 kg on B not applied:    |
|   grip slip (prog.gripSlip.lock)     |
```

* Applied changes show "from -> to"; suppressed proposals are shown muted with the rule that
  suppressed them (first-writer-wins, locks, injury mode, C21).
* Manual overrides, mode changes, block starts, phase changes, pauses and P decisions appear too.
* Tapping an entry opens the Why sheet with the rule bindings (identifier values the rule read).

### 14.6 Tests tab (`challenges.tests`)

| Column | Example |
|---|---|
| Date | Sun 30 Aug |
| Bodyweight | 71.0 kg |
| Weighted 3RM | +26 kg |
| Gate e1RM (personal curve) and x1.10 | computed values |
| Max reps | 18 |
| One-arm hang L / R | 3 / 8 s, flagged "old protocol, not comparable" |
| Top hold L / R | 0.5 / 1 s |
| Least assistance L / R | pin and ≈ kg (1 decimal); the 30 Aug value was recorded without an arm and is imported for both arms, with a note (C59) |
| Validity | "Valid for gates", "Not valid: not a test day", "Not valid: 3RM only" |

Rows are tappable: test detail and the recalculation report of that test (old -> new loads).

### 14.7 Report pain, pause, resume, abandon

* **Report pain** opens the Pain sheet (16.2) with this challenge's symptom kinds. The protocol is
  enforced (D10, C33) with a 10 s "Undo (wrong tap)".
* **Pause**: sheet with reason chips (Holiday, Illness, Injury, Other) and a preview of what happens
  on resume, as computed by `challenge.enrollment`: "Back within 7 days: nothing changes. 7 days or
  more: this week repeats with the same loads, and the next test waits for a clean session. 28 days
  or more: the block restarts from its starting loads (proposal, if accepted)." While paused: no week
  advance, habits muted, the dashboard is greyed with "Paused since Mon 12 Oct (holiday)" and a
  Resume button. App-written calendar events of future paused weeks are removed (D11 write-back).
* **Resume**: a sheet shows the consequence for the actual gap and a Resume button.
* **Abandon**: a confirmation dialog (one of the P6 exceptions): "Abandon One-arm pull-up? Your history
  is kept. Future calendar events the app created will be removed. You can restart later from your
  last block's loads." `[Abandon]` `[Cancel]`.
* These are domain events in `challenge_events` (pause, resume, abandon) plus telemetry
  (`challenge_pause_sheet_shown`, ...).

### 14.8 Challenge setup (`challenge.setup`)

A full-screen wizard following the definition's onboarding steps plus the user decisions D4, D7, D8
and D11. Each step saves; the enrollment is created only at the end.

| Step | Content |
|---|---|
| 1 Overview | What the challenge is, sessions per week, typical length, the evidence note, the safety summary (elbow stop signals and the 14-day protocol), the disclaimer |
| 2 Rules and parameters | Grip (supinated), completion arms (P item, decided in step 8) |
| 3 Equipment | Assisted machine calibration (unit lb, step 5, offset 6.5 kg, ratio 1.0) with the tip "To check the offset, stand on a bathroom scale on the platform at pin 0"; belt plates; wide bar (no -> typewriters excluded); dip belt; fixed neutral handle |
| 4 Starting point | Three choices: "Import my current program state" (D4), "Enter known values", "Schedule a baseline test". Import shows every value prefilled from the definition's import data and editable: phase 1, block 1, week 5, mode test pending, planned test Sun 11 Oct 13:00; loads C 20 kg, A 22.5 kg, B 27.5 kg, negatives 47.5 kg (reset 45 kg); pins A 40 lb, C 50 lb, 90-degree hold 35 lb; plank 20 s; one-arm hang 10 s; past tests with validity flags (30 Aug valid for gates, hang values "old protocol, not comparable", least assistance recorded without an arm and imported for both arms (C59); 19 Sep 3RM only, not valid; 29 Sep hang L 15 / R 20 s, not a test day, not valid). Past tests are imported as metric observations only; no historical set rows are created, so the strength gate uses the class prior `k` = 30 until the app's own logs give RCM-1 informative windows (C59; the step says so in one line). "Confirm values". |
| 5 Gate method (D7, information only) | "Your strength gate uses your personal rep curve, fitted to your weighted pull-up data and applied to the test-day 3RM. The x1.10 value and an 80 % range are shown next to it. No extra single is added to test days." |
| 6 Schedule | Preferred day and time per slot (definition defaults: C Wed 19:45, A Fri 19:45, B and Test Sun 11:00; editable), habit times (hang 17:15, plank Tue/Thu 17:30), reminders on/off |
| 7 Calendar (D11) | "The app adds challenge sessions and test days for this week and next to your primary calendar and keeps them up to date. It only edits events with its marker. Titles follow your existing style, for example 'Gym — sessie C (volume en techniek)'; if you edit an event's time, title or text, your edit wins." (F25, C37). The titles come from the definition's title template (Dutch by default for OAP); a switch "Calendar titles in English" changes the template language for this enrollment (the definition's `calendar.titleLocale` setup option, default `nl`); descriptions are always English. Lists this week's matching events; "Adopt" per event (needed once for the events created by hand) |
| 8 Proposals (D8) | The proposal list (14.4). Continue is enabled only when every proposal has a decision, so nothing is left implicit at the start (an undecided proposal would simply be inactive, C48). |
| 9 Review | The next two weeks materialized (current and next ISO week) with loads, pins, holds and rests; warnings from `challenge.contract` and `warnings` (for example "Pull, grip and elbow-flexor work stays inside challenge sessions; legs and push are allowed on any gap day, including the day before A, but not the day before B or a test", D5, D15) |
| 10 Cutover (D4) | "From now on the app is the source of truth for this program." Checklist: the Todoist session tasks will be retired (outside the app; Claude does it after your confirmation); the program page is frozen as a reference; home habits now come from this app. Checkbox "I confirm the cutover", then "Start challenge". |

Start creates `enrollment.created` (challenge_events), followed by one `rules.approval_changed` per
proposal decided in step 8 (CHALLENGE_ENGINE section 10.2), and emits `challenge_cutover_confirmed
{definition_id, version, sha256}` (telemetry). Until the cutover is confirmed the existing chat workflow stays in
use (D4); the wizard can be left and resumed. Emits `challenge_setup_step {step, duration_ms}` per
step.

### 14.9 Test-day wizard (`test.wizard`)

**Purpose.** Run the test battery in the order the definition prescribes, capture valid, comparable
values, and show the recalculation. It is a session of `kind = test` (C34) with the same foreground
service, rest timer, mini-bar and robustness as Live Session.

**Order (F10, C65).** The wizard renders the order of the definition's test template (OAP: `T1`) and
has no order of its own. For OAP that is the Todoist test-day order (tag A): weigh-in, warm-up,
weighted 3RM, max strict reps, one-arm dead hang L/R (fixed neutral handle), one-arm top hold L/R,
least assistance for one rep L/R, plus the unassisted 5 s one-arm negative per arm, which gate
criterion 3 needs and which the program requires on a test day (C65). If proposal P08 is approved,
max reps moves to the end. The negative follows least assistance in the template, whether P20 is
approved or not (P20 no longer changes the order; OAP_CHALLENGE OAP-OI-14). The negative is skipped,
with the reason shown, when an elbow trace was reported (the template's `skipIf`).

```
OVERVIEW                               STRENGTH ATTEMPTS
| Test day · Sun 11 Oct             : | | WEIGHTED 3RM (supinated) step 3/9 |
| This test counts for your gates.  | | Ramp: BW x3 [Done] +12.5 x2 [Done]|
| 1 Weigh-in                    v   | |       +20 x1 [Done]               |
| 2 Warm-up (10 min)            v   | | Attempt 1  BW + 30 kg             |
| 3 Weighted 3RM                >   | |   reps ( - ) 3 ( + )              |
| 4 Max strict reps                 | | [ Record clean ]  [ Not clean ]   |
| 5 One-arm hang L / R              | | Attempt 2  BW + 32.5 kg   next    |
| 6 One-arm top hold L / R          | | Heaviest clean 3: +30 kg          |
| 7 Least assistance L / R          | | Rest 4:00 between attempts        |
| 8 5 s negative, no help L / R     | |                                   |
| 9 Review and submit               | | [========= Next step ============]|

ASSISTANCE LADDER                      REVIEW
| LEAST ASSISTANCE · LEFT   step 7/9| | Results          vs 30 Aug        |
| Pin 50 lb  ≈ 29.2 kg assist       | | BW        70.4 kg                 |
| Attempt 2 of max 5                | | 3RM       +32.5 kg   +6.5         |
| One clean rep?                    | | Max reps  19         +1           |
| [ Record clean ]  [  Failed  ]    | | Hang      L 16 s  R 21 s          |
| Clean so far: 55 lb               | | Top hold  L 1.0  R 1.5 s          |
| Rest 3:00 between attempts        | | Least assist L 50 lb  R 45 lb     |
|                                   | | 5 s neg.  L yes  R no             |
|                                   | | Gate preview: 0 of 4 would be met |
|                                   | | [========= Submit test ==========]|
```

(Values illustrative.)

| Step type | UI | Logged |
|---|---|---|
| Validity check (step 0, overview header) | "This test counts for your gates" or, from `challenge.enrollment`, "This test will not count for gates: <reason>" with "Postpone" and "Do it anyway (not valid)" | `test_validity_shown {valid, reason}` |
| Weigh-in (required) | Keypad, kg, 0.1; "Before warming up, same scale as usual." The raw value is used for gates (C32, C62). | bodyweight observation with `context = test` |
| Warm-up | Block card (11.5), items logged with "Done" (C63) | warm-up items |
| Strength attempts (3RM) | Ramp sets as Done rows; attempts with belt-load stepper, reps achieved, "Record clean" / "Not clean"; the rest timer between attempts uses the protocol's rest; "Heaviest clean 3" is computed and shown. No single attempt is added (D7). | sets with `set_kind = test`, `rir_reported = 0` and `rir_source = protocol` (C63, DATA_MODEL E5); metric `wpu_3rm_added` |
| Max reps | Large reps stepper (64 dp) and "Record" | metric `bw_max_reps` |
| Max hold per side | Hold card in max mode (11.7.3), left then right, "Record" | metrics `oa_hang_s.L/R`, `oa_top_hold_s.L/R` |
| Assistance ladder per side | Start pin and maximum attempts from the template's protocol (with P21 approved: the A pin + 10 lb, at most 5 attempts per arm, 3 min rest); "Record clean" lowers the pin by one step after the rest; "Failed" ends that side; records the lowest clean pin with its kg (1 decimal) | metric `oa_least_assist_pin.L/R` |
| Unassisted 5 s negative per side (C65) | Negative card (11.7.5) without load or assistance, the protocol line "5 s one-arm negative without any help; the hand does not shift", optional "Count 1 per second" pacing, then "Record: controlled 5 s" / "Not controlled" for left, then right. Skipped with the reason when an elbow trace was reported. | metric `oa_neg5_unassisted.L/R` (yes / no) |
| Review | Table vs the previous valid test, deltas, gate preview, "Submit test" | `test.completed` (challenge_events) |

Test-day entries are metric observations, so they use a "Record" button instead of RIR chips, and the
3RM attempt sets are stored as sets with RIR 0 by protocol (C63).

**After submit: recalculation report.**

```
| Test saved · valid for gates        |
| GATE VALUE (personal curve, k 30,   |
|   class prior until enough data)    |
|   +39.4 kg   (x1.10 rule: +42.8)    |
|   80 % range +37.2 to +41.7         |
| NEW LOADS FROM MON 12 OCT           |
|  Session C  BW + 20   -> + 20       |
|  Session A  BW + 22.5 -> + 22.5     |
|  Session B  BW + 27.5 -> + 25       |
|  Negatives  BW + 47.5 -> + 45       |
|  Pins A 40 -> 55 lb · C 50 -> 65 lb |
|  (changes capped at 5 kg per test)  |
| Phase 2 gate: 0 of 4 met            |
| Next block starts Mon 12 Oct        |
| Next test: Sun 15 Nov (block-relat.)|
| [============== Done ==============]|
```

(Values illustrative; `k` = 30 is the class prior the gate uses until RCM-1 has informative windows,
C59.) Each line has a Why with the rule (`data.test.loads` or, with P10 approved,
`data.test.loads.curve`; `data.test.pins`; `struct.block.rollover`) and its bindings. A recalculated
load that jumps more than the generic load-jump cap is applied as the program says and carries the
amber WE-S2 note (D13, C45). Fed by `challenge.eca`, `challenge.enrollment`, `rcm`, `sse`,
`bodyweight`, `load` (belt rounding). Emits `test_step_completed {step, step_key, duration_ms,
skipped}`, `test_submitted {valid, steps_completed, steps_skipped}`, `recalc_report_shown {lines,
test_valid}`.

**Pain during the test**: the Pain sheet; a stop-signal kind ends the test (the engine decides
validity) and starts the protocol.

**Postpone** (from Today or the overview): handled by `challenge.enrollment` / `challenge.plan`; the
app rewrites its own calendar event (C36).

---

## 15. Screens: You (`you`)

### 15.1 Overview

```
+-------------------------------------+
| You                                 |
| PROGRESS                          > |
|   e1RM, holds, bodyweight, volume   |
| HISTORY                           > |
|   Week 41 · 2 sessions              |
| PREFERENCES                       > |
|   Profile, sets and reps, muscle    |
|   goals, sessions                   |
| EQUIPMENT                         > |
| CALENDARS                         > |
| REST AND ALERTS                   > |
|   ! Battery optimisation is on      |
| PROMPTS                           > |
| APPEARANCE                        > |
| TRANSPARENCY                      > |
|   engine 1.0.0 · params v1          |
| DATA AND SYNC                     > |
|   All changes uploaded              |
| ACCOUNT                           > |
+-------------------------------------+
| Today  Week  Exercises  Chall.  You |
+-------------------------------------+
```

Each row shows one status line only when there is something to say (a warning, a version, a sync
state).

### 15.2 Progress (`you.progress`)

**Purpose.** Honest progress charts that put assisted, bodyweight and weighted work on one
continuous scale, show uncertainty, and mark test days and personal bests.

```
+-------------------------------------+
| <  Progress                         |
| [ Weighted pull-up              v ] |
| [Strength] [Reps] [Holds] [Assist]  |
| Axis: (Added kg) [System kg]        |
| Range: [4 w] (12 w) [6 m] [All]     |
|  +45 |- - - - - - - - - gate +43.96 |
|  +40 |        ____----~~~~  <> test |
|  +35 |  .__--~   .  .  .            |
|  +30 |_-~ .  .                      |
|      +-------------------------     |
|       Aug      Sep      Oct         |
| e1RM +40.8 kg (80 %: +37.3 to +44.4)|
| trend +0.3 % per week · 1 new best  |
| Show as table                       |
+-------------------------------------+
```

| Element | Spec | Source |
|---|---|---|
| Exercise picker | search; default = the most-trained exercise of the last 4 weeks, or the challenge's main lift | session store |
| Metric tabs | only those that apply: Strength (e1RM), Reps at a load, Holds (seconds per side), Assistance (lowest effective assist, or one-arm load as % BW) | `ui.progress` |
| Raw points | per session, the best engine-logged per-set e1RM (C3: the UI never recomputes e1RM), drawn small at 60 % opacity | `rcm` / `sse` logged values |
| Trend line and band | the SSE-1 posterior (e1RM level) with its 80 % interval as a 12 % primary fill. There is no UI-side smoothing (C4: SSE-1 is the single general estimator; `ui.progress` as specified in ALGORITHMS_PLANNING section 19.6, C53). | `sse` |
| Test-day points | filled diamonds | metric observations |
| Personal bests | small star markers; list below the chart | `ui.progress` PR detection |
| Gate line | dashed line at the challenge threshold for the gate metric, labelled with its bodyweight basis | `challenge.eca` |
| Axis toggle | "Added kg" (default) or "System kg" (19.7) | `ui.progress` |
| Range | 4 weeks / 12 weeks (default) / 6 months / all | |
| Side toggle | L / R / both for unilateral metrics | |
| Point tooltip | date, the set that produced it, BW snapshot, values | |
| Table view | the same data as an accessible table | |

Other charts on the same screen (tabs above the picker): **Bodyweight** (weigh-ins as points, the
EWMA used for prescriptions as a line, C32; test-day weigh-ins as diamonds), **Weekly volume** (per
group, the last 12 ISO weeks, bars against the target, from `setcredit` and `targets`), **Personal
bests** (list).

Empty: "Log 2 sessions of an exercise to see a trend." Offline: no difference.

Emits `progress_viewed {exercise_id, metric, axis, range}`, `progress_axis_toggled`,
`progress_point_opened`.

### 15.3 History (`you.history`) and session detail (`you.session_detail`)

* List grouped by ISO week ("Week 41 · 2 sessions"), rows: date, session name, duration, working
  sets, badges (new best, pain, abandoned, home, test).
* Session detail: every exercise with targets vs actuals per set (load, reps or seconds, RIR, reason,
  flags, rest), pre- and post-session answers, the note, the calendar link, and a Why per exercise
  (the recommendation that prescribed it).
* Actions: edit a set (Set edit sheet), add a forgotten set, add a forgotten exercise with its sets
  (D28; it stays "not done" until a set is added), delete a set (undo), change the calendar link,
  edit the quick check (within 24 h), reopen an abandoned session (same local date), delete the
  session (undo, 10 s).
* Copy: "Edits update future recommendations." Engines rebuild from facts; no other confirmation.
* Emits `history_session_opened`, `set_edited`, `set_added_later`, `set_deleted`,
  `session_deleted`, `session_reopened`.

### 15.4 Preferences (`you.preferences`)

| Section | Content |
|---|---|
| Profile | bodyweight history (link to the Bodyweight chart, "Weigh in" button), experience, age (optional) |
| Sets and reps | the global default, class overrides, a list of per-exercise overrides; each with its evidence note from `warnings` (D10: the preference is kept) |
| Muscle goals | the same editor as onboarding (8.9) plus the resulting weekly target per group from `targets` ("Improve · 12 sets/week"; "Maintain · 4 sets/week, at least 1 hard set at normal load"; covered by challenge) |
| Sessions | default time budget without a calendar event (75), changeover subtracted from a calendar gym event (5; the event is training time, F11, C49), maximum gym visits per week (4, D5), usual training windows, supersets (F13), RIR check sets (F14), running priority (F6) |

Emits `goal_changed`, `rep_preference_changed`, `settings_changed {key, from, to}`.

### 15.5 Equipment (`you.equipment`)

The onboarding checklist (8.7) as an editable page, plus: per-machine stack steps, assisted machines
(several are allowed, each with its own calibration; if more than one exists, a session using one asks
"Which assisted machine today?" once), belt plates, the profile's version history and "Confirm".
Changes are versioned (prescriptions record the profile version). Emits `equipment_changed {key,
from, to}`, `equipment_confirmed`.

### 15.6 Calendars (`you.calendars`)

Calendar selection (8.10), the fixed write target, a list of app-written events for the current and
next week (with "Remove"), the adopt list, the list of per-event and per-title classification
overrides (C20) with "Remove override", a "This week as the app sees it" preview with the features
and score of each event (from `calendar_classified` events), and "Refresh".

### 15.7 Rest and alerts (`you.rest`)

| Setting | Values | Default |
|---|---|---|
| Rest-end vibration | always on (F20); shown as information | on |
| Rest-end tone | Off / On, with "Play test" | Off |
| Hold timer cues (beeps) | Off / On | On |
| Hold lead-in | 3 / 5 / 10 s | 5 s |
| Keep screen on | During session / During rest only / Off | During session (F20) |
| Show session on lock screen | Off / On | On |
| Rest focus mode by default | Off / On | Off |
| Alarm mode | Alarm clock (most reliable, shows an alarm icon) / Standard exact (no icon) | Alarm clock (C28); "Standard exact" enabled only after at least 30 logged alarm-clock rests with p95 latency <= 2 s, and reverted automatically when the p95 of its last 20 alarms exceeds 2 s (ANDROID section 12.4, C54) |
| Reliability | status of notifications, exact alarms, battery optimisation, activity recognition, phone-maker settings confirmation; last self-test result; alarm latency p50 / p95 / max of the last 30 alarms (ANDROID section 12.7); "Run timer test" | |

### 15.8 Prompts (`you.prompts`)

| Prompt | Default | Can be turned off | Notes |
|---|---|---|---|
| Readiness (Worse / Normal / Better) before gym sessions | on | yes | F15 |
| Elbow check (0-3) before gym sessions | on | not while an active challenge has an elbow pain rule ("Required while your challenge's elbow rule is active") | F15 |
| Soreness for muscles about to be trained | on | yes | asked only for muscles trained within 96 h |
| Session effort (0-10) after the session | on | yes | Foster 2001 |
| Joint pain after the session | on | not within 14 days of a pain report or while a challenge pain rule is active | UI-07 PAIN_GUARD_D |
| Weigh-in reminder | every 14 days (C32) | yes | |

Also shows the adaptive state ("Readiness is asked every 3rd session because it was skipped 3 times
in a row. Reset"). Emits `settings_changed`.

### 15.9 Appearance (`you.appearance`)

Theme (System / Light / Dark), dynamic colour (off), high contrast (off), pure black in dark mode
(off). Reduced motion follows the system setting (information line).

### 15.10 Transparency (`you.transparency`)

* `ENGINE_VERSION` of `:algo`; the list of modules with `id@version` and a short params hash (C30);
  the active params version and, during a session, the pinned one; catalog version; each active
  challenge definition `id@version` and short sha256.
* **Export review bundle**: a gzip-compressed JSON file (`.json.gz`, C40) for a chosen date range
  with `session_events`, `recommendations`, sessions, sets, metric observations, wellness and pain
  events, preferences,
  goals, equipment profile versions, `challenge_events`, `challenge_rule_events`, params and
  algorithm versions. Free-text notes are excluded unless "Include notes" is ticked. The file is
  handed to the Android share sheet; the user chooses where it goes (no automatic upload). Emits
  `export_completed {export_id, kind: review_bundle, rows, bytes, from, to, notes_included}`.
* "How the app decides": a short static explainer of the pipeline (safety, challenge, your
  constraints, general advice) and the three explanation layers.
* **Your personal parameters**: the active `user_algo_params` with their source (`default`, `fitted`,
  `review`, `user`) and, for review changes, the reason. Changes proposed by a Claude Code review are
  written only after the user approves the listed batch (F26); the screen shows them, it does not
  edit them.

### 15.11 Data and sync (`you.data`)

Sync state (7.1), outbox depth, last push and pull times, dead-letter rows with details, "Sync now";
the monthly data export (F4) with the last export date and a monthly reminder (default on); "Export
all data" (one gzip-compressed JSON file, `.json.gz`, C40); storage used on the phone; "Re-push all
data" (user-triggered recovery after the server lost data, for example a restored or reset project: it
queues every local row of every synced table again in dependency order; the action of ANDROID open issue
OI-25 for DATA_MODEL restore scenario R1, shown with one line that explains when to use it); "Delete local
data and sign out" (blocked while the outbox is not empty, then a confirmation dialog). Emits
`sync_now_tapped`, `export_completed {export_id, kind: manual_full | monthly_full, rows, bytes}`.

Android Auto Backup is off (D16, F21): the app's data are never in the phone's Google backup. The
screen says where the copies are instead: "Your data are on this phone and uploaded to your server
when you are online. The monthly export is your offline copy. After a reinstall, signing in restores
everything that was uploaded." This is why sign-out and local deletion are blocked while the outbox is
not empty.

### 15.12 Account (`you.account`)

Signed-in email, sign out (blocked while the outbox is not empty: "23 changes are not uploaded yet.
Connect to the internet and sync first."; then a confirmation dialog), the disclaimer, about (app
version, build, licences including Roboto Flex OFL and free-exercise-db Unlicense), "Redo setup".

---

## 16. Bottom sheets

### 16.1 Why sheet (`sheet.why`, F19 layer 2)

```
+-------------------------------------+
|            ----                     |
| Why BW + 25 kg for session B?       |
|                                     |
| Your program rule raised the load   |
| because all 6 sets of 2 were done   |
| with 2 or more in reserve.          |
|                                     |
| NUMBERS                             |
| Last B   6 x 2 @ +27.5, RIR 2 2 2 3 |
| Rule     +2.5 kg, rounded to belt   |
| BW used  70.7 kg (test day)         |
|                                     |
| RULE   prog.B.wpu (A, your program) |
| MODULE challenge.eca@1.0.0 · p 3f2a |
| EVIDENCE Program rule (coach        |
|   heuristic), not research          |
|                                     |
| [ Use suggested ] [ Use mine anyway]|
| Copy trace                          |
+-------------------------------------+
```

Sections, in order; empty sections are omitted:

1. **Title**: the question in plain words ("Why ...?").
2. **Summary**: one or two sentences, from the module's reason template.
3. **Your preference vs the app's choice** (when a preference is involved).
4. **Numbers**: the key inputs and outputs with units (from the recommendation's trace).
5. **Rule / module**: rule id with tag A / P, module `id@version`, short params hash, recommendation
   id.
6. **Evidence**: grade label (Strong evidence / Moderate evidence / Weak evidence / Expert opinion /
   Program rule (coach heuristic) / Engineering) and citations as text with links.
7. **Actions**, only where meaningful: "Use suggested" and "Use mine anyway" (warnings, D10), "Apply"
   (a suggestion).
8. **Copy trace / Export trace** (layer 3): the recommendation's trace JSON to the clipboard or the
   share sheet.

Fed by `recommendations` (trace), `warnings`, `challenge_rule_events` (bindings). Emits
`why_opened {subject_kind, subject_id, codes}` (the screen is the envelope column), `trace_exported`,
`warning_use_suggested`, `warning_use_mine_toggled`.

### 16.2 Pain sheet (`sheet.pain`)

```
+-------------------------------------+
| Where does it hurt?                 |
| [Elbow][Shoulder][Wrist][Knee]      |
| [Lower back][Hip][Neck][Other]      |
| Side   [Left] [Right] [Both]        |
| How bad?  [Mild] [Moderate] [Sharp] |
| What does it feel like? (elbow)     |
| [ Ache inside the elbow, hours      |
|   after training ]                  |
| [ Pain in the elbow crease when     |
|   straightening ]                   |
| [ Tingling in ring or little finger]|
| [ Something else ]                  |
| This app does not give medical      |
| advice.                             |
| [============= Report ==============]|
+-------------------------------------+
```

* Location chips, side, severity (Mild = 2, Moderate = 5, Sharp = 8 on the 0-10 scale used by
  REASON-1, whose stop threshold is 4; C64, params key `ui.prompts.pain_severity_map`), and symptom
  kinds when an active challenge's pain rule defines kinds for that location (OAP elbow stop signals).
* Context is filled automatically (during set X, after the session, from the Challenges tab, from the
  post-session check).
* Report writes one `PainEvent` fact (C33) that `safety` evaluates before anything else; challenge
  pain rules receive it as `pain.reported` (challenge_events). The sheet then shows what was enforced
  ("Today's remaining elbow work removed. Elbow protocol: 14 days.") with "OK" and "Undo (wrong
  tap)" for 10 s.
* Emits `pain_reported {wellness_log_id, body_region, side, severity, pain_score, symptom_codes,
  context}`, `pain_retracted {wellness_log_id, ms_since}`.

### 16.3 Readiness sheet (`sheet.readiness`, pre-session)

```
+-------------------------------------+
| Before you start (optional)         |
| How do you feel today?              |
| [ Worse ] [ Normal ] [ Better ]     |
| Elbows right now?                   |
| [0 None][1 Trace][2 Mild][3 Pain]   |
| Still sore from last time?          |
| Lats    [No] [Was, fine now] [Yes]  |
| Biceps  [No] [Was, fine now] [Yes]  |
| [============= Start ===============]|
| Skip questions                      |
+-------------------------------------+
```

* Shown items and their number come from `ui.prompts` (19.4).
* Elbow check: 0 none, 1 trace, 2 mild, 3 pain (F15). At 2 or more the sheet asks the kind (the stop
  signals). Answers go to the challenge as `readiness.checked` (challenge_events) and to `safety`.
* Soreness answers feed `readiness` (C5 check-in; the soreness-driven adaptation goes to the offline
  tuner, not the live model).
* Start is always enabled; unanswered items are logged as skipped.

### 16.4 Skip sheet (`sheet.skip`)

One tap on a reason completes the skip. Chip order for skips comes from `ui.reasonchips`
(ALGORITHMS_PLANNING section 19.2): No time, Occupied, Pain, Feeling bad, Too tired, Equipment missing
or broken, Other. "Occupied" switches the
sheet to the Do later / Swap content (11.12). "Pain" switches to the Pain sheet content. "Other" offers
an optional free-text field (never required). Emits `exercise_skipped {reason_code, chip_position,
sets_done}`.

### 16.5 Swap sheet (`sheet.swap`)

```
| Replace leg press (occupied)        |
| Hack squat     same muscles · free >|
|   start 80 kg x 10, aims 4 in reserve|
| Leg extension + leg curl  liked    >|
| Bulgarian split squat  disliked:    |
|   closest option                  > |
| Search catalog                    > |
| Skip instead                        |
```

* Up to 3 ranked candidates from `substitute` (SUB-1): similarity at least 0.6; disliked candidates
  only if no non-disliked candidate qualifies (labelled); Never-preferred candidates never appear.
* Each shows tags and the starting prescription: the user's own history, or a calibration first set
  aimed at target RIR + 2 when there is no history.
* Emits `swap_sheet_shown {from_exercise_id, candidates, trigger_reason}`, `exercise_swapped
  {from_exercise_id, to_exercise_id, rank?, similarity?, via: list|search, reason_code?}`.

### 16.6 Add exercise sheet (`sheet.add_exercise`)

Search field; "Suggested for what's left this week" (exercises that cover the week's remaining
deficits, from `select`); "Recent"; then the catalog. Each result shows availability and, if a hard
rule forbids it today, a red "Not today" label with the reason (adding needs "Add anyway", logged as
`rule_override`). Added exercises join the end of the general block, or "after the current exercise"
by a toggle. Emits `exercise_added {exercise_id, source, position, block_kind, rule_override}`.

### 16.7 Keypad sheet (`sheet.keypad`)

```
| Load for weighted pull-up           |
|               BW + 23 kg            |
| Loadable: [ 22.5 ]  [ 25 ]          |
| [ 1 ][ 2 ][ 3 ]                     |
| [ 4 ][ 5 ][ 6 ]                     |
| [ 7 ][ 8 ][ 9 ]                     |
| [ . ][ 0 ][ <- ]                    |
| [ Use target ]  [====== Set =======]|
```

* 64 dp keys, a decimal-point key (a comma typed on a hardware keyboard becomes a point), the unit
  label, chips with the nearest lower and higher loadable values, "Use target".
* "Set" snaps to the nearest loadable value; ties go to the easier load (lower external load or more
  assistance, C2) and play `REJECT` if snapping changed the value, with a line "Set to 22.5 kg
  (nearest you can load)".
* Out-of-range values show an inline error and a `REJECT` haptic.
* Emits `keypad_used {field, typed, snapped_to, changed}` (counted in `set_logged.input_method` too).

### 16.8 Weigh-in sheet (`sheet.weigh_in`)

Keypad in kg with 0.1 step, prefilled with the last weigh-in, "Save". Context: routine / test day.
Copy: "Weigh yourself at a similar time of day, on the same scale." Writes a bodyweight observation;
`bodyweight` recomputes the EWMA (C32). Emits `prompt_answered {prompt: weigh_in}`.

### 16.9 Template picker sheet (`sheet.template`)

See 9.5. Sections: ranked options, challenge slots (with status), family combinations, empty
session. Emits `template_picked {kind, slot_or_type, rank}`.

### 16.10 Day sheet (`sheet.day`)

See 10.9.

### 16.11 Calendar event sheet (`sheet.cal_event`)

```
| Gym — sessie A (één arm zwaar)      |
| Thu 8 Oct 19:45-21:15 · primary     |
| The app reads this as: OAP A (0.9)  |
| This is:                            |
| [OAP C][OAP A][OAP B][OAP test]     |
| [Gym: push][pull][legs][other]      |
| [Run] [Not a gym session]           |
| Apply to: (this event) [same title] |
| [ Adopt (let the app update it) ]   |
```

* Shows the event, the current classification and score (C20), correction choices, the scope of the
  override (this event, or all events with the same title fingerprint), and Adopt for unmarked
  challenge events.
* Emits `calendar_correction {instance_key, from, to, scope}`, `calendar_event_adopted`.

### 16.12 Warnings list sheet (`sheet.notes`)

All current warnings for the screen's scope, sorted like `ui.warndisplay`; each row expands to its
Why; "Dismiss" per row (snooze rules in 19.6). Opened from the "N more notes" row.

### 16.13 Finish sheet (`sheet.finish`)

```
| Finish session?                     |
| 2 exercises not done:               |
| Leg press        [No time] [Other]  |
| Calf raise       [No time] [Other]  |
| [==== Finish (mark as no time) ====]|
| Keep going                          |
```

### 16.14 Set edit sheet (`sheet.set_edit`)

The logged values with steppers; RIR chips that select (not log) the value; reason chips; flags; for
holds, seconds and felt chips; "Delete set" (undo); "Save". Emits `set_edited {field, from, to}` per
changed field.

### 16.15 Session note sheet (`sheet.session_note`)

A text field for the session's note (also reachable from the Summary). Stored on the session; never
copied into telemetry.

---

## 17. Warnings UI (UI-10 display policy)

There is one WarningEngine (`warnings`, C14). It produces violations with a rule id, a severity
(`INFO`, `WARN`, `STRONG`, `SAFETY`), an entity (muscle group, exercise, preference, session), a
magnitude (0-1, relative distance from the evidence bound), an evidence grade, citations, the numbers
and, where possible, a suggested alternative. `ui.warndisplay` (19.6) decides where each one appears.

| Surface | What | Rules |
|---|---|---|
| Banner | One per screen, below the header (after the safety banner, which is separate) | The highest-ranked visible warning; actions: Why, "Use suggested" (STRONG only, or when an alternative exists), dismiss |
| "N more notes" row | under the banner | opens the Warnings list sheet |
| Inline dot | an amber dot next to the affected value (a target, a goal row, a volume bar, a preference control) | always for WARN and STRONG on that entity; INFO uses a muted dot |
| "Your choice" marker | a small text "your choice" next to the value | when the user tapped "Use mine anyway" for this rule and entity |
| Why sheet | full explanation | from any banner, dot or marker |

Severity styling:

| Severity | Colour | Banner eligible | Dismissable | Example (C14 thresholds) |
|---|---|---|---|---|
| SAFETY | safety red | always, persistent until acknowledged | acknowledge only | "0 in reserve logged on a weighted pull-up: the plan keeps at least 1 in reserve on this lift" |
| STRONG | advisory amber | yes (magnitude rule) | yes, snoozed | "Lats (improve): about 2 hard sets per week planned. Below 3 per week gives little growth." |
| WARN | advisory amber | yes (magnitude rule) | yes, snoozed | "Quads (improve): 5 sets per week planned; fewer than 6 is well below the growth range." |
| INFO | muted | no (dot and list only) | yes | "Chest: 12 sets in one session; above about 11 per session adds little (preliminary evidence)." |

Example banner (non-blocking):

```
| ! Lats (improve): 2 sets per week   |
|   planned. Below about 3 per week   |
|   gives little growth.              |
|   [Why] [Use suggested] [Dismiss]   |
```

Copy rules for warnings: state the number, the bound and the consequence; name the evidence level in
the Why sheet; never say "you must". Preprint-based thresholds (per-session caps, the +20 % ramp cap)
are labelled "preliminary evidence" (C29).

---

## 18. Notifications and reminders catalogue

ANDROID is authoritative for notification behaviour, channel ids, importances, notification ids and
scheduling (C54; ANDROID sections 11.5, 12.4, 13 and 17); this section owns the catalogue ids, the
timing rules of the reminders and the user-visible wording (ANDROID section 13.1 leaves the wording to
this section and 24.6). The channel ids are those of ANDROID section 13.1: `session_ongoing`,
`rest_done`, `habit_reminders`, `challenge`, `safety`, `app_status`; they are final and cannot be
renamed after the first install.

| Id | Channel (importance) | When | Title / text | Actions | Tap opens (route, 3.4) |
|---|---|---|---|---|---|
| `N-SESSION` | `session_ongoing` (LOW, silent) | whole session (foreground service) | see 11.13.3 (ANDROID section 13.3) | -15 s, Skip, +15 s (during rest) | Live Session (`fitapp://session/current`) |
| `N-REST-DONE` | `rest_done` (HIGH; channel sound and vibration off, the app vibrates and plays the tone itself) | rest end | "Rest over" · "Set 2: BW + 22.5 kg × 4" (24.6) | none | Live Session (`fitapp://session/current`) |
| `N-IDLE` | `app_status` (LOW) | no set, rest or exercise event for 60 min during a session (`session.idlePromptMin`, ANDROID section 11.5); after 240 min (`session.idleStopFgsMin`) the service stops itself and the session stays in progress | "Session C still running. Finish it?" | Finish, Keep open | Live Session (`fitapp://session/current`) |
| `N-HABIT` | `habit_reminders` (DEFAULT) | habit time (17:15 hang, 17:30 plank on Tue/Thu), skipped on days the definition skips; only after the D4 cutover | "Two-arm hang" · "4-5 sets, 2-3 min total" (the deload prescription when halved) | Start, Snooze 30 min, Skip today (never "Done": completion is logged only through the session, ANDROID section 17) | home mini-session (`fitapp://habit/<habit_id>`) |
| `N-WEIGH` | `habit_reminders` (DEFAULT) | morning after the weigh-in becomes 14 days stale, at most once per week | "Time to weigh in (last: 70.7 kg, 15 days ago)" | Weigh in | Today (`fitapp://today`); its weigh-in row opens the Weigh-in sheet |
| `N-TEST-DAY` | `challenge` (DEFAULT) | 08:00 on a test day | "Test day today 13:00 · weigh in before warming up" | Open | Today (`fitapp://today`) |
| `N-PROGRAM` | `challenge` (DEFAULT) | after a rule changes future loads outside a session (test recalculation, protocol exit), deduplicated per message per day (C21) | "New loads from Monday after your test" | Open | Challenges dashboard, History tab (`fitapp://challenge/<enrollment_id>`) |
| `N-SAFETY` | `safety` (HIGH) | rule notifications of kind `stop` or `warn` (protocol day 14 exit or extension) | "Elbow protocol: 3 pain-free days needed before you go back. See a physio if it persists. Not medical advice." | Open | Challenges dashboard (`fitapp://challenge/<enrollment_id>`) |
| `N-CELEBRATE` | `challenge` (DEFAULT) | phase gate passed | "Phase 2 unlocked: less help" | Open | Challenges dashboard (`fitapp://challenge/<enrollment_id>`) |
| `N-EXPORT` | `app_status` (LOW) | monthly, if no export in 30 days (`export.reminderDays`, F4) | "Monthly backup: export your data" | Export | You > Data and sync (`fitapp://you/data`) |
| `N-SYNC` | `app_status` (LOW) | dead-letter rows, or no sync for 7 days | "3 changes could not be uploaded" | Open | You > Data and sync (`fitapp://you/data`) |

* Habit reminders use `setExactAndAllowWhileIdle` with a `setWindow` 10 min fallback, scheduled 48 h
  ahead (ANDROID section 17); the weigh-in and test-day notifications use the same reminder path. Only
  the rest timer uses `setAlarmClock`.
* Taps and actions open the app through internal route names (3.4), never deep links (C54).
* All channels can be changed by the user in Android settings, except that the rest-end vibration is
  played by the app itself (F20).
* Every notification emits `notification_posted {notification_id, channel, latency_ms?}` (the latency
  for scheduled reminders: delivery time minus due time); actions and taps emit `notification_action
  {notification_id, channel, action}` / `notification_opened {notification_id, channel}`, and a
  dismissed session notification emits `notification_dismissed`. The idle prompt also emits
  `session_idle_prompted {idle_min, action}` (`posted`, then `finish` or `keep_open`); scheduling the
  reminder horizon emits `reminder_scheduled {kind, count, horizon_h}` (DATA_MODEL section 9.8.1
  registry, payloads owned by ANDROID sections 11.5, 13 and 17).

---

## 19. Deterministic UI functions and platform controllers

The seven pure UI-side modules of the registry (`ui.todaycard`, `ui.prefill`, `ui.reasonchips`,
`ui.prompts`, `ui.volumebars`, `ui.warndisplay`, `ui.progress`) are **specified in
`docs/ALGORITHMS_PLANNING.md` section 19** (C53): their inputs, outputs, decision logic and unit tests
are defined there, and their constants are registered in ALGORITHMS_PLANNING section 23.19 under lower
snake_case keys (C41). This section does not restate that logic. It adds only display details: how
each output is rendered on the screens of sections 8 to 17, the English copy templates, the display
requirements a screen depends on, what is logged about the rendered result, and the golden fixtures of
section 25 that check the rendering. ALGORITHMS_PLANNING section 19 itself names what it leaves to this
document: the copy templates, titles, start labels, preview lines, the week strip, the secondary
actions, chart ranges and opacities, the personal-best list of the Session Summary and the layout of
the gate rows. The behaviours the screens once requested from the owner (hold prefill step, right-side
cap, post-log RIR deviation, hold ranges, no history across load modes, the `substitute` context, the
elbow follow-up tap outside the tap budget, and the personal-best rules) are adopted in
ALGORITHMS_PLANNING section 19 by C69; the subsections below only reference them.

The three platform controllers that the screens depend on (`RestTimerController`,
`HoldTimerController`, `TelemetryWriter`) are described in 19.8 to 19.10 as far as the user sees them
and as far as they log; their mechanics and constants are owned by `docs/ANDROID.md` (sections 12 to 14
and 25, C54). The ui modules live in `:app` (package `dev.fitnessapp.app.uidet`, section 27) but are
written exactly like `:algo` code.

| Module | Specification (owner, C53) | Registered constants (ALGORITHMS_PLANNING 23.19) | Display details | Used on |
|---|---|---|---|---|
| `ui.todaycard` | ALGORITHMS_PLANNING 19.1, with the `today` verdict of its section 9 and the reason codes of its section 0.6 (C53) | `ui.todaycard.close_rel`, `max_chips`, `max_alts` | 19.1 | Today (9), template picker (16.9), week strip (9.7) |
| `ui.prefill` | ALGORITHMS_PLANNING 19.7 | `ui.prefill.hold_delay_ms`, `repeat_ms`, `accel_after`, `accel_factor`, `hold_short_frac`, `post_rir_delta` (C69) | 19.2 | set card (11.6), keypad (16.7), Set edit (16.14) |
| `ui.reasonchips` | ALGORITHMS_PLANNING 19.2 | `ui.reasonchips.max_visible`, `max_defers` | 19.3 | set card (11.8), Skip (16.4) and Finish (16.13) sheets |
| `ui.prompts` | ALGORITHMS_PLANNING 19.3 | `ui.prompts.sore_window_h`, `sore_max`, `tap_budget`, `skip_streak`, `reduced_every`, `restore_after`, `pain_guard_d`, `pain_severity_map` | 19.4 | Readiness sheet (16.3), Summary quick check (12.2), Pain sheet (16.2) |
| `ui.volumebars` | ALGORITHMS_PLANNING 19.4 | `ui.volumebars.bar_cap` | 19.5 | Week (10.8), Progress weekly volume (15.2) |
| `ui.warndisplay` | ALGORITHMS_PLANNING 19.5 | `ui.warndisplay.min_magnitude`, `snooze_d`, `worsen_factor`, `max_banners` | 19.6 | banners, dots and the Warnings list (17, 16.12) |
| `ui.progress` | ALGORITHMS_PLANNING 19.6; gate evaluation CHALLENGE_ENGINE section 10.1 (C62) | `ui.progress.pr_min_gain`, `window_weeks`, `hold_pr_min_gain_s`, `pr_min_history` (C69) | 19.7 | Progress (15.2), Summary bests (12.3), gate rows (14.2) |

Display constants that this section owns (layout values, list lengths, opacities) have params keys under
the same module prefix (for example `ui.todaycard.preview_max_lines`) and are listed in the constants
paragraph of each module below; they never change a training decision.

### 19.0 Rules shared by all ui modules

These are the properties the screens and the telemetry rely on. They agree with the introduction of
ALGORITHMS_PLANNING section 19 (pure Kotlin, injected `now`, engine outputs only); the precedence classes
below are the numbering that ALGORITHMS_PLANNING section 0.6 uses for its reason codes.

| Rule | Detail | Grade |
|---|---|---|
| Purity | No `android.*`, no system clock, no randomness, no IO, no `HashMap` iteration order. `now: Instant` and `zone: ZoneId` are parameters. The `:algo` purity test (ANDROID.md 6.3) also scans `dev.fitnessapp.app.uidet`. | engineering-fact (C22, C31) |
| Inputs are engine outputs | A ui module never recomputes what an engine module owns: it never computes e1RM (C3), set credit (C6), readiness (C5), targets (C14), rest durations (C18), time estimates (C19) or warnings (C14). It only selects, orders, formats and lays out. | decision (C3, C6, C14, C18, C19) |
| Stable ordering | Every sort has an explicit final tie-break on a stable id (option id, exercise id, rule id, the group order of C9). | engineering-fact |
| Numbers | Every number is on the canonical grid before comparison or hashing: kg 0.01, s 0.1, ratio 1e-4, reps 0.01, HALF_EVEN (C22, C42). Display rounding follows section 5 (for example assist kg with 1 decimal, C54) and is applied only at the last step. | engineering-fact (C22, C42) |
| Versioning and keys | Each module is `ui.<name>@1.0.0`. Its constants live in the versioned params JSON under `<module>.<constant>` with a lower snake_case constant name (C41, DATA_MODEL E53), for example `ui.todaycard.close_rel`; pseudo-code and Kotlin may write the same constant in upper case (`CLOSE_REL`). Pure layout values that never change what is decided or logged as a decision (chip widths, gaps) are display constants of `:app` (`InteractionConfig`), named where they are used. | decision (C30, C41) |
| Result envelope | Every module returns `UiResult<T>(value, moduleId, moduleVersion, inputsHash)`. `inputsHash` is SHA-256 over the RFC 8785 JCS form of the module's input (C24). | engineering-fact |
| No snapshot rows | ui modules do not write `recommendations` rows (they would multiply the table without adding information). Instead, the telemetry event that records what was shown (for example `today_card_shown`) carries `module_id`, `module_version`, `inputs_hash` and the ids of the engine recommendations it was built from. `:sim replay` rebuilds the input from those recommendation snapshots and the timestamped facts and must reproduce the logged output. | decision (C24, C30) |
| Precedence | Whenever ui modules rank messages of different origin they use the pipeline precedence safety > challenge > user hard constraint > general (P11). The precedence class is a property of the reason or warning code (ALGORITHMS_PLANNING section 0.6), not something the ui module guesses. | decision |
| Params pinning | During a session the params pinned at session start are used (ANDROID.md 7.6), so a chip order cannot change in the middle of a session. | engineering-fact |

Precedence classes: `SAFETY = 0`, `CHALLENGE = 1`, `USER = 2` (the user's own hard constraints:
calendar plans, maximum visits, time budget), `GENERAL = 3`.

### 19.1 `ui.todaycard` (UI-01): Today card display

**Owner.** ALGORITHMS_PLANNING section 19.1 specifies the composer, its section 9 specifies `today`
(the verdict and the ranked options) and its section 0.6 the reason-code enum with typed parameters and
precedence classes (C53). The card states, titles and actions are section 9.3 of this document.

**What the screen relies on (C53).**

1. `today` outputs a verdict `TRAIN`, `REST_SOFT` or `REST_HARD` with typed reason codes. The card shows
   that verdict and never re-thresholds it (the research lens's own `REST_SCORE` is dropped).
2. OPTIONAL is CLOSE when the best other option's score is at least `(1 - ui.todaycard.close_rel)` times
   the primary option's score, with `close_rel` = 0.10; otherwise CLEAR. The test is relative because `today` scores are
   unnormalised utilities. GF-UX-01 fixes the boundary (1.6560 is CLOSE, 1.6559 is CLEAR against 1.8400).
3. At most `ui.todaycard.max_chips` = 3 reason chips (F19 layer 1) and `ui.todaycard.max_alts` = 3
   alternatives.
4. The primary option is the engine's choice (`primaryOptionId` of `today`); the card does not re-sort the
   options. Planned events, the DONE state, the test card that ignores a hard-rest verdict (C36) and the
   soft and hard rest variants follow the owner's composer.

**Display details.**

* State to title, primary button and secondary actions: the table of 9.3 (normative for the copy).
* Chip order on screen is the owner's: precedence class (SAFETY, CHALLENGE, USER, GENERAL), then the
  absolute weight, then the code; one chip per code and subject. GF-UX-25 checks it (a SAFETY chip with
  weight 0.10 beats a GENERAL chip with weight 0.90); the wireframes of section 9 show chips in an
  illustrative order.
* "Not today" lines (OPTIONAL and REST cards): the excluded families with the rule or protected window that
  excludes them, at most `not_today_max` lines, ordered by precedence class, then family order.
* Option preview: the first `preview_max_lines` exercise lines of the primary option plus "..." when more
  follow.
* DONE card: "Next: <date>, <slot> <time>" from `weekplan` when the next fixed session lies within
  `next_lookahead_days`.
* Selecting another option row in the OPTIONAL CLOSE card moves the Start target to that option. This is
  a UI state change logged as `today_action {action: select_alt, rank}`; it does not re-run the module.
* A reason code that this document has no template for (19.1.1) is rendered as `{code}` in muted style
  and listed in `today_card_shown.unknown_codes`, so the app keeps working when the engine is newer than
  the strings.
* Consequences of the owner's composer that the user sees: with two planned events today the card shows
  the first one not yet linked to a finished session, and after it is done PLANNED for the second (a
  session finished today plus a later planned event shows PLANNED, not DONE); a planned event whose end
  has passed without a session stays PLANNED until local midnight, so the user can still log late; a
  safety state on a test day is shown by the safety banner and the wizard's validity step, not by hiding
  the test.
* A TEST card shows no budget chips (the test is a fixed protocol).

#### 19.1.1 Reason-chip templates

Codes, their typed parameters and their precedence classes are the versioned enum `today.reason_codes`
of ALGORITHMS_PLANNING section 0.6 (C53). The English templates below are the normative display strings
(ALGORITHMS_PLANNING section 0.6 marks its own templates as indicative and refers here). Templates are
versioned app strings keyed by code (section 24); `{...}` parameters are formatted with the rules of
section 5. Chips are short; longer explanations live in the Why sheet.

| Code | Class | Template | Example |
|---|---|---|---|
| `SAFETY_PROTOCOL` | SAFETY | `{protocol} · day {day} of {days}` | "Elbow protocol · day 3 of 14" |
| `SAFETY_JOINT` | SAFETY | `{joint} rests today` | "Elbow rests today" |
| `REST_BEFORE` | CHALLENGE | `Rest before {slot} {when}` | "Rest before test tomorrow" |
| `PROTECT_REST` | CHALLENGE | `{family} rests for {slot} {when}` | "Pull rests for A tomorrow" |
| `GAP_DAY` | CHALLENGE | `Gap day next to {slot}` | "Gap day next to A" |
| `CHALLENGE_DUE` | CHALLENGE | `{slot} due ({done}/{total} this week)` | "A due (1/3 this week)" |
| `NEXT_FIXED` | CHALLENGE | `{slotShort} {weekday} {time}` | "Test Sun 13:00" |
| `DELOAD_WEEK` | CHALLENGE when synced to a challenge block (F8), else GENERAL | `Deload week` | "Deload week" |
| `COVERED_BY_CHALLENGE` | CHALLENGE | `{group} covered by {challenge}` | "Lats covered by One-arm pull-up" |
| `RUN_ON_REST_DAY` | CHALLENGE | `Run before {slot}: take it easy` | "Run before test: take it easy" |
| `PLANNED_CAL` | USER | `Planned {time}` | "Planned 19:45" |
| `MAX_VISITS` | USER | `{used} of {max} gym visits used` | "4 of 4 gym visits used" |
| `BUDGET_FIT` | USER | `Fits {minutes} min` | "Fits 60 min" |
| `NO_SLOT` | USER | `No free slot ({reason})` | "No free slot (busy evening)" |
| `TIME_SHORT` | USER | `Shortened to fit {budget} min` | "Shortened to fit 60 min" |
| `VOLUME_DEFICIT` | GENERAL | `{group} {done}/{target} wk` | "Quads 0/6 wk" (the `after` parameter is shown in the Why sheet) |
| `RECOVERED` | GENERAL | `{family} {pct} % recovered` | "Legs 100 % recovered" |
| `RECOVERING` | GENERAL | `{group} still recovering ({pct} %)` | "Quads still recovering (70 %)" |
| `HOURS_SINCE` | GENERAL | `{subject} {hours} h ago` | "C 48 h ago" |
| `REENTRY` | GENERAL | `First {family} in {days} days` | "First legs in 23 days" |
| `BETTER_OTHER_DAY` | GENERAL | `{type} fits better on {day}` | "Legs fits better on Friday" |
| `BETTER_THIS_DAY` | GENERAL | `Better today than {day}` | "Better today than Friday" |
| `SORE` | GENERAL | `{group} still sore` | "Quads still sore" |
| `LOW_SYSTEMIC` | GENERAL | `Whole-body readiness {pct} %` | "Whole-body readiness 85 %" |
| `HEAVY_LOWER_GAP` | GENERAL | `Heavy legs {hours} h ago` | "Heavy legs 30 h ago" |
| `RUN_NEAR` | GENERAL | `Run {when}` | "Run tomorrow 08:00" |
| `CONSECUTIVE` | GENERAL | `{days} days in a row` | "4 days in a row" |
| `TARGETS_MET` | GENERAL | `{group} target reached` | "Chest target reached" |
| `ONLY_OPTION` | GENERAL | `Only option for {group}` | "Only option for adductors" |
| `PREF_LIKED` | GENERAL | `You like {exercise}` | "You like leg press" |
| `FREQ_BONUS` | GENERAL | `{group}: 2nd session this week` | "Quads: 2nd session this week" |
| `ADDON` | GENERAL | `{family} add-on after {slot} (~{minutes} min)` | "Legs add-on after A (~15 min)" |
| `NOT_NEEDED` | GENERAL | `Targets on track` | "Targets on track" |
| `PAST_DAY` | GENERAL | `Past` | "Past" |

`{when}` is "today", "tomorrow" or the weekday name for dates within the next 6 days, else the date
(`Wed 14 Oct`). Percentages are integers with a space before the sign (section 5).

#### 19.1.2 Time-budget chips (F11)

The budget value is decided by F11 and C49 (a calendar gym event is training time; budget = event
duration - 5 min changeover; without an event the user's default of 75 min); trimming to the budget is
`time` (TIME-1, C19). The chip row is a display rule of this document:

```
fun budgetChips(e: ClassifiedEvent?, b: BudgetContext): BudgetChips
  standard = [45, 60, 75, NO_LIMIT]                                   # F11; NO_LIMIT is the "90+" chip
  if e != null and e.durationMin != null:
      cal = floorTo(e.durationMin - b.changeoverMin, budget_round_min)   # changeover 5 min (F11, C49)
      cal = max(cal, budget_min_chip)
      if cal >= 90:            chips = standard;       calChip = NO_LIMIT   # labelled "90+ cal"
      elif cal in standard:    chips = standard;       calChip = cal        # an 80-min event: "75 cal"
      else:                    chips = [cal] + standard; calChip = cal      # a 90-min event: "85 cal", shown first
      selected = b.userSelected ?: calChip; source = if b.userSelected != null then chip else calendar
  else:
      chips = standard
      selected = b.userSelected ?: b.defaultBudgetMin                # 75 by default (F11)
      source = if b.userSelected != null then chip else default
```

`b.userSelected` survives recomposition and process death (saved state) and is cleared at local
midnight. Selecting a chip re-runs session build (L6) for the selected option with that budget (`select`
then `time`, C19) and logs `time_budget_selected`. The ui module never trims anything itself (C19).

#### 19.1.3 Week strip

```
fun weekStrip(in): List<StripCell>                  # Monday..Sunday of the ISO week of in.localDate (F18, C31)
  for d in isoWeekDays(in.localDate):
     sym = when {
        doneOn(d)          -> DONE         # "v": a finished gym or test session on d
        conflictOn(d)      -> CONFLICT     # "!": calendar slot type contradicts challenge state (C36)
        testOn(d)          -> TEST         # "T"
        hardBlocked(d)     -> BLOCKED      # "x": weekplan rest day caused by a hard rule
        plannedOn(d)       -> PLANNED      # "*": a gym event in the calendar
        recommendedOn(d)   -> RECOMMENDED  # "o": weekplan recommends a session that is not in the calendar
        else               -> REST }       # "-"
     cell = StripCell(d, sym, isToday = d == in.localDate, a11yLabel = label(d, sym))
```

The precedence of the symbols is the order of the `when` branches; the day kinds themselves come from
`weekplan`. A day that is both done and a test day shows `v`, with the accessibility label "Sunday 11
October, test day, done". The day before session A is an ordinary gap day (D15, C50): it shows `o` or
`*` when a legs or push session is recommended or planned there; session A alone never makes it `x`.

#### 19.1.4 Constants

Registered in ALGORITHMS_PLANNING section 23.19 (owner): `ui.todaycard.close_rel` 0.10 (C53),
`ui.todaycard.max_chips` 3, `ui.todaycard.max_alts` 3. Display constants owned by this document (the
preview lines, the "Not today" lines, the DONE line and the budget chip row are display details, see the
owner's section 19.1):

| Key | Default | Bounds | Evidence | Tunable, tuned by |
|---|---|---|---|---|
| `ui.todaycard.preview_max_lines` | 3 | 1-5 | expert-opinion (card height at font scale 2.0) | Yes: rate of option-preview opens (`today_action.action = preview`) |
| `ui.todaycard.not_today_max` | 2 | 0-3 | expert-opinion | Yes: Why opens on exclusion lines |
| `ui.todaycard.next_lookahead_days` | 7 | 1-14 | expert-opinion | No data needed |
| `ui.todaycard.budget_round_min` | 5 | fixed | engineering-fact (display grid) | No |
| `ui.todaycard.budget_min_chip` | 20 | 10-45 | assumption | Yes: sessions started with a calendar chip below 30 min |
| Standard budget chips | 45 / 60 / 75 / 90+ | fixed | decision | F11; not tunable |
| Changeover, default budget | 5 min, 75 min | user settings | decision | F11, C49; user setting |

**Logged data.** `today_card_shown` (registry payload: state, verdict, variant, options with scores
rounded to 1e-4, chip codes and classes, confidence, primary option id, calendar instance keys, unknown
codes, `inputs_hash`; the envelope carries `module_id = ui.todaycard` and the `recommendation_id` of the
`today.options` snapshot), `today_action` (action, rank, milliseconds since the card was shown),
`time_budget_selected`, `why_opened`. Review questions: how often does the user start a non-primary
option, and after which reason codes; how often is a REST SOFT card followed by "Train anyway", and what
were that session's sRPE and pain answers (this tunes `today`'s rest decision, not this module).

### 19.2 `ui.prefill` (UI-04): set-card values display

**Owner.** ALGORITHMS_PLANNING section 19.7 specifies the prefill order, the steps, the long-press
timing (`ui.prefill.hold_delay_ms` 400, `repeat_ms` 120, `accel_after` 8, `accel_factor` 5), keypad
snapping through `LOAD.nearestLoadable` and the deviation test (`ui.prefill.hold_short_frac` 0.8). The
loadable sets, the next loadable value in each direction, assist kg and plate breakdowns come from
`load` (the single LOAD function, C1, C2); the ui module only compares and formats them.

**What the screen relies on.**

* Prefill order: an accepted adjustment of this session (11.10), then the target (`rx` or the challenge
  item, including a change made by a challenge in-session rule, D14), then the last value at the same
  set index, then the previous index, then the default. The source is logged as `prefill_source`
  (`accepted_adjustment`, `engine_target`, `last_same_index`, `last_prev_index`, `default`).
* RIR chips and felt chips are never prefilled and never highlighted (D9).
* No unloadable value is ever shown; ties go to the easier load (lower external load or more
  assistance, C2). For assisted pins "harder" is the next lower pin.
* A deviation has a direction `UP`, `DOWN` or `NONE` and a list of fields; mixed changes count as `DOWN`
  (safety first). The direction selects the reason-chip context of 19.3 (`UP` -> `easier`, `DOWN` ->
  `harder`).

**Display details.**

* Values are formatted with section 5: native pin unit first, then "≈ 24.6 kg assist · one-arm load
  46.1 kg" (1 decimal, C54); "BW + 22.5 kg" with the belt breakdown "20 + 2.5 on belt"; "20 + 5 per
  side" for barbells; "per hand" for dumbbells. Breakdowns and kg values are `load` outputs (`breakdown`,
  `assistKg`, `effectiveKg`), never computed in the UI.
* Assisted machines label the stepper buttons by effect, "( - ) harder" and "easier ( + )" (6.1).
* When the right side's reps are capped by the side rule (`ins.scap.matchLeft`, 11.7.2) the hint
  `side_cap_hint` (24.6) is shown and `side_cap_applied` is logged.
* Keypad: chips with the nearest lower and higher loadable values; if "Set" snapped the typed value, a
  `REJECT` haptic and the line "Set to 22.5 kg (nearest you can load)".
* RIR chip row layout (6.2): a row of six chips, or a 2 x 3 grid when `chipWidth < 52 dp` or the font
  scale is 1.5 or more, with `chipWidth = (width - 2 x 12 - 5 x 6) / 6`. These are layout values of
  `InteractionConfig` in `:app`, not params.
* An empty loadable set (unconfirmed equipment, F7) shows the target as text with "Is X kg available?";
  the first answer updates the equipment profile, and `snapped = false` is logged.

**Owner rules the screens rely on** (adopted in ALGORITHMS_PLANNING section 19.7 by C69; listed here
only as references for the screens and fixtures):

* Hold step 0.5 s below a 10 s target, otherwise 1 s; the top-hold attempt fields use the same step
  (11.7.4).
* Right-side prefill capped at the left side's logged reps when the item has the side rule.
* Post-log RIR deviation at `ui.prefill.post_rir_delta` (2) or more from the target, so the optional
  chips appear in the rest card (11.8).
* Hold ranges `[lo, hi]` judged against `lo` (GF-UX-06 "hold range").
* No prefill history across load modes (for example after "+ add weight" on a bodyweight exercise).

**Constants.** Registered in ALGORITHMS_PLANNING section 23.19: the five listed under Owner and
`ui.prefill.post_rir_delta` 2 (C69). Display
constants of `InteractionConfig` (not params): RIR row padding 12 dp, chip gap 6 dp (below the M3 8 dp,
deliberate, 6.2), minimum chip width 52 dp (Parhi 2006; WCAG 2.5.8), grid from font scale 1.5; tuned by
immediate one-step RIR edits (`set_edited.field = rir` within 30 s, delta 1). The double-tap debounce is
`ui.doubleTapDebounceMs` = 300 ms of ANDROID section 25.1; the input guard after a card appears is
500 ms (`InteractionConfig`, P5). The hold felt-chip seconds (Max 0, Hard 2, OK 5, Easy 10+) are owned by
`hold` (HOLD-1).

**Logged data.** In `set_logged` (registry payload): `prefill_source`, `side_cap_applied`,
`deviation_direction`, `deviation_fields`, `input_method` (`none`, `stepper`, `keypad`, `mixed`), `taps`
(taps on the card before the log tap), `snapped`, `ms_from_card_shown` and `ms_from_rest_end`.
`keypad_used` and `log_double_tap_dropped` are separate events. Review questions: a high deviation rate
for one exercise or one prefill source means the targets are off; keypad use on a stepper field means
the step is too small; one-step RIR edits right after logging mean the chips are too close together.

### 19.3 `ui.reasonchips` (UI-05): reason chips, skip and do-later display

**Owner.** ALGORITHMS_PLANNING section 19.2 specifies the chip orders per context, the routing of the
chosen code and do-later (`ui.reasonchips.max_visible` 6, `ui.reasonchips.max_defers` 2). Reason
semantics (what a reason does to estimators and future prescriptions) belong to `reason` (REASON-1,
C16); the codes and labels are the DATA_MODEL `reason_codes` vocabulary (`code`, `label`,
`ui_contexts`, `is_safety`, `implies_flag`).

**Display details.**

* Deviation chips are optional: they never block logging, and untouched chips are stored as
  `deviation_reason_code = null` (counted as `none` in reviews). A skip needs exactly one tap on a
  reason (16.4).
* At most `max_visible` chips are visible (two rows of three 48 dp chips); the rest sit behind "More".
  Sheets (Skip, Finish) show all chips.
* Labels are the English `reason_codes.label`. Choosing `torso_rotation` or `technique_breakdown` also
  sets the matching set flag (DATA_MODEL `implies_flag`).
* Follow-ups: `pain` opens the Pain sheet (safety path, C33); `occupied` in the Skip sheet switches the
  sheet to the Do later / Swap content (11.12); `other` offers optional free text that stays on the set
  row and never reaches telemetry.
* Do later moves the exercise after the next not-done exercise and raises its defer count; from
  `max_defers` defers on, Swap is the primary action. With no not-done exercise after it, Do later is
  disabled and Swap is primary. The "Do k now, come back after" suggestion and the swap candidates come
  from `substitute` (SUB-1).
* The order is static (not adaptive), so the same deviation always shows the same chips; reordering
  happens only through a reviewed params or module version change.

**Chip orders as rendered** (the owner's orders of ALGORITHMS_PLANNING section 19.2; each list holds
exactly the codes whose DATA_MODEL `ui_contexts` contain that context, C38):

| Context (`ui_contexts` value) | Order (owner) | Visible | Required |
|---|---|---|---|
| `easier` (deviation `UP`: more load or reps than the target, or RIR at least target + 2) | `too_easy`, `feeling_good`, `other` | all | no |
| `harder` (deviation `DOWN`: less load or reps, hold short, or RIR at most target - 2) | `too_hard`, `feeling_bad`, `too_tired`, `pain`, `grip_failure`, `torso_rotation` (only when the exercise offers that flag, C74), `technique_breakdown`, `no_time`, `occupied`, `other` | first `max_visible`, rest behind "More" | no |
| `skip` (Skip sheet, 16.4) | `no_time`, `occupied`, `pain`, `feeling_bad`, `too_tired`, `equipment_missing`, `other` | all | yes, exactly one tap |
| `session_end` (Finish sheet, 16.13) | `no_time` (preselected), `pain`, `other` | all | no |
| `substitute` (swap from the exercise menu without a skip, 16.5; owner context adopted by C69) | `occupied`, `equipment_missing` | all | no |

**Logged data.** `deviation_shown {context, direction, fields, codes_shown, visible_count}`,
`reason_selected {reason_code, context, chip_position, via_more}`, `exercise_skipped {reason_code,
chip_position, sets_done}`, `exercise_deferred {defer_count, from_position, to_position}` (registry
payloads). Review: `chip_position` of chosen chips and the untouched rate per context; defer-to-done
versus defer-to-skip.

### 19.4 `ui.prompts` (UI-07, F15): feedback prompt display

**Owner.** ALGORITHMS_PLANNING section 19.3 specifies which prompts are asked before and after a
session, the tap budget, the priority order (pain > elbow check > sRPE > readiness > soreness), the
adaptive reduction and the prompts that are never reduced (constants `ui.prompts.sore_window_h` 96,
`sore_max` 3, `tap_budget` 6 (F15), `skip_streak` 3, `reduced_every` 3, `restore_after` 3,
`pain_guard_d` 14, `pain_severity_map` {mild 2, moderate 5, sharp 8}, C64). Subjective measures track
training response well (Saw, Main and Gastin 2016, strong) and session RPE is a valid single question
(Foster 2001); the budget itself is expert opinion.

**Display details.**

* Pre-session items appear in one optional Readiness sheet (16.3) in this order: readiness, elbow check,
  soreness rows (in the order the module returns). Start is always enabled; unanswered items are logged
  as skipped. Post-session items are the Summary's quick check (12.2).
* Labels and stored codes:

| Item | Labels | Stored as |
|---|---|---|
| `readiness` | Worse / Normal / Better | `wellness_logs` readiness `worse`, `normal`, `better` |
| `elbow_check` | 0 None / 1 Trace / 2 Mild / 3 Pain; at 2 or more the symptom kinds of the active pain rule | `elbow_check` 0-3, symptom codes |
| `soreness[g]` | No / Was, fine now / Yes | E19 `never_sore`, `healed`, `still_sore` (the readiness mapping is the `readiness.soreness_points` param, OI-05) |
| `srpe` | 0-10 in two chip rows | `srpe` 0-10 |
| `joint_pain` | None, or location, then side, then Mild / Moderate / Sharp | `wellness_logs` kind `pain`; E20 `mild`, `moderate`, `sharp` with `pain_score` 2 / 5 / 8 (C64; stop threshold 4) |
| `weigh_in` | kg keypad, 0.1 step (Today row and N-WEIGH, not budgeted) | `metric_observations` `bw` |

* Answers are applied before the first exercise appears (9.8): an elbow score of 1 or more and a
  stop-signal kind are evaluated by `safety` and the challenge rules (C33); "Worse" readiness shows the
  suggestion card produced by `reason` (REASON-1 `feeling_bad` semantics: "1 set fewer per exercise
  today? Apply / Keep"), never an automatic change (D10).
* Home habit sessions show no pre-session prompts (owner).
* You > Prompts (15.8) shows the adaptive state, for example "Readiness is asked every 3rd session
  because it was skipped 3 times in a row. Reset".
* The weigh-in row is shown on Today while the weigh-in is stale (the `bodyweight` staleness threshold,
  14 days, C32); N-WEIGH follows the timing of section 18.

**Owner rule the screens rely on** (C69): the elbow symptom-kind follow-up (score 2 or more) is a
safety tap outside `tap_budget`. GF-UX-11 checks this.

**Logged data.** `prompt_shown {prompt, group?, phase, position, budget_used, reduced}`,
`prompt_answered {prompt, group?, value_code, latency_ms, wellness_log_id?, observation_id?}`,
`prompt_skipped {prompt, group?, cause: user|budget|reduced}`. Review questions: does readiness predict
the first-set residual of SSE-1; does soreness predict performance or volume tolerance; is any prompt
answered so rarely that it should go.

### 19.5 `ui.volumebars` (UI-09): weekly muscle-volume bars display

**Owner.** ALGORITHMS_PLANNING section 19.4 specifies the rows, fills, states and sort
(`ui.volumebars.bar_cap` 1.25). Done and planned numbers come from `setcredit` (one `SetCredit.v1`
table, C6, C44) and `weekplan`, targets and MRV from `targets` (C14); the module never counts sets.
Deficit and overshoot warnings come from the single WarningEngine (C14), not from bar thresholds.

**Display details.**

* One row per muscle group with goal Improve or Maintain: group name, bar (done solid, planned remaining
  dotted, target tick, MRV marker; in deload weeks the normal target is a faint tick next to the
  effective one), the text `done (+planned) / target` with fractional sets to one decimal and ".0"
  dropped (section 5), for example "9.5 (+3) / 12" ("(+planned)" omitted when planned is 0), and the
  goal tag.
* Colours follow 4.1: improve primary, improve with focus primary with an outline, maintain muted.
  Skip groups collapse into one line "Not trained: ...". Groups owned by an active challenge show
  "covered by challenge" instead of a bar (D6); their credited sets appear in the row's detail sheet,
  and challenge volume is exempt from MRV warnings.
* Row states are the owner's: `DEFICIT` (the WarningEngine reports a WARN or STRONG volume deficit for
  the group), `OVER` (done + planned above the MRV), `OK`; both `DEFICIT` and `OVER` carry the `!` flag.
  Display detail: the `!` of a `DEFICIT` row is in the advisory colour, the `!` of an `OVER` row is
  muted (it is not an evidence warning); never colour alone (text tag and accessible text of 23.2).
* Row order: the owner's sort (improve rows, then maintain rows, each by (done + planned) / target
  ascending, then canonical group order; covered rows next; the skip line last).

**Logged data.** `volume_row_opened {group, state}`; once per local date and ISO week the Week tab's
`weekplan_shown` event carries `volume_rows[{group, done, planned, target, state, marker}]` (at most 18
rows, well under the 8 KB payload limit). Review question: are deficits caused by skips (reason codes)
or by planning (planned remained below target)?

### 19.6 `ui.warndisplay` (UI-10): warning display policy

**Owner.** ALGORITHMS_PLANNING section 19.5 specifies where each WarningEngine violation appears: safety
violations always, a "your choice" marker after "Use mine anyway", INFO only in the list with a muted
dot, an amber dot for every WARN or STRONG violation, the banner only above `min_magnitude`, snoozing and
resurfacing after a dismissal (`ui.warndisplay.min_magnitude` 0.2, `snooze_d` 28, `worsen_factor`
0.25, `max_banners` 1). Whether a violation exists, and its severity, is the engine's (`warnings`, C14).

**Display details.**

* Surfaces and severity styling are section 17. SAFETY violations use the red safety banner of 7.3
  with an "Understood" action (the acknowledgement is logged), never the advisory banner.
* "Use suggested" is offered on the banner for STRONG violations or when the violation carries an
  alternative (D10 one tap). "Use mine anyway" keeps the user's value and shows the "your choice" marker
  next to it.
* When several violations hit one entity, the strongest marker is drawn: advisory dot over "your
  choice" over muted dot (GF-UX-12: chest keeps its advisory dot although an INFO note also applies).
* A violation that disappears (the plan changed) simply stops showing; its dismissal record is kept for
  `snooze_d` days.

**Screen scopes** (which entities each screen shows; a display decision of this document):

| Screen | Entities in scope | Global violations shown |
|---|---|---|
| Today | today's primary option and its exercises | SAFETY only |
| Week | all muscle groups, week-level rules | SAFETY, STRONG and WARN of week scope |
| Live Session | the session's exercises and blocks | SAFETY |
| Exercises detail | the exercise, its preference and its set/rep override | none |
| You > Preferences | preferences, goals, set/rep schemes | week-level coverage rules (for example ELIG-1 COVERAGE_GAP) |
| Onboarding set/rep and goals steps | the edited preference or goal | none (notes appear under the control, 8.8, 8.9) |

**Logged data.** `warning_shown {rule_id, severity, entity, magnitude, surface}` (once per rule, entity
and local date per surface), `warning_dismissed`, `warning_use_mine_toggled`, `warning_use_suggested`,
`warning_why_opened`, `safety_acknowledged`. Review: outcomes in the following weeks (e1RM slope, weekly
sets, pain) for warnings kept versus accepted.

### 19.7 `ui.progress` (UI-11): progress charts, personal bests and gate bars display

**Owner.** ALGORITHMS_PLANNING section 19.6 specifies the chart series from the engine-logged per-set
e1RM (C3: never recomputed in the UI), the SSE-1 band (C4: no UI-side smoothing), the axis toggle with
the per-set bodyweight snapshot (C32), personal-best detection and the labelled challenge-gate series
(`ui.progress.pr_min_gain` 0.005, `window_weeks` 12). The gate evaluation (`met` per criterion and side
on the test-day bodyweight, and `thresholdAt(bw)` for the forward threshold) is CHALLENGE_ENGINE section
10.1 (C62). The owner leaves the chart ranges, the opacities, the personal-best list of the Session
Summary and the gate-row layout to this section.

#### 19.7.1 Chart display

* Raw points: per session, the best engine-logged per-set e1RM (holds: best seconds per side; assistance:
  lowest assist kg with a clean rep, or one-arm load as % BW), drawn small at `raw_point_opacity` (60 %).
* Trend: the SSE-1 posterior level (e1RM) with its 80 % interval as a `band_opacity` (12 %) primary fill,
  exactly the engine-logged values (C4).
* Test days as filled diamonds, personal bests as small stars with a list below the chart, the gate as
  a dashed line labelled with its bodyweight basis.
* Axis toggle: "Added kg" (default) subtracts the bodyweight part of each set's own snapshot (C32), so
  assisted sets appear as a negative added load on the same continuous axis as weighted sets (one-arm
  work at pin 40 lb: -24.6 kg) and a later bodyweight change does not move old points; "System kg" shows
  the engine's system load.
* Range chips 4 weeks / 12 weeks (default, `window_weeks`) / 6 months / all; side toggle L / R / both for
  unilateral metrics; "Show as table" gives the same data as an accessible table.

#### 19.7.2 Personal bests display

The Session Summary (12.3) shows at most `max_prs_summary` = 3 new bests ("Show all" for more), ordered
by relative gain, then kind (e1RM, assistance, hold, reps), then exercise and side. The detection is the
owner's (an e1RM best above the previous maximum by `pr_min_gain`; hold bests on seconds per side;
assistance bests on the lowest effective assistance for one clean rep; no e1RM best from eccentric-only,
isometric or finger-assisted sets, C8). The owner also applies these rules (adopted by C69), which
GF-UX-21 checks:

* a rep best: more reps than ever before at the same effective load (0.01 kg grid);
* a hold best needs a gain of at least `hold_pr_min_gain_s` = 0.5 s per side; an assistance best needs at
  least one pin step less with a clean rep (no `failed_rep` flag);
* no best before `pr_min_history` = 1 previous session of that metric (no best on the first exposure);
* sets with a pain reason never produce a best.

#### 19.7.3 Gate rows (Challenges dashboard)

The bar of each gate row is a display computation of this document (CHALLENGE_ENGINE section 10.1
refers here for the display and bar logic):

```
fun gateRows(g: GateStatus, bwNow: BodyweightSummary): List<GateRow>
  for c in g.criteria (definition order):
    thrNow = c.thresholdAt(bwNow.ewmaKg)                 # e.g. 0.65 x 70.7 - 2.0 = 43.955 -> 43.96 (C22, C32, C62)
    for side in c.sides:                                  # ["none"] or ["L", "R"]
      v = c.lastValid[side]                               # from the latest test valid for gates, per metric comparability
      fraction = when {
         c.met[side]                       -> 1.0         # the engine's evaluation (test-day bodyweight, C62) wins
         v == null                         -> 0.0
         c.valueType == BOOL               -> if v then 1.0 else 0.0
         c.better == HIGHER and thrNow > 0 -> clamp(v / thrNow, 0, 1)
         c.better == LOWER and v > 0       -> clamp(thrNow / v, 0, 1)
         else                              -> 0.0 }
      rows += GateRow(c.id, side, label = c.label, thresholdText = thresholdText(c, bwNow),
                      value = v, valueDate = c.lastValidDate, fraction = round4(fraction), met = c.met[side],
                      extras = c.extras,                    # x1.10 value, 80 % range, SSE-1 training estimate (D7, C4)
                      nonValid = c.latestNonValid?.takeIf { it.date > c.lastValidDate })
  return rows
```

The threshold shown is the forward threshold at the current bodyweight (BW(t) EWMA, C32), because that
is what the next test has to reach; the label names that bodyweight ("+43.96 kg = 65 % of 70.7 kg -
2.0 kg"). The met / not-met tick is the challenge engine's evaluation on the test's own bodyweight (C62),
so a row can show a full bar with a tick even if today's bodyweight would give a slightly higher
threshold. Values from tests that are not valid for gates never fill the bar; the newest such
observation is shown on a muted line with its reason (14.2). CHALLENGE_ENGINE section 10.1 exposes both
`met` and `thresholdAt` (C62).

#### 19.7.4 Constants

Registered in ALGORITHMS_PLANNING section 23.19 (owner): `ui.progress.pr_min_gain` 0.005,
`ui.progress.window_weeks` 12 (the research lens's EWMA `alpha` is dropped, because the band is
SSE-1's), and the detection constants `ui.progress.hold_pr_min_gain_s` 0.5 and
`ui.progress.pr_min_history` 1 (C69). Display constants owned by this document:

| Key | Default | Bounds | Evidence | Owner | Tunable, tuned by |
|---|---|---|---|---|---|
| `ui.progress.max_prs_summary` | 3 | 1-5 | expert-opinion (section 12.3) | this document (display) | Yes: "Show all" taps |
| `ui.progress.raw_point_opacity` | 0.60 | 0.3-1.0 | expert-opinion | this document (display) | No |
| `ui.progress.band_opacity` | 0.12 | 0.05-0.3 | expert-opinion (15.2) | this document (display) | No |
| Band probability | 80 % | fixed | decision (C4, D7) | | No |

**Logged data.** `progress_viewed {exercise_id, metric, axis, range}`, `progress_axis_toggled`,
`progress_point_opened`, `pr_detected {exercise_id, kind, side, value, previous}` (once per best at
session finish), `session_summary_shown {prs, next_changes_count, pipeline_ms}`. Review: best rate (a high
rate on one exercise means noise), bodyweight staleness at the time of each point.

### 19.8 `RestTimerController` (platform, C28 and C18)

**Ownership.** The rest *duration* is `rest` (REST-1, C18); the UI never has a rest table. Persistence,
alarms, notifications, recovery, latency logging and the timer constants are owned by ANDROID.md
sections 12 and 25.1 (pure `RestTimerReducer` plus `RestTimerController`; authoritative for platform
behaviour, C54). This subsection fixes the user-visible behaviour, the display functions, the wording,
and the `rest_before_s` estimate (`setStartEstimate`, which DATA_MODEL section 9.6.4 and ANDROID
section 12.3 reference).

**When a timer starts.**

| Logged item | Timer | Duration source |
|---|---|---|
| Working set (`working`, `top`, `backoff`, `amrap`, `calibration`) | yes | `rest.after(set)`: REST-1 table and modifiers, clamp 60-300 s, challenge rest overrides (C18) |
| Left side of a unilateral pair | only if the between-sides rest is at least `timer.minTimerS` (10 s, ANDROID.md) | `rest.betweenSides(item)`; below 10 s the card shows "Switch to your right arm" for `SWITCH_HINT_MS` |
| Right side (pair complete) | yes | `rest.after(pair)` |
| Specific warm-up set (`warmup`, WARMUP-1) | yes | REST-1 warm-up values (45-60 s; 90 s before the first working set) |
| General warm-up block item (row, face pulls) | no | the block card has its own Done taps |
| Test-day attempt (`test`, `attempt`) | yes | the protocol's `rest_s` from the challenge test template |
| Timed hold | yes, after the felt chip | `rest.after(set)` |
| The working set that finishes the last open item's planned sets (no other open item has sets left) | no: there is no next set to rest for; the session notification returns to the count-up (user feedback 2026-10-09) | — |

Every timer is capped at the timer hard maximum of 900 s from its start (C18).

**Display (pure functions, unit-tested).**

```
fun restDisplay(t: RestTimerState, nowWall, nowElapsed, bootCount): RestDisplay
  r = remainingMs(t, nowWall, nowElapsed, bootCount)        # ANDROID.md 12.3: elapsed-based while the boot count is unchanged
  if t.state == RUNNING and r > 0:
      return Countdown(digits = mmss(ceilDiv(r, 1000)),     # "2:18"; shows 0:01 until the end, never 0:00
                       barFraction = clamp(r / (t.endWall - t.startWall), 0, 1),
                       colour = ON_SURFACE)
  overtimeS = floorDiv(max(0, -r), 1000)
  return Overtime(digits = "+" + mmss(overtimeS), bar = HIDDEN, colour = ADVISORY)   # "+0:23"
# The UI switches to overtime at r <= 0 even if the end alarm has not been delivered yet.

fun miniBarRest(t, ...) = when (restDisplay(t, ...)) { is Countdown -> "Rest " + digits; is Overtime -> digits; else -> "" }
```

**Controls.** -15 s, Skip, +15 s (64 dp, tonal), with the 500 ms input guard after the rest card
appears (P5). A tap or a notification action sends ±15 s (`timer.adjustStepS`); a long-press on -15 /
+15 repeats in ±60 s steps (`timer.longPressStepS`, ANDROID sections 12.3 and 25.1). Limits: at least
5 s must remain after an adjustment (`timer.minLeftAfterAdjustS`), and the end can never be later than
start + 900 s (+15 disabled with `REJECT` at the cap). Tapping the digits toggles focus mode. Logging
the next set, starting a hold or tapping Skip ends the rest.

**Alerts.** Pre-warning: one light tick at 10 s left (`timer.prewarnS`), only while the app UI is in
the foreground (ANDROID.md 12.4, C54). End: alarm-clock alarm by default (C28), vibration waveform
`[0, 300, 150, 300, 150, 600]` ms with alarm usage always (F20), optional ducking tone (default off),
heads-up notification "Rest over" · "Set 2: BW + 22.5 kg × 4" (wording of 24.6; channel and behaviour
ANDROID section 12.4). One overtime reminder pulse at +60 s (`timer.overtimeReminderS`). Alarm modes:
`ALARM_CLOCK` (default), `EXACT_IDLE` (user option, offered only after `timer.exactIdleMinSamples` = 30
alarm-clock rests with p95 latency at most 2000 ms and reverted when the p95 of the last
`timer.exactIdleRevertWindow` = 20 alarms of that mode exceeds it, C28, C54), `INEXACT` (fallback when
exact alarms are not allowed, with the "Alarm may be late" line on the rest card).

**Rest fields written on the next set** (DATA_MODEL section 9.6.4 column names, canonical, C55; written
by the ANDROID section 12.3 reducer):

| `set_logs` column | Value |
|---|---|
| `rest_timer_planned_s` | the prescribed duration after clamping, before user adjustments; null when no timer ran |
| `rest_adjust_s` | the net sum of adjustments in seconds (+15, -15, +60, ...) |
| `rest_timer_overridden` | any adjustment or skip happened during that rest (a pair that nets to 0 still counts) |
| `rest_end_reason` | E44: `elapsed` (alarm or display reached 0 and the user started later), `skipped`, `next_set_early` (logged before the end), `elapsed_while_dead`, `none` (first set of an exercise block with no preceding timer) |
| `rest_before_s` | actual rest, from the previous set's `completed_at` to this set's start estimate (below) |
| `rest_source` | E56: `measured` (holds) or `estimated` (below) |

```
fun setStartEstimate(prevCompletedAt, restEndedAt, firstTouchAfterRest, skipTapAt, completedAt, estSetDurationS, holdStartAt)
  if holdStartAt != null: return holdStartAt                       # holds: measured (rest_source = measured)
  candidates = listOfNotNull(firstTouchAfterRest, skipTapAt, completedAt - estSetDurationS)
  return max(prevCompletedAt, candidates.min())                     # rest_source = estimated
# estSetDurationS from `time` (TIME-1: 3 s per rep + 5 s per set, C19). firstTouchAfterRest counts only
# touches on the set card after the rest ended or was skipped; touches during the rest (adjusting the
# weight) do not count.
rest_before_s = round1((setStartEstimate - prevCompletedAt) / 1000)
```

The raw instants (`prev_completed_at`, `rest_end_at`, `first_touch_at`, `skip_at`, `completed_at`) are in
the `rest_ended` and `set_logged` payloads, so a review can recompute rest with any formula.

**Events.** `rest_started`, `rest_adjusted`, `rest_skipped`, `rest_prewarn`, `rest_alarm_fired`,
`rest_alarm_stale`, `rest_overtime_reminder`, `rest_ended`, `rest_recovered`, `rest_alarm_failed`,
`rest_tone_skipped`, `focus_mode_toggled` (DATA_MODEL section 9.8.1 registry; triggers in 20.6).

**Constants.** The rest-timer constants are platform constants in `PlatformConfig`, outside
`params_hash` and logged in event payloads (C54); ANDROID section 25.1 is their register:
`timer.adjustStepS` 15, `timer.longPressStepS` 60, `timer.minLeftAfterAdjustS` 5, `timer.minTimerS` 10,
`timer.prewarnS` 10, `timer.overtimeReminderS` 60, `timer.vibrationWaveformMs`,
`timer.exactIdleMinSamples` 30, `timer.exactIdleP95MaxMs` 2000, `timer.exactIdleRevertWindow` 20. They are
not repeated here. Two display constants of the rest card are specified here and registered in ANDROID
section 25.5 (the same `PlatformConfig`, C54):

| Key (ANDROID 25.5) | Name in pseudo-code | Default | Bounds | Evidence | Tunable, tuned by |
|---|---|---|---|---|---|
| `timer.switchHintMs` | `SWITCH_HINT_MS` | 1500 | 800-3000 | assumption | Yes: time from left log to first right touch |
| `timer.focusModeDefault` | `FOCUS_MODE_DEFAULT` | off | | expert-opinion | User setting |

### 19.9 `HoldTimerController` (platform): lead-in, hold, result

**Purpose.** Time holds while the user's hands are on the bar and the phone is on the floor (11.7.3):
a lead-in countdown with 3-2-1 cues, an audible count-up, a tap-anywhere stop, and a correctable result
that is then logged with the felt chips (D9).

**States.** `READY -> LEAD_IN -> HOLDING -> RESULT -> (logged)`, with `LEAD_IN -> READY` on Cancel and
`HOLDING -> RESULT` on stop or auto-stop. Process death during `LEAD_IN` returns to `READY`; during
`HOLDING` it returns to `RESULT` with an empty value and the line "Timer was interrupted, enter the
time" (`hold_interrupted`).

**Timing.** Cues are scheduled against the monotonic clock (`elapsedRealtime`, injected) as absolute
offsets from the lead-in start, so they never drift. Keep-screen-on is forced during `LEAD_IN` and
`HOLDING` regardless of the user's keep-screen-on setting, because the session service holds no wake
lock (ANDROID.md 11.4) and a sleeping CPU would delay the cues.

```
start(leadInS, mode, targetS, previousBestS, rangeLoS = null):
  # A hold prescribed as a range [lo, hi] (for example the 90-degree hold, 6-8 s) uses targetS = hi and
  # rangeLoS = lo: a TICK at lo ("minimum reached") and the TARGET_DOUBLE at hi.
  t0 = monotonicNow()
  cues = [ (t0 + (leadInS - k) * 1000, LEAD_TICK) for k in 3 downTo 1 if leadInS - k >= 0 ]
       + [ (t0 + leadInS * 1000, GO) ]                          # higher beep + CONFIRM haptic
  goAt = t0 + leadInS * 1000
  if mode == PRESCRIBED:
      cues += [ (goAt + n * BEEP_EVERY_S * 1000, TICK) for n >= 1 while n * BEEP_EVERY_S < targetS ]
      if rangeLoS != null: cues += [ (goAt + rangeLoS * 1000, TICK) ]   # deduplicated if it coincides with a tick
      cues += [ (goAt + targetS * 1000, TARGET_DOUBLE) ]        # replaces a regular tick at the same instant
                                                                # no cues after the target
  if mode == MAX:                                              # test day, `toFailure` items
      cues += [ (goAt + n * BEEP_EVERY_S * 1000, TICK) for n >= 1 while n * BEEP_EVERY_S <= MAX_HOLD_S ]
      if previousBestS != null: cues += [ (goAt + previousBestS * 1000, TARGET_DOUBLE) ]   # "passed your best"
  autoStopAt = goAt + MAX_HOLD_S * 1000

stop(atMonotonic):                                             # tap anywhere, ignored before goAt + STOP_GUARD_MS
  elapsedS = round1((atMonotonic - goAt) / 1000)               # 0.1 s grid (C22): hold_timer_s
  -> RESULT(prefill = resultPrefill(mode, elapsedS, targetS, previousBestS))

fun resultPrefill(mode, elapsedS, targetS, previousBestS): Seconds
  step = if (targetS ?: previousBestS ?: 10.0) < 10.0 then 0.5 else 1.0
  if mode == PRESCRIBED and elapsedS >= targetS: return targetS          # the double beep was the release cue
  return max(0, floorTo(elapsedS - REACTION_OFFSET_S, step))
```

Cue output: a short beep plus a 40 ms tick per `TICK`; two 120 ms pulses 100 ms apart plus a double
beep for `TARGET_DOUBLE` (6.7, 6.8). Beeps use the ducking audio path of ANDROID.md 14.2; in silent mode
or with hold cues off only the haptics play.

**Compact modes without lead-in.**

| Mode | Used for | Behaviour |
|---|---|---|
| Count aloud | top-hold rounds (11.7.4) | one beep per second for up to `COUNT_ALOUD_MAX_S`, started and stopped by a toggle; attempts are entered with steppers |
| Eccentric pacing | negatives (11.7.5) | the configured lead-in, then one beep per second for up to `ECC_PACE_MAX_S`, stopped by tap; the shortest lowering is entered with its stepper |
| Interval | the warm-up row (11.7.6) | requested: `RestTimerController` with `purpose = interval` (alarm-backed, no `set_logs` rest fields), which the ANDROID section 12.3 reducer does not support in v1; until then an in-app countdown with the screen kept on (OI-27) |

**Constants.** The behaviour and the values are this document's (ANDROID section 14.2 leaves the cue
timing to it); the keys are `PlatformConfig` keys registered in ANDROID section 25.5, outside
`params_hash` and logged in event payloads, because they change no recommendation (C54). The pseudo-code
above uses the upper-case names.

| Key (ANDROID 25.5) | Name in pseudo-code | Default | Bounds | Evidence | Tunable, tuned by |
|---|---|---|---|---|---|
| `hold.leadInS` | `LEAD_IN_S` | 5 (options 3, 5, 10) | 3-10 | expert-opinion (time to put the phone down and grip) | User setting; `hold_lead_in_cancelled` rate |
| `hold.beepEveryS` | `BEEP_EVERY_S` | 5 | 1-10 | expert-opinion | User feedback |
| `hold.maxHoldS` | `MAX_HOLD_S` | 300 | 60-600 | assumption | Auto-stops that the user corrects |
| `hold.stopGuardMs` | `STOP_GUARD_MS` | 500 | 200-1500 | assumption | Holds stopped within 1 s and then corrected upwards |
| `hold.reactionOffsetS` | `REACTION_OFFSET_S` | 0.0 | 0.0-2.0 | assumption | Yes: mean of `hold_timer_s - duration_s` over max holds (`hold_corrected`); set to that mean when it is stable over 20 holds |
| `hold.countAloudMaxS` | `COUNT_ALOUD_MAX_S` | 30 | 10-60 | assumption | No data needed |
| `hold.eccPaceMaxS` | `ECC_PACE_MAX_S` | 30 | 10-60 | assumption | No data needed |
| Tick, double pulse | | 40 ms; 2 x 120 ms, 100 ms apart | | expert-opinion | User feedback |

**Events.** `hold_started {mode, side, target_s, lead_in_s}`, `hold_lead_in_cancelled`, `hold_stopped
{elapsed_s, via: tap|auto_max}`, `hold_corrected {timer_s, logged_s, delta_s}`, `hold_interrupted`,
`hold_count_aloud_toggled`, `hold_ecc_pacing_started` (DATA_MODEL section 9.8.1 registry).

### 19.10 `TelemetryWriter` (platform, C24)

**Purpose.** Write every UI, lifecycle, rest-timer, calendar and sync event into the single
`session_events` stream (C24; DATA_MODEL.md 9.8.1), so that reviews can replay what the user saw and did.
There is no `ui_event`, `rest_event` or `calendar_classification_log` table (C24).

**Write path.**

1. An event that describes a domain write (a set log, a skip, a preference change, a calendar write) is
   inserted in the **same Room transaction** as that write and its outbox entry (ANDROID.md 7.3, C23).
   If the domain write fails, the event is not written, and vice versa.
2. The payload is built *before* the transaction. If building the payload throws (a bug), the domain
   write still commits with the event payload `{"error": "payload_build_failed", "event_schema": "<ui_version>"}`
   so that logging is never blocked (P10).
3. Events without a domain write (`screen_viewed`, `why_opened`, `progress_viewed`, ...) are buffered in
   memory and flushed in one transaction when `BUFFER_MAX` events are queued, after `FLUSH_MS`, or on
   `onStop` of the activity. A crash can lose at most these view events, never a domain event.
4. Rows are append-only and synced with ignore-duplicates (C23); the id is a UUIDv7.

**Envelope** (columns of `session_events`; S-APP standard columns plus):

| Column | Value |
|---|---|
| `id` | UUIDv7 from the injected clock and `SecureRandom` |
| `occurred_at` | the Instant of the user action or system callback (not the write time) |
| `tz_offset_min` | the zone offset at `occurred_at` (120 before 2026-10-25 03:00 CEST, 60 after; disambiguates the repeated hour) |
| `event_type`, `category` | the DATA_MODEL section 9.8.1 registry (canonical, C39); triggers in section 20 |
| `screen` | screen id of section 1 (`today`, `session.live`, `sheet.skip`, ...), or null for background events |
| `session_id` | the active session, if any |
| `entity_type`, `entity_id` | the domain row the event is about (`set`, `session_exercise`, `session`, `block`, `recommendation`, `calendar_event`, `enrollment`, `exercise`, `gym_equipment`, `wellness_log`, `export`, `none`) |
| `recommendation_id` | the engine snapshot whose output was shown or acted on |
| `module_id`, `module_version` | for events that record a ui module's output (`ui.todaycard@1.0.0`, ...) or an engine output |
| `ui_version` | the version of this telemetry catalogue (`1.0.0`); bumped (semver) whenever an event's payload changes |
| `app_version`, `device_id` | S-APP columns |
| `payload` | JSON object, at most 8192 bytes (DATA_MODEL check) |

**Payload rules.**

| Rule | Detail |
|---|---|
| No free text | Never notes, never "Other" free text, never search queries (only their length), never calendar titles (only the 64-hex `instance_key` of DATA_MODEL section 9.7.3 and the classification), never e-mail or names. Notes are referenced by the id of the row that holds them. |
| Codes and numbers only | Enum values lower snake case; ids as UUID strings; kg on the 0.01 grid, seconds on the 0.1 grid, ratios on the 1e-4 grid (C22); durations in integer milliseconds where named `*_ms`. |
| Keys | snake_case; the same key always means the same thing across events (glossary in 20.1). |
| Bounded arrays | Arrays hold at most `MAX_ARRAY` (50) items, the limit of the DATA_MODEL section 9.8.1 payload rules; longer lists are truncated and `truncated: true` is added. |
| Hashes | `inputs_hash` is SHA-256 of the RFC 8785 JCS form (C24), lower-case hex. |
| Determinism | Payload maps are serialised with sorted keys, so the same event always has the same bytes (needed for golden test GF-UX-17). |

**Constants** (`TelemetryConfig` in `:app`, outside `params_hash`, C54):

| Constant | Default | Bounds | Evidence | Tunable, tuned by |
|---|---|---|---|---|
| `BUFFER_MAX` | 20 events | 1-100 | assumption | View events lost after crashes (crash log vs gaps) |
| `FLUSH_MS` | 2000 | 500-10 000 | assumption | Same |
| `MAX_ARRAY` | 50 | fixed by the registry payload rules | decision (DATA_MODEL section 9.8.1) | Truncation counts (a change needs the registry first) |
| `SCREEN_VIEW_MIN_GAP_MS` | 1000 | 0-5000 | assumption | Duplicate `screen_viewed` from recompositions |

**Volume.** About 150 events per gym session at about 300 bytes each is roughly 45 kB per session, or
about 7 MB per year at three sessions a week: small against the Free plan's 500 MB (F4).

---

## 20. Telemetry event catalogue

The event registry of DATA_MODEL.md section 9.8.1 is canonical for every `session_events.event_type`
value, its category and its payload keys (C39): it is the union of the UX and ANDROID event names,
renamed to the snake_case `<area>_<event>` scheme (E48), and migration 005 seeds it. This section
references that registry and adds what the registry leaves to this document: when the UI emits each
event (trigger), the entity it is about, and value details (enum values, units). The payload keys
listed below repeat the registry's keys for convenience and must match them exactly; if they ever
differ, the registry wins and this section is corrected. The section also lists the `challenge_events`
the UI originates (20.11), whose payloads are owned by CHALLENGE_ENGINE section 4.2. The envelope and
payload rules are in 19.10. Categories are exactly the 14 values allowed by the
`session_events.category` CHECK in DATA_MODEL.md 9.8.1 (`lifecycle`, `logging`, `today`, `week`,
`autoreg`, `warning`, `prompt`, `rest`, `calendar`, `sync`, `config`, `system`, `challenge`, `export`).
Names used by earlier drafts of this document are listed in 20.13 for traceability.

No payload contains free text (19.10). "Optional" fields are omitted when not applicable rather than
sent as null.

### 20.1 Shared payload keys

| Key | Type | Meaning |
|---|---|---|
| `set_id`, `session_exercise_id`, `exercise_id`, `block_id`, `enrollment_id`, `timer_id`, `wellness_log_id`, `recommendation_id` | uuid | ids of domain rows |
| `side` | `both`, `L`, `R` | E1 |
| `set_index`, `round_index`, `attempt_index` | int | as in `set_logs` |
| `set_kind` | E2 | |
| `reason_code` | `fitness.reason_codes.code` | 13 canonical codes |
| `context` | `easier`, `harder`, `skip`, `session_end`, `substitute` | reason-chip context (19.3); the DATA_MODEL `reason_codes.ui_contexts` values (C38) |
| `chip_position` | int, 1-based | position of the chosen chip in its displayed order |
| `rank` | int, 1-based | position in a ranked list |
| `latency_ms` | int | time from the element being shown to the user's action |
| `duration_ms` | int | time spent on a step or screen |
| `via` | enum per event | how the action was performed |
| `from`, `to` | code or number on the C22 grid | previous and new value |
| `instance_key` | text | the full 64-hex `calendar_events.instance_key` (SHA-256, DATA_MODEL section 9.7.3), so telemetry joins to stored events; never the title |
| `inputs_hash` | hex sha256 | JCS hash of a ui module's input (19.0) |
| `rule_id` | text | rule id exactly as in the challenge definition or WarningEngine |
| `entity` | text | WarningEngine entity key (`group:quads`, `exercise:<uuid>`, `pref:<uuid>`, `session:<uuid>`) |

### 20.2 Lifecycle (`lifecycle`)

| event_type | Emitted when | Entity | Payload |
|---|---|---|---|
| `session_started` | Start tapped (any template) and the session row is created | session | `kind` (gym, home, test), `template_source` (E9), `challenge_slot`?, `families[]`, `budget_min`? (null = no limit), `budget_source` (calendar, chip, default), `started_from` (today_primary, today_alt, template_picker, week_day, test_wizard, habit_row, habit_notification), `plan_recommendation_id`?, `enrollment_mode`?, `params_hash`, `engine_version`, `catalog_version` |
| `session_minimised` | chevron or system back in Live Session | session | `via` (chevron, back) |
| `session_resumed` | return to Live Session | session | `via` (mini_bar, today_card, notification, relaunch) |
| `session_restored` | app relaunched with a session in progress (3.4) | session | `rest_state` (none, running, overtime), `ms_since_last_event` |
| `session_finished` | Finish completes (11.14) | session | `duration_s`, `working_sets_done`, `working_sets_planned`, `exercises_not_done`, `not_done_reason_codes[]`, `outbox_depth` |
| `session_abandoned` | Abandon in the session menu | session | `working_sets_done`, `reason_code`? |
| `session_abandon_undone` | Undo within 10 s | session | `ms_since` |
| `session_reopened` | Reopen an abandoned session (same local date) | session | `ms_since_abandon` |
| `session_deleted` | Delete in History (undo 10 s) | session | `working_sets`, `kind` |
| `block_started`, `block_finished` | first set of a block / last item of a block done or skipped | block | `block_id`, `block_kind` (E10), `origin`, `status`? |
| `block_addon_skipped` | Skip on an add-on block header | block | `block_id`, `reason_code`, `exercises_skipped` |
| `exercise_focused` | the current exercise changes | session_exercise | `via` (auto, list_tap, reorder, deferred_return) |
| `session_overrun_shown` | the session header shows the overrun line (elapsed plus remaining estimate more than 10 min above the budget, C63) | session | `elapsed_min`, `remaining_est_min`, `budget_min` |
| `session_idle_prompted` | N-IDLE is posted or answered (emitted by ANDROID section 11.5) | session | `idle_min`, `action` (posted, finish, keep_open) |
| `session_summary_shown` | Session Summary rendered | session | `prs` (list of `{kind, exercise_id, side}`), `next_changes_count`, `pipeline_ms` |
| `recalc_report_shown` | recalculation report after a test submit | enrollment | `lines`, `test_valid` |

### 20.3 Logging (`logging`)

| event_type | Emitted when | Entity | Payload |
|---|---|---|---|
| `set_logged` | RIR chip, felt chip, Record or Done tap writes a `set_logs` row (same transaction) | set | `set_id`, `session_exercise_id`, `set_index`, `side`, `set_kind`, `round_index`?, `attempt_index`?, `prefill_source` (accepted_adjustment, engine_target, last_same_index, last_prev_index, default), `side_cap_applied`, `deviation_direction` (none, up, down), `deviation_fields[]` (load, reps, hold, side_rule, rir), `input_method` (none, stepper, keypad, mixed), `taps`, `snapped`, `log_control` (rir_chip, felt_chip, record, done), `chip_value` (0-5, max, hard, ok, easy; the "`rir_chip`" of 6.2)?, `ms_from_card_shown`, `ms_from_rest_end`?, `first_touch_at`?, `completed_at` |
| `set_undone` | Undo in the snackbar | set | `set_id`, `ms_since_logged` |
| `set_edited` | Set edit sheet or History saves a changed field (one event per field) | set | `set_id`, `field` (load, reps, duration_s, rir, flags, reason, ecc_s, side), `from`, `to`, `surface` (set_edit_sheet, history), `ms_since_logged` |
| `set_deleted` | Delete set | set | `set_id`, `surface` |
| `set_added_later` | a forgotten set added in History | set | `set_id`, `hours_after_session` |
| `log_double_tap_dropped` | a second tap within 300 ms (`ui.doubleTapDebounceMs`) on a log control is ignored | none | `control` (rir_chip, felt_chip, record, done, button), `gap_ms` |
| `deviation_shown` | reason chips appear (19.3) | set or session_exercise | `context`, `direction`, `fields[]`, `codes_shown[]`, `visible_count` |
| `reason_selected` | a reason chip is tapped | set or session_exercise | `reason_code`, `context`, `chip_position`, `via_more` |
| `flag_toggled` | a flag chip changes | session_exercise | `flag` (E6), `on` |
| `keypad_used` | the Keypad sheet sets a value | session_exercise | `field` (load, reps, hold_s, ecc_s, bodyweight), `typed`, `snapped_to`, `changed` |
| `hold_started` | lead-in begins (19.9) | session_exercise | `mode` (prescribed, max), `side`, `target_s`?, `lead_in_s` |
| `hold_lead_in_cancelled` | Cancel during lead-in | session_exercise | `ms_into_lead_in` |
| `hold_stopped` | stop tap or auto-stop | session_exercise | `elapsed_s`, `via` (tap, auto_max) |
| `hold_corrected` | the logged duration differs from the timer | set | `timer_s`, `logged_s`, `delta_s` |
| `hold_interrupted` | process death during a hold | session_exercise | `ms_since_go`? |
| `hold_count_aloud_toggled`, `hold_ecc_pacing_started` | compact timer modes (19.9) | session_exercise | `on` / `lead_in_s` |
| `warmup_item_done` | Done on a warm-up block item or specific warm-up set | block or set | `item_key`, `block_id` |
| `exercise_skipped` | a reason tap in the Skip sheet | session_exercise | `reason_code`, `chip_position`, `sets_done` |
| `exercise_deferred` | Do later | session_exercise | `defer_count`, `from_position`, `to_position` |
| `swap_sheet_shown` | Swap sheet opened | session_exercise | `from_exercise_id`, `candidates[{exercise_id, score}]`, `trigger_reason` (occupied, equipment_missing, user) |
| `exercise_swapped` | a substitute is chosen | session_exercise | `from_exercise_id`, `to_exercise_id`, `rank`? (absent when chosen by search), `similarity`?, `via` (list, search), `reason_code`? |
| `exercise_added` | Add exercise sheet adds an exercise | session_exercise | `exercise_id`, `source` (suggested, recent, search, catalog), `position` (end, after_current), `block_kind`, `rule_override` |
| `exercise_removed` | Remove (no logged sets) | session_exercise | `exercise_id` |
| `exercise_ended` | End exercise (logged sets kept) | session_exercise | `sets_done`, `sets_planned`, `reason_code` |
| `exercise_reordered` | drag or Move up / down | session_exercise | `from_position`, `to_position`, `via` (drag, menu), `left_program_order` |
| `override_applied` | "Add a set" (C63) or another prescription change at exercise level made by the user | session_exercise | `field` (sets, rest_s), `from`, `to`, `reason_code`? |
| `rule_override` | the user does something a hard rule forbids ("Add anyway", "Log a session anyway") | session_exercise or session | `rule_id`, `enrollment_id`?, `action` (add_exercise, log_anyway), `exercise_id`? |
| `action_undone` | Undo of a skip, remove, defer, swap, suggestion or Plan it | as the undone event | `undone_event_type`, `undone_event_id`, `ms_since` |
| `habit_toggled` | habit row checkbox or home session finish (a notification never completes a habit, ANDROID section 17) | session | `habit_id`, `done`, `via` (checkbox, session) |
| `pain_reported` | Pain sheet Report (same transaction as the PainEvent `wellness_logs` row) | wellness_log | `wellness_log_id`, `body_region`, `side`, `severity` (E20), `pain_score`, `symptom_codes[]`, `context` (set, session_menu, skip_sheet, summary, challenges, test, readiness) |
| `pain_retracted` | "Undo (wrong tap)" within 10 s | wellness_log | `wellness_log_id`, `ms_since` |
| `pr_detected` | a personal best is found at session finish (19.7.2) | set | `exercise_id`, `kind` (e1rm, reps, hold, assist), `side`, `value`, `previous` |

### 20.4 Today and Week (`today`, `week`)

| event_type | Category | Emitted when | Entity | Payload |
|---|---|---|---|---|
| `today_card_shown` | today | the Today card is rendered with a new state or new inputs (not on every recomposition) | recommendation | `state`, `verdict` (TRAIN, REST_SOFT, REST_HARD, C53), `variant`, `options[{option_id, type, score}]`, `chips[{code, class}]`, `confidence` (clear, close)?, `primary_option_id`?, `calendar_instance_keys[]`, `unknown_codes[]`, `inputs_hash` (module `ui.todaycard`) |
| `today_action` | today | any Today card action | recommendation | `action` (start_primary, start_alt, select_alt, start_anyway, rest_today, train_anyway, log_anyway, plan_it, other_options, preview, postpone, retry, start_empty, view_summary, continue_setup, resume), `rank`?, `latency_ms` |
| `time_budget_selected` | week | a budget chip is selected (or preselected at Start) | block | `minutes`? (absent = no limit), `source` (calendar, chip, default), `trimmed_count`, `core_does_not_fit` |
| `session_trimmed` | week | `time` trims a block to its budget (C19) | block | `block_id`, `budget_min`, `trimmed[{exercise_id, sets_removed, removed}]`, `core_does_not_fit` |
| `template_picked` | today | Template picker selection | none | `kind` (ranked_option, challenge_slot, family_combo, empty), `slot_or_type`, `rank`? |
| `why_opened` | today | Why sheet opened on any screen | recommendation | `subject_kind` (today_card, option, prescription, rest, warning, rule, gate, volume_row, calendar_event, preference), `subject_id`?, `codes[]` |
| `trace_exported` | today | Copy trace / Export trace in the Why sheet | recommendation | `via` (clipboard, share) |
| `weekplan_shown` | week | Week tab rendered for a week (once per local date and ISO week) | recommendation | `iso_week`, `iso_week_offset`, `days[{date, kind, type}]`, `volume_rows[{group, done, planned, target, state, marker}]` |
| `week_day_opened` | week | Day sheet opened | none | `date`, `row_kind` |
| `plan_it_tapped` | week | Plan it (10.4) | recommendation | `date`, `type`, `recommendation_id` |
| `volume_row_opened` | week | a volume row is tapped | none | `group`, `state` |

### 20.5 Suggestions, warnings and prompts (`autoreg`, `warning`, `prompt`)

| event_type | Category | Emitted when | Entity | Payload |
|---|---|---|---|---|
| `suggestion_shown` | autoreg | an engine suggestion card appears (AUTO-1 with the UI-06 trigger, C16; or the readiness "Worse" card) | session_exercise or session | `trigger` (first_set_delta, consecutive, reps_short, reason, readiness_worse), `deltas[]` (RIR deltas of the sets that triggered), `proposed{load_native, unit, reps, sets}`, `recommendation_id` |
| `suggestion_applied` | autoreg | Apply | session_exercise or session | `recommendation_id`, `latency_ms` |
| `suggestion_kept` | autoreg | Keep, or the next set logged without a choice | session_exercise or session | `implicit` |
| `warning_shown` | warning | a warning reaches a banner, list or inline marker (once per rule, entity, surface and local date) | none | `rule_id`, `severity`, `entity`, `magnitude`, `surface` (banner, list, inline, your_choice) |
| `warning_dismissed` | warning | Dismiss | none | `rule_id`, `entity`, `magnitude` |
| `warning_why_opened` | warning | Why from a warning | none | `rule_id`, `entity` |
| `warning_use_suggested` | warning | "Use suggested" (D10) | none | `rule_id`, `entity`, `field`, `from`, `to` |
| `warning_use_mine_toggled` | warning | "Use mine anyway" switched | none | `rule_id`, `entity`, `on` |
| `safety_acknowledged` | warning | "Understood" on a SAFETY warning (7.3) | none | `rule_id`, `entity` |
| `prompt_shown` | prompt | a prompt item is displayed (19.4) | session | `prompt` (readiness, elbow_check, soreness, srpe, joint_pain, weigh_in), `group`?, `phase` (pre, post, standalone), `position`, `budget_used`, `reduced` |
| `prompt_answered` | prompt | an answer is saved | wellness_log | `prompt`, `group`?, `value_code` (for example worse, 2, still_sore, 7, elbow), `latency_ms`, `wellness_log_id`? or `observation_id`? |
| `prompt_skipped` | prompt | not answered when the sheet closes, or not shown | session | `prompt`, `group`?, `cause` (user, budget, reduced) |

### 20.6 Rest timer (`rest`)

Payload details of `rest_alarm_fired` are owned by ANDROID.md 12.7; they are repeated here only by name.

| event_type | Emitted when | Entity | Payload |
|---|---|---|---|
| `rest_started` | a timer starts (19.8) | set | `timer_id`, `set_id`, `planned_s`, `purpose` (rest, between_sides, interval), `duration_source` (rest_module, challenge, protocol), `alarm_mode` (alarm_clock, exact_idle, inexact) |
| `rest_adjusted` | -15 / +15 / long-press / notification action | set | `timer_id`, `delta_s`, `new_end_wall_ms`, `capped`, `via` (button, long_press, notification) |
| `rest_skipped` | Skip in the card, focus mode or notification | set | `timer_id`, `remaining_ms`, `via` (button, focus_mode, notification) |
| `rest_prewarn` | the 10 s pre-warning tick fires (foreground only) | set | `timer_id` |
| `rest_alarm_fired` | the end alarm is delivered | set | `timer_id`, `scheduled_end_wall_ms`, `fired_wall_ms`, `latency_ms`, `alarm_mode`, `app_state`, `screen_interactive`, `device_idle`, `power_save`, `battery_opt_exempt`, `standby_bucket`, `fgs_type`, `sdk_int`, `manufacturer`, `build_display`, `platform_config_hash` (ANDROID.md 12.7) |
| `rest_alarm_stale` | an alarm arrives for a timer that is no longer running | none | `timer_id` |
| `rest_overtime_reminder` | the +60 s reminder pulse | set | `timer_id`, `overtime_ms` |
| `rest_ended` | the next set starts, rest skipped or session finished | set | `timer_id`, `end_reason` (E44, plus `session_finished` when the session ends during a rest), `planned_s`, `adjust_s`, `actual_s`, `rest_source` (measured, estimated), `prev_completed_at`, `rest_end_at`?, `first_touch_at`?, `skip_at`?, `completed_at`? |
| `rest_recovered` | recovery after process death finds a running timer (ANDROID.md 12.6) | set | `timer_id`, `missed_by_ms`? |
| `rest_alarm_failed` | the alarm could not be scheduled (7.5) | set | `timer_id`, `error_class` |
| `rest_tone_skipped` | the tone could not play (audio focus denied, silent mode) | set | `timer_id`?, `reason` (focus_denied, silent_mode, setting_off) |
| `focus_mode_toggled` | focus mode on or off | none | `on` |

### 20.7 Calendar (`calendar`)

| event_type | Emitted when | Entity | Payload |
|---|---|---|---|
| `calendar_classified` | `calendar.classify` classifies an instance (C20) | calendar_event | `instance_key`, `kind` (challenge_session, planned_gym, run, busy, ignored), `slot`?, `type`?, `score`, `features{title_pattern, gym_word, location, duration, all_day, marker, override}`, `classifier_version`, `method` (E23) |
| `calendar_selection_saved` | calendar selection saved (8.10, 15.6) | none | `count_selected`, `primary_selected` |
| `calendar_correction` | the user corrects a classification (16.11) | calendar_event | `instance_key`, `from`, `to`, `scope` (event, title_fingerprint) |
| `calendar_event_written` | the app executed an insert, update or delete of one of its own events (D11; written by ANDROID sections 16.7-16.8) | calendar_event | `op` (insert, update, delete), `kind` (challenge, general), `instance_key`?, `marker_key` (the C37 slot key, for example `oap/A/2026-W42` or `gen/2026-W41/3`), `cause` (auto, plan_it, undo, postpone, pause, abandon), `provider_rows` |
| `calendar_event_adopted` | Adopt (10.5, 16.11) | calendar_event | `slot`, `instance_key` |
| `calendar_conflict_resolved` | the user chooses Rename or Keep on an unmarked conflicting event (C36, 10.3) | calendar_event | `instance_key`, `choice` (rename, keep) |
| `calendar_link_changed` | the post-hoc link of a session to an event is set or changed (C35, 12.3) | session | `from_instance_key`?, `to_instance_key`?, `method` (auto, manual) |
| `calendar_write_failed` | a write is refused or fails (7.5) | calendar_event | `op`, `error_class` (for example `marker_missing`, `week_guard`, `provider_error`) |
| `calendar_unavailable` | permission missing or provider error; degraded mode starts (10.6) | none | `reason` (permission, provider_error) |

### 20.8 Configuration and onboarding (`config`)

| event_type | Emitted when | Entity | Payload |
|---|---|---|---|
| `onboarding_step_completed` | an onboarding step is completed or skipped | none | `step` (signin, about, profile, permissions, equipment, setrep, goals, calendars, defaults, challenge, done), `duration_ms`, `skipped`, `fields_set[]` |
| `disclaimer_acknowledged` | the disclaimer checkbox is confirmed (8.4) | none | `version` |
| `equipment_confirmed` | Confirm equipment (8.7, 15.5) | gym_equipment | `profile_version`, `changed_from_default[]` (keys only) |
| `equipment_changed` | an equipment value is edited | gym_equipment | `key`, `from`, `to` |
| `equipment_calibration_changed` | a calibration field of a machine changes (DATA_MODEL 9.3.3) | gym_equipment | `gym_equipment_id`, `from_scheme`, `to_scheme`, `from_rev`, `to_rev` |
| `rep_preference_changed` | set and rep preference saved (8.8, 15.4, exercise detail) | none | `scope` (global, class_compound, class_isolation, exercise), `exercise_id`?, `field` (sets, rep_range, rir, scheme), `from`, `to`, `warning_rule_ids[]` |
| `goal_changed` | a muscle goal or focus changes (8.9, 15.4) | none | `group`, `from`, `to`, `focus_from`, `focus_to`, `via` (preset, manual), `preset_id`? |
| `preference_changed` | 5-state preference changed (13.4) | exercise | `exercise_id`, `from`, `to`, `surface` (detail, list, session) |
| `exercise_custom_created` | custom exercise saved (13.5) | exercise | `exercise_id`, `pattern` (E36), `equipment`, `coefficients_edited` |
| `settings_changed` | any other setting (time budget, changeover, max visits, windows, supersets, RIR check sets, running priority, prompts, rest and alerts, appearance) | none | `key`, `from`, `to` |
| `params_activated` | newly pulled params become active at a session start | none | `params_hash`, `params_version`, `session_id`? |
| `params_rejected` | params or a definition fail validation (7.5) | none | `params_version`, `kind` (params, catalog, definition), `error_class` |

### 20.9 System and navigation (`system`)

| event_type | Emitted when | Entity | Payload |
|---|---|---|---|
| `app_opened` | process start or return to foreground | none | `cold_start` |
| `screen_viewed` | navigation to a screen or sheet (not on recomposition; at most once per `SCREEN_VIEW_MIN_GAP_MS`) | none | `iso_week_offset`? (Week), `tab_reselected`? |
| `signin_failed` | sign-in error (8.2) | none | `error_class` (credentials, offline, rate_limited, server) |
| `allowlist_rejected` | the account is not allowlisted (8.3) | none | (empty; the user id is in the envelope) |
| `permission_requested` | a runtime permission or settings page is requested | none | `permission` (notifications, exact_alarm, activity_recognition, calendar_read, calendar_write, battery_optimization, promoted_notifications) |
| `permission_result` | the result returns (8.6) | none | `permission`, `granted`, `can_ask_again` |
| `exact_alarm_status`, `battery_optimization_status` | checked on onboarding and on each app start | none | `allowed` / `ignoring` |
| `oem_settings_opened`, `oem_settings_confirmed` | phone-maker settings step (8.6) | none | `maker`, `intent_ok?` (only on opened) |
| `timer_selftest` | the 20 s self-test completes (8.6) | none | `latency_ms`, `felt`, `screen_off`, `alarm_mode`, `passed`, `platform_config_hash` |
| `notification_posted` | a notification of section 18 is posted (for scheduled reminders, when it is delivered) | none | `notification_id` (N-SESSION, N-REST-DONE, N-IDLE, N-HABIT, N-WEIGH, N-TEST-DAY, N-PROGRAM, N-SAFETY, N-CELEBRATE, N-EXPORT, N-SYNC), `channel` (ANDROID 13.1 channel id), `latency_ms?` (scheduled reminders), `platform_config_hash?` (reminder deliveries) |
| `notification_action` | a notification action is tapped | none | `notification_id`, `channel`, `action` (minus_15, plus_15, skip, start, snooze, skip_today, finish, keep_open, weigh_in, export, open) |
| `notification_opened` | a notification body is tapped | none | `notification_id`, `channel` |
| `engine_error` | an engine module throws (7.5) | recommendation | `module_id`, `module_version`, `inputs_hash`, `error_class` |
| `catalog_searched` | search results update (13.2) | none | `query_length`, `results` |
| `catalog_filter_changed` | a catalog filter or sort changes | none | `filter` (muscle, equipment, pattern, preference, available_here, custom, sort), `value` |
| `exercise_opened` | exercise detail opened | exercise | `exercise_id`, `from` (list, session, alternatives, history, add_sheet) |
| `exercise_alternatives_opened` | Alternatives section expanded | exercise | `exercise_id`, `count` |
| `progress_viewed` | Progress chart shown | exercise | `exercise_id`, `metric`, `axis`, `range` |
| `progress_axis_toggled` | axis toggle | exercise | `axis` |
| `progress_point_opened` | a chart point tooltip opened | set | `set_id`?, `kind` (raw, test, pr) |
| `history_session_opened` | History session detail opened | session | `days_ago` |

### 20.10 Challenge UI events (`challenge`)

| event_type | Emitted when | Entity | Payload |
|---|---|---|---|
| `challenge_setup_step` | each setup step is left (14.8) | enrollment | `step` (1-10), `step_key`, `duration_ms`, `action` (next, back, leave) |
| `challenge_cutover_confirmed` | Start challenge after the cutover checkbox (D4) | enrollment | `definition_id`, `version`, `sha256` |
| `challenge_proposal_viewed` | a proposal is opened (14.4) | enrollment | `proposal_id` |
| `challenge_proposal_decided` | Approve or Decline on a proposal (D8, C48) | enrollment | `proposal_id`, `decision` (approved, declined), `previous`? |
| `challenge_rule_viewed` | a rule detail opened (14.3) | enrollment | `rule_id` |
| `challenge_rule_applied` | a challenge in-session rule changes the remaining sets automatically (11.10; D14, C46) | session_exercise | `rule_id`, `rule_tag` (A, P), `proposal_id`?, `session_exercise_id`, `field`, `from`, `to`, `sets_affected` |
| `challenge_rule_undone` | Undo on the "Rule: ... · Undo" line (C46: an override with a reason) | session_exercise | `rule_id`, `session_exercise_id`, `reason_code`, `ms_since` |
| `challenge_pause_sheet_shown`, `challenge_resume_sheet_shown`, `challenge_abandon_dialog_shown` | 14.7 | enrollment | `gap_days`? |
| `test_validity_shown` | the validity line of the test wizard (14.9) | session | `valid`, `reason_code`? |
| `test_step_completed` | a test wizard step is finished | session | `step`, `step_key`, `duration_ms`, `skipped` |
| `test_submitted` | Submit test | session | `valid`, `steps_completed`, `steps_skipped` |
| `celebration_shown` | gate achieved or phase change moment (14.2) | enrollment | `kind` (gate, phase), `phase`, `criterion_id`? |

### 20.11 `challenge_events` originated by the UI

These are domain input events of an enrollment (DATA_MODEL.md 9.7.1), not telemetry. They are written
in the same transaction as the domain rows they summarise. Their payload format (stored keys in
snake_case, coded values from DATA_MODEL, raw RIR as reported, C17, never free text) is owned by
CHALLENGE_ENGINE section 4.2; this table gives only the UI trigger and the main fields as a reading aid.
The tick events (`day.tick`, `week.closed`, `week.started`, `test.due`) are synthesised by the app's
challenge scheduler, not by a user action.

| challenge_event | UI trigger | Main fields (owner: CHALLENGE_ENGINE section 4.2) |
|---|---|---|
| `enrollment.created` | Challenge setup step 10 "Start challenge" (D4) | `params`, onboarding values, the `import` block (D4: baseline state and imported tests with validity flags, C59), `definition_version`, `jcs_sha256`; no proposal decisions (those are the separate events below) |
| `rules.approval_changed` | Approve / Decline on a proposal (setup step 8: one event per proposal right after `enrollment.created`; later from the Proposals screen of the dashboard menu) (D8) | `proposal_id`, `decision` (`approved`, `declined`), `previous?`, `decided_at`; one event per proposal, not per rule (C48) |
| `session.started` | Start of a session with a challenge slot (C35) | `session_id`, `slot`, `template_id`, `mode`, `local_date`, `started_at` |
| `session.completed` | Finish of that session (fires on the template slot, C35) | `session_id`, `slot`, `template_id`, `kind`, `local_date`, `completion_ratio`, `pain_reported`, the logged rows per item key under their `set_logs` column names (CHALLENGE_ENGINE section 6.12), `engine_facts` |
| `session.amended` | an edit in History to a session whose `session.completed` exists | same as `session.completed`, plus `supersedes_event_id` |
| `set.logged` | each logged set of an item that has in-session rules (`ins.*` rules of the definition) | `session_id`, `item_key` and the row |
| `habit.completed` | habit checkbox (quick done) or the home mini-session finishing | `habit_id`, `local_date`, rows (none for a quick done), `measure` value if any |
| `readiness.checked` | Readiness sheet Start (16.3) before a challenge session | `elbow_score`, `symptom_codes[]`, `readiness_level?` |
| `pain.reported` | Pain sheet Report (16.2) while a challenge is active | `wellness_log_id`, `body_region`, `side`, `symptom_codes[]`, `severity`, `pain_score` (C64), `context` |
| `test.completed` | Test wizard Submit (14.9) | `test_session_id`, `bw_kg`, metric values per side, validity inputs (the engine decides validity), attempts, `engine_facts` |
| `attempt.logged` | a phase 3 attempt entry (OAP `oa_rep`) | `metric`, `side`, `success`, checklist answers, `session_id?` |
| `pause` | Pause sheet confirm (14.7) | `reason_code` (holiday, illness, injury, other) |
| `resume` | Resume sheet confirm | `gap_days` |
| `abandon` | Abandon dialog confirm | `reason_code?` |
| `manual.override` | "Correct a value" in the dashboard menu (14.2; CHALLENGE_ENGINE section 4.9) | `target`, `value`, `reason_code` (required); an optional note stays in the local History entry and never enters the payload |
| `definition.migrated` | "Update" in the definition-update preview (14.2; CHALLENGE_ENGINE section 4.12) | `from_version`, `to_version`, `var_map` result, `preview_hash` |

A `pain.reported` retraction ("Undo (wrong tap)" within 10 s) is written as a supersession: a new
`pain.reported` event with `retracted: true` and `supersedes_event_id`, so the fold replays without the
pain (CHALLENGE_ENGINE section 4.11).

### 20.12 Sync, export and platform-owned events (`sync`, `export`, `system`)

UI-triggered:

| event_type | Category | Emitted when | Payload |
|---|---|---|---|
| `sync_now_tapped` | sync | "Sync now" (15.11) | `outbox_depth` |
| `export_completed` | export | "Export all data", the monthly export (F4) or "Export review bundle" (15.10) | `export_id`, `kind` (monthly_full, manual_full, review_bundle), `rows`, `bytes`, `from?`, `to?`, `notes_included?` (the last three for review bundles) |

Emitted by the platform layer (triggers and payloads in ANDROID.md, names and keys in the DATA_MODEL
section 9.8.1 registry; listed so the catalogue is complete): `sync_run`, `sync_conflict`, `sync_error`,
`sync_dead_letter` (sync); `calendar_classified` (calendar); `session_idle_prompted` (lifecycle);
`app_startup_failed`, `app_crash`, `fgs_started`, `fgs_stopped`, `fgs_restart_failed`,
`notification_dismissed`, `notification_promotion_state`, `permission_state`, `reminder_scheduled`,
`tick_catchup` (system); `platform_config_logged`, `params_override_rejected` (config). Reminder
deliveries and their actions are logged as `notification_posted` (with `latency_ms`) and
`notification_action`. `permission_state` is the snapshot taken at each app start; `permission_result`
(20.9) is the result of a request in onboarding.

### 20.13 Names adopted from the DATA_MODEL 9.8.1 registry (C39)

The registry is canonical (C39), so this document uses its names. For traceability, the names that
earlier drafts of this document used and that the merge renamed:

| Earlier name in this document | Registry name | Reason (registry) |
|---|---|---|
| `time_overrun_shown`, `summary_shown`, `addon_block_skipped` | `session_overrun_shown`, `session_summary_shown`, `block_addon_skipped` | area prefix |
| `double_tap_dropped`, `count_aloud_toggled`, `ecc_pacing_started` | `log_double_tap_dropped`, `hold_count_aloud_toggled`, `hold_ecc_pacing_started` | area prefix |
| `custom_exercise_created`, `alternatives_opened`, `calendars_selected` | `exercise_custom_created`, `exercise_alternatives_opened`, `calendar_selection_saved` | area prefix |
| `use_suggested_tapped`, `use_mine_anyway_toggled` | `warning_use_suggested`, `warning_use_mine_toggled` | area prefix |
| `rule_suggestion_shown`, `rule_suggestion_applied`, `rule_suggestion_kept` | `challenge_rule_applied`, `challenge_rule_undone` | D14: challenge in-session rules apply automatically with Undo (C46) |
| `p_rule_viewed`, `p_rule_decided` (payload `item_id`, decisions `accept` / `decline`) | `challenge_proposal_viewed`, `challenge_proposal_decided` (payload `proposal_id`, decisions `approved` / `declined`) | area; decisions per proposal id (C48) |
| `cutover_confirmed` | `challenge_cutover_confirmed` | area prefix |
| `data_exported`, `review_bundle_exported` | `export_completed` with `kind` | one event per export |
| `session_idle_prompt` (ANDROID) | `session_idle_prompted` | event verb |
| `reminder_delivered`, `reminder_action` (listed as platform events) | `notification_posted` (with `latency_ms`), `notification_action` | reminders are logged as notifications |
| `calendar_event_written` with a `result` key | `calendar_event_written` (executed ops) and `calendar_write_failed` (refused or failed ops, with `error_class`) | the event type carries the outcome |
| `promoted_notification_state`, `reliability_test`, `alarm_capability` (ANDROID or the first registry draft) | `notification_promotion_state`, `timer_selftest`, `exact_alarm_status` | area or same content as the UX event |
| `instance_key` as the first 16 hex characters of a hash | the full 64-hex `calendar_events.instance_key` | telemetry joins to stored events |

---

## 21. Screen to module map

For every screen and sheet: the engine modules it reads (with the `recommendations.kind` snapshot
where one exists, DATA_MODEL.md 10.1), the ui modules and controllers it uses, what it writes or
triggers, and the pipeline layer (L0 normalise, L1 safety, L2 challenge, L3 accounting / estimation,
L4 targets, L5 planning, L6 session build, L7 in-session, L8 post-session). Events are listed in the
screen sections and in section 20.

### 21.1 Tabs and full-screen flows

| Screen id | Reads (engine, snapshot kind) | ui modules, controllers | Writes or triggers | Layers |
|---|---|---|---|---|
| `onboarding.signin`, `onboarding.rejected` | Supabase auth, access probe (a read on a seeded reference table, ANDROID 10.4; `:data`) | TelemetryWriter | session token; nothing else until allowlisted | none |
| `onboarding.about` | none | | disclaimer acknowledgement (profile) | none |
| `onboarding.profile` | `bodyweight` | | profile, first `bw` observation | L0 |
| `onboarding.permissions` | none (platform checks) | RestTimerController (self-test) | permission state, OEM confirmation, self-test result | none |
| `onboarding.equipment` | `load` (loadable sets preview), `catalog` (`requires_station`) | | versioned gym profile, `gym_equipment` | L0 |
| `onboarding.setrep` | `warnings` (preference-only evaluation) | ui.warndisplay | `rep_preferences` | L6 (validation only) |
| `onboarding.goals` | `targets` (preview), `challenge.contract` (covered groups), `warnings` | ui.warndisplay | `muscle_goals` | L4 |
| `onboarding.calendars` | `calendar.classify` (current ISO week preview) | | calendar selection, per-event overrides | L0 |
| `onboarding.defaults` | none | | settings | none |
| `onboarding.challenge` | `challenge.cds` (published definitions) | | opens `challenge.setup` | L2 |
| `today` | `today` (`today.options`), `weekplan` (`weekplan.plan`), `calendar.classify`, `challenge.calendar`, `challenge.plan` (`challenge.weekplan`), `challenge.materialize` (habits, preview), `safety` (`safety.state`), `select` / `rx` / `time` (option previews, `session.plan`), `bodyweight` | ui.todaycard, ui.warndisplay, ui.prompts (weigh-in row) | session start (with Readiness sheet), habit quick-done, Plan it, budget selection | L0-L6 |
| `week` | `weekplan`, `challenge.plan`, `challenge.contract` (`challenge.contract`), `calendar.classify`, `challenge.calendar`, `today` (today's row), `setcredit` (`accounting.week`), `targets` (`targets.weekly`), `warnings` (`warnings.evaluation`), `runs`, `readiness` (day sheet preview) | ui.volumebars, ui.warndisplay, ui.todaycard (week strip shares its symbol rules) | calendar writes (Plan it, adopt, rename), classification corrections | L0-L5 |
| `session.live` | `challenge.materialize` (`challenge.session_plan`), `challenge.eca` (in-session rules), `select` / `elig` / `order` / `time` (`session.plan`), `rx` (`rx.prescription`), `warmup` (`warmup.sets`), `rest`, `autoreg` (`autoreg.suggestion`), `reason`, `substitute`, `pref`, `injury`, `safety`, `load`, `bodyweight`, `readiness` (`readiness.snapshot` at start), `warnings` | ui.prefill, ui.reasonchips, ui.prompts (pre-session sheet), ui.warndisplay, RestTimerController, HoldTimerController, TelemetryWriter | `workout_sessions`, `session_blocks`, `session_exercises`, `set_logs`, `wellness_logs`, `challenge_events` (`session.started`, `set.logged`, `readiness.checked`, `pain.reported`), rest timers, notifications | L1, L6, L7 |
| `session.summary` | post-session pipeline (L8): `sse` (`estimation.sse`), `rcm`, `rircal`, `hold` (`estimation.hold`), `stall`, `rx` (next prescriptions), `challenge.eca` (rule firings after `session.completed`), `calendar.classify` (link) | ui.progress (PRs), ui.prompts (post items) | sRPE and pain `wellness_logs`, calendar link, `challenge_events` `session.completed` (written at Finish) | L8 |
| `exercises` | `catalog`, `pref`, `elig` (availability) | | preference changes | L6 (filters only) |
| `exercises.detail` | `catalog`, `pref`, `elig` (coverage gap on Never), `substitute` (alternatives without "occupied" context), `sse`, `hold`, `load`, `rest`, `warnings`, `challenge.cds` (used by) | ui.progress (sparkline), ui.warndisplay | preference, per-exercise set/rep override | L3, L6 |
| `exercises.custom` | `catalog` (CAT-1 prototype and validator) | | custom exercise (`exercises`, `exercise_stimulus`) | L0 |
| `challenges` | `challenge.cds`, `challenge.enrollment`, `challenge.contract` (conflict check for a second challenge) | | opens setup or dashboard | L2 |
| `challenges.dashboard` | `challenge.enrollment`, `challenge.plan`, `challenge.eca` (gate evaluation), `rcm`, `sse`, `bodyweight`, `challenge.cds` (newer definition versions) | ui.progress (gate rows) | pause / resume / abandon, Correct a value (`manual.override`), definition update (`definition.migrated`), all as `challenge_events`; Report pain | L1, L2 |
| `challenges.rules` | `challenge.cds` (rules, tags), `challenge.eca` (firing counts from `challenge_rule_events`) | | P decisions (`rules.approval_changed`) | L2 |
| `challenges.history` | `challenge_rule_events`, `challenge_events` | | none | L2 |
| `challenges.tests` | `metric_observations`, `challenge.eca` (recalculation reports) | | none | L2 |
| `challenge.setup` | `challenge.cds` (definition, onboarding steps, proposals, import spec), `challenge.materialize` (two-week review), `challenge.contract`, `warnings`, `challenge.calendar` (adopt list) | | `enrollment.created`, `rules.approval_changed`, adopt, cutover confirmation | L2 |
| `test.wizard` | `challenge.materialize` (test template T1), `challenge.enrollment` (validity), `load` (belt and pin loadables), `bodyweight` | RestTimerController, HoldTimerController, ui.prefill | test session, sets (`test`, `attempt`), `metric_observations`, `test.completed`; then L8 recalculation report (`challenge.eca`, `rcm`, `sse`) | L1, L2, L7, L8 |
| `you` | sync state (`:data`), reliability status (ANDROID.md 15.5), versions | | none | none |
| `you.progress` | `sse` (states and per-set logged e1RM), `rcm`, `hold`, `metric_observations`, `bodyweight` (EWMA series), `setcredit` + `targets` (weekly volume history), `challenge.eca` (gate line) | ui.progress, ui.volumebars | none | L3, L4 |
| `you.history`, `you.session_detail` | session store, `recommendations` (Why per exercise), `challenge_events` | ui.prefill (edit steppers), ui.reasonchips | set edits, added sets, deletes, reopen, calendar link, quick-check edits, `session.amended` | L8 rebuild |
| `you.preferences` | `targets`, `warnings`, `bodyweight` | ui.warndisplay | goals, rep preferences, settings | L4 |
| `you.equipment` | `load` | | gym profile versions, calibration changes | L0 |
| `you.calendars` | `calendar.classify` (preview with features and scores), `challenge.calendar` (app-written events, adopt list) | | selection, overrides, removals | L0, L2 |
| `you.rest` | rest-alarm latency statistics (`session_events`) | RestTimerController (self-test) | settings | none |
| `you.prompts` | `local_prompt_state` | ui.prompts (state display) | settings, prompt state reset | none |
| `you.appearance` | none | | settings | none |
| `you.transparency` | `algorithm_versions`, params, catalog and definition versions | TelemetryWriter (bundle export) | review bundle export (`exports`) | none |
| `you.data`, `you.account` | outbox and sync state (`:data`) | | sync now, exports, sign out (blocked while the outbox is non-empty) | none |

### 21.2 Sheets

| Sheet | Reads | ui modules | Writes |
|---|---|---|---|
| `sheet.why` | `recommendations` (trace of the subject), `warnings`, `challenge_rule_events` (bindings), `challenge.cds` (rule text, tag) | ui.warndisplay (actions) | use suggested / use mine anyway, trace export |
| `sheet.pain` | `safety` (enforcement preview), `challenge.cds` (symptom kinds of the active pain rule), `body_regions`, `symptom_codes` | ui.prompts (`ui.prompts.pain_severity_map`, C64) | PainEvent `wellness_logs`, `pain.reported`, safety evaluation (L1) |
| `sheet.readiness` | session plan groups, `setcredit` (last hard sets) | ui.prompts | `wellness_logs` (readiness, elbow_check, soreness), `readiness.checked` |
| `sheet.skip` | `reason_codes` | ui.reasonchips | session exercise status, reason |
| `sheet.swap` | `substitute` (top 3, SUB-1), `rx` (start prescription), `pref`, `elig` | | substitution (`session_exercises.substitutes_id`) |
| `sheet.add_exercise` | `catalog`, `select` (suggested for remaining deficits), `elig` / `injury` / `challenge.contract` (Not today) | | new session exercise, `rule_override` |
| `sheet.keypad` | `load` (loadable set, snapping) | ui.prefill (`snap`) | none (returns a value) |
| `sheet.weigh_in` | `bodyweight` | ui.prompts | `metric_observations` (`bw`) |
| `sheet.template` | `today`, `challenge.plan` (slot statuses), `select` (family combinations on demand), `warnings`, `safety` | ui.todaycard (ranked options) | session start |
| `sheet.day` | `weekplan`, `calendar.classify`, `readiness` (family preview) | | Plan it, adopt, remove app event, correct classification |
| `sheet.cal_event` | `calendar.classify` | | override, adopt |
| `sheet.notes` (warnings list) | `warnings` | ui.warndisplay | dismissals |
| `sheet.finish` | session store | ui.reasonchips (`SESSION_END`) | not-done reasons, Finish |
| `sheet.set_edit` | `load` | ui.prefill, ui.reasonchips | set row edits |
| `sheet.session_note` | none | | `workout_sessions.notes` (never telemetry) |

### 21.3 Notifications

| Notification | Built from |
|---|---|
| N-SESSION, N-REST-DONE | RestTimerController, session store, `rx` / `challenge.materialize` (next-set text, including a change by a challenge in-session rule) |
| N-IDLE | session store, the session service's idle protection (ANDROID section 11.5) |
| N-HABIT | `challenge.materialize` (habits for the day, skip and scale rules), ReminderScheduler (ANDROID.md 17) |
| N-WEIGH | `bodyweight` (staleness), ui.prompts |
| N-TEST-DAY | `challenge.plan` |
| N-PROGRAM, N-SAFETY, N-CELEBRATE | `challenge.eca` rule notifications (deduplicated per message per day, C21), `safety` |
| N-EXPORT, N-SYNC | `:data` (export dates, outbox, dead letters) |

---

## 22. Interfaces the UI requires from engine modules

These are the fields and calls the screens of this document need, written as Kotlin-style signatures.
They are a *requirement* on the owning specifications (`docs/ALGORITHMS.md`,
`docs/ALGORITHMS_PLANNING.md`, `docs/CHALLENGE_ENGINE.md`, `docs/DATA_MODEL.md`, `docs/ANDROID.md`):
the owning spec may choose other names, but it must provide every field listed here, or the gap is an
open issue for that spec. Every engine call goes through the engine gateway (ANDROID.md 7.6), which
stores the `recommendations` snapshot (C24) and returns `Snapshot<T>`; the UI only ever sees outputs.

### 22.1 Shared boundary types (C22)

```kotlin
package dev.fitnessapp.algo.core

@JvmInline value class Kg(val centi: Long)          // 0.01 kg grid, HALF_EVEN on construction (C22)
@JvmInline value class Seconds(val deci: Long)      // 0.1 s grid
@JvmInline value class Ratio(val e4: Long)          // 1e-4 grid
enum class InputUnit { KG, LB, LEVEL, FINGERS }     // E4
data class NativeLoad(val value: java.math.BigDecimal, val unit: InputUnit)
enum class Side { BOTH, L, R }                      // E1
enum class PrecedenceClass { SAFETY, CHALLENGE, USER, GENERAL }   // P11, 19.0
enum class EvidenceGrade { STRONG, MODERATE, WEAK, EXPERT_OPINION, PROGRAM_RULE, ENGINEERING_FACT }

sealed interface ParamValue {                        // typed template parameters, never free text
    data class Num(val v: java.math.BigDecimal, val unit: String?) : ParamValue
    data class Code(val code: String) : ParamValue  // group id, family, slot, weekday code
    data class Time(val at: java.time.Instant) : ParamValue
    data class Date(val d: java.time.LocalDate) : ParamValue
}
data class Reason(val code: String, val cls: PrecedenceClass, val weight: Double,
                  val params: Map<String, ParamValue>, val ruleId: String? = null)

data class Snapshot<T>(val recommendationId: java.util.UUID, val kind: String,      // DATA_MODEL 10.1
                       val moduleId: String, val moduleVersion: String, val engineVersion: String,
                       val paramsHash: String, val inputsHash: String, val output: T)

data class Citation(val label: String, val url: String?, val grade: EvidenceGrade, val preprint: Boolean)
```

### 22.2 Core: `clock`, `load`, `bodyweight`, `calendar.classify`, `safety`

```kotlin
interface ClockWeek {                                                    // clock (C31)
    fun localDate(at: Instant, zone: ZoneId): LocalDate
    fun isoWeek(date: LocalDate): IsoWeek                               // "2026-W41" (E52)
    fun weekDays(week: IsoWeek): List<LocalDate>                        // Monday..Sunday
    fun offsetMinutes(at: Instant, zone: ZoneId): Int                   // for session_events.tz_offset_min
    fun mesocycleWeek(date: LocalDate, contract: ChallengeContractView?): Int
}

interface LoadModule {                                                   // load (C1, C2, LOAD-1)
    fun loadableSet(exerciseId: UUID, mode: LoadMode, equipment: EquipmentProfile): LoadableSet
    fun effectiveKg(input: LoadInput, bodyweight: Kg?, fBw: Ratio?, calibration: AssistCalibration?): Kg
    fun assistKg(pinNative: BigDecimal, calibration: AssistCalibration): Kg       // pin x unit_kg x ratio + offset
    fun breakdown(addedKg: Kg, scheme: LoadScheme): List<Kg>?                     // belt plates or plates per side
    fun localStep(at: NativeLoad, set: LoadableSet, direction: Int): BigDecimal    // used by ui.prefill deviation
}
data class LoadableSet(val values: List<BigDecimal>,      // sorted ascending, native unit
                       val unit: InputUnit, val harderIsLower: Boolean,   // true for assisted pins
                       val maxGap: BigDecimal, val maxOrdinal: Int?, val confirmed: Boolean)

data class BodyweightSummary(val ewmaKg: Kg?, val lastWeighInKg: Kg?, val lastWeighInAt: Instant?,
                             val isStale: Boolean, val staleDays: Int?)              // bodyweight (C32)

data class ClassifiedEvent(                                             // calendar.classify (C20) + challenge.calendar
    val instanceKey: String, val start: Instant, val end: Instant, val durationMin: Int?,
    val kind: EventKind,                       // CHALLENGE_SESSION, PLANNED_GYM, RUN, BUSY, IGNORED
    val slot: String?, val familyType: String?, val score: Ratio,
    val marker: MarkerStatus,                  // APP_MARKED, UNMARKED
    val adoptable: Boolean, val conflict: ConflictKind?,   // C36: TEST_POSTPONED_APP_REWRITTEN, TEST_POSTPONED_NEEDS_RENAME
    val linkedSessionId: UUID?, val linkedToFinishedSession: Boolean,
    val displayTitle: String)                  // shown on screen, never logged (19.10)

data class SafetySummary(                                               // safety (C33)
    val protocols: List<ActiveProtocol>,      // e.g. elbow, day 3 of 14, rule id safety.elbow.stop
    val hardRestToday: Boolean, val forbiddenTags: Set<String>,
    val bannerRuleId: String?, val disclaimerRequired: Boolean = true)
data class ActiveProtocol(val code: String, val ruleId: String, val day: Int, val days: Int,
                          val consequence: List<Reason>)
interface Safety {
    fun preview(event: PainEventDraft, state: SafetyState): SafetyDecision     // what the Pain sheet will enforce
}
data class SafetyDecision(val endCurrentExercise: Boolean, val removedSessionExerciseIds: List<UUID>,
                          val protocolStarted: ActiveProtocol?, val messages: List<Reason>)
```

### 22.3 Challenge modules

```kotlin
data class ChallengeTodayView(val enrollmentId: UUID, val slotToday: String?, val mode: EnrollmentMode,
                              val blockIndex: Int, val blockWeek: Int, val blockLength: Int,
                              val testValid: Boolean?, val testInvalidReason: String?,
                              val postponeAllowed: Boolean, val habitsToday: List<HabitToday>)

data class HabitToday(val habitId: String, val time: LocalTime, val state: HabitState,  // DUE, DONE, SKIPPED, SCALED, OFF
                      val reason: Reason?, val quickDoneAllowed: Boolean, val measureToday: Boolean,
                      val prescription: List<SetTarget>)

data class ChallengeDashboard(                                          // challenge.enrollment + challenge.plan + challenge.eca
    val enrollmentId: UUID, val status: EnrollmentStatus, val mode: EnrollmentMode,
    val phase: PhaseRef, val phases: List<PhaseRef>, val blockIndex: Int, val blockWeek: Int, val blockLength: Int,
    val nextTest: NextTest?, val week: List<SlotStatus>, val gate: GateStatus,
    val pendingProposals: Int, val pausedSince: LocalDate?)
data class NextTest(val at: Instant, val basis: TestDateBasis /* BLOCK_RELATIVE, FIXED_TABLE */,
                    val precondition: Reason?)
data class SlotStatus(val slot: String, val date: LocalDate?, val state: SlotState /* DUE, DONE, PLANNED, NOT_ALLOWED_TODAY, REPLACED */,
                      val reason: Reason?)

data class GateStatus(val phaseId: Int, val criteria: List<GateCriterion>, val metCount: Int, val total: Int)
data class GateCriterion(
    val id: String, val label: Map<String, String>, val metric: String,           // label from the definition (en, nl)
    val sides: List<Side>, val valueType: ValueType, val better: Better,
    val threshold: ThresholdParts,                     // ratio, gapKg, fixed value; evaluated by thresholdAt()
    val lastValid: Map<Side, BigDecimal?>, val lastValidDate: LocalDate?, val lastValidBodyweight: Kg?,
    val met: Map<Side, Boolean>,                       // evaluated on the test's own bodyweight (D7)
    val latestNonValid: NonValidObservation?,          // value, date, reason code ("not_a_test_day", "3rm_only", "old_protocol")
    val extras: GateExtras?) {
    fun thresholdAt(bodyweight: Kg): BigDecimal         // decimal arithmetic (C21, C22); the forward threshold (C62, CHALLENGE_ENGINE 10.1)
}
data class GateExtras(val multiplier3rmValue: Kg?,      // the x1.10 value (D7 transparency)
                      val ci80: Pair<Kg, Kg>?,          // 80 % range of the gate value
                      val kUsed: BigDecimal?,           // RCM-1 k of weighted_pullup
                      val sseEstimate: Kg?, val sseCi80: Pair<Kg, Kg>?)    // training estimate (C4)

data class ChallengeSessionPlan(val slot: String, val templateId: String, val mode: EnrollmentMode,
                                val blocks: List<PlannedBlock>, val excluded: List<ExcludedItem>,
                                val estMinutes: Int, val addOn: AddOnSlot?)          // challenge.materialize, kind challenge.session_plan
data class PlannedItem(val key: String, val exerciseId: UUID, val role: String, val sets: List<SetTarget>,
                       val restS: Int, val betweenSidesS: Int?, val perSide: Boolean, val sideOrder: List<Side>,
                       val sideRule: SideRule?, val flags: Set<String>, val stopCondition: Map<String, String>?,
                       val roundsAttempts: Pair<Int, Int>?, val note: Map<String, String>?,
                       val progressionMode: ProgressionMode)                          // RULE for challenge items (C15)
data class ExcludedItem(val key: String, val reason: Reason)                          // "skipped today: elbow trace"

interface ChallengeInSession {                         // challenge.eca in-session rules (CHALLENGE_ENGINE 6.10)
    fun afterSet(set: LoggedSet, state: SessionChallengeState): List<RuleApplication>
    fun undo(applicationId: UUID, reasonCode: String?): UndoResult        // restores the previous planned rows (C46)
}
data class RuleApplication(val applicationId: UUID, val ruleId: String, val tag: RuleTag /* A, P */,
                           val proposalId: String?, val itemKey: String,
                           val action: RuleAction,   // SUGGEST (applied automatically to the remaining sets, D14),
                                                     // CAP_TARGET (the side cap, 11.7.2), NOTIFY (info line)
                           val field: String?, val from: ParamValue?, val to: ParamValue?,
                           val setsAffected: Int, val loadJumpWarning: Violation?,     // WE-S2 at WARN (D13, C45)
                           val message: Reason)                                       // "Rule: ... · Undo" (11.10)

data class RuleFiring(val ruleId: String, val tag: RuleTag, val stage: RuleStage, val outcome: RuleOutcome, // E28, E29
                      val itemKey: String?, val from: ParamValue?, val to: ParamValue?,
                      val reason: Reason, val bindings: Map<String, ParamValue>, val at: Instant)

data class ProposalItem(val proposalId: String, val group: String, val title: Map<String, String>,
                        val ifDeclined: Map<String, String>, val whyProposed: Map<String, String>,
                        val switches: List<String>, val supersedes: List<String>,   // rule ids (C48)
                        val evidence: EvidenceGrade,
                        val decision: Decision? /* APPROVED, DECLINED; null = undecided (inactive, D8) */)

data class TestProtocol(val steps: List<TestStep>, val validity: TestValidity)       // template T1 (F10 order + P items)
data class TestStep(val key: String, val metric: String?, val exerciseId: UUID?, val perSide: Boolean,
                    val sideOrder: List<Side>, val attempts: Int?, val record: RecordRule, val restS: Int,
                    val protocolText: Map<String, String>, val skipReason: Reason?)
data class RecalcReport(val valid: Boolean, val gateValue: GateExtras?, val loadChanges: List<RuleFiring>,
                        val gateMet: Int, val gateTotal: Int, val nextBlockStart: LocalDate?, val nextTest: NextTest?)
data class PauseConsequence(val gapDays: Int, val consequence: List<Reason>)          // pause and resume sheets

data class ChallengeContractView(val ownsGroups: Set<String>, val blockedDays: List<BlockedDay>,
                                 val protectedWindows: List<ProtectedWindowView>, val addOnSlots: List<AddOnSlot>,
                                 val gapRuleScope: String)                             // challenge.contract (C11, C12)
```

### 22.4 Accounting, estimation, targets

```kotlin
data class WeekAccounting(val isoWeek: IsoWeek, val groups: Map<String, GroupCredit>)  // setcredit, accounting.week
data class GroupCredit(val vhypDone: Double, val vstrDone: Double, val fatigueDone: Double, val committed: Double)

data class WeeklyTargets(val isoWeek: IsoWeek, val groups: Map<String, GroupTarget>)   // targets, targets.weekly
data class GroupTarget(val goal: Goal, val focus: Boolean, val target: Double, val mrv: Double?,
                       val capS: Double?, val coveredByChallenge: Boolean, val reentry: Boolean, val deload: Boolean)

data class FamilyReadiness(val family: Family, val readiness: Ratio, val readyAt: Instant?)   // readiness (C5)

data class SseState(val exerciseId: UUID, val side: Side, val date: LocalDate,         // sse, estimation.sse
                    val mKg: Kg, val ci80Lo: Kg, val ci80Hi: Kg, val gPerWeek: Ratio)
data class SetEstimate(val setId: UUID, val e1rmKg: Kg?, val e1rmAddedKg: Kg?, val kUsed: BigDecimal?)
data class HoldState(val exerciseId: UUID, val side: Side, val capacityS: Seconds, val date: LocalDate)
data class StallStatus(val exerciseId: UUID, val status: String /* progressing, stall, regress */, val action: Reason?)
```

### 22.5 Planning: `today`, `weekplan`, `allocator`

```kotlin
data class TodayOutput(                                                 // today, today.options
    val verdict: Verdict,                     // TRAIN, REST_SOFT, REST_HARD (C53; ALGORITHMS_PLANNING section 9)
    val primaryOptionId: String?,             // the engine's choice; the card does not re-sort (ALGORITHMS_PLANNING 19.1)
    val options: List<TodayOption>,           // ranked; scores on the 1e-4 grid
    val restReasons: List<Reason>, val excluded: List<Exclusion>)
data class TodayOption(val optionId: String, val type: SessionType, val score: Double, val estMinutes: Int,
                       val reasons: List<Reason>, val template: TemplateRef)
// SessionType: LEGS, PUSH, PULL, PUSH_LEGS, PULL_LEGS, UPPER, FULL, CORE, CHALLENGE(slot), CHALLENGE_ADDON(slot, family), REST
data class Exclusion(val family: Family, val reason: Reason)                       // "Pull: protected window before A"

data class WeekPlan(val isoWeek: IsoWeek, val days: List<DayPlan>, val recommendedN: Int,
                    val maxVisits: Int, val plannedVisits: Int,
                    val projectedRemaining: Map<String, Double>)                   // per group, for ui.volumebars
data class DayPlan(val date: LocalDate, val kind: DayKind,
                   // DONE, CHALLENGE, TEST, RECOMMENDED, PLANNED_USER, REST_SOFT, REST_HARD, FREE_LIMITED, BUSY, RUN, CONFLICT
                   val type: SessionType?, val slot: String?, val estMinutes: Int?, val reasons: List<Reason>,
                   val allowedFamilies: Set<Family>, val suggestedStart: Instant?, val calendarInstanceKeys: List<String>)
data class NextFixedSession(val at: Instant, val label: Reason)                     // for DONE ("Next: Sun 11 Oct, Test day 13:00")
```

### 22.6 Session build and in-session modules

```kotlin
interface SessionBuilder {        // select + elig + order + time + rx + warmup + rest + warnings, one call per block (L6)
    fun build(template: TemplateRef, budget: Budget, ctx: BuildContext): Snapshot<SessionPlan>
}
data class Budget(val minutes: Int?, val source: BudgetSource)                     // null = no limit (90+ chip)
data class SessionPlan(val blocks: List<PlannedBlock>, val estMinutes: Int, val budgetMin: Int?,
                       val trimmed: List<TrimLine>, val coreDoesNotFit: Boolean, val shortVersion: SessionPlan?)
data class TrimLine(val exerciseId: UUID, val setsRemoved: Int, val removed: Boolean, val reason: Reason)

data class Prescription(val sessionExerciseId: UUID, val mode: ProgressionMode /* MODEL, DOUBLE, RULE, FIXED, NONE */,
                        val sets: List<SetTarget>, val capsApplied: List<String>, val why: List<Reason>)   // rx, rx.prescription
data class SetTarget(val setIndex: Int, val side: Side, val setKind: SetKind,
                     val reps: Int?, val repMin: Int?, val repMax: Int?, val rir: BigDecimal?,
                     val load: NativeLoad?, val effectiveKg: Kg?, val durationS: Seconds?, val durationLoS: Seconds?,
                     val eccS: Seconds?, val toFailure: Boolean, val source: PlannedSource)

data class NextTimeLine(val exerciseId: UUID, val itemKey: String?, val from: ParamValue?, val to: ParamValue?,
                        val stays: Boolean, val reason: Reason, val ruleId: String?)   // Summary "Next time" (rx, stall, challenge.eca)

interface WarmupModule { fun sets(item: PlannedItem, ctx: BuildContext): Snapshot<List<SetTarget>> }   // warmup.sets

interface RestModule {                                                     // rest (C18)
    fun after(set: LoggedSet, ctx: RestContext): RestDuration
    fun betweenSides(item: PlannedItem, ctx: RestContext): RestDuration
}
data class RestDuration(val seconds: Int, val source: RestSource /* REST_MODULE, CHALLENGE, PROTOCOL */,
                        val restClass: String /* E41 */, val modifiers: List<String>, val why: List<Reason>)

interface ReasonModule {                                                   // reason (REASON-1, C16)
    fun readinessSuggestion(level: ReadinessLevel, plan: SessionPlan): SessionSuggestion?   // "1 set fewer per exercise?"
    fun painStopThreshold(): Int                                            // 4 (out of 10)
}

interface AutoregModule {                                                  // autoreg (AUTO-1 + UI-06 trigger, C16)
    fun afterSet(set: LoggedSet, ctx: AutoregContext): Snapshot<AutoregDecision>   // autoreg.suggestion when shown
}
data class AutoregDecision(val show: Boolean, val trigger: String?, val deltas: List<BigDecimal>,
                           val proposed: ProposedChange?, val stop: Reason?)
data class ProposedChange(val load: NativeLoad?, val reps: Int?, val sets: Int?, val why: List<Reason>)

interface SubstituteModule {                                               // substitute (SUB-1)
    fun candidates(sessionExerciseId: UUID, reason: String, ctx: SubContext, limit: Int = 3): List<SubCandidate>
    fun reorderHint(sessionExerciseId: UUID, ctx: SubContext): ReorderHint?   // "Do leg curl now, come back after"
    fun alternatives(exerciseId: UUID, limit: Int = 5): List<SubCandidate>    // exercise detail, no occupied context
}
data class SubCandidate(val exerciseId: UUID, val score: Ratio, val similarity: Ratio, val tags: Set<String>,
                        val dislikedFallback: Boolean, val start: List<SetTarget>)

interface AddExerciseModule {                                              // elig + injury + challenge.contract + select
    fun candidates(query: String?, ctx: SessionContext): List<AddCandidate>
}
data class AddCandidate(val exerciseId: UUID, val available: Boolean, val forbidden: Reason?,   // red "Not today"
                        val suggestedFor: List<String>, val recent: Boolean)

interface WarningsModule {                                                 // warnings (C14, one WarningEngine)
    fun evaluate(scope: WarningScope): Snapshot<List<Violation>>             // warnings.evaluation
    fun evaluatePreference(draft: PreferenceDraft): List<Violation>         // onboarding and preferences notes
}
data class Violation(val ruleId: String, val severity: Severity /* INFO, WARN, STRONG, SAFETY */,
                     val entity: String, val magnitude: Ratio, val evidence: EvidenceGrade,
                     val citations: List<Citation>, val numbers: Map<String, ParamValue>,
                     val alternative: ProposedChange?, val message: Reason)
```

### 22.7 Selection and catalog

```kotlin
interface CatalogModule {                                                  // catalog (CAT-1)
    fun search(query: String, filters: CatalogFilters, locale: Locale): List<CatalogRow>    // EN + NL names, diacritic-insensitive
    fun detail(exerciseId: UUID): ExerciseDetail
    fun prototype(pattern: String, equipment: String): CoefficientDraft
    fun validateCustom(draft: CustomExerciseDraft): List<ValidationError>
}
data class ExerciseDetail(val id: UUID, val nameEn: String, val nameNl: String?, val equipment: String,
                          val pattern: String, val groupCoefficients: Map<String, BigDecimal>,
                          val regionCoefficients: Map<String, BigDecimal>, val loadMode: LoadMode,
                          val fBw: Ratio?, val flags: Set<String>, val unilateral: Boolean, val timed: Boolean,
                          val requiresStation: String?, val usedByChallenges: List<String>)
interface PreferenceModule { fun multiplier(p: Preference): BigDecimal }   // pref (C26): 0 / 0.6 / 1.0 / 1.15 / 1.495
interface EligibilityModule {                                              // elig (ELIG-1)
    fun coverageGapIf(exerciseId: UUID, newPreference: Preference): Violation?      // COVERAGE_GAP note on Never
    fun unlockReason(sessionExerciseId: UUID): Reason?                             // "Only option for adductors"
}
```

### 22.8 ui modules and platform controllers (provided by `:app`)

The ui modules are specified in ALGORITHMS_PLANNING section 19 (C53); the signatures below are the
calls the screens make and must match the owner's inputs and outputs. The controllers follow ANDROID
sections 12 to 14 (C54).

```kotlin
package dev.fitnessapp.app.uidet

data class UiResult<T>(val value: T, val moduleId: String, val moduleVersion: String, val inputsHash: String)

object UiTodayCard   { fun compose(input: TodayCardInput, p: TodayCardParams): UiResult<TodayCard> }
object UiPrefill     { fun prefill(slot: SetSlot, ctx: PrefillContext, p: PrefillParams): UiResult<Prefill>
                       fun step(v: StepValue, dir: Int, kind: StepKind, set: LoadableSet?, target: SetTarget?): StepValue
                       fun snap(typed: BigDecimal, set: LoadableSet): SnapResult
                       fun deviation(draft: SetDraft, target: SetTarget, set: LoadableSet?, p: PrefillParams): Deviation
                       fun rirChipLayout(widthDp: Float, fontScale: Float, p: PrefillParams): ChipLayout }
object UiReasonChips { fun chipsFor(context: ChipContext, exercise: ExerciseFlags, vocab: ReasonVocabulary, p: ReasonChipParams): UiResult<ChipSet>
                       fun doLater(order: List<SessionExerciseRef>, exerciseId: UUID, p: ReasonChipParams): MoveResult }
object UiPrompts     { fun plan(ctx: PromptContext, p: PromptParams): UiResult<PromptPlan>
                       fun update(state: PromptState, item: PromptItem, answered: Boolean, p: PromptParams): PromptState
                       fun weighInDue(bw: BodyweightSummary): Boolean }
object UiVolumeBars  { fun rows(input: VolumeInput, p: VolumeParams): UiResult<VolumeLayout> }
object UiWarnDisplay { fun display(vs: List<Violation>, scope: ScreenScope, store: DismissalStore, now: Instant, p: WarnParams): UiResult<WarnDisplay> }
object UiProgress    { fun chartSeries(req: ChartRequest, data: ProgressData, now: Instant, zone: ZoneId, p: ProgressParams): UiResult<ChartSeries>
                       fun personalBests(sets: List<LoggedSet>, history: PrHistory, p: ProgressParams): UiResult<List<PersonalBest>>
                       fun gateRows(gate: GateStatus, bw: BodyweightSummary): UiResult<List<GateRow>> }

package dev.fitnessapp.app.timer
interface RestTimerControl {
    fun observe(): Flow<RestTimerState?>
    suspend fun startAfter(setId: UUID, duration: RestDuration, purpose: TimerPurpose)
    suspend fun adjust(deltaS: Int, via: AdjustVia)           // ±15 s (tap, notification) or ±60 s (long-press), ANDROID 12.3
    suspend fun skip(via: SkipVia)
    fun display(state: RestTimerState, nowWall: Long, nowElapsed: Long, bootCount: Int): RestDisplay   // pure
}
interface HoldTimerControl {
    fun observe(): Flow<HoldTimerState>
    fun start(mode: HoldMode, targetS: Seconds?, rangeLoS: Seconds?, previousBestS: Seconds?, leadInS: Int)
    fun stop(); fun cancel()
}

package dev.fitnessapp.app.telemetry
interface TelemetrySink {
    suspend fun <T> withDomainWrite(event: EventDraft, write: suspend (Transaction) -> T): T   // same Room transaction
    fun view(event: EventDraft)                                                               // buffered (19.10)
}
data class EventDraft(val type: String, val category: String, val screen: String?, val entityType: String,
                      val entityId: UUID?, val recommendationId: UUID?, val moduleId: String?, val moduleVersion: String?,
                      val occurredAt: Instant, val payload: Map<String, Any?>)
```

### 22.9 Interface gaps known today

| Needed field | Owner | Why the UI needs it | Open issue |
|---|---|---|---|
| `TodayOutput.verdict` and reason codes with precedence classes and typed params | `today` (ALGORITHMS_PLANNING section 9) | Today card states without UI re-thresholding | Resolved by C53 (ALGORITHMS_PLANNING sections 9 and 19.1) |
| `GateCriterion.thresholdAt(bw)` and `met` on the test-day bodyweight | `challenge.eca` | forward threshold vs test-day evaluation | Resolved by C62 (CHALLENGE_ENGINE section 10.1) |
| `SetTarget.durationLoS` (hold ranges) | `rx`, `challenge.materialize` | hold-timer range cues | none (added here) |
| `RuleApplication` (automatic application with Undo, side cap, notify) | `challenge.eca` | "Rule: ... · Undo" line and the side-cap hint | Resolved by D14 / C46 (CHALLENGE_ENGINE section 6.10) |
| `RestTimerControl.adjust` with ±60 s long-press steps | ANDROID.md reducer | long-press adjust | Resolved: ANDROID sections 12.3 and 25.1 (`timer.longPressStepS`) |
| Timer `purpose = interval` | ANDROID.md | warm-up row countdown that survives screen-off | OI-27 (not in the ANDROID v1 reducer) |
| Display requirements of 19.2 to 19.7 not yet in ALGORITHMS_PLANNING section 19 / 23.19 | ALGORITHMS_PLANNING | prefill details, best detection, the elbow follow-up tap, the substitute context | Resolved by C69 |

---

## 23. Accessibility

The app is used one-handed, with sweaty or chalky fingers, under fluorescent light, often in the
evening, sometimes with earbuds; the same design choices that make it work in the gym (big targets,
no precision gestures, undo instead of confirm, sound plus vibration plus visuals) also make it
accessible. The target is WCAG 2.2 level AA where the criteria apply to a native app, plus Android's
own accessibility guidance.

### 23.1 Criteria and how the app meets them

| Criterion | Requirement | How | Evidence |
|---|---|---|---|
| WCAG 1.4.3 / 1.4.11 contrast | text 4.5:1, UI parts and chart lines 3:1 | verified token pairs in 4.1; high-contrast option | engineering-fact |
| WCAG 1.4.1 use of colour | colour never the only carrier | every state has icon + text; warnings have a `!` or icon; goal bars have text tags | engineering-fact |
| WCAG 1.4.4 / 1.4.10 resize and reflow | usable at 200 % text | font scale 2.0 tested (4.8, 25); 2 x 3 RIR grid; wrapping instead of truncation; no horizontal scroll | engineering-fact |
| WCAG 2.5.8 target size | at least 24 x 24 CSS px; Android 48 x 48 dp | 48 dp minimum, 56-64 dp in session (P4) | moderate (Parhi 2006) |
| WCAG 2.5.1 pointer gestures, 2.5.7 dragging | no path or drag-only gestures | swipe never required; drag-to-reorder has Move up / Move down actions | engineering-fact |
| WCAG 2.2.1 timing adjustable | time limits can be extended | undo snackbars use `AccessibilityManager.getRecommendedTimeoutMillis` (at least 6 s or 10 s, longer when the user's accessibility timeout is longer); the rest timer never forces an action | engineering-fact |
| WCAG 3.3.1 error identification | errors in text | inline field errors with `error()` semantics and a `REJECT` haptic | engineering-fact |
| WCAG 4.1.2 name, role, value | every control exposes them | semantics table below | engineering-fact |
| Android reduced motion | honour "Remove animations" | 4.6 | engineering-fact |

### 23.2 Screen-reader (TalkBack) semantics

| Element | Role | Accessible name / state (examples) | Notes |
|---|---|---|---|
| RIR chip | Button | "Log set, 2 reps in reserve"; "5+" reads "Log set, 5 or more reps in reserve" | the row is a group labelled "Reps left in the tank"; no chip is pre-selected (D9) |
| Felt chip | Button | "Log hold, felt hard" | group "How did it feel?" |
| Load stepper value | Adjustable (`stateDescription`) | "Load, body weight plus 22.5 kilograms"; custom actions "Increase to 25 kilograms", "Decrease to 20 kilograms" | volume keys or swipe up / down adjust when focused |
| Assisted pin stepper | Adjustable | "Assistance pin 40 pounds, about 24.6 kilograms of help"; actions "Harder, pin 35 pounds", "Easier, pin 45 pounds" | "lower = harder" is spoken, never only shown by an arrow; kg with 1 decimal as on screen (C54) |
| Reps stepper | Adjustable | "Reps, 4"; actions "5 reps", "3 reps" | |
| Rest countdown | Text with a separate announcer | announced at 60 s, 30 s and 10 s left and at the end ("Rest over, set 2 of 2 next"), not every second | the digits node is not a live region, to avoid chatter |
| Overtime | Text | "Rest over 23 seconds ago" | |
| Hold timer, holding | Button covering the screen | "Stop hold, 7 seconds" (state updates every 5 s) | with TalkBack on, phase changes are spoken ("Holding", "Target reached") and the beeps still play |
| Exercise list row | Button | "Weighted pull-up, current, 1 of 2 sets done"; custom actions Move up, Move down, Skip, Do later | |
| Week strip cell | Button | "Tuesday 6 October, done" / "Saturday 10 October, rest day, day before test" | 19.1.3 labels |
| Volume bar row | Button | "Quads, improve, 0 sets done, 3 planned, target 6, below target, warning" | |
| Gate row | Text + progress | "One-arm top hold, left, 0.5 seconds of 8, not met, last valid test 30 August" | progress range info 0-1 |
| Chart | Image with summary + "Show as table" | "Weighted pull-up estimated max, last 12 weeks, latest plus 40.8 kilograms, trend up 0.3 percent per week, 1 new best" | the table is the full equivalent |
| Muscle map | decorative image | none; the group list with coefficients next to it is the accessible equivalent | |
| Banners | Text with heading | "Safety. Elbow protocol, day 3 of 14. No one-arm work. Not medical advice." | safety banner is announced once when it appears |
| Snackbar | live region (polite) | "Set 2 logged. Undo" | focusable; Undo reachable before it times out |

Headings (`semantics { heading() }`) on screen titles and section overlines. Focus order follows the
visual order; when the Live Session opens, focus goes to the current-set card title, not to the
exercise list. Sheets trap focus and close with Back. Dutch calendar titles and Dutch exercise names
are wrapped in a `LocaleSpan("nl")` so TalkBack pronounces them in Dutch. Units are spelled out in
content descriptions ("kilograms", "pounds", "seconds"); "×" reads as "times".

### 23.3 Non-visual and non-audio equivalents

| Signal | Visual | Haptic | Audio |
|---|---|---|---|
| Set logged | chip micro-confirm, card changes, snackbar | `CONFIRM` | none |
| Rest end | digits turn to "+0:00" in advisory colour, heads-up notification | waveform (always, F20) | optional tone |
| Hold cues | digits flash for `CUE_FLASH_MS` (150 ms) on each cue | 40 ms tick / double pulse | beep / double beep |
| Safety banner | red banner with icon | `REJECT` once | none |
| Invalid entry | inline error text | `REJECT` | none |

No information is carried by haptics or sound alone, so the app works with the phone muted, with
vibration off (except the rest-end alarm vibration, which is deliberate, F20) and for deaf users.

### 23.4 Constants

| Constant (`A11yConfig` in `:app`) | Default | Bounds | Evidence | Tunable |
|---|---|---|---|---|
| `REST_ANNOUNCE_AT_S` | 60, 30, 10 | | expert-opinion | user feedback |
| `HOLD_STATE_EVERY_S` | 5 | 1-10 | expert-opinion | user feedback |
| `CUE_FLASH_MS` | 150 | 100-400 | expert-opinion | no data |
| Snackbar base durations | 6 s / 10 s | | expert-opinion (6.5) | `action_undone` latency distribution |

### 23.5 Testing

* Compose semantics tests for every component in 23.2 (`A11ySemanticsTest`).
* Accessibility Test Framework checks enabled in the Robolectric/Roborazzi screenshot tests
  (`roborazzi` accessibility check option), failing on missing labels, small targets and low contrast.
* A manual TalkBack pass of the M1 logging flow and the M2 challenge flow on the user's phone, with the
  screen locked between sets.

---

## 24. Copy guidelines

### 24.1 Language and tone

* English only in the UI (D12); Dutch appears only as data (calendar titles shown verbatim; Dutch
  exercise names as secondary text and search terms, and only when the phone's language is Dutch).
* Sentence case everywhere; uppercase only for small overlines (4.2).
* Second person, plain words, short sentences. The app states facts and options; it never scolds and
  never says "you must". No exclamation marks and no emoji, except the two celebration moments ("Phase 2
  unlocked") which may use one.
* One idea per line on gym screens; anything longer goes to the Why sheet (F19).
* Verbs on buttons ("Start Session A", "Log test day", "Use suggested"), never "OK" for a consequential
  action.

### 24.2 Numbers and units (D12, section 5)

* Decimal point always, regardless of the phone's locale: "42.8 kg", never "42,8 kg". Input accepts a
  comma and normalises it.
* A space between number and unit ("22.5 kg", "90 s", "65 %"); "BW + 22.5 kg" for weighted
  bodyweight; "Pin 40 lb" with "≈ 24.6 kg assist" as secondary text (1 decimal, C54); never a negative weight in an
  input field.
* The multiplication sign "×" between sets, reps and loads in rendered UI ("3 × 5", "BW + 22.5 kg × 4");
  the ASCII wireframes in this document write "x" only because they are ASCII.
* Times in 24-hour format ("19:45"), dates as "Wed 7 Oct", weeks as "Week 41".

### 24.3 Vocabulary

| Use | Avoid | Why |
|---|---|---|
| "reps left in the tank", "2 in reserve" | "RIR 2" on gym screens | plain language; "RIR" is allowed in the Why sheet with its definition |
| "0 in reserve" | "to failure", "failure" | the app never prescribes failure on high-risk lifts (D10); the word invites it |
| "estimated max" (Why and Progress may add "e1RM") | "your 1RM" for an estimate | an estimate is not a measured single (D7) |
| "Session A", "Test day" | "OAP-A", "testdag" (except when quoting the calendar title) | English UI |
| "Rest today" | "Day off", "Recovery day" | one term |
| "hard sets" | "working sets" in volume contexts | matches SetCredit (C6) |
| "program rule" | "law", "must" | rules come from the user's program (tag A) or a proposal (tag P) |
| "suggestion" | "correction", "fix" | suggestions are optional (D10) |
| "your estimate" (assisted-machine offset) | "the machine weighs 6.5 kg" | the offset is the user's estimate, not measured (C2, critique) |
| "Not medical advice." | "consult a doctor" alone | exact disclaimer (C33); referral text may follow |

### 24.4 Evidence labels: never present coach heuristics as research

Every Why sheet shows exactly one of these labels per claim (P8, F19):

| Grade | Label shown | Use for |
|---|---|---|
| strong | "Strong evidence" | meta-analyses and position stands (for example ACSM 2026 on weekly sets) |
| moderate | "Moderate evidence" | several consistent studies |
| weak | "Weak evidence" | single studies, extrapolations |
| preprint | "Preliminary evidence (preprint)" | Camargo 2026, Remmert per-session volume, Marzagao 2026 (C29) |
| expert-opinion | "Expert opinion" | coaching conventions with a rationale |
| program rule | "Program rule (coach heuristic), not research" | OAP thresholds (65 / 70 / 75 % of bodyweight), the x1.10 rule, pin schedules, gate criteria |
| engineering-fact | "How the app works" | rounding, alarms, calendar behaviour |

Rules:

1. Never write "science says", "studies show", "proven" or "optimal". Name the source instead
   ("ACSM 2026 position stand").
2. Citations in the Why sheet use author, year and venue as text with a link: "Coleman et al. 2024,
   PeerJ" (C29). Citations marked "from memory" in the research (Lasevicius 2018, Schoenfeld 2017
   volume, Sanchez-Medina 2011, the APRE table) are not shown until verified (C29).
3. Fragile personal estimates are shown as ranges, not corrections: the personal rep curve carries its
   80 % range next to the single value, for example "+39.4 kg (80 % range +37.2 to +41.7)" as in the
   illustrative report of 14.9 (D7, critique); while the class prior `k` = 30 is used (C59), the line
   says so ("class prior until enough data").
4. Rest is presented as a trade-off: "Longer rest keeps the next set stronger; for muscle growth, rest
   beyond about 1-2 minutes matters little (ACSM 2026). Skip it if you're short of time." (C18).
5. Preprint-based thresholds (the per-session cap around 11 sets, the +20 % weekly ramp cap) always
   carry "preliminary evidence" (C14, C29).

### 24.5 Message patterns

| Kind | Pattern | Example |
|---|---|---|
| Evidence warning | number, bound, consequence; evidence in Why | "Quads (improve): 5 sets per week planned; fewer than 6 is well below the growth range." |
| Safety | what is enforced, why, disclaimer | "Today's remaining elbow work removed. Elbow protocol: 14 days. Not medical advice." |
| Program rule change | from -> to, rule id | "Assisted pin 40 -> 35 lb · prog.A.pin.gated" |
| Challenge in-session rule (D14) | "Rule:" + what changed for the remaining sets + Undo | "Rule: pin 40 -> 45 lb (easier) for the remaining sets · Undo" |
| Error | what happened, what still works, action | "Couldn't work out a recommendation (module today@1.0.0). You can still train." |
| Undo | past-tense action + Undo | "Leg press skipped · Undo" |
| Empty state | what is missing, how to fill it | "Log 2 sessions of an exercise to see a trend." |
| Reliability | consequence first | "Rest alerts may be late: exact alarms are not allowed." |

### 24.6 Canonical strings

These strings appear in several places (screens, notifications, ANDROID.md examples); this table is
canonical for the wording. ANDROID section 13.1 leaves the user-visible wording of notifications to this
document; the structure of each notification mode (which fields, chronometer, actions) is ANDROID
section 13.3's (C54).

| Key | Text |
|---|---|
| `disclaimer` | "This app does not give medical advice." |
| `disclaimer_short` | "Not medical advice." |
| `rest_over_title` | "Rest over" (heads-up N-REST-DONE) |
| `rest_over_text` | "Set {n}: {load} × {reps}" (for example "Set 2: BW + 22.5 kg × 4") |
| `session_set_title` | "{session} · {challenge}" (for example "Session A · OAP"; the session name alone for general sessions) |
| `session_set_text` | "Set {n} of {total} · {exercise}" |
| `rest_running_title` | "Rest · next: {exercise} {n}/{total}" (for example "Rest · next: Weighted pull-up 2/2") |
| `rest_running_text` | "{load} × {reps}" |
| `rest_overtime_title` | "Rest over · Set {n} of {total}" |
| `rest_overtime_text` | "{exercise} · {load} × {reps}" |
| `rule_applied` | "Rule: {change} for the remaining sets" with the action "Undo" |
| `rule_undo_prompt` | "Undo this rule? Why:" (followed by reason chips and "Cancel") |
| `rule_undone` | "Rule undone ({reason})" |
| `alarm_late` | "Alarm may be late (exact alarms off)" |
| `alarm_unavailable` | "Alarm unavailable: keep the app open" |
| `session_restored` | "Session restored. Rest timer kept running." |
| `switch_side` | "Switch to your right arm" |
| `side_cap_hint` | "Right ≤ left: {n} clean reps" |
| `logged_offline` | "Offline. {n} changes are saved on this phone and will upload automatically." |

### 24.7 String management

* All strings live in `app/src/main/res/values/strings.xml` with stable ids; reason-chip templates
  (19.1.1), warning messages and rule texts are keyed by code. Templates use typed positional
  arguments formatted by `format/Numbers.kt` and `format/Dates.kt` (ANDROID.md 27.1), never string
  concatenation, and plurals use plural resources.
* Challenge texts come from the definition's `en` fields (the definition is data, so its texts are
  versioned with the definition); a missing `en` text falls back to the stored name.
* The version of the string set is part of `ui_version` (19.10) when a change alters what a template
  says, so a review can tell which wording the user saw.

---

## 25. Golden fixtures and tests

The wireframes of sections 8 to 16 use illustrative numbers. The fixtures below are **normative**: each
lists exact inputs and the exact expected output of a ui module, controller or screen. They use the
user's real week (ISO week 2026-W41, Monday 5 to Sunday 11 October 2026) plus the end of daylight
saving time on Sunday 25 October 2026 (F18). Inputs that are engine outputs (for example `today`
scores or `challenge.materialize` items) are fixture values chosen to be plausible; they test the UI,
not the engine. When an owning spec publishes its own golden output for the same day, the UX fixture
input is regenerated from it and the expected UI output is re-derived with the owner's specification of
the ui module (ALGORITHMS_PLANNING section 19, C53, C69) and the display rules of section 19. Values marked "synthetic" are not in the user's
real data.

Fixture files live in `app/src/test/resources/fixtures/ux/` (one JSON file per fixture, named after the
id); epoch values were computed with fixed offsets (+02:00 before 2026-10-25 01:00Z, +01:00 after) and
match ANDROID.md 24.1.

### 25.1 The fixture world (shared by all fixtures)

| Item | Value |
|---|---|
| Zone, week | Europe/Amsterdam; ISO week 2026-W41 = Mon 2026-10-05 .. Sun 2026-10-11; "Week 41" |
| Today | Wed 2026-10-07 (ISO weekday 3) |
| Bodyweight | BW(t) EWMA 70.7 kg, last weigh-in 70.7 kg on Sun 2026-10-04 (not stale, C32) |
| Profile | experience 1-3 years (intermediate; set explicitly, the default without an answer is novice, C64), changeover 5 min (F11, C49), default budget 75 min, maximum gym visits 4 (D5), all prompts on |
| Calendar (primary, all unmarked) | C Tue 2026-10-06 19:45-21:15 (`1791308700000`-`1791314100000`) "Gym — sessie C (volume en techniek)"; A Thu 2026-10-08 19:45-21:15 (`1791481500000`-`1791486900000`) "Gym — sessie A (één arm zwaar)"; TEST Sun 2026-10-11 13:00-15:00 (`1791716400000`-`1791723600000`, 2 h assumed) "Gym — testdag 1 (meten)"; synthetic busy Mon 2026-10-05 19:30-22:00 "Schaken" |
| Challenge | OAP, phase 1, block 1, week 5 of 5, mode `test_pending` (deload transform), test planned Sun 11 Oct 13:00, gap-rule scope per C11, rest-day-before-TEST scope `all_gym` |
| Challenge loads | weighted pull-up C 20 / A 22.5 / B 27.5 kg; weighted negatives 47.5 kg (reset 45); assisted pins A 40 lb / C 50 lb; 90-degree hold 35 lb |
| Assisted machine | `{unit lb, step 5, min 0, max 100 (fixture assumption), offsetKg 6.5, ratio 1.0}` (F7, C2) |
| Belt loadable set (input from `load`) | every multiple of 2.5 kg from 0 to 60 kg (gym plates 25, 20, 15, 10, 5, 2.5, at most two per size; the dip belt maximum is 60 kg) |
| Assisted values (from `load`, C2, HALF_EVEN) | pin 30 lb: 20.11 kg; 35: 22.38; 40: 24.64; 45: 26.91; 50: 29.18; 55: 31.45. One-arm load at BW 70.7: 50.59 / 48.32 / 46.06 / 43.79 / 41.52 / 39.25 kg |
| Session C (synthetic) | started Tue 19:52 (`1791309120000`), finished 21:00 (`1791313200000`), 68 min, 13 working sets, linked to the C event |
| Past tests (import, D4) | 2026-08-30: BW 71.0, 3RM +26 kg, max reps 18, hang L 3 / R 8 s ("old protocol, not comparable"), top hold L 0.5 / R 1.0 s, valid for gates; 2026-09-19: 3RM +32.5 kg at BW 70.7, not valid (3RM only); 2026-09-29: hang L 15 / R 20 s, not valid (not a test day) |
| Personal curve | `k_used` = 30 for `weighted_pullup`: the class prior, because no historical set rows are fed to RCM-1 at cutover and the gate uses the prior until RCM-1 has informative windows (C59) |
| DST | 2026-10-25 03:00 CEST -> 02:00 CET (01:00Z); W43 = Mon 19 .. Sun 25 Oct, 169 h long |

### 25.2 Today card (`ui.todaycard`)

**GF-UX-01 Wednesday 7 October 12:00 (`1791367200000`): OPTIONAL CLOSE (synthetic inputs).** The `today`
and `weekplan` inputs are synthetic: they describe a week in which Wednesday is the recommended day.
With the owner's real-week inputs Wednesday is a soft rest day (GF-UX-27, ALGORITHMS_PLANNING section
21.9); the fixture still tests the OPTIONAL CLOSE composition and its boundary.

Inputs: no event today; `today.verdict = TRAIN`; options `legs_push` 1.8400 (est 55 min), `legs`
1.7100 (45 min), `push` 1.1200 (40 min); reasons of `legs_push`: `PROTECT_REST {family: pull, slot: A,
when: tomorrow}` weight 0.90, `VOLUME_DEFICIT {group: quads, done: 0, target: 6}` 0.62, `RECOVERED
{family: legs, pct: 100}` 0.40, `DELOAD_WEEK` 0.30 (synced to the challenge, class CHALLENGE); excluded
`pull` with `PROTECT_REST {slot: A, at: Thu 19:45, groups: [lats, biceps, forearms]}`; weekplan: Mon
REST (busy), Tue DONE, Wed RECOMMENDED, Thu CHALLENGE, Fri FREE_LIMITED, Sat REST_HARD, Sun TEST.

| Output | Expected |
|---|---|
| state, variant | `OPTIONAL`, `CLOSE` (1.7100 >= 1.8400 x 0.90 = 1.6560) |
| primary, start label | `legs_push`, "Start legs + push" |
| alternatives (inline) | `legs`, `push` |
| chips | "Pull rests for A tomorrow", "Deload week", "Quads 0/6 wk" |
| not today | "Not today: Pull. Session A is tomorrow 19:45; lats, biceps and forearms are protected." |
| budget chips | 45, 60, 75, 90+; selected 75; source `default` |
| block line | "OAP block 1 · week 5 · deload + test" |
| week strip | `- v o * - x T`, Wednesday outlined |
| secondary | Other options, Plan it in calendar |

Boundary variants: `legs` = 1.6560 gives `CLOSE`; `legs` = 1.6559 gives `CLEAR` with no inline
alternatives.

**GF-UX-02 Thursday 8 October 12:00 (`1791453600000`): PLANNED CHALLENGE (Session A).** The option set
is synthetic (Session A without an add-on); in the owner's real week the primary option is "Session A +
legs add-on (~14 min)" with the same budget chip "85 cal" (ALGORITHMS_PLANNING section 21.10).

Inputs: A event 19:45-21:15 (90 min), matched slot A; `today.verdict = TRAIN`; option `challenge_A`
reasons `PLANNED_CAL {at: 19:45}` 1.00 (USER), `NEXT_FIXED {slot: test, at: Sun 13:00}` 0.50,
`CHALLENGE_DUE {slot: A, done: 1, total: 3}` 0.40, `HOURS_SINCE {subject: C, hours: 48}` 0.30; preview
items from `challenge.materialize` (fixture input, deload transform): assisted one-arm pull-up 3 x 3/arm
at pin 40 lb; weighted pull-up 2 x 4 at BW + 22.5 kg; top hold 2 rounds x 4 attempts/arm; 90-degree hold
2 x 6-8 s/arm at pin 35 lb.

| Output | Expected |
|---|---|
| state, variant | `PLANNED`, `CHALLENGE` |
| title, mode line | "Gym today 19:45 · OAP Session A"; "Deload week: sets halved, loads kept" |
| chips | "Test Sun 13:00", "A due (1/3 this week)", "Planned 19:45" |
| preview | "Assisted one-arm pull-up 3 × 3/arm · Pin 40 lb", "Weighted pull-up 2 × 4 · BW + 22.5 kg", "Top hold 2 rounds × 4/arm", more = true |
| budget chips | 85, 45, 60, 75, 90+; selected 85 labelled "85 cal" (90 - 5, F11, C49), shown first because it is not a standard value; source `calendar` |
| start label | "Start Session A" |
| week strip | `- v - * - x T` |

**GF-UX-03 Saturday 10 October 10:00 (`1791619200000`): REST HARD.** Session A finished Thu
(synthetic). `today.verdict = REST_HARD`, `restReasons = [REST_BEFORE {slot: test, when: tomorrow},
rule restDayBefore(TEST), scope all_gym]`. Expected: state `REST`, variant `HARD`, chips "Rest before
test tomorrow", no primary button, secondary Why and overflow "Log a session anyway", week strip
`- v - v - x T`.

**GF-UX-04 Sunday 11 October.** At 08:00 (`1791698400000`) N-TEST-DAY is posted: "Test day today 13:00 ·
weigh in before warming up". At 10:00 (`1791705600000`): state `PLANNED`, variant `TEST`, title "Test
day today 13:00", validity line "This test counts for your gates" (`testValid = true`: Session A on
Thursday was the clean session), start label "Log test day", secondary Postpone (`postponeAllowed`), no
budget chips. Variant **GF-UX-04b**: Session A was not logged, so `needsCleanSession` is true and the
challenge postponed the test (C36); the unmarked "testdag" event becomes a conflict: week strip Sunday
shows `!` (conflict wins over `T`), the Week row reads "Test postponed: no clean session yet" with
"Rename to session B" and "Keep".

**GF-UX-05 Tuesday 6 October 21:30: DONE.** Session C finished (synthetic values), the C event is linked
to it, `nextFixed` = A Thu 19:45. Expected: state `DONE`, title "Session C done · 68 min · 13 sets",
line "Next: Thu 8 Oct, Session A 19:45", "View summary".

**GF-UX-20 Template picker on Saturday 10 October.** Expected slot rows: "C · done Tue", "A · done Thu",
"Test · Sun 13:00 · not today (rest day before test)"; every family combination row carries "Not today:
rest day before test"; "Empty session" is enabled and starting it shows the red rule banner and logs
`rule_override {rule_id: restDayBefore(TEST) rule id from the definition, action: log_anyway}` (P10).

**GF-UX-22 Week strips (synthetic weekplan of GF-UX-01 to 05; the real-week strip is in GF-UX-27).**
Wed `- v o * - x T`; Thu (before A) `- v - * - x T`; Sat (A done)
`- v - v - x T`; Sun after the test is submitted `- v - v - x v` with Sunday's label "Sunday 11 October,
test day, done".

**GF-UX-25 Chip precedence beats weight.** Reasons `VOLUME_DEFICIT` 0.90, `PLANNED_CAL` 0.50,
`PROTECT_REST` 0.20, `SAFETY_PROTOCOL {protocol: Elbow protocol, day: 3, days: 14}` 0.10. Expected chips:
"Elbow protocol · day 3 of 14", "Pull rests for A tomorrow", "Planned 19:45" (SAFETY, CHALLENGE, USER;
the heaviest GENERAL reason is dropped).

**GF-UX-27 Wednesday 7 October 12:00, the real week: REST SOFT (inputs from ALGORITHMS_PLANNING section
21.9).** Inputs: no event today; `today.verdict = REST_SOFT`; `restReasons = [NEXT_FIXED {slot: A, at:
Thu 19:45} (CHALLENGE), BETTER_OTHER_DAY {type: Legs + push + rear delts, day: Fri} (GENERAL)]`; options
in the engine's order FULL 0.441 (~49 min), PUSH_LEGS 0.438 (~48 min), PULL_LEGS 0.274 (~47 min), UPPER
0.260, PUSH 0.128, LEGS 0.125, PULL -0.492; excluded `PROTECT_REST` (pull; lats, biceps, forearms;
Session A Thu 19:45) and `GAP_DAY` (upper back, light pull only); weekplan Mon PAST, Tue DONE, Wed
REST_SOFT, Thu CHALLENGE (with a legs add-on), Fri RECOMMENDED, Sat REST_HARD, Sun TEST.

| Output | Expected |
|---|---|
| state, variant | `REST`, `SOFT`; no primary Start button |
| chips | "A Thu 19:45", "Legs + push + rear delts fits better on Fri" (CHALLENGE before GENERAL) |
| not today | "Not today: Pull. Session A is tomorrow 19:45; lats, biceps and forearms are protected.", "Upper back: light pull only." |
| secondary | "Train anyway", which lists "Legs + push + rear delts ~49 min", "Legs + push ~48 min", "Legs + rear delts ~47 min" with the time chips 45, 60, 75 (selected, source `default`), 90+ |
| week strip | `- v - * o x T`, Wednesday outlined |
| Week tab | the rows of 10.2 (ALGORITHMS_PLANNING section 21.7) |

### 25.3 Set card (`ui.prefill`)

**GF-UX-06 Prefill, steps, snapping, deviation and layout.**

| Case | Input | Expected |
|---|---|---|
| A weighted pull-up set 1 | target BW + 22.5 kg × 4, `planned_source = challenge_rule` | values BW + 22.5 kg, 4 reps; source `engine_target`; secondary line from `load.breakdown` "20 + 2.5 on belt" |
| Step up / down from 22.5 | belt set | 25 / 20 |
| B weighted pull-up | 27.5 kg | up 30, down 25 |
| Negatives | 47.5 kg, breakdown input [20, 20, 5, 2.5] | "BW + 47.5 kg", "20 + 20 + 5 + 2.5"; up 50, down 45 |
| C weighted pull-up | 20 kg | "BW + 20 kg" |
| Keypad 23 | belt set | 22.5, `changed = true`, `REJECT`, line "Set to 22.5 kg (nearest you can load)" |
| Keypad 23.75 (tie) | belt set | 22.5 (the easier load) |
| Keypad 61 | belt set (max 60, max gap 2.5) | 60, changed |
| Keypad 63 | belt set | out of range: "Not loadable here (maximum 60 kg on the belt)" |
| A assisted pin | pin 40 lb (assist 24.64 kg, one-arm load 46.06 kg on the 0.01 grid) | "Pin 40 lb", "≈ 24.6 kg assist · one-arm load 46.1 kg (65 % BW)" (1 decimal, C54); harder 35, easier 45 |
| C assisted pin | pin 50 lb (29.18 / 41.52 kg) | "≈ 29.2 kg assist · one-arm load 41.5 kg (59 % BW)" |
| 90-degree hold pin | 35 lb (22.38 / 48.32 kg) | "≈ 22.4 kg assist · one-arm load 48.3 kg (68 % BW)" |
| Pin keypad 42 / 42.5 | pins 0-100 step 5 | 40 / 45 (tie goes to more assistance, C2) |
| Deviation: load up | target 22.5 × 4, draft 25 × 4 | fields [load up], direction `UP` |
| Deviation: reps down | draft 22.5 × 3 | [reps down], `DOWN` |
| Deviation: mixed | draft 25 × 3 | [load up, reps down], `DOWN` (safety first) |
| Deviation: post-log RIR | target RIR 2, chip 4 / chip 1 | `UP` / none |
| Deviation: hold range | 90-degree hold 6-8 s, logged 4.5 s / 5.0 s | `DOWN` (4.5 < 0.8 x 6 = 4.8) / none |
| RIR layout | 393 dp at 1.0 / 1.3 / 1.5; 360 dp at 1.0 | row (56.5 dp) / row / grid / grid (51.0 dp) |

**GF-UX-07 Unilateral side cap (assisted one-arm, Session A).** Target 3 reps/arm at pin 40 lb, target
RIR 1, side rule right <= left. Left logged 2 reps at RIR 0: left deviation `DOWN` (reps 2 < 3), post-log
RIR delta 1 (no RIR deviation), chips `too_hard, feeling_bad, too_tired, pain, grip_failure,
technique_breakdown` visible (the machine row offers no `torso_rotation` flag, C74) (the `harder` order of ALGORITHMS_PLANNING section 19.2). Right prefill 2 reps,
`side_cap_applied = true`, hint "Right ≤ left: 2 clean reps".
If the user raises the right side to 3: deviation field `side_rule` (UP), advisory line "Right above
left (program rule: right ≤ left)", logging not blocked.

**GF-UX-26 Challenge in-session rule with Undo (Session A, Thursday 8 October; D14, C46).** Inputs:
assisted one-arm pull-up, 3 pairs planned at pin 40 lb, target 3 reps/arm; rule `ins.A.oaa.calibrate`
(tag A) with the definition defaults `calLowReps` 2 and `pinDropLb` 5. Pair 1 logged L 2 / R 2 (the
minimum, 2, is at most 2). Expected: pairs 2 and 3 are planned at pin 45 lb with `planned_source =
challenge_rule` and prefilled with `prefill_source = engine_target`; the rest card and the cards of pairs
2 and 3 show "Rule: pin 40 -> 45 lb (easier) for the remaining sets · Undo"; one `challenge_rule_applied`
with `rule_id = ins.A.oaa.calibrate`, `rule_tag = A`, from 40 lb, to 45 lb, `sets_affected = 2`; no
suggestion card and no `suggestion_shown` (C46). Undo before pair 2, then the reason chip "Too easy":
pairs 2 and 3 return to pin 40 lb, the line reads "Rule undone (too easy)", the session exercise gets
`user_override = true` with `override_reason_code = too_easy`, one `challenge_rule_undone {rule_id:
ins.A.oaa.calibrate, reason_code: too_easy}`, and the rule does not fire again on this item in this
session (CHALLENGE_ENGINE GF-CE-24 tests the engine side). The notification's next-set text follows the
change and has no Undo action.

### 25.4 Timers

**GF-UX-08 Rest display and DST.**

| Case | Input | Expected |
|---|---|---|
| Countdown | running, total 180 000 ms, r = 138 000 | "2:18", bar 0.7667 |
| Last second | r = 1 | "0:01" |
| End | r = 0 | "+0:00" in advisory colour, bar hidden |
| Overtime | r = -23 000 / -23 999 | "+0:23" / "+0:23" |
| DST rest | start_wall `1792889940000` (02:59 CEST), start_elapsed 1 000 000, 180 s; now_elapsed 1 060 000, same boot | end_wall `1792890120000` (02:02 CET); display "2:00" |
| DST events | `rest_started` at `1792889940000`; `rest_ended` at `1792890150000` | `tz_offset_min` 120 and 60 |
| Session elapsed over DST | started `1792888200000` (02:30 CEST, first occurrence), now `1792892400000` (02:40 CET) | header "1:10:00" (not "0:10:00") |
| G3 alignment | set logged `1791482280000`, 180 s | end `1791482460000`; +15 then -15 return to it; -15 at 6 s left gives `1791482459000` (5 s minimum); cap `1791483180000` (ANDROID.md G3) |

**GF-UX-09 Sunday 25 October 2026 (DST end): Today and Week 43.** Inputs (synthetic weekplan, block 2
week 2 after a valid test on 11 October): an app-written (marked) B event Sun 25 Oct 11:00-12:30 CET
(`1792922400000`-`1792927800000`), slot B; `today.verdict = TRAIN`. At 09:00 CET (`1792915200000`):
state `PLANNED`, variant `CHALLENGE`, title "Gym today 11:00 · OAP Session B", budget chip "85 cal"
(90 - 5), block line "OAP block 2 · week 2", week label "Week 43", strip cells Monday 19 to Sunday 25
October, `today_card_shown.tz_offset_min` = 60. N-HABIT for the daily hang fires at 17:15 CET =
`1792944900000` (ANDROID.md G8). Two `session_events` rows written at 02:30 local before the change
(`1792888200000`, offset 120) and at 02:30 local after it (`1792891800000`, offset 60) sort correctly by
`occurred_at` and are told apart by `tz_offset_min`.

**GF-UX-15 Hold timer cues and result prefill.**

| Case | Input | Expected |
|---|---|---|
| Straight-arm hold (A), prescribed 6 s, lead-in 5 s | t0 = 0 | LEAD_TICK at 2000, 3000, 4000 ms; GO at 5000; TICK at 10 000; TARGET_DOUBLE at 11 000; no later cues |
| Stop at go + 7400 ms | | `hold_timer_s` 7.4; prefill 6 s (target reached) |
| Stop at go + 4730 ms | step 0.5 s (target < 10 s) | 4.7 -> prefill 4.5 s |
| Tap at go + 300 ms | `STOP_GUARD_MS` 500 | ignored, still HOLDING |
| 90-degree hold range 6-8 s | | TICK at go + 5000 and at go + 6000 (range minimum), TARGET_DOUBLE at go + 8000 |
| Test-day one-arm hang L, max mode, previous best 15 s (29 Sep, informative) | | TICK every 5 s, TARGET_DOUBLE at go + 15 000 replacing that tick |
| Stop at go + 16 730 ms | step 1 s (15 >= 10) | 16.7 -> prefill 16 s |
| Auto-stop | no tap | stop at go + 300 000 ms, `via = auto_max` |

**GF-UX-19 Session notification content (Thursday, after set 1 of the weighted pull-up).** Rest running
(structure of ANDROID section 13.3, wording of 24.6): title "Rest · next: Weighted pull-up 2/2", text
"BW + 22.5 kg × 4", `when` `1791482460000`, countdown chronometer, actions in the order "-15 s", "Skip",
"+15 s" (the order of the rest card). At the end: heads-up on channel `rest_done` with title "Rest over"
and text "Set 2: BW + 22.5 kg × 4"; the ongoing notification switches to overtime with title "Rest over
· Set 2 of 2" and text "Weighted pull-up · BW + 22.5 kg × 4". Tapping either opens the route
`fitapp://session/current`.

**GF-UX-23 Debounce and input guard.** Taps on the RIR chip "2" at t = 0 and t = 250 ms: one set
logged, one `log_double_tap_dropped {control: rir_chip, gap_ms: 250}`. A tap at t = 320 ms on the place
where "-15 s" appears is ignored by the 500 ms input guard (no `rest_adjusted`); a tap at t = 600 ms is
applied.

### 25.5 Challenges dashboard (`ui.progress` gate rows)

**GF-UX-10 Gate to phase 2, Wednesday 7 October.** Inputs from `challenge.eca` (phase 1 exit gate of
`oap.challenge.v1.json`; values computed in decimal, C21/C22): latest valid test 2026-08-30 (BW 71.0 kg);
gate value `wpu_e1rm_added` with the class prior `k` = 30 (C59) = (71.0 + 26) x (1 + 2 / 30) - 71.0 =
32.4666... -> **+32.47 kg** (displayed +32.5 kg); x1.10 value = 97.0 x 1.10 - 71.0 = **+35.70 kg**;
threshold on the test's bodyweight 0.65 x 71.0 - 2.0 = 44.15 (not met, C62); forward threshold at BW(t)
70.7: 0.65 x 70.7 - 2.0 = 43.955 -> **+43.96 kg**; latest non-valid observation 2026-09-19 (3RM only):
(70.7 + 32.5) x (1 + 2 / 30) - 70.7 = **39.38** -> **+39.4 kg** displayed, x1.10 103.2 x 1.10 - 70.7 =
42.82 -> **+42.8 kg**; fixture extras (synthetic engine outputs): gate value 80 % range +30.9 to +33.8
kg, SSE-1 training estimate +40.8 kg (80 %: +37.3 to +44.4).

| Row | Expected text | Bar fraction | Met |
|---|---|---|---|
| Header | "Gate to phase 2 · 0 of 4 met" | | |
| e1RM title | "Weighted pull-up e1RM ≥ +43.96 kg (65 % of 70.7 kg - 2.0 kg)" | | |
| e1RM value | "+32.5 kg (k 30, class prior) · test 30 Aug · x1.10 rule +35.7 · 80 % range +30.9 to +33.8" | 0.7386 (32.47 / 43.96) | no |
| e1RM training line | "Training estimate +40.8 kg (80 %: +37.3 to +44.4)" | | |
| e1RM muted line | "Latest, not valid for gates (19 Sep, 3RM only): +39.4 kg (x1.10 +42.8)" | | |
| Hang L / R | "no valid value" (the 30 Aug hang values are flagged "old protocol, not comparable") | 0 / 0 | no |
| Hang muted line | "Latest, not valid for gates (29 Sep, not a test day): L 15 s · R 20 s" | | |
| 5 s negative L / R | "not tested" | 0 / 0 | no |
| Top hold L / R | "0.5 s of 8 s" / "1 s of 8 s" | 0.0625 / 0.125 | no |

### 25.6 Prompts, warnings, volume bars, reason chips

**GF-UX-11 Prompt plan for Session A, Thursday 8 October 19:45.** Inputs: template A1 requires the elbow
check; the OAP elbow pain rule is active; no pain report in 14 days; no prompt in reduced mode; planned
groups with planned credited sets (fixture): lats 5.5, upper_back 4.0, biceps 3.0, forearms 2.5; all
four had hard sets in Tuesday's Session C (48 h before, within 96 h).

| Step | Items | Taps used |
|---|---|---|
| Guarded (never dropped) | `elbow_check`, `joint_pain` | 2 |
| Priority 2 | `srpe` | 3 |
| Priority 3 | `readiness` | 4 |
| Priority 4 | `soreness(lats)`, `soreness(upper_back)` | 6 |
| Dropped for budget | `soreness(biceps)` (`prompt_skipped {cause: budget}`); `forearms` was never a candidate (`ui.prompts.sore_max` 3) | |

Expected sheet order (16.3): readiness, elbow check, soreness lats, soreness upper back. Post (Summary):
session effort, joint pain. Answer "2 mild" on the elbow check adds the symptom-kind chips (one extra
tap, not budgeted).

**GF-UX-12 Warning display on the Week tab, Wednesday 7 October 12:00.**

| Id | Rule | Severity | Entity | Magnitude | Dismissal | Expected surface |
|---|---|---|---|---|---|---|
| v1 | `targets.improve_low` | WARN | `group:quads` | 0.50 | none | list, advisory dot |
| v2 | `targets.improve_low` | STRONG | `group:chest` | 0.83 | none | **banner** with "Use suggested" |
| v3 | `warn.session_cap` | INFO | `group:chest` | 0.10 | none | list only; chest keeps the advisory dot (stronger marker wins) |
| v4 | `targets.improve_low` | WARN | `group:triceps` | 0.15 | none | list, advisory dot (below `ui.warndisplay.min_magnitude`) |
| v5 | `targets.improve_low` | WARN | `group:hamstrings` | 0.40 | 2026-09-20 at 0.35 | list only, snoozed (17 days < 28; 0.40 < 0.4375) |
| v6 | `targets.improve_low` | WARN | `group:glutes` | 0.60 | 2026-09-20 at 0.40 | list, resurfaced (0.60 >= 0.50) |
| v7 | `rep.range_high` | WARN | `pref:leg_press` | 0.70 | use mine anyway | "your choice" marker, not listed |

Expected: banner v2; list order v2, v6, v1, v5, v4, v3; "5 more notes".

**GF-UX-13 Volume bars, Wednesday 7 October (deload week, Balanced preset, lats covered by the
challenge).**

| Input group | Goal | Target | MRV | Done | Planned |
|---|---|---|---|---|---|
| chest | improve | 6 | 12 | 2 | 2 |
| side_delts | improve | 6 | 12 | 1 | 2 |
| quads | improve | 6 | 12 | 0 | 3 |
| hamstrings | improve | 6 | 10 | 0 | 0 |
| calves | maintain | 2 | 8 | 0 | 2 |
| abs | maintain | 2 | 2.5 | 3 | 0 |
| lats | covered | | | 9.5 | |
| abductors | skip | | | | |

| Expected row (in order) | Text | Fill done / planned | State (owner) | Flag (warnings of GF-UX-12) |
|---|---|---|---|---|
| hamstrings | "0 / 6" | 0 / 0 | DEFICIT (WARN, a snoozed warning still counts) | `!` advisory |
| side_delts | "1 (+2) / 6" | 0.1667 / 0.3333 | OK (no WarningEngine deficit) | none |
| quads | "0 (+3) / 6" | 0 / 0.5 | DEFICIT (WARN) | `!` advisory |
| chest | "2 (+2) / 6" | 0.3333 / 0.3334 | DEFICIT (STRONG) | `!` advisory |
| calves | "0 (+2) / 2" | 0 / 1.0 | OK | none |
| abs | "3 / 2" | 1.25 / 0 (MRV marker at 1.25) | OVER | `!` muted |
| lats | "covered by challenge" | | covered (no bar) | none |
| Skip line | "Not trained: abductors" | | | |

Order: improve rows by (done + planned) / target ascending (hamstrings 0, side_delts and quads 0.5 in
canonical group order, chest 0.67), then maintain rows (calves 1.0, abs 1.5), then covered rows, then the
skip line (ALGORITHMS_PLANNING section 19.4).

**GF-UX-14 Reason-chip orders.**

| Exercise (flags offered) | Context | Visible | Behind "More" |
|---|---|---|---|
| weighted pull-up (two arms, `grip_slip_before_last`) | harder | too_hard, feeling_bad, too_tired, pain, grip_failure, technique_breakdown | no_time, occupied, other |
| finger-assisted one-arm pull-up (unilateral pull, `torso_rotation`) | harder | too_hard, feeling_bad, too_tired, pain, grip_failure, torso_rotation | technique_breakdown, no_time, occupied, other |
| assisted one-arm pull-up on the machine (no flag offered) | harder | too_hard, feeling_bad, too_tired, pain, grip_failure, technique_breakdown | no_time, occupied, other |
| leg press (none) | harder | too_hard, feeling_bad, too_tired, pain, grip_failure, technique_breakdown | no_time, occupied, other |
| any | easier | too_easy, feeling_good, other | |
| any | skip (sheet) | no_time, occupied, pain, feeling_bad, too_tired, equipment_missing, other | |
| any | session_end | no_time (preselected), pain, other | |

Do later: order [warm-up done, A: assisted done, weighted pull-up current, top hold pending, 90-degree
hold pending]; "Do later" on the weighted pull-up moves it between the top hold and the 90-degree hold,
`defer_count` 1; a second "Do later" moves it after the 90-degree hold, `defer_count` 2, and Swap
becomes the primary action.

### 25.7 Formatting, telemetry, summary

**GF-UX-16 Number and date formatting with the phone locale forced to `nl-NL`.** 70.7 -> "70.7 kg";
20.0 -> "20 kg"; 1.25 -> "1.25 kg"; assist 24.6436948 (stored 24.64) -> "24.6 kg"; e1RM 39.38 -> "+39.4 kg";
fractional sets 9.5 -> "9.5", 12.0 -> "12"; ratio 0.6515 -> "65 %"; `parse("42,8")` = 42.8; 2026-10-07 ->
"Wed 7 Oct", ISO week "Week 41"; 19:45 -> "19:45"; rest 138 s -> "2:18"; overtime 23 s -> "+0:23";
session 1930 s -> "32:10", 3852 s -> "1:04:12"; holds 0.5 -> "0.5 s", 8.0 -> "8 s", 90 -> "1:30". These
agree with ANDROID.md G7, including assist kg with 1 decimal ("≈ 24.6 kg", C54).

**GF-UX-17 Telemetry envelope and payload bytes.** Session A, Thursday 8 October, weighted pull-up set
1 logged at `2026-10-08T17:58:00Z` with the chip "2", no edits. Fixture ids: session
`0199c3a0-0000-7000-8000-00000000a001`, session exercise `...a011`, set `...a111`, timer `...a211`.

```json
{"event_type":"set_logged","category":"logging","occurred_at":"2026-10-08T17:58:00Z","tz_offset_min":120,
 "screen":"session.live","session_id":"0199c3a0-0000-7000-8000-00000000a001","entity_type":"set",
 "entity_id":"0199c3a0-0000-7000-8000-00000000a111","module_id":"ui.prefill","module_version":"1.0.0",
 "ui_version":"1.0.0",
 "payload":{"chip_value":2,"completed_at":"2026-10-08T17:58:00Z","deviation_direction":"none",
  "deviation_fields":[],"input_method":"none","log_control":"rir_chip","ms_from_card_shown":41250,
  "ms_from_rest_end":23000,"prefill_source":"engine_target",
  "session_exercise_id":"0199c3a0-0000-7000-8000-00000000a011","set_id":"0199c3a0-0000-7000-8000-00000000a111",
  "set_index":1,"set_kind":"working","side":"both","side_cap_applied":false,"snapped":false,"taps":0}}
```

The next event is `rest_started` with payload `{"alarm_mode":"alarm_clock","duration_source":"challenge",
"planned_s":180,"purpose":"rest","set_id":"...a111","timer_id":"...a211"}` and the end instant of
GF-UX-08's G3 row. Assertions: the payload serialises to exactly these bytes (sorted keys, JCS); every
key is in section 20; the payload is under 8192 bytes; the session note "elleboog voelt raar" written in
the same session appears in no `session_events` payload (free-text scan over all events of the fixture).

**GF-UX-21 Session Summary after Session A (synthetic firings, rule ids from `oap.challenge.v1.json`).**
Inputs: `NextTimeLine` for `oaa` stays pin 40 lb (reason deload week, rule `prog.A.pin.gated`), `wpu`
stays BW + 22.5 kg (reason deload week, rule `prog.wpu.A`), `h90` stays pin 35 lb; top hold R best 1.5 s
(previous best 1.0 s). Expected: NEW BEST "Top hold R 1.5 s (was 1 s)" (gain 0.5 s >= `ui.progress.hold_pr_min_gain_s`);
NEXT TIME lines "Assisted one-arm pull-up: stays Pin 40 lb · deload week (prog.A.pin.gated)", "Weighted
pull-up: stays BW + 22.5 kg · deload week (prog.wpu.A)", "90-degree hold: stays Pin 35 lb · deload week".

### 25.8 Screens (Roborazzi) and accessibility

**GF-UX-18 Screenshot matrix.** Each screen state below is captured in six configurations, 393 x 873 dp
at font scale 1.0, 1.3 and 2.0, each in light and dark; the two narrow configurations of 4.8 (360 x 800
dp at 1.0 and 1.3, light) are added for the Live Session states. This extends the reduced matrix of
4.8; ANDROID.md G14 is a subset of this list.

| # | Screen state | Fixture |
|---|---|---|
| 1 | Today, OPTIONAL CLOSE | GF-UX-01 |
| 1b | Today, REST SOFT (the real Wednesday) | GF-UX-27 |
| 2 | Today, PLANNED CHALLENGE | GF-UX-02 |
| 3 | Today, REST HARD | GF-UX-03 |
| 4 | Today, PLANNED TEST | GF-UX-04 |
| 5 | Today, DONE | GF-UX-05 |
| 6 | Today, IN_PROGRESS with mini-bar "Session A · 32:10 · Rest 1:42" | synthetic |
| 7 | Today and Live Session with the safety banner "Elbow protocol · day 3 of 14" | GF-UX-25 |
| 8 | Week 41 with volume bars and the STRONG banner | GF-UX-12, GF-UX-13 |
| 9 | Live Session set card: weighted pull-up set 2 of 2, BW + 22.5 kg × 4, grip flag | GF-UX-06 |
| 10 | Rest card 2:18 with an engine suggestion card | GF-UX-08 |
| 11 | Rest card in overtime "+0:23" with the next set card | GF-UX-08 |
| 12 | Unilateral assisted card, pin 40 lb, left active | GF-UX-07 |
| 13 | Hold card READY, HOLDING (00:07), RESULT | GF-UX-15 |
| 14 | Top-hold round entry | synthetic |
| 15 | Negative card, BW + 47.5 kg | GF-UX-06 |
| 16 | Session Summary | GF-UX-21 |
| 17 | Exercises list and leg press detail | synthetic |
| 18 | Challenges dashboard | GF-UX-10 |
| 19 | Test wizard: overview, assistance ladder, review | synthetic |
| 20 | Proposal item | synthetic |
| 21 | Why sheet for a program rule | synthetic |
| 22 | Pain sheet with elbow kinds | synthetic |
| 23 | Readiness sheet | GF-UX-11 |
| 24 | Skip sheet and Swap sheet | GF-UX-14 |
| 25 | Keypad sheet with the snapping line | GF-UX-06 |
| 26 | You > Rest and alerts with one amber reliability row | synthetic |
| 27 | Progress chart, weighted pull-up, 12 weeks | synthetic |
| 28 | Onboarding: permissions (exact alarms) and equipment | synthetic |
| 29 | Live Session set card with the challenge rule line "Rule: pin 40 -> 45 lb (easier) for the remaining sets · Undo" (D14) | GF-UX-26 |
| 30 | Test wizard: unassisted 5 s negative step, left side (C65) | synthetic |

Assertions in every configuration: no text has visual overflow; the RIR row is a 2 x 3 grid exactly when
19.2 says so; no horizontal scroll; the Accessibility Test Framework reports no missing label, no target
below 48 dp and no text contrast below 4.5:1.

**GF-UX-24 Accessibility semantics.** For the GF-UX-06 card: RIR chip "2" has the name "Log set, 2 reps
in reserve"; the pin stepper has the state "Assistance pin 40 pounds, about 24.6 kilograms of help" and
the actions "Harder, pin 35 pounds" and "Easier, pin 45 pounds"; the rest countdown announces only at 60,
30 and 10 s left; the week strip cell for Saturday reads "Saturday 10 October, rest day, day before test".

### 25.9 Test classes

| Test class (package `dev.fitnessapp.app`) | Fixtures | Kind |
|---|---|---|
| `uidet.TodayCardGoldenTest` | GF-UX-01 to 05, 09, 20, 22, 25, 27 | JVM unit |
| `uidet.PrefillGoldenTest` | GF-UX-06, 07 | JVM unit |
| `uidet.GateRowsGoldenTest` | GF-UX-10 | JVM unit |
| `uidet.PromptPlanGoldenTest` | GF-UX-11 | JVM unit |
| `uidet.WarnDisplayGoldenTest` | GF-UX-12 | JVM unit |
| `uidet.VolumeBarsGoldenTest` | GF-UX-13 | JVM unit |
| `uidet.ReasonChipsGoldenTest` | GF-UX-14 | JVM unit |
| `uidet.SummaryNextTimeGoldenTest` | GF-UX-21 | JVM unit |
| `uidet.UiDeterminismTest` | all ui fixtures run twice, and once with shuffled input list order: identical outputs and `inputs_hash` | JVM unit |
| `timer.RestDisplayDstTest` | GF-UX-08 | JVM unit |
| `timer.HoldTimerScheduleTest` | GF-UX-15 | JVM unit (fake monotonic clock) |
| `format.UxNumberFormatTest` | GF-UX-16 | JVM unit |
| `telemetry.TelemetryPayloadGoldenTest` | GF-UX-17 | JVM unit |
| `notify.SessionNotificationContentTest` | GF-UX-19 | Robolectric |
| `ui.live.InputGuardTest` | GF-UX-23 | Compose UI test (Robolectric) |
| `ui.live.ChallengeRuleLineTest` | GF-UX-26 | Compose UI test (Robolectric) with a fake `challenge.eca` |
| `ui.A11ySemanticsTest` | GF-UX-24 | Compose UI test |
| `ui.GoldenScreensScreenshotTest` | GF-UX-18 | Roborazzi (Robolectric) |

---

## 26. Open issues

Items this document needs but DECISIONS.md does not decide, interpretations of a decision that the user
should confirm, and differences with the other specs (ALGORITHMS_PLANNING.md, ANDROID.md,
CHALLENGE_ENGINE.md, DATA_MODEL.md, the OAP definition) that must be aligned. Each row names the default
this document uses until it is resolved. None of them contradicts DECISIONS.md. Items closed by the
2026-10-07 decisions (D13-D16, F21-F27, C37-C65) or by the owning documents are kept for traceability
and marked "Resolved by ...".

| Id | Status | Issue | Default used in this document | Needs |
|---|---|---|---|---|
| OI-01 | Open | The phone model and Android version are unknown (D2). They decide whether Android 16 Live Updates exist (11.13.3) and which phone-maker battery instructions apply (8.6). | Live Update requested and silently ignored when unsupported; OEM texts from dontkillmyapp.com, verified on the phone at M1. | User: phone model and Android version. |
| OI-02 | Resolved by C65 | Test-day order: F10 does not list the unassisted 5 s one-arm negative that gate criterion 3 needs on a test day. | The wizard renders the order of the definition's test template, which includes the negative per arm after least assistance (14.9); P08 moves max reps to the end, P20 no longer changes the order (OAP_CHALLENGE OAP-OI-14). | User: the P08 decision in challenge setup (no open question for this document). |
| OI-03 | Resolved by D14 / C46 | In-session program rules tagged A were rendered as one-tap suggestions because of D10. | Challenge in-session rules apply automatically with "Rule: ... · Undo"; general items keep Apply / Keep suggestions (11.10). | — |
| OI-04 | Resolved by C63 | D9 "on every set" versus warm-ups logged with "Done". | D9 applies to working sets; warm-ups use Done with `rir_source = missing` (11.5). | — |
| OI-05 | Open | Soreness chips "No / Was, fine now / Yes" map to `never_sore`, `healed`, `still_sore` (E19); their mapping to the readiness scale is the `readiness.soreness_points` param, whose values are still open (DATA_MODEL E19; ALGORITHMS_PLANNING open issue on the soreness scale). | Labels as in 16.3, codes as in E19 (19.4). | Readiness spec owner, after about 8 weeks of answers. |
| OI-06 | Resolved by C53 | `today` had to output a verdict and the close call needed a relative margin. | Verdict `TRAIN / REST_SOFT / REST_HARD` from `today`, `ui.todaycard.close_rel` = 0.10 (19.1, 22.5); ALGORITHMS_PLANNING sections 9, 19.1 and 23.19 now say the same. | — |
| OI-07 | Resolved by C63 | Test-day entries use "Record"; a 3RM is stored with RIR 0. | As in 14.9 (`rir_source = protocol`). | — |
| OI-08 | Resolved by C54 | Platform controller constants outside the params JSON. | `PlatformConfig` (ANDROID section 25, including the hold-timer and display keys of 25.5); telemetry, accessibility and interaction constants of this document are also outside `params_hash`. | — |
| OI-09 | Resolved by F25 | Language of calendar titles the app writes. | Challenge events use the definition's title template (Dutch for OAP, switchable at setup); general sessions English labels; descriptions English (8.10, 10.4, 14.8). | — |
| OI-10 | Open | No "forgot password" in v1 (accounts are managed by the owner of the shared Supabase project). | None in v1 (8.2). | User confirmation. |
| OI-11 | Resolved by CHALLENGE_ENGINE §8.5 | What logging against a hard rule means for a challenge ("Log a session anyway", "Add anyway"). | A warning and a `rule_override` record; any further consequence is decided by the definition's rules (9.3, 11.12). | — |
| OI-12 | Resolved by C64 | Pain severity mapping Mild 2 / Moderate 5 / Sharp 8. | `ui.prompts.pain_severity_map` (16.2, 19.4). | — |
| OI-13 | Resolved by CHALLENGE_ENGINE §3.7 | Meaning of "Shortest lowering". | The minimum lowering time of the set in `ecc_s_actual` (11.7.5). | — |
| OI-14 | Resolved by CHALLENGE_ENGINE §6.4 | Deload transform of the top-hold rounds. | Rounds are halved and rounded up (3 -> 2) (11.7.4, GF-UX-02). | — |
| OI-15 | Open | The one-arm plank has a "Start" chip instead of quick-done, because its progression needs per-set seconds and the hip-rotation flag; the daily hang can be quick-done. | 9.6. | User confirmation. |
| OI-16 | Resolved by C41 | Params key casing. | Lower snake_case constant names (`ui.todaycard.close_rel`, 19.0); platform keys keep their ANDROID names. | — |
| OI-17 | Resolved by C39 | Event names had to be reconciled with the DATA_MODEL registry. | The DATA_MODEL section 9.8.1 registry is canonical; section 20 uses its names and keys (earlier names in 20.13). | — |
| OI-18 | Resolved by C55 / C54 | Rest column names, the rest formula, the adjust steps and the minimum timer differed between this document and ANDROID. | DATA_MODEL section 9.6.4 column names (11.8, 11.13.8, 19.8); taps ±15 s, long-press ±60 s, no timer below 10 s (ANDROID sections 12.3, 25.1); the `rest_before_s` estimate of 19.8, which ANDROID section 12.3 and DATA_MODEL section 9.6.4 reference. | — |
| OI-19 | Resolved by C54 | Threshold of the "Standard exact" alarm option. | 30 logged alarm-clock rests, revert window 20 (11.13.4, 15.7). | — |
| OI-20 | Resolved by C54 | Rest pre-warning in the background. | Only while the app UI is in the foreground (6.7, 11.13.4, 19.8). | — |
| OI-21 | Resolved by C54 | Notification channel ids, habit-reminder mechanism and copy. | The six channel ids of ANDROID section 13.1; habit reminders as ANDROID section 17 (no Done action); the wording of 24.6 ("Rest over"), which ANDROID section 13.1 leaves to this document (ANDROID 13.3's overtime example still reads "Rest done"; informational). | — |
| OI-22 | Resolved by C54 | `fitapp://` deep links. | Internal route names carried in an intent extra (3.4, ANDROID section 7.4); nothing is exported. | — |
| OI-23 | Resolved by C54 | Assist-kg decimals. | 1 decimal on screen, 0.01 kg stored (section 5). | — |
| OI-24 | Open (informational) | Rule ids of the draft definition (`upd.*`, `guard.gripSlip`, ...) differ from `oap.challenge.v1.json` (`ins.*`, `prog.gripSlip.lock`, `prog.A.pin.gated`, `data.test.loads`, `struct.block.rollover`, ...). | The UI renders ids and texts from the definition and never hardcodes them; wireframes and fixtures now use the v1 ids. | None. |
| OI-25 | Resolved by C62 | Gate threshold basis. | `met` on the test-day bodyweight and `thresholdAt(bw)` for the forward threshold, both exposed by CHALLENGE_ENGINE section 10.1 (14.2, 19.7.3). | — |
| OI-26 | Resolved by C37 | Calendar marker format. | `[fitapp:v1 <uuid> <slotKey>]` as the last description line (10.4). | — |
| OI-27 | Open | The warm-up row countdown should survive screen-off through `RestTimerController` with `purpose = interval`, which the ANDROID section 12.3 reducer does not support in v1 (ANDROID OI-27). | An in-app countdown that keeps the screen on and vibrates at zero only while the app is visible (11.7.6, 19.9). | ANDROID reducer support, or acceptance of the fallback; decided in M2 with the warm-up block (D25). |
| OI-28 | Resolved by C63 | No "Add a set" action. | Exercise menu "Add a set", logged as `override_applied {field: sets}` (11.12). | — |
| OI-29 | Resolved by CHALLENGE_ENGINE §4.9 and §4.11 | `manual.override` had no UI, and the pain-retraction format was undefined. | "Correct a value" in the dashboard menu writes `manual.override` (14.2); a retraction is a superseding `pain.reported` with `retracted: true` (20.11). | — |
| OI-30 | Resolved by C54 | The ANDROID idle prompt was missing from section 18. | N-IDLE on channel `app_status`, event `session_idle_prompted` (18). | — |
| OI-31 | Resolved by C63 | No threshold for the time-overrun line. | 10 min over the budget (`session.overrunLineMin`, 11.3). | — |
| OI-32 | Resolved by ANDROID §7.4 | N-PROGRAM, N-SAFETY and N-CELEBRATE needed a route to the Challenges dashboard. | `fitapp://challenge/<enrollment_id>` (3.4, 18). | — |
| OI-33 | Resolved by C4 (ALGORITHMS_PLANNING §19.6) | The `ui.progress` trend was an EWMA in an earlier owner draft, and the "added kg" axis used the current bodyweight. | The SSE-1 band and the per-set bodyweight snapshot (15.2, 19.7.1). | — |
| OI-34 | Resolved by C69 | ALGORITHMS_PLANNING section 19 lacked the UX display requests (prefill details, `substitute` context, elbow follow-up tap, best rules). | Adopted by the owner (19.2, 19.3, 19.4, 19.7.2). | — |
| OI-35 | Resolved by C66 | Whether eccentric (negative) sets are logged with RIR chips. | Lowering seconds plus the effort chips Max / Hard / OK / Easy, no RIR chip (11.7.5). | — |
| OI-36 | Open (new) | ALGORITHMS_PLANNING open issue OI-21: GF-UX-01 and the earlier Wednesday wireframes assumed a training day, while the owner's real-week engine output for Wednesday 7 October is REST soft with Friday recommended (its sections 21.7, 21.9). | GF-UX-01 is labelled synthetic; GF-UX-27 and the wireframes of 9.3 and 10.2 follow the owner's real week. | ALGORITHMS_PLANNING: close its OI-21. |
| OI-37 | Open (new) | The DATA_MODEL section 9.8.1 trigger note of `rest_prewarn` says "while the app or the service is in the foreground"; ANDROID section 12.4 and this document give the pre-warning only while the app UI is visible (C54 as read by ANDROID OI-30). | UI-visible only. | DATA_MODEL registry note alignment (or the user's confirmation, ANDROID OI-30). |

---

## 27. Implementation notes for Claude Code

### 27.1 Packages and files (consistent with ANDROID.md 27.1)

```
app/src/main/kotlin/dev/fitnessapp/app/
  uidet/                         # pure ui modules (specified in ALGORITHMS_PLANNING 19, display details in section 19); scanned by the :algo purity test
    UiResult.kt
    todaycard/TodayCardComposer.kt, ReasonTemplates.kt, BudgetChips.kt, WeekStrip.kt
    prefill/Prefill.kt, Steps.kt, Snap.kt, Deviation.kt, RirChipLayout.kt
    reasonchips/ReasonChips.kt, DoLater.kt
    prompts/PromptPlanner.kt, PromptState.kt
    volumebars/VolumeBars.kt
    warndisplay/WarnDisplay.kt, ScreenScopes.kt
    progress/ChartSeries.kt, PersonalBests.kt, GateRows.kt
    UiParams.kt                  # typed views of the ui.* params (lower snake_case keys, C41)
  timer/RestDisplay.kt (pure), hold/HoldTimerController.kt, hold/HoldCueSchedule.kt (pure); timer and hold constants come from PlatformConfig.kt (ANDROID 25)
  telemetry/TelemetryWriter.kt, EventDraft.kt, EventCatalog.kt (event types, categories, allowed keys), TelemetryConfig.kt
  ui/theme/, ui/components/ (Stepper.kt, RirChipRow.kt, FeltChipRow.kt, ReasonChipRow.kt, UndoSnackbar.kt, WhySheet.kt)
  ui/today/, ui/week/, ui/live/, ui/summary/, ui/exercises/, ui/challenges/, ui/testwizard/, ui/you/, ui/onboarding/, ui/sheets/
  ui/a11y/A11yConfig.kt, Announcer.kt
  format/Numbers.kt, format/Dates.kt  # decimal point always (D12)
app/src/main/res/values/strings.xml   # all copy (section 24); reason templates keyed by code
app/src/test/resources/fixtures/ux/   # GF-UX-*.json (section 25)
```

### 27.2 Order of work by milestone

| Milestone | UX work |
|---|---|
| M1 (logger) | design tokens and components (4, 6); Live Session with set card, RIR and felt chips, unilateral pairs, hold timer, reason chips, skip / do later / add / remove / reorder, rest timer and notification, Summary (without "Next time"), History, Exercises list and detail (preferences), equipment and permissions onboarding; `ui.prefill`, `ui.reasonchips`, `TelemetryWriter`, `RestDisplay`, `HoldCueSchedule`; fixtures GF-UX-06, 07, 08, 14, 15, 16, 17, 19, 23, 24 |
| M2 (challenge) | Challenges tab, dashboard and gate rows, rules and proposals, Correct a value, definition update, setup wizard with cutover, test-day wizard (including the unassisted negative, C65), Pain and Readiness sheets, home habits, calendar read and app-written challenge events, challenge in-session rules with "Rule: ... · Undo" (D14); `ui.prompts`, `ui.progress.gateRows`; fixtures GF-UX-04, 10, 11, 21, 26 |
| M3 (prescription) | targets on the set card from `rx`, suggestion cards (`autoreg`), Why sheet with traces, warnings display; `ui.warndisplay`; fixture GF-UX-12 |
| M4 (planning) | Today card, Week tab, volume bars, Plan it, template picker; `ui.todaycard`, `ui.volumebars`; fixtures GF-UX-01, 02, 03, 05, 09, 13, 20, 22, 25, 27 |
| M5 (review) | Progress charts and PRs (`ui.progress`), review bundle export, Transparency screen; full screenshot matrix GF-UX-18 |

### 27.3 Test names to create first

`uidet.PrefillGoldenTest`, `uidet.ReasonChipsGoldenTest`, `timer.RestDisplayDstTest`,
`timer.HoldTimerScheduleTest`, `format.UxNumberFormatTest`, `telemetry.TelemetryPayloadGoldenTest`,
`telemetry.NoFreeTextInPayloadsTest` (scans every event type of `EventCatalog` with fixture sessions that
contain notes), `telemetry.EventCatalogConsistencyTest` (every emitted type is in `EventCatalog`, every
category is one of the 14 DATA_MODEL values, every payload key is allowed), `ui.live.InputGuardTest`,
`ui.A11ySemanticsTest`, `ui.live.ChallengeRuleLineTest`, then `uidet.TodayCardGoldenTest`, `uidet.GateRowsGoldenTest`,
`uidet.PromptPlanGoldenTest`, `uidet.WarnDisplayGoldenTest`, `uidet.VolumeBarsGoldenTest`,
`uidet.SummaryNextTimeGoldenTest`, `uidet.UiDeterminismTest`, `notify.SessionNotificationContentTest`,
`ui.GoldenScreensScreenshotTest`.

### 27.4 Rules for every session that touches the UI

1. Never compute in the UI what an engine module owns (19.0): no e1RM, set credit, readiness, targets,
   rest durations, time estimates or warnings in `:app`.
2. Never hardcode challenge rule ids, texts, thresholds or test order; render them from the definition
   (OI-24). Never hardcode OAP logic in screens (D3).
3. Every user-visible string goes through `strings.xml` and the formatters; decimal point always (D12).
4. Every new or changed event is first added to the DATA_MODEL section 9.8.1 registry (canonical, C39),
   then to `EventCatalog`, section 20 of this document and `ui_version`; no free text in payloads, ever.
5. Every new logic constant of a ui module is registered in ALGORITHMS_PLANNING section 23.19 (C53); a
   new display constant goes into the constants paragraph of its module in section 19; a new timer,
   notification or reminder constant goes into ANDROID section 25 (`PlatformConfig`, C54). Each has a
   default, bounds, evidence grade and the data that tunes it.
6. Wireframes in this document are illustrative; the fixtures in section 25 are normative. When an
   engine spec publishes golden outputs for the same days, regenerate the UX fixture inputs from them.
7. Run the screenshot matrix (six configurations per state) before installing a build that changes a
   screen; at font scale 2.0 nothing may be truncated.
8. Before M1 code, check that the channel ids (ANDROID section 13.1), event names and payload keys
   (DATA_MODEL section 9.8.1), params keys (C41) and route names (ANDROID section 7.4) used in code match
   their owners, because they are hard to change after the first install.
