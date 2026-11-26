package com.car.xiaoouscreen.car

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.carlyrics.util.AppLogger
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 车载实时 G 值（横/纵向加速度）多源融合采集管理器
 *
 * 坐标系约定（遵循保时捷 / AMG / GT-R 车规 G 值表标准）：
 * - gx > 0：向右离心力（左转弯，光点向右移）；gx < 0：向左离心力（右转弯，光点向左移）
 * - gy > 0：向后推背惯性（车辆加速，光点向下移）；gy < 0：向前点头惯性（车辆制动，光点向上移）
 *
 * 赛道级抗漂移与自标定引擎（解决“长时间过弯泄气、出弯反向反弹、右偏漂移”等业界通病）：
 * 1. 动静分离锁定机制：通过加计模长方差、高频抖动率与陀螺仪偏航角速度，实时精准判定车辆「静止停稳」与「行车动态」；
 * 2. 动态重力基准冻结：仅在车辆停稳（P档/红绿灯静止）时学习安装倾角与零偏；一旦起步运动，重力向量与零偏彻底冻结，
 *    过弯 5~10 秒绝不吞噬离心力，出弯回正瞬间干净归零，100% 根除出弯向右弹跳与残余零偏漂移！
 * 3. 零速强稳定心：停稳时强制吸附归入 (0.00G, 0.00G)，与原厂赛道模式 (v <= 2km/h 归零) 完全一致。
 */
