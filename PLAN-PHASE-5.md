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
| Corpus suite | jvmTest XMLs | green forever (8,842 / 0 / 3 at phase start; 9,074 with local tests as of round 409) |
| Self-compile FPs (tsc src/compiler) | `bench/self-compile-tsc.tsv` | 13,245 → 0 (**2,443 measured at round 409**; M1 complete at 2,726/round 389; rounds 395–409 burned bounded histogram-tail buckets 2,726 → 2,443; round 408 the TS2349 "not callable" family −21; round 409 `export *`-barrel / ESM-`.js` imported-guard FLOW narrowing (M3.4) −175 (TS2339 838 → 672); remaining bounded pool M3.4/M3-gated (a general-`resolveAlias` `.js`/star fix was measured net +297 via a TS2315 flood, reverted; NonNull-strip −17 but unmasks M3, reverted); no-stub stays the honest default) |
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
  "M3-gated" verdict is about the LOG TAIL — bucket the full output.**

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
  **STILL OPEN (the next high-yield M3.4/cross-file sub-step): the barrel-imported NAMESPACE-member
  assert case** (`Debug.assertIsDefined(machine.onLeft)` — the round-408 unreproducible ×3).
  `resolveNamespaceMemberFnDecl` (the PropertyAccess-callee path) resolves the receiver `Debug`
  via the general `resolveAlias`, which stays byte-identical (does NOT follow `.js`/`export *`),
  so the barrel-imported `Debug` namespace still doesn't resolve → the assert never narrows. A
  flow-only namespace-resolution variant (resolve the receiver alias through `.js`/star to the
  namespace symbol WITHOUT touching the general resolveAlias cache, mirroring
  `resolveImportedFunctionLikeDecl`) would flip those. Also pervasive: `some(x)`/`isDefined(x)`
  Identifier guards across the TS18048/TS2339/TS2722 families are now narrowed.
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
