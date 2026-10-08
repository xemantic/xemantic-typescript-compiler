# `-core` sunset — a measured report for the owner's decision ((TSGO.4-d))

Written 2026-10-08 on `main` at `a53970cd3` (the -tsgo gates re-verified on `19a3779c5` the same day).
Nothing in `-core` was deleted, disabled or edited, and no module's dependencies were changed: the
deletion is an **owner decision** (`PLAN-PHASE-5.md`, (TSGO.4-d), `BLOCKED-PENDING-USER`). Every number
below says where it came from and whether it is a single draw.

## 0. The answer in five lines

- **Parity is settled.** The port equals tsgo 7.0.2 on every gate it has (≈650k compared items, 0 differ),
  and on the 18 real projects measured here it reports **616 / 616** rows identical (file, line, code,
  message) where `-core` reports **585**, of which **153 are false positives and 184 are missing**.
- **Cost favours the port in time and `-core` in memory.** Warm on tsc's own sources: check **2.7 s vs
  7.6 s**, check+emit **4.0 s vs 8.9 s**; but process RSS **~3.5 GB vs ~2.1 GB** and the port is
  GC-bound at a 3 GB heap.
- **What still needs `-core` is plumbing, not checking**: the CLI / daemon / native image / `-project`
  (published, IntelliJ plugin) and KIR's lowering, which still walks `-core`'s AST.
- **The one unmeasured blocker is native**: `-tsgo` declares only a JVM target; Kotlin/Native and the
  GraalVM image of the port have never been built.
- **Recommendation: retire `-core`, in five staged steps (§ 6), deleting it only after the CLI, the
  native image and KIR's lowering have moved.** The owner decides (§ 7).

## 1. Dependency census — who still takes what from `-core`

Read from every `build.gradle.kts` and from `import com.xemantic.typescript.compiler.<Type>` (the
`-core` package) in each module's sources.

| module | depends on `-core` | what it takes | status | cost to move onto `-tsgo` |
|---|---|---|---|---|
| `-api` | no | — | — | none |
| `-client` | no (`-api` only; the native thin client) | — | — | none |
| `-externals` | **no** (since (TSGO.4-b)) | — | done | done |
| `-lsp` | **no** (since (TSGO.4-a); `api(-tsgo)`) | — | done | done (open: auto-imports, rename, incremental updates) |
| `-goport` | no | — | — | none (dev tool) |
| `-tsgo` | jvmTest only | `ParseBenchMain` compares parsers | test scope | delete the comparison arm |
| `-kir` | **yes**, `api(-core)` | the lowering walks `-core`'s AST and reads its `Type`/`Symbol`/`Signature` value classes (16 of 29 main files import `-core`; `KirFileLowering` 7,174 lines, 83 imports); `census/StructuralCensus*` is a census of `-core`'s own sink; `XTSC_KIR_ENGINE=core` keeps the old front end for the A/B | front end on `-tsgo` ((TSGO.4-c)); lowering not | **large**: rewrite `KirFileLowering` over tsgo's `ast.Node` plus a KIR-owned value model; drop `StructuralCensus` |
| `-project` | **yes**, `api(-core)` | the whole of it: `Project.kt` (4,028 lines) is the (INC.\*)/(API.\*) incremental language service over `-core`'s checker (≈17.5k main / 33k test lines, 93 of 138 files import `-core`). **Published** to Maven Central; consumed out of tree by `xemantic/xtsc-intellij-plugin`. No in-repo consumer since `-lsp` moved | — | **large**: needs tsgo's project system (`internal/project`, 11.0k Go lines) ported, or a compatibility facade over `TsgoProject`/`TsgoLanguageService` |
| `-daemon` | **yes**, `api(-core)` | `server/XtscMainKt` dispatches to `-core`'s `runCli`; `CompileServer` serves `-core` compiles. **Published** | — | **small-medium** once a tsgo CLI exists (it is a dispatcher) |
| `-cli` | **yes**, `api(-core)` | `cli/MainKt` calls `-core`'s `runCli`; the **GraalVM native image** (`:…-cli:nativeImage`, the shipped artifact, PGO in `native.yml`) is built from it. **Published** | — | **medium**: port tsgo's CLI (`internal/execute`, 7.8k Go lines, not yet ported), then re-measure the image |
| `-core` itself | — | Kotlin/Native `linuxX64` target (opt-in), its CI job (`native.yml` → `linuxX64Test`), the generated corpus suite, the embedded real libs | — | — |

