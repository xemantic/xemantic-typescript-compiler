# Architecture rethink — the M5 inversion arc (INV)

*Written 2026-07-13 (round 490). Owner directive: "follow your intuition and rescope
towards reaching the overall goal", plus the owner's measured finding that
`Flow<String>` outperforms `Sequence<String>` and that reading/decoding files on
`Dispatchers.IO` while processing on `Dispatchers.Default` yields further gains —
adopt pull-based design where it fits. This doc is the design record for the
re-scoped M5 arc. Read it BEFORE working any INV/M5 queue item. The queue itself
lives in PLAN-PHASE-5.md § QUEUE.*

---

## 0. ROUND-716 CORRECTION — read this before §1

*Owner directive 2026-07-26: "do anything needed … to increase the performance. We
are free to completely redesign this project." Round 716 answered the sizing
question this document has been assuming rather than measuring, and **the answer
overturns §1's diagnosis**. §1–§5 are kept for the record; where they conflict with
this section, this section is the measurement.*

**§1 says the cost is "uncached type recomputation". It is not.** Full attribution
of a compiler-profile run (`--passTiming`, new INV.4(g)/INV.5(c5) counters):

| | ms | share of checker-init |
|---|---:|---:|
| `checkSpine` | 14,292 | **83%** |
| — `spineEnterNode` | 7,166 | |
| — `spineLeaveNode` | 5,478 | |
| — unresolved-names family | 840 | |
| — `forEachChild` | 255 | |
| — scope maintenance | 25 | |
| **the whole type system, inside the above** | **5,056** | **28%** |
| — flow-narrowing walks (69,917) | 2,437 | |
| — `getTypeOfExpression` (624,810 calls) | 1,804 | |
| — relations (depth-0) | 468 | |
| — type-node resolution (depth-0) | 311 | |
| — member resolution | 36 | |
| **dispatch + handler machinery (residual)** | **~7,600** | **42%** |

857k nodes → **14.8 µs per node** for enter+leave, of which **8.9 µs is not type-system
work**. `spineEnterNode` is a linear chain reaching ~118 handler entry points and
`spineLeaveNode` 14 sub-dispatchers — **every handler is consulted about every node**.

**Three cache hypotheses died in one session, all measured, none reasoned:**

1. **The context-bypassed resolution prize is 68 ms** (31,571 outermost calls,
   2.2 µs each) — 0.35% of the compile. INV.5(c)'s entire reason for existing is
   worth a third of one percent.
2. **Widening the INV.5(c) gate is a LOSS.** The round-548 conservative gate rejects
   73.1% of bypassed resolutions (measured 65,000 of 88,829). Removing it lifts hits
   5,575 → 32,104 (23% → 46%) **and runs 28% slower** (6 interleaved pairs) — the
   composite-key hash probe costs more than the resolution it avoids. Memoizing the
   fingerprint (builds 53,765 → 13,293) still measured **+11.9%**.
