package com.webterm.mobile;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalRow;
import com.termux.view.TerminalView;

final class TerminalWindowInsetsController {
    private TerminalWindowInsetsController() {}

    static void installRootInsets(
        Activity activity,
        View root,
        int baseLeft,
        int baseTop,
        int baseRight,
        int baseBottom,
        boolean avoidImeWithPadding,
        ImeOverlapCallback callback
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            root.setPadding(baseLeft, baseTop, baseRight, baseBottom);
            return;
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top;
            int navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            int imeOverlap = Math.max(0, imeBottom - navBottom);
            int bottomInset = avoidImeWithPadding && imeBottom > 0 ? imeBottom : navBottom;
            view.setPadding(baseLeft, baseTop + statusTop, baseRight, baseBottom + bottomInset);
            callback.onImeOverlapChanged(imeOverlap);
            return insets;
        });
    }

    static void updateKeyboardAvoidance(Activity activity, View root, View viewport, View quickBar, TerminalView terminal, int imeOverlap) {
        if (quickBar != null) quickBar.setTranslationY(imeOverlap > 0 ? -imeOverlap : 0);
        if (terminal == null) return;
        if (imeOverlap <= 0 || root == null || viewport == null || terminal.mEmulator == null || terminal.mRenderer == null) {
            terminal.setTranslationY(0);
            return;
        }

        terminal.setTopRow(0);
        terminal.onScreenUpdated(true);

        int[] rootLocation = new int[2];
        int[] viewportLocation = new int[2];
        root.getLocationOnScreen(rootLocation);
        viewport.getLocationOnScreen(viewportLocation);

        int lineHeight = terminal.mRenderer.getFontLineSpacing();
        int quickBarHeight = quickBar == null ? UIUtils.dp(activity, 92) : quickBar.getHeight();
        int protectedRow = protectedKeyboardRow(terminal);
        int protectedBottom = viewportLocation[1] - rootLocation[1] + (protectedRow + 1) * lineHeight;
        int quickBarTop = root.getHeight() - root.getPaddingBottom() - imeOverlap - quickBarHeight;
        int neededShift = protectedBottom + UIUtils.dp(activity, 12) - quickBarTop;
        terminal.setTranslationY(-Math.max(0, neededShift));
        terminal.invalidate();
    }

    private static int protectedKeyboardRow(TerminalView terminal) {
        int visibleRows = terminal.mEmulator.mRows;
        int cursorRow = terminal.mEmulator.getCursorRow();
        int lastContentRow = 0;
        TerminalBuffer screen = terminal.mEmulator.getScreen();
        for (int row = visibleRows - 1; row >= 0; row--) {
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            if (!line.isBlank()) {
                lastContentRow = row;
                break;
            }
        }
        return Math.max(0, Math.min(visibleRows - 1, Math.max(cursorRow, lastContentRow)));
    }

    interface ImeOverlapCallback {
        void onImeOverlapChanged(int imeOverlap);
    }
}
