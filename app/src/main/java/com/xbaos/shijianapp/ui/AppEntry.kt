package com.xbaos.shijianapp.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.appyy.WebPages

/** 内置网页资源目录（WebView 直接读 assets，不走网络）。 */
private const val WEB_ASSET_BASE = "file:///android_asset/Web_app/"

/**
 * 一个可启动的功能条目。
 *
 * @param id       持久化用的**稳定 ID**。会写进「常用应用」的偏好与桌面快捷方式，
 *                 因此**一旦发布就不可更改** —— 改了等于把用户的配置全部作废。
 *                 取值用短横线小写（`stopwatch` / `datecalc`），不要用资源名。
 * @param target   原生页面；纯网页条目可以留空
 * @param webPage  assets/Web_app 下的文件名；非空时用 WebView 打开
 * @param pinyin   检索用的关键词串（拼音首字母 + 全拼，空格分隔）。
 *                 手写而不是引入拼音库：条目一共十来条，引入依赖不划算，
 *                 而且手写可以顺带收录「俗称」（例如秒表对应 mb / miaobiao）。
 */
data class AppEntry(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descRes: Int,
    @DrawableRes val iconRes: Int,
    val pinyin: String,
    val target: Class<*>? = null,
    val webPage: String? = null
)

/**
 * 功能总目录。
 *
 * 搜索页需要遍历「全部功能」，如果让各页面各自维护列表，
 * 搜索就会漏掉新加的功能，因此集中在这一处声明，页面只做筛选。
 */
object AppCatalog {

    /** 应用集：以「工具」为主。 */
    val apps = listOf(
        AppEntry(
            id = "clock",
            nameRes = R.string.app_clock_name,
            descRes = R.string.app_clock_desc,
            iconRes = R.drawable.ic_clock_face,
            pinyin = "szlt sz shizhonglitai shizhong litai clock",
            target = com.xbaos.shijianapp.appyy.clocklitai::class.java
        ),
        AppEntry(
            id = "stopwatch",
            nameRes = R.string.app_stopwatch_name,
            descRes = R.string.app_stopwatch_desc,
            iconRes = R.drawable.ic_timer,
            pinyin = "fhmb mb miaobiao fuhemiaobiao stopwatch",
            target = com.xbaos.shijianapp.appyy.miaobiao::class.java
        ),
        AppEntry(
            id = "pomodoro",
            nameRes = R.string.app_pomodoro_name,
            descRes = R.string.app_pomodoro_desc,
            iconRes = R.drawable.ic_hourglass,
            pinyin = "fqz fq fanqiezhong fanqie pomodoro tomato",
            target = com.xbaos.shijianapp.appyy.Pomodoro::class.java
        ),
        AppEntry(
            id = "datecalc",
            nameRes = R.string.app_calc_name,
            descRes = R.string.app_calc_desc,
            iconRes = R.drawable.ic_calendar,
            pinyin = "rqjsq rq riqi jisuanqi date calculator",
            target = com.xbaos.shijianapp.appyy.CalculatorDate::class.java
        ),
        AppEntry(
            id = "anniversary",
            nameRes = R.string.app_anniversary_name,
            descRes = R.string.app_anniversary_desc,
            iconRes = R.drawable.ic_event,
            pinyin = "jnr jn jinianri daoshu countdown anniversary",
            target = com.xbaos.shijianapp.appsys.Anniversaries::class.java
        ),
        AppEntry(
            id = "convert",
            nameRes = R.string.app_convert_name,
            descRes = R.string.app_convert_desc,
            iconRes = R.drawable.ic_swap_horiz,
            pinyin = "sjhs sj shijian huansuan convert time converter",
            webPage = "time_converter.html"
        ),
        AppEntry(
            id = "sun",
            nameRes = R.string.app_sun_name,
            descRes = R.string.app_sun_desc,
            iconRes = R.drawable.ic_sun,
            pinyin = "tyqk ty taiyang qingkuang sun",
            webPage = "sun_cal.html"
        )
    )

