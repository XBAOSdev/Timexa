package com.xbaos.shijianapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.xbaos.shijianapp.appsys.AppClock
import com.xbaos.shijianapp.ui.AppPrefs
import com.xbaos.shijianapp.ui.LocaleHelper
import com.xbaos.shijianapp.ui.PermissionBanner
import com.xbaos.shijianapp.ui.PermissionRequester
import com.xbaos.shijianapp.ui.PressFeedback

/**
 * 所有页面的基类。
 *
 * 统一承担四件此前散落在每个 Activity 里、且写法各不相同的事：
 *  1. 应用用户选择的语言（改写 Context 的 Locale）；
 *  2. 开启 edge-to-edge（内容绘制到系统栏之下）；
 *  3. 决定系统栏图标的明暗（跟随昼夜，纯黑页面恒为浅色）；
 *  4. 把系统栏尺寸叠加到根布局的 padding 上，保证内容既不被系统栏压住、也不贴屏幕边缘。
 *
 * 约定：页面根布局的 id 必须是 `@+id/main`。约定优于配置，省掉每个页面重复三遍的样板代码。
 */
open class BaseActivity : AppCompatActivity() {

    /**
     * 是否需要为系统栏预留内边距。
     * 沉浸式页面（例如全屏时钟）划为 false：系统栏会自行隐藏，
     * 预留内边距只会在瞬时显示系统栏时让画面产生跳动。
     */
    protected open val fitsSystemBars: Boolean get() = true

    /**
     * 系统栏图标是否使用深色。
     * 默认跟随昼夜：浅色背景用深色图标，深色背景用浅色图标。
     * 纯黑页面（时钟立台）必须覆写为 false，否则图标完全看不见。
     */
    protected open val lightSystemBarIcons: Boolean get() = !isNightMode

    protected val isNightMode: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 本页创建时实际生效的语言标签，用于在设置里改过语言后自动重建页面。 */
    private var appliedLocale: String = AppPrefs.LANG_SYSTEM

    /**
     * 本页创建时实际生效的**应用时区** ID，用于时区变化后自动重建页面。
     *
     * 与 [appliedLocale] 完全同构：不主动通知各页面刷新，而是记下「这一页是按哪个时区画的」，
     * 下一次可见时一比对，不一致就重建。这样每个页面都不需要关心时区从哪来。
     */
    private var appliedZoneId: String = ""

    /**
     * 本页创建时实际生效的**系统时区** ID。
     *
     * 与 [appliedZoneId] 的区别很关键：手动指定时区期间，系统时区变了而应用时区**没变**，
     * 此时不该重建整页（那正是这个功能要保证的「与系统解耦」），
     * 但「系统时区」这个事实确实变了 —— 需要通知页面刷新与之相关的显示
     * （目前只有设置页的「系统时区已变更」提示依赖它）。
     */
    private var appliedSystemZoneId: String = ""

    /**
     * 系统时区变化时的钩子。
     *
     * 默认空实现：绝大多数页面只关心**应用时区**（已由 [refreshIfZoneChanged] 的重建覆盖），
     * 系统时区本身对它们不可见。需要感知系统时区的页面覆写它即可，
     * 例如 [com.xbaos.shijianapp.appsys.Settings] 的「不一致提示」。
     */
    protected open fun onSystemZoneChanged() {}

