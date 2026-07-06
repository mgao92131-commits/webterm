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

/**
 * 按注意力优先级排序会话。
 * 需要用户处理的状态置顶，避免通知被淹没。
 */
export function sortSessionsByAttention<T extends { agentState?: string; notification?: { level?: string }; state?: string }>(
  sessions: T[],
): T[] {
  const priority = (s: T): number => {
    if (s.agentState === 'approval_required' || s.agentState === 'failed' || s.notification?.level === 'error') return 0;
    if (s.notification) return 1;
    if (s.agentState === 'running' || s.state === 'running') return 2;
    return 3;
  };
  return [...sessions].sort((a, b) => priority(a) - priority(b));
}
