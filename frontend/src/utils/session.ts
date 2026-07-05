export interface ParsedSessionId {
  /** 设备标识部分，仅当 sessionId 为 deviceId:localId 格式时存在 */
  deviceId?: string;
  /** 本地会话标识 */
  localId: string;
}

/**
 * 解析会话 ID。
 * 后端在 relay 模式下可能返回 composite id（如 deviceId:localId），
 * 统一在此处理，避免解析逻辑散落在各 service 中。
 */
export function parseSessionId(sessionId: string): ParsedSessionId {
  const separatorIndex = sessionId.indexOf(':');
  if (separatorIndex > -1) {
    return {
      deviceId: sessionId.slice(0, separatorIndex),
      localId: sessionId.slice(separatorIndex + 1),
    };
  }
  return { localId: sessionId };
}
