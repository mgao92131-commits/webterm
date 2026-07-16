# WebTerm local go-vte fork

This directory contains the runtime sources from `github.com/danielgatis/go-vte`
v1.0.11, commit `f7c0f78076be9695c1fc01e1c0d04c9dbad960ac`, under
its original MIT license. The original `LICENSE` is retained.

WebTerm keeps this local fork because v1.0.11 sends every byte received while in
`Utf8State` to the UTF-8 decoder. That misclassifies ECMA-48 C0 controls such as
ESC when a terminal application emits a control sequence between bytes of a
multi-byte character. The local patch suspends the UTF-8 decode state while the
control is parsed and resumes the same partial code point afterward.

## Patch and regression coverage

The patch is isolated in the UTF-8 performer/parser path. Regression coverage is
provided by `parser_utf8_control_test.go`, `parser_utf8_performer_test.go`, the
fixtures in `fixtures/`, and the full upstream parser suite:

```sh
go test ./...
```

## Upstream and removal conditions

The fork can be removed after upstream merges equivalent suspended UTF-8 state
handling, publishes a release containing it, and both the local parser suite and
WebTerm's headless-terminal chunk/UTF-8 regressions pass against that release.
