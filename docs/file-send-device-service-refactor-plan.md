# WebTerm File Send and Android Device Service Refactor Plan

## 1. Goal

Turn the current PC-to-Android file transfer prototype into a long-term architecture:

- User command is `webterm send <file>`.
- Old `download` / `dl` naming is removed, not kept for compatibility.
- Android owns long-lived remote-device connections in one app-level service.
- One Android service can manage multiple remote device connections.
- Each remote device connection has at most one physical WS/mux connection.
- Each enabled device stays logically online until the user explicitly disconnects it, removes it, signs out, or stops the service.
- Terminal, manager, and file-send flows reuse the same `DeviceConnection`.
- File transfer succeeds only after Android has saved the file and reports `saved`.
- File receiving uses foreground-service notifications when it needs background reliability.
- Claude Code, Codex, and Kimi Code Hook events reach Android through the same device connection and are rendered by the service-owned notification layer.

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
- Agent Hook notifications are currently tied to `TerminalSession` delivery, so they cannot reliably alert Android when the terminal page is closed or distinguish foreground-service status from an Agent alert.

## 3. Target Android Architecture

Use one Android service as the owner for long-lived device-connection policy, foreground lifetime, and notifications:

```text
WebTermDeviceService
  - RelayMuxSessionRegistry / DeviceConnectionRegistry facade
  - FileReceiveController
  - AgentNotificationController
  - NotificationController
  - TerminalChannelRegistry
```

Important existing-code constraint:

`android-client/core-session/src/main/java/com/webterm/core/session/RelayMuxSessionRegistry.java` already does the core registry job:

- `forDevice(baseUrl, cookie, deviceId)`
- keying by normalized base URL + device ID
- excluding rotating cookie from identity
- `releaseIfIdle(...)` as a lower-level cleanup capability
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
  - agent-notification control channel
  - reconnect and backoff state
```

Connection key during the first migration:

```text
canonicalUrl + "\n" + remoteDeviceId
```

The current registry key already uses `normalizeBaseUrl(baseUrl) + "\n" + deviceId`. Preserve this behavior during the migration. Do not add rotating cookie or token-derived values to the key.

Future key, only after a stable account identity is available:

```text
canonicalUrl + "\n" + stableAccountId + "\n" + remoteDeviceId
```

Account identity source:

- Relay mode: stable user id from relay auth state when available.
- Direct mode: fixed local placeholder such as `direct`.
- Device ID: remote relay device id; direct mode uses an empty or local placeholder device id.

If a compact key is needed for maps/logging, use `sha256(canonicalUrl + "\n" + remoteDeviceId)` during migration. Keep the structured fields on the connection object for diagnostics.

Rules:

- Activity and Fragment bind to `WebTermDeviceService`; they do not own WS connections.
- `TerminalRuntime` owns UI state and a terminal virtual channel, not the physical connection.
- `FileReceiveController` owns all Android receive tasks and routes them by `connectionKey` and `transferId`.
- `AgentNotificationController` receives Agent events by `connectionKey` and `sessionId`, deduplicates them, and requests user-visible alerts from `NotificationController`.
- `NotificationController` is the single owner of connection, transfer, and Agent event notifications.
- The Hilt `@Singleton` registry remains the canonical connection store during migration; the Service is the foreground/lifecycle host and policy coordinator, not a competing owner.
- The Service persists each device's desired online state and holds the corresponding device-online lease; UI visibility must never decide whether the physical connection exists.

## 4. Persistent Device Connection Policy

The product model is "paired devices stay online", not "a page temporarily opens a connection". The normal scale is two or three remote devices, so connection continuity takes priority over idle connection reclamation.

Every enabled device gets a persistent device-online lease when `WebTermDeviceService` starts. The service opens its single physical connection and keeps reconnecting it after ordinary transport failures. Switching screens or devices only changes which device the UI is observing; it must never release the previous device connection.

```text
enabled device A  -> persistent DEVICE_ONLINE lease -> one WS/mux connection
enabled device B  -> persistent DEVICE_ONLINE lease -> one WS/mux connection
enabled device C  -> persistent DEVICE_ONLINE lease -> one WS/mux connection