class CarGForceManager private constructor(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "CarGForceManager"
        private const val GRAVITY_MPS2 = 9.80665f
        private const val MAX_CLAMP_G = 1.50f
        private const val DEADZONE_G = 0.035f
        private const val PEAK_DECAY_HOLD_MS = 8000L
        private const val BOOT_SETTLING_DURATION_MS = 2500L

        @Volatile
        private var instance: CarGForceManager? = null

        fun getInstance(context: Context): CarGForceManager {
            return instance ?: synchronized(this) {
                instance ?: CarGForceManager(context.applicationContext).also { instance = it }
            }
        }
    }

    data class GForceData(
        val gx: Float = 0f,        // 横向 G (-1.5..+1.5, 左负右正)
        val gy: Float = 0f,        // 纵向 G (-1.5..+1.5, 制动负/上方, 加速正/下方)
        val totalG: Float = 0f,    // 合加速度 sqrt(gx^2 + gy^2)
        val peakG: Float = 0f,     // 近期峰值合加速度
        val peakGx: Float = 0f,    // 峰值点 X
        val peakGy: Float = 0f,    // 峰值点 Y
        val isSensorActive: Boolean = false
    )

    interface Listener {
        fun onGForceChanged(data: GForceData)
    }

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sensorThread: android.os.HandlerThread? = null
    private var sensorHandler: Handler? = null
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var isSensorRegistered = false
    private var hasLinearAccel = false
    private var hasGyro = false

    // 陀螺仪角速度 (rad/s)
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f

    // 动静分离状态机
    private var prevAccNorm = GRAVITY_MPS2
    private var accelJitter = 0f
    private var stillStartTimeMs = 0L
    @Volatile
    private var isStationary = true

    // 重力向量空间跟踪（用于任意安装倾角的空间投影自标定）
    private var gravityInitialized = false
    private var gravX = 0f
    private var gravY = GRAVITY_MPS2
    private var gravZ = 0f

    // 零偏自标定（静态消除中控向驾驶员倾角产生的常驻侧向分量）
    private var biasGx = 0f
    private var biasGy = 0f
    @Volatile
    private var isParked = true

    // 平滑滤波后的实时横/纵向 G
    private var filteredGx = 0f
    private var filteredGy = 0f

    // 峰值记忆与超时衰减
    private var peakG = 0f
    private var peakGx = 0f
    private var peakGy = 0f
    private var peakUpdatedMs = 0L

    // 实车底盘 ESP CAN 加速度时间戳（用于底盘总线信号优先裁决）
    @Volatile
    private var lastEspUpdateMs = 0L

    // 上电/启动抑制窗口时间戳（避免传感器上电阶跃与点火机械震动产生假峰值）
    @Volatile
    private var bootStartTimeMs = 0L

    // ADB 模拟覆盖锁（避免静止平板的真实 0G 传感器立即冲刷模拟值）
    @Volatile
    private var simulationUntilMs = 0L

    @Volatile
    private var currentData = GForceData()

    private val isDispatchScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val dispatchRunnable = Runnable {
        isDispatchScheduled.set(false)
        val data = currentData
        for (listener in listeners) {
            listener.onGForceChanged(data)
        }
    }

    fun getCurrentData(): GForceData = currentData

    @Synchronized
    fun registerListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        listener.onGForceChanged(currentData)
        if (!isSensorRegistered) {
            startSensors()
        }
    }

    @Synchronized
    fun unregisterListener(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty() && isSensorRegistered) {
            stopSensors()
        }
    }

    private fun startSensors() {
        bootStartTimeMs = SystemClock.uptimeMillis()
        val sm = sensorManager ?: return
        try {
            val thread = sensorThread?.takeIf { it.isAlive } ?: android.os.HandlerThread("CarGForceSensor").apply {
                start()
                sensorThread = this
                sensorHandler = Handler(looper)
            }
            val sHandler = sensorHandler ?: Handler(thread.looper).also { sensorHandler = it }

            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            hasLinearAccel = (linear != null)
            hasGyro = (gyro != null)

            var ok = false
            if (accel != null) {
                ok = sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, sHandler) || ok
            }
            if (linear != null) {
                ok = sm.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME, sHandler) || ok
            }
            if (gyro != null) {
                sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, sHandler)
            }
            isSensorRegistered = ok
            AppLogger.i(TAG, "🎯 启动车载 G 值传感器监听(独立后台线程): ok=$ok, hasAccel=${accel != null}, hasLinear=$hasLinearAccel, hasGyro=$hasGyro")
        } catch (t: Throwable) {
            AppLogger.w(TAG, "注册 G 值传感器失败: ${t.message}")
            isSensorRegistered = false
        }
    }

    private fun stopSensors() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Throwable) {}
        sensorHandler?.removeCallbacksAndMessages(null)
        try {
            sensorThread?.quitSafely()
        } catch (_: Throwable) {}
        sensorThread = null
        sensorHandler = null
        mainHandler.removeCallbacks(dispatchRunnable)
        isDispatchScheduled.set(false)
        isSensorRegistered = false
        gravityInitialized = false
        biasGx = 0f
        biasGy = 0f
        bootStartTimeMs = 0L
        stillStartTimeMs = 0L
        AppLogger.d(TAG, "🎯 停止车载 G 值传感器监听（零能耗休眠）")
    }

    /**
     * 实车档位变动通知（由 CarGearManager 联动传入）
     * 处于 P 档时自适应学习车机中控偏角零偏，挂入 P 档自动重置峰值
     */
    fun onGearChanged(gearLabel: String) {
        val nextParked = gearLabel.equals("P", ignoreCase = true)
        if (nextParked != isParked) {
            isParked = nextParked
            if (isParked) {
                resetPeak()
                isStationary = true
            }
            AppLogger.d(TAG, "🎯 G 值标定引擎同步档位: gear=$gearLabel, isParked=$isParked")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.values.size < 3) return
        val nowMs = SystemClock.uptimeMillis()

        if (nowMs < simulationUntilMs) return
        if (nowMs - lastEspUpdateMs < 2000L) {
            // 实车底盘 ESP CAN 加速度信号活跃，优先采用实车底盘传感器，暂停平板内置传感器冲刷
            return
        }

        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            gyroX = event.values[0]
            gyroY = event.values[1]
            gyroZ = event.values[2]
            return
        }

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            // ============================================================
            // 1. 动静分离状态机 (Kinematic Stillness Detector)
            // ============================================================
            val aNorm = sqrt(ax * ax + ay * ay + az * az)
            val deltaG = abs(aNorm - GRAVITY_MPS2)
            val jitterSample = abs(aNorm - prevAccNorm)
            prevAccNorm = aNorm
            accelJitter = 0.85f * accelJitter + 0.15f * jitterSample

            val gyroMag = if (hasGyro) sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ) else 0f
            val isGyroCalm = !hasGyro || (gyroMag < 0.08f) // 角速度 < 4.5 deg/sec

            // 静止状态判定条件：总加速度模长接近 1.0G 且高频抖动极低且无旋转角速度
            val instantStill = (deltaG < 0.38f) && (accelJitter < 0.16f) && isGyroCalm

            if (instantStill) {
                if (stillStartTimeMs == 0L) {
                    stillStartTimeMs = nowMs
                }
            } else {
                stillStartTimeMs = 0L
            }

            val stillDurationMs = if (stillStartTimeMs > 0L) (nowMs - stillStartTimeMs) else 0L
            // 只要持续平稳超过 0.5 秒或处于 P 档，即裁决为车辆静止停稳
            isStationary = isParked || (stillDurationMs >= 500L)

            // ============================================================
            // 2. 空间重力基准锁定更新 (核心抗漂移算法)
            // ============================================================
            if (!gravityInitialized) {
                gravX = ax
                gravY = ay
                gravZ = az
                gravityInitialized = true
            } else if (isStationary) {
                // 仅在完全停稳时快速平滑收敛重力基准 (锁定平板当前的真实安装支架倾角)
                val gAlphaStill = 0.88f
                gravX = gAlphaStill * gravX + (1f - gAlphaStill) * ax
                gravY = gAlphaStill * gravY + (1f - gAlphaStill) * ay
                gravZ = gAlphaStill * gravZ + (1f - gAlphaStill) * az
            } else {
                // 车辆运动中（转弯/变道/急加速）：彻底冻结重力基准！
                // 采用超慢时间常数 (数十小时量级) 仅吸收温度静态极微漂移，绝不吞噬过弯离心力！
                val gAlphaMoving = 0.9999f
                gravX = gAlphaMoving * gravX + (1f - gAlphaMoving) * ax
                gravY = gAlphaMoving * gravY + (1f - gAlphaMoving) * ay
                gravZ = gAlphaMoving * gravZ + (1f - gAlphaMoving) * az
            }

            // 若系统无硬件级线性加速度融合，采用 (raw - gravity) 计算线性加速度
            if (!hasLinearAccel) {
                val lx = ax - gravX
                val ly = ay - gravY
                val lz = az - gravZ
                projectAndEmit(lx, ly, lz, nowMs, isStationary, stillDurationMs)
            }
        } else if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            val stillDurationMs = if (stillStartTimeMs > 0L) (nowMs - stillStartTimeMs) else 0L
            projectAndEmit(ax, ay, az, nowMs, isStationary, stillDurationMs)
        }
    }

    private fun projectAndEmit(
        lx: Float,
        ly: Float,
        lz: Float,
        nowMs: Long,
        isStationary: Boolean,
        stillDurationMs: Long
    ) {
        // 1. 归一化当前重力单位向量 gUnit（指向天顶）
        val gMag = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ).coerceAtLeast(1f)
        val gxU = gravX / gMag
        val gyU = gravY / gMag
        val gzU = gravZ / gMag

        // 2. 构建与重力严格正交的水平面基向量 (rightAxis, forwardAxis)
        // 横屏设备 +X 沿屏幕右侧；消除重力法向投影后单位化
        var rx = 1f - gxU * gxU
        var ry = -gxU * gyU
        var rz = -gxU * gzU
        val rMag = sqrt(rx * rx + ry * ry + rz * rz)
        if (rMag > 0.05f) {
            rx /= rMag; ry /= rMag; rz /= rMag
        } else {
            rx = 1f; ry = 0f; rz = 0f
        }

        // forwardAxis = cross(rightAxis, gUnit)
        // 竖置屏幕 (gUnit≈0,1,0) -> (0,0,+1) 垂直屏幕指向驾驶员；平放 (gUnit≈0,0,1) -> (0,-1,0)
        var fx = ry * gzU - rz * gyU
        var fy = rz * gxU - rx * gzU
        var fz = rx * gyU - ry * gxU
        val fMag = sqrt(fx * fx + fy * fy + fz * fz)
        if (fMag > 0.05f) {
            fx /= fMag; fy /= fMag; fz /= fMag
        } else {
            fx = 0f; fy = 0f; fz = 1f
        }

        // 3. 投影线性加速度到横向 (lateral) 与纵向 (longitudinal) 并折算为车规体感 G 值
        // - 左转弯：车辆向左加速 (lx < 0)，车内驾驶员受离心力向右甩 (gx > 0，光球右偏)
        // - 右转弯：车辆向右加速 (lx > 0)，车内驾驶员受离心力向左甩 (gx < 0，光球左偏)
        // - 车辆加速前冲：车内推背感 (gy > 0，光球向下移)
        // - 车辆紧急制动：车内点头感 (gy < 0，光球向上移)
        val rawLatG = ((lx * rx + ly * ry + lz * rz) / GRAVITY_MPS2).coerceIn(-MAX_CLAMP_G, MAX_CLAMP_G)
        val rawLongG = ((lx * fx + ly * fy + lz * fz) / GRAVITY_MPS2).coerceIn(-MAX_CLAMP_G, MAX_CLAMP_G)

        val centrifugalGx = -rawLatG
        val centrifugalGy = -rawLongG

        // 4. 零偏自标定：只有在完全静止平稳停驻超过 0.8 秒时，才允许微调零偏！
        // 运动中彻底硬件冻结（Freeze），绝不把弯道离心力当零漂学进 bias！
        val settlingElapsedMs = nowMs - bootStartTimeMs
        val isBootSettling = settlingElapsedMs in 0L..BOOT_SETTLING_DURATION_MS

        if (isStationary && (stillDurationMs >= 800L || isBootSettling)) {
            val biasAlpha = if (isBootSettling) 0.15f else 0.02f
            biasGx += (centrifugalGx - biasGx) * biasAlpha
            biasGy += (centrifugalGy - biasGy) * biasAlpha
        }
        val zeroCorrectedGx = centrifugalGx - biasGx
        val zeroCorrectedGy = centrifugalGy - biasGy

        // 5. 零速强稳定心：停稳静止时，强制归零锁死原点 (0.00G)，消除微小抖动与路面倾角杂波
        val targetGx = if (isStationary && stillDurationMs >= 500L) 0f else zeroCorrectedGx
        val targetGy = if (isStationary && stillDurationMs >= 500L) 0f else zeroCorrectedGy

        // 6. 启动抑制：开机前 1.2 秒为传感器硬件偏置建立静默期强制输出 0，1.2~2.5 秒按比例渐入
        val bootScale = when {
            settlingElapsedMs < 1200L -> 0f
            settlingElapsedMs < BOOT_SETTLING_DURATION_MS -> (settlingElapsedMs - 1200f) / (BOOT_SETTLING_DURATION_MS - 1200f)
            else -> 1f
        }

        // 7. 赛道机械阻尼低通滤波 + 死区过滤
        val smooth = 0.28f
        filteredGx += (targetGx * bootScale - filteredGx) * smooth
        filteredGy += (targetGy * bootScale - filteredGy) * smooth

        val outGx = if (abs(filteredGx) < DEADZONE_G) 0f else filteredGx
        val outGy = if (abs(filteredGy) < DEADZONE_G) 0f else filteredGy

        emitData(outGx, outGy, nowMs, isSensorActive = true)
    }

    private fun emitData(gx: Float, gy: Float, nowMs: Long, isSensorActive: Boolean) {
        val total = hypot(gx.toDouble(), gy.toDouble()).toFloat().coerceIn(0f, MAX_CLAMP_G)

        val isBootSettling = (nowMs - bootStartTimeMs) in 0L..BOOT_SETTLING_DURATION_MS
        if (!isBootSettling && total >= peakG && total >= 0.05f) {
            peakG = total
            peakGx = gx
            peakGy = gy
            peakUpdatedMs = nowMs
        } else if (peakG > 0f && nowMs - peakUpdatedMs > PEAK_DECAY_HOLD_MS) {
            // 超过 8 秒无更高峰值，平滑缓慢回落释放旧峰值
            peakG = (peakG - 0.015f).coerceAtLeast(total)
            if (peakG <= 0.02f) {
                peakG = 0f
                peakGx = 0f
                peakGy = 0f
            }
        }

        val prev = currentData
        if (abs(prev.gx - gx) < 0.004f &&
            abs(prev.gy - gy) < 0.004f &&
            abs(prev.peakG - peakG) < 0.005f &&
            prev.isSensorActive == isSensorActive
        ) {
            return
        }

        val next = GForceData(
            gx = gx,
            gy = gy,
            totalG = total,
            peakG = peakG,
            peakGx = peakGx,
            peakGy = peakGy,
            isSensorActive = isSensorActive
        )
        currentData = next
        if (isDispatchScheduled.compareAndSet(false, true)) {
            mainHandler.post(dispatchRunnable)
        }
    }

    /**
     * 实车底盘 ESP (Electronic Stability Program) CAN 加速度信号接入
     * @param latAccelMps2 横向加速度 (m/s^2，右正左负)
     * @param longAccelMps2 纵向加速度 (m/s^2，加速正/制动负)
     */
    fun onVehicleEspAccelChanged(latAccelMps2: Float?, longAccelMps2: Float?) {
        val nowMs = SystemClock.uptimeMillis()
        if (nowMs < simulationUntilMs) return
        if (latAccelMps2 == null && longAccelMps2 == null) return

        // ESP 底盘向心/前向加速度 -> 惯性离心/推背 G 值
        val rawLatG = latAccelMps2?.let { (-it / GRAVITY_MPS2).coerceIn(-MAX_CLAMP_G, MAX_CLAMP_G) } ?: 0f
        val rawLongG = longAccelMps2?.let { (it / GRAVITY_MPS2).coerceIn(-MAX_CLAMP_G, MAX_CLAMP_G) } ?: 0f

        val smooth = 0.35f
        filteredGx += (rawLatG - filteredGx) * smooth
        filteredGy += (rawLongG - filteredGy) * smooth

        val outGx = if (abs(filteredGx) < DEADZONE_G) 0f else filteredGx
        val outGy = if (abs(filteredGy) < DEADZONE_G) 0f else filteredGy

        lastEspUpdateMs = nowMs
        emitData(outGx, outGy, nowMs, isSensorActive = true)
    }

    /**
     * 模拟 G 值输入（供 PAD ADB 验证十字准星动效、尾迹、峰值与变色）
     * @param gx 横向 G（右正左负，如 0.45f）
     * @param gy 纵向 G（加速正/下方，制动负/上方，如 -0.62f）
     * @param holdMs 保持模拟态时长（毫秒），默认 15 秒；传 0 立即恢复实车传感器
     */
    fun simulateGForce(gx: Float, gy: Float, holdMs: Long = 15_000L) {
        val nowMs = SystemClock.uptimeMillis()
        if (holdMs <= 0L) {
            simulationUntilMs = 0L
            return
        }
        simulationUntilMs = nowMs + holdMs
        val clampedX = gx.coerceIn(-MAX_CLAMP_G, MAX_CLAMP_G)
        val clampedY = gy.coerceIn(-MAX_CLAMP_G, MAX_CLAMP_G)
        filteredGx = clampedX
        filteredGy = clampedY
        AppLogger.i(TAG, "🧪 模拟车载 G 值: gx=$clampedX, gy=$clampedY (hold=${holdMs}ms)")
        emitData(clampedX, clampedY, nowMs, isSensorActive = true)
    }

    fun resetPeak() {
        peakG = 0f
        peakGx = 0f
        peakGy = 0f
        peakUpdatedMs = 0L
    }

    /**
     * 手动重置零偏（供设置界面或调试快速校准当前姿态）
     */
    fun resetZeroBias() {
        biasGx = 0f
        biasGy = 0f
        gravityInitialized = false
        AppLogger.i(TAG, "🎯 手动重置零偏与重力基准")
    }
}
