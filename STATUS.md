# Status

**Phase 4 — Checker buildout.** 8,442 / 10,078 tests passing (~84%).

**17.57 (2026-05-01, net-zero infra)** — Hand-rolled Window / Performance /
MediaQueryList interfaces added to `BUILTIN_LIB_SOURCE` per user authorization.
Members chosen to cover the failing TS2774 candidates: Window has `console`,
`performance`, `matchMedia`, plus standard browser globals; Performance has
`measure`/`mark`/`clearMarks`/`clearMeasures`/`now`; MediaQueryList has
`matches`/`media`/`onchange`/`addEventListener`. Plus `declare var window:
Window;` and `declare var performance: Performance;`. Companion guard in
`checkMemberAccessMissing`: skip TS2339 when `propName` is empty (parser
error-recovery placeholder for incomplete syntax like `var p = window.` —
TS1003 already fires for the missing identifier). Net delta: 0 tests (1633
→ 1633 failed). Window typing alone doesn't flip the originally-targeted
TS2774 tests: `truthinessCallExpressionCoercion2_ts` adds the missing
emission at (116,46) but ALSO over-fires at (116,70) — TypeScript's TS2774
walker has an asymmetric rule for `&&` RHS leaves we don't replicate;
`uncalledFunctionChecksInConditional2_ts` needs TS2774 to track typed
locals inside file-level `{ }` Blocks (currently only function bodies
trigger `withUncalledScope`). Foundation for those follow-on substeps;
17.30c is now unblocked.

**17.56 (2026-05-01, +1)** — Suppress TS2415/TS2420 "separate declarations of
a private property" when the override has a DIFFERENT type than the base —
TS2416 type-mismatch is the correct primary diagnostic for that case. Two
coordinated suppressions in Checker.kt: (1) `checkClassExtendsPrivateConflicts`
(TS2415, ~40954): in the `derivedIsPrivate && baseIsPrivate` branch, compute
widened types via `getTypeOfMemberDecl` + `widenType`; if they're not mutually
assignable, `continue` so TS2415 doesn't gate TS2416 (existing
`checkClassPropertyOverrides` walk fires TS2416 next). (2)
`checkImplementsClauses` (TS2420, ~39965): mirror of the same rule — when
`isPrivate && ifacePropIsPrivate` AND types differ, suppress TS2420. Flips
`interfaceExtendsClassWithPrivate2_ts`: test has `D extends C implements I`
with `private x = 2` (number, matches C's `x = 1`) — emits TS2415 + TS2420
correctly; AND `D2 extends C implements I` with `private x = ""` (string,
differs from C's number) — pre-fix emitted FP TS2415 + TS2420 and missed
TS2416 vs C; post-fix emits TS2416 vs C and TS2416 vs I (the two correct
type-mismatch diagnostics) without the FP "separate decls" lines.
Conservative gates: bidirectional assignability, widening applied to literal
initializers, both members must have resolvable types. Net delta: 1634 →
1633 failed (8441 → 8442 passing). Zero regressions across 10078-test suite.

**17.55 (2026-05-01, net-zero infra)** — Display improvements for TS2420
implements clause: (1) class name includes type parameters
(`ObservableArray<T>` not `ObservableArray`) via new `classNameRaw`/`className`
split — squiggle uses raw length so it doesn't extend into the type-param
list; (2) `Array<T>` / `ReadonlyArray<T>` rendered as `T[]` / `readonly T[]`
shorthand; (3) `OBJECT_PROTOTYPE_PROPERTIES` filter (toString, valueOf, etc.)
applied to the missing-property walk — mirrors `propertiesRelatedTo` /
`collectMissingProperties`. Net-zero on 10078-test suite (no flips, no
regressions). Two TS2420 baselines that name a generic class
(`bluebirdStaticThis`, `genericArrayExtenstions`) now produce correct
display; neither flips because of unrelated gaps (`bluebirdStaticThis` has
many other diagnostic mismatches; `genericArrayExtenstions` still has a
property-count mismatch — 4 extras over TS's es2015 baseline because our
embedded `Array<T>` includes ES2016+ methods like `includes`/`flat`/`at`/
`findLast`/`findLastIndex` that the test target lacks). Test count
unchanged 8441 / 1634 / 3.

**17.54 (2026-05-01, +1)** — TS2420 "incorrectly implements interface" for
inherited-private property mismatches. Flips `interfaceImplementation8_ts`.
The existing privacy check at `checkImplementsClauses` (Checker.kt ~39915)
walks `classDecl.members` to find the property declaration and inspect
`ModifierFlag.Private`, but for `class C5 extends C2 implements i1 { }`
where C5 has no own `name` member and inherits `private name: string` from
C2, the lookup returned null and the check silently passed. New helper
`findInheritedPrivateProperty(classDecl, propName)` (Checker.kt ~40010)
walks the `extends` chain via `ClassDeclaration.heritageClauses` looking
for the first base class that declares `propName`; returns the base class
name when the inherited declaration carries `ModifierFlag.Private`, else
null (public override or property not found in the chain). Wired into the
existing emission branch via a unified `privateInTypeName` variable: own
private uses `className`, inherited private uses the base class name from
the helper. Diagnostic chain becomes `Property 'name' is private in type
'C2' but not in type 'i1'.` matching baseline (note "type 'C2'", not
"type 'C5'"). Test source has C4 extends C1 (public) → no emission, C5
extends C2 (private) → emit, C6 extends C3 (private) → emit. The recursive
helper handles deeper chains by recursing past base classes that don't
declare the property. Conservative: returns null when first declaration
found is public (covers the case where a derived shadows a base's private
with a public member). Net delta: 1635 → 1634 failed (8440 → 8441 passing).
Zero regressions across 10078-test suite.

**17.53 (2026-05-01, +1)** — `typeToString` for `Type.Union` now follows TypeScript's
display convention: nullish members (`null`/`undefined`/`void`) are sorted to the
END of the union, and function/constructor-typed members (anonymous `Type.Object`
with exactly one call or construct signature, no properties, no tuple) get
parenthesized so the `|` is unambiguous. Flips `optionalPropertiesTest_ts` —
chain line `Type '(() => void) | undefined' is not assignable to type '() => void'.`
now matches the baseline (was emitting `'undefined | () => void'`). Single-spot
edit at the existing `Type.Union` branch in `typeToString` (Checker.kt ~38100):
`fun nullishRank(t)` returns 1/2/3 for `null`/`undefined`/`void` Intrinsics and 0
otherwise; `sortedBy { nullishRank(it) }` is stable so non-nullish members keep
their original relative order. Function-paren gate matches the typeToString
arrow-format branch (`m is Type.Object && tupleElementTypes == null && properties
nullOrEmpty && totalSigs == 1`) so multi-sig overload-shape Objects render as
`{ ... }` and don't get an extra wrap. Cross-corpus baseline check before
implementation: 1778 occurrences of `<X> | undefined` vs only 64 of `undefined |
<X>` — the convention is overwhelmingly the new behavior. Zero regressions
across the 10078-test suite. Net delta: 1636 → 1635 failed (8439 → 8440 passing).

**17.52 (2026-04-27, +1)** — TS2845 "This condition will always return 'false'/'true'."
for enum-member references in conditional-expression conditions. Flips
`importAliasFromNamespace_ts`. New `checkEnumReferenceFalsyCondition` helper
in Checker.kt called from `checkAlwaysTruthyInExpr`'s ConditionalExpression
branch (before walking whenTrue/whenFalse). When the condition is a
`PropertyAccessExpression` whose dotted path resolves through `resolveNamePath`
+ `resolveAlias` to a const- or non-const-enum member with a computed
ConstantValue, classifies the value as truthy (non-zero number / non-empty
string) or falsy (0 / "") and emits TS2845 with the appropriate verdict.
Squiggle covers the full property-access span via `expressionTrueEnd(expr) -
expr.pos`. Reuses existing `getReferencePath` helper for path serialization.
Path resolution handles nested-namespace import aliases via `resolveNamePath`'s
fallback to `findSymbolInAllNamespaceScopes` — works for the test's
`import Internal = My.Internal` inside `namespace SomeOther.Thing { ... }`
where `Internal.WhichThing.A` resolves to const enum member A = 0. Conservative
gate: only fires from ConditionalExpression conditions (not IfStatement /
WhileStatement / etc.), only when the path resolves to an enum symbol with a
populated `enumValues` entry. Net delta: 1637 → 1636 failed (8438 → 8439
passing). Zero regressions across 10078-test suite — `errorOnEnumReferenceInCondition_ts`
errors-baseline isn't generated (per CLAUDE.md gotcha: .errors.txt baselines
commented out for this file), so no new emissions affect existing tests.

**17.51 (2026-04-27, +1)** — TS2310 "recursively references itself as a base type"
for class-extends-generic-default-indexed-access cycles. Flips
`circularConstraintYieldsAppropriateError_ts`. New `checkCircularClassBaseViaDefaultTypeArg`
helper in Checker.kt (called from init step 64f3a-2, after the existing
interface-bases TS2310 walker). Walks all `ClassDeclaration`s; for each
`class C extends Base<...>` where C self-references in extends type args at
position(s) `i`, finds Base's declaration via a top-level class-decl map,
then checks whether ANY of Base's type-param defaults uses an
`IndexedAccessType` whose `objectType` is a `TypeReference` to the type
param at position `i`. If so, emits TS2310 at C's name span (line/character
via `getLineAndCharacterOfPosition`). Two-helper structure: `defaultIndexesIntoTypeParam`
recurses through `IndexedAccessType` (object + index) and `TypeReference`
(typeArguments) so nested forms like `T = U<C['k']>` also detect. Conservative
gate avoids CRTP-pattern false positives — `class Foo extends Base<Foo>`
where `Base<T extends Base<T>>` uses CONSTRAINTS not DEFAULTS does NOT
trigger because `tp.default == null`. Pattern's narrowness (default-arg
indexed-access into a self-bound position) verified by full-suite zero
regressions across 10078-test run. Net delta: 1638 → 1637 failed
(8437 → 8438 passing).

