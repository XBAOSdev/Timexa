package com.xbaos.shijianapp.appsys

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.widget.TextViewCompat
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.ui.ItemAnim

/**
 * 权限说明页。
 *
 * 为什么要单独开一页而不是弹窗：本次改动要求「显示得全一点」——
 * 每一条权限都带一段多行的用途说明（含「为什么需要」「不做哪些事」「拒绝后会怎样」），
 * 五六条叠起来远超一个居中弹窗能承载的长度，弹窗里只能内部滚动，读起来很憋。
 * 独立页面可以顺理成章地分组、留白、慢慢读，也和「关于页面」的形态一致。
 *
 * 内容分两组：
 *  · 需要手动授权的（定位 / 通知）—— 这些是用户在运行时真正会看到系统弹窗的；
 *  · 安装即获得、无需授权的（振动 / 网络 / 前台服务）—— 列出来才算「说明完整」，
 *    用户在看权限清单类工具时最容易被这几项吓到，提前讲清楚反而更透明。
 */
class Permissions : BaseActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        container = findViewById(R.id.permissions_container)
        findViewById<TextView>(R.id.page_title).setText(R.string.perm_page_title)

        buildUi()

        ItemAnim.staggerIn((0 until container.childCount).map { container.getChildAt(it) })
    }

    private fun buildUi() {
        container.removeAllViews()

        // 开篇说明：先给一句「本应用基本不需要权限」的总述，
        // 用户看到一长串清单之前不至于先紧张
        container.addView(introCard())

        addSection(
            titleRes = R.string.perm_page_section_manual,
            descRes = R.string.perm_page_section_manual_desc,
            entries = listOf(
                PermissionEntry(R.string.perm_location_name, R.string.perm_location_use),
                PermissionEntry(R.string.perm_notification_name, R.string.perm_notification_use)
            )
        )

        addSection(
            titleRes = R.string.perm_page_section_auto,
            descRes = R.string.perm_page_section_auto_desc,
            entries = listOf(
                PermissionEntry(R.string.perm_vibrate_name, R.string.perm_vibrate_use),
                PermissionEntry(R.string.perm_internet_name, R.string.perm_internet_use),
                PermissionEntry(R.string.perm_foreground_name, R.string.perm_foreground_use)
            )
        )

        addFooter()
    }

    /** 一个权限条目：名称（粗体）+ 用途（多行正文）。 */
    private data class PermissionEntry(
        @StringRes val nameRes: Int,
        @StringRes val useRes: Int
    )

    /** 开篇总述：一张卡片 + 一段正文。 */
    private fun introCard(): LinearLayout = card().apply {
        setPadding(pad(), pad(), pad(), pad())
        addView(bodyText(R.string.perm_page_intro))
    }

    /** 一个分组：分组标题 + 一张卡片（顶部副标题 + 若干条目，条目之间带分隔线）。 */
    private fun addSection(
        @StringRes titleRes: Int,
        @StringRes descRes: Int,
        entries: List<PermissionEntry>
    ) {
        container.addView(
            TextView(this).apply {
                TextViewCompat.setTextAppearance(this, R.style.Text_Caption)
                setText(titleRes)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimen(R.dimen.setting_group_gap)
                bottomMargin = dimen(R.dimen.setting_group_title_gap)
            }
        )

        val group = card()
        // 分组副标题放在卡片顶部：它是对「这一整组」的说明，不是对某一条的
        group.addView(
            bodyText(descRes).apply {
                setPadding(pad(), pad(), pad(), dimen(R.dimen.space_s))
            }
        )
        entries.forEachIndexed { index, entry ->
            if (index > 0) group.addView(divider())
            group.addView(buildEntry(entry))
        }
        container.addView(group)
    }

    private fun buildEntry(entry: PermissionEntry): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad(), dimen(R.dimen.space_m), pad(), dimen(R.dimen.space_m))
        addView(
            TextView(this@Permissions).apply {
                TextViewCompat.setTextAppearance(this, R.style.Text_Section)
                setText(entry.nameRes)
            }
        )
        addView(
            bodyText(entry.useRes).apply {
                setPadding(0, dimen(R.dimen.space_s), 0, 0)
            }
        )
    }

    /** 卡片容器：与全 App 同款的 bg_card。 */
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
    }

    /** 权限之间的分隔线：从卡片内缩一段，与设置页的行分隔线同一套规则。 */
    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayoutCompat.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dimen(R.dimen.stroke_hairline)
        ).apply {
            marginStart = dimen(R.dimen.divider_inset)
        }
        setBackgroundColor(getColor(R.color.stroke_soft))
    }

    /** 正文样式：多行、行距略松、次要文字色，保证长段落可读。 */
    private fun bodyText(@StringRes textRes: Int): TextView = TextView(this).apply {
        TextViewCompat.setTextAppearance(this, R.style.Text_Value)
        setText(textRes)
        setTextColor(getColor(R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
        setLineSpacing(dimen(R.dimen.line_spacing).toFloat(), 1f)
    }

    private fun addFooter() {
        container.addView(
            TextView(this).apply {
                TextViewCompat.setTextAppearance(this, R.style.Text_Caption)
                setText(R.string.perm_page_footer)
                gravity = Gravity.CENTER
                setLineSpacing(dimen(R.dimen.line_spacing_tight).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dimen(R.dimen.space_l) }
        )
    }

    private fun pad(): Int = dimen(R.dimen.card_padding)

    private fun dimen(resId: Int): Int = resources.getDimensionPixelSize(resId)
}
