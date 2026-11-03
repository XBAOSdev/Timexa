package com.xbaos.shijianapp.appsys

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.xbaos.shijianapp.R
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.Locale

/**
 * 一条纪念日。
 *
 * 日期拆成三个 Int 存储，而不是存时间戳：
 *  - 纪念日是「日历上的那一天」，不是「某一刻」，存时间戳会带上时区与夏令时的坑；
 *  - 每年重复需要「换一个年份、保留月日」，三个 Int 直接换年份即可。
 *
 * @param yearly 是否每年重复。为 true 时 [year] 只作为原始记录保留，倒数按今年的月日算。
 */
data class Anniversary(
    val name: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val yearly: Boolean
) {
    /** 展示用的日期串（固定 ISO 顺序，避免不同地区把日月写反）。 */
    val dateText: String
        get() = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
}

/**
 * 纪念日持久化（CSV）。
 *
 * 格式：`名称,YYYY-MM-DD,是否每年重复(1/0)`
 * 名称里的逗号 / 引号 / 换行按 RFC4180 加引号转义，因此带逗号的名字不会错位 ——
 * 这一点比老版秒表文件严谨（秒表那套格式是历史约定，不能动，所以两者没有共用写盘逻辑）。
 */
class AnniversaryStore(private val file: File) {

    private val tmpFile: File get() = File(file.parentFile, file.name + ".tmp")

    fun load(): List<Anniversary> {
        if (!file.exists()) return emptyList()
        return try {
            file.bufferedReader().useLines { lines ->
                lines.mapNotNull { parseLine(it) }.toList()
            }
        } catch (e: IOException) {
            emptyList()
        }
    }

    /** 保存失败标记，供调用方提示；失败不抛异常，内存数据仍然有效。 */
    var failed: Boolean = false
        private set

    fun save(items: List<Anniversary>) {
        failed = false
        try {
            file.parentFile?.mkdirs()
            tmpFile.bufferedWriter().use { writer ->
                items.forEach { item ->
                    writer.write(escape(item.name))
                    writer.write(",")
                    writer.write(item.dateText)
                    writer.write(",")
                    writer.write(if (item.yearly) "1" else "0")
                    writer.write("\n")
                }
            }
            if (!tmpFile.renameTo(file)) {
                file.writeText(tmpFile.readText())
                tmpFile.delete()
            }
        } catch (e: IOException) {
            failed = true
        }
    }

    fun clear() {
        file.delete()
        tmpFile.delete()
    }

    private fun parseLine(rawLine: String): Anniversary? {
        val line = rawLine.trimEnd('\r', '\n')
        if (line.isBlank()) return null

        val fields = splitFields(line)
        if (fields.size < 3) return null

        val yearly = fields[fields.size - 1].trim().toIntOrNull() ?: return null
        val parts = fields[fields.size - 2].trim().split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null

        val name = fields.subList(0, fields.size - 2).joinToString(",")
        if (name.isBlank()) return null

        return Anniversary(name, year, month, day, yearly == 1)
    }

    /** 按 RFC4180 拆一行 CSV（支持引号包裹与 `""` 转义）。 */
    private fun splitFields(line: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                inQuotes && ch == '"' && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            index++
        }
        fields.add(current.toString())
        return fields
    }

    private fun escape(name: String): String =
        if (name.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + name.replace("\"", "\"\"") + "\""
        } else {
            name
        }
}

/**
 * 日期计算。
 *
 * 全部以「本地零点」为基准做减法：纪念日是整天概念，
 * 用当前时刻直接相减会因为「今天过了几小时」得到 0 天或 1 天的抖动。
 *
 * ⚠ 这里的「本地」指的是**应用时区**（走 [AppClock]），不是系统默认时区。
 * 这是时区功能里少数**真的会算错**的地方之一：手动把应用时区设到与系统跨日不同的时区后，
 * 「今天」究竟算哪一天需要跟着应用时区走，否则倒数天数会差一天。
 */
object DayMath {

    private const val DAY_MS = 86_400_000L

