# FitnessApp

Personal Android gym app (Kotlin + Jetpack Compose) with a deterministic recommendation engine, Supabase
sync and data-driven challenges. Start with [PLAN.md](PLAN.md); [docs/DECISIONS.md](docs/DECISIONS.md) is
the single source of truth for every decision. Rules for Claude Code sessions are in [CLAUDE.md](CLAUDE.md).

## Modules

| Module | What it is |
|--------|------------|
| `:algo` | Pure Kotlin JVM engine: no Android, no clock, no randomness, no IO (enforced by `AlgoPurityTest` and `verifyAlgoDependencies`). Bundled data under `algo/src/main/resources/fitness/` (params, reference data). |
| `:data` | Android library: Supabase client (publishable key, schema `fitness`), Keystore-encrypted session, sync (from M1). |
| `:app` | Android application (Compose). The debug menu runs the on-device determinism self-test. |
| `:sim` | JVM CLI: `golden` (run golden fixtures), `seedgen` (generate the reference/params seed SQL). |

## Build and install (Windows, PowerShell)

```powershell
.\gradlew.bat check                      # all tests, purity checks, lint
.\gradlew.bat :app:recordRoborazziDebug  # screenshot baselines in app/src/test/screenshots
.\gradlew.bat :app:installDebug          # install over Wi-Fi adb (phone must be connected)
.\gradlew.bat :sim:run --args="golden"   # golden fixtures through :algo on the JVM
```

`local.properties` (git-ignored) holds `sdk.dir`, `supabase.url` and `supabase.publishableKey`. The
release signing properties live in `%USERPROFILE%\.gradle\gradle.properties` (`FITNESS_RELEASE_*`, ANDROID.md
5.4); `debug` and `release` are both signed with that key, and packaging fails clearly when it is missing.

Database migrations live in `supabase/migrations/` and are applied only through the Supabase MCP after
review (CLAUDE.md, DATA_MODEL.md 13). Never run `supabase db push` against the shared project.

## M0 log (2026-10-08)

**Toolchain**

| Item | Value |
|------|-------|
| JDK for Gradle | Temurin 21.0.12.1+1, portable zip in `%LOCALAPPDATA%\Programs\Temurin-21` (the quiet MSI needs admin rights); registered via `org.gradle.java.installations.paths` in the user `gradle.properties`. System Java 25 untouched. |
| Gradle | 9.8.1 wrapper, pinned SHA-256; daemon pinned to Java 21 (`gradle/gradle-daemon-jvm.properties`). |
| Android SDK | `C:\Users\sivuc\Android\Sdk` (not under AppData: the Claude desktop app is MSIX-packaged, and folders its tools create under `AppData\Local` are redirected into its private package cache). cmdline-tools 15859902 (sdkmanager 22.0), platform-tools 37.0.1, platforms android-36 and android-37, build-tools 36.0.0. |
| Library versions | AGP 9.4.1, Kotlin 2.4.21, KSP 2.3.12, Compose BOM 2026.09.00, supabase-kt 3.8.0 (Ktor 3.5.1), Room 2.8.5, Robolectric 4.17, Roborazzi 1.76.0 (full list in `gradle/libs.versions.toml`). |
| SDK levels | minSdk 26, compileSdk 37, targetSdk 36 (F23 as amended 2026-10-08). |

**Phone**: OnePlus CPH2747, Android 16 (API 36), build `CPH2747_16.0.10.500(EX01)`; paired over wireless
debugging. Application id `dev.fitnessapp` (confirmed before the first install).

**Release signing certificate** (not secret; check any APK with `apksigner verify --print-certs`):
`CN=FitnessApp, O=Personal, C=NL`, SHA-256
`2b:96:32:ee:71:09:09:aa:47:ee:c4:1f:bb:3b:c1:41:bc:37:72:32:81:22:f3:ca:40:4b:c9:ce:4c:74:6c:9f`.
Keystore: `%USERPROFILE%\.android-keystores\fitnessapp-release.jks`, backed up by the user.

**Supabase** (project `nuvjdvpqibqeyafzgaye`, shared with the chess app): migrations 001-007 applied via MCP
(005 in seven parts), exposed schemas `public, graphql_public, fitness`, advisors without new fitness
findings, RLS/LWW test in `supabase/tests/fitness_m0_rls_test.sql`, params 1.0.0 (621 keys, `params_hash`
`5ab49a05…77fd`) active, 51 module versions registered.

