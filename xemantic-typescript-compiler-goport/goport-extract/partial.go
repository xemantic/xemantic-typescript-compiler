package main

// Partial packages: tsgo's test harness (internal/testrunner, internal/testutil/harnessutil, …) is
// ordinary Go the port needs only a SLICE of — the case parsing and option derivation the
// diagnostics oracle runs (docs/goport-diag-oracle.md § 4, the preferred route) — while the rest of
// those packages reaches `gotest.tools`, `go-cmp`, baseline writers and the real file system.
//
// A partial package is extracted with only the top-level declarations REACHABLE from its roots,
// computed over every partial package of the run at once:
//
//   - a kept declaration keeps every declaration (in any partial package) holding an object it USES;
//   - a kept GenDecl is kept whole (all its specs: a var group shares an initialization order);
//   - a kept type keeps all its methods (Go satisfies interfaces structurally: any of them may be
//     called through an interface the closure names).
//
// References into fully-extracted packages need no traversal; references to anything else are
// externals, served by the hand-written shims like every other external.

import (
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"os"
	"sort"

	"golang.org/x/tools/go/packages"
)

// partialClosure: package (under internal/) -> root declaration names (functions, types, vars).
var partialClosure = map[string][]string{
	"testrunner":              {"XtscPrepare", "XtscIsSkippedTest", "XtscCaseConfigurations", "XtscEmitBaselines"},
	"testutil/tsbaseline":     {"XtscJSEmitBaseline", "XtscSourcemapBaseline", "XtscSourcemapRecordBaseline"},
	"testutil/baseline":       {"NoContent"},
	"testutil/harnessutil":    {"XtscDerive", "XtscCompileCheckOnly", "SkipUnsupportedCompilerOptions"},
	"tsoptions/tsoptionstest": {"NewVFSParseConfigHost"},
	"testutil":                {"TestProgramIsSingleThreaded"},
	// (TSGO.3-b) the API session in-process (docs/goport-api.md): the overlay's entry points keep
	// Session, whose methods (HandleRequest and every handler) are kept with it.
	"api": {"XtscNewSession", "XtscMarshal", "XtscOpenProgram",
		// (TSGO.4-a) the language service behind the language server's handlers (docs/goport-ls.md)
		"XtscResolveClientCapabilities", "XtscUserPreferences", "XtscLanguageService"},
}

// partialStubs: methods ("Recv.Name") or functions of a partial package kept as a SIGNATURE ONLY — the
// body is neither traversed for reachability nor extracted (an empty block stands in), so what only
// it reaches stays out of the closure; the porter emits the stub (refuse.txt pins each one). These
// are the API session's handlers that need tsgo's project system or language service, which the
// in-process session replaces (the caller builds the snapshot) or does not port yet.
var partialStubs = map[string][]string{
	"api": {
		// the project-session lifecycle: replaced by XtscNewSession's caller-built snapshot
		"Session.handleInitialize", "Session.handleUpdateSnapshot", "Session.handleRelease",
		"Session.handleGetDefaultProjectForFile", "Session.Close", "Session.releaseOpenRefs",
		"Session.toFileChangeSummary", "computeSnapshotChanges",
		// runtime/pprof
		"Session.handleStartCPUProfile", "Session.handleStopCPUProfile", "Session.handleSaveHeapProfile",
	},
}

// stubbedBodies are the bodies partialStubs drops (filled by computePartial).
var stubbedBodies = map[*ast.BlockStmt]bool{}

// keptDecls is the result: for each partial package, the set of its top-level declarations to extract.
type keptDecls map[string]map[ast.Decl]bool

