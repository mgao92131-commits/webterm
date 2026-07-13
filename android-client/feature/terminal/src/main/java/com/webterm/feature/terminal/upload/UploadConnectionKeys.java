package com.webterm.feature.terminal.upload;

import com.webterm.core.api.DeviceConnectionKeys;

/** 终端页上传任务的 connectionKey 计算（DeviceConnectionKeys 的薄封装）。
 * 必须与 WebTermDeviceService.connectionKey(baseUrl, deviceId) 保持一致
 * （直连设备 deviceId 缺省为 "direct"），否则 FileUploadController 的
 * EndpointResolver 无法按 key 解析 baseUrl/cookie，上传会以 no_endpoint 失败。 */
public final class UploadConnectionKeys {
    private UploadConnectionKeys() {}

    public static String connectionKey(String baseUrl, String relayDeviceId) {
        String deviceId = relayDeviceId == null ? "" : relayDeviceId;
        return deviceId.isEmpty()
            ? DeviceConnectionKeys.direct(baseUrl)
            : DeviceConnectionKeys.relay(baseUrl, deviceId);
    }
}
