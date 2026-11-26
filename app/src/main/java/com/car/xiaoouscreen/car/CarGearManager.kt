package com.car.xiaoouscreen.car

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.carlyrics.util.AppLogger
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

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

        // 实车车外环境温度属性 ID (AOSP 标准属性，CarSensorManager / CarHvacManager 共享，Float ℃，normal 权限)
        const val SENSOR_TYPE_ENV_OUTSIDE_TEMPERATURE = 0x11600703 // 291505923

        // 实车座舱内温度属性 ID (依据《车辆信息读取指南.md》§4.7 与原厂 B561_SystemUI / B561_Airconditioner 逆向)
        // 核心原厂属性 (仅需 CAR_VENDOR_EXTENSION，实车 100% 畅通)：
        const val ID_HVAC_TEMPERATURE_DRIVER = 0x2540f3b6 // 625013686 (CarHvacManager, Int ℃，SystemUI 顶栏 AirWidget 与空调 App 正在使用的主驾/座舱温度，16..35)
        const val ID_HVAC_TEMPERATURE_SET = 0x25400503    // 624952579 (CarHvacManager / CarCabinManager, Float/Int ℃，设定空调温控)
        // AOSP 与总线备选属性：
        const val ID_ZONED_TEMP_ACTUAL = 0x15600502        // 358614274 (CarHvacManager, Float ℃，AOSP 标准实测座舱温，需 CONTROL_CAR_CLIMATE 权限)
        const val ID_HVAC_AC_INTERNALTEMP = 0x2140f1cc    // 557904332 (CarCabinManager, Int ℃，FOTA 实车在用)
        const val ID_INSIDE_AIR_TEMP = 0x21603017         // 559951895 (CarCabinManager, Float ℃，备选)

        // 座舱内温度合理物理区间 (过滤 CAN 未就绪 / -40℃ / 255 / NaN 占位值)
        private const val MIN_VALID_CABIN_TEMP_C = -39.5f
        private const val MAX_VALID_CABIN_TEMP_C = 75.0f

        // 12V 低压蓄电池属性 ID (依据《车辆信息读取指南.md》§4.6 与 FOTA / 桌面逆向)
        const val ID_MCU_REPORT_AIO_BAT_ADC = 0x2160f51f      // 560002335 (CarCabinManager, Float V，PHEV 插电混动专有 12V 蓄电池电压)
        const val ID_BODY_BATTERY_VOLTAGE_EMS = 0x21400a84    // 557845124 (CarCabinManager, Int mV/0.1V，EMS 发动机端 12V 电压备选)
        const val ID_BODY_BATTERY_U_BATT_INFO = 0x21600a82    // 559942274 (CarMcuManager / CarDYCabinManager, Float V)
        const val ID_BCM_BATTERY_U = 0x2160f16f               // 560001391 (CarMcuManager, Float V 备选)
        const val ID_BCM_SOH_LIFE_PERCENT = 0x2140f10c        // 557904140 (CarCabinManager, Int 1..100% 蓄电池健康度 SOH)
        const val ID_BODY_BATTERY_REMAINING_INFO = 0x21400a81 // 557845121 (CarMcuManager, Int 0..100% 剩余电量 SOC)
        const val ID_BODY_BATTERY_SOC_LOW = 0x21403100        // 557854976 (CarCabinManager, Int 0正常/1亏电告警)

        // 12V 蓄电池电压合理物理区间 (过滤 CAN 未就绪 / 0V / 0xFF 占位值)
        private const val MIN_VALID_BATTERY_VOLTAGE_V = 6.0f
        private const val MAX_VALID_BATTERY_VOLTAGE_V = 18.5f

        // 底盘 ESP 车身姿态与横/纵向加速度属性 ID (逆向核验自 CarMcuManager / TrackMode，Float m/s^2)
        const val ID_ESP_LONG_ACCELERATION = 0x2160f17f       // 560001407 (CarMcuManager, Float m/s^2，纵向加速度)
        const val ID_ESP_LAT_ACCELERATION = 0x2160f1a9        // 560001449 (CarMcuManager, Float m/s^2，横向加速度)

        // 新能源高压动力电池与电机功率属性 ID (依据《车辆信息读取指南.md》§4.10，B561_NewEnergy / TrackMode 实车在用)
        const val ID_BMS_BATTI = 0x2160f502          // 560002306 (CarCabinManager, Float A，双向带符号电流)
        const val ID_BMS_BATTU = 0x2160f503          // 560002307 (CarCabinManager, Float V，高压母线电压)
        const val ID_PCU_SYSEGY_FLOW = 0x2140f46a    // 557905002 (CarCabinManager, Int，整车能量流状态枚举)
        const val ID_PCU_IPDRVEGY_IND = 0x2140f4ad   // 557905069 (CarCabinManager, Int，0..100 驱动能量指示)
        const val ID_PCU_SOC_DSP = 0x2140f46b        // 557905003 (CarCabinManager, Int，动力电池表显电量 %)
        private const val VEHICLE_AREA_GLOBAL = 0x01000000
        private const val HV_POWER_SIGN_DIR = 1f     // 放电输出为 +kW，动能回收为 -kW
        private const val MIN_VALID_HV_VOLTAGE_V = 50.0f
        private const val MAX_VALID_HV_VOLTAGE_V = 900.0f
        private const val MAX_VALID_HV_CURRENT_A = 650.0f

        // 发动机转速与混动电驱转速属性 ID (逆向核验自 B561_Dashboard / CarService.apk)
        const val ID_ENGINE_RPM_SPEED = 0x2160f315      // 560001813 (CarCabinManager, Float RPM，原厂仪表在用)
        const val ID_ENGINE_RPM = 0x11600305            // 291504901 (CarCabinManager / CarSensorManager, Float RPM，AOSP 标准备选)
        const val ID_TCU_INPUTSHAFTSPEED1 = 0x2160f47d  // 560002173 (CarCabinManager, Float RPM，蓝鲸三离合电驱输入轴1)
        const val ID_TCU_INPUTSHAFTSPEED2 = 0x2160f47e  // 560002174 (CarCabinManager, Float RPM，蓝鲸三离合电驱输入轴2)
        const val ID_PCU_EVMODSTS = 0x2140f51d          // 557905181 (CarCabinManager, Int，新能源混动模式状态: 1=EV, >=2=HEV)
        private const val MAX_VALID_ENGINE_RPM = 10_000f
        private const val MAX_VALID_MOTOR_RPM = 18_000f

        // 变道辅助与盲区预警属性 ID (依据《车辆信息读取指南.md》§4.11 与原厂 EaglePilot 逆向)
        const val ID_ADAS_BSD_LCA_ALERT = 0x21412146         // 557916486 (CarCabinManager, int[2]: [left, right])
        const val ID_ADAS_APA_BSD_LCA_ON = 0x214012c2        // 557847234 (CarCabinManager, Int: 0关/1开)
        const val ID_ADAS_ACC_PARALLEL_LINE_ASSIST_MODE = 0x21401325 // 557847333 (CarCabinManager, Int: 1灵敏/2标准/3延迟/4关)
        const val ID_LCDA_SIGNAL = 0x2141f228                // 557969960 (CarCabinManager, int[2]: [left, right])
        const val ID_LCDAL_BSD_LCA_STATUS = 0x2140f364       // 557904740 (CarCabinManager, Int)
        const val ID_LCDAR_BSD_LCA_STATUS = 0x2140f365       // 557904741 (CarCabinManager, Int)
        const val ID_LCDAL_RLTARGETDETECTION = 0x2140030a     // 557843210 (CarCabinManager, Int: 0无/1左后方有车)
        const val ID_LCDAL_RRTARGETDETECTION = 0x21400310     // 557843216 (CarCabinManager, Int: 0无/1右后方有车)

        // 转向灯与双闪属性 ID (依据《车辆信息读取指南.md》§4.12 与原厂 SmartLights / EaglePilot 逆向)
        const val ID_BODY_TURN_LEFT_SIGNAL_STATE = 0x21400a35   // 557845045 (CarCabinManager, Int: 0灭/1亮)
        const val ID_BODY_TURN_RIGHT_SIGNAL_STATE = 0x21400a37  // 557845047 (CarCabinManager, Int: 0灭/1亮)
        const val ID_BODY_EMERGENCY_LIGHT = 0x21400a39          // 557845049 (CarCabinManager, Int: 0关/1开 双闪)
        const val ID_TURNLIGHT_ACTIVE_AVM = 0x21402133          // 557850931 (CarCabinManager, Int: 0无/1右/2左)
        const val ID_BODY_LIGHT_LANE_CHANGE_TURN_STATE = 0x21400a2d // 557845037 (CarCabinManager, Int: 1关/2闪3次/3闪5次/4闪7次)

        // 车外温度合理物理区间 (过滤 CAN 未就绪/传感器故障常见的 -40℃ / 87.5℃ / NaN 占位值)
        private const val MIN_VALID_OUTSIDE_TEMP_C = -39.5f
        private const val MAX_VALID_OUTSIDE_TEMP_C = 65.0f

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

        // 车规级过路档 (Transit Gear) 防抖时间窗口：250ms
        // 揉库 (D <-> R) 或快速 P -> D 切档时，总线瞬态经过 N 档在此窗口内被暂扣，不打扰全屏 3D 翻转
        const val TRANSIT_N_DEBOUNCE_MS = 250L

        // 转向意图信号保活窗口 (车规级 850ms，完全覆盖实车 760ms 物理闪光继电器的 380ms 熄灭盲区，防抖平滑)
        const val TURN_SIGNAL_INTENT_HOLD_MS = 850L

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

    data class BatteryInfo(
        val voltageV: Float? = null,
        val sohPercent: Int? = null,
        val socPercent: Int? = null,
        val isLowWarning: Boolean = false
    )

    data class MotorPowerInfo(
        val powerKw: Float? = null,
        val voltageV: Float? = null,
        val currentA: Float? = null,
        val energyFlowState: Int? = null,
        val driveEnergyInd: Int? = null,
        val hvSocPercent: Int? = null,
        val peakOutputKw: Float = 0f,
        val peakRegenKw: Float = 0f,
        val isPlugCharging: Boolean = false
    )

    enum class PowertrainMode {
        PURE_ICE,   // 纯燃油车（无 EV / 混动系统，单显发动机转速）
        EV,         // 混动纯电工况 (evModSts == 1，单显电机转速)
        HEV         // 混动油电工况 (evModSts >= 2，双显发动机+电机转速)
    }

    data class RpmInfo(
        val engineRpm: Int? = null,
        val motorRpm: Int? = null,
        val enginePeakRpm: Int = 0,
        val motorPeakRpm: Int = 0,
        val powertrainMode: PowertrainMode = PowertrainMode.PURE_ICE
    )

    interface OutsideTempListener {
        fun onOutsideTempChanged(tempCelsius: Float)
    }

    interface CabinTempListener {
        fun onCabinTempChanged(tempCelsius: Float)
    }

    interface BatteryInfoListener {
        fun onBatteryInfoChanged(info: BatteryInfo)
    }

    interface MotorPowerListener {
        fun onMotorPowerChanged(info: MotorPowerInfo)
    }

    interface RpmListener {
        fun onRpmChanged(info: RpmInfo)
    }

    data class LaneChangeAlert(
        val leftWarning: Int = 0,   // 0=安全, 1=一级预警(常亮), 2=退出中, 3=二级告警(强行变道爆闪)
        val rightWarning: Int = 0,  // 0=安全, 1=一级预警(常亮), 2=退出中, 3=二级告警(强行变道爆闪)
        val isBsdOn: Boolean = true // 并线辅助开关是否开启 (0=关, 1=开)
    ) {
        val isLeftWarningActive: Boolean get() = leftWarning == 1 || leftWarning == 3
        val isRightWarningActive: Boolean get() = rightWarning == 1 || rightWarning == 3
        val isLeftDanger: Boolean get() = leftWarning == 3
        val isRightDanger: Boolean get() = rightWarning == 3
        val hasAnyWarning: Boolean get() = isLeftWarningActive || isRightWarningActive
        val hasDanger: Boolean get() = isLeftDanger || isRightDanger
    }

    data class TurnSignalInfo(
        val isLeftTurn: Boolean = false,
        val isRightTurn: Boolean = false,
        val isHazard: Boolean = false,
        val avmActiveDirection: Int = 0, // 0=无, 1=右, 2=左
        val isLeftTurnActive: Boolean = false,  // 持续转向意图 (融合 AVM 意图与 850ms 脉冲保活，用于变道辅助与盲区研判)
        val isRightTurnActive: Boolean = false  // 持续转向意图 (融合 AVM 意图与 850ms 脉冲保活，用于变道辅助与盲区研判)
    ) {
        val isAnyTurnActive: Boolean get() = isLeftTurn || isRightTurn || isHazard || isLeftTurnActive || isRightTurnActive
    }

    interface LaneChangeAlertListener {
        fun onLaneChangeAlertChanged(alert: LaneChangeAlert)
    }

    interface TurnSignalListener {
        fun onTurnSignalChanged(info: TurnSignalInfo)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CarGear-Worker").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<GearListener>()
    private val driveModeListeners = CopyOnWriteArrayList<DriveModeListener>()
    private val outsideTempListeners = CopyOnWriteArrayList<OutsideTempListener>()
    private val cabinTempListeners = CopyOnWriteArrayList<CabinTempListener>()
    private val batteryListeners = CopyOnWriteArrayList<BatteryInfoListener>()
    private val motorPowerListeners = CopyOnWriteArrayList<MotorPowerListener>()
    private val rpmListeners = CopyOnWriteArrayList<RpmListener>()
    private val laneChangeAlertListeners = CopyOnWriteArrayList<LaneChangeAlertListener>()
    private val turnSignalListeners = CopyOnWriteArrayList<TurnSignalListener>()

    @Volatile var currentLaneChangeAlert: LaneChangeAlert = LaneChangeAlert()
        private set
    @Volatile var currentTurnSignalInfo: TurnSignalInfo = TurnSignalInfo()
        private set

    @Volatile private var rawLeftWarning: Int = 0
    @Volatile private var rawRightWarning: Int = 0
    @Volatile private var rawBsdOnState: Int = 1

    @Volatile private var rawTurnLeftState: Int = 0
    @Volatile private var rawTurnRightState: Int = 0
    @Volatile private var rawEmergencyLightState: Int = 0
    @Volatile private var rawAvmDirection: Int = 0
    @Volatile private var lastReverseGearExitTime: Long = 0L
    @Volatile private var lastLeftTurnPulseTime: Long = 0L
    @Volatile private var lastRightTurnPulseTime: Long = 0L

    private val turnSignalHoldTimeoutRunnable = Runnable {
        updateTurnSignalInternal("转向意图超时收敛")
    }

    // 智能硬件状态防心跳高频误触发记录 (0 表示尚未由封面或显式设色覆盖)
    @Volatile private var lastAmbientLightColorIndex: Int = 0
    @Volatile private var lastHvacDriverTempInt: Int? = null
    @Volatile private var lastHvacSetTempInt: Int? = null
    @Volatile private var lastReportedDriveModeRaw: Int? = null

    @Volatile private var cachedGetPropertyIdMethod: Method? = null
    @Volatile private var cachedGetValueMethod: Method? = null
    @Volatile private var cachedGetFloatPropertyMethod: Method? = null
    @Volatile private var cachedGetIntPropertyMethod: Method? = null

    private var carInstance: Any? = null
    private var cabinManager: Any? = null
    private var dyCabinManager: Any? = null
    private var mcuManager: Any? = null
    private var sensorManager: Any? = null
    private var hvacManager: Any? = null
    private var propertyManager: Any? = null
    // 保持对动态代理回调的强引用，防止在内存紧张 (TrimMemory) 时被垃圾回收机制 (GC) 提前回收导致事件静默失联
    private var cabinCallbackProxy: Any? = null
    private var cabinBatteryCallbackProxy: Any? = null
    private var cabinTempCallbackProxy: Any? = null
    private var cabinPowerCallbackProxy: Any? = null
    private var cabinRpmCallbackProxy: Any? = null
    private var cabinLaneChangeCallbackProxy: Any? = null
    private var cabinTurnSignalCallbackProxy: Any? = null
    private var mcuCallbackProxy: Any? = null
    private var mcuBatteryCallbackProxy: Any? = null
    private var sensorCallbackProxy: Any? = null
    private var hvacCallbackProxy: Any? = null
    private var propertyCallbackProxy: Any? = null

    // 底盘 ESP 加速度缓存
    @Volatile private var latestEspLongMps2: Float? = null
    @Volatile private var latestEspLatMps2: Float? = null
    private var isCarSupported = false
    private var isConnected = false
    private var isInitialized = false

    private var currentGearLabel = "P"
    private var currentRawGear = RAW_GEAR_P

    // N 档瞬态防抖计时器 (用于过路档抑制)
    private var pendingNRunnable: Runnable? = null
    private var pendingNRaw: Int = RAW_GEAR_N

    private var currentDriveMode = MODE_NORMAL
    private var currentRawDriveMode = 1

    @Volatile
    private var currentOutsideTempC: Float? = null

    @Volatile
    private var currentCabinTempC: Float? = null

    @Volatile
    private var isRealCabinSensorAvailable: Boolean = false

    @Volatile
    private var lastRealCabinTempTimeMs: Long = 0L

    private var lastLoggedMotorKw: Float = Float.NaN
    private var lastLoggedMotorTimeMs: Long = 0L
    private var lastLoggedEngineRpm: Int = -1
    private var lastLoggedMotorRpm: Int = -1
    private var lastLoggedBatteryVoltage: Float? = null
    private var lastLoggedBatteryLow: Boolean = false
    private var lastLoggedOutsideTemp: Float? = null
    private var lastLoggedCabinTemp: Float? = null
    private var lastProcessedEvent: Any? = null

    @Volatile
    private var currentBatteryInfo: BatteryInfo = BatteryInfo()

    @Volatile
    private var currentMotorPowerInfo: MotorPowerInfo = MotorPowerInfo()

    @Volatile
    private var filteredHvPowerKw: Float = Float.NaN

    @Volatile
    private var hysteresisDisplayKw: Float = Float.NaN

    @Volatile
    private var latestHvBattU: Float? = null

    @Volatile
    private var latestHvBattI: Float? = null

    @Volatile
    private var isHybridVehicleDetected: Boolean = false

    @Volatile
    private var currentRpmInfo: RpmInfo = RpmInfo()

    @Volatile
    private var latestInputShaft1Rpm: Float? = null

    @Volatile
    private var latestInputShaft2Rpm: Float? = null

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

    fun registerOutsideTempListener(listener: OutsideTempListener) {
        if (!outsideTempListeners.contains(listener)) {
            outsideTempListeners.add(listener)
            currentOutsideTempC?.let { temp ->
                mainHandler.post { listener.onOutsideTempChanged(temp) }
            }
        }
    }

    fun unregisterOutsideTempListener(listener: OutsideTempListener) {
        outsideTempListeners.remove(listener)
    }

    fun registerCabinTempListener(listener: CabinTempListener) {
        if (!cabinTempListeners.contains(listener)) {
            cabinTempListeners.add(listener)
            currentCabinTempC?.let { temp ->
                mainHandler.post { listener.onCabinTempChanged(temp) }
            }
            if (currentCabinTempC == null && isConnected) {
                refreshCabinTempNow()
            }
        }
    }

    fun unregisterCabinTempListener(listener: CabinTempListener) {
        cabinTempListeners.remove(listener)
    }

    fun registerBatteryInfoListener(listener: BatteryInfoListener) {
        if (!batteryListeners.contains(listener)) {
            batteryListeners.add(listener)
            val snapshot = currentBatteryInfo
            mainHandler.post { listener.onBatteryInfoChanged(snapshot) }
        }
    }

    fun unregisterBatteryInfoListener(listener: BatteryInfoListener) {
        batteryListeners.remove(listener)
    }

    fun registerMotorPowerListener(listener: MotorPowerListener) {
        if (!motorPowerListeners.contains(listener)) {
            motorPowerListeners.add(listener)
            val snapshot = currentMotorPowerInfo
            mainHandler.post { listener.onMotorPowerChanged(snapshot) }
        }
    }

    fun unregisterMotorPowerListener(listener: MotorPowerListener) {
        motorPowerListeners.remove(listener)
    }

    fun registerRpmListener(listener: RpmListener) {
        if (!rpmListeners.contains(listener)) {
            rpmListeners.add(listener)
            val snapshot = currentRpmInfo
            mainHandler.post { listener.onRpmChanged(snapshot) }
        }
    }

    fun unregisterRpmListener(listener: RpmListener) {
        rpmListeners.remove(listener)
    }

    fun registerLaneChangeAlertListener(listener: LaneChangeAlertListener) {
        if (!laneChangeAlertListeners.contains(listener)) {
            laneChangeAlertListeners.add(listener)
            val snapshot = currentLaneChangeAlert
            mainHandler.post { listener.onLaneChangeAlertChanged(snapshot) }
        }
    }

    fun unregisterLaneChangeAlertListener(listener: LaneChangeAlertListener) {
        laneChangeAlertListeners.remove(listener)
    }

    fun registerTurnSignalListener(listener: TurnSignalListener) {
        if (!turnSignalListeners.contains(listener)) {
            turnSignalListeners.add(listener)
            val snapshot = currentTurnSignalInfo
            mainHandler.post { listener.onTurnSignalChanged(snapshot) }
        }
    }

    fun unregisterTurnSignalListener(listener: TurnSignalListener) {
        turnSignalListeners.remove(listener)
    }

    fun getCurrentGear(): String = currentGearLabel
    fun getCurrentRawGear(): Int = currentRawGear
    fun getCurrentDriveMode(): String = currentDriveMode
    fun getCurrentRawDriveMode(): Int = currentRawDriveMode
    fun getCurrentOutsideTempC(): Float? = currentOutsideTempC
    fun getCurrentCabinTempC(): Float? = currentCabinTempC
    fun getCurrentBatteryInfo(): BatteryInfo = currentBatteryInfo
    fun getCurrentMotorPowerInfo(): MotorPowerInfo = currentMotorPowerInfo
    fun getCurrentRpmInfo(): RpmInfo = currentRpmInfo
    fun isConnectedToCar(): Boolean = isConnected

    /**
     * 初始化连接车机 CarService (异步握手)
     */
    @Synchronized
    fun init() {
        if (isInitialized) {
            AppLogger.d(TAG, "CarGearManager 已经初始化过，当前已连接=$isConnected")
            return
        }
        isInitialized = true

        workerExecutor.execute {
            try {
                val carClass = Class.forName("android.car.Car")
                isCarSupported = true
                AppLogger.i(TAG, "🚗 检测到系统包含 android.car.Car，开始与 CarService 进行 IPC 握手...")

                val serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        AppLogger.i(TAG, "✅ CarService 服务连通: $name")
                        isConnected = true
                        initManagers(carClass, carInstance)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        AppLogger.w(TAG, "⚠️ CarService 服务断开: $name，3秒后尝试自动重新握手")
                        isConnected = false
                        isInitialized = false
                        cabinManager = null
                        dyCabinManager = null
                        mcuManager = null
                        sensorManager = null
                        hvacManager = null
                        propertyManager = null
                        cabinCallbackProxy = null
                        cabinBatteryCallbackProxy = null
                        cabinTempCallbackProxy = null
                        cabinPowerCallbackProxy = null
                        cabinRpmCallbackProxy = null
                        cabinLaneChangeCallbackProxy = null
                        cabinTurnSignalCallbackProxy = null
                        mcuCallbackProxy = null
                        mcuBatteryCallbackProxy = null
                        sensorCallbackProxy = null
                        hvacCallbackProxy = null
                        propertyCallbackProxy = null
                        latestEspLongMps2 = null
                        latestEspLatMps2 = null
                        mainHandler.postDelayed({
                            if (listeners.isNotEmpty() || driveModeListeners.isNotEmpty() || outsideTempListeners.isNotEmpty() || cabinTempListeners.isNotEmpty() || batteryListeners.isNotEmpty() || motorPowerListeners.isNotEmpty() || rpmListeners.isNotEmpty() || laneChangeAlertListeners.isNotEmpty() || turnSignalListeners.isNotEmpty()) {
                                init()
                            }
                        }, 3000L)
                    }
                }

                // Method: Car.createCar(Context, ServiceConnection)
                val createCarMethod = carClass.getMethod("createCar", Context::class.java, ServiceConnection::class.java)
                carInstance = createCarMethod.invoke(null, context, serviceConnection)

                // Android 9 Automotive 必须显式调用 connect()
                val connectMethod = carClass.getMethod("connect")
                connectMethod.invoke(carInstance)
                AppLogger.i(TAG, "已调用 car.connect()，等待 onServiceConnected 回调...")

            } catch (e: ClassNotFoundException) {
                isCarSupported = false
                isConnected = false
                AppLogger.w(TAG, "当前设备非 Android Automotive 车机系统 (缺少 android.car.Car)，无缝启用本地模拟驱动")
            } catch (t: Throwable) {
                isInitialized = false
                isConnected = false
                AppLogger.e(TAG, "初始化 CarService 握手异常: ${t.message}，4秒后尝试自动重新握手", t)
                mainHandler.postDelayed({
                    if (listeners.isNotEmpty() || driveModeListeners.isNotEmpty() || outsideTempListeners.isNotEmpty() || cabinTempListeners.isNotEmpty() || batteryListeners.isNotEmpty() || motorPowerListeners.isNotEmpty() || rpmListeners.isNotEmpty() || laneChangeAlertListeners.isNotEmpty() || turnSignalListeners.isNotEmpty()) {
                        init()
                    }
                }, 4000L)
            }
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
                AppLogger.i(TAG, "成功获取 CarCabinManager 实例")
                registerCabinCallback(cabinManager!!)
                // 连通时主动读取一次当前实车档位与驾驶模式
                readInitialGear(cabinManager!!)
                readInitialDriveMode(cabinManager!!)
                // 独立隔离注册与读取 12V 蓄电池健康度 SOH (0x2140f10c) 与低电量告警 (0x21403100)，绝不影响档位订阅
                registerCabinBatteryCallback(cabinManager!!)
                readBatterySohAndWarning(cabinManager!!)
                // 重点：欧尚 Z6 iDD 等插电混动车型的 12V 蓄电池电压 (0x2160f51f PHEV ADC / 0x21400a84 EMS) 位于 CarCabinManager
                readBatteryVoltageAndSoc(cabinManager!!)
                // 独立隔离注册与读取座舱内温度 (0x2140f1cc / 0x21603017)
                registerCabinTempCallback(cabinManager!!)
                readInitialCabinTemp(cabinManager!!)
                // 独立隔离注册与读取新能源电机功率 (0x2160f503 × 0x2160f502 / 0x2140f46a / 0x2140f46b)
                registerCabinPowerCallback(cabinManager!!)
                readMotorPowerOnce(cabinManager!!)
                // 独立隔离注册与读取发动机与混动电驱转速 (0x2160f315 / 0x11600305 / 0x2160f47d / 0x2160f47e)
                registerCabinRpmCallback(cabinManager!!)
                readRpmOnce(cabinManager!!)
                // 独立隔离注册与读取变道辅助与盲区监测预警 (0x21412146 / 0x214012c2)
                registerCabinLaneChangeCallback(cabinManager!!)
                readInitialLaneChangeAlert(cabinManager!!)
                // 独立隔离注册与读取转向灯与双闪状态 (0x21400a35 / 0x21400a37 / 0x21400a39 / 0x21402133)
                registerCabinTurnSignalCallback(cabinManager!!)
                readInitialTurnSignals(cabinManager!!)
            } else {
                AppLogger.w(TAG, "未能获取到 CarCabinManager (cabin)")
            }

            // 2. 获取 CarMcuManager (服务名: "mcu_service") —— 同时承担 12V 蓄电池电压 (0x21600a82) 与电量读取
            try {
                mcuManager = getCarManagerMethod.invoke(car, "mcu_service")
                if (mcuManager != null) {
                    AppLogger.i(TAG, "成功获取 CarMcuManager 实例")
                    registerMcuCallback(mcuManager!!)
                    registerMcuBatteryCallback(mcuManager!!)
                    readBatteryVoltageAndSoc(mcuManager!!)
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "未启用/未找到 mcu_service: ${e.message}")
            }

            // 2b. 备选通道：若 CarMcuManager 未读到 12V 电压，尝试通过 CarDYCabinManager ("dycabin") 读取同源 0x21600a82
            if (currentBatteryInfo.voltageV == null) {
                try {
                    dyCabinManager = getCarManagerMethod.invoke(car, "dycabin")
                    dyCabinManager?.let { readBatteryVoltageAndSoc(it) }
                } catch (_: Exception) {}
            }

            // 3. 获取 CarSensorManager (服务名: "sensor") 读取并订阅车外环境温度 (0x11600703, normal 权限)
            try {
                sensorManager = getCarManagerMethod.invoke(car, "sensor")
                if (sensorManager != null) {
                    AppLogger.i(TAG, "成功获取 CarSensorManager 实例，开始接入车外温度传感器 (0x11600703)")
                    registerOutsideTempSensor(sensorManager!!)
                    readInitialOutsideTemp(sensorManager!!)
                } else {
                    AppLogger.w(TAG, "未能获取到 CarSensorManager (sensor)")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "获取 CarSensorManager 异常: ${e.message}")
            }

            // 4. 获取 CarHvacManager (服务名: "hvac") —— 负责实测座舱温度 (0x2540f3b6 / 0x25400503) 与车外温度 (0x11600703)
            try {
                hvacManager = getCarManagerMethod.invoke(car, "hvac")
                if (hvacManager != null) {
                    AppLogger.i(TAG, "成功获取 CarHvacManager 实例，开始注册空调与座舱温度监听")
                    registerHvacCallback(hvacManager!!)
                    readInitialCabinTempFromHvac(hvacManager!!)
                    if (currentOutsideTempC == null) {
                        readOutsideTempFromHvac(hvacManager!!)
                    }
                } else {
                    AppLogger.w(TAG, "未能获取到 CarHvacManager (hvac)")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "获取 CarHvacManager 异常: ${e.message}")
            }

            // 5. 获取 CarPropertyManager (服务名: "property") —— 底层直连属性管理器，定向精准订阅 Vendor 属性
            try {
                propertyManager = getCarManagerMethod.invoke(car, "property")
                if (propertyManager != null) {
                    AppLogger.i(TAG, "成功获取 CarPropertyManager 实例，开始定向订阅温度属性")
                    registerPropertyCallback(propertyManager!!)
                    if (currentCabinTempC == null) {
                        readCabinTempFromProperty(propertyManager!!)
                    }
                } else {
                    AppLogger.w(TAG, "未能获取到 CarPropertyManager (property)")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "获取 CarPropertyManager 异常: ${e.message}")
            }

            // 6. 执行一次实车温度传感器通道全量探测，将全部原始信号打印至日志
            probeAllTemperatureProperties()

        } catch (t: Throwable) {
            AppLogger.e(TAG, "initManagers 异常: ${t.message}", t)
        }
    }

    /**
     * 通过 CarSensorManager 主动读取最新车外温度事件 (0x11600703)
     */
    private fun readInitialOutsideTemp(sensorMgr: Any) {
        try {
            val getLatestMethod = sensorMgr.javaClass.getMethod(
                "getLatestSensorEvent",
                Int::class.javaPrimitiveType
            )
            val event = getLatestMethod.invoke(sensorMgr, SENSOR_TYPE_ENV_OUTSIDE_TEMPERATURE)
            if (event != null) {
                handleOutsideTempSensorEvent(event, source = "Sensor初始查询")
            } else {
                AppLogger.d(TAG, "🌡️ 实车初始车外温度事件暂为空，等待 CAN 总线上报...")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "读取初始车外温度异常: ${t.message}")
        }
    }

    /**
     * 备选通过 CarHvacManager 读取同源属性 ID_OUTSIDE_AIR_TEMP (0x11600703)
     */
    private fun readOutsideTempFromHvac(hvacMgr: Any) {
        try {
            val getFloatMethod = hvacMgr.javaClass.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val temp = getFloatMethod.invoke(hvacMgr, SENSOR_TYPE_ENV_OUTSIDE_TEMPERATURE, 0) as? Float
            if (temp != null) {
                processOutsideTempValue(temp, source = "Hvac初始查询")
            }
        } catch (_: Throwable) {}
    }

    /**
     * 通过 CarHvacManager 主动读取实测座舱温度 (仅读取标准 AOSP 0x15600502 ID_ZONED_TEMP_ACTUAL)
     * 注意：严禁将 0x2540f3b6 (空调目标设定温档) 或 0x25400503 (设定温控) 当作座舱实测温度！
     */
    private fun readInitialCabinTempFromHvac(hvac: Any) {
        if (isRealCabinSensorAvailable && currentCabinTempC != null) return
        val areas = intArrayOf(1, 0, VEHICLE_AREA_GLOBAL, 2)

        // 仅读取标准 AOSP ID_ZONED_TEMP_ACTUAL (0x15600502, Float ℃, 需 CONTROL_CAR_CLIMATE 权限)
        try {
            val getFloatMethod = hvac.javaClass.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            for (area in areas) {
                val temp = runCatching {
                    getFloatMethod.invoke(hvac, ID_ZONED_TEMP_ACTUAL, area) as? Float
                }.getOrNull()
                if (temp != null && !temp.isNaN() && !temp.isInfinite() && temp in MIN_VALID_CABIN_TEMP_C..MAX_VALID_CABIN_TEMP_C) {
                    isRealCabinSensorAvailable = true
                    lastRealCabinTempTimeMs = SystemClock.uptimeMillis()
                    processCabinTempValue(temp, source = "Hvac初始实测(0x15600502, area=$area)")
                    return
                }
            }
        } catch (t: Throwable) {
            AppLogger.d(TAG, "从 CarHvacManager 读取 0x15600502 初始座舱温异常: ${t.message}")
        }
    }

    /**
     * 注册 CarHvacManager.CarHvacEventCallback 监听实车空调与实测座舱温度
     */
    private fun registerHvacCallback(hvac: Any) {
        try {
            val classLoader = hvac.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.hvac.CarHvacManager\$CarHvacEventCallback", true, classLoader)
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { p, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty() && args[0] != null) {
                            handleCarPropertyValue(args[0]!!)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> getDefaultReturnValue(method.returnType)
                    "equals" -> p === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(p)
                    "toString" -> "CarHvacCallbackProxy@${Integer.toHexString(System.identityHashCode(p))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }
            val registerMethod = hvac.javaClass.getMethod("registerCallback", callbackClass)
            runCatching {
                registerMethod.invoke(hvac, proxy)
                hvacCallbackProxy = proxy
                AppLogger.i(TAG, "🌡️ 成功向 CarHvacManager 注册空调事件回调 (0x2540f3b6 / 0x25400503 / 0x11600703)")
            }.onFailure { err ->
                AppLogger.w(TAG, "CarHvacManager.registerCallback 遇到特权属性拦截或不支持全量注册 (改走 PropertyManager/CabinManager 精准订阅): ${err.message}")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "注册 CarHvacManager 回调异常: ${t.message}")
        }
    }

    /**
     * 注册 CarPropertyManager$CarPropertyEventListener 定向精准订阅合法 Vendor 温度属性
     * 彻底规避 CarHvacManager.registerCallback 全量注册 privileged 属性触发的 SecurityException
     */
    private fun registerPropertyCallback(propMgr: Any) {
        try {
            val classLoader = propMgr.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventListener", true, classLoader)
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { p, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty() && args[0] != null) {
                            handleCarPropertyValue(args[0]!!)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> getDefaultReturnValue(method.returnType)
                    "equals" -> p === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(p)
                    "toString" -> "CarPropertyCallbackProxy@${Integer.toHexString(System.identityHashCode(p))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }
            val registerMethod = propMgr.javaClass.getMethod(
                "registerListener",
                callbackClass,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            val propsToRegister = intArrayOf(
                ID_HVAC_TEMPERATURE_DRIVER, // 0x2540f3b6 (Int ℃)
                ID_HVAC_TEMPERATURE_SET,    // 0x25400503 (Float/Int ℃)
                ID_HVAC_AC_INTERNALTEMP,    // 0x2140f1cc
                ID_INSIDE_AIR_TEMP          // 0x21603017
            )
            for (propId in propsToRegister) {
                runCatching {
                    registerMethod.invoke(propMgr, proxy, propId, 0.0f)
                    AppLogger.i(TAG, "🌡️ CarPropertyManager 成功精准订阅属性: 0x${Integer.toHexString(propId)}")
                }.onFailure { err ->
                    AppLogger.d(TAG, "CarPropertyManager 订阅属性 0x${Integer.toHexString(propId)} 跳过: ${err.message}")
                }
            }
            propertyCallbackProxy = proxy
        } catch (t: Throwable) {
            AppLogger.w(TAG, "注册 CarPropertyManager 回调异常: ${t.message}")
        }
    }

    /**
     * 通过 CarPropertyManager 主动直读座舱实测温度 (0x2140f1cc / 0x21603017)
     */
    private fun readCabinTempFromProperty(propMgr: Any) {
        if (isRealCabinSensorAvailable && currentCabinTempC != null) return
        val areas = intArrayOf(0, VEHICLE_AREA_GLOBAL, 1, 2)

        // 1. 首选实车物理座舱温度 ID_HVAC_AC_INTERNALTEMP (0x2140f1cc, Int 0.1℃ / ℃)
        try {
            val getIntMethod = propMgr.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            for (area in areas) {
                val rawInt = runCatching {
                    getIntMethod.invoke(propMgr, ID_HVAC_AC_INTERNALTEMP, area) as? Int
                }.getOrNull()
                if (rawInt != null) {
                    val decoded = decodeAcInternalTemp(rawInt)
                    if (decoded != null) {
                        isRealCabinSensorAvailable = true
                        lastRealCabinTempTimeMs = SystemClock.uptimeMillis()
                        if (processCabinTempValue(decoded, source = "PropertyManager座舱实测(0x2140f1cc, area=$area)")) {
                            return
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2. 备选浮点实测 ID_INSIDE_AIR_TEMP (0x21603017, Float ℃)
        try {
            val getFloatMethod = propMgr.javaClass.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            for (area in areas) {
                val tempFloat = runCatching {
                    getFloatMethod.invoke(propMgr, ID_INSIDE_AIR_TEMP, area) as? Float
                }.getOrNull()
                if (tempFloat != null && !tempFloat.isNaN() && !tempFloat.isInfinite() && tempFloat in MIN_VALID_CABIN_TEMP_C..MAX_VALID_CABIN_TEMP_C) {
                    isRealCabinSensorAvailable = true
                    lastRealCabinTempTimeMs = SystemClock.uptimeMillis()
                    if (processCabinTempValue(tempFloat, source = "PropertyManager备选实测(0x21603017, area=$area)")) {
                        return
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 注册 CarSensorManager.OnSensorChangedListener 监听实车车外温度实时变化
     */
    private fun registerOutsideTempSensor(sensorMgr: Any) {
        try {
            val classLoader = sensorMgr.javaClass.classLoader ?: context.classLoader
            val listenerClass = Class.forName(
                "android.car.hardware.CarSensorManager\$OnSensorChangedListener",
                true,
                classLoader
            )
            val callbackProxy = Proxy.newProxyInstance(
                classLoader,
                arrayOf(listenerClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "onSensorChanged" -> {
                        if (args != null && args.isNotEmpty() && args[0] != null) {
                            handleOutsideTempSensorEvent(args[0]!!, source = "Sensor实时回调")
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "equals" -> proxy === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "CarSensorOutsideTempProxy@${Integer.toHexString(System.identityHashCode(proxy))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }

            // 优先使用反射读取 CarSensorManager.SENSOR_RATE_NORMAL (通常为 3)，兜底为 3
            val rateNormal = try {
                sensorMgr.javaClass.getField("SENSOR_RATE_NORMAL").getInt(null)
            } catch (_: Exception) { 3 }

            val registerMethod = sensorMgr.javaClass.getMethod(
                "registerListener",
                listenerClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val ok = registerMethod.invoke(
                sensorMgr,
                callbackProxy,
                SENSOR_TYPE_ENV_OUTSIDE_TEMPERATURE,
                rateNormal
            ) as? Boolean ?: false

            sensorCallbackProxy = callbackProxy
            AppLogger.i(TAG, "🌡️ 订阅实车车外温度传感器 (0x11600703, rate=$rateNormal) 结果: $ok")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "注册车外温度传感器监听异常: ${t.message}")
        }
    }

    private fun handleOutsideTempSensorEvent(event: Any, source: String) {
        try {
            val sensorType = try {
                event.javaClass.getField("sensorType").getInt(event)
            } catch (_: Exception) {
                SENSOR_TYPE_ENV_OUTSIDE_TEMPERATURE
            }
            if (sensorType != SENSOR_TYPE_ENV_OUTSIDE_TEMPERATURE) return

            val floatValues = event.javaClass.getField("floatValues").get(event) as? FloatArray
            val rawTemp = floatValues?.firstOrNull() ?: return
            processOutsideTempValue(rawTemp, source)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "解析车外温度事件异常: ${t.message}")
        }
    }

    private fun processOutsideTempValue(rawTemp: Float, source: String) {
        if (rawTemp.isNaN() || rawTemp.isInfinite() || rawTemp < MIN_VALID_OUTSIDE_TEMP_C || rawTemp > MAX_VALID_OUTSIDE_TEMP_C) {
            AppLogger.d(TAG, "🌡️ 忽略无效车外温度帧 ($source): raw=$rawTemp")
            return
        }
        // 车规精度规整：四舍五入至 0.5℃ 刻度，与原车仪表步进一致，杜绝浮点噪点频闪
        val normalized = kotlin.math.round(rawTemp * 2f) / 2f
        val prev = currentOutsideTempC
        if (prev != null && kotlin.math.abs(normalized - prev) < 0.25f) {
            return
        }
        currentOutsideTempC = normalized
        AppLogger.recordBreadcrumb("TEMP_OUT", "${normalized}℃")
        val lastLogged = lastLoggedOutsideTemp
        if (lastLogged == null || kotlin.math.abs(normalized - lastLogged) >= 1.0f) {
            lastLoggedOutsideTemp = normalized
            AppLogger.i(TAG, "🌡️ [$source] 实车车外温度更新: ${normalized}℃ (raw=$rawTemp)")
        }
        mainHandler.post {
            for (listener in outsideTempListeners) {
                try {
                    listener.onOutsideTempChanged(normalized)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "OutsideTempListener 执行异常", t)
                }
            }
        }
    }

    /**
     * 车机亮屏唤醒或手动刷新天气时，主动补读一次车外温度最新帧
     */
    fun refreshOutsideTempNow() {
        workerExecutor.execute {
            sensorManager?.let { readInitialOutsideTemp(it) }
            if (currentOutsideTempC == null) {
                hvacManager?.let { readOutsideTempFromHvac(it) }
            }
        }
    }

    /**
     * 主动补读 12V 蓄电池实时电压、健康度 (SOH) 与电量 (SOC)
     */
    fun refreshBatteryInfoNow() {
        workerExecutor.execute {
            // 首选 CarCabinManager 读取 PHEV 混动插电专有 12V ADC (0x2160f51f)
            cabinManager?.let { readBatteryVoltageAndSoc(it) }
            if (currentBatteryInfo.voltageV == null) {
                mcuManager?.let { readBatteryVoltageAndSoc(it) }
            }
            if (currentBatteryInfo.voltageV == null) {
                dyCabinManager?.let { readBatteryVoltageAndSoc(it) }
            }
            cabinManager?.let { readBatterySohAndWarning(it) }
        }
    }

    /**
     * 主动补读实车座舱内温度 (仅向真正的实测传感器 0x2140f1cc / 0x21603017 / 0x15600502 查询)
     */
    fun refreshCabinTempNow() {
        workerExecutor.execute {
            cabinManager?.let { readInitialCabinTemp(it) }
            if (currentCabinTempC == null) {
                hvacManager?.let { readInitialCabinTempFromHvac(it) }
            }
            if (currentCabinTempC == null) {
                propertyManager?.let { readCabinTempFromProperty(it) }
            }
        }
    }

    /**
     * 诊断探测实车全部温度属性 (输出详细 Logcat 便于实车核对传感器硬件状态)
     */
    fun probeAllTemperatureProperties() {
        workerExecutor.execute {
            AppLogger.i(TAG, "🌡️ ======= 实车温度传感器硬件探测开始 =======")
            // 1. 车外温度 (CarSensorManager: 0x11600703)
            sensorManager?.let { mgr ->
                try {
                    val getLatest = mgr.javaClass.getMethod("getLatestSensorEvent", Int::class.javaPrimitiveType)
                    val event = getLatest.invoke(mgr, SENSOR_TYPE_ENV_OUTSIDE_TEMPERATURE)
                    val floats = event?.javaClass?.getField("floatValues")?.get(event) as? FloatArray
                    AppLogger.i(TAG, "🌡️ [0x11600703 车外温度 SensorManager] floatValues=${floats?.contentToString()}")
                } catch (t: Throwable) {
                    AppLogger.d(TAG, "🌡️ [0x11600703 车外温度 SensorManager] 异常: ${t.message}")
                }
            }
            // 2. 车内温度 (CarCabinManager: 0x2140f1cc)
            cabinManager?.let { cabin ->
                for (area in intArrayOf(0, 1, VEHICLE_AREA_GLOBAL)) {
                    try {
                        val getInt = cabin.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        val raw = getInt.invoke(cabin, ID_HVAC_AC_INTERNALTEMP, area) as? Int
                        AppLogger.i(TAG, "🌡️ [0x2140f1cc 座舱温度 CarCabinManager (area=$area)] rawInt=$raw, decoded=${raw?.let { decodeAcInternalTemp(it) }}")
                    } catch (t: Throwable) {
                        AppLogger.d(TAG, "🌡️ [0x2140f1cc 座舱温度 CarCabinManager (area=$area)] 异常: ${t.message}")
                    }
                }
            }
            // 3. 车内备选浮点 (CarCabinManager: 0x21603017)
            cabinManager?.let { cabin ->
                for (area in intArrayOf(0, 1, VEHICLE_AREA_GLOBAL)) {
                    try {
                        val getFloat = cabin.javaClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        val raw = getFloat.invoke(cabin, ID_INSIDE_AIR_TEMP, area) as? Float
                        AppLogger.i(TAG, "🌡️ [0x21603017 座舱温度 CarCabinManager (area=$area)] rawFloat=$raw")
                    } catch (t: Throwable) {
                        AppLogger.d(TAG, "🌡️ [0x21603017 座舱温度 CarCabinManager (area=$area)] 异常: ${t.message}")
                    }
                }
            }
            // 4. AOSP 分区实测 (CarHvacManager: 0x15600502)
            hvacManager?.let { hvac ->
                for (area in intArrayOf(1, 0, VEHICLE_AREA_GLOBAL)) {
                    try {
                        val getFloat = hvac.javaClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        val raw = getFloat.invoke(hvac, ID_ZONED_TEMP_ACTUAL, area) as? Float
                        AppLogger.i(TAG, "🌡️ [0x15600502 AOSP实测 CarHvacManager (area=$area)] rawFloat=$raw")
                    } catch (t: Throwable) {
                        AppLogger.d(TAG, "🌡️ [0x15600502 AOSP实测 CarHvacManager (area=$area)] 异常: ${t.message}")
                    }
                }
            }
            // 5. 空调设定温档 (CarHvacManager: 0x2540f3b6)
            hvacManager?.let { hvac ->
                for (area in intArrayOf(1, 0)) {
                    try {
                        val getInt = hvac.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        val raw = getInt.invoke(hvac, ID_HVAC_TEMPERATURE_DRIVER, area) as? Int
                        AppLogger.i(TAG, "🌡️ [0x2540f3b6 空调设定温档 CarHvacManager (area=$area)] rawInt=$raw")
                    } catch (t: Throwable) {
                        AppLogger.d(TAG, "🌡️ [0x2540f3b6 空调设定温档 CarHvacManager (area=$area)] 异常: ${t.message}")
                    }
                }
            }
            AppLogger.i(TAG, "🌡️ ======= 实车温度传感器硬件探测结束 =======")
        }
    }

    /**
     * 主动补读新能源高压电机功率 (ID_BMS_BATTU × ID_BMS_BATTI / 能量流 / 动力电量)
     */
    fun refreshMotorPowerNow() {
        workerExecutor.execute {
            cabinManager?.let { readMotorPowerOnce(it) }
        }
    }

    private fun isPlugChargingFlow(flowState: Int?): Boolean {
        return flowState == 0x14 || flowState == 0x15 || flowState == 0x16 || flowState == 0x1c
    }

    private fun getFloatPropertyWithGlobalFallback(cabin: Any, getFloat: Method, propId: Int): Float? {
        val v0 = runCatching { getFloat.invoke(cabin, propId, 0) as? Float }.getOrNull()
        if (v0 != null && !v0.isNaN() && !v0.isInfinite()) return v0
        return runCatching { getFloat.invoke(cabin, propId, VEHICLE_AREA_GLOBAL) as? Float }.getOrNull()
            ?.takeIf { !it.isNaN() && !it.isInfinite() }
    }

    private fun getIntPropertyWithGlobalFallback(cabin: Any, getInt: Method, propId: Int): Int? {
        val v0 = runCatching { getInt.invoke(cabin, propId, 0) as? Int }.getOrNull()
        if (v0 != null) return v0
        return runCatching { getInt.invoke(cabin, propId, VEHICLE_AREA_GLOBAL) as? Int }.getOrNull()
    }

    private fun readMotorPowerOnce(cabin: Any) {
        try {
            val getFloat = cachedGetFloatPropertyMethod ?: cabin.javaClass.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { cachedGetFloatPropertyMethod = it }
            val rawU = getFloatPropertyWithGlobalFallback(cabin, getFloat, ID_BMS_BATTU)
            val rawI = getFloatPropertyWithGlobalFallback(cabin, getFloat, ID_BMS_BATTI)
            if (rawU != null && rawU in MIN_VALID_HV_VOLTAGE_V..MAX_VALID_HV_VOLTAGE_V) {
                latestHvBattU = rawU
                isHybridVehicleDetected = true
            }
            if (rawI != null && kotlin.math.abs(rawI) <= MAX_VALID_HV_CURRENT_A) {
                latestHvBattI = rawI
            }
            recomputeMotorPowerFromBus(source = "Cabin主动查询")
        } catch (t: Throwable) {
            AppLogger.d(TAG, "读取高压母线电压/电流异常 (纯燃油版无高压 BMS 属正常): ${t.message}")
        }

        try {
            val getInt = cachedGetIntPropertyMethod ?: cabin.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { cachedGetIntPropertyMethod = it }
            val flow = getIntPropertyWithGlobalFallback(cabin, getInt, ID_PCU_SYSEGY_FLOW)
            val ind = getIntPropertyWithGlobalFallback(cabin, getInt, ID_PCU_IPDRVEGY_IND)
            val soc = getIntPropertyWithGlobalFallback(cabin, getInt, ID_PCU_SOC_DSP)
            val evMode = getIntPropertyWithGlobalFallback(cabin, getInt, ID_PCU_EVMODSTS)
            if (flow != null && flow > 0) isHybridVehicleDetected = true
            if (soc != null && soc in 1..100) isHybridVehicleDetected = true
            if (evMode != null && evMode > 0) {
                isHybridVehicleDetected = true
                updatePowertrainModeFromEvModSts(evMode, source = "Cabin主动查询")
            }
            if (flow != null || (ind != null && ind in 0..100) || (soc != null && soc in 0..100)) {
                updateMotorPowerInfo { prev ->
                    prev.copy(
                        energyFlowState = flow ?: prev.energyFlowState,
                        driveEnergyInd = ind?.takeIf { it in 0..100 } ?: prev.driveEnergyInd,
                        hvSocPercent = soc?.takeIf { it in 0..100 } ?: prev.hvSocPercent,
                        isPlugCharging = isPlugChargingFlow(flow ?: prev.energyFlowState)
                    )
                }
            }
        } catch (_: Throwable) {}
    }

    private fun registerCabinPowerCallback(cabin: Any) {
        try {
            val classLoader = cabin.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.cabin.CarCabinManager\$CarCabinEventCallback", true, classLoader)
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { p, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty() && args[0] != null) {
                            handleCarPropertyValue(args[0]!!)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> getDefaultReturnValue(method.returnType)
                    "equals" -> p === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(p)
                    "toString" -> "CarCabinPowerProxy@${Integer.toHexString(System.identityHashCode(p))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }
            val registerMethod = cabin.javaClass.getMethod("registerCallback", callbackClass, IntArray::class.java)
            registerMethod.invoke(
                cabin,
                proxy,
                intArrayOf(ID_BMS_BATTU, ID_BMS_BATTI, ID_PCU_SYSEGY_FLOW, ID_PCU_IPDRVEGY_IND, ID_PCU_SOC_DSP)
            )
            cabinPowerCallbackProxy = proxy
            AppLogger.i(TAG, "⚡ 成功向 CarCabinManager 独立订阅电机功率与能量流信号 (0x2160f503 / 0x2160f502 / 0x2140f46a)")
        } catch (t: Throwable) {
            AppLogger.d(TAG, "独立订阅电机功率回调未启用 (非新能源配置属正常): ${t.message}")
        }
    }

    private fun recomputeMotorPowerFromBus(source: String) {
        val u = latestHvBattU ?: return
        val i = latestHvBattI ?: return
        if (u !in MIN_VALID_HV_VOLTAGE_V..MAX_VALID_HV_VOLTAGE_V || kotlin.math.abs(i) > MAX_VALID_HV_CURRENT_A) {
            return
        }
        val rawKw = ((u * i) / 1000f) * HV_POWER_SIGN_DIR
        if (rawKw.isNaN() || rawKw.isInfinite() || rawKw < -120f || rawKw > 240f) return
        val deadzoned = if (kotlin.math.abs(rawKw) < 0.08f) 0f else rawKw

        // 1. 自适应非线性低通滤波 (自适应 EWMA)
        // 大动态（踩电门加速/急刹 |delta| >= 2.0kW）极速响应 (alpha = 0.75f)，0 延迟
        // 小稳态（|delta| < 0.8kW，如怠速/待机微噪）深度平滑 (alpha = 0.15f)，抹平电流 ADC 杂波
        val prevFiltered = filteredHvPowerKw
        val delta = if (prevFiltered.isNaN()) 0f else kotlin.math.abs(deadzoned - prevFiltered)
        val alpha = when {
            prevFiltered.isNaN() -> 1.0f
            deadzoned == 0f -> 0.45f // 归零迅速
            delta >= 2.0f -> 0.75f   // 加速/制动大动态极速跟手
            delta >= 0.8f -> 0.35f   // 中等动态
            else -> 0.15f            // 小稳态深度平滑滤波
        }
        val nextFiltered = if (prevFiltered.isNaN()) deadzoned else prevFiltered + (deadzoned - prevFiltered) * alpha
        filteredHvPowerKw = nextFiltered

        // 2. 施密特回差迟滞（Hysteresis），步长 0.1kW，回差带宽 0.025kW (25W)
        // 彻底解决 0.45kW 临界点在 0.4 和 0.5 之间来回跳字抽搐的问题
        val normalizedKw = computeHysteresisDisplay(nextFiltered, hysteresisDisplayKw, step = 0.1f, hysteresis = 0.025f)
        hysteresisDisplayKw = normalizedKw

        updateMotorPowerInfo { prev ->
            val nextPeakOut = if (normalizedKw > prev.peakOutputKw) normalizedKw else prev.peakOutputKw
            val nextPeakRegen = if (normalizedKw < 0f && -normalizedKw > prev.peakRegenKw) -normalizedKw else prev.peakRegenKw
            prev.copy(
                powerKw = normalizedKw,
                voltageV = kotlin.math.round(u * 10f) / 10f,
                currentA = kotlin.math.round(i * 10f) / 10f,
                peakOutputKw = nextPeakOut,
                peakRegenKw = nextPeakRegen
            )
        }
        // 记录黑匣子内存轨迹（零 I/O、不刷 Logcat）
        AppLogger.recordBreadcrumb("POWER", "${normalizedKw}kW (U=${u}V, I=${i}A)")

        // 仅在关键工况跃迁（待机/输出/回收跨越、大阶跃）时输出日志，杜绝持续数值抖动刷爆 Logcat
        val prevPower = lastLoggedMotorKw
        val isFirst = prevPower.isNaN()
        val isPolarityCrossing = !isFirst && (
            (prevPower <= 0.1f && normalizedKw > 0.1f) ||
            (prevPower >= -0.1f && normalizedKw < -0.1f) ||
            (kotlin.math.abs(prevPower) > 0.1f && kotlin.math.abs(normalizedKw) <= 0.1f)
        )
        val isLargeStep = !isFirst && kotlin.math.abs(normalizedKw - prevPower) >= 20.0f

        if (isFirst || isPolarityCrossing || isLargeStep) {
            lastLoggedMotorKw = normalizedKw
            lastLoggedMotorTimeMs = SystemClock.uptimeMillis()
            AppLogger.d(TAG, "⚡ [$source] 电机工况跃迁: ${normalizedKw}kW (U=${u}V, I=${i}A)")
        }
    }

    /**
     * 施密特回差量化算法（Hysteresis Quantizer）
     * 用于消除数显末位由于 ADC 白噪在临界分界点（如 0.45kW）来回反复横跳的问题
     */
    private fun computeHysteresisDisplay(
        value: Float,
        currentDisplay: Float,
        step: Float = 0.1f,
        hysteresis: Float = 0.025f
    ): Float {
        if (currentDisplay.isNaN()) {
            return kotlin.math.round(value * 10f) / 10f
        }
        val diff = value - currentDisplay
        return when {
            diff > 0f -> {
                val stepsUp = ((diff - hysteresis + step * 0.5f) / step).toInt()
                if (stepsUp > 0) {
                    kotlin.math.round((currentDisplay + stepsUp * step) * 10f) / 10f
                } else {
                    currentDisplay
                }
            }
            diff < 0f -> {
                val negDiff = -diff
                val stepsDown = ((negDiff - hysteresis + step * 0.5f) / step).toInt()
                if (stepsDown > 0) {
                    kotlin.math.round((currentDisplay - stepsDown * step) * 10f) / 10f
                } else {
                    currentDisplay
                }
            }
            else -> currentDisplay
        }
    }

    private fun updateMotorPowerInfo(transform: (MotorPowerInfo) -> MotorPowerInfo) {
        val next = synchronized(this) {
            val updated = transform(currentMotorPowerInfo)
            if (updated == currentMotorPowerInfo) return
            currentMotorPowerInfo = updated
            updated
        }
        mainHandler.post {
            for (listener in motorPowerListeners) {
                try {
                    listener.onMotorPowerChanged(next)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "MotorPowerListener 执行异常", t)
                }
            }
        }
    }

    /**
     * 模拟电机输出 (+kW) / 动能回收 (-kW) 功率输入 (供 PAD ADB 验证)
     */
    fun simulateMotorPower(
        powerKw: Float?,
        hvSocPercent: Int? = null,
        isPlugCharging: Boolean = false,
        resetPeaks: Boolean = false
    ) {
        val normalized = powerKw?.takeIf { !it.isNaN() && !it.isInfinite() }
            ?.coerceIn(-100f, 200f)
            ?.let { if (kotlin.math.abs(it) < 0.25f) 0f else kotlin.math.round(it * 10f) / 10f }
        AppLogger.i(TAG, "🧪 模拟电机功率: power=${normalized}kW, hvSoc=${hvSocPercent}%, plugCharging=$isPlugCharging")
        updateMotorPowerInfo { prev ->
            val baseOut = if (resetPeaks) 0f else prev.peakOutputKw
            val baseRegen = if (resetPeaks) 0f else prev.peakRegenKw
            val nextPeakOut = if (normalized != null && normalized > baseOut) normalized else baseOut
            val nextPeakRegen = if (normalized != null && normalized < 0f && -normalized > baseRegen) -normalized else baseRegen
            prev.copy(
                powerKw = normalized,
                hvSocPercent = hvSocPercent?.takeIf { it in 0..100 } ?: prev.hvSocPercent,
                peakOutputKw = nextPeakOut,
                peakRegenKw = nextPeakRegen,
                isPlugCharging = isPlugCharging
            )
        }
    }

    /**
     * 主动补读发动机转速与混动电驱转速 (支持纯油车与 iDD 混动自适应)
     */
    fun refreshRpmNow() {
        workerExecutor.execute {
            cabinManager?.let { readRpmOnce(it) }
            if (currentRpmInfo.engineRpm == null) {
                sensorManager?.let { readEngineRpmFromSensor(it) }
            }
        }
    }

    private fun readEngineRpmFromSensor(sensorMgr: Any) {
        try {
            val getLatest = sensorMgr.javaClass.getMethod("getLatestSensorEvent", Int::class.javaPrimitiveType)
            val event = getLatest.invoke(sensorMgr, ID_ENGINE_RPM) ?: return
            val floatValues = event.javaClass.getField("floatValues").get(event) as? FloatArray
            val raw = floatValues?.firstOrNull() ?: return
            processEngineRpmValue(raw, source = "Sensor备选查询(0x11600305)")
        } catch (_: Throwable) {}
    }

    private fun readRpmOnce(cabin: Any) {
        try {
            val getInt = cachedGetIntPropertyMethod ?: cabin.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { cachedGetIntPropertyMethod = it }
            val evMode = getIntPropertyWithGlobalFallback(cabin, getInt, ID_PCU_EVMODSTS)
            if (evMode != null && evMode > 0) {
                isHybridVehicleDetected = true
            }
        } catch (_: Throwable) {}

        try {
            val getFloat = cachedGetFloatPropertyMethod ?: cabin.javaClass.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { cachedGetFloatPropertyMethod = it }
            // 1. 发动机转速：首选 B561_Dashboard 实车在用的 0x2160f315，备选 AOSP 标准 0x11600305
            var rawEng = getFloatPropertyWithGlobalFallback(cabin, getFloat, ID_ENGINE_RPM_SPEED)
            if (rawEng == null || rawEng < 0f || rawEng > MAX_VALID_ENGINE_RPM) {
                rawEng = getFloatPropertyWithGlobalFallback(cabin, getFloat, ID_ENGINE_RPM)
            }
            if (rawEng != null) {
                processEngineRpmValue(rawEng, source = "Cabin主动查询")
            }

            // 2. 混动电驱轴转速：仅当确认存在高压新能源系统 (iDD) 时采集，杜绝纯油车误显电机转速
            if (isHybridVehicleDetected) {
                val s1 = getFloatPropertyWithGlobalFallback(cabin, getFloat, ID_TCU_INPUTSHAFTSPEED1)
                val s2 = getFloatPropertyWithGlobalFallback(cabin, getFloat, ID_TCU_INPUTSHAFTSPEED2)
                if (s1 != null && s1 in 0f..MAX_VALID_MOTOR_RPM) latestInputShaft1Rpm = s1
                if (s2 != null && s2 in 0f..MAX_VALID_MOTOR_RPM) latestInputShaft2Rpm = s2
                recomputeHybridMotorRpm(source = "Cabin主动查询")
            }
        } catch (t: Throwable) {
            AppLogger.d(TAG, "读取转速属性异常: ${t.message}")
        }
    }

    private fun registerCabinRpmCallback(cabin: Any) {
        try {
            val classLoader = cabin.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.cabin.CarCabinManager\$CarCabinEventCallback", true, classLoader)
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { p, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty() && args[0] != null) {
                            handleCarPropertyValue(args[0]!!)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> getDefaultReturnValue(method.returnType)
                    "equals" -> p === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(p)
                    "toString" -> "CarCabinRpmProxy@${Integer.toHexString(System.identityHashCode(p))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }
            val registerMethod = cabin.javaClass.getMethod("registerCallback", callbackClass, IntArray::class.java)
            registerMethod.invoke(
                cabin,
                proxy,
                intArrayOf(
                    ID_ENGINE_RPM_SPEED,
                    ID_ENGINE_RPM,
                    ID_TCU_INPUTSHAFTSPEED1,
                    ID_TCU_INPUTSHAFTSPEED2,
                    ID_PCU_EVMODSTS
                )
            )
            cabinRpmCallbackProxy = proxy
            AppLogger.i(TAG, "⚙️ 成功向 CarCabinManager 独立订阅发动机与混动电驱转速 (0x2160f315 / 0x11600305 / 0x2160f47d)")
        } catch (t: Throwable) {
            AppLogger.d(TAG, "独立订阅转速回调未启用: ${t.message}")
        }
    }

    private fun processEngineRpmValue(rawRpm: Float, source: String) {
        if (rawRpm.isNaN() || rawRpm.isInfinite() || rawRpm < 0f || rawRpm > MAX_VALID_ENGINE_RPM) return
        val rounded = kotlin.math.round(rawRpm).toInt().let { if (it < 15) 0 else it }
        updateRpmInfo { prev ->
            val nextPeak = if (rounded > prev.enginePeakRpm) rounded else prev.enginePeakRpm
            prev.copy(engineRpm = rounded, enginePeakRpm = nextPeak)
        }
        AppLogger.recordBreadcrumb("ENG_RPM", "$rounded RPM")
        val prev = lastLoggedEngineRpm
        val isFirst = prev == -1
        val isStartOrStop = (prev == 0 && rounded >= 400) || (prev >= 400 && rounded == 0)
        val isHighRpm = prev < 4000 && rounded >= 4000
        if (isFirst || isStartOrStop || isHighRpm) {
            lastLoggedEngineRpm = rounded
            AppLogger.d(TAG, "⚙️ [$source] 发动机转速状态变迁: ${rounded} RPM")
        }
    }

    private fun recomputeHybridMotorRpm(source: String) {
        if (!isHybridVehicleDetected) return
        val s1 = latestInputShaft1Rpm
        val s2 = latestInputShaft2Rpm
        if (s1 == null && s2 == null) return
        val rawMotor = maxOf(s1 ?: 0f, s2 ?: 0f)
        if (rawMotor.isNaN() || rawMotor.isInfinite() || rawMotor < 0f || rawMotor > MAX_VALID_MOTOR_RPM) return
        val rounded = kotlin.math.round(rawMotor).toInt().let { if (it < 15) 0 else it }
        updateRpmInfo { prev ->
            val nextPeak = if (rounded > prev.motorPeakRpm) rounded else prev.motorPeakRpm
            prev.copy(motorRpm = rounded, motorPeakRpm = nextPeak)
        }
        AppLogger.recordBreadcrumb("MOT_RPM", "$rounded RPM")
        val prev = lastLoggedMotorRpm
        val isFirst = prev == -1
        val isMotorStartOrStop = (prev == 0 && rounded >= 300) || (prev >= 300 && rounded == 0)
        if (isFirst || isMotorStartOrStop) {
            lastLoggedMotorRpm = rounded
            AppLogger.d(TAG, "⚙️ [$source] 混动电驱轴状态变迁: ${rounded} RPM")
        }
    }

    private fun updatePowertrainModeFromEvModSts(evModSts: Int, source: String) {
        val mode = when {
            evModSts == 1 -> PowertrainMode.EV
            evModSts >= 2 -> PowertrainMode.HEV
            !isHybridVehicleDetected -> PowertrainMode.PURE_ICE
            else -> PowertrainMode.HEV
        }
        if (currentRpmInfo.powertrainMode != mode) {
            AppLogger.i(TAG, "⚙️ [$source] 动力工况自适应切换: $mode (evModSts=$evModSts)")
            updateRpmInfo { it.copy(powertrainMode = mode) }
        }
    }

    private fun updateRpmInfo(transform: (RpmInfo) -> RpmInfo) {
        val next = synchronized(this) {
            val updated = transform(currentRpmInfo)
            if (updated == currentRpmInfo) return
            currentRpmInfo = updated
            updated
        }
        mainHandler.post {
            for (listener in rpmListeners) {
                try {
                    listener.onRpmChanged(next)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "RpmListener 执行异常", t)
                }
            }
        }
    }

    /**
     * 模拟发动机转速与电机转速输入 (供 PAD ADB 验证油车单显 / 纯电单显 / 混动双显三种形态)
     * 传 null 表示该动力源不存在或无信号
     */
    fun simulateRpm(
        engineRpm: Int?,
        motorRpm: Int?,
        resetPeaks: Boolean = false,
        mode: PowertrainMode? = null
    ) {
        val normEng = engineRpm?.takeIf { it >= 0 }?.coerceAtMost(MAX_VALID_ENGINE_RPM.toInt())
        val normMot = motorRpm?.takeIf { it >= 0 }?.coerceAtMost(MAX_VALID_MOTOR_RPM.toInt())
        val resolvedMode = mode ?: when {
            normEng != null && normMot != null -> PowertrainMode.HEV
            normMot != null -> PowertrainMode.EV
            else -> PowertrainMode.PURE_ICE
        }
        AppLogger.i(TAG, "🧪 模拟转速: engine=${normEng ?: "null"} RPM, motor=${normMot ?: "null"} RPM, mode=$resolvedMode, resetPeaks=$resetPeaks")
        updateRpmInfo { prev ->
            val baseEngPeak = if (resetPeaks) 0 else prev.enginePeakRpm
            val baseMotPeak = if (resetPeaks) 0 else prev.motorPeakRpm
            val nextEngPeak = if (normEng != null && normEng > baseEngPeak) normEng else baseEngPeak
            val nextMotPeak = if (normMot != null && normMot > baseMotPeak) normMot else baseMotPeak
            RpmInfo(
                engineRpm = normEng,
                motorRpm = normMot,
                enginePeakRpm = nextEngPeak,
                motorPeakRpm = nextMotPeak,
                powertrainMode = resolvedMode
            )
        }
    }

    private fun decodeAcInternalTemp(rawInt: Int): Float? {
        if (rawInt == 255 || rawInt == -40 || rawInt == -127 || rawInt == 0 || rawInt == 0x7FFF || rawInt == 0xFFFF || rawInt == -1) {
            return null
        }
        // 依据实车总线抓包分析：长安汽车座舱内环境温度传感器 (0x2140f1cc) 为 0.1℃ 定点数或整型摄氏度
        // 例如 raw=120 -> 12.0℃，raw=235 -> 23.5℃，或者 raw=24 -> 24.0℃
        val temp = when {
            rawInt in 70..650 -> rawInt * 0.1f
            rawInt in -400..-10 -> rawInt * 0.1f
            rawInt in -30..65 -> rawInt.toFloat()
            else -> null
        }
        return temp?.takeIf { it in MIN_VALID_CABIN_TEMP_C..MAX_VALID_CABIN_TEMP_C }
    }

    private fun readInitialCabinTemp(cabin: Any) {
        // 若已读取到真实座舱内温度，无需重复查询
        if (isRealCabinSensorAvailable && currentCabinTempC != null) return
        val areas = intArrayOf(0, VEHICLE_AREA_GLOBAL, 1, 2)

        // 1. 首选实车座舱物理传感器 ID_HVAC_AC_INTERNALTEMP (0x2140f1cc, 0.1℃ 定点数，FOTA 实车在用)
        try {
            val getInt = cachedGetIntPropertyMethod ?: cabin.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { cachedGetIntPropertyMethod = it }
            for (area in areas) {
                val rawInt = runCatching {
                    getInt.invoke(cabin, ID_HVAC_AC_INTERNALTEMP, area) as? Int
                }.getOrNull()
                if (rawInt != null) {
                    val decoded = decodeAcInternalTemp(rawInt)
                    if (decoded != null) {
                        isRealCabinSensorAvailable = true
                        lastRealCabinTempTimeMs = SystemClock.uptimeMillis()
                        if (processCabinTempValue(decoded, source = "Cabin初始实测(0x2140f1cc, area=$area)")) {
                            return
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            AppLogger.d(TAG, "读取座舱内温度 0x2140f1cc 异常: ${t.message}")
        }

        // 2. 备选浮点实测座舱温 ID_INSIDE_AIR_TEMP (0x21603017, Float ℃)
        try {
            val getFloat = cachedGetFloatPropertyMethod ?: cabin.javaClass.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { cachedGetFloatPropertyMethod = it }
            for (area in areas) {
                val rawFloat = runCatching {
                    getFloat.invoke(cabin, ID_INSIDE_AIR_TEMP, area) as? Float
                }.getOrNull()
                if (rawFloat != null && !rawFloat.isNaN() && !rawFloat.isInfinite() && rawFloat in MIN_VALID_CABIN_TEMP_C..MAX_VALID_CABIN_TEMP_C) {
                    isRealCabinSensorAvailable = true
                    lastRealCabinTempTimeMs = SystemClock.uptimeMillis()
                    if (processCabinTempValue(rawFloat, source = "Cabin备选实测(0x21603017, area=$area)")) {
                        return
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun registerCabinTempCallback(cabin: Any) {
        try {
            val classLoader = cabin.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.cabin.CarCabinManager\$CarCabinEventCallback", true, classLoader)
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { p, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty() && args[0] != null) {
                            handleCarPropertyValue(args[0]!!)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> getDefaultReturnValue(method.returnType)
                    "equals" -> p === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(p)
                    "toString" -> "CarCabinTempProxy@${Integer.toHexString(System.identityHashCode(p))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }
            val registerMethod = cabin.javaClass.getMethod("registerCallback", callbackClass, IntArray::class.java)
            registerMethod.invoke(cabin, proxy, intArrayOf(ID_HVAC_TEMPERATURE_SET, ID_HVAC_AC_INTERNALTEMP, ID_INSIDE_AIR_TEMP))
            cabinTempCallbackProxy = proxy
            AppLogger.i(TAG, "🌡️ 成功向 CarCabinManager 独立订阅座舱内温度 (0x25400503 / 0x2140f1cc / 0x21603017)")
        } catch (t: Throwable) {
            AppLogger.d(TAG, "独立订阅座舱内温度回调未启用: ${t.message}")
        }
    }

    private fun processCabinTempValue(rawTemp: Float, source: String): Boolean {
        if (rawTemp.isNaN() || rawTemp.isInfinite() || rawTemp < MIN_VALID_CABIN_TEMP_C || rawTemp > MAX_VALID_CABIN_TEMP_C) {
            AppLogger.d(TAG, "🌡️ 忽略无效座舱温度帧 ($source): raw=$rawTemp")
            return false
        }
        val normalized = kotlin.math.round(rawTemp * 2f) / 2f
        val prev = currentCabinTempC
        if (prev != null && kotlin.math.abs(normalized - prev) < 0.25f) {
            return true
        }
        currentCabinTempC = normalized
        AppLogger.recordBreadcrumb("TEMP_CAB", "${normalized}℃")
        val lastLogged = lastLoggedCabinTemp
        if (lastLogged == null || kotlin.math.abs(normalized - lastLogged) >= 1.0f) {
            lastLoggedCabinTemp = normalized
            AppLogger.i(TAG, "🌡️ [$source] 实车座舱内温度更新: ${normalized}℃ (raw=$rawTemp)")
        }
        mainHandler.post {
            for (listener in cabinTempListeners) {
                try {
                    listener.onCabinTempChanged(normalized)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "CabinTempListener 执行异常", t)
                }
            }
        }
        return true
    }

    /**
     * 模拟室外温度与座舱内温度输入 (供 PAD ADB 对比显示验证)
     */
    fun simulateTemps(outsideTempC: Float?, cabinTempC: Float?) {
        outsideTempC?.let { processOutsideTempValue(it, source = "ADB模拟室外") }
        cabinTempC?.let { processCabinTempValue(it, source = "ADB模拟座舱") }
    }

    private fun readBatteryVoltageAndSoc(mgr: Any) {
        try {
            val getFloat = cachedGetFloatPropertyMethod ?: mgr.javaClass.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { cachedGetFloatPropertyMethod = it }
            // 1. 首选 PHEV 插电混动专有 12V ADC 电压 (0x2160f51f, CarCabinManager)
            var rawVoltage = getFloatPropertyWithGlobalFallback(mgr, getFloat, ID_MCU_REPORT_AIO_BAT_ADC)

            // 2. 尝试标准 12V 蓄电池电压 (0x21600a82, CarMcuManager / CarDYCabinManager)
            if (rawVoltage == null || !isValidBatteryVoltage(rawVoltage)) {
                rawVoltage = getFloatPropertyWithGlobalFallback(mgr, getFloat, ID_BODY_BATTERY_U_BATT_INFO)
            }

            // 3. 尝试 BCM 蓄电池电压 (0x2160f16f, CarMcuManager 备选)
            if (rawVoltage == null || !isValidBatteryVoltage(rawVoltage)) {
                rawVoltage = getFloatPropertyWithGlobalFallback(mgr, getFloat, ID_BCM_BATTERY_U)
            }

            if (rawVoltage != null && isValidBatteryVoltage(rawVoltage)) {
                processBatteryVoltage(rawVoltage, source = "${mgr.javaClass.simpleName}查询")
            }
        } catch (t: Throwable) {
            AppLogger.d(TAG, "从 ${mgr.javaClass.simpleName} 读取 12V 电压异常: ${t.message}")
        }

        // 4. 尝试从 EMS 发动机端读取 12V 电压 (0x21400a84)
        if (currentBatteryInfo.voltageV == null) {
            try {
                val getInt = cachedGetIntPropertyMethod ?: mgr.javaClass.getMethod(
                    "getIntProperty",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).also { cachedGetIntPropertyMethod = it }
                val rawEms = getIntPropertyWithGlobalFallback(mgr, getInt, ID_BODY_BATTERY_VOLTAGE_EMS)
                if (rawEms != null) {
                    val v = when {
                        rawEms in 8000..18500 -> rawEms / 1000f
                        rawEms in 80..185 -> rawEms / 10f
                        else -> Float.NaN
                    }
                    if (!v.isNaN() && isValidBatteryVoltage(v)) {
                        processBatteryVoltage(v, source = "${mgr.javaClass.simpleName}EMS查询(0x21400a84)")
                    }
                }
            } catch (_: Throwable) {}
        }

        // 5. 尝试读取剩余电量 SOC (0x21400a81, 严格要求 1..100，0 代表无 IBS 传感器)
        try {
            val getInt = cachedGetIntPropertyMethod ?: mgr.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { cachedGetIntPropertyMethod = it }
            val rawSoc = getIntPropertyWithGlobalFallback(mgr, getInt, ID_BODY_BATTERY_REMAINING_INFO)
            if (rawSoc != null && rawSoc in 1..100) {
                updateBatteryInfo { it.copy(socPercent = rawSoc) }
            }
        } catch (_: Throwable) {}
    }

    private fun readBatterySohAndWarning(cabin: Any) {
        try {
            val getInt = cachedGetIntPropertyMethod ?: cabin.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).also { cachedGetIntPropertyMethod = it }
            val rawSoh = getIntPropertyWithGlobalFallback(cabin, getInt, ID_BCM_SOH_LIFE_PERCENT)
            if (rawSoh != null) {
                processBatterySoh(rawSoh, source = "Cabin初始查询")
            }
            val rawLow = getIntPropertyWithGlobalFallback(cabin, getInt, ID_BODY_BATTERY_SOC_LOW)
            if (rawLow != null) {
                updateBatteryInfo { it.copy(isLowWarning = rawLow == 1) }
            }
        } catch (t: Throwable) {
            AppLogger.d(TAG, "读取 12V 蓄电池健康度 SOH 异常: ${t.message}")
        }
    }

    private fun registerCabinBatteryCallback(cabin: Any) {
        try {
            val classLoader = cabin.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.cabin.CarCabinManager\$CarCabinEventCallback", true, classLoader)
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { p, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty() && args[0] != null) {
                            handleCarPropertyValue(args[0]!!)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> getDefaultReturnValue(method.returnType)
                    "equals" -> p === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(p)
                    "toString" -> "CarCabinBatteryProxy@${Integer.toHexString(System.identityHashCode(p))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }
            val registerMethod = cabin.javaClass.getMethod("registerCallback", callbackClass, IntArray::class.java)
            registerMethod.invoke(
                cabin,
                proxy,
                intArrayOf(
                    ID_MCU_REPORT_AIO_BAT_ADC,
                    ID_BODY_BATTERY_VOLTAGE_EMS,
                    ID_BCM_SOH_LIFE_PERCENT,
                    ID_BODY_BATTERY_SOC_LOW
                )
            )
            cabinBatteryCallbackProxy = proxy
            AppLogger.i(TAG, "🔋 成功向 CarCabinManager 独立订阅蓄电池电压(0x2160f51f/0x21400a84)、SOH (0x2140f10c) 与告警")
        } catch (t: Throwable) {
            AppLogger.d(TAG, "独立订阅 Cabin 蓄电池 SOH 回调未启用 (部分车型无 IBS 属正常): ${t.message}")
        }
    }

    private fun registerMcuBatteryCallback(mcu: Any) {
        try {
            val classLoader = mcu.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.mcu.CarMcuManager\$CarMcuEventCallback", true, classLoader)
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { p, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty() && args[0] != null) {
                            handleCarPropertyValue(args[0]!!)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> getDefaultReturnValue(method.returnType)
                    "equals" -> p === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(p)
                    "toString" -> "CarMcuBatteryProxy@${Integer.toHexString(System.identityHashCode(p))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }
            val registerMethod = mcu.javaClass.getMethod("registerCallback", callbackClass, IntArray::class.java)
            registerMethod.invoke(
                mcu,
                proxy,
                intArrayOf(ID_BODY_BATTERY_U_BATT_INFO, ID_BCM_BATTERY_U, ID_BODY_BATTERY_REMAINING_INFO)
            )
            mcuBatteryCallbackProxy = proxy
            AppLogger.i(TAG, "🔋 成功向 CarMcuManager 独立订阅 12V 蓄电池电压 (0x21600a82 / 0x2160f16f) 与电量")
        } catch (t: Throwable) {
            AppLogger.d(TAG, "独立订阅 MCU 蓄电池电压回调未启用，将通过轮询查询兜底: ${t.message}")
        }
    }

    private fun isValidBatteryVoltage(v: Float): Boolean {
        return !v.isNaN() && !v.isInfinite() && v in MIN_VALID_BATTERY_VOLTAGE_V..MAX_VALID_BATTERY_VOLTAGE_V
    }

    private fun processBatteryVoltage(rawVoltage: Float, source: String) {
        if (!isValidBatteryVoltage(rawVoltage)) return
        val normalized = kotlin.math.round(rawVoltage * 10f) / 10f
        val prev = currentBatteryInfo.voltageV
        if (prev != null && kotlin.math.abs(normalized - prev) < 0.05f) return
        AppLogger.recordBreadcrumb("BATTERY", "${normalized}V (raw=$rawVoltage)")
        val lastV = lastLoggedBatteryVoltage
        val isFirst = lastV == null
        val isLargeShift = lastV != null && kotlin.math.abs(normalized - lastV) >= 0.5f
        val isLow = normalized < 11.8f
        val lowToggled = isLow != lastLoggedBatteryLow
        if (isFirst || isLargeShift || lowToggled) {
            lastLoggedBatteryVoltage = normalized
            lastLoggedBatteryLow = isLow
            AppLogger.i(TAG, "🔋 [$source] 12V 蓄电池关键状态更新: ${normalized}V (低电=$isLow)")
        }
        updateBatteryInfo { prevInfo ->
            prevInfo.copy(voltageV = normalized)
        }
    }

    private fun processBatterySoh(rawSoh: Int, source: String) {
        // 依据《车辆信息读取指南.md》§4.6：无 IBS 传感器时可能返回 0 或 255(0xFF)，严格过滤仅接受 1..100
        if (rawSoh !in 1..100) {
            AppLogger.throttledD(TAG, "🔋 忽略无效蓄电池 SOH 值 ($source): raw=$rawSoh", 10 * 60_000L)
            return
        }
        if (currentBatteryInfo.sohPercent == rawSoh) return
        AppLogger.i(TAG, "🔋 [$source] 12V 蓄电池健康度 SOH 更新: ${rawSoh}%")
        updateBatteryInfo { it.copy(sohPercent = rawSoh) }
    }

    private fun updateBatteryInfo(transform: (BatteryInfo) -> BatteryInfo) {
        val next = synchronized(this) {
            val updated = transform(currentBatteryInfo)
            if (updated == currentBatteryInfo) return
            currentBatteryInfo = updated
            updated
        }
        mainHandler.post {
            for (listener in batteryListeners) {
                try {
                    listener.onBatteryInfoChanged(next)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "BatteryInfoListener 执行异常", t)
                }
            }
        }
    }

    /**
     * 模拟 12V 蓄电池电压与健康度输入 (供 PAD ADB 验证与测试)
     */
    fun simulateBattery(
        voltageV: Float?,
        sohPercent: Int?,
        socPercent: Int? = null,
        isLowWarning: Boolean = false
    ) {
        val normalizedV = voltageV?.takeIf { isValidBatteryVoltage(it) }?.let { kotlin.math.round(it * 10f) / 10f }
        val normalizedSoh = sohPercent?.takeIf { it in 1..100 }
        val normalizedSoc = socPercent?.takeIf { it in 0..100 }
        AppLogger.i(TAG, "🧪 模拟 12V 蓄电池信息: voltage=${normalizedV}V, soh=${normalizedSoh}%, soc=${normalizedSoc}%, low=$isLowWarning")
        updateBatteryInfo {
            BatteryInfo(
                voltageV = normalizedV,
                sohPercent = normalizedSoh,
                socPercent = normalizedSoc,
                isLowWarning = isLowWarning
            )
        }
    }

    /**
     * 主动读取一次实车当前档位 (支持 0x2140210f 与 0x11400400 双路热备份，确保重启自愈后 100% 捕获真实在播/行驶档位)
     */
    private fun readInitialGear(cabin: Any) {
        try {
            // int getIntProperty(int propertyId, int area)
            val getIntMethod = cabin.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            // 优先查询 0x2140210f (CarCabinManager 新能源座舱档位)
            var raw = try {
                getIntMethod.invoke(cabin, ID_GEAR_SELECTION_NEW, 0) as? Int ?: 0
            } catch (_: Exception) { 0 }

            // 若 0x2140210f 未命中或为无效值，立即回退查询 0x11400400 (底层动力总线档位)
            if (!isValidGear(raw)) {
                raw = try {
                    getIntMethod.invoke(cabin, ID_GEAR_SELECTION, 0) as? Int ?: 0
                } catch (_: Exception) { 0 }
            }

            if (isValidGear(raw)) {
                val label = toGearLabel(raw)
                AppLogger.i(TAG, "🎉 实车初始档位主动查询成功: raw=$raw -> $label 档")
                notifyGearInternal(label, raw, debounceN = false)
            } else {
                AppLogger.d(TAG, "实车初始档位非白名单值: raw=$raw (有效值: 1=N, 2=R, 4=P, 8=D)")
            }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "主动读取初始档位异常: ${t.message}")
        }
    }

    /**
     * 主动读取一次实车当前驾驶模式 (支持 0x2140f030 与 0x2141f08e 双属性兼容)
     */
    private fun readInitialDriveMode(cabin: Any) {
        try {
            val getIntMethod = cabin.javaClass.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            var raw = try {
                getIntMethod.invoke(cabin, ID_VENDOR_DRIVER_MODE_SET, 0) as? Int ?: 0
            } catch (_: Exception) { 0 }

            if (raw == 0) {
                raw = try {
                    getIntMethod.invoke(cabin, ID_DRIVING_MODE_ALL, 0) as? Int ?: 0
                } catch (_: Exception) { 0 }
            }

            val mode = toDriveMode(raw)
            AppLogger.i(TAG, "🎉 实车初始驾驶模式主动查询成功: raw=$raw -> $mode")
            notifyDriveModeInternal(mode, raw)
        } catch (t: Throwable) {
            AppLogger.w(TAG, "主动读取初始驾驶模式异常: ${t.message}")
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
            ) { proxy, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty()) {
                            val event = args[0] // CarPropertyValue
                            handleCarPropertyValue(event)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> {
                        AppLogger.w(TAG, "CarCabinManager.onErrorEvent: ${args?.contentToString()}")
                        getDefaultReturnValue(method.returnType)
                    }
                    "equals" -> proxy === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "CarCabinEventCallbackProxy@${Integer.toHexString(System.identityHashCode(proxy))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }

            // Method: registerCallback(CarCabinEventCallback, int[])
            val registerMethod = cabin.javaClass.getMethod(
                "registerCallback",
                callbackClass,
                IntArray::class.java
            )
            // 严禁订阅 ID_DRIVING_MODE_ALL (0x2141f08e): 该属性为底盘各子系统全量复合掩码，
            // 开机初期会短暂发送状态位 raw=3，若作为驾驶模式订阅会导致错误识别为 SPORT 并冲刷真实模式！
            // 驾驶模式变动唯一信任 ID_VENDOR_DRIVER_MODE_SET (0x2140f030)
            registerMethod.invoke(cabin, callbackProxy, intArrayOf(
                ID_GEAR_SELECTION_NEW, 
                ID_GEAR_SELECTION,
                ID_VENDOR_DRIVER_MODE_SET
            ))
            cabinCallbackProxy = callbackProxy
            AppLogger.i(TAG, "🔥 成功向 CarCabinManager 订阅档位与驾驶模式核心属性 (0x2140210f / 0x11400400 / 0x2140f030)")

        } catch (t: Throwable) {
            AppLogger.e(TAG, "注册 CarCabinManager 监听异常: ${t.message}", t)
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
            ) { proxy, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty()) {
                            val event = args[0]
                            handleCarPropertyValue(event)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> {
                        AppLogger.w(TAG, "CarMcuManager.onErrorEvent: ${args?.contentToString()}")
                        getDefaultReturnValue(method.returnType)
                    }
                    "equals" -> proxy === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "CarMcuEventCallbackProxy@${Integer.toHexString(System.identityHashCode(proxy))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }

            val registerMethod = mcu.javaClass.getMethod(
                "registerCallback",
                callbackClass,
                IntArray::class.java
            )
            registerMethod.invoke(
                mcu,
                callbackProxy,
                intArrayOf(
                    ID_GEAR_SELECTION,
                    ID_ESP_LONG_ACCELERATION,
                    ID_ESP_LAT_ACCELERATION
                )
            )
            mcuCallbackProxy = callbackProxy
            AppLogger.i(TAG, "成功向 CarMcuManager 订阅备用档位属性与底盘 ESP 姿态加速度 (0x2160f17f / 0x2160f1a9)")

        } catch (t: Throwable) {
            val root = if (t is java.lang.reflect.InvocationTargetException && t.cause != null) t.cause!! else t
            AppLogger.w(TAG, "注册 CarMcuManager 备用监听失败: ${root.javaClass.simpleName}${if (root.message != null) " - " + root.message else ""}")
        }
    }

    private fun getDefaultReturnValue(returnType: Class<*>): Any? {
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Void.TYPE -> null
            else -> null
        }
    }

    private fun registerCabinLaneChangeCallback(cabin: Any) {
        try {
            val classLoader = cabin.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.cabin.CarCabinManager\$CarCabinEventCallback", true, classLoader)
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { p, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty() && args[0] != null) {
                            handleCarPropertyValue(args[0]!!)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> getDefaultReturnValue(method.returnType)
                    "equals" -> p === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(p)
                    "toString" -> "CarCabinLaneChangeProxy@${Integer.toHexString(System.identityHashCode(p))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }
            val registerMethod = cabin.javaClass.getMethod("registerCallback", callbackClass, IntArray::class.java)
            registerMethod.invoke(
                cabin,
                proxy,
                intArrayOf(
                    ID_ADAS_BSD_LCA_ALERT,
                    ID_ADAS_APA_BSD_LCA_ON,
                    ID_LCDA_SIGNAL,
                    ID_LCDAL_BSD_LCA_STATUS,
                    ID_LCDAR_BSD_LCA_STATUS,
                    ID_LCDAL_RLTARGETDETECTION,
                    ID_LCDAL_RRTARGETDETECTION
                )
            )
            cabinLaneChangeCallbackProxy = proxy
            AppLogger.i(TAG, "🛡️ 成功向 CarCabinManager 独立订阅变道辅助与盲区监测预警矩阵 (0x21412146/0x2141f228/0x2140f364/0x2140f365/0x2140030a/0x21400310)")
        } catch (t: Throwable) {
            AppLogger.d(TAG, "独立订阅变道辅助预警回调未启用: ${t.message}")
        }
    }

    private fun readInitialLaneChangeAlert(cabin: Any) {
        try {
            val getIntMethod = try {
                cabin.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            } catch (_: Exception) { null }
            if (getIntMethod != null) {
                val bsdOn = try {
                    getIntMethod.invoke(cabin, ID_ADAS_APA_BSD_LCA_ON, 0) as? Int ?: 1
                } catch (_: Exception) { 1 }
                rawBsdOnState = bsdOn
            }

            val getIntArrayMethod = try {
                cabin.javaClass.getMethod("getIntArrayProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            } catch (_: Exception) { null }

            val arr: IntArray? = if (getIntArrayMethod != null) {
                try {
                    (getIntArrayMethod.invoke(cabin, ID_ADAS_BSD_LCA_ALERT, 0) as? IntArray)
                        ?: (getIntArrayMethod.invoke(cabin, ID_LCDA_SIGNAL, 0) as? IntArray)
                } catch (_: Exception) { null }
            } else null

            if (arr != null && arr.isNotEmpty()) {
                // 欧尚 CAN 总线实车标定：实车实测物理定义 arr[0] 为右后视镜预警，arr[1] 为左后视镜预警
                rawLeftWarning = if (arr.size > 1) arr[1] else 0
                rawRightWarning = arr[0]
            }
            updateLaneChangeAlertInternal("Cabin主动查询")
        } catch (t: Throwable) {
            AppLogger.d(TAG, "主动读取变道辅助预警异常: ${t.message}")
        }
    }

    private fun registerCabinTurnSignalCallback(cabin: Any) {
        try {
            val classLoader = cabin.javaClass.classLoader ?: context.classLoader
            val callbackClass = Class.forName("android.car.hardware.cabin.CarCabinManager\$CarCabinEventCallback", true, classLoader)
            val proxy = Proxy.newProxyInstance(classLoader, arrayOf(callbackClass)) { p, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        if (args != null && args.isNotEmpty() && args[0] != null) {
                            handleCarPropertyValue(args[0]!!)
                        }
                        getDefaultReturnValue(method.returnType)
                    }
                    "onErrorEvent" -> getDefaultReturnValue(method.returnType)
                    "equals" -> p === (args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(p)
                    "toString" -> "CarCabinTurnSignalProxy@${Integer.toHexString(System.identityHashCode(p))}"
                    else -> getDefaultReturnValue(method.returnType)
                }
            }
            val registerMethod = cabin.javaClass.getMethod("registerCallback", callbackClass, IntArray::class.java)
            registerMethod.invoke(
                cabin,
                proxy,
                intArrayOf(
                    ID_BODY_TURN_LEFT_SIGNAL_STATE,
                    ID_BODY_TURN_RIGHT_SIGNAL_STATE,
                    ID_BODY_EMERGENCY_LIGHT,
                    ID_TURNLIGHT_ACTIVE_AVM
                )
            )
            cabinTurnSignalCallbackProxy = proxy
            AppLogger.i(TAG, "💡 成功向 CarCabinManager 独立订阅转向灯与双闪 (0x21400a35 / 0x21400a37 / 0x21400a39 / 0x21402133)")
        } catch (t: Throwable) {
            AppLogger.d(TAG, "独立订阅转向灯回调未启用: ${t.message}")
        }
    }

    private fun readInitialTurnSignals(cabin: Any) {
        try {
            val getIntMethod = try {
                cabin.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            } catch (_: Exception) { null }
            if (getIntMethod != null) {
                rawTurnLeftState = try { getIntMethod.invoke(cabin, ID_BODY_TURN_LEFT_SIGNAL_STATE, 0) as? Int ?: 0 } catch (_: Exception) { 0 }
                rawTurnRightState = try { getIntMethod.invoke(cabin, ID_BODY_TURN_RIGHT_SIGNAL_STATE, 0) as? Int ?: 0 } catch (_: Exception) { 0 }
                rawEmergencyLightState = try { getIntMethod.invoke(cabin, ID_BODY_EMERGENCY_LIGHT, 0) as? Int ?: 0 } catch (_: Exception) { 0 }
                rawAvmDirection = try { getIntMethod.invoke(cabin, ID_TURNLIGHT_ACTIVE_AVM, 0) as? Int ?: 0 } catch (_: Exception) { 0 }
                updateTurnSignalInternal("Cabin主动查询")
            }
        } catch (t: Throwable) {
            AppLogger.d(TAG, "主动读取转向灯状态异常: ${t.message}")
        }
    }

    private fun updateLaneChangeAlertInternal(source: String) {
        val hasMirrorAlert = (rawLeftWarning == 1 || rawLeftWarning == 3) || (rawRightWarning == 1 || rawRightWarning == 3)
        val isBsdOn = (rawBsdOnState != 0) || hasMirrorAlert
        val newAlert = LaneChangeAlert(
            leftWarning = rawLeftWarning,
            rightWarning = rawRightWarning,
            isBsdOn = isBsdOn
        )
        if (newAlert != currentLaneChangeAlert) {
            currentLaneChangeAlert = newAlert
            AppLogger.i(TAG, "🛡️ [$source] 变道盲区预警更新: Left=${newAlert.leftWarning}, Right=${newAlert.rightWarning}, BsdOn=$isBsdOn")
            mainHandler.post {
                for (l in laneChangeAlertListeners) {
                    try {
                        l.onLaneChangeAlertChanged(newAlert)
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "LaneChangeAlertListener 执行异常", t)
                    }
                }
            }
        }
    }

    private var simulatedTurnBlinkRunnable: Runnable? = null
    private var isSimulatingTurnBlink = false

    private fun updateTurnSignalInternal(source: String) {
        val now = SystemClock.uptimeMillis()
        val isReverseGear = currentGearLabel == "R" || currentRawGear == RAW_GEAR_R
        val inExitReverseCooldown = (now - lastReverseGearExitTime) < 600L

        // 倒挡与退场保护：实车挂入倒挡或刚切出倒挡时，中控 AVM 360全景、倒车雷达或倒车辅助照明经常使总线 0x21400a39 或左右状态位拉高。
        // 实车此时并未开启物理双闪，小欧屏坚决彻底抑制双闪与假转向灯提示，保证界面纯净！
        val isHazard = if (isReverseGear || inExitReverseCooldown) {
            rawEmergencyLightState = 0
            false
        } else {
            rawEmergencyLightState == 1
        }

        // 倒挡及退场保护下抑制倒车辅助照明引起的假左右转向灯
        val isLeft = if (isReverseGear || inExitReverseCooldown) false else (!isHazard && rawTurnLeftState == 1)
        val isRight = if (isReverseGear || inExitReverseCooldown) false else (!isHazard && rawTurnRightState == 1)
        val effectiveAvmDir = if (isReverseGear || inExitReverseCooldown) 0 else rawAvmDirection

        if (isReverseGear || inExitReverseCooldown || isHazard) {
            lastLeftTurnPulseTime = 0L
            lastRightTurnPulseTime = 0L
        } else {
            // 左右单侧转向严格互斥保活：打右灯时立即清除左侧残余脉冲窗口，打左灯时立即清除右侧残余脉冲窗口
            if (isLeft && !isRight) {
                lastLeftTurnPulseTime = now
                lastRightTurnPulseTime = 0L
            } else if (isRight && !isLeft) {
                lastRightTurnPulseTime = now
                lastLeftTurnPulseTime = 0L
            }
        }

        // 持续变道意图研判 (严格单侧物理闪光继电器防抖，不混入 0x21402133 反向 AVM 脉冲，杜绝打右灯误升级左侧预警)：
        // - 物理闪光继电器亮灯状态 (isLeft / isRight)；
        // - 物理闪光继电器 850ms 脉冲保活：跨越 760ms 周期中的 380ms 熄灭盲区，并在对侧点亮时 0ms 立即互清。
        val isLeftIntent = !isHazard && !isReverseGear && !inExitReverseCooldown &&
                (isLeft || (!isRight && (now - lastLeftTurnPulseTime < TURN_SIGNAL_INTENT_HOLD_MS)))
        val isRightIntent = !isHazard && !isReverseGear && !inExitReverseCooldown &&
                (isRight || (!isLeft && (now - lastRightTurnPulseTime < TURN_SIGNAL_INTENT_HOLD_MS)))

        // 当存在基于脉冲保活的活跃状态时，安排延时任务在保活窗口结束后自动收敛状态
        if (!isHazard && !isReverseGear && !inExitReverseCooldown && (isLeftIntent || isRightIntent)) {
            mainHandler.removeCallbacks(turnSignalHoldTimeoutRunnable)
            mainHandler.postDelayed(turnSignalHoldTimeoutRunnable, TURN_SIGNAL_INTENT_HOLD_MS + 20L)
        }

        val newInfo = TurnSignalInfo(
            isLeftTurn = isLeft,
            isRightTurn = isRight,
            isHazard = isHazard,
            avmActiveDirection = effectiveAvmDir,
            isLeftTurnActive = isLeftIntent,
            isRightTurnActive = isRightIntent
        )
        if (newInfo != currentTurnSignalInfo) {
            currentTurnSignalInfo = newInfo
            AppLogger.i(TAG, "💡 [$source] 转向灯状态更新: Left=$isLeft(active=$isLeftIntent), Right=$isRight(active=$isRightIntent), Hazard=$isHazard, AVM=$rawAvmDirection")
            mainHandler.post {
                for (l in turnSignalListeners) {
                    try {
                        l.onTurnSignalChanged(newInfo)
                    } catch (t: Throwable) {
                        AppLogger.e(TAG, "TurnSignalListener 执行异常", t)
                    }
                }
            }
        }
    }

    fun simulateLaneChangeAlert(left: Int, right: Int, bsdOn: Boolean = true) {
        rawLeftWarning = left
        rawRightWarning = right
        rawBsdOnState = if (bsdOn) 1 else 0
        updateLaneChangeAlertInternal("模拟测试广播")
    }

    fun simulateTurnSignal(left: Boolean, right: Boolean, hazard: Boolean = false, avmDir: Int = 0) {
        simulatedTurnBlinkRunnable?.let { mainHandler.removeCallbacks(it) }
        simulatedTurnBlinkRunnable = null
        mainHandler.removeCallbacks(turnSignalHoldTimeoutRunnable)

        val anyActive = left || right || hazard
        if (!anyActive) {
            rawEmergencyLightState = 0
            rawTurnLeftState = 0
            rawTurnRightState = 0
            rawAvmDirection = avmDir
            lastLeftTurnPulseTime = 0L
            lastRightTurnPulseTime = 0L
            updateTurnSignalInternal("模拟测试广播(关闭)")
            return
        }

        val effectiveAvm = if (avmDir != 0) avmDir else if (left) 2 else if (right) 1 else 0
        rawAvmDirection = effectiveAvm
        var isOn = true
        rawEmergencyLightState = if (hazard) 1 else 0
        rawTurnLeftState = if (left || hazard) 1 else 0
        rawTurnRightState = if (right || hazard) 1 else 0
        updateTurnSignalInternal("模拟测试广播(点亮)")

        simulatedTurnBlinkRunnable = object : Runnable {
            override fun run() {
                isOn = !isOn
                rawEmergencyLightState = if (hazard && isOn) 1 else 0
                rawTurnLeftState = if ((left || hazard) && isOn) 1 else 0
                rawTurnRightState = if ((right || hazard) && isOn) 1 else 0
                updateTurnSignalInternal("模拟继电器节奏(${if (isOn) "亮" else "灭"})")
                mainHandler.postDelayed(this, if (isOn) 380L else 320L)
            }
        }
        mainHandler.postDelayed(simulatedTurnBlinkRunnable!!, 380L)
    }

    private fun extractIntFromValue(value: Any?): Int {
        return when (value) {
            is Int -> value
            is Array<*> -> (value.firstOrNull() as? Number)?.toInt() ?: 0
            is IntArray -> value.firstOrNull() ?: 0
            is Number -> value.toInt()
            else -> 0
        }
    }

    private fun extractFloatFromValue(value: Any?): Float {
        return when (value) {
            is Float -> value
            is Number -> value.toFloat()
            is FloatArray -> value.firstOrNull() ?: Float.NaN
            is Array<*> -> (value.firstOrNull() as? Number)?.toFloat() ?: Float.NaN
            else -> Float.NaN
        }
    }

    private fun extractIntArrayFromValue(value: Any?): IntArray? {
        return when (value) {
            is IntArray -> value
            is Array<*> -> IntArray(value.size) { i -> (value[i] as? Number)?.toInt() ?: 0 }
            is List<*> -> IntArray(value.size) { i -> (value[i] as? Number)?.toInt() ?: 0 }
            is Number -> intArrayOf(value.toInt())
            else -> null
        }
    }

    /**
     * 解析 CarPropertyValue 并执行白名单校验
     */
    private fun handleCarPropertyValue(event: Any) {
        if (event === lastProcessedEvent) return
        lastProcessedEvent = event
        try {
            val getPropId = cachedGetPropertyIdMethod ?: event.javaClass.getMethod("getPropertyId").also {
                cachedGetPropertyIdMethod = it
            }
            val getVal = cachedGetValueMethod ?: event.javaClass.getMethod("getValue").also {
                cachedGetValueMethod = it
            }
            val propId = getPropId.invoke(event) as Int
            when (propId) {
                ID_GEAR_SELECTION_NEW, ID_GEAR_SELECTION -> {
                    val rawValue = extractIntFromValue(getVal.invoke(event))
                    if (isValidGear(rawValue)) {
                        val label = toGearLabel(rawValue)
                        AppLogger.i(TAG, "🚘 实车档位总线变动触发: prop=0x${Integer.toHexString(propId)}, raw=$rawValue -> $label")
                        notifyGearInternal(label, rawValue, debounceN = true)
                    } else {
                        AppLogger.d(TAG, "忽略非标准档位过渡噪声: raw=$rawValue")
                    }
                }
                ID_VENDOR_DRIVER_MODE_SET -> {
                    val rawValue = extractIntFromValue(getVal.invoke(event))
                    val mode = toDriveMode(rawValue)
                    if (lastReportedDriveModeRaw == null) {
                        lastReportedDriveModeRaw = rawValue
                        notifyDriveModeInternal(mode, rawValue)
                    } else if (rawValue != lastReportedDriveModeRaw) {
                        lastReportedDriveModeRaw = rawValue
                        AppLogger.i(TAG, "🏎️ 实车驾驶模式总线变动触发: prop=0x${Integer.toHexString(propId)}, raw=$rawValue -> $mode")
                        notifyDriveModeInternal(mode, rawValue)
                        AmbientLightManager.onDriveModeChanged()
                    }
                }
                ID_MCU_REPORT_AIO_BAT_ADC -> {
                    val rawV = extractFloatFromValue(getVal.invoke(event))
                    processBatteryVoltage(rawV, source = "Cabin实时回调(0x2160f51f PHEV ADC)")
                }
                ID_BODY_BATTERY_VOLTAGE_EMS -> {
                    val rawVal = extractIntFromValue(getVal.invoke(event))
                    val v = when {
                        rawVal in 8000..18500 -> rawVal / 1000f
                        rawVal in 80..185 -> rawVal / 10f
                        else -> Float.NaN
                    }
                    if (!v.isNaN() && isValidBatteryVoltage(v)) {
                        processBatteryVoltage(v, source = "Cabin实时回调(0x21400a84 EMS)")
                    }
                }
                ID_BODY_BATTERY_U_BATT_INFO, ID_BCM_BATTERY_U -> {
                    val rawV = extractFloatFromValue(getVal.invoke(event))
                    processBatteryVoltage(rawV, source = "总线实时回调(0x${Integer.toHexString(propId)})")
                }
                ID_BCM_SOH_LIFE_PERCENT -> {
                    val rawSoh = extractIntFromValue(getVal.invoke(event))
                    processBatterySoh(rawSoh, source = "总线实时回调")
                }
                ID_BODY_BATTERY_REMAINING_INFO -> {
                    val rawSoc = extractIntFromValue(getVal.invoke(event))
                    if (rawSoc in 1..100) {
                        updateBatteryInfo { it.copy(socPercent = rawSoc) }
                    }
                }
                ID_BODY_BATTERY_SOC_LOW -> {
                    val rawLow = extractIntFromValue(getVal.invoke(event))
                    updateBatteryInfo { it.copy(isLowWarning = rawLow == 1) }
                }
                ID_HVAC_TEMPERATURE_DRIVER -> {
                    // 空调主驾目标设定温档 (0x2540f3b6)，仅作空调设定记录，严禁当作座舱实测温度
                    val rawVal = getVal.invoke(event)
                    val rawInt = extractIntFromValue(rawVal)
                    if (lastHvacDriverTempInt == null) {
                        lastHvacDriverTempInt = rawInt
                        AppLogger.d(TAG, "🌡️ 初始记录空调主驾设定温档 (0x2540f3b6): raw=$rawVal, gear=$rawInt")
                    } else if (rawInt != lastHvacDriverTempInt) {
                        lastHvacDriverTempInt = rawInt
                        AppLogger.d(TAG, "🌡️ 收到空调主驾设定温档变动 (0x2540f3b6): raw=$rawVal, gear=$rawInt")
                        AmbientLightManager.onAirConditionerChanged()
                    }
                }
                ID_HVAC_TEMPERATURE_SET -> {
                    // 空调设定温控 (0x25400503)，仅作空调设定记录，严禁当作座舱实测温度
                    val rawVal = getVal.invoke(event)
                    val rawInt = extractIntFromValue(rawVal)
                    if (lastHvacSetTempInt == null) {
                        lastHvacSetTempInt = rawInt
                        AppLogger.d(TAG, "🌡️ 初始记录空调设定温控 (0x25400503): raw=$rawVal")
                    } else if (rawInt != lastHvacSetTempInt) {
                        lastHvacSetTempInt = rawInt
                        AppLogger.d(TAG, "🌡️ 收到空调设定温控变动 (0x25400503): raw=$rawVal")
                        AmbientLightManager.onAirConditionerChanged()
                    }
                }
                ID_ZONED_TEMP_ACTUAL -> {
                    val rawFloat = extractFloatFromValue(getVal.invoke(event))
                    if (!rawFloat.isNaN() && rawFloat in MIN_VALID_CABIN_TEMP_C..MAX_VALID_CABIN_TEMP_C) {
                        isRealCabinSensorAvailable = true
                        lastRealCabinTempTimeMs = SystemClock.uptimeMillis()
                        processCabinTempValue(rawFloat, source = "Hvac实测座舱温(0x15600502)")
                    }
                }
                SENSOR_TYPE_ENV_OUTSIDE_TEMPERATURE -> {
                    val rawFloat = extractFloatFromValue(getVal.invoke(event))
                    processOutsideTempValue(rawFloat, source = "Hvac/Sensor实时回调(0x11600703)")
                }
                ID_HVAC_AC_INTERNALTEMP -> {
                    val rawInt = extractIntFromValue(getVal.invoke(event))
                    val decoded = decodeAcInternalTemp(rawInt)
                    if (decoded != null) {
                        isRealCabinSensorAvailable = true
                        lastRealCabinTempTimeMs = SystemClock.uptimeMillis()
                        processCabinTempValue(decoded, source = "总线实测座舱温(0x2140f1cc)")
                    } else {
                        AppLogger.d(TAG, "🌡️ 忽略无效座舱温度 0x2140f1cc: rawInt=$rawInt")
                    }
                }
                ID_INSIDE_AIR_TEMP -> {
                    val rawFloat = extractFloatFromValue(getVal.invoke(event))
                    if (!rawFloat.isNaN() && rawFloat in MIN_VALID_CABIN_TEMP_C..MAX_VALID_CABIN_TEMP_C) {
                        isRealCabinSensorAvailable = true
                        lastRealCabinTempTimeMs = SystemClock.uptimeMillis()
                        processCabinTempValue(rawFloat, source = "总线实测座舱温(0x21603017)")
                    }
                }
                ID_ESP_LONG_ACCELERATION -> {
                    val raw = extractFloatFromValue(getVal.invoke(event))
                    if (!raw.isNaN() && kotlin.math.abs(raw) < 25f) {
                        latestEspLongMps2 = raw
                        CarGForceManager.getInstance(context).onVehicleEspAccelChanged(latestEspLatMps2, latestEspLongMps2)
                    }
                }
                ID_ESP_LAT_ACCELERATION -> {
                    val raw = extractFloatFromValue(getVal.invoke(event))
                    if (!raw.isNaN() && kotlin.math.abs(raw) < 25f) {
                        latestEspLatMps2 = raw
                        CarGForceManager.getInstance(context).onVehicleEspAccelChanged(latestEspLatMps2, latestEspLongMps2)
                    }
                }
                ID_BMS_BATTU -> {
                    val rawU = extractFloatFromValue(getVal.invoke(event))
                    if (!rawU.isNaN() && rawU in MIN_VALID_HV_VOLTAGE_V..MAX_VALID_HV_VOLTAGE_V) {
                        latestHvBattU = rawU
                        isHybridVehicleDetected = true
                        recomputeMotorPowerFromBus(source = "总线回调(0x2160f503)")
                    }
                }
                ID_BMS_BATTI -> {
                    val rawI = extractFloatFromValue(getVal.invoke(event))
                    if (!rawI.isNaN() && kotlin.math.abs(rawI) <= MAX_VALID_HV_CURRENT_A) {
                        latestHvBattI = rawI
                        recomputeMotorPowerFromBus(source = "总线回调(0x2160f502)")
                    }
                }
                ID_PCU_SYSEGY_FLOW -> {
                    val flow = extractIntFromValue(getVal.invoke(event))
                    if (flow > 0) isHybridVehicleDetected = true
                    updateMotorPowerInfo { it.copy(energyFlowState = flow, isPlugCharging = isPlugChargingFlow(flow)) }
                }
                ID_PCU_IPDRVEGY_IND -> {
                    val ind = extractIntFromValue(getVal.invoke(event))
                    if (ind in 0..100) {
                        updateMotorPowerInfo { it.copy(driveEnergyInd = ind) }
                    }
                }
                ID_PCU_SOC_DSP -> {
                    val soc = extractIntFromValue(getVal.invoke(event))
                    if (soc in 0..100) {
                        if (soc > 0) isHybridVehicleDetected = true
                        updateMotorPowerInfo { it.copy(hvSocPercent = soc) }
                    }
                }
                ID_PCU_EVMODSTS -> {
                    val evMode = extractIntFromValue(getVal.invoke(event))
                    if (evMode > 0) isHybridVehicleDetected = true
                    updatePowertrainModeFromEvModSts(evMode, source = "总线回调(0x2140f51d)")
                }
                ID_ENGINE_RPM_SPEED, ID_ENGINE_RPM -> {
                    val rawEng = extractFloatFromValue(getVal.invoke(event))
                    processEngineRpmValue(rawEng, source = "总线回调(0x${Integer.toHexString(propId)})")
                }
                ID_TCU_INPUTSHAFTSPEED1 -> {
                    val rawS1 = extractFloatFromValue(getVal.invoke(event))
                    if (!rawS1.isNaN() && rawS1 in 0f..MAX_VALID_MOTOR_RPM) {
                        latestInputShaft1Rpm = rawS1
                        recomputeHybridMotorRpm(source = "总线回调(0x2160f47d)")
                    }
                }
                ID_TCU_INPUTSHAFTSPEED2 -> {
                    val rawS2 = extractFloatFromValue(getVal.invoke(event))
                    if (!rawS2.isNaN() && rawS2 in 0f..MAX_VALID_MOTOR_RPM) {
                        latestInputShaft2Rpm = rawS2
                        recomputeHybridMotorRpm(source = "总线回调(0x2160f47e)")
                    }
                }
                ID_ADAS_BSD_LCA_ALERT -> {
                    val arr = extractIntArrayFromValue(getVal.invoke(event))
                    if (arr != null && arr.isNotEmpty()) {
                        // 实车标定：实车实测物理定义 arr[0] 为右后视镜预警，arr[1] 为左后视镜预警
                        rawLeftWarning = if (arr.size > 1) arr[1] else 0
                        rawRightWarning = arr[0]
                        AppLogger.d(TAG, "🛡️ 收到 ADAS_BSD_LCA_ALERT(0x21412146): Left=$rawLeftWarning, Right=$rawRightWarning")
                        updateLaneChangeAlertInternal("总线回调(0x21412146)")
                    }
                }
                ID_LCDA_SIGNAL -> {
                    val arr = extractIntArrayFromValue(getVal.invoke(event))
                    if (arr != null && arr.isNotEmpty()) {
                        rawLeftWarning = if (arr.size > 1) arr[1] else 0
                        rawRightWarning = arr[0]
                        AppLogger.d(TAG, "🛡️ 收到 LCDA_SIGNAL(0x2141f228): Left=$rawLeftWarning, Right=$rawRightWarning")
                        updateLaneChangeAlertInternal("总线回调(0x2141f228)")
                    }
                }
                ID_LCDAL_BSD_LCA_STATUS -> {
                    // 雷达控制器系统就绪状态 (1=Standby, 2=Active)，仅记录日志，严禁污染后视镜报警灯
                    val status = extractIntFromValue(getVal.invoke(event))
                    AppLogger.v(TAG, "左侧LCDA控制器状态(0x2140f364): $status")
                }
                ID_LCDAR_BSD_LCA_STATUS -> {
                    val status = extractIntFromValue(getVal.invoke(event))
                    AppLogger.v(TAG, "右侧LCDA控制器状态(0x2140f365): $status")
                }
                ID_LCDAL_RLTARGETDETECTION -> {
                    // 仅记录毫米波雷达长距粗检调试日志，严禁直接作为预警点亮小欧屏！
                    // 变道预警必须 100% 遵循原厂智驾 0x21412146 的判定距离，杜绝远距离误报
                    val detect = extractIntFromValue(getVal.invoke(event))
                    AppLogger.v(TAG, "左后方雷达粗检目标状态: $detect")
                }
                ID_LCDAL_RRTARGETDETECTION -> {
                    val detect = extractIntFromValue(getVal.invoke(event))
                    AppLogger.v(TAG, "右后方雷达粗检目标状态: $detect")
                }
                ID_ADAS_APA_BSD_LCA_ON -> {
                    rawBsdOnState = extractIntFromValue(getVal.invoke(event))
                    updateLaneChangeAlertInternal("并线开关回调(0x214012c2)")
                }
                ID_BODY_TURN_LEFT_SIGNAL_STATE -> {
                    rawTurnLeftState = extractIntFromValue(getVal.invoke(event))
                    updateTurnSignalInternal("左转向总线回调(0x21400a35)")
                }
                ID_BODY_TURN_RIGHT_SIGNAL_STATE -> {
                    rawTurnRightState = extractIntFromValue(getVal.invoke(event))
                    updateTurnSignalInternal("右转向总线回调(0x21400a37)")
                }
                ID_BODY_EMERGENCY_LIGHT -> {
                    val rawVal = extractIntFromValue(getVal.invoke(event))
                    val isReverse = currentGearLabel == "R" || currentRawGear == RAW_GEAR_R
                    val now = SystemClock.uptimeMillis()
                    val inExitReverseCooldown = (now - lastReverseGearExitTime) < 600L
                    if ((isReverse || inExitReverseCooldown) && rawVal == 1) {
                        AppLogger.d(TAG, "🛡️ 倒车影像/倒车辅助激活期间，忽略虚假双闪信号 (0x21400a39=1)")
                        rawEmergencyLightState = 0
                    } else {
                        rawEmergencyLightState = rawVal
                    }
                    updateTurnSignalInternal("双闪总线回调(0x21400a39)")
                }
                ID_TURNLIGHT_ACTIVE_AVM -> {
                    rawAvmDirection = extractIntFromValue(getVal.invoke(event))
                    updateTurnSignalInternal("转向AVM回调(0x21402133)")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "解析 CarPropertyValue 异常: ${e.message}", e)
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
        AppLogger.i(TAG, "🧪 模拟切档触发: $label (raw=$raw)")
        notifyGearInternal(label, raw, debounceN = true)
    }

    fun simulateRawGear(rawGear: Int) {
        if (isValidGear(rawGear)) {
            val label = toGearLabel(rawGear)
            AppLogger.i(TAG, "🧪 模拟 Raw 档位触发: raw=$rawGear -> $label")
            notifyGearInternal(label, rawGear, debounceN = true)
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
        AppLogger.i(TAG, "🧪 模拟驾驶模式触发: $mode (raw=$raw)")
        notifyDriveModeInternal(mode, raw)
    }

    fun simulateRawDriveMode(rawMode: Int) {
        val mode = toDriveMode(rawMode)
        AppLogger.i(TAG, "🧪 模拟 Raw 驾驶模式触发: raw=$rawMode -> $mode")
        notifyDriveModeInternal(mode, rawMode)
    }

    /**
     * 档位总线与模拟输入统一分发入口 (车规级过路档 Transit 抑制算法)
     */
    private fun notifyGearInternal(label: String, raw: Int, debounceN: Boolean = true) {
        if (label == "N" && debounceN) {
            // N 档意图确认窗口 (车规级过路档抑制算法: 250ms 防抖)
            // 揉库 (D <-> R) 或快速 P -> D 切档时，总线瞬态经过 N 档在此窗口内被暂扣，不打扰全屏 3D 翻转
            pendingNRunnable?.let { mainHandler.removeCallbacks(it) }
            AppLogger.d(TAG, "⏳ 收到 N 档信号，启动 ${TRANSIT_N_DEBOUNCE_MS}ms 意图确认窗口 (防揉库闪屏)...")
            pendingNRaw = raw
            val runnable = Runnable {
                AppLogger.i(TAG, "⏱️ N 档停留已达 ${TRANSIT_N_DEBOUNCE_MS}ms，确认为真实挂入空档，执行调度")
                pendingNRunnable = null
                notifyGearDirect("N", pendingNRaw)
            }
            pendingNRunnable = runnable
            mainHandler.postDelayed(runnable, TRANSIT_N_DEBOUNCE_MS)
            return
        }

        // 收到非 N 档 (P, R, D) 或免防抖 N 档:
        // 如果此前有未到期的 N 档防抖计时器，证明 N 只是过路档，直接丢弃 N 档！
        if (pendingNRunnable != null) {
            mainHandler.removeCallbacks(pendingNRunnable!!)
            pendingNRunnable = null
            AppLogger.i(TAG, "🚦 检测到快速切档到非 N 档 ($label)，成功抑制过路 N 档，直通调度: $label 档")
        }

        notifyGearDirect(label, raw)
    }

    private fun notifyGearDirect(label: String, raw: Int) {
        val oldLabel = currentGearLabel
        currentGearLabel = label
        currentRawGear = raw
        AppLogger.recordBreadcrumb("GEAR", "$label (raw=$raw)")
        CarGForceManager.getInstance(context).onGearChanged(label)
        if (oldLabel != label) {
            if (oldLabel == "R" && label != "R") {
                lastReverseGearExitTime = SystemClock.uptimeMillis()
                rawEmergencyLightState = 0
                AppLogger.i(TAG, "🚗 档位离开 R 档 ($oldLabel->$label)，强行清零双闪寄存器并开启 600ms 退场杂波过滤保护")
            }
            updateTurnSignalInternal("档位切入切出($oldLabel->$label)")
        }
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onGearChanged(label, raw)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "GearListener 执行异常", t)
                }
            }
        }
    }

    private fun notifyDriveModeInternal(mode: String, raw: Int) {
        currentDriveMode = mode
        currentRawDriveMode = raw
        AppLogger.recordBreadcrumb("DRIVE_MODE", "$mode (raw=$raw)")
        mainHandler.post {
            for (listener in driveModeListeners) {
                try {
                    listener.onDriveModeChanged(mode, raw)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "DriveModeListener 执行异常", t)
                }
            }
        }
    }

    // ==========================================
    // 智能场景联动 · 车载座舱氛围灯硬件直控通道 (CarCabinManager CAN 总线直接接管)
    // ==========================================

    fun setCabinIntProperty(propId: Int, area: Int, value: Int): Boolean {
        val cabin = cabinManager ?: return false
        return try {
            val method = cabin.javaClass.getMethod("setIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            method.invoke(cabin, propId, area, value)
            true
        } catch (t: Throwable) {
            AppLogger.d(TAG, "CarCabinManager.setIntProperty(0x${Integer.toHexString(propId)}, area=$area, val=$value) 失败: ${t.message}")
            false
        }
    }

    fun setCabinIntArrayProperty(propId: Int, area: Int, values: IntArray): Boolean {
        val cabin = cabinManager ?: return false
        return try {
            val method = cabin.javaClass.getMethod("setIntArrayProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java)
            method.invoke(cabin, propId, area, values)
            true
        } catch (t: Throwable) {
            AppLogger.d(TAG, "CarCabinManager.setIntArrayProperty(0x${Integer.toHexString(propId)}, area=$area) 失败: ${t.message}")
            false
        }
    }

    fun getCabinIntArrayProperty(propId: Int, area: Int): IntArray? {
        val cabin = cabinManager ?: return null
        return try {
            val method = cabin.javaClass.getMethod("getIntArrayProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            method.invoke(cabin, propId, area) as? IntArray
        } catch (t: Throwable) {
            AppLogger.d(TAG, "CarCabinManager.getIntArrayProperty(0x${Integer.toHexString(propId)}, area=$area) 失败: ${t.message}")
            null
        }
    }

    /**
     * 读取当前车机物理氛围灯全区亮度 (1..10 级)
     */
    fun getAmbientLightBrightness(): Int {
        val arr = getCabinIntArrayProperty(0x2141f3d5, 1)
        val level = arr?.firstOrNull() ?: -1
        return if (level in 1..10) level else -1
    }

    /**
     * CAN 直控：氛围灯总开关使能 (0x21400a25 ID_BODY_LIGHT_ATMO_ON)
     * 原厂协议约定：2 为开启(ON)，1 为关闭(OFF)
     */
    fun setAmbientLightSwitch(enabled: Boolean) {
        workerExecutor.execute {
            val v = if (enabled) 2 else 1
            setCabinIntProperty(0x21400a25, 0, v)
            setCabinIntProperty(0x21400a25, 1, v)
            AppLogger.i(TAG, "💡 [CAN直控] 氛围灯总开关写入: $enabled (val=$v, 2=ON/1=OFF)")
        }
    }

    /**
     * CAN 直控：设置氛围灯基准亮度 (1..10 级) - 用户手动菜单与常亮恢复专用
     * 写入 0x2140f3e5 (HU_ALC_BRIGHTNESS_SET) + 0x2140f22e (呼吸周期清零)
     * + 0x2141f3d5 area=1 (全车 5 区亮度) + 0x2141f3d5 area=2 (全车 10 位色+亮联合)
     * + 分区控制寄存器 (0x2140f215..0x2140f219)
     * 彻底解决实车 BCM 硬件响应不完全、原车 recovery 恢复旧亮度或忽略外部写入的问题
     */
    fun setAmbientLightBrightness(level: Int) {
        workerExecutor.execute {
            val safeLevel = level.coerceIn(1, 10)
            val isCoverMode = try { com.carlyrics.util.PrefsManager.isAmbientLightColorSyncEnabled() } catch (_: Exception) { true }
            // 1. 设置车机主亮度信号 (0x2140f3e5 HU_ALC_BRIGHTNESS_SET)
            setCabinIntProperty(0x2140f3e5, 0, safeLevel)
            // 2. 清零呼吸周期 (0x2140f22e)，保证纯色与律动即时生效，杜绝 BCM 硬件进入自主呼吸
            setCabinIntProperty(0x2140f22e, 0, 0)
            // 3. 写入全车 5 区纯亮度数组 (0x2141f3d5 area=1)
            val brightArray = IntArray(5) { safeLevel }
            setCabinIntArrayProperty(0x2141f3d5, 1, brightArray)
            // 4. 仅在根据封面颜色变换模式且已产生真实有效颜色 (1..64) 时，写入全车 10 位色+亮联合控制数组 (0x2141f3d5 area=2: 0..4为颜色, 5..9为亮度)
            // 若为使用系统设置颜色模式或尚未提取到有效封面色，坚决不写入颜色，允许原车系统保留原有颜色
            if (isCoverMode && lastAmbientLightColorIndex in 1..64) {
                val safeColor = lastAmbientLightColorIndex
                val comboArray = IntArray(10) { if (it < 5) safeColor else safeLevel }
                setCabinIntArrayProperty(0x2141f3d5, 2, comboArray)
            }
            // 5. 写入仪表台与车门各区物理亮度寄存器 (0x2140f215..0x2140f219)
            setCabinIntProperty(0x2140f215, 0, safeLevel)
            setCabinIntProperty(0x2140f217, 0, safeLevel)
            setCabinIntProperty(0x2140f216, 0, safeLevel)
            setCabinIntProperty(0x2140f218, 0, safeLevel)
            setCabinIntProperty(0x2140f219, 0, safeLevel)
            AppLogger.d(TAG, "💡 [CAN直控] 氛围灯基准亮度全寄存器复位: bright=$safeLevel (isCoverMode=$isCoverMode)")
        }
    }

    /**
     * CAN 律动直控保底 (仅在 AIDL 不可用时作为降级通道调用)
     * 严禁触碰 0x2140f3e5 (HU_ALC_BRIGHTNESS_SET)，杜绝高频冲刷用户中控基准亮度滑块！
     * 仅写入 0x2140f22e (清零呼吸) 与 0x2141f3d5 area=1 (全车5区物理亮度)，对齐原厂 SmartLightsServer
     */
    fun setAmbientLightRhythmBrightness(level: Int) {
        workerExecutor.execute {
            val safeLevel = level.coerceIn(1, 10)
            // 1. 清零呼吸周期 (0x2140f22e)，保证纯色与律动即时生效
            setCabinIntProperty(0x2140f22e, 0, 0)
            // 2. 写入全车 5 区纯亮度数组 (0x2141f3d5 area=1)
            val brightArray = IntArray(5) { safeLevel }
            setCabinIntArrayProperty(0x2141f3d5, 1, brightArray)
            // 3. 写入仪表台与车门各区物理亮度寄存器 (0x2140f215..0x2140f219)
            setCabinIntProperty(0x2140f215, 0, safeLevel)
            setCabinIntProperty(0x2140f217, 0, safeLevel)
            setCabinIntProperty(0x2140f216, 0, safeLevel)
            setCabinIntProperty(0x2140f218, 0, safeLevel)
            setCabinIntProperty(0x2140f219, 0, safeLevel)
            AppLogger.d(TAG, "💡 [CAN律动保底] 亮度下发: bright=$safeLevel (0x2141f3d5+5区物理寄存器)")
        }
    }

    /**
     * CAN 直控：设置氛围灯颜色色号 (0..63)
     * 写入 0x2140f3e4 (用户主色) 与 0x2141f3d5 area=0 (全车 5 区色号数组)
     * 严禁触碰 0x21400a25 开关信号，彻底避免颜色下发导致开关被异常置为关闭！
     */
    fun setAmbientLightColor(colorIndex: Int) {
        workerExecutor.execute {
            val safeIdx = colorIndex.coerceIn(1, 64)
            lastAmbientLightColorIndex = safeIdx
            val colorArray = IntArray(5) { safeIdx }
            setCabinIntProperty(0x2140f3e4, 0, safeIdx)
            setCabinIntArrayProperty(0x2141f3d5, 0, colorArray)
            AppLogger.i(TAG, "💡 [CAN直控] 氛围灯颜色写入: $safeIdx (1..64, 5区数组)")
        }
    }

    /**
     * CAN 直控：氛围灯全区色+亮联合写入 (写入 0x2141f3d5 area=2, 10位数组: 0..4色号, 5..9亮度)
     */
    fun setAmbientLightColorAndBrightness(colorIndex: Int, level: Int) {
        workerExecutor.execute {
            val safeIdx = colorIndex.coerceIn(1, 64)
            val safeLevel = level.coerceIn(1, 10)
            val comboArray = IntArray(10) { if (it < 5) safeIdx else safeLevel }
            setCabinIntProperty(0x2140f22e, 0, 0)
            setCabinIntProperty(0x2140f3e4, 0, safeIdx)
            setCabinIntProperty(0x2140f3e5, 0, safeLevel)
            setCabinIntArrayProperty(0x2141f3d5, 2, comboArray)
            AppLogger.d(TAG, "💡 [CAN直控] 氛围灯全区色+亮联合写入: color=$safeIdx, bright=$safeLevel")
        }
    }

    /**
     * 释放车机 CarService 资源
     */
    fun release() {
        pendingNRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingNRunnable = null
        workerExecutor.execute {
            try {
                if (carInstance != null && isConnected) {
                    val carClass = carInstance!!.javaClass
                    val disconnectMethod = carClass.getMethod("disconnect")
                    disconnectMethod.invoke(carInstance)
                    AppLogger.i(TAG, "CarService 成功断开连接")
                }
            } catch (t: Throwable) {
                AppLogger.w(TAG, "断开 CarService 异常: ${t.message}")
            } finally {
                isConnected = false
                isInitialized = false
                carInstance = null
                cabinManager = null
                dyCabinManager = null
                mcuManager = null
                sensorManager = null
                hvacManager = null
                cabinCallbackProxy = null
                cabinBatteryCallbackProxy = null
                cabinTempCallbackProxy = null
                cabinPowerCallbackProxy = null
                cabinRpmCallbackProxy = null
                cabinLaneChangeCallbackProxy = null
                cabinTurnSignalCallbackProxy = null
                mcuCallbackProxy = null
                mcuBatteryCallbackProxy = null
                sensorCallbackProxy = null
                cachedGetPropertyIdMethod = null
                cachedGetValueMethod = null
                cachedGetFloatPropertyMethod = null
                cachedGetIntPropertyMethod = null
            }
        }
    }
}