terminal / manager / file-send -> virtual channels and tasks on the matching connection
```

Use explicit device state and leases instead of deriving lifetime from the visible page:

```text
DeviceOnlineState: ONLINE | DISCONNECTED | REMOVED

ConnectionLease(type=DEVICE_ONLINE, owner=deviceId, persistent=true)
ConnectionLease(type=TRANSFER, owner=transferId)
ConnectionLease(type=TERMINAL, owner=sessionId)
ConnectionLease(type=VISIBLE, owner=screenId)
```

Rules:

- `ONLINE` is the default state for an enabled, paired device. It acquires `DEVICE_ONLINE` and reconnects as needed.
- Changing the visible device releases only `VISIBLE`; it does not change `DEVICE_ONLINE`, terminal, or transfer leases.
- Terminal and transfer leases describe active work and prevent an explicit disconnect from silently tearing down that work; they are not the normal reason a connection stays alive.
- `DISCONNECTED` is an explicit user action. It releases the device-online lease, closes the physical connection after active work is cancelled or completed, and suppresses automatic reconnect.
- Removing a device, signing out, or stopping the device service releases its connection and clears its reconnect work.
- A device that is merely unreachable remains logically `ONLINE` and enters bounded exponential-backoff reconnect. Network availability changes may trigger an immediate retry; retries must use jitter and must not spin while offline.
- `RelayMuxSessionRegistry.releaseIfIdle()` remains a lower-level cleanup API, but the service must not call it for an `ONLINE` device. It is only eligible after the device-online lease has been released.

Foreground service is part of this promise. While at least one device is `ONLINE`, `WebTermDeviceService` runs in foreground mode with one persistent connection notification, for example `WebTerm connected to 3 devices`. It calls `startForeground(...)` immediately when the service is started for persistent connectivity. A transfer temporarily promotes that notification to transfer progress and adds cancel handling.

When the last device leaves `ONLINE` and no transfer needs completion, the service closes remaining connections and calls `stopForeground(false)`. Android or the user may still stop the service; persist each device's desired `ONLINE` state and restore its connections on the next permitted service start. Do not claim an OS-proof connection that Android cannot guarantee.

The existing `idle timeout` remains only for file-stream inactivity and transfer cleanup. It is not a default device-connection timeout. A later optional battery-saver mode may opt a device out of persistent online state, but it is not part of the first-version default behavior.

## 5. Target Go Architecture

Move file-send lifecycle out of `TerminalSession` and `session.Manager`.

Add:

```text
go-core/internal/filesend/
  service.go
  task.go
  protocol.go
  http_handler.go

go-core/internal/agentnotify/
  dispatcher.go
  protocol.go
