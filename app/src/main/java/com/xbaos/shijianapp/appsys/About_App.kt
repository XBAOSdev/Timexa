package com.xbaos.shijianapp.appsys

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.MainActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.AppDialog

/**
 * 关于页面：品牌标识、更新入口、简介与应用信息。
 *
 * 升级入口刻意做成「两步」：点版本名或更新提示 → 先问走哪条路 → 再跳浏览器。
 * 直接一步跳走的话，官网万一没同步好安装包，用户就没有第二选择；
 * 多问一句的代价很小，换来的是「总有一条路能拿到包」。
 *
 * ⚠⚠ 这里有两个不同的网址，别写混：
 *   [siteUrl]     = 主站 www.xbaos.com.cn，是「官方网站」那一行展示并可点的地址；
 *   [upgradeUrl]  = 升级专用站 time.xbaos.com.cn，只在用户选了「官网升级」之后打开。
 *   用户明确要求过：关于页里展示的官方网站仍然是主站，只有升级跳转才去官网子域。
 *
 * 版本比对逻辑与旧版一致：由首页检测后通过 Intent 传入在线版本号，
 * 未传入或与本地一致时不显示更新提示。
 */
class About_App : BaseActivity() {

    /** 备用下载通道（官网之前的临时落地页）。 */
    private val backupUrl = "https://8298670.s.wcd.im/v/7t85eZ44/"

    /** 升级专用站。 */
    private val upgradeUrl = "https://time.xbaos.com.cn/"

    /** 主站：关于页里展示的「官方网站」。 */
    private val siteUrl = "https://www.xbaos.com.cn/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about_app)

        findViewById<TextView>(R.id.page_title).setText(R.string.home_about)

        // 版本胶囊与更新提示是两个入口、同一个弹窗：
        // 用户找不到更新入口时，最直觉的动作就是点版本胶囊那一行。
        // 注意：胶囊文本要读 PackageManager，别写死 —— 版本号会随发布变化。
        findViewById<TextView>(R.id.app_version).apply {
            text = getString(R.string.version_placeholder, currentVersionName())
            setOnClickListener { askUpgradeMethod() }
        }

        // 注意这里取的是 View 而不是 TextView：整行（含副标题与箭头）才是点击区
        findViewById<View>(R.id.about_site).setOnClickListener { openUrl(siteUrl) }
        findViewById<View>(R.id.about_license).setOnClickListener { showLicenses() }

        bindUpdateBanner()
    }

    private fun bindUpdateBanner() {
        val banner = findViewById<View>(R.id.new_version)
        val onlineVersion = intent.getStringExtra(MainActivity.EXTRA_NEW_VERSION).orEmpty()

        if (onlineVersion.isEmpty() || onlineVersion == currentVersionName()) {
            banner.isVisible = false
            return
        }
        findViewById<TextView>(R.id.new_version_title)
            .text = getString(R.string.home_new_version, onlineVersion)
        banner.isVisible = true
        banner.setOnClickListener { askUpgradeMethod() }
    }

    /**
     * 询问升级方式：官网升级走 [upgradeUrl]，备用升级走 [backupUrl]。
     *
     * 官网升级放主按钮（实底强调色）：它是推荐路径，也是这个弹窗存在的理由；
     * 备用通道作为次按钮放左侧，随手可及但不去抢注意力。
     */
    private fun askUpgradeMethod() {
        AppDialog.confirm(
            context = this,
            title = getString(R.string.about_upgrade_title),
            message = getString(R.string.about_upgrade_message),
            positive = getString(R.string.about_upgrade_official),
            negative = getString(R.string.about_upgrade_backup),
            onPositive = { openUrl(upgradeUrl) },
            onNegative = { openUrl(backupUrl) }
        )
    }

    /** 开源许可全文。文案与设置页那一处共用，避免两处渐渐写成不一样的版本。 */
    private fun showLicenses() {
        AppDialog.info(
            context = this,
            title = getString(R.string.settings_licenses),
            message = getString(R.string.settings_licenses_body),
            positive = getString(R.string.dialog_ok)
        )
    }

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName
            ?: getString(R.string.about_unknown_version)
    } catch (e: Exception) {
        getString(R.string.about_unknown_version)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}