    /** 感受时间：以「体感」为主。 */
    val feel = listOf(
        AppEntry(
            id = "breath",
            nameRes = R.string.app_breath_name,
            descRes = R.string.app_breath_desc,
            iconRes = R.drawable.ic_cloud,
            pinyin = "hxfs hx huxi fangsong breathing relax",
            target = com.xbaos.shijianapp.ftt.huxifangsong::class.java
        ),
        AppEntry(
            id = "reaction",
            nameRes = R.string.app_reaction_name,
            descRes = R.string.app_reaction_desc,
            iconRes = R.drawable.ic_bolt,
            pinyin = "fynlcs fy fanying nengli ceshi reaction",
            webPage = "reaction-test.html"
        ),
        AppEntry(
            id = "age",
            nameRes = R.string.app_age_name,
            descRes = R.string.app_age_desc,
            iconRes = R.drawable.ic_cake,
            pinyin = "qwnljsq qw nianling jisuanqi age",
            webPage = "Interesting_Age.html"
        ),
        AppEntry(
            id = "progress",
            nameRes = R.string.app_progress_name,
            descRes = R.string.app_progress_desc,
            iconRes = R.drawable.ic_timelapse,
            pinyin = "sjjdt sj jindu tiao progress",
            webPage = "time_progress_bar.html"
        )
    )

    val all: List<AppEntry> get() = apps + feel

    /** 全部稳定 ID。用于「读到的记录还合法吗」这类判断。 */
    val ids: Set<String> get() = all.mapTo(HashSet()) { it.id }

    /**
     * 按稳定 ID 查找。
     *
     * **返回 `null` 的含义是「这个功能已经被删掉了」** —— 调用方（常用应用、桌面快捷方式对账）
     * 拿到 null 就应该把对应的持久化记录一并丢弃。这正是「删除某个功能后，
     * 常用应用与桌面快捷方式自动跟随移除」的实现方式：不写任何迁移代码，
     * 只让读取方尊重 `null`。
     */
    fun byId(id: String): AppEntry? = all.firstOrNull { it.id == id }

    /** 常用应用的出厂默认：时钟立台、复合秒表、时间进度条。 */
    val defaultFavorites: List<String> = listOf("clock", "stopwatch", "progress")
}

/**
 * 功能列表渲染器：把 [AppEntry] 列表铺成「一张卡片 + 多行」。
 *
 * 之所以从「一行一张卡」改为「一卡多行」：设置页、首页、两个列表页原本是三种不同的
 * 卡片切分方式，视觉上很难说是同一个 App。统一成分组卡片后，
 * 全 App 只有一种卡片形态，行与行之间用单侧内缩的分隔线，观感更接近系统设置。
 *
 * 行还统一挂了按压回弹与「依次浮现」入场（见 [PressFeedback] / [ItemAnim]），
 * 因此任何页面调这一个方法，交互动画就自动对齐了。
 */
object AppCardBuilder {

    fun populate(
        activity: AppCompatActivity,
        container: ViewGroup,
        entries: List<AppEntry>
    ) {
        container.removeAllViews()
        val card = LayoutInflater.from(activity)
            .inflate(R.layout.item_card_group, container, false) as LinearLayout

        val rows = ArrayList<View>(entries.size)
        entries.forEachIndexed { index, entry ->
            if (index > 0) card.addView(divider(activity))
            val row = buildRow(activity, card, entry)
            rows.add(row)
            card.addView(row)
        }
        container.addView(card)
        ItemAnim.staggerIn(rows)
    }

