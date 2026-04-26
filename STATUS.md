# Status

**Phase 4 — Checker buildout.** 8,415 / 10,078 tests passing (~83%).

**17.31b (2026-04-26, +1)** — Multi-arg same-typeParam conflict detection
with literal-preserving display for context-sensitive sigs. Refactored
`tryInferSingleTypeParamFromArgs` to gather candidates from EVERY bare-T
positional param (not just the first); detects cross-base conflicts via
mutual `checkTypeRelatedTo`. When conflict occurs AND sig has a function-
type parameter mentioning T (new `tparamMentionedInFunctionType` helper
recurses into Object call/construct sigs), emits TS2345 directly at the
failing arg position with LITERAL-form display (`'3' is not assignable to
'""'`) and returns null so the standard arg-check loop doesn't double-emit
(bare-T params silently pass `checkTypeRelatedTo(arg, T)` because
unconstrained T's apparent type is `{}`). Without function-type-T param,
falls through to widened first-candidate substitution (17.31a behavior).
Same-base-different-literal multi-arg cases (e.g. `g("a","b")`) resolve
via mutual-assignability check passing — both widened to same intrinsic
→ no conflict → substitute T=widened. Optional `source`/`fileName` params
on the helper; return-type call site (`getReturnTypeOfCallExpression`)
passes nulls so it just returns null on conflict-with-preserveLiterals.
Flips `typeInferenceConflictingCandidates_ts` (`g<T>(a:T,b:T,c:(t:T)=>T)`
with `g("", 3, a => a)` — context-sensitive sig due to arrow `c` arg
mentioning T → emits `Argument of type '3' is not assignable to parameter
of type '""'.` at `(3,7)`). Foundation for 17.31c (multi-typeParam
inference) and 17.31d (Reference-arg inference for rest-args).

**17.31a (2026-04-26, +2)** — Single-typeParam inference for non-overloaded
sigs landed. New `tryInferSingleTypeParamFromArgs` helper + narrow gate wired
into both `checkArgumentsAgainstSignature` (instantiates sig so other-arg
checks see substituted T) AND `getReturnTypeOfCallExpression` (substitutes T
into return type for downstream var-decl / property-access checks). Gate:
sig has exactly 1 typeParam; every param either is bare T or fully concrete
(no nested T); inferred type must be "named-like" (Type.Interface /
Type.Reference / Type.Intrinsic / literal flags) so anonymous Object literals
fall through to existing per-property paths (16.4ds / 16.4dt); when T is
constrained, inferred type must satisfy the constraint so 16.4i's
constraint-aware TS2345 still fires for non-assignable arg types. Flips
`fixTypeParameterInSignatureWithRestParameters_ts` (`bar<T>(item1: T, item2: T)`
called with `bar(1, "")` infers T=number, fires TS2345 at "" arg) and
`typeArgumentInferenceWithConstraintAsCommonRoot_ts` (`f<T extends Animal>(g, e)`
infers T=Giraffe, fires TS2345 with missing-prop chain at Elephant arg).
Foundation for 17.31b–d substeps (multi-arg LUB, multi-typeParam, Reference
inference).

**Post-17.30b queue audit (2026-04-26)**: 17.30c (`&&`-chain NARROWING for TS2774)
is BLOCKED-PENDING-USER on lib.dom.d.ts loading — cross-corpus search confirms
the ONLY failing TS2774 candidates that would flip with `&&`-chain narrowing
all reference `window.<x>` and gate on `window` resolving past `anyType`.
17.30d (discriminated-union narrowing through property-equality) was ALREADY
done via 17.7a (`narrowByDiscriminantProperty`); queue item was a duplicate.
Remaining work in Blocker #1 step 2h is just 17.30c which needs the
significant lib.dom.d.ts infrastructure piece.

**17.30b (2026-04-26, net-zero infra)** — FlowAssignment-RHS narrowing landed
in `narrowTypeFromFlow`'s FlowAssignment branch. When an assignment binds the
queried identifier and the RHS is a recognized literal shape (string / number /
bigint / template / true / false / null / undefined / unary-minus on numerics
— via the existing `literalTypeOfExpression` helper), filter the antecedent's
union members to those compatible with the RHS literal type and return the
narrowed shape. Conservative: only Union antecedents are narrowed (flat types
returned unchanged); RHS shapes that aren't pure literals (CallExpression /
NewExpression / Identifier RHSes) fall through to the prior pass-through behavior.
Function boundaries respected via the existing FlowGraphBuilder isolation
(`bindFunctionLikeBody` saves/restores `currentFlow` + starts a new `FlowStart`
for each inner function body). Test count unchanged (8412 / 1663 / 3); both
known wire-up call sites (checkVarDeclAssignability + checkSinglePropertyAccess
union receiver) now consume RHS narrowing but no failing test gates solely on
this. Foundation for follow-on substeps where TS2339-on-primitive emission for
narrowed function-local receivers, or downstream callable narrowing, can convert
the now-precise type into emissions.

