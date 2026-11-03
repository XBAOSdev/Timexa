package com.xbaos.shijianapp.appsys

import android.content.Context
import android.os.Build
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 全量备份（zip）的打包与解包。
 *
 * ## 包结构
 *
 * ```
 * manifest.json            ← 格式版本与应用信息，导入前的第一道闸门
 * data/stopwatches.csv     ← 秒表记录（有才写）
 * data/laps.csv            ← 计次记录（有才写）
 * data/anniversaries.csv   ← 纪念日（有才写）
 * data/settings.txt        ← 设置项快照（AppPrefs.exportForBackup 的行格式）
 * ```
 *
 * ## 版本策略（用户拍板：低→高可导，高→低拒绝）
 *
 * 「格式版本」是 [SCHEMA]，跟应用版本号是两回事：只有**包结构或字段含义**发生
 * 不兼容变化时才 +1。导入时只看它：
 *  - 包内 schema ≤ 本机 [SCHEMA] → 可导入。低版本备份里没有的条目保持原样不覆盖；
 *  - 包内 schema > 本机 → 抛 [SchemaTooNewException]，调用方提示先升级应用。
 *
 * ## 未来新增个性化数据
 *
 * 只要在 [export] / [read] 的条目清单里各加一行（以及 [SCHEMA] 视情况 +1），
 * 导入导出就自动覆盖 —— 调用方（设置页）不需要跟着改。
 */
object BackupZip {

    /**
     * 备份格式版本。v1：初始格式。
     * ⚠ 发布后**不可重命名任何条目**，也**不可改变条目含义** —— 只能新增，
     *     否则旧备份在新版本上会导不全。
     */
    const val SCHEMA = 1

    private const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_STOPWATCHES = "data/stopwatches.csv"
    const val ENTRY_LAPS = "data/laps.csv"
    const val ENTRY_ANNIVERSARIES = "data/anniversaries.csv"
    const val ENTRY_SETTINGS = "data/settings.txt"

    /**
     * 单个条目的读入上限（10 MB）。本应用的记录都是小文本（几百行量级），
     * 这个值纯属防御性护栏：万一用户选中了一个被构造过的超大 zip，不至于把内存吃光。
     */
    private const val MAX_ENTRY_BYTES = 10 * 1024 * 1024

    /** 备份的格式版本比本机新 —— 按用户拍板的策略直接拒绝。 */
    class SchemaTooNewException(val schema: Int) : IOException("backup schema $schema is newer than $SCHEMA")

    data class Manifest(val schema: Int, val versionName: String, val versionCode: Long)

    /** 解出来的备份内容。[entries] 只包含认识的数据条目，文件不存在时对应键缺席。 */
    data class Contents(val manifest: Manifest, val entries: Map<String, ByteArray>)

    /**
     * 把全部个性化数据打包为一个 zip 写入 [target]。
     * 传 null 或文件不存在的条目直接跳过 —— 备份不臆造空文件。
     */
    fun export(
        context: Context,
        target: File,
        stopwatchCsv: File?,
        lapCsv: File?,
        anniversaryCsv: File?,
        settingsSnapshot: String?
    ) {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) {
            pkg.longVersionCode
        } else {
            pkg.versionCode.toLong()
        }
        val manifest = JSONObject().apply {
            put("app", context.packageName)
            put("schema", SCHEMA)
            put("version_name", pkg.versionName ?: "")
            put("version_code", versionCode)
        }.toString()

        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            listOf(
                ENTRY_STOPWATCHES to stopwatchCsv,
                ENTRY_LAPS to lapCsv,
                ENTRY_ANNIVERSARIES to anniversaryCsv
            ).forEach { (name, file) ->
                if (file != null && file.exists()) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (settingsSnapshot != null) {
                zip.putNextEntry(ZipEntry(ENTRY_SETTINGS))
                zip.write(settingsSnapshot.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /**
     * 读入一个备份流。任何一步不对劲都抛 [IOException]：
     *  - 没有 manifest / manifest 坏了 / 应用对不上 → 普通无效文件；
     *  - schema 比本机新 → [SchemaTooNewException]（调用方要给不同的提示）。
     *
     * 全部条目**先读进内存**再返回 —— 校验（schema、秒表 csv 可解析）都发生在
     * 碰本地文件之前，导入失败时本地数据一个字节都不会动，不需要回滚逻辑。
     */
    fun read(input: InputStream, packageName: String): Contents {
        val entries = HashMap<String, ByteArray>()
        var manifest: Manifest? = null
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val bytes = readCapped(zip)
                    when (entry.name) {
                        ENTRY_MANIFEST -> manifest = parseManifest(bytes, packageName)
                        // 认识的数据条目收下；不认识的原样忽略 ——
                        // 这正是「低版本可导入高版本备份」的实现方式：
                        // 高版本新增的条目，低版本读到这里只是跳过。
                        ENTRY_STOPWATCHES,
                        ENTRY_LAPS,
                        ENTRY_ANNIVERSARIES,
                        ENTRY_SETTINGS -> entries[entry.name] = bytes
                    }
                }
                zip.closeEntry()
            }
        }
        val m = manifest ?: throw IOException("manifest missing")
        return Contents(m, entries)
    }

    private fun parseManifest(bytes: ByteArray, packageName: String): Manifest {
        // 「app」字段对不上说明这个 zip 不是本应用导出的（用户选错文件），
        // 与「manifest 缺失」一样按无效处理，不给「版本太新」的误导性提示。
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: JSONException) {
            throw IOException("manifest is not valid JSON", e)
        }
        if (json.optString("app") != packageName) {
            throw IOException("backup belongs to another app")
        }
        if (!json.has("schema")) {
            throw IOException("manifest has no schema")
        }
        val schema = json.optInt("schema", -1)
        if (schema <= 0) {
            throw IOException("manifest schema invalid")
        }
        if (schema > SCHEMA) {
            throw SchemaTooNewException(schema)
        }
        return Manifest(schema, json.optString("version_name"), json.optLong("version_code"))
    }

    private fun readCapped(input: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) {
                throw IOException("entry too large")
            }
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }
}