```

Responsibilities:

- `FileSendService`
  - Parse `webterm send <file>` commands from the local socket command protocol.
  - Resolve path from CLI cwd.
  - Validate file exists, is regular, and is readable.
  - Create and track `FileSendTask`.
  - Target the connected Android device (single-device fallback).
  - Send `file_send.offer`.
  - Stream status back to CLI.
  - Handle `accepted`, `progress`, `saved`, `failed`, `cancelled`.
  - Enforce timeout and cleanup.

- `FileSendTask`
  - `transferId`
  - source file path/name/size
  - optional cwd metadata
  - target connection/device id
  - status
  - status channel
  - expiry/cancel function

- HTTP handler
  - Serve `GET /api/file-send/{transferId}` or equivalent.
  - Open source file only for authorized, active task.
  - Stream bytes without buffering the whole file.
  - Do not mark task successful on EOF.

- `AgentNotificationDispatcher`
  - Accept an already resolved local `HookEvent` from `hook.Server`.
  - Preserve the existing terminal-session status update, but construct a separate `agent_notification` control message for Android delivery.
  - Assign a unique `event_id`, bind it to the resolved session and device connection, and send it through mux control.
  - Retain a bounded in-memory latest event per device/session for reconnect replay; discard it after `agent_notification.ack`, expiry, or replacement.
  - Never include raw Hook payloads, secrets, or unbounded prompt text in persistent logs.

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

The current Go mux implementation already has a text control-message receive path with `OnControl`, and terminal virtual channels are opened by `ws-connect`. The plan should use mux text control messages for device-level file-send control traffic, not terminal binary messages.

Current gap:

- Go can receive unknown mux text control messages through `OnControl`.
- Android currently ignores unknown mux text control messages.
- Go does not yet expose an explicit server-to-client `SendControl` path for file-send offers.

The control plane must become explicitly bidirectional before `file_send.offer` can move off terminal hooks.

Required server work:

- Wire `application.MuxServeOpts.OnControl` through direct and relay agent paths.
- Route `file_send.*` control messages to `FileSendService`.
- Add a server-side mux control sender for `file_send.offer` and other device-level messages.
- Keep terminal binary protocol focused on terminal traffic.

Required Android work:

- Add `MuxSession.Listener.onControlMessage(JSONObject msg)` for unknown text control messages.
- Forward control messages through `RelayMuxSessionManager`.
- Route `file_send.*` control messages to service-owned `FileReceiveController`.
- Add an API for Android to send `file_send.accepted/progress/saving/saved/failed/rejected/cancelled` as mux text control messages.

This avoids adding another terminal-scoped `MSG_FILE_SEND` frame and keeps file send device-level.

### Agent Hook Notification Transport

The existing PC-side interface remains unchanged:

```text
Claude Code / Codex / Kimi Code hook
  -> webterm-notify-helper
  -> webterm notify
  -> local HookEvent
```

`HookEvent` remains the local shell-to-Agent API and may still update terminal session metadata for in-app rendering. It must not be the Android notification delivery mechanism. After resolving its session, the Agent converts a notify event into a device-level mux control message on the matching `DeviceConnection`:

```json
{
  "type": "agent_notification",
  "event_id": "an_...",
  "session_id": "s1",
  "source": "claude",
  "level": "idle",
  "message": "Task completed",
  "created_at": 1760000000
}
```

Android acknowledges durable receipt with:

```json
{ "type": "agent_notification.ack", "event_id": "an_..." }
```

The mux connection itself identifies the remote device. `session_id` identifies the terminal to open after the user taps the notification. `event_id` must be generated by the Agent with at least 128 bits of entropy and remain unchanged across reconnect resends so Android can deduplicate it.

Routing rules:

- Go resolves HookEvent PID/session exactly as today, then sends `agent_notification` through the session's device connection without opening a terminal virtual channel.
- Android forwards `agent_notification` from `MuxSession` through `RelayMuxSessionManager` to `WebTermDeviceService`.
- `AgentNotificationController` persists a bounded, expiring recent-ID set by `connectionKey + event_id`, then acknowledges receipt and asks `NotificationController` to render the event. A duplicate is acknowledged again but does not create another alert.
- A closed terminal page must not prevent delivery. Tapping the alert routes to the matching device and `session_id`; the UI may open/reconnect its terminal channel only after the tap.
- Until `agent_notification.ack` arrives, the Agent keeps a bounded in-memory pending event per device/session for reconnect delivery. It must not replay an unbounded event history or persist raw Hook content in logs.

Notification policy:

- The persistent foreground notification is reserved for connection state and transfer progress. Agent events never replace or rapidly update it.
- `running` updates app state and may update a collapsed per-session notification, but is silent by default to avoid Hook noise.
- `idle` creates or replaces a normal-priority completion notification for that device/session.
- `error` creates a high-priority failure notification.
- Use a separate Android notification channel for Agent alerts so the user can control sound and importance independently from the foreground connection and transfer channels.
- Group Agent alerts by remote device, replace older unread status for the same `connectionKey + sessionId`, and include an `Open terminal` action.

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
  "file_name": "app-release.apk",
  "file_size": 15234567,
  "file_hash_sha256": "hex...",
  "transfer_token": "..."
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
- The offer also includes a CSPRNG one-time `transfer_token`.
- Android must present `transfer_token` when calling the file stream endpoint.
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
Authorization: Bearer <transfer_token>
```

