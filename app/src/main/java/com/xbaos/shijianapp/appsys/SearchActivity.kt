package com.xbaos.shijianapp.appsys

import android.content.Intent
import android.os.Bundle
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
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.AppCardBuilder
import com.xbaos.shijianapp.ui.AppCatalog
import com.xbaos.shijianapp.ui.AppEntry
import com.xbaos.shijianapp.ui.ItemAnim
import com.xbaos.shijianapp.ui.PressFeedback

/**
 * 全局搜索：一个输入框搜遍「功能入口」「设置项」与「桌面组件」。
 *
 * 匹配规则（三者命中其一即可）：
 *  1. 名称包含关键词（不分大小写）；
 *  2. 手写关键词串命中 —— 拼音首字母与全拼，例如 mb / miaobiao；
 *  3. 说明文字包含关键词。
 *
 * 功能数据来自 AppCatalog，因此以后新增功能只要加一条声明，
 * 搜索会自动收录，不需要改这里。
 *
 * ## 设置项的落点
 *
 * 设置页有七组、二十多行，「只跳到设置页顶部」等于让用户自己再翻一遍，
 * 搜索就白做了。因此点击设置类结果时会额外带上 [Settings.EXTRA_ANCHOR]，
 * 由设置页负责滚到那一行并让它闪两下 —— 本页只负责把「要找哪一项」传过去。
 */
class SearchActivity : BaseActivity() {

    private lateinit var input: EditText
    private lateinit var results: LinearLayout
    private lateinit var emptyView: TextView

    /**
     * 设置项的搜索入口：名称 + 所属分组 + 关键词串，点击直接进入设置页。
     *
     * `groupRes` 是必需的 —— 设置页里存在**跨分组同名**的项
     * （「首页」与「时钟立台」下各有一个「显示秒」和「24 小时制」），
     * 只显示名称的话搜索结果会出现两条一模一样的行，用户无法分辨点哪个。
     * 所以副标题不写死「设置」，而是显示分组名（首页 / 时钟立台 / …）作为消歧前缀。
     */
    private data class SettingShortcut(
        @StringRes val nameRes: Int,
        val pinyin: String,
        @StringRes val groupRes: Int
    )

    private val shortcuts = listOf(
        SettingShortcut(R.string.settings_theme, "szsd ysys theme dark mode shense", R.string.settings_group_appearance),
        SettingShortcut(R.string.settings_language, "yy yuyan language zhongwen yingyu", R.string.settings_group_appearance),
        SettingShortcut(R.string.settings_haptic, "cght chugan fantui haptic zhendong vibrate", R.string.settings_group_appearance),
        SettingShortcut(R.string.settings_timezone, "shiqu timezone tz shijian", R.string.settings_group_timezone),
        SettingShortcut(R.string.settings_timezone_mode, "shiqu moshi timezone mode", R.string.settings_group_timezone),
        SettingShortcut(R.string.settings_home_24h, "xiaoshi 24 12 hour", R.string.settings_group_home),
        SettingShortcut(R.string.settings_home_seconds, "xianshi miao seconds", R.string.settings_group_home),
        SettingShortcut(R.string.settings_home_date, "xianshi riqi date", R.string.settings_group_home),
        SettingShortcut(R.string.settings_home_anniversary, "jnr jinianri countdown anniversary", R.string.settings_group_home),
        SettingShortcut(R.string.settings_home_favorites, "cyyy changyong yingyong favorite shouye home", R.string.settings_group_home),
        SettingShortcut(R.string.settings_sw_tenths, "shifen miao tenths", R.string.settings_group_stopwatch),
        SettingShortcut(R.string.settings_sw_notification, "tongzhi notification changzhu", R.string.settings_group_stopwatch),
        SettingShortcut(R.string.settings_sw_auto_start, "zidong kaishi auto start xinjian new", R.string.settings_group_stopwatch),
        SettingShortcut(R.string.settings_pomodoro_focus, "fqsc zhuanzhu focus pomodoro", R.string.settings_group_pomodoro),
        SettingShortcut(R.string.settings_pomodoro_short, "duanxiuxi short break", R.string.settings_group_pomodoro),
        SettingShortcut(R.string.settings_pomodoro_long, "changxiuxi long break", R.string.settings_group_pomodoro),
        SettingShortcut(R.string.settings_pomodoro_rounds, "meilun round pomodoro", R.string.settings_group_pomodoro),
        SettingShortcut(R.string.settings_pomodoro_auto_next, "zidong next auto", R.string.settings_group_pomodoro),
        SettingShortcut(R.string.settings_pomodoro_vibrate, "zhendong vibrate fantie", R.string.settings_group_pomodoro),
        SettingShortcut(R.string.settings_pomodoro_notification, "tongzhi notification changzhu", R.string.settings_group_pomodoro),
        SettingShortcut(R.string.settings_clock_seconds, "xianshi miao seconds shizhong clock", R.string.settings_group_clock),
        SettingShortcut(R.string.settings_clock_24h, "xiaoshi 24 12 hour shizhong clock", R.string.settings_group_clock),
        SettingShortcut(R.string.settings_clock_anti_burn, "fangshaoping burn-in", R.string.settings_group_clock),
        SettingShortcut(R.string.settings_clock_keep_on, "changliang keep screen on", R.string.settings_group_clock),
        SettingShortcut(R.string.settings_breath_vibrate, "zhendong vibrate", R.string.settings_group_breath),
        SettingShortcut(R.string.settings_breath_duration, "shichang duration", R.string.settings_group_breath),
        SettingShortcut(R.string.settings_data_export, "daochu export zip beifen backup", R.string.settings_group_data),
        SettingShortcut(R.string.settings_data_import, "daoru import zip beifen backup huifu restore", R.string.settings_group_data),
        SettingShortcut(R.string.settings_reset, "huifu moren restore reset default", R.string.settings_group_reset),
        SettingShortcut(R.string.settings_licenses, "kaimian license kaifang", R.string.settings_group_about),
        SettingShortcut(R.string.settings_permissions, "quanxian permissions yinsi privacy", R.string.settings_group_about),
        SettingShortcut(R.string.about_this_app, "gybdyy about version banben", R.string.settings_group_about),
        SettingShortcut(R.string.changelog_title, "gxrz gengxinrizhi changelog update", R.string.settings_group_about)
    )

