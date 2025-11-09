# GitHub Issues to Create - HeightMark Audit

Use these templates to create issues on GitHub. Copy each section and create a new issue with the specified title, labels, and body.

---

## HIGH PRIORITY ISSUES (5)

### Issue H1: Add KDoc comments to public classes and functions

**Labels:** `documentation`, `good first issue`

**Body:**
```markdown
## Description
All main source files lack KDoc documentation comments. This makes it harder for developers to understand the intent and usage of public APIs.

## Affected Files
- `ElevationService.kt` - Class and all public functions
- `ElevationTextView.kt` - Custom view class
- `LocationPermissionHandler.kt` - Public API methods
- Other main source files

## Example
```kotlin
/**
 * Service for managing and averaging elevation readings from GPS.
 *
 * @param readingsCount Number of readings to keep in the averaging window
 */
class ElevationService(private val readingsCount: Int) {
    /**
     * Add an elevation reading and return the updated average.
     * If the reading count exceeds [readingsCount], the oldest reading is dropped.
     *
     * @param elevation The elevation reading in meters
     * @return The current average elevation
     */
    fun addElevationReading(elevation: Double): Double { ... }
}
```

## Priority
🔴 High

## Effort
~30 minutes
```

---

### Issue H2: Add contentDescription to unit toggle switch for accessibility

**Labels:** `accessibility`, `good first issue`

**Body:**
```markdown
## Description
The unit toggle switch lacks a `contentDescription` attribute, making it inaccessible to screen readers.

## Location
`app/src/main/res/layout/fragment_elevation.xml:40-46`

## Current Code
```xml
<androidx.appcompat.widget.SwitchCompat
    android:id="@+id/unit_switch"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="center"
    android:layout_marginTop="16dp"
    android:text="@string/use_metric_units" />
```

## Suggested Fix
Add:
```xml
android:contentDescription="@string/toggle_units_description"
```

And in `strings.xml`:
```xml
<string name="toggle_units_description">Switch between metric meters and imperial feet</string>
```

## Priority
🔴 High - **WCAG 2.1 Level A requirement**

## Effort
5 minutes
```

---

### Issue H3: Add contentDescription to ElevationTextView for accessibility

**Labels:** `accessibility`, `good first issue`

**Body:**
```markdown
## Description
The custom ElevationTextView displays important elevation data but has no accessibility label for screen readers.

## Location
`app/src/main/res/layout/fragment_elevation.xml:32-38`

## Current Code
```xml
<com.bizzarosn.heightmark.ElevationTextView
    android:id="@+id/elevation_text_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:gravity="center"
    android:text="@string/elevation_text"
    android:textSize="24sp" />
```

## Suggested Fix
Add dynamic content description in `ElevationTextView.updateElevation()`:
```kotlin
ViewCompat.setContentDescription(this, "Current elevation: $elevation $unit")
```

Or add to strings.xml:
```xml
<string name="elevation_loading_description">Fetching elevation from GPS</string>
```

## Priority
🔴 High - **WCAG 2.1 Level A requirement**

## Effort
10 minutes
```

---

### Issue H4: Add validation and error message for disabled location services

**Labels:** `bug`, `good first issue`

**Body:**
```markdown
## Description
The app silently fails if both GPS and Network location providers are disabled. No user-facing error message is shown.

## Location
`app/src/main/java/com/bizzarosn/heightmark/ElevationFragment.kt:142-150`

## Current Code
```kotlin
if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
    // Use GPS
} else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
    // Use Network
}
// No else clause - silently fails if both disabled!
```

## Suggested Fix
Add else clause:
```kotlin
} else {
    elevationTextView.text = getString(R.string.location_services_disabled)
}
```

And add to `strings.xml`:
```xml
<string name="location_services_disabled">Location services are disabled. Please enable GPS or Network location.</string>
```

## Priority
🔴 High

## Effort
10 minutes
```

---

### Issue H5: Add dedicated accessibility string resources

**Labels:** `accessibility`, `good first issue`

**Body:**
```markdown
## Description
The app needs dedicated string resources for accessibility content descriptions to support screen readers.

## Location
`app/src/main/res/values/strings.xml`

## Suggested Additions
```xml
<!-- Accessibility descriptions -->
<string name="toggle_units_description">Switch between metric meters and imperial feet</string>
<string name="elevation_loading_description">Fetching elevation from GPS</string>
<string name="elevation_value_description">Current elevation: %1$s %2$s</string>
```

## Related Issues
- Supports fixes for switch and elevation text view accessibility
- Can be used by other UI components in future

## Priority
🔴 High - Required for WCAG 2.1 compliance

## Effort
10 minutes
```

