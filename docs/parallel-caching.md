# Cache taxonomy & the parallel-checking plan (M5.4)

*Written 2026-07-07 (rounds 432–434 — renumbered at merge from the branch's original 430–432; main's parallel M3 rounds own those numbers). This is the
design record for how checker caches must be structured so the compiler can go parallel
the way tsgo did — read it before adding a cache, a shared map, or any M5.4 work.*

## The core decision: isolation, not synchronization

tsgo (TypeScript 7 / Corsa) parallelizes by running **N independent checker instances**
(4 by default) over shared **immutable** parse trees and binder output, partitioning
files between them and accepting that some types are computed redundantly in every
checker. It does NOT share its hot type caches across threads — the coherence/locking
cost of sharing fine-grained type facts exceeds the cost of recomputing them. We adopt
the same model. Consequences:

- **Plain `HashMap` stays correct and optimal** for everything owned by one worker.
  There is no need for a concurrent map on any hot path, on any platform.
- The multiplatform "we can't use ConcurrentHashMap in commonMain" constraint is
  therefore a non-problem: the design never wants one where it would matter.

## The three cache tiers

Classify every cache (existing or new) into exactly one tier:

**Tier 1 — derived from immutable program input; deterministic; enumerable domain.**
Examples: `enclosingImportIndex` (eager `val` since round 434 — the reference
implementation of this tier), `moduleStarExportsCache`, `barrelTypeOnlyMemo`,
`crossFileFuncs`. Rule: **build eagerly in a single-threaded phase (field initializer /
init prologue), expose as read-only `Map`, share freely across workers.** No
synchronization is needed for an object published before workers fork (JVM: safe
publication via final field; Native's modern memory model needs nothing; JS/WASM are
single-threaded).

**Tier 2 — worker-local or walk-local mutable state.**
Examples: `moduleSpecifierCache` (its key domain is OPEN — `import("spec")` type
positions and dynamic imports make eager enumeration fragile, so it stays a memo; it is
a `Checker` field, and under share-nothing each worker IS a `Checker` instance →
worker-local by construction), `NarrowSeen`, the flow-walk memo maps,
`currentLocalTypes`, `reassignScanCache` (per-`FlowGraphBuilder` = per-file). Rule:
**plain `HashMap`, never leaked across the worker boundary.**

