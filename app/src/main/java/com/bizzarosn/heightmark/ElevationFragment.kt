package com.bizzarosn.heightmark

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * The app's one screen. It renders whatever [ElevationTracker] publishes and
 * owns only what genuinely needs a Fragment: the views, the two toggles and
 * their persistence, the permission handler's launcher, and the dialogs.
 *
 * Nothing here decides *what* the reading is worth — [ElevationUiState] arrives
 * already derived, so the hero number, the settling line and the
 * location-services prompt cannot drift out of step with each other.
 */
@AndroidEntryPoint
class ElevationFragment : Fragment() {

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    private val tracker: ElevationTracker by viewModels()

    private lateinit var elevationTextView: TextView
    private lateinit var stabilityLine: StabilityLineView
    private lateinit var blockedActionButton: MaterialButton
    private lateinit var detailsToggle: TextView
    private lateinit var detailsPanel: TextView

    private var useMetricUnit = true
    private var showDetails = false
    private var locationOffDialog: AlertDialog? = null

    private lateinit var permissionHandler: LocationPermissionHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionHandler = LocationPermissionHandler(
            fragment = this,
            onPermissionStateChanged = { state -> tracker.onPermissionState(state) },
            hasShownUpgradeDialog = { tracker.upgradeDialogShown },
            onUpgradeDialogShown = { tracker.upgradeDialogShown = true }
        )
        permissionHandler.initialize()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_elevation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        elevationTextView = view.findViewById(R.id.elevation_text_view)
        stabilityLine = view.findViewById(R.id.stability_line)
        blockedActionButton = view.findViewById(R.id.blocked_action_button)
        detailsToggle = view.findViewById(R.id.details_toggle)
        detailsPanel = view.findViewById(R.id.details_panel)
        val unitToggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.unit_toggle_group)

        viewLifecycleOwner.lifecycleScope.launch {
            useMetricUnit = preferencesRepository.useMetricUnit.first()
            unitToggleGroup.check(if (useMetricUnit) R.id.button_meters else R.id.button_feet)
            applyDetailsVisibility(preferencesRepository.showDetails.first())
            permissionHandler.checkPermission()
        }

        // Both toggles repaint first and persist after: a DataStore write goes
        // to disk, and neither the new units nor the panel should wait on it
        unitToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            useMetricUnit = checkedId == R.id.button_meters
            // Only the unit-bearing parts of the current state change
            val state = tracker.uiState.value
            renderHero(state.hero)
            renderDetails(state.details)
            // Persisting on the fragment's scope, not the view's: a rotation
            // right after a tap must not cancel the write
            lifecycleScope.launch { preferencesRepository.setUseMetricUnit(useMetricUnit) }
        }

        detailsToggle.setOnClickListener {
            val show = !showDetails
            applyDetailsVisibility(show)
            lifecycleScope.launch { preferencesRepository.setShowDetails(show) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                tracker.uiState.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tracker.onForeground()
    }

    override fun onPause() {
        super.onPause()
        tracker.onBackground()
        dismissLocationOffDialog()
    }

    private fun applyDetailsVisibility(show: Boolean) {
        showDetails = show
        detailsPanel.isVisible = show
        detailsToggle.text = getString(if (show) R.string.details_hide else R.string.details_show)
        detailsToggle.stateDescription = getString(
            if (show) R.string.details_state_expanded else R.string.details_state_collapsed
        )
        tracker.setDetailsVisible(show)
    }

    private fun render(state: ElevationUiState) {
        renderHero(state.hero)
        renderStabilityLine(state.readingState)
        renderDetails(state.details)
        renderLocationOffPrompt(state.promptLocationSettings)
        renderBlockedAction(state.blockedAction)
    }

    /**
     * The only writer of the hero TextView. Both modes set the same properties
     * so switching modes can never leave a stale one behind.
     */
    private fun renderHero(hero: ElevationUiState.Hero) {
        when (hero) {
            is ElevationUiState.Hero.Status -> {
                // Status messages use headline type; the hero display size is
                // reserved for the value
                applyTextAppearance(com.google.android.material.R.attr.textAppearanceHeadlineSmall)
                // Status sentences run long ("Still searching…") and must not clip
                elevationTextView.maxLines = 3
                // Errors and search-status changes are the moments a screen-reader
                // user must hear unprompted; the ticking elevation number is not
                elevationTextView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                elevationTextView.contentDescription = null
                elevationTextView.text = getString(hero.messageRes)
            }
            is ElevationUiState.Hero.Value -> {
                applyTextAppearance(
                    com.google.android.material.R.attr.textAppearanceDisplayLargeEmphasized
                )
                elevationTextView.maxLines = 2
                // The value refreshes ~every second while converging; a live region
                // would make TalkBack announce every tick. Screen-reader users read
                // the value on focus instead, with the unit spoken in full.
                elevationTextView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                val formatted = LengthFormatter.hero(hero.meters, useMetricUnit)
                elevationTextView.text = getString(
                    R.string.elevation_text, formatted.value, getString(formatted.unitRes)
                )
                elevationTextView.contentDescription = getString(
                    R.string.elevation_a11y, formatted.value, getString(formatted.spokenUnitRes)
                )
            }
        }
    }

    /**
     * Pushes the derived [ReadingState] to the settling line and the hero
     * number's opacity. A null state means the screen is explaining an error,
     * where a kinetic signal widget alongside would contradict it.
     */
    private fun renderStabilityLine(state: ReadingState?) {
        if (state == null) {
            stabilityLine.isVisible = false
            elevationTextView.animate().cancel()
            elevationTextView.alpha = 1f
            return
        }
        stabilityLine.isVisible = true
        stabilityLine.setState(state)
        elevationTextView.animate()
            .alpha(ReadingState.heroAlpha(state))
            .setDuration(HERO_FADE_MS)
            .start()
    }

    private fun renderDetails(facts: ElevationUiState.DetailsFacts?) {
        if (facts == null) return
        val rows = DetailsPanelPresenter.rows(
            DetailsPanelPresenter.Input(
                isIdle = facts.isIdle,
                isBlocked = facts.isBlocked,
                location = facts.location,
                nowElapsedRealtimeNanos = facts.nowElapsedRealtimeNanos,
                satellitesUsed = facts.satellitesUsed,
                satellitesVisible = facts.satellitesVisible,
                pressureHpa = facts.pressureHpa,
                readingCount = facts.readingCount,
                useMetric = useMetricUnit,
                locale = Locale.getDefault()
            )
        )
        detailsPanel.text = rows.joinToString("\n") { resolve(it) }
    }

    /** Resolves a presenter row, flattening the one nested length resource. */
    private fun resolve(row: DetailsPanelPresenter.Row): String {
        // Argument-less rows skip the formatting overload, which would choke on
        // a translation containing a literal percent sign
        if (row.args.isEmpty()) return getString(row.templateRes)
        val args = row.args.map { arg ->
            if (arg is LengthFormatter.Detail) getString(arg.templateRes, arg.valueText) else arg
        }
        return getString(row.templateRes, *args.toTypedArray())
    }

    private fun renderLocationOffPrompt(show: Boolean) {
        if (!show) {
            dismissLocationOffDialog()
            return
        }
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

    private fun dismissLocationOffDialog() {
        locationOffDialog?.dismiss()
        locationOffDialog = null
    }

    /**
     * The blocked screen's persistent recovery path: unlike the dialogs,
     * which are dismissible and shown at most once per interruption, this
     * button stays available for the rest of the session so dismissing a
     * dialog never strands the user without a way to retry.
     */
    private fun renderBlockedAction(action: ElevationUiState.BlockedAction?) {
        if (action == null) {
            blockedActionButton.isVisible = false
            return
        }
        blockedActionButton.isVisible = true
        blockedActionButton.setText(action.labelRes)
        blockedActionButton.setOnClickListener { performBlockedAction(action) }
    }

    private fun performBlockedAction(action: ElevationUiState.BlockedAction) {
        when (action) {
            ElevationUiState.BlockedAction.RequestPermission -> permissionHandler.requestPermissions()
            ElevationUiState.BlockedAction.OpenAppSettings -> permissionHandler.openAppSettings()
            ElevationUiState.BlockedAction.OpenLocationSettings ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    private fun applyTextAppearance(textAppearanceAttr: Int) {
        val resolved = TypedValue()
        requireContext().theme.resolveAttribute(textAppearanceAttr, resolved, true)
        elevationTextView.setTextAppearance(resolved.resourceId)
        elevationTextView.setTextColor(requireContext().getColor(R.color.hm_on_scrim))
    }

    companion object {
        private const val HERO_FADE_MS = 250L
    }
}
