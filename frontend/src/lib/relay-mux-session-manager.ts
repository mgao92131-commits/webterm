import { BINARY_SUBPROTOCOL, JSON_SUBPROTOCOL } from './mux-protocol';
import {
  RelayMuxChannel,
  RelayMuxSession,
  type RelayMuxTransport,
  type RelayMuxTransportFactory,
  webSocketRelayMuxTransportFactory,
} from './relay-mux-session';

type OptionalTransportProvider = (deviceId: string) => RelayMuxTransport | null;

class RelayMuxSessionManager {
  private sessions = new Map<string, RelayMuxSession>();
  private transportProvider: OptionalTransportProvider | null = null;

  setTransportProvider(provider: OptionalTransportProvider | null) {
    this.transportProvider = provider;
  }

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

  reconnectDevice(deviceId: string, reason = 'mux transport changed') {
    const session = this.sessions.get(deviceId);
    if (!session) return;
    session.reconnect(reason);
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
      session = new RelayMuxSession(deviceId, this.createTransportFactory());
      this.sessions.set(deviceId, session);
    }
    return session;
  }

  private createTransportFactory(): RelayMuxTransportFactory {
    return (deviceId) => this.transportProvider?.(deviceId) ?? webSocketRelayMuxTransportFactory(deviceId);
  }
}

export function localSessionIdForDevice(sessionId: string, deviceId: string): string {
  const prefix = `${deviceId}:`;
  return sessionId.startsWith(prefix) ? sessionId.slice(prefix.length) : sessionId;
}

export const relayMuxSessionManager = new RelayMuxSessionManager();
