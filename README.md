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
