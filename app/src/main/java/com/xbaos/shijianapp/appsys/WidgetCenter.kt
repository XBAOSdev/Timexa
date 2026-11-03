package com.xbaos.shijianapp.appsys

import android.appwidget.AppWidgetProvider
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.AppCardBuilder
import com.xbaos.shijianapp.ui.AppCatalog
import com.xbaos.shijianapp.ui.AppDialog
import com.xbaos.shijianapp.ui.AppEntry
import com.xbaos.shijianapp.ui.AppShortcuts
import com.xbaos.shijianapp.ui.ItemAnim
import com.xbaos.shijianapp.ui.PressFeedback

/**
 * 「组件」中心：把应用图标快捷方式与桌面小组件放到系统桌面。
 *
 * ## 为什么这个页面几乎不存状态
 *
 * 桌面上的东西**不归应用管** —— 用户随时可能在桌面上把快捷方式删掉、把组件拉大，
 * 而我们既收不到通知也不该去猜。所以这里的原则是：
 *   · 状态每次 [onResume] 现查一次（[AppShortcuts.pinnedIds]）；
 *   · 查到的结果只用来改每行右侧那行字与可用性，**不重建列表**；
 *   · 不缓存任何「我加过什么」的本地记录 —— 缓存一旦与桌面真实情况不符，
 *     用户看到的要么是「明明在桌面上却说没加」，要么是「说加了却找不到」。
 *
 * ## 应用图标快捷方式 ≠ 桌面长按菜单
 *
 * 长按图标弹出的那几条是 `@xml/shortcuts` 静态快捷方式，内容固定、用户改不了。
 * 本页做的是**固定快捷方式（pinned shortcut）**：用户自己挑一个功能，
 * 让它像独立应用一样躺在桌面上。两者的区别与实现见 [AppShortcuts]。
 *
 * ## 添加失败不是异常，是常态
 *
 * 「一键添加」依赖桌面（Launcher）实现了对应的 API，**部分定制 ROM 的桌面就是不支持**，
 * 用户自己也可能在系统弹窗上点取消。因此 [addWidget] / [addShortcut] 都有兜底：
 * 失败就给出「怎么手动加」的说明，而不是静默什么也不发生。
 *
 * ## 两个组件各自一张卡
 *
 * 秒表组件与纪念日组件的预览、添加行是同构的，见 [setupWidgetGroup]。
 * 再加第三个组件时只需要：布局里多一张卡、这里多一行绑定、多一个 previewViews。
 */
class WidgetCenter : BaseActivity() {

    /** entry.id → 行右侧的状态文本，[refreshShortcutStates] 直接改它，不重建整行。 */
    private val shortcutStates = HashMap<String, TextView>()

    /** entry.id → 整行，用于按桌面状态切换可用性。 */
    private val shortcutRows = HashMap<String, View>()

