package com.xbaos.shijianapp.appsys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R

/**
 * 更新日志：最新版本排在最上面，每个版本一张卡片。
 *
 * ## 数据从哪来
 *
 * 每发一个版本，往 [ENTRIES] 的**最前面**加一条（版本名 + 发布日期 + 正文）。
 * 正文是普通的多行字符串，行首用「·」手写列表 —— 不引入任何格式解析，
 * 长按复制时用户拿到的就是眼前所见的那段文字。
 *
 * ## 长按复制
 *
 * 用户要求日志文本支持长按复制：整段正文（含版本号与日期）一次复制，
 * 方便贴到群聊或反馈邮件里。复制成功给一条 Toast，不做别的。
 */
class Changelog : BaseActivity() {

    /**
     * 一条更新日志。正文必须走字符串资源（[bodyRes]）而不是硬编码 ——
     * 日志页也跟随应用语言，硬编码中文会在英/繁界面漏出来（实测踩过）。
     */
    private data class Entry(val version: String, val date: String, val bodyRes: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_changelog)

        findViewById<TextView>(R.id.page_title).setText(R.string.changelog_title)

        val container = findViewById<LinearLayout>(R.id.changelog_container)
        val inflater = LayoutInflater.from(this)

        ENTRIES.forEachIndexed { index, entry ->
            val card = buildCard(inflater, container, entry)
            if (index > 0) {
                // 卡片之间的空隙：除第一张外每张卡上面留一个组间距
                (card.layoutParams as? LinearLayout.LayoutParams)?.topMargin =
                    resources.getDimensionPixelSize(R.dimen.setting_group_gap)
            }
            container.addView(card)
        }
    }

    private fun buildCard(
        inflater: LayoutInflater,
        parent: ViewGroup,
        entry: Entry
    ): View {
        val body = getString(entry.bodyRes)
        val card = inflater.inflate(R.layout.item_changelog_entry, parent, false) as LinearLayout
        card.findViewById<TextView>(R.id.changelog_version).text =
            getString(R.string.changelog_version_format, entry.version, entry.date)
        card.findViewById<TextView>(R.id.changelog_body).text = body

        val fullText = getString(R.string.changelog_version_format, entry.version, entry.date) +
            "\n" + body
        card.setOnLongClickListener {
            copy(fullText)
            true
        }
        // 卡片本身不是 clickable 的，长按挂在卡片上即可；按压反馈交给 PressFeedback
        // 的 attachTree（本页根布局 id 是 main，进页时已统一挂载）。
        return card
    }

    private fun copy(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.changelog_title), text))
        Toast.makeText(this, R.string.changelog_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * 更新日志，**最新的在最前面**。
         * 版本名与 app/build.gradle.kts 的 versionName 对应；日期为发布日。
         */
        private val ENTRIES = listOf(
            Entry("2.0.13", "2026-09-30", R.string.changelog_2_0_13_body),
            Entry("2.0.12", "2026-09-26", R.string.changelog_2_0_12_body),
            Entry("2.0.11", "2026-09-26", R.string.changelog_2_0_11_body),
            Entry("2.0.10", "2026-09-26", R.string.changelog_2_0_10_body)
        )
    }
}