    /**
     * 桌面组件的搜索入口：名称 + 关键词串 + 各自的描述与图标。
     *
     * 组件不是「点开就能用」的功能，而是「先看预览、再钉到桌面」的东西，
     * 所以点击统一进入「组件」中心，由那边的预览与系统确认框接手；
     * 这里只负责让人搜得到它。
     */
    private data class WidgetShortcut(
        @StringRes val nameRes: Int,
        val pinyin: String,
        @StringRes val descRes: Int,
        val iconRes: Int
    )

    private val widgetShortcuts = listOf(
        WidgetShortcut(
            R.string.widget_center_sw_name,
            "mbzj miaobiao zujian widget stopwatch",
            R.string.widget_sw_desc,
            R.drawable.ic_timer
        ),
        WidgetShortcut(
            R.string.widget_center_anniv_name,
            "jnrzj jinianri zujian widget anniversary",
            R.string.widget_anniversary_desc,
            R.drawable.ic_event
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        input = findViewById(R.id.search_input)
        results = findViewById(R.id.search_results)
        emptyView = findViewById(R.id.search_empty)

        findViewById<TextView>(R.id.page_title).setText(R.string.search_title)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }

        // 进页即聚焦并弹键盘，少一次点击
        input.requestFocus()
        input.post { showKeyboard() }

        render("")
    }

    // ==================== 渲染 ====================

    private fun render(query: String) {
        results.removeAllViews()
        val keyword = query.trim().lowercase()

        if (keyword.isEmpty()) {
            // 未输入时不展示任何结果，只留空态提示之外的一片留白
            emptyView.isVisible = false
            return
        }

        val features = AppCatalog.all.filter { matches(keyword, it) }
        val settings = shortcuts.filter { matches(keyword, it.nameRes, it.pinyin) }
        val widgets = widgetShortcuts.filter { matches(keyword, it.nameRes, it.pinyin) }

        emptyView.isVisible = features.isEmpty() && settings.isEmpty() && widgets.isEmpty()
        if (emptyView.isVisible) {
            emptyView.text = getString(R.string.search_empty) + "\n" + getString(R.string.search_empty_tip)
            return
        }

        if (features.isNotEmpty()) {
            addSection(R.string.search_section_feature)
            AppCardBuilder.populate(this, results, features)
        }
        if (settings.isNotEmpty()) {
            addSection(R.string.search_section_setting)
            addShortcutCard(settings)
        }
        if (widgets.isNotEmpty()) {
            // 组件排在最后：搜索的主诉求通常是「打开某功能 / 改某设置」，
            // 组件是低频目标，压轴出现不挡路。
            addSection(R.string.search_section_widget)
            addWidgetCard(widgets)
        }
    }

