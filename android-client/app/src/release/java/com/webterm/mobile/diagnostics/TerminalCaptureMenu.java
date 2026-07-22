package com.webterm.mobile.diagnostics;

import android.app.Activity;

import com.webterm.feature.terminal.TerminalScreenBuilder;

import java.util.Collections;
import java.util.List;

/** Release 不含任何现场捕获 UI 入口。 */
public final class TerminalCaptureMenu {
    private TerminalCaptureMenu() {}

    public static List<TerminalScreenBuilder.DebugMenuItem> items(Activity activity) {
        return Collections.emptyList();
    }
}
