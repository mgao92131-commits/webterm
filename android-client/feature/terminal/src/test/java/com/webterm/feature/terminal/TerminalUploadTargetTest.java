package com.webterm.feature.terminal;

import static org.junit.Assert.assertEquals;

import com.webterm.core.api.SessionIds;
import com.webterm.feature.terminal.upload.UploadConnectionKeys;

import org.junit.Test;

/**
 * 验证终端页上传目标构造：Relay 模式下必须把带设备前缀的 canonical sessionId
 * 转成 Agent 本地认识的 local sessionId，否则上传会命中错误的会话。
 */
public class TerminalUploadTargetTest {

    @Test
    public void relaySessionIdIsNormalizedForUpload() {
        String sessionId = "dev-abc:s1";
        String deviceId = "dev-abc";

        String localSessionId = SessionIds.local(sessionId, deviceId);
        assertEquals("s1", localSessionId);

        String connectionKey = UploadConnectionKeys.connectionKey(
            "https://relay.example.com/", deviceId);
        assertEquals("https://relay.example.com\ndev-abc", connectionKey);
    }

    @Test
    public void directSessionIdIsUsedAsIs() {
        String sessionId = "s1";

        assertEquals("s1", SessionIds.local(sessionId, ""));

        String connectionKey = UploadConnectionKeys.connectionKey(
            "https://direct.example.com/", "");
        assertEquals("https://direct.example.com\n", connectionKey);
    }
}
