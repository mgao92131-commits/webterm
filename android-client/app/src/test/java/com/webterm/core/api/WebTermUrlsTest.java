package com.webterm.core.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WebTermUrlsTest {

    @Test
    public void normalizeBaseUrl_addsHttpWhenMissing() {
        assertEquals("http://example.com", WebTermUrls.normalizeBaseUrl("example.com"));
    }

    @Test
    public void normalizeBaseUrl_preservesHttps() {
        assertEquals("https://example.com", WebTermUrls.normalizeBaseUrl("https://example.com"));
    }

    @Test
    public void normalizeBaseUrl_trimsTrailingSlashes() {
        assertEquals("http://example.com", WebTermUrls.normalizeBaseUrl("example.com/"));
        assertEquals("http://example.com", WebTermUrls.normalizeBaseUrl("example.com//"));
    }

    @Test
    public void normalizeBaseUrl_returnsEmptyForNull() {
        assertEquals("", WebTermUrls.normalizeBaseUrl(null));
    }

    @Test
    public void toWebSocketUrl_convertsHttpToWs() {
        assertEquals("ws://example.com", WebTermUrls.toWebSocketUrl("http://example.com"));
    }

    @Test
    public void toWebSocketUrl_convertsHttpsToWss() {
        assertEquals("wss://example.com", WebTermUrls.toWebSocketUrl("https://example.com"));
    }

    @Test
    public void encodePath_usesPercentEncodingAndReplacesPlus() {
        assertEquals("hello%20world", WebTermUrls.encodePath("hello world"));
        assertEquals("a%2Fb", WebTermUrls.encodePath("a/b"));
    }
}