**Scripts and CI.** 371 of 483 files under `scripts/` mention `-core` (`compiler-core`, `…compiler.MainKt`,
`XtscMainKt`, `BenchMainKt` or the core jar name) against 8 that mention `-tsgo`; most are dated
per-round scratch harnesses. The live ones: `scripts/xtsc` / `xtsc-aot` (the launcher, AOT cache over the
daemon's lib dir), `bench-3way.sh` + `.github/workflows/bench.yml` (the published 3-way series and the
CLI native image), `jvm-bench.yml` + `bench-compile-tsc.sh` (`-core`'s `BenchMain`), `cost_gate.py`
(`-core`'s checker counters — meaningless for the port), `huge_methods.py` (defaults to `-core`'s classes),
`corpus-screen.sh`, the 8-profile grid scripts, `kir-bench.sh` (arm 3/6 compile JavaScript with `-core`),
`native.yml` (runs `-core`'s `linuxX64Test`). `build-main.yml` publishes `-core`, `-cli`, `-daemon`,
`-project`, `-client`; **`-tsgo` is not published today**.

## 2. Parity

### 2.1 The port's gates (all equal to tsgo 7.0.2; re-verified on `19a3779c5`)

| gate | equal / total | what is compared |
|---|---|---|
| diagnostics | **13,127 / 13,127** | every configuration tsgo's compiler runner baselines (submodule + local × compiler + conformance) |
| emit | **13,127 / 13,127** | `.js` (incl. `.d.ts`), `.js.map`, `.sourcemap.txt`; tsc's 78 sources `diff -r`-identical |
| bound AST | **7,774 / 7,774** | encoded bound AST bytes vs `tsc --api` |
| type oracle (API) | **594,007 / 594,007** | `internal/api` requests replayed from `tsc --api` |
| language service | **21,614 / 21,614** | LSP requests vs `tsc --lsp -stdio` |
| `-core`'s own `diagnose` pins | **10,533 / 10,657** texts equal to tsgo, **0** port defects | `docs/goport-pin-census.md` (124 texts not compiled by tsgo's harness) |

### 2.2 `-core`'s standing

- Suite: **22,999 / 0 / 44** at (P18.313) (core module 21,248 of them); active corpus subtests **8,725**;
  **22** `LogicalParityDivergence` baselines switched off and **19** `tsgoPendingBaselines` (tsgo answers
  `-core` does not produce) in `-core/build.gradle.kts`.
- The **1,065** pins that pass on `-core` and fail on the port assert `-core`'s divergences from tsgo
  (590 "tsgo reports more", 176 different rows, 68 "core reports more", 57 message text, 22 chain/order) —
  plus **152** that pin `-core` internals (section probes, censuses, `PassTiming`) — `docs/goport-pin-census.md` § 3.
- Real-library tally (LIBS.1, ours-only rows vs tsgo over 8 libraries): **152** at (P18.313), down from 1,896.

### 2.3 Measured here: 18 projects, three compilers, row for row

`tools/tsgo-7.0.2/lib/tsc --noEmit -p` against the port (`SunsetProbeMain rows`, § 8) against `-core`'s CLI
(`MainKt --noEmit --listAll`), keyed on (file, line, code); "msgEq" also requires the head message text.
Libraries: the (P18.265) census set with its stored tsgo outputs, plus cronstrue (`…/cronstrue/src`, its own
`target: ES5`, so both references stop at the TS5108 config row) and marked (`build/bench/inc50-scratch-marked`).

| project | tsgo | port | port only / missing | port msgEq | `-core` | `-core` only / missing |
|---|---:|---:|---:|---:|---:|---:|
| tsc profiles × 7 (compiler, tsc, jsTyping, deprecatedCompat, typingsInstallerCore, services, server) | 65 each | 65 each | 0 / 0 | 65 each | 46 each | 0 / **19 each** |
| tsc profile harness | 126 | 126 | 0 / 0 | 126 | 94 | 1 / **33** |
| cronstrue | 1 | 1 | 0 / 0 | 1 | 1 | 0 / 0 |
| marked | 0 | 0 | 0 / 0 | 0 | 0 | 0 / 0 |
| mitt | 0 | 0 | 0 / 0 | 0 | 0 | 0 / 0 |
| date-fns | 1 | 1 | 0 / 0 | 1 | 1 | 0 / 0 |
| ky | 0 | 0 | 0 / 0 | 0 | 5 | 5 / 0 |
| superstruct | 10 | 10 | 0 / 0 | 10 | 5 | 4 / 9 |
| immer | 9 | 9 | 0 / 0 | 9 | 1 | 1 / 9 |
| zod | 14 | 14 | 0 / 0 | 14 | 27 | 13 / 0 |
| hono | 0 | 0 | 0 / 0 | 0 | 18 | 18 / 0 |
| type-fest | 0 | 0 | 0 / 0 | 0 | 111 | 111 / 0 |
| **total (18 projects)** | **616** | **616** | **0 / 0** | **616** | **585** | **153 / 184** |

Single run per arm (row counts are deterministic). The `-core` column reproduces the (P18.313) library
tally exactly (152 ours-only on the 8 census libraries; the 153rd is the harness profile's TS2591).
The 19 rows `-core` misses on every tsc profile are TS2591 ×11 (`require`/`process` under `types: []`),
TS18048 ×8 (compiler profile) and a handful of TS2304/TS2307/TS2345/TS7006/TS7031 — **the Phase-17 v1 "zero
false positives" exit never measured false negatives**, and these are them.

## 3. Cost

### 3.1 Warm whole program, tsc's own sources (compiler profile, 78 files)

One JVM per arm, `-Xms2g -Xmx3g -XX:+UseParallelGC`, 6 warm-up + 8 measured rebuilds, ABBA order
(port-check, core-check, port-emit, core-emit, core-emit, port-emit, core-check, port-check), then one
single-threaded port arm. JDK 26 (Zulu). **Two processes per arm, one for single-threaded**; the box had
~4 GB available (two idle Gradle/Kotlin daemons hold ~10 GB), which is why the heap is 3 GB.

| arm | mean of the process medians (ms) | the two process medians | process peak RSS | diagnostics |
|---|---:|---|---:|---:|
| port, check, parallel (tsgo's default) | **2,672** | 2,659 / 2,686 | 3.5 GB | 65 |
| port, check, single-threaded | 3,940 (one process) | — | 3.4 GB | 65 |
| `-core`, check | **7,572** | 7,711 / 7,433 | 1.8-2.2 GB | 46 |
| port, check + emit, parallel | **4,026** | 3,971 / 4,080 | 3.6 GB | 65, 78 files, 8.84 M chars |
| `-core`, check + emit | **8,883** | 8,671 / 9,095 | 2.1-2.3 GB | 46, 78 files |
| tsgo 7.0.2 binary, `--noEmit` (process wall, 3 runs) | 1,790-1,820 | | 0.37 GB | 65 |
| tsgo 7.0.2 binary, emit (process wall, 3 runs) | 2,600-2,680 | | 0.5-0.55 GB | 65 |

Caveats that cut both ways: the port re-parses every iteration while `-core`'s `BenchMain` serves parses
from its content-keyed `CrawlParseCache` (favours `-core`); `-core`'s emit arm writes to disk while the
port's counts the bytes (favours the port, by a few ms); the port spends **~0.9-1.2 s of each check in GC**
at a 3 GB heap (`docs/goport-perf.md` § 6 measured 2.1-2.5 s at 6 GB). So: **~2.8x faster check, ~2.2x
faster check+emit than `-core`; ~1.5x tsgo's wall; 1.6-1.7x `-core`'s memory** and ~6-10x tsgo's.

### 3.2 Cold, one shot (from the § 2.3 runs, `-Xmx3g`, one draw each)

| project | port wall / RSS | `-core` wall / RSS |
|---|---|---|
| compiler profile | 10.3 s / 2.0 GB | 29.2 s / 1.3 GB |
| harness profile | 14.1 s / 2.5 GB | 40.6 s / 1.4 GB |
| zod | 6.9 s / 1.4 GB | 10.4 s / 0.6 GB |
| hono | 5.6 s / 1.4 GB | 7.7 s / 0.6 GB |
| type-fest | **35.1 s / 3.6 GB** | 16.5 s / 2.1 GB (and 111 false positives) |
| small libraries (mitt, ky, superstruct, marked, cronstrue) | 1.9-4.0 s / 0.3-1.1 GB | 1.7-2.9 s / 0.2-0.3 GB |

type-fest is the port's worst case: tsgo itself takes 17.7 s there and ~5 GB parallel (`docs/goport-perf.md`
§ 6.3), so at 3 GB the port is heap-starved. `-core` is faster there because it does not do the work
(it resolves less and reports 111 rows tsgo does not).

### 3.3 Engines inside the products (already recorded)

- Externals, `@types/node` 51 modules, warm: tsgo **~17 s** vs `-core` **~30 s**; heap ≤ 1.6 GB vs 0.7 GB ((TSGO.4-b)).
- KIR front end, 30 programs + 3 projects, one draw: tsgo **1,265 ms** vs `-core` **1,973 ms**; peak heap 394 vs 367 MB ((TSGO.4-c)).

### 3.4 Build and artifact

| | `-tsgo` | `-core` |
|---|---:|---:|
| main Kotlin lines | 342,133 (≈99% generated) | 307,873 hand-written |
| test Kotlin lines | 11,227 | 255,971 |
| main classes / class bytes | 4,785 / 49.2 MB | 984 / 19.9 MB |
| JVM jar | 18.3 MB | 7.1 MB |
| `compileKotlinJvm --rerun` (warm daemon, full rebuild — every class rewritten; one draw) | 100 s | 104 s |
| methods over 8,000 bytecodes (main) | 0 | 0 |
| Kotlin/Native target | **none** (`jvm()` only) | `linuxX64`, opt-in, CI `native.yml` |
| GraalVM native image | **never built** | `-cli`'s `nativeImage`, PGO, shipped |

## 4. What `-core` has that `-tsgo` lacks

1. **A CLI.** tsgo's `internal/execute` (7.8k Go lines: `tsc`, `--build`, watch, incremental `.tsbuildinfo`)
   is not ported. The port is driven by test mains (`CheckBenchMain`, `EmitProjectMain`, `SunsetProbeMain`)
   and the facades. `-core`'s `runCli` (options, `--listAll`, `--passTiming`, `--workers`, `--serve`/`--daemon`/`--socket`,
   exit codes pinned by `ExitCodeParityTest`) has no counterpart.
2. **The daemon/serve path.** `CompileServer` (one compile thread, protocol v2 working-directory handoff,
   `XTSC_REFUSED`), the native thin client's fallback contract and the AOT launcher (`scripts/xtsc`, JDK AOT
   cache guard) all sit on `-core`.