---

## MEDIUM PRIORITY ISSUES (9)

### Issue M1: Make location update interval configurable

**Labels:** `enhancement`, `configuration`

**Body:**
```markdown
## Description
Location update interval is hardcoded to 1000ms with no configurability. This prevents adjusting the polling frequency for different use cases (battery life vs. accuracy tradeoff).

## Location
`app/src/main/java/com/bizzarosn/heightmark/ElevationFragment.kt:144,148`

## Current Code
```kotlin
locationManager.requestLocationUpdates(
    LocationManager.GPS_PROVIDER, 1000, 1f, listener  // 1000ms hardcoded
)
```

## Suggested Fix
Extract to a constant or make it configurable through AppModule:
```kotlin
companion object {
    private const val LOCATION_UPDATE_INTERVAL_MS = 1000L
}
```

Or inject via Hilt for runtime configurability.

## Priority
🟡 Medium

## Effort
15 minutes
```

---

### Issue M2: Add documentation to LocationPermissionState sealed class

**Labels:** `documentation`, `good first issue`

**Body:**
```markdown
## Description
LocationPermissionState sealed class and its subclasses lack documentation explaining when each state occurs.

## Location
`app/src/main/java/com/bizzarosn/heightmark/LocationPermissionHandler.kt:16-20`

## Current Code
```kotlin
sealed class LocationPermissionState {
    object Granted : LocationPermissionState()
    object Denied : LocationPermissionState()
    object PermanentlyDenied : LocationPermissionState()
    object RequiresRationale : LocationPermissionState()
}
```

## Suggested Fix
```kotlin
/**
 * Represents the various states of location permission status.
 */
sealed class LocationPermissionState {
    /** Permission has been granted by the user. */
    object Granted : LocationPermissionState()

    /** Permission was denied, but can be requested again. */
    object Denied : LocationPermissionState()

    /** Permission was permanently denied (user selected "Don't ask again"). */
    object PermanentlyDenied : LocationPermissionState()

    /** Permission requires rationale explanation before requesting. */
    object RequiresRationale : LocationPermissionState()
}
```

## Priority
🟡 Medium

## Effort
10 minutes
```

---

### Issue M3: Replace ExampleUnitTest with real unit tests

**Labels:** `testing`, `good first issue`

**Body:**
```markdown
## Description
The file `ExampleUnitTest.kt` contains only a placeholder test (`2 + 2 = 4`) and should be replaced with real unit tests.

## Location
`app/src/test/java/com/bizzarosn/heightmark/ExampleUnitTest.kt`

## Suggested Fix
Rename and implement real tests:
- `ElevationServiceTest.kt` - unit tests for ElevationService
- `PreferencesRepositoryTest.kt` - unit tests for PreferencesRepository

## Example Test
```kotlin
@Test
fun `addElevationReading should average multiple readings`() {
    val service = ElevationService(readingsCount = 3)
    service.addElevationReading(100.0)
    service.addElevationReading(200.0)
    val average = service.addElevationReading(300.0)
    assertEquals(200.0, average, 0.01)
}
```

## Priority
🟡 Medium

## Effort
20 minutes
```

---

### Issue M4: Add unit tests for ElevationTextView

**Labels:** `testing`, `enhancement`

**Body:**
```markdown
## Description
The custom `ElevationTextView` has no unit tests for its animation lifecycle, text formatting, or alpha value changes.

## Areas to Test
- Animation lifecycle (start/stop)
- Text formatting with different units
- Alpha value changes during animation
- Loading state transitions

## Suggested Test File
`app/src/test/java/com/bizzarosn/heightmark/ElevationTextViewTest.kt`

## Example Tests
```kotlin
@Test
fun `startLoadingAnimation should set alpha to 0_5f`() {
    // Test animation start behavior
}

@Test
fun `stopLoadingAnimation should cancel animator`() {
    // Test animation cleanup
}

@Test
fun `updateElevation should format text correctly for metric`() {
    // Test text formatting
}
```

## Priority
🟡 Medium

## Effort
30 minutes
```

---

### Issue M5: Replace Thread.sleep() with proper Android test synchronization

**Labels:** `testing`, `refactoring`