func computePartial(loaded []*packages.Package, partial map[string][]string) keptDecls {
	type pkgDecls struct {
		pkg   *packages.Package
		decls []ast.Decl // sorted by position (files share one FileSet)
		// methods of a named type, by the type's object
		methods map[*types.TypeName][]ast.Decl
	}
	byPath := map[string]*pkgDecls{}
	for _, pkg := range loaded {
		if _, ok := partial[pkg.PkgPath]; !ok {
			continue
		}
		pd := &pkgDecls{pkg: pkg, methods: map[*types.TypeName][]ast.Decl{}}
		for _, f := range pkg.Syntax {
			for _, d := range f.Decls {
				pd.decls = append(pd.decls, d)
				if fd, ok := d.(*ast.FuncDecl); ok && fd.Recv != nil && len(fd.Recv.List) > 0 {
					name := recvBaseName(fd.Recv.List[0].Type)
					if tn, ok := pkg.Types.Scope().Lookup(name).(*types.TypeName); ok {
						pd.methods[tn] = append(pd.methods[tn], d)
					}
				}
			}
		}
		sort.Slice(pd.decls, func(i, j int) bool { return pd.decls[i].Pos() < pd.decls[j].Pos() })
		byPath[pkg.PkgPath] = pd
	}
	declAt := func(pd *pkgDecls, pos token.Pos) ast.Decl {
		i := sort.Search(len(pd.decls), func(i int) bool { return pd.decls[i].End() > pos })
		if i < len(pd.decls) && pd.decls[i].Pos() <= pos {
			return pd.decls[i]
		}
		return nil
	}
	kept := keptDecls{}
	var queue []struct {
		pd *pkgDecls
		d  ast.Decl
	}
	keep := func(pd *pkgDecls, d ast.Decl) {
		if kept[pd.pkg.PkgPath] == nil {
			kept[pd.pkg.PkgPath] = map[ast.Decl]bool{}
		}
		if kept[pd.pkg.PkgPath][d] {
			return
		}
		kept[pd.pkg.PkgPath][d] = true
		queue = append(queue, struct {
			pd *pkgDecls
			d  ast.Decl
		}{pd, d})
	}
	for path, roots := range partial {
		pd := byPath[path]
		if pd == nil {
			fmt.Fprintf(os.Stderr, "partial package %s was not loaded\n", path)
			os.Exit(2)
		}
		for _, r := range roots {
			o := pd.pkg.Types.Scope().Lookup(r)
			if o == nil {
				fmt.Fprintf(os.Stderr, "partial package %s has no root %s\n", path, r)
				os.Exit(2)
			}
			keep(pd, declAt(pd, o.Pos()))
		}
	}
	for path, stubs := range partialStubs {
		pd := byPath[tsgoModule+"/internal/"+path]
		if pd == nil {
			continue
		}
		for _, name := range stubs {
			found := false
			for _, d := range pd.decls {
				fd, ok := d.(*ast.FuncDecl)
				if !ok || fd.Body == nil {
					continue
				}
				q := fd.Name.Name
				if fd.Recv != nil && len(fd.Recv.List) > 0 {
					q = recvBaseName(fd.Recv.List[0].Type) + "." + q
				}
				if q == name {
					stubbedBodies[fd.Body] = true
					found = true
				}
			}
			if !found {
				fmt.Fprintf(os.Stderr, "partial package %s has no stub %s\n", path, name)
				os.Exit(2)
			}
		}
	}
	for len(queue) > 0 {
		it := queue[0]
		queue = queue[1:]
		info := it.pd.pkg.TypesInfo
		ast.Inspect(it.d, func(n ast.Node) bool {
			if b, ok := n.(*ast.BlockStmt); ok && stubbedBodies[b] {
				return false
			}
			var o types.Object
			switch x := n.(type) {
			case *ast.Ident:
				o = info.Uses[x]
				if o == nil {
					o = info.Defs[x]
				}
			default:
				return true
			}
			if o == nil || o.Pkg() == nil {
				return true
			}
			if f, ok := o.(*types.Func); ok {
				o = f.Origin()
			}
			if v, ok := o.(*types.Var); ok {
				o = v.Origin()
			}
			pd := byPath[o.Pkg().Path()]
			if pd == nil {
				return true
			}
			if d := declAt(pd, o.Pos()); d != nil {
				keep(pd, d)
			}
			return true
		})
		// A kept type keeps its methods.
		if gd, ok := it.d.(*ast.GenDecl); ok && gd.Tok == token.TYPE {
			for _, s := range gd.Specs {
				ts := s.(*ast.TypeSpec)
				if tn, ok := info.Defs[ts.Name].(*types.TypeName); ok {
					for _, m := range it.pd.methods[tn] {
						keep(it.pd, m)
					}
				}
			}
		}
	}
	return kept
}

// inKeptDecl reports whether pos lies in a kept declaration of a partial package (always true for
// a fully-extracted package: kept == nil).
func inKeptDecl(kept map[ast.Decl]bool, pkg *packages.Package, pos token.Pos) bool {
	if kept == nil {
		return true
	}
	for b := range stubbedBodies {
		if b.Pos() <= pos && pos < b.End() {
			return false
		}
	}
	for d := range kept {
		if d.Pos() <= pos && pos < d.End() {
			return true
		}
	}
	return false
}
