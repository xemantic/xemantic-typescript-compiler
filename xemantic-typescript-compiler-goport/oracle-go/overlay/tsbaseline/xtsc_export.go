// OVERLAY FILE (docs/goport-emit-oracle.md): compiled into internal/testutil/tsbaseline by
// oracle-go/build.sh through `go build -overlay` (and ADDED to the port's IR by goport-extract); it
// is never written into typescript-go-repo.
//
// VERBATIM copies of DoJSEmitBaseline (js_emit_baseline.go), DoSourcemapBaseline
// (sourcemap_baseline.go) and DoSourcemapRecordBaseline (sourcemap_record_baseline.go), tag
// typescript/v7.0.2, with exactly one change each: the final `baseline.Run(t, baselinePath,
// actual, opts)` -- which writes and compares a file -- RETURNS (baselinePath, actual) instead, so
// the emit oracle and the port render the same text and compare it themselves. (The sourcemap
// copy also reports whether it reached baseline.Run at all.) Keep in sync on a tsgo bump.
package tsbaseline

import (
	"slices"
	"strings"
	"testing"

	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/collections"
	"github.com/microsoft/typescript-go/internal/core"
	"github.com/microsoft/typescript-go/internal/diagnosticwriter"
	"github.com/microsoft/typescript-go/internal/parser"
	"github.com/microsoft/typescript-go/internal/testutil/baseline"
	"github.com/microsoft/typescript-go/internal/testutil/harnessutil"
	"github.com/microsoft/typescript-go/internal/tspath"
)

func XtscJSEmitBaseline(
	t *testing.T,
	baselinePath string,
	header string,
	options *core.CompilerOptions,
	result *harnessutil.CompilationResult,
	tsConfigFiles []*harnessutil.TestFile,
	toBeCompiled []*harnessutil.TestFile,
	otherFiles []*harnessutil.TestFile,
	harnessSettings *harnessutil.HarnessOptions,
	opts baseline.Options,
) (string, string) {
	if !options.NoEmit.IsTrue() && !options.EmitDeclarationOnly.IsTrue() && result.JS.Size() == 0 && len(result.Diagnostics) == 0 {
		t.Fatal("Expected at least one js file to be emitted or at least one error to be created.")
	}

	// check js output
	var tsCode strings.Builder
	tsSources := core.Concatenate(otherFiles, toBeCompiled)
	tsCode.WriteString("//// [")
	tsCode.WriteString(header)
	tsCode.WriteString("] ////\r\n\r\n")

	for i, file := range tsSources {
		tsCode.WriteString("//// [")
		tsCode.WriteString(tspath.GetBaseFileName(file.UnitName))
		tsCode.WriteString("]\r\n")
		tsCode.WriteString(file.Content)
		if i < len(tsSources)-1 {
			tsCode.WriteString("\r\n")
		}
	}

	var jsCode strings.Builder
	for file := range result.JS.Values() {
		if jsCode.Len() > 0 && !strings.HasSuffix(jsCode.String(), "\n") {
			jsCode.WriteString("\r\n")
		}
		if len(result.Diagnostics) == 0 && strings.HasSuffix(file.UnitName, tspath.ExtensionJson) {
			fileParseResult := parser.ParseSourceFile(ast.SourceFileParseOptions{
				FileName: file.UnitName,
				Path:     tspath.Path(file.UnitName),
			}, file.Content, core.ScriptKindJSON)
			if len(fileParseResult.Diagnostics()) > 0 {
				jsCode.WriteString(GetErrorBaseline(t, []*harnessutil.TestFile{file}, diagnosticwriter.WrapASTDiagnostics(fileParseResult.Diagnostics()), diagnosticwriter.CompareASTDiagnostics, false /*pretty*/))
				continue
			}
		}
		jsCode.WriteString(fileOutput(file, harnessSettings))
	}

	if result.DTS.Size() > 0 {
		jsCode.WriteString("\r\n\r\n")
		for declFile := range result.DTS.Values() {
			jsCode.WriteString(fileOutput(declFile, harnessSettings))
		}
	}

	declFileContext := prepareDeclarationCompilationContext(
		toBeCompiled,
		otherFiles,
		result,
		harnessSettings,
		options,
		"", /*currentDirectory*/
	)
	declFileCompilationResult := compileDeclarationFiles(t, declFileContext, result.Symlinks)

	if declFileCompilationResult != nil && len(declFileCompilationResult.declResult.Diagnostics) > 0 {
		jsCode.WriteString("\r\n\r\n//// [DtsFileErrors]\r\n")
		jsCode.WriteString("\r\n\r\n")
		jsCode.WriteString(GetErrorBaseline(
			t,
			slices.Concat(tsConfigFiles, declFileCompilationResult.declInputFiles, declFileCompilationResult.declOtherFiles),
			diagnosticwriter.WrapASTDiagnostics(declFileCompilationResult.declResult.Diagnostics),
			diagnosticwriter.CompareASTDiagnostics,
			false, /*pretty*/
		))
	}

	if !options.NoCheck.IsTrue() && !options.NoEmit.IsTrue() {
		testConfig := make(map[string]string)
		testConfig["noCheck"] = "true"
		withoutChecking := result.Repeat(testConfig)
		compareResultFileSets := func(a *collections.OrderedMap[string, *harnessutil.TestFile], b *collections.OrderedMap[string, *harnessutil.TestFile]) {
			for key, doc := range a.Entries() {
				original := b.GetOrZero(key)
				if original == nil {
					jsCode.WriteString("\r\n\r\n!!!! File ")
					jsCode.WriteString(removeTestPathPrefixes(doc.UnitName, false /*retainTrailingDirectorySeparator*/))
					jsCode.WriteString(" missing from original emit, but present in noCheck emit\r\n")
					jsCode.WriteString(fileOutput(doc, harnessSettings))
				} else if original.Content != doc.Content {
					jsCode.WriteString("\r\n\r\n!!!! File ")
					jsCode.WriteString(removeTestPathPrefixes(doc.UnitName, false /*retainTrailingDirectorySeparator*/))
					jsCode.WriteString(" differs from original emit in noCheck emit\r\n")
					var fileName string
					if harnessSettings.FullEmitPaths {
						fileName = removeTestPathPrefixes(doc.UnitName, false /*retainTrailingDirectorySeparator*/)
					} else {
						fileName = tspath.GetBaseFileName(doc.UnitName)
					}
					jsCode.WriteString("//// [")
					jsCode.WriteString(fileName)
					jsCode.WriteString("]\r\n")
					expected := original.Content
					actual := doc.Content
					jsCode.WriteString(baseline.DiffText("Expected\tThe full check baseline", "Actual\twith noCheck set", expected, actual))
				}
			}
		}
		compareResultFileSets(&withoutChecking.DTS, &result.DTS)
		compareResultFileSets(&withoutChecking.JS, &result.JS)
	}

	if tspath.FileExtensionIsOneOf(baselinePath, []string{tspath.ExtensionTs, tspath.ExtensionTsx}) {
		baselinePath = tspath.ChangeExtension(baselinePath, tspath.ExtensionJs)
	}

	var actual string
	if jsCode.Len() > 0 {
		actual = tsCode.String() + "\r\n\r\n" + jsCode.String()
	} else {
		actual = baseline.NoContent
	}

	return baselinePath, actual
}