**Body:**
```markdown
## Description
Multiple instrumented tests use `Thread.sleep()` which is fragile, slow, and unreliable. Should use proper Android testing synchronization mechanisms.

## Affected Files
- `app/src/androidTest/java/com/bizzarosn/heightmark/ElevationFragmentTest.kt:64`
- `app/src/androidTest/java/com/bizzarosn/heightmark/StartupCrashTest.kt:47,75,98`
- `app/src/androidTest/java/com/bizzarosn/heightmark/LocationPermissionTest.kt:110`

## Current Anti-Pattern
```kotlin
Thread.sleep(1000)  // Bad: brittle, slow, unreliable
```

## Suggested Fix
Use Espresso's idling resources or proper synchronization:
```kotlin
// Better: Use Espresso's testing mechanisms
onView(withId(R.id.elevation_text_view))
    .check(matches(isDisplayed()))

// Or use Awaitility for more complex conditions
await().atMost(2, SECONDS).until { condition }

// Or use TestDispatchers for coroutine tests
testDispatcher.advanceUntilIdle()
```

## Priority
🟡 Medium

## Effort
45 minutes
```

---

### Issue M6: Consolidate duplicate permission checking logic

**Labels:** `refactoring`, `code quality`

**Body:**
```markdown
## Description
Permission checking logic is duplicated between `ElevationFragment` and `LocationPermissionHandler`. Should have a single source of truth.

## Locations
- `app/src/main/java/com/bizzarosn/heightmark/ElevationFragment.kt:104-112`
- `app/src/main/java/com/bizzarosn/heightmark/LocationPermissionHandler.kt:68-73`

## Current Duplication
```kotlin
// In ElevationFragment
fun hasLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(...) == PERMISSION_GRANTED ||
           ContextCompat.checkSelfPermission(...) == PERMISSION_GRANTED
}

// Same logic in LocationPermissionHandler
fun hasLocationPermission(): Boolean {
    return permissions.any { permission ->
        ContextCompat.checkSelfPermission(...) == PERMISSION_GRANTED
    }
}
```

## Suggested Fix
Move to single source (LocationPermissionHandler or utility class) and use that throughout.

## Priority
🟡 Medium

## Effort
20 minutes
```

---

### Issue M7: Refactor long permission string resource key

**Labels:** `resources`, `code quality`, `good first issue`

**Body:**
```markdown
## Description
The permission rationale string has an extremely long resource key name that's hard to maintain and reference.

## Location
`app/src/main/res/values/strings.xml:9`

## Current Code
```xml
<string name="this_app_needs_location_permission_to_determine_your_elevation_without_this_permission_the_app_cannot_function">
  This app needs location permission to determine your elevation. Without this permission, the app cannot function.
</string>
```

## Suggested Fix
Use a shorter, more maintainable key:
```xml
<string name="permission_rationale">This app needs location permission to determine your elevation. Without this permission, the app cannot function.</string>
```

Or split into multiple strings for different contexts:
```xml
<string name="permission_rationale_title">Location Permission Required</string>
<string name="permission_rationale_message">This app needs location permission to determine your elevation. Without this permission, the app cannot function.</string>
```

## Priority
🟡 Medium

## Effort
10 minutes
```

---

### Issue M8: Fix version management fallback inconsistency

**Labels:** `build`, `configuration`

**Body:**
```markdown
## Description
Local fallback versionCode of 4 doesn't match the release version calculation formula (10000+), which could cause confusion during local development.

## Location
`app/build.gradle.kts:18-19`

## Current Code
```kotlin
versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 4
versionName = project.findProperty("versionName") as String? ?: "1.0.0-dev"
```

## Issue
Release versions use formula `10000 + workflow_run_number`, so local fallback of 4 is inconsistent.

## Suggested Fix
```kotlin
// Use consistent fallback or document why 4 is used
versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 10000
versionName = project.findProperty("versionName") as String? ?: "0.0.0-local"
```

Or add a comment explaining the strategy:
```kotlin
// Local dev uses low versionCode (4), CI uses 10000+ formula
versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 4
```

## Priority
🟡 Medium

## Effort
10 minutes
```

---

### Issue M9: Enhance ProGuard rules for data classes

**Labels:** `build`, `optimization`

**Body:**
```markdown
## Description
ProGuard rules have good coverage for Hilt and Navigation, but could be more specific for data classes and serialization.

## Location
`app/proguard-rules.pro`

## Current State
Basic rules exist for Hilt and Navigation component.

## Suggested Enhancement
```pro
# Keep Kotlin data classes for serialization
-keepclassmembers class com.bizzarosn.heightmark.** {
    public <init>();
    public ** component*();
}

