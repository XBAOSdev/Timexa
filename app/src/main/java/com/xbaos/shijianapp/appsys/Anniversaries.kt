package com.xbaos.shijianapp.appsys

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.AppCardBuilder
import com.xbaos.shijianapp.ui.AppDialog
import com.xbaos.shijianapp.ui.ItemAnim
import com.xbaos.shijianapp.ui.PendingActions
import com.xbaos.shijianapp.ui.PressFeedback
import java.io.File
import java.util.Calendar

/**
 * 纪念日倒数。
 *
 * 交互设计上刻意「就地编辑」，不引入编辑页：
 *  · 点名称 → 重命名；点日期小字 → 重选日期（并顺便确认是否每年重复）；
 *  · 右侧两枚圆钮分别是「同步到系统日历」和「删除」。
 * 这样一屏之内就能完成全部维护，来回跳页面会让这类小工具变得很重。
 *
 * 新建流程只问两件事：名称 → 日期。默认按「每年重复」创建 ——
 * 纪念日绝大多数是周年，把少数派留给用户在日期那一行改成「仅此一次」。
 */
class Anniversaries : BaseActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyHint: TextView
    private lateinit var addButton: TextView

    private val items = mutableListOf<Anniversary>()

    private val store: AnniversaryStore by lazy {
        AnniversaryStore(File(filesDir, ANNIVERSARY_PATH))
    }

    /** 新建流程的临时名称：输入框确认后再弹日期选择器，中间要跨过一次 dismiss。 */
    private var pendingNewName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anniversaries)

        container = findViewById(R.id.anniversary_container)
        emptyHint = findViewById(R.id.anniversary_empty)
        addButton = findViewById(R.id.anniversary_add)

        findViewById<TextView>(R.id.page_title).setText(R.string.app_anniversary_name)
        addButton.setOnClickListener { startAdd() }

        reload()
    }

    /**
     * 数据可能在别处（设置页导入备份）被改过：置位则重读重画。
     * 页面本身只在 onCreate 读盘，返回栈里的旧实例靠这个标志保持与磁盘一致。
     */
    override fun onResume() {
        super.onResume()
        if (PendingActions.anniversaryChanged) {
            PendingActions.anniversaryChanged = false
            reload()
        }
    }

    // ==================== 数据 ====================

    private fun reload() {
        items.clear()
        items.addAll(store.load())
        render()
    }

    private fun persist() {
        store.save(items)
        if (store.failed) {
            toast(R.string.stopwatch_save_failed)
        }
    }

    private fun replace(old: Anniversary, new: Anniversary) {
        // 用引用相等定位：排序只影响显示顺序，不能拿显示下标去改原列表
        val index = items.indexOfFirst { it === old }
        if (index < 0) return
        items[index] = new
        persist()
        render()
    }

    // ==================== 渲染 ====================

    private fun render() {
        container.removeAllViews()
        emptyHint.isVisible = items.isEmpty()
        if (items.isEmpty()) return

        val card = LayoutInflater.from(this)
            .inflate(R.layout.item_card_group, container, false) as LinearLayout

        DayMath.sortedByUrgency(items).forEachIndexed { index, item ->
            if (index > 0) card.addView(AppCardBuilder.divider(this))
            card.addView(buildRow(card, item))
        }
        container.addView(card)
    }

    private fun buildRow(parent: ViewGroup, item: Anniversary): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_anniversary, parent, false)

        row.findViewById<TextView>(R.id.anniversary_name).apply {
            text = item.name
            setOnClickListener { rename(item) }
        }

        row.findViewById<TextView>(R.id.anniversary_date).apply {
            text = getString(
                R.string.anniversary_date_line,
                item.dateText,
                getString(
                    if (item.yearly) {
                        R.string.anniversary_every_year
                    } else {
                        R.string.anniversary_once
                    }
                )
            )
            setOnClickListener { pickDate(item, askRepeat = true) }
        }

        row.findViewById<TextView>(R.id.anniversary_days).apply {
            text = AnniversaryText.daysLabel(this@Anniversaries, item)
            setTextColor(ContextCompat.getColor(this@Anniversaries, AnniversaryText.daysColorRes(item)))
        }

        row.findViewById<ImageButton>(R.id.anniversary_sync).setOnClickListener { sync(item) }
        row.findViewById<ImageButton>(R.id.anniversary_delete).setOnClickListener { confirmDelete(item) }

        // 这一行是**动态 inflate** 出来的：BaseActivity.onContentChanged 里那趟 attachTree
        // 早在它被创建之前就跑完了，扫不到它。动态生成的行必须自己挂，否则整行点着毫无反馈。
        // ⚠ 顺序要在 setOnClickListener 之后 —— 是那一步才把 TextView 变成 clickable；
        //   而不 clickable 的 View 收不到 ACTION_UP，触觉恰好发在松手那一刻，就会一声不响。
        // ?.let 而不能直接内联：findViewById 的返回类型是可空的。
        intArrayOf(
            R.id.anniversary_name,
            R.id.anniversary_date,
            R.id.anniversary_sync,
            R.id.anniversary_delete
        ).forEach { id -> row.findViewById<View>(id)?.let { PressFeedback.attach(it) } }
        return row
    }

    // ==================== 新建 ====================

    private fun startAdd() {
        AppDialog.input(
            context = this,
            title = getString(R.string.anniversary_add),
            hint = getString(R.string.anniversary_name_hint),
            positive = getString(R.string.stopwatch_save),
            negative = getString(R.string.dialog_cancel),
            onConfirm = { name ->
                if (name.isEmpty()) {
                    toast(R.string.stopwatch_name_empty)
                    false
                } else {
                    pendingNewName = name
                    true
                }
            },
            onClosed = {
                // 只有「确认」路径会留下待用名称；取消时这里为空，不会误弹日期选择器
                pendingNewName?.let { name ->
                    pendingNewName = null
                    // 按**应用时区**取「今天」，与 DayMath 的判定基准保持一致
                    val today = AppClock.calendar()
                    showDatePicker(
                        year = today.get(Calendar.YEAR),
                        month = today.get(Calendar.MONTH) + 1,
                        day = today.get(Calendar.DAY_OF_MONTH)
                    ) { year, month, day ->
                        items.add(
                            Anniversary(
                                name = name,
                                year = year,
                                month = month,
                                day = day,
                                yearly = true
                            )
                        )
                        persist()
                        render()
                        toast(R.string.anniversary_created)
                    }
                }
            }
        )
    }

    // ==================== 编辑 ====================

    private fun rename(item: Anniversary) {
        AppDialog.input(
            context = this,
            title = getString(R.string.anniversary_rename),
            hint = getString(R.string.anniversary_name_hint),
            initial = item.name,
            positive = getString(R.string.stopwatch_save),
            negative = getString(R.string.dialog_cancel),
            onConfirm = { newName ->
                if (newName.isEmpty()) {
                    toast(R.string.stopwatch_name_empty)
                    false
                } else {
                    replace(item, item.copy(name = newName))
                    true
                }
            }
        )
    }

    /** 选定新日期后，再问一次是否每年重复 —— 用两个按钮代替新增一个复选控件。 */
    private fun pickDate(item: Anniversary, askRepeat: Boolean) {
        showDatePicker(item.year, item.month, item.day) { year, month, day ->
            val updated = item.copy(year = year, month = month, day = day)
            if (askRepeat) {
                askRepeat(updated)
            } else {
                replace(item, updated)
            }
        }
    }

    private fun askRepeat(updated: Anniversary) {
        AppDialog.confirm(
            context = this,
            title = getString(R.string.anniversary_repeat_ask),
            message = null,
            positive = getString(R.string.anniversary_every_year),
            negative = getString(R.string.anniversary_once),
            onPositive = { replace(updated, updated.copy(yearly = true)) },
            onNegative = { replace(updated, updated.copy(yearly = false)) }
        )
    }

    private fun showDatePicker(
        year: Int,
        month: Int,
        day: Int,
        onPicked: (Int, Int, Int) -> Unit
    ) {
        // 用应用时区的零点构造初值，避免把「今天」按当前时刻带进选择器而显示成昨天/明天。
        //
        // ⚠ 这里必须是 AppClock.calendar() 而不是 Calendar.getInstance()：
        // DayMath.startOf(...) 返回的是**按应用时区**算出的零点时间戳，
        // 若再用系统默认时区去解读它，手动指定了与系统跨日不同的时区时，
        // 选择器的初始日期会整体偏一天。
        val calendar = AppClock.calendar().apply {
            timeInMillis = DayMath.startOf(year, month, day)
        }
        DatePickerDialog(
            this,
            { _, pickedYear, pickedMonth, pickedDay ->
                onPicked(pickedYear, pickedMonth + 1, pickedDay)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    // ==================== 同步 / 删除 ====================

    private fun sync(item: Anniversary) {
        val inserted = CalendarSync.insert(
            this,
            item,
            getString(R.string.anniversary_sync_desc)
        )
        if (!inserted) {
            toast(R.string.anniversary_sync_no_app)
        }
    }

    private fun confirmDelete(item: Anniversary) {
        AppDialog.confirm(
            context = this,
            title = getString(R.string.anniversary_delete),
            message = getString(R.string.anniversary_delete_confirm, item.name),
            positive = getString(R.string.dialog_delete),
            negative = getString(R.string.dialog_cancel),
            positiveIsDanger = true,
            onPositive = {
                items.removeAll { it === item }
                persist()
                render()
            }
        )
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 纪念日文件。放在 sys 目录下，与秒表记录同处一级，便于用户整体备份。 */
        const val ANNIVERSARY_PATH = "sys/anniversaries.csv"
    }
}

/**
 * 首页的纪念日卡片。
 *
 * 只做「展示最近的两条 + 点击进入管理页」，不在这里提供增删改：
 * 首页的职责是「一眼看到」，维护动作集中在管理页，避免首页堆一堆入口按钮。
 */
object HomeAnniversaryCard {

    /** 首页最多展示几条，超过则说明「更多」交给管理页。 */
    private const val MAX_ROWS = 2

    data class Row(val name: CharSequence, val value: CharSequence)

    /**
     * 装配首页的纪念日卡片。
     *
     * **没有任何纪念日时整个卡片不出现** ——
     * 一条记录都还没有的时候，首页摆一行「还没有纪念日，点这里添加」其实是把
     * 一个尚未启用的功能的引导常驻在最显眼的位置，和「首页只放用户真正拥有的东西」
     * 相冲突。因此这里直接返回，容器也一并隐藏，不留空壳（否则会白占一段高度）。
     */
    fun populate(
        activity: AppCompatActivity,
        container: ViewGroup,
        rows: List<Row>,
        onOpen: () -> Unit
    ) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.isVisible = false
            return
        }
        container.isVisible = true

        val card = LayoutInflater.from(activity)
            .inflate(R.layout.item_card_group, container, false) as LinearLayout

        val rowViews = ArrayList<View>(MAX_ROWS)
        rows.take(MAX_ROWS).forEachIndexed { index, model ->
            if (index > 0) card.addView(AppCardBuilder.divider(activity))
            val row = LayoutInflater.from(activity)
                .inflate(R.layout.item_home_anniversary, card, false)
            row.findViewById<TextView>(R.id.home_anniversary_name).text = model.name
            row.findViewById<TextView>(R.id.home_anniversary_days).text = model.value
            row.setOnClickListener { onOpen() }
            PressFeedback.attach(row)
            rowViews.add(row)
            card.addView(row)
        }
        container.addView(card)
        ItemAnim.staggerIn(rowViews)
    }
}
