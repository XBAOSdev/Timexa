package com.xbaos.shijianapp.ftt

import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.AppPrefs

/**
 * 呼吸放松：跟随圆形按钮的缩放节奏吸气 / 呼气。
 *
 * 与旧版的差异：
 *  · 结束后面板会回到「重新开始」而不是停在一个点了没反应的「结束」状态；
 *  · 过程中可以随时轻触圆圈提前结束（旧版一旦开始就只能等满 1 分钟）；
 *  · 增加剩余秒数显示；
 *  · 计时器统一收口，页面销毁时一并取消，不会留下后台计时；
 *  · 缩放动画改用 ViewPropertyAnimator，不需要处理 fillAfter 的残留状态；
 *  · 时长与是否振动改为读设置（旧版总时长硬编码为 1 分钟）。
 */
class huxifangsong : BaseActivity() {

    private lateinit var breathButton: TextView
    private lateinit var tipText: TextView
    private lateinit var countdownText: TextView

    /** 设备可能没有马达（部分平板 / 模拟器），取不到就静默降级为“无震动提示”。 */
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_huxifangsong)

        breathButton = findViewById(R.id.breath_button)
        tipText = findViewById(R.id.tip_text)
        countdownText = findViewById(R.id.breath_countdown)

        findViewById<TextView>(R.id.page_title).setText(R.string.app_breath_name)

        breathButton.setOnClickListener {
            if (running) stopEarly() else startSession()
        }
        resetIdleUi()
    }

    private var running = false
    private var totalTimer: CountDownTimer? = null
    private var phaseTimer: CountDownTimer? = null

    /** 本次会话的总时长，开始时按设置取一次，避免中途设置变化导致进度错乱。 */
    private var sessionMinutes: Int = 1

    // ==================== 会话控制 ====================

    private fun startSession() {
        running = true
        sessionMinutes = AppPrefs.breathMinutes
        tipText.text = getString(R.string.breath_running_tip)

        totalTimer = object : CountDownTimer(sessionMinutes * 60_000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1000).toInt()
                countdownText.text = getString(R.string.breath_remaining, seconds)
            }

            override fun onFinish() {
                finishSession(completed = true)
            }
        }.start()

        // 起手：从当前大小**平滑**收到最小，收完再进入第一次吸气。
        // 原来是 `scaleX = EXHALE_SCALE` 直接赋值，圆会「啪」地缩一下 ——
        // 正是用户反馈的「点击后圆瞬间变小」。交给动画后，整条呼吸曲线就没有跳变了。
        // 400ms 相对 4s 的呼吸节奏很短，既不打乱韵律，也不至于让人干等。
        // ⚠ 这 400ms 也算在总时长里（倒计时已在走）：误差不到 1%，
        //   换一条连续的曲线，值。
        // 注：动画被取消时结束回调同样会执行，而 startInhalePhase 开头有
        //   `if (!running) return` 兜着，所以「刚点开始就点停止」不会出问题。
        animateScale(EXHALE_SCALE, ENTER_DURATION_MS) { startInhalePhase() }
    }

    /** 吸气：放大 → 4 秒后转呼气 */
    private fun startInhalePhase() {
        if (!running) return
        vibrate(200)
        breathButton.text = getString(R.string.breath_inhale)
        animateScale(INHALE_SCALE)
        schedulePhase { startExhalePhase() }
    }

    /** 呼气：缩小 → 4 秒后转吸气 */
    private fun startExhalePhase() {
        if (!running) return
        vibrate(200)
        breathButton.text = getString(R.string.breath_exhale)
        animateScale(EXHALE_SCALE)
        schedulePhase { startInhalePhase() }
    }

    private fun schedulePhase(next: () -> Unit) {
        phaseTimer?.cancel()
        phaseTimer = object : CountDownTimer(PHASE_DURATION_MS, PHASE_DURATION_MS) {
            override fun onTick(millisUntilFinished: Long) = Unit
            override fun onFinish() {
                if (running) next()
            }
        }.start()
    }

    /** 用户主动提前结束。 */
    private fun stopEarly() {
        finishSession(completed = false)
    }

    private fun finishSession(completed: Boolean) {
        val wasRunning = running
        running = false
        cancelTimers()

        if (wasRunning) vibrate(if (completed) 600 else 120)
        breathButton.text = getString(R.string.breath_restart)
        tipText.text = getString(
            if (completed) R.string.breath_done_tip else R.string.breath_stopped_tip
        )
        countdownText.text = getString(R.string.breath_total, sessionMinutes)
        animateScale(1f, durationMs = 240L)
    }

    private fun resetIdleUi() {
        breathButton.scaleX = 1f
        breathButton.scaleY = 1f
        breathButton.text = getString(R.string.breath_start)
        tipText.text = getString(R.string.breath_idle_tip)
        countdownText.text = getString(R.string.breath_total, AppPrefs.breathMinutes)
    }

    /**
     * 把圆缩放到 [target]。
     *
     * [onEnd] 传 null 会**清掉**上一次挂的结束回调 —— 这是必须的：
     * ViewPropertyAnimator 是复用的，不清就会让上一条动画的回调串到这一条上。
     */
    private fun animateScale(
        target: Float,
        durationMs: Long = PHASE_DURATION_MS,
        onEnd: (() -> Unit)? = null
    ) {
        breathButton.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(durationMs)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction(onEnd)
            .start()
    }

    private fun cancelTimers() {
        totalTimer?.cancel()
        phaseTimer?.cancel()
        totalTimer = null
        phaseTimer = null
    }

    private fun vibrate(milliseconds: Long) {
        if (!AppPrefs.breathVibrate) return
        val device = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            device.vibrate(
                VibrationEffect.createOneShot(milliseconds, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            device.vibrate(milliseconds)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        cancelTimers()
        vibrator?.cancel()
    }

    companion object {
        private const val PHASE_DURATION_MS = 4_000L

        /** 开始后「收到最小」这一段过渡的时长，见 [startSession]。 */
        private const val ENTER_DURATION_MS = 400L

        private const val INHALE_SCALE = 1.0f
        private const val EXHALE_SCALE = 0.62f
    }
}
