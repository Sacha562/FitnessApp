# LENS ux_ui

## SUMMARY
UX lens, terse. Recommendation: native Android app (Kotlin + Jetpack Compose, Material 3 baseline components, a few Expressive ones, calm standard motion). Five bottom-nav tabs: Today / Week / Exercises / Challenges / You. A live session opens as a full-screen flow and shrinks to a mini-bar when minimised. Logging core: one card for the current set with 64dp steppers. The row of reps-in-reserve (RIR) chips (0,1,2,3,4,5+) is also the 'log set' button, so logging a set takes 1 tap and always records RIR. Reason chips appear only when the user changes a target; skipping needs one tap on a reason. Rest timer starts on its own after each set, uses an absolute end time, shows a 96sp countdown, has -15/+15/Skip, sends a chronometer notification (an Android 16 Live Update where available) plus an exact alarm with a haptic pattern, so it works with the screen off. A PWA cannot do this reliably. Explanations come in layers: up to 3 reason chips everywhere, a 'Why' sheet with numbers, rule ids, citations and algorithm version, and a trace export for Claude Code reviews. Cheap feedback: optional 1-tap readiness before the session, session effort (sRPE 0-10) and joint pain after it, and a soreness check at the next session that trains that muscle. A tap budget of 6 or fewer per session limits prompts. Everything runs offline-first and logs UI events (suggestion shown/accepted, overrides, rest changes, prompt answers) so the algorithms can be tuned later. Challenges tab is driven by challenge definitions: phase gates with progress bars, block/deload/test-day status, test-day wizard, rule-event timeline, and a pain-report button that starts the challenge's pain protocol. The OAP program needs left/right sets (left first, right no more than left), timed holds with lead-in countdown and correction afterwards, assistance in pin lb (≈ effective kg = lb*0.4536+6.5) or in fingers, set-stop reasons such as grip slip, daily home tasks, and a fixed 10-minute warm-up. 12 deterministic UI-side algorithms are specified with constants and logged fields.

## FINDINGS
### Competitor pattern: Hevy (logging + rest timer) [engineering-fact]
Rest timer auto-starts when set marked complete; per-exercise rest 5 s-5 min; +/-15 s adjust or skip; global default in settings; notification at end. Lock-screen live activity / Android notification shows next exercise, sets done, prescribed weight x reps, duration, rest timer; actions: mark set complete, +/-15 s, skip. Set rows with PREVIOUS column (last time or last time in this routine), tap set number -> warm-up/drop/failure type, optional RPE column, per-exercise notes, three-dot menu for reorder/replace/remove; finish screen = name, duration edit, notes. Bodyweight types: bodyweight / assisted (effective = BW - assistance) / weighted bodyweight; duration exercises PR = best time. Takeaway: copy auto-rest, +/-15 s, lock-screen actions, previous-values hint; avoid the dense spreadsheet table as main in-gym view.
SRC: https://www.hevyapp.com/features/workout-rest-timer/ | https://www.hevyapp.com/features/live-activity/ | https://www.hevyapp.com/features/track-workouts/ | https://help.hevyapp.com/hc/en-us/articles/38386262243223 (search summary)

### Competitor pattern: Alpha Progression (per-set targets) [engineering-fact]
Per working set the app suggests specific weight, reps, optional RIR ('blue bubble'), based on previous performance + current workout. Snaps to actually available gym weights and will move reps up/down when increment too coarse. RIR optional, half values allowed, toggle per plan/workout. Deload flag per workout changes recommendations. Warm-up calculator, plate calculator (falls back to next lower achievable weight). Horizontal exercise-thumbnail tabs at top, current exercise focus. Android notification for live workout. Takeaway: target shown next to the input, rounding to loadable weights, deload as a first-class flag.
SRC: https://alphaprogression.com/en/blog/alpha-progression-guide

### Competitor pattern: Gravl / Fitbod / JuggernautAI / RP / Boostcamp / Liftosaur [moderate]
Gravl: tap a target weight to see why it was picked (named differentiator vs black-box AI). Per-exercise Easy/Ideal/Hard rating. Rep ladder 8-7-6 -> 8-8-6 -> 8-8-7 -> 8-8-8, then +1 equipment step. Records painful movements, avoids them, re-checks every 2 weeks. Fitbod: per-muscle recovery 0-100% heat map, full recovery after 6 days by default, picks fresher muscles. JuggernautAI: readiness from warm-up sets with 3 options (worse / as expected / stronger), e.g. top set target 190 -> 192.5 kg. RP Hypertrophy: pump, soreness, workload and joint-pain feedback drives weekly set changes (+1 set/muscle/week when recovery is on track). One third-party review says joint pain is not used in the calculation. Boostcamp: RPE/RIR first-class per set; swap/reorder/superset hidden behind one menu to keep the screen clean. Liftosaur: progression rules written as text script (lp, dp, sum), the model for data-driven, readable challenge rules.
SRC: https://www.sensai.fit/blog/gravl-app-review-2026 | https://fitbod.me/blog/muscle-recovery/ (search summary) | https://www.jtsstrength.com/?p=685137 | https://dr-muscle.com/rp-hypertrophy-app-for-strength-training-expert-review/ | https://arvo.guru/resources/methods/rp-training | https://www.boostcamp.app/workout-tracker | https://www.liftosaur.com/docs

### Gym-floor ergonomics: touch targets, grip, wet fingers [moderate]
M3/Android minimum 48x48dp with 8dp or more between targets (WCAG 2.5.8 AA / 2.5.5 AAA). Parhi, Karlson & Bederson 2006 (MobileHCI): one-handed thumb targets need 9.2 mm (discrete) / 9.6 mm (serial taps). At 1dp = 0.159 mm that is about 58-60dp, so primary in-gym controls should be 56-64dp. Hoober 2013 (1,333 observations): 49% one-handed, 36% cradled, 15% two-handed; easy thumb reach is bottom-centre, top corners hardest, so put Log/RIR/steppers in the bottom 40% of the screen and only passive info at the top. Wet or sweaty fingers cause missed and phantom touches on capacitive screens (RainCheck, Wobbrock lab ICMI 2018: filtering improved touch accuracy 47.9%, selection time 80%). Hence: big targets, no precision gestures (no swipe-to-complete), undo snackbars instead of confirm dialogs, debounce double taps (300 ms).
SRC: https://support.google.com/accessibility/android/answer/7101858 | Parhi P, Karlson A, Bederson B. MobileHCI 2006, doi:10.1145/1152215.1152260 | Hoober S. 2013 https://www.uxmatters.com/mt/archives/2013/02/how-do-users-really-hold-mobile-devices.php | https://faculty.washington.edu/wobbrock/pubs/icmi-18.02.pdf

### Numeric input: steppers vs keypad [expert-opinion]
Steppers suit small discrete changes (10 or fewer increments) and are bad for large deviations, where text entry is better. Gym use: steppers step by the loadable increment (1 rep; 2.5 kg barbell; next dumbbell; machine stack step; 5 lb assisted pin; 1 s for holds). Tap the value to open a numeric keypad sheet with nearest-loadable chips. Long-press auto-repeat with acceleration. Show the target and the last result next to the input so the user rarely types.
SRC: https://balsamiq.com/learn/ui-control-guidelines/steppers/ | NN/g stepper guidance (via search summary)

### Material 3 Expressive (2025-2026) status and tokens [engineering-fact]
Announced May 2025, backed by 46 studies and 18,000+ participants. Eye-tracking: key elements found up to 4x faster than baseline M3, and the age gap in that disappeared. Google warns: restraint for utility contexts, 'no amount of expressive design beats basic functionality', keep established patterns and labelled icons. New components: button groups, FAB menu, loading indicator, split button, docked/floating toolbars (replace the bottom app bar). Flexible navigation bar is 64dp tall (was 80dp), active indicator 56dp, labels not bold when selected. Compose: material3 1.4.0 stable needs @ExperimentalMaterial3ExpressiveApi; 1.5.0-alpha promotes ButtonGroup, FloatingToolbar, FAB/FAB menu, toggle buttons, expressive list items to stable, while MaterialExpressiveTheme is still experimental. Tokens: type scale Display L/M/S 57/45/36sp, Headline 32/28/24, Title 22/16/14, Body 16/14/12, Label 14/12/11. Corners XS 4, S 8, M 12, L 16, L-increased 20, XL 28, XL-increased 32, XXL 48dp, full 50%. Standard springs: spatial fast damping 0.9 / stiffness 1400, default 0.9/700, slow 0.9/300; effects fast 1.0/3800, default 1.0/1600, slow 1.0/800.
SRC: https://design.google/library/expressive-material-design-google-research | https://supercharge.design/blog/material-3-expressive | https://developer.android.com/jetpack/androidx/releases/compose-material3 | https://github.com/material-components/material-components-android/blob/master/docs/theming/Motion.md | https://github.com/material-components/material-components-android/blob/master/docs/theming/Typography.md | https://github.com/material-components/material-components-android/blob/master/docs/theming/Shape.md | https://proandroiddev.com/material-3-expressive-design-a-new-era-part-2-6a93483c98b0

