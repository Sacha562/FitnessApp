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

