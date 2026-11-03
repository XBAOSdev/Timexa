package com.xbaos.shijianapp.appyy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.AppPrefs

/**
 * 内置网页容器：加载 assets/Web_app 下的离线页面。
 *
 * 与旧版的差异：
 *  · 去掉了 “page_path 缺失就加载 https://www.example.com” 的兜底 ——
 *    那是个会真的发起网络请求的坑；现在参数缺失会直接提示并退出；
 *  · 网页里的外部链接（http/https）交给系统浏览器，避免用户被困在
 *    一个没有地址栏、又回不去的 WebView 里；
 *  · 补上 WebView 的销毁，避免页面反复进出时泄漏；
 *  · 通过 URL 参数把「当前语言」与「深浅色」传给页面，
 *    assets/Web_app/i18n.js 在 <head> 里同步读取，因此不会出现先中文后英文的闪变。
 */
class WebPages : BaseActivity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_pages)

        findViewById<TextView>(R.id.page_title).setText(titleExtraOrNull() ?: "")

        val pagePath = intent.getStringExtra(EXTRA_PAGE_PATH)
        if (pagePath.isNullOrEmpty()) {
            Toast.makeText(this, R.string.web_missing_param, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        webView = findViewById<WebView>(R.id.webappview).apply {
            // 透明底：让网页自绘的背景与原生页面的底色无缝衔接
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                mediaPlaybackRequiresUserGesture = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url.toString()
                    return if (url.startsWith("http://") || url.startsWith("https://")) {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        true
                    } else {
                        false
                    }
                }
            }
            loadUrl(withSkinParams(pagePath))
        }
    }

    private fun titleExtraOrNull(): String? =
        intent.getStringExtra(EXTRA_PAGE_TITLE)?.takeIf { it.isNotEmpty() }

    /**
     * 把当前语言、深浅色（以及可选的经纬度）作为查询参数接到页面地址后面，
     * 让网页在首帧之前就知道该用哪套文案、哪套配色，以及该用哪个坐标算太阳数据。
     *
     * 经纬度由调用方（功能入口）在打开本页之前取好，通过 Intent 传进来：
     * 定位是异步且可能失败的，放在 WebView 里做会让「加载中」与「无坐标」两种状态
     * 糊在一起；在打开页面之前拿到（或确认拿不到）则页面只需处理「有 / 没有」两种情况。
     */
    private fun withSkinParams(pagePath: String): String {
        val params = StringBuilder()
        params.append("lang=").append(pageLanguageTag())
        params.append("&theme=").append(if (isNightMode) "dark" else "light")

        // 经纬度可选：没取到就不拼这两个参数，页面据此自己回退到手动输入界面。
        // 太阳情况例外地还有一层兜底：Intent 没带坐标时（桌面快捷方式就是这种 ——
        // 快捷方式点击时没有机会先定位），回退到 AppPrefs 里缓存的上一次成功定位，
        // 这样「桌面点开直达」才成立；连缓存都没有（从未定位成功过）才真正交给页面手动输入。
        var lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        var lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
        if ((lat.isNaN() || lon.isNaN()) && pagePath.contains(SUN_PAGE) && AppPrefs.hasSunFix()) {
            lat = AppPrefs.sunFixLat
            lon = AppPrefs.sunFixLon
        }
        if (!lat.isNaN() && !lon.isNaN()) {
            params.append("&lat=").append(lat)
            params.append("&lon=").append(lon)
        }

        val separator = if (pagePath.contains("?")) "&" else "?"
        return pagePath + separator + params
    }

    /** 优先用应用内设置的语言；跟随系统时取系统语言标签。 */
    private fun pageLanguageTag(): String {
        AppPrefs.language.takeIf { it.isNotEmpty() }?.let { return it }
        val system = resources.configuration.locales[0]?.toLanguageTag().orEmpty()
        return system.ifEmpty { AppPrefs.LANG_ZH_CN }
    }

    /**
     * 页面内若有跳转，先回退历史，避免直接退出整个页面。
     *
     * 这里要同时抑制两个诊断：
     *  · `OVERRIDE_DEPRECATION` —— 「覆盖了已废弃成员却没把自己也标成废弃」，`DEPRECATION` 管不了它；
     *  · `DEPRECATION` —— 调用 `super.onBackPressed()` 本身也会报这条。
     * 之所以仍沿用 onBackPressed：页面自己维护 WebView 历史，
     * 换成 OnBackPressedDispatcher 要额外引入回调与生命周期管理，收益仅限于消除警告。
     */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PAGE_PATH = "page_path"
        const val EXTRA_PAGE_TITLE = "page_title"

        /**
         * 可选的经纬度（double）。
         * 用 double extra 而不是塞进 page_path 的 query：页面路径由 AppCatalog 声明，
         * 坐标是运行时才拿到的东西，硬拼进字符串会让「静态资源路径」与「动态数据」混在一起。
         */
        const val EXTRA_LAT = "page_lat"
        const val EXTRA_LON = "page_lon"

        /** 太阳情况页面的文件名：坐标缓存兜底只对它生效。 */
        private const val SUN_PAGE = "sun_cal.html"
    }
}
