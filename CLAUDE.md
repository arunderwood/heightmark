# CLAUDE.md

HeightMark is a single-screen Android app that shows the user's elevation from GPS, with a metric/imperial toggle and a diagnostic details panel. Class-level behavior is in each file's KDoc; this file holds what the code does not say.

## Commands

```bash
./gradlew build                     # unit tests + lint + APKs
./gradlew assembleDebug
./gradlew test
# One class; append .methodName for one method (backtick names need quoting)
./gradlew testDebugUnitTest --tests "com.bizzarosn.heightmark.ElevationServiceTest"
./gradlew lintDebug                 # warnings fail the build; Accessibility is error severity (app/lint.xml)
./gradlew connectedAndroidTest      # needs a device or emulator
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bizzarosn.heightmark.StartupCrashTest
./gradlew allGroupDebugAndroidTest  # Gradle Managed Devices (API 35 + 36); no emulator needed
./gradlew :app:generateReleaseBaselineProfile   # regenerate the committed baseline profile on a managed device
./gradlew :baselineprofile:pixel8proapi36profileBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark
```

## Architecture map

- `ElevationFragment` is a renderer. It maps `ElevationUiState` to views and decides nothing about what a reading is worth.
- `ElevationTracker` (`@HiltViewModel`) is the Android shell: GNSS, duty cycle, geoid conversion, watchdog, receivers. It drives `ElevationSession` and publishes `StateFlow<ElevationUiState>`.
- `ElevationSession` is the pure-JVM domain policy: fix admission, datum policy, duty-cycle flags, epoch guard against a conversion racing a flush. It takes the clock as an argument.
- `ElevationService` is the rolling average with jump re-anchoring.
- `SessionPool` folds whole GPS sessions into one pooled height. `BarometricOdometer` turns barometer samples into carried height, with weather left out.
- `ElevationUiState.derive()` is the only place screen state is decided.
- `ReadingState` (Acquiring / Converging / Stable / Dormant) drives the settling line and `heroAlpha`.
- `SerialConversion` feeds fixes through the geoid conversion in order. `AltitudeResolver.resolve` is `@Synchronized`, which serializes calls but does not order them, so ordering comes from the single consumer.
- `IdleWakeMonitor` and `IdleWakePolicy` wake the app while GPS is off. `StillnessDetector` and `PressureDeltaDetector` feed the duty cycle.
- `LengthFormatter` holds the one metric/imperial branch. `LocationPermissionPolicy` is the permission decision table.

## Invariants

- **Datum.** One averaging window never mixes `MEAN_SEA_LEVEL` and `ELLIPSOID` heights. `Elevation` carries its datum from `AltitudeResolver` to the hero number, so an unconverted fallback can never pass as sea level. Once a fix converts to MSL, later ellipsoid fixes are dropped. A device that never converts averages ellipsoid heights and labels them so. The ellipsoid → MSL switch flushes the window. A background-gap reset clears the "sea level measured" latch. A duty-cycle `wake()` keeps it.
- **Idle has one owner.** `ElevationSession.isIdle` is the only record of idleness. Anything that turns GPS on checks it first (`startLocationUpdates()` returns early while idle). `onBlocked()` ends idle the way a wake does.
- **Threading.** `ElevationTracker` and `ElevationSession` are main-thread confined.
- **Conversion lifetime.** `SerialConversion` starts in `startLocationUpdates()` and stops only in `onBackground()`/`onCleared()`, never in `stopLocationUpdates()`. The fix that tips the stillness detector into idle is still converted after the radio is off.
- **Pooled sessions.** One GPS session (radio on to radio off, or each `FOLD_INTERVAL_NANOS` of a long one) is one measurement. `SessionPool` folds it into the pool instead of replacing the reading. A fresh window never replaces a pooled reading unless it refutes the pool beyond `GATE_SIGMA` over `MIN_REFUTING_READINGS` fixes. Wakes, blocks and background gaps keep the pool. A datum switch and a `PRESSURE_CHANGE` wake with no barometer stream discard it.
- **Barometer frame.** Window and pool hold heights minus `BarometricOdometer.motionMeters` at commit time. The screen adds the odometer back. The barometer listener runs whenever the tracker is foreground, radio on or off. While the odometer is still, pressure change is weather and never moves the reading.
- **Signal loss.** `onFixWatchdogExpired()` (timeout: `FIX_WATCHDOG_TIMEOUT_MS`) forces `Dormant` without discarding the window. A flush discards it.
- **First launch.** The system permission dialog never auto-fires on a true first launch. The blocked screen is the rationale, and `LocationPermissionHandler.requestPermissions()` is the one choke point that marks `hasRequestedLocationPermission`. A returning, permanently denied user gets the silent auto-fire fallback.
- **Derive-time clock.** `nowElapsedRealtimeNanos` is stamped in `derive()` so a bare fix-age tick is a distinct `StateFlow` value.
- **Accuracy gate.** Fixes with no altitude or vertical accuracy worse than `MAX_VERTICAL_ACCURACY_M` stay out of the average.

