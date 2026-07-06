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

**Round 424 (2026-07-06) — M3.4/M1.12: five flow-narrowing burn-down fixes from the round-423
residual triage. Self-compile (compiler profile) 1,707 → 1,691 → 1,687 → 1,683 → 1,680 (−27;
TS2339 104 → 84, TS18048 5 → 1, TS2322 756 → 753); every step's by-site diff STRICTLY removals;
suite green; 5 fix commits, 5 local test files (~20 tests).**
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
- **Residual TS18048×1: checker.ts:21170 (overload-cluster return selection — M3). Next TS2339
  buckets: never×27 (per-site M3-relation diagnosis, catalogued round 423), DebugTypeMapper×10 —
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
| Corpus suite | jvmTest XMLs | green forever (8,842 / 0 / 3 at phase start; 9,245 with local tests as of round 424) |
| Self-compile FPs (tsc src/compiler) | `bench/self-compile-tsc.tsv` | 13,245 → 0 (**1,680 measured at round 424**; M1 complete at 2,726/round 389; rounds 395–424 burned bounded histogram-tail buckets + M3.4 flow-narrowing slices 2,726 → 1,680; round 424 five flow-narrowing fixes −27 (loop-entry union suppression w/ STRUCTURAL wash gate, call-RHS return-annotation narrowing, closure/join/call-crossing aliased conditions, prefix-path receiver guards, asserts-with-inferred-TP test-arg inference — every step by-site strictly removals); rounds 422–423 −92 (overload-arg flow narrowing, optional-chain discriminants, union-target guards end-to-end, exhaustive-switch receiver narrowing → TS2366 ZERO, aliased conditions); round 420 TYPE-ALIAS enum-member discriminant narrowing (M1.12) −9 (a `.kind: <alias>` member survived a `switch (x.kind)` because `enumMemberKeysOfTypeNode` handled only a direct `Enum.Member`; 0 new FPs); round 419 INTERSECTION union-member property resolution (M1.12) −46 (TS2339 189 → 143: `getPropertyOfType`/`typeHasOwnProperty` bail on a `Type.Intersection` member — fold the constituents in property resolution + discriminant-narrowing; 0 new FPs, self-compile time −17%); round 418 NESTED type-guard resolution (M1.12) −48 (TS2339 237 → 189: tsc's `isTupleType`/… guards are nested in `createTypeChecker` so the binder skips them and `resolveFlowCalleeDecl` missed them — program-wide unique-name fallback + a `Type.Union`-gate-bypassing narrow-DOWN suppression + an intersection-target positive-collapse fallback; 0 new FPs; the negative-exhaustion never of `instanceofWithStructurallyIdenticalTypes` stays intact); round 417 namespace-local `extends`-base resolution −2 (coordinated across `getTypeFromBaseTypeExpression` + `lookupInstanceMemberInResolvableChain`, FP-safe); round 409 `export *`-barrel / ESM-`.js` imported-guard FLOW narrowing (M3.4) −175 (TS2339 838 → 672); round 411 enum-member discriminant narrowing + type-guard-narrows-member-DOWN −59; round 412 single-type type-guard narrow-DOWN + TS18048 receiver-narrowing −1; round 413 the `export *` LEAF-EXPORT gate −407 (TS2339 614 → 237): the pre-413 star resolver returned non-exported IMPORT aliases, so barrel-imported `Debug.assert` (& every barrel guard) never resolved — the TRUE builder.ts blocker, NOT the round-412 depth red herring (an instrumented run showed ZERO walk truncations) — plus a dashboard-neutral tsc-faithful linear flow-walk iteration + a return-path narrowing consumer (−1); **round 414 the TS2366 "lacks ending return" family −35 (50 → 15): three CFA fall-through patterns in `statementAlwaysReturns`/`switchAlwaysReturns` — infinite-loop-with-return, trailing never-call (`Debug.fail`), switch fall-through — all FP-safe syntactic/barrel-resolution fixes; the remaining 15 are Pattern C2 (exhaustive switch w/o default → M3.4 discriminant-exhaustiveness)**; remaining bounded pool M3.4/M3-gated (a general-`resolveAlias` `.js`/star fix was measured net +297 via a TS2315 flood, reverted; NonNull-strip −17 but unmasks M3, reverted; const-string-enum→`string` relation deferred M3.3/B425); no-stub stays the honest default) |
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
  (its 27 self-compile TS2563 FPs remain on the dashboard).
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
  post-v1.
- [ ] **M3.2 Contextual typing engine** (parameters, returns, object/array literals,
  generic-context propagation — replaces `applyContextualParamTypesForArrow`-era
  special cases).
- [ ] **M3.3 Mapped / conditional / template-literal / indexed-access evaluation**
  (replace the AST-shape walkers; delete the superseded dedicated walkers and pins).
- [ ] **M3.4 Flow narrowing unified into identifier typing** (`getTypeOfIdentifier`
  consults the flow graph; retire the per-consumer narrowing carve-outs). **Absorbed
  from M1.2 (round 386): faithful TS2563 walk-exhaustion emission** — tsc fires it
  when USE-SITE reference typing walks a >2000-relevant-node flow (largeControlFlowGraph
  = 10k evolving-array mutations, each `data[0] = 0` check walks `data`'s flow), which
  requires exactly this item's flow-based identifier typing; then delete B399's
  per-file node-count heuristic + its `cfaTooLargeFiles` TS2454 filter pairing and the
  27 self-compile TS2563 FPs. tsc-shaped budget consumption (linear single-antecedent
  steps free via the iterative `while(true)` loop; only branch/condition recursion
  consumes `flowDepth`) belongs to the same rebuild. **Absorbed from M1.4 (round 387):
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
**M5 — Performance (starts at v1 compliance — the 8 tsc-source profiles compile clean)**

- [ ] **M5.1 Profiling grid**: JFR/async-profiler over the project corpus (cold CLI,
  warm in-process via BenchMain, RSS); publish flamegraph findings in a session note
  before optimizing anything.
- [ ] **M5.2 Allocation discipline in the relation engine** (type interning /
  canonicalization — replace the documented fresh-mint caps like the
  `getPropertyTypeForRelation` depth bound with proper sharing).
- [ ] **M5.3 Cache effectiveness under scope contexts** (today `nodeTypes` is bypassed
  whenever any resolution context is active = recompute on every generic-heavy path).
- [ ] **M5.4 Parallel per-file checking** via the existing-but-unused `CheckerPool`
  (LinkStore side-tables already keep binder output immutable for this).
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

### Offline asset inventory (verified 2026-07-02)

- `typescript-repo` object DB is complete (sparse checkout, full objects): any
  `src/**` path extractable via `git archive HEAD <path>`; `src/lib/` holds the 110
  real lib `.d.ts` files; `tests/cases/conformance/` holds 5,907 `.ts`/`.tsx` cases.
- Node/tsc/tsgo are NOT currently installed — differential testing (M0 optional) and
  real `@types/node` (M1.3) wait for network.
- The benchmark project cache lives under `build/bench/` (cheap to rebuild); results
  TSVs under `bench/` (gitignored, machine-specific).
