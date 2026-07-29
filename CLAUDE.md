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
- **ElevationFragment**: The whole app's UI and orchestration — owns the GPS lifecycle, permission handling, stillness/idle duty-cycling, epoch-guarded MSL conversion, unit toggle, details panel, and reading-state → UI mapping
- **ElevationService**: Rolling average of elevation readings with jump re-anchoring — sustained same-side outliers (elevator, stairs) flush and re-seed the window so the display snaps to the new level; exposes a `Snapshot` with window fill progress and a latched `settled` flag
- **ReadingState**: Sealed interface (Acquiring / Converging / Stable / Dormant) derived from tracking flags and the averaging window; drives the settling line and the hero number's opacity
- **StabilityLineView**: The "settling line" — a canvas-drawn kinetic line under the elevation number: traveling wave while acquiring, flattening/brightening core while converging, breathing glow when stable, motionless dotted line (with dimmed number) when the reading is dormant/stale
- **AltitudeResolver**: Converts WGS84 ellipsoid altitude to Mean Sea Level via the platform `AltitudeConverter` (API 34, offline geoid data); falls back to ellipsoid height if geoid data fails to load
- **StillnessDetector**: Declares the device stationary from GNSS fix speed/drift over a 30s window
- **IdleWakeMonitor**: While GPS is off, wakes on significant motion, sustained barometric pressure change (elevators), passive fixes, or a fallback poll (barometer-less devices only)
- **PressureDeltaDetector**: Sustained-pressure-change detection with weather-drift absorption and HVAC/door-transient rejection
- **LocationPermissionHandler**: Lifecycle-aware permission handler with state management, including the Android 12+ coarse-only ("approximate") grant state
- **PreferencesRepository**: DataStore-based persistence for user preferences (metric/imperial units, details panel)
- **UnitConverter**: `object` holding `FEET_PER_METER` and `metersToFeet()`

### Dependency Injection

The app uses **Hilt**. **AppModule** (`di/AppModule.kt`, `SingletonComponent`) provides:
- Singletons: `PreferencesRepository`, `LocationManager`, `AltitudeResolver`, `SensorManager`
- Unscoped (new instance per injection): `ElevationService(readingsCount = 10)`, `IdleWakeMonitor`

`@AndroidEntryPoint` is applied to `MainActivity` and `ElevationFragment`; the fragment `@Inject`s all six of the above. `StillnessDetector` and `LocationPermissionHandler` are constructed directly in the fragment, not injected.

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
- **Architecture Components**: Navigation Component, DataStore Preferences
- **Location**: Uses the platform `LocationManager` with GPS_PROVIDER only — **deliberately no Google Play services / play-services-location dependency**, so the app runs identically on certified and de-googled AOSP devices (GrapheneOS, LineageOS, etc.) and stays F-Droid-eligible. Do not introduce GMS dependencies.
- **Altitude**: Every fix is converted from WGS84 ellipsoid height to Mean Sea Level via the platform `AltitudeConverter`; fixes without altitude or with vertical accuracy worse than 50 m are excluded from the rolling average
- **Power**: GPS duty-cycles off after ~30 s stationary; wake triggers are significant motion, barometer delta (vertical movement), passive-provider fixes, and a 3-minute fallback poll on barometer-less devices
- **Permissions/Manifest**: Requires ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION; declares `<uses-feature android.hardware.location.gps required="true"/>` (affects Play Store device filtering)
- **Build variants**: Debug builds use applicationId suffix `.debug` and versionName suffix `-debug`, so debug and release installs coexist
- **Dependencies**: Managed via version catalog (`gradle/libs.versions.toml`)

## Accessibility Gates

Accessibility is enforced at three layers; all of them fail the build/CI on violation:

1. **Lint**: `app/lint.xml` promotes the entire `Accessibility` lint category to `error`, and `lint { abortOnError = true }` in `app/build.gradle.kts`
2. **Runtime checks**: the custom `HiltTestRunner` globally enables Accessibility Test Framework checks from the root view, so **every Espresso interaction** in every instrumented test validates the full view hierarchy
3. **Contrast tests**: `ScrimContrastTest` (unit test) computes WCAG 2.1 contrast ratios from constants referenced directly in production source (`ElevationFragment.DIMMED_TEXT_ALPHA`, `StabilityLineView` alpha constants, `hm_*` color resources). Changing those constants or colors can fail unit tests — that is by design.

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

Pure-JVM tests covering the core logic: `ElevationServiceTest` (rolling average, jump re-anchoring, settled latching), `AltitudeResolverTest` (MSL conversion + ellipsoid fallbacks, mocked `AltitudeConverter`), `StillnessDetectorTest`, `PressureDeltaDetectorTest`, `ReadingStateTest` (state derivation precedence), `ScrimContrastTest` (WCAG contrast — see Accessibility Gates), `UnitConverterTest`, `LocationPermissionHandlerUnitTest`, `PreferencesRepositoryUnitTest`, `DependencyInjectionTest`.

### Instrumented Tests (`app/src/androidTest/`)

- **HiltTestRunner**: custom runner — swaps in `HiltTestApplication` and enables global ATF accessibility checks
- **AccessibilityChecksTest**: drives interactive states in both day and night uiMode so ATF validates each
- **ElevationFragmentTest**: UI interactions and component integration (fine+coarse granted)
- **LocationPermissionTest**: three classes in one file (`LocationPermissionTest`, `CoarseLocationPermissionTest`, `BothLocationPermissionsTest`) covering the different grant combinations
- **StartupCrashTest**: crash detection and component initialization validation

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
