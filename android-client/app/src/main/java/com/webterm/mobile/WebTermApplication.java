package com.webterm.mobile;

import android.app.Application;

import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.mobile.device.WebTermDeviceService;
import com.webterm.mobile.diagnostics.DiagnosticsInstaller;
import com.webterm.mobile.diagnostics.TerminalCaptureInstaller;

import java.util.Map;

import dagger.hilt.android.HiltAndroidApp;

@HiltAndroidApp
public final class WebTermApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticsInstaller.install(this);
        // 现场捕获：debug/diag 安装真实控制器，release 为 NOOP（同名 stub）。
        TerminalCaptureInstaller.install(this);
        CrashReporter.install(this);
        Diagnostics.info("app", "application_started", Map.of("process", "main"));
        WebTermDeviceService.start(this);
    }
}
