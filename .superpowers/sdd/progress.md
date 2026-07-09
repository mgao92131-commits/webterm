# Android 终端连接定向修复 — 进度账本

## Previous Plan (archived)

Previous ledger content moved to `progress-previous.md`. It covered an earlier multi-phase module-extraction refactor (Phases 1-7, Tasks 1.1-6a.8) which is complete and unrelated to this plan.

## Current Plan

Plan file: `docs/superpowers/plans/2026-07-09-android-terminal-connection-refactor-plan.md`
Branch: `android-terminal-connection-refactor`
Base commit: `0d72bed` (recorded before Task 1 implementer dispatch)

| Task | Status | Commits | Review |
|------|--------|---------|--------|
| 1: Transport close callback carries code | complete | 0d72bed..ec64ae2 | approved |
| 2: MuxSession parses ws-close/ws-error code/reason | complete | ec64ae2..a125dff | approved |
| 3: Classify errors and recover correctly | complete | a125dff..41285fe | approved |
| 4: Add channel reuse/reattach to RelayMuxSessionManager | complete | 41285fe..2dc18aa | approved |
| 5: Split TerminalConnection close into detach and closeSession | complete | 2dc18aa..c4a216f | approved with accepted scope carry-over |
| 6: Fix TerminalLifecycleController lifecycle matrix | complete | c4a216f..366828d | approved |
| 7: Propagate lastSeq correctly on reattach | complete | 366828d..c59bb2d (includes 942f13b compile fix) | approved |
| 8: Add send queue for reconnect resilience | skipped | - | deferred per brief (existing synchronous sends acceptable) |
| 9: Integration tests and final cleanup | complete | c59bb2d..f26c618 | approved, ready to merge |

- Task 5: `sendDownloadProgress` / `onDownloadHook` are pre-existing working-tree code retained because `AppFlowCoordinator` and `FileDownloadHelper` depend on them. Removing them would break app-module compilation. Accepted as scope carry-over for final whole-branch review.
- Task 7: Pre-existing reconnect/P2P-fallback working-tree code was split into `942f13b` so the Task 7 commit stays scoped to `lastSeq`.
- Task 9: Commit message only mentions the integration test but also includes the `relayChannelId` reattach bug fix. Minor; noted for final review.

- Final review Important fixes (commit `f26c618`):
  - `RelayMuxSessionManager.forceReconnect` now uses `reconnectTransport(reason, true)` so a new session starts when channels exist.
  - `TerminalConnection.reconnectNow()` only sets `pendingForceReconnect` when the current manager matches, avoiding a dirty flag.
  - Added covering tests in `TerminalConnectionReconnectTest`.

## Minor Findings Log

- Task 2: `MuxSessionControlTest` setup duplicated across four test methods (reviewer Minor; acceptable, can refactor in final cleanup).
- Task 4: `openTerminalChannel` calls `listener.onConnected` synchronously (reviewer Minor; matches brief, acceptable because public state-machine methods are called on main thread by contract).
- Task 9: Integration test `TerminalConnectionIntegrationTest.detachReattachPreservesLastSeq` exposed a real reattach bug: `relayChannelId` was assigned from the return value of `openTerminalChannel`, but `openTerminalChannel` fires `onConnected` synchronously for an existing channel *before* returning. The `onConnected` guard `if (!channelId.equals(relayChannelId)) return;` then dropped the callback, leaving the reattached connection in `CONNECTING` and suppressing the second HELLO. Fixed by pre-computing and assigning `relayChannelId = existingChannelId` before calling `openTerminalChannel`.
