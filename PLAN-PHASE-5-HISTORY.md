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
