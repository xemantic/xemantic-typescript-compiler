# goport CLI — tsgo's command line on the port ((TSGO.5))

`tsc` 7.0.2 runs on the JVM: `internal/execute` (the command line: `CommandLine`, `tscCompilation`,
`performCompilation`, `performIncrementalCompilation`, `--showConfig`) and the whole of
`internal/execute/tsc` (diagnostic reporters, `--help`/`--all`, `--init`, `--diagnostics`, emit and
error-summary reporting) are PORTED mechanically with the rest of the closure, together with tsgo's OS
file system `internal/vfs/osvfs`. The gate is a byte-for-byte differential of the CLI's output, exit status
and emitted files against the shipped tsgo binary (§ 3).

| what | where |
|---|---|
| the Kotlin entry | `com.xemantic.typescript.tsgo.cli.TsgoMainKt` (`-tsgo` jvmMain, `src/jvmMain/kotlin/cli/TsgoMain.kt`) |
| the facade | `TsgoCli.run(args, currentDirectory, out, libDirectory, isTerminal, terminalWidth, environment)` (commonMain) |
| the overlay | `goport-extract/overlay/execute/xtsc_cli.go` → `internal/execute/zz_xtsc_cli.go` |
| the recording | `scripts/tsgo-cli-oracle.py` → `build/goport/cli-oracle/` |
| the gate | `CliParityTest` (`TSGO_CLI=1`), pins `TsgoCliTest` |

## 1. What is ported, and how

The extractor's closure gains `execute/tsc` and `vfs/osvfs` (whole) and `execute` as a PARTIAL package
rooted at the overlay's `XtscCommandLine`. tsgo's `main` (`cmd/tsgo`) is package `main` and cannot be
imported; the overlay is its two ends, verbatim in shape:

| overlay | what it replaces |
|---|---|
| `xtscSystem` | `cmd/tsgo/sys.go`'s `osSys` (the `tsc.System`): writer, file system, default-library path, cwd, start time — the terminal test (`golang.org/x/term`) and `os.Getenv` are passed in |
| `XtscCommandLine(args, fs, libPath, cwd, writer, isTTY, width, getenv)` | `cmd/tsgo/main.go`'s `runMain` for the compiler: `execute.CommandLine(ctx, sys, args, nil)` → the exit status |

`TsgoCli.run` builds what `newSystem` builds — `bundled.WrapFS(osvfs.FS())`, `bundled.LibPath()` (or a given
lib directory) — and runs the command on a goroutine thread. `TsgoMain` is the process: arguments, stdout,
`user.dir`, `System.console()` for the terminal, `System.getenv`, `exitProcess(status)`.
`XTSC_TSGO_LIB_DIR=<dir>` reads the `lib.*.d.ts` of `<dir>` instead of the bundled ones, which is what the
shipped npm binary does with the directory next to its executable (and what makes lib file names in its
output — `--listFiles`, `--explainFiles` — agree byte for byte).

Partial stubs (signatures only, `partial.go`): `tscBuildCompilation` (`--build`: `internal/execute/build`'s
orchestrator is goroutines and channels) and `createWatcher` plus every `Watcher` method (`--watch`:
`internal/execute/watchmanager` + `internal/fswatch`, OS file notifications). `TsgoCli.run` answers such a
stub with `error: this tsc mode is not available in the port yet (…)` and exit status 5
(`ExitStatusNotImplemented`) instead of a stack trace. Stand-ins for their types:
`go/internal_watch/{WatchManager,Fswatch}.kt`.

Porter, override and shim changes (each general):

- **Named results with `defer`** (`osvfs.osFS.ReadFile`: `func (vfs *osFS) ReadFile(path string) (contents string,
  ok bool) { defer readSema.Acquire()(); … }`) lower like unnamed ones when no deferred call and no func literal
  of the body mentions a result: the defer can neither read a result before the return assigns it nor change
  it after; a recovered panic returns the result variables' current values (`withDefers` with the named
  variables as its recovered value). Otherwise still refused (`defer-named-results`). This also lowered
  `ls.getCallHierarchyItemName` / `resolveCallHierarchyDeclaration` (refused before).