    /** 桌面是否支持「应用请求固定快捷方式」。一次创建期算好，页面存活期间不会变。 */
    private var shortcutPinSupported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_center)

        findViewById<TextView>(R.id.page_title).setText(R.string.widget_center_title)

        shortcutPinSupported = AppShortcuts.isSupported(this)

        setupWidgetGroup()
        setupAppsGroup()
    }

    override fun onResume() {
        super.onResume()
        // 预览里的内容来自真实数据 —— 用户可能刚去秒表页停了表、或刚改过纪念日再回来。
        // 重新渲染一次的成本是「两次 inflate」，比让预览与桌面上的组件对不上要划算。
        renderPreviews()
        // 用户也可能刚在桌面上把某个快捷方式删掉。重新对一遍状态：
        // 只改右侧那行字与可用性，不动列表本身（重建会把入场动画再播一遍）。
        refreshShortcutStates()
    }

    // ==================== 桌面组件 ====================

    /**
     * 两张组件卡片的「添加到桌面」行。
     *
     * 行标题统一是「添加到桌面」（用户的意图），组件名放在副标题里 ——
     * 两张卡的添加行长得一模一样，不写清楚加的是哪一个，用户只能靠猜。
     */
    private fun setupWidgetGroup() {
        bindWidgetCard(
            R.id.widget_sw_add_row,
            WidgetStopwatchProvider::class.java,
            R.string.widget_sw_desc
        )
        bindWidgetCard(
            R.id.widget_anniversary_add_row,
            WidgetAnniversaryProvider::class.java,
            R.string.widget_anniversary_desc
        )
    }

    private fun bindWidgetCard(
        rowId: Int,
        provider: Class<out AppWidgetProvider>,
        @StringRes descRes: Int
    ) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.setting_title).setText(R.string.widget_center_add)
        row.findViewById<TextView>(R.id.setting_desc).apply {
            setText(descRes)
            isVisible = true
        }
        row.setOnClickListener { addWidget(provider) }
    }

    /**
     * 把两个小组件各自按**桌面上的真实占位尺寸**渲染进对应的预览卡。
     *
     * 用的分别是两个 Provider 的 `previewViews` —— 与真正装到桌面上的是同一条渲染路径，
     * 所以预览里的数字、字号、配色与拖出去之后完全一致，不会「预览好看、装上去不一样」。
     *
     * ⚠ 尺寸**不能**用 provider 声明的 `widget_min_width`（110dp）：那只是「系统据此推算
     *   占几格」的声明值，桌面上的真实占位要大得多（实测 2×2 ≈ 172×234dp）。
     *   尺寸决定字号，用错尺寸就会出现「预览里字大、装上去字小」。
     *   估算方式与实测依据见 [WidgetPin.estimateSizePx]。
     *
     * 渲染失败时**整块收起来**而不是留一个空白框：布局根是 `match_parent`，
     * 拿不到视图就只剩一圈空白内边距，看起来像内容没加载出来。
     */
    private fun renderPreviews() {
        val size = WidgetPin.estimateSizePx(this, SPAN, SPAN)

        renderPreview(R.id.widget_sw_preview_area, R.id.widget_sw_preview_box, size) { w, h ->
            WidgetStopwatchProvider.previewViews(this, w, h)
        }
        renderPreview(
            R.id.widget_anniversary_preview_area,
            R.id.widget_anniversary_preview_box,
            size
        ) { w, h -> WidgetAnniversaryProvider.previewViews(this, w, h) }
    }

    private fun renderPreview(
        areaId: Int,
        boxId: Int,
        sizePx: IntArray,
        build: (Int, Int) -> RemoteViews
    ) {
        val area = findViewById<View>(areaId)
        val box = findViewById<FrameLayout>(boxId)

        val rendered = runCatching {
            build(sizePx[0], sizePx[1]).apply(this, box)
        }.getOrNull()

        if (rendered == null) {
            area.isVisible = false
            return
        }
        area.isVisible = true

        // 先清旧的再放新的：预览是「同一件东西的新一份快照」，不是列表，
        // 直接叠加会一层层堆上去。
        box.removeAllViews()
        box.addView(rendered, FrameLayout.LayoutParams(sizePx[0], sizePx[1]))
    }

    /**
     * 请求把某个组件钉到桌面。
     *
     * 系统会弹它自己的确认框，所以这里不再叠一层我们自己的二次确认。
     * 用户取消是正常结果，此时什么都不做（系统弹窗已经说得很清楚了），
     * 不该再弹一个「添加失败」。只有请求压根发不出去（版本不够 / 桌面不支持）
     * 才转为手动指引 —— 判定与请求都收在 [WidgetPin.requestPin] 里。
     */
    private fun addWidget(provider: Class<out AppWidgetProvider>) {
        if (!WidgetPin.requestPin(this, provider)) {
            showManualDialog(R.string.widget_center_manual_widget)
        }
    }

    // ==================== 应用快捷方式 ====================

    /**
     * 逐条铺出功能目录。
     *
     * 列表**由 [AppCatalog] 生成**而不是手写：以后新增/删除功能，这里自动跟随，
     * 不需要记得回来改一次。这与「常用应用」用的是同一份目录 —— 两个功能
     * 识别到的应用永远一致，不会一个多一个少。
     */
    private fun setupAppsGroup() {
        val card = findViewById<LinearLayout>(R.id.widget_apps)
        val rows = ArrayList<View>(AppCatalog.all.size)
        AppCatalog.all.forEachIndexed { index, entry ->
            if (index > 0) card.addView(AppCardBuilder.divider(this))

            val row = LayoutInflater.from(this).inflate(R.layout.item_shortcut_row, card, false)
            row.findViewById<ImageView>(R.id.shortcut_icon).setImageResource(entry.iconRes)
            row.findViewById<TextView>(R.id.shortcut_name).setText(entry.nameRes)
            row.findViewById<TextView>(R.id.shortcut_desc).setText(entry.descRes)
            row.setOnClickListener { addShortcut(entry) }
            // 这一行是代码动态建的，不在 setContentView 那一刻的视图树里，
            // 因此 BaseActivity 的统一挂载覆盖不到它，必须自己挂一次。
            PressFeedback.attach(row)

            shortcutRows[entry.id] = row
            shortcutStates[entry.id] = row.findViewById(R.id.shortcut_state)

            card.addView(row)
            rows.add(row)
        }
        ItemAnim.staggerIn(rows)
    }

    private fun addShortcut(entry: AppEntry) {
        val requested = shortcutPinSupported && AppShortcuts.requestPin(this, entry)
        if (!requested) showManualDialog(R.string.widget_center_manual_shortcut)
    }

    /**
     * 按桌面上的真实情况刷新每一行。
     *
     * 三种状态：
     *  1. 桌面不支持固定快捷方式 → 右侧不显示任何字样（显示「添加到桌面」是在骗人），
     *     整行仍可点，点了给手动指引；
     *  2. 已固定在桌面 → 右侧「已在桌面」，整行**禁用**（不能再点）。
     *     应用无权删除用户创建的快捷方式，所以这里除了置灰没有别的选择；
     *  3. 未添加 → 右侧强调色「添加到桌面」，可点。
     */
    private fun refreshShortcutStates() {
        val pinned = if (shortcutPinSupported) AppShortcuts.pinnedIds(this) else emptySet()
        val addedColor = ContextCompat.getColor(this, R.color.text_tertiary)
        val addColor = ContextCompat.getColor(this, R.color.accent)

        shortcutRows.forEach { (id, row) ->
            val state = shortcutStates[id] ?: return@forEach
            val added = pinned.contains(id)

            when {
                !shortcutPinSupported -> {
                    state.isVisible = false
                    row.isEnabled = true
                }
                added -> {
                    state.isVisible = true
                    state.setText(R.string.widget_center_added)
                    state.setTextColor(addedColor)
                    row.isEnabled = false
                }
                else -> {
                    state.isVisible = true
                    state.setText(R.string.widget_center_add)
                    state.setTextColor(addColor)
                    row.isEnabled = true
                }
            }
        }
    }

    // ==================== 兜底指引 ====================

    /**
     * 「一键加不上去」时给出的手动路径。
     *
     * 用 [AppDialog.info] 而不是 confirm：这里没有任何需要用户决策的事，
     * 只有一句「可以这样自己做」，所以只留一个「我知道了」。
     */
    private fun showManualDialog(@StringRes messageRes: Int) {
        AppDialog.info(
            this,
            getString(R.string.widget_center_manual_title),
            getString(messageRes),
            getString(R.string.dialog_ok)
        )
    }

    private companion object {
        /** 两个组件都是 2×2。以后加 4×1 之类的组件时，这里按 span 分别估算。 */
        const val SPAN = 2
    }
}
