# Status

**Phase 4 — Checker buildout.** ~8,343 / 10,078 tests passing (~83%).

**Surgical pool is exhausted (6+ consecutive sessions confirmed).** Queue
reshuffled 2026-04-25: next sessions should commit to architectural blockers
rather than searching for surgical wins.

**MAINT-1 done 2026-04-25**: 32 stale skip-log entries marked
strikethrough; `find_candidates.py` updated to strip `~~...~~` spans. Net
zero test-count delta (all stale entries already pass). Surgical pool
remains empty after the audit.

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
   - **Step 2g (next)**: Remaining wire-ups: TS2454 via flow-graph definite-assignment (replace ad-hoc walker — note 17.1c session warned a snapshot/restore approach regresses -7), `&&`-chain NARROWING wired into TS2774 emission (`uncalledFunctionChecksInConditional2_ts` — also blocked on `window` global type), FlowAssignment-RHS narrowing (medium risk — could over-narrow legitimate union-source TS2322 cases), union-receiver TS2339 multi-member elaboration (would unlock `typeGuardConstructorClassAndNumber_ts` after 17.5b).
3. **Blocker #2**: Generic argument inference (~2 sessions, +20–40 tests).
4. **Blocker #3**: Cross-file global scope refactor (~3+ sessions, +30+ tests).

See `PLAN-PHASE-4.md` for the full reshuffled blocker list with rationale,
the candidate-picking workflow, and live session notes. See
`PLAN-PHASE-4-HISTORY.md` for archived completed items.
