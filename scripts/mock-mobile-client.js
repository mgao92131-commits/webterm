import WebSocket from 'ws';
import {
  AUTH, LIST_DEVICES, CONNECT_DEVICE,
  AUTHENTICATED, DEVICES, DEVICE_CONNECTED,
  CREATE_SESSION, SESSION_CREATED,
  encodeRelayFrame, decodeRelayFrame,
  sendJSON, sendBinary
} from '../shared/relay-protocol.js';

const RELAY_WS_URL = 'ws://127.0.0.1:9000/ws/relay';
const USERNAME = 'admin';
const PASSWORD = 'admin_relay_password';

console.log('Connecting to Relay Server as a Mobile Client...');
const ws = new WebSocket(RELAY_WS_URL);

ws.on('open', () => {
  console.log('Connected to Relay Server. Sending auth request...');
  // 1. 发送登录认证
  sendJSON(ws, {
    type: AUTH,
    username: USERNAME,
    password: PASSWORD
  });
});

ws.on('message', (data, isBinary) => {
  if (isBinary) {
    // 收到终端二进制数据
    const frame = decodeRelayFrame(data);
    if (!frame) return;
    const { sessionId, terminalFrame } = frame;
    const type = terminalFrame[0];
    const payload = terminalFrame.subarray(1);
    
    if (type === 0x02) { // MSG_OUTPUT
      // 提取输出的文本（前8字节是seq，后面是真正的数据）
      if (payload.length > 8) {
        const text = payload.subarray(8).toString('utf8');
        process.stdout.write(text);
      }
    }
    return;
  }

  // JSON 消息
  let msg;
  try {
    msg = JSON.parse(data.toString('utf8'));
  } catch {
    return;
  }

  console.log(`\n[JSON Msg Received]:`, msg.type);

  switch (msg.type) {
    case AUTHENTICATED:
      console.log('Authenticated successfully! Listing online devices...');
      // 2. 认证成功，请求设备列表
      sendJSON(ws, { type: LIST_DEVICES });
      break;

    case DEVICES:
      console.log('Online Devices:', msg.devices);
      if (msg.devices.length === 0) {
        console.log('No online PC Agents found. Please make sure PC Agent is running.');
        ws.close();
        return;
      }
      // 3. 尝试连接列表中的第一个设备
      const targetDevice = msg.devices[0].deviceId;
      console.log(`Connecting to device: ${targetDevice}... (Please check your screen for the OS dialog prompt!)`);
      sendJSON(ws, { type: CONNECT_DEVICE, deviceId: targetDevice });
      break;

    case DEVICE_CONNECTED:
      console.log(`Successfully paired and connected to device ${msg.deviceId}!`);
      // 4. 连接成功，创建一个新终端会话
      console.log('Creating a new shell session...');
      sendJSON(ws, {
        type: CREATE_SESSION,
        name: 'Mock Shell',
        cwd: process.cwd()
      });
      break;

    case SESSION_CREATED:
      const session = msg.session;
      console.log(`Session created! ID: ${session.id}. Initiating hand-shake (MSG_HELLO)...`);
      
      // 5. 模拟发送 MSG_HELLO 握手（0x04），payload 传入 { lastSeq: 0 }
      const helloPayload = Buffer.from(JSON.stringify({ lastSeq: 0 }), 'utf8');
      const terminalFrame = Buffer.concat([Buffer.from([0x04]), helloPayload]);
      const helloRelayFrame = encodeRelayFrame(session.id, terminalFrame);
      sendBinary(ws, helloRelayFrame);

      // 6. 3秒后模拟用户在终端输入命令
      setTimeout(() => {
        console.log('\n--- Sending interactive command: "echo Hello from Mock Mobile Client!" ---');
        const cmd = 'echo Hello from Mock Mobile Client!\r';
        const inputFrame = Buffer.concat([Buffer.from([0x01]), Buffer.from(cmd, 'utf8')]);
        const inputRelayFrame = encodeRelayFrame(session.id, inputFrame);
        sendBinary(ws, inputRelayFrame);
      }, 3000);
      break;

    default:
      break;
  }
});

ws.on('close', () => {
  console.log('Connection closed.');
});

ws.on('error', (err) => {
  console.error('WebSocket Error:', err);
});
