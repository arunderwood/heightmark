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
    lateinit var session: ElevationSession

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
    private var locationListener: LocationListener? = null
    private var searchTimeoutJob: Job? = null
    private var locationOffDialog: AlertDialog? = null

    // Details panel state
    private var showDetails = false
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

        if (!session.hasFix) {
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
        if (session.hasFix) return
        searchTimeoutJob?.cancel()
        searchTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SEARCH_TIMEOUT_MS)
            if (!session.hasFix) {
                showStatusText(getString(R.string.still_searching))
            }
        }
    }

    private fun onGnssFix(location: Location) {
        if (stillnessDetector.feed(location)) {
            goIdle(location)
        }

        val pending = session.offer(location) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // Geoid data loads from disk on first use in a region
            val elevation = withContext(Dispatchers.IO) {
                altitudeResolver.mslAltitudeMeters(location)
            }
            // The window was flushed while this fix was converting: drop it
            if (!session.commit(pending, elevation)) return@launch
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
            session.enterIdle()
            applyReadingState()
            refreshDetails()
        } catch (e: SecurityException) {
            Log.e(TAG, "Lost permission while going idle", e)
            showBlockedStatus(R.string.location_permission_required)
        }
    }

    private fun goActive() {
        // The wake fired because the device moved, so the window is stale
        session.wake()
        stillnessDetector.reset()
        startLocationUpdates()
        refreshDetails()
    }

    private fun refreshDetails() {
        if (!showDetails || !::detailsPanel.isInitialized) return

        val rows = DetailsPanelPresenter.rows(
            DetailsPanelPresenter.Input(
                isIdle = session.isIdle,
                location = lastLocation,
                nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                satellitesUsed = detailsSources.satellitesUsed,
                satellitesVisible = detailsSources.satellitesVisible,
                pressureHpa = detailsSources.pressureHpa,
                readingCount = session.readingCount,
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
        session.onPaused(SystemClock.elapsedRealtime())
        requireContext().unregisterReceiver(providersChangedReceiver)
        locationOffDialog?.dismiss()
        locationOffDialog = null
        detailsSources.stop()
        stopLocationUpdates()
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
            session.onResumed(SystemClock.elapsedRealtime())
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
        val state = session.readingState()
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
        // Null before the first fix (e.g. units toggled while still searching),
        // and held across a flush so the cached value stays on screen — dimmed
        // via the Dormant state — until fresh fixes land
        val meters = session.displayedElevationMeters ?: return
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
        private const val HERO_FADE_MS = 250L
    }
}
