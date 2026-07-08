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

**Round 443 (2026-07-08) — module-augmentation `.js`-specifier resolution + `| undefined`
property-init exemption + augmentation-body scope: THREE bounded fixes, all in the module-augmentation
family, all suppression-only. Compiler profile UNCHANGED (190 — no augmentation FPs there), but the
fixes GENERALIZE hugely across the big profiles (services/types.ts augments `../compiler/types.js`
10× and augments the compiler interfaces; server/harness augment even more modules): services
591 → 542 (−49), server 887 → 777 (−110), harness 1,118 → 992 (−126). Suite 9,504 → 9,510 (+6 local
across 2 test files, 0 regressions); 3 fix commits. Services diffed via `--listAll`: TS2664 10→0,
TS2564 17→0, TS2304 24→2 (the 2 remaining are NodeJS `global` — env-legit, offline).**
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
- **NEXT (biggest remaining services vein): TS2339 on `SourceFileLike` ×44 (13 `text`/base members,
  27 augmentation-added `getLineAndCharacterOfPosition`, ...).** services/types.ts augments
  `SourceFileLike`; in the FULL 252-file services program both the augmentation-added AND the BASE
  members FP, but the compiler-only profile has 0 SourceFileLike FPs. Minimal + barrel repros (built
  this session) do NOT reproduce — it needs the full program's mergeSymbolTable interface-pollution /
  declaration-order state (Blocker #3, cross-file interface merge). A genuine rabbit hole; needs an
  instrumented run against the real services program to pin the mechanism (which symbol wins, in what
  declaration order) before a fix. Other bounded services buckets: TS2416×11 (override, diverse/deep),
  TS2353×10 (union/inherited excess), TS2740×9 / TS2739×8 (missing-property, deep relation).

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
