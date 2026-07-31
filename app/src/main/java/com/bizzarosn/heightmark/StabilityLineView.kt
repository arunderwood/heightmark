package com.bizzarosn.heightmark

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.annotation.StringRes
import androidx.core.graphics.withSave
import android.view.animation.AnimationUtils
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The settling line: a single kinetic line under the elevation number that
 * shows at a glance how much the reading can be trusted.
 *
 * - Acquiring: a sine wave travels along the full width — searching.
 * - Converging: a bright flat core grows outward from the center while the
 *   wave dies down in the unfilled edges — the line literally flattens as
 *   the average settles.
 * - Stable: a crisp full-width line whose glow breathes slowly.
 * - Dormant: a motionless dotted line — stillness itself signals that the
 *   GPS is off and the number above is frozen.
 *
 * All state changes ease toward their targets inside the frame loop, so
 * every transition is a smooth morph without per-transition animators. With
 * animator scale off (CI emulators, reduced motion) the view snaps straight
 * to each state's static frame and never starts the infinite animator.
 */
class StabilityLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val strokeWidth = 3f * density
    private val maxAmplitude = 6f * density
    private val wavelength = 66f * density

    private val linePaint = strokePaint(strokeWidth)

    // Fake glow: a wide translucent under-stroke, safe on a hardware canvas
    private val glowPaint = strokePaint(9f * density)

    private val wavePaint = strokePaint(strokeWidth)

    private val dormantPaint = strokePaint(
        strokeWidth,
        DashPathEffect(floatArrayOf(3f * density, 6f * density), 0f)
    )

    private val wavePath = Path()

    // Eased display parameters and their per-state targets
    private var amplitude = 1f
    private var core = 0f
    private var dormantMix = 0f
    private var targetAmplitude = 1f
    private var targetCore = 0f
    private var targetDormantMix = 0f

    private var phase = 0f
    private var state: ReadingState = ReadingState.Acquiring
    private var animator: ValueAnimator? = null
    private var slowFrameScheduled = false
    // Tracks onVisibilityAggregated rather than re-deriving it, since that's
    // the one hook that already folds in GONE, ancestor visibility, and
    // window visibility (screen off / backgrounded) in a single callback
    private var isVisibleAggregated = true

    init {
        // The initial state never passes through setState's change guard
        stateDescription = context.getString(presentationFor(state).spokenRes)
    }

    fun setState(newState: ReadingState) {
        if (newState == state) return
        state = newState
        val presentation = presentationFor(newState)
        targetAmplitude = presentation.amplitude
        targetCore = presentation.core
        targetDormantMix = presentation.dormantMix
        // The static contentDescription comes from the layout; the state
        // rides in stateDescription, whose change event the polite live
        // region turns into an automatic TalkBack announcement. Converging
        // arrives once per progress step, so guard against re-announcing
        // the same phrase.
        val spokenState = context.getString(presentation.spokenRes)
        if (stateDescription != spokenState) {
            stateDescription = spokenState
        }
        if (!ValueAnimator.areAnimatorsEnabled()) {
            snapToTargets()
        }
        invalidate()
        updateFramePump()
    }

    /** Everything the view derives from a [ReadingState], in one place. */
    private data class StatePresentation(
        val amplitude: Float,
        val core: Float,
        val dormantMix: Float,
        @param:StringRes val spokenRes: Int
    )

    private fun presentationFor(state: ReadingState): StatePresentation = when (state) {
        ReadingState.Acquiring -> StatePresentation(
            amplitude = 1f, core = 0f, dormantMix = 0f,
            spokenRes = R.string.stability_acquiring
        )
        is ReadingState.Converging -> {
            val p = state.progress.coerceIn(0f, 1f)
            StatePresentation(
                amplitude = 1f - p, core = p, dormantMix = 0f,
                spokenRes = R.string.stability_converging
            )
        }
        ReadingState.Stable -> StatePresentation(
            amplitude = 0f, core = 1f, dormantMix = 0f,
            spokenRes = R.string.stability_stable
        )
        ReadingState.Dormant -> StatePresentation(
            amplitude = 0f, core = 0f, dormantMix = 1f,
            spokenRes = R.string.stability_dormant
        )
    }

    private fun snapToTargets() {
        amplitude = targetAmplitude
        core = targetCore
        dormantMix = targetDormantMix
    }

    private fun startAnimator() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = WAVE_PERIOD_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    private val slowFrameTick = Runnable {
        slowFrameScheduled = false
        invalidate()
    }

    /** ~20 fps self-scheduled invalidation — ample for the 4 s breathing glow. */
    private fun scheduleSlowFrame() {
        if (slowFrameScheduled) return
        slowFrameScheduled = true
        postDelayed(slowFrameTick, SLOW_FRAME_INTERVAL_MS)
    }

    private fun stopSlowFrames() {
        if (!slowFrameScheduled) return
        slowFrameScheduled = false
        removeCallbacks(slowFrameTick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateFramePump()
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        stopSlowFrames()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        isVisibleAggregated = isVisible
        updateFramePump()
    }

    /** Fully dormant and done morphing: the one state that needs no frames. */
    private fun isAtRest(): Boolean = state == ReadingState.Dormant && !isEasing()

    /** The eased display params haven't reached this state's targets yet. */
    private fun isEasing(): Boolean =
        abs(amplitude - targetAmplitude) >= EPSILON ||
            abs(core - targetCore) >= EPSILON ||
            abs(dormantMix - targetDormantMix) >= EPSILON

    /**
     * Acquiring's traveling wave and Converging's growing core need a smooth
     * 60-120 Hz morph; so does any in-flight ease toward a new target,
     * regardless of which state it's easing into. Everything else — chiefly
     * Stable's slow breathing glow, which reads its own alpha off the system
     * clock rather than off [phase] — only needs occasional redraws.
     */
    private fun needsFullRate(): Boolean =
        state == ReadingState.Acquiring || state is ReadingState.Converging || isEasing()

    /** The single place deciding which frame pump (if any) should be running. */
    private fun updateFramePump() {
        if (!isAttachedToWindow || !isVisibleAggregated || !ValueAnimator.areAnimatorsEnabled()) {
            stopAnimator()
            stopSlowFrames()
            return
        }
        when {
            isAtRest() -> {
                stopAnimator()
                stopSlowFrames()
            }
            needsFullRate() -> {
                stopSlowFrames()
                startAnimator()
            }
            else -> {
                stopAnimator()
                scheduleSlowFrame()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        amplitude += (targetAmplitude - amplitude) * EASE
        core += (targetCore - core) * EASE
        dormantMix += (targetDormantMix - dormantMix) * EASE

        val width = width.toFloat()
        val centerY = height / 2f
        val inset = glowPaint.strokeWidth / 2f
        val active = 1f - dormantMix

        if (dormantMix > EPSILON) {
            dormantPaint.setAlphaFraction(DORMANT_ALPHA * dormantMix)
            canvas.drawLine(inset, centerY, width - inset, centerY, dormantPaint)
        }

        if (active > EPSILON) {
            val centerX = width / 2f
            val coreHalf = core * (centerX - inset)

            if (amplitude > EPSILON && core < 1f - EPSILON) {
                buildWavePath(inset, width - inset, centerY)
                wavePaint.setAlphaFraction(WAVE_ALPHA * active)
                if (coreHalf > 0f) {
                    canvas.withSave {
                        clipOutRect(
                            centerX - coreHalf, 0f, centerX + coreHalf, height.toFloat()
                        )
                        drawPath(wavePath, wavePaint)
                    }
                } else {
                    canvas.drawPath(wavePath, wavePaint)
                }
            }

            if (coreHalf > strokeWidth / 2f) {
                fun drawCore(paint: Paint) =
                    canvas.drawLine(centerX - coreHalf, centerY, centerX + coreHalf, centerY, paint)

                val breath = 0.875f + 0.125f *
                    sin(2f * PI.toFloat() * (AnimationUtils.currentAnimationTimeMillis() % BREATH_PERIOD_MS) / BREATH_PERIOD_MS)
                glowPaint.setAlphaFraction(GLOW_ALPHA * breath * active)
                drawCore(glowPaint)
                linePaint.setAlphaFraction(CORE_ALPHA * active)
                drawCore(linePaint)
            }
        }

        if (isAtRest()) {
            snapToTargets()
        }
        updateFramePump()
    }

    private fun strokePaint(widthPx: Float, dash: DashPathEffect? = null) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = widthPx
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
            pathEffect = dash
        }

    private fun Paint.setAlphaFraction(fraction: Float) {
        alpha = (255 * fraction).toInt()
    }

    private fun buildWavePath(startX: Float, endX: Float, centerY: Float) {
        wavePath.rewind()
        val span = endX - startX
        val amplitudePx = maxAmplitude * amplitude
        val step = 3f * density
        var x = startX
        wavePath.moveTo(x, waveY(x, startX, span, amplitudePx, centerY))
        while (x < endX) {
            x = (x + step).coerceAtMost(endX)
            wavePath.lineTo(x, waveY(x, startX, span, amplitudePx, centerY))
        }
    }

    // Envelope pins both ends so the line reads as a plucked string leveling out
    private fun waveY(x: Float, startX: Float, span: Float, amplitudePx: Float, centerY: Float): Float {
        val t = (x - startX) / span
        val envelope = sin(PI.toFloat() * t)
        return centerY + amplitudePx * envelope * sin(2f * PI.toFloat() * (x / wavelength - phase))
    }

    companion object {
        private const val EASE = 0.15f
        private const val EPSILON = 0.005f
        private const val WAVE_PERIOD_MS = 1_400L
        private const val BREATH_PERIOD_MS = 4_000L
        private const val SLOW_FRAME_INTERVAL_MS = 50L
        // The wave and dormant strokes carry meaning, so they must clear the
        // 3:1 non-text contrast minimum over the day-mode scrim floor;
        // ScrimContrastTest references these constants directly. The glow is
        // a decorative under-stroke beneath the near-opaque core line and is
        // exempt.
        internal const val WAVE_ALPHA = 0.65f
        internal const val CORE_ALPHA = 0.95f
        private const val GLOW_ALPHA = 0.22f
        internal const val DORMANT_ALPHA = 0.6f
    }
}
