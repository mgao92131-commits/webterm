package com.webterm.feature.terminal.upload;

import com.webterm.core.api.DeviceConnectionKeys;

/** 终端页 Relay 上传任务的 connectionKey 计算。 */
public final class UploadConnectionKeys {
    private UploadConnectionKeys() {}

    public static String connectionKey(String baseUrl, String relayDeviceId) {
		return DeviceConnectionKeys.relay(baseUrl, relayDeviceId);
    }
}
