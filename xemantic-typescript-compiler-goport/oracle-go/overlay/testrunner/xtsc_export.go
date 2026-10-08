// OVERLAY FILE (docs/goport-diag-oracle.md): compiled into internal/testrunner by
// oracle-go/build.sh through `go build -overlay`; it is never written into typescript-go-repo.
//
// It exports the compiler runner's own case parsing so the diag oracle prepares a case exactly
// as compiler_runner.go does. XtscPrepare is a VERBATIM copy of newCompilerTest's
// pre-CompileFiles block (compiler_runner.go, tag typescript/v7.0.2); keep it in sync on a bump.
package testrunner

import (
	"fmt"
	"maps"
	"slices"
	"strings"
	"testing"

	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/core"
	"github.com/microsoft/typescript-go/internal/diagnosticwriter"
	"github.com/microsoft/typescript-go/internal/testutil/baseline"
	"github.com/microsoft/typescript-go/internal/testutil/harnessutil"
	"github.com/microsoft/typescript-go/internal/testutil/tsbaseline"
	"github.com/microsoft/typescript-go/internal/tsoptions"
	"github.com/microsoft/typescript-go/internal/tspath"
)

// XtscIsSkippedTest reports compiler_runner.go's whole-file `skippedTests`.
func XtscIsSkippedTest(basename string) bool { return slices.Contains(skippedTests, basename) }

// XtscSkippedEmitTest reports compiler_runner.go's `skippedEmitTests` (emit-only skips).
func XtscSkippedEmitTest(basename string) string { return skippedEmitTests[basename] }

type XtscConfig struct {
	Named          *harnessutil.NamedTestConfiguration // nil: the case has no directives at all
	Name           string                              // "" without variations
	TestName       string                              // `basename[ name]`, as runTest names the subtest
	ConfiguredName string                              // `base(name).ext`, the baseline file name stem
}

// XtscConfigurations reads a case and enumerates its configurations as runTest does.
func XtscConfigurations(t *testing.T, filename string) (string, []XtscConfig) {
	test := getCompilerFileBasedTest(t, filename)
	basename := tspath.GetBaseFileName(filename)
	named := test.configurations
	if len(named) == 0 {
		named = []*harnessutil.NamedTestConfiguration{nil}
	}
	var out []XtscConfig
	for _, nc := range named {
		c := XtscConfig{Named: nc, TestName: basename, ConfiguredName: basename}
		if nc != nil && nc.Name != "" {
			c.Name = nc.Name
			c.TestName = basename + " " + nc.Name
			extname := tspath.GetAnyExtensionFromPath(basename, nil, false)
			c.ConfiguredName = basename[:len(basename)-len(extname)] + "(" + nc.Name + ")" + extname
		}
		out = append(out, c)
	}
	return test.content, out
}

type XtscPrepared struct {
	HarnessConfig    harnessutil.TestConfiguration
	CurrentDirectory string
	ToBeCompiled     []*harnessutil.TestFile
	OtherFiles       []*harnessutil.TestFile
	TsConfigFiles    []*harnessutil.TestFile
	TsConfig         *tsoptions.ParsedCommandLine
	Symlinks         map[string]string
	HasNonDtsFiles   bool
}

