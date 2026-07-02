const MSG_INPUT = 0x01;
const MSG_OUTPUT = 0x02;
const MSG_RESIZE = 0x03;
const MSG_HELLO = 0x04;
const MSG_INFO = 0x05;
const MSG_EXIT = 0x06;
const MSG_STATE = 0x0a;

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function encodeTerminalMessage(msg: any): Uint8Array {
  if (msg.type === 'hello') {
    return encodeJSON(MSG_HELLO, {
      lastSeq: Number(msg.lastSeq || 0),
      cols: Number(msg.cols || 0),
      rows: Number(msg.rows || 0),
    });
  }
  if (msg.type === 'input') {
    return encodeBytes(MSG_INPUT, encoder.encode(String(msg.data || '')));
  }
  if (msg.type === 'resize') {
    return encodeJSON(MSG_RESIZE, {
      cols: Number(msg.cols || 0),
      rows: Number(msg.rows || 0),
    });
  }
  return encodeJSON(MSG_HELLO, msg);
}

export function decodeTerminalMessage(data: ArrayBuffer | Uint8Array): any | null {
  const frame = data instanceof Uint8Array ? data : new Uint8Array(data);
  if (frame.length === 0) return null;
  const type = frame[0];
  const payload = frame.slice(1);
  if (type === MSG_OUTPUT || type === MSG_STATE) {
    if (payload.length < 8) return null;
    const view = new DataView(payload.buffer, payload.byteOffset, payload.byteLength);
    const seq = Number(view.getBigUint64(0, false));
    const text = decoder.decode(payload.slice(8));
    return { type: type === MSG_OUTPUT ? 'output' : 'state', seq, data: text };
  }
  if (type === MSG_INFO) {
    return { type: 'info', data: decodeJSON(payload) };
  }
  if (type === MSG_EXIT) {
    const body = decodeJSON(payload);
    return { type: 'exit', code: Number(body.code || 0) };
  }
  return null;
}

function encodeJSON(type: number, value: any): Uint8Array {
  return encodeBytes(type, encoder.encode(JSON.stringify(value || {})));
}

function encodeBytes(type: number, payload: Uint8Array): Uint8Array {
  const frame = new Uint8Array(1 + payload.length);
  frame[0] = type;
  frame.set(payload, 1);
  return frame;
}

function decodeJSON(payload: Uint8Array): any {
  if (payload.length === 0) return {};
  try {
    return JSON.parse(decoder.decode(payload));
  } catch {
    return {};
  }
}
