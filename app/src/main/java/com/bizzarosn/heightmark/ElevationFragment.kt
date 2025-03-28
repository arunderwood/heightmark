package com.bizzarosn.heightmark

import android.Manifest
import android.content.Context.LOCATION_SERVICE
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresPermission
import androidx.appcompat.widget.SwitchCompat
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

    private lateinit var permissionHandler: LocationPermissionHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionHandler = LocationPermissionHandler(this) {
            elevationTextView.startLoadingAnimation()
            getCurrentElevation()
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
                permissionHandler.checkPermission()
            }
        }

        return view
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun getCurrentElevation() {
        val locationManager = requireContext().getSystemService(LOCATION_SERVICE) as LocationManager
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val elevation = location.altitude // Elevation in meters
                elevationService.addElevationReading(elevation)
                updateUIWithElevation()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 0, 0f, locationListener
        )
    }

    private fun updateUIWithElevation() {
        val localizedElevation = elevationService.getLocalizedElevation(useMetricUnit)
        elevationTextView.updateElevation(localizedElevation, useMetricUnit)
    }
}
