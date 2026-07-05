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

# Keep JNI native methods and the class that loads the native library.
# R8 must not rename native methods or the class name used by System.loadLibrary().
-keep class com.termux.terminal.JNI { *; }

# Keep terminal public API that may be accessed via reflection or from app code.
-keep public class com.termux.terminal.TerminalSession {
    public <init>(...);
    public **[] get*(...);
    public void set*(...);
    public ** append*(...);
}
-keep public class com.termux.view.TerminalView {
    public <init>(...);
    public ** get*(...);
    public void set*(...);
}
-keep public class com.termux.view.TerminalRenderer { *; }

# Keep classes used by reflection in terminal-view support code.
-keep class com.termux.view.support.PopupWindowCompatGingerbread { *; }

# OkHttp / Okio: keep rules required for certificate pinning, reflection on
# platform internals, and Kotlin metadata used by Okio.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclasseswithmembers class * {
    @okhttp3.* <methods>;
}

# WebRTC: keep observer interfaces and data-channel classes that are called
# from native code or registered as callbacks.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# org.json: keep public constructors/methods used to parse and build payloads.
-keep class org.json.** { public protected *; }

# Keep WebTerm model classes that are serialized to/from JSON on disk.
-keep class com.webterm.mobile.ServerConfig { *; }
-keep class com.webterm.mobile.ServerConfigDialogHelper$* { *; }
-keep class com.webterm.mobile.TerminalDiskCache$* { *; }
-keep class com.webterm.mobile.CachedSessionMapper$* { *; }

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
