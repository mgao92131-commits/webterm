# WebTerm Go Server

This is the native Android-focused WebTerm server. It keeps the same basic HTTP
login and session API shape as the Node server, but terminal traffic on
`/ws/sessions/:id` is a binary PTY stream.

## Run

From the repository root:

```sh
go-server/scripts/build.sh
WEBTERM_GO_ADDR=100.121.115.14:8081 go-server/scripts/start.sh
```

The server reads `.env.local` from the repository root. Useful values:

```sh
WEBTERM_USER=gao
WEBTERM_PASSWORD=...
WEBTERM_ADDR=100.121.115.14:8080
WEBTERM_GO_ADDR=100.121.115.14:8081
WEBTERM_SHELL=/bin/zsh
WEBTERM_CWD=/Users/gao/Documents/webterm
WEBTERM_ZDOTDIR=/Users/gao/Documents/webterm/go-server/runtime/zsh
```

If `WEBTERM_GO_ADDR` is not set, the server derives the Go port from
`WEBTERM_ADDR` by keeping the same host and using port `8081`.

For zsh, `WEBTERM_ZDOTDIR` points to a small wrapper that sources the user's
normal zsh files and then applies `WEBTERM_CWD`. This keeps the normal prompt,
colors, PATH, and aliases without permanently changing the user's global
`.zshrc`.

## API

The first version keeps these endpoints:

```text
POST   /api/login
GET    /api/me
GET    /api/sessions
POST   /api/sessions
PATCH  /api/sessions/:id
DELETE /api/sessions/:id
GET    /ws/sessions/:id
```

## Binary WebSocket Protocol

Every WebSocket message is binary. The first byte is the message type. The rest
is the payload.

```text
0x01 input   client -> server  raw terminal input bytes
0x02 output  server -> client  8-byte big-endian seq + raw PTY output bytes
0x03 resize  client -> server  JSON: {"cols":100,"rows":30}
0x04 hello   client -> server  JSON: {"lastSeq":0}
0x05 info    server -> client  JSON session info
0x06 exit    server -> client  JSON: {"code":0}
0x07 ping    client -> server
0x08 pong    server -> client
```

Terminal input and output are intentionally raw bytes, so the Android client can
feed output straight into Termux's native terminal emulator.

## Android Client

The Android client in `android-client/` defaults to:

```text
http://100.121.115.14:8081
```

It uses the same login/session HTTP API and the binary WebSocket protocol above.
