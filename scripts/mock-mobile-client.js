import WebSocket from 'ws';
import {
  LIST_DEVICES,
  CREATE_SESSION, SESSION_CREATED,
  encodeRelayFrame, decodeRelayFrame,
  sendJSON, sendBinary
} from '../shared/relay-protocol.js';

// 模拟 App/网页客户端通过 WebSocket 连接到 relay-server
const RELAY_WS_URL = 'ws://127.0.0.1:9000/ws/sessions';

console.log('Connecting to Relay Server control channel...');
const ws = new WebSocket(RELAY_WS_URL);

ws.on('open', () => {
  console.log('Connected. Listening for device/session updates...');
});

ws.on('message', (data, isBinary) => {
  if (isBinary) return;

  let msg;
  try {
    msg = JSON.parse(data.toString('utf8'));
  } catch {
    return;
  }

  console.log(`[Msg Received]:`, msg.type);

  switch (msg.type) {
    case 'devices':
      console.log('Online Devices:', msg.devices);
      if (msg.devices.length === 0) {
        console.log('No online PC Agents found.');
        ws.close();
        return;
      }
      // 选定第一个设备，请求设备列表
      const targetDevice = msg.devices[0].deviceId;
      console.log(`Selected device: ${targetDevice}`);
      sendJSON(ws, { type: LIST_DEVICES });
      break;

    case 'sessions':
      console.log('Active sessions:', msg.data);
      if (msg.data && msg.data.length > 0) {
        const session = msg.data[0];
        console.log(`Opening terminal for session: ${session.id}`);
        openTerminalSession(session.id);
      } else {
        console.log('No active sessions. Run the web UI to create one.');
      }
      break;

    case 'session':
      console.log('Session updated:', msg.data);
      break;

    case 'session-closed':
      console.log('Session closed:', msg.id);
      break;

    default:
      break;
  }
});

function openTerminalSession(globalSessionId) {
  const [deviceId, localId] = globalSessionId.split(':');
  const termWs = new WebSocket(`ws://127.0.0.1:9000/ws/sessions/${encodeURIComponent(globalSessionId)}`);

  termWs.on('open', () => {
    console.log(`Terminal WS connected for ${globalSessionId}`);

    // MSG_HELLO handshake
    const helloPayload = Buffer.from(JSON.stringify({ lastSeq: 0 }), 'utf8');
    const terminalFrame = Buffer.concat([Buffer.from([0x04]), helloPayload]);
    const relayFrame = encodeRelayFrame(localId, terminalFrame);
    sendBinary(termWs, relayFrame);

    // 3秒后发送一条命令
    setTimeout(() => {
      console.log('--- Sending: "echo Hello from Mock Client!" ---');
      const cmd = 'echo Hello from Mock Client!\r';
      const inputFrame = Buffer.concat([Buffer.from([0x01]), Buffer.from(cmd, 'utf8')]);
      const inputRelayFrame = encodeRelayFrame(localId, inputFrame);
      sendBinary(termWs, inputRelayFrame);
    }, 3000);
  });

  termWs.on('message', (data, isBinary) => {
    if (isBinary) {
      const frame = decodeRelayFrame(data);
      if (!frame) return;
      const { terminalFrame } = frame;
      const type = terminalFrame[0];
      const payload = terminalFrame.subarray(1);
      if (type === 0x02 && payload.length > 8) {
        process.stdout.write(payload.subarray(8).toString('utf8'));
      }
    }
  });

  termWs.on('close', () => console.log('Terminal WS closed.'));
  termWs.on('error', (err) => console.error('Terminal WS Error:', err));
}

ws.on('close', () => console.log('Control channel closed.'));
ws.on('error', (err) => console.error('WebSocket Error:', err));
