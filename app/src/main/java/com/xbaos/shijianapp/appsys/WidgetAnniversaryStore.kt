package com.xbaos.shijianapp.appsys

import android.content.Context

/**
 * 「纪念日」桌面组件的配置：**每一个组件实例各展示哪一条纪念日**。
 *
 * 组件允许重复添加（桌面上放三份、分别盯着三个纪念日），
 * 所以配置的键必须是 `appWidgetId` 而不是「全局选中项」。
 *
 * ## 存的是「特征串」而不是下标
 *
 * 存 `List` 下标看起来最省事，但下标是**会飘的**：用户在纪念日页删掉一条、
 * 或在别处重新排序，同一个下标就指向了另一条纪念日 —— 组件会静默改成别的内容，
 * 用户还以为是软件出了 bug。这里改用 `YYYY-MM-DD + 分隔符 + 名称`：
 *   · 名称改了 → 组件显示「已移除」，用户可以重新选（改名的意图很难猜，宁可让他重选）；
 *   · 顺序变了 → 完全不受影响。
 *
 * ⚠ 日期固定 10 个字符（`%04d-%02d-%02d`），所以第 10 位必定是分隔符，
 *   解析时按位置切分即可，不需要转义。
 *
 * ## 为什么单独一个 SharedPreferences 文件
 *
 * 它属于「组件的运行状态」，不是用户偏好 ——
 * 混进 AppPrefs 的话，将来做「偏好导出/导入」就得专门把它排除掉，
 * 而它本来就明确**不属于可导出的个性化数据**（导出的是用户自己录入的内容，不是桌面布局）。
 */
object WidgetAnniversaryStore {

    private const val PREFS = "widget_anniversary"

    /**
     * 分隔符用不可打印字符：用户输入的名称里几乎不可能出现，省掉一整套转义。
     * 类型是 Char 而不是 String —— [looksValid] 里要拿它跟单个字符比。
     */
    private const val SEPARATOR = '\u0001'

    /** 日期部分的固定长度（`YYYY-MM-DD`）。 */
    private const val DATE_LENGTH = 10

    fun keyOf(item: Anniversary): String =
        item.dateText + SEPARATOR + item.name

    /** 读某个组件实例当前绑定的纪念日特征串；未配置返回 null。 */
    fun key(context: Context, widgetId: Int): String? =
        prefs(context).getString(widgetId.toString(), null)

    fun set(context: Context, widgetId: Int, key: String?) {
        prefs(context).edit().apply {
            if (key == null) remove(widgetId.toString()) else putString(widgetId.toString(), key)
        }.apply()
    }

    /** 组件被从桌面移除时调用，顺手清掉它的配置，不留孤儿记录。 */
    fun clear(context: Context, widgetId: Int) = set(context, widgetId, null)

    /** 该特征串是否还能在 [items] 里找到对应的那一条。 */
    fun resolve(key: String, items: List<Anniversary>): Anniversary? =
        items.firstOrNull { keyOf(it) == key }

    /** 特征串是否长得像我们写进去的（防止读到脏数据时越界）。 */
    fun looksValid(key: String): Boolean =
        key.length > DATE_LENGTH && key[DATE_LENGTH] == SEPARATOR

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
