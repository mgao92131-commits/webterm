/**
 * Tunnel protocol shared by relay clients, agents, and browser/mobile mux code.
 * Universal HTTP/WebSocket tunneling and proxying.
 */

// Re-export terminal protocol constants
export {
  MSG_INPUT, MSG_OUTPUT, MSG_RESIZE, MSG_HELLO,
  MSG_INFO, MSG_EXIT, MSG_PING, MSG_PONG, MSG_TITLE, MSG_STATE,
  BINARY_SUBPROTOCOL, JSON_SUBPROTOCOL,
} from './constants.js';

export {
  AGENT_REGISTER, REGISTERED, ERROR,
  HTTP_REQUEST, HTTP_RESPONSE, HTTP_ERROR,
  WS_CONNECT, WS_CONNECTED, WS_ERROR, WS_CLOSE,
  MSG_TYPE_WS_DATA, MSG_TYPE_HTTP_CHUNK,
  WS_DATA_TEXT, WS_DATA_BINARY,
  HTTP_CHUNK_DATA, HTTP_CHUNK_FIN,
} from './constants.js';

// ---------------------------------------------------------------------------
// Frame Multiplexing Encoders & Decoders
// ---------------------------------------------------------------------------

/**
 * Wrap a payload inside a multiplexed tunnel frame.
 * Format:
 *   [1 byte : message type]
 *   [1 byte : ID length (max 255)]
 *   [M bytes: Connection or Request ID (UTF-8)]
 *   [1 byte : payload type / flags]
 *   [rest   : raw binary data]
 */
export function encodeTunnelFrame(msgType, id, extraByte, payload) {
  const idBytes = Buffer.from(id, 'utf8');
  if (idBytes.length > 255) {
    throw new Error('Tunnel connection/request ID is too long');
  }
  const src = Buffer.isBuffer(payload) 
    ? payload 
    : (payload ? Buffer.from(payload) : Buffer.alloc(0));

  const frame = Buffer.allocUnsafe(1 + 1 + idBytes.length + 1 + src.length);
  frame[0] = msgType;
  frame[1] = idBytes.length;
  idBytes.copy(frame, 2);
  frame[2 + idBytes.length] = extraByte;
  src.copy(frame, 3 + idBytes.length);
  return frame;
}

/**
 * Decode a multiplexed tunnel frame.
 * Returns { msgType, id, extraByte, payload }
 */
export function decodeTunnelFrame(data) {
  const buf = Buffer.from(data);
  if (buf.length < 3) return null;
  const msgType = buf[0];
  const idLen = buf[1];
  if (buf.length < 2 + idLen + 1) return null;
  const id = buf.subarray(2, 2 + idLen).toString('utf8');
  const extraByte = buf[2 + idLen];
  const payload = buf.subarray(3 + idLen);
  return { msgType, id, extraByte, payload };
}

// ---------------------------------------------------------------------------
// WebSocket Helpers
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
