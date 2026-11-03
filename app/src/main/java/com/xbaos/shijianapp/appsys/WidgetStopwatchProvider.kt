package com.xbaos.shijianapp.appsys

import android.app.AlarmManager
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
import com.xbaos.shijianapp.appyy.StopwatchStore
import com.xbaos.shijianapp.appyy.miaobiao
import java.io.File
import kotlin.math.ceil

/**
 * 桌面「复合秒表」小组件。
 *
 * ## 为什么不做时钟组件（原有时钟组件已移除）
 *
 * 显示当前时间的信息价值极低 —— 状态栏、锁屏、桌面别处都能看到同一份数据，
 * 桌面组件放一份等于重复呈现，还得白占一块桌面。
 * 而「有几个秒表正在跑」是**只有本应用才知道**的状态，
 * 并且恰好是用户离开应用后最想确认的一件事：有没有忘了停的表。
 *
 * 组件底板是莫兰迪淡黄纯色（不是毛玻璃）：半透明底要透出壁纸，
 * 可读性会随壁纸明暗飘；纯色底 + 深蓝字在任何壁纸下观感一致。
 * 因此 [buildViews] 里也不再按「有没有正在跑的表」去改文字颜色 —— 一改就会破坏这份对比度。
 *
 * ## 刷新策略
 *
 * 组件显示的数字是「正在计时的秒表个数」，它只在秒表写盘时才会变，
 * 因此刷新的入口就钉在写盘上（[refresh]，由秒表页 / 单表专注页 / 设置页在改动后调用）：
 * 用户按下暂停、退回桌面，组件上的数字已经是新的 —— **不需要等下一次轮询**。
 *
 * 另有两条兜底：
 *  1. **进入应用时**（[updateAll] 由 MainActivity.onResume 调用）—— 覆盖「应用被系统杀掉、
 *     期间状态没被本进程写盘」这类边角情况；
 *  2. **每分钟一次的兜底闹钟** —— 覆盖「进程被杀后用户又从桌面点了别的入口」，
 *     也顺带保证长时间停留在桌面时组件不会显示一个过期的数字。
 *
 * 闹钟用 `setInexactRepeating`（非精确）：系统可合并调度以省电，
 * 而「晚几秒更新个数」对用户完全无感 —— 用精确闹钟在 Android 12+ 还要申请
 * SCHEDULE_EXACT_ALARM 权限，得不偿失。
 *
 * `onEnabled` 排程 / `onDisabled` 取消：若只排程不取消，
 * 用户删掉组件后闹钟仍会每分钟唤醒进程，白白耗电 —— 属于典型的「后台行为不干净」。
 */
class WidgetStopwatchProvider : AppWidgetProvider() {

