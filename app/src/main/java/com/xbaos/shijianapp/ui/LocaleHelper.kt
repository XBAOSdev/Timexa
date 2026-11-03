package com.xbaos.shijianapp.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 应用内语言切换。
 *
 * 采用「手动包装 Context」而不是 AppCompatDelegate.setApplicationLocales：
 * 后者在 Android 13 以下是由 AppCompat 模拟实现，且依赖 Manifest 中的
 * locale 存储服务声明，条件不满足时行为不一致。手动包装这条路径
 * 只依赖 framework 的 createConfigurationContext，在所有受支持版本上表现一致，
 * 也不会向系统级设置写入任何东西。
 *
 * 生效方式：BaseActivity.attachBaseContext 调用 [wrap]，
 * 因此每个页面在创建时都会拿到被改写过 Locale 的 Context。
 */
object LocaleHelper {

    fun wrap(base: Context): Context {
        val tag = AppPrefs.language
        if (tag.isEmpty()) return base // 跟随系统：不做任何改写
        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isEmpty()) return base

        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}
