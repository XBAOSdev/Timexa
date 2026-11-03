package com.xbaos.shijianapp.appyy

import java.io.File
import java.io.IOException

/**
 * 秒表计次的持久化（独立 CSV）。
 *
 * ## 为什么单独一个文件，而不是并进 stopwatches.csv
 *
 * `stopwatches.csv` 的格式是一条被多处依赖的契约：
 * 「从右往左取最后 3 个数值字段，其余全当名称」，这条规则同时保证了
 * 旧版含逗号的坏行能被正确读回。往里加一列会破坏它，代价远大于收益。
 * 计次是「一个秒表对应 N 条」的一对多关系，本来也不适合塞进同一条记录。
 *
 * ## 格式
 *
 * ```
 * 秒表id,时刻ms
 * ```
 * 每行一条计次。归属键是秒表的**身份 id**（`StopwatchRecord.id`，终身不变的主键）。
 * ⚠ 旧版曾用「创建时间戳」当键，那是 2026-09-26 修掉的串计次 bug 的根源：
 *   创建时间戳同时是计时参数（resume 会被改写成「本段起点」），「全部继续」时
 *   多个秒表会在同一毫秒内被赋同一个值 —— 键撞车，计次从此互通。
 *   现在的 id 与计时参数严格分离，resume/重置随便改 createdTimestamp 都不影响归属。
 *   历史数据无缝迁移：秒表 CSV 没有 id 列的旧行，id 兜底为创建时间戳，键不变。
 *
 * 同一秒表的多行按写入顺序排列，行内先出现的就是更早的计次。
 *
 * ## 写入策略
 *
 * 与 [StopwatchStore] 一致：`.tmp` + rename 原子写，避免写到一半被杀导致整份损坏。
 * 计次条数不会太大（一次专注几十条量级），整体重写足够快，不必做增量追加。
 */
class LapStore(private val file: File) {

    private val tmpFile: File get() = File(file.parentFile, file.name + ".tmp")

    /** 供调用方判断最近一次保存是否失败。 */
    var failed: Boolean = false
        private set

    /**
     * 读出全部计次，按「秒表创建时间戳 → 计次时刻列表」分组。
     *
     * 容错：单行解析失败就跳过该行，不整份丢弃 ——
     * 一条坏行不该让用户其它秒表的计次一起消失。
     */
    fun load(): Map<Long, List<Long>> {
        if (!file.exists()) return emptyMap()
        return try {
            val grouped = LinkedHashMap<Long, MutableList<Long>>()
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val parsed = parseLine(line) ?: return@forEach
                    grouped.getOrPut(parsed.first) { mutableListOf() }.add(parsed.second)
                }
            }
            grouped
        } catch (e: IOException) {
            emptyMap()
        }
    }

    /** 读出某一个秒表的计次。 */
    fun loadFor(createdTimestamp: Long): List<Long> = load()[createdTimestamp].orEmpty()

    /**
     * 全量写回。
     *
     * 调用方传入的是「完整的分组表」而不是单个秒表，因为本文件是多秒表共用的：
     * 只写一个秒表会把其它秒表的计次一起抹掉。
     */
    fun save(grouped: Map<Long, List<Long>>) {
        failed = false
        try {
            file.parentFile?.mkdirs()
            tmpFile.bufferedWriter().use { writer ->
                grouped.forEach { (created, laps) ->
                    laps.forEach { lap ->
                        writer.write(created.toString())
                        writer.write(",")
                        writer.write(lap.toString())
                        writer.write("\n")
                    }
                }
            }
            if (!tmpFile.renameTo(file)) {
                // 个别文件系统不支持 rename 覆盖，退化为直接写回
                file.writeText(tmpFile.readText())
                tmpFile.delete()
            }
        } catch (e: IOException) {
            failed = true
        }
    }

    /** 清空全部计次（用于「清空计时记录」这类整体操作）。 */
    fun clear() {
        file.delete()
        tmpFile.delete()
    }

    private fun parseLine(rawLine: String): Pair<Long, Long>? {
        val line = rawLine.trimEnd('\r', '\n')
        if (line.isBlank()) return null
        val parts = line.split(',')
        if (parts.size < 2) return null
        val created = parts[0].trim().toLongOrNull() ?: return null
        val lap = parts[1].trim().toLongOrNull() ?: return null
        return created to lap
    }
}
