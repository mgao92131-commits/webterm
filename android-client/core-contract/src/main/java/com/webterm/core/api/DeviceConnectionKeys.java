package com.webterm.core.api;

/** 设备连接标识（connectionKey）的统一计算。Direct 与 Relay 使用互不冲突的键空间。 */
public final class DeviceConnectionKeys {
	private DeviceConnectionKeys() {}

	/** Relay 设备键：baseUrl + deviceId（保持既有格式，避免破坏已缓存的连接身份）。 */
	public static String relay(String baseUrl, String deviceId) {
		return WebTermUrls.normalizeBaseUrl(baseUrl) + "\n" + (deviceId == null ? "" : deviceId);
	}

	public static String forDevice(String baseUrl, String deviceId) {
		return relay(baseUrl, deviceId);
	}

	/**
	 * Direct 设备键：{@code direct:{serverConfigId}}。以持久化的 configId 为准，
	 * 保证同一地址不同账户、或多个 Direct 配置之间不会发生连接冲突；configId 缺失
	 * 时回落到规范化 URL。前缀 {@code direct:} 与 Relay 键（以 http(s):// 开头）天然不冲突。
	 */
	public static String direct(String serverConfigId, String baseUrl) {
		String identity = !safe(serverConfigId).isEmpty()
			? serverConfigId
			: WebTermUrls.normalizeBaseUrl(baseUrl);
		return "direct:" + identity;
	}

	/**
	 * 统一的 connectionKey 计算入口：调用方必须显式传入 {@code directDevice}，
	 * 不要通过“deviceId 是否为空”推断 Direct（旧 Relay 或异常配置也可能出现空 deviceId）。
	 */
	public static String resolve(boolean directDevice, String serverConfigId, String baseUrl, String deviceId) {
		return directDevice
			? direct(serverConfigId, baseUrl)
			: relay(baseUrl, deviceId);
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}
}
