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

**Round 467 (2026-07-10, same session as 466) — the services burn-down starts: THREE bounded
fixes. Dashboard: services 126 → 116 (−10; TS7006 7 → 1, TS2339 8 → 6, TS2322 40 → 38); every
step verified strictly-removals by listAll diff. Suite 9,863 → 9,872 (+9 local across 3 new test
files, 0 regressions); 3 fix commits (d9f0ecab / cfa323d0 / 843f8ce9).**
- **Fix 1 (d9f0ecab, TS7006 array-element ctx + nested-interface annotation retry; 126 → 120):**
  the round-443 revert closed — checkImplicitAnyInExpr's ArrayLiteral branch now propagates the
  array's ELEMENT type into elements (object literals get member fn context, arrows the
  callable-arity suppression; a UNION contextual type yields NO element type, preserving the
  pinned contextualSignatureInArrayElement* rule). The round-443 blocker fell to
  `uniqueNestedInterfaceByName` + `nestedInterfaceCtxType` (transient synthetic symbol, memoized
  by decl; gates: unbound-anywhere + unique + non-generic) feeding
  `resolveImplicitAnyCtxAnnotation`'s bare-`X`/`X[]` retry — inferFromUsage.ts's function-body
  `interface Priority` + `const priorities: Priority[] = [{ high: t => …, low: t => … }]` ×6.
- **Fix 2 (cfa323d0, `switch (typeof ref)` clause narrowing, M3.4; 120 → 118):**
  narrowBySwitchClause gains a TypeOfExpression subject arm — each clause narrows the walked
  reference by its string tag via narrowByTypeOfGuard (positive for the matched range's tags,
  negative for prior cases, negative-by-all for a default; non-string-literal cases bail). The
  round-425 "object"-tag verdict already did the filtering — only the switch-subject arm was
  missing (completions.ts:1477 `type.value.negative` on `string | number | PseudoBigInt` under
  `case "object":`, TS2339 ×2).
- **Fix 3 (843f8ce9, inference gate (l) — the compact idiom, M3.1; 118 → 116):** an
  Array/ReadonlyArray param whose element union is exactly one bare TP plus DROPPABLE members
  (nullish / falsy literals) — core.ts `compact<T>(array: (T | undefined | null | false | 0 |
  "")[]): T[]` had no accepting gate, so the bare-`T[]` overload won and bound T WITH undefined
  (smartSelection.ts:336 `(SyntaxList | Node | undefined)[]` vs `readonly Node[]` ×2). The
  candidate is the arg's element union minus members assignable to a droppable.
- **NEXT (services @ 116, ~70 real):** organizeImports ×3 (heterogeneous: `??`-RHS literal
  widening in a contextual position at 954; ternary-arm array-literal member context at 216;
  destructured-member `??` write at 115); documentHighlights ×3 (`return [node]` after a guard
  needs a contextual array-element narrow-DOWN, + a `Node` vs `SourceFile` arg); the
  conflated-Info return-ternary family (convertExport ×3 / importTracker ×2 / findAllReferences
  ×2 / signatureHelp / fixExpectedComma / inlineVariable / convertToOptionalChain — union-source
  `{ error } | { … }` returns against `X | RefactorErrorInfo | undefined`); the services.ts
  objlit giants (ObjectAllocator / CompletionEntry / EmitTextWriter TS2740); mapCode.ts:55
  flatten (gate-(k) candidate needs a probe — the arg display resolves but T stays raw);
  stringCompletions `.types`/`.value` on `Type` (public-API `isUnion()`/`isStringLiteral()`
  this-guard modeling).**

