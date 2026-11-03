package com.xbaos.shijianapp.ui

import android.os.Build
import android.view.HapticFeedbackConstants

/**
 * 触感反馈的「语义 → `HapticFeedbackConstants` 常量」映射。
 *
 * 为什么单独一个文件：
 *  `HapticFeedbackConstants` 的常量是按 API 级别逐个加入的 —— `CLOCK_TICK`(21)、
 *  `CONFIRM`/`REJECT`(30)、`TOGGLE_*`/`SEGMENT_TICK`(34)，而本项目 **minSdk 24**，
 *  凡 API > 24 的常量都必须做版本守卫。把守卫集中在这里，调用方只写「这是什么动作」，
 *  不用每次去回忆某个常量的起始版本。
 *
 * ⚠ 为什么不用 androidx.core 的 `HapticFeedbackConstantsCompat`：
 *   那个类是从 **androidx.core 1.11.0** 才加入的，本项目锁在 1.10.1（且契约是「不加依赖」）。
 *   它内部做的事就是下面这几个三元表达式，自己写更透明、也不用动版本号。
 *
 * ⚠ **低版本上「传一个它不认识的常量」会怎样，官方没有承诺** —— 文档只说常量「会在必要时
 *   采用回退行为」，但没承诺一定会退化成某种有用效果，实际可能是静默忽略。
 *   所以这里一律**显式降级到 [LIGHT]**，不依赖未定义行为。
 *
 * 设计原则（来自 Android 官方 haptics 文档）：常量描述的是**功能语义**（「用户确认了」），
 * 而不是波形；具体振成什么样由设备马达与系统设置决定。所以这里只按语义选、不按「想要多强」选 ——
 * 也正因如此，本应用**不提供强度调节**，强度完全交给系统（系统关了我们也振不了，见 `Haptics.tap`）。
 */
object FeedbackKind {

    /**
     * 「这条路径**不要**发触感」。由调用方显式指定 —— 发不发不该让 [Haptics.tap] 去猜。
     *
     * 两个用途：
     *  1. **避免重复**：开关的语义触觉统一由 `OnCheckedChangeListener` 发那一次，
     *     而按压缩放那边 `row` 与 `switch` 都挂了 listener，它们若照常发声就成了「振两下」。
     *  2. **避免自相矛盾的时机**：把触感反馈开关拨到「关」的那一次不该响 ——
     *     不过这一条现在由 `Settings.addSwitch` 里「先写偏好、再发触觉」的顺序天然解决了
     *     （偏好已关，[Haptics.tap] 自己就会跳过），不再需要在此特判。
     *
     * 取值 0 恰好等于 API 30 的 `NO_HAPTICS`，但**不依赖**这一点：
     * 在更低的 API 上「传一个未知常量会怎样」官方没有承诺，所以 [Haptics.tap] 见到它直接
     * return，把决定权留在自己手里，而不是交给平台去猜。
     */
    const val NONE = 0

    /**
     * 普通点击：最通用的轻触。
     * `VIRTUAL_KEY` 是 API 3 的常量，任何设备上都会有反馈，是唯一可以无条件使用的选择。
     */
    const val LIGHT = HapticFeedbackConstants.VIRTUAL_KEY

    /**
     * 刻度：秒表「计次」这类离散动作。
     * `CLOCK_TICK` 是 API 21，低于 minSdk 24，可直接使用。
     */
    const val TICK = HapticFeedbackConstants.CLOCK_TICK

    /**
     * 分量重的动作：危险操作（删除、清空、重置）的确认。
     * `LONG_PRESS` 是 API 3，可直接使用。
     * ⚠ 只用于**我们主动发**的场合；列表行的真实长按有系统默认触感，别在这里再发一次（会叠两记）。
     */
    const val HEAVY = HapticFeedbackConstants.LONG_PRESS

    /** 开关打到「开」。API 34 起才有专属效果，其余降级为 [LIGHT]。 */
    fun toggleOn(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.TOGGLE_ON
        } else {
            LIGHT
        }

    /** 开关打到「关」。 */
    fun toggleOff(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.TOGGLE_OFF
        } else {
            LIGHT
        }

    /**
     * 分段控件换档。API 34 有 `SEGMENT_TICK`（专为「在一串离散选项间切换」设计，自带高频节流），
     * 低版本退到语义同族的 `CLOCK_TICK`。
     */
    fun segmentTick(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            HapticFeedbackConstants.SEGMENT_TICK

        else -> TICK
    }

    /** 确认 / 成功完成（弹窗「确定」、计时开始）。API 30 起。 */
    fun confirm(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            LIGHT
        }

    /**
     * 拒绝 / 失败（如日期解析失败）。
     * 官方对这个常量的定义就是「rejection or **failure** of a user interaction」，用得名正言顺。
     * ⚠ 弹窗的「取消」**不该**用它 —— 取消是中性选择，不是失败，那种场合用 [LIGHT]。
     */
    fun reject(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            LIGHT
        }
}
