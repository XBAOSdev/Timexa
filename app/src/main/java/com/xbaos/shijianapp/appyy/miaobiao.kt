package com.xbaos.shijianapp.appyy

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.appsys.WidgetStopwatchProvider
import com.xbaos.shijianapp.ui.AppDialog
import com.xbaos.shijianapp.ui.AppPrefs
import com.xbaos.shijianapp.ui.ItemAnim
import com.xbaos.shijianapp.ui.Notifier
import com.xbaos.shijianapp.ui.PendingActions
import com.xbaos.shijianapp.ui.PermissionBanner
import com.xbaos.shijianapp.ui.PressFeedback
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 秒表持久化记录。
 *
 * 计时模型（与旧版完全一致，务必不要改动语义）：
 *   已计时 = (isRunning ? now - createdTimestamp : 0) + pausedElapsed
 * 也就是说：运行中只记录“起点时刻”，不记录“已跑多久”；
 * 因此即便 App 被系统杀掉、没来得及保存，重新打开后计时也是连续的、不会清零。
 */
/**
 * 单条秒表记录。
 *
 * 字段刻意用 `var` 而不是 `val`：
 *  秒表列表页与「单表专注页」（[StopwatchFocus]）需要**共享同一个对象**，
 *  谁改了 `isRunning` / `pausedElapsed`，另一个页面立刻就能看到，无需任何序列化同步。
 *  这是本类不是纯不可变数据类的唯一原因，改动前请确认 [StopwatchBus] 的用法。
 *
 * [id] 是**终身不变的身份列**（val），计次（[LapStore]）用它做归属键。
 * 它必须与计时参数 `createdTimestamp` 严格分离：后者在每次「继续」时会被改写成
 * 本段起点（计时模型决定的，见下），**绝不能再用它当任何关联数据的键** ——
 * 2026-09-26 修的「计次串到别的秒表」正是旧版拿 createdTimestamp 当计次键、
 * 「全部继续」时多个表在同一毫秒内被赋同一个值导致的键撞车。
 */
data class StopwatchRecord(
    var name: String,
    var createdTimestamp: Long,
    var pausedElapsed: Long,
    var isRunning: Boolean,
    val id: Long
)

/**
 * 秒表记录持久化（CSV）。
 *
 * 向下兼容说明——这是本次重构最需要小心的地方：
 *  旧版写出格式为 `名称,创建时间戳,暂停累计,运行标志`，且名称**未做转义**，
 *  因此当用户把秒表重命名为含逗号的名称时，写出的行会被解析错位（旧 bug）。
 *  本实现：
 *    · 列序（**必须与 parseLine 的从右往左取法严格一致，两处一起改**）：
 *      `名称, id, 创建, 暂停累计, 运行标志` —— 运行标志恒在最后一列，旧 4 列的
 *      「最后三列数值」语义原样保留，id 插在名称之后；
 *    · 读取：先按 RFC4180 拆字段，再从**右往左**取数值字段。5 列行解析失败时
 *      会再试一次「坏序修复」（2.0.12 开发版曾把 id 追加在末尾，与读取取法错位，
 *      导致写一次就整行丢弃 —— 用户报的「无法保存」就是它），最后按旧 4 列兜底；
 *      旧 4 列行没有 id，此时 id 兜底为创建时间戳（自动迁移，计次全保留）；
 *    · 写出：仅当名称含 `,` `"` 换行时才加引号；原原子写：先写 .tmp 再改名。
 *
 *  关于歧义窗口：只有「极老版本未加引号的名称里含逗号、且逗号后恰好是一段
 *  纯数字」的行可能被误判 —— 后果只是名称少显示一段，计时与计次归属不受影响。
 *
 * @param fallbackName 记录里名称为空时用的兜底名。由调用方从字符串资源传入，
 *                     这样多语言下兜底名也会跟着变（旧版硬编码为中文“秒表”）。
 */
class StopwatchStore(private val file: File, private val fallbackName: String) {

    private val tmpFile: File get() = File(file.parentFile, file.name + ".tmp")

