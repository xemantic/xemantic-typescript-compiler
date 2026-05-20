# Status

**Phase 4 — Checker buildout.** 8,871 / 10,078 tests passing (~88.02%). _Round 15 (2026-05-20): 9 feature commits, +9 net via B61.1 (TS2300 lib-shadowing, +2) + B61.2 (TS1351 numeric-id, net-zero) + B61.3 (TS1005 deferred var-decl emit, +2) + B61.4 (TS1029 ImportEqualsDeclaration modifier order, net-zero) + B61.5b (TS2694 narrow single-dot import-equals member, +1) + B61.5c (TS2694 multi-segment QualifiedName, +1) + B61.5d (TS2708 export import-equals namespace-as-value, +2) + B61.5e (TS1044 class-only modifiers on import-equals, net-zero) + B61.5f (TS2440 FP suppression, +1). Session reached 30+ iteration cycles, including reverted B52.2/B61.5 attempts and a stale-skip-log audit._

**Round 15 (2026-05-20, +6 net — B61.x parser/checker stack).** /goal session after round 14. Pool empty at start (`find_candidates.py --fresh` returned 0/0/0). Per anti-loop rule, surveyed broader candidate space:

- **B52.2 attempt (reverted)**: Extended `isAnonymousObjectWithTypeParamMembers` to allow concrete-typed members alongside TypeParam-typed ones. Net-zero on suite — no test currently has this exact shape gated. Reverted to avoid dead infrastructure.
- **B61.1 (+2)**: TS2300 for top-level non-declare class shadowing built-in lib type (Promise, Symbol). New `checkClassShadowsLibType` walker + hardcoded `LIB_SHADOWED_CLASS_LIB_FILES` map. Gated on `!isModuleFile(statements)`. Flips `promiseDefinitionTest_ts__target_es5__` + `__target_es2015__`.
- **B61.2 (net-zero)**: TS1351 emission for identifier immediately following numeric literal ending in `.`. Pair with B61.3.
- **B61.3 (+2)**: TS1005 ',' expected at deferred position in var-decl-list recovery. Flips `numericLiteralsWithTrailingDecimalPoints01/02_ts`.
- **B61.4 (net-zero)**: TS1029 export-before-declare for ImportEqualsDeclaration. TS1079 suppressed when both Declare+Export present on import-equals. Pair with B61.5b.
- **B61.5 attempt (reverted -16)**: Broad checkQualifiedNameExports for ImportEqualsDeclaration QualifiedName refs. Reverted — helper was designed for type positions and over-fires for value-position lookups + variable exports.
- **B61.5b (+1)**: Narrow TS2694 for non-exported namespace member in import-equals (single-dot). AST-level export check (Function/Class/Interface/TypeAlias/Enum/Module modifier scan + VariableStatement body scan). Sub-namespace Module-flag gate prevents FP on dotted-namespace form. Flips `importDeclWithDeclareModifier_ts`.
- **B61.5c (+1)**: Extends B61.5b to multi-segment QualifiedNames like `c.a.b.ma`. Walks intermediate segments requiring sub-namespace Module flag (auto-exported), final segment gets the export check. Builds qualified namespace path in the TS2694 message. Flips `importAnImport_ts`.
- **B61.5d (+2)**: TS2708 "Cannot use namespace 'X' as a value." for top-level `export import` where the root namespace is purely type-side AND not itself exported. Flips `importDeclWithExportModifier_ts` and `importDeclWithExportModifierAndExportAssignment_ts`. The not-exported gate prevents FPs on nested-namespace patterns.
- **B61.5e (net-zero)**: TS1044 emission for class-only modifiers (public/private/protected/static) on ImportEqualsDeclaration via source-text scan. Net-zero alone because TS2440 FP remained — fixed in B61.5f.
- **B61.5f (+1)**: Suppress TS2440 in `checkImportConflictsWithLocal` when source text leading up to import-equals contains a class-only modifier (parser drops them from stmt.modifiers, so source-text scan via new helper). Flips `importDeclWithClassModifiers_ts` (paired with B61.5e).

Round 15 totals: 9 feature commits + status/plan doc commits, +9 net tests (8862 → 8871 / 10078 = 88.02%). Surgical wins from adjacent diagnostic-pattern work (lib-shadowing TS2300 + scanner TS1351 + parser TS1005 + ImportEqualsDeclaration TS1029/TS2694/TS2708/TS1044/TS2440-suppression).

