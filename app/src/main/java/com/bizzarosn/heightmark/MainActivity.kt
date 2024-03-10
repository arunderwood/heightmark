package com.bizzarosn.heightmark

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // Inside MainActivity class
    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val ELEVATION_READINGS_COUNT = 10
    }

    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var elevationTextView: ElevationTextView
    private val elevationService = ElevationService(ELEVATION_READINGS_COUNT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferencesRepository = PreferencesRepository(this)

        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        elevationTextView = findViewById(R.id.elevation_text_view)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
        } else {
            elevationTextView.startLoadingAnimation()
            getCurrentElevation()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getCurrentElevation()
        }
    }

    private fun getCurrentElevation() {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val elevation = location.altitude // Elevation in meters
                val averageElevation = elevationService.addElevationReading(elevation)
                updateUIWithElevation(averageElevation)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, locationListener)
        }
    }

    private fun updateUIWithElevation(elevation: Double) {
        lifecycleScope.launch {
            val useMetric = preferencesRepository.useMetricUnit.first()
            elevationTextView.updateElevation(elevation, useMetric)
        }
    }


}