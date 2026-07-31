# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

HeightMark is a simple Android app that displays the user's current elevation/altitude. It uses GPS location services to determine elevation and provides a clean interface with metric/imperial unit switching.

## Build Commands

```bash
# Build the project (unit tests + lint + APKs)
./gradlew build

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run a single unit test class (append .methodName for one method;
# most test methods use backtick names, so quote them)
./gradlew testDebugUnitTest --tests "com.bizzarosn.heightmark.ElevationServiceTest"

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run a single instrumented test class
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.bizzarosn.heightmark.StartupCrashTest

# Lint (Accessibility category is promoted to error severity — see app/lint.xml)
./gradlew lintDebug

# Clean build
./gradlew clean
```

## Architecture

The app is a single screen (`ElevationFragment`) plus a set of focused collaborator classes:

- **HeightMarkApplication**: `@HiltAndroidApp` Application; applies Material `DynamicColors` to all activities
- **MainActivity**: `@AndroidEntryPoint` entry point; splash screen (`installSplashScreen`), `enableEdgeToEdge`, Navigation Component with a single-destination `BottomNavigationView`
- **ElevationFragment**: The app's one screen, and only a renderer — it holds the views, the two toggles and their persistence, the permission handler's launcher, and the dialogs. It collects an already-derived `ElevationUiState` (via `repeatOnLifecycle(STARTED)`) and maps it to views; it decides nothing about what the reading is worth
- **ElevationTracker**: The tracking session's Android shell, a `@HiltViewModel` — GNSS listener registration, the stationary duty cycle, the geoid conversion, the search timeout, the fix-age watchdog, the `PROVIDERS_CHANGED` receiver, and the panel-only feeds. Drives `ElevationSession` and publishes a `StateFlow<ElevationUiState>`. Being a ViewModel, a rotation no longer restarts the averaging window or re-acquires a fix; `onForeground()`/`onBackground()` bracket every radio, sensor and receiver it holds, so nothing draws power behind a screen the user has left. Main-thread confined, like the session it drives. The fix-age watchdog is a `viewModelScope` timer reset on every committed fix and on `startLocationUpdates()`; if it lapses (20s — well past a normal single-fix gap, short enough that a signal-blocking building doesn't leave a stale "stable" reading on screen for long) it tells `ElevationSession` the signal has gone stale, which the details panel reports as "GPS: no signal" to distinguish it from the deliberate stationary duty cycle ("GPS: idle (stationary)")
- **ElevationUiState**: What the screen shows, built by one pure `derive()` so the hero number, the settling line and the location-services prompt can never disagree. Holds `Hero` (Status/Value), a `Blocked` reason table (only `LocationServicesOff` offers a settings prompt), and `DetailsFacts` for the panel. A block outranks even a good reading, because that reading is no longer being kept current. `nowElapsedRealtimeNanos` is stamped at derive time on purpose: it is what makes a bare fix-age tick a distinct state instead of one the `StateFlow` deduplicates away
- **DetailsPanelPresenter**: Pure-JVM builder for the diagnostic panel's lines; takes an `Input` snapshot (including the clock, so it stays JVM-testable) and returns `Row`s of `@StringRes` + args for the fragment to resolve
- **DetailsSourcesController**: Registers and releases the panel-only feeds (GNSS satellite counts, barometer, 1 Hz fix-age ticker). Each source arms independently so one that could not start — usually the GNSS callback, which needs fine location — is retried on the next `start()`; `stop()` deliberately keeps the last-known values
- **ElevationService**: Rolling average of elevation readings with jump re-anchoring — sustained same-side outliers (elevator, stairs) flush and re-seed the window so the display snaps to the new level; exposes a `Snapshot` with window fill progress and a latched `settled` flag
- **ElevationSession**: The tracking session's domain policy, pure JVM — which fixes are worth averaging (altitude present, vertical accuracy within `MAX_VERTICAL_ACCURACY_M`), which *datum* may reach the average (see below), the duty-cycle flags, the epoch that drops a geoid conversion racing a window flush, the background-gap reset, and the last value held on screen across a flush. Takes the clock as an input rather than calling `SystemClock`, following `DetailsPanelPresenter`. `onFixWatchdogExpired()` records that `ElevationTracker`'s timer lapsed; unlike a flush this does not discard the averaging window — the outage is usually brief enough (elevator lobby, parking garage) that the same reading is still right once fixes resume — it only forces `ReadingState.Dormant` until the next fix commits and clears it
- **Elevation / ElevationDatum**: A height in meters plus the surface it was measured from (`MEAN_SEA_LEVEL` / `ELLIPSOID`). The two differ by the local geoid separation — ~-30 m across most of North America — so the pair travels together from `AltitudeResolver` through `ElevationSession` to `Hero.Value`, and no code path can pass an unconverted fallback off as sea level
- **ReadingState**: Sealed interface (Acquiring / Converging / Stable / Dormant) derived from tracking flags and the averaging window; reaches the screen through `ElevationUiState`, driving the settling line, and owns the state → hero-opacity mapping (`heroAlpha`, `DIMMED_TEXT_ALPHA`)
- **StabilityLineView**: The "settling line" — a canvas-drawn kinetic line under the elevation number: traveling wave while acquiring, flattening/brightening core while converging, breathing glow when stable, motionless dotted line (with dimmed number) when the reading is dormant/stale
- **AltitudeResolver**: Converts WGS84 ellipsoid altitude to Mean Sea Level via the platform `AltitudeConverter` (API 34, offline geoid data); falls back to ellipsoid height if geoid data fails to load. Returns an `Elevation` — value plus datum — never a bare Double: all three failure paths (IOException, IllegalArgumentException, and a call that returns without populating `hasMslAltitude()`) produce a number that is *not* sea level, and only the tag tells them apart
- **StillnessDetector**: Declares the device stationary from GNSS fix speed/drift over a 30s window
- **IdleWakeMonitor**: While GPS is off, wakes on significant motion, sustained barometric pressure change (elevators), passive fixes, or a fallback poll (barometer-less devices only)
- **PressureDeltaDetector**: Sustained-pressure-change detection with weather-drift absorption and HVAC/door-transient rejection
- **LocationPermissionHandler**: Lifecycle-aware permission handler with state management, including the Android 12+ coarse-only ("approximate") grant state. Its file also holds the top-level `Context.hasFineLocationPermission()` that both it and `ElevationTracker` gate on
- **PreferencesRepository**: DataStore-based persistence for user preferences (metric/imperial units, details panel)
- **LengthFormatter**: The single home of the metric/imperial branch — value conversion, number formatting, and unit-resource selection. Returns resource IDs for callers to resolve: `Detail` for a panel row, `Hero` for the big number, each pairing a value with the unit that names it
- **LocationPermissionPolicy**: Pure decision table mapping grant state to a `Resolution` (report a state, show a dialog, or re-request)
- **UnitConverter**: `object` holding `FEET_PER_METER` and `metersToFeet()`
- **LocationAccuracy.kt / SensorListeners.kt**: Top-level extension helpers — nullable accuracy accessors; a `SensorEventListener` from a single lambda, and `registerPressureListener` for the barometer arming both `IdleWakeMonitor` and `DetailsSourcesController` need

