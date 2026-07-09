# WebTerm File Send and Android Device Service Refactor Plan

## 1. Goal

Turn the current PC-to-Android file transfer prototype into a long-term architecture:

- User command is `webterm send <file>`.
- Old `download` / `dl` naming is removed, not kept for compatibility.
- Android owns long-lived remote-device connections in one app-level service.
- One Android service can manage multiple remote device connections.
- Each remote device connection has at most one physical WS/mux connection.
- Terminal, manager, and file-send flows reuse the same `DeviceConnection`.
- File transfer succeeds only after Android has saved the file and reports `saved`.
- File receiving uses foreground-service notifications when it needs background reliability.

The current prototype already proves the data path can work. This refactor keeps the useful HTTP stream data path, but moves ownership, lifecycle, and status semantics to the right layers.

## 2. Current Problems To Fix

The current implementation still carries prototype assumptions:

- File transfer is named as `download`, even though the user action is "send this PC file to Android".
- File transfer is tied to terminal hooks and `TerminalConnection`.
- Android must be in or near the terminal page to receive the offer.
- The task lifecycle lives in session/terminal code instead of a file-transfer service.
- Agent-side stream EOF can be treated as completion, but Android may not have saved the file yet.
- Android save failures are local UI events and do not reliably fail the CLI immediately.
- Relay file streams still risk inheriting normal request timeout behavior.
- Multi-device connection ownership is not centralized in an Android service registry.

## 3. Target Android Architecture

Use one Android service as the owner for long-lived device-connection policy, foreground lifetime, and notifications:

```text
WebTermDeviceService
  - RelayMuxSessionRegistry / DeviceConnectionRegistry facade
  - FileReceiveController
  - NotificationController
  - TerminalChannelRegistry
```

Important existing-code constraint:

`android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionRegistry.java` already does the core registry job:

- `forDevice(baseUrl, cookie, deviceId)`
- keying by normalized base URL + device ID
- excluding rotating cookie from identity
- `releaseIfIdle(...)`
- `shutdown()`

Do not build a second registry beside it. The refactor should either:

1. Evolve `RelayMuxSessionRegistry` into the service-owned connection registry, or
2. Add a thin `DeviceConnectionRegistry` facade that delegates to `RelayMuxSessionRegistry` during migration.

The default plan is option 2 first, then option 1 only if the wrapper becomes needless.

The service/registry pair manages multiple remote connections:

```text
RelayMuxSessionRegistry / DeviceConnectionRegistry facade
  - DeviceConnection(serverA/deviceA)
  - DeviceConnection(serverB/deviceB)
  - DeviceConnection(serverC/deviceC)
```

Each `DeviceConnection` owns one physical transport and its mux state:

```text
DeviceConnection
  - connectionKey
  - serverId / baseUrl / account identity
  - remoteDeviceId
  - auth/cookie provider
  - WS or P2P-backed mux transport
  - manager/control channel
  - terminal virtual channels
  - file-send control channel
  - reconnect and backoff state
```

Connection key:

```text
canonicalUrl + "\n" + accountIdentity + "\n" + remoteDeviceId
```

The current registry key already uses `normalizeBaseUrl(baseUrl) + "\n" + deviceId`. The migration must preserve this behavior for relay-device identity and add account identity only after the source is well-defined.

Account identity source:

- Relay mode: stable user id from relay auth state when available; otherwise a hash of the authenticated cookie/token as a temporary identity.
- Direct mode: fixed local placeholder such as `direct`.
- Device ID: remote relay device id; direct mode uses an empty or local placeholder device id.

If a compact key is needed for maps/logging, use `sha256(canonicalUrl + "\n" + accountIdentity + "\n" + remoteDeviceId)`. Keep the structured fields on the connection object for diagnostics.

Rules:

- Activity and Fragment bind to `WebTermDeviceService`; they do not own WS connections.
- `TerminalRuntime` owns UI state and a terminal virtual channel, not the physical connection.
- `FileReceiveController` owns all Android receive tasks and routes them by `connectionKey` and `transferId`.
- `NotificationController` is the single owner of connection and transfer notifications.
- The Hilt `@Singleton` registry remains the canonical connection store during migration; the Service is the foreground/lifecycle host and policy coordinator, not a competing owner.

## 4. Connection Retention Policy

