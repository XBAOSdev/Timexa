package com.xbaos.shijianapp.appyy

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.appsys.AppClock
import com.xbaos.shijianapp.ui.AppPrefs
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时钟立台：横屏、全屏、常亮的大字时钟，适合把手机立在桌上当时钟用。
 *
 * 与旧版的差异：
 *  · 隐藏系统栏改用 WindowInsetsController（旧版用的 systemUiVisibility 标志
 *    在 Android 15/16 上已被忽略，targetSdk 36 下会失效，状态栏根本藏不住）；
 *  · 刷新时机对齐到整分钟（或整秒），避免长时间运行后与真实时间错位；
 *  · 点击整个屏幕退出（旧版只有点到时间文字才退出，命中区域很小）；
 *  · 新增防烧屏微移：本页是长期点亮的静态画面，静止像素会在 OLED 上留残影，
 *    因此默认每隔一分钟把整块内容轻微平移一次；
 *  · 显示项（秒 / 24 小时制 / 常亮 / 防烧屏）全部读自设置。
 */
class clocklitai : BaseActivity() {

    /** 全屏沉浸：不需要为系统栏预留内边距。 */
    override val fitsSystemBars: Boolean get() = false

    /**
     * 纯黑背景 → 系统栏图标必须用浅色。
     * 临时划出系统栏（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）时，
     * 若沿用默认的深色图标，在纯黑底上会完全看不见。
     */
    override val lightSystemBarIcons: Boolean get() = false

    private lateinit var timeTextView: TextView
    private lateinit var dateTextView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timeFormat: SimpleDateFormat
    private lateinit var dateFormat: DateFormat

    /**
     * 12 小时制专用：单独取「上午 / 下午」，再拼到日期行末尾。
     * 不能写进时间格式串（`hh:mm a`）—— 全屏大字下整串会被撑到换行。
     */
    private lateinit var meridiemFormat: SimpleDateFormat

    /** 当前是否为 24 小时制（决定日期行要不要带「上午 / 下午」）。 */
    private var use24Hour = false

    /** 防烧屏偏移的步进序号。 */
    private var shiftIndex = 0

    private val ticker = object : Runnable {
        override fun run() {
            updateTimeAndDate()
            scheduleNextTick()
        }
    }

    private val burnInTicker = object : Runnable {
        override fun run() {
            applyAntiBurnInShift()
            handler.postDelayed(this, ANTI_BURN_IN_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_clocklitai)

        timeTextView = findViewById(R.id.timeTextView)
        dateTextView = findViewById(R.id.dateTextView)

        // 整屏可点即退出
        findViewById<View>(R.id.main).setOnClickListener { finish() }

        applyDisplayPrefs()
        updateTimeAndDate()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        applyDisplayPrefs()
        applyKeepScreenOn()
        updateTimeAndDate()
        scheduleNextTick()
        handler.removeCallbacks(burnInTicker)
        handler.postDelayed(burnInTicker, ANTI_BURN_IN_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(burnInTicker)
    }

    // ==================== 显示设置 ====================

    private fun applyDisplayPrefs() {
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        val showSeconds = AppPrefs.clockShowSeconds
        use24Hour = AppPrefs.clock24Hour
        val pattern = when {
            use24Hour && showSeconds -> "HH:mm:ss"
            use24Hour -> "HH:mm"
            showSeconds -> "hh:mm:ss"
            else -> "hh:mm"
        }
        timeFormat = AppClock.formatter(pattern, locale)
        // 「上午 / 下午」不进时间格式串：全屏大字下整串会超出一行宽度而被折行。
        // 单独取出后拼到日期行末尾（与日期同字号），见 [updateTimeAndDate]。
        meridiemFormat = AppClock.formatter("a", locale)
        dateFormat = AppClock.dateFormat(DateFormat.FULL, locale)

        // 多出「:ss」两个字符，字号要降一档才不会在横屏下溢出
        timeTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (showSeconds) 92f else 112f)
    }

    private fun applyKeepScreenOn() {
        if (AppPrefs.clockKeepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ==================== 刷新 ====================

    /** 下一次刷新安排在本秒（或本分钟）结束的那一刻，保证显示值与真实时间始终一致。 */
    private fun scheduleNextTick() {
        handler.removeCallbacks(ticker)
        val now = System.currentTimeMillis()
        val interval = if (AppPrefs.clockShowSeconds) MILLIS_PER_SECOND else MILLIS_PER_MINUTE
        handler.postDelayed(ticker, interval - (now % interval))
    }

    private fun updateTimeAndDate() {
        val now = Date()
        timeTextView.text = timeFormat.format(now)
        // 日期行 = 日期（含星期）+ 12 小时制时的「上午 / 下午」，空格分隔、同一字号。
        // 时间那行用的是等宽字体且只含数字与冒号，字形宽度固定；
        // 把标记挪到这里之后，副标题的宽度变化也不会再反过来影响主干。
        val parts = mutableListOf(dateFormat.format(now))
        if (!use24Hour) parts += meridiemFormat.format(now)
        dateTextView.text = parts.joinToString(" ")
    }

    /**
     * 防烧屏：把时间与日期所在的那一块整体平移几个像素。
     * 幅度只有几 dp，正常观看几乎察觉不到，但足以让像素点换位置、避免长期点亮同一片像素。
     */
    private fun applyAntiBurnInShift() {
        val content = timeTextView.parent as? View ?: return
        if (!AppPrefs.clockAntiBurnIn) {
            content.translationX = 0f
            content.translationY = 0f
            return
        }
        val step = SHIFT_STEPS[shiftIndex % SHIFT_STEPS.size]
        shiftIndex++
        val density = resources.displayMetrics.density
        content.animate()
            .translationX(step[0] * density)
            .translationY(step[1] * density)
            .setDuration(SHIFT_DURATION_MS)
            .start()
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val ANTI_BURN_IN_INTERVAL_MS = 60_000L
        private const val SHIFT_DURATION_MS = 900L

        /** 平移轨迹（单位 dp）：在中心点周围小范围循环。 */
        private val SHIFT_STEPS = arrayOf(
            floatArrayOf(0f, 0f),
            floatArrayOf(5f, -4f),
            floatArrayOf(-5f, 4f),
            floatArrayOf(4f, 5f),
            floatArrayOf(-4f, -5f)
        )
    }
}
