package com.xbaos.shijianapp.appyy

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.Haptics
import com.xbaos.shijianapp.ui.Notifier
import java.util.Locale

/**
 * 计时前台服务。
 *
 * 为什么需要它：
 *  「复合秒表」和「番茄钟」都采用了「只记时间戳、用时现算」的模型，本身不需要后台进程；
 *  但用户希望「离开 App 也能一眼看到还在计时」以及「番茄钟到点能收到提醒」，
 *  这两件事只能靠常驻通知，而常驻通知在 Android 8 以后必须由前台服务承载。
 *
 * 职责刻意做得很窄 —— 它只负责「显示一条通知」和「数完倒计时后提醒一次」，
 * 不持有任何业务状态，所有状态都由页面通过 Intent 推给它：
 *   · [MODE_STOPWATCH]：通知文案由秒表页在状态变化时推送，服务不自行刷新（省电，且通知不会跳秒）；
 *   · [MODE_POMODORO]：服务每秒把 endAt 换算成剩余时间刷新通知，数到 0 时提醒一次。
 *
 * 页面可见时由页面自己负责提醒，服务只静默退出，避免同一次结束弹两条通知。
 */
class TimingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var mode: String = MODE_STOPWATCH

    /** 通知标题（秒表模式为「复合秒表」，番茄钟模式为阶段名）。 */
    private var title: String = ""

    /** 固定文案（秒表模式）；番茄钟模式的正文由剩余时间实时拼出。 */
    private var text: String = ""

    /** 倒计时终点（elapsedRealtime 毫秒）；0 表示不倒数（秒表模式）。 */
    private var endAt: Long = 0L

    /** 点击通知要打开的页面。 */
    private var target: String = TARGET_STOPWATCH

    /** 倒计时结束是否振动。由页面在启动倒计时时一并传进来，服务不自行读偏好。 */
    private var vibrateOnFinish: Boolean = true

    private var finished: Boolean = false

    private val ticker = object : Runnable {
        override fun run() {
            if (endAt <= 0L || finished) return
            val left = endAt - SystemClock.elapsedRealtime()
            if (left > 0L) {
                publish()
                handler.postDelayed(this, 1000L)
            } else {
                finished = true
                onCountdownFinished()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_STOPWATCH
        title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
            .ifEmpty { getString(R.string.app_name) }
        text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        endAt = intent?.getLongExtra(EXTRA_END_AT, 0L) ?: 0L
        target = intent?.getStringExtra(EXTRA_TARGET) ?: TARGET_STOPWATCH
        vibrateOnFinish = intent?.getBooleanExtra(EXTRA_VIBRATE, true) ?: true
        finished = false

        Notifier.ensureChannels(this)
        // 前台服务必须在 startForegroundService 后 5 秒内 startForeground，
        // 否则系统会直接抛异常终止服务。
        startForeground(Notifier.ID_TIMING, buildOngoing())

        stopTicker()
        if (mode == MODE_POMODORO && endAt > 0L) {
            handler.postDelayed(ticker, 1000L)
        }

        // 不被系统自动重启：计时状态由页面自行持久化，服务只是展示层，重建反而会出现幽灵通知。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTicker()
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }

    /** 刷新常驻通知。番茄钟模式会在正文里带上剩余时间。 */
    private fun publish() {
        if (!Notifier.canPost(this)) return
        val contentText = if (mode == MODE_POMODORO && endAt > 0L) {
            val left = (endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            getString(R.string.notification_pomodoro_left, title, formatClock(left))
        } else {
            text
        }
        val manager = Notifier.notificationManager(this) ?: return
        manager.notify(Notifier.ID_TIMING, buildOngoing(contentText))
    }

    /** 倒计时归零：页面在前台时交给页面提醒，否则由服务提醒一次后退出。 */
    private fun onCountdownFinished() {
        // 页面可能刚好在这一刻开启了下一阶段（endAt 已被刷新到未来），
        // 此时不能停服务，否则下一条常驻通知会被顺手一起收掉。
        if (endAt - SystemClock.elapsedRealtime() > 0L) {
            finished = false
            handler.postDelayed(ticker, 1000L)
            return
        }

        val pageVisible = TimingRuntime.pomodoroPageVisible
        if (!pageVisible) {
            alertPhaseFinished()
        }
        stopSelf()
    }

    private fun alertPhaseFinished() {
        if (!Notifier.canPost(this)) return
        val manager = Notifier.notificationManager(this) ?: return
        val notification = NotificationCompat.Builder(this, Notifier.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_hourglass)
            .setContentTitle(getString(R.string.app_pomodoro_name))
            .setContentText(getString(R.string.notification_pomodoro_finished, title))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(getString(R.string.notification_pomodoro_finished, title))
            )
            .setAutoCancel(true)
            .setContentIntent(openPageIntent())
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(Notifier.ID_ALERT, notification) }

        if (vibrateOnFinish) {
            Haptics.alert(this)
        }
    }

    private fun buildOngoing(contentText: String = text): Notification =
        NotificationCompat.Builder(this, Notifier.CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openPageIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun openPageIntent(): PendingIntent {
        val clazz = if (target == TARGET_POMODORO) {
            com.xbaos.shijianapp.appyy.Pomodoro::class.java
        } else {
            com.xbaos.shijianapp.appyy.miaobiao::class.java
        }
        val intent = Intent(this, clazz).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 倒计时用的 mm:ss（超过一小时自动补上小时位）。 */
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

    companion object {

        const val MODE_STOPWATCH = "stopwatch"
        const val MODE_POMODORO = "pomodoro"

        private const val TARGET_STOPWATCH = "stopwatch"
        private const val TARGET_POMODORO = "pomodoro"

        private const val ACTION_SHOW = "com.xbaos.shijianapp.action.SHOW_TIMING"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_END_AT = "end_at"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_VIBRATE = "vibrate"

        /** 秒表：显示一条常驻通知，文案由调用方给定，服务不自行刷新。 */
        fun showStopwatch(context: Context, title: String, text: String) {
            send(
                context,
                Intent(context, TimingService::class.java)
                    .setAction(ACTION_SHOW)
                    .putExtra(EXTRA_MODE, MODE_STOPWATCH)
                    .putExtra(EXTRA_TARGET, TARGET_STOPWATCH)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TEXT, text)
            )
        }

        /**
         * 番茄钟：显示常驻通知并倒数。
         * @param endAt 阶段结束时刻，取 `SystemClock.elapsedRealtime()` 的毫秒值。
         */
        fun showPomodoro(context: Context, title: String, endAt: Long, vibrate: Boolean) {
            send(
                context,
                Intent(context, TimingService::class.java)
                    .setAction(ACTION_SHOW)
                    .putExtra(EXTRA_MODE, MODE_POMODORO)
                    .putExtra(EXTRA_TARGET, TARGET_POMODORO)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_END_AT, endAt)
                    .putExtra(EXTRA_VIBRATE, vibrate)
            )
        }

        /**
         * 收起常驻通知（暂停、重置、离开时调用）。
         *
         * 这里用 stopService 而不是发一个「隐藏」指令：
         * 前台服务的通知随服务销毁自动消失，而如果改用 startForegroundService 发指令，
         * 就必须在 5 秒内调用 startForeground，否则系统会直接抛异常把进程干掉。
         */
        fun hide(context: Context) {
            runCatching { context.stopService(Intent(context, TimingService::class.java)) }
        }

        /**
         * 统一入口：前台服务只能「启动」不能「从后台启动」，
         * 因此这里统一用 startForegroundService；若系统拒绝（例如在后台被调用），
         * 捕获异常后静默降级为无通知运行 —— 计时本身不依赖它，不能因此崩溃。
         */
        private fun send(context: Context, intent: Intent) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}

/**
 * 服务与页面之间共享的一点点可变状态。
 *
 * 只放「无法通过 Intent 传递」的瞬时信息，不存业务数据。
 * 之所以需要它：番茄钟数到 0 时，服务要判断「页面是不是已经在提醒了」，
 * 否则同一次结束会弹两条通知。
 */
object TimingRuntime {

    /** 番茄钟页面当前是否可见。 */
    @Volatile
    var pomodoroPageVisible: Boolean = false
}
