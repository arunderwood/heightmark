package com.bizzarosn.heightmark

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
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

    private lateinit var permissionHandler: LocationPermissionHandler

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
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startLocationUpdates() {
        // Double-check permissions before starting location updates
        if (!hasLocationPermission()) {
            showPermissionRequired()
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
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000, 1f, listener
                    )
                }
            } catch (e: SecurityException) {
                // Log the unexpected security exception for debugging
                Log.e("ElevationFragment", "Unexpected SecurityException despite permission check", e)
                showPermissionRequired()
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
        locationListener?.let { listener ->
            locationManager.removeUpdates(listener)
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onResume() {
        super.onResume()
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
}
