package com.example.encoder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class TemperatureDialView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val START_ANGLE = 135f   // начало дуги — левый нижний угол
        private const val SWEEP_ANGLE = 270f   // разрыв снизу, как на макете
    }

    var minTemp = 16
    var maxTemp = 30

    var mode: String = "Охлаждение"
        set(v) { field = v; invalidate() }

    var temperature: Int = 22
        set(v) {
            val clamped = v.coerceIn(minTemp, maxTemp)
            if (field != clamped) {
                field = clamped
                onTemperatureChanged?.invoke(clamped)
            }
            invalidate()
        }

    var onTemperatureChanged: ((Int) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2E2E2E")
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2196F3")
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tempPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8E8E8")
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2196F3")
        textAlign = Paint.Align.LEFT
    }
    private val modePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8E8E8")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val arcRect = RectF()
    private var radius = 0f
    private var cx = 0f
    private var cy = 0f
    private var thumbR = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val stroke = w * 0.055f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        thumbR = w * 0.038f

        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - stroke
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        tempPaint.textSize = radius * 0.62f
        unitPaint.textSize = radius * 0.20f
        modePaint.textSize = radius * 0.18f
    }

    private fun fraction(): Float =
        (temperature - minTemp).toFloat() / (maxTemp - minTemp).coerceAtLeast(1)

    override fun onDraw(canvas: Canvas) {
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        val sweep = SWEEP_ANGLE * fraction()
        if (sweep > 0.5f) {
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, progressPaint)
        }

        val a = Math.toRadians((START_ANGLE + sweep).toDouble())
        canvas.drawCircle(
            cx + radius * cos(a).toFloat(),
            cy + radius * sin(a).toFloat(),
            thumbR, thumbPaint
        )

        canvas.drawText(mode, cx, cy - radius * 0.28f, modePaint)

        val text = temperature.toString()
        val fm = tempPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2 + radius * 0.10f
        canvas.drawText(text, cx, baseline, tempPaint)

        val halfWidth = tempPaint.measureText(text) / 2f
        canvas.drawText("°C", cx + halfWidth + radius * 0.04f, baseline - radius * 0.34f, unitPaint)
    }
}