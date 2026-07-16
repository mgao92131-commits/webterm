package com.webterm.feature.home.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.os.Handler;
import android.os.Looper;

import com.webterm.core.session.ChannelFailure;
import com.webterm.core.session.DeviceConnectionRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.concurrent.atomic.AtomicReference;

public final class ServerSessionDataSourceThreadingTest {

    @Test
    public void sessionClosedFromDeviceThreadIsDeliveredOnMainHandler() {
        Handler mainHandler = mock(Handler.class);
        Looper mainLooper = mock(Looper.class);
        when(mainHandler.getLooper()).thenReturn(mainLooper);
        ServerSessionDataSource source = new ServerSessionDataSource(
            mock(DeviceConnectionRegistry.class), mainHandler);
        AtomicReference<String> closedSession = new AtomicReference<>();

        try (MockedStatic<Looper> loopers = mockStatic(Looper.class)) {
            loopers.when(Looper::myLooper).thenReturn(null);
            source.dispatch("{\"type\":\"session-closed\",\"id\":\"s1\"}",
                listenerWithClosedSession(closedSession), "d1");
        }

        assertNull("background callback must not touch LiveData synchronously",
            closedSession.get());
        ArgumentCaptor<Runnable> posted = ArgumentCaptor.forClass(Runnable.class);
        verify(mainHandler).post(posted.capture());
        posted.getValue().run();
        assertEquals("d1:s1", closedSession.get());
    }

    @Test
    public void callbackAlreadyOnMainThreadIsNotReposted() {
        Handler mainHandler = mock(Handler.class);
        Looper mainLooper = mock(Looper.class);
        when(mainHandler.getLooper()).thenReturn(mainLooper);
        ServerSessionDataSource source = new ServerSessionDataSource(
            mock(DeviceConnectionRegistry.class), mainHandler);
        AtomicReference<String> value = new AtomicReference<>();

        try (MockedStatic<Looper> loopers = mockStatic(Looper.class)) {
            loopers.when(Looper::myLooper).thenReturn(mainLooper);
            source.dispatchOnMain(() -> value.set("main"));
        }

        assertEquals("main", value.get());
        verify(mainHandler, org.mockito.Mockito.never()).post(any(Runnable.class));
    }

    private static ServerSessionDataSource.Listener listenerWithClosedSession(
        AtomicReference<String> closedSession) {
        return new ServerSessionDataSource.Listener() {
            @Override public void onConnected() {}
            @Override public void onConnecting() {}
            @Override public void onDisconnected(ChannelFailure failure) {}
            @Override public void onSessions(JSONArray sessions) {}
            @Override public void onSession(JSONObject session) {}
            @Override public void onSessionClosed(String sessionId) {
                closedSession.set(sessionId);
            }
        };
    }
}
