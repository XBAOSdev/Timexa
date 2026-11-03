package com.xbaos.shijianapp.appsys

import java.util.Locale
import java.util.TimeZone

/**
 * 时区选择的**数据源**。
 *
 * `TimeZone.getAvailableIDs()` 在 Android 上返回约 600 条，但其中大部分不适合给用户看：
 *  - `Etc/GMT+8`、`Etc/GMT-8` 这类固定偏移名（而且 `+`/`-` 的语义与 POSIX 相反，极易误导）；
 *  - `SystemV/AST4`、`SystemV/EST5EDT` 等历史遗留；
 *  - `ACT`、`AET`、`PST8PDT` 这类三个字母的旧 ID（歧义大，同一缩写在不同地区含义不同）；
 *  - `posixrules`、`Factory`、`localtime` 等根本不是时区的条目。
 *
 * 这里只保留 `洲/城市` 形式的 Olson 区域时区（外加 `UTC`），
 * 也就是系统「日期和时间」里那一套标准命名 —— 稳定、无歧义、跨设备一致。
 *
 * 不需要联网、不需要额外依赖：全部数据来自系统自带的 tzdata。
 */
object ZoneCatalog {

    /** 一条可选的时区。 */
    data class ZoneItem(
        val id: String,
        val zone: TimeZone,
        /** 当前时刻相对 UTC 的偏移（分钟）。 */
        val offsetMinutes: Int
    )

    /** 同一 UTC 偏移的一组时区，便于按偏移分组展示。 */
    data class ZoneGroup(
        val offsetMinutes: Int,
        val items: List<ZoneItem>
    )

    /**
     * 置顶的「常用」时区。
     * `Asia/Shanghai` 在最前 —— 本项目的主要用户是中国用户，这是最高频的选择。
     */
    val COMMON_IDS = listOf("Asia/Shanghai", "UTC")

    /**
     * 保留的洲 / 区域前缀。
     * 只要 ID 的第一段不在这个集合里就被过滤掉（`Etc` / `SystemV` / `US` / `Canada`
     * 等旧式别名一并排除）。
     */
    private val REGIONS = setOf(
        "Africa", "America", "Antarctica", "Arctic", "Asia",
        "Atlantic", "Australia", "Europe", "Indian", "Pacific"
    )

    /** 首页列出的固定项：`UTC` 不满足「含 `/`」的条件，单独放行。 */
    private const val FIXED_UTC = "UTC"

    /**
     * 偏移检索：支持 `+8`、`utc+8`、`gmt-5`、`+5:30`、`utc8` 这几种写法。
     * 组 1 = 符号，组 2 = 小时，组 3 = 分钟（可省）。
     */
    private val OFFSET_QUERY = Regex(
        "^(?:utc|gmt)?\\s*([+-])?\\s*(\\d{1,2})(?::?(\\d{2}))?$",
        RegexOption.IGNORE_CASE
    )

    /** 全部可选项，按「洲 → 城市」的 ID 排序（只算一次）。 */
    private val allIds: List<String> by lazy {
        TimeZone.getAvailableIDs()
            .filter { id ->
                id == FIXED_UTC || (id.contains('/') && id.substringBefore('/') in REGIONS)
            }
            .sorted()
    }

    /**
     * 按 UTC 偏移分组的完整列表。
     * 组按偏移升序（从 UTC-12 到 UTC+14），组内按展示名升序 —— 用户扫一眼就能定位。
     */
    fun allGroups(now: Long): List<ZoneGroup> = group(ids = allIds, now = now)

