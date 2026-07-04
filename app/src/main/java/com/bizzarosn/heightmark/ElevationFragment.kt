package com.bizzarosn.heightmark

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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

    private lateinit var elevationTextView: TextView
    private lateinit var loadingIndicator: LoadingIndicator
    private var useMetricUnit = true
    private var hasFix = false
    private var locationListener: LocationListener? = null
    private var searchTimeoutJob: Job? = null
    private var locationOffDialog: AlertDialog? = null

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
        val unitToggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.unit_toggle_group)

        lifecycleScope.launch {
            useMetricUnit = preferencesRepository.useMetricUnit.first()
            unitToggleGroup.check(if (useMetricUnit) R.id.button_meters else R.id.button_feet)
            permissionHandler.checkPermission()
        }

        unitToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            useMetricUnit = checkedId == R.id.button_meters
            lifecycleScope.launch {
                preferencesRepository.setUseMetricUnit(useMetricUnit)
                updateUIWithElevation()
            }
        }

        return view
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
            @Suppress("DEPRECATION", "DEPRECATION_ERROR")
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onGnssFix(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                @Deprecated("Deprecated in Java")
                override fun onProviderEnabled(provider: String) {}

                @Deprecated("Deprecated in Java")
                override fun onProviderDisabled(provider: String) {}
            }
        }

        if (!hasFix) {
            setLoading(true)
        }

        locationListener?.let { listener ->
            try {
                // Only GNSS fixes carry altitude, so the network provider is useless here
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000, 1f, listener
                )
                startSearchTimeout()
            } catch (e: SecurityException) {
                // Log the unexpected security exception for debugging
                Log.e("ElevationFragment", "Unexpected SecurityException despite permission check", e)
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
        // A fix without altitude would read as 0.0 and poison the average
        if (!location.hasAltitude()) return
        viewLifecycleOwner.lifecycleScope.launch {
            // Geoid data loads from disk on first use in a region
            val elevation = withContext(Dispatchers.IO) {
                altitudeResolver.mslAltitudeMeters(location)
            }
            elevationService.addElevationReading(elevation)
            hasFix = true
            updateUIWithElevation()
        }
    }

    private fun stopLocationUpdates() {
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
        if (hasLocationPermission()) {
            startLocationUpdates()
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
        private const val SEARCH_TIMEOUT_MS = 30_000L
    }
}
