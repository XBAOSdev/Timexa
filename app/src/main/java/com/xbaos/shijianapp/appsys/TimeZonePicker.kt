package com.xbaos.shijianapp.appsys

import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.AppPrefs
import com.xbaos.shijianapp.ui.PressFeedback
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时区选择页。
 *
 * 形态：顶部搜索 + 按 UTC 偏移分组的完整列表；点一行即选中并返回。
 *
 * ## 为什么「常用」要置顶且与后面的分组重复
 *
 * 完整列表有 400 多条，按偏移排下来，中国的 UTC+8 会落在列表后段。
 * 把系统时区与 `Asia/Shanghai` / `UTC` 提到最前面，绝大多数用户开页即可点中；
 * 而它们在各自的偏移分组里**仍然保留** —— 否则用户按偏移浏览时
 * 会发现「UTC+8 那一组里居然没有上海」，那才更让人困惑。
 *
 * ## 为什么每行显示的是「该时区的当前时间」
 *
 * 偏移量已经写在分组标题上，逐行重复没有信息量。
 * 实时时间才是最有辨识度的一列：用户能一眼认出「我要找的就是慢 8 小时的那个」。
 * 但 400 多行每秒刷新会明显卡顿，因此 [ticker] 只更新**当前真正可见**的那十几行。
 *
 * ## 命名
 *
 * 展示名取 ID 的城市段（`Asia/Shanghai` → `Shanghai`），**不做中文本地化**：
 * 时区 ID 是国际通用标识，译成中文会让用户在与其它设备 / 文档对照时对不上号。
 */
class TimeZonePicker : BaseActivity() {

    private lateinit var search: EditText
    private lateinit var scroll: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var empty: TextView

    private val handler = Handler(Looper.getMainLooper())

    /** 已渲染的行，供 [ticker] 定位「哪些行当前可见」。 */
    private val rows = ArrayList<RowBinding>()

    private var pendingSearch: Runnable? = null

    /** 复用一个 Rect，避免每秒 400 多次分配。 */
    private val visibleRect = Rect()

    private class RowBinding(
        val root: View,
        val time: TextView,
        val format: SimpleDateFormat
    )

    private val locale: Locale
        get() = resources.configuration.locales[0] ?: Locale.getDefault()

    private val ticker = object : Runnable {
        override fun run() {
            val now = Date()
            // getGlobalVisibleRect 会考虑滚动与遮挡，是判断「这一行此刻在屏幕上吗」最省事的办法。
            // 每行只做一次父链上溯，400 行的开销远小于对 400 个 TextView 调 setText
            // （后者每个都会向上请求重排，整页会持续掉帧）。
            rows.forEach { row ->
                if (row.root.getGlobalVisibleRect(visibleRect)) {
                    row.time.text = row.format.format(now)
                }
            }
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timezone_picker)

        search = findViewById(R.id.tz_search)
        scroll = findViewById(R.id.tz_scroll)
        container = findViewById(R.id.tz_container)
        empty = findViewById(R.id.tz_empty)

        findViewById<TextView>(R.id.page_title).setText(R.string.timezone_title)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // 防抖：每敲一个字母就重建 400 多行会明显卡顿，
                // 而输入本身很快，等手停下来再渲染一次就够。
                pendingSearch?.let { handler.removeCallbacks(it) }
                val task = Runnable { render(search.text.toString()) }
                pendingSearch = task
                handler.postDelayed(task, SEARCH_DEBOUNCE_MS)
            }
        })
        search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // 搜完就收键盘：列表很长，留着键盘等于只剩半屏可视区
                hideKeyboard()
                true
            } else {
                false
            }
        }

        render("")
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ==================== 渲染 ====================

    private fun render(query: String) {
        val now = System.currentTimeMillis()
        val keyword = query.trim()
        val selectedId = AppClock.zoneId()
        val manual = !AppClock.followsSystem()

        container.removeAllViews()
        rows.clear()
        empty.isVisible = false

        // 只在未搜索时置顶「常用」：搜索时用户已有明确目标，插一段固定项反而干扰
        if (keyword.isEmpty()) {
            val common = buildList {
                add(AppClock.systemZoneId())
                addAll(ZoneCatalog.COMMON_IDS)
                if (manual) add(selectedId)
            }.distinct().mapNotNull { ZoneCatalog.item(it, now) }
            if (common.isNotEmpty()) {
                addSection(getString(R.string.timezone_group_common), common, selectedId)
            }
        }

        val groups = ZoneCatalog.filter(keyword, now)
        if (groups.isEmpty()) {
            empty.isVisible = true
            return
        }

        groups.forEach { group ->
            addSection(
                title = AppClock.offsetLabelOfMinutes(group.offsetMinutes),
                items = group.items,
                selectedId = selectedId
            )
        }
    }

    private fun addSection(title: String, items: List<ZoneCatalog.ZoneItem>, selectedId: String) {
        val titleView = TextView(this).apply {
            TextViewCompat.setTextAppearance(this, R.style.Text_Caption)
            text = title
        }
        container.addView(
            titleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                // 第一个分组不需要上间距，否则顶部会空出一截
                if (container.childCount > 0) {
                    topMargin = resources.getDimensionPixelSize(R.dimen.setting_group_gap)
                }
                bottomMargin = resources.getDimensionPixelSize(R.dimen.setting_group_title_gap)
            }
        )

        val card = LayoutInflater.from(this)
            .inflate(R.layout.item_card_group, container, false) as LinearLayout
        items.forEachIndexed { index, item ->
            if (index > 0) card.addView(divider(card))
            card.addView(buildRow(card, item, item.id == selectedId))
        }
        container.addView(card)
    }

    private fun buildRow(
        parent: ViewGroup,
        item: ZoneCatalog.ZoneItem,
        selected: Boolean
    ): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_timezone_row, parent, false)
        row.findViewById<TextView>(R.id.tz_name).text = ZoneCatalog.displayName(item.id)
        row.findViewById<TextView>(R.id.tz_id).text = item.id

        val timeView = row.findViewById<TextView>(R.id.tz_time)
        // 每行一个带自己时区的格式化器，建一次用到底（SimpleDateFormat 只在主线程用，无并发问题）
        val format = AppClock.formatter(TIME_PATTERN, locale, item.zone)
        timeView.text = format.format(Date())
        rows.add(RowBinding(row, timeView, format))

        // 用 INVISIBLE 而不是 GONE：占位保留，各行右侧的时间才会对齐成一条竖线
        row.findViewById<ImageView>(R.id.tz_check).visibility =
            if (selected) View.VISIBLE else View.INVISIBLE

        row.setOnClickListener {
            AppPrefs.timeZoneId = item.id
            // 手动指定后，「系统时区已变更」那类提示应当重新有机会出现：
            // 清掉旧的关闭记录，避免换到新时区后仍被上一次的叉掉状态压着。
            AppPrefs.timeZoneNoticeDismissedFor = null
            finish()
        }
        PressFeedback.attach(row)
        return row
    }

    private fun divider(parent: ViewGroup): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.stroke_hairline)
        ).apply {
            marginStart = resources.getDimensionPixelSize(R.dimen.divider_inset)
        }
        setBackgroundColor(getColor(R.color.stroke_soft))
    }

    // ==================== 输入法 ====================

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    companion object {
        /** 行内时间只显示到分钟：秒级变化会让整页不停抖动，且对辨认时区毫无帮助。 */
        private const val TIME_PATTERN = "HH:mm"

        private const val TICK_INTERVAL_MS = 1_000L

        private const val SEARCH_DEBOUNCE_MS = 150L
    }
}
