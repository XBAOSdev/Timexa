package com.xbaos.shijianapp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import androidx.core.content.ContextCompat
import com.xbaos.shijianapp.R

/**
 * 「找到了，就是这一项」的定位高亮。
 *
 * ## 解决什么问题
 *
 * 全局搜索能搜到设置项，但点进去只落到设置页**顶部** ——
 * 设置页有七组、二十多行，「你要找的那一项在第几屏」得用户自己翻。
 * 搜索因此等于半失效。这里负责「落点之后怎么告诉用户是哪一行」：
 * 让目标行轻微闪动两次（≈1.1 秒），视线会不由自主地被那次呼吸般的明暗带走。
 *
 * ## 为什么用 foreground 而不是背景
 *
 * 行的背景已经被 `bg_row_tappable` 的 selector 占着（按下态换色），
 * 直接改背景会在动画结束后无法还原，也会与按压反馈打架。
 * `View.foreground` 是独立的一层，画在行内容之上，用完置空即可，零副作用。
 * （`View.setForeground` 是 API 23 引入的，本项目 minSdk 24，可以直接用。）
 *
 * ## 为什么是「两次」而不是一次或循环
 *
 * 一次闪烁容易被当成渲染抖动；循环闪则像「出错了」。两次是一个完整的
 * 「看这里 → 再确认一下」的节奏，也刚好匹配 `highlight_pulse_duration` 的四程动画
 * （0→亮→暗→亮→暗）。
 */
object Highlight {

    /** 默认闪动次数。两次是「被注意到」与「不烦人」之间的档位。 */
    private const val DEFAULT_TIMES = 2

    /**
     * 让 [view] 轻微闪动 [times] 次。
     *
     * 视图还没测量（宽高为 0）时会自己 post 到下一帧再试 ——
     * 刚 `addView` 完就调用是常见情形，此时直接加 foreground 会得到一个零尺寸的覆盖层。
     */
    fun flash(view: View, times: Int = DEFAULT_TIMES) {
        if (view.width <= 0 || view.height <= 0) {
            view.post { flash(view, times) }
            return
        }

        // mutate()：drawable 从资源里取出时是共享实例，不改可变状态就会影响别处同样引用它的视图
        val overlay = ContextCompat.getDrawable(view.context, R.drawable.bg_highlight_flash)
            ?.mutate() ?: return

        // 同一行被连续触发（例如用户连点两次搜索结果）时，先掐掉上一段动画：
        // 否则两段动画会同时往同一个 alpha 上写，明暗会跳。
        (view.getTag(R.id.highlight_anim_tag) as? ValueAnimator)?.cancel()

        val previous = view.foreground
        overlay.alpha = 0
        view.foreground = overlay

        val peak = (view.resources.getFraction(R.fraction.highlight_peak_alpha, 1, 1) * 255).toInt()
        val segmentMs = view.resources.getInteger(R.integer.highlight_pulse_duration).toLong()

        // 关键帧：[0, 1, 0, 1, 0] —— times = 2 时的「暗→亮→暗→亮→暗」
        val steps = times * 2 + 1
        val frames = FloatArray(steps) { if (it % 2 == 0) 0f else 1f }

        ValueAnimator.ofFloat(*frames).apply {
            duration = segmentMs * (steps - 1)
            addUpdateListener { overlay.alpha = ((it.animatedValue as Float) * peak).toInt() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 期间若已被另一次 flash 接管，就不要把它的覆盖层清掉
                    if (view.foreground === overlay) view.foreground = previous
                    if (view.getTag(R.id.highlight_anim_tag) === animation) {
                        view.setTag(R.id.highlight_anim_tag, null)
                    }
                }
            })
            view.setTag(R.id.highlight_anim_tag, this)
            start()
        }
    }
}