### Dependency Injection

The app uses **Hilt**. `PreferencesRepository` (`@Singleton`), `IdleWakeMonitor`, and `ElevationSession` carry `@Inject constructor`. **AppModule** (`di/AppModule.kt`, `SingletonComponent`) holds only the bindings Dagger cannot derive:
- Singletons: `LocationManager`, `SensorManager` (both via `getSystemService`), `AltitudeResolver` (its defaulted `converter` param is the seam `AltitudeResolverTest` injects a fake through)
- Unscoped: `ElevationService(readingsCount = ElevationService.DEFAULT_WINDOW_SIZE)`, `StillnessDetector`, `PressureDeltaDetector` — all have constructors made entirely of defaulted tuning values, and Dagger ignores Kotlin defaults

Do not "simplify" the remaining five into `@Inject constructor`s; each would either break a test seam or make Dagger try to inject a `Long`/`Float`. `ElevationSession` avoids that trap precisely because its clock is an argument to `onPaused`/`onResumed` rather than a defaulted tuning parameter on the constructor — keep it that way.

`@AndroidEntryPoint` is applied to `MainActivity` and `ElevationFragment`. The fragment `@Inject`s only `PreferencesRepository` and reaches everything else through `ElevationTracker`, a `@HiltViewModel` obtained with `by viewModels()` — the six collaborators the fragment used to hold (`ElevationSession`, `LocationManager`, `SensorManager`, `AltitudeResolver`, `IdleWakeMonitor`, `StillnessDetector`) are constructor-injected into the tracker instead, alongside `@ApplicationContext`. `LocationPermissionHandler` is still constructed directly in the fragment, because its `ActivityResultLauncher` needs the Fragment; `DetailsSourcesController` is constructed in the tracker. No `androidx.hilt:hilt-navigation-fragment` is needed — plain `by viewModels()` in an `@AndroidEntryPoint` fragment already resolves through Hilt's generated factory.

