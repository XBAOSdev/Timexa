package com.xbaos.shijianapp.ui

import android.animation.ObjectAnimator
import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.xbaos.shijianapp.R

/**
 * 页面顶部的「申请原因」临时横幅。
 *
 * 为什么需要它：运行时权限的系统弹窗只写一句「允许 XX 访问位置信息吗？」，
 * 用户在这个时刻并不知道**这个应用为什么要这个权限**。国内应用商店的审核也要求
 * 「申请前需向用户说明用途」。因此在系统弹窗弹出之前，先在页面顶部浮出一条说明，
 * 停留一小会儿（或一直显示到权限结果返回），用户看完再弹系统框。
 *
 * 形态刻意与 AppDialog 区分：
 *  · AppDialog 是居中卡片，用于「需要用户做决策」；
 *  · 本横幅是顶部贴边的窄条，非阻塞、不遮内容中心，也不吃按键 ——
 *    它的职责只是「说一句话」，不需要用户点任何东西。
 *
 * 使用方式（在 Activity 里）：
 *   PermissionBanner.show(this, R.string.perm_location_banner)
 * 横幅会挂在 `android.R.id.content` 下，因此不依赖任何页面的布局结构；
 * 同一时刻只保留一条，重复调用会先替换掉旧的。
 */
object PermissionBanner {

    /** 横幅在整个 Activity 里的唯一标记，用于「先移除旧的再加新的」。 */
    private const val TAG_BANNER = "permission_banner"

    /** 自动隐藏前的停留时长。留够读完一句话的时间，又不至于一直压在顶上。 */
    private const val VISIBLE_MS = 2600L

    /**
     * **持久模式**下的兜底时长，见 [show] 的 `persistent` 参数。
     *
     * 持久横幅等的是「权限结果回调」，正常情况下几秒内必到；但用户可能直接按 Home 走人，
     * 或某些 ROM 在「不再询问」之后压根不弹框（回调也就不会来）。没有兜底的话，
     * 这条横幅会一直挂在页面顶部直到 Activity 销毁 —— 那就成了一件「留在屏幕上的脏东西」。
     * 60 秒足够覆盖任何真实交互，又不至于让人看到一条永远不走的提示。
     */
    private const val PERSISTENT_TIMEOUT_MS = 60_000L

    /**
     * 顶栏之下的纵向偏移。
     * 各页面的顶栏高度由 BaseActivity 附加的系统栏 inset + topbar_height 组成，
     * 这里只保证横幅落在**内容区之上、状态栏之下**的合理位置，
     * 取 topbar_height 作为起始偏移即可（不至于压住标题）。
     */
    /**
     * 显示一条「申请原因」横幅。
     *
     * @param persistent 传 true = **一直显示到权限结果返回**，由调用方（或
     *   BaseActivity 的统一转发）在 `onRequestPermissionsResult` 里调 [dismiss] 收起。
     *   用途是「系统权限弹窗正显示在屏幕上」的那段时间 —— 用户恰恰是在这一步需要知道
     *   「这个应用为什么要这个权限」；横幅若按默认的 2.6 秒自己走了，
     *   等系统框真正弹出来时说明早就看不见了，等于白说一句。
     *   传 false（默认）则是「说完一句就走」的临时提示。
     */
    @JvmStatic
    @JvmOverloads
    fun show(activity: Activity, @StringRes messageRes: Int, persistent: Boolean = false) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // 同一时刻只留一条：语言切换或连续两次申请时不至于叠成一块
        root.findViewWithTag<View>(TAG_BANNER)?.let { root.removeView(it) }

        val banner = LayoutInflater.from(activity)
            .inflate(R.layout.item_permission_banner, root, false)
        banner.tag = TAG_BANNER
        banner.findViewById<TextView>(R.id.banner_text).setText(messageRes)

        root.addView(
            banner,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                // 横幅挂在 content 视图下，**在页面根布局之外**，因此拿不到
                // BaseActivity 施加的 insets padding。这里必须自己把状态栏让开，
                // 否则横幅会钻到状态栏下面（尤其是带刘海/挖孔的机型）。
                topMargin = statusBarHeight(activity) +
                    activity.resources.getDimensionPixelSize(R.dimen.topbar_height) +
                    activity.resources.getDimensionPixelSize(R.dimen.space_s)
                marginStart = activity.resources.getDimensionPixelSize(R.dimen.screen_padding)
                marginEnd = activity.resources.getDimensionPixelSize(R.dimen.screen_padding)
            }
        )

        // 从上方滑入 + 淡入：一眼能看出是「刚出现的提示」，而不是页面的一部分
        banner.translationY = -banner.resources.getDimension(R.dimen.banner_slide_in)
        banner.alpha = 0f
        banner.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(banner.resources.getInteger(R.integer.banner_anim_duration).toLong())
            .start()

        // 持久横幅只是把兜底时间拉长，而不是完全不设 —— 理由见 PERSISTENT_TIMEOUT_MS
        banner.postDelayed(
            { dismiss(activity) },
            if (persistent) PERSISTENT_TIMEOUT_MS else VISIBLE_MS
        )
    }

    /** 立即收起横幅。权限结果返回后可以提前调用，不必等计时结束。 */
    @JvmStatic
    fun dismiss(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val banner = root.findViewWithTag<View>(TAG_BANNER) ?: return
        if (banner.isVisible.not() && banner.alpha == 0f) return
        val duration = banner.resources.getInteger(R.integer.banner_anim_duration).toLong()
        ObjectAnimator.ofFloat(banner, View.ALPHA, banner.alpha, 0f).apply {
            this.duration = duration
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    root.removeView(banner)
                }
            })
            start()
        }
    }

    /**
     * 状态栏高度。
     *
     * 为什么不用 WindowInsets：横幅是「用完即走」的临时视图，
     * 为一个一次性提示挂 insets 监听（还要处理移除时机）不值得。
     * 系统资源 `status_bar_height` 在所有版本上都存在，且这个值不会在运行中变化。
     * 万一取不到（个别深度定制 ROM 会藏掉该资源），退回 24dp —— 这是绝大多数设备的下限。
     */
    private fun statusBarHeight(activity: Activity): Int {
        val resId = activity.resources.getIdentifier(
            "status_bar_height", "dimen", "android"
        )
        return if (resId > 0) {
            activity.resources.getDimensionPixelSize(resId)
        } else {
            (24 * activity.resources.displayMetrics.density).toInt()
        }
    }
}