### Dark mode [engineering-fact]
M3 default dark surfaces are dark grey, not #000 (shadows stay visible, less eye strain). Elevation is shown by lighter tonal surface roles (surfaceContainerLowest to Highest), not shadows. Pure-black OLED mode is opt-in only. Contrast: text 4.5:1 or more (WCAG 1.4.3), UI parts and chart lines 3:1 or more (1.4.11). Gym lighting is bright, so offer a high-contrast option and test outdoors and under fluorescent light.
SRC: https://m2.material.io/design/color/dark-theme | https://material.io/blog/tone-based-surface-color-m3

### Rest timer with screen off: platform facts [engineering-fact]
Notification countdown is drawn by the system with setUsesChronometer(true) + setChronometerCountDown(true) (API 24+) + setWhen(endAt), so no per-second updates are needed. Android 16 Live Updates (promoted ongoing, shown on lock screen, always-on display and as a status-bar chip) require: POST_PROMOTED_NOTIFICATIONS in the manifest, setRequestPromotedOngoing(true), setOngoing(true), contentTitle set, Standard/BigText/Call/Progress/Metric style, no custom RemoteViews, not colorized, channel importance not MIN. Chip text via setShortCriticalText or chronometer countdown, chip max 96dp. Foreground service types: 'health' needs ACTIVITY_RECOGNITION or a body-sensor/heart-rate permission (or HIGH_SAMPLING_RATE_SENSORS); 'shortService' is capped at about 3 min, too short for 3-5 min OAP rests; 'specialUse' has no runtime prerequisite (Play review only). Exact alarms: SCHEDULE_EXACT_ALARM denied by default for new installs on Android 14+. USE_EXACT_ALARM is auto-granted but Play policy limits it to alarm/calendar apps, which doesn't matter for a sideloaded personal APK. POST_NOTIFICATIONS is a runtime permission on Android 13+. PWA: no alarm API, Notification Triggers still experimental, timers throttled in background, so a PWA cannot meet 'timer works with screen off + vibrate at end'. The Hevy-style lock-screen actions (complete set, +/-15 s, skip) are possible as notification actions.
SRC: https://developer.android.com/develop/ui/views/notifications/live-update | https://developer.android.com/about/versions/16/features/progress-centric-notifications | https://developer.android.com/develop/background-work/services/fgs/service-types | https://developer.android.com/about/versions/14/changes/schedule-exact-alarms | https://developer.android.com/reference/androidx/core/app/NotificationCompat

### Haptics and audio [engineering-fact]
Android 'clear haptics' principle: crisp, not buzzy. HapticFeedbackConstants.CONFIRM / REJECT (API 30+); View.performHapticFeedback needs no VIBRATE permission. Use CONFIRM on set logged, REJECT on an invalid entry, CLOCK_TICK per stepper step. Rest end: VibrationEffect waveform (needs VIBRATE) so it is felt in a pocket. Optional end tone with AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK so music in earbuds ducks rather than stops.
SRC: https://developer.android.com/develop/ui/views/haptics/haptic-feedback | https://source.android.com/docs/core/interaction/haptics/haptics-ux-foundation

### RIR self-report accuracy: drives chip design [strong]
Halperin et al. 2022 (Sports Med, meta-analysis): people under-predict reps to failure by 0.95 reps (95% CI 0.17-1.73), with very high heterogeneity (I^2 = 97.9%). Accuracy is better closer to failure and when the set to failure is 12 reps or fewer (beta 0.06 for 12 or fewer vs 0.47 for more than 12), and slightly better in later sets (beta -0.07). Zourdos 2016 / Helms 2016: RIR-based RPE scale validated for trained lifters. UI implications: whole-number chips 0-5+ (half values are false precision at about 1-rep error). Label 'reps left in the tank'. Treat RIR 5+ and sets over 12 reps as low-confidence (flag in log). Don't prefill the RIR chip, to avoid anchoring.
SRC: Halperin I et al. 2022 Sports Med 'Accuracy in predicting repetitions to task failure in resistance exercise: a scoping review and exploratory meta-analysis' https://sportrxiv.org/index.php/server/preprint/view/67 | Zourdos MC et al. 2016 J Strength Cond Res doi:10.1519/jsc.0000000000001049 | Helms ER et al. 2016 Strength Cond J doi:10.1519/ssc.0000000000000218 | Zourdos MC et al. 2021 J Strength Cond Res doi:10.1519/jsc.0000000000002995

### Cheap subjective feedback is worth collecting [strong]
Saw, Main & Gastin 2016 (BJSM systematic review): subjective self-reported measures track training response with better sensitivity and consistency than common objective measures. Foster et al. 2001 (JSCR): session RPE (CR-10, asked about 30 min after the session) is a valid single-question load measure; session load = sRPE x minutes. RP-style per-muscle pump/soreness/workload is richer but costs taps. JuggernautAI shows a 3-option readiness is enough to shift loads. Design: 1-tap readiness (Worse / Normal / Better), sRPE 0-10, joint pain (none or location + severity), soreness asked at the next session for the muscles about to be trained.
SRC: Saw AE, Main LC, Gastin PB. Br J Sports Med 2016 (Europe PMC EXT_ID 26423706) | Foster C et al. J Strength Cond Res 2001 'A new approach to monitoring exercise training' (EXT_ID 11708692) | https://www.jtsstrength.com/?p=685137

### Explaining algorithm decisions [moderate]
Tintarev & Masthoff: explanations serve 7 aims (transparency, scrutability, trust, effectiveness, persuasiveness, efficiency, satisfaction), and these can conflict. Pick transparency + scrutability (user can say 'this is wrong') + efficiency. Laato et al. 2022 systematic review: goals are understandability, trust, transparency, controllability, fairness; prefer on-demand explanations of key functions over explaining the whole system. Gravl markets 'tap target to see why'. Recommendation: layered disclosure. L1 = up to 3 reason chips inline. L2 = Why sheet with numbers, rule id, evidence grade, citations, 'use mine anyway'. L3 = trace JSON export (inputs, intermediate scores, algorithm version) for Claude Code reviews. Override reasons double as scrutability feedback.
SRC: Tintarev N, Masthoff J. 'Evaluating the effectiveness of explanations for recommender systems' UMUAI 2012 doi:10.1007/s11257-011-9117-5 | https://arxiv.org/pdf/2006.08672 | Laato S et al. 2022 'How to explain AI systems to end users: a systematic literature review and research agenda' Internet Research | https://www.sensai.fit/blog/gravl-app-review-2026

### Calendar + Health Connect integration facts [engineering-fact]
CalendarContract (on-device provider) with READ_CALENDAR reads every synced account and calendar, including the Todoist-synced one with stale 2022-23 'Gym' all-day tasks. So the user needs a calendar allowlist (default: primary only) and all-day events dropped. It works offline with no OAuth. The alternative is the Google Calendar API with the narrowest scope calendar.events.readonly via OAuth, only needed if a server must read the calendar. Health Connect: ExerciseSessionRecord can read runs from other apps (affects leg/recovery recommendations) and write gym sessions. PlannedExerciseSessionRecord (training plans, Health Connect 1.1.0-alpha12+, check feature flag FEATURE_PLANNED_EXERCISE) has no weight fields; permissions READ/WRITE_PLANNED_EXERCISE. WeightRecord could supply bodyweight (needed for weighted/assisted load math).
SRC: https://developer.android.com/identity/providers/calendar-provider | https://developers.google.com/calendar/api/auth | https://developer.android.com/health-and-fitness/guides/health-connect/develop/training-plans | https://developer.android.com/health-and-fitness/health-connect/experiences/workouts

### Assets: exercise data + muscle map [engineering-fact]
free-exercise-db (yuhonas, Unlicense): 873 exercises with id, level, force, mechanic, equipment, primary/secondary muscles, instructions, images. Good seed for the catalog, but its muscle vocabulary needs mapping to the app's muscle list. Muscle-map libraries: react-body-highlighter (MIT, about 18 regions: trapezius, upper-back, lower-back, chest, biceps, triceps, forearm, front/back deltoids, abs, obliques, adductor, abductors, hamstring, quadriceps, calves, gluteal, head, neck); flutter_body_heatmap (MIT); body-highlighter (vanilla JS). Gap: no separate lats, side delts or forearm flexor/extensor split, which matter for a pull-focused / OAP user. So build a custom simplified SVG (about 22 regions) as a Compose ImageVector.
SRC: https://apis.io/providers/free-exercise-db/ | https://github.com/giavinh79/react-body-highlighter | https://github.com/lahaxearnaud/body-highlighter | https://pub.dev/packages/flutter_body_heatmap

