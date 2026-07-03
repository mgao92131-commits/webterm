# terminal-emulator consumer ProGuard / R8 rules
# These rules are merged into the app when R8 shrinks a release build.

# Keep native methods and the JNI loader class.
-keep class com.termux.terminal.JNI { *; }

# Keep public terminal API used by host apps.
-keep public class com.termux.terminal.TerminalSession {
    public <init>(...);
    public **[] get*(...);
    public void set*(...);
    public ** append*(...);
    public ** write*(...);
}
-keep public class com.termux.terminal.TerminalEmulator { public *; }
-keep public class com.termux.terminal.TerminalBuffer { public *; }
-keep public class com.termux.terminal.TerminalRow { public *; }

# Keep callback interfaces that may be implemented anonymously.
-keep interface com.termux.terminal.TerminalSession$* { *; }
