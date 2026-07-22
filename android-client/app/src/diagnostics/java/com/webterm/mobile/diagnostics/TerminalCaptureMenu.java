package com.webterm.mobile.diagnostics;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.webterm.feature.terminal.TerminalScreenBuilder;
import com.webterm.terminal.model.capture.CaptureLimits;
import com.webterm.terminal.model.capture.CaptureResult;
import com.webterm.terminal.model.capture.TerminalCapture;
import com.webterm.terminal.model.capture.TerminalCaptureController;

import java.util.ArrayList;
import java.util.List;

/**
 * Debug/Diag 专用：向终端“更多”菜单注入现场捕获入口。release 由同名 stub 取代（返回空列表，
 * 无任何 UI 入口）。菜单项按记录状态动态显示：
 *   未记录：保存当前现场 / 开始现场记录
 *   记录中：保存并结束记录 / 取消现场记录
 */
public final class TerminalCaptureMenu {

    private static final int ID_SAVE_NOW = 9001;
    private static final int ID_START = 9002;
    private static final int ID_FINISH = 9003;
    private static final int ID_CANCEL = 9004;

    private TerminalCaptureMenu() {}

    public static List<TerminalScreenBuilder.DebugMenuItem> items(Activity activity) {
        List<TerminalScreenBuilder.DebugMenuItem> list = new ArrayList<>();
        if (!TerminalCapture.isSupported()) {
            return list;
        }
        TerminalCaptureController controller = TerminalCapture.controller();

        list.add(new TerminalScreenBuilder.DebugMenuItem(ID_SAVE_NOW, "保存当前现场",
                () -> confirmSensitive(activity, () ->
                        controller.saveCurrentScene(result -> onResult(activity, result))),
                () -> !TerminalCapture.isRecording()));

        list.add(new TerminalScreenBuilder.DebugMenuItem(ID_START, "开始现场记录",
                () -> {
                    controller.startCapture(CaptureLimits.defaults());
                    toast(activity, "已开始现场记录");
                },
                () -> !TerminalCapture.isRecording()));

        list.add(new TerminalScreenBuilder.DebugMenuItem(ID_FINISH, "保存并结束记录",
                () -> confirmSensitive(activity, () ->
                        controller.finishCapture(result -> onResult(activity, result))),
                () -> TerminalCapture.isRecording()));

        list.add(new TerminalScreenBuilder.DebugMenuItem(ID_CANCEL, "取消现场记录",
                () -> {
                    controller.cancelCapture();
                    toast(activity, "已取消现场记录");
                },
                () -> TerminalCapture.isRecording()));

        return list;
    }

    private static void onResult(Activity activity, CaptureResult result) {
        if (activity == null || activity.isFinishing()) return;
        if (result.success) {
            if (TerminalCapture.controller() instanceof RealTerminalCaptureController) {
                ((RealTerminalCaptureController) TerminalCapture.controller())
                        .share(activity, result.filePath);
            }
            toast(activity, "现场包已生成");
        } else {
            toast(activity, "现场保存失败: " + (result.error != null ? result.error : "unknown"));
        }
    }

    /** 保存前提示现场包含敏感正文，用户确认后才生成并分享。 */
    private static void confirmSensitive(Activity activity, Runnable onConfirm) {
        if (activity == null || activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle("保存渲染现场")
                .setMessage("现场包可能包含终端输出、命令、路径及其他敏感内容，且未脱敏。确认保存并分享吗？")
                .setPositiveButton("确认保存", (d, w) -> onConfirm.run())
                .setNegativeButton("取消", null)
                .show();
    }

    private static void toast(Activity activity, String message) {
        if (activity == null || activity.isFinishing()) return;
        activity.runOnUiThread(() ->
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }
}
