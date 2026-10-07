// OVERLAY FILE (docs/goport-diag-oracle.md): compiled into internal/testutil/harnessutil by
// oracle-go/build.sh through `go build -overlay`; it is never written into typescript-go-repo.
//
// XtscCompileCheckOnly is CompileFiles + CompileFilesEx + the PRE-EMIT half of
// compileFilesWithHost (harnessutil.go, tag typescript/v7.0.2), copied verbatim except that
//   - nothing is emitted (the "post" program and the TS-1 pre/post count check are dropped), and
//   - each phase's diagnostics are kept apart before the harness's SortAndDeduplicateDiagnostics,
//     so every diagnostic can be attributed to the program call that produced it.
//
// The oracle checks this copy against the unmodified runner (`diags -runner`): for every case
// without a TS-1 diagnostic the two lists must be identical.
package harnessutil

import (
	"context"
	"maps"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/bundled"
	"github.com/microsoft/typescript-go/internal/compiler"
	"github.com/microsoft/typescript-go/internal/core"
	"github.com/microsoft/typescript-go/internal/repo"
	"github.com/microsoft/typescript-go/internal/tsoptions"
	"github.com/microsoft/typescript-go/internal/tspath"
	"github.com/microsoft/typescript-go/internal/vfs/vfstest"
)

// Phase names, in the order compileFilesWithHost concatenates them.
const (
	XtscPhaseConfig      = "config"
	XtscPhaseProgram     = "program"
	XtscPhaseSyntactic   = "syntactic"
	XtscPhaseSemantic    = "semantic"
	XtscPhaseGlobal      = "global"
	XtscPhaseDeclaration = "declaration"
	XtscPhaseSuggestion  = "suggestion"
)

type XtscCheckOnly struct {
	Options          *core.CompilerOptions // after harness defaults, directives and absolutization
	HarnessOptions   *HarnessOptions
	ProgramFileNames []string // root names handed to NewProgram, in order
	IncludeLibDir    bool     // whether `/.lib` (tests/lib) is mounted in the FS
	LibDirFiles      map[string]string
	Program          compiler.ProgramLike
	Diagnostics      []*ast.Diagnostic // SortAndDeduplicateDiagnostics(all phases)
	Phase            map[*ast.Diagnostic]string
	PhaseCounts      map[string]int // before deduplication
}

// XtscResolveOptions is the option half of CompileFiles (verbatim).
func XtscResolveOptions(t *testing.T, testConfig TestConfiguration, tsconfig *tsoptions.ParsedCommandLine, currentDirectory string) (*core.CompilerOptions, *HarnessOptions) {
	var compilerOptions *core.CompilerOptions
	if tsconfig != nil {
		compilerOptions = tsconfig.ParsedConfig.CompilerOptions.Clone()
	}
	if compilerOptions == nil {
		compilerOptions = &core.CompilerOptions{}
	}
	// Set default options for tests
	if compilerOptions.NewLine == core.NewLineKindNone {
		compilerOptions.NewLine = core.NewLineKindCRLF
	}
	if compilerOptions.SkipDefaultLibCheck == core.TSUnknown {
		compilerOptions.SkipDefaultLibCheck = core.TSTrue
	}
	compilerOptions.NoErrorTruncation = core.TSTrue
	harnessOptions := HarnessOptions{UseCaseSensitiveFileNames: true, CurrentDirectory: currentDirectory}

	// Parse harness and compiler options from the test configuration
	if testConfig != nil {
		SetOptionsFromTestConfig(t, testConfig, compilerOptions, &harnessOptions, currentDirectory, false /*allowUnknownOptions*/)
	}
	return compilerOptions, &harnessOptions
}

// XtscDerived is everything CompileFilesEx derives before it creates a program.
type XtscDerived struct {
	Options          *core.CompilerOptions
	HarnessOptions   *HarnessOptions
	ProgramFileNames []string
	IncludeLibDir    bool
	LibDirFiles      map[string]string // `/.lib/...` path -> content, when IncludeLibDir
	TestFS           map[string]any
	Config           *tsoptions.ParsedCommandLine
	CurrentDirectory string
}