- **A method of a named map/slice type named like a member of `GoMap`/`GoSlice` is SHADOWED** — it is an
  extension on the typealias and Kotlin resolves `m.get(k)` to `GoMap.get`. `tsoptions.CommandLineOptionNameMap.Get`
  (case-insensitive option lookup) silently lost its lower-case fallback, so `--showConfig` printed
  `"compilerOptions": {}` and `--incremental` wrote a `.tsbuildinfo` without its `"options"`. The porter now
  REFUSES the build for such a method (`Program.shadowedAliasMethods`, reported as a NAME COLLISION) and
  `renames.txt` renames it (`getOption`). Finding it needed a fix to the shim scanner too: a column-0 `) {` (a
  multi-line primary constructor, `class GoMap<K, V> private constructor(…`) ended the class, so `GoMap`'s and
  `GoSlice`'s members were never indexed; with them indexed, `vfstest` now copies a `fstest.MapFile` VALUE
  where Go does (`fCopy := *f`) instead of aliasing it.
- **Three overrides** (hash-pinned): `core.LimitedSemaphore` and `core.NewLimitedSemaphore` — a buffered
  `chan struct{}` used as a counting semaphore becomes `go.sync.CountingSemaphore` (osvfs bounds its concurrent
  syscalls with three); `diagnostics.loadLocaleData` — a GoMap carries its value kind but not its KEY type, so
  the json shim decoded `map[Key]string` with plain-string keys and every localized lookup missed (`--locale de`
  printed English); the override decodes `map[string]string` and re-keys it. Any other `json` decode into a map
  with a NAMED key type has the same gap (none is reached by the gates).
- **`compress/gzip`** (the localized message bundles are gzipped): a real `Reader` over the platform's inflater
  (JVM `java.util.zip`), where it was a stub.
