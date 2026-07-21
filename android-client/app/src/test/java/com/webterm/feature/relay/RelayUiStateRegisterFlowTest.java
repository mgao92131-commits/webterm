package com.webterm.feature.relay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.MutableLiveData;

import com.webterm.core.relay.RelayService;
import com.webterm.data.http.WebTermApi;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class RelayUiStateRegisterFlowTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private RelayService relayService;
    private RelayUiState uiState;

    @Before
    public void setUp() {
        relayService = mock(RelayService.class);
        when(relayService.getSubtitleText()).thenReturn(new MutableLiveData<>());
        when(relayService.getSubtitleColor()).thenReturn(new MutableLiveData<>());
        when(relayService.getStatusDotStatus()).thenReturn(new MutableLiveData<>());
        uiState = new RelayUiState(relayService, mock(RelayService.Host.class));
    }

    @Test
    public void onLogin_forwardsUserTypedBaseUrlToService() {
        uiState.onLogin("http://typed.example.com:9001", "user@example.com", "pw",
            mock(RelayLoginScreenBuilder.LoginScreenCallback.class));

        verify(relayService).onLogin(eq("http://typed.example.com:9001"),
            eq("user@example.com"), eq("pw"), any());
    }

    @Test
    public void onVerifyOtp_forwardsLoginTimeBaseUrlToService() {
        uiState.onVerifyOtp("http://login-time.example.com", "user@example.com", "pw",
            "123456", "dev-1", "otp-cookie",
            mock(RelayLoginScreenBuilder.LoginScreenCallback.class));

        verify(relayService).onVerifyOtp(eq("http://login-time.example.com"),
            eq("user@example.com"), eq("123456"), eq("dev-1"), eq("otp-cookie"), any());
    }

    @Test
    public void onRegister_createdThenAutoLoginSavesAndSucceeds() {
        RelayLoginScreenBuilder.LoginScreenCallback callback =
            mock(RelayLoginScreenBuilder.LoginScreenCallback.class);
        uiState.onRegister("http://relay.example.com", "user@example.com", "pw", callback);

        ArgumentCaptor<WebTermApi.RegisterCallback> registerCb =
            ArgumentCaptor.forClass(WebTermApi.RegisterCallback.class);
        verify(relayService).onRegister(eq("http://relay.example.com"),
            eq("user@example.com"), eq("pw"), registerCb.capture());

        // 注册成功（无需邮箱验证）→ 自动登录。
        registerCb.getValue().onAccountCreated("http://relay.example.com", false);
        ArgumentCaptor<WebTermApi.ExtendedLoginCallback> loginCb =
            ArgumentCaptor.forClass(WebTermApi.ExtendedLoginCallback.class);
        verify(relayService).onLogin(eq("http://relay.example.com"),
            eq("user@example.com"), eq("pw"), loginCb.capture());

        // 自动登录取得 Cookie → 保存配置并回调成功。
        loginCb.getValue().onReady("http://relay.example.com", "sid=new");
        verify(relayService).saveRelayLogin("http://relay.example.com",
            "user@example.com", "pw", "sid=new");
        verify(callback).onLoginSuccess("http://relay.example.com", "sid=new");
    }

    @Test
    public void onRegister_emailVerificationRequiredDoesNotAutoLogin() {
        RelayLoginScreenBuilder.LoginScreenCallback callback =
            mock(RelayLoginScreenBuilder.LoginScreenCallback.class);
        uiState.onRegister("http://relay.example.com", "user@example.com", "pw", callback);

        ArgumentCaptor<WebTermApi.RegisterCallback> registerCb =
            ArgumentCaptor.forClass(WebTermApi.RegisterCallback.class);
        verify(relayService).onRegister(anyString(), anyString(), anyString(), registerCb.capture());

        registerCb.getValue().onAccountCreated("http://relay.example.com", true);

        verify(relayService, never()).onLogin(anyString(), anyString(), anyString(), any());
        verify(relayService, never()).saveRelayLogin(anyString(), anyString(), anyString(), anyString());
        verify(callback, never()).onLoginSuccess(anyString(), anyString());
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(callback).onEmailVerificationRequired(message.capture());
        assertTrue(message.getValue().contains("邮箱验证"));
    }

    @Test
    public void onRegister_autoLoginFailureReportsAccountCreated() {
        RelayLoginScreenBuilder.LoginScreenCallback callback =
            mock(RelayLoginScreenBuilder.LoginScreenCallback.class);
        uiState.onRegister("http://relay.example.com", "user@example.com", "pw", callback);

        ArgumentCaptor<WebTermApi.RegisterCallback> registerCb =
            ArgumentCaptor.forClass(WebTermApi.RegisterCallback.class);
        verify(relayService).onRegister(anyString(), anyString(), anyString(), registerCb.capture());
        registerCb.getValue().onAccountCreated("http://relay.example.com", false);

        ArgumentCaptor<WebTermApi.ExtendedLoginCallback> loginCb =
            ArgumentCaptor.forClass(WebTermApi.ExtendedLoginCallback.class);
        verify(relayService).onLogin(anyString(), anyString(), anyString(), loginCb.capture());

        loginCb.getValue().onError("invalid credentials");

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(callback).onError(message.capture());
        assertTrue(message.getValue().contains("账号已创建"));
        verify(relayService, never()).saveRelayLogin(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void onRegister_autoLoginRequiresDeviceOtpShowsNotice() {
        RelayLoginScreenBuilder.LoginScreenCallback callback =
            mock(RelayLoginScreenBuilder.LoginScreenCallback.class);
        uiState.onRegister("http://relay.example.com", "user@example.com", "pw", callback);

        ArgumentCaptor<WebTermApi.RegisterCallback> registerCb =
            ArgumentCaptor.forClass(WebTermApi.RegisterCallback.class);
        verify(relayService).onRegister(anyString(), anyString(), anyString(), registerCb.capture());
        registerCb.getValue().onAccountCreated("http://relay.example.com", false);

        ArgumentCaptor<WebTermApi.ExtendedLoginCallback> loginCb =
            ArgumentCaptor.forClass(WebTermApi.ExtendedLoginCallback.class);
        verify(relayService).onLogin(anyString(), anyString(), anyString(), loginCb.capture());
        loginCb.getValue().onOtpRequired("dev-1", "");

        verify(callback, never()).onLoginSuccess(anyString(), anyString());
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(callback).onEmailVerificationRequired(message.capture());
        assertTrue(message.getValue().contains("账号已创建"));
    }

    @Test
    public void onRegister_registerFailureSurfacesServerMessage() {
        RelayLoginScreenBuilder.LoginScreenCallback callback =
            mock(RelayLoginScreenBuilder.LoginScreenCallback.class);
        uiState.onRegister("http://relay.example.com", "user@example.com", "pw", callback);

        ArgumentCaptor<WebTermApi.RegisterCallback> registerCb =
            ArgumentCaptor.forClass(WebTermApi.RegisterCallback.class);
        verify(relayService).onRegister(anyString(), anyString(), anyString(), registerCb.capture());
        registerCb.getValue().onError("该邮箱已被注册。");

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(callback).onError(message.capture());
        assertEquals("该邮箱已被注册。", message.getValue());
        verify(relayService, never()).onLogin(anyString(), anyString(), anyString(), any());
    }
}
