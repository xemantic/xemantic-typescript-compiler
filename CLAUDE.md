# CLAUDE.md

This file captures only what cannot be inferred from the codebase itself. It is loaded into
EVERY session, so every line costs context on every turn.

## Rules for editing this file

- Add an entry only for a trap a future agent WILL break without knowing it — not something
  rediscoverable in under 60 seconds with grep + reading source.
- An entry is 1–3 lines: the trap/invariant and where to look. No fix stories, round numbers,
  B-numbers, measurement tables or test-name narratives — those go in the commit message, the
  PLAN-PHASE-5.md session note, or the relevant `docs/goport-*.md`.
- File entries under the matching topical section; do not add per-round sections.
- **History**: everything from the `-core` era (the hand-written checker, frozen since
  (TSGO.0) 2026-10-07) — per-walker/per-diagnostic gotchas, corpus/pin/ledger rules,
  INC/INV/CHK/KIR measurements, the Phase 17/18 protocol detail — is in
  `docs/history/CLAUDE-CORE-ERA.md` (verbatim snapshot) and the older
  `docs/history/CLAUDE-GOTCHAS-ARCHIVE.md`. **Grep them (never read them whole — they are
  hundreds of KB)** before touching `-core`, a corpus regression, or a frozen subsystem.

## Mission

xtsc is **TypeScript for the JVM and Kotlin** (owner directive 2026-09-01): type-check and
compile TypeScript with no Node and no Go in the toolchain; a whole-program checker embeddable
in Kotlin (the `Project` API); a Kotlin externals generator with resolved types; a JVM bytecode
backend (KIR); an LSP. It is not "a compiler competing with tsgo".

- **OWNER DECISION 2026-10-06 — PORT tsgo TO KOTLIN.** `-goport` (mechanical go/types-driven +
  LLM porter) lowers tsgo's Go into the checked-in `-tsgo` module. Read `docs/tsgo-port-plan.md`
  and the `docs/goport-*.md` family before (TSGO.\*) work. **Never hand-edit
  `-tsgo/src/commonMain/kotlin/gen/`** — every fix is a porter rule or a hash-pinned override.
- **(TSGO.0) decided 2026-10-07**: Go 1.27.1 at gitignored `tools/go/` (run
  `GOTOOLCHAIN=local tools/go/bin/go`); `-tsgo` is AGPL-3.0-only + output exception (Microsoft
  copyright, Apache `LICENSE` + `NOTICE.txt`, porter-emitted headers); **`-core` is FROZEN** —
  no (CHK.\*)/(INV.\*)/(LIBS.\*) parity or extraction rounds; it takes only fixes a product needs now.
- **TypeScript 7.0 is the ONLY compatibility target** (2026-09-12). Reference:
  `tools/tsgo-7.0.2/lib/tsc` (gitignored). TS 6 is not an adjudicator.
- **Compatibility is an invariant on the shared surface** (2026-09-21): a semantic divergence
  that would make code check differently than in VS Code is refused. A tsgo defect tsgo itself
  records under `submoduleTriaged/` is not a target (the intended answer is); `submoduleAccepted/`
  is. Extensions (Project API, oracle, externals, KIR, LSP) are a strict superset; anything
  stricter than tsgo is opt-in. Unrecorded tsgo divergences go to `docs/upstream-findings.md`.
- The (INC.\*) latency arc is CLOSED: refuse latency rounds unless a plugin-facing query is
  measured > 300 ms warm.

### Guardrails requiring user input

- Build system changes (Gradle config, dependency add/remove/upgrade, Kotlin version bump).
  Pre-approved: `-externals` and `-lsp` modules; the LSP uses `kotlinx-serialization-json` +
  `kotlinx-io` only — **lsp4j is NOT approved**.
- Test-generation pipeline changes (`generateTypeScriptTests`, baseline format).
- README repositioning lands on branch `docs/reposition`, never main.

## Execution protocol

`PLAN-PHASE-5.md` holds the live QUEUE (its WORK ORDER note is at the top) and session notes.
**It is ~1 MB — never read it whole; grep for the queue heading / `- [ ]` and read slices.**

1. Pick the first unchecked `- [ ]` queue item. If blocked on missing infrastructure, promote the
   smallest unblocker to the top of the queue (with a back-pointer) and work that — never skip
   silently. Only Guardrail items may be skipped, with a `BLOCKED-PENDING-USER:` proposal.
