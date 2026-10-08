// PORT OVERLAY FILE ((TSGO.3-b), docs/goport-api.md): ADDED to internal/api by goport-extract
// (go/packages Overlay) so the port carries tsgo's API session IN-PROCESS; it is never written into
// typescript-go-repo. tsgo serves the session over stdio/sockets (`tsc --api`, conn_*.go, protocol_*.go)
// and builds its programs and checkers in the project system (internal/project). The port keeps the
// session — Session, snapshotData, HandleRequest and every query handler, verbatim — and replaces those
// ends: a caller hands it a snapshot (the hand-written `project` shim holding a program built by
// XtscOpenProgram) and reads each response through XtscMarshal, protocol_msgpack.go's payload.
package api

import (
	"context"
	"sync"

	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/bundled"
	"github.com/microsoft/typescript-go/internal/checker"
	"github.com/microsoft/typescript-go/internal/compiler"
	"github.com/microsoft/typescript-go/internal/core"
	"github.com/microsoft/typescript-go/internal/json"
	"github.com/microsoft/typescript-go/internal/project"
	"github.com/microsoft/typescript-go/internal/tsoptions"
	"github.com/microsoft/typescript-go/internal/tspath"
	"github.com/microsoft/typescript-go/internal/vfs"
)

// XtscNewSession is NewSession plus the snapshot bookkeeping of handleUpdateSnapshot (session.go,
// tag typescript/v7.0.2) for one snapshot the caller built: the session then answers every query
// handler against it. It speaks the JSON protocol (UseBinaryResponses false: getSourceFile answers
// base64, never a RawBinary).
func XtscNewSession(projectSession *project.Session, snapshot *project.Snapshot) (*Session, SnapshotID) {
	s := NewSession(projectSession, &SessionOptions{})
	handle := snapshotHandle(snapshot)
	s.snapshots[handle] = &snapshotData{
		snapshot:                snapshot,
		refCount:                1,
		symbolRegistry:          make(map[SymbolID]*ast.Symbol),
		symbolCanonicalProjects: make(map[SymbolID]ProjectID),
		projectRegistries:       make(map[ProjectID]*projectRegistryData),
	}
	s.latestSnapshot = handle
	return s, handle
}

// XtscMarshal is the payload MessagePackProtocol.WriteResponse (protocol_msgpack.go) writes for a
// handler's result: its JSON (an XtscNewSession session never answers a RawBinary).
func XtscMarshal(result any) ([]byte, error) {
	return json.Marshal(result)
}

// XtscOpenProgram builds a configured project's program the way the project system does
// (project.NewConfiguredProject: the config's directory is the current directory; Project.CreateProgram:
// UseSourceOfProjectReference and the project's checker pool, then BindSourceFiles). libPath is the
// default library directory; "" serves the bundled libs (fs wrapped with bundled.WrapFS), as an
// embedded-libs tsgo build does — the shipped npm binary reads them next to its executable instead.
func XtscOpenProgram(configFileName string, fs vfs.FS, libPath string) (*compiler.Program, *XtscCheckerPool, []*ast.Diagnostic) {
	if libPath == "" {
		fs = bundled.WrapFS(fs)
		libPath = bundled.LibPath()
	}
	cwd := tspath.GetDirectoryPath(configFileName)
	host := compiler.NewCachedFSCompilerHost(cwd, fs, libPath, nil, nil)
	config, errs := tsoptions.GetParsedCommandLineOfConfigFile(configFileName, nil, nil, host, nil)
	if config == nil {
		return nil, nil, errs
	}
	var pool *XtscCheckerPool
	program := compiler.NewProgram(compiler.ProgramOptions{
		Host:                        host,
		Config:                      config,
		UseSourceOfProjectReference: true,
		CreateCheckerPool: func(p *compiler.Program) compiler.CheckerPool {
			pool = &XtscCheckerPool{program: p}
			return pool
		},
	})
	program.BindSourceFiles()
	return program, pool, errs
}

// XtscCheckerPool is the project checker pool (project/checkerpool.go) reduced to its three
// categories, one checker each: the persistent API checker every API query uses (created on first use,
// never disposed — stable type/symbol identity), the diagnostics checker, and the query checker.
// Each acquisition is exclusive — except within one REQUEST (core.WithRequestID, as the language
// server tags each request's context): a request that already holds a checker gets it again without
// blocking, the pool's request affinity (tryReacquireForRequest), which find-all-references needs (it
// acquires a checker per file while holding the first).
type XtscCheckerPool struct {
	program  *compiler.Program
	mu       sync.Mutex
	checkers [3]*checker.Checker
	locks    [3]sync.Mutex
	heldBy   [3]string
}

func (p *XtscCheckerPool) GetChecker(ctx context.Context, file *ast.SourceFile) (*checker.Checker, func()) {
	i := 2
	switch core.GetCheckerLifetime(ctx) {
	case core.CheckerLifetimeAPI:
		i = 0
	case core.CheckerLifetimeDiagnostics:
		i = 1
	}
	requestID := core.GetRequestID(ctx)
	if requestID != "" {
		p.mu.Lock()
		if p.heldBy[i] == requestID {
			c := p.checkers[i]
			p.mu.Unlock()
			return c, func() {}
		}
		p.mu.Unlock()
	}
	p.locks[i].Lock()
	p.mu.Lock()
	if p.checkers[i] == nil {
		p.checkers[i], _ = checker.NewChecker(p.program, nil)
	}
	c := p.checkers[i]
	p.heldBy[i] = requestID
	p.mu.Unlock()
	return c, sync.OnceFunc(func() {
		p.mu.Lock()
		p.heldBy[i] = ""
		p.mu.Unlock()
		p.locks[i].Unlock()
	})
}

// GetGlobalDiagnostics is the global (file-less) diagnostics of every checker created so far, as
// project.checkerPool.GetGlobalDiagnostics accumulates them.
func (p *XtscCheckerPool) GetGlobalDiagnostics() []*ast.Diagnostic {
	p.mu.Lock()
	defer p.mu.Unlock()
	var all []*ast.Diagnostic
	for _, c := range p.checkers {
		if c != nil {
			all = append(all, c.GetGlobalDiagnostics()...)
		}
	}
	return compiler.SortAndDeduplicateDiagnostics(all)
}