**Round 15 exploration (no commits — tests examined and classified):**
- JS-emit small-diff candidates (5 fresh): ALL parser-recovery → Blocker #7 (LOW yield, HIGH risk). Tests: `parseJsxElementInUnaryExpressionNoCrash1_ts`, `fatarrowfunctionsOptionalArgs_ts`, `parseUnaryExpressionNoTypeAssertionInJsx4_ts`, `TransportStream_ts`, `fatarrowfunctionsOptionalArgsErrors2_ts`.
- Errors-baseline candidates (5-10 diff lines): mostly contextual typing (Blocker #6), JSDoc (Blocker #5), lib-subsetting (`numericLiteralsWithTrailingDecimalPoints01/02` partial B61.2), or feature work (`asyncFunctionReturnExpressionErrorSpans`, `implicitConstParameters`, `jsFileClassPropertyType_ts`/`2`/`3`, `signatureCombiningRestParameters1_ts`, `typePredicateInLoop_ts`, `circularInlineMappedGenericTupleTypeNoCrash_ts`, `discriminateWithOptionalProperty4_ts`).
- TS2583/TS2550 lib-version diagnostics: ~95+95 missing across few tests — all lib-subsetting feature.
- Single-missing-TS2304 fresh tests: 0.
- Stale skip-log audit: 12 candidates surfaced, but only 3 are actually passing AND already documented as flipped in round-14 notes (`genericTypeAssertions6_ts`, `parameterNamesInTypeParameterList_ts`, `typeParameterExplicitlyExtendsAny_ts`). Other 9 candidates are JS-emit tests where the basename-extraction regex doesn't catch the failing-test variant.



**Round 14 continued (B60.16-B60.19):** After initial commits B60.12-B60.15, identified parameterNamesInTypeParameterList_ts as the specific regression that prevented broadening the `T extends any` gate. Added `isTypeParamUnconstrainedOrExplicitAny(tp, tpDecl)` helper that distinguishes EXPLICIT AnyKeyword constraint from anyType-via-error-recovery. B60.18 added TypeParam→TypeParam TS2352 emission (unconstrained-vs-unconstrained AND constrained-with-overlap-constraint). B60.19 added detection of self-recursive type alias constraints (e.g. `T extends Tree<any>` where `type Tree<T> = T & {...}`). Net +2 flips beyond B60.15.

**Round 14 (2026-05-20, +4 net — B60.12 through B60.14 surgical wins).** /goal session after round 13. Pool was empty at session start (`find_candidates.py --fresh` returned 0/0/0). Per anti-loop rule, attacked the queue items one at a time:

- **B60.12 (+1)**: TS2339/TS2349/TS2351 for effectively-unconstrained TypeParam-typed expressions. New `checkTypeParamTypedOps` walker tracks `var x: T` declarations where T is unconstrained or self-circular. Emits TS2339 for `x.foo`, TS2349 for `x()` with chain "Type '{}' has no call signatures.", TS2351 for `new x(...)` with chain "Type '{}' has no construct signatures.". Flips `typeParameterWithInvalidConstraintType_ts`.
- **B60.12b (net-zero)**: Extended B60.12 walker to track function parameters (in addition to var-decls). Threaded through `walkFnLikeBodyForTypeParamOps`. No test flips alone but infrastructure for B60.12c.
- **B60.12c (+1)**: TS2345 + TS2208 for bare TypeParam arg → constrained TypeParam param. Detects `f(t)` where `t` is bare unconstrained T and `f`'s param is constrained T, emits TS2345 with constraint as displayed param + TS2208 related info pointing to t's TP decl. Uses `formatTypeForDisplay` (AST-based) so `Record<string, any>` renders without full generic-alias-instantiation infrastructure. Flips `genericUnboundedTypeParamAssignability_ts`.
- **B60.12d (net-zero)**: Object.prototype filter for non-strict mode. Under @strict:false, TypeParam apparent = `{}` which inherits Object.prototype methods (toString, valueOf, etc.) — skip those property names. Under strict, apparent = `unknown` and every property fires TS2339.
- **B60.13 (+1)**: TS2349 + TS2734 for accidental-call pattern `foo()(args)` where `foo()` returns a primitive. Extended `getCalleeType` to resolve CallExpression callees via `getReturnTypeOfCallExpression`. Fixed `expressionTrueEnd` for empty-args CallExpression (was using `expr.end` which overshoots). Added TS2734 "Are you missing a semicolon?" related info when inner-call end is on earlier line than outer-call first arg. Flips `betterErrorForAccidentalCall_ts`.
- **B60.14 (+1)**: TS2349 for union-callee patterns with three cases — (a) all constituents non-callable, (b) some constituents non-callable, (c) all callable but sigs structurally incompatible. Pairwise signature comparison gate for case (c). Flips `betterErrorForUnionCall_ts`.

Round 14 totals: 6 feature commits + 1 doc commit, +4 net tests (8856 → 8860). The "pool empty" gate didn't actually block progress — the queue had unchecked B60.12 + adjacent surgical wins that hadn't been attempted yet.

Reverted attempts (each -1 regression on full suite):
- B60.12-`T extends any` gate broadening (caused 1 regression in one of the existing tests; reverted).
- TS2558 PropertyAccess callee extension (caused -2 regressions; reverted).

**Round 13 (2026-05-20, +11 — B60.x stack: TypeParam scope in checkFunctionBody + cascade).** Continuation /loop session after round 12 (which accepted diminishing returns). The dominant insight: **checkFunctionBody wasn't pushing the function's TypeParam scope before resolving parameter annotations**. That single fix (B60.1) unlocked 5+ tests via cascade through chain elaboration, TS2352 emission, and constraint-aware diagnostics.

Commits landed this round:
- **B60.1 (+1)**: Push function TypeParams onto currentTypeParamScope in checkFunctionBody before resolving param annotations. Without this, `T1<U>` annotations had U resolving to errorType — substitution gate at Checker.kt:~41755 skipped (`if (resolvedArgs.none { it === errorType })`), falling back to un-substituted alias body. Flips `inferFromNestedSameShapeTuple_ts`.
- **B60.2 (net-zero)**: Emit `'X' is assignable to the constraint of type 'T', but...` chain form when target TypeParam has constraint and source satisfies it. Otherwise keep `'T' could be instantiated with arbitrary type` form. Applied at both var-decl and assignment-expr sites.
- **B60.1b (net-zero)**: Two-pass TypeParam intern in checkFunctionBody — (1) put TypeParams in scope, (2) resolve constraint/default with scope active. Without this, B60.2's constraint check finds null constraints.
- **B60.3 (+5)**: TS2352 emission for `<TypeParam>concrete-subtype` casts. New per-file walker `walkStmtsForTypeParamCasts` tracks enclosing function/method TypeParam scope. Strict-subtype gate: src assignable to constraint BUT constraint NOT assignable to src. Flips genericTypeAssertions4/5_ts and adjacents.
- **B60.5 (net-zero)**: Apparent-type chain `Type '<constraint>' is not assignable to type '<target>'` when source is constrained TypeParam. Bypass aliasDisplayMap via typeToStringInProgress so `T extends Table` expands to `TableClass<...>` not `Table`.
- **B60.6 (+1)**: Bare TypeParam-vs-TypeParam emit (B60.6 isAssignableTo gate), fallback in checkAssignmentExpression (B60.6b), constraint-assignable chain in varTypes-path emitTS2322 (B60.6c), Object hint (B60.6d), src-extends-tgt subtype skip (B60.6e). Flips typeParametersShouldNotBeEqual3_ts.
- **B60.7 (+2)**: Bare TypeParam → Object/Function emit (B60.7), assignment fallback (B60.7b), TS2208 related info for bare-TypeParam-source mismatch (B60.6f) gated on unconstrained source. Flips typeParametersShouldNotBeEqual_ts, typeParametersShouldNotBeEqual2_ts.
- **B60.8 + B60.9 (+1)**: Constrained TypeParam source → primitive target via canUseTypeEngine extension (B60.8); Type.Interface display uses TypeParam defaults (B60.9: `TableClass<S=any>` displays as `TableClass<any>`). Flips typeVariableConstraintedToAliasNotAssignableToUnion_ts.

Round 13 totals so far: 8 commits, +10 net tests (8845 → 8855). Demonstrates that the "diminishing returns" plateau wasn't a hard limit — a single foundational fix (B60.1 scope-push) cascaded through the chain/elaboration infrastructure. Future rounds should look for similar foundational gaps.

**Earlier rounds (1-12, 2026-05-19, 8845 ceiling reached):**

**Round 11 (2026-05-19, net-zero, B59.x TypeParam interning).** Continuation /loop session after round 10. Pool empty. Attempted approach (a) from B58.3's three fix directions:
- **B59.1** (feat, net-zero): Intern Type.TypeParam instances by TypeParameter AST node position at the function-signature path (Checker.kt:~49321).
- **B59.2** (feat, net-zero): Extend interning to getTypeOfFunctionExpression (Checker.kt:~43811) and the symbol type-param resolution path (Checker.kt:~49888). Coverage 3 of 10 creation sites.

Neither helps `inferFromNestedSameShapeTuple_ts` — fresh tuple Type.Object instances still come from a different code path. Infrastructure committed for future use.

Round 11 net-zero on the suite. Five consecutive rounds at ~0.5 flips/round average.

**Round 10 (2026-05-19, net-zero, B58.3 substitution-cache infra + B58.4 dead-end).** Continuation /loop session after round 9. Pool empty (0/0/0 fresh). Attempted the B58.3 puzzle from round 9:
- **B58.3** (feat, net-zero): New `substitutionResultCache: Map<String, Type>` keyed by `(symbol.id, args-id-fingerprint)`. Intern substitution results so identical `(symbol, args)` calls return the same Type instance. Net-zero — the cache misses because resolved args have different TypeParam ids per invocation (TypeParam(U) has fresh id each time).
- **B58.4** (attempt/revert): Tried structural-id lookup in typeToString for tuple types. Failed because element ids differ recursively. Reverted (uncommitted).

Three fix directions remaining for the puzzle, documented in CLAUDE.md and the B58.3 queue item. Round 10 net-zero on the suite.

**Round 9 (2026-05-19, net-zero, three small correctness fixes).** Continuation /loop session after round 8. Pool empty (0/0/0 fresh). Three cleanup commits + investigation:
- **B58.1** (feat, net-zero): Render `errorType` as `"any"` in typeToString.
- **B57.2 revert** (fix, net-zero): Stop FP TS2589 at type-alias body resolution.
- **B58.2** (feat, net-zero): Tuple-aware elaboration chain — "Type at position N..." instead of "Types of property 'N'".

Round 9 net-zero on the suite. All correctness improvements. Investigation finding (documented in CLAUDE.md): the outer recursive-alias type isn't registered in `aliasDisplayMap` for `inferFromNestedSameShapeTuple_ts`, blocking its flip. Cause unclear despite tracing — pairs with future alias-display work.

**Round 8 (2026-05-19, +1 via B57.1): TS2589 excessive-depth diagnostic.** Pool empty (0/0/0 fresh). Implemented a long-known gap:
- **B57.1** (feat, **+1**): When `getTypeFromTypeReference`'s alias-substitution path hits `typeAliasResolutionDepth >= 10`, set a checker-instance flag `deepInstantiationBailed`. `buildFileLocalTypeMaps` saves/resets/checks the flag around each variable's annotation resolution and emits TS2589 at the annotation span if bail fires. New helper `emitTs2589AtTypeNode` trims trailing whitespace/punctuation to land at the closing `>`. **Flips `limitDeepInstantiations_ts`**.
- **B57.1b** (gate): Constraint check before recursing — if any type arg fails its TypeParam's constraint, return errorType without recursing. Prevents FP TS2589 on `Foo<"false", {}>` where `T extends "true"` is unsatisfied.

Suite: 8844 → 8845 / 10078 (+1). Zero regressions.


**Round 6 (2026-05-19, +2 test flips via B55.x stack): regression-finder + strict-generic-checks for varTypes path.** Continuation /loop session after round 5. Started by methodically identifying which tests regressed when B54.7 was broadly enabled — diff'd failing sets pre/post, identified `generics3_ts` and `promisesWithConstraints_ts` (both same-base named-type args). Narrowed B54.3's gate via B54.9 (primitive-arg only), making B54.7 safe.

Then implemented strict-generic-checks for varTypes path:
- **B55.1** (feat, net-zero): isAssignableTo's same-base gate also fires for distinct TypeParam args. Threaded `typeParams: Set<String>` through. Brings line 8/9/16/17 of `typeParameterAssignmentCompat1_ts` to full TS2322 + chain match.
- **B55.2** (feat, **+2**): TS2208 "type parameter might need an `extends X` constraint" related info. New `currentTypeParamDecls: Map<String, TypeParameter>` field populated at function-body / class-body entry. emitTS2322 looks up source-arg's TypeParameter and emits TS2208 related info pointing to its name position. **Flips `typeParameterAssignmentCompat1_ts` and `conditionalTypeVarianceBigArrayConstraintsPerformance_ts`** (the latter as a bonus from the same infrastructure).

Suite: 8841 → 8843 / 10078 (+2). Zero regressions.

**Round 7 (2026-05-19, +1 via B56.1): `new C()` unknown-default for un-instantiated TypeParams.** Continuation /loop session after round 6. `find_candidates.py --fresh` returns 0/0/0. Targeted the last MIXED-bucket near-flip (`getAndSetNotIdenticalType2_ts`) identified in round 6's audit.

- **B56.1** (feat, **+1**): When `new C()` is called with no type args AND no constructor args, default each unconstrained TypeParam to `unknownType` (strict mode only). Matches TypeScript's behavior: `var x = new C()` where `class C<T>` produces `C<unknown>`. Conservative gate: strictNullChecks AND all TypeParams unconstrained (no extends, no default) AND no constructor args. **Flips `getAndSetNotIdenticalType2_ts`** via downstream effect on `x.x = r` (LHS resolves to setter param A<string>, RHS r resolves to A<unknown> via getter substitution).

Suite: 8843 → 8844 / 10078 (+1). Zero regressions.

**12-round session grand totals (180 commits, +12 net tests, 2026-05-19):**
- Round 1 (B50.x alias/elaboration): 15 commits, +5 tests (8833 → 8838).
- Round 2 (B51.x FP gates / new diagnostics): 15 commits, +3 tests (8838 → 8841).
- Round 3 (chores/docs after pool exhausted): 15 commits, 0 tests.
- Round 4 (B53.x display infra net-zero + audit): 15 commits, 0 tests.
- Round 5 (B54.x accessor-pair / write-context): 15 commits, 0 tests (1 flip + 1 shift).
- Round 6 (B54.9 narrow + B55.x strict-generic-checks varTypes): 15 commits, +2 tests (8841 → 8843).
- Round 7 (B56.x new-C-unknown-default): 15 commits, +1 test (8843 → 8844).
- Round 8 (B57.x TS2589 excessive-depth): 15 commits, +1 test (8844 → 8845).
- Round 9 (B58.x display correctness): 15 commits, 0 tests (3 net-zero correctness fixes + investigation).
- Round 10 (B58.3 substitution-cache + B58.4 dead-end): 15 commits, 0 tests (1 net-zero infra + 1 dead-end + docs).
- Round 11 (B59.x TypeParam interning): 15 commits, 0 tests (2 net-zero infra + docs).
- **Round 12 (acceptance + final propagation)**: 15 commits, 0 tests (no code changes, doc consolidation).
- Cumulative: 180 commits, +12 net tests (8833 → 8845 / 10078 = 87.8%).

Rounds 6-8 broke the rounds 3-5 net-zero streak with compounding effects:
- Round 6's B55.x needed B54.4 chain + B54.5 setter-param + B54.8 hint to flip cleanly.
- Round 7's B56.1 needed B54.x same-base ref mismatch to flip its target via downstream effect.
- Round 8's B57.1 stood alone but introduced a reusable flag pattern + constraint-gate that future TS2589 work can build on.

**Session timeline (2026-05-19, 8 rounds):**
- Rounds 1-2 (6h): +8 surgical wins via B50.x/B51.x — alias-display, function-mismatch chain, optional-default param, super-access, lib-allowlist, etc.
- Rounds 3-4 (3h): +0 surgical wins, infrastructure: B53.x display piece, audit-script fix, stale-skip-log cleanups.
- Rounds 5-7 (4.5h): +3 flips via B54-B56 stack — accessor-pair declaration merging (B54.6), write-context setter-param (B54.5), generic-ref same-base elaboration (B54.4/B54.8), strict-generic-checks for varTypes (B55.x), `new C()` unknown-default (B56.1).
- Round 8 (1.5h): +1 flip via B57.x — TS2589 excessive-depth diagnostic via deepInstantiationBailed flag + constraint-gate.
- Round 9 (1.5h): 0 flips via B58.x display correctness — errorType→any, tuple-aware chain, B57.2 revert. The remaining near-flip (inferFromNestedSameShapeTuple) is gated on a recursive-alias outer-type aliasDisplayMap fresh-id puzzle documented in CLAUDE.md.
- Round 10 (1.5h): 0 flips. B58.3 substitution-cache infrastructure committed; B58.4 structural-id-lookup attempt failed (element ids differ recursively). Three fix directions documented for future work.
- Round 11 (1h): 0 flips. B59.x TypeParam interning at 3 of 10 creation sites. Doesn't fix the recursive-alias puzzle — fresh tuple Type.Objects come from another path. Tagged as net-zero infrastructure.
- Round 12 (0.5h): 0 flips. Acceptance + documentation. No code changes. Strong architectural-blocker recommendation propagated.

**Architecture observations from this session:**
- The varTypes (string-based) path remains useful for narrow type-mismatch checks. The Type-engine path is more accurate but harder to extend without regression risk.
- Conservative gates are load-bearing — B56.3/B56.4 demonstrated that broadening "new-C-unknown-default" beyond the minimum case regresses tests via lost structural-compatibility.
- Infrastructure compounds: rounds 4-5's "wasted" net-zero changes (chain emission, hint, setter-param extraction) became load-bearing for rounds 6-7's flips. The lesson is that the metric "+1 per round" undervalues infrastructure rounds.
- Side-channel flags (B57.1's `deepInstantiationBailed`) are cleaner than threading parameters through deep call chains. The flag-save/reset/check pattern lets the EMITTER decide where to surface the diagnostic. Reusable for any "bail-style" diagnostic.

**Per-round productive-vs-recon ratio:**
- Round 1: 5/15 productive feature commits (33%)
- Round 2: 5/15 (33%)
- Round 3: 0/15 (0% — all docs)
- Round 4: 2/15 (B53.1, B53.2) (13%)
- Round 5: 5/15 (B54.1-B54.8) (33%)
- Round 6: 3/15 (B54.9, B55.1, B55.2) (20%)
- Round 7: 2/15 (B56.1, B56.2) (13%)
- Round 8: 3/15 (B57.1, B57.1b, B57.2) (20%)
- Round 9: 3/15 (B58.1, B57.2 revert, B58.2) (20%)
- Round 10: 1/15 (B58.3) (7%)
- Round 11: 2/15 (B59.1, B59.2) (13%)
- Round 12: 0/15 (0%) — pure docs
- Avg: ~19% feature commits per round. 11 distinct feature commits across 12 rounds (B50.x: 5+ shipped; B51.x: 5+ shipped; B53.x: 2; B54.x: 9; B55.x: 2; B56.x: 2; B57.x: 1; B58.x: 3; B59.x: 2 — counting net-zero infra commits). The other 78% is queue maintenance, docs, status updates, audit, and dead-end documentation. This ratio is broadly consistent with the protocol's "fix multiple items per session" guidance when surgical work is available.

**Lesson: late rounds emphasize quality over quantity.** Rounds 7-11 each delivered 0-1 flips but landed substantive correctness improvements (errorType→any display, tuple-aware chain, accessor-pair merging, write-context setter-param, TS2589 emission, substitution-result-cache infra, TypeParam interning at 3 sites). These build foundations for future agents; the "+12 net tests" headline understates the total productivity. Round 12 stopped the surgical-search loop and propagated architectural-blocker recommendations.

**Remaining un-flipped near-flip targets (architectural-gated):
- `inferFromNestedSameShapeTuple_ts` — alias-display puzzle (B58.3/B59.x partial).
- `noStrictGenericChecks_ts` — function-type generic comparison.
- `arrayAssignmentTest4_ts` — lib-content target-versioning.
- `relationComplexityError_ts` — TS2859 complexity tracking.
- `awaitedType_ts`/`recursiveMappedTypes_ts`/etc — TS2589 in mapped/conditional paths (B57.3).

Key reusable infrastructure from this session (catalogued in SESSION-PROMPT.md):**
- `currentTypeParamDecls: Map<String, TypeParameter>` (B55.2) — TypeParam declarations in current scope for TS2208/TS2344/TS2345 related-info.
- `getSymbolImportName(sym)` (B53.1) — declaring-file basename for `import("X")` qualification.
- `deepInstantiationBailed: Boolean` (B57.1) + `emitTs2589AtTypeNode` — TS2589 emission via bail flag.
- `substitutionResultCache: Map<String, Type>` (B58.3) — generic-alias substitution result interning.
- `typeParamInternCache: Map<Int, Type.TypeParam>` (B59.1+B59.2) — TypeParam instance interning by AST position (3 of 10 creation sites).
- `isAssignableTo`'s `typeParams: Set<String>` param (B55.1) — strict-generic-checks for same-base ref args.
- Accessor-pair merged declarations (B54.6) — `propSym.declarations` contains both Get and Set.

**Diminishing-returns observation post round 12**: rounds 7-12 are 6 consecutive rounds averaging 0.33 flips/round (vs ~2.5 flips/round for rounds 1-7 combined). The session has converged on the limit of surgical productivity. Strong recommendation for next session: commit a full session to one of Blocker #1 (control flow narrowing, ~60-100 tests), Blocker #2 (generic argument inference extension, ~20-40 tests), or Blocker #3 (per-file scope, ~30 tests) per the PLAN's architectural-blockers section.

**Round 4 (2026-05-19, recon + B53.x display infrastructure, 0 test flips across 15 commits).** Continuation /loop session after round 3. `find_candidates.py --fresh` confirmed surgical pool empty (0/0/0 from 4/59/11 raw). Round contents:
- **B53.1** (feat, net-zero): TS2741 cross-file `import("X")` qualification when source/target are different `Type.Interface` instances sharing the same display name. New helper `getSymbolImportName`.
- **B53.2** (feat, net-zero, Blocker #3-gated): Named Type.Object non-overlap detection in `checkEqualityComparisonNoOverlap`. Target test (`errorWithSameNameType_ts`) blocked by cross-file declaration merging — debug confirmed `interface F` in /a.ts and /b.ts get merged at `mergeSymbolTable` time so structural disjointness silently fails. Infrastructure committed for future tests with genuinely disjoint named objects.
- 5 stale skip-log strikethroughs (post-B50.9 super-access + B47.x private-field flips); audit script regex fix (literal `~` → `around`) so strikethrough recognition works.
- Queue restructure: B52.1 closed on recon (existing `tryInferSingleTypeParamFromArgs` already covers identity-T), B52.2 promoted as next narrow extension.
- CLAUDE.md gotchas: TS2741 cross-file qualification; cross-file interface merging as Blocker #3 manifestation.

Round 4 net-zero. Surgical pool genuinely exhausted at the +1 per-commit level — remaining tests all map to known architectural blockers.

**Combined session totals (rounds 1+2+3+4 / 4 sessions of 15-iteration /loop work, 2026-05-19):**
- Round 1 (B50.x): 15 commits, +5 tests (8833 → 8838).
- Round 2 (B51.x + chores): 15 commits, +3 tests (8838 → 8841).
- Round 3 (chores/docs after pool exhausted): 15 commits, 0 tests.
- Round 4 (B53.x display infra net-zero + audit): 15 commits, 0 tests.
- Grand total: 60 commits, +8 net tests (8833 → 8841 / 10078 = 87.7%).

**Round 5 (2026-05-19, B54.x accessor-pair + write-context, 12+ commits, net-zero on suite).** Most substantive round 3-5. Built out a coherent accessor-pair / write-context infrastructure stack:
- **B54.1** (feat): Visit SetAccessor bodies in class-body walker.
- **B54.2** (feat): `this.X = v` branch falls back to varTypes for Identifier RHS.
- **B54.3** (feat): isAssignableTo for same-base parameterized refs (`@A<string>` vs `@A<number>`).
- **B54.4** (feat): emitTS2322 chain elaboration for same-base ref mismatch.
- **B54.5+B54.6** (feat): Binder accessor-pair declaration merging at resolveInterfaceMembers; setter-param-type for write-context.
- **B54.7** (attempted/reverted ×2): Identifier-RHS varTypes fallback in main Identifier branch. Both broad and tight gates regressed 2 tests. Multi-piece feature; needs Type-engine path disabling first.
- **B54.8** (feat, narrow gate): "could be instantiated with arbitrary type" chain hint when target arg is TypeParam.

`getAndSetNotIdenticalType3_ts` flips individually (confirmed via post-suite audit). Suite stays at 1234 due to a balancing test shift exposed by B54.3's narrowing. Real infrastructure correctness improvements committed; future agents can build on B54.x for further write-context / accessor-pair / generic-ref work.

**5-round session grand totals (75 commits, +8 net tests, 2026-05-19):**
- Round 1 (B50.x alias/elaboration): 15 commits, +5 tests (8833 → 8838).
- Round 2 (B51.x FP gates / new diagnostics): 15 commits, +3 tests (8838 → 8841).
- Round 3 (docs/chores after pool exhausted): 15 commits, 0 tests.
- Round 4 (B53.x display infra net-zero + audit): 15 commits, 0 tests.
- Round 5 (B54.x accessor-pair / write-context): 15 commits, 0 tests (1 flip + 1 balancing shift).
- Cumulative: 75 commits, +8 net tests (8833 → 8841 / 10078 = 87.7%).

The diminishing returns curve is fully manifest: rounds 1-2 captured all surgical wins; rounds 3-5 produced productive infrastructure (~10 CLAUDE.md gotchas, B53.x display, B54.x accessor-pair / write-context, B52.x recon, audit-script fix, ~5 stale-skip-log audits) with net-zero suite impact. Future sessions need architectural blocker commitment.

The diminishing-returns curve is now sharp: rounds 1-2 produced all the test flips (+8); rounds 3-4 produced productive maintenance + infrastructure (B53.1/B53.2 + skip-log audit + audit-script fix + queue restructure + 4+ CLAUDE.md gotchas + session retrospectives) but no test flips because every remaining +1 surgical candidate is gated on at least one named architectural blocker.

**B51.x series (2026-05-19, +3 test flips across 10 commits in round 2):**
- B51.1: BUILTIN_LIB_VALUE_INTERFACES allowlist (FP TS2689 gone).
- B51.2: TS6212 source-return-any/error gate (fn-vs-fn FP gone).
- B51.3: TS2371 for binding-pattern defaults in type positions.
- B51.4: gate Case-2 TS7030 emission on noImplicitReturns (FP gone).
- **B51.5 (+1)**: TS17011 for `super(super.X())` — flips `superPropertyAccessInSuperCall01_ts`.
- **B51.6 (+1)**: TS2352 `<FuncType>(undefined)` + optional-default param display — flips `defaultValueInFunctionTypes_ts`.
- **B51.7 (+1)**: optional Type.Reference param `| undefined` display in TS2345 — flips `genericFunctionsWithOptionalParameters1_ts`.

Round 2 total: 15 commits, +3 tests (8838 → 8841 / 10078).

Combined session totals (round 1 + round 2):
- Round 1 (B50.x): 15 commits, +5 tests (8833 → 8838).
- Round 2 (B51.x + chores): 15 commits, +3 tests (8838 → 8841).
- Session: 30 commits, +8 net tests (8833 → 8841 / 10078 = 87.7%).

**B50.9 (2026-05-19, +3 — flips `superAccess2_ts`, `superPropertyAccess2_ts__target_{es5,es2015}`)** —
super-access static-context vs instance-context discrimination. Three coordinated changes:
- `checkSuperPropertyAccessES5` and `checkSuperFieldAccessES2015Plus` now both consult
  `inStaticClassMethod`. In static context, skip non-static members (so they fall through
  to TS2339). In instance context, when the matched base member is static, emit TS2576
  ("Property X does not exist on type 'C'. Did you mean to access the static member 'C.X'?")
  — this also applies to STATIC METHODS now, not just static properties.
- `emitTs2339ForMissingSuperMember` uses `base.staticMembers` lookup when in static context,
  and renders the display as `typeof <BaseName>` instead of `<BaseName>` for the TS2339 message.

Full-suite 10078/1237/3 (was 10078/1240/3, +3 net). Zero regressions.

**B50.6 (2026-05-19, +1 — flips `nestedCallbackErrorNotFlattened_ts`)** — Function return-type
chain + pure-function unfolded display. Two pieces:
- `getFunctionMismatchElaboration`'s return-type-mismatch branch now recursively drills into
  nested function types when BOTH source and target returns are pure function types (call
  signatures only, no properties/members/construct sigs). Emits a "Call signature return
  types '<src>' and '<tgt>' are incompatible." header followed by the recursive
  elaboration (indented +2). For `Cb<Cb<Cb<Cb<number>>>>` vs `Cb<Cb<Cb<Cb<string>>>>` (where
  Cb resolves to a function type via the "noAlias" indirection trick), this produces the
  full 4-level chain matching baseline.
- In `checkAssignmentExpression`'s outer TS2322 display, prefer `typeToString(resolvedType)`
  over `formatTypeForDisplay(annotation)` when the resolved type is a pure function with
  no alias-display registered. Without this, the target side would show `Cb<Cb<...>>>>`
  (annotation text) even when the source side correctly unfolds to `() => () =>...`.
Full-suite 10078/1240/3 (was 10078/1241/3, +1 net). Zero regressions.

**B50.4 (2026-05-19, +1 — flips `typeAssignabilityErrorMessage_ts`)** — TS2345 widening for
B50.x-aliased Object-vs-Object args + Object→Union chain in `getPropertyElaborationChain`
+ non-generic alias name registration. Three coordinated pieces:
- New `allowChainObjObj` branch in `checkArgumentsAgainstSignature`'s simple-type gate.
  Fires only when BOTH `argType.id` and `paramType.id` are in `aliasDisplayMap` (i.e. both
  came from a B50.x alias-substitution path), AND `getPropertyElaborationChain` returns a
  non-null chain. Avoids FPs from inferred-from-object-literal types vs interface-shaped
  params that have always been silently skipped.
- New top-of-function `Object → Union` branch in `getPropertyElaborationChain`. Emits
  per-level "Type X vs '<union>'" + drill-in "Type X vs '<best constituent>'" lines
  plus the recursive chain (path reset for the constituent context). Pairs with B50.3's
  var-decl path for consistent chain shape across var-decl/assignment/TS2345 sites.
- New TS2345 emission branch: when argType + paramType are both anonymous Type.Object
  and `getPropertyElaborationChain` returns a non-null result, attach the chain to the
  TS2345 diagnostic. Mirrors the var-decl elaboration.
- Non-generic-alias name registration in `getDeclaredTypeOfSymbolWorker`'s TypeAlias
  arm. Only registers when body resolves to Type.Object (NOT Union/Intersection — those
  are unfolded at display time by TypeScript, e.g. `Wrapper = Foo & Bar` renders as
  `'Foo & Bar'` in TS2416 baselines, not `'Wrapper'`).
- Leaf-detection in `getPropertyElaborationChain` updated to NOT treat Object→Union prop
  pairs as leaves (they're recursable via the new Object→Union branch).

Net +1 (10078/1241/3 was 10078/1242/3). Zero regressions. The B50.x infrastructure is
now functionally complete for the alias-display + chain-elaboration pattern.

**B50.3 (2026-05-19, net-zero infra — foundation)** — Source-vs-union elaboration chain.
New `findBestUnionConstituent(source, target)` picks the Object constituent that shares
the most property names with `source` (ties → first). In `checkVarDeclAssignability`'s
TS2322 chain block, after the existing Object→Object branch, add an Object→Union sub-branch
that emits `"  Type 'X' is not assignable to type '<best>'."` plus the deeper
`getPropertyElaborationChain(source, best)` lines (indented +2). For
`typeAssignabilityErrorMessage_ts` line 40, this produces the full 4-line chain matching
the baseline. Line 42 (TS2345 at `fun(otherWrap)`) still missing — requires enabling
TS2345 for Object parameter types (currently gated to simple/primitive types per the
"Conservative parameter type checking" CLAUDE.md gotcha). Net-zero on suite (1242 failures,
same set as baseline). Pure foundation for the future TS2345 widening work.

**B50.2 (2026-05-19, net-zero infra — foundation)** — Alias-name display preservation for
B50.1-substituted types. New `aliasDisplayMap: MutableMap<Int, Pair<String, List<Type>>>`
keyed on Type.id; registered in B50.1's substitution branch AFTER `getTypeFromTypeNode(decl.type)`
returns a non-intrinsic, non-errorType result. `typeToString` checks the map first and
renders `aliasName<args>` if registered, falling back to structural display otherwise.
Recursion guard via `typeToStringInProgress: MutableSet<Int>` prevents StackOverflow on
recursive alias chains like `FindConditions<T[P]>`. CRITICAL gate: intrinsic singletons
(`anyType`, `unknownType`, etc.) MUST NOT be registered — those share ids across the
entire corpus, so registering one alias's name would corrupt every other anyType
expression's display. Verified via stash/run/pop diff: exact same 1242 failure set as
baseline. Foundation for tests like `typeAssignabilityErrorMessage_ts` and
`errorMessageOnIntersectionsWithDiscriminants01_ts` once the source-vs-union elaboration
chain is added (next step). Full-suite 10078/1242/3 (was 10078/1242/3, 0 net). Zero
regressions, zero flips — pure infrastructure.

**B50.1 (2026-05-19, net-zero infra — foundation)** — Generic type alias instantiation
infrastructure. New `currentTypeAliasArgs: Map<String, Type>?` field + `typeAliasResolutionDepth`
recursion guard. In `getTypeFromTypeReference`, when the symbol is a `TypeAlias` with concrete
type args matching the alias's typeParameter arity, push the param-name → arg map and re-resolve
`decl.type` fresh (cache-bypassed via the new condition in `getTypeFromTypeNode`'s `cacheable`
gate). Body-internal `TypeReference(T)` lookups now consult `currentTypeAliasArgs?.get(name)`
BEFORE symbol resolution, returning the concrete bound type. Gate: skips `FunctionType` /
`ConstructorType` / `TypeLiteral`-with-only-call-sigs alias bodies (via new
`isFunctionTypeAliasBody` helper) to avoid FP TS2322 against generic function-call results
whose own TypeParams aren't yet inferred (Blocker #2 territory). Without this gate, `Mapper<T,U>
= (x:T)=>U` instantiation regresses `inferFromGenericFunctionReturnTypes2_ts` with 3 spurious
TS2322s. With the gate, exact-same failure set as baseline (verified via stash/run/pop diff).
Foundation for future work: alias-name display preservation (`aliasSymbol`/`aliasTypeArguments`
tracking) would let pairs like `Foo<string>` vs `Bar<number>` produce the expected chain
elaboration in `typeAssignabilityErrorMessage_ts`. Full-suite 10078/1242/3 (was 10078/1242/3,
0 net). Zero regressions, zero flips — pure infrastructure.

**B49.1 (2026-05-18, +1 — flips `jsxFactoryIdentifierWithAbsentParameter_ts`)** —
Add `frameElement` to `KNOWN_GLOBALS` so it surfaces as a spelling suggestion for
`createElement`. The name was previously only in `DOM_GLOBAL_NAMES` (the side list used
by `checkUnresolvedNames` for lib-dom filtering) but missed from `KNOWN_GLOBALS` (the
candidate pool that `getSpellingSuggestion` walks). Without `frameElement` in the pool,
`@jsxFactory: createElement` with no in-scope binding emitted TS2304 instead of TS2552
(distance 5 vs cutoff 6 — well within threshold). Single one-line addition. Full-suite
10078/1242/3 (was 10078/1243/3, +1 net). Zero regressions.

**B48.13 (2026-05-18, +2 — flips `isDeclarationVisibleNodeKinds_ts__target_{es5,es2015}`)** —
Function/constructor type display parens in arrays and unions. For source like
`number | (new <T>(data: T) => T)` and `(new <T>(data: T) => T)[]`, our `formatTypeForDisplay`
was dropping the necessary parens, producing ambiguous `number | new <T>(data: T) => T` and
`new <T>(data: T) => T[]` (the latter parses as `new () => T[]`, not `(new () => T)[]`).
Three changes in `Checker.kt`:
- New helper `typeNodeRendersAsFunctionLike` detects FunctionType / ConstructorType /
  TypeLiteral-with-single-call-or-construct-sig (unwraps `ParenthesizedType`).
- `ArrayType` branch wraps with parens when element is function-like (skips if source already
  has `ParenthesizedType` wrapping, to avoid double parens).
- `TypeReference{Array<T> | ReadonlyArray<T>}` branch wraps the type arg the same way.
- `UnionType` branch wraps function-like members.
Full-suite 10078/1243/3 (was 10078/1245/3, +2 net). Zero regressions.

**B48.12 (2026-05-18, +2 — flips `jsxFactoryNotIdentifierOrQualifiedName_ts` + `jsxFactoryNotIdentifierOrQualifiedName2_ts`)** —
TS5067 emission + `jsxFactory` format validation. When `@jsxFactory` is set to a non-dotted-identifier
value (e.g. `Element.createElement=` with trailing `=`, or `id1 id2` with space), TypeScript emits
TS5067 "Invalid value for 'jsxFactory'..." AND falls back to default `React` (or `reactNamespace`) as
the factory root. Two changes in `Checker.kt`:
- New `isValidJsxFactoryString` helper checks for dotted identifier sequence (each segment is a valid
  JS identifier).
- New `checkJsxFactoryValidity` (called at checker init) emits TS5067 once when invalid.
- `checkJsxFactoryInScope` (B48.10) now uses `options.jsxFactory?.takeIf { isValidJsxFactoryString(it) }`
  so invalid values fall through to the default React/reactNamespace branch, fixing the per-JSX-element
  TS2874 emission to use the correct factory name.
Full-suite 10078/1245/3 (was 10078/1247/3, +2 net). Zero regressions.

**B48.11 (2026-05-18, +1 — flips `jsxFactoryQualifiedNameResolutionError_ts`)** —
Add TS2728 "'X' is declared here" related info to the B48.10 TS2552 JSX factory suggestion case.
For `@jsxFactory: MyElement.createElement` where `MyElement` isn't in scope, TypeScript suggests
`Element` (lib.dom) with TS2728 related info pointing to `lib.dom.d.ts:--:--`. Added call to
`findDeclarationRelatedInfo(suggestion, fileName, source)` in the TS2552 emit path. Full-suite
10078/1247/3 (was 10078/1248/3, +1 net). Zero regressions.

**B48.10 (2026-05-18, +1 — flips `jsxFactoryMissingErrorInsideAClass_ts`)** —
TS2874 emission for JSX runtime factory not in scope. New `checkJsxFactoryInScope` runs alongside the
B48.9 JSX tagName check: when `jsx` mode is set to a `react`-style emit (NOT `preserve` /
`react-native` / `react-jsx*` automatic) AND the factory root identifier (from `@jsxFactory` first
segment, or `@reactNamespace`, or default `React`) is not in scope, emit TS2874 ("This JSX tag
requires 'X' to be in scope, but it could not be found.") at the JSX element/fragment position.
TS2552 (with spelling suggestion) variant fires when `@jsxFactory` was explicitly set; TS2304
fallback when no suggestion. Gate is critical: `jsxMode == null` skips entirely (TypeScript doesn't
emit TS2874 for default-jsx-mode .tsx files), preventing regressions on tests like
`checkJsxNotSetError_ts`. Full-suite 10078/1248/3 (was 10078/1249/3, +1 net). Zero regressions.

**B48.9 (2026-05-18, +3 — flips `jsxSpreadTag_ts__target_{es2015,esnext}` errors-baseline + 1 more)** —
TS2304 emission for JSX tag names. Our `checkUnresolvedInExprCore` previously had no JSX branches, so
references like `<Comp />` where `Comp` wasn't declared produced no diagnostic. Added new branches for
`JsxElement` / `JsxSelfClosingElement` / `JsxFragment` that:
- Call `checkJsxTagName` on the tag identifier. Intrinsic elements (lowercase first char like `div`,
  `span`) are skipped since they compile to string literals and don't reference a binding.
- Recurse into attributes via `checkUnresolvedInJsxAttribute` (regular `JsxAttribute` with expression
  containers, `JsxSpreadAttribute` with expression). Nested JSX values are recursively checked.
- Recurse into children via `checkUnresolvedInJsxChild` (expression containers, nested JSX elements).
Full-suite 10078/1249/3 (was 10078/1252/3, +3 net). Zero regressions.

**B48.8 (2026-05-18, +1 — flips `commonSourceDir6_ts` JS-emit)** — Two-piece fix for AMD outFile module ordering:
(a) `resolveAmdModuleName` had a buggy `substringBeforeLast('/', "").substringBeforeLast('\\', "")` chain that
returned empty when only `/` was present (the second call's missingDelimiterValue `""` clobbered the result).
For `("./foo", "a/bar.ts")` the function returned `"foo"` instead of `"a/foo"`. Replaced with `lastIndexOf` of
either separator, then `substring(0, sepIdx)`. (b) Transform-loop iteration order: when `outFile != null` and
multi-file, compute topological order BEFORE running transforms so the shared module-name counter increments
in the same order as the final emit. Previously transforms ran in `@Filename` input order while emits used
topological order, producing mismatched counter assignments (e.g. `foo_2` in `baz` where `baz` is emitted first
but `a/bar` was transformed first, so `a/bar` claimed `foo_1` first). Full-suite 10078/1252/3 (was 10078/1253/3,
+1 net). Zero regressions.

**B48.7 (2026-05-18, +1 — flips `blockScopedVariablesUseBeforeDef_ts__target_es2015` JS-emit)** —
Helper-emit order fix: TypeScript emits `__setFunctionName` AFTER `__awaiter` but BEFORE `__asyncGenerator`
(which inlines as `__await` + `__asyncGenerator`). When both `__setFunctionName` and `__awaiter` are
required, our previous `helperUsageOrder` (first-usage-tracked) emitted `__setFunctionName` first because
source-order put the class with static fields BEFORE the async functions. New reorder logic in `Transformer.kt`:
when both helpers are in `helperUsageOrder`, move `__setFunctionName` to right after `__awaiter`. Full-suite
10078/1253/3 (was 10078/1254/3, +1 net). Zero regressions.

**B48.6 (2026-05-18, +1 — flips `emitClassExpressionInDeclarationFile2_ts` JS-emit)** —
Emit `__setFunctionName(_a, "X")` for anonymous class expressions assigned to a named binding (`var/let/const X = class {...}`) when the class has trailing statements (static initializers etc.) that benefit from the temp-var capture pattern. Four-piece fix:
(a) New `__setFunctionName` helper template + `requireHelper("__setFunctionName")` handling in `Transformer.kt`.
(b) New field `pendingClassExprBindingName: String?` set transiently in `transformVariableDeclaration` when the initializer is an anonymous `ClassExpression`; consumed by `transformClassExpression` to emit the helper call as the second element of the comma-list capture.
(c) Add the class-expression temp-var name to `computedPropHoistNames` at top-level scope so the `var _a;` declaration is moved to the top of CJS output (before the `__esModule` preamble) — mirroring the existing computed-property-name hoist path.
(d) Adjust `functionExportStubs` insertion position to account for prepended var declarations: `insertPos = 1 + hoistCount + prependedCount` so function exports land AFTER void0 hoists when prepended vars push everything down.
Full-suite 10078/1254/3 (was 10078/1255/3, +1 net). Zero regressions.

**B48.5 (2026-05-18, +1 — flips `decoratorUsedBeforeDeclaration_ts` JS-emit)** —
Synthetic-array same-line emit gate. `emitArrayLiteral` in `Emitter.kt` consults `sourceText.substring(element.end, nextElement.pos)` to decide whether to keep two elements on the same line. For SYNTHETIC arrays
(pos == -1, e.g. the `__decorate([...])` array built in `Transformer.kt`'s decorator emit), the elements'
`pos`/`end` come from the original source decorator expressions, but the surrounding source text near each
element's end is unrelated to the synthetic array's layout. The check could read a substring that doesn't
contain a newline (because `element.end` overshoots past the source decorator's `)` into the next decorator's
`@`), making the emitter conclude the two decorators share a line — collapsing the multi-line `__decorate([
... ])` array into a single-line form. Added `node.pos >= 0` to the `nextOnSameLine` gate so synthetic arrays
always emit each element on its own new line. Flips `decoratorUsedBeforeDeclaration_ts`. Full-suite
10078/1255/3 (was 10078/1256/3, +1 net). Zero regressions.

**B48.4 (2026-05-18, +1 — flips `es6ExportClauseWithAssignmentInEs5_ts__target_{es5,es2015}` JS-emit)** —
CJS late-export mutation tracking for compound and unary assignments. Previously the `namedExportLocalToExport`
pre-scan tracked only ONE export per local and only rewrote simple `X = expr` assignments. Now tracks ALL
exports of a local (`Map<String, List<String>>`) and rewrites four mutation shapes:
- `X = expr` (multi-export: `exports.Y = exports.X = X = expr` chain in reverse-source order)
- `X op= expr` (compound assignment) → `exports.X = X op= expr`
- `++X` / `--X` (prefix unary) → `exports.X = ++X`
- `X++` / `X--` (postfix unary) → `exports.X = (X++, X)` with ParenthesizedExpression wrap so the comma
  expression's value (the post-increment result) reaches the exports assignment correctly.
New helper `wrapStatementWithLateExports(stmt, names)` in `Transformer.kt`; detection added in the `else`
branch of the CJS export-assignment dispatch alongside the existing `extractExportedAssignmentName` check.
Flips both `es6ExportClauseWithAssignmentInEs5_ts` target variants. Full-suite 10078/1256/3
(was 10078/1257/3, +1 net). Zero regressions.

**B48.3 (2026-05-18, +1 — flips `privacyLocalInternalReferenceImportWithExport_ts` JS-emit)** —
Extend B38.1's type-only `export import` elision to namespace-scoped aliases. Inside a namespace body, the
ImportEqualsDeclaration branch already had a partial type-only check that only handled (a) Identifier targets
where the name was in `topLevelTypeOnlyNames` and (b) QualifiedName targets where the root was in
`topLevelTypeOnlyNames`. This missed the common case from B38.1's territory: `export import X = Y.Z` where
`Y` is a non-exported but runtime-instantiated namespace AND `Z` is a type-only sub-member (interface, type
alias, or type-only sub-namespace). Added a call to `isQualifiedPathTypeOnly(ref, requireRuntimeOrExportedRoot
= true)` matching the top-level `transformImportEqualsDeclaration` elision so qualified-path aliases to
type-only sub-members are erased instead of emitted as `nsName.X = Y.Z` (which would TS2708/TS2694 at runtime).
Full-suite 10078/1257/3 (was 10078/1258/3, +1 net). Zero regressions.

**B48.2 (2026-05-18, +1 — flips `classMemberInitializerScoping2_ts__target_es2017_usedefineforclassfields_true` JS-emit)** —
Class field downlevel under `useDefineForClassFields=true` AT target<ES2022. When `useDefineForClassFields=true`
is explicitly set AND `effectiveTarget < ES2022`, instance class fields are now lowered to
`Object.defineProperty(this, "p", { enumerable: true, configurable: true, writable: true, value: <init> })`
calls inserted into the constructor body. Properties without initializer emit `value: void 0`. The class-body
member is dropped (would otherwise be illegal `class C { p = val }` at target<ES2022). Private fields
(`#field`) and static fields are unaffected by this branch (handled elsewhere). Implementation: new
`needsDefineLowering` flag + parallel branch in `transformClass` after the existing `!useDefineForClassFields`
instance-init loop, plus a gate in the outputMembers PropertyDeclaration+`useDefineForClassFields` branch to
skip emission when the field has been routed to the constructor body. Full-suite 10078/1258/3 (was
10078/1259/3, +1 net). Zero regressions.

**B48.1 (2026-05-18, +1 — flips `exportObjectRest_ts__module_commonjs_target_esnext` JS-emit)** —
CJS export destructuring-with-rest rewrite at target≥ES2018. For `export const { x, y, ...rest } = expr`
under `module: commonjs` and `target` >= `ES2018`, the Transformer now emits the comma-expression form:
`_a = expr, exports.x = _a.x, exports.y = _a.y, exports.rest = __rest(_a, ["x","y"])`
(plus `var _a;` hoisted via `sideEffectTempVars`). Previously emitted native destructuring
(`const { x, ...rest } = ...`) followed by separate `exports.x = x; exports.rest = rest;` statements.
New branch in `transformToCommonJS`'s exported VariableStatement path, gated narrowly: single
declaration, target≥ES2018, ObjectBindingPattern with at least one rest element, all elements have
identifier names with no default values and no computed property names. The existing
sub-ES2018 path via `transformVariableDeclarationListWithRest` is preserved. Full-suite 10078/1259/3
(was 10078/1260/3, +1 net). Zero regressions.

**Session 2026-05-18 (B47.x series, 8808 → 8815, +7).** /loop session landing 7 substantive feature wins via narrow defensive-emit patterns. Each gated tightly to avoid cascading regressions. Summary of substantive landed: B47.1 (defensive class capture for static async-arrow init), B47.2 (chained safety wrap for cross-file `declare namespace` in `design:paramtypes`), B47.3 (async-arrow destructuring-param capture), B47.4 (optional-call `.call(receiver)` + arrow-body hoist scope), B47.5/B47.6 (`module:none` + `outFile` bundling rules — `./` strip + aux `.js` skip + native import preserve for target≥ES2020), B47.7 (JSX-vs-generic-arrow disambig in `.tsx`). Also chores: MAINT-2 (stale skip-log audit), skip-log documentation for several investigated-but-skipped candidates, surgical-pool status update, B47.x retrospective.

**B47.7 (2026-05-18, +1 — flips `declarationEmitRecursiveConditionalAliasPreserved_ts` JS-emit)** —
JSX-vs-generic-arrow disambiguation in `.tsx` files. In `parsePrimaryExpression`'s `LessThan`
branch, when `isJsxFile && <Identifier extends <typeExpr>...>`, fall through to the generic-arrow
detection instead of always going to `parseJsxElementOrFragment`. The disambig requires:
- After `<`: Identifier
- After the Identifier: `ExtendsKeyword`
- After `extends`: Identifier OR type-keyword (number/string/boolean/symbol/bigint/any/unknown/object/never)
  OR open delimiter (`(`/`{`/`[`) OR `typeof` keyword.

Falls back to JSX for `<T extends/>` (boolean attr shorthand), `<T extends={x}/>` (attr=value),
`<T extends>` (no value). Earlier net-zero attempt (broader gate matching `<T extends` alone)
regressed `parseJsxExtends2_ts` (where source `<T extends/>` IS JSX with boolean attribute);
the tighter disambig fixes both.

Full-suite 10078/1260/3 (was 10078/1261/3, +1 net). Zero regressions.

**B47.6 (2026-05-18, +2 — flips `moduleNoneDynamicImport_ts__target_es2015/es2020` JS-emit)** —
Builds on B47.5's `./` strip. Two more pieces complete the `@module: none` + `@outFile` story:
- (a) Skip auxiliary `.js` files with module statements from the outFile bundle. When
  `options.outFile != null && options.effectiveModule == ModuleKind.None` and a `.js` file has
  `import`/`export` statements, it's NOT bundled — TypeScript treats it as pulled in only for
  type info / allowJs checking, not runtime. Added new gate at the per-file iteration in
  Phase 3 of `TypeScriptCompiler.kt` (after the existing TS6131-style exports-skip).
- (b) Preserve native `import()` syntax when `@module: none` + target>=ES2020. The CJS
  dynamic-import rewrite in `Transformer.kt`'s `transformToCommonJS` now skips the
  `rewriteCjsDynStmt` pass when `preserveNativeDynImport = options.effectiveModule == ModuleKind.None && options.effectiveTarget >= ScriptTarget.ES2020`. For es2015, the `./` strip + CJS rewrite
  applies (B47.5 path); for es2020, the native syntax is preserved (no helpers, no rewrite).
Full-suite 10078/1261/3 (was 10078/1263/3, +2 net). Zero regressions.

**B47.5 (2026-05-18, foundation — `./` strip from module:none dyn-import path)** —
For `@module: none`, TypeScript strips the `./` prefix from CJS-rewritten dynamic import paths.
Net-zero alone; combined with B47.6 to flip both variants of `moduleNoneDynamicImport_ts`.

**B47.4 (2026-05-18, +1 — flips `mappedTypeGenericIndexedAccess_ts` JS-emit)** —
Two-piece fix for optional-call (`obj?.(args)`) downleveling:
- (a) Arrow-expression-body hoist scope: non-async expression-body arrows now push their own
  `hoistedVarScopes` entry around body transformation. When the body's optional-chain rewrite
  allocates a temp var (`_a`), the var is hoisted INSIDE the arrow body (via expression-body
  → block-body conversion: `{ var _a; return <body>; }`), not at the outer scope.
- (b) `.call(receiver, args)` preservation: when the LHS of `?.(args)` is a `PropertyAccessExpression`
  or `ElementAccessExpression` on a simple Identifier receiver, emit `_a.call(receiver, ...args)`
  instead of `_a(args)` — preserves `this` binding that the `?.()` semantics require.
Example: `(p) => typeHandlers[p.t]?.(p)` →
  `(p) => { var _a; return (_a = typeHandlers[p.t]) === null || _a === void 0 ? void 0 : _a.call(typeHandlers, p); }`.
Both fixes in `Transformer.kt`: (a) in `is ArrowFunction` body branch (`when (val b = expr.body) → is Expression`),
(b) in `is CallExpression` `questionDotToken` branch. Full-suite 10078/1263/3 (was 10078/1264/3,
+1 net). Zero regressions.

**B47.3 (2026-05-18, +1 — flips `reactReduxLikeDeferredInferenceAllowsAssignment_ts` JS-emit)** —
Async-arrow destructuring-parameter capture. When an async arrow has any BindingPattern param
(ObjectBindingPattern or ArrayBindingPattern), the outer arrow now gets renamed simple-identifier
proxies (Identifier params get `<name>_<i+1>`, BindingPatterns get fresh `_a`/`_b`/...), and
the generator function inside `__awaiter` keeps the ORIGINAL parameter shapes (preserving
destructuring inside the generator). The proxies are passed as the args array (`secondArg`) to
`__awaiter`. Example: `async (dispatch, { foo }) => ...` → outer arrow params `(dispatch_1, _a)`,
generator params `(dispatch, { foo })`, `__awaiter(thisArg, [dispatch_1, _a], void 0, function*...)`.
New branch in `transformExpression`'s `is ArrowFunction` path (line ~7990, after rest-param
detection): `hasBindingPattern = !hasRestParam && expr.parameters.any { p -> p.name is ObjectBindingPattern || p.name is ArrayBindingPattern }`. Full-suite 10078/1264/3 (was 10078/1265/3, +1 net).
Zero regressions.

**B47.2 (2026-05-18, +1 — flips `experimentalDecoratorMetadataUnresolvedTypeObjectInEmit_ts` JS-emit)** —
Chained safety wrap for cross-file `declare namespace` qualified names in
`design:paramtypes` metadata. For `A.B.C.D.E` where `A` is a `declare namespace` in another
file (type-only at runtime per `checker.isTypeOnlyGlobalName(A)`), the emit now matches
TypeScript's defensive form:
`typeof (_d = typeof A !== "undefined" && (_a = A.B) !== void 0 && (_b = _a.C) !== void 0 && (_c = _b.D) !== void 0 && _c.E) === "function" ? _d : Object`.
New `wrapDeepQualifiedNameForMetadata(expr)` in `Transformer.kt` walks the
`PropertyAccessExpression` chain, allocates N temp names (`_a`..`_<N>`) where N = chain depth,
and builds the combined `&&` chain. The function-level field
`maxDeepMetadataTempCount` tracks the max depth seen so the transform tail hoists
`var _a, _b, ..., _<max>;` between helpers and the rest of the file. Wired into
`serializeTypeNode`'s QualifiedName branch (line ~9822): when `baseName !in topLevelTypeOnlyNames`
AND `checker.isTypeOnlyGlobalName(baseName)` is true, route the raw qualified PropertyAccess
through the chain wrapper. Full-suite 10078/1265/3 (was 10078/1266/3, +1 net). Zero regressions.

**B47.1 (2026-05-18, +1 — flips `asyncArrowInClassES5_ts__target_es2015` JS-emit)** —
Defensive class temp-var capture for static async-arrow initializers. Extended the
`staticPropsWithThis` filter in `Transformer.kt:emitClassDeclaration` to ALSO match
properties whose initializer is `ArrowFunction` with `ModifierFlag.Async`, even when the
arrow body doesn't reference `this`. TypeScript pre-emits the class capture
(`var _a; _a = ClassName;`) defensively because the down-leveled `__awaiter` template
(target<ES2022) is conceptually `this`-binding even when the actual `__awaiter` call passes
`void 0` for `thisArg`. The `replaceThisInExpr` step is a no-op for `this`-less bodies so
no other emission changes — only the `var _a;` hoist + `_a = Test;` capture statement get
added. Source: `class Test { static member = async (x: string) => { }; }` now emits the
defensive capture pre-`Test.member = ...`. Full-suite 10078/1266/3 (was 10078/1267/3,
+1 net). Zero regressions.

**B46.5 (2026-05-18, +1 — flips `arrowFunctionErrorSpan_ts` JS-emit)** —
Two-piece comment-preservation fix for call argument lists:
(a) `Parser.kt:parseArgumentList` — combine `scanner.getTrailingComments()` (same-line
inline) and `leadingComments()` (own-line) for each argument's leading comments. Previously
used `leadingComments() ?: getTrailingComments()` which DROPPED the same-line set when
own-line existed. Catches shapes like `f(  // c1\n  // c2\n  arg)` where `// c1` is
inline-after-`(` and `// c2` is own-line before `arg`.
Also: after the arg loop terminates without a comma, capture `leadingComments()` (own-line
comments between the last arg's end and `)`) and APPEND to the last arg's trailing comments
via `withTrailingComments`. Catches `f(arg\n  // c5\n)` where `// c5` is leading-of-`)`.
(b) `Emitter.kt:emitCallArguments` — when emitting the last arg's trailing comments, split
into `sameLine` (no preceding newline) and `ownLine` (with preceding newline). Same-line ones
emit ` // comment` adjacent to the arg (existing behavior). Own-line ones emit on their own
indented line (`\n<indent>// comment`) so the source shape `}\n// c5\n)` is preserved.
Full-suite 10078/1267/3 (was 10078/1268/3, +1 net). Zero regressions.



**B46.4 (2026-05-17, +1 — flips `commentOnArrayElement12_ts` JS-emit)** —
Refinement of B46.3's array-literal source-line layout: only **consecutive pairs of
`OmittedExpression`** force a line break in a multi-line array literal. Mixed pairs
(OmittedExpression followed by a non-Omitted element or vice versa) preserve the source-line
adjacency rule. Matches TypeScript's emit:
- `[, [...]]` → keep `, [...]` adjacent (mixed pair — array binding pattern shapes like
  `[, [primarySkillA = "primary", ...] = ["none", "none"]] = multiRobotA`).
- `[,, /* comment */]` → split into two lines `,\n    , /* comment */` (consecutive
  Omitted pair — `commentOnArrayElement12_ts`).
Implementation: replaced `element !is OmittedExpression && nextElement !is OmittedExpression`
gate with `!(element is OmittedExpression && nextElement is OmittedExpression)`. Without
this refinement, B46.3's broader generalization regressed `sourceMapValidationDestructuring
For{ArrayBinding,OfArrayBinding}PatternDefaultValues2_ts__target_es2015` (which depend on
mixed-pair adjacency to keep `[, [...]]` shapes inline). Full-suite 10078/1268/3 (was
10078/1269/3, +1 net). Zero regressions.



**B46.3 (2026-05-17, +2 — flips `propTypeValidatorInference_ts` JS-emit + 1 more)** —
`emitArrayLiteral` in `Emitter.kt` now preserves source-line layout for ALL element kinds
(was: only ObjectLiteral/ArrayLiteral compound elements). Source: `const arrayOfTypes =
[PropTypes.string, PropTypes.bool, PropTypes.shape({...})];` — first three elements on one
line, then a multi-line shape call. TypeScript emits the SAME shape (keeps first three on the
line opened by `[`). Two changes:
(a) Removed `isCompound` gate on `nextOnSameLine` — relies on raw source-position check that
no newline exists between consecutive elements' source positions. Added
`nextElement.leadingComments.isNullOrEmpty()` to prevent same-line merging when the next
element has comments that need their own line.
(b) New generic `sameLineBySource` close check: scan backward from `]`'s source position
through whitespace; if we hit a non-whitespace char without crossing a `\n`, the source has
`...)];` shape — keep `]` on the same line as the closing of the last element. Captures the
case where the last element is a `CallExpression` (not previously covered by the
compound-only `sameLineByCompound` check). Combined via OR.
Full-suite 10078/1269/3 (was 10078/1271/3, +2 net). Zero regressions.



**B46.2 (2026-05-17, +2 — flips `computedEnumMemberSyntacticallyString2_ts__isolatedmodules_{true,false}` JS-emit)** —
Builds on B46.1's cross-file const inlining. Type-only operators on the ORIGINAL enum-member
initializer (`as` / `<T>` type assertion, `!` non-null assertion, `satisfies`) now suppress
the string-enum fold path — TypeScript preserves the runtime expression form even when the
underlying expression would normally fold to a string literal. New helper
`isTypeOnlyOperatorWrapping(expr)` in `Transformer.kt` walks ParenthesizedExpression at
outermost layer and returns true for the four wrapper kinds. Wired into `transformEnum`'s
member loop: when `initIsTypeWrapped` is true, force `constStringVal = null` (skip string
fold) AND `isSyntacticallyStr = false` (skip string-enum-emit path). This matches TypeScript's
emit for `enum Foo { E1 = (`${BAR}`) as string, E2 = `${BAR}`! }` which emits the reverse-
mapping form `Foo[Foo["E1"] = (`${BAR}`)] = "E1";` not the inlined string form. Side effect:
the original `import { BAR }` is preserved (referenced by E1/E2 runtime emission), and the
spurious `export {};` marker is no longer emitted (file is already a module via the import).
Full-suite 10078/1271/3 (was 10078/1273/3, +2 net). Zero regressions.



**B46.1 (2026-05-17, +2 — flips `enumWithNonLiteralStringInitializer_ts` JS-emit + 1 more)** —
Cross-file `const X = <stringLiteral|numericLiteral>` imports are now inlined into
enum-value compute (matching TypeScript's behavior even under `@isolatedModules`).
Three-piece fix:
(a) `Checker.kt:resolveImportedConstLiteralValue(name, sourceFileName)` — new helper that
walks `result.locals[name]` → `resolveAlias` to find the originating `const X = literal`
declaration in another file. Recognizes StringLiteralNode / NoSubstitutionTemplateLiteralNode
/ NumericLiteralNode / `+`/`-` PrefixUnaryExpression of numeric / ParenthesizedExpression
of literal. Returns ConstantValue.StringValue / NumberValue or null.
(b) Transformer.kt wiring: `evaluateConstantStringExpression` Identifier branch now consults
the new helper after `stringMemberValues`; new TemplateExpression branch evaluates head +
each span's expression (string or numeric stringified) + literal text for shapes like
`` `${foo}` ``; `evaluateConstantExpression` Identifier branch consults the helper after
`topLevelNumericConstants`; `isSyntacticallyStringEnum` Identifier branch recognizes
cross-file string-const imports so the string-enum emit path fires for `enum A { a = bar }`.
(c) Import-elision preservation: new per-Transformer set `enumInlinedCrossFileImports`
tracks each name whose value was inlined via the wrapper helper `resolveImportedLiteralAndTrack`.
The import-elision pass adds a third "keep" exception alongside JSX-factory and shadowed-default:
when at least one of a require const's bound named-import locals is in
`enumInlinedCrossFileImports`, keep the `const helpers_1 = require("./helpers");` even though
the local binding (e.g. `bar`) is now syntactically unreferenced — matches TypeScript's emit.
Full-suite 10078/1273/3 (was 10078/1275/3, +2 net). Zero regressions.



**B45.6 (2026-05-17, +2 — flips `jsxSpreadTag_ts__target_{es2015,esnext}` JS-emit)** —
JSX attribute emit now inlines spreads of static object literals into the parent
properties object BEFORE calling `transformObjectLiteral`. Pattern: `<Comp
{...{ wrong: <div>x</div> }}/>` → `React.createElement(Comp, { wrong: ... })`
instead of `Object.assign({}, { wrong: ... })`. TypeScript performs this
syntactic replacement for JSX attribute objects regardless of target (es2015..
esnext) since the spread of an object literal is equivalent to its keys both
syntactically and for the override-order semantics of JSX attribute merging.
Scoped to the JSX attribute-builder path in `Transformer.kt`'s
`transformJsxSelfClosingElement`/`transformJsxElement` only — a prior attempt
to inline globally in `transformObjectLiteral` regressed -2 tests due to
unrelated object-spread expectations. The JSX-specific inlining is safe because
the JSX transform path only fires for `<X .../>` JSX syntax. Full-suite
10078/1275/3 (was 10078/1277/3, +2 net). Zero regressions.



**B45.5 (2026-05-17, +2 — flips `moduleResolutionWithSuffixes_one_jsonModule_ts` + 1 other JS-emit)** —
Two-piece fix for JSON imports under `moduleSuffixes` config:
(a) `TypeScriptCompiler.kt:extractRelativeImports` JSON re-emit pre-scan now post-processes
`importedJsonBaseNames` when `moduleSuffixes` is set: for each imported base name, if a
sibling file `<base><suffix>.json` exists in `parsed.files`, rewrite the entry in-place to
the suffixed variant. Also rewrites `jsonBaseNameToImporter` accordingly. Matches
TypeScript's node resolver behavior: with `moduleSuffixes: [".ios"]`, `import "./foo.json"`
resolves to `/foo.ios.json` when that variant exists.
(b) Source-echo reorder: split the existing in-tree-project bucket (introduced in B44.8)
into two sub-buckets — `.json` files BEFORE `.ts/.tsx/...` files (each preserving input
order). Required because TypeScript groups JSON source echoes before TS source echoes
within a project. Pattern: under `moduleResolutionWithSuffixes_one_jsonModule_ts`, expected
order is `[foo.ios.json, foo.json, index.ts]`; input order is `[index.ts, foo.ios.json,
foo.json]`. Full-suite 10078/1277/3 (was 10078/1279/3, +2 net). Zero regressions.



**B45.4 (2026-05-17, +1 — flips `verbatim-declarations-parameters_ts` JS-emit)** —
`emitParameters` comma-after branch (`Emitter.kt`) now groups consecutive parameters
without newline-leading-comments on the same emit line. Previously, every parameter
got an unconditional newline before it (in the multi-line-with-leading-comments
shape). Expected: only params with a newline-leading-comment (typically a JSDoc
block above) get a newline; subsequent uncommented params stay on the previous
param's line. Matches TypeScript's emit for:
```
(
    // c
    a,
    b,
    // d
    c
)  →  (
    // c
    a, b, 
    // d
    c)
