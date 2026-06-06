const WebSocket = require("ws");

const base = process.env.WEBTERM_URL || "http://127.0.0.1:8080";
const username = process.env.WEBTERM_USER || "admin";
const password = process.env.WEBTERM_PASSWORD;
const command = process.argv.slice(2).join(" ") || "printf 'ANDROID_SMOKE_OK\\n'";
const expected = process.env.WEBTERM_SMOKE_EXPECT || "ANDROID_SMOKE_OK";
const expectedOccurrences = command.includes(expected) ? 2 : 1;

if (!password) {
  throw new Error("WEBTERM_PASSWORD must be set");
}

main().catch((err) => {
  console.error(err.message || err);
  process.exit(1);
});

async function main() {
  const cookie = await login();
  const session = await createSession(cookie);
  try {
    const output = await runCommand(cookie, session.id, command + "\r");
    process.stdout.write(output);
    if (countOccurrences(output, expected) < expectedOccurrences) {
      throw new Error(`smoke output did not include ${expected} ${expectedOccurrences} time(s)`);
    }
  } finally {
    await deleteSession(cookie, session.id).catch(() => {});
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
    body: JSON.stringify({ name: "Android smoke" }),
  });
  if (!res.ok) throw new Error(`session create failed: ${res.status} ${await res.text()}`);
  return res.json();
}

async function deleteSession(cookie, sessionID) {
  await fetch(base + `/api/sessions/${encodeURIComponent(sessionID)}`, {
    method: "DELETE",
    headers: { Cookie: cookie },
  });
}

function runCommand(cookie, sessionID, input) {
  return new Promise((resolve, reject) => {
    const wsURL = base.replace(/^http:/, "ws:").replace(/^https:/, "wss:")
      + `/ws/sessions/${encodeURIComponent(sessionID)}`;
    const ws = new WebSocket(wsURL, { headers: { Cookie: cookie } });
    let output = "";
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      ws.close();
      resolve(output);
    }, 3000);

    ws.on("open", () => {
      send(ws, 0x04, Buffer.from(JSON.stringify({ lastSeq: 0 })));
      send(ws, 0x03, Buffer.from(JSON.stringify({ cols: 100, rows: 30 })));
      setTimeout(() => send(ws, 0x01, Buffer.from(input)), 250);
    });

    ws.on("message", (data, isBinary) => {
      if (!isBinary) return;
      const frame = Buffer.from(data);
      if (frame[0] === 0x02 && frame.length >= 9) {
        output += frame.subarray(9).toString("utf8");
        if (countOccurrences(output, expected) >= expectedOccurrences && !settled) {
          settled = true;
          clearTimeout(timer);
          ws.close();
          resolve(output);
        }
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

function countOccurrences(value, needle) {
  if (!needle) return 0;
  let count = 0;
  let index = 0;
  while ((index = value.indexOf(needle, index)) >= 0) {
    count += 1;
    index += needle.length;
  }
  return count;
}
