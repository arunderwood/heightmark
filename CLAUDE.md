# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

HeightMark is a simple Android app that displays the user's current elevation/altitude. It uses GPS location services to determine elevation and provides a clean interface with metric/imperial unit switching.

## Build Commands

```bash
# Build the project
./gradlew build

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Clean build
./gradlew clean
```

## Architecture

The app follows a simple Android architecture with these key components:

- **HeightMarkApplication**: Application class annotated with `@HiltAndroidApp` for dependency injection
- **MainActivity**: Entry point with navigation setup using Navigation Component (annotated with `@AndroidEntryPoint`)
- **ElevationFragment**: Main UI fragment that handles location permissions and displays elevation (uses `@Inject` for dependencies)
- **ElevationService**: Business logic for averaging elevation readings (configurable number of readings)
- **ElevationTextView**: Custom TextView with loading animation for elevation display
- **LocationPermissionHandler**: Robust permission handler with state management and lifecycle awareness
- **PreferencesRepository**: DataStore-based persistence for user preferences (metric/imperial units)

### Dependency Injection

The app uses **Hilt** for dependency injection:
- **AppModule** (`di/AppModule.kt`): Defines dependency providers
  - `PreferencesRepository`: Singleton scope
  - `ElevationService`: Factory scope (new instance per injection)
  - `LocationManager`: Singleton scope
- **@AndroidEntryPoint**: Applied to `MainActivity` and `ElevationFragment` to enable injection
- **@Inject**: Used in fragments to inject dependencies automatically
- **Benefits**: Improved testability, cleaner code, centralized dependency management

### Permission Handling

The app uses a sophisticated permission handling system:
- **LocationPermissionState**: Sealed class defining permission states (Granted, Denied, PermanentlyDenied, RequiresRationale)
- **Lifecycle-aware**: Automatically cleans up dialogs and resources when fragment is destroyed
- **Multiple permissions**: Handles both ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION
- **Proper state management**: Clear separation between permission states and UI responses

## Key Technical Details

- **Compile SDK**: 36
- **Target SDK**: 36
- **Minimum SDK**: 34 (Android 14)
- **Language**: Kotlin 2.2.20
- **Architecture Components**: Navigation Component, DataStore Preferences, Hilt 2.57.2
- **Dependency Injection**: Hilt with KSP 2.2.20-2.0.3 (Kotlin Symbol Processing)
- **Location**: Uses GPS_PROVIDER for elevation readings, averages multiple readings for accuracy
- **Permissions**: Requires ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION
- **Dependencies**: Uses version catalog (gradle/libs.versions.toml) for dependency management

## Release Process

### Automatic Releases

Every merge to `main` automatically creates a new release if all quality checks pass.

**Flow:**
1. Developer creates PR → `android_build.yml` runs full quality checks
2. PR merged to main → `android_build.yml` re-runs on main
3. If all checks pass → `release.yml` automatically triggers
4. Release workflow:
   - Calculates version based on workflow run number
   - Builds signed AAB
   - Uploads to Play Store internal track
   - Creates GitHub release with auto-generated notes

### Version Numbering

**versionCode (Play Store):** Monotonically increasing integer
- Formula: `10000 + workflow_run_number`
- Example: Run #7 → versionCode = 10007
- Always increases, never decreases (Play Store requirement)

**versionName (User-visible):** Human-readable version string
- Format: `MAJOR.MINOR.PATCH`
- Example: Run #7 → versionName = "1.0.7"
- PATCH auto-increments with each release

