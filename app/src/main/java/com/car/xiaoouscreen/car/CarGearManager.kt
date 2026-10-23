package com.car.xiaoouscreen.car

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 长安欧尚 Z6 iDD / B561 智能座舱档位信号接入管理器
 *
 * 遵循 GEAR_DRIVE_MODE_DEV.md 与《车辆信息读取指南.md》规范：
 * 1. 采用全量 Java 反射与动态代理，避免编译期硬依赖私有 android.car.jar
 * 2. 核心属性白名单：
 *    - 0x2140210f (CarCabinManager.ID_GEAR_SELECTION_NEW = 557850895，首选推荐)
 *    - 0x11400400 (CarMcuManager.ID_GEAR_SELECTION = 289408000，AOSP底层备用)
 * 3. 欧尚标准档位 Bit 位白名单：P=4, R=2, N=1, D=8，杜绝总线过渡噪声跳变
 * 4. 握手连通后主动通过 getIntProperty(0x2140210f, 0) 读取初始档位
 * 5. 非车机环境 (如 Android Pad / 模拟器) 平滑降级，提供广播与方法模拟
 */
class CarGearManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CarGearManager"

        // 欧尚车机座舱与 MCU 档位属性 ID (来自 _sdk/car_property_ids.tsv)
        const val ID_GEAR_SELECTION_NEW = 0x2140210f // 557850895 (CarCabinManager)
        const val ID_GEAR_SELECTION = 0x11400400     // 289408000 (CarMcuManager / Sensor)

        // 驾驶模式属性 ID (来自 GEAR_DRIVE_MODE_DEV.md 与 CarCabinManager)
        const val ID_VENDOR_DRIVER_MODE_SET = 0x2140f030 // 557903920
        const val ID_DRIVING_MODE_ALL = 0x2141f08e       // 557969550

        // 驾驶模式常量
        const val MODE_ECO = "ECO"
        const val MODE_NORMAL = "NORMAL"
        const val MODE_SPORT = "SPORT"
        const val MODE_RACE = "RACE"
        const val MODE_CUSTOM = "CUSTOM"

        // 欧尚档位原始有效值
        const val RAW_GEAR_N = 1
        const val RAW_GEAR_R = 2
        const val RAW_GEAR_P = 4
        const val RAW_GEAR_D = 8

        @Volatile
        private var instance: CarGearManager? = null

        fun getInstance(context: Context): CarGearManager {
            return instance ?: synchronized(this) {
                instance ?: CarGearManager(context.applicationContext).also { instance = it }
            }
        }

        fun isValidGear(rawGear: Int): Boolean {
            return rawGear == RAW_GEAR_P || rawGear == RAW_GEAR_R || 
                   rawGear == RAW_GEAR_N || rawGear == RAW_GEAR_D
        }

        fun toGearLabel(rawGear: Int): String {
            return when (rawGear) {
                RAW_GEAR_P -> "P"
                RAW_GEAR_R -> "R"
                RAW_GEAR_N -> "N"
                RAW_GEAR_D -> "D"
                else -> "-"
            }
        }

        fun toDriveMode(rawMode: Int): String {
            return when (rawMode) {
                2 -> MODE_ECO
                0, 1 -> MODE_NORMAL
                3 -> MODE_SPORT
                5 -> MODE_RACE
                else -> MODE_CUSTOM // 4(雪地) 及其他扩展模式
            }
        }
    }

    interface GearListener {
        fun onGearChanged(gear: String, rawValue: Int)
    }

    interface DriveModeListener {
        fun onDriveModeChanged(mode: String, rawValue: Int)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<GearListener>()
    private val driveModeListeners = mutableListOf<DriveModeListener>()

    private var carInstance: Any? = null
    private var cabinManager: Any? = null
    private var mcuManager: Any? = null
    private var isCarSupported = false
    private var isConnected = false
    private var isInitialized = false

    private var currentGearLabel = "P"
    private var currentRawGear = RAW_GEAR_P

    private var currentDriveMode = MODE_SPORT
    private var currentRawDriveMode = 3

    fun registerListener(listener: GearListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            // 立即回传当前最新档位
            listener.onGearChanged(currentGearLabel, currentRawGear)
        }
    }

    fun unregisterListener(listener: GearListener) {
        listeners.remove(listener)
    }

    fun registerDriveModeListener(listener: DriveModeListener) {
        if (!driveModeListeners.contains(listener)) {
            driveModeListeners.add(listener)
            listener.onDriveModeChanged(currentDriveMode, currentRawDriveMode)
        }
    }

    fun unregisterDriveModeListener(listener: DriveModeListener) {
        driveModeListeners.remove(listener)
    }

    fun getCurrentGear(): String = currentGearLabel
    fun getCurrentRawGear(): Int = currentRawGear
    fun getCurrentDriveMode(): String = currentDriveMode
    fun getCurrentRawDriveMode(): Int = currentRawDriveMode
    fun isConnectedToCar(): Boolean = isConnected

    /**
     * 初始化连接车机 CarService (异步握手)
     */
    @Synchronized
    fun init() {
        if (isInitialized) {
            Log.d(TAG, "CarGearManager 已经初始化过，当前已连接=$isConnected")
            return
        }
        isInitialized = true

        try {
            val carClass = Class.forName("android.car.Car")
            isCarSupported = true
            Log.i(TAG, "🚗 检测到系统包含 android.car.Car，开始与 CarService 进行 IPC 握手...")

            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    Log.i(TAG, "✅ CarService 服务连通: $name")
                    isConnected = true
                    initManagers(carClass, carInstance)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(TAG, "⚠️ CarService 服务断开: $name")
                    isConnected = false
                    cabinManager = null
                    mcuManager = null
                }
            }

            // Method: Car.createCar(Context, ServiceConnection)
            val createCarMethod = carClass.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
            carInstance = createCarMethod.invoke(null, context, serviceConnection)

            // Android 9 Automotive 必须显式调用 connect()
            val connectMethod = carClass.getMethod("connect")
            connectMethod.invoke(carInstance)
            Log.i(TAG, "已调用 car.connect()，等待 onServiceConnected 回调...")

        } catch (e: ClassNotFoundException) {
            isCarSupported = false
            isConnected = false
            Log.w(TAG, "当前设备非 Android Automotive 车机系统 (缺少 android.car.Car)，无缝启用本地模拟驱动")
        } catch (t: Throwable) {
            Log.e(TAG, "初始化 CarService 握手异常: ${t.message}", t)
        }
    }

    /**
     * 获取 CarCabinManager 与 CarMcuManager 并动态代理注册 Callback
     */
    private fun initManagers(carClass: Class<*>, car: Any?) {
        if (car == null) return
        try {
            val getCarManagerMethod = carClass.getMethod("getCarManager", String::class.java)

            // 1. 获取 CarCabinManager (服务名: "cabin")
            cabinManager = getCarManagerMethod.invoke(car, "cabin")
            if (cabinManager != null) {
                Log.i(TAG, "成功获取 CarCabinManager 实例")
                registerCabinCallback(cabinManager!!)
                // 连通时主动读取一次当前实车档位与驾驶模式
                readInitialGear(cabinManager!!)
                readInitialDriveMode(cabinManager!!)
            } else {
                Log.w(TAG, "未能获取到 CarCabinManager (cabin)")
            }

            // 2. 获取备用 CarMcuManager (服务名: "mcu_service")
            try {
                mcuManager = getCarManagerMethod.invoke(car, "mcu_service")
                if (mcuManager != null) {
                    Log.i(TAG, "成功获取 CarMcuManager 备用实例")
                    registerMcuCallback(mcuManager!!)
                }
            } catch (e: Exception) {
                Log.w(TAG, "未启用/未找到 mcu_service: ${e.message}")
            }

        } catch (t: Throwable) {
            Log.e(TAG, "initManagers 异常", t)
        }
    }

    /**
     * 主动读取一次实车当前档位
     */
    private fun readInitialGear(cabin: Any) {
        try {
            // int getIntProperty(int propertyId, int area)
            val getIntMethod = cabin.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val raw = getIntMethod.invoke(cabin, ID_GEAR_SELECTION_NEW, 0) as Int
            if (isValidGear(raw)) {
                val label = toGearLabel(raw)
                Log.i(TAG, "🎉 实车初始档位主动查询成功: raw=$raw -> $label 档")
                notifyGearInternal(label, raw)
            } else {
                Log.d(TAG, "实车初始档位非白名单值: raw=$raw (有效值: 1=N, 2=R, 4=P, 8=D)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "主动读取初始档位异常: ${t.message}")
        }
    }

    /**
     * 主动读取一次实车当前驾驶模式
     */
    private fun readInitialDriveMode(cabin: Any) {
        try {
            val getIntMethod = cabin.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val raw = getIntMethod.invoke(cabin, ID_VENDOR_DRIVER_MODE_SET, 0) as Int
            val mode = toDriveMode(raw)
            Log.i(TAG, "🎉 实车初始驾驶模式主动查询成功: raw=$raw -> $mode")
            notifyDriveModeInternal(mode, raw)
        } catch (t: Throwable) {
            Log.w(TAG, "主动读取初始驾驶模式异常: ${t.message}")
        }
    }

    /**
     * 注册 CarCabinManager 动态代理 Callback
     */
    private fun registerCabinCallback(cabin: Any) {
        try {
            val classLoader = cabin.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.cabin.CarCabinManager\$CarCabinEventCallback", true, classLoader)

            val callbackProxy = Proxy.newProxyInstance(
                classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty()) {
                            val event = args[0] // CarPropertyValue
                            handleCarPropertyValue(event)
                        }
                        null
                    }
                    "onErrorEvent" -> {
                        Log.w(TAG, "CarCabinManager.onErrorEvent: ${args?.contentToString()}")
                        null
                    }
                    "equals" -> false
                    "hashCode" -> 31
                    "toString" -> "CarCabinEventCallbackProxy"
                    else -> null
                }
            }

            // Method: registerCallback(CarCabinEventCallback, int[])
            val registerMethod = cabin.javaClass.getMethod(
                "registerCallback",
                callbackClass,
                IntArray::class.java
            )
            registerMethod.invoke(cabin, callbackProxy, intArrayOf(
                ID_GEAR_SELECTION_NEW, 
                ID_GEAR_SELECTION,
                ID_VENDOR_DRIVER_MODE_SET,
                ID_DRIVING_MODE_ALL
            ))
            Log.i(TAG, "🔥 成功向 CarCabinManager 订阅档位与驾驶模式属性 (0x2140210f / 0x11400400 / 0x2140f030 / 0x2141f08e)")

        } catch (t: Throwable) {
            Log.e(TAG, "注册 CarCabinManager 监听异常", t)
        }
    }

    /**
     * 注册 CarMcuManager 备用监听
     */
    private fun registerMcuCallback(mcu: Any) {
        try {
            val classLoader = mcu.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.mcu.CarMcuManager\$CarMcuEventCallback", true, classLoader)

            val callbackProxy = Proxy.newProxyInstance(
                classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                if ("onChangeEvent" == method.name && args != null && args.isNotEmpty()) {
                    val event = args[0]
                    handleCarPropertyValue(event)
                }
                null
            }

            val registerMethod = mcu.javaClass.getMethod(
                "registerCallback",
                callbackClass,
                IntArray::class.java
            )
            registerMethod.invoke(mcu, callbackProxy, intArrayOf(ID_GEAR_SELECTION))
            Log.i(TAG, "成功向 CarMcuManager 订阅备用档位属性 (0x11400400)")

        } catch (t: Throwable) {
            Log.w(TAG, "注册 CarMcuManager 监听失败: ${t.message}")
        }
    }

    /**
     * 解析 CarPropertyValue 并执行白名单校验
     */
    private fun handleCarPropertyValue(event: Any) {
        try {
            val propId = event.javaClass.getMethod("getPropertyId").invoke(event) as Int
            if (propId == ID_GEAR_SELECTION_NEW || propId == ID_GEAR_SELECTION) {
                val rawValue = event.javaClass.getMethod("getValue").invoke(event) as Int
                if (isValidGear(rawValue)) {
                    val label = toGearLabel(rawValue)
                    Log.i(TAG, "🚘 实车档位总线变动触发: prop=0x${Integer.toHexString(propId)}, raw=$rawValue -> $label")
                    notifyGearInternal(label, rawValue)
                } else {
                    Log.d(TAG, "忽略非标准档位过渡噪声: raw=$rawValue")
                }
            } else if (propId == ID_VENDOR_DRIVER_MODE_SET || propId == ID_DRIVING_MODE_ALL) {
                val rawValue = event.javaClass.getMethod("getValue").invoke(event) as Int
                val mode = toDriveMode(rawValue)
                Log.i(TAG, "🏎️ 实车驾驶模式总线变动触发: prop=0x${Integer.toHexString(propId)}, raw=$rawValue -> $mode")
                notifyDriveModeInternal(mode, rawValue)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析 CarPropertyValue 异常", e)
        }
    }

    /**
     * 模拟档位输入 (供控制台测试、广播测试使用)
     */
    fun simulateGear(gearLabel: String) {
        val label = gearLabel.uppercase().trim()
        val raw = when (label) {
            "P" -> RAW_GEAR_P
            "R" -> RAW_GEAR_R
            "N" -> RAW_GEAR_N
            "D" -> RAW_GEAR_D
            else -> return
        }
        Log.i(TAG, "🧪 模拟切档触发: $label (raw=$raw)")
        notifyGearInternal(label, raw)
    }

    fun simulateRawGear(rawGear: Int) {
        if (isValidGear(rawGear)) {
            val label = toGearLabel(rawGear)
            Log.i(TAG, "🧪 模拟 Raw 档位触发: raw=$rawGear -> $label")
            notifyGearInternal(label, rawGear)
        }
    }

    /**
     * 模拟车辆驾驶模式输入 (供控制台测试、广播测试使用)
     */
    fun simulateDriveMode(modeStr: String) {
        val mode = when (modeStr.uppercase().trim()) {
            "ECO" -> MODE_ECO
            "NORMAL", "COMFORT" -> MODE_NORMAL
            "SPORT" -> MODE_SPORT
            "RACE" -> MODE_RACE
            else -> MODE_CUSTOM
        }
        val raw = when (mode) {
            MODE_ECO -> 2
            MODE_NORMAL -> 1
            MODE_SPORT -> 3
            MODE_RACE -> 5
            else -> 4
        }
        Log.i(TAG, "🧪 模拟驾驶模式触发: $mode (raw=$raw)")
        notifyDriveModeInternal(mode, raw)
    }

    fun simulateRawDriveMode(rawMode: Int) {
        val mode = toDriveMode(rawMode)
        Log.i(TAG, "🧪 模拟 Raw 驾驶模式触发: raw=$rawMode -> $mode")
        notifyDriveModeInternal(mode, rawMode)
    }

    private fun notifyGearInternal(label: String, raw: Int) {
        currentGearLabel = label
        currentRawGear = raw
        mainHandler.post {
            for (listener in listeners) {
                listener.onGearChanged(label, raw)
            }
        }
    }

    private fun notifyDriveModeInternal(mode: String, raw: Int) {
        currentDriveMode = mode
        currentRawDriveMode = raw
        mainHandler.post {
            for (listener in driveModeListeners) {
                listener.onDriveModeChanged(mode, raw)
            }
        }
    }
}
