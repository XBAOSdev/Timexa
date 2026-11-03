package com.xbaos.shijianapp.appsys

import com.xbaos.shijianapp.ui.AppPrefs
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 全应用**唯一的**时间 / 时区出口。
 *
 * ## 为什么必须有这么一个东西
 *
 * Android 不允许普通应用改系统时区（`AlarmManager.setTimeZone` 需要 signature 级权限），
 * 也没有「按应用设置时区」的官方 API。所以「应用时区」这件事只能靠自己显式地
 * **把选定的 [TimeZone] 传给每一个用到时间的 API** 来实现。
 *
 * 一旦有任何一个地方漏了、走了 `Calendar.getInstance()` 或 `new SimpleDateFormat(...)`
 * 这类「隐式读默认时区」的写法，就会出现「首页是纽约时间、日期计算器是北京时间」的撕裂。
 * 把取值收敛到本对象，是避免这种撕裂的**唯一**手段 —— 新增用到时间的代码时请一律走这里。
 *
 * ## 两条取值规则
 *
 * 1. **[AppPrefs.timeZoneId] 为 `null`（出厂默认）→ 跟随系统。**
 *    每次都重新读 [TimeZone.getDefault()]，**刻意不缓存**。
 *    AOSP 在 `TimeZone.getDefault()` 的源码注释里就明确建议过：
 *    *"Since the user's time zone changes dynamically, avoid caching this value."*
 * 2. **非 `null` → 用存下的 Olson ID。** 此时系统时区怎么变都不影响应用显示。
 *
 * 若存下的 ID 在本机不存在（换机恢复、或 tzdata 更新移除了别名），
 * [isKnownId] 会判定为无效，[zone] 回退到跟随系统 ——
 * **绝不静默退化成 GMT**（`TimeZone.getTimeZone()` 对未知 ID 正是静默返回 GMT，
 * 那会让界面显示一个无人选择的时区，比回退到系统更难排查）。
 *
 * ## 关于「缓存」
 *
 * 本对象**不缓存** [zone] 的结果：`TimeZone.getDefault()` 与 `TimeZone.getTimeZone()` 内部
 * 都有框架级缓存，重复调用很便宜。真正会过期的是 `SimpleDateFormat` ——
 * 它在**构造时**就把当时的时区拷进内部 `Calendar`，之后再改系统时区它也不会变。
 * 因此这里的 [formatter] / [dateFormat] **每次都新建**一个带正确时区的实例，
 * 调用方**不要**在外面做长期复用（页面重建时会重新取，见 `BaseActivity` 的时区变更检测）。
 */
object AppClock {

    /** 系统（设备）时区的 Olson ID。 */
    fun systemZoneId(): String = TimeZone.getDefault().id

    /** 当前是否处于「跟随系统」模式（即没有手动指定）。 */
    fun followsSystem(): Boolean = AppPrefs.timeZoneId == null

    /**
     * 当前生效的应用时区。
     * 手动 ID 无效时回退到系统时区（见类注释），因此本方法**永远返回一个可用的时区**。
     */
    fun zone(): TimeZone {
        val id = AppPrefs.timeZoneId ?: return TimeZone.getDefault()
        return if (isKnownId(id)) TimeZone.getTimeZone(id) else TimeZone.getDefault()
    }

    /** 当前生效的应用时区 ID。 */
    fun zoneId(): String = zone().id

    /** 用应用时区构造的 [Calendar]。所有「今天」「零点」「日期加减」都必须用它。 */
    fun calendar(): Calendar = Calendar.getInstance(zone())

    /**
     * 用应用时区构造的 [SimpleDateFormat]，每次新建（理由见类注释）。
     *
     * [zone] 参数用于「按**别的**时区」格式化（时区选择页要给每一行显示该时区的时间），
     * 默认值就是当前应用时区。
     */
    fun formatter(
        pattern: String,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = zone()
    ): SimpleDateFormat = SimpleDateFormat(pattern, locale).apply { timeZone = zone }

    /** 用应用时区构造的 [DateFormat]。 */
    fun dateFormat(style: Int, locale: Locale = Locale.getDefault()): DateFormat =
        DateFormat.getDateInstance(style, locale).apply { timeZone = zone() }

    /**
     * 时区偏移的展示标签，例如 `UTC+8` / `UTC+5:30` / `UTC-3`。
     * 整点偏移省略 `:00`（绝大多数时区都是整点，省下的字符让列表更清爽）；
     * 零偏移直接显示 `UTC`。
     */
    fun offsetLabel(
        zone: TimeZone = zone(),
        at: Long = System.currentTimeMillis()
    ): String = offsetLabelOfMinutes(zone.getOffset(at) / 60_000)

    /** [offsetLabel] 的纯数值版本，供「已按偏移分好组」的列表显示分组标题。 */
    fun offsetLabelOfMinutes(totalMinutes: Int): String {
        if (totalMinutes == 0) return "UTC"
        val sign = if (totalMinutes < 0) '-' else '+'
        val abs = if (totalMinutes < 0) -totalMinutes else totalMinutes
        val hours = abs / 60
        val minutes = abs % 60
        return if (minutes == 0) {
            "UTC$sign$hours"
        } else {
            "UTC$sign$hours:" + String.format(Locale.US, "%02d", minutes)
        }
    }

    /** `Asia/Shanghai · UTC+8` 形式的完整标签。 */
    fun label(
        zone: TimeZone = zone(),
        at: Long = System.currentTimeMillis()
    ): String = zone.id + " · " + offsetLabel(zone, at)

    /** 该 Olson ID 在本机 tzdata 里是否存在。 */
    fun isKnownId(id: String): Boolean = id.lowercase(Locale.US) in knownIds

    /**
     * 手动指定期间，是否需要在设置页显示「系统时区已变更」提示。
     *
     * 判定用的是「用户上次关掉提示时的**系统时区 ID**」而不是一个布尔值，
     * 这样才能同时满足两条相反的要求：
     *  - 同一个系统时区下关掉之后**不再反复骚扰**；
     *  - 系统时区**再次变化**时提示**必须重新出现**（防止用户误判自己还在系统时区上）。
     */
    fun needsMismatchNotice(): Boolean {
        val manualId = AppPrefs.timeZoneId ?: return false
        val systemId = systemZoneId()
        // 手选的时区恰好就是系统时区 → 视为一致，不算「不一致」
        if (manualId == systemId) return false
        return AppPrefs.timeZoneNoticeDismissedFor != systemId
    }

    /**
     * 本机 tzdata 里的全部时区 ID（小写）。
     *
     * `TimeZone.getAvailableIDs()` 在 Android 上约 600 条，含大量「固定偏移别名」
     * （`Etc/GMT+8`、`SystemV/AST4`、三个字母的旧 ID 等），不适合直接给用户看；
     * 过滤交给 [ZoneCatalog]。
     */
    private val knownIds: Set<String> by lazy {
        TimeZone.getAvailableIDs().mapTo(mutableSetOf()) { it.lowercase(Locale.US) }
    }
}
