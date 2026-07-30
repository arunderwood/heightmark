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
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.google.android.material.button.MaterialButtonToggleGroup
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ElevationFragment : Fragment() {

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var elevationService: ElevationService

    @Inject
    lateinit var locationManager: LocationManager

    @Inject
    lateinit var altitudeResolver: AltitudeResolver

    @Inject
    lateinit var idleWakeMonitor: IdleWakeMonitor

    @Inject
    lateinit var sensorManager: SensorManager

    @Inject
    lateinit var stillnessDetector: StillnessDetector

    private lateinit var elevationTextView: TextView
    private lateinit var stabilityLine: StabilityLineView
    private lateinit var detailsToggle: TextView
    private lateinit var detailsPanel: TextView
    private var useMetricUnit = true
    private var hasFix = false
    private var locationListener: LocationListener? = null
    private var searchTimeoutJob: Job? = null
    private var locationOffDialog: AlertDialog? = null

    // The averaging window is flushed after gaps (background return, idle
    // wake); the epoch drops geoid-conversion coroutines launched before a
    // flush, and awaitingFreshFix keeps the cached number dimmed until the
    // first post-flush reading lands.
    private var readingEpoch = 0
    private var awaitingFreshFix = false
    private var pausedAtElapsedMs = 0L
    private var lastDisplayedElevationMeters: Double? = null

    // Details panel state
    private var showDetails = false
    private var isIdle = false
    private var lastLocation: Location? = null

    private lateinit var permissionHandler: LocationPermissionHandler
    private lateinit var detailsSources: DetailsSourcesController

    private val providersChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != LocationManager.PROVIDERS_CHANGED_ACTION) return
            if (!permissionHandler.hasFinePermission()) return
            if (isGpsAvailable()) {
                locationOffDialog?.dismiss()
                locationOffDialog = null
                startLocationUpdates()
            } else {
                stopLocationUpdates()
                showLocationOff()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionHandler = LocationPermissionHandler(this) { state ->
            handlePermissionStateChange(state)
        }
        permissionHandler.initialize()

        detailsSources = DetailsSourcesController(locationManager, sensorManager) {
            refreshDetails()
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_elevation, container, false)

        elevationTextView = view.findViewById(R.id.elevation_text_view)
        stabilityLine = view.findViewById(R.id.stability_line)
        detailsToggle = view.findViewById(R.id.details_toggle)
        detailsPanel = view.findViewById(R.id.details_panel)
        val unitToggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.unit_toggle_group)

        lifecycleScope.launch {
            useMetricUnit = preferencesRepository.useMetricUnit.first()
            unitToggleGroup.check(if (useMetricUnit) R.id.button_meters else R.id.button_feet)
            applyDetailsVisibility(preferencesRepository.showDetails.first())
            permissionHandler.checkPermission()
        }

        unitToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            useMetricUnit = checkedId == R.id.button_meters
            lifecycleScope.launch {
                preferencesRepository.setUseMetricUnit(useMetricUnit)
                updateUIWithElevation()
                refreshDetails()
            }
        }

        detailsToggle.setOnClickListener {
            lifecycleScope.launch {
                preferencesRepository.setShowDetails(!showDetails)
                applyDetailsVisibility(!showDetails)
            }
        }

        return view
    }

    private fun applyDetailsVisibility(show: Boolean) {
        showDetails = show
        detailsPanel.isVisible = show
        detailsToggle.text = getString(if (show) R.string.details_hide else R.string.details_show)
        detailsToggle.stateDescription = getString(
            if (show) R.string.details_state_expanded else R.string.details_state_collapsed
        )
        if (show) {
            startDetailsSources()
            refreshDetails()
        } else {
            detailsSources.stop()
        }
    }

    private fun startDetailsSources() {
        detailsSources.start(
            ContextCompat.getMainExecutor(requireContext()),
            viewLifecycleOwner.lifecycleScope,
            permissionHandler.hasFinePermission()
        )
    }

    private fun handlePermissionStateChange(state: LocationPermissionState) {
        when (state) {
            is LocationPermissionState.Granted -> {
                // startLocationUpdates re-verifies the permission itself
                startLocationUpdates()
            }
            is LocationPermissionState.CoarseOnly -> {
                stopLocationUpdates()
                showBlockedStatus(R.string.precise_location_required)
            }
            is LocationPermissionState.PermanentlyDenied,
            is LocationPermissionState.RequiresRationale -> {
                stopLocationUpdates()
                showBlockedStatus(R.string.location_permission_required)
            }
        }
    }

    private fun isGpsAvailable(): Boolean {
        return locationManager.isLocationEnabled &&
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun startLocationUpdates() {
        // Double-check permissions before starting location updates
        if (!permissionHandler.hasFinePermission()) {
            showBlockedStatus(R.string.location_permission_required)
            return
        }

        if (!isGpsAvailable()) {
            showLocationOff()
            return
        }

        if (locationListener == null) {
            locationListener = LocationListener { location -> onGnssFix(location) }
        }

        if (!hasFix) {
            showStatusText(getString(R.string.loading_elevation))
        }
        applyReadingState()

        locationListener?.let { listener ->
            try {
                // Only GNSS fixes carry altitude, so the network provider is useless here.
                // No min update distance: stillness detection needs fixes while parked.
                val request = LocationRequest.Builder(UPDATE_INTERVAL_MS)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .build()
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    request,
                    ContextCompat.getMainExecutor(requireContext()),
                    listener
                )
                startSearchTimeout()
            } catch (e: SecurityException) {
                // Log the unexpected security exception for debugging
                Log.e(TAG, "Unexpected SecurityException despite permission check", e)
                showBlockedStatus(R.string.location_permission_required)
            }
        }
    }

    private fun startSearchTimeout() {
        if (hasFix) return
        searchTimeoutJob?.cancel()
        searchTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SEARCH_TIMEOUT_MS)
            if (!hasFix) {
                showStatusText(getString(R.string.still_searching))
            }
        }
    }

    private fun onGnssFix(location: Location) {
        if (stillnessDetector.feed(location)) {
            goIdle(location)
        }

        // A fix without altitude would read as 0.0 and poison the average
        if (!location.hasAltitude()) return
        // Skip fixes whose vertical error would drag the average around. A fix
        // that reports no vertical accuracy is kept: unknown is not the same as bad.
        val reportedAccuracy = location.verticalAccuracyOrNull()
        if (reportedAccuracy != null && reportedAccuracy > MAX_VERTICAL_ACCURACY_M) return

        val epoch = readingEpoch
        viewLifecycleOwner.lifecycleScope.launch {
            // Geoid data loads from disk on first use in a region
            val elevation = withContext(Dispatchers.IO) {
                altitudeResolver.mslAltitudeMeters(location)
            }
            // The window was flushed while this fix was converting: drop it
            if (epoch != readingEpoch) return@launch
            val verticalAccuracy =
                reportedAccuracy ?: ElevationService.DEFAULT_VERTICAL_ACCURACY_M
            elevationService.addElevationReading(elevation, verticalAccuracy)
            hasFix = true
            awaitingFreshFix = false
            lastLocation = location
            updateUIWithElevation()
            applyReadingState()
            refreshDetails()
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
            idleWakeMonitor.start(
                anchor,
                ContextCompat.getMainExecutor(requireContext()),
                viewLifecycleOwner.lifecycleScope
            ) {
                goActive()
            }
            isIdle = true
            applyReadingState()
            refreshDetails()
        } catch (e: SecurityException) {
            Log.e(TAG, "Lost permission while going idle", e)
            showBlockedStatus(R.string.location_permission_required)
        }
    }

    private fun goActive() {
        isIdle = false
        stillnessDetector.reset()
        // The wake fired because the device moved, so the window is stale
        flushReadings()
        startLocationUpdates()
        refreshDetails()
    }

    /** Discard the averaging window; the cached number stays on screen, dimmed. */
    private fun flushReadings() {
        elevationService.reset()
        readingEpoch++
        if (hasFix) {
            awaitingFreshFix = true
        }
    }

    private fun refreshDetails() {
        if (!showDetails || !::detailsPanel.isInitialized) return

        val rows = DetailsPanelPresenter.rows(
            DetailsPanelPresenter.Input(
                isIdle = isIdle,
                location = lastLocation,
                nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                satellitesUsed = detailsSources.satellitesUsed,
                satellitesVisible = detailsSources.satellitesVisible,
                pressureHpa = detailsSources.pressureHpa,
                readingCount = elevationService.snapshot().readingCount,
                useMetric = useMetricUnit,
                locale = Locale.getDefault()
            )
        )
        detailsPanel.text = rows.joinToString("\n") { resolve(it) }
    }

    /** Resolves a presenter row, flattening the one nested length resource. */
    private fun resolve(row: DetailsPanelPresenter.Row): String {
        // Argument-less rows skip the formatting overload, which would choke on
        // a translation containing a literal percent sign
        if (row.args.isEmpty()) return getString(row.templateRes)
        val args = row.args.map { arg ->
            if (arg is LengthFormatter.Detail) getString(arg.templateRes, arg.valueText) else arg
        }
        return getString(row.templateRes, *args.toTypedArray())
    }

    private fun stopLocationUpdates() {
        idleWakeMonitor.stop()
        searchTimeoutJob?.cancel()
        searchTimeoutJob = null
        locationListener?.let { listener ->
            locationManager.removeUpdates(listener)
        }
    }

    override fun onPause() {
        super.onPause()
        pausedAtElapsedMs = SystemClock.elapsedRealtime()
        requireContext().unregisterReceiver(providersChangedReceiver)
        locationOffDialog?.dismiss()
        locationOffDialog = null
        detailsSources.stop()
        stopLocationUpdates()
        isIdle = false
    }

    override fun onResume() {
        super.onResume()
        // System broadcast, so RECEIVER_NOT_EXPORTED still receives it
        ContextCompat.registerReceiver(
            requireContext(),
            providersChangedReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (permissionHandler.hasFinePermission()) {
            // A long gap away from the app can mean a new elevation: start
            // the average over rather than walking the stale window there
            if (pausedAtElapsedMs != 0L &&
                SystemClock.elapsedRealtime() - pausedAtElapsedMs > RESET_AFTER_GAP_MS
            ) {
                flushReadings()
            }
            stillnessDetector.reset()
            startLocationUpdates()
        }
        if (showDetails) {
            startDetailsSources()
        }
    }

    /**
     * Push the derived [ReadingState] to the settling line and the hero
     * number's opacity. Called whenever any of its inputs change.
     */
    private fun applyReadingState() {
        if (!::stabilityLine.isInitialized) return
        val state = ReadingState.derive(
            hasFixEver = hasFix,
            isIdle = isIdle,
            awaitingFreshFix = awaitingFreshFix,
            snapshot = elevationService.snapshot()
        )
        stabilityLine.isVisible = true
        stabilityLine.setState(state)
        elevationTextView.animate()
            .alpha(ReadingState.heroAlpha(state))
            .setDuration(HERO_FADE_MS)
            .start()
    }

    // Errors get explanatory status text; a kinetic signal widget alongside
    // would contradict it
    private fun hideStabilityLine() {
        if (::stabilityLine.isInitialized) {
            stabilityLine.isVisible = false
        }
        elevationTextView.animate().cancel()
        elevationTextView.alpha = 1f
    }

    /** Blocked states (missing permission, GPS off) share this presentation. */
    private fun showBlockedStatus(@StringRes messageRes: Int) {
        hideStabilityLine()
        showStatusText(getString(messageRes))
    }

    private fun showLocationOff() {
        showBlockedStatus(R.string.location_services_off)

        if (locationOffDialog?.isShowing == true) return
        locationOffDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.location_services_off))
            .setMessage(getString(R.string.location_services_off_message))
            .setPositiveButton(getString(R.string.open_location_settings)) { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        locationOffDialog?.show()
    }

    /** The hero TextView's two mutually exclusive configurations. */
    private sealed interface HeroMode {
        data class Status(val text: String) : HeroMode
        data class Value(val meters: Double) : HeroMode
    }

    private fun showStatusText(text: String) {
        renderHero(HeroMode.Status(text))
    }

    /**
     * The only writer of the hero TextView. Both modes set the same
     * properties so switching modes can never leave a stale one behind.
     */
    private fun renderHero(mode: HeroMode) {
        when (mode) {
            is HeroMode.Status -> {
                // Status messages use headline type; the hero display size is
                // reserved for the value
                applyTextAppearance(com.google.android.material.R.attr.textAppearanceHeadlineSmall)
                // Status sentences run long ("Still searching…") and must not clip
                elevationTextView.maxLines = 3
                // Errors and search-status changes are the moments a screen-reader
                // user must hear unprompted; the ticking elevation number is not
                elevationTextView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                elevationTextView.contentDescription = null
                elevationTextView.text = mode.text
            }
            is HeroMode.Value -> {
                applyTextAppearance(
                    com.google.android.material.R.attr.textAppearanceDisplayLargeEmphasized
                )
                elevationTextView.maxLines = 2
                // The value refreshes ~every second while converging; a live region
                // would make TalkBack announce every tick. Screen-reader users read
                // the value on focus instead, with the unit spoken in full.
                elevationTextView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                val rounded = LengthFormatter.heroValue(mode.meters, useMetricUnit)
                val unit = getString(LengthFormatter.unitRes(useMetricUnit))
                elevationTextView.text = getString(R.string.elevation_text, rounded, unit)
                val spokenUnit = getString(LengthFormatter.spokenUnitRes(useMetricUnit))
                elevationTextView.contentDescription =
                    getString(R.string.elevation_a11y, rounded, spokenUnit)
            }
        }
    }

    private fun applyTextAppearance(textAppearanceAttr: Int) {
        val resolved = TypedValue()
        requireContext().theme.resolveAttribute(textAppearanceAttr, resolved, true)
        elevationTextView.setTextAppearance(resolved.resourceId)
        elevationTextView.setTextColor(requireContext().getColor(R.color.hm_on_scrim))
    }

    private fun updateUIWithElevation() {
        // No reading yet (e.g. units toggled before the first GPS fix) — keep the loading state
        if (!hasFix) return

        val averageMeters = elevationService.snapshot().averageMeters
        if (!averageMeters.isNaN()) {
            lastDisplayedElevationMeters = averageMeters
        }
        // The window can be empty right after a flush; keep showing the
        // cached value (dimmed via the Dormant state) until fresh fixes land
        val meters = lastDisplayedElevationMeters ?: return

        renderHero(HeroMode.Value(meters))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        locationListener = null
    }

    companion object {
        private const val TAG = "ElevationFragment"
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val MAX_VERTICAL_ACCURACY_M = 50f
        private const val RESET_AFTER_GAP_MS = 30_000L
        private const val HERO_FADE_MS = 250L
    }
}