### Permission Handling

- **LocationPermissionState**: Sealed class with states Granted, CoarseOnly, PermanentlyDenied, RequiresRationale
- **Lifecycle-aware**: Automatically cleans up dialogs and resources when fragment is destroyed
- **Multiple permissions**: Handles both ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION; coarse-only grants get an in-context precise-location upgrade prompt because GPS requires fine

## Key Technical Details

- **Compile SDK**: 37, **Target SDK**: 36, **Minimum SDK**: 34 (Android 14)
- **Toolchain**: AGP 9.3.1, Gradle wrapper 9.6.1, JDK 21 (Temurin), Java/Kotlin target 17
- **Kotlin**: compiled through AGP's **built-in Kotlin support** — there is no `org.jetbrains.kotlin.android` plugin and no `kotlin` version in the catalog, so the Kotlin version is whatever KGP the AGP version bundles (2.2.10 for AGP 9.3.1) and moves with AGP. Do not re-add the plugin or the `android.builtInKotlin` / `android.newDsl` opt-outs: applying the standalone plugin makes it call the legacy variant API, and every one of those calls is a deprecation that AGP 10 removes outright. `ksp` is still pinned in the catalog and is bumped by Dependabot independently.
- **JDK selection**: the Gradle daemon JVM is pinned by Daemon JVM criteria in `gradle/gradle-daemon-jvm.properties` (`toolchainVendor=ADOPTIUM`, `toolchainVersion=21`), so IDE, CLI, and CI builds all run on Temurin 21. `.tool-versions` pins the local install, and CI's three `setup-java` steps must keep `distribution: 'temurin'` to satisfy the vendor pin. Do **not** repin the vendor to `JETBRAINS` — Android Studio's bundled JBR lives inside the app bundle, which Gradle's toolchain auto-detection does not scan, so that pin forces a JDK download on every machine and every CI job. Android Studio still *boots* on its bundled JBR through a separate mechanism (`STUDIO_JDK` / the `jbr` directory); the criteria file has no effect on the IDE runtime. Dependabot bumps none of `toolchainVersion`, `.tool-versions`, or `setup-java`'s `java-version` — move those three by hand, together.
- **Dependency Injection**: Hilt 2.60.1 with KSP 2.3.10
- **Architecture Components**: Navigation Component, DataStore Preferences, Lifecycle ViewModel (`ElevationTracker`)
- **Location**: Uses the platform `LocationManager` with GPS_PROVIDER only — **deliberately no Google Play services / play-services-location dependency**, so the app runs identically on certified and de-googled AOSP devices (GrapheneOS, LineageOS, etc.) and stays F-Droid-eligible. Do not introduce GMS dependencies.
- **Altitude**: Every fix is converted from WGS84 ellipsoid height to Mean Sea Level via the platform `AltitudeConverter`; fixes without altitude or with vertical accuracy worse than 50 m are excluded from the rolling average
- **Datum**: One averaging window never mixes datums. Once a fix has converted to MSL this session, later unconverted fallbacks are dropped rather than averaged in (at 1 Hz, a dropped fix costs a second of freshness; mixing costs the geoid separation). A device that can never convert averages ellipsoid heights consistently, and says so — the hero's label reads "Ellipsoid height · no sea-level data" and the details panel names the datum. The one permitted switch, ellipsoid → MSL when geoid data finally loads, flushes the window at the boundary so `ElevationService`'s jump detector never reads a change of surface as a climb
- **Power**: GPS duty-cycles off after ~30 s stationary; wake triggers are significant motion, barometer delta (vertical movement), passive-provider fixes, and a 3-minute fallback poll on barometer-less devices
- **Permissions/Manifest**: Requires ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION; declares `<uses-feature android.hardware.location.gps required="true"/>` (affects Play Store device filtering)
- **Build variants**: Debug builds use applicationId suffix `.debug` and versionName suffix `-debug`, so debug and release installs coexist
- **Dependencies**: Managed via version catalog (`gradle/libs.versions.toml`)