func XtscSourcemapBaseline(
	t *testing.T,
	baselinePath string,
	header string,
	options *core.CompilerOptions,
	result *harnessutil.CompilationResult,
	harnessSettings *harnessutil.HarnessOptions,
	opts baseline.Options,
) (string, string, bool) {
	declMaps := options.GetAreDeclarationMapsEnabled()
	if options.InlineSourceMap.IsTrue() {
		if result.Maps.Size() > 0 && !declMaps {
			t.Fatal("No sourcemap files should be generated if inlineSourceMaps was set.")
		}
		return "", "", false
	} else if options.SourceMap.IsTrue() || declMaps {
		expectedMapCount := 0
		if options.SourceMap.IsTrue() {
			expectedMapCount += result.GetNumberOfJSFiles( /*includeJSON*/ false)
		}
		if declMaps {
			expectedMapCount += result.GetNumberOfJSFiles( /*includeJSON*/ true)
		}
		if result.Maps.Size() != expectedMapCount {
			t.Fatal("Number of sourcemap files should be same as js files.")
		}

		var sourceMapCode string
		if options.NoEmitOnError.IsTrue() && len(result.Diagnostics) != 0 || result.Maps.Size() == 0 {
			sourceMapCode = baseline.NoContent
		} else {
			var sourceMapCodeBuilder strings.Builder
			for sourceMap := range result.Maps.Values() {
				if sourceMapCodeBuilder.Len() > 0 {
					sourceMapCodeBuilder.WriteString("\r\n")
				}
				sourceMapCodeBuilder.WriteString(fileOutput(sourceMap, harnessSettings))
				if !options.InlineSourceMap.IsTrue() {
					sourceMapCodeBuilder.WriteString(createSourceMapPreviewLink(sourceMap, result))
				}
			}
			sourceMapCode = sourceMapCodeBuilder.String()
		}

		if tspath.FileExtensionIsOneOf(baselinePath, []string{tspath.ExtensionTs, tspath.ExtensionTsx}) {
			baselinePath = tspath.ChangeExtension(baselinePath, tspath.ExtensionJs+".map")
		}

		return baselinePath, sourceMapCode, true
	}
	return "", "", false
}

func XtscSourcemapRecordBaseline(
	t *testing.T,
	baselinePath string,
	header string,
	options *core.CompilerOptions,
	result *harnessutil.CompilationResult,
	harnessSettings *harnessutil.HarnessOptions,
	opts baseline.Options,
) (string, string) {
	actual := baseline.NoContent
	if options.SourceMap.IsTrue() || options.InlineSourceMap.IsTrue() || options.DeclarationMap.IsTrue() {
		record := removeTestPathPrefixes(result.GetSourceMapRecord(), false /*retainTrailingDirectorySeparator*/)
		if !(options.NoEmitOnError.IsTrue() && len(result.Diagnostics) > 0) && len(record) > 0 {
			actual = record
		}
	}

	if tspath.FileExtensionIsOneOf(baselinePath, []string{tspath.ExtensionTs, tspath.ExtensionTsx}) {
		baselinePath = tspath.ChangeExtension(baselinePath, ".sourcemap.txt")
	}

	return baselinePath, actual
}
