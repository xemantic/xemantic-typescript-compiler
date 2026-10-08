// Command goport-extract turns tsgo's Go source into a fully typed JSON IR,
// one file per Go package, from which the Kotlin porter
// (xemantic-typescript-compiler-goport) generates the Kotlin port of tsgo.
//
// The IR is documented in docs/goport-ir.md; the porter contract in
// docs/goport-design.md. Usage:
//
//	GOTOOLCHAIN=local tools/go/bin/go run ./xemantic-typescript-compiler-goport/goport-extract \
//	    [--tsgo DIR] [--out DIR] [--stats FILE] [--check] [pkg ...]
//
// A pkg is a path under the tsgo module's internal/ ("ast", "api/encoder") or
// a full import path. With no pkg the spike closure is extracted.
package main

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"golang.org/x/tools/go/packages"
)

// irSchemaVersion is bumped on every incompatible change to the IR shape.
const irSchemaVersion = 2

const tsgoModule = "github.com/microsoft/typescript-go"

var defaultClosure = []string{
	"ast", "diagnostics", "parser", "stringutil", "scanner", "api/encoder", "core",
	"tspath", "collections", "jsnum", "json", "debug", "locale", "binder",
	// (TSGO.2): everything internal/compiler needs to build a Program and run
	// the checker (`go list -deps ./internal/compiler`), plus the bundled lib files.
	"astnav", "checker", "compiler", "evaluator", "glob", "module", "modulespecifiers",
	"nodebuilder", "outputpaths", "packagejson", "printer", "pseudochecker", "semver",
	"sourcemap", "symlinks", "tracing", "transformers", "transformers/declarations",
	"transformers/estransforms", "transformers/inliners", "transformers/jsxtransforms",
	"transformers/moduletransforms", "transformers/tstransforms", "tsoptions", "vfs",
	"vfs/cachedvfs", "vfs/vfsmatch", "bundled",
	// (TSGO.2) the compiler test harness's case preparation (docs/goport-diag-oracle.md § 4):
	// these whole, plus the partial packages of partial.go.
	"execute/incremental", "vfs/iovfs", "vfs/internal", "vfs/vfstest", "testutil/race",
}

// overlayFiles are ADDED to tsgo packages through go/packages' Overlay (never written into
// typescript-go-repo): the oracle's verbatim copies of the runner's/harness's prepare blocks
// (oracle-go/overlay) and the port's own glue (overlay/), so the port and the oracle run the same code.
var overlayFiles = map[string]string{
	"internal/testrunner/zz_xtsc_export.go":           "../oracle-go/overlay/testrunner/xtsc_export.go",
	"internal/testutil/harnessutil/zz_xtsc_export.go": "../oracle-go/overlay/harnessutil/xtsc_export.go",
	"internal/testrunner/zz_xtsc_port.go":             "overlay/testrunner/xtsc_port.go",
}

// overlaySrc holds the overlay files' contents by their virtual path (they exist on no disk).
var overlaySrc = map[string][]byte{}

func readFile(name string) ([]byte, error) {
	if b, ok := overlaySrc[name]; ok {
		return b, nil
	}
	return os.ReadFile(name)
}

// repoRoot finds the xtsc repository root by walking up from the working
// directory to the first directory holding typescript-go-repo/.
func repoRoot() string {
	d, _ := os.Getwd()
	for {
		if st, err := os.Stat(filepath.Join(d, "typescript-go-repo")); err == nil && st.IsDir() {
			return d
		}
		parent := filepath.Dir(d)
		if parent == d {
			return ""
		}
		d = parent
	}
}

