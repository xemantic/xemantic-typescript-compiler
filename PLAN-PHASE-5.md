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

**Round 478 (2026-07-11, same session as 477 — the HARNESS burn-down begins) — FIVE fixes,
harness 217 → 145 (−72; TS2339 66 → 13, TS7006 15 → 4, TS2341 6 → 0; every step
zero-additions by per-position diff). Suite 10,090 → 10,098 (+8 local across 2 new test
files, 0 regressions).**
- **Fix 1+2 (tsc getAssignmentReducedType — the fourslash reassignment idioms, ~37 FPs):**
  `narrowByAssignmentRhs` gains THREE assignment-reduction arms, all placed BEFORE the
  round-416 non-nullish reset (a both-arms-non-nullish ternary would otherwise reset to the
  FULL declared union first): (a) `x = typeof x === "tag" ? { … } : x` (both condition
  orders) drops the tag's members via narrowByTypeOfGuard when the pass-through arm is the
  bare reference and the replacement arm an object literal; (b) a plain OBJECT-LITERAL RHS
  drops the declared union's primitive/nullish members (`if (typeof source === "string")
  source = { files: … };` — evaluatorImpl); (c) an ARRAY-LITERAL RHS keeps only array-like
  members (Array/ReadonlyArray refs, tuples, intersections containing one — `if
  (!ts.isArray(expected)) expected = [expected];` incl. the `readonly T[] & {plus}` brand).
- **Fix 3 (lexical private access, TS2341 ×6):** `checkStaticPrivateMemberAccess` accepts a
  same-file access POSITIONALLY inside the declaring class declaration — a function nested
  in a class method reads the class's static privates legally (fourslash
  `TestState.nLinesContext` inside `textWithContext`); the enclosing-class threading resets
  at nested-function boundaries (this-rebinding), which is right for `this` but wrong for
  lexical accessibility.
- **Fix 4 (`import * as ns` guards, TS2339 ×16):** `resolveNamespaceMemberFnDecl` gains a
  NamespaceImport branch — resolveAlias never resolves namespace-import aliases (round 444)
  and the ImportSpecifier-keyed flow resolvers skip them, so
  `ts.isDocumentRegistryEntry(entry)` through the harness `.js` barrel silently never
  narrowed. Resolve the import's own specifier → target file → locals + `export *` chain;
  memoized (`nsImportMemberFnCache`, declared before `init`). REPRO LESSON: the free-fn
  receiver variant "passed" because `entry` was silently UNTYPED (resolution failure reads
  as success) — the interface-METHOD receiver variant typed it and exposed the guard; when
  a repro "passes", confirm the types actually RESOLVED before believing it.
- **Fix 5 (namespace-callee locals, TS7006 ×11):** the same resolver feeds
  `initializerCtxTypeForImplicitAny`'s namespace-callee arm — `const compilerHost =
  ts.createCompilerHostWorker(…)` types the local from the callee's return annotation, so
  `compilerHost.getSourceFile = (fileName, …) => …` arrow params inherit the CompilerHost
  member context.
- **NEXT (harness @ 145, ~55 real):** harnessIO `CompilerSettings` index-sig ×3 (namespace-
  nested interface with a string index sig — the TS2339 should be suppressed) + TS2833
  `compiler.CompilationResult` ns-import-in-TYPE-position ×4 (the type-position sibling of
  fix 4); client.ts protocol `Location` ×5 (conflation family); compilerImpl TS2564 ×3;
  fourslash 829/839 (`.definitions` on a union), 1946 (`string | Range`), 4045 (`Refactor
  .actions`); incrementalUtils TS18048 ×2; editorServices 1461 TS2774 (`this.host.realpath`
  optional-method truthiness) + 3212.**

**Round 477 (2026-07-11 — SERVER REACHES ZERO REAL FPs, SEVEN of eight profiles) — FIVE
fixes, server 51 → 46 (real FPs 5 → 0; the remaining 46 = TS2591×43 + TS2304×2 `global` +
TS2584 console, all env-legit offline artifacts). All five residuals were CONFLATION-family
(Blocker #3) on server/protocol.ts vs compiler/jsTyping declarations.**
- **Fix A (utilities:7827 TS2366):** a same-named `const enum NewLineKind` in protocol.ts
  (Crlf/Lf) AND compiler types.ts (CarriageReturnLineFeed/LineFeed) merges into a 4-member
  chimera, so the switch covering the complete compiler pair read non-exhaustive. THREE
  coupled pieces: `conflatedEnumFileSubsets` (per-file member-name sets keyed by the merged
  symbol id) relaxes the exhaustiveness comparison (`coveredExhaustsConflatedEnumSubset` —
  covering ONE file's complete member set is exhaustive; real tsc never merges module-scoped
  enums); `unionDiscriminantKeysFromAnnotation` — the AST-side fallback when the union
  members are ALSO alias-shadowed (protocol's `type CompilerOptions = ChangePropertyTypes<…>`
  shadows the interface via last-wins → the resolved member is any/error and the
  resolved-type walk bails), resolving each member via `interfaceDeclsForCurrentFileView`
  (own decl, else the import followed through `.js` barrels); and `checkImplicitReturns` now
  sets `currentCheckFileName` per file — it was STALE from whatever pass ran before, so
  conflation-aware resolution in that pass silently used the wrong file.
- **Fix B (editorServices:1253 TS2353→TS2345):** four pieces: `annotationAgrees` (the
  topLevelConstStringValues builder) accepts a namespace-import-QUALIFIED alias annotation
  (`const CloseFileWatcherEvent: protocol.CloseFileWatcherEventName = "closeFileWatcher"` was
  POISONED out of the index); `enumMemberKeysOfTypeNode`'s QualifiedName arm falls back to
  `resolveNamespaceQualifiedTypeAlias` so `eventName: protocol.XEventName` member annotations
  yield `lit:s:` keys; `checkExcessProperties`' UNION nested descent drills the
  DISCRIMINANT-matched constituent (tsc getMatchingUnionConstituentForObjectLiteral — was
  first-with-the-prop, so `data: { id }` checked against LargeFileReferencedEvent's data);
  the then-morphed missing-props TS2345 (the chimera demands protocol's `event`/`body`)
  needed the round-468 `objectLiteralMatchesSomeConflatedDeclaration` rule in the union-arg
  structural gate.
- **Fix C (session:475 TS2322):** a QUALIFIED `ns.Name` naming an interface SHADOWED by a
  same-named `type Name` alias in a DIFFERENT module file (session's own `type Event` vs
  protocol's `interface Event`) resolves through the namespace import to the target module's
  per-file view (`conflatedPerFileInterfaceType` gains a filesOverride param fed by
  `interfaceDeclFilesAll`; QUALIFIED-only so bare refs keep the round-443/444 ecology).
  PAIRED with the `isConflatedInterfaceRefNode` QualifiedName-arm extension (nodeTypes bypass
  — a null-context first touch would cache the alias resolution) and a precise-verdict early
  return in checkReturnAssignability (`qualifiedAliasShadowedTarget` + engine-confirmed
  fresh-literal pass — the STRING fallback re-resolves "Event" by bare name to the shadowing
  alias and re-FP'd; the round-436c trap).
- **Fix D (session:3994 TS2322):** `objectLiteralSpreadsConflatedInterface` — an objlit
  SPREADING a conflated-interface-typed value is unknowable (the spread source's fn-return
  shell cached the chimera eagerly, B198; `{ ...textSpan, contextStart, contextEnd }` mixed
  compiler's `{start: number, length}` into protocol's `{start: Location, end}`); wired at
  the ternary-arm + direct-return paths. Suppression-only.
- **Fix E (typingInstallerAdapter:233 TS2345):** the round-476 "B516 never-param / callee
  union" theory was WRONG — the Diagnostic-init probe showed the emission is the DEFAULT
  clause's `assertType<never>(response)` (line 233 IS the default clause): the
  default-exhaustiveness never fired because EVERY member's `kind: ActionSet`-style
  annotation read null — the CHECKING file only IMPORTS the merged const+type-alias names,
  so `currentFileLocals["ActionSet"]` is an IMPORT alias whose declarations are
  ImportSpecifiers and the bare-alias arm of enumMemberKeysOfTypeNode bailed.
  Fall to the merged GLOBALS symbol's TypeAliasDeclaration (mergeSymbolTable's addAll keeps
  the declaring file's alias in the polluted list).
- **Process:** the round-472 probe technique earned its keep twice (the XP233B stack
  falsified round 476's theory in one run; XP233C found the null keys in one more); one
  self-inflicted incident — a `--rerun-tasks` warnings check launched DURING the chain's
  MainKt run clobbered its classes (the documented NoClassDefFoundError gotcha), costing one
  re-run.
- **NEXT: harness (@225 last measured) — the LAST profile for v1.**

**Round 476 (2026-07-11, same session as 475) — TWO more server fixes in 2 commits
(b4bbf29c / 2e568f9e). Dashboard: server 54 → 51 (real FPs 8 → 5). Suite 10,075 → 10,078
(+3 local, 0 regressions).**
- **Fix 1 (b4bbf29c, jsTyping SafeList ×2):** the round-474 probe verdict resolved in one
  println probe — `globals["ReadonlyMap"]` is NULL (a KNOWN_GLOBALS name with NO modeled
  interface), so jsTyping's `type SafeList = ReadonlyMap<string, string>` body resolves
  errorType. `returnSourceSatisfiesFileLocalAliasBody` treats an UNRESOLVABLE file-local
  alias body as UNKNOWABLE and suppresses (the resolved target is the known-wrong merged
  chimera; FN-not-FP — only reached in the conflation context, and a resolvable failing
  body still fires per the negative pin). The PROPER fix (model ReadonlyMap in the
  embedded lib) is a lib change with the "and N more" count-shift trap — deferred.
- **Fix 2 (2e568f9e, typingInstallerAdapter:224):** `overloadNarrowedArgType`'s union path
  retries with `getNarrowedTypeForReferenceFollowLoopEntry` when the plain walk washed
  back to the declared union at a FlowLoopLabel (STRUCTURAL wash gate — branch labels
  mint fresh identical unions, the round-424 lesson). The ActionSet case reads `response`
  AFTER its requestQueue while-loop, so the switch-case narrowing was lost and both
  updateTypingsForProject overloads FP-rejected. Un-narrowed union args vs a narrower
  single overload turn out to be a PRE-EXISTING conservative FN (couldn't pin a small
  negative control — the dashboard diff is the both-directions evidence).
- **NEXT (server @ 51, 5 real):** compiler/utilities:7827 TS2366 (minimal union-param
  switch repro is CLEAN — the real site's barrel-imported CompilerOptions/PrinterOptions
  or cross-file NewLineKind differ; probe); editorServices:1253 TS2353 + session:475
  TS2322 (both repro clean minimally — the real sites involve protocol.ts's same-named
  conflated event/Event interfaces; probe the emission with the Diagnostic-init trick);
  session:3994 (TextSpan chimera-spread, known deep); typingInstallerAdapter:233 (the
  callee resolves to a UNION of Project's and ProjectService's watchTypingLocations →
  B516 combined sig intersects params to `never` — probe how `this.projectService`
  resolves). Then harness (@225 — TS2339×66/TS2322×16/TS7006×15).**

**Round 475 (2026-07-11, the SERVER burn-down continues) — FIFTEEN fixes in 3 commits
(f1e2589a / 258aae3d / ccc33547). Dashboard: server 77 → 54 (−23; real FPs 31 → 8 excl.
TS2591×43 + TS2304×2 `global` + TS2584), harness 255 → 225 (−30 riding, TSV rows recorded),
services re-verified UNCHANGED at its 46 env-legit floor. Suite 10,045 → 10,075 (+30 local
across 5 new test files, 0 regressions).**
- **Fix batch 1 (f1e2589a, the completions `Request` family ×8, Blocker #3):** the round-474
  "needs a probe first" verdict DISSOLVED into a minimal 2-file repro (protocol `interface
  Request` + completions-local `type Request = <union of inline type literals>`) — no probe
  needed. Three coupled extensions: `returnSourceSatisfiesFileLocalAliasBody` iterates EVERY
  union member of the return annotation (was sole-non-nullish); TypeLiteral alias-body
  constituents check via the new `objectLiteralExactlySatisfiesTypeLiteralNode`; and
  checkMemberAccessMissing's union branch suppresses when a MULTI-member receiver union
  contains an own-file conflated alias member (the chimera makes discriminant narrowing
  unmodelable) and the property exists on some member/alias constituent.
- **Fix batch 2 (258aae3d, nine families):** arg-path spread-of-any (session:1469);
  `registerBindingPatternParamLocals` — binding-pattern params register element names in the
  assignability pass with annotation member types (optional → `| undefined`), closing the
  destructured-SHORTHAND cross-file fn leak (editorServices:2852 `enable`, session:4063
  `isWriteAccess` — the round-473 residual); `getReturnTypeOfNewExpression`'s
  constructor-interface branch gated to NON-class callees (class instances DO carry
  constructSignatures inherited-first, so `new ConfiguredProject(...)` typed as `Project` —
  project:2764, editorServices:2897/3428); `A && B` = falsy(A) | B via isDefinitelyFalsyMember
  (root-caused from the 2 builder.ts FPs the binding-pattern fix unmasked — `let oldState =
  oldProgram && oldProgram.state` had dropped `| undefined`); TS2391 optional bodyless methods;
  TS2416 mutable literal-override widening; TS2564 ctor switch clauses; property-init
  foreign-TP bail (maybeBind, project:564); rhsIsDefinitelyNonNullish returns true for a
  NonNullExpression RHS outright (project:1694 — the unwrap-and-descend classified by the
  INNER call's nullable annotation).
- **Fix batch 3 (ccc33547, four families):** `<literal-union> || "literal"` keeps the right
  literal when the kept left is all string-literals (editorServices:2848);
  resolveMemberPropertyType UNION-root arm — `(A|B).p = A.p | B.p` (union-annotated param
  member switch; repro clean, the REAL utilities:7827 stays — barrel-imported interfaces need
  a probe); REST-param targets provide unbounded args (server/utilities:30);
  conflatedInterfaceFiles extended to cross-file CLASS X + `interface X` merges (canMerge
  Class+Interface makes it a chimera — scriptVersionCache's `class TextChange`) + the
  round-468 ARG-side objectLiteralMatchesSomeConflatedDeclaration rule wired into the RETURN
  path (services/utilities:2353).
- **Also landed (repro-verified, real site deferred):** const-string discriminant keys in the
  objlit-vs-union member selection (enumMemberKeysOfTypeNode TypeQuery arm +
  bare-Identifier const value arm) — the minimal eventName repro passes; the real
  editorServices:1253 additionally involves protocol.ts's same-named conflated event
  interfaces.
- **Process notes:** (a) the round-474 probe-first verdicts keep dissolving into minimal
  repros — ALWAYS try the 2-file repro before instrumenting; (b) one interim regression
  (2 builder.ts FPs from the binding-pattern registration) was caught by the per-step listAll
  diff and root-caused to the missing `&&` falsy rule IN the same batch — the diff-per-step
  discipline pays; (c) a `java` CLI run during a background gradle compile dies SILENTLY
  (classes clobbered mid-load) — sequence them.
- **NEXT (server @ 54, 8 real):** compiler/utilities:7827 TS2366 (probe — the minimal
  union-param switch repro is clean; barrel-imported CompilerOptions/PrinterOptions or the
  cross-file enum differ); jsTyping:81/88 SafeList ×2 (probe — why the alias body
  `ReadonlyMap<string, string>` resolves errorType at the call site; suspect the structural
  nodeTypes collision); editorServices:1253 (conflated event interfaces + const-string
  discriminant interplay); session:475 (repro clean — real site involves the protocol
  namespace-qualified conflated `Event`... probe) + session:3994 (chimera-spread, known);
  typingInstallerAdapter:224 (case-body read AFTER a while loop — suspect the FlowLoopLabel
  wash) + :233 (callee resolved to a UNION of the two watchTypingLocations methods → B516
  intersected param `never` — probe the callee resolution). Then harness (@225 —
  TS2339×66/TS2322×16/TS7006×15 on harness-only files).**

**Round 474 (2026-07-11, the SERVER burn-down continues) — EIGHT fixes in 4 commits
(8c65858a / dc105f56 / 5134ea7c / + the literal-write commit). Dashboard: server 104 → 77 (−27;
real FPs 58 → 31 excl. TS2591×43 + TS2304×2 `global` + TS2584), harness 299 → 255 (−44 riding
the same fixes, TSV rows recorded), every step strictly-removals by listAll diff at the ~46 s
normal band. Suite 10,024 → 10,045 (+21 local across 8 new test files, 0 regressions).**
- **Fix 1 (extractSymbol.ts ×7 + goToDefinition, Blocker #3):** a type-alias BODY referencing a
  CONFLATED interface name resolves in its DECLARING file's view
  (`resolveTypeAliasBodyWithOwnerContext`, identity-matched via localTypeAliasIndex) + the
  `isConflatedInterfaceRefNode` TypeOperator arm (`readonly Diagnostic[]` cached a
  chimera-element resolution in nodeTypes — plain `Diagnostic[]` worked, the readonly wrapper
  didn't: the missing-arm tell). **MEASURED DEAD-END folded into the gate: UNRESTRICTED owner
  threading regressed +41 server FPs and 3.4× wall (104 → 145, 48 → 164 s) — an owner file that
  itself DECLARES one of the referenced conflated interfaces (importTracker's own
  `interface AmbientModuleDeclaration` inside its leaked `type SourceFileLike` union) must keep
  the merged-chimera status quo; the round-443 display-keyed suppression ecology depends on it.**
- **Fix 2 (executeCommandLine.ts ×4, Blocker #3):** an imported CALLEE colliding with an
  unrelated same-named exported function (`formatMessage` compiler vs server/session) resolves
  through its OWN identity-matched import + `export *` chain (`importedCalleeFunctionType`, the
  fn sibling of round 473's `importedTopLevelVarAnnotationType`); gated to a genuine collision
  (globals valueDeclaration ∉ the import target's own decls) so non-collision paths stay
  byte-identical. Negative pin: a wrong arg against the CORRECT signature still fires.
- **Fix 3 (rules.ts ×5):** `keyof X` where a `type X` SHADOWED the `interface X` via the
  last-wins Interface+TypeAlias merge (protocol.ts's `ChangePropertyTypes<…>` aliases → anyType
  → `keyof any` = `string | number | symbol`) recovers the literal key union AST-side
  (`keyofShadowedInterfaceKeyUnion`, own + extends-inherited names; bails on index signatures /
  unresolvable bases). The invalid-key positive control proves the union is real.
- **Fix 4 (editorServices.ts ×4 TS7006):** a body local initialized from a `this.<method>(…)`
  call types from the ENCLOSING class's own method return annotation (the implicit-any walker's
  this-call arm — `getTypeOfExpression(this)` is anyType per B101), PAIRED with the
  ctx-unknowable rule: a target member ANNOTATION naming a conflated alias-shadowed interface
  (`sourceFileLike?: SourceFileLike`) marks the RHS contextually typed instead of propagating
  the wrong resolution. The union-receiver gate needed explicit member resolution
  (getPropertyOfType has NO Union branch — the round-419 gotcha, found by probe).
- **Fix 5 (completions.ts:1251):** a return annotation naming a conflated `type X` THIS file
  declares checks against the TRUE file-local alias BODY
  (`returnSourceSatisfiesFileLocalAliasBody`). jsTyping:81/88 (the SafeList target) STAY: the
  alias body `ReadonlyMap<string, string>` resolves errorType at this call site while ReadonlyMap
  resolves fine program-wide — a resolution residual needing a probe.
- **Fix 6 (session.ts 1827/1907/2424):** a ternary ARM spreading an any/error-typed value is
  `any` in tsc — the round-445 spread-poison rule extended to checkConditionalReturnBranches.
  session:3994 stays (its spread resolves to the conflated-TextSpan chimera, not any).
- **Fix 7 (server/utilities.ts:83):** a POSITIVE equality against a literal narrows a BARE
  supertype primitive to the literal (tsc narrowTypeByEquality) — narrowUnionByLiteral's
  non-union branch returned the primitive unchanged, so `base === "tsconfig.json" || base ===
  "jsconfig.json" ? base : undefined` FP'd `string` vs the literal-union return.
- **Fix 8 (project.ts:2286):** a LITERAL property write whose literal the target's declared
  union annotation SYNTACTICALLY contains is always legal (`this.autoImportProviderHost =
  false` vs `AutoImportProviderProject | false | undefined`) — BOTH this-prop write paths
  (the varTypes string path and checkPropertyAccessAssignment) widened the literal first;
  both now consult the round-436c syntactic membership proof.
- **MEASURED & REVERTED (the completions `Request` theory):** resolving a conflated name to the
  ctx file's OWN top-level `type` alias inside conflatedPerFileInterfaceType cleared NOTHING
  (the Request FPs come through a different path) and added 3 returnValueCorrect.ts
  `'Info | undefined' ⊄ 'Info | undefined'` identity-mismatch FPs — the round-444/445 Info
  first-touch ecology. The completions Request/CompletionData ×8 family needs a probe first.
- **Session note:** the session was restored mid-flight (`--continue`) after the harness process
  died; the suspected in-flight OOM was actually the perf regression of the then-unbisected
  fix-1 (164 s run) — bisecting the two coupled edits found the TypeOperator arm clean and the
  threading responsible for both the +41 and the slowdown.
- **NEXT (server @ 77, 31 real):** completions Request/CompletionData ×8 (probe the emission
  path first — the reverted theory shows it is NOT the bare-name TypeReference resolution);
  session.ts residual (475 `protocol.Event` qualified conflated-alias, 3994 chimera-spread,
  4063 shorthand leak, 1469); project.ts ×8 (399 TS2564, 470/471 TS2391, 564, 1694 TS18048,
  2764 new-expr base, 2914 TS2416); editorServices residual ×5;
  jsTyping SafeList ReadonlyMap-errorType probe; typingInstallerAdapter ×2; compiler/utilities
  :7827 TS2366; services/utilities:2353. Then harness (last 299).**

**Round 473 (2026-07-11, the SERVER burn-down — the three big conflation families) — THREE fixes
in 2 commits (ad660db5 / ef8107f5). Dashboard: server 227 → 104 (−123; real FPs 181 → 58 excl.
TS2591×43 + TS2304×2 `global` + TS2584), harness 429 → 299 (−130 riding the same fixes);
services and compiler UNCHANGED at their env-legit floors (46 each — the zero-real profiles did
not regress). Suite 10,013 → 10,024 (+11 local across 3 new test files, 0 regressions); server
self-compile 43.6 → 48.6 s (+11% — the conflated-name nodeTypes bypass; acceptable, noted for M5).**
- **Fix 1 (ad660db5a, const-string discriminants — session/typingInstallerAdapter/editorServices
  ~35 FPs):** tsc's jsTyping/shared.ts idiom discriminates unions on CONST-typed strings
  (`switch (response.kind) { case EventTypesRegistry: … }` + `eventName: typeof
  ProjectsUpdatedInBackgroundEvent` members). FOUR coupled pieces: the Binder MERGES
  Variable+TypeAlias (the `type ActionSet = "action::set"` + `const ActionSet: ActionSet`
  same-name pair — the const previously OVERWROTE the alias symbol, so every `kind: ActionSet`
  annotation resolved errorType and the narrowing filters kept every member); the NEW
  program-wide `topLevelConstStringValues` index (unambiguous top-level const strings;
  value-space competitors POISON, type-space aliases/interfaces don't compete) feeds
  `constStringCaseLiteralType` in narrowBySwitchClause + the default-exhaustiveness block +
  narrowByDiscriminantProperty; `typeQueryConstStringLiteral` recovers `typeof <const>` member
  annotations that widened to string/any in both discriminant filters; and
  checkMemberAccessMissing gained the SIBLING-discriminant suppression (`switch
  (event.eventName)` narrows the BASE `event` and projects `.data` — the walked path
  "event.data" is invisible to the FlowSwitchClause).
- **Fix 2 (ad660db5b, per-import barrel VAR resolution — the `emptyArray` family, 29 FPs,
  Blocker #3):** compiler files importing core.ts's `emptyArray: never[]` through
  `./_namespaces/ts.js` resolved server/utilitiesPublic.ts's `emptyArray:
  SortedReadonlyArray<never>` — the merged globals symbol's winner is FILE-ORDER-DEPENDENT.
  `importedTopLevelVarAnnotationType` (getTypeOfSymbolWorker's Alias branch ONLY — the
  round-409 resolveAlias-flood rationale stands) resolves the alias through its OWN
  ImportDeclaration (IDENTITY-matched in the structural index — same-shaped specifiers live in
  files whose barrels resolve DIFFERENTLY) + the new `computeExportedVarDeclThroughStars`
  (FILE-AST star following — the merged symbol's declarations list is polluted, so symbol-side
  resolution can't pick the right file's decl).
- **Fix 3 (ef8107f5, per-file views of CONFLATED interfaces — the protocol.ts family, ~64 FPs,
  Blocker #3):** `interface Diagnostic`/`TextSpan`/`HighlightSpan`/`Request` declared in BOTH
  server/protocol.ts and compiler-or-services types.ts merge into a chimera. References now
  resolve the per-file view their context selects (see the commit message + the CLAUDE.md
  gotcha for the FIVE coupled pieces: conflatedPerFileInterfaceType with the
  defer-to-general-resolver rule pinned by errorWithSameNameType; the transient-symbol
  perFileInterfaceType; heritage context threading; the isConflatedInterfaceRefNode nodeTypes
  bypass incl. COMPOSITE nodes; the conflatedCtxMissing no-cache flag + conflatedOwnerFile
  member-annotation context; the conflatedMergedPairRelated relation/arg-emitter bails).
  The round-468 `&&`-return arm now EMITS the falsy-remainder error directly (tsc types
  `count && obj` as `0 | {…}`) — the chimera-era coarse path had reported it by accident
  (the negative control was pinning an accidental mechanism).
- **Lessons:** (a) the `nodeTypes` cache is keyed by the STRUCTURAL node — same-shaped
  annotation nodes in DIFFERENT files collide, which the per-file resolution exposed (bypass
  for conflated names, including composites: TypeLiteral members resolve EAGERLY in
  getTypeFromTypeLiteral); (b) a Diagnostic-init probe on a 4-file repro beats armchair
  resolution-tracing — three rounds of wrong valueDeclaration theories fell to one
  `XPROBE-ID` print showing `globals=SortedReadonlyArray<never>`; (c) fn types cache their
  shell EAGERLY (B198), so null-context param annotations stay chimeras — that's what the
  relation-level conflatedMergedPairRelated bail is for.
- **Residual (documented):** session.ts:4063 resurfaced with a different display — a
  destructured-param SHORTHAND (`isWriteAccess`) leaking to a same-named cross-file function
  in the return-objlit path (previously masked by spread-of-any); the round-429
  currentParamBindingNames shadowing does not reach this pass's shorthand-value typing.
- **NEXT (server @ 104, 58 real):** session.ts:4063 (the shorthand leak above); the remaining
  session.ts objlit targets (Event/EmitOutput/QuickInfo/RefactorEditInfo — union-of-protocol
  targets); completions.ts Request/CompletionData ×9; editorServices ×9; project.ts ×8;
  rules.ts keyof ×5; executeCommandLine Logger ×4. Then harness (@ 299 — its own files +
  TS2339×69/TS2345×37 tail).**

**Round 472 (2026-07-11, the services burn-down — SERVICES REACHES ZERO REAL FPs) — SEVEN fixes
in 6 commits (15c1ff56 / ada176fd / 255a92f6 / 36f98fbf / c09dc08b / + the truthy-guard commit).
Dashboard: services 56 → 46 (−10; TS2322 3 → 0, TS2345 2 → 0, TS2740/TS2538/TS2339/TS7034/TS7005
→ 0 — the remaining 46 = TS2591×43 + TS2304×2 `global` + TS2584 console, ALL env-legit offline
artifacts). The SECOND profile at zero real FPs, matching compiler. Every fix verified
strictly-removals by per-position listAll diff; suite 10,009 / 0 failing (+21 local across 8 new
test files). Compiler profile unchanged (46, zero real).**
- **Fix 1 (15c1ff56, nested-objlit contextual distribution):** getTypeOfObjectLiteral's ctxObj now
  resolves a UNION contextual type (sole non-nullish object member → NEW
  selectUnionMemberByObjLitDiscriminant via the round-411 canonical `symId#member` key space →
  key-coverage); a nested ObjectLiteralExpression VALUE inherits the property's contextual type;
  array-literal ELEMENTS inherit the contextual array's element type (+ the return path provides
  the array ref for a returned array literal). The guard-narrowed values (`node: parent` after
  isJsxOpeningLikeElement, `file: node` after isSourceFile) then ride the existing monotone
  ctxAcceptsNarrow. Cleared signatureHelp:379 + findAllReferences:1000 — BOTH reproduced in
  minimal single-file repros (no probe needed).