    private fun matches(keyword: String, entry: AppEntry): Boolean {
        val name = getString(entry.nameRes).lowercase()
        if (name.contains(keyword)) return true
        if (entry.pinyin.contains(keyword)) return true
        return getString(entry.descRes).lowercase().contains(keyword)
    }

    private fun matches(keyword: String, @StringRes nameRes: Int, pinyin: String): Boolean =
        getString(nameRes).lowercase().contains(keyword) || pinyin.contains(keyword)

    private fun addSection(@StringRes titleRes: Int) {
        val title = TextView(this).apply {
            TextViewCompat.setTextAppearance(this, R.style.Text_Caption)
            setText(titleRes)
        }
        results.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                if (results.childCount > 0) {
                    topMargin = resources.getDimensionPixelSize(R.dimen.setting_group_gap)
                }
                bottomMargin = resources.getDimensionPixelSize(R.dimen.setting_group_title_gap)
            }
        )
    }

    private fun addShortcutCard(items: List<SettingShortcut>) {
        val card = LayoutInflater.from(this)
            .inflate(R.layout.item_card_group, results, false) as LinearLayout

        val rowViews = ArrayList<View>(items.size)
        items.forEachIndexed { index, shortcut ->
            if (index > 0) card.addView(AppCardBuilder.divider(this))
            val row = buildShortcutRow(card, shortcut)
            rowViews.add(row)
            card.addView(row)
        }
        results.addView(card)
        ItemAnim.staggerIn(rowViews)
    }

    private fun buildShortcutRow(parent: ViewGroup, shortcut: SettingShortcut): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_app_row, parent, false)
        row.findViewById<ImageView>(R.id.app_icon).setImageResource(R.drawable.ic_settings)
        row.findViewById<TextView>(R.id.app_name).setText(shortcut.nameRes)
        row.findViewById<TextView>(R.id.app_desc).apply {
            // 显示「分组名 · 设置」而不是写死「设置」：跨分组同名的项靠分组名消歧
            setText(
                getString(R.string.search_setting_desc_format, getString(shortcut.groupRes))
            )
            setTextColor(ContextCompat.getColor(this@SearchActivity, R.color.text_tertiary))
        }
        row.setOnClickListener {
            // 锚点直接用 titleRes：设置页每一行都以「创建它的那条字符串资源」登记，
            // 而这份 shortcuts 列表本来就是设置页的镜像、用的正是同一批资源 id，
            // 所以两边不需要额外维护一份对照表，加设置项时也不会漏配锚点。
            startActivity(
                Intent(this, Settings::class.java)
                    .putExtra(Settings.EXTRA_ANCHOR, shortcut.nameRes)
            )
        }
        PressFeedback.attach(row)
        return row
    }

    /** 组件结果卡：结构与设置卡一致，但每行用组件**自己的图标**，说明就是组件描述本身。 */
    private fun addWidgetCard(items: List<WidgetShortcut>) {
        val card = LayoutInflater.from(this)
            .inflate(R.layout.item_card_group, results, false) as LinearLayout

        val rowViews = ArrayList<View>(items.size)
        items.forEachIndexed { index, widget ->
            if (index > 0) card.addView(AppCardBuilder.divider(this))
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_app_row, card, false)
            row.findViewById<ImageView>(R.id.app_icon).setImageResource(widget.iconRes)
            row.findViewById<TextView>(R.id.app_name).setText(widget.nameRes)
            row.findViewById<TextView>(R.id.app_desc).setText(widget.descRes)
            row.setOnClickListener { startActivity(Intent(this, WidgetCenter::class.java)) }
            rowViews.add(row)
            card.addView(row)
        }
        results.addView(card)
        ItemAnim.staggerIn(rowViews)
    }

    // ==================== 输入法 ====================

    private fun showKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }
}