// XtscPrepare is newCompilerTest up to (not including) its harnessutil.CompileFiles call.
// The configuration map is CLONED first: the runner mutates it (`baseurl`), and the oracle may
// prepare the same configuration more than once.
func XtscPrepare(filename string, content string, named *harnessutil.NamedTestConfiguration) *XtscPrepared {
	testContent := makeUnitsFromTest(content, filename)
	var configuration harnessutil.TestConfiguration
	if named != nil {
		configuration = maps.Clone(named.Config)
	}
	testCaseContentWithConfig := testCaseContentWithConfig{
		testCaseContent: testContent,
		configuration:   configuration,
	}

	// ---- verbatim from newCompilerTest ----
	harnessConfig := testCaseContentWithConfig.configuration
	currentDirectory := tspath.GetNormalizedAbsolutePath(harnessConfig["currentdirectory"], srcFolder)

	units := testCaseContentWithConfig.testUnitData
	var toBeCompiled []*harnessutil.TestFile
	var otherFiles []*harnessutil.TestFile
	var tsConfig *tsoptions.ParsedCommandLine
	hasNonDtsFiles := core.Some(
		units,
		func(unit *testUnit) bool { return !tspath.FileExtensionIs(unit.name, tspath.ExtensionDts) },
	)
	var tsConfigFiles []*harnessutil.TestFile
	if testCaseContentWithConfig.tsConfig != nil {
		tsConfig = testCaseContentWithConfig.tsConfig
		tsConfigFiles = []*harnessutil.TestFile{
			createHarnessTestFile(testCaseContentWithConfig.tsConfigFileUnitData, currentDirectory),
		}
		for _, unit := range units {
			if slices.Contains(
				tsConfig.ParsedConfig.FileNames,
				tspath.GetNormalizedAbsolutePath(unit.name, currentDirectory),
			) {
				toBeCompiled = append(toBeCompiled, createHarnessTestFile(unit, currentDirectory))
			} else {
				otherFiles = append(otherFiles, createHarnessTestFile(unit, currentDirectory))
			}
		}
	} else {
		baseUrl, ok := harnessConfig["baseurl"]
		if ok && !tspath.IsRootedDiskPath(baseUrl) {
			harnessConfig["baseurl"] = tspath.GetNormalizedAbsolutePath(baseUrl, currentDirectory)
		}

		lastUnit := units[len(units)-1]
		if testCaseContentWithConfig.configuration["noimplicitreferences"] != "" ||
			strings.Contains(lastUnit.content, requireStr) ||
			referencesRegex.MatchString(lastUnit.content) {
			toBeCompiled = append(toBeCompiled, createHarnessTestFile(lastUnit, currentDirectory))
			for _, unit := range units[:len(units)-1] {
				otherFiles = append(otherFiles, createHarnessTestFile(unit, currentDirectory))
			}
		} else {
			toBeCompiled = core.Map(units, func(unit *testUnit) *harnessutil.TestFile { return createHarnessTestFile(unit, currentDirectory) })
		}
	}
	// ---- end verbatim ----

	return &XtscPrepared{
		HarnessConfig:    harnessConfig,
		CurrentDirectory: currentDirectory,
		ToBeCompiled:     toBeCompiled,
		OtherFiles:       otherFiles,
		TsConfigFiles:    tsConfigFiles,
		TsConfig:         tsConfig,
		Symlinks:         testContent.symlinks,
		HasNonDtsFiles:   hasNonDtsFiles,
	}
}

// XtscRunnerCompile runs the UNMODIFIED runner path (newCompilerTest → harnessutil.CompileFiles,
// i.e. a pre-emit program, an emitting program and the TS-1 count check) and returns what
// verifyDiagnostics would baseline: the files list and the diagnostics.
func XtscRunnerCompile(t *testing.T, testName string, filename string, content string, named *harnessutil.NamedTestConfiguration) (*harnessutil.CompilationResult, []*harnessutil.TestFile) {
	payload := makeUnitsFromTest(content, filename)
	if named != nil {
		named = &harnessutil.NamedTestConfiguration{Name: named.Name, Config: maps.Clone(named.Config)}
	}
	ct := newCompilerTest(t, testName, filename, &payload, named)
	files := core.Concatenate(ct.tsConfigFiles, core.Concatenate(ct.toBeCompiled, ct.otherFiles))
	return ct.result, files
}

