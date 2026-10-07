# goport diagnostics oracle — tsgo's harness diagnostics per conformance case

The (TSGO.2) gate (`docs/tsgo-port-plan.md` § 5) is a **diagnostics differential**: the Kotlin
port, compiling a conformance case exactly as tsgo's compiler test harness does, must produce the
same diagnostics as tsgo. This page is the reference side and the input materialization, so the
comparison is a diff of two files per case. It extends the (TSGO.1) AST oracle
(`docs/goport-oracle.md`): same tool, same build trick.

| what | where |
|---|---|
| case materializer | `scripts/tsgo-diag-cases.py` → `build/goport/diag-cases/<case>/<variation>/`, `…/manifest.json` |
| oracle driver | `scripts/tsgo-diag-oracle.py` → `build/goport/diag-oracle/<case>/<variation>.jsonl`, `…/manifest.json` |
| comparator (the Kotlin side's gate) | `scripts/tsgo-diag-compare.py ACTUAL_DIR` |
| CLI cross-check | `scripts/tsgo-diag-cli-crosscheck.py` |
| pin census (second leg) | `scripts/tsgo-diag-pin-census.py` → `build/goport/diag-pins/census.json` |
| in-process tool | `xemantic-typescript-compiler-goport/oracle-go/diags.go` (`materialize`, `diags`) + `oracle-go/overlay/` |

`<case>` is the case path relative to `typescript-repo/tests/cases` (`compiler/foo.ts`,
`conformance/es6/restParameters/x.ts`); `<variation>` is tsgo's configuration name
(`target=es2015,strict=true`) or `_` for a case without variations. Nothing under `build/` is
checked in; re-running both scripts reproduces everything in ~30 s.

## 1. The oracle IS tsgo's harness, not a re-implementation of it

tsgo's runner (`internal/testrunner/compiler_runner.go`, `test_case_parser.go`) and harness
(`internal/testutil/harnessutil/harnessutil.go`) are non-test Go files with unexported entry
points. `oracle-go/build.sh` compiles three small **overlay files** into those packages through
`go build -overlay` — they are ADDED to the package at build time (the script refuses if a file of
that name exists on disk), nothing is written into `typescript-go-repo`:

| overlay | exports |
|---|---|
| `overlay/testrunner/xtsc_export.go` | `XtscConfigurations` (the runner's `getCompilerFileBasedTest` + configuration naming), `XtscPrepare` (newCompilerTest's pre-compile block, **copied verbatim**), `XtscRunnerCompile` (the **unmodified** `newCompilerTest`), `XtscErrorBaseline` (`tsbaseline.GetErrorBaseline`), the runner's `skippedTests` |
| `overlay/harnessutil/xtsc_export.go` | `XtscDerive` (CompileFiles + CompileFilesEx up to program creation, **verbatim**), `XtscCompileCheckOnly` (the pre-emit half of `compileFilesWithHost`, with each phase kept apart), option lookups |
| `overlay/repo/xtsc_submodule.go` | `XTSC_TS_SUBMODULE` overrides the `_submodules/TypeScript` path (empty in this clone), so `/.lib` (`tests/lib`) resolves to `build/goport/ts-submodule`, extracted from `typescript-repo` at the same commit (`4d4f005c`) |

The harness's functions take a `*testing.T` (for `Skipf`/`Fatalf`). The tool runs outside `go
test` through `testing.MainStart` with a minimal `testDeps` (`testing.RunTests` cannot be used:
its CPU list is only parsed by `M.Run`); every case is its own `t.Run`, so a harness skip or fatal
ends that case only.

**Evidence.** With `-runner` every configuration is also compiled through the unmodified runner
path (pre-emit program, emitting program, TS-1 count check) and rendered exactly as tsgo's
`.errors.txt`: **6,318 / 6,318 configurations are byte-identical to tsgo's committed baseline**
under `typescript-go-repo/testdata/baselines/reference/submodule/<suite>/` (absent baseline ⇔
`<no content>` included). That is a statement about the whole pipeline — splitting, variations,
option resolution, file system, libs, diagnostic text, related information, ordering.

### Which list is the oracle

The committed baseline is the **post-emit** list. The oracle records the **pre-emit
(check-only)** list — `XtscCompileCheckOnly`, i.e. the first program `compileFilesWithHost`
builds, with `TraceResolution` off:

```
config   = GetConfigFileParsingDiagnostics()
program  = GetProgramDiagnostics()
syntactic= GetSyntacticDiagnostics(ctx, nil)
semantic = GetSemanticDiagnostics(ctx, nil)
global   = GetGlobalDiagnostics(ctx)
declaration = GetDeclarationDiagnostics(ctx, nil)   only if Options().GetEmitDeclarations()
suggestion  = GetSuggestionDiagnostics(ctx, nil)    only if harness option @captureSuggestions
list = compiler.SortAndDeduplicateDiagnostics(concat in that order)
```

The two lists agree in 6,287 configurations. The other 31 are all checker order dependence that
tsgo itself records as a bug: 23 carry the harness's `TS-1 Pre-emit (N) and post-emit (M)
diagnostic counts do not match!` (21 `submoduleTriaged`, 2 where pristine tsc's harness printed
the same TS-1, so no diff layer exists), and 8 have the same count but the emitting program
attaches different related information or a different code (6 × `commentsOnJSXExpressionsArePreserved`
TS7026 vs TS2875, `incorrectRecursiveMappedTypeConstraint` and
`typeParameterWithInvalidConstraintType` gaining a TS2751 related location) — all 8
`submoduleTriaged`. Per CLAUDE.md's 2026-09-21 rule a triaged tsgo defect is not a target, and
the check-only list is what the Kotlin side produces when it does not emit; it is the right
reference for (TSGO.2). The manifest records `runner.checkOnlyVsRunner` per configuration.

## 2. Case materialization (`scripts/tsgo-diag-cases.py`)

Cases: `tests/cases/compiler/*.ts{,x}` (flat) + `tests/cases/conformance/**/*.ts{,x}` — tsgo's
runner regex `\.tsx?$` — i.e. **6,573 cases** in this sparse clone (6,537 compiler, 36
conformance). Everything is materialized, including `.tsx` and conformance categories the Kotlin
corpus has not adopted; `inCorpus` marks the subset the generated Kotlin corpus runs (`.ts` only,
`compiler/` + `build.gradle.kts` `conformanceCategories`, parsed from the build file).

Configurations: tsgo's `GetFileBasedTestConfigurations` over the runner's `compilerVaryBy` set
(Cartesian product, cap 25), named as the runner names them. **7,280 configurations.**

Skips, in the manifest with a reason, all decided by tsgo's own code except the last:

| skip | count | decided by |
|---|---|---|
| `skippedTests` (whole file) | 34 files | the runner's list, by basename |
| `SkipUnsupportedCompilerOptions` on the RESOLVED options (embedded tsconfig, then directives) | 962 configurations | the real function, called on the derived options |
| corpus-only `tsconfigInTestUsesRemovedFeature` (build.gradle.kts, (LEGACY.1)(g)) | 52 cases flagged (`corpusSkip`) | Python port of the Kotlin predicate; **all 52 are also skipped by tsgo's own rule**, so it removes nothing extra (still materialized unless `--corpus-skips`) |

**6,318 configurations are materialized** (6,176 `inCorpus`); 0 fatal.

### The project directory

```
<case>/<variation>/case.json        what to compile (below)
<case>/<variation>/vfs/<path>       every harness file at its virtual absolute path (`/` = vfs/)
<case>/<variation>/tsconfig.json    for the CLI cross-check ONLY; the oracle never reads it
```

`case.json` (schema 1):

| field | meaning |
|---|---|
| `case`, `caseSha256`, `suite`, `variation`, `testName`, `configuredName` | identity; `caseSha256` is of the text the harness read; `configuredName` is the baseline stem (`foo(target=es2015).ts`) |
| `configuration` | the harness's `TestConfiguration` map: lower-case directive name → value as tsgo split it (non-varying options included; `filename` appears because the runner's `extractCompilerSettings` keeps it) |
| `currentDirectory` | `/.src` unless `@currentDirectory` |
| `files[]` | `{path, role, sha256}`; `role` = `root` (toBeCompiled), `other` (otherFiles), `tsconfig` (the embedded `tsconfig.json`/`jsconfig.json`, **not mounted in the program's FS** — the harness parses it separately), `testlib` (`/.lib/**`, mounted only when a root mentions `/.lib/` or `@libFiles` is set). A path written twice (a case may repeat an `@filename`, e.g. `augmentExportEquals2`) holds the last content, as the harness's FS map does |
| `symlinks` | `{src: target}`, both absolute (`@link`, `@symlink`); also created as real relative symlinks under `vfs/` |
| `rootFiles` | the root names `NewProgram` gets, in order (inputs minus `.json`/`.tsbuildinfo`, plus `/.lib/<libFile>` for `@libFiles`) |
| `tsconfig` | the embedded config's path or null |
| `compilerOptions` | the FINAL `core.CompilerOptions` (tsconfig options cloned, harness defaults `newLine: CRLF`, `skipDefaultLibCheck: true`, `noErrorTruncation: true`, directives applied by `SetOptionsFromTestConfig`, path options absolutized against `currentDirectory`) in **tsgo's own JSON form** (`internal/json`: enums as numbers, Tristate as booleans). The materializer asserts it round-trips through `json.Unmarshal` to a `reflect.DeepEqual` value |
| `harnessOptions` | the harness's `HarnessOptions` struct (Go field names) |
| `hasNonDtsFiles`, `skippedEmit` | the runner's own flags (emit-only, for (TSGO.3)) |

`tsgo-oracle diags` re-derives everything from the case source and **refuses** a project
directory whose files, roles or hashes no longer match what it is about to compile (`case
source changed since materialize`, `materialized file … modified`), so the directory can never
silently drift from the oracle's input.

## 3. The output format (exact)

One file per configuration, `<case>/<variation>.jsonl`. One JSON object per diagnostic, one per
line, in the harness's order (`SortAndDeduplicateDiagnostics`). **An empty file means no
diagnostics**; a missing file is never "no diagnostics". Keys, in serialization order:

| key | type | value |
|---|---|---|
| `file` | string \| null | `Diagnostic.File().FileName()`: the virtual absolute path (`/.src/a.ts`, `/node_modules/x/index.d.ts`, `/.lib/react.d.ts`) or a bundled lib (`bundled:///libs/lib.es5.d.ts`); null for a diagnostic without a file (options, globals) |
| `start`, `length` | int | `Diagnostic.Pos()`, `Len()`: **UTF-8 byte offsets** into the file text as the program's `SourceFile` holds it (after the vfs layer's BOM strip / UTF-16 decode). These are Go's native positions, i.e. what the port's byte strings hold (`docs/goport-design.md`) |
| `code` | int | `Diagnostic.Code()` |
| `category` | string | `Category().Name()`: `error`, `warning`, `suggestion`, `message` |
| `text` | string | `diagnosticwriter.FlattenDiagnosticMessage(d, "\n", locale.Default)`: the localized message, then for each chain element depth-first `"\n" + "  " × level + message` |
| `related` | array | `RelatedInformation()` in order (after `compactAndMergeRelatedInfos`), each `{file, start, length, code, category, text}` with the same rules; `[]` when none |
| `phase` | string | `config` \| `program` \| `syntactic` \| `semantic` \| `global` \| `declaration` \| `suggestion`: the first program call (in the order of § 1) whose result contained the diagnostic; a diagnostic `compactAndMergeRelatedInfos` cloned is attributed through `EqualDiagnosticsNoRelatedInfo` |
| `skippedOnNoEmit` | bool | `Diagnostic.SkippedOnNoEmit()` (6 occurrences in the corpus; a `noEmit` program drops them) |

Serialization (informative — the comparison is on parsed values): Go `encoding/json`,
`SetEscapeHTML(false)`, compact, `\n` after each object; invalid UTF-8 in a string becomes one
U+FFFD per invalid byte (the same mapping as `GoString.toUtf16`).

### Comparison rule

`scripts/tsgo-diag-compare.py ACTUAL_DIR` — `ACTUAL_DIR` mirrors the oracle layout. A
configuration is **equal** when both files hold the same **sequence** of parsed JSON values
(order matters: it is the ported `SortAndDeduplicateDiagnostics`' order; every key compared,
including `related` and `phase`). Missing actual files are counted as `missing`. Bring-up knobs:
`--exclude-phase declaration` (drop that phase on both sides until the declaration transformer
arrives in (TSGO.3)), `--ignore-phase`, `--only-corpus`. The script refuses an incomplete oracle
manifest and exits non-zero unless every configuration is equal. Self-test: the oracle against
itself is 6,318 equal; one moved offset and one deleted file read `equal 6316, missing 1, differ 1`.

Each manifest entry also carries `layer` — the tsgo diff layer of the configuration's
`.errors.txt` (`submoduleTriaged` 31, `submoduleAccepted` 225, `submodule` 29, none 6,033): a
Kotlin/oracle disagreement on a `submoduleTriaged` configuration is evidence about tsgo, not the
port (the 2026-09-21 directive).

## 4. Kotlin-side harness contract

The ported compiler is driven per configuration, from the project directory, so that the only
variable left is the engine. **Preferred route: port the harness too.** The Go functions the
oracle calls are ordinary non-test code that `goport` can lower like the rest of tsgo:
`testrunner.makeUnitsFromTest` / `ParseTestFilesAndSymlinks` / `extractCompilerSettings`,
`harnessutil.GetFileBasedTestConfigurations`, `SetOptionsFromTestConfig` (+ `getOptionValue`,
the harness option table), `SkipUnsupportedCompilerOptions`, and the bodies of `XtscPrepare` /
`XtscDerive` / `XtscCompileCheckOnly` (they need a `testing.T` shim with `Skipf`/`Fatalf`). Then
the Kotlin harness reads the RAW case (`typescript-repo/tests/cases/<case>`) and the variation
name, and `case.json` is only a cross-check of its derived state (`rootFiles`, `files`,
`compilerOptions` must match field for field — that comparison is a cheap first gate before any
diagnostic is compared).

**Fallback route (no harness port): drive the program from `case.json`.**

1. **File system.** An in-memory, case-sensitive FS (`vfstest.FromMap` semantics) holding every
   `files[]` entry whose role is `root`, `other` or `testlib`, at its `path`, with the bytes of
   `vfs/<path>`; plus every `symlinks` entry as a symlink. **Not** the `tsconfig` file. Wrap it in
   the ported `bundled.WrapFS` (libs under `bundled:///libs/`, tsgo 7.0.2's own `lib.*.d.ts`).
   No real directory is visible.
2. **Options.** Decode `compilerOptions` as `core.CompilerOptions` (it is tsgo's own JSON form),
   then — as `compileFilesWithHost` does for its pre-emit program — clone it with
   `TraceResolution = false`.
3. **Config file.** If `tsconfig` is not null, parse its text as the runner does: a JSON
   `SourceFile` (`parser.ParseSourceFile` with `ScriptKindJSON`, `FileName` = the path) and
   `tsoptions.ParseJsonSourceFileConfigFileContent(sourceFile, host, dirOf(path), nil, nil, path,
   nil, nil, nil)` where `host` is `tsoptionstest.NewVFSParseConfigHost` over **all** case files
   (including the tsconfig), `currentDirectory`, case-sensitive. Keep only its `ConfigFile` and
   `Errors`; its options are already merged into `compilerOptions`.
4. **Program.** `ParsedCommandLine{ParsedConfig{CompilerOptions, FileNames: rootFiles},
   ConfigFile, Errors}`; host `compiler.NewCompilerHost(currentDirectory, fs, bundled.LibPath(),
   nil, <no-op trace>)`; `compiler.NewProgram(ProgramOptions{Config, Host, SingleThreaded:
   true})`. (If `compilerOptions.incremental` is true the harness wraps it in
   `incremental.NewProgram` with a build-info reader over the same FS — 0 such configurations in
   the current corpus produce diagnostics from that path; add it when one does.)
5. **Diagnostics.** The phases of § 1 in that order (declaration only when
   `GetEmitDeclarations()`, suggestion only when `harnessOptions.CaptureSuggestions`), each
   diagnostic tagged with the first phase that produced it, `SortAndDeduplicateDiagnostics`,
   then one line per diagnostic as in § 3.
6. **Run.** Iterate `build/goport/diag-oracle/manifest.json` `entries`; read
   `build/goport/diag-cases/<case>/<variation>/`; write `build/goport/diag-kotlin/<case>/<variation>.jsonl`;
   then `scripts/tsgo-diag-compare.py build/goport/diag-kotlin`. **`DiagParityTest` applies the same rule
   itself and FAILS** on any unequal or crashed (missing) configuration, printing the first ten
   differences — the differential is a gate, not only a measurement. Positive control:
   `TSGO_DIAG_INJECT=<case>/<variation>` appends one row to that configuration's actual result before
   grading (measured: `1 of 1 configurations not equal`, red).

Never run the Kotlin side against a configuration the oracle manifest does not list: skips are
decided once, by tsgo, in the materializer.

## 5. Cross-checks and disagreement classes

**(a) Committed tsgo baselines** — 6,318 / 6,318 byte-identical (§ 1). This also covers the four
layers of `docs/tsgo-baselines.md`: the full file under `submodule/` is tsgo's output whatever
layer its `.diff` lives in, and every one matches.

**(b) Determinism** — 100 configurations re-run in a fresh process in reverse order, no runner:
0 mismatches (the harness program is `SingleThreaded`).

**(c) The tsgo CLI** — `scripts/tsgo-diag-cli-crosscheck.py` runs
`tools/tsgo-7.0.2/lib/tsc -p <dir>/tsconfig.json --noEmit --pretty false` over materialized
directories and compares (file, line, UTF-16 column, code, flattened text). Over ALL 6,318
configurations (3,196 with diagnostics; `--pretty false` prints no related information, so that is
not compared here):

| comparison | agree | classes of the rest |
|---|---|---|
| raw harness list vs CLI (`--no-gate`) | 6,019 | unexplained-by-rule 146, path-model 103, noemit-option 30, option-location 20 |
| CLI-equivalent list vs CLI (default) | **6,171** | path-model 94, option-location 26, noemit-option 12, unexplained-by-rule 15 |

Rows: 12,807 gated oracle rows / 13,205 CLI rows; 12,600 rows sit in agreeing configurations. The
gate simulation takes the unexplained class from 146 configurations to 15 (and agreement from 6,019 to 6,171).

The gate simulation applies the two mechanisms that separate the harness from the CLI, both
taken from tsgo's source, to the oracle's phase-tagged list: **(1) the CLI's diagnostic gate**,
`compiler.GetDiagnosticsOfAnyProgram`: config diagnostics, then syntactic; only without
syntactic errors program + global; only if those are empty too, semantic (then global again),
and declaration only under `noEmit` with declaration emit. The harness never gates — it asks every
phase. **(2) `--noEmit`**, which drops `SkippedOnNoEmit` diagnostics. The remaining classes:

- **path-model (94)** — the case has files outside the current directory, `/.lib`, or symlinks.
  Under the CLI an absolute virtual path (`/// <reference path="/.lib/react16.d.ts"/>`,
  `jsxImportSource: "/jsx"`, `/node_modules/@types`) resolves against the real `/`, and
  `typeRoots`/`types` resolve against the generated tsconfig's directory rather than the
  harness's `/.src` walk. The materialized `vfs/` cannot fix this without a chroot; it is a
  property of the CLI run, not of the oracle.
- **option-location (26)** — identical option diagnostics; the harness has no config file for
  `// @option` directives (`file: null`), the CLI reports them at the generated tsconfig.json.
- **noemit-option (12)** — diagnostics that exist only when emitting: TS5055/TS5056 (cannot write
  file), TS2209 (project root needed for an export-map self-name, computed from outDir/declarationDir).
- **the 15 the rules do not name**, each read by hand: config-file presence (TS5074 `--incremental`
  needs a config file; the "file is in the program because" chain says `Root file specified for
  compilation` in the harness and `Part of 'files' list in tsconfig.json` under the CLI, 3; TS5090
  reported per `paths` entry in the embedded config by the harness vs once at the `extends` by the
  CLI, 1); the CLI's `GetDiagnosticsOfAnyProgram` asking for global diagnostics BEFORE checking
  (`awaitedTypeNoLib`: TS2318 `Awaited` is only discovered during checking, 1); `--noEmit` also
  enabling declaration diagnostics the harness's non-noEmit program does not compute
  (`declarationEmitInvalidExport` TS4081, 1); and the outDir/rootDir-dependent package self-name
  resolution under a config file (`nodeNextPackageSelfName*`, `nodeNextPackageImportMapRootDir`,
  TS2307, 5; `referenceTypesPreferedToPathIfPossible` TS2580, 1; the TS5055 + chain pairs, 2).

**No class is a checker disagreement**: every residual is explained by the CLI's gate, its
config-file/option plumbing, `--noEmit`, or the real-root path model. The harness answer is the
oracle; the CLI is a second witness that agrees wherever it compiles the same program.

## 6. Run statistics (2026-10-07)

| step | result | wall |
|---|---|---|
| `tsgo-diag-cases.py` | 6,573 cases → 7,280 configurations → 6,318 materialized, 0 fatal; 212 MB | ~3 s (4 workers) |
| `tsgo-diag-oracle.py` (with `-runner`) | 6,318 ok, 3,200 with diagnostics, 13,698 diagnostics (phases before dedup: semantic 12,157, syntactic 1,114, declaration 209, program 82, global 68, suggestion 36, config 32); 49 MB of JSONL | ~25 s (4 workers, ~4 cores, 1.3 GB peak RSS) |
| re-run, nothing changed | fully cached | ~2 s |
| `tsgo-diag-cli-crosscheck.py` (all 6,318) | § 5(c) | ~140 s |

Caching: the materializer keys on (case bytes, tool sha256, layout version); the driver on
(sha256 of `case.json`, tool sha256, runner flag) plus the output's own hash. A rebuilt
`tsgo-oracle` invalidates both (the driver refuses a case manifest built by a different tool).
Both scripts mark their manifest `"complete": false` and exit non-zero on any non-ok
configuration, dead worker, missing status line or determinism mismatch; consumers refuse it.

## 7. The second leg: the hand-written pins

`scripts/tsgo-diag-pin-census.py` scans every `@Test fun` in `xemantic-typescript-compiler-*/src/*Test*`
(heuristically: the region from the `fun` to the next `@Test`, following same-file helpers three
calls deep) and classifies it by entry point:

| entry point | tests | expressible as (source, options, expected)? |
|---|---|---|
| `diagnose(source, directives, fileName)` (direct 6,305 / via a helper 4,473) | 10,778 | **yes** — it compiles `directives + "\n" + trimIndent(source)` through `TypeScriptCompiler.compile(String)`, whose `parseMultiFileSource` reads the same `// @option` / `// @Filename` format the conformance harness does |
| `TypeScriptCompiler().compile(text, …)` (80 / 407) | 487 | yes for diagnostics (some of these assert emitted JS instead, which is (TSGO.3)) |
| `diagnoseVerbatim` | 4 | yes, single file + a `CompilerOptions` object (line-terminator pins) |
| `ProjectCompiler` / `Project` / `Vfs` (335 / 837) | 1,172 | as a project directory — needs the ported program/module resolution through a real tsconfig; later |
| `Checker(...)` / `Binder(...)` direct (39 / 182) | 221 | no — symbol-identity and internal-API pins |
| none of the above | 1,642 | no — parser/scanner/emitter/KIR/externals/LSP/perf |

**11,269 tests in 976 classes are expressible** (777 multi-file). Assertion shapes among them
(several per test): exact list/size 3,000, presence 2,899, **silence 2,574**, message text 2,007,
position 624. (The plan's "~2,800" is the silence-asserting subset; the census counts every pin.)

**Design.** Do not port the expectations. The pins already ARE (source, options, expected)
triples; what differs is the engine behind `diagnose`. So the second leg is an **engine switch in
`CompilerTestSupport.diagnose`/`TypeScriptCompiler.compile(String)`**: with `XTSC_ENGINE=tsgo`
(an environment variable — Gradle does not forward `-D` to the test JVM, CLAUDE.md) the same text
is handed to the Kotlin port's harness (§ 4, preferred route: the ported `makeUnitsFromTest` reads
exactly this format), and the resulting diagnostics are mapped into `-core`'s `Diagnostic`
(`message` = the top message, `messageChain` = the chain lines, `code`, `category`, `fileName`,
`start`/`length` converted from UTF-8 bytes to the UTF-16 offsets `-core` reports, `line`/
`character` from the ported line map, `relatedInformation`). Run the module's suite twice, once
per engine, and diff the two failure sets: a pin `-tsgo` fails and `-core` passes is a porting bug
or a wrong pin (check it against `tools/tsgo-7.0.2/lib/tsc`); the reverse is `-core`'s defect.
Three adjustments are known in advance: `-core`'s harness defaults differ from tsgo's (`strict`
is not a tsgo harness default — `diagnose` passes `// @strict: true` explicitly, fine — but the
`-core` corpus file names are flat where tsgo's live under `/.src`; map `fileName` back by
stripping the current directory); a pin over a DISPLAY string inherits every display difference
between the engines; and the 4,473 helper-wrapped pins must be routed through the same switch,
which they are automatically because the helpers call `diagnose`. A cheaper first step, needing
no port at all: a record mode (`XTSC_PIN_DUMP=<dir>`) in `diagnose` that writes each composed
text as a case file, so the pins can be run through THIS oracle (`tsgo-oracle materialize/diags`
accept any case root) and `-core`'s pin expectations checked against tsgo directly.

## 7a. What threatens it

- **The overlay copies** (`XtscPrepare`, `XtscDerive`, `XtscCompileCheckOnly`) are verbatim copies
  of tag `typescript/v7.0.2`; on a tsgo bump re-copy them. The `-runner` baseline check is the
  detector: a stale copy shows up as `checkOnlyVsRunner: differ` outside `submoduleTriaged`.
- **`/.lib` comes from `typescript-repo`**, not tsgo's submodule; the two are the same commit today
  (the corpus re-pin (LEGACY.0a)). If they diverge, re-extract from the submodule's commit.
- **Positions are UTF-8 bytes.** A Kotlin harness that hands the program UTF-16 `String`s instead of
  the port's byte strings will be off on every non-ASCII file.
- **Absolute virtual paths are part of the output** (`/.src/…`, `bundled:///libs/…`); a Kotlin
  harness that roots its FS elsewhere fails every line.
