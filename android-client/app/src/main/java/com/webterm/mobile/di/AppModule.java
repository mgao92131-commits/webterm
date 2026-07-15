package com.webterm.mobile.di;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.webterm.core.cache.TerminalCacheCoordinator;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.core.config.ServerConfigStore;
import com.webterm.core.session.DeviceConnectionRegistry;
import com.webterm.transport.api.TransportFactory;
import com.webterm.transport.websocket.WebSocketMuxTransport;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import dagger.Module;
import dagger.Provides;
import dagger.hilt.InstallIn;
import dagger.hilt.android.qualifiers.ApplicationContext;
import dagger.hilt.components.SingletonComponent;
import okhttp3.OkHttpClient;

import javax.inject.Singleton;

@Module
@InstallIn(SingletonComponent.class)
public class AppModule {

    @Provides
    @Singleton
    static OkHttpClient provideHttpClient() { return new OkHttpClient(); }

    @Provides
    @Singleton
    static Handler provideMainHandler() { return new Handler(Looper.getMainLooper()); }

    @Provides
    @Singleton
    static ServerConfigStore provideConfigStore(@ApplicationContext Context context) {
        return new ServerConfigStore(context);
    }

    @Provides
    @Singleton
    static ServerConfigManager provideConfigManager(ServerConfigStore store) {
        return new ServerConfigManager(store);
    }

    @Provides
    @Singleton
    static TerminalCacheCoordinator provideTerminalCache(@ApplicationContext Context context) {
        return new TerminalCacheCoordinator(context.getFilesDir());
    }

    @Provides
    @Singleton
    static Executor provideIoExecutor() {
        return Executors.newSingleThreadExecutor();
    }

    @Provides
    @Singleton
    static TransportFactory provideTransportFactory(OkHttpClient http) {
        return (wsUrl, cookie, subprotocol) -> new WebSocketMuxTransport(http, wsUrl, cookie, subprotocol);
    }
}
