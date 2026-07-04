package com.bizzarosn.heightmark

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Color
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.loadingindicator.LoadingIndicator
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

    private lateinit var elevationTextView: TextView
    private lateinit var loadingIndicator: LoadingIndicator
    private lateinit var detailsToggle: TextView
    private lateinit var detailsPanel: TextView
    private var useMetricUnit = true
    private var hasFix = false
    private var locationListener: LocationListener? = null
    private var searchTimeoutJob: Job? = null
    private var locationOffDialog: AlertDialog? = null
    private val stillnessDetector = StillnessDetector()

    // Details panel state
    private var showDetails = false
    private var isIdle = false
    private var lastLocation: Location? = null
    private var satellitesUsed = 0
    private var satellitesVisible = 0
    private var lastPressureHpa: Float? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var pressurePanelListener: SensorEventListener? = null
    private var detailsTickerJob: Job? = null

    private lateinit var permissionHandler: LocationPermissionHandler

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
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_elevation, container, false)

        elevationTextView = view.findViewById(R.id.elevation_text_view)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
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
        if (show) {
            startDetailsSources()
            refreshDetails()
        } else {
            stopDetailsSources()
        }
    }

    /** Extra data feeds (satellites, pressure, fix-age ticker) used only by the panel. */
    private fun startDetailsSources() {
        if (gnssStatusCallback == null && hasLocationPermission()) {
            val callback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    satellitesVisible = status.satelliteCount
                    satellitesUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
                    refreshDetails()
                }
            }
            try {
                locationManager.registerGnssStatusCallback(
                    ContextCompat.getMainExecutor(requireContext()), callback
                )
                gnssStatusCallback = callback
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot register GnssStatus callback", e)
            }
        }

        if (pressurePanelListener == null) {
            sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { sensor ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        lastPressureHpa = event.values[0]
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                if (sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)) {
                    pressurePanelListener = listener
                }
            }
        }

        if (detailsTickerJob == null) {
            detailsTickerJob = viewLifecycleOwner.lifecycleScope.launch {
                while (true) {
                    delay(1_000)
                    refreshDetails()
                }
            }
        }
    }

    private fun stopDetailsSources() {
        gnssStatusCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
        gnssStatusCallback = null
        pressurePanelListener?.let { sensorManager.unregisterListener(it) }
        pressurePanelListener = null
        detailsTickerJob?.cancel()
        detailsTickerJob = null
    }

    private fun handlePermissionStateChange(state: LocationPermissionState) {
        when (state) {
            is LocationPermissionState.Granted -> {
                if (hasLocationPermission()) {
                    startLocationUpdates()
                } else {
                    // This shouldn't happen, but handle gracefully
                    showPermissionRequired()
                }
            }
            is LocationPermissionState.CoarseOnly -> {
                stopLocationUpdates()
                showPreciseLocationRequired()
            }
            is LocationPermissionState.Denied,
            is LocationPermissionState.PermanentlyDenied -> {
                stopLocationUpdates()
                showPermissionRequired()
            }
            is LocationPermissionState.RequiresRationale -> {
                stopLocationUpdates()
                showPermissionRequired()
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isGpsAvailable(): Boolean {
        return locationManager.isLocationEnabled &&
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun startLocationUpdates() {
        // Double-check permissions before starting location updates
        if (!hasLocationPermission()) {
            showPermissionRequired()
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
            setLoading(true)
        }

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
                showPermissionRequired()
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
        // Skip fixes whose vertical error would drag the average around
        if (location.hasVerticalAccuracy() &&
            location.verticalAccuracyMeters > MAX_VERTICAL_ACCURACY_M
        ) {
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // Geoid data loads from disk on first use in a region
            val elevation = withContext(Dispatchers.IO) {
                altitudeResolver.mslAltitudeMeters(location)
            }
            elevationService.addElevationReading(elevation)
            hasFix = true
            lastLocation = location
            updateUIWithElevation()
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
            refreshDetails()
        } catch (e: SecurityException) {
            Log.e(TAG, "Lost permission while going idle", e)
            showPermissionRequired()
        }
    }

    private fun goActive() {
        isIdle = false
        stillnessDetector.reset()
        startLocationUpdates()
        refreshDetails()
    }

    private fun refreshDetails() {
        if (!showDetails || !::detailsPanel.isInitialized) return

        val lines = mutableListOf<String>()
        lines += getString(if (isIdle) R.string.detail_state_idle else R.string.detail_state_tracking)

        val location = lastLocation
        if (location == null) {
            lines += getString(R.string.detail_no_fix)
        } else {
            if (location.hasMslAltitude()) {
                lines += getString(R.string.detail_msl, formatLength(location.mslAltitudeMeters))
            }
            lines += getString(R.string.detail_ellipsoid, formatLength(location.altitude))
            if (location.hasMslAltitude()) {
                lines += getString(
                    R.string.detail_geoid_offset,
                    formatLength(location.altitude - location.mslAltitudeMeters)
                )
            }
            val verticalAccuracy = if (location.hasVerticalAccuracy()) {
                formatLength(location.verticalAccuracyMeters.toDouble())
            } else {
                "?"
            }
            val horizontalAccuracy = if (location.hasAccuracy()) {
                formatLength(location.accuracy.toDouble())
            } else {
                "?"
            }
            lines += getString(R.string.detail_accuracy, verticalAccuracy, horizontalAccuracy)
            lines += getString(
                R.string.detail_position,
                String.format(Locale.getDefault(), "%.5f", location.latitude),
                String.format(Locale.getDefault(), "%.5f", location.longitude)
            )
            val fixAgeSeconds =
                (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000
            lines += getString(R.string.detail_fix_age, fixAgeSeconds)
        }

        lines += getString(R.string.detail_satellites, satellitesUsed, satellitesVisible)
        lastPressureHpa?.let { pressure ->
            lines += getString(
                R.string.detail_pressure,
                String.format(Locale.getDefault(), "%.1f", pressure)
            )
        }
        lines += getString(R.string.detail_readings, elevationService.readingCount())

        detailsPanel.text = lines.joinToString("\n")
    }

    private fun formatLength(meters: Double): String {
        return if (useMetricUnit) {
            getString(R.string.value_meters, String.format(Locale.getDefault(), "%.1f", meters))
        } else {
            getString(
                R.string.value_feet,
                String.format(Locale.getDefault(), "%.0f", UnitConverter.metersToFeet(meters))
            )
        }
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
        requireContext().unregisterReceiver(providersChangedReceiver)
        locationOffDialog?.dismiss()
        locationOffDialog = null
        stopDetailsSources()
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
        if (hasLocationPermission()) {
            stillnessDetector.reset()
            startLocationUpdates()
        }
        if (showDetails) {
            startDetailsSources()
        }
    }

    private fun setLoading(loading: Boolean) {
        loadingIndicator.isVisible = loading
        if (loading) {
            showStatusText(getString(R.string.loading_elevation))
        }
    }

    private fun showPermissionRequired() {
        setLoading(false)
        showStatusText(getString(R.string.location_permission_required))
    }

    private fun showPreciseLocationRequired() {
        setLoading(false)
        showStatusText(getString(R.string.precise_location_required))
    }

    private fun showLocationOff() {
        setLoading(false)
        showStatusText(getString(R.string.location_services_off))

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

    // Status messages use headline type; the hero display size is reserved for the value
    private fun showStatusText(text: String) {
        applyTextAppearance(com.google.android.material.R.attr.textAppearanceHeadlineSmall)
        elevationTextView.text = text
    }

    private fun applyTextAppearance(textAppearanceAttr: Int) {
        val resolved = TypedValue()
        requireContext().theme.resolveAttribute(textAppearanceAttr, resolved, true)
        TextViewCompat.setTextAppearance(elevationTextView, resolved.resourceId)
        elevationTextView.setTextColor(Color.WHITE)
    }

    private fun updateUIWithElevation() {
        // No reading yet (e.g. units toggled before the first GPS fix) — keep the loading state
        if (!hasFix) return

        setLoading(false)
        applyTextAppearance(com.google.android.material.R.attr.textAppearanceDisplayLargeEmphasized)
        val localizedElevation = elevationService.getLocalizedElevation(useMetricUnit)
        val elevationRounded = kotlin.math.round(localizedElevation).toInt()
        val unit = getString(if (useMetricUnit) R.string.unit_meters else R.string.unit_feet)
        elevationTextView.text = getString(R.string.elevation_text, elevationRounded, unit)
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
    }
}
