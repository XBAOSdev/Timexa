package com.xbaos.shijianapp.appsys

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.AppCardBuilder
import com.xbaos.shijianapp.ui.ItemAnim
import com.xbaos.shijianapp.ui.PressFeedback
import java.io.File

/**
 * 「纪念日」桌面组件的选择页。
 *
 * ## 为什么是「点组件进来选」而不是「拖上桌面时先配」
 *
 * 面板里给过三个候选，用户选的是前者。它的好处在**事后**：
 * 拖上桌面那一刻要配的话，用户必须先想清楚「我要放哪一条」，
 * 而那一刻他往往只是想把组件摆上去看看；一旦随手配错，再改还得找应用内的入口。
 * 点组件进来选则是「随时可改」—— 这条路永远在同一个地方。
 *
 * ## 进来时先读，出去前再写
 *
 * 选中即刻生效（写配置 → 刷新桌面 → 关页），不做「确定 / 取消」那一套：
 * 这个页面的唯一目的就是选一条，多一次确认没有任何信息量。
 *
 * ## 一条纪念日都没有时
 *
 * 不显示空卡片，换成一行「去添加纪念日」直接跳到纪念日页 ——
 * 用户此刻的困境是「我没有可选的」，那就给他一条能走下去的路。
 */
class AnniversaryWidgetPicker : BaseActivity() {

    /** 从桌面传来的组件实例号。理论上一定有效（只有组件点击会进这个页）。 */
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_anniversary_widget_picker)

        widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        findViewById<TextView>(R.id.page_title).setText(R.string.widget_anniversary_picker_title)

        val hint = findViewById<TextView>(R.id.picker_hint)
        val list = findViewById<LinearLayout>(R.id.picker_list)
        val emptyRow = findViewById<View>(R.id.picker_empty_row)

        val items = DayMath.sortedByUrgency(
            AnniversaryStore(File(filesDir, Anniversaries.ANNIVERSARY_PATH)).load()
        )

        if (items.isEmpty()) {
            hint.isVisible = false
            list.isVisible = false
            emptyRow.isVisible = true
            emptyRow.findViewById<TextView>(R.id.setting_title)
                .setText(R.string.widget_anniversary_picker_empty_action)
            emptyRow.setOnClickListener {
                startActivity(Intent(this, Anniversaries::class.java))
            }
            return
        }

        val currentKey = WidgetAnniversaryStore.key(this, widgetId)

        val rows = ArrayList<View>(items.size)
        items.forEachIndexed { index, item ->
            if (index > 0) list.addView(AppCardBuilder.divider(this))

            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_anniversary_pick, list, false)
            row.findViewById<TextView>(R.id.pick_name).text = item.name
            row.findViewById<TextView>(R.id.pick_date).text = item.dateText
            row.findViewById<ImageView>(R.id.pick_check).isVisible =
                WidgetAnniversaryStore.keyOf(item) == currentKey
            row.setOnClickListener { choose(item) }
            // 代码动态建出来的行不在 setContentView 那一刻的视图树里，
            // BaseActivity 的统一挂载覆盖不到，必须自己挂一次。
            PressFeedback.attach(row)

            list.addView(row)
            rows.add(row)
        }
        ItemAnim.staggerIn(rows)
    }

    /** 选中某一条：写配置 → 立刻刷新桌面上的这个组件 → 退出，回到桌面就能看到结果。 */
    private fun choose(item: Anniversary) {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            WidgetAnniversaryStore.set(this, widgetId, WidgetAnniversaryStore.keyOf(item))
            WidgetAnniversaryProvider.updateAll(this)
        }
        finish()
    }
}
