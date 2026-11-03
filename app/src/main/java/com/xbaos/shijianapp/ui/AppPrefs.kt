package com.xbaos.shijianapp.ui

import android.content.Context
import android.content.SharedPreferences
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 全应用偏好设置（SharedPreferences）。
 *
 * 刻意与「用户数据」分开存放：
 *  - 用户数据（秒表计时记录）仍在 `files/sys/stopwatches.csv`，格式与位置不变；
 *  - 设置项放在 SharedPreferences，清空设置不会碰到任何计时数据。
 *
 * 使用前必须由 Application 调用一次 [init]。
 */
object AppPrefs {

    private const val FILE_NAME = "settings"

    // ==================== 深色模式 ====================
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    // ==================== 语言 ====================
    /** 跟随系统。 */
    const val LANG_SYSTEM = ""
    const val LANG_ZH_CN = "zh-CN"
    const val LANG_ZH_TW = "zh-TW"
    const val LANG_EN = "en"

    /** 呼吸放松可选的时长（分钟）。 */
    val BREATH_DURATIONS = intArrayOf(1, 2, 3, 5)

    /** 番茄钟可选的时长（分钟），专注与两种休息共用同一组候选值。 */
    val POMODORO_MINUTES = intArrayOf(5, 10, 15, 20, 25, 30, 45, 60)

    /** 每轮包含的番茄个数候选值。 */
    val POMODORO_ROUNDS = intArrayOf(2, 3, 4, 6, 8)

    private const val KEY_THEME = "theme_mode"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_HOME_24H = "home_24h"
    private const val KEY_HOME_SECONDS = "home_seconds"
    private const val KEY_HOME_DATE = "home_date"
    private const val KEY_HOME_ANNIVERSARY = "home_anniversary"
    private const val KEY_HOME_SHOW_FAVORITES = "home_show_favorites"
    private const val KEY_HOME_FAVORITES = "home_favorites"
    private const val KEY_SW_TENTHS = "stopwatch_tenths"
    private const val KEY_SW_NOTIFICATION = "stopwatch_notification"
    private const val KEY_SW_AUTO_START = "stopwatch_auto_start"
    private const val KEY_CLOCK_ANTI_BURN = "clock_anti_burn"
    private const val KEY_CLOCK_SECONDS = "clock_seconds"
    private const val KEY_CLOCK_KEEP_ON = "clock_keep_screen_on"
    private const val KEY_CLOCK_24H = "clock_24h"
    private const val KEY_BREATH_VIBRATE = "breath_vibrate"
    private const val KEY_BREATH_MINUTES = "breath_minutes"
    private const val KEY_POMO_FOCUS = "pomodoro_focus_minutes"
    private const val KEY_POMO_SHORT = "pomodoro_short_break_minutes"
    private const val KEY_POMO_LONG = "pomodoro_long_break_minutes"
    private const val KEY_POMO_ROUNDS = "pomodoro_rounds_per_set"
    private const val KEY_POMO_AUTO_NEXT = "pomodoro_auto_next"
    private const val KEY_POMO_VIBRATE = "pomodoro_vibrate"
    private const val KEY_POMO_NOTIFICATION = "pomodoro_notification"
    private const val KEY_TIME_ZONE = "time_zone_id"
    private const val KEY_TIME_ZONE_NOTICE = "time_zone_notice_dismissed_for"

    /** 「太阳情况」最近一次成功定位的缓存。NaN = 从未定位成功过。 */
    private const val KEY_SUN_LAT = "sun_lat"
    private const val KEY_SUN_LON = "sun_lon"

    /** 应用内触感反馈开关。默认开；关掉只影响本应用，系统的触感总开关另有其事。 */
    private const val KEY_HAPTIC = "haptic_feedback"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    private fun edit() = prefs.edit()

