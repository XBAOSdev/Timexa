package com.xbaos.shijianapp.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.xbaos.shijianapp.R

/**
 * 通知相关的统一入口：渠道创建 + 权限判断。
 *
 * 通知渠道只能在创建后由用户决定强度，名称一旦注册就固化在系统设置里，
 * 所以字符串资源必须在「创建那一刻」就取对语言 —— 因此渠道创建放在每次
 * 进入相关页面时做一次（已存在则直接返回，开销可忽略），而不是只在 Application 里建一次。
 * 这样用户切换语言后，通知栏里的渠道名也会跟着更新。
 */
object Notifier {

    /** 计时中的常驻通知（前台服务）。低优先级：常驻但不打扰。 */
    const val CHANNEL_ONGOING = "timing_ongoing"

    /** 阶段结束提醒。默认优先级：需要提示音/振动提醒用户。 */
    const val CHANNEL_ALERT = "timing_alert"

    /** 常驻通知的固定 id：同一个 id 才能「更新」而不是堆叠。 */
    const val ID_TIMING = 1001

    /** 到点提醒的 id。 */
    const val ID_ALERT = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        createChannel(context, CHANNEL_ONGOING, R.string.notification_channel_ongoing, NotificationManager.IMPORTANCE_LOW)
        createChannel(context, CHANNEL_ALERT, R.string.notification_channel_alert, NotificationManager.IMPORTANCE_DEFAULT)
    }

    private fun createChannel(context: Context, id: String, nameRes: Int, importance: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(id) != null) return
        manager.createNotificationChannel(
            NotificationChannel(id, context.getString(nameRes), importance)
        )
    }

    /**
     * 是否已经可以发通知。
     * Android 13 起发通知需要运行时授权；未授权时前台服务仍能运行，
     * 只是用户看不到那条常驻通知，因此这里只做判断、不做拦截。
     */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 取通知管理器；失败返回 null，调用方据此跳过发送而不是崩溃。 */
    fun notificationManager(context: Context): NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /** 需要向系统申请的通知权限名；API 33 以下不需要，返回 null。 */
    fun permissionToRequest(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS else null
}
