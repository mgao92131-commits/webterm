import { BINARY_SUBPROTOCOL, JSON_SUBPROTOCOL } from './mux-protocol';
import {
  RelayMuxChannel,
  RelayMuxSession,
  webSocketRelayMuxTransportFactory,
} from './relay-mux-session';

class RelayMuxSessionManager {
  private sessions = new Map<string, RelayMuxSession>();

  openManagerChannel(deviceId: string): RelayMuxChannel {
    return this.forDevice(deviceId).openChannel(`manager:${deviceId}`, '/ws/sessions', [JSON_SUBPROTOCOL]);
  }

  openTerminalChannel(deviceId: string, sessionId: string): RelayMuxChannel {
    const localSessionId = localSessionIdForDevice(sessionId, deviceId);
    return this.forDevice(deviceId).openChannel(
      `term:${localSessionId}`,
      `/ws/sessions/${encodeURIComponent(localSessionId)}`,
      [BINARY_SUBPROTOCOL],
    );
  }

  closeDevice(deviceId: string) {
    const session = this.sessions.get(deviceId);
    if (!session) return;
    session.stop();
    this.sessions.delete(deviceId);
  }

  closeAll() {
    for (const session of this.sessions.values()) {
      session.stop();
    }
    this.sessions.clear();
  }

  private forDevice(deviceId: string): RelayMuxSession {
    let session = this.sessions.get(deviceId);
    if (!session) {
      session = new RelayMuxSession(deviceId, webSocketRelayMuxTransportFactory);
      this.sessions.set(deviceId, session);
    }
    return session;
  }
}

export function localSessionIdForDevice(sessionId: string, deviceId: string): string {
  const prefix = `${deviceId}:`;
  return sessionId.startsWith(prefix) ? sessionId.slice(prefix.length) : sessionId;
}

export const relayMuxSessionManager = new RelayMuxSessionManager();
