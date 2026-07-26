# WebTerm Android release ProGuard / R8 rules
# These rules keep the minimum surface required for the app to work correctly
# under code shrinking and obfuscation.

# Preserve line numbers and source file names so crash stack traces are usable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep the Application entry point and its onCreate.
-keep public class com.webterm.mobile.WebTermApplication {
    public <init>();
    public void onCreate();
}

# Keep Android components referenced from the manifest.
-keep public class com.webterm.mobile.ui.MainActivity {
    public <init>();
}

# OkHttp 自带 consumer R8 规则；不要整包 keep OkHttp/Okio，否则会把未使用的
# HTTP、缓存、HTTP/2 和平台适配实现全部打进 Release。
-dontwarn okhttp3.**
-dontwarn okio.**

# org.json: keep public constructors/methods used to parse and build payloads.
-keep class org.json.** { public protected *; }

# Keep WebTerm model classes that are serialized to/from JSON on disk.
-keep class com.webterm.core.config.ServerConfig { *; }
-keep class com.webterm.core.cache.TerminalDiskCache$* { *; }
-keep class com.webterm.core.cache.CachedSessionMapper$* { *; }

# Keep callback / listener interfaces that may be implemented as anonymous
# inner classes and passed across boundaries.
-keepclassmembers class * {
    *** *Callback;
    *** *Listener;
    *** *Observer;
}

# Keep enum values (e.g. MainActivity.ScreenMode, item types) intact.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable 由 Android 框架按 CREATOR 字段访问。
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
