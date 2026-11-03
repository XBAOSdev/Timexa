package com.xbaos.shijianapp.ui

import android.animation.TimeInterpolator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.webkit.WebView
import com.xbaos.shijianapp.R

/**
 * 全 App 统一的「按压反馈」：**视觉缩放 + 触感**两件事都在这里发。
 *
 * 解决什么问题：
 *  本项目所有可点控件都是 `TextView` / `ImageButton` + selector 背景，
 *  selector 只能换颜色，换不出「按下去沉一点」的物理感，点起来偏硬。
 *  这里用 ViewPropertyAnimator 做一次极短的缩放（幅度见 `values/fractions.xml` 的
 *  `press_scale_down`，时长见 `values/integers.xml`），
 *  成本几乎为零，却能让每一次点击都有回应。
 *
 *  **两件事的节奏刻意不同**（见 [Haptics.tap]）：
 *   · 缩放发在 **按下** —— 手指一落就得「沉」下去，晚一帧就不跟手了；
 *   · 触觉发在 **松手** —— 那才是这次点击真正成立的时刻，也才与系统控件（Switch、
 *     系统设置里的各项）的节奏一致。按下就振等于对一件还没发生的事给反馈。
 *
 * 用法——两种，按场景选：
 *  1. 单个按钮 / 图片按钮：在其所在页面里调 [attach]；
 *  2. 由装配器（`AppEntry`、`Settings` 等）统一批量挂载（推荐，不会漏）。
 *  [attachTree] 挂的是默认轻触语义；要更精确的语义（开关 / 分段 / 危险操作）就传 [attach] 的 haptic 参数。
 *
 * 注意：松手时如果手指滑出了控件范围，系统只会给 ACTION_CANCEL，
 * 所以两个结束事件都要处理，否则控件会永远停在缩小状态。
 */
object PressFeedback {

    /** 按下的曲线：起步快、结尾缓，让「按下去」立刻被感知到。 */
    private val pressInterpolator: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** 松手回弹的曲线：带一点过冲，手感更活。 */
    private val releaseInterpolator: TimeInterpolator = PathInterpolator(0.34f, 1.3f, 0.64f, 1f)

