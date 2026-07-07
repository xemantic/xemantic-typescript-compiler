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
- [ ] **M3.3 Mapped / conditional / template-literal / indexed-access evaluation**
  (replace the AST-shape walkers; delete the superseded dedicated walkers and pins).
- [ ] **M3.4 Flow narrowing unified into identifier typing** (`getTypeOfIdentifier`
  consults the flow graph; retire the per-consumer narrowing carve-outs).
  **CONTINUED (round 436f/g): switch-case narrowing of a BARE string subject
  (semver operator family) + guard-gated ternary RETURN arms (the
  checkConditionalReturnBranches tri-state — utilities.ts's
  memberIfLabeledElementDeclaration family, −22 combined). Residual M3.4
  slices: `number | undefined`→number reassignment flow ×4,
  Expression→Identifier narrowed args ×3, `Node`→never exhaustiveness ×3,
  moduleNameResolver `unknown` typeof-narrowing ×3.** **Absorbed
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