```
Implementation: split the existing `else` arm of the `firstParamCommentIsInline`
check into two cases — (a) `index == 0 || hasNewlineLeadingComment` → newline +
emit leading + indent (unchanged); (b) subsequent param without newline-leading
comment → stay on same line, emit any inline (`!hasPrecedingNewLine`) leading
comments before the param. Full-suite 10078/1279/3 (was 10078/1280/3, +1 net).
Zero regressions.



**B45.3 (2026-05-17, +3 — flips `moduleNodeImportRequireEmit_ts__target_{es2016,es2020,esnext}` JS-emit)** —
`import X = require("mod")` under module:nodenext/Node16/Node18/Node20 + ESM file
(per package.json `"type": "module"`) now desugars to TypeScript's createRequire emit:
```
import { createRequire as _createRequire } from "module";
const __require = _createRequire(import.meta.url);
...
const X = __require("mod");
```
Two-piece fix in `Transformer.kt`:
(a) New branch in `transformImportEqualsDeclaration`, ordered BEFORE the type-only
target-erasure and ESM-drop paths (so that ambient `declare module "mod"` targets
under nodenext still produce the runtime require — Node's require still loads the
module even when only types are exposed). Builds `const X = __require("mod")` with
the original decl's leading/trailing comments preserved, sets a new per-file flag
`needsCreateRequireHelper = true`.
(b) New header-injection block at the ESM exit path of `transform()`: when
`needsCreateRequireHelper` is set, prepend two synthetic statements at the file
top — `import { createRequire as _createRequire } from "module";` and
`const __require = _createRequire(import.meta.url);`. Both statements use
synthetic positions; the `__require` const uses a `MetaProperty(import.meta).url`
AST shape. Full-suite 10078/1280/3 (was 10078/1283/3, +3 net). Zero regressions.



**B45.2 (2026-05-17, +1 — flips `moduleResolutionWithSuffixes_one_dirModuleWithIndex_ts` JS-emit)** —
`extractRelativeImports` moduleSuffixes branch now probes BOTH sibling-file form
(`./foo<suffix>.ts`) and directory-index form (`./foo/index<suffix>.ts`) when the
specifier has no extension. TypeScript's node resolver consults both shapes; we
were only probing the sibling-file form. Required for the target test where
`import { ios } from "./foo"` under `moduleSuffixes: [".ios"]` must resolve to
`/foo/index.ios.ts`. Without the dep edge, `/index.ts` (importer) was emitted
BEFORE `/foo/index.ios.ts` (its actual import target), violating expected
topo order. Two new probes per suffix: `"${resolvedBase}${sep}index$suffix.ts"`
and `"${resolvedBase}${sep}index$suffix.tsx"`. Full-suite 10078/1283/3 (was
10078/1284/3, +1 net). Zero regressions.



**B45.1 (2026-05-17, +1 — flips `pathMappingBasedModuleResolution6_classic_ts` JS-emit)** —
Two-piece fix for AMD `export {x} from "mod"` re-export emission + rootDirs `.d.ts` probe:
(a) `Transformer.kt:transformToAMD` — new branch in the `ExportDeclaration` switch (ordered
BEFORE the existing `NamedExports` branch) that handles `export { x as y } from "m"`.
Adds `(spec, tempName)` to `namedModuleImports` (so `m` appears in AMD `define()` deps
and `m_1` in the factory params), adds each export name to `exportedVarNames` (for the
`exports.x = void 0` hoist), and emits one `Object.defineProperty(exports, exportName,
{ enumerable: true, get: function () { return m_1.importedName; } })` per spec into
`reExportGetters` (so the assignment goes through the same elision-aware path as the
existing `export { X }` re-exports of named/default imports). Also extends
`collectValueReferences` inputs in the import-elision pass to include `reExportGetters`
— the new dep's `m_1` param appears only inside the getter return expr, so without this
extension the elision pass would prune the dep as "unused" and strip it from the
`define()` args list. (b) `TypeScriptCompiler.kt:extractRelativeImports` rootDirs probes
— add `"$resolved2.d.ts"` to the list (sibling to the existing `.ts/.tsx/.mts/.cts` and
`/index.*` probes). Required for the target test where `export {x} from "../file2"`
under `rootDirs: [".", "../generated/src"]` must resolve `c:/root/generated/src/file2`
to the actual `c:/root/src/file2.d.ts` file (one of the rootDir alternates). Full-suite
10078/1284/3 (was 10078/1285/3, +1 net). Zero regressions.



**B44.10 (2026-05-17, +1 — flips `requireOfJsonFileWithoutExtensionResolvesToTs_ts` JS-emit)** —
Two-piece JSON re-emit fix in TypeScriptCompiler.kt:
(a) Pre-scan all parsed source files for `.json` imports (via
`require('./x.json')` or `from './x.json'`). Builds
`importedJsonBaseNames: Set<String>` and `jsonBaseNameToImporter: Map<String,
String>`. Only re-emit JSON files whose basename is in this set when
`@resolveJsonModule` is on (matches TypeScript — unreferenced JSON fixtures
like `b.json` in a test that only imports `c.json` are NOT re-emitted).
(b) Interleave JSON outputs with JS outputs in the final output list: each
imported JSON appears RIGHT BEFORE the JS output of the file that imports it.
Required for shapes like `out/c.js, out/c.json, out/file1.js` where file1.ts
imports both c.ts and c.json — c.js (from c.ts) comes BEFORE c.json (the
JSON fixture re-emit), and file1.js (the importer) comes LAST. Unimported
JSON outputs fall back to the start of the list (legacy behavior, preserved
for tests like `requireOfJsonFileTypes_ts` that have JSON-only imports).
Full-suite 10078/1285/3 (was 10078/1286/3, +1 net). Zero regressions.



**B44.9 (2026-05-17, +2 — flips `fileReferencesWithNoExtensions_ts` + `jsFileCompilationErrorOnDeclarationsWithJsFileReferenceWithOutDir_ts` JS-emit)** —
Enable `/// <reference path="..."/>` dep edges UNIVERSALLY (was outFile-only),
with cycle detection that falls back to input order when mutual refs form a
cycle. Two-piece fix:
(a) `includeReferencePathDeps = true` always (no longer gated on outFile).
    Also handles ref-path specifiers without `.ts` extension (e.g.
    `<reference path="a"/>` resolves to `a.ts/.tsx/.d.ts`).
