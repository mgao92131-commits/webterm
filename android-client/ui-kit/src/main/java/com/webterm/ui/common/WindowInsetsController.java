package com.webterm.ui.common;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

/** Common system-bar and IME inset handling; independent of any terminal implementation. */
public final class WindowInsetsController {
    private WindowInsetsController() {}

    public interface ImeOverlapCallback {
        void onImeOverlapChanged(int imeOverlap);
    }

    public static void installRootInsets(Activity activity, View root, int baseLeft, int baseTop,
                                         int baseRight, int baseBottom, boolean avoidImeWithPadding,
                                         ImeOverlapCallback callback) {
        installRootInsets(activity, root, baseLeft, baseTop, baseRight, baseBottom,
            avoidImeWithPadding, true, callback);
    }

    public static void installRootInsets(Activity activity, View root, int baseLeft, int baseTop,
                                         int baseRight, int baseBottom, boolean avoidImeWithPadding,
                                         boolean includeStatusBar, ImeOverlapCallback callback) {
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
        requestInsetsWhenAttached(root);
    }

    public static void installTopbarInset(View topbar) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        topbar.setOnApplyWindowInsetsListener((view, insets) -> {
            int statusTop = insets.getInsets(WindowInsets.Type.statusBars()).top;
            view.setPadding(view.getPaddingLeft(), statusTop, view.getPaddingRight(), view.getPaddingBottom());
            return insets;
        });
        requestInsetsWhenAttached(topbar);
    }

    private static void requestInsetsWhenAttached(View view) {
        if (view.isAttachedToWindow()) {
            view.requestApplyInsets();
        } else {
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View attached) {
                    attached.removeOnAttachStateChangeListener(this);
                    attached.requestApplyInsets();
                }
                @Override public void onViewDetachedFromWindow(View detached) {}
            });
        }
    }
}
