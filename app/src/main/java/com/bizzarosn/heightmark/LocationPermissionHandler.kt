package com.bizzarosn.heightmark

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class LocationPermissionHandler(
    private val fragment: Fragment, private val onPermissionGranted: () -> Unit
) {
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>

    fun initialize() {
        locationPermissionLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                onPermissionGranted()
            } else {
                handlePermissionDenied()
            }
        }
    }

    fun checkPermission() {
        when {
            ContextCompat.checkSelfPermission(
                fragment.requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                onPermissionGranted()
            }

            fragment.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                showPermissionRequiredDialog()
            }

            else -> {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun handlePermissionDenied() {
        if (fragment.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            showPermissionRequiredDialog()
        } else {
            showPermanentDenialDialog()
        }
    }

    private fun showPermissionRequiredDialog() {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.location_permission_required))
            .setMessage(fragment.getString(R.string.this_app_needs_location_permission_to_determine_your_elevation_without_this_permission_the_app_cannot_function))
            .setPositiveButton(fragment.getString(R.string.grant_permission)) { _, _ ->
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }.setNegativeButton(fragment.getString(R.string.exit_app)) { _, _ ->
                fragment.requireActivity().finish()
            }.setCancelable(false).create().show()
    }

    private fun showPermanentDenialDialog() {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.location_permission_required))
            .setMessage(fragment.getString(R.string.permission_permanently_denied_message))
            .setPositiveButton(fragment.getString(R.string.open_settings)) { _, _ ->
                openAppSettings()
            }.setNegativeButton(fragment.getString(R.string.exit_app)) { _, _ ->
                fragment.requireActivity().finish()
            }.setCancelable(false).create().show()
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", fragment.requireActivity().packageName, null)
        )
        fragment.startActivity(intent)
    }
}