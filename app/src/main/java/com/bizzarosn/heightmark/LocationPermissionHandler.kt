package com.bizzarosn.heightmark

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * True when ACCESS_FINE_LOCATION is granted. GPS needs precise location, so
 * this is the gate every location caller checks; [LocationPermissionHandler]
 * and [ElevationTracker] share it rather than each rolling their own.
 */
fun Context.hasFineLocationPermission(): Boolean {
    return ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

sealed class LocationPermissionState {
    object Granted : LocationPermissionState()

    /** Only approximate location granted (Android 12+ downgrade); GPS needs precise. */
    object CoarseOnly : LocationPermissionState()
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
        applyResolution(
            LocationPermissionPolicy.resolve(
                fineGranted = hasFinePermission(),
                anyGranted = hasLocationPermission(),
                shouldShowRationale = shouldShowRationale(),
                isRequestResult = false
            )
        )
    }

    fun hasLocationPermission(): Boolean {
        return permissions.any { permission ->
            ContextCompat.checkSelfPermission(
                fragment.requireContext(), permission
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasFinePermission(): Boolean {
        return fragment.requireContext().hasFineLocationPermission()
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
        applyResolution(
            LocationPermissionPolicy.resolve(
                fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true,
                anyGranted = permissions.values.any { it },
                shouldShowRationale = shouldShowRationale(),
                isRequestResult = true
            )
        )
    }

    private fun applyResolution(resolution: LocationPermissionPolicy.Resolution) {
        when (resolution) {
            is LocationPermissionPolicy.Resolution.RequestPermissions -> requestPermissions()
            is LocationPermissionPolicy.Resolution.Report -> {
                onPermissionStateChanged(resolution.state)
                when (resolution.dialog) {
                    LocationPermissionPolicy.Dialog.PreciseUpgrade -> showPreciseUpgradeDialog()
                    LocationPermissionPolicy.Dialog.PermissionRequired ->
                        showPermissionRequiredDialog()
                    LocationPermissionPolicy.Dialog.PermanentDenial -> showPermanentDenialDialog()
                    null -> Unit
                }
            }
        }
    }

    private fun showPreciseUpgradeDialog() {
        // Ask once per session; re-requesting in a loop would just re-show the
        // system dialog every time the fragment resumes
        if (upgradeDialogShown) return
        if (currentDialog?.isShowing == true) return
        upgradeDialogShown = true

        showDialog(
            titleRes = R.string.precise_location_required,
            messageRes = R.string.precise_location_required_message,
            positiveRes = R.string.grant_permission,
            onPositive = ::requestPermissions,
            negativeRes = R.string.open_settings,
            onNegative = ::openAppSettings
        )
    }

    private fun showPermissionRequiredDialog() {
        if (currentDialog?.isShowing == true) return

        showDialog(
            titleRes = R.string.location_permission_required,
            messageRes = R.string.location_permission_rationale_message,
            positiveRes = R.string.grant_permission,
            onPositive = ::requestPermissions,
            negativeRes = R.string.exit_app,
            onNegative = { fragment.requireActivity().finish() }
        )
    }

    private fun showPermanentDenialDialog() {
        if (currentDialog?.isShowing == true) return

        showDialog(
            titleRes = R.string.location_permission_required,
            messageRes = R.string.permission_permanently_denied_message,
            positiveRes = R.string.open_settings,
            onPositive = ::openAppSettings,
            negativeRes = R.string.exit_app,
            onNegative = { fragment.requireActivity().finish() }
        )
    }

    /** Builds and shows the single tracked dialog; callers gate on [currentDialog]. */
    private fun showDialog(
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        @StringRes positiveRes: Int,
        onPositive: () -> Unit,
        @StringRes negativeRes: Int,
        onNegative: () -> Unit
    ) {
        currentDialog = AlertDialog.Builder(fragment.requireContext())
            .setTitle(fragment.getString(titleRes))
            .setMessage(fragment.getString(messageRes))
            .setPositiveButton(fragment.getString(positiveRes)) { _, _ -> onPositive() }
            .setNegativeButton(fragment.getString(negativeRes)) { _, _ -> onNegative() }
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