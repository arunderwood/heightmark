package com.bizzarosn.heightmark

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet

class ElevationTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : androidx.appcompat.widget.AppCompatTextView(context, attrs, defStyleAttr) {

    private var loadingAnimator: ValueAnimator? = null

    init {
        text = context.getString(R.string.loading_elevation)

        loadingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            addUpdateListener { animator ->
                val alpha = animator.animatedValue as Float
                this@ElevationTextView.alpha = alpha
            }
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
        }
    }

    fun startLoadingAnimation() {
        loadingAnimator?.start()
    }

    private fun stopLoadingAnimation() {
        loadingAnimator?.cancel()
        alpha = 1f
    }

    fun updateElevation(elevation: Double) {
        stopLoadingAnimation()
        val elevationRounded = kotlin.math.round(elevation).toInt()
        text = context.getString(R.string.elevation_text, elevationRounded)
    }
}