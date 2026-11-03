package com.xbaos.shijianapp.ui

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.WeakHashMap

/**
 * 运行时权限申请的「一次性回调」桥。
 *
 * 平台 API 是 `Activity.requestPermissions(..., requestCode)` + `onRequestPermissionsResult`
 * 的回调对，要在每个页面里写一遍覆写方法。本应用需要申请权限的场景只有两处
 * （通知、定位），为此在每个 Activity 里加覆写并不划算，也容易漏。
 *
 * 这里用一个 `Activity → 回调` 的弱引用表把 requestCode 藏在内部：
 *  · 弱引用是为了不给已经销毁的页面续命；
 *  · 同一时刻同一个 Activity 只保留最后一次请求的回调（用户连续点两次时，
 *    后一次会覆盖前一次，不会出现「回调打架」）。
 *
 * 注意：ActivityCompat.requestPermissions 的结果最终仍会回到 Activity 的
 * `onRequestPermissionsResult`。本应用由 [com.xbaos.shijianapp.BaseActivity]
 * 统一覆写并转发进来，因此业务页面不需要自己写覆写方法。
 */
object PermissionRequester {

    /** 所有活动共用的 requestCode。因为回调按 Activity 归档，这个值不需要区分场景。 */
    private const val REQUEST_CODE = 7101

    private val callbacks = WeakHashMap<Activity, (granted: Boolean) -> Unit>()

    /**
     * 申请一个权限。
     *
     * @param onResult 用户做出选择后回调（主线程）。参数是「是否授予」。
     *                 注意：用户选「不再询问」时同样是 false，调用方应按「拒绝」处理。
     */
    fun request(activity: Activity, permission: String, onResult: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(activity, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
            return
        }
        callbacks[activity] = onResult
        ActivityCompat.requestPermissions(activity, arrayOf(permission), REQUEST_CODE)
    }

    /**
     * 由 Activity 的 `onRequestPermissionsResult` 转发进来。
     * 返回 true 表示这次结果确实属于本工具（调用方可以不再往下走）。
     */
    fun onResult(activity: Activity, requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val callback = callbacks.remove(activity) ?: return true
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        callback(granted)
        return true
    }
}
