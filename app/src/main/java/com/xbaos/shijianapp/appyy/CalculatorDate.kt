package com.xbaos.shijianapp.appyy

import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.xbaos.shijianapp.BaseActivity
import com.xbaos.shijianapp.R
import com.xbaos.shijianapp.appsys.AppClock
import com.xbaos.shijianapp.ui.FeedbackKind
import com.xbaos.shijianapp.ui.Haptics
import com.xbaos.shijianapp.ui.PressFeedback
import com.xbaos.shijianapp.ui.SegmentAnim
import java.text.DateFormat
import java.text.ParsePosition
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 只关心年月日的轻量日期模型（month 为 0 基，与 Calendar 保持一致）。
 *
 * 全部时间取值走 [AppClock]（应用时区）：本文件是时区功能里少数**真的会算错**的地方 ——
 * 手动指定一个与系统跨日不同的时区后，若还用系统默认时区去解释「今天」「零点」，
 * 结果会整体差一天。
 */
private data class SimpleDate(val year: Int, val month: Int, val day: Int) {

    /**
     * 按当前语言输出日期。
     * 用 DateFormat 而不是手写 `yyyy年M月d日`：简中输出「2026年9月20日」、
     * 繁中同样、英文输出「Sep 20, 2026」，一处解决问题。
     */
    fun display(locale: Locale): String =
        AppClock.dateFormat(DateFormat.MEDIUM, locale).format(toDate())

    fun toDate(): Date = AppClock.calendar().apply {
        clear()
        set(year, month, day)
    }.time

    companion object {
        fun today(): SimpleDate = AppClock.calendar().let {
            SimpleDate(it.get(Calendar.YEAR), it.get(Calendar.MONTH), it.get(Calendar.DAY_OF_MONTH))
        }

        /** 只取年月日，丢掉时分秒。 */
        fun of(date: Date): SimpleDate =
            AppClock.calendar().apply { time = date }.let {
                SimpleDate(
                    it.get(Calendar.YEAR),
                    it.get(Calendar.MONTH),
                    it.get(Calendar.DAY_OF_MONTH)
                )
            }
    }
}

/**
 * 快捷日期条里的一条候选：相对**某一格当前日期**的一个偏移。
 *
 * ⚠ 写成「偏移量」而不是「今天 ± N」，是这一版的关键：
 *   用户点完一枚胶囊后浮层**不收**，下一枚要以**刚填进去的那一天**为原点继续加减
 *   （连点「一周后」就是 7 天、14 天、21 天……）。若把它锚定在今天，连点第二下就原地不动了。
 *   随之而来的措辞调整：候选名从「昨天 / 明天」改成「前一天 / 后一天」——
 *   原点可能是任意一天，「昨天」这个说法就不再成立（详见 strings 里的同名注释）。
 *
 * 「今天」是唯一的例外：它是一枚**复位**键，职责就是「不管现在填的是哪天，拉回今天」，
 * 所以单独标 [absolute]，不跟随原点。
 */
