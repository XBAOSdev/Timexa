package com.xbaos.shijianapp.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import com.xbaos.shijianapp.R

/**
 * 弹窗宽度：屏幕的 88%，且不超过 420dp。
 * 两个自绘弹窗共用，避免宽度规则分叉。
 */
private fun dialogWidthPx(context: Context): Int {
    val screen = context.resources.displayMetrics.widthPixels
    val max = (420 * context.resources.displayMetrics.density).toInt()
    return minOf((screen * 0.88f).toInt(), max)
}

/**
 * 全应用统一的自绘弹窗（居中卡片）。
 *
 * 为什么不用 AlertDialog：
 *  AlertDialog 的按钮配色由主题里 `colorAccent` / `colorPrimary` / 对话框按钮条样式
 *  联合推导，一旦自定义了 `windowBackground`（为了圆角底板），按钮就会掉进
 *  「白底白字」之类的不可见状态，且实际渲染结果随主题继承链变化，很难稳定控制。
 *  自绘之后，底板、按钮、文字全部走本项目的样式表，与其余页面同源，不会再漂移。
 *
 * 形态：居中卡片，宽度取屏幕的 88% 且不超过 420dp；底板 = 白/深色卡片 + 1dp 描边。
 * 按钮：一律成排等高，最多两个（否定在左、肯定在右），与页面按钮同款样式。
 */
class AppDialog private constructor(context: Context) : Dialog(context) {

    private val titleView: TextView
    private val messageView: TextView
    private val inputView: EditText
    private val negativeView: TextView
    private val positiveView: TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_app)

        titleView = findViewById(R.id.dialog_title)
        messageView = findViewById(R.id.dialog_message)
        inputView = findViewById(R.id.dialog_input)
        negativeView = findViewById(R.id.dialog_negative)
        positiveView = findViewById(R.id.dialog_positive)

        setCanceledOnTouchOutside(true)
        setCancelable(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            // 底板完全交给布局自绘，窗口本身不要任何背景，否则会露出系统方形白底。
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.42f)
            setGravity(Gravity.CENTER)
            setLayout(dialogWidthPx(context), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // 带输入框的弹窗：自动聚焦并弹出键盘。
        // ADJUST_RESIZE 让窗口在键盘出现时收缩，配合 CENTER 重力，
        // 卡片会被整体顶到键盘上方，而不是被键盘盖住半截。
        // 该常量在 API 30 被标记废弃（官方建议改用 setDecorFitsSystemWindows），
        // 但那条建议针对的是「边到边的 Activity 窗口」；弹窗窗口本来就不做 edge-to-edge，
        // 收缩让位仍是平台当前实际生效的行为，因此这里保留并显式抑制废弃警告。
        if (inputView.isVisible) {
            @Suppress("DEPRECATION")
            window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            inputView.requestFocus()
        }
    }

    private fun dialogWidth(context: Context): Int = dialogWidthPx(context)

    // ==================== 装配 ====================

    private fun title(text: CharSequence?) = apply {
        titleView.isVisible = !text.isNullOrEmpty()
        titleView.text = text
    }

    private fun message(text: CharSequence?) = apply {
        messageView.isVisible = !text.isNullOrEmpty()
        messageView.text = text
    }

    private fun inputField(
        initial: CharSequence?,
        hint: CharSequence?,
        multiline: Boolean
    ) = apply {
        inputView.isVisible = true
        inputView.hint = hint
        // 注意：CharSequence? 没有 orEmpty()（那个扩展只对 String? 存在），
        // 这里必须先 toString 再兜底。
        inputView.setText(initial?.toString().orEmpty())
        inputView.setSelection(inputView.text.length)
        inputView.inputType = if (multiline) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        inputView.maxLines = if (multiline) 4 else 1
    }

    private fun buttons(
        positive: CharSequence,
        negative: CharSequence? = null,
        positiveIsDanger: Boolean = false
    ) = apply {
        positiveView.text = positive
        positiveView.isVisible = positive.isNotEmpty()
        // 危险操作换用破坏性配色，与页面里的删除/重置按钮一致
        positiveView.setBackgroundResource(
            if (positiveIsDanger) R.drawable.bg_btn_danger else R.drawable.bg_btn_primary
        )
        positiveView.setTextColor(
            context.getColor(if (positiveIsDanger) R.color.danger else R.color.text_on_accent)
        )

        negativeView.isVisible = !negative.isNullOrEmpty()
        negativeView.text = negative
        // 只有否定按钮时，让它占满整行，避免出现半宽按钮孤立在右侧
        (negativeView.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            lp.width = if (negativeView.isVisible) 0 else ViewGroup.LayoutParams.MATCH_PARENT
            lp.weight = if (negativeView.isVisible) 1f else 0f
            negativeView.layoutParams = lp
        }

        // 弹窗按钮同样要有按压反馈与触觉。
        // ⚠ 必须在这里显式挂：Dialog 的视图树挂在**自己的 Window** 上，不在 Activity 的
        // `android.R.id.content` 之下，所以 BaseActivity 里那趟 `PressFeedback.attachTree`
        // 根本遍历不到它 —— 这正是「页面里点了会振、弹窗按钮却不振」的原因。
        PressFeedback.attach(positiveView)
        PressFeedback.attach(negativeView)
    }

    // ==================== 对外入口 ====================

    companion object {

        /** 单按钮提示。用于「使用说明」「我知道了」这类纯告知场景。 */
        fun info(
            context: Context,
            title: CharSequence?,
            message: CharSequence?,
            positive: CharSequence,
            onPositive: (() -> Unit)? = null
        ) {
            AppDialog(context).title(title).message(message)
                .buttons(positive)
                .apply {
                    positiveView.setOnClickListener {
                        dismiss()
                        onPositive?.invoke()
                    }
                }
                .show()
        }

        /** 双按钮确认。否定按钮不做任何事时传 null 委托即可。 */
        fun confirm(
            context: Context,
            title: CharSequence?,
            message: CharSequence?,
            positive: CharSequence,
            negative: CharSequence?,
            positiveIsDanger: Boolean = false,
            onPositive: () -> Unit,
            onNegative: (() -> Unit)? = null
        ) {
            AppDialog(context).title(title).message(message)
                .buttons(positive, negative, positiveIsDanger)
                .apply {
                    positiveView.setOnClickListener {
                        dismiss()
                        onPositive()
                    }
                    negativeView.setOnClickListener {
                        dismiss()
                        onNegative?.invoke()
                    }
                }
                .show()
        }

        /**
         * 带输入框的弹窗。
         * [onConfirm] 返回 true 表示校验通过、可以关闭；返回 false 则保持打开（例如名称为空）。
         * [onClosed] 在弹窗关闭后回调一次。用于「确认后还要接一个系统选择器」这类两步流程 ——
         * 直接在 onConfirm 里弹下一个窗口会叠在正在消失的弹窗之上，观感很差。
         */
        fun input(
            context: Context,
            title: CharSequence?,
            message: CharSequence? = null,
            hint: CharSequence? = null,
            initial: CharSequence? = null,
            multiline: Boolean = false,
            positive: CharSequence,
            negative: CharSequence?,
            onConfirm: (String) -> Boolean,
            onClosed: (() -> Unit)? = null
        ) {
            AppDialog(context).title(title).message(message)
                .inputField(initial, hint, multiline)
                .buttons(positive, negative)
                .apply {
                    positiveView.setOnClickListener {
                        val text = inputView.text.toString().trim()
                        if (onConfirm(text)) dismiss()
                    }
                    negativeView.setOnClickListener { dismiss() }
                    if (onClosed != null) {
                        setOnDismissListener { onClosed.invoke() }
                    }
                }
                .show()
        }

        /**
         * 竖向选择列表。
         *
         * 为什么需要它：番茄钟的「时长 / 轮数」候选值有 8 档，塞进横向分段控件会让每格窄到
         * 点不准；而为一个数值开一个独立的设置页又太重。竖向列表是这类「多选一」的标准形态，
         * 点中即生效并关闭，不需要再来一次「确定」。
         *
         * @param selected 当前值对应的下标，会用主按钮样式标出，让用户一眼看到现状。
         */
        fun choice(
            context: Context,
            title: CharSequence,
            message: CharSequence? = null,
            options: List<CharSequence>,
            selected: Int,
            negative: CharSequence,
            onPick: (Int) -> Unit
        ) {
            AppChoiceDialog(context, title, message, options, selected, negative, onPick).show()
        }
    }
}

