# WebTerm local go-headless-term fork

## Upstream baseline

- Project: `github.com/danielgatis/go-headless-term`
- Version: `v1.0.9`
- Commit: `777d14258771d6fb7f51f936eea134fcbe455500`
- License: MIT; the original `LICENSE` is retained in this directory.

## Why WebTerm carries this fork

WebTerm's authoritative screen protocol needs atomic projection reads, dirty-row
tracking, bounded scrollback snapshots, semantic shell metadata, and terminal
effects that are not exposed by the upstream release. Keeping those changes next
to the upstream package preserves the terminal engine API while the Go Agent
remains the only owner of screen state.

## Patch summary

- atomic screen projection and row-level dirty tracking;
- bounded snapshot/scrollback export and trim metadata;
- width, alternate-buffer, UTF-8 control, and chunk-consistency fixes;
- semantic prompt, working-directory, notification, Kitty, and image effects;
- WebTerm projection and performance regression fixtures.

## Regression tests

Run from this directory:

```sh
go test ./...
```

The WebTerm integration coverage also runs through `go test ./...` from
`go-core`, especially `internal/terminalengine`, `internal/terminalsession`, and
`internal/session`.

## Upstream and removal conditions

There is no single upstream issue covering this fork. Individual fixes may be
upstreamed when they can be expressed without WebTerm protocol concepts. The
fork can be removed only after an upstream release provides equivalent atomic
projection, dirty-row, bounded-history, effect, and regression-test behavior,
and WebTerm's screen protocol and reconnect E2E suites pass without local code.
