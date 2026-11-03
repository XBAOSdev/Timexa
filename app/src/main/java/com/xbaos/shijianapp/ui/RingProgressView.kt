package com.xbaos.shijianapp.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.xbaos.shijianapp.R

/**
 * 细环形进度。
 *
 * 为什么自己画而不是用 ProgressBar：
 *  - 系统环形 ProgressBar 的粗细、圆头、起始角都由主题决定，改不动，且会带上 Material 的
 *    动画与配色推导，和本项目的莫兰迪纯色风格不一路；
 *  - 需求只有「两个圆 + 一段圆弧」，自己画不到 60 行，还能把颜色交给调用方按阶段切换。
 *
 * 颜色刻意不写死：传入的资源 id 在这里解析，因此深浅模式切换后会跟着 values-night 走，
 * 但同时提供 [ringColor] 供调用方覆盖（番茄钟用它按阶段换色）。
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strokeWidth = resources.getDimension(R.dimen.pomodoro_ring_stroke)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RingProgressView.strokeWidth
        color = ContextCompat.getColor(context, R.color.stroke)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RingProgressView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private val arcBounds = RectF()

    /** 进度，0f~1f。 */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /** 进度弧的颜色（已解析的色值，不是资源 id）。 */
    var ringColor: Int = ContextCompat.getColor(context, R.color.accent)
        set(value) {
            if (field != value) {
                field = value
                progressPaint.color = value
                invalidate()
            }
        }

    init {
        progressPaint.color = ringColor
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = (minOf(width, height) - strokeWidth) / 2f
        if (radius <= 0f) return
        val centerX = width / 2f
        val centerY = height / 2f

        // 背景整圈：用极淡的描边色，只做「轨道」提示，不抢视觉
        canvas.drawCircle(centerX, centerY, radius, trackPaint)

        if (progress > 0f) {
            arcBounds.set(
                centerX - radius,
                centerY - radius,
                centerX + radius,
                centerY + radius
            )
            // 从 12 点方向顺时针填充
            canvas.drawArc(arcBounds, -90f, 360f * progress, false, progressPaint)
        }
    }
}