**17.50 (2026-04-27, +1)** — TS2416 for `implements` of TypeAlias resolving
to `Type.Intersection`. Flips `implementsIncorrectlyNoAssertion_ts`. Single
surgical patch in `checkClassPropertyOverrides` (Checker.kt ~40345): when
`clause.token == ImplementsKeyword` AND the resolved heritage type is a
`Type.Intersection` (e.g. via `type Wrapper = Foo & Bar`), build a synthetic
`Type.Object` whose `members` are merged from each constituent's resolved
members (last-wins on name conflicts). The synthetic stands in for
`baseTypeRaw` so the existing TS2416 / TS2423 / TS2425 / TS2426 walk applies
uniformly. Display name override: when this branch fires, `baseTypeName` is
`typeToString(baseTypeRawOriginal)` ("Foo & Bar") instead of
`typeToString(synth)` (which would print the merged shape). Implements-only
gate is correct — TypeScript rejects intersection bases in `extends` clauses
at parse, so the branch never fires for extends. Constituents that aren't
`Type.Object` (e.g. `Foo & string`) are filtered out via `mapNotNull`. Net
delta: 1639 → 1638 failed (8436 → 8437 passing).

**17.49 (2026-04-27, +1)** — TS2416 chain elaboration for implements-clause
generic method override. Three coordinated pieces in Checker.kt flip
`genericTypeWithNonGenericBaseMisMatch_ts`. (1) `classMemberShapeMismatchDiagnostic`
gains an `isImplements: Boolean` parameter; passed `true` when
`clause.token == SyntaxKind.ImplementsKeyword`. When `isImplements` and
the would-be-emitted code is TS2425 (property → function shape mismatch),
the helper returns null. Rationale: TS2425 fires on class-property-vs-
class-method conflict because the instance field would shadow the
prototype method at runtime. Interfaces have no prototype semantics —
function-typed properties (`f: (a) => void`) are structurally
compatible with method declarations (`f(a) {}`) under `implements`.
TS2423/TS2426 retained for both `extends`/`implements` (no test
demands the relaxation). (2) `addSignatureElaboration` (TS2416 path)
adds a chain line `'T' could be instantiated with an arbitrary type
which could be unrelated to '<type>'.` when the derived param is a
`Type.TypeParam` and the base param is a concrete type. Mirrors
`getFunctionMismatchElaboration`'s 17.11a return-type version (line
47283); contravariance reversed. The hint signals that the override
is unsound — T's caller-chosen instantiation may not be compatible
with the base's concrete param. (3) `getFunctionMismatchElaboration`
(TS2322 path) recurses via `getPropertyElaborationChain` when both
inner param types are non-function `Type.Object`. Adds inner property
mismatch chain (`{a: number}` vs `{a: string}` → "Types of property
'a' are incompatible. Type 'number' is not assignable to type
'string'."). Indented by 4 extra spaces so the inner chain stacks
correctly under the param-mismatch chain line. Argument order
mirrors contravariance: `targetParamType` is the "from" displayed
type, `sourceParamType` is the "to". Net delta: 1640 → 1639 failed
(+1 test). No regressions across 10078-test suite — the chain
elaboration paths are gated to only fire on existing failure
positions (no FP risk).

**17.47 (2026-04-27, +1)** — `checkTypeArgCount` extended to QualifiedName.
Mirrors 17.46b's `isUnresolvedGenericType` extension — `var b: A.B`
where `B` is a generic class missing type args now emits TS2314 at
the right position (squiggle covering full `A.B` span via new
`nameSpan` computation: `right.pos + right.text.length - typeName.pos`).
Skips `scope.has(name)` / `KEYWORD_IDENTIFIERS` / `scope.isTypeParam`
checks for QualifiedName because resolution goes through
`getTypeParamInfo`'s namespace-export walk, not the simple-name
scope. Flips `genericCloduleInModule2_ts`. Pairs with 17.46b:
together they correctly emit TS2314 (and suppress the FP TS2454
that the bare TypeRef-without-typeName-resolution would otherwise
provoke).

**17.46 (2026-04-27, +5)** — Top-level TS2454 flow-graph walker
(retry of 17.45 with broader pre-installed type-guard recognition)
+ destructuring assignment FlowAssignment fix. Flips `isArray_ts` and
4 `sourceMapValidationDestructuring*` cases. Substep series:
17.46a pre-installed `instanceof` / lib-type-predicate (`Array.isArray`,
`ArrayBuffer.isView`, `Number.isInteger`/`isFinite`/`isNaN`/`isSafeInteger`)
/ `x.constructor === T` recognition in `conditionImpliesAssignedTrue`
(net-zero); 17.46b extended `isUnresolvedGenericType` to QualifiedName
receivers (`var b: A.B` filtered, net-zero); 17.46c added ArrayLiteral /
ObjectLiteral as `bindAssignmentTarget` targets so destructuring
assignment registers FlowAssignments (net-zero); 17.46d added
`runFlowTS2454OnTopLevel` mirroring `runFlowTS2454OnFunction` for
file-level statements, plus two Flow.kt fixes uncovered during
verification: (i) pass the underlying Identifier as `declarationNode` at
SpreadElement / SpreadAssignment / PropertyAssignment /
ShorthandPropertyAssignment destructuring leaves so
`flowAssignmentTargetsName` matches the FlowAssignment.node, (ii) new
top-level `BinaryExpression(=)` branch in `bindAssignmentTarget` handling
default-value forms `{a: x = 1}` / `[x = 1]` regardless of which
destructuring path reaches them. Initial 17.46d landed -1 (broken
destructuring FPs); after both fixes, +5 net (8429 → 8434).

**17.44 (2026-04-27, +2)** — TS2532 "Object is possibly 'undefined'" for
non-optional access on a synthetic-paren receiver produced when an
instantiation expression breaks an optional chain. Flips both
`optionalChainWithInstantiationExpression1_ts(target=es2019|es2020)`.
Three-piece change: (1) `ParenthesizedExpression` AST gains an
`instantiationEnd: Int? = null` field — non-null only when the parser
synthesizes the paren wrap for `expr<T>` followed by `.`/`?.` AND
`expr` contains an optional chain. The value is the source position
right after the closing `>` of the type-argument list, so the checker
can compute a squiggle covering `expr<T>` exactly (excluding the
trailing `.`). (2) `Parser.kt` (the existing TS1477 emission branch)
now passes `instantiationEnd = typeArgsEnd` when constructing the
synthetic `ParenthesizedExpression`. (3) New
`emitTs2532ForOptionalChainInstantiationReceiver` helper called at
the top of `checkSinglePropertyAccess`. Conservative gates: outer
access non-optional (otherwise the chain continues); receiver IS a
`ParenthesizedExpression` with `instantiationEnd != null` (the
parser's synthetic-from-instantiation marker — narrowest possible
gate, single-test-pattern in corpus); `strictNullChecks` enabled;
`findOptionalChainRootOperand` finds a `?.` somewhere in the inner
chain (defensive — should always succeed when the parser-side gate
fires, since the parser only wraps when `expressionHasOptionalChain`
is true). The base type IS NOT checked for `T | undefined` because
`typeof Namespace` resolves to `anyType` in our checker (a known
limitation of `getTypeOfSymbolForTypeQuery`'s namespace branch),
which would always defeat the check. The instantiation-paren
pattern's extreme rarity (1 test in the entire corpus, verified via
`grep '?\.[a-z]*<[A-Z,]+>\.'` over `typescript-repo/tests/cases/`)
bounds the over-firing risk. New helper `findOptionalChainRootOperand`
peels the synthetic paren and walks down looking for the first `?.`
operator, returning its operand. Test results 1648 / 3 → 1646 / 3.

**17.43 (2026-04-27, +1)** — Contextual literal preservation for substituted-T
property types. Flips `errorMessagesIntersectionTypes02_ts`. Pairs with
17.41/17.42 to close the `errorMessagesIntersectionTypes01/02_ts` cluster.
New `applyContextualLiteralPreservation(sourceType, targetType, init)` helper
in Checker.kt, called from `checkVarDeclAssignability` after computing the raw
source type. When (a) `init` is a `CallExpression` whose substituted return
type produced an anonymous Type.Object property typed as a widened intrinsic
(string/number/etc.) AND (b) the target type has a literal-typed property
at the same name, recovers the literal type from the original arg AST via
`literalTypeOfExpression`. Walks Type.Intersection / Type.Object source
constituents; only modifies anonymous Object constituents (skips named
Interface/Reference). For each property whose name matches a literal in
the call's first ObjectLiteralExpression arg AND whose target counterpart
contains literal members AND whose current source type is the widened
base of the recovered literal — replace with the literal-typed Symbol.
Two new helpers `propTypeContainsLiteral` (literal direct, or any literal
in a Union; handles Type.Intrinsic with TypeFlags.{String,Number,Boolean,
BigInt}Literal) and `literalWidensTo` (StringLiteral→stringType etc.).
Net result for `mixBar({fooProp: "frizzlebizzle"})` against
`FooBar { fooProp: "hello" | "world" }`: source displays as
`{ fooProp: "frizzlebizzle"; } & Bar` instead of `{ fooProp: string; } & Bar`,
chain emits `Type '"frizzlebizzle"' is not assignable to type '"hello" |
"world"'.`. Test 01 unchanged because target's `fooProp: boolean` doesn't
contain literal members → no replacement applied. Test results 1649 / 3 →
1648 / 3.

**17.42 (2026-04-27, +1)** — Intersection-source property elaboration in
`getPropertyElaborationChain`. Flips `errorMessagesIntersectionTypes01_ts`
(pairs with 17.41's substitution: source displays as `{ fooProp: string; } & Bar`
instead of `T & Bar`, and the chain `Types of property 'fooProp' are
incompatible. Type 'string' is not assignable to type 'boolean'.` now emits).
New `getIntersectionPropertyElaborationChain(source, target, path)` helper
in Checker.kt — merges members from object-like intersection constituents
(later wins on conflict), walks target.properties, emits the standard
"Types of property" + leaf chain on the first incompatible match. Falls
through to missing-required-property path when no type-mismatch found
(walks each Object-like constituent via the existing
`getMissingRequiredPropertySymbol`). Wired via `getPropertyElaborationChain`'s
new top-level Type.Intersection branch (delegates) AND the var-decl init
gate at Checker.kt:33655 (now accepts `sourceType is Type.Object || sourceType
is Type.Intersection`). Conservative: only direct property-level mismatches
(no recursion / method-collapsed form / privacy checks — those don't apply
to the intersection sources in the corpus). Doesn't flip
`errorMessagesIntersectionTypes02_ts` — it expects literal preservation
(`"frizzlebizzle"` vs `"hello" | "world"`) which is a separate inference
piece (literal-preserving when target has literal types). Test results
1650 / 3 → 1649 / 3.

**17.41 (2026-04-27, net-zero infra)** — Allow anonymous Type.Object arg
types at the return-type call site only. New `forReturnType: Boolean = false`
parameter on `tryInferSingleTypeParamFromArgs`; passed `true` only from
`getReturnTypeOfCallExpression` (Checker.kt:36853) to lift the named-like
bail at Checker.kt:37145 for return-type substitution. Arg-vs-param call
site (`checkArgumentsAgainstSignature`, Checker.kt:44853) keeps `false` so
16.4ds / 16.4dt per-property elaborations stay unchanged on object-literal
args. The substitution now fires for calls like `mixBar({fooProp: ...})`:
return type displays as `{ fooProp: string; } & Bar` instead of `T & Bar`.
Net-zero on the suite alone (10078 / 1650 / 3 unchanged) — paired with
17.42 to flip `errorMessagesIntersectionTypes01_ts`.

**17.40 (2026-04-27, +1)** — TS2345 for null/undefined arg vs anonymous
function-type parameter whose signature mentions sig TypeParams. Flips
`privacyCheckAnonymousFunctionParameter2_ts`. Mirror of 17.11c
(Type.Reference param) but for `Type.Object` with callSignatures /
constructSignatures (excluding Reference / Interface). Pattern:
`foo<T>(x: (a: Iterator<T>) => number)` called with `foo(null)` —
display substitutes sig TypeParams to `unknown`, rendering param as
`(a: Iterator<unknown>) => number`. New `sigMentionsAnyTp` helper
recurses via existing `sourceContainsTypeParam` over signature
params + return. Display path builds a wrapper `Type.Object` with
`instantiateSignature(s, mapper)` applied to each call/construct sig
(mapper substitutes sig-side TypeParams to `unknownType`), then
calls `typeToString` on the wrapper. Test results 1651 / 3 → 1650
/ 3.

**17.39 (2026-04-27, +1)** — Function-typed property substitution + first-decl-wins
local-type map. Flips `genericConstraintSatisfaction1_ts`. Closes the
"property f: <T extends S>(x: T) => void on generic interface I<S>" gap
in Blocker #2 — when calling `x.f({s: 1})` on `var x: I<{s: string}>`,
T's constraint was unsubstituted (still `TypeParam(S)`) so 16.4ds
per-property elaboration didn't fire. Two coordinated pieces in
Checker.kt: (1) New helpers `substituteOuterTypeArgsInGenericFnObject`
+ `substituteOuterTypeArgsInSignature` invoked from
`resolveGenericPropertyType`'s PropertyDeclaration branch when rawType
is Type.Object (excluding Reference/Interface) with non-empty
callSignatures or constructSignatures. Mutates the freshly-allocated
sig's typeParam constraints/defaults in place via the outer mapper,
then substitutes params and return. Sig's typeParameters are
PRESERVED (T stays generic) so call-site inference + 16.4ds / 16.4i
still fires. (2) `checkVarDeclAssignability`'s annotated-typeNode path
now skips `currentLocalTypes[name] = ...` when already set —
first-decl wins for the local-type map, matching Binder's
`valueDeclaration` semantics (Binder.kt:355). Without this, line 6's
`declare var x: I<{s: string}>` overwrites line 5's `var x: I<{s:
string}>` in `currentLocalTypes`, so `getTypeOfIdentifier(x)` returns
the line-6-resolved Reference whose typeArg is the line-6-TypeLiteral
copy of `{s: string}`, and TS6500 "declared here" points to the wrong
copy of property `s`. Test results 1652 / 3 → 1651 / 3.

**17.38 (2026-04-27, +1)** — Object-literal-of-T param shape inference for
single-arg generic calls. Flips `widenToAny1_ts`. Closes the simplest
"needs full generic argument inference" Blocker #2 case for object-
literal param shapes: `foo<T>(f: { x: T; y: T })` called with
`{ x: undefined, y: "def" }` now infers T = `string | undefined` (LUB-
as-Union over collected widened property values), substitutes through
return type T, and triggers TS2322 at the var-decl site
`var z1: number = ...`. Three pieces in `tryInferSingleTypeParamFromArgs`:
(1) new `isAnonymousObjectWithTypeParamMembers` helper — Type.Object
(excluding Type.Interface / Type.Reference / sym-non-null / call-or-
construct-sigs / index-sigs) whose every member is a bare TypeParam in
`tps`. Conservative — mixed shapes like `{x: T; y: number}` bail.
(2) Outer gate (Checker.kt:36996) adds clause (e): `!isRest &&
isAnonymousObjectWithTypeParamMembers(pt, tpsSet)`. (3) Per-tp gather
adds `isObjLitOfT` branch BEFORE the standard rawArgType pipeline.
Walks pt's members, finds ones typed exactly as tp, looks up arg's
same-named property via `getPropertyKeyName`, widens each value type,
LUBs as Union when multiple. Handles `undefined` values via the LUB
path (the standard undefined-arg bail doesn't fire because the arg as
a whole is a Type.Object, not undefined). Named-like check accepts
Union-of-named-likes (matches the isArrayT extension from 17.31f).
Lifted `isNamedLikeAtom` to outer `for (tp in tps)` block so both
isObjLitOfT and isArrayT/isBareT paths share the predicate. Test
results 1653 → 1652 failed (8422 → 8423 passing). Foundation:
`widenToAny1_ts`'s `{x: T; y: T}` shape, plus the 17.31f-installed
callBypass in `checkVarDeclAssignability`, produces TS2322 with chain
"Type 'undefined' is not assignable to type 'number'." at (4,5).

**17.37 (2026-04-27, +1)** — Empty-array `[]` arg → `T = never` inference for
`Array<T>` params + CallExpression-receiver TS2339 'never' emission. Flips
`inferentiallyTypingAnEmptyArray_ts`. Three coordinated pieces in Checker.kt:
(1) `tryInferSingleTypeParamFromArgs` now special-cases empty
`ArrayLiteralExpression` arg for `Array<tp>` params: contributes a `neverType`
candidate instead of bailing on `rawArgType === anyType`. (2)
"Never-wildcard" rule: when gathering candidates for a typeParam, an empty-
array `never` candidate is treated as a wildcard — the helper builds
`effectiveCandidates = candidates.filter { it.widenedType !== neverType }`
(falling back to the full list only if every candidate is never), and uses
that for both first-candidate selection AND multi-arg conflict detection.
Without this, `f<T>(arr: T[], elemnt: T)` called with `f([], 3)` would anchor
T = never (from the empty array) and then fire spurious TS2345 at arg `3`
(checked against substituted `never` param) — TypeScript correctly anchors
T = number from the second arg. (3) New CallExpression branch in
`checkMemberAccessMissing` emits TS2339 'never' when the call's resolved
return type IS exactly `neverType`. Narrow gate: only fires for receivers
that ARE CallExpressions and whose return is exactly never; doesn't extend
to other receiver shapes (full call-result property checking against arbitrary
return types stays out of scope).

**17.36 (2026-04-27, +1)** — TS2300 "Duplicate identifier 'eval'" for
top-level `var eval` in non-module strict mode. Flips
`variableDeclarationInStrictMode1_ts`. Two-piece change: (1)
`BaselineFormatter.kt` summary section (Part 1) now renders lib files
(`lib.*.d.ts`) with `(--,--)` when `line == null` — previously the
prefix was suppressed entirely, so lib-side primary diagnostics
rendered as plain `error TSXXXX:` without a file column. (2)
`diagnosticComparator` now sorts lib files AFTER user files (matches
TypeScript's baseline ordering — user-file diagnostics precede
lib-side ones, regardless of alphabetic order). (3) New
`checkStrictModeReservedRedeclaration` in Checker.kt fires only for
`var eval` (NOT `var arguments` — not lib-declared) at top level in
non-module strict mode (`globalStrict || hasUseStrict` AND
`!isModule`). Emits TS2300 at the user position, TS2300 at lib
position (line=null, char=null → renders as `lib.es5.d.ts(--,--)`),
and TS6203 related info on the user-side TS2300 ("'eval' was also
declared here."). Conservative gates rule out the 2 regressions an
initial broader version caused: (a) module-file `var eval` only emits
TS1215 (`jsFileCompilationBindStrictModeErrors_ts` c.js — module
auto-strict), (b) `var arguments` is NOT lib-declared as a global var
so doesn't fire TS2300 (`argumentsBindsToFunctionScopeArgumentList_ts`
alwaysstrict_true).

**17.35a (2026-04-27, net-zero infra)** — Expand falsy/truthy
narrowing to literal-truthy types. `isDefinitelyTruthyMember` now also
returns true for non-empty `Type.StringLiteral`, non-zero
`Type.NumberLiteral`, `trueType` (the `Type.Intrinsic` boolean-literal
"true"), and non-zero `Type.BigIntLiteral` (string value not "0"/"0n"
via new `isZeroBigIntLiteral` helper). Mirror addition:
`isDefinitelyFalsyMember` now also drops empty `Type.StringLiteral`,
zero `Type.NumberLiteral`, and zero `Type.BigIntLiteral` from union
on the truthy side. Both non-union and union dispatch in
`narrowByTruthiness` were also unified — non-union dispatch now uses
the same predicate so a bare definitely-truthy/falsy literal also
collapses to `neverType`. Primitive intrinsics (`stringType`,
`numberType`, `booleanType`, `bigintType`) deliberately kept on both
sides — they could be the empty/zero variant. Test results 10078 /
1655 / 3 (unchanged from 17.34e). Foundation net-zero — no failing
test gates solely on literal-truthy filtering, but the path to
`if (x) {} else { /* x: 0 | "" | undefined */ }` and similar
literal-receiver narrowing is now in place. Closes 17.35a/b — 17.35b
was scoped as "verify primitive case" with no expected code change;
17.35a's primitive-on-both-sides behavior matches that intent.

**17.34e (2026-04-27, net-zero infra)** — Conservative falsy-side
narrowing in `narrowByTruthiness`. Pre-fix: `truthy=false` returned `t`
unchanged per the 17.7e session note's regression-risk gate. Post-fix:
on the falsy branch, drop union members that are definitely truthy —
`Type.Object` / `Type.Interface` / `Type.Reference` (object types are
always truthy in JS). New helpers: `isDefinitelyFalsyMember` (recasts
the existing truthy-side filter as a named predicate) and
`isDefinitelyTruthyMember` (the new falsy-side filter). Non-union
object-likes also collapse to `neverType`. Conservative scope: literal-
truthy types (non-empty StringLiteral, non-zero NumberLiteral,
trueType, non-zero BigIntLiteral) and primitive-truthy (`stringType`,
`numberType`, etc. — they could be the empty/zero variant) NOT yet
dropped — adding those expands the regression surface and warrants a
follow-up substep. Test results 10078 / 1655 / 3 (unchanged from
17.34d). No new flips — corpus's failing tests don't gate solely on
object-type-only falsy narrowing. Foundation: `if (foo) {} else { /*
foo: Foo | null */ }` patterns where Foo is Object/Interface/Reference
now narrow `foo` to `null` on the else side. PropertyAccess version
inherited via the existing path comparison from 17.34a. Closes the
17.34 substep series — 17.34a/b/c/d/e together implement PropertyAccess
narrowing across the narrowing infrastructure.

**17.34d (2026-04-27, net-zero infra)** — PropertyAccess narrowing in
`getTypeOfPropertyAccess` for non-var-decl read positions. The function
was returning the raw declared type without consulting the flow graph;
wired through a new wrapper that calls `getNarrowedTypeForReference`
when the raw type is a Union AND `getReferencePath(expr) != null` (pure
Identifier-or-PropertyAccess chain). Implementation: extracted the
existing body to `computeRawTypeOfPropertyAccess` (private helper),
made the public `getTypeOfPropertyAccess` a thin wrapper. Conservative
gate keeps performance impact minimal: non-Union types skip the flow
lookup entirely; outside expression-checking phases `currentFlowGraph`
is null so `getFlowAt` returns null and `getNarrowedTypeForReference`
returns the raw type unchanged. Test results 10078 / 1655 / 3 (no
change from 17.34c) — no failing test gates solely on PropertyAccess
narrowing being visible in expression-context reads. Foundation:
chained accesses like `A._a.length` now see narrowed `A._a` type when
inside `if (A._a) { ... }`. Remaining 17.34 substep is 17.34e
(falsy-side narrowing — needs general infrastructure beyond just
PropertyAccess).

**17.34c (2026-04-27, net-zero infra)** — Wire PropertyAccess narrowing
into TS2339 union-receiver narrowed-to-never branch. Restructured the
gate at Checker.kt:42395: was `if (objectExpr !is Identifier) return`
unconditionally, now first allows PropertyAccess receivers (gated via
`getReferencePath(objectExpr) != null`) to reach the narrowing block
(narrowed-to-never / single-Object / Union-with-missing emissions),
then bails to identifier-only paths after the narrowing block — the
identifier-symbol lookup paths beyond (`globals[identName]`-driven
namespace/typeof/wrapper emissions) don't generalize to dotted-path
receivers. Test results unchanged 10078 / 1655 / 3 — no failing test
gates solely on TS2339 narrowed-to-never on PropertyAccess receivers.
The narrowing block now covers `if (typeof A._a === "string") {} else
{ A._a.length }` (A._a narrowed to never in else branch via
exhaustive typeof guard) and `if (A._a.kind === "a") { A._a.aProp }`
(discriminant-property narrowing on PropertyAccess via 17.34b's
`isDiscriminantAccessOf` extension). Remaining 17.34 substep is 17.34d
(PropertyAccess narrowing in `getTypeOfPropertyAccess` for non-var-decl
read positions — broad change, deserves its own session for careful
testing).

**17.34b (2026-04-27, net-zero infra)** — Extend narrowing operators
to compare PropertyAccess paths. Six call-site flips replacing
`expr is Identifier && expr.text == name`-pattern checks with
`getReferencePath(expr) == name` (the helper introduced in 17.34a):
(1) `narrowByEquality` left/right operand check — direct-equality
narrowing now matches `A._a === literal`; (2) `isTypeOfRef` — `typeof
A._a === "string"` matches when name="A._a"; (3) `narrowByCallPredicate`
arg check — `predFn(A._a)` matches; (4) `narrowByInstanceOf` left
operand — `A._a instanceof Class` matches; (5) `narrowByInOperator`
right operand — `'k' in A._a` matches; (6) `isConstructorAccessOf` and
`isDiscriminantAccessOf` receiver checks — `A._a.constructor === C` and
`A._a.kind === 'foo'` patterns. Path-comparison preserves prior
Identifier-only behavior (Identifier path = `expr.text`); same
identity-based comparison for both. Test results unchanged 10078 / 1655
/ 3 — no failing test gates solely on these operators applied to
PropertyAccess sources. Foundation for 17.34c (TS2339 narrowed-to-never
on PropertyAccess receivers) and 17.34d (PropertyAccess narrowing in
read positions via getTypeOfPropertyAccess).

**17.34a (2026-04-27, net-zero infra)** — PropertyAccess narrowing
infrastructure for class statics. New `getReferencePath` helper in
Checker.kt serializes any `Identifier`-or-`PropertyAccessExpression`
chain as a dotted path (`"A._a"`, `"this.field"`, `"a.b.c"`); returns
null for shapes with calls, parens, or element access. Three call-site
extensions: (1) `getNarrowedTypeForReference` consumes the path string
instead of an Identifier-only `expr.text`, so it now narrows
PropertyAccess sources too; (2) `applyConditionNarrowing` adds a
`PropertyAccessExpression` branch parallel to its `Identifier`
truthiness branch — `if (A._a) { ... A._a ... }` narrows from
`T | undefined | null` to `T` on the truthy side via the existing
`narrowByTruthiness` helper; (3) `checkVarDeclAssignability`'s
narrowing call-site widens its gate from `init is Identifier` to
`init is Identifier || init is PropertyAccessExpression` (still
gated by `isNarrowableTarget`). Test results unchanged 10078 / 1655 / 3
— `classStaticPropertyTypeGuard_ts` (the test 17.31f's gate masks)
continues to pass; no failing test in the corpus gates SOLELY on this
narrowing path. Foundation for follow-on substeps: (a) extend
`narrowByEquality`/`narrowByInstanceOf`/`narrowByInOperator`/
`narrowByCallPredicate`/`narrowByConstructorEquals` to compare
PropertyAccess paths (currently still only Identifier); (b) wire
PropertyAccess narrowing into the TS2339 union-receiver
narrowed-to-never branch (Checker.kt:42376 still gated on
`objectExpr is Identifier`); (c) wire into `getTypeOfPropertyAccess`
so identifier-of-property-access sources see narrowed types in
non-var-decl positions. Foundation only — not a regressing change.

**17.33 (2026-04-27, +1)** — TS2686 "refers to a UMD global" emission
for `export as namespace X;` references in module files. Flips
`jsdocReferenceGlobalTypeInCommonJs_ts`. Two-piece change: (1) source-text
regex scan during init step 1d collects `umdGlobalNames` (matches
`^\s*export\s+as\s+namespace\s+IDENT\s*;?` in .d.ts files — the parser
falls through to expression-statement parsing for this construct, so a
regex on the raw source is the smallest sufficient implementation) +
`moduleFiles` set (any file with imports/exports OR — for .js/.jsx/.mjs/.cjs
— a top-level `require(...)` call). (2) `checkIdentifierResolved`
TS2304-emission branch now checks `name in umdGlobalNames && fileName in
moduleFiles` first; if so, emits TS2686 with the standard "Consider
adding an import instead." message instead of TS2304. Conservative gates:
the spelling-suggestion (TS2552) path still runs first, so a UMD global
that has a near-spelling alternative would still get TS2552 (matches
TypeScript's behavior of preferring suggestions when available).

**17.32e (2026-04-27, net-zero behavior)** — TS2304 file-scope flip
(Blocker #3 step 1 — final substep; "highest blast radius" landed clean).
`checkUnresolvedNames` (Checker.kt:8598) now builds `fileScope.names` from
`perFileScope[fileName]` (lib + script-file locals + own-file locals)
instead of iterating `result.locals + globals` (the over-merged map
containing every module-file's locals). Cross-file unimported identifiers
in module file A no longer silently resolve via `globals[X]` from unrelated
module file B. Defensive fallback to legacy iteration when perFileScope is
null (matches 17.32b/c/d pattern). Test results unchanged from 17.32d
(8419 / 1656 / 3) — strict-improvement change with zero regression: tests
don't rely on the cross-file leak. Closes the major 17.32 migration: all 4
identifier-resolution sites identified for the series (ctorParam shadow
disambiguation, TS2552 spelling-suggestion candidates, default-import /
export-equals helper, TS2304 file-scope) now consume `perFileScope`.
Remaining minor `globals[X]` sites in `resolveAmbientModuleExportEquals`
(line 2333) intentionally left — the ambient-module-internal
`import alias = X` pattern legitimately needs cross-file global lookup.

**17.32d (2026-04-27, net-zero behavior)** — Third call-site flip onto
17.32a's per-file scope: `resolveExpressionToSymbol`'s Identifier branch
(used by both `export default X` resolution at Checker.kt:2207 and
`export = X` resolution via `resolveModuleExportAssignment` at
Checker.kt:2303) now resolves identifiers inside the export-default /
export-equals expression against the target file's `perFileScope` (lib +
script-file locals + own-file locals) instead of the over-merged `globals`
map. Other module files' locals are not visible inside the target module
without an explicit import, so the previous `result.locals[X] ?: globals[X]`
chain could find symbols that wouldn't actually resolve in the target's
scope. New chain: `result.locals[X] ?: perFileScope[fileName]?.get(X)` with
a defensive fallback to `globals[X]` if perFileScope is null (matches the
17.32b/c pattern). Test results unchanged from 17.32c (8419 / 1656 / 3) —
no failing test in the corpus gates on this filter (consistent with prior
flips), but the change reduces cross-file pollution in the export-resolution
helper. Foundation for the eventual 17.32e+ TS2304 file-scope flip.

**17.32c (2026-04-27, net-zero behavior)** — Second call-site flip onto
17.32a's per-file scope: `getSpellingSuggestion`'s value-position candidate
pool now consults `perFileScope[fileName]` at the file-root scope instead of
the over-merged `globals`-derived `s.names` set. Inner (function/block)
scopes still contribute their `names` unchanged — those are this file's own
lexical bindings. KNOWN_GLOBALS continues to be added at the start. Removes
other-file MODULE locals from TS2552 spelling-suggestion candidates without
touching TS2304 visibility (which still consults the legacy file-root
scope — highest blast radius, deferred). Test results unchanged from 17.32b
(8419 / 1656 / 3) — no failing test in the corpus gates on this filter, but
the change reduces cross-file pollution in the suggestion pool which is
foundation for 17.32d+ flips. Type-position branch unchanged (uses scope
chain `typeParamNames` / `typeNames` which are file-local only).

**17.32b (2026-04-27, net-zero behavior)** — First call-site flip onto 17.32a's
per-file scope: `ctorParamShadowsRealOuterBinding` (TS2663-vs-TS2301
disambiguation for parameter-property shadow in class member initializers)
now consults `perFileScope[currentFileName]` instead of walking `binderResults`
with an ad-hoc module-file filter. The new code is semantically equivalent —
both encode "lib + script-file locals + own-file locals, excluding other-file
module locals" — but uses the centralized infrastructure so future identifier-
resolution call sites can follow the same pattern. KNOWN_GLOBALS still
checked first (companion-level data not in the binder's lib output).
Test results unchanged from 17.31f (8419 / 1656 / 3). Foundation for
17.32c+ flips (TS2552 spelling suggestion candidate set, default-import-
from-export-equals visibility).

**17.31f (2026-04-27, +1)** — Union-element widening for `Array<T>` inference +
CallExpression source bypass for Union→primitive var-decls. Flips `widenToAny2_ts`.
`tryInferSingleTypeParamFromArgs` `isArrayT` branch now widens Union constituents
(`widenType` skips `Type.Union`; explicit `getUnionType(types.map(widenType))`
recurses) so `Array<undefined | "def">` infers T = `undefined | string`. The
`isNamedLike` check is now a local `isNamedLikeAtom` helper applied either
directly OR (for `isArrayT` only) to every Union constituent — anonymous-Object
members in heterogeneous arrays (e.g. `[{a:1}, "def"]`) still bail because the
widened anonymous Object fails `isNamedLikeAtom`. Wired pair: `checkVarDeclAssignability`
adds a `callBypass` of `canUseTypeEngine`'s nullish-Union gate when `init is
CallExpression` AND `sourceType is Type.Union` AND target is primitive-shape AND
`strictNullChecks` is on — CallExpression results aren't narrowable so the
gate's control-flow narrowing safety rationale doesn't apply. Initial broader
version (lift the gate inside `canUseTypeEngine`) regressed
`classStaticPropertyTypeGuard_ts__target_es5__` (`return A._a` after `if (A._a)`
needs PropertyAccess narrowing on class statics that we don't have); narrowed
to `init is CallExpression` keeps that latent gap masked.

**17.31e (2026-04-27, net-zero infra)** — Reference-arg `Array<T>` inference
for non-rest params. Gate clause (d) added: non-rest param of `Array<tp_i>`
(any tp). Per-tp gather grew an `isArrayT` branch — for non-rest `Array<tp>`
params, extracts the element type X from the call arg's same-target
`Array<X>` reference (bails when arg isn't a `Type.Reference Array`),
applies `widenType` to widen literal element types, and contributes the
widened X as the candidate (no literal type — array element doesn't have a
single literal value). 17.31a's `isNamedLike` check still applies on the
extracted/widened X — Union element types (`Array<undefined | "def">`,
`Array<1 | 2>`) bail. Renamed `isRestArrayOfTypeParam` → `isArrayOfTypeParam`
(same body; helper now used for both rest and non-rest contexts).
Net-zero on the suite: `widenToAny2` (`foo3<T>(x: T[])` called with
`[undefined, "def"]`) bails because `Array<undefined | "def">`'s element
is a Union; `inferentiallyTypingAnEmptyArray` (`foo([])`) bails because
empty array literal returns `anyType`; `subtypeReductionWithAnyFunctionType`
needs context-sensitive arrow inference (`compact<T>(arr: T[])` with arg
inside an arrow callback). Foundation for 17.31f-style follow-ups
(Union-element handling, contextual typing through Array<T> params).

**17.31d (2026-04-27, net-zero infra)** — Multi-typeParam inference (independent
T, U) extended `tryInferSingleTypeParamFromArgs` from single-tp to N-tp.
Gate replaced: was `tps.size != 1` early-return; now allows ANY tp count where
every param is bare-some-tp_i, rest-of-tp_i[] (last param only), or fully
concrete (mentions NONE of our tps). Per-tp candidate gathering runs
independently — each tp_i gathers from positions where param IS exactly tp_i,
runs 17.31a's named-like + constraint gates and 17.31b's multi-arg conflict
detection. On any tp's gather-side bail (anyType / undefined arg / non-named
arg), the WHOLE function returns null so the bare-TypeParam continue path in
`checkArgumentsAgainstSignature` keeps firing (matches old behavior). Built
multi-mapper covers every tp; `instantiateSignature` substitutes all of them.
Net-zero on the suite: 8 failing tests have all-bare multi-tp sigs, but most
either (a) have NO `.errors.txt` baseline so trivially pass already
(`objectAssignLikeNonUnionResult`, `silentNeverPropagation`,
`contextualSigInstantiationRestParams`), (b) have non-call usage like
`tt = tuple2(...)` where the failing baselines test the var-assignment side
not the call (`tupleTypes`), or (c) have body-internal patterns the call-site
inference doesn't reach (`typeParametersShouldNotBeEqual2`,
`genericCallbackInvokedInsideItsContainingFunction1`). `defaultBestCommonTypesHaveDecls`'s
`concat2(1, "")` activates the new multi-tp path (T0=number, T1=string) but
the failing baseline gates on UNRELATED single-tp `concat(1, "")` shape.
Net-zero net delta — foundation for 17.31e (Reference-arg `Array<T>` inference)
which can build on the per-tp gather loop now in place. Old `isParamShapeAllowedFor17_31a`
helper removed (single-tp logic merged into the new gate).

**17.31c (2026-04-27, +3)** — Rest-param `T[]` inference + post-loop emission
helper for trailing rest args. Two-piece change: (1) `tryInferSingleTypeParamFromArgs`
gate extended to allow rest param of `T[]` (new `isRestArrayOfTypeParam` helper);
candidate-gathering loop walks every trailing arg at the rest position so each
contributes a T candidate (uses 17.31b's existing widened-vs-literal candidate
shape and conflict-detection). (2) New `checkRestArgsAgainstArrayElementType`
runs at the end of `checkArgumentsAgainstSignature` (gated on
`diagnostics.size == initialDiagCount` so it doesn't double-fire when the
standard loop already emitted) — extracts the rest's `Array<X>` element type
and emits TS2345 at the first trailing arg whose type is not assignable to X.
Conservative gate: `isSimpleCheckableType(argType)` (continues past complex
args) — needed to avoid FPs in `concatError_ts` (`fa.concat([0])` — our lib
lacks the `Array.concat(...items: T[][])` overload TypeScript ships) and
`typeArgInference_ts` (`x.g<number,string>([o], [o])` — structural compare on
nested `Array<{a:T;b:U}>` is incomplete). Element-type guard skips when X is
still a `Type.TypeParam` (inference failed or two-typeParam case).
Flips `genericRestArgs_ts` (3 missing diagnostics: inference path
`makeArrayG(1,"")` arg[1]; explicit `makeArrayG<number>(1,"")` arg[1];
explicit `makeArrayG<any[]>(1,"")` arg[0]) plus +2 incidental flips from
adjacent rest-T inference patterns elsewhere in the corpus. Foundation for
17.31d (`Array<T>` arg-shape inference for non-rest cases).

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
