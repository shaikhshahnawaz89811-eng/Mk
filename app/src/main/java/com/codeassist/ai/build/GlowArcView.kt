package com.codeassist.ai.build

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** Lightweight progress ring used by the project skill-loading screen. */
class GlowArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val bounds = RectF()
    private var sweep = 110f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = paint.strokeWidth
        bounds.set(inset, inset, width - inset, height - inset)
        paint.color = 0xFF6EC1FF.toInt()
        canvas.drawArc(bounds, -90f, sweep, false, paint)
    }

    fun setProgress(progress: Float) {
        sweep = (progress.coerceIn(0f, 1f) * 360f).coerceAtLeast(24f)
        invalidate()
    }
}
