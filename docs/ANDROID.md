# ANDROID.md: Android platform, toolchain and app architecture

Status: specification v1.1 (2026-10-07, aligned with the post-spec-pass decisions D13–D16, F21–F27 and
C37–C65). Owner of this file: the Android/platform part of the design.
Source of truth for decisions: `docs/DECISIONS.md` (IDs D1–D16, F1–F27, C1–C65). This document never
overrides it; where it needed a decision that DECISIONS.md does not contain, the item is listed in
section 26 ("Open issues") together with the default this document proposes.

This document is the **owner** (DECISIONS 3.1) of: the Room local schema, timestamp representation and
outbox payload policy (section 8, C40); the calendar edit policy and marker parser (sections 16.5–16.7,
C37); platform behaviour and platform constants (timer, notifications, notification channel ids,
reminders, sync backoff; sections 12, 13, 17, 25, C54); the `AlgoPurityTest` scanner (section 6.3).
Other documents reference these sections instead of restating them.

Readers: the user, and future Claude Code sessions that scaffold, implement, test and review the app.
Everything here is written so that a session can act on it without re-reading the research notes.

---

## Contents

1. Scope and reading guide
2. Platform decisions at a glance
3. Toolchain on Windows 11
4. Repository layout
5. Gradle build: catalog, SDK levels, variants, signing, versioning
6. Module boundaries and `:algo` purity enforcement
7. App architecture (MVVM/UDF, Navigation3, AppContainer, coroutines, WorkManager)
8. Local persistence (Room)
9. Outbox sync (C23)
10. Supabase client and authentication
11. Live session foreground service
12. Rest timer (C28)
13. Notifications
14. Haptics, audio and keep-screen-on
15. Xiaomi / OnePlus / Oppo hardening (D2)
16. Calendar: read and write (C20, C36, D11)
17. Home-habit reminders (D4, C34)
18. Localisation and number format (D12)
19. Permissions matrix
20. Privacy and security
21. Testing strategy
22. CI and distribution (F17)
23. M0 bootstrap checklist
24. Golden fixtures / tests
25. Constants register
26. Open issues
27. Implementation notes for Claude Code
28. Sources

---

## 1. Scope and reading guide

This document specifies everything that is Android-specific or build-specific:

- how the development machine is prepared (JDK, Gradle, Android SDK, phone connection) without
  Android Studio and without changing the system Java 25 default (D1);
- how the repository and the four Gradle modules `:app`, `:algo`, `:data`, `:sim` are laid out and how the
  purity of the deterministic engine in `:algo` is enforced;
- the runtime architecture of the app (UI pattern, dependency injection, threading, background work);
- every platform integration that touches reliability or privacy: Room, outbox sync to Supabase,
  authentication, the session foreground service, the rest timer, notifications, haptics, OEM battery
  killers, the calendar provider, reminders, permissions;
- testing, CI, distribution and the step-by-step M0 bootstrap.

It does not specify the algorithms themselves. Algorithm modules (registry ids such as `rest`,
`calendar.classify`, `challenge.calendar`, `ui.todaycard`) are specified in their own documents; this
document only says where they run, how they are called and which platform data they receive. When this
document mentions an algorithm constant it cites the canonical decision (for example "per C18") rather
than redefining it.

Terminology used throughout:

| Term | Meaning |
|------|---------|
| wall time | `System.currentTimeMillis()`, UTC epoch milliseconds. Used for alarms, logs and sync. |
| elapsed time | `SystemClock.elapsedRealtime()`, milliseconds since boot, monotonic, includes deep sleep. Used for on-screen countdowns within one boot. |
| profile zone | The user's training time zone from the profile, default `Europe/Amsterdam` (F18). Used for every local date, ISO week and reminder time. The device zone is never used for training logic. |
| PlatformConfig | The Kotlin object in `:app` that holds the platform constants of this document (section 25). It is not part of the engine params JSON and not part of `params_hash`; its values are logged in event payloads (C54, section 7.10). |
| user action | A tap by the user in a visible activity. Foreground services are only ever started from a user action (section 11). |

Evidence grades used in this document: **engineering-fact** (documented platform behaviour, verified
against official documentation on 2026-10-07 where marked), **strong / moderate / weak** (empirical
evidence), **expert-opinion** (convention or design judgement), **assumption** (a starting value that
must be tuned from logs).

---

## 2. Platform decisions at a glance

| Topic | Decision | Basis |
|-------|----------|-------|
| UI stack | Native Kotlin, Jetpack Compose, Material 3 (baseline components, stable `material3`) restyled as the "Bento" dark design: tokens in `ui/theme`, Bento components in `ui/common`, bundled Manrope, dark only, no dynamic colour (UX 4) | D1, D31 |
| Build host | Windows 11, no Android Studio, Temurin JDK 21 for Gradle, system Java 25 untouched | D1 |
| Modules | `:app` (Android application: UI, services, platform adapters), `:algo` (pure Kotlin JVM, deterministic engine), `:data` (Android library: Room, outbox sync, supabase-kt), `:sim` (JVM CLI: simulation and replay) | D1, task brief |
| Local database | Room is the source of truth on the phone; Supabase is a synced copy | F4, C23 |
| Sync | Outbox push with plain PostgREST upsert, server `BEFORE UPDATE` LWW trigger keyed on `client_updated_at` with `device_id` tie-break, pull cursor on `server_updated_at` with 120 s overlap, append-only tables use ignore-duplicates | C23 |
| Backend schema | Client `defaultSchema = "fitness"`, publishable key only, never `service_role` | F1, F2, C25 |
| Auth | Email + password with the user's existing account in the shared project; allowlist enforced server-side | D3 |
| Session service | Foreground service type `health` (prerequisite ACTIVITY_RECOGNITION) with `specialUse` fallback, started only from a user action | C28 |
| Rest timer | Timestamp state, `setAlarmClock` by default, `USE_EXACT_ALARM` with `canScheduleExactAlarms()` fallback, chronometer countdown notification, Android 16 Live Update promotion, actions −15 s / +15 s / Skip | C28, C18, F20 |
| OEM hardening | Every reliability measure: FGS, `setAlarmClock`, battery-optimisation exemption, autostart guidance, recents lock, on-device Doze test | D2 |
| Calendar | `CalendarContract` read of the primary calendar (no OAuth); auto-write of challenge sessions and test days for the current and next ISO week with a marker line; general sessions written on "Plan it"; unmarked events never modified unless adopted | D11, C20, C36 |
| Habits | Home habits become in-app reminders at cutover | D4, C34 |
| Language | English UI, decimal point, Dutch calendar titles recognised; app-written challenge titles from the definition's (Dutch) template, general sessions in English | D12, F25 |
| Application id, SDK levels, variants | `dev.fitnessapp` (sandbox `dev.fitnessapp.sandbox`); `minSdk 26`, `compileSdk = targetSdk = 36`; `debug` + `release` share the id and the release key, `sandbox` has sync and real-calendar writes off | F22, F23, F27 |
| Backup and encryption | Android Auto Backup **off** (`allowBackup="false"`, data-extraction rules exclude everything); no SQLCipher in v1 | D16, F21, F24 |
| Distribution | `gradlew installDebug` over adb Wi-Fi; stable release keystore created at M0 and backed up; GitHub Actions + Obtainium optional later | F17 |

---

## 3. Toolchain on Windows 11

### 3.1 Current machine state (read-only observations, 2026-10-07)

| Item | Observed | Consequence |
|------|----------|-------------|
| System Java | Oracle JDK 25.0.3 LTS, on PATH via `C:\Program Files\Common Files\Oracle\Java\javapath` | Must stay the system default (D1). Nothing in this plan edits PATH, JAVA_HOME or system environment variables. |
| JDK 21 | Not installed | Installed at M0 (section 3.2). |
| Android SDK | Not installed | Installed at M0 under `%LOCALAPPDATA%\Android\Sdk` (section 3.4). |
| Git repository | The project folder is not a git repository yet | `git init` at M0 after the user approves (section 23). |
| Python | 3.14 available | Not needed by the build; only for ad-hoc scripts. |
| Shells | PowerShell 5.1 (primary) and Git Bash | Commands below are PowerShell unless marked "Git Bash". PowerShell 5.1 has no `&&`; binary output redirection with `>` corrupts files (UTF-16), so screenshots are pulled with Git Bash or `adb pull` (section 3.7). |

### 3.2 JDK 21 while keeping Java 25 as the system default

AGP 9.4.0 requires JDK 17 or newer and is tested on 17 and 21 (engineering-fact, AGP compatibility table,
verified 2026-10-07). Running the Gradle daemon on Java 25 is possible with Gradle 9.1+, but AGP's
newer-JDK paths are not its tested configuration, so the daemon is pinned to 21.

**Option A (primary): Temurin 21 MSI installed with only the core feature.** The Temurin MSI has
optional features for PATH, JAVA_HOME and `.jar` association. Installing only `FeatureMain` leaves the
system default untouched:

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --exact --source winget `
  --override "/quiet /norestart ADDLOCAL=FeatureMain" `
  --accept-package-agreements --accept-source-agreements
```

- `--accept-package-agreements` accepts the package's licence terms. D1 authorises the install, but the
  acceptance flag is still shown to the user and run only after an explicit "yes" in chat. The MSI
  install triggers a Windows UAC prompt that the user approves.
- Verification: the newest `C:\Program Files\Eclipse Adoptium\jdk-21*` folder contains `bin\java.exe`
  reporting `21.0.x` (Temurin), and plain `java -version` still reports 25.0.3.

**Option B (fallback, no system install): Gradle toolchain provisioning.** If winget is unavailable or the
user prefers no MSI, the root `settings.gradle.kts` applies the `org.gradle.toolchains.foojay-resolver-convention`
plugin and Gradle downloads Temurin 21 into `%USERPROFILE%\.gradle\jdks` on first build. Tools that need
a JDK outside Gradle (`sdkmanager`, `keytool`) then use that folder as a process-scoped `JAVA_HOME`.

**Pinning the daemon (both options).** After the wrapper exists (section 3.3):

```powershell
.\gradlew.bat updateDaemonJvm --jvm-version=21 --jvm-vendor=adoptium
```

This writes `gradle/gradle-daemon-jvm.properties` (keys `toolchainVersion=21`, `toolchainVendor=ADOPTIUM`,
plus per-platform `toolchainUrl.*` entries when a toolchain download repository is configured). The file
is committed, so every build on every machine uses a Java 21 daemon regardless of the system default.
Gradle auto-detects JDKs installed under the Windows registry, so Option A needs no further
configuration; if detection fails, add
`org.gradle.java.installations.paths=C:/Program Files/Eclipse Adoptium/jdk-21.0.x.y-hotspot` to
`%USERPROFILE%\.gradle\gradle.properties` (a user-level Gradle file, not a system setting).

**Process-scoped JAVA_HOME.** For one-off tools the commands set `$env:JAVA_HOME` inside the same
PowerShell invocation. This affects only that process:

```powershell
$jdk21 = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-21*" |
          Sort-Object Name -Descending | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk21
```

### 3.3 Gradle wrapper bootstrap

There is no Android Studio to generate the wrapper, so M0 downloads one official Gradle distribution once
into a temporary folder, verifies its SHA-256 against the `.sha256` file published next to it on
`services.gradle.org`, and uses it only to run the `wrapper` task. Gradle 9 refuses to run in a folder
without a settings file, so the scaffold files (`settings.gradle.kts` at least) are written first.

```powershell
$ProgressPreference = 'SilentlyContinue'   # PowerShell 5.1 downloads are very slow with the progress bar
$boot = "$env:TEMP\fitnessapp-bootstrap"
New-Item -ItemType Directory -Force $boot | Out-Null
Invoke-WebRequest https://services.gradle.org/distributions/gradle-9.8.0-bin.zip -OutFile "$boot\gradle-9.8.0-bin.zip"
Invoke-WebRequest https://services.gradle.org/distributions/gradle-9.8.0-bin.zip.sha256 -OutFile "$boot\gradle-9.8.0-bin.zip.sha256"
$expected = (Get-Content "$boot\gradle-9.8.0-bin.zip.sha256").Trim()
(Get-FileHash "$boot\gradle-9.8.0-bin.zip" -Algorithm SHA256).Hash.ToLower() -eq $expected   # must print True
Expand-Archive "$boot\gradle-9.8.0-bin.zip" -DestinationPath $boot -Force
$env:JAVA_HOME = $jdk21
& "$boot\gradle-9.8.0\bin\gradle.bat" -p C:\Users\sivuc\projects\FitnessApp wrapper `
  --gradle-version 9.8.0 --distribution-type bin --gradle-distribution-sha256-sum $expected
```

The wrapper's `distributionSha256Sum` is therefore pinned from the first commit. Gradle 9.8.0 was the
current documented version on 2026-10-07; AGP 9.4.0 requires at least 9.6.0. If a newer 9.x is current at
scaffold time, use it and re-check the AGP compatibility table.

### 3.4 Android SDK command-line tools

Layout (engineering-fact, `sdkmanager` documentation): the zip contains a top-level `cmdline-tools`
folder; its contents must live in `<sdk>/cmdline-tools/latest/` (`bin`, `lib`, `NOTICE.txt`,
`source.properties`). The SDK root is `%LOCALAPPDATA%\Android\Sdk` (the location Android Studio would use,
so later tools find it).

| Item | Value (verified 2026-10-07 on developer.android.com; re-verify at M0) |
|------|------|
| File | `commandlinetools-win-15859902_latest.zip` |
| URL | `https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip` |
| Size | 155.7 MB |
| SHA-256 | `90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a` |

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
Invoke-WebRequest https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip -OutFile "$boot\cmdline-tools.zip"
(Get-FileHash "$boot\cmdline-tools.zip" -Algorithm SHA256).Hash.ToLower() -eq "90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a"
Expand-Archive "$boot\cmdline-tools.zip" -DestinationPath "$boot\clt" -Force
New-Item -ItemType Directory -Force "$sdk\cmdline-tools" | Out-Null
Move-Item "$boot\clt\cmdline-tools" "$sdk\cmdline-tools\latest"
$env:JAVA_HOME = $jdk21
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --version
```

Packages installed by Claude after the licences are accepted (section 3.5):

| Package | Why |
|---------|-----|
| `platform-tools` | `adb` (wireless debugging, install, logcat, screencap). |
| `platforms;android-36` | `compileSdk 36` (Android 16 APIs incl. Live Update promotion). |
| `build-tools;36.0.0` | Default build tools of AGP 9.4.0 (engineering-fact). |
| `platforms;android-37` | Only if `compileSdk` is raised to 37 after the phone's Android version is known (F23 says revisit then; open issue OI-01). AGP 9.4.0 supports up to API 37. |

```powershell
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --install "platform-tools" "platforms;android-36" "build-tools;36.0.0"
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --list_installed
```

No emulator, system image or HAXM/WHPX is installed (an emulator would need the Windows Hypervisor
Platform, a system setting). The documentation now marks `sdkmanager` as deprecated in favour of the
preview "Android CLI" (`android sdk install|list|update|remove`); `sdkmanager` still works and is used
because it is the stable, documented path. Re-evaluate at the first SDK update after M0.

The SDK location reaches Gradle through `local.properties` (`sdk.dir=...`, git-ignored), not through
`ANDROID_HOME`, so no environment variable is changed.

### 3.5 Licence acceptance (user action)

Android SDK licences must be accepted by the user (D1; DECISIONS §5 item 1). Claude never pipes "y" into
`sdkmanager`. The user runs, in their own terminal:

```powershell
$env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-21*" | Sort-Object Name -Descending | Select-Object -First 1).FullName
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

This writes hash files into `<sdk>\licenses\`. After that, Claude's non-interactive package installs
succeed. When a later SDK package introduces a new licence, the install fails with a licence message and
the user repeats this step.

### 3.6 Phone connection: wireless debugging

Requirements: Android 11 or newer on the phone, phone and PC on the same Wi-Fi network without client
isolation.

User steps (once; DECISIONS §5 item 2):

1. Enable Developer options: tap the build number 7 times. Xiaomi/HyperOS: Settings → About phone → "OS
   version" (or "MIUI version") 7 times. OnePlus/Oppo (ColorOS/OxygenOS): Settings → About device →
   Version → "Build number" 7 times.
2. Developer options → **Wireless debugging** on → "Pair device with pairing code". The phone shows
   `IP:pairing-port` and a 6-digit code.
3. The user runs `adb pair <IP>:<pairing-port>` in their own terminal and types the code (the code is a
   short-lived secret; Claude does not handle it):
   `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" pair 192.168.1.23:37123`
4. Windows Defender Firewall may ask whether `adb.exe` may use the network; that is a security setting
   the user answers.
