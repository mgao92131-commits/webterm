// Shared tunnel protocol constants — safe for both Node.js and browser.
// These are the canonical definitions; do not duplicate elsewhere.

// Message type codes (terminal protocol)
export const MSG_INPUT = 0x01;
export const MSG_OUTPUT = 0x02;
export const MSG_RESIZE = 0x03;
export const MSG_HELLO = 0x04;
export const MSG_INFO = 0x05;
export const MSG_EXIT = 0x06;
export const MSG_PING = 0x07;
export const MSG_PONG = 0x08;
export const MSG_TITLE = 0x09;

// WebSocket subprotocol identifiers
export const BINARY_SUBPROTOCOL = 'webterm.binary.v1';
export const JSON_SUBPROTOCOL = 'webterm.json.v1';

// Tunnel control plane message types
export const AGENT_REGISTER = 'agent-register';
export const REGISTERED = 'registered';
export const ERROR = 'error';
export const HTTP_REQUEST = 'http-request';
export const HTTP_RESPONSE = 'http-response';
export const HTTP_ERROR = 'http-error';
export const WS_CONNECT = 'ws-connect';
export const WS_CONNECTED = 'ws-connected';
export const WS_ERROR = 'ws-error';
export const WS_CLOSE = 'ws-close';

// Binary multiplexed frame message types
export const MSG_TYPE_WS_DATA = 0x01;
export const MSG_TYPE_HTTP_CHUNK = 0x02;

// Payload type / flag bytes
export const WS_DATA_TEXT = 0x01;
export const WS_DATA_BINARY = 0x02;
export const HTTP_CHUNK_DATA = 0x01;
export const HTTP_CHUNK_FIN = 0x02;
