package com.webterm.feature.terminal.domain;

import androidx.annotation.NonNull;

import com.webterm.core.api.SessionIds;
import com.webterm.core.api.WebTermUrls;

import java.util.Objects;

/** 防止跨服务器、账号、Relay 设备误复用终端投影的完整 Runtime 身份。 */
public final class TerminalRuntimeKey {
  public final String serverConfigId;
  public final String authIdentity;
  public final String normalizedBaseUrl;
  public final String relayDeviceId;
  public final String sessionId;

  public TerminalRuntimeKey(String serverConfigId, String authIdentity, String baseUrl,
                            String relayDeviceId, String sessionId) {
    this.serverConfigId = clean(serverConfigId);
    this.authIdentity = clean(authIdentity);
    this.normalizedBaseUrl = WebTermUrls.normalizeBaseUrl(baseUrl);
    this.relayDeviceId = clean(relayDeviceId);
    // 归一化为本地会话 ID：上游入口可能传 "d2:s6" 或 "s6"，两者指向同一终端，
    // 通道层（TerminalChannel）也会剥离前缀，Key 必须同样归一，避免双 Runtime 互相接管。
    this.sessionId = SessionIds.local(clean(sessionId), this.relayDeviceId);
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof TerminalRuntimeKey)) return false;
    TerminalRuntimeKey that = (TerminalRuntimeKey) other;
    return serverConfigId.equals(that.serverConfigId)
        && authIdentity.equals(that.authIdentity)
        && normalizedBaseUrl.equals(that.normalizedBaseUrl)
        && relayDeviceId.equals(that.relayDeviceId)
        && sessionId.equals(that.sessionId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(serverConfigId, authIdentity, normalizedBaseUrl, relayDeviceId, sessionId);
  }

  @NonNull
  @Override
  public String toString() {
    // 不包含 cookie/token；authIdentity 应是账号或 generation，不是凭据正文。
    return serverConfigId + ":" + authIdentity + ":" + normalizedBaseUrl + ":"
        + relayDeviceId + ":" + sessionId;
  }
}
