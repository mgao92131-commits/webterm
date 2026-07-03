# terminal-view consumer ProGuard / R8 rules
# These rules are merged into the app when R8 shrinks a release build.

# Keep the public TerminalView surface used by host apps and XML layouts.
-keep public class com.termux.view.TerminalView {
    public <init>(...);
    public ** get*(...);
    public void set*(...);
}

# Keep renderer and selection classes that may be referenced reflectively.
-keep public class com.termux.view.TerminalRenderer { *; }
-keep class com.termux.view.textselection.** { *; }

# Keep reflection-based compatibility helper.
-keep class com.termux.view.support.PopupWindowCompatGingerbread { *; }
