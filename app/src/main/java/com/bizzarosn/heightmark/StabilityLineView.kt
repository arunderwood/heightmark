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

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@StabilityLineView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    // Fake glow: a wide translucent under-stroke, safe on a hardware canvas
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 9f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@StabilityLineView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val dormantPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@StabilityLineView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 6f * density), 0f)
    }

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

    fun setState(newState: ReadingState) {
        if (newState == state && contentDescription != null) return
        state = newState
        when (newState) {
            ReadingState.Acquiring -> setTargets(amplitude = 1f, core = 0f, dormantMix = 0f)
            is ReadingState.Converging -> {
                val p = newState.progress.coerceIn(0f, 1f)
                setTargets(amplitude = 1f - p, core = p, dormantMix = 0f)
            }
            ReadingState.Stable -> setTargets(amplitude = 0f, core = 1f, dormantMix = 0f)
            ReadingState.Dormant -> setTargets(amplitude = 0f, core = 0f, dormantMix = 1f)
        }
        contentDescription = context.getString(
            when (newState) {
                ReadingState.Acquiring -> R.string.stability_acquiring
                is ReadingState.Converging -> R.string.stability_converging
                ReadingState.Stable -> R.string.stability_stable
                ReadingState.Dormant -> R.string.stability_dormant
            }
        )
        if (!ValueAnimator.areAnimatorsEnabled()) {
            snapToTargets()
        } else if (isAttachedToWindow) {
            startAnimator()
        }
        invalidate()
    }

    private fun setTargets(amplitude: Float, core: Float, dormantMix: Float) {
        targetAmplitude = amplitude
        targetCore = core
        targetDormantMix = dormantMix
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ValueAnimator.areAnimatorsEnabled() && !isAtRest()) {
            startAnimator()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimator()
        super.onDetachedFromWindow()
    }

    /** Fully dormant and done morphing: the one state that needs no frames. */
    private fun isAtRest(): Boolean =
        state == ReadingState.Dormant &&
            abs(amplitude - targetAmplitude) < EPSILON &&
            abs(core - targetCore) < EPSILON &&
            abs(dormantMix - targetDormantMix) < EPSILON

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
            dormantPaint.alpha = (255 * DORMANT_ALPHA * dormantMix).toInt()
            canvas.drawLine(inset, centerY, width - inset, centerY, dormantPaint)
        }

        if (active > EPSILON) {
            val centerX = width / 2f
            val coreHalf = core * (centerX - inset)

            if (amplitude > EPSILON && core < 1f - EPSILON) {
                buildWavePath(inset, width - inset, centerY)
                wavePaint.alpha = (255 * WAVE_ALPHA * active).toInt()
                if (coreHalf > 0f) {
                    canvas.save()
                    canvas.clipOutRect(
                        centerX - coreHalf, 0f, centerX + coreHalf, height.toFloat()
                    )
                    canvas.drawPath(wavePath, wavePaint)
                    canvas.restore()
                } else {
                    canvas.drawPath(wavePath, wavePaint)
                }
            }

            if (coreHalf > strokeWidth / 2f) {
                val breath = 0.875f + 0.125f *
                    sin(2f * PI.toFloat() * (AnimationUtils.currentAnimationTimeMillis() % BREATH_PERIOD_MS) / BREATH_PERIOD_MS)
                glowPaint.alpha = (255 * GLOW_ALPHA * breath * active).toInt()
                canvas.drawLine(centerX - coreHalf, centerY, centerX + coreHalf, centerY, glowPaint)
                linePaint.alpha = (255 * CORE_ALPHA * active).toInt()
                canvas.drawLine(centerX - coreHalf, centerY, centerX + coreHalf, centerY, linePaint)
            }
        }

        if (isAtRest()) {
            snapToTargets()
            stopAnimator()
        }
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
        private const val WAVE_ALPHA = 0.55f
        private const val CORE_ALPHA = 0.95f
        private const val GLOW_ALPHA = 0.22f
        private const val DORMANT_ALPHA = 0.35f
    }
}