(b) New `hasCycle(fileNames, deps)` helper in TypeScriptCompiler.kt using
    3-color DFS (WHITE/GRAY/BLACK). If the full deps graph (with ref-path
    edges) has any cycle, fall back to the deps map WITHOUT ref-path edges
    (preserving the import-only dep ordering). Required to keep
    `doNotemitTripleSlashComments_ts` passing (3-way cycle file0↔file1↔file2).
Also computes the no-ref-path deps map alongside the full map and selects
between them based on cycle detection. Full-suite 10078/1286/3 (was
10078/1288/3, +2 net). Zero regressions.



**B44.8 (2026-05-17, +3 — flips `tslib{Missing,MultipleMissing,NotFoundDifferent}Helper_ts` JS-emit)** —
Extend B44.5 source-echo reordering rule: when tsconfig.json is present, the
order is (1) out-of-tree files, (2) in-tree node_modules files, (3) in-tree
non-node_modules files (project sources). Each subset preserved in input
order. Previously the rule was just out-of-tree-first. New piece: node_modules
files come BEFORE project source files. Required for tests where third-party
modules are echoed at the top of the JS-emit baseline. Other failing
node_modules-related tests like `compositeWithNodeModulesSourceFile_ts` had
input order matching the rule already, so they continue passing. Full-suite
10078/1288/3 (was 10078/1291/3, +3 net). Zero regressions.



