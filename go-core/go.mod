module webterm/go-core

go 1.25.1

require (
	github.com/creack/pty v1.1.24
	github.com/danielgatis/go-headless-term v1.0.9
	github.com/mileusna/useragent v1.3.5
	google.golang.org/protobuf v1.36.11
	nhooyr.io/websocket v1.8.17
)

require (
	github.com/danielgatis/go-ansicode v1.0.14 // indirect
	github.com/danielgatis/go-iterator v0.0.1 // indirect
	github.com/danielgatis/go-utf8 v1.0.1 // indirect
	github.com/danielgatis/go-vte v1.0.11 // indirect
	github.com/rivo/uniseg v0.4.7 // indirect
	github.com/stretchr/testify v1.11.1 // indirect
)

replace github.com/danielgatis/go-headless-term => ./third_party/go-headless-term

replace github.com/danielgatis/go-vte => ./third_party/go-vte
