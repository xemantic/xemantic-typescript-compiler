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

**Round 408 (2026-07-05) — a decomposed M3.4 slice: the TS2349 "not callable" family (the
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
| Corpus suite | jvmTest XMLs | green forever (8,842 / 0 / 3 at phase start; 9,134 with local tests as of round 414) |
| Self-compile FPs (tsc src/compiler) | `bench/self-compile-tsc.tsv` | 13,245 → 0 (**1,930 measured at round 414**; M1 complete at 2,726/round 389; rounds 395–414 burned bounded histogram-tail buckets + M3.4 flow-narrowing slices 2,726 → 1,930; round 409 `export *`-barrel / ESM-`.js` imported-guard FLOW narrowing (M3.4) −175 (TS2339 838 → 672); round 411 enum-member discriminant narrowing + type-guard-narrows-member-DOWN −59; round 412 single-type type-guard narrow-DOWN + TS18048 receiver-narrowing −1; round 413 the `export *` LEAF-EXPORT gate −407 (TS2339 614 → 237): the pre-413 star resolver returned non-exported IMPORT aliases, so barrel-imported `Debug.assert` (& every barrel guard) never resolved — the TRUE builder.ts blocker, NOT the round-412 depth red herring (an instrumented run showed ZERO walk truncations) — plus a dashboard-neutral tsc-faithful linear flow-walk iteration + a return-path narrowing consumer (−1); **round 414 the TS2366 "lacks ending return" family −35 (50 → 15): three CFA fall-through patterns in `statementAlwaysReturns`/`switchAlwaysReturns` — infinite-loop-with-return, trailing never-call (`Debug.fail`), switch fall-through — all FP-safe syntactic/barrel-resolution fixes; the remaining 15 are Pattern C2 (exhaustive switch w/o default → M3.4 discriminant-exhaustiveness)**; remaining bounded pool M3.4/M3-gated (a general-`resolveAlias` `.js`/star fix was measured net +297 via a TS2315 flood, reverted; NonNull-strip −17 but unmasks M3, reverted; const-string-enum→`string` relation deferred M3.3/B425); no-stub stays the honest default) |
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
