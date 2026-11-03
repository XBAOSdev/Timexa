package com.xbaos.shijianapp.ui

/**
 * 跨页面的一次性动作标记。
 *
 * 场景：在设置页清空了计时记录，但秒表页可能还在返回栈里活着 ——
 * 它的界面只在 onCreate 时加载过一次，返回时不会重新读文件，
 * 更糟的是它退出时还会把内存里的旧记录写回磁盘，把清空操作覆盖掉。
 * 因此清空时置位，秒表页在下次可见时据此重新加载。
 */
object PendingActions {

    @Volatile
    var stopwatchCleared: Boolean = false

    /**
     * 纪念日数据在后台被改过（导入备份），纪念日页下次可见时需重载。
     * 与 [stopwatchCleared] 同一套机制；置位方负责在数据落盘**之后**再置位。
     */
    @Volatile
    var anniversaryChanged: Boolean = false
}