/**
 * 竖向选择弹窗。
 *
 * 与 [AppDialog] 同源：同样的透明窗口 + 自绘底板 + 同样的宽度规则，
 * 区别只是「一排按钮」换成了「一列按钮」。分开成两个类是因为内容差异
 * （列表要动态生成、要可滚动）比复用带来的收益更大。
 */
class AppChoiceDialog(
    context: Context,
    private val titleText: CharSequence,
    private val messageText: CharSequence?,
    private val options: List<CharSequence>,
    private val selectedIndex: Int,
    private val negativeText: CharSequence,
    private val onPick: (Int) -> Unit
) : Dialog(context) {

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_choice)
        setCanceledOnTouchOutside(true)
        setCancelable(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.42f)
            setGravity(Gravity.CENTER)
            setLayout(dialogWidthPx(context), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        findViewById<TextView>(R.id.dialog_title).text = titleText
        findViewById<TextView>(R.id.dialog_message).apply {
            isVisible = !messageText.isNullOrEmpty()
            text = messageText
        }

        val container = findViewById<LinearLayout>(R.id.dialog_options)
        options.forEachIndexed { index, label ->
            container.addView(buildOption(container, label, index == selectedIndex, index))
        }

        val scroll = findViewById<ScrollView>(R.id.dialog_options_scroll)
        // 选项多时限制高度并内部滚动：布局测量完成后才知道真实高度，
        // 因此放在 post 里判断，避免弹窗在小屏上顶到屏幕外。
        // 注意：Dialog 本身不是 Context，没有 resources 属性，必须从 context 取。
        val maxHeight = context.resources.getDimensionPixelSize(R.dimen.dialog_options_max_height)
        scroll.post {
            if (scroll.height > maxHeight) {
                scroll.layoutParams = scroll.layoutParams.also { it.height = maxHeight }
            }
        }

        findViewById<TextView>(R.id.dialog_negative).apply {
            text = negativeText
            setOnClickListener { dismiss() }
            // 弹窗内的视图不在 Activity 的 content 树下，attachTree 扫不到，必须显式挂
            PressFeedback.attach(this)
        }
    }

    private fun buildOption(parent: ViewGroup, label: CharSequence, isSelected: Boolean, index: Int): TextView {
        val option = LayoutInflater.from(context)
            .inflate(R.layout.item_choice_option, parent, false) as TextView
        option.text = label
        option.setBackgroundResource(
            if (isSelected) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary
        )
        option.setTextColor(
            context.getColor(if (isSelected) R.color.text_on_accent else R.color.text_primary)
        )
        option.setOnClickListener {
            dismiss()
            onPick(index)
        }
        // 同 AppDialog：弹窗视图不在 Activity 的 content 树下，attachTree 遍历不到
        PressFeedback.attach(option)
        return option
    }
}