    /**
     * 除了 AppWidget 自身的 action，还要处理自己排的分钟闹钟。
     *
     * AppWidgetProvider 本身就是 BroadcastReceiver，`onReceive` 会先把 APPWIDGET_*
     * 分发给对应回调；其余自定义 action 必须自己识别 ——
     * 不覆写这个方法的话，自定义 action 会静默落进默认实现，组件永远不会刷新。
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TICK) {
            updateAll(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildViews(context, id, appWidgetManager))
        }
        scheduleNextTick(context)
    }

    /** 第一个组件被添加时排程。 */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        scheduleNextTick(context)
    }

    /** 最后一个组件被移除时取消排程（不取消会留下无意义的每分钟唤醒）。 */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelTick(context)
    }

    /**
     * 用户拉伸组件时重新渲染：
     * 不处理的话，拉大之后字号仍是初始值，小尺寸溢出、大尺寸缩在角落。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        appWidgetManager.updateAppWidget(
            appWidgetId,
            buildViews(context, appWidgetId, appWidgetManager)
        )
    }

    companion object {

        /**
         * 刷新所有在桌面上的秒表组件。
         *
         * 先查有没有实例：没有就直接返回，避免每次进入应用都白白构造一遍 RemoteViews。
         */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(
                ComponentName(context, WidgetStopwatchProvider::class.java)
            )
            if (ids.isEmpty()) return
            ids.forEach { manager.updateAppWidget(it, buildViews(context, it, manager)) }
        }

        /**
         * 「秒表状态变了」——由秒表页 / 单表专注页 / 设置页在改动落盘后调用。
         *
         * 为什么直接调而不是发广播：
         *   组件与本应用同属一个进程（Manifest 里没有给这个 receiver 指定 android:process），
         *   发广播只是绕一圈再回到自己。直接调是同步的 —— 用户按下暂停、立刻退回桌面，
         *   组件上的数字已经是新的；广播要排队到下一次消息循环才处理，
         *   在「边切页面边看桌面」的场景里会看到一眼旧数字。
         *   组件本身仍保留每分钟一次的兜底轮询，这里补的是「及时」，不是「可靠」。
         *
         * 没有实例时 [updateAll] 会立即返回，所以可以放心地在每一次写盘后调用。
         */
        fun refresh(context: Context) {
            updateAll(context)
        }

        /**
         * 构造一份**不依赖桌面上任何实例**的 RemoteViews，供「组件」中心预览用。
         *
         * 与真实组件走的是同一个 [buildViews]，因此预览里看到的数字、字号、配色
         * 与拖到桌面上之后完全一致 —— 不会出现「预览好看、装上去不一样」。
         *
         * 尺寸**由调用方给**（[WidgetPin.estimateSizePx]）。为什么不能像上一版那样
         * 直接用 `widget_min_width/height`（110dp）：那是「系统据此推算占几格」的声明值，
         * 不是桌面上真实渲染出来的尺寸 —— 实测真机 2×2 是 451×614px（≈172×234dp），
         * 差了 1.5~2 倍。按 110dp 算字号，预览里字会显得奇大、第二行还会被顶出边界。
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
            previewSizePx: IntArray? = null
        ): RemoteViews {
            val running = runningCount(context)
            val countText = running.toString()

            // 单位文案要分单复数：英文里「1 stopwatches running」是病句。
            // 数字与单位拆成了两个视图，没法直接用 Android 的 plurals 机制，
            // 所以在个数为 1 时切到 *_one 那条。
            val label = context.getString(
                if (running == 1) R.string.widget_unit_running_one
                else R.string.widget_unit_running
            )
            // 量词「个」。英文没有这个成分，资源取到空串，此时把整格隐藏 ——
            // 不隐藏的话空 TextView 仍会吃掉 layout_marginStart 那 2dp，
            // 数字就不再严格居中了。
            val suffix = context.getString(R.string.widget_unit_count_suffix)

            return RemoteViews(context.packageName, R.layout.widget_stopwatch).apply {
                setTextViewText(R.id.widget_count, countText)
                setTextViewText(R.id.widget_count_suffix, suffix)
                setViewVisibility(
                    R.id.widget_count_suffix,
                    if (suffix.isEmpty()) View.GONE else View.VISIBLE
                )
                setTextViewText(R.id.widget_unit, label)

                // 字号随组件实际尺寸走。RemoteViews 不支持 autosize，也没有布局权重，
                // 只能自己按当前可用宽高把 dp 算成 sp 再推过去。
                contentBox(context, manager, widgetId, previewSizePx)?.let { box ->
                    val countSp = countSizeSp(box[0], box[1], countText.length, suffix, label)
                    setTextViewTextSize(
                        R.id.widget_count,
                        TypedValue.COMPLEX_UNIT_SP,
                        countSp
                    )
                    // 量词恒为数字的一档小字号：它是附在数字后的单位，不能与数字抢层级
                    setTextViewTextSize(
                        R.id.widget_count_suffix,
                        TypedValue.COMPLEX_UNIT_SP,
                        countSp * SUFFIX_RATIO
                    )
                }

                setContentDescription(
                    R.id.widget_root,
                    context.getString(
                        when {
                            running == 1 -> R.string.widget_sw_a11y_running_one
                            running > 1 -> R.string.widget_sw_a11y_running
                            else -> R.string.widget_sw_a11y_none
                        },
                        running
                    )
                )
                setOnClickPendingIntent(R.id.widget_root, openStopwatch(context))
            }
        }

        /**
         * 统计正在运行的秒表个数。
         *
         * 直接用 [StopwatchStore] 读同一份 CSV —— 不引入任何额外的状态副本，
         * 因此组件显示的数字永远和秒表页一致，不存在「两处各算各的」的失同步问题。
         * 读盘失败时 [StopwatchStore.load] 返回空列表，组件显示 0，不会崩。
         */
        private fun runningCount(context: Context): Int {
            return runCatching {
                val store = StopwatchStore(
                    File(context.filesDir, STOPWATCH_PATH),
                    context.getString(R.string.stopwatch_fallback_name)
                )
                store.load().count { it.isRunning }
            }.getOrDefault(0)
        }

        /**
         * 组件内容区的可用尺寸（dp），下标 0 是宽、1 是高。
         *
         * 取桌面报告的**竖屏**当前尺寸减去自身内边距（取值语义与踩坑史见
         * [WidgetPin.portraitBoxDp]）。桌面还没测量时（组件刚被拖上桌面的头一两帧）
         * 返回 null，此时不动字号，由布局里的默认值先兜着。
         */
        private fun contentBox(
            context: Context,
            manager: AppWidgetManager?,
            widgetId: Int,
            previewSizePx: IntArray?
        ): FloatArray? {
            val density = context.resources.displayMetrics.density

            // 预览场景：没有宿主报告尺寸，用调用方给的「桌面 2×2 实际占多大」估算值。
            // 走与真实渲染同一套换算（统一减内边距），字号才会两边一致。
            previewSizePx?.let {
                return floatArrayOf(
                    it[0] / density - WIDGET_PADDING_DP * 2,
                    it[1] / density - WIDGET_PADDING_DP * 2 - UNIT_GAP_DP
                )
            }

            // 竖屏是手机的常态，按竖屏真实尺寸排版；取值语义与踩坑史见 WidgetPin.portraitBoxDp
            val options = manager?.getAppWidgetOptions(widgetId) ?: return null
            val box = WidgetPin.portraitBoxDp(options)
            if (box[0] <= 0f || box[1] <= 0f) return null
            return floatArrayOf(
                box[0] - WIDGET_PADDING_DP * 2,
                box[1] - WIDGET_PADDING_DP * 2 - UNIT_GAP_DP
            )
        }

        /**
         * 数字字号（sp）。两个约束都要满足，取更紧的那个：
         *
         *  1. **高度**：数字那一行 + 单位文案占掉的高度必须放得下。
         *     单位文案的行数要自己估 —— 中文「秒表正在计时」一行放得下，
         *     英文 "stopwatches running" 会折成两行（布局里给了 maxLines=2）；
         *     不算行数的话英文会把数字挤到溢出。
         *  2. **宽度**：数字本身 + 紧跟其后的量词不能超出组件宽度。
         *
         * ⚠ 为什么必须补宽度约束：量词从第二行并到数字后面之后，
         *   两位数的个数（10 个秒表以上）明显更宽 —— 只按高度算的话，
         *   56sp 的「12」加上「个」正好顶出边界、右边被裁掉。
         *   按宽度反推之后，位数一多字号自己收一档，永远不会出血。
         *
         * ⚠ 三个比例系数都是**估算值**：RemoteViews 拿不到文本的测量结果，
         *   只能按经验折算。宁可估大（字号偏小）也不要估小 ——
         *   估小会直接裁切，估大只是显得保守。
         */
        private fun countSizeSp(
            widthDp: Float,
            heightDp: Float,
            digits: Int,
            suffix: String,
            label: String
        ): Float {
            // 宽度先乘安全系数（em 估算偏乐观，见 WidgetText.WIDTH_SAFETY）：
            // label 折行与数字宽度两处约束都要用，统一在入口收一次。
            val safeWidth = widthDp * WidgetText.WIDTH_SAFETY
            val usableWidth = safeWidth.coerceAtLeast(MIN_BOX_DP)
            val labelLines = ceil(WidgetText.emWidth(label) * BASE_UNIT_SP / usableWidth)
                .coerceAtLeast(1f)
            val labelBlock = labelLines * BASE_UNIT_SP * WidgetText.LINE_HEIGHT_EM

            val byHeight =
                (heightDp - labelBlock) / WidgetText.LINE_HEIGHT_EM * HEIGHT_SAFETY
            val suffixEm = SUFFIX_RATIO * WidgetText.emWidth(suffix)
            val byWidth = (safeWidth - SUFFIX_GAP_DP) /
                (WidgetText.emWidth(DIGIT_PROBE) * digits + suffixEm)

            return minOf(byHeight, byWidth).coerceIn(MIN_COUNT_SP, MAX_COUNT_SP)
        }

        /** 点击组件打开复合秒表页。
         *
         * 直接指向 `miaobiao` 而不是首页：组件的语义就是「秒表的状态」，
         * 点进去应当直达那一页，少一步点击才符合预期。
         *
         * FLAG_IMMUTABLE 从 Android 12 起是强制要求；低版本没有这个 flag，
         * 因此按版本判断而不是无条件加（API 31 以下传进去会抛异常）。
         */
        private fun openStopwatch(context: Context): PendingIntent {
            val intent = Intent(context, miaobiao::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
            return PendingIntent.getActivity(context, 0, intent, flags)
        }

        // ==================== 每分钟兜底刷新 ====================

        private const val ACTION_TICK = "com.xbaos.shijianapp.action.SW_WIDGET_TICK"

        private const val TICK_REQUEST_CODE = 8802

        private const val STOPWATCH_PATH = "sys/stopwatches.csv"

        /** 与 @dimen/widget_padding 一致；RemoteViews 里读不到布局的实际内边距，只能按值算。 */
        private const val WIDGET_PADDING_DP = 14

        /** 与 @dimen/widget_unit_gap / @dimen/widget_suffix_gap 一致。 */
        private const val UNIT_GAP_DP = 2
        private const val SUFFIX_GAP_DP = 2

        /** 第二行说明文案的字号，与 @dimen/widget_unit_size 一致。 */
        private const val BASE_UNIT_SP = 13f

        /** 量词字号相对数字字号的比值（四成半：看得出是单位，又不至于变成第二个数字）。 */
        private const val SUFFIX_RATIO = 0.45f

        /**
         * 用来探「一位数字多宽」的哑字符。
         * 宽度系数统一收敛在 [WidgetText] 里（等宽数字 0.62em），这里只是借它取出来 ——
         * 免得同一个系数在两处各写一遍，哪天改了一处忘了另一处。
         */
        private const val DIGIT_PROBE = "0"

        /**
         * 高度方向的安全系数。
         *
         * [WidgetText.LINE_HEIGHT_EM] 是 1.15 的经验值，而 Noto Sans CJK 的实际行框约 1.2em ——
         * 也就是说按 1.15 算出来的字号**刚好把内容区填满**，真机上就会从底部溢出去。
         * 实测现象是预览里第二行「秒表正在计时」的下缘被盖住。
         * 这里统一收 6%：宁可字号保守一点，也不要任何一处在小尺寸下被裁。
         */
        private const val HEIGHT_SAFETY = 0.94f

        /** 可用宽度异常时的兜底下限，避免除零得到 Inf 字号。 */
        private const val MIN_BOX_DP = 40f

        private const val MIN_COUNT_SP = 28f

        /**
         * 上限 64sp。
         * 比上一版的 56sp 提高一档，同时在 [countSizeSp] 里补了宽度约束 ——
         * 提高上限不会让两位数溢出，因为宽度那一关会先把它压回去。
         */
        private const val MAX_COUNT_SP = 64f

        /**
         * 一分钟的毫秒数。
         *
         * 自己定义而不是用 `AlarmManager.INTERVAL_*`：那几个常量**只有**
         * FIFTEEN_MINUTES / HALF_HOUR / HOUR / HALF_DAY / DAY，**没有 INTERVAL_MINUTE**
         * （已用 javap 对 android-36 的 android.jar 核实过）。想当然写上去会直接编译不过。
         */
        private const val INTERVAL_ONE_MINUTE = 60_000L

        private fun scheduleNextTick(context: Context) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerAt = System.currentTimeMillis() + INTERVAL_ONE_MINUTE
            runCatching {
                manager.setInexactRepeating(
                    AlarmManager.RTC,
                    triggerAt,
                    INTERVAL_ONE_MINUTE,
                    tickIntent(context)
                )
            }
        }

        private fun cancelTick(context: Context) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching { manager.cancel(tickIntent(context)) }
        }

        private fun tickIntent(context: Context): PendingIntent {
            val intent = Intent(context, WidgetStopwatchProvider::class.java).setAction(ACTION_TICK)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_IMMUTABLE
                } else {
                    0
                }
            return PendingIntent.getBroadcast(context, TICK_REQUEST_CODE, intent, flags)
        }
    }
}