    /** 今天零点的时间戳（按应用时区）。 */
    fun startOfToday(): Long {
        val cal = AppClock.calendar()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 指定年月日的零点。
     * 日期会被夹到当月实际存在的范围内（例如 2 月 29 日在平年落到 2 月 28 日），
     * 否则 Calendar 会宽松进位到 3 月 1 日 —— 那样「2 月 29 日的周年」就跑到三月去了。
     */
    fun startOf(year: Int, month: Int, day: Int): Long {
        val cal = AppClock.calendar()
        cal.clear()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month - 1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        cal.set(Calendar.DAY_OF_MONTH, minOf(day, maxDay))
        return cal.timeInMillis
    }

    /** 下一次发生这一天的零点。每年重复时，今年的已过去就顺延到明年。 */
    fun nextOccurrence(item: Anniversary): Long {
        if (!item.yearly) return startOf(item.year, item.month, item.day)
        val today = startOfToday()
        val thisYear = AppClock.calendar().get(Calendar.YEAR)
        val candidate = startOf(thisYear, item.month, item.day)
        return if (candidate < today) {
            startOf(thisYear + 1, item.month, item.day)
        } else {
            candidate
        }
    }

    /**
     * 距今天的天数：正数 = 还有几天，0 = 就是今天，负数 = 已经过去几天。
     * 用四舍五入而不是整除，避免夏令时导致的一天内 23/25 小时被算错一天。
     */
    fun daysFromToday(targetMillis: Long): Int =
        Math.round((targetMillis - startOfToday()).toDouble() / DAY_MS).toInt()

    /** 一条纪念日距今天的天数。 */
    fun daysOf(item: Anniversary): Int = daysFromToday(nextOccurrence(item))

    /**
     * 列表排序：未来最近的在最前，已经过去的按「越近越前」排在后面。
     * 这样首页取前几条时，拿到的永远是用户最该看到的那几个。
     */
    fun sortedByUrgency(items: List<Anniversary>): List<Anniversary> =
        items.sortedWith(
            compareBy(
                { val d = daysOf(it); if (d >= 0) 0 else 1 },
                { val d = daysOf(it); if (d >= 0) d else -d }
            )
        )

    fun dayMillis(): Long = DAY_MS
}

/**
 * 写入系统日历。
 *
 * 用 ACTION_INSERT 把「新建事件」的界面交给用户已安装的日历应用：
 *  - 这样完全不需要 READ_CALENDAR / WRITE_CALENDAR 权限（写入由日历应用自己完成，
 *    用户还能在日历应用里先确认、改提醒时间），比直接往 CalendarContract 里插数据更稳妥；
 *  - 代价是依赖设备上存在日历应用，没有的话只能提示用户（返回 false）。
 *
 * 全天事件按惯例约定：开始 = 当天零点，结束 = 次日零点。
 */
object CalendarSync {

    fun insert(context: Context, item: Anniversary, description: String?): Boolean {
        val start = DayMath.nextOccurrence(item)
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, item.name)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + DayMath.dayMillis())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(CalendarContract.Events.DESCRIPTION, description)
        if (item.yearly) {
            // 每年重复。
            // 注意：这里用的是 **Events.RRULE 这个列名常量**，不是 EXTRA_ 系列 ——
            // CalendarContract 里并不存在 EXTRA_EVENT_RRULE，官方文档给出的
            // 「INSERT 时可携带的 extras」表格中，重复规则一项写的就是 Events.RRULE。
            // 是否被采纳取决于日历应用对该字段的支持程度。
            intent.putExtra(CalendarContract.Events.RRULE, "FREQ=YEARLY")
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}

/**
 * 倒数文案。
 * 集中一处，保证首页卡片与列表页对同一条纪念日的说法完全一致
 * （否则很容易出现首页写「还有 3 天」、列表写「3 天后」这种不统一）。
 */
object AnniversaryText {

    fun daysLabel(context: Context, item: Anniversary): String {
        val days = DayMath.daysOf(item)
        return when {
            days == 0 -> context.getString(R.string.anniversary_days_today)
            // 走 plurals 而不是拼字符串：英文里 1 后面得是 `day left` 而不是 `days left`。
            days > 0 -> context.resources
                .getQuantityString(R.plurals.anniversary_days_left, days, days)
            else -> context.resources
                .getQuantityString(R.plurals.anniversary_days_passed, -days, -days)
        }
    }

    /** 倒数天数对应的颜色资源：今天用强调色，未来用主文字色，已过去用弱化色。 */
    fun daysColorRes(item: Anniversary): Int {
        val days = DayMath.daysOf(item)
        return when {
            days == 0 -> R.color.accent
            days > 0 -> R.color.text_primary
            else -> R.color.text_tertiary
        }
    }
}