or an equivalent header such as:

```text
X-WebTerm-Transfer-Token: <transfer_token>
```

The token must not be logged in plaintext.

Route updates required for `/api/file-send/`:

- Direct server must route `/api/file-send/` to the stream-capable handler.
- Relay agent HTTP proxy must treat `/api/file-send/` as a streaming route.
- Relay gateway must apply file-stream timeout behavior to `/api/file-send/`.
- Existing `/api/fs/download` prototype routes must be removed during the send rename.

Relay requirements:

- Do not use the normal short API total timeout for file streams.
- Use no total timeout or a long idle timeout.
- Do not buffer the whole response body.
- Propagate Android cancellation to the upstream stream.
- Release task resources on disconnect, timeout, saved, failed, or cancelled.
- Ensure the response wait loop uses the per-stream timeout, not the gateway default timeout.

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
  -> verify file_hash_sha256
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

Agent alert (separate notification, not ongoing):
  Claude Code finished on MacBook
  Task completed
  Action: Open terminal
```

Notification actions:

- Cancel current transfer.
- Disconnect an online device from the connection notification.
- Stop all online devices from the connection notification when no transfer is active.
- Open the matching device and terminal session from an Agent alert.

## 9. Refactor Phases

### Phase 0: Control And Stream Foundation

Goal: create the minimum infrastructure needed before changing product behavior.

Tasks:

- Add bidirectional mux text control support:
  - Go can send device-level control messages to Android.
  - Android can receive unknown mux text control messages and forward them to the service layer.
  - Android can send `file_send.*` status control messages back to Go.
  - Android can acknowledge `agent_notification` after durable deduplication state is recorded.
  - Go can send both `file_send.*` and `agent_notification` without opening a terminal channel.
- Add stream-capable HTTP routing for direct and relay paths.
- Route `/api/file-send/` through the streaming path in direct server, relay agent proxy, and relay gateway.
- Fix relay file-stream timeout handling so the response wait loop uses file-stream timeout semantics.
- Add local CLI command dispatch in `hook/server.go` for command/response flows, even if the command initially returns "not implemented".

Validation:

- A test mux control message can round-trip Android -> Go and Go -> Android without opening a terminal channel.
- A test `agent_notification` reaches the Android service layer without opening a terminal channel.
- A test stream route can return a streaming body through direct and relay paths.
- Existing terminal mux tests still pass.

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
- Rename `download_token` concepts to `transfer_token`.

Validation:

- No user-facing `download` text remains for this feature.
- Prototype file-send flow still transfers a file.

### Phase 2: Extract Go FileSendService Skeleton

Goal: remove file task lifecycle from terminal/session code without depending on Android service migration.

Tasks:

- Add `go-core/internal/filesend`.
- Move task map, state channel, expiry, and cleanup into `FileSendService`.
- Route local socket `file_send` command to `FileSendService`.
- Keep `TerminalSession` as context provider only.
- Add HTTP stream handler under the file-send service.
- Keep a temporary compatibility adapter only if needed to preserve the existing prototype during migration; remove it before final cleanup.

Validation:

- File task state is not owned by `TerminalSession`.
- Unit tests cover task create, lookup, expire, cancel, and saved/failed transitions.
- CLI can create a task and receive a controlled "waiting for Android service path" response before the Android service path is connected.

### Phase 3: Add Android WebTermDeviceService Registry

Goal: centralize multi-device connection ownership and make enabled devices persistently online.

Tasks:

- Add `WebTermDeviceService`.
- Reuse or wrap the existing `RelayMuxSessionRegistry`; do not create a duplicate registry with independent keying.
- Define the migration facade if renaming to `DeviceConnectionRegistry`.
- Add `DeviceConnection` terminology as a wrapper/evolution of `RelayMuxSessionManager`.
- Move existing relay mux ownership toward service-held connections.
- Persist `DeviceOnlineState` for each paired device; default enabled devices to `ONLINE`.
- Acquire one persistent device-online lease per `ONLINE` device and suppress `releaseIfIdle()` while that lease exists.
- Add bounded exponential-backoff reconnect with jitter and network-available retry.
- Expose explicit connect, disconnect, remove-device, sign-out, and stop-service teardown paths.
- Expose bind APIs for UI to request manager and terminal channels.
- Expose service routing for device-level `agent_notification` messages; do not couple notification delivery to `TerminalRuntime`.

Validation:

- Registry can hold 2-3 `DeviceConnection` instances.
- Same remote device does not create duplicate physical WS connections.
- Activity recreation does not recreate physical WS.
- Switching from one device to another leaves both enabled device connections intact.
- An explicitly disconnected device stays disconnected across Activity recreation and does not reconnect until requested.
- An Agent event for a closed terminal session still reaches the service layer with its device and session identity.

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

### Phase 5: Complete File Send/Receive Control Loop

Goal: Android can receive offers even when not on the terminal page.

Tasks:

- Add service-owned `FileReceiveController`.
- Route `file_send.offer` from mux control messages to `FileReceiveController`.
- Send `accepted`, `progress`, `saved`, `failed`, `cancelled` through the same `DeviceConnection`.
- Remove terminal-hook dependency for file receive.
- Add `rejected`, `saving`, idempotent offer handling, and terminal-state guards.
- Require `file_hash_sha256` in `file_send.offer` and verify it before `saved`.

Validation:

- Android home page can receive a file offer.
- Android terminal page is not required for receiving.
- CLI failure happens immediately after Android reports failed/cancelled.
- CLI success happens only after Android reports saved.
- Duplicate offers with the same `transfer_id` do not start duplicate HTTP GETs.

### Phase 6: Migrate Agent Hook Notifications

Goal: preserve existing Agent Hook installation while moving Android alert delivery into the persistent device service.

Tasks:

- Keep `webterm notify`, `webterm-notify-helper`, and the Claude Code, Codex, and Kimi Code Hook configurations as the PC-side integration API.
- Add a Go HookEvent-to-`agent_notification` adapter after normal PID/session resolution.
- Send Agent events as device-level mux control messages, not terminal binary `MSG_HOOK` messages.
- Add service-owned `AgentNotificationController` with persisted `connectionKey + event_id` deduplication and `agent_notification.ack`.
- Add Android `agent_alerts` notification channel and render `running`, `idle`, and `error` according to the notification policy.
- Add an `Open terminal` action that navigates to the event's matching device and session.
- Keep terminal UI status updates compatible during migration, but make foreground alerts independent from an open terminal page.

Validation:

- Claude Code, Codex, and Kimi Code Hook events reach Android while their terminal pages are closed.
- `running` does not create noisy heads-up alerts; `idle` and `error` render with their intended priority.
- A reconnect resend with the same `event_id` produces one Android alert.
- The Agent removes pending replay state after `agent_notification.ack`.
- Tapping an Agent alert opens the correct device and terminal session.
- The foreground connection notification remains stable while Agent alerts arrive.

### Phase 7: Foreground Service and Notifications

Goal: make persistent multi-device connectivity and file receiving reliable in the background.

Tasks:

- Integrate `NotificationController`.
- Start `WebTermDeviceService` in foreground immediately whenever one or more devices are `ONLINE`.
- Show one persistent notification with connected/reconnecting device count and explicit disconnect/stop actions.
- Promote that notification to transfer progress during file receive.
- Add progress, success, failure, cancel notifications.
- Implement `.part` write, byte verification, final rename.
- Add notification cancel action.
- Persist desired online states and restore them on the next permitted service start after process/service recreation.

Validation:

- Two or three enabled devices remain connected while the app is backgrounded, with one persistent notification.
- Switching device pages does not alter connection count.
- Background file receive continues with notification.
- Cancel action deletes `.part` and reports cancelled.
- Failed save deletes `.part` and reports failed.
- Saved notification path matches actual saved location.

### Phase 8: Relay File Stream Hardening

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

### Phase 9: Tests and Cleanup

Goal: finish with a clean architecture and no prototype residue.

Tests:

- CLI `webterm send` command parsing and status stream.
- `FileSendService` task lifecycle.
- Android `accepted/progress/saved/failed/cancelled` routing.
- HTTP EOF does not produce CLI success.
- Multi-device registry route selection.
- Same-device multi-terminal one-WS invariant.
- Foreground notification cancel.
- Claude Code, Codex, and Kimi Code Hook event routing without an open terminal page.
- `agent_notification` deduplication across reconnect replay.
- Agent `running`, `idle`, and `error` notification priority and channel separation.
- Agent notification `Open terminal` action routes to the original device and session.
- Agent alerts do not replace the persistent foreground connection notification.
- Persistent online-device notification and explicit disconnect behavior.
- Device switch does not close an already-online device connection.
- Reconnect backoff does not busy-loop while the network is unavailable.
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
- Bidirectional mux control without a terminal channel.
- `/api/file-send/` prefix coverage in direct server, relay agent proxy, and relay gateway.
- SHA-256 mismatch failure.

Test layers:

- Go unit tests: `FileSendService`, `AgentNotificationDispatcher`, token auth, stream handler, task state machine.
- Go integration tests: direct route, relay HTTP proxy streaming, HookEvent-to-`agent_notification` routing, hook command response.
- Android JVM tests: connection registry/facade keying, receive state machine, Agent event deduplication, notification command decisions.
- Android instrumentation tests: foreground service behavior, storage/SAF write, notification cancel, Agent alert tap routing.
- Manual/physical-device smoke: background transfer, network switch, large APK/zip transfer, and Hook alerts from Claude Code, Codex, and Kimi Code.

Observability:

- Log and classify transfer failure reasons.
- Track phase durations: offer to accepted, accepted to first byte, receiving duration, saving duration.
- Track transfer path: direct vs relay, WS vs P2P-backed mux control.
- Track bytes sent/received and final saved path.
- Track reconnect count and foreground-service duration.
- Track intended-online, connected, reconnecting, explicitly disconnected, and removed device counts.
- Track Agent event counts by source and level, deduplication drops, replay attempts, and notification tap actions; never log full Agent message content by default.
- Never log transfer token or full source path unless debug mode explicitly allows it.

Risk mitigation:

- Gate Phase 3-4 connection ownership migration behind a feature flag.
- Keep the old `RelayMuxSessionRegistry` path available until new service ownership passes smoke tests.
- Roll back if same-device multi-terminal creates more than one physical WS, device switching releases an online connection, terminal reconnect breaks, or background service causes foreground-service exceptions.
- Run the terminal mux smoke suite before and after each ownership migration slice.

Cleanup:

- Delete or rewrite the old download design doc.
- Delete `DownloadTask` and `MSG_DOWNLOAD_PROGRESS` references.
- Remove terminal hook dependency for file receive.
- Update Hook integration docs to state that `webterm notify` remains the local Agent API while Android alerts are device-service-owned.
- Update README and user docs to `webterm send`.
- Update command help text.
- Update protocol docs.
- Update Android notification text conventions.
- Update architecture docs that still describe terminal-owned physical connections.

## 10. Recommended Implementation Order

```text
0. Add bidirectional mux control and stream-capable `/api/file-send/` foundation.
1. Rename download prototype to send/receive.
2. Extract Go FileSendService skeleton.
3. Add WebTermDeviceService + RelayMuxSessionRegistry facade skeleton.
4. Migrate terminal physical connection ownership into DeviceConnection terminology.
5. Complete file send/receive control loop in WebTermDeviceService.
6. Migrate Agent Hook notifications into service-owned `agent_notification` routing.
7. Add foreground connection, transfer, and Agent notification channels plus the .part save protocol.
8. Harden relay file stream timeout/cancellation.
9. Add tests and remove prototype residue.
```

The highest-risk step is migrating Android connection ownership. Keep it separate from file-send protocol changes so regressions are easy to isolate.
