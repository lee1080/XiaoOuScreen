package com.car.xiaoouscreen.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import com.carlyrics.util.PrivacyUtil

/**
 * 车载 GPS / 粗略定位管理
 * 实现规范 5.1 节：15分钟与 5km 复合触发闸门 (高速巡航防连击)，杜绝请求风暴
 */
class CarGpsManager(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val prefs = com.carlyrics.util.PrefsManager.getSafeStorageContext(context)
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
    private var isListening: Boolean = false

    init {
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
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
                    Log.i(TAG, "GPS triggered weather update: lat=${PrivacyUtil.maskCoordinate(currentLatitude)}, lon=${PrivacyUtil.maskCoordinate(currentLongitude)}")
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
     * (Delta Time >= 15分钟) OR (Delta Distance >= 5km AND Delta Time >= 8分钟)
     */
    private fun shouldTriggerWeatherUpdate(lat: Double, lon: Double): Boolean {
        val now = System.currentTimeMillis()
        val timeElapsed = now - lastReportedTimeMs

        // 首次定位直接触发
        if (lastReportedTimeMs == 0L) {
            lastReportedLat = lat
            lastReportedLon = lon
            lastReportedTimeMs = now
            return true
        }

        val results = FloatArray(1)
        Location.distanceBetween(lastReportedLat, lastReportedLon, lat, lon, results)
        val distance = results[0]

        val shouldTrigger = (timeElapsed >= 15 * 60 * 1000L) ||
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
            // 严谨遍历所有可用定位源 (GPS, NETWORK, PASSIVE, FUSED) 获取最精准 LastKnownLocation
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
                lastReportedTimeMs = System.currentTimeMillis()
                Log.i(TAG, "📍【定位就绪】成功读取系统已知坐标: provider=${best.provider}, lat=${PrivacyUtil.maskCoordinate(currentLatitude)}, lon=${PrivacyUtil.maskCoordinate(currentLongitude)}, acc=${best.accuracy}m, time=${best.time}")
                callback(currentLatitude, currentLongitude)
            } else {
                Log.w(TAG, "⚠️【定位提示】未能读取到已知系统坐标 (locations=${locations.size})，当前兜底: lat=${PrivacyUtil.maskCoordinate(currentLatitude)}, lon=${PrivacyUtil.maskCoordinate(currentLongitude)}")
                callback(currentLatitude, currentLongitude)
            }

            // 同时注册 GPS 与 NETWORK 双通道监听，确保室内 WiFi 与户外卫星均可无缝定位
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    30_000L,
                    100f,
                    locationListener
                )
                Log.i(TAG, "✅ 已注册 NETWORK_PROVIDER 定位监听 (室内高灵敏)")
            }

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    30_000L,
                    100f,
                    locationListener
                )
                Log.i(TAG, "✅ 已注册 GPS_PROVIDER 定位监听 (户外高精卫星)")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ 定位权限未授权，使用兜底坐标", e)
            callback(currentLatitude, currentLongitude)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动定位监听异常", e)
            callback(currentLatitude, currentLongitude)
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location updates", e)
        }
    }

    private fun isBetterLocation(loc: Location): Boolean {
        return loc.latitude != 0.0 && loc.longitude != 0.0
    }

    companion object {
        private const val TAG = "CarGpsManager"
    }
}
