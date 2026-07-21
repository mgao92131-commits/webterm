package com.webterm.feature.relay;

import android.app.Activity;
import android.widget.TextView;

import androidx.lifecycle.Observer;

import com.webterm.core.relay.RelayService;
import com.webterm.ui.common.StatusIndicatorView;

import org.json.JSONArray;

/**
 * Implements screen-builder Host interfaces and bridges {@link RelayService}
 * business logic with UI elements (subtitle Text, status dot).
 *
 * Created by MainActivity which owns both the service and the UI views.
 */
public final class RelayUiState implements RelayLoginScreenBuilder.Host, RelayDevicesScreenBuilder.Host {

    private final RelayService relayService;
    private final RelayService.Host relayHost;

    private TextView homeSubtitle;
    private StatusIndicatorView homeStatusDot;

    private Observer<String> subtitleObserver;
    private Observer<Integer> colorObserver;
    private Observer<Integer> statusObserver;

    public RelayUiState(RelayService relayService, RelayService.Host relayHost) {
        this.relayService = relayService;
        this.relayHost = relayHost;

        // Create observers after relayService is assigned
        subtitleObserver = text -> {
            if (homeSubtitle != null) {
                homeSubtitle.post(() -> {
                    homeSubtitle.setText(text);
                    Integer color = relayService.getSubtitleColor().getValue();
                    if (color != null) homeSubtitle.setTextColor(color);
                });
            }
        };

        colorObserver = color -> {
            if (homeSubtitle != null && color != null) {
                homeSubtitle.post(() -> homeSubtitle.setTextColor(color));
            }
        };

        statusObserver = status -> {
            if (homeStatusDot != null && status != null) {
                homeStatusDot.post(() -> {
                    switch (status) {
                        case RelayService.STATUS_CONNECTING:
                            homeStatusDot.setStatus(StatusIndicatorView.Status.CONNECTING);
                            break;
                        case RelayService.STATUS_DISCONNECTED:
                            homeStatusDot.setStatus(StatusIndicatorView.Status.DISCONNECTED);
                            break;
                        case RelayService.STATUS_CONNECTED:
                            homeStatusDot.setStatus(StatusIndicatorView.Status.CONNECTED);
                            break;
                    }
                });
            }
        };

        // Observe LiveData
        relayService.getSubtitleText().observeForever(subtitleObserver);
        relayService.getSubtitleColor().observeForever(colorObserver);
        relayService.getStatusDotStatus().observeForever(statusObserver);
    }

    public void attachSubtitle(TextView subtitle) {
        this.homeSubtitle = subtitle;
        // Apply current value immediately
        String text = relayService.getSubtitleText().getValue();
        Integer color = relayService.getSubtitleColor().getValue();
        if (subtitle != null && text != null) {
            subtitle.setText(text);
            if (color != null) subtitle.setTextColor(color);
        }
    }

    public void attachStatusDot(StatusIndicatorView statusDot) {
        this.homeStatusDot = statusDot;
        Integer status = relayService.getStatusDotStatus().getValue();
        if (statusDot != null && status != null) {
            switch (status) {
                case RelayService.STATUS_CONNECTING:
                    statusDot.setStatus(StatusIndicatorView.Status.CONNECTING);
                    break;
                case RelayService.STATUS_DISCONNECTED:
                    statusDot.setStatus(StatusIndicatorView.Status.DISCONNECTED);
                    break;
                case RelayService.STATUS_CONNECTED:
                    statusDot.setStatus(StatusIndicatorView.Status.CONNECTED);
                    break;
            }
        }
    }

    public void detachSubtitle() {
        this.homeSubtitle = null;
    }

    public void detachStatusDot() {
        this.homeStatusDot = null;
    }

    public void destroy() {
        relayService.getSubtitleText().removeObserver(subtitleObserver);
        relayService.getSubtitleColor().removeObserver(colorObserver);
        relayService.getStatusDotStatus().removeObserver(statusObserver);
        homeSubtitle = null;
        homeStatusDot = null;
    }

    // ── RelayLoginScreenBuilder.Host ─────────────────────────────

    @Override
    public Activity activity() {
        return relayHost.activity();
    }