**Round 466 (2026-07-10) — Blocker #2 landed for the compiler profile: map-callback return
inference through nested functions clears builder.ts:2390, the LAST real compiler FP. Dashboard:
compiler 47 → 46 (**ZERO real FPs — all 46 are env-legit TS2591×43 require / TS2304×2 `global` /
TS2584 console, waiting on real @types/node**), services 127 → 126 — both listAll diffs are
EXACTLY the one removal. Suite 9,855 → 9,863 (+8 local in MapCallbackReturnInferenceTest, 0
regressions); 1 fix commit (e12b905b). Bench: self ~28 s (normal band).**
- **The chain (one FP, five mechanisms):** `arrayToMap(diagnostics, value => toFilePath(value[0]),
  value => value[1])` must select the generic `Map<K, V2>` overload with K := Path, which needs the
  arrow body typed through: `toFilePath` (UNIQUE nested fn, un-annotated) → `filePaths[fileId - 1]`
  → `filePaths = buildInfo.fileNames?.map(toPathInBuildInfoDirectory)` (AMBIGUOUS 2-decl nested
  callback) → both bodies `return toPath(…)` (BARREL-imported, and the merged-globals `toPath`
  resolves to tsbuild's same-named 2-param NESTED fn — the round-440 pollution family).
- **Mechanisms landed (all in tryInferSingleTypeParamFromArgs / getReturnTypeOfCallExpression):**
  (a) NAMED-function callback args bind a return-position TP from the fn's return type, with
  all-candidates-agree resolution for ambiguous nested names (`namedFnCallbackReturnType`);
  (b) `tryNestedFnCallReturnType` — a call to a body-nested fn types its return (annotated, or
  single-return body inference with the decl's own params scoped; depth 3; first-touch memo);
  (c) a barrel-imported Identifier callee resolves through the flow-only import resolver INSIDE
  inference bodies (lexical import beats polluted globals); (d) ReadonlyArray.map is now generic
  (mirrors Array.map — corpus-green); (e) callback-return positions accept `K | undefined`,
  anchor-able TPs gather before callback-return TPs, and branded intersections
  (`string & {__pathBrand}`) count as named-like candidates.
- **Measured gates (each violated cut produced a real FP on the profile):** the nested-fn return
  capability is INFERENCE-BODY-SCOPED (`inInferenceBodyTyping`) — the program-wide version was
  net +8 (checker.ts createTypeChecker objlit, getNodeLinks property writes, classFields receiver,
  commandLineParser objlit: concrete types riding M3 relation gaps); the barrel-callee pre-step is
  gated NON-generic (`SortedReadonlyArray<T>` resolves garbage without the TP scope) + BODIED
  (a bodyless decl is one OVERLOAD of a cluster — `sortAndDeduplicate`'s non-generic overload) +
  no-explicit-type-args; a heterogeneous ARRAY-LITERAL callback body contributes NO candidate
  (tsc contextually tuple-types it — builder.ts:1332 `map(key => [toFileId(key), …])`).
- **Tooling trap (CLAUDE.md gotcha added):** `pkill -9 -f KotlinCompileDaemon` KILLS THE INVOKING
  SHELL — the -f pattern matches the bash -c command line itself (the pgrep self-match gotcha's
  pkill variant); several compound commands silently died mid-chain. Use `'KotlinCompile[D]aemon'`.
- **NEXT: the services profile burn-down (126 — TS2322×40 / TS2345×12 / TS2339×8 / TS7006×7 …),
  then server (last measured 402 at round 458) / harness (615). v1 = all 8 profiles at zero FPs.**

**Round 465 (2026-07-10) — bounded FP burn-down to the LAST real compiler FP: SEVEN fixes across
8 sites. Dashboard: compiler 55 → 47 (−8; TS2322 6 → 1, TS2345/TS2339/TS2349 → 0), services
139 → 127 (−12 cross-profile). Suite 9,836 → 9,855 (+19 local across 7 new test files, 0
regressions); 7 fix commits (5b589394 / 97263c21 / 6102e8ed / 9a7d2b35 / 20894a34 / f521582c /
ba0a4e5a). Bench: self 28.3 s (normal band).**
- **Fix 1 (5b589394, union-in-intersection kind-reduction; 55 → 54):** a property read on
  `Union & Interface` reduces the union constituent's members by `.kind` disjointness
  (typeGuardMemberDisjoint) against the sibling constituents before folding, so
  `(NamedEvaluation & BinaryExpression).left` resolves through the surviving
  `AssignmentExpression & { left: Identifier }` members to Identifier, not BinaryExpression's
  wide Expression (namedEvaluation.ts:434 TS2345). COMPANION: the intersection contribution
  fold dedupes by Type.id — a self-intersection `Expression & Expression` made downstream arg
  checks bail where the plain type correctly fires (pinned by a negative control).
- **Fix 2 (97263c21, destructured member from callee body, M3.4; 54 → 53):**
  `({ referencedName, name } = visitReferencedPropertyName(member.name))` — the RHS calls a
  NESTED un-annotated fn, so the round-460 overwrite narrowing kept the antecedent. New
  destructuredMemberNonNullishFromCalleeBody: every callee return must be an object literal
  carrying the member with a non-nullish value; identifier values resolve through the callee's
  OWN params + body-local const decls (the new bodyDecls map in retExprNonNullishForFlow —
  getTypeOfIdentifier would resolve the CALLER's same-named nullable binding during the walk).
  Cleared esDecorators.ts:1309 (the round-464 'two more mechanism layers' item — one layer
  sufficed).
- **Fix 3 (6102e8ed, intersection shared-member rule, M3.1; 53 → 51, TWO sites):**
  intersectionMergedContradictsTarget's first-decl-wins merge was wrong whenever a later
  constituent REFINES a member — tsc gives a multiply-declared member the INTERSECTION of the
  declared types, so contradiction now requires EVERY declaration to fail (any relating
  declaration proves the intersected member relates). Cleared parser.ts:9581 AND
  factory/utilities.ts:1688 — **the round-461 'whole-program-only' verdict was WRONG: the pair
  reproduces single-file once the interface itself declares the shared member** (`node as
  AssignmentExpression<EqualsToken> & { readonly left: GeneratedIdentifier }` vs its own
  annotation; bisected by deleting AssignmentExpression's own `left`).
- **Fix 4 (9a7d2b35, fn-AWARE generic member instantiation, M3.1; 51 → 50):** instantiateType
  no-ops fn-shaped objects (the documented gotcha), so a generic member's fn-typed RETURN kept
  the raw outer T: `select(index): ((node: T) => T) | undefined` as
  OrdinalParentheizerRuleSelector<TypeNode> failed its conforming initializer (emitter.ts:1277;
  Diagnostic-init stack probe located the emission, member-shape bisection V3–V6 isolated the
  method-return + fn-property-nested-fn shapes). New instantiateTypeFnAware /
  instantiateSignatureFnAware (fresh instances, never mutation, sig TPs preserved) wired at
  resolveGenericPropertyTypeWorker's MethodDeclaration RETURN and
  substituteOuterTypeArgsInSignature's return/params.
- **Fix 5 (20894a34, concise-arrow captured narrowing, M3.4 × B464; 50 → 49):**
  getTypeOfArrowFunction's concise-body branch now consults
  getNarrowedTypeForReferenceFollowLoopEntry for a bare-reference nullable-union body —
  the B464 flow-into-closures continuation (reassigned-after gates) proves
  `packageJsonInfoCache ??= create…; return { getPackageJsonInfoCache: () =>
  packageJsonInfoCache }` non-nullish. Accepted ONLY as an EXACT nullish strip (member-id set
  equality with narrowByExcludingNullUndefined). Cleared moduleNameResolver.ts:1300.
- **Fix 6 (f521582c, Exclude-filter kind disjointness, M3.4; 49 → 48):** probe-confirmed the
  round-457 `asserts node is Exclude<T, U>` branch REACHED es2020.ts flattenChain with
  uType=NonNullChain resolved and union=4 — but kept=0: enum-member kinds resolve `any`, so the
  lenient relation related EVERY brand-intersection member (PropertyAccessChain =
  PropertyAccessExpression & { _optionalChainBrand }) to NonNullChain. The filter now keeps a
  member whose kind keys are provably disjoint (the round-423 narrowByCallPredicate lesson).
  Cleared es2020.ts:91 — the round-457 'whole-program resolution scale' diagnosis was actually
  the lenient relation, visible only where member types resolve fully.
- **Fix 7 (ba0a4e5a, IIFE-const fn calls, M3.4; 48 → 47):**
  callRhsHasNonNullishReturnAnnotation's VariableDeclaration branch gains a CallExpression case
  — iifeReturnedFunctionTriple resolves `const f = (() => { return g; function g(…): R {…}
  })()` (no-arg IIFE, block body, first top-level return naming a same-block nested fn or an
  inline fn) to the returned function's annotation, so `uiComparerCaseSensitive ??=
  createUIStringComparer(uiLocale); return uiComparerCaseSensitive(a, b)` proves the callee
  non-nullish. Cleared core.ts:2135 TS2349 (the round-463 'hard' item — the classifier angle
  made it bounded).
- **NEXT: compiler @ 47 = ONE real FP + 46 env-legit (TS2591×43 require / TS2304×2 global /
  TS2584 console).** builder.ts:2390 is the genuine Blocker #2 chain: arrayToMap's K := Path
  needs the makeKey callback's return through nested `toFilePath` → element access on
  `filePaths = buildInfo.fileNames?.map(toPathInBuildInfoDirectory)` → Array.map return
  inference from a NAMED-fn callback whose name is AMBIGUOUS (2 decls, both un-annotated
  returning `toPath(…)` = Path) — needs map-callback return inference + all-candidates-agree
  ambiguous-fn resolution. Then the services profile burn-down (127).**


**Round 464 (2026-07-10) — bounded FP burn-down: SIX fixes. Dashboard: compiler 61 → 55 (−6;
TS2322 10 → 6, TS2362 → 0, TS7006 → 0), services 154 → 139 (−15 cross-profile). Suite 9,817 →
9,836 (+19 local across 7 new test files, 0 regressions); 6 fix commits (ecf6290d / 44e1b2ff /
6fcf7cda / f1e48c81 / 2d843068 / 6fe6406d).**
- **Fix 1 (ecf6290d, barrel-enum member non-nullish; 61 → 59):** `receiverResolvesToRealEnum`
  only followed the general resolveAlias (can't follow ESM-`.js` + `export *` barrels), so
  `flags = flags || NodeBuilderFlags.None` (tsc checker.ts withContext) never proved `flags`
  non-nullish — TS2362 at checker.ts:6639 AND TS2322 at 6640 (the objlit shorthand member) with
  ONE root cause. Falls back to the flow-only `resolveImportedEnumSymbol` (round 411, memoized),
  mirroring `resolveEnumSymbolForDiscriminant`. Reproduced minimally (3-file barrel repro).
- **Fix 2 (44e1b2ff, generic inference from flow-narrowed args, M3.4 × M3.1; 59 → 58):**
  `tryInferSingleTypeParamFromArgs` bound T from `getTypeOfExpression`'s DECLARED type — a
  switch-narrowed union arg bound T to the full union (`const name = cloneNode(node)` under
  `case SyntaxKind.Identifier:` → return FP'd `EntityName ⊄ SerializedEntityName`,
  typeSerializer.ts:603). Gated: union-declared bare Identifier/PropertyAccess args, narrowed
  type must be a non-never/any refinement still relating to the declared type — inference only
  gets MORE precise. Shared-inference change → full corpus + strictly-removals listAll gates.
- **Fix 3 (6fcf7cda, TS7006 destructured-source context, M3.2; 58 → 57):** a THIRD parallel
  implicit-any scope stack (`implicitAnyScopeDestructures`: element name → source expr +
  property name, push/pop ONLY via push/popImplicitAnyScope) lets an assignment target rooted
  at a destructured local (`const { parseConfigFileHost } = state; parseConfigFileHost.on… =
  d => …`, tsbuildPublic.ts:594) resolve its contextual type from the source's declared member.
  Top-level elements only (nested/rest unrecorded — bounded).
- **Fix 4 (f1e48c81, flow non-nullish cluster; 57 → 56):** FOUR coupled pieces clear
  getTypeAtFlowNode's `return type;` (checker.ts:29132, `let type: FlowType | undefined`
  assigned in every branch): (a) ternary RHS non-nullish iff BOTH arms; (b)
  typeNodeDefinitelyNonNullish's globals fallback accepts an UNAMBIGUOUS barrel type ALIAS and
  recurses its body — gate counts TypeAliasDeclarations, NEVER list size (the merged
  declarations list is polluted with importers' ImportSpecifiers); (c) an un-annotated param
  DEFAULTED from an annotated PRECEDING sibling (`initialType = declaredType`) types as the
  sibling's annotation (checkFunctionBody + populateParameterLocalTypes); (d) an UN-ANNOTATED
  callee proves non-nullish from a bounded body-return scan (bare `return;`/opaque statements
  fail; ternary identifier leaves resolve via the callee's own params → getTypeOfIdentifier →
  the new program-wide `uniqueNestedVarDeclByName` — tsc's `convertAutoToAny` whose leaves are
  `var anyType = createIntrinsicType(…)` closure vars). Diagnosed by an XPROBE cascade
  (Diagnostic-init stack probe → checkReturnAssignability entry → narrowByAssignmentRhs
  per-branch); **TOOLING TRAP (CLAUDE.md gotcha added): the bench files are CRLF, so python
  text-mode offsets understate checker positions by one per line — 3 probe iterations lost to
  ranges that silently missed.**
- **Fix 5 (2d843068, barrel checkDefined returns + PA-RHS narrowing; dashboard-neutral,
  repro-pinned):** `Debug.checkDefined(x)` through the barrel resolves its RETURN as the arg
  minus nullish (`tryBarrelCheckDefinedReturn`, shape-gated like the round-461 flow classifier
  — no general barrel resolution in the type path, the round-409 TS2315-flood hazard);
  `narrowByAssignmentRhs` gains a PropertyAccess-RHS arm mirroring the round-463 Identifier arm
  (`end = importLiteral.end`). program.ts:1220's chain source improves `{ file: any; … }` →
  `{ file: SourceFile; … }`; the site itself still FPs because the pos/end objlit-VALUE
  narrowing needs a contextual type and the round-462 return-path objlit context only accepts a
  union target's SOLE non-nullish object member — this target has TWO
  (`ReferenceFileLocation | SyntheticReferenceFileLocation`). Next layer scoped.
- **Deferred with findings:** esDecorators.ts:1309 needs destructured-member-from-un-annotated-
  nested-callee resolution PLUS method-calls-on-destructured-receivers (`factory` is itself
  destructured from `context`) — two more mechanism layers on the fix-3/fix-4 machinery;
  builder.ts:2390 needs callback-RETURN inference through an un-annotated same-file fn
  (Blocker #2); namedEvaluation.ts:434 needs `(Union & Interface).left` kind-discriminant
  member reduction (tsc reduces union members whose `kind` conflicts with the interface's).
- **Fix 6 (destructured-const element typing + multi-member-union objlit context; 56 → 55):**
  `recordDestructuredConstElementTypes` types a destructured const's TOP-LEVEL elements from the
  source's declared members (`const { kind, index } = ref` — B83.5-unbound, so `index` was anyType
  and `file.referencedFiles[index]` resolved any, defeating the pos/end destructuring-overwrite
  narrowing; the probe cascade showed the ELEMENT-ACCESS INDEX, not the receiver, was the leak).
  Conservative: absent names only, non-union/any sources, no defaults/rest/nested; **FUNCTION-shaped
  member types stay unrecorded** — recording them rode the fn-type relation's M3 gaps
  (tsbuildPublic.ts:767 unmasked on the first cut; the detector must resolveStructuredTypeMembers
  and check union constituents, the fn types arrive lazily-membered / `| undefined`-wrapped).
  PLUS `selectUnionMemberByObjLitKeys`: a MULTI-object-member union return target contributes the
  SINGLE constituent whose members cover every objlit property name as the contextual type.
  program.ts:1220 CLEARED (56 → 55, strictly removals by listAll diff).
- **NEXT (compiler @ 55, ~9 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):**
  emitter.ts:1277 (select-return);
  moduleNameResolver.ts:1300 (getPackageJsonInfoCache `| undefined` member);
  esDecorators.ts:1309; namedEvaluation.ts:434 (kind-reduction); parser.ts:9581 /
  factory/utilities.ts:1688 (cast-instantiation identity, M3); builder.ts:2390 (Blocker #2);
  es2020.ts:91 + core.ts:2135 (known-hard).**

**Round 463 (2026-07-10) — bounded FP burn-down: NINE fixes — minimal-repro fixes, one measured
UN-GATE of a historical skip, and two new flow-narrowing mechanisms. Dashboard: compiler 72 → 61
(−11; TS2322 15 → 10, TS2339 2 → 1, TS2345 2 → 1, TS2353/TS7053/TS18048/TS2589 all → 0). Suite
9,794 → 9,817 (+23 local across 9 new test files, 0 regressions); 9 fix commits (6075703a / 610bf2a0 / fd3a7003 / 78001791 / 9a55bd1a / 8e6ec34c / 83f27992 / cac642c6 / 181a850a).**
- **Fix 1 (6075703a, never array element):** `checkArrayLiteralElementExcessProps`'
  primitive-element-vs-object branch skipped Null/Undefined/Void/Any but not NEVER — `[undefined!]`
  (never per B282) vs `Expression[]` FP'd TS2322 (taggedTemplate.ts:50). TypeFlags.Never added.
- **Fix 2 (610bf2a0, fn-type alias instantiation UN-GATE; with fix 1: 72 → 68):** the historical
  B50.1 skip (FunctionType/ConstructorType/call-sig-only-TypeLiteral alias bodies never substitute)
  left `TransformerFactory<SourceFile | Bundle>`'s body `T` an UNBOUND TypeParam that FAILS the
  relation against a concrete conforming source (`return transformModule` ×3, transformer.ts:83/98/
  100 — whole-program-only: small programs resolve the unbound T to errorType and pass vacuously;
  found by probing the resolved target's call-sig structure). The skip's historical FP hazard is
  covered by the round-431 foreign-TP source gates. Measured: corpus green + strictly-removals.
  `isFunctionTypeAliasBody` deleted; the CLAUDE.md gotcha prescribing the gate REWRITTEN.
- **Fix 3 (fd3a7003, Identifier body-local vs merged-globals shadow, call-types pass; 68 → 67):**
  `shadowCallTypesDeclList`'s Identifier branch only overrode an INHERITED currentLocalTypes entry —
  a for-of loop-header `const patternText` colliding with core.ts's exported `function patternText`
  resolved through globals in arg position → TS2345 `'() => string'` vs `'string'`
  (moduleSpecifiers.ts:929, the long-standing scouted item). Global-colliding Identifier locals now
  register into currentParamBindingNames (anyType; a concrete recording still wins).
- **Fix 4 (78001791, mapped-type unknowable key domain; 67 → 66):** `getTypeFromMappedType`'s
  union-constraint enumeration used mapNotNull, silently DROPPING non-string-literal constituents —
  `[K in keyof T & CompilerOptionKeys | StrictOptionName]` with T un-inferred enumerated only the
  strict keys and the PARTIAL domain manufactured excess TS2353 (utilities.ts:9042). Any
  non-enumerable constituent now bails the whole mapped type to anyType.
- **Fix 5 (9a55bd1a, annotated-decl skip in nearestPrecedingObjectLiteralDecl; 66 → 65):** the B290
  element-access receiver-shape recovery matched ANNOTATED decls, keying the noImplicitAny index
  checks off the initializer literal — `const result: ExtendsResult = { options: {} }` made
  `result[propertyName]` FP TS7053 even though the access-site `result` was the nested fn's
  annotated param (commandLineParser.ts:3466). Annotated decls skip to the typed path.
- **Fix 6 (8e6ec34c, NULLISH-MIRROR overload pairs in flow narrowing, M3.4; 65 → 64):** the
  round-424 documented overload-cluster deferral closed for `f(x: T, …): R;` / `f(x: T | undefined,
  …): R | undefined;` (+ impl — tsc instantiateType): tsc picks the FIRST applicable overload, and
  between mirror sigs the ONLY applicability dimension is arg nullishness, so a provably
  non-nullish arg selects the first (non-nullish) sig (`nullishMirrorOverloadNonNullish`;
  buildNestedFunctionMap retains full clusters). PAIRED: typeNodeDefinitelyNonNullish falls back to
  merged globals for a barrel-imported Alias, interface/class/enum ONLY (TypeAlias would re-open
  the round-443 conflation trap). Cleared checker.ts:21170 TS18048; nullable-arg and
  nullish-FIRST-order negative controls pinned.
- **Fix 7 (83f27992, deferred-position recursive UNION alias cycle-break; 64 → 63):**
  `WrappedExpression<T> = OuterExpression & { expression: WrappedExpression<T> } | T` depth-bailed →
  spurious TS2589 (utilities.ts:5553). The B57 lazy cycle-break extends to UNION bodies whose every
  member is deferred-position (`unionBodyIsDeferredPositionOnly`) — an INDEXED-ACCESS/MAPPED member
  FORCES evaluation, and the first ungated cut regressed exactly that corpus pin
  (recursivelyExpandingUnionNoStackoverflow expects TS2589+TS2615).
- **Fix 8 (cac642c6, TS 5.5 INFERRED TYPE PREDICATES, bounded slice; 63 → 62):**
  `filter(getEmitHelpers(sf), helper => !helper.scoped)` — a single-expression boolean-literal-
  discriminant arrow in a guard-overload callback position infers `helper is UnscopedEmitHelper`
  (`inferDiscriminantArrowPredicateTarget` in tryInferPredicateOverloadReturn); without it the
  non-guard overload returned the full union and `.importName` FP'd TS2339
  (factory/utilities.ts:713, the round-462 scouted finding). TRAP: LiteralType `true`/`false`
  literals parse as Identifier nodes (KEYWORD_IDENTIFIERS), not TrueKeyword/FalseKeyword kinds —
  the first cut silently bailed on every member.
- **Fix 9 (identifier-RHS assignment narrowing, M3.4; 62 → 61):** a plain `=` with a bare-Identifier
  RHS filters the antecedent union by the RHS's resolved type (`narrowUnionByRhsAssignment`, member
  identity preserved for the round-459 subset gates) — `result = node` narrows `VisitResult<T> =
  T | readonly Node[]` to T so the later `result = [staticBlock, result]` array element reads the
  member (esDecorators.ts:1485). NON-UNION RHS only: a union RHS routed through the LENIENT member
  relation (enum-member kinds resolve `any`, round-423) filtered a JsxCallLike union to its
  property-poorest member — caught by the AliasedConditionAndUnionPredicateTest reassignment
  control on the first full-suite run, gated before commit.
- **NEXT (compiler @ 61, ~15 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):** the
  big-objlit M3 family (moduleNameResolver.ts:1300 / emitter.ts:1277 / checker.ts:6640);
  program.ts:1220 (checkDefined RETURN TYPE resolution); esDecorators.ts:1309 (destructured
  referencedName union); typeSerializer.ts:603 (switch-case narrowing must feed generic clone-chain
  inference); parser.ts:9581 / factory/utilities.ts:1688 (cast-instantiation identity, M3);
  builder.ts:2390 `string → __String` branding; checker.ts:29132 evolving-let FlowType;
  namedEvaluation.ts:434; es2020.ts:91 (Exclude at whole-program scale); core.ts:2135 (scouted:
  needs IIFE-const return inference — the `??=` RHS `createUIStringComparer(uiLocale)` calls a
  `const = (() => {…})()` whose type we cannot resolve, hard); tsbuildPublic.ts:594 TS7006
  (cross-barrel); checker.ts:6639 TS2362 (barrel enum, known-hard).**

**Round 462 (2026-07-10) — probe-driven burn-down: SIX fixes; FOUR needed instrumented
whole-program probes (minimal repros clean or misleading — XPROBE prints + a stack-trace probe on
the Diagnostic constructor). Dashboard: compiler 79 → 72 (−7; TS2322 19 → 15, TS2345 4 → 2,
TS2339 3 → 2), services 165 → 154 (−11 cross-profile). Suite 9,782 → 9,794 (+12 local across 5
new test files, 0 regressions); 6 fix commits (a4c8b186 / 9810df0f / f6e2456f / e2e99396 /
99181256).**
- **Fix 1 (a4c8b186, call-arg narrow-DOWN gate; compiler 79 → 77):** the round-428b/429c/438
  branch required the narrowed type to refine the DECLARED type (`n <: declared`) — but tsc's
  getNarrowedType(assumeTrue) legitimately narrows to a guard target OUTSIDE the declared
  hierarchy (isPropertyNameLiteral's PropertyNameLiteral union contains JsxNamespacedName, which
  does not extend Expression), so a genuine narrow was rejected → the wide Expression FP'd TS2345
  ×2 (utilities.ts:4066 isSameEntityName). An XPROBE on the real bench project showed
  refines=false / matchesParam=true — the shape does NOT reproduce minimally (the conservative
  union-param gate skips small repros). The gate now also accepts a narrowed type that makes the
  PARAM relation pass (the standard monotone rule).
- **Fix 2 (9810df0f, objlit property-value contextual narrow-DOWN; 77 → 76):** `return { class:
  node.parent.parent, … }` after `isClassLike(node.parent.parent)` kept the wide Node — the
  round-438 nullish-strip gate alone rejects narrow-DOWNs (utilities.ts:7458). Two pieces:
  checkReturnAssignability provides the objlit CONTEXT (a union target contributes its sole
  non-nullish object member — mirrors the call-arg B83.4g), and getTypeOfObjectLiteral's value
  narrowing extends to PropertyAccess initializers when the contextual property accepts the
  narrow and rejects the raw. **PERF LANDMINE (measured + fixed pre-commit): an unconditional
  getNarrowedTypeForReference on every objlit PropertyAccess value program-wide cost +352%
  self-compile time (28.9 s → 130.5 s) — the walk now runs only when the raw type FAILS the
  cached contextual-property relation (31.4 s, normal band).**
- **Fix 3 (f6e2456f, prefix-path guard tail substitution; 76 → 75):** the round-424 prefix-path
  branch (guard on a RECEIVER prefix of the walked path) made only the drop-nullish claim; tsc
  re-types `x.y` from the NARROWED x — `isComputedPropertyName(parent)` makes `parent.parent`
  ComputedPropertyName's declared `parent: Declaration` (utilities.ts:5085). Now substitutes the
  resolved tail, with TWO measured gates: NON-UNION tails only (an ungated cut was net +1 — a
  precise union tail like `BindingName` fires where the wide type stayed silent when downstream
  discriminant narrowing can't complete: checker.ts:45139/binder.ts:2498), and a non-nullish
  union tail KEEPS the round-424 nullish-strip (dropping it regressed transformers/utilities.ts:643).
  Verified strictly-removal by listAll diff.
- **Fix 4 (e2e99396, ambiguous block locals in the PROPERTY-ACCESS pass; 75 → 74):** the round-460
  rule (≥2 block-scoped decls of one name in ONE body → anyType) ran only in the assignability
  pass; the property-access pass has its own three body-entry sites. moduleNameResolver.ts's
  loadModuleFromTargetExportOrImport has `const result = nodeModuleNameResolverWorker(...)`
  (ResolvedModuleWithFailedLookupLocations) in one block and the recursive SearchResult `const
  result` in another — first-decl-wins made `result.value` (2823) FP TS2339 on the wrong block's
  type. Root-caused by TWO probes (a getTypeOfIdentifier-origin print falsified the
  inherited-entry hypothesis; the B136-recording print found the same-body sibling block).
- **Fix 5 (f6e2456f follow-up in the same commit as fix 3's gates):** see fix 3's measured gates.
- **Fix 6 (99181256, constraint-shape foreign-TP gate; 74 → 72, −2):** typeContainsForeignTypeParam
  matched own TPs by NAME, so a CALLEE's un-inferred TP sharing the name was claimed own —
  `getUpToDateStatusWorker<T extends BuilderProgram>` returning forEachKey's UNCONSTRAINED
  `T | undefined` never hit the foreign-TP bail → bare-T TS2322 (tsbuildPublic.ts:1778, pinned by
  a temporary stack-trace probe on the Diagnostic constructor after the return-path gate looked
  correct). A same-named TP with a mismatched constraint SHAPE (own constrained vs instance
  unconstrained, or vice versa) is now foreign; scoped to names present in currentTypeParamDecls
  so signature-own TP names keep the pure name test (round-431e pins). ALSO cleared
  transformer.ts:271 (the memoize objlit member — same collision, previously scouted
  "whole-program-only").
- **Probe finding (checkConditionalReturnBranches instrumentation):** utilities.ts:5085's
  emission does NOT come from the ternary-arm branch (its narrow related fine both times) — the
  ternary-arm TS2322 anchor at 5085:49 was the DIRECT `return parent.parent` shape, mis-read from
  the earlier scout. Lesson repeated: verify the emitting SITE with a probe before theorizing.
- **Scouted with findings:** factory/utilities.ts:713 (`helper.importName`) needs TS 5.5 INFERRED
  TYPE PREDICATES — `filter(helpers, helper => !helper.scoped)` selects the guard overload only
  because tsc infers `helper is UnscopedEmitHelper` from the boolean-literal discriminant arrow;
  a bounded slice would infer predicates for single-expression discriminant arrows in
  filter-family calls.
- **NEXT (compiler @ 72, ~26 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):**
  the big-objlit M3 family remnant (moduleNameResolver.ts:1300 / emitter.ts:1277 /
  checker.ts:6640); TransformerFactory ×3 (transformer.ts:83/98/100); inferred type predicates
  (factory/utilities.ts:713); program.ts:1220 (checkDefined RETURN TYPE resolution);
  es2020.ts:91 Exclude-narrowing at whole-program scale; typeSerializer.ts:603 (switch-narrowed
  generic clone chain); `string → __String` branding (builder.ts:2390); utilities.ts:9042
  mapped-type-unknowable-keys excess bail; checker.ts:29132 evolving-let FlowType;
  checker.ts:21170 TS18048 restrictiveInstantiation self-write; esDecorators ×2 /
  taggedTemplate:50 / namedEvaluation:434 / parser.ts:9581 / factory/utilities.ts:1688
  (transformer-family intersections + cast-instantiation identity, M3).**

**Round 461 (2026-07-10) — bounded FP burn-down: FIVE fixes, all GENERAL checker/flow correctness
that reproduce minimally. Dashboard: compiler 88 → 79 (−9; TS2322 25 → 19, TS2345 6 → 4),
services 178 → 165 (−13 cross-profile, measured after all five). Suite 9,762 → 9,782 (+20 local
across 5 new test files, 0 regressions); 5 fix commits (c863d04b / 72c3abc5 / b20a695e /
f8cbd1e6 / 1f3726f2).**
- **Fix 1 (c863d04b, TypeParam-source constraint bail, M3.1; compiler 88 → 86):** the round-456
  reverted broad rule landed as the prescribed per-site EMISSION bail — `bareTpConstraintRelatesTo`
  (constraint-chain walk, cycle-guarded for `T extends T`, unconstrained → false) suppresses the
  assignment-path TS2322 when a bare-TypeParam source's constraint chain relates to the target
  (mirrors round 442's arg-check bail; no shared-engine change → no overload-selection
  perturbation). Cleared transformers/classFields.ts:1800 (`currentClassContainer = node`, `node: T
  extends ClassLikeDeclaration`) + utilities.ts:12338 (`clone = getSynthesizedDeepCloneWorker(...)`
  returning bare `T extends Node` — the callee's T shares the enclosing fn's TP name, so the
  foreign-TP gate deliberately passes it). tsbuildPublic.ts:1778 (return path) does NOT reproduce
  minimally — stays.
- **Fix 2 (72c3abc5, element-access reference paths, M3.4; 86 → 85):** an element access with a
  LITERAL index is a narrowable reference (tsc isMatchingReference) — `getReferencePath` gains an
  ElementAccessExpression arm serializing `recv[N]` segments, so
  `!!node.declarationList.declarations[0].initializer && getInternalEmitFlags(<same path>)`
  narrows (transformers/es2015.ts:2730). Companions: exact-path ElementAccess target arms in
  flowAssignmentMightNarrow + narrowByAssignmentRhs; `flowPathRoot` (splits on '[' too) for the
  root-name comparisons; `pathPrefixOf` boundary tests in argMentionsReferencePath. Also cleaned
  two pre-existing round-460 warnings the recompile surfaced.
- **Fix 3 (b20a695e, namespace-member nested-fn shadow; 85 → 82, −3):** `shadowNestedFunctionNames`'
  collision gate also consults the enclosing inference-namespace exports (new
  `nameInEnclosingNamespaceExports`, symbol-presence only) — parser.ts:1865's
  `syntaxCursor = { currentNode }` shorthand referenced the body-nested
  `currentNode(position): Node` but resolved to namespace Parser's
  `currentNode(parsingContext, pos?): Node | undefined` via lookupInInferenceNamespace (which runs
  BEFORE globals in getTypeOfIdentifier). Generalized beyond the pinned site (−3).
- **Fix 4 (f8cbd1e6, checkDefined-shape non-nullish, M3.4; 82 → 81):** a call whose resolved
  callee returns a bare OWN-TP `T` with some param annotated `T | undefined` (/`| null`) proves
  non-nullish (tsc Debug.checkDefined — inference binds T to the arg's non-nullish part); wired
  into callRhsHasNonNullishReturnAnnotation, gated to no-explicit-type-args (a control pins
  `checkDefined<X | undefined>` staying nullable). Cleared program.ts:4041 (`sourceFile =
  Debug.checkDefined(commandLine.options.configFile)` at an if/else join; the callee resolves
  through the barrel via the flow-only resolvers).
- **Fix 5 (1f3726f2, var-decl + tuple-slot narrowing consumers, M3.4; 81 → 79):**
  (a) checkVarDeclAssignability gets the assignment/return paths' relation-passes-gated narrowing
  for named-object/union/intersection targets (parser.ts:6245 — `let expression: PropertyAccess |
  Identifier | ThisExpression = initialExpression` after a negative isJsxNamespacedName guard);
  (b) `elementAssignableToSlot` (round-446 array→variadic-tuple AST check) narrows a reference
  element — builder.ts:518's `isString(old) ? [old] : old[0]` vs `EmitSignature = string |
  [signature: string]`.
- **Scouted, whole-program-only (deferred with findings, all minimal repros CLEAN):**
  transformer.ts:271's `getEmitHelperFactory: memoize(...)` member (the foreign-TP gate walks
  anonymous-object members and a faithful single-file memoize repro passes — the real gap is
  whole-program); utilities.ts:4066 isPropertyNameLiteral &&-guard (faithful single-file repro
  passes — the PropertyNameLiteral alias union's whole-program resolution is the suspect);
  parser.ts:9581 displays the SAME union on both sides (`& { expression: SerializedEntityName }` vs
  the inline union it aliases — alias-display/conflation flavor); factory/utilities.ts:1688 casts
  to `AssignmentExpression<EqualsToken>` but the cast resolves `PunctuationToken<any>` (generic
  instantiation identity, M3).
- **NEXT (compiler @ 79, ~33 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):** the
  whole-program probe batch (instrument narrowByCallPredicate on the real bench project for
  utilities.ts:4066/5085, es2020.ts:91, factory/utilities.ts:713); tsbuildPublic.ts:1778 return-path
  T (extend the fix-1 bail to the return emission after probing why the narrowed `T | undefined`
  reaches it); the big-objlit M3 family (transformer.ts:271 / moduleNameResolver.ts:1300 /
  emitter.ts:1277 / checker.ts:6640); program.ts:1220 (needs the checkDefined call's RETURN TYPE
  resolved — the non-nullish flag alone doesn't type `file`, so `file.referencedFiles[index]`'s
  members stay unresolvable for the round-460 destructuring narrowing); typeSerializer.ts:603
  (switch-narrowed generic clone chain, M3.1); `string → __String` branding (builder.ts:2390);
  utilities.ts:9042 TS2353 (mapped type keyed `keyof T & …` with un-inferred T — the excess check
  needs an unknowable-key-domain bail).**

**Round 460 (2026-07-09/10) — bounded FP burn-down: TEN fixes (9 dashboard wins + 1
repro-pinned capability), all GENERAL checker/flow correctness. Dashboard: compiler 103 → 88
(−15; TS2322 33 → 25, TS2345 11 → 6, TS2454 1 → 0, TS2363 1 → 0), services 194 → 178 (−16
cross-profile, measured at fix 8). Suite 9,731 → 9,762 (+31 local across 10 new test files,
0 regressions); 9 fix commits (9973bf9d / 4c00e5f1 / fcec70f7 / c35e24cb / 739d16ef /
f2d67ef9 / 2c03d8f9 / a0765dd6 / f1849a01).**
- **Fix 1 (9973bf9d, block-scoping-ambiguous locals; compiler 103 → 100):** the round-459 NEXT
  item with root cause pre-diagnosed. A name with ≥2 block-scoped (`let`/`const`) declarations in
  ONE function body can only refer to DIFFERENT blocks' bindings, but the flat first-decl-wins
  `currentLocalTypes` made every later read resolve to whichever decl the walk saw first —
  program.ts findSourceFileWorker's three `const file` made `return file;` read the if-block's
  `SourceFile | false | undefined` → FP TS2322. `applyAmbiguousBlockScopedLocals` registers such
  names anyType at body entry (`ambiguousBlockLocalNames`; loop-header decls excluded);
  `checkVarDeclAssignability` skips re-recording them in all three maps. BONUS: also cleared
  checker.ts:11321 (round-458's "Blocker-#3 name→DeclarationName conflation" was actually this)
  and utilitiesPublic.ts:991 — the family was mis-attributed to whole-program conflation.
- **Fix 2 (4c00e5f1, exhaustive-switch never family; 100 → 97):** all three `Debug.assertNever` /
  `assertType<never>` sites had DIFFERENT root causes: (a) programDiagnostics.ts:346 — a NON-union
  switch subject (single interface after `isReferencedFile(reason)` guard) whose `.kind` is an
  enum-member-union ALIAS fully covered by cases → `narrowBySwitchClause`'s default branch now
  exhausts it to never; (b) declarations/diagnostics.ts:702 — `Debug.type<T>(node)` where T is a
  FUNCTION-BODY-local alias (B83.5-unbound) → new `uniqueNestedTypeAliasByName` program-wide map
  (type-alias sibling of buildNestedFunctionMap); (c) utilities.ts:12082 — HasInferredType contains
  `Exclude<VariableLikeDeclaration, JsxAttribute | EnumMember>` (no union distribution → member
  resolved anyType, poisoning the union) → new `resolveAssertTargetTypeNode` resolves member-wise
  and evaluates lib Exclude (keep members NOT assignable to U). All flow-only.
- **Fix 3 (fcec70f7, flatten double-array anchor, M3.1; 97 → 95):** `flatten<T>(array: T[][] |
  readonly (T | readonly T[] | undefined)[])` — a union param with a `tp[][]` member is now an
  inference ANCHOR (gate (k)); an array-of-array arg binds T from its inner element (enum elements
  accepted). Cleared utilities.ts:9972/9978; the explicit-type-arg control proved the relation
  passes once T binds.
- **Fix 4 (c35e24cb, TS2454 captured reads; 95 → 94):** `isAssignedAtFlow` returned false at the
  closure's FlowStart, so an expression-bodied arrow's captured read never saw the enclosing
  function's assignments (checker.ts:14106 `filter(…, info => !findIndexInfo(indexInfos, …))` after
  an if/else assigning indexInfos on both branches). It now follows `FlowStart.outerFlow` (closures
  only, localNames-gated; OR-semantics → suppression-only; never-assigned captured reads still fire).
- **Fix 5 (739d16ef, nested destructured global shadow; 94 → 93):** `registerNestedGlobalShadowDecls`
  now registers binding-PATTERN element names — checker.ts:37376's nested `const { start, length } =
  getDiagnosticSpanForCallNode(…)` shadowing core.ts's `length` fn (`diagnostic.length = length`
  FP'd fn-vs-number).
- **Fix 6 (f2d67ef9, arithmetic-pass module-var-leak arm; 93 → 92):** the round-442 family —
  `arithOperandType` bails a bare-Identifier operand whose name ∈ moduleFileLocalVarNames (own-file
  + currentLocalTypes gates): program.ts's module `const indent = "    "` poisoned parser.ts's
  body-local `let indent` → `margin - indent` FP'd TS2363 (parser.ts:8974).
- **Fix 7 (2c03d8f9, destructuring-assignment overwrite narrowing — dashboard-neutral capability,
  repro-pinned):** `({ pos, end } = refs(i))` now OVERWRITES pattern names in the narrowing walk:
  Flow.kt threads the ENCLOSING BinaryExpression through the literal target arms (was the leaf
  Identifier — no RHS visible), `flowAssignmentTargetsName` walks pattern LHSes via
  `destructuringAssignTargetHasName` (nested default-value patterns REQUIRED — the
  sourceMapValidationDestructuringForOf… corpus pin caught the first flat-only cut), and
  `narrowByAssignmentRhs` strips nullish from the declared type when the destructured member
  resolves nullish-free. Companion: a DIRECT enum-typed switch subject with all members covered
  exhausts to never in the default clause. The real program.ts:1220 stays FP — its `file` local is
  `Debug.checkDefined(program.getSourceFileByPath(…))` (cross-barrel generic inference), so the RHS
  type doesn't resolve; mechanism verified by repro + 5 local tests.
- **Fix 8 (2c03d8f9 same commit, function-local-alias predicate target; 92 → 91):**
  `narrowByCallPredicate` falls back to `resolveAssertTargetTypeNode` — utilities.ts
  resolveNameHelper's nested `isSelfReferenceLocation(node): node is SelfReferenceLocation` (the
  alias is declared INSIDE the function) never narrowed → `lastSelfReferenceLocation = location`
  FP'd TS2322 (utilities.ts:11856).
- **Fix 9 (a0765dd6, chained assignment RHS; 91 → 90):** `location = node = value` evaluates to
  the ULTIMATE RHS value, but the round-410/438 assignment-path narrowing gate only accepted a
  bare Identifier/PropertyAccess RHS — transformers/destructuring.ts:113's chain inside
  `if (isDestructuringAssignment(value))` kept the declared `Expression | undefined` → FP vs
  TextRange. The gate now unwraps the `=` right-spine before narrowing.
- **Fix 10 (f1849a01, this-param signature alignment; 90 → 88):** `getTypeOfFunction` zipped the
  this-DROPPED paramSymbols against the this-KEPT decl.parameters — every param type shifted by
  one for a `this`-param function (core.ts's `multiMapAdd<K, V>(this: MultiMap<K, V>, key: K,
  value: V)` built `(key: MultiMap<K, V>, value: K)` → FP TS2322 ×2 at `map.add = multiMapAdd`).
  A this-param function now resolves each symbol's type from its own valueDeclaration, and `this`
  no longer counts toward minArgumentCount. LOAD-BEARING: functions WITHOUT a this param KEEP the
  legacy positional zip — a leading BINDING-PATTERN param is also dropped from paramSymbols
  (round-446 gotcha), and the shift keeps sig.parameters[i] carrying decl.parameters[i]'s type,
  which the call-site arg alignment relies on (an unconditional valueDeclaration-based resolution
  regressed 19 sites: moduleSpecifiers getModuleSpecifierPreferences et al). Aligning properly
  needs placeholder symbols for pattern params — a queued follow-up with real blast radius.
- **Scouted, whole-program-only (deferred with findings):** tsbuildPublic.ts:594 TS7006
  (`parseConfigFileHost.onUnRecoverableConfigFileDiagnostic = d => …` — the PropertyAccess
  assignment-target context EXISTS and a faithful generic destructured-receiver repro compiles
  clean; the real gap is cross-barrel); moduleSpecifiers.ts:929 (round-459 finding stands).
- **NEXT (compiler @ 88, ~42 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console):**
  program.ts:1220 needs `Debug.checkDefined(<call>)` cross-barrel generic-return inference to feed
  the (now-landed) destructuring narrowing; transformers/transformers/es2015.ts:2730 needs element-access segments (`declarations[0].initializer`) in the
  narrowing reference-path machinery (getReferencePath has no `[0]` support — core change, assess
  blast radius); utilities.ts:4066 isPropertyNameLiteral &&-guard (probe); TransformerFactory
  whole-program probe (round-457 finding); `string → __String` branding (builderState/builder);
  the binding-pattern placeholder-symbol signature alignment (unblocks the round-446 family properly); the big objlit-vs-interface returns
  (parser.ts:1865, emitter.ts:1277, transformer.ts:271, moduleNameResolver.ts:1300 — likely one
  M3 family); utilities.ts:12338/tsbuildPublic.ts:1778 = the round-456 REVERTED TypeParam-constraint
  relation rule family (needs the per-site emission bail variant).

**Round 459 (2026-07-09) — bounded FP burn-down: EIGHT fixes, all GENERAL checker correctness
that reproduce minimally. Dashboard: compiler 116 → 103 (−13; TS2322 43 → 33, TS2339 5 → 3,
TS2349 2 → 0), services 210 → 194 (−16 measured at fix 7; TS2322 94 → 81, TS2345 29 → 25,
TS2339 13 → 11). Suite 9,706 → 9,731 (+25 local across 8 new test files, 0 regressions); 8 fix
commits (b68d6487 / e6b6d990 / 435d7220 / bef95f36 / 4139b47a / 6c630b54 / d209b138 / 47cb985f).**
- **Fix 1 (b68d6487, array-literal→tuple ASSIGNMENT; compiler −3):** the round-458 NEXT item.
  New `currentLocalDeclTypeNodes` map records body-local annotation NODES alongside
  `currentLocalTypes` (first-decl-wins; saved/restored in checkFunctionBody) so
  `checkAssignmentExpression` can feed the DECLARED tuple node to the round-446
  `arrayLiteralSatisfiesTupleTarget` helper for an ArrayLiteralExpression RHS (body locals are
  B83.5-unbound and the resolved Type collapses the rest slot). Cleared checker.ts:22621/22927
  (`lastSkippedInfo = [source, target]`, `relatedInfo = [info]`), esnextAnd2015.ts:248. The map is
  general infra — any new body-local AST-shape check should read it.
- **Fix 2 (e6b6d990, enum-member-literal overload selection; compiler −1):** an enum-member param
  (`kind: SyntaxKind.NamedImports`) resolves to anyType, so an enum-member ARG matched EVERY
  overload and the FIRST won — `parseNamedImportsOrExports(SyntaxKind.NamedExports)` (parser.ts:8717)
  returned the NamedImports overload's type → FP TS2322. `resolveCallOverload` now compares the param
  annotation's canonical enum-member key set (round-411 key space) against the arg's key AST-side.
- **Fix 3 (435d7220, array-element flow narrowing + Array.isArray; compiler −2):**
  (a) `getTypeOfArrayLiteral` narrows a bare-Identifier ELEMENT via getNarrowedTypeForReference —
  the array sibling of round 438's objlit-value narrowing, same nullish-strip gate — clearing
  builderState.ts:388 (`if (!sourceFile) return emptyArray; … return [sourceFile]`);
  (b) `applyConditionNarrowing` gains `Array.isArray(x)` (covers BOTH the round-458 AST path and the
  flow FlowCondition path; the embedded lib deliberately has no ArrayConstructor so the predicate
  can't resolve via declarations) — clearing utilities.ts:8757 (chainDiagnosticMessages'
  `details === undefined || Array.isArray(details) ? details : [details]`). The isArray.ts corpus pin
  (a USE-BEFORE-ASSIGNED file-level var keeps its DECLARED type in tsc — the TS2454 rule; its
  baseline expects the ELSE branch un-narrowed) is honored by skipping the narrowing for a bare
  identifier resolving to a file-level var with no initializer — the first gate run caught this
  as the session's only (fixed-before-commit) corpus regression.
- **Fix 4 (bef95f36, objLitValueNullishStrip union-member-subset):** the gate on flow-narrowed
  objlit property values / array elements also accepts a nullish-FREE strict MEMBER-SUBSET narrow of
  a union source — monotone-safe (if the raw union relates, any member subset relates → can only
  suppress). Cleared commandLineParser.ts:2269 (readConfigFile's `isString(x) ? … : { config: {},
  error: x }` — `string | Diagnostic` → `Diagnostic` is not a nullish strip). The round-438
  shadowing hazard stays excluded (a nullish narrowed type never qualifies).
- **Fix 5 (4139b47a, numeric-literal vs enum-domain discriminant):** `narrowByDiscriminantProperty`
  compares a NUMERIC-literal RHS against an enum-typed discriminant's VALUE domain (enumValues via
  canonicalEnumSymbol): `flowType.flags === 0` drops `Type` (TypeFlags has no 0-valued member),
  keeping IncompleteType — tsc's getTypeFromFlowType/isIncomplete (checker.ts:28630 TS2339 +
  the 29132 TS2322 cascade). An enum WITH a matching value does not narrow (negative control).
- **Fix 6 (d209b138, tuple-union element access; TS2339):** `value[1]` on a receiver NARROWED to
  `[FileId] | [FileId, BuilderFileEmit]` (builder.ts:2242 toBuilderFileEmit) — tsc resolves union
  element access per-member with undefined for out-of-bounds members. The element-access
  missing-member path bails for a numeric key in bounds for SOME member of an all-tuples union;
  the bail tests the NARROWED receiver (the DECLARED type still carries the guard-removed FileId).
- **Fix 7 (6c630b54, nullable-array default-init push on PROPERTY targets; TS2349 2 → 0):**
  `(label.antecedent || (label.antecedent = [])).push(antecedent)` (binder.ts:1375; core.ts:2135
  cleared too). Root cause found by tracing: inside the `||` right operand the read of `x.p` is
  flow-narrowed to the falsy `undefined`, so the round-408 empty-`[]` contextual rule never saw the
  array type. Two coupled rules: combineBinaryTypes' Equals arm recomputes the RAW property type for
  a PropertyAccess target with a fresh empty-array RHS (an assignment TARGET types against its
  DECLARED type — M1.9); contextualAssignmentRhsType accepts a UNION target whose sole non-nullish
  member is Array-family. The local wrong-arg control pins that the receiver collapses to the
  PRECISE `string[]` (an `any[]` would accept the bad arg). This closes the round-452
  "inline-property-receiver" deferral.
- **Fix 8 (47cb985f, truthy ASSIGNMENT condition; compiler 104 → 103):** `while (child =
  tryParse(() => …))` — the assignment evaluates to the assigned value, so the body sees `child`
  truthy; tsc's parser JSDoc loops declare `let child: Tag | … | false` and the `false` member
  otherwise survived every discriminant filter (a primitive literal member has no `.kind` — the
  enum-key filter conservatively keeps it) → FP TS2322 at parser.ts:9638. `applyConditionNarrowing`
  gains a `SyntaxKind.Equals` arm (LHS is the walked reference → narrowByTruthiness; the false
  branch falsy-narrows symmetrically), covering both the flow FlowCondition and the round-458 AST
  paths.
- **NEXT (compiler @ 103, ~57 real excl. TS2591×43 + TS2304×2 `global` + TS2584 console env-legit):**
  the TransformerFactory<T> whole-program conflation probe (transformer.ts:83/98/100 — round-457
  finding: NOT a generic-alias gap, needs an instrumented probe); program.ts:3705 — ROOT CAUSE FOUND
  (no probe needed): findSourceFileWorker has TWO block-scoped `const file` decls
  (`filesByName.get(path)` → `SourceFile | false | undefined`, declared FIRST inside an if-block,
  vs the outer `host.getSourceFile(…)`) colliding in the block-UNAWARE first-decl-wins
  `currentLocalTypes`, so `return file;` reads the wrong binding's type — the B83.5/block-scoping
  family, needs block-aware local types or a scoped bail; program.ts:1220 (switch-exhaustive +
  destructuring-assignment definite assignment); the `assertNever` never-param family ×3 (round 441
  deep); utilities.ts:9978/9972 flatten (M3.1 generic inference); `string → __String` branding
  (whole-program); moduleSpecifiers.ts:929 for-of element mis-inference (whole-program-only —
  minimal repro clean).

**Round 458 (2026-07-09) — logical/ternary operand narrowing + try/finally flow fix +
fresh-objlit interface-target retry + a P0 single-file-CLI crash fix. FOUR fixes, all GENERAL
checker/flow/driver correctness that reproduce minimally. Dashboard: compiler 121 → 116 (−5),
services 217 → 210 (−7), server 407 → 402 (−5), harness 620 → 615 (−5), tsc-cli 118, jsTyping 115,
deprecatedCompat 118, typingsInstallerCore 115. Suite 9,690 → 9,706 (+16 local across 4 test files,
0 regressions); 4 fix commits (a8571636 / 4eb423e7 / b2143c9e / eec58c6a).**
- **Fix 4 (eec58c6a, P0 — `xtsc foo.ts` crashed on ANY bare source-file argument, even
  `const x = 1;`):** discovered mid-session via a probe; per the P0 mandate, fixed before wrapping.
  The bare `.ts` was passed straight into TsConfigLoader (`resolveConfigPath` treats any
  non-directory as a tsconfig), parsed as JSON → garbage config → a corrupt lib binderResult whose
  `sourceFile.text` mismatched its statement positions → StringIndexOutOfBounds in
  `checkMultiBaseInStatement` (`source.substring` at a lib position ~22995 indexed into the 295-char
  user file). Two narrower guards were tried first (a statement-span bounds check; a merged-decl
  in-source filter) — NEITHER stopped the crash (the position mismatch is in the binderResult
  itself, not the cross-file merge), pointing to the ROOT fix: `ProjectCompiler.build` now detects a
  non-`.json` existing-FILE argument and builds it as a single-file program with default options
  (like `tsc foo.ts`): the file is the sole root in a synthesized `LoadedTsConfig(files = [it])`,
  its relative imports are still walked into the program, and type checking works normally.
  Directory/tsconfig arguments untouched (compiler profile confirmed unchanged at 116). +3 local
  (SingleFileBuildTest: clean compile, real-error reporting, relative-import walk).
- **Fix 1 (a8571636, logical/ternary operand narrowing):** `combineBinaryTypes` narrows the
  `||`/`&&` RIGHT operand, and the `ConditionalExpression` typing narrows the TRUE/FALSE branches,
  by the governing condition via a new `narrowOperandByCondition` → `applyConditionNarrowing` (pure
  AST, no flow graph). tsc's binder places a FlowCondition on the operand (`A && B` under "A true";
  `A || B` and a ternary FALSE branch under "A false"; a ternary TRUE branch under "A true"); our
  `getTypeOfExpression` was flow-unaware for bare references, so `insertComment === undefined ||
  insertComment` typed `boolean | undefined` (→ FP TS2322 against `boolean`; services.ts commenting
  logic) and `cond ? ref : …` kept the un-narrowed branch. Fires only for a pure
  Identifier/PropertyAccess operand (`getReferencePath`); FP-safe (type unchanged when the condition
  doesn't mention the operand path). Cleared services.ts:2891/2976, rename.ts:192, checker.ts:51198
  (accessor-`kind` discriminant → the object-literal `{firstAccessor, …, setAccessor, getAccessor}`
  now matches `AllAccessorDeclarations`). Compiler net 0: the 51198 win is offset by exposing
  checker.ts:11321, a Blocker-#3 `name`→DeclarationName whole-program conflation the cleaner
  (undefined-stripped) narrowing surfaces (the relation now checks the wrongly-typed reference the
  coarse union masked). CAVEAT: RETURN-position ternaries/logicals go through the SEPARATE
  `checkConditionalReturnBranches` (per-branch), untouched by this.
- **Fix 2 (4eb423e7, try/finally flow):** `bindTryStatement` made the finally block's entry flow the
  join of ONLY the try/catch NORMAL completion, so a try that always `return`s/throws left that
  completion unreachable → every read in the finally washed to `never` → spurious TS2339 on cleanup
  code. tsc's `checkGrammarRegularExpressionLiteral` resets `scanner` in its finally after a `try {…
  return; }`; our `scanner` washed to `never` → FP TS2339 on `scanner.setText`/`setOnError`
  (checker.ts:33288/33289). Fix: a two-label structure — the finally block's entry joins the pre-try
  flow (exceptional early exit) + the normal completion, while the flow AFTER the whole statement
  stays the normal completion (so the finally's exceptional-inclusive entry can't widen away the
  try/catch narrowing for the following statements). compiler 121 → 119 (−2), no services regression.
  CLAUDE.md gotcha added (do NOT collapse the two-label structure).
- **Fix 3 (b2143c9e, fresh-objlit interface-target retry):** the round-448 return-path
  fresh-object-literal literal retry (recover a returned object literal's un-widened literal property
  per PropertyAssignment via `freshObjLitRange` so `propertiesRelatedTo` relates it) was gated to
  `Type.Union` targets only. A fresh object literal returned against an INTERFACE (or anonymous
  object) with a literal(-union) member hit the same widening — `return { kind: "ambient", … }` vs
  `interface ModuleSpecifierResult { kind: "node_modules" | … | "ambient"; … }` widened `kind` to
  `string` → FP TS2322. Broadened the gate to `Type.Interface`/`Type.Object`. Suppression-only (the
  retry returns only when the relation then PASSES within the fresh scope → a wrong literal / missing
  property still fires; negative controls pinned). Cleared moduleSpecifiers.ts:399/507,
  sourcemap.ts:323 (RawSourceMap). compiler 119 → 116, services 214 → 210. NOTE the var-decl and
  assignment fresh-objlit paths already apply `withFreshObjLitSource` for all targets — only the
  return path carried the union-only gate.
- **INVESTIGATED, deferred (array-literal→tuple ASSIGNMENT, checker.ts:22621/22927,
  esnextAnd2015.ts:248):** `lastSkippedInfo = [source, target]` → `[Type, Type]`; `relatedInfo =
  [info]` → `[X, ...X[]]`. The `arrayLiteralSatisfiesTupleTarget` helper (round 446, return path)
  handles these shapes, but the targets are function-body-local `let`s (B83.5-unbound), so the
  assignment walk has NO declaration-node map to feed the helper the tuple type NODE (it knows the
  local only by resolved type, which collapses the tuple rest). Needs local-declaration-node plumbing
  into the assignment walk (a `currentLocalDeclTypeNodes` map populated alongside `currentLocalTypes`)
  — a broader infra change that also unblocks other AST-based checks for function-body locals.
- **NEXT (compiler @ 116):** the array-literal→tuple assignment plumbing above; the
  `TransformerFactory<T>` generic-type-alias-of-fn relation (M3.3, whole-program — minimal repro
  clean); the `assertNever(reason)` exhaustive-switch enum-union residual (programDiagnostics.ts,
  round 441 — the guarded `ReferencedFile` union must resolve with readable enum `.kind` members);
  `string → __String` branding (whole-program).

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
