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

# OkHttp / Okio: keep rules required for certificate pinning, reflection on
# platform internals, and Kotlin metadata used by Okio.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclasseswithmembers class * {
    @okhttp3.* <methods>;
}

# org.json: keep public constructors/methods used to parse and build payloads.
-keep class org.json.** { public protected *; }

# Keep WebTerm model classes that are serialized to/from JSON on disk.
-keep class com.webterm.core.config.ServerConfig { *; }
-keep class com.webterm.mobile.ui.dialog.ServerConfigDialogHelper$* { *; }
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

# Keep Parcelable / Serializable implementations if any are added later.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * implements java.io.Serializable { *; }

