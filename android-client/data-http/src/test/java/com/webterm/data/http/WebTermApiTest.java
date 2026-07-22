package com.webterm.data.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import okhttp3.OkHttpClient;

public class WebTermApiTest {
    @Test
    public void invalidLoginUrlReturnsCallbackErrorInsteadOfThrowing() {
        WebTermApi api = new WebTermApi(new OkHttpClient());
        String[] error = {null};

        WebTermApi.RequestHandle call = api.login(
            "http://bad host:8080", "", "admin", "pw", new WebTermApi.LoginCallback() {
                @Override public void onReady(String baseUrl, String cookie) {}
                @Override public void onError(String message) { error[0] = message; }
            });

        assertNotNull(call);
        assertEquals("设备地址无效", error[0]);
    }
}