- **Shims**: `os.OpenFile` (`*os.File`.`WriteString`/`Close`, the `O_*` flags), `MkdirAll`, `RemoveAll`, `Chtimes`,
  `Executable`, `Stat`, `IsNotExist`, `UserCacheDir`, `TempDir`; `filepath.Abs` (absolute paths);
  `runtime.MemStats`/`GC`/`ReadMemStats` (`--diagnostics`: "Memory allocs" is not observable on the JVM and reads
  0); `pprof.BeginProfiling` (`--pprofDir` writes no Go profile: a stated divergence); the `nativepath`
  stand-in (`Realpath` = the host's canonical path, `IsSymlinkOrReparsePoint` = lstat). `os.DirFS` now converts
  byte-string names to host paths and back (non-ASCII file names), and reports a symbolic link as
  `ModeSymlink`, as Go's `ReadDir` (lstat) does — `vfs/internal.Common` then stats it and records it in
  `Entries.Symlinks`.

Every new platform shim has a Kotlin/Native `actual` too ((TSGO.6)'s `linuxX64`, opt-in): POSIX
`open`/`write`/`mkdir`/`unlink`/`rmdir`/`utime`/`readlink(/proc/self/exe)`, `lstat` for the symlink kind,
`realpath(3)`, zlib's `inflate` for gzip, `GC.collect()` ("Memory used" reads 0 natively). They COMPILE
(`compileKotlinLinuxX64`); the CLI has not been run natively yet — the native executable is (TSGO.6)'s
`NativeCheckMain`, and a native `tsc` entry plus a native arm of this gate are (TSGO.6)/(TSGO.7) work.

## 2. Not ported / divergences

- `--build` (project references, `internal/execute/build`) and `--watch` — partial stubs, exit status 5.
- `--pprofDir` — no Go profile is written and its two "profile:" lines are not printed.
- `--diagnostics` / `--extendedDiagnostics` — times and memory are the JVM's (the rows are the same; the values
  are not comparable), "Memory allocs" is 0.
- `--lsp` / `--api` are tsgo's other two modes; the port serves them through `-lsp` (`TsgoLanguageService`) and
  `TsgoProject`, not through this entry.

## 3. The gate

```
scripts/tsgo-cli-oracle.py [--only NAME,…] [--workers N]     # records the tsgo binary → build/goport/cli-oracle
TSGO_CLI=1 TSGO_TEST_HEAP=4g ./gradlew :xemantic-typescript-compiler-tsgo:jvmTest --tests '*CliParityTest*'
```

The recorder runs `tools/tsgo-7.0.2/lib/tsc` per case in a fresh process (stdout piped, so not a terminal; a
fixed environment) and records stdout, the exit status, stderr (always empty: tsc writes everything to
stdout) and, for a case run in a scratch COPY of its project, the sha256 of every file the run created or
changed. `CliParityTest` runs the same case through `TsgoCli.run` in process — the same arguments, working
directory, environment and default-library directory (the binary's own `lib`) — in its own copy, and FAILS on
any difference in stdout bytes, exit status or the emitted file set/bytes. Copies live at the same depth
(`build/goport/cli-work/{oracle,kotlin}/<case>`), `.git`/`dist`/`node_modules` excluded and `node_modules`
LINKED to the original, so a path printed relative to the working directory reads the same in both; the copy's
absolute path is normalised to `{WORK}`.

Cases (106): the 8 tsc profiles (`build/bench/tsc-*`, the recorder REFUSES fewer than 8) × `--noEmit -p
<dir> --pretty false` and an emit arm (78-312 emitted files each), plus their tsconfig's own `"pretty": true`
(colours, code frames, the error summary) and a run from another working directory; the (P18.265) census
libraries (noEmit; an emit arm for mitt, superstruct, ky, zod); cronstrue (`…/cronstrue/src`, the TS5108
config-error short-circuit) and marked (both arms); 20 type-oracle projects (`build/goport/api-projects`, both
arms); and the command line: `--version`/`-v`, `--help`, `--all`, no tsconfig (version + help, exit 1), a missing
`-p` path (TS5058), a directory without tsconfig, an unknown flag, a bad flag value, `-p` with files,
an unparsable tsconfig, an unknown tsconfig option, `-p <file>`, the upward tsconfig search, `--pretty true` with
and without `NO_COLOR`, `--showConfig`, `--listFiles`, `--listFilesOnly`, `--explainFiles`, loose files (with and
without `--outDir`/`--declaration`, `--ignoreConfig`, files beside a tsconfig), a clean project with
declarations + source maps, `--listEmittedFiles`, `--incremental` (the `.tsbuildinfo`), `--noEmitOnError`,
`--init`, `--locale de`, a non-ASCII file name.

**Heap**: cases are run in one JVM; the default is 4 GB. `lib-typefest-noemit` needs ~8 GB (tsgo itself holds
~3.5 GB live there, `--extendedDiagnostics`: 10.6 M symbols, 5.1 M types — and the shipped binary is OOM-killed
on a shared box when it checks in parallel, so that case runs `--singleThreaded` on both sides); below that
heap it is reported as SKIPPED, never as equal.

Positive control: `TSGO_CLI_INJECT=<case>` appends to that case's recorded stdout, which must read red.

**Receipts** (2026-10-08): see the (TSGO.5) session note in PLAN-PHASE-5.md.

## 4. The GraalVM image ((TSGO.6))

```
./gradlew :xemantic-typescript-compiler-tsgo:nativeImage -PgraalvmHome=tools/graalvm-25   # → build/native/xtsc-tsgo
scripts/tsgo-cli-native-replay.py [--workers N]                                            # the 106 recorded cases
```

`TsgoMain` compiles closed-world with `--no-fallback` and NO reflection metadata (the port emits its own type
information for `reflect`/`json`; the one reflective call, `Console.isTerminal`, degrades through `runCatching`).
Oracle GraalVM 25.0.4 lives in the gitignored `tools/graalvm-25` (as Go in `tools/go`); the build takes ~2 min and
the image is ~57 MB. The gate replays the CLI recording through the image (the recorder's own `run_case`, copies at
`build/goport/cli-work/native`): **106 / 106 equal** in stdout, exit status and written files, type-fest included —
the in-process `CliParityTest` skips it below an 8 GB heap.

The task defaults to **G1 with `-R:MaxRAMPercentage=80`**, both measured (2026-10-09, `--noEmit` on tsc's compiler
profile, 65 rows byte-identical in every arm, 8-core box):

| | wall | RSS |
|---|---:|---:|
| tsgo 7.0.2 | 1.76 s | 0.39 GB |
| image, G1 (the default) | 2.9 s | 1.7 GB |
| image, serial GC | 8.7 s | 0.81 GB |
| port on the JVM, cold | 10.7 s | 2.5 GB |
| port on the JVM, warm (BenchMain) | 2.7 s | — |

The serial collector (`--gc=serial`, the only one in GraalVM Community Edition) is single-threaded and stops every
goroutine: its pauses summed ~6.4 s of the 8.7 s; `-Xmn1g -Xms2g` only reaches 6.9 s. G1's DEFAULT limit is 25%
of RAM (`-R:MaxRAMPercentage=25`; serial's is 80%), and type-fest needs ~9.7 GB (tsgo: 6 GB, 17 s; the image
28 s parallel at `-Xmx10g`) — at 25% the image ran out of memory inside goroutines and HUNG at 0% CPU, because the
error recurred inside `WaitGroup`'s own handler and the waiter was never signalled. A throwable escaping a
goroutine is now fatal to the process with Go's panic status 2 (`goSpawn`, both actuals; `GoroutineFatalTest`).
Community Edition builds with `-PnativeImageGc=serial`. Not yet tried: PGO (worth -21% on `-core`'s image).
