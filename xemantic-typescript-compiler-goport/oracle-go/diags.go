// The (TSGO.2) DIAGNOSTICS oracle (docs/goport-diag-oracle.md).
//
// Commands:
//
//	materialize -cases-root DIR -out DIR [-list FILE | CASE...]
//	    turn conformance cases (paths relative to -cases-root) into project directories, one per
//	    configuration, exactly as tsgo's compiler runner splits them. One JSON status line per
//	    configuration on stdout.
//	diags -cases-root DIR [-runner] [-baselines DIR] [-o OUT] PROJECT_DIR
//	diags -cases-root DIR [-runner] [-baselines DIR] -batch LIST -status FILE
//	    compile a materialized project in-process exactly as the compiler harness does (pre-emit
//	    program, all phases, SortAndDeduplicateDiagnostics) and write normalized JSON lines.
//	    LIST holds "PROJECT_DIR<TAB>OUT" lines. -runner additionally runs the unmodified runner
//	    path (with emit) and compares its .errors.txt rendering against tsgo's committed baseline.
package main

import (
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"slices"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/core"
	"github.com/microsoft/typescript-go/internal/diagnosticwriter"
	tsjson "github.com/microsoft/typescript-go/internal/json"
	"github.com/microsoft/typescript-go/internal/locale"
	"github.com/microsoft/typescript-go/internal/testrunner"
	"github.com/microsoft/typescript-go/internal/testutil/harnessutil"
	"github.com/microsoft/typescript-go/internal/tsoptions"
	"github.com/microsoft/typescript-go/internal/tspath"
)

const diagSchema = 1

// ---------------------------------------------------------------------------------------------
// testing.T outside `go test`.
//
// The harness functions take a *testing.T and use it for Skipf/Fatalf (runtime.Goexit). We run
// one top-level test through testing.RunTests and every unit of work in its own t.Run, so a
// skip or a fatal ends that unit only.

// testing.RunTests is unusable outside `go test` (its cpu list is only parsed by M.Run), so the
// oracle goes through testing.MainStart with a minimal testDeps (the same method set
// testing/internal/testdeps implements; corpusEntry is an alias of an anonymous struct, so the
// identical struct literal satisfies it).
type corpusEntryT = struct {
	Parent     string
	Path       string
	Data       []byte
	Values     []any
	Generation int
	IsSeed     bool
}

type oracleDeps struct{}

func (oracleDeps) ImportPath() string                                        { return "xtsc-oracle" }
func (oracleDeps) ModulePath() string                                        { return "" }
func (oracleDeps) MatchString(pat, str string) (bool, error)                 { return true, nil }
func (oracleDeps) SetPanicOnExit0(bool)                                      {}
func (oracleDeps) StartCPUProfile(io.Writer) error                           { return nil }
func (oracleDeps) StopCPUProfile()                                           {}
func (oracleDeps) StartTestLog(io.Writer)                                    {}
func (oracleDeps) StopTestLog() error                                        { return nil }
func (oracleDeps) WriteProfileTo(string, io.Writer, int) error               { return nil }
func (oracleDeps) ResetCoverage()                                            {}
func (oracleDeps) SnapshotCoverage()                                         {}
func (oracleDeps) CheckCorpus([]any, []reflect.Type) error                   { return nil }
func (oracleDeps) RunFuzzWorker(func(corpusEntryT) error) error              { return nil }
func (oracleDeps) ReadCorpus(string, []reflect.Type) ([]corpusEntryT, error) { return nil, nil }
func (oracleDeps) CoordinateFuzzing(time.Duration, int64, time.Duration, int64, int, []corpusEntryT, []reflect.Type, string, string) error {
	return nil
}
func (oracleDeps) InitRuntimeCoverage() (string, func(string, string) (string, error), func() float64) {
	return "", nil, nil
}

func withT(body func(t *testing.T)) {
	// M.Run parses flag.CommandLine from os.Args; the subcommand has parsed its own flags already.
	savedArgs := os.Args
	os.Args = []string{savedArgs[0], "-test.count=1"}
	defer func() { os.Args = savedArgs }()
	// testing writes its own PASS/FAIL chatter to stdout; our results go to files.
	saved := os.Stdout
	os.Stdout = os.Stderr
	defer func() { os.Stdout = saved }()
	m := testing.MainStart(oracleDeps{}, []testing.InternalTest{{Name: "xtsc", F: body}}, nil, nil, nil)
	m.Run()
}