// XtscErrorBaseline renders diagnostics exactly as verifyDiagnostics → tsbaseline.DoErrorBaseline.
func XtscErrorBaseline(t *testing.T, files []*harnessutil.TestFile, errors []*ast.Diagnostic, pretty bool) string {
	if len(errors) == 0 {
		return baseline.NoContent
	}
	return tsbaseline.GetErrorBaseline(t, files, diagnosticwriter.WrapASTDiagnostics(errors), diagnosticwriter.CompareASTDiagnostics, pretty)
}

// ---- (TSGO.3) the emit baselines (docs/goport-emit-oracle.md) ----

// XtscEmitArtifact is one emit baseline of a configuration, as runSingleConfigTest's
// verifyJavaScriptOutput / verifySourceMapOutput / verifySourceMapRecord would write it.
type XtscEmitArtifact struct {
	Kind     string // "output" | "sourcemap" | "sourcemap record" (the runner's subtest names)
	Status   string // "ok" (Baseline/Content hold what baseline.Run would get) | "absent" (never reaches baseline.Run) | "skipped" | "failed" (t.Fatal or a panic)
	Baseline string // the baseline file name, e.g. foo(target=es2015).js
	Content  string // the text baseline.Run would compare ("<no content>" = the file must not exist)
}

// XtscEmitBaselines is newCompilerTest (UNMODIFIED, i.e. harnessutil.CompileFiles with emit, the
// TS-1 count check and the runner's own options) followed by the three emit verifications of
// runSingleConfigTest, each in its own subtest as the runner runs them. `header` is the
// `tests/cases/<suite>/<file>` path the verifications derive from repo.TestDataPath(). A
// verification's panic is recovered here (the runner's RecoverAndFail turns it into t.Fatal):
// both end the artifact as "failed".
func XtscEmitBaselines(t *testing.T, testName string, filename string, content string, named *harnessutil.NamedTestConfiguration, header string) []*XtscEmitArtifact {
	payload := makeUnitsFromTest(content, filename)
	if named != nil {
		named = &harnessutil.NamedTestConfiguration{Name: named.Name, Config: maps.Clone(named.Config)}
	}
	c := newCompilerTest(t, testName, filename, &payload, named)
	opts := baseline.Options{}
	run := func(kind string, fn func(a *XtscEmitArtifact)) *XtscEmitArtifact {
		a := &XtscEmitArtifact{Kind: kind, Status: "failed"}
		t.Run(kind, func(t *testing.T) {
			defer func() {
				if p := recover(); p != nil {
					a.Status = "failed"
					a.Content = fmt.Sprint(p)
				}
			}()
			fn(a)
		})
		return a
	}
	var out []*XtscEmitArtifact
	// verifyJavaScriptOutput
	if !c.hasNonDtsFiles {
		out = append(out, &XtscEmitArtifact{Kind: "output", Status: "absent"})
	} else if _, ok := skippedEmitTests[c.basename]; ok {
		out = append(out, &XtscEmitArtifact{Kind: "output", Status: "skipped"})
	} else {
		out = append(out, run("output", func(a *XtscEmitArtifact) {
			a.Baseline, a.Content = tsbaseline.XtscJSEmitBaseline(t, c.configuredName, header, c.options, c.result, c.tsConfigFiles, c.toBeCompiled, c.otherFiles, c.harnessOptions, opts)
			a.Status = "ok"
		}))
	}
	// verifySourceMapOutput
	out = append(out, run("sourcemap", func(a *XtscEmitArtifact) {
		var ran bool
		a.Baseline, a.Content, ran = tsbaseline.XtscSourcemapBaseline(t, c.configuredName, header, c.options, c.result, c.harnessOptions, opts)
		a.Status = "absent"
		if ran {
			a.Status = "ok"
		}
	}))
	// verifySourceMapRecord
	out = append(out, run("sourcemap record", func(a *XtscEmitArtifact) {
		a.Baseline, a.Content = tsbaseline.XtscSourcemapRecordBaseline(t, c.configuredName, header, c.options, c.result, c.harnessOptions, opts)
		a.Status = "ok"
	}))
	return out
}
