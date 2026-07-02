declare module '@shared/constants.js' {
  export const MSG_INPUT: number;
  export const MSG_OUTPUT: number;
  export const MSG_RESIZE: number;
  export const MSG_HELLO: number;
  export const MSG_INFO: number;
  export const MSG_EXIT: number;
  export const MSG_PING: number;
  export const MSG_PONG: number;
  export const MSG_TITLE: number;
  export const MSG_STATE: number;

  export const BINARY_SUBPROTOCOL: string;
  export const JSON_SUBPROTOCOL: string;

  export const AGENT_REGISTER: string;
  export const REGISTERED: string;
  export const ERROR: string;
  export const HTTP_REQUEST: string;
  export const HTTP_RESPONSE: string;
  export const HTTP_ERROR: string;
  export const WS_CONNECT: string;
  export const WS_CONNECTED: string;
  export const WS_ERROR: string;
  export const WS_CLOSE: string;

  export const MSG_TYPE_WS_DATA: number;
  export const MSG_TYPE_HTTP_CHUNK: number;
  export const WS_DATA_TEXT: number;
  export const WS_DATA_BINARY: number;
  export const HTTP_CHUNK_DATA: number;
  export const HTTP_CHUNK_FIN: number;
}
