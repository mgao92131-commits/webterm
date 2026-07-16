package com.webterm.core.api;

import com.webterm.core.config.ServerConfig;
import com.webterm.core.config.ServerConfigManager;
import com.webterm.data.http.WebTermApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Process-wide, single-flight authentication recovery.
 *
 * Callers report an authentication failure; this coordinator performs at most
 * one refresh and one password login for the server, persists the new cookie,
 * then fans the result out to every waiting HTTP/WebSocket consumer.
 */
@Singleton
public final class AuthSessionCoordinator {
    public interface Callback {
        void onAuthenticated(ServerConfig server, String cookie);
        void onFailure(Failure failure);
    }

    public static final class Failure {
        public final int code;
        public final String message;

        Failure(int code, String message) {
            this.code = code;
            this.message = message == null ? "" : message;
        }

        public boolean isAuthenticationRequired() {
            return code == 401 || code == 403;
        }
    }

    private static final class Waiter {
        final ServerConfig hint;
        final Callback callback;

        Waiter(ServerConfig hint, Callback callback) {
            this.hint = hint;
            this.callback = callback;
        }
    }

    private final WebTermApi api;
    private final ServerConfigManager configs;
    private final Map<String, List<Waiter>> inFlight = new HashMap<>();

    @Inject
    public AuthSessionCoordinator(WebTermApi api, ServerConfigManager configs) {
        this.api = api;
        this.configs = configs;
    }

    public void recover(ServerConfig hint, Callback callback) {
        if (hint == null) {
            callback.onFailure(new Failure(401, "missing server configuration"));
            return;
        }
        ServerConfig owner = configs.credentialOwner(hint);
        String key = identity(owner != null ? owner : hint);
        synchronized (this) {
            List<Waiter> waiters = inFlight.get(key);
            if (waiters != null) {
                waiters.add(new Waiter(hint, callback));
                return;
            }
            waiters = new ArrayList<>();
            waiters.add(new Waiter(hint, callback));
            inFlight.put(key, waiters);
        }
        ServerConfig target = owner != null ? owner : hint;
        String cookie = safe(target.getCookie());
        if (cookie.isEmpty()) {
            login(key, target);
        } else {
            refresh(key, target, cookie);
        }
    }

    private void refresh(String key, ServerConfig target, String cookie) {
        api.refresh(target.getUrl(), cookie, new WebTermApi.LoginCallback() {
            @Override public void onReady(String baseUrl, String refreshedCookie) {
                succeed(key, target, refreshedCookie);
            }

            @Override public void onError(String message) {
                fail(key, 0, message);
            }

            @Override public void onError(int code, String message) {
                if (code == 401 || code == 403) {
                    login(key, target);
                } else {
                    fail(key, code, message);
                }
            }
        });
    }

    private void login(String key, ServerConfig target) {
        String username = safe(target.getUsername());
        String password = safe(target.getPassword());
        if (username.isEmpty() || password.isEmpty()) {
            fail(key, 401, "stored credentials are unavailable");
            return;
        }
        api.login(target.getUrl(), safe(target.getCookie()), username, password,
            new WebTermApi.LoginCallback() {
                @Override public void onReady(String baseUrl, String cookie) {
                    succeed(key, target, cookie);
                }

                @Override public void onError(String message) {
                    fail(key, 0, message);
                }

                @Override public void onError(int code, String message) {
                    fail(key, code, message);
                }
            });
    }

    private void succeed(String key, ServerConfig target, String cookie) {
        ServerConfig canonical = configs.updateCookie(target, cookie);
        List<Waiter> waiters = take(key);
        for (Waiter waiter : waiters) {
            waiter.hint.setCookie(cookie);
            waiter.callback.onAuthenticated(canonical != null ? canonical : waiter.hint, cookie);
        }
    }

    private void fail(String key, int code, String message) {
        Failure failure = new Failure(code, message);
        for (Waiter waiter : take(key)) {
            waiter.callback.onFailure(failure);
        }
    }

    private synchronized List<Waiter> take(String key) {
        List<Waiter> waiters = inFlight.remove(key);
        return waiters != null ? waiters : new ArrayList<>();
    }

    private static String identity(ServerConfig server) {
        String id = safe(server.getId());
        if (!id.isEmpty()) return id;
        return safe(server.getUrl()) + "\n" + safe(server.getUsername());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