**Tier 3 — global type-identity state with FIRST-TOUCH semantics. The hard tier.**
Examples: `Type.id` allocation, `getOrInternReference`/`referenceCache`,
`typeParamInternCache`, `symbolTargets` (LinkStore), `aliasDisplayMap`,
`declaredTypes`/`nodeTypes`/`symbolTypes`. CLAUDE.md documents that these are
order-dependent ("resolution context at first touch wins"; id-allocation drift
reshuffles ~350 boundary tests; the round-425 enum-key split was a two-instances-of-one
-thing cache split). Rule: **NEVER share these mutably across threads — replicate them
per worker (tsgo's answer).** A concurrent map here would make first-touch racy →
nondeterministic diagnostics → the byte-identical corpus/baseline verification method
breaks. Worker-local `Type.id`s are fine as long as ids never cross the worker boundary.

## Determinism is a hard requirement

Every verification tool in this repo (corpus baselines, `--listAll` by-code diffs, the
bench TSV FP counts) assumes deterministic output. The parallel design must preserve it:
deterministic file partition across workers + per-worker Tier-3 state + deterministic
merge (the BaselineFormatter's diagnostic sort already provides the merge order).
Any parallel implementation whose output depends on thread scheduling is wrong, full
stop — even if every individual data structure is thread-safe.

## The phased plan for M5.4

1. **Phase 0 — share-nothing (tsgo parity):** one `Checker` per worker over the frozen
   `binderResults`, deterministic partition, deterministic diagnostic merge. No new
   primitives. The LinkStore/`CheckerState` side-table design ("keeps binder output
   immutable for parallel checking") already anticipates this.
2. **Phase 1 — shared frozen lib slice (beats tsgo's redundancy):** tsgo's replicated
   checkers each re-resolve lib.d.ts / @types types redundantly. Those are context-free
   and identical for every worker: resolve them ONCE single-threaded, freeze that slice
   of the type graph, share read-only. Immutability must be proven only for the lib
   slice, not the whole graph. (The M2.1 shared-lib snapshot is the seed.)
3. **Phase 2 — speculative, only after purity refactors:** single-flight sharing of
   expensive PURE computations (materialized utility types, relation verdicts on
   interned pairs). Blocked today by (a) in-place `Type` mutation (lazy member/baseTypes
   resolution, `substituteOuterTypeArgsInGenericFnObject`, side-channel id sets like
   `mappedReadonlyMemberIds`) and (b) ambient-context-dependent resolution
   (`currentTypeParamScope`/`inferenceNamespaceStack`/`currentTypeAliasArgs`). Do not
   attempt before those are made explicit/pure.

## If a shared mutable cache is ever truly unavoidable

In order of preference:
1. **Publish-then-freeze** (turn it into Tier 1).
2. **Copy-on-write snapshot in `kotlin.concurrent.atomics.AtomicReference`**
   (multiplatform stdlib, no dependency) — for read-mostly caches of deterministic
   values; a racy duplicate computation of a pure value is benign.
3. **`expect`/`actual` facade** (~100 lines, no dependency): JVM →
   `java.util.concurrent.ConcurrentHashMap`, Native → mutex/lock-striped or a
   left-right map, JS/WASM → plain `HashMap`. commonMain's no-JVM-API rule bounds APIs,
   not platforms — platform source sets may use platform primitives.

**Evaluated and declined (2026-07-07): CharlieTap/cachemap** (KMP concurrent map,
left-right primitive — wait-free reads, mutex-serialized writes applied twice).
Verdict: right pattern for read-mostly shared maps, but (a) Tier 1 doesn't need
concurrent writes at all, (b) no JS/WASM targets → we'd need the expect/actual seam
anyway (and the JVM actual would be ConcurrentHashMap), (c) dormant single-author lib
(v0.2.4, Dec 2023), (d) no `getOrPut`/single-flight compute-dedup — the operation the
"share checking work" vision actually needs, (e) the real blockers to sharing checking
work are Tier-3 immutability/purity/determinism, which no map library provides. Keep it
as a reference implementation of left-right in Kotlin if a Native `actual` is ever
hand-rolled.

## JFR profiling how-to (the method behind rounds 432–433)

```bash
# 1. Build + classpath (bench script's init-script trick, or reuse build/bench/):
./gradlew compileKotlinJvm
CP="build/classes/kotlin/jvm/main:<jvmRuntimeClasspath>"

# 2. Record (stackdepth matters — checker walks exceed the 64 default; even 1024
#    truncates the deepest flow-walk stacks, losing ROOT attribution — expect a large
#    "?" bucket in caller attribution and treat it as "deep recursion", not noise):
java -Xmx4g -XX:FlightRecorderOptions=stackdepth=1024 \
  -XX:StartFlightRecording=filename=out.jfr,settings=profile \
  -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit <project>

# 3. Aggregate (self/inclusive/by-class + caller attribution):
python3 scripts/aggregate_jfr.py out.jfr 30
```

Notes: `jfr` may not be on PATH — the script resolves it next to `java`. `jfr print`
uses locale decimal commas in sizes. Line numbers in frames are irrelevant here, but
remember stack-trace line numbers from Checker.kt wrap mod 65536 (CLAUDE.md).

## Perf state as of round 434 (branch `perf/flow-import-resolution`)

Machine: Apple Silicon macOS dev box; all noEmit wall, cold CLI, same tsconfigs.

| Workload | tsgo 7.0-dev | tsc 6.0.3 | xtsc (this branch) | xtsc before branch |
|---|---|---|---|---|
| tsc-source `compiler` (78 files, 195k LOC) | 0.94 s | 5.1 s | 19.6 s (56 s CPU) | ~490–593 s |
| zod `packages/zod/src` (107 files, 31k LOC) | 0.52 s | 2.1 s | 3.5 s | ~6–7 s |

What was fixed (byte-identical diagnostics at every step; details in the round 432/433
session notes + CLAUDE.md gotchas): the `resolveAlias`/`findEnclosingImport`
program-wide structural scans (76% of samples) → `enclosingImportIndex`;
`resolveModuleSpecifier` memo; the per-closure B464 reassignment text scan (~14%) →
per-enclosing-function shared scan; the flow walkers' per-branch-antecedent `seen`-set
copies (~11%) → `NarrowSeen` mark/popToMark backtracking.

**Remaining profile is FLAT (top self ≤8%)** — next M5 leads, none dominant:
HashMap churn in the flow-walk memos, `findLocalTypeAlias$scan` (~4%, via
`discUnionParamMembers` — another candidate for a Tier-1 prebuilt index),
`checkMemberAccessMissing` (~3%), `Checker.<init>` walker-pass aggregate. A fresh JFR
pass is mandatory before the next optimization — the profile shifts after every fix
(round 433's targets were invisible before round 432 landed).

Caveat when comparing against tsc/tsgo: the workload is not semantically identical —
xtsc's checker is incomplete (M3 gaps → less relation work) but runs hundreds of
dedicated walkers tsc doesn't (more tree passes) and currently emits FPs (1,148 on the
compiler profile). Killing FPs is itself the biggest perf lever (see the Phase 17
principles in PLAN-PHASE-5.md).
