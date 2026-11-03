package com.xbaos.shijianapp.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 「太阳情况」的一次性定位取点。
 *
 * 为什么不用 FusedLocationProviderClient：那需要引入 play-services-location 依赖，
 * 而本项目有一条硬规矩 —— **不加依赖**。平台自带的 LocationManager 对这个场景完全够用：
 *   · 只要一次坐标，不要持续定位；
 *   · 精度要求低（城市尺度足够），因此可以优先用网络/被动定位，不必等 GPS 冷启动。
 *
 * 取点顺序（由快到慢）：
 *   1. getLastKnownLocation —— 直接读系统最近一次缓存，几乎瞬时；
 *      只要不是几十公里以外的陈旧点（用 [MAX_CACHE_AGE_MS] 卡一下）就够用了。
 *   2. requestSingleUpdate —— 缓存没有或太旧时，向网络/被动/低功耗源要一次新点。
 * 无论哪条路径，超过 [TIMEOUT_MS] 都会以失败回调，绝不让页面无限等待。
 *
 * 权限：只申请 ACCESS_COARSE_LOCATION（在 Manifest 声明）。
 * 已授权时单点定位不会再次弹窗；未授权时 [start] 会直接走 [onFail]，
 * 由调用方决定「先弹原因横幅再申请」还是「直接回退手动输入」。
 */
object LocationFix {

    /** 系统缓存点的最大可接受年龄：超过就认为太旧，宁可重新取一次。 */
    private const val MAX_CACHE_AGE_MS = 10 * 60 * 1000L

    /** 单点定位的总超时。室内/无信号时宁可快速失败回退，也不要卡住页面。 */
    private const val TIMEOUT_MS = 8000L

    /** 一次结果：经纬度。用 data class 而不是 Pair，避免调用方记混顺序。 */
    data class Fix(val lat: Double, val lon: Double)

    /** 只申请粗定位：城市尺度足够，且授权弹窗文案更轻。 */
    val permission: String = Manifest.permission.ACCESS_COARSE_LOCATION

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 取一次坐标。
     *
     * @param onResult 成功回调（主线程）
     * @param onFail   失败/超时/无权限回调（主线程）。调用方应在此回退到手动输入。
     */
    @SuppressLint("MissingPermission") // 已在 hasPermission 里显式检查过，见下方注释
    // requestSingleUpdate(provider, listener, Looper) 在 API 30 起被标记废弃
    // （官方建议改用 getCurrentLocation），但它向下兼容到本项目 minSdk 24 的所有版本；
    // 换成新 API 就必须为低版本再写一套分支，收益为零 —— 因此保留旧签名并就地抑制。
    @Suppress("DEPRECATION")
    fun start(context: Context, onResult: (Fix) -> Unit, onFail: () -> Unit) {
        val appContext = context.applicationContext
        // 静态检查过不了「可能未授权」这一关，但我们已经在入口处拦住了未授权的情况；
        // 加这个兜底是为了防「检查通过后、取点时权限被撤销」的极端竞态。
        if (!hasPermission(appContext)) {
            onFail()
            return
        }

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            onFail()
            return
        }

        val main = Handler(Looper.getMainLooper())
        // 结果只允许送达一次：缓存命中后新点又回来了、或超时后结果姗姗来迟，都只认第一个
        var delivered = false
        val done: (Fix?) -> Unit = { fix ->
            if (!delivered) {
                delivered = true
                main.removeCallbacksAndMessages(null)
                if (fix != null) onResult(fix) else onFail()
            }
        }

        // 1) 先试系统缓存：这是绝大多数情况下真正走到的分支
        val cached = bestCached(manager)
        if (cached != null) {
            done(Fix(cached.latitude, cached.longitude))
            return
        }

        // 2) 缓存不可用时才请求单点。优先网络源：粗定位场景下它比 GPS 快得多
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            done(null)
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                done(Fix(location.latitude, location.longitude))
            }

            // 平台要求实现（API 24 起它们有默认实现，但仍有 OEM 会回调），留空即可 ——
            // 状态变化对「只要一次点」没有意义。
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("平台旧签名，API 29 起改为默认方法；保留覆写以兼容低版本回调")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        runCatching {
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }.onFailure {
            done(null)
            return
        }

        // 超时兜底：清零回调的同时把它自己摘掉，避免监听一直挂着耗电
        main.postDelayed({
            runCatching { manager.removeUpdates(listener) }
            done(null)
        }, TIMEOUT_MS)
    }

    /**
     * 取所有 provider 中「最新的、且不过期」的那个缓存点。
     *
     * 必须遍历全部 provider 而不是只问 GPS：很多设备刚开机时 GPS 无缓存，
     * 但网络定位缓存是有的；只问 GPS 会白白落进「重新请求」分支，多等好几秒。
     */
    @SuppressLint("MissingPermission")
    private fun bestCached(manager: LocationManager): Location? {
        val now = System.currentTimeMillis()
        return runCatching {
            manager.allProviders
                .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .filter { now - it.time in 0..MAX_CACHE_AGE_MS }
                .maxByOrNull { it.time }
        }.getOrNull()
    }
}
