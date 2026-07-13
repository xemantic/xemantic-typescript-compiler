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

- [ ] **INV.0 Instrument the multiplier.** Per-pass wall-time table behind an opt-in
  flag: a `pass("name") { … }` wrapper around Checker.init's ~512 dispatch calls
  (accumulator fields declared BEFORE `init` — the documented Kotlin init-order trap;
  wrap the plain `checkFoo()` lines mechanically, conditionals by hand), plus counters:
  `getTypeOfExpression` invocations vs distinct expression nodes, `nodeTypes`
  cacheable-vs-bypassed ratio, narrowing walks launched per consumer family. Publish
  the sorted pass-time table in a session note — it is the INV.4 migration worklist
  and the honest baseline. Gate: instrumentation off by default, byte-identical.
- [ ] **INV.1 Concurrent front-end — the owner's Flow beachhead (owner-approved
  kotlinx-coroutines-core dependency, 2026-07-13).** Sub-steps: (a) add the dep + a
  behavior-identical sequential `Flow` refactor of project file loading; (b) read +
  UTF-8→UTF-16 decode on `Dispatchers.IO`, scan+parse on `Dispatchers.Default`,
  bounded `flatMapMerge` (backpressure = bounded in-flight files); the BINDER STAYS
  SEQUENTIAL in file order — global `nextSymbolId` allocation order is load-bearing
  (~350-test reshuffle on drift); (c) determinism verification: corpus + `--listAll`
  byte-diff over 3 runs; (d) bench rows (compiler + harness). Expected ~1–2 s on big
  profiles now, more on the 500k-LOC horizon; derisks the coroutine foundation INV.6
  needs. Verify the parser has no hidden shared mutable state before enabling
  parallelism (internSalt stamping is per-file pure).
- [ ] **INV.2 Bind the world.** Full lexical binding — function bodies + block scopes
  (dissolves B83.5), container/parent chain, per-file `nodeId` for array-indexed side
  tables (attacks the `HashMap.getNode` top JFR entry; unlocks the file+node-identity
  memo keying rounds 481–482 were blocked on). Scope symbols allocate from a SEPARATE
  id space (no boundary-test reshuffle). Additive: existing walkers keep working; their
  scope hacks become deletable in INV.4.
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
