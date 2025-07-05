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
GitHub Actions runs both unit and instrumented tests:
- **build** job: Unit tests and compilation
- **instrumented-tests** job: Full integration tests on Android emulator

**Key Testing Areas:**
- Startup crash detection
- Permission flow validation (no permissions, fine only, coarse only, both)
- UI component initialization
- Fragment lifecycle management
- Component integration testing

## Key Features

- Real-time elevation display using GPS
- Averaging of multiple elevation readings for accuracy
- Metric/Imperial unit switching with persistent preferences
- Location permission handling with proper rationale dialogs
- Loading animation while acquiring GPS fix
- Material Design theming with dark mode support