    fun load(): List<StopwatchRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val records = file.bufferedReader().useLines { lines ->
                lines.mapNotNull { parseLine(it) }.toList()
            }
            // id 去重：旧版 resumeAll 会给多个秒表赋同一个毫秒时间戳，迁移后
            // 它们的 id（=created）可能相同 —— id 重复等于计次互通，必须拆开。
            // 已撞上的那份计次没法凭空拆分（数据在旧版里就已合并），保证此后不再撞即可。
            val used = HashSet<Long>(records.size)
            records.map { record ->
                if (used.add(record.id)) {
                    record
                } else {
                    var fresh = System.currentTimeMillis()
                    while (!used.add(fresh)) fresh++
                    record.copy(id = fresh)
                }
            }
        } catch (e: IOException) {
            emptyList()
        }
    }

    fun save(records: List<StopwatchRecord>) {
        failed = false
        try {
            file.parentFile?.mkdirs()
            tmpFile.bufferedWriter().use { writer ->
                records.forEach { record ->
                    writer.write(escape(record.name))
                    writer.write(",")
                    // 列顺序必须与 parseLine 的从右往左取法严格一致：
                    // 运行标志恒在最后一列，id 在创建时间戳之前（原因见 parseLine 注释）。
                    writer.write(record.id.toString())
                    writer.write(",")
                    writer.write(record.createdTimestamp.toString())
                    writer.write(",")
                    writer.write(record.pausedElapsed.toString())
                    writer.write(",")
                    writer.write(if (record.isRunning) "1" else "0")
                    writer.write("\n")
                }
            }
            if (!tmpFile.renameTo(file)) {
                // 个别文件系统不支持 rename 覆盖，退化为直接写回。
                file.writeText(tmpFile.readText())
                tmpFile.delete()
            }
        } catch (e: IOException) {
            // 保存失败不打断用户操作，仅提示；内存中的计时状态仍然有效。
            failed = true
        }
    }

    /** 供调用方判断最近一次保存是否失败。 */
    var failed: Boolean = false
        private set

    fun clear() {
        file.delete()
        tmpFile.delete()
    }

    /** id 列的合理区间（毫秒时间戳量级）：用来把「名称里恰好有段数字」的极老行挡回旧格式。 */
    private companion object {
        const val MIN_PLAUSIBLE_ID = 1_000_000_000_000L  // ≈ 2001-09
        const val MAX_PLAUSIBLE_ID = 99_999_999_999_999L // ≈ 公元 5138 年，够用到天荒地老
    }

    private fun parseLine(rawLine: String): StopwatchRecord? {
        val line = rawLine.trimEnd('\r', '\n')
        if (line.isBlank()) return null

        val fields = splitFields(line)
        if (fields.size < 4) return null

        fun numAt(index: Int): Long? = fields[index].trim().toLongOrNull()
        fun flagAt(index: Int): Int? = fields[index].trim().toIntOrNull()?.takeIf { it == 0 || it == 1 }

        if (fields.size >= 5) {
            // ---- 尝试 1：现行格式 `名称,id,创建,暂停,运行`（运行标志恒在最后一列）----
            // id 位必须数值可解析**且像时间戳**：极老数据名称未转义时可能多出逗号段，
            // 把名称里的数字段误认成 id（1e12 起的门槛挡住它，让那行落进旧 4 列处理）。
            val id = numAt(fields.size - 4)?.takeIf { it in MIN_PLAUSIBLE_ID..MAX_PLAUSIBLE_ID }
            val created = numAt(fields.size - 3)
            val elapsed = numAt(fields.size - 2)
            val running = flagAt(fields.size - 1)
            if (id != null && created != null && elapsed != null && running != null) {
                val name = fields.subList(0, fields.size - 4).joinToString(",")
                return StopwatchRecord(
                    name.ifBlank { fallbackName }, created, elapsed, running == 1, id
                )
            }

            // ---- 尝试 2：修复 2.0.12 开发版写出的坏序 `名称,创建,暂停,运行,id` ----
            // 那一版把 id 追加在末尾，而读取仍按「运行标志在最后一列」解析，
            // id（13 位时间戳）转 Int 溢出导致整行被丢弃 —— 就是「写一次就丢数据」的事故。
            // 判别特征：末列是大时间戳而不是 0/1，且倒数第二列恰为 0/1。
            val badOrderId = numAt(fields.size - 1)?.takeIf { it in MIN_PLAUSIBLE_ID..MAX_PLAUSIBLE_ID }
            val badOrderRunning = flagAt(fields.size - 2)
            val badOrderElapsed = numAt(fields.size - 3)
            val badOrderCreated = numAt(fields.size - 4)
            if (badOrderId != null && badOrderRunning != null &&
                badOrderElapsed != null && badOrderCreated != null
            ) {
                val name = fields.subList(0, fields.size - 4).joinToString(",")
                return StopwatchRecord(
                    name.ifBlank { fallbackName },
                    badOrderCreated, badOrderElapsed, badOrderRunning == 1, badOrderId
                )
            }
            // 两种 5 列都对不上 → 按旧 4 列（名称未转义含逗号）落下去
        }

        // ---- 旧 4 列：从右往左 运行标志、暂停累计、创建时间戳；其余全当名称；id=创建时间戳 ----
        val running = flagAt(fields.size - 1) ?: return null
        val elapsed = numAt(fields.size - 2) ?: return null
        val created = numAt(fields.size - 3) ?: return null
        val name = fields.subList(0, fields.size - 3).joinToString(",").ifBlank { fallbackName }
        return StopwatchRecord(name, created, elapsed, running == 1, created)
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
 * 复合秒表：多路独立计时。
 *
 * 相比旧版的改动：
 *  1. 由「每个秒表各持一个 Handler」改为「整页一个 Handler 统一刷新」，
 *     卡片增多时不再线性增加定时器数量（旧版每加一个秒表就多一个常驻 100ms 回调）；
 *  2. 「全部暂停」「全部继续」是两颗常驻按钮（用户要求：同时存在更符合直觉），
 *     按下时以当前实情为准，不再依赖可能与现实脱节的 isAllPaused 变量；
 *  3. 暂停/继续给了显式按钮，同时保留“点击时间也能切换”的老习惯；
 *  4. 空列表有明确引导，重置/删除均有确认；
 *  5. 弹窗改用自绘的 AppDialog，文案与配色都跟随主题与语言；
 *  6. 新增「显示十分之一秒」「新建秒表自动开始计时」设置。
 */
class miaobiao : BaseActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var fabAdd: TextView
    private lateinit var fabPauseAll: TextView
    private lateinit var fabResumeAll: TextView
    private lateinit var fabReset: TextView

    private val cards = mutableListOf<StopwatchCard>()
    private val handler = Handler(Looper.getMainLooper())
    private val store: StopwatchStore by lazy {
        StopwatchStore(
            File(filesDir, STOPWATCH_PATH),
            getString(R.string.stopwatch_fallback_name)
        )
    }

    private val lapStore: LapStore by lazy { LapStore(File(filesDir, LAP_PATH)) }

    /**
     * 计次数量缓存：秒表身份 id → 计次条数。
     *
     * 为什么缓存：卡片刷新节拍是 100ms（为了十分之一秒跳得均匀），
     * 若每次刷新都去读一遍 laps.csv，等于每秒读 10 次文件 —— 毫无必要且费电。
     * 计次只会在专注页里增减，因此只在「进页面」和「从专注页返回」时重读一次即可。
     */
    private var lapCounts: Map<Long, Int> = emptyMap()

    /**
     * 统一刷新节拍：只在存在运行中的秒表时才会被挂上。
     *
     * 100ms 是为了让最后一位（十分之一秒）跳得均匀；开启 autosize 之后，
     * 数字变长会触发一次重新测量，这个节拍下也毫无压力。
     */
    private val ticker = object : Runnable {
        override fun run() {
            cards.forEach { it.refresh() }
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    /** 上次同步过的专注页改动序号，用来判断从专注页返回时要不要重绘卡片。 */
    private var lastSeenRevision: Long = 0L

    /**
     * 页面是否处于 RESUMED。
     *
     * 只用来回答一个问题：**现在能不能动常驻通知**。
     * onCreate 里的 restore() 只是把页面骨架搭起来，此时卡片列表还是「未经 onResume 校正」的
     * 中间态；若在这个时刻就发出 showNotification，紧接着 onResume 又把它撤掉，
     * 就会形成 startForegroundService → stopService 紧挨着的一对调用 ——
     * 系统会认定「没在 5 秒内 startForeground」而直接杀掉进程
     * （`ForegroundServiceDidNotStartInTimeException`，实测踩过一次）。
     * 所以规定：进入 RESUMED 之前不对通知做任何动作，onResume 里只同步一次、且状态已稳定。
     */
    private var resumed = false

    /** 通知权限申请入口。必须在 onStart 之前注册，因此放在属性初始化位置。 */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 未授权也不影响计时；这里只是把「申请原因」横幅收起，不必等它自己超时
            PermissionBanner.dismiss(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_miaobiao)

        container = findViewById(R.id.stopwatchContainer)
        emptyHint = findViewById(R.id.empty_hint)
        fabAdd = findViewById(R.id.fabAdd)
        fabPauseAll = findViewById(R.id.fabPauseAll)
        fabResumeAll = findViewById(R.id.fabResumeAll)
        fabReset = findViewById(R.id.fabReset)

        findViewById<TextView>(R.id.page_title).setText(R.string.app_stopwatch_name)
        fabAdd.setOnClickListener { addStopwatch() }
        fabPauseAll.setOnClickListener { pauseAll() }
        fabResumeAll.setOnClickListener { resumeAll() }
        fabReset.setOnClickListener { confirmResetAll() }
        // Tips 是 ImageButton（图标必须靠 scaleType 居中，TextView + drawableEnd 做不到）
        findViewById<ImageButton>(R.id.Tips).setOnClickListener { showTips() }

        restore()
    }

    override fun onResume() {
        super.onResume()
        // 设置页可能刚「清空」或「导入」过记录：此时界面里还是旧卡片，必须重新加载，
        // 否则本页 onStop 时又会把内存中的旧记录写回磁盘。
        if (PendingActions.stopwatchCleared) {
            PendingActions.stopwatchCleared = false
            reloadFromDisk()
            // 记录是在设置页被改掉的，本页只是跟着重载内存副本，
            // 期间没有走过 persist()，所以要在这里补一次组件刷新
            WidgetStopwatchProvider.refresh(this)
        }
        // ★ 置位必须在 reloadFromDisk() **之后**：上面那次重载会走
        //   refreshChrome() → syncNotification()，而此刻 resumed 仍为 false，
        //   于是那次同步被跳过。整轮 onResume 只在下面同步一次，
        //   且此时卡片列表已经是磁盘上的真实状态 —— 不会出现「先 show 再 hide」。
        resumed = true
        // 用户可能刚在设置页开关过「常驻通知」，回到本页时同步一次
        syncNotification()

        // 计次只会在专注页里增减，所以在这个时机重读一次就够了（不必每帧读盘）。
        reloadLapCounts()

        // 从专注页返回：那边改的是同一份记录（见 StopwatchBus），
        // 名字、暂停状态都可能被改过，因此重绘所有卡片；滚动位置不受影响。
        if (StopwatchBus.revision != lastSeenRevision) {
            lastSeenRevision = StopwatchBus.revision
            nameSeed = maxOf(nameSeed, cards.size)
            cards.forEach { it.syncFromRecord() }
            refreshChrome()
        }
    }

    /** 重读计次数量并刷新每张卡片的徽标。 */
    private fun reloadLapCounts() {
        lapCounts = lapStore.load().mapValues { it.value.size }
        cards.forEach { it.renderLapBadge(lapCounts[it.record.id] ?: 0) }
    }

    override fun onStop() {
        super.onStop()
        // 离开 RESUMED 后不再碰常驻通知（见 resumed 的说明）。
        // 通知本身不受影响：服务是独立的，页面不可见时它照常留着。
        resumed = false
        // 这里刻意不暂停任何计时：秒表按「起点时刻」推算用时，
        // 离开页面后继续走表才是符合直觉的行为，也正因为如此才不需要后台保活
        // （旧版曾有「退出时自动暂停」开关，会在这里把全部计时结算掉，已移除）。
        persist()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
    }

    // ==================== 持久化 ====================

    private fun persist() {
        store.save(cards.map { it.record })
        if (store.failed) {
            Toast.makeText(this, R.string.stopwatch_save_failed, Toast.LENGTH_SHORT).show()
        }
        // 组件显示的是「正在计时的秒表个数」，只能从这份 CSV 读出来，
        // 所以每一次写盘之后都要让它重算一次 —— 否则用户退出应用看到的还是旧数字。
        WidgetStopwatchProvider.refresh(this)
    }

    private fun restore() {
        val records = store.load()
        records.forEach { record -> addCard(record) }
        recordCounters(records)
        refreshChrome()
    }

    /**
     * 丢掉内存副本，完全按磁盘重建卡片列表。用于「记录在设置页被清空或导入」之后。
     *
     * ⚠ 这里必须是**真正的重载**，不能只做 `cards.clear()`：
     * 「清空」之后磁盘上确实什么都没有，清空内存恰好等于重载，所以旧写法看不出问题；
     * 但「导入」之后磁盘上有新数据，只清空内存就会得到一张空表 ——
     * 看起来像「导入没生效」，实际数据好好地躺在 CSV 里。
     * 两者走的是同一个标志位，因此必须统一成对磁盘负责的做法。
     */
    private fun reloadFromDisk() {
        cards.clear()
        container.removeAllViews()
        nameSeed = 0
        restore()
    }

    /** 记录已出现过的默认名称序号，避免新建时与既有名称重复。 */
    private var nameSeed = 0

    /**
     * 从既有名称里推断默认名已经用到第几号。
     * 前缀取自字符串资源（简中是「秒表」、繁中是「碼表」、英文是「Stopwatch」），
     * 因此换了语言也能正确接续编号。
     */
    private fun recordCounters(records: List<StopwatchRecord>) {
        val prefix = getString(R.string.stopwatch_default_name, 0).replace("0", "")
        val pattern = Regex("^" + Regex.escape(prefix) + "(\\d+)$")
        val maxIndex = records
            .mapNotNull { pattern.find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        nameSeed = maxOf(maxIndex, records.size)
    }

    // ==================== 增删改 ====================

    private fun addCard(record: StopwatchRecord) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_stopwatch, container, false)
        container.addView(view)
        val card = StopwatchCard(view, record)
        cards.add(card)
        card.refresh()
        // 新建的卡片淡入，避免列表里突然「跳」出一条
        ItemAnim.staggerIn(listOf(view))
    }

    private fun addStopwatch() {
        nameSeed++
        // 身份 id：与计时参数无关的独立主键，计次（[LapStore]）挂在它下面。
        // 必须避开现有秒表的 id 与计次文件里的一切键 —— 后者包含旧版遗留的
        // 孤儿行（resume 改键产生的），撞上会把别人的计次显示成自己的。
        val usedKeys = cards.mapTo(HashSet()) { it.record.id } + lapStore.load().keys
        var id = System.currentTimeMillis()
        while (id in usedKeys) id++
        val record = StopwatchRecord(
            name = getString(R.string.stopwatch_default_name, nameSeed),
            createdTimestamp = System.currentTimeMillis(),
            pausedElapsed = 0L,
            // 设置项「新建秒表自动开始计时」：关闭时停在 0:00，等用户手动开始
            isRunning = AppPrefs.stopwatchAutoStart,
            id = id
        )
        addCard(record)
        refreshChrome()
        persist()
        // 在用户主动操作时申请通知权限：此时系统弹窗有明确的前因后果，比冷启动就弹更易被接受
        requestNotificationPermission()
    }

    private fun removeCard(card: StopwatchCard) {
        AppDialog.confirm(
            context = this,
            title = getString(R.string.stopwatch_delete),
            message = getString(R.string.stopwatch_delete_confirm),
            positive = getString(R.string.dialog_delete),
            negative = getString(R.string.stopwatch_cancel),
            positiveIsDanger = true,
            onPositive = {
                // 先清掉这个秒表的计次再移除卡片：计次以身份 id 为归属键，
                // 秒表一旦消失，这批计次就再也读不出来了（成为永远删不掉的孤儿数据）。
                clearLapsOf(card.record.id)
                cards.remove(card)
                container.removeView(card.view)
                refreshChrome()
                persist()
            }
        )
    }

    /** 删掉某个秒表的计次（同为「读全量 → 去掉这一份 → 写回」，见 [LapStore]）。 */
    private fun clearLapsOf(id: Long) {
        val grouped = lapStore.load().toMutableMap()
        if (grouped.remove(id) == null) return
        lapStore.save(grouped)
        lapCounts = lapCounts - id
    }

    private fun confirmResetAll() {
        if (cards.isEmpty()) {
            Toast.makeText(this, R.string.stopwatch_none, Toast.LENGTH_SHORT).show()
            return
        }
        AppDialog.confirm(
            context = this,
            title = getString(R.string.stopwatch_reset_title),
            message = resources.getQuantityString(R.plurals.stopwatch_reset_confirm, cards.size, cards.size),
            positive = getString(R.string.dialog_delete),
            negative = getString(R.string.stopwatch_cancel),
            positiveIsDanger = true,
            onPositive = {
                cards.clear()
                container.removeAllViews()
                nameSeed = 0
                store.clear()
                // 所有秒表都没了，计次自然一起清掉，否则会留下读不出归属的孤儿行
                lapStore.clear()
                lapCounts = emptyMap()
                refreshChrome()
                // 这里走的是 store.clear() 而不是 persist()，组件刷新要自己补
                WidgetStopwatchProvider.refresh(this)
                Toast.makeText(this, R.string.stopwatch_reset_done, Toast.LENGTH_SHORT).show()
            }
        )
    }

    /**
     * 全部暂停：把所有还在跑的秒表一起按下暂停。
     * 与「全部继续」拆成两个常驻按钮（用户要求：两个按钮同时存在更符合直觉），
     * 不再共用一颗来回换文案。
     */
    private fun pauseAll() {
        if (cards.isEmpty()) {
            Toast.makeText(this, R.string.stopwatch_need_create, Toast.LENGTH_SHORT).show()
            return
        }
        if (cards.none { it.record.isRunning }) return
        cards.forEach { it.pause(silent = true) }
        refreshChrome()
        persist()
    }

    /** 全部继续：把所有停着的秒表一起重新跑起来。没有停着的就不动。 */
    private fun resumeAll() {
        if (cards.isEmpty()) {
            Toast.makeText(this, R.string.stopwatch_need_create, Toast.LENGTH_SHORT).show()
            return
        }
        if (cards.all { it.record.isRunning }) return
        cards.forEach { if (!it.record.isRunning) it.resume(silent = true) }
        refreshChrome()
        persist()
    }

    /** 依据真实状态刷新底部操作条与空态提示。 */
    private fun refreshChrome() {
        emptyHint.isVisible = cards.isEmpty()

        // 四颗按钮的可用态：没有秒表时全部压暗；有秒表时全部可用 ——
        // 即使「当前没有在跑的」，全部暂停点了也无害（直接不动），保持可用比
        // 时亮时暗更少一点视觉跳动。
        val alpha = if (cards.isEmpty()) DISABLED_ALPHA else 1f
        fabPauseAll.alpha = alpha
        fabResumeAll.alpha = alpha
        fabReset.alpha = alpha

        val needTicker = cards.any { it.record.isRunning }
        handler.removeCallbacks(ticker)
        if (needTicker) handler.post(ticker)

        syncNotification()
    }

    /**
     * 计时中的常驻通知。
     *
     * 只在「有秒表在跑」且用户开了开关时显示，其余情况一律收起 ——
     * 常驻通知的价值就是「离开 App 后仍能确认还在计时」，全部暂停时留着它反而变成噪声。
     *
     * 通知文案只在状态变化时推送一次，不跟着秒表跳秒：
     * 通知栏里跳动的时间既没人看，也会白白消耗电量；要看精确时间点一下就能回到本页。
     */
    private fun syncNotification() {
        // 页面还没进入 RESUMED 时一律不动通知：onCreate 里的 restore() 只是搭骨架，
        // 此刻发出的 show 可能马上被 onResume 的 hide 撤销，而
        // startForegroundService 与 stopService 挨在一起会让系统杀掉进程（见 resumed 的说明）。
        if (!resumed) return

        val runningCount = cards.count { it.record.isRunning }
        if (runningCount > 0 && AppPrefs.stopwatchNotification) {
            TimingService.showStopwatch(
                this,
                getString(R.string.app_stopwatch_name),
                resources.getQuantityString(R.plurals.notification_stopwatch_running, runningCount, runningCount)
            )
        } else {
            TimingService.hide(this)
        }
    }

    /** 申请通知权限（仅 Android 13 及以上需要）。拒绝也不影响计时，只是看不到常驻通知。 */
    private fun requestNotificationPermission() {
        if (!AppPrefs.stopwatchNotification || Notifier.canPost(this)) return
        val permission = Notifier.permissionToRequest() ?: return
        // 先说明用途再弹系统框：用户在这个时刻并不知道为什么要通知权限
        // persistent：横幅要留到用户在系统框里做出选择为止，不自动消失。
        // 收起仍由上面的 notificationPermission 回调负责（同意/拒绝都会走到）。
        PermissionBanner.show(this, R.string.perm_notification_banner, persistent = true)
        notificationPermission.launch(permission)
    }

    private fun showTips() {
        AppDialog.info(
            context = this,
            title = getString(R.string.stopwatch_tips_title),
            message = getString(R.string.stopwatch_tips_body),
            positive = getString(R.string.dialog_ok)
        )
    }

    // ==================== 单个秒表卡片 ====================

    inner class StopwatchCard(val view: View, val record: StopwatchRecord) {

        private val nameView: TextView = view.findViewById(R.id.numTextView)
        private val timeView: TextView = view.findViewById(R.id.timeTextView)
        private val statusView: TextView = view.findViewById(R.id.statusChip)
        private val lapBadge: TextView = view.findViewById(R.id.lapBadge)
        private val toggleButton: ImageButton = view.findViewById(R.id.toggleButton)
        private val resetButton: ImageButton = view.findViewById(R.id.resetButton)
        private val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)

        var name: String = record.name
            private set

        init {
            nameView.text = name
            nameView.setOnClickListener { editName() }
            // 时间文本不再切换暂停（那个操作已有右侧显式圆钮，误触代价还很大），
            // 改为「点开大图」：进入专注页整页展示这一个表，并能计次。
            timeView.setOnClickListener { openFocus() }
            toggleButton.setOnClickListener { toggle() }
            resetButton.setOnClickListener { confirmReset() }
            deleteButton.setOnClickListener { removeCard(this) }
            // ⚠ timeView 也要一起挂：它同样是「点了有反应」的控件（进专注页），
            //   之前漏了它，表现为这一行只有名字和两个圆钮按下去有反馈。
            PressFeedback.attachAll(nameView, timeView, resetButton, toggleButton, deleteButton)
            renderState()
            // 首帧就把徽标画上，避免「先没有、onResume 才冒出来」的跳动
            renderLapBadge(lapCounts[record.id] ?: 0)
        }

        /** 打开专注页。务必先把共享引用挂好，再启动 Activity。 */
        private fun openFocus() {
            StopwatchBus.attach(record)
            val index = cards.indexOf(this)
            startActivity(
                Intent(this@miaobiao, StopwatchFocus::class.java)
                    .putExtra(StopwatchFocus.EXTRA_INDEX, index)
            )
        }

        /** 当前累计毫秒数。 */
        fun elapsedMillis(): Long =
            if (record.isRunning) {
                (System.currentTimeMillis() - record.createdTimestamp) + record.pausedElapsed
            } else {
                record.pausedElapsed
            }

        fun refresh() {
            if (record.isRunning) timeView.text = format(elapsedMillis())
        }

        /**
         * 从共享记录重新同步界面。
         *
         * 专注页改的是同一份 [StopwatchRecord]，所以这里重新读一遍字段并整块重绘即可。
         * 之所以要单独一个方法：名称只存在卡片自己的 `name` 缓存里，
         * 而记录上的 `name` 已被专注页更新过，不重新取就还是旧名字。
         */
        fun syncFromRecord() {
            if (name != record.name) {
                name = record.name
                nameView.text = name
            }
            renderState()
        }

        fun toggle() {
            if (record.isRunning) pause() else resume()
        }

        /**
         * 复位（列表卡片上的快捷入口，2026-11-03 用户反馈新增）。
         *
         * 语义：时长清零 + 清空本表计次 + **转入已暂停**，名称保留。
         * 最后这条（2026-11-03 第二轮用户要求）是有意与专注页的「归零」区分开的：
         * 卡片是「管理多块表」的地方，复位意味着把这块表放回待用状态（0:00 停着），
         * 而不是让它一边归零一边继续跑 —— 否则用户刚清完零，抬头又看到时间在走。
         * ⚠ 专注页 `StopwatchFocus.confirmReset()` 仍保持「保留运行状态」（原地重计），
         *   两处不是笔误，是两种使用场景；改其中一处前请先想清楚要不要一起改。
         *
         * 确认键文案用 [R.string.stopwatch_reset_action]「复位」而不是「删除」：
         * 这个动作不删秒表，沿用「删除」会让用户以为整块表要没了。
         */
        private fun confirmReset() {
            val hasLaps = (lapCounts[record.id] ?: 0) > 0
            if (elapsedMillis() == 0L && !hasLaps) return
            AppDialog.confirm(
                context = this@miaobiao,
                title = getString(R.string.stopwatch_focus_reset),
                message = getString(R.string.stopwatch_focus_reset_confirm),
                positive = getString(R.string.stopwatch_reset_action),
                negative = getString(R.string.stopwatch_cancel),
                positiveIsDanger = true,
                onPositive = {
                    clearLapsOf(record.id)
                    record.pausedElapsed = 0L
                    // createdTimestamp 重新当「本段起点」用（计时模型决定的，见类注释）
                    record.createdTimestamp = System.currentTimeMillis()
                    // 走 pause 的「结果」而不走 pause()：后者会把已跑时长折进 pausedElapsed，
                    // 而这里要的就是归零。置停必须放在清零之后，否则 pausedElapsed 会带回旧值。
                    record.isRunning = false
                    renderLapBadge(lapCounts[record.id] ?: 0)
                    renderState()
                    // 运行态变了 → 刷新节拍与常驻通知都跟着变，必须走一遍 refreshChrome()
                    refreshChrome()
                    persist()
                }
            )
        }

        fun pause(silent: Boolean = false) {
            if (!record.isRunning) return
            // 结算：把“已经跑掉的这一段”折进 pausedElapsed。
            record.pausedElapsed += System.currentTimeMillis() - record.createdTimestamp
            record.isRunning = false
            renderState()
            if (!silent) {
                refreshChrome()
                persist()
            }
        }

        fun resume(silent: Boolean = false) {
            if (record.isRunning) return
            record.createdTimestamp = System.currentTimeMillis()
            record.isRunning = true
            renderState()
            if (!silent) {
                refreshChrome()
                persist()
            }
        }

        private fun renderState() {
            timeView.text = format(elapsedMillis())
            statusView.setText(
                if (record.isRunning) R.string.stopwatch_status_running else R.string.stopwatch_status_paused
            )
            statusView.setTextColor(
                ContextCompat.getColor(
                    this@miaobiao,
                    if (record.isRunning) R.color.accent else R.color.text_secondary
                )
            )
            statusView.setBackgroundResource(
                if (record.isRunning) R.drawable.bg_chip_running else R.drawable.bg_chip_paused
            )
            toggleButton.setImageResource(
                if (record.isRunning) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )
            toggleButton.contentDescription = getString(
                if (record.isRunning) R.string.stopwatch_pause_all else R.string.stopwatch_resume_all
            )
        }

        /** 渲染「计次 N」徽标；0 条时整块隐藏（连间距一起消失，不留空隙）。 */
        fun renderLapBadge(count: Int) {
            lapBadge.isVisible = count > 0
            if (count > 0) {
                lapBadge.text = resources.getQuantityString(R.plurals.stopwatch_lap_badge, count, count)
            }
        }

        private fun editName() {
            AppDialog.input(
                context = this@miaobiao,
                title = getString(R.string.stopwatch_rename),
                hint = getString(R.string.stopwatch_name_hint),
                initial = name,
                positive = getString(R.string.stopwatch_save),
                negative = getString(R.string.stopwatch_cancel),
                onConfirm = { newName ->
                    if (newName.isEmpty()) {
                        Toast.makeText(
                            this@miaobiao,
                            R.string.stopwatch_name_empty,
                            Toast.LENGTH_SHORT
                        ).show()
                        false
                    } else {
                        name = newName
                        nameView.text = newName
                        // 记录上也要同步：专注页读的是 record.name，不同步就会看到旧名字
                        record.name = newName
                        persist()
                        true
                    }
                }
            )
        }
    }

    /**
     * 显示格式：时:分:秒(.十分秒)。
     * 十分秒是否显示由设置决定；固定用 Locale.US 生成数字，
     * 避免个别地区的数字格式把小数点写成逗号。
     */
    private fun format(millis: Long): String {
        val hours = millis / 3_600_000
        val minutes = (millis % 3_600_000) / 60_000
        val seconds = (millis % 60_000) / 1000
        return if (AppPrefs.stopwatchShowTenths) {
            val tenths = (millis % 1000) / 100
            String.format(Locale.US, "%d:%02d:%02d.%d", hours, minutes, seconds, tenths)
        } else {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        }
    }

    companion object {
        /** 秒表记录文件：与设置页的导出/清空共用同一路径，务必保持一致。 */
        private const val STOPWATCH_PATH = "sys/stopwatches.csv"

        /** 计次文件：与专注页共用（见 [LapStore]），路径必须一致。 */
        private const val LAP_PATH = "sys/laps.csv"

        private const val TICK_INTERVAL_MS = 100L
        private const val DISABLED_ALPHA = 0.45f
    }
}