type unitResult struct {
	skipped bool
	failed  bool
}

func runUnit(t *testing.T, name string, fn func(t *testing.T)) unitResult {
	var r unitResult
	t.Run(name, func(t *testing.T) {
		defer func() {
			r.skipped = t.Skipped()
			r.failed = t.Failed()
		}()
		defer func() {
			if p := recover(); p != nil {
				t.Errorf("panic: %v", p)
			}
		}()
		fn(t)
	})
	return r
}

// ---------------------------------------------------------------------------------------------
// case.json

type caseFile struct {
	Path   string `json:"path"`   // absolute path in the harness's virtual FS
	Role   string `json:"role"`   // root | other | tsconfig | testlib
	Sha256 string `json:"sha256"` // of the content bytes
}

type caseJSON struct {
	Schema           int               `json:"schema"`
	Case             string            `json:"case"` // relative to the cases root, e.g. compiler/foo.ts
	CaseSha256       string            `json:"caseSha256"`
	Suite            string            `json:"suite"`
	Variation        string            `json:"variation"` // "_" without variations
	TestName         string            `json:"testName"`
	ConfiguredName   string            `json:"configuredName"`
	Configuration    map[string]string `json:"configuration"`
	CurrentDirectory string            `json:"currentDirectory"`
	Files            []caseFile        `json:"files"`
	Symlinks         map[string]string `json:"symlinks"`
	RootFiles        []string          `json:"rootFiles"`
	TsConfig         *string           `json:"tsconfig"`
	IncludeLibDir    bool              `json:"includeLibDir"`
	CompilerOptions  json.RawMessage   `json:"compilerOptions"`
	HarnessOptions   json.RawMessage   `json:"harnessOptions"`
	HasNonDtsFiles   bool              `json:"hasNonDtsFiles"`
	SkippedEmit      string            `json:"skippedEmit,omitempty"`
}

type matStatus struct {
	Case      string `json:"case"`
	Variation string `json:"variation"`
	Dir       string `json:"dir,omitempty"`
	Status    string `json:"status"` // ok | skipped | fatal
	Reason    string `json:"reason,omitempty"`
}

