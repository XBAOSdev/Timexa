package com.xbaos.shijianapp.appyy

/**
 * 单表专注页与秒表列表页之间传递「某个秒表」的通道。
 *
 * 为什么不用 Intent 的 extra 传整个记录：
 *  专注页需要持续改动这个秒表（暂停、继续、计次、重置），而 Intent 传过去的是**副本**，
 *  改完还得想办法同步回来。与其来回序列化，不如让两页共享同一个 [StopwatchRecord] 的内存引用 ——
 *  记录里的字段本来就是可变的计时状态，专注页改完，列表页再回到前台时自然就是新值。
 *
 * 具体做法：
 *  · 列表页点开某个表时，把「它在卡片列表里的下标」通过 Intent 传给专注页；
 *  · 专注页 [`attach`] 时按这个下标从 [FocusTarget] 取到那份记录，双方持有同一个引用；
 *  · 专注页每次改动都调 [`persist`]，把当前内存状态整体落盘（写的是全部秒表，不是单个）；
 *  · 列表页在 `onResume` 里发现自己持有的记录被改过（[revision] 变化）就重绘对应卡片。
 *
 * 这是个「够用就停」的方案：没有跨进程、没有重建场景（两页同属一个任务栈、
 * 配置变化也不会销毁列表页的数据结构），所以不需要引入更重的状态持有者。
 */
object StopwatchBus {

    /** 当前正在被专注页操作的记录；专注页 finish 后置空。 */
    private var target: StopwatchRecord? = null

    /** 每次专注页改动计数 +1，列表页据此判断要不要重绘卡片。 */
    var revision: Long = 0L
        private set

    /** 由专注页在 onCreate 时调用，建立共享引用。 */
    fun attach(record: StopwatchRecord) {
        target = record
    }

    /** 由专注页在 onDestroy 时调用，断开共享引用，避免列表页一直拿着已死的记录。 */
    fun detach() {
        target = null
    }

    /** 当前共享的记录（列表页点开时用不到，专注页内部用）。 */
    fun current(): StopwatchRecord? = target

    /**
     * 专注页发生了一次需要落盘的改动。
     *
     * @param save 实际执行落盘的动作。由调用方（Activity）注入，
     *             因为写文件要用到 filesDir 与字符串资源，这里不持有 Context。
     */
    fun notifyChanged(save: () -> Unit) {
        revision++
        save()
    }
}