- **Fix 2 (ada176fd, any-spread var-decl objlit):** the round-445 spread-poisons-to-any rule
  existed only on the RETURN path — the var-decl missing-prop/coarse emission now bails too.
  Cleared completions:2391 (`{ ...baseWriter, … }` vs EmitTextWriter, baseWriter from an
  unresolvable `.js`-barrel namespace call).
- **Fix 3 (255a92f6, alias-TP name capture):** `currentTypeParamScope` is consulted BEFORE
  `currentTypeAliasArgs`, so inside an alias substitution a callee TP named like an alias TP
  captured the body's reference — `binarySearchKey<T, U>(…, keyComparer: Comparer<U>)` with
  `Comparer<T> = (a: T, b: T) => Comparison` resolved the body's `a: T` to the CALLEE's T, and
  the anchor mapper's T→Node2 binding typed the comparer callback param as Node2 → FP TS2538 on
  `children[middle]`. The substitution now shadows exactly its own TP names out of the scope.
  Cleared utilities:1750.
- **Fix 4 (36f98fbf, two guard-resolution fixes):** (a) a TP-referencing fn-typed PARAM skipped
  by the B516 gate now still registers in currentParamBindingNames — unshadowed, the call
  resolved through merged globals to a same-named cross-file top-level fn (documentHighlights'
  `getNodes` vs fixAwaitInSyncFunction's; reproduced 3-file). (b) a NEGATIVE guard branch
  (`!isModifier(node)`) collapsed `node` to never — the relation over-accepts `Node <: Modifier`
  (enum-any) — `kindDomainProvesNotSubtype` reads declared `.kind` DOMAINS (bare-enum = whole
  member set; generic token refs thread type-arg NODES through `extends` levels) and keeps t
  when its domain exceeds the target's. Cleared completions:2237.
- **Fix 5 (emitter:994 + program:1088):** (a) checkMemberAccessMissing's knockout single-interface
  branch now bails for Array/ReadonlyArray references — under REAL LIBS (`lib: es2020`, the bench
  tsconfig) a numeric element access `transform.transformed[0]` reached it with propName "0" and
  the lib Array InterfaceDeclaration passed every gate (the "whole-program-only" verdict was just
  the missing real-libs flag — `w.items[0]` reproduces in 3 lines with the bench tsconfig).
  (b) checkReturnAssignability retries a failing generic-REFERENCE target whose type args include
  the fn's OWN TPs with each TP bound to its declared CONSTRAINT — tsc's variance analysis accepts
  the contravariant-TP-usage shape (createTypeReferenceResolutionLoader returning a getter typed
  with the constraint itself); suppression-only, FN-not-FP; bare-TP targets excluded by the
  head-name/no-args gate.
- **Probe technique that unblocked the batch:** a temporary `init {}` block in the Diagnostic data
  class keyed on (code, start, fileName) printing `Exception().stackTraceToString()` — found the
  emitting walker in one services run each time (remember Checker.kt frame line numbers wrap
  mod 65536). Lesson: of the four "whole-program probe needed" verdicts, THREE dissolved into
  reproducible factors (cross-file name collision ×1, real libs ×1, enum-any negative wash ×1) —
  try a name-collision file and the bench tsconfig's `"lib": ["es2020"]` before believing a
  whole-program verdict.
- **Cross-profile at session end:** server 275 → 232 (−43), harness 481 → 429 (−52) — the six
  fixes generalize; bench rows appended (services 40.9 s self, +1.7% vs the round-471b band —
  no perf regression).
- **Fix 7 (the LAST real services FP — symbolDisplay:917/935 TS7034/TS7005): the truthy-guard
  rule, NOT the full evolving-any model.** A captured read of an auto-typed `let x;` inside an
  `if` whose condition TRUTHY-TESTS the variable (`if (flags & Sig && indexInfos)` /
  `x !== undefined`) is provably assigned (undefined is falsy) — the closure-position flow
  inside the guard carries the evolved non-undefined type, so tsc reports nothing.
  `ulCondProvesAssigned` + `UlState.guardDepth` gate the capturedReads recording; an UNGUARDED
  captured read keeps firing (controlFlowNoImplicitAny f9/f10 — re-derived from the reference
  baselines this round: BOTH the nested function decl AND the stored arrow error in tsc, so
  "past the last assignment" alone is NOT the suppressor; the guard is).
  **SERVICES @ 46 — ZERO REAL FPs** (TS2591×43 + TS2304×2 `global` + TS2584 console, all
  env-legit offline artifacts) — the SECOND profile at zero real, matching compiler.
- **Fixes 8–10 (same session): tsc-cli + deprecatedCompat to zero real too — SIX of eight
  profiles at zero real FPs.** The small-profile re-baseline showed tsc-cli @48 (TS7006×2) and
  deprecatedCompat @49 (TS7006×2 + TS2339×1); all three cleared: (8) the embedded lib's
  ObjectConstructor gains `getOwnPropertyDescriptor(s)` — the members were simply absent, and
  even under real libs the embedded `libGlobals` copy is consulted (deprecations.ts:82); the
  plural rides the existing es2017 LIB_MIN_TARGET entry. (9) An arrow's EXPRESSION body inherits
  the contextual signature's RETURN type (`contextualSigReturnTypeForCtx`) — the builder chain
  `overload: overloads => ({ bind: binder => ({ … }) })` contextually types each nested returned
  objlit's fn members (deprecations.ts:144/146). (10) `rhsCanConsumeFnCtx` accepts an objlit
  with fn-shaped members, and `namespaceMemberVarAnnotationCtx` resolves a `ns.Sub.member = {…}`
  target's declared annotation through the merged globals when the root is a namespace import
  whose declaration is the whole ImportDeclaration (tsc.ts:7 `ts.Debug.loggingHost =
  { log(_level, s) {…} }`).
- **NEXT:** burn down server (@230) / harness (@427) with the same listAll-diff workflow —
  their top codes (TS2322×87/TS2339×42 server; TS2339×109/TS2322×102 harness) are
  services-family shapes on server/harness-only files. v1 exit = all 8 profiles at zero real.**

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

