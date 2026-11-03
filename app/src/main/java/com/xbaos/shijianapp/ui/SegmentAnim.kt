package com.xbaos.shijianapp.ui

import android.view.View
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.xbaos.shijianapp.R

/**
 * 分段控件（Segment）的滑块动画。
 *
 * 原来的实现是「选中项自己浮出白底」（`item_segment_unit` 上挂 selector），
 * 切换时白块会硬切到另一格，是设置页里最明显的一处「生硬」。
 *
 * 现在改成 iOS 式的做法：
 *  轨道（bg_segment_track）里放一个**独立的白底滑块**，所有分段单元都画在它上面，
 *  切换时只把滑块平移到目标格并改宽度。
 *
 * ## 为什么用 `layout()` 手动定位，而不是 layoutParams + translationX
 *
 * 滑块宽 `0dp` 且没有 `weight`，`LinearLayout` 在测量阶段会把它算成 0 宽 ——
 * 它只是块装饰物，本就不该参与流式布局。若走 `updateLayoutParams { width = ... }` 这条路：
 *  1. 改 `layoutParams` 不会自动触发重新布局（缺 `requestLayout()`），宽度不生效；
 *  2. 即便补上 `requestLayout()`，父容器一旦因别的原因重排，滑块的 `left`
 *     会被重置回 padding 处，而动画用的 `translationX` 是叠加在 `left` 之上的，
 *     于是滑块**跳回左边缘、宽度还是窄的** —— 这就是「滑块位置和项目对不上」的 bug。
 *
 * 所以滑块的几何完全由本对象掌握：用 `layout()` 直接落地，
 * 动画用 `translationX` 叠加在「目标格的实际位置」之上（见 [place]）。
 */
object SegmentAnim {

    /** 滑块在轨道子节点里的位置，必须是第一个。 */
    private const val SLIDER_INDEX = 0

    /**
     * 把滑块对齐到 [index] 这一格。
     *
     * @param track 承载滑块的容器（`@id/setting_segment`）
     * @param units 各分段单元，顺序与滑块的目标位置一一对应
     * @param animate 首次布局时传 false（直接落位，避免进页面时看到滑块从左边滑过来）
     */
    fun moveTo(track: LinearLayout, units: List<View>, index: Int, animate: Boolean) {
        val slider = track.getChildAt(SLIDER_INDEX) ?: return
        val target = units.getOrNull(index) ?: return

        // 首帧时目标格可能还没测量完，宽度是 0。等它测量完再落位。
        if (target.width == 0) {
            target.post { moveTo(track, units, index, animate) }
            return
        }

        // 每次布局后父容器都会把滑块的 left 打回 padding 处，
        // 所以这里顺手挂一个「重排后按当前格重新对齐」的监听（只挂一次）。
        // 用 slider 的 tag 存当前格号，监听器只读它，天然跟随最新状态。
        if (track.getTag(R.id.segment_track_bound_tag) == null) {
            track.setTag(R.id.segment_track_bound_tag, true)
            track.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val t = view as? LinearLayout ?: return@addOnLayoutChangeListener
                val s = t.getChildAt(SLIDER_INDEX) ?: return@addOnLayoutChangeListener
                val idx = s.tag as? Int ?: return@addOnLayoutChangeListener
                val all = (0 until t.childCount).map { t.getChildAt(it) }.drop(SLIDER_INDEX + 1)
                val unit = all.getOrNull(idx) ?: return@addOnLayoutChangeListener
                if (unit.width > 0 && s.isVisible && s.width != unit.width) {
                    place(t, s, unit, animate = false)
                }
            }
        }

        slider.tag = index
        place(track, slider, target, animate)
    }

    /** 把滑块几何切到 [target] 那一格；[animate] 为真时从旧位置平移过去。 */
    private fun place(track: LinearLayout, slider: View, target: View, animate: Boolean) {
        // 滑块的几何必须**正好落在目标格的字形盒上**，四个方向都由轨道的内边距决定：
        //
        // 早先这里写成 `target.left - track.paddingLeft`，那是错的 ——
        // 子视图的 left 本来就含内边距，再减一次等于把滑块整体左移一个内边距：
        // 左边贴死轨道外框（内缩 0）、右边却留了 2 倍内边距，左右不对称，
        // 于是滑块的圆角弧心与轨道外框的弧心横向差了一个内边距、两段弧不共心，
        // 看起来就是「圆角弧度不一样」（用户报告的 bug）。
        //
        // 直接用 target.left：横向内缩 = paddingLeft，与纵向的 paddingTop 对称，
        // 于是「内圆角 = 外圆角 − 内边距」这条同心条件才真正成立。
        val left = target.left
        val top = track.paddingTop
        val right = left + target.width
        val bottom = track.height - track.paddingBottom

        val wasVisible = slider.isVisible
        // 旧位置要在改几何之前取；再判断一下它是不是「有效」的：
        // 从未落位过时 left 恒为 padding 处，不该被当成起点滑过来。
        val oldLeft = slider.left

        slider.isVisible = true

        // 先瞬间把几何切到目标格，再用 translationX 反向偏移「骗」回旧位置，最后动画归零
        // —— 得到一次从旧格滑到新格的平移。
        //
        // 宽度不参与动画（直接换）：各段宽度差只有几个字符，
        // 硬切宽度的跳变看不出来；若改用 scaleX 过渡，圆角与 1dp 描边会被横向拉伸变形。
        slider.layout(left, top, right, bottom)
        slider.translationX = if (wasVisible && animate) (oldLeft - left).toFloat() else 0f

        if (animate && wasVisible) {
            val duration = track.resources.getInteger(R.integer.segment_anim_duration).toLong()
            slider.animate()
                .translationX(0f)
                .setDuration(duration)
                .start()
        }
    }
}
