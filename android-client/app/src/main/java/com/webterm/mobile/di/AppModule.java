package com.webterm.mobile.di;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.webterm.mobile.RelayMuxSessionRegistry;
import com.webterm.mobile.ServerConfigManager;
import com.webterm.mobile.ServerConfigStore;
import com.webterm.mobile.TerminalCacheCoordinator;
import com.webterm.mobile.WebTermApi;

import java.io.File;

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
    static WebTermApi provideWebTermApi(OkHttpClient http) { return new WebTermApi(http); }

    @Provides
    @Singleton
    static RelayMuxSessionRegistry provideRegistry(OkHttpClient http, Handler mainHandler) {
        return new RelayMuxSessionRegistry(http, mainHandler);
    }

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
}