    /**
     * 给一个控件挂上按压反馈（视觉缩放 + 触感）。
     *
     * 只挂 [View.OnTouchListener]，**不消费事件**（返回 false），
     * 因此不会影响原有的 `setOnClickListener` / `setOnLongClickListener`。
     * 这是本实现能与既有页面零侵入共存的关键。
     *
     * ⚠⚠ **触觉必须挂在这里，不能另起一个 `setOnTouchListener`** ——
     * 一个 View **只能有一个** `OnTouchListener`，新建并列的会**静默覆盖**掉按压缩放：
     * 不报错、不崩溃，只是视觉反馈全部消失。而且「松手是否算一次有效点击」这个判断
     * （有没有滑出控件、控件是不是可用）也只有共用一个 listener 才拿得到完整的事件序列。
     *
     * @param haptic 语义常量提供者。不传时按最通用的轻触（[FeedbackKind.LIGHT]）处理 ——
     *   于是 `attachTree` 的全量挂载不需要任何额外代码。
     *   需要更精确语义的装配器（开关、分段控件、危险按钮）传 lambda，例如：
     *   `attach(sw) { if (sw.isChecked) FeedbackKind.toggleOff() else FeedbackKind.toggleOn() }`。
     *   ⚠ 这个 lambda 在 **`ACTION_UP`** 求值。对 `SwitchCompat` 来说 `isChecked` 此刻仍是
     *     **旧值**（`CompoundButton` 是先派发 touch 事件、后 `performClick()` 才切换），
     *     所以按「旧值」推断这次会变成什么即可。
     *     更省心的做法见 `Settings.addSwitch`：语义触觉直接放进 `OnCheckedChangeListener`，
     *     那里读到的是**新值**，方向天然不会写反。
     */
    fun attach(view: View, haptic: (() -> Int)? = null) {
        if (view.getTag(KEY_ATTACHED) == true) return
        view.setTag(KEY_ATTACHED, true)

        val pressDuration = view.resources.getInteger(R.integer.press_anim_duration).toLong()
        val releaseDuration = view.resources.getInteger(R.integer.release_anim_duration).toLong()

        // 缩放比例必须用 **fraction**，不能用 dimen。
        // 教训（曾导致启动即崩溃）：
        //   `<dimen name="x">0.95</dimen>` 这种**没有单位**的值，aapt2 不会把它编成
        //   dimension 类型，`Resources.getDimension()` 会直接抛
        //   `Resources$NotFoundException: Resource ID #0x... type #0x4 is not valid`。
        //   fraction 才是 Android 里表达「0~1 的比例」的资源类型，`getFraction()` 也不做密度换算，
        //   因此这里是它的正解（此前「读 dimen 再除 density 还原」的写法既绕又根本行不通）。
        val ratio = view.resources.getFraction(R.fraction.press_scale_down, 1, 1)
        val normal = view.resources.getFraction(R.fraction.press_scale_normal, 1, 1)

        view.setOnTouchListener { v, event ->
            // 禁用的控件不给任何按压反馈。
            // 判据取 isEnabled 而不是 isClickable：本 App 里「暂时不可用」一律用
            // isEnabled=false 表达（例如组件中心里已经加过桌面快捷方式的那几行），
            // 此时它看起来就是「点不动」的，按下却仍然缩一下、反而更像能点。
            // 触觉那边本来就有同样的判断（见 ACTION_UP），这里补上视觉的一半。
            if (!v.isEnabled) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 只做视觉缩放：手指一落就「沉」下去，才跟手。
                    // 触觉**不在这里**发，见下面 ACTION_UP 的注释。
                    animate(v, ratio, pressDuration, pressInterpolator)
                }

                MotionEvent.ACTION_UP -> {
                    // ⚠⚠ 触觉发在**松手**这一刻（曾发在 ACTION_DOWN，用户实测反馈「碰到就振」
                    // 不像系统控件）。三个理由：
                    //  · 按下的瞬间只代表「手指碰到了」，用户还可能滑走、放弃这次点击；
                    //  · 系统自身的控件都是松手才振，跟着它走，跨应用手感才一致；
                    //  · 确实点成了才给反馈 —— 滑出控件再松手时系统仍会发 ACTION_UP，
                    //    但**不会**触发 onClick，此时若振就成了「没点中却在振」。
                    //    真正的滑动（列表滚动）由父容器拦截并改发 ACTION_CANCEL，走不到这里。
                    if (v.isEnabled && isInside(v, event)) {
                        Haptics.tap(v, haptic?.invoke() ?: FeedbackKind.LIGHT)
                    }
                    animate(v, normal, releaseDuration, releaseInterpolator)
                }

                // 滑出控件，或被父容器拦去滚动：只回弹，不发触觉。
                MotionEvent.ACTION_CANCEL -> animate(v, normal, releaseDuration, releaseInterpolator)
            }
            false
        }
    }

    /**
     * 松手时手指是否仍在控件范围内。
     *
     * `MotionEvent.x/y` 是相对该控件的坐标，滑出后会变成负数或大于控件宽高。
     * 用 `< width/height`（而不是 `<=`）与 View 自身的命中判定保持一致：
     * 右/下边界那一像素不算在内。
     */
    private fun isInside(view: View, event: MotionEvent): Boolean =
        event.x >= 0f && event.x < view.width.toFloat() &&
            event.y >= 0f && event.y < view.height.toFloat()

    /** 批量挂载，供各装配器一次调用。 */
    fun attachAll(vararg views: View) {
        views.forEach { it?.let(::attach) }
    }

    /**
     * 递归把整棵视图树里「可点击」的控件都挂上按压反馈。
     *
     * 递归时**不进 Card / 列表容器内部**是做不到的（它们本身就是 LinearLayout），
     * 因此判据取「自己 clickable」而不是「是不是按钮类型」——
     * 布局里凡是写了 `android:clickable="true"` 或调过 setOnClickListener 的节点都会被命中，
     * 而纯展示的 TextView 不会被误挂。
     *
     * 例外：**`WebView` 及整棵子树一律跳过**。
     * WebView 默认就是 clickable，但它整块都是可滚动内容区，
     * 按下去整体缩小 5% 会连同里面的网页一起缩放，观感非常突兀（用户反馈）。
     * 网页内部按钮的反馈由网页自己用 CSS 处理，原生层不该介入。
     */
    fun attachTree(root: View) {
        if (root is WebView) return
        if (root.isClickable) attach(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) attachTree(root.getChildAt(i))
        }
    }

    private fun animate(
        view: View,
        scale: Float,
        duration: Long,
        interpolator: TimeInterpolator
    ) {
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()
    }

    private val KEY_ATTACHED = R.id.press_feedback_tag
}
