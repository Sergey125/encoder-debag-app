package com.example.encoder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class EncoderWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var maxValue: Int = 600

    /** Абсолютный непрерывный угол стрелки в градусах — не сбрасывается на границе. */
    private var spokeAngleDeg: Double = -90.0

    var position: Int = 0
        set(v) {
            val wrapped = ((v % maxValue) + maxValue) % maxValue
            if (field != wrapped) {
                field = wrapped
                invalidate()
            }
        }

    var isActive: Boolean = false
        set(v) { field = v; invalidate() }

    var isForward: Boolean = true
        set(v) { field = v; invalidate() }

    /**
     * Двигает стрелку на заданное число шагов в заданную сторону.
     * Угол копится непрерывно, поэтому перехода 599→0 стрелка не замечает.
     */
    fun rotateBy(steps: Int, forward: Boolean) {
        val degreesPerStep = 360.0 / maxValue
        spokeAngleDeg += if (forward) steps * degreesPerStep else -steps * degreesPerStep
        invalidate()
    }

    fun resetAngle() {
        spokeAngleDeg = -90.0
        invalidate()
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2E2E2E")
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A4A4A")
    }
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9E9E9E")
        textAlign = Paint.Align.CENTER
    }
    private val dirPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val stroke = w * 0.03f
        trackPaint.strokeWidth = stroke
        spokePaint.strokeWidth = w * 0.035f

        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - stroke * 1.5f

        numberPaint.textSize = radius * 0.55f
        labelPaint.textSize = radius * 0.16f
        dirPaint.textSize = radius * 0.22f
    }

    private fun activeColor(): Int =
        if (isForward) Color.parseColor("#2196F3") else Color.parseColor("#FF9800")

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, trackPaint)

        for (i in 0 until 20) {
            val angle = Math.toRadians((i * 18).toDouble())
            val inner = radius * 0.86f
            val outer = radius * 0.96f
            canvas.drawLine(
                cx + inner * cos(angle).toFloat(),
                cy + inner * sin(angle).toFloat(),
                cx + outer * cos(angle).toFloat(),
                cy + outer * sin(angle).toFloat(),
                tickPaint
            )
        }

        val angleRad = Math.toRadians(spokeAngleDeg)
        val color = if (isActive) activeColor() else Color.parseColor("#5A5A5A")
        spokePaint.color = color
        hubPaint.color = color

        val spokeLen = radius * 0.75f
        canvas.drawLine(
            cx, cy,
            cx + spokeLen * cos(angleRad).toFloat(),
            cy + spokeLen * sin(angleRad).toFloat(),
            spokePaint
        )
        canvas.drawCircle(cx, cy, radius * 0.06f, hubPaint)

        val text = position.toString()
        val fm = numberPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baseline, numberPaint)

        if (isActive) {
            dirPaint.color = color
            canvas.drawText(
                if (isForward) "▲ +" else "▼ −",
                cx, baseline + radius * 0.32f, dirPaint
            )
        } else {
            canvas.drawText("/ $maxValue", cx, baseline + radius * 0.30f, labelPaint)
        }
    }
}