# Keep class members for reflection (if needed)
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Additional rules for any future JSON serialization
-keepattributes Signature
-keepattributes *Annotation*
```

## Priority
🟡 Medium

## Effort
20 minutes
```

---

## LOW PRIORITY ISSUES (8)

### Issue L1: Make elevation reading count configurable

**Labels:** `enhancement`, `configuration`

**Body:**
```markdown
## Description
The number of elevation readings used for averaging (10) is hardcoded and not configurable.

## Location
`app/src/main/java/com/bizzarosn/heightmark/di/AppModule.kt:28`

## Current Code
```kotlin
@Provides
fun provideElevationService(): ElevationService {
    return ElevationService(readingsCount = 10)
}
```

## Suggested Fix
Make it configurable through preferences or extract to a constant:
```kotlin
companion object {
    private const val DEFAULT_READINGS_COUNT = 10
}

@Provides
fun provideElevationService(): ElevationService {
    return ElevationService(readingsCount = DEFAULT_READINGS_COUNT)
}
```

## Priority
🟢 Low

## Effort
15 minutes
```

---

### Issue L2: Extract hardcoded animation duration to constant

**Labels:** `refactoring`, `good first issue`

**Body:**
```markdown
## Description
Loading animation duration is hardcoded to 1000ms as a magic number.

## Location
`app/src/main/java/com/bizzarosn/heightmark/ElevationTextView.kt:23`

## Current Code
```kotlin
animator.apply {
    duration = 1000
    repeatCount = ValueAnimator.INFINITE
    repeatMode = ValueAnimator.REVERSE
}
```

## Suggested Fix
```kotlin
companion object {
    private const val LOADING_ANIMATION_DURATION_MS = 1000L
}

animator.apply {
    duration = LOADING_ANIMATION_DURATION_MS
    repeatCount = ValueAnimator.INFINITE
    repeatMode = ValueAnimator.REVERSE
}
```

## Priority
🟢 Low

## Effort
10 minutes
```

---

### Issue L3: Extract meters-to-feet conversion factor to named constant

**Labels:** `refactoring`, `good first issue`

**Body:**
```markdown
## Description
The meters-to-feet conversion factor (3.28084) is a magic number without documentation.

## Location
`app/src/main/java/com/bizzarosn/heightmark/ElevationService.kt:20`

## Current Code
```kotlin
fun getFormattedElevation(useMetric: Boolean): String {
    return if (useMetric) averageElevation else averageElevation * 3.28084
    // ...
}
```

## Suggested Fix
```kotlin
companion object {
    /** Conversion factor from meters to feet */
    private const val METERS_TO_FEET_CONVERSION = 3.28084
}

fun getFormattedElevation(useMetric: Boolean): String {
    return if (useMetric) {
        averageElevation
    } else {
        averageElevation * METERS_TO_FEET_CONVERSION
    }
    // ...
}
```

## Priority
🟢 Low

## Effort
5 minutes
```

---

### Issue L4: Add lifecycle documentation to locationListener management

**Labels:** `documentation`

**Body:**
```markdown
## Description
The `locationListener` variable's lifecycle management across pause/resume cycles could be better documented.

## Location
`app/src/main/java/com/bizzarosn/heightmark/ElevationFragment.kt:38,159-187`

## Current Code
```kotlin
private var locationListener: LocationListener? = null

override fun onResume() {
    if (hasLocationPermission()) {
        startLocationUpdates()  // Creates new listener if null
    }
}

override fun onPause() {
    stopLocationUpdates()  // Removes but keeps reference
}
```

## Suggested Fix
Add KDoc explaining the lifecycle:
```kotlin
/**
 * Location listener for receiving GPS updates.
 * Created in [startLocationUpdates] and removed in [stopLocationUpdates].
 * The reference is kept across pause/resume cycles but the listener is
 * unregistered to conserve battery.
 */
private var locationListener: LocationListener? = null
```

## Priority
🟢 Low

## Effort
10 minutes
```

---

### Issue L5: Add more specific error logging in SecurityException handler

**Labels:** `logging`, `good first issue`