3. **Native.** `-core` builds and passes its suite on Kotlin/Native `linuxX64` (CI) and ships as a GraalVM
   image with PGO (~1.9x faster than tsc 6 on this box, `docs/perf/aot-native-image.md` § 10). `-tsgo` has no
   native target; its commonMain has no `java.*`, and its two `expect`/`actual` pairs (`go/os`, `go/sync/Park`
   — goroutine parking) have only JVM actuals. Kotlin/Native has no escape analysis and the port allocates
   ~2.6 GB per check (CLAUDE.md: 4-29x per primitive) — the risk is cost, not correctness, and it is unmeasured.
4. **The `-project` API** (published): incremental rebuilds (`updateFile`/`deleteFile`/`reloadFile`),
   partitioned `diagnosticsOf`, `prepare`, `saveState`/`restoreState` (cross-process snapshot, (INC.48)),
   cooperative cancellation, `renameAt` with a verified rename plan, `semanticsAt`/`fileSemantics`,
   `typeOracle()`, an `OverlayVfs`. `TsgoProject`/`TsgoLanguageService` answer hover, definition,
   references, completions, signature help, highlights, diagnostics and the 142-method oracle, but **open
   a fresh program per change** — no incremental update, no rename, no auto-imports, no inferred projects,
   no state snapshot (tsgo's `internal/project`, 11.0k Go lines, is not ported).
5. **KIR's lowering input.** The front end asks tsgo; the lowering walks `-core`'s AST and value model.
6. **Measurement infrastructure tied to `-core`'s checker**: `cost_gate.py`'s counters, `PassTiming`/PassLab,
   section probes, the 8-profile grid scripts, `BenchMain`. They describe `-core`'s engine and retire with it;
   the port's equivalents are the differential gates plus `CheckBenchMain`/`CaseBenchMain`.
7. **The corpus harness's own test generator** (`generateTypeScriptTests`, ledgers) — superseded for the port
   by `DiagParityTest`/`EmitParityTest`, which run tsgo's own harness over the same corpus.

Nothing on the CHECKING side is in this list: the port is the definition and `-core` is a reconstruction of it.

## 5. Risks of retiring

| risk | size | mitigation |
|---|---|---|
| native image / Kotlin/Native regress or are infeasible for the port | unmeasured — the largest | measure before deletion (stage 3); keep `-core`'s image as the shipped CLI until the port's is measured |
| memory: 1.6-1.7x `-core`'s RSS warm, type-fest heap-starved at 3 GB | measured | the documented levers (`docs/goport-perf.md` § 6.3: JSDoc prefix copy ~19% of allocation, GoSlice headers); a `-Xmx` default for the CLI |
| IntelliJ plugin and other `-project` users break | certain if `-project` is removed | owner decides: facade or a new API (§ 7) |
| upstream drift: tsgo 7.1 | ongoing | re-run the porter; overrides are hash-pinned (the design's point) |
| losing the 1,065 divergence pins' intent | none for the language | they encode `-core` behaviour that tsgo does not share; the 9,617 language pins pass on both and can be re-pointed at the port |

## 6. Recommendation and staged plan

**Retire `-core`.** It is less correct on every real project measured (153 false positives, 184 misses
against 0 / 0), 2.2-2.8x slower warm on the profile it was optimised for, and frozen. Its remaining value
is plumbing and native packaging, all of which can move. Do not delete before stage 4's gates are green.

| stage | what | gate | size |
|---|---|---|---|
| **0. Freeze** | done ((TSGO.0) D3, 2026-10-07) | — | — |
| **1. A tsgo CLI** | port `internal/execute` (+ `tsc` command line, `--build`, incremental) through the porter; a `-cli`-shaped entry on `-tsgo` | CLI-output differential vs the tsgo binary over the 8 profiles + the census libraries + the diag-oracle configurations (§ 2.3 is the shape: 616/616 today through a test main); exit codes; emit `diff -r` | medium (7.8k Go, mostly mechanical) |
| **2. Native** | add `linuxX64` to `-tsgo` (two `actual`s), build the GraalVM image of the stage-1 CLI with PGO | native suite of `-tsgo` green in CI; image runs the stage-1 differential; wall/RSS vs `-core`'s image on the compiler profile | medium, **unknown cost** — the go/no-go of the whole retirement |
| **3. Move consumers** | `-daemon` and `scripts/xtsc` onto the stage-1 CLI; `bench.yml`/`bench-3way.sh`/`jvm-bench.yml` onto the port; KIR's lowering onto tsgo's AST (`KirFileLowering` rewrite); re-point the 9,617 language pins at the port, delete the 152 internals pins and the 1,065 divergence pins | each consumer's existing gate (serve parity, kir 313/313 + `kir-bench.sh` equivalence, the bench series with a declared V3 boundary) | large (KIR) + small (daemon, benches) |
| **4. `-project`** | either port tsgo's project system (`internal/project`, 11.0k Go) and expose it through a `Project`-shaped facade, or ship a new API and deprecate `-project` | the IntelliJ plugin's flows against the new API; LS differential (21,614) extended to incremental edits | large |
| **5. Delete** | remove `-core`, its CI job, its corpus generator, `cost_gate.py`/grid scripts; archive the `-core` sections of CLAUDE.md | full suite green without the module; published artifacts renamed/redirected | small, mechanical — **owner decision** |

## 7. What the owner must decide

1. **Retire `-core` at all** — recommended yes, after stage 2's native measurement.
2. **The `-project` API**: keep it as a facade over the port (compatibility for the IntelliJ plugin) or
   replace it with a tsgo-shaped API (`TsgoProject`/`TsgoLanguageService` grown into a project system).
3. **The published coordinates**: `-tsgo` is unpublished; the jar name `xemantic-typescript-compiler` is
   today `-core`'s (a contract for the AOT scripts) — which artifact carries it after the switch.
4. **A JVM-only interval**: whether stages 3-5 may proceed before Kotlin/Native for the port is measured
   (the GraalVM image is the shipped CLI; Kotlin/Native is the KMP promise).
5. **Memory budget**: whether ~1.6x `-core`'s heap for ~2.5x its speed and exact tsgo parity is acceptable
   for the CLI and the daemon defaults.

## 8. Reproduce

```bash
# classpaths: -tsgo's and -core's jvmTestRuntimeClasspath (init script as in scripts/tsgo-parse-bench.sh)
java -Xmx3g -Xss512m -cp <tsgo cp> com.xemantic.typescript.tsgo.SunsetProbeMainKt rows <tsconfig.json>
java -Xmx3g -Xss512m -cp <core cp> com.xemantic.typescript.compiler.MainKt --noEmit --listAll <project dir>
tools/tsgo-7.0.2/lib/tsc --noEmit -p <tsconfig.json> --pretty false        # run from the project dir
# warm, one JVM per arm
java -Xms2g -Xmx3g -XX:+UseParallelGC -cp <tsgo cp> com.xemantic.typescript.tsgo.SunsetProbeMainKt \
  bench build/bench/tsc-project-*/tsconfig.json 6 8 <check|emit> <parallel|single>
java -Xms2g -Xmx3g -XX:+UseParallelGC -cp <core cp> com.xemantic.typescript.compiler.bench.BenchMainKt \
  build/bench/tsc-project-* 6 8 off <noEmit|emit>
```

`SunsetProbeMain rows` mirrors `tsc --noEmit -p`: the CLI's `noEmit`, then `compiler.GetDiagnosticsOfAnyProgram`
(which stops after config/syntactic/global errors exactly as the CLI does — without it cronstrue reads an extra
TS2550 and marked/ky an emit-only TS5096/TS5011), then `SortAndDeduplicateDiagnostics`. The library tsgo
outputs are the stored ones under `build/scratch-p18265-census/out/`; rows are compared on (file, line, code)
after stripping the project root, with the head message as the second key.

**Not measured**: Kotlin/Native and the GraalVM image of the port (never built); the port's CLI (does not
exist); `-project`'s incremental latency against an equivalent port path (none exists); a quiet-box warm
figure at tsgo's own memory (the box had ~4 GB free, so every JVM arm ran at 3 GB).
