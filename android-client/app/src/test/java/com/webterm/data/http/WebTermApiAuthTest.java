package com.webterm.data.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class WebTermApiAuthTest {
    private static final long TIMEOUT_SECONDS = 5;

    private MockWebServer server;
    private WebTermApi api;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        baseUrl = server.url("/").toString();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        api = new WebTermApi(new OkHttpClient());
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void registerAcceptsCreatedAndLegacyOkResponses() throws Exception {
        server.enqueue(json(201, userJson(false)));
        RegisterProbe created = register();
        assertTrue(created.created);
        assertFalse(created.emailVerificationRequired);
        assertNull(created.error);

        server.enqueue(json(200, userJson(false)));
        RegisterProbe legacy = register();
        assertTrue(legacy.created);
        assertNull(legacy.error);
    }

    @Test
    public void registerAcceptsEmailVerificationRequired() throws Exception {
        server.enqueue(json(201, userJson(true)));
        RegisterProbe probe = register();
        assertTrue(probe.created);
        assertTrue(probe.emailVerificationRequired);
    }

    @Test
    public void registerRejectsUnsupportedStatusHtmlEmptyAndMissingIdentity() throws Exception {
        assertRegisterFailure(new MockResponse().setResponseCode(202).setBody(userJson(false)));
        assertRegisterFailure(new MockResponse().setResponseCode(204));
        assertRegisterFailure(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/html").setBody("<html>ok</html>"));
        assertRegisterFailure(json(200, "{\"emailVerificationRequired\":false}"));
    }

    @Test
    public void registerRejectsWrongFieldTypeAndPropagatesConflict() throws Exception {
        assertRegisterFailure(json(201, "{\"id\":\"u1\",\"email\":\"a@b\",\"username\":\"a@b\",\"emailVerificationRequired\":\"false\"}"));

        server.enqueue(json(409, "{\"error\":\"user already exists\"}"));
        RegisterProbe probe = register();
        assertEquals("user already exists", probe.error);
        assertFalse(probe.created);
    }

    @Test
    public void registerRejectsInvalidUrlWithoutRequest() throws Exception {
        RegisterProbe probe = new RegisterProbe();
        api.register("http://[invalid", "user@example.com", "", "secret-password", probe);
        probe.await();
        assertEquals("服务器地址无效", probe.error);
        assertFalse(probe.created);
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void verifyEmailRequiresVerifiedTrue() throws Exception {
        server.enqueue(json(200, "{\"verified\":true}"));
        VerifyProbe success = verify("123456");
        assertEquals(baseUrl, success.url);
        assertNull(success.error);

        server.enqueue(json(200, "{\"verified\":false}"));
        VerifyProbe falseValue = verify("123456");
        assertEquals("邮箱验证失败", falseValue.error);
    }

    @Test
    public void verifyEmailRejectsHtmlEmptyAndPropagatesUnauthorized() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html").setBody("<html>ok</html>"));
        assertEquals("邮箱验证失败: 服务器响应格式不正确", verify("123456").error);

        server.enqueue(new MockResponse().setResponseCode(204));
        assertEquals("邮箱验证失败: 服务器响应格式不正确", verify("123456").error);

        server.enqueue(json(401, "{\"error\":\"invalid verification code\"}"));
        assertEquals("invalid verification code", verify("000000").error);
    }

    @Test
    public void resendEmailVerificationRequiresSentTrueAndPropagatesErrors() throws Exception {
        server.enqueue(json(200, "{\"sent\":true}"));
        SimpleProbe success = resend();
        assertTrue(success.ready);
        assertNull(success.error);

        server.enqueue(json(429, "{\"error\":\"otp recently sent\"}"));
        assertEquals("otp recently sent", resend().error);

        server.enqueue(json(401, "{\"error\":\"invalid credentials\"}"));
        assertEquals("invalid credentials", resend().error);

        server.enqueue(new MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/html").setBody("<html>ok</html>"));
        SimpleProbe invalid = resend();
        assertFalse(invalid.ready);
        assertEquals("服务器返回了不兼容的验证码响应", invalid.error);
    }

    private RegisterProbe register() throws Exception {
        RegisterProbe probe = new RegisterProbe();
        api.register(baseUrl, "user@example.com", "", "secret-password", probe);
        probe.await();
        return probe;
    }

    private VerifyProbe verify(String code) throws Exception {
        VerifyProbe probe = new VerifyProbe();
        api.verifyEmail(baseUrl, "user@example.com", code, probe);
        probe.await();
        return probe;
    }

    private SimpleProbe resend() throws Exception {
        SimpleProbe probe = new SimpleProbe();
        api.resendEmailVerification(baseUrl, "user@example.com", "secret-password", probe);
        probe.await();
        return probe;
    }

    private void assertRegisterFailure(MockResponse response) throws Exception {
        server.enqueue(response);
        RegisterProbe probe = register();
        assertFalse(probe.created);
        assertTrue(probe.error != null && !probe.error.isEmpty());
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json").setBody(body);
    }

    private static String userJson(boolean emailVerificationRequired) {
        return "{\"id\":\"u1\",\"email\":\"user@example.com\",\"username\":\"user@example.com\",\"emailVerificationRequired\":"
            + emailVerificationRequired + "}";
    }

    private abstract static class Probe {
        final CountDownLatch done = new CountDownLatch(1);

        void await() throws InterruptedException {
            assertTrue("callback timeout", done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    private static final class RegisterProbe extends Probe implements WebTermApi.RegisterCallback {
        boolean created;
        boolean emailVerificationRequired;
        String error;

        @Override
        public void onAccountCreated(String baseUrl, boolean emailVerificationRequired) {
            created = true;
            this.emailVerificationRequired = emailVerificationRequired;
            done.countDown();
        }

        @Override
        public void onError(String message) {
            error = message;
            done.countDown();
        }

        @Override
        public void onError(String message, boolean accountCreated,
                            boolean emailVerificationRequired,
                            boolean verificationDeliveryFailed) {
            created = accountCreated;
            this.emailVerificationRequired = emailVerificationRequired;
            error = message;
            done.countDown();
        }
    }

    private static final class VerifyProbe extends Probe implements WebTermApi.EmailVerifyCallback {
        String url;
        String error;

        @Override
        public void onVerified(String baseUrl) {
            url = baseUrl;
            done.countDown();
        }

        @Override
        public void onError(String message) {
            error = message;
            done.countDown();
        }
    }

    private static final class SimpleProbe extends Probe implements WebTermApi.SimpleCallback {
        boolean ready;
        String error;

        @Override
        public void onReady() {
            ready = true;
            done.countDown();
        }

        @Override
        public void onError(String message) {
            error = message;
            done.countDown();
        }
    }
}
