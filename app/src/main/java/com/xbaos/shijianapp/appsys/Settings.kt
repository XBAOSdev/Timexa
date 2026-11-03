package com.xbaos.shijianapp.appsys

import android.os.Bundle
// 必须起别名：本类自己就叫 Settings，直接 import android.provider.Settings 会被自身遮蔽，
// 而且代码里凡是写 Settings.xxx 都会解析到本类，编译器只会报「找不到这个成员」，很费时间。
import android.provider.Settings as SysSettings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import android.content.Intent
import android.net.Uri
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.MainActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.appyy.StopwatchStore
import com.xbaos.shijianapp.ui.AppDialog
import com.xbaos.shijianapp.ui.AppPrefs
import com.xbaos.shijianapp.ui.FeedbackKind
import com.xbaos.shijianapp.ui.Haptics
import com.xbaos.shijianapp.ui.Highlight
import com.xbaos.shijianapp.ui.ItemAnim
import com.xbaos.shijianapp.ui.PendingActions
import com.xbaos.shijianapp.ui.PressFeedback
import com.xbaos.shijianapp.ui.SegmentAnim
import java.io.File
import java.util.Date
import java.util.Locale

/**
 * 设置页。
 *
 * 分组与行全部由代码组装：每一行都要绑定「读偏好 → 写偏好 → 立即生效」三步，
 * 写在代码里比在 XML 里逐个 findViewById 再连线更不容易漏，也更好加新项。
 * 行的视觉全部走 item_setting_* 布局与 styles.xml，页面本身只负责数据。
 *
 * ## 搜索落点
 *
 * 本页是全 App 最长的一页（七组、二十多行），而全局搜索只能把用户送到页首，
 * 于是「搜得到但找不到」。因此本页接受 [EXTRA_ANCHOR]：
 * 组装时为每一行登记「titleRes → 行视图」，收到锚点后滚到那一行并让它闪两下。
 * 登记而不是查找（遍历视图比对文本）是刻意的 —— 文案会随语言变，
 * 资源 id 不会。
 */
class Settings : BaseActivity() {

    private lateinit var container: LinearLayout
    private lateinit var scroll: ScrollView

    /**
     * 行视图登记表：key 是那一行的标题字符串资源 id。
     *
     * 用 titleRes 当锚点，是因为搜索页的设置项清单（SearchActivity.shortcuts）
     * 本来就用同一批资源 id 声明，两边天然对齐，不需要再维护一张对照表。
     */
    private val anchors = HashMap<Int, View>()

    /**
     * 手选的时区 ID 在本机 tzdata 里已失效时的原 ID，用于显示一次性提示。
     * 置为非 null 即代表「本页需要显示这条提示」，用户关掉后恢复为 null。
     */
    private var invalidZoneId: String? = null

    /**
     * 「系统已关闭触感反馈」提示行的容器（含它上面那条分割线），非 null 即代表本页有这个控件。
     *
     * 之所以拿住容器的引用、而不是在 [onResume] 里整页 [rebuild]：
     * 用户去系统设置改了触感开关再回来，如果整页重建，滚动位置会被丢回页首 ——
     * 他刚在「外观」分组里操作完，回来却要重新往下翻，很突兀。
     * 这里只切换一个 View 的可见性，滚动位置纹丝不动。
     *
     * 也是为什么分割线要一起装在容器里：提示行藏起来时，那条线不能孤零零留在卡片末尾。
     */
    private var hapticHintHolder: View? = null

