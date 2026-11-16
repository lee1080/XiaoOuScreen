package com.car.xiaoouscreen.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.chinatsp.threescreenmiddleservice.INotifyShowInterface
import com.chinatsp.threescreenmiddleservice.ITSmiddleService

/**
 * 欧尚三屏系统调度客户端 (绑定 NotifyService)
 * 视图槽位规范：统一使用 OSHAN_FITNESS (priority=7, 最高普通业务优先级，避开待机指南针与系统形象冲突)
 */
class ThreeScreenClient(
    context: Context,
    private val callback: Callback? = null
) {
    interface Callback {
        fun onBound(ok: Boolean)
        fun onShowRequest(type: String)
        fun onHideRequest(type: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: ITSmiddleService? = null
    var isBound = false
        private set

    private val notifyStub = object : INotifyShowInterface.Stub() {
        override fun show(type: String?) {
            Log.i(TAG, "三屏服务调度 show: type=$type")
            mainHandler.post {
                callback?.onShowRequest(type ?: "")
            }
        }

        override fun hide(type: String?) {
            Log.i(TAG, "三屏服务调度 hide: type=$type")
            mainHandler.post {
                callback?.onHideRequest(type ?: "")
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = ITSmiddleService.Stub.asInterface(binder)
            isBound = true
            Log.i(TAG, "ThreeScreen NotifyService 连接成功")
            try {
                service?.registerNotifyListener(notifyStub)
                service?.registerListenerByPackage(notifyStub, appContext.packageName)
            } catch (e: RemoteException) {
                Log.w(TAG, "注册三屏监听异常: ${e.message}")
            }
            mainHandler.post { callback?.onBound(true) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "ThreeScreen NotifyService 连接断开")
            service = null
            isBound = false
            mainHandler.post { callback?.onBound(false) }
        }
    }

    fun bind() {
        if (isBound) return
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.chinatsp.threescreenmiddleservice",
                    "com.chinatsp.threescreenmiddleservice.NotifyService"
                )
            }
            val ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService NotifyService: $ok")
        } catch (e: Exception) {
            Log.w(TAG, "bindService 失败: ${e.message}")
        }
    }

    fun unbind() {
        if (!isBound) return
        try {
            service?.unRegisterNotifyListener(notifyStub)
            service?.unRegisterListenerByPackage(notifyStub, appContext.packageName)
            appContext.unbindService(connection)
        } catch (e: Exception) {
            Log.w(TAG, "unbindService 异常: ${e.message}")
        } finally {
            isBound = false
            service = null
        }
    }

    /**
     * 查询车机三屏调度当前前台显示的视图类型
     * 例如："MEDIA" (媒体/蓝牙音乐/酷狗)、"NEBULAR_COMPASS" (待机指南针)、"NAVIGATION" (导航) 等
     */
    fun getCurrentView(): String? {
        if (!isBound || service == null) return null
        return try {
            service?.currentView
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentView 远程调用异常: ${e.message}")
            null
        }
    }

    /**
     * 查询当前副屏是否处于系统高优先级窗口 (如 APA 泊车辅助、休息模式等)
     */
    fun isHighWindowView(): Boolean {
        if (!isBound || service == null) return false
        return try {
            service?.isHighWindowView ?: false
        } catch (e: Exception) {
            Log.w(TAG, "isHighWindowView 远程调用异常: ${e.message}")
            false
        }
    }

    /**
     * 判断指定视图名是否为原厂媒体类界面 (蓝牙音乐、酷狗音乐等)
     */
    fun isMediaView(viewType: String?): Boolean {
        if (viewType.isNullOrBlank()) return false
        return viewType.contains(VIEW_MEDIA, ignoreCase = true)
    }

    /**
     * 核心自检测：主动探测当前副屏是否正被原厂媒体视图 (MEDIA) 遮挡
     */
    fun isOccludedByNativeMedia(): Boolean {
        val current = getCurrentView()
        return isMediaView(current)
    }

    /**
     * 申请三屏切换到 OSHAN_FITNESS 槽位
     */
    fun requestShowFitness() {
        try {
            service?.notifyShowView(SOURCE_FITNESS)
        } catch (e: Exception) {
            Log.w(TAG, "requestShowFitness 异常: ${e.message}")
        }
    }

    /**
     * 释放槽位，交还三屏控制权
     */
    fun requestHideFitness() {
        try {
            service?.notifyHideView(SOURCE_FITNESS)
        } catch (e: Exception) {
            Log.w(TAG, "requestHideFitness 异常: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ThreeScreenClient"
        const val SOURCE_FITNESS = "OSHAN_FITNESS"
        const val VIEW_MEDIA = "MEDIA"
        const val VIEW_NAVIGATION = "NAVIGATION"
        const val VIEW_COMPASS = "NEBULAR_COMPASS"
        const val VIEW_TRAVEL = "TRAVEL"
        const val VIEW_CIRCUIT = "CIRCUIT_MODEL"
        const val VIEW_REST = "REST_MODE"
        const val VIEW_OTA = "OTA_UPGRADE"
        const val VIEW_APA = "APA"
        const val VIEW_VOICE_TTS = "VOICE_TTS"
    }
}
