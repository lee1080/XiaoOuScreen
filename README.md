# 车载超宽副屏 (1920×384) 气象动态背景与大气融合时钟组件

> **针对硬件**：联发科 MT6771 (Mali-G72 GPU) / Android 9.0 (API 28) 欧尚车机定制  
> **设计规格**：标准画布 1920 × 384 px，左右物理安全黑边各 130px  
> **数据源**：Open-Meteo API (`api.open-meteo.com`) 秒级 Unix 时间戳气象预报  

---

## 1. 屏幕物理空间分区图 (1920 × 384)

```text
 0px          130px                    700px                    1400px              1790px       1920px
┌──────────────┬────────────────────────┬────────────────────────┬────────────────────┬──────────────┐
│  左侧黑边渐变  │    大气数字时钟与日期   │   [预留插槽]            │   天体/太阳/月亮    │ 右侧黑边渐变  │
│  (Vignette)  │    天气简要徽章         │   车机档位 (P/R/N/D)   │   雷暴电闪主干区    │ (Vignette)   │
│  0 -> 130px  │    x = 240px           │   驾驶模式 / 电话状态   │   x = 1520px       │ 1790->1920px │
└──────────────┴────────────────────────┴────────────────────────┴────────────────────┴──────────────┘
```

---

## 2. 核心架构与模块清单

| 模块 | 文件路径 | 职责 |
|---|---|---|
| **Global Rules** | [`.cursorrules`](file:///Users/liyafei/Desktop/liyafei/车机研究/car_weather_clock/.cursorrules) | 锁死 1920×384 绝对坐标系、130px 黑边、MT6771 零 GC 掉帧红线 |
| **Domain Layer** | [`WeatherSceneConfig.kt`](file:///Users/liyafei/Desktop/liyafei/车机研究/car_weather_clock/src/main/java/com/car/weather/domain/WeatherSceneConfig.kt) | 6 种气象状态 × 4 昼夜时段状态配置，解耦光照与粒子参数 |
| **Domain Layer** | [`WeatherSceneMapper.kt`](file:///Users/liyafei/Desktop/liyafei/车机研究/car_weather_clock/src/main/java/com/car/weather/domain/WeatherSceneMapper.kt) | WMO 28+代码转换，日出日落 ±45 分钟（2700秒）精准判定拂晓与落日 |
| **Data Layer** | [`OpenMeteoModels.kt`](file:///Users/liyafei/Desktop/liyafei/车机研究/car_weather_clock/src/main/java/com/car/weather/data/OpenMeteoModels.kt) | 秒级 Unix 时间戳 DTO (`timeformat=unixtime`) |
| **Data Layer** | [`OpenMeteoApi.kt`](file:///Users/liyafei/Desktop/liyafei/车机研究/car_weather_clock/src/main/java/com/car/weather/data/OpenMeteoApi.kt) | Retrofit 声明，免时区干扰 |
| **Data Layer** | [`OpenMeteoRepository.kt`](file:///Users/liyafei/Desktop/liyafei/车机研究/car_weather_clock/src/main/java/com/car/weather/data/OpenMeteoRepository.kt) | 本地持久化缓存兜底（过隧道、地库断网无缝保持渲染） |
| **Render View** | [`CarWeatherCanvasView.kt`](file:///Users/liyafei/Desktop/liyafei/车机研究/car_weather_clock/src/main/java/com/car/weather/ui/CarWeatherCanvasView.kt) | 5 层原生 Canvas 高性能渲染引擎，零对象分配，1.5s 状态 Cross-Fade |
| **Location** | [`CarGpsManager.kt`](file:///Users/liyafei/Desktop/liyafei/车机研究/car_weather_clock/src/main/java/com/car/weather/location/CarGpsManager.kt) | 车载 GPS 定位及最后已知坐标降级管理 |
| **State ViewModel**| [`WeatherClockViewModel.kt`](file:///Users/liyafei/Desktop/liyafei/车机研究/car_weather_clock/src/main/java/com/car/weather/ui/WeatherClockViewModel.kt) | 15分钟天气轮询 + 1分钟昼夜跨界无感知自检 |
| **Activity/UI** | [`CarSubscreenActivity.kt`](file:///Users/liyafei/Desktop/liyafei/车机研究/car_weather_clock/src/main/java/com/car/weather/ui/CarSubscreenActivity.kt) | 沉浸式全屏与点击切换 130px 辅助线调试 |

---

## 3. MT6771 车机芯片性能保障机制

1. **`onDraw` 绝对零 GC 产生**：
   - 所有 `Paint`、`Path`、`RectF`、`LinearGradient` 均在构造或初始时分配完毕。
   - 时间格式化复用固定 `Date` 与毫秒缓存，每秒仅更新一次字符串。
2. **粒子对象池复用 (Object Pooling)**：
   - 雨滴 (160) 与雪花 (110) 采用固定数组复用，越界直接回到顶部重置属性，绝无内存碎片。
3. **精准生命周期能耗冻结**：
   - 监听 `onVisibilityAggregated` 与 `onWindowVisibilityChanged`，全屏遮挡或熄屏时注销 `Choreographer`，CPU/GPU 占用立即归零。
