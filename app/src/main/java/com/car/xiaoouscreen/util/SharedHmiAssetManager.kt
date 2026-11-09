package com.car.xiaoouscreen.util

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.car.xiaoouscreen.R
import java.util.concurrent.ConcurrentHashMap

/**
 * 车规级全局 HMI 位图资源共享池 (单例零重复解码)：
 * 1. 彻底解决 CarWeatherCanvasView / GearCubeFlipView / DriveModeWipeView 在不同窗口或与
 *    CarSubscreenActivity 并存时重复全量解码位图的问题 (消除 95MB+ 冗余堆内存)；
 * 2. 对所有 1920×384 实色不透明背景图 (档位 P/R/N/D、驾驶模式 5 大背景) 强制使用 RGB_565 解码，
 *    单图内存直接从 2.95MB 降至 1.47MB (立减 50%)；
 * 3. 对带 Alpha 透明通道的天体、体积云与通话图标，采用 ARGB_8888 单份常驻复用；
 * 4. 线程安全并发缓存，支持生命周期自愈与内存清理。
 */
object SharedHmiAssetManager {

    private const val TAG = "SharedHmiAssetManager"

    // 内存共享缓存字典
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()

    private val rgb565Options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inDither = true
    }

    private val argb8888Options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    /**
     * 获取实色档位 WebP 背景图 (1920×384，RGB_565)
     */
    fun getGearBitmap(res: Resources, gear: String): Bitmap? {
        val key = "gear_${gear.uppercase()}"
        return bitmapCache[key]?.takeIf { !it.isRecycled } ?: synchronized(this) {
            bitmapCache[key]?.takeIf { !it.isRecycled } ?: run {
                val resId = when (gear.uppercase()) {
                    "P" -> R.drawable.bg_gear_p
                    "R" -> R.drawable.bg_gear_r
                    "N" -> R.drawable.bg_gear_n
                    "D" -> R.drawable.bg_gear_d
                    else -> R.drawable.bg_fallback_space
                }
                decodeResourceSafe(res, resId, rgb565Options)?.also {
                    bitmapCache[key] = it
                    Log.d(TAG, "加载共享档位底图: $key (RGB_565, 1.47MB)")
                }
            }
        }
    }

    fun getFallbackGearBitmap(res: Resources): Bitmap? {
        val key = "gear_fallback"
        return bitmapCache[key]?.takeIf { !it.isRecycled } ?: synchronized(this) {
            bitmapCache[key]?.takeIf { !it.isRecycled } ?: run {
                decodeResourceSafe(res, R.drawable.bg_fallback_space, rgb565Options)?.also {
                    bitmapCache[key] = it
                }
            }
        }
    }

    /**
     * 获取实色驾驶模式 WebP 背景图 (1920×384，RGB_565)
     */
    fun getDriveModeBitmap(res: Resources, mode: String): Bitmap? {
        val key = "mode_${mode.uppercase()}"
        return bitmapCache[key]?.takeIf { !it.isRecycled } ?: synchronized(this) {
            bitmapCache[key]?.takeIf { !it.isRecycled } ?: run {
                val resId = when (mode.uppercase()) {
                    "ECO" -> R.drawable.bg_mode_eco
                    "NORMAL" -> R.drawable.bg_mode_normal
                    "SPORT" -> R.drawable.bg_mode_sport
                    "RACE" -> R.drawable.bg_mode_race
                    "CUSTOM" -> R.drawable.bg_mode_custom
                    else -> R.drawable.bg_mode_normal
                }
                decodeResourceSafe(res, resId, rgb565Options)?.also {
                    bitmapCache[key] = it
                    Log.d(TAG, "加载共享模式底图: $key (RGB_565, 1.47MB)")
                }
            }
        }
    }

    /**
     * 获取写实天气天体素材 (ARGB_8888 共享单份)
     */
    fun getCelestialBitmap(res: Resources, type: String): Bitmap? {
        val key = "celestial_$type"
        return bitmapCache[key]?.takeIf { !it.isRecycled } ?: synchronized(this) {
            bitmapCache[key]?.takeIf { !it.isRecycled } ?: run {
                val resId = when (type) {
                    "sun" -> R.drawable.weather_celestial_sun
                    "sun_rays" -> R.drawable.weather_celestial_sun_rays
                    "sun_flare" -> R.drawable.weather_sun_flare
                    "moon" -> R.drawable.weather_celestial_moon
                    else -> return null
                }
                decodeResourceSafe(res, resId, argb8888Options)?.also {
                    bitmapCache[key] = it
                }
            }
        }
    }

    /**
     * 获取写实天气 5 层无缝体积云纹理素材 (2560×384 ARGB_8888 共享单份)
     */
    fun getCloudBitmap(res: Resources, type: String): Bitmap? {
        val key = "cloud_$type"
        return bitmapCache[key]?.takeIf { !it.isRecycled } ?: synchronized(this) {
            bitmapCache[key]?.takeIf { !it.isRecycled } ?: run {
                val resId = when (type) {
                    "cirrus" -> R.drawable.weather_cloud_cirrus
                    "cumulus_back" -> R.drawable.weather_cloud_cumulus_back
                    "cumulus_front" -> R.drawable.weather_cloud_cumulus_front
                    "mist" -> R.drawable.weather_cloud_mist
                    "overcast" -> R.drawable.weather_cloud_overcast
                    else -> return null
                }
                decodeResourceSafe(res, resId, argb8888Options)?.also {
                    bitmapCache[key] = it
                    Log.d(TAG, "加载共享体积云纹理: $key (2560x384, 3.93MB)")
                }
            }
        }
    }

    /**
     * 获取车载通话分层动效切图 (ARGB_8888 共享单份)
     */
    fun getCallBitmap(res: Resources, type: String): Bitmap? {
        val key = "call_$type"
        return bitmapCache[key]?.takeIf { !it.isRecycled } ?: synchronized(this) {
            bitmapCache[key]?.takeIf { !it.isRecycled } ?: run {
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
                    bitmapCache[key] = it
                }
            }
        }
    }

    private fun decodeResourceSafe(res: Resources, resId: Int, opts: BitmapFactory.Options): Bitmap? {
        return try {
            BitmapFactory.decodeResource(res, resId, opts)
        } catch (t: Throwable) {
            Log.e(TAG, "解码资源异常 (resId=$resId): ${t.message}", t)
            null
        }
    }

    /**
     * 释放所有缓存位图引用 (通常在应用退出或系统低内存警报时调用)
     * 注意：严禁在 Android 8.0+ 手动对共享位图调用 bmp.recycle()！
     * 因为硬件加速渲染线程 (HWUI RenderThread) 与自定义 View 的 onDraw 存在并发绘制可能，
     * 若强行 recycle() 会导致正在参与帧绘制的 Canvas 抛出 "trying to use a recycled bitmap" 致命崩溃。
     * 仅清空引用字典，交由 JVM GC 与底层 Native Finalizer 安全平滑回收显存。
     */
    fun clearAll() {
        synchronized(this) {
            bitmapCache.clear()
            Log.i(TAG, "已清空所有共享 HMI 位图缓存引用")
        }
    }
}
