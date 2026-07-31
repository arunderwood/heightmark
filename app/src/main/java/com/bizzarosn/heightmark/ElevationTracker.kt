package com.bizzarosn.heightmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * The tracking session's Android shell: the GNSS listener registration, the
 * stationary duty cycle, the geoid conversion, the search timeout, the
 * location-provider broadcast, and the panel-only feeds. It drives the pure
 * policy in [ElevationSession] and publishes a single [ElevationUiState] for
 * hosts to render.
 *
 * A [ViewModel] because the session outlives the view: a rotation no longer
 * restarts the averaging window or re-acquires a fix. The host still owns when
 * tracking may run — [onForeground] and [onBackground] bracket every radio,
 * sensor and receiver this class holds, so nothing keeps drawing power behind
 * a screen the user has left.
 *
 * Confined to the main thread, like the [ElevationSession] it drives: location
 * callbacks are delivered on the main executor and the conversion coroutine
 * resumes there, so none of the state is synchronized.
 */
@HiltViewModel
class ElevationTracker @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val session: ElevationSession,
    private val locationManager: LocationManager,
    private val sensorManager: SensorManager,
    private val altitudeResolver: AltitudeResolver,
    private val idleWakeMonitor: IdleWakeMonitor,
    private val stillnessDetector: StillnessDetector
) : ViewModel() {

    // Matches the layout's starting text, so the first frame after a cold
    // start is the same whether or not the collector has run yet
    private val _uiState = MutableStateFlow(
        ElevationUiState.derive(
            blocked = null,
            locationPromptAnswered = false,
            searchTimedOut = false,
            elevationMeters = null,
            readingState = ReadingState.Acquiring,
            details = null
        )
    )
    val uiState: StateFlow<ElevationUiState> = _uiState.asStateFlow()

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)

    private val detailsSources = DetailsSourcesController(locationManager, sensorManager) {
        publish()
    }

    private var locationListener: LocationListener? = null
    private var searchTimeoutJob: Job? = null

    private var blocked: ElevationUiState.Blocked? = null
    private var locationPromptAnswered = false
    private var searchTimedOut = false
    private var detailsVisible = false
    private var foreground = false
    private var receiverRegistered = false
    private var lastLocation: Location? = null

    /**
     * Whether the coarse-only precise-upgrade dialog has already interrupted
     * this session. Lives here rather than on [LocationPermissionHandler]
     * because that handler is rebuilt on every fragment recreation
     * (rotation, dark-mode switch); this ViewModel survives those and dies
     * only with the session, matching the "once per session" intent.
     */
    var upgradeDialogShown = false

    private val providersChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
            if (!appContext.hasFineLocationPermission()) return
            // startLocationUpdates re-blocks on its own if GPS is still off,
            // and clears the block — dismissing the host's dialog — if it is not
            startLocationUpdates()
        }
    }

    /** The host resolved the location permission. */
    fun onPermissionState(state: LocationPermissionState) {
        when (state) {
            is LocationPermissionState.Granted -> {
                // startLocationUpdates re-verifies the permission itself
                startLocationUpdates()
            }
            is LocationPermissionState.CoarseOnly ->
                block(ElevationUiState.Blocked.PreciseLocationRequired)
            is LocationPermissionState.RequiresRationale ->
                block(ElevationUiState.Blocked.PermissionRequired)
            is LocationPermissionState.PermanentlyDenied ->
                block(ElevationUiState.Blocked.PermissionPermanentlyDenied)
        }
    }

    /**
     * The user answered the location-services prompt, either way. The screen
     * keeps saying that tracking is off, but it stops asking until the block
     * clears and a later one raises the question again.
     */
    fun onLocationPromptAnswered() {
        locationPromptAnswered = true
        publish()
    }

    /** The host became visible: everything that costs power starts here. */
    fun onForeground() {
        foreground = true
        registerProvidersReceiver()
        if (appContext.hasFineLocationPermission()) {
            session.onResumed(SystemClock.elapsedRealtime())
            stillnessDetector.reset()
            startLocationUpdates()
        }
        if (detailsVisible) {
            startDetailsSources()
        }
        publish()
    }

    /** The host went away: everything that costs power stops here. */
    fun onBackground() {
        foreground = false
        session.onPaused(SystemClock.elapsedRealtime())
        unregisterProvidersReceiver()
        detailsSources.stop()
        stopLocationUpdates()
        publish()
    }

    /** The diagnostic panel opened or closed; its feeds are armed to match. */
    fun setDetailsVisible(visible: Boolean) {
        detailsVisible = visible
        if (visible) {
            // A panel opened from the preference load can beat onForeground;
            // that path arms the sources itself once it runs
            if (foreground) startDetailsSources()
        } else {
            detailsSources.stop()
        }
        publish()
    }

    private fun startDetailsSources() {
        detailsSources.start(mainExecutor, viewModelScope, appContext.hasFineLocationPermission())
    }

    private fun startLocationUpdates() {
        // Double-check permissions before starting location updates
        if (!appContext.hasFineLocationPermission()) {
            block(ElevationUiState.Blocked.PermissionRequired)
            return
        }

        if (!isGpsAvailable()) {
            block(ElevationUiState.Blocked.LocationServicesOff)
            return
        }

        val listener = locationListener
            ?: LocationListener { location -> onGnssFix(location) }
                .also { locationListener = it }

        try {
            // Only GNSS fixes carry altitude, so the network provider is useless here.
            // No min update distance: stillness detection needs fixes while parked.
            val request = LocationRequest.Builder(UPDATE_INTERVAL_MS)
                .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                .build()
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                request,
                mainExecutor,
                listener
            )
        } catch (e: SecurityException) {
            // Log the unexpected security exception for debugging
            Log.e(TAG, "Unexpected SecurityException despite permission check", e)
            block(ElevationUiState.Blocked.PermissionRequired)
            return
        }

        updateBlocked(null)
        startSearchTimeout()
        publish()
    }

    private fun startSearchTimeout() {
        if (session.hasFix) return
        searchTimeoutJob?.cancel()
        searchTimedOut = false
        searchTimeoutJob = viewModelScope.launch {
            delay(SEARCH_TIMEOUT_MS)
            if (!session.hasFix) {
                searchTimedOut = true
                publish()
            }
        }
    }

    private fun onGnssFix(location: Location) {
        if (stillnessDetector.feed(location)) {
            goIdle(location)
        }

        val pending = session.offer(location) ?: return
        viewModelScope.launch {
            // Geoid data loads from disk on first use in a region
            val elevation = withContext(Dispatchers.IO) {
                altitudeResolver.mslAltitudeMeters(location)
            }
            // The conversion may have populated a tighter, post-conversion
            // accuracy bound on the same Location; prefer it over the
            // pre-conversion figure session.offer() captured
            val accuracy = location.mslAltitudeAccuracyOrNull() ?: pending.verticalAccuracyMeters
            // The window was flushed while this fix was converting: drop it
            if (!session.commit(pending, elevation, accuracy)) return@launch
            lastLocation = location
            publish()
        }
    }

    /**
     * The device has been still for a while: turn the GPS radio off and let
     * [IdleWakeMonitor] (significant motion, barometer, passive fixes) turn it
     * back on when we move. The displayed elevation stays frozen meanwhile.
     */
    private fun goIdle(anchor: Location) {
        if (idleWakeMonitor.isRunning) return
        stopLocationUpdates()
        try {
            idleWakeMonitor.start(anchor, mainExecutor, viewModelScope) {
                goActive()
            }
            session.enterIdle()
            publish()
        } catch (e: SecurityException) {
            Log.e(TAG, "Lost permission while going idle", e)
            block(ElevationUiState.Blocked.PermissionRequired)
        }
    }

    private fun goActive() {
        // The wake fired because the device moved, so the window is stale
        session.wake()
        stillnessDetector.reset()
        startLocationUpdates()
    }

    private fun stopLocationUpdates() {
        idleWakeMonitor.stop()
        searchTimeoutJob?.cancel()
        searchTimeoutJob = null
        locationListener?.let { listener ->
            locationManager.removeUpdates(listener)
        }
    }

    /** Records why tracking cannot run and tears down whatever was running. */
    private fun block(reason: ElevationUiState.Blocked) {
        updateBlocked(reason)
        stopLocationUpdates()
        publish()
    }

    /**
     * The only writer of [blocked], because answering the settings prompt only
     * silences the block that raised it. Anything else — tracking resumed, a
     * different block — is a new situation, so the prompt is armed again; GPS
     * still being off is not, and re-blocking for that reason keeps it silent.
     */
    private fun updateBlocked(reason: ElevationUiState.Blocked?) {
        if (reason != ElevationUiState.Blocked.LocationServicesOff) {
            locationPromptAnswered = false
        }
        blocked = reason
    }

    private fun isGpsAvailable(): Boolean {
        return locationManager.isLocationEnabled &&
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun registerProvidersReceiver() {
        if (receiverRegistered) return
        // System broadcast, so RECEIVER_NOT_EXPORTED still receives it
        ContextCompat.registerReceiver(
            appContext,
            providersChangedReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterProvidersReceiver() {
        if (!receiverRegistered) return
        appContext.unregisterReceiver(providersChangedReceiver)
        receiverRegistered = false
    }

    /** Rebuilds the whole state; the only writer of [uiState]. */
    private fun publish() {
        _uiState.value = ElevationUiState.derive(
            blocked = blocked,
            locationPromptAnswered = locationPromptAnswered,
            searchTimedOut = searchTimedOut,
            elevationMeters = session.displayedElevationMeters,
            readingState = session.readingState(),
            details = detailsFacts()
        )
    }

    private fun detailsFacts(): ElevationUiState.DetailsFacts? {
        if (!detailsVisible) return null
        return ElevationUiState.DetailsFacts(
            isIdle = session.isIdle,
            isBlocked = blocked != null,
            location = lastLocation,
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            satellitesUsed = detailsSources.satellitesUsed,
            satellitesVisible = detailsSources.satellitesVisible,
            pressureHpa = detailsSources.pressureHpa,
            readingCount = session.readingCount
        )
    }

    override fun onCleared() {
        unregisterProvidersReceiver()
        detailsSources.stop()
        stopLocationUpdates()
        locationListener = null
    }

    companion object {
        private const val TAG = "ElevationTracker"
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val UPDATE_INTERVAL_MS = 1_000L
    }
}