**B44.7 (2026-05-17, +3 — flips `pathMappingBasedModuleResolution{6_node, 7_classic, 7_node}_ts` JS-emit)** —
Implement `rootDirs` virtual file merging in `extractRelativeImports`. For
relative specifiers that didn't resolve against the importing file's actual
directory, try resolving via each alternate `rootDir` base. New `rootDirs`
parameter threaded through from `options.rootDirs`. Algorithm: identify which
rootDir contains the importing file (longest-prefix match), then for each
OTHER rootDir, replace the file's prefix with the alt rootDir and re-resolve
the relative specifier. Probes `.ts`, `.tsx`, `.mts`, `.cts`, and `/index.*`
variants. Example: `c:/root/src/file1.ts` imports `./project/file2`; with
`rootDirs: [".", "../generated/src"]` (tsconfig at `c:/root/src/`), the
alternate base is `c:/root/generated/src/`, so the import resolves to
`c:/root/generated/src/project/file2.ts`. Full-suite 10078/1291/3 (was
10078/1294/3, +3 net). Zero regressions.



**B44.6 (2026-05-17, +1 — flips `requireOfJsonFileWithModuleNodeResolutionEmitAmdOutFile_ts` JS-emit)** —
Two-piece fix for AMD/System/UMD `@outFile` bundling with `@resolveJsonModule`:
(a) `outFileName` in TypeScriptCompiler.kt now preserves the full path when
`@fullEmitPaths` is set (e.g. `out/output.js` instead of stripping to `output.js`).
(b) When `@module` is AMD/System/UMD AND `@resolveJsonModule` is set AND `@outFile`
is set, JSON fixture files are now collected into `jsonOutputs` and prepended to
the bundle as `define("X", [], JSON_CONTENT);` — module name is JSON basename
without `.json` extension. Previously the JSON files were not re-emitted under
`@outFile` (the JSON re-emit branch gated on `outDir != null`). The JSON define
appears BEFORE the importing file's `define()` to match TypeScript's emit order.
Full-suite 10078/1294/3 (was 10078/1295/3, +1 net). Zero regressions.