5. **Xiaomi / Redmi / POCO only:** enable Developer options → "Install via USB" and "USB debugging
   (Security settings)". Without them, installs fail with `INSTALL_FAILED_USER_RESTRICTED`. HyperOS may
   require a signed-in Xiaomi account and a SIM card to enable them. If installs still fail, some
   HyperOS builds need Developer options → "Turn on system optimization" (also labelled "MIUI
   optimization") switched off; this is a user decision because it changes system behaviour.
6. **OnePlus / Oppo:** if `adb install` is blocked, check Developer options → "Verify apps over USB"
   (switch off for sideloading) and, on some ColorOS builds, "Disable permission monitoring".

Claude's steps afterwards (each reconnect): `adb mdns services` lists the phone's connect port
(`_adb-tls-connect`), `adb connect <IP>:<connect-port>` connects (the connect port differs from the pairing
port and changes when Wireless debugging is toggled), `adb devices -l` must list the device as `device`.
Paired PCs reconnect automatically on the same network with recent platform-tools.

Device facts read once at M0 to close the D2 open item (model and Android version), all non-personal:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell getprop ro.product.manufacturer
& $adb shell getprop ro.product.model
& $adb shell getprop ro.build.version.release
& $adb shell getprop ro.build.version.sdk
& $adb shell getprop ro.build.display.id
```

### 3.7 Visual feedback without an emulator

Claude needs to see the UI. Two channels, no emulator:

1. **Roborazzi screenshots on the JVM** (Robolectric native graphics). `.\gradlew.bat :app:recordRoborazziDebug`
   writes PNGs under `app/build/outputs/roborazzi/`; Claude reads them directly. `verifyRoborazziDebug`
   compares against committed baselines in `app/src/test/screenshots/`. Robolectric 4.17 supports SDK 37
   (verified); its release notes mention a native-graphics limitation on Windows for SDK 35, so screenshot
   tests pin `@Config(sdk = [34])` until a first run on this machine proves a newer SDK renders correctly.
2. **Real screenshots from the phone.** In Git Bash (binary-safe redirection):
   `"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" exec-out screencap -p > shot.png`.
   In PowerShell use the two-step form, because `>` in PowerShell 5.1 rewrites binary output as UTF-16:
   ```powershell
   & $adb shell screencap -p /sdcard/fitness_shot.png
   & $adb pull /sdcard/fitness_shot.png "$env:TEMP\fitness_shot.png"
   & $adb shell rm /sdcard/fitness_shot.png
   ```
   Optional for text-level checks: `adb shell uiautomator dump /sdcard/ui.xml` + `adb pull` (Compose
   nodes appear when they carry `testTag`/content descriptions).

### 3.8 Version table (verify at scaffold time)

The research versions are treated as unverified, in the same spirit as C29: Claude pins nothing before
checking Maven Central / Google Maven at scaffold time. Rows marked "verified 2026-10-07" were checked
against the official release page while writing this document; they still get a final check at scaffold.

| Component | Version | Status | Notes |
|-----------|---------|--------|-------|
| Android Gradle Plugin | 9.4.0 | verified 2026-10-07 | Gradle ≥ 9.6.0, JDK ≥ 17, build tools 36.0.0, max API 37. Built-in Kotlin on by default (no `org.jetbrains.kotlin.android` plugin); `kapt` incompatible (use KSP). |
| Gradle | 9.8.0 | verified 2026-10-07 (current docs) | Wrapper with pinned SHA-256. |
| Kotlin / KGP | 2.4.20 | verified 2026-10-07 | AGP 9.x bundles an older KGP runtime dependency; a newer KGP is selected by declaring the Kotlin Gradle plugin version in the root build (verify the exact mechanism in the AGP 9 built-in-Kotlin migration guide at scaffold). Compose compiler plugin `org.jetbrains.kotlin.plugin.compose` and `plugin.serialization` at the same Kotlin version. |
| KSP | latest 2.3.x+ | unverified | KSP versioning is decoupled from Kotlin since KSP 2.3.0; pick the newest release that lists AGP 9 + Kotlin 2.4 support. |
| Compose BOM | 2026.09.00 | verified 2026-10-07 | ui/foundation 1.12.1, material3 1.4.0. Material 3 Expressive APIs only from the 1.5.0 alpha line; not used (stable baseline only). |
| Navigation3 | 1.2.0 | verified 2026-10-07 | `androidx.navigation3:navigation3-runtime`, `navigation3-ui`; ViewModel scoping via `androidx.lifecycle:lifecycle-viewmodel-navigation3` (verify artifact and version). |
| Room | 2.8.5 | verified 2026-10-07 | KSP codegen, Room Gradle plugin `schemaDirectory`, minSdk 23. |
| WorkManager | 2.12.0 | verified 2026-10-07 | minSdk 24. |
| supabase-kt BOM | 3.8.0 | newest on Maven Central 2026-10-07 | `io.github.jan-tennert.supabase:bom`, modules `auth-kt`, `postgrest-kt`. Publishable key supported (`supabaseKey` = publishable key per the official Kotlin reference). Android minSdk 26 without desugaring (research). |
| Ktor client | align with supabase-kt BOM | unverified (research: 3.4.3) | `ktor-client-okhttp`. Never pin a Ktor version that differs from the one supabase-kt was built with without a test run. |
| AndroidX core | newest stable | unverified | Must expose `NotificationCompat.Builder.setRequestPromotedOngoing` and `setShortCriticalText`; otherwise set the promotion extra directly on API 36. |
| activity-compose, lifecycle, DataStore, kotlinx-serialization, kotlinx-coroutines | newest stable | unverified | |
| Vico (charts) | 3.3.1 | unverified | `compose-m3` artifact. |
| JUnit | newest Jupiter (5.x or 6.x) | unverified | Robolectric tests run on JUnit 4 through the vintage engine. |
| kotest (assertions, property) | 6.2.5 | unverified | Used inside JUnit tests; no kotest runner needed. |
| Turbine | newest | unverified | Flow testing. |
| Robolectric | 4.17 | verified 2026-10-07 | Supports SDK 37. |
| Roborazzi | 1.66.0 | unverified | Gradle plugin `io.github.takahirom.roborazzi`. |
| Android cmdline-tools | 15859902 | verified 2026-10-07 | See 3.4. |

---

## 4. Repository layout

```
FitnessApp/
├─ .gitattributes              # * text=auto eol=lf ; *.bat eol=crlf ; *.png *.jks binary
├─ .gitignore                  # local.properties, build/, .gradle/, *.jks, *.keystore, captures/, .kotlin/
├─ README.md                   # what the app is, how to build, links to docs/
├─ CLAUDE.md                   # short rules for Claude Code sessions (purity, never uninstall, migrations via MCP)
├─ settings.gradle.kts         # includes :app :algo :data :sim, includeBuild("build-logic")
├─ build.gradle.kts            # plugin versions with apply false
├─ gradle.properties
├─ gradle/
│  ├─ libs.versions.toml       # version catalog (section 5.1)
│  ├─ gradle-daemon-jvm.properties
│  └─ wrapper/gradle-wrapper.properties, gradle-wrapper.jar
├─ gradlew, gradlew.bat
├─ local.properties            # git-ignored: sdk.dir, supabase.url, supabase.publishableKey
├─ build-logic/                # convention plugins (composite build)
│  └─ convention/src/main/kotlin/
│     ├─ fitness.jvm.library.gradle.kts        # :algo, :sim
│     ├─ fitness.android.library.gradle.kts    # :data
│     ├─ fitness.android.application.gradle.kts# :app
│     └─ fitness.algo.purity.gradle.kts        # verifyAlgoDependencies task (section 6.3)
├─ app/                        # Android application
│  ├─ src/main/AndroidManifest.xml
│  ├─ src/main/kotlin/dev/fitnessapp/app/...   (section 27)
│  ├─ src/main/res/            # strings (English), drawables, xml/data_extraction_rules.xml, xml/network_security_config.xml
│  ├─ src/debug/               # debug menu, StrictMode, local test calendar
│  ├─ src/test/                # JVM unit tests, Robolectric, Roborazzi
│  └─ src/test/screenshots/    # Roborazzi baselines
├─ algo/                       # pure Kotlin JVM library
│  ├─ src/main/kotlin/dev/fitnessapp/algo/<registry module packages>
│  ├─ src/main/resources/fitness/   # bundled defaults (data only): params/ (C40), catalog, challenge definitions
│  └─ src/test/
├─ data/                       # Android library: Room, sync, Supabase
│  ├─ schemas/                 # Room exported schema JSON (committed)
│  ├─ src/main/kotlin/dev/fitnessapp/data/...
│  └─ src/test/
├─ sim/                        # JVM CLI (application plugin)
│  ├─ src/main/kotlin/dev/fitnessapp/sim/...
│  └─ src/test/
├─ fixtures/
│  ├─ golden/                  # engine golden cases (input, expected output, expected trace)
│  ├─ android/                 # platform fixtures of section 24 (calendar rows, timer event scripts, ...)
│  ├─ personas/                # synthetic athletes for :sim
│  └─ replay/                  # anonymised exports for replay tests (added after real data exists)
├─ supabase/
│  ├─ migrations/              # fitness_*.sql, applied through Supabase MCP after user review (F3)
│  ├─ tests/                   # pgTAP / RLS checks run in a rollback transaction, synthetic UUIDs only (C61)
│  └─ snapshots/fitness.types.ts  # generated TypeScript types, used by the DTO drift test
├─ tools/
│  ├─ screencap.sh             # Git Bash helper
│  ├─ doze-test.ps1            # section 15.6 sequence, only run with the user's OK
│  └─ verify-env.ps1           # prints JDK/SDK/adb status (read-only)
├─ docs/                       # DECISIONS.md, ANDROID.md, other specs, research/
└─ .github/workflows/ci.yml    # optional (section 22)
```

Bundled defaults live in `algo/src/main/resources/fitness/` so that the app, the simulator and the tests
read exactly the same bytes. The bundled params files are at `algo/src/main/resources/fitness/params/`
(canonical path, C40; for example `default-params-1.0.0.json`). The other bundled data sit next to them
under the same root: `fitness/catalog/` (catalog seed, for example `catalog-1.0.0.json`),
`fitness/challenges/` (published challenge definitions copied from `docs/challenges/`) and `fitness/cds/`
(the definition JSON Schema), as CHALLENGE_ENGINE also uses. Loading them is IO and therefore happens
outside `:algo` code (a loader in `:data` and in `:sim`); `:algo` functions receive parsed objects.

---

## 5. Gradle build

### 5.1 Version catalog (`gradle/libs.versions.toml`)

Values in angle brackets are filled at scaffold time after checking Maven Central / Google Maven
(section 3.8). The structure below is normative; the numbers are not.

```toml
[versions]
agp = "9.4.0"
kotlin = "2.4.20"
ksp = "<verify>"
composeBom = "2026.09.00"
activityCompose = "<verify>"
lifecycle = "<verify>"
navigation3 = "1.2.0"
coreKtx = "<verify: must have setRequestPromotedOngoing>"
room = "2.8.5"
work = "2.12.0"
datastore = "<verify>"
supabase = "3.8.0"
ktor = "<same as supabase-kt build>"
serialization = "<verify>"
coroutines = "<verify>"
vico = "<verify, research 3.3.1>"
junit = "<verify>"
kotest = "<verify, research 6.2.5>"
turbine = "<verify>"
robolectric = "4.17"
roborazzi = "<verify, research 1.66.0>"
androidxTest = "<verify>"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-navigation3 = { module = "androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycle" }
androidx-lifecycle-process = { module = "androidx.lifecycle:lifecycle-process", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "navigation3" }
navigation3-ui = { module = "androidx.navigation3:navigation3-ui", version.ref = "navigation3" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
supabase-bom = { module = "io.github.jan-tennert.supabase:bom", version.ref = "supabase" }
supabase-auth = { module = "io.github.jan-tennert.supabase:auth-kt" }
supabase-postgrest = { module = "io.github.jan-tennert.supabase:postgrest-kt" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
vico-compose-m3 = { module = "com.patrykandpatrick.vico:compose-m3", version.ref = "vico" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-vintage = { module = "org.junit.vintage:junit-vintage-engine" }
junit4 = { module = "junit:junit", version = "<verify 4.13.x>" }
kotest-assertions = { module = "io.kotest:kotest-assertions-core", version.ref = "kotest" }
kotest-property = { module = "io.kotest:kotest-property", version.ref = "kotest" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
roborazzi = { module = "io.github.takahirom.roborazzi:roborazzi", version.ref = "roborazzi" }
roborazzi-compose = { module = "io.github.takahirom.roborazzi:roborazzi-compose", version.ref = "roborazzi" }
roborazzi-junit-rule = { module = "io.github.takahirom.roborazzi:roborazzi-junit-rule", version.ref = "roborazzi" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTest" }
androidx-test-ext-junit = { module = "androidx.test.ext:junit", version = "<verify>" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
room = { id = "androidx.room", version.ref = "room" }
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
```

No Hilt, no kapt, no Firebase, no analytics or crash-reporting SDKs, no Google Play services.

### 5.2 SDK levels and JVM targets

| Setting | Value | Rationale |
|---------|-------|-----------|
| `minSdk` | 26 (Android 8.0) | F23. supabase-kt's Android floor without core-library desugaring; `java.time` available natively; inclusive for a future friend's phone (D3). |
| `compileSdk` | 36 | F23. Android 16 APIs (Live Update promotion, `POST_PROMOTED_NOTIFICATIONS`). Revisit (raise to 37) when the phone's OS is known and Android 17 behaviour (section 14.2) has been tested (OI-01). |
| `targetSdk` | = `compileSdk` (AGP 9 default) | Sideloaded app; no Play target-level deadline. |
| JVM toolchain | 21 for all modules | Same JDK as the daemon. |
| Bytecode / API level of `:algo` and `:sim` | `jvmTarget = 17`, `-Xjdk-release=17` | `:algo` runs on ART; restricting it to the Java 17 API surface reduces the risk of calling JDK APIs that ART at minSdk does not have (see 6.3, rule P9). |
| Android `compileOptions` | source/target 17 | |

### 5.3 Build variants

F17 makes `gradlew installDebug` the everyday install path and requires a stable release keystore whose
loss would force a data-wiping reinstall. That only holds if the installed app is signed with the release
key, so both `debug` and `release` are signed with it and share one application id (F27); a third variant
is the sandbox for experiments. F22 and F27 call the sandbox a "flavour"; it is implemented as a third
**build type** (no product-flavour dimension), so that `installDebug` stays the F17 command and no
variant names change. The behaviour is exactly the one F27 describes.

| Build type | applicationId | Signed with | Debuggable | R8 | Sync | Calendar writes | Extras |
|------------|---------------|-------------|------------|----|------|-----------------|--------|
| `debug` | `dev.fitnessapp` (F22) | release keystore | yes | no | on (daily app, F27) | primary calendar (D11) | Debug menu, StrictMode (log only), verbose session_event payloads |
| `release` | `dev.fitnessapp` | release keystore | no | yes (full mode, resource shrinking) | on | primary calendar (D11) | Used by CI/Obtainium; installable over a `debug` build in place |
| `sandbox` | `dev.fitnessapp.sandbox` (F22) | default debug keystore | yes | no | **off** (local only, F27) | **off** for the user's calendars (F27); only the device-local test calendar the app creates itself (16.10) | Seeded fixture data, safe for destructive experiments |

Rules:

- `debug` and `release` replace each other in place because the signature and id are identical; the
  versionCode must not decrease (5.5).
- If the release signing properties are missing (for example on a new PC), the `debug` and `release`
  builds fail with a clear message instead of silently falling back to the debug key. A silent fallback
  would produce an APK that cannot update the installed app, and the only "fix" (uninstall) wipes the
  local database.
- `BuildConfig` fields: `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` (from `local.properties` or CI
  secrets), `ENGINE_VERSION` (copied from `:algo`), `SYNC_ENABLED` (false for `sandbox`),
  `USER_CALENDAR_WRITES_ENABLED` (false for `sandbox`; `CalendarWriteExecutor` then refuses every target
  except the app-owned local test calendar of 16.10), `GIT_SHA`.
- R8 keep rules are needed for kotlinx-serialization DTOs, Room entities (Room ships its own rules) and
  supabase-kt/Ktor (verify the library-provided consumer rules at scaffold). Every release build is
  smoke-tested on the phone (section 21.5) before it becomes the daily install.

### 5.4 Signing (F17)

The release keystore is created at M0 by the user (the command prompts for a password; Claude does not
handle keystore passwords):

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.android-keystores" | Out-Null
& "$jdk21\bin\keytool.exe" -genkeypair -v `
  -keystore "$env:USERPROFILE\.android-keystores\fitnessapp-release.jks" -storetype PKCS12 `
  -alias fitnessapp -keyalg RSA -keysize 4096 -validity 36500 `
  -dname "CN=FitnessApp, O=Personal, C=NL"
```

The user then adds four lines to `%USERPROFILE%\.gradle\gradle.properties` (user-level, outside the
repository): `FITNESS_RELEASE_STORE_FILE`, `FITNESS_RELEASE_STORE_PASSWORD`, `FITNESS_RELEASE_KEY_ALIAS`
(`fitnessapp`), `FITNESS_RELEASE_KEY_PASSWORD` (PKCS12 uses the same password for store and key).

Backup (user, at M0): copy the `.jks` file to two independent places (for example a cloud drive folder
and an offline USB stick) and store the password in a password manager. The certificate's SHA-256
fingerprint (not secret) is recorded in `README.md` after `keytool -list -v` (the user runs it; it
prompts for the password) so that any future APK can be checked with
`apksigner verify --print-certs`.

Loss of the keystore means the next build cannot update the installed app. Recovery then requires: empty
outbox, fresh JSON export (20.4), uninstall, install with the new key, sign in, full pull. Claude never
performs these steps without the user's explicit approval in chat.

### 5.5 Versioning

- `versionCode` = number of commits on the current branch (`git rev-list --count HEAD`), computed at
  configuration time; falls back to 1 when git is unavailable. Strictly increasing for every commit,
  which Obtainium and Android require for updates.
- `versionName` = `0.<milestone>.<versionCode>+<short git sha>`, for example `0.1.42+3f9c2ab`.
- `ENGINE_VERSION` (C30) is a constant in `:algo`, independent of the app version, and is shown in
  You → Transparency.

### 5.6 `gradle.properties` (repository)

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
kotlin.code.style=official
```

`-Dfile.encoding=UTF-8` matters on Windows: fixtures contain Dutch titles with diacritics ("één arm
zwaar") and a non-UTF-8 default charset would change test results.

---

## 6. Module boundaries and `:algo` purity enforcement

### 6.1 Dependency graph

```
:app  ──► :data ──► :algo
  └────────────────► :algo
:sim  ──────────────► :algo        (never :data or :app; reads exported JSON)
```

| Module | Plugin | May depend on | Must not depend on |
|--------|--------|---------------|--------------------|
| `:algo` | `kotlin("jvm")` + serialization | Kotlin stdlib, `kotlinx-serialization-core/json` | anything else (no Android, no coroutines, no Ktor, no Room, no logging) |
| `:data` | `com.android.library` + KSP + Room | `:algo`, Room, WorkManager, supabase-kt, Ktor, DataStore, coroutines, serialization | `:app` |
| `:app` | `com.android.application` + Compose | `:data`, `:algo`, AndroidX UI libraries | — |
| `:sim` | `kotlin("jvm")` + `application` | `:algo`, serialization, a CLI parser (optional) | `:data`, `:app`, any Android artifact |

`:sim` replays history from exported JSON files and from the `fitness_review` exports, not from Room,
which keeps it a plain JVM program that runs in seconds.

### 6.2 Purity rules for `:algo` (and the `ui.*` pure functions in `:app`)

The registry modules (core, challenge, accounting, estimation, targets, planning, selection,
prescription) live in `:algo`. The ui-deterministic modules (`ui.todaycard`, `ui.prefill`,
`ui.reasonchips`, `ui.prompts`, `ui.volumebars`, `ui.warndisplay`, `ui.progress`; specified by
ALGORITHMS_PLANNING §19, which owns all `ui.*` specs per C53) live in
`app/src/main/kotlin/dev/fitnessapp/app/uidet/` and obey the same rules.

| Rule | Statement | Why |
|------|-----------|-----|
| P1 | No `android.*`, `androidx.*`, `com.google.android.*` imports. | Runs on the JVM for tests and `:sim`. |
| P2 | No system clock: no `System.currentTimeMillis`, `System.nanoTime`, `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()`, `ZonedDateTime.now()`, `Clock.system*()`, `SystemClock`. Time comes from the injected `core.clock` value (C31). | Replay must reproduce the same output. |
| P3 | No randomness: no `java.util.Random`, `kotlin.random`, `Math.random`, `SecureRandom`, `UUID.randomUUID`, `SplittableRandom`. (`:sim` uses seeded `SplittableRandom` in its own code, outside `:algo`.) | Determinism. |
| P4 | No IO: no `java.io`, `java.nio.file`, `java.net`, `kotlinx.coroutines`, `Thread`, `System.getenv`, `System.getProperty`, `Locale.getDefault`, `TimeZone.getDefault`, `ZoneId.systemDefault`. | Purity; locale and zone must be explicit inputs. |
| P5 | Transcendental maths through `StrictMath` only; `Math.*` and `kotlin.math` functions `exp, ln, log, log10, log2, pow, sin, cos, tan, asin, acos, atan, atan2, sinh, cosh, tanh, cbrt, hypot, expm1, ln1p` and `Double.pow` are forbidden. `abs, min, max, floor, ceil, round, sign, sqrt` are allowed (exactly specified by IEEE-754). | C22; JVM intrinsics and ART may differ by 1 ulp in `Math.*`. |
| P6 | No `HashMap`, `HashSet`, `hashMapOf`, `hashSetOf`, `java.util.concurrent.*`. Use `LinkedHashMap`/`mapOf` (insertion order), `TreeMap`/`sortedMapOf`, or sorted lists. | Iteration order must not depend on hash codes. |
| P7 | No top-level or `object`-level `var`, no `lateinit` globals. | Hidden state breaks replay. |
| P8 | No `String.format`/`.format(` without an explicit `Locale.ROOT`; numbers that are hashed or stored go through the canonical formatter of C22 (kg 0.01, s 0.1, ratio 1e-4). | Locale-dependent output. |
| P9 | Only JDK APIs available on Android API 26 (plus those the D8 dexer desugars; "D8" here is the Android dex compiler, not decision D8). | `:algo` runs on ART. |

ALGORITHMS.md A2 (owner of those rules) adds the algorithm-level rules **P10–P16** for the same code:
canonical input order (P10), stable sorts with a total tie-break (P11), `Det.cmpScore` with `TIE_EPS`
for score comparisons (P12), no `Double`-to-text except through `Canon` (P13), no NaN/infinity leaving a
module (P14), params only through `ParamView` (P15), no calls into another module's internals (P16).
They are not restated here; section 6.3 scans for the parts that a line-based source scanner can detect
(OI-A02), and ALGORITHMS.md's tests (`ShuffleInvarianceTest`, `ParamsCoverageTest`, golden hashes)
enforce the rest.

### 6.3 Enforcement

1. **Dependency allowlist task.** The convention plugin `fitness.algo.purity` registers
   `verifyAlgoDependencies`, wired into `check`. It resolves `:algo`'s `runtimeClasspath` and fails if any
   module group/name is outside: `org.jetbrains.kotlin:kotlin-stdlib*`, `org.jetbrains:annotations`,
   `org.jetbrains.kotlinx:kotlinx-serialization-core*`, `org.jetbrains.kotlinx:kotlinx-serialization-json*`.
2. **Source scanner test** `AlgoPurityTest` in `algo/src/test`. It walks
   `algo/src/main/kotlin/**/*.kt` and `app/src/main/kotlin/dev/fitnessapp/app/uidet/**/*.kt`, strips
   comments and string literals, and fails with file:line for each match of the forbidden patterns:

   | Pattern (regex, applied per line) | Rule |
   |-----------------------------------|------|
   | `^\s*import\s+(android|androidx|com\.google\.android)\.` | P1 |
   | `System\.(currentTimeMillis|nanoTime|getenv|getProperty)\b` | P2/P4 |
   | `\b(Instant|LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|Year|YearMonth)\.now\s*\(` | P2 |
   | `\bClock\.system\w*\(|\bSystemClock\b` | P2 |
   | `\bjava\.util\.Random\b|\bkotlin\.random\b|\bRandom\s*\(|Math\.random|SecureRandom|UUID\.randomUUID|SplittableRandom` | P3 |
   | `^\s*import\s+(java\.io|java\.nio\.file|java\.net|kotlinx\.coroutines|java\.util\.concurrent)\b` | P4/P6 |
   | `\bThread\b|Locale\.getDefault|TimeZone\.getDefault|ZoneId\.systemDefault` | P4 |
   | `\b(Math|kotlin\.math)\.(exp|log|log10|log1p|expm1|pow|sin|cos|tan|asin|acos|atan|atan2|sinh|cosh|tanh|cbrt|hypot)\s*\(` and imports of the same names from `kotlin.math`, and `\.pow\s*\(` | P5 |
   | `\b(HashMap|HashSet|hashMapOf|hashSetOf)\b` | P6 |
   | `^(var|lateinit\s+var)\s` at top level, and `var` declarations inside `object` bodies | P7 |
   | `String\.format\s*\(|\.format\s*\(` on a line without `Locale.ROOT` | P8 |
   | `\.parallelStream\s*\(|\.parallel\s*\(\s*\)|\bparallelSort\b` | P11 (and P4): parallel streams and sorts have no deterministic order |
   | `\bDouble\.toString\s*\(|\bjava\.lang\.Double\.toString\b|\bString\.valueOf\s*\(` outside `core/det/` | P13 |
   | `\bBigDecimal\.valueOf\s*\(|\.toBigDecimal\s*\(` outside `core/det/` (both go through `Double.toString` for doubles; the exact `BigDecimal(…)` constructors are allowed) | P13 |
   | On the raw line, before string stripping: a `.format(` call whose format string contains a floating-point conversion (`%[-#+ 0,(]*\d*(\.\d+)?[eEfgGaA]`) | P13 |
   | `^\s*import\s+dev\.fitnessapp\.algo\.(\S+)\.impl\.` in a file whose package is not `dev.fitnessapp.algo.<same>` or below | P16 (relies on the `impl` sub-package convention of 27.1, adopted by ALGORITHMS.md's implementation notes) |

   Rules P10, P12, P14 and P15, and the parts of P11/P13 that need type information (comparators
   without a tie-break, string templates or `toString()` on a `Double` receiver), cannot be detected
   reliably by a line scanner; they are guarded by the ALGORITHMS.md A2 tests (`ShuffleInvarianceTest`
   for P10/P11, unit tests for P12, property tests for P14, `ParamsCoverageTest` for P15) and the golden
   hash tests. This closes ALGORITHMS OI-A02 as far as scanning is feasible (ALGORITHMS.md marks it
   resolved).

   Allowed exceptions: `core/clock/Clock.kt` may mention `Instant`/`ZoneId` types (it defines the injected
   interface) but still may not call `now()`; `core/det/` (`Det`, `Grid`, `Canon`, ALGORITHMS.md A3) is
   the only place where the P13 patterns may appear. No escape comments exist; changing the rule list
   requires editing the test, which is visible in review.
3. **Android API-level check (P9).** Optional but recommended: the animal-sniffer Gradle plugin with an
   Android API 26 signature (for example the "gummy-bears" signatures) on `:algo`. Verify plugin and
   signature availability at scaffold; if not available, rely on rule 4.
4. **ART determinism self-test.** The debug menu has "Run determinism self-test": it runs every golden
   fixture in `fixtures/golden/` (bundled into the debug APK as test assets) through `:algo` on the phone,
   computes the RFC 8785 JCS + SHA-256 hash of each output (C24) and compares it with the hash produced by
   `:algo:test` on the JVM (stored in the fixture). Result is shown and written to logcat with the tag
   `DeterminismSelfTest`, so Claude can run it via
   `adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.debug.DebugActivity --es action selftest`
   and read `adb logcat -d -s DeterminismSelfTest`. Any mismatch is a release blocker.
5. **Review rule.** `:app` and `:data` never re-implement engine logic; they assemble inputs, call
   `:algo`, persist the snapshot and render the output.

---

## 7. App architecture

### 7.1 Layers

| Layer | Lives in | Responsibility |
|-------|----------|----------------|
| UI | `:app` `ui/` | Stateless composables + one `Route` composable per screen that collects state and forwards actions. Material 3 only. |
| ViewModels | `:app` `ui/<screen>/` | Hold `StateFlow<UiState>`, receive `UiAction`, emit one-off `UiEffect` through a `Channel`. No Android framework types in state. |
| Controllers (app services) | `:app` `session/`, `timer/`, `calendar/`, `reminders/`, `oem/`, `notify/` | Platform-bound orchestration: `SessionController`, `RestTimerController`, `CalendarSyncController`, `ReminderScheduler`, `ReliabilityStatusProvider`, `NotificationPresenter`. |
| Repositories | `:data` | The only code that touches Room, DataStore and Supabase. Expose `Flow`s and `suspend` functions. Every write goes through `OutboxWriter` (section 9). |
| Engine gateway | `:data` `engine/` | Builds `EngineInput` from Room snapshots, calls `:algo` on `Dispatchers.Default`, stores the recommendation snapshot (C24), returns the typed output. |
| Engine | `:algo` | Pure functions (section 6). |
| Platform adapters | `:app` `platform/` | `AndroidClock`, `CalendarProviderGateway`, `AlarmGateway`, `HapticsPlayer`, `TonePlayer`, `PermissionChecker`. Each behind an interface so that JVM tests use fakes. |

### 7.2 Manual dependency injection: `AppContainer`

No Hilt/Dagger/Koin (fast builds, obvious code, no kapt). `FitnessApplication.onCreate()` creates one
`AppContainer`; everything inside is `by lazy` so cold start stays cheap.

```kotlin
class FitnessApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer; private set
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.startup.run()          // channels, crash handler, timer recovery, observers (7.7)
    }
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(container.workerFactory).build()
}

class AppContainer(val app: Application) {
    val platformConfig = PlatformConfig.DEFAULT
    val zoneProvider = ProfileZoneProvider(/* DataStore-backed, default Europe/Amsterdam */)
    val clock: core.clock.Clock = AndroidClock(zoneProvider)
    val ids = UuidV7Generator(clock, java.security.SecureRandom())
    val db by lazy { FitnessDatabase.open(app) }            // section 8
    val supabase by lazy { SupabaseFactory.create(app) }     // section 10
    val syncScheduler by lazy { SyncScheduler(WorkManager.getInstance(app), platformConfig) }
    val outbox by lazy { OutboxWriter(db, syncScheduler) }
    val sessionRepository by lazy { SessionRepository(db, outbox, clock, ids) }
    val paramsRepository by lazy { ParamsRepository(db, bundledDefaults) }
    val engine by lazy { EngineGateway(paramsRepository, RecommendationRepository(db, outbox), Dispatchers.Default) }
    val notifications by lazy { NotificationPresenter(app) }
    val alarms by lazy { AlarmGateway(app, platformConfig) }
    val restTimer by lazy { RestTimerController(db, alarms, notifications, clock, platformConfig) }
    val calendar by lazy { CalendarSyncController(CalendarProviderGateway(app.contentResolver), db, engine, clock) }
    val reminders by lazy { ReminderScheduler(alarms, notifications, db, clock) }
    val workerFactory by lazy { FitnessWorkerFactory(this) }
    // ...
}
```

The default WorkManager initializer is removed in the manifest (`androidx.startup` `InitializationProvider`
meta-data removal for `androidx.work.WorkManagerInitializer`) so the custom `WorkerFactory` can inject
repositories into workers.

ViewModels are created with `viewModelFactory { initializer { ... } }` reading the container from
`APPLICATION_KEY`.

### 7.3 UDF contract for screens

```kotlin
@Immutable data class LiveSessionUiState(...)                    // pure data, no Android types
sealed interface LiveSessionAction { data class LogSet(val rir: Int) : LiveSessionAction; ... }
sealed interface LiveSessionEffect { data object VibrateConfirm : LiveSessionEffect; ... }

class LiveSessionViewModel(...) : ViewModel() {
    val state: StateFlow<LiveSessionUiState> =
        combine(sessionRepository.observeActive(), restTimer.observe(), ...) { ... }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveSessionUiState.Loading)
    private val _effects = Channel<LiveSessionEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()
    fun onAction(a: LiveSessionAction) { viewModelScope.launch { ... } }
}
```

Rules: state is derived from repositories (Room is the single source of truth), never from in-memory
mutable copies; every user action that changes data is one repository call that writes the domain row,
the `session_events` row and the outbox entry in one Room transaction; text input in progress lives in
`SavedStateHandle` so it survives process death.

### 7.4 Navigation (Navigation3)

Navigation3 keeps the back stack as app-owned state. Keys are `@Serializable` data objects/classes:

| Key | Screen |
|-----|--------|
| `TodayKey`, `WeekKey`, `ExercisesKey`, `ChallengesKey`, `YouKey` | The five top-level tabs (tab structure owned by the UX spec). Each tab keeps its own back stack (a `SnapshotStateList` per tab, persisted with `rememberSaveable`). |
| `ExerciseDetailKey(exerciseId)`, `ChallengeDetailKey(enrollmentId)`, `SessionDetailKey(sessionId)` | Detail screens. |
| `LiveSessionKey(sessionId)` | Full-screen live session (no bottom bar). |
| `SessionSummaryKey(sessionId)`, `TestDayWizardKey(enrollmentId)`, `OnboardingKey(step)`, `HabitSessionKey(habitId)` | Full-screen flows. |
| `ReliabilityKey`, `SettingsKey(section)`, `SyncStatusKey`, `DebugKey` | Settings, reliability check (15.5), sync status, debug menu (debug/sandbox only). |

`NavDisplay` uses the ViewModel-store entry decorator so each entry gets its own `ViewModelStore`.

**Internal route names (C54).** Notification taps, notification actions that open the app and the
`AlarmClockInfo` show-intent open `MainActivity` with one string extra `route` whose value is one of the
UX §3.4 route names below. These are **internal route names, not deep links**: no intent filter declares
the `fitapp` scheme, so no other app or web page can open them, and the strings never leave the app. A
pure `RouteParser` (`nav/Routes.kt`, JVM-tested) maps them to Navigation3 keys; `onNewIntent` pushes the
key unless it is already on top. An unknown or malformed route opens `TodayKey`.

| Route name (UX §3.4) | Navigation3 key |
|----------------------|-----------------|
| `fitapp://session/current` | `LiveSessionKey(activeSessionId)`; `TodayKey` if no session is in progress |
| `fitapp://session/current/rest` | `LiveSessionKey(activeSessionId)` with the rest card in focus |
| `fitapp://today` | `TodayKey` |
| `fitapp://habit/<habit_id>` | `HabitSessionKey(habitId)`: the home mini-session screen; the session (and the FGS) starts when the user taps Start there (11.3) |
| `fitapp://challenge/<enrollment_id>/test` | `TestDayWizardKey(enrollmentId)` |
| `fitapp://challenge/<enrollment_id>` | `ChallengeDetailKey(enrollmentId)`: the Challenges dashboard, used by `N-PROGRAM`, `N-SAFETY` and `N-CELEBRATE` (UX §3.4, §18; answers UX OI-32's route request) |
| `fitapp://you/data` | `SyncStatusKey` (You → Data and sync) |

There are no external deep links (no OAuth, no web links), so no intent filters other than the launcher.

### 7.5 Coroutines and threading

| Work | Dispatcher | Notes |
|------|-----------|-------|
| Room queries | Room's query executor (`setQueryCoroutineContext(Dispatchers.IO)`) | DAOs are `suspend` or return `Flow`. |
| Engine calls | `Dispatchers.Default` | CPU-bound; never on Main. |
| Network (supabase-kt/Ktor) | Ktor/OkHttp threads | Called only from workers or repository `suspend` functions. |
| Calendar provider | `Dispatchers.IO` | `ContentResolver` calls block. |
| Alarm/notification calls | Main or any | Cheap system calls. |

Application-level `CoroutineScope(SupervisorJob() + Dispatchers.Default)` in the container for
observers (calendar `ContentObserver` debounce, process-lifecycle triggers). Debug builds enable
`StrictMode` with `penaltyLog()` for disk and network on the main thread.

### 7.6 Engine invocation, snapshots and params pinning

1. `EngineGateway` obtains `now` from `AndroidClock` (one `Instant` + profile zone per call) and passes it
   into the `EngineInput`; `:algo` never reads a clock (C31).
2. Params: bundled defaults (`algo/src/main/resources/fitness/params/…`, C40) merged with
   `user_algo_params` rows pulled from Supabase (those rows are written by a review only after the user
   approved the change list as a batch, F26; keys follow C41). The effective params object and its
   `params_hash` are **pinned when a session starts** (stored on the `workout_sessions` row) and used for
   every engine call during that session; newly pulled params activate at the next session start.
   Platform constants (`PlatformConfig`, section 25) are not params and never enter `params_hash` (C54).
3. After each call the gateway stores one `recommendations` row (C24: module id@version, `ENGINE_VERSION`,
   `params_hash`, `catalog_version`, challenge definition sha256, `inputs_hash` = SHA-256 of the RFC 8785
   JCS form of the inputs, inputs, outputs, trace). If the latest row for the same scope already has the
   same `(module version, params_hash, inputs_hash)`, no new row is written (dedupe as in the data-model
   research S2).
4. The UI shows only the output; the Why sheet reads the trace from the stored row (F19).

### 7.7 Startup sequence (`AppContainer.startup.run()`)

In this order, each step idempotent and guarded by try/catch with a `session_events` record on failure
(`app_startup_failed { step, error_class }`):

1. Install the uncaught-exception handler (writes a local `crash_log` row: timestamp, exception class,
   SHA-256 of the stack trace, first frame; then delegates to the default handler).
2. Create notification channels (section 13.1). Creating an existing channel is a no-op.
3. Rest-timer recovery (section 12.6).
4. Reschedule alarms whose `scheduled_alarm` rows are in the future but whose PendingIntent is missing
   (after force-stop or reboot).
5. Register the calendar `ContentObserver` if READ_CALENDAR is granted (section 16.4).
6. Register the `ProcessLifecycleOwner` observer: on `ON_START` enqueue a sync and a calendar refresh.
7. Enqueue the periodic workers if not yet enqueued (7.8).
8. Platform state snapshots at each cold start (7.10): `permission_state { permission, granted }` per
   runtime permission, `exact_alarm_status { allowed }` (`canScheduleExactAlarms()`) and
   `battery_optimization_status { ignoring }`; plus `platform_config_logged { platform_config_hash,
   values }` when the `PlatformConfig` hash differs from the last logged one (7.10, C54).

### 7.8 Background work inventory

| Name | Mechanism | Trigger / schedule | Constraints | Purpose |
|------|-----------|--------------------|-------------|---------|
| `sync` | WorkManager unique one-time, `ExistingWorkPolicy.KEEP` | Every outbox enqueue (initial delay `sync.debounceS`), app `ON_START`, network regained, manual "Sync now" | `NetworkType.CONNECTED`; exponential backoff (section 9.4) | Push then pull (section 9). |
| `sync-expedited` | WorkManager one-time, `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)` | Session finish | `CONNECTED` | Get the finished session to the server quickly. |
| `sync-periodic` | WorkManager periodic, `ExistingPeriodicWorkPolicy.UPDATE` | Every `sync.periodicH` (6 h), flex 1 h | `CONNECTED` | Catch stragglers; pull params and definitions. |
| `daily-maintenance` | WorkManager periodic 24 h, initial delay to the next 05:00 profile-zone time | Daily | none | Challenge tick synthesis first (`challenge.enrollment.synthesizeTicks` for every active or paused enrollment, so that `week.closed` happens on Monday even when the app is not opened; CHALLENGE_ENGINE 4.6, CE-OI-19; a late or skipped run is acceptable, because catch-up at the next app start, session finish, planning call or job run is idempotent and ordered by boundary `occurred_at`), then calendar refresh and write plan (16), reminder horizon (17), export reminder (20.4), pre-migration backup pruning (8.5), dead-letter summary. |
| Rest end alarm | `AlarmManager.setAlarmClock` | Each rest start / adjust | — | Section 12. |
| Rest overtime reminder | `AlarmManager.setAlarmClock` | When the rest-end alarm fires | — | One reminder buzz (12.4). |
| Habit reminders | `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP)` | Rolling 48 h horizon | — | Section 17. |
| Session idle prompt | `AlarmManager.setAndAllowWhileIdle` (inexact is fine) | `session.idlePromptMin` after the last activity | — | Section 11.5. |

No work is scheduled with `setRepeating`; every alarm is one-shot and rescheduled by the code that owns
it, which keeps the schedule a deterministic function of persisted state.

### 7.9 Error and status surfacing

One `AppStatus` flow aggregates: sync state (9.7), permission/reliability status (15.5), calendar
availability, dead letters, crash-log count since last view. The UI shows at most one status chip (top of
Today) and the full list in You → Settings → Status. Errors never block set logging; logging is a local
transaction that cannot fail on network conditions.

### 7.10 Platform telemetry events (C39, C54)

The canonical event-type vocabulary, including each event's category and payload keys, is the
DATA_MODEL §9.8.1 registry (C39: snake_case `<area>_<event>`, the union of the UX §20 and ANDROID names
after renaming). This document does not own event names or payload keys. The table below lists the
`session_events` types that the platform layer emits, with the registry's category and payload keys
copied exactly (as read for this revision; if the two ever differ, the registry wins). This document
adds only the trigger (the section that emits the event) and value codes where the registry leaves them
open. Payloads hold codes, numbers and ids only, never free text (C24, C39), and a key marked `?` is
omitted when it does not apply; keys that the registry does not list are dropped by the client
(DATA_MODEL §16.3). UI events (session lifecycle, logging, Today, Week, warnings, prompts,
configuration) are emitted by the screens as catalogued in UX §20; `set_logged` in 8.4 is only an
example of the write path.

| event_type | Category | Emitted by | Payload keys (registry) and value codes (this document) |
|------------|----------|------------|--------------|
| `rest_started` | rest | 12.3 `start` | `timer_id`, `set_id`, `planned_s`, `purpose` (`rest`, `between_sides`, `interval` for a timed warm-up item, D33), `duration_source` (`rest_module`, `challenge`, `protocol`), `alarm_mode` (`alarm_clock`, `exact_idle`, `inexact`) |
| `rest_adjusted` | rest | 12.3 `adjust` | `timer_id`, `delta_s`, `new_end_wall_ms`, `capped`, `via` (`button`, `long_press`, `notification`) |
| `rest_skipped` | rest | 12.3 `skip` | `timer_id`, `remaining_ms`, `via` (`button`, `focus_mode`, `notification`) |
| `rest_prewarn` | rest | 12.4 pre-warning (app UI visible only, C72) | `timer_id` |
| `rest_alarm_fired` | rest | 12.3 `onEndAlarm`; fields defined in 12.7 | `timer_id`, `scheduled_end_wall_ms`, `fired_wall_ms`, `latency_ms`, `alarm_mode`, `app_state`, `screen_interactive`, `device_idle`, `power_save`, `battery_opt_exempt`, `standby_bucket`, `fgs_type`, `sdk_int`, `manufacturer`, `build_display`, `platform_config_hash` |
| `rest_alarm_stale` | rest | 12.3 `onEndAlarm` | `timer_id` |
| `rest_alarm_failed` | rest | 12.4 `scheduleEnd` | `timer_id`, `error_class` |
| `rest_overtime_reminder` | rest | 12.3 `onOvertimeAlarm` | `timer_id`, `overtime_ms` |
| `rest_ended` | rest | 12.3 `onNextSetLogged`, `skip`, `sessionFinished` | `timer_id`, `end_reason` (E44, including `session_finished`), `planned_s`, `adjust_s`, `actual_s`, `rest_source` (E56), `prev_completed_at`, `rest_end_at?`, `first_touch_at?`, `skip_at?`, `completed_at?` |
| `rest_recovered` | rest | 12.6 | `timer_id`, `missed_by_ms?` |
| `rest_tone_skipped` | rest | 14.2 | `timer_id?`, `reason` (`focus_denied`, `silent_mode`, `setting_off`) |
| `session_idle_prompted` | lifecycle | 11.5 | `idle_min`, `action` (`posted`, `finish`, `keep_open`) |
| `fgs_started` | system | 11.2, 11.3 | `fgs_type` (`health`, `special_use`; `none` below API 34, where services are untyped), `sdk_int` |
| `fgs_stopped` | system | 11.3, 11.5 | `reason` (`finished`, `abandoned`, `idle_timeout`, `no_active_session`) |
| `fgs_restart_failed` | system | 11.3 | `error_class` |
| `notification_posted`, `notification_opened`, `notification_dismissed` | system | 11.5, 12.4, 13, 17 | `notification_id` (UX §18 catalogue id, for example `N-SESSION`), `channel` (13.1 channel id), `latency_ms?` (scheduled reminders: delivery time minus due time), `platform_config_hash?` (reminder deliveries) |
| `notification_action` | system | 11.5, 12.5, 17 | `notification_id`, `channel`, `action` (`minus_15`, `plus_15`, `skip`, `start`, `snooze`, `skip_today`, `finish`, `keep_open`, `weigh_in`, `export`, `open`) |
| `notification_promotion_state` | system | 13.4 | `can_post_promoted`, `flag_promoted` |
| `reminder_scheduled` | system | 17 | `kind` (`habit`, `weigh_in`, `test_day`), `count`, `horizon_h` |
| `permission_state` | system | 7.7 step 8 | `permission`, `granted` |
| `exact_alarm_status`, `battery_optimization_status` | system | 7.7 step 8, 15.4 | `allowed` (`exact_alarm_status`) / `ignoring` (`battery_optimization_status`) |
| `oem_settings_opened`, `oem_settings_confirmed` | system | 15.3 | `maker`, `intent_ok?` |
| `timer_selftest` | system | 15.4 step 7 | `latency_ms`, `felt`, `screen_off`, `alarm_mode`, `passed`, `platform_config_hash` |
| `platform_config_logged` | config | 7.7 step 8, 25 | `platform_config_hash` (SHA-256 of the JCS form), `values` (every section-25 key and its value) |
| `app_startup_failed` | system | 7.7 | `step`, `error_class` |
| `app_crash` | system | 7.7 step 1 (summarised at the next start from `crash_log`, 8.3) | `exception_class`, `stack_sha256`, `top_frame`, `crashed_at`, `crash_app_version` |
| `calendar_classified` | calendar | 16.4 (C20) | `instance_key`, `kind`, `slot?`, `type?`, `score`, `features`, `classifier_version`, `method` (E23) |
| `calendar_event_written` | calendar | 16.7, 16.8 (executed ops only) | `op` (`insert`, `update`, `delete`), `kind` (`challenge`, `general`), `instance_key?`, `marker_key` (the C37 slot key), `cause` (UX §20.7 codes), `provider_rows` |
| `calendar_event_adopted` | calendar | 16.7 guard 5 | `slot`, `instance_key` |
| `calendar_write_failed` | calendar | 16.7 (refused or failed ops) | `op`, `error_class` (`marker_missing`, `week_guard`, `target_guard`, `delete_guard`, `sandbox`, `provider_error`, `security_exception`) |
| `calendar_unavailable` | calendar | 16.2 | `reason` (`permission`, `provider_error`) |
| `sync_run` | sync | 9.8 | `trigger`, `pushed`, `pulled` (row totals), `pushed_by_table`, `pulled_by_table`, `duration_ms`, `outbox_before`, `outbox_after`, `oldest_outbox_age_s`, `errors` (counts by error class), `platform_config_hash` |
| `sync_conflict` | sync | 9.6, 9.8 | `table`, `row_id`, `local_tuple`, `server_tuple`, `winner` |
| `sync_error` | sync | 9.5, 9.8 | `table`, `row_id?`, `rows?`, `error_class`, `http_status?`, `pg_code?`, `pgrst_code?`, `attempts` |
| `sync_dead_letter` | sync | 9.5, 9.8 | `table`, `row_id`, `http_status?`, `pg_code?`, `pgrst_code?`, `attempts` |
| `export_completed` | export | 20.4 | `export_id`, `kind` (`monthly_full`, `manual_full`, `review_bundle`), `rows`, `bytes`, `from?`, `to?`, `notes_included?` |

**Platform constants in payloads (C54).** A platform constant that shaped an event is logged in that
event's payload (registry rule): `alarm_mode`, `delta_s` and `fgs_type` carry the values directly, and
`platform_config_logged` records the full `PlatformConfig` set (section 25) whenever it changes, while
`rest_alarm_fired`, `sync_run`, `timer_selftest` and reminder `notification_posted` events carry its
`platform_config_hash`, so a review can join every platform event to the constants that produced it.
Every row also carries the envelope column `app_version` (DATA_MODEL §6.2), which identifies the
compiled `PlatformConfig`.

---

## 8. Local persistence (Room)

### 8.1 Database

This section owns the Room schema: local table names, column representations (including timestamps) and
the outbox payload policy (C40). DATA_MODEL §12 summarises it and references it; where the two differ,
this section applies.

- `FitnessDatabase` in `:data`, file `fitness.db`, WAL journal (Room default), foreign keys on.
- Version 1 is created at M1 with the tables defined by the data-model specification (synced tables
  mirror the server schema `fitness`; column names identical so DTO mapping is mechanical) plus the
  local-only tables below.
- KSP code generation, Kotlin codegen (Room default with KSP), `room { schemaDirectory("$projectDir/schemas") }`;
  the exported JSON for every version is committed.

### 8.2 Column conventions

| Concept | Room representation | Reason |
|---------|---------------------|--------|
| ids | `TEXT` UUIDv7 (client-generated, `UuidV7Generator` in `:data`: 48-bit ms timestamp, version 7, 74 random bits from `SecureRandom`, monotonic counter within the same ms) | PG17 has no `uuidv7()`; ids sort by creation time. |
| wall timestamps (every `timestamptz` column except `server_updated_at`, and every local `*_ms` column) | `INTEGER` epoch ms (UTC) | Exact, zone-free. Client-written values are millisecond-precise. A server-written value with microseconds (for example `client_updated_at = now()` of a review write) is truncated toward the past on pull; the only effect is that the next pull sees the remote tuple as newer and overwrites the local copy again, which is idempotent (9.6). DTOs write ISO 8601 UTC with milliseconds. `client_updated_at` is made strictly increasing per device with the `device.last_client_updated_at_ms` guard (8.3; DATA_MODEL §11.12). |
| `server_updated_at` | `TEXT` exactly as returned by PostgREST (microsecond precision) | Avoids precision loss in the pull cursor (the cursor must keep microseconds; DATA_MODEL open issue 6). |
| local dates | `TEXT` ISO `yyyy-MM-dd` in the profile zone | Matches `LocalDate` and SQL `date`. |
| every `numeric(p, s)` column | `INTEGER` scaled by 10^s: kg × 100 (C22), seconds and RIR × 10, ratio × 10 000 (C22), reps-valued numerics × 100 (REP grid, C42), metric values × 1000 | Values are exact on the canonical grid; no `REAL` columns for stored quantities. DTOs convert to JSON decimals with `BigDecimal.valueOf(value, scale)` (a `Long` overload, exact). |
| statistical state (STAT grid, 12 significant digits, C42) | inside `TEXT` JSON only (for example `exercise_model_state.state`), numbers written by `Canon` (ALGORITHMS.md A3.4) | A relative grid cannot be a fixed-scale integer; it never needs its own column. |
| enums/states | `TEXT` with the same codes as the server's CHECK constraints (DATA_MODEL §8 value domains are canonical, C38); local-only codes in this document are lower snake_case too | One vocabulary. |
| JSON payloads | `TEXT` (canonical JSON) | `session_events.payload`, recommendation inputs/outputs/trace. |

### 8.3 Local-only tables

| Table | Columns (essential) | Purpose |
|-------|---------------------|---------|
| `outbox` | `seq INTEGER PK AUTOINCREMENT`, `table_name`, `row_id`, `op` (`upsert` \| `insert_ignore`), `gen INTEGER`, `enqueued_at_ms`, `attempts`, `next_attempt_at_ms`, `last_error`; `UNIQUE(table_name, row_id)` | Pending pushes. **Payload policy (C40): no payload copy; the payload is read from the entity at push time**, which coalesces many edits into one push. Enqueue = `INSERT OR IGNORE` followed by `UPDATE outbox SET gen = gen + 1, next_attempt_at_ms = min(next_attempt_at_ms, now)` for that row, in the same transaction (plain SQL, because SQLite's `ON CONFLICT … DO UPDATE` needs SQLite 3.24, newer than API 26). A successful push deletes only rows whose `(seq, gen)` still equal the values read when the batch was loaded, so an edit made while the batch was in flight is pushed in the next loop instead of being lost. |
| `sync_cursor` | `table_name PK`, `server_updated_at TEXT`, `last_id TEXT`, `updated_at_ms` | Pull cursor per table. |
| `dead_letter` | `id PK`, `table_name`, `row_id`, `http_status`, `error_code`, `message`, `payload_json`, `first_failed_at_ms`, `attempts` | Rows the server rejected permanently (9.5). |
| `rest_timer` | see 12.2 | Active and last rest timer per session. |
| `calendar_link` | see 16.6 | Mapping between app-written calendar events and slots. Rebuildable from the markers. |
| `calendar_instance` | `instance_key PK` (DATA_MODEL §9.7.3 definition), `series_key`, `calendar_identity_sha256`, `calendar_id`, `event_id`, `begin_ms`, `end_ms`, `title`, `description`, `location`, `all_day`, `status`, `declined`, `classified_kind` (incl. `busy`), `slot`, `method`, `score`, `match_milli`, `gym_type`, `attention`, `needs_confirm`, `features_json`, `last_seen_ms` (Room v6, M2b.5) | Full local cache of the read window, including busy and ignored events, which are never uploaded (16.4); the description stays on the phone for the event sheet and the marker scan. Rows older than 60 days are purged. |
| `scheduled_alarm` | `request_code PK`, `kind`, `ref_id`, `trigger_at_wall_ms`, `mode`, `created_at_ms` | Every alarm the app has scheduled (rest, overtime, reminders, idle prompt), for rescheduling after boot, force-stop or update. |
| `params_cache` | `params_version PK`, `params_json`, `params_hash`, `source` (`bundled`, `server`), `fetched_at_ms` | Effective-params fallback before the first pull and cache of pulled params (7.6). |
| `warning_dismissal` | `rule_id`, `entity_key`, `dismissed_at_ms`, `magnitude_at_dismiss`, `use_mine_anyway`; PK `(rule_id, entity_key)` | UI-10 dismissal store; rebuildable from `session_events`. |
| `prompt_state` | `prompt PK`, `skip_streak`, `reduced_every`, `last_shown_session_id` | UI-07 adaptive prompt state; rebuildable from `session_events`. Not created in M4.2: the app folds the `prompt_*` events each time (they are few). |
| `exercise_fts` | FTS4 over `name_en`, `name_nl`, `aliases` | Catalog search (D12: English and Dutch names). |
| `device` | `id = 1`, `device_id TEXT`, `created_at_ms`, `last_client_updated_at_ms` | Random UUIDv4 created at first launch; LWW tie-break (C23). `last_client_updated_at_ms` makes `client_updated_at` strictly increasing on this device (`max(now, last + 1)`). |
| `crash_log` | `id`, `at_ms`, `exception_class`, `stack_sha256`, `top_frame`, `app_version` | Local crash record; summarised into a `session_events` row (`app_crash`, with `at_ms` as `crashed_at` and `app_version` as `crash_app_version`, 7.10) on next start. |

No separate local tables are needed for the active-session pin (the pin is stored on the
`workout_sessions` row, 7.6), the sync log (`sync_run` events, 9.8), habit reminders (`scheduled_alarm`
rows of kind `reminder`, 17) or a calendar write queue (a write plan is applied in one `applyBatch`,
16.7). Local-only table names carry no `local_` prefix; none of them collides with a `fitness` table
name.

### 8.4 Write path

Every domain write is one transaction:

```kotlin
suspend fun logSet(cmd: LogSetCommand) = db.withTransaction {
    val row = cmd.toEntity(id = ids.next(), clientUpdatedAt = device.nextClientUpdatedAtMs(clock), deviceId = device.id)
    setLogDao.upsert(row)
    sessionEventDao.insert(event("set_logged", row))         // append-only (C24); names per 7.10 / DATA_MODEL 9.8.1
    outbox.enqueue("set_logs", row.id, Op.UPSERT)             // stored as 'upsert'; INSERT OR IGNORE + gen bump (8.3)
    outbox.enqueue("session_events", eventId, Op.INSERT_IGNORE)
}   // after commit: syncScheduler.request()  (debounced, 9.3)
```

Soft delete is an update that sets `deleted_at`; there is no hard delete of synced rows.

### 8.5 Migrations

- Every schema change bumps the version, commits the new schema JSON, and ships either an
  `@AutoMigration(from, to, spec)` or a hand-written `Migration`.
- `fallbackToDestructiveMigration*` is never called in any build type (a destructive migration wipes the
  only authoritative copy of unsynced data). A unit test asserts that the database builder used in
  production code has no destructive fallback (by reading the builder configuration through a factory
  function that tests can inspect).
- Pre-migration backup: before Room opens the file, `FitnessDatabase.open()` reads `PRAGMA user_version`
  with a plain read-only `SQLiteDatabase`; if it is lower than the compiled version, it copies
  `fitness.db` (+ `-wal`, `-shm`) to `files/db-backups/fitness-v<old>-<epochMs>.db`. The newest
  `db.preMigrationBackupsKept` (3) copies are kept.
- Tests: `FitnessDatabaseMigrationTest` uses `MigrationTestHelper` under Robolectric and migrates every
  historical version to the latest, checking data survives (one fixture row per table).

---

## 9. Outbox sync (C23)

### 9.1 Principles

- Room is authoritative on the phone; the server is a converging copy that also feeds reviews (F4).
- Mutable tables: plain PostgREST upsert (`Prefer: resolution=merge-duplicates`, `on_conflict=id`). The
  server's `BEFORE UPDATE` trigger silently drops a row whose `client_updated_at` is older than the stored
  one (tie-break `device_id`), so a stale push can never overwrite newer data (C23).
- Append-only tables (`session_events`, `recommendations`, `challenge_events`,
  `challenge_rule_events`, and any other insert-only table of the data model): upsert with
  `ignoreDuplicates = true` (`Prefer: resolution=ignore-duplicates`), which makes retries idempotent.
- Pull: per table, rows with `server_updated_at >= cursor − 120 s`, ordered by `(server_updated_at, id)`,
  page size `sync.pullPageRows` (500, below PostgREST `max_rows` 1000). The 120 s overlap is canonical
  (C23) and covers commit-order skew of `now()`.

### 9.2 Push algorithm

```
push():
  for table in DEPENDENCY_ORDER:                        # parents before children (order owned by the data-model spec)
    loop:
      batch = outbox rows for table, ordered by seq, limit sync.pushBatchRows (200),
              where next_attempt_at_ms <= now
      if batch empty: break
      rows = load entities for batch.row_id (skip ids whose entity no longer exists → delete outbox row)
      result = postgrest.from(table).upsert(rows.toDto()) {
                 onConflict = "id"; ignoreDuplicates = (op == insert_ignore) }
      on success: delete the batch's outbox rows whose (seq, gen) are unchanged (8.3);
                  rows re-enqueued during the request stay and are pushed in the next loop
      on error:   classify (9.5); for row-level permanent errors bisect the batch
                  (split in halves, retry each) to isolate failing rows deterministically
```

Supabase's `authenticator` role preloads `safeupdate` (UPDATE/DELETE without WHERE rejected); upserts are
INSERT … ON CONFLICT and are not affected. Any PATCH/DELETE the app ever issues carries an `eq("id", …)`
filter.

### 9.3 Triggers

| Trigger | Action |
|---------|--------|
| Any committed local write that enqueued outbox rows | `SyncScheduler.request()`: enqueue unique work `sync` with initial delay `sync.debounceS` (3 s), policy `KEEP`; while a run is active it keeps draining the outbox until empty, so later writes are picked up in the same run. |
| Session finished | Enqueue `sync-expedited`. |
| App comes to foreground (`ON_START`) | `SyncScheduler.request()` with zero delay. |
| Network becomes available | Implicit: pending work has a `CONNECTED` constraint. |
| Periodic | `sync-periodic` every 6 h. |
| User taps "Sync now" | Zero-delay request. |

Events written by the sync worker itself (`sync_run`, `sync_error`) are enqueued with `triggerSync =
false` and only when the run pushed or pulled something or failed. Otherwise every sync would create a
new event that requires another sync.

### 9.4 Backoff and retry

- The sync-backoff constants are platform constants owned by this document (C54, section 25.4).
- WorkManager `BackoffPolicy.EXPONENTIAL`, initial `sync.backoffInitialS` (30 s; WorkManager's minimum is
  10 s), capped by WorkManager at 5 h. The worker returns `Result.retry()` for transient failures and
  `Result.success()` otherwise (permanent errors are dead-lettered, so the periodic chain never fails).
- Per-row `attempts` and `next_attempt_at_ms` in the outbox handle FK-order problems (9.5) independently
  of the worker's backoff.

### 9.5 Error classification

| Condition | Class | Handling |
|-----------|-------|----------|
| IO exception, timeout, DNS failure | transient | `retry` |
| HTTP 5xx, 408, 429, `57014` (statement timeout), project paused/unreachable | transient | `retry` (for 429 not before the `Retry-After` delay when the server sends one); keep table order, so children of a failed parent table are not pushed in this run; after `sync.unreachableBannerH` (24 h) of consecutive failures show "Server unreachable, the Supabase project may be paused" (F4). |
| HTTP 401 | auth | supabase-kt refreshes the session; retry once; if the refresh token is invalid → state `needs_signin`, stop pushing, keep outbox. |
| PostgREST `PGRST106` (schema not exposed) | config | Stop sync; status "fitness schema not exposed (M0 step, F2)"; retry at each app start. |
| PostgREST `PGRST204`/`42703` (unknown column), `PGRST205` (unknown table) | schema drift | Stop sync for that table and every later table that depends on it; status "server schema older than app"; retry every `sync.schemaDriftRetryMin` (60 min). |
| `42501` permission denied, and the access check says the user is not allowed | access | Stop all sync; state `not_allowed` (10.4). |
| `42501` while the user is allowed | grant/RLS bug | Dead-letter the rows of that table, continue with other tables, surface in Sync status. |
| `23503` foreign-key violation | ordering | Keep the row, `attempts += 1`, `next_attempt_at = now + 2^attempts min`; dead-letter after `sync.fkRetryAttempts` (5). |
| `23505`, `23514`, `23502`, `22xxx` (unique, check, not-null, data exceptions such as `22P02`, `22003`) | permanent row error | Bisect, dead-letter the failing rows, push the rest. |
| `25006` (`read_only_sql_transaction`: the Free-plan database is above 500 MB and read-only) | server read-only | Behaviour owned by DATA_MODEL §15.2: keep every outbox row, stop pushing, keep pulling, status "Server storage is full (read-only). Your data is safe on this phone.", retry pushes every `sync.periodicH` (6 h). Nothing is dead-lettered. |
| HTTP 413 (payload too large) | batch size | Halve the batch size for this table for the rest of the run and retry (DATA_MODEL §11.8). |

Dead letters are shown in You → Settings → Sync status with "Retry" (moves them back to the outbox) and
"Export" (JSON). They are never deleted automatically.

### 9.6 Pull and merge

```
pull(table):
  since = cursor[table].server_updated_at − 120 s   (null cursor → full pull)
  repeat:
    page = select * from table where server_updated_at >= since
           order by server_updated_at asc, id asc limit 500
    for r in page:
      local = find(r.id)
      if local == null: insert r
      elif outbox has a pending entry for r.id:
         if (r.client_updated_at, r.device_id) > (local.client_updated_at, local.device_id): overwrite local, drop outbox entry
         else: keep local (it will be pushed; the server trigger keeps the newer one)
      elif (r.client_updated_at, r.device_id) >= (local.client_updated_at, local.device_id): overwrite local
      # else: local is newer and already pushed; ignore
    cursor[table] = (last row's server_updated_at, id)
  until page.size < 500
```

Tuple comparison is lexicographic: first `client_updated_at` (ms), then `device_id` compared as its
lower-case canonical text, which orders exactly like PostgreSQL's `uuid` comparison; the greater tuple
wins. The server trigger `fitness_private.tg_sync_row()` (DATA_MODEL §7.1) implements the identical rule
(it drops an incoming row whose `(client_updated_at, device_id)` is not greater than the stored one).
Which tables are pulled, and with which window, is owned by DATA_MODEL §11.6 (the sync protocol is
DATA_MODEL's, C23); the pull loop above applies it per table with one cursor each: mutable tables, global
reference tables (catalog, reason codes, algorithm versions, challenge definitions, `user_algo_params`)
and the append-only `challenge_events`, `challenge_rule_events`, `params_snapshots` and `exports` are
pulled incrementally (the challenge tables are needed for multi-device convergence); `recommendations`
are pulled for the last 90 days and `session_events` only for the cache-rebuilding types of §11.6 over
the last 365 days; a new-device bootstrap or a user-triggered "Restore from server" pulls the same scope
with a null cursor.

### 9.7 Sync status model

`Synced(at)` · `Pending(n)` · `Offline` · `Unreachable(since)` · `NeedsSignIn` · `NotAllowed` ·
`SchemaNotExposed` · `SchemaDrift(table)` · `DeadLetters(n)`. Shown as a small chip; the user can always
keep training.

### 9.8 Logging

`session_events` rows of type `sync_run`, `sync_conflict`, `sync_error` and `sync_dead_letter` with
exactly the payload keys of the DATA_MODEL §9.8.1 registry (C39; listed in 7.10): `sync_run` carries row
totals (`pushed`, `pulled`), per-table counts (`pushed_by_table`, `pulled_by_table`), `duration_ms`,
`outbox_before`, `outbox_after`, `oldest_outbox_age_s`, `errors` (counts by error class) and the
`platform_config_hash` of the backoff constants in force (C54);
`sync_error` carries the error class of 9.5 and the HTTP / Postgres / PostgREST codes; `sync_dead_letter`
is written once per row moved to `dead_letter`. Payloads never contain row contents. These events tune
the constants in 25.4 and detect a paused project, grant mistakes and clock skew (`client_updated_at`
vs `server_updated_at`).

---

## 10. Supabase client and authentication

### 10.1 Client configuration

```kotlin
object SupabaseFactory {
    fun create(app: Application): SupabaseClient = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,                 // https://nuvjdvpqibqeyafzgaye.supabase.co
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,     // sb_publishable_…, never a secret/service_role key
    ) {
        defaultSerializer = KotlinXSerializer(FitnessJson)       // explicitNulls=false, ignoreUnknownKeys=true
        install(Auth) {
            alwaysAutoRefresh = true                             // default
            autoLoadFromStorage = true                           // default
            sessionManager = KeystoreSessionManager(app)         // 10.3
            // no flowType/PKCE, no deeplink scheme: email+password has no redirect
        }
        install(Postgrest) {
            defaultSchema = "fitness"                            // F1
            propertyConversionMethod = PropertyConversionMethod.SERIAL_NAME   // DTOs carry @SerialName("snake_case")
        }
        // HTTP engine: OkHttp; request timeout 30 s, connect timeout 15 s (verify property names in supabase-kt 3.x)
    }
}
```

- Only `auth-kt` and `postgrest-kt` are used. No realtime, storage, functions or compose-auth modules.
- The project URL and publishable key reach `BuildConfig` from `local.properties` (`supabase.url`,
  `supabase.publishableKey`) or, in CI, from repository secrets. The publishable key is a public client key
  by design (RLS protects the data); it is still kept out of git for hygiene and so the repository can be
  made public later without key rotation questions. Claude reads it once through the Supabase MCP
  `get_publishable_keys` tool and writes it to `local.properties` without printing it in chat.
- DTOs: hand-written `@Serializable` classes in `:data/remote/dto`. Supabase has no Kotlin type
  generator; `DtoSchemaDriftTest` parses `supabase/snapshots/fitness.types.ts` (generated through the
  Supabase MCP `generate_typescript_types` after each migration) and fails if a DTO's serial names differ
  from the table's columns.

### 10.2 Sign-in flow

1. First launch shows "Sign in" (email + password). There is no sign-up, magic link, OTP or social login:
   the user already has an account in the shared project (D3), and magic links would need deep links and
   the rate-limited shared SMTP.
2. `supabase.auth.signInWith(Email) { email = …; password = … }`. The password is never stored by the app.
3. Access check (10.4). If allowed: create the local `device` row if missing, run a full bootstrap pull,
   upsert the `profiles` row lazily (no trigger on `auth.users`, which belongs to the chess app), then open
   onboarding or Today.
4. The first sign-in requires network. After that the app works offline indefinitely; access tokens are
   refreshed whenever the network returns.
5. "Forgot password" is not implemented in v1 (the shared project's redirect URLs belong to the chess
   app); the screen tells the user to reset the password through the Supabase dashboard or the chess app.

### 10.3 Session persistence

supabase-kt's default Android session manager stores the session in app-private SharedPreferences,
unencrypted. This app uses `KeystoreSessionManager`: the session JSON is encrypted with AES-256-GCM using
a non-exportable key in the Android Keystore (alias `fitness_session_v1`, no user-authentication
requirement) and stored in a DataStore file `session.preferences_pb`. It never leaves the device: Android
Auto Backup and device-to-device transfer are off for the whole app (D16, F21, 20.3). If decryption fails
(key invalidated, file copied from elsewhere), the session is discarded and the user signs in again; local
data is untouched.

### 10.4 Allowlist rejection

The allowlist lives server-side in `fitness_private` (D3, F1) and is enforced by RLS. The `fitness` schema
has no RPC functions by design (DATA_MODEL §5.4, §15.2), so the access check is a read probe: after each
sign-in and at every bootstrap pull the app requests `muscle_groups?select=id&limit=1` (a global reference
table seeded by migration and readable by every allowlisted user). One row means allowed; an empty result
means not allowlisted, because RLS returns `200 []` for every table to a non-allowlisted user (DATA_MODEL
§5.4). `PGRST106` on the probe means the schema is not exposed yet (9.5), not a rejection.

| Result | App behaviour |
|--------|---------------|
| allowed | Continue. |
| not allowed | Screen: "This account is not enabled for the fitness app. The owner can add it to the allowlist." Sign out, delete the stored session, write nothing locally. |
| network error | Retry with backoff; the user stays on the sign-in screen with an offline message. |

A `42501` during sync after a successful sign-in triggers the access check again before anything is
dead-lettered (the allowlist row may have been removed).

### 10.5 Sign-out and account switching

- Sign-out is refused while the outbox is not empty; the dialog shows the number of pending rows and
  offers "Sync now" and "Export data".
- Signing in as a different user than the owner of the local database is refused unless the user chooses
  "Erase local data", which is only offered when the outbox is empty or right after an export.

---

## 11. Live session foreground service

### 11.1 Manifest

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_HEALTH" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />

<service
    android:name="dev.fitnessapp.app.session.SessionService"
    android:exported="false"
    android:foregroundServiceType="health|specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Active strength-training session: keeps the rest timer, session notification and set logging alive while the screen is off." />
</service>
```

### 11.2 Type selection (C28)

Facts (engineering-fact, FGS types page, verified 2026-10-07): type `health` requires
`FOREGROUND_SERVICE_HEALTH` in the manifest plus at least one of: the `HIGH_SAMPLING_RATE_SENSORS`
manifest permission, or a granted runtime permission among `BODY_SENSORS` (API ≤ 35), `READ_HEART_RATE`,
`READ_SKIN_TEMPERATURE`, `READ_OXYGEN_SATURATION`, `ACTIVITY_RECOGNITION`. Its documented use case is
"long-running use cases to support apps in the fitness category such as exercise trackers". `specialUse`
has no runtime prerequisite. `shortService` times out after about 3 minutes and is unusable for 3–5 min
OAP rests. Typed FGS (`health`, `specialUse`) exist from API 34; on API 29–33 types are not required for
this use.

```kotlin
fun fgsTypeFor(sdk: Int, activityRecognitionGranted: Boolean): Int? = when {
    sdk < 34 -> null                                           // startForeground(id, notification) without type
    activityRecognitionGranted -> ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE      // fallback (C28)
}
```

ACTIVITY_RECOGNITION is requested only on API ≥ 34 (the only place it matters). The app reads no activity
data; the permission rationale says so. The chosen type is logged (`fgs_started { fgs_type, sdk_int }`,
7.10; `none` below API 34).

### 11.3 Start and stop rules

- **Start only from a user action**: "Start session" (any template: challenge slot, family combination,
  empty, home habit session; C34, C35) calls `SessionController.start()` while `MainActivity` is resumed,
  which calls `ContextCompat.startForegroundService()`. `onStartCommand` calls
  `ServiceCompat.startForeground()` immediately with the session notification (section 13.3).
- The service is never started from WorkManager, boot, calendar changes or reminders.
- Stop on Finish or Abandon: cancel rest alarms, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf()`,
  record `fgs_stopped { reason }`.
- `onStartCommand` returns `START_STICKY`. If the system recreates the service with a null intent, it reads
  the active session from Room: no active session → `stopSelf()`; active session → try `startForeground`
  inside try/catch (`ForegroundServiceStartNotAllowedException` on API 31+). On failure it posts a
  normal notification "Session still open. Tap to resume" (channel `session_ongoing`), records
  `fgs_restart_failed`, and stops. Opening the app (a user action) restarts the service.
- When the rest alarm fires after the process died (12.6), the alarm receiver may restart the service:
  exact-alarm deliveries are exempt from background FGS start restrictions on Android 12+, and
  ACTIVITY_RECOGNITION is not a while-in-use permission. If the start still fails, the receiver only
  vibrates and posts the overtime session notification as a plain notification (no service).

### 11.4 What the service does

It holds no wake lock and runs no loop. It exists so that the process keeps foreground priority during a
session (OEM killers spare foreground services far more often than background processes), owns the
ongoing notification, and gives the app while-in-use capability for audio cues (section 14.2). All timing
is done by `AlarmManager` and by the system-rendered chronometer.

### 11.5 Idle protection

If no set, rest or exercise event happens for `session.idlePromptMin` (60 min), an inexact alarm posts
"Session C still running. Finish it?" (`N-IDLE`, notification id 3003, channel `app_status`) with
actions Finish / Keep open, logged as `session_idle_prompted { idle_min, action: posted }` when posted
and again with `action` = `finish` or `keep_open` when the user answers (registry name, 7.10). After
`session.idleStopFgsMin` (240 min) without activity the service stops itself (`fgs_stopped { reason:
idle_timeout }`); the session row stays `in_progress` and the user can resume or finish it from Today. Both thresholds are assumptions, tuned from the distribution of gaps
between consecutive `session_events` in real sessions.

---

## 12. Rest timer (C28)

### 12.1 Responsibilities

- The **duration** comes from REST-1 (C18: 180/150/120/90/60/45–60 s table with modifiers, clamp 60–300 s,
  challenge rest values override, timer hard max 900 s; 60 s between superset members, C56) through the
  engine output for the set, or from the exercise's/challenge's between-sides rest for side A of a
  unilateral pair. The Android layer never computes a prescribed rest.
- The **Android layer** owns: persistence, alarm scheduling, notifications, alerts, recovery and logging.
  This section is authoritative for that platform behaviour and its constants (C54, section 25.1); UX §19.8
  specifies the visible rest card and references this section.
- The **state machine** is a pure reducer (`RestTimerReducer`, no Android imports, unit-tested on the JVM);
  `RestTimerController` applies its effects (Room write, alarm, notification, haptics, events).

### 12.2 Persisted state (`rest_timer` table, local-only)

| Column | Meaning |
|--------|---------|
| `id` | UUIDv7 |
| `session_id`, `set_log_id`, `exercise_id`, `side` | What the rest follows. |
| `purpose` | `rest`, `between_sides` or `interval` (the `rest_started.purpose` codes; `interval` is the countdown of a timed warm-up item such as the OAP row, D33). |
| `duration_source` | `rest_module`, `challenge` or `protocol`: where the engine output says the duration came from (REST-1, a challenge rest override, a test-protocol `rest_s`). |
| `prescribed_s` | Duration from REST-1/challenge after clamping (12.3). |
| `start_wall_ms`, `start_elapsed_ms`, `boot_count` | Start instant in both clocks; `boot_count` = `Settings.Global.BOOT_COUNT`. |
| `end_wall_ms` | Current planned end (changes with adjustments). |
| `adjustments_json` | `[{ "at_wall_ms": …, "delta_s": ±15 or ±60, "via": "button" }]` |
| `state` | `running`, `finished`, `overtime`, `ended` |
| `ended_by` | `elapsed`, `skipped`, `next_set_early`, `elapsed_while_dead`, `session_finished` (DATA_MODEL E44 codes, C38; `session_finished` appears only in the `rest_ended` event, never in `set_logs`, 12.3), or null |
| `alarm_mode` | `alarm_clock`, `exact_idle` (the `profiles.rest_alarm_mode` codes) or the fallback `inexact` |
| `fired_wall_ms`, `latency_ms` | When the end alarm was delivered and its lateness. |
| `overtime_reminder_sent` | boolean |
| `session_exercise_id`, `set_index`, `label` | Interval only (Room v5): the warm-up item it logs when it ends and the name the notification shows; null for a rest. |

State is timestamps only; there is never a decrementing counter, so process death loses nothing.

### 12.3 Reducer

```
start(setLoggedAtWall, nowElapsed, bootCount, d_s, hardMaxS, purpose, durationSource):
  if d_s < timer.minTimerS (10): return NoTimer            # e.g. between-sides rest of 0 s (UX shows a switch hint)
  d = min(d_s, hardMaxS)                                    # hardMaxS = REST-1 timer hard max (900 s, C18)
  timer = running, start_wall = setLoggedAtWall, start_elapsed = nowElapsed, boot = bootCount,
          end_wall = setLoggedAtWall + d*1000, prescribed_s = d, purpose, duration_source = durationSource
  effects: persist; scheduleEnd(end_wall); postSessionNotification(rest mode);
           event rest_started{timer_id, set_id, planned_s = d, purpose, duration_source, alarm_mode}

adjust(deltaS, via, nowWall):                               # only while running
  require deltaS ≠ 0 and deltaS is a multiple of timer.adjustStepS (15 s) and |deltaS| ≤ timer.longPressStepS (60 s)
                                                            # taps and notification actions send ±15, a long-press repeat ±60 (UX 19.8)
  newEnd = timer.end_wall + deltaS*1000
  newEnd = max(newEnd, nowWall + timer.minLeftAfterAdjustS*1000)        # 5 s
  newEnd = min(newEnd, timer.start_wall + hardMaxS*1000)               # 900 s cap from the start
  append adjustment; end_wall = newEnd
  effects: persist; rescheduleEnd(newEnd); repost notification;
           event rest_adjusted{delta_s, new_end_wall_ms, capped, via}

skip(nowWall, via):                                         # running, finished or overtime
  state = ended, ended_by = skipped (only if it was running; otherwise ended_by stays elapsed)
  effects: cancel end + overtime alarms; notification back to session mode; event rest_skipped{remaining_ms, via}

onEndAlarm(timerId, firedWall):
  if timerId != active timer id or state != running: event rest_alarm_stale; return
  state = finished → overtime; ended_by = elapsed; fired_wall = firedWall; latency = firedWall − end_wall
  effects: alert (12.4); schedule overtime reminder at end_wall + overtimeReminderS*1000;
           notification switches to overtime mode with count-up from end_wall; event rest_alarm_fired{latency_ms, …}

onOvertimeAlarm(timerId): if still overtime and not sent: short buzz; overtime_reminder_sent = true;
                          event rest_overtime_reminder{overtime_ms}

onNextSetLogged(nextCompletedAtWall, firstTouchAfterRestWall?, skipTapWall?, holdStartWall?, estNextSetDurationS):
  if state == running: ended_by = next_set_early
  state = ended; cancel alarms
  setStart = setStartEstimate(prevCompletedAt = timer.start_wall, firstTouchAfterRestWall, skipTapWall,
                              nextCompletedAtWall, estNextSetDurationS, holdStartWall)       # UX 19.8 formula, adopted here
  rest_before_s = round1((setStart − timer.start_wall) / 1000)    # rest_source = measured for holds, else estimated
  set_logs(next) rest columns, named exactly as in DATA_MODEL §9.6.4 (canonical, C55):
      rest_timer_planned_s = prescribed_s,
      rest_adjust_s = Σ adjustments.delta_s,
      rest_timer_overridden = (adjustments not empty) or ended_by == skipped,   # a pair netting to 0 still counts
      rest_end_reason = ended_by (E44), rest_before_s, rest_source (E56)
  event rest_ended{timer_id, end_reason = ended_by, planned_s = prescribed_s, adjust_s, actual_s = rest_before_s,
                   rest_source, prev_completed_at = start_wall, rest_end_at?, first_touch_at?, skip_at?, completed_at}

sessionFinished(nowWall):                                   # only if the timer is not already ended
  state = ended, ended_by = session_finished (E44); cancel alarms
  event rest_ended{end_reason = session_finished, planned_s, adjust_s,
                   actual_s = round1((nowWall − start_wall) / 1000), rest_source = measured,
                   prev_completed_at = start_wall, rest_end_at? (if the rest had already elapsed)}
  # nothing is copied to set_logs: there is no next set (E44 note)

remainingMs(nowWall, nowElapsed, bootCount):
  if boot_count == bootCount: start_elapsed + (end_wall − start_wall) − nowElapsed     # immune to wall-clock changes
  else: end_wall − nowWall
```

**Interval timers (`purpose = interval`, D33).** The countdown of a timed warm-up item (UX 11.7.6, the OAP
"Row 3:00") uses the same table, alarm path and recovery as a rest, with these differences:

```
startInterval(nowWall, nowElapsed, bootCount, d_s, hardMaxS, durationSource, sessionId, plannedRowRef):
  any active rest or interval timer first ends (rest: ended_by = next_set_early, as onNextSetLogged without a set)
  start(nowWall, nowElapsed, bootCount, d_s, hardMaxS, interval, durationSource)   # start_wall = the Start tap, set_log_id null
onEndAlarm (interval): same alert as a rest end (vibration waveform, optional tone; no heads-up, D46), the
  session notification returns to the count-up; no overtime reminder; state = ended, ended_by = elapsed; the warm-up item is logged done with
  duration_s = prescribed_s (+ adjustments) and completed_at = end_wall
done early (the item's Done tap while running): state = ended, ended_by = skipped; the item is logged with the
  measured seconds (completed_at = tap). Skip (the notification shows Done in its place) and a set logged while
  the interval runs are done early too; a tap after the planned end counts as elapsed.
recovery (elapsed while dead): state = ended, ended_by = elapsed_while_dead; the item is logged with the planned
  seconds at end_wall; the alert plays only within timer.catchUpAlertMaxS, as for a rest
adjust, remainingMs: as for a rest
```

Nothing of an interval is copied into `set_logs` rest columns: the first working set after the warm-up has
no preceding rest timer (`rest_end_reason = none`). The session notification shows the item countdown
("Row · 1:12 left") with the same −15 s / Skip / +15 s actions.

`setStartEstimate` is UX §19.8's function: for holds the explicit hold start (measured); otherwise the
earliest of the first touch on the set card after the rest ended or was skipped, the Skip tap, and the
completion time minus the TIME-1 duration estimate (C19: 3 s per rep + 5 s per set), never earlier than
the previous set's completion. Set logging by RIR chip (D9; Done for warm-ups and Record for test
attempts, C63) records the completion time, not the start, so dynamic-set rest is an estimate and is
flagged `rest_source = estimated` in the `rest_ended` payload; the raw instants are in that payload so a
review can recompute rest with any formula. A set that had no preceding timer gets `rest_end_reason =
none`, `rest_timer_planned_s = null`, `rest_adjust_s = null` and `rest_timer_overridden = false`. The
column names and their CHECK rules are owned by DATA_MODEL §9.6.4 (C55); this section only fills them.

### 12.4 Scheduling and alerting

```kotlin
fun scheduleEnd(t: RestTimer) {
    val mode = when {
        !alarmManager.canScheduleExactAlarmsCompat() -> INEXACT
        settings.restAlarmMode == EXACT_IDLE && exactIdleAllowed() -> EXACT_IDLE
        else -> ALARM_CLOCK                                                   // default (C28)
    }
    when (mode) {
        ALARM_CLOCK -> alarmManager.setAlarmClock(AlarmClockInfo(t.endWallMs, openLiveSessionPi), restEndPi(t.id))
        EXACT_IDLE  -> alarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, t.endWallMs, restEndPi(t.id))
        INEXACT     -> alarmManager.setAndAllowWhileIdle(RTC_WAKEUP, t.endWallMs, restEndPi(t.id))
    }
}
// Kotlin enum names; stored and logged as the codes alarm_clock / exact_idle / inexact (12.2).
// A SecurityException from the exact call is caught: event rest_alarm_failed{timer_id, error_class},
// then the timer is rescheduled in INEXACT mode with the "Rest alarm may be late" banner.
```

Facts (engineering-fact, alarms documentation, verified 2026-10-07): for `setAlarmClock` "the system never
adjusts their delivery time" and leaves low-power modes if necessary. `USE_EXACT_ALARM` is granted
automatically and cannot be revoked by the user (Play restricts it to alarm/timer/calendar apps, which does
not matter for a sideloaded APK). `SCHEDULE_EXACT_ALARM` is not pre-granted to fresh installs on Android
14+. `setAlarmClock` itself requires one of the two permissions on Android 12+. When the permission is
revoked, all exact alarms of the app are cancelled; `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`
is delivered when it is granted. A side effect of `setAlarmClock` is the alarm icon / "next alarm" in the
status bar and lock screen during rests.

Manifest: `USE_EXACT_ALARM` (API 33+) and `SCHEDULE_EXACT_ALARM` with `android:maxSdkVersion="32"` (API
31–32 grant it by default). `canScheduleExactAlarms()` is still checked before every schedule; when it
returns false (unexpected with `USE_EXACT_ALARM`, possible on API 31–32 after a manual revoke) the timer
runs in `INEXACT` mode, shows a banner "Rest alarm may be late. Allow Alarms & reminders" with
`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, and the receiver for the state-change broadcast reschedules
the running timer.

**The `EXACT_IDLE` option (C28, C54).** The setting "Standard exact (no alarm icon)" (UX label) switches to
`setExactAndAllowWhileIdle`. It is offered only after at least `timer.exactIdleMinSamples` (30, C54)
`ALARM_CLOCK` rest alarms have been logged on this device with p95 latency ≤ 2000 ms. While it is active,
latency is monitored on the last `timer.exactIdleRevertWindow` (20) alarms of that mode; if their p95
exceeds 2000 ms the app reverts to `ALARM_CLOCK` and tells the user why. (Allow-while-idle alarms are
rate-limited in Doze, roughly one per 9 minutes per app per the research; rests are usually spaced less
than that, which is why this mode is not the default.)

**Alert on end (F20).** Performed by `RestAlarmReceiver` (exported=false):

1. Vibration, always: `VibrationEffect.createWaveform(timer.vibrationWaveformMs, -1)` with
   `VibrationAttributes.USAGE_ALARM` on API 33+ (`AudioAttributes.USAGE_ALARM` before), so it is felt in a
   pocket and is not suppressed by touch-feedback settings. Default waveform
   `[0, 300, 150, 300, 150, 600]` ms (three pulses, the last long).
2. Optional tone (setting, default off): section 14.2.
3. The ongoing session notification switches to overtime mode (count-up from `end_wall`, the next set in
   its text). It is the only notification of the rest end: no separate heads-up is posted (D46). The
   session channel is LOW and silent, so the update does not pop up; the vibration is the alert.
4. One overtime reminder buzz at `end_wall + timer.overtimeReminderS` (60 s), scheduled as a second
   `setAlarmClock`, cancelled by the next set.

**Pre-warning (C54).** At `end − timer.prewarnS` (10 s) a single light haptic tick, only while the app UI is
in the foreground, driven by the UI's own countdown (no alarm) and logged as `rest_prewarn`. No
pre-warning in the background: the session foreground service runs no loop (11.4), and the end alarm
already covers the screen-off case. C54's wording "while the app/FGS is in the foreground" is read this
way: the tick needs a visible app; a running FGS alone does not produce it (C72). The trigger note of
`rest_prewarn` in the DATA_MODEL §9.8.1 registry says the same.

### 12.5 Notification actions

The session notification carries three actions while a rest is running: **−15 s**, **Skip**, **+15 s**
(C28). They are `PendingIntent.getBroadcast(…, FLAG_IMMUTABLE)` to `RestActionReceiver` (exported=false)
with distinct request codes; the receiver applies `adjust`/`skip` (with `via = notification`), which
reschedules the alarm and reposts the notification, and logs `notification_action { notification_id:
N-SESSION, channel: session_ongoing, action: minus_15 | skip | plus_15 }` next to the `rest_adjusted` /
`rest_skipped` event (7.10). Double deliveries within `ui.doubleTapDebounceMs`
(300 ms) are ignored. There is no "log set" action: logging needs the app UI (the RIR chip for working
sets, D9; Done for warm-ups and Record for test attempts, C63).

**Auto-applied challenge rules (D14, C46).** When a challenge in-session rule changes the remaining sets
(for example the session A first-set pin calibration ±5 lb), the change happens in the app at the moment
the triggering set is logged. The notification only shows the resulting next-set prescription (it is
reposted with the new load, like any state change); it carries **no Undo action**. Undo lives on the set
card ("Rule: … · Undo", UX), where the user sees what is being undone; an Undo reposts the notification
with the restored prescription. General (non-challenge) in-session changes stay one-tap suggestions in the
app (D10, C16) and never appear as notification actions either.

### 12.6 Process death, reboot and update recovery

`RestTimerRecovery.reconcile()` runs at application start, in `SessionService.onCreate`, in
`RestAlarmReceiver`, and on `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`:

```
t = active rest_timer (state running or overtime, ended_by null or elapsed with overtime pending)
if t == null: return
r = remainingMs(now)
if t.state == running and r > 0:  rescheduleEnd(t.end_wall); repost notification         # idempotent (same PendingIntent)
if t.state == running and r <= 0: state = overtime, ended_by = elapsed_while_dead, latency unknown
                                  event rest_recovered{missed_by_ms = −r}; repost overtime notification; catch-up alert (12.4)
                                  only if −r <= timer.catchUpAlertMaxS*1000; schedule the overtime reminder if still ahead
if t.state == overtime and !overtime_reminder_sent and end_wall + overtimeReminderS*1000 > now: reschedule reminder
```

A user force-stop (or an OEM killer that behaves like one, such as some "clear recents" implementations)
cancels every alarm of the app until it is next launched; this is exactly the failure the D2 hardening in
section 15 prevents, and the on-device checklist (15.6) tests it.

### 12.7 Latency logging

Every delivered end alarm writes `session_events` type `rest_alarm_fired` with: `scheduled_end_wall_ms`,
`fired_wall_ms`, `latency_ms`, `alarm_mode`, `app_state` (`foreground_ui`, `fgs_background`,
`cold_start`), `screen_interactive`, `device_idle` (`PowerManager.isDeviceIdleMode`), `power_save`,
`battery_opt_exempt`, `standby_bucket` (API 28+), `fgs_type`, `sdk_int`, `manufacturer`, `build_display`,
the `timer_id` and `platform_config_hash` (C54; the registry key list, 7.10). The Reliability screen
(15.5) shows p50/p95/max over the last 30 alarms. Other
timer events (payloads in 7.10): `rest_started`, `rest_adjusted`, `rest_skipped`, `rest_prewarn`,
`rest_alarm_stale`, `rest_alarm_failed`, `rest_overtime_reminder`, `rest_ended`, `rest_recovered`,
`rest_tone_skipped`. The C39 registry names the delivered-alarm event `rest_alarm_fired` (its rename
table replaces the earlier `rest_elapsed`), and DATA_MODEL's review view `v_rest_alarm_latency` (§14.12)
reads that name. `manufacturer` and `build_display` are free-form system strings, so the client
sanitises them to the payload code pattern of DATA_MODEL §16.3 (characters outside it become `_`, at most
64 characters) before writing the event.

### 12.8 Time zones and DST

Rest timers live entirely in UTC epoch milliseconds and elapsed time, so the DST change at 03:00 CEST →
02:00 CET on 2026-10-25 has no effect on a running timer: a 180 s rest started at 02:59 CEST
(00:59:00Z) ends at 01:02:00Z, displayed as 02:02 CET. Golden test G4 asserts this.

---

## 13. Notifications

### 13.1 Channels

| Channel id | Name (user-visible) | Importance | Sound | Vibration | Lock screen | Used for |
|------------|--------------------|------------|-------|-----------|-------------|----------|
| `session_ongoing` | "Active session" | LOW | none | none | PUBLIC | The FGS notification: session progress, rest countdown, actions (`N-SESSION`). Not MIN, so it can be promoted to a Live Update. |
| `rest_done` | "Rest finished" | HIGH | none (app plays tone) | none (app vibrates) | PUBLIC | Retired (D46): was the heads-up at rest end (`N-REST-DONE`). No longer created on new installs, never deleted on existing ones; the id stays reserved. |
| `habit_reminders` | "Reminders" | DEFAULT | system default | system default | PRIVATE | Home-habit reminders (`N-HABIT`, section 17) and the weigh-in reminder (`N-WEIGH`). |
| `challenge` | "Challenge updates" | DEFAULT | system default | system default | PRIVATE | Test-day morning (`N-TEST-DAY`), program changes outside a session (`N-PROGRAM`), phase unlocked (`N-CELEBRATE`). |
| `safety` | "Safety alerts" | HIGH | system default | system default | PRIVATE | Challenge rule notifications of kind stop/warn, such as a pain-protocol exit or extension (`N-SAFETY`). |
| `app_status` | "App status" | LOW | none | none | PRIVATE | Sync problems (`N-SYNC`), export reminder (`N-EXPORT`), calendar write problems, session idle prompt (11.5). |

The channel ids, importances and the assignment of each `N-…` notification to a channel above are final
and authoritative (C54); UX §18 uses exactly these six ids and this assignment (`N-WEIGH` on
`habit_reminders`; `N-TEST-DAY`, `N-PROGRAM`, `N-CELEBRATE` on `challenge`; `N-SAFETY` on the HIGH
channel `safety`). The `N-…` ids are the UX §18 catalogue ids and are the `notification_id` values in the
7.10 events; the channel id is logged next to them (`channel`). Channels are created at startup and never
deleted/renamed (users' per-channel choices would be lost); behaviour changes need a new channel id.
Ownership split: channels, importances, notification ids, actions, timing and delivery behaviour are
owned here (C54); the user-visible strings are the UX §18 catalogue's, and the texts shown in this
section only illustrate structure.

### 13.2 Notification ids and request codes

| Id / request code | Use |
|-------------------|-----|
| 1001 | Session FGS notification |
| 1002 | Reserved (was the rest-done heads-up, retired by D46) |
| 1101 / 1102 / 1103 | Rest actions −15 s / Skip / +15 s |
| 1201 / 1202 | Rest end alarm / overtime alarm PendingIntents |
| 2000 + (hash(reminderId) mod 1000) | Habit reminders and their alarms |
| 1003 | Session notification delete intent (`notification_dismissed`) |
| 3001 | Sync/status notification |
| 3002 | Export reminder |
| 3102 | Export reminder "Export" action |
| 3003 | Session idle prompt |
| 3103 / 3113 / 3123 | Idle alarm / idle "Finish" / idle "Keep open" |
| 3004 | Weigh-in reminder (and its alarm) |
| 4001 / 4002 / 4003 | Test-day morning (and its alarm) / program change / phase unlocked |
| 4101 | Safety alert |

### 13.3 Session notification content

Built with `NotificationCompat.Builder` on `session_ongoing`:

| Mode | Title | Text | Chronometer | Actions |
|------|-------|------|-------------|---------|
| In set | "Session C · OAP" | "Set 2 of 4 · Weighted pull-up" | count-up, `setWhen(sessionStartWall)` | Open |
| Rest running | "Rest · next: Weighted pull-up 3/4" | "BW + 22.5 kg × 4" | `setUsesChronometer(true)`, `setChronometerCountDown(true)`, `setWhen(end_wall)` | −15 s, Skip, +15 s |
| Overtime | "Rest over · Set 3 of 4" (UX §24.6 string `rest_overtime_title`, "Rest over · Set {n} of {total}") | "Weighted pull-up · BW + 22.5 kg × 4" | count-up from `end_wall` | Skip |

Common: `setOngoing(true)`, `setOnlyAlertOnce(true)`, `setSilent(true)`, `setCategory(CATEGORY_WORKOUT)`,
`setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)` (avoids the up-to-10-second FGS notification
delay on Android 12+), `setVisibility(VISIBILITY_PUBLIC)`, content intent → route
`fitapp://session/current` (7.4), `setDeleteIntent` → event `notification_dismissed` (Android 14+ lets
users dismiss FGS notifications; the app does not re-post until the next state change). The notification
is updated on state changes only (including a next-set prescription changed by an auto-applied challenge
rule or its Undo, 12.5), never every second; the system draws the countdown.

### 13.4 Android 16 Live Update promotion

Facts (engineering-fact, Live Update docs, verified 2026-10-07): manifest permission
`android.permission.POST_PROMOTED_NOTIFICATIONS` (not a runtime permission); `setRequestPromotedOngoing(true)`
(or `EXTRA_REQUEST_PROMOTED_ONGOING`); `setOngoing(true)`; a content title; style Standard, BigText, Call,
Progress or Metric; no custom `RemoteViews`, not a group summary, not `setColorized(true)`; channel
importance not MIN. Chip text through `setShortCriticalText` or the chronometer (`setWhen`,
`setUsesChronometer`, `setChronometerCountDown`). Users can demote or disable Live Updates per app;
`NotificationManager.canPostPromotedNotifications()` reports the state and
`Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` opens the setting.

Implementation: the session notification always requests promotion (ignored below API 36). On API 36+ the
Reliability screen shows whether promotion is allowed and offers the settings link. Whether the posted
notification actually carries `FLAG_PROMOTED_ONGOING` is read back from
`NotificationManager.getActiveNotifications()` and logged once per session as
`notification_promotion_state { can_post_promoted, flag_promoted }` (`canPostPromotedNotifications()`
and whether the flag is set; 7.10). OEM skins (HyperOS, ColorOS) may present promoted notifications in their
own way or not at all; the standard ongoing notification is always the fallback and is verified on the
phone (15.6).

---

## 14. Haptics, audio and keep-screen-on

### 14.1 Haptics

| Moment | Haptic | API |
|--------|--------|-----|
| Set logged (RIR chip tap, hold "felt" chip, warm-up Done, test-attempt Record; C63) | Confirm | `HapticFeedbackConstants.CONFIRM` (API 30+; `VIRTUAL_KEY` below) via `View.performHapticFeedback`, or Compose `LocalHapticFeedback` with the equivalent type (verify names in Compose 1.12) |
| Invalid entry (e.g. unloadable weight typed) | Reject | `REJECT` (API 30+) |
| Stepper step | Tick | `CLOCK_TICK` / segment tick |
| Rest pre-warning (UI in foreground) | Tick | as above |
| Timed-hold lead-in 3-2-1 and "go" | Tick, Confirm | as above |
| Rest end | Waveform | `Vibrator` with alarm usage (12.4) |

`performHapticFeedback` needs no permission and respects the system touch-feedback setting. The rest-end
waveform needs `VIBRATE` and is always on (F20).

### 14.2 Audio cues and ducking (F20)

The optional rest-end tone and the timed-hold beeps (cue timing owned by the UX spec) use a preloaded
`SoundPool` sample (`res/raw/rest_done.ogg`, about 600 ms) with `AudioAttributes.USAGE_NOTIFICATION_EVENT`
(to verify on the phone that it plays through earbuds; fallback candidate `USAGE_ASSISTANCE_SONIFICATION`),
inside an `AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)`. Music in earbuds ducks instead of
pausing; focus is abandoned when the sample ends.

Android 17 background-audio hardening (engineering-fact, verified 2026-10-07): on Android 17 every app
needs a visible activity or a non-`shortService` foreground service to play audio or request focus; apps
targeting API 37 additionally need a foreground service with while-in-use capability, which a service
started while the app is visible has (this is why section 11.3 starts the service from the user's tap).
Failed focus requests return `AUDIOFOCUS_REQUEST_FAILED`; playback fails silently. The app therefore checks
the focus result, skips the tone if focus is denied, records `rest_tone_skipped { reason }`, and relies on
vibration (which is not covered by the audio hardening). Test command on the phone:
`adb shell cmd audio set-enable-hardening enable` (and `disable` afterwards), diagnosis via
`adb shell dumpsys audio` (`AudioHardening` entries).

### 14.3 Keep-screen-on (F20)

Default ON during an active session, toggle in settings. Implemented in the live-session route with a
`DisposableEffect` that sets `LocalView.current.keepScreenOn = (enabled && sessionActive) ||
holdTimerRunning` and clears it on dispose. `holdTimerRunning` covers the lead-in and hold phases of UX
§19.9, which force the screen on regardless of the setting because the service holds no wake lock (11.4)
and a sleeping CPU would delay the hold cues. It is not a window flag on the whole activity, so other
screens never keep the screen on.

---

## 15. Xiaomi / OnePlus / Oppo hardening (D2)

### 15.1 Why

dontkillmyapp ranks Xiaomi and OnePlus among the most aggressive background killers; OnePlus settings
reportedly revert after firmware updates, and the site lists no developer-side fix for OnePlus/Oppo. On
MIUI/HyperOS the per-app "Autostart" permission also blocks an app from being started by alarms and
broadcasts when its process is not running, which would silence a rest alarm after a kill. The measures
below are layered; none of them alone is sufficient.

### 15.2 Measures

| # | Measure | Mechanism | Who |
|---|---------|-----------|-----|
| M1 | Foreground service for the whole session | Section 11 | app |
| M2 | `setAlarmClock` for rest ends | Section 12 | app |
| M3 | Battery-optimisation exemption | `PowerManager.isIgnoringBatteryOptimizations(pkg)`; if false, explain, then `Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg"))` (needs the normal permission `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`); fallback `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` | user taps "Allow" |
| M4 | OEM autostart / background settings | Deep-link attempts (15.3) with written fallback instructions | user |
| M5 | OEM battery saver "No restrictions" | Xiaomi: Settings → Apps → app → Battery saver → "No restrictions". OnePlus/Oppo: Battery → app → "Allow background activity" and disable "Optimise battery use"; OnePlus Advanced optimisation: disable "Deep optimization" / "Sleep standby optimization" | user |
| M6 | Lock the app in Recents | Recents → long-press or pull down the app card → lock icon. Prevents "clear all" from killing it. | user |
| M7 | Notifications allowed incl. lock screen and floating (heads-up) | MIUI often disables floating and lock-screen notifications for sideloaded apps by default: Settings → Notifications → app → "Floating notifications" and "Lock screen notifications" | user |
| M8 | Latency logging + Reliability screen | Sections 12.7, 15.5 | app |
| M9 | On-device Doze/kill test | Section 15.6 | user + Claude |

### 15.3 Autostart deep-link attempts

Best-effort list (engineering folklore from open-source "autostarter" helpers, unverified for current
HyperOS/ColorOS; every entry may fail). The app tries them in order; for each it calls
`startActivity` inside try/catch for `ActivityNotFoundException` and `SecurityException` (several of
these activities are not exported on newer builds). It stops at the first success. If all fail, it opens
`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the app and shows the written steps.

| Manufacturer (`Build.MANUFACTURER`, lower-case) | Component attempts (package / activity) |
|---|---|
| `xiaomi`, `redmi`, `poco` | `com.miui.securitycenter` / `com.miui.permcenter.autostart.AutoStartManagementActivity`; then battery: `com.miui.powerkeeper` / `com.miui.powerkeeper.ui.HiddenAppsConfigActivity` with extras `package_name`, `package_label` |
| `oneplus` | `com.oneplus.security` / `com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity` (OxygenOS ≤ 11), then the Oppo list (OxygenOS 12+ is ColorOS-based) |
| `oppo`, `realme` | `com.coloros.safecenter` / `com.coloros.safecenter.permission.startup.StartupAppListActivity`; `com.coloros.safecenter` / `com.coloros.safecenter.startupapp.StartupAppListActivity`; `com.oppo.safe` / `com.oppo.safe.permission.startup.StartupAppListActivity`; battery: `com.coloros.oppoguardelf` / `com.coloros.powermanager.fuelgaue.PowerUsageModelActivity` |

Package visibility (API 30+): the manifest declares `<queries><package android:name="…"/></queries>` for
each package above, so `resolveActivity` works. The app does not use reflection or hidden APIs to read
the autostart state; the user confirms each step with an "I've done this" checkbox, stored locally and
shown in the Reliability screen as "user-confirmed". Each attempt is logged as `oem_settings_opened
{ maker, intent_ok }` (whether an intent from the list above resolved) and each confirmation as
`oem_settings_confirmed { maker }` (registry names, 7.10).

Written fallback steps (string resources, shown per manufacturer):

- **Xiaomi / HyperOS:** (1) Settings → Apps → Manage apps → FitnessApp → Autostart: on. (2) Battery saver:
  No restrictions. (3) Other permissions: "Show on lock screen" allowed. (4) Notifications: allow floating
  and lock-screen notifications. (5) Recents: lock the app card.
- **OnePlus:** (1) Battery → Battery optimisation → FitnessApp → Don't optimise. (2) Advanced
  optimisation: switch off Deep optimisation and Sleep standby optimisation. (3) App info → Battery usage:
  allow background activity. (4) Recents: lock the app card. (5) Re-check after every system update.
- **Oppo / Realme (ColorOS):** (1) App info → "Allow auto-launch" (or Startup manager in Phone Manager).
  (2) Battery usage: allow background activity, do not optimise. (3) Recents: lock. (4) Notifications:
  allow banners and lock screen.

Generic phones (any other manufacturer) see only M3, M7 and the test alarm, which keeps onboarding valid
for any user (D3).

### 15.4 Reliability setup (onboarding step, M1)

Ordered, each step skippable and revisitable in You → Settings → Reliability:

1. Notifications (`POST_NOTIFICATIONS`, API 33+).
2. Physical activity (`ACTIVITY_RECOGNITION`, API 34+), explained as "needed so Android lets the session
   run as a fitness session; no activity data is read".
3. Exact alarms: check only (`canScheduleExactAlarms()`), settings link if false.
4. Battery optimisation exemption (M3).
5. Manufacturer steps (M4–M7) if the manufacturer matches.
6. Live Updates (API 36+): status and settings link.
7. "Test the rest alarm": a 20-second test timer; the user locks the phone and puts it away; the result
   screen shows the measured latency and whether vibration was felt (user answers yes/no). Logged as
   `timer_selftest { latency_ms, felt, screen_off, alarm_mode, passed }` (the DATA_MODEL §9.8.1 registry
   name, C39; formerly `reliability_test` here).

### 15.5 Reliability screen

Rows with a green/amber/red state and a fix action: notifications allowed; heads-up/lock-screen hint
(user-confirmed on Xiaomi); exact alarms; battery exemption; FGS type in use (`health` or `specialUse`);
Live Update allowed (API 36+); autostart/no-restrictions/recents lock (user-confirmed); rest-alarm latency
p50/p95/max of the last 30 alarms (amber if p95 > 2000 ms, red if > 5000 ms or any alarm recovered as
`elapsed_while_dead` in the last 14 days); last test-alarm result.

### 15.6 On-device test checklist and Doze commands

Commands below change device state only temporarily and are run by Claude only after the user agrees in
chat for that test session; every forced state is reverted at the end. Settings that persist (battery
whitelist via `dumpsys deviceidle whitelist +`, `appops set`, `pm grant`) are never changed by Claude; the
user toggles them in the Settings UI.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"; $pkg = "dev.fitnessapp"
# Doze
& $adb shell dumpsys battery unplug
& $adb shell dumpsys deviceidle force-idle          # enter deep Doze now
& $adb shell dumpsys deviceidle get deep             # expect IDLE
& $adb shell dumpsys alarm | Select-String $pkg      # our pending alarms
# ... run a rest timer, wait for the alarm ...
& $adb shell dumpsys deviceidle unforce
& $adb shell dumpsys battery reset
# App standby buckets
& $adb shell am get-standby-bucket $pkg
& $adb shell am set-standby-bucket $pkg rare         # then later: active
& $adb shell am set-standby-bucket $pkg active
# Process death without cancelling alarms (simulates a low-memory kill)
& $adb shell am crash $pkg
# Force-stop (simulates an aggressive OEM kill; cancels alarms until next launch, expected failure mode)
& $adb shell am force-stop $pkg
# Logs
& $adb logcat -d -v time -s FitnessApp:V DeterminismSelfTest:V AndroidRuntime:E
```

Checklist (pass criteria in brackets; each result is recorded in the test log of the milestone):

| # | Scenario | Pass if |
|---|----------|---------|
| T1 | 60 s rest, screen off, phone in pocket, music in earbuds | Vibration within 2 s of the end; tone (if enabled) ducks music; `latency_ms` ≤ 2000 |
| T2 | T1 under forced Doze (commands above) | Same as T1 |
| T3 | T1 with system Battery saver on | Same as T1 |
| T4 | Rest running, `am crash` | Alarm still fires; notification restored on next launch or by the receiver |
| T5 | Rest running, swipe app away in Recents (unlocked) | Alarm fires (if not: lock in Recents and enable Autostart, re-test) |
| T6 | Rest running, reboot | After boot (Autostart on): no crash; timer recovered as overtime or rescheduled |
| T7 | Rest running, `am force-stop` | Expected: no alarm. On next launch the timer shows `elapsed_while_dead`; no crash |
| T8 | Notification actions −15/+15/Skip from the lock screen | End time changes by 15 s; Skip ends the rest; events logged |
| T9 | ACTIVITY_RECOGNITION denied (API 34+) | Session starts with `specialUse`; logged |
| T10 | Notifications denied | Session and alarm still work; vibration felt; Reliability screen red |
| T11 | Live Update (API 36+) | Countdown chip on status bar/lock screen, or documented fallback |
| T12 | Overnight idle (8 h screen off), then a session | First rest alarm on time |
| T13 | Airplane mode for a whole session, then reconnect | All sets logged; sync drains outbox within 1 min of reconnect |
| T14 | Determinism self-test | All golden hashes equal |
| T15 | Audio hardening test (API 37 phone or `set-enable-hardening enable`) | Tone plays while session FGS runs; skipped gracefully otherwise |
| T16 | Habit reminder at a set time with screen off | Delivered within 1 min |
| T17 | Calendar write in the sandbox local test calendar, then (after user OK) the primary calendar for one week | Events appear in Google Calendar on the web within a few minutes; markers present; no unmarked event changed. Test-calendar half passed on 2026-10-22 (README M2b log); primary-calendar half passed on 2026-11-03 (README M4 log): real writes on since 2026-10-22, W43-W46 written. |

---

## 16. Calendar: read and write (C20, C36, D11)

### 16.1 Division of labour

| Concern | Where |
|---------|-------|
| Reading provider rows, permissions, observers, executing writes, safety guards | `:app` `calendar/` (`CalendarProviderGateway`, `CalendarSyncController`, `CalendarWriteExecutor`) |
| Classification into `{challenge_session(slot), planned_gym(type), run(intensity, minutes), busy, ignored}` incl. marker detection | `:algo` `calendar.classify` (C20) |
| Matching to challenge slots, deciding which events to create/update/delete, C36 slot-type changes | `:algo` `challenge.calendar` (matching + write-back) and `weekplan` for accepted "Plan it" sessions |
| Linking a free session to an event post hoc (same day, ±3 h) | engine (C35) |

The Android layer is a thin, testable executor: it hands normalized rows to `:algo` and executes the
write plan `:algo` returns, refusing any operation that would violate the guards in 16.7.

Calendar gym events are training time (F11 revised, C49): the engine derives the session budget as event
duration − 5 min and adds no travel buffer. The Android layer only passes the exact `beginUtcMs` /
`endUtcMs` of each instance; it never adjusts durations.

### 16.2 Permissions and degraded mode

`READ_CALENDAR` and `WRITE_CALENDAR` (one runtime permission group) are requested together in the calendar
onboarding step. Without READ the app plans without calendar anchors and shows a banner ("Calendar not
connected: planned sessions are not considered"). Without WRITE (possible if the user revokes it later)
auto-write is off and a banner explains it. Entering degraded mode is logged as `calendar_unavailable
{ reason }` (`permission`, or `provider_error` when the provider query throws; 7.10). Calendar data never
blocks Today; the planner treats a missing calendar as "no planned sessions".

### 16.3 Calendar selection

Query `CalendarContract.Calendars` for `_ID, ACCOUNT_NAME, ACCOUNT_TYPE, OWNER_ACCOUNT, IS_PRIMARY,
CALENDAR_DISPLAY_NAME, VISIBLE, SYNC_EVENTS, CALENDAR_ACCESS_LEVEL, CALENDAR_TIME_ZONE`.

Default read set and write target (deterministic):

```
candidates = calendars where ACCOUNT_TYPE == "com.google"
             and (IS_PRIMARY == 1 or (IS_PRIMARY is null and OWNER_ACCOUNT == ACCOUNT_NAME))
             and CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_OWNER (700)
             and SYNC_EVENTS == 1
sorted by ACCOUNT_NAME, _ID
if size == 1: primary = candidates[0]
elif size > 1: ask the user to pick (onboarding); never guess
else: no primary → read-only from user-selected calendars, auto-write disabled
```

- The selection is stored by identity `(ACCOUNT_NAME, ACCOUNT_TYPE, OWNER_ACCOUNT)`, not by `_ID` (IDs
  change when an account is re-added). The identity stays on the device; logs and synced rows carry only
  `sha256(identity)`.
- The user may add further calendars to the **read** set in settings. Calendars whose display name matches
  `(?i)todoist` are excluded from suggestions and can never be a write target (D11).
- Writes go only to the primary calendar.

### 16.4 Reading

Window per refresh: `[Monday of the current ISO week − 7 days, Monday of the current ISO week + 14 days)`
in the profile zone, converted to UTC epoch ms (data-model research S8). For Wednesday 2026-10-07 this is
`[2026-09-28 00:00 CEST, 2026-10-19 00:00 CEST)` = `[1790546400000, 1792360800000)`.

```kotlin
val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
    .also { ContentUris.appendId(it, windowStartMs); ContentUris.appendId(it, windowEndMs) }.build()
val projection = arrayOf(
    Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.CALENDAR_ID, Instances.TITLE,
    Instances.DESCRIPTION, Instances.EVENT_LOCATION, Instances.ALL_DAY, Instances.STATUS,
    Instances.SELF_ATTENDEE_STATUS, Instances.EVENT_TIMEZONE,
)                                                    // the event's _SYNC_ID for the instance key is read from
                                                      // Events by id (as built in M2b.5); null -> the provider _ID
val selection = "${Instances.CALENDAR_ID} IN (${ids.joinToString { "?" }})"
val sortOrder = "${Instances.BEGIN} ASC, ${Instances.EVENT_ID} ASC"
```

Each row becomes `CalendarInstanceRow` (pure data: ids, `beginUtcMs`, `endUtcMs`, `allDay`, raw
title/description/location strings, status, self-attendee status, event time zone). All filtering
(cancelled, declined, all-day, Todoist, titles, locations, Dutch keywords, markers) is done by
`calendar.classify` in `:algo`, so that the classification is replayable. All-day instances carry UTC
midnight boundaries; the classifier ignores them (D11).

Refresh triggers:

| Trigger | Detail |
|---------|--------|
| `ContentObserver` on `CalendarContract.CONTENT_URI` (`notifyForDescendants = true`) | Registered while the process lives; debounced `calendar.observerDebounceMs` (2000 ms). Changes caused by the app's own writes produce identical inputs and therefore no new writes. |
| App foreground (`ON_START`) | Immediate refresh. |
| `daily-maintenance` at 05:00 profile time | Refresh plus write plan for the (possibly new) current and next week. |
| After post-session updates (L8) and challenge state changes | Re-plan + write plan. |

Each instance is identified by the `instance_key` of DATA_MODEL §9.7.3 (owner of the definition), computed
on the device: SHA-256 hex of `account_type|account_name|calendar_sync_id|event_sync_id (or _ID)|instance_begin_ms`.
Classification results are logged as `session_events` type `calendar_classified` (C20). Privacy rule:
for events classified `busy` or `ignored`, the payload holds only the `instance_key`, the kind and the
score components, never title, description or location. Only gym/challenge/test/run events are stored
with their titles in the synced `calendar_events` table (DATA_MODEL §9.7.3); everything else stays in the
local `calendar_instance` cache (8.3).

### 16.5 Marker line

Every event the app writes ends its `DESCRIPTION` with one marker line (D11). The format is the single
canonical one of C37, used identically by the DATA_MODEL `calendar_events.marker` CHECK, UX,
ALGORITHMS_PLANNING (`calendar.classify`), CHALLENGE_ENGINE (`challenge.calendar`) and the challenge
definition's `writeBack`:

```
[fitapp:v1 <uuid> <slotKey>]
```

- `<uuid>` (C37): the **enrollment id** for challenge sessions and test days, the **recommendation id** of
  the accepted "Plan it" recommendation for general sessions. Both are ordinary user rows with
  device-generated UUIDv7 ids (DATA_MODEL §6.4). The uuid alone is therefore not unique per event (every
  event of one enrollment carries the same uuid); the pair `(uuid, slotKey)` identifies an event.
- `<slotKey>` (C37): matches `[A-Za-z0-9_./-]{1,64}` (upper-case letters allowed, because slot ids such as
  `C` and `TEST` are upper-case). Challenge: `<definitionSlug>/<slot>/<isoYear>-W<ww>` (for example
  `oap/C/2026-W42`, `oap/TEST/2026-W46`). General: `gen/<isoYear>-W<ww>/<isoDow>` with ISO day of week
  1–7 (for example `gen/2026-W42/5`).
- Parse regex, applied to the last non-empty line of the description after trimming (anchored; apart
  from the capture groups it is the same pattern as the DATA_MODEL §9.7.3 `calendar_events.marker`
  CHECK, the ALGORITHMS_PLANNING `calendar.classify` marker grammar and the CHALLENGE_ENGINE marker row):
  `^\[fitapp:v1 ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) ([A-Za-z0-9_./-]{1,64})\]$`.
  The ids are device-generated UUIDv7 in lower-case canonical text, but the parser does not depend on the
  version digit (the shared pattern accepts any lower-case UUID). A key that matches the class but neither
  shape above is treated as a foreign marker: the event is never edited.
- A C36 slot-type change on an app-owned event (for example a postponed test day rewritten to session B)
  rewrites the slot key in the same guarded update (`oap/TEST/2026-W41` → `oap/B/2026-W41`).
- The line before the marker is human-readable: "Planned by FitnessApp. If you move this event, the app
  keeps your new time."
- Rendering and parsing have exactly one implementation (C68): `Marker` in the package
  `dev.fitnessapp.algo.core.calendar` (render, parse and the LIKE guard pattern). The classifier
  (`calendar.classify`), the write planner (`challenge.calendar`) and the app's `CalendarWriteExecutor`
  all call it, so detection, rendering and the write guard share one definition.

### 16.6 `calendar_link` (local-only)

| Column | Meaning |
|--------|---------|
| `id` (PK) | Local UUIDv7 of the link row |
| `marker_uuid`, `slot_key` | The two marker fields (16.5); `UNIQUE(marker_uuid, slot_key)` |
| `kind` (`challenge` \| `test` \| `general`) | What the event represents |
| `calendar_identity_sha256` | Which calendar |
| `event_id` | Provider `_ID` of the event (may change; rediscovered by marker) |
| `last_written_title`, `last_written_desc_sha256`, `last_written_dtstart_ms`, `last_written_dtend_ms` | What the app wrote last |
| `state` | `active`, `user_moved`, `user_edited`, `user_deleted`, `released`, `deleted_by_app` |
| `created_at_ms`, `updated_at_ms` | |

After a reinstall the table is rebuilt by scanning the read window for markers; the found values become
`last_written_*` (treated as "unchanged"). The synced `calendar_events` row of an app-written event
carries `app_written = true` and the full marker line (DATA_MODEL §9.7.3). The link `state` is mapped
onto that row's `write_state` (E24) and `attention` columns by the mapping table in DATA_MODEL §9.7.3
(for example `user_moved` → `attention = moved_externally`, `released` → `edited_externally` with the last
written marker kept, `deleted_by_app` → `write_state = deleted`); the executor writes exactly that
mapping after each successful batch (OI-26).

### 16.7 Write rules (D11) and field ownership

What is written automatically: challenge sessions and test days for the **current and next ISO week**, in
the primary calendar. General recommended sessions only when the user taps "Plan it" (one tap). Events
without a marker are never modified, except by "Adopt".

Field ownership after the app created an event. This edit policy is canonical (C37) and this section is
its owner; UX, ALGORITHMS_PLANNING and CHALLENGE_ENGINE reference it. In short: user time edits win; a
user-edited title or description stops app updates for that event; the app deletes only its own
unmodified future events; user deletions are respected.

| Field | Owner | Rule |
|-------|-------|------|
| `DTSTART`/`DTEND` | user after creation | The app moves an event only if the current times still equal `last_written_*`. If the user moved it, the link becomes `user_moved` and the new time is fed to the planner as the slot's planned time; the app never moves it back. |
| `TITLE` | app until the user edits it | If the current title differs from `last_written_title`, the link becomes `user_edited` and the app stops changing the title (C36 slot-type changes then become a "Rename?" prompt instead). |
| `DESCRIPTION` body | app until the user edits it | Same rule via `last_written_desc_sha256`. The marker line is always kept; if the user deletes the marker the link becomes `released` and the app never touches the event again. |
| Existence | app for unmodified future events | Delete only if: marker present, `DTSTART > now`, and the link is `active` (unmodified). Otherwise the app shows a one-tap prompt "Remove 'Gym — sessie B' from your calendar?". Past events are never deleted. |
| User deletion | user | If a linked event disappears, the link becomes `user_deleted` and the app does not re-create that slot for that week unless the user taps "Add to calendar". |

Guards enforced by `CalendarWriteExecutor` regardless of what the write plan says:

1. Target calendar = the selected primary, access level ≥ `CAL_ACCESS_CONTRIBUTOR` (500). In the
   `sandbox` build (`USER_CALENDAR_WRITES_ENABLED = false`, F27) the only allowed target is the app-owned
   local test calendar of 16.10.
2. Week guard: `DTSTART` lies in the current or next ISO week (profile zone) for automatic writes; "Plan it"
   and "Adopt" are user actions and may target any future date.
3. Atomic marker guard on update and delete: the provider call includes a selection on the exact marker
   (uuid **and** slot key, because one enrollment id is shared by all its events), so the operation touches
   zero rows if the marker is gone or different. `Marker.likePattern` escapes `\`, `%` and `_` (the slot-key
   class allows `_`, a LIKE wildcard):
   ```kotlin
   resolver.update(Events.CONTENT_URI, values,
       "${Events._ID} = ? AND ${Events.DESCRIPTION} LIKE ? ESCAPE '\\'",
       arrayOf(eventId.toString(), "%" + Marker.likeEscape("[fitapp:v1 $uuid $slotKey]") + "%"))
   ```
   The id goes into the selection on `Events.CONTENT_URI`: the Calendar provider refuses any selection on an
   `events/<id>` URI ("Selection not permitted for content://com.android.calendar/events/<id>", engineering-fact,
   seen on Android 16 on 2026-10-22, M2b.5). The same holds for the delete guard and the Adopt guard.
   SQLite's `LIKE` ignores ASCII case, so an op is only planned after the last read found the exact,
   case-sensitive marker (16.4); the guard then protects against edits made between that read and the
   batch. Zero rows → abort that op, set the link to `released`, log
   `calendar_write_failed { op, error_class: "marker_missing" }` (7.10).
4. Delete guard adds `AND ${Events.DTSTART} > ?` with `now`.
5. Adopt (user tap only): update with selection `${Events.DESCRIPTION} IS NULL OR ${Events.DESCRIPTION} NOT LIKE '%[fitapp:%'`
   and append "\n\n" + marker to the existing description (the user's text is preserved). The marker uses the
   enrollment id and the matched slot's key (C37); logged as `calendar_event_adopted { slot, instance_key }`.
6. All ops of one plan run in one `applyBatch` (`ContentProviderOperation`), so a plan is applied entirely or
   not at all; the link table is updated after the batch succeeds. An exception from the provider (or a
   `SecurityException` after a permission revoke) fails the whole batch and is logged as
   `calendar_write_failed { op, error_class }` (`provider_error`, `security_exception`).

An op that a guard refuses before the batch is not sent and is logged as `calendar_write_failed { op,
error_class }` with the guard's code (`target_guard`, `week_guard`, `delete_guard`, `sandbox`); only
executed ops are logged as `calendar_event_written` (16.8), following the DATA_MODEL §9.8.1 registry.

Insert values:

```kotlin
ContentValues().apply {
    put(Events.CALENDAR_ID, primaryId)
    put(Events.DTSTART, startUtcMs)                   // ZonedDateTime.of(localDate, localTime, profileZone).toInstant()
    put(Events.DTEND, endUtcMs)                       // length from the write plan; the event is training time only (C49)
    put(Events.EVENT_TIMEZONE, "Europe/Amsterdam")     // profile zone (F18)
    put(Events.EVENT_END_TIMEZONE, "Europe/Amsterdam")
    put(Events.TITLE, title)                           // from the challenge definition's title template / plan
    put(Events.DESCRIPTION, body + "\n\n" + humanLine + "\n" + marker)
    put(Events.EVENT_LOCATION, gymLocation)            // from the user's gym profile
    put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
    put(Events.STATUS, Events.STATUS_CONFIRMED)
    put(Events.HAS_ALARM, 0)                           // no calendar reminders; the app has its own notifications
}
```

Required columns per the provider documentation (engineering-fact, verified 2026-10-07): `CALENDAR_ID`,
`DTSTART`, `EVENT_TIMEZONE`, and `DTEND` for non-recurring events. The app writes only single,
non-recurring events. An application delete sets the row's `DELETED` flag and the Google sync adapter
propagates it; the app never uses sync-adapter mode on Google accounts.

Titles come from data, not code (F25): challenge sessions use the challenge definition's calendar title
template (for the OAP definition, the user's existing Dutch style such as "Gym — sessie C (volume en
techniek)", which the classifier already recognises), general sessions use the plan's English label (for
example "Gym — Legs + Push"); descriptions are in English.

### 16.8 Sync behaviour

Writes through `ContentResolver` mark rows dirty; the Google Calendar sync adapter uploads them on its own
schedule (usually within minutes when online), and edits made on the web arrive through the same adapter.
The app does not force syncs. If the account's calendar sync is off (`SYNC_EVENTS == 0` or account sync
disabled), events stay on the phone only; the Reliability/Calendar settings row shows a hint. Each executed
op is logged as `calendar_event_written { op, kind, instance_key?, marker_key, cause, provider_rows }` and
each refused or failed op as `calendar_write_failed { op, error_class }` (16.7, 7.10; registry keys, codes
only, no titles).

### 16.9 Current-week behaviour (cutover case)

The current week's C (Tue), A (Thu) and TEST (Sun 13:00) events were created by hand and carry no marker.
`challenge.calendar` matches them to their slots, so the plan contains **no inserts** for those slots (no
duplicates) and offers "Adopt" for the future ones (A Thu, TEST Sun). Until the user adopts, the app reads
but never edits them; C36 conflicts (for example a postponed test) on unmarked events are surfaced as a
prompt to adopt or rename.

### 16.10 Sandbox local test calendar

The app can create a device-only calendar "FitnessApp TEST" using sync-adapter mode for `ACCOUNT_TYPE_LOCAL`
(allowed for local accounts), and redirect all writes there. As built in M2b.5 (D39): You → Calendars has "Create
test calendar"; in `debug` every write goes to it until the user switches on "Write to my own calendar" (stored on the
device, `LocalSettings.calendarRealWrites`, off by default), and the debug menu's `calendar_*` actions seed, edit and
dump test events (ANDROID 7 debug menu, `CalendarDebug`).
This lets Claude and the user test the write path without touching the real Google calendar. In `sandbox`
this redirection is fixed: calendar writes to the user's calendars are disabled (F27), and the executor's
target guard (16.7, guard 1) refuses any other calendar.

---

## 17. Home-habit reminders (D4, C34)

- Home habits (daily two-arm hang, one-arm plank, and any future habit) are defined by challenge
  definitions; `challenge.plan`/`challenge.materialize` produce dated habit instances (time in the
  profile zone, skip rules such as "no hang on A day", scaling such as "halved in the deload week").
  The Android layer schedules exactly what it is given.
- Reminders are enabled only after the D4 cutover has been confirmed (setting
  `habitRemindersEnabled`, default false until cutover; until then Todoist keeps the reminders and nothing
  is duplicated).
- Mechanism (two stages, changed 2026-10-21 after a measurement on the phone): OxygenOS turned
  `setExactAndAllowWhileIdle` into an inexact alarm that fired at the end of a window of 0.75 x the lead time,
  up to 1 h late, while `setWindow` and `setAlarmClock` kept their times. More than `reminders.preLeadMin`
  (15 min) ahead, a pre-alarm `setWindow(due - 15 min, 10 min)` wakes the app 15-5 min before the time and
  arms `setAlarmClock(due)`; closer than that, `setAlarmClock` directly. The alarm clock is never deferred;
  its "next alarm" icon shows only for those minutes, and tapping it opens the habit's mini-session.
  `setWindow(due, 10 min)` remains the fallback if the alarm clock is refused. Every recompute arms all
  reminders again, because a reboot, an update or a force stop drops the alarms but keeps `scheduled_alarm`.
- Horizon: the next `reminders.horizonH` (48 h) are scheduled; `daily-maintenance`, app start, boot,
  `TIME_SET`, `TIMEZONE_CHANGED`, package update and any challenge state change recompute the horizon and
  cancel obsolete PendingIntents (request code = 2000 + hash(reminderId) mod 1000, collision-checked).
- Due times are computed from `LocalDate + LocalTime` in the profile zone every day, so 17:15 stays 17:15
  across the DST change (golden G8). Travelling does not move reminders; changing the profile zone does.
- Notification (channel `habit_reminders`, `N-HABIT`): title "Two-arm hang", text "4–5 sets, 2–3 min
  total" (or the halved deload prescription; wording per UX §18), actions **Start** (route
  `fitapp://habit/<habit_id>`, opens the home session screen, `workout_sessions.kind = home`, C34),
  **Snooze 30 min**, **Skip today** (opens the skip-reason sheet, one tap). Completion is logged only
  through the session (holds use the D9 "Max / Hard / OK / Easy" chips), never by the notification; UX §18
  now agrees (no "Done" action).
- The same mechanism (exact while-idle alarm, 48 h horizon, profile-zone due times) delivers the other
  scheduled notifications of 13.1: the weigh-in reminder (`N-WEIGH`, channel `habit_reminders`) and the
  test-day morning notification (`N-TEST-DAY`, channel `challenge`); their timing rules are UX §18's.
- Events (registry names and keys, 7.10): each horizon recomputation that changes the schedule logs
  `reminder_scheduled { kind, count, horizon_h }` per kind (`habit`, `weigh_in`, `test_day`); each
  delivery logs `notification_posted { notification_id, channel, latency_ms, platform_config_hash }` with
  `latency_ms` = delivery time − due time; taps on actions log `notification_action { notification_id,
  channel, action }` (`start`, `snooze`, `skip_today`, `weigh_in`, `open`). The registry folded the
  earlier `reminder_delivered` / `reminder_action` names into these notification events.

---

## 18. Localisation and number format (D12)

- UI language: English only (`res/values/strings.xml`). No `values-nl` resources. Dutch appears only as
  data: calendar title patterns in classifier params and challenge definitions, and Dutch exercise names
  stored next to the English ones. Those names are shown and searched only when the first locale of the
  configuration has language `nl` (D12); `ui/common/Language.kt` holds the check.
- Numbers: always a decimal point, independent of the device locale (a Dutch device would otherwise
  format `42,8`). One `Numbers` helper in `:app` (UI) uses `DecimalFormatSymbols(Locale.US)`:
  `formatKg(42.8) = "42.8"`, trailing zeros trimmed to the equipment grid (`22.5`, `20`, `47.5`); weights
  shown with their unit ("BW + 22.5 kg", "Pin 40 lb"). Canonical formatting for hashing is separate and
  lives in `:algo` (C22).
- Input parsing is lenient: both `42.8` and `42,8` parse to 42.8 (a Dutch system keyboard shows a comma);
  inputs with more than one separator are rejected with the Reject haptic. The in-app keypad only offers
  a point.
- Units: kg everywhere; assisted-machine pins are shown and entered in their native unit (lb) and never
  converted in the input field; the computed assistance is shown as a secondary "≈ 24.6 kg assist" (C2:
  `assistKg = pin × 0.45359237 × ratio + offsetKg`, rounded to 0.01 kg, displayed with 1 decimal, C54).
- Dates and times: English month/day names ("Wed 7 Oct"), weeks start on Monday (F18), ISO week labels
  ("Week 41"). Clock format follows the system 24-hour setting (`DateFormat.is24HourFormat`); the
  Netherlands default is 24-hour ("19:45"). Rest countdowns `m:ss`, overtime `+m:ss`.
- All numeric text uses tabular figures (`fontFeatureSettings = "tnum"`).

---

## 19. Permissions matrix

| Permission | Kind | Why | When requested | If denied / unavailable |
|------------|------|-----|----------------|-------------------------|
| `INTERNET`, `ACCESS_NETWORK_STATE` | normal | Supabase sync; WorkManager network constraint | install | — |
| `POST_NOTIFICATIONS` | runtime, API 33+ | Session notification (rest countdown and overtime), reminders | Reliability setup step 1 (M1) | Session and alarms still run; vibration still alerts; no visible countdown; Reliability red; re-ask link |
| `FOREGROUND_SERVICE` | normal | Session service | install | — |
| `FOREGROUND_SERVICE_HEALTH` | normal | FGS type `health` (C28) | install | — |
| `ACTIVITY_RECOGNITION` | runtime, API 29+ (requested only on 34+) | Prerequisite for `health` FGS type; no activity data read | Reliability setup step 2 | `specialUse` FGS type (C28) |
| `FOREGROUND_SERVICE_SPECIAL_USE` | normal | Fallback FGS type | install | — |
| `USE_EXACT_ALARM` | normal (auto-granted, API 33+) | `setAlarmClock` rest ends, exact reminders | install | n/a (not revocable) |
| `SCHEDULE_EXACT_ALARM` (`maxSdkVersion=32`) | special, pre-granted on 31–32 | Same on API 31–32 | install | `INEXACT` mode + banner + settings intent |
| `POST_PROMOTED_NOTIFICATIONS` | normal, API 36+ | Live Update promotion | install | Standard ongoing notification |
| `VIBRATE` | normal | Rest-end waveform | install | — |
| `RECEIVE_BOOT_COMPLETED` | normal | Reschedule alarms and reminders after reboot | install | Alarms resume at next app start |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | normal | Show the system exemption dialog (D2) | Reliability setup step 4 | Instructions; Reliability amber |
| `READ_CALENDAR` | runtime | Planned sessions, anchors (C20) | Calendar onboarding step | Degraded mode without anchors + banner |
| `WRITE_CALENDAR` | runtime (same group) | Auto-write and "Plan it" (D11) | Same step, same dialog | Auto-write off + banner |
| `WAKE_LOCK` | normal (merged by WorkManager) | WorkManager | install | — |

Not requested: location, contacts, storage (exports use the Storage Access Framework), body sensors,
Health Connect (later milestone, F6), `USE_FULL_SCREEN_INTENT`, `SYSTEM_ALERT_WINDOW`, accounts. The
manifest declares `<queries>` for the OEM security-centre packages of 15.3 only.

---

## 20. Privacy and security

### 20.1 Keys and server access

- Only the publishable key is compiled into the app. No `service_role`/secret key exists anywhere in the
  repository, `local.properties`, CI secrets or the APK. A unit test (`NoSecretKeysTest`) scans the
  repository text files and the generated `BuildConfig` for `sb_secret_`, `service_role` and JWT-shaped
  strings in key fields and fails if found.
- All data access is through RLS-protected tables in `fitness` with grants to `authenticated` only (F1);
  the `anon` role has no grants. The chess app's tables are not reachable with the user's token because
  they grant nothing to `authenticated` (data-model research).
- Claude Code's review access goes through the Supabase MCP as the project owner, never through the app.

### 20.2 Transport and code

- `network_security_config.xml`: cleartext disabled for all domains; release builds trust system CAs only;
  no certificate pinning (Supabase certificate rotation would brick sync).
- Exported components: only `MainActivity` (launcher). Services, receivers and providers are
  `exported=false`. All PendingIntents are `FLAG_IMMUTABLE`.
- Release logging: no personal data (titles, notes, emails) in logcat; verbose logging only in `debug` and
  `sandbox`.
- No third-party SDKs that send data (no analytics, no crash reporting); crashes stay in `crash_log` and
  sync as `app_crash` events with stack hashes only.

### 20.3 Local database encryption and backup

- **No SQLCipher in v1** (F24). Rationale: Android file-based encryption plus the app sandbox protect the
  file at rest; SQLCipher adds a native library, key management (a lost key equals lost data) and
  migration complexity for a single-user app whose data is training logs. Revisit if the app gets
  multiple users on shared devices.
- **Android Auto Backup is off** (D16, F21). Supabase sync and the monthly JSON export (20.4) are the
  backups; a restored local database could be stale and conflict with sync, and no training data goes to
  the user's Google backup. Manifest of `:app`:

  ```xml
  <application
      android:allowBackup="false"
      android:dataExtractionRules="@xml/data_extraction_rules"
      ... >
  ```

  `allowBackup="false"` disables cloud backup on every API level and device-to-device transfer up to
  API 30. For apps targeting API 31+ it no longer stops device-to-device transfer, so
  `res/xml/data_extraction_rules.xml` excludes everything in both sections:

  ```xml
  <data-extraction-rules>
      <cloud-backup>
          <exclude domain="root" path="." />
          <exclude domain="file" path="." />
          <exclude domain="database" path="." />
          <exclude domain="sharedpref" path="." />
          <exclude domain="external" path="." />
      </cloud-backup>
      <device-transfer>
          <exclude domain="root" path="." />
          <exclude domain="file" path="." />
          <exclude domain="database" path="." />
          <exclude domain="sharedpref" path="." />
          <exclude domain="external" path="." />
      </device-transfer>
  </data-extraction-rules>
  ```

  (Verify the attribute behaviour and the XML against the official "Back up user data with Auto Backup"
  page at scaffold time.) Consequences: a new phone or a reinstall starts empty and restores through the
  bootstrap pull from Supabase (10.2) or, later, from an export file; the `device_id`, the auth session and
  the pre-migration copies can never be cloned onto another device. `BackupRulesTest` (Robolectric) asserts
  the merged manifest values and that the XML excludes every domain in both sections.

### 20.4 Exports (F4)

- "Export all data" writes one gzip-compressed JSON file through `ACTION_CREATE_DOCUMENT` (the user picks
  the location; no storage permission). Container: `.json.gz` (C40), default name
  `fitnessapp-export-2026-10-07.json.gz`. The JSON content and its version field (`schema_version:
  "fitness-export/1"`, canonical JCS bytes before compression, tables, metadata such as app version, Room
  schema version, `ENGINE_VERSION`, outbox depth and dead letters) are specified by DATA_MODEL §15.5 and
  not restated here; every synced and local-only table is included except the auth session, the outbox
  internals, the local `calendar_instance` cache (busy and unclassified calendar events never leave the
  phone, DATA_MODEL §16.1; classified events are exported through `calendar_events`) and the derived
  search index `exercise_fts` (DATA_MODEL §15.5). Each export also writes an `exports` row (DATA_MODEL §9.8.3) with `format = 'json_gz'` (C40)
  and the matching `export_kind` (`manual_full`, `monthly_full`, `review_bundle`), and one
  `export_completed { export_id, kind, rows, bytes, from?, to?, notes_included? }` event (the registry
  event that replaced UX's `data_exported` / `review_bundle_exported`, 7.10).
- "Export review bundle" (F19 trace export) writes recommendations, session_events and the related facts
  for a chosen date range (content per DATA_MODEL §15.5), in the same `.json.gz` container, for Claude Code
  reviews and `:sim` replay.
- Monthly reminder: `daily-maintenance` posts an `app_status` notification when the last export is older
  than `export.reminderDays` (30, F4).
- Import ("Restore from file") is a later milestone; until then the full pull from Supabase is the restore
  path.

### 20.5 Disclaimer

Safety rules are enforced by the engine (C33, D10); the app shows the "not medical advice" disclaimer in
onboarding and in the pain-report sheet (content owned by the safety spec).

---

## 21. Testing strategy

### 21.1 Layers

| Layer | Tooling | Runs | Scope |
|-------|---------|------|-------|
| Unit (`:algo`, `:sim`) | JUnit Jupiter + kotest assertions | `:algo:test`, `:sim:test` | Every registry module; seconds. |
| Property | kotest-property `checkAll` inside JUnit tests | same | Determinism (same input → identical bytes), grid rounding, LOAD invariants, reducer invariants (21.3). |
| Golden | JSON fixtures in `fixtures/golden/` and `fixtures/android/`; `-PupdateGoldens` rewrites expected files; diffs reviewed in git | `check` | Engine cases and the platform fixtures of section 24. |
| Purity | `AlgoPurityTest`, `verifyAlgoDependencies` | `check` | Section 6.3. |
| `:data` | JUnit + Robolectric (Room in-memory), Ktor `MockEngine` for PostgREST, `work-testing` `TestDriver` | `:data:testDebugUnitTest` | DAOs, outbox ordering, LWW merge, error classification, migrations, DTO drift. |
| `:app` logic | JUnit + fakes (`FakeAlarmGateway`, `FakeCalendarGateway`, `FakeNotificationPresenter`, `FakeClock`) | `:app:testDebugUnitTest` | Rest timer controller, calendar executor guards, reminder scheduler, FGS type selection, number formatting. |
| UI screenshots | Robolectric + Roborazzi (+ `compose-ui-test`) | `:app:verifyRoborazziDebug` | Today, Live session (set, rest, overtime, unilateral, hold), Week, Reliability, Sync status; light/dark; font scale 1.0/1.3/2.0; widths 360 dp and 411 dp. |
| Simulation | `:sim` CLI | `.\gradlew.bat :sim:run --args="…"` | Long synthetic histories and replay (owned by the simulation spec). |
| Device | Determinism self-test + checklist 15.6 | manual, per milestone | Platform reality. |

### 21.2 Runner setup

- `:algo`, `:sim`: JUnit Platform with Jupiter.
- `:data`, `:app`: JUnit Platform with Jupiter + vintage engine; Robolectric/Roborazzi tests are JUnit 4
  classes (`@RunWith(AndroidJUnit4::class)`), plain logic tests are Jupiter.
- Coroutines: `kotlinx-coroutines-test` (`runTest`, `StandardTestDispatcher`); Flows with Turbine.
- No instrumented (`androidTest`) tests in v1: there is no emulator, and the phone is the user's daily
  device.

### 21.3 Property tests specific to this document

| Property | Generator |
|----------|-----------|
| Rest reducer: `end_wall ≤ start_wall + hardMax*1000` after any sequence of adjusts | random sequences of ±15 adjusts at random times |
| Rest reducer: after any adjust, `end_wall ≥ now + minLeft*1000` | same |
| Rest reducer is a pure function: same event script → same state and effects | random scripts, run twice |
| `Numbers.parse(Numbers.formatKg(x)) == x` for x on the 0.01 grid in [0, 500] | grid values |
| Profile-zone conversion: `LocalDateTime → UTC → LocalDateTime` round trip for non-gap times in 2026–2027 | random date-times in Europe/Amsterdam |
| Marker `parse(render(uuid, slotKey)) == (uuid, slotKey)`; `likeEscape` output matches only its own marker | random UUIDv7 and slot keys from the C37 class `[A-Za-z0-9_./-]{1,64}` (upper-case letters and `_` included) |
| Outbox: an entity edited while its batch is in flight is still pushed afterwards (`(seq, gen)` rule, 8.3) | random interleavings of edits and push completions |
| LWW merge is idempotent and order-independent: `merge(a, merge(a, b)) == merge(a, b)`, `merge(a, b) == merge(b, a)` | random `(client_updated_at, device_id)` tuples incl. ties |
| Outbox push order: no child row is pushed in a batch before its parent's table batch | random enqueue orders |

### 21.4 Test naming

`<Subject>Test` classes, method names in backticks describing behaviour, for example
``fun `adjust never moves the end beyond the hard max`()``. Golden tests are named `G<n>_<topic>Test`
(section 24).

### 21.5 Release smoke test (before a release build becomes the daily install)

Install over the existing app (`installRelease`, same signature), open Today, start and abandon a sandbox
session (debug menu "dry session" in release is not available, so use a real 2-set session), run one
30 s rest with the screen off, finish, check the sync chip turns "Synced", check `adb logcat` for R8-related
`ClassNotFoundException`/`SerializationException`.

---

## 22. CI and distribution (F17)

### 22.1 Everyday install

`.\gradlew.bat :app:installDebug` over adb Wi-Fi (F17). The debug build is signed with the release key
(5.3), so it updates the installed app in place. Claude never runs `adb uninstall dev.fitnessapp` or
`adb shell pm clear dev.fitnessapp`; if an install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
(signature mismatch) or a downgrade error, Claude stops and reports.

### 22.2 Optional GitHub Actions + Obtainium (later)

Prerequisites (user actions): a private GitHub repository; repository secrets
`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, `SUPABASE_URL`,
`SUPABASE_PUBLISHABLE_KEY` entered by the user in the GitHub UI; Obtainium installed on the phone with a
GitHub token for the private repository.

```yaml
name: ci
on: { push: { branches: [main], tags: ['v*'] }, pull_request: {} }
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4            # pin to SHAs at setup time
        with: { fetch-depth: 0 }             # versionCode = commit count
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew check :app:compareRoborazziDebug   # compare only; baselines are recorded on Windows
      - if: startsWith(github.ref, 'refs/tags/v')
        run: |
          echo "$RELEASE_KEYSTORE_BASE64" | base64 -d > $RUNNER_TEMP/release.jks
          ./gradlew :app:assembleRelease \
            -PFITNESS_RELEASE_STORE_FILE=$RUNNER_TEMP/release.jks \
            -PFITNESS_RELEASE_STORE_PASSWORD=$RELEASE_STORE_PASSWORD \
            -PFITNESS_RELEASE_KEY_ALIAS=fitnessapp \
            -PFITNESS_RELEASE_KEY_PASSWORD=$RELEASE_KEY_PASSWORD
      - if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2   # attach app-release.apk
```

Screenshot baselines are recorded on Windows; Linux font rendering can differ slightly, so CI only
compares (reports) and does not fail on screenshot diffs. The release APK from CI is signed with the same
key as local builds, so Obtainium updates replace the installed app in place.

### 22.3 Sideloading policy

Android developer verification is enforced only in a few countries from 2026-09-30 and globally "2027 and
beyond" (research, moderate). ADB installs are reported to remain possible. Before global enforcement,
register a free limited-distribution developer account (user action) so Obtainium installs keep working.

---

## 23. M0 bootstrap checklist

"Who" = **C** (Claude runs it), **U** (user does it), **C+U** (Claude runs it after the user's explicit OK
in chat, for downloads, licence-agreement flags or anything changing the phone's state). Commands are
PowerShell unless marked. `$proj = "C:\Users\sivuc\projects\FitnessApp"`, `$boot = "$env:TEMP\fitnessapp-bootstrap"`,
`$sdk = "$env:LOCALAPPDATA\Android\Sdk"`.

| # | Who | Step | Command / action | Done when |
|---|-----|------|------------------|-----------|
| 1 | C | Read-only environment check | `java -version; Get-Command java; winget --version; git --version; Test-Path "C:\Program Files\Eclipse Adoptium"; Test-Path $sdk` | Output recorded in the M0 log |
| 2 | C+U | Install Temurin 21 (core feature only; UAC prompt approved by the user) | `winget install --id EclipseAdoptium.Temurin.21.JDK --exact --source winget --override "/quiet /norestart ADDLOCAL=FeatureMain" --accept-package-agreements --accept-source-agreements` | `& "$jdk21\bin\java.exe" -version` shows 21; `java -version` still shows 25.0.3 |
| 3 | C+U | Download Gradle 9.8.0 bootstrap distribution (about 140 MB, services.gradle.org) | Section 3.3 commands | SHA-256 check prints `True` |
| 4 | C | Scaffold the repository (section 4): settings, root build, catalog, `build-logic`, module skeletons, `.gitignore`, `.gitattributes`, README, CLAUDE.md, empty `fixtures/`, `supabase/migrations/` | Write files | Files exist |
| 5 | C | Generate the wrapper; pin the daemon JVM | Section 3.3 `gradle wrapper …`; then `.\gradlew.bat updateDaemonJvm --jvm-version=21 --jvm-vendor=adoptium`; `.\gradlew.bat --version` | `gradle/gradle-daemon-jvm.properties` exists; daemon reports Java 21 |
| 6 | C+U | Download cmdline-tools (155.7 MB, dl.google.com) and lay out `cmdline-tools/latest` | Section 3.4 commands | `sdkmanager --version` prints a version |
| 7 | U | Accept SDK licences in own terminal | Section 3.5 command | `$sdk\licenses\android-sdk-license` exists |
| 8 | C+U | Install SDK packages (about 200 MB) | `& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" --install "platform-tools" "platforms;android-36" "build-tools;36.0.0"` | `--list_installed` lists all three |
| 9 | C | Write `local.properties` (`sdk.dir`, `supabase.url`, `supabase.publishableKey` fetched via Supabase MCP `get_publishable_keys`, not echoed) | Write file | File git-ignored |
| 10 | C | Verify catalog versions on Maven Central / Google Maven; fill `libs.versions.toml` | Web lookups | No `<verify>` left |
| 11 | C | First build and tests: `:algo` hello-world module with `ENGINE_VERSION`, `AlgoPurityTest`, `verifyAlgoDependencies`; `:app` empty Compose screen; first Roborazzi test | `.\gradlew.bat check`; `.\gradlew.bat :app:recordRoborazziDebug` | Green; PNG readable by Claude |
| 12 | U | Create release keystore; add 4 lines to `%USERPROFILE%\.gradle\gradle.properties`; back up `.jks` + password (two places + password manager) | Section 5.4 | User confirms in chat |
| 13 | U | Phone: Developer options, Wireless debugging, pair (`adb pair`); Xiaomi: "Install via USB" + "USB debugging (Security settings)" | Section 3.6 | `adb pair` reports success |
| 14 | C | Connect and read device facts (closes D2's model/Android-version question) | `adb mdns services; adb connect <ip:port>; adb devices -l;` getprop commands of 3.6 | Model, Android version, SDK recorded in DECISIONS changelog proposal (OI-01) |
| 15 | C+U | Install and launch the skeleton app, after the user has confirmed the application id `dev.fitnessapp` once more in chat (F22: changing it later means a new app) | `.\gradlew.bat :app:installDebug`; `& $adb shell am start -n dev.fitnessapp/dev.fitnessapp.app.MainActivity`; screenshot (3.7) | Screenshot shows the skeleton |
| 16 | C | Determinism self-test on the phone (with the first golden fixture) | Section 6.3 item 4 | Hashes equal |
| 17 | C | Supabase pre-checks (read-only): `get_project`, `list_tables` for `fitness*`, PGRST106 probe | Per F2/C25 and the data-model spec | Current exposed schemas recorded (`public, graphql_public`) |
| 18 | U | Review migrations 001–004 (schemas, grants, RLS template, LWW trigger, reference tables) | Read the SQL files | User approves each in chat |
| 19 | C | Apply each approved migration through Supabase MCP `apply_migration`; run `get_advisors` (security, performance) | Per F3, C60 | Zero new fitness findings except the C60 exceptions (INFO `unused_index` on new fitness indexes during their first 8 weeks; the security-definer lint on `fitness_private.is_allowed()`) |
| 20 | U | Dashboard → Data API → Exposed schemas: add `fitness`, keep `public`, `graphql_public` | Per F2, C25 | — |
| 21 | C | Post-probe: the PGRST106 hint lists `fitness`; sign in from the skeleton app's debug menu and run the access check (10.4 read probe) | Per F2 | The probe returns one `muscle_groups` row |
| 22 | U | Optional: confirm `pg_jsonschema` install (F5) | Chat | Decision recorded |
| 23 | C+U | `git init`, first commit (after the user asks for the commit) | `git init; git add -A; git commit` with the attribution lines required by the session | Repository has history (versionCode > 0) |
| 24 | C | Write the M0 log (what was installed where, versions, device facts, certificate fingerprint) into `README.md` | Edit | Done |

Nothing in M0 modifies PATH, JAVA_HOME, system environment variables, Windows security settings or phone
system settings on Claude's side.

---

## 24. Golden fixtures / tests

All fixtures use the real week: today **Wednesday 2026-10-07**, ISO week 2026-W41 (Mon 2026-10-05 – Sun
2026-10-11), profile zone Europe/Amsterdam (CEST, UTC+2, until 2026-10-25 03:00 CEST → 02:00 CET). Calendar:
C Tue 2026-10-06 19:45, A Thu 2026-10-08 19:45, TEST Sun 2026-10-11 13:00 (OAP deload week, block 1
week 5). BW 70.7 kg. Weighted pull-up loads C 20 / A 22.5 / B 27.5 kg; weighted negatives 47.5 kg;
assisted pins A 40 lb / C 50 lb; 90-degree hold 35 lb. The C and A events (19:45–21:15) are training
time, so the engine's session budget is 90 − 5 = 85 min (F11 revised, C49). Marker fixtures use the
DATA_MODEL §19.6 fixture enrollment id `01a115ce-3d00-7000-8000-000000000001` (a UUIDv7). All epoch values
below were computed with `java.time` (tzdb) and are exact.

### 24.1 Reference instants

| Label | Local | UTC | Epoch ms |
|-------|-------|-----|----------|
| W41 start | Mon 2026-10-05 00:00 +02:00 | 2026-10-04T22:00:00Z | 1791151200000 |
| Today start | Wed 2026-10-07 00:00 +02:00 | 2026-10-06T22:00:00Z | 1791324000000 |
| C start / end | Tue 2026-10-06 19:45 / 21:15 +02:00 | 17:45Z / 19:15Z | 1791308700000 / 1791314100000 |
| A start / end | Thu 2026-10-08 19:45 / 21:15 +02:00 | 17:45Z / 19:15Z | 1791481500000 / 1791486900000 |
| TEST start / end (2 h assumed for the fixture) | Sun 2026-10-11 13:00 / 15:00 +02:00 | 11:00Z / 13:00Z | 1791716400000 / 1791723600000 |
| W42 start | Mon 2026-10-12 00:00 +02:00 | 2026-10-11T22:00:00Z | 1791756000000 |
| W43 start | Mon 2026-10-19 00:00 +02:00 | 2026-10-18T22:00:00Z | 1792360800000 |
| W44 start | Mon 2026-10-26 00:00 +01:00 | 2026-10-25T23:00:00Z | 1792969200000 |
| Read window | [2026-09-28 00:00 +02:00, 2026-10-19 00:00 +02:00) | [2026-09-27T22:00Z, 2026-10-18T22:00Z) | [1790546400000, 1792360800000) |

W43 is 169 hours long (608 400 000 ms) because it contains the DST end.

### 24.2 Fixture files and tests

| Id | File(s) | Test class | Asserts |
|----|---------|-----------|---------|
| G1 | `fixtures/android/calendar/2026-W41.provider.json` (raw provider rows: primary-calendar C, A, TEST events with the user's Dutch titles "Gym — sessie C (volume en techniek)", "Gym — sessie A (één arm zwaar)", "Gym — testdag 1 (meten)", location "Basic-Fit"; a Todoist-calendar all-day "Gym" row; one synthetic busy event, marked `"synthetic": true`) | `G1_CalendarReadMappingTest` | (a) the Instances query uses the read window `[1790546400000, 1792360800000)` and selects only the primary calendar id, so the Todoist row is never read; (b) the rows map to `CalendarInstanceRow` with the begin/end epochs of 24.1; (c) the strings reach `:algo` byte-identical (UTF-8, "één" preserved, em dash preserved); (d) `calendar_classified` events for the busy row contain no title. |
| G2 | `fixtures/android/calendar/2026-W41-W42.writeplan.json` (input ops as `challenge.calendar` would emit them) | `G2_CalendarWriteExecutorTest` | W41: zero inserts (slots covered by unmarked events), "Adopt" suggestions for A Thu and TEST Sun only (C Tue is past). W42 example inserts (times assume the OAP definition's preferred schedule C Wed 19:45, A Fri 19:45, B Sun 11:00; authoritative times come from the definition): C `DTSTART 1791999900000`, A `1792172700000`, B `1792314000000`; each with `EVENT_TIMEZONE = "Europe/Amsterdam"`, `HAS_ALARM = 0`, `AVAILABILITY_BUSY`, description ending in a marker matching the regex of 16.5 with the fixture enrollment id and slot keys `oap/C/2026-W42`, `oap/A/2026-W42`, `oap/B/2026-W42` (`[fitapp:v1 01a115ce-3d00-7000-8000-000000000001 oap/C/2026-W42]`, C37). An update op whose target carries the same enrollment id but another slot key, or no marker, affects 0 rows and is logged `calendar_write_failed { error_class: "marker_missing" }`. A delete op for a past event is refused (`error_class: "delete_guard"`). With `USER_CALENDAR_WRITES_ENABLED = false` (sandbox) every op targeting the primary calendar is refused (`error_class: "sandbox"`). A provider exception during `applyBatch` leaves the calendar unchanged and is logged `calendar_write_failed { error_class: "provider_error" }`; only executed ops produce `calendar_event_written`. |
| G3 | `fixtures/android/timer/session-A-2026-10-08.script.json` | `G3_RestTimerReducerTest` | Set logged at 2026-10-08T17:58:00Z (`1791482280000`, 19:58 local), prescription "BW + 22.5 kg × 4" next, `d = 180 s` → `end_wall = 1791482460000` (18:01:00Z); `setAlarmClock` requested with that trigger; notification `when = 1791482460000`, countdown true, actions −15/Skip/+15. Then +15 → `1791482475000`; −15 → `1791482460000`; −15 at `now = 1791482454000` (6 s left) → `max(…, now + 5000) = 1791482459000`; twenty +15 adjustments are capped at `start + 900 s = 1791483180000`. A long-press step of −60 is accepted; steps of +20 or +75 are rejected (not a multiple of 15, or above `timer.longPressStepS`). Skip → `ended_by = skipped`, both alarms cancelled, `rest_skipped` and `rest_ended { end_reason: "skipped" }` events; the next set's row gets `rest_timer_planned_s = 180`, `rest_adjust_s` = the net adjustment, `rest_timer_overridden = true`, `rest_end_reason = "skipped"` (C55). A second script finishes the session while a rest is running: `ended_by = session_finished`, `rest_ended { end_reason: "session_finished", rest_source: "measured" }`, no `set_logs` write. Events and effects match the expected list byte for byte. |
| G4 | `fixtures/android/timer/dst-2026-10-25.script.json` | `G4_RestTimerDstTest` | Start at 02:59 CEST = `1792889940000` (00:59:00Z), 180 s → `end_wall = 1792890120000` (01:02:00Z), local display "02:02" (CET); `remainingMs` with the same boot count is computed from elapsed time and equals 180 000 at start regardless of a simulated wall-clock jump. Also: the ambiguous local time 02:30 on 2026-10-25 resolves to the earlier offset (+02:00, `1792888200000`) as `java.time` does by default. |
| G5 | `fixtures/android/timer/process-death.script.json` | `G5_RestTimerRecoveryTest` | Timer started 18:00:00Z with 180 s; recovery at 18:02:00Z (same boot) reschedules the alarm at 18:03:00Z and reposts; recovery at 18:04:00Z with no fired alarm sets `elapsed_while_dead`, `missed_by_ms = 60000`, no catch-up vibration (≤ 60 s rule: vibration only if missed by ≤ 60 s → here exactly 60 000 ms, boundary: vibrate); recovery after a reboot (different boot count) uses wall time. |
| G6 | inline table | `G6_FgsTypeSelectionTest` | `(sdk 33, any) → null`; `(34, granted) → HEALTH`; `(34, denied) → SPECIAL_USE`; `(36, granted) → HEALTH`; `(37, denied) → SPECIAL_USE`. |
| G7 | inline table | `G7_NumberFormatTest` | Default locale forced to `nl-NL`: `formatKg(70.7) = "70.7"`, `20.0 → "20"`, `22.5 → "22.5"`, `27.5 → "27.5"`, `47.5 → "47.5"`; `parseKg("42,8") = 42.8`, `parseKg("42.8") = 42.8`, `parseKg("1.234,5")` rejected. Assisted display (C2, offset 6.5 kg, ratio 1.0): pin 40 lb → assist 24.64 kg → "Pin 40 lb (≈ 24.6 kg assist)"; pin 50 lb → 29.18 kg → "≈ 29.2 kg"; hold pin 35 lb → 22.38 kg → "≈ 22.4 kg". Weighted: "BW + 20 kg", "BW + 22.5 kg", "BW + 27.5 kg", negatives "BW + 47.5 kg". Pins are never shown converted in the input field. |
| G8 | `fixtures/android/reminders/dst-hang.json` | `G8_ReminderScheduleTest` | Daily hang at 17:15 profile time: 2026-10-24 → `1792854900000` (15:15Z), 2026-10-25 → `1792944900000` (16:15Z), 2026-10-26 → `1793031300000` (16:15Z). Given a habit plan without a Thu 2026-10-08 hang instance (A day), no alarm is scheduled that day; reminders disabled before cutover → zero alarms. |
| G9 | `fixtures/android/sync/lww-cases.json` | `G9_LwwMergeTest` | Cases: remote newer → overwrite; local newer and pending → keep local; equal `client_updated_at` (2026-10-07T18:00:00.000Z) with device ids `"a1…"` vs `"b2…"` → `"b2…"` wins on both client and (documented) server; append-only duplicates ignored. |
| G10 | `fixtures/android/sync/outbox-order.json` | `G10_OutboxOrderTest` | Enqueue `set_logs` before `workout_sessions` for the same session → push sends `workout_sessions` first; batches ≤ 200; a batch with one `23514` row is bisected and only that row is dead-lettered. |
| G11 | inline | `G11_MarkerTest` | `render(0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b, "oap/C/2026-W42") = "[fitapp:v1 0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b oap/C/2026-W42]"` (same vector as CHALLENGE_ENGINE GF-CE-27); parse round trip, upper-case slot letters parse (C37); `gen/2026-W42/5` parses; the marker is found on the last non-empty line even when trailing blank lines or spaces follow it; a lower-case UUID of another version also parses (the shared pattern does not check the version digit) while an upper-case UUID does not; a key with a space or longer than 64 characters does not parse; LIKE guard string `"%[fitapp:v1 0199c3a0-5d2e-7b41-8f3a-1c2d3e4f5a6b oap/C/2026-W42]%"`, and for a key containing `_` the `_` is escaped as `\_`; a description containing a different uuid or a different slot key of the same uuid does not match. |
| G12 | inline | `G12_WeekBoundaryTest` | ISO week of 2026-10-07 = 41, of 2026-10-25 = 43 (a Sunday); week starts as in 24.1; W43 length 169 h; current + next week for 2026-10-07 = {W41, W42}; for Sun 2026-10-11 23:30 = {W41, W42}; for Mon 2026-10-12 00:00 = {W42, W43}. |
| G13 | `fixtures/golden/*.json` | `DeterminismSelfTest` (device) + `:algo:test` | JVM and ART produce identical output hashes for every golden case. |
| G14 | Roborazzi | `G14_ScreenshotTest` | Today (Wed 7 Oct state: A tomorrow, TEST Sunday), Live session rest card (A Thu, next set "BW + 22.5 kg × 4", 2:18 remaining), unilateral assisted card (Pin 40 lb), Reliability screen with one amber row; light and dark; font scale 1.0, 1.3, 2.0. |

The DST fixture week (W43, Sunday 2026-10-25) is also where the next B session falls (block 2 week 2);
a B write at Sun 2026-10-25 11:00 → `DTSTART 1792922400000` (10:00Z, CET) is part of G2's DST variant.

---

## 25. Constants register

All constants of this document live in `PlatformConfig` in `:app`, outside the engine params JSON and
outside `params_hash` (C54). This register is authoritative for timer, notification, reminder and
sync-backoff constants; UX and DATA_MODEL reference it. The keys are platform-config keys, not params
keys, so the C41 params-key convention does not apply and the names below are kept (UX already uses
them). The full set is logged as `platform_config_logged { platform_config_hash, values }` whenever it
changes, and the main platform events carry the hash (7.10, C54). Constants owned by algorithm modules (REST-1
durations and hard max, TIME-1, classifier weights) are referenced, not duplicated. Notification channel
ids and importances (13.1) and notification ids (13.2) are part of the same authoritative set.

### 25.1 Rest timer and session

| Key | Default | Bounds | Grade | Source | Tunable? Tuned by |
|-----|---------|--------|-------|--------|-------------------|
| `timer.adjustStepS` | 15 s | 5–60 | expert-opinion | Hevy convention (ux research), C28 actions | Yes: size/frequency of `rest_adjusted` per exercise |
| `timer.longPressStepS` | 60 s (a multiple of `adjustStepS`) | 30–120 | expert-opinion | UX 19.8 (`LONG_PRESS_STEP_S`), moved here as a timer constant (C54) | Yes: long-press usage followed by corrections |
| `timer.minLeftAfterAdjustS` | 5 s | 1–15 | assumption | tech-stack research (ux research used 1 s) | Yes: `rest_adjusted` followed by `rest_skipped` within 5 s |
| `timer.minTimerS` | 10 s | 0–30 | assumption | — | Yes: share of very short between-sides rests |
| `timer.prewarnS` | 10 s (UI in the foreground only, C54) | 0–30 | expert-opinion | ux/tech-stack research | Yes: user feedback; overtime when foreground |
| `timer.overtimeReminderS` | 60 s (0 = off) | 0, 30–300 | expert-opinion | ux research REMIND_S | Yes: overtime distribution with screen off |
| `timer.catchUpAlertMaxS` | 60 s | 0–120 | assumption | 12.6 (an end alarm delivered to a cold process after reconcile already ran) | Yes: `rest_recovered.missed_by_ms` distribution |
| `timer.vibrationWaveformMs` | [0, 300, 150, 300, 150, 600] | each 0–1000 ms | expert-opinion | ux research | Yes: proxy "overtime > 30 s with screen off" (missed alerts) |
| `timer.exactIdleMinSamples` | 30 alarms | 10–100 | assumption (value confirmed by C54) | C28 needs logged p95 | Yes |
| `timer.exactIdleP95MaxMs` | 2000 ms | fixed | canonical | C28 | No |
| `timer.exactIdleRevertWindow` | 20 alarms | 10–50 | assumption | — | Yes |
| `reliability.latencyAmberMs` / `RedMs` | 2000 / 5000 ms | — | assumption (amber derived from C28) | — | Yes: latency distribution |
| `session.idlePromptMin` | 60 min | 20–180 | assumption | — | Yes: gaps between `session_events` |
| `session.idleStopFgsMin` | 240 min | 60–600 | assumption | — | Yes: same |
| `ui.doubleTapDebounceMs` | 300 ms | 100–600 | moderate | Wet-finger touch research (ux research) | Yes: duplicate action events |
| `ui.assistKgDisplayDecimals` | 1 | fixed | canonical | C54 (18; storage stays on the 0.01 kg grid, C22) | No |
| `tone.sampleMs` | ≈ 600 ms | 200–1500 | assumption | — | User feedback |

### 25.2 Calendar

| Key | Default | Bounds | Grade | Source | Tunable? |
|-----|---------|--------|-------|--------|----------|
| `calendar.readWindowBeforeDays` | 7 | 0–14 | expert-opinion | data-model research S8 | Yes: link misses for late-logged sessions |
| `calendar.readWindowAfterDays` | 14 | 14–28 | expert-opinion | S8; must cover the next ISO week | Yes |
| `calendar.observerDebounceMs` | 2000 ms | 500–10 000 | assumption | tech-stack research | Yes: refresh count vs staleness |
| `calendar.dailyRefreshLocalTime` | 05:00 | — | assumption | tech-stack research | Yes |
| `calendar.writeWeeks` | current + next ISO week | fixed | canonical | D11 | No |

### 25.3 Reminders, export, database

| Key | Default | Bounds | Grade | Source | Tunable? |
|-----|---------|--------|-------|--------|----------|
| `reminders.horizonH` | 48 h | 24–96 | assumption | — | Yes: missed reminders after long offline periods |
| `reminders.snoozeMin` | 30 min | 10–60 | assumption | — | Yes: snooze usage |
| `reminders.preLeadMin` | 15 min | 11–30 | measured on OxygenOS (exact alarms deferred up to 1 h) | — | Yes: `notification_posted.latency_ms` of N-HABIT |
| `reminders.inexactWindowMin` | 10 min | 5–30 | assumption | — | Yes: `latency_ms` of reminder `notification_posted` events |
| `export.reminderDays` | 30 | 7–90 | DEFAULT | F4 | No: the reminder itself is a local on/off setting (UX 15.11, default on) |
| `db.preMigrationBackupsKept` | 3 | 1–10 | assumption | — | No data needed |

### 25.4 Sync

`sync.staleNoticeDays` (7 days, bounds 3-30, assumption, UX 18 N-SYNC; tuned by how often the notice is followed by a
successful manual sync) posts N-SYNC when the oldest outbox entry is that old; dead letters post it at once.

| Key | Default | Bounds | Grade | Source | Tunable? |
|-----|---------|--------|-------|--------|----------|
| `sync.pushBatchRows` | 200 | 50–500 | expert-opinion | research; PostgREST statement timeout 8 s | Yes: `sync_run` duration, timeouts |
| `sync.pullPageRows` | 500 | ≤ 1000 | engineering-fact bound | PostgREST `max_rows` 1000 | Yes |
| `sync.overlapS` | 120 s | fixed | canonical | C23 | No |
| `sync.debounceS` | 3 s | 0–30 | assumption | — | Yes: runs per session |
| `sync.backoffInitialS` | 30 s | 10–300 | assumption | tech-stack research (WorkManager min 10 s, max 5 h) | Yes: time-to-drain after outages |
| `sync.periodicH` | 6 h | 1–24 | assumption | tech-stack research | Yes |
| `sync.fkRetryAttempts` | 5 | 1–10 | assumption | — | Yes: `23503` frequency |
| `sync.schemaDriftRetryMin` | 60 min | 15–1440 | assumption | DATA_MODEL §11.8 ("retry hourly") | Yes: time from a migration to the first successful push |
| `sync.unreachableBannerH` | 24 h | 6–72 | assumption | F4 pause risk | Yes |
| `sync.pendingLongH` | 24 h | 6–72 | assumption | UX 7.1 "Pending long": an outbox entry waiting this long while online is shown; younger entries are not (each sync run queues its own `sync_run` event) | Yes |

### 25.5 Hold timer and session display (registered from UX, C54)

UX §19.8, §19.9 and §11.3 specify the behaviour that uses these constants and asked for them to be
registered here; the values below are UX's, unchanged. UX names them in upper snake case inside
`RestTimerConfig` / `HoldTimerConfig`; in code they are groups of the one `PlatformConfig` (C54) under
the keys below (OI-33). The telemetry-writer, accessibility and interaction constants of UX §19.10
(`TelemetryConfig` and others) are UI constants owned by UX; they are also outside `params_hash` and are
not restated here.

| Key | Default | Bounds | Grade | Source | Tunable? Tuned by |
|-----|---------|--------|-------|--------|-------------------|
| `hold.leadInS` | 5 s (options 3, 5, 10) | 3–10 | expert-opinion | UX 19.9 `LEAD_IN_S` | User setting; `hold_lead_in_cancelled` rate |
| `hold.beepEveryS` | 5 s | 1–10 | expert-opinion | UX 19.9 `BEEP_EVERY_S` | User feedback |
| `hold.maxHoldS` | 300 s | 60–600 | assumption | UX 19.9 `MAX_HOLD_S`, 11.7.3 | Yes: auto-stops that the user corrects |
| `hold.stopGuardMs` | 500 ms | 200–1500 | assumption | UX 19.9 `STOP_GUARD_MS` | Yes: holds stopped within 1 s and then corrected upwards |
| `hold.reactionOffsetS` | 0.0 s | 0.0–2.0 | assumption | UX 19.9 `REACTION_OFFSET_S` | Yes: mean of `hold_timer_s − duration_s` over max holds (`hold_corrected`) |
| `hold.countAloudMaxS` | 30 s | 10–60 | assumption | UX 19.9 `COUNT_ALOUD_MAX_S` | No data needed |
| `hold.eccPaceMaxS` | 30 s | 10–60 | assumption | UX 19.9 `ECC_PACE_MAX_S` | No data needed |
| `timer.switchHintMs` | 1500 ms | 800–3000 | assumption | UX 19.8 `SWITCH_HINT_MS` | Yes: time from the left-side log to the first right-side touch |
| `timer.chipTickMs` | 1000 ms | 500–5000 | engineering-fact | OnePlus CPH2747 (OxygenOS, Android 16) draws the Live Update chip from the title / `shortCriticalText`, not the chronometer (2026-10-11 screenshot) | No: reposts only while the screen is on |
| `timer.focusModeDefault` | off | — | expert-opinion | UX 19.8 `FOCUS_MODE_DEFAULT` | User setting |
| `session.overrunLineMin` | 10 min | fixed | canonical | C63 (UX 11.3 overrun line) | No: a different margin needs a change of C63 first |

---

## 26. Open issues

Items this document needed but DECISIONS.md does not decide, or where alignment with another spec is
required. Each has a proposed default used in this document until the user or the owning spec decides.
Items closed by the 2026-10-07 decisions are kept for traceability and marked "Resolved by …".

| Id | Status | Issue | Proposed default | Needs |
|----|--------|-------|------------------|-------|
| OI-01 | Resolved by F23 (SDK levels); the phone part stays open (D2) | SDK levels were not in DECISIONS.md, and D2's phone model/Android version is still unknown. | `minSdk 26`, `compileSdk = targetSdk = 36` (F23); raise to 37 after M0 step 14 confirms the phone runs Android 17 and T15 passes. | Phone facts at M0 step 14. |
| OI-02 | Resolved by F22 | The `applicationId` is irreversible (changing it later means a new app and a data-wiping migration). | `dev.fitnessapp` (+ `.sandbox` for the sandbox build). | Confirm once more before the first install (F22). |
| OI-03 | Resolved by F27 | F17 did not say which build is the daily driver. | `debug` and `release` share the id and the release key; `installDebug` is the everyday path; `sandbox` is separate (implemented as a build type, 5.3). | — |
| OI-04 | Resolved by F24 | Local DB encryption was not decided. | No SQLCipher in v1 (20.3). | — |
| OI-05 | Resolved by D16 / F21 | Android Auto Backup was not decided. | Off: `allowBackup="false"`, data-extraction rules exclude everything (20.3). | — |
| OI-06 | Resolved by C54 | Platform constants (section 25) are not registry modules. | `PlatformConfig` outside `params_hash`, logged in event payloads (7.10, 25). | — |
| OI-07 | Resolved by C37 | The marker line format had to be shared with the `challenge.calendar` spec. | `[fitapp:v1 <uuid> <slotKey>]`, uuid = enrollment or recommendation id (16.5). | — |
| OI-08 | Resolved by F25 | Language of calendar titles the app writes. | Definition template (Dutch for OAP), English general labels and descriptions (16.7). | — |
| OI-09 | Resolved by C37 | D11 did not define what happens when the user edits an app-written event. | Field-ownership policy of 16.7, now canonical with this document as owner. | — |
| OI-10 | Resolved by DATA_MODEL §5.4 (no RPC by design) | The access check was assumed to be an RPC. | Read probe on a seeded reference table (10.4). | — |
| OI-11 | Resolved by DATA_MODEL §7.1 | C23's `device_id` tie-break had to be implemented on the server. | `tg_sync_row()` compares the `(client_updated_at, device_id)` tuple like 9.6. | — |
| OI-12 | Resolved by C39 (naming details in OI-21) | Android-layer `session_events` types had to be in the event-type vocabulary. | DATA_MODEL §9.8.1 registry is canonical; 7.10 lists what this layer emits. | — |
| OI-13 | Resolved by C54 | C23 did not fix backoff/periodic constants. | Section 25.4 (authoritative, tunable). | — |
| OI-14 | Resolved by PLAN.md M2 | Milestone for calendar write-back: the critique placed write-back in M6, but D11 makes auto-write of challenge sessions part of the product. | PLAN.md M2 ships read + classify + auto-write with marker + adopt together with the challenge engine; this document adds the sandbox test calendar first (27.3 rule 9). | — |
| OI-15 | Resolved by F27 | Debug builds sync into the user's real account. | `debug` syncs normally (daily app); experiments use `sandbox` (sync and user-calendar writes off). | — |
| OI-16 | Open (informational) | The `health` FGS prerequisite could also be met by declaring `HIGH_SAMPLING_RATE_SENSORS` (normal permission, no prompt). Not adopted because C28 names ACTIVITY_RECOGNITION and the app uses no sensors. | Follow C28. | None. |
| OI-17 | Open | Rest-tone audio usage (`USAGE_NOTIFICATION_EVENT` vs `USAGE_ASSISTANCE_SONIFICATION` vs `USAGE_ALARM`) affects routing to earbuds vs speaker. | `USAGE_NOTIFICATION_EVENT`, verified by T1 on the phone. | On-device verification. |
| OI-18 | Open | `versionCode` from git commit count requires a git repository (none exists yet). | `git init` at M0 step 23. | User approval of the first commit. |
| OI-19 | Resolved by C55 | C55 named `rest_before_s`, `rest_timer_planned_s`, `rest_timer_overridden` as the canonical `set_logs` columns while DATA_MODEL §9.6.4 still had `rest_planned_s`. | DATA_MODEL §9.6.4 now defines `rest_timer_planned_s`, `rest_adjust_s`, `rest_timer_overridden`, `rest_before_s`, `rest_source`, `rest_end_reason`; 12.3 writes exactly these. | — |
| OI-20 | Resolved by C37 | C37 fixes the slot-key class but not the uuid sub-pattern; DATA_MODEL and ALGORITHMS_PLANNING still had pre-C37 marker formats. | DATA_MODEL §9.7.3 (CHECK), ALGORITHMS_PLANNING (marker grammar, write-back table) and CHALLENGE_ENGINE now use `[fitapp:v1 <uuid> <slotKey>]` with the version-agnostic lower-case UUID pattern and the last-non-empty-line rule; 16.5 adopted the same pattern (it previously required the UUIDv7 version digit). | — |
| OI-21 | Resolved by C39 / C72 | Platform event names and payload keys had to be merged into the DATA_MODEL §9.8.1 registry, which lacked `fgs_type = none` below API 34. | The registry carries this document's events and keys, and `fgs_started.fgs_type` now lists `none` (C72); 7.10 copies it. | — |
| OI-22 | Partly resolved (UX §18 aligned) | UX §18 had a "Done (hang only)" reminder action and other channel ids. | Resolved in UX §18: no Done action, the six channel ids and channel assignment of 13.1, `session_idle_prompted`, and the `fitapp://challenge/<enrollment_id>` route (added to 7.4). Still open in UX: (a) §20.9 lists the `notification_action` codes `minus_15, plus_15, skip, done, start, skip_today, weigh_in, export, open`, without `snooze` (N-HABIT), `finish` and `keep_open` (N-IDLE), and with `done`, which no notification offers; (b) §20 intro, §20.12 and §20.13 still call the UX names canonical and list `promoted_notification_state`, `alarm_capability`, `reliability_test`, `reminder_delivered`, `session_idle_prompt` (C39 makes the registry canonical); (c) §8 ("Check again re-runs the allowlist RPC") and §21.1 ("allowlist RPC") describe an RPC, while 10.4 uses a read probe because `fitness` has no RPC (DATA_MODEL §5.4). | The action codes of 7.10; registry names; the 10.4 read probe. | UX §20.9, §20.12-20.13, §8 and §21.1 alignment. |
| OI-23 | Resolved by C68 | Package and resource layout: (a) ALGORITHMS_PLANNING §26 used `fit.algo.*` / `fit.app.ui.logic`; (b) its resource paths were outside `resources/fitness/`; (c) three places held marker code; (d) ALGORITHMS.md placed `EngineVersion` inconsistently. | C68: packages `dev.fitnessapp.*` (ALGORITHMS_PLANNING §26 now uses them and `dev.fitnessapp.app.uidet`), resources under `algo/src/main/resources/fitness/`, one `Marker` in `dev.fitnessapp.algo.core.calendar` (16.5, 27.1), `dev.fitnessapp.algo.EngineVersion`. | — |
| OI-24 | Resolved (ALGORITHMS.md implementation notes) | The P16 scanner pattern (6.3) needs a package convention: each registry module keeps non-public helpers in an `impl` sub-package. | ALGORITHMS.md adopts the `impl` convention and marks its OI-A02 resolved; 27.1 states it. | — |
| OI-25 | Resolved by D17 | The "full re-push" restore action (DATA_MODEL §15.4 scenario R1, its open issue 12) is not specified here. | A Sync status action "Re-push all data" that re-enqueues every local row of every pushed table in dependency order (9.2), user-triggered only; automatic detection deferred. | Specification before M1 sync code. |
| OI-26 | Resolved (DATA_MODEL §9.7.3) | The mapping of `calendar_link.state` (16.6) onto the synced `calendar_events.write_state` (E24) and `attention` columns was not written down. | DATA_MODEL §9.7.3 now has the mapping table (following this document's proposal, with `released` → `attention = edited_externally` and the last marker kept); 16.6 references it. | — |
| OI-27 | Resolved by D33 (alarm-backed interval, 12.3) | UX asks the rest timer to support a warm-up interval countdown (`purpose = interval`, UX §11.7.6, §19.9); the reducer supports only `rest` and `between_sides`. The registry's `rest_started.purpose` key already allows the value. | Not supported in v1 of this spec; UX §11.7.6 meanwhile uses an in-app countdown that keeps the screen on and vibrates only while the app is visible. | UX/ANDROID decision before the warm-up UI is built; that UI moved to M2 (D25, 2026-10-11). |
| OI-28 | Resolved by C72 | UX §20.1 defined `instance_key` as 16 hex characters. | Full 64-hex DATA_MODEL §9.7.3 key; UX now uses it. | — |
| OI-29 | Resolved by C37 | The OAP definition's `writeBack.marker` template and CHALLENGE_ENGINE's marker row described the uuid as the `calendar_link` row id. | Both now use the enrollment id (`[fitapp:v1 {enrollmentId} {slug}/{slot}/{isoWeek}]`), as 16.5 does. | — |
| OI-30 | Resolved by C72 | C54's "app/FGS in the foreground" wording for the rest pre-warning was ambiguous. | UI-visible only (12.4); the DATA_MODEL §9.8.1 `rest_prewarn` note and UX agree. | — |
| OI-31 | Resolved (CHALLENGE_ENGINE §9.8 aligned) | CHALLENGE_ENGINE listed a `result` key on `calendar_event_written`. | Registry keys; CHALLENGE_ENGINE now states there is no `result` key. | — |
| OI-32 | Resolved by CHALLENGE_ENGINE 4.6 | `daily-maintenance` must run tick synthesis before the calendar write plan, and an OEM-deferred job could delay the Monday `week.closed`. | 7.8 runs synthesis first; CHALLENGE_ENGINE 4.6 confirms late ticks are acceptable (idempotent, ordered catch-up; delayed, never lost or reordered). | — |
| OI-33 | Open (new) | UX §19.8 and §19.9 keep some rest-timer and hold-timer constants in `RestTimerConfig` / `HoldTimerConfig` with upper-snake names (`SWITCH_HINT_MS`, `FOCUS_MODE_DEFAULT`, `MAX_HOLD_S`, ...), while it already uses the 25.1 keys for the other timer constants; C54 names one `PlatformConfig`, whose keys are registered in section 25. | One `PlatformConfig` with the keys of 25.1 and 25.5 (values identical to UX's). | UX §19.8-19.9 naming alignment. |
| OI-34 | Resolved by C72 | The DATA_MODEL §16.3 payload string pattern had no `/`, so `marker_key` slot keys would be rejected. | DATA_MODEL §16.3 now allows `/` (`^[A-Za-z0-9_.:/+-]{0,64}$`). | — |
| OI-35 | Resolved (DATA_MODEL §11.6) | 9.6 pulled append-only tables only at bootstrap, while DATA_MODEL §11.6 pulls `challenge_events`, `challenge_rule_events`, `params_snapshots` and `exports` incrementally for multi-device convergence (its open issue 26). | 9.6 now applies the §11.6 pull scope per table. | — |

---

## 27. Implementation notes for Claude Code

### 27.1 Packages and key files

```
algo/src/main/kotlin/dev/fitnessapp/algo/
  EngineVersion.kt                    # dev.fitnessapp.algo.EngineVersion: const ENGINE_VERSION (C30, C68)
  core/clock/Clock.kt                 # injected clock + Week utilities (C31)
  core/calendar/Marker.kt             # the one marker render/parse/likePattern (C68), used by classify, challenge.calendar and the app writer
  core/calendar/classify/...          # calendar.classify (C20)
  challenge/calendar/...              # challenge.calendar (write plan ops)
  core/det/                           # Det, Grid, Canon, Jcs (ALGORITHMS.md A3); the only place P13 patterns may appear
  ... (one package per registry module; non-public helpers of a module in its `impl` sub-package,
       which other modules never import: the P16 scanner rule of 6.3)
algo/src/test/kotlin/dev/fitnessapp/algo/purity/AlgoPurityTest.kt

data/src/main/kotlin/dev/fitnessapp/data/
  db/FitnessDatabase.kt, db/Migrations.kt, db/PreMigrationBackup.kt
  db/local/OutboxDao.kt, SyncCursorDao.kt, DeadLetterDao.kt, RestTimerDao.kt, CalendarLinkDao.kt, ScheduledAlarmDao.kt
  ids/UuidV7Generator.kt
  outbox/OutboxWriter.kt
  sync/SyncWorker.kt, sync/SyncScheduler.kt, sync/PushEngine.kt, sync/PullEngine.kt, sync/LwwMerge.kt, sync/ErrorClassifier.kt
  remote/SupabaseFactory.kt, remote/KeystoreSessionManager.kt, remote/AccessCheck.kt, remote/dto/*.kt
  engine/EngineGateway.kt, engine/EngineInputAssembler.kt, engine/ParamsRepository.kt
  repo/*.kt                           # SessionRepository, SetLogRepository, CatalogRepository, ...
  export/ExportWriter.kt

app/src/main/kotlin/dev/fitnessapp/app/
  FitnessApplication.kt, AppContainer.kt, Startup.kt, PlatformConfig.kt, MainActivity.kt
  nav/Keys.kt, nav/AppNavHost.kt
  ui/theme/Color.kt (Bento palette, D31), Theme.kt (Material 3 scheme mapped onto it), Type.kt (bundled Manrope)
  ui/common/Bento.kt                  # tiles, pill buttons, chips, steppers, ring timer, floating nav bar (UX 4)
  ui/today/, ui/week/, ui/exercises/, ui/challenges/, ui/you/, ui/live/, ui/summary/, ui/onboarding/, ui/reliability/, ui/sync/
  uidet/                              # ui.todaycard, ui.prefill, ui.reasonchips, ui.prompts, ui.volumebars, ui.warndisplay, ui.progress (pure)
  session/SessionService.kt, session/SessionController.kt, session/FgsType.kt
  timer/RestTimerReducer.kt (pure), timer/RestTimerController.kt, timer/RestAlarmReceiver.kt, timer/RestActionReceiver.kt, timer/RestTimerRecovery.kt
  notify/Channels.kt, notify/NotificationPresenter.kt, notify/SessionNotificationBuilder.kt
  calendar/CalendarProviderGateway.kt, calendar/CalendarSyncController.kt, calendar/CalendarWriteExecutor.kt, calendar/LocalTestCalendar.kt (debug/sandbox)
  reminders/ReminderScheduler.kt, reminders/ReminderReceiver.kt
  oem/OemHardening.kt, oem/AutostartIntents.kt
  platform/AndroidClock.kt, AlarmGateway.kt, HapticsPlayer.kt, TonePlayer.kt, PermissionChecker.kt
  receivers/BootReceiver.kt           # BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED, exact-alarm permission change
  format/Numbers.kt, format/Dates.kt
  debug/DebugActivity.kt (debug + sandbox source sets), debug/DeterminismSelfTest.kt
```

### 27.2 Test names to create first

`AlgoPurityTest`, `VerifyAlgoDependencies` (Gradle task), `G1_CalendarReadMappingTest`,
`G2_CalendarWriteExecutorTest`, `G3_RestTimerReducerTest`, `G4_RestTimerDstTest`,
`G5_RestTimerRecoveryTest`, `G6_FgsTypeSelectionTest`, `G7_NumberFormatTest`,
`G8_ReminderScheduleTest`, `G9_LwwMergeTest`, `G10_OutboxOrderTest`, `G11_MarkerTest`,
`G12_WeekBoundaryTest`, `G14_ScreenshotTest`, `RestTimerReducerPropertiesTest`,
`LwwMergePropertiesTest`, `FitnessDatabaseMigrationTest`, `NoDestructiveMigrationTest`,
`DtoSchemaDriftTest`, `NoSecretKeysTest`, `SyncErrorClassifierTest`, `KeystoreSessionManagerTest`
(Robolectric), `CalendarSelectionTest`, `AutostartIntentOrderTest`.

### 27.3 Rules for every session

1. Never run `adb uninstall dev.fitnessapp`, `adb shell pm clear dev.fitnessapp`, or install with a
   different signing key. Stop and ask on any signature or downgrade error.
2. Never call `fallbackToDestructiveMigration*`; every Room version bump ships a migration and a test.
3. Never put a secret or `service_role` key into the repository, `local.properties`, `BuildConfig` or chat.
4. Never run `supabase config push`, `supabase db push`, `migration repair`, or `alter role authenticator …`
   (F2); migrations go through MCP `apply_migration` after the user has seen them (F3).
5. Keep `:algo` pure; if a rule in 6.2 blocks something, change the design, not the test.
6. Use `$env:JAVA_HOME` only process-scoped; never change system or user environment variables.
7. Use Git Bash or `adb pull` for binary output; never `>` in PowerShell for screenshots.
8. Run `.\gradlew.bat check` before installing on the phone; run the determinism self-test after any
   `:algo` change that reaches the phone.
9. Calendar writes to the real primary calendar only after the sandbox test calendar passed G2 and T17 and
   the user agreed.
10. Re-verify library versions at each dependency bump (section 3.8) and record them in the commit message.

---

## 28. Sources

Official documentation checked on 2026-10-07 (engineering-fact):

- Android Gradle plugin release notes and compatibility table: https://developer.android.com/build/releases/gradle-plugin
- Migrate to built-in Kotlin (AGP 9): https://developer.android.com/build/migrate-to-built-in-kotlin
- Gradle daemon JVM criteria: https://docs.gradle.org/current/userguide/gradle_daemon.html
- Android Studio download page (cmdline-tools file, size, SHA-256): https://developer.android.com/studio#command-line-tools-only
- sdkmanager (layout, commands, deprecation note): https://developer.android.com/tools/sdkmanager
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Schedule alarms (setAlarmClock, USE_EXACT_ALARM, canScheduleExactAlarms): https://developer.android.com/develop/background-work/services/alarms/schedule
- Live Updates: https://developer.android.com/develop/ui/views/notifications/live-update
- Android 17 behaviour changes (targeting 37 and all apps): https://developer.android.com/about/versions/17/behavior-changes-17 , https://developer.android.com/about/versions/17/behavior-changes-all
- Android 17 background audio hardening: https://developer.android.com/about/versions/17/changes/bg-audio
- Calendar provider: https://developer.android.com/identity/providers/calendar-provider
- Compose BOM mapping: https://developer.android.com/develop/ui/compose/bom/bom-mapping
- Room releases: https://developer.android.com/jetpack/androidx/releases/room
- Navigation3 releases: https://developer.android.com/jetpack/androidx/releases/navigation3
- WorkManager releases: https://developer.android.com/jetpack/androidx/releases/work
- Kotlin releases: https://kotlinlang.org/docs/releases.html
- Robolectric releases: https://github.com/robolectric/robolectric/releases
- supabase-kt BOM versions: https://central.sonatype.com/artifact/io.github.jan-tennert.supabase/bom/versions
- Supabase Kotlin client initialisation and sign-in: https://supabase.com/docs/reference/kotlin/initializing , https://supabase.com/docs/reference/kotlin/auth-signinwithpassword
- dontkillmyapp (Xiaomi, OnePlus, Oppo): https://dontkillmyapp.com/xiaomi , https://dontkillmyapp.com/oneplus , https://dontkillmyapp.com/oppo

Research notes (data, read 2026-10-07): `docs/research/tech_stack.md`, `docs/research/ux_ui.md`,
`docs/research/data_model.md`, `docs/research/critique.md`, `docs/research/challenge_engine.md`
(calendar matcher section).