private class QuickPick(
    @StringRes val label: Int,
    val days: Int = 0,
    val months: Int = 0,
    /** true = 恒指今天（复位用），不跟随原点。 */
    val absolute: Boolean = false
) {

    /**
     * 从 [base] 出发偏移之后是哪一天。
     * 月份溢出交给 Calendar 自己夹取（3月31日往前一个月 → 2月28日），
     * 与用户直觉一致，不必自己判月末。
     */
    fun resolveFrom(base: SimpleDate): SimpleDate {
        if (absolute) return SimpleDate.today()
        val calendar = AppClock.calendar().apply {
            clear()
            set(base.year, base.month, base.day)
        }
        if (days != 0) calendar.add(Calendar.DAY_OF_MONTH, days)
        if (months != 0) calendar.add(Calendar.MONTH, months)
        return SimpleDate(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
    }
}

private val QUICK_TODAY = QuickPick(R.string.calc_quick_today, absolute = true)
private val QUICK_PREV_DAY = QuickPick(R.string.calc_quick_prev_day, days = -1)
private val QUICK_NEXT_DAY = QuickPick(R.string.calc_quick_next_day, days = 1)
private val QUICK_WEEK_AGO = QuickPick(R.string.calc_quick_week_ago, days = -7)
private val QUICK_WEEK_LATER = QuickPick(R.string.calc_quick_week_later, days = 7)
private val QUICK_MONTH_AGO = QuickPick(R.string.calc_quick_month_ago, months = -1)
private val QUICK_MONTH_LATER = QuickPick(R.string.calc_quick_month_later, months = 1)

/**
 * 日期计算器：相差几天 + 推算日期。
 *
 * ## 这一版做了什么（功能未变，全部是可读性）
 *
 * 1. **两种问法提到页面级分段**，表单只剩一句句子：「从 X 到 Y」/「从 X 往前 N 天」。
 *    旧版是两张结构雷同的卡片（标题都是功能名、高度还不同），外加 5 个名词标签。
 * 2. **结果去框、放大居中**：旧版结果框与输入框在圆角、高度、内边距、字号上完全相同、
 *    底色只差 5 个灰阶，页面上没有「答案」这个角色。
 * 3. **「从」默认今天是逻辑默认值，不是预填值**：输入框平时只显示示例提示，
 *    点计算时才把今天写进去（见 [calculateDayDifference]）。这样用户不必每次先清掉一个
 *    自己不想要的日期，而算出来的结果又永远有出处可查。
 * 4. **快捷日期条贴在当前焦点的输入框上方**浮出（[showQuickBar]）：
 *    页面有两个日期框，常驻一排快捷项无法表达「点它填到哪一格」。
 *    点中一枚之后浮层**不收**，并且下一枚以**刚填进去的那一天**为原点继续加减 ——
 *    连点「一周后」就是 7 天、14 天、21 天（见 [QuickPick] 的注释）。
 *
 * ## 日期输入支持两条路
 *
 *  - 点右侧的日历图标走系统日期选择器；
 *  - 直接在输入框里手输。显示按当前语言的日期格式，但解析同时接受
 *    `2026-09-20` / `2026/9/20` / `2026.9.20` / `20260920` 等写法 ——
 *    选择器要滚到几十年前很慢，手输是刚需。
 * 解析失败的输入标红，并按「未选择」处理，于是后续提示与没填时完全一致。
 *
 * ## 逻辑上修掉的旧版缺陷（沿用至今）
 *
 *  1. 差值计算直接用两个「带当前时分秒」的 Calendar 相减再整除 86400000，
 *     在夏令时切换日会差 1 天；改为归零到本地 00:00 后按天四舍五入。
 *  2. 旧版用 `tvStartDate.text == "请选择日期"` 判断「是否已选择」，
 *     任何文案调整都会静默让校验失效；改为用可空状态判断。
 */
class CalculatorDate : BaseActivity() {

    // ==================== 视图 ====================

    private lateinit var scroll: ScrollView
    private lateinit var modeSegment: LinearLayout
    private lateinit var modeUnits: List<TextView>
    private lateinit var rowDiff: View
    private lateinit var rowShift: View
    private lateinit var dirSegment: LinearLayout
    private lateinit var dirUnits: List<TextView>
    private lateinit var etDays: EditText
    private lateinit var tvDaysUnit: TextView
    private lateinit var btnCalculate: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvResultAux: TextView

    /** 答案区当前是不是在展示「算过的东西」（结果或错误）——用来判断有没有东西需要作废。 */
    private var answerShowing = false

    /** 差值模式的「从」；推算模式下它是「从（基准日期）」—— 两模式共用同一格，切模式不丢已输入的内容。 */
    private lateinit var startField: DateField

    /** 差值模式的「到」。推算模式下整行隐藏。 */
    private lateinit var endField: DateField

    // ==================== 状态 ====================

    /** 当前是不是「相差几天」模式。整页只切这一行的第二行视图。 */
    private var diffMode = true

    /** 推算方向：true = 往后（加），false = 往前（减）。 */
    private var addMode = true

    /** 输入框的正常文字色，用来把「解析失败」的标红还原回来。 */
    private var normalFieldColor = 0

    /** 三种状态下答案区的字号（px）。字号在这里切而不是写死在样式里，见 renderAnswer 的注释。 */
    private var answerPx = 0f
    private var answerDatePx = 0f
    private var bodyPx = 0f

    /**
     * 快捷日期条的浮层状态。
     *
     * 记的是 [quickField]（而不是锚点 View）：胶囊要以「这一格当前的日期」为原点，
     * 而日期只存在于 [DateField] 上，光有一个 EditText 拿不到。
     */
    private var quickBar: PopupWindow? = null
    private var quickBarView: LinearLayout? = null
    private var quickField: DateField? = null

    /** 退场动画正在跑。此刻浮层还画在屏幕上，但逻辑上已经收掉了。 */
    private var quickDismissing = false

    /** 浮层实测宽高。⚠ 不能用 popup.width —— 那是构造时传的 WRAP_CONTENT(-2)，不是真实尺寸。 */
    private var quickW = 0
    private var quickH = 0

    /** 浮层当前落在输入框**下方**（而不是上方）—— 决定进出场动画从哪条边「长」出来。 */
    private var quickBelow = false

    /** 刚被点过、正在「亮着」的那一枚胶囊。 */
    private var flashingChip: TextView? = null

    /**
     * 胶囊反馈的延时任务挂在这里，而不是挂在胶囊自己的消息队列上：
     * 胶囊每次弹出都会整体重建，挂在旧胶囊上的任务会随它一起变成孤儿。
     */
    private val handler = Handler(Looper.getMainLooper())

    /** 胶囊「亮一下」结束，落回常态。 */
    private val quickChipFlashOff = Runnable {
        flashingChip?.isSelected = false
        flashingChip = null
    }

    /** 当前生效的语言，用于日期格式化与解析。 */
    private val locale: Locale
        get() = resources.configuration.locales[0] ?: Locale.getDefault()

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calculator_date)

        scroll = findViewById(R.id.calcScroll)
        modeSegment = findViewById(R.id.calcModeSegment)
        rowDiff = findViewById(R.id.rowDiff)
        rowShift = findViewById(R.id.rowShift)
        dirSegment = findViewById(R.id.calcDirSegment)
        etDays = findViewById(R.id.etDays)
        tvDaysUnit = findViewById(R.id.tvDaysUnit)
        btnCalculate = findViewById(R.id.btnCalculate)
        tvResult = findViewById(R.id.tvResult)
        tvResultAux = findViewById(R.id.tvResultAux)

        findViewById<TextView>(R.id.page_title).setText(R.string.app_calc_name)

        normalFieldColor = ContextCompat.getColor(this, R.color.text_primary)
        answerPx = resources.getDimension(R.dimen.text_large_title)
        answerDatePx = resources.getDimension(R.dimen.text_answer_date)
        bodyPx = resources.getDimension(R.dimen.text_body)

        // 示例提示里的日期**取今天**：提示的职责是告诉用户「连打 8 个数字就行」，
        // 用一个已经过去的固定日期当例子，反而会让人怀疑格式是不是写死的。
        val hint = getString(R.string.calc_date_hint, compactToday())
        startField = DateField(findViewById(R.id.etStartDate), findViewById(R.id.btnSelectStartDate))
        endField = DateField(findViewById(R.id.etEndDate), findViewById(R.id.btnSelectEndDate))
        listOf(startField, endField).forEach { it.bind(hint) }

        btnCalculate.setOnClickListener { calculate() }

        setUpDirSegment()
        setUpDaysUnit()
        // setUpModeSegment 内部会走一次 setDiffMode(true)：段选中态、第二行显隐、按钮文案、
        // 答案区空态都只在这一个出口设置，不另写一遍初始状态。
        setUpModeSegment()

        // 浮层要跟着输入框走，而不是「一滚动就收起」：
        // 这一页在键盘弹出时会被 adjustResize 压小，系统为了让光标可见会把 ScrollView 滚一点，
        // 若把滚动当成「用户走开了」，就会变成**一点输入框快捷条自己消失**、功能直接失效。
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(
            ViewTreeObserver.OnGlobalLayoutListener { repositionQuickBar() }
        )
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> repositionQuickBar() }
    }

    override fun onPause() {
        super.onPause()
        // 浮层属于当前窗口，窗口一走它就没有意义了。
        // PopupWindow 自己也会在 token 失效时收掉，这里显式收一次，顺便清掉引用，别让它挂着 Activity。
        // ⚠ 这里**不要**走退场动画：页面已经在离开，动画只会变成一具没人看的空转，
        //   而且退场是靠 withEndAction 里的 dismiss 收尾的，页面先没了就会留下一个悬空的浮层。
        dismissQuickBar(animated = false)
    }

    /**
     * ⚠⚠ 必须在 `super` 之前挂分段控件的按压反馈。
     *
     * `super.onContentChanged()` 里那趟 `PressFeedback.attachTree` 会给**所有 clickable 节点**
     * 挂上默认的轻触语义（[FeedbackKind.LIGHT]），而 `attach()` 有「已挂过就跳过」的保护（靠 tag 判断）——
     * 一旦它先跑，这里就再也改不动手感了，分段换档永远只能振成普通点击。
     *
     * 此刻视图树已经建立、但还没测量（这也是 SegmentAnim 要 post 到测量后再落位的原因），
     * 所以这里只挂反馈，不碰任何几何。
     */
    override fun onContentChanged() {
        listOf(R.id.modeDiff, R.id.modeShift, R.id.dirBefore, R.id.dirAfter).forEach { id ->
            findViewById<View>(id)?.let { unit ->
                // 「在一串离散选项间切换」的专用语义，与设置页的分段保持一致。
                PressFeedback.attach(unit) { FeedbackKind.segmentTick() }
            }
        }
        super.onContentChanged()
    }

    // ==================== 模式分段 ====================

    /**
     * 页面级模式分段。
     *
     * ⚠ 点击处理必须放在段单元**全部生成之后**：回调里要统一刷新兄弟节点的选中态，
     *   而在这个位置直接引用 modeUnits 会构成「val 在自己的初始化表达式里引用自己」，无法编译。
     * ⚠ 首帧就调 [setDiffMode]（而不是另写一遍初始状态）：段选中态、第二行显隐、按钮文案、
     *   答案区空态只有这一个出口，以后加状态不会漏掉某一条路径。
     */
    private fun setUpModeSegment() {
        modeUnits = listOf(findViewById(R.id.modeDiff), findViewById(R.id.modeShift))
        modeUnits.forEachIndexed { index, unit ->
            unit.setOnClickListener { setDiffMode(index == 0) }
        }
        setDiffMode(true)
    }

    /**
     * 切换整页的问法。
     *
     * 两套表单共用同一个卡片、只换第二行 —— 所以切换时**已经输入的日期不会丢**
     * （若改成两份 include，除了 id 会重复，两组输入框各自独立也会把日期弄丢）。
     */
    private fun setDiffMode(isDiff: Boolean) {
        diffMode = isDiff
        val index = if (isDiff) 0 else 1
        modeUnits.forEachIndexed { i, unit -> unit.isSelected = i == index }
        SegmentAnim.moveTo(modeSegment, modeUnits, index, animate = true)

        rowDiff.isVisible = isDiff
        rowShift.isVisible = !isDiff
        btnCalculate.setText(if (isDiff) R.string.calc_action_days else R.string.calc_action_date)
        dismissQuickBar()
        // 换了问法，上一次的答案就不再对应屏幕上这个问题了，回到空态。
        renderIdle()

        if (!isDiff) {
            // ⚠⚠ 顺序有讲究：必须**先让 rowShift 可见**，再给方向分段落位。
            //   SegmentAnim.moveTo 在目标格宽度为 0 时会 post 重试（等测量完成），
            //   而一条 GONE 的分段永远量不出宽度 —— 先落位就会变成每帧 post 一次的空转。
            setAddMode(addMode)
        }
    }

    /**
     * 推算方向：往前 / 往后。
     * 只在 [setDiffMode] 切到推算模式（此时 rowShift 已可见）以及用户点击时调用。
     */
    private fun setAddMode(isAdd: Boolean) {
        addMode = isAdd
        val index = if (isAdd) 1 else 0
        dirUnits.forEachIndexed { i, unit -> unit.isSelected = i == index }
        SegmentAnim.moveTo(dirSegment, dirUnits, index, animate = true)
    }

    private fun setUpDirSegment() {
        dirUnits = listOf(findViewById(R.id.dirBefore), findViewById(R.id.dirAfter))
        dirUnits.forEachIndexed { index, unit ->
            unit.setOnClickListener { setAddMode(index == 1) }
        }
    }

    // ==================== 天数后面的量词 ====================

    /**
     * 量词跟着输入的数字走：英文里「1」后面该是 `day` 而不是 `days`。
     * 走 plurals 而不是自己拼字符串，规则就交给系统按语言来选。
     */
    private fun setUpDaysUnit() {
        etDays.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateDaysUnit()
                // 改了天数，上一次推算出来的那个日期就不作数了。
                invalidateAnswer()
            }
        })
        updateDaysUnit()
    }

    private fun updateDaysUnit() {
        val days = etDays.text.toString().trim().toIntOrNull() ?: 0
        tvDaysUnit.text = resources.getQuantityString(R.plurals.calc_days_unit, days)
    }

    // ==================== 日期输入（手输 + 选择器 + 快捷条） ====================

    /**
     * 一组「日期输入框 + 日历按钮」。
     *
     * 两个入口共用一套提交逻辑：手输在失焦或按完成键时解析，选择器与快捷条直接写入。
     * 收成一个内部类是为了让两个日期字段（差值「从 / 到」）不各写一份。
     *
     * ⚠ [input] 必须是 **public 于本类**（不能写 private）：快捷条要拿它当锚点、
     *   要往它里面写日期，而 **Kotlin 不允许外部类访问内部类的 private 成员**
     *   （Java 允许，Kotlin 不允许 —— 编译直接报 "Cannot access 'val input'"）。
     *   本类自身是 private inner class，所以这些成员的实际可见范围仍然只有本文件。
     */
    private inner class DateField(val input: EditText, private val button: TextView) {

        var value: SimpleDate? = null
            private set

        /**
         * 「这次文本变化是**我们用代码**写进去的」。
         *
         * 用途只有一个：快捷条只在**用户自己打字**时收起。
         * 代码填进去的不算，否则一点胶囊就把整条收掉，用户想连点两下都做不到。
         */
        private var writing = false

        fun bind(hint: String) {
            input.hint = hint
            input.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showQuickBar(this)
                } else {
                    syncFromInput()
                    dismissQuickBar()
                }
            }
            // 点一个**已经获得焦点**的输入框不会再触发 onFocusChange，所以点击也要负责弹出。
            input.setOnClickListener { showQuickBar(this) }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!writing) {
                        dismissQuickBar()
                        // 输入一变，上一次的答案就不再对应屏幕上这些输入了（详见 invalidateAnswer）。
                        invalidateAnswer()
                    }
                }
            })
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    // 只走失焦这一条路径：clearFocus 会触发上面的监听器，避免两处提交逻辑
                    input.clearFocus()
                    hideKeyboard(input)
                    true
                } else {
                    false
                }
            }
            button.setOnClickListener {
                // 先把已手输的内容落定，再让选择器覆盖它；
                // 否则用户手输一半又去点日历，输入框里会留着一段没被采纳的文本。
                dismissQuickBar()
                input.clearFocus()
                pickDate(value) { fromPicker(it) }
            }
            PressFeedback.attach(button)
        }

        /**
         * 把输入框里的文本解析成日期：空 → 未选择；认不出 → 标红并当作未选择。
         *
         * 除了失焦，各「计算」按钮在读取 [value] 之前也必须先调它 ——
         * 用户输完直接按系统返回键收起键盘时，输入框并不会失焦，
         * 少了这一步就会拿着上一次的旧值去算。
         */
        fun syncFromInput() {
            val raw = input.text.toString().trim()
            if (raw.isEmpty()) {
                value = null
                input.setTextColor(normalFieldColor)
                return
            }
            val parsed = parseDate(raw)
            if (parsed == null) {
                value = null
                input.setTextColor(ContextCompat.getColor(this@CalculatorDate, R.color.warning))
            } else {
                fromPicker(parsed)
            }
        }

        /** 由日期选择器 / 快捷条写入（一定合法）。 */
        fun fromPicker(picked: SimpleDate) {
            value = picked
            input.setTextColor(normalFieldColor)
            writing = true
            input.setText(picked.display(locale))
            input.setSelection(input.text.length)
            writing = false
        }
    }

    /** 打开日期选择器：默认停在「当前已选日期」，而不是每次都跳回今天。 */
    private fun pickDate(current: SimpleDate?, onPicked: (SimpleDate) -> Unit) {
        val init = current ?: SimpleDate.today()
        DatePickerDialog(
            this,
            { _, year, month, day -> onPicked(SimpleDate(year, month, day)) },
            init.year,
            init.month,
            init.day
        ).show()
    }

    /**
     * 纯数字写法的候选格式，按顺序尝试。
     * 用 Locale.US 构造，避免受当前语言影响（这些写法本身与语言无关）。
     */
    private val numericDatePatterns = listOf(
        "yyyy-M-d", "yyyy/M/d", "yyyy.M.d", "yyyy MM d", "yyyyMMdd"
    )

    /**
     * 解析用户手输的日期，认不出时返回 null。
     *
     * 先试几种纯数字写法（输入框 hint 教的就是 `20260930`），再试当前语言的日期格式
     * （简中「2026年9月20日」、英文「Sep 20, 2026」）—— 输入框平时显示的就是这个写法，
     * 改一两个数字不必换格式。
     *
     * 一律用「严格」模式：宽松模式下 SimpleDateFormat 会把 2026-02-30 顺延成 3-2，
     * 用户看着自己没输错、结果却悄悄换了一天。
     *
     * ⚠⚠ 严格模式**管不到年份位数**：`yyyy` 并不要求真的写满四位，
     *    「Oct 6, 202」（删掉一位的半成品）会被它收下、当成公元 202 年，
     *    于是「9月29日到10月6日」变成 666196 天。所以年份位数必须自己再数一遍。
     */
    private fun parseDate(raw: String): SimpleDate? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val groups = digitGroups(text)

        val numeric = when {
            // 紧凑写法只有一段数字，必须正好 8 位（yyyyMMdd）
            groups.size == 1 -> groups[0].length == 8
            // 分隔写法（2026-9-30 / 2026 9 30）：本页 5 种候选的年份都在最前，看第一段
            groups.size > 1 -> groups[0].length == 4
            else -> false
        }
        if (numeric) {
            for (pattern in numericDatePatterns) {
                val format = AppClock.formatter(pattern, Locale.US).apply { isLenient = false }
                parseFully(format, text)?.let { return SimpleDate.of(it) }
            }
        }

        val localized = AppClock.dateFormat(DateFormat.MEDIUM, locale)
            .apply { isLenient = false }
        parseFully(localized, text)?.let { date ->
            if (isFullYear(date, groups)) return SimpleDate.of(date)
        }
        return null
    }

    /** 文本里被非数字隔开的数字段，如「Sep 20, 2026」→ `["20", "2026"]`。 */
    private fun digitGroups(text: String): List<String> =
        DIGIT_GROUP.findAll(text).map { it.value }.toList()

    /**
     * [text] 里那个恰好 4 位的数字段，是不是就是解析出来的年份。
     *
     * 用「恰好 4 位」而不是「最后一段」定位年份：各语言的年份位置不一样
     * （英文在末尾 `Sep 20, 2026`，简中在开头 `2026年9月20日`），
     * 只有宽度是跨语言都成立的特征。找不到、或找到多个，都按「没写全」处理。
     */
    private fun isFullYear(date: Date, groups: List<String>): Boolean {
        val year = groups.singleOrNull { it.length == 4 }?.toIntOrNull() ?: return false
        return AppClock.calendar().apply { time = date }.get(Calendar.YEAR) == year
    }

    /**
     * 用 [format] 解析 [text]，要求**从头到尾全部消费掉**才算成功。
     * 直接调 `parse()` 不校验剩余字符，会把「2026-09-20xxx」这类输入也当合法。
     */
    private fun parseFully(format: DateFormat, text: String): Date? {
        val position = ParsePosition(0)
        val parsed = format.parse(text, position) ?: return null
        return if (position.index == text.length) parsed else null
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /** 手输格式下的今天（如 20260930），用作输入框的示例。 */
    private fun compactToday(): String {
        val today = SimpleDate.today()
        return String.format(Locale.US, "%04d%02d%02d", today.year, today.month + 1, today.day)
    }

    // ==================== 快捷日期条 ====================

    /**
     * 这一格该推荐哪几条。
     *
     * ## 原点（候选相对哪一天偏移）
     *
     * 「连点继续增减」要求候选相对**本格当前的日期**，所以按下面的顺序取原点：
     *  1. 本格已经有日期 → 就是它（点「一周后」得 +7 天，再点一次再 +7 天）；
     *  2. 本格为空、另一格有日期 → 借另一格的日期。差值的「从 / 到」本就是一个区间的两端，
     *     空着的那一头顺着已定的那一头往外推最自然：填了「从」10月1日，
     *     「到」的候选就直接落在 10月2日 / 10月8日 上，不必先回到今天再绕一圈；
     *  3. 两格都空 → 今天。
     *
     * ⚠ 第 2 条只在**差值模式**下成立。推算模式里 rowDiff 是隐藏的，但「到」里
     *   可能还留着切模式之前填的日期 —— 拿它当原点，用户会看着一个自己根本看不见的基准
     *   算出结果来。
     *
     * ## 候选集
     *
     * 两套模式、每一格都从同一批候选里挑一个子集，**外观完全一致**，只有方向不同：
     *  · 差值的「从」往回看（它在定区间的起点，多半是已经过去的日子）；
     *  · 差值的「到」往前看（它在定终点）；
     *  · 推算模式的「从（基准日期）」不分方向（方向另有「往前 / 往后」分段在管），给一组不对称的。
     * 最后滤掉「解析结果正好等于另一格」的那一条 —— 点它只会得到「相差 0 天」，是无效推荐。
     */
    private fun quickPicksFor(field: DateField): List<QuickPick> {
        val base = baseFor(field)
        val all = when {
            !diffMode -> listOf(QUICK_TODAY, QUICK_PREV_DAY, QUICK_NEXT_DAY, QUICK_WEEK_LATER)
            field === endField -> listOf(QUICK_TODAY, QUICK_NEXT_DAY, QUICK_WEEK_LATER, QUICK_MONTH_LATER)
            else -> listOf(QUICK_TODAY, QUICK_PREV_DAY, QUICK_WEEK_AGO, QUICK_MONTH_AGO)
        }
        if (!diffMode) return all

        val other = if (field === endField) startField.value else endField.value
        // 顺带去重：原点本身可能就落在某条候选的落点上（例如本格已经是「一个月前」），
        // 那样两条候选会指向同一天，留先出现的那条。
        val seen = mutableSetOf<SimpleDate>()
        return all.filter { pick ->
            val date = pick.resolveFrom(base)
            date != other && seen.add(date)
        }
    }

    /**
     * 快捷条的**原点**：本格当前日期优先，本格为空才借另一格，都空才是今天。
     * 规则与理由见 [quickPicksFor]。
     */
    private fun baseFor(field: DateField): SimpleDate {
        field.value?.let { return it }
        if (diffMode) {
            (if (field === endField) startField.value else endField.value)?.let { return it }
        }
        return SimpleDate.today()
    }

    /**
     * 在 [field] 那一格旁边弹出快捷日期条。
     *
     * ## 为什么做成浮层而不是常驻一行
     *
     * 页面有两个日期框。常驻的快捷条无法表达「点它填到哪一格」，用户得先猜再点；
     * 贴在**当前获得焦点的那一格**旁边，作用对象就不言自明。
     *
     * ## 四个必须这样写的细节
     *
     * 1. **`isFocusable = false`**：浮层一旦抢走焦点，输入框立刻失焦、键盘收起 ——
     *    而用户此刻正要输入，这条是硬性的。
     * 2. **必须给一个非 null 的 background**：`isOutsideTouchable` 靠它生效（官方如此），
     *    否则点浮层外面不会收起。
     * 3. **宽度要算**：中文的「今天 / 一周前」很短，撑满屏宽显得空；英文的 `1 month ago`
     *    又会长到装不下。所以先按自然宽度量一遍，放得下就紧凑排，
     *    放不下才改成等宽 + 自适应字号（胶囊样式里已开 autoSize）。
     * 4. **进出场动画自己做**：窗口自带的 `setAnimationStyle` 只能整窗缩放、轴心固定在中心，
     *    做不出「贴着输入框那条边展开」的观感，所以走视图属性动画（见 [animateQuickBarIn]）。
     *    代价是退场也要自己接管 —— 点浮层外面本来由系统直接收掉（没有动画），
     *    这里用 touchInterceptor 把 `ACTION_OUTSIDE` 截下来自己收。
     */
    private fun showQuickBar(field: DateField) {
        val anchor = field.input
        // 聚焦与点击会连着来两次，同一格重复触发时不要重建 —— 重建会让浮层闪一下。
        if (quickBar?.isShowing == true && quickField === field && !quickDismissing) return
        dismissQuickBar(animated = false)

        val bar = LayoutInflater.from(this).inflate(R.layout.popup_date_quick, null) as LinearLayout
        val barPadding = resources.getDimensionPixelSize(R.dimen.space_xs)
        val barGap = resources.getDimensionPixelSize(R.dimen.space_s)
        val maxWidth = resources.displayMetrics.widthPixels -
            2 * resources.getDimensionPixelSize(R.dimen.space_m)

        val chips = quickPicksFor(field).mapIndexed { index, pick ->
            val chip = LayoutInflater.from(this).inflate(R.layout.item_quick_chip, bar, false) as TextView
            chip.text = getString(pick.label)
            if (index > 0) {
                (chip.layoutParams as LinearLayout.LayoutParams).marginStart = barGap
            }
            chip.setOnClickListener {
                // ⚠ 原点取**按下这一下**时本格的日期，而不是浮层弹出时算好的那一份 ——
                //   这正是「连点继续增减」的落点：每点一次，原点就往前挪一格。
                field.fromPicker(pick.resolveFrom(baseFor(field)))
                // 值变了，上一轮的答案（一句陈述句）就不再对应屏幕上的输入了。
                // fromPicker 走的是「代码写入」，绕开了 DateField 里那条通用的作废路径，
                // 所以这里必须显式撤一次。
                invalidateAnswer()
                flashChip(chip)
                // 浮层**故意不收**：用户往往要连点几下，点一次就消失等于逼他反复去点输入框。
            }
            // 浮层不在 Activity 的视图树下，BaseActivity 那趟 attachTree 扫不到它，必须自己挂。
            PressFeedback.attach(chip)
            bar.addView(chip)
            chip
        }
        // 理论上去不掉这么干净（候选 4 条、最多滤掉 1 条），这里只是不让一个空浮层弹出来。
        if (chips.isEmpty()) return

        var natural = barPadding * 2 + barGap * (chips.size - 1)
        chips.forEach { chip ->
            chip.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            natural += chip.measuredWidth
        }

        val barWidth = if (natural <= maxWidth) {
            natural
        } else {
            // 装不下：改成等宽（自适应字号会各自缩到放得下），保证三语都不会顶破屏幕。
            chips.forEach { chip ->
                chip.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { weight = 1f }
            }
            maxWidth
        }
        // ⚠ 必须写回 layoutParams：窗口是按这个宽度把栏量出来的，
        //   否则栏自己的宽是 WRAP_CONTENT，里面 0dp + weight 的胶囊会塌成 0 宽。
        bar.layoutParams = LinearLayout.LayoutParams(barWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(barWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        quickW = bar.measuredWidth
        quickH = bar.measuredHeight

        val popup = PopupWindow(bar, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        popup.isFocusable = false
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        popup.elevation = resources.getDimension(R.dimen.space_s)
        // 「点浮层外面」= 用户走开了，该收。但系统收是瞬间消失、没有退场动画，
        // 所以把 ACTION_OUTSIDE 截下来自己收（返回 true 阻止系统再收一次）。
        // 只认 ACTION_OUTSIDE：浮层内部的按下/抬起照常放行，否则胶囊点不动。
        popup.setTouchInterceptor { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissQuickBar()
                true
            } else {
                false
            }
        }

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val place = placeFor(anchor, location, quickW, quickH)
        quickBelow = place.below

        // 入场初始态必须**在 show 之前**落到视图上：否则会先以完整状态闪一帧，
        // 动画看起来就是「跳了一下」而不是「长出来」。
        bar.alpha = 0f
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, place.x, place.y)

        quickBar = popup
        quickBarView = bar
        quickField = field
        quickDismissing = false
        animateQuickBarIn(bar)
    }

    /**
     * 入场：从贴着输入框的那条边展开。
     * 浮层在输入框**上方**时以底边为轴、稍微往下摆一点再收上去；
     * 翻到下方时反过来。位移只有 4dp —— 这是「长出来」不是「飞进来」，幅度大了就浮夸。
     */
    private fun animateQuickBarIn(bar: View) {
        val density = resources.displayMetrics.density
        bar.pivotY = if (quickBelow) 0f else quickH.toFloat()
        bar.scaleX = QUICK_BAR_SCALE_FROM
        bar.scaleY = QUICK_BAR_SCALE_FROM
        bar.translationY = if (quickBelow) -QUICK_BAR_SLIDE_DP * density else QUICK_BAR_SLIDE_DP * density
        bar.animate().cancel()
        bar.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(resources.getInteger(R.integer.quick_bar_enter_duration).toLong())
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * 点中一枚胶囊之后的即时反馈。
     *
     * 浮层不再收起，所以「点到了」这件事必须由胶囊自己说清楚，三层叠在一起：
     *  · 底色从淡蓝闪成实心强调色（`state_selected`），停 ~420ms 再落回 —— 比一闪而过的按压缩放显眼得多；
     *  · 同时来一次缩放脉冲（先捏小再弹回），连点同一枚也看得到；
     *  · 触感由 PressFeedback 统一给（浮层创建时已 attach）。
     */
    private fun flashChip(chip: TextView) {
        handler.removeCallbacks(quickChipFlashOff)
        flashingChip?.takeIf { it !== chip }?.isSelected = false
        flashingChip = chip

        // 连点同一枚时状态本来就是亮着的，直接再点亮等于没有变化。
        // 所以先落回常态、下一帧再点亮，保证每一次点击都有一次真正的状态翻转。
        chip.isSelected = false
        chip.jumpDrawablesToCurrentState()
        chip.post {
            if (flashingChip !== chip) return@post
            chip.isSelected = true
            chip.animate().cancel()
            chip.animate()
                .scaleX(QUICK_CHIP_PULSE_SCALE)
                .scaleY(QUICK_CHIP_PULSE_SCALE)
                .setDuration(resources.getInteger(R.integer.quick_chip_pulse_down_duration).toLong())
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    chip.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(resources.getInteger(R.integer.quick_chip_pulse_up_duration).toLong())
                        .setInterpolator(OvershootInterpolator(OVERSHOOT_TENSION))
                        .start()
                }
                .start()
        }
        handler.postDelayed(
            quickChipFlashOff,
            resources.getInteger(R.integer.quick_chip_flash_duration).toLong()
        )
    }

    /**
     * 收起浮层。
     *
     * @param animated true 走退场动画（淡出 + 缩回输入框那一侧），动画放完才真正 dismiss；
     *                 false 立刻消失 —— 用于「马上就要弹一条新的」和「页面正在离开」两种情况：
     *                 前者叠着一条还在淡出的旧浮层会糊成一片，后者根本没人看，
     *                 而且退场是靠 withEndAction 里的 dismiss 收尾的，页面先没了它会悬空。
     */
    private fun dismissQuickBar(animated: Boolean = true) {
        handler.removeCallbacks(quickChipFlashOff)
        flashingChip = null
        quickField = null

        val popup = quickBar ?: return
        val bar = quickBarView
        if (!animated || bar == null) {
            bar?.animate()?.cancel()
            quickDismissing = false
            quickBar = null
            quickBarView = null
            popup.dismiss()
            return
        }

        quickDismissing = true
        val density = resources.displayMetrics.density
        bar.pivotY = if (quickBelow) 0f else quickH.toFloat()
        bar.animate().cancel()
        bar.animate()
            .alpha(0f)
            .scaleX(QUICK_BAR_SCALE_FROM)
            .scaleY(QUICK_BAR_SCALE_FROM)
            .translationY(if (quickBelow) -QUICK_BAR_SLIDE_DP * density else QUICK_BAR_SLIDE_DP * density)
            .setDuration(resources.getInteger(R.integer.quick_bar_exit_duration).toLong())
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // 动画期间可能已经有人走非动画分支收掉它了，只收自己这一条
                if (quickBar === popup) {
                    quickBar = null
                    quickBarView = null
                    quickDismissing = false
                    popup.dismiss()
                }
            }
            .start()
    }

    /** 让浮层重新贴到输入框上（窗口被键盘压缩、列表滚动、转屏时都要走一趟）。 */
    private fun repositionQuickBar() {
        val popup = quickBar ?: return
        if (quickDismissing) return
        val anchor = quickField?.input ?: return
        if (!popup.isShowing) return
        if (!anchor.isShown) {
            dismissQuickBar(animated = false)
            return
        }
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val place = placeFor(anchor, location, quickW, quickH)
        // 键盘压缩窗口时它可能从输入框上方被挤到下方，轴心要跟着换边
        quickBelow = place.below
        popup.update(place.x, place.y, quickW, quickH)
    }

    /**
     * 浮层的落点：屏幕坐标 + 它落在输入框**上方还是下方**。
     *
     * 上/下不只是位置 —— 进出场动画要以贴着输入框的那条边为轴心（见 [animateQuickBarIn]），
     * 所以这个判断必须跟着落点一起传出来，不能在动画里另算一遍。
     */
    private data class Placement(val x: Int, val y: Int, val below: Boolean)

    /**
     * 算出浮层该落在哪：优先贴在输入框上方，头上放不下才翻到下方。
     * 横向居中对齐到输入框，并向屏幕内收边距，免得贴边或被裁掉。
     */
    private fun placeFor(anchor: View, location: IntArray, width: Int, height: Int): Placement {
        val margin = resources.getDimensionPixelSize(R.dimen.space_m)
        val gap = resources.getDimensionPixelSize(R.dimen.space_xs)
        val screenWidth = resources.displayMetrics.widthPixels

        val x = (location[0] + anchor.width / 2 - width / 2)
            .coerceIn(margin, (screenWidth - width - margin).coerceAtLeast(margin))
        val above = location[1] - gap - height
        val below = above < margin
        val y = if (below) location[1] + anchor.height + gap else above
        return Placement(x, y, below)
    }

    // ==================== 计算 ====================

    private fun calculate() {
        dismissQuickBar()
        if (diffMode) calculateDayDifference() else calculateShiftedDate()
    }

    /**
     * 「从 → 到」相差几天。
     *
     * 「从」留空时按**今天**算 —— 这是最高频的用法（从今天起算到某个日子）。
     * ⚠ 默认值只在**点计算这一下**才落进输入框：平时输入框里显示的是示例提示，
     *   不预填成日期 —— 否则用户每次都要先把那个自己并不想要的日期清掉。
     */
    private fun calculateDayDifference() {
        // 先把手输内容落定再取值：用户可能输完直接按返回键收键盘（此时并未失焦）
        startField.syncFromInput()
        endField.syncFromInput()

        var from = startField.value
        if (from == null) {
            from = SimpleDate.today()
            startField.fromPicker(from)
        }
        val to = endField.value
        if (to == null) {
            renderError(R.string.calc_need_end_date)
            return
        }
        renderDayDifference(daysBetween(from, to))
    }

    /**
     * 两个「本地日期」之间的整天数。
     * 先把两端归零到当天 00:00，再四舍五入，跨夏令时切换也不会差一天。
     * 归零用的是应用时区的零点（见 [AppClock]）。
     */
    private fun daysBetween(from: SimpleDate, to: SimpleDate): Long {
        val start = AppClock.calendar().apply {
            clear()
            set(from.year, from.month, from.day)
        }
        val end = AppClock.calendar().apply {
            clear()
            set(to.year, to.month, to.day)
        }
        return ((end.timeInMillis - start.timeInMillis) / MILLIS_PER_DAY.toDouble()).roundToLong()
    }

    /**
     * 差值答案的排法：主行是「91 天」，下面一行补「13 周 1 天」。
     *
     * ⚠⚠ 数字与单位**不能拆成两个 TextView 各自取资源** —— 英文是 `91 days`、
     * 中文是「91 天」，语序与单复数都不同，拆开就一定会出 `1 days` 这种半对半错的结果。
     * 做法是：一条整串 plurals + 一个占位符定位单位段，再把单位用 RelativeSizeSpan 缩到 18sp、
     * 数字加粗留在 28sp（见 [sizedQuantity]）。语序与单复数全交给资源管。
     */
    private fun renderDayDifference(days: Long) {
        val magnitude = abs(days).toInt()
        val answer = sizedQuantity(R.plurals.calc_days, magnitude, answerPx)
        val aux = when {
            // 结束早于开始：先把这件事说清楚，比给「13 周 1 天」更要紧
            days < 0 -> getString(R.string.calc_aux_end_before)
            magnitude == 0 -> getString(R.string.calc_aux_same_day)
            // 不到一周时大字已经说完了，再补一行「3 天」只是重复
            magnitude < 7 -> null
            magnitude % 7 == 0 -> resources.getQuantityString(R.plurals.calc_weeks, magnitude / 7, magnitude / 7)
            else -> getString(
                R.string.calc_aux_weeks_days,
                resources.getQuantityString(R.plurals.calc_weeks, magnitude / 7, magnitude / 7),
                resources.getQuantityString(R.plurals.calc_days, magnitude % 7, magnitude % 7)
            )
        }
        renderAnswer(answer, aux, answerPx)
    }

    /**
     * 「从某个日期往前 / 往后 N 天」推算出哪一天。
     *
     * 「从（基准日期）」留空时同样按今天算，与差值模式保持一致。
     */
    private fun calculateShiftedDate() {
        startField.syncFromInput()

        var base = startField.value
        if (base == null) {
            base = SimpleDate.today()
            startField.fromPicker(base)
        }

        val daysText = etDays.text.toString().trim()
        if (daysText.isEmpty()) {
            renderError(R.string.calc_need_days)
            return
        }
        val days = daysText.toIntOrNull()
        if (days == null) {
            renderError(R.string.calc_need_days_valid)
            return
        }

        val calendar = AppClock.calendar().apply { set(base.year, base.month, base.day) }
        calendar.add(Calendar.DAY_OF_MONTH, if (addMode) days else -days)

        val result = SimpleDate(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        // 答案是一整串日期，用 22sp 而不是 28sp：中文「2026年10月30日」12 个全角字符，
        // 卡片内宽 296dp 下 28sp 会折行，22sp 正好一行放得下。
        // 星期几交给系统按语言给出：简中「星期三」/ 英文「Wednesday」。
        val weekDay = calendar
            .getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, locale)
            .orEmpty()
        renderAnswer(bold(result.display(locale)), weekDay, answerDatePx)
    }

    /**
     * 把「数字 + 单位」排成两种字号的一段文字：数字用 [numberPx] 并加粗，单位缩到 18sp。
     *
     * 实现上先用 `getQuantityText`（**不做格式化**）拿到带 `%1$d` 的原文，
     * 再按占位符的位置切段 —— 这样无论单位排在数字前面还是后面（有的语言如此）都能正确识别。
     *
     * ⚠ 不能用 `getQuantityString(..., 哨兵)` 去定位：`%1$d` 会拿哨兵去当整数格式化，
     *   `String.format` 直接抛 IllegalFormatConversionException。
     */
    private fun sizedQuantity(@PluralsRes pluralsRes: Int, quantity: Int, numberPx: Float): CharSequence {
        val template = resources.getQuantityText(pluralsRes, quantity).toString()
        val start = template.indexOf('%')
        if (start < 0) return template // 资源里没有占位符（例如单位为空的语种），原样返回
        var end = start + 1
        while (end < template.length && !template[end].isLetter()) end++
        if (end < template.length) end++ // 连类型字符一起吃掉

        val number = quantity.toString()
        val text = SpannableString(template.substring(0, start) + number + template.substring(end))
        val unitScale = resources.getDimension(R.dimen.text_answer_unit) / numberPx
        val numberEnd = start + number.length

        if (start > 0) {
            text.setSpan(RelativeSizeSpan(unitScale), 0, start, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        text.setSpan(StyleSpan(Typeface.BOLD), start, numberEnd, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (numberEnd < text.length) {
            text.setSpan(RelativeSizeSpan(unitScale), numberEnd, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        return text
    }

    private fun bold(text: String): CharSequence =
        SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }

    // ==================== 答案区三种状态 ====================

    private fun renderAnswer(main: CharSequence, aux: CharSequence?, sizePx: Float) {
        answerShowing = true
        tvResult.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        tvResult.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        tvResult.text = main
        tvResultAux.isVisible = !aux.isNullOrEmpty()
        tvResultAux.text = aux
    }

    /**
     * 出错时答案区退回普通字号 + 警示色：错误信息不是「答案」，
     * 用 28sp 加粗喊出来，等于把「没填完」这件事的视觉权重抬得和结果一样高。
     * 顺带发一次「失败」语义的触感 —— 官方对那个常量的描述就是
     * 「rejection or failure of a user interaction」，用在这里名正言顺。
     */
    private fun renderError(@StringRes messageRes: Int) {
        answerShowing = true
        tvResult.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
        tvResult.setTextColor(ContextCompat.getColor(this, R.color.warning))
        tvResult.setText(messageRes)
        tvResultAux.isVisible = false
        Haptics.tap(tvResult, FeedbackKind.reject())
    }

    private fun renderIdle() {
        answerShowing = false
        tvResult.setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx)
        tvResult.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        tvResult.setText(R.string.calc_idle_hint)
        tvResultAux.isVisible = false
    }

    /**
     * 输入一变，上一次的答案就作废。
     *
     * 这一页的答案是一句**陈述句**（「30 天」/「2026年10月30日」），不是一个自带「已过期」
     * 标记的字段 —— 改完日期却把上一轮的结论留在屏幕上，用户扫一眼就会把它当成本次的结论。
     * 所以任何一次用户编辑都直接退回空态；下次点计算再出新的。
     *
     * 只在真的有答案要撤时才动手：用户连打 8 个数字会走 8 次 [TextWatcher]，
     * 每次都重排一遍答案区纯属白费。
     */
    private fun invalidateAnswer() {
        if (answerShowing) renderIdle()
    }

    private companion object {
        private const val MILLIS_PER_DAY = 86_400_000L

        /** 连续数字的一段，用来单独检查年份写了几位（见 [isFullYear]）。 */
        private val DIGIT_GROUP = Regex("\\d+")

        /**
         * 快捷日期条出场时的缩放起点。
         *
         * ⚠⚠ 这是**比例**，只能写在代码里。比例绝不能进 `dimens.xml`：
         *   写成无单位的 `<dimen>0.92</dimen>` aapt2 不会报错，运行期 `getDimension()`
         *   直接抛 NotFoundException —— 表现就是**一启动就崩**。
         */
        private const val QUICK_BAR_SCALE_FROM = 0.92f

        /** 出场时朝输入框那一侧的位移（dp）。只做「长出来」的暗示，不做「飞进来」。 */
        private const val QUICK_BAR_SLIDE_DP = 4f

        /** 胶囊被点中时的缩放脉冲起点。 */
        private const val QUICK_CHIP_PULSE_SCALE = 0.90f

        /** 缩放回弹的过冲量：略过 1 再落回，手感更「弹」一点。 */
        private const val OVERSHOOT_TENSION = 2.2f
    }
}
