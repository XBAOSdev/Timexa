package com.xbaos.shijianapp.ui

import android.view.View
import com.xbaos.shijianapp.R

/**
 * 列表项的「依次浮现」入场动画。
 *
 * 为什么只做一次：
 *  入场动画属于「第一次看到这一屏」的引导，属于一次性装饰。
 *  如果每次 onResume / 数据刷新都重放，用户从设置页返回都会被晃一下，
 *  那不是丝滑，是干扰 —— 所以用 View 上的一个标记位保证每个列表项只播一次。
 *
 * 为什么用逐条延迟（stagger）而不是整体淡入：
 *  统一延迟会让整屏像一块板子一起亮起来，仍然显得「生硬」。
 *  每条错开一小步（默认 30ms、上限 8 条）之后，视线会被自然地从上往下引导，
 *  这是列表里「丝滑感」最廉价也最有效的一招。
 */
object ItemAnim {

    /**
     * 让一批列表项依次浮现。
     *
     * @param views 按自上而下的顺序传入
     */
    fun staggerIn(views: List<View>) {
        if (views.isEmpty()) return

        val resources = views[0].resources
        val duration = resources.getInteger(R.integer.list_item_anim_duration).toLong()
        val step = resources.getInteger(R.integer.list_item_stagger_step).toLong()
        val maxIndex = resources.getInteger(R.integer.list_item_stagger_max_index)

        views.forEachIndexed { index, view ->
            // 用资源 id 作为 key 的普通 tag（ViewCompat 没有带 key 的 getTag/setTag 重载）
            if (view.getTag(KEY_ANIMATED) == true) return@forEachIndexed
            view.setTag(KEY_ANIMATED, true)

            // 位移只走 8dp：素净的界面里，大幅位移会显得浮夸
            val offset = resources.displayMetrics.density * START_TRANSLATION_DP
            view.alpha = 0f
            view.translationY = offset
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index.coerceAtMost(maxIndex) * step)
                .setDuration(duration)
                .start()
        }
    }

    private const val START_TRANSLATION_DP = 8f

    private val KEY_ANIMATED = R.id.item_anim_tag
}
