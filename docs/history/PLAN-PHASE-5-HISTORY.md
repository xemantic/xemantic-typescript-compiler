**Round 493 (2026-07-13, same session as 492) — INV.1(b)+(c)+(d): the concurrent
front-end landed.** The crawl's per-file work now runs CONCURRENTLY per frontier:
read + UTF-8→UTF-16 decode on `Dispatchers.IO` (new `pipelineIoDispatcher`
expect/actual in PipelineRunner — `Dispatchers.IO` does not exist in common
code), the specifier-extraction parse on `Dispatchers.Default`, under a bounded
`flatMapMerge(16)` (`readAndScanBatch`, results re-ordered to INPUT order, one
entry per occurrence; note `flatMapMerge` is `@ExperimentalCoroutinesApi` in
coroutines 1.11.0, not `@FlowPreview` as older docs suggest). Specifier
RESOLUTION + emission stay sequential per frontier (a frontier-level barrier),
so emission order remains first-discovery order — the binder/symbol-id contract
from (a) holds by construction. Parser concurrency audit (the
ARCHITECTURE-RETHINK § 4 precondition): no top-level/companion mutable state in
Parser/Scanner/Ast — only immutable keyword sets; `internSalt` is per-file pure.
Observable parity with the sequential crawl for a static-during-crawl Vfs; only
read COUNTS differ (a multiply-discovered UNREADABLE path probes once per
frontier, not once per discovery). **Verification:** suite green 10,206 → 10,212
(+6 local `Inv1ConcurrentCrawlTest`: wider-than-the-bound frontier order (30
imports vs concurrency 16), cross-frontier first-discovery positions, deep-chain
BFS, resolvable-but-unreadable discovered file skipped (NOT unresolved) with
siblings intact, unreadable SEED enters as "", 3× build determinism); (c) 3×
`--listAll` runs byte-identical to each other AND to the round-492 BEFORE binary
(46 diagnostics incl. chains). **Measurement — the false-regression trap:** the
initial batch-then-batch wall-clock read showed AFTER ~0.7 s SLOWER (+4–6 s
user), and a `-Dkotlinx.coroutines.default.parallelism=1` probe ruled out
concurrent-parse GC pressure — the BOX had drifted ~2 s slower across the
session (the same BEFORE binary: 24.6 s at session start, 26.1–27.1 s two hours
later). An INTERLEAVED A/B (B/A pairs ×3, daemons stopped) gives the valid
read: **BEFORE median 26,804 ms vs AFTER 25,976 ms (−0.8 s, ~3%), every A run
beating its adjacent B runs** — consistent with parallelizing the ~1–1.5 s
extraction-parse leg on 4 cores; the front-end win grows with profile size and
on the 500k-LOC horizon. Bench TSV row appended (d): 24,846 ms self / 934 MB /
46 errors / 78 emitted — an EMIT run (the `+1.3%` vs-previous line compares
against a `--noEmit` row, so it understates; the interleaved A/B above is the
valid perf read). **NEXT (queued as
INV.1(e)):** reuse the crawl's parses in `compileParsed` to kill the double
parse — NOT a drop-in: the core's three parse sites pass option-derived parser
flags (`forceJsx` / `topLevelAwait` / `needsJsxFlag` / `noImplicitAny`) that
change the tree, so the crawl must parse with the RESOLVED options (available —
the tsconfig loads before the crawl) and `ParsedSource` must grow a
pre-parsed-files channel.


**Round 492 (2026-07-13, same session as 491) — INV.1(a): the sequential-Flow
beachhead landed.** kotlinx-coroutines-core was ALREADY a commonMain dependency
(only the unused CheckerPool consumed it), so (a) reduced to the seam: new
`runCompilerPipeline` expect/actual (JVM = `runBlocking`; common code cannot call
runBlocking — a future JS target needs an async driver, pipeline sequential there
anyway) + ProjectCompiler's import-graph crawl rewritten as a cold
`Flow<Pair<path, content>>` (`crawlImportGraph`) collected through the seam —
sequential and behavior-identical (seeds in seed order, read unconditionally;
BFS discovery order; duplicate-seed re-reads preserved; unreadable discovered
files skipped). The EMISSION-order contract is documented at the flow: emission
order becomes the binder's file order → global symbol-id allocation (the
documented ~350-test reshuffle hazard) — INV.1(b)'s concurrency must keep
emission DETERMINISTIC (e.g. frontier-level barriers), never completion-ordered.
**Verification:** suite green 10,203 → 10,206 (+3 local
`Inv1SequentialCrawlOrderTest`: diamond-graph seed-then-BFS order with
first-discovery dedup, unresolved `(importer, specifier)` attribution,
run-to-run determinism — the INV.1(b) invariant); compiler-profile `--listAll`
A/B vs the INV.0-only binary byte-identical at wall parity; the round-491
"clean re-run" bench row (24,525 ms self, `+dirty`) was in fact built WITH this
refactor — an accidental whole-profile smoke A/B at diagnostic (46) and wall
parity. **Bench-row hygiene:** the FIRST round-491 TSV row (80,122 ms) is
swap-polluted — the bench script's own gradle build leaves BOTH daemons resident
on the 7.7 GB box and the -Xmx4g run swaps (the documented memory trap; the
historical ~28–29.5 s TSV band carries mild daemon overhead, this session's
full-suite daemon made it catastrophic); always `./gradlew --stop && pkill -9 -f
'KotlinCompile[D]aemon'` before bench runs whose numbers will be read — the
corrective row's label documents the pollution. **NEXT — INV.1(b):** IO decode /
Default parse via bounded `flatMapMerge`; note for its design: the crawl's
`extractSpecifiers` already FULL-PARSES every file and `compileParsed` parses
everything a second time — the parallel-parse step should also evaluate reusing
the crawl's parses (kills the double parse; changes `ParsedSource`'s input
shape, scope it separately).


**Round 491 (2026-07-13) — INV.0 LANDED: the pass multiplier is instrumented and
measured (opt-in, byte-identical off).** First item of the inversion arc. Landed:
`PassTiming.kt` (off-by-default singleton + a top-level NON-inline `pass(name) {}`
wrapper — non-inline deliberately: ~514 inline expansions of the try/finally +
time-mark body would push the constructor toward the JVM 64 KB method limit), a
mechanical bytes-mode rewrite of the init dispatch (all 513 whole-line zero-arg
dispatch calls + 1 single-line if-call → `pass("checkFoo") { checkFoo() }`; all
names unique so accumulation is 1:1), counter hooks (each additive-only behind
`if (PassTiming.enabled)`): `getTypeOfExpression` invocations + approx-distinct
nodes (pos<<32|end keys — cross-file collisions UNDERcount distinct, so the
recompute factor is slightly OVERstated; labeled `~` in the dump) with per-pass
attribution via `PassTiming.currentPass`, `getTypeFromTypeNode` cacheable vs
bypassed vs hit, and depth-0 flow walks at the `flowWalkWithTripCheck` choke
point; `--passTiming` CLI flag (reset + enable before build, sorted table after).
**Verification:** suite green 10,196 → 10,203 (+7 local `Inv0PassTimingTest`:
on/off diagnostic parity on a TS2322-emitting probe, disabled-run-records-nothing
negative control — which caught noteInitStart/End recording unconditionally
pre-commit — accumulation, per-pass attribution incl. nested + throwing bodies,
dump format); `--listAll` A/B compiler profile vs a stash-built BEFORE binary:
byte-identical (46 lines incl. chains) at wall parity (24.51 vs 24.53 s);
instrumented run +~2% (25.07 s).
**THE TABLE (compiler profile, --noEmit --passTiming, daemons stopped):**
checker-init 20,846.7 ms of 25,071 ms wall (83%); 496 passes ran, sum 20,009 ms,
outside-pass (setup/merges/eager indexes) only 837.5 ms. Concentration: top-1 =
19.0%, top-3 = 38.6%, top-10 = 54.2%, top-25 = 64.9%, top-50 = 75.3%; median pass
1.6 ms; **474 passes under 100 ms sum to 7,297 ms (36.5%)** — the pure
pass-multiplication tail (each is a full-program walk for a small check). Top 15:
```
      ms   typeOfExpr  narrowWalks  pass
  3788.9       255192        57282  checkPropertyAccess
  2195.4        87225        10508  checkTypeAssignability
  1727.5       115956        16211  checkCallExpressionTypes
   845.6            0            0  checkUnresolvedNames
   733.7            0            0  checkTypeUsedAsValue
   506.1        37188           90  checkUncalledFunctionsInConditions
   289.6            0            0  checkAbstractClassInstantiation
   271.2        69024           61  checkArithmeticOperandTypes
   242.5            0            0  checkDuplicateIdentifiers
   228.0            0            0  checkDefiniteAssignment
   218.0         2756          124  checkImplicitReturns
   204.7         9948            0  checkImplicitAnyParameters
   196.9            0            0  checkArgumentCounts
   191.3          103            0  checkFnTypedParamCalls
   169.9           66            0  checkObjectSpreadInvalidTypes
```
**Counters:** `getTypeOfExpression` 594,779 calls over ~221,844 distinct nodes —
×2.6 program-wide recompute, and the top-3 passes ALONE make 458k calls (each
independently re-types overlapping expression sets with its own scope machinery);
`getTypeFromTypeNode` 255,019 cacheable (77% hit) vs 47,946 bypassed (15.8% — the
compiler profile; expect higher inside generic-heavy services) = ~107k full
annotation re-resolutions; flow-narrowing walks 84,469 — checkPropertyAccess
launches 57,282 of them (68%), each a fresh CFG traversal with per-walk memos.
**INV.4 migration worklist (this table, cost order):** (1) the property-access
family (3.8 s, the top walker + top narrower), (2) assignability (2.2 s), (3)
call-types (1.7 s) — these three are the spine candidates where one shared walk +
one narrowing consult per reference collapses 7.7 s (38.6%); (4) the
name-resolution pair checkUnresolvedNames + checkTypeUsedAsValue (1.6 s combined,
zero expression typing — pure walk cost); then the sub-100 ms tail wholesale
(7.3 s of walk overhead that becomes per-node cases in a single dispatch).
Instrumentation invariants recorded as a CLAUDE.md gotcha (wrap new init passes;
hooks stay additive-only; `pass` stays non-inline).

**Round 490 (2026-07-13) — the architecture analysis behind the rescope (no code
change).** Verified in-code: Checker.init dispatches ~512 passes (523 call sites,
1,700 lines); 575 `for (result in binderResults)` loops; 1,005 `check*` functions;
`getTypeOfExpression` (321 call sites) has NO cache; `nodeTypes` bypassed whenever
any resolution context is active (Checker.kt ~93091); unions un-interned;
`resolveGenericPropertyType` depth-capped at 4 to avoid OOM. Cross-checked tsc
(single-walk pull-based checker, NodeLinks/SymbolLinks memoization, interned
unions/instantiations, mapper-based instantiation, flow type cached per reference)
and tsgo 7.0 RC (native + parallel parse/bind/emit + 4 share-nothing checker
workers, `--checkers`; matches docs/parallel-caching.md). Key comparison: tsc does
MORE semantic work per node yet runs the compiler profile in 10.2 s vs our ~25 s —
the walker architecture, not micro-inefficiency, is the bottleneck (rounds 432–434's
30× from structural fixes vs rounds 482–489's ~10% combined from micro-opts confirm
the response curve). Deliverables: `docs/ARCHITECTURE-RETHINK.md` + this queue
restructure + CLAUDE.md mission pointer.

**Round 489 (2026-07-12) — M5.1: two byte-identical per-property-access hot-path
reductions from a fresh JFR (~2.9% wall-clock).** Mandatory fresh compiler-profile JFR
(25.5 s / 1,961 samples, post-488): `checkMemberAccessMissing` at the clear top (5.7%
self / 6.8% incl, under `checkPropertyAccessInExpr` 8.8% → `checkSinglePropertyAccess`
8.1%), with `emitTs18048ForClosureCapturedUndefinedReceiver` (1.4% self) and
`narrowTypeFromFlow` (1.3% self) both running per-access. Two commits, each byte-identical
(compiler / services / harness `--listAll` diffs empty vs a stash-built BEFORE binary —
46 / 46 / 95; the compiler profile exercises the round-418 `isTupleType` narrow-DOWN path
via checker.ts, and the full corpus suite pins the narrowing corner cases):
- **Fix 1 — closure-scan skip for concrete receivers** (`34d9798c`):
  `emitTs18048ForClosureCapturedUndefinedReceiver` runs for EVERY property-access with an
  Identifier receiver and scanned ALL of the flow graph's `closureStarts` (hundreds on a
  big source like checker.ts) to find the innermost lexically-containing closure BEFORE
  resolving the receiver type. The emitter only ever fires when the (narrowed) receiver
  type is a union containing `undefined`, and `getNarrowedTypeForReferenceFollowLoopEntry`
  only ever SUBSETS the raw type, so resolve the type first and bail before the
  O(closureStarts) scan whenever `raw` is concrete (not a `T | undefined` union). The
  captured-`var` case resolves to `anyType` (B467, recovered below via the closure's
  `enclosingVarDecls`), so `anyType` must NOT bail — it needs the closure.
- **Fix 2 — narrow-DOWN walks skipped when the property is present** (`3d9aee21`): the
  round-418 single-type narrow-DOWN suppression (`checkMemberAccessMissing`) ran TWO
  flow-narrowing walks (`getNarrowedTypeForReference` +
  `getNarrowedTypeForReferenceFollowLoopEntry`) for every concrete non-union receiver. Its
  ONLY purpose is to suppress a would-be TS2339 when `raw` LACKS the property but a
  narrowed strict subtype HAS it — so if `raw` already resolves the property (on itself or
  its apparent type, exactly what the tail-of-function main check consults for a concrete
  non-union receiver, where `objectType == raw`), no TS2339 can fire and both walks are
  pure waste. Gate them on `getPropertyOfType(raw / apparent, propName) == null`.
- **Verification:** both byte-identical on compiler / services / harness (`--listAll`
  diffs empty). AFTER JFR: the closure emitter drops out of the top-16 self-time,
  `narrowTypeFromFlow` 26 → 18 samples, `checkMemberAccessMissing` self 111 → 97. Full
  corpus suite green 10,190 → 10,196 (+6 local across 2 files:
  `Round489ClosureConcreteReceiverFastBailTest` — concrete bails, undefined-union still
  fires, inner guard still suppresses; `Round489MemberPresentNarrowGateTest` —
  present-on-declared no error under active narrowing, subtype-only still suppressed,
  genuinely-missing still fires; 0 regressions). Clean same-machine wall-clock A/B (daemon
  stopped, `pkill KotlinCompile[D]aemon`, ≥4.9 GB free, 4 runs each, self `time:`): BEFORE
  (round 488) median 25.5 s (band 24.8–26.2) → AFTER 24.76 s (band 24.5–25.1) ≈ **2.9%**,
  with the AFTER runs also visibly tighter. Recordings `$SCRATCH/r489-compiler.jfr`
  (before) + `$SCRATCH/r489-after.jfr` (after), session-local.
- **INVARIANT:** the fix-2 gate is sound ONLY because the round-418 block handles the
  concrete non-union receiver, where the main check's `objectType == raw` and every
  downstream emitter gates on `getPropertyOfType(objectType, …) == null`. Do NOT extend the
  gate to union / `this` receivers — they have their own separate narrowing paths and
  emission logic.
- **NEXT M5 lead (unchanged):** `checkMemberAccessMissing` remains the top single WALKER.
  This round shaved its per-access work at the entry (skipping the two narrow-DOWN walks
  for the property-present case); the bigger remaining lever is a broader member-present
  common-case early-out at the very top of the function (before the conflation / leak /
  shadow suppression pre-checks), but it is a 1,965-line correctness-critical fn and the
  suppression pre-checks are gated so they are cheap when their program-wide conditions are
  empty (the compiler profile has ZERO conflated aliases, so that block is already skipped
  there — the lever is bigger on services / harness). Attempt with the `--listAll` byte gate
  on all three profiles + the full suite, and decompose carefully.

**Round 488 (2026-07-12) — M5.1/M5.2: three byte-identical hot-path allocation /
map-lookup reductions from a fresh JFR (~2.1% wall-clock).** Mandatory fresh
compiler-profile JFR (26.4 s / 2,014 samples, post-487): with the scope-name-set
copy family cleared, the top self-time was `HashMap.getNode` (5.5%, scattered across
resolveFlowCalleeDecl/aliasedConditionInitializer/getTypeOfSymbol/getTypeOfIdentifier/
isOptionalProperty), `checkMemberAccessMissing` (5.3% self / 7.0% incl — the top
walker), then the HashMap/HashSet put/copy family (`putVal` 3.3%, `putMapEntries` 2.4%,
`HashSet.add` 4.1% incl). Three commits, each byte-identical (compiler-profile 46
diagnostics, `--listAll` diff empty vs a stash-built BEFORE binary):
- **Fix 1 — `getUnionType` tiny-input fast paths** (`6258836b`): the general path
  allocates 4 intermediate lists + 1 HashSet per call; the dominant inputs are size-1
  and size-2 (the pervasive `T | undefined`, `??` results, nullable narrowing). Added
  size-1 / size-2 fast paths that skip flatten/filter/dedup/sort when no member is a
  nested union — preserving the stable sort-by-flags-value, never filtering, any
  absorption, and id dedup exactly. +4 local `GetUnionTypeFastPathTest` (observable via
  inferred array-element unions — the target side of a mismatch renders annotations
  syntactically, bypassing getUnionType).
- **Fix 2 — `isOptionalProperty` reorder** (`3329561c`): tested the declaration-less
  tuple-member side set (`optionalTupleMemberIds`) FIRST on every call. Only
  `buildTupleFromTypes`' synthetic declaration-less symbols are ever in that set and
  their globally-unique ids can never collide with a declared symbol's, so check the
  declaration path first (the overwhelming majority) and hit the set only for a
  declaration-less symbol. +3 local `IsOptionalPropertyReorderTest` (both branches +
  negative). **INVARIANT for future agents: never add a declaration-BEARING symbol's id
  to `optionalTupleMemberIds` — the reorder assumes the set holds only declaration-less
  tuple members.**
- **Fix 3 — single-lookup flow-callee cache** (`3329561c`, same commit): `resolveFlowCalleeDecl`
  did `containsKey(key)` then `[key]` (two lookups per cached hit); resolved callees are
  usually non-null, so a single `get()` covers the common path, falling back to
  `containsKey` only to disambiguate a legitimately-cached null. Mirrors round 483's
  resolveModuleSpecifier single-lookup.
- **Fix 4 — `checkMultiBaseInStatement` occOf scan skip** (`3c40908e`): the TS2320
  same-generic-base check ran `occOf` (an O(source) `<`-bracket-match scan) for every base
  of a 2+-base interface, then grouped by name. Only a RECURRING base name can produce
  TS2320, so group by the cheap base name first and run occOf only for names appearing 2+
  times — the common `interface X extends A, B` case (distinct names, pervasive in tsc's
  Node hierarchy) skips the scan. `occOf`'s lambda was the #6 self-time frame (13 samples).
  +3 local `MultiBaseTs2320OccOfTest`.
- **Verification:** all byte-identical (46-diagnostic `--listAll` diff empty); full corpus
  suite green 10,180 → 10,190 (+10 local across 3 test files, 0 regressions). Clean
  same-machine wall-clock A/B (daemon stopped, `pkill KotlinCompile[D]aemon`, 4.7 GB free,
  3 runs each, self-reported `time:`): BEFORE (round 487 fd4769c2) median 25.70 s vs AFTER
  (this session) 25.17 s ≈ **2.1%** (best-case 24.90 vs 25.70 ≈ 3.1%), consistent across
  runs. A modest real win from cutting per-call allocations + map lookups on the
  property-access / flow-narrowing / union-construction hot paths.
- **NEXT M5 lead (unchanged):** `checkMemberAccessMissing` (5.3% self / 7.0% incl) remains
  the top single WALKER — it runs for EVERY property access and does heavy eager narrowing/
  type-resolution work (getTypeOfExpression + getNarrowedTypeForReference + checkTypeRelatedTo
  in several suppression blocks) BEFORE determining whether the member is even missing.
  A common-case early-out (member present on the receiver's apparent type → return before
  the flow-suppression blocks) is the biggest remaining lever, but it's a 700+-line
  correctness-critical function — attempt with the `--listAll` byte gate + full suite, and
  decompose carefully. Also standing: the `checkFunctionBody` per-body scope-map copies
  (`putMapEntries`, ~15 samples — currentLocalTypes/currentLocalDeclTypeNodes/currentShadowedNames
  copied O(scope size) at every nested function; a layered/copy-on-write redesign like
  ScopeNameSet would eliminate it but touches many read/write sites).

**Round 487 (2026-07-12) — M5.1/M5.2: eliminate the scope-name-set COPY in the
type-as-value + expando walkers (two byte-identical commits, ~2.1% wall-clock).**
Commits `c580231a` (type-as-value) + `250be2a7` (expando). A mandatory fresh
compiler-profile JFR (26.8 s / 2,044 samples, post-486 flat profile) put the
type-as-value (TS2693/TS2708) + expando (TS2339) walker family at the TOP of the
remaining allocation churn — `--callers-of HashSet.<init>` and `AbstractCollection.addAll`
both → `checkTypeAsValueInStatement` (38) / `checkTypeAsValueInStatements` (27-28) /
`visitExpandoStmt` (21) / `checkTypeAsValueInExpr` (8); `--callers-of HashMap.put` the
same four (61 of 232 samples ≈ 26%). Root cause: round 486 converted these copies from
LinkedHashSet to HashSet (removed `afterNodeInsertion`) but the COPY itself remained —
each nested function/class/method copies the enclosing scope's file-level name sets via
`HashSet(parent)`, and on tsc's own checker.ts (one `createTypeChecker`, hundreds of
nested functions, a ~1000-name file-level `typeOnlyNames`) that is quadratic copying.
- **Fix 1 (type-as-value):** a two-level `ScopeNameSet` — a shared, never-copied
  file-level BASE plus a small per-scope OVERLAY that `child()` copies alone. Membership
  is `base∪overlay` (depth-independent, ≤2 lookups; `if (overlay.isEmpty()) name in base`
  fast path for the common file-level case), NOT a parent-chain walk. Two facts the walker
  already had make this exact: every type-only / namespace-only read is value-gated
  (`name !in valueNames && name in typeOnlyNames` — enumerated all 6 read sites) and every
  scope grows the sets purely additively inward, so the former per-scope `remove` (a param
  or namespace self-name shadowing an outer type name) is subsumed by the value overlay —
  the structure is add / contains only. `addParamBindingNamesToValues` (add-and-remove) is
  replaced by a generic `forEachParamBindingName(name) { … }` visitor used by both the
  plain-set hoisting collector and the scope-set callers.
- **Fix 2 (expando):** the expando `shadowed` set is DIFFERENT — its base is EMPTY and it
  only accumulates each nested function's own locals, and it is read RARELY (only for a
  property access whose receiver is a top-level expando candidate). So a base+overlay does
  not help (the whole set is overlay → `child()` still copies it); a parent CHAIN does:
  `ChainedNameSet.child(locals)` links a new layer WITHOUT copying the ancestors, and
  `contains` walks the chain (cheap given the rare checks). `collectExpandoFnLocals` now
  returns `MutableSet` so the FunctionExpression case can add its own name to the fresh
  layer. `ChainedNameSet.EMPTY` (companion val — not an instance field, so the init-order
  gotcha does not apply) seeds the top-level walk.
- **Verification:** BOTH commits byte-identical (compiler-profile 46 diagnostics,
  per-position `--listAll` diff empty vs a stash-built BEFORE binary). Full corpus suite
  green 10,173 → 10,180 (+7 local `ScopeNameSetLayeringTest`). Clean same-machine
  wall-clock A/B (daemon stopped, `pkill KotlinCompile[D]aemon`, ≥5 GB free, 3 runs each,
  self-reported `time:`; commit 1): BEFORE (HashSet copy) median 26.23 s vs AFTER
  (ScopeNameSet) 25.67 s — ~2.1% (best-case 26.17 → 25.37 = 3.0%). A REAL wall-clock win,
  unlike round 486's neutral LinkedHashSet swap, because the COPY is eliminated (not just
  its per-element overhead). Commit 2 (expando) is a ~1% allocation contributor → sub-noise
  wall-clock, reported as allocation discipline. AFTER JFR (both commits): the whole family
  is gone from the top self-time — `checkTypeAsValueInStatement` (was 5.1% self, #2) and
  `LinkedHashMap.afterNodeInsertion` (2.3%) no longer in the top-16; `HashMap.put` 3.7% →
  2.4%; run wall 26.8 → 25.9 s. Recordings `$SCRATCH/r487-compiler.jfr` (before) +
  `$SCRATCH/r487-after.jfr` (after), session-local.
- **NEXT M5 lead (fresh JFR):** with the set-copy family cleared, the top self-time is now
  `HashMap.getNode` (5.5% — scattered: `aliasedConditionInitializer` / `isOptionalProperty`
  / `getTypeFromTypeNode` / `getTypeOfIdentifier`) and `checkMemberAccessMissing` (4.6%
  self / 6.3% incl, the top WALKER, under `checkSinglePropertyAccess` →
  `checkPropertyAccessInExpr` 8.4% incl). Audit `checkMemberAccessMissing`'s per-access
  work for a real wall-clock lever (the round-486 NEXT, still standing).

**Round 486 (2026-07-12) — M5.1/M5.2 allocation discipline: HashSet for per-scope
name-set copies in the type-as-value + expando walkers (byte-identical).** Commit
`9ec344e6`. A fresh compiler-profile JFR (28 s / 2,097 samples, post-483 flat profile)
put the `checkTypeAsValue*`/`visitExpando*` walker family at the top of the
set-allocation churn: `--callers-of SetsKt___SetsKt.plus` → `checkTypeAsValueInStatements`
(20) / `visitExpandoStmt` (13) / `visitExpandoExpr` (6); `--callers-of
AbstractCollection.addAll` the same three. Root cause: those walkers copy the enclosing
scope's name sets (`typeOnlyNames`/`valueNames`/`namespaceOnlyNames`/expando `shadowed`)
at EVERY nested function/arrow/class so a child scope can add its own names without
mutating the parent, and the copies were `.toMutableSet()` / `Set.plus` — both return a
`LinkedHashSet` (per-element `afterNodeInsertion` + an insertion-ordered linked list),
pure overhead because these sets are membership-only (verified: zero
`.joinToString/.sorted/.first/.forEach/.map/.iterator/…` on them file-wide). On tsc's
own checker.ts (one `createTypeChecker`, hundreds of nested functions, a large
accumulated name set) that is quadratic LinkedHashSet churn.
- **Fix:** convert all 15 `.toMutableSet()` + the 4 `Set.plus` copies in the family to
  plain `HashSet(...)` / `HashSet(a).also { it.addAll(b) }` (the three `typeOnlyNames`/
  `valueNames`/`namespaceOnlyNames.toMutableSet()` strings are UNIQUE to this walker
  family per a whole-file grep, so `replace_all` was confined; mirrors round 483 change
  1's rationale for the per-function-body scope maps).
- **Verification:** compiler-profile diagnostics byte-identical (46, per-position diff
  of sorted `error TS` lines empty vs pre-change); full corpus suite green 10,167 →
  10,171 (+4 local `NestedScopeNameSetPropagationTest` — TS2693 fires three functions
  deep (typeOnlyNames propagated), a param that shadows a type name as a value
  propagates into a nested fn (no TS2693), a nested-fn read of an undeclared expando
  prop fires TS2339 while a declared one does not, and a nested-fn param shadowing the
  expando base suppresses TS2339). Fresh AFTER JFR: `SetsKt.plus` (3.2%) and
  `LinkedHashSet.<init>` (2.4%) GONE from the top-90 (~5.6% of samples redistributed to
  cheaper HashSet ops). **Clean A/B (daemon stopped, 3 runs each, self-reported `time:`):
  BEFORE (LinkedHashSet) 25.35/25.77/25.43 s vs AFTER (HashSet) 25.35/25.81/25.97 s —
  WALL-CLOCK-NEUTRAL, within the ~2% box-noise band.** Honest read: this is
  allocation-discipline hygiene (GC pressure + correct data structure), NOT a wall-clock
  win — the compiler profile's remaining wall-clock cost is elsewhere
  (`checkMemberAccessMissing` 5.1% self / 6.5% inclusive, the biggest walker;
  `narrowTypeFromFlow` + `applyConditionNarrowing` flow narrowing; `getTypeOfExpression`).
  Recording: `$SCRATCH/r486-compiler.jfr` (before) + `$SCRATCH/r486-after.jfr` (after),
  session-local. **NEXT M5 lead (fresh JFR):** `checkMemberAccessMissing` is now the
  clear top walker (5.1% self, 6.5% inclusive; `checkPropertyAccessInExpr` 8.8% inclusive
  → `checkSinglePropertyAccess` 7.5% → `checkMemberAccessMissing`) — audit its per-access
  work for a real wall-clock lever, not just allocation churn.
- **Fix 2 (commit `fe01237d`) — memoize `getLineAndCharacterOfPosition` (a
  reduce-redundant-WORK lever, not allocation):** it was an O(position) linear newline
  scan from index 0 on EVERY call — on tsc's ~1.5 MB checker.ts a position near the end
  is ~1.5 M char comparisons per call, run per-diagnostic + in several walker position
  computations (0.9% self / 1.9% inclusive). Build a per-source line-start offset table
  once (memoized by the stable `sourceFile.text` String — JVM String.hashCode cached +
  equals short-circuits on identity → O(1) lookups after the first per file) and
  binary-search the greatest offset ≤ min(position, len). Byte-for-byte equivalent
  result (line/col unchanged for all 46 diagnostics — the sorted diff is empty).
  `lineStartsCache` declared before `init` (the function runs during init via diagnostic
  emission). **Same-session A/B (daemon stopped, 4 runs, self `time:`): BEFORE (linear)
  median 26.75 s vs AFTER (memo) median 26.41 s — ~1.3% faster, matching the ~0.9% self
  this held.** +2 local `LineAndCharacterMemoTest` (offset-independent: two identical
  errors N lines apart → exactly an N-line gap + identical column, deep into a large
  source). Suite 10,171 → 10,173.
- **Also this session (commit `724fa2bb`) — restored the warning-clean invariant:**
  round 484 flagged 5 drifted `Checker.kt` compiler warnings; all fixed (redundant
  `?.`/cast/`else`, each verified to drop no load-bearing smart-cast or side effect —
  e.g. 129367/129374's `when (arg)` blocks are exhaustive because the function opens
  with `if (arg !is ArrowFunction && arg !is FunctionExpression) return false`).
  `compileKotlinJvm compileTestKotlinJvm --rerun-tasks` is 0-`w:` again; diagnostics
  byte-identical; suite green.

**Round 485 (2026-07-12) — CI perf/compliance dashboard: `Bench` GitHub Action
(owner-requested).** New `.github/workflows/bench.yml` + `scripts/bench-3way.sh`
compile the pinned TypeScript `compiler` profile with xtsc, reference JS tsc, and
native tsgo, then publish a per-run Markdown report under `bench-history/runs/` and
prepend a row to `bench-history/README.md` (index, newest-first) so wall-clock /
throughput / error trends are observable across commits. Trigger: push-to-main
(owner's choice) + `workflow_dispatch` (tsc/tsgo npm specs are inputs, default
`typescript@6` — the released JS line; 7.0 is native tsgo — / `@typescript/native-preview@latest`;
report records resolved versions). Runner: JDK 26 (temurin, setup-java@v5) so the CI
numbers match the JDK-26 dev box; action majors current (checkout@v7, setup-gradle@v6,
setup-node@v6/Node 22). Loop-guarded: `paths-ignore: bench-history/**` + the bot's result commit
is `[skip ci]` + pushes `HEAD:main` with rebase-retry. `bench-history/` is a NEW
tracked dir (the existing `/bench/` is gitignored machine-local TSV). Gotchas
hit + fixed while building: an UNQUOTED python heredoc ran every backtick in the
Markdown as command substitution (→ quoted `<<'PYEOF'` + values via `export`/`os.environ`);
`git diff --quiet` misses the untracked new report (→ `git add` then `--cached`);
tsgo `--version` is "Version X" (→ `awk '{print $NF}'`). Local macOS validation
(busy box): xtsc 23.7s/46 vs tsc@6.0.3 6.5s/65 vs tsgo@7.0-dev 1.35s/65 — CI on
Linux GNU-grep gets real self/err too. NEXT: the EP.2/EP.1 emit-parity families, or
resume M5.

Also this session (owner-requested build-tooling check): **Gradle 9.5.1 → 9.6.1**
(wrapper bumped, `compileKotlinJvm`+`compileTestKotlinJvm` and the full suite green
10,167/0 — committed; build-tool only, no xtsc-runtime effect). **javaTarget 17 → 26
experiment — MEASURED, NOT committed.** Target 26 compiles under Kotlin 2.4 (jvmTarget
26 supported) and the dev box already RUNS on JDK 26, so the runtime JIT/GC of 26 is
already in every bench number — a *bytecode-target* bump changes the class-file
version + min-JDK, not runtime speed. A/B self-compile (3 runs each, JDK 26 both):
target26 median 23.3s vs target17 median 25.8s (~10% apparent) — but 3 noisy samples
on a busy box measured sequentially (26 first), so box-load drift dominates and a
target-only bump rarely moves runtime >1–2%; treat as inconclusive/likely noise.
DECISION: keep javaTarget=17 — this artifact is published to Maven Central as a
multiplatform LIBRARY, and min-JDK 26 (non-LTS) would exclude ~all consumers (17/21/25
LTS) + break the reusable CI workflow + bench.yml's JDK 21. Revisit only if xtsc ships
as a standalone bundled-JRE binary (min-JDK moot) AND the gain is confirmed on a quiet
box / warm BenchMain.

**Round 484 (2026-07-12) — EP emit parity: three-way bench + emit diff + EP.3 landed
(owner-authorized "output parity, including reported errors").**
- **Three-way bench** (`compiler` profile, 78 files / 194,702 LOC, cold wall, emit): xtsc
  26,893 ms (self 26,769) vs JS `tsc@6.0.3` 10,161 ms (median of 3) vs `tsgo@7.0-dev`
  2,124 ms. xtsc ≈2.6× behind JS tsc, ≈12.7× behind tsgo — the M5 frontier. All three
  agree diagnostically: only env-legit offline `@types/node` errors (tsc/tsgo 65, xtsc
  46 — xtsc suppresses more of the same family), zero real FPs, 78/78 emitted.
- **Emit-byte diff** (new `scripts/emit-diff-tsc.sh`, xtsc vs `tsc@6.0.3`, SEPARATE
  outDirs): 8/78 byte-identical, 70/78 differ — but NONE are miscompiles (xtsc output is
  runnable). Three systematic families explain nearly all changed lines: (1) cross-module
  const-enum inlining — xtsc keeps `mod.Enum.Member`, tsc inlines `VALUE /* Enum.Member */`
  (xtsc inlines 8,695 reads, tsc 18,118 — the ~9,400 gap is cross-module; utilities.js
  3,091→225 residual once normalized); (2) multi-line expression printer formatting
  (operator/`:` line-end vs line-start); (3) `||=`/`&&=`/`??=` not downleveled at es2020
  (xtsc 299 vs tsc 15). Version confound noted (npm tsc ≠ pinned commit; the 3 families
  are version-stable, the small emitHelpers.js residual is version noise).
- **EP.3 landed** — `Transformer.downlevelLogicalAssignment` (gate `effectiveTarget <
  ES2021` in the binary-spine collector + `transformBinaryExpressionSpecial` dispatch):
  `a ||= b` → `a || (a = b)`, `&&=`/`??=` likewise; side-effecting property/element
  receivers captured into temps with tsc-faithful naming (`(_a = obj())[_b = key()] ||
  (_a[_b] = 6)` — the element KEY capture is bare inside `[]`, only the receiver is
  parenthesized). Corpus has ZERO logical-assign files → pinned by the new
  `LogicalAssignmentDownlevelTest` (7 cases). Known residual: sub-ES2020 `??=` keeps a
  native `??`. Full suite green 10,167 / 0 (was 10,160 + 7 local).
- Queue: added the **EP milestone** (EP.3 done; EP.2 printer formatting, EP.1
  cross-module const-enum, EP.0 dashboard-wire the gate — sequenced cheap-first). Pre-
  existing (not this change): 5 `Checker.kt` compiler warnings on HEAD — the
  "warning-clean" invariant has drifted; flagged for a separate cleanup.

**Round 483 (2026-07-12) — M5.1 performance, checker hot-path micro-opts (branch
`perf/hoist-kind-domain-target-keys`, squash-merged).** Started from a fresh compiler-profile
JFR (the flat post-482 profile). Three byte-identical changes, compiler self-compile still 46
diagnostics, full corpus suite green 10,160 / 0.
- **Change 1 — LinkedHashMap → HashMap on order-independent hot maps.** Kotlin's
  `mutableMapOf()`/`mutableSetOf()` return LinkedHashMap/LinkedHashSet, which pay
  `afterNodeInsertion` on every put and an ordered copy on construction. The per-function-body
  scope structures `currentLocalTypes` / `currentLocalDeclTypeNodes` / `currentShadowedNames` /
  `currentParamBindingNames` are copied on every scope entry and their iteration order is never
  consumed (verified: zero `.keys/.values/.entries/.forEach/iterator` usages across all
  references), and `getUnionType`'s dedup set is membership-only with the result sorted before
  use — convert all to plain HashMap/HashSet. Profile: `LinkedHashMap.afterNodeInsertion`
  **5.0% → 0.6% self**, `LinkedHashIterator.nextNode` 1.5% → 0.7%.
- **Change 2 — `flowCallMightNarrow` gate order.** It tested the O(arg-tree)
  `argMentionsReferencePath` scan FIRST on every flow call, then the callee-effects predicate.
  Swap the `&&`: `flowCalleeMayHaveAssertEffects` is per-walk memoized (`narrowWalkDeclCache`)
  and returns false for the vast majority of flow calls (non-assert callees), short-circuiting
  before the scan; `&&` is commutative for the result and both operands only fill idempotent
  memos. Profile: `argMentionsReferencePath` **1.9% self → out of top-90**;
  `flowCallMightNarrow` inclusive 2.5% → 1.0%.
- **Change 3 — single-lookup `resolveModuleSpecifier` memo.** It did `containsKey` + `get`
  (two map lookups) per hit and null is the hot result; encode null with a sentinel so the
  memo is one `get`. Profile: getNode-from-`resolveModuleSpecifier` 26 → 7 samples.
- **Merge note:** this session's fourth planned item — hoisting the target `.kind`-domain out
  of the negative type-guard filter — was landed INDEPENDENTLY by round 482 (`b72ebcf2`, which
  also added the `kindDomainKeysOfType` memo). On merging main into the branch, that hunk
  conflicted and was resolved to main's version (strictly better), so the squash contributes
  only changes 1–3.
- **Verification:** every change confirmed byte-identical by the full corpus suite (10,160/0)
  and the unchanged 46-diagnostic compiler self-compile, then measured against a re-recorded
  JFR (the profile shifts after each fix, so each was re-profiled). Wall-clock on the dev box
  was too noise-dominated (±4 s on a 78-file `noEmit`) to read a single-file delta — the
  sample-fraction reductions are the signal; the savings compound on the larger services/server
  profiles (more/larger discriminated unions and scope entries).
- **NEXT M5 leads (unchanged from 482):** the node-keyed AST scans need file+node-identity
  keying (round-481 (e) hazard); `checkMemberAccessMissing` (~4.7% self); the residual
  scope-map COPY cost (`HashMap.putMapEntries` — a copy-on-write / layered-scope redesign,
  higher risk).

**Round 482 (2026-07-12) — M5.1 performance, first post-v1 perf items after the mandatory
fresh JFR pass.** Two commits (b72ebcf2, 5b5d4f75), both byte-identical. The fresh round-482
harness JFR (45.8 s / 3,620 samples) confirmed the round-481 flat profile with the
discriminant `.kind` key-domain family as the top set-churn source: `--callers-of
AbstractCollection.addAll` and `HashSet.add` both put `kindDomainKeysOfType` at the top
(~29 `addAll` + ~24 `HashSet.add` samples), because a union like `Node` is guard-narrowed
at many read sites and each call re-scanned every member's `.kind` annotation and built
fresh mutable sets.
- **Fix 1 (byte-identical, two behavior-preserving moves):**
  - Memoize `kindDomainKeysOfType` by Type.id (new `kindDomainKeysOfTypeCache`, mirroring
    `discriminantKindKeysCache` exactly — empty-set encodes "unreadable", and the same
    `canonicalEnumSymbol` cross-path determinism guarantee its `.kind`-annotation readers
    already carry makes a global Type.id memo safe, per the round-425 canonical-key gotcha).
  - Hoist the target's `.kind` key domain out of the negative type-guard filter loop:
    `kindDomainProvesNotSubtype(member, targetNode)` was re-scanning `targetTypeNode` once
    per union member; new `kindDomainKeysExceed(t, targetKeys)` takes the pre-computed
    domain so the filter computes it once per narrowing call.
- **Verification (fix 1):** harness diagnostics byte-identical (95, per-position `--listAll`
  diff empty vs HEAD); full corpus suite green 10,155 → 10,157 (+2 local
  KindDomainMemoConsistencyTest — repeated negative guards on the same union with different
  targets narrow independently, no stale cross-site memo contamination; + the negative
  control that a genuine subtype still collapses); clean same-machine A/B (3 runs each,
  daemon up) harness self **44.35 → 41.5 s (−6.4%)**; bench TSV row 41.1 s, 95 errors.
- **Fix 2 (`emitTs18048ForClosureCapturedUndefinedReceiver`, 1.6% self):** this emitter runs
  for EVERY property-access with an Identifier receiver and built a throwaway filtered list
  per call (`.filter{}.maxByOrNull{}`) to find the innermost lexically-containing closure.
  Replaced with an allocation-free single-pass max-`container.pos` scan + an empty-
  closureStarts early bail. Byte-identical (harness 95, listAll diff empty); suite
  10,157 → 10,160 (+3 local ClosureCapturedInnermostSelectionTest — the innermost-closure
  selection the single pass must preserve: fires for a captured maybe-undefined receiver in
  the inner of two nested closures, suppresses with an inner-closure guard, bails for the
  inner closure's own local); bench row 41.1 → 40.8 s (−0.6%, allocation reduction near the
  noise band but consistently in the right direction).
- **NEXT M5 leads (from this JFR):** the node-keyed AST scans `kindDomainKeysFromTypeNode`
  / `enumSwitchKeysFromTypeNode` / `enumMemberKeysOfTypeNode` (3.7% / 3.1% / 2.3% inclusive)
  are the deeper cost but need file + node-identity keying — the round-481 (e) hazard (pos
  collides across files; result depends on `currentFileLocals`), so a pure memo is unsafe;
  `checkMemberAccessMissing` (9.2% inclusive / 4.3% self — the biggest walker);
  `emitTs18048ForClosureCapturedUndefinedReceiver` 1.6% self (audit its per-node work); and
  the broad flow-walk HashMap/HashSet churn (M5.2 allocation discipline).

**Round 481 (2026-07-12) — HARNESS REACHES ZERO REAL FPs: ALL EIGHT PROFILES AT ZERO REAL
FALSE POSITIVES — the v1 FP exit criterion is met.** FIVE fixes in 1 commit (b77b1afc),
harness 100 → 95 (the remaining 95 = TS2591×66 process/require + TS2304×10
BufferEncoding/global + TS2584×6 console + TS2503×6 + TS2593 `it` + harnessGlobals
TS7006×3 chai + `Error.captureStackTrace` TS2339×2 + a BufferEncoding-consequence
TS2322 — ALL env-legit offline artifacts). Zero additions by per-position diff; all
seven other profiles re-verified at their 46 floors. Suite 10,142 → 10,155 (+11 local
tests across 5 new files, 0 regressions).
- **Spread-of-any poisons at the TYPE level:** getTypeOfObjectLiteral returns `anyType`
  when a spread's type is any/error (tsc semantics) — harnessLanguageService:758's
  `typingsInstaller: { ...nullTypingsInstaller, globalTypingsCacheLocation }` FP'd the
  per-property leaf, and suppressing only the leaf UNMASKED the coarse whole-object
  relation at the var decl (same-position masking); the type-level rule makes every
  consumer agree. The round-445/472 per-site bails stay as guards.
- **Chimera structural sibling:** `sourceSatisfiesConflatedTargetPerFileView` (relation
  entry + missing-props arg emitter) — a source with NO heritage link relates to a
  chimera target when it satisfies SOME declaring file's per-file view
  (editorServices:3212 CachedDirectoryStructureHost vs ParseConfigHost, whose fakesHosts
  class merge demanded a required getCurrentDirectory; optional on the interface tsc sees).
- **String-layer union members are display strings (no `@`):** a named member falls to
  the bottom `return false` — `namedUnionMemberCouldAcceptArray` resolves a TYPE-ALIAS
  member's body for array-ish forms (`ArrayOrSingle<T> = T | readonly T[]`) so
  fourslashImpl:1214's `expected = [expected]` relates; Array-EXTENDING interfaces
  deliberately keep firing (their extra members make a bare literal a genuine error —
  the first cut's heritage arm failed its own negative control).
- **Overload contextual selection:** resolveCallOverload treats an un-inferred bare
  TypeParam param as matching (tsc infers it), and the property-access pass's
  multi-overload contextual branch adopts the overload arg-matching SELECTS
  (strictSelect — definitive winners only, and only when ≠ sigs[0], keeping the legacy
  heuristic byte-identical otherwise) — documentsUtil:30's `.reduce((meta, key) =>
  meta.set(…), new Map())` typed `meta` as string via the first overload's callback.
- **As-cast member context:** `castTypeDeclaresFnMember` + `uniqueTypeAliasInclNamespaces`
  — an as-cast receiver whose TYPE declares the assigned member as a method AST-side
  signals ctx-unknowable (round-474 mechanism) when the resolved receiver poisons to any
  (harnessIO:379's `(result as CompileFilesResult).repeat = newOptions => …`; the
  namespace-nested alias intersects a barrel-unresolvable `compiler.CompilationResult`).
- **Emit/crash legs verified same session — the OFFLINE-VERIFIABLE v1 DEFINITION OF DONE
  IS FULLY MET:** all eight profiles emit every program file with exit 0, no
  crashes/hangs/OOMs (compiler 78/78, tsc-cli 80/80, jsTyping 84/84, deprecatedCompat
  81/81, typingsInstallerCore 88/88, services 252/252, server 274/274, harness 312/312
  via the bench row — self 50.5 s, +0.9% noise band, RSS 1.89 GB).
- **M5.1 fresh JFR pass (same session, harness profile, 50.5 s / 4,070 samples) — the
  round-434 "flat profile" verdict still holds (top self = HashMap.getNode 6.4%), with
  these ranked leads:** (a) **HashMap/HashSet churn ~20%+ inclusive aggregate**
  (getNode 9.2%, put 7.8%, HashSet.add 6.9%, putVal 5.4% — the flow-walk memos and
  per-walk set copies; M5.2 territory); (b) **checkMemberAccessMissing 8.6% inclusive
  / 4.1% self** — the single biggest walker; (c) **the barrel-star resolution chain
  resolveBarrelStarTarget → resolveModuleSpecifierRelative → normalizePath ~5%**
  (every star-chain walk re-resolved every hop) — **FIXED same session:
  `barrelStarTargetCache` (Tier-2 pure memo over frozen fileResults), byte-identical
  diagnostics, harness self 50.5 → 46.2 s (−8.5%, bench row)**; (d) **the symbol-lookup
  family `findSymbolInAllNamespaceScopes` → `findSymbolInExports` ~7% inclusive** — the
  Transformer probes `resolveConstEnumMemberAccess` for EVERY dotted expression chain,
  and any head resolving nowhere (a B83.5-unbound function-body local) fell through
  `resolveNamePath` to a full-program recursive namespace scan — **FIXED same session:
  `namespaceScopeSymbolCache` (Tier-2 memo keyed by name; stored null = not found),
  byte-identical diagnostics, harness self 46.2 → 45.0 s (a further −2.6%)**; (e) the
  discriminant key-domain AST scans `kindDomainKeysFromTypeNode` +
  `enumSwitchKeysFromTypeNode` ~6% combined (per-node memo candidates); (f)
  display-string building (typeToString 3.4% + joinTo/split ~3.5%); (g)
  `emitTs18048ForClosureCapturedUndefinedReceiver` 1.3% self (a niche emitter — audit
  its per-node work). `getTypeParamInfo` 1.7% self is a smaller flat-profile entry.
  Caller attribution: normalizePath ← resolveModuleSpecifierRelative (137/188);
  resolveModuleSpecifierRelative ← resolveBarrelStarTarget (82 direct + 117
  deep-recursion truncated); checkMemberAccessMissing ← checkSinglePropertyAccess
  (254/351); findSymbolInExports ← findSymbolInAllNamespaceScopes (143/143);
  resolveConstEnumMemberAccess ← Transformer.transformExpression (118/131). Recording:
  `$SCRATCH/r481-harness.jfr` (session-local; rerun per the docs/parallel-caching.md
  how-to — the profile shifts after every fix). **FOUR Tier-2 memos landed same session
  (all byte-identical diagnostics, full suite green): `barrelStarTargetCache`,
  `namespaceScopeSymbolCache`, `typeParamInfoCache` (getTypeParamInfo — full-program
  binder-table double scan per generic ref), `starExportVarDeclCache`
  (resolveExportedVarDeclThroughStars — the emptyArray conflation path). Net harness
  self 50.5 → 44.8 s (−11.3%). LESSON re-confirmed: a Tier-2 memo field consulted
  during init (getTypeParamInfo runs via collectUninitializedVars) MUST be declared
  BEFORE `init` — the first getTypeParamInfo cut NPE'd on a null cache field; the
  crash surfaced as `COUNT=0` on the whole profile (a run-wide crash, not a diff).**
- **NEXT (post-v1):** M5 continues — the remaining flat-profile leads are HashMap/HashSet
  churn in the flow-walk memos (M5.2 allocation discipline) and the discriminant
  key-domain per-node AST scans (context-sensitive on `currentFileLocals`, so a
  file-keyed memo, not a pure one). byte-correct emit diffing vs real tsc stays
  network-gated (needs node + typescript). Candidate follow-ups: delete superseded pin
  walkers; re-audit the env-legit floors once a node-types story exists.**

**Round 480 (2026-07-12, same session as 479) — SIX fixes in 1 commit (629561bb). Dashboard:
harness 109 → 100 with the 480b heritage batch (ddad6077): an imported conflated heritage base resolves per-file (conflatedPerFileViewForContext) + the derived-vs-chimera bails (conflatedChimeraTargetSourceHasPerFileBase, relation entry + arg emitter — the first cut manufactured 2 ParseConfigFileHost FPs, caught by per-position diff). ~5 real left; every step zero-additions by per-position diff; all seven
other profiles hold their 46 floors. Suite 10,132 → 10,142 (+10 local across 5 new test
files, 0 regressions); bench row +2.1% self (noise band).**
- **Never-inference:** a no-return block body whose every path THROWS infers `never`
  (tsc fall-off-never; gated on blockHasAnyReturn so a bare `return;` keeps void) —
  evaluatorImpl's `import: _id => { throw … }` vs `import(id): Promise<…>`.
- **Contextual literal returns:** allArgumentsMatch accepts an inline arrow arg whose every
  RETURN is a string literal ∈ the param's literal-union return
  (argFnLiteralReturnsSatisfyParam; block bodies must always-return) — vfsUtil `_walk`
  callbacks widened `"retry"`/`"throw"` to string and FP-rejected BOTH overloads (TS2769 ×2).
- **Fresh literals at the per-prop ARG leaf:** the B326 keep-the-literal rule applied where
  an objlit arg's member is drilled per-property (`type: "file"` vs `type: "file"` displayed
  as 'string' ⊄ 'string', fourslash organizeImports/getCombinedCodeFix).
- **tsc's SUBTYPE rule in negative narrowing (the vfsUtil symlink-never family):**
  `missingVsOptionalProvesNotSubtype` — a union member LACKING a property the guard target
  declares OPTIONAL is not a subtype (tsc assumeFalse uses the subtype relation, where
  missing-vs-optional FAILS; assignability passes) → `!isDirectory(node)` keeps
  FileInode/SymlinkInode, whose only differences from DirectoryInode are optional props.
  Wired into BOTH the union filter and the single-type negative return; the
  structurally-identical corpus pin (instanceofWithStructurallyIdenticalTypes — no optional
  distinguishers) is unaffected.
- **Any-element source REST params accept-all:** signatureRelatedTo's B196 expansion
  rejected `(...args: any[]) => void` → `(project: Project) => void` by comparing the ARRAY
  type contravariantly when the element gate returned null (incrementalUtils:656).
- **NEXT (harness @100, 5 real + harnessGlobals×3 likely-env-legit):** documentsUtil:30
  (reduce<U> accumulator contextual typing — both overloads arity-applicable so B476
  bails, yet `meta` typed as T; probe); harnessIO:379 (as-cast member assignment ctx —
  minimal repro passes, whole-program only; probe); harnessLanguageService:758 (spread of
  barrel-unresolvable `nullTypingsInstaller` in a var-decl objlit MEMBER value — the
  emission is emitPerPropertyMismatchesForObjectLiteral per the probe; needs the
  round-445 unresolved-spread bail there); fourslash:1214 ('array' vs ArrayOrSingle<…>
  union — the "array" display suggests an un-typed array literal vs an alias union);
  editorServices:3212 (CachedDirectoryStructureHost vs chimera ParseConfigHost param —
  no heritage link, tsc satisfies STRUCTURALLY; the arg emitter would need to compare
  against the per-file view when the param is a chimera).**

**Round 479 (2026-07-12 — the harness burn-down continues) — SEVENTEEN fixes across 3
commits (0a5668b2 / 982431aa / 08cb0bab). Dashboard: harness 145 → 109 (−36; real ~14 left
excl. env-legit + harnessGlobals×3 reclassified likely-env-legit); every step zero-additions
by per-position listAll diff; all seven other profiles re-verified at 46. Suite 10,098 →
10,132 (+34 local, 13 new/extended test files, 0 regressions); harness self −7.2% (TSV row).**
- **Conflation family (the big one):** `conflatedPerFileInterfaceType`'s QualifiedName arm
  gains (a) an ImportSpecifier branch — a namespace imported by NAME through a barrel
  (`import { protocol } from "./_namespaces/ts.server.js"` → the star chain →
  `export { protocol }` of an `import * as protocol` → its module → the interface's
  declaring leaf; client.ts protocol.TextSpan/Location ×5) — and (b) the NamespaceImport
  branch follows a BARREL target's `export *` chain to the leaf (`ts.ParseConfigHost`
  through harness `_namespaces/ts.js` vs fakesHosts' `class ParseConfigHost` chimera;
  cleared the ParseConfigHost/TS2740/TS2739/TS7053/Classification family ×5).
- **Namespace-import aliases ARE namespaces:** checkTypeNameResolved bails TS2833/TS2702
  for an `import * as X` alias (symbolIsNamespaceImportAlias) — a case-differing sibling
  namespace manufactured "Did you mean 'Compiler'?" ×4; and an import-equals alias to a
  ns-member (`export import parse = ts.getPathComponents`) resolves the CALLEE through its
  own target (importEqualsNamespaceMemberCalleeType), never a same-named merged-globals fn.
- **Module-scope isolation on cross-file merge walkers:** TS2433 (namespace-split) and
  TS2475 (const-enum use) gate on isModuleFile — two module files' same-named decls never
  merge in real tsc (namespace Debug vs class Debug; const enum State vs class State).
- **Narrowing/CFA:** the narrowed-single-Object TS2339 emission bails on index signatures
  (CompilerSettings ×3); a closure that is an ARGUMENT of a call rooted at `root?.` is
  non-nullish inside (incrementalUtils ×2, closureGuardedByOptionalChainRoot); property-
  access `.x!` strips nullish under the round-456 all-concrete gate (8-profile A/B clean —
  the historical deferral's hazard is covered by the M3 machinery landed since).
- **Smaller families:** ctor var-decl-nested `this.x =` assignments count for TS2564 (×3 +
  chains); ANY-optional-decl member truthiness for TS2774 (the System class+interface
  chimera pollutes isOptionalProperty's first-decl read); statement-position `yield x;`
  draws no TS7057 (tsc expressionResultIsUnused); bare specifiers never resolve RELATIVE
  under nodenext (TS1192 'path' → src/compiler/path.ts); for-of loop vars shadow in the
  call-types walker (evaluatorImpl); extends+implements-same-class TS2720 skip (bare-args
  gated — the ungated cut regressed extendAndImplementTheSameBaseType2, caught by the
  suite); `new Function(...)()` is an untyped call (tsc isUntypedFunctionCall); method/ctor
  bodies run applyBodyLocalShadowing in the property-access pass (the round-447 trap —
  fourslash Refactor.actions ×4 via refactorProvider's leaked `const refactors` Map).
- **REVERTED:** TS7006 suppression for arrows assigned to an any-typed receiver's member —
  contradicts the round-464 pin (an any contextual type provides NO contextual signature →
  tsc fires); harnessGlobals ×3 reclassified likely-env-legit (chai unresolvable offline).
- **NEXT (harness @109, ~14 real):** the ParseConfigHost/ServerHost RELATION residuals
  (services:1790 objlit vs ParseConfigFileHost, editorServices:3212, harnessLanguageService
  754/758 — the System/ServerHost chimera on the relation side, not resolution); vfsUtil
  TS2769 ×2 + :860 symlink-on-never; documentsUtil:30 reduce-accumulator overload
  selection; fourslash 636/3411 'string' vs 'string' identity displays; evaluatorImpl:337
  (throw-only arrow infers void, tsc infers never); incrementalUtils:656; harnessIO:379.**

**Round 478 (2026-07-11, same session as 477 — the HARNESS burn-down begins) — FIVE fixes,
harness 217 → 145 (−72; TS2339 66 → 13, TS7006 15 → 4, TS2341 6 → 0; every step
zero-additions by per-position diff). Suite 10,090 → 10,098 (+8 local across 2 new test
files, 0 regressions).**
- **Fix 1+2 (tsc getAssignmentReducedType — the fourslash reassignment idioms, ~37 FPs):**
  `narrowByAssignmentRhs` gains THREE assignment-reduction arms, all placed BEFORE the
  round-416 non-nullish reset (a both-arms-non-nullish ternary would otherwise reset to the
  FULL declared union first): (a) `x = typeof x === "tag" ? { … } : x` (both condition
  orders) drops the tag's members via narrowByTypeOfGuard when the pass-through arm is the
  bare reference and the replacement arm an object literal; (b) a plain OBJECT-LITERAL RHS
  drops the declared union's primitive/nullish members (`if (typeof source === "string")
  source = { files: … };` — evaluatorImpl); (c) an ARRAY-LITERAL RHS keeps only array-like
  members (Array/ReadonlyArray refs, tuples, intersections containing one — `if
  (!ts.isArray(expected)) expected = [expected];` incl. the `readonly T[] & {plus}` brand).
- **Fix 3 (lexical private access, TS2341 ×6):** `checkStaticPrivateMemberAccess` accepts a
  same-file access POSITIONALLY inside the declaring class declaration — a function nested
  in a class method reads the class's static privates legally (fourslash
  `TestState.nLinesContext` inside `textWithContext`); the enclosing-class threading resets
  at nested-function boundaries (this-rebinding), which is right for `this` but wrong for
  lexical accessibility.
- **Fix 4 (`import * as ns` guards, TS2339 ×16):** `resolveNamespaceMemberFnDecl` gains a
  NamespaceImport branch — resolveAlias never resolves namespace-import aliases (round 444)
  and the ImportSpecifier-keyed flow resolvers skip them, so
  `ts.isDocumentRegistryEntry(entry)` through the harness `.js` barrel silently never
  narrowed. Resolve the import's own specifier → target file → locals + `export *` chain;
  memoized (`nsImportMemberFnCache`, declared before `init`). REPRO LESSON: the free-fn
  receiver variant "passed" because `entry` was silently UNTYPED (resolution failure reads
  as success) — the interface-METHOD receiver variant typed it and exposed the guard; when
  a repro "passes", confirm the types actually RESOLVED before believing it.
- **Fix 5 (namespace-callee locals, TS7006 ×11):** the same resolver feeds
  `initializerCtxTypeForImplicitAny`'s namespace-callee arm — `const compilerHost =
  ts.createCompilerHostWorker(…)` types the local from the callee's return annotation, so
  `compilerHost.getSourceFile = (fileName, …) => …` arrow params inherit the CompilerHost
  member context.
- **NEXT (harness @ 145, ~55 real):** harnessIO `CompilerSettings` index-sig ×3 (namespace-
  nested interface with a string index sig — the TS2339 should be suppressed) + TS2833
  `compiler.CompilationResult` ns-import-in-TYPE-position ×4 (the type-position sibling of
  fix 4); client.ts protocol `Location` ×5 (conflation family); compilerImpl TS2564 ×3;
  fourslash 829/839 (`.definitions` on a union), 1946 (`string | Range`), 4045 (`Refactor
  .actions`); incrementalUtils TS18048 ×2; editorServices 1461 TS2774 (`this.host.realpath`
  optional-method truthiness) + 3212.**


**Round 477 (2026-07-11 — SERVER REACHES ZERO REAL FPs, SEVEN of eight profiles) — FIVE
fixes, server 51 → 46 (real FPs 5 → 0; the remaining 46 = TS2591×43 + TS2304×2 `global` +
TS2584 console, all env-legit offline artifacts). All five residuals were CONFLATION-family
(Blocker #3) on server/protocol.ts vs compiler/jsTyping declarations.**
- **Fix A (utilities:7827 TS2366):** a same-named `const enum NewLineKind` in protocol.ts
  (Crlf/Lf) AND compiler types.ts (CarriageReturnLineFeed/LineFeed) merges into a 4-member
  chimera, so the switch covering the complete compiler pair read non-exhaustive. THREE
  coupled pieces: `conflatedEnumFileSubsets` (per-file member-name sets keyed by the merged
  symbol id) relaxes the exhaustiveness comparison (`coveredExhaustsConflatedEnumSubset` —
  covering ONE file's complete member set is exhaustive; real tsc never merges module-scoped
  enums); `unionDiscriminantKeysFromAnnotation` — the AST-side fallback when the union
  members are ALSO alias-shadowed (protocol's `type CompilerOptions = ChangePropertyTypes<…>`
  shadows the interface via last-wins → the resolved member is any/error and the
  resolved-type walk bails), resolving each member via `interfaceDeclsForCurrentFileView`
  (own decl, else the import followed through `.js` barrels); and `checkImplicitReturns` now
  sets `currentCheckFileName` per file — it was STALE from whatever pass ran before, so
  conflation-aware resolution in that pass silently used the wrong file.
- **Fix B (editorServices:1253 TS2353→TS2345):** four pieces: `annotationAgrees` (the
  topLevelConstStringValues builder) accepts a namespace-import-QUALIFIED alias annotation
  (`const CloseFileWatcherEvent: protocol.CloseFileWatcherEventName = "closeFileWatcher"` was
  POISONED out of the index); `enumMemberKeysOfTypeNode`'s QualifiedName arm falls back to
  `resolveNamespaceQualifiedTypeAlias` so `eventName: protocol.XEventName` member annotations
  yield `lit:s:` keys; `checkExcessProperties`' UNION nested descent drills the
  DISCRIMINANT-matched constituent (tsc getMatchingUnionConstituentForObjectLiteral — was
  first-with-the-prop, so `data: { id }` checked against LargeFileReferencedEvent's data);
  the then-morphed missing-props TS2345 (the chimera demands protocol's `event`/`body`)
  needed the round-468 `objectLiteralMatchesSomeConflatedDeclaration` rule in the union-arg
  structural gate.
- **Fix C (session:475 TS2322):** a QUALIFIED `ns.Name` naming an interface SHADOWED by a
  same-named `type Name` alias in a DIFFERENT module file (session's own `type Event` vs
  protocol's `interface Event`) resolves through the namespace import to the target module's
  per-file view (`conflatedPerFileInterfaceType` gains a filesOverride param fed by
  `interfaceDeclFilesAll`; QUALIFIED-only so bare refs keep the round-443/444 ecology).
  PAIRED with the `isConflatedInterfaceRefNode` QualifiedName-arm extension (nodeTypes bypass
  — a null-context first touch would cache the alias resolution) and a precise-verdict early
  return in checkReturnAssignability (`qualifiedAliasShadowedTarget` + engine-confirmed
  fresh-literal pass — the STRING fallback re-resolves "Event" by bare name to the shadowing
  alias and re-FP'd; the round-436c trap).
- **Fix D (session:3994 TS2322):** `objectLiteralSpreadsConflatedInterface` — an objlit
  SPREADING a conflated-interface-typed value is unknowable (the spread source's fn-return
  shell cached the chimera eagerly, B198; `{ ...textSpan, contextStart, contextEnd }` mixed
  compiler's `{start: number, length}` into protocol's `{start: Location, end}`); wired at
  the ternary-arm + direct-return paths. Suppression-only.
- **Fix E (typingInstallerAdapter:233 TS2345):** the round-476 "B516 never-param / callee
  union" theory was WRONG — the Diagnostic-init probe showed the emission is the DEFAULT
  clause's `assertType<never>(response)` (line 233 IS the default clause): the
  default-exhaustiveness never fired because EVERY member's `kind: ActionSet`-style
  annotation read null — the CHECKING file only IMPORTS the merged const+type-alias names,
  so `currentFileLocals["ActionSet"]` is an IMPORT alias whose declarations are
  ImportSpecifiers and the bare-alias arm of enumMemberKeysOfTypeNode bailed.
  Fall to the merged GLOBALS symbol's TypeAliasDeclaration (mergeSymbolTable's addAll keeps
  the declaring file's alias in the polluted list).
- **Process:** the round-472 probe technique earned its keep twice (the XP233B stack
  falsified round 476's theory in one run; XP233C found the null keys in one more); one
  self-inflicted incident — a `--rerun-tasks` warnings check launched DURING the chain's
  MainKt run clobbered its classes (the documented NoClassDefFoundError gotcha), costing one
  re-run.
- **NEXT: harness (@225 last measured) — the LAST profile for v1.**

**Round 476 (2026-07-11, same session as 475) — TWO more server fixes in 2 commits
(b4bbf29c / 2e568f9e). Dashboard: server 54 → 51 (real FPs 8 → 5). Suite 10,075 → 10,078
(+3 local, 0 regressions).**
- **Fix 1 (b4bbf29c, jsTyping SafeList ×2):** the round-474 probe verdict resolved in one
  println probe — `globals["ReadonlyMap"]` is NULL (a KNOWN_GLOBALS name with NO modeled
  interface), so jsTyping's `type SafeList = ReadonlyMap<string, string>` body resolves
  errorType. `returnSourceSatisfiesFileLocalAliasBody` treats an UNRESOLVABLE file-local
  alias body as UNKNOWABLE and suppresses (the resolved target is the known-wrong merged
  chimera; FN-not-FP — only reached in the conflation context, and a resolvable failing
  body still fires per the negative pin). The PROPER fix (model ReadonlyMap in the
  embedded lib) is a lib change with the "and N more" count-shift trap — deferred.
- **Fix 2 (2e568f9e, typingInstallerAdapter:224):** `overloadNarrowedArgType`'s union path
  retries with `getNarrowedTypeForReferenceFollowLoopEntry` when the plain walk washed
  back to the declared union at a FlowLoopLabel (STRUCTURAL wash gate — branch labels
  mint fresh identical unions, the round-424 lesson). The ActionSet case reads `response`
  AFTER its requestQueue while-loop, so the switch-case narrowing was lost and both
  updateTypingsForProject overloads FP-rejected. Un-narrowed union args vs a narrower
  single overload turn out to be a PRE-EXISTING conservative FN (couldn't pin a small
  negative control — the dashboard diff is the both-directions evidence).
- **NEXT (server @ 51, 5 real):** compiler/utilities:7827 TS2366 (minimal union-param
  switch repro is CLEAN — the real site's barrel-imported CompilerOptions/PrinterOptions
  or cross-file NewLineKind differ; probe); editorServices:1253 TS2353 + session:475
  TS2322 (both repro clean minimally — the real sites involve protocol.ts's same-named
  conflated event/Event interfaces; probe the emission with the Diagnostic-init trick);
  session:3994 (TextSpan chimera-spread, known deep); typingInstallerAdapter:233 (the
  callee resolves to a UNION of Project's and ProjectService's watchTypingLocations →
  B516 combined sig intersects params to `never` — probe how `this.projectService`
  resolves). Then harness (@225 — TS2339×66/TS2322×16/TS7006×15).**

**Round 475 (2026-07-11, the SERVER burn-down continues) — FIFTEEN fixes in 3 commits
(f1e2589a / 258aae3d / ccc33547). Dashboard: server 77 → 54 (−23; real FPs 31 → 8 excl.
TS2591×43 + TS2304×2 `global` + TS2584), harness 255 → 225 (−30 riding, TSV rows recorded),
services re-verified UNCHANGED at its 46 env-legit floor. Suite 10,045 → 10,075 (+30 local
across 5 new test files, 0 regressions).**
- **Fix batch 1 (f1e2589a, the completions `Request` family ×8, Blocker #3):** the round-474
  "needs a probe first" verdict DISSOLVED into a minimal 2-file repro (protocol `interface
  Request` + completions-local `type Request = <union of inline type literals>`) — no probe
  needed. Three coupled extensions: `returnSourceSatisfiesFileLocalAliasBody` iterates EVERY
  union member of the return annotation (was sole-non-nullish); TypeLiteral alias-body
  constituents check via the new `objectLiteralExactlySatisfiesTypeLiteralNode`; and
  checkMemberAccessMissing's union branch suppresses when a MULTI-member receiver union
  contains an own-file conflated alias member (the chimera makes discriminant narrowing
  unmodelable) and the property exists on some member/alias constituent.
- **Fix batch 2 (258aae3d, nine families):** arg-path spread-of-any (session:1469);
  `registerBindingPatternParamLocals` — binding-pattern params register element names in the
  assignability pass with annotation member types (optional → `| undefined`), closing the
  destructured-SHORTHAND cross-file fn leak (editorServices:2852 `enable`, session:4063
  `isWriteAccess` — the round-473 residual); `getReturnTypeOfNewExpression`'s
  constructor-interface branch gated to NON-class callees (class instances DO carry
  constructSignatures inherited-first, so `new ConfiguredProject(...)` typed as `Project` —
  project:2764, editorServices:2897/3428); `A && B` = falsy(A) | B via isDefinitelyFalsyMember
  (root-caused from the 2 builder.ts FPs the binding-pattern fix unmasked — `let oldState =
  oldProgram && oldProgram.state` had dropped `| undefined`); TS2391 optional bodyless methods;
  TS2416 mutable literal-override widening; TS2564 ctor switch clauses; property-init
  foreign-TP bail (maybeBind, project:564); rhsIsDefinitelyNonNullish returns true for a
  NonNullExpression RHS outright (project:1694 — the unwrap-and-descend classified by the
  INNER call's nullable annotation).
- **Fix batch 3 (ccc33547, four families):** `<literal-union> || "literal"` keeps the right
  literal when the kept left is all string-literals (editorServices:2848);
  resolveMemberPropertyType UNION-root arm — `(A|B).p = A.p | B.p` (union-annotated param
  member switch; repro clean, the REAL utilities:7827 stays — barrel-imported interfaces need
  a probe); REST-param targets provide unbounded args (server/utilities:30);
  conflatedInterfaceFiles extended to cross-file CLASS X + `interface X` merges (canMerge
  Class+Interface makes it a chimera — scriptVersionCache's `class TextChange`) + the
  round-468 ARG-side objectLiteralMatchesSomeConflatedDeclaration rule wired into the RETURN
  path (services/utilities:2353).
- **Also landed (repro-verified, real site deferred):** const-string discriminant keys in the
  objlit-vs-union member selection (enumMemberKeysOfTypeNode TypeQuery arm +
  bare-Identifier const value arm) — the minimal eventName repro passes; the real
  editorServices:1253 additionally involves protocol.ts's same-named conflated event
  interfaces.
- **Process notes:** (a) the round-474 probe-first verdicts keep dissolving into minimal
  repros — ALWAYS try the 2-file repro before instrumenting; (b) one interim regression
  (2 builder.ts FPs from the binding-pattern registration) was caught by the per-step listAll
  diff and root-caused to the missing `&&` falsy rule IN the same batch — the diff-per-step
  discipline pays; (c) a `java` CLI run during a background gradle compile dies SILENTLY
  (classes clobbered mid-load) — sequence them.
- **NEXT (server @ 54, 8 real):** compiler/utilities:7827 TS2366 (probe — the minimal
  union-param switch repro is clean; barrel-imported CompilerOptions/PrinterOptions or the
  cross-file enum differ); jsTyping:81/88 SafeList ×2 (probe — why the alias body
  `ReadonlyMap<string, string>` resolves errorType at the call site; suspect the structural
  nodeTypes collision); editorServices:1253 (conflated event interfaces + const-string
  discriminant interplay); session:475 (repro clean — real site involves the protocol
  namespace-qualified conflated `Event`... probe) + session:3994 (chimera-spread, known);
  typingInstallerAdapter:224 (case-body read AFTER a while loop — suspect the FlowLoopLabel
  wash) + :233 (callee resolved to a UNION of the two watchTypingLocations methods → B516
  intersected param `never` — probe the callee resolution). Then harness (@225 —
  TS2339×66/TS2322×16/TS7006×15 on harness-only files).**

**Round 474 (2026-07-11, the SERVER burn-down continues) — EIGHT fixes in 4 commits
(8c65858a / dc105f56 / 5134ea7c / + the literal-write commit). Dashboard: server 104 → 77 (−27;
real FPs 58 → 31 excl. TS2591×43 + TS2304×2 `global` + TS2584), harness 299 → 255 (−44 riding
the same fixes, TSV rows recorded), every step strictly-removals by listAll diff at the ~46 s
normal band. Suite 10,024 → 10,045 (+21 local across 8 new test files, 0 regressions).**
- **Fix 1 (extractSymbol.ts ×7 + goToDefinition, Blocker #3):** a type-alias BODY referencing a
  CONFLATED interface name resolves in its DECLARING file's view
  (`resolveTypeAliasBodyWithOwnerContext`, identity-matched via localTypeAliasIndex) + the
  `isConflatedInterfaceRefNode` TypeOperator arm (`readonly Diagnostic[]` cached a
  chimera-element resolution in nodeTypes — plain `Diagnostic[]` worked, the readonly wrapper
  didn't: the missing-arm tell). **MEASURED DEAD-END folded into the gate: UNRESTRICTED owner
  threading regressed +41 server FPs and 3.4× wall (104 → 145, 48 → 164 s) — an owner file that
  itself DECLARES one of the referenced conflated interfaces (importTracker's own
  `interface AmbientModuleDeclaration` inside its leaked `type SourceFileLike` union) must keep
  the merged-chimera status quo; the round-443 display-keyed suppression ecology depends on it.**
- **Fix 2 (executeCommandLine.ts ×4, Blocker #3):** an imported CALLEE colliding with an
  unrelated same-named exported function (`formatMessage` compiler vs server/session) resolves
  through its OWN identity-matched import + `export *` chain (`importedCalleeFunctionType`, the
  fn sibling of round 473's `importedTopLevelVarAnnotationType`); gated to a genuine collision
  (globals valueDeclaration ∉ the import target's own decls) so non-collision paths stay
  byte-identical. Negative pin: a wrong arg against the CORRECT signature still fires.
- **Fix 3 (rules.ts ×5):** `keyof X` where a `type X` SHADOWED the `interface X` via the
  last-wins Interface+TypeAlias merge (protocol.ts's `ChangePropertyTypes<…>` aliases → anyType
  → `keyof any` = `string | number | symbol`) recovers the literal key union AST-side
  (`keyofShadowedInterfaceKeyUnion`, own + extends-inherited names; bails on index signatures /
  unresolvable bases). The invalid-key positive control proves the union is real.
- **Fix 4 (editorServices.ts ×4 TS7006):** a body local initialized from a `this.<method>(…)`
  call types from the ENCLOSING class's own method return annotation (the implicit-any walker's
  this-call arm — `getTypeOfExpression(this)` is anyType per B101), PAIRED with the
  ctx-unknowable rule: a target member ANNOTATION naming a conflated alias-shadowed interface
  (`sourceFileLike?: SourceFileLike`) marks the RHS contextually typed instead of propagating
  the wrong resolution. The union-receiver gate needed explicit member resolution
  (getPropertyOfType has NO Union branch — the round-419 gotcha, found by probe).
- **Fix 5 (completions.ts:1251):** a return annotation naming a conflated `type X` THIS file
  declares checks against the TRUE file-local alias BODY
  (`returnSourceSatisfiesFileLocalAliasBody`). jsTyping:81/88 (the SafeList target) STAY: the
  alias body `ReadonlyMap<string, string>` resolves errorType at this call site while ReadonlyMap
  resolves fine program-wide — a resolution residual needing a probe.
- **Fix 6 (session.ts 1827/1907/2424):** a ternary ARM spreading an any/error-typed value is
  `any` in tsc — the round-445 spread-poison rule extended to checkConditionalReturnBranches.
  session:3994 stays (its spread resolves to the conflated-TextSpan chimera, not any).
- **Fix 7 (server/utilities.ts:83):** a POSITIVE equality against a literal narrows a BARE
  supertype primitive to the literal (tsc narrowTypeByEquality) — narrowUnionByLiteral's
  non-union branch returned the primitive unchanged, so `base === "tsconfig.json" || base ===
  "jsconfig.json" ? base : undefined` FP'd `string` vs the literal-union return.
- **Fix 8 (project.ts:2286):** a LITERAL property write whose literal the target's declared
  union annotation SYNTACTICALLY contains is always legal (`this.autoImportProviderHost =
  false` vs `AutoImportProviderProject | false | undefined`) — BOTH this-prop write paths
  (the varTypes string path and checkPropertyAccessAssignment) widened the literal first;
  both now consult the round-436c syntactic membership proof.
- **MEASURED & REVERTED (the completions `Request` theory):** resolving a conflated name to the
  ctx file's OWN top-level `type` alias inside conflatedPerFileInterfaceType cleared NOTHING
  (the Request FPs come through a different path) and added 3 returnValueCorrect.ts
  `'Info | undefined' ⊄ 'Info | undefined'` identity-mismatch FPs — the round-444/445 Info
  first-touch ecology. The completions Request/CompletionData ×8 family needs a probe first.
- **Session note:** the session was restored mid-flight (`--continue`) after the harness process
  died; the suspected in-flight OOM was actually the perf regression of the then-unbisected
  fix-1 (164 s run) — bisecting the two coupled edits found the TypeOperator arm clean and the
  threading responsible for both the +41 and the slowdown.
- **NEXT (server @ 77, 31 real):** completions Request/CompletionData ×8 (probe the emission
  path first — the reverted theory shows it is NOT the bare-name TypeReference resolution);
  session.ts residual (475 `protocol.Event` qualified conflated-alias, 3994 chimera-spread,
  4063 shorthand leak, 1469); project.ts ×8 (399 TS2564, 470/471 TS2391, 564, 1694 TS18048,
  2764 new-expr base, 2914 TS2416); editorServices residual ×5;
  jsTyping SafeList ReadonlyMap-errorType probe; typingInstallerAdapter ×2; compiler/utilities
  :7827 TS2366; services/utilities:2353. Then harness (last 299).**

**Round 473 (2026-07-11, the SERVER burn-down — the three big conflation families) — THREE fixes
in 2 commits (ad660db5 / ef8107f5). Dashboard: server 227 → 104 (−123; real FPs 181 → 58 excl.
TS2591×43 + TS2304×2 `global` + TS2584), harness 429 → 299 (−130 riding the same fixes);
services and compiler UNCHANGED at their env-legit floors (46 each — the zero-real profiles did
not regress). Suite 10,013 → 10,024 (+11 local across 3 new test files, 0 regressions); server
self-compile 43.6 → 48.6 s (+11% — the conflated-name nodeTypes bypass; acceptable, noted for M5).**
- **Fix 1 (ad660db5a, const-string discriminants — session/typingInstallerAdapter/editorServices
  ~35 FPs):** tsc's jsTyping/shared.ts idiom discriminates unions on CONST-typed strings
  (`switch (response.kind) { case EventTypesRegistry: … }` + `eventName: typeof
  ProjectsUpdatedInBackgroundEvent` members). FOUR coupled pieces: the Binder MERGES
  Variable+TypeAlias (the `type ActionSet = "action::set"` + `const ActionSet: ActionSet`
  same-name pair — the const previously OVERWROTE the alias symbol, so every `kind: ActionSet`
  annotation resolved errorType and the narrowing filters kept every member); the NEW
  program-wide `topLevelConstStringValues` index (unambiguous top-level const strings;
  value-space competitors POISON, type-space aliases/interfaces don't compete) feeds
  `constStringCaseLiteralType` in narrowBySwitchClause + the default-exhaustiveness block +
  narrowByDiscriminantProperty; `typeQueryConstStringLiteral` recovers `typeof <const>` member
  annotations that widened to string/any in both discriminant filters; and
  checkMemberAccessMissing gained the SIBLING-discriminant suppression (`switch
  (event.eventName)` narrows the BASE `event` and projects `.data` — the walked path
  "event.data" is invisible to the FlowSwitchClause).
- **Fix 2 (ad660db5b, per-import barrel VAR resolution — the `emptyArray` family, 29 FPs,
  Blocker #3):** compiler files importing core.ts's `emptyArray: never[]` through
  `./_namespaces/ts.js` resolved server/utilitiesPublic.ts's `emptyArray:
  SortedReadonlyArray<never>` — the merged globals symbol's winner is FILE-ORDER-DEPENDENT.
  `importedTopLevelVarAnnotationType` (getTypeOfSymbolWorker's Alias branch ONLY — the
  round-409 resolveAlias-flood rationale stands) resolves the alias through its OWN
  ImportDeclaration (IDENTITY-matched in the structural index — same-shaped specifiers live in
  files whose barrels resolve DIFFERENTLY) + the new `computeExportedVarDeclThroughStars`
  (FILE-AST star following — the merged symbol's declarations list is polluted, so symbol-side
  resolution can't pick the right file's decl).
- **Fix 3 (ef8107f5, per-file views of CONFLATED interfaces — the protocol.ts family, ~64 FPs,
  Blocker #3):** `interface Diagnostic`/`TextSpan`/`HighlightSpan`/`Request` declared in BOTH
  server/protocol.ts and compiler-or-services types.ts merge into a chimera. References now
  resolve the per-file view their context selects (see the commit message + the CLAUDE.md
  gotcha for the FIVE coupled pieces: conflatedPerFileInterfaceType with the
  defer-to-general-resolver rule pinned by errorWithSameNameType; the transient-symbol
  perFileInterfaceType; heritage context threading; the isConflatedInterfaceRefNode nodeTypes
  bypass incl. COMPOSITE nodes; the conflatedCtxMissing no-cache flag + conflatedOwnerFile
  member-annotation context; the conflatedMergedPairRelated relation/arg-emitter bails).
  The round-468 `&&`-return arm now EMITS the falsy-remainder error directly (tsc types
  `count && obj` as `0 | {…}`) — the chimera-era coarse path had reported it by accident
  (the negative control was pinning an accidental mechanism).
- **Lessons:** (a) the `nodeTypes` cache is keyed by the STRUCTURAL node — same-shaped
  annotation nodes in DIFFERENT files collide, which the per-file resolution exposed (bypass
  for conflated names, including composites: TypeLiteral members resolve EAGERLY in
  getTypeFromTypeLiteral); (b) a Diagnostic-init probe on a 4-file repro beats armchair
  resolution-tracing — three rounds of wrong valueDeclaration theories fell to one
  `XPROBE-ID` print showing `globals=SortedReadonlyArray<never>`; (c) fn types cache their
  shell EAGERLY (B198), so null-context param annotations stay chimeras — that's what the
  relation-level conflatedMergedPairRelated bail is for.
- **Residual (documented):** session.ts:4063 resurfaced with a different display — a
  destructured-param SHORTHAND (`isWriteAccess`) leaking to a same-named cross-file function
  in the return-objlit path (previously masked by spread-of-any); the round-429
  currentParamBindingNames shadowing does not reach this pass's shorthand-value typing.
- **NEXT (server @ 104, 58 real):** session.ts:4063 (the shorthand leak above); the remaining
  session.ts objlit targets (Event/EmitOutput/QuickInfo/RefactorEditInfo — union-of-protocol
  targets); completions.ts Request/CompletionData ×9; editorServices ×9; project.ts ×8;
  rules.ts keyof ×5; executeCommandLine Logger ×4. Then harness (@ 299 — its own files +
  TS2339×69/TS2345×37 tail).**

**Round 472 (2026-07-11, the services burn-down — SERVICES REACHES ZERO REAL FPs) — SEVEN fixes
in 6 commits (15c1ff56 / ada176fd / 255a92f6 / 36f98fbf / c09dc08b / + the truthy-guard commit).
Dashboard: services 56 → 46 (−10; TS2322 3 → 0, TS2345 2 → 0, TS2740/TS2538/TS2339/TS7034/TS7005
→ 0 — the remaining 46 = TS2591×43 + TS2304×2 `global` + TS2584 console, ALL env-legit offline
artifacts). The SECOND profile at zero real FPs, matching compiler. Every fix verified
strictly-removals by per-position listAll diff; suite 10,009 / 0 failing (+21 local across 8 new
test files). Compiler profile unchanged (46, zero real).**
- **Fix 1 (15c1ff56, nested-objlit contextual distribution):** getTypeOfObjectLiteral's ctxObj now
  resolves a UNION contextual type (sole non-nullish object member → NEW
  selectUnionMemberByObjLitDiscriminant via the round-411 canonical `symId#member` key space →
  key-coverage); a nested ObjectLiteralExpression VALUE inherits the property's contextual type;
  array-literal ELEMENTS inherit the contextual array's element type (+ the return path provides
  the array ref for a returned array literal). The guard-narrowed values (`node: parent` after
  isJsxOpeningLikeElement, `file: node` after isSourceFile) then ride the existing monotone
  ctxAcceptsNarrow. Cleared signatureHelp:379 + findAllReferences:1000 — BOTH reproduced in
  minimal single-file repros (no probe needed).
- **Fix 2 (ada176fd, any-spread var-decl objlit):** the round-445 spread-poisons-to-any rule
  existed only on the RETURN path — the var-decl missing-prop/coarse emission now bails too.
  Cleared completions:2391 (`{ ...baseWriter, … }` vs EmitTextWriter, baseWriter from an
  unresolvable `.js`-barrel namespace call).
- **Fix 3 (255a92f6, alias-TP name capture):** `currentTypeParamScope` is consulted BEFORE
  `currentTypeAliasArgs`, so inside an alias substitution a callee TP named like an alias TP
  captured the body's reference — `binarySearchKey<T, U>(…, keyComparer: Comparer<U>)` with
  `Comparer<T> = (a: T, b: T) => Comparison` resolved the body's `a: T` to the CALLEE's T, and
  the anchor mapper's T→Node2 binding typed the comparer callback param as Node2 → FP TS2538 on
  `children[middle]`. The substitution now shadows exactly its own TP names out of the scope.
  Cleared utilities:1750.
- **Fix 4 (36f98fbf, two guard-resolution fixes):** (a) a TP-referencing fn-typed PARAM skipped
  by the B516 gate now still registers in currentParamBindingNames — unshadowed, the call
  resolved through merged globals to a same-named cross-file top-level fn (documentHighlights'
  `getNodes` vs fixAwaitInSyncFunction's; reproduced 3-file). (b) a NEGATIVE guard branch
  (`!isModifier(node)`) collapsed `node` to never — the relation over-accepts `Node <: Modifier`
  (enum-any) — `kindDomainProvesNotSubtype` reads declared `.kind` DOMAINS (bare-enum = whole
  member set; generic token refs thread type-arg NODES through `extends` levels) and keeps t
  when its domain exceeds the target's. Cleared completions:2237.
- **Fix 5 (emitter:994 + program:1088):** (a) checkMemberAccessMissing's knockout single-interface
  branch now bails for Array/ReadonlyArray references — under REAL LIBS (`lib: es2020`, the bench
  tsconfig) a numeric element access `transform.transformed[0]` reached it with propName "0" and
  the lib Array InterfaceDeclaration passed every gate (the "whole-program-only" verdict was just
  the missing real-libs flag — `w.items[0]` reproduces in 3 lines with the bench tsconfig).
  (b) checkReturnAssignability retries a failing generic-REFERENCE target whose type args include
  the fn's OWN TPs with each TP bound to its declared CONSTRAINT — tsc's variance analysis accepts
  the contravariant-TP-usage shape (createTypeReferenceResolutionLoader returning a getter typed
  with the constraint itself); suppression-only, FN-not-FP; bare-TP targets excluded by the
  head-name/no-args gate.
- **Probe technique that unblocked the batch:** a temporary `init {}` block in the Diagnostic data
  class keyed on (code, start, fileName) printing `Exception().stackTraceToString()` — found the
  emitting walker in one services run each time (remember Checker.kt frame line numbers wrap
  mod 65536). Lesson: of the four "whole-program probe needed" verdicts, THREE dissolved into
  reproducible factors (cross-file name collision ×1, real libs ×1, enum-any negative wash ×1) —
  try a name-collision file and the bench tsconfig's `"lib": ["es2020"]` before believing a
  whole-program verdict.
- **Cross-profile at session end:** server 275 → 232 (−43), harness 481 → 429 (−52) — the six
  fixes generalize; bench rows appended (services 40.9 s self, +1.7% vs the round-471b band —
  no perf regression).
- **Fix 7 (the LAST real services FP — symbolDisplay:917/935 TS7034/TS7005): the truthy-guard
  rule, NOT the full evolving-any model.** A captured read of an auto-typed `let x;` inside an
  `if` whose condition TRUTHY-TESTS the variable (`if (flags & Sig && indexInfos)` /
  `x !== undefined`) is provably assigned (undefined is falsy) — the closure-position flow
  inside the guard carries the evolved non-undefined type, so tsc reports nothing.
  `ulCondProvesAssigned` + `UlState.guardDepth` gate the capturedReads recording; an UNGUARDED
  captured read keeps firing (controlFlowNoImplicitAny f9/f10 — re-derived from the reference
  baselines this round: BOTH the nested function decl AND the stored arrow error in tsc, so
  "past the last assignment" alone is NOT the suppressor; the guard is).
  **SERVICES @ 46 — ZERO REAL FPs** (TS2591×43 + TS2304×2 `global` + TS2584 console, all
  env-legit offline artifacts) — the SECOND profile at zero real, matching compiler.
- **Fixes 8–10 (same session): tsc-cli + deprecatedCompat to zero real too — SIX of eight
  profiles at zero real FPs.** The small-profile re-baseline showed tsc-cli @48 (TS7006×2) and
  deprecatedCompat @49 (TS7006×2 + TS2339×1); all three cleared: (8) the embedded lib's
  ObjectConstructor gains `getOwnPropertyDescriptor(s)` — the members were simply absent, and
  even under real libs the embedded `libGlobals` copy is consulted (deprecations.ts:82); the
  plural rides the existing es2017 LIB_MIN_TARGET entry. (9) An arrow's EXPRESSION body inherits
  the contextual signature's RETURN type (`contextualSigReturnTypeForCtx`) — the builder chain
  `overload: overloads => ({ bind: binder => ({ … }) })` contextually types each nested returned
  objlit's fn members (deprecations.ts:144/146). (10) `rhsCanConsumeFnCtx` accepts an objlit
  with fn-shaped members, and `namespaceMemberVarAnnotationCtx` resolves a `ns.Sub.member = {…}`
  target's declared annotation through the merged globals when the root is a namespace import
  whose declaration is the whole ImportDeclaration (tsc.ts:7 `ts.Debug.loggingHost =
  { log(_level, s) {…} }`).
- **NEXT:** burn down server (@230) / harness (@427) with the same listAll-diff workflow —
  their top codes (TS2322×87/TS2339×42 server; TS2339×109/TS2322×102 harness) are
  services-family shapes on server/harness-only files. v1 exit = all 8 profiles at zero real.**

**Round 471 (2026-07-10/11, the services burn-down continues) — NINE bounded fixes in 9 commits
(63287e70 / 3f5166da / 3766ef73 / 8524afe4 / 093df3f1 / 39b0fddf / c7781e76 / f9a81674 / bd70f5d6). Dashboard: services 72 → 56 (−16; TS2322 12 → 3, TS2345 5 → 2, TS2349 2 → 0, TS2741 2 → 0,
TS2420 1 → 0; 10 real left excl. TS2591×43 + TS2304×2 `global` + TS2584 console); every fix
repro-verified both directions before landing. Suite 9,958 → 9,987 (+29 local across 8 new test
files, 0 regressions).**
- **Fix A (63287e70, array-literal literal elements):** a fresh array literal keeps its elements'
  literal types in ARRAY-LIKE contextual positions — literalTypeOfExpression's new
  ArrayLiteralExpression arm gated on the `arrayCtx` flag (an ungated arm regressed
  assignmentIndexedToPrimitives: `const n4: 0 = [0]` must stay `number[]`), the
  one-literal-arm ConditionalExpression relaxation, propTypeContainsLiteral's Array/ReadonlyArray
  Reference arm, and the same rule in the per-element walkers (a wrong literal element now
  displays as its literal `"q"` like tsc). Cleared services.ts:1585 + organizeImports:216 +
  (bonus, with C/E) services.ts:1327 ObjectAllocator and completions:1922.
- **Fix B (3f5166da, optional-read objlit write target):** an objlit member whose value was an
  OPTIONAL-member read is `T | undefined` in tsc (the round-424 optionality-is-a-symbol-attribute
  gap) — objLitMemberValueWasOptionalRead widens the WRITE target only. Cleared organizeImports:115.
- **Fix C (3766ef73, boolean || truthy facts):** `preferences.includeCompletionsWithSnippetText ||
  undefined` is `true | undefined` (tsc getTypeWithFacts Truthy); gated when the RIGHT side is
  boolean-like so `boolA || boolB` stays `boolean` (our getUnionType has no subsumption).
  Cleared completions:2299.
- **Fix D (8524afe4, enum VALUE-domain exhaustion):** an enum ALIAS member
  (`NameContainsNonURISafeCharacters = NameContainsInvalidCharacters`) counts as covered when a
  covered member has an EQUAL value — tsc's case narrowing removes every member with that value.
  Cleared jsTyping:414 (the barrel-assertNever family's last member).
- **Fix E (093df3f1, intersection call signatures):** getCallSignaturesOfType concatenates a
  Type.Intersection's constituents' sigs (tsc getSignaturesOfStructuredType) — a `Fn & Fn` union
  member read as non-callable and FP'd TS2349. Cleared textChanges:706 + callHierarchy:263.
- **Fix F (39b0fddf, lib-phantom members, Blocker #3):** a module file's own top-level
  `interface Symbol` merges with the same-named LIB global, demanding es2019's `description` —
  isLibPhantomMemberOfModuleInterface (moduleInterfaceNames + lib-only prop declarations) skips
  such members in propertiesRelatedTo / collectMissingProperties / getMissingRequiredPropertySymbol
  / the TS2420 loop. FN-not-FP; script-file lib augmentations don't trip the gate. Cleared
  services.ts:656 (TS2420) + :725 (TS2345).
- **Fix G (c7781e76, nested guard shadows global, M1.11):** fixMissingTypeAnnotationOnExports
  nests `isConstAssertion(location): location is AssertionExpression` while compiler/utilities
  exports a NON-guard `isConstAssertion` — the merged-globals hit killed the narrowing.
  resolveFlowCalleeDecl prefers the unique predicate-bearing nested decl of the CURRENT file
  (nestedFnDeclFiles, per-file batches in buildNestedFunctionMap) over a non-predicate globals
  hit; the negative pin proves the guard fires (the still-firing error displays the NARROWED
  type). Cleared fixMissingTypeAnnotationOnExports:452 ×2.
- **Fix H (f9a81674, flow-narrowed inference anchors, M3.1):** `focusLocations &&
  flatten(focusLocations)` read the RAW nullable union for inference (the (k) tp[][] anchor
  soft-skipped, T unbound) while the arg CHECK narrowed — the (k)/(l) anchors now read
  Identifier/PropertyAccess args' flow-narrowed type under the objLitValueNullishStrip gate.
  The negative pin proves T binds (the return relates as TextSpan[] and fails string[]).
  Cleared mapCode:55.
- **Fix I (spread-of-target + declared extras):** `fix = { ...fix, ...(addAsTypeOnly ===
  undefined ? {} : { addAsTypeOnly }) }` FP'd — the B426 spread merge keeps only GUARANTEED props.
  objectLiteralSpreadOfTargetSatisfies suppresses at the assignment path when a spread's
  (flow-narrowed) type relates to the target and every extra key is declared in SOME target union
  member with a relating value type (conditional-spread arm values read their flow-narrowed type).
  Undeclared keys / wrong-typed extras keep firing. Cleared importFixes:316 + :374.
- **Scouted, deferred with findings:** completions:2237 (a faithful nested-fn repro with
  isIdentifier + identifierToKeywordKind passes — whole-program probe needed);
  program.ts:1088 ResolutionLoader<T> (a faithful generic-loader repro passes — probe);
  emitter:994 `transformed[0]` (round-468 finding stands — probe); signatureHelp:379 +
  findAllReferences:1000 (+ the completions family) = ONE family: a nested objlit member with an
  ENUM-member discriminant (`invocation: { kind: InvocationKind.Call, node }`) vs a
  kind-discriminated union member — needs the round-411 canonical-key space wired into
  objlit-vs-union member selection; importFixes:316/374 = `fix = { ...fix, ...(cond ? {} :
  { extra }) }` — a spread-of-target-typed-value + target-declared extras rule (design sketched:
  first spread's narrowed type relates to target + every extra key declared in SOME union member
  with a relating value type); symbolDisplay:917/935 TS7034/7005 (the evolving-any model, round-470b
  finding stands); utilities:1750 TS2538 (binarySearchKey keySelector-return inference).
- **Round 471b PERF episode (same session, caught by the bench row): the nine fixes had doubled
  the services self-compile (39 → 92 s) — bisected to TWO independent costs, both fixed, final
  41.5 s at the same 56 diagnostics.** (1) Fix G's `HashMap<FunctionDeclaration, String>` file map:
  AST nodes are data classes, so every lookup DEEP-HASHED the whole function subtree, quadratically
  re-checked per file (+10 s alone) → replaced by cluster-order-aligned file LISTS written during
  the per-file walk (see the new CLAUDE.md Kotlin-idioms gotcha). (2) Fix F's propertiesRelatedTo
  hook removed the missing-prop EARLY EXIT from every relation against a lib+module-merged
  interface (Symbol), re-running full member-type comparisons (39 → 77 s measured at the F commit)
  → the relation-hook skip is now gated to a CLASS-instance source (the SymbolObject family —
  exactly the FP's shape) and the phantom-member sets are memoized per symbol id
  (libPhantomMembersCache). Lesson recorded: run the bench row BEFORE the docs commit, not after
  the round wraps.
- **NEXT (services @ 56, 10 real):** the nested-objlit enum-discriminant family
  (signatureHelp:379 / findAllReferences:1000); completions:2391 TS2740 EmitTextWriter (objlit of
  any-typed fn members vs a 22-member interface — probe why the missing-set lists 18); the
  whole-program probe batch (emitter:994 / program.ts:1088 / completions:2237 /
  documentHighlights:193); symbolDisplay:917/935 evolving-any (needs the model — round-470b
  finding); utilities:1750 TS2538 (keySelector-return generic inference).**

**Round 467 (2026-07-10, same session as 466) — the services burn-down starts: FIVE bounded
fixes. Dashboard: services 126 → 108 (−18; TS7006 7 → 1, TS2339 8 → 6, TS2322 40 → 30); every
step verified strictly-removals by listAll diff. Suite 9,863 → 9,877 (+14 local across 5 new test
files, 0 regressions); 5 fix commits (d9f0ecab / cfa323d0 / 843f8ce9 / f06586f8 / e1c54010).**
- **Fix 1 (d9f0ecab, TS7006 array-element ctx + nested-interface annotation retry; 126 → 120):**
  the round-443 revert closed — checkImplicitAnyInExpr's ArrayLiteral branch now propagates the
  array's ELEMENT type into elements (object literals get member fn context, arrows the
  callable-arity suppression; a UNION contextual type yields NO element type, preserving the
  pinned contextualSignatureInArrayElement* rule). The round-443 blocker fell to
  `uniqueNestedInterfaceByName` + `nestedInterfaceCtxType` (transient synthetic symbol, memoized
  by decl; gates: unbound-anywhere + unique + non-generic) feeding
  `resolveImplicitAnyCtxAnnotation`'s bare-`X`/`X[]` retry — inferFromUsage.ts's function-body
  `interface Priority` + `const priorities: Priority[] = [{ high: t => …, low: t => … }]` ×6.
- **Fix 2 (cfa323d0, `switch (typeof ref)` clause narrowing, M3.4; 120 → 118):**
  narrowBySwitchClause gains a TypeOfExpression subject arm — each clause narrows the walked
  reference by its string tag via narrowByTypeOfGuard (positive for the matched range's tags,
  negative for prior cases, negative-by-all for a default; non-string-literal cases bail). The
  round-425 "object"-tag verdict already did the filtering — only the switch-subject arm was
  missing (completions.ts:1477 `type.value.negative` on `string | number | PseudoBigInt` under
  `case "object":`, TS2339 ×2).
- **Fix 3 (843f8ce9, inference gate (l) — the compact idiom, M3.1; 118 → 116):** an
  Array/ReadonlyArray param whose element union is exactly one bare TP plus DROPPABLE members
  (nullish / falsy literals) — core.ts `compact<T>(array: (T | undefined | null | false | 0 |
  "")[]): T[]` had no accepting gate, so the bare-`T[]` overload won and bound T WITH undefined
  (smartSelection.ts:336 `(SyntaxList | Node | undefined)[]` vs `readonly Node[]` ×2). The
  candidate is the arg's element union minus members assignable to a droppable.
- **Fix 4 (f06586f8, guard-narrowed array-literal returns, M3.4; 116 → 111, −5):**
  `narrowedArrayLiteralType` re-types an array literal from its flow-narrowed
  Identifier/PropertyAccess elements; the direct-return path and the ternary-arm path substitute
  it ONLY when it makes the return relation pass (monotone) — getTypeOfArrayLiteral's own
  element narrowing deliberately accepts only nullish strips (round 459's shadowing hazard), so
  `if (isThrowStatement(node)) return [node];` built `Node[]` vs
  `readonly ThrowStatement[] | undefined`. The 2 targeted documentHighlights.ts sites PLUS
  jsDoc.ts:238 and extractSymbol.ts ×2 generalized.
- **Fix 5 (e1c54010, `||`-nested conflated objlit returns, Blocker #3; 111 → 108):** the
  round-445 note's prescribed extension — `return noSymbolError(name) || { exportNode, … }`
  (convertExport.ts ×3): the RIGHT object literal routes through
  objectLiteralMatchesConflatedFileLocalInterface (this file's OWN `interface ExportInfo`, not
  the cross-file merged pollution), the LEFT operand's non-falsy type must relate on its own;
  `??` covered too. Suppression-only.
- **NEXT (services @ 108, ~62 real):** organizeImports ×3 (heterogeneous: `??`-RHS literal
  widening in a contextual position at 954; ternary-arm array-literal member context at 216;
  destructured-member `??` write at 115); documentHighlights:193 (`Node` vs `SourceFile` arg);
  the conflated-Info return family residual (importTracker ×2 /
  findAllReferences ×2 / signatureHelp / fixExpectedComma / inlineVariable /
  convertToOptionalChain — objlit-with-any-members / nested-shape variants the round-447
  strict check rejects); the services.ts objlit giants (ObjectAllocator /
  CompletionEntry / EmitTextWriter TS2740); mapCode.ts:55 flatten (gate-(k) candidate needs a
  probe — the arg display resolves but T stays raw); stringCompletions `.types`/`.value` on
  `Type` (public-API `isUnion()`/`isStringLiteral()` this-guard modeling).**


**Round 466 (2026-07-10) — Blocker #2 landed for the compiler profile: map-callback return
inference through nested functions clears builder.ts:2390, the LAST real compiler FP. Dashboard:
compiler 47 → 46 (**ZERO real FPs — all 46 are env-legit TS2591×43 require / TS2304×2 `global` /
TS2584 console, waiting on real @types/node**), services 127 → 126 — both listAll diffs are
EXACTLY the one removal. Suite 9,855 → 9,863 (+8 local in MapCallbackReturnInferenceTest, 0
regressions); 1 fix commit (e12b905b). Bench: self ~28 s (normal band).**
- **The chain (one FP, five mechanisms):** `arrayToMap(diagnostics, value => toFilePath(value[0]),
  value => value[1])` must select the generic `Map<K, V2>` overload with K := Path, which needs the
  arrow body typed through: `toFilePath` (UNIQUE nested fn, un-annotated) → `filePaths[fileId - 1]`
  → `filePaths = buildInfo.fileNames?.map(toPathInBuildInfoDirectory)` (AMBIGUOUS 2-decl nested
  callback) → both bodies `return toPath(…)` (BARREL-imported, and the merged-globals `toPath`
  resolves to tsbuild's same-named 2-param NESTED fn — the round-440 pollution family).
- **Mechanisms landed (all in tryInferSingleTypeParamFromArgs / getReturnTypeOfCallExpression):**
  (a) NAMED-function callback args bind a return-position TP from the fn's return type, with
  all-candidates-agree resolution for ambiguous nested names (`namedFnCallbackReturnType`);
  (b) `tryNestedFnCallReturnType` — a call to a body-nested fn types its return (annotated, or
  single-return body inference with the decl's own params scoped; depth 3; first-touch memo);
  (c) a barrel-imported Identifier callee resolves through the flow-only import resolver INSIDE
  inference bodies (lexical import beats polluted globals); (d) ReadonlyArray.map is now generic
  (mirrors Array.map — corpus-green); (e) callback-return positions accept `K | undefined`,
  anchor-able TPs gather before callback-return TPs, and branded intersections
  (`string & {__pathBrand}`) count as named-like candidates.
- **Measured gates (each violated cut produced a real FP on the profile):** the nested-fn return
  capability is INFERENCE-BODY-SCOPED (`inInferenceBodyTyping`) — the program-wide version was
  net +8 (checker.ts createTypeChecker objlit, getNodeLinks property writes, classFields receiver,
  commandLineParser objlit: concrete types riding M3 relation gaps); the barrel-callee pre-step is
  gated NON-generic (`SortedReadonlyArray<T>` resolves garbage without the TP scope) + BODIED
  (a bodyless decl is one OVERLOAD of a cluster — `sortAndDeduplicate`'s non-generic overload) +
  no-explicit-type-args; a heterogeneous ARRAY-LITERAL callback body contributes NO candidate
  (tsc contextually tuple-types it — builder.ts:1332 `map(key => [toFileId(key), …])`).
- **Tooling trap (CLAUDE.md gotcha added):** `pkill -9 -f KotlinCompileDaemon` KILLS THE INVOKING
  SHELL — the -f pattern matches the bash -c command line itself (the pgrep self-match gotcha's
  pkill variant); several compound commands silently died mid-chain. Use `'KotlinCompile[D]aemon'`.
- **NEXT: the services profile burn-down (126 — TS2322×40 / TS2345×12 / TS2339×8 / TS7006×7 …),
  then server (last measured 402 at round 458) / harness (615). v1 = all 8 profiles at zero FPs.**


**Round 465 (2026-07-10) — bounded FP burn-down to the LAST real compiler FP: SEVEN fixes across
8 sites. Dashboard: compiler 55 → 47 (−8; TS2322 6 → 1, TS2345/TS2339/TS2349 → 0), services
139 → 127 (−12 cross-profile). Suite 9,836 → 9,855 (+19 local across 7 new test files, 0
regressions); 7 fix commits (5b589394 / 97263c21 / 6102e8ed / 9a7d2b35 / 20894a34 / f521582c /
ba0a4e5a). Bench: self 28.3 s (normal band).**
- **Fix 1 (5b589394, union-in-intersection kind-reduction; 55 → 54):** a property read on
  `Union & Interface` reduces the union constituent's members by `.kind` disjointness
  (typeGuardMemberDisjoint) against the sibling constituents before folding, so
  `(NamedEvaluation & BinaryExpression).left` resolves through the surviving
  `AssignmentExpression & { left: Identifier }` members to Identifier, not BinaryExpression's
  wide Expression (namedEvaluation.ts:434 TS2345). COMPANION: the intersection contribution
  fold dedupes by Type.id — a self-intersection `Expression & Expression` made downstream arg
  checks bail where the plain type correctly fires (pinned by a negative control).
- **Fix 2 (97263c21, destructured member from callee body, M3.4; 54 → 53):**
  `({ referencedName, name } = visitReferencedPropertyName(member.name))` — the RHS calls a
  NESTED un-annotated fn, so the round-460 overwrite narrowing kept the antecedent. New
  destructuredMemberNonNullishFromCalleeBody: every callee return must be an object literal
  carrying the member with a non-nullish value; identifier values resolve through the callee's
  OWN params + body-local const decls (the new bodyDecls map in retExprNonNullishForFlow —
  getTypeOfIdentifier would resolve the CALLER's same-named nullable binding during the walk).
  Cleared esDecorators.ts:1309 (the round-464 'two more mechanism layers' item — one layer
  sufficed).
- **Fix 3 (6102e8ed, intersection shared-member rule, M3.1; 53 → 51, TWO sites):**
  intersectionMergedContradictsTarget's first-decl-wins merge was wrong whenever a later
  constituent REFINES a member — tsc gives a multiply-declared member the INTERSECTION of the
  declared types, so contradiction now requires EVERY declaration to fail (any relating
  declaration proves the intersected member relates). Cleared parser.ts:9581 AND
  factory/utilities.ts:1688 — **the round-461 'whole-program-only' verdict was WRONG: the pair
  reproduces single-file once the interface itself declares the shared member** (`node as
  AssignmentExpression<EqualsToken> & { readonly left: GeneratedIdentifier }` vs its own
  annotation; bisected by deleting AssignmentExpression's own `left`).
- **Fix 4 (9a7d2b35, fn-AWARE generic member instantiation, M3.1; 51 → 50):** instantiateType
  no-ops fn-shaped objects (the documented gotcha), so a generic member's fn-typed RETURN kept
  the raw outer T: `select(index): ((node: T) => T) | undefined` as
  OrdinalParentheizerRuleSelector<TypeNode> failed its conforming initializer (emitter.ts:1277;
  Diagnostic-init stack probe located the emission, member-shape bisection V3–V6 isolated the
  method-return + fn-property-nested-fn shapes). New instantiateTypeFnAware /
  instantiateSignatureFnAware (fresh instances, never mutation, sig TPs preserved) wired at
  resolveGenericPropertyTypeWorker's MethodDeclaration RETURN and
  substituteOuterTypeArgsInSignature's return/params.
- **Fix 5 (20894a34, concise-arrow captured narrowing, M3.4 × B464; 50 → 49):**
  getTypeOfArrowFunction's concise-body branch now consults
  getNarrowedTypeForReferenceFollowLoopEntry for a bare-reference nullable-union body —
  the B464 flow-into-closures continuation (reassigned-after gates) proves
  `packageJsonInfoCache ??= create…; return { getPackageJsonInfoCache: () =>
  packageJsonInfoCache }` non-nullish. Accepted ONLY as an EXACT nullish strip (member-id set
  equality with narrowByExcludingNullUndefined). Cleared moduleNameResolver.ts:1300.
- **Fix 6 (f521582c, Exclude-filter kind disjointness, M3.4; 49 → 48):** probe-confirmed the
  round-457 `asserts node is Exclude<T, U>` branch REACHED es2020.ts flattenChain with
  uType=NonNullChain resolved and union=4 — but kept=0: enum-member kinds resolve `any`, so the
  lenient relation related EVERY brand-intersection member (PropertyAccessChain =
  PropertyAccessExpression & { _optionalChainBrand }) to NonNullChain. The filter now keeps a
  member whose kind keys are provably disjoint (the round-423 narrowByCallPredicate lesson).
  Cleared es2020.ts:91 — the round-457 'whole-program resolution scale' diagnosis was actually
  the lenient relation, visible only where member types resolve fully.
- **Fix 7 (ba0a4e5a, IIFE-const fn calls, M3.4; 48 → 47):**
  callRhsHasNonNullishReturnAnnotation's VariableDeclaration branch gains a CallExpression case
  — iifeReturnedFunctionTriple resolves `const f = (() => { return g; function g(…): R {…}
  })()` (no-arg IIFE, block body, first top-level return naming a same-block nested fn or an
  inline fn) to the returned function's annotation, so `uiComparerCaseSensitive ??=
  createUIStringComparer(uiLocale); return uiComparerCaseSensitive(a, b)` proves the callee
  non-nullish. Cleared core.ts:2135 TS2349 (the round-463 'hard' item — the classifier angle
  made it bounded).
- **NEXT: compiler @ 47 = ONE real FP + 46 env-legit (TS2591×43 require / TS2304×2 global /
  TS2584 console).** builder.ts:2390 is the genuine Blocker #2 chain: arrayToMap's K := Path
  needs the makeKey callback's return through nested `toFilePath` → element access on
  `filePaths = buildInfo.fileNames?.map(toPathInBuildInfoDirectory)` → Array.map return
  inference from a NAMED-fn callback whose name is AMBIGUOUS (2 decls, both un-annotated
  returning `toPath(…)` = Path) — needs map-callback return inference + all-candidates-agree
  ambiguous-fn resolution. Then the services profile burn-down (127).**

**Round 464 (2026-07-10) — bounded FP burn-down: SIX fixes. Dashboard: compiler 61 → 55 (−6;
TS2322 10 → 6, TS2362 → 0, TS7006 → 0), services 154 → 139 (−15 cross-profile). Suite 9,817 →
9,836 (+19 local across 7 new test files, 0 regressions); 6 fix commits (ecf6290d / 44e1b2ff /
6fcf7cda / f1e48c81 / 2d843068 / 6fe6406d).**
- **Fix 1 (ecf6290d, barrel-enum member non-nullish; 61 → 59):** `receiverResolvesToRealEnum`
  only followed the general resolveAlias (can't follow ESM-`.js` + `export *` barrels), so
  `flags = flags || NodeBuilderFlags.None` (tsc checker.ts withContext) never proved `flags`
  non-nullish — TS2362 at checker.ts:6639 AND TS2322 at 6640 (the objlit shorthand member) with
  ONE root cause. Falls back to the flow-only `resolveImportedEnumSymbol` (round 411, memoized),
  mirroring `resolveEnumSymbolForDiscriminant`. Reproduced minimally (3-file barrel repro).
- **Fix 2 (44e1b2ff, generic inference from flow-narrowed args, M3.4 × M3.1; 59 → 58):**
  `tryInferSingleTypeParamFromArgs` bound T from `getTypeOfExpression`'s DECLARED type — a
  switch-narrowed union arg bound T to the full union (`const name = cloneNode(node)` under
  `case SyntaxKind.Identifier:` → return FP'd `EntityName ⊄ SerializedEntityName`,
  typeSerializer.ts:603). Gated: union-declared bare Identifier/PropertyAccess args, narrowed
  type must be a non-never/any refinement still relating to the declared type — inference only
  gets MORE precise. Shared-inference change → full corpus + strictly-removals listAll gates.
- **Fix 3 (6fcf7cda, TS7006 destructured-source context, M3.2; 58 → 57):** a THIRD parallel
  implicit-any scope stack (`implicitAnyScopeDestructures`: element name → source expr +
  property name, push/pop ONLY via push/popImplicitAnyScope) lets an assignment target rooted
  at a destructured local (`const { parseConfigFileHost } = state; parseConfigFileHost.on… =
  d => …`, tsbuildPublic.ts:594) resolve its contextual type from the source's declared member.
  Top-level elements only (nested/rest unrecorded — bounded).
- **Fix 4 (f1e48c81, flow non-nullish cluster; 57 → 56):** FOUR coupled pieces clear
  getTypeAtFlowNode's `return type;` (checker.ts:29132, `let type: FlowType | undefined`
  assigned in every branch): (a) ternary RHS non-nullish iff BOTH arms; (b)
  typeNodeDefinitelyNonNullish's globals fallback accepts an UNAMBIGUOUS barrel type ALIAS and
  recurses its body — gate counts TypeAliasDeclarations, NEVER list size (the merged
  declarations list is polluted with importers' ImportSpecifiers); (c) an un-annotated param
  DEFAULTED from an annotated PRECEDING sibling (`initialType = declaredType`) types as the
  sibling's annotation (checkFunctionBody + populateParameterLocalTypes); (d) an UN-ANNOTATED
  callee proves non-nullish from a bounded body-return scan (bare `return;`/opaque statements
  fail; ternary identifier leaves resolve via the callee's own params → getTypeOfIdentifier →
  the new program-wide `uniqueNestedVarDeclByName` — tsc's `convertAutoToAny` whose leaves are
  `var anyType = createIntrinsicType(…)` closure vars). Diagnosed by an XPROBE cascade
  (Diagnostic-init stack probe → checkReturnAssignability entry → narrowByAssignmentRhs
  per-branch); **TOOLING TRAP (CLAUDE.md gotcha added): the bench files are CRLF, so python
  text-mode offsets understate checker positions by one per line — 3 probe iterations lost to
  ranges that silently missed.**
- **Fix 5 (2d843068, barrel checkDefined returns + PA-RHS narrowing; dashboard-neutral,
  repro-pinned):** `Debug.checkDefined(x)` through the barrel resolves its RETURN as the arg
  minus nullish (`tryBarrelCheckDefinedReturn`, shape-gated like the round-461 flow classifier
  — no general barrel resolution in the type path, the round-409 TS2315-flood hazard);
  `narrowByAssignmentRhs` gains a PropertyAccess-RHS arm mirroring the round-463 Identifier arm
  (`end = importLiteral.end`). program.ts:1220's chain source improves `{ file: any; … }` →
  `{ file: SourceFile; … }`; the site itself still FPs because the pos/end objlit-VALUE
  narrowing needs a contextual type and the round-462 return-path objlit context only accepts a
  union target's SOLE non-nullish object member — this target has TWO
  (`ReferenceFileLocation | SyntheticReferenceFileLocation`). Next layer scoped.
- **Deferred with findings:** esDecorators.ts:1309 needs destructured-member-from-un-annotated-
  nested-callee resolution PLUS method-calls-on-destructured-receivers (`factory` is itself
  destructured from `context`) — two more mechanism layers on the fix-3/fix-4 machinery;
  builder.ts:2390 needs callback-RETURN inference through an un-annotated same-file fn
  (Blocker #2); namedEvaluation.ts:434 needs `(Union & Interface).left` kind-discriminant
  member reduction (tsc reduces union members whose `kind` conflicts with the interface's).
- **Fix 6 (destructured-const element typing + multi-member-union objlit context; 56 → 55):**
  `recordDestructuredConstElementTypes` types a destructured const's TOP-LEVEL elements from the
  source's declared members (`const { kind, index } = ref` — B83.5-unbound, so `index` was anyType
  and `file.referencedFiles[index]` resolved any, defeating the pos/end destructuring-overwrite
  narrowing; the probe cascade showed the ELEMENT-ACCESS INDEX, not the receiver, was the leak).
  Conservative: absent names only, non-union/any sources, no defaults/rest/nested; **FUNCTION-shaped
  member types stay unrecorded** — recording them rode the fn-type relation's M3 gaps
  (tsbuildPublic.ts:767 unmasked on the first cut; the detector must resolveStructuredTypeMembers
  and check union constituents, the fn types arrive lazily-membered / `| undefined`-wrapped).
  PLUS `selectUnionMemberByObjLitKeys`: a MULTI-object-member union return target contributes the
  SINGLE constituent whose members cover every objlit property name as the contextual type.
  program.ts:1220 CLEARED (56 → 55, strictly removals by listAll diff).
- **NEXT (compiler @ 55, ~9 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):**
  emitter.ts:1277 (select-return);
  moduleNameResolver.ts:1300 (getPackageJsonInfoCache `| undefined` member);
  esDecorators.ts:1309; namedEvaluation.ts:434 (kind-reduction); parser.ts:9581 /
  factory/utilities.ts:1688 (cast-instantiation identity, M3); builder.ts:2390 (Blocker #2);
  es2020.ts:91 + core.ts:2135 (known-hard).**

**Round 463 (2026-07-10) — bounded FP burn-down: NINE fixes — minimal-repro fixes, one measured
UN-GATE of a historical skip, and two new flow-narrowing mechanisms. Dashboard: compiler 72 → 61
(−11; TS2322 15 → 10, TS2339 2 → 1, TS2345 2 → 1, TS2353/TS7053/TS18048/TS2589 all → 0). Suite
9,794 → 9,817 (+23 local across 9 new test files, 0 regressions); 9 fix commits (6075703a / 610bf2a0 / fd3a7003 / 78001791 / 9a55bd1a / 8e6ec34c / 83f27992 / cac642c6 / 181a850a).**
- **Fix 1 (6075703a, never array element):** `checkArrayLiteralElementExcessProps`'
  primitive-element-vs-object branch skipped Null/Undefined/Void/Any but not NEVER — `[undefined!]`
  (never per B282) vs `Expression[]` FP'd TS2322 (taggedTemplate.ts:50). TypeFlags.Never added.
- **Fix 2 (610bf2a0, fn-type alias instantiation UN-GATE; with fix 1: 72 → 68):** the historical
  B50.1 skip (FunctionType/ConstructorType/call-sig-only-TypeLiteral alias bodies never substitute)
  left `TransformerFactory<SourceFile | Bundle>`'s body `T` an UNBOUND TypeParam that FAILS the
  relation against a concrete conforming source (`return transformModule` ×3, transformer.ts:83/98/
  100 — whole-program-only: small programs resolve the unbound T to errorType and pass vacuously;
  found by probing the resolved target's call-sig structure). The skip's historical FP hazard is
  covered by the round-431 foreign-TP source gates. Measured: corpus green + strictly-removals.
  `isFunctionTypeAliasBody` deleted; the CLAUDE.md gotcha prescribing the gate REWRITTEN.
- **Fix 3 (fd3a7003, Identifier body-local vs merged-globals shadow, call-types pass; 68 → 67):**
  `shadowCallTypesDeclList`'s Identifier branch only overrode an INHERITED currentLocalTypes entry —
  a for-of loop-header `const patternText` colliding with core.ts's exported `function patternText`
  resolved through globals in arg position → TS2345 `'() => string'` vs `'string'`
  (moduleSpecifiers.ts:929, the long-standing scouted item). Global-colliding Identifier locals now
  register into currentParamBindingNames (anyType; a concrete recording still wins).
- **Fix 4 (78001791, mapped-type unknowable key domain; 67 → 66):** `getTypeFromMappedType`'s
  union-constraint enumeration used mapNotNull, silently DROPPING non-string-literal constituents —
  `[K in keyof T & CompilerOptionKeys | StrictOptionName]` with T un-inferred enumerated only the
  strict keys and the PARTIAL domain manufactured excess TS2353 (utilities.ts:9042). Any
  non-enumerable constituent now bails the whole mapped type to anyType.
- **Fix 5 (9a55bd1a, annotated-decl skip in nearestPrecedingObjectLiteralDecl; 66 → 65):** the B290
  element-access receiver-shape recovery matched ANNOTATED decls, keying the noImplicitAny index
  checks off the initializer literal — `const result: ExtendsResult = { options: {} }` made
  `result[propertyName]` FP TS7053 even though the access-site `result` was the nested fn's
  annotated param (commandLineParser.ts:3466). Annotated decls skip to the typed path.
- **Fix 6 (8e6ec34c, NULLISH-MIRROR overload pairs in flow narrowing, M3.4; 65 → 64):** the
  round-424 documented overload-cluster deferral closed for `f(x: T, …): R;` / `f(x: T | undefined,
  …): R | undefined;` (+ impl — tsc instantiateType): tsc picks the FIRST applicable overload, and
  between mirror sigs the ONLY applicability dimension is arg nullishness, so a provably
  non-nullish arg selects the first (non-nullish) sig (`nullishMirrorOverloadNonNullish`;
  buildNestedFunctionMap retains full clusters). PAIRED: typeNodeDefinitelyNonNullish falls back to
  merged globals for a barrel-imported Alias, interface/class/enum ONLY (TypeAlias would re-open
  the round-443 conflation trap). Cleared checker.ts:21170 TS18048; nullable-arg and
  nullish-FIRST-order negative controls pinned.
- **Fix 7 (83f27992, deferred-position recursive UNION alias cycle-break; 64 → 63):**
  `WrappedExpression<T> = OuterExpression & { expression: WrappedExpression<T> } | T` depth-bailed →
  spurious TS2589 (utilities.ts:5553). The B57 lazy cycle-break extends to UNION bodies whose every
  member is deferred-position (`unionBodyIsDeferredPositionOnly`) — an INDEXED-ACCESS/MAPPED member
  FORCES evaluation, and the first ungated cut regressed exactly that corpus pin
  (recursivelyExpandingUnionNoStackoverflow expects TS2589+TS2615).
- **Fix 8 (cac642c6, TS 5.5 INFERRED TYPE PREDICATES, bounded slice; 63 → 62):**
  `filter(getEmitHelpers(sf), helper => !helper.scoped)` — a single-expression boolean-literal-
  discriminant arrow in a guard-overload callback position infers `helper is UnscopedEmitHelper`
  (`inferDiscriminantArrowPredicateTarget` in tryInferPredicateOverloadReturn); without it the
  non-guard overload returned the full union and `.importName` FP'd TS2339
  (factory/utilities.ts:713, the round-462 scouted finding). TRAP: LiteralType `true`/`false`
  literals parse as Identifier nodes (KEYWORD_IDENTIFIERS), not TrueKeyword/FalseKeyword kinds —
  the first cut silently bailed on every member.
- **Fix 9 (identifier-RHS assignment narrowing, M3.4; 62 → 61):** a plain `=` with a bare-Identifier
  RHS filters the antecedent union by the RHS's resolved type (`narrowUnionByRhsAssignment`, member
  identity preserved for the round-459 subset gates) — `result = node` narrows `VisitResult<T> =
  T | readonly Node[]` to T so the later `result = [staticBlock, result]` array element reads the
  member (esDecorators.ts:1485). NON-UNION RHS only: a union RHS routed through the LENIENT member
  relation (enum-member kinds resolve `any`, round-423) filtered a JsxCallLike union to its
  property-poorest member — caught by the AliasedConditionAndUnionPredicateTest reassignment
  control on the first full-suite run, gated before commit.
- **NEXT (compiler @ 61, ~15 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):** the
  big-objlit M3 family (moduleNameResolver.ts:1300 / emitter.ts:1277 / checker.ts:6640);
  program.ts:1220 (checkDefined RETURN TYPE resolution); esDecorators.ts:1309 (destructured
  referencedName union); typeSerializer.ts:603 (switch-case narrowing must feed generic clone-chain
  inference); parser.ts:9581 / factory/utilities.ts:1688 (cast-instantiation identity, M3);
  builder.ts:2390 `string → __String` branding; checker.ts:29132 evolving-let FlowType;
  namedEvaluation.ts:434; es2020.ts:91 (Exclude at whole-program scale); core.ts:2135 (scouted:
  needs IIFE-const return inference — the `??=` RHS `createUIStringComparer(uiLocale)` calls a
  `const = (() => {…})()` whose type we cannot resolve, hard); tsbuildPublic.ts:594 TS7006
  (cross-barrel); checker.ts:6639 TS2362 (barrel enum, known-hard).**

**Round 462 (2026-07-10) — probe-driven burn-down: SIX fixes; FOUR needed instrumented
whole-program probes (minimal repros clean or misleading — XPROBE prints + a stack-trace probe on
the Diagnostic constructor). Dashboard: compiler 79 → 72 (−7; TS2322 19 → 15, TS2345 4 → 2,
TS2339 3 → 2), services 165 → 154 (−11 cross-profile). Suite 9,782 → 9,794 (+12 local across 5
new test files, 0 regressions); 6 fix commits (a4c8b186 / 9810df0f / f6e2456f / e2e99396 /
99181256).**
- **Fix 1 (a4c8b186, call-arg narrow-DOWN gate; compiler 79 → 77):** the round-428b/429c/438
  branch required the narrowed type to refine the DECLARED type (`n <: declared`) — but tsc's
  getNarrowedType(assumeTrue) legitimately narrows to a guard target OUTSIDE the declared
  hierarchy (isPropertyNameLiteral's PropertyNameLiteral union contains JsxNamespacedName, which
  does not extend Expression), so a genuine narrow was rejected → the wide Expression FP'd TS2345
  ×2 (utilities.ts:4066 isSameEntityName). An XPROBE on the real bench project showed
  refines=false / matchesParam=true — the shape does NOT reproduce minimally (the conservative
  union-param gate skips small repros). The gate now also accepts a narrowed type that makes the
  PARAM relation pass (the standard monotone rule).
- **Fix 2 (9810df0f, objlit property-value contextual narrow-DOWN; 77 → 76):** `return { class:
  node.parent.parent, … }` after `isClassLike(node.parent.parent)` kept the wide Node — the
  round-438 nullish-strip gate alone rejects narrow-DOWNs (utilities.ts:7458). Two pieces:
  checkReturnAssignability provides the objlit CONTEXT (a union target contributes its sole
  non-nullish object member — mirrors the call-arg B83.4g), and getTypeOfObjectLiteral's value
  narrowing extends to PropertyAccess initializers when the contextual property accepts the
  narrow and rejects the raw. **PERF LANDMINE (measured + fixed pre-commit): an unconditional
  getNarrowedTypeForReference on every objlit PropertyAccess value program-wide cost +352%
  self-compile time (28.9 s → 130.5 s) — the walk now runs only when the raw type FAILS the
  cached contextual-property relation (31.4 s, normal band).**
- **Fix 3 (f6e2456f, prefix-path guard tail substitution; 76 → 75):** the round-424 prefix-path
  branch (guard on a RECEIVER prefix of the walked path) made only the drop-nullish claim; tsc
  re-types `x.y` from the NARROWED x — `isComputedPropertyName(parent)` makes `parent.parent`
  ComputedPropertyName's declared `parent: Declaration` (utilities.ts:5085). Now substitutes the
  resolved tail, with TWO measured gates: NON-UNION tails only (an ungated cut was net +1 — a
  precise union tail like `BindingName` fires where the wide type stayed silent when downstream
  discriminant narrowing can't complete: checker.ts:45139/binder.ts:2498), and a non-nullish
  union tail KEEPS the round-424 nullish-strip (dropping it regressed transformers/utilities.ts:643).
  Verified strictly-removal by listAll diff.
- **Fix 4 (e2e99396, ambiguous block locals in the PROPERTY-ACCESS pass; 75 → 74):** the round-460
  rule (≥2 block-scoped decls of one name in ONE body → anyType) ran only in the assignability
  pass; the property-access pass has its own three body-entry sites. moduleNameResolver.ts's
  loadModuleFromTargetExportOrImport has `const result = nodeModuleNameResolverWorker(...)`
  (ResolvedModuleWithFailedLookupLocations) in one block and the recursive SearchResult `const
  result` in another — first-decl-wins made `result.value` (2823) FP TS2339 on the wrong block's
  type. Root-caused by TWO probes (a getTypeOfIdentifier-origin print falsified the
  inherited-entry hypothesis; the B136-recording print found the same-body sibling block).
- **Fix 5 (f6e2456f follow-up in the same commit as fix 3's gates):** see fix 3's measured gates.
- **Fix 6 (99181256, constraint-shape foreign-TP gate; 74 → 72, −2):** typeContainsForeignTypeParam
  matched own TPs by NAME, so a CALLEE's un-inferred TP sharing the name was claimed own —
  `getUpToDateStatusWorker<T extends BuilderProgram>` returning forEachKey's UNCONSTRAINED
  `T | undefined` never hit the foreign-TP bail → bare-T TS2322 (tsbuildPublic.ts:1778, pinned by
  a temporary stack-trace probe on the Diagnostic constructor after the return-path gate looked
  correct). A same-named TP with a mismatched constraint SHAPE (own constrained vs instance
  unconstrained, or vice versa) is now foreign; scoped to names present in currentTypeParamDecls
  so signature-own TP names keep the pure name test (round-431e pins). ALSO cleared
  transformer.ts:271 (the memoize objlit member — same collision, previously scouted
  "whole-program-only").
- **Probe finding (checkConditionalReturnBranches instrumentation):** utilities.ts:5085's
  emission does NOT come from the ternary-arm branch (its narrow related fine both times) — the
  ternary-arm TS2322 anchor at 5085:49 was the DIRECT `return parent.parent` shape, mis-read from
  the earlier scout. Lesson repeated: verify the emitting SITE with a probe before theorizing.
- **Scouted with findings:** factory/utilities.ts:713 (`helper.importName`) needs TS 5.5 INFERRED
  TYPE PREDICATES — `filter(helpers, helper => !helper.scoped)` selects the guard overload only
  because tsc infers `helper is UnscopedEmitHelper` from the boolean-literal discriminant arrow;
  a bounded slice would infer predicates for single-expression discriminant arrows in
  filter-family calls.
- **NEXT (compiler @ 72, ~26 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):**
  the big-objlit M3 family remnant (moduleNameResolver.ts:1300 / emitter.ts:1277 /
  checker.ts:6640); TransformerFactory ×3 (transformer.ts:83/98/100); inferred type predicates
  (factory/utilities.ts:713); program.ts:1220 (checkDefined RETURN TYPE resolution);
  es2020.ts:91 Exclude-narrowing at whole-program scale; typeSerializer.ts:603 (switch-narrowed
  generic clone chain); `string → __String` branding (builder.ts:2390); utilities.ts:9042
  mapped-type-unknowable-keys excess bail; checker.ts:29132 evolving-let FlowType;
  checker.ts:21170 TS18048 restrictiveInstantiation self-write; esDecorators ×2 /
  taggedTemplate:50 / namedEvaluation:434 / parser.ts:9581 / factory/utilities.ts:1688
  (transformer-family intersections + cast-instantiation identity, M3).**

**Round 461 (2026-07-10) — bounded FP burn-down: FIVE fixes, all GENERAL checker/flow correctness
that reproduce minimally. Dashboard: compiler 88 → 79 (−9; TS2322 25 → 19, TS2345 6 → 4),
services 178 → 165 (−13 cross-profile, measured after all five). Suite 9,762 → 9,782 (+20 local
across 5 new test files, 0 regressions); 5 fix commits (c863d04b / 72c3abc5 / b20a695e /
f8cbd1e6 / 1f3726f2).**
- **Fix 1 (c863d04b, TypeParam-source constraint bail, M3.1; compiler 88 → 86):** the round-456
  reverted broad rule landed as the prescribed per-site EMISSION bail — `bareTpConstraintRelatesTo`
  (constraint-chain walk, cycle-guarded for `T extends T`, unconstrained → false) suppresses the
  assignment-path TS2322 when a bare-TypeParam source's constraint chain relates to the target
  (mirrors round 442's arg-check bail; no shared-engine change → no overload-selection
  perturbation). Cleared transformers/classFields.ts:1800 (`currentClassContainer = node`, `node: T
  extends ClassLikeDeclaration`) + utilities.ts:12338 (`clone = getSynthesizedDeepCloneWorker(...)`
  returning bare `T extends Node` — the callee's T shares the enclosing fn's TP name, so the
  foreign-TP gate deliberately passes it). tsbuildPublic.ts:1778 (return path) does NOT reproduce
  minimally — stays.
- **Fix 2 (72c3abc5, element-access reference paths, M3.4; 86 → 85):** an element access with a
  LITERAL index is a narrowable reference (tsc isMatchingReference) — `getReferencePath` gains an
  ElementAccessExpression arm serializing `recv[N]` segments, so
  `!!node.declarationList.declarations[0].initializer && getInternalEmitFlags(<same path>)`
  narrows (transformers/es2015.ts:2730). Companions: exact-path ElementAccess target arms in
  flowAssignmentMightNarrow + narrowByAssignmentRhs; `flowPathRoot` (splits on '[' too) for the
  root-name comparisons; `pathPrefixOf` boundary tests in argMentionsReferencePath. Also cleaned
  two pre-existing round-460 warnings the recompile surfaced.
- **Fix 3 (b20a695e, namespace-member nested-fn shadow; 85 → 82, −3):** `shadowNestedFunctionNames`'
  collision gate also consults the enclosing inference-namespace exports (new
  `nameInEnclosingNamespaceExports`, symbol-presence only) — parser.ts:1865's
  `syntaxCursor = { currentNode }` shorthand referenced the body-nested
  `currentNode(position): Node` but resolved to namespace Parser's
  `currentNode(parsingContext, pos?): Node | undefined` via lookupInInferenceNamespace (which runs
  BEFORE globals in getTypeOfIdentifier). Generalized beyond the pinned site (−3).
- **Fix 4 (f8cbd1e6, checkDefined-shape non-nullish, M3.4; 82 → 81):** a call whose resolved
  callee returns a bare OWN-TP `T` with some param annotated `T | undefined` (/`| null`) proves
  non-nullish (tsc Debug.checkDefined — inference binds T to the arg's non-nullish part); wired
  into callRhsHasNonNullishReturnAnnotation, gated to no-explicit-type-args (a control pins
  `checkDefined<X | undefined>` staying nullable). Cleared program.ts:4041 (`sourceFile =
  Debug.checkDefined(commandLine.options.configFile)` at an if/else join; the callee resolves
  through the barrel via the flow-only resolvers).
- **Fix 5 (1f3726f2, var-decl + tuple-slot narrowing consumers, M3.4; 81 → 79):**
  (a) checkVarDeclAssignability gets the assignment/return paths' relation-passes-gated narrowing
  for named-object/union/intersection targets (parser.ts:6245 — `let expression: PropertyAccess |
  Identifier | ThisExpression = initialExpression` after a negative isJsxNamespacedName guard);
  (b) `elementAssignableToSlot` (round-446 array→variadic-tuple AST check) narrows a reference
  element — builder.ts:518's `isString(old) ? [old] : old[0]` vs `EmitSignature = string |
  [signature: string]`.
- **Scouted, whole-program-only (deferred with findings, all minimal repros CLEAN):**
  transformer.ts:271's `getEmitHelperFactory: memoize(...)` member (the foreign-TP gate walks
  anonymous-object members and a faithful single-file memoize repro passes — the real gap is
  whole-program); utilities.ts:4066 isPropertyNameLiteral &&-guard (faithful single-file repro
  passes — the PropertyNameLiteral alias union's whole-program resolution is the suspect);
  parser.ts:9581 displays the SAME union on both sides (`& { expression: SerializedEntityName }` vs
  the inline union it aliases — alias-display/conflation flavor); factory/utilities.ts:1688 casts
  to `AssignmentExpression<EqualsToken>` but the cast resolves `PunctuationToken<any>` (generic
  instantiation identity, M3).
- **NEXT (compiler @ 79, ~33 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):** the
  whole-program probe batch (instrument narrowByCallPredicate on the real bench project for
  utilities.ts:4066/5085, es2020.ts:91, factory/utilities.ts:713); tsbuildPublic.ts:1778 return-path
  T (extend the fix-1 bail to the return emission after probing why the narrowed `T | undefined`
  reaches it); the big-objlit M3 family (transformer.ts:271 / moduleNameResolver.ts:1300 /
  emitter.ts:1277 / checker.ts:6640); program.ts:1220 (needs the checkDefined call's RETURN TYPE
  resolved — the non-nullish flag alone doesn't type `file`, so `file.referencedFiles[index]`'s
  members stay unresolvable for the round-460 destructuring narrowing); typeSerializer.ts:603
  (switch-narrowed generic clone chain, M3.1); `string → __String` branding (builder.ts:2390);
  utilities.ts:9042 TS2353 (mapped type keyed `keyof T & …` with un-inferred T — the excess check
  needs an unknowable-key-domain bail).**

**Round 460 (2026-07-09/10) — bounded FP burn-down: TEN fixes (9 dashboard wins + 1
repro-pinned capability), all GENERAL checker/flow correctness. Dashboard: compiler 103 → 88
(−15; TS2322 33 → 25, TS2345 11 → 6, TS2454 1 → 0, TS2363 1 → 0), services 194 → 178 (−16
cross-profile, measured at fix 8). Suite 9,731 → 9,762 (+31 local across 10 new test files,
0 regressions); 9 fix commits (9973bf9d / 4c00e5f1 / fcec70f7 / c35e24cb / 739d16ef /
f2d67ef9 / 2c03d8f9 / a0765dd6 / f1849a01).**
- **Fix 1 (9973bf9d, block-scoping-ambiguous locals; compiler 103 → 100):** the round-459 NEXT
  item with root cause pre-diagnosed. A name with ≥2 block-scoped (`let`/`const`) declarations in
  ONE function body can only refer to DIFFERENT blocks' bindings, but the flat first-decl-wins
  `currentLocalTypes` made every later read resolve to whichever decl the walk saw first —
  program.ts findSourceFileWorker's three `const file` made `return file;` read the if-block's
  `SourceFile | false | undefined` → FP TS2322. `applyAmbiguousBlockScopedLocals` registers such
  names anyType at body entry (`ambiguousBlockLocalNames`; loop-header decls excluded);
  `checkVarDeclAssignability` skips re-recording them in all three maps. BONUS: also cleared
  checker.ts:11321 (round-458's "Blocker-#3 name→DeclarationName conflation" was actually this)
  and utilitiesPublic.ts:991 — the family was mis-attributed to whole-program conflation.
- **Fix 2 (4c00e5f1, exhaustive-switch never family; 100 → 97):** all three `Debug.assertNever` /
  `assertType<never>` sites had DIFFERENT root causes: (a) programDiagnostics.ts:346 — a NON-union
  switch subject (single interface after `isReferencedFile(reason)` guard) whose `.kind` is an
  enum-member-union ALIAS fully covered by cases → `narrowBySwitchClause`'s default branch now
  exhausts it to never; (b) declarations/diagnostics.ts:702 — `Debug.type<T>(node)` where T is a
  FUNCTION-BODY-local alias (B83.5-unbound) → new `uniqueNestedTypeAliasByName` program-wide map
  (type-alias sibling of buildNestedFunctionMap); (c) utilities.ts:12082 — HasInferredType contains
  `Exclude<VariableLikeDeclaration, JsxAttribute | EnumMember>` (no union distribution → member
  resolved anyType, poisoning the union) → new `resolveAssertTargetTypeNode` resolves member-wise
  and evaluates lib Exclude (keep members NOT assignable to U). All flow-only.
- **Fix 3 (fcec70f7, flatten double-array anchor, M3.1; 97 → 95):** `flatten<T>(array: T[][] |
  readonly (T | readonly T[] | undefined)[])` — a union param with a `tp[][]` member is now an
  inference ANCHOR (gate (k)); an array-of-array arg binds T from its inner element (enum elements
  accepted). Cleared utilities.ts:9972/9978; the explicit-type-arg control proved the relation
  passes once T binds.
- **Fix 4 (c35e24cb, TS2454 captured reads; 95 → 94):** `isAssignedAtFlow` returned false at the
  closure's FlowStart, so an expression-bodied arrow's captured read never saw the enclosing
  function's assignments (checker.ts:14106 `filter(…, info => !findIndexInfo(indexInfos, …))` after
  an if/else assigning indexInfos on both branches). It now follows `FlowStart.outerFlow` (closures
  only, localNames-gated; OR-semantics → suppression-only; never-assigned captured reads still fire).
- **Fix 5 (739d16ef, nested destructured global shadow; 94 → 93):** `registerNestedGlobalShadowDecls`
  now registers binding-PATTERN element names — checker.ts:37376's nested `const { start, length } =
  getDiagnosticSpanForCallNode(…)` shadowing core.ts's `length` fn (`diagnostic.length = length`
  FP'd fn-vs-number).
- **Fix 6 (f2d67ef9, arithmetic-pass module-var-leak arm; 93 → 92):** the round-442 family —
  `arithOperandType` bails a bare-Identifier operand whose name ∈ moduleFileLocalVarNames (own-file
  + currentLocalTypes gates): program.ts's module `const indent = "    "` poisoned parser.ts's
  body-local `let indent` → `margin - indent` FP'd TS2363 (parser.ts:8974).
- **Fix 7 (2c03d8f9, destructuring-assignment overwrite narrowing — dashboard-neutral capability,
  repro-pinned):** `({ pos, end } = refs(i))` now OVERWRITES pattern names in the narrowing walk:
  Flow.kt threads the ENCLOSING BinaryExpression through the literal target arms (was the leaf
  Identifier — no RHS visible), `flowAssignmentTargetsName` walks pattern LHSes via
  `destructuringAssignTargetHasName` (nested default-value patterns REQUIRED — the
  sourceMapValidationDestructuringForOf… corpus pin caught the first flat-only cut), and
  `narrowByAssignmentRhs` strips nullish from the declared type when the destructured member
  resolves nullish-free. Companion: a DIRECT enum-typed switch subject with all members covered
  exhausts to never in the default clause. The real program.ts:1220 stays FP — its `file` local is
  `Debug.checkDefined(program.getSourceFileByPath(…))` (cross-barrel generic inference), so the RHS
  type doesn't resolve; mechanism verified by repro + 5 local tests.
- **Fix 8 (2c03d8f9 same commit, function-local-alias predicate target; 92 → 91):**
  `narrowByCallPredicate` falls back to `resolveAssertTargetTypeNode` — utilities.ts
  resolveNameHelper's nested `isSelfReferenceLocation(node): node is SelfReferenceLocation` (the
  alias is declared INSIDE the function) never narrowed → `lastSelfReferenceLocation = location`
  FP'd TS2322 (utilities.ts:11856).
- **Fix 9 (a0765dd6, chained assignment RHS; 91 → 90):** `location = node = value` evaluates to
  the ULTIMATE RHS value, but the round-410/438 assignment-path narrowing gate only accepted a
  bare Identifier/PropertyAccess RHS — transformers/destructuring.ts:113's chain inside
  `if (isDestructuringAssignment(value))` kept the declared `Expression | undefined` → FP vs
  TextRange. The gate now unwraps the `=` right-spine before narrowing.
- **Fix 10 (f1849a01, this-param signature alignment; 90 → 88):** `getTypeOfFunction` zipped the
  this-DROPPED paramSymbols against the this-KEPT decl.parameters — every param type shifted by
  one for a `this`-param function (core.ts's `multiMapAdd<K, V>(this: MultiMap<K, V>, key: K,
  value: V)` built `(key: MultiMap<K, V>, value: K)` → FP TS2322 ×2 at `map.add = multiMapAdd`).
  A this-param function now resolves each symbol's type from its own valueDeclaration, and `this`
  no longer counts toward minArgumentCount. LOAD-BEARING: functions WITHOUT a this param KEEP the
  legacy positional zip — a leading BINDING-PATTERN param is also dropped from paramSymbols
  (round-446 gotcha), and the shift keeps sig.parameters[i] carrying decl.parameters[i]'s type,
  which the call-site arg alignment relies on (an unconditional valueDeclaration-based resolution
  regressed 19 sites: moduleSpecifiers getModuleSpecifierPreferences et al). Aligning properly
  needs placeholder symbols for pattern params — a queued follow-up with real blast radius.
- **Scouted, whole-program-only (deferred with findings):** tsbuildPublic.ts:594 TS7006
  (`parseConfigFileHost.onUnRecoverableConfigFileDiagnostic = d => …` — the PropertyAccess
  assignment-target context EXISTS and a faithful generic destructured-receiver repro compiles
  clean; the real gap is cross-barrel); moduleSpecifiers.ts:929 (round-459 finding stands).
- **NEXT (compiler @ 88, ~42 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):**
  program.ts:1220 needs `Debug.checkDefined(<call>)` cross-barrel generic-return inference to feed
  the (now-landed) destructuring narrowing; transformers/transformers/es2015.ts:2730 needs element-access segments (`declarations[0].initializer`) in the
  narrowing reference-path machinery (getReferencePath has no `[0]` support — core change, assess
  blast radius); utilities.ts:4066 isPropertyNameLiteral &&-guard (probe); TransformerFactory
  whole-program probe (round-457 finding); `string → __String` branding (builderState/builder);
  the binding-pattern placeholder-symbol signature alignment (unblocks the round-446 family properly); the big objlit-vs-interface returns
  (parser.ts:1865, emitter.ts:1277, transformer.ts:271, moduleNameResolver.ts:1300 — likely one
  M3 family); utilities.ts:12338/tsbuildPublic.ts:1778 = the round-456 REVERTED TypeParam-constraint
  relation rule family (needs the per-site emission bail variant).

**Round 459 (2026-07-09) — bounded FP burn-down: EIGHT fixes, all GENERAL checker correctness
that reproduce minimally. Dashboard: compiler 116 → 103 (−13; TS2322 43 → 33, TS2339 5 → 3,
TS2349 2 → 0), services 210 → 194 (−16 measured at fix 7; TS2322 94 → 81, TS2345 29 → 25,
TS2339 13 → 11). Suite 9,706 → 9,731 (+25 local across 8 new test files, 0 regressions); 8 fix
commits (b68d6487 / e6b6d990 / 435d7220 / bef95f36 / 4139b47a / 6c630b54 / d209b138 / 47cb985f).**
- **Fix 1 (b68d6487, array-literal→tuple ASSIGNMENT; compiler −3):** the round-458 NEXT item.
  New `currentLocalDeclTypeNodes` map records body-local annotation NODES alongside
  `currentLocalTypes` (first-decl-wins; saved/restored in checkFunctionBody) so
  `checkAssignmentExpression` can feed the DECLARED tuple node to the round-446
  `arrayLiteralSatisfiesTupleTarget` helper for an ArrayLiteralExpression RHS (body locals are
  B83.5-unbound and the resolved Type collapses the rest slot). Cleared checker.ts:22621/22927
  (`lastSkippedInfo = [source, target]`, `relatedInfo = [info]`), esnextAnd2015.ts:248. The map is
  general infra — any new body-local AST-shape check should read it.
- **Fix 2 (e6b6d990, enum-member-literal overload selection; compiler −1):** an enum-member param
  (`kind: SyntaxKind.NamedImports`) resolves to anyType, so an enum-member ARG matched EVERY
  overload and the FIRST won — `parseNamedImportsOrExports(SyntaxKind.NamedExports)` (parser.ts:8717)
  returned the NamedImports overload's type → FP TS2322. `resolveCallOverload` now compares the param
  annotation's canonical enum-member key set (round-411 key space) against the arg's key AST-side.
- **Fix 3 (435d7220, array-element flow narrowing + Array.isArray; compiler −2):**
  (a) `getTypeOfArrayLiteral` narrows a bare-Identifier ELEMENT via getNarrowedTypeForReference —
  the array sibling of round 438's objlit-value narrowing, same nullish-strip gate — clearing
  builderState.ts:388 (`if (!sourceFile) return emptyArray; … return [sourceFile]`);
  (b) `applyConditionNarrowing` gains `Array.isArray(x)` (covers BOTH the round-458 AST path and the
  flow FlowCondition path; the embedded lib deliberately has no ArrayConstructor so the predicate
  can't resolve via declarations) — clearing utilities.ts:8757 (chainDiagnosticMessages'
  `details === undefined || Array.isArray(details) ? details : [details]`). The isArray.ts corpus pin
  (a USE-BEFORE-ASSIGNED file-level var keeps its DECLARED type in tsc — the TS2454 rule; its
  baseline expects the ELSE branch un-narrowed) is honored by skipping the narrowing for a bare
  identifier resolving to a file-level var with no initializer — the first gate run caught this
  as the session's only (fixed-before-commit) corpus regression.
- **Fix 4 (bef95f36, objLitValueNullishStrip union-member-subset):** the gate on flow-narrowed
  objlit property values / array elements also accepts a nullish-FREE strict MEMBER-SUBSET narrow of
  a union source — monotone-safe (if the raw union relates, any member subset relates → can only
  suppress). Cleared commandLineParser.ts:2269 (readConfigFile's `isString(x) ? … : { config: {},
  error: x }` — `string | Diagnostic` → `Diagnostic` is not a nullish strip). The round-438
  shadowing hazard stays excluded (a nullish narrowed type never qualifies).
- **Fix 5 (4139b47a, numeric-literal vs enum-domain discriminant):** `narrowByDiscriminantProperty`
  compares a NUMERIC-literal RHS against an enum-typed discriminant's VALUE domain (enumValues via
  canonicalEnumSymbol): `flowType.flags === 0` drops `Type` (TypeFlags has no 0-valued member),
  keeping IncompleteType — tsc's getTypeFromFlowType/isIncomplete (checker.ts:28630 TS2339 +
  the 29132 TS2322 cascade). An enum WITH a matching value does not narrow (negative control).
- **Fix 6 (d209b138, tuple-union element access; TS2339):** `value[1]` on a receiver NARROWED to
  `[FileId] | [FileId, BuilderFileEmit]` (builder.ts:2242 toBuilderFileEmit) — tsc resolves union
  element access per-member with undefined for out-of-bounds members. The element-access
  missing-member path bails for a numeric key in bounds for SOME member of an all-tuples union;
  the bail tests the NARROWED receiver (the DECLARED type still carries the guard-removed FileId).
- **Fix 7 (6c630b54, nullable-array default-init push on PROPERTY targets; TS2349 2 → 0):**
  `(label.antecedent || (label.antecedent = [])).push(antecedent)` (binder.ts:1375; core.ts:2135
  cleared too). Root cause found by tracing: inside the `||` right operand the read of `x.p` is
  flow-narrowed to the falsy `undefined`, so the round-408 empty-`[]` contextual rule never saw the
  array type. Two coupled rules: combineBinaryTypes' Equals arm recomputes the RAW property type for
  a PropertyAccess target with a fresh empty-array RHS (an assignment TARGET types against its
  DECLARED type — M1.9); contextualAssignmentRhsType accepts a UNION target whose sole non-nullish
  member is Array-family. The local wrong-arg control pins that the receiver collapses to the
  PRECISE `string[]` (an `any[]` would accept the bad arg). This closes the round-452
  "inline-property-receiver" deferral.
- **Fix 8 (47cb985f, truthy ASSIGNMENT condition; compiler 104 → 103):** `while (child =
  tryParse(() => …))` — the assignment evaluates to the assigned value, so the body sees `child`
  truthy; tsc's parser JSDoc loops declare `let child: Tag | … | false` and the `false` member
  otherwise survived every discriminant filter (a primitive literal member has no `.kind` — the
  enum-key filter conservatively keeps it) → FP TS2322 at parser.ts:9638. `applyConditionNarrowing`
  gains a `SyntaxKind.Equals` arm (LHS is the walked reference → narrowByTruthiness; the false
  branch falsy-narrows symmetrically), covering both the flow FlowCondition and the round-458 AST
  paths.
- **NEXT (compiler @ 103, ~57 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console env-legit):**
  the TransformerFactory<T> whole-program conflation probe (transformer.ts:83/98/100 — round-457
  finding: NOT a generic-alias gap, needs an instrumented probe); program.ts:3705 — ROOT CAUSE FOUND
  (no probe needed): findSourceFileWorker has TWO block-scoped `const file` decls
  (`filesByName.get(path)` → `SourceFile | false | undefined`, declared FIRST inside an if-block,
  vs the outer `host.getSourceFile(…)`) colliding in the block-UNAWARE first-decl-wins
  `currentLocalTypes`, so `return file;` reads the wrong binding's type — the B83.5/block-scoping
  family, needs block-aware local types or a scoped bail; program.ts:1220 (switch-exhaustive +
  destructuring-assignment definite assignment); the `assertNever` never-param family ×3 (round 441
  deep); utilities.ts:9978/9972 flatten (M3.1 generic inference); `string → __String` branding
  (whole-program); moduleSpecifiers.ts:929 for-of element mis-inference (whole-program-only —
  minimal repro clean).

**Round 458 (2026-07-09) — logical/ternary operand narrowing + try/finally flow fix +
fresh-objlit interface-target retry + a P0 single-file-CLI crash fix. FOUR fixes, all GENERAL
checker/flow/driver correctness that reproduce minimally. Dashboard: compiler 121 → 116 (−5),
services 217 → 210 (−7), server 407 → 402 (−5), harness 620 → 615 (−5), tsc-cli 118, jsTyping 115,
deprecatedCompat 118, typingsInstallerCore 115. Suite 9,690 → 9,706 (+16 local across 4 test files,
0 regressions); 4 fix commits (a8571636 / 4eb423e7 / b2143c9e / eec58c6a).**
- **Fix 4 (eec58c6a, P0 — `xtsc foo.ts` crashed on ANY bare source-file argument, even
  `const x = 1;`):** discovered mid-session via a probe; per the P0 mandate, fixed before wrapping.
  The bare `.ts` was passed straight into TsConfigLoader (`resolveConfigPath` treats any
  non-directory as a tsconfig), parsed as JSON → garbage config → a corrupt lib binderResult whose
  `sourceFile.text` mismatched its statement positions → StringIndexOutOfBounds in
  `checkMultiBaseInStatement` (`source.substring` at a lib position ~22995 indexed into the 295-char
  user file). Two narrower guards were tried first (a statement-span bounds check; a merged-decl
  in-source filter) — NEITHER stopped the crash (the position mismatch is in the binderResult
  itself, not the cross-file merge), pointing to the ROOT fix: `ProjectCompiler.build` now detects a
  non-`.json` existing-FILE argument and builds it as a single-file program with default options
  (like `tsc foo.ts`): the file is the sole root in a synthesized `LoadedTsConfig(files = [it])`,
  its relative imports are still walked into the program, and type checking works normally.
  Directory/tsconfig arguments untouched (compiler profile confirmed unchanged at 116). +3 local
  (SingleFileBuildTest: clean compile, real-error reporting, relative-import walk).
- **Fix 1 (a8571636, logical/ternary operand narrowing):** `combineBinaryTypes` narrows the
  `||`/`&&` RIGHT operand, and the `ConditionalExpression` typing narrows the TRUE/FALSE branches,
  by the governing condition via a new `narrowOperandByCondition` → `applyConditionNarrowing` (pure
  AST, no flow graph). tsc's binder places a FlowCondition on the operand (`A && B` under "A true";
  `A || B` and a ternary FALSE branch under "A false"; a ternary TRUE branch under "A true"); our
  `getTypeOfExpression` was flow-unaware for bare references, so `insertComment === undefined ||
  insertComment` typed `boolean | undefined` (→ FP TS2322 against `boolean`; services.ts commenting
  logic) and `cond ? ref : …` kept the un-narrowed branch. Fires only for a pure
  Identifier/PropertyAccess operand (`getReferencePath`); FP-safe (type unchanged when the condition
  doesn't mention the operand path). Cleared services.ts:2891/2976, rename.ts:192, checker.ts:51198
  (accessor-`kind` discriminant → the object-literal `{firstAccessor, …, setAccessor, getAccessor}`
  now matches `AllAccessorDeclarations`). Compiler net 0: the 51198 win is offset by exposing
  checker.ts:11321, a Blocker-#3 `name`→DeclarationName whole-program conflation the cleaner
  (undefined-stripped) narrowing surfaces (the relation now checks the wrongly-typed reference the
  coarse union masked). CAVEAT: RETURN-position ternaries/logicals go through the SEPARATE
  `checkConditionalReturnBranches` (per-branch), untouched by this.
- **Fix 2 (4eb423e7, try/finally flow):** `bindTryStatement` made the finally block's entry flow the
  join of ONLY the try/catch NORMAL completion, so a try that always `return`s/throws left that
  completion unreachable → every read in the finally washed to `never` → spurious TS2339 on cleanup
  code. tsc's `checkGrammarRegularExpressionLiteral` resets `scanner` in its finally after a `try {…
  return; }`; our `scanner` washed to `never` → FP TS2339 on `scanner.setText`/`setOnError`
  (checker.ts:33288/33289). Fix: a two-label structure — the finally block's entry joins the pre-try
  flow (exceptional early exit) + the normal completion, while the flow AFTER the whole statement
  stays the normal completion (so the finally's exceptional-inclusive entry can't widen away the
  try/catch narrowing for the following statements). compiler 121 → 119 (−2), no services regression.
  CLAUDE.md gotcha added (do NOT collapse the two-label structure).
- **Fix 3 (b2143c9e, fresh-objlit interface-target retry):** the round-448 return-path
  fresh-object-literal literal retry (recover a returned object literal's un-widened literal property
  per PropertyAssignment via `freshObjLitRange` so `propertiesRelatedTo` relates it) was gated to
  `Type.Union` targets only. A fresh object literal returned against an INTERFACE (or anonymous
  object) with a literal(-union) member hit the same widening — `return { kind: "ambient", … }` vs
  `interface ModuleSpecifierResult { kind: "node_modules" | … | "ambient"; … }` widened `kind` to
  `string` → FP TS2322. Broadened the gate to `Type.Interface`/`Type.Object`. Suppression-only (the
  retry returns only when the relation then PASSES within the fresh scope → a wrong literal / missing
  property still fires; negative controls pinned). Cleared moduleSpecifiers.ts:399/507,
  sourcemap.ts:323 (RawSourceMap). compiler 119 → 116, services 214 → 210. NOTE the var-decl and
  assignment fresh-objlit paths already apply `withFreshObjLitSource` for all targets — only the
  return path carried the union-only gate.
- **INVESTIGATED, deferred (array-literal→tuple ASSIGNMENT, checker.ts:22621/22927,
  esnextAnd2015.ts:248):** `lastSkippedInfo = [source, target]` → `[Type, Type]`; `relatedInfo =
  [info]` → `[X, ...X[]]`. The `arrayLiteralSatisfiesTupleTarget` helper (round 446, return path)
  handles these shapes, but the targets are function-body-local `let`s (B83.5-unbound), so the
  assignment walk has NO declaration-node map to feed the helper the tuple type NODE (it knows the
  local only by resolved type, which collapses the tuple rest). Needs local-declaration-node plumbing
  into the assignment walk (a `currentLocalDeclTypeNodes` map populated alongside `currentLocalTypes`)
  — a broader infra change that also unblocks other AST-based checks for function-body locals.
- **NEXT (compiler @ 116):** the array-literal→tuple assignment plumbing above; the
  `TransformerFactory<T>` generic-type-alias-of-fn relation (M3.3, whole-program — minimal repro
  clean); the `assertNever(reason)` exhaustive-switch enum-union residual (programDiagnostics.ts,
  round 441 — the guarded `ReferencedFile` union must resolve with readable enum `.kind` members);
  `string → __String` branding (whole-program).

**Round 457 (2026-07-09) — parser `as`/`satisfies` precedence bug. ONE fix, GENERAL parser
correctness that reproduces minimally. Dashboard: compiler 124 → 121 (−3, TS2322 49 → 46); services
220 → 217 (−3); the fix generalizes to any `<binary> as T` right-operand cast. Suite 9,681 → 9,686
(+5 local, 0 regressions); 1 fix commit (22b37a95).**
- **Root cause:** `parseBinaryExpression` (Parser.kt:5399) called a greedy `parseExpressionSuffix(left)`
  right after the unary operand, gluing `as`/`satisfies` to the bare RIGHT operand of a binary op
  BEFORE the precedence-respecting binary loop. `as`/`satisfies` have precedence 7 — LOWER than
  additive (`+`/`-` = 9) and multiplicative (`*`/`/`/`%` = 10) — so a trailing cast must bind the WHOLE
  binary result. `a + b as T` was mis-parsed as `a + (b as T)` instead of tsc's `(a + b) as T`.
- **Fix:** removed the greedy `parseExpressionSuffix(left)` call; the binary loop (which attaches
  `as`/`satisfies` only when `7 > minPrec`) is now the sole handler; `parseExpressionSuffix` is dead
  and deleted. The parallel `parseBinaryExpressionRest` loop (7833) already handled the LEFT-operand
  `as` correctly with the same `prec <= minPrec` break — this only fixed the right-operand path.
- **Trip site:** binder.ts `getDeclarationName` returns `tokenToString(op) + operand.text as __String`
  and `"arg" + index as __String` — the whole `+` is the cast source (→ `__String`, assignable to the
  declared `__String | undefined`); the wrong parse yielded `string + __String` = `string` → FP
  `string ⊄ __String | undefined` (binder.ts ×2 + utilities.ts ×1). Cleared by the fix.
- **Local pin:** `AsExpressionPrecedenceTest` (5 tests) — the exact binder.ts shape, a
  multiplicative variant, a `satisfies` smoke, and a negative control (`a + (b as T)` = `string`,
  still fires TS2322 — the fix does not blanket-suppress right-operand casts).
- **Second item (b8fd350d, `asserts node is Exclude<T, U>` narrowing — DASHBOARD-NEUTRAL capability
  extension per the round-411 precedent):** `narrowByAssertCall` gained an `Exclude<T, U>` branch
  (tsc's `Debug.assertNotNode`) beside the `NonNullable<T>` special-case. After the call the walked
  union drops members assignable to U (bound from the sibling `test` arg's predicate target via
  `predicateTargetTypeOfGuardExpr`); suppression-only / FP-safe. Verified end-to-end by a FAITHFUL
  barrel + guard + loop + intersection repro (all pass), so the narrowing is correct — but the one
  compiler site (es2020.ts:91 `chain.questionDotToken` after `Debug.assertNotNode(chain,
  isNonNullChain)`) stays FP: instrumented isolation shows the mechanism works, so the real site is
  blocked by a WHOLE-PROGRAM resolution/relation scale issue (the huge `_namespaces/ts.js` barrel
  Debug resolution, or `OptionalChain`/`NonNullChain` conflation), NOT the narrowing. `AssertNotNode
  ExcludeNarrowingTest` (4 tests) pins direct/loop/intersection exclusion + a precise-exclusion
  negative control. Follow-up: instrument `resolveNamespaceMemberFnDecl`/`checkTypeRelatedTo` on the
  real es2020 profile to unblock the dashboard site.
- **NEXT (compiler @ 121):** the remaining compiler FPs are ALL deep M3 / whole-program — the
  bounded surgical pool for the compiler profile is dry (every family scouted this round). Confirmed
  findings for the next agent:
  - `TransformerFactory<T>` (×3 compiler + ×3 services): NOT a generic-alias-instantiation gap — the
    substitution path (Checker.kt:91386 `getTypeFromTypeNode(decl.type)` WITH `currentTypeAliasArgs`)
    DOES process a FunctionType alias body, and a minimal repro (`type Transformer<T> = (n:T)=>T;
    type TransformerFactory<T> = (c:number)=>Transformer<T>; return src`) compiles CLEAN. The real
    FP is a WHOLE-PROGRAM conflation (Blocker #3 family — `TransformerFactory`/`Transformer`/
    `SourceFile`/`Bundle` resolve differently in the full program); needs an instrumented probe on
    the real profile, NOT the M3.3 generic-alias angle.
  - `checker.ts:28630` `flowType.flags === 0 ? flowType.type` on `Type | IncompleteType`: numeric-
    literal-`0` discriminant narrowing where the member discriminants are an enum (`Type.flags:
    TypeFlags`) vs a literal `0` (`IncompleteType.flags`) — deep discriminant narrowing.
  - `es2020.ts:91` `chain.questionDotToken`: needs `Debug.assertNotNode(x, guard)` NEGATIVE-exclude
    assert narrowing (`asserts x is Exclude<T, U>`) — new narrowing machinery for a tsc idiom.
  - `services.ts:2891/2976` `boolean | false` target: `||`-RHS flow narrowing gap (`a === undefined
    || a` should narrow `a` to non-undefined) + union normalization (`boolean | false` → `boolean`).
  - `(x || (x = [])).push()` TS2349 (core.ts/binder.ts): round 452 reverted — receiver typed in the
    call-type pass via a path `contextualAssignmentRhsType` doesn't reach; property/`??=`-IIFE targets.
  - services missing-property TS2740/TS2741 (`Node` → `Expression`/`PropertyAccessExpression`,
    `_expressionBrand`): brand-narrowing against conflated Node/Expression types (whole-program).

**Round 456 (2026-07-09) — Array/ReadonlyArray `find` type-guard overload + NonNull-identifier
undefined-strip + branded-intersection flow-narrowing + IterableIterator heritage + a build-warning
cleanup; ONE broad relation rule investigated & reverted. Dashboard: compiler 132 → 124 (−8, TS2322
56 → 49, TS2353 2 → 1); services 230 → 220 (−10, all generalize cleanly, no new FP). Suite 9,666 →
9,681 (+15 local, 0 regressions); 5 commits (860046fc find / 042e2551 nonnull / 3ad1ae4d
branded-intersection / 6330194e iterable-iterator / 54e644db warning).**
- **Fix 1d (6330194e, IterableIterator heritage; compiler −1, services −1):** the embedded
  `IterableIterator<T>` was an EMPTY interface `{ }`, so an interface `extends IterableIterator<T>`
  inherited none of `next()`/`return?()`/`throw?()` (they live on `Iterator<T>`) — an object literal
  supplying `next()` against such a target FP-fired TS2353 "'next' does not exist in type
  'MappingsDecoder'" (sourcemap.ts `MappingsDecoder extends IterableIterator<Mapping>` decoder literal).
  `IterableIterator<T>` now `extends Iterator<T>` (as in the real lib.es2015.iterable);
  ArrayIterator/MapIterator/SetIterator left empty (not heritage targets in tsc's sources → minimal
  blast radius). Corpus green (lib change — the critical gate).
- **Fix 1c (3ad1ae4d, branded-intersection flow-narrowing; compiler −2, services −2):** the
  assignment-RHS (round 410/438) and return-path (round 413/438) flow-narrowing gates covered
  Interface/Reference/Object/Union targets but excluded `Type.Intersection`, so a reference narrowed
  non-nullish (`if (!x) return` / `if (x === undefined) { x = … }`) assigned/returned against a BRANDED
  intersection (`Path = string & {__pathBrand}`, `IncrementalBuildInfoFileId = number & {__…Brand}`)
  kept its wider `X | undefined` type → FP `X | undefined ⊄ X`. Added `Type.Intersection` to both gates;
  FP-safe by construction (unchanged: the narrowed type is substituted only when it makes the relation
  pass). Cleared resolutionCache.ts:1518 (`fileOrDirectoryPath = updatedPath`) + builder.ts:1394
  (`return fileId`). Corpus green.
- **Fix 1b (042e2551, NonNull-identifier undefined-strip; compiler −4, services −6):**
  `getTypeOfExpression(NonNullExpression)` kept the union for a bare-identifier `x!` — only
  `undefined!`/`null!` → never and a `<call>()!` all-concrete union stripped (round 439). So
  `writer = _writer!` (emitter.ts, `_writer: EmitTextWriter | undefined`) and `currentFlow =
  preSwitchCaseFlow!` (binder.ts, `FlowNode | undefined`) FP-fired `X | undefined ⊄ X`. Extended the
  round-439 strip to `expr.expression is Identifier` with the SAME all-concrete gate
  (`types.none { typeContainsUnresolvedTypeParam }`). Property-access `.x!` (the object-literal-vs-
  interface member-precision gap the round-407 note documents) and TP-carrying operands stay deferred
  — a bare Identifier is a simple reference and cannot expose the `.x!` object-literal gap. Cleared
  binder.ts:1748/1768, checker.ts:5791 (`(Symbol | undefined)[]` → `Symbol[] | undefined`),
  emitter.ts:1417; transformer.ts:271's pre-existing object-literal-vs-TransformationContext FP only
  shifts a member display (not a new FP). Corpus green (core-path change — the critical gate).
- **Fix 1 (860046fc, `find`/`findLast` type-predicate overload; TS2322 −1 compiler, −1 services):**
  the embedded `Array<T>`/`ReadonlyArray<T>` `find`/`findLast` had only the general
  `(predicate: … => unknown): T | undefined` signature, so `arr.find(isFoo)` returned the base element
  type. Added the type-predicate overload `find<S extends T>(predicate: (value: T, …) => value is S,
  thisArg?): S | undefined` (mirrors round 455's filter/every), so `tryInferPredicateOverloadReturn`
  (round 439) refines the result — cleared utilities.ts:8276 `getClassLikeDeclarationOfSymbol`
  (`symbol.declarations?.find(isClassLike)`). **Adding an OVERLOAD to an EXISTING method (not a new
  member name) does NOT shift "and N more" missing-property counts** — corpus green. Generalizes across
  profiles (`.find(isX)`/`.findLast(isX)` are pervasive in tsc's own source).
- **Fix 2 (54e644db, build-warning cleanup):** removed a redundant `as TypeOfExpression` cast
  (round 451 typeof-switch exhaustiveness, Checker.kt:79547) Kotlin flagged "No cast needed"
  (`stmt.expression` is already smart-cast). The build must stay warning-clean.
- **INVESTIGATED & REVERTED — the broad relation-engine `source is Type.TypeParam` → relate-its-
  constraint-chain rule (cycle-guarded for `T extends T`).** MEASURED net-zero A/B on BOTH compiler and
  services: it fixed exactly one assignment FP (utilities.ts:12338 `getSynthesizedDeepCloneWithReplacements
  <T extends Node>` — `T` assigned to a `Node | undefined` local) but INTRODUCED one via OVERLOAD
  SELECTION — a bare un-inferred `T extends string` (from `getPathFromPathComponents<T extends string>`,
  whose `readonly T[]`-from-`PathPathComponents` inference our engine leaves un-substituted) now matched
  `ensureTrailingDirectorySeparator`'s `(path: string)` overload instead of `(path: Path)`, returning
  `string` → FP `string ⊄ string & {__pathBrand}` at moduleNameResolver.ts:2933. The rule is SOUND
  (T <: constraint <: target) but overload arg-matching shares `checkTypeRelatedTo`, so it perturbs
  signature selection wherever inference under-resolves a TypeParam arg — exactly CLAUDE.md's standing
  warning against the broad rule. If retried: apply as a PER-SITE bail at the assignment/return TS2322
  EMISSION only (not the shared engine), or fix the upstream `getPathFromPathComponents` inference first.
- **NEXT (compiler @ 124, ~81 real excl. TS2591×43 offline-node):** TransformerFactory<T>
  generic-type-alias-of-fn relation (M3.3 — the alias `(context) => Transformer<T>` doesn't
  instantiate its fn-type body; whole-program, minimal repro clean); the exhaustive-switch
  `assertNever(reason)` enum-union residual (programDiagnostics.ts's `ReferencedFile` → `never` in the
  default, round 441 deep); the `OptionalChain.questionDotToken` Exclude<>-narrowing (es2020.ts — needs
  `Exclude<>` materialization + `assertNotNode` narrowing); `string → __String` branding (whole-program).

**Round 455 (2026-07-09) — global-shadow anyType + widenType tuple shape + ReadonlyArray filter
guard + generic-identity-fn assignability: FOUR fixes, all GENERAL correctness that reproduce
minimally. Dashboard: compiler 153 → 132 (−21, TS2322 77 → 56); shared checker/lib so they generalize
to every profile. Suite 9,654 → 9,666 (+12 local across 4 new test files, 0 regressions); 4 fix
commits (4e94a4e1 / b90d79f1 / 7d82a0aa / aada9e31).**
- **Fix 1 (4e94a4e1, body-local shadows a global function; TS2322 −7):** `applyBodyLocalShadowing`
  (the return/argument-assignability pass) recorded an un-annotated top-level local shadowing an outer
  binding by REMOVING it from `currentLocalTypes` (round 351), so a value-position use (`return clone`)
  fell through `getTypeOfIdentifier` to the outer binding. When that outer binding is a GLOBAL (an
  exported fn merged into globals — core.ts's `clone<T>(object: T): T`, `identity<T>`) the reference
  resolved to the generic function ITSELF → FP `'<T>(object: T) => T' is not assignable to type
  'Identifier' / 'Node | undefined' / …`. Two coupled changes (both suppression-only): (a) an
  un-annotated `inGlobals && !inLocal` shadow now registers `currentLocalTypes[nm] = anyType` (a
  concrete inferred local still wins, checked first; a file-level VAR shadow keeps round 351's
  remove-and-re-infer); (b) `applyBodyLocalShadowing` descends into nested blocks (if/loop/try/switch,
  `applyNestedGlobalShadow`, anyType-only, GLOBAL collisions only — never a concrete annotation type,
  which would leak the block-local shape function-wide) so `const clone=…;return clone` inside an `if`
  resolves. Cleared nodeFactory.ts cloneIdentifier/clonePrivateIdentifier, checker.ts:15679,
  expressionToTypeNode.ts:535 (round-454's documented unmasked gap), plus the builder.ts `create`/`map`
  and checker.ts `length` object-literal-factory shadows (bonus).
- **Fix 2 (b90d79f1, widenType preserves tuple shape; TS2322 −8):** `widenType`'s Type.Object branch
  rebuilt a tuple WITHOUT copying `tupleElementTypes` and widened its `length` LITERAL (`2 → number`),
  turning `[SF, FR]` into a `{ 0: SF; 1: FR; length: number; [x: number]: SF | FR }` object that no
  longer related to a tuple target (`length: number ⊄ 2`). tsc's own `map(arr, x => [a, b])` idiom whose
  result flows through a generic wrapper (`concatenate(...)`) and is assigned to a `[A, B][]` variable
  FP-fired TS2322 (declarations.ts/builder.ts/destructuring.ts ×8). widenType now widens a tuple's
  ELEMENT types only + rebuilds via `buildTupleFromTypes` (length literal + tupleElementTypes +
  per-element optionality preserved); no element widens → tuple returned untouched. Strictly more
  precise. NOTE the var-decl path already passed (contextual tuple typing); only the ASSIGNMENT path
  (which widens the RHS) hit the lossy rebuild — that's why it reproduced only as an assignment.
- **Fix 3 (7d82a0aa, ReadonlyArray.filter/every type-predicate overload; TS2322 −3):** the embedded
  `ReadonlyArray<T>.filter`/`every` lacked the `filter<S extends T>(…): S[]` overload that `Array<T>`
  already carried, so `roArr.filter(isFoo)` returned the base `T[]` — utilitiesPublic.ts
  `getJSDocTagsWorker(...).filter(isJSDocParameterTag)` (a `readonly JSDocTag[]`) FP-fired
  `'JSDocTag[]' ⊄ 'readonly JSDocParameterTag[]'` (×3). Added the overload (and `every`), so
  `tryInferPredicateOverloadReturn` (round 439) infers S from the guard's predicate target. Lib change →
  full corpus blast-radius gate clean.
- **Fix 4 (generic-identity-fn → concrete-fn assignability; TS2322 −3):** `signatureRelatedTo` already
  pinned source type params from the target's param positions for the source-generic/target-non-generic
  case (17.10d — `identity<T>(x: T): T` vs `(fileName: string) => string` pins T := string), but did NOT
  substitute those pins into the source RETURN type, so the return `T` FP-rejected against the concrete
  target return `string`. tsc's core.ts `createGetCanonicalFileName` (`return useCS ? identity :
  toFileNameLowerCase` → `GetCanonicalFileName`) and sourcemap.ts `identitySourceMapConsumer` FP-fired
  TS2322, plus the construct-sig variant parser.ts:1737 (`new <TKind extends SyntaxKind>(…): Token<TKind>`
  → `new (…): Node`). The return type is now instantiated with the pins (`tpAssignments` by id + a
  `tpAssignByName` fallback — TypeParam interning is per-AST-position (B199), so the return's `T` may
  carry a different id than the param's) before the covariant compare. Correctness preserved: `identity`
  vs `(x: string) => number` still fails (pinned return `string ⊄ number`). Relation-engine change →
  full corpus gate clean.
- **INVESTIGATED, deferred (whole-program-only or deep M3, confirmed via minimal repro):** the
  `isPropertyNameLiteral(name) && …` TS2345 Expression→Identifier narrowing is 0-error in isolation (a
  whole-program alias/merge issue); the generic-identity-fn `<T>(x: T) => T` → concrete-fn assignability
  (core.ts:2378 `identity` → `GetCanonicalFileName`, sourcemap.ts:820) is a genuine M3.1 generic-signature
  relation; the `Type | IncompleteType` `.type` access is a specialized `flags === 0` enum-domain
  narrowing; the `assertNever(reason)` exhaustive-switch residual (programDiagnostics.ts) needs the
  enum-union `ReferencedFile` to narrow to `never` in the default (round 441 family).
- **NEXT (compiler @ 132):** the `TransformerFactory<T>` generic-type-alias-of-fn relation ×3 (M3.3 —
  `(context) => (x) => x` vs the alias `(context) => Transformer<T>`; likely a generic type-alias
  instantiation gap); the exhaustive-switch enum-union `assertNever`/`assertType<never>` residuals
  (programDiagnostics.ts's `ReferencedFile` union → `never` in the default, round 441 family);
  `string → __String` branding (whole-program); the `.map` DIRECT array-literal-in-assignment
  contextual tuple typing (assignment path lacks the var-decl array-literal-vs-tuple handling).

**Round 451 (2026-07-09) — bounded FP burn-down: FOUR fixes, one a GENERAL parser correctness
fix that reproduces minimally. Dashboard: compiler 178 → 177 (−1), tsc-cli 179, jsTyping 176,
deprecatedCompat 179, typingsInstallerCore 176, services 291 → 282 (−9), server 498 → 488 (−10),
harness 715 → 704 (−11). Suite 9,619 corpus green / 0 fail / 3 skip + 14 local across 4 new test
files; 4 fix commits (dfb5b2c2 / cebd023d / a00c798b / fd7700ee).**
- **Fix 1 (dfb5b2c2, PARSER — type-predicate keyword-subject; the general one):** a user type-guard
  whose PARAMETER is named with a type keyword — the pervasive `function isTransientSymbol(symbol:
  Symbol): symbol is TransientSymbol` in tsc's own sources — never narrowed, so
  `if (isTransientSymbol(sym)) sym.links` FP'd TS2339. The predicate SUBJECT is grammatically a
  parameter NAME, but the parser reaches it via `parseType`, which turns a keyword-like name
  (`symbol`/`string`/`object`/…) into a `KeywordTypeNode` instead of an Identifier, so the checker's
  `predicateParamName` extraction (which handles Identifier/TypeReference/ThisType) returned null and
  the guard was silently ignored. Fix in `parsePrimaryTypeOrHigher`'s predicate branch: when the
  parsed subject is a KeywordTypeNode and `is` follows, rebuild it as an `Identifier` from the keyword
  text (`keywordTypeKindText`). The asserts path already handled this via `isIdentifier()`. Cleared the
  isTransientSymbol(symbol) family (symbolDisplay ×4 / signatureHelp ×1). **KEY: this REPRODUCES
  minimally** (a single-file `declare function isD(symbol: Base): symbol is Derived; if (isD(x))
  x.links` FP'd) — the round-450 note wrongly grouped it with the deferred whole-program family. The
  companion `isUnion()`/`isStringLiteral()` cross-file `declare module` augmentation method-guard
  (Type.types/Type.value) was re-confirmed genuinely whole-program-only (a minimal 2-file `declare
  module` augmentation repro is CLEAN) — Blocker #3, still deferred.
- **Fix 2 (cebd023d, LIB Object.entries):** `entries(o: any): any[]` → `entries<T>(o: any): any[]` so
  tsc's `Object.entries<string>(result.config)` (jsTyping) type-checks (was TS2558 "Expected 0 type
  arguments, but got 1"). Return kept `any[]` for zero downstream-inference change.
- **Fix 3 (a00c798b, CFA typeof-switch exhaustiveness):** `switch (typeof value)` covering the
  subject union's ACTUAL possible tags (`value: string | number | PseudoBigInt` → string/number/object)
  is exhaustive (tsc narrows the subject to `never` after them), so a value-returning function needs
  no trailing return — tsc's own utilities.ts `hasValue` FP'd TS2366. The CFA check previously only
  recognized a switch covering ALL 8 typeof strings; it now also accepts the subject's own tag set
  (new `typeofTagsOfType` / `typeofSwitchSubjectType`, subject resolved from the param annotation via
  `currentFunctionParams` — `getTypeOfExpression` returns `any` for a param in the CFA pass). FP-safe:
  bails to null on any uncertain constituent (any/unknown/type-param/enum). Only the TS2366/TS7030
  path — the separate TS7027 `isDefinitelyTerminating` stays strict.
- **Fix 4 (fd7700ee, LIB Set set-ops):** the ES2024/ES2025 Set methods (union/intersection/…,
  lib.es2025.collection.d.ts in the pinned tsc) are now LIB_MIN_TARGET-gated at ESNext (our top
  ScriptTarget — no ES2025): absent at the self-compile's es2020 lib (where tsc's core.ts
  `const set: Set<T> = { has, add, … }` shim satisfies Set, was TS2740 "missing union, …"), present at
  the setMethods test's @target esnext. No corpus `Set<>` missing-property display and tsc calls none
  of the methods → nothing shifts. Cleared core.ts across ALL profiles (compiler-file family).
- **NEXT (services @ 282, all deferred/whole-program):** the `isUnion()`/`isStringLiteral()` cross-file
  augmentation method-guard family (Type.types ×3 / Type.value, Blocker #3); the `isTransientSymbol(
  symbol.links.target)` CHAIN residuals (property-access-path narrowing); deep-M3 TS2322×154 fragments
  (max bucket 3); the `[] → all-optional-tuple` TS2739 (moduleSpecifiers.ts — tuple-element optionality,
  needs the annotation AST node); the `.map`-returns-tuple-array contextual-return M3.2 slice.

**Round 450 (2026-07-09) — bounded FP burn-down: FIVE suppression-only / soundness fixes,
all cleanly reproducible in isolated repros + locally tested (5 new test files, 20 tests).
Dashboard: compiler 183 → 178 (−5), services 310 → 291 (−19), server 518 → 498 (−20),
harness 735 → 715 (−20). Suite 9,605 corpus green / 0 fail / 3 skip; 6 fix commits
(21f41105 / 2da83a26 / 4d443ae9 / 2c202d67 / 134f9d7a / 4ad6f1b7).**
- **Fix 1 (21f41105, boolean-literal assign; TS2322 −3 services):** a `true`/`false` literal
  assigned to a literal-boolean local (`let isSnippet: true | undefined; isSnippet = true`,
  completions.ts). A boolean literal parses as an `Identifier` (literalTypeOfExpression), so it
  was NOT covered by the `tryCatchFinallyControlFlow` guard that skips the legacy varTypes
  string-fallback for numeric/string/bigint literal RHS — the fallback widens `true`→"boolean"
  and `isAssignableTo("boolean", "true | undefined")` fails even though the engine already
  validated it (keeping the literal via propTypeContainsLiteral). Extend the guard to
  `true`/`false`. FP-safe (canUse && isAssignable gate); negative controls fire via the engine.
- **Fix 2 (2da83a26, leaked-module-var assignment TARGET; TS2740 −3 + TS2322 −3 services,
  compiler −2; Blocker #3):** `parent = parent.parent` inside a NESTED block where
  `let parent = node.parent` shadows navigationBar.ts's module-level `let parent: NavigationBarNode`
  (leaked into globals per round 442). applyBodyLocalShadowing only scans function-body TOP-LEVEL
  statements, so a nested `let parent` isn't in currentShadowedNames — the assignment target then
  resolved to the leaked `NavigationBarNode` annotation and FP'd TS2740 ('Node' missing its props).
  Skip the globals lookup in the assignment-target resolution for a moduleFileLocalVarNames name
  not owned by this file (currentFileLocals). FP-safe (a cross-file leaked var is TS2304 in tsc);
  the own-file binding is still checked.
- **Fix 3 (4d443ae9, `x = x || DEFAULT` narrowing; TS2345 −4 services):** the default-init idiom
  `maximumLength = maximumLength || defaultMaximumTruncationLength` (utilities.ts) didn't narrow a
  `T | undefined` reference to non-nullish `T`. `rhsIsDefinitelyNonNullish` (consulted by
  narrowByAssignmentRhs → narrowByExcludingNullUndefined(declaredType), round 416) didn't classify
  a `||`/`??` RHS. `a || b`/`a ?? b` are non-nullish iff the RIGHT operand is; the right operand is
  usually a const/local reference so its type is resolved and nullish-checked. FP-safe: a nullish
  right operand keeps the reference nullable (pinned by a control).
- **Fix 4 (2c202d67, `while (true)` definite-assignment; TS2454 −2 compiler / −2 services):** a
  `while (true)` loop's only normal exit is a `break`, so a var assigned before EVERY exiting break
  is definitely assigned after it (scanner.ts scanTemplateAndSetTokenValue idiom). The set-based
  `markAssignments` had no WhileStatement case; add a constant-true case backed by a sound
  single-variable definite-assignment walk (sequential flow, if/else join, abrupt break/return/
  throw/continue). Bails conservatively on any labeled break/continue or a try/labeled statement
  that could hide an exiting break; nested loops/switch opaque. Only `while (true)`. Controls: a
  non-assigning break, `while (cond)`, and a break-in-try all keep firing.
- **Fix 5 (134f9d7a, for-of/for-in shadow; TS2454 −1 compiler / −1 services):** a `for (const X
  of/in …)` loop variable shadowing an outer uninitialized `let X` and bound each iteration
  (generators.ts `let variable; for (const variable of decls) { …variable.name… }`). The
  flow-based used-before-assigned pass descended into the loop body with the outer var still in
  its set — drop loop-declared names (incl. binding patterns) when descending (mirrors the
  catch-body shadowing skip). Controls: outer read outside the loop, and a non-shadowing for-of,
  both fire.
- **Fix 6 (4ad6f1b7, destructured-const shadow of a leaked module var; TS2339 −3 services;
  Blocker #3):** `const { parent } = node` shadowing a module-file `let parent:
  NavigationBarNode` (leaked into globals per round 442) is not bound (B83.5), so a body read
  of `parent` resolved to the leaked outer decl and FP'd `parent.operatorToken` (navigationBar.ts
  getFunctionOrClassName). applyBodyLocalShadowing handled only a simple `let x`; it now also
  registers a `const { … } = init` object pattern's binding names as shadowed and records each
  from the destructured property type of `init` (getPropertyOfType), so the shadowed-name bail
  suppresses a valid destructured-type member access and a following user type-guard
  (`isBinExpr(parent)`) narrows the right type. FP-safe: a genuinely-missing member still fires
  (through the module-var fallback — display divergence only, not an FP).
- **INVESTIGATED & DEFERRED (do not re-chase minimally):** the `Symbol.links` (isTransientSymbol
  narrow-DOWN ×5), `Node → NavigationBarNode` TS2339 (navigationBar.ts's own `const { parent } =
  node` destructured-shadow of the module var, B83.5), and `Node → PropertyAccessExpression` TS2740
  (fixMissingCallParentheses) families all PASS in isolated multi-file repros (incl. barrel imports)
  — they need whole-program conflation/leak context; instrument the services profile, not a minimal
  repro. The `.map`-returns-tuple-array TS2322 (`map(refs, f => [sf, f])` vs `readonly [A,B][]`)
  needs contextual return inference (M3.2). The last `indexInfos` TS2454 is the flow graph's
  if-else-both-assign-then-loop gap.
- **NEXT (services @ 294):** the whole-program narrow-DOWN family (Symbol.links / Type.types) via
  services-profile instrumentation, the `.map` contextual-return M3.2 slice, deep-M3 TS2322/TS2345
  relation fragments.

**Round 449 (2026-07-09) — object-literal spread member-symbol corruption (Blocker #3-adjacent):
ONE root-cause fix clears the `readonly ApplicableRefactorInfo[]` TS2322 ×9 family deferred across
rounds 446-448 as "a type-param-scope pollution needing a whole-program probe" — PLUS the convertExport
`ExportInfo | RefactorErrorInfo` cascade that round 448's NEXT pointer mislabelled a "deep M3
union-of-named-AST relation gap". Dashboard: compiler 185 → 183 (−2), services 321 → 310 (−11), server
529 → 518 (−11), harness 746 → 735 (−11). Suite 9,581 corpus green / 0 fail / 3 skip + 3 local (0
regressions); 1 fix commit (6df8166f); services by-code diff strictly TS2322 −11, zero regressions.**
- **Baseline @ HEAD (round 448): services 321.** The `readonly ApplicableRefactorInfo[]` ×9 was the
  single biggest bounded TS2322 family but had been deferred 3× as "does not reproduce minimally / needs
  a whole-program probe". Built the probe: the chain showed the TARGET member `ApplicableRefactorInfo.actions`
  displaying as `U[]` (a stray unbound type parameter) instead of `RefactorActionInfo[]`.
- **Instrumentation (the decisive tool — the root cause is invisible without it):** (1) probing
  `getTypeFromTypeReference("RefactorActionInfo")` showed it ALWAYS resolves cleanly (scope=null) → the
  `U` does NOT come from resolving the member's type node; (2) probing `getPropertyTypeForRelation`
  showed the SAME symbol (id 47351) returning `U[]` 28× and `RefactorActionInfo[]` 13× — a non-deterministic
  cache; (3) probing the cached VALUE showed two distinct `Type.Reference` objects (element = interface vs
  element = `TypeParam U`) → `symbolTypes[47351]` was being OVERWRITTEN; (4) a `symbolTypes.put`-interceptor
  stack trace pointed at `getTypeOfObjectLiteral`'s `existing != null` override write, reached via
  `tryInferSingleTypeParamFromArgs` (generic inference); (5) dumping the members table showed
  `propNames=[SpreadAssignment, actions]` — the object literal is `{ ...info, actions: [...] }`, and the
  SPREAD had merged the interface member symbol 47351 into `members` BY REFERENCE.
- **ROOT CAUSE:** `getTypeOfObjectLiteral`'s B426 spread merge did `members[pn] = psym` — sharing the spread
  SOURCE's member SYMBOLS. When a later explicit member of the same name overrode a spread member, the
  `existing != null` branch wrote `symbolTypes[existing.id] = <override type>` — mutating the spread SOURCE's
  member type cache GLOBALLY. In the refactor return sites (`return [{ ...info, actions: [...] }]`) the
  override array was typed under a generic-inference context as `U[]`, so `ApplicableRefactorInfo.actions`'s
  cached type became `U[]` and every subsequent relation against `ApplicableRefactorInfo` FP-fired TS2322.
  (The getter/setter override branches similarly MUTATE `existing.declarations` — same latent hazard.)
- **FIX (Checker.kt, spread branch of getTypeOfObjectLiteral):** COPY each guaranteed spread member into a
  FRESH literal-owned `Symbol` (carrying `psym`'s resolved type via `getTypeOfSymbol` + declarations +
  valueDeclaration + parent, so reads/optionality/positions are byte-unchanged), so an override touches the
  literal's own symbol — never the shared source member. Core hot/shared path, so gated on the full corpus
  suite (9,581/0/3, byte-clean) + a `symbolTypes`-write A/B before/after.
- **Removed by position (11 TS2322, 0 added):** 7 `ApplicableRefactorInfo[]` (extractType,
  convertParamsToDestructuredObject, moveToNewFile ×2, convertOverloadListToSingleSignature,
  inferFunctionReturnType ×2) + convertExport.ts:85/89 (the round-448 "deep M3" mislabel — same bug) +
  checker.ts:9392 + moduleNameResolver.ts:2365 (compiler-side cascade, hence compiler −2).
- **3 local tests (SpreadOverrideMemberCorruptionTest):** the refactor-shaped return, the corruption
  invariant (`{ ...base, actions: [{ name, extra }] }` must not corrupt `Info.actions` for a later
  relation — VERIFIED to FAIL on the reverted buggy version), and a negative control (a genuinely-wrong
  override element still fires). NOTE the refactor-shaped minimal test PASSES even on the buggy version
  (the `U[]` corruption needs the whole-program generic-inference context — the services profile is its
  proof); test 2 is the load-bearing pin.
- **NEXT (services @ 310):** TS7006×8 (inferFromUsage `Priority[]` — the B83.5 nested-interface-resolution
  blocker; land WITH the reverted round-443 array-element contextual-typing enabler), TS2454×5 (definite-
  assignment `while(true)`-break flow gap: `resultingToken`/`indexInfos`/`variable`/`previousRange`),
  residual deep-M3 TS2322/TS2345 relation fragments (each ≤3).

**Round 448 (2026-07-08) — TS2322/TS2774 burn-down: `this.optionalProp = undefined` write +
discriminated-union object-literal return + module-var-leak local alias + destructured-shadow TS2774:
FOUR bounded fixes, all suppression-only / FP-safe. Compiler UNCHANGED (185 — the families live in
services-side files: services.ts, completions.ts, signatureHelp.ts, jsDoc.ts), services 339 → 321 (−18),
server 555 → 529 (−26), harness 772 → 746 (−26). Suite 9,577 corpus green + 10 local (0 regressions);
commits 1b65da39 / 764afbd0 / da2f421b / d34b48d2; every services diff strictly by-position removals
via the `--listAll` `comm` loop.**
- **Baseline @ HEAD (round 447): services 339.** Bucketed the `--listAll` TS2322×188: the biggest CLEAN
  bounded families were the services.ts `undefined`-to-optional constructor field resets (×10) and the
  completions.ts discriminated-union return (×5). The `readonly ApplicableRefactorInfo[]` ×9 stayed the
  round-447 deferred stray-`U[]` type-param-scope pollution (needs a whole-program probe).
- **Fix 1 (1b65da39, `this.optionalProp = undefined`; TS2322 −10 services; services 339 → 329, server
  555 → 537, harness 772 → 754):** the string-based `this.prop = value` write path
  (`checkAssignmentExpression` ~88528, taken when `varTypes["this.$prop"]` is set) is OPTIONALITY-BLIND —
  `varTypes` stores the bare type-NAME via `resolveSimpleTypeName`, dropping the `?`. So
  `this.parent = undefined` where `parent?: Symbol` (services.ts SymbolObject/NodeObject constructor field
  resets) FP-fired TS2322. An explicit `| undefined` in the declared type, or an array-typed optional,
  already passed the lenient string relation — which is why ONLY bare-interface/class-typed optionals FP'd.
  Added the `thisPropertyIsOptional(propName)` helper (consulting `currentClassForThis`'s OWN members) and
  a bail for a bare-`undefined` RHS to a non-eOPT optional field, mirroring the type-engine
  `checkPropertyAccessAssignment` undefined-optional bail at 89644. Negative controls (non-optional field,
  eOPT) keep firing.
- **Fix 2 (764afbd0, discriminated-union object-literal return; TS2322 −5; services 329 → 324, server 537
  → 532, harness 754 → 749):** `return { type: "cases" }` vs `... | { type: "cases"; } | { type: "none"; }
  | ...` (completions.ts getSymbolCompletionFromEntryId, 5 returns). `getTypeOfObjectLiteral` WIDENS the
  discriminant to its base primitive (source displays `{ type: string }`), so it matched no union member
  and the coarse return relation failed. The return path (`checkReturnAssignability`, before the coarse
  relation) now retries the union relation with `withFreshObjLitSource(expr)` (round 435) — propertiesRelatedTo
  recovers the un-widened literal from each PropertyAssignment per union member, so the object relates to its
  discriminated member. Suppression-only (only when the retry PASSES) → FP-safe: an object with a non-matching
  discriminant, or a matching discriminant with a wrong property TYPE, still falls through and fires
  (both negative controls pinned). Gated `targetType is Type.Union && canUseTypeEngine`.
- **Fix 3 (da2f421b, local aliased from a leaked module var; TS2322 −2; services 324 → 322, server 532 →
  530, harness 749 → 747):** the Blocker #3 module-var leak reaching through a local alias. `const invocation
  = parent` where navigationBar.ts's module-level `let parent: NavigationBarNode` leaked into globals (round
  442) and the destructured `const { parent } = node` in signatureHelp.ts is unbound (B83.5) → `invocation`
  inherited `NavigationBarNode`, poisoning the nested object-literal value `{ node: invocation }` in a returned
  ArgumentListInfo (the bare-Identifier moduleFileLocalVarNames bails can't reach a value nested inside an
  object literal). The un-annotated var-decl inference (`checkVarDeclAssignability` ~84486) now returns early
  (records nothing → the alias resolves as anyType) when the initializer is a bare leaked-module-var Identifier
  that is NOT this file's own binding AND NOT already in `currentLocalTypes` (a genuine same-named param
  `(parent: Node)` IS recorded → keeps its real type — firewall pinned). Cleared 2 of the 3 signatureHelp
  ArgumentListInfo FPs; the 3rd uses `node: parent` DIRECTLY in the object-literal value (no alias) — a
  getTypeOfObjectLiteral property-value bail would clear it but that is a hot/shared path, deferred.
- **Fix 4 (d34b48d2, destructured local shadows a same-file function; TS2774 −1; services 322 → 321,
  server 530 → 529, harness 747 → 746):** `const { hasReturn } = commentOwnerInfo` (jsDoc.ts
  getDocCommentTemplateAtPosition) shadows a same-file module-level `function hasReturn`, but the TS2774
  uncalled-function walker's shadow-collector (`collectUncalledTypedLocalsFromBody`) handled only a simple
  `const x` — it skipped binding patterns (`d.name as? Identifier ?: continue`). So `hasReturn` in
  `hasReturn ? … : …` resolved to the function → FP "always defined, did you mean to call it". Binding-pattern
  names are now registered as shadows via `collectBindingNames` (shadow-only, consistent with an untyped
  simple local); a genuine uncalled same-file function in a condition still fires (firewall pinned).
- **INVESTIGATED & DEFERRED:** (a) the `SourceFileLike` object-literal conflation (sourcemaps.ts/textChanges.ts
  ×2) — the base `interface SourceFileLike` LACKS `getLineAndCharacterOfPosition` (added by a `declare module`
  augmentation), so an AST-based satisfaction check against the raw InterfaceDeclaration reads it as EXCESS →
  can't verify FP-safely without merging augmentation members; fragile, skipped. (b) the convertExport
  `ExportInfo | RefactorErrorInfo | undefined` ×3 — reproduces CLEAN in a minimal `A || {objLit}` union return,
  so it is NOT a `||`-unwrap gap; the real FP is a deep M3 union-of-named-AST-interfaces relation
  (`exportNode: ExportToConvert` where `ExportToConvert` includes `IsInterface = InterfaceDeclaration` — big
  AST-node interfaces). (c) the `X | undefined` arg-vs-non-nullish-`Node`-param cases (es2015/convertParams/
  fixUnreferenceable) — element-access reference paths + big-AST-union `.kind`-discriminant filtering (M1.4),
  each a per-site narrowing gap.
- **NEXT (services @ 321, all deep/whole-program):** ApplicableRefactorInfo stray-`U[]` type-param-scope
  pollution ×9 (whole-program probe), the `InterfaceDeclaration`→`IsInterface` union-of-named-AST relation gap,
  array-of-union generic-inference (compact/slice → `readonly T[]`), big-AST-union discriminant filtering (M1.4).

**Round 447 (2026-07-08) — cross-file conflation emission-site bails (ARG + RETURN sides, Blocker #3)
+ nested-arrow inner-local shadowing: FIVE fixes across four commits (four suppression bails + one
general shadowing correctness fix). Compiler UNCHANGED (185 — services-side conflations/leaks/shadowing),
but they generalize uniformly: services 373 → 339 (−34), server 589 → 555 (−34), harness 806 → 772
(−34). Suite 9,558 → 9,571 (+13 local across 5 test files, 0 regressions); commits d4065ea6 /
72b441c7 / da8c64a9 / ebc83ea3; every services diff strictly by-position removals via the `--listAll`
`comm` loop.**
- **Baseline @ HEAD (round 446): services 373.** Bucketed the `--listAll`: the round-446 NEXT pointer's
  `ApplicableRefactorInfo` ×9 root-caused to a stray-`U[]` type-param-scope pollution that does NOT
  reproduce minimally (deferred — needs a whole-program probe); the clean bounded veins were the
  conflated-`Info` families the round-444/445 machinery already understands.
- **Fix 1 (d4065ea6, return object literal vs a conflated file-local TYPE-ALIAS union; TS2353 6 → 0;
  services 373 → 367):** the EXCESS-property complement of round 444's alias's-own-file member-access
  bail. In the file declaring `type X = A | B | …`, a `return { … }` excess-checked against `X`
  resolves — last-wins Interface+TypeAlias merge — to a SIBLING file's `interface X`
  (fixAddMissingMember.ts's `type Info = TypeLikeDeclarationInfo | EnumInfo | …` vs 12 sibling
  `interface Info`), FP'ing "'kind' does not exist in type 'Info'". `objectLiteralMatchesConflated-
  FileLocalTypeAlias` bails when THIS file declares `type X`, the target names the conflated `X`, and
  the object satisfies some alias-union member interface. **LANDMINE (cost me an instrumented CLI trace):
  the union member interfaces (`FunctionInfo`/`SignatureInfo`) are THEMSELVES conflated — `getProperties-
  OfType` returned polluted merged members (extra `selectedVariableDeclaration`/`newParameters`/… from
  sibling files), so 4 of 6 initially stayed FP'ing. The satisfaction check must read each member
  interface AST-side (`objectLiteralExactlySatisfiesFileLocalInterface` — no-excess + required-provided
  from the file's own InterfaceDeclaration), NOT via the resolved constituent members.**
- **Fix 2 (72b441c7, ARG-side conflated-alias PARAM; TS2345 `SourceFileLike` 8 → 0; services 367 → 359):**
  the arg complement of round 443's conflated-type-alias RECEIVER bail. A param typed as a leaked
  conflated `type X` (importTracker.ts's `type SourceFileLike` vs compiler/types.ts's `interface
  SourceFileLike`) resolves, in a NON-owning file, to the bogus alias union, so an object/class-instance
  arg satisfying the real interface FP'd. `paramTypeIsLeakedConflatedAlias` skips when the param displays
  as a conflated name and the file is not the alias's own.
- **Fix 3 (72b441c7, ARG-side leaked-var chain; TS2345 10 incl. 6 compiler-file leak-chains; services
  359 → 349):** the arg complement of round 444's receiver chain-walk. Round 442 bailed only a
  bare-Identifier leaked-var arg; the root can sit behind a property-access chain
  (`isCallExpression(parent.parent)` where `parent` leaks navigationBar.ts's `NavigationBarNode`).
  `argRootIsLeakedModuleVar` walks the arg to its root Identifier and bails on a leaked module var
  (a CALL in the chain breaks the walk).
- **Fix 4 (da8c64a9, return object literal vs a MULTI-member union with a conflated interface; TS2322
  FunctionInfo ×2; services 349 → 347):** round 445's `objectLiteralMatchesConflatedFileLocalInterface`
  used `.singleOrNull()` (single non-nullish member). Extended to `X | Err | undefined` unions — the
  object is assignable iff it EXACTLY satisfies some conflated file-local interface member (tsc's refactor
  `getInfo(): FunctionInfo | RefactorErrorInfo | undefined` shape).
- **Fix 5 (ebc83ea3, nested-arrow/fn-expr inner-local SHADOWING; TS2339 `ExportInfoMap` ×8; services
  347 → 339): a GENERAL correctness fix, not a suppression.** The round-444 NEXT pointer mislabelled this
  as a "destructuring-reassignment" gap; the real cause (found by reproducing it minimally — it DOES
  reproduce, unlike the leaks) is a nested-scope shadowing gap: `checkPropertyAccessInExpr`'s
  ArrowFunction / FunctionExpression branches recorded PARAM types on entering the body but never called
  `applyBodyLocalShadowing` for the body's own local declarations. completions.ts has an outer
  `const exportInfo: ExportInfoMap` and, in a nested `forEachEntry` callback, an inner
  `let exportInfo: SymbolExportInfo | FutureSymbolExportInfo` — reads of `exportInfo.exportKind`/`.symbol`/
  `.moduleFileName` resolved to the OUTER `ExportInfoMap` → FP TS2339. Both branches now call
  `applyBodyLocalShadowing(body.statements, paramNames)` for a Block body and save/restore
  `currentShadowedNames` (so the inner shadow does not leak outward). Suite-verified 0 corpus regressions
  from the broader walker change; 3 local tests pin the shadow + both firewall directions.
- **NEXT (services @ 339):** the `X | RefactorErrorInfo | undefined` object-vs-union RELATION gap for
  SINGLE-FILE interfaces (InliningInfo/OptionalChainInfo/ExportInfo — genuine M3, not conflation); the
  `ApplicableRefactorInfo` stray-`U[]` type-param-scope pollution ×9 (deferred, needs a whole-program
  probe — does not reproduce minimally); deep fragmented TS2322.

**M2 — Real-lib migration (staged; decompose further at start)**

- [x] **M2.1 Lib graph loader.** COMPLETE (round 390, all four sub-steps below). Parse + bind the real `typescript-repo/src/lib/*.d.ts`
  selected per `target`/`lib` (the `/// <reference lib="…" />` DAG: lib.es2020 →
  es2019 + es2020.* pieces), as a process-wide immutable snapshot parsed ONCE and
  shared across programs (this snapshot is deliberately the seed of M5's incremental
  infra). Behind a CompilerOptions flag so corpus A/B comparison is possible.
  **Decomposition (round-389 scoping; work as separate commits):**
  - [x] (a) *Ship the lib text* — DONE (round 390): `generateRealLibSources`
    Gradle codegen (guardrail-approved as part of M2) extracts the non-DOM ES set
    (100 files, 565,732 bytes) from the typescript-repo object DB (`git ls-tree` +
    `git show` at the pin — works offline, the sparse working tree never
    materializes `src/lib`) into `build/generated/real-lib/RealLibFiles.kt`
    (commonMain srcDir; every Kotlin compile task depends on it). The 64 KB
    class-file string-constant TRAP is dodged by chunking each file into
    `sb.append("…")` literals of ≤ 60,000 modified-UTF-8 value bytes split at
    line boundaries (es5 = 4 chunks), reassembled at runtime — never fold chunks
    into one literal / `const val` concat (constant-folds back over the cap).
    Keys are bare lib names (`es5`, `es2015.core`); content byte-faithful (CRLF
    preserved). 3 local tests (RealLibFilesTest) pin multi-chunk reassembly +
    the reference directives (b) will consume.
  - [x] (b) *DAG resolver* — DONE (round 390): `RealLibResolver` (RealLibs.kt)
    ports tsc's `libEntries`/`libMap` verbatim (110 entries incl. the `es6`/`es7`
    aliases + the `esnext.bigint`-style back-compat fallbacks),
    `targetToLibMap`/`getDefaultLibFileName` (target default = the `.full`
    variant; ES2015 → `lib.es6.d.ts`, ES5/ES3 → `lib.d.ts`), the
    `/// <reference lib>` closure (program.ts `processLibReferenceDirectives`),
    and — the non-obvious part — tsc's FINAL order = `getDefaultLibFilePriority`
    (libEntries index; `lib.d.ts`/`lib.es6.d.ts` first), NOT the DFS discovery
    order (es5 references decorators, which still sorts near the END). Unshipped
    DOM/host references and unknown names are returned in `Resolution` side
    channels, not silently dropped. 6 local tests (RealLibResolverTest) against
    the real shipped headers.
  - [x] (c) *Snapshot* — DONE (round 390): `RealLibSnapshots` caches the PARSE
    per lib file process-wide (immutable shared ASTs; fileName = the DISTRIBUTED
    name `lib.es5.d.ts`/`lib.d.ts` that baselines render); BINDING is
    deliberately per-consumer (`bindLibFiles` returns fresh BinderResults) —
    `mergeSymbolTable` MUTATES merged-in symbols (the merge-pollution gotcha),
    so a shared bound table would leak one program's user-declaration merges
    into the next program's lib; revisit bind-sharing at M5.4/M5.5. Not
    thread-safe yet (single-threaded checking today; M5.4 adds sync).
    `CompilerOptions.useRealLibs` (default false) added. The real es5.d.ts
    (218 KB) parses + binds cleanly (Array/Object/Promise/parseInt all bound).
    4 local tests (RealLibSnapshotTest) pin parse-once identity, fresh-bind
    non-identity, and dist naming.
  - [x] (d) *Checker wiring + A/B* — DONE (round 390): `bindRealLibs()` in
    Checker (gated `options.useRealLibs`; `// @useRealLibs` directive added)
    resolves `options.lib`/`target` through `RealLibSnapshots`, merges each
    file's locals in inclusion order (es2016.array.include's `Array<T>` merges
    onto es5's — verified end-to-end by `[1,2,3].includes(2)` type-checking
    clean), and populates the same `builtinLibDecls`/`builtinLibMemberDecls`
    identity sets; `builtinLibSourceFile` keeps the first (es5-layer) file
    (multi-file position lookups are inherently ambiguous; lib diagnostics
    render `:--:--` so only the display name is affected). 4 local smoke tests
    (RealLibsInCheckerTest). **A/B (default temporarily flipped true, full
    corpus): 40 failures out of 8,961 — ALL error-baseline subtests, ZERO
    js-emit regressions, +70% wall time (1:54 → 3:14). The 40 are the predicted
    compensating-hardcode collisions — the M2.2 burn-down list (below).**
- [ ] **M2.2 Corpus A/B and default flip.** Burn down the round-390 A/B diff
  (baselines were produced by real-lib tsc, so divergence generally means one of our
  compensating hardcodes — fix by deletion). Flip the default when green. **Round 391
  fixed 2 (arguments + unaryOperatorsInStrictMode — value-position spelling suggestions).
  Round 392 fixed the TS2728 lib-file-attribution cluster (libMembers + externModule +
  errorMessageOnObjectLiteralType; initializedDestructuringAssignmentTypes also cleared).
  Round 393 fixed the lib-declared utility-alias modifier cluster (omitTypeHelperModifiers01,
  omitTypeTestErrors01, intersectionsAndOptionalProperties, parameterListAsTupleType via
  `isBuiltinUtilityAlias` materializer routing) + redefineArray (construct-sig double-emit
  guard). Round 394 fixed keywordExpressionInternalComments (Object.prototype-member
  fallback in the TS2790 delete check — `delete Array.toString` under real libs).
  Round 394 ALSO fixed jsExportMemberMergedWithModuleAugmentation2 (node-first
  `libFileOfDecl` in the B553 CJS-string-import TS2728 builder, the unwired 4th of
  round 392's attribution sites).
  A/B RECOUNT (round 394): 27 corpus failing testcases remaining
  (`.errors.txt` subtests):** arrayBufferIsViewNarrowsType, builtinIterator,
  consistentAliasVsNonAliasRecordBehavior, correctOrderOfPromiseMethod,
  deleteExpressionMustBeOptional_exactOptionalPropertyTypes (×2 variants),
  dissallowSymbolAsWeakType, divergentAccessorsTypes6/8,
  doYouNeedToChangeYourTargetLibraryES2016Plus, flatArrayNoExcessiveStackDepth,
  genericIndexedAccessVarianceComparisonResultCorrect, implementArrayInterface,
  interfaceAssignmentCompat, isArray,
  keyRemappingKeyofResult,
  mappedTypeGenericWithKnownKeys,
  mappedTypeIndexedAccessConstraint, mergedClassNamespaceRecordCast,
  narrowingPastLastAssignment, requiredMappedTypeModifierTrumpsVariance,
  specialIntersectionsInMappedTypes, stringMappingAssignability,
  templateStringsArrayTypeRedefinedInES6Mode, truthinessCallExpressionCoercion2,
  typedArraysCrossAssignability01, uncalledFunctionChecksInConditional2. Most are
  documented lib-divergence pins (typed-array chains, Date/Array hardcoded counts,
  LIB_MIN_TARGET) — M2.3's unwind list overlaps heavily; work them together. Also
  measure/mitigate the +70% suite wall time before flipping (per-key bound-lib reuse
  within a run, or M5-style sharing). **Triaged failure MODES (round 392 sampling; see
  the round-392 note): TS2322-from-richer-lib-types (correctOrderOfPromiseMethod
  Promise.all tuple, narrowingPastLastAssignment evolving-array concat, keyRemappingKeyofResult
  → engine/M3); SWAP (omitTypeHelperModifiers01 TS2540↔TS2322 — Omit modifier/readonly);
  MISSING (mergedClassNamespaceRecordCast/interfaceAssignmentCompat/divergentAccessorsTypes6 —
  Record cast + documented walkers); double-emit/display (builtinIterator TS2515 dup,
  doYouNeedToChange... `Promise<T>` vs `Promise<unknown>`); keywordExpressionInternalComments
  = we emit NOTHING under real libs (investigate — possible exception).**
- [ ] **M2.3 Unwind lib-divergence pins.** Grep anchors: `LIB_MIN_TARGET`,
  `LIB_MIN_TARGET_SOFT`, `BUILTIN_LIB_VALUE_INTERFACES`, `KNOWN_GLOBALS` (derive from
  the loaded libs), the hardcoded Date TS2740 message, hardcoded "and N more" counts,
  hardcoded overload chains copied from baselines (`WEAKSET_2769_CHAIN` etc.),
  `libFeatureAvailable`. Delete `BUILTIN_LIB_SOURCE` last.
**M3 — Type-engine completion, dashboard-driven (the long pole; re-scope 2026-07-03:
the acceptance bar per item is the self-compile burn-down — handle the shapes tsc's
source uses with the corpus suite as the regression net, NOT conformance completeness;
each item still decomposes into a multi-session campaign — read PLAN-PHASE-4.md §
"Known architectural blockers" for accumulated detail before starting)**

- [ ] **M3.1 Generic instantiation + call-site inference** (remove the
  `hasUnresolvedTypeParams` relation bail; real type-argument inference incl.
  contextual return positions). This is the documented #1 engine blocker. V1 bar
  (re-scope 2026-07-03): burn down the compiler profile's TS2322×777 (top shape
  `Type 'T[]'` ×174), TS7006×301 (call-arg contexts whose callee doesn't resolve),
  and the TS2345 share — tsc-source shapes only; full conformance generality is
  post-v1. **STARTED (round 428, −391: 1,577 → 1,186):** nullable-union generic
  param inference (`nullableUnionOfTpMode`) + overloaded all-generic callee
  inference in `getReturnTypeOfCallExpression` killed the `T[]`-return family
  (TS2322 751 → 501); the TS2345 histogram top (this-param binding, guarded
  optional-member args, enum→number) + the body-local-shadows-function anyType
  registration took 394 → 261 (TS2769 45 → 36). **CONTINUED (round 429, −186:
  1,186 → 1,000, TS2345 261 → 86):** call-types lexical shadowing (body-locals
  vs enclosing params; destructured params — the round-428 "mini-repro does not
  reproduce" residue was DESTRUCTURING, resolved via the
  `currentParamBindingNames` side set in `getTypeOfIdentifier`; arrow
  own-params), String-lib RegExp signatures, optional-param union args,
  NonNull-asserted args, guard-narrowed interface/unknown args (the ~110-site
  dominant mechanism, never-param excluded), string-enum→string (round-410
  deferral resolved), rest-arg flow narrowing. Next sub-slices (triaged in the
  round-429 session note): `'true'` vs `'false'` nested-overload selection ×5,
  string-vs-literal-union args ×10, residual `T[]` inference-gate misses (~40:
  readonly-array `TypeOperator` params defeat `nullableUnionOfTpMode` —
  `addRange(to: T[] | undefined, from: readonly T[] | undefined)`),
  `SearchResult<T>` un-inferred generic Reference returns ×10, `string | string`
  interface-override literal props ×24 (M3), inferred type predicates (tsc 5.5 —
  `helper => !helper.scoped`, M3.4), exhaustive-switch `assertType<never>`
  (M3.4 exhaustiveness). **CONTINUED (round 430, −64: 1,000 → 936):** the
  `T extends {}` constraint was killing the whole `append` inference (empty-object
  relation rule, TP-source excluded per genericPrototypeProperty3), readonly-array
  anchors (`Reference(ReadonlyArray, [T])`), TP-from-PREDICATE binding
  (`getFirstJSDocTag(node, isJSDocAugmentsTag)` → T from the guard's target).
  **CONTINUED (round 431c/d, part of −385: 936 → 551):** engine return-checking
  reaches switch/try bodies (returnTypeNode threading through both dispatchers)
  behind the FOREIGN-TP source gate (`typeContainsForeignTypeParam` — an
  un-inferred generic call result is our inference gap, not a user error;
  cleared the `T[]`/`U | undefined`/`SearchResult<T>` return families, ~130
  sites incl. anonymous-alias-body members; round 431e extended it to the
  var-decl/assignment/property-write/conditional-return paths, −69, with the
  sig-own-TP refinement keeping generic fn-value sources checkable).
  **CONTINUED (round 435, −109: 482 → 373):** generator TReturn returns, fresh
  object-literal literal props (freshObjLitRange relation retry),
  TP-literal-constraint args, the union-decomposition-transparent relation
  re-entry gate (resolves the NodeArray-covariance family ×23 — NOT a heritage
  gap after all), bare-`new` contextual instantiation, the foreign-TP gate on
  assignment TARGETS (visitor family), nullish alias-union returns.
  **CONTINUED (round 436, part of −79: 373 → 294):** TP-carrying
  callback-return param skip (the forEachEntry ×14 family), destructured-
  LOCAL shadowing (semver/checker + 12 transformer sites), literal-return
  syntactic union membership, explicit-type-arg overload selection
  (constraint-filtered), overload-helper optional-param/foreign-TP arg
  rules. Next: contextual-RETURN inference
  (`parseTokenNode<T>()`, no args — M3.2), `Iterable<T>`-style single-arg
  generic anchors, `.map`-family callback-return inference (M3.2 — also
  findAncestor predicate-overload returns, the residual TS2769 core).
  **CONTINUED (round 440, part of 228 → 210): the CALLEE-resolution half —
  getCalleeType now consults currentParamBindingNames (destructured-const
  body-local shadows a cross-file function callee) AND prefers a same-file
  FunctionDeclaration over merged globals (Blocker #3 name collisions:
  getBuildInfo/createWatchStatusReporter/... picked the wrong file's fn),
  function-only-gated so a type-only `import { Date }` interface doesn't
  shadow the global Date VALUE; PLUS tryInferSingleTypeParamFromArgs binds T=any when a TP's
  only candidate is an any-typed arg at a return-type site (`Debug.checkDefined(pos)` where
  `pos` is a destructured-const anyType local returned the un-inferred T — UNMASKED once fix C
  stopped pos resolving to a cross-file function). Generalizes (folding round 439): services
  −79 / server −80 / harness −83.** NEXT: the constraint-chain `TKind extends JSDocSyntaxKind
  extends SyntaxKind` TS2344, and the deeper whole-object / branded-string TS2322 relation gaps.
- [ ] **M3.2 Contextual typing engine** (parameters, returns, object/array literals,
  generic-context propagation — replaces `applyContextualParamTypesForArrow`-era
  special cases). **STARTED (round 431, −295 of the session's −385): the TS7006
  core fell 301 → 11** — callee resolvability (nested-fn map + the new
  `implicitAnyScopes` lexical scope stack), assignment-RHS contextual typing
  from the LHS declared type (B476 single-applicable-sig rule; `||`/`??` both
  operands, `&&`/comma right-only — corpus-pinned asymmetries), receiver
  member resolution through intersections/lazy References/extends bases, and
  call-return-annotation locals. Residual TS7006×11 triaged in the round-431
  note (namespace-local annotations, initializer-inferred fn locals).
  **CONTINUED (round 435c, TS7006 11 → 1):** namespace-local annotations
  (implicitAnyNsStack bridge), initializer-typed locals (implicitAnyScopeInits),
  the Map.get idiom, nullish-union member ctx. Residual ×1: tsbuildPublic's
  destructured-member local.
  **CONTINUED (round 439, 244 → 236): findAncestor-style predicate-overload RETURN
  inference — a generic overload with a type-guard-callback param `(x) => x is T`
  returning `T | undefined`/`S[]` infers T from the actual guard arg's predicate
  target (`tryInferPredicateOverloadReturn`, before the B136 concrete-overload swap)
  + a companion `<call>()!` concrete-union NonNull strip. This is the residual TS2769
  "findAncestor predicate-overload returns" bucket the round-436 note flagged.**
- [ ] **M3.3 Mapped / conditional / template-literal / indexed-access evaluation**
  (replace the AST-shape walkers; delete the superseded dedicated walkers and pins).
- [ ] **M3.4 Flow narrowing unified into identifier typing** (`getTypeOfIdentifier`
  consults the flow graph; retire the per-consumer narrowing carve-outs).
  **CONTINUED (round 436f/g): switch-case narrowing of a BARE string subject
  (semver operator family) + guard-gated ternary RETURN arms (the
  checkConditionalReturnBranches tri-state — utilities.ts's
  memberIfLabeledElementDeclaration family, −22 combined).**
  **CONTINUED (round 438, −48 of the session's −50): FOUR symmetric extensions of
  the type-guard-narrowing consumers, all suppression-only — the assignment-RHS AND
  return-path gates now accept a `Type.Union` target (`currentSourceFile = node` /
  `return node` vs `SourceFile | undefined`); the call-arg guard-narrow-DOWN branch
  covers PROPERTY-ACCESS args (`getExports(node.left)`); and object-literal property
  VALUES narrow in getTypeOfObjectLiteral, NULLISH-STRIP-gated (`objLitValueNullishStrip`
  — rejects the name-based-flow shadowing hazard).**
  **CONTINUED (round 440): two operator/optional-property gaps (NOT flow-narrowing but
  same M3.4 family) — `combineBinaryTypes` types `a ?? b` as `NonNullable<a> | b` (strips
  the left's nullish/void; `verbosityLevel ?? -1` → `number`), and
  checkNestedObjLitPropTypes' per-property leaf routes the target member through
  widenOptionalTargetPropType so a fresh `T | undefined` value passes an optional `a?: T`
  (sourcemap.ts captureMapping).** Residual M3.4 slices (mostly NOT
  reproducible in isolation — need the exact flow context): `number | undefined`→number
  reassignment flow ×4, `TempFlags | undefined`→TempFlags NonNull-assign ×2,
  `undefined => Symbol/Expression/SyntaxKind` M1.9 assignment-target ×5 (the write path
  should use the DECLARED type, not the narrowed one — a focused flow change),
  `Node`→never exhaustiveness ×3, moduleNameResolver `unknown` typeof-narrowing ×3.** **Absorbed
  from M1.2 (round 386): faithful TS2563 walk-exhaustion emission — DONE (round 426,
  earlier than predicted: the existing narrowing/definite-assignment walkers ARE deep
  flow walks, so trip detection didn't need full flow-based identifier typing).**
  Depth-trip at 2000 recursion levels in all three flow walkers → one-shot TS2563 at
  the containing function-or-module block + per-container `flowDisabledRanges`
  (replacing B399's per-file node-count heuristic + its `cfaTooLargeFiles` TS2454
  filter — the 27 self-compile TS2563 FPs are gone: 26 from the proxy removal, the
  27th via round 426b's asserts-callee gate in `flowCallMightNarrow`; TS2454×20
  pre-existing walker FPs the per-file filter had masked are now honestly visible,
  the next bounded burn-down bucket). The corpus largeControlFlowGraph shape
  (top-level evolving-array writes) trips via the dedicated `evolvingArrayWalkTrips`
  init walk (pinned by CfaTooLargeBailTest — the generated corpus test is
  JS-emit-only); GENERAL use-site evolving-array typing (function-local auto arrays)
  still belongs to this item's flow-based identifier typing. **Absorbed from M1.4 (round 387):
  the self-compile TS2339 family's dominant bucket (461 union-receiver sites + the
  named `Type`/tuple ones) is user-type-guard narrowing feeding MEMBER ACCESS on tsc's
  big AST-node unions (`isTypeParameterDeclaration(node) ? …node.name… : …` on
  `HasModifiers`; `isGenericTupleType(type) && type.target.…`) — the narrowing
  consumers exist, but predicate-filtering 40-member merged-interface unions (and
  ternary-position narrowing) under-resolves; measure per-consumer before rebuilding.**
  **DONE (round 409, 8f22d126) for the Identifier-callee case — a user type-guard / assert
  imported through an `export *` barrel now NARROWS.** Two independent gaps blocked it (round
  408's naive "wire resolveAlias into resolveFlowCalleeDecl" was inert because of the FIRST,
  found only this session): (1) `resolveModuleSpecifier` won't strip the ESM `.js` extension
  (TS2459 FP-avoidance) → `resolveAlias` couldn't resolve ANY `.js` import (tsc uses `.js`
  everywhere), so even a DIRECT imported guard failed; (2) `targetFile.locals[name]` misses
  through an `export *` barrel. Fixed FLOW-ONLY via `resolveImportedFunctionLikeDecl` (memoized;
  finds the module `.js`-tolerantly + follows `export *` via `resolveExportedSymbolThroughStars`).
  **Deliberately NOT in the general `resolveAlias` — a first cut there measured a self-compile
  REGRESSION 2,618 → 2,915 (TS2315×466 flood from resolving barrel-imported TYPES, an M3 gap),
  reverted.** Self-compile 2,618 → 2,443 (TS2339 838 → 672); services hang-check clean.
  **ALSO DONE (same session, 4d0192ad): the barrel-imported NAMESPACE-member case**
  (`Debug.assertIsDefined(x)` / `Debug.isString(x)`). `resolveNamespaceMemberFnDecl` resolved the
  receiver `Debug` via the general (byte-identical) `resolveAlias`, so a barrel-imported namespace
  didn't resolve → the member guard/assert never narrowed. Added the flow-only
  `resolveImportedNamespaceSymbol` (the namespace-receiver sibling of
  `resolveImportedFunctionLikeDecl`; memoized in `importedNamespaceSymCache`, never touches the
  resolveAlias cache), consulted only when the general resolveAlias fails to yield a module symbol.
  2 load-bearing tests (both verified to FAIL without it). **DASHBOARD-NEUTRAL on the compiler
  profile (2,443 → 2,443) — the round-408 `Debug.assertIsDefined(machine.onLeft)` cases were
  flagged "unreproducible" (a deeper cause than resolution), so resolving the barrel `Debug` alone
  doesn't flip a compiler-profile FP; landed as a principled capability extension (cf. round-404's
  neutral M1.13) for the other 7 profiles / real projects where barrel-imported namespace guards
  are ubiquitous.** Also pervasive: `some(x)`/`isDefined(x)` Identifier guards across the
  TS18048/TS2339/TS2722 families are now narrowed. **REMAINING M3.4 investigation: the round-408
  `Debug.assertIsDefined` FPs (×3) have a root cause OTHER than resolution — worth a fresh repro
  (generic-class param-property assert + the `asserts x is NonNullable<T>` path through a real-code
  interaction) now that the barrel resolution is no longer a confound.**
  **ALSO DONE (round 411, aba1dcb6 + 7a771a77) — two more union-narrowing slices, −59 (TS2339
  672 → 614): (a) DISCRIMINATED-UNION narrowing keyed on an ENUM-MEMBER discriminant
  (`s.type === Kind.A` / `switch (s.type) { case Kind.B }` where the member declares
  `type: Kind.A`). Enum-member types resolve to `anyType` (not modeled as literals), so neither
  the equality path (`narrowByDiscriminantProperty`) nor the switch path (`narrowBySwitchClause`)
  matched — AST-based fix keyed on the member's declared `type: Enum.Member` annotation; the
  barrel-imported enum resolves FLOW-ONLY via `resolveImportedEnumSymbol` (the enum sibling of
  `resolveImportedNamespaceSymbol`). Unlocked tsc's UpToDateStatus (23→1) / TypeMapper (16→6) /
  PrivateIdentifierInfo (13→0). (b) A type-guard `x is C` narrows a union member DOWN to `C` when
  `C <: member` — `narrowByCallPredicate`'s positive branch only kept `member <: C` and collapsed
  a supertype-only union to `never` (`Expression | PropertyName` narrowed by
  `is TaggedTemplateExpression` → the `never`-receiver TS2339 family, 39 → 20). Both FP-safe /
  suppression-only. Remaining `never`×20 (generic-alias resolution, closure-capture),
  `Type`×46 (closure-capture + `&&`-narrowing into a `findIndex` callback), TS2722×2 (loop-stable
  narrowing of un-reassigned property paths / object-literal-method flow) are the next M3.4
  sub-steps — each needs narrowing to survive a FlowLoopLabel / flow into closures + object-literal
  methods, not a bounded slice.**
  **ALSO DONE (round 413, c4c8850c + 68da80da) — the builder.ts `Debug.assert(isDefined(state))`
  TS18048 family (round-412's flagged "highest-value M3.4 target") is FIXED, −407 (TS2339 614 →
  237). The round-412 "walk hits `NARROW_MAX_DEPTH`" diagnosis was a RED HERRING (an instrumented
  run showed ZERO narrowing-walk truncations; the assert and use are co-located). The real cause:
  `computeExportedSymbolThroughStars`'s leaf lookup returned a non-re-exported IMPORT alias, so the
  `export *` search for `Debug` stopped at `core.ts` (which merely IMPORTS `Debug`) before reaching
  `debug.ts`'s `export namespace Debug` — `Debug.assert` never resolved → its bare-assert narrowing
  never fired. Gated the leaf on genuine export (`name in getModuleNamedExports(file)`, memoized;
  flow-only, FP-safe). Barrel-imported `Debug.*` + every barrel guard now resolves. Companion
  (68da80da, dashboard-neutral): the documented "tsc-shaped budget consumption" sub-item — both
  narrowing walkers follow LINEAR pass-through antecedents iteratively (tsc's `getTypeAtFlowNode`
  `while(true)` loop) WITHOUT consuming `NARROW_MAX_DEPTH`; eliminates all depth truncation but
  the compiler profile never hit it (co-located asserts). Perf: self-compile 72 → 92 s (extra
  narrowing; M5). LESSON: verify a "walk hits the cap" claim by instrumenting the truncation, NOT
  by inferring from a file's node count.**
**Round 446 (2026-07-08) — array-literal→variadic-tuple-in-union returns + nested Array<X>-in-Array<Y>
covariant relation + destructured-param method arity: THREE bounded fixes. Dashboard: compiler 188 →
185 (−3), services 399 → 373 (−26), server 623 → 589 (−34), harness 868 → 806 (−62). Suite 9,540 →
9,558 (+18 local across 3 test files, 0 regressions); 3 fix commits (4a97bd52/28abf66a/03acbf0d);
diffed via `--listAll` as strictly by-position removals; bench rows logged for all four profiles.**
- **Baseline @ HEAD (round 445): services 399, compiler 188.** Bucketed the services `--listAll` and
  found the two families round 445's note flagged as NEXT: `DiagnosticOrDiagnosticAndArguments (|
  undefined)` ×16 (array-literal→variadic-tuple-in-union) and `readonly ApplicableRefactorInfo[]` ×15
  (nested-array element relation).
- **Fix 1 (4a97bd52, array-literal→variadic-tuple-in-union returns; services 399 → 379, compiler 188 →
  185, server 623 → 603, harness 868 → 848):** `return [Diagnostics.X, arg, …]` where the target is a
  variadic tuple, or a union/alias containing one (`DiagnosticOrDiagnosticAndArguments =
  DiagnosticMessage | [message: DiagnosticMessage, ...args: (string|number)[]]`). The relation engine
  SKIPS array→tuple and `getTupleType` COLLAPSES the rest slot, so both the engine and the string
  fallback ("array" display) FP'd. `arrayLiteralSatisfiesTupleTarget` AST-matches the array literal
  against the tuple found by `findVariadicTupleInTarget` (walk the target node: tuple / union /
  parenthesized / alias — **alias bodies resolved through the merged `globals`, since an imported
  alias's file-local symbol is only the ImportSpecifier**). `arrayLiteralMatchesVariadicTuple` parses
  the tuple into fixed-prefix / one rest / fixed-suffix and verifies each element against its slot
  (permissive on unresolvable slots → suppression-only). Hooked into `checkReturnAssignability` (direct)
  + `checkConditionalReturnBranches` (`?:`). Also covers FIXED tuples in a union — compiler's
  `specToDiagnostic(): [DiagnosticMessage, string] | undefined` + utilities.ts's `isDirectory ? [a,b] :
  undefined`. Services TS2322 216 → 196.
- **Fix 2 (28abf66a, nested `Array<X>`-in-`Array<Y>` covariant shortcut; services 379 → 373, server 603
  → 597, harness 848 → 842, compiler unchanged):** `{ actions: ActionInfo[] }[]` vs `readonly
  ApplicableRefactorInfo[]` FP'd TS2322. Root cause (found by minimal-repro bisection): the OUTER Array
  pushes `globalArrayType.id` on the comparison stack, so when the INNER `Array<ActionInfo>` is
  compared, the same target id counts as an `isReentry` → the covariant element shortcut is deferred to
  STRUCTURAL comparison of the two `Array` INTERFACES → `concat`'s contravariant element param
  `ConcatArray<T>` spuriously fails (no per-TP variance info). Array/ReadonlyArray are covariant, so they
  must ALWAYS use the element shortcut (`A ⊄ B ⇒ Array<A> ⊄ Array<B>`; termination via
  `relationComparisonStack` + `isDeeplyNested`). **Gated to TP-FREE target args** — an unbound-TP target
  (`flatten<T>(…: T[][])`) is an M3.1 inference gap the trivial structural pass currently MASKS; the
  first cut without the gate turned it into a +1 compiler FP (program.ts `flatten(allDiagnostics)`
  `readonly Diagnostic[] ⊄ T[]`). Core relation-engine change; corpus-clean (suite 9,548 → 9,553).
  Services TS2322 196 → 190.
- **Fix 3 (03acbf0d, destructured-param method arity; harness 842 → 806, server 597 → 589, compiler /
  services unchanged):** a method with a binding-pattern param (`goToRangeStart({ fileName, pos }:
  Range)`) FP'd TS2554 "Expected 1-0 arguments, but got 1." on a correctly-argumented property-access
  call. A destructured param produces NO Symbol, so the built Signature DROPS it — `sig.parameters` is
  empty (maxParams 0) while `minArgumentCount` stays 1, an impossible range that reads any 1-arg call as
  "too many". `checkTs2554ForPropertyAccessCall` recovers the true arity from the DECLARATION's parameter
  list via `paramInfo` (which counts binding-pattern params + handles rest/optional). Harness TS2554 37
  → 1 (the residual mapCode.ts:103 is pre-existing/unrelated); the harness is the test-infrastructure
  profile with many `fourslashImpl` destructured-param methods — the smaller profiles have few such
  property-access calls, hence compiler/services unchanged. Pure TS2554 removal, no other code touched.
- **NEXT (services @ 373):** the residual `ApplicableRefactorInfo[]` ×10 — a SEPARATE bug: their
  `actions` property resolves to a stray `U[]` (an unbound type parameter — no `U` in the file), a
  type-param-scope pollution / `getPropertyTypeForRelation` gap (my TP-gate correctly avoids making it an
  FP, so they stay as-is); the `X | RefactorErrorInfo | undefined` refactor-info family (object-literal /
  `&&`-object / `{error}`-union-source vs union-with-object-member — fragmented M3 object-vs-union
  relation, not conflation since the Info interfaces are single-file); the `DiagnosticOrDiagnosticAndArguments`
  residual is a TS2339 (`messageAndArgs[0]` indexing the union — a different code).

**Round 445 (2026-07-08) — TS2416/TS2430 property-override variance families + the cross-file
interface-merge conflation (Blocker #3) + spread-of-any object returns + the module-var-leak TS2322
extension: FIVE bounded fixes, all suppression-only. Dashboard: compiler 190 → 188 (−2), services
439 → 399 (−40), server 669 → 623 (−46), harness 919 → 868 (−51). Suite 9,523 → 9,540 (+17 local
across 5 test files, 0 regressions); 5 fix commits (76b7f2cc/a81d6300/c31f3577/e93fc974/6db81b97);
services diffed via `--listAll` as strictly by-position removals.**
- **Baseline @ HEAD (round 444): services 439, compiler 190.** Bucketed the services `--listAll`
  and found TS2416×11 + TS2430×3 (bounded override-variance families) and the `Info | undefined`
  TS2322×11 (the biggest single conflation family). The `readonly ApplicableRefactorInfo[]` ×15 and
  `DiagnosticOrDiagnosticAndArguments (| undefined)` ×16 stay deep-M3 (object-literal-array vs
  interface-array / array→tuple-union relation) — deferred.
- **Fix 1 (76b7f2cc, TS2416 override 11 → 0; compiler 190→189, services 439→428, server 669→656,
  harness 919→904): three class-property-override FP families from services.ts's NodeObject /
  TokenOrIdentifierObject / SourceFileObject implementors.** (A) An OPTIONAL base member `p?: T` has
  effective type `T | undefined`; a derived `p: T | undefined` override is legal — the raw base
  declared type dropped the optional `| undefined`, so widen it for the relation via
  `widenOptionalTargetPropType` (source-nullish gated → a non-nullish override still compares against
  the bare base). (B) A CONSTRAINED-type-parameter override (`kind: TKind` where `TKind extends
  SyntaxKind`, base `kind: SyntaxKind`) is valid via the constraint — per-site constraint bail (no
  general TypeParam-source relation rule). (C) tsc compares METHOD signatures with BIVARIANT params
  (`getWidth(sf?: SourceFile)` vs base `getWidth(sf?: SourceFileLike)`) — per-site
  `methodSignaturesBivariantlyRelated` retry (adds a defaulted `bivariantParams` flag to
  `signatureRelatedTo`, no threading elsewhere).
- **Fix 2 (a81d6300, TS2430 interface-extends 3 → 0; services 428→425): two FP families.** (A) The
  optional-base widen applied to the interface-extends check (`ValidParameterDeclaration extends
  ParameterDeclaration { modifiers: undefined }` — `undefined` assignable to the optional base's
  `T | undefined`). (B) A derived METHOD implementing a base function-typed DATA property
  (`EmitHost.getCanonicalFileName(fileName): string` implementing
  `SourceFileMayBeEmittedHost.getCanonicalFileName: GetCanonicalFileName`) was compared as the
  method's RETURN type (`string`) vs the base property's full function type — `getMemberNameAndType`
  returns a method's return type. Skip the simple property comparison when the derived member is a
  method.
- **Fix 3 (c31f3577, the interface-merge conflation; compiler 189→188, services 425→409 −16, server
  656→637, harness 904→884): a name X declared as `interface X` in ≥2 DISTINCT MODULE files merges
  via `mergeSymbolTable` into one polluted `globals[X]`, even though each module's interface is
  module-scoped.** tsc's codefixes each declare a private `interface Info`, so `getInfo(): Info |
  undefined` returning `{ importNode, name, moduleSpecifier }` (matching the FILE-LOCAL Info) looked
  "missing properties" against the merged union. Built `conflatedInterfaceFiles` (name → module files
  declaring `interface X`, for X in ≥2 files); `checkReturnAssignability` bails a returned object
  literal whose target is (the sole non-nullish member of) such a conflated X when THIS file declares
  its own `interface X` AND the object satisfies the file-local X (checked AST-side — the merged
  symbol's `declarations` list is polluted). Runs AFTER the per-property drills (genuine inner-key
  mismatch still fires), BEFORE the coarse missing-property/relation paths. Conservative for
  heritage-bearing interfaces / spread object literals. `Info | undefined` TS2322 11 → 1 (the residual
  fixExpectedComma.ts `{ node }` doesn't satisfy its file-local Info).
- **Fix 4 (e93fc974, spread-of-any object returns; services 409→404 −5, server 637→628, harness 884→873):
  a returned `{ ...anyExpr, ... }`.** tsc types an object literal that spreads an `any`/unresolved value as
  `any` (the spread poisons the whole object), so it cannot be "missing" required target properties.
  `getFileAndTextSpanFromNode` (no return annotation) returns an object literal → our
  `inferReturnTypeFromBody` has no object-literal branch → the call resolves to `any` → the spread
  `...getFileAndTextSpanFromNode(node)` looked to provide nothing → findAllReferences.ts's 5 returned
  objects FP'd "missing sourceFile, textSpan". `checkReturnAssignability` bails when the returned object
  literal has an any/error-typed spread (after the per-property drills, so a genuine explicit-prop mismatch
  still fires). Root fix is `inferReturnTypeFromBody`'s object-literal branch (documented suite-wide blast
  radius — deferred); this suppression is FP-safe (spread-of-any is genuinely `any` in tsc).
- **Fix 5 (6db81b97, module-var-leak TS2322 extension; services 404→399 −5, server 628→623, harness
  873→868): a `return <leakedVar>` / `<ident> = <leakedVar>`.** Round 442's `moduleFileLocalVarNames`
  bail (a top-level `let`/`var`/`const` in a MODULE file leaks into `globals` and shadows every OTHER
  file's same-named block/destructured local, unbound per B83.5) covered TS2339/TS2345. A block/
  destructured `parent` (whose initializer our checker can't infer locally — a destructuring or `&&`)
  leaks to navigationBar.ts's `let parent: NavigationBarNode`, so `return parent` (inferFromUsage.ts) /
  `lastParent = parent` (checker.ts) FP'd TS2322. `checkReturnAssignability` bails a returned
  bare-identifier leaked var; `checkAssignmentExpression` bails an `<ident> = <leaked ident>` (gated to
  a simple Identifier target). Both skip UNLESS it IS this file's own top-level binding. Compiler
  unchanged (navigationBar.ts is not in the compiler-only program). Local test confirms the destructured
  leak FPs without the fix (both `return parent` and `lastParent = parent`) and is clean with it.
- **NEXT (services @ 399, all deeper):** the union-of-2-interfaces conflation (`ExportInfo |
  RefactorErrorInfo | undefined` ×3 — the single-interface gate needs a union-member extension AND the
  `||`-nested object literal path), the two deep-M3 relation families `readonly ApplicableRefactorInfo[]`
  ×15 (object-literal-array vs interface-array with union/`.concat` element types) /
  `DiagnosticOrDiagnosticAndArguments` ×16 (array→tuple-union relation, plus a duplicate-chain-line
  display bug + an `'array'` fallback display). Extend the conflation / spread bails to var-decl/argument
  positions only when a non-return FP surfaces (none observed this round).


**Round 444 (2026-07-08) — cross-file heritage / `this`-guard receiver narrowing / alias-own-file
conflation / module-var-leak property chain (Blocker #3): FOUR bounded fixes, all suppression-only.
Compiler profile UNCHANGED (190), but they GENERALIZE strongly across the big profiles: services
498 → 439 (−59), server 733 → 669 (−64), harness 989 → 919 (−70). Suite 9,512 → 9,523 (+11 local
across 3 test files, 0 regressions); 4 fix commits (19e282f0/59868e67/796d263f/bd0d8eba); services
diffed via `--listAll` as strictly by-position removals (heritage 22 removed / 1 unmasked;
this-predicate 17/0; conflation 12/0; NavNode-chain 9/0). Bench rows recorded.**
- **Baseline @ HEAD (round 443): services 498, compiler 190.** The compiler profile is mined out for
  clean bounded veins; bucketed the SERVICES `--listAll` TS2339×85: `Type`×20 / `Info`×12 /
  `RefactorContext`×9 / `NavigationBarNode`×9 / `ExportInfoMap`×8 / `CodeFixContextBase`×8 / `Symbol`×5.
- **Fix 1 (19e282f0, −21 services, TS2339): namespace-import-aliased heritage base.** An interface
  whose `extends` base is `NS.Base` where `NS` is a MODULE namespace-import alias
  (`RefactorContext`/`CodeFixContextBase extends textChanges.TextChangesContext`, with
  `import * as textChanges` / a `_namespaces` `export * as` barrel) did not inherit the base's members.
  `resolveAlias` does NOT resolve an `import * as NS` / `export * as NS` namespace alias to a module with
  `.exports` (the alias's declaration is the NamespaceImport node, which none of resolveAlias's branches
  handle), so `resolveHeritageBaseSymbol`'s exports lookup returned null → base = errorType → inherited
  `.host` FP'd TS2339 ×17. Fix: `getTypeFromBaseTypeExpression` falls back to the merged-global
  LAST-SEGMENT name (`globals[baseExpr.name.text]`) — exactly what `getTypeFromTypeReference` does for
  the same qualified shape in ANNOTATION position (which is why a direct `ctx: textChanges.TextChangesContext`
  annotation resolved while the heritage base did not). By-position diff 22 removed / 1 unmasked
  (pasteEdits.ts:111 — a pre-existing `originalProgram!` NonNull-strip gap in an object-literal value,
  surfaced because CodeFixContextBase now resolves its base; needs exact nested-flow context, does not
  reproduce in isolation → left as residual).
- **Fix 2 (59868e67, −17 services, TS2339 — the `.types`-on-`Type` bucket 20 → 2): a `this is X` guard
  METHOD narrows the call RECEIVER.** The tsc `Type`/`Symbol`/`Node` public-API guards
  (`isUnion(): this is UnionType`, `isIntersection()`, `isLiteral()`, …, added to the interfaces by a
  `declare module` augmentation) narrow the method-call receiver, not an argument. `narrowByCallPredicate`
  bailed twice: (1) the `this` subject of a `this is X` predicate parses as a **ThisType** node (not an
  Identifier), so `predicateParamName` extraction returned null; (2) even with the name, the narrowed
  reference is the receiver, so the arg-path fast-path / paramIdx logic never matched. Fix: recognise a
  ThisType subject as `"this"`, compute the method-call receiver path up front (participating in the P0
  fast-path), and narrow the receiver via the existing single-type/union logic. **FP-safe gate (caught by
  the corpus suite): a `this is X` method guard narrows only a NON-UNION receiver** — tsc narrows a
  union-receiver method-guard only if EVERY constituent has a matching predicate (typePredicatesInUnion3:
  `Type1 | Type2` where `Type2.predicate(): boolean` is not a guard), and resolveFlowCalleeDecl found only
  one member's method, so a union bails (suppression-only → a bail is a harmless false-negative).
- **Fix 3 (796d263f, −12 services, TS2339): the alias's-own-file complement of round 443.** In the file
  that DECLARES `type Info = TypeLikeDeclarationInfo | EnumInfo | …` (fixAddMissingMember.ts), the receiver
  `info` resolves — via the merged last-wins pick (Interface+TypeAlias don't merge) — to a SIBLING codefix
  file's unrelated `interface Info` instead of the local union, so `info.kind`/`info.parentDeclaration`/
  `info.token` (union members reachable after a `.kind` discriminant narrowing our conflated receiver can't
  model) FP'd. `checkMemberAccessMissing` bails when the receiver's conflated name is a `type X` in THIS
  file AND the property exists on SOME constituent of the file-local union. **The union is resolved from the
  TypeAliasDeclaration's BODY node directly (`getTypeFromTypeNode`), NOT via `getDeclaredTypeOfSymbol` on the
  file-local symbol — that symbol's `declarations` list is polluted by `mergeSymbolTable` with the sibling
  `interface X`es, so getDeclaredTypeOfSymbol resolves an Interface, not the Union** (found by an instrumented
  probe: `localInfo=[TypeAliasDeclaration, InterfaceDeclaration, InterfaceDeclaration] laType=Interface`).
  Handles both a single `interface X` receiver and an `X | undefined` union receiver (sole non-nullish member).
- **Fix 4 (bd0d8eba, −9 services, TS2339 — the NavigationBarNode ×9 residual): the module-var-leak root
  behind a PROPERTY-ACCESS chain.** Round 442's `moduleFileLocalVarNames` bail covered a bare-Identifier
  receiver, but `parent.parent.kind` (checker.ts) leaks navigationBar.ts's `let parent: NavigationBarNode`
  through the bare `parent` — `parent.parent` resolves to NavigationBarNode (it has a `.parent`) so `.kind`
  FP'd on the CHAIN. `checkMemberAccessMissing` walks the receiver chain to its root Identifier and bails
  when the root is a leaked module var (and not the current file's own binding). FP-safe: the whole chain is
  resolved through the wrong leaked type; a CALL in the chain breaks the property-access walk so only pure
  chains bail. Generalizes: services/server/harness each −9.
- **INVESTIGATED & DEFERRED: the type-RESOLUTION fix (prefer the file-local `type X` in `getTypeFromTypeReference`)
  fails — `currentCheckFileName` is NULL at the lazy `getInfo`-return-type resolution (the type is resolved +
  cached before any file-check context sets it). The emission-site bail (fix 3) is the robust choice.**
- **META / next-agent (services @ 439):** the clean bounded services veins are now largely mined; the
  residual TS2339×30 is deep — **`Symbol.links`×5 is NOT augmentation-merge** (INVESTIGATED round 444: `links`
  is on `TransientSymbol`, narrowed by an `isTransientSymbol(symbol) && symbol.links…` `&&`-chain — the
  narrowing WORKS in isolation but the real symbolDisplay.ts FP is a deep gap, likely the huge-file flow-walk
  depth cap or a `TransientSymbol`/`Symbol`-lib conflation; needs an instrumented probe). **`ExportInfoMap`×8 is
  a WRONG-TYPE issue** (`exportInfo: SymbolExportInfo | FutureSymbolExportInfo` — a `let x: T = …; ({ x } = result)`
  destructuring-reassignment re-types `exportInfo` to `ExportInfoMap`; an inference/destructuring-target-type gap).
  The bulk is now deep-M3 TS2322×236 (fragmented relation gaps). The genuine AUGMENTATION-MEMBER merge (a
  `declare module { interface Symbol { links } }` adding members our binder doesn't merge into the base) is
  PARTIALLY modeled already — `mergeModuleAugmentations` merges the augmentation's interface DECLARATIONS into the
  base symbol's `declarations` list (round-444 repro: `Type.isUnion()` added by augmentation RESOLVES cross-file),
  so it is no longer the dominant residual it was thought to be.

**Round 443 (2026-07-08) — module-augmentation family + the module-file-local TYPE-alias leak
(Blocker #3): FOUR bounded fixes, all suppression-only. Compiler profile UNCHANGED (190 — no FPs in
these families there), but the fixes GENERALIZE hugely across the big profiles: services 591 → 498
(−93), server 887 → 733 (−154), harness 1,118 → 989 (−129). Suite 9,504 → 9,512 (+8 local across 3
test files, 0 regressions); 4 fix commits. Services diffed via `--listAll` as strictly by-position
removals: TS2664 10→0, TS2564 17→0, TS2304 24→2 (2 remaining = NodeJS `global`, env-legit offline),
TS2339 129→85 (SourceFileLike 44→0).**
- **Baseline @ HEAD (round 442): services 591.** Bucketed the `--listAll`: the clean bounded veins
  were all in `services/types.ts`'s `declare module "../compiler/types.js"` augmentations —
  (a) TS2664 "Invalid module name in augmentation ... cannot be found." ×10, (b) TS2304 on compiler
  type names inside augmentation bodies ×22, and (c) TS2564 on `| undefined` properties ×17.
- **Fix 1 (TS2664, `.js`-aware augmentation-target resolution): a `declare module "../compiler/types.js"`
  augmentation resolves `.js` → the `.ts` sibling.** The TS2664 check went through
  `resolveModuleSpecifierRelative`, which deliberately does NOT strip the ESM `.js` extension (the
  TS2459 gotcha) → the augmentation target never resolved → FP. Added
  `resolveModuleSpecifierRelativeJsAware` (strip-and-retry for `.js`/`.jsx`/`.mjs`/`.cjs` — purely
  additive, only makes MORE specifiers resolve, so only ever SUPPRESSES a false 'cannot be found');
  consolidates the inline strip-and-retry already at the TS2694/TS2305/TS2307 augmentation sites.
- **Fix 2 (TS2564, `| undefined` property exemption): a class property whose declared type INCLUDES
  `undefined` needs no definite assignment.** tsc's strictPropertyInitialization exempts it
  (`getFalsyFlags(type) & TypeFlags.Undefined`); `checkClassPropertyInit` skipped
  initializer/optional/!/declare/static/abstract/any but NOT `| undefined`, so services.ts's
  `SourceFileObject` (`nameTable: Map<...> | undefined` + siblings) FP-fired. Reuses the existing
  `typeIncludesUndefined` helper (also used by the TS2454 definite-assignment path). Suppression-only.
- **Fix 3 (TS2304, augmentation-body scope): a `declare module "X" { ... }` body sees the AUGMENTED
  module's exports by bare name.** `buildNamespaceScope` had no `StringLiteralNode` branch, so inside
  the augmentation body only the augmenting file's own scope was visible; tsc checks the body in the
  augmented module's context (Node/NodeArray/SymbolFlags/TypeChecker/__String — compiler/types.ts
  exports NOT imported into services/types.ts). Added the branch: resolve the specifier (via the
  `.js`-aware resolver from fix 1) and add the target's `moduleNamedExportsOf` to the namespace scope's
  `names` + `typeNames`. Purely additive (bare/unresolvable specifier is a no-op).
- **INVESTIGATED & REVERTED (dashboard no-op, blocked on B83.5): TS7006 array-element contextual typing.**
  `checkImplicitAnyInExpr`'s `ArrayLiteralExpression` case propagated only the `contextuallyTyped` flag,
  not the element `Type`, so an OBJECT-LITERAL element of `Priority[]` got no contextual type and its
  property arrows FP'd TS7006 (inferFromUsage.ts `const priorities: Priority[] = [{ high: t => …, low:
  t => … }]`). Wired `arrayElementTypeOf(contextualType, i)` into object-literal elements (3 local
  tests passed). BUT it reduced ZERO dashboard FPs: `interface Priority` is NESTED inside
  `function inferTypeFromReferences` → UNBOUND per B83.5 → `Priority[]` resolves to any → no element
  type to propagate. The array-element fix is a correct M3.2 enabler but its dashboard payoff is gated
  on nested-type resolution (B83.5). Reverted to avoid landing a dashboard no-op; land it TOGETHER with
  the nested-interface-resolution companion when a session takes B83.5 for annotation positions.
- **Fix 4 (TS2339 on `SourceFileLike` ×44 → 0, Blocker #3 — the module-file-local TYPE-alias leak):
  a module-file-local `type X = A | B` alias leaks into `globals` and shadows the global `interface X`
  in OTHER files.** ROOT CAUSE pinned with an instrumented probe (the minimal/barrel repros do NOT
  reproduce — the leak is file-order/pollution-dependent, a whole-program phenomenon): the receiver
  `sourceFile: SourceFileLike` resolves to `Type.Union` `SourceFile | AmbientModuleDeclaration`
  (displayed via the alias map as 'SourceFileLike'), because services/importTracker.ts's NON-exported
  `type SourceFileLike = SourceFile | AmbientModuleDeclaration` won the last-wins merge over
  compiler/types.ts's `interface SourceFileLike` (Interface+TypeAlias don't merge). `AmbientModuleDeclaration`
  has no `.text` → the union member-access FP'd (both base `text`/`lineMap` AND augmentation-added
  `getLineAndCharacterOfPosition`). Built `conflatedTypeAliasFiles` (name X → files declaring `type X`,
  for X also declared as `interface X`); `checkMemberAccessMissing` bails a UNION-receiver TS2339 when
  the receiver's display (nullish-stripped, for `X | undefined` optional receivers) is a conflated name
  AND the current file is NOT the alias's own file. TYPE-space analog of round 442's `moduleFileLocalVarNames`.
  FP-safe: in every other file tsc resolves X to the INTERFACE (all members present), so it never errors;
  the alias's own file still fires. services/server −44 each, harness −3 (harness test files resolve
  `SourceFileLike` differently). Local tests pin the FP firewall only (the positive case is whole-program-only).
- **NEXT (remaining services buckets @ 498, all deep): TS2322×235 / TS2339×85 / TS2345×54 (M3 relation +
  residual Blocker #3), TS2416×11 (override, diverse), TS2353×10 (union/inherited excess), TS2740×9 /
  TS2739×8 (missing-property, deep relation), TS7006×8 (contextual typing — the inferFromUsage `Priority[]`
  case needs B83.5 nested-interface resolution + the reverted array-element enabler). TS2339×85 residual
  buckets: `Type`×20, `Info`×12, `RefactorContext`/`CodeFixContextBase`/`ExportInfoMap` — likely more
  module-file-local-type-alias/interface conflations (same Blocker #3 family — bucket + probe each).**

**Round 442 (2026-07-08) — TypeParam-constraint arg + overloaded-callback arity + the
module-file-local-variable/type global-leak (Blocker #3): FIVE bounded fixes. Compiler profile
197 → 190 (−7), and the leak fixes GENERALIZE MASSIVELY across the big profiles:
services 1,030 → 591 (−439), server 1,314 → 887 (−427), harness 1,603 → 1,118 (−485). Suite
9,492 → 9,504 (+12 local across 4 test files, 0 regressions); 5 fix commits. Compiler diffed
via the `--listAll` `comm` loop as strictly by-position removals.**
- **Baseline @ HEAD (round 441): compiler 197.** Bucketed the `--listAll`: the clean bounded veins
  were (a) `K`/`T` (constrained TypeParam) → `string` param ×3, (b) the overloaded-callback arity ×2,
  and — found only by bucketing the SERVICES profile — (c) TS2339 on `NavigationBarNode` ×279 (!).
- **Fix 1 (TypeParam-constraint arg, M3.1, −4 compiler TS2345): `checkArgumentsAgainstSignature`
  bails when a bare-TypeParam arg's declared constraint is assignable to a concrete primitive param.**
  tsc's rule (a type param relates to X iff its constraint does). The relation engine deliberately
  has NO general `source is Type.TypeParam && target !is Type.TypeParam` branch (39+ cycle-regression
  gate — CLAUDE.md), so this is a per-site bail-out mirroring round 441's `checkConstraintsForTypeArgs`.
  Uses the RAW constraint (NOT `getApparentType`, which wraps a bare `string` constraint into the
  String interface — not assignable to primitive `string`). Gated to a constrained TP (an unconstrained
  `T` still fires). `readPackageJsonField<K extends keyof PackageJson>` → `hasProperty(json, fieldName)`;
  `changeExtension<T extends string | Path>` → `changeAnyExtension`; + the `IncludeTypeSpaceImports`
  TP-vs-boolean case (5 negative/positive local tests).
- **Fix 2 (overloaded-callback arity, M3.1, −2 compiler TS2345): `allowArityMismatch` uses the MIN
  minArgumentCount across an overloaded arg's call sigs.** An overloaded function passed as a callback
  is arity-incompatible with a single-sig target only when EVERY overload needs more args than the
  target provides (tsc picks a matching overload). `tryCast(x, isAssignmentExpression)` — 1st overload
  2 required, 2nd's 2nd param OPTIONAL (minArgumentCount 1) — no longer reports 'too few arguments'
  against the 1-param `(value: TIn) => value is TOut` target (es2015.ts decorator IIFE ×2). Single-sig
  args unaffected (minOf == first).
- **Fixes 3+4 (module-file-local var global-leak, Blocker #3 — THE big one): a top-level `let/var/const`
  in a MODULE file leaks into `globals` and shadows every OTHER file's local of the same name.**
  ROOT CAUSE (found by bucketing services TS2339×404 → `NavigationBarNode`×279): navigationBar.ts's
  module-level `let parent: NavigationBarNode` merged into `globals`, so every other file's local
  `parent` — a block-scoped const (`const parent = errorLocation.parent` in checker.ts) or a nested-fn
  param (`function maybeEmitExpression(next, parent: BinaryExpression)` in emitter.ts), both invisible
  to our scope machinery per B83.5 — resolved to `NavigationBarNode` → FP TS2339 on `.left`/`.pos`/
  `.operatorToken` and FP TS2345 when passed as an arg. Built `moduleFileLocalVarNames` (after the merge)
  = names EXCLUSIVELY module-file-local variables MINUS any competing global meaning (script-file
  top-level decl, or a function/class/interface/enum/type-alias/namespace of that name anywhere), so a
  name in the set can only be a cross-file conflation. Bail `checkMemberAccessMissing` (TS2339) AND
  `checkArgumentsAgainstSignature`'s per-arg loop (TS2345) for such a bare-Identifier receiver/arg
  UNLESS `currentFileLocals?.get(name) != null` (it IS this file's own module var — keeps firing).
  FP-safe by construction (a cross-file bare module var is TS2304 in real tsc, never TS2339/TS2345).
  services TS2339 404 → 129 (NavNode 279 → 9), TS2345 197 → 44 (NavNode-as-arg 153 → 10); compiler −1
  (utilities.ts:6325 `getIndentString(indent)` — the round-440-flagged 'wrong-callee single', actually
  a module-var leak). 3 local tests (cross-file positive + same-file negative control + arg-check positive).
- **Fix 5 (TYPE-position analog of the leak, Blocker #3, −13 services TS2314): a file's OWN
  non-generic Class/Interface/TypeAlias declaration shadows a cross-file same-named GENERIC type.**
  `getTypeParamInfo` iterates ALL files' locals and returns the first generic match, so
  convertToAsyncFunction.ts's non-generic `interface Transformer` lost to types.ts's `type
  Transformer<T>` → FP TS2314 "requires 1 type argument" (×14). `checkTypeArgCount` bails via a new
  AST-based `fileDeclaresNonGenericType(fileName, name)` (scans the file's own top-level statements —
  pollution-proof, since the merged `globals`/first-file symbol carries BOTH declarations); a same-file
  GENERIC decl returns false so its real arity still applies. Strictly 14 TS2314 removed / 1 TS2322
  added (convertToAsyncFunction.ts:166 — a pre-existing object-literal-vs-local-interface M3 relation
  gap unmasked once `Transformer` correctly resolves to the local interface). NOTE the FP requires the
  file to have a local decl (so `scope.has(name)` is true and the arity check runs at all) — a bare
  cross-file generic with NO local shadow is scope-gated out and never fired TS2314 to begin with.
- **LESSON / MEASURED DEAD-END: a broader `getTypeOfIdentifier` variant (return anyType for these names
  in the globals fallback) was tried and REVERTED — it took services TS2345 197 → 44 too but broke
  cross-file initializer inference / redeclare / `.d.ts` emit → 5 corpus regressions (es6Import*,
  typePredicateInLoop, checkJsdoc*, structurally*Imports*). Identifier typing feeds emit/redeclare paths
  that need the real cross-file type; only the two DIAGNOSTIC emission sites are safe to suppress. The
  suite gate caught it — the property-access + arg-check bails are the safe subset.**
- **META / next-agent:** the module-var-leak fix is the highest-yield single fix in many rounds
  (−426/−427/−485 on services/server/harness). The remaining big-profile buckets: services TS2322×220
  / TS2345×44 / TS2339×120 (SourceFileLike×44, Type×20 — deeper narrowing on big AST-node unions,
  the M1.4 territory), TS2314 `Generic type 'Transformer' requires 1 type argument` ×14 (a generic-arity
  gap — `Transformer<T>` used bare where tsc has a default), TS2564×17 / TS2664×10 (fresh bounded
  buckets not yet triaged). The compiler profile (190) is genuinely mined out for CLEAN bounded veins —
  bucket the SERVICES/SERVER profile to find the next generalizable family.

**Round 441 (2026-07-08) — TS2344 constraint-chain + assertNever exhaustiveness burn-down:
THREE bounded fixes take the compiler profile 205 → 197 (−8). Suite 9,482 → 9,492 (+10 local across
2 test files, 0 regressions); 3 fix commits. All diffed via the `--listAll` `comm` loop as
strictly by-position removals (0 added). Fixes 2+3 together clear 5 of the 8 assertNever `→never`
FPs.**
- **Fix 3 (checker, −3, TS2345): the arg-check narrows a NON-union arg to a `never` param when the
  walk proves `never`.** `checkArgumentsAgainstSignature` (~124781) previously EXCLUDED the
  never-param case for non-union args (the exclusion was correct only BEFORE fix 2, when a partial
  refinement would manufacture an FP). Now: narrow the arg and USE the result ONLY when
  `n === neverType` (a partial union stays `ctxApplied` → the same TS2345 the pre-narrow path
  emitted → no manufactured FP). This makes the `Debug.type<SomeUnion>(node)` / `asType<T>(node)`
  assert (`asserts value is T`, explicit type arg) end-to-end: `narrowByAssertCall` re-types the
  non-union `node` to the union (round-424b explicit-type-arg bind + the non-relating-object →
  return-target branch), the exhaustive switch narrows it to `never`, and this gate consumes it.
  Cleared debug.ts:852, utilities.ts:2270/12050 (the `isDeclarationWithTypeParameterChildren`
  family). **DIAGNOSIS UPDATE (supersedes the round-441 "fails top-level too" note below): the
  assert-to-union narrowing WAS working in the walk all along — the block was purely the arg-check
  CONSUMER gate; the 3 residual `→never` (utilities.ts:12082, programDiagnostics.ts:346,
  diagnostics.ts:702) now need the target union to resolve with readable `.kind` members
  (`HasInferredType`-style unions of big AST-node interfaces) — a deeper resolution gap, not a
  narrowing/consumer gap.**
- **Fix 2 (checker, −2, TS2345): exhaustive-switch `default` narrows the discriminant to `never`.**
  `narrowBySwitchClause`'s round-425 default-clause negative-narrowing branch already dropped the
  case-covered members but returned `null` (= "no narrowing") when the filtered set was EMPTY
  (i.e. every member covered = exhaustive) — it now returns `neverType`. That is what makes
  `default: return assertNever(x)` / `assertType<never>(x)` type-check: the `never`-param arg-check
  reads the narrowed `never` via `getNarrowedTypeForReference` (`never <: never` passes). BOTH
  filter paths only DROP a member with a readable literal/enum `.kind` matching a case (a wide-kind
  member OR one without a readable discriminant is KEPT), so `[]` is a genuine exhaustiveness proof
  and a NON-exhaustive switch narrows to the surviving members (the never-param call still errors
  with the uncovered member — verified by negative control). Cleared the 2 compiler `→never` FPs
  with resolvable discriminated-union subjects (programDiagnostics.ts:419 `RootFile | LibFile | …`,
  tsbuildPublic.ts:2482 `Unbuildable | UpToDate | …`). The other 6 compiler `→never` FPs
  (utilities/debug/programDiagnostics/diagnostics) have `Node`/`Expression` BASE-INTERFACE subjects
  — tsc narrows them via a preceding `Debug.type<SomeUnion>(node)` assert (`asserts value is T`,
  explicit type arg) that casts `node` to a union FIRST, then the switch exhausts it. **DIAGNOSED
  (round 441, do not re-chase without instrumentation): the assert-to-union narrowing of an
  OBJECT-typed reference does NOT fire — even for a TOP-LEVEL `declare function asType<T>(value:
  unknown): asserts value is T; asType<Shape>(node)` (no `Debug` namespace), `node: {kind:"a"|"b"}`
  stays its declared type in the switch default, so my exhaustive-never fix has no union to
  exhaust.** `narrowByAssertCall`'s code path (Checker.kt ~94150-94167) DOES bind the explicit type
  arg (round-424b) and return the target for a non-relating object source (`checkTypeRelatedTo(t,
  target)` false → return target), so the gap is UPSTREAM: `narrowByAssertCall` is not being
  REACHED / its result not consumed for this shape — likely the round-413 fast-forward loop's
  `flowCallMightNarrow`/`flowCalleeMayHaveAssertEffects` gate skipping the FlowCall, or the walk not
  reaching it. Needs a marker-diagnostic trace at the FlowCall handler. High leverage (the
  `Debug.type<T>` + exhaustive-switch idiom is pervasive in tsc source) but a real M3.4 slice.
- **Fix 1 (checker, −3, TS2344): constraint-chain bail-outs (detail below).**
- **Generalization (all THREE fixes, `--no-emit` `--listAll`, vs the round-440 END baseline):
  services 1,037 → 1,030 (−7), server 1,321 → 1,314 (−7), harness 1,610 → 1,603 (−7).** Consistent
  −7 to −8 across profiles, no regressions. The assertNever `→never` cases on the larger profiles
  are gated by the same union-`.kind`-resolution requirement, so only the resolvable ones clear
  there too.
- **Baseline @ HEAD (round 440): 205 FPs.** Bucketed the full `--listAll`: TS2322×100 (deep
  M3 relation, fragmented — largest sub-shape only 3), TS2591×43 + TS2304 `global`×2 + TS2584
  `console`×1 env-legit (offline, no @types/node — NOT compiler FPs), TS2345×28 (fragmented:
  assertNever `→never` exhaustiveness ×8, wrong-callee singles, `number|undefined`→number
  arithmetic-flow), TS2344×3, small buckets. The clean bounded family was TS2344×3.
- **Fix (checker, −3): `checkConstraintsForTypeArgs` + `checkTpListDefaults` (default validation)
  gained two constraint-chain bail-outs.** (a) A bare TypeParam arg whose `.constraint` resolves
  to `anyType` satisfies EVERY target constraint (a literal `extends any` OR — our gap — an
  enum-member union constraint `JSDocSyntaxKind = SyntaxKind.A | …` that collapses to `any`: each
  member type resolves to `any` so the union collapses). A DIRECT `Token<JSDocSyntaxKind>` arg is
  already skipped by the `argType === anyType` guard, so a TypeParam arg (`Token<TKind>` where
  `TKind extends JSDocSyntaxKind`, Token's param `extends SyntaxKind`) must be too — parser.ts
  `parseOptionalTokenJSDoc`/`parseExpectedTokenJSDoc` ×2. (b) A UNION arg/default satisfies when
  EVERY member does, incl. a TypeParam member whose own constraint relates — `Visitor<TIn extends
  Node, TOut extends Node | undefined = TIn | undefined>` (`TIn | undefined` vs `Node | undefined`;
  the whole-union relation misses `TIn <: Node | undefined` because we have no
  TypeParam-source-via-constraint relation rule) — types.ts `Visitor` default ×1. FP-safe: every
  union member must genuinely relate (2 negative controls: unrelated union member → TS2344 still
  fires). The relation engine still has NO general `source is Type.TypeParam && target !is
  Type.TypeParam` branch — a broad relation change risks the documented 39+ cycle regressions, so
  the fix stays as per-site bail-outs.
- **META / next-agent residual (197 after all three fixes):** the clean bounded veins on the
  COMPILER profile are now nearly mined out — the residual is genuinely hard. TS2322×100 is deeply
  fragmented (largest sub-shape 3: `TransformerFactory<SourceFile|Bundle>`, `__String | undefined`,
  `Expression`) — deep M3 relation/narrow-DOWN work. The assertNever `→never` TAIL is down to 3
  (fixes 2+3 cleared 5 of 8): the residual need the target union (`HasInferredType`-style: a union
  of big AST-node interfaces reached via `Debug.type<Union>(node)`) to RESOLVE with readable
  `.kind` members — a resolution gap, not narrowing. Lib-completeness
  gaps deferred to M2.3: TS2353 `next` (sourcemap.ts — embedded `interface IterableIterator<T> {}`
  is EMPTY, doesn't `extends Iterator<T>`, so an object literal with `next()` looks excess);
  TS2740 Set set-methods (core.ts). Arithmetic-flow `number|undefined`→number (parser.ts 8911/8974)
  are the round-440-flagged not-reproducible-in-isolation M3.4 slices. Wrong-callee singles
  (utilities:6325 `getIndentString(indent)` → indent resolves to string; moduleSpecifiers:929;
  program:832) are the round-440 C/D cross-file-collision/shadow pattern — each 1 FP, individual
  root-cause. TS2454×4 `resultingToken` is the `while(true)`-break definite-assignment flow gap.

**Round 440 (2026-07-07) — optional-widen / operator-typing / cross-file-callee /
generic-inference burn-down: FIVE bounded fixes take the compiler profile 228 → 205
(−23, −10.1%; TS2345 39 → 28, TS2322 108 → 100, TS2362 4 → 2, TS2365 1 → 0). Suite
9,465 → 9,482 (+17 local across 5 test files, 0 regressions); 5 fix commits (a6155814,
390b5a6a, f812e017, 19d19d08, 67366445). Every step diffed via the `--listAll` loop as
strictly removals except fix B's documented position shift.**
- **Baseline @ HEAD (round 439, 228 FPs).** Reused the materialize-once `--listAll` per-fix
  `comm -13` diff loop (~30 s CLI run per fix).
- **Fix A (a6155814, −4): fresh object-literal OPTIONAL prop accepts `T | undefined` (M3.4).**
  `checkNestedObjLitPropTypes`' per-property LEAF compared the value against the BARE
  declared member type; it now routes the relation target through `widenOptionalTargetPropType`
  (source-nullish gated, exactOptionalPropertyTypes off) — a fresh `sourceIndex: hasSource ? n :
  undefined` (`number | undefined`) passes `Mapping.sourceIndex?: number` (sourcemap.ts
  captureMapping ×4). Display keeps the bare member type. Widen-site count 4 → 5.
- **Fix B (390b5a6a, −3): `combineBinaryTypes` types `a ?? b` as `NonNullable<a> | b` (M3.4).**
  The `??` case unioned the RAW left type; it now strips null/undefined/void from the left
  (pure-nullish left → the right operand only). `verbosityLevel ?? -1` (`number | undefined`)
  → `number`. 3 clean whole-object/property removals (moduleNameResolver:1828,
  moduleSpecifiers:555, typeSerializer:446); ALSO a checker.ts 6647→6640 POSITION SHIFT — the
  per-property `maxExpansionDepth` FP is replaced by a coarse whole-object `NodeBuilderContext`
  relation FP (a pre-existing MASKED deep-M3 gap: NodeBuilderContext extends an interface using
  `Required<Pick<...>>` utility types + Maps; the count on checker.ts is unchanged). Not
  chased — the whole-object relation is a separate M3.1 slice.
- **Fix C (f812e017, −4): getCalleeType consults currentParamBindingNames (M3.1).** A
  function-body destructured-const local (`const { watchFile } = createWatchFactory()`,
  unbound per B83.5) shadows a same-named cross-file function callee. getCalleeType resolved a
  bare-Identifier callee straight through merged `globals` → tsbuildPublic's `function
  watchFile<T>(state: SolutionBuilderState<T>, file: string, ...)`, FP-checking the args
  against ITS params. Now consults the currentParamBindingNames side set (already populated by
  applyCallTypesBodyLocalShadowing) → anyType, mirroring getTypeOfIdentifier (watchPublic
  1053/1165/1199 TS2345 + 643 TS2769).
- **Fix D (19d19d08, −7): getCalleeType prefers a same-file FunctionDeclaration over merged
  globals (Blocker #3 cross-file name collision).** `mergeSymbolTable` pollutes the
  first-processed file's own symbol with every file's same-named decls, so `getBuildInfo` inside
  tsbuildPublic.ts (with its OWN `function getBuildInfo<T>(state, ...)`) picked emitter.ts's
  `getBuildInfo(file: string, ...)` → FP'd `state` against `string` (also createWatchStatusReporter,
  flattenDiagnosticMessageText, classFields). getCalleeType now consults currentFileLocals AFTER
  the enclosing-namespace lookup and before globals — NARROWED to a genuine same-file
  FunctionDeclaration (SymbolFlags.Function, non-Alias): a callee `Date` shadowed by a type-only
  `import { Date }` interface must still resolve to the global `Date` VALUE
  (isolatedModulesShadowGlobalTypeNotValue — an un-gated any-symbol consult regressed 3 corpus
  tests, caught by the suite gate). Namespace-lookup-first keeps a `namespace Parser` call to
  `createSourceFile` picking the namespace-internal one over the file-level export (a first cut
  with file-local BEFORE the namespace lookup FP'd parser.ts:1819 — caught by the `--listAll`
  diff). builder.ts:1686, executeCommandLine 688/727/860/1048, classFields:3359, tsbuildPublic:1531.
- **Fix E (67366445, −5): generic inference binds T=any from an any-typed arg at a return-type
  site (M3.1).** `tryInferSingleTypeParamFromArgs` soft-skips an any-typed arg at a return-type
  site (round 428, so concrete args elsewhere drive inference) — but when a TP's ONLY candidate
  position is an any arg the candidate list ended up empty → inference returned null → the caller
  used the un-inferred bare `T` as the call's return. `Debug.checkDefined<T>(value: T | null |
  undefined): T` called with a destructured-const local `pos` (typed anyType via
  currentParamBindingNames — the round-C mechanism, so this was UNMASKED once pos stopped
  resolving to a cross-file function) returned `T`, FP'ing against `createFileDiagnostic`'s
  `number` param + a downstream `T - pos` arithmetic. Now binds T=any when candidates are empty
  ONLY because of a soft-skipped any arg (per-TP `tpSawAnyArg` flag, return-type site only) —
  tsc-faithful (`id<T>(x:T)` with an any arg infers T=any), strictly suppression-only (an any
  return is assignable to any consumer). The arg-vs-param check site keeps the hard bail.
  programDiagnostics 198/199, checker.ts:25098, utilities.ts:6314, watch.ts:627.
- **GENERALIZATION (full-dashboard bench at the round-440 END state, `--no-emit`, vs the
  round-438 recorded baseline — so the deltas fold in round 439 + round 440): compiler 244 → 205
  (−39), services 1,116 → 1,037 (−79), server 1,401 → 1,321 (−80), harness 1,693 → 1,610 (−83);
  ~6,900 LOC/s, RSS ~1 GB.** The cross-file-callee (C/D) + generic-inference (E) fixes generalize
  strongly — collisions, destructured-factory locals, and any-arg generic calls are pervasive.
- **META / next-agent residual (205):** TS2322×100 (deep M3 — the NodeBuilderContext whole-object
  relation, `__String` cross-file branded-string returns, B526 tuple/brand, `Declaration |
  undefined`/`Node → HasModifiers` narrow-DOWN blocked by incomplete relation-heritage);
  TS2591×43 env-legit (offline, no @types/node); TS2345×28 — NEXT bounded buckets: the
  constraint-chain `TKind extends JSDocSyntaxKind extends SyntaxKind` TS2344 (parser.ts
  2531/2545), the MappingsDecoder excess-of-inherited-generic-base member (TS2353 `next` from
  `extends IterableIterator<Mapping>`), and wrong-callee singles (moduleSpecifiers:929,
  utilities:6325, program:832). TS2339×7.

**Round 439 (2026-07-07) — predicate-overload / arg-narrow-DOWN burn-down: THREE bounded
fixes take the compiler profile 244 → 228 (−16, −6.6%; TS2769 9 → 1). Suite 9,458 → 9,465
(+7 local, 0 regressions); 3 fix commits (4bdb051f, ee43d153, e6f61973). Every step
diffed by-POSITION as strictly removals (fix 1's one exposed regression fixed in the same
commit by the companion NonNull strip).**
- **Baseline @ HEAD (round 438, listall-439.txt): 244 FPs.** Reused the `--listAll`
  per-fix diff loop (materialize once, ~30 s CLI run per fix, `comm -13` on `file:line:col`).
- **Fix 1 (4bdb051f, −8): findAncestor-style predicate-overload RETURN inference (M3.2).**
  A generic overload whose callback param is a type-guard position `(x) => x is T` and
  whose return is built from T (`T | undefined`/`T`/`S[]`) infers T from the actual
  type-guard ARGUMENT's predicate target (`predicateTargetTypeOfGuardExpr`), BEFORE the
  B136 concrete-overload swap. `findAncestor(node.parent, isFunctionLike)` →
  `SignatureDeclaration | undefined` (not the B136 `Node | undefined`). New helpers
  `tryInferPredicateOverloadReturn` + `predicateCallbackParamGuardTpName` (AST-side: read
  the sig's declaration params for a `FunctionType` returning a non-asserts `TypePredicate`
  whose target names a sig TP). A non-guard callback (`=> boolean | "quit"`) yields null →
  B136 still owns it. Cleared utilities.ts getContainingFunction/Declaration/Class/
  OrClassStaticBlock + getJSDocRoot + commandLineParser. **Companion NonNull strip:** the
  inference made `getParseTreeNode(x, isGetOrSetAccessorDeclaration)!` return the CONCRETE
  `AccessorDeclaration | undefined` (was a foreign `T | undefined` suppressed by the round-431
  gate), exposing the documented round-407 NonNull-union non-strip → +1. Fixed in the same
  commit: a `<call>()!` on an all-CONCRETE union return (no un-inferred TP) strips nullish
  via narrowByExcludingNullUndefined. Restricted to a CALL operand + concrete members so
  property-access `.x!` (object-literal-vs-interface gap) and TP-carrying returns
  (generic-inference gap) keep the deferred behavior — net −8, ALSO cleared emitter ×2.
- **Fix 2 (ee43d153, −5): overloadNarrowedArgType narrows a NON-union arg DOWN.** A bare
  Identifier/PropertyAccess whose non-union declared type is guard-narrowed DOWN to a
  subtype (`if (isLiteralLikeAccess(name)) getElementOrPropertyAccessName(name)` —
  utilities.ts `isSameEntityName`) kept the wide `Expression` and failed both overloads.
  Narrows an Object/Interface/Reference raw via getNarrowedTypeForReference when the result
  is a strict improvement (mirror of round 438 fix C for the OVERLOAD path); suppression-only;
  never-collapse keeps `raw`. utilities.ts getElementOrPropertyAccessName family ×5,
  TS2769 9 → 4.
- **Fix 3 (e6f61973, −3): same branch extended to `raw === unknownType`.** A `typeof target
  === "string"` arm narrows the `unknown` param to `string`, matching the plain-string
  overload. Round 429d added `unknown`→primitive narrowing but it reached only the single-sig
  call-arg path; `getPathComponents(target)` is overloaded. moduleNameResolver ×3, TS2769 4 → 1.
- **META / next-agent residual (228):** the clean predicate-overload/narrow-DOWN vein is now
  mostly mined. Remaining TS2769×1 (watchPublic `watchFile` complex-type callee), TS2349×2
  (core.ts/binder.ts `??= []` union-target contextual typing, round-408 known gap). Deeper
  buckets NOT bounded: (a) `Node → HasModifiers`/`Declaration|undefined` narrow-DOWN returns
  (utilities 5085/11856) — the RELATION GATE (`checkTypeRelatedTo(narrowed, declared)`) fails
  on tsc-specific heritage (`JsxNamespacedName <: Expression` etc.) so the single-sig branch's
  legit narrowing is discarded, AND the `.parent`-property-of-narrowed-ComputedPropertyName
  needs per-node-type `.parent` modeling; (b) `assertType<never>` exhaustive-switch defaults
  (×8) — the large `.kind`-discriminated-union exhaustiveness slice; (c) the CROSS-FILE
  function-SHADOW cluster (executeCommandLine `createWatchStatusReporter`/
  `performIncrementalCompilation` ×4) — a module-file-local function shadowing a same-named
  cross-file EXPORT; the mergeSymbolTable pollution (addAll onto the shared symbol) builds a
  bogus cross-file overload set in getTypeOfFunction, so the wrong sig is picked (Blocker #3 /
  M3.5). ATTEMPTED + REVERTED (round 439): a node→file map (eager `topLevelFnDeclFiles`) +
  a filter keeping only the valueDeclaration-file's decls in getTypeOfFunction went
  NET-NEGATIVE (228 → 230) — it did NOT clear the target FPs (the executeCommandLine callee
  sig resolves via a path the filter didn't reach) AND regressed +2 (checker.ts:7360,
  es2018.ts:1052), disproving the "function overloads are always same-file" premise
  (legitimate cross-file function symbols exist — ambient `declare function` merges or the
  B434 crossFileFuncs interaction). A correct fix must prefer the current file's own
  declarations at the RESOLUTION site (getTypeOfIdentifier's currentFileLocals path), not a
  global getTypeOfFunction filter — deferred. (d) B526 tuple/brand + generic-fn-alias
  TS2322 representation gaps.

**Round 436 (2026-07-07) — M3.1/M3.2/M3.4 burn-down, SEVEN more bounded fixes: compiler
profile 373 → 294 (−79, −21.2%; TS2322 184 → 158, TS2345 79 → 47, TS2769 30 → 9) + the
round-436 full-dashboard baseline at the round-435 end state. Suite 9,419 → 9,444
(+25 local, 0 regressions); 7 fix commits (2938d681, d0155b68, a10aa528, b5250f25,
39160e43, 4419d333, b80ab634) + `--listAll` chain printing (f70e4fa6); every step's
by-site diff strictly removals.**
- **Baseline (all 8 profiles at 182b5877, wall 27–41 s):** compiler 373 / tsc-cli 375 /
  jsTyping 371 / deprecatedCompat 372 / typingsInstallerCore 380 / services 1,476 /
  server 1,769 / harness 2,062 — every profile shrank from the round-435 fixes
  (services −127, server −125, harness −131); throughput ~7,000 LOC/s across the board.
- **Fix 1 (2938d681, −14): TP-carrying callback-return params of a generic callee skip
  the fn-return mismatch** (M3.2) — `forEachEntry<K, V, U>(map, cb: (v, k) => U |
  undefined)` with a boolean-returning callback: tsc infers U from the callback's own
  return; `allowFuncReturnMismatch` already skipped a BARE-TP param return, now any
  TP-CONTAINING one (generic callee only). The forEachEntry/firstDefinedIterator/
  forEach/forEachKey family across 5 files.
- **Fix 2 (d0155b68, −17): destructured LOCALS shadow outer bindings in the call-types
  walker** (M3.1) — `const { version, major } = parsePartial(text)` (semver.ts) /
  `const { sourceFile, start, length } = getDiagnosticSpanForCallNode(node)`
  (checker.ts): the binding names lived in NO local map, so bare-identifier args fell
  through to the merged globals and resolved tsc's imported `version: string` /
  `function length(…)`. Registered into the round-429 `currentParamBindingNames` side
  set (anyType, suppression-only) + INHERITED currentLocalTypes entries overridden (the
  file-level `const version = "5.0"` literal recording is consulted before the side
  set). Also cleared 8 TS2769 + 4 TS2345 in the transformers — the same shadow.
- **Fix 3 (a10aa528, −7): a literal return whose annotation union syntactically
  contains it is legal.** DISCOVERY: the engine relation PASSES a literal return
  against a literal-containing union but does NOT early-return for non-nullish sources
  — control falls to the STRING fallback, which re-widens the literal ('boolean' /
  'string') → tsc parser.ts's `return false;` vs `JSDocTypeTag | … | false` ×4, AND the
  completely UNPINNED basic shape `function f(): "a" | "b" { return "a"; }` (fails via
  harness and CLI — no corpus test covers it). `returnUnionSyntacticallyContainsLiteral`
  decides before either path (inline union or direct alias body; FP-proof). CLAUDE.md
  gotcha added for the fall-through trap.
- **Fix 4 (b5250f25, −6): explicit-type-arg calls select the MATCHING generic overload,
  constraint-filtered** — parser.ts `createMissingNode<Identifier>(kind,
  /*reportAtCurrentPosition*/ true, msg)` must select the 2nd overload (the 1st pins
  the literal `false`); the namespace container is load-bearing for the repro (the
  round-429 "mini-repro does not reproduce" residue). **The suite gate caught the
  unfiltered first cut** regressing typeArgumentConstraintResolution1 — tsc
  applicability filters by TYPE-ARG CONSTRAINT first (`foo1<Date>("")` disqualifies the
  `T extends Number` overload; the arg failure reports against the `T extends Date`
  one). Selection: constraint-satisfying candidates (implTypeArgConstraintsSatisfied,
  fallback all) → first args-matching (allArgumentsMatch on the padded contextual
  instantiation) → first candidate.
- **Fix 5 (39160e43, −13): the four overload arg helpers mirror the optional-param
  union-arg rule + skip foreign-TP args** — tsc program.ts's UNION-RECEIVER method
  calls (`(Program | T).getOptionsDiagnostics(cancellationToken)` — the synthesized
  overload pair failed BOTH ways on `CancellationToken | undefined` vs the optional
  param, TS2769 ×6) + `visitNode(…)` results leaking `TOut | TIn & undefined` into
  overload args. Shared `overloadArgSkippable` = `unionArgOkForOptionalParam` (round
  429c) || foreign-TP-carrying arg.
- **Fix 6 (4419d333, −3): switch-case narrowing of a BARE string subject** (M3.4) —
  `narrowBySwitchClause`'s direct path bailed on non-union subjects; semver.ts's
  `switch (operator) { case "<": case ">=": createComparator(operator, v) }` narrows
  to the clause range's literal union (all-literals-of-base gate); the call-arg
  consumer accepts bare-string/number identifiers in the relation-gated refinement
  branch.
- **Fix 7 (b80ab634, −19): guard-gated ternary return arms narrow + the all-leaves-
  verified early return** (M3.4) — `return isNamedTupleMember(m) || isParameter(m) ?
  m : undefined` (utilities.ts family): arms narrow via getNarrowedTypeForReference
  (relation-gated), and `checkConditionalReturnBranches` returns a TRI-STATE (0
  unverified / 1 all-leaves-verified / 2 emitted) so a fully-verified ternary skips
  the aggregated whole-union re-check that FP'd at the return keyword; bailing leaves
  keep the aggregated coverage. −19 across utilities/checker/factory/transformers.
- **Tooling (f70e4fa6): `--listAll` prints elaboration chains** (indented `|`-prefixed
  sub-lines, never matching the `error TS` grep) — the TS2769 triage was unactionable
  without them; chains directly identified fix 5's two mechanisms.
- **META:** (1) the fix-3 discovery generalizes: a shape can fail via BOTH harness and
  CLI with zero corpus coverage — when a dashboard FP looks "too basic", test the
  minimal shape through the harness before assuming corpus protection. (2) Two commit-
  split dances (revert-hunk → gate → commit → re-apply) kept same-file fixes cleanly
  bisectable. (3) The background-suite `| grep` pipeline intermittently returns empty/
  exit-1 — XMLs are the ground truth.
- **Residual triage (next-agent), 294 = TS2322×158 (top buckets now ≤4: `number |
  undefined`→number ×4 M3.4, ModuleSpecifierResult fresh-prop ×4, `__String` branded
  ×3, TransformerFactory generic alias ×3, B526 tuple/brand shapes ×~10, long tail),
  TS2345×47 (`Node`→never exhaustiveness ×3, NodeArray<Node>→SourceFile ×3,
  Expression→Identifier narrowing ×3, `K`→string own-TP ×2, System→
  IncrementalCompilationOptions ×2), TS2591×43 env-legit (needs real @types/node),
  TS2769×9 (findAncestor predicate-overload returns — M3.2 B136-adjacent,
  moduleNameResolver `unknown` narrowing ×3), TS2339×7, TS2454×4, TS2362×4.**

**Round 433 (2026-07-07) — M5 (perf round 2, JFR-driven): the two post-432 hotspots —
self-compile (compiler profile) 38–41 s → 19.9 s noEmit / 21.7 s wall with emit (the
2026-07-05 baseline was 592.8 s → cumulative ~27×), zod 5.0 → 3.6 s; diagnostics
byte-identical both rounds (1,148 incl. per-error diff / 1,665); suite 9,333/0 (+3 local).**
(a) `collectReassignedNamesInRange` (Flow.kt, B464) char-scanned `[closure.pos,
enclosingFn.end)` PER CLOSURE — ~14% of the compile (7.3% self + the String.charAt/getOrNull
churn) on `createTypeChecker`-scale functions. The matcher's decisions depend only on
BACKWARD context and the range END, never the scan start (a scan entering mid-word skips
the partial word exactly as a from-the-start scan attributes it before the range), so all
closures sharing an enclosing function now share ONE scan cached per `hi`, filtered by
position — exact semantics. (b) The flow walkers copied the whole cycle-detection `seen`
set PER BRANCH ANTECEDENT at every FlowBranchLabel (~11%: thousands of ids × a copy per
antecedent). `NarrowSeen` bundles set + add-log: branch antecedents walk the shared
path-so-far membership with mark/popToMark restoring it after each — only genuinely-added
ids are logged, so the restored membership is exactly the fresh-copy state; linear recursion
shares unmarked (additions persist upward, as before); both walkers changed in sync.
`FlowNarrowingPerfInvariantsTest` pins per-closure past-last-assignment semantics through
the shared scan (params, not `let` locals — the TS18048 emitter's captured-body-local
recovery is var-only per B467, verified pre-existing), branch-sibling isolation across a
diamond join, and an emitter-active positive control. Remaining profile is FLAT (top self
≤8%): HashMap churn in the walkers' memo, `findLocalTypeAlias$scan` (~4%, via
`discUnionParamMembers`), `checkMemberAccessMissing` ~3% — next M5 round needs a fresh
JFR pass, no obvious single target left.

**Round 430 (2026-07-07) — M3.1: the `append`/`addRange` inference unlocks +
TP-from-predicate binding. Self-compile (compiler profile) 1,000 → 956 → 936 (−64;
TS2322 496 → 435, TS2769 32 → 30); suite 9,348 → 9,356 (+8 local, 0 regressions);
2 fix commits (6a056b95, 83aeceb1).**
- **Fix 1 (6a056b95, −44 with +6 catalogued): the `T extends {}` constraint killed the
  whole `append` inference + readonly-array anchors.** Round 428's nullable-union
  inference worked for UNCONSTRAINED test sigs, but tsc declares `append<T extends
  {}>` — the candidate constraint check `checkTypeRelatedTo(string, {})` FAILED (an
  anonymous empty object target had no primitive-source rule; the apparent-type
  recovery is Type.Interface-gated), so the mapper was null and every `x = append(x,
  item)` kept the un-instantiated `T[]` return. New relation rule: an EMPTY anonymous
  object target accepts any non-nullish non-void source. TWO landmines pinned:
  (a) a `Type.Union` source's own flags carry no nullish bits (documented gotcha) —
  members checked explicitly so `string | null` still fails; (b) a TYPE-PARAM source
  is EXCLUDED — genericPrototypeProperty3 pins tsc's `Type 'T' is not assignable to
  type '{}'` + "might need an `extends {}` constraint" for unconstrained T under
  strict (the ungated first cut suppressed it; the SUITE GATE caught it — the
  corpus-as-regression-net working exactly as designed). Companion:
  `readonly T[]` params/args anchor array-of-tp inference (`Reference(ReadonlyArray,
  [T])` from getTypeFromTypeOperator; both `isArrayOfTypeParam` and the arg-side
  element extraction matched only "Array") — `addRange(to: T[] | undefined, from:
  readonly T[] | undefined)` never inferred. The +6 are precision-exposures of
  documented M3 residuals where anyType used to hide them (brand-string map keys via
  callback-return widening, optional-target ternary props, visitor generics,
  un-inferred `.map` U[], tuple-vs-array B526 ×2) — by-code still strictly shrank.
- **Fix 2 (83aeceb1, −20 strictly removals): TP-from-PREDICATE binding.**
  `getFirstJSDocTag<T extends JSDocTag>(node, predicate: (tag: JSDocTag) => tag is
  T)` called with a NAMED guard (`isJSDocAugmentsTag`) binds T from the guard's own
  predicate target — the `T | undefined` TS2322 bucket (utilitiesPublic's ~20
  getJSDoc*Tag wrappers, 41 → 21). The resolved signature ERASES the predicate
  (TypePredicate resolves to booleanType), so the param gate reads the AST
  (`predicatePositionTpOf`) and the candidate branch reuses round-424's barrel-aware
  `predicateTargetTypeOfGuardExpr`, soft-skipping unresolvable/inline guards. The
  candidate branch runs BEFORE the standard rawArgType path (which would type the
  guard as a callable object and hard-bail at the named-like gate). Single-sig path
  only (the multi-sig named-guard gate is untouched — B136's swap keeps firing).
- **Residual triage (next-agent):** TS2322×435 — `string` ×38 (incl. the ×24
  interface-override literal props, M3), `T` ×29 (dominated by CONTEXTUAL-RETURN
  inference: `parseTokenNode<T extends Node>()` has NO args — T comes from the
  return context, M3.2), `undefined` ×26 (VisitResult family), `T | undefined` ×21
  residue (non-Array single-arg generic anchors: `firstOrUndefinedIterator(it:
  Iterable<T>)` — extend the anchor set to same-target single-arg References),
  `U[]`/`U | undefined` ×31 (`.map`-family callback-return inference, M3.2),
  visitNodes TOut/TIn ×11 (visitor generics). TS7006×301 (M3.2). TS2345×86:
  `'true'` vs `'false'` nested-overload selection ×5, string-vs-literal-union ×10,
  `Node` vs never ×3 (M3.4 exhaustiveness), NodeArray vs SourceFile ×3.

**Round 429 (2026-07-07) — M3.1 histogram burn-down: the TS2345 core falls 261 → 86
(−67%). Self-compile (compiler profile) 1,186 → 1,156 → 1,135 → 1,027 → 1,000 (−186,
−15.7%; TS2345 261 → 86, TS2769 36 → 32, TS2322 501 → 496, TS2367 −2); every step's
by-site diff STRICTLY removals (the one +3 excursion was caught by the diff and gated
before commit); suite 9,315 → 9,348 (+33 local, 0 regressions); 4 fix commits
(577b2c54, 5fbb8caf, bc893882, d1e53cbd).**
- **Fix 1 (577b2c54, −30): call-types pass lexical shadowing — three scope shapes
  resolved a bare-identifier ARG to the WRONG outer declaration.** (1) A NESTED
  function's body-local (`let host = node.parent`) shadowing an ENCLOSING fn's param
  (`createTypeChecker(host: TypeCheckerHost)`): the inherited `currentLocalTypes` entry
  survived because round 428d's branch is gated entry==null —
  `applyCallTypesBodyLocalShadowing` pre-scans the body (statement-level, not
  descending into nested fn-likes) and anyType-overrides colliding local-decl names;
  same-fn param redeclaration excluded (param wins, pinned). (2) The round-428
  "PARAM-shadow mini-repro does not reproduce" mystery RESOLVED: the real shape is a
  DESTRUCTURED param (`{ useCaseSensitiveFileNames }` in sys.ts vs moduleNameResolver's
  same-named function) — binding names live only in the `currentParamBindingNames` side
  set, and `getTypeOfIdentifier` fell through to the merged globals; it now returns
  anyType for side-set names (after `currentLocalTypes`). (3) Arrow/fn-expr params
  (the walker deliberately doesn't type them) leaked the enclosing binding — those
  branches now scope the maps and register anyType for own param names. 8 local tests
  (CallTypesScopeShadowingTest).
- **Fix 2 (5fbb8caf, −21): embedded String.replace/replaceAll/search/split accept
  RegExp** (`searchValue: string | RegExp`, replaceValue `any` per the
  callbacks-are-any doctrine) — tsc regex-replaces pervasively. Corpus byte-identical
  (no "and N more" shifts). Accepted documented FN: a union-with-interface param is
  not simple-checkable, so wrong-typed args to these four params no longer error
  (control pins indexOf still fires).
- **Fix 3 (bc893882, −129, the big one): three arg-typing rules on the call-arg
  path.** (a) A `string | undefined` union arg is legal for an OPTIONAL param
  (`configFileName?: string` — tsc getTypeAtPosition unions undefined under strict);
  only undefined members stripped (null stays), relation on the stripped type,
  suppression-only. (b) A non-null-asserted arg (`readFile(p)!`) types as its
  nullish-stripped union — LOCAL strip (`stripNullishForNonNullArg`), mirroring the
  round-415 arithmetic rule; the round-407 global-strip revert stands. (c) THE
  DOMINANT mechanism (~110 sites): an Identifier arg whose NON-union interface type
  is guard-narrowed DOWN (`isSourceFile(x) && isExternalOrCommonJsModule(x)` — Node
  → SourceFile) substitutes the refined type, relation-gated — generalizes round
  428b's `this`-only branch. LANDMINE caught by the by-site diff: `never`-typed
  params must be EXCLUDED — `assertType<never>(node)` in an exhaustive-switch default
  needs exhaustiveness narrowing we don't model, and a partial case-union refinement
  TAKES THE UNION-ARG EMISSION PATH (interface args stay conservatively silent vs
  never; unions emit) → +3 FPs until gated. No stable local pin exists for the gate
  (tsc itself errors on the in-file non-exhaustive shape; the exhaustive
  discriminated-union shape needs M3.4 exhaustiveness) — pinned by the by-site diff.
  10 local tests (OptionalParamUnionArgTest).
- **Fix 4 (d1e53cbd, −27): typeof-unknown + string-enum + rest-arg narrowing.**
  (a) `typeof x === "<primitive>"` narrows a non-union UNKNOWN to the primitive —
  `narrowByTypeOfGuard`'s non-union flags path returned NEVER for a positive match
  on unknown (no primitive flags), which the relation-gated consumers rejected
  (moduleNameResolver `target: unknown` ×10). (b) An all-string-valued enum is
  assignable to `string` (`isStringEnumObjectType` in `isSimpleTypeRelatedTo`, the
  round-428b numeric sibling; unevaluated values NOT provable, conservative) —
  resolves the round-410 DEFERRED `Extension[][]` cluster: cascades to `Extension[]`
  → `string[]` via same-target covariant element comparison + clears the paired
  TS2367 no-overlap FPs (×8). (c) The rest-args helper mirrors B469 flow narrowing
  (`cond ? diag(…, deprecatedEntity) : …` ×5). 10 local tests
  (UnknownTypeofAndStringEnumArgTest).
- **META:** two process notes. (1) A mid-bench Checker edit poisoned one bench row
  (the 429b TSV row's build raced my 429c edits) — recovered via git-stash patch-split
  and per-commit listalls; batch edits BEFORE launching a suite/bench. (2) The
  round-428 residual note said "probe the pass's nesting entry with a marker before
  theorizing" — the actual fix needed no marker: re-reading the real site showed the
  param was DESTRUCTURED, which the mini-repro had simplified away. Repro fidelity
  beats instrumentation.
- **Residual triage (next-agent):** TS2345×86 — `'true'` vs `'false'` ×5 (parser.ts
  createMissingNode nested OVERLOADS with literal-typed params; the top-level
  mini-repro does NOT reproduce — the nested/closure context matters, probe needed);
  `string` vs literal-union ×10 (`"typings"|"types"|…`, pragma names, comparators —
  likely needs literal-preserving locals or narrowing); `Node` vs `never` ×3 +
  in-file exhaustive discriminated-union `assertType<never>` (needs M3.4
  exhaustiveness narrowing — catalogued, our A|B switch repro still fires);
  `NodeArray<Node>` vs SourceFile ×3, `System` vs IncrementalCompilationOptions ×2,
  `K` vs string ×2 (keyof-TP). TS2322×496 — `string` vs `string` ×24
  (interface-override literal props, M3), `T[]` residuals (~40: rest-param sigs,
  readonly-array params — `addRange(to: T[] | undefined, from: readonly T[] |
  undefined)`'s TypeOperator param defeats the union-mode detection), SearchResult<T>
  ×10, `undefined` vs VisitResult ×12. TS7006×301 (M3.2) untouched.**



**Round 428 (2026-07-06) — M3.1 (first real slice of the TS2322/TS2345 cores): generic
call-site inference for tsc's `append` idiom + the TS2345 histogram top + the array-literal
string-layer union rule + the body-local-shadows-function conflation. Self-compile
(compiler profile) 1,577 → 1,385 → 1,266 → 1,213 → 1,186 (−391, −25%; TS2322 751 → 501,
TS2345 394 → 261, TS2769 45 → 36, TS2339 6 → 7); suite 9,291 → 9,315 (+24 local, 0
regressions); 4 fix commits (67efa224, 14e9d566, 1791e87a, fbda155d).**
- **Fix 1 (67efa224, −192): nullable-union generic params + overloaded generic callees.**
  The single biggest TS2322 shape (`Type 'T[]' is not assignable to type 'Statement[]'`
  ×130+ + siblings) is tsc core.ts's `x = append(x, item)` — every `append` overload is
  GENERIC (single TP each) with `T[] | undefined` / `T | undefined` union params. Four
  coupled mechanisms: (a) `tryInferSingleTypeParamFromArgs` accepts a nullable-union-of-tp
  param (`nullableUnionOfTpMode`) and strips nullish members from a UNION arg (purely
  nullish arg → soft-skip, T still anchors from the other arg); (b) an `anyType` arg (an
  unmodeled local — for-of loop var) contributes NO candidate at the RETURN-TYPE site
  instead of killing the inference (`forReturnType`-gated; the arg-vs-param site keeps the
  hard bail — its consumers EMIT); (c) `getReturnTypeOfCallExpression`'s multi-sig path
  runs single-TP inference for overloaded all-generic callees (chosen sig first, then
  arity-matching sigs; first full mapper wins) — gated on NO named-type-guard Identifier
  arg (`argIsNamedTypeGuardIdentifier`: `filter(arr, isFoo)` selects tsc's guard overload
  whose S binds from the PREDICATE, which we don't model — and the gate is deliberately
  NOT folded into `callHasTypeGuardArg`, whose B136 concrete-overload swap must keep
  firing for named guards); (d) the string-layer `isAssignableTo` treats an array-literal
  source vs `T[]` (T an enclosing fn's TP) as unknowable. By-site: 201 removed, 8
  position-identical message transformations (builder.ts tuple-vs-anon-object — the B526
  representation gap now visible where 'T[]' was), 1 new FP at factory/utilities.ts:713 —
  **tsc 5.5 INFERRED TYPE PREDICATES: `filter(helpers, helper => !helper.scoped)` gets an
  inferred `helper is UnscopedEmitHelper` in tsc, selecting the guard overload; we take
  the boolean overload → EmitHelper keeps the ScopedEmitHelper member → TS2339 on
  `.importName` (catalogued M3.4; needs predicate inference from arrow bodies).**
- **Fix 2 (14e9d566, −119): the TS2345 histogram top — three mechanisms.** (a) explicit
  `this`-PARAM annotation wins over the objlit contextual `this` in the call-types walker
  (`withObjThis` now resolves `value(this: Node)` — debug.ts's Object.defineProperties
  `__tsDebuggerDisplay` FP'd ×36 at every `isFoo(this)` arg). (b)
  `tryEmitOptionalMemberArgVsRequiredNamedTs2345` (the optional-member arg emitter that
  synthesizes `T | undefined` locally) consults `propertyAccessNarrowedNonNull` — a
  truthy-guarded access is not undefined (`if (source.valueDeclaration)
  setValueDeclaration(target, source.valueDeclaration)`, checker.ts mergeSymbol ×24+;
  unguarded + wrong-polarity controls pinned). (c) two exposure companions the by-site
  diff caught: numeric-enum → `number` in `isSimpleTypeRelatedTo` (FlowFlags/Comparison/
  TypeFlags ×8), and a this-typed arg narrowed DOWN by a guard substitutes the refined
  type (relation-gated suppression-only; `isIdentifier(this) ? idText(this) : …`).
- **Fix 3 (1791e87a, −53): 'array' vs union-with-array-member at the string layer.** An
  array-literal source against a union member that is array-ish (`[]`-suffix, tuple,
  `Array<X>`/`ReadonlyArray<X>`) is unknowable at the string layer → permissive
  (`sourcesContent = []` vs `(string | null)[] | undefined`, `return []`); a union WITHOUT
  an array member still fires (pinned).
- **Fix 4 (fbda155d, −27): the body-local-shadows-function half of the conflation
  family.** A body-local `const symbolName = …` colliding with an outer/imported FUNCTION
  resolved through the merged globals to the function in bare-identifier ARG positions
  (checker.ts's `canUsePropertyAccess(symbolName, …)` → TS2345
  `(symbol: Symbol) => string` vs `string` ×15 + TS2769 ×8). The call-types walker's
  VariableStatement branch registers an anyType shadow when the colliding outer symbol
  declares a FUNCTION (AST-only gate) — mirrors M1.11's `shadowNestedFunctionNames`.
  First-cut negative control was WRONG about baseline capability (non-callable body
  locals aren't typed by this pass at all — the suite gate caught it); replaced with a
  param-based control.
- **META:** the ~450 ms scratch-CLI repro loop + temporary `println` tracing (the CLI shows
  stdout, unlike gradle) found the root causes fast; the XDBG probe DISPROVED the assumed
  emitter for fix 2b (checkArgumentsAgainstSignature's B469 narrowing never ran — the
  emitter was the dedicated optional-member walker).
- **Residual triage (next-agent):** TS2345×261 — the PARAM-shadow half of the conflation
  remains (`(state: ModuleResolutionState) => any` vs boolean ×14, `TypeCheckerHost` ×14:
  watch.ts's `useCaseSensitiveFileNames` / checker.ts's `host` are enclosing-fn PARAMS
  shadowing barrel-imported functions, read inside NESTED arrows — the mini-repro of the
  same shape does NOT reproduce, so the real blocker is in how the pass enters those
  specific nestings; probe with a marker before theorizing); `Declaration | undefined`
  guard-shape leftovers. TS2322×501 — `string | string` ×24 (interface-override literal
  props: `TsConfigOnlyOption.type: "object"` — per-prop resolution through the narrowed
  redeclaration, M3), `undefined | VisitResult<Node | undefined>` ×12 (generic alias
  unions), residual `T[]` shapes ×~30 (inference gate misses: rest-params, multi-TP),
  `SearchResult<T>` ×10 (un-inferred generic Reference returns), builder.ts
  tuple-vs-anon-object ×8 (B526). TS7006×301 (M3.2 contextual typing) untouched.**

**Round 427 (2026-07-06) — M3.4: the TS2454 bucket round 426 unmasked — three tsc-faithful
`assumeInitialized`/definiteness rules. Self-compile (compiler profile) 1,593 → 1,577
(−16; TS2454 20 → 4, all else byte-identical); suite 9,282 → 9,291 (+9 local, 0
regressions); 1 fix commit (7b2e3807).**
- **(1) Logical assignments are DEFINITE (tsc `getAssignmentTargetKind`):** `??=`/`||=`/
  `&&=` classify `AssignmentKind.Definite` (same as plain `=`), so
  `isSymbolAssignedDefinitely` → `isNeverInitialized` false → a CAPTURED (cross-closure)
  read of an outer `let` assumes initialized when any definite assignment exists
  ANYWHERE, nested closures included (tsc checker.ts:31196 `assumeInitialized =
  … (isOuterVariable && !isNeverInitialized) …`). Our B78.2 anywhere-scan
  (`collectAssignmentsInExpr`) recognized only `=` — tsc's own
  `(sourceStack ??= []).push(source)` / `(trackedSymbols ??= []).push(…)` closures FP'd
  ×13. Compound assignments (`|=`, `+=`, `++`) stay NON-definite
  (`AssignmentKind.Compound`) — the negative control matches unusedLocalsInMethod4's
  `enabledSubstitutions |= …` baseline expectation.
- **(2) A `!`-asserted read assumes initialized:** the literal `node.parent.kind ===
  SyntaxKind.NonNullExpression` disjunct — tsc's own core.ts `return lastResult!`.
  Applied in BOTH read walkers (`findUninitializedRefs` + `walkExprForFlowTS2454`): a
  bare Identifier DIRECTLY under `!` is exempt (covers `x!` and `x!.prop`);
  `(obj.foo)!` still walks the receiver `obj`.
- **(3) The comma-nested definite assignment (`(!memberName ? (memberName = X, true) :
  …)`, checker.ts getSignaturesOfType) needed TWO coupled fixes, both caught by the
  bench by-site diff:** (a) the anywhere-scan's iterative left-spine walk applied the
  assignment-target rule only to the OUTERMOST BinaryExpression — a COMMA expression
  nests the assignment on the LEFT spine, silently skipped; per spine node now (tsc
  `markNodeAssignments` is a full forEachChild walk). (b) The FLOW-based walker's
  expression-bodied-arrow branch (B86.1a) checks the arrow body against the OUTER
  uninit set when reached via a flagged position (a NESTED if's condition is walked
  `inUncheckedBody=true` — which is why the real site only fired inside the enclosing
  `if (kind === SignatureKind.Call …)` block and a top-level repro was clean); it now
  masks out names with a definite assignment inside the arrow body (the captured-read
  exemption), via the same anywhere-scan expression walker.
- **Residual TS2454×4 (triaged, none bounded):** scanner.ts `resultingToken` ×2
  (assigned inside a `while (true)` body before every exit — `isAssignedAtFlow` follows
  only the loop-ENTRY antecedent at FlowLoopLabel, the deliberate back-edge bound; needs
  loop-aware assignment evidence); checker.ts:14106 `indexInfos` (same-container flow
  precision: `x = concatenate(x, …)` self-read in a for-of after conditional seeding);
  generators.ts:1681 (`for (const variable of …)` SHADOWS the outer `let variable` — the
  name-based block-unaware `uninitialized` set resolves the read to the outer decl;
  needs block-scoped shadow tracking).
- 9 local tests (Ts2454AssumeInitializedTest) with negative controls (compound `|=`
  still fires; never-assigned captured read still fires; plain un-asserted read still
  fires; a plain same-container read after an in-arrow assignment still fires — the
  arrow's assignment is invisible to the outer control flow, which is why the real tsc
  source uses `memberName!` for those reads).
- **Perf note:** bench self-time 126.6 → 112.2 s — likely band movement (three
  consecutive runs trended 149 → 127 → 112 s); treat the M5 single-run baseline as
  ~110–150 s until an iterations run.

**Round 426 (2026-07-06) — M3.4 (absorbs M1.2's TS2563 item): faithful TS2563 — flow-walk
DEPTH-TRIP semantics with per-container disable, replacing the B399 per-file node-count
proxy. Self-compile (compiler profile) 1,600 → 1,594 → 1,593 (−7; TS2563 27 → 1 → 0,
TS2454 0 → 20 — pre-existing walker FPs the proxy's blanket per-file filter had masked,
now honestly visible; all else byte-identical by-code); suite 9,276 → 9,282 (+6 local,
0 regressions); 2 fix commits (4d23738f + db69fe59). (The implementing session was OOM-killed
mid-verification with the work complete-but-uncommitted; this session verified, measured,
landed it, and root-caused + fixed the one residual trip.)**
- **Mechanics (4d23738f):** tsc reports TS2563 ONLY when a flow walk recurses 2000 deep
  (checker.ts `getTypeAtFlowNode` `flowDepth === 2000` → `flowAnalysisDisabled` +
  `reportFlowControlError(reference)` at the containing function-or-module block +
  errorType for that container's flow queries thereafter — so TS2454 is suppressed per
  CONTAINER, tsc's OR-rule). All three flow walkers (`narrowTypeFromFlow` + the
  FollowLoopEntry mirror + `isAssignedAtFlow` — the last rewritten ITERATIVE with the
  round-413 accounting: linear pass-through antecedents free, only branch-join /
  condition / loop-entry recursion consumes depth) set `flowDepthTripped` at the trip;
  every depth-0 entry (11 sites) routes through `flowWalkWithTripCheck(reference)` —
  pre-checks `flowDisabledRanges` (disabled → conservative default WITHOUT walking),
  one-shot TS2563 per container via the flow graph's new `containerStarts` (innermost
  containing function-like body block, else the file); the end-of-init TS2454 filter is
  per-container-RANGE (was per-file `cfaTooLargeFiles`, deleted). The dedicated
  `evolvingArrayWalkTrips` init walk supplies the depth consumer for the corpus pin
  (largeControlFlowGraph: auto-typed `const data = []` + 10k top-level `data[0] = 0`
  writes, one level per relevant mutation; after the first trip the container is
  disabled, so the whole file costs ONE 2000-step walk). Our OWN budgets (visit budget,
  global re-entry depth, cycle bail) still truncate SILENTLY — only the per-walk depth
  limit is tsc's TS2563 semantic.
- **The measured trade (by-code diff, everything else byte-identical):** −26
  by-construction TS2563 proxies; +20 TS2454 = pre-existing definite-assignment FPs on
  the giant files the per-file filter had blanket-suppressed. Triage (next bounded
  burn-down bucket, three shapes): (a) DOMINANT ×~16 — cross-closure reads of an outer
  `let` (`let sourceStack: Type[];` in the outer fn, `(sourceStack ??= []).push(…)`
  inside a NESTED function — checker.ts inferFromTypes/serializer, tsc's
  used-before-assigned check applies only within the declaration's own control-flow
  container; captured reads assume initialized); (b) core.ts:2474 `return lastResult!`
  — a NON-NULL-ASSERTED read (tsc does not report TS2454 through a `!`); (c)
  scanner.ts `resultingToken` ×2 — assigned inside a `while (true)` body before every
  exit, read after `Debug.assert(resultingToken !== undefined)`; our `isAssignedAtFlow`
  follows only the loop-ENTRY antecedent at FlowLoopLabel (the deliberate back-edge
  bound), so in-loop assignment evidence is invisible.
- **Fix 2 (db69fe59): the 27th TS2563 was OURS, not the proxy's — `flowCallMightNarrow`
  needs the asserts-callee check (tsc `getEffectsSignature`).**
  diagnosticInformationMap.generated.ts (~2,100 top-level `diag(…,
  DiagnosticCategory.Error, …)` statements): any walk for a `DiagnosticCategory`
  reference found EVERY call's args mentioning the path, so the round-413
  over-approximating gate recursed per call → 2,100 > 2,000 → trip. tsc resolves the
  callee's effects signature BEFORE deciding (cached per node): a non-assert call is
  followed in the `while` loop, consuming NO flowDepth. `flowCalleeMayHaveAssertEffects`
  gives an EXACT verdict for Identifier callees (map-lookup resolution via
  `resolveFlowCalleeDecl`'s Identifier branch — never types a receiver; same decl +
  same predicate test `narrowByAssertCall` applies, so iterating past a false is
  EQUIVALENT, not just safe) and conservative-TRUE for PropertyAccess callees
  (`Debug.assert(x)`) — resolving those types the RECEIVER (the round-385
  services-hang hazard), they keep the consume-depth behavior. LESSON: with faithful
  TS2563, the round-413 "a too-eager gate only costs a depth level" calculus changed —
  a too-eager CALL gate now manufactures a false TS2563 on any >2000-chain of
  path-mentioning non-assert calls.
- **Local tests (CfaTooLargeBailTest 2 → 8):** deep branch chain trips exactly ONCE +
  suppresses the container's TS2454; per-container disable (a sibling function's TS2454
  SURVIVES a trip — the per-file proxy killed it); straight-line 3000-assignment chain
  does NOT trip; evolving-array 3000-write chain trips once at the first statement
  (+ 100-write control) — the largeControlFlowGraph pin the JS-emit-only corpus test
  never asserted; 2,500 non-assert calls mentioning the reference do NOT trip (426b,
  with the TS2339 control still firing); 2,500 asserts-callee calls DO trip exactly
  once (the too-lax-gate landmine control).
- **Perf watch (M5) — 426b is a WIN, not a cost:** bench self-time 150.9 s (round 425,
  dirty) → 149.4 s (426) → **126.6 s (426b, −15.3%)**. The asserts-callee gate doesn't
  just fix the false trip — every path-mentioning NON-assert call used to break the
  fast-forward loop into recursion (+ a narrowByAssertCall resolution at each), and
  tsc's sources are saturated with calls that mention whatever reference is being
  walked; now those iterate for free.

**Round 425 (2026-07-06) — M3.4/M1.12: the TS2339 never-cluster ROOT CAUSES + eight more
narrowing slices. Self-compile (compiler profile) 1,662 → 1,634 → 1,628 → 1,608 → 1,607 →
1,603 → 1,600 (−62; TS2339 68 → 6, never×21 → 2); EVERY step's by-site diff strictly
removals; suite 9,251 → 9,276 (+25 local tests, 0 regressions); 7 fix commits.**
- **Fix 1 (−28, eb28f0d3): union-target guards distribute over candidates + CANONICAL enum
  discriminant keys.** Two coupled root causes behind the never cluster: (a)
  `narrowByCallPredicate`'s positive union branch tested narrow-DOWN against the WHOLE
  target union (`targetUnion <: member` — requires every candidate, never holds); tsc's
  getNarrowedType distributes `mapType(candidate, c => …)` — now per-candidate, strictly
  more-keeping. (b) THE BIG ONE: the round-411 `"symId#member"` key space SPLIT — the same
  enum reaches the key builders as DIFFERENT Symbol instances (program-global merged vs
  declaring-file local via the barrel resolver), so ALL SyntaxKind keys looked pairwise
  disjoint and `typeGuardMemberDisjoint` dropped every guard-narrowed member.
  `canonicalEnumSymbol` (memoized; prefers the global merged symbol when it shares an
  EnumDeclaration NODE by identity and has enumValues) at all four key-builder sites.
  **Also cleared the round-423 "dead-end" `Identifier | ComputedPropertyName`×8 family and
  the isAccessExpression never×4 — the DISJOINTNESS VERDICTS, not the relation, were the
  blocker all along.** META: the scratch repro cleared while the real corpus didn't budge
  (zero site churn); two rounds of repro-enrichment found nothing — only stderr
  instrumentation on the REAL corpus (print the key sets) found the split.
- **Fix 2 (−6, 4cb59a6c): aliased SWITCH discriminants** (tsc compareTypeMappers:
  `const kind1 = m1.kind; switch (kind1) { case TypeMapKind.Simple: m1.source }`) —
  `narrowBySwitchClause` resolves a bare-Identifier subject through the round-423
  aliased-condition back-walk (the const-ness proof) to `<name>.<prop>`.
- **Fix 3 (08835c06, part of −20): four slices** — (a) `narrowByDiscriminantProperty`:
  a UNION-of-literals discriminant (`type: "list" | "listOrElement"`) matches positively
  when ANY constituent equals / survives a negative when ANY differs; an OBJECT-typed
  discriminant (`type: Map<…>`) can never === a primitive VALUE literal → positive drops
  the member (enum-flavored objects excluded). **LANDMINE (+3 nevers in the first cut,
  caught by the by-site diff): BOTH rules gate on a definite VALUE literal — optionality
  is a symbol attribute NOT folded into the resolved prop type, so `x.body === undefined`
  proves NOTHING** (checkGrammarAccessor/isUncheckedJSSuggestion collapsed). (b) `typeof
  x === "object"` three-way union filter (object-like + null match; primitives/undefined/
  CALLABLES — they report "function" — don't; any/unknown kept both branches). (c)
  truthiness of a BOOLEAN-LITERAL discriminant (`info.isStatic ? info.variableName : …`,
  classFields ×2). (d) a DESTRUCTURING read consults flow narrowing of its initializer
  (`if (!result) return; const { version, paths } = result` — moduleNameResolver/
  programDiagnostics/utilities ×6).
- **Fix 4 (fb6c23f4, part of −20): loop-entry retry for the round-418 single-type
  narrow-DOWN suppression** — a guard before a loop narrows a read inside it; the plain
  walk washes at the FlowLoopLabel (checker.ts tuple-inference `constraint.target` ×3).
  The single-type sibling of round-424 fix 1.
- **Fix 5 (−1, aa00dc51): instanceof narrows a SUPERTYPE member DOWN to the class**
  (`tracker instanceof SymbolTrackerImpl` on `SymbolTracker | undefined`, the class
  implements the interface — the subtype-only filter dropped everything). Approximates
  tsc's intersection fallback with the class type; the structural-identity corpus pin
  (instanceofWithStructurallyIdenticalTypes) verified intact.
- **Fix 6 (−4, 5ff41ffb): aliased `===` discriminants** (commandLineParser
  `const optType = opt.type; if (optType === "listOrElement") { opt.element }`) **+
  switch-DEFAULT negative narrowing** (a default clause alone in its flow range narrows
  by every case literal/enum key of the whole switch — executeCommandLine's
  `option.type.forEach`/`option.deprecatedKeys` + bonus utilities.ts:3466; conservative:
  non-literal case exprs bail, fallthrough ranges bail, only LITERAL-typed members drop
  on the direct path).
- **Fix 7 (−3): tsc's positive-empty INTERSECTION fallback** (`hasDynamicName(accessor)`
  vs an unrelated-in-both-directions target now yields `m & c` for object-capable pairs
  instead of `never` — **REVERSES the round-423 dead-end verdict: the 1,708 → 1,710
  net-negative was an artifact of the enum-key split; re-measure dead-ends when an
  upstream root cause falls**) + `typeof "object"` classifies an ENUM member as
  NOT-object (watchPublic's `ScriptTarget | CreateSourceFileOptions`).
- **Process notes:** (1) do NOT `compileKotlinJvm` while a background self-compile A/B is
  in flight — the recompile clobbers class files the running JVM lazily loads
  (ClassNotFoundException mid-run); concurrent CLI RUNS are safe. (2) The patch-split
  protocol again (5 same-file batches split into 7 bisectable commits, tests distributed
  per commit).
- **Perf watch (M5):** the round-425 bench single-run came in at 151 s self-reported vs the
  ~100–137 s recent band (+10%) — single-run noise vs the new retry/back-walk paths not yet
  disentangled; the retries only run on would-be-FP emissions and the back-walks are memoized,
  but re-measure with iterations at the next M5 touchpoint.
- **Residual TS2339×6 (all triaged):** checker.ts:33288/33289 never×2 — try/finally:
  `bindTryStatement` gives a finally-only block ONLY the try-end antecedent (unreachable
  when the try returns → never) — needs a preTry antecedent for the finally entry (but
  NOT for the post-switch continuation — TS2454 regression risk documented in-session)
  PLUS `??=` non-nullish-call-RHS narrowing; checker.ts:28630 `Type | IncompleteType`
  (`flags === 0` vs `flags: TypeFlags` — needs enum-as-literal-union comparability,
  B425/M3.3); moduleNameResolver.ts:2823 (interface modeling, M3);
  builder.ts:2242 (tuple-index on tuple-union, the B526 representation gap);
  es2020.ts:91 (loop-carried `OptionalChain` reassignment, M3). **Next-agent note —
  TS2563×27 (the whole bucket, diagnosed this session):** tsc emits TS2563 ONLY when a
  flow WALK recurses 2000 deep (`getTypeAtFlowNode` `flowDepth === 2000` → set
  `flowAnalysisDisabled`, report at the containing function-or-module block's
  `statements.pos`, return errorType thereafter — checker.ts:29036/28841); on tsc's own
  sources NO walk trips (the linear fast-forwarding our round-413 iteration mirrors keeps
  depth low), so all 27 per-FILE-node-count proxies are FPs by construction. The faithful
  rebuild: trip-detection + a per-CONTAINER disabled set + one-shot TS2563 at tsc's
  position, threaded through ALL flow walkers (narrowTypeFromFlow + FollowLoopEntry
  mirror, the TS2454 definite-assignment walkers), REPLACING the B399 per-file proxy AND
  its `cfaTooLargeFiles` TS2454 end-of-init filter (tsc's OR-rule then holds per
  container naturally). `CfaTooLargeBailTest` pins the CURRENT proxy deliberately and
  must be REWRITTEN to the depth-trip semantics (its 3000-if "big" shape plausibly DOES
  trip a faithful walk — sequential if-joins recurse per join; verify against
  `largeControlFlowGraph`'s baseline which expects TS2563). RISK: un-suppressing TS2454
  on the 27 files may surface previously-masked TS2454 FPs — measure the trade by-site.
  Next big buckets:
  TS2322×751 / TS2345×394 / TS7006×301 (M3 cores), TS2769×45 (M3.1 generic call-site
  inference), TS2563×27 (B399 heuristic → M3.4), TS2591×43 + TS2304×2 (env-legit).**

**Round 424 (2026-07-06) — M3.4/M1.12: seven flow-narrowing burn-down fixes from the round-423
residual triage. Self-compile (compiler profile) 1,707 → 1,691 → 1,687 → 1,683 → 1,680 → 1,672 →
1,662 (−45; TS2339 104 → 68, TS18048 5 → 1, TS2322 756 → 751); every step's by-site diff STRICTLY
removals; suite 9,223 → 9,251 (+28 local, 0 regressions, 2 deliberate pin flips toward tsc
semantics); 7 fix commits, 7 local test files (28 tests).**
- **Fix 1: union-receiver TS2339 suppression survives loop boundaries (−16).** tsc's own
  `parseResponseFile` (commandLineParser): `const text = tryReadFile(…)` (`string | Diagnostic`),
  pre-loop `if (!isString(text)) return;`, reads inside `while` loops — the plain walk washes to
  the declared union at FlowLoopLabel, so the union elaboration FP'd. The union branch of
  `checkMemberAccessMissing` retries with the loop-entry-following variant, SUPPRESSION-ONLY.
  **The landmine that cost the first cut: the "plain walk didn't narrow" gate must be STRUCTURAL
  (member-id sets) — any `&&`/`||` on the path is a 2-antecedent FlowBranchLabel whose union of
  [declared, declared] MINTS a fresh Type.Union (getUnionType does not intern), so `===` misses
  the wash exactly when a compound condition is present.**
- **Fix 2: `narrowByAssignmentRhs` accepts a CALL RHS with a provably non-nullish return
  annotation** (syntactic `typeNodeDefinitelyNonNullish`; own-TP refs and `?.` calls bail;
  `flowAssignmentMightNarrow` needed NO change — it already over-approximates on the LHS). No
  compiler-profile delta: the motivating checker.ts:21170 (`instantiateType`) is an OVERLOAD
  CLUSTER (2 sigs + impl) → `uniqueFunctionDeclByName` ambiguous → no claim. Selecting the right
  overload's return is genuine overload resolution (M3) — noted, deferred. Capability is real for
  single-decl callees (local tests + other profiles).
- **Fix 3: the aliased-condition back-walk follows closure boundaries, if/else joins, and calls
  (−4: builder.ts:431/433 `canCopyEmitSignatures` + 2 bonus JsxCallLike TS2339 at
  checker.ts:37578).** FlowStart → outer flow gated by the B464 captured-name rules on BOTH the
  alias and the walked root; FlowBranchLabel → every REACHABLE antecedent must independently
  prove value preservation and land on the same decl (unreachable ones contribute nothing);
  FlowCall/FlowArrayMutation are value-preserving (a call can't rebind an enclosing let/const —
  tsc's isConstantVariable gate likewise ignores closure-mediated rebinding); plus a per-call
  node MEMO (a 6-term `||` condition fans out a diamond per term). **TWO invisible blockers the
  repro missed but the real builder.ts hit: `FlowAssignment.node` for an assignment EXPRESSION is
  the whole BinaryExpression (`flowAssignmentRootName` must read its LHS — it bailed at
  `!(oldInfo = oldState!.fileInfos.get(…))`), and the un-memoized fan-out exhausted the budget.**
- **Fix 4: prefix-path guard narrowing (−4: moduleNameResolver.ts:849 + 3 bonus builder.ts
  TS2322).** `usesWildcardTypes(options): options is CompilerOptions & { types: string[] }` with
  walked path `options.types` — the predicate arg's path is a proper dot-PREFIX of the walked
  path; when the tail resolves on the predicate target to a REQUIRED property with a provably
  non-nullish type, the positive branch drops nullish. Minimal claim only. **Landmine: property
  OPTIONALITY is a symbol attribute, NOT folded into the property type (`types?: string[]`
  resolves to `string[]`) — `resolvePrefixTailSegment` consults `isOptionalProperty` per segment;
  on an intersection, required iff ANY constituent declares it required.** The
  `narrowByCallPredicate` pre-check widened (allocation-free) to prefix matches — the old
  "exact-match only" note is superseded.
- **Fix 5: `asserts node is U` with U an INFERRED callee type param (−3: transformers/ts.ts:2012
  `Debug.assertNode(node.name, isIdentifier)` — BOTH its TS18048 and its latent co-located
  TS2339, + a bonus emitter.ts:5263).** THREE coupled pieces, each measured necessary: (a)
  `resolveNamespaceMemberFnDecl` PREFERS a TypePredicate-bearing declaration — an overloaded
  assert's valueDeclaration is the annotation-less IMPL, which made every narrowing consumer bail
  before anything else could work; (b) U resolves from the type-guard TEST argument's own
  predicate target (`predicateTargetTypeOfGuardExpr`, mirroring resolveFlowCalleeDecl's paths
  without its call-keyed memo) — **the constraint-chain drop-nullish claim ALONE just trades the
  TS18048 for a TS2339 on the surviving union members** (`Identifier | StringLit` lacks
  escapedText); (c) the constraint chain (`U → T → Node` all non-nullish) stays as the fallback
  for asserts without a resolvable test arg.
- **META (repro-loop discipline):** every fix was developed against a ~400 ms scratch
  mini-project through the compiled CLI with per-fix NEGATIVE controls (wrong polarity /
  reassignment / optional tail / unconstrained TP), and every self-compile step was verified by
  BY-SITE diff (strictly-removals), not just the count. Three of five fixes needed a second
  iteration only discoverable against the REAL tsc source (the assignment-expression flow-node
  shape, the overload-cluster impl, the union-member trade) — always re-measure on the real
  corpus before calling a repro-verified fix done.
- **Fix 6: assignment-overwrite reset (−8: moduleNameResolver.ts 1924/1931/1950 never×6 + bonus
  checker.ts:7144 / program.ts:4048 TS2322).** A shadowing redeclaration after an outer falsy
  guard collapsed to `never`: the walk crossed the outer falsy branch (→ `undefined`), passed the
  inner `const resolved = loadModuleFromImports(…)` UNCHANGED (unclassifiable call RHS kept the
  stale antecedent), and the inner truthy guard narrowed `undefined` → `never`. An overwrite now
  resets to the PRECISE overwritten type: a DECLARATION to its own annotation / initializer-call
  return annotation (the flow-nearest declaration IS the binding the read lexically refers to —
  the flat name-keyed local map is block-unaware/first-decl-wins), a plain `=` to its call-RHS
  return annotation; `??=`/`||=`/unresolvable keep the antecedent pass-through (for `??=` the
  antecedent IS the correct base). **MEASURED trap: resetting to the reader's flat-map
  declaredType instead injects the OUTER shadowed binding's type — 3 new FPs (builder.ts:1814,
  destructuring.ts:114, moduleNameResolver:1950 reshaped) — the precise-type form has zero.**
- **Fix 7: the DebugTypeMapper slice (−10: debug.ts 832–850, the whole family).**
  `type<TypeMapper>(this); switch (this.kind) { case …: this.source }` — FOUR coupled pieces:
  (a) `asserts value is <TP>` binds the TP from the call's EXPLICIT type arguments; (b) an
  assertion on an `any`/`unknown` reference RE-TYPES it to the target (the relation gate
  trivially passes for `any` and kept the useless `any`); (c) `checkMemberAccessMissing`
  consults flow narrowing for `this` receivers (`getTypeOfExpression(this)` is deliberately
  anyType per B101, so the round-418 suppression never applied) and the exhaustive-switch
  receiver typing recovers an anyType receiver through the same re-type; (d)
  `buildNestedFunctionMap` resolves a name collision to the UNIQUE TypePredicate-bearing
  declaration (Debug's `type` is an overload pair — sig + annotation-less impl — and the plain
  "≥2 → ambiguous" rule made the guard invisible to every narrowing consumer; zero or several
  predicate-bearing decls stay ambiguous). The single-file repro cleared in one pass but the
  REAL debug.ts needed (d) — the faithful multi-file repro (barrel import + namespace-local
  overloaded guard) was what exposed it.
- **Residual TS18048×1: checker.ts:21170 (overload-cluster return selection — M3). Next-agent
  note for the classFields.ts:841–859 never×5 sub-cluster: the shape is a De-Morgan early
  return `if (!isPrivateIdentifierClassElementDeclaration(node) || !shouldTransform…) return;`
  whose positive narrowing target `PrivateClassElementDeclaration` is a UNION OF
  brand-INTERSECTIONS (`PropertyDeclaration & { name: PrivateIdentifier }`, …) — the round-418
  positive-collapse fallback is gated `targetType is Type.Intersection` and misses a union of
  intersections, so the filter drops every member → `never`. Extending that gate (or applying
  the member-vs-intersection fold before the drop) is the candidate mechanism — verify with a
  marker first; the negative-exhaustion never pin (instanceofWithStructurallyIdenticalTypes)
  must stay intact. Next TS2339 buckets: never×21 remaining (per-site M3-relation diagnosis, catalogued round 423), DebugTypeMapper×10 —
  now PARTIALLY unblocked: needs `type<TypeMapper>(this)` = `asserts value is T` with an EXPLICIT
  type-arg call (bind T from `expr.typeArguments` — the fix-5 machinery gives the shape), plus
  the TS2339 `this`-branch consulting flow narrowing for path "this" (the round-418 suppression
  excludes `isThisAccess`), plus `this.kind` switch narrowing over the TypeMapper union.
  `Identifier | ComputedPropertyName`×8 stays a measured dead-end (round 423).**

**Round 423 (2026-07-06) — M3.4: exhaustive-switch receiver narrowing (TS2366 → 0) + union-target
type guards + aliased conditions + truthy optional-chain calls. Self-compile (compiler profile)
1,756 → 1,752 → 1,708 → 1,707 (−49 total); suite 9,202 → 9,223 (+21 local, 0 regressions); 3 fix
commits.**
- **Fix 1 (50297e6a): the four round-422 residual TS2366 sites — TS2366 is now ZERO on the compiler
  profile.** Four mechanisms in `requiredUnionDiscriminantKeys`/`enumSwitchKeysFromTypeNode`, exactly
  the round-422 next-agent note's plan: (a) the discriminant RECEIVER is guard-narrowed via the
  pass-dedicated `implicitReturnFlowGraph` (lifted into `currentFlowGraph` only around the walk —
  the arithmetic-pass landmine pattern), so `if (!target) return;` drops `undefined` and
  `if (!isNamedEvaluationSource(node)) return false;` narrows a `Node` param down to the union
  (`getAssignmentTargetKind`, `isNamedEvaluation`); (b) a body-local `const target = call()` receiver
  types from the callee's return annotation (`localConstCallInitType`; single-decl + non-overloaded
  gates); (c) an OPTIONAL enum discriminant contributes a required `@undefined` key instead of
  bailing (`getNewLineCharacter` + `case undefined:`); (d) `LiteralToken["kind"]` — an
  IndexedAccessType branch reuses the union-member walk (`createLiteralLikeNode`), depth-guarded.
  10 local tests (GuardNarrowedSwitchReceiverTest) incl. per-mechanism negative controls; one
  first-cut control was WRONG against tsc semantics (a reassigned-`let` receiver: tsc computes
  exhaustiveness on the non-nullish part and flags the ACCESS, so TS2366 stays quiet) — flipped
  with a comment.
- **Fix 2: union-target type guards + aliased conditions (TS2339 117 → 104, TS2322 784 → 756,
  TS2345 −2, TS18048 −1).** THREE coupled pieces: (a) PARSER — `x is A | B` predicates on the
  UNION (tsc parseTypePredicate → parseType); the old `parseIntersectionOrHigherType` truncated
  the target at `A` and the union-continuation wrapped the PREDICATE (`(x is A) | B`) — the return
  annotation wasn't a TypePredicate at all, so every union-target guard (`isCallOrNewExpression`,
  `isPropertyNameLiteral`, `isOptionalChain`) silently never narrowed; (b) ALIASED CONDITIONS
  (tsc `narrowType` inlineLevel): `const isJsxOpenFragment = isJsxOpeningFragment(node);
  if (!isJsxOpenFragment) { node.tagName }` (the JsxCallLike ×12 family) — the alias initializer
  is recovered by a memoized value-preserving flow BACK-WALK that bails on branch/loop/call/start
  nodes and on reassignment of the alias or the walked root (the const-ness proof); the UNCACHED
  first cut ran the self-compile 4×+ slower — killed and memoized (`aliasedConditionInitCache`,
  keyed by start-FlowNode identity, immune to the cross-file nodeKey collision); (c) the predicate
  union filters consult the round-411 `.kind` key space — PROVABLY DISJOINT keys beat the
  too-lenient relation (enum-member kinds resolve to `any`, so `!isJsxOpeningFragment` collapsed
  JsxCallLike to `never`); plus the round-418 narrow-DOWN suppression accepts a narrowed UNION when
  every member resolves the property. 9 local tests (AliasedConditionAndUnionPredicateTest).
- **Measured dead-ends (2 extra self-compile A/Bs, reverted):** a key-SUBSET ⇒ matched verdict
  (1,708 → 1,720 — brand-intersection targets like `CallChain = CallExpression &
  {_optionalChainBrand}` share the kind without being matched by it); the same rule gated to
  plain-object targets + a tsc-faithful positive-empty → `declared & candidate` fallback
  (1,708 → 1,710 — fixed 4 nevers, surfaced a 12-site checker.ts alias-resolution cluster);
  same-SYMBOL union membership (exact no-op — the real-tsc member/target instances are not
  symbol-identical, so the relation failure is deeper).
- **Fix 3: truthy optional-chain CALL conditions (TS18048 −1, zero site churn).**
  `if (state.referencedMap?.size()) { state.referencedMap.keys() }` (builder.ts:1332) — a nullish
  receiver short-circuits the chain to `undefined` (falsy), so the truthy branch excludes nullish
  from any `?.`-guarded intermediate. A dedicated walk in `applyConditionNarrowing`'s
  CallExpression branch, positive branch only (a falsy chain proves nothing — the receiver may be
  present with a falsy call result, pinned by a local control). 2 local tests.
- **Residual (by-site diff −68/+24 for fix 2 — the +24 catalogued in the session listalls):**
  never×10 (checker.ts 35055/35094/52738/52739 `isAccessExpression`-family positive collapses,
  factory/utilities 1747/1750, classFields 2689, utilities 5445/6840/6843),
  `Identifier | ComputedPropertyName` ×8 (esDecorators/namedEvaluation — the negative branch
  cannot prove `Identifier <: PropertyNameLiteral` on the real types; same-symbol identity ALSO
  fails, so the member instances differ — an M3 relation/instance question), partial narrowings ×5,
  TS2322×1. All are the SAME M3-relation-gap family newly EXPOSED because union-target guards now
  narrow at all — each was previously invisible behind the parse truncation. Next targets:
  TS2339 never×27 remaining, DebugTypeMapper×10 (`asserts value is T` + `this`-path narrowing),
  `string | Diagnostic`×6 (commandLineParser.ts:2016-2032 — TRIAGED, next-agent note: the shape is
  `const text = tryReadFile(…)` (string | Diagnostic via the call-types local recording) +
  `if (!isString(text)) { …; return; }` — every narrowing piece exists (isString is a plain
  single-target guard, the negative branch drops Diagnostic), so the question is WHY the union
  TS2339 emitter doesn't consult it for this receiver — probe with a marker before theorizing;
  candidate suspects: the emitting site may be a different pass without `currentFlowGraph`, or the
  local-const union type reaches the emitter through a path that bypasses
  `getNarrowedTypeForReference`). **TS18048×5 remaining, all triaged with concrete
  mechanisms:** checker.ts:21170 `type.restrictiveInstantiation = instantiateType(…)` then a
  sub-path read — needs `narrowByAssignmentRhs` to accept a CALL RHS whose resolved callee declares
  a non-nullish return annotation (bounded; mind the flowAssignmentMightNarrow keep-in-sync
  landmine); builder.ts:431/433 `canCopyEmitSignatures` — the aliased-condition back-walk bails at
  the closure FlowStart (alias declared OUTSIDE the `forEach` closure, used INSIDE) — needs
  outerFlow-following with the B464 captured-name gates; moduleNameResolver.ts:849 loop-crossing
  narrowing; transformers/ts.ts:2012 generic `Debug.assertNode(node.name, isIdentifier)` (the
  predicate target is an inferred type param — M3.1-adjacent).

**Round 422 (2026-07-06) — M1.12/M3.4: FIVE bounded FP-safe fixes from a fresh full `--listAll`
bucketing — overload-arg flow narrowing, optional-chain discriminants, mixed enum/literal
discriminant keys, boolean-literal overload narrowing, and union-`.kind` exhaustive switches.
Self-compile (compiler profile) 1,799 → 1,756 (−43, zero new codes); suite 9,178 → 9,202 (+24 local, 0
regressions); 5 fix commits (be6f0645, d504a6c3, fc9780c4, 44cee15e, 02764aaf).** Method (the
M1.12 note): fresh `--listAll` at HEAD reproduced 1,799 exactly; bucketing by normalized shape
put the M3 cores on top (TS2322×784 / TS2345×396 / TS7006×301) with TS2769×60 the biggest
un-triaged non-core family — and sampling its sites found FOUR bounded mechanisms plus a
deferred-list TS2366 slice that round 415's key-space work had just unblocked:
- **(1) overload arg-check flow narrowing (TS2769 60 → 47, −13; be6f0645):** the five overload
  arg-check helpers typed args with raw `getTypeOfExpression`, unlike the single-signature path
  (B469) — so a guard-narrowed union arg (`containingFile ? getDirectoryPath(containingFile) :
  undefined`, `if (typeof version === "string") version = new Version(version)`; tsc's own
  moduleNameResolver.ts:545 / semver.ts:228) failed EVERY overload → FP TS2769. New
  `overloadNarrowedArgType` (Identifier/PropertyAccess + Union → `getNarrowedTypeForReference`)
  routed through all five helpers. Suppression-only by monotonicity. The first negative-control
  attempt exposed a PRE-EXISTING false-negative family, not a fix bug: assigning a NULLISH
  literal after a guard (`if (x !== undefined) { x = undefined; use(x) }`) does not narrow the
  reference to `undefined` (`narrowByAssignmentRhs` nullish-RHS no-op) — even the var-decl path
  misses it; noted for M3.4, control replaced with an unrelated-guard shape.
- **(2) optional-chain discriminant access proves the receiver non-nullish (TS18048 10 → 7,
  −3; d504a6c3):** `x?.kind === RHS` (true branch) can only hold when `x` is non-nullish —
  `undefined?.kind` is `undefined`, never equal to a non-nullish RHS. tsc's checker.ts:8061/8062
  (`signature.declaration?.kind === SyntaxKind.JSDocSignature && signature.declaration.parent…`)
  + 5332 (the `||`-of-two-optional-discriminants ternary). This resolves round 416's dead-end
  note: (a) the flow DOES route through `narrowByDiscriminantProperty` (via
  applyConditionNarrowing on the `&&`-left FlowCondition) — the pre-416 attempt failed only on
  (b), the literal-only RHS gate: the fix gates on "RHS definitely non-nullish"
  (`rhsDefinitelyNonNullishForDiscriminant`: enum member OR non-null/undefined literal), and the
  nullish-drop SURVIVES the per-member filter bail (members without readable annotations are
  kept — including the nullish intrinsics, which was the whole bug). Positive branch only.
- **(3) mixed enum + string-literal discriminant unions (TS2339 134 → 117, −17; fc9780c4):**
  tsc's PrivateIdentifierInfo (`kind: PrivateIdentifierKind.Accessor | … | "untransformed"`,
  classFields.ts ×~19 sites) — the literal-typed member had NO representation in the round-411
  enum key space, so it survived every enum-member case and the over-wide union FP'd TS2339 on
  variant props. String-literal discriminants now carry disjoint `lit:s:` keys
  (`literalDiscriminantKeyOfType`; `enumMemberKeysOfTypeNode` LiteralType branch — which also
  serves the equality path — plus `narrowBySwitchClause`'s enum path accepting all-convertible
  literal cases, still gated ≥1 genuine enum key so pure-literal switches stay on the
  corpus-pinned assignability path). Deliberately string-ONLY and namespace-DISJOINT: a string
  enum member never equals a plain string literal in tsc narrowing, but numeric enums ARE
  number-comparable → numeric literals stay unrepresented (member conservatively KEPT, matching
  tsc), pinned by a local test.
- **(4) boolean args vs literal `true`/`false` overload params (TS2769 −2; 44cee15e):** our
  `boolean` is not modeled as `true | false`, so fix (1)'s Union gate couldn't refine tsc's own
  `if (!allowAmbiguity) … parseParametersWorker(flags, allowAmbiguity)` (parser.ts:5453/5460,
  overloads on literal `true`/`false` params). `overloadNarrowedArgType` now narrows a synthetic
  `true | false` union for a bare-boolean reference arg, accepting only a single-literal result.
- **(5) union-`.kind` exhaustive switches (TS2366 12 → 4, −8; 02764aaf):** rounds 414/415
  deferred "Pattern C2's discriminated-union half" as the larger M3.4 slice — fix (3)'s key
  space unlocked its FP-safe subset: `requiredUnionDiscriminantKeys` claims a `switch (x.kind)`
  exhaustive ONLY when the receiver resolves to a UNION whose EVERY member contributes a
  complete key set from a REQUIRED (non-optional) declared annotation (enum members and/or
  string literals; multi-valued `kind: K.B | K.C` contributes both), and every case converts.
  Any gap — optional `kind?:`, nullish receiver, unreadable/numeric annotation — bails and
  TS2366 STANDS. tsc's own `getMappedType` (TypeMapper) / `getAssignmentTargetKind`. An
  Identifier receiver resolves via its PARAM ANNOTATION first (this pass has no param scope in
  getTypeOfExpression — the first cut was inert until that mirror of requiredEnumSwitchKeys'
  own rule). Strong negative controls per the round-414/415 doctrine (`.errors.txt` disabled =
  the corpus is a weak gate here): missing-member / optional-kind / `| undefined`-receiver all
  still fire.
24 local tests across 4 new files (OverloadArgFlowNarrowingTest ×8,
OptionalChainDiscriminantNarrowingTest ×5, MixedEnumLiteralDiscriminantTest ×5,
UnionKindDiscriminantExhaustiveSwitchTest ×6). Bench rows: 1,766 @ fc9780c4 (fixes 1–3) and 1,756 @ 02764aaf (fixes 4–5), both in bench/self-compile-tsc.tsv. Perf: self-compile time in the
~100–131 s single-run variance band (round 413 note). **META (process): the patch-split
protocol worked well for landing multiple checker fixes from one working tree as separate
bisectable commits (git diff → split hunks by marker → checkout → apply per fix), with the
full suite gating each tree state that got committed. And the fastest repro loop for checker
work is a scratch mini-project run through the compiled CLI (~400 ms/iteration), not a gradle
test cycle.** Residual: TS2769×~45 (generic call-site inference — createNodeArray/
createImportAttributes chains, `Program | T` generic-union callees, lib includes() chains →
M3.1), TS2339×117 (never×29 via alias-collapse, JsxCallLike×12 alias-of-alias unions,
DebugTypeMapper×10 `this`-narrowing, `string | Diagnostic`×6 → M3/M3.4), TS18048×7
(assignment-in-guard variants, deep property paths), TS2366×4 (utilities.ts/nodeFactory.ts —
DIAGNOSED, next-agent note: these need the switch RECEIVER guard-narrowed before
`requiredUnionDiscriminantKeys` reads it — `isNamedEvaluation`'s `node` is a bare `Node` param
narrowed only by the `isNamedEvaluationSource(node)` early-return, and `getAssignmentTargetKind`'s
`target` is a call-initialized LOCAL (`const target = getAssignmentTarget(node)`) invisible to this
pass, narrowed by `if (!target) return`. The fix needs (a) a DEDICATED flow-graph field set in
`checkImplicitReturns`' per-file loop and lifted only around the narrowing call — NOT
`currentFlowGraph` for the whole pass, the arithmetic-pass 78-test landmine — and (b) for the
local-const case, initializer typing from the callee's return annotation), and the M3 cores
TS2322×784 / TS2345×396 / TS7006×301.

**Round 421 (2026-07-06) — maintenance (owner-requested): CLAUDE.md trim + root history reorg.
No code changes; suite re-verified green; 3 commits (c3c9c8c1, 396ce8ae, + docs).** The owner asked
whether CLAUDE.md should shrink and whether root-folder history should move. Findings + actions:
- **CLAUDE.md had silently regrown to 594 KB / ~147k tokens** (3.5× the 170 KB cap its own format
  rule set at the 2026-06-10 audit) — loaded into EVERY session's context, ~25%+ of a working
  budget, with measurable task-success cost per the arxiv note in the file itself. Rounds 361–420
  each appended 1–2 KB and nobody enforced the cap.
- **Phase 17 residency criterion applied** (the trim's principle, now codified in the file's rules):
  KEEP cross-cutting architecture of live subsystems, process/build traps, and measured negative
  knowledge; ARCHIVE per-test/per-walker corpus-pin documentation — its protection is the
  always-green 2-minute corpus gate + the walker's own code comments, NOT agent memory, and Phase 17
  doctrine deletes those walkers as the engine supersedes them (the deleter greps by name).
- **250 of 650 entries (316 KB) → docs/history/CLAUDE-GOTCHAS-ARCHIVE.md**; CLAUDE.md 594 → 280 KB
  (−53%, ~70k tokens). A distilled "Measured dead-ends" block preserves the headline negative facts
  whose parent entries archived (variance-in-relation-engine DEAD ~263 regressions; B153 general
  property-receiver fallback not viable; tuple-`?` discarded by the parser; `@typedef` never bound;
  weak-type rule not in the relation engine). New rule: grep the archive BEFORE modifying/deleting a
  dedicated walker or working in a frozen subsystem.
- **Root .md files 19 → 7**: STATUS-HISTORY (1.5 MB), PLAN-PHASE-4-HISTORY (4.1 MB),
  PLAN-PHASE-5-HISTORY, PLAN-PHASE-3(-done), PLAN.md, NEXT-SESSION.md, FAILURES.md, DESIGN-*.md,
  ANALYSIS-A0, TYPESCRIPT-TEST-HARNESS.md → docs/history/. Path couplings updated:
  scripts/find_candidates.py + scripts/mine_small_diffs.py (both smoke-tested), CLAUDE.md
  trim-on-write/workflow pointers, STATUS.md, PLAN-PHASE-4.md. PLAN-PHASE-4.md itself STAYS at root
  (its "Known architectural blockers" section is the live M3 reference).
- **Trim-on-write now targets docs/history/ paths** — future round notes trim there.

**Round 419 resolved that DEFERRED intersection-arm gap (self-compile 1,854 → 1,808, TS2339
  189 → 143): `getPropertyOfType` has no Intersection branch and `typeHasOwnProperty` bails on a
  `Type.Intersection` member, so `PropertyAccessExpression | (ElementAccessExpression & Declaration
  & {…})` FP'd TS2339 on a property inherited by the intersection arm (~28 binder.ts/utilities.ts
  sites). Fixed with `resolveMemberPropertyType` (folds an intersection member's constituents),
  wired into the B83.4e union-member fold + `checkMemberAccessMissing`'s `memberHasIt`; plus
  `discriminantPropAnnotation` now reads the `.kind` annotation from intersection constituents so a
  `switch (node.kind) { case … }` filters an intersection member (else the 1st cut left it in the
  narrowed union → 3 new FPs on the case-body property). 0 new FPs; self-compile time −17%
  (reclaims round 418's +17%); +4 local tests (IntersectionMemberPropertyTest).**
  **Round 420 resolved TYPE-ALIAS enum-member discriminants in narrowing (self-compile 1,808 →
  1,799, TS2339 143 → 134): a `.kind: <type-alias-of-enum-members>` discriminated-union member
  (`ProjectReferenceFile.kind = ProjectReferenceFileKind = FileIncludeKind.Source |
  FileIncludeKind.Output`) survived a `switch (x.kind) { case … }` because `enumMemberKeysOfTypeNode`
  handled only a direct `Enum.Member` (QualifiedName), not a bare-Identifier alias — resolve +
  recurse the alias body (mirroring round-415's `enumSwitchKeysFromTypeNode`), depth-guarded. 0 new
  FPs; +1 local test. Residual discriminated-union TS2339 (anonymous TypeMapper `{ kind: any }`
  union, `PrivateIdentifier*Info`) is `.kind`-narrowing on ANONYMOUS/`any`-kind members — a harder
  M3.4 slice.**
  **Round 422 killed FIVE bounded families (1,799 → 1,756, −43; see the session note): overload
  arg-check flow narrowing (TS2769 60 → 47 — the five helpers now route through
  `overloadNarrowedArgType`, mirroring B469), optional-chain discriminant receiver proof
  (TS18048 10 → 7 — `x?.kind === <non-nullish RHS>` drops nullish members, resolving round
  416's dead-end), mixed enum + string-literal discriminant keys (TS2339 134 → 117 —
  PrivateIdentifierInfo's `kind: "untransformed"` joins the key space as disjoint `lit:s:`
  keys; numeric literals stay conservatively KEPT since numeric enums are number-comparable),
  boolean-vs-literal-overload narrowing (TS2769 47 → 45 — a synthetic `true | false` union for
  bare-boolean args, tsc's parseParametersWorker), and the deferred Pattern-C2
  discriminated-union half (TS2366 12 → 4 — `requiredUnionDiscriminantKeys` proves a
  `switch (x.kind)` exhaustive from REQUIRED member annotations, any gap bails). Residual
  bounded pool: TS2769×45 generic call-site inference (createNodeArray/createImportAttributes,
  `Program | T` generic-union callees — M3.1), TS2339×117 (never×29 alias-collapse,
  JsxCallLike×12 alias-of-alias, DebugTypeMapper×10 `this`-narrowing), TS18048×7
  (assignment-in-guard variants, deep property paths), TS2366×4. NOTED false-negative family
  (M3.4): assigning a NULLISH literal after a guard (`if (x !== undefined) { x = undefined;
  use(x) }`) does not narrow the reference to `undefined` — `narrowByAssignmentRhs`'s
  nullish-RHS branch is a no-op, even on the var-decl path.**

**Round 420 (2026-07-06) — M1.12: resolve TYPE-ALIAS enum-member discriminants in narrowing.
Self-compile (compiler profile) 1,808 → 1,799 (−9, TS2339 143 → 134); suite 9,177 → 9,178 (+1
local, 0 regressions); 1 fix commit (47c655c8).** After round 419, re-bucketing TS2339 by receiver
put the discriminated-union families next (`ProjectReferenceFile | AutomaticTypeDirectiveFile` ×9,
`PrivateIdentifier*Info`, the TypeMapper `{ kind }` union). The `ProjectReferenceFile` family is a
`switch (reason.kind) { case FileIncludeKind.AutomaticTypeDirectiveFile: reason.typeReference }`
where our narrowing kept `ProjectReferenceFile` alongside `AutomaticTypeDirectiveFile` because
`ProjectReferenceFile.kind` is `ProjectReferenceFileKind` — a **type ALIAS** to
`FileIncludeKind.Source | FileIncludeKind.Output`, not a direct `FileIncludeKind.X`.
`enumMemberKeysOfTypeNode`'s TypeReference branch (`discriminantPropAnnotation` → narrowing) handled
only a `QualifiedName` (`Enum.Member`), so a bare-Identifier alias returned null → the member's
`.kind` read as unknown → it was conservatively KEPT → the over-wide union FP'd TS2339 on
`.typeReference`/`.packageId`. Fixed by resolving + recursing the alias body (mirroring round-415's
`enumSwitchKeysFromTypeNode`, which already did this in the TS2366 context), depth-guarded (≤8). 0
new FPs; the `ProjectReferenceFile` bucket 9 → 1. +1 local test (EnumDiscriminantNarrowingTest's
round-420 case). Self-compile time noisy single-run (119 s vs round-419's 101 s — a tiny
alias-resolution addition can't add 18%; the ~100–120 s band is single-run variance on the small
profile, per round 413). **META: continues the round-419 lesson — the discriminant-reading gap must
be closed at EVERY narrowing site AND for EVERY discriminant SHAPE (direct `Enum.Member`,
intersection-member, and now type-alias-of-enum-members); the remaining discriminated-union TS2339
(the anonymous TypeMapper `{ kind: any }` union, `PrivateIdentifier*Info`) are `.kind`-narrowing on
ANONYMOUS/`any`-kind members, a harder M3.4 slice.**

### Mission & strategy

Three strategic reads that shape everything below:

1. **Compliance and performance are the same road for the first 90%.** We run
   ~26 kLOC/s on corpus-shaped code but ~0.7 kLOC/s on tsc's own source — the 40× gap
   IS the false-positive paths (wasted relation checks, elaboration-chain construction,
   hundreds of per-file pin walkers). Killing FPs is the biggest available perf
   optimization, which is why "fully compile first, optimize second" is also the
   correct engineering order.
2. **The pin-walker strategy won Phase 16 and cannot win Phase 17.** Corpus-unique
   suppress-and-reemit walkers were rational for byte-exact baseline matching;
   arbitrary code never matches their gates. Phase 17's core is replacing pinned
   behavior with the real engine — with the green corpus as a permanent regression
   net, and pins **deleted** as the engine supersedes them (each deletion suite-gated,
   in the same commit as the superseding feature when practical).
3. **You cannot steer without a real-world metric.** The corpus count is saturated at
   100%; the Phase 17 dashboard is per-project FP counts, emit diffs, crash count, and
   throughput. `scripts/bench-compile-tsc.sh` + `bench/*.tsv` are the seed.

### Ground rules (delta vs Phase 16)

- The corpus suite stays a **hard zero-regression gate** forever: full suite green
  before every commit (`rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest`).
- The **success metric is the dashboard** (below), not the corpus count. STATUS.md
  tracks both.
- **Local corner-case tests per fix** (Phase 16 protocol step 2) still applies.
- **Never-crash doctrine**: any crash/hang/OOM on any input is a P0 — insert a repro
  item at the top of the queue.
- **Pins are deletable**: when an engine feature makes a corpus-unique walker
  redundant, delete the walker (suite-gated). Track net walker count in session notes.
- Everything else in CLAUDE.md § "Execution protocol" (promote-unblocker default,
  one-commit-per-substep, session notes, trim-on-write, guardrails) applies unchanged.

### Approvals granted by the owner (2026-07-02, "the last mile" → this plan)

- **Conformance-suite adoption** (test-generation change): extend
  `generateTypeScriptTests` to `tests/cases/conformance/<category>` subsets, staged
  per category, keeping the tsgo set-B filters (incl. `tsconfigInTestUsesRemovedFeature`).
- **Real-lib migration**: replace the embedded simplified lib with the real
  `typescript-repo/src/lib/*.d.ts` files (110 files, verified present offline).
- **Differential testing against real tsc** (network needed): install node +
  typescript@6.x when available; vendor real projects (zod etc.) as fixtures.
- Still user-gated: Gradle/dependency changes beyond these scopes; re-enabling the
  native target build config is pre-approved as part of M5.

### The dashboard

| Metric | Source | Phase 17 target |
|---|---|---|
| Corpus suite | jvmTest XMLs | green forever (8,842 / 0 / 3 at phase start; 9,251 with local tests as of round 424) |
| Self-compile FPs (tsc src/compiler) | `bench/self-compile-tsc.tsv` | 13,245 → 0 (**1,186 measured at round 428**; M1 complete at 2,726/round 389; rounds 395–427 burned bounded histogram-tail buckets + M3.4 flow-narrowing slices 2,726 → 1,577; round 428 opened the M3.1 core burn-down −391 (nullable-union generic param inference + overloaded generic callees for the `append` idiom, TS2322 751 → 501; this-param binding + guarded optional-member args + enum→number + body-local-shadows-function, TS2345 394 → 261; array-vs-union-member string layer); round 424 seven flow-narrowing fixes −45 (loop-entry union suppression w/ STRUCTURAL wash gate, call-RHS return-annotation narrowing, closure/join/call-crossing aliased conditions, prefix-path receiver guards, asserts-with-inferred-TP test-arg inference, assignment-overwrite reset to the declaration/call-RHS resolved type, DebugTypeMapper this-narrowing — every step by-site strictly removals); rounds 422–423 −92 (overload-arg flow narrowing, optional-chain discriminants, union-target guards end-to-end, exhaustive-switch receiver narrowing → TS2366 ZERO, aliased conditions); round 420 TYPE-ALIAS enum-member discriminant narrowing (M1.12) −9 (a `.kind: <alias>` member survived a `switch (x.kind)` because `enumMemberKeysOfTypeNode` handled only a direct `Enum.Member`; 0 new FPs); round 419 INTERSECTION union-member property resolution (M1.12) −46 (TS2339 189 → 143: `getPropertyOfType`/`typeHasOwnProperty` bail on a `Type.Intersection` member — fold the constituents in property resolution + discriminant-narrowing; 0 new FPs, self-compile time −17%); round 418 NESTED type-guard resolution (M1.12) −48 (TS2339 237 → 189: tsc's `isTupleType`/… guards are nested in `createTypeChecker` so the binder skips them and `resolveFlowCalleeDecl` missed them — program-wide unique-name fallback + a `Type.Union`-gate-bypassing narrow-DOWN suppression + an intersection-target positive-collapse fallback; 0 new FPs; the negative-exhaustion never of `instanceofWithStructurallyIdenticalTypes` stays intact); round 417 namespace-local `extends`-base resolution −2 (coordinated across `getTypeFromBaseTypeExpression` + `lookupInstanceMemberInResolvableChain`, FP-safe); round 409 `export *`-barrel / ESM-`.js` imported-guard FLOW narrowing (M3.4) −175 (TS2339 838 → 672); round 411 enum-member discriminant narrowing + type-guard-narrows-member-DOWN −59; round 412 single-type type-guard narrow-DOWN + TS18048 receiver-narrowing −1; round 413 the `export *` LEAF-EXPORT gate −407 (TS2339 614 → 237): the pre-413 star resolver returned non-exported IMPORT aliases, so barrel-imported `Debug.assert` (& every barrel guard) never resolved — the TRUE builder.ts blocker, NOT the round-412 depth red herring (an instrumented run showed ZERO walk truncations) — plus a dashboard-neutral tsc-faithful linear flow-walk iteration + a return-path narrowing consumer (−1); **round 414 the TS2366 "lacks ending return" family −35 (50 → 15): three CFA fall-through patterns in `statementAlwaysReturns`/`switchAlwaysReturns` — infinite-loop-with-return, trailing never-call (`Debug.fail`), switch fall-through — all FP-safe syntactic/barrel-resolution fixes; the remaining 15 are Pattern C2 (exhaustive switch w/o default → M3.4 discriminant-exhaustiveness)**; remaining bounded pool M3.4/M3-gated (a general-`resolveAlias` `.js`/star fix was measured net +297 via a TS2315 flood, reverted; NonNull-strip −17 but unmasks M3, reverted; const-string-enum→`string` relation deferred M3.3/B425); no-stub stays the honest default) |
| Project corpus FPs (services/server/…) | `bench/` TSVs (M0.1) | 0 — **the v1 exit** (all 8 profiles) |
| Conformance adoption | generated-test counts per category | POST-V1 (re-scope 2026-07-03 — see § "Post-v1 backlog", M3.0) |
| Crashes on any input | bench runs | 0 |
| Throughput (self-compile) | `bench/self-compile-tsc.tsv` | ≥ corpus-shaped ~26 kLOC/s (M5: numeric targets vs tsc/tsgo) |

### QUEUE — work top-to-bottom; promote unblockers per protocol

- [x] **P0 — services-profile compile hang: exponential narrowing re-entry.** DONE
  (round 385, 349dc97b + 40d33b58): the predicted re-entry exponential, with a twist —
  `parseType()`'s AssertsKeyword branch ERASES `asserts x is T` to bare `T`
  (`TypePredicate.assertsModifier` is never constructed), so ALL the exponential
  callee-resolution work concluded "not a predicate" every time (assert narrowing has
  been inert since round 43 → M1.5). Fix mirrors tsc checker.ts: arg-path pre-check
  before any callee resolution; per-outermost-request callee-decl memo
  (`narrowWalkDeclCache`, tsc `links.effectsSignature`); per-invocation flow-node memo
  (tsc `sharedFlowNodes`) with the `depth <= cachedDepth` serve rule + clean-only
  stores (byte-identical to pre-fix truncation semantics); live-depth (2000, tsc
  `flowDepth`) + 1M cumulative-visit budgets shared across re-entries via the
  `narrowLiveDepth` field. services: hang → 563 s / 7,173 errors; compiler profile
  byte-identical 4,484 at −35.8% compile time; server + harness first baselines landed
  (M0.2 now 8/8). AssertNarrowingScalingTest pins the invariant (N=120 of the exact
  re-entry shape ≈2^120 visits pre-fix → 0.125 s; controls prove `x is T` narrowing
  still applies). See the round-385 session note + CLAUDE.md gotchas for the budget
  sizing lesson (50k truncated a legitimate walk and grew the dashboard by one FP).

**M0 — Real-world measurement rig**

- [x] **M0.1 Project-corpus runner.** DONE (9b5bcd78): `--project` profiles in
  `bench-compile-tsc.sh` — compiler/tsc/jsTyping/deprecatedCompat/typingsInstallerCore/
  services/server/harness (each = named dir + transitive tsconfig-references closure,
  flattened) or `all`/comma-list; per-project TSVs (`self-compile-<name>.tsv`,
  compiler keeps the historical `self-compile-tsc.tsv`); per-project log subdirs +
  multi-project overview table.
- [x] **M0.2 Crash/robustness gate.** DONE (round 384; completed 8/8 in round 385) —
  the gate ran and did its job: round 384 got 5/8 profiles green with tightly-clustered
  baselines (compiler 13,245 err / 298 s; tsc-cli 13,247 / 297 s; jsTyping 13,301 /
  304 s; deprecatedCompat 13,256 / 296 s; typingsInstallerCore 13,348 / 292 s — TS2305
  dominating pre-M1.1; rows in bench/*.tsv), zero exceptions/OOMs; **services HUNG →
  became the P0** (killed after 30+ CPU-min frozen in one statement). Round 385 (P0
  fixed) completed the remaining baselines: services 563 s / 7,173 err / 1,226 MB;
  server 627 s / 7,634 err / 1,139 MB; harness 593 s / 8,164 err / 1,920 MB — all
  files emitted, zero crashes anywhere; same FP families across profiles
  (TS2339/TS7006/TS2345/TS2322 ≈ 85% of every profile's count). Also caught an M0.1
  bug: the src/tsc profile logged into the compiler profile's historical TSV — fixed
  (fabca29d, self-compile-tsc-cli.tsv).
- [x] **M0.3 Fix ProjectCompiler dynamic-import specifier extraction.** DONE
  (f85cc438): the parser records specifiers at the real parse sites into
  `SourceFile.moduleSpecifiers` (tsc's `SourceFile.imports`) — static import/export-from,
  import-equals require, dynamic `import()`/`require()` string-literal calls at any
  depth, `import("...")` types, triple-slash path/types from leading trivia;
  `extractSpecifiers` parses instead of regex-scanning. 6 local tests
  (ModuleSpecifierExtractionTest). Known FN: JSDoc `@type {import("x")}` in .js (no
  structural JSDoc model) — revisit with M4.

**M1 — Kill the systematic FP families**

- [x] **M1.1 TS2305 export-star barrel following.** DONE (8a4ba245): measured
  **13,245 → 4,484 self-compile errors (−8,761, −66%)**, TS2305 gone from the top-codes
  list, compile −2.7% for free. `getModuleExportsFollowingStars` (cycle-guarded,
  depth-bounded, memoized per top-level file; NULL = unknowable → callers skip absence
  emission for non-default names — FN-safe) wired into TS2305/2459/2460/2614/2724 +
  TS2613's upgrade; `export * as ns` contributes its name; re-export branch gained the
  import branch's `.js`→`.ts` fallback; `getModuleAllExports` deleted. 8 local tests.
  Suite 8,856 / 0 / 3, zero regressions.
- [x] **M1.2 TS2563 per-container CFA rule.** RESOLVED in three parts. **M1.2a
  (round 385, 3c4cb60b)**: TS2454 respects the CFA bail (`cfaTooLargeFiles` +
  end-of-init filter; CfaTooLargeBailTest). **M1.2b (round 386)**: NARROW_MAX_DEPTH
  50→2000, aligned with tsc's `flowDepth` guard — the decision experiment measured
  ZERO corpus churn (8,861/0/3) and a **−63% self-compile time** (185.8→68.3 s, RSS
  −325 MB): the 50-cap truncated most deep walks, and truncated subtrees are never
  memo-stored, so the cap itself caused the recomputation storm. Deeper walks also
  complete 2 more narrowings that an arg-check consumer turns into TS2345 FPs
  (utilities.ts:11604/11859 — tracked under M1.4). **The TS2563-EMISSION half is
  FOLDED into M3.4** (measured, not assumed): tsc fires TS2563 on largeControlFlowGraph
  because checking each `data[0] = 0` statement walks the evolving array's flow AT THE
  USE SITE — flow-based reference typing, exactly the M3.4 capability; none of our four
  narrowing consumers ever walks that file deep, so faithful walk-exhaustion emission
  is impossible until then. Until M3.4, B399's per-file node-count heuristic stays
  (its 27 self-compile TS2563 FPs remain on the dashboard). **SUPERSEDED (round 426):
  the faithful depth-trip landed early (the narrowing walkers ARE deep flow walks, so
  trip detection didn't need full M3.4) — B399 proxy + `cfaTooLargeFiles` deleted, the
  27 FPs gone; see the round-426 session note.**
- [x] **M1.5 Activate `asserts` predicates end-to-end.** DONE (round 386, eaa27a90):
  parser builds `TypePredicate(assertsModifier=true)` (`asserts x [is T]` /
  `asserts this`); asserts returns resolve to VOID (getTypeFromTypeNode /
  getTypeNodeName / resolveSimpleTypeName — a return-less bodied assert fn draws no
  TS2355/TS2366/TS7030); `narrowByAssertCall` live for the first time — `is T` target
  narrowing, `is NonNullable<T>` as nullish exclusion, bare `asserts cond` via
  `applyConditionNarrowing` (the `Debug.assert(x !== undefined)` shape); the round-385
  pre-check widened to path-containment (`argMentionsReferencePath`, iterative,
  bails open) per the firewall gotcha; `resolveFlowCalleeDecl` resolves namespace-member
  callees (`Debug.assert` — receiver types as `any`, so property-method resolution
  missed it); `callHasTypeGuardArg` gates `!assertsModifier`. 8 local tests
  (AssertsPredicateActivationTest) with negative controls. Suite 8,869 / 0 / 3.
- [x] **M1.5b Assert narrowing "inert on self-compile" — PREMISE FALSIFIED by test
  (round 386).** A ProjectCompiler repro (AssertsBarrelResolutionTest: namespace
  assert imported through an `export * from` barrel, exactly tsc's
  `_namespaces/ts.ts` topology) narrows CORRECTLY — barrel/alias resolution was
  never the blocker; the 3 tests now pin it. The real reason the M1.5 delta was
  small: sampling the actual TS18048 FPs showed they are ASSIGNMENT-narrowing
  shapes, not assert shapes (`context.pragmas = new Map() as PragmaMap;` then use;
  `result.extendedSourceFiles ??= new Set()`). Addressed the same round:
  **assignment-effect narrowing** — the walkers' shared `narrowByAssignmentRhs`
  adds non-nullish-structural-RHS exclusion (new X / object, array literal / fn
  expr / class expr / template / non-nullish literal, through value-preserving
  wrappers) for `=` and `??=`/`||=` on identifier AND property-path targets
  (`&&=` deliberately excluded — a nullish LHS survives it), with cheap pre-gates
  before any path-string building; Flow.kt binds FlowAssignment for COMPOUND
  assigns on property LHS (plain `=` property targets already had nodes — a
  stale walker comment claiming otherwise cost a first-cut duplicate `when` arm
  that shadowed the real one, dropped the LHS read-records, and regressed
  this-before-super + instanceof narrowing until the suite gate caught it).
  `flowAssignmentTargetsName` (TS2454-shared) untouched. 7 local tests
  (FlowAssignmentNarrowingTest) + per-family bench delta in the session note.
- [x] **M1.3 `types` / `typeRoots` / `@types` resolution.** DONE (round 387,
  473cc0d0 + eed2b73c): ProjectCompiler acquires type libraries like tsc — effective
  roots = `typeRoots` (config-dir-relative) when specified, else every
  `<ancestor>/node_modules/@types` walking up from the config dir; included set =
  `types` when specified (an EMPTY list disables acquisition — the null-vs-empty
  distinction is load-bearing, see the new CLAUDE.md gotcha), else auto-discovery of
  existing packages (scope dirs expand to their subdirectories, dot-dirs skipped);
  entries resolve package.json `types`/`typings` → `index.d.ts`
  (`ModuleResolver.resolveTypeRootPackage`, DefinitelyTyped `scope__name` mangling
  probed for scoped requests) and SEED the import-graph walk (their own imports +
  `/// <reference types>` directives follow); an explicitly requested name that
  resolves nowhere reports TS2688 (byte-exact tsc message). 9 local tests
  (TypesAcquisitionTest) pin inclusion AND exclusion via ambient-global-only packages
  (reachable only through acquisition). Bench gained `--node-stub` (minimal any-typed
  @types/node; toggles without --fresh; rows auto-labeled "+node-stub"). Self-compile:
  no-stub control EXACTLY 4,456 (acquisition inert under `types: []`); with stub
  4,456 → 4,411 (TS2591 43→0, TS2304 3→0, TS2552 4→5 — the 46 resolved names free the
  global 10-lookup suggestion budget so all 5 SetIterator/MapIterator sites carry
  suggestions; ZERO new codes). No-stub stays the honest dashboard default until
  network provides real @types/node.
- [x] **M1.4 Re-measure + strategic map.** DONE (round 387) — full `--listAll`
  family analysis of the compiler profile (4,411 sites bucketed by code × file ×
  message shape × source line) + fresh services/server/harness rows; the map and
  per-family numbers are in the round-387 session note; the top-3 re-ranked
  families are M1.6–M1.8 below (plus two absorbed observations: the
  TS2339-on-union-receiver predicate-narrowing family ~460 sites → noted in M3.4;
  `SetIterator`/`MapIterator`/`RegExp`-replace-overload lib gaps → M2 markers).
- [x] **M1.6 Contextual typing of object-literal fn-valued members (the TS7006
  kill).** DONE (round 388, 0e38be5a + the M1.6(a) commit): (b) landed first —
  `contextualCallableArity` suppresses TS7006 up to a plain callable contextual
  slot's arity (rest = unbounded; beyond-arity keeps firing per B224) in the
  implicit-any walker's arrow/fn-expr/object-literal-METHOD branches; the real
  factory shape turned out to be the VAR-DECL annotation (`const checker:
  TypeChecker = {...}` — the plumbing existed, only union-with-primitive slots
  suppressed before), plus NEW return-annotation threading
  (`returnCtxAnnotation` through `checkImplicitAnyInStatements`, reset per
  function boundary, resolved lazily at the ReturnStatement). FP firewall found
  by the suite gate: members reached through a union-with-non-object literal
  context get NO arity suppression (`ctxViaUnionWithPrimitive` —
  contextualOverloadListFromUnionWithPrimitiveNoImplicitAny pins it). (a) the
  computed-enum-key mapped table (visitorPublic ×810): AST-side
  `mappedAnnotationValueFnArity` (annotation → alias → MappedType → value alias →
  FunctionType arity) drives computed-key members via the threaded
  `ctxAnnotation` node — no mapped-type engine work needed. 13 local tests
  (ContextualFnMemberParamsTest). Self-compile: (b) 4,243 → 3,797 (TS7006
  1554 → 1111); (a)+M1.8 delta in the round-388 note.
- [x] **M1.7 Two bounded engine bugs, 3-digit combined count.** DONE (round 387):
  (a) the TS2345 ×65 turned out to be a missing OPTIONALITY rule, not a lost union
  member — the ` | undefined` in the display was our own B51.7 optional-param
  append; the 17.11c Type.Reference nullish-arg branch (and the 17.40 anonymous-fn
  sibling) rejected an explicit `undefined` against an OPTIONAL parameter. Fixed by
  applying B176's rule (absent and undefined are interchangeable for parameters —
  questionToken OR initializer) on the single-signature path; `null` stays checked,
  required params still reject undefined. (b) `getReturnTypeOfNewExpression`:
  EXPLICIT type args on a CONSTRUCTOR-INTERFACE callee (`declare var Map:
  MapConstructor` — no interface-own type params; the generics live on the
  construct sig's return) re-instantiate the sig return's Reference target
  (`new Map<string, number>()` → `Map<string, number>`), bare sig return as the
  arity-mismatch fallback. 8 local tests (OptionalParamAndCtorInterfaceTest) with
  negative controls. Suite 8,896 / 0 / 3; self-compile delta in the session note.
- [x] **M1.9 `undefined` lost against explicitly-undefined-including UNION targets.**
  DONE (round 388, b4c15a22) — over-delivered: −133 (predicted ~75); the
  undefined family is essentially dead (TS2345-undefined 100 → 2, both the
  separate nested-fn-shadowing callee-resolution family; TS2322-undefined
  70 → 0). The item text's hypotheses were both WRONG in instructive ways: the
  union's undefined member was never lost in the relation — FIVE distinct
  emitters were at fault: (1) the RETURN path's legacy string fallback ran even
  after the ENGINE confirmed assignability (B325's engine-confirmed early
  return had never been applied to returns; alias names like `Mode` are opaque
  to the string system); (2) enum-member union aliases (`ResolutionMode`)
  resolve to anyType (any-absorbing union) → engine bails → string fallback —
  fixed by the syntactic `aliasUnionContainsNullishKeyword` skip; (3)
  assignment TARGETS inside `if (x !== undefined)` guards checked against the
  NARROWED type (`narrowedDeclaredTypes` now records the declared type at both
  dispatcher narrowing arms); (4) the main simple-checkable arg path missed
  M1.7a's undefined-to-optional rule (primitive + namespace-nested-fn params);
  (5) the 17.20 bare-TypeParam nullish-arg branch fired for the sig's OWN
  inferable TPs (tsc infers T = undefined). 13 local tests
  (UndefinedVsUnionTargetsTest). Side effect: removing the TS2322s at empty
  `return;` statements SURFACED 8 same-position-masked TS7030 FPs → M1.8.
- [x] **M1.8 TS7030/TS2366 gate audit vs tsc's exact rule.** DONE (round 388,
  d31be6be): read tsc's checkAllCodePathsInNonVoidFunctionReturnOrThrow +
  checkReturnStatement from the offline sources and aligned all three arms of
  `checkBodyForImplicitReturn` — (1) the mixed-return TS7030 arm is
  noImplicitReturns-ONLY (strictNullChecks disjunct dropped); (2) TS2366
  additionally requires `!returnAnnotationAcceptsUndefined` (engine relation on
  a concrete resolution OR the M1.9 syntactic alias-union proof — the
  classifier calls `VisitResult<Node | undefined>` "non-void"); (3) the
  per-empty-return TS7030 (Case 1) is `noImplicitReturns && !strictNullChecks`
  (under strict, an empty `return;` routes through return-expression
  assignability = TS2322, which checkReturnAssignability already owns). The
  "corpus-gated audit" came back EMPTY — zero corpus tests pinned the old
  disjuncts (suite 8,928/0/3 on the first try). Writing the local tests
  (ImplicitReturnGatesTest ×9) surfaced that under strict+noImplicitReturns
  tsc's TS2366 branch wins over TS7030. Self-compile delta in the round-388
  note (combined row with M1.6a).
- [x] **M1.10 Model the `-readonly` mapped modifier (TS2540 ×64 → 0).** DONE
  (round 388, fe65a3cc): the parser consumed `-readonly` without recording the
  sign, and a homomorphic mapped member carries its SOURCE declaration — so
  every write through tsc's `Mutable<T>` idiom
  (`(newSourceFile as Mutable<SourceFile>).flags |= …`) FP'd TS2540.
  `MappedType.readonlyMinus` → `mappedMutableMemberIds` (the inverse of
  `mappedReadonlyMemberIds`), consulted FIRST by the readonly predicates;
  symmetrically the plain `readonly` TOKEN now registers
  `mappedReadonlyMemberIds` (was a silent FN — corpus pinned nothing either
  way). 4 local tests (MutableMappedTypeTest). Self-compile 2,858 → 2,794
  (−64 exactly, zero new codes).
- [x] **M1.11 Nested-function shadowing in call resolution (TS2554 ×45 +
  TS2345 ×2).** DONE (round 389) — over-delivered: self-compile 2,794 → 2,726
  (−68; TS2554 45 → 0, TS2345 −13, TS2769 −10, zero new codes). Site triage
  showed FIVE distinct shapes behind "nested-function shadowing": (a) PARAMETER
  shadowing — identifier, destructured, and fn-typed params (sys.ts's
  `setTimeout`/`getModifiedTime`, utilities.ts's `writeFile`, checker.ts's
  `compareTypes`/`createProperty`) → `minusParamShadowedNames` at every
  fn-body descent of the arity walker; (b) body-local `const`/`let`/`var`
  shadowing (program.ts's `fileOrDirectoryExistsUsingSource`) → the
  `argCountFnDepth`-gated list-level removal; (c) NAMESPACE flattening leak
  (parser.ts's namespace-local 0-param `isExternalModuleReference` hijacking
  the file-level call) → collectFuncDecls no longer flattens ModuleDeclaration
  bodies; the walker's ModuleDeclaration branch collects a body-scoped overlay
  (incl. the extracted inherited-ctor fixpoint); (d) constructor OVERLOADS
  checked against only the FIRST signature (semver.ts's `Version`) → arity
  RANGE + isOverloaded; (e) SPREAD-argument too-few unsoundness
  (`createDiagnostic(...args)` counts 1, expands N) → spread suppresses
  too-FEW (too-many stands). Type path: `populateParameterLocalTypes` infers
  un-annotated fn-valued-DEFAULT params (emitter.ts's `getCommonSourceDirectory
  = (): string => …` passed as an arg — 5 TS2345); `shadowNestedFunctionNames`
  anyType-bails body-nested fns colliding with an outer binding (emitter.ts:1331's
  sibling `writeFile` vs the utilities import). 13 local tests
  (NestedFnShadowingTest), every suppression paired with a negative control.
- [x] **M1.13 `typeParamInternCache` cross-file pos-collision (architectural — a bug class
  the single-file corpus is structurally blind to).** DONE (round 404): the intern-cache key
  is now `internKey(tp)` = `(TypeParameter.internSalt, pos)` packed into a Long, NOT bare `pos`.
  `internSalt = fileName.hashCode()` is stamped by the parser onto every TypeParameter it
  creates (one `.also {}` in `parseTypeParameter` + a `typeParamFileSalt` field), and all 20
  `getOrPut(...)` intern sites now key by `internKey(...)`. Single-file compiles stamp every
  param with the SAME salt → the key is a bijection with `pos` → interning is byte-identical
  (corpus 9,026 → 9,031 with +5 local tests, 0 regressions); multi-file programs get distinct
  salts per file → the cross-file collision (and the factory-site stomping the round-403
  read-site fix did NOT cover) is eliminated at the KEY, exactly as the item mandated. The body
  property is excluded from data-class `equals`/`hashCode`/`copy` (TypeParameter is never
  copied). **MEASURED (the item's explicit "measure after the proper fix"): self-compile
  compiler profile 2,664 → 2,664, by-code map UNCHANGED — the identity-separation hypothesis
  (that some M3-bucket TS2322/TS2345 FPs were stale-constraint artifacts) is FALSIFIED for the
  self-compile; the one observed FP was already fixed at the read site, and the latent factory
  collisions weren't manifesting as self-compile FPs.** Still a principled hardening (removes a
  real latent bug class + the belt-and-suspenders per-call re-resolution is no longer the ONLY
  safety at the read site). Follow-up for the OTHER pos-keyed caches that store per-decl mutable
  state across files (grep `getOrPut(...pos)`) is noted in the CLAUDE.md gotcha. 5 local tests
  (TypeParamInternKeyTest): reverse-order collision, generic-function collision, 3-file
  cross-contamination, single-file corpus-safety, and a negative control (genuine violation
  still fires).
- [ ] **M1.12 Remaining bounded self-compile buckets (the by-shape histogram tail M1
  didn't reach).** After M1, bucket the FULL compiler-profile `--listAll` output by
  NORMALIZED message shape (`re.sub(r"'[^']*'", "'X'", msg)`) — NOT the 30-line log tail —
  to surface bounded non-M3 bugs the code-path triage misses. Round 395 fixed TS2499×16
  (multi-base-generic heritage misparse, parser), round 396 fixed TS2440×10 (type-only
  barrel import + value-only local, checker), and round 397 fixed TS2344×2 of 8 (the
  `createNodeArray<T>()` call-path constraint-chain skip) this way (2,726 → 2,700).
  Round 403 fixed **TS2344×3 more (6 → 3)**, the **SetIterator/MapIterator lib gap
  (TS2552 4→0 + TS2304 3→2)**, and **TS2774×5 (9 → 4)** — self-compile 2,680 → 2,667.
  **Remaining candidates triaged but not done:** (a) **TS2344×3 remaining** — the
  `TPrivateEntry`-vs-`{}` sub-shape (round 403) turned out to be a genuine MULTI-FILE bug:
  `typeParamInternCache` is keyed by absolute AST `pos`, which COLLIDES across files, so an
  unconstrained param inherited a pos-colliding `<X extends {}>` param's stale `{}`
  constraint — fixed by always clearing `.constraint`/`.default` from the current node
  (`checkConstraintsForTypeArgs`; single-file positions never collide → corpus-neutral). The
  3 left are OTHER sub-shapes: `Token<TKind>` where `TKind extends JSDocSyntaxKind` vs
  `SyntaxKind` (enum-subset relation gap — a union of enum members ≤ the enum; risky, B425
  nominal-enum territory) and a UNION arg `TIn | undefined` vs `Node | undefined` (needs
  per-member constraint resolution). **NOTE: the pos-collision class of bug is structurally
  invisible to the single-file corpus — grep the other 20 `getOrPut(tp.pos)` intern sites for
  readers of a stale-constraint shared instance.** (b)
  **TS2693×1 remaining** — round 398 fixed the `symbol`-destructuring shape (×6:
  `checkTypeAsValueInStatements`'s value-name hoisting now extracts binding-pattern element
  names, not just simple Identifier decl names); the 1 left is a different
  `BinaryExpressionState` clodule-namespace-as-value shape (factory/utilities.ts:1477); (c)
  **TS2314×3 → 0 (round 399)** — `checkTypeArgCount` now skips the arity check when a qualified
  name's qualifier resolves to an enum (`SyntaxKind.ThisType`/`TypeMapKind.Array` are enum
  MEMBERS, not the same-named generic lib types); (d) **TS2588×4 → 0 (round 400)** — a nested
  `let`/`var` shadowing an enclosing `const` now REMOVES the name from the inherited const set
  (checker.ts's `compareTypes`); (e) **TS2709×1 + TS2693×1 → 0 (round 401)** — the
  `BinaryExpressionState` `type X` + `namespace X` clodule now resolves as both a type (TS2709
  suppressed via `currentTypeProvidingNames`) and a value (an instantiated namespace added to the
  value set via `isNamespaceInstantiated`); (f) **TS2551×5 → 0 (round 402)** — `Object.setPrototypeOf`
  added to the embedded ObjectConstructor (zero corpus baseline shifts). **Round 405 fixed
  TS2774×1 (2,664 → 2,663): `let shouldElaborateErrors = reportErrors` in checker.ts —
  `reportErrors` is a boolean PARAM, but the uncalled-function check's syntactic pass sets up no
  local param scope, so `getTypeOfExpression(reportErrors)` resolved in file/global scope and
  found the outer `function reportErrors` (a callable) → FP TS2774 on `if (shouldElaborateErrors)`.
  Fix: `collectUncalledTypedLocalsFromBody` types a bare-identifier initializer from the
  uncalled-scope's OWN knowledge of the binding (`shadowed`/`into` for the same scope,
  `isUncalledShadowed`/`lookupUncalledTypedLocal` for an enclosing scope on the stack) rather
  than the unreliable global resolution — a boolean param → boolean (no TS2774), a same-scope
  local FUNCTION → still callable (genuine `let f = localFn; if (f)` keeps firing). 3 local tests
  (UncalledFunctionParamTypeTest).** **Round 406 killed TWO more by bucketing the FULL 2,663-line
  `--listAll` (not the log tail): TS1100×2 (`interface { arguments: … }` — the InterfaceDeclaration
  branch checked the property NAME; a property/method name is never binding-name-restricted) and
  TS7023×2 (`return cond ? mapType(t, self) : concrete` — self as a callback ARG receives a
  contextual param type and breaks the inference cycle; `selfRefsOnlyAsCallbackArgs` gate). Self-compile
  2,663 → 2,659.** **Round 407 (same session) killed TWO arithmetic-pass buckets: (1) TS2365
  21→7 — a local `const length = arr.length` SHADOWING an outer `function length` was typed as the
  function (`i < length` → `number < (…)=>number`); record a const that shadows an outer FUNCTION
  (SHADOW gate load-bearing — recording every primitive const unmasks narrowing FPs on the other
  operand). (2) TS2362 19→15 — a branded number `number & {__brand}` is number-like (intersection
  ⊆ number member); added `Type.Intersection` to the operand classifiers. (3) TS7053 3→1 — an
  enum reverse-mapping `NumericEnum[key]` is valid; excluded the enum-object receiver from the
  empty-object noImplicitAny element-access branch. Self-compile 2,659 → 2,639. A nullish-strip
  in the `NonNullExpression` case (`(T|undefined)! → T`) measured net −17 but UNMASKS M3
  object-literal-vs-interface + generic-inference gaps (program.ts/transformer.ts) → reverted,
  deferred to M3.** **The bounded pool is genuinely thin now — remaining
  candidates + M3-family (self-compile at 2,639 after round 407):** TS2740×1 (the tsc `createSet()`
  Set shim FP: our embedded Set carries the es2024 set-methods `union`/`intersection`/… that es2020
  shouldn't have — gating them behind `LIB_MIN_TARGET` es2024 is risky per the "and N more"
  count-shift gotcha + the `setMethods` corpus test depends on them; DEFERRED), **TS7019×4
  (RECLASSIFIED round 405 from "M1.6 territory" to M3.2-gated):** all four are arrow REST params
  that receive a contextual function type — from an assignment LHS member (`compilerHost.getSourceFile
  = (...args) =>`, `host.writeFile = (…, ...rest) =>`) or a callback-arg param. A round-405 attempt
  to propagate the LHS type into the implicit-any `BinaryExpression` case was a NO-OP and reverted:
  `getTypeOfExpression(compilerHost.getSourceFile)` returns `any` because the implicit-any pass sets
  up NO enclosing-function param scope (`compilerHost` is a param, not in `currentFileLocals`). So the
  fix needs param scopes in that pass (or a real contextual-typing pass) — M3.2, not bounded.
  TS2739×7 (brand-property structural comparison → M3.4), TS2722×3 (property-path narrowing →
  M3.4/M1.5), TS2741×3 + TS2430×1 (brand-property → M3), TS7053×3 (index-sig/implicit-any → M3),
  TS2367×2 (string-enum-vs-string nested-array → M3/B425), TS2394×1. Env-legit: TS2591×43 (node
  globals — `--node-stub`), TS2304×2 (node `global`), TS2563×27 (B399 heuristic → M3.4). M3 cores:
  TS2339×838, TS2322×794, TS2345×405, TS7006×301 — the next real progress is a decomposed
  M3.1/M3.4 sub-step. **Round 408 took exactly such a decomposed M3.4 slice: re-bucketing the
  FULL `--listAll` (not the log tail) put TS2349×25 at the top of the bounded tail, and it fell
  to a callee-position flow-narrowing family — callee flow-narrowing (−13) + `typeof x ===
  "function"` callability filtering (−2) + empty-array contextual assignment (−6), self-compile
  2,639 → 2,618 (TS2349 25 → 5). The 5 remaining TS2349 are M3.4/M3 (unreproducible generic-class
  assert-narrowing ×3, `??=`-call-RHS ×1, union-LHS default-init ×1). Re-confirms: the M1.12
  "M3-gated" verdict is about the LOG TAIL — bucket the full output.** **Round 410 fixed THREE more
  by the same full-`--listAll` bucketing (2,443 → 2,433): TS2862×1 (the B98.r80 generic-index-write
  walker fired for a bare `T extends object` — narrowed `constrainedTpNames` to constraints bearing a
  string/symbol index sig, matching tsc's `NoIndexSignatures` gate), assign-RHS type-guard narrowing
  −8 (TS2739 7→3, TS2741 3→2, TS2322 793→790 — `checkAssignmentExpression` now narrows an
  Identifier/PropertyAccess RHS via `getNarrowedTypeForReference`, suppression-only, for `node = parent`
  inside `if (isParenthesizedExpression(parent))`-style guards), and TS2394×1 (a `void` overload return
  is compatible with any impl return per tsc `isImplementationCompatibleWithOverload`). Two of the three
  were hiding under M3-labeled families (TS2394 under "overload", the narrowing under the brand-property
  bucket). **DEFERRED M3.3/B425: a const STRING enum is not assignable to `string` in our engine (even
  scalar `const y: string = x`, x: E) → the `Extension[][]`/`string[][]` TS2367×2 + TS2322×2 cluster;
  needs string-valued-enum-as-string-like in the relation engine + comparabilityCategory.** **Round 414
  killed the TS2366 "Function lacks ending return statement" family (50 → 15, self-compile 1,965 → 1,930)
  — the biggest bounded bucket, three CFA fall-through patterns in
  `statementAlwaysReturns`/`switchAlwaysReturns`: (A) an infinite loop whose only exits are return/throw
  never falls through (`infiniteLoopFallsThrough` — the old `containsBreakOrReturn` wrongly counted the
  return); (B) a trailing `Debug.fail(...)`/`assertNever(x)` never-call diverges
  (`callHasNeverReturnAnnotation` via round-413's barrel-aware `resolveFlowCalleeDecl`); (C1) switch
  fall-through (a non-empty case completing normally inherits the next clause's guarantee). **DEFERRED —
  Pattern C2 (~15 remaining): an EXHAUSTIVE `switch` with NO `default` over an enum / discriminated-union
  `.kind` — needs type-level discriminant exhaustiveness (the discriminant narrows to `never` after all
  cases), an M3.4 slice.** `.errors.txt` tests are disabled so this whole reachability analysis is
  gated only by the full suite — which is why the 50-FP bucket was invisible on the dashboard.**
  **Round 415 killed TWO more (1,930 → 1,922): (1) TS2362 15 → 10 — a `x!` NonNull arithmetic operand
  now uses the non-null type (`arithOperandType` strips nullish LOCALLY for a syntactic `!`, avoiding
  the round-407 global-strip blast radius); the residual 10 are `&&`/`||`/reassignment flow-narrowing
  (M3.4). (2) TS2366 15 → 12 — the FP-safe subset of Pattern C2: an exhaustive ENUM /
  enum-member-union / call-return-enum switch is terminating (`isExhaustiveEnumSwitch` claims
  exhaustive ONLY when every enum member is provably covered — any uncertainty bails, so no false
  negative; the round-411 barrel-aware enum helpers do the resolution). The remaining 12 TS2366 are
  `.kind` discriminated-union switches (union-of-interfaces/TypeLiterals with per-member `.kind` — the
  larger M3.4 slice, correctly bails). DEFERRED (bounded but broad/risky): the empty-tuple-vs-
  all-optional-tuple TS2739 (moduleSpecifiers `return emptyArray as []`) — `buildTupleFromTypes` builds
  numbered props as required (the resolved tuple `Type` loses the AST `questionToken`/`OptionalType`
  optionality); the clean fix needs a `SymbolFlags.Optional` bit threaded through tuple building + read
  by `isOptionalProperty`, a broad regression surface (many callers) for 1 instance.**
  **Round 416 killed FOUR bounded families (1,922 → 1,904): (1) TS2365 7 → 5 — a `let`/`var`
  local shadowing an outer function (`let min = Number.POSITIVE_INFINITY` shadows `function min` →
  `min < args.length` FP'd `{ <T>(…) } < number`); extended round 407's `const`-only shadow-recording
  to `let`/`var` (records `anyType`, reassignment-proof; the shadow gate is the firewall). (2)
  TS2362 10 → 4 + TS2365 5 → 1 — &&/ternary truthy-narrowing (`checkMode && checkMode & X`,
  `X !== undefined && X > 0`, `X === undefined ? … : start! + X`): new `arithTruthyNarrowedNames`
  strips nullish from an operand narrowed by an enclosing `&&`/ternary guard (a `Type.Union` carries
  no Undefined flag on itself, so the classifier otherwise rejects the undefined member). (3)
  TS18048 16 → 12 — a captured var narrowed by a closure-LOCAL guard before a loop and read INSIDE
  it (checker.ts:8207 `if (!expandedParams) return; for (…expandedParams.length…)`): the
  closure-capture TS18048 emitter now uses the loop-entry-following narrowing variant so the
  pre-loop narrowing survives the FlowLoopLabel (M3.4). (4) TS18048 12 → 10 — assignment-effect
  narrowing based on the DECLARED type: `if (!x.y) { x.y = new Map() } x.y.method()` FP'd for a
  property-path target because `narrowByAssignmentRhs` excluded nullish from the pre-assignment
  narrowed antecedent (bare `undefined`, a no-op) instead of the declared type (an assignment
  overwrites — tsc `getAssignmentReducedType`). All FP-safe / suppression-only; a shared narrowing
  path yet zero regressions. Residual: TS2362×4 (reassignment `flags = flags || None` + generic
  reduceLeft/checkDefined returns) + TS2365×1 (generic `lineCount + T`) + the remaining TS18048×10
  (further assignment-in-guard cases, optional-chain `X?.kind === lit &&` discriminants, deep
  single-use property paths) are M3.4/M3.**
  **Round 417 resolved namespace-local `extends` bases (self-compile 1,904 → 1,902):
  `getTypeFromBaseTypeExpression` (+ `lookupInstanceMemberInResolvableChain`, coordinated) resolve a
  bare-Identifier base through the enclosing namespace before `globals` — a namespace-local base
  (`namespace M { interface Base {}; interface Derived extends Base {} }`) was never inherited, FP'ing
  TS2353 on builderState.ts. FP-safe (strict superset; the this-member chain returns `false` only when
  fully resolvable). The FIRST cut (only the base-expression site) REGRESSED
  genericRecursiveImplicitConstructorErrors3 — once baseTypes is populated the conservative
  "class has base types" this-member TS2339 branch runs and a globals-only base lookup bails on `null`,
  swallowing the expected TS2339; the second site fixes that. DEFERRED: the reassignment-narrowing
  residual (TS2362 `length = end - start` in parser.ts + `flags = flags || None`) is genuinely M3.4
  cross-statement narrowing — the arithmetic pass has no statement-order flow tracking, and a naive
  same-scope reassignment recording has a branch/loop-leak FP surface. TS2740×1 (Set-shim lib),
  TS2416/TS2430/TS7053/TS7031 (M3 assignability/contextual), TS2344 (enum-subset/union-constraint),
  TS2591/TS2304/TS2584 (env-legit node/dom globals), TS2366×12 (`.kind` discriminated-union
  exhaustive-switch, FP-risky with `.errors.txt` disabled) remain the bounded/M3-gated pool.**
  **Round 418 resolved NESTED type-guard functions (self-compile 1,902 → 1,854, TS2339 237 → 189):
  re-bucketing TS2339 by RECEIVER type (`s/does not exist on type '\([^']*\)'/\1/`, the round-411
  method) put "on type 'Type'" ×46 as the biggest sub-family — `isTupleType(x)`/`isGenericTupleType(x)`
  guards then `x.target`. Root cause: tsc's guards are NESTED functions inside `createTypeChecker`
  which the binder skips (B83.5), so `resolveFlowCalleeDecl` missed the callee and the guard never
  narrowed. Fixed with a program-wide UNIQUE-name FunctionDeclaration fallback
  (`uniqueFunctionDeclByName`) + a `checkMemberAccessMissing` single-type narrow-DOWN suppression
  (the receiver-narrowing consumers are all `Type.Union`-gated, so a `Type` → `TupleTypeReference`
  narrow-DOWN never reached the property access) + a `narrowByCallPredicate` intersection-target
  fallback (a POSITIVE guard against `X & {p}` that drops every constituent falls back to the
  antecedent union — declarations.ts's `shouldPrintWithInitializer`; gated `targetType is
  Type.Intersection` so the NEGATIVE-branch genuine-never of `instanceofWithStructurallyIdenticalTypes`
  stays intact). 0 new self-compile FPs; +5 local tests (NestedTypeGuardNarrowingTest). Perf +17%
  (more narrowing walks succeed; M5). The 8 binder/nodeFactory intersection-arm-UNION FPs a broader
  "raw exposes the property → suppress" guard would flip were DEFERRED as an M3 gap (union
  property-access over an intersection-arm member).**

**Round 419 (2026-07-06) — M1.12: resolve properties on INTERSECTION union-members. Self-compile
(compiler profile) 1,854 → 1,808 (−46, TS2339 189 → 143); suite 9,173 → 9,177 (+4 local, 0
regressions); 1 fix commit (39f22170).** After round 418, re-bucketing TS2339 by receiver type put
the intersection-arm unions (`PropertyAccessExpression | (ElementAccessExpression & Declaration &
{…})`, tsc's `BindableStaticAccessExpression`) as the biggest remaining sub-family (~28 sites in
binder.ts/utilities.ts, all PRE-EXISTING). Root cause: `getPropertyOfType` has NO Intersection
branch (deliberately — "modifying it is broad", CLAUDE.md) and `typeHasOwnProperty` bails on a
`Type.Intersection` member (`type !is Type.Object`), so a property INHERITED by the intersection arm
(`.parent` via `Node`) reads as missing and the whole union access FP's TS2339. **TWO coupled pieces
— the 2nd because the 1st EXPOSED a switch-narrowing gap:**
- **(1) property resolution** — new `resolveMemberPropertyType(member, prop)` folds an intersection
  member's constituents (a property exists on `A & B` iff ANY constituent has it — the round-352
  rule, applied per union member instead of only for a direct-intersection receiver). Wired into the
  B83.4e union-member fold in `computeRawTypeOfPropertyAccess` (the property TYPE) AND into
  `checkMemberAccessMissing`'s `memberHasIt` (the TS2339 EMISSION). Minimal blast radius: for a
  NON-intersection member it reduces to the existing `getPropertyOfType`, so only intersection
  members change.
- **(2) discriminant narrowing** — piece (1) ALONE introduced 3 new FPs at utilities.ts:4362/4365/4367:
  a `switch (node.kind) { case Import: case Export: return node.moduleSpecifier }` left an
  intersection member (`BindingPattern & {…}` — a NON-matching `.kind`) in the narrowed union because
  `discriminantPropAnnotation` bailed on the intersection (`getApparentType(member) as? Type.Object`)
  → the member's `.kind` read as unknown → it wrongly SURVIVED the switch → the over-wide narrowed
  union FP'd `.moduleSpecifier`. Folding the intersection constituents in `discriminantPropAnnotation`
  (read the `.kind` annotation from any constituent) filters the member correctly → 0 new FPs.
- **PERF: self-compile TIME −17% (122 → 101 s)** — the more-accurate narrowing + fewer FP
  elaborations RECLAIM round 418's +17% regression, so rounds 418+419 net roughly FLAT on time
  (~104 → ~101 s) while −94 on FPs. BOTH dashboard metrics improved this round. Corpus suite time
  flat. +4 local tests (IntersectionMemberPropertyTest: intersection-arm property resolves +
  switch-`.kind` filters the intersection member + FP-safety: plain-union genuinely-missing still
  fires, partial-coverage still fires). **META: the fix is the documented
  `getPropertyOfType`-has-no-Intersection-branch gap, resolved NARROWLY in the union-member path (not
  `getPropertyOfType` itself — that stays broad-risk); the 1st cut's 3-FP regression is the lesson
  that the same intersection-fold must be applied to EVERY place that reads a member property (both
  property resolution AND discriminant-narrowing annotation), not just the obvious one.** DEFERRED
  (residual, M3): the `.kind`-switch narrowing still doesn't handle a WIDER-declared discriminant
  union (the TS2366 `.kind` exhaustive-switch family); the M3 cores TS2322×784 / TS2345×396 /
  TS7006×301 dominate.

**Round 418 (2026-07-06) — M1.12: resolve NESTED type-guard functions so their narrowing fires.
Self-compile (compiler profile) 1,902 → 1,854 (−48, TS2339 237 → 189); suite 9,168 → 9,173
(+5 local, 0 regressions); 1 fix commit (7a806360).** Bucketing the fresh full `--listAll` by
receiver-type (round-411 method, `s/does not exist on type '\([^']*\)'/\1/`) put **TS2339 "on type
'Type' ×46** as the single biggest TS2339 sub-family — all `isTupleType(x)`/`isGenericTupleType(x)`
user-type-guard narrowing DOWN to `TupleTypeReference` then accessing `.target`. A minimal repro
isolated the root cause (NOT the giant-flow-graph depth the display suggested): tsc's guards are
declared as NESTED functions inside `createTypeChecker`, which the binder does NOT bind (B83.5), so
`resolveFlowCalleeDecl` (`currentFileLocals?.get ?: globals`) returned null → `narrowByCallPredicate`
bailed → the guard never narrowed. A faithful single-file repro with the guard as a NESTED function
reproduced it exactly (0 errors when top-level, TS2339 when nested). **THREE coupled pieces (the
resolver exposes the other two):**
- **(1) the resolver** — `resolveFlowCalleeDecl`'s Identifier branch falls back to
  `uniqueFunctionDeclByName` (a program-wide map name → the UNIQUELY-named FunctionDeclaration at any
  nesting depth, or null when ≥2 share the name; built once by `buildNestedFunctionMap`, an iterative
  worklist over statement containers / function-method-accessor bodies / namespace bodies / class
  members / arrow-and-function-expr initializers). FP-safe: a colliding name → null → no narrowing
  (conservative), and narrowing only refines / removes union members.
- **(2) the narrow-DOWN consumer** — the receiver-narrowing consumers (`computeRawTypeOfPropertyAccess`,
  the narrowed-to-never TS2339 branch) are ALL gated on `rawObjectType is Type.Union`, so a NON-union
  narrow-DOWN (`Type` → `TupleTypeReference`) never reached the property access. `checkMemberAccessMissing`
  gains a top-of-function suppression: for a pure Identifier/PropertyAccess receiver, if flow
  narrowing yields a strict subtype (`narrowed <: raw`, non-union, non-never) that HAS the property,
  suppress. FP-safe / suppression-only (`narrowed <: raw` ⇒ the subtype carries at least the declared
  members).
- **(3) the intersection-target collapse** — `narrowByCallPredicate`'s POSITIVE union branch drops a
  constituent when it relates to the guard target in NEITHER direction; for an INTERSECTION target
  `X & {p}` (declarations.ts's `shouldPrintWithInitializer(node): node is CanHaveLiteralInitializer &
  { initializer: Expression }`) EVERY member drops → collapse to `never` → FP TS2339 on `.initializer`.
  Fall back to the antecedent union, narrowly gated `narrowed.isEmpty() && targetType is Type.Intersection`.
- **THE REGRESSION THAT SHAPED THE FINAL FORM (1 corpus fail on the first full-suite run):
  `instanceofWithStructurallyIdenticalTypes` EXPECTS `TS2339: Property 'item' does not exist on type
  'never'` at `x.item` — a GENUINE never** (union `C1 | C2 | C3` where C1≡C2≡C3 structurally, so
  `else if (isC3(x))` after `!isC1 && !isC2` narrows to `never` via the NEGATIVE branch, and tsc
  reports there). A blanket never-fix (any positive-empty → union) and a "raw declared type exposes
  the property → suppress" guard BOTH over-suppressed it. The distinguisher is **positive-collapse
  (a bug) vs negative-exhaustion (correct)**: piece (3) is gated to the POSITIVE branch + an
  INTERSECTION target, and piece (2)'s narrow-DOWN excludes `narrowed === never`, so the
  negative-exhaustion never is untouched. Landed at **0 NEW self-compile FPs (clean burn-down, no
  swaps)**; the 8 binder/nodeFactory intersection-arm-union FPs that a broader guard (a) exposed were
  avoided by keeping the fix to these three narrow gates.
- **Perf: self-compile 104 → 122 s (+17%)** — the nested guards now resolve, so many more narrowing
  walks SUCCEED (do the full relation work instead of bailing at `resolveFlowCalleeDecl`);
  correctness-first, perf is M5. Corpus suite time flat (2m 1s). +5 local tests
  (NestedTypeGuardNarrowingTest: nested-guard if / `&&`-RHS / union narrow + FP-safety missing-prop
  still fires + ambiguous-name-does-not-resolve). **META (re-confirms round 411): re-bucket a big M3
  family by its INNER type — TS2339-by-receiver surfaced a bounded, general, FP-safe engine fix
  (nested-guard resolution) hiding inside the "M3.4 narrowing" bucket; and reproduce a scattered
  family with a minimal test (top-level vs nested guard) to pin the ONE mechanism before touching the
  hot path.** DEFERRED (residual, M3): TS2739×1 empty-tuple (round 415), TS2740 Set-shim lib, the
  arithmetic reassignment TS2362 (cross-statement narrowing), TS2367 const-string-enum (M3.3/B425),
  the M3 cores TS2322×784 / TS2345×395 / TS7006×301.
**Round 417 (2026-07-06) — M1.12: resolve NAMESPACE-LOCAL interface/class `extends` bases.
Self-compile (compiler profile) 1,904 → 1,902 (−2); suite 9,161 → 9,168 (+7 local, 0 regressions);
1 commit (2a05b3ea).** Investigating the TS2353×3 excess-property bucket, a minimal repro confirmed
that `getTypeFromBaseTypeExpression` resolved a bare-Identifier `extends` base via `globals` ONLY,
so a namespace-local base (`namespace M { interface Base {}; interface Derived extends Base {} }`)
was never inherited — the inherited members were invisible, FP'ing TS2353 (an inherited member in an
object literal looked "excess") on tsc's own `builderState.ts`
(`ManyToManyPathMap extends ReadonlyManyToManyPathMap`, both inside `namespace BuilderState`). **The
fix is COORDINATED across the two base-resolution sites — the FIRST cut (only
`getTypeFromBaseTypeExpression`) REGRESSED `genericRecursiveImplicitConstructorErrors3`:** (1)
`getTypeFromBaseTypeExpression` resolves the base through the enclosing namespace
(`lookupTypeSymbolInInferenceNamespace`, which `resolveInterfaceMembers` has already pushed onto
`inferenceNamespaceStack`) before `globals` — a strict superset (empty stack → globals, module-level
bases unaffected). (2) `lookupInstanceMemberInResolvableChain` gains a threaded `enclosingNs` (default
null → the 4 non-`this` callers are byte-identical) so the `this.X` TS2339 check can resolve a
namespace-local base chain. **The second site is LOAD-BEARING: once base resolution populates
`type.baseTypes` for a namespace-local base, the conservative "class has base types" branch of the
this-member TS2339 check runs, and a globals-only base lookup there returns `null` (uncertain → bail),
SWALLOWING a genuinely-missing-member TS2339 — `genericRecursiveImplicitConstructorErrors3`'s
`this.isArray()` on `PullTypeSymbol extends PullSymbol` (both in `namespace TypeScript`; `isArray` is
declared NOWHERE, so tsc emits TS2339).** FP-safe BY CONSTRUCTION: `lookupInstanceMemberInResolvableChain`
returns `false` (→ emit) ONLY when the chain is FULLY resolvable and the member is absent everywhere;
any uncertainty (sibling interface, index sig, `declare`, unresolvable base) propagates `null`. Cleared
builderState:126 (TS2353 getKeys) + builderState:339 (a downstream TS2322); builderState:168 only
MORPHED TS2739→TS2740 (the same pre-existing FP: `const map: ManyToManyPathMap` mis-resolves as the
merged `interface BuilderState`; the missing-member count just grew as inheritance now works —
orthogonal interface+namespace-merge M3 issue, NOT a new FP). +7 local tests
(NamespaceLocalBaseInheritanceTest: excess/nested/module-level + the this-member TS2339 present/absent
pair, both edges as negative controls). **META (hard-won, ~4 suite runs): the FIRST cut looked exactly
like B451 id-drift — genericClasses0 "passed in isolation, failed in the full suite", and the process-
static `Type.nextTypeId`/`Symbol.nextId` counters (never reset) plus id-ordered output made id-drift
the obvious hypothesis. It was WRONG: a naive JUnit-XML regex (self-closing-tag-fragile, per the
CLAUDE.md gotcha) had MISATTRIBUTED the failure to the passing `genericClasses0` — the REAL failing
test was `genericRecursiveImplicitConstructorErrors3`, and the mechanism was a REAL semantic
interaction (the this-member "has base types" heuristic), not id-drift. Always parse JUnit XML with an
actual parser, and get the ACTUAL diff before theorizing.** DEFERRED: the builderState:168
interface+namespace-merge FP (`const map: ManyToManyPathMap` resolving as `BuilderState`) is a
separate M3 issue; the namespace-local class+interface-merge FP hole in `classNamesWithSiblingInterfaces`
(top-level-only) is not exercised by the corpus (suite green) and closing it precisely would need
namespace-context-keyed name tracking (deferred).

**Round 416 (2026-07-05) — M1.12/M3.4: THREE clean bounded FP-safe / suppression-only fixes from
bucketing the fresh full `--listAll`. Self-compile (compiler profile) 1,922 → 1,906 (−16); suite
9,145 → 9,157 (+12 local, 0 regressions); 3 commits (3b216114, f0a3c81f, 77daebc5).** The M3 cores still dominate the histogram (TS2322×785 / TS2345×396 /
TS7006×301 / TS2339×237, engine-gated) but two contained arithmetic-pass families were genuine
bugs. **(1) let/var local shadowing an outer function (TS2365 7 → 5):** round 407 recorded an
un-annotated `const X` whose name SHADOWS an outer same-named FUNCTION so a later bare-identifier
arithmetic/comparison operand resolves to the shadowing local, not past it to the function — the
gate was `const`-only. tsc's own checker.ts uses `let min = Number.POSITIVE_INFINITY` / `let max =
Number.NEGATIVE_INFINITY` (shadowing the imported `function min`/`function max`), so `if (min <
args.length && args.length < max)` FP'd TS2365 "Operator '<' cannot be applied to types '{ <T>(…) }'
and 'number'" (checker.ts:36449/36458). Extended the branch to `let`/`var`, recording `anyType`
(reassignment-proof — a `let` may be reassigned to a different primitive, so recording its INITIAL
primitive could FP a later comparison; `any` only ever SUPPRESSES the bogus operand check); `const`
keeps its concrete-primitive recording. The SHADOW gate (the name resolves to an outer FUNCTION) is
the firewall. **(2) &&/ternary truthy-narrowing (TS2362 10 → 4, TS2365 5 → 1):** the arithmetic pass
has no flow narrowing, so an `Enum | undefined` operand narrowed by an enclosing `&&` or a ternary
condition FP'd TS2362/TS2363/TS2365 — a `Type.Union` carries no Undefined flag on ITSELF, so the
`strictNullChecks` bail in `checkBinaryOperatorTypes` never fires and the operand classifier
(`isValidArithmeticOperand`) sees the undefined union member and rejects. New field
`arithTruthyNarrowedNames`: while walking the RIGHT of a `&&` (a dedicated branch in
`checkArithmeticInExpr` that scopes the narrowing set via try/finally, run BEFORE the left-spine
flatten so it isn't lost) and the whenTrue/whenFalse of a ternary, the guard's non-nullish
identifiers have their nullish members stripped in `arithOperandType` (generalizing the existing
NonNull `x!` strip). `collectArithTruthyNarrowableNames` handles `X` (truthy), `A && B` (union),
`X !== undefined`/`X != null`; `collectArithFalsyNonNullNames` handles the ternary whenFalse `X ===
undefined`/`X === null`/`!X`/`A || B` (De Morgan). Fixed checker.ts `checkMode && checkMode &
CheckMode.Inferential` (×2) + `contextFlags && contextFlags & ContextFlags.NoConstraints` + two
`checkMode && checkMode & ~CheckMode.SkipGenericFunctions` + a `checkMode && checkMode &
CheckMode.RestBindingElement` (6 TS2362), sourcemap.ts `sourceLine !== undefined && … pendingSourceLine
> sourceLine` (×2), generators.ts `label !== undefined && label > 0`, and scanner.ts `length ===
undefined ? text.length : start! + length` (the ternary false-branch). FP-safe BY CONSTRUCTION: a
truthy operand provably has no nullish value at that point, so stripping only ever suppresses a
wrong error, never adds one. The `&&` branch is safe because `checkBinaryOperatorTypes` does nothing
for `&&` itself, and a `&&` is never on the left-spine of an arithmetic operator (it is the
lowest-precedence binary except `||`/`??`/assignment), so the dedicated branch always intercepts it.
+9 local tests (ArithmeticShadowedFunctionLocalTest +1, ArithmeticAmpAmpNarrowingTest ×8) with
strong negative controls: a bare un-narrowed enum-union operand, a `||`-right operand, and a ternary
false-branch of a NON-nullish test all STILL fire. **Residual (M3.4/M3-gated): TS2362×4** —
checker.ts:6639 `flags = flags || None; … flags & X` (reassignment narrowing, cross-statement — a
different, harder pattern, 1 site not worth the scope-tracking mechanism + its FP surface for one
FP), programDiagnostics.ts/checker.ts `Debug.checkDefined(x) - y` / `reduceLeft(…) & X` (generic
call-return inference, M3.1); **TS2365×1** — utilities.ts:6314 `lineCount + T` (generic). META
(re-confirms the M1.12 method): bucket the FULL `--listAll` by normalized message shape, then
sub-classify a family by the SYNTACTIC shape at each site (`&&`/ternary vs reassignment vs generic)
— 9 of the 12 arithmetic residuals were the two syntactic-narrowing shapes, the rest genuinely
engine-gated. Perf unmeasured beyond the bench row (an operand strip + a `&&`/ternary walk — no
relation-engine work). **(3) closure-captured-var loop narrowing (TS18048 16 → 12):** re-bucketing
the fresh TS18048×16 by reference and reproducing one with a minimal test found a genuine bounded
flow-narrowing bug: `emitTs18048ForClosureCapturedUndefinedReceiver` (B464) used the non-loop-following
`getNarrowedTypeForReference`, which washes a reference back to its DECLARED type at a loop's
FlowLoopLabel (the deliberate back-edge-safety wash-out). So a captured variable narrowed by a
closure-LOCAL guard BEFORE a loop and read INSIDE it FP'd TS18048 — tsc's own checker.ts:8207
(`expandedParams: readonly Symbol[] | undefined` guarded `if (!expandedParams) return;` then read in
`for (…; pIndex < expandedParams.length; …)`), builder.ts:1551 (`array` guarded at the outer function,
read in a NESTED-closure for-loop — the B464 flow-into-closures brings the outer narrowing in, this
makes it survive the inner loop), and checker.ts:47176/47178 (`baseTypeNode`). Switched to
`getNarrowedTypeForReferenceFollowLoopEntry` — the loop-ENTRY-following variant the sibling
`emitTs18048ForOptionalPropertyAccessReceiver` already uses (B81.1c) — which follows antecedent[0] so a
read inside the loop sees the pre-loop narrowing. FP-safe: it only ever narrows MORE (suppresses a
TS18048), never adds one, and behaves identically outside loops. The remaining 12 TS18048 are OTHER
gaps: assignment-in-guard property paths (`if (!state.X) { state.X = new Map() } state.X.set(…)`,
es2015.ts/builder.ts — round 416 fix 4 closed the `if (!x.y){x.y=new Map()}` subset, but a
`state.referencedMap`/`oldState` variant with a NESTED assignment target or deeper join remains),
`X?.kind === lit && X.parent…` optional-chain discriminants (checker.ts:8061/8062), and deep
single-use property-path narrowing (options.types / node.name / symbol.valueDeclaration) — each
a distinct M3.4 sub-cause, not a single bounded slice. **DEAD-END NOTED for the optional-chain case
(next agent, don't repeat): adding `X?.prop === lit → exclude nullish from X` to `narrowByEquality`
did NOT flip checker.ts:8061/8062 — the optional-property TS18048 emitter
(`emitTs18048ForOptionalPropertyAccessReceiver`) narrows the receiver via a path that does not route
the `&&`-left condition through `narrowByEquality` for the receiver reference (reverted, unverified).
The real fix needs (a) tracing WHERE that emitter's receiver narrowing consults the flow condition,
and (b) accepting an ENUM-MEMBER RHS (`SyntaxKind.X`) — `literalTypeOfExpression` returns null for
enum members, so a literal-only gate misses every real site; use a "RHS is definitely non-nullish"
check (a possibly-undefined RHS is unsafe: `undefined?.p === undefinedRHS` can be true when X is
undefined).** 3 local tests (ClosureCapturedLoopNarrowTest)
with an un-guarded negative control (an un-guarded captured possibly-undefined var in a loop STILL
fires). META: the productive move was to reproduce a scattered-family member with a minimal test
(gradle suppresses stdout → assert, don't println), which turned "16 scattered property-path gaps"
into one crisp loop-wash-out bug the corpus could never surface (single-file, no captured-var-in-loop
shapes). **(4) assignment-effect narrowing based on the DECLARED type (TS18048 12 → 10):**
reproducing the es2015.ts `state.labeledNonLocalBreaks` FP found the tsc idiom
`if (!x.y) { x.y = new Map() } x.y.method()` FPs TS18048 for a PROPERTY-PATH target — but the
identifier form (`if (!m) { m = new Map() } m.set()`), the straight-line form (`x.y = new Map();
x.y.m()`), and the guard-return form (`if (!x.y) return; x.y.m()`) all worked. Isolation bisection
pinned it to a TWO-antecedent branch-join where one antecedent narrows via a property-path
FlowAssignment: `narrowByAssignmentRhs`'s non-nullish-RHS branch returned
`narrowByExcludingNullUndefined(antecedent)`, and the then-branch antecedent is `x.y` already
narrowed to bare `undefined` by the `!x.y` guard — `narrowByExcludingNullUndefined` returns a
NON-union `undefined` UNCHANGED (nothing to filter), so the then-arm re-adds undefined at the join.
An assignment OVERWRITES the reference (tsc `getAssignmentReducedType(declared, rhsType)`), so its
post-state is the DECLARED type minus nullish, independent of the pre-assignment flow narrowing.
Fixed by threading `declaredType` into `narrowByAssignmentRhs` and basing the exclusion on it
(straight-line is unaffected — antecedent equals declaredType there; a possibly-undefined RHS still
doesn't narrow). This is a SHARED narrowing path (TS2454/TS18048/TS2339/TS2345) yet the full suite
stayed green with ZERO regressions — a principled, tsc-faithful correctness improvement, not a
scoped emitter tweak. 4 local tests (AssignmentInGuardNarrowTest) incl. a negative control
(assigning a possibly-undefined value STILL fires). META: the isolation-bisection method (vary ONE
axis at a time — identifier vs property-path, braces vs none, assign vs return, straight vs
conditional — until the failing combination is a single cell) turned a vague "property-path
narrowing gap" into an exact root cause in `narrowByExcludingNullUndefined`'s non-union early-return.

**Round 415 (2026-07-05) — M1.12: TWO clean bounded self-compile fixes from bucketing the fresh
full `--listAll` by normalized message shape, both FP-safe. Self-compile (compiler profile)
1,930 → 1,922 (−8); suite 9,134 → 9,145 (+11 local, 0 regressions); 2 commits (4c768bb7, a647aa74).**
Method (the M1.12 note): re-ran `--listAll` at HEAD (1,930, 100 s), bucketed all lines by
normalized shape — the M3 cores dominate (TS2322×785 / TS2345×396 / TS7006×301 / TS2339×237, all
engine-gated) but two bounded tail buckets were genuine bugs. **(1) NonNull arithmetic operand
(TS2362 15 → 10, −5):** a `x!` (NonNullExpression) arithmetic/comparison operand whose base type is
`T | undefined` now uses the NON-NULL type — tsc types `x!` as `NonNullable<typeof x>` and does
arithmetic on THAT. `getTypeOfExpression` deliberately keeps the union for `(T | undefined)!`
(round 407: a GLOBAL nullish-strip in that case unmasks M3 object-literal/generic gaps → reverted),
but the arithmetic pass classifies operands and a `Type.Union`'s own `.flags` carry neither the
Undefined bit nor the numeric-enum bit, so `TokenFlags | undefined` fails every operand test →
spurious TS2362/TS2363. New `arithOperandType` strips nullish LOCALLY (only for a syntactic `!`),
reproducing tsc's own source: `templateFlags! & TokenFlags.TemplateLiteralLikeFlags` (nodeFactory
×2), `contextFlags! & ContextFlags.X` (checker ×2), `state.affectedFilesIndex! - 1` (builder).
FP-safe by construction: only fires on an explicit `!`, only ever REMOVES nullish members (a
`(string | undefined)!` still fails, still errors), and is local to the pass (no touch to global
expression typing — the round-407 blast radius). The residual 10 TS2362 (+2 TS2363) are
`&&`/`||`/reassignment flow-narrowing cases (checker.ts `checkMode && checkMode & X`, `flags =
flags || None`) — M3.4. **(2) exhaustive enum switch (TS2366 15 → 12, −3):** round 414 deferred
Pattern C2 — a `switch` with NO `default` that EXHAUSTIVELY covers its discriminant's enum is
terminating (tsc narrows the discriminant to `never` after all cases, so the endpoint is
unreachable). This lands the FP-SAFE SUBSET: an ENUM / enum-member-union / call-return-enum
discriminant (the value set is a precise enum). `isExhaustiveEnumSwitch` claims exhaustive ONLY
when it can PROVE every enum member is covered — every case an `Enum.Member`/`undefined`/`null`,
and the discriminant resolving to a precise enum (or `enum | undefined/null`) via a simple param's
type ANNOTATION (`enumSwitchKeysFromTypeNode`: a bare enum name → all members; a type alias =
union of enum members → recurse — `CompoundAssignmentOperator` = union of `SyntaxKind.X`; an
`Enum.Member` ref → that one) OR the resolved expression type (`enumSwitchKeysFromType`: a pure
enum object → all members, a `enum | undefined` union → members + nullish marker). Any uncertainty
(a non-enum-member case, a discriminant that doesn't resolve to a clean enum, a missing member)
returns null/false → the TS2366 STANDS, so it never suppresses a genuinely non-exhaustive switch's
diagnostic (no false negative). Reuses the round-411 enum helpers
(`resolveEnumSymbolForDiscriminant`/`enumMemberKeyOfExpr`/`enumValues`, barrel-aware). Fixed tsc's
own `getCategoryFormat(category: DiagnosticCategory)`,
`getNonAssignmentOperatorForCompoundAssignment(kind: CompoundAssignmentOperator)`,
`getSetExternalModuleIndicator` over `getEmitModuleDetectionKind(options): ModuleDetectionKind`.
The remaining 12 are `.kind` discriminated-union switches (`mapper.kind`, `node.kind` ×5,
`target.kind`, `name.kind`, `LiteralToken["kind"]` indexed-access, `options.newLine` property
access) — the discriminant is a property of a union of interfaces/TypeLiterals, needing type-alias-
chain + intersection + inherited/TypeLiteral-`.kind` flattening, with a real false-negative risk
(`.errors.txt` disabled = weak gate) — the documented larger M3.4 slice, which correctly BAILS
here. **This analysis feeds ONLY `checkBodyForImplicitReturn` (TS2366/TS7030/TS2355); TS7027
unreachable-code uses the separate `isDefinitelyTerminating`, so a wrong claim here cannot
spuriously report post-switch code as unreachable — the change is a pure suppress-when-provable.**
11 local tests (NonNullArithmeticOperandTest ×5, ExhaustiveEnumSwitchTerminationTest ×6) with
negative controls (a `(string|undefined)! - 1` still fires TS2362; a bare `flags & X` with no `!`/
guard still fires; a switch MISSING an enum member, an `enum|undefined` WITHOUT `case undefined`,
and a `break`-ing case body all STILL fire TS2366 — the FP-safety firewall, since the corpus is a
weak gate for TS2366 with `.errors.txt` disabled). **DEFERRED (bounded but broad/risky): the
empty-tuple-vs-all-optional-tuple TS2739** (moduleSpecifiers.ts:344 `return emptyArray as []`
against `readonly [kind?, specifiers?, moduleFile?, modulePaths?, cache?]`) — `[]` is assignable to
a tuple whose elements are ALL optional, but `buildTupleFromTypes` builds all numbered props as
required (`SymbolFlags.Property`, no Optional flag) since the resolved tuple `Type` loses the AST
`NamedTupleMember.questionToken`/`OptionalType` optionality (the documented no-minLength-tracking
gotcha). The clean fix needs a new `SymbolFlags.Optional` bit threaded through tuple building +
`isOptionalProperty` reading it — broad regression surface (many `isOptionalProperty` callers on
tuple props) for 1 self-compile instance; not worth the risk this session. **META: re-confirms the
M1.12 method — bucket the FULL `--listAll`, not the log tail. Also re-confirms round 414's finding
that `.errors.txt` being disabled makes the whole termination analysis (TS2366/TS7030/TS2355) gated
ONLY by the full suite + local tests → strong negative controls are mandatory for any
suppress-a-diagnostic change there.** Perf unmeasured beyond the bench row (an operand-strip + a
syntactic enum-switch — no relation-engine work; the enum resolution reuses round-411's memos).

**Round 414 (2026-07-05) — M1.12: the TS2366 "Function lacks ending return statement" family
(50 self-compile FPs; tsc reports 0 on its own source) is THREE CFA fall-through patterns — TWO+
landed. Self-compile (compiler profile) 1,965 → 1,930 (−35, TS2366 50 → 15); suite 9,117 → 9,134
(+17 local, 0 regressions); 2 commits (a8871148, c756292c).** Method (the M1.12 note): a fresh full
`--listAll` bucketed by normalized message shape put TS2366×50 as the biggest BOUNDED family (the M3
cores TS2322×785 / TS2345×396 / TS7006×301 / TS2339×237 dominate but stay engine-gated). Classifying
the 50 sites showed three purely-syntactic (or barrel-resolution) CFA gaps in
`statementAlwaysReturns`/`switchAlwaysReturns` — the "does the function body always return/terminate"
analysis behind TS2366/TS7030/TS2355: **(A) infinite loop (~17):** `while(true)`/`for(;;)`/`do..while(true)`
used `!containsBreakOrReturn(body)`, which counted a `return` INSIDE the loop as a fall-through exit —
but a return exits the FUNCTION, not the loop, so the endpoint stays unreachable (`while (true) {
return x; }` is terminating, exactly as tsc's reachability models it). Replaced with
`infiniteLoopFallsThrough` (a plain return/throw excluded; the labeled-break-in-a-nested-loop
detection KEPT via `containsLabeledBreakEscaping`, so reachabilityChecks5/6 f11 — `do { do { break
test; } while(true); } while(true)` — still resolves, since its `break test` has no plain return
inside the loops). tsc's own `unwrapInnermostStatementOfLabel`/`skipTrivia`/scanner char loops.
**(B) trailing never-call (~11):** a `Debug.fail("...")` / `assertNever(x)` bare ExpressionStatement
diverges (returns `never`), so the endpoint after it is unreachable — but `statementAlwaysReturns`
never checked call return types. New `callHasNeverReturnAnnotation` resolves the callee via round-413's
barrel-aware `resolveFlowCalleeDecl` (handles an Identifier callee AND a namespace-member `Debug.fail`
PropertyAccess, following import aliases + `export *` — tsc's `Debug` is barrel-imported through
`_namespaces/ts.js`; the existing `isNeverReturningExpression` was Identifier+globals-only) and checks
the explicit `: never` return annotation. FP-safe: an explicit `: never` is authoritative — the call
provably cannot return normally. (`return Debug.fail(...)` was already handled by the `ReturnStatement`
arm; only the bare-statement form needed this.) **(C1) switch fall-through (~10):** `switchAlwaysReturns`
checked each clause in ISOLATION (`clauses.all { stmts.isEmpty() || bodyAlwaysReturns(stmts) }`), so a
NON-empty clause that completes normally and falls through to a returning clause was missed — tsc's own
`parseSimpleUnaryExpression`: `case AwaitKeyword: if (isAwaitExpression()) return parseAwaitExpression();
/* falls through */ default: return parseUpdateExpression();`. Rewrote as a fall-through-aware REVERSE
walk: a clause guarantees a return if its body always-returns; else, if it completes normally with no
`break` out of the switch (checked FIRST — a reachable break escapes even after a later return), it
inherits the NEXT clause's guarantee; a break-out or the last clause completing normally escapes.
+17 local tests (InfiniteLoopTerminationTest ×11, SwitchFallThroughTerminationTest ×6), each with
negative controls (an escapable loop / a labeled break escaping a nested loop / a non-never void call /
a break-out case / a no-default non-exhaustive switch / a last-clause-that-falls-through all still fire
the diagnostic). **DEFERRED — Pattern C2 (~15 remaining): an EXHAUSTIVE `switch` with NO `default`
over an enum (`ModuleDetectionKind`, `DiagnosticCategory`) or a discriminated-union `.kind` (`node.kind`
on `PropertyAccessExpression | QualifiedName | ImportTypeNode`; `mapper.kind` on the `TypeMapper`
union) — tsc treats these as exhaustive because the discriminant narrows to `never` after all cases.
That needs type-level discriminant-exhaustiveness (resolve the discriminant type, enumerate its
enum-members / union-`kind` literals, check coverage) — an M3.4 discriminant-narrowing slice with real
FP surface (the corpus has exhaustive-switch tests), not a bounded syntactic fix. Fold into M3.4.**
META: `.errors.txt` tests are DISABLED in the corpus (CLAUDE.md), so this whole reachability analysis
(TS2366/TS7030/TS2355/TS7027) is gated ONLY by the full suite + local `*TerminationTest.kt` — which is
exactly why a 50-FP bucket sat invisible on the self-compile dashboard until `--listAll` bucketing
surfaced it. Perf unmeasured (a syntactic CFA refinement — no relation-engine work; the never-call
resolution reuses round-413's process-wide memo).

**Round 413 (2026-07-05) — M3.4: the builder.ts `Debug.assert(isDefined(state))` TS18048
blocker (round-412's "highest-value next M3.4 target") is FIXED — and the round-412 depth
diagnosis was a RED HERRING. Self-compile (compiler profile) 2,373 → 1,966 (−407, TS2339
614 → 237, TS18048 29 → 16, TS2722 2 → 1); suite 9,105 → 9,113 (+8 local, 0 regressions);
2 commits (68da80da, c4c8850c).** Started on round-412's flagged target (the walk hits
`NARROW_MAX_DEPTH` on builder.ts's 3290-node flow graph) and implemented the documented
M3.4 "tsc-shaped budget consumption" fix (**Item A**, 68da80da): both narrowing walkers
now follow LINEAR pass-through antecedents — array mutations, and assignments/calls that
don't narrow the walked reference — ITERATIVELY (tsc's `getTypeAtFlowNode` `while(true)`
loop) WITHOUT consuming `NARROW_MAX_DEPTH`; only branch/condition/switch/assertion recursion
consumes depth (tsc's `flowDepth`). The `flowAssignmentMightNarrow`/`flowCallMightNarrow`
gates over-approximate "narrows" (a too-lax gate would iterate past a real narrowing and
silently drop it); budget/memo/seen/truncation semantics are unchanged. **BUT Item A was
DASHBOARD-NEUTRAL (2,373 → 2,373) — an instrumented run (a debug print at every truncation
point, gated on an env var) showed ZERO narrowing-walk truncations across the whole
compiler-profile compile, yet the builder.ts FPs PERSISTED. The round-412 "walk hits the
depth cap" claim was inferred from the file's 3290-node count, NOT measured at the
truncation — the assert and use are actually CO-LOCATED in `emitBuildInfo` (a short chain),
so depth was never the issue.** Traced the chain further (`narrowByAssertCall` →
`resolveFlowCalleeDecl` → `resolveNamespaceMemberFnDecl`) and found the REAL cause:
`Debug.assert` never RESOLVED (`declResolved=false` ×37). **Item B (c4c8850c, the −407 win):**
`computeExportedSymbolThroughStars`'s leaf lookup returned ANY local named X — including a
non-re-exported IMPORT alias. tsc's `_namespaces/ts.ts` does `export * from "../core.js"`
(core.ts merely IMPORTS `Debug`) BEFORE `export * from "../debug.js"` (debug.ts DECLARES
`export namespace Debug`), so the star search for `Debug` stopped at core.ts's import alias
(flags=Alias, no `.exports`) and never reached debug.ts's namespace → `Debug.assert` never
resolved → `resolveNamespaceMemberFnDecl` returned null → its bare-assert narrowing never
fired. (This is why round 409's `isDefined` worked — core.ts genuinely declares+exports it —
but `Debug` didn't.) Fix: gate the leaf on the local being genuinely EXPORTED
(`name in getModuleNamedExports(file)`, which covers every ESM export form tsc's source
uses; memoized per file in `moduleNamedExportsCache`). FP-safe: `resolveExportedSymbolThroughStars`
is consulted ONLY by the round-409+ flow-only resolvers (function/namespace/enum), where
narrowing only removes union constituents. Barrel-imported `Debug.*` + every barrel guard
now resolves → TS2339 614 → 237 (−377), the single biggest slice. **Item C (a41f0ee2, −1, principled): the RETURN-assignability path is now a flow-narrowing
consumer** — `checkReturnAssignability` used the returned reference's wider DECLARED type, so
`return state` after `Debug.assert(isDefined(state))` (builder.ts's
`toBuilderProgramStateWithDefinedProgram`) FP'd a missing-property TS2739; narrow the returned
Identifier/PropertyAccess and substitute only when it strictly relates (mirrors round 410's
assignment-RHS narrowing; the return path was absent from the CLAUDE.md consumer list).
FP-safe by monotonicity. 12 local tests
(LinearFlowDepthNarrowingTest ×5: a 3000-node linear chain > `NARROW_MAX_DEPTH` still
narrows past asserts/conditions/calls + negative/trivial controls; BarrelExportLeafGateTest
×3: the exact importer-alias-before-declaration collision + non-vacuity + not-over-restrictive
controls; ReturnPathNarrowingTest ×4: type-literal guard/assert narrowed returns + two
negative controls — the type-literal shape is load-bearing since the return-path TS2739 emit
is gated to a `Type.Object` source/target, not a named interface). **Perf: compiler self-compile 72 → 92 s (no-emit) / +42% (emit) — the extra
narrowing work (many more guards resolve → more relation checks; round 409 saw the same
+16% for the direct-guard case). Correctness-first; perf is M5. Services P0 hang-check
CLEAN — no hang/crash, all 252 files emitted, 400 s (round 385 baseline was 563 s), and
services ALSO improved 4,301 → 3,643 (−658, TS2339 1,464 → 863; the barrel-guard fix is
even bigger on the larger profile) with time essentially FLAT (394 → 400 s, +1.5%) — so
the +42% on the SMALL compiler profile is single-run/emit noise + a constant, NOT
algorithmic (exactly round 409's O(n²)-falsifying observation).** **META (the session's hard lesson): an instrumented "does the walk truncate?"
probe FALSIFIED a documented depth hypothesis and redirected to the real resolution gap.
Verify a "walk hits the cap" claim by instrumenting the truncation directly, NOT by
inferring it from a file's node count. Two process gotchas re-confirmed: `pkill -f
KotlinCompileDaemon` SELF-MATCHES a shell command whose own cmdline contains the pattern
(exit 144, kills itself) — put the kill in a script file so the running process's cmdline
is `bash /tmp/x.sh`; and freeing the KotlinCompileDaemon (NOT `gradle --stop`, which wipes
`build/classes`) is what lets the `-Xmx3g` self-compile fit alongside the gradle daemon.**
Next M3.4 (deferred): the residual TS2339×237 / TS18048×16 / TS2722×1 — closure-capture
narrowing, generic-alias resolution, loop-stable narrowing of un-reassigned property paths
(the round-411-flagged `never`/`Type`/TS2722 residuals); the M3.1 cores (TS2322×785,
TS7006×301, TS2345×396) remain the long pole.

**Round 408 (2026-07-05) — a decomposed M3.4 slice: the TS2349 "not callable" family (the

**Round 412 (2026-07-05) — M3.4: a user type-guard narrows a SINGLE (non-union) type
DOWN to the guard's declared subtype. Self-compile (compiler profile) 2,374 → 2,373
(TS18048 30 → 29); suite 9,100 → 9,105 (+5 local, 0 regressions); 1 commit (69284a77).**
Method: bucketed the round-411 HEAD `--listAll` TS18048×30 by reference-path shape and
bisected the dominant `state.program`/property-receiver sub-pattern (builder.ts) to a
minimal repro. **Root cause (found by isolation bisection, not code reading):**
`narrowByCallPredicate`'s single-type (non-union) path checked `t <: candidate` FIRST and
returned the WIDE `t` when true — but our relation engine over-accepts `t <: candidate`
when `t` has an OPTIONAL property `program?: T | undefined` and `candidate` (`t`'s declared
subtype via `extends`) redefines it as REQUIRED `program: T` (an optional source prop
satisfies a required non-undefined target prop, so BOTH `t <: candidate` and `candidate <: t`
hold). So a guard `state is StateWithProgram` that narrows DOWN kept the wide `state`, and
`state.program` stayed possibly-undefined. Reordered to tsc's `getNarrowedType`(assumeTrue):
`candidate <: t ? candidate : t <: candidate ? t : candidate` — `candidate <: t` first (the
round-411 UNION path already did this; the single-type path was the un-fixed sibling).
**SECOND coupled fix:** the FP fired only via `emitTs18048ForOptionalPropertyAccessReceiver`
(optional-property-specific — that's why the required-union variant was always clean), which
narrowed the reference path `state.program`; but the guard narrows `state` (a DIFFERENT
path). Added receiver-PATH narrowing there: narrow `state` via the property-access node's
flow (always recordFlow'd — the bare receiver Identifier for a captured var often has NO flow
node) and suppress if the narrowed receiver's property is non-optional + non-undefined. Both
FP-safe / suppression-only. **CLEARED the local-namespace-guard receiver shape (utilities.ts
`name.emitNode`). DEEP-DIVE, DOCUMENTED FOR THE NEXT AGENT — the builder.ts barrel-`Debug`
`state.program` sites (7+) do NOT clear:** the exact shape (assert-then-use, captured const,
optional prop, multi-level + multiple inheritance, barrel-imported `Debug.assert(localGuard(state))`)
clears in EVERY isolation repro I built (single-file, closure, barrel with sibling `export *`s,
multi-inherit) — but in the real `createBuilderProgram` an instrumented run gives the PRECISE diagnosis: the
flow nodes ARE present (`getFlowAt(state.program)` = FlowCondition/FlowAssignment, non-null), but
builder.ts is `cfaTooLarge` (flow graph = **3290 nodes** > 2000), so the narrowing walk hits
`NARROW_MAX_DEPTH` (2000) before reaching the top-of-function assert FlowCall —
`narrowByAssertCall`/`narrowByCallPredicate` NEVER fire for `state`. NOT resolution (the barrel
`Debug` resolves fine in isolation) and NOT a binder flow-node gap. This is the genuine M3.4-hard
residual the round-411 note flagged; the fix is a smarter/deeper walk for too-large files, but
raising `NARROW_MAX_DEPTH` is the round-385 services-HANG P0 risk (a naive bump re-opens the
exponential), so it needs the tsc-shaped budget rebuild (M3.4 "faithful budget consumption"). This
one blocker gates ~15 builder.ts/es2015.ts/module.ts TS18048 + the 2 TS2722 sites — the highest-value
next M3.4 target once the budget rebuild is designed. 5 local tests (TypeGuardNarrowDownSingleTest). **PROCESS lessons (hard-won, cost ~2 rebuild
cycles): (1) `./gradlew --stop` mid-session WIPED the freshly-built `build/classes/kotlin/jvm/main`
(gradle daemon-shutdown stale-output cleanup, aggravated by `pkill -9 KotlinCompileDaemon`) → the
self-compile then failed "Could not find or load main class MainKt"; recover with a source `touch`
+ clean rebuild, and thereafter LEAVE the daemon up (run the `-Xmx3g` self-compile alongside it —
memory was fine at ~4.5 GB free). (2) foreground `sleep N` is BLOCKED in this environment (use a
`python3 -c "import time;time.sleep(N)"` poll). (3) build ~60s + self-compile ~60s > the 120s Bash
timeout — run them as SEPARATE commands, never chained.**


**Round 411 (2026-07-05) — M3.4: TWO clean bounded FLOW-NARROWING slices from the
TS2339 union-receiver family (the second-biggest self-compile family). Self-compile
(compiler profile) 2,433 → 2,374 (−59, TS2339 672 → 614); suite 9,087 → 9,100 (+13 local,
0 regressions); 2 code commits (aba1dcb6, 7a771a77) + 1 docs commit.** Method: bucketed the
round-410 HEAD `--listAll` TS2339 sites by RECEIVER-type shape (`s/does not exist on type
'([^']*)'/\1/`), which surfaced two dominant narrowing sub-patterns hiding inside the "M3.4
union-receiver" bucket. **(1) ENUM-MEMBER DISCRIMINANT narrowing (−42, TS2339 672 → 631):** a
discriminated union keyed on an enum-member discriminant (`type: Kind.A`) never narrowed in
EITHER the `if (s.type === Kind.A)` equality path (`narrowByDiscriminantProperty`) OR the
`switch (s.type) { case Kind.B }` path (`narrowBySwitchClause`) — a member access in the
narrowed branch FP'd TS2339 on the whole union. Root cause: enum-member types resolve to
`anyType` in our engine (they are NOT modeled as literals — modeling them generally is
nominal-enum / B425-risky), so `literalTypeOfExpression(Kind.A)` returns null (a
`PropertyAccessExpression`) AND each union member's discriminant property `type: Kind.A`
resolves to `any` (confirmed by debug-instrumenting `narrowByDiscriminantProperty`:
`literalType=null`, `propType=Intrinsic:any`). tsc's own source has THREE such families:
`UpToDateStatus` (tsbuildPublic.ts, keyed on `type: UpToDateStatusType.X`, 23→1), `TypeMapper`
(checker.ts, `switch (mapper.kind) { case TypeMapKind.Simple }`, anonymous-object-literal
members, 16→6), `PrivateIdentifierInfo` (classFields.ts, `kind: PrivateIdentifierKind.X`,
extends-a-base members, 13→0). Fixed TARGETED + AST-based: new helpers
`enumMemberKeyOfExpr`/`enumMemberKeysOfTypeNode`/`discriminantPropAnnotation`/
`filterUnionByEnumDiscriminant` match the union member's DECLARED `type: Enum.Member` annotation
(read from the property's `PropertyDeclaration.type`) against the `Enum.Member` on the
comparison / case, keyed by `"${enumSymId}#member"`. FP-safe by construction: a member without
a resolvable enum-member discriminant is KEPT (can't prove exclusion), a multi-valued
discriminant (`type: Kind.A | Kind.B`) survives a single `!==` (keep iff `keys.any { !in
caseKeys }`), and narrowing only removes union members. **THE KEY FINDING (measured, not
assumed): the FIRST cut was only −4 — all three real families use BARREL-IMPORTED enums
(`import { UpToDateStatusType } from "./_namespaces/ts.js"`), and `resolveEnumSymbolForDiscriminant`
resolved them to an `Alias` symbol (flags=4096) that the general `resolveAlias` can't follow
through the ESM `.js` specifier + `export *` barrel (the round-409 issue). Debug-instrumenting
showed the narrowing was REACHED but `rhsKey=null` + `isEnum=false`.** Added
`resolveImportedEnumSymbol` (FLOW-ONLY, memoized in `importedEnumSymCache`, the enum sibling of
round 409's `resolveImportedNamespaceSymbol` — mirrors `computeImportedNamespaceSymbol` but
gated on `SymbolFlags.Enum`), consulted only when `resolveAlias` yields an Alias. That unlocked
the full −42. Deliberately NOT in the general resolveAlias (round 409 measured a self-compile
REGRESSION +297 via a TS2315 flood there). 8 local tests (EnumDiscriminantNarrowingTest: if/
switch/negative-else/multi-value-positive/multi-value-survives-single-negative + two negative
controls + a MULTI-FILE ProjectCompiler barrel case). **(2) TYPE-GUARD narrows a union member
DOWN to the guard type (−17, TS2339 631 → 614, the `never`-receiver sites 39 → 20):**
`narrowByCallPredicate`'s positive union branch kept ONLY constituents `m <: c` (assignable TO
the guard target `c`), so a union whose members are all SUPERTYPES of `c` collapsed to `never`
— a member access in the narrowed branch then FP'd TS2339 on `never`. tsc's `getNarrowedType`
(assumeTrue) does `m <: c ? m : c <: m ? c : never` PER CONSTITUENT: when the guard target `c`
is a subtype of a union member `m` (the common case — a broad member like `Expression`
narrowed by `is TaggedTemplateExpression`), narrow the member DOWN to `c` instead of dropping
it. tsc's own `parser.ts` (`isTaggedTemplateExpression(node)` on `Expression | PropertyName`,
FIXED — was `never`) and `checker.ts` (`isAutoAccessorPropertyDeclaration(node)`) rely on it.
FP-safe: the new mapping only ever KEEPS more than the old `m <: c` filter (it adds the
`c <: m` → narrow-to-`c` case), so it can only suppress an FP, never remove a member the old
filter kept; the negative branch and the single-type (non-union) branch are unchanged (the
single-type path already narrowed to the target). 4 local tests (TypeGuardUnionNarrowNoNeverTest:
subtype-of-a-member, deep-6-level chain, member-already-subtype-kept, + an unrelated-target
negative control). Both fixes' by-code sets are IDENTICAL to round-410 HEAD (no new codes = no
regression). **DEFERRED (deeper M3.4/M3, triaged but not bounded): the residual `never`×20 are
scattered (generic-alias resolution `SearchResult<T>` collapsing to never under truthiness,
closure-capture); `Type`×46 is closure-capture + `&&`-narrowing (`isGenericTupleType(type) &&
findIndex(getElementTypes(type), (t,i) => type.target.elementFlags[i])` — the narrowed `type`
must flow into the `findIndex` callback); TS2722×2 (moduleNameResolver.ts `if (host.directoryExists
&& …) { for (…) if (host.directoryExists(root)) }` — loop-stable narrowing of an un-reassigned
property path; program.ts `Debug.assertIsDefined(dsh.readDirectory)` in an object-literal method
body — flow into an object-literal method; both need the flow narrower to survive a FlowLoopLabel
/ be set in object-literal method bodies); TS2740/2739/2741 brand-property + the core.ts Set-shim
lib-min-target FP; TS2589 `WrappedExpression<T>` legal recursive-type depth-bail (M3.3).** META
(re-confirms round 409): the cross-file union-narrowing families are unlocked by the
barrel-import FLOW-ONLY resolution pattern, which the single-file corpus structurally cannot
surface — so measure the self-compile before/after every cut, and when a fix under-delivers,
debug-instrument to distinguish a RESOLUTION gap (rhsKey=null → the barrel resolver) from a
LOGIC gap.


**Round 410 (2026-07-05) — M1.12 + M3.4: THREE clean bounded self-compile fixes, all
FP-safe / suppression-only, found by bucketing the FULL compiler-profile `--listAll` (2,443
lines) by normalized message shape. Self-compile (compiler profile) 2,443 → 2,433 (−10); suite
9,076 → 9,087 (+11 local, 0 regressions); 3 code commits + 1 continuity docs commit.** Method
(the M1.12 note): re-ran `--listAll` at HEAD (2,443, 63 s) and bucketed all lines; the M3 cores
(TS2322×793, TS2339×672, TS2345×400, TS7006×301) dominate and stay engine-gated, but three bounded
buckets in the tail were genuine non-M3 bugs. **(1) TS2862 1→0 (commit 3512c756):** the B98.r80
generic-index-write walker fired for ANY constrained type-parameter receiver whose index write used
a non-numeric key, FP-ing tsc's own `assign<T extends object>(t: T){ t[p] = arg[p] }` (core.ts).
tsc emits TS2862 only when the write would otherwise fall back to a STRING/symbol index signature
(checker.ts ~19294: `accessFlags & NoIndexSignatures && indexInfo.keyType !== numberType`) — a bare
`T extends object` has no such `indexInfo`. Narrowed `constrainedTpNames` (used only by this walker)
to constraints bearing a string/symbol index signature: an inline `{ [s: string]: V }` TypeLiteral,
a `Record<K, V>` with a string/symbol-like key, or an intersection of either. Both
`cannotIndexGenericWritingError` corpus shapes (`Record<string | symbol, any>`,
`number[] & { [s: string]: … }`) still fire; a user `TypeReference` to an interface with an index
signature is a harmless false negative. **(2) assign-RHS type-guard narrowing −8 (commit 2c9fd451):**
a plain assignment `x = y` where `y` (an Identifier / property path) is narrowed by a preceding user
type-guard to a SUBTYPE of `x`'s declared type FP'd a missing-brand-property error — tsc's own
`node = parent` inside `if (isParenthesizedExpression(parent))` (utilities.ts) and `target = callee`
inside `if (isSuperProperty(callee))` (nodeFactory.ts). Flow narrowing was consulted by the var-decl
assignability path / TS2339 / call-args / the TS2349 callee, but NOT by `checkAssignmentExpression`,
so the RHS resolved to its wider declared type. Fix: before the identifier-target missing-property /
relation checks, narrow an Identifier/PropertyAccess RHS via `getNarrowedTypeForReference` and use
the narrowed type only when it is a STRICT improvement that makes the assignment relate
(`checkTypeRelatedTo(narrowed, tt, assignableRelation)` passes). Gated to a named object target
(Interface/Reference) — the shape the var-decl path deliberately defers. Suppression-only + FP-safe:
a genuine widening (no narrowing, or narrowed still not assignable) keeps the raw type and still
fires. Cleared TS2739 7→3, TS2741 3→2, TS2322 793→790; the residual TS2741×2 (compound `&&` guard
conditions) + TS2739×3 are deeper narrowing cases (M3.4). **(3) TS2394 1→0 (commit b0c38b2b):** a
`void` OVERLOAD return is compatible with ANY implementation return (tsc
`isImplementationCompatibleWithOverload`: `targetReturnType === voidType || (overload→impl) ||
(impl→overload)`); `isSignatureCompatible` ran the return check unconditionally, FP-ing tsc's
`writeTokenText(…): void` overload against its `: number` implementation. Skip the return check
(both the syntactic compare and the class-return covariance) when the overload return is `void`.
Constructor overloads are unaffected (no explicit return annotation). **Also cleaned a pre-existing
always-true `eff is Type.Union` warning introduced by round 408's callee-narrowing commit
(dabd5557) — `eff` is initialized from the smart-cast `calleeType: Type.Union`, so the guard was
redundant; rewritten to reference `calleeType`. Build is warning-clean again.** 11 local tests
(GenericIndexWriteConstraintTest ×5, AssignmentRhsNarrowingTest ×3, OverloadVoidReturnTest ×3), each
with negative controls (a `T extends object` / plain-interface constraint must NOT fire TS2862; a
genuine widening assignment must still fire; an overload returning an unrelated class must still fire
TS2394). **DEFERRED as M3/B425 (broad relation-engine risk): a const STRING enum is NOT assignable to
`string` in our engine.** A minimal probe showed even the SCALAR `const y: string = x` (where
`x: E`, `E` a const string enum) FPs TS2322 — not just the nested `Extension[][]` / `string[][]`
TS2367×2 + TS2322×2 module-resolution cluster. Fixing it needs modeling a string-valued enum as
string-like in the relation engine + `comparabilityCategory` (mirroring round 407's
`isNumericEnumObjectType` for the arithmetic pass), which the round-408 note already flagged M3 —
enum assignability is heavily corpus-tested, so it belongs to a dedicated M3.3/B425 slice, not a
bounded quick fix. **META (re-confirms the M1.12 method): TWO of the three bounded bugs were hiding
under M3-LABELED families — TS2394 under "overload", the assignment narrowing under the
TS2741/TS2739 brand-property bucket — and were surfaced only by bucketing the FULL `--listAll` by
normalized message shape, not the 30-line log tail. The residual bounded pool is genuinely thin:
after these, the tail is TS2591×43 (node globals, env-legit), TS2563×27 (B399 heuristic → M3.4), the
arithmetic ~22 (enum-`| undefined` un-narrowing → M3.4), the brand-property residue (M3.4/M3), and
the const-string-enum relation (M3.3/B425). Next real progress is a decomposed M3.1/M3.3/M3.4 slice
or M2.2 (real-lib A/B).**


**Round 409 (2026-07-05) — M3.4: user type-guards/asserts imported through `export *` barrels
(and ESM `.js` specifiers) now NARROW — the round-408-flagged "next high-yield M3.4/cross-file
sub-step". Self-compile (compiler profile) 2,618 → 2,443 (−175, TS2339 838 → 672); suite
9,066 → 9,074 (+8 local, 0 regressions); 1 commit (8f22d126).** tsc's own sources import
everything via `import { some, isDefined, Debug, … } from "./_namespaces/ts.js"` where the barrel
is `export * from "../core.js"; …`, so an imported guard/assert never narrowed — the pervasive
root cause behind a large TS18048/TS2339/TS2722/TS2349 slice. **TWO independent gaps blocked
resolution (round 408's naive "wire resolveAlias into resolveFlowCalleeDecl" was inert because of
the FIRST, undiscovered until this session):** (1) `resolveModuleSpecifier` deliberately won't
strip the ESM `.js`/.jsx/.mjs/.cjs extension (CLAUDE.md TS2459 FP-avoidance) → `resolveAlias`
could not resolve ANY `.js`-suffixed import (tsc uses `.js` specifiers everywhere), so even a
DIRECT imported guard failed; (2) even resolved, `targetFile.locals[name]` misses through an
`export *` re-export barrel. **CRITICAL SCOPING LESSON (measured, not assumed): the fix is
deliberately FLOW-ONLY.** A first cut added the `.js`+`export *`-star fallback to the GENERAL
`resolveAlias` (the clean, general fix the round-408 note's phrasing suggested) — the corpus stayed
green (0 corpus tests use `.js`/`export *` imports) BUT the compiler-profile self-compile REGRESSED
2,618 → 2,915 (+297) with a TS2315×466 flood: resolving barrel-imported TYPE references generally
exposed our generic-arity / type-resolution gaps (M3). Reverted the resolveAlias change to
byte-identical and scoped resolution to the flow walkers via a dedicated
`resolveImportedFunctionLikeDecl` (memoized process-wide in `importedGuardDeclCache` — the
services-hang firewall) that finds the ImportSpecifier's module (`.js`-tolerant
`resolveAliasJsModuleSpecifier`) + follows `export *` via the new `resolveExportedSymbolThroughStars`
(the SYMBOL-resolving companion to M1.1's `getModuleExportsFollowingStars`, which only followed
stars for NAME existence). FP-safe: flow narrowing only REMOVES union constituents → can suppress a
false positive, never add one. **ALSO fixed the generic guard `isDefined<T>(x: T | undefined): x is
T` (tsc's own `isDefined`, pervasive): `collectPredicateTpBindings`' UnionType branch now binds a
SINGLE naked type-param member to the actual constituents no concrete member covers (tsc's
naked-type-parameter union inference) — before, bare-TP union members were skipped as ambiguous, so
T never bound and the guard didn't narrow.** 8 local tests (BarrelImportedGuardNarrowingTest):
type-guard then/else, generic `isDefined`, `asserts x is T`, multi-hop `export *` chain, renamed
re-export specifier, + 2 negative controls (a non-guard call and a pre-guard call both still fire
TS2345). **Perf: compiler profile ~61 s → ~71 s (+16%) — the additional narrowing work itself (the
memo doesn't change it; more imported guards resolve → more relation checks); correctness-first,
perf is M5. Services hang-check (round-408 P0 firewall requirement): CLEAN — no hang, no crash, all
252 files emitted, lowest FP count ever (4,301), and time FLAT (+0.3%) despite services being the
BIGGER input (260k LOC) while the smaller compiler profile was +16% — the OPPOSITE of what an O(n²)
blowup would show, confirming the +16% is single-run noise + a small constant, not algorithmic.**
META (re-confirms rounds 407/408): a read-only "M3-gated" verdict is about the GENERAL fix; a
decomposed, tightly-scoped FP-safe slice (here: flow-only narrowing resolution) flips a whole
cross-file family even while the general resolveAlias / M3 engine stays as-is — and "the clean
general fix" can be a dashboard REGRESSION that only the self-compile (not the corpus) reveals, so
measure the profile before trusting generality. Next high-yield M3.4 sub-steps: the barrel-imported
NAMESPACE-member assert case (`Debug.assertIsDefined` — `resolveNamespaceMemberFnDecl` still routes
through the general `resolveAlias`, which stays byte-identical, so it does NOT resolve the
barrel-imported `Debug` namespace; a flow-only namespace-resolution variant would flip the round-408
unreproducible ×3 assert cases) and the TS2322×793 / TS7006×301 M3.1 cores.

biggest bounded bucket a FRESH full `--listAll` bucketing surfaced INSIDE the round-407
"M3-gated" tail). Self-compile (compiler profile) 2,639 → 2,618 (−21, TS2349 25 → 5); suite
9,053 → 9,066 (+13 local, 0 regressions); 3 commits.** Method (re-confirms the M1.12 note):
round 407 called the bounded pool "M3-gated", but that verdict was about the 30-line LOG TAIL —
re-running `--listAll` and bucketing all 2,639 lines by normalized message shape put TS2349×25
at the top of the bounded tail. Reading the 25 sites showed ONE root theme: a callee reference
typed `F | undefined` that a guard should have narrowed to the callable `F`, but the callee-type
resolution never consults flow narrowing (`getCalleeType` resolves an Identifier via
`currentLocalTypes` = the DECLARED type; the CLAUDE.md flow-narrowing gotcha confirms
`getTypeOfIdentifier` deliberately doesn't narrow). THREE FP-safe fixes, each "narrowing/typing
only REMOVES constituents → can suppress a false positive but never add one" (the corpus suite is
the gate): **(1) callee flow-narrowing (−13, commit dabd5557):** before the union callability
verdict in `checkSingleCallExpressionTypes`, re-narrow an Identifier/PropertyAccess callee via the
proven `getNarrowedTypeForReference`, and — under an optional call `f?.()` — drop the nullish
constituents the `?.` short-circuits. Gated to a callee that ORIGINALLY had a non-callable member
(so the case-(c) all-callable structural-mismatch path is untouched). Fixed `if (fn) fn()`,
`fn?.()`, `typeof v === "string" ? v : v()`, `&&`-chain guards, `getCustomTransformers?.()`, etc.
**(2) `typeof x === "function"` narrowing (−2, commit b2e2b8da):** `narrowByTypeOfGuard` returned
the union unchanged for the "function" tag ("can't identify function types by flags"), so
`typeof f === "function"` then `f()` FP-fired TS2349 (and a possibly-undefined callee FP-fired
TS2722 — the −1 bonus). A function value is exactly one with call OR construct signatures; the
tag now filters a union by callability (any/unknown/error kept on BOTH branches; never narrows to
`never`; "object" tag untouched). Broader typeof-narrowing change → full-suite-verified zero
regressions. **(3) empty-array contextual assignment (−6, commit 952cb715):** the tsc default-init
idiom `(x || (x = [])).push(v)` / `(x ??= []).push(v)` typed the receiver `T[] | any[]` because
`x = []` types the bare `[]` as `any[]` (B87.6) instead of contextually as the target's `T[]`;
`.push` on `T[] | any[]` then resolves to a UNION of two differing call signatures →
`getUnionType` of two sigs → the union-callee "not callable" verdict. `contextualAssignmentRhsType`
returns the target's type for an empty `[]` RHS when the target is an Array/ReadonlyArray Reference
(exactly tsc's contextual typing; `[]` is assignable to any array type so the result is only more
precise), wired into `combineBinaryTypes`' `SyntaxKind.Equals` + the logical-assign ops. Removed
all six `.push` default-init sites. 13 local tests (CalleeNarrowingNotCallableTest ×8,
EmptyArrayAssignmentTypeTest ×5) with negative controls (unguarded possibly-undefined callee still
fires; narrowed-to-non-callable still fires; non-empty array not retyped). **DEBUGGING SAGA (the
assert case, deferred): six minimal probes could NOT reproduce the `Debug.assertIsDefined(machine.onLeft)`
then `machine.onLeft()` FP (×3 remaining) — a custom `assertIsDefined<T>(): asserts value is
NonNullable<T>` narrows correctly for identifier paths, property paths, namespace-member callees,
generic receivers, intervening element-access assignments, AND generic-class constructor-parameter
properties. The real factory/utilities.ts case is a deeper interaction of several real-code
specifics I could not cheaply isolate; deferred.** Remaining 5 TS2349 = 3 sub-problems: the assert
cases (×3, unreproducible), a `??=`-with-call-RHS (core.ts:2135 — assignment-narrowing gap for a
call RHS, tsc narrows `x ??= foo()` to non-undefined regardless), and a `FlowNode[] | undefined`
union-LHS default-init (binder.ts:1375 — the bare member types as a union `FlowNode | FlowNode[]`,
likely a cross-interface-merge member-typing issue, so the empty-array fix's Array-Reference gate
doesn't apply). All M3.4/M3-gated. **Other bounded families this session (investigated, deferred):
TS2367×2 (`readonly string[][]` vs `readonly Extension[][]` — the array no-overlap check's
`checkTypeRelatedTo(ReadonlyArray<ReadonlyArray<Extension>>, ReadonlyArray<ReadonlyArray<string>>)`
returns false: a string-enum→string covariance gap through nested readonly-array = relation engine,
M3).** META (re-confirms rounds 305-307/317-324 + round 395): a read-only "M3-gated / pool
exhausted" verdict is about the GENERAL fix and the LOG TAIL — always re-bucket the ACTUAL full
`--listAll` by message shape; a decomposed M3.4 slice (flow narrowing into the callee position) with
tight FP-safe gates flips a whole family even while the M3.4 rebuild remains unbuilt.

**Round 407 (2026-07-05, same session as 406) — M1.12: the arithmetic-pass family yields TWO
more bounded buckets (self-compile 2,659 → 2,641, −18). Suite 9,043 → 9,050 (+7 local, 0
regressions); 2 commits.** Round 405/406 marked the arithmetic family "M3-gated", but two
sub-patterns are bounded. **(1) local-const-shadows-outer-function (TS2365 21 → 7, −14):** the
arithmetic/comparison pass types a bare-identifier operand via `getTypeOfExpression`, which falls
back to file/global scope for an un-recorded function-body local — so `const length =
arr.length` (a number) that SHADOWS tsc's own imported `function length(): number` resolved to
the FUNCTION, and `i < length` FP'd TS2365 `number < (…) => number` (~14 sites; core.ts also
exports `function min/max`). Fix: record an un-annotated `const` whose name shadows an outer
FUNCTION (concrete primitive when determinable, else `anyType`). **The SHADOW gate is
load-bearing — a first cut recorded EVERY primitive-typed const, which UNMASKED pre-existing
narrowing FPs on the OTHER operand: `const numStatements = source.length` (number) exposed
`statementOffset < numStatements` (statementOffset an un-narrowed `number | undefined` param),
and `const max = length(sig.tp)` exposed `min < max`. Gating to "the name resolves to an outer
FUNCTION" targets exactly the shadow-suppression case; the `any` fallback for a genuine shadow
only bails the bogus check and never unmasks (+5/−6 messy swap → clean −14).** **(2)
branded-number arithmetic (TS2362 19 → 15, −4):** a branded number `type
IncrementalBuildInfoFileId = number & { __brand }` is assignable to `number` (an intersection is
a subtype of each member), so `fileId - 1` is valid — but the operand classifiers
(`isNumberLikeType`/`isBigIntLikeType`/`isStringLikeType` + the B283 `typeAssignableTo*Kind`)
handled Union/TypeParam, not `Type.Intersection`. Fix: an intersection is number-/bigint-/
string-like iff ANY constituent is. 7 local tests (ArithmeticShadowedFunctionLocalTest ×3,
BrandedNumberArithmeticTest ×4) with negative controls (non-shadowed `5 < g` still fires;
object-intersection `x - 1` still fires). **The residual arithmetic FPs ARE M3.4/M3-gated: the
remaining ~15 TS2362 are `<enumFlags> & <enumMember>` where the LHS is `Enum | undefined`
un-narrowed via a `&&`/`!` guard (narrowing gap) or a NonNull-of-enum-union that doesn't strip
undefined; the remaining 7 TS2365 are the `min/max` overload type and `number | undefined`
comparisons — all narrowing/M3.** **(3, same session) enum reverse-mapping TS7053 (3 → 1, −2):**
`NumericEnum[key]` is a valid reverse mapping (number → member name), so tsc emits no TS7053 —
but a numeric enum in value position resolves to an empty `Type.Object` here and matched the
empty-object branch of the noImplicitAny element-access check (an any/string key on a
no-members/no-index object). tsc's own `moduleNameResolver.ts` does
`ModuleResolutionKind[moduleResolution]` twice. Fix: exclude an enum-object receiver from that
branch (mirrors the enum exclusion the sibling `tryEmitNoImplicitAnyIndexAccess` already had).
3 local tests (EnumReverseMappingIndexTest) with an empty-`{}` still-fires control. **ATTEMPTED
+ REVERTED — nullish-strip in the `NonNullExpression` case of `getTypeOfExpression` (`(T |
undefined)!` → `T`, the deferred "broader change"):** measured net −17 (2,639 → 2,622, removing
9 TS2322 + 8 TS2345 + 5 TS2362 + 1 TS2365) BUT it UNMASKED 6 M3 gaps — huge object-literal-vs-
interface FPs (`const program: Program = {…}` at program.ts:1876, transformer.ts:271, whose
incomplete structural comparison now rejects a member whose `x!` type became precise), a
generic-inference TS2345, and a new arithmetic TS2365. Correct + tsc-faithful, but it violates
the clean-no-swap discipline and the 6 exposed gaps are M3 (object-literal-vs-interface / generic
inference) — better landed WITH those M3 fixes so the dashboard stays clean. Reverted; noted for
M3.1/M3.3. Session total (406+407): self-compile 2,663 → 2,639 (−24) on FIVE clean bounded
buckets, all from bucketing the full `--listAll`.

**Round 405 (2026-07-04) — M1.12 continued: TS2774×1 fixed (self-compile 2,664 → 2,663);
TS7019×4 investigated and RECLASSIFIED to M3.2-gated. Suite 9,031 → 9,034 (+3 local); 1 commit.**
Same session as round 404, second sub-step. Method: ran a full `--listAll` on the compiler
profile, bucketed the tail, and worked the two remaining "bounded" candidates. **TS2774×1
(checker.ts:24702 `if (shouldElaborateErrors)` where `let shouldElaborateErrors = reportErrors`):**
`reportErrors` is a boolean PARAM of the enclosing `signaturesRelatedTo`, but the uncalled-function
check's syntactic pass establishes no local param scope, so its
`getTypeOfExpression(reportErrors)` resolved in file/global scope and found the sibling
`function reportErrors` (a callable) → mis-typed `shouldElaborateErrors` as a function → FP TS2774.
Round 403 had fixed the shadow-registration + lookup-stop; this last one was the initializer-type
resolution. Fix (`collectUncalledTypedLocalsFromBody`): type a bare-identifier initializer from
the uncalled check's OWN scope knowledge — `shadowed`/`into` for a binding in THIS scope being
collected (an enclosing param / earlier local, not yet on the stack), or
`isUncalledShadowed`/`lookupUncalledTypedLocal` for an ENCLOSING scope already pushed (a `let X =
param` in a nested block) — instead of the unreliable global `getTypeOfExpression`. A boolean param
→ boolean (no TS2774); a same-scope local FUNCTION is still recorded callable, so a genuine
`let f = localFn; if (f)` keeps firing (the negative-control test). listAll diff = exactly one line
removed, nothing added. 3 local tests (UncalledFunctionParamTypeTest). **TS7019×4 (arrow rest
params `compilerHost.getSourceFile = (...args) =>`, `host.writeFile = (…, ...rest) =>`, and a
callback-arg): reclassified from "M1.6 territory" to M3.2-gated.** These arrows receive a contextual
function type from the assignment LHS member (or callee param), so tsc doesn't emit TS7019. A first
attempt propagated the LHS type into the implicit-any `BinaryExpression` case (gated to rest-param
RHS, suppression-only), but it was a NO-OP (self-compile unchanged) — root cause:
`getTypeOfExpression(compilerHost.getSourceFile)` returns `any` because the implicit-any pass sets
up NO enclosing-function param scope (`compilerHost` is a param, absent from `currentFileLocals`),
so the LHS type never resolves to a function. Reverted the dead code cleanly (working tree = HEAD).
The fix needs param scopes threaded into the implicit-any pass (or a real contextual-typing pass) —
M3.2, not a bounded fix. **META: BOTH remaining "bounded" candidates were gated on the SAME
underlying gap — the specialized syntactic passes (implicit-any, uncalled-function) resolve
identifier/property types WITHOUT the enclosing function's param scope, so a bare identifier
resolves to the wrong outer/global binding. TS2774 was fixable because the uncalled check ALREADY
tracks a scope stack I could consult; TS7019 is not, because the implicit-any pass tracks none. This
confirms the M1.12 note: the bounded pool is exhausted and remaining self-compile progress is
M3-gated (M3.2 contextual typing / M3.4 flow-into-identifier-typing).**

**Round 404 (2026-07-04) — M1.13 DONE: `typeParamInternCache` is now keyed file-aware
`(internSalt, pos)` instead of bare `pos`. Corpus 9,026 → 9,031 (+5 local, 0 regressions);
self-compile compiler profile 2,664 → 2,664 (by-code map UNCHANGED); 1 commit.** The proper
"fix the KEY" the item mandated: the bare AST `pos` COLLIDES across files in a multi-file
program (each file starts at 0), so two unrelated params in different files shared ONE
`Type.TypeParam` and stomped its mutable `.constraint`/`.default`. Round 403 had fixed only
the one OBSERVED FP (a read-site re-set in `checkConstraintsForTypeArgs`); the two hot-path
factory builders (`getTypeOfFunctionExpression`/`buildMethodType`, which set the constraint
INSIDE the `getOrPut` factory → stale on a cache hit) and ~16 loop sites were still latent.
Fix: (1) a `TypeParameter.internSalt` BODY property (excluded from data-class
`equals`/`hashCode`/`copy` — TypeParameter is never copied), stamped by the parser as
`fileName.hashCode()` (one `.also{}` in `parseTypeParameter` + a `typeParamFileSalt` field);
(2) a `Checker.internKey(tp)` = `(salt.toLong() shl 32) or (pos and 0xFFFFFFFF)` (injective
over `(salt,pos)`); (3) all 20 intern sites switched from `getOrPut(tp.pos)` to
`getOrPut(internKey(tp))`; cache type `Map<Int,…>` → `Map<Long,…>`. **Corpus byte-identical
by construction: a single-file compile stamps every param with the same salt so the key is a
bijection with `pos` — interning is unchanged; a multi-file program gets distinct salts per
file so the collision (and the factory-site stomping the read-site fix never covered) vanishes
at the KEY.** No walk, no node-identity map (structural equality would re-collide), no
threading through parser constructors — the parser already has `fileName`. **The item's
explicit "measure after the proper fix" MEASURED: self-compile unchanged, by-code map identical
(TS2339×838, TS2322×794, TS2345×405, TS7006×301…). The identity-separation hypothesis — that
some M3-bucket TS2322/TS2345 FPs were stale-constraint artifacts — is FALSIFIED for the
self-compile.** Still worth keeping: it resolves the item with the mandated principled fix,
removes a real latent bug class, and de-risks the belt-and-suspenders per-call re-resolution at
the read site (now no longer the ONLY safety). 5 local tests (TypeParamInternKeyTest):
reverse-order collision, generic-function collision, 3-file cross-contamination, single-file
corpus-safety, genuine-violation negative control. New CLAUDE.md gotcha flags the OTHER
pos-keyed caches that store per-decl mutable state across files (grep `getOrPut(...pos)`) as
carrying the same latent hazard. **META: a bug class the single-file corpus is structurally
blind to and the self-compile dashboard doesn't surface either — validated only by a
purpose-built multi-file ProjectCompiler repro. When the observed symptom is already patched at
a read site, the generalized KEY fix is dashboard-neutral but still closes the latent surface.**

**Round 403 (2026-07-04) — self-compile burn-down (THREE more bounded bugs, one a
genuine multi-file checker bug) + a codebase-wide code-quality sweep the owner
requested mid-session. Self-compile (compiler profile) 2,680 → 2,664 (−16); suite
9,017 → 9,026 (+9 local); 5 commits.** By-shape histogram of the full `--listAll`
again (the M1.12 method): **(1) TS2344 6 → 3 — a genuine MULTI-FILE cross-file bug, not
a lib gap.** `checkConstraintsForTypeArgs` interns each generic's type params as SHARED
`Type.TypeParam` via `typeParamInternCache`, keyed by the parameter's absolute AST `pos`
— which COLLIDES across files (each file's positions start at 0). An UNCONSTRAINED param
(`LexicalEnvironment<in out TEnvData, TPrivateEnvData, TPrivateEntry>`'s 3rd param) got
back the very instance a pos-colliding `<X extends {}>` param in another file left with a
stale `.constraint`, and line 108601 only SET a constraint (never CLEARED one), so the
`{}` leaked in → spurious TS2344. Fix: always (re)set `.constraint`/`.default` from the
current node (clear to null for an unconstrained param). Single-file positions never
collide → corpus byte-identical. Validated with a 2-file repro that FAILS on pre-fix code
(TypeParamConstraintCrossFileCollisionTest, 3 tests). This is the FIRST bounded bug in the
session that was a cross-file/multi-file checker defect (the corpus is single-file so it
never surfaced there). **(2) SetIterator/MapIterator lib gap — TS2552 4 → 0, TS2304 3 →
2.** `SetIterator<T>`/`MapIterator<T>`/`ArrayIterator<T>` live in `lib.es2015.iterable.d.ts`
(available at es2020, tsc's own base), so tsc's `core.ts`/`transformers/utilities.ts` use
them with 0 errors; the embedded lib lacked them → FP TS2552/TS2304. Added as arity-1
empty interfaces; corpus-neutral (0 generated baselines reference them; the sole corpus
user `iterableTReturnTNext` isn't in the generated set). 2 local tests. **(3) TS2774 9 → 4
— local-var-shadows-outer-function.** The uncalled-function check registered a local's
shadow only when the local's initializer TYPE resolved, so `const emitComments =
state.stack[i] = shouldEmitComments(node)` (element-access-assignment initializer → `any`)
left `emitComments` unshadowed and FP'd against the outer `function emitComments`. Fix:
register the shadow UNCONDITIONALLY (a local decl always shadows regardless of type) AND
make `lookupUncalledTypedLocal` STOP at an inner shadowed-but-untyped scope rather than fall
through to an OUTER nested `function`'s callable entry (the emitter.ts:2911/2912 else-block +
one checker.ts case). The last 1 (checker.ts:24702, `let x = reportErrors`) is a nested-scope
initializer misresolution — separate follow-up. 4 local tests, repros validated to fire on
pre-fix code. **(4) OWNER-REQUESTED code-quality sweep: narrow all 135 defensive
`catch (_: Throwable)` → `catch (_: Exception)`** (130 Checker.kt, 3 Vfs.kt, 2 Parser.kt).
`Throwable` swallows `Error` subtypes — most importantly `StackOverflowError`, which must
reach the `init` boundary guard (→ TS2589) rather than be absorbed into a silently-wrong
default; this was the exact anti-pattern the 2026-07-02 SoE cleanup removed. `Exception`
still catches the genuine recoverable cases (NPE/ClassCast/IllegalState ⊆ RuntimeException
⊆ Exception), so the narrowing is behavior-preserving: full suite byte-identical, self-compile
byte-identical (2,667, no new codes, no crash) EXCEPT Errors now propagate. Validated by
the full suite (exercises all 135 catch paths) + self-compile parity + DeepExpressionChainTest
(pins SoE→TS2589). CLAUDE.md gained a guardrail. Removing the defensive Exception-catching
ENTIRELY (to surface NPEs from incomplete modeling as crashes) is a separate per-site
root-cause effort — NOT attempted blind. **META: the cross-file `typeParamInternCache`
pos-collision (#1) is a class of bug the single-file corpus structurally cannot catch —
worth grepping other `getOrPut(tp.pos)` / pos-keyed caches for the same hazard (20 intern
sites share the cache; the fix mitigated the one READ site, others may still read a
stale-constraint shared instance).**

# PLAN-PHASE-5 — session-note history

Older Phase 17 session notes trimmed from PLAN-PHASE-5.md (most recent first).

**Round 396 (2026-07-04) — self-compile burn-down, the SECOND bounded bucket from round
395's by-shape histogram: TS2440 (type-only import merges with a value-only local).
Self-compile (compiler profile) 2,712 → 2,702 (TS2440 10 → 0), zero corpus regressions,
suite 8,995 / 0 / 3 (+5 local).** tsc's own `src/compiler` imports the type interfaces
`Node`/`Identifier`/`Signature`/`Symbol`/`Type`/`Token`/`SourceMapSource`/`NodeLinks`/
`SymbolLinks` from the `_namespaces/ts.js` `export *` barrel AND declares local
`function Node`/… (AST/object-allocator helpers) + `const SymbolLinks = class`. The
import binds the TYPE, the local binds the VALUE (disjoint declaration spaces), so tsc
reports no error; we FP-emitted TS2440. Fix: `checkImportConflictsWithLocal`'s
named-specifier loop skips the conflict when `importedNameIsTypeOnlyThroughBarrel(sourceName)`
(a NEW conservative `export *`-following resolver — `isExportedNameTypeOnly` inspects only
DIRECT exports, missing barrel-re-exported names; the `.js` specifier needs
`resolveBarrelStarTarget` since `resolveModuleSpecifier` won't strip `.js`) AND the local
is `valueOnlyLocalNames` (function + value-var, MINUS class/enum/interface/typealias/
namespace which have a conflicting type side). **Two LOAD-BEARING gates, both learned the
hard way: (1) `!options.isolatedModules && !options.verbatimModuleSyntax` — a first cut
`continue`d unconditionally and the suite gate caught 3 regressions
(isolatedModulesSketchyAliasLocalMerge ×2, isolatedModulesExportDeclarationType): those
modes DO error on the merge (TS2865 / TS1484 / TS2440) via the var-conflict + case-3
emitters BELOW my guard, so the guard must not pre-empt them; the self-compile sets
neither option so its suppression still fires. (2) the barrel closure is the WHOLE program
(a barrel `export *`s everything), so the per-(barrel,name) result is memoized
(`barrelTypeOnlyMemo`, declared BEFORE init per the init-order gotcha) and the recursion
shares ONE `visited` set — never fresh-per-re-export.** FN-safe: any uncertainty
(unresolvable star, `export { } from`, `export =`, a value found anywhere) → keeps firing.
5 local tests (ImportTypeOnlyBarrelMergeTest, ProjectCompiler multi-file): barrel +
multi-hop type-only-merge positives, a value-import negative control, a local-class
type-side-conflict negative control. **DEBUGGING SAGA (2 rounds lost, now armored in the
benchmark memory + a CLAUDE.md gotcha): the fix "hung" the self-compile at the 2-min tool
timeout — it was MEMORY PRESSURE, not the barrel walk. A manual `-Xmx4g` self-compile atop
the Gradle daemon (~1.8 GB) + KotlinCompileDaemon (~2.7 GB) exceeds the 7.7 GB box → swap.
The tell: a helper STUBBED to return false immediately STILL timed out. Fix:
`./gradlew --stop && pkill -9 -f KotlinCompileDaemon`, verify `free -m` ≥ 5 GB free, then
run (clean self-compile ~62–74 s / ~850 MB RSS). The bench script sidesteps this (fresh
JVM); the manual `--listAll` is only for full-FP-list diffing.** This session (rounds 395
+ 396) took the compiler profile 2,726 → 2,702 (−24) on TWO bounded buckets — validating
the META-LESSON that a "pool picked over / M3-gated" read-only triage is about the
code-path analysis; bucketing the ACTUAL full `--listAll` output by normalized message
shape surfaces bounded parser/checker bugs hiding in the histogram tail. **Next bounded
buckets identified but not yet done (queued for the next session): TS2344×8 (`Type 'T'
does not satisfy the constraint 'Node'` — a type-param arg `T extends Node` passed to a
generic `<U extends Node>`; T's constraint SATISFIES the target constraint but we don't
check the constraint chain in the type-arg path — likely bounded, generic-constraint
satisfaction) and TS2693×7 (`'symbol' only refers to a type` — a `const { symbol } = node`
destructured local variable named `symbol` shadowing the type keyword; a function-body
scope-tracking gap, more involved).**

**Round 395 (2026-07-04) — self-compile burn-down via a bounded PARSER bug the round-394
"pool picked over" triage missed: the multi-base-generic heritage misparse. Self-compile
(compiler profile) 2,726 → 2,712 (−14), TS2499 16 → 0, zero corpus regressions, suite
8,990 / 0 / 3 (+6 local).** Method that found it: bucketed the full `--listAll` output (all
2,726 error lines, not the 30-line log tail) by normalized message shape. Two bounded
non-M3 buckets popped that the round-394 code-path triage had not surfaced — TS2499×16
("An interface can only extend an identifier/qualified-name…") and TS2440×10 ("Import
declaration conflicts with local declaration"). TS2499 was the documented (CLAUDE.md)
multi-base-generic-before-comma misparse, marked "NOT yet fixed / parser fix risk-bearing,
deferred": `interface NodeArray<T> extends ReadonlyArray<T>, TextRange` collapsed the
non-last generic base `ReadonlyArray<T>` into a value-position instantiation expression
(synthetic `ParenthesizedExpression`) that DROPPED its `<T>` type args, so `resolveBaseSym`
returned null AND the checker FP-emitted TS2499. Root cause: `parseExpressionWithTypeArguments`
uses the general `parseLeftHandSideExpression`, whose postfix `<` branch converts `Foo<T>,`
into an instantiation expr because `,` is in `canFollowTypeArgumentsInExpression()`; the LAST
base always worked because `{`/`implements` are NOT in that set. Fix (NOT risk-bearing after
all — heritage-scoped): a `parsingHeritageBase` flag set around the base spine in
`parseExpressionWithTypeArguments`, RESET inside `parseArgumentList` (so a nested
`extends foo(bar<T>)` call arg still parses instantiation exprs); in that context the postfix
`<` branch bails (`typeArgs != null && parsingHeritageBase -> null`, placed BEFORE the
instantiation `canFollowTypeArgumentsInExpression()` branch), `tryScan` restores, and the
type args are re-read as heritage type arguments — matching tsc's
`parseLeftHandSideExpressionOrHigher` (which yields an ExpressionWithTypeArguments verbatim).
A genuine heritage call `extends mixin<T>()` (the `(` produces a CallExpression above the
guard) and a real non-entity-name base (`extends foo()` / `extends (typeof A)`, a
primary-paren/call not an instantiation collapse) still fire correctly. **Delta breakdown
(the honest part): −40 removed FPs (16 TS2499 + 8 TS2769 + 7 TS2345 + 3 TS2322 + 2 TS2339
+ 2 TS2430 + 1 TS2740 + 1 TS2353) vs +26 added (20 TS2322 + 4 TS2339 + 1 TS2345 + 1 TS2769)
= net −14.** ALL 20 added TS2322 are `NodeArray<T>` — the M3.1 generic-inference gap now
VISIBLE because the correctly-resolved base means the comparison runs (previously
`hasUnresolvedTypeParams` bailed on the unresolved `ReadonlyArray<T>` base and suppressed it);
the 4 added TS2339 are `AssignmentPattern`/`PropertyName` union-narrowing (M3.4). So the fix
un-MASKED latent M3.1/M3.4 FPs (honest attribution, not new wrong behavior — corpus green
guarantees it) AND restored non-last-generic-base member inheritance. CLAUDE.md's multi-base
gotcha updated (misparse → FIXED; the B521 `checkMultiBaseInStatement` source-scan workaround
is now redundant-but-harmless). 6 local tests (MultiBaseGenericHeritageTest): sharp
member-inheritance signal (`Sub<number>` inheriting `Container<T>.value` → TS2322 on string
assign, and NOT TS2339), a `extends foo()` TS2499 negative control, a `class implements A<T>, B`
case, and a single-last-base regression control. **TS2440×10 (utilities.ts/checker.ts local
`function Node`/`function Identifier` + imported type-only `Node`/`Identifier` interfaces
through the `_namespaces/ts` barrel — a legal type+value declaration-space merge) is the next
bounded bucket, but it needs a barrel-following (`export *`) type-only resolver
(`isExportedNameTypeOnly` only walks DIRECT exports); queued as a follow-up.** META-LESSON
reinforced: a read-only "pool picked over / M3-gated" verdict is about the code-path triage —
always bucket the ACTUAL full FP output by message shape; bounded parser/checker bugs hide in
the histogram tail even when the top families are all M3.

**Round 394 (2026-07-04) — M2.2 burn-down #4: `delete x.<Object.prototype member>`
now fires TS2790 under real libs. Real-lib A/B recount 29 → 28
(keywordExpressionInternalComments fixed), zero corpus regressions, suite 8,983 / 0 / 3
(+6 local).** Root cause (a genuine correctness gap the richer lib EXPOSED, not a
compensating hardcode): `getApparentType` does NOT fold `Object.prototype`'s members
(`toString`/`valueOf`/`hasOwnProperty`/…) into an object type's apparent members — it only
maps type-params → constraints and primitives → wrapper interfaces; a
`Type.Object`/`Interface`/`Reference` passes through unchanged. Under the embedded lib
`Array` (value position) resolves to the `Array<any>` INSTANCE, which declares its OWN
`toString`, so `getPropertyOfType` finds the member and TS2790 fires; under real libs
`Array` → `ArrayConstructor`, which has NO own `toString` (inherited from
`Object.prototype`), so the member was missed and no error fired (round 393 had flagged
this as "we emit NOTHING under real libs — the getApparentType-Object.prototype gap →
M3"). Fix: an Object.prototype fallback in the TS2790 delete check (Checker.kt ~54234) —
when the receiver is object-like (`objType is Type.Object`), the member name ∈
`OBJECT_PROTOTYPE_PROPERTIES`, and the type has no own declaration of it (`propSym ==
null`), emit TS2790. FP-safe BY CONSTRUCTION: `delete x.<objProtoMember>` is ALWAYS
TS2790 under strictNullChecks (those members are non-optional and present on every
object — matches tsc), the fallback is scoped to the 7 Object.prototype names, and a user
type that declares the name OPTIONALLY still routes through the own-member branch
(`propSym != null` → optional → no emit). Folding Object.prototype into `getApparentType`
generally is the broad M3 change (touches every relation) — deliberately NOT done; the
narrow delete-local fallback closes the one shape the corpus needs and a latent embedded
FN (`delete x.constructor` etc. where the receiver lacks an own decl). 6 local tests
(DeleteObjectPrototypeTs2790Test): real-libs positive (toString, valueOf), embedded
regression control (own-member branch unchanged), own-optional-member negative,
index-signature-non-prototype-name negative, strictNullChecks-off negative. New CLAUDE.md
gotcha on the getApparentType Object.prototype gap. **SECOND clean win, same session
(jsExportMemberMergedWithModuleAugmentation2, A/B 28 → 27): the B553 CJS-string-import
spelling-suggestion TS2728 now attributes lib-first.** The `emitCjsStringImportMethodAccess`
walker (`name.<method>()` where `name` is a `string`-typed CJS import → TS2551 "did you mean
'<sugg>'?" + a TS2728 "declared here") built its related-info via the position-based
`resolveDeclarationSourceFile`, so under multi-file real libs the suggestion `fixed` (a
DEPRECATED HTML helper on the real `String` interface) false-matched the large `/index.ts`
(`/index.ts:8:18528`) instead of `lib.es2015.core.d.ts:--:--`. This is the SAME lib-file
attribution bug round 392 fixed at three TS2728 builders (`findDeclarationRelatedInfo`, the
property-suggestion site, `createPropertyDeclaredHereRelatedInfo`) — the B553 walker was
simply an unwired 4th path. Fix: consult `libFileOfDecl(decl)` (node-first `realLibDeclFile`
map) BEFORE the position path; the map is empty under the embedded lib so the embedded path
is byte-identical (guaranteed). The `DEPRECATED_STRING_HTML_HELPERS` override
(`fixed`/`sub`/`sup`/… → `lib.es2015.core.d.ts`) still fires on top of the node-first
attribution. 1 local test (RealLibsTs2728FileTest, the multi-file CJS shape). A preventive
audit of all TS2728 sites found ~7 others still on the position path — all emit at user-decl
"declared here" positions (duplicate-identifier / user re-decl) that never target a lib
member, so speculatively wiring them is scope creep; flag them only if a future A/B test
exercises a spelling-suggestion / missing-lib-member on those paths. **Both fixes are the
round-317-324 META-LESSON again: a read-only-triage "ENGINE / M3" verdict is about the
GENERAL fix — a corpus-unique, FP-safe, narrow fallback can still flip a test the triage
rated engine-gated. Re-check "ENGINE" sub-verdicts against corpus-uniqueness + a
tightly-gated local fix before trusting them.** Batch A/B run this session also confirmed
the OTHER five sampled candidates ARE genuinely engine-gated (data, not guessing):
typedArraysCrossAssignability01 (B496 pin double-emits alongside the real generic
typed-array relation → M2.3 unwind), narrowingPastLastAssignment (`[]`=`any[]` B87.6 vs
`number[]` concat-return relation FP), correctOrderOfPromiseMethod (`Promise.all` const-tuple
inference → M3.1), dissallowSymbolAsWeakType (`new FinalizationRegistry(() => {})` leaves the
generic `T` unresolved → `f.register(s, null)` FP TS2345 `null ≁ T` → M3.1),
interfaceAssignmentCompat / mergedClassNamespaceRecordCast (Record materialization / dedicated
walkers under real libs → M3.3). **Self-compile map refreshed this session (compiler profile,
`MainKt --noEmit --listAll`, current HEAD): 2,728 errors — round 394's checker changes are
INERT (delete-TS2790 count 0, confirmed both by a grep of tsc's source finding zero
`delete x.<objProtoMember>` shapes and by the listAll; TS2728 is related-info-only). The +2
vs round-389's 2,726 predates this session (intervening commits 390–393 + the warning
cleanup — most likely the warning-cleanup's `minArgumentCount` correctness fix, which is a
tsc-more-accurate change, not a regression). The M1.4 family map is STABLE: TS2339×836
(M3.4 union-receiver narrowing), TS2322×777 (M3.1 generic call-site inference, top shape
`Type 'T[]'`), TS2345×411, TS7006×303, TS2769×67, TS2366×50, TS18048×34, TS2349×25,
TS2365/TS2362 (~40 arithmetic), TS2563×27 (B399 heuristic FPs → M3.4). ~70 of the 2,728
are env-legit: TS2591×43 (`process`/`require`/`Buffer` node globals — resolved by
`--node-stub`/real @types/node) + TS2563×27 (B399). The rest are the M3 cores — no new
narrow non-M3 slice remains (M1 peeled them all). Next-session guidance: the M2.2
narrow-fallback pool is largely picked over (this session's two wins were the last of the
lib-attribution / apparent-type-gap category); further M2.2 progress is gated on the M3
engine items these failures share — prefer advancing M2.3 (typed-array/lib-pin unwind, which
overlaps the M2.2 typedArrays/templateStringsArray/builtinIterator failures) or a decomposed
M3.1/M3.3/M3.4 sub-step (which unblocks both M2.2 AND the self-compile dashboard).**

**Round 393 (2026-07-04) — M2.2 burn-down #3: the lib-declared utility-alias
modifier cluster + the redefineArray construct-sig double-emit. Real-lib A/B recount
34 → 29 corpus failures (omitTypeHelperModifiers01, omitTypeTestErrors01,
intersectionsAndOptionalProperties, parameterListAsTupleType, redefineArray fixed),
zero corpus regressions, suite 8,977 / 0 / 3 (+6 local).** Two fixes, both "fix by
convergence" (make the real-lib path behave like the embedded path that already passes
the corpus): (1) **Utility-alias materializer routing.** Under `useRealLibs` the lib's
`Pick`/`Omit`/`Readonly`/`Parameters`/`ConstructorParameters`/`ReturnType` resolve to a
real `TypeAlias` symbol, so `getTypeFromTypeReference`'s generic alias-substitution path
expands their real definitions — `Omit<T,K> = Pick<T, Exclude<keyof T,K>>` → the
non-homomorphic mapped type `{ [P in K]: T[P] }` — and DROPS the optional/readonly
modifiers (our `getTypeFromMappedType` doesn't yet treat `[P in K extends keyof T]` as
homomorphic → M3.3). The embedded lib does NOT declare these names, so under it they hit
the modifier-preserving `materialize*` dispatch (symbol==null). New
`isBuiltinUtilityAlias(name, symbol)` (name ∈ the six utilities +
`symbol.declarations.all { it in builtinLibDecls }`) routes a lib-only utility symbol
through the same materializers so both paths agree — the materializers REUSE the source
property Symbols, so `readonly`/`?` survive. Fixed 4 (Omit modifier-preservation +
key-removal; Parameters/ConstructorParameters signature utilities). Real-libs-only in
practice (embedded resolves these to null → byte-identical); a user `type Omit<…>` shadow
keeps a non-lib declaration → gate false → user def wins (negative-control test).
(2) **redefineArray construct-sig double-emit.** Under real libs `ArrayConstructor`
carries a construct signature, so `Array = fn` fired BOTH B444's TS2739 (missing
isArray/from/of/[Symbol.species]) AND a construct-/call-sig mismatch TS2322 — tsc reports
a structural relation failure ONCE (the missing-property error). Guarded the assignment
path's 17.111 construct-sig branch AND the general `canUse && !isAssignable` block with
`!targetHasRequiredPropAbsentFromSource(source, tt)`. **LANDMINE:
`collectMissingProperties`/`getMissingRequiredPropertySymbol` BAIL (return empty/null)
when `source.members` is null — a bare-function source (`callSignatures` only) has null
members — so a new null-tolerant helper was required.** The guard fires ONLY when props
are genuinely missing, so a source satisfying all named props but failing only the
signature still reports the coarse TS2322 (positive control: a construct-only interface
`{new():X}` with no named props → no missing → TS2322 stands). 6 local tests
(RealLibsUtilityModifiersTest ×4, RealLibsCtorAssignTest ×2). No self-compile dashboard
delta (corpus-A/B fixes; default stays off). **Triage of the remaining 29 (for the next
burn-down): mostly M3 engine** — mapped/conditional/indexed-access/variance
(requiredMappedTypeModifierTrumpsVariance keeps the `Required<>` wrapper in display +
Required's modifier flip; mappedTypeGenericWithKnownKeys wants TS2862 generic-Record;
mappedTypeIndexedAccessConstraint, specialIntersectionsInMappedTypes, keyRemappingKeyofResult,
genericIndexedAccessVarianceComparisonResultCorrect), intrinsic string-mapping
(stringMappingAssignability `Uppercase<string>`), Iterator abstract inheritance
(builtinIterator); **DOM-dependent** (`@lib: dom` — divergentAccessorsTypes6/8,
truthinessCallExpressionCoercion2 → post-v1/M2.4); **M2.3 pin-unwind**
(typedArraysCrossAssignability01's generic `Uint8Array<ArrayBuffer>` needs the B496 pin
retired + the real typed-array structural relation; templateStringsArrayTypeRedefinedInES6Mode
is NOT a simple B533 gate — verified the actual real-lib diff: the empty `class
TemplateStringsArray {}` merges with the real `interface TemplateStringsArray extends
ReadonlyArray<string>`, and the GENERAL arg-check missing-prop path fires a SECOND TS2345
whose message is INHERITED-FIRST + wrong (`length, concat, join, slice, and 17 more` +
a spurious TS2728 `'length' is declared here`) — tsc lists the OWN merged member `raw`
FIRST (`raw, length, concat, join, and 17 more`) with NO TS2728 for a ≥2-missing set.
B533's HARDCODED message is the correct one, so the fix is to suppress the GENERAL path
here (own-member-first ordering under class+interface merge + multi-missing TS2728
suppression), NOT to gate B533); **apparent-type Object.prototype gap** (keywordExpressionInternalComments
`delete Array.toString` — `getApparentType(interface)` must include Object.prototype's
`toString` for the TS2790 delete check to resolve the member → M3); **checkJs augmentation**
(jsExportMemberMergedWithModuleAugmentation2). None are clean-win-shaped like Omit/redefine.

# PLAN-PHASE-5 session-note history

**Round 392 (2026-07-04) — M2.2 burn-down #2: the TS2728 lib-file-attribution
cluster. Real-lib A/B recount 38 → 34 corpus failures (libMembers + externModule +
errorMessageOnObjectLiteralType fixed, initializedDestructuringAssignmentTypes also
cleared), zero corpus regressions, suite 8,971 / 0 / 3 (+3 local).** Sampled a fresh
12-test slice of the 38; three (externModule, errorMessageOnObjectLiteralType, plus
last session's libMembers) shared ONE root cause: under `useRealLibs` the default
library is SPLIT across many files (`lib.es5.d.ts`, `lib.es2015.core.d.ts`, …), each
parsed independently so positions OVERLAP (every file's nodes start at 0). The TS2728
"declared here" related-info builders resolved the declaring file by POSITION
(`resolveDeclarationSourceFile`), which under multi-file libs cannot disambiguate AND
false-matches a large USER file whose text happens to span the lib position (a lib
`sub` decl's position landed on `subby` in libMembers.ts → `libMembers.ts:15:19748`
instead of `lib.es2015.core.d.ts:--:--`). Fix: NODE-first attribution — `bindRealLibs`
populates `realLibDeclFile: Map<Node, String>` (every lib statement / interface-class
member / inner var-decl node → its DIST fileName), and the three TS2728 builders
(`findDeclarationRelatedInfo`, the property-suggestion site, `createPropertyDeclaredHereRelatedInfo`)
consult `libFileOfDecl(decl)` BEFORE the position path. Real-libs-scoped by
construction: the map is EMPTY under the embedded lib (single file) → embedded path
byte-identical (guaranteed, verified by the full embedded gate). The existing
`DEPRECATED_STRING_HTML_HELPERS` override (sub/sup/… → `lib.es2015.core.d.ts`) still
fires on top — it only needs `isLib` true, which the map now guarantees. 3 local tests
(RealLibsTs2728FileTest): non-es5 member → its real lib file (not es5), es5 member →
`lib.es5.d.ts`, and a USER member control (still the user file with a real position —
proving the map is lib-only). No self-compile dashboard delta (corpus-A/B fix; default
stays off). **Round-392 triage of the other 9 sampled failures (for the burn-down):**
correctOrderOfPromiseMethod/narrowingPastLastAssignment/keyRemappingKeyofResult = extra
TS2322 from richer lib generics (Promise.all const-tuple, evolving-array concat, mapped
key-remap → M3 engine); omitTypeHelperModifiers01 = SWAP TS2540↔TS2322 (Omit modifier +
readonly); mergedClassNamespaceRecordCast/interfaceAssignmentCompat/divergentAccessorsTypes6
= MISSING (Record-cast overlap + documented walkers under-fire); builtinIterator =
duplicate TS2515 (short vs full `Iterator<…>` display); doYouNeedToChange… = `Promise<T>`
vs `Promise<unknown>` display; keywordExpressionInternalComments = we emit NOTHING under
real libs (investigate — possible exception, unusual).

**Round 391 (2026-07-04) — M2.2 first burn-down: the real-lib A/B failing set
drops 40 → 38 (arguments + unaryOperatorsInStrictMode), zero corpus regressions,
suite 8,968 / 0 / 3 (+3 local).** Method: temp-flipped the `useRealLibs` default
to true, ran a diverse 8-test slice of the 40, extracted the actual diffs from the
result XMLs, and triaged them into distinct failure modes (recorded in the M2.2
item below for the next burn-down session). The cleanest first fix — a genuine
correctness bug the richer lib EXPOSED, not a compensating hardcode to gate: under
`useRealLibs` the real lib's `interface IArguments` (type-only, no `declare var`
companion) leaked into the VALUE-position spelling-suggestion candidate pool, so an
unresolved value-position `arguments` drew "Did you mean 'IArguments'?" (TS2552)
where tsc emits a plain TS2304. The embedded lib had no `IArguments` at all, which
is exactly why only the real-lib A/B surfaced it. Fix (Checker `getSpellingSuggestion`):
classify type-only symbols (Type flag, no Value/Module) from `perFileScope[fileName]`
— lib globals + cross-file script locals, the SAME source the value-position pool
draws from — into `typeOnlyNames`, not just the current file's binder locals. The
type-position branch never consults `typeOnlyNames`, so the fix is structurally
value-position-only; noted a symmetric latent FN (the type-position pool won't
suggest a type-only LIB global for a mistyped TYPE — no corpus test exercises it).
Lib-agnostic (embedded stays green; the fix only removes wrong suggestions that
depended on the richer lib being present). 3 local tests (SpellingSuggestionTypeOnlyTest)
+ two controls (`Object` still suggested in value position; a user interface still
suggested in type position). No self-compile dashboard delta (a corpus-A/B fix;
default stays off). **Triage of the other 7 sampled failures (for the next
session):** redefineArray = construct-sig TS2322 double-emitting alongside TS2739
(tsc reports only missing-props; our B112 pre-gate fires because real ArrayConstructor
HAS a construct sig — embedded didn't); libMembers = TS2728 "declared here" related
points at the wrong file/pos (`libMembers.ts:15:…` vs `lib.es2015.core.d.ts:--:--`)
because `resolveDeclarationSourceFile`/`isLibFileName` only know the FIRST real-lib
file (M2.1d's acknowledged multi-lib-file position ambiguity); isArray = `Array.isArray`
type-guard narrowing not applied under real libs → extra TS2339 (M3.4);
dissallowSymbolAsWeakType = extra TS2345 `null` ≁ `T` (generic inference, walker+engine
interaction); truthinessCallExpressionCoercion2 = one MISSING TS2774; implementArrayInterface
= extra TS2420 (9 missing es2015 Array methods) + TS2416-some (B537 semantics, implements-vs-array).

**Round 390 (2026-07-04) — M2.1(a) landed: the real TypeScript lib sources ship
as generated Kotlin (`RealLibFiles.kt`, 100 non-DOM lib files / 565,732 bytes,
keyed by bare lib name, byte-faithful incl. CRLF).** `generateRealLibSources`
(build.gradle.kts) reads the pinned commit's object DB directly (`git ls-tree`
+ `git show` — offline; the sparse working tree never materializes `src/lib`)
and emits ≤ 60,000-modified-UTF-8-byte `sb.append` chunks per string literal
(the 64 KB class-file constant cap; es5.d.ts = 4 chunks), wired as a commonMain
srcDir with every Kotlin compile task depending on it (first compile in a fresh
clone now needs typescript-repo, same as tests always did). 3 local tests
(RealLibFilesTest) pin multi-chunk reassembly (es5 > 65,535 chars with
first/middle/last-chunk anchors) and the `/// <reference lib>` directives
M2.1(b)'s DAG resolver will consume. No dashboard delta (no runtime behavior
change — the checker doesn't read RealLibFiles yet). **Debugging saga worth the
note: the first cut's KDoc contained the path glob `src/lib/*.d.ts` — the `/*`
in it opened a NESTED Kotlin block comment that the NEXT declaration's KDoc
`*/` re-balanced, so build.gradle.kts COMPILED with a silently-dead region:
tasks registered after the comment were "not found", top-level probe statements
never executed, and even an appended `this is a syntax error!!!` line "BUILT
SUCCESSFULLY" (it sat inside the swallowed region).** The tell that cracked it:
a deliberate EOF syntax error still building → the content can't be what's
compiling → comment-depth scan found the imbalance. (A raw NUL byte from a
tool-input NUL-char literal was a red herring fixed first.) CLAUDE.md's
block-comments-NEST gotcha gained the silent-dead-region variant.
**Same round, M2.1(b): `RealLibResolver` landed** — tsc's `libMap` (110 entries,
aliases + back-compat fallbacks), `targetToLibMap` defaults, the reference-lib
closure, and the priority ORDER (`getDefaultLibFilePriority` = libEntries index,
not DFS — es5 pulls in decorators, which still sorts last). Unknown names and
unshipped DOM references surface via `Resolution.unknownNames`/`.unavailable`.
One expectation fixed mid-test: `esnext.bigint` alone expands to THREE libs
(es2020.bigint's own directives pull es2020.intl → es2018.intl). 6 local tests
against the real headers. Suite 8,957 / 0 / 3.
**And M2.1(c): `RealLibSnapshots`** — parse-once shared ASTs (dist file
names), fresh binds per consumer (mergeSymbolTable mutates merged-in symbols
→ shared bound tables would cross-pollute programs), `useRealLibs` flag
(default off). The real es5.d.ts parses + binds cleanly on the first try.
4 local tests. Suite 8,961 / 0 / 3.
**And M2.1(d): checker wiring + the corpus A/B.** `bindRealLibs()` behind
`useRealLibs` (+ directive); cross-lib-file interface merging proven by
`[1,2,3].includes(2)` under `@lib: es2016`. A/B with the default temporarily
flipped: **40 / 8,961 failures, all error-baseline, zero js-emit — the M2.2
burn-down list is seeded in the queue item.** Wall time +70% under real libs
(fresh per-program binds of ~240KB+ of lib source) — noted as an M2.2
pre-flip task. Suite (default off) 8,965 / 0 / 3. M2.1 is COMPLETE.

Archived Phase-17 session notes trimmed from PLAN-PHASE-5.md (most recent first). See PLAN-PHASE-5.md for the live queue + the ~10 most-recent notes.

**Round 389 (2026-07-03) — M1.11 landed: self-compile 2,794 → 2,726 (−68;
TS2554 45 → 0, TS2345 424 → 411, TS2769 77 → 67, zero new codes) — M1 is
COMPLETE.** The "nested-function shadowing" item decomposed into five shapes
once each of the 45 TS2554 sites was traced to its declaration (the item's
own three samples were all different shapes): parameter shadowing (identifier
+ destructured + fn-typed params), body-local variable shadowing, the
namespace-flattening leak (`collectFuncDecls` recursed into ModuleDeclaration
bodies, making parser.ts's namespace-local 0-param `isExternalModuleReference`
hijack the file-level call site — now a body-scoped overlay collected at the
walker's ModuleDeclaration branch, with the inherited-ctor fixpoint extracted
and re-run per namespace), constructor-overload arity (only the FIRST ctor
signature was recorded — semver.ts's `Version(text)`/`Version(major,…)` pair
now records an isOverloaded RANGE), and spread-argument too-few unsoundness
(a spread counts as 1 arg but expands to ≥0, so `argCount < min` is unprovable
— too-many stands since spreads only add). Arity fixes are all
removal/bail-shaped (`minusParamShadowedNames` at every fn-body descent +
the `argCountFnDepth`-gated list-level var removal — the depth gate keeps
top-level B64.2 var-arrow entries checked). Type path: two mechanisms —
`populateParameterLocalTypes` now registers an UN-annotated param whose
DEFAULT is an arrow/fn-expr with the initializer's inferred type (emitter.ts
passes such params straight through as args: 5 FP TS2345 showing the outer
5-param signature vs `() => string`), and `shadowNestedFunctionNames`
(call-types walker, after the fn-body map copy) registers an anyType BAIL for
each body-nested fn whose name collides with an outer binding — B83.5 leaves
them unbound, so `getCalleeType`/`getTypeOfIdentifier` fell through to the
merged globals and found the utilities `writeFile` import (the
'undefined' ≁ 'string' FP at emitter.ts:1331). The −10 TS2769 were unbudgeted:
declarations.ts's nested fns colliding with overloaded imports fed the
overload path the same wrong signatures. 13 local tests
(NestedFnShadowingTest), every suppression paired with a negative control
proving the unshadowed check still fires. Residue intel: the last
TS2345-'undefined' (debug.ts:599) is NOT this family — it's assignment
narrowing through an `as`-cast RHS (`nodeArrayProto = Object.create(…) as
NodeArray<Node>` inside `if (!nodeArrayProto)`) → M3.4/narrowByAssignmentRhs
territory. Next by family: TS2339×836 (M3.4), TS2322×777 (M3.1 top shape),
TS2345×411, TS7006×301 (M1.6(c) call-arg contexts — callee doesn't resolve).**

**Round 388 (2026-07-03) — M1.9 + M1.6(a)+(b) + M1.8 + M1.10 all landed:
self-compile 4,376 → 2,794 (−1,582, −36.2%), five code commits, zero corpus
regressions (suite 8,935 / 0 / 3, +39 local tests). M1 is COMPLETE except the
newly-filed M1.11.** Fifth item, M1.10 (fe65a3cc, −64 exactly — TS2540 GONE):
the parser consumed `-readonly` in a mapped type without recording the sign,
and homomorphic mapped members carry their SOURCE declaration, so writes
through tsc's `Mutable<T>` idiom FP'd; `MappedType.readonlyMinus` +
`mappedMutableMemberIds` (inverse side-channel, checked first) + the
symmetric plain-`readonly`-token registration. Residue intel from the fresh
listAll: TS7006×301 = call-arg contexts whose callee doesn't resolve
(`makeFunctionTypeMapper(t => …)` — M1.6(c) territory); TS2322's top shape is
`Type 'T[]'` ×174 (generic call-site inference, M3.1); TS2554×45 is
nested-function shadowing → filed as M1.11. Per-item deltas (each bench-isolated):
**M1.9 (b4c15a22, −133)** — the "undefined lost against union targets" item
over-delivered because the union member was never lost in the RELATION; five
distinct emitters were at fault (return-path string fallback running after the
engine CONFIRMED assignability — B325's early return had never reached the
return path; enum-member union aliases resolving to anyType → the syntactic
`aliasUnionContainsNullishKeyword` skip; assignment TARGETS checked against the
guard-NARROWED type instead of the declared one → `narrowedDeclaredTypes` at
both dispatcher arms; the main arg path missing M1.7a's undefined-to-optional
rule for primitive/namespace-nested params; 17.20 firing on the sig's OWN
inferable bare TPs). TS2345-undefined 100 → 2, TS2322-undefined 70 → 0.
**M1.6(b) (0e38be5a, −446; TS7006 1554 → 1111)** — `contextualCallableArity`:
a plain callable contextual slot suppresses TS7006 up to its arity (rest =
unbounded; beyond-arity keeps firing per B224) in the arrow/fn-expr/
object-literal-METHOD branches + return-annotation threading
(`returnCtxAnnotation`, lazy resolution at the ReturnStatement). The real
checker.ts factory is a VAR-DECL annotation (`const checker: TypeChecker =
{...}`), not the return shape the map predicted — the plumbing existed, only
union-with-primitive slots suppressed. The suite gate caught the ONE corpus
pin: members reached through a union-with-non-object literal context must NOT
suppress (`ctxViaUnionWithPrimitive`;
contextualOverloadListFromUnionWithPrimitiveNoImplicitAny). **M1.8 (d31be6be)
+ M1.6(a) (4e048750), combined row −939 (TS7006 1111 → 301 = exactly
visitorPublic's ×810; TS7030 122 → 0; TS2366 57 → 50; TS7019 7 → 4)** — M1.8
aligned `checkBodyForImplicitReturn` with tsc's
checkAllCodePathsInNonVoidFunctionReturnOrThrow read from the offline sources
(TS7030 = noImplicitReturns-ONLY; TS2366 gated on resolved
undefined-assignability via `returnAnnotationAcceptsUndefined`; per-empty-return
TS7030 additionally `!strictNullChecks` — under strict an empty `return;` is
the TS2322 return-expression path); the queue's "audit which corpus baselines
pin the current disjunct" came back EMPTY (first-try green). M1.6(a):
`mappedAnnotationValueFnArity` derives the computed-enum-key mapped-table
members' contextual arity from the AST (annotation → alias → MappedType →
value alias → FunctionType), threaded as `ctxAnnotation` — no mapped-type
engine work needed. Two process notes: (1) **same-position masking** — M1.9's
TS2322 removal at empty `return;` sites SURFACED 8 pre-existing TS7030 FPs at
identical positions (a +N in an unrelated code after an FP fix: check position
overlap before calling it a regression); it became M1.8's repro. (2) The
M1.8+M1.6a bench row is marked +dirty from uncommitted DOCS edits only — the
compiled code is exactly 4e048750. New top families: TS2339×836 (the M3.4
union-receiver narrowing bucket), TS2322×777, TS2345×424, TS7006×301 (residue:
call-arg/uncontextualized shapes), TS2769×77, TS2540×64.**

**Round 387 (2026-07-03) — M1.3 landed: tsconfig `types`/`typeRoots`/@types acquisition
+ bench `--node-stub`. Self-compile: no-stub control EXACTLY 4,456 (acquisition inert
under `types: []`); stub run 4,456 → 4,411 (−45 env-legit, zero new codes). Suite
8,888 / 0 / 3 (+9 local).** Mechanics: `ProjectCompiler.collectTypeRootEntries` +
`effectiveTypeRoots` (explicit roots REPLACE the walk-up `node_modules/@types`
default) + `ModuleResolver.resolveTypeRootPackage` (package.json `types`/`typings`,
else `index.d.ts` — deliberately narrower than directory resolution: no `main`, no
runtime `index.*`); entries seed the graph walk so their imports and
`/// <reference types>` directives follow; TS2688 for explicitly-requested-but-missing
names only. TypesAcquisitionTest pins the sharp both-ways invariant with
ambient-global-only packages (only acquisition can reach them → inclusion = global
resolves + entry in programFiles; exclusion = TS2304). Two findings worth keeping:
(1) the first stub cut declared `Buffer` value-only and sys.ts's `let buffer: Buffer;`
drew TS2749 — a node global that doubles as a type needs the lib wrapper-type shape
(generic-tolerant `interface Buffer<T = any>` MERGED with the var via canMerge
Variable+Interface; harness even writes `Buffer<ArrayBuffer>`, hence the defaulted
type param). (2) Resolving the 46 env-legit names FREED the global 10-lookup TS2552
suggestion budget: the 5 `SetIterator`/`MapIterator` sites (es2024 collection-iterator
types missing from the embedded lib — an M2 lib-gap marker; 4×TS2552 + 1×TS2304 in the
control) all became TS2552 — diagnostics SHAPES can shift when unrelated names start
resolving, so compare by-code diffs against the freed-budget effect before calling a
+1 a regression. Ops: two mid-session cwd drifts (a `cd` into the bench dir made
relative-path XML reads report 0 files — a false "no tests ran" scare; absolute paths
resolved it). **Same session, M1.4 (re-measure + strategic map): fresh no-stub rows at
2254d13c — services 7,173 → 7,145 err / 563 → 393 s (−30%); server 7,634 → 7,606 /
627 → 383 s (−39%); harness 8,164 → 8,135 / 593 → 392 s (−34%, RSS 1,920 → 1,192 MB).
The ~−28 error deltas are round 386's narrowing work; the time/RSS drop is the
depth-2000 memo effect; each profile also gains M1.2b's +2 completed-narrowing TS2345
(same shape as utilities.ts:11604/11859). Family map from the 4,411-site compiler
`--listAll`: TS7006×1554 is 52% ONE FILE (visitorPublic.ts ×810 — the
`VisitEachChildTable` computed-enum-key mapped-type table) plus the factory pattern
(checker.ts ×318 `return { isUndefinedSymbol: symbol => … }` ← return-annotation
member fn types; program.ts ×94; tsbuildPublic.ts ×63) → M1.6. TS2345×65 + TS2322×~35
are ONE relation bug (`undefined` ≁ union CONTAINING undefined:
`PunctuationToken<any> | undefined`, `VisitResult<Node | undefined>`) and TS2339×44 is
`new Map<K, V>()` typing the local as MapConstructor → M1.7. TS7030×114 are
`T | undefined`-returning functions drawing our strict-only TS7030 where tsc requires
noImplicitReturns → M1.8. TS2339's dominant bucket (461 union receivers + the named
`Type`/tuple sites — `isTypeParameterDeclaration(node) ? node.name…`,
`isGenericTupleType(type) && type.target…`) is user-type-guard narrowing on the big
merged AST unions → absorbed into M3.4's item text. `SetIterator`/`MapIterator`
(es2024) and String.replace's RegExp-arg overload (TS2345 `'RegExp'`→`'string'` ×19)
→ M2 markers. On services/server/harness, TS2339 (1,741–1,904) overtakes TS7006 as
the #1 family — the M3.4 narrowing bucket dominates the bigger profiles.**
**Third arc, same round — M1.7 landed: self-compile 4,456 → 4,376 (−80, zero new
codes; TS2339 −50, TS2345 −25, TS2322 −5). (a) Explicit `undefined` is legal for an
OPTIONAL parameter on the single-signature arg path (B176's overload rule applied to
the 17.11c Reference branch + the 17.40 anonymous-fn sibling; `null` stays checked,
required params still reject). The ` | undefined` in the FP display was our OWN
B51.7 optional-display append — reading it as "union containing undefined" was the
wrong first hypothesis; the bench isolated the real split: only the `?:`-style
factory params were this bug, the `: X | undefined`-annotated style is a genuinely
lost union member → re-scoped into M1.9 (~75 sites with the TS2322 sibling).
(b) `getReturnTypeOfNewExpression` re-instantiates a constructor-interface's
construct-sig return target with the explicit type args (`new Map<string, number>()`
→ `Map<string, number>`, was MapConstructor → 44 TS2339 + 6 knock-ons). 8 local
tests (OptionalParamAndCtorInterfaceTest) with negative controls (null rejected,
required-param undefined rejected, no-type-args path intact). Suite 8,896 / 0 / 3.**

**Round 386 (2026-07-03) — M1.2 closed: narrowing depth horizon 50→2000 (zero corpus
churn), TS2563 half folded into M3.4 with measurement; M1.5 asserts predicates ACTIVE
end-to-end; M1.5b falsified-and-pinned; assignment-effect narrowing landed.
Self-compile: 4,464 → 4,456 errors, 185.8 → 75.8 s (−59%), RSS 1,166 → 853 MB.**
M1.2b: the flagged blocker ("corpus depends on depth-50 truncation") was measured
EMPTY — one-constant experiment, full suite 8,861/0/3 at depth 2000 — and the cap
itself turned out to be the round-385 perf problem's other half: truncated subtrees
are never memo-stored (clean-only rule), so the 50-cap forced deep-CFG walks to
recompute everything; lifting it alone took the compiler profile 185.8 → 68.3 s and
RSS 1,166 → 841 MB at byte-identical diagnostics EXCEPT +2 TS2345
(utilities.ts:11604/11859 — deeper walks now COMPLETE two narrowings whose results an
arg-check consumer turns into FPs; the depth-50 truncation had been hiding them;
tracked under M1.4). TS2563-emission folded into M3.4 after reading
largeControlFlowGraph: it is 10k sequential `data[0] = 0` statements — tsc's TS2563
fires because USE-SITE reference typing walks the evolving array's flow at every
mutation check (flow-based identifier typing = M3.4's exact capability); none of our
four narrowing consumers ever walks that file deep, so a faithful walk-exhaustion
emitter is unreachable until then and B399's per-file heuristic (+ its 27 self-compile
TS2563 FPs) stays. **M1.5 (eaa27a90)**: parser builds `TypePredicate(assertsModifier=
true)`; asserts returns are void (return-less bodied assert fns draw no TS2355/2366/
7030); `narrowByAssertCall` live — `is T` targets, `is NonNullable<T>` as nullish
exclusion, bare `asserts cond` by CONDITION via `applyConditionNarrowing` (the
`Debug.assert(x !== undefined)` shape); the round-385 pre-check widened to
path-containment (`argMentionsReferencePath` — iterative, bails open; the firewall
stays); `resolveFlowCalleeDecl` gains namespace-member callee resolution
(`resolveNamespaceMemberFnDecl`); `callHasTypeGuardArg` gates `!assertsModifier`.
8 local tests with negative controls (AssertsPredicateActivationTest); suite
8,869/0/3. **Self-compile M1.5 delta is only TS2344 −2 + TS2355 −1 — the assert
NARROWING moved nothing on tsc sources** (TS2339/TS18048 unchanged): the imported
`Debug` alias apparently doesn't resolve to debug.ts's namespace through the
`_namespaces/ts.ts` export-star barrel in the flow-callee path → new M1.5b queue item.
Also landed: `--listAll` CLI flag (full diagnostic lists for run-to-run FP diffing —
used to isolate every delta above). **Same session, the M1.5b pivot + assignment-effect
narrowing (482e9ad1 + 8f246dcf): self-compile 4,463 → 4,456 (TS18048 41 → 34).**
M1.5b's hypothesis was falsified BY TEST before building anything — a ProjectCompiler
repro of tsc's exact barrel topology narrows correctly (3 pinning tests,
AssertsBarrelResolutionTest) — and sampling the real TS18048 FPs showed ASSIGNMENT
shapes instead (`context.pragmas = new Map() as PragmaMap` then use;
`result.extendedSourceFiles ??= new Set()`). Landed `narrowByAssignmentRhs` (shared by
both walker mirrors): structurally-non-nullish-RHS exclusion for `=`/`??=`/`||=` on
identifier + property-path targets (`&&=` excluded and pinned), cheap pre-gates before
path building; Flow.kt binds FlowAssignment for COMPOUND assigns on property LHS (the
only real binder gap — plain `=` property targets already had nodes). Cost: compile
68.6 → 75.8 s (+10%, the per-FlowAssignment matcher; still −59% vs the session's
185.8 s start — revisit in M5). Suite 8,879 / 0 / 3 (+10 local this arc). **Process
lessons, armored in comments/memory: (1) a STALE walker comment ("no FlowAssignment
for property paths") led to a duplicate `when` arm in bindAssignmentTarget that
SHADOWED the real arm (Kotlin takes the first match), dropped the LHS read-records,
and regressed this-before-super + instanceof narrowing (narrowingOfDottedNames,
checkSuperCallBeforeThisAccessing2) — the commit initially landed on a FALSE-GREEN
garbled notification and was amended after a filesystem-verified rerun. (2)
Background-task/Monitor payloads were unreliable ALL session — fabricated bench
summaries citing nonexistent log dirs, two different "12-char" expansions of one sha,
a BUILD SUCCESSFUL while the worker was mid-run, `-s`-gated monitors firing on empty
files — every gate now reads test XMLs / TSVs / logs from disk only (memory:
background-task-verification.md). (3) Hit CLAUDE.md's №1 gotcha verbatim: an
`until ! pgrep -f GradleWorkerMain` poller matches ITSELF — the bench sat behind it
for 10 minutes.** Ops notes from earlier in the session, superseded by the above:
one bench overlapped a concurrently launched attribution JVM (contaminated row
deleted); never run a second JVM while a bench measures.

**Round 385 (2026-07-03) — P0 services hang FIXED (flow-walker memoization + budgets);
asserts-parse stub discovered; M0.2 baselines completed 8/8.**
The hang was the predicted exponential re-entry with a twist: `narrowByAssertCall`
resolved every visited FlowCall's callee (PropertyAccess receiver typing →
`getNarrowedTypeForReference` re-entry per call, no memoization anywhere) — and
because `parseType()`'s AssertsKeyword branch ERASES `asserts x is T` to bare `T`
(`TypePredicate.assertsModifier` is never constructed), ALL of that exponential work
was spent discovering "not a predicate" every time; assert narrowing has been inert
since round 43 built it. Fix (349dc97b) mirrors tsc checker.ts, four pieces:
(1) arg-path pre-check in `narrowByAssertCall`/`narrowByCallPredicate` — bail before
any callee resolution unless some argument's reference path IS the walked name;
(2) per-outermost-request callee-decl memo (`narrowWalkDeclCache`, tsc
`links.effectsSignature` — request-scoped for cross-pass safety); (3) per-invocation
flow-node memo (tsc `sharedFlowNodes`) with the depth-conditional serve rule
`depth <= cachedDepth` + clean-subtree-only stores that keep narrowing byte-identical
under the NARROW_MAX_DEPTH truncation; (4) live-depth (2000, tsc `flowDepth`) +
cumulative-visit budgets shared across re-entries via the `narrowLiveDepth` FIELD
(== 0 ⇔ outermost request = reset point). **Budget sizing lesson (40d33b58): near the
NARROW_MAX_DEPTH horizon subtree results are inherently entry-depth-dependent — no
identity-preserving memo can serve them — so depth-skewed diamond chains in giant tsc
functions legitimately need 6-figure visit counts; the first 50k budget truncated one
such walk and GREW the dashboard (TS18048 41→42 → compiler profile 4,485). The final
1M budget (probes: 50k → 4,485/101.8 s, 200k → 4,485/139.1 s, 1M → 4,484/187.1 s)
recovers exact pre-fix diagnostics.** Benches: compiler pre-fix 289.9 s → 187.1 s
(−35.5% at exact 4,484; the memo win is larger at looser compliance points — 101.8 s
at the 50k budget); services HUNG (killed after 30+ CPU-min frozen in one statement)
→ 563.4 s / 7,173 errors / 1,226 MB (7,174→7,173: the 1M budget recovers one narrowing
there too); server FIRST baseline 627.4 s / 7,634 errors / 1,139 MB (274 files);
harness FIRST baseline 592.7 s / 8,164 errors / 1,920 MB (312 files) — **M0.2 crash
gate 8/8 profiles green, zero crashes/OOMs anywhere.** Local
AssertNarrowingScalingTest pins the invariant: the exact services shape (N=120
property-chain assert-style calls whose args mention both walked paths, ≈2^120 walker
visits pre-fix) → 0.125 s, plus negative/positive controls proving `x is T` predicate
narrowing still applies through the memoized path. NEW: M1.5 queued (activate asserts
predicates end-to-end — parser + tsc condition-arg narrowing; the arg-path pre-check
must WIDEN to path-containment, never be deleted). M1.2 updated: its tsc-flowDepth
mechanism now exists as these budget fields; what remains is the TS2563
emission/suppression semantics. **Same session, M1.2a landed (3c4cb60b): B399 records
`cfaTooLargeFiles` and an end-of-init filter removes every TS2454 in them (tsc's
flowAnalysisDisabled emits TS2563 OR TS2454, never both; real tsc emits neither on
its own sources) — self-compile 4,484 → 4,464 (−20, exactly the predicted knock-ons),
compile time unchanged; CfaTooLargeBailTest pins both directions (small CFG: same
never-assigned-read shape MUST fire TS2454; too-large CFG: TS2563 present, TS2454
suppressed). The M1.2 remainder (TS2563 per-container semantics) is re-scoped in the
queue item — it is gated on NARROW_MAX_DEPTH removal (largeControlFlowGraph's
baseline REQUIRES TS2563, but faithful walk-exhaustion semantics need walks to reach
depth 2000, which the 50-cap prevents) and overlaps M3.4.** Full suite 8,861 / 0 / 3
(+5 local tests this round).

**Round 384 (continued) — M0.2 findings + M1.1 landed: self-compile 13,245 → 4,484 (−66%).**
M0.2 (`--project all`): 5/8 profiles green in ~5 min each with tightly clustered
baselines (compiler 13,245; tsc-cli 13,247; jsTyping 13,301; deprecatedCompat 13,256;
typingsInstallerCore 13,348 — TS2305 dominating each at 8,752–8,837), zero
exceptions/OOMs; **services HUNG → the P0 now at the queue top** (30+ CPU-min frozen in
one `checkVarDeclAssignability`; stack: `narrowByAssertCall` → callee/arg type
resolution → `getNarrowedTypeForReference` re-entry per assert-call flow node, no
memoization — tsc's services code is `Debug.assert`-dense); server/harness deferred.
Also caught: the src/tsc profile's TSV name collided with the compiler profile's
historical file (fixed, `self-compile-tsc-cli.tsv`). **M1.1** (8a4ba245): export-star
barrel following — measured **13,245 → 4,484 (−8,761)**, TS2305 eliminated from the
top codes, compile −2.7%; remaining top families now TS7006×1554 (contextual-typing
gaps → M3.2), TS2339×886, TS2322×827, TS2345×543, TS7030×114, TS2769×77. **M1.2 recon**
(for the P0 + M1.2 implementer): tsc's mechanism confirmed at checker.ts:29037 —
`flowDepth === 2000` counts recursive `getTypeAtFlowNode` invocations per
`getFlowTypeOfReference` walk (linear single-antecedent steps are the iterative
`while(true)` loop and don't consume budget; `sharedFlowNodes` memoizes shared nodes
per walk), `flowAnalysisDisabled` is checker-global but save/restored around each
function-or-module block in `checkBlock` (= container-scoped), and
`reportFlowControlError` anchors at `findAncestor(reference, isFunctionOrModuleBlock)
.statements.pos` token span. Our B399 per-file node-count heuristic must be replaced by
that walk-budget + per-walk memoization — which is ALSO the P0 fix.

**Round 384 (2026-07-03) — M0.1 + M0.3 landed; M0.2 baseline running.**
M0.1 (9b5bcd78): `--project` profiles + per-project TSVs in the bench script (see QUEUE
entry). M0.3 (f85cc438): parse-based specifier extraction — the parser now records
`SourceFile.moduleSpecifiers` at the real parse sites (static/dynamic/require/import-type
plus a new bounded leading-trivia scan for `/// <reference>` that honors directives after
a block-comment header, which `checkTripleSlashSelfReference`'s corpus-pinned scan does
not); `ProjectCompiler.extractSpecifiers` parses instead of regex-scanning, so
string-literal/comment/regex-literal text can no longer fabricate unresolved imports or
pull junk files into the program. 6 local tests pin the invariant (garbage never
extracted; deep-nested dynamic imports found; string-literal mention neither reaches
`unresolved` nor joins the program). Suite 8,848 / 0 / 3 (+6 local). Session ops notes:
a leftover bench run from round 383 was still executing at session start (its TSV row
landed at 23:08 — labels tell them apart); my first services verification run was killed
as polluted (its gradle step compiled pre-M0.3 code, then the M0.3 recompile swapped
class files under the running JVM — don't recompile while a bench JVM is up). M0.2
`--project all` relaunched clean on f85cc438; expected effect on compiler profile:
errors stay exactly 13,245 (extraction doesn't affect checking), unresolved drops from
120 to just node-builtin bare specifiers (env-legit until M1.3).

**Round 406 (2026-07-05) — M1.12 continued: TWO more bounded self-compile FPs killed by
bucketing the fresh full `--listAll` output (self-compile 2,663 → 2,659, −4). Suite 9,034 →
9,043 (+9 local, 0 regressions); 2 commits.** Re-ran the compiler-profile `--listAll` (68 s,
2,663 confirmed) and bucketed all 2,663 lines by normalized message shape — the M1.12 method.
Two clean bounded buckets popped that round 405 hadn't reached (round 405 only worked the
30-line log tail's TS2774/TS7019). **(1) TS1100×2 (`types.ts:3030`/`:3117` `interface
CallExpression { readonly arguments: NodeArray<Expression>; }` / `interface NewExpression {
readonly arguments?: … }`):** the `InterfaceDeclaration` branch of `checkStrictModeInStatement`
checked the property NAME itself via `checkStrictModeName`, FP-ing TS1100. tsc's
`checkStrictModeEvalOrArguments` fires ONLY for binding names (variable/parameter/function names
+ assignment LHS) — a property/method NAME is never restricted (`interface I { arguments: T }`
is legal). Fix: removed the `PropertyDeclaration` name-check arm; kept the method/index PARAM
checks. **(2) TS7023×2 (`checker.ts:35924` `getMutableArrayOrTupleType`, `:43622`
`unwrapAwaitedType`):** both are `return t.flags & Union ? mapType(t, self) : concreteBranch;` —
the self-reference appears ONLY as a callback ARGUMENT to `mapType`, where it receives a
contextual parameter type from `mapType`'s signature, so self's own return type is never needed
to type the call, and the other branches supply a concrete type. tsc emits no TS7023.
`checkIndirectSelfReferenceReturn`'s crude `anyIndirect` heuristic (anything that isn't a
top-level direct self-call/ref) caught it. Fix: new `selfRefsOnlyAsCallbackArgs(expr, name)`
walker — a self-reference is safe iff EVERY occurrence is a direct `CallExpression` argument;
array/object element, element-access base, property receiver, operand, and callee positions stay
stuck → TS7023 still fires (`[self][0]()`, `{ next: self }`). Both fixes FP-safe by
construction with negative-control local tests (a strict-mode `var arguments` still fires
TS1100; `[self][0]()` / object-literal-value still fire TS7023). Diff = exactly the 4 lines
removed, nothing added. 9 local tests (StrictModeInterfacePropertyTest ×5,
CircularReturnCallbackArgTest ×4). **META: round 405's "bounded pool exhausted" was about the
LOG TAIL — bucketing the FULL 2,663-line listAll surfaced two more, exactly the M1.12 method's
promise. After these, the residual bounded pool IS genuinely M3-gated (verified by triaging the
whole ≤30-count histogram): TS2349×25 = `typeof x === "function"` / `??=` callee narrowing
(M3.4); the arithmetic ~42, TS2739/TS2741/TS2740 brand-property, TS2722/TS7053, TS2344 enum-subset
(B425-risky) all M3; TS2591×43/TS2304×2/TS2563×27 env-legit. Next real progress is M2.2 (real-lib
A/B, next queue item, 27 documented corpus failures) or a decomposed M3.4 slice.**

**Round 432 (2026-07-07) — M5 (first performance round, JFR-driven): the alias-resolution
quadratic — self-compile (compiler profile) wall ~490–593 s → 38.6 s (~13–15×), zod
6.0 → 5.0 s, diagnostics byte-identical (1,148 / by-code identical; zod 1,665 identical);
suite 9,328/0 green (+2 local).** A JFR profile (settings=profile, stackdepth=1024) on the
compiler profile showed **76% of ALL samples in `Identifier.equals` ← `ImportSpecifier.equals`
← `ArrayList.indexOf`**: the program-wide structural scans in `resolveAlias`'s
ImportSpecifier branch and `findEnclosingImport` (`spec in bindings.elements` over every
ImportDeclaration of every binderResult). Root cause: only POSITIVE resolutions are cached
(`setSymbolTarget`), so every UNRESOLVABLE alias — exactly tsc's ESM-`.js` barrel imports,
which `resolveModuleSpecifier` deliberately won't strip — re-ran the full scan on EVERY
`resolveAlias` call, from flow-walk recursion depths >1024 (stacks truncated, so most samples
lost root attribution; the visible tail pointed at `computeImportedFunctionLikeDecl` /
`resolveEnumSymbolForDiscriminant`). Fix (semantics-preserving by construction, verified
byte-identical on both dashboards): (1) `enclosingImportsOf` — a lazily-built structural-keyed
index ImportSpecifier → encounter-ordered list of (fileName, ImportDeclaration), replacing
both scans; structural keys + first-write-wins lists reproduce the old scans' first-match AND
fallback-to-next-match semantics exactly (structurally-equal specifiers across files share a
key, as they matched each other in the old scans). (2) `resolveModuleSpecifier` memoized incl.
null results via containsKey (it is a pure function of the specifier — `fileResults`/`options`
fixed before init, contextNode unused; the null case is the hot one). Second-tier zod finding
(NOT yet fixed, queue candidate): `resolveQualifiedName`-driven `resolveAlias` string churn
still ~30% of the zod compile. `EnclosingImportIndexTest` pins collision + distinct-key
resolution (the same-name-from-different-modules variant is UNUSABLE as a signal — Blocker #3
scope conflation masks it, verified pre-existing on clean HEAD via stash A/B). Also: zod
compiles end-to-end (107 files, 0 crashes, runnable emit — smoke-tested; 1,665 FPs vs real
tsc 6.0.3's 0 in 2.8 s) — a good second dashboard profile; and `bench-compile-tsc.sh` stat
parsing (`grep -oP`) silently logs 0s on macOS (BSD grep), wall_ms is real.

**Round 431 (2026-07-07) — M3.2 (STARTED) + M3.1: the TS7006 core falls 301 → 11
(−96%) via contextual typing, and engine return-checking reaches switch/try bodies
behind a foreign-TP source gate that then extends to every assignability path.
Self-compile (compiler profile) 936 → 672 → 641 → 574 → 551 → 482 (−454, −48%;
TS7006 301 → 11, TS7019 4 → 0, TS2322 435 → 276, TS2367 kept 0); by-code strictly
shrinking at every landed step; suite 9,356 → 9,384 (+28 local, 0 regressions);
5 fix commits (b2411656, 186cb3cd, cceeb26b, f12dfe61, bd567338).**
- **Fix 1 (b2411656, −264 strictly removals): TS7006 contextual typing — the two
  dominant mechanisms.** (a) Callee RESOLVABILITY: `isCalleeResolvable` falls back to
  the round-418 nested-function name map (`filterType`/`mapType` inside
  `createTypeChecker` are B83.5-unbound ×~140 sites) and a NEW lexical scope stack
  (`implicitAnyScopes` — params incl. binding-pattern names + body locals, push/pop
  in try/finally at every function-like boundary), so param-typed and nested callees
  contextually type their callback args — the same permissive rule file-level
  callees already had. (b) Assignment-RHS contextual typing (tsc
  getContextualTypeForBinaryOperand): `lhs = arrow` resolves the LHS DECLARED type
  (scope-map annotations, `as T` casts, property-access members via the receiver)
  under the single-applicable-signature rule (mirrors B476 — a ≥2-sig LHS gives NO
  ctx, contextualTypingWithGenericAndNonGenericSignature's pinned FIRE; an untyped
  `let mark; mark = tag => …` keeps firing, uncalledFunctionChecksInConditional2's
  pin). Binary propagation: `||`/`??` feed BOTH operands, `&&`/comma the RIGHT only
  (contextuallyTypeLogicalAnd03/CommaOperator03 pin the left firing).
  `contextualCallableArity` sees through single-callable-member unions
  (`WriteFileCallback | undefined` returns) + lazy References. 13 local tests.
- **Fix 2 (186cb3cd, −31 strictly removals): residual receiver shapes.**
  `lookupPropertyTypeForCtx` resolves members through Type.Intersection receivers
  (`x as CompilerHost & ResolutionCacheHost`, watchPublic ×9), lazy-membered
  References (target fallback — arity survives missing substitution), and interface
  `extends` bases (depth-guarded); an un-annotated call-initialized local registers
  its callee's declared RETURN annotation (AST-only, lazily resolved).
- **Fix 3 (cceeb26b, −108/+41): engine return-checking in switch/try + the
  foreign-TP gate + TS2367 anchoring.** `returnTypeNode` now threads through the
  SwitchStatement/TryStatement arms of BOTH assignability dispatchers (+ the
  Stmt-dispatcher IfStatement arm) — a `return undefined` in a switch case
  previously fell to the STRING path which can't resolve alias unions
  (`VisitResult<Node | undefined>` ×12 FP'd). COUPLED (load-bearing pair):
  `checkReturnAssignability` bails on a source containing a FOREIGN type param
  (name ∉ enclosing `typeParams` — an un-inferred generic call result like `return
  append(…)` typing as `T[]`; own-TP sources keep checking, corpus-pinned) — this
  cleared ~95 PRE-EXISTING top-level un-inferred-generic return FPs. The +41 are
  position-exposures of pre-existing M3 families at newly-checked positions
  (round-426 "honestly visible" precedent): NodeArray<X>-vs-NodeArray<Node>
  covariance ×~17 (cross-file heritage relation gap), `Node` narrowing-dependent
  returns ×5, branded `__String` ×2, TransformerFactory ×3. The TS2367
  same-target-Reference disjointness proof now requires a differing arg pair
  anchored in a NON-object type (a first-touch-exposed `nodes ===
  (parent as X).typeArguments` FP; `Array<string>` vs `Array<number>` stays firing).
- **Fix 4 (f12dfe61, −23 strictly removals): the gate walks ANONYMOUS-object
  members/call-sigs** — `SearchResult<T> = { value: T | undefined } | undefined`
  hides the un-inferred TP in a member (`return toSearchResult(undefined)` ×12 +
  `() => T` factory returns ×4); named interfaces stay excluded (Reference args
  carry their TPs; a member walk would be broad + first-touch-shifting).
- **Fix 5 (bd567338, −69 strictly removals): the foreign-TP gate extends to the
  var-decl (`const p: () => Printer = memoize(…)`), assignment
  (`fileIncludeReasons = append(…)`), property-access-assignment
  (`type.typeParameters = concatenate(…)` — no typeParams threading there, ALL
  TPs treated foreign), and conditional-return-branch (B69.1 runs BEFORE the
  return-path gate) paths. LANDMINE caught by the SUITE GATE (5 corpus
  regressions fixed pre-land): a generic FUNCTION VALUE source (`var f:
  (x: number) => number = genericFn`) carries its sig-OWN TPs — legitimately
  checkable, NOT leaked inference; `typeContainsForeignTypeParam` treats a
  signature's own type parameters as bound within that signature
  (genericAssignmentCompatOfFunctionSignatures1 + 4 siblings pin it; the
  refinement cost zero self-compile suppressions).
- **META:** (1) the round-431 TSV row used `--no-emit` (emitted column 0 — not an
  emit regression). (2) The round-428 negative-control lesson RECURRED: the first
  own-TP control asserted a capability the baseline never had (bare `return x`
  own-TP-vs-number is a pre-existing FN) — verify a control fires at BASELINE before
  pinning it; replaced with the B69.1-ordered ternary shape.
- **Residual triage (next-agent):** TS7006×11 — namespace-local interface
  annotations ×5 (builderState `const map: ManyToManyPathMap = {…}` inside
  `namespace BuilderState` — the walker's getTypeFromTypeNode has no namespace
  context), initializer-inferred fn locals ×3 (parenthesizerRules
  `let rule = cache.get(k); rule = node => …`, checker addLazyDiagnostic),
  destructured-member local ×1, object-member ctx ×2 (watchUtilities). TS2322×345 —
  `string`→`string` ×24 (interface-override literal props, M3), assignment-path
  foreign-TP siblings (`T[]`→`TypeParameter[]` ×2, `U | undefined`→`Modifier` ×2 —
  extend the gate to checkVarDeclAssignability/checkAssignmentExpression, same
  principle), `undefined`→ResolutionMode/ElaborationIterator ×8 (non-return
  positions), NodeArray-covariance adds ×~17 (fix `TypeNode <: Node` cross-file
  heritage or catalogue), `Node`→`Declaration | undefined` ×5 (narrowing-dependent,
  M3.4). TS2345×86/TS2769×30 (nested-overload `'true'`/`'false'` ×5,
  string-vs-literal-union ×10). TS2591×43 is env-legit (offline, no @types/node —
  `--node-stub` suppresses).

**Round 434 (2026-07-07) — M5.4 groundwork (owner-directed): parallel-caching design
record + eager-immutable index + durable tooling.** `enclosingImportIndex` converted
from lazy-mutable to an eager immutable field initializer (Tier 1 — byte-identical
diagnostics + timing on both dashboards, suite 9,333/0). NEW **`docs/parallel-caching.md`**
is the canonical design record for M5.4: the three cache tiers (eager-immutable
program facts / worker-local scratch / replicated-never-shared first-touch type state),
the share-nothing phased plan (tsgo parity → shared frozen lib slice → single-flight
pure computations), the determinism-over-everything rule, the multiplatform primitives
ladder (freeze → `kotlin.concurrent.atomics` CoW → expect/actual), the
evaluated-and-DECLINED CharlieTap/cachemap dependency (left-right KMP map: dormant, no
JS/WASM targets, no single-flight; the real blockers to sharing checking work are
Tier-3 immutability/purity, not the map), the JFR profiling how-to, and the
tsc/tsgo/xtsc comparison (tsc-source: tsgo 0.94 s / tsc 5.1 s / xtsc 19.6 s; zod:
0.52 / 2.1 / 3.5 s). `scripts/aggregate_jfr.py` (portable jfr-tool resolution,
self/inclusive/by-class + `--callers-of` attribution) checked in — profiling is now
reproducible on any box (VPS included), nothing lives only in a session scratchpad.
Backlog: M4.6 (`package.json "type": "module"` ProjectCompiler gap, found via zod) +
M4.7 (zod as second dashboard profile, full recipe + FP baseline) written down with
stable IDs; M5.1/M5.4 queue items now point at the design note.

**Round 435 (2026-07-07) — M3.1/M3.2 burn-down at post-perf-arc iteration speed: SEVEN
bounded fixes take the compiler profile 482 → 373 (−109, −22.6%; TS2322 276 → 184,
TS7006 11 → 1, TS2345 86 → 79) + the first full-dashboard bench baseline. Suite
9,389 → 9,419 (+30 local, 0 regressions); 7 fix commits (449957bc, 3a275609, cf54c26d,
c99efbb5, 451abce6, 0bcdeadf, b751249b); every step's by-site diff strictly removals
(fix 2's +3 were same-site transformations).**
- **Baseline (bench/*.tsv, "round 435 baseline post-M5-perf-arc", all 8 profiles at
  e24ae081, wall 29–41 s each — every profile is now cheap to iterate):** compiler 482 /
  tsc-cli 484 / jsTyping 480 / deprecatedCompat 481 / typingsInstallerCore 489 /
  services 1,603 / server 1,894 / harness 2,193. The small profiles are CONVERGED
  (~480 ± 9 — the same 4 families); services/server/harness carry ~3–5× (TS2339×407+,
  TS2564×66+ appear there — the next dashboard-widening signal).
- **Fix 1 (449957bc, −4): generator returns check the annotation's TReturn** (tsc
  getIterationTypeOfGeneratorFunctionReturnType): `inGeneratorFunctionBody` threaded
  like isAsync; checkReturnAssignability's top gate re-targets a Generator-family
  reference's explicit 2nd type arg and skips otherwise (bare `return;` in
  `function* (): ElaborationIterator` — checker.ts ×4). Explicit-TReturn mismatch
  still fires through the unwrap (pinned).
- **Fix 2 (3a275609, −38/+3 transforms): fresh object-literal literal props.**
  propertiesRelatedTo + both per-prop emitters retry a failing literal-containing
  target member with the un-widened literal from the member symbol's
  PropertyAssignment, gated to `freshObjLitRange` (withFreshObjLitSource at the
  var-decl/assignment/conditional-return consumers) — tsc freshness: a WIDENED var
  reference still fails (pinned). Cleared checker.ts's IterationTypesResolver tables,
  watch.ts's message table (the `'string' ≁ 'string'` display family ×21),
  esDecorators' discriminated-union stack pushes. The +3: per-prop suppression
  unmasked whole-object residuals on OTHER props (same-position-masking, catalogued —
  ModuleSpecifierResult's `?.length`-guarded props need narrowing).
- **Fix 3 (cf54c26d, −10): four TS7006 contextual-typing sources** from the round-431
  triage — namespace-local annotations (implicitAnyNsStack + the one-call
  inferenceNamespaceStack bridge; builderState ×5), declared-by-INITIALIZER locals
  (implicitAnyScopeInits parallel stack; checker.ts addLazyDiagnostic), the
  `let rule = cache.get(k)` Map-VALUE idiom (parenthesizerRules ×2), and NULLISH union
  constituents no longer disabling member ctx (`Host | undefined` returns —
  watchUtilities ×2; the real-primitive corpus pin still fires). TS7006 11 → 1.
- **Fix 4 (c99efbb5, −7): a TP whose CONSTRAINT contains literals is a
  literal-preserving arg position** (propTypeContainsLiteral TypeParam arm) —
  `readPackageJsonPathField<K extends "typings" | …>(json, "typings")` ×4 + the
  pragmas.get keyof-constraint sites ×3 check and display with the literal (tsc's
  inference keeps literal candidates under literal constraints).
- **Fix 5 (451abce6, −27): union-target decomposition is TRANSPARENT to the relation
  re-entry gate + bare-`new` contextual instantiation.** The same-target covariant
  arg-shortcut's isReentry misread `NodeArray<TemplateSpan>` vs
  `NodeArray<Node> | undefined` as a re-entry (the union decomposition re-pushes the
  SAME source) and deferred to structural comparison → Array-method-contravariance FPs
  (tsc's getContainingNodeArray family ×23). The union-target branch now pops its own
  redundant source-stack entry around the member iteration. **The first cut (isReentry
  requiring the repeat on BOTH stacks) broke the recursiveTypeComparison corpus pin —
  a genuine member-recursion re-entry repeats on the SOURCE side only; the suite gate
  caught it.** Companion: a bare `new C()` (no type/ctor args) is contextually
  instantiated when the target references the same C (nodeChildren.ts
  `map = new WeakMap()`).
- **Fix 6 (0bcdeadf, −22): the foreign-TP gate covers the assignment TARGET** — a
  local typed from an un-inferred generic call return (`let expression =
  visitNode(…)` → raw `TOut | TIn & undefined | TVisited & undefined`) makes every
  later reassignment check meaningless (the visitor family ×15: esDecorators/
  classFields/es2018/es2020). Mirrors round 431e's source gate.
- **Fix 7 (b751249b, −4): nullish returns trust the alias union's syntactic
  `| undefined`** (aliasUnionContainsNullishKeyword, extended with the
  imported-alias→globals fallback) — `return undefined` vs barrel-imported
  `ResolutionMode` (parser.ts/program.ts ×4); the resolved union collapses through
  cross-file enum-member resolution, so the M1.8 syntactic proof extends to the
  return-VALUE path. The local facsimile resolves cleanly (no repro) — the
  discriminating pin is the self-compile A/B, noted in the test.
- **META:** (1) the pre-perf listall-431e2.txt on disk matched HEAD exactly (482) —
  bucketing needed no fresh run; at ~30 s/run the probe loop is now bench-friendly.
  (2) The commit-split dance (revert-hunk → commit → re-apply) worked for landing
  three same-file fixes from one tree state with per-fix suites.
- **Residual triage (next-agent):** TS2322×184 — narrowing-dependent ×~15
  (`Node`→`Declaration | undefined` ×5, `number | undefined`→number ×4, TempFlags ×2,
  FlowNode ×2 — M3.4), `boolean`→JSDocTag-union-with-`false` ×4,
  ModuleSpecifierResult ×4 (fresh-prop values need `?.length`-guard narrowing),
  `__String` ×3 (branded, M3), builder.ts tuple/brand shapes (B526). TS2345×79 —
  callback-return inference `(…)=>boolean` vs `(…)=>U | undefined` ×7 (program.ts
  forEachEntry family — infer U from the callback return, M3.2), semver switch-CASE
  narrowing of a bare `string` to the case literals ×3 (M3.4), `'true'` vs `'false'`
  nested-overloads ×5, `Node`→never exhaustiveness ×3 (M3.4). TS2769×30 (un-triaged
  chains — sample next). TS2591×43 env-legit. TS7006×1 (tsbuildPublic destructured-
  member local — needs member-typed binding registration).**

**Round 438 (2026-07-07) — M3.1/M3.4 narrowing/relation burn-down: FIVE bounded fixes take
the compiler profile 294 → 244 (−50, −17%; TS2322 158 → 116, TS2345 47 → 39). Suite
9,444 → 9,458 (+14 local, 0 regressions); 5 fix commits (988ffacd, b3ee2ae1, 7e921b5d,
da67f611, f643f04e). Every fix suppression-only / relation-gated; each diffed by-site
(fix E additionally by-POSITION) as strictly removals before the suite gate. Theme: the
type-guard-narrowing consumers each gated their TARGET/PARAM shape and excluded `Type.Union`
/ PROPERTY-ACCESS — four symmetric extensions + a fresh-object-value narrowing.**
- **Baseline @ HEAD (b6cdcb6a, round 437 test-only): 294 FPs** (bench confirms; the
  round-436g listall was still HEAD-accurate). Reusable `--listAll` per-fix diff loop set up
  (materialize + build once, then a ~30 s CLI run per fix).
- **Full-dashboard baseline at the round-438 end state (all 8 profiles, `--no-emit`, wall
  29–42 s each): compiler 244 / tsc-cli 246 / jsTyping 241 / deprecatedCompat 243 /
  typingsInstallerCore 246 / services 1,116 / server 1,401 / harness 1,693.** The
  narrowing-gate fixes GENERALIZE STRONGLY — the big profiles dropped even harder than the
  small ones (services 1,476 → 1,116 −360, server 1,769 → 1,401 −368, harness 2,062 → 1,693
  −369 vs the round-436 baseline, which includes round 436's own un-re-measured big-profile
  gains) because the larger profiles exercise more narrowing/assignability paths. Small
  profiles converged at 241–246 (the same ~4 residual families).
- **Fix A (988ffacd, −2): checkReturnAssignability precise-verdict early return for a target
  carrying an empty-object `{}` union member.** `return ""` vs `{} | undefined` (tsc
  commandLineParser.ts `getOptionValueWithEmptyStrings`): the engine CONFIRMS `string <: {} |
  undefined` (round 430's empty-object rule is sound), but with the relation passing for a
  non-nullish source there was no early return, so control fell to the STRING fallback which
  re-widens / mis-handles `{}` — the round-436c trap, for empty-object members instead of
  literals. `targetHasEmptyObjectMember(t) && checkTypeRelatedTo(source, t)` is added to the
  precise-verdict list (the `{}`-member shape is where the engine is trustworthy). Only the
  return path had this gap (var-decl/assignment/bare-`{}` all pass).
- **Fix B (b3ee2ae1, −15): the assignment-RHS type-guard narrowing gate (round 410) extends to
  UNION targets.** `currentSourceFile = node` inside `if (isSourceFile(node))` where
  `currentSourceFile: SourceFile | undefined` — the `Interface || Reference` gate excluded the
  union, so `node` kept its wider `Node` type and FP'd the missing-property error.
  Suppression-only (the narrowed type is substituted only when it makes the relation pass). tsc's
  impliedNodeFormatDependent/esnextAnd2015/checker/parser/transformers — `Node=>SourceFile/
  EntityName/Declaration`, `CodeBlock=>ExceptionBlock`, `X | undefined => Y | undefined`.
- **Fix C (7e921b5d, −8): the call-arg guard-narrow-DOWN branch (round 428b/429c) covers
  PROPERTY-ACCESS args.** `getExports(node.left)` inside `if (isIdentifier(node.left))` —
  `node.left: Expression` narrows to `Identifier`, but the branch was gated to `arg is
  Identifier`. A PropertyAccess's built-in narrowing only refines UNION receivers, NOT a
  non-union interface DOWN to a subtype, so it needs the same explicit narrow as a bare
  Identifier. Relation-gated + never-excluded (unchanged). tsc's module/system transformers,
  checker/binder narrow-then-pass sites (`Node=>ModuleDeclaration/Expression/SourceFile`,
  `Expression=>Identifier/GeneratedIdentifier`, `Declaration=>BindingElement`).
- **Fix D (da67f611, −12): the return-path narrowing gate (round 413) extends to UNION targets
  — the symmetric partner of fix B.** `return node` where the return type is `Identifier |
  PrivateIdentifier | undefined` — the `Interface || Reference || Object` gate excluded the
  union. Suppression-only. tsc's utilitiesPublic/utilities/factory/checker/tsbuildPublic
  `return node` sites.
- **Fix E (f643f04e, −13): object-literal property VALUES read their nullish-stripped narrowed
  type in getTypeOfObjectLiteral.** `{ moduleSpecifiers: specs }` where `specs = append(specs,
  x)` narrowed `specs` to `string[]` — but the property value read the wider `string[] |
  undefined` (getTypeOfIdentifier does not narrow). Both PropertyAssignment-Identifier and
  ShorthandPropertyAssignment branches now narrow, **NULLISH-STRIP-gated (`objLitValueNullishStrip`):
  accept ONLY `X | undefined` → `X`.** LANDMINE (caught by the full listall diff): the ungated
  first cut cleared −15 but regressed +2 — the name-based-flow SHADOWING hazard (builder.ts's
  inner `const affected = state.program` under an outer `if (!affected)` over-narrowed the
  SHORTHAND `affected` to `undefined`) and a narrow-DOWN cascade (executeCommandLine `createWatch
  StatusReporter` arg). The nullish-strip gate rejects both (a narrow-to-`undefined` keeps
  nullish; a narrow-to-subtype doesn't strip nullish) → net-clean (a POSITION-only diff confirms
  zero new FP positions; the 3 remaining message-diffs at moduleSpecifiers:507 / moduleNameResolver:1300
  / program:4041 are the SAME already-failing positions with a narrowed display, still firing on a
  residual — `kind: string` literal-widening etc.). Cleared moduleSpecifiers ×3, tsbuildPublic ×3,
  moduleNameResolver ×2, esDecorators ×2, utilities/declarations/commandLineParser/program/builder.
- **META:** (1) many small residual families (sourcemap `number | undefined => number` ×4,
  emitter `TempFlags | undefined => TempFlags` ×2, the `undefined => X` M1.9 assignment-target
  set) do NOT reproduce in isolation — the mini-repro passes, so they need the exact flow context
  (documented round-428/429 pattern). Chasing them is low-yield; the reproducible narrowing-gate
  gaps were the vein. (2) The listall per-fix loop + the POSITION-only diff (`comm -13` on
  `file:line:col`) is the right regression check for a BROAD change (fix E) where a message diff
  over-reports (transformed vs new).
- **Residual triage (next-agent), 244 = TS2322×116** (deep M3, mostly NOT reproducible in isolation:
  `__String` branded ×3, `TransformerFactory<T>` generic-fn-alias ×3, B526 tuple/brand `{ [x:number]:
  …; N:…; length }[] => [A,B][]` ×~10, `number | undefined`→number reassignment-flow M3.4 ×4,
  `TempFlags | undefined`→TempFlags NonNull-assign ×2, FlowNode-union returns ×2, the
  `undefined => Symbol/Expression/SyntaxKind` M1.9 assignment-target family ×5), **TS2345×39**
  (`X => never` exhaustive-switch ×8 — M3.4 exhaustiveness, `NodeArray<Node> => SourceFile` ×3,
  generic/keyof `K=>string`/`T=>string`), **TS2591×43 + TS2304×2 (`global`) + TS2584×1 env-legit**
  (offline, no @types/node — `--node-stub` suppresses), TS2769×9 (findAncestor predicate-overload
  M3.2, moduleNameResolver `unknown` narrowing), TS2339×7 (discriminant/assert narrowing gaps),
  TS2454×4 / TS2362×4 (documented M3.4 residuals). The clean narrowing-gate vein is now mostly
  mined; the next slices are the M1.9 assignment-target-uses-declared-type family (a focused flow
  change) or the deeper M3 relation gaps (branding/generic-fn-alias/tuple representation).

**Round 437 (2026-07-07) — test-convention sweep (branch `test-refactoring`, merged with
main; numbered 437 at merge per the parallel-branch renumbering convention above — the
branch ran in PARALLEL with main's rounds 435–436): all hand-written tests now use the
shared `diagnose()` helper (CompilerTestSupport.kt) + the `should`/`have` idiom. Suite
9,444 / 0 failing / 3 skipped unchanged, 0 regressions; no compiler behavior change.**
- The sweep (~99 test files, landed on the branch as 6 refactor commits): per-file
  `TypeScriptCompiler().compile(...)` helpers → the shared
  `diagnose(source, directives = "// @strict: true", fileName = "t.ts")` (trimIndents the
  source, prepends the directives line); `assertTrue(d.isEmpty()/isNotEmpty(), msg)` →
  `diagnose(...) should { have(none/any { it.code == NNNN }) }`; buildString source
  builders → multiline templates; class-shared TS preludes hoisted to a trimIndented
  `private val` concatenated by the caller (`diagnose(prelude + """…""")`); test names
  converted to backtick sentences; RealLibResolverTest onto `should`/`have` receiver blocks.
- At merge, the 14 round-435/436 test files that landed on main in parallel
  (CallbackReturnTpParam, DestructuredLocalShadowing, ExplicitTypeArgOverloadSelection,
  ForeignTpAssignmentTarget, FreshObjLitLiteralProp, GeneratorReturnTReturn,
  GenericContainerCovariance, ImplicitAnyCtxSources, LiteralArgVsTpConstraint,
  LiteralReturnVsLiteralUnion, NullishAliasUnionReturn, OverloadOptionalUnionArg,
  SwitchCaseBareStringNarrowing, TernaryGuardedReturnArm) were converted to the same
  conventions — class-level KDocs (round provenance) kept byte-identical.
- Two compiler warnings introduced by the round-436 merge fixed (Checker.kt:21185
  redundant `!!`, Main.kt:97 redundant `?.`) — the warning-clean invariant holds.
- CLAUDE.md: testing-conventions entry added under "Test assertion gotchas" so future
  agents write new local tests in the new style (main's rounds 435/436 tests were
  written old-style in parallel — exactly the drift the entry prevents).

**Round 453 (2026-07-09) — bounded FP burn-down: THREE fixes, all GENERAL checker correctness
that reproduce minimally. Dashboard: compiler 176 → 170 (−6), tsc-cli 179 → 172, jsTyping 176 →
169, deprecatedCompat 179 → 172, typingsInstallerCore 176 → 169, services 280 → 275 (−5), server
485 → 479 (−6), harness 701 → 695 (−6). Suite 9,628 → 9,642 (+14 local across 3 new test files,
0 regressions); 3 fix commits (9f53366d / 1246458a / df18ce2f).**
- **Fix 1 (9f53366d, arithmetic reassignment/guard narrowing; M3.4):** a `number | undefined`
  reference proven non-nullish by a CROSS-STATEMENT reassignment (`length = end - start; length - 5`
  — tsc parser.ts `parseJSDocCommentWorker`) or an enclosing guard (`if (m !== undefined) indent += m`)
  was still rejected as an arithmetic operand (TS2362/TS2363/TS2365) — the arithmetic pass had no
  flow narrowing (only the `x!` NonNull + `arithTruthyNarrowedNames` `&&`/ternary strips). Two coupled
  changes: (a) `arithOperandType` runs a SCOPED flow-narrowing walk (`arithFlowNarrowedNonNullish`) for
  a bare Identifier / PropertyAccess operand — `currentFlowGraph` set ONLY around the walk (setting it
  pass-wide makes getTypeOfExpression's union-receiver path flow-aware and regressed 78 tests, per the
  gotcha), using the flow-narrowed type only when it PROVES non-nullish; (b) `rhsIsDefinitelyNonNullish`
  classifies arithmetic / bitwise / shift / relational / equality / `+` BinaryExpressions AND
  enum-member value accesses (`Flags.None`, incl. imported enums via resolveAlias) as non-nullish, so
  the reassignment idioms narrow the assigned reference. FP-safe (narrowing only removes union
  members). Cleared parser.ts:8911. **Barrel-imported enums** (checker.ts:6639's `NodeBuilderFlags`
  from `_namespaces/ts.js`) stay — resolveAlias can't follow the ESM `.js` barrel (known-hard).
- **Fix 2 (1246458a, optional-target `= undefined`; TS2322):** `<ident> = undefined` where the target's
  DECLARED type includes undefined — an OPTIONAL parameter `x?: T` (effective `T | undefined`, B85.1a)
  or a `T | undefined` local — FP-fired TS2322. The identifier-target assignment check resolves the
  target from the string `varTypes` map, which drops the `?` (resolveSimpleTypeName → "T"); the
  type-engine `currentLocalTypes` (and its pre-narrowing `narrowedDeclaredTypes`) carries the correct
  `T | undefined`. Bail when the RHS is literally `undefined`, `!exactOptionalPropertyTypes`, and the
  declared engine type includes undefined — the identifier-target analog of round 448's
  `this.optionalProp = undefined`. Cleared generators.ts (`leadingElement = undefined`),
  checker.ts:19908 (`aliasSymbol = undefined`), nodeFactory.ts:1331. FP-safe (a non-optional `x: T`
  target keeps firing).
- **Fix 3 (df18ce2f, un-annotated param shadows a leaked module var; TS2345):** program.ts's
  `const indent = "    "` (module const, string) is SHADOWED by `flattenDiagnosticMessageText(diag,
  newLine, indent = 0)`'s param, but `populateParameterLocalTypes` registered annotated (case 1) and
  function-default (case 2) params only, leaving an un-annotated non-function-default param
  unregistered — so the recursive arg `indent` resolved to the leaked const's `string` → FP TS2345
  (program.ts:832). Now an un-annotated Identifier param is added to `currentParamBindingNames`
  (getTypeOfIdentifier → anyType) AND, when a same-named entry is inherited in `currentLocalTypes`
  (checked BEFORE the set), overrides it with anyType (mirrors the binding-pattern branch).
  Un-annotated-only — an ANNOTATED shadowing param is still type-checked (negative control).
- **INVESTIGATED & REVERTED (dashboard no-op):** the varTypes-side (return-path) shadow override for
  fix 3 — the assignment/return walk's string `varTypes` resolves a shadowed-param return via a path
  the `innerTypes` override didn't reach, and the return-path shadowed-param TS2322 is not a confirmed
  dashboard FP (only my synthetic repro). Reverted to keep the change to the proven type-engine side.
- **NEXT (compiler @ 170, TS2591×43 env-legit offline node stub → real ≈127):** TS2322×93 deep-M3
  relation (mostly whole-program contextual inference — `__String` branding, generic-fn identity,
  SourceFileLike conflation); the TS2353 getter/method object-literal excess (sourcemap.ts
  MappingsDecoder) + mapped-type-key eval (utilities.ts createComputedCompilerOptions, M3.3); TS2349
  `(x || (x = [])).push()` inline-property-receiver (binder.ts:1375 / core.ts:2135 — the round-452
  deferred family); the evolving-let `indexInfos` CFA cluster (TS2454 + TS7034 + TS7005).

**Round 452 (2026-07-09) — bounded FP burn-down: TWO fixes, both GENERAL parser/checker
correctness that reproduce minimally (unlike the recent whole-program Blocker #3 families).
Dashboard: compiler 177 → 176 (−1), services 282 → 280 (−2), server 488 → 485 (−3), harness
704 → 701 (−3). Suite 9,619 → 9,628 (+9 local across 2 new test files, 0 regressions); 2 fix
commits (4c47c918 string-index / 0db79740 tuple).**
- **Fix 1 (4c47c918, string-index element access):** a numeric-literal element access on a
  STRING-typed receiver — `(arr[i])[0]` where the inner element access is typed `string`
  (tsc's own jsTyping.ts `pathComponents[pathComponents.length - 3][0] === "@"`) — FP-fired
  "Property '0' does not exist on type 'string'." The B292 bail in the element-access
  missing-member path (checkMemberAccessMissing's caller ~118808) suppressed only NON-numeric
  string keys (`s["s"]`); a numeric-literal key `[0]` (and a numeric-looking string key
  `["0"]`) fell through to the missing-member check. A plain identifier receiver `str[0]`
  resolved elsewhere, but an element-access-typed receiver reached this path. Element access on
  a string primitive is never TS2339 in tsc (String has a numeric index sig; the TS2339
  property-existence path is property-access only), so broadening the bail to any literal key
  is FP-safe. Cleared jsTyping.ts:258 (services/server/harness).
- **Fix 2 (0db79740, optional-tuple assignability):** `return emptyArray as []` against an
  all-optional tuple `readonly [kind?: T, specifiers?: T, …]` (tsc's own moduleSpecifiers.ts)
  FP-fired TS2739 "Type '[]' is missing … 0, 1, 2". The parser DISCARDS a tuple element's `?`
  token AND label from the element node (no NamedTupleMember/OptionalType is produced — `[T?]`'s
  `?` is eaten by parseType's trailing-`?` JSDoc-recovery), so the resolved tuple Type marked
  every numbered member REQUIRED and its `length` a fixed literal. Fix spans parser + checker:
  (a) the parser records per-element optionality on the new `TupleType.elementOptional` metadata
  field — a named `?` (`[a?: T]`) in the labeled branch, an unnamed `[T?]` bridged via the
  `tupleElementConsumedOptionalMarker` flag set where the recovery consumes it (read+reset per
  element so a nested tuple's marker does not leak); (b) `getTupleType` marks optional members
  in the new `optionalTupleMemberIds` side-channel (consulted by `isOptionalProperty`, since
  tuple member symbols carry no declaration) and sets an optional-containing tuple's `length`
  to the union `minLength..maxLength` (so `[]`'s length 0 relates to `[a?, b?]`'s `0 | 1 | 2`).
  FP-safe: a required-leading `[a, b?]` and an all-required `[a, b]` still error; no element
  node kind changes, so no type-node walker is affected. Cleared moduleSpecifiers.ts:344 (all
  profiles — src/compiler is shared).
- **INVESTIGATED & REVERTED (dashboard no-op):** the `(x || (x = [])).push(v)` empty-array /
  UNION-target contextual typing (round-408 gap for property/union targets). Extending
  `contextualAssignmentRhsType` to pick an Array-family member from a union target fixed the
  IDENTIFIER inline case `(x || (x = [])).push(v)`, but the real tsc-source FPs are PROPERTY
  targets (binder.ts:1375 `(label.antecedent || (label.antecedent = [])).push(antecedent)`) and
  the `??=` IIFE-call-return case (core.ts:2135) — both resolve the `.push`/call receiver in the
  call-type pass via a path the fix doesn't reach (the split `const r = …; r.push()` form IS
  fixed, confirming the `||` result typing works, but the inline call FP persists). 0 dashboard
  FPs cleared → reverted to avoid churn. The inline-property-receiver call-type resolution is
  the follow-up if this family resurfaces.
- **NEXT (services @ 280, all deep/whole-program or M3.4):** deep-M3 TS2322×154 fragments
  (`Type 'X' is not assignable to type 'Y'`, max bucket 5 — Node[]/generic-fn-identity/
  SourceFileLike-conflation); the whole-program cross-file `declare module` augmentation
  method-guard family (`isUnion()`/`isStringLiteral()`, Blocker #3); the `length = end - start;
  length - 5` cross-statement reassignment arithmetic-narrowing residual (parser.ts:8911 /
  core.ts:6639, M3.4 — the arithmetic pass has no reassignment narrowing); the evolving-let
  `indexInfos` CFA cluster (TS2454 + TS7034 + TS7005, symbolDisplay.ts).

**Round 454 (2026-07-09) — nested-function-shadow generalization + flow-narrowing + method-param
bivariance: FIVE fixes. Dashboard: compiler 170 → 153 (−17); services 275 → 251 (−24, the fixes
generalize). Suite 9,642 → 9,654 (+12 local across 5 new test files, 0 regressions); 5 fix commits
(0df21c9b / a0c0f3d8 / d4e93ead / 3eb6c99b / a505ce0a).**
- **Fix 1 (0df21c9b, nested-fn shadow in arrow/fn-expr bodies; TS2345 ×3):** `shadowNestedFunctionNames`
  (anyType-bails a body-nested `function NAME` colliding with an outer/global binding, B83.5 —
  suppression-only, round 429) ran ONLY for FunctionDeclaration bodies in the call-types walker. tsc's
  program.ts nests `function createDiagnosticForNodeArray(nodes, message)` inside the
  `runWithCancellationToken(() => { … })` ARROW body, shadowing utilities.ts's exported 3-param
  `createDiagnosticForNodeArray(sourceFile, nodes, message)`; a sibling-nested `walkArray`'s call
  FP-checked `nodes` (NodeArray) against `sourceFile` (SourceFile) → TS2345 ×3 (program.ts:3152/
  3182/3194). Added the call to the ArrowFunction (Block body) + FunctionExpression branches.
- **Fix 2 (a0c0f3d8, cast narrowing; TS2345 −1):** an assignment `x = expr as T` produces a value of the
  cast TARGET T. `rhsIsDefinitelyNonNullish` unwrapped the `as`/`<T>` cast to its inner and classified
  THAT, so `Object.create(Array.prototype) as NodeArray<Node>` (debug.ts) inside `if (!nodeArrayProto)`
  kept the falsy-guard `undefined` narrowing → the next `attachNodeArrayDebugInfoWorker(proto)` FP'd
  TS2345. New `castTargetIsNonNullish`: a cast to a concrete non-nullish target re-narrows to
  declared-minus-nullish; a nullable/any/unknown target still falls through to the inner shape.
- **Fix 3 (d4e93ead, TS2722 loop-entry; −1):** the optional-member-invoke "possibly undefined"
  suppression used only the plain narrower, which washes at a loop's FlowLoopLabel. tsc's
  moduleNameResolver.ts guards optional host methods BEFORE a loop — `if (host.directoryExists &&
  host.getDirectories) { for (…) { if (host.directoryExists(root)) … } }` — so `host.directoryExists(root)`
  FP'd inside; `propertyAccessNarrowedNonNull` now retries with the loop-entry-following variant
  (mirrors emitTs18048ForClosureCaptured…). Suppression-only.
- **Fix 4 (3eb6c99b, nested-fn shadow in checkFunctionBody; TS2322 89 → 78, TS2740 1 → 0):** the
  RETURN/argument-assignability pass now applies `shadowNestedFunctionNames` too (`getTypeOfIdentifier`,
  which `getReturnTypeOfCallExpression` consults, resolves a call to a nested function as anyType rather
  than a same-named merged-globals EXPORTED function). tsc's builderState.ts nests
  `create(forward, reverse, deleted): ManyToManyPathMap` shadowing the exported `create(newProgram:
  Program, …): BuilderState`, so `return create(new Map, new Map, undefined)` FP'd TS2740+TS2345. The
  pervasive object-literal-factory pattern `{ clear, create, … }` (shorthand → nested fn) was poisoned the
  same way — the shorthand resolved to a global's wrong signature and broke the object's assignability to
  its target interface (~13 cases across moduleNameResolver/sys/resolutionCache/emitter/nodeFactory).
- **Fix 5 (a505ce0a, object-literal METHOD-param bivariance; TS2322 −1 + fixes an unmasked FP):** a method
  member (method syntax) compares its params BIVARIANTLY vs an interface target (tsc — `strictFunctionTypes`
  never applies to methods), but our object-literal-vs-interface relation (`propertiesRelatedTo`) compared
  function-typed members contravariantly. `propertiesRelatedTo` now retries a failed member comparison via
  `methodSignaturesBivariantlyRelated` (the same helper as TS2416/TS2430) when BOTH members are methods —
  suppression-only, FP-safe by construction (a method-param bivariance match is not an error in tsc), a
  function-typed PROPERTY stays contravariant. Cleared checker.ts:6310 (`const x:
  SyntacticTypeNodeBuilderResolver = { canReuseTypeNodeAnnotation(…, symbol: Symbol, …) {…} }` vs interface
  `symbol: Symbol | undefined`) — one of the two fix-4 unmasked gaps, and a general correctness win. Corpus
  green (relation-engine change — the critical gate).
- **UNMASKED (one remaining, documented, not manufactured):** fix 4's more-correct resolution exposed two
  latent gaps; fix 5 cleared the first (method-param bivariance). The remaining one: expressionToTypeNode.ts:535
  — an un-annotated block-scoped `const clone = resolver.markNodeReuse(…)` shadowing the global
  `clone<T>(object: T): T` resolves to the GLOBAL in the return check, because `applyBodyLocalShadowing`
  does NOT descend into if-blocks and un-annotated shadowing locals are removed from currentLocalTypes (→
  fall to globals). Needs a broad, careful applyBodyLocalShadowing fix; corpus green.
- **NEXT (compiler @ 153):** the expressionToTypeNode.ts:535 unmasked gap (applyBodyLocalShadowing if-block
  descent); TS2322×77 deep-M3 relation (generic-fn-identity `<T>(object: T) => T`, `.map`-returns-tuple-array
  M3.2, whole-program conflation); TS2353 sourcemap getter/method excess + utilities.ts mapped-type-key
  eval (M3.3); TS2349 `(x || (x = [])).push()` inline-property-receiver; factory/utilities.ts:713 tsc-5.5
  inferred type predicates (`helper => !helper.scoped` → `helper is UnscopedEmitHelper`); moduleNameResolver.ts:2823
  `.value` union-return; checker.ts:21170 restrictiveInstantiation overload-selection TS18048; the three
  `assertType<never>(node)` big-AST-union exhaustive-switch residuals (round 441 deep).

**Round 469 (2026-07-10, same session as 468) — two deeper Blocker-#3/flow rules. Dashboard:
services 90 → 86 (−4); both steps strictly-removals by listAll diff. Suite 9,914 → 9,920
(+6 local across 2 new test files, 0 regressions); 2 fix commits (429785e8 / 7af1415f).**
- **Fix 15 (429785e8, type-alias-SHADOWED interface targets, Blocker #3; 90 → 87):** round 443's
  SourceFileLike conflation closed for the OBJLIT-TARGET direction — importTracker's
  `type SourceFileLike` wins the last-wins Interface+TypeAlias merge, so annotations in OTHER
  files resolve to the bogus alias union. tsc's true target is the MERGED interface: the
  compiler/types.ts base PLUS services' `declare module` AUGMENTATION members (the cross-file
  augmentation member merge our symbol tables don't model).
  objectLiteralSatisfiesMergedConflatedAliasInterface assembles the merged member table AST-side
  (top-level + module-augmentation `interface X`; required = required anywhere) and demands full
  required coverage + no excess; hooked into the return AND var-decl paths. Cleared
  sourcemaps:232, textChanges:1339 + convertToAsyncFunction:166 as a bonus (Transformer is the
  same shape).
- **Fix 16 (7af1415f, closure-assigned definite assignment; 87 → 86):** a name assigned inside
  a NESTED function-like can be assigned at any time relative to an outer read (fn decls hoist,
  closures run first) — tsc's definite-assignment never fires for it (formatting.ts
  formatSpanWorker's `previousRange`, assigned only inside the nested processRange/processPair
  helpers, read in the trailing-edit block ABOVE their declarations). Both TS2454 passes (the
  SET-based checkUsesOfUninitialized driver AND the flow-graph runFlowTS2454OnFunction — the
  round-450 two-pass gotcha in action: the first cut fixed only the flow pass and the set pass
  kept firing) remove closure-assigned candidates via a shared collectClosureAssignedNames
  (AST-based, reusing B78.2's collectAllAssignmentsAnywhere on nested bodies). **PERF LANDMINE
  (caught pre-commit): the first cut text-scanned every nested container range per STATEMENT —
  the services bench went 42 s → 5-min timeout on checker.ts's ~3000 nested fns; the AST
  pre-pass restored the normal band (42.4 s).** Straight-line use-before-assign keeps firing
  (negative controls pinned).
- **NEXT (services @ 86, 40 real):** unchanged from the round-468 list minus the four cleared.**
**Round 468 (2026-07-10) — the conflated-interface family closed out + contextual narrow-DOWN
completions: FOURTEEN fixes in 12 commits. Dashboard: services 108 → 90 (−18; TS2322 30 → 18,
TS2345 12 → 9, TS2339 6 → 5, TS2554/TS2739 → 0; 44 real excl. TS2591×43 + TS2304×2 `global` +
TS2584 console); every step verified strictly-removals by listAll diff. Suite 9,877 → 9,914
(+37 local across 12 new test files, 0 regressions).**
- **Conflated-interface variants (Blocker #3, fixes 1/2/6/7):** the round-445 rule now covers a
  TERNARY arm (checkConditionalReturnBranches; fixExpectedComma:57 + importTracker:758), an
  `&&`-nested right operand (the result is falsy(LEFT)|RIGHT — nullish LEFT members must relate,
  definitely-truthy members contribute nothing; addMissingAwait:251), the VAR-DECL path
  (convertToEsModule:130), a NESTED member whose declared type names a conflated interface the
  file also declares (objectLiteralMatchesViaNestedConflatedMember — importTracker:644
  ExportedSymbol.exportInfo), and the ARG position where the calling file merely IMPORTS the
  conflated name (objectLiteralMatchesSomeConflatedDeclaration: exact satisfaction of SOME
  declaring file's version, FN-not-FP direction; findAllReferences:1316).
- **Contextual narrow-DOWN completions (M3.4, fixes 3/4/5):** the round-462 monotone
  ctxAcceptsNarrow rule extends to SHORTHAND values (convertArrowFn:258 getVariableInfo) and
  ARRAY-LITERAL property values via narrowedArrayLiteralType (convertToOptionalChain:157 +
  convertStringOrTemplateLiteral:157); the return-path objlit CONTEXT reaches `&&`/`||`/`??`-nested
  right operands (contextualType is live while the binary evaluates — inlineVariable:163).
- **Nullish/literal triple (fixes 8-10, one commit):** `??`-literal ABSORB in combineBinaryTypes
  (a literal right ∈ the left's nullish-stripped literal union drops the widened primitive —
  organizeImports:954); the arg per-prop leaf routes through widenOptionalTargetPropType (SIXTH
  site — returnValueCorrect:264); the assignment-RHS narrowing gate accepts ENUM-object targets
  (importFixes:572 QuotePreference + services:1641 LanguageServiceMode as a bonus).
- **Fix 11 (lib):** RegExpConstructor mirrors the real es5 TWO-OVERLOAD shape (`new(pattern:
  RegExp | string)` + `new(pattern: string, flags?)`) — a single union param went SILENT on
  `new RegExp(42)` (non-simple param skips the conservative arg check); the overload shape keeps
  it erroring as TS2769 (services:2876 `new RegExp(/\S/)` cleared).
- **Fix 12 (M1.11):** for-of loop-var binding names (incl. destructured elements) shadow
  same-named functions in the arity walker — mapCode.ts's `for (const { parse, body } of
  nodeKinds)` vs the file's own 2-param `function parse` FP'd TS2554.
- **Fix 13 (M3.1):** a CALL-EXPRESSION arg carrying an un-inferred FOREIGN TP skips the
  single-sig arg check (the overloadArgSkippable rule at the single-sig site; gated to call args
  so own-TP identifier args keep corpus-pinned checks — codeFixProvider:94 `cast(...)` → TOut).
- **Fix 14 (M1.12):** a NUMERIC key resolves through an ARRAY-LIKE intersection constituent's
  number index in resolveMemberPropertyType — `Array.isArray(diag) ? diag[0] : …` intersects
  union members with `readonly unknown[]` (utilities:4062).
- **Scouted, deferred with findings:** organizeImports:115/216 need optional-member READS to
  include `| undefined` (the round-424 optionality-is-a-symbol-attribute gap — broad);
  emitter.ts:994 `transformed[0]` is whole-program-only (minimal repro clean — probe);
  fixUnreferenceableDecoratorMetadata:70 + convertParamsToDestructuredObject:475 are
  kind-discriminant narrowing not reaching property-access args (probe); jsTyping:414 is the
  known-hard barrel assertNever family; signatureHelp:379 needs nested-union-member objlit
  context; findAllReferences:1000 needs array→objlit→nested-objlit ctx propagation.
- **Cross-profile (measured at session end, accumulated since the round-458 measurement):**
  server 485 → 275 (−210), harness 701 → 481 (−220), compiler stays 46 (zero real FPs —
  all env-legit). TSV rows recorded for services/compiler/server/harness.
- **NEXT (services @ 90, 44 real):** the services.ts objlit giants (ObjectAllocator ×2 /
  CompletionEntry / EmitTextWriter TS2740); completions:1922/2237/2299/2391; importFixes:316/374
  (ImportFixWithModuleSpecifier); stringCompletions `.types`/`.value` public-API this-guards;
  sourcemaps:212/232 + textChanges:1339 (SourceFileLike objlits); symbolDisplay:917/935
  TS7034/7005; formatting:572 TS2454; utilities:1750 TS2538; goToDefinition:513.**

**Round 470b (2026-07-10, the services burn-down continues) — ELEVEN bounded fixes across 9 commits
(a PARALLEL session's perf commit — round 470 below — was rebased in mid-stream). Dashboard:
services 86 → 72 (−14; every step strictly-removals by listAll diff; bench row 39.8 s self,
−7.8% riding the perf commit). Suite 9,920 → 9,958 (+38 local across 9 new test files, 0
regressions); commits 4df5b318 / b7c5b152 / 51a2c96c / c3c6813d / 13240cb2 / 03364f91 /
1c8cd83a / e23b1d79 + the fix-10 commit.**
- **Fix 1 (4df5b318, keyof typeof Enum):** `typeof Enum` washes to anyType so `keyof` gave
  `string | number | symbol` — keyofTypeQueryEnumMemberNames builds the member-NAME literal union
  (barrel aliases via the memoized flow-only resolveImportedEnumSymbol; the merged symbol's
  declarations are POLLUTED with ImportSpecifiers, so the value-merge bail tests specific kinds,
  never "anything non-enum"). Cleared navigateTo:188.
- **Fix 2 (b7c5b152, union member with string index sig):** resolveMemberPropertyType falls back
  to the apparent Type.Object's stringIndexInfo value — `settingsOrHost.getCompilationSettings` on
  `CompilerOptions | MinimalResolutionCacheHost` resolves through CompilerOptions' index sig
  (fixes BOTH the emission and property-TYPE sites, the round-419 lesson). Cleared
  documentRegistry:222.
- **Fix 3 (51a2c96c, TS2366 param-member discriminant):** paramMemberChainType resolves a switch
  receiver `<param>.parent` through the param's annotation (the CFA pass has no param scope), so
  `switch (constructorDeclaration.parent.kind)` over `ClassDeclaration | (ClassExpression & {…})`
  proves exhaustive. Cleared convertParamsToDestructuredObject:683.
- **Fixes 4+5 (c3c6813d, this-guard pair):** resolvePropertyMethodDecl resolves a NULLISH union
  receiver through its sole non-nullish member, and the round-444 this-guard UNION bail is relaxed
  to a shared-decl test (a `guard() && cond && read` branch join unions base + subtype, both
  inheriting the guard). COMPANION caught by the local negative pin: resolvedCallReturnTypeForFlow
  bails on an optional-chained CALLEE (`factory?.make(t)`). Cleared stringCompletions:641/642 ×3
  + goToDefinition:513.
- **Fix 6 (13240cb2, this.prop assignment ctx):** the implicit-any walker tracks the enclosing
  class's members so `this.skipTrivia = skipTrivia || (pos => pos)` resolves the arrow's context
  from the property annotation (getTypeOfExpression(this) is anyType per B101). Cleared
  services.ts:1318 TS7006.
- **Fix 7 (03364f91, OR-return left narrowing):** a returned `X || undefined` / `X ?? y`
  recombines with the guard-narrowed LEFT reference via combineBinaryTypes (monotone). Cleared
  sourcemaps:212.
- **Fix 8 (1c8cd83a, nested-block const shadows param, M1.11):** a let/const in a NESTED block
  colliding with a PARAM registers anyType in the call-types walker (top-level body decls keep the
  param-wins redeclaration rule — functionArgShadowing). Cleared importFixes:1967/1970 ×2.
- **Fix 9 (e23b1d79, sibling-discriminant optional-member arg):** the optional-member-arg TS2345
  emitter suppresses when the discriminant-NARROWED receiver declares the member REQUIRED
  (`case SyntaxKind.MethodDeclaration: … fd.name` — the guard tests the SIBLING `.kind`, so the
  path-keyed access narrowing can't see it). Cleared convertParamsToDestructuredObject:475 +
  fixUnreferenceableDecoratorMetadata:70.
- **Fix 10 (flow-verified return precise verdict):** a flow-narrowing-VERIFIED return source
  early-returns from the engine block instead of falling to the STRING fallback (the round-436c
  trap) — `if (typeof target === "string") return target;` with `target: unknown` had the engine
  pass (the M1.9 if-arm machinery had already overwritten currentLocalTypes to `string`) but the
  varTypes string still said "unknown" and re-FP'd. TWO precise-verdict arms: the substitution
  sites set a `sourceNarrowVerified` flag, and a name present in narrowedDeclaredTypes whose
  raw resolved type relates counts too (PROBE470 found raw was ALREADY `string`). Never a blanket
  engine-confirmed return. Cleared stringCompletions:1133.
- **Scouted, deferred with findings:** symbolDisplay:917/935 TS7034/7005 is tsc's TWO-PART
  evolving-any model (declaration-site TS7034 + per-reference auto-flow via
  isPastLastAssignment/flowContainer-extension, tsc checker.ts ~30260/31170) — a faithful fix
  needs the evolving-type model, not a walker tweak (static derivation could NOT reproduce why
  f9/f10 error while symbolDisplay doesn't). utilities:1750 TS2538 needs binarySearchKey's U
  inferred from the keySelector RETURN (generic inference). textChanges:706/callHierarchy:263
  TS2349 look like augmentation-merge method resolution (Blocker #3 deep). completions:2237 +
  documentHighlights:193 need probes (fn-typed-param sig alignment suspected for the latter).
- **NEXT (services @ 72, 26 real):** the objlit giants (services.ts:1327 ObjectAllocator /
  completions:1922/2299/2391 / importFixes:316/374 / signatureHelp:379 / findAllReferences:1000 —
  nested objlit member context, likely one family); organizeImports:115/216 (optional-member
  reads need `| undefined`, broad); program.ts:1088 ResolutionLoader<T>; services.ts:1585
  string[] vs keyof-literal-union (Object.keys typing); fixMissingTypeAnnotationOnExports:452 ×2
  (Node vs Expression brand); services.ts:656/725 SymbolObject implements Symbol (TS2420);
  mapCode:55 flatten gate-(k) probe; jsTyping:414 barrel assertNever; emitter:994 whole-program
  probe; symbolDisplay TS7034/7005 (needs the evolving-type model — see the deferral above).**
**Round 470 (2026-07-10, user-directed 3-way benchmark + profile session, M5) — `localTypeAliasIndex`
(Tier 1): [findLocalTypeAlias]'s per-call whole-file AST rescan replaced by an eager per-file
first-wins DFS index (Checker.kt, declared before `init` per the init-order trap). Self-compile
(compiler profile) wall 23.5 s → 18.6 s median of 3 (−21%), diagnostics byte-unchanged (46).
Suite 9,920 → 9,925 (+5 local, LocalTypeAliasIndexTest, 0 regressions).**
- JFR profile (scripts/aggregate_jfr.py, 1,211 samples @ stackdepth=1024): Checker 74% inclusive
  (Parser 4% / Transformer 4% / Scanner 1.4%). `findLocalTypeAlias$scan` was the #1 SELF-time
  method (~10% of samples incl. callers): `discUnionParamMembers` (the TS2488 exhaustive-default
  never-destructure walker) called it for EVERY bare-TypeReference-annotated param of every
  function → O(fileSize × functions × params) on checker.ts. Remaining hot after the fix, for a
  future M5 session: stdlib collection churn ~30% of samples (per-function-body map snapshot
  COPIES — `HashMap.putMapEntries` via checkFunctionBody / spread2698Stmt / the arithmetic+
  call-types walkers — a layered-scope or mark/pop-log refactor target), flow walkers ~12%
  (already budget/memo-optimized), property-access pass ~8%.
- 3-way benchmark (same materialized `tsc-project-637d5746`, identical tsconfig, 3 cold runs each,
  macOS arm64 8-core): **tsgo 7.0.0-dev 1.3 s / 546 MB; original tsc 6.0.3 (JS) 6.6 s / 654 MB;
  xtsc post-fix 18.6 s / ~1.2 GB** (pre-fix 23.5 s; was ~3.6× JS-tsc, now ~2.8×). tsc and tsgo
  agree BYTE-IDENTICALLY on 65 env-legit errors (offline, no @types/node); xtsc emits 46 of the
  same family — **0 FPs, 19 FNs** on this profile. JFR shows ONE application thread does all
  compiling (user/wall ≈ 2.8 is GC/JIT) — M5.4 parallelism is the other ~3× of the tsgo gap.
- Incidental finding (not fixed): `scanExhaustiveSwitchDefault` does not descend ModuleDeclaration
  bodies, so the TS2488 walker never fires inside namespaces (pre-existing; the index covers
  namespace-nested ALIASES like the replaced scan did).
