# ==============================================================================
# 小欧屏 (XiaoOuScreen) - ProGuard / R8 高强度混淆与安全加密配置
# ==============================================================================

# 基础优化参数
-repackageclasses ''
-allowaccessmodification
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ==================== 数据模型与实体序列化 (保留字段防止反序列化异常) ====================
-keep class com.car.xiaoouscreen.data.** { *; }
-keepclassmembers class com.car.xiaoouscreen.data.** { *; }
-keep class com.car.xiaoouscreen.ui.settings.model.** { *; }
-keepclassmembers class com.car.xiaoouscreen.ui.settings.model.** { *; }
-keep class com.carlyrics.model.** { *; }
-keepclassmembers class com.carlyrics.model.** { *; }
-keep class com.carlyrics.lyrics.** { *; }
-keepclassmembers class com.carlyrics.lyrics.** { *; }
-keepclassmembers class com.carlyrics.skin.SkinType { *; }

# ==================== 车机 AIDL 与系统底层通信接口 ====================
-keep interface com.chinatsp.threescreenmiddleservice.** { *; }
-keep class com.chinatsp.threescreenmiddleservice.** { *; }
-keep class com.chinatsp.music.** { *; }
-keepclassmembers class com.chinatsp.music.** { *; }
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keep class * extends android.os.IInterface { *; }
-dontwarn android.car.**
-keep class android.car.** { *; }

# ==================== 车机档位与车身信号 (CarGearManager / CarCallManager / 动态代理回调) ====================
-keep class com.car.xiaoouscreen.car.** { *; }
-keepclassmembers class com.car.xiaoouscreen.car.** { *; }

# ==================== JS 引擎宿主桥接 (LyricsSourceRunner 暴露给 JS 的 Java 桥接方法与沙箱白名单) ====================
-keep class com.carlyrics.lyrics.LyricsSourceRunner$* { *; }
-keepclassmembers class com.carlyrics.lyrics.LyricsSourceRunner$* { *; }

# ==================== 工具类与日志管理器 ====================
-keep class com.carlyrics.util.** { *; }
-keepclassmembers class com.carlyrics.util.** { *; }

# ==================== Retrofit 2 & OkHttp 3 & Gson 混淆保护 ====================
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}

# 必须保留泛型签名与异常，避免 Retrofit 配合 Kotlin suspend 协程时抛出:
# java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType
-keepattributes Signature,Exceptions,*Annotation*,InnerClasses,EnclosingMethod
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface kotlin.coroutines.Continuation

# 保留业务 API 接口和数据模型
-keep interface com.car.xiaoouscreen.data.** { *; }
-keep class com.car.xiaoouscreen.data.** { *; }
-keepclassmembers class com.car.xiaoouscreen.data.** { *; }
-keepclassmembers interface com.car.xiaoouscreen.data.** { *; }

-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

-dontwarn com.google.gson.**
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.Expose <fields>;
}

# ==================== Rhino JS 歌词脚本引擎 ====================
-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.javascript.** { *; }
-keepclassmembers class org.mozilla.javascript.** { *; }

# ==================== Jetpack Compose 混淆保护 ====================
-keepclassmembers class * extends androidx.compose.ui.Modifier { *; }
-dontwarn androidx.compose.**

# ==================== Glide 图片加载与 Palette 调色板 ====================
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl { *; }
-dontwarn com.bumptech.glide.**
-keep class androidx.palette.graphics.** { *; }

# ==================== AndroidX 基础组件 & 协程 ====================
-dontwarn androidx.**
-keep class androidx.media.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ==================== 枚举安全保留 ====================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
