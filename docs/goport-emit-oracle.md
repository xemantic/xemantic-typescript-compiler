# goport emit oracle — tsgo's compiler-runner emit baselines per configuration

The (TSGO.3-a) gate (`docs/tsgo-port-plan.md` § 5, "emit parity") is the emit twin of the
diagnostics differential (`docs/goport-diag-oracle.md`): the Kotlin port, compiling a case exactly as
tsgo's compiler runner does, must render the same EMIT baselines as tsgo. Same case population, same
build trick, same discipline: the oracle IS tsgo's runner (verified byte for byte against tsgo's
committed baselines), and the port runs the same overlay code, mechanically lowered.

| what | where |
|---|---|
| cases (shared with the diag oracle) | `scripts/tsgo-diag-cases.py` → `build/goport/diag-cases/…`, raw text under `build/goport/diag-src` |
| oracle driver | `scripts/tsgo-emit-oracle.py` → `build/goport/emit-oracle/<case>/<variation>.emit`, `…/manifest.json` |
| in-process tool | `oracle-go/emit.go` (`tsgo-oracle emit`) + `oracle-go/overlay/{testrunner,tsbaseline}/xtsc_export.go` |
| the Kotlin side (the gate) | `EmitParityTest` (-tsgo jvmTest, `TSGO_EMIT=1`) → `build/goport/emit-kotlin/…` |
| project receipt | `scripts/tsgo-emit-project.sh` (`EmitProjectMain` vs the tsgo 7.0.2 binary, `diff -r`) |

## 1. What is compared: the runner's three emit baselines

`runSingleConfigTest` (`internal/testrunner/compiler_runner.go`, tag `typescript/v7.0.2`) runs, after
`newCompilerTest` (`harnessutil.CompileFiles`: pre-emit program, EMITTING program, the TS-1 pre/post
count check), these verifications that baseline emit output:

| subtest | baseline | content |
|---|---|---|
| `output` (`verifyJavaScriptOutput`) | `<configured>.js` | the inputs, every emitted `.js`, every `.d.ts` (`result.DTS`), a `DtsFileErrors` section when the declarations do not re-compile cleanly (a SECOND compilation, `compileDeclarationFiles`), and the noCheck comparison (`result.Repeat(noCheck: true)`, a THIRD compilation, printing any differing file with a patience diff) |
| `sourcemap` (`verifySourceMapOutput`) | `<configured>.js.map` | every source map plus its preview link (base64 of map + output + inputs); only with `sourceMap`/`declarationMap` |
| `sourcemap record` (`verifySourceMapRecord`) | `<configured>.sourcemap.txt` | the decoded mapping record (`harnessutil` `sourcemap_recorder.go`), `<no content>` without maps |

The `.js` subtest is not run for a case with only `.d.ts` units (`hasNonDtsFiles`) and is skipped for
`skippedEmitTests` (8 files: parallel-emit nondeterminism in tsgo's own harness).

The overlay `oracle-go/overlay/tsbaseline/xtsc_export.go` holds VERBATIM copies of `DoJSEmitBaseline`,
`DoSourcemapBaseline` and `DoSourcemapRecordBaseline` whose only change is that the final
`baseline.Run(t, path, actual, opts)` RETURNS `(path, actual)` instead of writing and comparing a file.
`testrunner.XtscEmitBaselines` (in `oracle-go/overlay/testrunner/xtsc_export.go`) is the unmodified
`newCompilerTest` followed by the three verifications, each in its own `t.Run` as the runner runs them; a
verification's panic is recovered (the runner's `RecoverAndFail` turns it into `t.Fatal`) and ends that
artifact as `failed`. `header` — the `tests/cases/<suite>/<file>` line the `.js` baseline starts with —
is passed in (the runner derives it from `repo.TestDataPath()`; for both submodule and `local/` cases it
is `tests/cases/` + the case path).

### The frame

One file per configuration, byte-exact (Go strings are bytes), written identically by both sides:

```
== <kind>\t<status>\t<baseline file>\t<byte length>\n<content bytes>\n      (once per artifact)
```

`status` is `ok` (content = what `baseline.Run` would get; `<no content>` means the file must not
exist), `absent` (the verification never reaches `baseline.Run`: no maps, or a `.d.ts`-only case),
`skipped`, or `failed` (content omitted — a panic message is not comparable across languages).

## 2. The oracle (`scripts/tsgo-emit-oracle.py`)

Runs `build/goport/bin/tsgo-oracle emit` (built by `oracle-go/build.sh`) over every configuration the
diag materializer produced, in worker batches, with the same caching, completeness and determinism probe
as the diag oracle (a sample re-run in a fresh process, reverse order). `-baselines` compares every `ok`
artifact with tsgo's COMMITTED baseline (`testdata/baselines/reference/submodule/<suite>/` for a submodule
case, `reference/<suite>/` for a `local/` case; `<no content>` ⇔ the file is absent).

**Evidence (2026-10-08):** 13,127 configurations, all `ok`, 33 s with 5 workers; determinism 100 / 100.