The service should not blindly connect to every known device forever. It should centralize policy:

- Current visible device: keep connected.
- Device with active terminal channel: keep connected.
- Device with active file transfer: keep connected and foreground if needed.
- Device marked by user as "keep online in background": keep connected.
- Idle device with no active channels/tasks: release after idle timeout.
- Device selected by user later: lazy reconnect.

Priority order:

```text
active file transfer
  > active terminal channel
  > current visible device
  > explicit keep-online lease
  > idle timeout
```

Use lease tokens instead of implicit booleans:

```text
ConnectionLease(type=TRANSFER, owner=transferId)
ConnectionLease(type=TERMINAL, owner=sessionId)
ConnectionLease(type=VISIBLE, owner=screenId)
ConnectionLease(type=KEEP_ONLINE, owner=userSetting)
```

A connection is idle only when it has no live leases and no open mux channels. `releaseIfIdle()` may close only then.

Foreground mode is required when:

- A file is being received.
- App is in the background and a transfer must continue.
- User enabled background keep-online.
- A long-running terminal session is explicitly kept alive in background.

Foreground mode is not required for every foreground-page connection with no active long task.

Foreground-service startup rule:

- If the service is started specifically for background receive/keep-online, call `startForeground(...)` immediately with a connection-status notification.
- When a transfer starts, update the same foreground service to a transfer-progress notification or add a per-transfer notification.
- After the last foreground-required lease ends, downgrade with `stopForeground(false)` if no background keep-online lease remains.

## 5. Target Go Architecture

Move file-send lifecycle out of `TerminalSession` and `session.Manager`.

Add:

```text
go-core/internal/filesend/
  service.go
  task.go
  protocol.go
  http_handler.go
```

Responsibilities:

- `FileSendService`
  - Parse `webterm send <file>` commands from the local socket command protocol.
  - Resolve path from CLI cwd and session context.
  - Validate file exists, is regular, and is readable.
  - Create and track `FileSendTask`.
  - Bind task to session and target remote Android device.
  - Send `file_send.offer`.
  - Stream status back to CLI.
  - Handle `accepted`, `progress`, `saved`, `failed`, `cancelled`.
  - Enforce timeout and cleanup.

- `FileSendTask`
  - `transferId`
  - source file path/name/size
  - session id and optional cwd metadata
  - target connection/device id
  - status
  - status channel
  - expiry/cancel function

- HTTP handler
  - Serve `GET /api/file-send/{transferId}` or equivalent.
  - Open source file only for authorized, active task.
  - Stream bytes without buffering the whole file.
  - Do not mark task successful on EOF.

`TerminalSession` should only provide context:

```text
sessionId
cwd/liveCwd
owning device/user information
```

Boundary rule:

`FileSendTask` may read session context only at creation time. After the file path, source metadata, and target identity are resolved, the task has an independent lifecycle. `TerminalSession.Close()` must not cancel an already accepted or actively receiving file-send task unless the user explicitly cancels the transfer.

## 6. Protocol

Rename the command and messages to send/receive semantics.

Control-message carrying layer:

The current mux implementation already has a text control-message path with `OnControl`, and terminal virtual channels are opened by `ws-connect`. The plan should use the mux control-message path for device-level file-send messages, not terminal binary messages.

Required server work:

- Wire `application.MuxServeOpts.OnControl` through direct and relay agent paths.
- Route `file_send.*` control messages to `FileSendService`.
- Keep terminal binary protocol focused on terminal traffic.

This avoids adding another terminal-scoped `MSG_FILE_SEND` frame and keeps file send device-level.

CLI command:

```bash
webterm send ./app-release.apk
```

Go to Android:

```json
{
  "type": "file_send.offer",
  "transfer_id": "t_xxx",
  "connection_key": "...",
  "session_id": "s1",
  "file_name": "app-release.apk",
  "file_size": 15234567
}
```

Android to Go:

```json
{ "type": "file_send.accepted", "transfer_id": "t_xxx" }
{ "type": "file_send.rejected", "transfer_id": "t_xxx", "reason": "user rejected", "retry_allowed": false }
{ "type": "file_send.progress", "transfer_id": "t_xxx", "bytes": 6389760, "total": 15234567 }
{ "type": "file_send.saving", "transfer_id": "t_xxx" }
{ "type": "file_send.saved", "transfer_id": "t_xxx", "path": "Downloads/WebTerm/app-release.apk" }
{ "type": "file_send.failed", "transfer_id": "t_xxx", "error": "space not enough" }
{ "type": "file_send.cancelled", "transfer_id": "t_xxx" }
```

