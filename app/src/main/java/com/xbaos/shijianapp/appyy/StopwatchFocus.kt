package com.xbaos.shijianapp.appyy

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.appsys.WidgetStopwatchProvider
import com.xbaos.shijianapp.ui.AppCardBuilder
import com.xbaos.shijianapp.ui.AppDialog
import com.xbaos.shijianapp.ui.AppPrefs
import com.xbaos.shijianapp.ui.ItemAnim
import com.xbaos.shijianapp.ui.PressFeedback
import java.io.File
import java.util.Locale

/**
 * 单表专注页：整页只展示一个秒表，大数字 + 暂停/继续 + 计次。
 *
 * 为什么需要它：
 *  秒表卡片里的时间受卡片宽度限制只能到 36sp，而「对表、掐时间」这类用法需要
 *  一眼看清十分之一秒。做成独立页之后字号可以拉到 64sp 级别，也更接近系统时钟的秒表页。
 *  同时卡片上的时间文本因此不再承担「点击暂停」的职责 —— 那个操作已有显式的圆形按钮，
 *  把时间改成「点开看大图」远比「点一下不小心就停表」符合直觉。
 *
 * 计次是本页独有的能力：列表页的卡片没有位置放计次列表，而掐分段时间本来就属于
 * 「专注看一个表」的场景。
 *
 * 计次**会持久化**（存在独立的 `files/sys/laps.csv`，归属键是秒表的终身 id）。
 * 列表页会给有计次的秒表显示一枚「计次 N」徽标，用户点进来就能看到完整列表，
 * 因此计次不能在退出后凭空消失 —— 否则那枚徽标会指向一片空白。
 */
class StopwatchFocus : BaseActivity() {

    private lateinit var timeView: TextView
    private lateinit var titleView: TextView
    private lateinit var toggleButton: TextView
    private lateinit var lapContainer: LinearLayout
    private lateinit var lapEmpty: TextView

    private val handler = Handler(Looper.getMainLooper())

    /** 共享记录：与列表页持有的是同一个对象，见 [StopwatchBus]。 */
    private lateinit var record: StopwatchRecord

    /** 计次记录：每条是「此刻的总用时」，展示时再换算成分段用时。 */
    private val laps = mutableListOf<Long>()

    /** 专注页打开期间列表页是否发生了需要拉取的变化。 */
    private var lastRevision: Long = 0L

