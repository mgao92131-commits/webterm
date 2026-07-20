package main

import (
	"fmt"
	"os"

	"webterm/go-core/internal/webtermcmd"
)

func main() {
	cmd := webtermcmd.New()
	if err := cmd.Execute(); err != nil {
		fmt.Fprintln(cmd.ErrOrStderr(), err)
		os.Exit(webtermcmd.ExitCode(err))
	}
}
