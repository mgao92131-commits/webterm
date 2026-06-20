package com.webterm.mobile;

import android.app.Application;

public final class WebTermApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashReporter.install(this);
    }
}