Completion rule:

```text
Android saved == CLI success
HTTP EOF != CLI success
Android failed/cancelled == CLI failure
Android rejected == CLI failure without retry unless retry_allowed is true
```

ID and authorization rules:

- `transfer_id` is generated by Go using CSPRNG, at least 128 bits of entropy.
- `transfer_id` is globally unique within the running agent process.
- The offer also includes a CSPRNG one-time `download_token`.
- Android must present `download_token` when calling the file stream endpoint.
- The token is bound to `transfer_id` and target connection/device identity.
- Token expires when the task reaches a terminal state or times out.
- HTTP responses must set `Cache-Control: no-store`.

Numeric rules:

- `file_size`, `bytes`, and related counters are `int64` / Java `long`.
- Define a configurable maximum file size before implementation; reject larger files before sending an offer.

Offer idempotency:

- Android keeps a task map by `transfer_id`.
- Repeated `file_send.offer` for an existing non-terminal task must not create a second receive task.
- Repeated offer may re-send the current status (`accepted`, `progress`, `saving`) but must not start a second HTTP GET unless an explicit retry/resume protocol is introduced.
- Go should not re-send offers automatically except after a reconnect policy explicitly says to re-offer.

State machine:

```text
created
  -> offered
  -> accepting
  -> accepted
  -> receiving
  -> saving
  -> saved

created/offered/accepting
  -> rejected

created/offered/accepting/accepted/receiving/saving
  -> failed

created/offered/accepting/accepted/receiving
  -> cancelling
  -> cancelled
```

Terminal states:

```text
saved
rejected
failed
cancelled
```

Once a task reaches a terminal state, ignore later non-terminal status messages. `cancel` must compare-and-set to `cancelling` and is ignored after `saving` unless the platform can still safely abort before final rename.

## 7. Data Plane

Keep HTTP stream as the first stable data plane:

```text
Android -> Relay HTTP Gateway -> Agent HTTP Proxy -> FileSendService -> file stream
Android -> Direct Agent HTTP Server -> FileSendService -> file stream
```

Use a clear route:

```text
GET /api/file-send/{transferId}
```

Request authorization:

```text
Authorization: Bearer <download_token>
```

or an equivalent header such as:

```text
X-WebTerm-Transfer-Token: <download_token>
```

The token must not be logged in plaintext.

Relay requirements:

- Do not use the normal short API total timeout for file streams.
- Use no total timeout or a long idle timeout.
- Do not buffer the whole response body.
- Propagate Android cancellation to the upstream stream.
- Release task resources on disconnect, timeout, saved, failed, or cancelled.

Current go-core constraint:

The current `SessionRouter.RouteHTTP()` model returns `(int, []byte, error)`, and `relay.HTTPProxy` buffers request/response behavior around that synchronous shape. Before `FileSendService` can safely stream files, add a stream-capable routing interface.

Required foundation:

```text
SessionRouter.RouteHTTPv2(method, rawPath, body) -> HTTPResult
HTTPResult { StatusCode, Header, Body io.ReadCloser, Data []byte }
```

or a direct `http.Handler` registration path for file-send routes. Direct server and relay HTTP proxy must both use this streaming path.

P2P file transport is explicitly deferred. It can be added later as a data-plane optimization after the service ownership and status protocol are stable.

## 8. Android Receive and Notification Flow

`FileReceiveController` flow:

```text
offer received
  -> create receive task
  -> dedupe by transfer_id
  -> check notification/storage/SAF readiness
  -> notify accepted or rejected to Go
  -> start foreground receive if needed
  -> HTTP GET file stream
  -> write .part file
  -> report progress
  -> notify saving to Go
  -> flush/close
  -> verify bytes == expected file_size
  -> verify file_hash if provided
  -> rename to final file
  -> notify saved to Go
```

Failure flow:

```text
HTTP error / storage error / SAF error / user cancel / service stop
  -> close response body
  -> delete .part file
  -> notify failed or cancelled to Go
  -> update notification
```

