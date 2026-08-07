package com.webterm.mobile;

import android.app.Application;

import com.webterm.core.contract.diagnostics.Diagnostics;
import com.webterm.mobile.device.WebTermDeviceService;
import com.webterm.mobile.diagnostics.DiagnosticsInstaller;

import java.util.Map;

import dagger.hilt.android.HiltAndroidApp;

@HiltAndroidApp
public final class WebTermApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DiagnosticsInstaller.install(this);
        CrashReporter.install(this);
        Diagnostics.info("app", "application_started", Map.of("process", "main"));
        WebTermDeviceService.start(this);
    }
}
