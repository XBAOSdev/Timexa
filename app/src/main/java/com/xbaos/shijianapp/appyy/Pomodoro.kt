package com.xbaos.shijianapp.appyy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.AppDialog
import com.xbaos.shijianapp.ui.AppPrefs
import com.xbaos.shijianapp.ui.Haptics
import com.xbaos.shijianapp.ui.Notifier
import com.xbaos.shijianapp.ui.PermissionBanner
import com.xbaos.shijianapp.ui.RingProgressView
import java.util.Locale

/**
 * 番茄钟。
 *
 * 计时模型与秒表保持一致：**只记「结束时刻」，用时现算**，而不是每秒累加。
 *   · 运行中：剩余 = endRealtime - SystemClock.elapsedRealtime()
 *   · 暂停中：剩余 = remainingMs
 * 好处是切后台、被系统冻结、甚至活动被回收重建，回来后的剩余时间都是准的，
 * 不会出现「切出去五分钟回来发现一秒没走」这种常见毛病。
 *
 * 时间基准用 elapsedRealtime 而不是 currentTimeMillis：
 * 前者包含深睡时间，且不受用户手动改系统时钟影响 —— 倒计时必须用单调时钟。
 *
 * 后台策略：页面可见时完全由页面自己计时与提醒；离开页面时把「结束时刻」交给前台服务，
 * 由服务负责刷新通知并在到点时提醒一次（见 TimingService）。这样职责清晰、也不会双份提醒。
 */
class Pomodoro : BaseActivity() {

    private enum class Phase { FOCUS, SHORT_BREAK, LONG_BREAK }

    private lateinit var ringView: RingProgressView
    private lateinit var phaseView: TextView
    private lateinit var remainingView: TextView
    private lateinit var roundView: TextView
    private lateinit var hintView: TextView
    private lateinit var primaryButton: TextView
    private lateinit var resetButton: TextView
    private lateinit var skipButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { PomodoroStore(this) }

    private var phase = Phase.FOCUS
    private var running = false

    /** 阶段结束时刻（elapsedRealtime）；未运行时无意义。 */
    private var endRealtime = 0L

    /** 暂停时的剩余毫秒；未开始时等于本阶段总时长。 */
    private var remainingMs = 0L

    /** 本阶段的总时长，用于换算进度环。 */
    private var phaseTotalMs = 0L

    private var completedInSet = 0
    private var totalCompleted = 0

