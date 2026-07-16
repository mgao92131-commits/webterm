package com.webterm.core.api;

import com.webterm.data.http.WebTermApi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicInteger;

public class AuthSessionCoordinatorTest {
    @Test
    public void concurrentRecoveryRefreshesThenLogsInOnceAndFansOut() {
        WebTermApi api = mock(WebTermApi.class);
        ServerConfigManager configs = mock(ServerConfigManager.class);
        ServerConfig owner = server("srv", "old", "pw");
        ServerConfig copy1 = server("srv", "old", "pw");
        ServerConfig copy2 = server("srv", "old", "pw");
        when(configs.credentialOwner(any(ServerConfig.class))).thenReturn(owner);
        when(configs.updateCookie(any(ServerConfig.class), anyString())).thenReturn(owner);
        AuthSessionCoordinator coordinator = new AuthSessionCoordinator(api, configs);
        AtomicInteger success = new AtomicInteger();
        AuthSessionCoordinator.Callback callback = new AuthSessionCoordinator.Callback() {
            @Override public void onAuthenticated(ServerConfig server, String cookie) {
                assertEquals("fresh", cookie);
                success.incrementAndGet();
            }
            @Override public void onFailure(AuthSessionCoordinator.Failure failure) {}
        };

        coordinator.recover(copy1, callback);
        coordinator.recover(copy2, callback);

        ArgumentCaptor<WebTermApi.LoginCallback> refresh = ArgumentCaptor.forClass(WebTermApi.LoginCallback.class);
        verify(api, times(1)).refresh(anyString(), anyString(), refresh.capture());
        refresh.getValue().onError(401, "expired");

        ArgumentCaptor<WebTermApi.LoginCallback> login = ArgumentCaptor.forClass(WebTermApi.LoginCallback.class);
        verify(api, times(1)).login(anyString(), anyString(), anyString(), anyString(), login.capture());
        login.getValue().onReady("http://example.test", "fresh");

        assertEquals(2, success.get());
        assertEquals("fresh", copy1.getCookie());
        assertEquals("fresh", copy2.getCookie());
        verify(configs, times(1)).updateCookie(owner, "fresh");
    }

    @Test
    public void networkFailureDoesNotAttemptPasswordLogin() {
        WebTermApi api = mock(WebTermApi.class);
        ServerConfigManager configs = mock(ServerConfigManager.class);
        ServerConfig owner = server("srv", "old", "pw");
        when(configs.credentialOwner(any(ServerConfig.class))).thenReturn(owner);
        AuthSessionCoordinator coordinator = new AuthSessionCoordinator(api, configs);
        final boolean[] failed = {false};

        coordinator.recover(owner, new AuthSessionCoordinator.Callback() {
            @Override public void onAuthenticated(ServerConfig server, String cookie) {}
            @Override public void onFailure(AuthSessionCoordinator.Failure failure) {
                failed[0] = true;
                assertFalse(failure.isAuthenticationRequired());
            }
        });

        ArgumentCaptor<WebTermApi.LoginCallback> refresh = ArgumentCaptor.forClass(WebTermApi.LoginCallback.class);
        verify(api).refresh(anyString(), anyString(), refresh.capture());
        refresh.getValue().onError(0, "offline");

        assertTrue(failed[0]);
        verify(api, never()).login(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    public void rejectedRefreshAndLoginFailsOnceWithoutLooping() {
        WebTermApi api = mock(WebTermApi.class);
        ServerConfigManager configs = mock(ServerConfigManager.class);
        ServerConfig owner = server("srv", "old", "pw");
        when(configs.credentialOwner(any(ServerConfig.class))).thenReturn(owner);
        AuthSessionCoordinator coordinator = new AuthSessionCoordinator(api, configs);
        final boolean[] authRequired = {false};
        coordinator.recover(owner, new AuthSessionCoordinator.Callback() {
            @Override public void onAuthenticated(ServerConfig server, String cookie) {}
            @Override public void onFailure(AuthSessionCoordinator.Failure failure) {
                authRequired[0] = failure.isAuthenticationRequired();
            }
        });

        ArgumentCaptor<WebTermApi.LoginCallback> refresh = ArgumentCaptor.forClass(WebTermApi.LoginCallback.class);
        verify(api).refresh(anyString(), anyString(), refresh.capture());
        refresh.getValue().onError(401, "expired");
        ArgumentCaptor<WebTermApi.LoginCallback> login = ArgumentCaptor.forClass(WebTermApi.LoginCallback.class);
        verify(api).login(anyString(), anyString(), anyString(), anyString(), login.capture());
        login.getValue().onError(401, "denied");

        assertTrue(authRequired[0]);
        verify(api, times(1)).refresh(anyString(), anyString(), any(WebTermApi.LoginCallback.class));
        verify(api, times(1)).login(anyString(), anyString(), anyString(), anyString(),
            any(WebTermApi.LoginCallback.class));
    }

    private static ServerConfig server(String id, String cookie, String password) {
        return new ServerConfig(id, "Mac", "http://example.test", cookie, "user", password);
    }
}
