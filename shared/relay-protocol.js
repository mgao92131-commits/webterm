/**
 * Relay protocol — shared between relay-server and pc-agent.
 *
 * Control plane: JSON text WebSocket messages.
 * Data plane: binary WebSocket frames with session-ID prefix wrapping
 *             the existing webterm.binary.v1 terminal frames.
 */

// Re-export terminal protocol constants so consumers only need one import.
export {
  MSG_INPUT, MSG_OUTPUT, MSG_RESIZE, MSG_HELLO,
  MSG_INFO, MSG_EXIT, MSG_PING, MSG_PONG, MSG_TITLE,
  BINARY_SUBPROTOCOL, JSON_SUBPROTOCOL,
  encodeOutput, encodeJSON, encodeEmpty, decodeJSONPayload,
  readUint64BE, writeUint64BE,
} from '../server/protocol-binary.js';

// ---------------------------------------------------------------------------
// Relay JSON message types (control plane)
// ---------------------------------------------------------------------------

// Agent → Relay
export const AGENT_REGISTER = 'agent-register';
export const CONNECT_ACCEPT = 'connect-accept';
export const CONNECT_REJECT = 'connect-reject';

// Relay → Agent
export const REGISTERED = 'registered';
export const CONNECT_REQUEST = 'connect-request';
export const CLIENT_PAIRED = 'client-paired';
export const CLIENT_UNPAIRED = 'client-unpaired';

// Client (mobile) → Relay
export const AUTH = 'auth';
export const LIST_DEVICES = 'list-devices';
export const CONNECT_DEVICE = 'connect-device';
export const DISCONNECT_DEVICE = 'disconnect-device';

// Relay → Client (mobile)
export const AUTHENTICATED = 'authenticated';
export const AUTH_FAILED = 'auth-failed';
export const DEVICES = 'devices';
export const CONNECT_PENDING = 'connect-pending';
export const DEVICE_CONNECTED = 'device-connected';
export const DEVICE_REJECTED = 'device-rejected';
export const DEVICE_DISCONNECTED = 'device-disconnected';

// Forwarded between client ↔ agent (relay transparent pass-through)
export const LIST_SESSIONS = 'list-sessions';
export const CREATE_SESSION = 'create-session';
export const CLOSE_SESSION = 'close-session';
export const RENAME_SESSION = 'rename-session';
export const SESSIONS = 'sessions';
export const SESSION_UPDATE = 'session-update';
export const SESSION_CLOSED = 'session-closed';
export const SESSION_CREATED = 'session-created';

// Generic error
export const ERROR = 'error';

// ---------------------------------------------------------------------------
// Session-multiplexed binary frame encoding
// ---------------------------------------------------------------------------
//
// Format:
//   [1 byte : sessionId length (max 255)]
//   [N bytes: sessionId (UTF-8)]
//   [rest   : original webterm.binary.v1 frame]
//

/**
 * Wrap a terminal binary frame with a session-ID prefix.
 */
export function encodeRelayFrame(sessionId, terminalFrame) {
  const idBytes = Buffer.from(sessionId, 'utf8');
  if (idBytes.length > 255) throw new Error('sessionId too long');
  const frame = Buffer.allocUnsafe(1 + idBytes.length + terminalFrame.length);
  frame[0] = idBytes.length;
  idBytes.copy(frame, 1);
  const src = Buffer.isBuffer(terminalFrame) ? terminalFrame : Buffer.from(terminalFrame);
  src.copy(frame, 1 + idBytes.length);
  return frame;
}

/**
 * Unwrap a session-multiplexed binary frame.
 * Returns { sessionId, terminalFrame } or null on malformed input.
 */
export function decodeRelayFrame(data) {
  const buf = Buffer.from(data);
  if (buf.length < 2) return null;
  const idLen = buf[0];
  if (buf.length < 1 + idLen + 1) return null;
  const sessionId = buf.subarray(1, 1 + idLen).toString('utf8');
  const terminalFrame = buf.subarray(1 + idLen);
  return { sessionId, terminalFrame };
}

// ---------------------------------------------------------------------------
// JSON helpers
// ---------------------------------------------------------------------------

export function sendJSON(ws, message) {
  if (ws.readyState !== 1) return false;
  try {
    ws.send(JSON.stringify(message));
    return true;
  } catch {
    return false;
  }
}

export function sendBinary(ws, data) {
  if (ws.readyState !== 1) return false;
  try {
    ws.send(data);
    return true;
  } catch {
    return false;
  }
}