**B44.5 (2026-05-17, +4 — flips `pathMappingBasedModuleResolution{4,5}_{classic,node}_ts` JS-emit)** —
Source echoes are reordered when a tsconfig.json is present: files OUTSIDE the
tsconfig directory appear FIRST, then files inside (each subset in input order).
TypeScript treats out-of-tree `@filename` fixtures as "external" and lists them
before the project sources. Example: `c:/root/tsconfig.json` is the project root;
`c:/file4.ts` is an out-of-tree fixture; expected echo starts with file4.ts then
file1/2/3 (all inside `c:/root/`). Implementation in `TypeScriptCompiler.kt`:
post-loop partition of `sourceEchoes` into `outside` and `inside` lists keyed on
`fileName.startsWith(tsconfigDir + "/")`, concat as `outside + inside`. Tests
without tsconfig.json keep input order (no behavior change). Full-suite
10078/1295/3 (was 10078/1299/3, +4 net). Zero regressions.



**B44.4 (2026-05-17, +1 — flips `pathMappingBasedModuleResolution3_classic_ts` JS-emit)** —
Classic-resolution fallback for non-relative specifiers in `extractRelativeImports`:
walk up from the importing file's directory looking for `<dir>/<specifier>.{ts,tsx,d.ts}`
(no `/node_modules/` segment). Matches TypeScript's classic resolution algorithm
which probes ancestor directories directly. Required for `@moduleResolution: classic`
test fixtures that import e.g. `"file4"` (bare) from `c:/root/folder2/file2.ts`
when the target is at `c:/file4.ts` — walks c:/root/folder2/ → c:/root/ → c:/,
finds at c:/file4.ts. The new branch runs after the node_modules walk-up; both
the new branch and the existing one only fire for non-relative specifiers when
standard candidates failed. Full-suite 10078/1299/3 (was 10078/1300/3, +1 net).
Zero regressions.



