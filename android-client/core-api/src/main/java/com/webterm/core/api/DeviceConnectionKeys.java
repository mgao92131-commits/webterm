package com.webterm.core.api;

/** 设备连接标识（connectionKey）的统一计算。
 * 生产侧（WebTermDeviceService.connectDevice）与所有消费侧（通知点击跳转、
 * 终端焦点免打扰、文件上传端点解析等）必须经由本类拼接，
 * 格式为 normalizeBaseUrl(url) + "\n" + deviceId 段；cookie 不参与设备身份。 */
public final class DeviceConnectionKeys {
    /** 直连设备在 connectionKey 中使用的固定 deviceId 段。 */
    public static final String DIRECT_DEVICE_ID = "direct";

    private DeviceConnectionKeys() {}

    /** 直连设备：normalizeBaseUrl(url) + "\n" + "direct"。 */
    public static String direct(String baseUrl) {
        return WebTermUrls.normalizeBaseUrl(baseUrl) + "\n" + DIRECT_DEVICE_ID;
    }

    /** Relay 设备：normalizeBaseUrl(relayUrl) + "\n" + deviceId。 */
    public static String relay(String baseUrl, String deviceId) {
        return WebTermUrls.normalizeBaseUrl(baseUrl) + "\n" + (deviceId == null ? "" : deviceId);
    }

    /** 与连接建立侧（WebTermDeviceService.connectDevice）的身份规则对齐：
     * 非 Relay 设备且 deviceId 为空时映射为 "direct"，否则按实际 deviceId。 */
    public static String forDevice(String baseUrl, boolean relayDevice, String deviceId) {
        String id = deviceId == null ? "" : deviceId;
        if (!relayDevice && id.isEmpty()) return direct(baseUrl);
        return relay(baseUrl, id);
    }
}
