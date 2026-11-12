// OVERLAY FILE (docs/goport-diag-oracle.md): compiled into internal/testutil/harnessutil by
// oracle-go/build.sh through `go build -overlay`; it is never written into typescript-go-repo.
//
// XtscCompileCheckOnly is CompileFiles + CompileFilesEx + the PRE-EMIT half of
// compileFilesWithHost (harnessutil.go, TypeScript 7.1.0-beta, microsoft/TypeScript 3e9236361fa9
// tsc/), copied verbatim except that
//   - CompileFilesEx is split at its program creation: XtscDerive is everything up to (and
//     including) the ParsedCommandLine, and also returns the `/.lib` files it mounts; the content
//     mapper host (created first upstream, read by nothing before the compiler host) is created in
//     XtscCompileCheckOnly, right before the compiler host,
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

	"github.com/microsoft/TypeScript/tsc/internal/ast"
	"github.com/microsoft/TypeScript/tsc/internal/bundled"
	"github.com/microsoft/TypeScript/tsc/internal/compiler"
	"github.com/microsoft/TypeScript/tsc/internal/contentmapper"
	"github.com/microsoft/TypeScript/tsc/internal/core"
	"github.com/microsoft/TypeScript/tsc/internal/locale"
	"github.com/microsoft/TypeScript/tsc/internal/testutil/contentmappertest"
	"github.com/microsoft/TypeScript/tsc/internal/tsoptions"
	"github.com/microsoft/TypeScript/tsc/internal/tspath"
	"github.com/microsoft/TypeScript/tsc/internal/vfs/vfstest"
)

// Phase names, in the order compileFilesWithHost concatenates them.
const (
	XtscPhaseConfig      = "config"
	XtscPhaseProgram     = "program"
	XtscPhaseSyntactic   = "syntactic"
	XtscPhaseSemantic    = "semantic"
	XtscPhaseGlobal      = "global"
	XtscPhaseSuggestion  = "suggestion"
	XtscPhaseDeclaration = "declaration"
)

type XtscCheckOnly struct {
	Options          *core.CompilerOptions // after harness defaults and directives
	HarnessOptions   *HarnessOptions
	ProgramFileNames []tspath.RootedFilePath // root names handed to NewProgram, in order
	IncludeLibDir    bool                    // whether `/.lib` (tests/lib) is mounted in the FS
	LibDirFiles      map[string]string
	Program          compiler.ProgramLike
	Diagnostics      []*ast.Diagnostic // SortAndDeduplicateDiagnostics(all phases)
	Phase            map[*ast.Diagnostic]string
	PhaseCounts      map[string]int // before deduplication
}

// XtscResolveOptions is the option half of CompileFiles (verbatim).
func XtscResolveOptions(t *testing.T, testConfig TestConfiguration, tsconfig *tsoptions.ParsedCommandLine, currentDirectory tspath.RootedDirectoryPath) (*core.CompilerOptions, *HarnessOptions) {
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
	harnessOptions := HarnessOptions{CaseSensitivity: tspath.CaseSensitive, CurrentDirectory: currentDirectory}

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
	ProgramFileNames []tspath.RootedFilePath
	IncludeLibDir    bool
	LibDirFiles      map[string]string // `/.lib/...` path -> content, when IncludeLibDir
	TestFS           map[string]any
	ContentMappers   []*contentmapper.Mapper
	Config           *tsoptions.ParsedCommandLine
	CurrentDirectory tspath.RootedDirectoryPath
}

