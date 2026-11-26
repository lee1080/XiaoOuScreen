package com.car.xiaoouscreen.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import com.carlyrics.util.AppLogger
import com.carlyrics.util.PrivacyUtil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * 欧尚 Z6 iDD / B561 智能座舱通话状态感知管理器
 *
 * 核心功能：
 * 1. 深度对接长安原厂系统服务 com.chinatsp.phone (BluetoothPhoneService)
 * 2. 纯 Binder/AIDL 动态 Stub 代理，免硬编译依赖
 * 3. 智能区分识别普通蓝牙电话 vs 微信电话 (基于 iOS CallKit / 蓝牙 HFP 关键字特征)
 * 4. 内置秒表毫秒走时驱动与挂断延时退场状态机
 * 5. 全面支持模拟测试与 ADB 外部指令注入
 */
class CarCallManager private constructor(private val context: Context) {

    enum class CallType {
        NORMAL, // 普通通话
        WECHAT  // 微信电话
    }

    enum class CallState {
        IDLE,       // 空闲无通话
        RINGING,    // 来电振铃 (Incoming)
        DIALING,    // 呼出拨号 (Outgoing)
        ACTIVE,     // 通话中/已接通 (Connected)
        TERMINATED  // 已挂断/结束 (Hang Up)
    }

    data class CallInfo(
        val type: CallType = CallType.NORMAL,
        val state: CallState = CallState.IDLE,
        val name: String = "",
        val number: String = "",
        val durationSeconds: Long = 0L,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        // 核心修正：仅当处于响铃、呼出拨号或通话中时，才判定为活跃通话状态 (已挂断 TERMINATED 不再阻塞置顶接管)
        val isCallActive: Boolean
            get() = state == CallState.RINGING || state == CallState.DIALING || state == CallState.ACTIVE

        // 是否处于通话展示态 (含挂断退场展示)
        val isDisplayActive: Boolean
            get() = state != CallState.IDLE
    }

    interface CallListener {
        fun onCallChanged(callInfo: CallInfo)
    }