## Accessibility Gates

Accessibility is enforced at three layers; all of them fail the build/CI on violation:

1. **Lint**: `app/lint.xml` promotes the entire `Accessibility` lint category to `error`, and `lint { abortOnError = true }` in `app/build.gradle.kts`
2. **Runtime checks**: the custom `HiltTestRunner` globally enables Accessibility Test Framework checks from the root view, so **every Espresso interaction** in every instrumented test validates the full view hierarchy
3. **Contrast tests**: `ScrimContrastTest` (unit test) computes WCAG 2.1 contrast ratios from constants referenced directly in production source (`ReadingState.DIMMED_TEXT_ALPHA`, `StabilityLineView` alpha constants, `hm_*` color resources). Changing those constants or colors can fail unit tests — that is by design.

## Release Process

### Automatic Releases

Every merge to `main` automatically creates a new release if all quality checks pass.

**Flow:**
1. PR merged to main → `android_build.yml` ("Android CI") re-runs on main
2. `release.yml` triggers via `workflow_run` when "Android CI" completes **successfully** on main (not on push directly)
3. Release workflow: calculates version from its own `run_number`, builds a signed AAB (`bundleRelease`), uploads to the Play Store **internal** track, and creates a GitHub release with auto-generated notes. Concurrency group `play-store-release` serializes releases (Play API allows one open edit).

Release signing reads `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` env vars (populated from secrets in CI); if any is missing the signing config is left empty and local `assembleRelease`/`bundleRelease` output is unsigned.

### Version Numbering

- **versionCode** (Play Store, must always increase): `BASE_CODE + run_number` with `BASE_CODE=10000`
- **versionName** (user-visible): `VERSION_PREFIX.run_number` with `VERSION_PREFIX="1.0"`
- Both are defined in `.github/workflows/release.yml`; `run_number` is the release workflow's own run counter
- Local builds fall back to `versionCode 4` / `versionName "1.0.0-dev"` unless overridden:

```bash
./gradlew bundleRelease -PversionName=1.0.999 -PversionCode=10999
```

To bump major/minor (e.g. v1.1.x or v2.0.x), edit `VERSION_PREFIX` and `BASE_CODE` in `release.yml` (e.g. `"1.1"` / `11000`).

### Rollback

Revert the problematic commit on main (`git revert <sha>`, or `git revert -m 1 <merge-sha>` for a merge) and push — a new release is created automatically with the fix.

## Testing

### Unit Tests (`app/src/test/`)