## Do not

- Add Google Play services or `play-services-location`. The app runs on de-googled AOSP devices and stays F-Droid-eligible. Location uses `LocationManager` with `GPS_PROVIDER` only.
- Set `android.builtInKotlin=false` or `android.newDsl=false` to get past a build error. Both are deprecated and removed in AGP 10.
- Repin the Daemon JVM vendor (`gradle/gradle-daemon-jvm.properties`) to `JETBRAINS`. Android Studio's bundled JBR is not on Gradle's toolchain search path, so every machine and CI job would download a JDK. Dependabot bumps neither `toolchainVersion` nor `.tool-versions`. Every `setup-java` step reads `.tool-versions` (`java-version-file`), which sets both version and vendor. Move it and `gradle-daemon-jvm.properties` together, and never add a `java-version` or `distribution` input, which would override the file.
- Turn the `AppModule` bindings into `@Inject constructor`s. Each has its own reason:
  - `LocationManager` and `SensorManager` are framework services from `getSystemService`, with no constructor to annotate.
  - `ElevationService` takes a plain `Int` window size, which the module supplies as `DEFAULT_WINDOW_SIZE`.
  - `StillnessDetector` and `PressureDeltaDetector` take only defaulted tuning values. Dagger ignores Kotlin defaults and would try to inject each `Long`, `Float` or `Int`.
  - `AltitudeResolver`'s defaulted `converter` param is the `AltitudeResolverTest` seam. Dagger would ignore the default and demand an `AltitudeConverter` binding.

  `ElevationSession` stays injectable only because its clock is an argument to `onPaused`/`onResumed`, not a constructor parameter.
- Rotate the sideload signing key. A new signer strands every sideloaded install.
- Rename the `Instrumented Tests` CI job. It is a required status check in the `main` ruleset.
- Edit `ScrimContrastTest` to make a failure pass. It reads `ReadingState.DIMMED_TEXT_ALPHA`, the `StabilityLineView` alpha constants and the `hm_*` colors directly, so a contrast failure after changing them is the gate working.

## Testing

- Tests assert behavior. Do not test what the compiler guarantees (a constructor exists, a class is not abstract, a sealed `object` equals itself).
- Shared mockk `Location` factories live in `TestLocations`.
- Instrumented tests use `@HiltAndroidTest` with `HiltAndroidRule` at `order = 0`, then `GrantPermissionRule`.
- `HiltTestRunner` enables Accessibility Test Framework checks on every Espresso interaction. Lint, that runner, and `ScrimContrastTest` all fail the build on an accessibility violation.
- The Android Test Orchestrator runs each test in its own process with `clearPackageData=true`. This isolates runtime permissions. `CoarseLocationPermissionTest` asserts that FINE is not granted. Do not turn that assert into an `assume`, which would skip silently if the isolation broke.
- Do not put `Thread.sleep` inside `onActivity`. It blocks the looper it waits on. Use `HiltUiTestBase.launchHome()`, whose Espresso check syncs on the looper.
- `ElevationTracker` has no JVM test. `ElevationFragmentTest` (including the details-panel toggle cycle) plus the `ElevationSession` and `ElevationUiState` suites cover it.

## Build facts

- SDK levels (`compileSdk`, `minSdk`, `targetSdk`) are in `gradle/libs.versions.toml`, shared by both modules. The JDK is pinned in `gradle/gradle-daemon-jvm.properties` and `.tool-versions`. Regenerate the properties file with `./gradlew updateDaemonJvm --jvm-version=<N> --jvm-vendor=adoptium`. That task needs the `org.gradle.toolchains.foojay-resolver-convention` plugin in `settings.gradle.kts` to write download URLs, so add it for the run and remove it after. AGP and dependency versions are in `gradle/libs.versions.toml`, and the Gradle version is in `gradle/wrapper/gradle-wrapper.properties`.
- Debug builds use applicationId suffix `.debug`, so debug and release installs coexist.
- Local `assembleRelease` is unsigned unless `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` are all set. Local builds default to a `-dev` version; override with `-PversionName=<name> -PversionCode=<code>`.
- The baseline profile at `app/src/release/generated/baselineProfiles/baseline-prof.txt` is generated by hand and committed. Regenerate it after a change to the startup path or the settling line. Generation is not byte-stable from run to run, so CI never regenerates it.
- `AndroidManifest.xml` declares `android.hardware.location.gps` as required. This filters Play Store devices.
- The release APK is reproducible, and `release.yml` publishes nothing unless a second build matches it (`RELEASE_SETUP.md`). AGP records the commit in the APK, so a build from a worktree or source archive differs from the release in that one file.

CI and release rules are in `.claude/rules/ci-and-release.md` and load when Claude reads a file under `.github/workflows/` or `RELEASE_SETUP.md`. Every merge to `main` that passes CI ships a release, so treat a merge as a release. Roll back with `git revert`.
