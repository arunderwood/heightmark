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

- **MainActivity**: Entry point with navigation setup using Navigation Component
- **ElevationFragment**: Main UI fragment that handles location permissions and displays elevation
- **ElevationService**: Business logic for averaging elevation readings (configurable number of readings)
- **ElevationTextView**: Custom TextView with loading animation for elevation display
- **LocationPermissionHandler**: Robust permission handler with state management and lifecycle awareness
- **PreferencesRepository**: DataStore-based persistence for user preferences (metric/imperial units)

### Permission Handling

The app uses a sophisticated permission handling system:
- **LocationPermissionState**: Sealed class defining permission states (Granted, Denied, PermanentlyDenied, RequiresRationale)
- **Lifecycle-aware**: Automatically cleans up dialogs and resources when fragment is destroyed
- **Multiple permissions**: Handles both ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION
- **Proper state management**: Clear separation between permission states and UI responses

## Key Technical Details

- **Target SDK**: 35 (Android 15)
- **Minimum SDK**: 35 (Android 15)
- **Language**: Kotlin
- **Architecture Components**: Navigation Component, DataStore Preferences
- **Location**: Uses GPS_PROVIDER for elevation readings, averages multiple readings for accuracy
- **Permissions**: Requires ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION
- **Dependencies**: Uses version catalog (gradle/libs.versions.toml) for dependency management

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
- **Advanced caching**:
  - Gradle build cache with read/write optimization
  - Android SDK caching with improved cache keys
  - AVD caching with version-specific keys
- **Emulator optimizations**:
  - Increased RAM (4GB) and heap (512MB) for faster test execution
  - `cache-read-only=true` for instrumented tests to avoid cache conflicts
- **Artifact retention**: 7-day retention to reduce storage costs

**Speed Improvements:**
- ~60% faster total CI time through job consolidation
- ~40% faster Gradle builds through parallelization and caching
- ~30% faster emulator startup through optimized AVD caching
- Immediate security scanning without waiting for setup

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