3. **Identity keying (tsc's mapper-object approach) gets 4.1% hits** — the context
   maps are re-allocated per install, not reused per region, so reference identity
   finds almost nothing.

This is the **third independent confirmation of one law** (after round 665's 30 ms
expression memo and round 659's 75%-reappears migration): *in xtsc the cacheable
population is the cheap tail. Caching in front of a resolution does not pay, because
the resolutions that are cacheable are the ones that were already fast.* **Stop
proposing caches.** tsc is not fast because it caches; `NodeLinks.resolvedType` is a
field read on the node, not a keyed probe — there is no key to build.

**The measured lever is consultation, not computation.** Decisive probe: skipping
`spineEnterNode`'s entire chain for bare `Identifier` nodes (44.5% of all nodes,
2,746 ns each = **1,048 ms**) leaves the compiler-profile diagnostics **byte-identical**.
That time is provably unnecessary work. See queue item **(DISPATCH.1)**.

**Corrected targets.** The 2.4× gap to JS tsc is not a type-system gap — our type
system is 5 s of an 18 s compile and tsc does *more* semantic work than we do. It is
the accumulated per-node checking machinery. Order of remaining levers, by measured
size:

| lever | measured size | risk |
|---|---:|---|
| (DISPATCH.1) per-kind handler table | 1.0–2.5 s | low, mechanical |
| flow-narrowing walks (69,917 walks) | 2.4 s | medium (round 664 banked 0.83 s here) |
| `getTypeOfExpression` call COUNT (2.8× recompute) | ≤1.8 s | medium — fewer calls, NOT a memo (round 665) |
| context-cache work of any shape | **0.07 s** | **do not pursue** |

Also measured and unchanged: 1,341,719 globals lookups at 98.9% miss (the (M0.3)(i)
short-circuit, still priced ≲0.2%).

---

## 1. The verdict

Micro-optimization has hit its measured ceiling. Rounds 482–489 each produced 1–3%
against a **flat JFR profile** (top self-time entry ≤ 6%). Flatness here is not
"nothing left to optimize" — it is the signature of an architecture whose cost is a
**multiplier**, not a hotspot:

> ~hundreds of sequential full-program checker passes
> × uncached type recomputation
> × per-pass scope re-derivation
> × non-canonical type identity.

The benchmark that frames the goal (compiler profile, 78 files / 195k LOC, cold):

| | time | vs xtsc |
|---|---|---|
| tsgo 7.0-dev | 2.1 s | 12× faster |
| tsc 6.0.3 (JS on Node) | 10.2 s | 2.5× faster |
| xtsc (round 489) | ~25 s | — |

The decisive observation: **tsc does strictly MORE semantic work per node than xtsc**
(full bidirectional inference, contextual typing everywhere, variance-aware
relations) and is still 2.5× faster. It wins by doing the work **once** — one tree
walk, every computed fact cached, every instantiation interned — not by being
micro-faster. The historical proof that xtsc responds to structural fixes: rounds
432–434 (scans → indexes) took the self-compile from ~593 s to ~20 s (30×), while
eight subsequent micro-rounds bought ~10% combined.

## 2. Evidence inventory (verified in-code, 2026-07-13)

1. **`Checker.init` is a ~1,700-line sequential dispatch of ~512 distinct check
   passes** (523 call sites; 1,005 `check*` functions; Checker.kt = 162,840 lines =
   64% of the codebase). There are **575 `for (result in binderResults)` loops** —
   575 places that independently iterate the whole program, dozens of them full
   recursive walks that each rebuild their own scope state (`currentLocalTypes` +
   the shadowing/ambiguity machinery, per pass).
2. **`getTypeOfExpression` has no cache** (Checker.kt:96297). All 321 call sites
   recompute recursively, in every pass that consults them. tsc computes a node's
   type once and stores it (`NodeLinks.resolvedType`).
3. **The one node-type cache (`nodeTypes`) is bypassed whenever ANY resolution
   context is active** (`cacheable = currentTypeParamScope == null &&
   inferenceNamespaceStack.isEmpty() && …`, Checker.kt:~93091). Inside generic code
   — i.e. most of checker.ts — annotations re-resolve on every touch. Root cause:
   resolution context is *ambient mutable state*, so caching is unsound; tsc makes
   context explicit (mapper objects), so its caches are always valid.
4. **Type identity is not canonical**: `getUnionType` mints a fresh `Type.id` per
   call (documented gotcha); `resolveGenericPropertyType` mints fresh
   `Type.Object`/`Signature`/`Symbol` per query and is **depth-capped at 4 because
   it OOMs otherwise**. Non-canonical ids defeat every id-keyed cache downstream
   (relation cache, display maps) and forced structural workarounds
   (`ts2403Identical`, `unionAliasStructural`).
5. **Flow narrowing is re-launched per consumer site** (TS2339 / TS2454 / arg-check /
   return-check / assignment / arithmetic — wired one by one across rounds 408–479)
   with per-walk memos, instead of one flow-typed reference cached on the node.
6. **`mergeSymbolTable(globals, every file's locals)`** (Blocker #3) spawned the
   conflation-suppression ecology (`moduleFileLocalVarNames`,
   `conflatedTypeAliasFiles`, `conflatedInterfaceFiles`, `conflatedEnumFileSubsets`,
   per-file interface views, chimera bails) — consulted on the hottest path:
   `checkMemberAccessMissing`, the top walker in the last four JFRs, runs for every
   property access in the program.
7. Single-threaded end to end; ~1.9 GB RSS on the harness profile (312 files).
8. Front-end share: read+decode+parse+bind ≈ 1.5–2 s of the 25 s (front-end
   functions never appear in JFR top entries; the checker dominates). Emit ≈ 1–2 s
   (26.9 s emit vs ~25 s noEmit).

## 3. Cross-check against tsc and tsgo

**tsc** (the architecture to converge on): a **pull-based, single-walk,
memoize-everything** checker. `checkSourceFile` visits each file once; per-node
grammar+semantic checks run in that single visit (plus a small deferral queue for
contextually-typed functions). Every computed fact is cached in side tables:
`SymbolLinks.type`, `NodeLinks.resolvedType`, resolved signatures, structured
members resolved once *onto the type*, instantiations interned on the generic
target keyed by type-argument id lists, unions/intersections interned by sorted
member-id key, relation results cached per `(sourceId,targetId)` with a maybe-stack
for cycles. Generic instantiation is a pure **TypeMapper** applied to a cached
generic type — context lives in the artifact, never in ambient state. Flow analysis
runs once per reference (`getFlowTypeOfReference`) and participates in the cached
type.

**tsgo** (TypeScript 7): same semantics, plus native code + data layout (roughly
the first ~3.5×) and **concurrency**: parse/bind/emit fully parallel per file, and
type-checking split across a fixed number of checker workers (default 4,
`--checkers`), each with "their own view of the world" — own type tables, shared
immutable ASTs/binder output, deterministic partitioning, accepting redundant type
computation per worker rather than sharing hot caches. Confirmed against the
[TypeScript 7.0 RC announcement](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0-rc/)
and [native previews post](https://devblogs.microsoft.com/typescript/announcing-typescript-native-previews/).
Our `docs/parallel-caching.md` reached the same share-nothing design independently;
it stands. Note the prerequisite: tsgo parallelizes **one** demand-driven check
pass — you cannot usefully partition 512 program-wide passes. Parallelism is gated
behind the single-pass inversion.

**Why xtsc looks the way it does (honest retrospective):** the walker-accretion
model was arguably the *right* strategy for conformance — the byte-identical corpus
gate rewarded surgical, individually-verifiable walkers; broad engine attempts
measurably regressed (round-336 variance dead-end, round-409 resolveAlias flood).
It produced 10,196 green tests and zero real FPs on all 8 profiles. But it is the
wrong *permanent* shape: it multiplies passes, prevents unified caching, and
forecloses the post-v1 horizon — a checker whose construction IS the compilation
(512 eager passes in `init`) can never do incremental checking, watch mode, or
serve an LSP. The inversion is not only a perf play; it is the prerequisite for
everything after v1.

## 4. The streams decision (owner's Flow proposal)

The owner's microbenchmarks: `Flow<String>` beats `Sequence<String>`; reading files
on `Dispatchers.IO` (UTF-8 → UTF-16 transcoding) while processing on
`Dispatchers.Default` yields further gains. Where this lands:

**Adopted — streams at the boundaries (INV.1, INV.6, INV.7):**

- **Front-end pipeline**: a cold `Flow` of file paths → `flatMapMerge(concurrency =
  N)` where each file does read+decode on `Dispatchers.IO`, then scan+parse on
  `Dispatchers.Default` → collect to the parsed-file list (the phase barrier).
  Backpressure here is real and useful: bounded in-flight files = bounded peak
  memory during the front-end, self-regulating exactly as the owner describes.
- **Back-end**: per-file emit on Default, file writes flowing to an IO sink.
- **Watch/incremental mode (post-inversion)**: file-change events as a `Flow` is
  the natural driver.
- kotlinx-coroutines-core as a commonMain dependency is **owner-approved
  2026-07-13** (kotlinx.* is within the commonMain dependency rule; JS/WASM degrade
  to single-threaded dispatchers; Native is supported).

**Redirected — no streaming through the middle, and why:**

- After parse, the unit of work stops being a `String` and becomes a **graph**.
  Checking any file needs the bound symbol tables of every file it can reach
  (imports, `export *` barrels, script-file globals, `declare global`, module
  augmentations) — the classic compiler barrier. tsgo, with every incentive to
  stream, still runs phase barriers: parallel parse → barrier → bind → barrier →
  partitioned parallel check → parallel emit.
- Backpressure regulates producer/consumer rate mismatch over an unbounded stream.
  A compiler's working set is bounded and must stay **resident** — every AST is
  potentially consulted by every later check, so nothing is ever "consumed" and
  droppable mid-pipeline. There is no queue to regulate; the memory floor is the
  program itself.
- The **pull** intuition is exactly right, but the pullable thing is *facts, not
  strings*: "the type of node N", pulled on demand, computed once, memoized —
  tsc's model. A cold Flow re-runs its upstream per collector, which is precisely
  wrong for a DAG with massive fan-in reuse; a memoized lazy graph computes each
  fact once. So: **streams at the I/O boundaries, demand-driven memoization in the
  core, fork-join structured concurrency between phases.**
- Scale honestly stated: the front-end is ~1.5–2 s of 25 s today, so INV.1 is a
  ~1–2 s-class win now — worth having, growing with the 500k-LOC "any project"
  horizon, and it derisks the coroutine foundation the big INV.6 win (2–3×) needs.
  The 15+ s prize is the checker inversion (INV.2–INV.5).
- Inside checker hot paths, neither `Flow` nor `Sequence` belongs — plain loops,
  arrays, and id-keyed tables win; the owner's Flow-vs-Sequence result applies to
  the pipeline layer, not the core.

**Determinism hazards recorded now:**

- Parallel **parse** is safe: the parser is per-file pure (internSalt =
  fileName.hashCode stamped per file); verify no hidden global counters during
  implementation.
- The **binder stays sequential in file order** in INV.1: global `nextSymbolId`
  allocation order is load-bearing (documented ~350-test reshuffle on id drift;
  first-touch semantics). Parallel binding needs per-file id spaces + deterministic
  renumbering — INV.2 territory at the earliest, likely never necessary (bind is
  cheap).
- `Type.id` allocation is checker-phase and unaffected by front-end parallelism;
  under INV.6 each worker replicates Tier-3 state per `docs/parallel-caching.md`.

## 5. The phased plan (queue items INV.0–INV.7)

Not a rewrite — an **inversion of control**, migrating walkers into a single-pass
spine while the corpus suite + `--listAll` byte-diffs + bench TSV pin behavior at
every step. The verification loop is the project's superpower; it is what makes
this safe. Every phase = many small suite-gated commits.

- **INV.0 — Instrument the multiplier.** Per-pass wall-time behind an opt-in flag
  (`pass("name") { … }` wrapper around the init dispatch; accumulator fields
  DECLARED BEFORE `init` per the Kotlin init-order gotcha; mechanical wrap of the
  plain `checkFoo()` lines, manual for conditionals). Counters: getTypeOfExpression
  invocations vs distinct nodes touched; nodeTypes cacheable-vs-bypassed ratio;
  narrowing walks launched per consumer site. Deliverable: a sorted pass-time table
  in a session note = the INV.4 migration worklist + the honest baseline. Gate:
  instrumentation off by default, byte-identical, suite green.
- **INV.1 — Concurrent front-end (the owner's Flow beachhead).** (a) add
  kotlinx-coroutines-core + a behavior-identical sequential-Flow refactor of
  project file loading; (b) IO decode / Default parse via bounded `flatMapMerge`;
  binder stays sequential file-order; (c) determinism verification (corpus + 3×
  listAll byte-diff runs); (d) bench rows (compiler + harness). Expected ~1–2 s on
  big profiles + the structured-concurrency foundation.
- **INV.2 — Bind the world.** Full lexical binding (function bodies, blocks —
  dissolves B83.5), container/parent chain, per-file `nodeId` enabling
  array-indexed side tables (kills a real slice of the `HashMap.getNode` top
  entry; unlocks the file+node-identity memo keying rounds 481–482 were blocked
  on). Scope symbols allocate from a SEPARATE id space to avoid the ~350-test
  boundary reshuffle. Existing walkers keep working (additive tables); their
  scope hacks become deletable in INV.4.
- **INV.3 — Per-file scoping.** Consume the already-built `buildPerFileScopes`;
  module files resolve own-locals + imports + true globals (script files + libs);
  retire the `mergeSymbolTable` conflation for module files; delete the conflation
  ecology walker-by-walker (each deletion suite- and listAll-gated, and each
  removes hot-path checks from `checkMemberAccessMissing`). Also lays the
  cross-file value-resolution groundwork EP.1 (cross-module const-enum inlining)
  needs.
- **INV.4 — Single-pass spine.** `checkSourceFileOnce` per-node dispatch; migrate
  walker families in INV.0's cost order — every migration deletes a full-tree pass
  and its private scope machinery. Once ONE authoritative walk state exists, two
  things become safe that are unsound today: a per-node expression-type cache, and
  folding flow narrowing into reference typing once (collapsing the 70-round
  per-consumer wiring). The long middle; plan as many small items.
- **INV.5 — Canonical types + explicit instantiation.** Intern
  unions/intersections by sorted member-id key (tsc-style; preserves display
  order); replace ambient `currentTypeAliasArgs`/TP-scope with explicit mapper
  objects; cache instantiated members ON the `Type.Reference` (delete
  `resolveGenericPropertyType` fresh-minting and its depth-4 OOM cap); `nodeTypes`
  keyed (node, mapper) — always valid; open `canUseTypeEngine`'s generic gate;
  delete superseded pin walkers.
- **INV.6 — Parallelism.** Share-nothing checker workers per
  `docs/parallel-caching.md` (trivially partitionable once INV.4 gives a per-file
  check entry); parallel emit on Default + IO write sink; deterministic merge via
  the existing diagnostic sort. Structured concurrency from INV.1's foundation.
- **INV.7 — Productization.** Native re-enable (the measured big-input GC
  inversion should largely dissolve once INV.4/5 cut allocation); watch mode
  driven by a file-event Flow; `.tsbuildinfo`-style incremental reuse. (Absorbs
  old M5.5/M5.6.)

## 6. Targets and measurement protocol

*(Rewritten 2026-07-20, round 618, owner-approved. The original targets priced in
the (f1)/(f2) memo+fold wins, which measured as dead-ends at the pre-canonical-types
cost structure (rounds 596/599). The honest wall ledger for the inversion: ~25 s
pre-arc (round 489) → 35.7 s mid-arc peak → ~31 s today — wall-NEGATIVE until the
M0 debt burn-down completes, in exchange for the warm loop (watch incremental
46–157 ms, `.xtsbuildinfo` ~630 ms), the partition seam, one authoritative walk,
and the cache soundness M1 builds on. Round-618 measurements framing the arc: the
JVM flag matrix is a measured dead-end (not GC-bound at 4 g); relations are 0.79 s —
the engine is NOT the bottleneck; HashMap+String-equality ≈ 15% of wall with NO
single hot map; the 440-pass legacy tail (6.2 s) emits NOTHING on the compiler
profile — the corpus is its only pin; Identifier = 44.2% of 858k nodes and a kindId
table kills 88% of dispatch-chain cost.)*

Revised targets (compiler profile = 78 files/195k LOC, cold CLI, single-threaded
unless stated; queue items in PLAN-PHASE-5.md § QUEUE "PERF"):

| checkpoint | target |
|---|---|
| M0 — debt burn-down (tail triage/deletion, kindId dispatch, layout: atoms/links records/open-addressing, scaffolding retirement) | ≤ 24 s (recover, then beat, the round-489 baseline) |
| M1 — identity stability (epoch churn, canonical narrowing outputs, member caching on the Reference) reviving the f1/f2 memo+fold | ≤ 15–20 s |
| M2 — parallel scaling Phase 1 (shared frozen collectors) | ≤ 10–12 s at 4 workers (≈ JS-tsc parity) |

Native is explicitly NOT a perf lever (measured round 610: 196 s debug .kexe;
K/N ≤ JVM for this allocation-heavy workload) — it stays a product/portability
asset. tsgo-class times (2–4 s) are out of scope for this arc. For the edit loop
the perf problem is already largely solved by watch/incremental — these targets
are about the cold full build.

Invariants at EVERY step: corpus suite 100% green; the 8-profile FP floors
unchanged (env-legit only); a bench TSV row per landed item;
diagnostics/emit byte-diffs (`--listAll`, `emit-diff-tsc.sh`) empty vs the
pre-change binary for behavior-preserving steps; wall-clock claims decided by
interleaved A/B medians ONLY — anything priced below the ±2% drift band folds
into a structural item instead of landing alone (the round-618 discipline).
A fresh JFR (or the INV.0 pass table) before and after each structural phase —
the profile shifts after every fix.

## 7. Anti-goals

- **No more micro-opt rounds against the flat profile** — closed as of this
  rescope, unless INV.0 data exposes a genuine ≥5% single lever.
- **No big-bang rewrite / no 1:1 tsc-checker port.** Every broad attempt under the
  exact-baseline gate regressed; the walkers encode 489 rounds of FP-burn-down
  knowledge — migrate them, don't discard them.
- **No shared concurrent maps** (per `docs/parallel-caching.md`, confirmed by
  tsgo's shipped design).
- **No streaming through the checker**; no parallel binding before the id-space
  work; scanner stays UTF-16 `String`-based (positions are baseline-pinned;
  UTF-8-native scanning is at most an INV.7/native-arc idea).
- **Don't touch** Scanner/Parser/Emitter shape (fine, tsc-shaped, invisible in
  every JFR), the flow-walk budgets, or the verification loop itself.

## 8. Relation to the EP milestone

EP.2 (printer formatting) is independent — interleave freely. EP.1 (cross-module
const-enum inlining) gets structurally easier after INV.3's cross-file resolution
work — prefer that ordering. EP.0 (emit-diff dashboard wiring) any time.