    // ==================== 外观 ====================

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME, THEME_SYSTEM)
        set(value) = edit().putInt(KEY_THEME, value).apply()

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANG_SYSTEM).orEmpty()
        set(value) = edit().putString(KEY_LANGUAGE, value).apply()

    // ==================== 太阳情况 ====================

    /**
     * 最近一次**成功**定位的坐标。给桌面快捷方式兜底用。
     *
     * 快捷方式被点击时没有任何机会插入「先定位再打开」的异步步骤，只能静态带参；
     * 有这份缓存时快捷方式就能直接落到「上次的位置」，没有时才回退到页面内的手动输入。
     * 每次应用内成功定位都会覆盖更新，所以它始终是最近一次的值。
     * 不是「个性化设置」，不参与导出/导入。
     */
    var sunFixLat: Double
        get() = prefs.getFloat(KEY_SUN_LAT, Float.NaN).toDouble()
        set(value) = edit().putFloat(KEY_SUN_LAT, value.toFloat()).apply()

    var sunFixLon: Double
        get() = prefs.getFloat(KEY_SUN_LON, Float.NaN).toDouble()
        set(value) = edit().putFloat(KEY_SUN_LON, value.toFloat()).apply()

    fun hasSunFix(): Boolean = !sunFixLat.isNaN() && !sunFixLon.isNaN()

    // ==================== 触感反馈 ====================

    /**
     * 点击等操作的触感反馈（手机振动）。默认开。
     *
     * 关掉 = **本应用不再主动发触感**，用户不想振但又不想关掉整个系统的振。
     *
     * ⚠ 这个开关**管不到**系统的触感总开关（`HAPTIC_FEEDBACK_ENABLED`）——
     * 那个由系统强制生效，`performHapticFeedback` 自己会尊重它，我们无从干预（API 33 起
     * 连 `FLAG_IGNORE_GLOBAL_SETTING` 都对普通应用失效了）。设置页会在检测到系统关闭时给出提示。
     *
     * ⚠ 也**不管** [Haptics.alert] 那条「提醒」路线（番茄钟 / 呼吸放松的阶段结束振动）——
     * 那个关掉会漏提醒，走的是独立的 `breathVibrate` / `pomodoroVibrate`。
     */
    var hapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC, true)
        set(value) = edit().putBoolean(KEY_HAPTIC, value).apply()

    // ==================== 首页 ====================

    /** 24 小时制；关闭则用 12 小时制（上下午）。 */
    var home24Hour: Boolean
        get() = prefs.getBoolean(KEY_HOME_24H, true)
        set(value) = edit().putBoolean(KEY_HOME_24H, value).apply()

    var homeShowSeconds: Boolean
        get() = prefs.getBoolean(KEY_HOME_SECONDS, true)
        set(value) = edit().putBoolean(KEY_HOME_SECONDS, value).apply()

    var homeShowDate: Boolean
        get() = prefs.getBoolean(KEY_HOME_DATE, true)
        set(value) = edit().putBoolean(KEY_HOME_DATE, value).apply()

    /** 在首页显示纪念日倒数卡片。 */
    var homeShowAnniversary: Boolean
        get() = prefs.getBoolean(KEY_HOME_ANNIVERSARY, true)
        set(value) = edit().putBoolean(KEY_HOME_ANNIVERSARY, value).apply()

    /**
     * 在首页显示「常用应用」卡片。默认开。
     *
     * 用户把常用应用**全部移除**时会问一句「是否一并移除这张卡片」，
     * 确认后就是把这个开关关掉 —— 之后可以在 设置 → 首页 里重新打开。
     */
    var homeShowFavorites: Boolean
        get() = prefs.getBoolean(KEY_HOME_SHOW_FAVORITES, true)
        set(value) = edit().putBoolean(KEY_HOME_SHOW_FAVORITES, value).apply()

    /**
     * 常用应用的 id 串（逗号分隔，顺序即展示顺序）。
     *
     * ⚠ **`null`（键不存在）与空串是两种不同的状态**，别合并：
     *   · `null` = 从来没有写过 → 首次运行，应由 `FavoritesStore` 填入出厂默认三项；
     *   · `""`   = 用户把常用应用**全部移除**了 → 必须老老实实显示空态，不能又塞回默认值。
     * 读写统一走 `ui/FavoritesStore`，**不要在别处直接碰这个属性**。
     */
    var homeFavorites: String?
        get() = prefs.getString(KEY_HOME_FAVORITES, null)
        set(value) = edit().putString(KEY_HOME_FAVORITES, value).apply()

    // ==================== 复合秒表 ====================

    /** 显示十分之一秒。关闭后只显示到秒，降低刷屏与耗电。 */
    var stopwatchShowTenths: Boolean
        get() = prefs.getBoolean(KEY_SW_TENTHS, true)
        set(value) = edit().putBoolean(KEY_SW_TENTHS, value).apply()

    /**
     * 秒表计时中显示常驻通知（前台服务）。
     * 计时是「按时间戳推算」的，本来就不依赖后台进程，因此这项纯属可选：
     * 开着能随时从通知栏看到还在计时、并一键回到秒表页；关掉则完全静默。
     */
    var stopwatchNotification: Boolean
        get() = prefs.getBoolean(KEY_SW_NOTIFICATION, true)
        set(value) = edit().putBoolean(KEY_SW_NOTIFICATION, value).apply()

    /**
     * 新建秒表是否自动开始计时。默认开（旧行为）；
     * 关闭后新建的秒表停在 0:00 等待手动开始 —— 适合「先摆一排、到点再一起跑」的用法。
     */
    var stopwatchAutoStart: Boolean
        get() = prefs.getBoolean(KEY_SW_AUTO_START, true)
        set(value) = edit().putBoolean(KEY_SW_AUTO_START, value).apply()

    // ==================== 时钟立台 ====================

    /**
     * 防烧屏像素微移。
     * 时钟立台是仿熄屏显示的常亮画面，静态像素长期不移动会在 OLED 上留下残影，
     * 因此默认开启：每分钟把整块内容轻微平移一小段距离。
     */
    var clockAntiBurnIn: Boolean
        get() = prefs.getBoolean(KEY_CLOCK_ANTI_BURN, true)
        set(value) = edit().putBoolean(KEY_CLOCK_ANTI_BURN, value).apply()

    var clockShowSeconds: Boolean
        get() = prefs.getBoolean(KEY_CLOCK_SECONDS, true)
        set(value) = edit().putBoolean(KEY_CLOCK_SECONDS, value).apply()

    var clockKeepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_CLOCK_KEEP_ON, true)
        set(value) = edit().putBoolean(KEY_CLOCK_KEEP_ON, value).apply()

    var clock24Hour: Boolean
        get() = prefs.getBoolean(KEY_CLOCK_24H, true)
        set(value) = edit().putBoolean(KEY_CLOCK_24H, value).apply()

    // ==================== 呼吸放松 ====================

    var breathVibrate: Boolean
        get() = prefs.getBoolean(KEY_BREATH_VIBRATE, true)
        set(value) = edit().putBoolean(KEY_BREATH_VIBRATE, value).apply()

    /** 一次放松的总时长（分钟），取值来自 [BREATH_DURATIONS]。 */
    var breathMinutes: Int
        get() = prefs.getInt(KEY_BREATH_MINUTES, 1)
        set(value) = edit().putInt(KEY_BREATH_MINUTES, value).apply()

    /** 分段控件用的序号，越界时回落到默认值，避免设置被写坏后取不到下标。 */
    fun breathDurationIndex(): Int =
        BREATH_DURATIONS.indexOf(breathMinutes).takeIf { it >= 0 } ?: 0

    // ==================== 番茄钟 ====================

    /** 专注时长（分钟）。 */
    var pomodoroFocusMinutes: Int
        get() = prefs.getInt(KEY_POMO_FOCUS, 25)
        set(value) = edit().putInt(KEY_POMO_FOCUS, value).apply()

    /** 短休息时长（分钟）。 */
    var pomodoroShortBreakMinutes: Int
        get() = prefs.getInt(KEY_POMO_SHORT, 5)
        set(value) = edit().putInt(KEY_POMO_SHORT, value).apply()

    /** 长休息时长（分钟）：每完成一整轮番茄后进入。 */
    var pomodoroLongBreakMinutes: Int
        get() = prefs.getInt(KEY_POMO_LONG, 15)
        set(value) = edit().putInt(KEY_POMO_LONG, value).apply()

    /** 每轮的番茄个数，达到该数量后进入长休息。 */
    var pomodoroRoundsPerSet: Int
        get() = prefs.getInt(KEY_POMO_ROUNDS, 4)
        set(value) = edit().putInt(KEY_POMO_ROUNDS, value).apply()

    /** 一个阶段结束后自动开始下一阶段。关闭后停在原地等用户手动继续。 */
    var pomodoroAutoNext: Boolean
        get() = prefs.getBoolean(KEY_POMO_AUTO_NEXT, true)
        set(value) = edit().putBoolean(KEY_POMO_AUTO_NEXT, value).apply()

    /** 阶段切换时振动提醒。 */
    var pomodoroVibrate: Boolean
        get() = prefs.getBoolean(KEY_POMO_VIBRATE, true)
        set(value) = edit().putBoolean(KEY_POMO_VIBRATE, value).apply()

    /** 番茄钟运行中显示常驻通知，并在后台到点时提醒。 */
    var pomodoroNotification: Boolean
        get() = prefs.getBoolean(KEY_POMO_NOTIFICATION, true)
        set(value) = edit().putBoolean(KEY_POMO_NOTIFICATION, value).apply()

    // ==================== 时间与时区 ====================

    /**
     * 手动指定的时区 ID（Olson 形式，如 `Asia/Shanghai`）。
     *
     * **`null` = 跟随系统**，也就是出厂默认。刻意用 `null` 而不是空串来表达「跟随系统」：
     * 这样「没有这个键」与「显式跟随」是同一种状态，`resetAll()` 的 `clear()`
     * 天然把它恢复成跟随系统，不需要任何额外处理。
     *
     * 取值统一走 `appsys/AppClock.kt`，**不要在别处直接读这个属性** ——
     * 否则就会出现「某些页面跟随系统、某些页面跟随手选值」的撕裂。
     */
    var timeZoneId: String?
        get() = prefs.getString(KEY_TIME_ZONE, null)?.takeIf { it.isNotEmpty() }
        set(value) = edit().putString(KEY_TIME_ZONE, value).apply()

    /**
     * 用户关掉「系统时区已变更」提示时，**当时的系统时区 ID**。
     *
     * 刻意存 ID 而不是一个 `true/false`：需求要求「可以叉掉，但下一次切换时区时必须重新出现」。
     * 若只存布尔值，用户叉掉一次之后就永远看不到了；
     * 存成「在哪个系统时区下关掉的」，则系统时区一变（值不同）提示会自动重现，
     * 而同一个系统时区下确实不会再反复骚扰。判定逻辑见 `AppClock.needsMismatchNotice()`。
     */
    var timeZoneNoticeDismissedFor: String?
        get() = prefs.getString(KEY_TIME_ZONE_NOTICE, null)
        set(value) = edit().putString(KEY_TIME_ZONE_NOTICE, value).apply()

    // ==================== 备份（导出 / 导入） ====================

    /**
     * 备份的行格式：`类型|键|URL编码后的值`，每行一条。
     *
     * 用行文本而不是直接搬 settings.xml：导入时按类型写回（XML 那套解析要处理
     * 各种标签嵌套，脆弱）；值做 URL 编码是为了让「值里出现竖线或换行」也不会破坏格式。
     * 未来新增任何偏好，这里**自动**被纳入导出导入，不用改备份代码。
     */
    private const val TYPE_BOOLEAN = "b"
    private const val TYPE_INT = "i"
    private const val TYPE_LONG = "l"
    private const val TYPE_FLOAT = "f"
    private const val TYPE_STRING = "s"

    /** 不属于「个性化设置」的键：定位缓存、一次性 UI 状态 —— 不导出，也不被备份覆盖。 */
    private val NON_PERSONAL_KEYS = setOf(KEY_SUN_LAT, KEY_SUN_LON, KEY_TIME_ZONE_NOTICE)

    /**
     * 当前全部个性化设置 → 行文本快照（见上格式说明）。
     * `prefs.all` 是全量读取，未来加的键不需要在这里登记。
     */
    fun exportForBackup(): String {
        val sb = StringBuilder()
        prefs.all.forEach { (key, value) ->
            if (key in NON_PERSONAL_KEYS) return@forEach
            val type = when (value) {
                is Boolean -> TYPE_BOOLEAN
                is Int -> TYPE_INT
                is Long -> TYPE_LONG
                is Float -> TYPE_FLOAT
                is String -> TYPE_STRING
                // 未知类型跳过：宁可少备份一项，也不能写出导入端读不回的行
                else -> return@forEach
            }
            sb.append(type).append('|').append(key).append('|')
                .append(URLEncoder.encode(value.toString(), "UTF-8")).append('\n')
        }
        return sb.toString()
    }

    /**
     * 把备份快照写回偏好。
     *
     * 只**添加或覆盖**快照里出现的键，快照里没有的键保持原样 ——
     * 这正是「低版本备份导入高版本应用」的语义：新版本新增的偏好走出厂默认。
     * 坏行（类型不认识、数值解析失败）整行跳过，不中断也不抛异常。
     */
    fun importFromBackup(text: String) {
        val editor = edit()
        text.lineSequence().forEach { line ->
            if (line.isEmpty()) return@forEach
            val parts = line.split('|', limit = 3)
            if (parts.size != 3) return@forEach
            val (type, key, encoded) = parts
            if (key in NON_PERSONAL_KEYS) return@forEach
            val raw = try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (e: IllegalArgumentException) {
                return@forEach
            }
            when (type) {
                TYPE_BOOLEAN -> editor.putBoolean(key, raw.toBooleanStrictOrNull() ?: return@forEach)
                TYPE_INT -> editor.putInt(key, raw.toIntOrNull() ?: return@forEach)
                TYPE_LONG -> editor.putLong(key, raw.toLongOrNull() ?: return@forEach)
                TYPE_FLOAT -> editor.putFloat(key, raw.toFloatOrNull() ?: return@forEach)
                TYPE_STRING -> editor.putString(key, raw)
                // 未知类型 = 更高版本新增的偏好，本版本不认识，安全跳过
                else -> return@forEach
            }
        }
        editor.apply()
    }

    // ==================== 重置 ====================

    /**
     * 把所有设置项恢复为初始值（清空本偏好文件）。
     *
     * 只动设置，不碰用户数据：秒表记录在 `files/sys/stopwatches.csv`、
     * 纪念日在自己的数据文件里，两者都不在 SharedPreferences 中，
     * 所以清空偏好不会丢任何记录 —— 这一点要一直保持，别把用户数据搬进来。
     *
     * 主题与语言是「进程级生效」的项，调用方清空之后需要自行重新应用
     * （夜间模式要过 AppCompatDelegate，语言要靠 recreate 重新包装 Context）。
     */
    fun resetAll() {
        edit().clear().apply()
    }
}
