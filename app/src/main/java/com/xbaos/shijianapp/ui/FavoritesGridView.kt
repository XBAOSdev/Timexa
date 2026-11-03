package com.xbaos.shijianapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.xbaos.shijianapp.R
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 首页「常用应用」的网格容器（**本项目第一个自绘 ViewGroup**）。
 *
 * 为什么不用 RecyclerView / GridLayout：
 *   · 项目刻意不加任何依赖，RecyclerView 要引 androidx.recyclerview；
 *   · GridLayout 做不了「一行居中 + 等间距 + 列数随宽度变 + 分页 + 长按拖拽」这套组合；
 *   · 自绘 `onLayout` 反而把这几件事一次说清。
 *
 * ## 几何
 * ```
 * available  = 宽度 − 左右 padding
 * columns    = clamp(available ÷ minCellWidth, 2, maxColumns)   ← 「一行几个」的唯一来源
 * cellWidth  = available ÷ columns                              ← 每列等宽 = 天然等间距
 * leftOf(col)= paddingLeft + (available − cellWidth × countInRow(row)) ÷ 2 + col × cellWidth
 * ```
 * ⚠ 行内居中的被减数是**这一行实际有几个格子**，不是 `columns`。
 *   按 `columns` 算时满行恒等于 available、居中线恒为 0，看不出问题；
 *   但最后一行不满时会整行贴左 —— 那正是首轮实测踩到的 bug。
 *
 * ## 高度与分页
 * ```
 * totalRows   = ceil(cellCount ÷ columns)
 * visibleRows = min(totalRows, MAX_VISIBLE_ROWS=2)
 * 自身高度     = 上下 padding + visibleRows × cellHeight
 * pageCount   = ceil(totalRows ÷ 2)
 * ```
 * 超过两行时高度**锁死在两行**，用滚动整数倍 `2 × cellHeight` 翻页 ——
 * 因此上一页的末行底边刚好落在 paddingTop 上，不会被切出半截。
 *
 * ## 三条交互
 * 1. **点击**：非编辑态直接进应用；编辑态看到的是右上角减号（整格点击被忽略）。
 * 2. **翻页**：超过一页时竖向拖拽跟手滚动、松手吸附；甩动则整页翻转。
 * 3. **拖拽排序**：编辑态长按拎起（放大 + 提高 Z），挪到别的格位上让位、松手落位。
 *
 * ⚠ 本视图的父容器是**不滚动**的 LinearLayout（首页本身不滚），
 *   所以竖向拖拽不涉及嵌套滚动协商，网格可以直接消费。
 */
class FavoritesGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    /** 交互回调。移除与「添加」只在编辑态（或空态）触发。 */
    interface Listener {
        fun onAppClick(entry: AppEntry)
        fun onAppRemove(entry: AppEntry)
        fun onAddClick()

        /** 页数或当前页变化时回调，供页面画指示点。 */
        fun onPagingChanged(pageCount: Int, page: Int)

        /**
         * 非编辑态长按某个应用图标/卡片 —— 直接请求进入编辑模式
         * （用户 2026-09-26 拍板：免去找「编辑」小字的那一步）。
         * 网格只负责触发，进入与否由 MainActivity 的状态机决定。
         */
        fun onEditRequested()

        /**
         * 拖拽排序结束。
         * ⚠ 网格**已经**把内部顺序改成新顺序并重排好了，这里只需要把 [ids] 持久化，
         *   不要再回调进来触发一次重建 —— 那会把刚落位的格子再挪一次。
         */
        fun onOrderChanged(ids: List<String>)
    }

    var listener: Listener? = null

    /** 编辑态：显示右上角移除角标与末尾的「＋」空位。由 [render] 一并设置。 */
    var editing: Boolean = false
        private set

    private var entries: List<AppEntry> = emptyList()

    // ==================== 视图与数据 ====================

    /** 参与布局的格子，**顺序即视觉顺序**。拖拽排序改的就是它。 */
    private val cells = ArrayList<View>(MAX_VISIBLE_ROWS * 8)

    /** 与 [cells] 一一对应的 id（「＋」空位是 [ADD_SLOT]）。 */
    private val cellIds = ArrayList<String>(MAX_VISIBLE_ROWS * 8)

    /** 应用 id → 已装配好的格子视图，用于「复用而不是重建」。 */
    private val cellById = HashMap<String, View>()

    /** 「＋」空位。它是一个独立于应用目录的固定视图，单独持有。 */
    private var addSlot: View? = null

    /** 每次 [render] 递增。用于作废上一轮还没跑完的「淡出后重排」延迟任务。 */
    private var renderGeneration = 0

    // ==================== 尺寸与几何 ====================

    private val cellHeight = resources.getDimensionPixelSize(R.dimen.favorite_cell_height)
    private val minCellWidth = resources.getDimensionPixelSize(R.dimen.favorite_cell_min_width)
    private val maxColumns = resources.getInteger(R.integer.favorite_max_columns)

    private val pageAnimDuration =
        resources.getInteger(R.integer.favorite_page_anim_duration).toLong()
    private val reorderAnimDuration =
        resources.getInteger(R.integer.favorite_reorder_anim_duration).toLong()
    private val dropAnimDuration =
        resources.getInteger(R.integer.favorite_drop_anim_duration).toLong()
    private val enterAnimDuration =
        resources.getInteger(R.integer.favorite_enter_anim_duration).toLong()
    private val exitAnimDuration =
        resources.getInteger(R.integer.favorite_exit_anim_duration).toLong()
    private val moveAnimDuration =
        resources.getInteger(R.integer.favorite_move_anim_duration).toLong()
    private val staggerStep = resources.getInteger(R.integer.favorite_stagger_step).toLong()

    private val dragScale = resources.getFraction(R.fraction.favorite_drag_scale, 1, 1)
    private val enterScale = resources.getFraction(R.fraction.favorite_enter_scale, 1, 1)
    private val exitScale = resources.getFraction(R.fraction.favorite_exit_scale, 1, 1)
    private val dragElevation = resources.getDimensionPixelSize(R.dimen.favorite_drag_elevation)

    private var columns = DEFAULT_COLUMNS
    private var cellWidth = 0
    private var totalRows = 0
    private var visibleRows = 0

    private var page = 0
    private var pageCount = 1
    private var pageAnimator: ValueAnimator? = null

    // ==================== 位置过渡（增删/排序后让旧格子滑过去） ====================

    /** 布局完成后要从「旧位置」位移过来的视图。 */
    private val pendingMove = HashMap<View, PointF>()

    /** 布局完成后要从「缩小 + 透明」长出来的新视图。 */
    private val pendingEnter = ArrayList<View>()

    // ==================== 手势 ====================

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity =
        ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()

    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f

    /** 本次手势已判定为「网格自己翻页」，后续事件不再交给子视图。 */
    private var pagingGesture = false

    private var velocityTracker: VelocityTracker? = null

    // ==================== 拖拽排序 ====================

    private var dragView: View? = null
    private var dragIndex = -1

    /** 是否已经把被拎起的格子放大过了（延迟到真正接管手势时再放大，避开按压回弹动画）。 */
    private var dragLifted = false

    /** 拖拽开始时各格子的布局矩形 / 已有位移。让位动画都相对这份基准算，避免叠加出偏差。 */
    private val dragBaseRect = HashMap<View, Rect>()
    private val dragBaseShift = HashMap<View, PointF>()

    /** 拖拽时的自动翻页锁：只在跨过边缘那一瞬触发一次，避免每帧都翻。 */
    private var dragEdgeLatch = false

    // ==================== 对外 ====================

    /**
     * 重绘整张网格。
     *
     * 与「每次推倒重建」不同，这里按 id 做差分：
     *   · 已有格子**复用视图**，只重新绑定（编辑态角标、文案可能变了）；
     *   · 被删掉的格子先缩小淡出，淡完再让后面的格子滑过来（串行，不糊成一团）；
     *   · 新增的格子从 88% 长出来；
     *   · 位置变了的格子从旧位置滑到新位置。
     *
     * @param animate 是否播动画。**只在「这一屏第一次装配」和「用户主动增删」时为 true**；
     *                从别的页面返回时传 false —— 那不是新内容，晃一下反而像闪屏。
     */
    fun render(entries: List<AppEntry>, editing: Boolean, animate: Boolean) {
        this.entries = entries
        this.editing = editing
        renderGeneration++

        // 「＋」空位是固定槽位，不跟 entries 走 —— 否则清空图标后退出编辑态，
        // 空位会在这里被判成「该退场」：先播退场动画（缩 0、透明），随后 sync 复用
        // 同一个 View 重新加入，而入场动画只播给新建的格子 —— 它就永远停在
        // alpha 0，「添加应用」凭空消失，重进应用（全新 inflate）才复位。
        val removedIds = if (animate) {
            cellById.keys.filter { id -> id != ADD_SLOT && entries.none { it.id == id } }
        } else {
            emptyList()
        }

        if (removedIds.isEmpty()) {
            sync(animate)
            return
        }

        // 先让要消失的格子淡出，淡完再重排 —— 两段串行，位移不会和缩放撞在一起
        removedIds.mapNotNull { cellById[it] }.forEach { cell ->
            cell.animate().cancel()
            cell.animate()
                .scaleX(exitScale).scaleY(exitScale).alpha(0f)
                .setDuration(exitAnimDuration)
                .start()
        }
        // ⚠ 这期间 cells 保持不动（含已被判死的那些），所以尺寸与格位都不变，
        //   被删的格子才能「原地缩小」而不是边缩边被挤走。
        val generation = renderGeneration
        postDelayed({
            if (generation == renderGeneration) sync(animate = true)
        }, exitAnimDuration)
    }

    val currentPage: Int get() = page

    /** 开关「添加应用」空位的显示。
     *
     * 2026-09-26 改版：编辑态**恒显示** —— 「添加应用」表头按钮已删，
     * 这个「＋」格子是唯一添加入口，且按用户拍板「图标按钮也占用一个位置，
     * 退出编辑后按钮隐藏」。非编辑态只在**一个应用都没有**时显示：
     * 空卡上的「＋」是最直白的邀请，有应用时不打扰。
     * ⚠ 应用一多（超过两行）空位会被挤到第二页 —— 表头按钮时代这是删掉它的理由，
     *   现在用户明确接受这个代价（拍板原文「图标按钮也占用一个位置」）。
     */
    private val showAddSlot: Boolean get() = entries.isEmpty() || editing

    // ==================== 差分重绘 ====================

    private fun sync(animate: Boolean) {
        val targetIds = ArrayList<String>(entries.size + 1)
        entries.forEach { targetIds += it.id }
        if (showAddSlot) targetIds += ADD_SLOT

        // 位置过渡的起点必须现在取 —— requestLayout() 之后 left/top 就变了
        val oldPosition = HashMap<View, PointF>()
        if (animate) {
            cells.forEach { oldPosition[it] = PointF(it.left.toFloat(), it.top.toFloat()) }
        }

        val newCells = ArrayList<View>(targetIds.size)
        val newIds = ArrayList<String>(targetIds.size)
        val newById = HashMap<String, View>()
        val entering = ArrayList<View>()
        var newAddSlot: View? = null

        for (id in targetIds) {
            if (id == ADD_SLOT) {
                var slot = addSlot
                if (slot == null) {
                    slot = inflateCell()
                    addView(slot)
                    entering += slot
                }
                bindAddCell(slot)
                newCells += slot
                newIds += id
                newById[id] = slot
                newAddSlot = slot
                continue
            }

            // 应用目录里已经没有的功能直接跳过 —— 「删功能即自动从常用应用消失」就是这一行
            val entry = AppCatalog.byId(id) ?: continue

            var cell = cellById[id]
            if (cell == null) {
                cell = inflateCell()
                addView(cell)
                entering += cell
            }
            bindAppCell(cell, entry)
            newCells += cell
            newIds += id
            newById[id] = cell
        }

        // 摘掉不再需要的视图。先收集再移除：removeView 会让后面的下标前移
        val keep = HashSet<View>(newCells)
        val dropping = ArrayList<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child !in keep) dropping += child
        }
        dropping.forEach { removeView(it) }

        cells.clear()
        cells.addAll(newCells)
        cellIds.clear()
        cellIds.addAll(newIds)
        cellById.clear()
        cellById.putAll(newById)
        addSlot = newAddSlot

        if (animate) {
            oldPosition.forEach { (view, from) -> if (view in keep) pendingMove[view] = from }
            entering.forEach { view ->
                view.animate().cancel()
                view.alpha = 0f
                view.scaleX = enterScale
                view.scaleY = enterScale
                pendingEnter += view
            }
        }

        requestLayout()
        invalidate()
    }

    private fun inflateCell(): View =
        LayoutInflater.from(context).inflate(R.layout.item_favorite_cell, this, false)

    private fun bindAppCell(cell: View, entry: AppEntry) {
        cell.findViewById<ImageView>(R.id.fav_icon).setImageResource(entry.iconRes)
        cell.findViewById<TextView>(R.id.fav_label).setText(entry.nameRes)

        val badge = cell.findViewById<ImageView>(R.id.fav_badge)
        badge.isVisible = editing
        if (editing) {
            // 角标自带动作，先于整格；contentDescription 由布局给出，这里补上功能名便于读屏
            badge.contentDescription =
                context.getString(R.string.favorite_remove_named, context.getString(entry.nameRes))
            badge.setOnClickListener { listener?.onAppRemove(entry) }
        } else {
            badge.setOnClickListener(null)
        }

        cell.contentDescription = context.getString(entry.nameRes)
        cell.setOnClickListener { listener?.onAppClick(entry) }

        // 长按分流：编辑态拎起拖拽；非编辑态直接请求进入编辑模式（触觉与拎起同款）。
        // 非编辑态**必须**有 listener，否则长按什么都不发生；但「打开应用」的点击
        // 仍由 onClick 负责 —— 长按超时会抑制 onClick，不会误开应用。
        cell.setOnLongClickListener(if (editing) {
            View.OnLongClickListener { view -> startDrag(view) }
        } else {
            View.OnLongClickListener { view ->
                Haptics.tap(view, FeedbackKind.HEAVY)
                listener?.onEditRequested()
                true
            }
        })

        // ⚠ 必须在 setOnClickListener 之后 —— PressFeedback 要看到 View 已经是 clickable
        PressFeedback.attach(cell)
    }

    private fun bindAddCell(cell: View) {
        cell.findViewById<ImageView>(R.id.fav_icon).apply {
            setBackgroundResource(R.drawable.bg_favorite_add)
            setImageResource(R.drawable.ic_add)
            imageTintList = ContextCompat.getColorStateList(context, R.color.text_tertiary)
        }
        cell.findViewById<TextView>(R.id.fav_label).apply {
            setText(R.string.favorite_add)
            setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        }
        cell.findViewById<ImageView>(R.id.fav_badge).isVisible = false
        cell.contentDescription = context.getString(R.string.favorite_add)
        cell.setOnClickListener { listener?.onAddClick() }
        cell.setOnLongClickListener(null)
        PressFeedback.attach(cell)
    }

    // ==================== 测量与布局 ====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val available = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(1)

        // 「一行几个」只由这一行决定
        columns = (available / minCellWidth).coerceIn(MIN_COLUMNS, maxColumns)
        cellWidth = available / columns

        val count = cells.size
        totalRows = if (count == 0) 0 else ceil(count.toDouble() / columns).toInt()
        visibleRows = min(totalRows, MAX_VISIBLE_ROWS)

        val newPageCount =
            if (totalRows == 0) 1 else ceil(totalRows.toDouble() / MAX_VISIBLE_ROWS).toInt()
        var pagingChanged = false
        if (newPageCount != pageCount) {
            pageCount = newPageCount
            pagingChanged = true
        }
        if (page > pageCount - 1) {
            page = pageCount - 1
            pagingChanged = true
        }
        if (pagingChanged) {
            // 布局还没跑完，滚动位置下一帧再设，避免用旧的 content 高度
            post { applyPage(animate = false); listener?.onPagingChanged(pageCount, page) }
        }

        val height = paddingTop + paddingBottom + visibleRows * cellHeight
        setMeasuredDimension(measuredWidth, resolveSize(height, heightMeasureSpec))

        // 子视图统一按「算好的格宽 × 固定格高」精确测量
        val childW = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY)
        val childH = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(childW, childH)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // ⚠ 按 cells 布局，不是按 child 下标 —— 迭代期间可能还有正在淡出的旧视图挂在树上，
        //   它们已不在 cells 里，因此会停在原地（正是我们想要的：原地缩放消失）。
        for (i in cells.indices) {
            val view = cells[i]
            if (view.parent !== this) continue
            val rect = cellRect(i)
            view.layout(rect.left, rect.top, rect.right, rect.bottom)
        }

        runPendingAnimations()
    }

    /**
     * 把「位置变化 / 新入场」的动画补上。
     *
     * 为什么放在 `onLayout` 末尾而不是 `sync` 里：
     *  位移量 = 旧位置 − 新位置，而新位置要等这一趟布局跑完才知道。
     *  这之前 `view.left` 还是旧值，算出来会是 0（「没动」）。
     */
    private fun runPendingAnimations() {
        if (pendingMove.isEmpty() && pendingEnter.isEmpty()) return

        val moving = ArrayList<View>(pendingMove.size)
        pendingMove.forEach { (view, from) ->
            view.animate().cancel()
            view.translationX = from.x - view.left
            view.translationY = from.y - view.top
            moving += view
        }
        val entering = ArrayList(pendingEnter)
        pendingMove.clear()
        pendingEnter.clear()

        moving.forEach {
            it.animate().translationX(0f).translationY(0f)
                .setDuration(moveAnimDuration)
                .start()
        }
        entering.forEachIndexed { index, view ->
            view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(index.coerceAtMost(STAGGER_MAX_INDEX) * staggerStep)
                .setDuration(enterAnimDuration)
                .start()
        }
    }

    /** 第 [index] 个格子应该在的矩形（未滚动坐标系，与 `view.left/top` 同一套基准）。 */
    private fun cellRect(index: Int): Rect {
        val row = index / columns
        val col = index % columns
        val available = width - paddingLeft - paddingRight
        val left = rowStartX(row, available) + col * cellWidth
        val top = paddingTop + row * cellHeight
        return Rect(left, top, left + cellWidth, top + cellHeight)
    }

    /** 第 [row] 行实际有几个格子。只有最后一行可能不满。 */
    private fun countInRow(row: Int): Int {
        if (cells.isEmpty()) return 0
        return (cells.size - row * columns).coerceIn(0, columns)
    }

    /** 第 [row] 行的起始 x：本行实到格子的总宽在可用宽度里居中。 */
    private fun rowStartX(row: Int, available: Int): Int {
        val used = cellWidth * countInRow(row)
        return paddingLeft + (available - used) / 2
    }

    // ==================== 分页 ====================

    /** 每「页」的高度 = 可见行数 × 格高。翻页就是滚整数倍的这个值。 */
    private val pageHeight: Int get() = MAX_VISIBLE_ROWS * cellHeight

    /** 当前滚动量允许的上下限。 */
    private val maxScroll: Int get() = (pageCount - 1) * pageHeight

    fun goToPage(target: Int, animate: Boolean) {
        val clamped = target.coerceIn(0, pageCount - 1)
        val changed = clamped != page
        page = clamped
        applyPage(animate)
        if (changed) listener?.onPagingChanged(pageCount, page)
    }

    private fun applyPage(animate: Boolean) {
        val target = page * pageHeight
        pageAnimator?.cancel()
        if (!animate) {
            scrollTo(0, target)
            return
        }
        val start = scrollY
        if (start == target) return
        pageAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = pageAnimDuration
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }

    // ==================== 触摸：翻页 + 拖拽排序 ====================

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(ev)

            MotionEvent.ACTION_MOVE -> {
                // 已经在拎着格子 —— 立刻接管，把手势从子视图手里拿过来
                if (dragView != null) return true
                if (pagingGesture) return true
                if (tryStartPaging(ev)) return true
            }
        }
        return false
    }

    /** 记下本次手势的起点。两条入口（拦截 / 自行处理）都要调，原因见 [tryStartPaging]。 */
    private fun beginGesture(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        lastY = ev.y
        pagingGesture = false
    }

    /**
     * 判断这一下该不该由网格接手翻页；接手了就返回 true。
     *
     * ⚠ 它必须能从**两处**被调用（[onInterceptTouchEvent] 与 [onTouchEvent]），
     *   这不是冗余，而是修掉了一个真 bug：
     *     · 手指按在**图标格子**上 → 子视图消费掉 DOWN，`mFirstTouchTarget != null`，
     *       之后的 MOVE 会正常走 `onInterceptTouchEvent`；
     *     · 手指按在**空白处**（最典型的是第二页只有一个图标、旁边全是空的）→
     *       没有子视图要这个 DOWN，ViewGroup 只好自己接管，`mFirstTouchTarget == null`；
     *       而 ViewGroup 在 `mFirstTouchTarget == null` 时**直接置 intercepted = true 并跳过
     *       拦截回调** —— 也就是说 `onInterceptTouchEvent` 再也不会被调用。
     *   只把判定写在拦截里的话，空白处怎么滑都不翻页；偏偏「第二页只有一格」是最常见的形态，
     *   用户会以为翻页坏了。
     */
    private fun tryStartPaging(ev: MotionEvent): Boolean {
        val dy = ev.y - downY
        val dx = ev.x - downX
        if (abs(dy) <= touchSlop || abs(dy) <= abs(dx) || !canPageBy(dy)) return false

        pagingGesture = true
        lastY = ev.y
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain()
        // 补上触发点之前的事件，否则算出来的初速度是从半路开始量的
        obtainDownEvent(ev)?.let { velocityTracker?.addMovement(it) }
        velocityTracker?.addMovement(ev)
        return true
    }

    /**
     * 拖拽时给 `VelocityTracker` 补一个 DOWN。
     * ⚠ 子视图消费了 DOWN，所以拦截时我们手里没有那个事件 —— 只好就地造一个
     *   （时间戳与坐标取触发拦截的那一帧，对「甩动方向」的判断已经完全够用）。
     */
    private fun obtainDownEvent(ev: MotionEvent): MotionEvent? {
        val down = MotionEvent.obtain(ev)
        down.setAction(MotionEvent.ACTION_DOWN)
        return down
    }

    /** 这一拖的方向是否还有内容可看。没有了就**不拦截**，让父容器/系统去处理。 */
    private fun canPageBy(dy: Float): Boolean {
        if (pageCount <= 1) return false
        return if (dy < 0) scrollY < maxScroll else scrollY > 0
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(ev)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragView != null) {
                    updateDrag(ev.x, ev.y)
                    return true
                }
                // 空白处按下时拦截回调不会再来了（原因见 tryStartPaging），翻页判定在这里补一次
                if (!pagingGesture && tryStartPaging(ev)) return true
                if (pagingGesture) {
                    val delta = (lastY - ev.y).roundToInt()
                    scrollTo(scrollX, (scrollY + delta).coerceIn(0, maxScroll))
                    lastY = ev.y
                    velocityTracker?.addMovement(ev)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragView != null) {
                    dropDrag()
                    return true
                }
                if (pagingGesture) {
                    settlePaging()
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragView != null) {
                    dropDrag()
                    return true
                }
                if (pagingGesture) {
                    // 中途被别的窗口抢走（来电、下拉通知）—— 按当前滚动量就近吸附，不甩
                    goToPage((scrollY.toFloat() / pageHeight).roundToInt(), animate = true)
                    return true
                }
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 松手：先看甩动速度，够快就整页翻转，否则就近吸附。 */
    private fun settlePaging() {
        val velocity = velocityTracker?.let {
            it.computeCurrentVelocity(VELOCITY_UNITS)
            it.yVelocity
        } ?: 0f
        val nearest = (scrollY.toFloat() / pageHeight).roundToInt()
        val target = when {
            // 手指向上甩（速度为负）→ 看下一页
            velocity < -minFlingVelocity -> nearest + 1
            velocity > minFlingVelocity -> nearest - 1
            else -> nearest
        }
        velocityTracker?.recycle()
        velocityTracker = null
        goToPage(target, animate = true)
    }

    // ==================== 拖拽排序 ====================

    private fun startDrag(cell: View): Boolean {
        if (!editing) return false
        val index = cells.indexOf(cell)
        // 只有「应用格子」可以拎起来；「＋」空位是固定槽位，不参与排序
        if (index < 0 || index >= entries.size) return false

        dragView = cell
        dragIndex = index
        dragLifted = false
        dragEdgeLatch = false

        dragBaseRect.clear()
        dragBaseShift.clear()
        cells.forEach {
            dragBaseRect[it] = Rect(it.left, it.top, it.right, it.bottom)
            // 拖拽可能紧跟在一次让位动画之后开始，把当时的位移一并记下来，
            // 后面所有位移都在这个基准上叠加 —— 直接置 0 会让它「弹」一下再动
            dragBaseShift[it] = PointF(it.translationX, it.translationY)
        }

        // 唯一目的是把这一格提到最上层（Z 决定兄弟节点的绘制顺序）
        cell.elevation = dragElevation.toFloat()
        Haptics.tap(cell, FeedbackKind.HEAVY)
        return true
    }

    private fun updateDrag(x: Float, y: Float) {
        val cell = dragView ?: return

        // 放大故意推迟到这一刻：长按触发时子视图还按着（PressFeedback 缩到 0.95），
        // 马上放大立刻会被它松手时的回弹动画顶掉。等我们真正接管手势（子视图收到 CANCEL）之后再放大，
        // 就是最后写入者，不会被覆盖。
        if (!dragLifted) {
            dragLifted = true
            cell.animate().scaleX(dragScale).scaleY(dragScale)
                .setDuration(reorderAnimDuration).start()
        }

        cell.translationX = x - downX
        cell.translationY = y - downY

        autoPageWhileDragging(y)

        val target = indexAt(x, y)
        if (target >= 0 && target != dragIndex && target < entries.size) {
            reorderCells(dragIndex, target)
            dragIndex = target
            animateOthersToSlots()
        }
    }

    /** 把 [from] 位置的格子挪到 [to]，`cells` 与 `cellIds` 必须一起动。 */
    private fun reorderCells(from: Int, to: Int) {
        if (from == to) return
        val view = cells.removeAt(from)
        val id = cellIds.removeAt(from)
        cells.add(to, view)
        cellIds.add(to, id)
    }

    /** 让除被拎起那一格之外的所有格子滑到各自的新格位。 */
    private fun animateOthersToSlots() {
        val held = dragView
        cells.forEachIndexed { index, cell ->
            if (cell === held) return@forEachIndexed
            val base = dragBaseRect[cell] ?: return@forEachIndexed
            val shift = dragBaseShift[cell]
            val slot = cellRect(index)
            cell.animate()
                .translationX((shift?.x ?: 0f) + (slot.left - base.left))
                .translationY((shift?.y ?: 0f) + (slot.top - base.top))
                .setDuration(reorderAnimDuration)
                .start()
        }
    }

    /**
     * 拎到网格上下边缘时自动翻页，好把格子放到另一页。
     *
     * ⚠ 翻页后必须把 [downY] 补偿掉一个页高，否则整页内容平移了、手指没动，
     *   被拎起的格子会**突然跳开**一大截。
     */
    private fun autoPageWhileDragging(y: Float) {
        if (pageCount <= 1) return
        val zone = cellHeight / 2
        val wantUp = y < paddingTop + zone
        val wantDown = y > height - paddingBottom - zone
        if (!wantUp && !wantDown) {
            dragEdgeLatch = false
            return
        }
        if (dragEdgeLatch) return

        val target = if (wantUp) page - 1 else page + 1
        if (target !in 0 until pageCount) return

        dragEdgeLatch = true
        val deltaScroll = target * pageHeight - scrollY
        goToPage(target, animate = true)
        downY -= deltaScroll
        lastY -= deltaScroll
    }

    /** 松手：先滑到最终格位，落位后再把顺序固化下来。 */
    private fun dropDrag() {
        val cell = dragView ?: return
        val base = dragBaseRect[cell]
        val shift = dragBaseShift[cell]
        val slot = cellRect(dragIndex)
        val restX = (shift?.x ?: 0f) + (slot.left - (base?.left ?: slot.left))
        val restY = (shift?.y ?: 0f) + (slot.top - (base?.top ?: slot.top))

        cell.animate().cancel()
        cell.animate()
            .translationX(restX)
            .translationY(restY)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(dropAnimDuration)
            .withEndAction { commitDrag() }
            .start()
    }

    /**
     * 固化顺序。
     *
     * 关键一步：**先把所有位移清零、再 requestLayout()**。
     * 此时 `cells` 已是新顺序，布局会直接把每个格子放到它该在的格位 ——
     * 与刚才动画的终点完全重合，所以肉眼看不到任何跳动。
     */
    private fun commitDrag() {
        val orderedIds = cellIds.filter { it != ADD_SLOT }
        dragView = null
        dragIndex = -1
        dragLifted = false
        dragBaseRect.clear()
        dragBaseShift.clear()

        cells.forEach {
            it.animate().cancel()
            it.translationX = 0f
            it.translationY = 0f
            it.scaleX = 1f
            it.scaleY = 1f
            it.elevation = 0f
        }
        requestLayout()
        invalidate()

        listener?.onOrderChanged(orderedIds)
    }

    // ==================== 命中测试 ====================

    /** 给定网格内坐标，返回命中的格子索引；没命中返回 -1。 */
    fun indexAt(x: Float, y: Float): Int {
        val available = width - paddingLeft - paddingRight
        val contentY = (y - paddingTop + scrollY).toInt()
        val row = contentY / cellHeight
        if (row < 0) return -1
        // 每一行的起点都不同（行内居中），所以必须先定位行、再算列
        val rowStart = rowStartX(row, available)
        if (x < rowStart) return -1
        val col = ((x - rowStart) / cellWidth).toInt()
        if (col < 0 || col >= countInRow(row)) return -1
        val index = row * columns + col
        return if (index in cells.indices) index else -1
    }

    override fun onDetachedFromWindow() {
        pageAnimator?.cancel()
        pageAnimator = null
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 可见行数上限：1 行或 2 行，超过就翻页。 */
        const val MAX_VISIBLE_ROWS = 2

        /** 再窄也要保证一行至少 2 个，否则一个格子会宽得离谱。 */
        const val MIN_COLUMNS = 2

        /** 布局还没跑过时的占位列数（onMeasure 会立刻覆盖）。 */
        const val DEFAULT_COLUMNS = 4

        /** 「＋」空位在 [cellIds] 里的占位 id。空串永远不会是一个应用 id。 */
        const val ADD_SLOT = ""

        /** 依次入场的错开上限，避免一次加很多时最后几个等太久。 */
        const val STAGGER_MAX_INDEX = 8

        /** 速度计算用的时间基准（像素/秒）。 */
        const val VELOCITY_UNITS = 1000
    }
}
