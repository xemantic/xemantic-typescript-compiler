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

**Round 471 (2026-07-10/11, the services burn-down continues) — NINE bounded fixes in 9 commits
(63287e70 / 3f5166da / 3766ef73 / 8524afe4 / 093df3f1 / 39b0fddf / c7781e76 / f9a81674 / bd70f5d6). Dashboard: services 72 → 56 (−16; TS2322 12 → 3, TS2345 5 → 2, TS2349 2 → 0, TS2741 2 → 0,
TS2420 1 → 0; 10 real left excl. TS2591×43 + TS2304×2 `global` + TS2584 console); every fix
repro-verified both directions before landing. Suite 9,958 → 9,987 (+29 local across 8 new test
files, 0 regressions).**
- **Fix A (63287e70, array-literal literal elements):** a fresh array literal keeps its elements'
  literal types in ARRAY-LIKE contextual positions — literalTypeOfExpression's new
  ArrayLiteralExpression arm gated on the `arrayCtx` flag (an ungated arm regressed
  assignmentIndexedToPrimitives: `const n4: 0 = [0]` must stay `number[]`), the
  one-literal-arm ConditionalExpression relaxation, propTypeContainsLiteral's Array/ReadonlyArray
  Reference arm, and the same rule in the per-element walkers (a wrong literal element now
  displays as its literal `"q"` like tsc). Cleared services.ts:1585 + organizeImports:216 +
  (bonus, with C/E) services.ts:1327 ObjectAllocator and completions:1922.
- **Fix B (3f5166da, optional-read objlit write target):** an objlit member whose value was an
  OPTIONAL-member read is `T | undefined` in tsc (the round-424 optionality-is-a-symbol-attribute
  gap) — objLitMemberValueWasOptionalRead widens the WRITE target only. Cleared organizeImports:115.
- **Fix C (3766ef73, boolean || truthy facts):** `preferences.includeCompletionsWithSnippetText ||
  undefined` is `true | undefined` (tsc getTypeWithFacts Truthy); gated when the RIGHT side is
  boolean-like so `boolA || boolB` stays `boolean` (our getUnionType has no subsumption).
  Cleared completions:2299.