func XtscDerive(
	t *testing.T,
	inputFiles []*TestFile,
	otherFiles []*TestFile,
	testConfig TestConfiguration,
	tsconfig *tsoptions.ParsedCommandLine,
	currentDirectory string,
	symlinks map[string]string,
) *XtscDerived {
	compilerOptions, harnessOptions := XtscResolveOptions(t, testConfig, tsconfig, currentDirectory)

	// ---- verbatim from CompileFilesEx ----
	var programFileNames []string
	for _, file := range inputFiles {
		fileName := tspath.GetNormalizedAbsolutePath(file.UnitName, currentDirectory)

		if !tspath.FileExtensionIs(fileName, tspath.ExtensionJson) &&
			!tspath.FileExtensionIs(fileName, tspath.ExtensionTsBuildInfo) {
			programFileNames = append(programFileNames, fileName)
		}
	}

	includeLibDir := core.Some(inputFiles, func(file *TestFile) bool { return strings.Contains(file.Content, testLibFolder+"/") })

	if len(harnessOptions.LibFiles) > 0 {
		for _, libFile := range harnessOptions.LibFiles {
			if libFile == "lib.d.ts" && compilerOptions.NoLib != core.TSTrue {
				continue
			}
			programFileNames = append(programFileNames, tspath.CombinePaths(testLibFolder, libFile))
			includeLibDir = true
		}
	}

	if includeLibDir {
		repo.SkipIfNoTypeScriptSubmodule(t)
	}

	if compilerOptions.OutDir != "" {
		compilerOptions.OutDir = tspath.GetNormalizedAbsolutePath(compilerOptions.OutDir, currentDirectory)
	}
	if compilerOptions.Project != "" {
		compilerOptions.Project = tspath.GetNormalizedAbsolutePath(compilerOptions.Project, currentDirectory)
	}
	if compilerOptions.RootDir != "" {
		compilerOptions.RootDir = tspath.GetNormalizedAbsolutePath(compilerOptions.RootDir, currentDirectory)
	}
	if compilerOptions.TsBuildInfoFile != "" {
		compilerOptions.TsBuildInfoFile = tspath.GetNormalizedAbsolutePath(compilerOptions.TsBuildInfoFile, currentDirectory)
	}
	if compilerOptions.BaseUrl != "" {
		compilerOptions.BaseUrl = tspath.GetNormalizedAbsolutePath(compilerOptions.BaseUrl, currentDirectory)
	}
	if compilerOptions.DeclarationDir != "" {
		compilerOptions.DeclarationDir = tspath.GetNormalizedAbsolutePath(compilerOptions.DeclarationDir, currentDirectory)
	}
	for i, rootDir := range compilerOptions.RootDirs {
		compilerOptions.RootDirs[i] = tspath.GetNormalizedAbsolutePath(rootDir, currentDirectory)
	}
	for i, typeRoot := range compilerOptions.TypeRoots {
		compilerOptions.TypeRoots[i] = tspath.GetNormalizedAbsolutePath(typeRoot, currentDirectory)
	}

	testfs := map[string]any{}
	for _, file := range inputFiles {
		fileName := tspath.GetNormalizedAbsolutePath(file.UnitName, currentDirectory)
		testfs[fileName] = &fstest.MapFile{
			Data: []byte(file.Content),
		}
	}
	for _, file := range otherFiles {
		fileName := tspath.GetNormalizedAbsolutePath(file.UnitName, currentDirectory)
		testfs[fileName] = &fstest.MapFile{
			Data: []byte(file.Content),
		}
	}
	for src, target := range symlinks {
		srcFileName := tspath.GetNormalizedAbsolutePath(src, currentDirectory)
		targetFileName := tspath.GetNormalizedAbsolutePath(target, currentDirectory)
		testfs[srcFileName] = vfstest.Symlink(targetFileName)
	}

	libDirFiles := map[string]string{}
	if includeLibDir {
		lib := testLibFolderMap()
		maps.Copy(testfs, lib)
		for k, v := range lib {
			libDirFiles[k] = string(v.(*fstest.MapFile).Data)
		}
	}

	var configFile *tsoptions.TsConfigSourceFile
	var errors []*ast.Diagnostic
	if tsconfig != nil {
		configFile = tsconfig.ConfigFile
		errors = tsconfig.Errors
	}
	config := &tsoptions.ParsedCommandLine{
		ParsedConfig: &core.ParsedOptions{
			CompilerOptions: compilerOptions,
			FileNames:       programFileNames,
		},
		ConfigFile: configFile,
		Errors:     errors,
	}
	return &XtscDerived{
		Options:          compilerOptions,
		HarnessOptions:   harnessOptions,
		ProgramFileNames: programFileNames,
		IncludeLibDir:    includeLibDir,
		LibDirFiles:      libDirFiles,
		TestFS:           testfs,
		Config:           config,
		CurrentDirectory: currentDirectory,
	}
}

