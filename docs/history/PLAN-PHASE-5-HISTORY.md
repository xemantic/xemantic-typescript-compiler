**Round 449 (2026-07-09) — object-literal spread member-symbol corruption (Blocker #3-adjacent):
ONE root-cause fix clears the `readonly ApplicableRefactorInfo[]` TS2322 ×9 family deferred across
rounds 446-448 as "a type-param-scope pollution needing a whole-program probe" — PLUS the convertExport
`ExportInfo | RefactorErrorInfo` cascade that round 448's NEXT pointer mislabelled a "deep M3
union-of-named-AST relation gap". Dashboard: compiler 185 → 183 (−2), services 321 → 310 (−11), server
529 → 518 (−11), harness 746 → 735 (−11). Suite 9,581 corpus green / 0 fail / 3 skip + 3 local (0
regressions); 1 fix commit (6df8166f); services by-code diff strictly TS2322 −11, zero regressions.**
- **Baseline @ HEAD (round 448): services 321.** The `readonly ApplicableRefactorInfo[]` ×9 was the
  single biggest bounded TS2322 family but had been deferred 3× as "does not reproduce minimally / needs
  a whole-program probe". Built the probe: the chain showed the TARGET member `ApplicableRefactorInfo.actions`
  displaying as `U[]` (a stray unbound type parameter) instead of `RefactorActionInfo[]`.
- **Instrumentation (the decisive tool — the root cause is invisible without it):** (1) probing
  `getTypeFromTypeReference("RefactorActionInfo")` showed it ALWAYS resolves cleanly (scope=null) → the
  `U` does NOT come from resolving the member's type node; (2) probing `getPropertyTypeForRelation`
  showed the SAME symbol (id 47351) returning `U[]` 28× and `RefactorActionInfo[]` 13× — a non-deterministic
  cache; (3) probing the cached VALUE showed two distinct `Type.Reference` objects (element = interface vs
  element = `TypeParam U`) → `symbolTypes[47351]` was being OVERWRITTEN; (4) a `symbolTypes.put`-interceptor
  stack trace pointed at `getTypeOfObjectLiteral`'s `existing != null` override write, reached via
  `tryInferSingleTypeParamFromArgs` (generic inference); (5) dumping the members table showed
  `propNames=[SpreadAssignment, actions]` — the object literal is `{ ...info, actions: [...] }`, and the
  SPREAD had merged the interface member symbol 47351 into `members` BY REFERENCE.
- **ROOT CAUSE:** `getTypeOfObjectLiteral`'s B426 spread merge did `members[pn] = psym` — sharing the spread
  SOURCE's member SYMBOLS. When a later explicit member of the same name overrode a spread member, the
  `existing != null` branch wrote `symbolTypes[existing.id] = <override type>` — mutating the spread SOURCE's
  member type cache GLOBALLY. In the refactor return sites (`return [{ ...info, actions: [...] }]`) the
  override array was typed under a generic-inference context as `U[]`, so `ApplicableRefactorInfo.actions`'s
  cached type became `U[]` and every subsequent relation against `ApplicableRefactorInfo` FP-fired TS2322.
  (The getter/setter override branches similarly MUTATE `existing.declarations` — same latent hazard.)
- **FIX (Checker.kt, spread branch of getTypeOfObjectLiteral):** COPY each guaranteed spread member into a
  FRESH literal-owned `Symbol` (carrying `psym`'s resolved type via `getTypeOfSymbol` + declarations +
  valueDeclaration + parent, so reads/optionality/positions are byte-unchanged), so an override touches the
  literal's own symbol — never the shared source member. Core hot/shared path, so gated on the full corpus
  suite (9,581/0/3, byte-clean) + a `symbolTypes`-write A/B before/after.
- **Removed by position (11 TS2322, 0 added):** 7 `ApplicableRefactorInfo[]` (extractType,
  convertParamsToDestructuredObject, moveToNewFile ×2, convertOverloadListToSingleSignature,
  inferFunctionReturnType ×2) + convertExport.ts:85/89 (the round-448 "deep M3" mislabel — same bug) +
  checker.ts:9392 + moduleNameResolver.ts:2365 (compiler-side cascade, hence compiler −2).
- **3 local tests (SpreadOverrideMemberCorruptionTest):** the refactor-shaped return, the corruption
  invariant (`{ ...base, actions: [{ name, extra }] }` must not corrupt `Info.actions` for a later
  relation — VERIFIED to FAIL on the reverted buggy version), and a negative control (a genuinely-wrong
  override element still fires). NOTE the refactor-shaped minimal test PASSES even on the buggy version
  (the `U[]` corruption needs the whole-program generic-inference context — the services profile is its
  proof); test 2 is the load-bearing pin.
- **NEXT (services @ 310):** TS7006×8 (inferFromUsage `Priority[]` — the B83.5 nested-interface-resolution
  blocker; land WITH the reverted round-443 array-element contextual-typing enabler), TS2454×5 (definite-
  assignment `while(true)`-break flow gap: `resultingToken`/`indexInfos`/`variable`/`previousRange`),
  residual deep-M3 TS2322/TS2345 relation fragments (each ≤3).

**Round 448 (2026-07-08) — TS2322/TS2774 burn-down: `this.optionalProp = undefined` write +
discriminated-union object-literal return + module-var-leak local alias + destructured-shadow TS2774:
FOUR bounded fixes, all suppression-only / FP-safe. Compiler UNCHANGED (185 — the families live in
services-side files: services.ts, completions.ts, signatureHelp.ts, jsDoc.ts), services 339 → 321 (−18),
server 555 → 529 (−26), harness 772 → 746 (−26). Suite 9,577 corpus green + 10 local (0 regressions);
commits 1b65da39 / 764afbd0 / da2f421b / d34b48d2; every services diff strictly by-position removals
via the `--listAll` `comm` loop.**
- **Baseline @ HEAD (round 447): services 339.** Bucketed the `--listAll` TS2322×188: the biggest CLEAN
  bounded families were the services.ts `undefined`-to-optional constructor field resets (×10) and the
  completions.ts discriminated-union return (×5). The `readonly ApplicableRefactorInfo[]` ×9 stayed the
  round-447 deferred stray-`U[]` type-param-scope pollution (needs a whole-program probe).
- **Fix 1 (1b65da39, `this.optionalProp = undefined`; TS2322 −10 services; services 339 → 329, server
  555 → 537, harness 772 → 754):** the string-based `this.prop = value` write path
  (`checkAssignmentExpression` ~88528, taken when `varTypes["this.$prop"]` is set) is OPTIONALITY-BLIND —
  `varTypes` stores the bare type-NAME via `resolveSimpleTypeName`, dropping the `?`. So
  `this.parent = undefined` where `parent?: Symbol` (services.ts SymbolObject/NodeObject constructor field
  resets) FP-fired TS2322. An explicit `| undefined` in the declared type, or an array-typed optional,
  already passed the lenient string relation — which is why ONLY bare-interface/class-typed optionals FP'd.
  Added the `thisPropertyIsOptional(propName)` helper (consulting `currentClassForThis`'s OWN members) and
  a bail for a bare-`undefined` RHS to a non-eOPT optional field, mirroring the type-engine
  `checkPropertyAccessAssignment` undefined-optional bail at 89644. Negative controls (non-optional field,
  eOPT) keep firing.
- **Fix 2 (764afbd0, discriminated-union object-literal return; TS2322 −5; services 329 → 324, server 537
  → 532, harness 754 → 749):** `return { type: "cases" }` vs `... | { type: "cases"; } | { type: "none"; }
  | ...` (completions.ts getSymbolCompletionFromEntryId, 5 returns). `getTypeOfObjectLiteral` WIDENS the
  discriminant to its base primitive (source displays `{ type: string }`), so it matched no union member
  and the coarse return relation failed. The return path (`checkReturnAssignability`, before the coarse
  relation) now retries the union relation with `withFreshObjLitSource(expr)` (round 435) — propertiesRelatedTo
  recovers the un-widened literal from each PropertyAssignment per union member, so the object relates to its
  discriminated member. Suppression-only (only when the retry PASSES) → FP-safe: an object with a non-matching
  discriminant, or a matching discriminant with a wrong property TYPE, still falls through and fires
  (both negative controls pinned). Gated `targetType is Type.Union && canUseTypeEngine`.
- **Fix 3 (da2f421b, local aliased from a leaked module var; TS2322 −2; services 324 → 322, server 532 →
  530, harness 749 → 747):** the Blocker #3 module-var leak reaching through a local alias. `const invocation
  = parent` where navigationBar.ts's module-level `let parent: NavigationBarNode` leaked into globals (round
  442) and the destructured `const { parent } = node` in signatureHelp.ts is unbound (B83.5) → `invocation`
  inherited `NavigationBarNode`, poisoning the nested object-literal value `{ node: invocation }` in a returned
  ArgumentListInfo (the bare-Identifier moduleFileLocalVarNames bails can't reach a value nested inside an
  object literal). The un-annotated var-decl inference (`checkVarDeclAssignability` ~84486) now returns early
  (records nothing → the alias resolves as anyType) when the initializer is a bare leaked-module-var Identifier
  that is NOT this file's own binding AND NOT already in `currentLocalTypes` (a genuine same-named param
  `(parent: Node)` IS recorded → keeps its real type — firewall pinned). Cleared 2 of the 3 signatureHelp
  ArgumentListInfo FPs; the 3rd uses `node: parent` DIRECTLY in the object-literal value (no alias) — a
  getTypeOfObjectLiteral property-value bail would clear it but that is a hot/shared path, deferred.
- **Fix 4 (d34b48d2, destructured local shadows a same-file function; TS2774 −1; services 322 → 321,
  server 530 → 529, harness 747 → 746):** `const { hasReturn } = commentOwnerInfo` (jsDoc.ts
  getDocCommentTemplateAtPosition) shadows a same-file module-level `function hasReturn`, but the TS2774
  uncalled-function walker's shadow-collector (`collectUncalledTypedLocalsFromBody`) handled only a simple
  `const x` — it skipped binding patterns (`d.name as? Identifier ?: continue`). So `hasReturn` in
  `hasReturn ? … : …` resolved to the function → FP "always defined, did you mean to call it". Binding-pattern
  names are now registered as shadows via `collectBindingNames` (shadow-only, consistent with an untyped
  simple local); a genuine uncalled same-file function in a condition still fires (firewall pinned).
- **INVESTIGATED & DEFERRED:** (a) the `SourceFileLike` object-literal conflation (sourcemaps.ts/textChanges.ts
  ×2) — the base `interface SourceFileLike` LACKS `getLineAndCharacterOfPosition` (added by a `declare module`
  augmentation), so an AST-based satisfaction check against the raw InterfaceDeclaration reads it as EXCESS →
  can't verify FP-safely without merging augmentation members; fragile, skipped. (b) the convertExport
  `ExportInfo | RefactorErrorInfo | undefined` ×3 — reproduces CLEAN in a minimal `A || {objLit}` union return,
  so it is NOT a `||`-unwrap gap; the real FP is a deep M3 union-of-named-AST-interfaces relation
  (`exportNode: ExportToConvert` where `ExportToConvert` includes `IsInterface = InterfaceDeclaration` — big
  AST-node interfaces). (c) the `X | undefined` arg-vs-non-nullish-`Node`-param cases (es2015/convertParams/
  fixUnreferenceable) — element-access reference paths + big-AST-union `.kind`-discriminant filtering (M1.4),
  each a per-site narrowing gap.
- **NEXT (services @ 321, all deep/whole-program):** ApplicableRefactorInfo stray-`U[]` type-param-scope
  pollution ×9 (whole-program probe), the `InterfaceDeclaration`→`IsInterface` union-of-named-AST relation gap,
  array-of-union generic-inference (compact/slice → `readonly T[]`), big-AST-union discriminant filtering (M1.4).

**Round 447 (2026-07-08) — cross-file conflation emission-site bails (ARG + RETURN sides, Blocker #3)
+ nested-arrow inner-local shadowing: FIVE fixes across four commits (four suppression bails + one
general shadowing correctness fix). Compiler UNCHANGED (185 — services-side conflations/leaks/shadowing),
but they generalize uniformly: services 373 → 339 (−34), server 589 → 555 (−34), harness 806 → 772
(−34). Suite 9,558 → 9,571 (+13 local across 5 test files, 0 regressions); commits d4065ea6 /
72b441c7 / da8c64a9 / ebc83ea3; every services diff strictly by-position removals via the `--listAll`
`comm` loop.**
- **Baseline @ HEAD (round 446): services 373.** Bucketed the `--listAll`: the round-446 NEXT pointer's
  `ApplicableRefactorInfo` ×9 root-caused to a stray-`U[]` type-param-scope pollution that does NOT
  reproduce minimally (deferred — needs a whole-program probe); the clean bounded veins were the
  conflated-`Info` families the round-444/445 machinery already understands.
- **Fix 1 (d4065ea6, return object literal vs a conflated file-local TYPE-ALIAS union; TS2353 6 → 0;
  services 373 → 367):** the EXCESS-property complement of round 444's alias's-own-file member-access
  bail. In the file declaring `type X = A | B | …`, a `return { … }` excess-checked against `X`
  resolves — last-wins Interface+TypeAlias merge — to a SIBLING file's `interface X`
  (fixAddMissingMember.ts's `type Info = TypeLikeDeclarationInfo | EnumInfo | …` vs 12 sibling
  `interface Info`), FP'ing "'kind' does not exist in type 'Info'". `objectLiteralMatchesConflated-
  FileLocalTypeAlias` bails when THIS file declares `type X`, the target names the conflated `X`, and
  the object satisfies some alias-union member interface. **LANDMINE (cost me an instrumented CLI trace):
  the union member interfaces (`FunctionInfo`/`SignatureInfo`) are THEMSELVES conflated — `getProperties-
  OfType` returned polluted merged members (extra `selectedVariableDeclaration`/`newParameters`/… from
  sibling files), so 4 of 6 initially stayed FP'ing. The satisfaction check must read each member
  interface AST-side (`objectLiteralExactlySatisfiesFileLocalInterface` — no-excess + required-provided
  from the file's own InterfaceDeclaration), NOT via the resolved constituent members.**
- **Fix 2 (72b441c7, ARG-side conflated-alias PARAM; TS2345 `SourceFileLike` 8 → 0; services 367 → 359):**
  the arg complement of round 443's conflated-type-alias RECEIVER bail. A param typed as a leaked
  conflated `type X` (importTracker.ts's `type SourceFileLike` vs compiler/types.ts's `interface
  SourceFileLike`) resolves, in a NON-owning file, to the bogus alias union, so an object/class-instance
  arg satisfying the real interface FP'd. `paramTypeIsLeakedConflatedAlias` skips when the param displays
  as a conflated name and the file is not the alias's own.
- **Fix 3 (72b441c7, ARG-side leaked-var chain; TS2345 10 incl. 6 compiler-file leak-chains; services
  359 → 349):** the arg complement of round 444's receiver chain-walk. Round 442 bailed only a
  bare-Identifier leaked-var arg; the root can sit behind a property-access chain
  (`isCallExpression(parent.parent)` where `parent` leaks navigationBar.ts's `NavigationBarNode`).
  `argRootIsLeakedModuleVar` walks the arg to its root Identifier and bails on a leaked module var
  (a CALL in the chain breaks the walk).
- **Fix 4 (da8c64a9, return object literal vs a MULTI-member union with a conflated interface; TS2322
  FunctionInfo ×2; services 349 → 347):** round 445's `objectLiteralMatchesConflatedFileLocalInterface`
  used `.singleOrNull()` (single non-nullish member). Extended to `X | Err | undefined` unions — the
  object is assignable iff it EXACTLY satisfies some conflated file-local interface member (tsc's refactor
  `getInfo(): FunctionInfo | RefactorErrorInfo | undefined` shape).
- **Fix 5 (ebc83ea3, nested-arrow/fn-expr inner-local SHADOWING; TS2339 `ExportInfoMap` ×8; services
  347 → 339): a GENERAL correctness fix, not a suppression.** The round-444 NEXT pointer mislabelled this
  as a "destructuring-reassignment" gap; the real cause (found by reproducing it minimally — it DOES
  reproduce, unlike the leaks) is a nested-scope shadowing gap: `checkPropertyAccessInExpr`'s
  ArrowFunction / FunctionExpression branches recorded PARAM types on entering the body but never called
  `applyBodyLocalShadowing` for the body's own local declarations. completions.ts has an outer
  `const exportInfo: ExportInfoMap` and, in a nested `forEachEntry` callback, an inner
  `let exportInfo: SymbolExportInfo | FutureSymbolExportInfo` — reads of `exportInfo.exportKind`/`.symbol`/
  `.moduleFileName` resolved to the OUTER `ExportInfoMap` → FP TS2339. Both branches now call
  `applyBodyLocalShadowing(body.statements, paramNames)` for a Block body and save/restore
  `currentShadowedNames` (so the inner shadow does not leak outward). Suite-verified 0 corpus regressions
  from the broader walker change; 3 local tests pin the shadow + both firewall directions.
- **NEXT (services @ 339):** the `X | RefactorErrorInfo | undefined` object-vs-union RELATION gap for
  SINGLE-FILE interfaces (InliningInfo/OptionalChainInfo/ExportInfo — genuine M3, not conflation); the
  `ApplicableRefactorInfo` stray-`U[]` type-param-scope pollution ×9 (deferred, needs a whole-program
  probe — does not reproduce minimally); deep fragmented TS2322.

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

**Round 446 (2026-07-08) — array-literal→variadic-tuple-in-union returns + nested Array<X>-in-Array<Y>
covariant relation + destructured-param method arity: THREE bounded fixes. Dashboard: compiler 188 →
185 (−3), services 399 → 373 (−26), server 623 → 589 (−34), harness 868 → 806 (−62). Suite 9,540 →
9,558 (+18 local across 3 test files, 0 regressions); 3 fix commits (4a97bd52/28abf66a/03acbf0d);
diffed via `--listAll` as strictly by-position removals; bench rows logged for all four profiles.**
- **Baseline @ HEAD (round 445): services 399, compiler 188.** Bucketed the services `--listAll` and
  found the two families round 445's note flagged as NEXT: `DiagnosticOrDiagnosticAndArguments (|
  undefined)` ×16 (array-literal→variadic-tuple-in-union) and `readonly ApplicableRefactorInfo[]` ×15
  (nested-array element relation).
- **Fix 1 (4a97bd52, array-literal→variadic-tuple-in-union returns; services 399 → 379, compiler 188 →
  185, server 623 → 603, harness 868 → 848):** `return [Diagnostics.X, arg, …]` where the target is a
  variadic tuple, or a union/alias containing one (`DiagnosticOrDiagnosticAndArguments =
  DiagnosticMessage | [message: DiagnosticMessage, ...args: (string|number)[]]`). The relation engine
  SKIPS array→tuple and `getTupleType` COLLAPSES the rest slot, so both the engine and the string
  fallback ("array" display) FP'd. `arrayLiteralSatisfiesTupleTarget` AST-matches the array literal
  against the tuple found by `findVariadicTupleInTarget` (walk the target node: tuple / union /
  parenthesized / alias — **alias bodies resolved through the merged `globals`, since an imported
  alias's file-local symbol is only the ImportSpecifier**). `arrayLiteralMatchesVariadicTuple` parses
  the tuple into fixed-prefix / one rest / fixed-suffix and verifies each element against its slot
  (permissive on unresolvable slots → suppression-only). Hooked into `checkReturnAssignability` (direct)
  + `checkConditionalReturnBranches` (`?:`). Also covers FIXED tuples in a union — compiler's
  `specToDiagnostic(): [DiagnosticMessage, string] | undefined` + utilities.ts's `isDirectory ? [a,b] :
  undefined`. Services TS2322 216 → 196.
- **Fix 2 (28abf66a, nested `Array<X>`-in-`Array<Y>` covariant shortcut; services 379 → 373, server 603
  → 597, harness 848 → 842, compiler unchanged):** `{ actions: ActionInfo[] }[]` vs `readonly
  ApplicableRefactorInfo[]` FP'd TS2322. Root cause (found by minimal-repro bisection): the OUTER Array
  pushes `globalArrayType.id` on the comparison stack, so when the INNER `Array<ActionInfo>` is
  compared, the same target id counts as an `isReentry` → the covariant element shortcut is deferred to
  STRUCTURAL comparison of the two `Array` INTERFACES → `concat`'s contravariant element param
  `ConcatArray<T>` spuriously fails (no per-TP variance info). Array/ReadonlyArray are covariant, so they
  must ALWAYS use the element shortcut (`A ⊄ B ⇒ Array<A> ⊄ Array<B>`; termination via
  `relationComparisonStack` + `isDeeplyNested`). **Gated to TP-FREE target args** — an unbound-TP target
  (`flatten<T>(…: T[][])`) is an M3.1 inference gap the trivial structural pass currently MASKS; the
  first cut without the gate turned it into a +1 compiler FP (program.ts `flatten(allDiagnostics)`
  `readonly Diagnostic[] ⊄ T[]`). Core relation-engine change; corpus-clean (suite 9,548 → 9,553).
  Services TS2322 196 → 190.
- **Fix 3 (03acbf0d, destructured-param method arity; harness 842 → 806, server 597 → 589, compiler /
  services unchanged):** a method with a binding-pattern param (`goToRangeStart({ fileName, pos }:
  Range)`) FP'd TS2554 "Expected 1-0 arguments, but got 1." on a correctly-argumented property-access
  call. A destructured param produces NO Symbol, so the built Signature DROPS it — `sig.parameters` is
  empty (maxParams 0) while `minArgumentCount` stays 1, an impossible range that reads any 1-arg call as
  "too many". `checkTs2554ForPropertyAccessCall` recovers the true arity from the DECLARATION's parameter
  list via `paramInfo` (which counts binding-pattern params + handles rest/optional). Harness TS2554 37
  → 1 (the residual mapCode.ts:103 is pre-existing/unrelated); the harness is the test-infrastructure
  profile with many `fourslashImpl` destructured-param methods — the smaller profiles have few such
  property-access calls, hence compiler/services unchanged. Pure TS2554 removal, no other code touched.
- **NEXT (services @ 373):** the residual `ApplicableRefactorInfo[]` ×10 — a SEPARATE bug: their
  `actions` property resolves to a stray `U[]` (an unbound type parameter — no `U` in the file), a
  type-param-scope pollution / `getPropertyTypeForRelation` gap (my TP-gate correctly avoids making it an
  FP, so they stay as-is); the `X | RefactorErrorInfo | undefined` refactor-info family (object-literal /
  `&&`-object / `{error}`-union-source vs union-with-object-member — fragmented M3 object-vs-union
  relation, not conflation since the Info interfaces are single-file); the `DiagnosticOrDiagnosticAndArguments`
  residual is a TS2339 (`messageAndArgs[0]` indexing the union — a different code).

**Round 445 (2026-07-08) — TS2416/TS2430 property-override variance families + the cross-file
interface-merge conflation (Blocker #3) + spread-of-any object returns + the module-var-leak TS2322
extension: FIVE bounded fixes, all suppression-only. Dashboard: compiler 190 → 188 (−2), services
439 → 399 (−40), server 669 → 623 (−46), harness 919 → 868 (−51). Suite 9,523 → 9,540 (+17 local
across 5 test files, 0 regressions); 5 fix commits (76b7f2cc/a81d6300/c31f3577/e93fc974/6db81b97);
services diffed via `--listAll` as strictly by-position removals.**
- **Baseline @ HEAD (round 444): services 439, compiler 190.** Bucketed the services `--listAll`
  and found TS2416×11 + TS2430×3 (bounded override-variance families) and the `Info | undefined`
  TS2322×11 (the biggest single conflation family). The `readonly ApplicableRefactorInfo[]` ×15 and
  `DiagnosticOrDiagnosticAndArguments (| undefined)` ×16 stay deep-M3 (object-literal-array vs
  interface-array / array→tuple-union relation) — deferred.
- **Fix 1 (76b7f2cc, TS2416 override 11 → 0; compiler 190→189, services 439→428, server 669→656,
  harness 919→904): three class-property-override FP families from services.ts's NodeObject /
  TokenOrIdentifierObject / SourceFileObject implementors.** (A) An OPTIONAL base member `p?: T` has
  effective type `T | undefined`; a derived `p: T | undefined` override is legal — the raw base
  declared type dropped the optional `| undefined`, so widen it for the relation via
  `widenOptionalTargetPropType` (source-nullish gated → a non-nullish override still compares against
  the bare base). (B) A CONSTRAINED-type-parameter override (`kind: TKind` where `TKind extends
  SyntaxKind`, base `kind: SyntaxKind`) is valid via the constraint — per-site constraint bail (no
  general TypeParam-source relation rule). (C) tsc compares METHOD signatures with BIVARIANT params
  (`getWidth(sf?: SourceFile)` vs base `getWidth(sf?: SourceFileLike)`) — per-site
  `methodSignaturesBivariantlyRelated` retry (adds a defaulted `bivariantParams` flag to
  `signatureRelatedTo`, no threading elsewhere).
- **Fix 2 (a81d6300, TS2430 interface-extends 3 → 0; services 428→425): two FP families.** (A) The
  optional-base widen applied to the interface-extends check (`ValidParameterDeclaration extends
  ParameterDeclaration { modifiers: undefined }` — `undefined` assignable to the optional base's
  `T | undefined`). (B) A derived METHOD implementing a base function-typed DATA property
  (`EmitHost.getCanonicalFileName(fileName): string` implementing
  `SourceFileMayBeEmittedHost.getCanonicalFileName: GetCanonicalFileName`) was compared as the
  method's RETURN type (`string`) vs the base property's full function type — `getMemberNameAndType`
  returns a method's return type. Skip the simple property comparison when the derived member is a
  method.
- **Fix 3 (c31f3577, the interface-merge conflation; compiler 189→188, services 425→409 −16, server
  656→637, harness 904→884): a name X declared as `interface X` in ≥2 DISTINCT MODULE files merges
  via `mergeSymbolTable` into one polluted `globals[X]`, even though each module's interface is
  module-scoped.** tsc's codefixes each declare a private `interface Info`, so `getInfo(): Info |
  undefined` returning `{ importNode, name, moduleSpecifier }` (matching the FILE-LOCAL Info) looked
  "missing properties" against the merged union. Built `conflatedInterfaceFiles` (name → module files
  declaring `interface X`, for X in ≥2 files); `checkReturnAssignability` bails a returned object
  literal whose target is (the sole non-nullish member of) such a conflated X when THIS file declares
  its own `interface X` AND the object satisfies the file-local X (checked AST-side — the merged
  symbol's `declarations` list is polluted). Runs AFTER the per-property drills (genuine inner-key
  mismatch still fires), BEFORE the coarse missing-property/relation paths. Conservative for
  heritage-bearing interfaces / spread object literals. `Info | undefined` TS2322 11 → 1 (the residual
  fixExpectedComma.ts `{ node }` doesn't satisfy its file-local Info).
- **Fix 4 (e93fc974, spread-of-any object returns; services 409→404 −5, server 637→628, harness 884→873):
  a returned `{ ...anyExpr, ... }`.** tsc types an object literal that spreads an `any`/unresolved value as
  `any` (the spread poisons the whole object), so it cannot be "missing" required target properties.
  `getFileAndTextSpanFromNode` (no return annotation) returns an object literal → our
  `inferReturnTypeFromBody` has no object-literal branch → the call resolves to `any` → the spread
  `...getFileAndTextSpanFromNode(node)` looked to provide nothing → findAllReferences.ts's 5 returned
  objects FP'd "missing sourceFile, textSpan". `checkReturnAssignability` bails when the returned object
  literal has an any/error-typed spread (after the per-property drills, so a genuine explicit-prop mismatch
  still fires). Root fix is `inferReturnTypeFromBody`'s object-literal branch (documented suite-wide blast
  radius — deferred); this suppression is FP-safe (spread-of-any is genuinely `any` in tsc).
- **Fix 5 (6db81b97, module-var-leak TS2322 extension; services 404→399 −5, server 628→623, harness
  873→868): a `return <leakedVar>` / `<ident> = <leakedVar>`.** Round 442's `moduleFileLocalVarNames`
  bail (a top-level `let`/`var`/`const` in a MODULE file leaks into `globals` and shadows every OTHER
  file's same-named block/destructured local, unbound per B83.5) covered TS2339/TS2345. A block/
  destructured `parent` (whose initializer our checker can't infer locally — a destructuring or `&&`)
  leaks to navigationBar.ts's `let parent: NavigationBarNode`, so `return parent` (inferFromUsage.ts) /
  `lastParent = parent` (checker.ts) FP'd TS2322. `checkReturnAssignability` bails a returned
  bare-identifier leaked var; `checkAssignmentExpression` bails an `<ident> = <leaked ident>` (gated to
  a simple Identifier target). Both skip UNLESS it IS this file's own top-level binding. Compiler
  unchanged (navigationBar.ts is not in the compiler-only program). Local test confirms the destructured
  leak FPs without the fix (both `return parent` and `lastParent = parent`) and is clean with it.
- **NEXT (services @ 399, all deeper):** the union-of-2-interfaces conflation (`ExportInfo |
  RefactorErrorInfo | undefined` ×3 — the single-interface gate needs a union-member extension AND the
  `||`-nested object literal path), the two deep-M3 relation families `readonly ApplicableRefactorInfo[]`
  ×15 (object-literal-array vs interface-array with union/`.concat` element types) /
  `DiagnosticOrDiagnosticAndArguments` ×16 (array→tuple-union relation, plus a duplicate-chain-line
  display bug + an `'array'` fallback display). Extend the conflation / spread bails to var-decl/argument
  positions only when a non-return FP surfaces (none observed this round).


**Round 444 (2026-07-08) — cross-file heritage / `this`-guard receiver narrowing / alias-own-file
conflation / module-var-leak property chain (Blocker #3): FOUR bounded fixes, all suppression-only.
Compiler profile UNCHANGED (190), but they GENERALIZE strongly across the big profiles: services
498 → 439 (−59), server 733 → 669 (−64), harness 989 → 919 (−70). Suite 9,512 → 9,523 (+11 local
across 3 test files, 0 regressions); 4 fix commits (19e282f0/59868e67/796d263f/bd0d8eba); services
diffed via `--listAll` as strictly by-position removals (heritage 22 removed / 1 unmasked;
this-predicate 17/0; conflation 12/0; NavNode-chain 9/0). Bench rows recorded.**
- **Baseline @ HEAD (round 443): services 498, compiler 190.** The compiler profile is mined out for
  clean bounded veins; bucketed the SERVICES `--listAll` TS2339×85: `Type`×20 / `Info`×12 /
  `RefactorContext`×9 / `NavigationBarNode`×9 / `ExportInfoMap`×8 / `CodeFixContextBase`×8 / `Symbol`×5.
- **Fix 1 (19e282f0, −21 services, TS2339): namespace-import-aliased heritage base.** An interface
  whose `extends` base is `NS.Base` where `NS` is a MODULE namespace-import alias
  (`RefactorContext`/`CodeFixContextBase extends textChanges.TextChangesContext`, with
  `import * as textChanges` / a `_namespaces` `export * as` barrel) did not inherit the base's members.
  `resolveAlias` does NOT resolve an `import * as NS` / `export * as NS` namespace alias to a module with
  `.exports` (the alias's declaration is the NamespaceImport node, which none of resolveAlias's branches
  handle), so `resolveHeritageBaseSymbol`'s exports lookup returned null → base = errorType → inherited
  `.host` FP'd TS2339 ×17. Fix: `getTypeFromBaseTypeExpression` falls back to the merged-global
  LAST-SEGMENT name (`globals[baseExpr.name.text]`) — exactly what `getTypeFromTypeReference` does for
  the same qualified shape in ANNOTATION position (which is why a direct `ctx: textChanges.TextChangesContext`
  annotation resolved while the heritage base did not). By-position diff 22 removed / 1 unmasked
  (pasteEdits.ts:111 — a pre-existing `originalProgram!` NonNull-strip gap in an object-literal value,
  surfaced because CodeFixContextBase now resolves its base; needs exact nested-flow context, does not
  reproduce in isolation → left as residual).
- **Fix 2 (59868e67, −17 services, TS2339 — the `.types`-on-`Type` bucket 20 → 2): a `this is X` guard
  METHOD narrows the call RECEIVER.** The tsc `Type`/`Symbol`/`Node` public-API guards
  (`isUnion(): this is UnionType`, `isIntersection()`, `isLiteral()`, …, added to the interfaces by a
  `declare module` augmentation) narrow the method-call receiver, not an argument. `narrowByCallPredicate`
  bailed twice: (1) the `this` subject of a `this is X` predicate parses as a **ThisType** node (not an
  Identifier), so `predicateParamName` extraction returned null; (2) even with the name, the narrowed
  reference is the receiver, so the arg-path fast-path / paramIdx logic never matched. Fix: recognise a
  ThisType subject as `"this"`, compute the method-call receiver path up front (participating in the P0
  fast-path), and narrow the receiver via the existing single-type/union logic. **FP-safe gate (caught by
  the corpus suite): a `this is X` method guard narrows only a NON-UNION receiver** — tsc narrows a
  union-receiver method-guard only if EVERY constituent has a matching predicate (typePredicatesInUnion3:
  `Type1 | Type2` where `Type2.predicate(): boolean` is not a guard), and resolveFlowCalleeDecl found only
  one member's method, so a union bails (suppression-only → a bail is a harmless false-negative).
- **Fix 3 (796d263f, −12 services, TS2339): the alias's-own-file complement of round 443.** In the file
  that DECLARES `type Info = TypeLikeDeclarationInfo | EnumInfo | …` (fixAddMissingMember.ts), the receiver
  `info` resolves — via the merged last-wins pick (Interface+TypeAlias don't merge) — to a SIBLING codefix
  file's unrelated `interface Info` instead of the local union, so `info.kind`/`info.parentDeclaration`/
  `info.token` (union members reachable after a `.kind` discriminant narrowing our conflated receiver can't
  model) FP'd. `checkMemberAccessMissing` bails when the receiver's conflated name is a `type X` in THIS
  file AND the property exists on SOME constituent of the file-local union. **The union is resolved from the
  TypeAliasDeclaration's BODY node directly (`getTypeFromTypeNode`), NOT via `getDeclaredTypeOfSymbol` on the
  file-local symbol — that symbol's `declarations` list is polluted by `mergeSymbolTable` with the sibling
  `interface X`es, so getDeclaredTypeOfSymbol resolves an Interface, not the Union** (found by an instrumented
  probe: `localInfo=[TypeAliasDeclaration, InterfaceDeclaration, InterfaceDeclaration] laType=Interface`).
  Handles both a single `interface X` receiver and an `X | undefined` union receiver (sole non-nullish member).
- **Fix 4 (bd0d8eba, −9 services, TS2339 — the NavigationBarNode ×9 residual): the module-var-leak root
  behind a PROPERTY-ACCESS chain.** Round 442's `moduleFileLocalVarNames` bail covered a bare-Identifier
  receiver, but `parent.parent.kind` (checker.ts) leaks navigationBar.ts's `let parent: NavigationBarNode`
  through the bare `parent` — `parent.parent` resolves to NavigationBarNode (it has a `.parent`) so `.kind`
  FP'd on the CHAIN. `checkMemberAccessMissing` walks the receiver chain to its root Identifier and bails
  when the root is a leaked module var (and not the current file's own binding). FP-safe: the whole chain is
  resolved through the wrong leaked type; a CALL in the chain breaks the property-access walk so only pure
  chains bail. Generalizes: services/server/harness each −9.
- **INVESTIGATED & DEFERRED: the type-RESOLUTION fix (prefer the file-local `type X` in `getTypeFromTypeReference`)
  fails — `currentCheckFileName` is NULL at the lazy `getInfo`-return-type resolution (the type is resolved +
  cached before any file-check context sets it). The emission-site bail (fix 3) is the robust choice.**
- **META / next-agent (services @ 439):** the clean bounded services veins are now largely mined; the
  residual TS2339×30 is deep — **`Symbol.links`×5 is NOT augmentation-merge** (INVESTIGATED round 444: `links`
  is on `TransientSymbol`, narrowed by an `isTransientSymbol(symbol) && symbol.links…` `&&`-chain — the
  narrowing WORKS in isolation but the real symbolDisplay.ts FP is a deep gap, likely the huge-file flow-walk
  depth cap or a `TransientSymbol`/`Symbol`-lib conflation; needs an instrumented probe). **`ExportInfoMap`×8 is
  a WRONG-TYPE issue** (`exportInfo: SymbolExportInfo | FutureSymbolExportInfo` — a `let x: T = …; ({ x } = result)`
  destructuring-reassignment re-types `exportInfo` to `ExportInfoMap`; an inference/destructuring-target-type gap).
  The bulk is now deep-M3 TS2322×236 (fragmented relation gaps). The genuine AUGMENTATION-MEMBER merge (a
  `declare module { interface Symbol { links } }` adding members our binder doesn't merge into the base) is
  PARTIALLY modeled already — `mergeModuleAugmentations` merges the augmentation's interface DECLARATIONS into the
  base symbol's `declarations` list (round-444 repro: `Type.isUnion()` added by augmentation RESOLVES cross-file),
  so it is no longer the dominant residual it was thought to be.

**Round 443 (2026-07-08) — module-augmentation family + the module-file-local TYPE-alias leak
(Blocker #3): FOUR bounded fixes, all suppression-only. Compiler profile UNCHANGED (190 — no FPs in
these families there), but the fixes GENERALIZE hugely across the big profiles: services 591 → 498
(−93), server 887 → 733 (−154), harness 1,118 → 989 (−129). Suite 9,504 → 9,512 (+8 local across 3
test files, 0 regressions); 4 fix commits. Services diffed via `--listAll` as strictly by-position
removals: TS2664 10→0, TS2564 17→0, TS2304 24→2 (2 remaining = NodeJS `global`, env-legit offline),
TS2339 129→85 (SourceFileLike 44→0).**
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
- **Fix 4 (TS2339 on `SourceFileLike` ×44 → 0, Blocker #3 — the module-file-local TYPE-alias leak):
  a module-file-local `type X = A | B` alias leaks into `globals` and shadows the global `interface X`
  in OTHER files.** ROOT CAUSE pinned with an instrumented probe (the minimal/barrel repros do NOT
  reproduce — the leak is file-order/pollution-dependent, a whole-program phenomenon): the receiver
  `sourceFile: SourceFileLike` resolves to `Type.Union` `SourceFile | AmbientModuleDeclaration`
  (displayed via the alias map as 'SourceFileLike'), because services/importTracker.ts's NON-exported
  `type SourceFileLike = SourceFile | AmbientModuleDeclaration` won the last-wins merge over
  compiler/types.ts's `interface SourceFileLike` (Interface+TypeAlias don't merge). `AmbientModuleDeclaration`
  has no `.text` → the union member-access FP'd (both base `text`/`lineMap` AND augmentation-added
  `getLineAndCharacterOfPosition`). Built `conflatedTypeAliasFiles` (name X → files declaring `type X`,
  for X also declared as `interface X`); `checkMemberAccessMissing` bails a UNION-receiver TS2339 when
  the receiver's display (nullish-stripped, for `X | undefined` optional receivers) is a conflated name
  AND the current file is NOT the alias's own file. TYPE-space analog of round 442's `moduleFileLocalVarNames`.
  FP-safe: in every other file tsc resolves X to the INTERFACE (all members present), so it never errors;
  the alias's own file still fires. services/server −44 each, harness −3 (harness test files resolve
  `SourceFileLike` differently). Local tests pin the FP firewall only (the positive case is whole-program-only).
- **NEXT (remaining services buckets @ 498, all deep): TS2322×235 / TS2339×85 / TS2345×54 (M3 relation +
  residual Blocker #3), TS2416×11 (override, diverse), TS2353×10 (union/inherited excess), TS2740×9 /
  TS2739×8 (missing-property, deep relation), TS7006×8 (contextual typing — the inferFromUsage `Priority[]`
  case needs B83.5 nested-interface resolution + the reverted array-element enabler). TS2339×85 residual
  buckets: `Type`×20, `Info`×12, `RefactorContext`/`CodeFixContextBase`/`ExportInfoMap` — likely more
  module-file-local-type-alias/interface conflations (same Blocker #3 family — bucket + probe each).**

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

**Round 433 (2026-07-07) — M5 (perf round 2, JFR-driven): the two post-432 hotspots —
self-compile (compiler profile) 38–41 s → 19.9 s noEmit / 21.7 s wall with emit (the
2026-07-05 baseline was 592.8 s → cumulative ~27×), zod 5.0 → 3.6 s; diagnostics
byte-identical both rounds (1,148 incl. per-error diff / 1,665); suite 9,333/0 (+3 local).**
(a) `collectReassignedNamesInRange` (Flow.kt, B464) char-scanned `[closure.pos,
enclosingFn.end)` PER CLOSURE — ~14% of the compile (7.3% self + the String.charAt/getOrNull
churn) on `createTypeChecker`-scale functions. The matcher's decisions depend only on
BACKWARD context and the range END, never the scan start (a scan entering mid-word skips
the partial word exactly as a from-the-start scan attributes it before the range), so all
closures sharing an enclosing function now share ONE scan cached per `hi`, filtered by
position — exact semantics. (b) The flow walkers copied the whole cycle-detection `seen`
set PER BRANCH ANTECEDENT at every FlowBranchLabel (~11%: thousands of ids × a copy per
antecedent). `NarrowSeen` bundles set + add-log: branch antecedents walk the shared
path-so-far membership with mark/popToMark restoring it after each — only genuinely-added
ids are logged, so the restored membership is exactly the fresh-copy state; linear recursion
shares unmarked (additions persist upward, as before); both walkers changed in sync.
`FlowNarrowingPerfInvariantsTest` pins per-closure past-last-assignment semantics through
the shared scan (params, not `let` locals — the TS18048 emitter's captured-body-local
recovery is var-only per B467, verified pre-existing), branch-sibling isolation across a
diamond join, and an emitter-active positive control. Remaining profile is FLAT (top self
≤8%): HashMap churn in the walkers' memo, `findLocalTypeAlias$scan` (~4%, via
`discUnionParamMembers`), `checkMemberAccessMissing` ~3% — next M5 round needs a fresh
JFR pass, no obvious single target left.

**Round 430 (2026-07-07) — M3.1: the `append`/`addRange` inference unlocks +
TP-from-predicate binding. Self-compile (compiler profile) 1,000 → 956 → 936 (−64;
TS2322 496 → 435, TS2769 32 → 30); suite 9,348 → 9,356 (+8 local, 0 regressions);
2 fix commits (6a056b95, 83aeceb1).**
- **Fix 1 (6a056b95, −44 with +6 catalogued): the `T extends {}` constraint killed the
  whole `append` inference + readonly-array anchors.** Round 428's nullable-union
  inference worked for UNCONSTRAINED test sigs, but tsc declares `append<T extends
  {}>` — the candidate constraint check `checkTypeRelatedTo(string, {})` FAILED (an
  anonymous empty object target had no primitive-source rule; the apparent-type
  recovery is Type.Interface-gated), so the mapper was null and every `x = append(x,
  item)` kept the un-instantiated `T[]` return. New relation rule: an EMPTY anonymous
  object target accepts any non-nullish non-void source. TWO landmines pinned:
  (a) a `Type.Union` source's own flags carry no nullish bits (documented gotcha) —
  members checked explicitly so `string | null` still fails; (b) a TYPE-PARAM source
  is EXCLUDED — genericPrototypeProperty3 pins tsc's `Type 'T' is not assignable to
  type '{}'` + "might need an `extends {}` constraint" for unconstrained T under
  strict (the ungated first cut suppressed it; the SUITE GATE caught it — the
  corpus-as-regression-net working exactly as designed). Companion:
  `readonly T[]` params/args anchor array-of-tp inference (`Reference(ReadonlyArray,
  [T])` from getTypeFromTypeOperator; both `isArrayOfTypeParam` and the arg-side
  element extraction matched only "Array") — `addRange(to: T[] | undefined, from:
  readonly T[] | undefined)` never inferred. The +6 are precision-exposures of
  documented M3 residuals where anyType used to hide them (brand-string map keys via
  callback-return widening, optional-target ternary props, visitor generics,
  un-inferred `.map` U[], tuple-vs-array B526 ×2) — by-code still strictly shrank.
- **Fix 2 (83aeceb1, −20 strictly removals): TP-from-PREDICATE binding.**
  `getFirstJSDocTag<T extends JSDocTag>(node, predicate: (tag: JSDocTag) => tag is
  T)` called with a NAMED guard (`isJSDocAugmentsTag`) binds T from the guard's own
  predicate target — the `T | undefined` TS2322 bucket (utilitiesPublic's ~20
  getJSDoc*Tag wrappers, 41 → 21). The resolved signature ERASES the predicate
  (TypePredicate resolves to booleanType), so the param gate reads the AST
  (`predicatePositionTpOf`) and the candidate branch reuses round-424's barrel-aware
  `predicateTargetTypeOfGuardExpr`, soft-skipping unresolvable/inline guards. The
  candidate branch runs BEFORE the standard rawArgType path (which would type the
  guard as a callable object and hard-bail at the named-like gate). Single-sig path
  only (the multi-sig named-guard gate is untouched — B136's swap keeps firing).
- **Residual triage (next-agent):** TS2322×435 — `string` ×38 (incl. the ×24
  interface-override literal props, M3), `T` ×29 (dominated by CONTEXTUAL-RETURN
  inference: `parseTokenNode<T extends Node>()` has NO args — T comes from the
  return context, M3.2), `undefined` ×26 (VisitResult family), `T | undefined` ×21
  residue (non-Array single-arg generic anchors: `firstOrUndefinedIterator(it:
  Iterable<T>)` — extend the anchor set to same-target single-arg References),
  `U[]`/`U | undefined` ×31 (`.map`-family callback-return inference, M3.2),
  visitNodes TOut/TIn ×11 (visitor generics). TS7006×301 (M3.2). TS2345×86:
  `'true'` vs `'false'` nested-overload selection ×5, string-vs-literal-union ×10,
  `Node` vs never ×3 (M3.4 exhaustiveness), NodeArray vs SourceFile ×3.

**Round 429 (2026-07-07) — M3.1 histogram burn-down: the TS2345 core falls 261 → 86
(−67%). Self-compile (compiler profile) 1,186 → 1,156 → 1,135 → 1,027 → 1,000 (−186,
−15.7%; TS2345 261 → 86, TS2769 36 → 32, TS2322 501 → 496, TS2367 −2); every step's
by-site diff STRICTLY removals (the one +3 excursion was caught by the diff and gated
before commit); suite 9,315 → 9,348 (+33 local, 0 regressions); 4 fix commits
(577b2c54, 5fbb8caf, bc893882, d1e53cbd).**
- **Fix 1 (577b2c54, −30): call-types pass lexical shadowing — three scope shapes
  resolved a bare-identifier ARG to the WRONG outer declaration.** (1) A NESTED
  function's body-local (`let host = node.parent`) shadowing an ENCLOSING fn's param
  (`createTypeChecker(host: TypeCheckerHost)`): the inherited `currentLocalTypes` entry
  survived because round 428d's branch is gated entry==null —
  `applyCallTypesBodyLocalShadowing` pre-scans the body (statement-level, not
  descending into nested fn-likes) and anyType-overrides colliding local-decl names;
  same-fn param redeclaration excluded (param wins, pinned). (2) The round-428
  "PARAM-shadow mini-repro does not reproduce" mystery RESOLVED: the real shape is a
  DESTRUCTURED param (`{ useCaseSensitiveFileNames }` in sys.ts vs moduleNameResolver's
  same-named function) — binding names live only in the `currentParamBindingNames` side
  set, and `getTypeOfIdentifier` fell through to the merged globals; it now returns
  anyType for side-set names (after `currentLocalTypes`). (3) Arrow/fn-expr params
  (the walker deliberately doesn't type them) leaked the enclosing binding — those
  branches now scope the maps and register anyType for own param names. 8 local tests
  (CallTypesScopeShadowingTest).
- **Fix 2 (5fbb8caf, −21): embedded String.replace/replaceAll/search/split accept
  RegExp** (`searchValue: string | RegExp`, replaceValue `any` per the
  callbacks-are-any doctrine) — tsc regex-replaces pervasively. Corpus byte-identical
  (no "and N more" shifts). Accepted documented FN: a union-with-interface param is
  not simple-checkable, so wrong-typed args to these four params no longer error
  (control pins indexOf still fires).
- **Fix 3 (bc893882, −129, the big one): three arg-typing rules on the call-arg
  path.** (a) A `string | undefined` union arg is legal for an OPTIONAL param
  (`configFileName?: string` — tsc getTypeAtPosition unions undefined under strict);
  only undefined members stripped (null stays), relation on the stripped type,
  suppression-only. (b) A non-null-asserted arg (`readFile(p)!`) types as its
  nullish-stripped union — LOCAL strip (`stripNullishForNonNullArg`), mirroring the
  round-415 arithmetic rule; the round-407 global-strip revert stands. (c) THE
  DOMINANT mechanism (~110 sites): an Identifier arg whose NON-union interface type
  is guard-narrowed DOWN (`isSourceFile(x) && isExternalOrCommonJsModule(x)` — Node
  → SourceFile) substitutes the refined type, relation-gated — generalizes round
  428b's `this`-only branch. LANDMINE caught by the by-site diff: `never`-typed
  params must be EXCLUDED — `assertType<never>(node)` in an exhaustive-switch default
  needs exhaustiveness narrowing we don't model, and a partial case-union refinement
  TAKES THE UNION-ARG EMISSION PATH (interface args stay conservatively silent vs
  never; unions emit) → +3 FPs until gated. No stable local pin exists for the gate
  (tsc itself errors on the in-file non-exhaustive shape; the exhaustive
  discriminated-union shape needs M3.4 exhaustiveness) — pinned by the by-site diff.
  10 local tests (OptionalParamUnionArgTest).
- **Fix 4 (d1e53cbd, −27): typeof-unknown + string-enum + rest-arg narrowing.**
  (a) `typeof x === "<primitive>"` narrows a non-union UNKNOWN to the primitive —
  `narrowByTypeOfGuard`'s non-union flags path returned NEVER for a positive match
  on unknown (no primitive flags), which the relation-gated consumers rejected
  (moduleNameResolver `target: unknown` ×10). (b) An all-string-valued enum is
  assignable to `string` (`isStringEnumObjectType` in `isSimpleTypeRelatedTo`, the
  round-428b numeric sibling; unevaluated values NOT provable, conservative) —
  resolves the round-410 DEFERRED `Extension[][]` cluster: cascades to `Extension[]`
  → `string[]` via same-target covariant element comparison + clears the paired
  TS2367 no-overlap FPs (×8). (c) The rest-args helper mirrors B469 flow narrowing
  (`cond ? diag(…, deprecatedEntity) : …` ×5). 10 local tests
  (UnknownTypeofAndStringEnumArgTest).
- **META:** two process notes. (1) A mid-bench Checker edit poisoned one bench row
  (the 429b TSV row's build raced my 429c edits) — recovered via git-stash patch-split
  and per-commit listalls; batch edits BEFORE launching a suite/bench. (2) The
  round-428 residual note said "probe the pass's nesting entry with a marker before
  theorizing" — the actual fix needed no marker: re-reading the real site showed the
  param was DESTRUCTURED, which the mini-repro had simplified away. Repro fidelity
  beats instrumentation.
- **Residual triage (next-agent):** TS2345×86 — `'true'` vs `'false'` ×5 (parser.ts
  createMissingNode nested OVERLOADS with literal-typed params; the top-level
  mini-repro does NOT reproduce — the nested/closure context matters, probe needed);
  `string` vs literal-union ×10 (`"typings"|"types"|…`, pragma names, comparators —
  likely needs literal-preserving locals or narrowing); `Node` vs `never` ×3 +
  in-file exhaustive discriminated-union `assertType<never>` (needs M3.4
  exhaustiveness narrowing — catalogued, our A|B switch repro still fires);
  `NodeArray<Node>` vs SourceFile ×3, `System` vs IncrementalCompilationOptions ×2,
  `K` vs string ×2 (keyof-TP). TS2322×496 — `string` vs `string` ×24
  (interface-override literal props, M3), `T[]` residuals (~40: rest-param sigs,
  readonly-array params — `addRange(to: T[] | undefined, from: readonly T[] |
  undefined)`'s TypeOperator param defeats the union-mode detection), SearchResult<T>
  ×10, `undefined` vs VisitResult ×12. TS7006×301 (M3.2) untouched.**



**Round 428 (2026-07-06) — M3.1 (first real slice of the TS2322/TS2345 cores): generic
call-site inference for tsc's `append` idiom + the TS2345 histogram top + the array-literal
string-layer union rule + the body-local-shadows-function conflation. Self-compile
(compiler profile) 1,577 → 1,385 → 1,266 → 1,213 → 1,186 (−391, −25%; TS2322 751 → 501,
TS2345 394 → 261, TS2769 45 → 36, TS2339 6 → 7); suite 9,291 → 9,315 (+24 local, 0
regressions); 4 fix commits (67efa224, 14e9d566, 1791e87a, fbda155d).**
- **Fix 1 (67efa224, −192): nullable-union generic params + overloaded generic callees.**
  The single biggest TS2322 shape (`Type 'T[]' is not assignable to type 'Statement[]'`
  ×130+ + siblings) is tsc core.ts's `x = append(x, item)` — every `append` overload is
  GENERIC (single TP each) with `T[] | undefined` / `T | undefined` union params. Four
  coupled mechanisms: (a) `tryInferSingleTypeParamFromArgs` accepts a nullable-union-of-tp
  param (`nullableUnionOfTpMode`) and strips nullish members from a UNION arg (purely
  nullish arg → soft-skip, T still anchors from the other arg); (b) an `anyType` arg (an
  unmodeled local — for-of loop var) contributes NO candidate at the RETURN-TYPE site
  instead of killing the inference (`forReturnType`-gated; the arg-vs-param site keeps the
  hard bail — its consumers EMIT); (c) `getReturnTypeOfCallExpression`'s multi-sig path
  runs single-TP inference for overloaded all-generic callees (chosen sig first, then
  arity-matching sigs; first full mapper wins) — gated on NO named-type-guard Identifier
  arg (`argIsNamedTypeGuardIdentifier`: `filter(arr, isFoo)` selects tsc's guard overload
  whose S binds from the PREDICATE, which we don't model — and the gate is deliberately
  NOT folded into `callHasTypeGuardArg`, whose B136 concrete-overload swap must keep
  firing for named guards); (d) the string-layer `isAssignableTo` treats an array-literal
  source vs `T[]` (T an enclosing fn's TP) as unknowable. By-site: 201 removed, 8
  position-identical message transformations (builder.ts tuple-vs-anon-object — the B526
  representation gap now visible where 'T[]' was), 1 new FP at factory/utilities.ts:713 —
  **tsc 5.5 INFERRED TYPE PREDICATES: `filter(helpers, helper => !helper.scoped)` gets an
  inferred `helper is UnscopedEmitHelper` in tsc, selecting the guard overload; we take
  the boolean overload → EmitHelper keeps the ScopedEmitHelper member → TS2339 on
  `.importName` (catalogued M3.4; needs predicate inference from arrow bodies).**
- **Fix 2 (14e9d566, −119): the TS2345 histogram top — three mechanisms.** (a) explicit
  `this`-PARAM annotation wins over the objlit contextual `this` in the call-types walker
  (`withObjThis` now resolves `value(this: Node)` — debug.ts's Object.defineProperties
  `__tsDebuggerDisplay` FP'd ×36 at every `isFoo(this)` arg). (b)
  `tryEmitOptionalMemberArgVsRequiredNamedTs2345` (the optional-member arg emitter that
  synthesizes `T | undefined` locally) consults `propertyAccessNarrowedNonNull` — a
  truthy-guarded access is not undefined (`if (source.valueDeclaration)
  setValueDeclaration(target, source.valueDeclaration)`, checker.ts mergeSymbol ×24+;
  unguarded + wrong-polarity controls pinned). (c) two exposure companions the by-site
  diff caught: numeric-enum → `number` in `isSimpleTypeRelatedTo` (FlowFlags/Comparison/
  TypeFlags ×8), and a this-typed arg narrowed DOWN by a guard substitutes the refined
  type (relation-gated suppression-only; `isIdentifier(this) ? idText(this) : …`).
- **Fix 3 (1791e87a, −53): 'array' vs union-with-array-member at the string layer.** An
  array-literal source against a union member that is array-ish (`[]`-suffix, tuple,
  `Array<X>`/`ReadonlyArray<X>`) is unknowable at the string layer → permissive
  (`sourcesContent = []` vs `(string | null)[] | undefined`, `return []`); a union WITHOUT
  an array member still fires (pinned).
- **Fix 4 (fbda155d, −27): the body-local-shadows-function half of the conflation
  family.** A body-local `const symbolName = …` colliding with an outer/imported FUNCTION
  resolved through the merged globals to the function in bare-identifier ARG positions
  (checker.ts's `canUsePropertyAccess(symbolName, …)` → TS2345
  `(symbol: Symbol) => string` vs `string` ×15 + TS2769 ×8). The call-types walker's
  VariableStatement branch registers an anyType shadow when the colliding outer symbol
  declares a FUNCTION (AST-only gate) — mirrors M1.11's `shadowNestedFunctionNames`.
  First-cut negative control was WRONG about baseline capability (non-callable body
  locals aren't typed by this pass at all — the suite gate caught it); replaced with a
  param-based control.
- **META:** the ~450 ms scratch-CLI repro loop + temporary `println` tracing (the CLI shows
  stdout, unlike gradle) found the root causes fast; the XDBG probe DISPROVED the assumed
  emitter for fix 2b (checkArgumentsAgainstSignature's B469 narrowing never ran — the
  emitter was the dedicated optional-member walker).
- **Residual triage (next-agent):** TS2345×261 — the PARAM-shadow half of the conflation
  remains (`(state: ModuleResolutionState) => any` vs boolean ×14, `TypeCheckerHost` ×14:
  watch.ts's `useCaseSensitiveFileNames` / checker.ts's `host` are enclosing-fn PARAMS
  shadowing barrel-imported functions, read inside NESTED arrows — the mini-repro of the
  same shape does NOT reproduce, so the real blocker is in how the pass enters those
  specific nestings; probe with a marker before theorizing); `Declaration | undefined`
  guard-shape leftovers. TS2322×501 — `string | string` ×24 (interface-override literal
  props: `TsConfigOnlyOption.type: "object"` — per-prop resolution through the narrowed
  redeclaration, M3), `undefined | VisitResult<Node | undefined>` ×12 (generic alias
  unions), residual `T[]` shapes ×~30 (inference gate misses: rest-params, multi-TP),
  `SearchResult<T>` ×10 (un-inferred generic Reference returns), builder.ts
  tuple-vs-anon-object ×8 (B526). TS7006×301 (M3.2 contextual typing) untouched.**

**Round 427 (2026-07-06) — M3.4: the TS2454 bucket round 426 unmasked — three tsc-faithful
`assumeInitialized`/definiteness rules. Self-compile (compiler profile) 1,593 → 1,577
(−16; TS2454 20 → 4, all else byte-identical); suite 9,282 → 9,291 (+9 local, 0
regressions); 1 fix commit (7b2e3807).**
- **(1) Logical assignments are DEFINITE (tsc `getAssignmentTargetKind`):** `??=`/`||=`/
  `&&=` classify `AssignmentKind.Definite` (same as plain `=`), so
  `isSymbolAssignedDefinitely` → `isNeverInitialized` false → a CAPTURED (cross-closure)
  read of an outer `let` assumes initialized when any definite assignment exists
  ANYWHERE, nested closures included (tsc checker.ts:31196 `assumeInitialized =
  … (isOuterVariable && !isNeverInitialized) …`). Our B78.2 anywhere-scan
  (`collectAssignmentsInExpr`) recognized only `=` — tsc's own
  `(sourceStack ??= []).push(source)` / `(trackedSymbols ??= []).push(…)` closures FP'd
  ×13. Compound assignments (`|=`, `+=`, `++`) stay NON-definite
  (`AssignmentKind.Compound`) — the negative control matches unusedLocalsInMethod4's
  `enabledSubstitutions |= …` baseline expectation.
- **(2) A `!`-asserted read assumes initialized:** the literal `node.parent.kind ===
  SyntaxKind.NonNullExpression` disjunct — tsc's own core.ts `return lastResult!`.
  Applied in BOTH read walkers (`findUninitializedRefs` + `walkExprForFlowTS2454`): a
  bare Identifier DIRECTLY under `!` is exempt (covers `x!` and `x!.prop`);
  `(obj.foo)!` still walks the receiver `obj`.
- **(3) The comma-nested definite assignment (`(!memberName ? (memberName = X, true) :
  …)`, checker.ts getSignaturesOfType) needed TWO coupled fixes, both caught by the
  bench by-site diff:** (a) the anywhere-scan's iterative left-spine walk applied the
  assignment-target rule only to the OUTERMOST BinaryExpression — a COMMA expression
  nests the assignment on the LEFT spine, silently skipped; per spine node now (tsc
  `markNodeAssignments` is a full forEachChild walk). (b) The FLOW-based walker's
  expression-bodied-arrow branch (B86.1a) checks the arrow body against the OUTER
  uninit set when reached via a flagged position (a NESTED if's condition is walked
  `inUncheckedBody=true` — which is why the real site only fired inside the enclosing
  `if (kind === SignatureKind.Call …)` block and a top-level repro was clean); it now
  masks out names with a definite assignment inside the arrow body (the captured-read
  exemption), via the same anywhere-scan expression walker.
- **Residual TS2454×4 (triaged, none bounded):** scanner.ts `resultingToken` ×2
  (assigned inside a `while (true)` body before every exit — `isAssignedAtFlow` follows
  only the loop-ENTRY antecedent at FlowLoopLabel, the deliberate back-edge bound; needs
  loop-aware assignment evidence); checker.ts:14106 `indexInfos` (same-container flow
  precision: `x = concatenate(x, …)` self-read in a for-of after conditional seeding);
  generators.ts:1681 (`for (const variable of …)` SHADOWS the outer `let variable` — the
  name-based block-unaware `uninitialized` set resolves the read to the outer decl;
  needs block-scoped shadow tracking).
- 9 local tests (Ts2454AssumeInitializedTest) with negative controls (compound `|=`
  still fires; never-assigned captured read still fires; plain un-asserted read still
  fires; a plain same-container read after an in-arrow assignment still fires — the
  arrow's assignment is invisible to the outer control flow, which is why the real tsc
  source uses `memberName!` for those reads).
- **Perf note:** bench self-time 126.6 → 112.2 s — likely band movement (three
  consecutive runs trended 149 → 127 → 112 s); treat the M5 single-run baseline as
  ~110–150 s until an iterations run.

**Round 426 (2026-07-06) — M3.4 (absorbs M1.2's TS2563 item): faithful TS2563 — flow-walk
DEPTH-TRIP semantics with per-container disable, replacing the B399 per-file node-count
proxy. Self-compile (compiler profile) 1,600 → 1,594 → 1,593 (−7; TS2563 27 → 1 → 0,
TS2454 0 → 20 — pre-existing walker FPs the proxy's blanket per-file filter had masked,
now honestly visible; all else byte-identical by-code); suite 9,276 → 9,282 (+6 local,
0 regressions); 2 fix commits (4d23738f + db69fe59). (The implementing session was OOM-killed
mid-verification with the work complete-but-uncommitted; this session verified, measured,
landed it, and root-caused + fixed the one residual trip.)**
- **Mechanics (4d23738f):** tsc reports TS2563 ONLY when a flow walk recurses 2000 deep
  (checker.ts `getTypeAtFlowNode` `flowDepth === 2000` → `flowAnalysisDisabled` +
  `reportFlowControlError(reference)` at the containing function-or-module block +
  errorType for that container's flow queries thereafter — so TS2454 is suppressed per
  CONTAINER, tsc's OR-rule). All three flow walkers (`narrowTypeFromFlow` + the
  FollowLoopEntry mirror + `isAssignedAtFlow` — the last rewritten ITERATIVE with the
  round-413 accounting: linear pass-through antecedents free, only branch-join /
  condition / loop-entry recursion consumes depth) set `flowDepthTripped` at the trip;
  every depth-0 entry (11 sites) routes through `flowWalkWithTripCheck(reference)` —
  pre-checks `flowDisabledRanges` (disabled → conservative default WITHOUT walking),
  one-shot TS2563 per container via the flow graph's new `containerStarts` (innermost
  containing function-like body block, else the file); the end-of-init TS2454 filter is
  per-container-RANGE (was per-file `cfaTooLargeFiles`, deleted). The dedicated
  `evolvingArrayWalkTrips` init walk supplies the depth consumer for the corpus pin
  (largeControlFlowGraph: auto-typed `const data = []` + 10k top-level `data[0] = 0`
  writes, one level per relevant mutation; after the first trip the container is
  disabled, so the whole file costs ONE 2000-step walk). Our OWN budgets (visit budget,
  global re-entry depth, cycle bail) still truncate SILENTLY — only the per-walk depth
  limit is tsc's TS2563 semantic.
- **The measured trade (by-code diff, everything else byte-identical):** −26
  by-construction TS2563 proxies; +20 TS2454 = pre-existing definite-assignment FPs on
  the giant files the per-file filter had blanket-suppressed. Triage (next bounded
  burn-down bucket, three shapes): (a) DOMINANT ×~16 — cross-closure reads of an outer
  `let` (`let sourceStack: Type[];` in the outer fn, `(sourceStack ??= []).push(…)`
  inside a NESTED function — checker.ts inferFromTypes/serializer, tsc's
  used-before-assigned check applies only within the declaration's own control-flow
  container; captured reads assume initialized); (b) core.ts:2474 `return lastResult!`
  — a NON-NULL-ASSERTED read (tsc does not report TS2454 through a `!`); (c)
  scanner.ts `resultingToken` ×2 — assigned inside a `while (true)` body before every
  exit, read after `Debug.assert(resultingToken !== undefined)`; our `isAssignedAtFlow`
  follows only the loop-ENTRY antecedent at FlowLoopLabel (the deliberate back-edge
  bound), so in-loop assignment evidence is invisible.
- **Fix 2 (db69fe59): the 27th TS2563 was OURS, not the proxy's — `flowCallMightNarrow`
  needs the asserts-callee check (tsc `getEffectsSignature`).**
  diagnosticInformationMap.generated.ts (~2,100 top-level `diag(…,
  DiagnosticCategory.Error, …)` statements): any walk for a `DiagnosticCategory`
  reference found EVERY call's args mentioning the path, so the round-413
  over-approximating gate recursed per call → 2,100 > 2,000 → trip. tsc resolves the
  callee's effects signature BEFORE deciding (cached per node): a non-assert call is
  followed in the `while` loop, consuming NO flowDepth. `flowCalleeMayHaveAssertEffects`
  gives an EXACT verdict for Identifier callees (map-lookup resolution via
  `resolveFlowCalleeDecl`'s Identifier branch — never types a receiver; same decl +
  same predicate test `narrowByAssertCall` applies, so iterating past a false is
  EQUIVALENT, not just safe) and conservative-TRUE for PropertyAccess callees
  (`Debug.assert(x)`) — resolving those types the RECEIVER (the round-385
  services-hang hazard), they keep the consume-depth behavior. LESSON: with faithful
  TS2563, the round-413 "a too-eager gate only costs a depth level" calculus changed —
  a too-eager CALL gate now manufactures a false TS2563 on any >2000-chain of
  path-mentioning non-assert calls.
- **Local tests (CfaTooLargeBailTest 2 → 8):** deep branch chain trips exactly ONCE +
  suppresses the container's TS2454; per-container disable (a sibling function's TS2454
  SURVIVES a trip — the per-file proxy killed it); straight-line 3000-assignment chain
  does NOT trip; evolving-array 3000-write chain trips once at the first statement
  (+ 100-write control) — the largeControlFlowGraph pin the JS-emit-only corpus test
  never asserted; 2,500 non-assert calls mentioning the reference do NOT trip (426b,
  with the TS2339 control still firing); 2,500 asserts-callee calls DO trip exactly
  once (the too-lax-gate landmine control).
- **Perf watch (M5) — 426b is a WIN, not a cost:** bench self-time 150.9 s (round 425,
  dirty) → 149.4 s (426) → **126.6 s (426b, −15.3%)**. The asserts-callee gate doesn't
  just fix the false trip — every path-mentioning NON-assert call used to break the
  fast-forward loop into recursion (+ a narrowByAssertCall resolution at each), and
  tsc's sources are saturated with calls that mention whatever reference is being
  walked; now those iterate for free.

**Round 425 (2026-07-06) — M3.4/M1.12: the TS2339 never-cluster ROOT CAUSES + eight more
narrowing slices. Self-compile (compiler profile) 1,662 → 1,634 → 1,628 → 1,608 → 1,607 →
1,603 → 1,600 (−62; TS2339 68 → 6, never×21 → 2); EVERY step's by-site diff strictly
removals; suite 9,251 → 9,276 (+25 local tests, 0 regressions); 7 fix commits.**
- **Fix 1 (−28, eb28f0d3): union-target guards distribute over candidates + CANONICAL enum
  discriminant keys.** Two coupled root causes behind the never cluster: (a)
  `narrowByCallPredicate`'s positive union branch tested narrow-DOWN against the WHOLE
  target union (`targetUnion <: member` — requires every candidate, never holds); tsc's
  getNarrowedType distributes `mapType(candidate, c => …)` — now per-candidate, strictly
  more-keeping. (b) THE BIG ONE: the round-411 `"symId#member"` key space SPLIT — the same
  enum reaches the key builders as DIFFERENT Symbol instances (program-global merged vs
  declaring-file local via the barrel resolver), so ALL SyntaxKind keys looked pairwise
  disjoint and `typeGuardMemberDisjoint` dropped every guard-narrowed member.
  `canonicalEnumSymbol` (memoized; prefers the global merged symbol when it shares an
  EnumDeclaration NODE by identity and has enumValues) at all four key-builder sites.
  **Also cleared the round-423 "dead-end" `Identifier | ComputedPropertyName`×8 family and
  the isAccessExpression never×4 — the DISJOINTNESS VERDICTS, not the relation, were the
  blocker all along.** META: the scratch repro cleared while the real corpus didn't budge
  (zero site churn); two rounds of repro-enrichment found nothing — only stderr
  instrumentation on the REAL corpus (print the key sets) found the split.
- **Fix 2 (−6, 4cb59a6c): aliased SWITCH discriminants** (tsc compareTypeMappers:
  `const kind1 = m1.kind; switch (kind1) { case TypeMapKind.Simple: m1.source }`) —
  `narrowBySwitchClause` resolves a bare-Identifier subject through the round-423
  aliased-condition back-walk (the const-ness proof) to `<name>.<prop>`.
- **Fix 3 (08835c06, part of −20): four slices** — (a) `narrowByDiscriminantProperty`:
  a UNION-of-literals discriminant (`type: "list" | "listOrElement"`) matches positively
  when ANY constituent equals / survives a negative when ANY differs; an OBJECT-typed
  discriminant (`type: Map<…>`) can never === a primitive VALUE literal → positive drops
  the member (enum-flavored objects excluded). **LANDMINE (+3 nevers in the first cut,
  caught by the by-site diff): BOTH rules gate on a definite VALUE literal — optionality
  is a symbol attribute NOT folded into the resolved prop type, so `x.body === undefined`
  proves NOTHING** (checkGrammarAccessor/isUncheckedJSSuggestion collapsed). (b) `typeof
  x === "object"` three-way union filter (object-like + null match; primitives/undefined/
  CALLABLES — they report "function" — don't; any/unknown kept both branches). (c)
  truthiness of a BOOLEAN-LITERAL discriminant (`info.isStatic ? info.variableName : …`,
  classFields ×2). (d) a DESTRUCTURING read consults flow narrowing of its initializer
  (`if (!result) return; const { version, paths } = result` — moduleNameResolver/
  programDiagnostics/utilities ×6).
- **Fix 4 (fb6c23f4, part of −20): loop-entry retry for the round-418 single-type
  narrow-DOWN suppression** — a guard before a loop narrows a read inside it; the plain
  walk washes at the FlowLoopLabel (checker.ts tuple-inference `constraint.target` ×3).
  The single-type sibling of round-424 fix 1.
- **Fix 5 (−1, aa00dc51): instanceof narrows a SUPERTYPE member DOWN to the class**
  (`tracker instanceof SymbolTrackerImpl` on `SymbolTracker | undefined`, the class
  implements the interface — the subtype-only filter dropped everything). Approximates
  tsc's intersection fallback with the class type; the structural-identity corpus pin
  (instanceofWithStructurallyIdenticalTypes) verified intact.
- **Fix 6 (−4, 5ff41ffb): aliased `===` discriminants** (commandLineParser
  `const optType = opt.type; if (optType === "listOrElement") { opt.element }`) **+
  switch-DEFAULT negative narrowing** (a default clause alone in its flow range narrows
  by every case literal/enum key of the whole switch — executeCommandLine's
  `option.type.forEach`/`option.deprecatedKeys` + bonus utilities.ts:3466; conservative:
  non-literal case exprs bail, fallthrough ranges bail, only LITERAL-typed members drop
  on the direct path).
- **Fix 7 (−3): tsc's positive-empty INTERSECTION fallback** (`hasDynamicName(accessor)`
  vs an unrelated-in-both-directions target now yields `m & c` for object-capable pairs
  instead of `never` — **REVERSES the round-423 dead-end verdict: the 1,708 → 1,710
  net-negative was an artifact of the enum-key split; re-measure dead-ends when an
  upstream root cause falls**) + `typeof "object"` classifies an ENUM member as
  NOT-object (watchPublic's `ScriptTarget | CreateSourceFileOptions`).
- **Process notes:** (1) do NOT `compileKotlinJvm` while a background self-compile A/B is
  in flight — the recompile clobbers class files the running JVM lazily loads
  (ClassNotFoundException mid-run); concurrent CLI RUNS are safe. (2) The patch-split
  protocol again (5 same-file batches split into 7 bisectable commits, tests distributed
  per commit).
- **Perf watch (M5):** the round-425 bench single-run came in at 151 s self-reported vs the
  ~100–137 s recent band (+10%) — single-run noise vs the new retry/back-walk paths not yet
  disentangled; the retries only run on would-be-FP emissions and the back-walks are memoized,
  but re-measure with iterations at the next M5 touchpoint.
- **Residual TS2339×6 (all triaged):** checker.ts:33288/33289 never×2 — try/finally:
  `bindTryStatement` gives a finally-only block ONLY the try-end antecedent (unreachable
  when the try returns → never) — needs a preTry antecedent for the finally entry (but
  NOT for the post-switch continuation — TS2454 regression risk documented in-session)
  PLUS `??=` non-nullish-call-RHS narrowing; checker.ts:28630 `Type | IncompleteType`
  (`flags === 0` vs `flags: TypeFlags` — needs enum-as-literal-union comparability,
  B425/M3.3); moduleNameResolver.ts:2823 (interface modeling, M3);
  builder.ts:2242 (tuple-index on tuple-union, the B526 representation gap);
  es2020.ts:91 (loop-carried `OptionalChain` reassignment, M3). **Next-agent note —
  TS2563×27 (the whole bucket, diagnosed this session):** tsc emits TS2563 ONLY when a
  flow WALK recurses 2000 deep (`getTypeAtFlowNode` `flowDepth === 2000` → set
  `flowAnalysisDisabled`, report at the containing function-or-module block's
  `statements.pos`, return errorType thereafter — checker.ts:29036/28841); on tsc's own
  sources NO walk trips (the linear fast-forwarding our round-413 iteration mirrors keeps
  depth low), so all 27 per-FILE-node-count proxies are FPs by construction. The faithful
  rebuild: trip-detection + a per-CONTAINER disabled set + one-shot TS2563 at tsc's
  position, threaded through ALL flow walkers (narrowTypeFromFlow + FollowLoopEntry
  mirror, the TS2454 definite-assignment walkers), REPLACING the B399 per-file proxy AND
  its `cfaTooLargeFiles` TS2454 end-of-init filter (tsc's OR-rule then holds per
  container naturally). `CfaTooLargeBailTest` pins the CURRENT proxy deliberately and
  must be REWRITTEN to the depth-trip semantics (its 3000-if "big" shape plausibly DOES
  trip a faithful walk — sequential if-joins recurse per join; verify against
  `largeControlFlowGraph`'s baseline which expects TS2563). RISK: un-suppressing TS2454
  on the 27 files may surface previously-masked TS2454 FPs — measure the trade by-site.
  Next big buckets:
  TS2322×751 / TS2345×394 / TS7006×301 (M3 cores), TS2769×45 (M3.1 generic call-site
  inference), TS2563×27 (B399 heuristic → M3.4), TS2591×43 + TS2304×2 (env-legit).**

**Round 424 (2026-07-06) — M3.4/M1.12: seven flow-narrowing burn-down fixes from the round-423
residual triage. Self-compile (compiler profile) 1,707 → 1,691 → 1,687 → 1,683 → 1,680 → 1,672 →
1,662 (−45; TS2339 104 → 68, TS18048 5 → 1, TS2322 756 → 751); every step's by-site diff STRICTLY
removals; suite 9,223 → 9,251 (+28 local, 0 regressions, 2 deliberate pin flips toward tsc
semantics); 7 fix commits, 7 local test files (28 tests).**
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
- **Fix 6: assignment-overwrite reset (−8: moduleNameResolver.ts 1924/1931/1950 never×6 + bonus
  checker.ts:7144 / program.ts:4048 TS2322).** A shadowing redeclaration after an outer falsy
  guard collapsed to `never`: the walk crossed the outer falsy branch (→ `undefined`), passed the
  inner `const resolved = loadModuleFromImports(…)` UNCHANGED (unclassifiable call RHS kept the
  stale antecedent), and the inner truthy guard narrowed `undefined` → `never`. An overwrite now
  resets to the PRECISE overwritten type: a DECLARATION to its own annotation / initializer-call
  return annotation (the flow-nearest declaration IS the binding the read lexically refers to —
  the flat name-keyed local map is block-unaware/first-decl-wins), a plain `=` to its call-RHS
  return annotation; `??=`/`||=`/unresolvable keep the antecedent pass-through (for `??=` the
  antecedent IS the correct base). **MEASURED trap: resetting to the reader's flat-map
  declaredType instead injects the OUTER shadowed binding's type — 3 new FPs (builder.ts:1814,
  destructuring.ts:114, moduleNameResolver:1950 reshaped) — the precise-type form has zero.**
- **Fix 7: the DebugTypeMapper slice (−10: debug.ts 832–850, the whole family).**
  `type<TypeMapper>(this); switch (this.kind) { case …: this.source }` — FOUR coupled pieces:
  (a) `asserts value is <TP>` binds the TP from the call's EXPLICIT type arguments; (b) an
  assertion on an `any`/`unknown` reference RE-TYPES it to the target (the relation gate
  trivially passes for `any` and kept the useless `any`); (c) `checkMemberAccessMissing`
  consults flow narrowing for `this` receivers (`getTypeOfExpression(this)` is deliberately
  anyType per B101, so the round-418 suppression never applied) and the exhaustive-switch
  receiver typing recovers an anyType receiver through the same re-type; (d)
  `buildNestedFunctionMap` resolves a name collision to the UNIQUE TypePredicate-bearing
  declaration (Debug's `type` is an overload pair — sig + annotation-less impl — and the plain
  "≥2 → ambiguous" rule made the guard invisible to every narrowing consumer; zero or several
  predicate-bearing decls stay ambiguous). The single-file repro cleared in one pass but the
  REAL debug.ts needed (d) — the faithful multi-file repro (barrel import + namespace-local
  overloaded guard) was what exposed it.
- **Residual TS18048×1: checker.ts:21170 (overload-cluster return selection — M3). Next-agent
  note for the classFields.ts:841–859 never×5 sub-cluster: the shape is a De-Morgan early
  return `if (!isPrivateIdentifierClassElementDeclaration(node) || !shouldTransform…) return;`
  whose positive narrowing target `PrivateClassElementDeclaration` is a UNION OF
  brand-INTERSECTIONS (`PropertyDeclaration & { name: PrivateIdentifier }`, …) — the round-418
  positive-collapse fallback is gated `targetType is Type.Intersection` and misses a union of
  intersections, so the filter drops every member → `never`. Extending that gate (or applying
  the member-vs-intersection fold before the drop) is the candidate mechanism — verify with a
  marker first; the negative-exhaustion never pin (instanceofWithStructurallyIdenticalTypes)
  must stay intact. Next TS2339 buckets: never×21 remaining (per-site M3-relation diagnosis, catalogued round 423), DebugTypeMapper×10 —
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
| Corpus suite | jvmTest XMLs | green forever (8,842 / 0 / 3 at phase start; 9,251 with local tests as of round 424) |
| Self-compile FPs (tsc src/compiler) | `bench/self-compile-tsc.tsv` | 13,245 → 0 (**1,186 measured at round 428**; M1 complete at 2,726/round 389; rounds 395–427 burned bounded histogram-tail buckets + M3.4 flow-narrowing slices 2,726 → 1,577; round 428 opened the M3.1 core burn-down −391 (nullable-union generic param inference + overloaded generic callees for the `append` idiom, TS2322 751 → 501; this-param binding + guarded optional-member args + enum→number + body-local-shadows-function, TS2345 394 → 261; array-vs-union-member string layer); round 424 seven flow-narrowing fixes −45 (loop-entry union suppression w/ STRUCTURAL wash gate, call-RHS return-annotation narrowing, closure/join/call-crossing aliased conditions, prefix-path receiver guards, asserts-with-inferred-TP test-arg inference, assignment-overwrite reset to the declaration/call-RHS resolved type, DebugTypeMapper this-narrowing — every step by-site strictly removals); rounds 422–423 −92 (overload-arg flow narrowing, optional-chain discriminants, union-target guards end-to-end, exhaustive-switch receiver narrowing → TS2366 ZERO, aliased conditions); round 420 TYPE-ALIAS enum-member discriminant narrowing (M1.12) −9 (a `.kind: <alias>` member survived a `switch (x.kind)` because `enumMemberKeysOfTypeNode` handled only a direct `Enum.Member`; 0 new FPs); round 419 INTERSECTION union-member property resolution (M1.12) −46 (TS2339 189 → 143: `getPropertyOfType`/`typeHasOwnProperty` bail on a `Type.Intersection` member — fold the constituents in property resolution + discriminant-narrowing; 0 new FPs, self-compile time −17%); round 418 NESTED type-guard resolution (M1.12) −48 (TS2339 237 → 189: tsc's `isTupleType`/… guards are nested in `createTypeChecker` so the binder skips them and `resolveFlowCalleeDecl` missed them — program-wide unique-name fallback + a `Type.Union`-gate-bypassing narrow-DOWN suppression + an intersection-target positive-collapse fallback; 0 new FPs; the negative-exhaustion never of `instanceofWithStructurallyIdenticalTypes` stays intact); round 417 namespace-local `extends`-base resolution −2 (coordinated across `getTypeFromBaseTypeExpression` + `lookupInstanceMemberInResolvableChain`, FP-safe); round 409 `export *`-barrel / ESM-`.js` imported-guard FLOW narrowing (M3.4) −175 (TS2339 838 → 672); round 411 enum-member discriminant narrowing + type-guard-narrows-member-DOWN −59; round 412 single-type type-guard narrow-DOWN + TS18048 receiver-narrowing −1; round 413 the `export *` LEAF-EXPORT gate −407 (TS2339 614 → 237): the pre-413 star resolver returned non-exported IMPORT aliases, so barrel-imported `Debug.assert` (& every barrel guard) never resolved — the TRUE builder.ts blocker, NOT the round-412 depth red herring (an instrumented run showed ZERO walk truncations) — plus a dashboard-neutral tsc-faithful linear flow-walk iteration + a return-path narrowing consumer (−1); **round 414 the TS2366 "lacks ending return" family −35 (50 → 15): three CFA fall-through patterns in `statementAlwaysReturns`/`switchAlwaysReturns` — infinite-loop-with-return, trailing never-call (`Debug.fail`), switch fall-through — all FP-safe syntactic/barrel-resolution fixes; the remaining 15 are Pattern C2 (exhaustive switch w/o default → M3.4 discriminant-exhaustiveness)**; remaining bounded pool M3.4/M3-gated (a general-`resolveAlias` `.js`/star fix was measured net +297 via a TS2315 flood, reverted; NonNull-strip −17 but unmasks M3, reverted; const-string-enum→`string` relation deferred M3.3/B425); no-stub stays the honest default) |
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
  (its 27 self-compile TS2563 FPs remain on the dashboard). **SUPERSEDED (round 426):
  the faithful depth-trip landed early (the narrowing walkers ARE deep flow walks, so
  trip detection didn't need full M3.4) — B399 proxy + `cfaTooLargeFiles` deleted, the
  27 FPs gone; see the round-426 session note.**
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

**Round 415 (2026-07-05) — M1.12: TWO clean bounded self-compile fixes from bucketing the fresh
full `--listAll` by normalized message shape, both FP-safe. Self-compile (compiler profile)
1,930 → 1,922 (−8); suite 9,134 → 9,145 (+11 local, 0 regressions); 2 commits (4c768bb7, a647aa74).**
Method (the M1.12 note): re-ran `--listAll` at HEAD (1,930, 100 s), bucketed all lines by
normalized shape — the M3 cores dominate (TS2322×785 / TS2345×396 / TS7006×301 / TS2339×237, all
engine-gated) but two bounded tail buckets were genuine bugs. **(1) NonNull arithmetic operand
(TS2362 15 → 10, −5):** a `x!` (NonNullExpression) arithmetic/comparison operand whose base type is
`T | undefined` now uses the NON-NULL type — tsc types `x!` as `NonNullable<typeof x>` and does
arithmetic on THAT. `getTypeOfExpression` deliberately keeps the union for `(T | undefined)!`
(round 407: a GLOBAL nullish-strip in that case unmasks M3 object-literal/generic gaps → reverted),
but the arithmetic pass classifies operands and a `Type.Union`'s own `.flags` carry neither the
Undefined bit nor the numeric-enum bit, so `TokenFlags | undefined` fails every operand test →
spurious TS2362/TS2363. New `arithOperandType` strips nullish LOCALLY (only for a syntactic `!`),
reproducing tsc's own source: `templateFlags! & TokenFlags.TemplateLiteralLikeFlags` (nodeFactory
×2), `contextFlags! & ContextFlags.X` (checker ×2), `state.affectedFilesIndex! - 1` (builder).
FP-safe by construction: only fires on an explicit `!`, only ever REMOVES nullish members (a
`(string | undefined)!` still fails, still errors), and is local to the pass (no touch to global
expression typing — the round-407 blast radius). The residual 10 TS2362 (+2 TS2363) are
`&&`/`||`/reassignment flow-narrowing cases (checker.ts `checkMode && checkMode & X`, `flags =
flags || None`) — M3.4. **(2) exhaustive enum switch (TS2366 15 → 12, −3):** round 414 deferred
Pattern C2 — a `switch` with NO `default` that EXHAUSTIVELY covers its discriminant's enum is
terminating (tsc narrows the discriminant to `never` after all cases, so the endpoint is
unreachable). This lands the FP-SAFE SUBSET: an ENUM / enum-member-union / call-return-enum
discriminant (the value set is a precise enum). `isExhaustiveEnumSwitch` claims exhaustive ONLY
when it can PROVE every enum member is covered — every case an `Enum.Member`/`undefined`/`null`,
and the discriminant resolving to a precise enum (or `enum | undefined/null`) via a simple param's
type ANNOTATION (`enumSwitchKeysFromTypeNode`: a bare enum name → all members; a type alias =
union of enum members → recurse — `CompoundAssignmentOperator` = union of `SyntaxKind.X`; an
`Enum.Member` ref → that one) OR the resolved expression type (`enumSwitchKeysFromType`: a pure
enum object → all members, a `enum | undefined` union → members + nullish marker). Any uncertainty
(a non-enum-member case, a discriminant that doesn't resolve to a clean enum, a missing member)
returns null/false → the TS2366 STANDS, so it never suppresses a genuinely non-exhaustive switch's
diagnostic (no false negative). Reuses the round-411 enum helpers
(`resolveEnumSymbolForDiscriminant`/`enumMemberKeyOfExpr`/`enumValues`, barrel-aware). Fixed tsc's
own `getCategoryFormat(category: DiagnosticCategory)`,
`getNonAssignmentOperatorForCompoundAssignment(kind: CompoundAssignmentOperator)`,
`getSetExternalModuleIndicator` over `getEmitModuleDetectionKind(options): ModuleDetectionKind`.
The remaining 12 are `.kind` discriminated-union switches (`mapper.kind`, `node.kind` ×5,
`target.kind`, `name.kind`, `LiteralToken["kind"]` indexed-access, `options.newLine` property
access) — the discriminant is a property of a union of interfaces/TypeLiterals, needing type-alias-
chain + intersection + inherited/TypeLiteral-`.kind` flattening, with a real false-negative risk
(`.errors.txt` disabled = weak gate) — the documented larger M3.4 slice, which correctly BAILS
here. **This analysis feeds ONLY `checkBodyForImplicitReturn` (TS2366/TS7030/TS2355); TS7027
unreachable-code uses the separate `isDefinitelyTerminating`, so a wrong claim here cannot
spuriously report post-switch code as unreachable — the change is a pure suppress-when-provable.**
11 local tests (NonNullArithmeticOperandTest ×5, ExhaustiveEnumSwitchTerminationTest ×6) with
negative controls (a `(string|undefined)! - 1` still fires TS2362; a bare `flags & X` with no `!`/
guard still fires; a switch MISSING an enum member, an `enum|undefined` WITHOUT `case undefined`,
and a `break`-ing case body all STILL fire TS2366 — the FP-safety firewall, since the corpus is a
weak gate for TS2366 with `.errors.txt` disabled). **DEFERRED (bounded but broad/risky): the
empty-tuple-vs-all-optional-tuple TS2739** (moduleSpecifiers.ts:344 `return emptyArray as []`
against `readonly [kind?, specifiers?, moduleFile?, modulePaths?, cache?]`) — `[]` is assignable to
a tuple whose elements are ALL optional, but `buildTupleFromTypes` builds all numbered props as
required (`SymbolFlags.Property`, no Optional flag) since the resolved tuple `Type` loses the AST
`NamedTupleMember.questionToken`/`OptionalType` optionality (the documented no-minLength-tracking
gotcha). The clean fix needs a new `SymbolFlags.Optional` bit threaded through tuple building +
`isOptionalProperty` reading it — broad regression surface (many `isOptionalProperty` callers on
tuple props) for 1 self-compile instance; not worth the risk this session. **META: re-confirms the
M1.12 method — bucket the FULL `--listAll`, not the log tail. Also re-confirms round 414's finding
that `.errors.txt` being disabled makes the whole termination analysis (TS2366/TS7030/TS2355) gated
ONLY by the full suite + local tests → strong negative controls are mandatory for any
suppress-a-diagnostic change there.** Perf unmeasured beyond the bench row (an operand-strip + a
syntactic enum-switch — no relation-engine work; the enum resolution reuses round-411's memos).

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

**Round 408 (2026-07-05) — a decomposed M3.4 slice: the TS2349 "not callable" family (the

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

**Round 432 (2026-07-07) — M5 (first performance round, JFR-driven): the alias-resolution
quadratic — self-compile (compiler profile) wall ~490–593 s → 38.6 s (~13–15×), zod
6.0 → 5.0 s, diagnostics byte-identical (1,148 / by-code identical; zod 1,665 identical);
suite 9,328/0 green (+2 local).** A JFR profile (settings=profile, stackdepth=1024) on the
compiler profile showed **76% of ALL samples in `Identifier.equals` ← `ImportSpecifier.equals`
← `ArrayList.indexOf`**: the program-wide structural scans in `resolveAlias`'s
ImportSpecifier branch and `findEnclosingImport` (`spec in bindings.elements` over every
ImportDeclaration of every binderResult). Root cause: only POSITIVE resolutions are cached
(`setSymbolTarget`), so every UNRESOLVABLE alias — exactly tsc's ESM-`.js` barrel imports,
which `resolveModuleSpecifier` deliberately won't strip — re-ran the full scan on EVERY
`resolveAlias` call, from flow-walk recursion depths >1024 (stacks truncated, so most samples
lost root attribution; the visible tail pointed at `computeImportedFunctionLikeDecl` /
`resolveEnumSymbolForDiscriminant`). Fix (semantics-preserving by construction, verified
byte-identical on both dashboards): (1) `enclosingImportsOf` — a lazily-built structural-keyed
index ImportSpecifier → encounter-ordered list of (fileName, ImportDeclaration), replacing
both scans; structural keys + first-write-wins lists reproduce the old scans' first-match AND
fallback-to-next-match semantics exactly (structurally-equal specifiers across files share a
key, as they matched each other in the old scans). (2) `resolveModuleSpecifier` memoized incl.
null results via containsKey (it is a pure function of the specifier — `fileResults`/`options`
fixed before init, contextNode unused; the null case is the hot one). Second-tier zod finding
(NOT yet fixed, queue candidate): `resolveQualifiedName`-driven `resolveAlias` string churn
still ~30% of the zod compile. `EnclosingImportIndexTest` pins collision + distinct-key
resolution (the same-name-from-different-modules variant is UNUSABLE as a signal — Blocker #3
scope conflation masks it, verified pre-existing on clean HEAD via stash A/B). Also: zod
compiles end-to-end (107 files, 0 crashes, runnable emit — smoke-tested; 1,665 FPs vs real
tsc 6.0.3's 0 in 2.8 s) — a good second dashboard profile; and `bench-compile-tsc.sh` stat
parsing (`grep -oP`) silently logs 0s on macOS (BSD grep), wall_ms is real.

**Round 431 (2026-07-07) — M3.2 (STARTED) + M3.1: the TS7006 core falls 301 → 11
(−96%) via contextual typing, and engine return-checking reaches switch/try bodies
behind a foreign-TP source gate that then extends to every assignability path.
Self-compile (compiler profile) 936 → 672 → 641 → 574 → 551 → 482 (−454, −48%;
TS7006 301 → 11, TS7019 4 → 0, TS2322 435 → 276, TS2367 kept 0); by-code strictly
shrinking at every landed step; suite 9,356 → 9,384 (+28 local, 0 regressions);
5 fix commits (b2411656, 186cb3cd, cceeb26b, f12dfe61, bd567338).**
- **Fix 1 (b2411656, −264 strictly removals): TS7006 contextual typing — the two
  dominant mechanisms.** (a) Callee RESOLVABILITY: `isCalleeResolvable` falls back to
  the round-418 nested-function name map (`filterType`/`mapType` inside
  `createTypeChecker` are B83.5-unbound ×~140 sites) and a NEW lexical scope stack
  (`implicitAnyScopes` — params incl. binding-pattern names + body locals, push/pop
  in try/finally at every function-like boundary), so param-typed and nested callees
  contextually type their callback args — the same permissive rule file-level
  callees already had. (b) Assignment-RHS contextual typing (tsc
  getContextualTypeForBinaryOperand): `lhs = arrow` resolves the LHS DECLARED type
  (scope-map annotations, `as T` casts, property-access members via the receiver)
  under the single-applicable-signature rule (mirrors B476 — a ≥2-sig LHS gives NO
  ctx, contextualTypingWithGenericAndNonGenericSignature's pinned FIRE; an untyped
  `let mark; mark = tag => …` keeps firing, uncalledFunctionChecksInConditional2's
  pin). Binary propagation: `||`/`??` feed BOTH operands, `&&`/comma the RIGHT only
  (contextuallyTypeLogicalAnd03/CommaOperator03 pin the left firing).
  `contextualCallableArity` sees through single-callable-member unions
  (`WriteFileCallback | undefined` returns) + lazy References. 13 local tests.
- **Fix 2 (186cb3cd, −31 strictly removals): residual receiver shapes.**
  `lookupPropertyTypeForCtx` resolves members through Type.Intersection receivers
  (`x as CompilerHost & ResolutionCacheHost`, watchPublic ×9), lazy-membered
  References (target fallback — arity survives missing substitution), and interface
  `extends` bases (depth-guarded); an un-annotated call-initialized local registers
  its callee's declared RETURN annotation (AST-only, lazily resolved).
- **Fix 3 (cceeb26b, −108/+41): engine return-checking in switch/try + the
  foreign-TP gate + TS2367 anchoring.** `returnTypeNode` now threads through the
  SwitchStatement/TryStatement arms of BOTH assignability dispatchers (+ the
  Stmt-dispatcher IfStatement arm) — a `return undefined` in a switch case
  previously fell to the STRING path which can't resolve alias unions
  (`VisitResult<Node | undefined>` ×12 FP'd). COUPLED (load-bearing pair):
  `checkReturnAssignability` bails on a source containing a FOREIGN type param
  (name ∉ enclosing `typeParams` — an un-inferred generic call result like `return
  append(…)` typing as `T[]`; own-TP sources keep checking, corpus-pinned) — this
  cleared ~95 PRE-EXISTING top-level un-inferred-generic return FPs. The +41 are
  position-exposures of pre-existing M3 families at newly-checked positions
  (round-426 "honestly visible" precedent): NodeArray<X>-vs-NodeArray<Node>
  covariance ×~17 (cross-file heritage relation gap), `Node` narrowing-dependent
  returns ×5, branded `__String` ×2, TransformerFactory ×3. The TS2367
  same-target-Reference disjointness proof now requires a differing arg pair
  anchored in a NON-object type (a first-touch-exposed `nodes ===
  (parent as X).typeArguments` FP; `Array<string>` vs `Array<number>` stays firing).
- **Fix 4 (f12dfe61, −23 strictly removals): the gate walks ANONYMOUS-object
  members/call-sigs** — `SearchResult<T> = { value: T | undefined } | undefined`
  hides the un-inferred TP in a member (`return toSearchResult(undefined)` ×12 +
  `() => T` factory returns ×4); named interfaces stay excluded (Reference args
  carry their TPs; a member walk would be broad + first-touch-shifting).
- **Fix 5 (bd567338, −69 strictly removals): the foreign-TP gate extends to the
  var-decl (`const p: () => Printer = memoize(…)`), assignment
  (`fileIncludeReasons = append(…)`), property-access-assignment
  (`type.typeParameters = concatenate(…)` — no typeParams threading there, ALL
  TPs treated foreign), and conditional-return-branch (B69.1 runs BEFORE the
  return-path gate) paths. LANDMINE caught by the SUITE GATE (5 corpus
  regressions fixed pre-land): a generic FUNCTION VALUE source (`var f:
  (x: number) => number = genericFn`) carries its sig-OWN TPs — legitimately
  checkable, NOT leaked inference; `typeContainsForeignTypeParam` treats a
  signature's own type parameters as bound within that signature
  (genericAssignmentCompatOfFunctionSignatures1 + 4 siblings pin it; the
  refinement cost zero self-compile suppressions).
- **META:** (1) the round-431 TSV row used `--no-emit` (emitted column 0 — not an
  emit regression). (2) The round-428 negative-control lesson RECURRED: the first
  own-TP control asserted a capability the baseline never had (bare `return x`
  own-TP-vs-number is a pre-existing FN) — verify a control fires at BASELINE before
  pinning it; replaced with the B69.1-ordered ternary shape.
- **Residual triage (next-agent):** TS7006×11 — namespace-local interface
  annotations ×5 (builderState `const map: ManyToManyPathMap = {…}` inside
  `namespace BuilderState` — the walker's getTypeFromTypeNode has no namespace
  context), initializer-inferred fn locals ×3 (parenthesizerRules
  `let rule = cache.get(k); rule = node => …`, checker addLazyDiagnostic),
  destructured-member local ×1, object-member ctx ×2 (watchUtilities). TS2322×345 —
  `string`→`string` ×24 (interface-override literal props, M3), assignment-path
  foreign-TP siblings (`T[]`→`TypeParameter[]` ×2, `U | undefined`→`Modifier` ×2 —
  extend the gate to checkVarDeclAssignability/checkAssignmentExpression, same
  principle), `undefined`→ResolutionMode/ElaborationIterator ×8 (non-return
  positions), NodeArray-covariance adds ×~17 (fix `TypeNode <: Node` cross-file
  heritage or catalogue), `Node`→`Declaration | undefined` ×5 (narrowing-dependent,
  M3.4). TS2345×86/TS2769×30 (nested-overload `'true'`/`'false'` ×5,
  string-vs-literal-union ×10). TS2591×43 is env-legit (offline, no @types/node —
  `--node-stub` suppresses).

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
