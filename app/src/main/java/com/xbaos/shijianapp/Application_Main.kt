package com.xbaos.shijianapp

import android.os.Bundle
import android.widget.TextView
import com.xbaos.shijianapp.ui.AppCardBuilder
import com.xbaos.shijianapp.ui.AppCatalog

/**
 * 应用集：原生功能 + 内置网页应用的统一入口。
 * 列表内容全部声明在 AppCatalog，搜索页读的是同一份数据，不会漏项。
 */
class Application_Main : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_application_main)

        findViewById<TextView>(R.id.page_title).setText(R.string.home_apps)

        AppCardBuilder.populate(this, findViewById(R.id.apps_container), AppCatalog.apps)
    }
}