Crash recovery:

- Each `.part` file gets a sidecar metadata file with `transfer_id`, final name, expected size/hash, and created time.
- On service start, scan stale `.part` metadata and delete expired leftovers.
- If recovery/resume is not implemented in the first version, stale partial transfers are failed and cleaned up, not resumed.

Save location:

```text
Downloads/WebTerm/
```

For user-selected directory:

```text
{pickedDir}/WebTerm/
```

Collision behavior:

```text
app-release.apk
app-release (1).apk
app-release (2).apk
```

Notifications:

```text
Connection:
  WebTerm connected to 3 devices

Transfer:
  Receiving app-release.apk from MacBook
  42% - 6.4 MB / 15.2 MB
  Action: Cancel

Success:
  Saved to WebTerm/app-release.apk

Failure:
  Receive failed: space not enough
```

Notification actions:

- Cancel current transfer.
- Disconnect a kept-alive connection when shown in the connection notification.

## 9. Refactor Phases

### Phase 1: Rename Prototype Semantics

Goal: remove old user-facing and internal `download` semantics.

Tasks:

- Replace CLI command with `webterm send <file>`.
- Remove `download` and `dl` command paths.
- Rename `DownloadTask` to `FileSendTask`.
- Rename `downloadId` to `transferId`.
- Rename Android receive helper/controller to receive terminology.
- Rename protocol messages to `file_send.*`.
- Rename route from `/api/fs/download` to `/api/file-send/{transferId}`.
- Remove old `MSG_DOWNLOAD_PROGRESS` naming.

Validation:

- No user-facing `download` text remains for this feature.
- Prototype file-send flow still transfers a file.

### Phase 2: Extract Go FileSendService

Goal: remove file task lifecycle from terminal/session code.

Tasks:

- First add stream-capable HTTP routing for direct and relay paths.
- Add CLI command dispatch in `hook/server.go` for command/response flows.
- Add `go-core/internal/filesend`.
- Move task map, state channel, expiry, and cleanup into `FileSendService`.
- Route local socket `file_send` command to `FileSendService`.
- Keep `TerminalSession` as context provider only.
- Add HTTP stream handler under the file-send service.

Validation:

- File task state is not owned by `TerminalSession`.
- Unit tests cover task create, lookup, expire, cancel, and saved/failed transitions.

### Phase 3: Add Android WebTermDeviceService Registry

Goal: centralize multi-device connection ownership.

Tasks:

- Add `WebTermDeviceService`.
- Reuse or wrap the existing `RelayMuxSessionRegistry`; do not create a duplicate registry with independent keying.
- Define the migration facade if renaming to `DeviceConnectionRegistry`.
- Add `DeviceConnection` terminology as a wrapper/evolution of `RelayMuxSessionManager`.
- Move existing relay mux ownership toward service-held connections.
- Expose bind APIs for UI to request manager and terminal channels.

Validation:

- Registry can hold 2-3 `DeviceConnection` instances.
- Same remote device does not create duplicate physical WS connections.
- Activity recreation does not recreate physical WS.

### Phase 4: Migrate Terminal Channels

Goal: make terminal a virtual channel on a service-owned connection.

Tasks:

- Change `TerminalRuntime` to request/open a terminal channel through the service.
- Make `TerminalConnection` wrap a virtual channel, not the physical transport.
- Ensure lastSeq, resize, input, state replay, and close semantics are preserved.
- Keep current relay/P2P fallback behavior inside the connection transport provider.

Validation:

- Multiple terminals to the same remote device share one physical connection.
- Closing one terminal channel does not close the device connection if other channels/tasks exist.
- UI detach/reattach preserves channel where appropriate.

### Phase 5: Move File Receive to Device Service

Goal: Android can receive offers even when not on the terminal page.

Tasks:

- Add service-owned `FileReceiveController`.
- Route `file_send.offer` from mux control messages to `FileReceiveController`.
- Send `accepted`, `progress`, `saved`, `failed`, `cancelled` through the same `DeviceConnection`.
- Remove terminal-hook dependency for file receive.
- Add `rejected`, `saving`, idempotent offer handling, and terminal-state guards.

Validation:

- Android home page can receive a file offer.
- Android terminal page is not required for receiving.
- CLI failure happens immediately after Android reports failed/cancelled.
- CLI success happens only after Android reports saved.

