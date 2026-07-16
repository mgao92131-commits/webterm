package com.webterm.core.api;

/** Relay 设备连接标识（connectionKey）的统一计算。 */
public final class DeviceConnectionKeys {
	private DeviceConnectionKeys() {}

	public static String relay(String baseUrl, String deviceId) {
		return WebTermUrls.normalizeBaseUrl(baseUrl) + "\n" + (deviceId == null ? "" : deviceId);
	}

	public static String forDevice(String baseUrl, String deviceId) {
		return relay(baseUrl, deviceId);
	}
}
