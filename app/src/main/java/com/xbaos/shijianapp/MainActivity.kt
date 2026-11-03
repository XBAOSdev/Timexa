package com.xbaos.shijianapp

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.xbaos.shijianapp.appsys.Anniversaries
import com.xbaos.shijianapp.appsys.AnniversaryStore
import com.xbaos.shijianapp.appsys.AnniversaryText
import com.xbaos.shijianapp.appsys.AppClock
import com.xbaos.shijianapp.appsys.DayMath
import com.xbaos.shijianapp.appsys.HomeAnniversaryCard
import com.xbaos.shijianapp.appsys.SearchActivity
import com.xbaos.shijianapp.appsys.Settings
import com.xbaos.shijianapp.appsys.WidgetCenter
import com.xbaos.shijianapp.appsys.WidgetStopwatchProvider
import com.xbaos.shijianapp.ui.AppCardBuilder
import com.xbaos.shijianapp.ui.AppCatalog
import com.xbaos.shijianapp.ui.AppDialog
import com.xbaos.shijianapp.ui.AppEntry
import com.xbaos.shijianapp.ui.AppPrefs
import com.xbaos.shijianapp.ui.FavoritesGridView
import com.xbaos.shijianapp.ui.FavoritesStore
import com.xbaos.shijianapp.ui.ItemAnim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * 首页：实时时钟 + 搜索/设置入口 + 两个功能入口 + 版本检测。
 *
 * 版本检测沿用原有约定，不改存储位置与协议，保证老版本升级上来的用户行为一致：
 *  - 在线版本号来自固定的微云分享页，用 `{version}...{version}` 包裹；
 *  - 结果缓存到私有文件 `files/sys/lastversion`，供设置页的「关于本应用」读取；
 *  - 缓存与本地版本不一致时，在设置按钮右上角点一颗小红点 ——
 *    「关于」收进设置页后，这是首页唯一能看见的更新提示。
 *
 * 显示项（24 小时制 / 是否显示秒 / 是否显示日期）全部读自设置，
 * 并且在 onResume 重新读取 —— 用户可能刚从设置页改完返回。
 */
class MainActivity : BaseActivity() {

    private lateinit var realtimeTextView: TextView
    private lateinit var realtimeDateView: TextView
    private lateinit var anniversaryContainer: LinearLayout

    /** 设置按钮右上角的新版本提示点（小红点）。 */
    private lateinit var settingsDot: View

    // ==================== 常用应用 ====================
    private lateinit var favoritesContainer: View
    private lateinit var favoritesGrid: FavoritesGridView
    private lateinit var favoritesEditButton: TextView
    private lateinit var favoritesIndicators: LinearLayout

    /** 网格是否处于编辑态。与网格内部的 [FavoritesGridView.editing] 始终同步。 */
    private var favoritesEditing = false

    /** 「编辑」入口的浮现参数：3 秒无操作自动淡出，淡入淡出各 200ms。 */
    private val editHintIdleMs = 3_000L
    private val editHintFadeMs = 200L

    /**
     * 上一次真正画到界面上的状态。
     * 用来让 [renderFavorites] 在「什么都没变」时直接返回 —— 见那里的注释：
     * 无条件重建会重播入场动画、并把翻页位置弹回第一页。
     */
    private var renderedIds: List<String>? = null
    private var renderedVisible: Boolean? = null
    private var renderedEditing: Boolean? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var timeFormat: SimpleDateFormat

    /**
     * 12 小时制专用：单独取「上午 / 下午」，再拼到日期行末尾。
     * 不能写进时间格式串（`hh:mm a`）—— 时间字号 72sp，整串会被撑到换行。
     */
    private lateinit var meridiemFormat: SimpleDateFormat
    private lateinit var dateFormat: DateFormat

    /** 当前是否为 24 小时制（决定日期行要不要带「上午 / 下午」）。 */
    private var use24Hour = false

