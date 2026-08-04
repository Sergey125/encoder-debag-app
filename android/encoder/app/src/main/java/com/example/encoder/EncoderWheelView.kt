package com.example.encoder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class EncoderWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MINOR_TICKS = 60      // мелких делений по кругу
        private const val MAJOR_EVERY = 5       // каждое 5-е деление крупное (итого 12)
        private const val FRAME_MS = 16L        // ~60 кадров/с
        private const val EASING = 0.25         // доля оставшегося пути за кадр
        private const val SNAP_THRESHOLD = 0.5  // ближе этого — доводим сразу
        private const val JUMP_THRESHOLD = 180.0 // отстали больше — переставляем без анимации
    }

    var maxValue: Int = 600

    /** Текущий отрисованный угол стрелки. */
    private var spokeAngleDeg: Double = -90.0

    /** Угол, к которому стрелка плавно доезжает. */
    private var targetAngleDeg: Double = -90.0

    private var animating = false

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
     * Сдвигает целевой угол. Оба угла удерживаются в пределах одного
     * оборота, иначе за длинную сессию значения уходят в тысячи градусов
     * и в Double накапливается ошибка — стрелка начинает вести себя
     * непредсказуемо.
     */
    fun rotateBy(steps: Int, forward: Boolean) {
        val degreesPerStep = 360.0 / maxValue
        targetAngleDeg += if (forward) steps * degreesPerStep else -steps * degreesPerStep

        // Если стрелка отстала больше чем на пол-оборота, догонять
        // по длинному пути бессмысленно — переставляем сразу.
        if (abs(targetAngleDeg - spokeAngleDeg) > JUMP_THRESHOLD) {
            spokeAngleDeg = targetAngleDeg
        }

        // Нормализация: не даём значениям расти бесконечно
        if (abs(targetAngleDeg) > 360.0) {
            val whole = (targetAngleDeg / 360.0).toInt() * 360.0
            targetAngleDeg -= whole
            spokeAngleDeg -= whole
        }

        startAnimation()
    }

    fun resetAngle() {
        removeCallbacks(animator)
        animating = false
        spokeAngleDeg = -90.0
        targetAngleDeg = -90.0
        invalidate()
    }

    private val animator = object : Runnable {
        override fun run() {
            val diff = targetAngleDeg - spokeAngleDeg
            if (abs(diff) < SNAP_THRESHOLD) {
                spokeAngleDeg = targetAngleDeg
                animating = false
                invalidate()
                return
            }
            spokeAngleDeg += diff * EASING
            invalidate()
            postDelayed(this, FRAME_MS)
        }
    }

    private fun startAnimation() {
        if (!animating) {
            animating = true
            post(animator)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animator)
        animating = false
        super.onDetachedFromWindow()
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2E2E2E")
    }
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D3D3D")
    }
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6E6E")
    }
    private val zeroTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
    }
    private val scaleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A7A7A")
        textAlign = Paint.Align.CENTER
    }
    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
        val stroke = w * 0.025f
        trackPaint.strokeWidth = stroke
        minorTickPaint.strokeWidth = w * 0.006f
        majorTickPaint.strokeWidth = w * 0.012f
        zeroTickPaint.strokeWidth = w * 0.016f
        spokePaint.strokeWidth = w * 0.032f
        tailPaint.strokeWidth = w * 0.018f

        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - stroke * 2.2f

        numberPaint.textSize = radius * 0.50f
        labelPaint.textSize = radius * 0.15f
        dirPaint.textSize = radius * 0.20f
        scaleLabelPaint.textSize = radius * 0.11f
    }

    private fun activeColor(): Int =
        if (isForward) Color.parseColor("#2196F3") else Color.parseColor("#FF9800")

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radius, trackPaint)

        for (i in 0 until MINOR_TICKS) {
            val angleDeg = -90.0 + i * (360.0 / MINOR_TICKS)
            val angle = Math.toRadians(angleDeg)

            val isMajor = i % MAJOR_EVERY == 0
            val isZero = i == 0

            val paint = when {
                isZero -> zeroTickPaint
                isMajor -> majorTickPaint
                else -> minorTickPaint
            }
            val inner = when {
                isZero -> radius * 0.76f
                isMajor -> radius * 0.82f
                else -> radius * 0.89f
            }

            canvas.drawLine(
                cx + inner * cos(angle).toFloat(),
                cy + inner * sin(angle).toFloat(),
                cx + radius * 0.97f * cos(angle).toFloat(),
                cy + radius * 0.97f * sin(angle).toFloat(),
                paint
            )

            if (isMajor) {
                val value = (i * maxValue / MINOR_TICKS)
                val labelR = radius * 0.66f
                val fm = scaleLabelPaint.fontMetrics
                canvas.drawText(
                    value.toString(),
                    cx + labelR * cos(angle).toFloat(),
                    cy + labelR * sin(angle).toFloat() - (fm.ascent + fm.descent) / 2f,
                    scaleLabelPaint
                )
            }
        }

        val angleRad = Math.toRadians(spokeAngleDeg)
        val color = if (isActive) activeColor() else Color.parseColor("#5A5A5A")
        spokePaint.color = color
        hubPaint.color = color
        tailPaint.color = Color.parseColor("#404040")

        val tailLen = radius * 0.22f
        canvas.drawLine(
            cx, cy,
            cx - tailLen * cos(angleRad).toFloat(),
            cy - tailLen * sin(angleRad).toFloat(),
            tailPaint
        )

        val spokeLen = radius * 0.70f
        val tipX = cx + spokeLen * cos(angleRad).toFloat()
        val tipY = cy + spokeLen * sin(angleRad).toFloat()
        canvas.drawLine(cx, cy, tipX, tipY, spokePaint)
        canvas.drawCircle(tipX, tipY, radius * 0.055f, hubPaint)
        canvas.drawCircle(cx, cy, radius * 0.055f, hubPaint)

        val text = position.toString()
        val fm = numberPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baseline, numberPaint)

        if (isActive) {
            dirPaint.color = color
            canvas.drawText(
                if (isForward) "▲ +" else "▼ −",
                cx, baseline + radius * 0.30f, dirPaint
            )
        } else {
            canvas.drawText("/ $maxValue", cx, baseline + radius * 0.28f, labelPaint)
        }
    }
}