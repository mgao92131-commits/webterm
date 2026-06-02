# Web Terminal Frontend Refactor And Test Plan

This document describes the planned frontend refactor for WebTerm's terminal
display, rendering, layout, and event handling. It also defines the automated
test strategy that should protect the refactor.

The focus is the browser side of the terminal experience:

- xterm.js creation, rendering, and write flow
- WebSocket event handling
- resize, visual viewport, and mobile keyboard behavior
- keyboard, quickbar, modifier, and IME input
- selection and clipboard handling
- lifecycle cleanup when entering and leaving terminal views

## Goals

- Keep the existing user-visible behavior stable while making the frontend
  easier to reason about.
- Make terminal rendering resilient under large output and reconnect replay.
- Centralize resize and viewport handling so terminal dimensions do not jump or
  collapse on desktop or mobile.
- Centralize input event handling so keyboard, quickbar, modifier, IME, and
  selection mode do not fight each other.
- Add automated tests before and during the refactor so regressions are caught
  quickly.

## Current Observations

- `web/app.js` currently owns terminal creation, WebSocket handling, rendering,
  layout, input, selection, title editing, and mobile behavior in one large
  script.
- Terminal output is written directly from WebSocket message handlers into
  `term.write()`.
- Resize behavior is triggered from several places: initial fit, `window.resize`,
  `visualViewport.resize`, `fonts.ready`, visibility changes, and delayed
  `setTimeout` calls.
- Mobile keyboard and scroll anchor behavior is spread across multiple helpers.
- Selection mode calculates terminal cells manually and depends on xterm private
  internals such as `_core._renderService`.
- Event listeners and xterm disposables are not gathered into one lifecycle
  object, so repeated terminal view creation is risky if navigation becomes more
  SPA-like.

## Refactor Plan

## Implementation Status

Current completed work:

- Added `DisposableStore` and started routing terminal page listeners and timers
  through one disposable lifecycle.
- Added `TerminalView` around xterm.js and `FitAddon`.
- Added `TerminalWriteQueue` and routed `state`, `replay`, and `output` writes
  through a batched write path.
- Converted `web/app.js` to an ES module so frontend code can be split into
  testable modules.
- Added `TerminalInputController` for quickbar and modifier input handling.
- Added `TerminalLayoutController` for fit, resize, visual viewport metrics,
  keyboard-open state, and scroll-anchor restoration.
- Added `TerminalSelectionController` for selection mode, copy handling, pointer
  selection, viewport preservation, and input suspension.
- Added debug hooks for terminal state, WebSocket state, layout state,
  lifecycle state, selection targeting, and write queue state.
- Added unit tests for disposable lifecycle, input mapping, input controller
  behavior, layout calculations, selection range/cell mapping, and write queue
  behavior.
- Added Playwright E2E smoke coverage for login, terminal creation, xterm
  rendering, WebSocket readiness, and real command output.
- Added Playwright E2E resize coverage for desktop and mobile viewport sizes.
- Added Playwright E2E coverage for large output and reload/reconnect recovery.
- Added Playwright E2E coverage for quickbar `Ctrl C` interruption, selection
  copy, and repeated terminal re-entry lifecycle cleanup.
- Added unit coverage for keyboard-open layout behavior.
- Added unit coverage for empty selection copy feedback.
- Added comments around high-risk xterm CSS overrides.

Remaining optional hardening:

- Add browser-matrix runs for WebKit and Firefox before release.
- Revisit xterm DOM overrides when upgrading xterm.js and replace any that gain
  stable option/API support.

### Phase 1: Terminal Lifecycle

Goal: make all terminal-related resources have an explicit creation and cleanup
path.

Tasks:

- Add a `DisposableStore` utility.
- Track DOM event listeners, xterm disposables, timers, and WebSocket listeners
  through the store.
- Add a `TerminalView` wrapper around xterm.js and `FitAddon`.
- Move terminal creation, addon loading, `open()`, `dispose()`, `fit()`,
  `focus()`, `reset()`, and basic write calls into `TerminalView`.
