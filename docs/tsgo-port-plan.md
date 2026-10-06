# Porting tsgo to Kotlin — plan and spike gate

Owner decision 2026-10-06. Queue items: (TSGO.0) – (TSGO.4) in `PLAN-PHASE-5.md`.

## 1. Why

A checker reconstructed from tsgo's outputs keeps diverging from it, in behaviour and in data
structures. With current LLM tooling, a mirror port of tsgo is a practical alternative.

Our own record agrees. TypeScript has no spec; tsgo *is* the definition, and
xtsc reconstructs it from outputs. Measured on 2026-10-06:

| | lines |
|---|---|
| tsgo check path (scanner, parser, ast, binder, checker, compiler, core) | ~110–130k Go |
| of which `internal/checker` | 59,971 Go |
| xtsc `-core/src/commonMain` | 305,063 Kotlin |

The 2.5x surplus is the ~1,000 pin walkers, wipe-and-pin machinery and per-shape rules that a
reconstruction accumulates. Recent rounds are "find another divergence from tsgo, add a rule",
which does not converge, and the checker's walk-scoped state structurally cannot answer a
post-hoc type oracle (`CheckedProgram.kt`, `TypeCapture.kt`, `docs/type-oracle.md`).

A mirror port fixes the root cause: the same algorithms, the same data structures, the same
answers — and tsgo's own `internal/api` (142 methods: `getTypeAtLocation`, `getSymbolAtLocation`,
`isTypeAssignableTo`, …) comes along for free, which is exactly the oracle an IDE integration needs.

**What stays xtsc's differentiation** is unchanged by this decision and gets a better engine
underneath it: the embeddable `Project` API, the Kotlin externals generator with resolved types,
the KIR JVM/native backend, the LSP, Kotlin Multiplatform, no Node and no Go for users.

## 2. Shape: a tsgo porter, not a general transpiler

A general Go→Kotlin transpiler is a research project. A porter that only needs to handle the Go
subset tsgo actually uses is tractable, and its *mechanical* half is the point: when tsgo moves
from 7.0.2 to 7.1, we re-run the porter and review a diff, instead of re-porting by hand and
drifting. An LLM-only port is a one-off; it decays from the first upstream commit.

### 2.1 Pipeline

```
typescript-go-repo (pinned tag)          dev-time only, never needed by users
        │
        ▼
 goport-extract  (small Go program, go/packages + go/types)
        │  typed IR as JSON: every decl, expr, resolved type, method set,
        │  escape/aliasing facts, per-function source hash
        ▼
 xemantic-typescript-compiler-goport   (Kotlin, Gradle module, JVM)
        │  mechanical lowering Go IR → Kotlin source
        │  + overrides/: hand/LLM ports keyed by Go qualified name + source hash
        ▼
 xemantic-typescript-compiler-tsgo     (generated Kotlin, CHECKED IN, commonMain)
```

- **Extraction runs Go; nothing else does.** Type information is not optional: whether `a = b`
  copies or aliases, whether a slice is shared, which method an embedded field promotes — these
  are type questions, and `go/types` answers them exactly. Re-implementing Go's type checker in
  Kotlin to avoid a dev-time Go install would be the same mistake this plan exists to stop.
- **The output is checked in.** Building xtsc needs no Go; a tsgo upgrade is a reviewable diff;
  a reviewer can read the generated Kotlin next to the Go.
- **Overrides are keyed by source hash.** Where the lowering refuses (goroutines, channels,
  `unsafe`, reflection, a generic shape it does not map), it emits a stub naming the function,
  and a port written by hand or by an LLM goes in `overrides/<qualified name>.kt` carrying the
  hash of the Go body it was written against. On re-extraction, a changed hash makes that
  override **STALE** and the build fails until it is re-ported. This is the line between "hybrid"
  and "drifting".
- **The LLM's role is bounded and auditable.** It writes overrides and proposes new lowering
  rules; it never edits generated output. Every override is a file a human can diff against its
  Go source.

### 2.2 Lowering — the known hard parts

Ordered by how silently they fail:

1. **Value-struct copy semantics.** `x := y` on a struct copies in Go; in Kotlin it aliases.
   Wrong here is a silent wrong answer. Rule: immutable small structs (`core.TextRange`) → a
   `value class` (packed `Long`); every other struct type gets an explicit `copy()` at each
   assignment, parameter pass and return that `go/types` marks as struct-valued, unless the
   extractor can prove the source is dead afterwards.
2. **Slice aliasing.** `s[i:j]` shares a backing array, and `append` may or may not reallocate.
   Default: an owned `ArrayList`-backed representation where the extractor proves no
   sub-slice outlives a mutation; otherwise a `GoSlice<T>` view type from a small runtime. The
   spike measures how often the fallback is needed — that number decides the runtime's design.
3. **Zero values.** Every declared-but-unassigned variable, every struct field, every map miss.
   The lowering emits them explicitly from the type.
