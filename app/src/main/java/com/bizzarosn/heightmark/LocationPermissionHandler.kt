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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

sealed class LocationPermissionState {
    object Granted : LocationPermissionState()

    /** Only approximate location granted (Android 12+ downgrade); GPS needs precise. */
    object CoarseOnly : LocationPermissionState()
    object Denied : LocationPermissionState()
    object PermanentlyDenied : LocationPermissionState()
    object RequiresRationale : LocationPermissionState()
}

class LocationPermissionHandler(
    private val fragment: Fragment,
    private val onPermissionStateChanged: (LocationPermissionState) -> Unit
) : DefaultLifecycleObserver {
    
    private var locationPermissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var currentDialog: AlertDialog? = null
    private var upgradeDialogShown = false

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    
    fun initialize() {
        fragment.lifecycle.addObserver(this)
        locationPermissionLauncher = fragment.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            handlePermissionResult(permissions)
        }
    }
    
    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        currentDialog?.dismiss()
        currentDialog = null
    }
    
    fun checkPermission() {
        when {
            hasFinePermission() -> {
                onPermissionStateChanged(LocationPermissionState.Granted)
            }

            hasLocationPermission() -> {
                // Approximate-only grant: GPS (and therefore elevation) needs precise
                onPermissionStateChanged(LocationPermissionState.CoarseOnly)
                showPreciseUpgradeDialog()
            }

            shouldShowRationale() -> {
                onPermissionStateChanged(LocationPermissionState.RequiresRationale)
                showPermissionRequiredDialog()
            }

            else -> {
                requestPermissions()
            }
        }
    }

    fun hasLocationPermission(): Boolean {
        return permissions.any { permission ->
            ContextCompat.checkSelfPermission(
                fragment.requireContext(), permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasFinePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            fragment.requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun shouldShowRationale(): Boolean {
        return permissions.any { permission ->
            fragment.shouldShowRequestPermissionRationale(permission)
        }
    }
    
    private fun requestPermissions() {
        locationPermissionLauncher?.launch(permissions)
    }
    
    private fun handlePermissionResult(permissions: Map<String, Boolean>) {
        when {
            permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true -> {
                onPermissionStateChanged(LocationPermissionState.Granted)
            }

            permissions.values.any { it } -> {
                onPermissionStateChanged(LocationPermissionState.CoarseOnly)
                showPreciseUpgradeDialog()
            }

            shouldShowRationale() -> {
                onPermissionStateChanged(LocationPermissionState.RequiresRationale)
                showPermissionRequiredDialog()
            }

            else -> {
                onPermissionStateChanged(LocationPermissionState.PermanentlyDenied)
                showPermanentDenialDialog()
            }
        }
    }

    private fun showPreciseUpgradeDialog() {
        // Ask once per session; re-requesting in a loop would just re-show the
        // system dialog every time the fragment resumes
        if (upgradeDialogShown) return
        if (currentDialog?.isShowing == true) return
        upgradeDialogShown = true

        currentDialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.precise_location_required))
            .setMessage(fragment.getString(R.string.precise_location_required_message))
            .setPositiveButton(fragment.getString(R.string.grant_permission)) { _, _ ->
                requestPermissions()
            }
            .setNegativeButton(fragment.getString(R.string.open_settings)) { _, _ ->
                openAppSettings()
            }
            .setCancelable(false)
            .create()

        currentDialog?.show()
    }
    
    private fun showPermissionRequiredDialog() {
        if (currentDialog?.isShowing == true) return
        
        currentDialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.location_permission_required))
            .setMessage(fragment.getString(R.string.this_app_needs_location_permission_to_determine_your_elevation_without_this_permission_the_app_cannot_function))
            .setPositiveButton(fragment.getString(R.string.grant_permission)) { _, _ ->
                requestPermissions()
            }
            .setNegativeButton(fragment.getString(R.string.exit_app)) { _, _ ->
                fragment.requireActivity().finish()
            }
            .setCancelable(false)
            .create()
        
        currentDialog?.show()
    }
    
    private fun showPermanentDenialDialog() {
        if (currentDialog?.isShowing == true) return
        
        currentDialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.location_permission_required))
            .setMessage(fragment.getString(R.string.permission_permanently_denied_message))
            .setPositiveButton(fragment.getString(R.string.open_settings)) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(fragment.getString(R.string.exit_app)) { _, _ ->
                fragment.requireActivity().finish()
            }
            .setCancelable(false)
            .create()
        
        currentDialog?.show()
    }
    
    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", fragment.requireActivity().packageName, null)
        )
        fragment.startActivity(intent)
    }
}