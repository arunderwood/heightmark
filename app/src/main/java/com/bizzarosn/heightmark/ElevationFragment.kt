package com.bizzarosn.heightmark

import android.Manifest
import android.content.Context.LOCATION_SERVICE
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ElevationFragment : Fragment() {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val ELEVATION_READINGS_COUNT = 10
    }

    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var elevationTextView: ElevationTextView
    private val elevationService = ElevationService(ELEVATION_READINGS_COUNT)
    private var useMetricUnit = true
    private var lastKnownElevation: Double = 0.0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
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
            if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
            } else {
                elevationTextView.startLoadingAnimation()
                getCurrentElevation()
            }
        }

        unitSwitch.setOnCheckedChangeListener { _, isChecked ->
            useMetricUnit = isChecked
            lifecycleScope.launch {
                preferencesRepository.setUseMetricUnit(isChecked)
                updateUIWithElevation(lastKnownElevation)
            }
        }

        return view
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getCurrentElevation()
        }
    }

    private fun getCurrentElevation() {
        val locationManager = requireContext().getSystemService(LOCATION_SERVICE) as LocationManager
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val elevation = location.altitude // Elevation in meters
                val averageElevation = elevationService.addElevationReading(elevation)
                lastKnownElevation = averageElevation
                updateUIWithElevation(averageElevation)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, locationListener)
        }
    }

    private fun updateUIWithElevation(elevation: Double) {
        val displayElevation = elevationService.getConvertedElevation(elevation, useMetricUnit)
        elevationTextView.updateElevation(displayElevation, useMetricUnit)
    }
}
