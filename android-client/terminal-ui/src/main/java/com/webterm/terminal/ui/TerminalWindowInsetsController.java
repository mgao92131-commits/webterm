package com.webterm.terminal.ui;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import com.webterm.ui.common.UIUtils;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalRow;
import com.termux.view.TerminalView;

public final class TerminalWindowInsetsController {
    private TerminalWindowInsetsController() {}

    public static void installRootInsets(
        Activity activity,
        View root,
        int baseLeft,
        int baseTop,
        int baseRight,
        int baseBottom,
        boolean avoidImeWithPadding,
        ImeOverlapCallback callback
    ) {
        installRootInsets(activity, root, baseLeft, baseTop, baseRight, baseBottom,
            avoidImeWithPadding, true, callback);
    }

    /**
     * @param includeStatusBar 是否把状态栏 inset 加到 root.paddingTop。
     *        新方案：顶栏自己吸收 statusBar inset（用 {@link UIUtils#installTopbarInset}），
     *        root 这里传 false 避免双重 inset。
     */
    public static void installRootInsets(
        Activity activity,
        View root,
        int baseLeft,
        int baseTop,
        int baseRight,
        int baseBottom,
        boolean avoidImeWithPadding,
        boolean includeStatusBar,
        ImeOverlapCallback callback
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            root.setPadding(baseLeft, baseTop, baseRight, baseBottom);
            return;
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int statusTop = includeStatusBar ? insets.getInsets(WindowInsets.Type.statusBars()).top : 0;
            int navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            int imeOverlap = Math.max(0, imeBottom - navBottom);
            int bottomInset = avoidImeWithPadding && imeBottom > 0 ? imeBottom : navBottom;
            view.setPadding(baseLeft, baseTop + statusTop, baseRight, baseBottom + bottomInset);
            callback.onImeOverlapChanged(imeOverlap);
            return insets;
        });
        if (root.isAttachedToWindow()) {
            root.requestApplyInsets();
        } else {
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    view.removeOnAttachStateChangeListener(this);
                    view.requestApplyInsets();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                }
            });
        }
    }

    /**
     * 单独为顶栏安装 statusBar inset 监听，让 topbar 自己 paddingTop = statusBarHeight。
     * 顶栏背景延伸到屏幕顶端（覆盖状态栏区域），与状态栏图标融为一体。
     */
    public static void installTopbarInset(View topbar) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        topbar.setOnApplyWindowInsetsListener((view, insets) -> {
            int statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top;
            view.setPadding(view.getPaddingLeft(), statusTop, view.getPaddingRight(), view.getPaddingBottom());
            return insets;
        });
        if (topbar.isAttachedToWindow()) {
            topbar.requestApplyInsets();
        } else {
            topbar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    view.removeOnAttachStateChangeListener(this);
                    view.requestApplyInsets();
                }
                @Override
                public void onViewDetachedFromWindow(View view) {}
            });
        }
    }

    public static void updateKeyboardAvoidance(Activity activity, View root, View viewport, View quickBar, TerminalView terminal, int imeOverlap) {
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

    public interface ImeOverlapCallback {
        void onImeOverlapChanged(int imeOverlap);
    }
}