### OAP challenge: specific UX requirements (from artifact data) [engineering-fact]
(1) Left/right sets with ordering: 'left first; right does no more clean reps than left'. (2) Timed holds whose stop condition is torso rotation, not letting go (hang 4x10 s/arm; hold at 90 degrees 6-8 s; straight-arm hold 6 s; top hold 4 short attempts x 3 rounds per arm). The hand is on the bar, so the phone can't be tapped during the hold: needs a lead-in countdown, audio cues, and a correction stepper afterwards. (3) Assistance units: machine pin in lb, 5 lb steps, lower = harder; effective assist about pin_lb*0.4536 + 6.5 kg (artifact: 40 lb about 24.6 kg, 50 lb about 29 kg, 35 lb about 22.4 kg); phase 2 adds finger assistance 4 -> 3 -> 2 -> 1 fingers; towel as fallback when the machine is occupied. (4) Weighted: 'BW + x kg' on the dip belt, loadable from plates (e.g. 47.5 = 2x20+5+2.5). (5) Set-stop reasons: grip slip before the last rep (stop set, no load increase), torso rotation, elbow pain (skip negatives at any hint of pain). (6) Fixed 10-min warm-up (3 min row, 20 face pulls, 10 scap pulls, 2 warm-up sets). (7) Non-gym tasks: daily 2-arm hang 10 min at 17:15 (skipped on A-day), home one-arm plank 2x/wk. (8) Per-exercise rest: 3 min, 2.5 min, 2 min, 90 s, 60 s. (9) Blocks of 4 build weeks + 1 deload; deload Sunday = test day replacing B; test postponed after a missed week or pain. (10) Test-day fields: bodyweight, 3RM, 1RM, max reps, one-arm hang L/R s, top hold L/R s, least assistance for 1 rep. (11) Phase gates per arm with current values: 1RM 42.8/44, hang L15 R20 of 20 s, 5 s negative not reported yet, top hold L0.5 R1 of 8 s. (12) Max 3 heavy unilateral sessions/week. (13) Program text is in Dutch.
SRC: C:\Users\sivuc\.claude\projects\C--Users-sivuc-projects-FitnessApp\23e9a33b-aa17-4d4a-80e1-fef330676224\tool-results\artifact-518dd69c-1791372722-e794.html (user's program, read as data)

### Visual identity continuity [expert-opinion]
The OAP artifact the user already uses has a coherent token set: light ground #F1F4F1, surface #FFFFFF, ink #131918, accent teal #0B6A60, signal/amber #9C5217, warn/red #8E3226. Dark: ground #0F1413, surface #161D1C, ink #E9EEEB, accent #4FB5A6, signal #D08B4E, warn #D4796C. Monospace uppercase labels, tabular numbers, 3px radii. Seeding the app's M3 tonal palette with teal #0B6A60 and mapping signal = advisory and warn = pain/safety gives continuity with no extra design debt. Note M3 corners (16-28dp) are rounder than the artifact's 3px; pick one.
SRC: artifact HTML (data)

### Rest-interval defaults (UI fallback only; engine owns the values) [moderate]
Schoenfeld 2016: 3-min rest beat 1-min for strength and hypertrophy in trained men. Singer et al. 2024 Bayesian meta-analysis: rest over 60 s gives a small hypertrophy benefit, with little difference beyond about 90 s. The UI fallback table should therefore lean long (heavy 180 s, compound 120 s, isolation 90 s) and let the timer be overridden, logging each override.
SRC: Schoenfeld BJ et al. J Strength Cond Res 2016 doi:10.1519/jsc.0000000000001272 | Singer A et al. Front Sports Act Living 2024 doi:10.3389/fspor.2024.1429789

### Weekly volume display: fractional set counting [moderate]
Pelland et al. (Sports Med 2026, meta-regressions) counted sets fractionally: direct (primary) muscle 1.0, indirect (secondary) 0.5. Use the same counting in Week-screen muscle bars, so the UI matches the engine's volume model.
SRC: Pelland JC et al. 'The Resistance Training Dose Response...' Sports Med 2026 doi:10.1007/s40279-025-02344-w

### Information architecture + screen list [expert-opinion]
Bottom nav (M3 flexible nav bar 64dp, icon + label, 5 destinations): TODAY (decide + start: verdict card, time-budget chips, calendar event, challenge session, home tasks, week strip) | WEEK (recommended vs planned per day, muscle volume bars vs targets, block/deload banner, 'Plan it' to calendar) | EXERCISES (catalog: search, filter chips by muscle/equipment/pattern/preference, list, detail with muscle map, 5-level preference, history sparkline, loading increments, default rest, alternatives) | CHALLENGES (library with on/off toggle, active dashboard: phase stepper, gate progress, block/week, next test, sessions this week, test-day wizard, rule-event timeline, report pain) | YOU (Progress: e1RM charts, weekly volume, PRs, bodyweight; History: sessions -> detail/edit; Preferences: set/rep style, muscle goals improve/maintain/off, availability (chess Mon, weekend 09-16), equipment profile and loadable increments, calendars allowlist, units, rest defaults, feedback prompts, theme; Transparency: algorithm versions, export trace/data). Full-screen flows without nav: Live Session, Session Summary, Test Day wizard, Onboarding. While a session is minimised, a persistent mini-bar sits above the nav: '* Session C 32:10 Rest 1:42 >'. Sheets: Why, Swap, Add exercise, Skip reason, Keypad, Readiness, Post-session check.
SRC: M3 navigation bar guidance (3-5 destinations) | Hevy/Strong minimised-workout pattern

### Wireframe: Today (3 states) [expert-opinion]
STATE PLANNED+RECOMMENDED
+-----------------------------------+
| Wed 7 Oct                     (i) |
| Train today?                      |
| +-------------------------------+ |
| | GYM TODAY              19:45  | |
| | OAP challenge - Session C     | |
| | Volume & technique            | |
| | + Push top-up (dips, OHP)     | |
| | 5 exercises - ~70 min         | |
| | [Planned 19:45] [A 48 h ago]  | |
| | [Push 3/8 sets wk]      Why > | |
| | Time:  45   60  (75)  90+     | |
| | [====== Start session ======] | |  56dp, full width
| | Other options >               | |
| +-------------------------------+ |
| CALENDAR Gym - sessie C 19:45     |
| HOME [ ] 2-arm hang 10 min 17:15  |
|      [ ] 1-arm plank 3x20 s/side  |
| WEEK  M  T  W  T  F  S  S         |
|       -  v  *  .  A  -  T         |
+-----------------------------------+
| Today Week Exercises Challenges You|

STATE NOT PLANNED: card title 'No gym planned - if you go today:' ranked list
| 1 Legs + core   fresh 100%  ~50' >|
| 2 Push          fresh  80%  ~45' >|
| 3 Pull  (!) OAP-A is tomorrow     |
+ 'Plan it in calendar' text button.

STATE REST ADVISED: 'Rest today' + reason 'Session B (max strength) tomorrow, rest the day before' + secondary 'Train anyway >' (opens ranked list with warnings).
DONE: summary chip + 'Next: Thu Session A'.
SRC: design synthesis

### Wireframe: Live session (set card, rest, L/R, holds, skip, summary) [expert-opinion]
MAIN
+-----------------------------------+
| v  Session C      32:10    Finish |  v = minimise
| ==========---------  6/15 sets    |
| v Assisted OAP (L/R)      3/3     |  compact list: tap = jump,
| > Weighted pull-up        1/4     |  long-press = drag reorder
| o Scapular pulls (L/R)    0/4     |
| o One-arm hang (L/R)      0/4     |
| o Prehab                  0/3     |
| + Add exercise                    |
+-----------------------------------+
| WEIGHTED PULL-UP  Set 2 of 4   :  |  : = swap/skip/later/note
| Target BW +20 kg x 5  RIR 2   (i) |  (i) = why this target
| Last   +20 x 5 5 5 4  (17 Sep)    |
|  ( - )   BW + 20.0 kg   ( + )     |  64dp steppers
|  ( - )      5 reps      ( + )     |
| [too easy][too hard][good day]... |  only if changed
| Reps left in the tank?            |
| [ 0 ][ 1 ][ 2 ][ 3 ][ 4 ][ 5+ ]   |  56x56dp, tap = LOG
+-----------------------------------+
REST (replaces action area; snackbar 'Set 2 logged - Undo' 6 s)
| REST                next: set 3   |
|             2:18                  |  96sp tabular
| ======================-------     |
|  [ -15 s ]  [ Skip ]  [ +15 s ]   |
| Next: BW +20 kg x 5  RIR 2        |
| ^ Suggest +2.5 kg (RIR 4 vs 2)    |
|        [Apply]   [Keep]           |
OVERTIME shows '+0:23' in signal colour.

UNILATERAL
| ASSISTED ONE-ARM PULL-UP  2/5     |
| Pin 40 lb (~24.6 kg assist) v=harder|
|        LEFT (first)   RIGHT       |
| reps  (-) 3 (+)     (-) 3 (+)     |
| Left: reps left? 0 1 2 3 4 5+     |  then Right chips
| hint: Right <= Left clean reps    |

TIMED HOLD
| ONE-ARM DEAD HANG  L  1/4         |
| Target 10 s, stop when torso turns|
|      [      START (3-2-1)     ]   |  beep each 5 s, double at target
| 00:07  (tap anywhere = stop)      |
| Result (-) 9 s (+)                |
| Felt: [Max][Hard][OK][Easy] = log |

SKIP (: > Skip) bottom sheet, one tap completes:
[No time][Occupied -> Do later][Pain][Feeling bad][Too tired][Equipment missing][Other]

SUMMARY
| Session C done - 68 min - 15 sets |
| PR Weighted pull-up e1RM +43.6 ^0.8|
| Next time                          |
|  Weighted pull-up +20 -> +22.5 kg  |
|   (all 4 sets at RIR >= 2)         |
|  Assisted OAP stays 50 lb          |
|   (needs 2 easy sessions)          |
| Quick check (optional)             |
|  Effort 0 1 2 3 4 5 6 7 8 9 10     |
|  Joint pain [None][Elbow][Shoulder]|
|   [Wrist][Other] -> mild/mod/sharp |
|  Note ...            [ Save ]      |
SRC: design synthesis; Hevy/Alpha Progression patterns

### Wireframe: Week, Catalog, Challenges, Warnings [expert-opinion]
WEEK
| Week 41 - Block 2 wk 1/5    < >   |
|      RECOMMENDED     PLANNED (cal) |
| Mon  Rest (chess)    -             |
| Tue  OAP-C  v done   sessie C v    |
| Wed  Rest            -             |
| Thu  OAP-A           sessie A 19:45|
| Fri  Legs+Push 45'   -   [+ Plan]  |
| Sat  Rest (pre-B)    -             |
| Sun  OAP Test        testdag 13:00 |
| Muscle volume (hard sets this wk)  |
| Lats   ########.--  9.5/10-14  imp |
| Biceps ######---    6/6-10     mnt |
| Chest  ##::-------  2(+3 plan)/6 ! |
| Quads  off (your preference)       |
(# done, : planned remaining, - to target band)

CATALOG
| [Search exercises...]             |
| [Muscle v][Equipment v][Fav][Hide never]
| * Leg press      Quads Glutes  Mach|
|   Seated calf    Calves        Mach|
|   Bulgarian sq   Quads Glutes DB  (dislike)|
Detail: front/back muscle map; preference segmented [Fav][Like][Neutral][Dislike][Never]; helper text 'Dislike: still used if it is the best way to train a goal muscle'; 'Never: never used; app warns if a muscle loses coverage'; history sparkline; loading step; default rest; L/R or timed flags; alternatives.

CHALLENGES
| ACTIVE                            |
| One-arm pull-up (supinated) [On]  |
| Phase 1 of 3 - Block 2 - Wk 1/5   |
| Next test: Sun 11 Oct             |
| Gate to phase 2       2/4 met     |
|  1RM >= +44 kg   42.8  #########- |
|  Hang >= 20 s    L15 R20 #####v   |
|  5 s negative    not tested       |
|  Top hold >= 8 s L0.5 R1 #-------  |
| This week: C v  A Thu  Test Sun   |
| [Log test day] [Report elbow pain]|
| AVAILABLE  + (other definitions)  |
| Detail tabs: Overview|Rules|History|Tests
Rules tab shows the challenge's rules in plain language, each with an id. History shows rule events, e.g. '19 Sep anchor 20 kg 4x5 -> loads recalculated'.

WARNING (non-blocking)
| (!) Lats (improve): app uses 3 sets|
| on 2 exercises. 2 sets/wk is below |
| the range where gains plateau.     |
| [Why]   [Use mine anyway]   [x]    |
In session: small amber dot on '3 sets (you prefer 2) (i)'. Why sheet: plain summary; your preference vs app's choice; numbers; evidence grade + citations; toggle; rule id + algorithm version.
SRC: design synthesis

### Visual design spec (minimal) [expert-opinion]
Grid 4dp. Screen margin 16dp. Card padding 16dp. Gaps 8/12/16/24. Touch: 48dp minimum everywhere; 56-64dp for in-session primary controls; 8dp minimum between targets. Type: Roboto Flex (OFL) or system Roboto, max 2 weights (400/600), tabular figures ('tnum') for all numbers. Current-set values 45sp/600 (Display M). Rest countdown 96sp/600. Card titles 22sp (Title L). Body 16sp. Labels 12-14sp, uppercase only for small overlines. Color: seed #0B6A60 into an M3 tonal scheme; semantic roles advisory = #9C5217 light / #D08B4E dark, safety/pain = #8E3226 / #D4796C, done/primary = teal. Muscle goals: improve = primary, maintain = onSurfaceVariant, off = outline dashed/hidden. One accent per screen, no gradients, no colour-only meaning (always icon + text). Surfaces: tonal containers, no shadows in dark. Shape: cards 16dp, chips 8dp, primary buttons full (pill), sheets 28dp top. Icons: Material Symbols Rounded, weight 400, optical size 24, filled only for selected/done. Motion: standard springs (spatial default 0.9/700, effects default 1.0/1600); expressive bounce only on set-logged micro-confirm and gate-achieved; honour 'remove animations' (ANIMATOR_DURATION_SCALE = 0). Haptics as above. Must test at font scale 1.3 and 2.0 (Android 14 nonlinear scaling). Keep-screen-on during active session (toggle, default on). Theme follows system, dark-first QA.
SRC: M3 tokens (see above) | WCAG 2.2 1.4.3/1.4.11/2.5.8

## ALGORITHMS
### UI-01 Today verdict composer + reason-chip explainer
PURPOSE: Turn the engine's scored session options + calendar + challenge + rest advice into one deterministic Today card state, a primary option, alternatives and up to 3 reason chips; also feeds the Why sheet.
IN: engine.options[] {option_id, session_type (push|pull|legs|combo|challenge:<code>), score in [0,1], est_minutes, reasons[] {code, weight (signed), params}}; calendar_today[] (classified by UI-08); challenge_due[] {challenge_id, session_code}; rest_advice {flag, reason_code, params}; completed_today bool; active_session id|null; algo_version.
OUT: card_state in {IN_PROGRESS, DONE, PLANNED, PLANNED_BUT_REST, OPTIONAL, REST}; primary option; alternatives[<=3]; chips[<=3] {code, text}; confidence in {clear, close}; why_payload.
PSEUDO:
if active_session: return IN_PROGRESS
if completed_today: return DONE (+ next planned)
opts = sort(options, key=(-score, TYPE_ORDER[type], option_id))  # TYPE_ORDER: challenge=0, planned_match=1, improve=2, maintain=3, other=4
best = opts[0]; second = opts[1] or null
planned = [e in calendar_today if e.is_gym]
if planned:
  primary = match(planned[0], opts) or best   # match via UI-08 cost
  state = (rest_advice.flag and primary.score < REST_SCORE) ? PLANNED_BUT_REST : PLANNED
elif rest_advice.flag and best.score < REST_SCORE: state = REST; primary = null
else: state = OPTIONAL; primary = best
reasons = primary.reasons (or [rest_advice] if REST)
chips = take(sort(reasons, key=(-abs(weight), code)), MAX_CHIPS)
chip.text = TEMPLATE[code].format(params)   # e.g. PLANNED_CAL 'Planned {time}', HOURS_SINCE '{muscle_or_session} {h} h ago', VOLUME_DEFICIT '{muscle} {done}/{target} sets wk', CHALLENGE_DUE '{session} due ({n}/{k} this wk)', REST_BEFORE_HEAVY 'Rest before {session} tomorrow', ONLY_OPTION 'Only option for {muscle}', PREF_LIKED 'You like {exercise}', SORE '{muscle} still sore'
confidence = (second == null or best.score - second.score >= CLOSE_MARGIN) ? clear : close
alternatives = opts[1:1+MAX_ALTS]; show inline only if confidence == close or state in {REST, PLANNED_BUT_REST}
why_payload = {all reasons with weights, scores of all options, inputs snapshot hash, algo_version}
CONST: REST_SCORE = 0.5 (option below midpoint while rest advised -> show rest; tune from logs). CLOSE_MARGIN = 0.15 (normalised score gap below which the choice is shown as a close call). MAX_CHIPS = 3 (glanceable; matches L1 disclosure). MAX_ALTS = 3 (Hick's law: few choices). Templates stored as versioned strings (EN, optional NL).
LOG: ui_event 'today_card_shown' {state, options[id,type,score], chips[codes], confidence, algo_version, calendar_event_ids}; 'today_action' {start_primary | start_alt(rank) | rest | train_anyway | dismiss, latency_ms}; 'why_opened' {screen, code}. Tuning: how often the user starts a non-primary option (and which reason codes preceded it), and REST overrides followed by bad sRPE/pain, are used to recalibrate REST_SCORE, CLOSE_MARGIN and reason weights.

### UI-02 Session time-boxing (time-budget chips)
PURPOSE: User picks the available time (45/60/75/90+); the app deterministically trims the recommended session to fit, so 'no time' is handled before the session rather than by skipping later.
IN: exercises[] {id, tier (0 = challenge core/prehab/safety, 1 = improve-muscle compound, 2 = maintain, 3 = isolation/accessory), sets, min_sets, reps_target or hold_s, rest_s, unilateral bool, preference (-2..+2), engine_priority}; warmup_min (from challenge def, e.g. 10); budget_min.
OUT: trimmed exercise list with sets; est_total_min; trimmed[] with reason 'time'; flag core_does_not_fit.
PSEUDO:
set_s(e) = (e.timed ? e.hold_s + HOLD_OVERHEAD_S : SETUP_S + e.reps_target * REP_S) * (e.unilateral ? 2 : 1) + (e.unilateral ? SIDE_SWITCH_S : 0)
ex_s(e) = e.sets*set_s(e) + (e.sets-1)*e.rest_s + TRANSITION_S
total = warmup_min*60 + sum(ex_s)
while total > budget_min*60:
  cands = [e for e if e.tier >= 1], ordered by (-tier, preference asc, engine_priority asc, id)
  if none: core_does_not_fit = true; break
  e = cands[0]
  if e.sets > e.min_sets: e.sets -= 1 else: remove e (log trimmed)
  recompute total
if core_does_not_fit: offer 'Short version': core sets = ceil(sets/2), loads unchanged (mirrors the challenge deload rule), else suggest another day
min_sets: improve = 2, maintain = 1, accessory = 0 (removable)
CONST: REP_S = 4 s (about 3-4 s per rep tempo). SETUP_S = 20 s (grip, belt, pin). HOLD_OVERHEAD_S = 15 s. SIDE_SWITCH_S = 15 s. TRANSITION_S = 90 s (walk, set up machine). Budget chips 45/60/75/90+ min (90+ = no trim). All are starting guesses, to be fitted from logs.
LOG: budget chosen; est_total vs actual session duration; per-exercise est vs actual (first set start to last set log); trimmed items. Fit REP_S, SETUP_S and TRANSITION_S by least squares on (actual exercise duration - sets*rest - planned rest overrides). Flag sessions with actual > budget + 10 min.

### UI-03 Rest timer state machine + background alerting
PURPOSE: Reliable, overridable rest countdown that survives screen-off, app kill and music playback, and logs planned vs actual rest.
IN: rest_s from engine for (exercise, set type); fallback table; between_sides_s (exercise/challenge def); user adjustments; set log timestamps; device elapsedRealtime + wall clock.
OUT: timer state {IDLE, RUNNING(endAt), FINISHED, OVERTIME}; notification (chronometer countdown, actions -15/+15/Skip/Log as planned); exact alarm at endAt; haptic/audio cues.
PSEUDO:
on set_logged(s):
  dur = s.is_side_A_of_unilateral ? between_sides_s : (engine.rest_s ?? FALLBACK[s.category])
  if dur == 0: state = IDLE; return
  endAt = elapsedRealtime() + dur*1000; persist {endAt, planned=dur, set_id}
  post ongoing notification: setUsesChronometer(true), setChronometerCountDown(true), setWhen(wallNow + dur*1000), setRequestPromotedOngoing(true) [API36], actions [-15][+15][Skip]
  schedule exact alarm at endAt (setExactAndAllowWhileIdle; USE_EXACT_ALARM since the APK is sideloaded) and pre-alarm at endAt - PREWARN_S
  state = RUNNING
on adjust(d in {-15,+15}): endAt = max(now+1s, endAt + d*1000); reschedule; log
on skip: state = IDLE; log skipped
on prewarn: single short haptic tick (if enabled)
on endAt: state = FINISHED -> vibrate WAVEFORM; optional tone with AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK; heads-up notification 'Set 3: +20 kg x 5'
after FINISHED: state = OVERTIME (count up, shown +m:ss in signal colour); at endAt + REMIND_S one short reminder buzz (once)
on next set logged or hold started: actual_rest_s = (t_log_next - est_set_duration(next)) - t_log_prev; state = IDLE
process restart: restore from persisted endAt; if now > endAt -> OVERTIME
CONST: FALLBACK (only if engine silent): heavy compound or 5 or fewer reps or weighted BW = 180 s; compound 6-12 reps = 120 s; isolation or more than 12 reps = 90 s; skill/hold = 90 s; core = 60 s (leans long per Schoenfeld 2016, Singer 2024). Adjust step 15 s (Hevy convention). PREWARN_S = 10. REMIND_S = 60. WAVEFORM = [0,300,150,300,150,600] ms (3 pulses, felt in a pocket). Minimum remaining after adjust = 1 s.
LOG: rest_planned_s, adjustments[] (time, delta), skipped, rest_actual_s (estimated), overtime_s, screen_state at alarm (on/off), whether a notification action was used. Engine uses actual-vs-planned rest together with next-set RIR to tune per-exercise rest; frequent +15s on an exercise suggests rest_s is too short.

### UI-04 Set input prefill, loadable snapping, stepper behaviour
PURPOSE: Show the most likely correct values so a typical set needs 0 edits, and keep every weight loadable on the user's real equipment.
IN: targets for set k (engine), accepted intra-session adjustment (UI-06), last actual for same exercise + set index, equipment profile {barbell plates, dumbbell list, machine stack steps, dip-belt plates, assisted pin step 5 lb + range, finger levels 4..0}, exercise load_type in {external, bodyweight, weighted_bw, assisted_pin, assisted_fingers, timed}.
OUT: displayed values; step sizes; snapped value; deviation flag.
PSEUDO:
prefill = first_non_null(accepted_adjustment[k], engine_target[k], last_actual[k], last_actual[k-1], defaults)
step_weight = by load_type: external barbell -> 2 * smallest_plate_pair (e.g. 2.5 kg); dumbbell -> next value in dumbbell list; machine -> stack_step; weighted_bw -> smallest dip-belt plate (e.g. 1.25 kg); assisted_pin -> 5 lb (direction label: lower = harder); fingers -> 1 finger; bodyweight -> none (show 'BW', optional '+ add weight')
step_reps = 1; step_hold = 1 s
long-press: after HOLD_DELAY_MS repeat every REPEAT_MS; after ACCEL_AFTER repeats multiply step by ACCEL_FACTOR
tap value -> keypad sheet with chips [nearest lower loadable, nearest higher loadable]
snap(v) = nearest value in Loadable set L (weighted_bw: all subset-sums of owned plates; assisted: multiples of 5 lb within [0, max]); ties -> the easier load (lower external or higher assistance)
deviation = |w - w_t| >= 0.5*step or reps outside [rep_low, rep_high] or hold_s < 0.8*target or skip or sets changed
CONST: HOLD_DELAY_MS = 400, REPEAT_MS = 120, ACCEL_AFTER = 8, ACCEL_FACTOR = 5 (Android auto-repeat conventions). Tie-break toward the easier load (safety). Assisted effective assist = pin_lb * 0.4536 + MACHINE_OFFSET_KG, MACHINE_OFFSET_KG = 6.5 (artifact: 'the machine itself weighs 6-7 kg against you'; 40 lb gives about 24.6 kg), user-calibratable per machine.
LOG: prefill_source, target vs final values, deviation flag, input method (stepper/keypad/none), taps per set, ms from rest end to log (logging friction), snap corrections. A high deviation rate on a given exercise or prefill_source means the engine targets are off. Keypad use on steppers means the step is too small.

### UI-05 Contextual override-reason chips and the skip/defer flow
PURPOSE: Capture why the user deviated with 0-1 taps, never blocking logging; turn 'occupied' into 'do later' instead of a lost exercise.
IN: deviation direction (harder/easier than target, skip), actual RIR vs target RIR, pain flags, defer count of the exercise this session.
OUT: ordered chip list (6 visible max + More); reason codes saved on the set or exercise; defer/swap actions.
PSEUDO:
if direction == easier_than_target (weight up, reps up, or RIR >= target+2): order = [TOO_EASY, FEELING_GOOD, OTHER]
elif direction == harder (weight/reps down, RIR <= target-2, hold short): order = [TOO_HARD, FEELING_BAD, PAIN, GRIP_FAILED, TORSO_ROTATION(if unilateral hang/pull), NO_TIME, OCCUPIED, OTHER]
elif skip: order = [NO_TIME, OCCUPIED, PAIN, FEELING_BAD, TOO_TIRED, EQUIPMENT_MISSING, OTHER]; skip requires exactly one tap
chips are optional for non-skip deviations (logged as NONE if untouched)
if reason == OCCUPIED on skip: offer DO_LATER -> move exercise to after the next not-done exercise; defer_count += 1; if defer_count >= MAX_DEFERS: offer Swap sheet (engine alternatives ranked; tags 'same muscles', 'liked', 'free equipment')
if reason == PAIN: open pain sheet (location, severity mild/moderate/sharp); if an active challenge has a pain rule for that location -> prompt 'Start <challenge> pain protocol?'
OTHER -> optional free text (never required)
CONST: MAX_VISIBLE = 6 (fits 2 rows of 56dp chips). MAX_DEFERS = 2. Reason vocabulary (versioned enum): TOO_EASY, TOO_HARD, FEELING_GOOD, FEELING_BAD, TOO_TIRED, NO_TIME, OCCUPIED, EQUIPMENT_MISSING, PAIN, GRIP_FAILED, TORSO_ROTATION, TECHNIQUE_BREAKDOWN, OTHER.
LOG: reason code, chip position chosen (to check ordering), untouched-chip rate, defer count, defer-to-done vs defer-to-skip, swap chosen rank, pain location/severity. Engine consumes reasons: TOO_EASY/TOO_HARD feed load models; NO_TIME and OCCUPIED are excluded from performance models; FEELING_* feed readiness; PAIN feeds the safety and challenge rules.

### UI-06 Intra-session adjustment suggestion trigger
PURPOSE: Decide when to surface an engine-computed load change for the remaining sets of an exercise, presented as one-tap Apply/Keep. Never auto-applied.
IN: per logged set k: RIR_target, RIR_actual, reps_target range, reps_actual, hold target/actual, reason code; engine.adjust(exercise, remaining_sets) -> new load/reps.
OUT: suggestion chip shown in the rest card {new_load, new_reps, explanation} or none.
PSEUDO:
delta_k = RIR_actual - RIR_target   (+ = easier)
short_k = max(0, rep_low - reps_actual)
trigger = (k == 1 and abs(delta_k) >= FIRST_SET_DELTA)
       or (k >= 2 and sign(delta_k) == sign(delta_{k-1}) != 0 and abs(delta_k) >= CONSEC_DELTA and abs(delta_{k-1}) >= CONSEC_DELTA)
       or short_k >= SHORT_REPS
       or reason in {TOO_EASY, TOO_HARD}
if RIR_actual is 5+ or reps_actual > 12: treat delta as low-confidence -> require the consecutive rule (Halperin 2022 accuracy)
if trigger and suggestions_shown[exercise] < MAX_PER_EX and remaining_sets > 0:
  adj = engine.adjust(...); if |adj.load - current| >= 1 loadable step or adj.reps != current: show chip 'Next sets: {adj} ({RIR_actual} vs {RIR_target})' [Apply][Keep]
PAIN reason bypasses: show 'Stop this exercise?' immediately
CONST: FIRST_SET_DELTA = 2 (more than double the ~1-rep RIR error from Halperin 2022). CONSEC_DELTA = 1 (two sets in agreement). SHORT_REPS = 2. MAX_PER_EX = 1 per session (avoid nagging). Default action = Keep.
LOG: suggestion shown {trigger rule, deltas, proposed change}, accepted/kept, next-set delta after accept vs keep (does it bring RIR to target?), pain stops. Used to tune thresholds and check that suggestions improve how often targets are hit.

### UI-07 Feedback prompt budget (readiness, soreness, sRPE, joint pain)
PURPOSE: Collect the subjective signals the engines need (Saw 2016: subjective measures are sensitive) at minimum tap cost, and adapt down when the user ignores them.
IN: session plan (muscles to train), last sessions per muscle (timestamps), prompt history (answered/skipped), pain history, active challenge pain rules, user settings.
OUT: which prompts to show at session start/end; stored answers.
PSEUDO:
PRE (on Start, one sheet, skippable):
  readiness: [Worse][Normal][Better] if enabled(readiness)
  soreness: for muscles m in plan trained within SORE_WINDOW_H, top SORE_MAX by planned sets: [Never sore][Healed][Still sore]
POST (summary screen):
  sRPE 0-10 chips (Foster 2001)
  joint pain: [None] or location chips -> severity [mild][moderate][sharp]
  optional note
budget: total_taps(PRE+POST) <= TAP_BUDGET; drop lowest-priority prompts first (priority: pain > sRPE > readiness > soreness)
adaptive: if prompt p skipped SKIP_STREAK sessions in a row -> show p every REDUCED_EVERY sessions; restore after 3 answers
pain prompt never reduced if pain reported in last PAIN_GUARD_D days or a challenge pain rule is active
CONST: SORE_WINDOW_H = 96 (soreness relevance window). SORE_MAX = 3 muscles. TAP_BUDGET = 6 taps outside sets. SKIP_STREAK = 3. REDUCED_EVERY = 3. PAIN_GUARD_D = 14 (matches the challenge's 2-week elbow protocol).
LOG: prompt shown/answered/skipped, answer, latency_ms, answers vs next-session performance (readiness vs e1RM delta; soreness vs volume). The engine uses sRPE x minutes as session load and pain as a safety trigger. Review checks which prompts predict anything; drop prompts with no predictive value.

### UI-08 Calendar gym-event classifier + recommended-vs-planned matcher
PURPOSE: Read Google Calendar events on the device, decide which are gym sessions (and which challenge session), ignore stale Todoist all-day tasks, and pair them with recommended sessions for the Week/Today screens.
IN: CalendarContract instances for [week_start, week_end] from allowlisted calendars; gym_locations[] (e.g. 'Basic-Fit'); challenge definition session patterns (e.g. regex 'sessie\s*([ABC])' -> OAP-A/B/C, 'testdag' -> OAP-TEST); keyword map push/pull/legs (EN+NL: borst, rug, benen); recommended sessions[] {id, day, type}; user corrections.
OUT: events[] {is_gym, session_code|type|null, score}; matches[] (rec_id, event_id); unmatched planned; unmatched recommended ('suggested slot').
PSEUDO:
for e in events:
  if e.calendar not in allowlist: skip
  s = 0
  if regex(GYM_WORDS) in title: s += 3        # gym|sessie|session|training|workout|testdag
  if any(loc in e.location for loc in gym_locations): s += 3
  if 45 <= duration_min <= 150: s += 2
  if challenge_pattern matches title: s += 2; code = mapped
  if e.all_day: s -= 5
  if user_correction(e.id or title_fingerprint) exists: use it
  e.is_gym = s >= GYM_THRESHOLD
  e.type = code or keyword_type(title, description) or 'unspecified'
matching (greedy, deterministic): pairs ordered by cost asc, then event start asc, then rec id
  cost(r,e) = DAY_W*|day(r)-day(e)| + (type_mismatch ? TYPE_W : 0)
  accept if cost <= MAX_COST and neither already matched
UI: matched -> same row; planned-only -> 'Planned (not recommended)' with Why; rec-only -> dashed card + '[+ Plan]' (optional calendar insert)
CONST: GYM_THRESHOLD = 4 (title word + location, or title + plausible duration). DAY_W = 2, TYPE_W = 3, MAX_COST = 4 (allows +/-1 day shift with matching type, or same day with a type mismatch; the user's sessions move between Tue/Wed and Thu/Fri). Allowlist default: primary calendar only (excludes the stale Todoist 'Gym' tasks).
LOG: per event: score components, classification, user corrections ('not a gym session', 'this is session A'), matching costs, unmatched counts. Corrections retune keyword weights and thresholds.

### UI-09 Weekly muscle-volume bars
PURPOSE: Show per-muscle hard sets done (plus planned remaining) against the engine's target band, coloured by goal.
IN: logged sets this week {exercise muscles primary/secondary, RIR, set_type}; planned remaining sessions' sets; engine targets per muscle {floor, target, ceiling}; goals improve/maintain/off.
OUT: rows sorted by goal then deficit: done, planned, band, state colour, warning flag.
PSEUDO:
hard(set) = set_type == working and (RIR is null or RIR <= HARD_RIR_MAX)
done[m] = sum over hard sets: (m in primary ? 1.0 : m in secondary ? 0.5 : 0)
planned[m] = same over planned remaining (assume hard)
proj = done + planned
fill = min(done/target, 1.25); planned segment = min(proj/target,1.25) - fill (dotted)
state = proj < floor ? DEFICIT (advisory colour + '!') : proj > ceiling ? OVER (neutral + marker) : OK (goal colour)
sort: improve first, then maintain; within a group by (proj/target asc); 'off' muscles collapsed into one line 'Not trained: quads, ...'
CONST: Fractional counting primary 1.0 / secondary 0.5 (Pelland et al. 2026). HARD_RIR_MAX = 4 (sets at 5+ RIR don't count as hard). Bar cap 125%. Week = Mon-Sun (Dutch locale).
LOG: weekly done/planned/target per muscle, DEFICIT weeks, user taps on rows (interest). Engine uses the same numbers; the review checks whether deficits are caused by skips (reasons) or by planning.

### UI-10 Evidence-override warning surfacing policy
PURPOSE: When the engine overrides user preferences (sets/reps, goals) because research shows a much better program, inform without nagging: non-blocking, dismissable, with a Why sheet.
IN: engine.violations[] {rule_id, severity in {info, advisory, safety}, entity (muscle/exercise/preference), magnitude (0..1, relative distance from the evidence bound), evidence_grade A-D, citations[], applied_override bool}; dismissal store {rule_id, entity, dismissed_at, magnitude_at_dismiss}; 'use mine anyway' flags.
OUT: banners (max 1 per screen) + collapsed counter; inline amber dots on affected targets; Why sheet content.
PSEUDO:
visible = []
for v in violations:
  if v.severity == safety: visible.add(v); continue   # persistent until acknowledged, never auto-hidden
  if v.magnitude < MIN_MAGNITUDE: continue (inline dot only if applied_override)
  d = dismissal(v.rule_id, v.entity)
  if d and now - d.at < SNOOZE_D and v.magnitude < d.magnitude * (1 + WORSEN_FACTOR): continue
  if use_mine_anyway(v): show only an inline 'your choice' marker; continue
  visible.add(v)
sort visible by (severity desc, magnitude desc, rule_id)
banner = visible[0]; rest -> 'N more notes' row
Why sheet = {plain summary, your preference vs app's choice, numbers, evidence grade, citations (links), toggle 'Use mine anyway', rule_id, algo_version}
CONST: MIN_MAGNITUDE = 0.2 (deviation of 20% or more from the evidence bound before a banner). SNOOZE_D = 28 days. WORSEN_FACTOR = 0.25 (resurface if 25% worse). Max banners per screen = 1. Safety examples: pain protocol active, unilateral heavy sessions over 3/week (challenge rule).
LOG: warning shown, why_opened, dismissed, use_mine_anyway toggled, override accepted, plus outcomes in following weeks (progress, pain) for users who kept their preference vs accepted. Tunes MIN_MAGNITUDE and which rules deserve a banner.

### UI-11 Progress chart smoothing + PR detection (display)
PURPOSE: Clean, honest e1RM and hold/assistance progress charts that handle weighted, assisted and bodyweight lifts consistently.
IN: per session best set per exercise; engine e1RM function (user-calibrated, flatter rep curve); bodyweight at session (last known within 7 days); assisted pin + MACHINE_OFFSET_KG; load_type.
OUT: points, trend line, PR markers, y-axis in the chosen representation.
PSEUDO:
system_load = external: w; weighted_bw: BW_s + added; assisted: BW_s - (pin_lb*0.4536 + MACHINE_OFFSET_KG); bodyweight: BW_s
e1RM_sys = engine.e1rm(system_load, reps, RIR)
display (toggle): 'added kg' = e1RM_sys - BW_current (weighted/assisted shown on one continuous axis: assisted becomes negative 'added') or 'system kg'
trend: EWMA over session-ordered points, a = ALPHA; draw raw points (small, 60% opacity) + trend (2dp primary)
PR if e1RM_sys > max(previous) * (1 + PR_MIN_GAIN); timed holds: PR on seconds (per side); assistance: PR on lowest effective assist for 1 clean rep
default range 12 weeks; test-day points drawn as filled diamonds
CONST: ALPHA = 0.3 (smooth but responsive at 2-3 sessions/week). PR_MIN_GAIN = 0.005 (0.5%, ignores loadable-rounding noise). Default window 12 weeks. MACHINE_OFFSET_KG = 6.5 (see UI-04).
LOG: chart views, toggles, PR events (to check PR rate isn't noise), BW staleness at computation time.

### UI-12 UI telemetry logger (feeds every tuning loop)
PURPOSE: One append-only, versioned event stream that captures what the UI showed and what the user did, so Claude Code reviews can replay decisions.
IN: events from UI-01..UI-11 plus session lifecycle (start, finish, abandon, minimise), set logs, sheet opens.
OUT: rows in fitness schema table ui_event; local-first in SQLite/Room, synced to Supabase when online.
PSEUDO:
ui_event(id uuid, user_id, session_id null, ts_utc, ts_local_offset, screen, event_type, entity_type, entity_id, payload jsonb, algo_version, ui_version, app_build)
write locally inside the same transaction as the domain write (e.g. set log + 'set_logged' event)
sync: batch upload every SYNC_S while online + on session finish; idempotent on id
privacy: no free-text notes in payload (reference the note id instead)
export: 'Export review bundle' in You > Transparency -> JSON zip (events + domain data + algo versions) for Claude Code
CONST: SYNC_S = 60. Retention: unlimited (personal app; few MB per year). Event-type enum versioned.
LOG: itself; plus sync lag and failure counts.

## DESIGN OPTIONS
- Information architecture / bottom navigation: [A: 5 tabs Today / Week / Exercises / Challenges / You (Progress + History + Preferences inside You) / B: 4 tabs Today / Plan / Library / Progress, with Challenges nested in Plan / C: single home feed + navigation drawer] -> A, with Live Session as a full-screen flow and a mini-bar above the nav while minimised.. WHY: Matches the user's own mental model (they asked for a 'challenges tab'). M3 nav bar supports 3-5 destinations. Today = decide, Week = plan, You = look back + configure. Drawers hide destinations and are hard to reach with the thumb.
- Set logging interaction: [A: Hevy/Strong table rows with a checkmark per set / B: current-set card with a big Log button + separate RIR chips (2 taps) / C: current-set card where the RIR chips ARE the log action (1 tap, RIR always captured) / D: swipe-to-complete] -> C, plus a compact exercise list above it for jumping to and editing past sets. Holds use Max/Hard/OK/Easy chips as the log action.. WHY: One tap per set and guaranteed RIR (the engines need it). Large bottom-zone targets match the thumb zone (Hoober). Swipes fail with sweaty fingers and cause accidental logs; undo snackbar covers mistakes.
- RIR / effort scale: [A: integer chips 0,1,2,3,4,5+ / B: RPE 6-10 with half steps / C: 3-level easy/ideal/hard (Gravl)] -> A; no prefill (avoid anchoring); flag 5+ and sets over 12 reps as low confidence.. WHY: Self-report error is about 1 rep (Halperin 2022), so halves are false precision. RIR matches the user's challenge language ('2 reps in reserve'). Three levels are too coarse for load progression.
- Override-reason capture: [A: mandatory dialog on any deviation / B: optional inline context-ordered chips on deviation; one mandatory tap only for skip / C: only at session end] -> B (UI-05), with 'Occupied' turning a skip into 'Do later'.. WHY: Mandatory dialogs add friction mid-set; session-end recall is poor. Inline chips get most reasons in 0-1 taps, and the untouched rate is logged.
- Exercise preference scale: [A: 1-5 stars / B: like / neutral / dislike / C: 5-level semantic: Favorite / Like / Neutral (default) / Dislike / Never] -> C. Dislike = soft penalty, still used when it is the only or best coverage of a goal muscle (explained in UI). Never = hard exclusion (e.g. it hurts); the coverage engine warns if a muscle loses all options.. WHY: Labels say what the algorithm does, unlike stars. It meets the user's 'disliked but needed' rule while giving an escape hatch for injury-driven exclusions.
- Rest timer presentation: [A: full-screen overlay / B: inline card replacing the action area + collapsible top pill + ongoing/Live Update notification / C: bottom sheet] -> B, plus an optional full-screen 'focus' mode toggle.. WHY: Keeps exercise context, the next-set preview and the intra-session suggestion visible. The notification and lock-screen actions handle the phone-in-pocket case.
- Background timer mechanism: [A: foreground service for the whole active session (type specialUse, or health + ACTIVITY_RECOGNITION) + chronometer notification + exact alarm / B: chronometer notification + exact alarm only (no FGS) / C: PWA / web timers] -> A, using USE_EXACT_ALARM (sideloaded APK). Request promotion as a Live Update on Android 16+, fall back to a standard ongoing notification, and prompt for a battery-optimisation exemption on aggressive OEMs.. WHY: Survives screen-off and process death, keeps the session notification and lock-screen actions alive. A PWA cannot schedule alarms reliably, and shortService's ~3 min cap is shorter than OAP rests.
- Client stack, from the UX angle: [A: Kotlin + Jetpack Compose (Material 3, Expressive APIs where stable) / B: React Native / Expo / C: Flutter (not installed) / D: PWA] -> A. Keep the algorithm engine a pure Kotlin/JVM module, unit-testable on desktop with the installed Java, and use Android SDK command-line tools without Android Studio. Weigh against other lenses (e.g. sharing TypeScript with Supabase).. WHY: First-class M3/Expressive components, notifications/alarms/haptics/CalendarContract/Health Connect APIs used directly. D fails the screen-off timer requirement.
- Colour theming: [A: Material You dynamic colour from wallpaper / B: fixed seed teal #0B6A60 (continuity with the OAP artifact) into an M3 tonal scheme / C: monochrome + one accent] -> B, following system light/dark (dark-first QA), with dynamic colour as an optional toggle and a high-contrast option.. WHY: Stable semantics for advisory (amber) and safety (red) colours that dynamic palettes can wash out; visual continuity with the program the user already reads.
- Motion scheme: [A: M3 standard springs (calm) / B: M3 Expressive bouncy springs everywhere] -> A; expressive motion only on set-logged confirm and gate-achieved moments; respect 'remove animations'.. WHY: The user wants 'very clean'. Google's own research says to use expressive styles with restraint in utility contexts.
- Transparency of algorithm decisions: [A: none (black box) / B: full explanation always visible / C: layered: up to 3 reason chips, then Why sheet (numbers, rule id, evidence grade, citations, 'use mine anyway'), then trace export for Claude Code] -> C. WHY: Supports transparency and scrutability (Tintarev & Masthoff) and on-demand explanation (Laato 2022) without clutter. The trace gives the periodic algorithm reviews reproducible inputs.
- Feedback capture: [A: RP-style per-muscle pump/soreness/workload every session / B: minimal: 1-tap readiness pre, sRPE + joint pain post, soreness at the next session for planned muscles, with a 6-tap budget and adaptive reduction / C: none] -> B (UI-07); per-muscle pump only as an opt-in for 'improve' hypertrophy muscles.. WHY: Subjective measures are sensitive (Saw 2016) and sRPE is valid (Foster 2001), but prompt fatigue kills compliance. The OAP work is strength/skill, where pump is not meaningful.
- Calendar access: [A: on-device CalendarContract (READ_CALENDAR) + calendar allowlist; optional write via CalendarContract for 'Plan it' / B: Google Calendar API OAuth (calendar.events.readonly) via backend] -> A. WHY: No OAuth or consent-screen work, works offline in the gym, sees exactly what the phone sees. The allowlist removes the stale Todoist 'Gym' tasks.
- Unilateral set representation: [A: separate sets per side / B: one set = L/R pair with independent values; side order from definition (left first); side-cap hint (right no more than left)] -> B. WHY: Matches how the user trains and the challenge rules. Asymmetry metrics come directly from paired data. Rest applies after both sides, between-sides rest is configurable.
- Assisted / bodyweight load display: [A: machine-native value primary (Pin 40 lb), effective kg secondary, 'lower = harder' cue / B: effective kg primary / C: negative weight in the weight field] -> A; store raw pin value + unit + computed assistance; fingers as an alternative assistance unit; weighted shown as 'BW + x kg'.. WHY: The user dials the pin, not the kg. Negative weights break graphs and volume (Hevy's own reasoning). Charts use system load (UI-11).
- Timed holds (hangs, top holds): [A: manual start/stop taps / B: lead-in countdown (3-2-1) + audio beeps every 5 s and at target + correction stepper afterwards + felt chips / C: sensor auto-detection] -> B. WHY: During one-arm hangs the hand is on the bar and the phone is on the floor. Stop condition is torso rotation, which only the user can judge. Sensors are unreliable and not deterministic enough.
- Muscle map asset: [A: reuse react-body-highlighter / flutter_body_heatmap polygons (MIT) / B: custom simplified SVG of about 22 regions incl. lats, front/side/rear delts, forearm flexors/extensors, adductors, as a Compose ImageVector] -> B (may start from MIT polygons, then add the missing regions). WHY: The existing libraries lack lats and side delts, which are central for a pull/OAP-focused user and for goal settings.

## RISKS
- Prompt fatigue: too many chips, questions or warnings make the user stop answering, which degrades the data the algorithms depend on. Mitigate with the tap budget (UI-07), 1 banner per screen (UI-10), and logging of untouched/skip rates.
- RIR anchoring and bias: prefilled or target-highlighted RIR chips invite lazy confirmation. Self-report error is about 1 rep and worse above 12 reps (Halperin 2022). Don't prefill, flag low-confidence sets, let the engine calibrate per user.
- OEM battery killers (Samsung/Xiaomi/OnePlus) can delay alarms or kill the session service. Needs a battery-optimisation exemption prompt, exact alarms, persisted endAt and a recovery-on-restart path. Test on the user's actual phone.
- Android 16 Live Update promotion depends on a user setting (canPostPromotedNotifications). Always ship a standard ongoing-notification fallback.
- POST_NOTIFICATIONS (Android 13+) and exact-alarm permissions may be denied, which silently breaks the rest alarm. Show an onboarding check and a persistent warning in You > Settings.
- Compose Expressive APIs are experimental in material3 1.4.0 stable (1.5 still alpha). Pinning alpha risks API churn; prefer baseline M3 plus selectively opted-in Expressive components behind a thin wrapper.
- Gym connectivity at Basic-Fit may be poor. Any network-blocking write loses sets; must be offline-first (local DB transaction per set, background sync, idempotent ids).
- Calendar parsing is brittle (Dutch titles, em-dash, sessions moving days, Todoist duplicates). Needs a one-tap correction UI and an allowlist; log corrections.
- Wet or sweaty touch errors cause wrong logs. Large targets, 300 ms double-tap debounce, undo snackbar, past sets easy to edit.
- Holding the phone during one-arm work is impossible. Timing UX must be hands-free (lead-in + audio) with correction afterwards, or the data will be noisy.
- Explanations can become clutter or overclaim certainty. Keep L1 to 3 chips, show evidence grade, never present heuristics (e.g. coach rule-of-thumb thresholds) as research.
- Large font scale (up to 200%) can break the 45/96sp numeric layouts and 6-chip rows. Need responsive fallbacks (2x3 chip grid) and tests at 1.3x and 2.0x.
- Language mismatch: program/calendar content is Dutch, the user writes English. Mixed-language UI looks unclean; decide once and support NL keywords in the parsers regardless.
- Mixed units (machine pins in lb, plates in kg) can confuse. Always label units and never auto-convert the pin value shown to the user.
- Muscle-map and catalog data quality (free-exercise-db vocabulary differs from the app's muscle list) gives wrong coverage and volume bars. Needs a curated mapping table, reviewed once.

## QUESTIONS
- Which phone model and Android version do you use? This affects Live Update notifications (Android 16+) and battery-killer workarounds.
- Should the app UI be in English or Dutch? Number format: decimal comma (42,8 kg) or point?
- Visual style: reuse the teal/amber palette of your one-arm pull-up program page for continuity, or use Material You colours from your wallpaper? Dark mode by default?
- Exercise preference scale: is Favorite / Like / Neutral / Dislike / Never OK, where Dislike is still used when it is the only good option and Never is a hard exclusion (e.g. it hurts)?
- Logging: OK if tapping the 'reps left in the tank' chip (0-5+) logs the set in one tap, so RIR is always recorded? Or do you want RIR optional?
- Rest-timer alert: vibration only, or also a sound (ducking your music if you wear earbuds)? Is the phone usually in your pocket or on the bench/floor?
- After a session, are you OK with a quick optional check (session effort 0-10 + joint pain + soreness asked next time), at most about 6 taps per session?
- Google Calendar: should the app only read gym events, or also create events when you tap 'Plan it' on a recommended session? Which calendars should it read (primary only, so the old Todoist 'Gym' tasks are ignored)?
- Does your running app (or a smart scale) sync to Health Connect? Runs could inform leg/recovery advice and bodyweight could update the weighted/assisted load maths automatically.
- Do you wear a smartwatch (Wear OS / Garmin / other) that you'd want the rest timer or set logging on?
- How do you time one-arm hangs and top holds now? Would a 3-2-1 lead-in with beeps every 5 s and a quick 'I let go at X s' correction work for you?
- Should daily home tasks from the challenge (2-arm hang, one-arm plank) appear on Today as a checklist and be logged?
- Will your friend (or other users of the Supabase project) use this app too? That decides whether onboarding, multi-user preferences and sharing need UI now.
- Are you willing to enter your gym's equipment once (dumbbell steps, plates you own for the dip belt, machine stack steps), so all suggested weights are actually loadable?
