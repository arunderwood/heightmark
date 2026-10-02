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
```

## Architecture map

- `ElevationFragment` is a renderer. It maps `ElevationUiState` to views and decides nothing about what a reading is worth.
- `ElevationTracker` (`@HiltViewModel`) is the Android shell: GNSS, duty cycle, geoid conversion, watchdog, receivers. It drives `ElevationSession` and publishes `StateFlow<ElevationUiState>`.
- `ElevationSession` is the pure-JVM domain policy: fix admission, datum policy, duty-cycle flags, epoch guard against a conversion racing a flush. It takes the clock as an argument.
- `ElevationService` is the rolling average with jump re-anchoring.
- `ElevationUiState.derive()` is the only place screen state is decided.
- `ReadingState` (Acquiring / Converging / Stable / Dormant) drives the settling line and `heroAlpha`.
- `SerialConversion` feeds fixes through the geoid conversion in order. `AltitudeResolver.resolve` is `@Synchronized`, which serializes calls but does not order them, so ordering comes from the single consumer.
- `IdleWakeMonitor` and `IdleWakePolicy` wake the app while GPS is off. `StillnessDetector` and `PressureDeltaDetector` feed the duty cycle.
- `LengthFormatter` holds the one metric/imperial branch. `LocationPermissionPolicy` is the permission decision table.

## Invariants

- **Datum.** One averaging window never mixes `MEAN_SEA_LEVEL` and `ELLIPSOID` heights. `Elevation` carries its datum from `AltitudeResolver` to the hero number, so an unconverted fallback can never pass as sea level. Once a fix converts to MSL, later ellipsoid fixes are dropped. A device that never converts averages ellipsoid heights and labels them so. The ellipsoid → MSL switch flushes the window. A background-gap reset clears the "sea level measured" latch; a duty-cycle `wake()` flushes but keeps it.
- **Idle has one owner.** `ElevationSession.isIdle` is the only record of idleness. Anything that turns GPS on checks it first (`startLocationUpdates()` returns early while idle). `onBlocked()` ends idle with a flush.
- **Threading.** `ElevationTracker` and `ElevationSession` are main-thread confined.
- **Conversion lifetime.** `SerialConversion` starts in `startLocationUpdates()` and stops only in `onBackground()`/`onCleared()`, never in `stopLocationUpdates()`. The fix that tips the stillness detector into idle is still converted after the radio is off.
- **Signal loss.** `onFixWatchdogExpired()` (timeout: `FIX_WATCHDOG_TIMEOUT_MS`) forces `Dormant` without discarding the window. A flush discards it.
- **First launch.** The system permission dialog never auto-fires on a true first launch. The blocked screen is the rationale, and `LocationPermissionHandler.requestPermissions()` is the one choke point that marks `hasRequestedLocationPermission`. A returning, permanently denied user gets the silent auto-fire fallback.
- **Derive-time clock.** `nowElapsedRealtimeNanos` is stamped in `derive()` so a bare fix-age tick is a distinct `StateFlow` value.
- **Accuracy gate.** Fixes with no altitude or vertical accuracy worse than `MAX_VERTICAL_ACCURACY_M` stay out of the average.

## Do not

- Add Google Play services or `play-services-location`. The app runs on de-googled AOSP devices and stays F-Droid-eligible. Location uses `LocationManager` with `GPS_PROVIDER` only.
- Re-add `org.jetbrains.kotlin.android`, a `kotlin` catalog version, or the `android.builtInKotlin` / `android.newDsl` opt-outs. AGP's built-in Kotlin is used, and the standalone plugin calls the legacy variant API, which AGP has deprecated for removal.
- Repin the Daemon JVM vendor (`gradle/gradle-daemon-jvm.properties`) to `JETBRAINS`. Android Studio's bundled JBR is not on Gradle's toolchain search path, so every machine and CI job would download a JDK. Dependabot bumps none of `toolchainVersion`, `.tool-versions`, or `setup-java`'s `java-version`. Move the three together, and keep `distribution: 'temurin'` in all three `setup-java` steps.
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

- SDK levels are in `app/build.gradle.kts`. The JDK is pinned in `gradle/gradle-daemon-jvm.properties` and `.tool-versions`. AGP and dependency versions are in `gradle/libs.versions.toml`, and the Gradle version is in `gradle/wrapper/gradle-wrapper.properties`.
- Debug builds use applicationId suffix `.debug`, so debug and release installs coexist.
- Local `assembleRelease` is unsigned unless `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD` are all set. Local builds default to a `-dev` version; override with `-PversionName=<name> -PversionCode=<code>`.
- `AndroidManifest.xml` declares `android.hardware.location.gps` as required. This filters Play Store devices.

CI and release rules are in `.claude/rules/ci-and-release.md` and load when Claude reads a file under `.github/workflows/` or `RELEASE_SETUP.md`. Every merge to `main` that passes CI ships a release, so treat a merge as a release. Roll back with `git revert`.