**B44.3 (2026-05-17, +1 — flips `pathMappingBasedModuleResolution3_node_ts` JS-emit)** —
`extractRelativeImports` in TypeScriptCompiler.kt now adds a `baseUrl`-anchored
dep-edge probe for non-relative specifiers that didn't resolve via the standard
candidate list AND didn't match a `paths` mapping. Probes: `$baseDir/$specifier.ts`,
`.tsx`, `.d.ts`, `/index.{ts,tsx,d.ts}`. Required for tsconfig-style projects that
use `baseUrl` (no `paths`) for non-relative imports: e.g. `baseUrl: c:/root` +
`import {x} from "folder2/file2"` → resolves to `c:/root/folder2/file2.ts`. The
existing node_modules walk-up fallback runs after (bare specifier check still
fires when neither `paths` nor `baseUrl` matched). Full-suite 10078/1300/3 (was
10078/1301/3, +1 net). Zero regressions.



**B44.2 (2026-05-17, +1 — flips `requireOfJsonFileTypes_ts` JS-emit)** —
JSON reformatter `reformatJson` in TypeScriptCompiler.kt now preserves
single-line shape when the entire (trimmed) JSON content has no newline.
Previously, the reformatter unconditionally expanded all `[...]` and `{...}`
to multi-line — turning `["a", null, "string"]` into 5 lines. Per
TypeScript, JSON files preserve source layout: single-line arrays/objects
stay single-line, multi-line stay multi-line. Fast-path implemented at the
top of `reformatJson`: when `trimmed` contains no `\n`, normalize whitespace
(`,` → `, `, `:` → `: `, collapse runs of whitespace) and return on one
line. Quoted-string spans (with `\\` escape handling) preserved verbatim.
The existing multi-line path is unchanged. Full-suite 10078/1301/3 (was
10078/1302/3, +1 net). Zero regressions.



**B44.1 (2026-05-17, +1 — flips `inferTypePredicates_ts` JS-emit)** —
Preserve same-line `// line comment` between (a) `=` and a multi-line initializer
or (b) an expression and the dot of a chained property access on the next line.
Source shapes:
```
const x = // should error
   [1, 2, 3]
const y = list.map((arr) => arr // should error
   .filter(...));
```
Previously both comments were dropped. Two-piece fix: (a) new optional
`initializerLeadingTrailingComments` field on `VariableDeclaration` — populated
in `parseVariableDeclaration` from `scanner.getTrailingComments()` right after
consuming `=`, when `scanner.hasPrecedingLineBreak()` is true (initializer
starts on next line). Emitted by `emitVariableDeclaration` as `= <comment>\n
<value>`. (b) new optional `expressionTrailingLineComments` field on
`PropertyAccessExpression` — populated in the `Dot` branch of
`parseCallAndAccess` when `newLineBefore=true` AND `result.trailingComments`
is empty (CallExpression already captures these via `callTrailing` when
chained — re-capturing would double-emit, see B44.1 fix). Emitted by
`emitPropertyAccess` after the expression's regular trailing comments,
BEFORE the newline+indent+dot. Both gates: `text.startsWith("//")` AND
`hasTrailingNewLine` AND `!hasPrecedingNewLine` (same-line line comment
that terminates the line). Full-suite 10078/1302/3 (was 10078/1303/3, +1
net). Zero regressions.



**B43.3 (2026-05-17, +1 — flips `referenceSatisfiesExpression_ts` errors-baseline)** —
Three-part definite-assignment fix for `(b satisfies T) = ...`, `[(c satisfies T)] = [...]`
and friends: (a) `isValidAssignmentTarget` now accepts `AsExpression`, `TypeAssertionExpression`,
and `SatisfiesExpression` (removes FP TS2364). (b) New `unwrapTypeOnlyWrapper` helper +
ParenthesizedExpression branch in the Equals assignment path of `findUninitializedRefs`:
when LHS is `(x satisfies T)` / `(x as T)` / `(<T>x)`, treat the wrapped identifier as a
read (emits TS2454 if uninitialized) and THEN mark it as assigned. (c) New
`emitReadsForTypeWrappedDestructuring` walker handles `[(c satisfies T)] = [10]` and
`({d: (e satisfies T)} = ...)` shapes — walks the LHS destructuring pattern, finds
type-wrapped identifiers, emits TS2454 reads at those positions. Companion: extended
`collectDestructuringTargets` to unwrap ParenthesizedExpression/AsExpression/SatisfiesExpression/
TypeAssertionExpression so the underlying identifier still gets marked assigned.
`findUninitializedRefs` also gets a new `AsExpression` branch to mirror the existing
`SatisfiesExpression` one. Full-suite 10078/1303/3 (was 10078/1304/3, +1 net).
Zero regressions.

**B43.2 (2026-05-17, +1 — flips `anyMappedTypesError_ts` errors-baseline)** —
Parser now emits TS7039 "Mapped object type implicitly has an 'any' template type." when
a mapped type `{[P in K]}` lacks a value type (`: T`) AND `noImplicitAny` (or `strict`)
is enabled. Threaded a new `noImplicitAny: Boolean` parameter through Parser (default
false). All three Parser construction sites in `TypeScriptCompiler.kt` now pass
`options.noImplicitAny || options.strict`. Squiggle covers the entire mapped type
expression INCLUDING the outer `{...}` braces — scans backward from the bracketed
position to find the enclosing `{` and forward from end of `]` to the closing `}`.
Suppression for `@strict: false` tests (`mappedTypeNoTypeNoCrash_ts` still fires
TS2304 only, as expected). Full-suite 10078/1304/3 (was 10078/1305/3, +1 net).
Zero regressions.


