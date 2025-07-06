package com.bizzarosn.heightmark

import android.Manifest
import android.content.Context.LOCATION_SERVICE
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresPermission
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ElevationFragment : Fragment() {

    companion object {
        private const val ELEVATION_READINGS_COUNT = 10
    }

    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var elevationTextView: ElevationTextView
    private val elevationService = ElevationService(ELEVATION_READINGS_COUNT)
    private var useMetricUnit = true
    private var locationManager: LocationManager? = null
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
        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        preferencesRepository = PreferencesRepository(requireContext().applicationContext)

        elevationTextView = view.findViewById(R.id.elevation_text_view)
        val unitSwitch = view.findViewById<SwitchCompat>(R.id.unit_switch)

        lifecycleScope.launch {
            useMetricUnit = preferencesRepository.useMetricUnit.first()
            unitSwitch.isChecked = useMetricUnit
            permissionHandler.checkPermission()
        }

        unitSwitch.setOnCheckedChangeListener { _, isChecked ->
            useMetricUnit = isChecked
            lifecycleScope.launch {
                preferencesRepository.setUseMetricUnit(isChecked)
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
                    elevationTextView.text = getString(R.string.location_permission_required)
                }
            }
            is LocationPermissionState.Denied,
            is LocationPermissionState.PermanentlyDenied -> {
                stopLocationUpdates()
                elevationTextView.text = getString(R.string.location_permission_required)
            }
            is LocationPermissionState.RequiresRationale -> {
                stopLocationUpdates()
                elevationTextView.text = getString(R.string.location_permission_required)
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
            elevationTextView.text = getString(R.string.location_permission_required)
            return
        }
        
        if (locationManager == null) {
            locationManager = requireContext().getSystemService(LOCATION_SERVICE) as LocationManager
        }
        
        if (locationListener == null) {
            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val elevation = location.altitude
                    elevationService.addElevationReading(elevation)
                    updateUIWithElevation()
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
        }
        
        elevationTextView.startLoadingAnimation()
        
        locationManager?.let { manager ->
            locationListener?.let { listener ->
                try {
                    if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        manager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, 1000, 1f, listener
                        )
                    } else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        manager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, 1000, 1f, listener
                        )
                    }
                } catch (e: SecurityException) {
                    // Log the unexpected security exception for debugging
                    Log.e("ElevationFragment", "Unexpected SecurityException despite permission check", e)
                    elevationTextView.text = getString(R.string.location_permission_required)
                }
            }
        }
    }
    
    private fun stopLocationUpdates() {
        locationManager?.let { manager ->
            locationListener?.let { listener ->
                manager.removeUpdates(listener)
            }
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

    private fun updateUIWithElevation() {
        val localizedElevation = elevationService.getLocalizedElevation(useMetricUnit)
        elevationTextView.updateElevation(localizedElevation, useMetricUnit)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        locationManager = null
        locationListener = null
    }
}
