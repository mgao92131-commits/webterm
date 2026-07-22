package com.webterm.core.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void validateBaseUrl_rejectsEmptyInput() {
        WebTermUrls.BaseUrlCheck check = WebTermUrls.validateBaseUrl("   ");
        assertFalse(check.valid);
        assertFalse(check.error.isEmpty());
    }

    @Test
    public void validateBaseUrl_trimsWhitespaceAndTrailingSlashes() {
        WebTermUrls.BaseUrlCheck check = WebTermUrls.validateBaseUrl("  http://example.com//  ");
        assertTrue(check.valid);
        assertEquals("http://example.com", check.normalized);
    }

    @Test
    public void validateBaseUrl_addsHttpWhenSchemeMissing() {
        WebTermUrls.BaseUrlCheck check = WebTermUrls.validateBaseUrl("relay.example.com:9001");
        assertTrue(check.valid);
        assertEquals("http://relay.example.com:9001", check.normalized);
    }

    @Test
    public void validateBaseUrl_acceptsHttpsAndKeepsPort() {
        WebTermUrls.BaseUrlCheck check = WebTermUrls.validateBaseUrl("https://relay.example.com:8443");
        assertTrue(check.valid);
        assertEquals("https://relay.example.com:8443", check.normalized);
    }

    @Test
    public void validateBaseUrl_rejectsNonHttpSchemes() {
        assertFalse(WebTermUrls.validateBaseUrl("ftp://example.com").valid);
        assertFalse(WebTermUrls.validateBaseUrl("ws://example.com").valid);
    }

    @Test
    public void validateBaseUrl_rejectsMissingHost() {
        assertFalse(WebTermUrls.validateBaseUrl("http://").valid);
        assertFalse(WebTermUrls.validateBaseUrl("https:///path").valid);
    }

    @Test
    public void validateBaseUrl_rejectsQueryAndFragment() {
        assertFalse(WebTermUrls.validateBaseUrl("http://example.com?x=1").valid);
        assertFalse(WebTermUrls.validateBaseUrl("http://example.com#frag").valid);
    }

    @Test
    public void validateBaseUrl_rejectsUserInfo() {
        assertFalse(WebTermUrls.validateBaseUrl("http://user:pass@example.com").valid);
    }

    @Test
    public void validateBaseUrl_acceptsBoundaryPorts() {
        WebTermUrls.BaseUrlCheck min = WebTermUrls.validateBaseUrl("http://example.com:1");
        assertTrue(min.valid);
        assertEquals("http://example.com:1", min.normalized);
        WebTermUrls.BaseUrlCheck max = WebTermUrls.validateBaseUrl("http://example.com:65535");
        assertTrue(max.valid);
        assertEquals("http://example.com:65535", max.normalized);
        assertTrue(WebTermUrls.validateBaseUrl("http://example.com").valid); // 未指定端口合法
    }

    @Test
    public void validateBaseUrl_rejectsOutOfRangePorts() {
        assertFalse(WebTermUrls.validateBaseUrl("http://example.com:0").valid);
        assertFalse(WebTermUrls.validateBaseUrl("http://example.com:65536").valid);
        assertFalse(WebTermUrls.validateBaseUrl("http://example.com:99999").valid);
    }

    @Test
    public void validateBaseUrl_rejectsMalformedPorts() {
        assertFalse(WebTermUrls.validateBaseUrl("http://example.com:abc").valid);
        assertFalse(WebTermUrls.validateBaseUrl("http://example.com:").valid);
        assertFalse(WebTermUrls.validateBaseUrl("http://example.com:-1").valid);
    }

    @Test
    public void sameBaseUrl_ignoresTrailingSlashesCaseAndWhitespace() {
        assertTrue(WebTermUrls.sameBaseUrl("http://example.com/", "http://example.com"));
        assertTrue(WebTermUrls.sameBaseUrl("HTTP://EXAMPLE.com", "http://example.com"));
        assertTrue(WebTermUrls.sameBaseUrl("  http://example.com  ", "http://example.com"));
        assertFalse(WebTermUrls.sameBaseUrl("http://a.example.com", "http://b.example.com"));
        assertFalse(WebTermUrls.sameBaseUrl("http://example.com:9001", "http://example.com:9002"));
        assertFalse(WebTermUrls.sameBaseUrl("", "http://example.com"));
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
