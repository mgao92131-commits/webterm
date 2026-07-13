package com.webterm.mobile;

import android.app.Application;

import com.webterm.mobile.device.WebTermDeviceService;

import dagger.hilt.android.HiltAndroidApp;

@HiltAndroidApp
public final class WebTermApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashReporter.install(this);
        WebTermDeviceService.start(this);
    }
}
