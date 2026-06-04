const WebSocket = require("ws");

const base = process.env.WEBTERM_GO_URL || "http://100.121.115.14:8081";
const username = process.env.WEBTERM_USER || "gao";
const password = process.env.WEBTERM_PASSWORD;

if (!password) {
  throw new Error("WEBTERM_PASSWORD must be set");
}

const input = process.argv.slice(2).join(" ") || "echo GO_BINARY_SMOKE_OK";

main().catch((err) => {
  console.error(err.message || err);
  process.exit(1);
});

async function main() {
  const cookie = await login();
  const session = await createSession(cookie);
  const output = await runCommand(cookie, session.id, input + "\r");
  process.stdout.write(output);
  if (!output.includes(input.replace(/^echo\s+/, ""))) {
    throw new Error("smoke output did not include expected command result");
  }
}

async function login() {
  const res = await fetch(base + "/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) throw new Error(`login failed: ${res.status} ${await res.text()}`);
  const setCookie = res.headers.get("set-cookie") || "";
  const cookie = setCookie.split(";")[0];
  if (!cookie) throw new Error("login did not return a cookie");
  return cookie;
}

async function createSession(cookie) {
  const res = await fetch(base + "/api/sessions", {
    method: "POST",
    headers: { "Content-Type": "application/json", Cookie: cookie },
    body: JSON.stringify({ name: "go-smoke" }),
  });
  if (!res.ok) throw new Error(`session create failed: ${res.status} ${await res.text()}`);
  return res.json();
}

function runCommand(cookie, sessionID, command) {
  return new Promise((resolve, reject) => {
    const wsURL = base.replace(/^http:/, "ws:").replace(/^https:/, "wss:") + `/ws/sessions/${encodeURIComponent(sessionID)}`;
    const ws = new WebSocket(wsURL, { headers: { Cookie: cookie } });
    let output = "";
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      ws.close();
      resolve(output);
    }, 2500);

    ws.on("open", () => {
      send(ws, 0x04, Buffer.from(JSON.stringify({ lastSeq: 0 })));
      send(ws, 0x03, Buffer.from(JSON.stringify({ cols: 100, rows: 30 })));
      setTimeout(() => send(ws, 0x01, Buffer.from(command)), 250);
    });

    ws.on("message", (data) => {
      const frame = Buffer.from(data);
      if (frame[0] === 0x02 && frame.length >= 9) {
        output += frame.subarray(9).toString("utf8");
      }
    });

    ws.on("error", (err) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      reject(err);
    });
  });
}

function send(ws, type, payload = Buffer.alloc(0)) {
  ws.send(Buffer.concat([Buffer.from([type]), payload]));
}
