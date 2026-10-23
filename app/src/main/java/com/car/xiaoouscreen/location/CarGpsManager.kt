package com.car.xiaoouscreen.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

/**
 * 车载 GPS / 粗略定位管理
 * 优先获取车载 GPS 传感器或系统最后已知位置，失败时自动兜底安全经纬度
 */
class CarGpsManager(private val context: Context) {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    // 默认兜底经纬度 (上海人民广场 31.2304, 121.4737)
    var currentLatitude: Double = 31.2304
        private set
    var currentLongitude: Double = 121.4737
        private set

    private var onLocationChangedCallback: ((Double, Double) -> Unit)? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (isBetterLocation(loc)) {
                currentLatitude = loc.latitude
                currentLongitude = loc.longitude
                Log.i(TAG, "GPS updated: lat=$currentLatitude, lon=$currentLongitude")
                onLocationChangedCallback?.invoke(currentLatitude, currentLongitude)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(callback: (Double, Double) -> Unit) {
        this.onLocationChangedCallback = callback

        try {
            // 先尝试读取 LastKnownLocation 立即生效
            val gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = if (gpsLoc != null && netLoc != null) {
                if (gpsLoc.time > netLoc.time) gpsLoc else netLoc
            } else {
                gpsLoc ?: netLoc
            }

            best?.let {
                currentLatitude = it.latitude
                currentLongitude = it.longitude
                callback(currentLatitude, currentLongitude)
            }

            // 注册车载移动监听 (最小时间 60秒，最小位移 500米，减少车机中断)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    60_000L,
                    500f,
                    locationListener
                )
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    60_000L,
                    500f,
                    locationListener
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted, using fallback coordinates")
            callback(currentLatitude, currentLongitude)
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating location updates", e)
            callback(currentLatitude, currentLongitude)
        }
    }

    fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping location updates", e)
        }
    }

    private fun isBetterLocation(loc: Location): Boolean {
        // 过滤经纬度异常 (0, 0)
        return loc.latitude != 0.0 && loc.longitude != 0.0
    }

    companion object {
        private const val TAG = "CarGpsManager"
    }
}
