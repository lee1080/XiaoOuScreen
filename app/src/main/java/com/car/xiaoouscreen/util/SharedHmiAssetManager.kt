package com.car.xiaoouscreen.util

import android.content.ComponentCallbacks2
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.collection.LruCache
import com.carlyrics.util.AppLogger
import com.car.xiaoouscreen.R

/**
 * 车规级全局 HMI 位图资源共享池 (严格 28MB 有界 LRU 缓存)：
 * 1. 彻底解决 CarWeatherCanvasView / GearCubeFlipView / DriveModeWipeView 内存无界膨胀问题；
 * 2. 限制最大显存 28MB，超出自动淘汰不常用的驾驶模式和历史切图；
 * 3. 对 1920×384 实色背景图强制采用 RGB_565 解码 (单张 1.47MB)；
 * 4. 完美响应系统 onTrimMemory 内存修剪事件。
 */
object SharedHmiAssetManager {

    private const val TAG = "SharedHmiAssetManager"
    private const val MAX_CACHE_BYTES = 28 * 1024 * 1024 // 28MB 车规安全上限

    private val lruCache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount.coerceAtLeast(1)
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) {
                AppLogger.d(TAG, "LRU 显存淘汰闲置位图: $key (${oldValue.byteCount / 1024}KB)")
            }
        }
    }

    private val rgb565Options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inDither = true
    }

    private val argb8888Options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    // 柔和体积云采用 1/2 分辨率解码 (1280×192)，单张显存与 GPU 带宽直降 75%，双线性放大肉眼无损
    private val cloudHalfResOptions = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = 2
    }

    /**
     * 获取实色档位 WebP 背景图 (1920×384，RGB_565)
     */
    fun getGearBitmap(res: Resources, gear: String): Bitmap? {
        val key = "gear_${gear.uppercase()}"
        return synchronized(lruCache) {
            lruCache.get(key)?.takeIf { !it.isRecycled } ?: run {
                val resId = when (gear.uppercase()) {
                    "P" -> R.drawable.bg_gear_p
                    "R" -> R.drawable.bg_gear_r
                    "N" -> R.drawable.bg_gear_n
                    "D" -> R.drawable.bg_gear_d
                    else -> R.drawable.bg_fallback_space
                }
                decodeResourceSafe(res, resId, rgb565Options)?.also {
                    lruCache.put(key, it)
                }
            }
        }
    }

    fun getFallbackGearBitmap(res: Resources): Bitmap? {
        val key = "gear_fallback"
        return synchronized(lruCache) {
            lruCache.get(key)?.takeIf { !it.isRecycled } ?: run {
                decodeResourceSafe(res, R.drawable.bg_fallback_space, rgb565Options)?.also {
                    lruCache.put(key, it)
                }
            }
        }
    }

    /**
     * 获取实色驾驶模式 WebP 背景图 (1920×384，RGB_565)
     */
    fun getDriveModeBitmap(res: Resources, mode: String): Bitmap? {
        val key = "mode_${mode.uppercase()}"
        return synchronized(lruCache) {
            lruCache.get(key)?.takeIf { !it.isRecycled } ?: run {
                val resId = when (mode.uppercase()) {
                    "ECO" -> R.drawable.bg_mode_eco
                    "NORMAL" -> R.drawable.bg_mode_normal
                    "SPORT" -> R.drawable.bg_mode_sport
                    "RACE" -> R.drawable.bg_mode_race
                    "CUSTOM" -> R.drawable.bg_mode_custom
                    else -> R.drawable.bg_mode_normal
                }
                decodeResourceSafe(res, resId, rgb565Options)?.also {
                    lruCache.put(key, it)
                }
            }
        }
    }

    /**
     * 获取写实天气天体素材 (ARGB_8888 共享单份)
     */
    fun getCelestialBitmap(res: Resources, type: String): Bitmap? {
        val key = "celestial_$type"
        return synchronized(lruCache) {
            lruCache.get(key)?.takeIf { !it.isRecycled } ?: run {
                val resId = when (type) {
                    "sun" -> R.drawable.weather_celestial_sun
                    "sun_rays" -> R.drawable.weather_celestial_sun_rays
                    "sun_flare" -> R.drawable.weather_sun_flare
                    "moon" -> R.drawable.weather_celestial_moon
                    else -> return null
                }
                decodeResourceSafe(res, resId, argb8888Options)?.also {
                    lruCache.put(key, it)
                }
            }
        }
    }

    /**
     * 获取写实天气 5 层无缝体积云纹理素材 (2560×384 ARGB_8888 共享单份)
     */
    fun getCloudBitmap(res: Resources, type: String): Bitmap? {
        val key = "cloud_$type"
        return synchronized(lruCache) {
            lruCache.get(key)?.takeIf { !it.isRecycled } ?: run {
                val resId = when (type) {
                    "cirrus" -> R.drawable.weather_cloud_cirrus
                    "cumulus_back" -> R.drawable.weather_cloud_cumulus_back
                    "cumulus_front" -> R.drawable.weather_cloud_cumulus_front
                    "mist" -> R.drawable.weather_cloud_mist
                    "overcast" -> R.drawable.weather_cloud_overcast
                    else -> return null
                }
                decodeResourceSafe(res, resId, cloudHalfResOptions)?.also {
                    lruCache.put(key, it)
                }
            }
        }
    }

    /**
     * 获取车载通话分层动效切图 (ARGB_8888 共享单份)
     */
    fun getCallBitmap(res: Resources, type: String): Bitmap? {
        val key = "call_$type"
        return synchronized(lruCache) {
            lruCache.get(key)?.takeIf { !it.isRecycled } ?: run {
                val resId = when (type) {
                    "incoming_handset" -> R.drawable.ic_call_incoming_handset
                    "incoming_arrow" -> R.drawable.ic_call_incoming_arrow
                    "incoming_ripple" -> R.drawable.ic_call_incoming_ripple
                    "outgoing_handset" -> R.drawable.ic_call_outgoing_handset
                    "outgoing_arrow" -> R.drawable.ic_call_outgoing_arrow
                    "outgoing_wave1" -> R.drawable.ic_call_outgoing_wave1
                    "outgoing_wave2" -> R.drawable.ic_call_outgoing_wave2
                    "connected_handset" -> R.drawable.ic_call_connected_handset
                    "connected_wave1" -> R.drawable.ic_call_connected_wave1
                    "connected_wave2" -> R.drawable.ic_call_connected_wave2
                    "connected_wave3" -> R.drawable.ic_call_connected_wave3
                    "hangup_handset" -> R.drawable.ic_call_hangup_handset
                    "hangup_halo" -> R.drawable.ic_call_hangup_halo
                    "wechat_back" -> R.drawable.ic_call_wechat_back
                    "wechat_front" -> R.drawable.ic_call_wechat_front
                    "wechat_wave" -> R.drawable.ic_call_wechat_wave
                    else -> return null
                }
                decodeResourceSafe(res, resId, argb8888Options)?.also {
                    lruCache.put(key, it)
                }
            }
        }
    }

    private fun decodeResourceSafe(res: Resources, resId: Int, opts: BitmapFactory.Options): Bitmap? {
        return try {
            BitmapFactory.decodeResource(res, resId, opts)
        } catch (t: Throwable) {
            AppLogger.e(TAG, "解码资源异常 (resId=$resId): ${t.message}", t)
            null
        }
    }

    fun trimMemory(level: Int) {
        synchronized(lruCache) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                lruCache.evictAll()
            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
                       level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                lruCache.trimToSize(MAX_CACHE_BYTES / 2)
            }
        }
    }

    fun clearAll() {
        synchronized(lruCache) {
            lruCache.evictAll()
            Log.i(TAG, "已清空所有共享 HMI 位图 LRU 缓存")
        }
    }
}
