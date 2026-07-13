/**
 * 终端会话业务服务。
 * 封装会话列表查询、创建、关闭、重命名等用例。
 */

import { httpClient } from '../api/client';
import { parseSessionId } from '../utils/session';
import type { Session } from '../store';

export type { Session };

export async function fetchSessions(): Promise<Session[]> {
  const raw = await httpClient('/api/sessions', { method: 'GET' });
  return Array.isArray(raw) ? raw : [];
}

export async function createSession(): Promise<Session> {
  return httpClient('/api/sessions', { method: 'POST', body: JSON.stringify({}) });
}

export async function closeSession(sessionId: string): Promise<void> {
  const { localId } = parseSessionId(sessionId);
  await httpClient(`/api/sessions/${encodeURIComponent(localId)}`, { method: 'DELETE' });
}