    private val ticker = object : Runnable {
        override fun run() {
            renderTime()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    private val store: StopwatchStore by lazy {
        StopwatchStore(
            File(filesDir, STOPWATCH_PATH),
            getString(R.string.stopwatch_fallback_name)
        )
    }

    private val lapStore: LapStore by lazy { LapStore(File(filesDir, LAP_PATH)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stopwatch_focus)

        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        if (index < 0) {
            // 没有定位信息就没什么可展示的，直接退出比显示一个空壳页更诚实
            finish()
            return
        }
        val current = StopwatchBus.current()
        if (current == null) {
            // 只有 Activity 被系统重建、而进程里的共享引用已丢时才会走到这里
            Toast.makeText(this, R.string.stopwatch_restore_failed, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        record = current
        lastRevision = StopwatchBus.revision

        // 计次先于渲染读进来：读盘是同步的小文件操作，放在这里可以让首帧就是最终样子，
        // 不会出现「先空一下、再补上几条」的跳动。
        laps.clear()
        laps.addAll(lapStore.loadFor(record.id))

        timeView = findViewById(R.id.focus_time)
        titleView = findViewById(R.id.page_title)
        toggleButton = findViewById(R.id.focus_toggle)
        lapContainer = findViewById(R.id.focus_lap_container)
        lapEmpty = findViewById(R.id.focus_lap_empty)

        // 顶栏标题 = 当前秒表的名称（不再是固定的「专注计时」）：
        // 用户在这里只想确认「我在看哪一个表」。点击标题即可重命名，
        // 与列表页「点卡片标题重命名」是同一条交互路径，
        // 因此原先右上角那行小字名称也一并去掉了。
        titleView.text = record.name
        titleView.setOnClickListener { editName() }
        // page_title 原本不可点，attachTree 扫它时还不是 clickable，必须自己挂
        PressFeedback.attach(titleView)

        val toggle = findViewById<TextView>(R.id.focus_toggle)
        val lap = findViewById<TextView>(R.id.focus_lap)
        val reset = findViewById<TextView>(R.id.focus_reset)
        toggle.setOnClickListener { toggleRunning() }
        lap.setOnClickListener { addLap() }
        reset.setOnClickListener { confirmReset() }
        PressFeedback.attachAll(toggle, lap, reset)

        renderTime()
        renderToggle()
        renderLaps()

        // 整页的入场：从上往下轻微错开淡入，避免「啪」地一下全出现
        ItemAnim.staggerIn(
            listOf(
                lapContainer,
                timeView,
                findViewById(R.id.focus_toggle),
                findViewById(R.id.focus_lap),
                findViewById(R.id.focus_reset)
            )
        )
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(ticker)
        if (record.isRunning) handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        // 离开页面前把改动落盘。计时本身是按时间戳推算的，不落盘也不会丢，
        // 但「暂停状态」是必须写下去的。
        persist()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        StopwatchBus.detach()
    }

    // ==================== 计时 ====================

    /** 已计时毫秒数。与列表页用的是同一个公式（运行中只记起点，用时现算）。 */
    private fun elapsedMillis(): Long =
        if (record.isRunning) {
            (System.currentTimeMillis() - record.createdTimestamp) + record.pausedElapsed
        } else {
            record.pausedElapsed
        }

    private fun toggleRunning() {
        if (record.isRunning) {
            record.pausedElapsed += System.currentTimeMillis() - record.createdTimestamp
            record.isRunning = false
            handler.removeCallbacks(ticker)
        } else {
            // 继续计时：把起点挪到「现在」，之前累计的时长留在 pausedElapsed 里
            record.createdTimestamp = System.currentTimeMillis()
            record.isRunning = true
            handler.post(ticker)
        }
        renderTime()
        renderToggle()
        StopwatchBus.notifyChanged { persist() }
    }

    private fun renderTime() {
        timeView.text = format(elapsedMillis())
    }

    private fun renderToggle() {
        // 本页只管一个秒表，所以用单数的「暂停 / 继续」，
        // 不能用列表页那两个 `stopwatch_pause_all` / `stopwatch_resume_all`（那是「全部」语义）。
        toggleButton.setText(
            if (record.isRunning) R.string.stopwatch_pause else R.string.stopwatch_resume
        )
    }

    // ==================== 计次 ====================

    /**
     * 记一次分段。
     *
     * 只拦「整表还停在 0」这一种无效情况；**暂停状态下允许计次** ——
     * 掐时间的真实用法往往是「跑到某点、暂停、记一下」，禁掉反而别扭。
     * 也不对「与上次相同的时刻」去重：暂停时秒表本来就不走，
     * 用户连点两下若第二下悄悄没反应，那才是更难理解的 bug。
     */
    private fun addLap() {
        val now = elapsedMillis()
        if (laps.isEmpty() && now == 0L) {
            Toast.makeText(this, R.string.stopwatch_focus_lap_empty, Toast.LENGTH_SHORT).show()
            return
        }
        laps.add(now)
        persistLaps()
        renderLaps()
    }

    /**
     * 删除单条计次。
     *
     * 删中间的某一条时**不做时间重算** —— 后面的计次仍保留各自「当时的总用时」原值。
     * 这是刻意的：计次记录的是「按下那一刻秒表走到哪」，是既成事实，
     * 不是「相对上一条的差值」。若把差值改成相对新的上一条，
     * 用户删一条就会看到其它几条的数字集体变化，反而像数据被改坏了。
     */
    private fun removeLap(index: Int) {
        if (index !in laps.indices) return
        laps.removeAt(index)
        persistLaps()
        renderLaps()
    }

    /** 清空本秒表的全部计次（带确认，避免误触抹掉整场记录）。 */
    private fun confirmClearLaps() {
        if (laps.isEmpty()) return
        AppDialog.confirm(
            context = this,
            title = getString(R.string.stopwatch_focus_lap_clear),
            message = resources.getQuantityString(R.plurals.stopwatch_focus_lap_clear_confirm, laps.size, laps.size),
            positive = getString(R.string.dialog_delete),
            negative = getString(R.string.stopwatch_cancel),
            positiveIsDanger = true,
            onPositive = {
                laps.clear()
                persistLaps()
                renderLaps()
            }
        )
    }

    /**
     * 渲染计次列表。
     *
     * 展示的是**分段用时**（本次计次与上次之间的差），而不是累计用时 ——
     * 掐时间的人关心的是「这一段跑了多久」，累计值看上面的大数字就够了。
     * 列表倒序：最近的计次在最上面，不用滚到底部去找。
     */
    private fun renderLaps() {
        lapContainer.removeAllViews()
        lapEmpty.isVisible = laps.isEmpty()
        renderLapActions()
        if (laps.isEmpty()) return

        val card = LayoutInflater.from(this)
            .inflate(R.layout.item_card_group, lapContainer, false) as LinearLayout

        val rowViews = ArrayList<View>(laps.size)
        for (index in laps.indices.reversed()) {
            if (rowViews.isNotEmpty()) card.addView(AppCardBuilder.divider(this))
            val split = if (index == 0) laps[0] else laps[index] - laps[index - 1]
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_stopwatch_lap, card, false)
            row.findViewById<TextView>(R.id.lap_index).text =
                getString(R.string.stopwatch_focus_lap_index, index + 1)
            row.findViewById<TextView>(R.id.lap_time).text = format(split)
            // 删除按钮按 laps 的真实下标回调（列表是倒序渲染的，不能用行内的循环序号）
            row.findViewById<ImageButton>(R.id.lap_delete).setOnClickListener {
                removeLap(index)
            }
            // 计次行是动态 inflate 的，attachTree 那一趟早跑完了，必须自己挂
            PressFeedback.attach(row.findViewById(R.id.lap_delete))
            rowViews.add(row)
            card.addView(row)
        }
        lapContainer.addView(card)
        ItemAnim.staggerIn(rowViews)
        // 新计次在最上方，滚回顶部让它可见
        findViewById<ScrollView>(R.id.focus_lap_scroll).scrollTo(0, 0)
    }

    /** 「清空计次」按钮只在真的有计次时出现，空列表时不占位置。 */
    private fun renderLapActions() {
        val clear = findViewById<TextView>(R.id.focus_lap_clear)
        clear.isVisible = laps.isNotEmpty()
        clear.setOnClickListener { confirmClearLaps() }
        // 挂载必须在 setOnClickListener 之后：是那一步把 TextView 变成 clickable 的，
        // 而不可点的 View 收不到 ACTION_UP —— 触觉发在松手那一刻，就会一声不响。
        PressFeedback.attach(clear)
    }

    /**
     * 重命名当前秒表（点顶栏标题触发）。
     *
     * 与列表页的 rename 同一套弹窗与校验；区别只是改完同步的位置 ——
     * 这里没有独立的名字视图，标题本身就是名字。
     */
    private fun editName() {
        AppDialog.input(
            context = this,
            title = getString(R.string.stopwatch_rename),
            hint = getString(R.string.stopwatch_name_hint),
            initial = record.name,
            positive = getString(R.string.stopwatch_save),
            negative = getString(R.string.stopwatch_cancel),
            onConfirm = { newName ->
                if (newName.isEmpty()) {
                    Toast.makeText(this, R.string.stopwatch_name_empty, Toast.LENGTH_SHORT).show()
                    false
                } else {
                    record.name = newName
                    titleView.text = newName
                    persist()
                    true
                }
            }
        )
    }

    private fun confirmReset() {
        AppDialog.confirm(
            context = this,
            title = getString(R.string.stopwatch_focus_reset),
            message = getString(R.string.stopwatch_focus_reset_confirm),
            positive = getString(R.string.dialog_delete),
            negative = getString(R.string.stopwatch_cancel),
            positiveIsDanger = true,
            onPositive = {
                // 归零 = 清掉这个表的全部计次 + 计时参数归位。
                // id 不变，计次键稳定；createdTimestamp 重新当「本段起点」用，随便改。
                clearLapsFor(record.id)
                record.pausedElapsed = 0L
                record.createdTimestamp = System.currentTimeMillis()
                laps.clear()
                renderTime()
                renderLaps()
                StopwatchBus.notifyChanged { persist() }
            }
        )
    }

    // ==================== 持久化 ====================

    /**
     * 落盘。
     *
     * 微妙之处：写的是**整个秒表列表**，而本页只持有其中一个。因此不能直接用
     * `store.save(listOf(record))` —— 那会把别的秒表全删掉。
     * 正确做法是「读出现有全部 → 用共享引用覆盖掉对应那一条 → 整体写回」，
     * 对应关系靠「记录本身是同一个对象」来识别。
     */
    private fun persist() {
        val records = store.load().toMutableList()
        // 认领本条记录：内存里是同一个引用最好；不是（列表页在别处重载过）就按
        // 身份 id 匹配 —— id 是终身不变的主键，比旧版的「名称 + 创建时刻」兜底
        // 可靠得多（名称可改、创建时刻会被 resume 改写，两者都曾不可靠）。
        val at = records.indexOfFirst { it === record || it.id == record.id }
        if (at >= 0) {
            records[at] = record
        } else {
            records.add(record)
        }
        store.save(records)
        lastRevision = StopwatchBus.revision
        // 本页能暂停/继续这个秒表，也就是能改变「正在计时的个数」——
        // 桌面组件读的正是这份 CSV，所以每次写盘后都要让它重算。
        WidgetStopwatchProvider.refresh(this)
    }

    /**
     * 计次落盘。
     *
     * 与 [persist] 同理：laps.csv 是所有秒表共用的，只能「读出全部分组 →
     * 替换掉本秒表那一份 → 整体写回」，不能只写自己这一份。
     *
     * 写完后顺带通知列表页刷新 —— 有计次/无计次会改变列表页上那枚「计次 N」徽标，
     * 不同步的话用户返回列表会看到徽标少一次或该出现没出现。
     */
    private fun persistLaps() {
        val grouped = lapStore.load().toMutableMap()
        if (laps.isEmpty()) {
            grouped.remove(record.id)
        } else {
            grouped[record.id] = laps.toList()
        }
        lapStore.save(grouped)
        StopwatchBus.notifyChanged { persist() }
    }

    /**
     * 按身份 id 删掉那份计次（「归零」与清空计次共用）。
     *
     * id 终身不变，所以不存在旧版「改 createdTimestamp 之前必须先清旧键」的
     * 时序陷阱 —— 那个陷阱正是当年孤儿计次行的来源。
     */
    private fun clearLapsFor(id: Long) {
        val grouped = lapStore.load().toMutableMap()
        if (grouped.remove(id) == null) return
        lapStore.save(grouped)
    }

    /** 与列表页完全一致的格式化，保证两处显示同一个数不会差一位。 */
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
        const val EXTRA_INDEX = "stopwatch_index"

        private const val STOPWATCH_PATH = "sys/stopwatches.csv"

        /** 计次独立存放（见 [LapStore] 的说明：不能并进 stopwatches.csv）。 */
        private const val LAP_PATH = "sys/laps.csv"

        private const val TICK_INTERVAL_MS = 60L
    }
}
