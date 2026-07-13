# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Re-scope (2026-07-03, owner): the Phase 17 target narrowed from "any TypeScript
project" to the TypeScript compiler itself.** Rationale: "any project" is asymptotic,
while the tsc-source profiles are already the dashboard — v1 becomes a measurable
burn-down (compiler 2,726 / services ~7,145 / server ~7,606 / harness ~8,135 FPs,
same ~4 families ≈85% of every profile). Queue consequences: M2.4 (DOM — tsc sources
don't reference it), M3.0 (conformance adoption — optional extra regression net, not
needed for the burn-down), M3.5 (per-file scopes — revisit only if dashboard FPs trace
to cross-file conflation on tsc sources), and all of M4 (nodenext `exports` maps, decl
emitter, JSX, external sourcemaps, project references — none block self-compiling tsc)
moved to the new § "Post-v1 backlog". M3.1–M3.4 stay live but re-scoped from
completeness campaigns to dashboard-driven burn-down: the acceptance bar is the shapes
tsc's source uses, with the corpus suite as the regression net. M5 unchanged —
performance is the directive's second half and starts at v1 compliance.

*(Numbering note: rounds 432–434 below are the `perf/flow-import-resolution` branch's original
rounds 430–432, renumbered at merge — the branch ran in PARALLEL with main's own rounds 429c–431e,
which own those numbers. The perf rounds' FP baselines (1,148 / 1,665) are the branch's pre-merge
numbers; main's concurrent M3.1/M3.2 work independently took the compiler profile to 482.)*

**Re-scope (2026-07-13, owner — round 490): M5 becomes the staged ARCHITECTURE
INVERSION arc (INV.0–INV.7).** The owner reviewed the round-490 architecture
analysis ("question every decision; cross-check tsc/tsgo") and directed: follow the
recommendation, rescope towards the overall goal. Full analysis + phase design:
**`docs/ARCHITECTURE-RETHINK.md`** — read it before ANY M5/INV item. Headline: the
flat-profile micro-opt mode (rounds 482–489, 1–3%/round) is CLOSED; the measured
cost is a multiplier (~512 sequential full-program checker passes × uncached
`getTypeOfExpression` × context-bypassed `nodeTypes` × per-pass scope re-derivation
× non-interned unions), and the fix is the tsc-shaped inversion: bind-everything →
per-file scopes → single-pass demand-driven checking with per-node caches →
canonical type identity/mappers → share-nothing parallel checkers. Owner-approved
same directive: **kotlinx-coroutines-core as a commonMain dependency** (within the
kotlinx.* rule) for the Flow-based concurrent front-end (read+UTF-8→UTF-16 decode on
`Dispatchers.IO`, parse on `Dispatchers.Default`, bounded `flatMapMerge` = the
owner's measured microbench win) and later the parallel checker/emit phases —
streams live at the I/O boundaries; the checker core stays demand-driven
memoization, per the doc's § 4. Old M5.1–M5.7 are superseded/absorbed by the INV
items in the QUEUE below (M5.1 profiling → INV.0; M5.2/M5.3 → INV.5; M5.4 → INV.6;
M5.5/M5.6 → INV.7; M5.7 targets → doc § 6).**

**Round 498 (2026-07-13, same session as 497) — INV.2(c) phase (ii) LANDED:
block-scope containers + class/interface/alias/enum scopes — INV.2(c) is
COMPLETE.** The lexical binder now covers tsc's `IsBlockScopedContainer` set
and the remaining containers: every `Block` that is NOT a function-like's
immediate body (the body shares the function scope — tsc `getContainerFlags`),
`for`/`for-in`/`for-of` headers (header `let`/`const` in the for scope, the
body block a child scope under it), `CatchClause` (binds the catch variable,
destructuring patterns included; the catch block chains under it), and
`SwitchStatement` standing in for tsc's CaseBlock — our AST has NO CaseBlock
node, so the switch statement owns the case-block scope and its EXPRESSION is
routed to the OUTER scope by hand (pushed last so sibling visit order stays
source order, which the first-wins merge semantics rely on). Class
declarations/expressions get scopes (type params; a named class EXPRESSION's
self-name binds inside only; class decorators walk under the OUTER scope),
interfaces/type aliases get type-param scopes, and enums get member-sibling
scopes (`enum E { A = 1, B = A }`): a main-bound enum ALIASES its merged
`exports`; a nested (B83.5-unbound) enum binds scope-space members ALSO
published onto the scope symbol's `exports` — gated `id ≤ −2` so a MAIN
symbol's exports are never touched. **The design dividend: phase (i)'s
`isDirectBodyChild` gates for block-scoped declarations DISSOLVE into a plain
`scope.existing == null` test — once every block-scope container owns a fresh
scope, the current scope IS the correct binding target everywhere (file/module
level stays skipped via the aliasing `existing`); `var` gains the real
`varHoistTarget` walk-up (nearest function-like/file/module boundary).**
Block-nested function declarations bind to the BLOCK (strict/module
semantics — the non-strict hoisting divergence is documented in the KDoc).
Verification: suite green 10,245 → 10,251 (+6; Inv2LexicalScopeTest now 20 —
the phase-(i) negative controls FLIPPED to positive location asserts:
if-block let/class/function in the block scope chained to the fn scope,
for-header `let` in its for scope while the sibling `var` header hoists,
catch destructuring, switch case-clause declarations, nested-bare-block
chains, fn-body-block/ModuleBlock negative controls, class/iface/alias
type-param scopes, main-vs-nested enum aliasing with the exports identity
check); `--listAll` byte-identical vs the round-497 binary; interleaved wall
B/A ×6 both orders NEUTRAL (medians 26,712 before / 26,526 after — after
faster on medians, slower on means via one outlier; noise). Tables remain
UNCONSUMED until INV.4/INV.2(d). NEXT: INV.2(d) — B83.5 dissolution pilots
(convert 1–2 checker transient-symbol sites to consume the new tables).

**Round 497 (2026-07-13) — INV.2(c) phase (i) LANDED: additive lexical binding
for function-like containers.** The Binder gained a second pass
(`bindLexicalScopes`, run after conventional binding) that walks the whole
tree ITERATIVELY (parallel explicit node/scope stacks — a 30k-term binary
chain binds on a plain thread) and builds `BinderResult.lexicalScopes`:
per-nodeId `LexicalScope` tables. Container design: the SourceFile root
ALIASES file locals and a ModuleDeclaration aliases its merged namespace
`exports` — one chained scope level per dotted segment, mirroring the
checker's B512 rule, with outer segments recovered via `symbol.parent` —
while the seven function-like kinds plus `ClassStaticBlockDeclaration` get
FRESH tables holding type params, params (binding patterns recursed; `this`
params excluded — tsc never binds them into locals), a named function
expression's self-name, body-top-level declarations (the function body block
is NOT a block-scope container, tsc `getContainerFlags`), and `var`s hoisted
from ANY block depth (also into file/module scopes for block-nested vars the
main binder's statement-only walk never saw). The function-like's own
decorators walk under the OUTER scope. **The reshuffle firewall: scope
symbols come from `Symbol.scopeSymbol` — a SEPARATE negative id space
(≤ −2, own counter) — so the global `nextId` sequence is untouched;
`declareLexical` mirrors `declareSymbol`'s merge semantics (canMerge reuse,
B505 Class+Class first-wins, param+var redeclaration merge) but never writes
`nodeToSymbol` or the aliased existing tables (a name the main binder
already bound is SKIPPED — attaching the extra declaration would mutate the
shared symbol).** Phase (ii) is deliberately unbound and PINNED by negative
controls: nested-block let/const/class/function, for-header let, catch
variables, case blocks, class scopes. Verification: suite green
10,231 → 10,245 (+14 `Inv2LexicalScopeTest` — flags per decl kind, hoisting,
root-aliasing identity, binding patterns, the zero-global-id-consumption
DELTA PROBE (two binds of identical top-level shape, one with rich bodies,
must consume equal global-id counts), namespace chain identity, plain-thread
deep chain, unindexed-tree guard, rich-fixture smoke); `--listAll`
byte-identical vs the stash-built BEFORE binary (46 diagnostics, compiler
profile); interleaved wall B/A ×6 with BOTH orders: a consistent
second-position-slower artifact appears in each order — position-balanced
means 26,328 vs 26,550 ms (+0.8%), inside the documented drift band. Tables
UNCONSUMED until INV.4/INV.2(d). NEXT: INV.2(c)(ii) block-scope containers +
class scopes, then INV.2(d) B83.5 dissolution pilots.

**Round 496 (2026-07-13, same session as 495) — INV.2(b) LANDED: the pilot
nodeId-array side table — `FlowGraph.flowAt`.** The first consumer of INV.2(a)'s
identity fields: `FlowGraph` carries `flowById`/`nodeById` arrays sized
`sourceFile.nodeCount`, PRE-COMPUTED at construction from the FINISHED
`nodeToFlow` map by a `forEachChild` walk (`array[nodeId] = map[nodeKey(node)]`),
and `flowAt(node)` serves in-tree lookups from the array behind an IDENTITY
ownership check (`nodeById[id] === node`), legacy-map fallback otherwise; all 5
checker read sites migrated. **The design discovery: a naive record-into-
array[nodeId] migration is NOT faithful** — the Long `nodeKey(pos,end)` ALIASES a
wrapper and a same-extent child onto one map entry (last-write-wins) and lookups
for EITHER hit it; pre-computing from the map reproduces the aliasing exactly,
and the identity check routes synthesized copies (nodeId −1 with real extents)
and foreign-file nodes (valid-looking ids) to the exact old path — behavior-
preserving BY CONSTRUCTION. (`nodeTypes` was REJECTED as the pilot: program-wide
`HashMap<TypeNode, Type>` STRUCTURAL keying with no file context at the lookup
sites, and the round-473 cross-file structural-collision ecology sits on top of
it — migrating it is INV.5's (node, mapper) keying, not a drop-in array.)
**Verification:** suite green 10,228 → 10,231 (+3 `Inv2FlowLookupTest`:
per-node fast≡legacy equivalence over the rich fixture incl. aliasing;
ghost-node fallback KEEPS the legacy map hit; foreign-file nodes take the map
path); `--listAll` byte-identical (interleaved). **Measurement (the (b)
deliverable):** interleaved wall B/A ×3 NEUTRAL (medians 25,999 vs 26,177 ms —
inside the noise band); bench row 25,800 ms self / 997 MB (RSS single-run band
840–997 across recent rows; the arrays' true cost ≈ +16 MB on ~1M nodes); JFR: `HashMap.getNode` = ~6.7% of ALL execution samples
but the nodeToFlow slice only ~6/139 of those (~0.3% of wall) — the ARRAY
MECHANISM is validated, and the mass-migration payoff is NOT in more cold
tables: it is in the hot maps the getNode samples actually sit in (walk-internal
memos, checker caches) and ultimately INV.4's per-node expression-type cache.
NEXT: INV.2(c) — full lexical binding, additive (function bodies first).

**Round 495 (2026-07-13) — INV.2(a) LANDED: AST identity foundations.** All 138
node data classes now extend `NodeBase` (`var nodeId = -1`, `var parent: Node? =
null`; deliberately NOT implementing `Node` — a non-sealed direct subtype would
break exhaustive `when` over `Node`); base-class vars sit outside data-class
`equals`/`hashCode`/`copy`, so structural node keys are byte-identical and a
Transformer `copy()` yields an UNINDEXED node; `SourceFile.nodeCount` body var.
New `NodeWalk.kt`: the canonical generic `forEachChild(node) {}` (every
node-typed primary-constructor property of all ~139 kinds; exhaustive sealed
`when`, so a new node CLASS fails compilation until added) + `indexSourceFile`
stamping dense PREORDER nodeIds (SourceFile = 0; a subtree = a contiguous id
range) + parents + nodeCount at the end of `Parser.parse()` — ITERATIVE
explicit-stack (crawl parses run on Dispatchers.Default OFF the deep-stack
thread; a recursive indexer would overflow exactly there). Fields are inert
until INV.2(b) consumes them. **Verification:** suite green 10,218 → 10,228
(+10 local: `Inv2NodeIndexTest` — dense preorder + parent chains + copy-
unindexed + a 30k-term chain indexed on a PLAIN thread (measured nodeCount
60,009 exact via jshell) + negative control; `ForEachChildOracleTest` — the
jvmTest REFLECTION oracle diffing forEachChild against data-class componentN
properties per node, over the kind-dense fixture + JSX fixture + directly-
constructed parser-unreachable kinds + ALL 78 real tsc compiler sources,
>100k nodes, identity-set AND multiset-size agreement); `--listAll`
byte-identical vs the stash-built BEFORE on the compiler profile (46
diagnostics; wall 25.67 → 25.73 s — the indexing walk is noise-level);
bench row 25,430 ms self / 840 MB RSS (−2.5%/−62 MB vs previous row = box
noise band; the per-node nodeId+parent fields cost ~16 MB on ~1M nodes,
invisible in RSS).
**Migration surprises (both now CLAUDE.md gotchas):** (1) the shared
superclass changed Kotlin LUB inference — `parsePropertyName`'s inferred
return type degraded to `Any` (14 downstream type errors; ONE explicit return
type fixed all; the silently-compiling `Any` variant is exactly what the
suite + listAll gates cover). (2) power-assert renders every captured
subexpression's toString on FAILURE — a failing `have(sourceFile.nodeCount >
…)` STACK-OVERFLOWED rendering the 30k-deep tree, and the oracle's `have`
OOM'd building a node-list diagram, masking the real messages; both tests
rewritten render-safe (int/boolean locals, plain `fail()`), after which the
initial sweep "failure" did not reproduce (deterministic green incl. the full
suite — the run-1 verdict is attributed to the assertion-machinery path, not
a forEachChild gap). NEXT: INV.2(b) — migrate ONE hot pos-keyed side table
(Flow's `nodeToFlow` or the checker's `nodeTypes`, per INV.0 evidence) to a
nodeId-indexed array; measure the `HashMap.getNode` JFR delta before mass
migration.

**Round 494 (2026-07-13) — INV.1(e) LANDED: the double parse is dead — the core
reuses the crawl's parses.** The crawl full-parsed every file for specifiers and
`compileParsed` parsed everything again; now ONE parse per file serves both.
Design (as scoped rounds 492/493): (1) `computeParserFlags(fileName, content,
options)` in TypeScriptCompiler.kt is the single source of truth for the
option-derived `Parser` flags (`forceJsx` / `topLevelAwait` incl. the
`fileLooksLikeModuleForAwait` content scan / `needsJsxFlag` / `noImplicitAny`) —
the core's single-file, emitDeclarationOnly-multi, and main multi-file sites all
route through it, and so does the crawl (`parseForCrawl`, which replaced
`extractSpecifiers`; specifiers now read off `preParsed.sourceFile.
moduleSpecifiers`). (2) `ParsedSource.preParsed: Map<String, PreParsedFile>`
carries `(content, flags, sourceFile, parser diagnostics)` from
`ProjectCompiler.build` (which hoists `emitOptions` above the crawl so crawl
flags are computed from the SAME options the core receives — verified
`effectiveModule` and every flag input is independent of the core's later
`packageJsonTypes` copy). (3) The core's multi-file parse site reuses an entry
ONLY on an exact content + flags match (`takeIf`), else parses fresh — reuse is
a pure optimization by construction; opt-in PassTiming counters
(`preParseReused`/`preParseFresh`, `--passTiming` dump line) make the match
observable. **Verification:** suite green 10,212 → 10,218 (+6 local
`Inv1PreParseReuseTest`: a deliberately-lying sentinel tree proves reuse FIRES
(the only externally visible identity signal), flags-mismatch and
content-mismatch negative controls re-parse, the real driver path reuses 2/2
under an option-driven flag (module es2022 makes `topLevelAwait` option-only —
a default-flag crawl would read 0), native top-level `await` flows through
check+emit off the reused tree, and the string/corpus path parses fresh);
`--listAll` byte-identical vs the stash-built BEFORE on compiler (46) AND
services (46, 252 files); reuse fires 78/78 on the bench compiler profile.
**Wall-clock: neutral within noise** on interleaved A/B (compiler BEFORE median
25,455 ms vs AFTER 25,364 ms; services 36,605 vs 36,466 with one adverse pair) —
the removed core-parse leg is small (~0.2–0.5 s hot) next to the 21 s checker,
and the win is architectural: ONE canonical tree per file is what INV.2 hangs
per-file `nodeId` side tables off. SESSION TRAP (memory updated): a fully GREEN
`./gradlew jvmTest` printed NO "tests completed" line, so the protocol's grep
pipeline exit-1'd and the task notification claimed failure — the XMLs (10,218/0)
were the truth; parse XMLs before reacting to a "failed" suite notification.
NEXT: INV.2 bind-the-world (full lexical binding, nodeIds, array-indexed side
tables — dissolves B83.5).

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

### QUEUE — work top-to-bottom; promote unblockers per protocol

(Restored 2026-07-12, round 481 — the queue/backlog/inventory sections had been
swept into PLAN-PHASE-5-HISTORY.md by an over-eager session-note trim; they are
LIVE structure, not history. v1's offline-verifiable legs LANDED at round 481, so
M5 is now the active arc per the owner directive; the Post-v1 backlog below is the
"any TypeScript project" horizon and stays parked until the owner re-scopes. The
M1–M3 campaign items still unchecked in the history file (M2.2/M2.3/M3.1–M3.4/M1.12)
hit their re-scoped v1 acceptance bar — "the shapes tsc's source uses" — when the
burn-down reached zero real FPs; reviving their full-completeness form is a
backlog-horizon decision, not queue debt.)

**EP — Emit parity (owner-authorized 2026-07-12: "output parity, including reported errors").**
The offline v1 DoD checked emit COMPLETENESS (all files emitted, exit 0) but not
emit-BYTE parity with tsc. The round-483 emit diff (`scripts/emit-diff-tsc.sh`, xtsc
vs npm `tsc@6.0.3` on the `compiler` profile) found 8/78 byte-identical, 70/78
differing — but **none are miscompiles**; xtsc's output is semantically correct and
runnable. Three systematic families explain nearly all changed lines (sequenced
cheap-first to shrink the diff before tackling the hard cross-file one):

- [x] **EP.3 Logical/nullish-assignment downleveling** (`||=`/`&&=`/`??=` below
  ES2021). DONE round 484 (2026-07-12): `Transformer.downlevelLogicalAssignment` —
  `a ||= b` → `a || (a = b)` etc., with side-effecting property/element receivers
  captured into temps (`(_a = obj())[_b = key()] || (_a[_b] = 6)`, tsc-faithful temp
  naming). ~284 sites in the compiler profile. Gated `effectiveTarget < ES2021`;
  corpus has ZERO files exercising these operators so it's pinned by
  `LogicalAssignmentDownlevelTest` only. KNOWN RESIDUAL: a `??=` target BELOW ES2020
  keeps a native `??` (not further downleveled — ES2020 is the tested/dashboard
  target); close when a sub-ES2020 `??=` case appears.
- [ ] **EP.2 Multi-line expression printer formatting.** Match tsc's operator/`:`
  placement (line-end vs line-start) and indentation when wrapping long
  `||`/`&&`/ternary chains. Mechanical Emitter work, no cross-file dependency, but
  HIGHER corpus-regression risk (touches the printer that the green corpus pins) —
  do it with the emit-diff gate in place and verify the full suite after each step.
- [ ] **EP.1 Cross-module const-enum inlining** (highest impact, ~93% of the changed
  lines in files like utilities.js). xtsc inlines SAME-FILE const enums but keeps
  `mod.Enum.Member` for const enums imported across modules; tsc inlines to
  `VALUE /* Enum.Member */` (numeric AND string-valued). Needs the checker to resolve
  imported const-enum values whole-program. Biggest/hardest (cross-file), collapses
  most of the diff. NOTE: xtsc's form still RUNS (preserveConstEnums keeps the enum
  objects) — this is byte-fidelity, not correctness.
- [ ] **EP.0 Wire the emit-diff gate into the dashboard.** `scripts/emit-diff-tsc.sh`
  exists (reports identical/differing + family signals). Ideal reference is a tsc
  BUILT AT THE PINNED COMMIT (npm tsc adds version noise to the small residual tail,
  esp. emitHelpers.js helper bodies); decide whether to build+cache the pinned tsc or
  accept the version-stable family signals. Re-run after EP.2/EP.1 to track the diff
  shrinking.

Session note (round 484) has the full family breakdown + methodology.

**INV — the M5 architecture-inversion arc (re-scoped 2026-07-13, owner; supersedes
M5.1–M5.7 — mapping and full design in `docs/ARCHITECTURE-RETHINK.md`, READ IT FIRST).**
Ground rules for every INV item: corpus suite green + 8-profile FP floors unchanged +
`--listAll` byte-diff empty for behavior-preserving steps + a bench TSV row per landed
item; decompose into the smallest standalone suite-gated commits; micro-opt rounds
against the flat profile are CLOSED (only an INV.0-evidenced ≥5% single lever may
interrupt the arc).

- [x] **INV.0 Instrument the multiplier.** DONE round 491 (2026-07-13):
  `PassTiming.kt` + non-inline `pass(name) {}` around all 514 init dispatch calls +
  the three counters (`getTypeOfExpression` calls/distinct with per-pass attribution,
  `nodeTypes` cacheable/bypassed/hit, depth-0 flow walks at `flowWalkWithTripCheck`),
  behind the `--passTiming` CLI flag; off-mode byte-identical (listAll A/B + wall
  parity) + suite green (+7 local). The table (round-491 session note): checker-init
  = 83% of wall; top-3 passes 38.6% (property-access / assignability / call-types,
  458k of 595k getTypeOfExpression calls, 84k flow walks — 68% from
  checkPropertyAccess); 474 sub-100 ms passes sum 36.5% = the multiplication tail.
  That note's cost-ordered worklist IS the INV.4 migration order.
- [x] **INV.1 Concurrent front-end — the owner's Flow beachhead (owner-approved
  kotlinx-coroutines-core dependency, 2026-07-13).** Sub-steps: (a) DONE round 492 —
  the dep was already in commonMain; landed the `runCompilerPipeline` expect/actual
  seam (JVM `runBlocking`) + the import-graph crawl as a cold sequential Flow
  (`crawlImportGraph`, ProjectCompiler) with the load-bearing emission-order
  contract documented at the seam (suite +3, listAll A/B byte-identical); (b) DONE
  round 493 — read+decode on `Dispatchers.IO` (`pipelineIoDispatcher`
  expect/actual), extraction parse on `Dispatchers.Default`, bounded
  `flatMapMerge(16)` per frontier (`readAndScanBatch`); resolution + emission stay
  sequential per frontier so emission stays first-discovery order (the binder stays
  sequential; parser audited — no shared mutable state); (c) DONE round 493 —
  corpus green (+6 local) + 3× `--listAll` byte-identical vs the (a) binary; (d)
  DONE round 493 — interleaved A/B −0.8 s (~3%) on the compiler profile + bench
  TSV row.
- [x] **INV.1(e) Kill the double parse — reuse the crawl's parses in the core.**
  DONE round 494 (2026-07-13): `computeParserFlags` (the shared single source of
  truth for the option-derived `Parser` flags, used by the core's parse sites AND
  the crawl), `ParsedSource.preParsed` carrying `PreParsedFile(content, flags,
  sourceFile, diagnostics)`, and the core's multi-file site reusing an entry ONLY
  on an exact content+flags match (else re-parse — reuse is a pure optimization).
  Verified: suite +6 (Inv1PreParseReuseTest — sentinel-tree reuse proof + both
  mismatch gates + driver-path counters), `--listAll` byte-identical on compiler
  AND services, reuse fires 78/78 (`--passTiming` counters), interleaved wall A/B
  neutral within noise on both profiles (the parse leg is small next to the
  checker; the point is one canonical tree per file — the INV.2 enabler).
  CLAUDE.md gotcha: a new option-derived Parser argument must extend
  `ParserFlags`, never a parse site inline, or the match reuses a wrong tree.
- [ ] **INV.2 Bind the world** — decomposed round 494 (facts verified in-code:
  `Node` is a sealed interface + ~138 data classes with single-interface supertypes
  `) : Expression/Node/TypeNode/Statement/Declaration/ClassElement`; there is NO
  generic child-walk anywhere; nodes have no parent/id fields; `Symbol.id` is a
  GLOBAL companion `nextId++` (Types.kt:116–127, the ~350-test reshuffle anchor);
  `nodeKey` is the cross-file-colliding `(pos<<32)|end`). Work the sub-items in
  order, one commit each:
  - [x] **INV.2(a) AST identity foundations.** DONE round 495 (2026-07-13):
    `NodeBase` (nodeId/parent, NOT implementing Node — preserves sealed-`when`
    exhaustiveness) + 138 supertype edits + `SourceFile.nodeCount`; canonical
    `forEachChild` (exhaustive sealed `when`) + iterative preorder
    `indexSourceFile` hooked into `Parser.parse()`. Pinned by the jvmTest
    reflection oracle (`ForEachChildOracleTest` — componentN diff over fixtures +
    all 78 real tsc sources) + `Inv2NodeIndexTest` (dense preorder / parent
    chains / copy-unindexed / 30k-chain-on-plain-thread). Suite +10 (10,228),
    `--listAll` byte-identical, wall neutral. Gotchas: NodeBase LUB trap +
    power-assert node-toString trap.
  - [x] **INV.2(b) Pilot consumer.** DONE round 496 (2026-07-13):
    `FlowGraph.flowAt` — nodeId arrays pre-computed from the finished map
    (preserves the nodeKey extent-ALIASING) + identity ownership check
    (synthesized/foreign nodes take the legacy path); 5 checker sites migrated;
    suite +3, listAll byte-identical, wall neutral. JFR verdict: getNode ≈6.7%
    of samples but nodeToFlow only ~4% of that slice (~0.3% wall) — mechanism
    validated; the mass-migration targets are the HOT maps (walk memos, INV.4
    per-node type cache), not more cold tables. `nodeTypes` rejected as pilot
    (structural cross-file keying — INV.5 territory).
  - [x] **INV.2(c) Full lexical binding, additive.** Scope symbols from a SEPARATE
    id space (never the global `nextId` sequence — the reshuffle hazard); existing
    `locals`/`globals` byte-unchanged; new tables unconsumed until INV.4.
    - (i) DONE round 497 (2026-07-13): function-like containers —
      `bindLexicalScopes` (Binder.kt) walks the whole tree iteratively after
      conventional binding, building per-nodeId `LexicalScope`s
      (`BinderResult.lexicalScopes`): SourceFile root aliases file locals,
      ModuleDeclaration aliases the merged exports (chained per dotted segment,
      the B512 rule), the 7 function-like kinds + static blocks get fresh tables
      (type params, params minus `this`, fn-expr self-name, body-top-level
      decls, `var`s hoisted from any block depth). `Symbol.scopeSymbol` mints
      ids ≤ −2; a delta-probe test pins zero global-id consumption. Suite +14
      (Inv2LexicalScopeTest), listAll byte-identical, interleaved wall
      position-balanced +0.8% (noise band).
    - (ii) DONE round 498 (2026-07-13, same session): block-scope containers —
      every Block that is not a function-like's immediate body, for/for-in/for-of
      headers, CatchClause (binds the catch variable, destructuring included),
      SwitchStatement standing in for tsc's CaseBlock (our AST has none — the
      switch EXPRESSION routes to the OUTER scope by hand) — plus class scopes
      (type params; named class-expression self-name; class decorators outer),
      interface/type-alias scopes (type params), and enum scopes (aliasing
      main-bound exports; nested enums bind scope-space members also published
      on the scope symbol's exports, gated `id ≤ −2` so main symbols stay
      untouched). Design dividend: the phase-(i) `isDirectBodyChild` gates for
      block-scoped declarations DISSOLVE into `scope.existing == null` (every
      fresh scope IS the correct nearest block-scope container); `var` gains the
      real `varHoistTarget` walk-up. Block-nested function declarations use
      strict/module semantics (bind to the block). Suite +6 (20 total in
      Inv2LexicalScopeTest — the phase-(i) negative controls flipped to
      positive location asserts), listAll byte-identical, interleaved wall ×6
      both orders neutral.
  - [ ] **INV.2(d) B83.5 dissolution pilots.** Convert 1–2 checker sites that
    synthesize transient symbols for unbound block-scoped decls (e.g.
    `checkPropertyAccessInStatement`'s ClassDeclaration branch) to consume the
    new tables — proving fidelity walker-by-walker; suite-gated.
- [ ] **INV.3 Per-file scoping.** Consume `buildPerFileScopes` (built round 17.32a,
  never consumed); module files resolve own-locals + imports + true globals; retire the
  `mergeSymbolTable` globals conflation for module files; delete the conflation
  ecology walker-by-walker (`moduleFileLocalVarNames`, `conflatedTypeAliasFiles`,
  `conflatedInterfaceFiles`, `conflatedEnumFileSubsets`, per-file interface views,
  chimera bails — each deletion suite- and listAll-gated, each removes hot-path work
  from `checkMemberAccessMissing`). Also lays the cross-file value-resolution
  groundwork EP.1 needs.
- [ ] **INV.4 Single-pass check spine.** `checkSourceFileOnce` per-node dispatch;
  migrate walker families in INV.0's cost order — every migration deletes a full-tree
  pass and its private scope machinery. Once ONE authoritative walk state exists, land
  the two things that are unsound today: a per-node expression-type cache, and flow
  narrowing folded into reference typing once (collapsing the rounds-408–479
  per-consumer wiring). The long middle — plan as many small items; corpus + listAll
  gate every family move.
- [ ] **INV.5 Canonical types + explicit instantiation** (absorbs M5.2/M5.3). Intern
  unions/intersections by sorted member-id key; literal interning; explicit mapper
  objects replace the ambient `currentTypeAliasArgs`/TP-scope contexts; instantiated
  members cached ON the `Type.Reference` (delete `resolveGenericPropertyType`
  fresh-minting + its depth-4 OOM cap); `nodeTypes` keyed (node, mapper) — always
  valid; then open `canUseTypeEngine`'s generic gate and DELETE superseded pin walkers.
- [ ] **INV.6 Parallelism** (absorbs M5.4). Share-nothing checker workers per
  `docs/parallel-caching.md` (trivially partitionable once INV.4 gives a per-file
  check entry); parallel emit on Default + IO write sink; deterministic partition +
  merge via the existing diagnostic sort. Structured concurrency from INV.1.
- [ ] **INV.7 Productization** (absorbs M5.5/M5.6). Native re-enable (the big-input
  GC inversion should largely dissolve post INV.4/5); watch mode driven by a
  file-event Flow; `.tsbuildinfo`-style incremental reuse.

Numeric targets (proposed, doc § 6): post INV.4/5 single-threaded compiler profile
≤ 10 s (≈ JS tsc) + harness RSS ≤ 1 GB; post INV.6 compiler ≤ 5 s on 4 cores;
INV.7 stretch: native cold ≤ 2× tsgo.

### Post-v1 backlog — the "any TypeScript project" horizon (parked 2026-07-03)

The top-to-bottom loop SKIPS this section until v1 (the 8 tsc-source profiles at zero
FPs) lands. None of these block self-compiling tsc. Each returns to the live queue
when v1 lands — or earlier if a live item genuinely needs one (promote per protocol,
with a session note saying why). Item IDs are stable; session notes reference them.

- [ ] **M2.4 DOM libs as an opt-in set** (dom.generated.d.ts is 1 MB+ — measure the
  parse/bind cost; ties into the shared-snapshot design). tsc's sources don't
  reference DOM — post-v1.
- [ ] **M3.0 Conformance generator extension.** Extend `generateTypeScriptTests` with
  a per-category allowlist for `tests/cases/conformance/` (5,907 files; keep all tsgo
  set-B filters). Start with the categories matching M3.1 (types/typeParameters,
  types/typeRelationships, expressions/functions). Each category lands only when its
  failures are triaged into queue items — never leave a category half-red without
  notes. Owner approval (2026-07-02) stands; optionally pull in early as an extra
  regression net if an M3 campaign wants the coverage.
- [ ] **M3.5 Per-file scopes** (Blocker #3: stop merging all file locals into
  `globals`; per-file scope construction with explicit import visibility). Revisit
  before v1 ONLY if dashboard FPs trace to cross-file scope conflation on tsc sources.
- [ ] **M4.1 Full nodenext resolution**: package.json `exports`/`imports` maps,
  symlink/realpath (pnpm layouts), `typesVersions`, package self-references. (The tsc
  repo itself uses relative imports + @types — unused for v1.)
- [ ] **M4.2 Real declaration emitter.** `.d.ts` output for arbitrary code (the corpus
  strips most `.d.ts` sections, so almost none exists today; `declaration: true` is
  table stakes for "any project"). Test bed: conformance decl baselines + self-compile
  d.ts diffing. Pull into v1 only if the owner defines "fully compile tsc" to include
  declaration output.
- [ ] **M4.3 JSX end-to-end** (`jsx: react-jsx`/`react`/`preserve` transforms on real
  React-shaped code).
- [ ] **M4.4 External sourcemaps** (`.js.map` files; inline maps exist).
- [ ] **M4.5 Decision point**: project references / composite / incremental scope
  (tsgo supports them; needed for large monorepos — decide build vs defer with owner).
- [ ] **M4.6 `package.json "type": "module"` module-format detection in
  `ProjectCompiler`** (found compiling zod, 2026-07-07): under `module: NodeNext`
  with a `"type": "module"` package.json, real tsc emits ESM but we emit CJS — the
  `collectPackageJsonTypes` machinery exists only for the multi-file TEST-source path
  and is not wired into the on-disk project pipeline. Repro: zod (see M4.7); the
  emitted CJS only runs in a `"type": "commonjs"` context. Unused for v1 (the
  tsc-source bench project has no package.json → CJS default is correct there).
- [ ] **M4.7 zod as a second dashboard profile** (validated 2026-07-07, round 432
  session note): shallow-clone `github.com/colinhacks/zod`, compile
  `packages/zod/src` (107 files, ~31k LOC) via a `tsconfig.xtsc.json` extending zod's
  real `.configs/tsconfig.base.json` (strict, exactOptionalPropertyTypes,
  noUnusedLocals, NodeNext), include `src/**/*.ts`, exclude tests/benchmarks — real
  tsc 6.0.3 reports 0 errors on it, so every xtsc diagnostic is an FP. Baseline
  2026-07-07: 1,665 FPs (top: TS7006×447 contextual params, TS2694×284 namespace
  members via `export *` barrels, TS7029×211 switch-fallthrough, TS2344×182), 0
  crashes, all 107 files emit, output passes a runtime smoke test. Complements the
  tsc-source profiles: stresses generic method chaining + noFallthroughCasesInSwitch,
  which tsc's own source doesn't.

### Offline asset inventory (verified 2026-07-02)

- `typescript-repo` object DB is complete (sparse checkout, full objects): any
  `src/**` path extractable via `git archive HEAD <path>`; `src/lib/` holds the 110
  real lib `.d.ts` files; `tests/cases/conformance/` holds 5,907 `.ts`/`.tsx` cases.
- Node/tsc/tsgo are NOT currently installed — differential testing (M0 optional) and
  real `@types/node` (M1.3) wait for network.
- The benchmark project cache lives under `build/bench/` (cheap to rebuild); results
  TSVs under `bench/` (gitignored, machine-specific).
