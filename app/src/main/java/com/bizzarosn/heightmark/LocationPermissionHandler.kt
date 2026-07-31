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

    /**
     * A true first launch: this install has never triggered the system
     * permission dialog. Renders the same blocked screen as
     * [RequiresRationale] — its message is the up-front rationale Android's
     * guidance calls for — but the caller must not pair it with either
     * dialog, since firing one here is exactly the no-context prompt this
     * state exists to avoid.
     */
    object NotYetRequested : LocationPermissionState()
}

class LocationPermissionHandler(
    private val fragment: Fragment,
    private val onPermissionStateChanged: (LocationPermissionState) -> Unit,
    /** Whether the upgrade dialog already interrupted this session — the
     *  caller backs this with session-scoped state, since this handler is
     *  rebuilt on every fragment recreation and cannot hold it itself. */
    private val hasShownUpgradeDialog: () -> Boolean,
    private val onUpgradeDialogShown: () -> Unit,
    /** Whether the system dialog has ever been triggered for this install —
     *  the seam [checkPermission] uses to tell a true first launch apart
     *  from a returning, already-decided user. The caller backs this with
     *  persisted state (survives process death, unlike [hasShownUpgradeDialog]),
     *  since a killed-and-relaunched process must not re-ask. */
    private val hasRequestedPermissionBefore: () -> Boolean,
    private val onPermissionRequested: () -> Unit
) : DefaultLifecycleObserver {

    private var locationPermissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var currentDialog: AlertDialog? = null

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
                isRequestResult = false,
                hasRequestedBefore = hasRequestedPermissionBefore()
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
    
    /** Launches the system permission request. Also the blocked screen's
     *  persistent-action entry point for [ElevationUiState.BlockedAction.RequestPermission]. */
    fun requestPermissions() {
        // The single choke point every request path funnels through — the
        // auto-fire fallback, each dialog's positive button, and the blocked
        // screen's own button — so "requested at least once" is recorded
        // regardless of which of them the user reached it from.
        onPermissionRequested()
        locationPermissionLauncher?.launch(permissions)
    }

    private fun handlePermissionResult(permissions: Map<String, Boolean>) {
        applyResolution(
            LocationPermissionPolicy.resolve(
                fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true,
                anyGranted = permissions.values.any { it },
                shouldShowRationale = shouldShowRationale(),
                isRequestResult = true,
                // A result callback only fires after a request, so this is
                // trivially true — read directly rather than through the
                // persisted flag, which may still be mid-write.
                hasRequestedBefore = true
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
        // system dialog every time the fragment resumes. The blocked screen's
        // persistent action button is the fallback once this has fired.
        if (hasShownUpgradeDialog()) return
        if (currentDialog?.isShowing == true) return
        onUpgradeDialogShown()

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
            negativeRes = R.string.not_now,
            onNegative = {}
        )
    }

    private fun showPermanentDenialDialog() {
        if (currentDialog?.isShowing == true) return

        showDialog(
            titleRes = R.string.location_permission_required,
            messageRes = R.string.permission_permanently_denied_message,
            positiveRes = R.string.open_settings,
            onPositive = ::openAppSettings,
            negativeRes = R.string.not_now,
            onNegative = {}
        )
    }

    /**
     * Builds and shows the single tracked dialog; callers gate on
     * [currentDialog]. Dismissible rather than forced — refusing (back,
     * outside tap, or the negative button) lands the user back on the
     * blocked screen, which now explains the block and offers the same
     * recovery action as a persistent button.
     */
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
            .setCancelable(true)
            .create()

        currentDialog?.show()
    }

    /** Also the blocked screen's persistent-action entry point for
     *  [ElevationUiState.BlockedAction.OpenAppSettings]. */
    fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", fragment.requireActivity().packageName, null)
        )
        fragment.startActivity(intent)
    }
}