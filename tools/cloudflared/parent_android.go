package main

import (
	"io"
	"os"
	"syscall"
)

func init() {
	if os.Getenv("STRIKE_PARENT_PIPE") != "1" {
		return
	}
	go func() {
		io.Copy(io.Discard, os.Stdin)
		if syscall.Kill(os.Getpid(), syscall.SIGTERM) != nil {
			os.Exit(1)
		}
	}()
}
