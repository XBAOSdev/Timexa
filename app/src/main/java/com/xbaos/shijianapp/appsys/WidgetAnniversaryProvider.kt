package com.xbaos.shijianapp.appsys

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.xbaos.shijianapp.R
import java.io.File

/**
 * 桌面「纪念日」小组件。
 *
 * ## 它与秒表组件的三点不同
 *
 * 1. **每个实例各绑一条纪念日**。秒表组件是所有实例显示同一个数字（全局状态），
 *    纪念日组件则是「这一个盒子盯着这一段日子」—— 配置按 `appWidgetId` 存，
 *    因此可以往桌面上放三份、分别看三个纪念日。配置读写见 [WidgetAnniversaryStore]。
 *
 * 2. **点组件是「换一条」**，不是「打开某个页面」。用户选了「点击组件选择」这个交互
 *    （面板里三个候选之一），所以点击一律落到 [AnniversaryWidgetPicker]。
 *    好处是「改了纪念日名 / 加了一条新的」之后，改绑的路子始终是那一条，不用去应用里翻。
 *
 * 3. **不排轮询闹钟**。倒数天数只在跨零点时变，而跨零点那一刻应用多半没在跑 ——
 *    与其每分钟醒来算一次，不如在**用户真正会看到它的时候**刷新：
 *    进入应用（[updateAll]，由 MainActivity.onResume 调）与纪念日数据变更时。
 *    这两处已经覆盖了「用户会注意到数字过期」的全部场景。
 *
 * ## 没有数据时显示什么
 *
 * 三种状态各有各的说法，绝不显示空白：
 *   · **从未配置** → 「点击选择纪念日」；
 *   · **配置的那条已被删除 / 改名** → 显示原名称 + 「已移除」，让用户知道发生了什么；
 *   · **一条纪念日都还没建** → 与「从未配置」同款，点击进去会看到引导。
 *
 * 组件是桌面上的东西，用户忘了它是怎么来的很正常 —— 出问题时**说清楚**比好看重要。
 */
class WidgetAnniversaryProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, id, appWidgetManager, null))
        }
    }

    /**
     * 用户拉伸组件时重新渲染。
     * 不处理的话，拉大之后字号仍是初始值 —— 小尺寸溢出、大尺寸缩在角落。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        appWidgetManager.updateAppWidget(
            appWidgetId,
            buildViews(context, appWidgetId, appWidgetManager, null)
        )
    }

    /** 组件从桌面被移除：它的配置就没有主人了，顺手清掉，不留孤儿记录。 */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetAnniversaryStore.clear(context, it) }
    }

    companion object {

        /**
         * 刷新桌面上所有纪念日组件。
         *
         * 调用时机与秒表组件一致：**进入应用时**（MainActivity.onResume）与
         * **纪念日数据变更后**。没有实例时立即返回，所以可以放心地到处调。
         */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WidgetAnniversaryProvider::class.java)
            )
            if (ids.isEmpty()) return
            ids.forEach { manager.updateAppWidget(it, buildViews(context, it, manager, null)) }
        }

        /**
         * 仅供「组件」中心预览：不依赖桌面上任何实例。
         *
         * 尺寸由调用方给（[WidgetPin.estimateSizePx]）—— 尺寸决定字号，
         * 拿 `widget_min_width`（110dp）当预览尺寸会让预览里的字明显偏大，详见那里的注释。
         * 内容取用户真实的**最近一条**纪念日，没有就用占位文案 ——
         * 预览的意义就在于「让用户认出这是他要的东西」，拿假数据不如拿真数据。
         */
        fun previewViews(context: Context, widthPx: Int, heightPx: Int): RemoteViews =
            buildViews(
                context,
                AppWidgetManager.INVALID_APPWIDGET_ID,
                null,
                intArrayOf(widthPx, heightPx)
            )

        private fun buildViews(
            context: Context,
            widgetId: Int,
            manager: AppWidgetManager?,
            previewSizePx: IntArray?
        ): RemoteViews {
            val items = loadItems(context)

            val boundKey = if (previewSizePx != null) {
                null
            } else {
                WidgetAnniversaryStore.key(context, widgetId)
            }
            val bound = boundKey?.let { key -> items.firstOrNull { WidgetAnniversaryStore.keyOf(it) == key } }

            val nameText: String
            val daysText: String
            val dateText: String
            when {
                // 预览：优先真实数据，一条都没有时才用占位
                previewSizePx != null -> {
                    val head = DayMath.sortedByUrgency(items).firstOrNull()
                    nameText = head?.name ?: context.getString(R.string.widget_anniversary_preview_name)
                    daysText = head?.let { AnniversaryText.daysLabel(context, it) }
                        ?: context.getString(R.string.widget_anniversary_preview_days)
                    dateText = head?.dateText.orEmpty()
                }

                bound != null -> {
                    nameText = bound.name
                    daysText = AnniversaryText.daysLabel(context, bound)
                    dateText = bound.dateText
                }

                // 配置过、但那条纪念日已经不在列表里（被删掉或改了名）
                boundKey != null -> {
                    nameText = nameFromKey(boundKey)
                    daysText = context.getString(R.string.widget_anniversary_lost)
                    dateText = ""
                }

                // 还没选过
                else -> {
                    nameText = context.getString(R.string.widget_anniversary_preview_name)
                    daysText = context.getString(R.string.widget_anniversary_unset)
                    dateText = ""
                }
            }

            // 倒数那一行的颜色跟着「还有几天」走：今天用强调色、已过去用弱化色，
            // 与首页卡片、列表页的规则同一套（AnniversaryText.daysColorRes）。
            val daysColor = bound?.let { AnniversaryText.daysColorRes(it) } ?: R.color.widget_text_primary

            return RemoteViews(context.packageName, R.layout.widget_anniversary).apply {
                setTextViewText(R.id.anniversary_widget_name, nameText)
                setTextViewText(R.id.anniversary_widget_days, daysText)
                setTextViewText(R.id.anniversary_widget_date, dateText)
                setViewVisibility(
                    R.id.anniversary_widget_date,
                    if (dateText.isEmpty()) View.GONE else View.VISIBLE
                )
                setTextColor(R.id.anniversary_widget_days, resolveWidgetColor(context, daysColor))

                // 字号随组件实际尺寸走。RemoteViews 不支持 autosize，
                // 只能按可用宽高估一个 sp 推过去 —— 估算工具在 WidgetText。
                // 宽度先乘安全系数：em 估算偏乐观，直接贴边会在真机上撞省略号。
                contentBox(context, manager, widgetId, previewSizePx)?.let { box ->
                    val width = box[0] * WidgetText.WIDTH_SAFETY
                    val height = box[1]
                    setTextViewTextSize(
                        R.id.anniversary_widget_name,
                        TypedValue.COMPLEX_UNIT_SP,
                        minOf(
                            WidgetText.widthFitSp(nameText, width, NAME_MIN_SP, NAME_MAX_SP),
                            height * NAME_HEIGHT_RATIO
                        )
                    )
                    setTextViewTextSize(
                        R.id.anniversary_widget_days,
                        TypedValue.COMPLEX_UNIT_SP,
                        minOf(
                            WidgetText.widthFitSp(daysText, width, DAYS_MIN_SP, DAYS_MAX_SP),
                            height * DAYS_HEIGHT_RATIO
                        )
                    )
                    setTextViewTextSize(
                        R.id.anniversary_widget_date,
                        TypedValue.COMPLEX_UNIT_SP,
                        minOf(
                            WidgetText.widthFitSp(dateText.ifEmpty { "0" }, width, DATE_MIN_SP, DATE_MAX_SP),
                            height * DATE_HEIGHT_RATIO
                        )
                    )
                }

                setContentDescription(
                    R.id.anniversary_widget_root,
                    context.getString(R.string.widget_anniversary_a11y, nameText, daysText)
                )
                setOnClickPendingIntent(
                    R.id.anniversary_widget_root,
                    openPicker(context, widgetId)
                )
            }
        }

        /** 读全部纪念日。读盘失败返回空列表，组件退化成「未配置」而不是崩掉。 */
        private fun loadItems(context: Context): List<Anniversary> = runCatching {
            AnniversaryStore(File(context.filesDir, Anniversaries.ANNIVERSARY_PATH)).load()
        }.getOrDefault(emptyList())

        /** 从特征串里取出存进去时的旧名称（日期固定 10 位，其后是分隔符）。 */
        private fun nameFromKey(key: String): String =
            if (WidgetAnniversaryStore.looksValid(key)) key.substring(11) else key

        private fun resolveWidgetColor(context: Context, colorRes: Int): Int =
            androidx.core.content.ContextCompat.getColor(context, colorRes)

        /**
         * 与秒表组件的 contentBox 同一套口径：预览用调用方给的估算尺寸，
         * 正式渲染用桌面报告的竖屏真实尺寸（原因见 [WidgetPin.portraitBoxDp]），
         * 桌面还没测量时返回 null、由布局默认值先兜着。
         */
        private fun contentBox(
            context: Context,
            manager: AppWidgetManager?,
            widgetId: Int,
            previewSizePx: IntArray?
        ): FloatArray? {
            val density = context.resources.displayMetrics.density
            previewSizePx?.let {
                return floatArrayOf(
                    it[0] / density - WIDGET_PADDING_DP * 2,
                    it[1] / density - WIDGET_PADDING_DP * 2
                )
            }

            // 竖屏是手机的常态，按竖屏真实尺寸排版；取值语义与踩坑史见 WidgetPin.portraitBoxDp
            val options = manager?.getAppWidgetOptions(widgetId) ?: return null
            val box = WidgetPin.portraitBoxDp(options)
            if (box[0] <= 0f || box[1] <= 0f) return null
            return floatArrayOf(
                box[0] - WIDGET_PADDING_DP * 2,
                box[1] - WIDGET_PADDING_DP * 2
            )
        }

        /**
         * 点击组件 → 打开选择页。
         * `requestCode` 必须带上 `widgetId`：不区分的话，桌面上放三个组件时
         * 三个 PendingIntent 会被系统视为同一个（extra 不同但 requestCode 相同），
         * 点哪一个都会跳到「第一个」的配置。
         */
        private fun openPicker(context: Context, widgetId: Int): PendingIntent {
            val intent = Intent(context, AnniversaryWidgetPicker::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
            return PendingIntent.getActivity(context, widgetId, intent, flags)
        }

        /** 与 @dimen/widget_padding 一致（RemoteViews 里读不到布局的实际内边距）。 */
        private const val WIDGET_PADDING_DP = 14

        // 三行各自的字号区间与高度占比。占比是「相对整个内容区高度」的上限，
        // 保证小尺寸下三行加起来也不会超过组件 —— 大尺寸时宽度那一关会先起作用。
        // 下限取「极小尺寸下仍然可读」的一档；正常 2×2 尺寸下都在中段，
        // 修掉 spanDp 的双重密度换算后（见 WidgetPin.spanDp），主体字号比旧版大了一倍多。
        private const val NAME_MIN_SP = 12f
        private const val NAME_MAX_SP = 19f
        private const val NAME_HEIGHT_RATIO = 0.20f

        private const val DAYS_MIN_SP = 16f
        private const val DAYS_MAX_SP = 44f
        private const val DAYS_HEIGHT_RATIO = 0.42f

        private const val DATE_MIN_SP = 10f
        private const val DATE_MAX_SP = 15f
        private const val DATE_HEIGHT_RATIO = 0.14f
    }
}
