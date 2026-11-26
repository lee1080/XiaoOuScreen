package com.car.xiaoouscreen.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.carlyrics.util.AppLogger
import com.carlyrics.util.PrivacyUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 车载 GPS / 粗略定位管理
 * 实现规范 5.1 节：15分钟与 5km 复合触发闸门 (高速巡航防连击)，杜绝请求风暴
 */
class CarGpsManager private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val prefs = com.carlyrics.util.PrefsManager.getSafeStorageContext(appContext)
        .getSharedPreferences("car_gps_prefs", Context.MODE_PRIVATE)

    // 默认经纬度 (采用双精度 LongBits 存储，杜绝 Float 强转精度截断与跳变)
    @Volatile
    var currentLatitude: Double = if (prefs.contains("last_lat_bits")) {
        java.lang.Double.longBitsToDouble(prefs.getLong("last_lat_bits", java.lang.Double.doubleToRawLongBits(30.82)))
    } else {
        prefs.getFloat("last_lat", 30.82f).toDouble()
    }
        private set

    @Volatile
    var currentLongitude: Double = if (prefs.contains("last_lon_bits")) {
        java.lang.Double.longBitsToDouble(prefs.getLong("last_lon_bits", java.lang.Double.doubleToRawLongBits(104.18)))
    } else {
        prefs.getFloat("last_lon", 104.18f).toDouble()
    }
        private set

    @Volatile
    private var lastReportedLat: Double = currentLatitude
    @Volatile
    private var lastReportedLon: Double = currentLongitude
    @Volatile
    private var lastReportedTimeMs: Long = 0L

    @Volatile
    private var hasReceivedLiveFix: Boolean = false

    @Volatile
    private var isListening: Boolean = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                val locations = providers.mapNotNull { p ->
                    try { locationManager.getLastKnownLocation(p) } catch (_: Exception) { null }
                }
                val best = locations.maxByOrNull { it.time }
                if (best != null && isBetterLocation(best)) {
                    currentLatitude = best.latitude
                    currentLongitude = best.longitude
                    lastReportedLat = best.latitude
                    lastReportedLon = best.longitude
                    saveLocation(best.latitude, best.longitude)
                    Log.i(TAG, "📍【初始化就绪】读取到系统已知坐标: lat=${PrivacyUtil.maskCoordinate(currentLatitude)}, lon=${PrivacyUtil.maskCoordinate(currentLongitude)}")
                }
            } catch (_: Exception) {}
        }
    }

    private fun saveLocation(lat: Double, lon: Double) {
        prefs.edit()
            .putLong("last_lat_bits", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("last_lon_bits", java.lang.Double.doubleToRawLongBits(lon))
            .apply()
    }

    private var onLocationChangedCallback: ((Double, Double) -> Unit)? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (isBetterLocation(loc)) {
                currentLatitude = loc.latitude
                currentLongitude = loc.longitude
                saveLocation(loc.latitude, loc.longitude)

                if (shouldTriggerWeatherUpdate(loc.latitude, loc.longitude)) {
                    AppLogger.i(TAG, "📍 [实时定位触发天气更新] provider=${loc.provider}, lat=${PrivacyUtil.maskCoordinate(currentLatitude)}, lon=${PrivacyUtil.maskCoordinate(currentLongitude)}, acc=${loc.accuracy}m")
                    onLocationChangedCallback?.invoke(currentLatitude, currentLongitude)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * 复合防连击门禁：
     * 1. 启动/唤醒后的首次实时卫星/网络定位：若距离初始历史/兜底坐标 >= 1km 或距上次已超 2分钟，立即穿透放行，绝不死等 8 分钟！
     * 2. 长距离定位纠偏/出长隧道 (Delta Distance >= 15km)：立即放行。
     * 3. 常规高速巡航：(Delta Time >= 15分钟) OR (Delta Distance >= 5km AND Delta Time >= 8分钟)
     */
    private fun shouldTriggerWeatherUpdate(lat: Double, lon: Double): Boolean {
        val now = System.currentTimeMillis()
        val timeElapsed = now - lastReportedTimeMs

        val results = FloatArray(1)
        Location.distanceBetween(lastReportedLat, lastReportedLon, lat, lon, results)
        val distance = results[0]

        // 首次实时定位到达：如果坐标与冷启动兜底/历史坐标偏差超过 1km，立即纠偏刷新
        if (!hasReceivedLiveFix) {
            hasReceivedLiveFix = true
            if (lastReportedTimeMs == 0L || distance >= 1000f || timeElapsed >= 2 * 60 * 1000L) {
                lastReportedLat = lat
                lastReportedLon = lon
                lastReportedTimeMs = now
                return true
            }
        }

        // 大幅跳变纠偏 (例如跨区出隧道/初始兜底定位修正)
        val shouldTrigger = (distance >= 15000f && timeElapsed >= 60 * 1000L) ||
                (timeElapsed >= 15 * 60 * 1000L) ||
                (distance >= 5000f && timeElapsed >= 8 * 60 * 1000L)

        if (shouldTrigger) {
            lastReportedLat = lat
            lastReportedLon = lon
            lastReportedTimeMs = now
        }
        return shouldTrigger
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(callback: (Double, Double) -> Unit) {
        this.onLocationChangedCallback = callback
        if (isListening) {
            callback(currentLatitude, currentLongitude)
            return
        }
        isListening = true

        try {
            // 严谨遍历所有可用定位源 (GPS, NETWORK, PASSIVE) 获取最精准 LastKnownLocation
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            val locations = providers.mapNotNull { p ->
                try { locationManager.getLastKnownLocation(p) } catch (_: Exception) { null }
            }

            // 优先选择最新且精度最高的有效坐标
            val best = locations.maxByOrNull { it.time }

            if (best != null && isBetterLocation(best)) {
                currentLatitude = best.latitude
                currentLongitude = best.longitude
                lastReportedLat = best.latitude
                lastReportedLon = best.longitude
                saveLocation(best.latitude, best.longitude)
                // 注意：此处不将 lastReportedTimeMs 锁为当前时刻，也不置 hasReceivedLiveFix = true，
                // 以确保车辆驶出车库拿到真实实时卫星定位后可第一时间穿透纠偏！
                AppLogger.i(TAG, "📍【定位就绪】读取系统历史坐标: provider=${best.provider}, lat=${PrivacyUtil.maskCoordinate(currentLatitude)}, lon=${PrivacyUtil.maskCoordinate(currentLongitude)}, acc=${best.accuracy}m")
                callback(currentLatitude, currentLongitude)
            } else {
                AppLogger.w(TAG, "⚠️【定位提示】未能读取到已知系统坐标 (locations=${locations.size})，当前兜底: lat=${PrivacyUtil.maskCoordinate(currentLatitude)}, lon=${PrivacyUtil.maskCoordinate(currentLongitude)}")
                callback(currentLatitude, currentLongitude)
            }

            // 同时注册 GPS、NETWORK 与 PASSIVE 三通道监听，确保室内 WiFi、户外卫星及车机高德导航共享定位均可无缝捕获
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    30_000L,
                    100f,
                    locationListener,
                    Looper.getMainLooper()
                )
                AppLogger.i(TAG, "✅ 已注册 NETWORK_PROVIDER 定位监听")
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    30_000L,
                    100f,
                    locationListener,
                    Looper.getMainLooper()
                )
                AppLogger.i(TAG, "✅ 已注册 GPS_PROVIDER 定位监听")
            }

            if (locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    30_000L,
                    200f,
                    locationListener,
                    Looper.getMainLooper()
                )
                AppLogger.i(TAG, "✅ 已注册 PASSIVE_PROVIDER 被动定位监听 (复用车机导航实时定位)")
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "❌ 定位权限未授权，使用兜底坐标", e)
            callback(currentLatitude, currentLongitude)
        } catch (e: Exception) {
            AppLogger.e(TAG, "❌ 启动定位监听异常: ${e.message}", e)
            callback(currentLatitude, currentLongitude)
        }
    }

    /**
     * 车机 STR 休眠唤醒/点火上车时调用：主动抓取最新系统定位，并解锁首次实时定位快速纠偏通道
     */
    @SuppressLint("MissingPermission")
    fun requestFreshLocationForWakeup() {
        hasReceivedLiveFix = false
        try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            val best = providers.mapNotNull { p ->
                try { locationManager.getLastKnownLocation(p) } catch (_: Exception) { null }
            }.maxByOrNull { it.time }

            if (best != null && isBetterLocation(best)) {
                currentLatitude = best.latitude
                currentLongitude = best.longitude
                saveLocation(best.latitude, best.longitude)
            }
        } catch (_: Exception) {}
    }

    fun forceRefresh() {
        lastReportedTimeMs = System.currentTimeMillis()
        onLocationChangedCallback?.invoke(currentLatitude, currentLongitude)
    }

    fun stopLocationUpdates() {
        if (!isListening) return
        try {
            locationManager.removeUpdates(locationListener)
            isListening = false
            onLocationChangedCallback = null
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error stopping location updates", e)
        }
    }

    private fun isBetterLocation(loc: Location): Boolean {
        return loc.latitude != 0.0 && loc.longitude != 0.0
    }

    companion object {
        private const val TAG = "CarGpsManager"

        @Volatile
        private var instance: CarGpsManager? = null

        fun getInstance(context: Context): CarGpsManager {
            return instance ?: synchronized(this) {
                instance ?: CarGpsManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
