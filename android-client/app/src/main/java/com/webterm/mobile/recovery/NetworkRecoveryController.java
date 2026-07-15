package com.webterm.mobile.recovery;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;

import androidx.lifecycle.LiveData;

import com.webterm.ui.common.SingleLiveEvent;

/**
 * Monitors network connectivity and notifies observers via LiveData.
 * Replaces the old callback-based Host pattern.
 *
 * Usage:
 *   NetworkRecoveryController controller = new NetworkRecoveryController(context, handler);
 *   controller.getOnNetworkAvailable().observe(lifecycleOwner, v -> { ... });
 *   controller.register();
 *   // ... later ...
 *   controller.unregister();
 */
public final class NetworkRecoveryController {

    private final Context context;
    private final Handler handler;
    private final SingleLiveEvent<Void> onNetworkAvailable = new SingleLiveEvent<>();

    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean registered;

    public NetworkRecoveryController(Context context, Handler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    public LiveData<Void> getOnNetworkAvailable() {
        return onNetworkAvailable;
    }

    public void register() {
        if (registered) return;
        registered = true;

        ConnectivityManager cm = (ConnectivityManager)
            context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;

        NetworkRequest request = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(() -> onNetworkAvailable.setValue(null));
            }
        };
        cm.registerNetworkCallback(request, networkCallback);
    }

    public void unregister() {
        if (!registered) return;
        registered = false;

        try {
            ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && networkCallback != null) {
                cm.unregisterNetworkCallback(networkCallback);
            }
        } catch (IllegalArgumentException ignored) {
            // Already unregistered
        }
        networkCallback = null;
    }

}
