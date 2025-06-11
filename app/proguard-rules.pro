# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# WebRTC相关配置
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keepnames class org.webrtc.** { *; }

# WebSocket相关配置
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Gson相关配置
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# 保持数据模型类
-keep class com.example.webrtc.model.** { *; }

# OkHttp相关配置
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# PermissionX相关配置
-keep class com.guolindev.permissionx.** { *; }

# Kotlin相关配置
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# 协程相关配置
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Compose相关配置
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# 保持应用程序主要类
-keep public class com.example.webrtc.MainActivity { *; }
-keep public class com.example.webrtc.WebRTCApplication { *; }

# 保持管理器类的公共方法
-keep public class com.example.webrtc.manager.** {
    public <methods>;
}

# 保持服务类
-keep public class com.example.webrtc.service.** { *; }

# JNI相关配置
-keepclasseswithmembernames class * {
    native <methods>;
}

# 枚举相关配置
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable相关配置
-keepclassmembers class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator CREATOR;
}

# 通用Android配置
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 避免混淆泛型
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 保持异常类
-keep public class * extends java.lang.Exception

# 保持R文件中的所有静态字段
-keepclassmembers class **.R$* {
    public static <fields>;
}