    @Override
    public void onLogin(String baseUrl, String email, String password, RelayLoginScreenBuilder.LoginScreenCallback callback) {
        relayService.onLogin(baseUrl, email, password, new com.webterm.data.http.WebTermApi.ExtendedLoginCallback() {
            @Override
            public void onReady(String url, String cookie) {
                if (cookie != null && !cookie.isEmpty()) {
                    relayService.saveRelayLogin(url, email, password, cookie);
                }
                callback.onLoginSuccess(url, cookie);
            }

            @Override
            public void onOtpRequired(String targetDeviceId, String cookie) {
                callback.onOtpRequired(targetDeviceId, cookie);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onRegister(String baseUrl, String email, String password, RelayLoginScreenBuilder.LoginScreenCallback callback) {
        relayService.onRegister(baseUrl, email, password, new com.webterm.data.http.WebTermApi.RegisterCallback() {
            @Override
            public void onAccountCreated(String url, boolean emailVerificationRequired) {
                if (emailVerificationRequired) {
                    // 注册成功但需先完成邮箱验证；不得调用 new_device 的 verify-otp 接口。
                    // TODO(email-verify): 后端补充 /api/auth/verify-email 后，在此接入邮箱验证码界面。
                    callback.onEmailVerificationRequired(
                        "注册成功。该服务器要求邮箱验证，请在邮箱中完成验证后返回登录页登录。");
                    return;
                }
                // 注册成功后用同一 baseUrl、email、password 自动登录以取得认证 Cookie。
                relayService.onLogin(url, email, password, new com.webterm.data.http.WebTermApi.ExtendedLoginCallback() {
                    @Override
                    public void onReady(String loginUrl, String cookie) {
                        if (cookie != null && !cookie.isEmpty()) {
                            relayService.saveRelayLogin(loginUrl, email, password, cookie);
                        }
                        callback.onLoginSuccess(loginUrl, cookie);
                    }

                    @Override
                    public void onOtpRequired(String targetDeviceId, String cookie) {
                        callback.onEmailVerificationRequired(
                            "账号已创建。该服务器要求设备验证，请返回登录页输入验证码后登录。");
                    }

                    @Override
                    public void onError(String message) {
                        callback.onError("账号已创建，但自动登录失败，请返回登录页重试。");
                    }
                });
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onVerifyOtp(String baseUrl, String email, String password, String code, String targetDeviceId, String cookie, RelayLoginScreenBuilder.LoginScreenCallback callback) {
        relayService.onVerifyOtp(baseUrl, email, code, targetDeviceId, cookie, new com.webterm.data.http.WebTermApi.LoginCallback() {
            @Override
            public void onReady(String url, String newCookie) {
                if (newCookie != null && !newCookie.isEmpty()) {
                    relayService.saveRelayLogin(url, email, password, newCookie);
                }
                callback.onLoginSuccess(url, newCookie);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onBackToHome() {
        relayHost.onRelayAuthDone();
    }

    // ── RelayDevicesScreenBuilder.Host ───────────────────────────

    @Override
    public void onFetchDevices(RelayDevicesScreenBuilder.DevicesCallback callback) {
        relayService.onFetchDevices(new com.webterm.data.http.WebTermApi.SessionsCallback() {
            @Override
            public void onReady(JSONArray devices) {
                callback.onReady(devices);
            }

            @Override
            public void onError(int code, String message) {
                callback.onError(message);
            }

            @Override
            public void onParseError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onRegisterDevice(String deviceName, RelayDevicesScreenBuilder.DeviceCreateCallback callback) {
        relayService.onRegisterDevice(deviceName, new com.webterm.data.http.WebTermApi.DeviceCreateCallback() {
            @Override
            public void onReady(String deviceId, String name, String agentSecret) {
                callback.onReady(deviceId, name, agentSecret);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onDeleteDevice(String deviceId, RelayDevicesScreenBuilder.SimpleCallback callback) {
        relayService.onDeleteDevice(deviceId, new com.webterm.data.http.WebTermApi.SimpleCallback() {
            @Override
            public void onReady() { callback.onReady(); }
            @Override
            public void onError(String message) { callback.onError(message); }
        });
    }

    @Override
    public void onFetchTrustedDevices(RelayDevicesScreenBuilder.TrustedDevicesCallback callback) {
        relayService.onFetchTrustedDevices(new com.webterm.data.http.WebTermApi.TrustedDevicesCallback() {
            @Override
            public void onReady(JSONArray devices) {
                callback.onReady(devices);
            }

            @Override
            public void onError(String message) {
                callback.onError(message);
            }
        });
    }

    @Override
    public void onDeleteTrustedDevice(String trustedDeviceId, RelayDevicesScreenBuilder.SimpleCallback callback) {
        relayService.onDeleteTrustedDevice(trustedDeviceId, new com.webterm.data.http.WebTermApi.SimpleCallback() {
            @Override
            public void onReady() { callback.onReady(); }
            @Override
            public void onError(String message) { callback.onError(message); }
        });
    }

    @Override
    public void onLogout() {
        relayService.onDisconnectRelay();
    }
}