Pure-JVM tests covering the core logic: `ElevationServiceTest` (rolling average, jump re-anchoring, settled latching), `ElevationSessionTest` (fix admission, the datum policy, the epoch guard against a conversion racing a flush, duty-cycle and background-gap policy, the fix-age watchdog), `AltitudeResolverTest` (MSL conversion + the three datum-tagged fallbacks, mocked `AltitudeConverter`), `DetailsPanelPresenterTest` (details-panel row set and order), `StillnessDetectorTest`, `PressureDeltaDetectorTest`, `ReadingStateTest` (state derivation precedence), `ElevationUiStateTest` (the screen's precedence table: block vs. reading vs. search text, and when the settling line goes away), `LocationPermissionPolicyTest` (permission decision table), `ScrimContrastTest` (WCAG contrast — see Accessibility Gates), `LengthFormatterTest`, `LocationAccuracyTest`, `UnitConverterTest`. Shared mockk `Location` factories live in `TestLocations`.

Tests assert behavior, not language semantics. Reflection checks that a constructor exists, that a class is not abstract, or that a sealed `object` equals itself are guaranteed by the compiler and do not belong here.

### Instrumented Tests (`app/src/androidTest/`)

- **HiltTestRunner**: custom runner — swaps in `HiltTestApplication` and enables global ATF accessibility checks
- **AccessibilityChecksTest**: drives interactive states in both day and night uiMode so ATF validates each
- **ElevationFragmentTest**: UI interactions (fine+coarse granted), including the details-panel toggle cycle that exercises `DetailsSourcesController`'s arm/release/re-arm path through `ElevationTracker` — a leaked or double-registered listener is invisible to the compiler and to the unit tests. `ElevationTracker` itself is the Android shell and has no JVM test; these tests plus the pure `ElevationSession`/`ElevationUiState` suites are what cover it
- **LocationPermissionTest**: three classes in one file (`LocationPermissionTest`, `CoarseLocationPermissionTest`, `BothLocationPermissionsTest`) covering the different grant combinations
- **StartupCrashTest**: crash detection and component initialization validation; all three cases go through `HiltUiTestBase.launchHome()`, whose Espresso check syncs on the looper — do not reintroduce `Thread.sleep` inside `onActivity`, which blocks the very thread it appears to wait on

All instrumented tests use `@HiltAndroidTest` + `HiltAndroidRule`; rule ordering matters — `HiltAndroidRule` must be first (`order = 0`), `GrantPermissionRule` after it.

### CI (`.github/workflows/android_build.yml`)

Triggers on push to `main` and on all PRs (deliberately no base-branch filter, to support stacked PRs). Three jobs:

1. **security**: Trivy filesystem scan → SARIF upload (runs immediately, parallel with build)
2. **build-and-test**: single job running `lintDebug testDebugUnitTest assembleDebug assembleRelease` (combined to avoid per-job setup overhead); publishes test results and lint annotations to the PR; uploads the debug APK; Gradle cache write access. `assembleRelease` is there so R8 and resource shrinking are exercised on every PR rather than first running in `release.yml`; without signing secrets it produces an unsigned APK that is built but never uploaded
3. **instrumented-tests** (needs build-and-test): emulator tests on API 35 (google_apis, x86_64) with KVM, AVD snapshot caching, and read-only Gradle cache (avoids conflicts with job 2)

Gradle performance flags (parallel, build cache, `workers.max=4`, configuration cache, no incremental Kotlin) are set via `GRADLE_OPTS` in the workflow — the configuration cache is CI-only and only in `android_build.yml`, not `release.yml`.

### Quality Gates

All releases must pass: security scan (Trivy), lint (including accessibility-as-error), unit tests, and instrumented tests. Releases only happen if ALL checks pass.

### GitHub Workflow Testing

Validate workflow changes with `act` before committing:

```bash
brew install act              # if not already installed
act --list                    # list workflows and jobs
act push -j build-and-test --container-architecture linux/amd64 --dryrun
act push -j build-and-test --container-architecture linux/amd64   # full run (requires Docker)
```

Use `--container-architecture linux/amd64` on Apple M-series chips to avoid compatibility issues.
