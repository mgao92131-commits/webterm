package com.webterm.feature.terminal.upload;

import static org.junit.Assert.assertEquals;

import com.webterm.core.api.WebTermUrls;

import org.junit.Test;

/** connectionKey 必须与 WebTermDeviceService 的键规则一致：
 * normalizeBaseUrl(baseUrl) + "\n" + deviceId，直连设备 deviceId 缺省为 "direct"。 */
public class UploadConnectionKeysTest {

    @Test
    public void directConnection_usesDirectDeviceId() {
        assertEquals("http://192.168.1.10:18080\ndirect",
            UploadConnectionKeys.connectionKey("http://192.168.1.10:18080/", ""));
    }

    @Test
    public void nullDeviceId_usesDirectDeviceId() {
        assertEquals("http://192.168.1.10:18080\ndirect",
            UploadConnectionKeys.connectionKey("192.168.1.10:18080", null));
    }

    @Test
    public void relayDevice_usesRelayDeviceId() {
        String key = UploadConnectionKeys.connectionKey("https://relay.example.com/", "dev-abc");
        assertEquals(WebTermUrls.normalizeBaseUrl("https://relay.example.com/") + "\ndev-abc", key);
    }

    @Test
    public void nullBaseUrl_doesNotCrash() {
        assertEquals("\ndirect", UploadConnectionKeys.connectionKey(null, ""));
    }
}