    /**
     * 关键词过滤后的分组列表。关键词为空时等价于 [allGroups]。
     *
     * 命中条件（任一即可）：
     *  1. 展示名包含（不分大小写），例如 `shanghai`；
     *  2. 完整 ID 包含，例如 `asia/`；
     *  3. 关键词是一个 UTC 偏移写法，例如 `+8` / `utc-5` / `+5:30`。
     */
    fun filter(query: String, now: Long): List<ZoneGroup> {
        val keyword = normalize(query).trim().lowercase(Locale.US)
        if (keyword.isEmpty()) return allGroups(now)

        offsetQueryMinutes(keyword)?.let { target ->
            return group(
                ids = allIds.filter { offsetOf(it, now) == target },
                now = now
            )
        }

        val matched = allIds.filter { id ->
            displayName(id).lowercase(Locale.US).contains(keyword) ||
                id.lowercase(Locale.US).contains(keyword)
        }
        return group(ids = matched, now = now)
    }

    /** 单个时区信息；ID 无效时返回 null。 */
    fun item(id: String, now: Long): ZoneItem? {
        if (!AppClock.isKnownId(id)) return null
        return ZoneItem(id, TimeZone.getTimeZone(id), offsetOf(id, now))
    }

    /**
     * 展示名：取 ID 的最后一段、下划线换空格。
     * `Asia/Shanghai` → `Shanghai`；`America/Argentina/Buenos_Aires` → `Buenos Aires`。
     *
     * 刻意**不做**中文本地化：时区 ID 是国际通用标识，
     * 译成中文反而会让用户在与别的设备 / 文档对照时对不上号。
     */
    fun displayName(id: String): String =
        id.substringAfterLast('/').replace('_', ' ')

    private fun group(ids: List<String>, now: Long): List<ZoneGroup> =
        ids.map { ZoneItem(it, TimeZone.getTimeZone(it), offsetOf(it, now)) }
            .groupBy { it.offsetMinutes }
            .toSortedMap()
            .map { (offset, items) ->
                ZoneGroup(offset, items.sortedBy { displayName(it.id) })
            }

    private fun offsetOf(id: String, now: Long): Int =
        TimeZone.getTimeZone(id).getOffset(now) / 60_000

    /**
     * 关键词归一化：全角转半角。
     *
     * ⚠ 中文输入法下打出的 `＋8`（全角加号 U+FF0B）、全角冒号 `：`、全角数字 `８`
     * 与对应的 ASCII 字符**完全不是同一个码位**，但肉眼无法区分 ——
     * 不做归一化就会出现「用户看着输入了 `+8`、列表却说没有匹配」的诡异现象。
     * 本项目里的时区 ID 与偏移写法只用 ASCII，因此这一层转换是安全的。
     *
     * 实测来源：模拟器中英文拼音键盘下 `adb shell input text "+8"` 落进输入框的就是 U+FF0B。
     */
    private fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            sb.append(
                when {
                    ch == '\u3000' -> ' '                     // 全角空格
                    // 方向别写反：全角码位 = 半角 + 0xFEE0，因此半角 = 全角 − 0xFEE0。
                    // 写成 `ch + 0xFEE0` 会溢出 Char 范围（U+FF0B + 0xFEE0 > U+FFFF），
                    // 得到一个完全不相干的字符 —— 编译期不报错，只是永远搜不到。
                    ch.code in 0xFF01..0xFF5E -> ch - 0xFEE0  // 全角 ASCII 区（含 ＋ － ： ０-９）
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** 把 `+8` / `utc-5` / `+5:30` 解析成偏移分钟数；不是偏移写法时返回 null。 */
    private fun offsetQueryMinutes(keyword: String): Int? {
        val match = OFFSET_QUERY.matchEntire(keyword) ?: return null
        // 纯数字（如 `8`）不当作偏移 —— 那更可能是用户在搜地名里的数字
        if (match.groupValues[1].isEmpty() && !keyword.startsWith("utc") && !keyword.startsWith("gmt")) {
            return null
        }
        val hours = match.groupValues[2].toIntOrNull() ?: return null
        if (hours > 14) return null
        val minutes = match.groupValues[3].toIntOrNull() ?: 0
        val sign = if (match.groupValues[1] == "-") -1 else 1
        return sign * (hours * 60 + minutes)
    }
}
