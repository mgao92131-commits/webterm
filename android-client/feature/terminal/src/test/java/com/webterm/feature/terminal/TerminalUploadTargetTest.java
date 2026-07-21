package com.webterm.feature.terminal;

import static org.junit.Assert.assertEquals;

import com.webterm.core.api.DeviceConnectionKeys;
import com.webterm.core.api.SessionIds;

import org.junit.Test;

/**
 * 验证终端页上传目标构造：上传任务键直接复用统一 connectionKey，sessionId 需转成
 * Agent 本地认识的 local sessionId。Direct 与 Relay 的 connectionKey 由
 * {@link DeviceConnectionKeys#resolve} 统一计算，互不冲突。
 */
public class TerminalUploadTargetTest {

    @Test
    public void relaySessionIdIsNormalizedForUpload() {
        String sessionId = "dev-abc:s1";
        String deviceId = "dev-abc";

        assertEquals("s1", SessionIds.local(sessionId, deviceId));

        String connectionKey = DeviceConnectionKeys.resolve(
            false, "cfg-1", "https://relay.example.com/", deviceId);
        assertEquals("https://relay.example.com\ndev-abc", connectionKey);
    }

    @Test
    public void directUsesDirectKeyAndPlainSessionId() {
        String sessionId = "s1";
        String configId = "direct_1";

        // Direct 不带设备前缀，sessionId 原样使用。
        assertEquals("s1", SessionIds.local(sessionId, ""));

        // Direct 上传键为 direct:{configId}，而非 Relay 的 baseUrl\n 形式。
        String connectionKey = DeviceConnectionKeys.resolve(
            true, configId, "https://direct.example.com/", "");
        assertEquals("direct:direct_1", connectionKey);
    }

    @Test
    public void directAndRelayKeysDoNotCollideForSameUrl() {
        String url = "https://same.example.com/";
        String directKey = DeviceConnectionKeys.resolve(true, "direct_1", url, "");
        String relayKey = DeviceConnectionKeys.resolve(false, "direct_1", url, "");
        // 即便 URL 相同，Direct 与 Relay 键空间也不冲突。
        assertEquals("direct:direct_1", directKey);
        assertEquals("https://same.example.com\n", relayKey);
    }
}
