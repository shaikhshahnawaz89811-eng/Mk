package com.codeassist.ai.build

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class GlowArcView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private var sweep = 86f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { rotation = it.animatedValue as Float; invalidate() }
    }
    private var rotation = 0f

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = paint.strokeWidth + 6f
        val r = RectF(pad, pad, width - pad, height - pad)
        paint.color = 0xFF6EC1FF.toInt()
        paint.alpha = 220
        canvas.drawArc(r, rotation, sweep, false, paint)
        paint.alpha = 70
        canvas.drawArc(r, rotation + 180f, 70f, false, paint)
    }
}
