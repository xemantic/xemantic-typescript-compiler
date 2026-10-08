// PORT OVERLAY FILE ((TSGO.5), docs/goport-cli.md): ADDED to internal/execute by goport-extract
// (go/packages Overlay) so the port carries tsgo's command line — CommandLine, tscCompilation,
// performCompilation, performIncrementalCompilation and the whole internal/execute/tsc package, verbatim.
// It is never written into typescript-go-repo. tsgo's `main` (cmd/tsgo, package main) cannot be imported;
// this file is its two ends: the System (cmd/tsgo/sys.go's osSys) and runMain's compiler branch.
package execute

import (
	"context"
	"io"
	"time"

	"github.com/microsoft/typescript-go/internal/tspath"
	"github.com/microsoft/typescript-go/internal/vfs"
)

// xtscSystem is cmd/tsgo/sys.go's osSys (tag typescript/v7.0.2) with its process ends injected: the
// writer (os.Stdout there), the file system (bundled.WrapFS(osvfs.FS()) there), the default library
// directory (bundled.LibPath()), whether stdout is a terminal and its width (golang.org/x/term), and the
// environment (os.Getenv).
type xtscSystem struct {
	writer             io.Writer
	fs                 vfs.FS
	defaultLibraryPath string
	cwd                string
	start              time.Time
	isTTY              bool
	width              int
	getenv             func(name string) string
}

func (s *xtscSystem) SinceStart() time.Duration {
	return time.Since(s.start)
}

func (s *xtscSystem) Now() time.Time {
	return time.Now()
}

func (s *xtscSystem) FS() vfs.FS {
	return s.fs
}

func (s *xtscSystem) DefaultLibraryPath() string {
	return s.defaultLibraryPath
}

func (s *xtscSystem) GetCurrentDirectory() string {
	return s.cwd
}

func (s *xtscSystem) Writer() io.Writer {
	return s.writer
}

func (s *xtscSystem) WriteOutputIsTTY() bool {
	return s.isTTY
}

func (s *xtscSystem) GetWidthOfTerminal() int {
	return s.width
}

func (s *xtscSystem) GetEnvironmentVariable(name string) string {
	return s.getenv(name)
}

// XtscCommandLine is cmd/tsgo/main.go's runMain for the compiler (the `--lsp` and `--api` branches
// are the language server's and the API's, not the CLI's): newSystem's System over the given ends,
// then execute.CommandLine, its exit status returned. The signal context (SIGINT/SIGTERM) is
// context.Background: the port's goroutines are not cancelled by a signal, the process is.
func XtscCommandLine(args []string, fs vfs.FS, defaultLibraryPath string, cwd string, writer io.Writer, isTTY bool, width int, getenv func(name string) string) int {
	sys := &xtscSystem{
		cwd:                tspath.NormalizePath(cwd),
		fs:                 fs,
		defaultLibraryPath: defaultLibraryPath,
		writer:             writer,
		start:              time.Now(),
		isTTY:              isTTY,
		width:              width,
		getenv:             getenv,
	}
	result := CommandLine(context.Background(), sys, args, nil)
	return int(result.Status)
}