- **Fix D (8524afe4, enum VALUE-domain exhaustion):** an enum ALIAS member
  (`NameContainsNonURISafeCharacters = NameContainsInvalidCharacters`) counts as covered when a
  covered member has an EQUAL value — tsc's case narrowing removes every member with that value.
  Cleared jsTyping:414 (the barrel-assertNever family's last member).
- **Fix E (093df3f1, intersection call signatures):** getCallSignaturesOfType concatenates a
  Type.Intersection's constituents' sigs (tsc getSignaturesOfStructuredType) — a `Fn & Fn` union
  member read as non-callable and FP'd TS2349. Cleared textChanges:706 + callHierarchy:263.
- **Fix F (39b0fddf, lib-phantom members, Blocker #3):** a module file's own top-level
  `interface Symbol` merges with the same-named LIB global, demanding es2019's `description` —
  isLibPhantomMemberOfModuleInterface (moduleInterfaceNames + lib-only prop declarations) skips
  such members in propertiesRelatedTo / collectMissingProperties / getMissingRequiredPropertySymbol
  / the TS2420 loop. FN-not-FP; script-file lib augmentations don't trip the gate. Cleared
  services.ts:656 (TS2420) + :725 (TS2345).
- **Fix G (c7781e76, nested guard shadows global, M1.11):** fixMissingTypeAnnotationOnExports
  nests `isConstAssertion(location): location is AssertionExpression` while compiler/utilities
  exports a NON-guard `isConstAssertion` — the merged-globals hit killed the narrowing.
  resolveFlowCalleeDecl prefers the unique predicate-bearing nested decl of the CURRENT file
  (nestedFnDeclFiles, per-file batches in buildNestedFunctionMap) over a non-predicate globals
  hit; the negative pin proves the guard fires (the still-firing error displays the NARROWED
  type). Cleared fixMissingTypeAnnotationOnExports:452 ×2.
- **Fix H (f9a81674, flow-narrowed inference anchors, M3.1):** `focusLocations &&
  flatten(focusLocations)` read the RAW nullable union for inference (the (k) tp[][] anchor
  soft-skipped, T unbound) while the arg CHECK narrowed — the (k)/(l) anchors now read
  Identifier/PropertyAccess args' flow-narrowed type under the objLitValueNullishStrip gate.
  The negative pin proves T binds (the return relates as TextSpan[] and fails string[]).
  Cleared mapCode:55.
- **Fix I (spread-of-target + declared extras):** `fix = { ...fix, ...(addAsTypeOnly ===
  undefined ? {} : { addAsTypeOnly }) }` FP'd — the B426 spread merge keeps only GUARANTEED props.
  objectLiteralSpreadOfTargetSatisfies suppresses at the assignment path when a spread's
  (flow-narrowed) type relates to the target and every extra key is declared in SOME target union
  member with a relating value type (conditional-spread arm values read their flow-narrowed type).
  Undeclared keys / wrong-typed extras keep firing. Cleared importFixes:316 + :374.
- **Scouted, deferred with findings:** completions:2237 (a faithful nested-fn repro with
  isIdentifier + identifierToKeywordKind passes — whole-program probe needed);
  program.ts:1088 ResolutionLoader<T> (a faithful generic-loader repro passes — probe);
  emitter:994 `transformed[0]` (round-468 finding stands — probe); signatureHelp:379 +
  findAllReferences:1000 (+ the completions family) = ONE family: a nested objlit member with an
  ENUM-member discriminant (`invocation: { kind: InvocationKind.Call, node }`) vs a
  kind-discriminated union member — needs the round-411 canonical-key space wired into
  objlit-vs-union member selection; importFixes:316/374 = `fix = { ...fix, ...(cond ? {} :
  { extra }) }` — a spread-of-target-typed-value + target-declared extras rule (design sketched:
  first spread's narrowed type relates to target + every extra key declared in SOME union member
  with a relating value type); symbolDisplay:917/935 TS7034/7005 (the evolving-any model, round-470b
  finding stands); utilities:1750 TS2538 (binarySearchKey keySelector-return inference).
- **Round 471b PERF episode (same session, caught by the bench row): the nine fixes had doubled
  the services self-compile (39 → 92 s) — bisected to TWO independent costs, both fixed, final
  41.5 s at the same 56 diagnostics.** (1) Fix G's `HashMap<FunctionDeclaration, String>` file map:
  AST nodes are data classes, so every lookup DEEP-HASHED the whole function subtree, quadratically
  re-checked per file (+10 s alone) → replaced by cluster-order-aligned file LISTS written during
  the per-file walk (see the new CLAUDE.md Kotlin-idioms gotcha). (2) Fix F's propertiesRelatedTo
  hook removed the missing-prop EARLY EXIT from every relation against a lib+module-merged
  interface (Symbol), re-running full member-type comparisons (39 → 77 s measured at the F commit)
  → the relation-hook skip is now gated to a CLASS-instance source (the SymbolObject family —
  exactly the FP's shape) and the phantom-member sets are memoized per symbol id
  (libPhantomMembersCache). Lesson recorded: run the bench row BEFORE the docs commit, not after
  the round wraps.
- **NEXT (services @ 56, 10 real):** the nested-objlit enum-discriminant family
  (signatureHelp:379 / findAllReferences:1000); completions:2391 TS2740 EmitTextWriter (objlit of
  any-typed fn members vs a 22-member interface — probe why the missing-set lists 18); the
  whole-program probe batch (emitter:994 / program.ts:1088 / completions:2237 /
  documentHighlights:193); symbolDisplay:917/935 evolving-any (needs the model — round-470b
  finding); utilities:1750 TS2538 (keySelector-return generic inference).**

**Round 470b (2026-07-10, the services burn-down continues) — ELEVEN bounded fixes across 9 commits
(a PARALLEL session's perf commit — round 470 below — was rebased in mid-stream). Dashboard:
services 86 → 72 (−14; every step strictly-removals by listAll diff; bench row 39.8 s self,
−7.8% riding the perf commit). Suite 9,920 → 9,958 (+38 local across 9 new test files, 0
regressions); commits 4df5b318 / b7c5b152 / 51a2c96c / c3c6813d / 13240cb2 / 03364f91 /
1c8cd83a / e23b1d79 + the fix-10 commit.**
- **Fix 1 (4df5b318, keyof typeof Enum):** `typeof Enum` washes to anyType so `keyof` gave
  `string | number | symbol` — keyofTypeQueryEnumMemberNames builds the member-NAME literal union
  (barrel aliases via the memoized flow-only resolveImportedEnumSymbol; the merged symbol's
  declarations are POLLUTED with ImportSpecifiers, so the value-merge bail tests specific kinds,
  never "anything non-enum"). Cleared navigateTo:188.
- **Fix 2 (b7c5b152, union member with string index sig):** resolveMemberPropertyType falls back
  to the apparent Type.Object's stringIndexInfo value — `settingsOrHost.getCompilationSettings` on
  `CompilerOptions | MinimalResolutionCacheHost` resolves through CompilerOptions' index sig
  (fixes BOTH the emission and property-TYPE sites, the round-419 lesson). Cleared
  documentRegistry:222.
- **Fix 3 (51a2c96c, TS2366 param-member discriminant):** paramMemberChainType resolves a switch
  receiver `<param>.parent` through the param's annotation (the CFA pass has no param scope), so
  `switch (constructorDeclaration.parent.kind)` over `ClassDeclaration | (ClassExpression & {…})`
  proves exhaustive. Cleared convertParamsToDestructuredObject:683.
- **Fixes 4+5 (c3c6813d, this-guard pair):** resolvePropertyMethodDecl resolves a NULLISH union
  receiver through its sole non-nullish member, and the round-444 this-guard UNION bail is relaxed
  to a shared-decl test (a `guard() && cond && read` branch join unions base + subtype, both
  inheriting the guard). COMPANION caught by the local negative pin: resolvedCallReturnTypeForFlow
  bails on an optional-chained CALLEE (`factory?.make(t)`). Cleared stringCompletions:641/642 ×3
  + goToDefinition:513.
- **Fix 6 (13240cb2, this.prop assignment ctx):** the implicit-any walker tracks the enclosing
  class's members so `this.skipTrivia = skipTrivia || (pos => pos)` resolves the arrow's context
  from the property annotation (getTypeOfExpression(this) is anyType per B101). Cleared
  services.ts:1318 TS7006.
- **Fix 7 (03364f91, OR-return left narrowing):** a returned `X || undefined` / `X ?? y`
  recombines with the guard-narrowed LEFT reference via combineBinaryTypes (monotone). Cleared
  sourcemaps:212.
- **Fix 8 (1c8cd83a, nested-block const shadows param, M1.11):** a let/const in a NESTED block
  colliding with a PARAM registers anyType in the call-types walker (top-level body decls keep the
  param-wins redeclaration rule — functionArgShadowing). Cleared importFixes:1967/1970 ×2.
- **Fix 9 (e23b1d79, sibling-discriminant optional-member arg):** the optional-member-arg TS2345
  emitter suppresses when the discriminant-NARROWED receiver declares the member REQUIRED
  (`case SyntaxKind.MethodDeclaration: … fd.name` — the guard tests the SIBLING `.kind`, so the
  path-keyed access narrowing can't see it). Cleared convertParamsToDestructuredObject:475 +
  fixUnreferenceableDecoratorMetadata:70.
- **Fix 10 (flow-verified return precise verdict):** a flow-narrowing-VERIFIED return source
  early-returns from the engine block instead of falling to the STRING fallback (the round-436c
  trap) — `if (typeof target === "string") return target;` with `target: unknown` had the engine
  pass (the M1.9 if-arm machinery had already overwritten currentLocalTypes to `string`) but the
  varTypes string still said "unknown" and re-FP'd. TWO precise-verdict arms: the substitution
  sites set a `sourceNarrowVerified` flag, and a name present in narrowedDeclaredTypes whose
  raw resolved type relates counts too (PROBE470 found raw was ALREADY `string`). Never a blanket
  engine-confirmed return. Cleared stringCompletions:1133.
- **Scouted, deferred with findings:** symbolDisplay:917/935 TS7034/7005 is tsc's TWO-PART
  evolving-any model (declaration-site TS7034 + per-reference auto-flow via
  isPastLastAssignment/flowContainer-extension, tsc checker.ts ~30260/31170) — a faithful fix
  needs the evolving-type model, not a walker tweak (static derivation could NOT reproduce why
  f9/f10 error while symbolDisplay doesn't). utilities:1750 TS2538 needs binarySearchKey's U
  inferred from the keySelector RETURN (generic inference). textChanges:706/callHierarchy:263
  TS2349 look like augmentation-merge method resolution (Blocker #3 deep). completions:2237 +
  documentHighlights:193 need probes (fn-typed-param sig alignment suspected for the latter).
- **NEXT (services @ 72, 26 real):** the objlit giants (services.ts:1327 ObjectAllocator /
  completions:1922/2299/2391 / importFixes:316/374 / signatureHelp:379 / findAllReferences:1000 —
  nested objlit member context, likely one family); organizeImports:115/216 (optional-member
  reads need `| undefined`, broad); program.ts:1088 ResolutionLoader<T>; services.ts:1585
  string[] vs keyof-literal-union (Object.keys typing); fixMissingTypeAnnotationOnExports:452 ×2
  (Node vs Expression brand); services.ts:656/725 SymbolObject implements Symbol (TS2420);
  mapCode:55 flatten gate-(k) probe; jsTyping:414 barrel assertNever; emitter:994 whole-program
  probe; symbolDisplay TS7034/7005 (needs the evolving-type model — see the deferral above).**

**Round 470 (2026-07-10, user-directed 3-way benchmark + profile session, M5) — `localTypeAliasIndex`
(Tier 1): [findLocalTypeAlias]'s per-call whole-file AST rescan replaced by an eager per-file
first-wins DFS index (Checker.kt, declared before `init` per the init-order trap). Self-compile
(compiler profile) wall 23.5 s → 18.6 s median of 3 (−21%), diagnostics byte-unchanged (46).
Suite 9,920 → 9,925 (+5 local, LocalTypeAliasIndexTest, 0 regressions).**
- JFR profile (scripts/aggregate_jfr.py, 1,211 samples @ stackdepth=1024): Checker 74% inclusive
  (Parser 4% / Transformer 4% / Scanner 1.4%). `findLocalTypeAlias$scan` was the #1 SELF-time
  method (~10% of samples incl. callers): `discUnionParamMembers` (the TS2488 exhaustive-default
  never-destructure walker) called it for EVERY bare-TypeReference-annotated param of every
  function → O(fileSize × functions × params) on checker.ts. Remaining hot after the fix, for a
  future M5 session: stdlib collection churn ~30% of samples (per-function-body map snapshot
  COPIES — `HashMap.putMapEntries` via checkFunctionBody / spread2698Stmt / the arithmetic+
  call-types walkers — a layered-scope or mark/pop-log refactor target), flow walkers ~12%
  (already budget/memo-optimized), property-access pass ~8%.
- 3-way benchmark (same materialized `tsc-project-637d5746`, identical tsconfig, 3 cold runs each,
  macOS arm64 8-core): **tsgo 7.0.0-dev 1.3 s / 546 MB; original tsc 6.0.3 (JS) 6.6 s / 654 MB;
  xtsc post-fix 18.6 s / ~1.2 GB** (pre-fix 23.5 s; was ~3.6× JS-tsc, now ~2.8×). tsc and tsgo
  agree BYTE-IDENTICALLY on 65 env-legit errors (offline, no @types/node); xtsc emits 46 of the
  same family — **0 FPs, 19 FNs** on this profile. JFR shows ONE application thread does all
  compiling (user/wall ≈ 2.8 is GC/JIT) — M5.4 parallelism is the other ~3× of the tsgo gap.
- Incidental finding (not fixed): `scanExhaustiveSwitchDefault` does not descend ModuleDeclaration
  bodies, so the TS2488 walker never fires inside namespaces (pre-existing; the index covers
  namespace-nested ALIASES like the replaced scan did).

**Round 469 (2026-07-10, same session as 468) — two deeper Blocker-#3/flow rules. Dashboard:
services 90 → 86 (−4); both steps strictly-removals by listAll diff. Suite 9,914 → 9,920
(+6 local across 2 new test files, 0 regressions); 2 fix commits (429785e8 / 7af1415f).**
- **Fix 15 (429785e8, type-alias-SHADOWED interface targets, Blocker #3; 90 → 87):** round 443's
  SourceFileLike conflation closed for the OBJLIT-TARGET direction — importTracker's
  `type SourceFileLike` wins the last-wins Interface+TypeAlias merge, so annotations in OTHER
  files resolve to the bogus alias union. tsc's true target is the MERGED interface: the
  compiler/types.ts base PLUS services' `declare module` AUGMENTATION members (the cross-file
  augmentation member merge our symbol tables don't model).
  objectLiteralSatisfiesMergedConflatedAliasInterface assembles the merged member table AST-side
  (top-level + module-augmentation `interface X`; required = required anywhere) and demands full
  required coverage + no excess; hooked into the return AND var-decl paths. Cleared
  sourcemaps:232, textChanges:1339 + convertToAsyncFunction:166 as a bonus (Transformer is the
  same shape).
- **Fix 16 (7af1415f, closure-assigned definite assignment; 87 → 86):** a name assigned inside
  a NESTED function-like can be assigned at any time relative to an outer read (fn decls hoist,
  closures run first) — tsc's definite-assignment never fires for it (formatting.ts
  formatSpanWorker's `previousRange`, assigned only inside the nested processRange/processPair
  helpers, read in the trailing-edit block ABOVE their declarations). Both TS2454 passes (the
  SET-based checkUsesOfUninitialized driver AND the flow-graph runFlowTS2454OnFunction — the
  round-450 two-pass gotcha in action: the first cut fixed only the flow pass and the set pass
  kept firing) remove closure-assigned candidates via a shared collectClosureAssignedNames
  (AST-based, reusing B78.2's collectAllAssignmentsAnywhere on nested bodies). **PERF LANDMINE
  (caught pre-commit): the first cut text-scanned every nested container range per STATEMENT —
  the services bench went 42 s → 5-min timeout on checker.ts's ~3000 nested fns; the AST
  pre-pass restored the normal band (42.4 s).** Straight-line use-before-assign keeps firing
  (negative controls pinned).
- **NEXT (services @ 86, 40 real):** unchanged from the round-468 list minus the four cleared.**

**Round 468 (2026-07-10) — the conflated-interface family closed out + contextual narrow-DOWN
completions: FOURTEEN fixes in 12 commits. Dashboard: services 108 → 90 (−18; TS2322 30 → 18,
TS2345 12 → 9, TS2339 6 → 5, TS2554/TS2739 → 0; 44 real excl. TS2591×43 + TS2304×2 `global` +
TS2584 console); every step verified strictly-removals by listAll diff. Suite 9,877 → 9,914
(+37 local across 12 new test files, 0 regressions).**
- **Conflated-interface variants (Blocker #3, fixes 1/2/6/7):** the round-445 rule now covers a
  TERNARY arm (checkConditionalReturnBranches; fixExpectedComma:57 + importTracker:758), an
  `&&`-nested right operand (the result is falsy(LEFT)|RIGHT — nullish LEFT members must relate,
  definitely-truthy members contribute nothing; addMissingAwait:251), the VAR-DECL path
  (convertToEsModule:130), a NESTED member whose declared type names a conflated interface the
  file also declares (objectLiteralMatchesViaNestedConflatedMember — importTracker:644
  ExportedSymbol.exportInfo), and the ARG position where the calling file merely IMPORTS the
  conflated name (objectLiteralMatchesSomeConflatedDeclaration: exact satisfaction of SOME
  declaring file's version, FN-not-FP direction; findAllReferences:1316).
- **Contextual narrow-DOWN completions (M3.4, fixes 3/4/5):** the round-462 monotone
  ctxAcceptsNarrow rule extends to SHORTHAND values (convertArrowFn:258 getVariableInfo) and
  ARRAY-LITERAL property values via narrowedArrayLiteralType (convertToOptionalChain:157 +
  convertStringOrTemplateLiteral:157); the return-path objlit CONTEXT reaches `&&`/`||`/`??`-nested
  right operands (contextualType is live while the binary evaluates — inlineVariable:163).
- **Nullish/literal triple (fixes 8-10, one commit):** `??`-literal ABSORB in combineBinaryTypes
  (a literal right ∈ the left's nullish-stripped literal union drops the widened primitive —
  organizeImports:954); the arg per-prop leaf routes through widenOptionalTargetPropType (SIXTH
  site — returnValueCorrect:264); the assignment-RHS narrowing gate accepts ENUM-object targets
  (importFixes:572 QuotePreference + services:1641 LanguageServiceMode as a bonus).
- **Fix 11 (lib):** RegExpConstructor mirrors the real es5 TWO-OVERLOAD shape (`new(pattern:
  RegExp | string)` + `new(pattern: string, flags?)`) — a single union param went SILENT on
  `new RegExp(42)` (non-simple param skips the conservative arg check); the overload shape keeps
  it erroring as TS2769 (services:2876 `new RegExp(/\S/)` cleared).
- **Fix 12 (M1.11):** for-of loop-var binding names (incl. destructured elements) shadow
  same-named functions in the arity walker — mapCode.ts's `for (const { parse, body } of
  nodeKinds)` vs the file's own 2-param `function parse` FP'd TS2554.
- **Fix 13 (M3.1):** a CALL-EXPRESSION arg carrying an un-inferred FOREIGN TP skips the
  single-sig arg check (the overloadArgSkippable rule at the single-sig site; gated to call args
  so own-TP identifier args keep corpus-pinned checks — codeFixProvider:94 `cast(...)` → TOut).
- **Fix 14 (M1.12):** a NUMERIC key resolves through an ARRAY-LIKE intersection constituent's
  number index in resolveMemberPropertyType — `Array.isArray(diag) ? diag[0] : …` intersects
  union members with `readonly unknown[]` (utilities:4062).
- **Scouted, deferred with findings:** organizeImports:115/216 need optional-member READS to
  include `| undefined` (the round-424 optionality-is-a-symbol-attribute gap — broad);
  emitter.ts:994 `transformed[0]` is whole-program-only (minimal repro clean — probe);
  fixUnreferenceableDecoratorMetadata:70 + convertParamsToDestructuredObject:475 are
  kind-discriminant narrowing not reaching property-access args (probe); jsTyping:414 is the
  known-hard barrel assertNever family; signatureHelp:379 needs nested-union-member objlit
  context; findAllReferences:1000 needs array→objlit→nested-objlit ctx propagation.
- **Cross-profile (measured at session end, accumulated since the round-458 measurement):**
  server 485 → 275 (−210), harness 701 → 481 (−220), compiler stays 46 (zero real FPs —
  all env-legit). TSV rows recorded for services/compiler/server/harness.
- **NEXT (services @ 90, 44 real):** the services.ts objlit giants (ObjectAllocator ×2 /
  CompletionEntry / EmitTextWriter TS2740); completions:1922/2237/2299/2391; importFixes:316/374
  (ImportFixWithModuleSpecifier); stringCompletions `.types`/`.value` public-API this-guards;
  sourcemaps:212/232 + textChanges:1339 (SourceFileLike objlits); symbolDisplay:917/935
  TS7034/7005; formatting:572 TS2454; utilities:1750 TS2538; goToDefinition:513.**

**Round 467 (2026-07-10, same session as 466) — the services burn-down starts: FIVE bounded
fixes. Dashboard: services 126 → 108 (−18; TS7006 7 → 1, TS2339 8 → 6, TS2322 40 → 30); every
step verified strictly-removals by listAll diff. Suite 9,863 → 9,877 (+14 local across 5 new test
files, 0 regressions); 5 fix commits (d9f0ecab / cfa323d0 / 843f8ce9 / f06586f8 / e1c54010).**
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
- **Fix 4 (f06586f8, guard-narrowed array-literal returns, M3.4; 116 → 111, −5):**
  `narrowedArrayLiteralType` re-types an array literal from its flow-narrowed
  Identifier/PropertyAccess elements; the direct-return path and the ternary-arm path substitute
  it ONLY when it makes the return relation pass (monotone) — getTypeOfArrayLiteral's own
  element narrowing deliberately accepts only nullish strips (round 459's shadowing hazard), so
  `if (isThrowStatement(node)) return [node];` built `Node[]` vs
  `readonly ThrowStatement[] | undefined`. The 2 targeted documentHighlights.ts sites PLUS
  jsDoc.ts:238 and extractSymbol.ts ×2 generalized.
- **Fix 5 (e1c54010, `||`-nested conflated objlit returns, Blocker #3; 111 → 108):** the
  round-445 note's prescribed extension — `return noSymbolError(name) || { exportNode, … }`
  (convertExport.ts ×3): the RIGHT object literal routes through
  objectLiteralMatchesConflatedFileLocalInterface (this file's OWN `interface ExportInfo`, not
  the cross-file merged pollution), the LEFT operand's non-falsy type must relate on its own;
  `??` covered too. Suppression-only.
- **NEXT (services @ 108, ~62 real):** organizeImports ×3 (heterogeneous: `??`-RHS literal
  widening in a contextual position at 954; ternary-arm array-literal member context at 216;
  destructured-member `??` write at 115); documentHighlights:193 (`Node` vs `SourceFile` arg);
  the conflated-Info return family residual (importTracker ×2 /
  findAllReferences ×2 / signatureHelp / fixExpectedComma / inlineVariable /
  convertToOptionalChain — objlit-with-any-members / nested-shape variants the round-447
  strict check rejects); the services.ts objlit giants (ObjectAllocator /
  CompletionEntry / EmitTextWriter TS2740); mapCode.ts:55 flatten (gate-(k) candidate needs a
  probe — the arg display resolves but T stays raw); stringCompletions `.types`/`.value` on
  `Type` (public-API `isUnion()`/`isStringLiteral()` this-guard modeling).**

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
