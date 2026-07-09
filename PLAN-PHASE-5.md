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

**Round 452 (2026-07-09) — bounded FP burn-down: TWO fixes, both GENERAL parser/checker
correctness that reproduce minimally (unlike the recent whole-program Blocker #3 families).
Dashboard: compiler 177 → 176 (−1), services 282 → 280 (−2), server 488 → 485 (−3), harness
704 → 701 (−3). Suite 9,619 → 9,628 (+9 local across 2 new test files, 0 regressions); 2 fix
commits (4c47c918 string-index / 0db79740 tuple).**
- **Fix 1 (4c47c918, string-index element access):** a numeric-literal element access on a
  STRING-typed receiver — `(arr[i])[0]` where the inner element access is typed `string`
  (tsc's own jsTyping.ts `pathComponents[pathComponents.length - 3][0] === "@"`) — FP-fired
  "Property '0' does not exist on type 'string'." The B292 bail in the element-access
  missing-member path (checkMemberAccessMissing's caller ~118808) suppressed only NON-numeric
  string keys (`s["s"]`); a numeric-literal key `[0]` (and a numeric-looking string key
  `["0"]`) fell through to the missing-member check. A plain identifier receiver `str[0]`
  resolved elsewhere, but an element-access-typed receiver reached this path. Element access on
  a string primitive is never TS2339 in tsc (String has a numeric index sig; the TS2339
  property-existence path is property-access only), so broadening the bail to any literal key
  is FP-safe. Cleared jsTyping.ts:258 (services/server/harness).
- **Fix 2 (0db79740, optional-tuple assignability):** `return emptyArray as []` against an
  all-optional tuple `readonly [kind?: T, specifiers?: T, …]` (tsc's own moduleSpecifiers.ts)
  FP-fired TS2739 "Type '[]' is missing … 0, 1, 2". The parser DISCARDS a tuple element's `?`
  token AND label from the element node (no NamedTupleMember/OptionalType is produced — `[T?]`'s
  `?` is eaten by parseType's trailing-`?` JSDoc-recovery), so the resolved tuple Type marked
  every numbered member REQUIRED and its `length` a fixed literal. Fix spans parser + checker:
  (a) the parser records per-element optionality on the new `TupleType.elementOptional` metadata
  field — a named `?` (`[a?: T]`) in the labeled branch, an unnamed `[T?]` bridged via the
  `tupleElementConsumedOptionalMarker` flag set where the recovery consumes it (read+reset per
  element so a nested tuple's marker does not leak); (b) `getTupleType` marks optional members
  in the new `optionalTupleMemberIds` side-channel (consulted by `isOptionalProperty`, since
  tuple member symbols carry no declaration) and sets an optional-containing tuple's `length`
  to the union `minLength..maxLength` (so `[]`'s length 0 relates to `[a?, b?]`'s `0 | 1 | 2`).
  FP-safe: a required-leading `[a, b?]` and an all-required `[a, b]` still error; no element
  node kind changes, so no type-node walker is affected. Cleared moduleSpecifiers.ts:344 (all
  profiles — src/compiler is shared).
- **INVESTIGATED & REVERTED (dashboard no-op):** the `(x || (x = [])).push(v)` empty-array /
  UNION-target contextual typing (round-408 gap for property/union targets). Extending
  `contextualAssignmentRhsType` to pick an Array-family member from a union target fixed the
  IDENTIFIER inline case `(x || (x = [])).push(v)`, but the real tsc-source FPs are PROPERTY
  targets (binder.ts:1375 `(label.antecedent || (label.antecedent = [])).push(antecedent)`) and
  the `??=` IIFE-call-return case (core.ts:2135) — both resolve the `.push`/call receiver in the
  call-type pass via a path the fix doesn't reach (the split `const r = …; r.push()` form IS
  fixed, confirming the `||` result typing works, but the inline call FP persists). 0 dashboard
  FPs cleared → reverted to avoid churn. The inline-property-receiver call-type resolution is
  the follow-up if this family resurfaces.
- **NEXT (services @ 280, all deep/whole-program or M3.4):** deep-M3 TS2322×154 fragments
  (`Type 'X' is not assignable to type 'Y'`, max bucket 5 — Node[]/generic-fn-identity/
  SourceFileLike-conflation); the whole-program cross-file `declare module` augmentation
  method-guard family (`isUnion()`/`isStringLiteral()`, Blocker #3); the `length = end - start;
  length - 5` cross-statement reassignment arithmetic-narrowing residual (parser.ts:8911 /
  core.ts:6639, M3.4 — the arithmetic pass has no reassignment narrowing); the evolving-let
  `indexInfos` CFA cluster (TS2454 + TS7034 + TS7005, symbolDisplay.ts).


**Round 451 (2026-07-09) — bounded FP burn-down: FOUR fixes, one a GENERAL parser correctness
fix that reproduces minimally. Dashboard: compiler 178 → 177 (−1), tsc-cli 179, jsTyping 176,
deprecatedCompat 179, typingsInstallerCore 176, services 291 → 282 (−9), server 498 → 488 (−10),
harness 715 → 704 (−11). Suite 9,619 corpus green / 0 fail / 3 skip + 14 local across 4 new test
files; 4 fix commits (dfb5b2c2 / cebd023d / a00c798b / fd7700ee).**
- **Fix 1 (dfb5b2c2, PARSER — type-predicate keyword-subject; the general one):** a user type-guard
  whose PARAMETER is named with a type keyword — the pervasive `function isTransientSymbol(symbol:
  Symbol): symbol is TransientSymbol` in tsc's own sources — never narrowed, so
  `if (isTransientSymbol(sym)) sym.links` FP'd TS2339. The predicate SUBJECT is grammatically a
  parameter NAME, but the parser reaches it via `parseType`, which turns a keyword-like name
  (`symbol`/`string`/`object`/…) into a `KeywordTypeNode` instead of an Identifier, so the checker's
  `predicateParamName` extraction (which handles Identifier/TypeReference/ThisType) returned null and
  the guard was silently ignored. Fix in `parsePrimaryTypeOrHigher`'s predicate branch: when the
  parsed subject is a KeywordTypeNode and `is` follows, rebuild it as an `Identifier` from the keyword
  text (`keywordTypeKindText`). The asserts path already handled this via `isIdentifier()`. Cleared the
  isTransientSymbol(symbol) family (symbolDisplay ×4 / signatureHelp ×1). **KEY: this REPRODUCES
  minimally** (a single-file `declare function isD(symbol: Base): symbol is Derived; if (isD(x))
  x.links` FP'd) — the round-450 note wrongly grouped it with the deferred whole-program family. The
  companion `isUnion()`/`isStringLiteral()` cross-file `declare module` augmentation method-guard
  (Type.types/Type.value) was re-confirmed genuinely whole-program-only (a minimal 2-file `declare
  module` augmentation repro is CLEAN) — Blocker #3, still deferred.
- **Fix 2 (cebd023d, LIB Object.entries):** `entries(o: any): any[]` → `entries<T>(o: any): any[]` so
  tsc's `Object.entries<string>(result.config)` (jsTyping) type-checks (was TS2558 "Expected 0 type
  arguments, but got 1"). Return kept `any[]` for zero downstream-inference change.
- **Fix 3 (a00c798b, CFA typeof-switch exhaustiveness):** `switch (typeof value)` covering the
  subject union's ACTUAL possible tags (`value: string | number | PseudoBigInt` → string/number/object)
  is exhaustive (tsc narrows the subject to `never` after them), so a value-returning function needs
  no trailing return — tsc's own utilities.ts `hasValue` FP'd TS2366. The CFA check previously only
  recognized a switch covering ALL 8 typeof strings; it now also accepts the subject's own tag set
  (new `typeofTagsOfType` / `typeofSwitchSubjectType`, subject resolved from the param annotation via
  `currentFunctionParams` — `getTypeOfExpression` returns `any` for a param in the CFA pass). FP-safe:
  bails to null on any uncertain constituent (any/unknown/type-param/enum). Only the TS2366/TS7030
  path — the separate TS7027 `isDefinitelyTerminating` stays strict.
- **Fix 4 (fd7700ee, LIB Set set-ops):** the ES2024/ES2025 Set methods (union/intersection/…,
  lib.es2025.collection.d.ts in the pinned tsc) are now LIB_MIN_TARGET-gated at ESNext (our top
  ScriptTarget — no ES2025): absent at the self-compile's es2020 lib (where tsc's core.ts
  `const set: Set<T> = { has, add, … }` shim satisfies Set, was TS2740 "missing union, …"), present at
  the setMethods test's @target esnext. No corpus `Set<>` missing-property display and tsc calls none
  of the methods → nothing shifts. Cleared core.ts across ALL profiles (compiler-file family).
- **NEXT (services @ 282, all deferred/whole-program):** the `isUnion()`/`isStringLiteral()` cross-file
  augmentation method-guard family (Type.types ×3 / Type.value, Blocker #3); the `isTransientSymbol(
  symbol.links.target)` CHAIN residuals (property-access-path narrowing); deep-M3 TS2322×154 fragments
  (max bucket 3); the `[] → all-optional-tuple` TS2739 (moduleSpecifiers.ts — tuple-element optionality,
  needs the annotation AST node); the `.map`-returns-tuple-array contextual-return M3.2 slice.

**Round 450 (2026-07-09) — bounded FP burn-down: FIVE suppression-only / soundness fixes,
all cleanly reproducible in isolated repros + locally tested (5 new test files, 20 tests).
Dashboard: compiler 183 → 178 (−5), services 310 → 291 (−19), server 518 → 498 (−20),
harness 735 → 715 (−20). Suite 9,605 corpus green / 0 fail / 3 skip; 6 fix commits
(21f41105 / 2da83a26 / 4d443ae9 / 2c202d67 / 134f9d7a / 4ad6f1b7).**
- **Fix 1 (21f41105, boolean-literal assign; TS2322 −3 services):** a `true`/`false` literal
  assigned to a literal-boolean local (`let isSnippet: true | undefined; isSnippet = true`,
  completions.ts). A boolean literal parses as an `Identifier` (literalTypeOfExpression), so it
  was NOT covered by the `tryCatchFinallyControlFlow` guard that skips the legacy varTypes
  string-fallback for numeric/string/bigint literal RHS — the fallback widens `true`→"boolean"
  and `isAssignableTo("boolean", "true | undefined")` fails even though the engine already
  validated it (keeping the literal via propTypeContainsLiteral). Extend the guard to
  `true`/`false`. FP-safe (canUse && isAssignable gate); negative controls fire via the engine.
- **Fix 2 (2da83a26, leaked-module-var assignment TARGET; TS2740 −3 + TS2322 −3 services,
  compiler −2; Blocker #3):** `parent = parent.parent` inside a NESTED block where
  `let parent = node.parent` shadows navigationBar.ts's module-level `let parent: NavigationBarNode`
  (leaked into globals per round 442). applyBodyLocalShadowing only scans function-body TOP-LEVEL
  statements, so a nested `let parent` isn't in currentShadowedNames — the assignment target then
  resolved to the leaked `NavigationBarNode` annotation and FP'd TS2740 ('Node' missing its props).
  Skip the globals lookup in the assignment-target resolution for a moduleFileLocalVarNames name
  not owned by this file (currentFileLocals). FP-safe (a cross-file leaked var is TS2304 in tsc);
  the own-file binding is still checked.
- **Fix 3 (4d443ae9, `x = x || DEFAULT` narrowing; TS2345 −4 services):** the default-init idiom
  `maximumLength = maximumLength || defaultMaximumTruncationLength` (utilities.ts) didn't narrow a
  `T | undefined` reference to non-nullish `T`. `rhsIsDefinitelyNonNullish` (consulted by
  narrowByAssignmentRhs → narrowByExcludingNullUndefined(declaredType), round 416) didn't classify
  a `||`/`??` RHS. `a || b`/`a ?? b` are non-nullish iff the RIGHT operand is; the right operand is
  usually a const/local reference so its type is resolved and nullish-checked. FP-safe: a nullish
  right operand keeps the reference nullable (pinned by a control).
- **Fix 4 (2c202d67, `while (true)` definite-assignment; TS2454 −2 compiler / −2 services):** a
  `while (true)` loop's only normal exit is a `break`, so a var assigned before EVERY exiting break
  is definitely assigned after it (scanner.ts scanTemplateAndSetTokenValue idiom). The set-based
  `markAssignments` had no WhileStatement case; add a constant-true case backed by a sound
  single-variable definite-assignment walk (sequential flow, if/else join, abrupt break/return/
  throw/continue). Bails conservatively on any labeled break/continue or a try/labeled statement
  that could hide an exiting break; nested loops/switch opaque. Only `while (true)`. Controls: a
  non-assigning break, `while (cond)`, and a break-in-try all keep firing.
- **Fix 5 (134f9d7a, for-of/for-in shadow; TS2454 −1 compiler / −1 services):** a `for (const X
  of/in …)` loop variable shadowing an outer uninitialized `let X` and bound each iteration
  (generators.ts `let variable; for (const variable of decls) { …variable.name… }`). The
  flow-based used-before-assigned pass descended into the loop body with the outer var still in
  its set — drop loop-declared names (incl. binding patterns) when descending (mirrors the
  catch-body shadowing skip). Controls: outer read outside the loop, and a non-shadowing for-of,
  both fire.
- **Fix 6 (4ad6f1b7, destructured-const shadow of a leaked module var; TS2339 −3 services;
  Blocker #3):** `const { parent } = node` shadowing a module-file `let parent:
  NavigationBarNode` (leaked into globals per round 442) is not bound (B83.5), so a body read
  of `parent` resolved to the leaked outer decl and FP'd `parent.operatorToken` (navigationBar.ts
  getFunctionOrClassName). applyBodyLocalShadowing handled only a simple `let x`; it now also
  registers a `const { … } = init` object pattern's binding names as shadowed and records each
  from the destructured property type of `init` (getPropertyOfType), so the shadowed-name bail
  suppresses a valid destructured-type member access and a following user type-guard
  (`isBinExpr(parent)`) narrows the right type. FP-safe: a genuinely-missing member still fires
  (through the module-var fallback — display divergence only, not an FP).
- **INVESTIGATED & DEFERRED (do not re-chase minimally):** the `Symbol.links` (isTransientSymbol
  narrow-DOWN ×5), `Node → NavigationBarNode` TS2339 (navigationBar.ts's own `const { parent } =
  node` destructured-shadow of the module var, B83.5), and `Node → PropertyAccessExpression` TS2740
  (fixMissingCallParentheses) families all PASS in isolated multi-file repros (incl. barrel imports)
  — they need whole-program conflation/leak context; instrument the services profile, not a minimal
  repro. The `.map`-returns-tuple-array TS2322 (`map(refs, f => [sf, f])` vs `readonly [A,B][]`)
  needs contextual return inference (M3.2). The last `indexInfos` TS2454 is the flow graph's
  if-else-both-assign-then-loop gap.
- **NEXT (services @ 294):** the whole-program narrow-DOWN family (Symbol.links / Type.types) via
  services-profile instrumentation, the `.map` contextual-return M3.2 slice, deep-M3 TS2322/TS2345
  relation fragments.

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