| artifact | ok | absent | skipped | committed baseline |
|---|---|---|---|---|
| `output` (.js) | 13,058 | 61 | 8 | **13,058 / 13,058 equal** |
| `sourcemap` (.js.map) | 146 | 12,981 | 0 | **146 / 146 equal** |
| `sourcemap record` (.sourcemap.txt) | 13,127 | 0 | 0 | **13,127 / 13,127 equal** |

So the overlay copies reproduce tsgo's committed emit baselines exactly, including the `submoduleTriaged`
layers (we port tsgo, so a triaged tsgo bug is part of the target, as in the diag oracle). Content that
the population exercises: `DtsFileErrors` 24 configurations, the noCheck "missing from original emit"
section 2, source-map preview links 140; the patience diff (noCheck "differs") **0** — that path is
ported mechanically (`gen/thirdparty/github.com/peter-evans/patience`) but no configuration reaches it.

## 3. The Kotlin side (`EmitParityTest`)

For every manifest entry: read the RAW case from `build/goport/diag-src`, enumerate its configurations
with the ported runner, run `harness.emitBaselines` (the generated `testrunner.xtscEmitBaselines`),
render `harness.emitFrame`, write `build/goport/emit-kotlin/<case>/<variation>.emit`, then compare every
frame byte for byte with the oracle's. FAILS on any differing, crashed (missing) or timed-out
configuration. `TSGO_EMIT_THREADS` (default 4) runs configurations concurrently — the ported harness's
shared caches are Go's concurrent ones. Positive control: `TSGO_EMIT_INJECT=<case>/<variation>` appends a
byte to one actual frame.

**Receipt (2026-10-08): 13,127 / 13,127 configurations equal, 0 differ, 0 missing (89 s, 4 threads); the
inject control reads red.**

```bash
python3 scripts/tsgo-diag-cases.py && python3 scripts/tsgo-emit-oracle.py
TSGO_EMIT=1 TSGO_TEST_HEAP=3g ./gradlew :xemantic-typescript-compiler-tsgo:jvmTest --tests '*EmitParityTest*'
```

## 4. What the port needed

- **Partial packages**: `testutil/tsbaseline` (roots: the three `Xtsc*` copies) and `testutil/baseline`
  (`NoContent`), plus `XtscEmitBaselines` in `testrunner`; `diagnosticwriter` whole.
- **A third-party package, mechanically**: `github.com/peter-evans/patience` (MIT, tsgo's baseline diff).
  The porter now accepts a non-tsgo package listed in `THIRD_PARTY` (Main.kt: version, copyright,
  licence file — `LICENSE-patience` in -tsgo), emits it under `gen/thirdparty/<module path>` with its own
  header, and refuses any other.
- **Shims**: `testing.T.Run` (a subtest: skip/fatal end the subtest only, a failure fails the parent),
  `net/url.QueryEscape`/`QueryUnescape`, `gotest.tools/v3/assert.Check` + `assert/cmp.Equal`.
- **Lowering rules** (found by compiling the new closure, none by a per-case patch):
  1. a PROMOTED nil-safe method of another package (an extension on `T?`, e.g. `ast.Diagnostic.Category`
     through `diagnosticwriter.ASTDiagnostic`'s embedded pointer) is imported where the delegate is emitted;
  2. a type parameter in scope SHADOWS a same-named imported class (`GetErrorBaseline[T …](t *testing.T)`):
     the class is spelled fully qualified.
- **Port defects the gate found** (first full run 12,964 equal / 153 differ / 10 crashed), all fixed in the runtime,
  shims or lowering:
  1. `fmt` printed a generated named-basic value class (`GoBasicValue`) as `%!d(T=…)`; it now formats the underlying
     value. 147 source-map records AND 6 `.js` baselines: the transformers name temporaries with `fmt.Sprintf("_%d", tempFlags)`.
  2. The json shim could not marshal a named basic type (`incremental.BuildInfoFileId`): every `incremental` build panicked.
  3. `slices.Concat()` over zero slices had no element kind: the shim now takes the porter's `GoElem<S>` dictionary.
  4. A `[]byte` inside an `any` was indistinguishable from `[]int`: `GoElem.BYTE` (emitted for `uint8`/`byte` elements)
     lets `fmt`'s `%s`/`%q`/`%x` print its bytes. (The json shim still marshals one as a number array, not base64; nothing
     in the closure does it.)
- **Pinned refusal**: `baseline.Run` / `RunAgainstSubmodule` (they write files on disk and record through
  `testing.TB`); the gate never calls them.

## 5. The project receipt (`scripts/tsgo-emit-project.sh`)

`EmitProjectMain <tsconfig.json> <outDir>` is `tsc -p <project> --outDir <outDir>` through the ported
compiler (tsconfig parsed with `outDir` as a command-line option, `compiler.Program`, `Emit`, outputs written
through the host file system). The script runs it and the tsgo 7.0.2 binary on the same project and
`diff -r`s the trees, refusing an empty tree. Default project: tsc's own 78 sources (`build/bench/tsc-project-*`).

**Receipt (2026-10-08):** tsgo 7.0.2 and the port each emit 78 files (65 diagnostics each); `diff -r` IDENTICAL,
8,841,387 bytes.