4. **Identity-keyed maps.** `map[*ast.Symbol]T` is identity; a Kotlin data class would make it
   structural (CLAUDE.md's round-471 deep-`hashCode` trap). Generated node/symbol/type classes
   are plain classes, never `data`.
5. **Multiple returns, `defer`, `panic`/`recover`, struct embedding, method values, generics.**
   Mechanical but numerous; each gets a lowering rule and a pin.
6. **Map iteration order.** Go randomizes it, so correct tsgo code never depends on it; Kotlin
   `HashMap` is fine. Any place the port *does* show order-sensitivity is a porting bug.
7. **Concurrency.** Small in the check path: 7 sites across checker, binder, parser and scanner;
   `go`/`WaitGroup` lives in `compiler`, `core`, `lsp`, `project`, `fswatch`. Lower to the
   existing `runInDeepStackWorkers` / coroutine machinery by override, not by rule.

Generated code targets `commonMain` from day one (no `java.*`), because that is a property of the
lowering and expensive to retrofit. JVM is the only target exercised during the spike.

## 3. Modules

| module | contents | notes |
|---|---|---|
| `xemantic-typescript-compiler-goport` | the Kotlin lowering, `overrides/`, the `goport-extract` Go source under `goport-extract/` | JVM; a dev tool, not published |
| `xemantic-typescript-compiler-tsgo` | generated Kotlin + the small Go runtime (`GoSlice`, zero values, …) | KMP `commonMain`; published eventually |

The existing `-core` stays untouched and keeps building and passing. The two engines live side by
side, which is what makes the differential in § 5 possible.

## 4. The spike — (TSGO.1)

**Scope:** scanner, parser, AST, and the API encoder, plus their closure — `core`, `collections`,
`debug`, `diagnostics`, `jsnum`, `stringutil`, `tspath`, `locale`. About **57k Go lines, ~20k of
them generated** (`ast_generated.go`, `kind_*_generated.go`, `diagnostics_generated.go`, encoder
`*_generated.go`).

**The oracle needs no Go and already exists.** The shipped `tools/tsgo-7.0.2/lib/tsc --api`
serves `getSourceFile`, which returns the file's AST as the bytes of
`encoder.EncodeSourceFile` (base64 under `--async` JSON-RPC). Porting the encoder too means the
gate is **byte equality of the encoded AST**, file by file:

```
ported Kotlin: parse(text) → EncodeSourceFile → bytes
tsgo binary:   --api getSourceFile          → bytes
```

Every node kind, flag, position, parent link, and the string table is in those bytes. No AST
dump format to invent, no tolerance to argue about.

**Corpus for the gate:** every `.ts`/`.tsx`/`.js` in the conformance cases, tsc's own 78 sources,
and the library probes (cronstrue, marked, knip, type-fest, hono, rxjs).

### 4.1 Go / no-go gate (timebox: 3 weeks of rounds)

| | go | no-go |
|---|---|---|
| encoded-AST byte equality | ≥ 99.5% of files, every miss explained | < 98% or unexplained clusters |
| mechanically lowered share | ≥ 90% of Go lines | < 75% (it is an LLM port with extra steps) |
| overrides | ≤ ~50 functions, all hash-pinned | override count still climbing at timebox |
| parse throughput, JVM warm, tsc's 78 sources | within 1.5x of `-core`'s `Parser` | > 3x slower with no identified cause |
| build | warning-clean, `huge_methods.py --fail-over 0` green | — |

`huge_methods.py` matters: Go functions translate one-to-one, and tsgo has very large ones. A
method over 8,000 bytecodes is never JIT-compiled (CLAUDE.md, (JIT.1)), so the lowering must
know how to split, and the spike finds out whether it has to.

**If no-go:** the written result is the deliverable — which lowering class failed and why — and
the `-core` lane continues. **If go:** (TSGO.2).

## 5. After the gate

- **(TSGO.2) binder + checker** (~64k Go). Oracle: diagnostics differential against the tsgo CLI
  over the corpus (all four layers of `docs/tsgo-baselines.md`) and against our ~2,800
  hand-written pins, run against **both** engines. A pin that `-tsgo` fails and `-core` passes is
  a porting bug or a wrong pin; the reverse is `-core`'s defect. Both are useful.
- **(TSGO.3) program, module resolution, transformers, printer** — emit parity, and
  `internal/api` exposed through the `Project` API: the type oracle.
- **(TSGO.4) re-base the products** — externals, KIR, LSP onto `-tsgo`; decide `-core`'s
  retirement on measured parity, not before. The pins, the 8-profile grid, the library probes and
  the ablation discipline transfer unchanged; the hand-written checker does not.

Kotlin/Native is deferred to after (TSGO.2): tsgo allocates freely and Native has no escape
analysis (CLAUDE.md: 4–29x per primitive), so it needs its own measurement.

## 6. Decisions for the owner

- **D1 — Go toolchain at dev time.** `goport-extract` needs Go 1.26 (tsgo's `go.mod`). Proposed:
  downloaded into `tools/go-1.26/`, gitignored, exactly like `tools/tsgo-7.0.2`. Not needed to
  build or use xtsc. Nothing is installed until this is approved.
- **D2 — licence of `-tsgo`.** tsgo is Apache-2.0; the repo is AGPL-3.0-only with the output
  exception. Apache-2.0 code may be incorporated into an AGPLv3 work, so either works legally;
  either way every generated file keeps Microsoft's copyright line and the Apache notice, and the
  module ships tsgo's `LICENSE`/`NOTICE`. Option A: `-tsgo` stays **Apache-2.0** (simplest
  provenance; leaves room to collaborate upstream). Option B: AGPL like the
  rest. Recommendation: A.
- **D3 — `-core` freeze.** Recommendation: from (TSGO.1) on, `-core` takes only fixes that the
  products need now; no new (CHK.\*)/(INV.\*) parity rounds unless the gate fails.

## 7. Facts this plan rests on (re-check before relying on them)

- tsgo pin: `typescript-go-repo` at tag `typescript/v7.0.2`, commit `2bd066d87f5b`.
- `tools/tsgo-7.0.2/lib/tsc --api` accepts `-async` (JSON-RPC); `handleGetSourceFile`
  (`internal/api/session.go:1045`) returns `encoder.EncodeSourceFile` bytes, base64 in JSON mode.
- `ast.Node` is `{Kind, Flags, Loc, id, Parent, data nodeData}` with hundreds of `nodeData`
  implementations (`internal/ast/ast.go:179`) — a plain-class hierarchy in Kotlin, never `data`.
- No Go toolchain is installed on the dev box as of 2026-10-06.
