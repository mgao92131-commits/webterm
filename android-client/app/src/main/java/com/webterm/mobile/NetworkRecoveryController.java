package com.webterm.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;

final class NetworkRecoveryController {
    private static final String TAG = "NetworkRecoveryController";
    private static final long RECOVERY_DEBOUNCE_MS = 2000L;

    private final Context context;
    private final Handler mainHandler;
    private final Host host;
    private ConnectivityManager.NetworkCallback networkCallback;
    private long lastNetworkAvailableTime;

    NetworkRecoveryController(Context context, Handler mainHandler, Host host) {
        this.context = context.getApplicationContext();
        this.mainHandler = mainHandler;
        this.host = host;
    }

    void register() {
        if (networkCallback != null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                mainHandler.post(() -> {
                    long now = System.currentTimeMillis();
                    if (now - lastNetworkAvailableTime <= RECOVERY_DEBOUNCE_MS) return;
                    lastNetworkAvailableTime = now;
                    Log.i(TAG, "Network available: trigger recovery");
                    host.onNetworkAvailableForRecovery();
                });
            }
        };
        cm.registerDefaultNetworkCallback(networkCallback);
    }

    void unregister() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || networkCallback == null) return;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            cm.unregisterNetworkCallback(networkCallback);
        }
        networkCallback = null;
    }

    interface Host {
        void onNetworkAvailableForRecovery();
    }
}