func XtscDerive(
	t *testing.T,
	inputFiles []*TestFile,
	otherFiles []*TestFile,
	testConfig TestConfiguration,
	tsconfig *tsoptions.ParsedCommandLine,
	currentDirectory tspath.RootedDirectoryPath,
	symlinks map[string]string,
) *XtscDerived {
	compilerOptions, harnessOptions := XtscResolveOptions(t, testConfig, tsconfig, currentDirectory)

	// ---- verbatim from CompileFilesEx ----
	var programFileNames []tspath.RootedFilePath
	for _, file := range inputFiles {
		fileName := tspath.ToRootedFilePath(file.UnitName, currentDirectory)

		if !fileName.ExtensionIs(tspath.ExtensionJson) &&
			!fileName.ExtensionIs(tspath.ExtensionTsBuildInfo) {
			programFileNames = append(programFileNames, fileName)
		}
	}

	includeLibDir := core.Some(inputFiles, func(file *TestFile) bool { return strings.Contains(file.Content, testLibFolder+"/") })

	if len(harnessOptions.LibFiles) > 0 {
		for _, libFile := range harnessOptions.LibFiles {
			if libFile == "lib.d.ts" && compilerOptions.NoLib != core.TSTrue {
				continue
			}
			programFileNames = append(programFileNames, currentDirectory.ResolveFile(tspath.CombinePaths(testLibFolder, libFile)))
			includeLibDir = true
		}
	}

	var contentMappers []*contentmapper.Mapper
	if tsconfig != nil && tsconfig.ParsedConfig != nil {
		contentMappers = tsconfig.ParsedConfig.ContentMappers
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
	baseDirectory := currentDirectory
	if tsconfig != nil && tsconfig.BaseDirectory() != "" {
		baseDirectory = tsconfig.BaseDirectory()
	}
	config := tsoptions.NewParsedCommandLine(compilerOptions, programFileNames, nil, baseDirectory, harnessOptions.CaseSensitivity)
	config.ParsedConfig.ContentMappers = contentMappers
	config.ConfigFile = configFile
	config.Errors = errors
	// ---- end verbatim (up to the content mapper project and the compiler host) ----
	return &XtscDerived{
		Options:          compilerOptions,
		HarnessOptions:   harnessOptions,
		ProgramFileNames: programFileNames,
		IncludeLibDir:    includeLibDir,
		LibDirFiles:      libDirFiles,
		TestFS:           testfs,
		ContentMappers:   contentMappers,
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
	currentDirectory tspath.RootedDirectoryPath,
	symlinks map[string]string,
) *XtscCheckOnly {
	d := XtscDerive(t, inputFiles, otherFiles, testConfig, tsconfig, currentDirectory, symlinks)
	compilerOptions, harnessOptions, programFileNames, config := d.Options, d.HarnessOptions, d.ProgramFileNames, d.Config
	includeLibDir, libDirFiles, contentMappers := d.IncludeLibDir, d.LibDirFiles, d.ContentMappers

	// ---- verbatim from CompileFilesEx ----
	fs := vfstest.FromMap(d.TestFS, harnessOptions.CaseSensitivity)
	fs = bundled.WrapFS(fs)
	fs = NewOutputRecorderFS(fs)

	var contentMapperHost contentmapper.Host
	if compilerOptions.RunExternalCode.IsTrue() && len(contentMappers) > 0 {
		contentMapperHost = contentmapper.NewHost(context.Background(), contentmappertest.NewSpawner(), locale.Default)
		defer contentMapperHost.Close()
	}

	var contentMapperProject contentmapper.Project
	if contentMapperHost != nil {
		contentMapperProject = contentMapperHost.Project(contentmapper.ProjectSpec{
			ConfigFileName:  config.ConfigName(),
			Mappers:         config.ContentMappers(),
			CompilerOptions: config.CompilerOptions(),
		})
		defer contentMapperProject.Close()
	}
	host := createCompilerHost(fs, bundled.LibPath(), currentDirectory, contentMapperProject)
	// ---- end verbatim (CompileFilesEx); below: the pre-emit half of compileFilesWithHost ----

	ctx := context.Background()
	preCompilerOptions := config.CompilerOptions().Clone()
	preCompilerOptions.TraceResolution = core.TSFalse
	preConfig := tsoptions.NewParsedCommandLine(
		preCompilerOptions,
		config.FileNames(),
		config.ProjectReferences(),
		config.BaseDirectory(),
		config.CaseSensitivity(),
	)
	preConfig.ParsedConfig.ContentMappers = config.ContentMappers()
	preConfig.ConfigFile = config.ConfigFile
	preConfig.Errors = config.Errors
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
	if harnessOptions.CaptureSuggestions {
		phases = append(phases, phased{XtscPhaseSuggestion, preProgram.GetSuggestionDiagnostics(ctx, nil)})
	}
	if preProgram.Options().GetEmitDeclarations() {
		phases = append(phases, phased{XtscPhaseDeclaration, preProgram.GetDeclarationDiagnostics(ctx, nil)})
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