func main() {
	root := repoRoot()
	tsgoDir := flag.String("tsgo", filepath.Join(root, "typescript-go-repo"), "tsgo checkout (module "+tsgoModule+")")
	outDir := flag.String("out", filepath.Join(root, "build", "goport", "ir"), "output directory for the IR")
	statsFile := flag.String("stats", "", "write the closure census (markdown) to this file")
	check := flag.Bool("check", false, "report IR holes (expressions without a type, identifiers without an object); exit 1 if any")
	flag.Parse()

	pkgs := flag.Args()
	partial := map[string][]string{}
	useOverlay := false
	if len(pkgs) == 0 {
		pkgs = defaultClosure
		for p, roots := range partialClosure {
			pkgs = append(pkgs, p)
			partial[tsgoModule+"/internal/"+p] = roots
		}
		sort.Strings(pkgs[len(defaultClosure):])
		useOverlay = true
	}
	var patterns []string
	closure := map[string]bool{}
	for _, p := range pkgs {
		if !strings.Contains(p, ".") || !strings.Contains(strings.SplitN(p, "/", 2)[0], ".") {
			p = tsgoModule + "/internal/" + p
		}
		patterns = append(patterns, p)
		closure[p] = true
	}

	start := time.Now()
	// Prefer the repository's pinned Go toolchain for the `go list` that
	// go/packages runs (it resolves "go" through THIS process's PATH).
	if root != "" {
		gobin := filepath.Join(root, "tools", "go", "bin")
		if _, err := os.Stat(gobin); err == nil {
			os.Setenv("PATH", gobin+string(os.PathListSeparator)+os.Getenv("PATH"))
			os.Setenv("GOTOOLCHAIN", "local")
		}
	}
	env := os.Environ()
	cfg := &packages.Config{
		Dir: *tsgoDir,
		Env: env,
		Mode: packages.NeedName | packages.NeedFiles | packages.NeedCompiledGoFiles | packages.NeedSyntax |
			packages.NeedTypes | packages.NeedTypesInfo | packages.NeedImports,
		Tests: false,
	}
	if useOverlay {
		here := filepath.Join(root, "xemantic-typescript-compiler-goport", "goport-extract")
		cfg.Overlay = map[string][]byte{}
		for dst, src := range overlayFiles {
			abs := filepath.Join(*tsgoDir, dst)
			if _, err := os.Stat(abs); err == nil {
				fmt.Fprintln(os.Stderr, "overlay target exists on disk (an overlay only ADDS files):", abs)
				os.Exit(2)
			}
			b, err := os.ReadFile(filepath.Join(here, src))
			if err != nil {
				panic(err)
			}
			cfg.Overlay[abs] = b
			overlaySrc[abs] = b
		}
	}
	loaded, err := packages.Load(cfg, patterns...)
	if err != nil {
		fmt.Fprintln(os.Stderr, "load:", err)
		os.Exit(2)
	}
	if n := packages.PrintErrors(loaded); n > 0 {
		fmt.Fprintln(os.Stderr, "type errors in tsgo; refusing to extract")
		os.Exit(2)
	}
	sort.Slice(loaded, func(i, j int) bool { return loaded[i].PkgPath < loaded[j].PkgPath })
	loadTime := time.Since(start)

	if err := os.MkdirAll(*outDir, 0o755); err != nil {
		panic(err)
	}
	st := newStats(tsgoModule, closure)
	var index []any
	var order []string
	holesTotal := 0
	allHoles := map[string][]string{}
	for _, pkg := range loaded {
		for _, f := range pkg.CompiledGoFiles {
			closureFiles[f] = true
		}
	}
	kept := computePartial(loaded, partial)
	ifaces := collectInterfaces(loaded, kept)
	for _, pkg := range loaded {
		p := newPx(pkg, closure, tsgoModule, st, ifaces)
		p.kept = kept[pkg.PkgPath]
		ir, files := p.packageIR()
		short := strings.TrimPrefix(pkg.PkgPath, tsgoModule+"/")
		name := strings.ReplaceAll(short, "/", "_") + ".json"
		sum, size := writeJSON(filepath.Join(*outDir, name), ir)
		ps := st.pkg(pkg.PkgPath)
		ps.irBytes, ps.irSha = size, sum
		order = append(order, pkg.PkgPath)

		var fl []any
		lines, genLines, decls := 0, 0, 0
		for _, f := range files {
			fl = append(fl, obj().S("name", f.name).S("generated", f.generated).S("lines", f.lines).S("decls", f.decls))
			lines += f.lines
			decls += f.decls
			if f.generated {
				genLines += f.lines
			}
		}
		index = append(index, obj().S("path", pkg.PkgPath).S("name", pkg.Name).S("file", name).
			S("sha256", sum).S("bytes", size).S("lines", lines).S("generatedLines", genLines).
			S("decls", decls).S("types", len(p.typeEntries)).S("objects", len(p.objList)).
			S("scopes", len(p.scopeEntries)).S("files", fl))
		fmt.Printf("%-48s %9d bytes  %5d types %6d objects  sha256 %s\n", short, size, len(p.typeEntries), len(p.objList), sum[:16])

		cats := make([]string, 0, len(p.holes))
		for c := range p.holes {
			cats = append(cats, c)
		}
		sort.Strings(cats)
		for _, c := range cats {
			holesTotal += len(p.holes[c])
			allHoles[c] = append(allHoles[c], p.holes[c]...)
		}
	}
	idx := obj().S("schema", irSchemaVersion).S("module", tsgoModule).S("packages", Lines(index))
	writeJSON(filepath.Join(*outDir, "index.json"), idx)
	elapsed := time.Since(start)
	fmt.Printf("extracted %d packages in %s (load %s) -> %s\n", len(loaded), elapsed.Round(time.Millisecond), loadTime.Round(time.Millisecond), *outDir)

	if *statsFile != "" {
		md := st.markdown(order, "")
		if err := os.WriteFile(*statsFile, []byte(md), 0o644); err != nil {
			panic(err)
		}
		fmt.Println("stats ->", *statsFile)
	}

	if *check {
		cats := make([]string, 0, len(allHoles))
		for c := range allHoles {
			cats = append(cats, c)
		}
		sort.Strings(cats)
		for _, c := range cats {
			l := allHoles[c]
			fmt.Printf("HOLE %-40s %6d  e.g. %s\n", c, len(l), strings.Join(first(l, 5), "; "))
		}
		if holesTotal > 0 {
			fmt.Printf("check: %d holes\n", holesTotal)
			os.Exit(1)
		}
		fmt.Println("check: no holes — every expression has a type and every identifier an object")
	}
}

func first(l []string, n int) []string {
	if len(l) < n {
		return l
	}
	return l[:n]
}

// writeJSON writes v and returns the file's sha256 and size.
func writeJSON(path string, v any) (string, int) {
	f, err := os.Create(path)
	if err != nil {
		panic(err)
	}
	h := sha256.New()
	cw := &countWriter{}
	bw := bufio.NewWriterSize(multi{f, h, cw}, 1<<20)
	j := &jw{w: bw}
	j.val(v)
	bw.WriteByte('\n')
	if err := bw.Flush(); err != nil {
		panic(err)
	}
	if err := f.Close(); err != nil {
		panic(err)
	}
	return hex.EncodeToString(h.Sum(nil)), cw.n
}

type countWriter struct{ n int }

func (c *countWriter) Write(b []byte) (int, error) { c.n += len(b); return len(b), nil }

type multi struct {
	a, b, c interface{ Write([]byte) (int, error) }
}

func (m multi) Write(b []byte) (int, error) {
	m.a.Write(b)
	m.b.Write(b)
	m.c.Write(b)
	return len(b), nil
}