    companion object {
        private const val TAG = "CarCallManager"

        // 原厂蓝牙电话服务配置 (来自 GEAR_DRIVE_MODE_DEV.md)
        private const val BT_PHONE_PKG = "com.chinatsp.phone"
        private const val BT_PHONE_CLS = "com.chinatsp.phone.bt.profile.BluetoothPhoneService"
        private const val BT_PHONE_ACTION = "com.chinatsp.phone.bt.barrot.BluetoothPhoneService"

        private const val DESCRIPTOR_MANAGER = "com.chinatsp.phone.aidl.IBluetoothPhoneManager"
        private const val DESCRIPTOR_REPLY = "com.chinatsp.phone.aidl.IBluetoothPhoneReply"

        // AIDL Transact 事务码
        private const val TRANSACTION_REGISTER_REPLY = 1
        private const val TRANSACTION_UNREGISTER_REPLY = 2
        private const val TRANSACTION_GET_CALLS = 29

        // IBluetoothPhoneReply 回调事务码
        private const val REPLY_TRANSACTION_ON_BOTH_CONTACT_CALL = 12
        private const val REPLY_TRANSACTION_ON_BOTH_CALL_TERMINATED = 13
        private const val REPLY_TRANSACTION_ON_CONTACT_CALLING_THIRD_TERMINATED = 10
        private const val REPLY_TRANSACTION_ON_THIRD_CALLING_CONTACT_TERMINATED = 11

        // 原厂 CallContact 状态整型常量
        private const val RAW_STATE_ACTIVE = 0
        private const val RAW_STATE_HELD_CALL = 1
        private const val RAW_STATE_DIALING = 2
        private const val RAW_STATE_RINGING = 3
        private const val RAW_STATE_INCOMING = 4
        private const val RAW_STATE_WAITING = 5
        private const val RAW_STATE_HELD_BY_HOLD = 6
        private const val RAW_STATE_TERMINATED = 7

        @Volatile
        private var instance: CarCallManager? = null

        fun getInstance(context: Context): CarCallManager {
            return instance ?: synchronized(this) {
                instance ?: CarCallManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * 启发式微信电话识别算法：
         * 当手机（如 iPhone CallKit 或安卓 VoIP）通过蓝牙连接车机时，
         * 联系人名称或号码中常带有“微信”、“WeChat”、“wx”等关键字。
         */
        fun detectCallType(rawName: String, rawNumber: String): CallType {
            val lowerName = rawName.lowercase()
            val lowerNum = rawNumber.lowercase()
            return if (lowerName.contains("微信") || lowerName.contains("wechat") || lowerName.contains("wx") ||
                lowerNum.contains("微信") || lowerNum.contains("wechat") || lowerNum.contains("wx")
            ) {
                CallType.WECHAT
            } else {
                CallType.NORMAL
            }
        }

        /**
         * 清理联系人姓名中的微信前缀，保留纯净昵称
         * 例如：“微信语音: 张总” -> “张总”
         */
        fun cleanContactName(rawName: String): String {
            val cleaned = rawName.replace(
                Regex("^(微信语音|微信电话|微信通话|wechat call|wechat)[:：\\s]*", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            return cleaned.ifBlank { rawName.trim() }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CarCall-Worker").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<CallListener>()

    private var currentCallInfo = CallInfo()
    private var phoneServiceBinder: IBinder? = null
    private var isServiceBound = false

    // 通话计时器 (用于接通状态下的动态时长走时)
    private var activeCallStartElapsedMs = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (currentCallInfo.state == CallState.ACTIVE) {
                val durationSec = ((SystemClock.elapsedRealtime() - activeCallStartElapsedMs) / 1000L).coerceAtLeast(0L)
                if (durationSec != currentCallInfo.durationSeconds) {
                    currentCallInfo = currentCallInfo.copy(durationSeconds = durationSec)
                    notifyListeners(currentCallInfo)
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    // 挂断退场定时器 (挂断后展示“通话结束”2.5秒后平滑回退至 IDLE)
    private val terminateDismissRunnable = Runnable {
        if (currentCallInfo.state == CallState.TERMINATED) {
            AppLogger.i(TAG, "🏁 挂断展示倒计时结束，通话状态平滑重置为 IDLE")
            updateCallState(CallState.IDLE, "", "")
        }
    }

    private val replyCallbackStub = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(DESCRIPTOR_REPLY)
                return true
            }
            try {
                when (code) {
                    REPLY_TRANSACTION_ON_BOTH_CONTACT_CALL -> {
                        data.enforceInterface(DESCRIPTOR_REPLY)
                        val combination = data.readInt()
                        val hasContact1 = data.readInt() != 0
                        val contact1 = if (hasContact1) readCallContact(data) else null
                        val hasContact2 = data.readInt() != 0
                        val contact2 = if (hasContact2) readCallContact(data) else null
                        AppLogger.i(TAG, "📞 收到 onBothContactCall: comb=$combination, c1=$contact1, c2=$contact2")
                        val activeContact = contact1 ?: contact2
                        if (activeContact != null) {
                            handleRawContact(activeContact)
                        }
                        return true
                    }
                    REPLY_TRANSACTION_ON_BOTH_CALL_TERMINATED -> {
                        data.enforceInterface(DESCRIPTOR_REPLY)
                        val hasContact1 = data.readInt() != 0
                        val contact1 = if (hasContact1) readCallContact(data) else null
                        val hasContact2 = data.readInt() != 0
                        val contact2 = if (hasContact2) readCallContact(data) else null
                        AppLogger.i(TAG, "📴 收到 onBothCallTerminated: c1=$contact1, c2=$contact2")
                        val lastContact = contact1 ?: contact2
                        handleRawTerminated(lastContact?.name ?: currentCallInfo.name, lastContact?.number ?: currentCallInfo.number)
                        return true
                    }
                    REPLY_TRANSACTION_ON_CONTACT_CALLING_THIRD_TERMINATED -> {
                        data.enforceInterface(DESCRIPTOR_REPLY)
                        val hasContact1 = data.readInt() != 0
                        val contact1 = if (hasContact1) readCallContact(data) else null
                        if (contact1 != null) {
                            handleRawContact(contact1)
                        }
                        return true
                    }
                    REPLY_TRANSACTION_ON_THIRD_CALLING_CONTACT_TERMINATED -> {
                        data.enforceInterface(DESCRIPTOR_REPLY)
                        val hasContact2 = data.readInt() != 0
                        val contact2 = if (hasContact2) readCallContact(data) else null
                        if (contact2 != null) {
                            handleRawContact(contact2)
                        }
                        return true
                    }
                }
            } catch (t: Throwable) {
                AppLogger.e(TAG, "解析车机电话 AIDL 回调异常: ${t.message}", t)
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        AppLogger.w(TAG, "⚠️ 原厂蓝牙电话服务 Binder 异常死亡 (DeathRecipient 触发)")
        phoneServiceBinder = null
        isServiceBound = false
        mainHandler.postDelayed({
            if (listeners.isNotEmpty()) {
                AppLogger.i(TAG, "🔄 触发原厂蓝牙电话服务自动重连...")
                workerExecutor.execute {
                    bindBluetoothPhoneService()
                }
            }
        }, 3000L)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            AppLogger.i(TAG, "🚗 成功连通原厂蓝牙电话服务: $name")
            phoneServiceBinder = service
            isServiceBound = true
            try {
                service?.linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                AppLogger.w(TAG, "linkToDeath 异常: ${e.message}")
            }
            // 调度至后台工作线程执行 IPC 查询与远程注册，杜绝主线程阻塞 ANR
            workerExecutor.execute {
                registerRemoteReply()
                queryActiveCalls()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            AppLogger.w(TAG, "⚠️ 原厂蓝牙电话服务断开连接: $name")
            try {
                phoneServiceBinder?.unlinkToDeath(deathRecipient, 0)
            } catch (_: Exception) {}
            phoneServiceBinder = null
            isServiceBound = false
        }
    }

    init {
        // 彻底停用标准 TelephonyManager 监听，杜绝车机无 SIM 卡虚拟基带误报 OFFHOOK 导致的常驻“未知通话”
    }

    fun startListening() {
        workerExecutor.execute {
            bindBluetoothPhoneService()
        }
    }

    fun stopListening() {
        workerExecutor.execute {
            if (isServiceBound) {
                try {
                    phoneServiceBinder?.unlinkToDeath(deathRecipient, 0)
                    unregisterRemoteReply()
                    context.unbindService(serviceConnection)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "解绑车机原生蓝牙电话服务异常: ${e.message}")
                } finally {
                    isServiceBound = false
                    phoneServiceBinder = null
                }
            }
        }
    }

    private fun unregisterRemoteReply() {
        val binder = phoneServiceBinder ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_MANAGER)
            // 原厂 AIDL unregisterReply(String key) 接受调用者 key
            data.writeString(context.packageName)
            binder.transact(TRANSACTION_UNREGISTER_REPLY, data, null, IBinder.FLAG_ONEWAY)
            AppLogger.i(TAG, "注销车机蓝牙电话远程 Reply 回调成功 (key=${context.packageName})")
        } catch (e: Exception) {
            AppLogger.w(TAG, "注销车机电话 Reply 异常: ${e.message}")
        } finally {
            data.recycle()
        }
    }

    private fun bindBluetoothPhoneService() {
        try {
            val explicitIntent = Intent().apply {
                component = ComponentName(BT_PHONE_PKG, BT_PHONE_CLS)
                action = BT_PHONE_ACTION
            }
            var ok = context.bindService(explicitIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!ok) {
                val implicitIntent = Intent(BT_PHONE_ACTION).apply {
                    `package` = BT_PHONE_PKG
                }
                ok = context.bindService(implicitIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
            AppLogger.i(TAG, "尝试绑定车机原生蓝牙电话服务 (com.chinatsp.phone): ok=$ok")
        } catch (e: Exception) {
            AppLogger.w(TAG, "绑定车机原生蓝牙电话服务失败 (非车机或被拦截): ${e.message}")
        }
    }

    private fun registerRemoteReply() {
        val binder = phoneServiceBinder ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_MANAGER)
            // 原厂 AIDL registerReply(String key, IBluetoothPhoneReply callback)
            // 第一个参数是 key (String)，第二个参数是回调 Binder
            data.writeString(context.packageName)
            data.writeStrongBinder(replyCallbackStub)
            // 根据规范：FLAG_ONEWAY 且 reply 为 null
            binder.transact(TRANSACTION_REGISTER_REPLY, data, null, IBinder.FLAG_ONEWAY)
            AppLogger.i(TAG, "✅ 注册车机蓝牙电话远程 Reply 回调成功 (key=${context.packageName}, FLAG_ONEWAY)")
        } catch (e: Exception) {
            AppLogger.e(TAG, "注册车机电话 Reply 异常: ${e.message}", e)
        } finally {
            data.recycle()
        }
    }

    private fun queryActiveCalls() {
        val binder = phoneServiceBinder ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR_MANAGER)
            val ok = binder.transact(TRANSACTION_GET_CALLS, data, reply, 0)
            if (ok) {
                reply.readException()
                // 读取现存活动通话列表 (createTypedArrayList 结构: size, 然后每个元素为 hasItem(Int) + CallContact)
                val size = reply.readInt()
                AppLogger.i(TAG, "主动查询现存活动通话: 数量=$size")
                for (i in 0 until size) {
                    val hasItem = reply.readInt() != 0
                    if (hasItem) {
                        val contact = readCallContact(reply)
                        AppLogger.i(TAG, "现存通话项[$i]: $contact")
                        if (i == 0) {
                            handleRawContact(contact)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "查询现存活动通话失败: ${e.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * 按照 smali 逐字节读取 CallContact Parcel
     * writeToParcel 字节序:
     * id(Int), name(String), number(String), state(Int), creationElapsedMilli(Long),
     * isMultiParty(Byte), isOutgoing(Byte), isInBandRing(Byte)
     */
    private fun readCallContact(parcel: Parcel): RawContact {
        val id = parcel.readInt()
        val name = parcel.readString() ?: ""
        val number = parcel.readString() ?: ""
        val state = parcel.readInt()
        val creationElapsedMilli = parcel.readLong()
        val isMultiParty = parcel.readByte().toInt() != 0
        val isOutgoing = parcel.readByte().toInt() != 0
        val isInBandRing = parcel.readByte().toInt() != 0
        return RawContact(id, name, number, state, creationElapsedMilli, isMultiParty, isOutgoing, isInBandRing)
    }

    private fun handleRawContact(raw: RawContact) {
        mainHandler.post {
            val mappedState = when {
                // 1. 明确的挂断态
                raw.state == RAW_STATE_TERMINATED -> CallState.TERMINATED

                // 2. 明确的已接通/保持态
                raw.state == RAW_STATE_ACTIVE ||
                raw.state == RAW_STATE_HELD_CALL ||
                raw.state == RAW_STATE_HELD_BY_HOLD -> CallState.ACTIVE

                // 3. 呼出方向 (无论是拨号 RAW_STATE_DIALING 还是对方振铃 RAW_STATE_RINGING，只要是呼出，均为 DIALING)
                raw.isOutgoing -> CallState.DIALING

                // 4. 呼入方向 (来电振铃、来电中、呼叫等待)
                raw.state == RAW_STATE_RINGING ||
                raw.state == RAW_STATE_INCOMING ||
                raw.state == RAW_STATE_WAITING -> CallState.RINGING

                // 5. 其他非零状态兜底保护
                raw.state == RAW_STATE_DIALING -> CallState.DIALING

                else -> CallState.IDLE
            }
            AppLogger.d(TAG, "📞 解析车机原生通话: state=${raw.state}, isOutgoing=${raw.isOutgoing} -> mappedState=$mappedState")
            updateCallState(mappedState, raw.name, raw.number)
        }
    }

    private fun handleRawTerminated(name: String, number: String) {
        mainHandler.post {
            updateCallState(CallState.TERMINATED, name, number)
        }
    }

    /**
     * 核心状态机推进逻辑
     */
    fun updateCallState(state: CallState, rawName: String, rawNumber: String) {
        val cleanName = cleanContactName(rawName)

        // 防虚假未知通话保护：若姓名和号码全部为空白，且非 IDLE，判定为底层总线未就绪的空占位，忽略该事件
        if (state != CallState.IDLE && cleanName.isBlank() && rawNumber.isBlank()) {
            AppLogger.d(TAG, "忽略无联系人信息的虚假通话状态: $state")
            return
        }

        val type = detectCallType(rawName, rawNumber)
        val previousState = currentCallInfo.state

        if (state == CallState.ACTIVE) {
            if (previousState != CallState.ACTIVE) {
                // 首次切入接通：重置计时器
                activeCallStartElapsedMs = SystemClock.elapsedRealtime()
                mainHandler.removeCallbacks(timerRunnable)
                mainHandler.post(timerRunnable)
            }
        } else {
            mainHandler.removeCallbacks(timerRunnable)
        }

        if (state == CallState.TERMINATED) {
            mainHandler.removeCallbacks(terminateDismissRunnable)
            // 挂断保留 2.5 秒后回退至 IDLE
            mainHandler.postDelayed(terminateDismissRunnable, 2500L)
        } else {
            mainHandler.removeCallbacks(terminateDismissRunnable)
        }

        val duration = if (state == CallState.ACTIVE) {
            ((SystemClock.elapsedRealtime() - activeCallStartElapsedMs) / 1000L).coerceAtLeast(0L)
        } else {
            0L
        }

        currentCallInfo = CallInfo(
            type = type,
            state = state,
            name = cleanName,
            number = rawNumber,
            durationSeconds = duration
        )

        AppLogger.i(TAG, "📢 通话状态演变: state=$state, type=$type, name=${PrivacyUtil.maskName(cleanName)}, number=${PrivacyUtil.maskPhone(rawNumber)}, dur=${duration}s")
        notifyListeners(currentCallInfo)
    }



    /**
     * 模拟测试接口 (支持 Pad 预览与 ADB 调试)
     */
    fun simulateCall(type: CallType, state: CallState, name: String, number: String) {
        val finalName = if (type == CallType.WECHAT && !name.contains("微信")) {
            "微信语音: $name"
        } else {
            name
        }
        updateCallState(state, finalName, number)
    }

    fun getCurrentCallInfo(): CallInfo = currentCallInfo

    fun addListener(listener: CallListener) {
        if (!listeners.contains(listener)) {
            val wasEmpty = listeners.isEmpty()
            listeners.add(listener)
            if (wasEmpty) {
                startListening()
            }
            mainHandler.post {
                try {
                    listener.onCallChanged(currentCallInfo)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "CallListener 执行异常", t)
                }
            }
        }
    }

    fun removeListener(listener: CallListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            stopListening()
        }
    }

    private fun notifyListeners(info: CallInfo) {
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onCallChanged(info)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "CallListener 执行异常", t)
                }
            }
        }
    }

    private data class RawContact(
        val id: Int,
        val name: String,
        val number: String,
        val state: Int,
        val creationElapsedMilli: Long,
        val isMultiParty: Boolean,
        val isOutgoing: Boolean,
        val isInBandRing: Boolean
    ) {
        override fun toString(): String {
            return "RawContact(id=$id, name=${PrivacyUtil.maskName(name)}, number=${PrivacyUtil.maskPhone(number)}, state=$state, outgoing=$isOutgoing)"
        }
    }
}