**Body:**
```markdown
## Description
The SecurityException catch block could provide more specific debugging information.

## Location
`app/src/main/java/com/bizzarosn/heightmark/ElevationFragment.kt:151-155`

## Current Code
```kotlin
} catch (e: SecurityException) {
    Log.e("ElevationFragment", "Unexpected SecurityException despite permission check", e)
    elevationTextView.text = getString(R.string.location_permission_required)
}
```

## Suggested Fix
```kotlin
} catch (e: SecurityException) {
    Log.e(
        "ElevationFragment",
        "Unexpected SecurityException despite permission check. " +
        "Provider: $provider, Permission status: ${hasLocationPermission()}",
        e
    )
    elevationTextView.text = getString(R.string.location_permission_required)
}
```

## Priority
🟢 Low

## Effort
10 minutes
```

---

### Issue L6: Define explicit color palette in colors.xml

**Labels:** `resources`, `theming`

**Body:**
```markdown
## Description
The `colors.xml` file is empty. An explicit color palette would make theming easier.

## Location
`app/src/main/res/values/colors.xml`

## Current State
File is empty or has minimal content.

## Suggested Fix
Define a proper color palette:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Primary colors -->
    <color name="primary">#6200EE</color>
    <color name="primary_variant">#3700B3</color>
    <color name="secondary">#03DAC6</color>

    <!-- Background colors -->
    <color name="background">#FFFFFF</color>
    <color name="surface">#FFFFFF</color>

    <!-- Text colors -->
    <color name="on_primary">#FFFFFF</color>
    <color name="on_background">#000000</color>
    <color name="on_surface">#000000</color>

    <!-- Custom app colors -->
    <color name="elevation_text">#000000</color>
</resources>
```

## Priority
🟢 Low

## Effort
15 minutes
```

---

### Issue L7: Add @Suppress annotation for deprecated LocationListener method

**Labels:** `code quality`, `good first issue`

**Body:**
```markdown
## Description
The deprecated `onStatusChanged` method is correctly handled but could use `@Suppress("DEPRECATION")` for clarity.

## Location
`app/src/main/java/com/bizzarosn/heightmark/ElevationFragment.kt:130-131`

## Current Code
```kotlin
@Deprecated("Deprecated in Java")
override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
```

## Suggested Fix
```kotlin
@Suppress("DEPRECATION")
@Deprecated("Deprecated in Java")
override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    // No-op: Required for LocationListener interface but deprecated in API 29+
}
```

## Priority
🟢 Low

## Effort
2 minutes
```

---

### Issue L8: Create Constants.kt for all magic numbers

**Labels:** `refactoring`, `code quality`

**Body:**
```markdown
## Description
Consolidate all hardcoded numeric values (intervals, counts, conversion factors) into a single Constants file for easier maintenance.

## Suggested File
`app/src/main/java/com/bizzarosn/heightmark/Constants.kt`

## Example Content
```kotlin
package com.bizzarosn.heightmark

object Constants {
    /** GPS location update interval in milliseconds */
    const val LOCATION_UPDATE_INTERVAL_MS = 1000L

    /** Number of elevation readings to average */
    const val ELEVATION_READINGS_COUNT = 10

    /** Loading animation duration in milliseconds */
    const val LOADING_ANIMATION_DURATION_MS = 1000L

    /** Conversion factor from meters to feet */
    const val METERS_TO_FEET_CONVERSION = 3.28084

    /** Minimum distance change for location updates in meters */
    const val MIN_DISTANCE_CHANGE_METERS = 1f
}
```

## Related Issues
Consolidates L1, L2, L3, and M1

## Priority
🟢 Low

## Effort
20 minutes
```

---

## Summary

- **Total Issues: 22**
  - 🔴 High Priority: 5
  - 🟡 Medium Priority: 9
  - 🟢 Low Priority: 8

## Quick Wins (Easiest to implement)
1. H2 - Add contentDescription to switch (5 min)
2. L3 - Extract conversion factor constant (5 min)
3. H5 - Add accessibility strings (10 min)
4. H4 - Fix location services validation (10 min)
5. M2 - Document permission states (10 min)

## Labels Used
- `accessibility` - Accessibility improvements
- `documentation` - Documentation additions
- `testing` - Test improvements
- `refactoring` - Code refactoring
- `enhancement` - New features or improvements
- `bug` - Bug fixes
- `good first issue` - Easy for new contributors
- `configuration` - Configuration changes
- `code quality` - Code quality improvements
- `build` - Build system changes
- `resources` - Resource file changes
- `logging` - Logging improvements
- `theming` - Theme and styling
- `optimization` - Performance optimizations