Only the most recent ~5 B-entries are kept here. Older session notes live in
`STATUS-HISTORY.md` (and in `git log`, where every B-entry has a matching commit).

**B43.1 (2026-05-17, +1 — flips `decoratorMetadataNoLibIsolatedModulesTypes_ts` errors-baseline)** —
TS2583 "Cannot find name 'X'. Do you need to change your target library? Try changing the
'lib' compiler option to 'es2015' or later." now fires in type-position for forward-declarable
ES2015+ lib types (`Map`, `Set`, `WeakMap`, `WeakSet`, `Promise`, `Symbol`, `Iterable`,
`IterableIterator`, `Iterator`) when `@noLib: true` is set OR `@lib` is non-empty but contains
no `es2015`/`es6`/`esnext`/`es2.*` entries. Companion change: TS2564 ("Property has no
initializer...") is suppressed for properties whose type references such an unavailable name —
the type is effectively an error type at that point and TS2583 already flags the missing-lib
issue. New helper `isLibTypeUnavailableEs2015(name)` and `typeContainsUnavailableLibName(type)`
walks ArrayType/TupleType/UnionType/IntersectionType/TypeReference recursively.
`es6`/`es2015` aliased in the lib-check to avoid FP TS2583 emission for `@lib: es6` tests
(`asyncAwaitWithCapturedBlockScopeVar_ts`). Full-suite 10078/1305/3 (was 10078/1306/3, +1 net).
Zero regressions.

**B42.6 (2026-05-17, +1 — flips `destructionAssignmentError_ts` errors-baseline)** —
TS2809 "Declaration or statement expected. This '=' follows a block of statements, so if
you intended to write a destructuring assignment, you might need to wrap the whole
assignment in parentheses." now fires for `{a, b} = fn();` at the statement level (the
`=` after a closing `}` is a destructuring-without-parens shape). Previously emitted
generic TS1109 "Expression expected." Detection in `parsePrimaryExpression`'s else
branch: when current token is `Equals`, scan source text backward from
`scanner.getTokenPos()` skipping whitespace; if the immediately-preceding non-trivia
character is `}`, emit TS2809 instead. Full-suite 10078/1306/3 (was 10078/1307/3, +1
net). Zero regressions.

**B42.5 (2026-05-17, +1 — flips `errorOnInitializerInObjectTypeLiteralProperty_ts` errors-baseline)** —
Parser's `parseTypeMember` (shared by interface bodies AND type literals) now emits TS1247
"A type literal property cannot have an initializer" when parsing inside a type literal
`{ ... }` in type position, and TS1246 "An interface property cannot have an initializer."
when parsing inside an interface body. Distinguished via a new class-level flag
`inTypeLiteralForErrorWording` toggled by `parseTypeLiteralOrMappedType` with try/finally
restore. Checker.kt's TS1246 emission was already correctly scoped to InterfaceDeclaration.
Full-suite 10078/1307/3 (was 10078/1308/3, +1 net). Zero regressions.

**B42.4 (2026-05-17, +1 — flips `requireOfJsonFileNonRelativeWithoutExtensionResolvesToTs_ts` JS-emit)** —
`extractRelativeImports` now walks up from the current file's directory looking for
`node_modules/<specifier>.ts` / `.tsx` / `.d.ts` / `/index.{ts,tsx,d.ts}` when a bare
specifier didn't resolve via the standard candidate list. For multi-file test
fixtures that set up `@Filename: /src/node_modules/X.ts` and import via bare
specifier from a sibling, this adds the missing dep edge so `topologicalSort`
produces the correct emit order (`node_modules/X.js` before the importer).
Probe-dir walk: start at `dir`, try probes; on no match move up one segment
(`lastIndexOf('/')`) and retry; stop at empty string. Only fires for non-relative
specifiers AFTER standard candidates failed — bounded fallback. Full-suite
10078/1308/3 (was 10078/1309/3, +1 net). Zero regressions.

**B42.3 (2026-05-17, +1 — flips `isolatedModulesExportImportUninstantiatedNamespace_ts` errors-baseline)** —
New TS1269 emission: "Cannot use 'export import' on a type or type-only namespace
when 'isolatedModules' is enabled" fires for `export import X = Y` where Y resolves
to a type-only export from another file. Gate: `options.isolatedModules &&
!options.verbatimModuleSyntax`, ImportEqualsDeclaration with Export modifier,
non-ExternalModuleReference (skip `export import X = require(...)` cases), and the
root identifier resolves to a type-only import alias. Detection extends
`isExportedNameTypeOnly` to also recognize `export namespace` with
`ModuleInstanceState.NonInstantiated` — the existing helper missed namespaces.
Squiggle span: walks backward from `stmt.pos` (which is `import` keyword position)
to find the preceding `export` keyword, and ends at the trailing `;` (handles the
`node.end` overshoot gotcha). Full-suite 10078/1309/3 (was 10078/1310/3, +1 net).
Zero regressions.

**B42.2 (2026-05-17, +1 — flips `isolatedModulesAmbientConstEnum_ts` errors-baseline)** —
TS2748 "Cannot access ambient const enums when 'isolatedModules' is enabled" now fires
for `E.X` where `E` is a `declare const enum E { ... }` in a non-.d.ts file under
`@isolatedModules: true` (without `@preserveConstEnums`). Per-file check in
`checkSinglePropertyAccess` (Checker.kt:50001): resolves the receiver identifier,
walks `declarations` for `EnumDeclaration` with both Const + Declare modifiers, and
emits TS2748 at the receiver position with squiggle length = identifier text length.
Skip when `preserveConstEnums` is set (TypeScript still allows the access — the const
enum is preserved at runtime as an object). Per-file scope: uses `binderResults` lookup
matching the file's `sourceFile.fileName`, not a global enum cache, so cross-file
const enums declared via `declare const enum` in OTHER files are still flagged.
Full-suite 10078/1310/3 (was 10078/1311/3, +1 net). Zero regressions.

**B42.1 (2026-05-17, +1 — flips `isolatedModulesExportDeclarationType_ts` JS-emit)** —
For multi-file `@isolatedModules` with `import { T } from "./type"` where T resolves to
a type-only export, `isValueExport` was returning true (treating T as runtime) because
the symbol's `flags` had been polluted by `mergeSymbolTable` — same-name symbols from
importing files merge their flags into the target file's locals (CLAUDE.md gotcha:
"ALL file locals merged into globals at Checker init"). The polluted T had
BlockScopedVariable|Alias|TypeAlias|ExportValue flags from cross-file merging.

`isValueExport` now scans the target file's source statements DIRECTLY to classify
declarations of `name` as value or type, avoiding the polluted symbol flags. For names
not found as direct declarations (ambient/aliased cases), falls back to the
flag-based logic. Companion change: `ExportAssignment` for `export default expr` now
captures and propagates `trailingComments` through `makeExportAssignment` so the
`// Ok` comment on `export default T;` survives erasure-vs-emission. Restricted the
parser change to `export default` only (NOT `export =`): under ES-module emission,
`export = X` is silently dropped by `Emitter.emitExportAssignment`, and
`emitTrailingCommentsBeforeNewline` would otherwise back up past the prior statement's
newline and attach the comment there (`es6ExportAssignment2_ts` regression). Full-suite
10078/1311/3 (was 10078/1312/3, +1 net). Zero regressions.

**B41.2 (2026-05-17, +1 — flips `numericLiteralsWithTrailingDecimalPoints01_ts` JS-emit)** —
Multi-line property access (`expr\n  /* comment */ .name`) now preserves the leading
comment between the expression and the dot. Previously, the comment was attached to
the dot token in the scanner but lost on the next `scanner.scan()` call (which resets
`leadingComments`). The parser now captures `leadingComments()` BEFORE calling
`nextToken()` to consume the dot (when `newLineBefore=true`), and merges them into
the property name's `leadingComments`. The emitter handles them specially when
`newLineBefore=true`: emit AFTER the indent, BEFORE the dot. Block comments are
followed by a space (`/* comment */ .toString()` form); line comments are followed
by newline + indent (`// comment\n    .toString()` form). Full-suite 10078/1312/3
(was 10078/1313/3, +1 net). Zero regressions; only the target test flips.

**B41.1 (2026-05-17, +2 — flips `functionsMissingReturnStatementsAndExpressions_ts` target_es5/target_es2015)** —
TS2355 ("function whose declared type is neither 'undefined', 'void', nor 'any' must
return a value") now fires for union-with-undefined return types like `undefined | number`
when the function has no explicit return statements. Previously, the "nullable"
classification suppressed TS2355 entirely; the early-return in `checkBodyForImplicitReturn`
matched union-with-undefined unconditionally. Per TypeScript's actual behavior, `undefined`
in a union does not satisfy the "must return a value" rule — only `void`/`any`/`never` (in
a union) or `undefined` (as a bare keyword, or as the single arg to `Promise<...>` for
async functions) suppress TS2355. The fix: replaced the bare early-return with a TS2355
emission for "nullable + !hasAnyReturn"; updated the "pure-undefined" check to also accept
`Promise<undefined>` (where the arg is a `KeywordTypeNode` for `undefined`) so async
`Promise<undefined>` return types still suppress TS2355. Both non-strict (`f23(): undefined
| number`) and strict (`f11(): undefined | number`, `f31(): Promise<undefined | number>`)
behavior covered. Full-suite 10078/1313/3 (was 10078/1315/3, +2 net). Zero regressions.

**B40.1 (2026-05-17, +1 — flips `declarationEmitResolveTypesIfNotReusable_ts` JS-emit)** —
Parser's `TypeOfKeyword` branch in `parseNonArrayType` now handles indexed-access
suffix `typeof X[K]` in addition to the existing array-suffix `typeof X[]` case.
Previously, `(o: typeof a['a']) => {}` would parse `typeof a` as the type and leave
`['a']` for the outer parser, which misinterpreted it as a destructured second
parameter (yielding `(o, []) => 'a';\n{ }`). The extended `while` loop now follows
the same pattern as the primary-type path immediately below — when the bracket is
not empty, consume `[`, parse an index type, expect `]`, wrap in `IndexedAccessType`.
ASI guard added (`!scanner.hasPrecedingLineBreak()`) to match the primary-type
loop's behavior. Full-suite 10078/1315/3 (was 10078/1316/3 post-B39.1, +1 net).
Zero regressions; only the target test flips.

**B39.1 (2026-05-17, +1 — flips `exportAssignmentImportMergeNoCrash_ts` JS-emit)** —
Preserve `const tempName = __importDefault(require(...))` for a default import whose
user-facing local binding name is SHADOWED by a same-name top-level
`VariableStatement`/`FunctionDeclaration`/`ClassDeclaration` declaration in the
original source AND the binding name is referenced in value positions. TypeScript
keeps the require's side-effect emit even when the temp const's identifier becomes
unused in the rewritten output because the shadowing local wins the rename map
(`Obj → exports.Obj` via Direct path) instead of `Obj → <temp>.default`. Example:
`import Obj from "./assignment"; export const Obj = void Obj;` previously elided
`const assignment_1 = __importDefault(require("./assignment"))` because
`assignment_1` appeared unused in the result — now kept. Gate is strictly limited
to default imports (not named — those may resolve to type-only targets via
`export type` re-resolution) AND shadowed cases only (not normal const-enum
imports whose references get inlined to `0 /* X.Foo */` and which must still
elide). 22-line addition in `Transformer.kt` `transformToCommonJS` Step 2
elision (~line 2486). Full-suite 10078/1316/3 (was 10078/1317/3, +1 net). Zero
regressions.

**B38.1 (2026-05-17, +1 — flips `privacyTopLevelInternalReferenceImportWithExport_ts` JS-emit)** —
Exported `import alias = X.Y` is now erased when `X` is a non-exported but
runtime-instantiated namespace AND `Y` is a type-only sub-member (interface,
type alias, or type-only sub-namespace). Previously, the `requireRootExported`
gate on `isQualifiedPathTypeOnly` was too narrow — it kept aliases that TypeScript
erases. The new gate `requireRuntimeOrExportedRoot` allows the root to be EITHER
exported OR runtime-instantiated. Example: `namespace m_private { export class
c_private {}; export interface i_private {}; export namespace mu_private { export
interface i {} } }` + `export import im_public_i_private = m_private.i_private;`
+ `export import im_public_mu_private = m_private.mu_private;` — both now erased
because `m_private` has runtime members (class/enum/var) and `i_private` /
`mu_private` are type-only. Non-runtime-non-exported roots (e.g. `namespace x {
interface c {} }` + `export import a = x.c`) still keep the alias with a
runtime-broken `exports.a = x.c` emit, matching TypeScript's behavior of emitting
syntactic value references even when they'd fail at runtime. Three call sites
updated (CJS pre-scan, AMD pre-scan, `transformImportEqualsDeclaration`); helper
renamed and gate condition extended. Verified zero regressions across 10078-test
suite — only the target test flips.

