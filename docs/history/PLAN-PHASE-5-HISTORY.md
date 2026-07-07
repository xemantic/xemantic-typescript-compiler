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