**Current version:** Based on workflow run number (currently at run #6)
- Next release will be: v1.0.7 (versionCode 10007)

### Bumping Major/Minor Versions

When you want to release a new major or minor version (e.g., v1.1.0 or v2.0.0):

**Edit `.github/workflows/release.yml`:**
```yaml
# For v1.1.x releases
VERSION_PREFIX="1.1"
BASE_CODE=11000

# For v2.0.x releases
VERSION_PREFIX="2.0"
BASE_CODE=20000
```

Then next release will be v1.1.7 or v2.0.7 (depending on current run_number).

### Manual Version Override

For local testing or manual releases:
```bash
./gradlew bundleRelease \
  -PversionName=1.0.999 \
  -PversionCode=10999
```

### Rollback Strategy

If a release has issues:
```bash
# Revert the problematic commit
git revert <commit-sha>
git push origin main

# Or revert a merge
git revert -m 1 <merge-commit-sha>
git push origin main
```

A new release is automatically created with the fix.

### Quality Gates

All releases must pass:
- ✅ Security scan (Trivy)
- ✅ Lint checks
- ✅ Unit tests
- ✅ Instrumented tests (Android emulator)

Releases only happen if ALL checks pass.

## Testing

The project includes comprehensive testing strategy:

### Unit Tests (`app/src/test/`)
- Basic logic testing
- ElevationService unit conversion tests
- Business logic validation

### Instrumented Tests (`app/src/androidTest/`)
- **ElevationFragmentTest**: UI interactions and component integration
- **LocationPermissionTest**: Permission flow testing with different scenarios
- **StartupCrashTest**: Crash detection and component initialization validation

### Test Commands
```bash
# Run unit tests only (fast)
./gradlew test

# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest

# Run all tests (unit + build + lint)
./gradlew build
```

### Test Dependencies
- `androidx.test.rules`: For permission testing rules
- `androidx.test.runner`: For instrumented test runner
- `androidx.test.espresso.core`: For UI testing
- `androidx.test.ext.junit`: For JUnit extensions
- `hilt-android-testing`: For Hilt dependency injection in tests

### Hilt Testing
All instrumented tests use Hilt for dependency injection:
- **@HiltAndroidTest**: Applied to all test classes requiring Hilt
- **HiltAndroidRule**: Manages Hilt components in tests
- Tests inject dependencies via `@Inject` just like production code
- Rule ordering is important: HiltAndroidRule must be first (order = 0)

### CI Testing
GitHub Actions runs comprehensive quality checks with optimized job dependencies:

**Job Pipeline (Optimized for Speed):**
1. **security**: Trivy vulnerability scanning (runs immediately, parallel)
2. **build-and-test**: Combined lint + unit tests + APK build (single job for efficiency)
3. **instrumented-tests**: Android emulator tests (depends on build-and-test)

**Performance Optimizations:**
- **Combined jobs**: Merged lint, unit tests, and build into single job to eliminate setup overhead
- **Maximum parallelization**: Security scan runs immediately without dependencies
- **Gradle optimizations**:
  - `--parallel` enables multi-module parallel builds
  - `--build-cache` caches intermediate build outputs
  - `gradle.workers.max=4` uses all available CPU cores
  - `kotlin.incremental=false` avoids incremental compilation overhead in CI
  - `--configuration-cache` for faster Gradle configuration phase (Gradle 8.14+)
  - 4GB JVM heap for Gradle daemon, 2GB for Kotlin compiler
  - Configuration on demand for faster project configuration
  - Parallel test execution with `maxParallelForks` equal to CPU cores
- **Advanced caching**:
  - Gradle build cache with read/write optimization
    - `build-and-test` job: write access for cache population
    - `instrumented-tests` job: read-only to avoid cache conflicts
  - Android SDK caching to avoid repeated SDK downloads
  - AVD caching with version-specific keys

### GitHub Workflow Testing

**Always validate workflow changes using `act` before committing:**

```bash
# Install act (if not already installed)
brew install act

# List available workflows and jobs
act --list

# Test specific job (dry run)
act push -j build-and-test --container-architecture linux/amd64 --dryrun

# Full local test run (requires Docker)
act push -j build-and-test --container-architecture linux/amd64

# Test with environment variables
act push -j build-and-test --env GITHUB_TOKEN=fake_token --artifact-server-path /tmp/artifacts
```

**Validation steps to perform:**
1. **YAML syntax validation**: Ensure workflow file parses correctly
2. **Job configuration**: Verify jobs, steps, and dependencies are properly defined
3. **Action references**: Confirm community actions exist and versions are correct
4. **File paths**: Validate that expected artifacts (test results, lint reports) are generated
5. **Permissions**: Ensure required permissions are granted for actions to function

**Note**: Use `--container-architecture linux/amd64` on Apple M-series chips to avoid compatibility issues.

## Key Features

- Real-time elevation display using GPS
- Averaging of multiple elevation readings for accuracy
- Metric/Imperial unit switching with persistent preferences
- Location permission handling with proper rationale dialogs
- Loading animation while acquiring GPS fix
- Material Design theming with dark mode support