**Done-when checks**: `gradlew check` green; the app installs on the phone, signs in with the existing
account, passes the allowlist probe and round-trips the profile row; determinism self-test on ART passes
7/7 golden fixtures with the same hashes as the JVM.

## M1a log (2026-10-11)

**Builds**: debug builds signed with the release keystore (certificate above), installed over wireless or USB adb.
Unit tests: `:algo`, `:data`, `:app` green; lint clean.

**Supabase**: migrations 008-015 (M1a batch, checklist notes in `supabase/migrations/notes/`), 019 params 1.0.1 and
020 catalog 1.0.1 (data only). Live RLS suite after 020: `FITNESS_DB_TESTS_PASSED 56 (live)`, residue 0; probes
unchanged.

**Done-when checks**: a full session logged in airplane mode (2026-10-09) arrived complete after reconnecting
(session, exercise, three sets, events); the rest alarm fires on time with the screen off (below).

**On-device rest-timer checklist** (ANDROID 15.6; OnePlus CPH2747, Android 16, battery optimisation exempt, alarm
mode `alarm_clock`; T1-T8 run with the 20 s self-test, which uses the production timer path):

| # | Result | Evidence |
|---|--------|----------|
| T1 screen off | pass | fired 7 ms late, screen off (phone dozing), vibration felt |
| T2 forced Doze | pass | `deviceidle force-idle` (deep IDLE, battery unplugged over USB), fired 4 ms late, felt; state reverted |
| T3 Battery Saver | pass (user report) | not measured |
| T4 `am crash` | pass | fired 8 ms late in a fresh process, screen off, felt (the crash shows up as an `app_crash` event: test-induced) |
| T5 swipe from Recents | pass (user report) | not measured |
| T6 reboot | open | optional, not a done-when item |
| T7 `am force-stop` | pass (expected failure mode) | no alarm while stopped; on relaunch the timer was recovered as 9.1 s late with "Rest over", no stale countdown, no crash |
| T8 lock-screen actions | pass | +15/-15 change the end, Skip ends the rest; `notification_action` events logged (after the self-test cap fix) |
| T9, T10 denied permissions | open | |
| T11 Live Update | pass | countdown in the status-bar chip via `shortCriticalText` (OxygenOS ignores the chronometer) |
| T12 overnight idle | open | read from the first rest of the next session |
| T13 airplane-mode session | pass | 2026-10-09, outbox drained after reconnect |
| T14 determinism self-test | pass | 7/7 golden hashes equal on ART, engine 1.0.0 |
| T15-T17 | n/a | tone, habit reminders and calendar arrive with later milestones |

Real-session alarms (`rest_alarm_fired`): 7, 10 and 9 ms late, app in the foreground or foreground service.


## M1b log (2026-10-11)

**Builds**: debug builds installed over wireless adb. `gradlew check` green: 186 unit tests (`:algo` 74, `:data` 49,
`:app` 63), including the Room 1 -> 2 migration test (a fixture row in every v1 table) and the muscle-map Roborazzi
screenshots. On the phone Room migrated v1 -> v2 in place; the pre-migration backup is in `files/db-backups/`.

**Supabase**: migration 017 (`fitness_review`, 26 security-invoker views, no grants; checklist note in
`supabase/migrations/notes/20261011163142_fitness_017.md`). Live RLS suite `FITNESS_DB_TESTS_PASSED 76 (live)`,
local `89 (local)`, residue 0; advisors unchanged; probes P1-P3 unchanged, `fitness_review` never exposed.

**Done-when checks** (run over adb on the phone, verified read-only on Supabase):

| Check | Result | Evidence |
|-------|--------|----------|
| Correct a past session | pass | 2026-10-09 set 1 reps 8 -> 9: `set_logs` and `v_set_facts` showed 9 within 4 s, one `set_edited {field: reps}`; set back to 8 the same way. Delete set + Undo restored the set (`set_deleted`, `action_undone`). |
| Export your data | pass | `fitnessapp-export-2026-10-11.json.gz`, 22,362 bytes, 370 rows (353 synced, 17 local-only); canonical JSON, one user; the file's SHA-256 equals the synced `exports` row; `export_completed {rows: 370}`. |
| Review views return your sessions | pass | `v_session_summary` returns 2026-10-09 and 2026-10-11. `v_data_quality`: DQ06 x3 at first (the 2026-10-09 ab-crunch sets had no load), cleared by giving them a test load of 30 lb in History; DQ10 x2 remain (params snapshots arrive in M3). |