    private fun buildRow(
        activity: AppCompatActivity,
        parent: ViewGroup,
        entry: AppEntry
    ): View {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_app_row, parent, false)
        row.findViewById<ImageView>(R.id.app_icon).setImageResource(entry.iconRes)
        row.findViewById<TextView>(R.id.app_name).setText(entry.nameRes)
        row.findViewById<TextView>(R.id.app_desc).setText(entry.descRes)
        row.setOnClickListener { open(activity, entry) }
        // 整行按压回弹。挂在这里而不是各页面，功能列表、搜索结果、首页入口一次全覆盖
        PressFeedback.attach(row)
        return row
    }

    /** 供搜索页直接跳转使用。 */
    fun open(context: Context, entry: AppEntry) {
        val page = entry.webPage
        if (page != null) {
            // 「太阳情况」需要先拿到经纬度再打开：定位是异步的，且可能拿不到。
            // 放在这里（而不是页面内部）是因为入口不止一处 —— 功能列表与搜索结果
            // 都走这个方法，集中处理才不会漏。
            if (page == SUN_PAGE) {
                openSunPage(context, entry)
                return
            }
            startWebPage(context, page, context.getString(entry.nameRes))
            return
        }
        entry.target?.let { context.startActivity(Intent(context, it)) }
    }

    /**
     * 打开「太阳情况」：先定位，再开页面。
     *
     * 三种情况都必须能进页面，功能不因权限而不可用：
     *  1. 已授权 → 直接取点，拿到就带上坐标打开；
     *  2. 未授权 → 先在顶部横幅说明用途，再弹系统权限框；
     *     用户允许后取点；用户拒绝同样打开页面（回退手动输入）；
     *  3. 已授权但取点失败/超时 → 打开页面，由页面自己提示手动输入。
     */
    private fun openSunPage(context: Context, entry: AppEntry) {
        val title = context.getString(entry.nameRes)
        if (LocationFix.hasPermission(context)) {
            requestFixAndOpen(context, title)
            return
        }

        val activity = context as? Activity
        if (activity == null) {
            // 理论上不会发生（调用方都是 Activity）；兜底直接开页面
            startWebPage(context, SUN_PAGE, title)
            return
        }

        // 先说明用途再弹系统框：这是商店审核与用户知情的要求，也符合本次改动的整体方向。
        // ⚠ 必须用 persistent：横幅要一直留在屏幕上，直到用户在系统框里做出选择。
        //   与系统框**同时存在**是刻意的 —— 用户恰是在盯着系统框的那一刻才需要这句说明；
        //   若按默认的 2.6 秒自动消失，等系统框真正弹出来时说明早就看不见了。
        PermissionBanner.show(activity, R.string.perm_location_banner, persistent = true)
        PermissionRequester.request(
            activity = activity,
            permission = LocationFix.permission,
            // 结果一回来立刻收起（同意或拒绝都会走到这里），不留残留在屏幕上
            onResult = {
                PermissionBanner.dismiss(activity)
                if (LocationFix.hasPermission(context)) {
                    requestFixAndOpen(context, title)
                } else {
                    startWebPage(context, SUN_PAGE, title)
                }
            }
        )
    }

    /** 取一次坐标并带着它打开页面；拿不到就直接打开（页面回退手动输入）。成功后顺手缓存。 */
    private fun requestFixAndOpen(context: Context, title: String) {
        LocationFix.start(
            context = context,
            onResult = { fix ->
                // 缓存最近一次成功定位：桌面快捷方式点击时没有机会定位，靠它兜底
                AppPrefs.sunFixLat = fix.lat
                AppPrefs.sunFixLon = fix.lon
                startWebPage(context, SUN_PAGE, title, fix.lat, fix.lon)
            },
            onFail = { startWebPage(context, SUN_PAGE, title) }
        )
    }

    /**
     * 只做「条目 → Intent」的映射，**不带任何前置流程**（定位、权限）。
     *
     * 专门给**桌面快捷方式**用：快捷方式被点击时我们没有任何机会插入异步步骤，
     * 因此这里对「太阳情况」也直接给出网页 Intent。
     * 页面侧会自己兜底：Intent 没带坐标时回退 AppPrefs 缓存的上一次成功定位
     * （见 [com.xbaos.shijianapp.appyy.WebPages] 的 withSkinParams），
     * 从未定位成功过才落到手动输入经纬度。应用内的正常路径走 [open]。
     *
     * @return 目录里两种去向都为空的条目（正常不会出现）返回 null，由调用方跳过，
     *         而不是在这里抛异常 —— 一个坏条目不该让整个页面崩掉。
     */
    fun launchIntent(context: Context, entry: AppEntry): Intent? {
        val page = entry.webPage
        if (page != null) return webIntent(context, page, context.getString(entry.nameRes))
        val target = entry.target ?: return null
        return Intent(context, target)
    }

    /** 只有页面路径与标题、没有坐标的网页 Intent。 */
    private fun webIntent(
        context: Context,
        page: String,
        title: String,
        lat: Double? = null,
        lon: Double? = null
    ): Intent {
        val intent = Intent(context, WebPages::class.java)
            .putExtra(WebPages.EXTRA_PAGE_PATH, WEB_ASSET_BASE + page)
            .putExtra(WebPages.EXTRA_PAGE_TITLE, title)
        if (lat != null && lon != null) {
            intent.putExtra(WebPages.EXTRA_LAT, lat)
            intent.putExtra(WebPages.EXTRA_LON, lon)
        }
        return intent
    }

    private fun startWebPage(
        context: Context,
        page: String,
        title: String,
        lat: Double? = null,
        lon: Double? = null
    ) {
        context.startActivity(webIntent(context, page, title, lat, lon))
    }

    /** 「太阳情况」页面名。需要与 AppCatalog 里的声明保持一致。 */
    private const val SUN_PAGE = "sun_cal.html"

    /** 卡片内的行分隔线：只做单侧内缩，不贯通整张卡片。 */
    fun divider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            context.resources.getDimensionPixelSize(R.dimen.stroke_hairline)
        ).apply {
            marginStart = context.resources.getDimensionPixelSize(R.dimen.divider_inset)
        }
        setBackgroundColor(ContextCompat.getColor(context, R.color.stroke_soft))
    }
}
