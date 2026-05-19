# Session starter

Copy the block below into a new Claude Code session to continue Phase 4 of
the TypeScript compiler port. The block is self-contained — it points the
agent at `CLAUDE.md` and `PLAN-PHASE-4.md` for state and at the "Known
architectural blockers" + "Candidate-picking workflow" sections for the
approach.

---

```
Continue Phase 4 of the TypeScript compiler port.

(This session may be running as one iteration of `scripts/run-loop.sh`.
Commit + push every fix individually. Do NOT leave uncommitted changes
at session end — the loop driver aborts if it sees them, so partial
state would freeze the loop until a human investigates.)

Before picking any work:
- read STATUS.md for the current test count
- read CLAUDE.md's "AI agent mission" and "Execution protocol" sections
- open PLAN-PHASE-4.md and read THESE three sections near the bottom,
  in order:
    1. "Explored-but-skipped tests" — every test already examined and
       classified by root cause. If a test you're considering is listed
       here, read its skip reason BEFORE re-investigating — the failure
       mode is already characterized. Do not repeat the analysis.
    2. "What's left in the surgical fix pool" — concrete guidance on
       whether the surgical pool still has wins or whether the session
       should instead attempt an architectural blocker.
    3. "Known architectural blockers" — multi-session investigations
       with yield/risk ratings. Read the retry plan for blocker #1 if
       you intend to tackle one.
- `PLAN-PHASE-4-HISTORY.md` holds archived session notes from completed
  items. Only read it if you need to understand why a past fix was made.

Your loop (per CLAUDE.md § Execution protocol):

1. Run the full suite once to produce fresh test-results XMLs. It is slow
   (4-6 minutes) — kick it off in the background, then do steps 2-3 while
   it runs:

       rm -rf build/test-results/jvmTest/binary && ./gradlew jvmTest 2>&1 | grep -a "tests completed"

2. While the suite runs, skim the most recent session notes under item
   16.4 in PLAN-PHASE-4.md (search for "Session 2026-04") to see what has
   been tried lately and avoid re-treading.

3. Once XMLs are fresh, run the candidate finder:

       python3 scripts/find_candidates.py --fresh

   `--fresh` hides tests already in the "Explored-but-skipped" log. Drop
   the flag to see all buckets with `[SKIP]` markers — useful when
   spot-checking whether a previous skip decision still applies. The
   script auto-parses the skipped-log from PLAN-PHASE-4.md, so once a
   test lands in that section future sessions skip it automatically.

   The three output buckets are:
     - EXTRA — we emit N extra diagnostic lines (add a guard)
     - MISSING — expected has N extra lines (add a new check)
     - SWAP — same position, different TS code (change emission site)

4. For each candidate you plan to attempt, READ BOTH the source in
   `typescript-repo/tests/cases/compiler/<name>.ts` AND the expected
   baseline in `typescript-repo/tests/baselines/reference/<name>.errors.txt`
   before making any change. The full source often explains why TypeScript
   chose a particular diagnostic and at which position.

5. If you investigate a candidate and decide to skip it without a fix,
   LOG IT under "Explored-but-skipped tests" in PLAN-PHASE-4.md with a
   one-line skip reason (root cause or which blocker). This is how we
   stop the next session from re-treading the same path.

6. If `--fresh` returns mostly empty or the remaining candidates are all
   "needs new diagnostic / feature" items, it's time to either:
     (a) implement one of the small new-diagnostic items grouped under
         "Needs a new diagnostic / feature" (each is 1-3 tests, but they
         add up), or
     (b) take on an architectural blocker — read blocker #1's "Retry
         plan" first and size the work honestly against remaining context
         budget. Blocker #1 is ~30+ tests but consumes most of a session.

7. Commit each fix as its own `feat(16.4X): ...` commit and push. Re-run
   the full suite between fixes to catch regressions immediately. A
   reasonable cadence is 2-4 commits per session when items yield +1 to
   +5 tests each.

8. After each commit:
     - append a session note under item 16.4 in PLAN-PHASE-4.md
       describing what changed, the test-count delta, and any surprising
       constraint the fix revealed;
     - add a CLAUDE.md gotcha if the fix exposed a non-obvious invariant
       that a future agent would otherwise re-discover the hard way.

9. If you get stuck — a fix regresses repeatedly, or every remaining
   low-hanging candidate turns out to be an architectural-blocker case —
   STOP cleanly after committing any in-progress work. Before ending:
     - log any newly investigated tests under "Explored-but-skipped" so
       the next session benefits from your analysis;
     - update the "What's left in the surgical fix pool" recommendation
       if the situation has shifted (e.g. new diagnostic implemented,
       blocker partially unblocked).

---

**Status (2026-05-19, 8845 passing — post 8-round /loop session, 115+ commits):** 8 rounds of 15-iteration /loop work landed +12 net tests (8833 → 8845 / 10078 = 87.8%). Recent rounds produced productive infrastructure with mixed flip outcomes:
- Rounds 1-2: B50.x alias/elaboration (+5), B51.x FP gates / new diagnostics (+3).
- Rounds 3-4: B53.x display infra net-zero.
- Round 5: B54.x accessor-pair / write-context net-zero (1 flip + 1 shift).
- Round 6: B54.9 narrowing + B55.x strict-generic-checks for varTypes path (+2 — `typeParameterAssignmentCompat1_ts` + `conditionalTypeVarianceBigArrayConstraintsPerformance_ts`).
- Round 7: B56.1+B56.2 — `new C()` defaults TypeParams to `unknown` under strict mode (+1 — `getAndSetNotIdenticalType2_ts`).
- **Round 8 (NEW)**: B57.1+B57.2 — TS2589 excessive-depth emission via deepInstantiationBailed flag; B57.1b constraint gate prevents FP (+1 — `limitDeepInstantiations_ts`).

`find_candidates.py --fresh` returns 0/0/0. Audit confirms 0 STALE skip-log entries. All 5 MIXED bucket tests have ≥2 failing sub-variants (not close to flipping).

Next-session recommendation: pick a single architectural blocker.
- **Blocker #1** (control flow narrowing): ~60-100 tests, infrastructure-heavy.
- **Blocker #2** (generic argument inference): ~20-40 tests. Foundation in `tryInferSingleTypeParamFromArgs`; extension B52.2 promoted in queue.
- **Blocker #3** (per-file scope construction): ~30+ tests. Highest risk.
- Lib-content target-versioning: ~5-10 tests.

Important dead-ends documented in CLAUDE.md: TS2300 lib-conflict (needs lib-file-related-info tracking), strict-generic-checks for Type-engine path (still open, separate from B55.x which only handled varTypes path).

**Earlier status (2026-04-26, 8409 passing):** Surgical pool is exhausted (16+
consecutive sessions confirmed; `find_candidates.py --fresh` returns
0/0/0, filtered from 8/93/22). Phase 17 / Blocker #1 (full control
flow narrowing) infrastructure landed in 17.1–17.7; 17.9–17.27 series
landed an additional +60 from architectural-leaning surgical fixes
(namespace-aware identifier resolution, optional/index-sig/privacy
elaboration depth, generic ctor inference, `typeof Class`
construct-sig elaboration, fn-vs-fn-arg overload chain, ambient-module
`export = X` named-import alias resolution, this-parameter handling,
TS2417 clodule static-side, `super(...)` arg checking with heritage
type-arg substitution, super.method arg checking, namespace-aware
new-expression arg checking, TS2339 enum-member-access chain, TS2493
assignment-tuple-bounds, fn-vs-fn arity TS2345, void-return inference
for unannotated fn-decl bodies, TS2663-vs-TS2301 narrow disambiguation
for parameter-property shadow, Function-prototype satisfaction +
Reference-vs-named-Interface arg missing-property chain). All sub-steps:

- 17.1a: Flow-graph infra in binder (no behavior change)
- 17.1b: var-decl `never` target narrowing wired (+1)
- 17.1c: `typeof` narrowing op + widened var-decl gate (net-zero infra)
- 17.1d: `instanceof` narrowing op (net-zero infra)
- 17.1e: TS2339 narrowed-to-never wiring + instanceof contradiction
  fix (net-zero infra)
- 17.1f: `in` operator narrowing (net-zero infra)
- 17.2a: TS2774 "uncalled function in conditional" Identifier-only (+1)
- 17.3a: type-predicate fn narrowing + symbol-identity instanceof +
  flow-graph in checkPropertyAccess (+1)
- 17.4a: TS2774 PropertyAccessExpression + parameter/local-fn typed
  scope + `this` tracking + path-aware body suppression (+2)
- 17.4b: TS2774 `&&`-chain walking + ExpressionStatement-level +
  arrow-body-level (net-zero infra; test2 reaches 34/35)
- 17.5a: `x.constructor === Class` narrowing wired into
  `narrowByEquality` (+1; flips `typeGuardConstructorDerivedClass_ts`)
- 17.5b: ElementAccessExpression `["constructor"]` form +
  negative-direction `!==` correctness fix (net-zero infra)
- 17.6a: union-receiver TS2339 multi-member elaboration with
  all-primitives gate (+1; flips `typeGuardConstructorClassAndNumber_ts`)
- 17.7a: discriminant-property narrowing in `narrowByEquality` —
  `name.propX === literal` filters union members by literal-match
  (net-zero infra)
- 17.7b: lift 17.6a's all-primitives gate on multi-member TS2339
  (+1; flips `nonexistentPropertyOnUnion_ts`)
- 17.7c: narrowed-to-single-Object TS2339 emission for anonymous
  `Type.Object` receivers (net-zero infra)
- 17.7d: 17.7c gate also accepts `Type.Interface` with no base types
  (net-zero infra)
- 17.7e: bare-Identifier truthiness narrowing in
  `applyConditionNarrowing` + `narrowByTruthiness` helper
  (net-zero infra — completes the missing `is Identifier` branch so
  `if (x) { ... }` narrows `T | undefined` to `T`; falsy side
  conservatively unchanged)
- 17.8a: `typeof Class` source display + construct-sig elaboration
  for class-Identifier-as-value var-decls (+2 — flips
  `assignmentCompatability44/45_ts`; not narrowing — surgical
  carve-out for the case `canUseTypeEngine` deliberately skips)
- 17.8b: populate real ctor params in `getTypeOfSymbolForTypeQuery`'s
  Class branch (+1 — flips `classSideInheritance3_ts`; closes the
  FP 17.8a's full-suite re-run revealed where target `typeof A`
  ctor sig had 0 params and source's 1-param ctor wrongly tripped
  the arity gap)
- 17.8c: extend the 17.8a typeof-Class + construct-sig branch to
  assignment expressions + prefer overload sigs over impl in source
  ctor builder (+1 — flips `assignmentCompatWithOverloads_ts` line
  30 `d = C`)
- 17.9a: namespace-aware identifier resolution — `inferenceNamespaceStack`
  threads enclosing-namespace context through lazy variable type
  resolution (+10; flips 10 `assignmentCompatabilityNN_ts` family tests)
- 17.9b: deep widening of object-literal member types and Array
  element types in `widenType` (+2)
- 17.10a–e: type-parameter wiring on FunctionExpression / FunctionType
  / TypeLiteral call sigs + substituted-pinning elaboration (+7)
- 17.11a: `S→unknown` substitution + "T could be instantiated" chain
  in `getFunctionMismatchElaboration` (+2)
- 17.11b: TS2554 for property-access call expressions (+2)
- 17.11c: MethodDeclaration scope-push for typeParameters + null/undef
  vs Type.Reference TS2345 (+1)
- 17.11e: push namespace symbol onto inferenceNamespaceStack for
  ModuleDeclaration body in type-assignability walk (+1)
- 17.12a: widen optional source props to `T | undefined` in
  `propertiesRelatedTo` + deeper undef-vs-target chain line (+11)
- 17.12b: number-index-signature missing diagnostic + Type.Reference
  nominal-source detection + index-sig elaboration (+1)
- 17.13: `formatTypeForDisplay` honors optional/rest param tokens
  (net-zero infra)
- 17.14a: `getNonConstructibleElaboration` for non-constructible
  source vs constructible target in checkPropertyAccessAssignment (+2)
- 17.14b: generic argument inference from `new` expressions +
  class-instance comparison via canUseTypeEngine widening +
  parameter-property type substitution (+3)
- 17.14c: asymmetric privacy elaboration in `getPropertyElaborationChain` (+3)
- 17.15a: drop `| undefined` for fn-type optional params + recurse
  fn-mismatch chain for nested fn-type param mismatches (+1)
- 17.15b: TS2769 overload-error fn-vs-fn arg chain + callee-position
  squiggle (+1)
- 17.16: ambient-module `export = X` chain in `resolveAlias`'s
  named-import branch — looks up `originalName` via `X.exports`
  through a new `resolveAmbientModuleExportEquals` helper that walks
  the `ModuleDeclaration.body` for `ExportAssignment{isExportEquals=true}`
  (+1 — flips `aliasDoesNotDuplicateSignatures_ts`)
- 17.17: `this`-parameter handling in FunctionExpression — param-type
  indexing fix in `getTypeOfFunctionExpression` + `this:` display in
  `signatureToString` + literal-return inference in
  `inferReturnTypeFromFunctionExpressionBody` (+1 — flips
  `contextualTyping24_ts`)
- 17.18: TS2417 namespace-vs-namespace for clodule (class+namespace)
  merges — `checkClassStaticSideExtends` recurses into ModuleDeclaration
  pair members' exports to find first missing base export, emits TS2417
  with `Property X is missing` chain + TS2728 related info (+1 — flips
  `clodulesDerivedClasses_ts`)
- 17.19: `super(...)` arg checking — new `currentSuperBaseSig` state
  + `buildBaseConstructorSignatureForSuper` helper that pushes base
  class typeParameters onto `currentTypeParamScope` while resolving
  ctor param types, then instantiates via heritage clause type args.
  Wired into `checkSingleCallExpressionTypes` super-callee branch
  before the standard anyType bail (+1 — flips
  `superCallArgsMustMatch_ts`)
- 17.20–17.26: super.method arg checking (+1), namespace-aware
  new-expression arg checking with class TypeParam scope (+1), TS2339
  enum-member chain (+1), TS2493 assignment-tuple-bounds (+1), fn-vs-fn
  arity TS2345 (+1), void-return inference for unannotated fn-decl
  bodies (+1), TS2663-vs-TS2301 narrow disambiguation for
  parameter-property shadow (+1)
- 17.27: Function-prototype-method satisfaction in `propertiesRelatedTo`
  (skip target props in `{call,apply,bind}` when source has callSignatures)
  + new `Type.Reference` vs named `Type.Interface` arg branch in
  `checkArgumentsAgainstSignature` that emits TS2345 + missing-prop chain
  + TS2728 related info via `collectMissingProperties`. (+2 — flips
  `assignmentCompatability_checking-call/apply-member-off-of-function-interface_ts`)

**Do NOT re-attempt** Blocker #4 step (b) (TypeParam-vs-TypeParam) —
read 16.4df session note in PLAN-PHASE-4.md first if tempted. The
remaining sub-cases of Blocker #4 are demoted to LOW yield; pursue
them only opportunistically. Default workflow (steps 1-9 above)
applies. The `assignmentCompatabilityNN_ts` numbered family (11–43
series) is **fully healed** as of 17.15b — 0 of 38 numbered tests
fail. Remaining candidates classified by post-17.19 recon:

- ~~**`assignmentCompatability_checking-call|apply-member-off-of-function-interface_ts`**:
  needs Function-apparent-type infrastructure (source `() => any`
  should structurally satisfy an interface requiring `.call(...)` /
  `.apply(...)` because Function.prototype provides them).~~ **Flipped
  17.27 (2026-04-26)** — `propertiesRelatedTo` now skips target props
  in `{call,apply,bind}` when source has callSignatures, and a new
  `checkArgumentsAgainstSignature` branch emits TS2345 + missing-prop
  chain for `Type.Reference` (no callSigs) vs named `Type.Interface`
  args. Both are
  >1-file changes with cross-cutting risk (every fn-vs-interface
  comparison) — out of scope per autonomous-decision policy.
- **`noStrictGenericChecks_ts`**: needs full type-param-vs-type-param
  matching across nested fn signatures (`<S>(x:S, y:S)` vs
  `<T,U>(x:T, y:U)` where S→T/U bipartition fails on second param).
  Architectural — Blocker #2 (generic argument inference).
- **TS2774 + `&&`-chain narrowing** (`uncalledFunctionChecksInConditional2_ts`):
  needs `perf && perf.measure && ...` to narrow `perf` from
  `Performance | undefined` to defined by the time the last operand
  fires. ALSO blocked on `window` resolving to `any` (lib
  `Window & typeof globalThis` intersection unresolved); same gap
  as the missing 35th emission in `truthinessCallExpressionCoercion2_ts`.
- **TS2454 via flow-graph definite-assignment**: replace the ad-hoc
  walker (does NOT recurse into IfStatement bodies, blocking
  `nestedLoopTypeGuards_ts` etc.). Note 17.1c session warned a
  snapshot/restore approach regressed -7 tests; tread carefully.
- **FlowAssignment-RHS narrowing**: medium risk — could over-narrow
  legitimate union-source TS2322 cases.

These leverage the installed flow-graph (`Flow.kt` / `FlowGraphBuilder`,
`currentFlowGraph`, `nodeToFlow`, `applyConditionNarrowing`,
`narrowByEquality` / `narrowByInstanceOf` / `narrowByInOperator` /
`tryNarrowByTypeOf`) and the structural-comparison infra
(`relationSourceTargets` / `relationTargetTargets` stacks,
`isDeeplyNested` 5+ heuristic, TypeParam apparent-type comparison,
`resolvedPropertyTypes` cache).
```

---

## Tips for using this file

- The block above is intentionally self-contained so it keeps working as
  the codebase drifts. It points at `CLAUDE.md` and `PLAN-PHASE-4.md`
  rather than re-stating their contents, so it won't go stale.

- If you want to start the session focused on a specific feature (e.g.
  "work on 16.3 control flow narrowing" instead of "find surgical wins"),
  just append a line or two at the end of the block telling the agent
  what to prioritize. The framework (read the docs, find candidates,
  skip blockers, commit per fix) stays the same.

- When the architectural blockers are eventually unblocked (e.g. someone
  fixes the cross-file global scope issue), edit both this file and
  `PLAN-PHASE-4.md`'s "Known architectural blockers" section to remove
  the stale entries.