    /** 时钟每秒刷新一次；只更新时间文本，不触碰其它视图。 */
    private val timeUpdater = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    /** 私有目录下的版本缓存文件：/data/data/<包名>/files/sys/lastversion */
    private val versionFile: File by lazy {
        File(filesDir, "sys").apply { if (!exists()) mkdirs() }
            .let { File(it, "lastversion") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        realtimeTextView = findViewById(R.id.realtime)
        realtimeDateView = findViewById(R.id.realtime_date)
        anniversaryContainer = findViewById(R.id.home_anniversary_container)
        settingsDot = findViewById(R.id.T_settings_dot)

        findViewById<View>(R.id.T_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        // 组件中心：把应用图标快捷方式与桌面小组件摆到系统桌面
        findViewById<View>(R.id.T_widgets).setOnClickListener {
            startActivity(Intent(this, WidgetCenter::class.java))
        }
        findViewById<View>(R.id.T_settings).setOnClickListener {
            startActivity(Intent(this, Settings::class.java))
        }
        findViewById<View>(R.id.T_ganshoushijian).setOnClickListener {
            startActivity(Intent(this, FeelTheTime::class.java))
        }
        findViewById<View>(R.id.T_yingyongji).setOnClickListener {
            startActivity(Intent(this, Application_Main::class.java))
        }

        setupFavorites()

        applyDisplayPrefs()
        // 先用上一次的缓存点亮红点（若上次已知有新版本），再发起在线校验
        refreshUpdateDot()
        checkAppVersion()
        renderFavorites(animate = true)
        playEnterAnimation()
    }

    /**
     * 首页第一次画出来时的入场动画。
     *
     * 只在这一屏**第一次装配**（onCreate / 语言·时区变化后的重载）时播，onResume 不重播 ——
     * 每次从设置页返回都被晃一下不是丝滑，是干扰。这条约定与 [ItemAnim] 内部的一致。
     *
     * 顺序按视线自上而下，用 [ItemAnim.staggerIn] 的逐条错开：
     * 顶栏 → 大时钟 → 日期行 → 纪念日 → 功能入口。
     *
     * ⚠⚠ 「常用应用」网格**刻意不在这个列表里**。它自己有一套逐格入场
     *    （FavoritesGridView 的 pendingEnter，24ms 一格、格子从 88% 长出来），
     *    外面再套一层容器级淡入会把那套节奏整个压平 ——
     *    容器 alpha 还是 0 的时候，里面的格子早就自己淡完了，
     *    用户看到的只是一整块东西慢慢显形。宁可让它按自己的节奏进。
     */
    private fun playEnterAnimation() {
        val blocks = listOf(
            R.id.home_topbar,
            R.id.realtime,
            R.id.realtime_date,
            R.id.home_anniversary_container,
            R.id.home_entries
        ).mapNotNull { findViewById<View>(it) }
            // 被设置关掉的块（如纪念日卡片、日期行）此刻是 GONE，跳过它们：
            // 给 GONE 的视图播动画没有任何画面，却会把「已播过」的标记吃掉。
            .filter { it.visibility == View.VISIBLE }
        ItemAnim.staggerIn(blocks)
    }

    override fun onResume() {
        super.onResume()
        // 设置页可能刚改过显示选项，回到首页时重新取一遍
        applyDisplayPrefs()
        updateClock()
        renderAnniversaries()
        // 设置页可能刚改过「首页 · 常用应用」开关；内容没变时这个调用会自己跳过重建
        renderFavorites(animate = false)
        refreshUpdateDot()
        // 桌面秒表组件的计数在这里对齐一次。
        // 秒表的运行/暂停只会在应用内改变，所以「进入应用」就是最准确的刷新时机；
        // 另有每分钟一次的兜底闹钟覆盖边角情况。没有实例时该调用会立即返回。
        WidgetStopwatchProvider.updateAll(this)
        handler.removeCallbacks(timeUpdater)
        handler.postDelayed(timeUpdater, 1000)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(timeUpdater)
    }

    /** 把「首页」分组里的显示选项落到界面上。 */
    private fun applyDisplayPrefs() {
        val locale = currentLocale()
        use24Hour = AppPrefs.home24Hour
        val pattern = when {
            use24Hour && AppPrefs.homeShowSeconds -> "HH:mm:ss"
            use24Hour -> "HH:mm"
            AppPrefs.homeShowSeconds -> "hh:mm:ss"
            else -> "hh:mm"
        }
        timeFormat = AppClock.formatter(pattern, locale)
        // 「上午 / 下午」不进时间格式串：72sp 下整串会超出一行宽度而被折行。
        // 单独取出后拼到日期行末尾（与日期同字号），见 [updateClock]。
        meridiemFormat = AppClock.formatter("a", locale)
        dateFormat = AppClock.dateFormat(DateFormat.FULL, locale)
    }

    /** 取当前生效的 Locale（已在 BaseActivity 里按设置包装过）。 */
    private fun currentLocale(): Locale =
        resources.configuration.locales[0] ?: Locale.getDefault()

    private fun updateClock() {
        val now = Date()
        realtimeTextView.text = timeFormat.format(now)

        // 日期行 = 日期（含星期）+ 12 小时制时的「上午 / 下午」，空格分隔、同一字号。
        // 两段都可能为空，用 joinToString 拼可以自然吃掉多余空格：
        //   · 24 小时制 → 只显示日期；
        //   · 关掉「显示日期」但仍为 12 小时制 → 只显示「上午 / 下午」，
        //     否则整块界面上再也看不到上下午。
        val parts = mutableListOf<String>()
        if (AppPrefs.homeShowDate) parts += dateFormat.format(now)
        if (!use24Hour) parts += meridiemFormat.format(now)
        realtimeDateView.text = parts.joinToString(" ")
        realtimeDateView.isVisible = parts.isNotEmpty()
    }

    /**
     * 首页的纪念日倒数卡片。
     *
     * 没有任何纪念日时整张卡片不出现（判据交给 [HomeAnniversaryCard.populate]，
     * 它会在 rows 为空时把容器一起隐藏）；关闭开关时同样不留空壳。
     * 每次 onResume 都重算：用户可能刚在纪念日页改过日期，也可能已经跨过零点，
     * 而「还有几天」这类文案是不能依靠缓存显示的。
     */
    private fun renderAnniversaries() {
        if (!AppPrefs.homeShowAnniversary) {
            anniversaryContainer.isVisible = false
            anniversaryContainer.removeAllViews()
            return
        }

        val items = DayMath.sortedByUrgency(
            AnniversaryStore(File(filesDir, Anniversaries.ANNIVERSARY_PATH)).load()
        )
        HomeAnniversaryCard.populate(
            activity = this,
            container = anniversaryContainer,
            rows = items.map {
                HomeAnniversaryCard.Row(
                    name = it.name,
                    value = AnniversaryText.daysLabel(this, it)
                )
            },
            onOpen = { startActivity(Intent(this, Anniversaries::class.java)) }
        )
    }

    // ==================== 常用应用 ====================

    /**
     * 装配首页「常用应用」卡片。
     *
     * 数据来源是 [FavoritesStore]（只存稳定 id），每次重绘都重新读一遍 ——
     * 这样设置页改了开关、或者将来某个功能被删掉，回到首页自然就是最新的。
     * 功能目录本身来自 [AppCatalog]，因此**应用增减会自动跟随**，这里不写任何硬编码清单。
     */
    private fun setupFavorites() {
        favoritesContainer = findViewById(R.id.home_favorites_container)
        favoritesGrid = findViewById(R.id.fav_grid)
        favoritesEditButton = findViewById(R.id.fav_edit)
        favoritesIndicators = findViewById(R.id.fav_indicators)

        favoritesGrid.listener = object : FavoritesGridView.Listener {
            override fun onAppClick(entry: AppEntry) {
                // 编辑态下点图标不打开功能 —— 此刻每一次点击都可能是在「准备长按拖拽」，
                // 顺手把功能打开会非常容易误触。移除走角标，退出编辑态即恢复正常。
                if (favoritesEditing) return
                AppCardBuilder.open(this@MainActivity, entry)
            }

            override fun onAppRemove(entry: AppEntry) {
                FavoritesStore.remove(entry.id)
                renderFavorites(animate = true)
                if (FavoritesStore.load().isEmpty()) promptRemoveEmptyCard()
            }

            override fun onAddClick() = showAddPicker()

            /**
             * 非编辑态长按图标/卡片 → 直接进编辑。
             * 走 [toggleFavoritesEditing] 同一条路径：网格切到编辑态，
             * 「完成」按钮淡入并常驻（即使此前完全隐身，见 applyFavoritesButtons）。
             */
            override fun onEditRequested() {
                if (!favoritesEditing) toggleFavoritesEditing()
            }

            override fun onPagingChanged(pageCount: Int, page: Int) =
                renderPageIndicators(pageCount, page)

            /**
             * 拖拽排序结束。网格**自己**已经改好顺序并落位了，这里只负责持久化。
             * ⚠ 不能在这里调 renderFavorites —— 那会再走一遍差分重绘，
             *   把刚落位的格子又挪一次（虽然位置一样，但会多一次无谓的动画）。
             *   排序也不会改变格子总数，因此翻页指示点无需更新。
             */
            override fun onOrderChanged(ids: List<String>) {
                FavoritesStore.save(ids)
            }
        }

        favoritesEditButton.setOnClickListener { toggleFavoritesEditing() }

        // 「编辑」入口的浮现交互（用户 2026-09-26 拍板）：
        // 平时隐身，点首页**空白处**淡入，3 秒无操作自动淡出。
        // 空白点击 = 没被子视图消费的点击，最终都会落到根布局上 ——
        // 给根挂 OnClickListener 即可接到；再挂一个不消费的 OnTouchListener，
        // 在每次按下时把 3 秒倒计时重置（「没有其他操作」才淡出的语义）。
        val root = findViewById<View>(R.id.main)
        root.setOnClickListener { onHomeBlankTap() }
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pokeEditHint()
            false // 不消费，点击照常派发
        }
    }

    private fun renderFavorites(animate: Boolean) {
        val visible = AppPrefs.homeShowFavorites
        val entries = if (visible) {
            FavoritesStore.load().mapNotNull { AppCatalog.byId(it) }
        } else {
            emptyList()
        }
        val ids = entries.map { it.id }

        applyFavoritesButtons()

        // 与上次画出来的完全一致就别重建。
        // ⚠ 这不是省性能，是**功能需要**：每次重建都会换掉全部子视图，
        //   入场动画（ItemAnim 靠 View tag 记「播过没有」）会重播，翻页位置也会弹回第一页。
        //   onResume 每次都会走到这里，绝不能无条件重建。
        if (!animate &&
            visible == renderedVisible &&
            favoritesEditing == renderedEditing &&
            ids == renderedIds
        ) return

        renderedVisible = visible
        renderedEditing = favoritesEditing
        renderedIds = ids

        favoritesContainer.isVisible = visible
        if (!visible) return
        favoritesGrid.render(entries, favoritesEditing, animate)
    }

    private fun toggleFavoritesEditing() {
        favoritesEditing = !favoritesEditing
        // 走动画：进编辑态时「＋」空位是「长出来」的，比凭空出现自然
        renderFavorites(animate = true)
    }

    /** 「编辑」入口的自动隐去任务；挂在按钮自己的消息队列上，视图销毁自动失效。 */
    private val editHintHide = Runnable {
        if (!favoritesEditing) fadeOutEditHint()
    }

    /**
     * 「编辑 / 完成」入口的状态机。
     *
     *   · 编辑态 —— 常驻显示（否则没法退出），取消自动隐去；
     *   · 非编辑态 —— 回到「点空白才浮现」的待命：没显示就保持隐身，
     *     正显示着（刚从编辑态退出来）就重新走一轮 3 秒倒计时。
     *
     * 「添加应用」表头按钮已删：添加入口统一是编辑态网格里的「＋」格子
     * （见 FavoritesGridView.showAddSlot）。
     */
    private fun applyFavoritesButtons() {
        favoritesEditButton.setText(
            if (favoritesEditing) R.string.favorite_edit_done else R.string.favorite_edit
        )
        if (favoritesEditing) {
            // 编辑态常驻。可能从完全隐身（长按图标进入编辑）直接跳到这里，
            // 给一个短淡入而不是瞬间蹦出；可点性同样等淡入完成再给（见 showEditHint 的规则）。
            favoritesEditButton.removeCallbacks(editHintHide)
            favoritesEditButton.animate().cancel()
            favoritesEditButton.isClickable = false
            favoritesEditButton.animate()
                .alpha(1f)
                .setDuration(if (favoritesEditButton.alpha < 1f) editHintFadeMs else 0L)
                .withEndAction { if (favoritesEditing) favoritesEditButton.isClickable = true }
                .start()
        } else if (favoritesEditButton.alpha > 0f) {
            showEditHint()
        } else {
            favoritesEditButton.isClickable = false
        }
    }

    /** 点到首页空白处：「编辑」入口淡入，并开始 3 秒无操作自动淡出的倒计时。 */
    private fun onHomeBlankTap() {
        if (favoritesEditing || !AppPrefs.homeShowFavorites) return
        showEditHint()
    }

    /** 首页任意触摸的「心跳」：入口正显示着时，把 3 秒倒计时重新走一遍。 */
    private fun pokeEditHint() {
        if (favoritesEditing || favoritesEditButton.alpha <= 0f) return
        showEditHint()
    }

    /**
     * 「编辑」入口淡入。可点性**必须**等淡入完成（withEndAction）才给：
     * 半透明期间用户视觉上认为那是空白，手指落到按钮占位区会消费按下事件 ——
     * selectableItemBackgroundBorderless 的水波纹会闪出来（用户 2026-09-26 反馈的误触发），
     * 甚至抬起时误触进编辑。淡入中一律不可点，按下落到根布局，不会有波纹。
     */
    private fun showEditHint() {
        favoritesEditButton.removeCallbacks(editHintHide)
        favoritesEditButton.animate().cancel()
        favoritesEditButton.isClickable = false
        favoritesEditButton.animate()
            .alpha(1f)
            .setDuration(editHintFadeMs)
            .withEndAction { if (!favoritesEditing) favoritesEditButton.isClickable = true }
            .start()
        favoritesEditButton.postDelayed(editHintHide, editHintIdleMs)
    }

    /** 淡出一开始就立刻不可点 —— 不能等动画结束，否则淡出中按下仍会冒波纹、误触切换。 */
    private fun fadeOutEditHint() {
        favoritesEditButton.isClickable = false
        favoritesEditButton.animate().cancel()
        favoritesEditButton.animate()
            .alpha(0f)
            .setDuration(editHintFadeMs)
            .start()
    }

    /** 「＋」被点：列出还没加进常用应用的功能，点一项即添加（已添加的不再出现，天然防重复）。 */
    private fun showAddPicker() {
        val available = FavoritesStore.available()
        if (available.isEmpty()) {
            AppDialog.info(
                context = this,
                title = getString(R.string.favorite_picker_title),
                message = getString(R.string.favorite_picker_empty),
                positive = getString(R.string.dialog_ok)
            )
            return
        }
        AppDialog.choice(
            context = this,
            title = getString(R.string.favorite_picker_title),
            options = available.map { getString(it.nameRes) },
            selected = -1,
            negative = getString(R.string.dialog_cancel)
        ) { index ->
            FavoritesStore.add(available[index].id)
            // 新格子会从 88% 长出来；若因此多出一行，它也会跟着出现
            renderFavorites(animate = true)
        }
    }

    /**
     * 常用应用被清空时的询问。
     *
     * 特意在**最后一项刚被移除的那一刻**立刻弹 —— 用户正处在「我在收拾首页」的心智里，
     * 这时问「这张卡片还要不要」最自然。选「一并移除」就是把设置开关关掉，
     * 之后可以在 设置 → 首页 里重新打开（弹窗文案里已经说明了这一点）。
     */
    private fun promptRemoveEmptyCard() {
        AppDialog.confirm(
            context = this,
            title = getString(R.string.favorite_cleared_title),
            message = getString(R.string.favorite_cleared_message),
            positive = getString(R.string.favorite_cleared_remove_card),
            negative = getString(R.string.favorite_cleared_keep_card),
            positiveIsDanger = true,
            onPositive = {
                AppPrefs.homeShowFavorites = false
                favoritesEditing = false
                renderFavorites(animate = false)
            }
        )
    }

    /**
     * 翻页指示点。只有总行数超过两行（也就是真有第二页）时才出现。
     * 页数不变时只改高亮、不重建视图 —— 翻页是高频动作，每次重建会看到闪烁。
     */
    private fun renderPageIndicators(pageCount: Int, page: Int) {
        if (pageCount <= 1) {
            favoritesIndicators.isVisible = false
            favoritesIndicators.removeAllViews()
            return
        }
        favoritesIndicators.isVisible = true
        favoritesIndicators.contentDescription =
            getString(R.string.favorite_page_desc, page + 1, pageCount)

        val dotSize = resources.getDimensionPixelSize(R.dimen.favorite_indicator_size)
        val gap = resources.getDimensionPixelSize(R.dimen.favorite_indicator_gap)
        // ⚠ 竖排：圆点的排列方向必须与翻页方向一致（翻页是上下，横排圆点反直觉），
        //    所以间距加在 topMargin 上而不是 marginStart。
        if (favoritesIndicators.childCount != pageCount) {
            favoritesIndicators.removeAllViews()
            repeat(pageCount) { index ->
                val dot = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                        if (index > 0) topMargin = gap
                    }
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_favorite_dot)
                }
                favoritesIndicators.addView(dot)
            }
        }
        for (i in 0 until favoritesIndicators.childCount) {
            favoritesIndicators.getChildAt(i).backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    this,
                    if (i == page) R.color.accent else R.color.stroke_strong
                )
            )
        }
    }

    // ==================== 版本检测 ====================

    private fun checkAppVersion() {
        val localVersion = currentVersionName()
        if (localVersion.isEmpty()) {
            Toast.makeText(this, R.string.home_version_failed, Toast.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val onlineVersion = fetchOnlineVersion()
            // 只有真正拿到结果才写缓存：请求失败时保留上一次的结果。
            // 否则一次断网就会把已知的新版本号抹成空串，首页的红点也跟着灭掉。
            if (onlineVersion != null) saveCachedVersion(onlineVersion)
            withContext(Dispatchers.Main) { refreshUpdateDot() }
        }
    }

    // ==================== 新版本提示点 ====================

    /**
     * 首页设置按钮右上角的小红点。
     *
     * 「关于」收进设置页之后，首页再没有别处能看到更新信息，提示职责全部落在这颗点上。
     * 判定口径与设置页、「关于」页保持一致：缓存的在线版本号非空、且与本地版本号不同。
     *
     * 只读本地缓存、不发请求，所以可以放心地在每次 onResume 调用 ——
     * 从设置页返回时立刻反映最新状态，不必再等一次网络往返。
     * 用户升级到新版本后本地版本号与之相等，红点会自己消失。
     */
    private fun refreshUpdateDot() {
        val online = readCachedVersion()
        settingsDot.isVisible = online.isNotEmpty() && online != currentVersionName()
    }

    /** 读取在线版本号缓存；无文件或读取失败一律按「无新版本」处理。 */
    private fun readCachedVersion(): String = try {
        if (versionFile.exists()) versionFile.readText().trim() else ""
    } catch (e: Exception) {
        Log.w(TAG, "read version cache failed", e)
        ""
    }

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        Log.w(TAG, "read local version failed", e)
        ""
    }

    /**
     * 请求约定的分享页并抽取 `{version}` 之间的内容。
     *
     * 返回值区分两种「空」：
     *  - `null` —— 请求失败（断网、超时、页面结构变了），调用方应保留旧缓存；
     *  - `""`   —— 请求成功但页面里没有版本标记，视为「没有新版本」，可覆盖缓存。
     */
    private fun fetchOnlineVersion(): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            val content = connection.inputStream.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
            }
            // 原实现按行拼接，会把换行吃掉；这里等价于整段读取后再匹配。
            Pattern.compile("\\{version\\}(.*?)\\{version\\}").matcher(content.replace("\n", ""))
                .takeIf { it.find() }?.group(1).orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "fetch online version failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun saveCachedVersion(version: String) {
        try {
            versionFile.parentFile?.mkdirs()
            FileWriter(versionFile).use { it.write(version) }
        } catch (e: Exception) {
            Log.e(TAG, "write version cache failed", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val VERSION_URL =
            "https://sharechain.qq.com/fa4c8a7543fce26b7a636abe9f41cdd4"
        const val EXTRA_NEW_VERSION = "newversion"
    }
}