    /**
     * 「导入数据」的文件选择器（SAF）。
     *
     * 这一步就是「授权访问外部文件」本身：`ACTION_OPEN_DOCUMENT` 让用户**自己挑**一个文件，
     * 被挑中的那一个才临时授权给本应用读 —— 用户的选择即授权，因此
     * **不需要**申请 `READ_EXTERNAL_STORAGE`。那个权限在 API 33+ 已被拆废，
     * 且要「读整个存储」才能用，对一个「导入自己导出的备份」的需求来说远超必要。
     *
     * MIME 传「任意类型」而不是 `application/zip`：zip 在不同机型与网盘上会被标成
     * `application/octet-stream` 甚至空串（尤其从聊天工具传出来的），限定类型只会
     * 让用户在自己的文件列表里找不到那个文件。选错文件由导入后的 manifest 校验兜住
     * （见 [importBackup]），代价只是多弹一次「这不是有效的备份文件」。
     *
     * ⚠⚠ 本段注释里**不要**写出那个通配 MIME 字面量（星号-斜杠-星号）：块注释遇到
     * 「星号斜杠」就会提前结束，后面整段 KDoc 会被当成代码，报出一堆
     * `Expecting member declaration`，而真正的位置提示在几十行之后。踩过一次。
     *
     * ⚠ 必须在 Activity 创建期（字段初始化即可）就登记，不能等到点击时再注册 ——
     * `registerForActivityResult` 要求发生在 STARTED 之前。
     */
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importBackup(it) }
        }

    /** 一次性提示，避免每处都写一遍 Toast.makeText。[args] 供带占位符的文案使用。 */
    private fun toast(@StringRes res: Int, vararg args: Any) {
        Toast.makeText(this, getString(res, *args), Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        container = findViewById(R.id.settings_container)
        scroll = findViewById(R.id.settings_scroll)
        findViewById<TextView>(R.id.page_title).setText(R.string.settings_title)

        buildUi()

        val anchor = intent.getIntExtra(EXTRA_ANCHOR, 0)
        if (anchor == 0) {
            // 设置项很多，进页时让各分组卡片依次浮现，视线会被自然地带下去
            ItemAnim.staggerIn((0 until container.childCount).map { container.getChildAt(it) })
        } else {
            // 从搜索结果跳进来：直奔目标行。
            // 这里刻意**不播**入场动画 —— 那几帧里各卡片还在上下位移，
            // 滚动目标会算偏；而且用户本来就是来找某一项的，不需要先看一遍整页浮现。
            scrollToAnchor(anchor)
        }
    }

    // ==================== 搜索落点（锚点） ====================

    /**
     * 滚到锚点行并让它闪两下。
     *
     * 闪动放在滚动**停下之后**：滚动途中闪，高亮会跟着行一起移动并被当作运动模糊，
     * 用户根本来不及把「亮起来的这一行」和「我要找的那一项」对上号。
     */
    private fun scrollToAnchor(anchor: Int) {
        val row = anchors[anchor] ?: return
        scroll.post {
            // 额外让出一点上边距：目标行贴着分割线停住的话，看不到它上面那行，
            // 反而难以判断「这是哪一组里的项」。
            val lead = resources.getDimensionPixelSize(R.dimen.space_l)
            scroll.smoothScrollTo(0, (offsetWithin(row) - lead).coerceAtLeast(0))
            afterScrollSettles { Highlight.flash(row) }
        }
    }

    /** 行在滚动内容坐标系里的 y（px）。中间可能隔着卡片，所以要逐层累加 top。 */
    private fun offsetWithin(row: View): Int {
        var y = 0
        var node: View? = row
        while (node != null && node !== container) {
            y += node.top
            node = node.parent as? View
        }
        return y
    }

    /**
     * 等 `smoothScrollTo` 真的停下来再执行 [action]。
     *
     * 不用固定 `postDelayed`：ScrollView 的滚动时长随距离变化（几十 dp 与上千 dp 差一个数量级），
     * 写死一个值必然在长距离时抢跑、在短距离时白等。这里改成轮询 scrollY 是否还在变，
     * 并以 [MAX_SCROLL_POLLS] 兜底（内容不够长、一直没滚动时不能永远等下去）。
     */
    private fun afterScrollSettles(action: () -> Unit) {
        var last = Int.MIN_VALUE
        var polls = 0
        val watcher = object : Runnable {
            override fun run() {
                val y = scroll.scrollY
                if (y == last || polls++ >= MAX_SCROLL_POLLS) {
                    action()
                    return
                }
                last = y
                scroll.postDelayed(this, SCROLL_POLL_MS)
            }
        }
        scroll.postDelayed(watcher, SCROLL_POLL_MS)
    }

    // ==================== 页面组装 ====================

    private fun buildUi() {
        container.removeAllViews()
        // 行视图会被整批丢掉，锚点登记表必须一起清空，否则会指向已经脱离视图树的对象
        anchors.clear()
        detectStaleTimeZone()
        addUpdateBanner()

        addGroup(R.string.settings_group_appearance) {
            addSegment(
                card = it,
                titleRes = R.string.settings_theme,
                descRes = R.string.settings_theme_desc,
                labels = arrayOf(
                    getString(R.string.settings_theme_system),
                    getString(R.string.settings_theme_light),
                    getString(R.string.settings_theme_dark)
                ),
                selected = AppPrefs.themeMode,
                onSelect = ::applyTheme
            )
            addSegment(
                card = it,
                titleRes = R.string.settings_language,
                descRes = R.string.settings_language_desc,
                labels = arrayOf(
                    getString(R.string.settings_lang_system),
                    getString(R.string.settings_lang_simplified),
                    getString(R.string.settings_lang_traditional),
                    getString(R.string.settings_lang_english)
                ),
                selected = languageIndex(),
                onSelect = ::applyLanguage
            )
            addSwitch(
                card = it,
                titleRes = R.string.settings_haptic,
                descRes = R.string.settings_haptic_desc,
                initial = AppPrefs.hapticFeedback
                // 「拨到关的那一次刻意不发声」不再需要在调用点特判：
                // addSwitch 内部是「先写偏好、再发触觉」，而 Haptics.tap 见到偏好已关会直接跳过。
            ) { on ->
                AppPrefs.hapticFeedback = on
                // 这条提示的显隐同时取决于应用内开关，改完必须立刻重算，
                // 否则「关掉应用内开关」之后那句「系统已关闭触感反馈」还挂在那里，看着莫名其妙。
                refreshSystemHapticHint()
            }
            addSystemHapticHint(it)
        }

        addGroup(R.string.settings_group_timezone) {
            buildTimeZoneSection(it)
        }

        addGroup(R.string.settings_group_home) {
            addSwitch(it, R.string.settings_home_24h, R.string.settings_home_24h_desc, AppPrefs.home24Hour) { on ->
                AppPrefs.home24Hour = on
            }
            addSwitch(it, R.string.settings_home_seconds, null, AppPrefs.homeShowSeconds) { on ->
                AppPrefs.homeShowSeconds = on
            }
            addSwitch(it, R.string.settings_home_date, null, AppPrefs.homeShowDate) { on ->
                AppPrefs.homeShowDate = on
            }
            addSwitch(
                it,
                R.string.settings_home_anniversary,
                R.string.settings_home_anniversary_desc,
                AppPrefs.homeShowAnniversary
            ) { on ->
                AppPrefs.homeShowAnniversary = on
            }
            addSwitch(
                it,
                R.string.settings_home_favorites,
                R.string.settings_home_favorites_desc,
                AppPrefs.homeShowFavorites
            ) { on ->
                AppPrefs.homeShowFavorites = on
            }
        }

        addGroup(R.string.settings_group_stopwatch) {
            addSwitch(it, R.string.settings_sw_tenths, R.string.settings_sw_tenths_desc, AppPrefs.stopwatchShowTenths) { on ->
                AppPrefs.stopwatchShowTenths = on
            }
            addSwitch(
                it,
                R.string.settings_sw_notification,
                R.string.settings_sw_notification_desc,
                AppPrefs.stopwatchNotification
            ) { on ->
                AppPrefs.stopwatchNotification = on
            }
            addSwitch(
                it,
                R.string.settings_sw_auto_start,
                R.string.settings_sw_auto_start_desc,
                AppPrefs.stopwatchAutoStart
            ) { on ->
                AppPrefs.stopwatchAutoStart = on
            }
        }

        addGroup(R.string.settings_group_pomodoro) {
            bindMinutesRow(it, R.string.settings_pomodoro_focus, { AppPrefs.pomodoroFocusMinutes }) { minutes ->
                AppPrefs.pomodoroFocusMinutes = minutes
            }
            bindMinutesRow(it, R.string.settings_pomodoro_short, { AppPrefs.pomodoroShortBreakMinutes }) { minutes ->
                AppPrefs.pomodoroShortBreakMinutes = minutes
            }
            bindMinutesRow(it, R.string.settings_pomodoro_long, { AppPrefs.pomodoroLongBreakMinutes }) { minutes ->
                AppPrefs.pomodoroLongBreakMinutes = minutes
            }
            bindRoundsRow(it)
            addSwitch(
                it,
                R.string.settings_pomodoro_auto_next,
                R.string.settings_pomodoro_auto_next_desc,
                AppPrefs.pomodoroAutoNext
            ) { on ->
                AppPrefs.pomodoroAutoNext = on
            }
            addSwitch(it, R.string.settings_pomodoro_vibrate, null, AppPrefs.pomodoroVibrate) { on ->
                AppPrefs.pomodoroVibrate = on
            }
            addSwitch(
                it,
                R.string.settings_pomodoro_notification,
                R.string.settings_pomodoro_notification_desc,
                AppPrefs.pomodoroNotification
            ) { on ->
                AppPrefs.pomodoroNotification = on
            }
        }

        addGroup(R.string.settings_group_clock) {
            addSwitch(it, R.string.settings_clock_anti_burn, R.string.settings_clock_anti_burn_desc, AppPrefs.clockAntiBurnIn) { on ->
                AppPrefs.clockAntiBurnIn = on
            }
            addSwitch(it, R.string.settings_clock_seconds, null, AppPrefs.clockShowSeconds) { on ->
                AppPrefs.clockShowSeconds = on
            }
            addSwitch(it, R.string.settings_clock_24h, null, AppPrefs.clock24Hour) { on ->
                AppPrefs.clock24Hour = on
            }
            addSwitch(it, R.string.settings_clock_keep_on, R.string.settings_clock_keep_on_desc, AppPrefs.clockKeepScreenOn) { on ->
                AppPrefs.clockKeepScreenOn = on
            }
        }

        addGroup(R.string.settings_group_breath) {
            addSegment(
                card = it,
                titleRes = R.string.settings_breath_duration,
                descRes = R.string.settings_breath_duration_desc,
                labels = AppPrefs.BREATH_DURATIONS.map { minutes ->
                    getString(R.string.settings_breath_duration_value, minutes)
                }.toTypedArray(),
                selected = AppPrefs.breathDurationIndex(),
                onSelect = { index -> AppPrefs.breathMinutes = AppPrefs.BREATH_DURATIONS[index] }
            )
            addSwitch(it, R.string.settings_breath_vibrate, null, AppPrefs.breathVibrate) { on ->
                AppPrefs.breathVibrate = on
            }
        }

        addGroup(R.string.settings_group_data) {
            addAction(it, R.string.settings_data_export, R.string.settings_data_export_desc) { exportBackup() }
            addAction(it, R.string.settings_data_import, R.string.settings_data_import_desc) { confirmImportBackup() }
        }

        addGroup(R.string.settings_group_about) {
            addAction(
                card = it,
                titleRes = R.string.about_this_app,
                value = versionSummary()
            ) { openAbout() }
            // 更新日志：最新版本在最上面，正文支持长按复制
            addAction(it, R.string.changelog_title, R.string.changelog_title_desc) { openChangelog() }
            addAction(
                card = it,
                titleRes = R.string.settings_permissions,
                descRes = R.string.settings_permissions_desc
            ) { openPermissions() }
            addAction(card = it, titleRes = R.string.settings_licenses, descRes = R.string.settings_licenses_desc) {
                showLicenses()
            }
        }

        // 放在最后一组：这是「回退」性质的操作，不需要用户天天看到
        addGroup(R.string.settings_group_reset) {
            addAction(
                card = it,
                titleRes = R.string.settings_reset,
                descRes = R.string.settings_reset_desc
            ) { confirmResetSettings() }
        }
    }

    /** 添加一个分组：分组标题 + 分组卡片，卡片内的行由 [build] 填充。 */
    private fun addGroup(@StringRes titleRes: Int, build: (LinearLayout) -> Unit) {
        val title = TextView(this).apply {
            TextViewCompat.setTextAppearance(this, R.style.Text_Caption)
            setText(titleRes)
        }
        container.addView(
            title,
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
        build(card)
        container.addView(card)
    }

    // ==================== 发现新版本 ====================

    /**
     * 设置页顶部的「发现新版本」临时卡片。
     *
     * 判据与首页那颗小红点、以及「关于本应用」右侧的值**完全一致**
     * （缓存里的在线版本号非空、且与本地版本号不同），
     * 因此不会出现「首页提示了、设置页却没有」这种自相矛盾的状态。
     *
     * 放在第一屏最上方是必须的：更新入口（关于本应用）在整页最靠下的分组里，
     * 只靠红点把人引进来、却还要用户自己滚到底，等于没解决「进来之后不知道干什么」。
     * 用户升级后判据自然不成立，卡片会自己消失，所以不需要关闭按钮。
     */
    private fun addUpdateBanner() {
        val online = readOnlineVersion()
        if (online.isEmpty() || online == currentVersionName()) return

        val banner = LayoutInflater.from(this)
            .inflate(R.layout.item_update_banner, container, false)
        banner.findViewById<TextView>(R.id.update_banner_title)
            .setText(getString(R.string.home_new_version, online))
        banner.findViewById<TextView>(R.id.update_banner_desc)
            .setText(R.string.settings_update_banner_desc)
        // 直达「关于」：那里才是「怎么更新」的答案（下载地址 + 官网）。
        // 不在这里直接跳下载链接 —— 卡片上写的是「查看更新方式」，跳走就名不副实了。
        banner.setOnClickListener { openAbout() }
        PressFeedback.attach(banner)
        container.addView(banner)
    }

    // ==================== 时间与时区 ====================

    /**
     * 时区 ID 失效检查。
     *
     * 换机恢复、或系统 tzdata 更新移除了某个别名时，存下的 ID 可能在本机已不存在。
     * 此时改回「跟随系统」并记下来提示一次 ——
     * **绝不静默退化成 GMT**：`TimeZone.getTimeZone()` 对未知 ID 正是返回 GMT，
     * 那会让界面显示一个用户从未选择过的时区，比回退到系统更难排查。
     */
    private fun detectStaleTimeZone() {
        val id = AppPrefs.timeZoneId ?: return
        if (AppClock.isKnownId(id)) return
        AppPrefs.timeZoneId = null
        AppPrefs.timeZoneNoticeDismissedFor = null
        invalidZoneId = id
    }

    /**
     * 「时间与时区」分组的内容。三段顺序刻意如此：
     *  1. 一次性提示（有才显示）—— 放最上，因为它是「当前状态可能出乎你意料」的警告；
     *  2. 模式分段控件 —— 决定应用是跟着系统走、还是与系统解耦；
     *  3. 当前时区行 —— 显示生效值，点击进入选择页。
     */
    private fun buildTimeZoneSection(card: LinearLayout) {
        invalidZoneId?.let { stale ->
            addNotice(card, getString(R.string.timezone_notice_invalid, stale)) {
                invalidZoneId = null
                rebuild()
            }
        }
        if (AppClock.needsMismatchNotice()) {
            addNotice(
                card,
                getString(
                    R.string.timezone_notice_mismatch,
                    AppClock.systemZoneId(),
                    AppClock.zoneId()
                )
            ) {
                // 记的是「在哪个系统时区下关掉的」，而不是一个布尔值：
                // 同一系统时区下不再反复骚扰，而系统时区一旦再变，提示会自动重现。
                AppPrefs.timeZoneNoticeDismissedFor = AppClock.systemZoneId()
                rebuild()
            }
        }

        addSegment(
            card = card,
            titleRes = R.string.settings_timezone_mode,
            descRes = R.string.settings_timezone_mode_desc,
            labels = arrayOf(
                getString(R.string.settings_timezone_follow_system),
                getString(R.string.settings_timezone_manual)
            ),
            selected = if (AppClock.followsSystem()) 0 else 1,
            onSelect = { index -> applyTimeZoneMode(followSystem = index == 0) }
        )

        // 模式为「跟随系统」时，应用时区完全由系统决定，手选这一行就没有任何作用了。
        // 处理成**置灰**而不是隐藏：让用户看得见「这个能力存在，只是当前模式下用不上」，
        // 比整行消失更容易理解 —— 消失会让人以为是功能被砍了。
        addAction(
            card = card,
            titleRes = R.string.settings_timezone,
            descText = timeZoneRowDesc(),
            value = AppClock.offsetLabel(),
            enabled = !AppClock.followsSystem()
        ) { openTimeZonePicker() }
    }

    /**
     * 系统时区变化 → 就地刷新本页。
     *
     * 设置页是**唯一**需要感知「系统时区」的页面：「不一致提示」的显隐与文案都取决于它。
     * 手动指定期间系统时区变化不会改变应用时区，基类因此不会重建本页
     * （页面上的时间显示也确实该纹丝不动），所以必须靠这个钩子把提示补出来 ——
     * 否则提示只在「碰巧因为别的原因重建」时才出现，用户切成手动后改了系统时区
     * 再回来看，页面上会什么都没有，正好落进「用户搞错」的场景。
     */
    override fun onSystemZoneChanged() {
        if (isFinishing || isDestroyed || !::container.isInitialized) return
        rebuild()
    }

    private fun applyTimeZoneMode(followSystem: Boolean) {
        if (followSystem) {
            AppPrefs.timeZoneId = null
        } else if (AppPrefs.timeZoneId == null) {
            // 切到「手动指定」但还没选过具体时区 → 先落在系统时区上，
            // 避免出现「手动指定却没有值」这种无法表达的中间态。
            AppPrefs.timeZoneId = AppClock.systemZoneId()
        }
        // 模式改了，上一次「关掉提示」的记录不再适用
        AppPrefs.timeZoneNoticeDismissedFor = null
        rebuild()
    }

    private fun timeZoneRowDesc(): String =
        if (AppClock.followsSystem()) {
            getString(R.string.settings_timezone_follow_desc, AppClock.label())
        } else {
            getString(R.string.settings_timezone_manual_desc, AppClock.label())
        }

    private fun openTimeZonePicker() {
        startActivity(Intent(this, TimeZonePicker::class.java))
        // 返回时 BaseActivity 会比对应用时区、变了就重建本页 —— 不需要在这里处理结果回调。
    }

    /** 就地重建整个设置页（不重放入场动画），供时区相关状态变化后刷新。 */
    private fun rebuild() {
        buildUi()
    }

    private fun addNotice(card: LinearLayout, text: String, onDismiss: () -> Unit) {
        if (card.childCount > 0) card.addView(divider(card))
        val row = LayoutInflater.from(this).inflate(R.layout.item_setting_notice, card, false)
        row.findViewById<TextView>(R.id.notice_text).text = text
        val close = row.findViewById<ImageButton>(R.id.notice_close)
        close.setOnClickListener { onDismiss() }
        PressFeedback.attach(close)
        card.addView(row)
    }

    // ==================== 触感反馈 ====================

    /**
     * 「系统触感反馈已关闭」提示行。
     *
     * 为什么必须显示这一条：本应用的触感走 `View.performHapticFeedback()`，
     * 而它**强制**尊重系统的 `HAPTIC_FEEDBACK_ENABLED`（API 33 起连
     * `FLAG_IGNORE_GLOBAL_SETTING` 对普通应用都失效了 —— 平台不允许第三方绕过用户的触感开关）。
     * 所以系统一关，本应用把开关打开也**一点不响**。不解释一句的话，用户只会判断成
     * 「这应用的触感坏了」，然后跑去关掉应用内开关 —— 治错了地方。
     *
     * 刻意**不**在系统关闭时禁用应用内开关：那个开关管的是「本应用要不要发」，与系统是两件事；
     * 把它置灰反而更像本应用自己坏了。两条信息分开说清楚即可。
     *
     * 整行可点 → 直达系统「声音与振动」。**不做**厂商深链：各家 Activity 名不统一，
     * 写死一个必然在别的 ROM 上抛 `ActivityNotFoundException`，跳到标准入口就够了。
     */
    private fun addSystemHapticHint(card: LinearLayout) {
        // 分割线与提示行同装一个容器，理由见 hapticHintHolder 的注释
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        holder.addView(divider(holder))

        val row = LayoutInflater.from(this).inflate(R.layout.item_setting_hint, holder, false)
        row.findViewById<TextView>(R.id.setting_hint_text)
            .setText(R.string.settings_haptic_system_off)
        row.setOnClickListener { openSystemSoundSettings() }
        PressFeedback.attach(row)
        holder.addView(row)

        card.addView(holder)
        hapticHintHolder = holder
        refreshSystemHapticHint()
    }

    /**
     * 系统「触感反馈」总开关是否打开。
     *
     * ⚠ 必须 try-catch：部分定制 ROM 上这个 key 读不到，会抛 `SecurityException` /
     * `SettingNotFoundException`。读不到时按「开着」处理 —— 宁可不提示，
     * 也不要凭空报一条用户在自己设置里找不到对应项的「错误」，那比不提示更让人困惑。
     *
     * ⚠⚠ 这个 key 自 API 30 起被标记废弃，废弃理由原文是
     * 「Replaced by using VibrationAttributes.USAGE_TOUCH on vibrations. User settings are
     * applied automatically by the service and **should not be applied by individual apps**」。
     *
     * 读它的意思很明确：**不要拿它去决定「要不要振」** —— 那件事平台已经自动做了
     * （`performHapticFeedback` 自己会尊重它，见 `Haptics.tap`），我们再去判断只会重复施加。
     * 这里读它**纯粹是为了显示一句解释**（「系统关闭了，所以本应用不会振」），
     * 属于该废弃说明没有否定的用法，而且至今没有公开的替代 API 能读到这个状态。
     * 万一某个 ROM 不再维护这个 key，最坏结果也只是这句提示显示得不对 —— 不会崩、不会影响振动本身。
     */
    @Suppress("DEPRECATION")
    private fun systemHapticEnabled(): Boolean = try {
        SysSettings.System.getInt(
            contentResolver,
            SysSettings.System.HAPTIC_FEEDBACK_ENABLED,
            1
        ) != 0
    } catch (e: Exception) {
        true
    }

    /**
     * 提示行何时该出现：**应用内开着、系统却关着**。
     *
     * 应用内也关掉时不显示：那种状态下用户已经明确不要触感了，再挂一条「系统也没开」只是噪音；
     * 而「应用内开着、点了却没反应」才是真正需要解释的场合。
     */
    private fun refreshSystemHapticHint() {
        hapticHintHolder?.isVisible = AppPrefs.hapticFeedback && !systemHapticEnabled()
    }

    /**
     * 从系统设置返回时重算提示行。
     *
     * 只靠调用点（建行时、拨动应用内开关时）是不够的：用户去系统里改了开关再回来，
     * 本页不会被重建（系统开关不触发本应用的任何监听），不在这里补一次，
     * 页面就永远停在「提示还没出现」的旧样子。
     */
    override fun onResume() {
        super.onResume()
        refreshSystemHapticHint()
    }

    private fun openSystemSoundSettings() {
        try {
            startActivity(Intent(SysSettings.ACTION_SOUND_SETTINGS))
        } catch (e: Exception) {
            // 极少数 ROM 裁掉了设置入口；给一句提示，总比点了毫无反应好
            toast(R.string.settings_haptic_no_settings)
        }
    }

    // ==================== 各类型设置行 ====================

    /**
     * 「开关」型设置行。
     *
     * ## 语义触觉为什么放在 `OnCheckedChangeListener` 里
     *
     * 三条理由，第三条是决定性的：
     *  1. 那里读到的是**新值**，`TOGGLE_ON` / `TOGGLE_OFF` 的方向天然不会写反 ——
     *     而放在按压缩放的 lambda 里读到的是旧值，得取反，写反了两个常量在多数设备上
     *     波形一模一样、**几乎测不出来**，属于能潜伏很久的错；
     *  2. 无论**怎么**触发都只响一次：点整行、点开关本体、代码 `toggle()`；
     *  3. ⚠⚠ **用户直接点 Switch 本体时，挂在整行上的 `OnTouchListener` 根本收不到事件** ——
     *     触觉若只挂在整行就会漏。这正是用户实测反馈的「点设置项卡片有振动、点开关却没有」。
     *     改挂到「状态发生变化」这个时刻，就与点哪里无关了。
     *
     * 与此同时**缩放两边都要挂**（点整行挂 row、点开关本体挂 switch），但**都传 [FeedbackKind.NONE]**
     * 让它们不发声，否则会和上面那一次叠成「振两下」。
     *
     * ## 顺序：先 onChange 再发触觉
     *
     * 拨动「触感反馈」这个开关本身时，只有 `onChange` 才会把偏好写进去。
     * 先写偏好再发触觉：拨到「关」的那一刻 `Haptics.tap` 发现偏好已关、直接返回 ——
     * 用户说的是「别再振了」，若照常振一记就很讽刺；反过来拨到「开」时偏好已为 true，
     * 正好让他当场听到刚打开的效果。
     */
    private fun addSwitch(
        card: LinearLayout,
        @StringRes titleRes: Int,
        @StringRes descRes: Int?,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        if (card.childCount > 0) card.addView(divider(card))
        val row = LayoutInflater.from(this).inflate(R.layout.item_setting_switch, card, false)
        row.findViewById<TextView>(R.id.setting_title).setText(titleRes)
        row.findViewById<TextView>(R.id.setting_desc).bindDesc(descRes)

        val switch = row.findViewById<SwitchCompat>(R.id.setting_switch)
        switch.isChecked = initial
        switch.setOnCheckedChangeListener { _, value ->
            onChange(value)
            Haptics.tap(switch, if (value) FeedbackKind.toggleOn() else FeedbackKind.toggleOff())
        }
        // 整行可点即切换，避免只有开关那一小块能点
        row.setOnClickListener { switch.toggle() }
        PressFeedback.attach(row) { FeedbackKind.NONE }
        PressFeedback.attach(switch) { FeedbackKind.NONE }
        card.addView(row)
        anchors[titleRes] = row
    }

    private fun addSegment(
        card: LinearLayout,
        @StringRes titleRes: Int,
        @StringRes descRes: Int?,
        labels: Array<String>,
        selected: Int,
        haptic: (() -> Int)? = null,
        onSelect: (Int) -> Unit
    ) {
        if (card.childCount > 0) card.addView(divider(card))
        val row = LayoutInflater.from(this).inflate(R.layout.item_setting_segment, card, false)
        row.findViewById<TextView>(R.id.setting_title).setText(titleRes)
        row.findViewById<TextView>(R.id.setting_desc).bindDesc(descRes)

        val track = row.findViewById<LinearLayout>(R.id.setting_segment)
        val units = labels.mapIndexed { index, label ->
            val unit = LayoutInflater.from(this)
                .inflate(R.layout.item_segment_unit, track, false) as TextView
            unit.text = label
            unit.isSelected = index == selected
            unit
        }
        // 点击处理必须放在段单元全部生成之后：回调里要统一刷新兄弟节点的选中态，
        // 而在这个位置引用 units 本身会构成「val 在自己的初始化表达式里引用自己」，无法编译。
        units.forEachIndexed { index, unit ->
            unit.setOnClickListener {
                // 先就地更新选中态与滑块：主题切换会触发重建，但语言/时长不一定，
                // 两种情况都要让用户立刻看到自己选了哪一项。
                units.forEachIndexed { i, view -> view.isSelected = i == index }
                SegmentAnim.moveTo(track, units, index, animate = true)
                onSelect(index)
            }
            // 分段控件换档走 SEGMENT_TICK（「在一串离散选项间切换」的专用语义），
            // 不用普通点击的 VIRTUAL_KEY：后者在高频连点时手感会糊成一片。
            PressFeedback.attach(unit) { haptic?.invoke() ?: FeedbackKind.segmentTick() }
        }
        units.forEach { track.addView(it) }
        // 首帧直接落位（animate = false）：否则进页面时会看到滑块从左边缘滑过来。
        // 此刻各段还没测量，moveTo 内部会自己 post 到测量完成后再定位。
        track.post { SegmentAnim.moveTo(track, units, selected, animate = false) }
        card.addView(row)
        anchors[titleRes] = row
    }

    /**
     * 「点击整行执行一个动作」型设置行，返回右侧那个「值」文本视图。
     *
     * [onClick] 的回调参数就是**右侧值视图本身**，而不是让调用方去接返回值：
     * 调用方通常需要在动作完成后就地刷新这个值，如果直接写成
     * `val valueView = addAction(...) { ... valueView.text = ... }`，
     * 就会构成「局部 val 在自己的初始化表达式里引用自己」，无法编译。
     * 由这里把视图当参数递出去，既保留了「传值」的便利，又绕开了这个陷阱。
     *
     * [enabled] 传 false 表示「当前状态下这一项没有意义」，整行会置灰且完全不可点
     * （典型场合：时区已经选了「跟随系统」，下面那行手选时区就没有作用了）。
     * ⚠ 置灰时**不挂按压反馈** —— 否则按下去照样缩小、照样振动，却什么也不会发生，
     * 比单纯不响应更让人困惑。
     */
    private fun addAction(
        card: LinearLayout,
        @StringRes titleRes: Int,
        @StringRes descRes: Int? = null,
        descText: String? = null,
        value: String? = null,
        enabled: Boolean = true,
        onClick: (TextView) -> Unit
    ): TextView {
        if (card.childCount > 0) card.addView(divider(card))
        val row = LayoutInflater.from(this).inflate(R.layout.item_setting_action, card, false)
        row.findViewById<TextView>(R.id.setting_title).setText(titleRes)
        // descText 优先：像「时区」那种说明文字里嵌了当前生效值，没法写成静态字符串资源。
        row.findViewById<TextView>(R.id.setting_desc).bindDesc(descRes, descText)
        val valueView = row.findViewById<TextView>(R.id.setting_value)
        valueView.apply {
            isVisible = !value.isNullOrEmpty()
            text = value
        }
        if (enabled) {
            row.setOnClickListener { onClick(valueView) }
            PressFeedback.attach(row)
        } else {
            // isEnabled = false 顺带把整行移出无障碍焦点：读屏不会把一个点不动的项念成可点。
            row.isEnabled = false
            row.isClickable = false
            row.alpha = resources.getFraction(R.fraction.alpha_disabled, 1, 1)
        }
        card.addView(row)
        anchors[titleRes] = row
        return valueView
    }

    /**
     * 「分钟数」型设置行：右侧显示当前值，点击弹出候选列表，选中即写入并就地刷新该行。
     *
     * 用竖向列表而不是分段控件：时长候选有 8 档，横着排每格会窄到点不准。
     * 就地刷新而不是整页重建，是为了不让用户被弹窗打断之后又被滚回页首。
     *
     * [read] 每次点击时重新取值，而不是复用建行那一刻的快照 ——
     * 否则连续改两次，第二次弹出的列表里高亮的还是改之前的那一项。
     */
    private fun bindMinutesRow(
        card: LinearLayout,
        @StringRes titleRes: Int,
        read: () -> Int,
        apply: (Int) -> Unit
    ) {
        val values = AppPrefs.POMODORO_MINUTES
        val valueView = addAction(
            card = card,
            titleRes = titleRes,
            value = minutesLabel(read())
        ) { value ->
            AppDialog.choice(
                context = this,
                title = getString(titleRes),
                options = values.map { minutesLabel(it) },
                selected = values.indexOf(read()).takeIf { it >= 0 } ?: 0,
                negative = getString(R.string.dialog_cancel)
            ) { index ->
                apply(values[index])
                value.text = minutesLabel(values[index])
            }
        }
        valueView.text = minutesLabel(read())
    }

    /** 「每轮番茄数」型设置行：与分钟数同理，只是文案单位不同。 */
    private fun bindRoundsRow(card: LinearLayout) {
        val values = AppPrefs.POMODORO_ROUNDS
        val valueView = addAction(
            card = card,
            titleRes = R.string.settings_pomodoro_rounds,
            value = roundsLabel(AppPrefs.pomodoroRoundsPerSet)
        ) { value ->
            AppDialog.choice(
                context = this,
                title = getString(R.string.settings_pomodoro_rounds),
                options = values.map { roundsLabel(it) },
                selected = values.indexOf(AppPrefs.pomodoroRoundsPerSet).takeIf { it >= 0 } ?: 0,
                negative = getString(R.string.dialog_cancel)
            ) { index ->
                AppPrefs.pomodoroRoundsPerSet = values[index]
                value.text = roundsLabel(values[index])
            }
        }
        valueView.text = roundsLabel(AppPrefs.pomodoroRoundsPerSet)
    }

    private fun minutesLabel(minutes: Int): String =
        getString(R.string.settings_pomodoro_minutes_value, minutes)

    private fun roundsLabel(rounds: Int): String =
        getString(R.string.settings_pomodoro_rounds_value, rounds)

    /**
     * 设置说明行的内容绑定。
     * [text] 优先于 [descRes]：有些说明需要嵌当前生效值（例如时区），静态资源写不出来。
     */
    private fun TextView.bindDesc(@StringRes descRes: Int?, text: String? = null) {
        val resolved = text ?: descRes?.let { getString(it) }
        isVisible = !resolved.isNullOrEmpty()
        if (resolved != null) setText(resolved)
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

    // ==================== 即时生效的设置 ====================

    /**
     * 深浅模式。
     * setDefaultNightMode 会让当前页面自动重建，界面无需手动刷新；
     * 若目标模式与当前一致则不重建，此时分段控件的选中态已在点击处就地更新。
     */
    private fun applyTheme(index: Int) {
        if (AppPrefs.themeMode == index) return
        AppPrefs.themeMode = index
        AppCompatDelegate.setDefaultNightMode(
            when (index) {
                AppPrefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppPrefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /**
     * 语言。
     * recreate 之后 BaseActivity.attachBaseContext 会用新的 Locale 包装 Context，
     * 资源随之切换到对应语言；返回栈里的其它页面会在下一次可见时自行重建。
     */
    private fun applyLanguage(index: Int) {
        val tag = LANGUAGE_TAGS[index]
        if (AppPrefs.language == tag) return
        AppPrefs.language = tag
        recreate()
    }

    private fun languageIndex(): Int =
        LANGUAGE_TAGS.indexOf(AppPrefs.language).takeIf { it >= 0 } ?: 0

    // ==================== 数据操作 ====================

    /**
     * 导出**全部个性化数据**为一个 zip 备份（秒表记录、计次、纪念日、设置项）。
     *
     * 与旧版「只导秒表 csv」的区别：常用应用、主题、语言、时区这些散在偏好里的
     * 个性化项也是用户调出来的状态，换机/重装时同样不想重来。
     * 打包细节见 [BackupZip]；文件写在外部私有目录，用户可以随意拷走。
     */
    private fun exportBackup() {
        // 文件名里的时间戳同样走 AppClock：应与用户选定的时区一致（旧版注释沿用）
        val stamp = AppClock.formatter("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val target = File(getExternalFilesDir(null) ?: filesDir, "shijianapp_backup_$stamp.zip")
        val result = runCatching {
            BackupZip.export(
                context = this,
                target = target,
                stopwatchCsv = File(filesDir, STOPWATCH_PATH),
                lapCsv = File(filesDir, LAP_PATH),
                anniversaryCsv = File(filesDir, Anniversaries.ANNIVERSARY_PATH),
                settingsSnapshot = AppPrefs.exportForBackup()
            )
        }

        AppDialog.info(
            this,
            getString(R.string.settings_data_export),
            getString(
                if (result.isSuccess) {
                    R.string.settings_data_export_done
                } else {
                    R.string.settings_data_export_fail
                },
                target.absolutePath
            ),
            getString(R.string.dialog_ok)
        )
    }

    /**
     * 导入前先确认一次。导入是**覆写**：秒表记录、纪念日、设置项都会被备份里的
     * 内容覆盖且不可恢复，与旧版同样按破坏性操作走 danger 态确认弹窗。
     */
    private fun confirmImportBackup() {
        AppDialog.confirm(
            context = this,
            title = getString(R.string.settings_data_import),
            message = getString(R.string.settings_data_import_confirm),
            positive = getString(R.string.settings_data_import),
            negative = getString(R.string.dialog_cancel),
            positiveIsDanger = true,
            onPositive = { importLauncher.launch(arrayOf("*/*")) }
        )
    }

    /**
     * 应用一个 zip 备份。
     *
     * ## 「先校验后落盘」
     *
     * [BackupZip.read] 已经把全部条目读进了内存并完成 manifest 校验
     * （是不是本应用的包、版本是否可导入），任何不通过都不会碰本地文件；
     * 秒表 csv 的可解析校验发生在写完之后，失败就把旧内容写回去 ——
     * 与旧版 csv 导入同一条安全思路，只是覆盖面从一份数据扩大到全部。
     *
     * ## 版本策略
     *
     * 备份里**缺席**的条目不动本地对应数据；设置项只覆盖快照里出现的键。
     * 所以低版本备份导入高版本应用，新增偏好好好地保持出厂默认。
     */
    private fun importBackup(uri: Uri) {
        val contents = try {
            contentResolver.openInputStream(uri)?.use { BackupZip.read(it, packageName) }
                ?: throw IOException("openInputStream returned null")
        } catch (e: BackupZip.SchemaTooNewException) {
            AppDialog.info(
                this,
                getString(R.string.settings_data_import),
                getString(R.string.settings_data_import_newer),
                getString(R.string.dialog_ok)
            )
            return
        } catch (e: IOException) {
            AppDialog.info(
                this,
                getString(R.string.settings_data_import),
                getString(R.string.settings_data_import_invalid),
                getString(R.string.dialog_ok)
            )
            return
        }

        val entries = contents.entries

        // ---- 秒表记录：写 → 校验 → 不合格回滚（旧版机制，原样保留） ----
        val stopwatchTarget = File(filesDir, STOPWATCH_PATH)
        val stopwatchBytes = entries[BackupZip.ENTRY_STOPWATCHES]
        if (stopwatchBytes != null) {
            val store = StopwatchStore(stopwatchTarget, getString(R.string.stopwatch_fallback_name))
            val previous = runCatching { stopwatchTarget.takeIf { it.exists() }?.readText() }.getOrNull()
            val written = runCatching {
                stopwatchTarget.parentFile?.mkdirs()
                stopwatchTarget.writeBytes(stopwatchBytes)
            }.isSuccess
            val valid = written && store.load().isNotEmpty()
            if (!valid) {
                if (previous != null) {
                    runCatching { stopwatchTarget.writeText(previous) }
                } else {
                    runCatching { stopwatchTarget.delete() }
                }
                AppDialog.info(
                    this,
                    getString(R.string.settings_data_import),
                    getString(R.string.settings_data_import_invalid),
                    getString(R.string.dialog_ok)
                )
                return
            }
        }

        // ---- 计次 / 纪念日：纯文本直写（缺条目 = 备份里没有，保持原样） ----
        entries[BackupZip.ENTRY_LAPS]?.let { bytes ->
            runCatching {
                File(filesDir, LAP_PATH).parentFile?.mkdirs()
                File(filesDir, LAP_PATH).writeBytes(bytes)
            }
        }
        entries[BackupZip.ENTRY_ANNIVERSARIES]?.let { bytes ->
            runCatching {
                File(filesDir, Anniversaries.ANNIVERSARY_PATH).parentFile?.mkdirs()
                File(filesDir, Anniversaries.ANNIVERSARY_PATH).writeBytes(bytes)
            }
        }

        // ---- 设置项 ----
        entries[BackupZip.ENTRY_SETTINGS]?.let { bytes ->
            AppPrefs.importFromBackup(String(bytes, Charsets.UTF_8))
        }

        // ---- 生效 ----
        // 秒表页可能还在返回栈里：置位让它下次可见时重新读盘（与旧版同机制）
        PendingActions.stopwatchCleared = true
        // 纪念日页只有 onCreate 时读盘：置位让它下次可见时重载（本轮新加的机制）
        PendingActions.anniversaryChanged = true
        WidgetStopwatchProvider.refresh(this)

        // 主题与语言是进程级生效的项，导入后立即重新应用（做法同「恢复默认设置」）
        when (AppPrefs.themeMode) {
            AppPrefs.THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            AppPrefs.THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        recreate()
        toast(R.string.settings_data_import_done)
    }


    private fun showLicenses() {
        AppDialog.info(
            this,
            getString(R.string.settings_licenses),
            getString(R.string.settings_licenses_body),
            getString(R.string.dialog_ok)
        )
    }

    // ==================== 恢复默认设置 ====================

    /**
     * 恢复默认设置。
     *
     * 只重置设置项，不动用户数据（秒表记录、纪念日都不住在 SharedPreferences 里）。
     * 麻烦的是「主题」与「语言」这两项是进程级生效的，光清空偏好界面不会变：
     *  - 夜间模式由 AppCompatDelegate 全局持有，必须显式拨回「跟随系统」——
     *    这一步会让 AppCompat 自己重建当前页面，语言与所有开关随之刷新；
     *  - 如果代理模式本来就是「跟随系统」，就不会有那次自动重建，
     *    此时手动 recreate 一次，否则语言分段控件还停在旧选项上。
     */
    private fun confirmResetSettings() {
        AppDialog.confirm(
            context = this,
            title = getString(R.string.settings_reset),
            message = getString(R.string.settings_reset_confirm),
            positive = getString(R.string.settings_reset_action),
            negative = getString(R.string.dialog_cancel),
            positiveIsDanger = true,
            onPositive = {
                val nightModeNeedsReset =
                    AppCompatDelegate.getDefaultNightMode() != AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppPrefs.resetAll()
                if (nightModeNeedsReset) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                } else {
                    recreate()
                }
            }
        )
    }

    // ==================== 关于 ====================

    /**
     * 「关于本应用」右侧显示的版本值。
     * 在线版本号由首页检测后缓存，这里的路径必须与 MainActivity 保持一致（只读）。
     */
    private fun versionSummary(): String {
        val current = currentVersionName()
        val online = readOnlineVersion()
        return if (online.isNotEmpty() && online != current) {
            getString(R.string.home_new_version, online)
        } else {
            current.ifEmpty { getString(R.string.about_unknown_version) }
        }
    }

    /** 本机版本号。取不到时返回空串，调用方一律按「无版本信息」处理。 */
    private fun currentVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun openAbout() {
        startActivity(
            Intent(this, About_App::class.java)
                .putExtra(MainActivity.EXTRA_NEW_VERSION, readOnlineVersion())
        )
    }

    private fun openChangelog() {
        startActivity(Intent(this, Changelog::class.java))
    }

    /** 权限说明页是纯静态内容，不需要任何参数。 */
    private fun openPermissions() {
        startActivity(Intent(this, Permissions::class.java))
    }

    /**
     * 读在线版本号缓存（只读，不发请求）。
     *
     * ⚠ 必须 trim：判据是「与本地版本号是否**相等**」，而文件里多一个尾换行
     * （任何用 `echo` 之类写入过的痕迹、或将来写入侧换了实现）就会让比较永远不成立，
     * 表现是「关于本应用」和顶部卡片永远显示有新版本，怎么升级都不会消失。
     * 首页的同类读取（MainActivity.readCachedVersion）本来就是 trim 的，这里与其对齐。
     */
    private fun readOnlineVersion(): String = runCatching {
        val file = File(filesDir, VERSION_CACHE_PATH)
        if (file.exists()) file.readText().trim() else ""
    }.getOrDefault("")

    companion object {
        /**
         * 搜索页传来的「要定位到哪一行」，值是该行标题的字符串资源 id。
         * 资源 id 只在同一个 APK 内传递有意义 —— 本页与搜索页同属一个应用、一个进程，成立。
         */
        const val EXTRA_ANCHOR = "settings_anchor"

        /** 滚动停稳判定的轮询间隔与次数上限（约 2.4s 还没有停就直接执行）。 */
        private const val SCROLL_POLL_MS = 60L
        private const val MAX_SCROLL_POLLS = 40

        /** 秒表记录文件：与 miaobiao 共用同一路径，务必保持一致。 */
        private const val STOPWATCH_PATH = "sys/stopwatches.csv"

        /** 计次记录（归属键是秒表的创建时间戳）。与 miaobiao.kt 里的声明保持一致。 */
        private const val LAP_PATH = "sys/laps.csv"

        /** 版本号在线结果缓存：与 MainActivity 共用同一路径，务必保持一致。 */
        private const val VERSION_CACHE_PATH = "sys/lastversion"

        /** 下标与分段控件一一对应。 */
        private val LANGUAGE_TAGS = arrayOf(
            AppPrefs.LANG_SYSTEM,
            AppPrefs.LANG_ZH_CN,
            AppPrefs.LANG_ZH_TW,
            AppPrefs.LANG_EN
        )
    }
}