2. Implement it, with hand-written local tests pinning the invariant (build the probe so it
   FAILS if the change does not work).
3. Gate it (see "Gates" below). No regressions.
4. Check off the item, bump STATUS.md (keep ~5 most recent notes; older ones go to
   `docs/history/STATUS-HISTORY.md`), add a short session note on top in PLAN-PHASE-5.md,
   commit + push — one commit per sub-step.
5. Loop. When context runs low, finish the current item, commit, stop. Never end a session with
   analysis-only output while workable items remain, and never leave a dirty tree
   (`scripts/run-loop.sh` aborts on one).

## Gates

- **Diagnostics differential** (any lowering/porter change):
  `TSGO_DIAG=1 TSGO_TEST_HEAP=2g ./gradlew :xemantic-typescript-compiler-tsgo:jvmTest --tests '*DiagParityTest*'`
  then `python3 scripts/tsgo-diag-compare.py build/goport/diag-kotlin`. Rebuild inputs with
  `scripts/tsgo-diag-cases.py` then `scripts/tsgo-diag-oracle.py`. `SnippetMain` (jvmTest)
  runs one file outside Gradle in ~2 s.
- **Emit differential** (`EmitParityTest`, `TSGO_EMIT=1`, docs/goport-emit-oracle.md) — the only
  gate that sees shim formatting bugs; the diag differential passes them.
- Parity gates need their heaps (`TSGO_TEST_HEAP` 3g emit/LS/API, 4g CLI) with no idle Kotlin
  daemon resident, or they OOM and read as a regression.
- A worktree has no `tools/` — link `tools/tsgo-7.0.2` in before gating, or `ApiParityTest`
  reports mass "differ".
- Native image gate: `scripts/tsgo-cli-native-replay.py`.
- Language-service ground truth: `tools/tsgo-7.0.2/lib/tsc --lsp -stdio` is an LSP server
  (client `scripts/lsp_hover.py`) — never hand-write expected LS answers.

## tsgo port gotchas

- A generated struct class doubles as Go's pointer: identity `equals`/`hashCode` except where Go
  compares by value (`Program.structKeys`). Widening that set made every `map[*Node]` structural
  and the test JVM was SIGKILLed.
- A shim dispatching on a value's Kotlin class must treat the named-basic value classes
  (`GoBasicValue`) as their underlying kind; a `[]byte` is recognisable only by `GoElem.BYTE`.
- A struct in `IMMUTABLE_STRUCT_CANDIDATES` has `goCopy() = this`; a window field
  (`Parser.sourceText`, `Scanner.text`) is three slots `f`/`f_o`/`f_n` — read via
  `goStrWin(f, f_o, f_n)`. Hand-written Kotlin must not mutate the former or assign `f` alone
  (docs/goport-lowering.md § 3).
- An owned slice field's `GoSlice` header is mutated in place (`append1Owned`/`sliceOwned`):
  hand-written code holding one must take `ownedCopy()`.
- `GoLinkKey.goLinkIndex` is a port-internal index, never tsgo's `Node.id` (lazily assigned,
  order-dependent): nothing observable may read it, and the `core.LinkStore` override must not
  fall back on `GetNodeId`.
- A goroutine runs only while holding a `GoProcs` run token (`TSGO_GOMAXPROCS`): any new way for
  ported code to block must wrap the wait in `blockingWait { }` (`GoProcsTest` N=1 pins detect
  it). Never give a not-yet-runnable goroutine its own thread — queue the closure.
- Do NOT release the run token around host file IO or hand IO to a pool — measured large
  slowdowns (docs/goport-perf.md § 9); `TSGO_IO_STATS=1` prices IO.
- `goSpawn` makes a throwable escaping a goroutine fatal (exit 2, like a Go panic) — never route
  around it; otherwise an OOM inside `WaitGroup` becomes a silent 0%-CPU hang.
- The KIR front end's tsgo program must be `singleThreaded` (`TsgoProgram.kt`): one query checker
  behind one lock, and a throwing parallel goroutine never releases it → parked JVM, no error.
- `-core`'s declaration `Node.pos` is AFTER modifiers, tsgo's is not: `FileNodeMap` keys a
  modified tsgo node under both offsets.

## Native image / JVM runtime

- `-tsgo` native links with `gc=pmcs`, not Kotlin 2.4's default `cms` (docs/goport-runtime.md § 9a).
  A binary option that is the default or a no-op links a byte-identical binary — `md5sum` every
  arm before timing. `-PtsgoNativeGcLogs=true` is the instrument (no profiler on this box).