func XtscCompileCheckOnly(
	t *testing.T,
	inputFiles []*TestFile,
	otherFiles []*TestFile,
	testConfig TestConfiguration,
	tsconfig *tsoptions.ParsedCommandLine,
	currentDirectory string,
	symlinks map[string]string,
) *XtscCheckOnly {
	d := XtscDerive(t, inputFiles, otherFiles, testConfig, tsconfig, currentDirectory, symlinks)
	compilerOptions, harnessOptions, programFileNames, config := d.Options, d.HarnessOptions, d.ProgramFileNames, d.Config
	includeLibDir, libDirFiles := d.IncludeLibDir, d.LibDirFiles

	fs := vfstest.FromMap(d.TestFS, harnessOptions.UseCaseSensitiveFileNames)
	fs = bundled.WrapFS(fs)
	fs = NewOutputRecorderFS(fs)
	host := createCompilerHost(fs, bundled.LibPath(), currentDirectory)
	// ---- end verbatim (CompileFilesEx); below: the pre-emit half of compileFilesWithHost ----

	ctx := context.Background()
	preCompilerOptions := config.CompilerOptions().Clone()
	preCompilerOptions.TraceResolution = core.TSFalse
	preConfig := &tsoptions.ParsedCommandLine{
		ParsedConfig: &core.ParsedOptions{
			CompilerOptions: preCompilerOptions,
			FileNames:       config.FileNames(),
		},
		ConfigFile: config.ConfigFile,
		Errors:     config.Errors,
	}
	preProgram := createProgram(host, preConfig)

	type phased struct {
		name  string
		diags []*ast.Diagnostic
	}
	phases := []phased{
		{XtscPhaseConfig, preProgram.GetConfigFileParsingDiagnostics()},
		{XtscPhaseProgram, preProgram.GetProgramDiagnostics()},
		{XtscPhaseSyntactic, preProgram.GetSyntacticDiagnostics(ctx, nil)},
		{XtscPhaseSemantic, preProgram.GetSemanticDiagnostics(ctx, nil)},
		{XtscPhaseGlobal, preProgram.GetGlobalDiagnostics(ctx)},
	}
	if preProgram.Options().GetEmitDeclarations() {
		phases = append(phases, phased{XtscPhaseDeclaration, preProgram.GetDeclarationDiagnostics(ctx, nil)})
	}
	if harnessOptions.CaptureSuggestions {
		phases = append(phases, phased{XtscPhaseSuggestion, preProgram.GetSuggestionDiagnostics(ctx, nil)})
	}
	var preErrors []*ast.Diagnostic
	phaseOf := map[*ast.Diagnostic]string{}
	counts := map[string]int{}
	for _, p := range phases {
		counts[p.name] = len(p.diags)
		for _, d := range p.diags {
			if _, ok := phaseOf[d]; !ok {
				phaseOf[d] = p.name
			}
		}
		preErrors = append(preErrors, p.diags...)
	}
	preErrors = compiler.SortAndDeduplicateDiagnostics(preErrors)

	return &XtscCheckOnly{
		Options:          compilerOptions,
		HarnessOptions:   harnessOptions,
		ProgramFileNames: programFileNames,
		IncludeLibDir:    includeLibDir,
		LibDirFiles:      libDirFiles,
		Program:          preProgram,
		Diagnostics:      preErrors,
		Phase:            phaseOf,
		PhaseCounts:      counts,
	}
}

// XtscCompilerOption returns the harness's compiler-option declaration for a directive name (nil
// for a harness option or an unknown name) — the lookup SetOptionsFromTestConfig uses.
func XtscCompilerOption(name string) *tsoptions.CommandLineOption { return getCommandLineOption(name) }

// XtscIsHarnessOption reports whether a directive is one of the harness-only options.
func XtscIsHarnessOption(name string) bool { return getHarnessOption(name) != nil }
