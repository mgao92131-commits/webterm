package com.webterm.core.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DirectDeviceAddressNormalizerTest {
    private static String ok(String input) {
        DirectDeviceAddressNormalizer.Result result = DirectDeviceAddressNormalizer.normalize(input);
        assertTrue("expected ok for " + input + " but got error: " + result.error, result.ok);
        assertNotNull(result.url);
        return result.url;
    }

    private static String error(String input) {
        DirectDeviceAddressNormalizer.Result result = DirectDeviceAddressNormalizer.normalize(input);
        assertFalse("expected error for " + input + " but got url: " + result.url, result.ok);
        assertNotNull(result.error);
        return result.error;
    }

    @Test
    public void normalizeBareIp() {
        assertEquals("http://192.168.1.20:8080", ok("192.168.1.20"));
    }

    @Test
    public void normalizeIpWithPort() {
        assertEquals("http://192.168.1.20:9000", ok("192.168.1.20:9000"));
    }

    @Test
    public void normalizeHttpUrl() {
        assertEquals("http://192.168.1.20:8080", ok("http://192.168.1.20:8080"));
    }

    @Test
    public void normalizeHttpsUrl() {
        assertEquals("https://pc.example.com:443", ok("https://pc.example.com"));
    }

    @Test
    public void normalizeStripsTrailingSlash() {
        assertEquals("http://192.168.1.20:8080", ok("192.168.1.20/"));
        assertEquals("http://192.168.1.20:8080", ok("http://192.168.1.20:8080/"));
    }

    @Test
    public void normalizeTrimsWhitespace() {
        assertEquals("http://192.168.1.20:8080", ok("  192.168.1.20  "));
    }

    @Test
    public void rejectPath() {
        error("http://192.168.1.20:8080/api");
        error("192.168.1.20/ws/sessions");
    }

    @Test
    public void rejectInvalidPort() {
        error("192.168.1.20:99999");
        error("192.168.1.20:0");
        error("192.168.1.20:abc");
        error("http://192.168.1.20:");
    }

    @Test
    public void rejectEmptyHost() {
        error("");
        error("   ");
        error("http://");
        error("http:///api");
    }

    @Test
    public void rejectUnsupportedScheme() {
        error("ftp://192.168.1.20");
        error("ws://192.168.1.20");
    }

    @Test
    public void hostAndPortExposed() {
        DirectDeviceAddressNormalizer.Result result = DirectDeviceAddressNormalizer.normalize("192.168.1.20:9000");
        assertTrue(result.ok);
        assertEquals("192.168.1.20", result.host);
        assertEquals(9000, result.port);
    }
}