- A GraalVM image with `--gc=G1` caps heap at 25% of RAM; `:…-tsgo:nativeImage` sets
  `-R:MaxRAMPercentage=80`. PGO (`scripts/tsgo-native-image-pgo.sh`, Oracle GraalVM) is the AOT
  lever worth having.
- The PGO script initializes the port at BUILD time (`TsgoLibSnapshot`): any new top-level or
  companion state reading env, CPU count, clock or files is baked in — add it to
  `--initialize-at-run-time` (as `GoProcs` is) or read lazily. Replay with `XTSC_TSGO_LIB_DIR`
  unset to exercise the snapshot.
- The JDK AOT cache refuses a class DIRECTORY on the classpath and the training run still exits
  0 having written nothing (only tell: `[error][aot] … non-empty directory` on stdout) — jar it.
- The AOT cache has no invalidation; `scripts/xtsc`'s content-hash manifest is the only guard —
  never simplify it to mtime/size. Jars are not reproducible, so any build invalidates the cache:
  train after the last build and confirm `XTSC_AOT_VERBOSE=1` prints `aot USE …` before
  measuring a cached arm.

## Build, tools and process traps

- **Never run any Gradle task while `jvmTest` is in flight** — it silently kills the suite (empty
  results dir).
- The box has 7.7 GB: Gradle daemon + KotlinCompileDaemon + a big JVM run → swap → fake "hang".
  `./gradlew --stop && pkill -9 -f KotlinCompileDaemon`, check `free -m`, then run.
- Test XMLs are per-module (`*/build/test-results/jvmTest/*.xml`); a root-level glob prints
  `0 0 0`, indistinguishable from a suite that never ran. Wipe the whole results dir, not
  `/binary`. Count with a real XML parser, never a regex (passed testcases self-close).
- `--tests '*Name*'` wipes the XMLs — rerun the full suite before any XML-reading script.
- Always `grep -a` on Gradle output AND on large sources (`Checker.kt`, `Project.kt` trip grep's
  binary heuristic and silently match nothing). Retry a surprising empty grep with `-a`.
- Warning gate: the build is warning-clean; check with
  `./gradlew compileKotlinJvm compileTestKotlinJvm --rerun-tasks 2>&1 | grep -a '^w:'`. Never
  `-q` (zero-byte log reads as clean). `--rerun` is per-task — put it after every task or use
  `--rerun-tasks`. An empty log is not a pass: prove the gate live with an injected warning.
- Kotlin's `File.deleteRecursively()` follows directory symlinks and empties the TARGET. Delete
  trees that may hold links with `Files.walk(p).sorted(reverseOrder()).forEach(Files::delete)`
  or `shutil.rmtree`.
- TypeScript 7 ships as the `typescript` package (bin `tsc`); `@typescript/native-preview` is
  retired. TS 6 and 7 cannot coexist in one `node_modules`.

## Kotlin idioms

- `commonMain` targets Kotlin/Native too: only `kotlin.*`/`kotlinx.*`, no `java.*` or JVM-only
  APIs (`putIfAbsent` → `getOrPut`, `Math.pow` → `kotlin.math.pow`, `DOT_MATCHES_ALL` →
  `[\s\S]`). `compileKotlinJvm` does not catch these.
- Inside KDoc, `*/` ends the comment and a literal `/*` or `/**` (including in a path glob) opens
  a NESTED comment that runs to EOF. Reword.
- Enum context resolution: use unqualified entry names where the enum type is expected, unless a
  same-named class makes it ambiguous.
- Never key a HashMap/HashSet by a data-class AST node with a large subtree (deep `hashCode`).
- On hot, order-independent maps/sets use `HashMap()`/`HashSet()`, not `mutableMapOf()`
  (LinkedHashMap) — after proving iteration order is unused.
- A `when (v) { null, X, false -> … }` compiles to a chain of `Intrinsics.areEqual`; prefer type
  tests on hot paths.

## Tests

- One assertion idiom: power-assert `assert(<boolean>)` / `should { have(…) }` from
  com.xemantic.kotlin.test, no message argument. `actual == expected` order; empty collections
  as `assert(xs.isEmpty())`.
- Never put an AST node/SourceFile inside a power-assert expression (the failure diagram renders
  whole subtrees → SOE/OOM); compute primitives into locals first.
- Assert the full expected output, not a substring. A pin is only a pin once it has failed
  against the mistake it names.
