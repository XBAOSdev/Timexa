package com.xbaos.shijianapp

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.xbaos.shijianapp.ui.AppPrefs
import com.xbaos.shijianapp.ui.AppShortcuts

/**
 * 应用入口。
 *
 * 存在的唯一理由是「抢时间」：深色模式必须在任何 Activity 创建之前设置好，
 * 否则第一屏会用错误的配色渲染一遍再闪一下。
 * setDefaultNightMode 在 Application 中最先执行，是官方推荐的位置。
 */
class ShijianApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)

        // 只做「跟随系统 / 强制浅色 / 强制深色」三态映射，
        // 不启用系统自动反色（forceDark），配色完全由 values-night 决定。
        AppCompatDelegate.setDefaultNightMode(
            when (AppPrefs.themeMode) {
                AppPrefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppPrefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )

        // 桌面快捷方式对账：功能被删掉之后，桌面上那条快捷方式要跟着失效。
        //
        // 放这里（而不是「删除功能时调一次」）是因为「删掉某个功能」不需要记得做任何事 ——
        // 判据只有一个：固定在桌面上、但 id 已经不在 AppCatalog 里的，一律禁用。
        // 每次冷启动读一次快捷方式列表，代价很小，换来的是这条链路以后永远不会漏。
        AppShortcuts.reconcile(this)
    }
}