    /** 通知权限的申请入口。必须在 onStart 之前注册，因此放在属性初始化位置。 */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 结果不影响计时；这里只把「申请原因」横幅提前收起
            PermissionBanner.dismiss(this)
        }

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            if (endRealtime - SystemClock.elapsedRealtime() <= 0L) {
                finishPhase(countFocus = true, autoStartNext = AppPrefs.pomodoroAutoNext)
            } else {
                render()
                handler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pomodoro)

        ringView = findViewById(R.id.pomodoro_ring)
        phaseView = findViewById(R.id.pomodoro_phase)
        remainingView = findViewById(R.id.pomodoro_remaining)
        roundView = findViewById(R.id.pomodoro_round)
        hintView = findViewById(R.id.pomodoro_hint)
        primaryButton = findViewById(R.id.pomodoro_primary)
        resetButton = findViewById(R.id.pomodoro_reset)
        skipButton = findViewById(R.id.pomodoro_skip)

        findViewById<TextView>(R.id.page_title).setText(R.string.app_pomodoro_name)

        primaryButton.setOnClickListener { if (running) pause() else start() }
        resetButton.setOnClickListener { confirmReset() }
        skipButton.setOnClickListener { finishPhase(countFocus = false, autoStartNext = true) }

        restore()
        render()
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来：先按真实的剩余时间对一次账，
        // 若在离开期间已经走完，就地结算并进入下一阶段（不补算离开期间的时间）。
        if (running) {
            if (endRealtime - SystemClock.elapsedRealtime() <= 0L) {
                finishPhase(countFocus = true, autoStartNext = AppPrefs.pomodoroAutoNext)
                return
            }
            startTicker()
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        TimingRuntime.pomodoroPageVisible = true
        // 页面可见时提醒由页面负责，常驻通知反而多余，收起它
        TimingService.hide(this)
    }

    override fun onStop() {
        super.onStop()
        TimingRuntime.pomodoroPageVisible = false

        if (isChangingConfigurations) {
            // 旋转屏幕、切换深浅色都会重建页面，页面随即又可见 ——
            // 这种情况下既不该新建通知，也不该把已有通知收掉，否则会看到一次无意义的闪烁。
        } else if (running && AppPrefs.pomodoroNotification) {
            // 真正的离开：把结束时刻交给前台服务，让通知栏继续显示剩余时间并在到点提醒
            TimingService.showPomodoro(
                this,
                getString(phaseLabelRes(phase)),
                endRealtime,
                AppPrefs.pomodoroVibrate
            )
        } else {
            TimingService.hide(this)
        }
        persist()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
    }

    // ==================== 状态恢复与保存 ====================

    private fun restore() {
        val state = store.load()
        phase = when (state.phase) {
            PomodoroState.PHASE_SHORT -> Phase.SHORT_BREAK
            PomodoroState.PHASE_LONG -> Phase.LONG_BREAK
            else -> Phase.FOCUS
        }
        completedInSet = state.completedInSet
        totalCompleted = state.totalCompleted
        phaseTotalMs = state.phaseTotalMs.takeIf { it > 0L } ?: durationOf(phase)

        running = state.running
        if (running && state.endWallClock > 0L) {
            // 墙上时间换算回单调时间：两者的差值在本次开机期间是常量。
            val left = state.endWallClock - System.currentTimeMillis()
            if (left > 0L) {
                endRealtime = SystemClock.elapsedRealtime() + left
                remainingMs = left
            } else {
                // 离开期间已经走完，onResume 里会立刻结算
                endRealtime = SystemClock.elapsedRealtime()
                remainingMs = 0L
            }
        } else {
            running = false
            remainingMs = state.remainingMs.takeIf { it > 0L } ?: phaseTotalMs
        }
    }

    private fun persist() {
        store.save(
            PomodoroState(
                phase = when (phase) {
                    Phase.FOCUS -> PomodoroState.PHASE_FOCUS
                    Phase.SHORT_BREAK -> PomodoroState.PHASE_SHORT
                    Phase.LONG_BREAK -> PomodoroState.PHASE_LONG
                },
                running = running,
                endWallClock = if (running) {
                    System.currentTimeMillis() + (endRealtime - SystemClock.elapsedRealtime())
                } else {
                    0L
                },
                remainingMs = remainingMs,
                phaseTotalMs = phaseTotalMs,
                completedInSet = completedInSet,
                totalCompleted = totalCompleted
            )
        )
    }

    // ==================== 计时控制 ====================

    private fun start() {
        if (phaseTotalMs <= 0L) {
            phaseTotalMs = durationOf(phase)
            remainingMs = phaseTotalMs
        }
        if (remainingMs <= 0L) remainingMs = phaseTotalMs
        running = true
        endRealtime = SystemClock.elapsedRealtime() + remainingMs
        requestNotificationPermission()
        hintView.setText(if (phase == Phase.FOCUS) R.string.pomodoro_focus_tip else R.string.pomodoro_break_tip)
        startTicker()
        persist()
        render()
    }

    private fun pause() {
        if (!running) return
        remainingMs = (endRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        running = false
        handler.removeCallbacks(ticker)
        persist()
        render()
    }

    /**
     * 结束当前阶段并进入下一阶段。
     *
     * @param countFocus 「跳过」时为 false —— 没专注完的番茄不应该计入成绩。
     */
    private fun finishPhase(countFocus: Boolean, autoStartNext: Boolean) {
        handler.removeCallbacks(ticker)
        val wasFocus = phase == Phase.FOCUS
        running = false
        remainingMs = 0L

        if (wasFocus && countFocus) {
            completedInSet++
            totalCompleted++
        }
        if (AppPrefs.pomodoroVibrate) {
            Haptics.alert(this)
        }

        val finishedLabel = getString(
            if (wasFocus) R.string.pomodoro_focus_done else R.string.pomodoro_break_done
        )
        phase = nextPhase(wasFocus)
        phaseTotalMs = durationOf(phase)
        remainingMs = phaseTotalMs
        endRealtime = 0L

        if (autoStartNext) {
            running = true
            endRealtime = SystemClock.elapsedRealtime() + remainingMs
            startTicker()
            hintView.text = finishedLabel
        } else {
            hintView.text = getString(
                R.string.pomodoro_next_waiting,
                getString(phaseLabelRes(phase))
            )
        }
        persist()
        render()
    }

    private fun confirmReset() {
        AppDialog.confirm(
            context = this,
            title = getString(R.string.pomodoro_reset),
            message = getString(R.string.pomodoro_reset_confirm),
            positive = getString(R.string.pomodoro_reset),
            negative = getString(R.string.dialog_cancel),
            positiveIsDanger = true,
            onPositive = { reset() }
        )
    }

    private fun reset() {
        handler.removeCallbacks(ticker)
        running = false
        endRealtime = 0L
        phase = Phase.FOCUS
        completedInSet = 0
        totalCompleted = 0
        phaseTotalMs = durationOf(Phase.FOCUS)
        remainingMs = phaseTotalMs
        store.clear()
        TimingService.hide(this)
        hintView.setText(R.string.pomodoro_idle_tip)
        render()
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_INTERVAL_MS)
    }

    private fun requestNotificationPermission() {
        if (!AppPrefs.pomodoroNotification || Notifier.canPost(this)) return
        val permission = Notifier.permissionToRequest() ?: return
        // 先说明用途再弹系统框
        // persistent：横幅要留到用户在系统框里做出选择为止，不自动消失。
        // 收起仍由上面的 notificationPermission 回调负责（同意/拒绝都会走到）。
        PermissionBanner.show(this, R.string.perm_notification_banner, persistent = true)
        notificationPermission.launch(permission)
    }

    // ==================== 渲染 ====================

    private fun render() {
        val left = remainingMillis()
        remainingView.text = formatClock(left)

        ringView.progress =
            if (phaseTotalMs > 0L) 1f - left.toFloat() / phaseTotalMs.toFloat() else 0f
        ringView.ringColor = ContextCompat.getColor(this, phaseColorRes(phase))

        phaseView.setText(phaseLabelRes(phase))
        phaseView.setTextColor(ContextCompat.getColor(this, phaseColorRes(phase)))

        roundView.text = getString(
            R.string.pomodoro_round_info,
            completedInSet,
            AppPrefs.pomodoroRoundsPerSet,
            totalCompleted
        )

        primaryButton.setText(
            when {
                running -> R.string.pomodoro_pause
                left < phaseTotalMs -> R.string.pomodoro_resume
                else -> R.string.pomodoro_start
            }
        )
        resetButton.alpha = if (running || left < phaseTotalMs || totalCompleted > 0) 1f else DISABLED_ALPHA
    }

    private fun remainingMillis(): Long =
        if (running) (endRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L) else remainingMs

    private fun durationOf(target: Phase): Long = when (target) {
        Phase.FOCUS -> AppPrefs.pomodoroFocusMinutes
        Phase.SHORT_BREAK -> AppPrefs.pomodoroShortBreakMinutes
        Phase.LONG_BREAK -> AppPrefs.pomodoroLongBreakMinutes
    }.coerceAtLeast(1) * 60_000L

    /** 刚结束的是专注，则按本轮进度决定进长休息还是短休息；刚结束的是休息，则回到专注。 */
    private fun nextPhase(justFinishedFocus: Boolean): Phase {
        if (!justFinishedFocus) return Phase.FOCUS
        val rounds = AppPrefs.pomodoroRoundsPerSet.coerceAtLeast(1)
        return if (completedInSet > 0 && completedInSet % rounds == 0) {
            Phase.LONG_BREAK
        } else {
            Phase.SHORT_BREAK
        }
    }

    @StringRes
    private fun phaseLabelRes(target: Phase): Int = when (target) {
        Phase.FOCUS -> R.string.pomodoro_phase_focus
        Phase.SHORT_BREAK -> R.string.pomodoro_phase_short_break
        Phase.LONG_BREAK -> R.string.pomodoro_phase_long_break
    }

    /** 专注用强调色，短休息用暖色，长休息用深一档的强调色：三者在两种主题下都能区分。 */
    @ColorRes
    private fun phaseColorRes(target: Phase): Int = when (target) {
        Phase.FOCUS -> R.color.accent
        Phase.SHORT_BREAK -> R.color.warning
        Phase.LONG_BREAK -> R.color.accent_deep
    }

    /** mm:ss；超过一小时自动补小时位。固定 Locale.US 避免个别地区把冒号写成别的符号。 */
    private fun formatClock(millis: Long): String {
        val totalSeconds = (millis + 999L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private companion object {
        const val TICK_INTERVAL_MS = 200L
        const val DISABLED_ALPHA = 0.45f
    }
}