### Phase 6: Foreground Service and Notifications

Goal: make file receiving and optional background connection reliable.

Tasks:

- Integrate `NotificationController`.
- Promote `WebTermDeviceService` to foreground during file receive.
- Add progress, success, failure, cancel notifications.
- Implement `.part` write, byte verification, final rename.
- Add notification cancel action.

Validation:

- Background file receive continues with notification.
- Cancel action deletes `.part` and reports cancelled.
- Failed save deletes `.part` and reports failed.
- Saved notification path matches actual saved location.

### Phase 7: Relay File Stream Hardening

Goal: ensure relay mode supports slow and large file streams.

Tasks:

- Make file-send HTTP streams use no total timeout or a file-specific idle timeout.
- Ensure relay gateway and agent proxy stream response chunks without full buffering.
- Propagate downstream cancellation upstream.
- Ensure task cleanup after cancellation and disconnect.
- Decide first-version reconnect behavior: either disconnected transfers restart from byte 0, or add HTTP Range/resume. Do not leave it implicit.
- Define minimum idle timeout and heartbeat behavior for slow links.

Validation:

- Slow relay transfer longer than 30 seconds can complete.
- Android cancellation stops upstream streaming.
- No permanent transfer task remains after disconnect or timeout.

### Phase 8: Tests and Cleanup

Goal: finish with a clean architecture and no prototype residue.

Tests:

- CLI `webterm send` command parsing and status stream.
- `FileSendService` task lifecycle.
- Android `accepted/progress/saved/failed/cancelled` routing.
- HTTP EOF does not produce CLI success.
- Multi-device registry route selection.
- Same-device multi-terminal one-WS invariant.
- Foreground notification cancel.
- Relay long stream timeout behavior.
- `.part` cleanup on failure.
- Multiple simultaneous remote devices in the registry.
- Concurrent transfers from two devices.
- Network switch / reconnect during offer and during stream.
- Service restart with stale `.part` files.
- Empty file.
- File larger than 2 GiB, or explicit rejection when above configured max size.
- Direct mode file send.
- Repeated offer idempotency.
- Token authorization failure.

Test layers:

- Go unit tests: `FileSendService`, token auth, stream handler, task state machine.
- Go integration tests: direct route, relay HTTP proxy streaming, hook command response.
- Android JVM tests: connection registry/facade keying, receive state machine, notification command decisions.
- Android instrumentation tests: foreground service behavior, storage/SAF write, notification cancel.
- Manual/physical-device smoke: background transfer, network switch, large APK/zip transfer.

Observability:

- Log and classify transfer failure reasons.
- Track phase durations: offer to accepted, accepted to first byte, receiving duration, saving duration.
- Track transfer path: direct vs relay, WS vs P2P-backed mux control.
- Track bytes sent/received and final saved path.
- Track reconnect count and foreground-service duration.
- Never log transfer token or full source path unless debug mode explicitly allows it.

Risk mitigation:

- Gate Phase 3-4 connection ownership migration behind a feature flag.
- Keep the old `RelayMuxSessionRegistry` path available until new service ownership passes smoke tests.
- Roll back if same-device multi-terminal creates more than one physical WS, terminal reconnect breaks, or background service causes foreground-service exceptions.
- Run the terminal mux smoke suite before and after each ownership migration slice.

Cleanup:

- Delete or rewrite the old download design doc.
- Delete `DownloadTask` and `MSG_DOWNLOAD_PROGRESS` references.
- Remove terminal hook dependency for file receive.
- Update README and user docs to `webterm send`.
- Update command help text.
- Update protocol docs.
- Update Android notification text conventions.
- Update architecture docs that still describe terminal-owned physical connections.

## 10. Recommended Implementation Order

```text
1. Rename download prototype to send/receive.
2. Extract Go FileSendService.
3. Add WebTermDeviceService + DeviceConnectionRegistry skeleton.
4. Migrate terminal physical connection ownership into DeviceConnection.
5. Move file receive control into WebTermDeviceService.
6. Add foreground receive notifications and .part save protocol.
7. Harden relay file stream timeout/cancellation.
8. Add tests and remove prototype residue.
```

The highest-risk step is migrating Android connection ownership. Keep it separate from file-send protocol changes so regressions are easy to isolate.