    /**
     * 系统时区变化广播。
     *
     * `ACTION_TIMEZONE_CHANGED` 在 Android 8.0 隐式广播限制的**豁免名单**里，
     * 动态注册与清单静态注册都能收到。这里选动态注册，因为本应用并不需要
     * 「进程不在前台时也被唤醒」——只需要「页面正开着的时候别显示过期时间」。
     *
     * 收到后走的仍是 [refreshIfZoneChanged] 那一次比对，因此：
     *  - 跟随系统时 → 应用时区跟着变 → 重建，时间立刻正确；
     *  - 手动指定时 → 应用时区没变 → 不重建，显示**保持不变**（这是设计意图），
     *    但会走 [onSystemZoneChanged] 让需要它的页面刷新。
     */
    private val zoneChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_TIMEZONE_CHANGED) refreshIfZoneChanged()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // attachBaseContext 是拿到 Context 的最早时机，语言必须在这里就位，
        // 否则页面会先按旧语言渲染一遍再重建，出现肉眼可见的语言闪变。
        appliedLocale = AppPrefs.language
        // 时区同理：记下这一页是按哪个时区构造的（此刻读到的就是最新值，
        // 因为 AppClock 不缓存 TimeZone.getDefault()）。
        appliedZoneId = AppClock.zoneId()
        appliedSystemZoneId = AppClock.systemZoneId()
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 起注册接收器必须声明导出性。系统广播用 NOT_EXPORTED。
            registerReceiver(zoneChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(zoneChangeReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(zoneChangeReceiver) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyImmersiveSystemBars()
        super.onCreate(savedInstanceState)
    }

    /**
     * 系统栏（状态栏 + 小白条）的沉浸式底色。
     *
     * `enableEdgeToEdge()` 只负责把窗口变成 edge-to-edge，**不保证系统栏底色透明**：
     * 部分 OEM 的系统或旧版本上仍会留一层默认的深色/半透明底衬，
     * 表现为「内容确实画到了小白条下面，但小白条那里蒙了一层灰」——不算真沉浸。
     *
     * 这里显式把两层都设为透明，并让 DecorView 不替系统栏留位（关掉 fitsSystemWindows），
     * 于是小白条区域完全透出页面背景色。底部的「不被盖住」由 [bindSystemBarInsets] 的
     * padding 负责，与本方法各司其职 —— 不要在这里加 padding，否则会与 insets 双重留白。
     *
     * 对比度：`isNavigationBarContrastEnforced = false` 用于关掉 Android 10+ 在浅色导航栏上
     * 自动加的暗色底衬（那层底衬会让沉浸式页面在小白条处出现一条明显的横带）。
     *
     * 关于两个 deprecation 警告：`statusBarColor` / `navigationBarColor` 在 API 35 起被废弃
     * （官方推荐改用 `enableEdgeToEdge` 的 SystemBarStyle），但本项目 minSdk 24，
     * 在 24~34 这些版本上**只有**这两个属性能把系统栏刷成透明 —— 不写就会在部分 OEM 上
     * 留一层灰底。因此这里是「为兼容旧版本必须保留」的调用，抑制警告而非删除。
     */
    @Suppress("DEPRECATION")
    private fun applyImmersiveSystemBars() {
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
    }

    override fun onResume() {
        super.onResume()
        // 设置页里切换语言后，栈中已经存在的页面在下一次可见时重建。
        // appliedLocale 会随 attachBaseContext 更新，因此不会反复重建。
        if (appliedLocale != AppPrefs.language) {
            recreate()
            return
        }
        // 时区（含「设置里改成了别的时区」与「跟随系统时系统时区被改」两种情况）。
        refreshIfZoneChanged()
    }

    /**
     * 时区相关变化的分级处理：**应用时区**变了就重建，只有**系统时区**变了就只通知钩子。
     *
     * ⚠ 重建的判据必须是 [AppClock.zoneId]（已算上「跟随系统 / 手动指定」）而**不是**系统时区 ID：
     * 手动指定期间系统时区怎么变，应用时区都没有变，此时**不应该**重建、
     * 也不该刷新页面上的任何时间显示 —— 那正是这个功能要保证的「应用时区与系统解耦」。
     *
     * 但这不等于「系统时区变化可以被忽略」：设置页的「系统时区已变更」提示正是由它驱动，
     * 而该提示的语义是「你的系统时区变了、本应用还在按另一个时区走」——
     * 只在重建时顺带生成，就会出现「用户明明切了系统时区、回来看却没有任何提示」的漏判。
     * 因此这里把两级变化分开：**重不重建看应用时区，通知不通知看系统时区**。
     */
    private fun refreshIfZoneChanged() {
        if (isFinishing || isDestroyed) return
        if (appliedZoneId != AppClock.zoneId()) {
            // 应用时区变了 → 整页重建（页面上的时间、日期、文案都要跟着换）。
            recreate()
            return
        }
        // 应用时区没变。手动指定期间系统时区变化就是这种情况：
        // 按设计**不能**重建（显示必须纹丝不动），但要把「系统时区变了」这件事
        // 告诉需要它的页面 —— 否则设置页的不一致提示只有碰巧重建时才出现。
        val systemZoneId = AppClock.systemZoneId()
        if (appliedSystemZoneId != systemZoneId) {
            appliedSystemZoneId = systemZoneId
            onSystemZoneChanged()
        }
    }

    /**
     * 运行时权限结果统一转发。
     *
     * 本应用申请运行时权限的场景集中在少数页面（通知、定位），若每个页面各写一遍覆写
     * 既啰嗦又容易漏。这里统一覆写一次，转给 [com.xbaos.shijianapp.ui.PermissionRequester]
     * 处理：未登记的 requestCode 会被它自行忽略（返回 false），再落回 super。
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        // 兜底：只要权限结果回来，就把「申请原因」横幅收掉。
        // 各业务页大多自己也会调一次 dismiss（幂等，找不到横幅直接返回），
        // 但持久横幅的存在时间现在由「用户何时做出选择」决定 —— 万一某个新页面忘了收，
        // 一条横幅就要在屏幕上挂到兜底超时（60 秒）才会走，所以这里再保一道。
        PermissionBanner.dismiss(this)
        if (PermissionRequester.onResult(this, requestCode, grantResults)) return
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /**
     * onContentChanged 在 setContentView 之后回调，
     * 在这里挂监听可以避免每个子类都要记得“先 setContentView 再设置 insets”。
     */
    override fun onContentChanged() {
        super.onContentChanged()
        bindSystemBarInsets()
        bindBackButton()
        // 全 App 的按压缩放反馈在这里统一挂载：
        // 各页面的可点控件都是 TextView / ImageButton，逐个页面手写必然会漏，
        // 而 attachTree 只认「clickable 的节点」，纯展示控件不会被误伤。
        // 放在基类还有一个好处：新增页面时自动获得反馈，不会因为忘了调用而手感不一致。
        findViewById<View>(R.id.main)?.let { PressFeedback.attachTree(it) }
    }

    /**
     * 统一绑定返回键。
     * 全 App 的返回控件都来自 `@layout/item_back_button`，行为也一律是 finish()，
     * 因此没有理由让每个子类各写一遍。没有返回键的页面（例如全屏时钟）取到 null，直接跳过。
     */
    private fun bindBackButton() {
        findViewById<View>(R.id.back_button)?.setOnClickListener { finish() }
    }

    private fun bindSystemBarInsets() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightSystemBarIcons
            isAppearanceLightNavigationBars = lightSystemBarIcons
        }

        if (!fitsSystemBars) return
        val root = findViewById<View>(R.id.main) ?: return

        // 关键：布局自身通常已经写了 padding（例如页面左右安全边距 screen_padding）。
        // updatePadding 是「覆盖」语义，若直接塞 insets 进去，布局里写的 padding 会被整体抹掉，
        // 表现就是内容紧贴屏幕边缘绘制。所以先记录布局的原始 padding，之后每次都基于它叠加。
        val basePadding = intArrayOf(
            root.paddingLeft,
            root.paddingTop,
            root.paddingRight,
            root.paddingBottom
        )

        // 底部系统栏（手势小白条 / 三键导航）之上的额外留白。
        //
        // 曾经这里固定加 8dp「呼吸留白」，正是「小白条区域背景反而更高」的原因：
        // 手势导航本身已经预留了安全区，再叠 8dp 等于凭空把内容抬高一条，
        // 底部于是露出一大块纯背景色，看起来像多了一条色带而非「沉浸」。
        //
        // 现在的原则：**系统栏给多少就留多少，不额外加**。
        // 各页面若确实需要与小白条拉开距离，由页面自己的底部 margin 决定（那是页面语义，不是全局约定）。
        val extra = 0

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            // 键盘高度必须单独取。
            // 本 App 开启了 edge-to-edge（等价于 WindowCompat.setDecorFitsSystemWindows(window, false)），
            // 从 Android 11 起，这种窗口系统不再替我们为输入法让位，
            // Manifest 里写的 android:windowSoftInputMode="adjustResize" 会变成空操作 ——
            // 表现就是键盘弹出来直接盖住输入框，页面完全不重排（旧版一直没有解决）。
            // 这里显式把 IME 的底部高度算进根布局的 padding，页面就会随键盘收缩。
            // 兼容性：API 30 以下 systemBars 本身就含键盘高度，两个值取大者不会重复叠加。
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)

            view.updatePadding(
                left = basePadding[0] + bars.left,
                top = basePadding[1] + bars.top,
                right = basePadding[2] + bars.right,
                bottom = basePadding[3] + bottom + extra
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
