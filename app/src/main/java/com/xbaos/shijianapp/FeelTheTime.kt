package com.xbaos.shijianapp

import android.os.Bundle
import android.widget.TextView
import com.xbaos.shijianapp.ui.AppCardBuilder
import com.xbaos.shijianapp.ui.AppCatalog

/**
 * 感受时间：以“体感”为核心的一小组应用。
 * 与应用集同构（同一个 AppCardBuilder、同一份目录数据），保证两个入口页观感一致。
 */
class FeelTheTime : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feel_the_time)

        findViewById<TextView>(R.id.page_title).setText(R.string.home_feel_time)

        AppCardBuilder.populate(this, findViewById(R.id.apps_container), AppCatalog.feel)
    }
}