func sha(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

func variationDir(name string) string {
	if name == "" {
		return "_"
	}
	return name
}

func suiteOf(rel string) string {
	if i := strings.IndexByte(rel, '/'); i > 0 {
		return rel[:i]
	}
	return ""
}

// unsupportedReason mirrors harnessutil.SkipUnsupportedCompilerOptions' conditions as text
// (the skip itself is still decided by calling the real function).
func unsupportedReason(o *core.CompilerOptions) string {
	switch o.Module {
	case core.ModuleKindAMD, core.ModuleKindUMD, core.ModuleKindSystem:
		return "unsupported module kind " + o.Module.String()
	}
	switch o.ModuleResolution {
	case core.ModuleResolutionKindNode10, core.ModuleResolutionKindClassic:
		return "unsupported module resolution kind " + strconv.Itoa(int(o.ModuleResolution))
	}
	switch {
	case o.ESModuleInterop.IsFalse():
		return "esModuleInterop=false is unsupported"
	case o.AllowSyntheticDefaultImports.IsFalse():
		return "allowSyntheticDefaultImports=false is unsupported"
	case o.BaseUrl != "":
		return "unsupported baseUrl"
	case o.OutFile != "":
		return "unsupported outFile"
	case o.Target == core.ScriptTargetES5:
		return "unsupported target ES5"
	case o.AlwaysStrict.IsFalse():
		return "alwaysStrict=false is unsupported"
	}
	return "SkipUnsupportedCompilerOptions"
}

func caseSubtestName(rel, variation string) string { return rel + "|" + variation }

func cmdMaterialize(args []string) error {
	fl := flag.NewFlagSet("materialize", flag.ExitOnError)
	casesRoot := fl.String("cases-root", "", "typescript-repo/tests/cases")
	out := fl.String("out", "", "output root (build/goport/diag-cases)")
	list := fl.String("list", "", "file with case paths relative to -cases-root, one per line")
	fl.Parse(args)
	if *casesRoot == "" || *out == "" {
		return fmt.Errorf("materialize needs -cases-root and -out")
	}
	if abs, err := filepath.Abs(*casesRoot); err == nil {
		*casesRoot = abs
	}
	cases := fl.Args()
	if *list != "" {
		raw, err := os.ReadFile(*list)
		if err != nil {
			return err
		}
		for _, l := range strings.Split(string(raw), "\n") {
			if l = strings.TrimSpace(l); l != "" {
				cases = append(cases, l)
			}
		}
	}
	enc := json.NewEncoder(os.Stdout)
	enc.SetEscapeHTML(false)
	var emit []matStatus
	withT(func(t *testing.T) {
		for _, rel := range cases {
			emit = append(emit, materializeCase(t, *casesRoot, *out, rel)...)
		}
	})
	for _, s := range emit {
		if err := enc.Encode(s); err != nil {
			return err
		}
	}
	return nil
}

func materializeCase(t *testing.T, casesRoot, outRoot, rel string) []matStatus {
	filename := filepath.Join(casesRoot, rel)
	basename := tspath.GetBaseFileName(filename)
	if testrunner.XtscIsSkippedTest(basename) {
		return []matStatus{{Case: rel, Variation: "*", Status: "skipped", Reason: "tsgo skippedTests"}}
	}
	var content string
	var configs []testrunner.XtscConfig
	r := runUnit(t, rel, func(t *testing.T) { content, configs = testrunner.XtscConfigurations(t, filename) })
	if r.failed || r.skipped {
		return []matStatus{{Case: rel, Variation: "*", Status: "fatal", Reason: "configuration enumeration failed (see log)"}}
	}
	var res []matStatus
	for _, c := range configs {
		v := variationDir(c.Name)
		st := matStatus{Case: rel, Variation: v}
		var prep *testrunner.XtscPrepared
		var derived *harnessutil.XtscDerived
		var cfgMap harnessutil.TestConfiguration
		if c.Named != nil {
			cfgMap = c.Named.Config
		}
		unsupported := false
		r := runUnit(t, caseSubtestName(rel, v), func(t *testing.T) {
			prep = testrunner.XtscPrepare(filename, content, c.Named)
			derived = harnessutil.XtscDerive(t, prep.ToBeCompiled, prep.OtherFiles, prep.HarnessConfig, prep.TsConfig, prep.CurrentDirectory, prep.Symlinks)
			// the runner calls this on result.Options, which IS the derived (absolutized) options
			r2 := runUnit(t, "unsupported", func(t *testing.T) { harnessutil.SkipUnsupportedCompilerOptions(t, derived.Options) })
			unsupported = r2.skipped
		})
		switch {
		case r.failed:
			st.Status, st.Reason = "fatal", "prepare failed (see log)"
		case r.skipped:
			st.Status, st.Reason = "skipped", "harness skip (TypeScript submodule / tests/lib missing)"
		case unsupported:
			st.Status, st.Reason = "skipped", "SkipUnsupportedCompilerOptions: "+unsupportedReason(derived.Options)
		default:
			dir := filepath.Join(outRoot, rel, v)
			if err := writeProject(dir, rel, content, c, cfgMap, prep, derived); err != nil {
				st.Status, st.Reason = "fatal", err.Error()
			} else {
				st.Status, st.Dir = "ok", dir
			}
		}
		res = append(res, st)
	}
	return res
}

func vfsReal(dir, virtual string) string {
	return filepath.Join(dir, "vfs", filepath.FromSlash(strings.TrimPrefix(virtual, "/")))
}

func writeProject(dir, rel, content string, c testrunner.XtscConfig, cfgMap map[string]string, prep *testrunner.XtscPrepared, d *harnessutil.XtscDerived) error {
	if err := os.RemoveAll(dir); err != nil {
		return err
	}
	cj := caseJSON{
		Schema:           diagSchema,
		Case:             rel,
		CaseSha256:       sha(content),
		Suite:            suiteOf(rel),
		Variation:        variationDir(c.Name),
		TestName:         c.TestName,
		ConfiguredName:   c.ConfiguredName,
		Configuration:    map[string]string{},
		CurrentDirectory: prep.CurrentDirectory,
		Symlinks:         map[string]string{},
		RootFiles:        d.ProgramFileNames,
		IncludeLibDir:    d.IncludeLibDir,
		HasNonDtsFiles:   prep.HasNonDtsFiles,
		SkippedEmit:      testrunner.XtscSkippedEmitTest(tspath.GetBaseFileName(rel)),
	}
	for k, v := range cfgMap {
		cj.Configuration[k] = v
	}
	if cj.RootFiles == nil {
		cj.RootFiles = []string{}
	}
	var err error
	if cj.CompilerOptions, err = tsjson.Marshal(d.Options); err != nil {
		return err
	}
	// The JSON form is the Kotlin side's input (docs/goport-diag-oracle.md § 4): it must round-trip.
	var back core.CompilerOptions
	if err := tsjson.Unmarshal(cj.CompilerOptions, &back); err != nil {
		return fmt.Errorf("compilerOptions do not round-trip: %w", err)
	}
	if !reflect.DeepEqual(&back, d.Options) {
		again, _ := tsjson.Marshal(&back)
		return fmt.Errorf("compilerOptions do not round-trip: %s -> %s", cj.CompilerOptions, again)
	}
	if cj.HarnessOptions, err = json.Marshal(d.HarnessOptions); err != nil {
		return err
	}
	// A path written twice (a case may repeat an `@filename`) holds the LAST content, as the
	// harness's FS map does (CompileFilesEx assigns inputs, then other files, by path).
	write := func(path, role, text string) error {
		entry := caseFile{Path: path, Role: role, Sha256: sha(text)}
		if i := slices.IndexFunc(cj.Files, func(f caseFile) bool { return f.Path == path }); i >= 0 {
			cj.Files[i] = entry
		} else {
			cj.Files = append(cj.Files, entry)
		}
		real := vfsReal(dir, path)
		if err := os.MkdirAll(filepath.Dir(real), 0o755); err != nil {
			return err
		}
		return os.WriteFile(real, []byte(text), 0o644)
	}
	norm := func(f *harnessutil.TestFile) string {
		return tspath.GetNormalizedAbsolutePath(f.UnitName, prep.CurrentDirectory)
	}
	for _, f := range prep.TsConfigFiles {
		p := norm(f)
		cj.TsConfig = &p
		if err := write(p, "tsconfig", f.Content); err != nil {
			return err
		}
	}
	for _, f := range prep.ToBeCompiled {
		if err := write(norm(f), "root", f.Content); err != nil {
			return err
		}
	}
	for _, f := range prep.OtherFiles {
		if err := write(norm(f), "other", f.Content); err != nil {
			return err
		}
	}
	libPaths := make([]string, 0, len(d.LibDirFiles))
	for p := range d.LibDirFiles {
		libPaths = append(libPaths, p)
	}
	sort.Strings(libPaths)
	for _, p := range libPaths {
		if err := write(p, "testlib", d.LibDirFiles[p]); err != nil {
			return err
		}
	}
	// symlinks: real relative links inside vfs/, so the directory also works for a CLI run.
	srcs := make([]string, 0, len(prep.Symlinks))
	for s := range prep.Symlinks {
		srcs = append(srcs, s)
	}
	sort.Strings(srcs)
	for _, s := range srcs {
		src := tspath.GetNormalizedAbsolutePath(s, prep.CurrentDirectory)
		dst := tspath.GetNormalizedAbsolutePath(prep.Symlinks[s], prep.CurrentDirectory)
		cj.Symlinks[src] = dst
		rs, rd := vfsReal(dir, src), vfsReal(dir, dst)
		if err := os.MkdirAll(filepath.Dir(rs), 0o755); err != nil {
			return err
		}
		relTarget, err := filepath.Rel(filepath.Dir(rs), rd)
		if err != nil {
			return err
		}
		_ = os.Remove(rs)
		if err := os.Symlink(relTarget, rs); err != nil {
			return err
		}
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	raw, err := json.MarshalIndent(cj, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(dir, "case.json"), append(raw, '\n'), 0o644); err != nil {
		return err
	}
	return writeCliTsconfig(dir, prep, d, cfgMap)
}

// writeCliTsconfig writes <dir>/tsconfig.json for the CLI cross-check ONLY (the oracle itself
// never reads it): the directives typed by their option declarations, the harness's three
// defaults, the root files, and an `extends` of the embedded tsconfig when the case has one.
// Path-valued options are absolutized against the harness's current directory and mapped into
// vfs/, as CompileFilesEx does.
func writeCliTsconfig(dir string, prep *testrunner.XtscPrepared, d *harnessutil.XtscDerived, cfgMap map[string]string) error {
	opts := map[string]any{
		"newLine":             "crlf",
		"skipDefaultLibCheck": true,
		"noErrorTruncation":   true,
	}
	if prep.TsConfig != nil && prep.TsConfig.ParsedConfig.CompilerOptions.SkipDefaultLibCheck != core.TSUnknown {
		delete(opts, "skipDefaultLibCheck")
	}
	if prep.TsConfig != nil && prep.TsConfig.ParsedConfig.CompilerOptions.NewLine != core.NewLineKindNone {
		delete(opts, "newLine")
	}
	keys := make([]string, 0, len(cfgMap))
	for k := range cfgMap {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	// relative to the tsconfig's own directory, so the project directory can move
	vfsRel := func(virtual string) string { return "./vfs/" + strings.TrimPrefix(virtual, "/") }
	mapPath := func(p string) string { return vfsRel(tspath.GetNormalizedAbsolutePath(p, prep.CurrentDirectory)) }
	for _, k := range keys {
		decl := harnessutil.XtscCompilerOption(k)
		if decl == nil {
			continue // a harness option (currentDirectory, noImplicitReferences, libFiles, ...)
		}
		v := strings.TrimSpace(cfgMap[k])
		switch decl.Kind {
		case tsoptions.CommandLineOptionTypeBoolean:
			opts[decl.Name] = strings.EqualFold(v, "true")
		case tsoptions.CommandLineOptionTypeNumber:
			if n, err := strconv.Atoi(v); err == nil {
				opts[decl.Name] = n
			} else {
				opts[decl.Name] = v
			}
		case tsoptions.CommandLineOptionTypeList, tsoptions.CommandLineOptionTypeListOrElement:
			var items []string
			for _, it := range strings.Split(v, ",") {
				if it = strings.TrimSpace(it); it != "" {
					if decl.Elements() != nil && decl.Elements().IsFilePath {
						it = mapPath(it)
					}
					items = append(items, it)
				}
			}
			if items == nil {
				items = []string{}
			}
			opts[decl.Name] = items
		case tsoptions.CommandLineOptionTypeObject:
			var obj any
			if json.Unmarshal([]byte(v), &obj) == nil {
				opts[decl.Name] = obj
			} else {
				opts[decl.Name] = v
			}
		default: // string, enum
			if decl.IsFilePath && v != "" {
				v = mapPath(v)
			}
			opts[decl.Name] = v
		}
	}
	files := []string{}
	for _, f := range d.ProgramFileNames {
		files = append(files, vfsRel(f))
	}
	cfg := map[string]any{"compilerOptions": opts, "files": files}
	if prep.TsConfig != nil {
		cfg["extends"] = vfsRel(tspath.GetNormalizedAbsolutePath(prep.TsConfigFiles[0].UnitName, prep.CurrentDirectory))
	}
	raw, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(dir, "tsconfig.json"), append(raw, '\n'), 0o644)
}

// ---------------------------------------------------------------------------------------------
// diags

// diagLine is ONE line of the oracle's JSONL output. Field order is the serialization order.
type diagLine struct {
	File     *string       `json:"file"`
	Start    int           `json:"start"`
	Length   int           `json:"length"`
	Code     int32         `json:"code"`
	Category string        `json:"category"`
	Text     string        `json:"text"`
	Related  []relatedLine `json:"related"`
	Phase    string        `json:"phase"`
	// Diagnostic.SkippedOnNoEmit: a program with noEmit drops it (checker grammar diagnostics
	// about downlevel emit). Present in harness output, which does not set noEmit by default.
	SkippedOnNoEmit bool `json:"skippedOnNoEmit"`
}

type relatedLine struct {
	File     *string `json:"file"`
	Start    int     `json:"start"`
	Length   int     `json:"length"`
	Code     int32   `json:"code"`
	Category string  `json:"category"`
	Text     string  `json:"text"`
}

func diagFile(d *ast.Diagnostic) *string {
	if d.File() == nil {
		return nil
	}
	s := d.File().FileName()
	return &s
}

func flatten(d *ast.Diagnostic) string {
	return diagnosticwriter.FlattenDiagnosticMessage(diagnosticwriter.WrapASTDiagnostic(d), "\n", locale.Default)
}

func toLine(d *ast.Diagnostic, phase string) diagLine {
	l := diagLine{
		File: diagFile(d), Start: d.Pos(), Length: d.Len(), Code: d.Code(),
		Category: d.Category().Name(), Text: flatten(d), Related: []relatedLine{}, Phase: phase,
		SkippedOnNoEmit: d.SkippedOnNoEmit(),
	}
	for _, r := range d.RelatedInformation() {
		l.Related = append(l.Related, relatedLine{
			File: diagFile(r), Start: r.Pos(), Length: r.Len(), Code: r.Code(),
			Category: r.Category().Name(), Text: flatten(r),
		})
	}
	return l
}

type diagStatus struct {
	Dir         string         `json:"dir"`
	Out         string         `json:"out,omitempty"`
	Status      string         `json:"status"` // ok | skipped | fatal | error
	Reason      string         `json:"reason,omitempty"`
	Count       int            `json:"count"`
	OutSha256   string         `json:"outSha256,omitempty"`
	PhaseCounts map[string]int `json:"phaseCounts,omitempty"`
	// -runner only
	Runner *runnerCheck `json:"runner,omitempty"`
}

type runnerCheck struct {
	// "equal": the check-only list equals the runner's list; "ts1": the runner appended the
	// TS-1 pre/post count diagnostic; "differ": anything else.
	CheckOnlyVsRunner string `json:"checkOnlyVsRunner"`
	Baseline          string `json:"baseline"`      // path relative to -baselines
	BaselineMatch     string `json:"baselineMatch"` // equal | differ | missing-expected | unexpected-absent
	RunnerCount       int    `json:"runnerCount"`
	// "differ" only: the first differing position, both sides rendered as oracle lines
	// (phase empty); a missing side is null.
	FirstDiff *[2]*diagLine `json:"firstDiff,omitempty"`
}

func readCase(dir string) (*caseJSON, error) {
	raw, err := os.ReadFile(filepath.Join(dir, "case.json"))
	if err != nil {
		return nil, err
	}
	var cj caseJSON
	if err := json.Unmarshal(raw, &cj); err != nil {
		return nil, err
	}
	if cj.Schema != diagSchema {
		return nil, fmt.Errorf("case.json schema %d, expected %d", cj.Schema, diagSchema)
	}
	return &cj, nil
}

func cmdDiags(args []string) error {
	fl := flag.NewFlagSet("diags", flag.ExitOnError)
	casesRoot := fl.String("cases-root", "", "typescript-repo/tests/cases")
	runner := fl.Bool("runner", false, "also run the unmodified runner and compare against tsgo's committed baseline")
	baselines := fl.String("baselines", "", "typescript-go-repo/testdata/baselines/reference/submodule (with -runner)")
	out := fl.String("o", "", "output JSONL (default stdout; single project)")
	batch := fl.String("batch", "", "file of PROJECT_DIR<TAB>OUT lines")
	status := fl.String("status", "", "status JSONL (with -batch)")
	fl.Parse(args)
	if *casesRoot == "" {
		return fmt.Errorf("diags needs -cases-root")
	}
	if abs, err := filepath.Abs(*casesRoot); err == nil {
		*casesRoot = abs
	}
	type item struct{ dir, out string }
	var items []item
	if *batch != "" {
		raw, err := os.ReadFile(*batch)
		if err != nil {
			return err
		}
		for _, l := range strings.Split(string(raw), "\n") {
			if l = strings.TrimRight(l, "\r"); l == "" {
				continue
			}
			parts := strings.SplitN(l, "\t", 2)
			if len(parts) != 2 {
				return fmt.Errorf("bad batch line %q", l)
			}
			items = append(items, item{parts[0], parts[1]})
		}
	} else {
		if fl.NArg() != 1 {
			return fmt.Errorf("diags needs one PROJECT_DIR (or -batch)")
		}
		items = append(items, item{fl.Arg(0), *out})
	}
	var statusW *bufio.Writer
	if *status != "" {
		f, err := os.Create(*status)
		if err != nil {
			return err
		}
		defer f.Close()
		statusW = bufio.NewWriter(f)
		defer statusW.Flush()
	}
	var firstErr error
	withT(func(t *testing.T) {
		for _, it := range items {
			st := diagsOne(t, *casesRoot, it.dir, it.out, *runner, *baselines)
			if statusW != nil {
				b, _ := json.Marshal(st)
				statusW.Write(append(b, '\n'))
				statusW.Flush()
			} else if st.Status != "ok" {
				firstErr = fmt.Errorf("%s: %s %s", it.dir, st.Status, st.Reason)
			} else if st.Runner != nil {
				b, _ := json.Marshal(st.Runner)
				fmt.Fprintln(os.Stderr, string(b))
			}
		}
	})
	return firstErr
}

func diagsOne(t *testing.T, casesRoot, dir, out string, runner bool, baselines string) diagStatus {
	st := diagStatus{Dir: dir, Out: out}
	cj, err := readCase(dir)
	if err != nil {
		st.Status, st.Reason = "error", err.Error()
		return st
	}
	filename := filepath.Join(casesRoot, cj.Case)
	contentRaw, err := os.ReadFile(filename)
	if err != nil {
		st.Status, st.Reason = "error", err.Error()
		return st
	}
	// The harness reads cases through osvfs (BOM stripped / UTF-16 decoded); hash what the
	// configuration enumeration reads, which is what materialize hashed.
	var content string
	var configs []testrunner.XtscConfig
	var check *harnessutil.XtscCheckOnly
	var prep *testrunner.XtscPrepared
	var problem string
	r := runUnit(t, caseSubtestName(cj.Case, cj.Variation), func(t *testing.T) {
		content, configs = testrunner.XtscConfigurations(t, filename)
		if sha(content) != cj.CaseSha256 {
			problem = "case source changed since materialize (re-run the materializer)"
			return
		}
		var named *harnessutil.NamedTestConfiguration
		found := false
		for _, c := range configs {
			if variationDir(c.Name) == cj.Variation {
				named, found = c.Named, true
			}
		}
		if !found {
			problem = "variation not found: " + cj.Variation
			return
		}
		prep = testrunner.XtscPrepare(filename, content, named)
		if p := verifyProject(dir, cj, prep); p != "" {
			problem = p
			return
		}
		check = harnessutil.XtscCompileCheckOnly(t, prep.ToBeCompiled, prep.OtherFiles, prep.HarnessConfig, prep.TsConfig, prep.CurrentDirectory, prep.Symlinks)
	})
	_ = contentRaw
	switch {
	case problem != "":
		st.Status, st.Reason = "error", problem
		return st
	case r.failed:
		st.Status, st.Reason = "fatal", "compile failed or panicked (see log)"
		return st
	case r.skipped:
		st.Status, st.Reason = "skipped", "harness skip"
		return st
	}
	var buf strings.Builder
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	for _, d := range check.Diagnostics {
		if err := enc.Encode(toLine(d, phaseOf(check, d))); err != nil {
			st.Status, st.Reason = "error", err.Error()
			return st
		}
	}
	text := buf.String()
	if out == "" {
		os.Stdout.WriteString(text)
	} else {
		if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
			st.Status, st.Reason = "error", err.Error()
			return st
		}
		tmp := out + ".tmp"
		if err := os.WriteFile(tmp, []byte(text), 0o644); err != nil {
			st.Status, st.Reason = "error", err.Error()
			return st
		}
		if err := os.Rename(tmp, out); err != nil {
			st.Status, st.Reason = "error", err.Error()
			return st
		}
	}
	st.Status, st.Count, st.OutSha256, st.PhaseCounts = "ok", len(check.Diagnostics), sha(text), check.PhaseCounts

	if runner {
		st.Runner = runnerCompare(t, cj, filename, content, configs, check, baselines)
	}
	return st
}

func phaseOf(c *harnessutil.XtscCheckOnly, d *ast.Diagnostic) string {
	if p, ok := c.Phase[d]; ok {
		return p
	}
	// compactAndMergeRelatedInfos cloned a diagnostic that differed only in related information
	for orig, p := range c.Phase {
		if ast.EqualDiagnosticsNoRelatedInfo(orig, d) {
			return p
		}
	}
	return "unknown"
}

// verifyProject checks that the materialized directory is a faithful view of what the oracle is
// about to compile (file set, roles, content hashes, roots, current directory).
func verifyProject(dir string, cj *caseJSON, prep *testrunner.XtscPrepared) string {
	if prep.CurrentDirectory != cj.CurrentDirectory {
		return "currentDirectory differs from case.json"
	}
	want := map[string]string{}
	norm := func(f *harnessutil.TestFile) string {
		return tspath.GetNormalizedAbsolutePath(f.UnitName, prep.CurrentDirectory)
	}
	add := func(files []*harnessutil.TestFile, role string) {
		for _, f := range files {
			want[norm(f)] = role + "|" + sha(f.Content) // last write wins, as in writeProject
		}
	}
	add(prep.TsConfigFiles, "tsconfig")
	add(prep.ToBeCompiled, "root")
	add(prep.OtherFiles, "other")
	got := map[string]string{}
	for _, f := range cj.Files {
		if f.Role == "testlib" {
			continue
		}
		got[f.Path] = f.Role + "|" + f.Sha256
		b, err := os.ReadFile(vfsReal(dir, f.Path))
		if err != nil || sha(string(b)) != f.Sha256 {
			return "materialized file missing or modified: " + f.Path
		}
	}
	if len(got) != len(want) {
		return "materialized file set differs from the harness's"
	}
	for k, v := range want {
		if got[k] != v {
			return "materialized file differs: " + k
		}
	}
	return ""
}

func runnerCompare(t *testing.T, cj *caseJSON, filename, content string, configs []testrunner.XtscConfig, check *harnessutil.XtscCheckOnly, baselines string) *runnerCheck {
	rc := &runnerCheck{}
	var named *harnessutil.NamedTestConfiguration
	var testName string
	for _, c := range configs {
		if variationDir(c.Name) == cj.Variation {
			named, testName = c.Named, c.TestName
		}
	}
	var result *harnessutil.CompilationResult
	var files []*harnessutil.TestFile
	var rendered string
	r := runUnit(t, "runner", func(t *testing.T) {
		result, files = testrunner.XtscRunnerCompile(t, testName, filename, content, named)
		rendered = testrunner.XtscErrorBaseline(t, files, result.Diagnostics, result.Options.Pretty.IsTrue())
	})
	if r.failed || r.skipped || result == nil {
		rc.CheckOnlyVsRunner, rc.BaselineMatch = "runner-failed", "runner-failed"
		return rc
	}
	rc.RunnerCount = len(result.Diagnostics)
	switch {
	case slices.EqualFunc(check.Diagnostics, result.Diagnostics, func(a, b *ast.Diagnostic) bool { return ast.CompareDiagnostics(a, b) == 0 }):
		rc.CheckOnlyVsRunner = "equal"
	case len(result.Diagnostics) > 0 && result.Diagnostics[len(result.Diagnostics)-1].Code() == -1:
		rc.CheckOnlyVsRunner = "ts1"
	default:
		rc.CheckOnlyVsRunner = "differ"
		for i := 0; i < max(len(check.Diagnostics), len(result.Diagnostics)); i++ {
			var a, b *diagLine
			if i < len(check.Diagnostics) {
				l := toLine(check.Diagnostics[i], "")
				a = &l
			}
			if i < len(result.Diagnostics) {
				l := toLine(result.Diagnostics[i], "")
				b = &l
			}
			if a == nil || b == nil || ast.CompareDiagnostics(check.Diagnostics[i], result.Diagnostics[i]) != 0 {
				rc.FirstDiff = &[2]*diagLine{a, b}
				break
			}
		}
	}
	if baselines != "" {
		name := strings.TrimSuffix(strings.TrimSuffix(cj.ConfiguredName, ".tsx"), ".ts") + ".errors.txt"
		rc.Baseline = cj.Suite + "/" + name
		b, err := os.ReadFile(filepath.Join(baselines, cj.Suite, name))
		const noContent = "<no content>"
		switch {
		case err != nil && rendered == noContent:
			rc.BaselineMatch = "equal"
		case err != nil:
			rc.BaselineMatch = "missing-expected"
		case rendered == noContent:
			rc.BaselineMatch = "unexpected-absent"
		case string(b) == rendered:
			rc.BaselineMatch = "equal"
		default:
			rc.BaselineMatch = "differ"
		}
	}
	return rc
}
