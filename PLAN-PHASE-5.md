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

**M5 — Performance (starts at v1 compliance — the 8 tsc-source profiles compile clean)**

- [ ] **M5.1 Profiling grid**: JFR/async-profiler over the project corpus (cold CLI,
  warm in-process via BenchMain, RSS); publish flamegraph findings in a session note
  before optimizing anything. **Partially done early (rounds 432–434, branch
  `perf/flow-import-resolution`, owner-directed): two JFR rounds removed the four
  dominant hotspots — self-compile ~593 → ~20 s, zod 6 → 3.5 s, byte-identical
  diagnostics. Tooling: `scripts/aggregate_jfr.py`; method + remaining flat-profile
  leads + tsc/tsgo comparison: `docs/parallel-caching.md`. A FRESH JFR pass is
  mandatory before the next perf item — the profile shifts after every fix.**
- [ ] **M5.2 Allocation discipline in the relation engine** (type interning /
  canonicalization — replace the documented fresh-mint caps like the
  `getPropertyTypeForRelation` depth bound with proper sharing).
- [ ] **M5.3 Cache effectiveness under scope contexts** (today `nodeTypes` is bypassed
  whenever any resolution context is active = recompute on every generic-heavy path).
- [ ] **M5.4 Parallel per-file checking** via the existing-but-unused `CheckerPool`
  (LinkStore side-tables already keep binder output immutable for this).
  **Design decided (2026-07-07, owner discussion): share-nothing workers à la tsgo —
  NO shared/concurrent maps; cache-tier rules, determinism requirements, the phased
  plan (share-nothing → shared frozen lib slice → single-flight pure computations),
  and the evaluated-and-declined cachemap dependency are all in
  `docs/parallel-caching.md`. Read it BEFORE starting this item.**
- [ ] **M5.5 Incremental compilation** (`.tsbuildinfo`-style reuse; the M2.1 shared
  lib snapshot is the first piece).
- [ ] **M5.6 Native target re-enable + tune** (linuxX64 was disabled in c7e3535f;
  native already wins <10 kLOC — fix the big-input inversion, likely GC/allocation).
- [ ] **M5.7 Numeric targets** (proposed; confirm with owner at M5 start): warm ≥ tsc
  throughput on 500k-LOC real code; cold CLI ≤ 1.5× tsc on medium projects; RSS ≤ tsc;
  stretch: approach tsgo on native.

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
