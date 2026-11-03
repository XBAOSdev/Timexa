package com.xbaos.shijianapp.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import androidx.core.content.ContextCompat

/**
 * 振动 / 触感反馈。
 *
 * ⚠⚠ **两条路线在这里并存，但绝不要合并**，它们的目的、API、权限、是否跟随系统开关全都不同：
 *
 * | | [alert]（提醒） | [tap]（手感） |
 * |---|---|---|
 * | 目的 | 用户**没在看屏幕**也必须感知到 | 用户正在操作，回一句「我收到了」 |
 * | 场合 | 番茄钟 / 呼吸放松的阶段结束 | 点击、切换、确认 |
 * | API | `Vibrator.vibrate(VibrationEffect)` | `View.performHapticFeedback()` |
 * | 权限 | **需要 `VIBRATE`** | **不需要** |
 * | 受系统「触感反馈」开关约束？ | **否**（只受「振动」总开关约束） | **是**（强制跟随） |
 * | 强度 | 自己编波形 | 只选语义，由系统决定 |
 * | 该被应用内开关关掉吗 | **不该**（关了会漏提醒） | 该（用户可能只是不想上手就振） |
 *
 * 所以：[alert] 继续走 `Vibrator`（`VIBRATE` 权限**不要删**），[tap] 走 `View` 那套。
 */
object Haptics {

    /** 一次短促的「两段式」提示：醒神但不刺耳，用于阶段结束。 */
    private val ALERT_PATTERN = longArrayOf(0L, 320L, 180L, 320L)

    fun alert(context: Context) {
        vibrate(context, ALERT_PATTERN)
    }

    fun vibrate(context: Context, pattern: LongArray) {
        val vibrator = ContextCompat.getSystemService(context, Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    /**
     * 一次「点击触感反馈」。
     *
     * 用 `View.performHapticFeedback` 是**唯一**能跨厂商一致的做法，原因有三条（均出自官方文档）：
     *  1. **不需要 `VIBRATE` 权限**（「using HapticFeedbackConstants with a View doesn't require
     *     the VIBRATE permission」）；
     *  2. **不需要探测设备能力** —— 常量自带回退，好马达上细腻、差马达上就是一记短震，两者都是正确结果；
     *  3. **天然跟随系统总开关**（「The performHapticFeedback method also honors the system setting
     *     `HAPTIC_FEEDBACK_ENABLED`」）。而且 API 33 起
     *     `HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING` 对普通应用失效 ——
     *     平台**禁止**第三方绕过用户的触感开关，我们想做反都做不到。
     *
     * 因此这里：
     *  - **不做**机型判断、**不做**强度调整（`performHapticFeedback` 根本没有幅度参数）；
     *  - 只在最外层判一次**应用自己的**开关 [AppPrefs.hapticFeedback]：
     *    关掉 = 本应用不发触感（用户不想振，但不想关掉整个系统）。
     *    至于系统开关，交给系统去尊重，我们无从干预、也不需要干预。
     *
     * ⚠ 只能在「view 已 attach 到窗口」时调用才有意义 —— 触摸回调里天然满足。
     *
     * @param kind 语义常量，取自 [FeedbackKind]。
     */
    fun tap(view: View, kind: Int = FeedbackKind.LIGHT) {
        // 「这一次不发」一律由调用方显式指定，这里不猜。
        // 主要场合：开关的语义触觉已由 OnCheckedChangeListener 发过一次，
        // 按压缩放那边挂着的那两条 listener 若照常发声就会振两下。见 FeedbackKind.NONE。
        if (kind == FeedbackKind.NONE) return
        if (!AppPrefs.hapticFeedback) return
        view.performHapticFeedback(kind)
    }
}