- Replace direct `state.term` and `state.fit` usage gradually with
  `state.terminalView`.
- Ensure `renderManager()` disposes the active terminal view and terminal page
  resources.

Acceptance criteria:

- Repeatedly navigating between `/` and `/terminal/{id}` does not duplicate
  resize, input, or WebSocket handlers.
- A single key press sends one input message.
- Only one active terminal WebSocket exists for the current terminal page.

Suggested commit:

```text
frontend: add disposable lifecycle for terminal page
frontend: wrap xterm in TerminalView
```

### Phase 2: Rendering Write Pipeline

Goal: decouple WebSocket message frequency from xterm rendering frequency.

Tasks:

- Add a write queue inside `TerminalView`.
- Replace direct `term.write(data)` calls with `enqueueWrite(data)`.
- Flush queued writes with `requestAnimationFrame` or a short timer.
- Route `state`, `replay`, and `output` messages through the same write path.
- Track restore state:
  - `restoring`: serialized state or replay frames are being applied.
  - `ready`: normal incremental output can be applied.
- Add debug metrics for queue length, last flush byte count, and flush count.

Acceptance criteria:

- Large output does not freeze the page.
- Replay with many frames does not synchronously block the browser for a long
  time.
- The final visible output is correct after `state`, `replay`, and incremental
  `output` messages.

Suggested commit:

```text
frontend: batch terminal writes
```

### Phase 3: Layout And Resize

Goal: centralize terminal dimensions, fit behavior, viewport height, soft
keyboard handling, and scroll anchor restoration.

Tasks:

- Add a `TerminalLayoutController`.
- Use `ResizeObserver` on `#terminal-container` to drive `fit()`.
- Send resize messages only after `fit()` has produced valid `cols` and `rows`.
- Use `visualViewport` only to update viewport height and keyboard state.
- Reduce scattered `window.resize`, `fonts.ready`, and delayed `sendResize`
  logic.
- Centralize scroll anchor capture and restore:
  - if the user is at the bottom, stay at the bottom;
  - if the user is viewing history, preserve the approximate center line;
  - if the mobile keyboard opens while the terminal input is focused, scroll to
    bottom.

Acceptance criteria:

- Desktop window resize preserves a sensible scroll position.
- Mobile viewport changes and keyboard open/close do not collapse the terminal.
- `term.cols` and `term.rows` remain positive after resize.
- The page does not gain horizontal scroll.

Suggested commit:

```text
frontend: centralize terminal layout and resize
```

### Phase 4: Input Handling

Goal: make all terminal input pass through one controller.

Tasks:

- Add a `TerminalInputController`.
- Provide one input path: `sendInput(data, source)`.
- Sources should include `xterm`, `quickbar`, `modifier`, and `debug`.
- Move pending modifier state into the input controller.
- Move `modifiedInput`, `quickbarInput`, keydown, and beforeinput handling into
  the input controller.
- Keep selection mode input suspension coordinated through the input controller.
- Ensure IME composition is not interrupted by modifier handling.

Acceptance criteria:

- Quickbar keys send the expected control sequences.
- `Ctrl+C`, `Tab`, `Shift+Tab`, arrows, and ordinary input work after repeated
  focus changes.
- Chinese or other IME input still works.
- Leaving selection mode restores normal terminal input.

Suggested commit:

```text
frontend: centralize terminal input handling
```

### Phase 5: Selection And Clipboard

Goal: isolate selection mode and reduce private xterm API risk.

Tasks:

- Add a `TerminalSelectionController`.
- Move selection mode state, copy button behavior, pointer selection, and
  viewport preservation into the controller.
- Coordinate input suspension with `TerminalInputController`.
- Encapsulate cell metric lookup in `getCellMetrics()`.
- Prefer stable xterm APIs where possible.
- If private xterm internals remain necessary, keep them in one small function
  with a fallback.
- Add copy success and failure feedback.

Acceptance criteria:

- Desktop and mobile selection select the expected text.
- Copy writes the expected text to the clipboard.
- Empty selection behavior is clear and does not silently look successful.
- Any xterm private API usage is isolated.

Suggested commit:

```text
frontend: isolate selection mode
```

### Phase 6: CSS And xterm DOM Overrides

Goal: make xterm CSS overrides intentional and upgrade-friendly.

Tasks:

- Review `.xterm-*` rules in `web/style.css`.
- Keep only rules with a clear purpose.
- Add short comments for non-obvious overrides.
- Reduce high-risk overrides of `.xterm-helper-textarea`, `.xterm-screen`, and
  `.xterm-viewport` where possible.
- Prefer xterm options over DOM overrides when available.

Acceptance criteria:

- Long lines, full-screen programs, Chinese input, and mobile keyboard behavior
  still render correctly.
- xterm DOM overrides are small and documented.

Suggested commit:

```text
frontend: document and trim xterm css overrides
```

## Automated Test Plan

The test strategy has three layers:

- unit tests for pure logic and controllers;
- browser integration tests for xterm rendering and WebSocket-driven behavior;
- end-to-end tests against a real server and real PTY.

## Layer 1: Unit Tests

These tests should run with `npm test` and avoid a real browser where possible.

Logic to extract and test:

- `DisposableStore`
- `TerminalWriteQueue`
- `modifiedInput(modifier, data)`
- `quickbarInput(modifier, key)`
- `sessionDisplayTitle(session)`
- selection range calculations
- scroll anchor calculations
- input controller state transitions

Core cases:

```text
Ctrl + c -> \x03
Ctrl + a -> \x01
Ctrl + 8 -> \x7f
Shift Tab -> \x1b[Z
Arrow keys -> CSI sequences
Modifier state is consumed exactly once
Reverse selection drag produces the correct range
Disposing a DisposableStore calls each disposable once
```

Acceptance criteria:

- Pure input and selection behavior is covered before moving code out of
  `web/app.js`.
- Controller state transitions can be tested without a live WebSocket.

## Layer 2: Browser Integration Tests

Use Playwright for browser-level tests.

Suggested scripts:

```json
{
  "scripts": {
    "test:web": "playwright test"
  },
  "devDependencies": {
    "@playwright/test": "^latest"
  }
}
```

Recommended tests:

### Terminal State Restore

- Open `/terminal/s1` in a controlled test setup.
- Send a WebSocket message:

```json
{ "type": "state", "seq": 10, "data": "hello\r\nworld" }
```

- Assert that the terminal displays `hello` and `world`.
- Assert that `sessionStorage` stores `lastSeq = 10`.

### Replay Restore

- Apply an initial state.
- Simulate reconnect.
- Send replay frames.
- Assert that new output is appended and previous output is not duplicated.

### Incremental Output Rendering

- Send many `output` messages.
- Assert that the last expected line appears.
- Assert that there are no page errors.

### Info Updates

- Send an `info` message with title and session name.
- Assert that `document.title`, title input value, and placeholder are updated.

### Exit Behavior

- Send an `exit` message.
- Assert that automatic reconnect stops.
- Assert that further input is not sent on a closed session.

### Rendering Under Load

- Send thousands of output lines.
- Assert that the terminal remains visible.
- Assert that canvas dimensions are non-zero.
- Assert that the page remains responsive to click and input.

## Layer 3: End-To-End Tests With Real PTY

Run these against the real server and shell.

Suggested environment:

```sh
WEBTERM_PASSWORD=test
WEBTERM_ADDR=127.0.0.1:18080
```

Suggested scripts:

```json
{
  "scripts": {
    "test:e2e": "playwright test tests/e2e"
  }
}
```

Recommended tests:

### Login And Create Terminal

- Open `/`.
- Log in.
- Click `新建终端`.
- Assert that the browser navigates to `/terminal/{id}`.
- Assert that xterm is visible.
- Assert that terminal `cols` and `rows` are positive.

### Basic Input And Output

- Type:

```sh
printf 'WEBTERM_OK\n'
```

- Assert that `WEBTERM_OK` appears in the terminal.

### Large Output

- Type:

```sh
seq 1 5000
```

- Assert that `5000` appears.
- Assert that the page remains responsive.
- Assert that the terminal does not become blank.

### Resize

- Test at desktop size, such as `1280x800`.
- Test at mobile size, such as `390x844`.
- After each resize, assert:
  - terminal canvas width and height are non-zero;
  - `cols > 0`;
  - `rows > 0`;
  - there is no horizontal page scroll.

### Reconnect Recovery

- Type:

```sh
echo before
```

- Reload the page or simulate WebSocket disconnect.
- Reopen the same session.
- Assert that `before` is still visible.
- Type:

```sh
echo after
```

- Assert that `after` appears.

### Quickbar Input

- Start a long-running command:

```sh
sleep 10
```

- Tap `Ctrl C`.
- Assert that the prompt returns before 10 seconds.
- For `Tab`, `Shift Tab`, and arrows, prefer asserting the sent input sequence in
  an integration test; real shell behavior can vary.

### Selection And Clipboard

- Output a fixed marker, such as `COPY_ME`.
- Enable selection mode.
- Drag over the marker.
- Click copy.
- Assert that the clipboard includes `COPY_ME`.
- Exit selection mode and verify normal input still works.

### Mobile Viewport

- Run with mobile emulation.
- Focus the terminal.
- Trigger viewport resize.
- Assert that `.terminal-page` follows `--viewport-height`.
- Assert that the quickbar remains visible.
- Assert that `#terminal-container` height stays non-zero.

## Event Cleanup Tests

These tests are especially important for the refactor.

Add debug helpers in test/debug mode:

```js
window.__webtermDebug.listeners()
window.__webtermDebug.wsState()
window.__webtermDebug.termState()
window.__webtermDebug.writeQueue()
```

Recommended flow:

- Open a terminal page.
- Return to the manager page.
- Open the terminal page again.
- Repeat five times.
- Type a single character.
- Assert:
  - one active terminal WebSocket exists;
  - resize listener count is stable;
  - `onData` handler count is stable;
  - the single typed character produces one input send;
  - disposed terminal instances do not receive output.

## Suggested Test Layout

```text
web/
  lib/
    disposable.js
    terminal-input.js
    terminal-layout.js
    terminal-selection.js
    terminal-view.js
    terminal-write-queue.js
  *.test.js

tests/
  e2e/
    terminal-smoke.spec.js
    terminal-rendering.spec.js
    terminal-input.spec.js
    terminal-resize.spec.js
    terminal-selection.spec.js
  fixtures/
    server.js
```

## Run Strategy

Fast local tests:

```sh
npm test
```

Browser integration tests:

```sh
npm run test:web
```

Full end-to-end tests:

```sh
npm run test:e2e
```

Recommended gates:

- Pull request: unit tests and Chromium Playwright tests.
- Nightly or manual: mobile emulation, large output, and real PTY tests.
- Release check: Chromium, WebKit, and Firefox browser runs.

## Implementation Order

1. Add Playwright smoke test for login, terminal creation, and basic display.
2. Add a real input/output E2E test.
3. Add event cleanup debug hooks and tests.
4. Implement `DisposableStore` and `TerminalView`.
5. Add write queue unit tests and browser rendering tests.
6. Implement batched write queue.
7. Add resize and mobile viewport tests.
8. Implement `TerminalLayoutController`.
9. Add input controller unit tests and quickbar integration tests.
10. Implement `TerminalInputController`.
11. Add selection and clipboard tests.
12. Implement `TerminalSelectionController`.
13. Review xterm CSS overrides and run the full browser matrix.

## Highest Priority Regression Guards

These four checks should be automated before deep refactoring begins:

- Typing once sends one input message.
- Server output eventually appears in the terminal.
- Resizing never leaves the terminal blank or with zero dimensions.
- Repeatedly entering and leaving terminal pages does not duplicate listeners.