**17.30a (2026-04-26, +1)** — TS2454 via flow-graph definite-assignment
landed: new `checkDefiniteAssignmentViaFlowGraph` walks if/while/for/switch/try
bodies that the ad-hoc walker explicitly skips, with positive-typeof /
truthy / `!= null` / `!== undefined` assertion-implies-assigned detection.
Sidesteps the 17.1c snapshot/restore -7 regression by following only
`antecedents[0]` at FlowLoopLabel (avoiding back-edge narrowing leaks).
Flips `nestedLoopTypeGuards_ts`. First substep of Blocker #1 step 2h —
remaining substeps: 17.30c (`&&`-chain narrowing into TS2774),
17.30d (discriminated-union property-equality narrowing).

**Surgical pool was exhausted (post-17.29: pool re-confirmed for 22+
consecutive recon sessions, but spot-checking flips occasional +1).**
Last surgical win was 17.29 (Type.Interface source vs different-symbol
Type.Interface target arg-mismatch path in `checkArgumentsAgainstSignature`
— extends 17.27 from Reference-source-only to also cover same-name-different-scope
named class arg cases. New `typeToStringQualified` helper walks
`symbol.parent` through Module/NamespaceModule symbols to render
`m.variable` instead of `variable` for namespace-nested classes, but only
for THIS branch's display — global typeToString unchanged so no
regressions in unrelated tests). Flipped `differentTypesWithSameName_ts`.
Post-17.27 recon #2 (2026-04-26) confirmed `find_candidates.py
--fresh` returns 0/0/0 (filtered from 8/93/22). Spot-checked five
candidates (declarationEmitExpressionInExtends4, nodeNextModuleResolution1,
circularConstraintYieldsAppropriateError, variableDeclarationInStrictMode1,
arrowFunctionErrorSpan) — all confirmed architectural or multi-piece. Queue reshuffled 2026-04-25: subsequent
sessions must commit to architectural blockers rather than searching
for surgical wins. 17.9–17.27 series landed cumulative +60 from
architectural-leaning surgical fixes (namespace-aware inference,
optional/index-sig/privacy elaboration, generic ctor inference,
ambient-module export-equals named-import resolution, this-parameter
display, TS2417 clodule static-side, super-call arg checking with
heritage type-arg substitution, super.method arg checking, namespace-aware
new-expression arg checking with class TypeParam scope re-resolution,
TS2339 enum-member-access chain, TS2493 assignment-tuple-bounds, fn-vs-fn
arity TS2345, void-return inference for unannotated fn-decl bodies,
TS2663-vs-TS2301 narrow disambiguation for parameter-property shadow in
module-file class initializers, Function-prototype satisfaction +
Reference-vs-named-Interface arg missing-property chain).

**MAINT-1 done 2026-04-25**: 32 stale skip-log entries marked
strikethrough; `find_candidates.py` updated to strip `~~...~~` spans. Net
zero test-count delta (all stale entries already pass). Surgical pool
remains empty after the audit.

**MAINT-1b (2026-04-26, post-17.29 recon #5)**: 6 additional stale
skip-log entries marked strikethrough — `clodulesDerivedClasses_ts`
(flipped 17.18), `derivedClassConstructorWithExplicitReturns01_ts`
(es5/es2015 variants, flipped earlier), `letConstInCaseClauses_ts`
(es5/es2015 variants, flipped earlier), `exportStarFromEmptyModule_ts`
(es5/es2015 variants, flipped 16.4fj), `superWithTypeArgument3_ts`
(flipped 17.20, was duplicate-listed), and the
`assignmentCompatability37/38/39/40/41/42_ts` cluster (flipped by
17.14b/17.15b). Net zero test-count delta (all stale entries already
pass — pure documentation hygiene). Re-confirms post-17.29 pool empty
(23+ consecutive recon sessions).

**Recommended next sessions (highest absolute yield first):**
1. ~~**MAINT-1**: Stale skip-log audit (~1 session, +5–15 tests).~~ Done.
2. **Blocker #1**: Full control flow narrowing (~2–4 sessions, +60–100 tests).
   - **Step 1 (2026-04-25, 17.1a)**: Flow-graph infrastructure in binder — DONE (no behavior change yet, 0 tests). `Flow.kt` + `FlowGraphBuilder` integrated into `BinderResult.flowGraph`.
   - **Step 2a (2026-04-25, 17.1b, +1)**: First narrowing wire-up — `getNarrowedTypeForReference` walker + var-decl `never` target adoption. Flips `narrowingUnionToNeverAssigment_ts`. Supports `===`/`!==`/`==`/`!=` against literals, `&&`/`||` (De Morgan), FlowBranchLabel joins.
   - **Step 2b (2026-04-25, 17.1c+17.1d, net-zero infra)**: Extended narrowing ops + widened gate. `tryNarrowByTypeOf` handles `typeof x === "string"`; `narrowByInstanceOf` handles `x instanceof Class`. Var-decl gate widened from `never`-only to any primitive-shaped target (Intrinsic / Literal). Both commits net-zero — failing tests with these patterns gate on adjacent infrastructure (type-predicate inference, switch-true case-cond narrowing).
   - **Step 2c-i (2026-04-25, 17.1e, net-zero)**: TS2339 narrowed-to-never wiring in `checkMemberAccessMissing` — when receiver Identifier's `Type.Union` raw type narrows to `never` via flow graph, emit `Property 'X' does not exist on type 'never'.`. Uses `getTypeOfExpression` so works for function-local identifiers, not just file-globals. Companion fix: `narrowByInstanceOf` non-union contradiction now returns `never` (was returning `t`) when source matches class and isMatch=false — mirrors `narrowByTypeOfGuard`'s already-correct shape. Net-zero because failing TS2339-on-never tests (`instanceofWithStructurallyIdenticalTypes_ts`, `typeGuardConstructorDerivedClass_ts`) need additional narrowing operators (type-predicate fns, `x.constructor === Class`).
   - **Step 2c-ii (2026-04-25, 17.1f, net-zero)**: `in` operator narrowing — mirror of typeof/instanceof. New `narrowByInOperator` filters union by `typeHasOwnProperty` (positive: keep "has prop"; negative: keep "doesn't have"). Non-union returns `never` for the `!in` contradiction case. Wired into `applyConditionNarrowing`'s BinaryExpression switch. Net-zero — failing in-narrowing tests (`inKeywordTypeguard_ts`) need additional pieces (in-narrowing wired into TS2339 elaboration on union receivers, primitive-RHS TS2638, unknown-RHS TS18046).
   - **Step 2c-iii (2026-04-25, 17.3a, +1)**: Type-predicate function narrowing + symbol-identity instanceof + flow-graph activated in checkPropertyAccess. New `narrowByCallPredicate` for `predFn(arg)` calls; `narrowByInstanceOf` switched to symbol-identity (extends-chain) via new `isInstanceOfClass` helper; `currentFlowGraph` wired in `checkPropertyAccess` so 17.1e's TS2339 narrowed-to-never check actually engages. Flips `instanceofWithStructurallyIdenticalTypes_ts`.
   - **Step 2d (2026-04-25, 17.4a, +2)**: TS2774 walker extended for PropertyAccessExpression operands + parameter/local-fn typed scope + `this` type tracking + path-aware body suppression + ConditionalExpression body candidates. Flips `truthinessCallExpressionCoercion_ts` (7 emissions) and `truthinessCallExpressionCoercion1_ts` (5 emissions). Test2 (35 emissions) still requires `&&`-chain walking — deferred.
   - **Step 2e (2026-04-25, 17.4b, net-zero infra)**: TS2774 `&&`-chain walking + ExpressionStatement-level + arrow-body-level. Unified `walkUncalledChain` handles all three truthiness operators (`&&` adds siblings to suppression sources; `||`/`??` don't). `truthinessCallExpressionCoercion2_ts` now reproduces 34 of 35 expected emissions; missing one is `window.console.error` blocked on `getTypeOfIdentifier(window)` resolving as `anyType`.
   - **Step 2f (2026-04-25, 17.5a, +1)**: `x.constructor === Class` narrowing wired into `narrowByEquality`. New `narrowByConstructorEquals` filters union members by exact class symbol identity (distinct from `instanceof` — does NOT include subclasses). Flips `typeGuardConstructorDerivedClass_ts`.
   - **Step 2f-ii (2026-04-25, 17.5b, net-zero infra)**: ElementAccessExpression form `var1["constructor"]` (StringLiteralNode + NoSubstitutionTemplateLiteralNode keys) added to `isConstructorAccessOf`; negative-direction (`!==`/`!=`) corrected to `return t` unchanged (TypeScript does NOT narrow on `!==` of `.constructor` — too weak to remove union members because subclass instances and reassigned `.constructor` values exist). Foundation only — `typeGuardConstructorClassAndNumber_ts` still doesn't flip without union-receiver TS2339 multi-member elaboration.
   - **Step 2g (2026-04-25, 17.6a, +1)**: Union-receiver TS2339 multi-member elaboration in `checkMemberAccessMissing`. When narrowed receiver is still a Union with at least one primitive-like member missing the property AND at least one member having it (partial coverage), emit TS2339 with the union display + chain line naming the first missing member. Conservative gate: only emits when ALL missing members are primitives (Type.Intrinsic / literal types) — Object/Interface missing members likely indicate discriminated-union narrowing through property-equality (e.g. `ab.type === 'a'`) which isn't yet implemented. Flips `typeGuardConstructorClassAndNumber_ts`.
   - **Step 2h (next)**: Remaining wire-ups: TS2454 via flow-graph definite-assignment (replace ad-hoc walker — note 17.1c session warned a snapshot/restore approach regresses -7), `&&`-chain NARROWING wired into TS2774 emission (`uncalledFunctionChecksInConditional2_ts` — also blocked on `window` global type), FlowAssignment-RHS narrowing (medium risk — could over-narrow legitimate union-source TS2322 cases), discriminated-union narrowing through property-equality (e.g. `ab.type === 'a'`).
3. **Blocker #2**: Generic argument inference (~2 sessions, +20–40 tests).
4. **Blocker #3**: Cross-file global scope refactor (~3+ sessions, +30+ tests).

See `PLAN-PHASE-4.md` for the full reshuffled blocker list with rationale,
the candidate-picking workflow, and live session notes. See
`PLAN-PHASE-4-HISTORY.md` for archived completed items.
