package com.xbaos.shijianapp.appsys

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * 桌面小组件的公共设施：估算「真实占位尺寸」+ 请求一键固定。
 *
 * 两个 Provider（秒表 / 纪念日）共用，避免各自抄一份。
 */
object WidgetPin {

    /** 2×2 组件在桌面上的宽度约占屏宽的多少（实测反推，见 [estimateSizePx]）。 */
    private const val SPAN_WIDTH_RATIO = 0.42f

    /** 2×2 组件在桌面上的高度约占屏高的多少。 */
    private const val SPAN_HEIGHT_RATIO = 0.26f

    /**
     * 估算一个 [spanX]×[spanY] 的组件**在桌面上实际占多大**（像素）。
     *
     * ⚠ 为什么需要它（这里踩过坑）：
     *   预览时桌面上还没有任何实例，`getAppWidgetOptions` 无从取值，
     *   于是早先的版本退化成用 `widget_min_width/height`（110dp）当预览尺寸 ——
     *   而 110dp 只是「系统据此推算占几格」的**声明值**，不是真实渲染尺寸。
     *   实测 Pixel Launcher + 1080×2400/420dpi 上，2×2 组件真实占 **451×614px**
     *   （≈172×234dp），差不多是 110dp 见方的 1.6~2.1 倍。
     *   尺寸差这么多，按 110dp 算出来的字号在预览里就显得奇大，还会把第二行顶出边界。
     *
     * 取值方式：按屏幕尺寸的比例反推 —— 451÷1080 ≈ 0.42、614÷2400 ≈ 0.256。
     * 这是经验值：换一款桌面（列数不同）会有偏差，但预览本来就只是「示意」，
     * 量级对了就不会出现「预览和桌面两种观感」的割裂。
     */
    fun estimateSizePx(context: Context, spanX: Int, spanY: Int): IntArray {
        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * SPAN_WIDTH_RATIO / SPAN_UNIT * spanX).toInt()
        val height = (dm.heightPixels * SPAN_HEIGHT_RATIO / SPAN_UNIT * spanY).toInt()
        return intArrayOf(width, height)
    }

    /**
     * 请求把 [provider] 固定到桌面。
     *
     * @return true 表示请求已经交给系统 —— **不代表用户点了同意**，用户在系统弹窗上取消是正常结果。
     *         只有请求根本发不出去（系统版本不够 / 桌面没实现这套 API）才返回 false。
     */
    fun requestPin(activity: Activity, provider: Class<out AppWidgetProvider>): Boolean {
        // requestPinAppWidget 是 API 26 起才有的；本项目 minSdk 24，
        // 在 24/25 上直接走手动指引 —— 那时系统本来也没有这套交互。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = AppWidgetManager.getInstance(activity) ?: return false
        if (!manager.isRequestPinAppWidgetSupported) return false
        return runCatching {
            manager.requestPinAppWidget(ComponentName(activity, provider), null, null)
        }.getOrDefault(false)
    }

    /**
     * 从组件 options 里取**竖屏**（手机常态）的当前尺寸，下标 0 是宽、1 是高（dp）。
     *
     * ⚠⚠ options 的取值语义（Pixel Launcher 实测，2026-09-26，density 2.625）：
     *   · `OPTION_APPWIDGET_MIN_WIDTH`  = **竖屏**下的当前宽（2×2 实测 172dp = 真实 451px）；
     *   · `OPTION_APPWIDGET_MAX_WIDTH`  = 横屏下的宽（实测 332dp）；
     *   · `OPTION_APPWIDGET_MIN_HEIGHT` = 横屏下的高（实测 137dp，**恒定小值**）；
     *   · `OPTION_APPWIDGET_MAX_HEIGHT` = **竖屏**下的当前高（实测 233dp = 真实 614px）。
     *   即竖屏尺寸 = (MIN_WIDTH, MAX_HEIGHT)，横屏 = (MAX_WIDTH, MIN_HEIGHT)。
     *   用户拉伸组件时系统会**更新这四个值**，所以按竖屏值取字号照样跟随拖拽。
     *
     * ⚠⚠ 两段踩坑史，都别再走回头路：
     *   1. **只读 MIN**：当年读的是 MIN_HEIGHT（横屏高），恒定不变 →
     *      「用户拉大组件字号纹丝不动」（3×4 下数字行高卡 57px）。于是改成了取中点。
     *   2. **取中点**：宽度中点 = (172+332)/2 = 252dp，比竖屏真实 172dp 宽 46%，
     *      字号按 252dp 反推直接把「还有 97 天」挤出省略号；高度中点则比竖屏矮 21%。
     *      中点是「两个方向各错一半」的妥协解，不是正确解。
     *
     * 另：options 里的值**本身就是 dp**（官方定义如此），绝不能再除以 density ——
     * 更早的版本除过一次，2×2 内容区被算成约 42dp，所有字号全部撞到下限，
     * 而预览走 px→dp 的正确换算、完全正常 ——「预览字大、桌面字小」割裂感的最初根因。
     */
    fun portraitBoxDp(options: Bundle): FloatArray {
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        return floatArrayOf(width.toFloat(), height.toFloat())
    }

    /** 经验比例是按「2 格」反推的，这里把 SPAN 归一化一下，便于以后加 4×2 组件。 */
    private const val SPAN_UNIT = 2
}
