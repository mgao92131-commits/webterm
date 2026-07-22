package com.webterm.core.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.os.Handler;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.webterm.core.config.ServerConfig;
import com.webterm.data.http.WebTermApi;

import org.json.JSONArray;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;

public final class RelayServiceAuthTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private RelayService newService(WebTermApi api, Handler handler) {
        return new RelayService(mock(OkHttpClient.class), handler, api);
    }

    private static Handler immediateHandler() {
        Handler handler = mock(Handler.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return true;
        }).when(handler).post(any(Runnable.class));
        return handler;
    }

    private static ServerConfig master(String url, String cookie) {
        return new ServerConfig("relay_master", "中转服务", url, cookie,
            "user@example.com", "pw", true, false, "");
    }

    // ── reusableCookieFor：纯逻辑 Cookie 隔离 ─────────────────────

    @Test
    public void reusableCookie_sameNormalizedUrlReusesCookie() {
        assertEquals("sid=abc",
            RelayService.reusableCookieFor("http://relay.example.com/", "sid=abc", "http://relay.example.com"));
        assertEquals("sid=abc",
            RelayService.reusableCookieFor("http://relay.example.com", "sid=abc", "HTTP://RELAY.EXAMPLE.COM"));
    }

    @Test
    public void reusableCookie_differentUrlGetsEmptyCookie() {
        assertEquals("",
            RelayService.reusableCookieFor("http://server-a.example.com", "sid=abc", "http://server-b.example.com"));
    }

    @Test
    public void reusableCookie_emptySavedCookieStaysEmpty() {
        assertEquals("",
            RelayService.reusableCookieFor("http://relay.example.com", "", "http://relay.example.com"));
    }

    // ── onLogin：使用传入 baseUrl，且隔离旧服务器 Cookie ──────────

    @Test
    public void onLogin_switchingServerSendsEmptyCookie() {
        WebTermApi api = mock(WebTermApi.class);
        RelayService service = newService(api, immediateHandler());
        List<ServerConfig> servers = new ArrayList<>();
        servers.add(master("http://server-a.example.com", "sid=old"));
        service.loadMasterFromServers(servers);

        service.onLogin("http://server-b.example.com", "user@example.com", "pw",
            mock(WebTermApi.ExtendedLoginCallback.class));

        verify(api).login(eq("http://server-b.example.com"), eq(""),
            eq("user@example.com"), eq("pw"), any());
    }

    @Test
    public void onLogin_sameServerReusesSavedCookie() {
        WebTermApi api = mock(WebTermApi.class);
        RelayService service = newService(api, immediateHandler());
        List<ServerConfig> servers = new ArrayList<>();
        servers.add(master("http://relay.example.com", "sid=keep"));
        service.loadMasterFromServers(servers);

        service.onLogin("http://relay.example.com/", "user@example.com", "pw",
            mock(WebTermApi.ExtendedLoginCallback.class));

        verify(api).login(eq("http://relay.example.com/"), eq("sid=keep"),
            eq("user@example.com"), eq("pw"), any());
    }

    @Test
    public void onLogin_usesProvidedBaseUrlNotSavedMasterUrl() {
        WebTermApi api = mock(WebTermApi.class);
        RelayService service = newService(api, immediateHandler());
        List<ServerConfig> servers = new ArrayList<>();
        servers.add(master("http://saved.example.com", "sid=x"));
        service.loadMasterFromServers(servers);

        service.onLogin("http://typed-by-user.example.com:9001", "user@example.com", "pw",
            mock(WebTermApi.ExtendedLoginCallback.class));

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(api).login(url.capture(), anyString(), anyString(), anyString(), any());
        assertEquals("http://typed-by-user.example.com:9001", url.getValue());
    }

    // ── onVerifyOtp：固定使用登录时的 baseUrl ─────────────────────

    @Test
    public void onVerifyOtp_usesProvidedBaseUrlNotMasterUrl() {
        WebTermApi api = mock(WebTermApi.class);
        RelayService service = newService(api, immediateHandler());
        List<ServerConfig> servers = new ArrayList<>();
        servers.add(master("http://saved.example.com", "sid=x"));
        service.loadMasterFromServers(servers);

        service.onVerifyOtp("http://login-time.example.com", "user@example.com", "123456",
            "dev-1", "otp-cookie", mock(WebTermApi.LoginCallback.class));

        verify(api).verifyOtp(eq("http://login-time.example.com"), eq("user@example.com"),
            eq("123456"), eq("dev-1"), eq("otp-cookie"), any());
    }

    @Test
    public void onVerifyEmail_usesProvidedBaseUrlNotMasterUrl() {
        WebTermApi api = mock(WebTermApi.class);
        RelayService service = newService(api, immediateHandler());
        List<ServerConfig> servers = new ArrayList<>();
        servers.add(master("http://saved.example.com", "sid=x"));
        service.loadMasterFromServers(servers);

        service.onVerifyEmail("http://register-time.example.com", "user@example.com", "123456",
            mock(WebTermApi.EmailVerifyCallback.class));

        verify(api).verifyEmail(eq("http://register-time.example.com"),
            eq("user@example.com"), eq("123456"), any());
    }

    @Test
    public void onResendEmailVerification_usesProvidedBaseUrlAndCredentials() {
        WebTermApi api = mock(WebTermApi.class);
        RelayService service = newService(api, immediateHandler());
        List<ServerConfig> servers = new ArrayList<>();
        servers.add(master("http://saved.example.com", "sid=x"));
        service.loadMasterFromServers(servers);

        service.onResendEmailVerification("http://register-time.example.com",
            "user@example.com", "pw", mock(WebTermApi.SimpleCallback.class));

        verify(api).resendEmailVerification(eq("http://register-time.example.com"),
            eq("user@example.com"), eq("pw"), any());
    }

    // ── saveRelayLogin：切换服务器清理旧设备缓存 ──────────────────

    @Test
    public void saveRelayLogin_serverSwitchClearsStaleDevices() throws Exception {
        WebTermApi api = mock(WebTermApi.class);
        RelayService service = newService(api, immediateHandler());
        List<ServerConfig> servers = new ArrayList<>();
        servers.add(master("http://server-a.example.com", "sid=old"));
        service.loadMasterFromServers(servers);

        doAnswer(inv -> {
            WebTermApi.SessionsCallback cb = inv.getArgument(2);
            cb.onReady(new JSONArray(
                "[{\"deviceId\":\"d1\",\"deviceName\":\"PC\",\"online\":true}]"));
            return null;
        }).when(api).fetchDevices(anyString(), anyString(), any());
        service.refresh();
        assertTrue(service.areDevicesLoaded());
        assertEquals(1, service.devices().size());

        // 登录到新服务器 B：旧服务器 A 的设备缓存必须被清空。
        service.saveRelayLogin("http://server-b.example.com", "user@example.com", "pw", "sid=new");

        assertFalse(service.areDevicesLoaded());
        assertTrue(service.devices().isEmpty());
        assertEquals("http://server-b.example.com", service.masterConfig().getUrl());
        assertEquals("sid=new", service.masterConfig().getCookie());
    }

    @Test
    public void saveRelayLogin_sameServerKeepsDeviceCache() throws Exception {
        WebTermApi api = mock(WebTermApi.class);
        RelayService service = newService(api, immediateHandler());
        List<ServerConfig> servers = new ArrayList<>();
        servers.add(master("http://relay.example.com", "sid=old"));
        service.loadMasterFromServers(servers);

        doAnswer(inv -> {
            WebTermApi.SessionsCallback cb = inv.getArgument(2);
            cb.onReady(new JSONArray(
                "[{\"deviceId\":\"d1\",\"deviceName\":\"PC\",\"online\":true}]"));
            return null;
        }).when(api).fetchDevices(anyString(), anyString(), any());
        service.refresh();
        assertEquals(1, service.devices().size());

        service.saveRelayLogin("http://relay.example.com/", "user@example.com", "pw", "sid=new");

        assertTrue(service.areDevicesLoaded());
        assertEquals(1, service.devices().size());
        assertEquals("sid=new", service.masterConfig().getCookie());
    }
}