**Fixed during the phone checks**:
- A sync worker that cold-started the process ran before Supabase auth had loaded the stored session, showed "Sign
  in again to upload" and dropped the run; it now waits for the session (`SyncRemote.awaitAuthLoaded`).
- A write made while a sync run was active (for example the export record) waited for the next trigger, because its
  `KEEP` request was dropped; the run now drains the outbox before it ends, as ANDROID.md 9.3 specifies.
- Set edit sheet: a set logged without a load read "null (as logged)"; it now reads "No weight logged".
- Undo snackbars lasted 4 s; they now follow UX P6 (6 s, 10 s for session actions, longer when the accessibility
  timeout is longer). "Last export" counts calendar days ("today", "yesterday").
- Exercise detail: a preference change no longer shows an Undo snackbar (D30, your call during the checks).
- Dutch exercise names are shown and searched only when the phone language is Dutch (D12 updated, your call).
- Session detail quick check: one layout before and after the 24 h window ("Change" for effort and note, "Add pain",
  "Editable until ..."); the catalog list drops the Pattern filter (your calls, changelog 2026-10-11).
- Debug builds only: `DebugActivity --es action restore_set --es set_id <id>` restores a set deleted in History
  through the same code as Undo (used once when an Undo window ran out during the adb test).

## M2a log (2026-10-20)

**Builds**: `gradlew check` green: 304 unit tests (`:algo` 163, `:sim` 23, `:data` 50, `:app` 68). M2a changes no
screen and no database object (D32).

**Engine** (how it was built: CHALLENGE_ENGINE 16.1): FitExpr, the definition validator (stages 1 and 2, checks
V01-V18), the enrollment fold with tick synthesis and catch-up, the staged rule engine with audit rows, the
materializer with in-session rules and Undo, the week planner with today advice, the contract and the gate criteria.
`oap@1.0.0` validates with 43 rules and 9 review warnings (V14 listings and the P18 fallback, V07).

```powershell
.\gradlew.bat :sim:run --args="challenge validate docs/challenges/oap.challenge.v1.json"
.\gradlew.bat :sim:run --args="challenge simulate --persona all --out build/sim-out"
.\gradlew.bat :sim:test -PupdateGoldens   # rewrite the persona snapshots after a reviewed rule change
```

**Done-when checks**:

| Check | Result | Evidence |
|-------|--------|----------|
| GF-CE fixtures that need no app | pass | GF-CE-01..24b, 28 (postponement), 29-35, 37, 38, 39 in `:algo`; calendar fixtures 25-27 follow in M2b, 36 in M4. |
| Persona snapshots replay byte-identically | pass | 8 personas x proposals approved / declined, 26 weeks each, 16 snapshots in `fixtures/golden/challenge/`; two runs per test and a second JVM produce the same bytes. |
| Global invariants | pass | Every load loadable, no session on an all-gym blocked day, state vars within bounds, an audit row for every changed var, the incremental fold equals a full refold, one `day.tick` per date (also across 2026-10-25). |

**Found and fixed**:
- The validator rejected `struct.resume.interrupt`: `event.gapDays >= 1` compares Days with a plain number; the
  unpublished `oap@1.0.0` now says `1d`.
- The elbow persona showed every new pain report restarting the 14-day protocol, so P14's extension could never
  act; `safety.elbow.stop` now only enters injury from outside it, as OAP_CHALLENGE 4.1 describes.
- The long elbow scenario with the proposals declined showed a block rollover taking the mode out of injury while the
  pain persisted; the engine now keeps injury unless a safety rule ends it.
- Catch-up folded ticks one by one and lost the `test.due` of a test scheduled on `week.started`; it now folds the
  synthesized batch and synthesizes again only when the planned test moves.
- A rule whose actions resolved to nothing was audited as `applied`; it is now `no_effect`.
- An independent review of the engine against the spec found six bugs, now fixed with tests: "train now" ignored
  hard constraints on the tried slot; with P16 approved a right-arm-only rep did not pass the phase-3 gate; a late
  `resume` revived an abandoned or completed enrollment; a non-ASCII letter hung the FitExpr lexer; Undo of an
  in-session rule wiped a later rule's change; an amendment cycle hung the fold. Home habits for a day
  (`HabitPlanner`: skip, deload scale, injury mute, plank days, measure day) were missing and were added.
