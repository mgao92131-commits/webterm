# WebTerm local go-vte fork

This directory contains the runtime sources from `github.com/danielgatis/go-vte`
v1.0.11 under its original MIT license.

WebTerm keeps this local fork because v1.0.11 sends every byte received while in
`Utf8State` to the UTF-8 decoder. That misclassifies ECMA-48 C0 controls such as
ESC when a terminal application emits a control sequence between bytes of a
multi-byte character. The local patch suspends the UTF-8 decode state while the
control is parsed and resumes the same partial code point afterward.
