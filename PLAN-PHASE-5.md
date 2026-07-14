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

**Re-scope (2026-07-13, owner — round 490): M5 becomes the staged ARCHITECTURE
INVERSION arc (INV.0–INV.7).** The owner reviewed the round-490 architecture
analysis ("question every decision; cross-check tsc/tsgo") and directed: follow the
recommendation, rescope towards the overall goal. Full analysis + phase design:
**`docs/ARCHITECTURE-RETHINK.md`** — read it before ANY M5/INV item. Headline: the
flat-profile micro-opt mode (rounds 482–489, 1–3%/round) is CLOSED; the measured
cost is a multiplier (~512 sequential full-program checker passes × uncached
`getTypeOfExpression` × context-bypassed `nodeTypes` × per-pass scope re-derivation
× non-interned unions), and the fix is the tsc-shaped inversion: bind-everything →
per-file scopes → single-pass demand-driven checking with per-node caches →
canonical type identity/mappers → share-nothing parallel checkers. Owner-approved
same directive: **kotlinx-coroutines-core as a commonMain dependency** (within the
kotlinx.* rule) for the Flow-based concurrent front-end (read+UTF-8→UTF-16 decode on
`Dispatchers.IO`, parse on `Dispatchers.Default`, bounded `flatMapMerge` = the
owner's measured microbench win) and later the parallel checker/emit phases —
streams live at the I/O boundaries; the checker core stays demand-driven
memoization, per the doc's § 4. Old M5.1–M5.7 are superseded/absorbed by the INV
items in the QUEUE below (M5.1 profiling → INV.0; M5.2/M5.3 → INV.5; M5.4 → INV.6;
M5.5/M5.6 → INV.7; M5.7 targets → doc § 6).**

**Round 519 (2026-07-14) — INV.4(b) batches 10+11: TS2373 param-init forward
refs + the break/continue jump-target family migrated onto the check spine;
6 walker functions (~376 lines) deleted, 2 init dispatches removed.**
Batch 10: `checkParamInitForwardRef`/`walkForParamInitForwardRef` deleted;
`checkForwardRefsInParams` (+ `findForwardParamRefs`/`findForwardParamRefsInBlock`/
`collectHoistedVarNamesFromStmts`) retained as the per-function core,
dispatched from `spineCheckParamForwardRefs` at every BODIED function-like's
enter — the old statement walk reached function/class DECLARATIONS only, so
arrows / function expressions / object-literal methods / class-EXPRESSION
members are faithful widenings (TS2373 is per-signature tsc grammar,
position-independent); bodyless signatures keep the old no-check (TS2371
territory), GetAccessor params stay unchecked (TS1054 territory); the ES5
hoisted-body-var TS2454 companion rides along unchanged (raw
`options.target < ES2015` gate). Batch 11: the `checkJumpTargets` family
(TS1104/TS1105/TS1107/TS1115/TS1116 + TS1344) — the threaded
inIteration/inSwitch/labelNames/crossedFunctionBoundary flags became ONE
parent-chain walk (`spineCheckJumpTarget`) that is a direct mirror of tsc
checkGrammarBreakOrContinueStatement's `while (current)` loop: the first
function-like ancestor → TS1107 (class static blocks now count — the old
walk never descended them); a matching LabeledStatement resolves the jump,
with tsc's isIterationStatement(lookInLabeledStatements=true) nested-label
unwrap for labeled `continue` — a faithfulness FIX over the old
immediate-child test (`L1: L2: for(;;){continue L1}` no longer false-fires
TS1115); an iteration ancestor legalizes unlabeled jumps, a SwitchStatement
legalizes unlabeled `break`, and a ModuleBlock ancestor suppresses unlabeled
`break` (the old inSwitch=true namespace rule — TS1036 owns ambient-context
statements); TS1344 label-on-declaration became a LabeledStatement-enter
handler (widened to arrow-in-condition positions the old expression walk
missed); `emitJumpDiagnostic`/`isDeclarationStatement` retained as the
per-jump core. VERIFIED: 32 pins written FIRST and pre-run against the OLD
walkers (batch 10: 10 green + 4 widening pins fail pre-migration; batch 11:
14 green + 3 widening + 1 faithfulness-fix pins fail pre-migration); suite
10,538 → 10,552 → 10,570 (+14 Inv4SpineBatch10Test, +18
Inv4SpineBatch11Test, 0 regressions); `--listAll` error lines IDENTICAL on
ALL 8 profiles after EACH batch (519a vs 518b, 519b vs 519a); warning-clean; bench 25,491 ms self, 46 errors unchanged (single-run −7.9% = the box-drift band). Batch 12 same session:
checkObjectLiteralModifiers (TS1042/TS1184) — the near-full-tree
explicit-stack expression walk became a pure ObjectLiteralExpression-enter
handler (spineCheckObjLitModifiers; OBJLIT_ACCESS_MODIFIERS
companion-hosted per the init-order gotcha — the spine runs during init, an
instance field declared after init would read null); nested literals get
their own enters; parameter-default + spread-operand positions are faithful
widenings. 3 walker funs (~206 lines) deleted, 1 init dispatch removed.
Suite 10,570 → 10,580 (+10 Inv4SpineBatch12Test — 8 pre-verified against
the OLD walker, 2 widening pins fail pre-migration as expected); listAll
error lines IDENTICAL on ALL 8 profiles (519c vs 519b). NEXT: INV.4(b)
batch 13 — the remaining zero-typing tail
(checkDuplicateObjectLiteralProperties — NOTE its
destructuring-assignment-LHS skip needs a came-from-child parent walk,
checkReservedWordIdentifiers, checkStrictModeReservedWords,
checkAwaitContext — the last is stateful isAsync threading, decompose when
reached).

**Round 518 (2026-07-14) — INV.4(b) batch 8: the parameter-initializer family
migrated onto the spine; 24 walker functions (~902 lines) deleted, 6 init
dispatches removed.** Session opened with the queued `--passTiming` re-measure
(compiler profile, daemons stopped): checker-init 21.6 s of 24.9 s wall; the
spine pass now carries 24 migrated passes at 529 ms; top-3 unchanged
(checkPropertyAccess 3.73 s / checkTypeAssignability 2.43 s /
checkCallExpressionTypes 2.27 s); the name-resolution pair (INV.4(c)) at
932.7 + 681.8 ms; the zero-typing tail remains the batch pool. Batch 8 took
the six-pass parameter-initializer family as THREE Parameter-enter handlers +
ONE SetAccessor-enter handler: (1) `checkOptionalParamWithInitializer`
(TS1015) — parent-kind dispatch reproduces the old reach exactly: the
corpus-tuned requireType gate (tsc's checkGrammarParameterList fires
unconditionally; ours needs a type annotation or a param-property modifier in
DECLARATIONS, fires bare in arrow/fn-expr params — lifting it is signal-driven,
not a migration side effect), interface/type-literal signatures + function
TYPES + objlit/class-expr GET accessors stay excluded; widened faithfully to
class property initializers / parameter defaults / dotted-namespace bodies.
(2) `checkOptionalBindingPatternParams` (TS2463) — the per-parent
owner-has-body gate (overload/ambient signatures exempt); widened to
parameter defaults. (3) `checkParamInitializerForbidden`
(TS2523/TS2524/TS2372/TS2502/TS18048) — `walkParamInitForbidden`, the
binding-name walk, and `collectParamSelfRefs` retained verbatim as the
per-parameter core (all stop at nested fn/class boundaries); the per-FILE
(code@pos) dedup set became `spineParamForbiddenEmitted` (cleared in
checkSpine's file loop); the `walkParamForbiddenExprForFns` nested-fn descent
DISSOLVES into per-Parameter enters (its whole purpose was reaching nested
functions' params — the spine visits them directly); `findParamSelfRef`
deleted as already-dead code (orphaned by B519's collectParamSelfRefs — only
self-recursive references remained). (4) `checkParameterInitializerInNonImpl`
(TS2371) — verified against tsc checker.ts:45130 (checkParameter: initializer
+ `nodeIsMissing(getContainingFunction(node).body)`) and WIDENED faithfully to
every FunctionType/ConstructorType position (the old walk reached fn-types
only under var annotations / type aliases / casts via a 35-branch manual
type-node descent — a fn-type in a PARAMETER annotation was silently
unchecked); bodyless fn/method/ctor declarations + interface/type-literal
methods fire as before; accessors stay excluded (old behavior);
`reportTS2371ForParam` retained as the per-param emitter (binding-element
defaults included). (5) `checkSetAccessorInitializer` +
`checkSetAccessorRestParameter` (TS1052/TS1053, one SetAccessor-enter
handler) — parent gate widened from class DECLARATIONS to class expressions +
object literals per tsc checkGrammarAccessor (verified at checker.ts:52894/52900);
interface/type-literal setters stay excluded (their parse may drop
initializers — signal-driven candidate); emission shapes preserved exactly
(ONE TS1052 per setter at the name; TS1053 per rest param at the `...`).
VERIFIED: 29 pins written FIRST and run against the OLD walkers (23 green,
the 6 widening pins fail pre-migration as expected — exactly the widened
positions); suite 10,491 → 10,520 (+29 Inv4SpineBatch8Test, 0 regressions);
listAll error lines IDENTICAL on ALL 8 profiles (518a vs 517b); warning-clean
(--rerun-tasks). Bench row: 26,357 ms self, 46 errors unchanged (the TSV's
−25% vs-previous compares against round 517's documented drift-artifact row —
vs the clean 517 band ~26–27 s this is neutral). BATCH 9 same session: FOUR
more passes (16 walker funs, ~606 lines deleted, 4 init slots removed):
(1) `checkForInLhsTypeAnnotation` (TS2404) — ForInStatement-enter handler;
widened faithfully to arrow/fn-expr bodies (the old statement walk had no
expression descent at all). (2) `checkEmptyTypeArguments` (TS1099 on
calls/new) — CallExpression/NewExpression-enter; the `<>` source scan from
the callee start preserved; the type-POSITION TS1099 emitter (emitTS1099's
other caller at ~27072) untouched; `reportEmptyTypeArgs` deleted as orphaned.
(3) `checkSetterReturns` (TS2408) — SetAccessor-enter with body;
`checkSetterBodyReturns` retained (does not cross fn/class boundaries);
interface/type-literal setters have no body so no parent gate is needed;
widened to expression positions the finder walk missed (await operands —
pinned). (4) `checkWithStatements` (TS1101/TS1300/TS2410) — the threaded
isInWith/isInAsync pair became ONE parent-chain walk from the WithStatement:
a WithStatement ancestor hit BEFORE any function-like boundary suppresses
TS1300/TS2410 (inner with of a chain); the nearest function-like boundary
decides isInAsync (Async modifier on fn-decl/fn-expr/method; ARROWS reset to
false — the old walker's rule, tsc's AwaitContext would fire for async
arrows, a signal-driven widening candidate pinned negative; ctors/accessors/
static blocks/namespaces reset too); TS1101 gated on `spineWithStrictActive`
(alwaysStrict != false); TS2410's balanced-paren span scan preserved
verbatim; widened to class property initializers (pinned). VERIFIED: 18 pins
pre-run against the OLD walkers (14 green, 4 widening pins fail
pre-migration as expected); suite 10,520 → 10,538 (+18 Inv4SpineBatch9Test,
0 regressions); listAll error lines IDENTICAL on ALL 8 profiles (518b vs
518a); warning-clean. Session total: TEN passes, 40 walker funs (~1,508
lines) deleted, 10 init dispatches removed, suite +47. Ops note: a
`pkill -f "MainK[t]"` bracket pattern still matched ITSELF because a
`pgrep -af "MainKt"` LITERAL sat earlier in the same compound command — the
bracket trick must cover every occurrence of the pattern in the command
line, not just the pkill's own argument.

**Round 517 (2026-07-14) — INV.4(b) batch 6: duplicate-modifier grammar +
ambient initializers + switch/case comparability migrated onto the spine;
9 walker functions (~453 lines) deleted, 3 init slots removed.** Three passes:
(1) `checkDuplicateModifiers` (TS1030/TS1029 + TS1044 via
`checkInvalidImportEqualsModifiers`) — dispatched for the exact 10 statement
kinds the old walk checked (class/interface members handled inside the
class/interface handler, preserving member-kind coverage); the TWO threaded
flags with DIFFERENT reset rules (inAmbientContext: reset by fn/member
bodies, set by declare-module descent; atTopLevel: false in Blocks, reset
TRUE in ModuleBlocks, preserved through if/else) became one parent-chain walk
(`spineDupModContext`) where the innermost flag-deciding ancestor wins per
flag and any ancestor kind the old walk never descended (loops, switch, try,
labeled, arrow/fn-expr bodies, class expressions) returns null = no-visit.
Reach deliberately NOT widened: `checkModifiers` is an FP-firewalled TEXT
heuristic (B69.6 `as const` firewall, B459b TS1184-ownership suppression for
non-top-level functions, B61.5g ambient import-equals TS1029 skip) — the
negative pins (for-loop body, nested fn) hold the line. (2)
`checkAmbientInitializers` (TS1039/TS1254/TS1066/TS1031) — EnumDeclaration /
VariableStatement / ClassDeclaration enter handlers over
`spineAmbientInitContext` (pass-through for the old walk's full statement-
container set incl. loops/switch/try/labeled; declare-module sets ambient,
fn bodies reset it, SourceFile terminal returns `spineIsDts` = the
.d.ts-top-level-ambient rule — this pass has NO dts skip). Class-member and
arrow/fn-expr bodies stay unreached (pinned negative — tsc WOULD emit TS1039
for `declare var x = 1` in a method body; widening is a signal-driven change,
noted in the KDoc). The B162 same-enum member-reference exception's sibling
scan reproduced via `spineSiblingStatements` (parent statement list;
single-statement positions degrade to `listOf(stmt)`). The class-EXPRESSION
member path (TS1031 fires regardless of ambient) retained via the unchanged
`visitExprForClassExpression` Paren/Binary-only descent from VariableStatement
initializers. (3) `checkSwitchCaseComparable` (TS2678) — the old walker's
per-statement-LIST const/annotated binding maps (FRESH per nested block,
accumulated in statement order, annotated-wins-over-const, non-classifiable
writes preserve earlier entries) reproduced as a preceding-sibling scan AT
the SwitchStatement node (`spineSwitchSubjectBinding` breaks at the switch,
so declared-after bindings stay invisible — pinned); the old broad expression
descent (arrow/fn-expr/class-expr bodies) is subsumed by the spine, widening
faithfully to the few positions it missed (parameter defaults). VERIFIED:
27 pins written FIRST and run against the OLD walkers (all green), then the
migration — suite 10,443 → 10,470 (+27 Inv4SpineBatch6Test, 0 regressions);
listAll error lines IDENTICAL on ALL 8 profiles (517a vs 516b); bench in
band (27,212 ms self, +1.3% = noise; 46 errors unchanged). BATCH 7 same
session: FOUR more passes migrated (15 walker funs, ~551 lines deleted, 4
init slots removed): (1) `checkRestElementPropertyNames` (TS2566) — a
pure-syntax grammar rule became an ObjectBindingPattern-enter handler; the
manual nested-pattern recursion dissolves (every nested pattern gets its own
enter) and coverage widens faithfully to catch-clause patterns (pinned).
(2) `checkRestBindingPatternElements` (TS1186/TS2493/TS2322) —
`checkRestBindingParam` + its three emission helpers retained verbatim as
the Parameter-dispatch core (third Parameter handler); the old
statement/expression scan pair deleted; coverage widens to object-literal
methods and class-expression members (pinned). (3) `checkAmbientImplementation`
(TS1183) — the most intricate reach reconstruction so far
(`spineAmbientImplContext`): an AMBIENT FunctionDeclaration / class-DECLARATION
member body was never descended (its `{` already reported) — own-`declare`
returns null immediately and a declare-module found ABOVE such a body
returns null too (the `passedDeclBody` flag); arrow / fn-expr /
class-EXPRESSION-member / objlit-method bodies RESET ambient to false
UNCONDITIONALLY (the old expression walk descended them with `false` even
under ambient — so `passedDeclBody` must be CLEARED at those boundaries, or
a fn nested arrow-deep inside a declare-module wrongly nulls); statement
containers are position-checked (if-conditions, for-headers, switch
subjects, case expressions stay unreached) while expressions pass through
generically (the old expression walk was comprehensive). PIN CORRECTION
found pre-migration: `interface I { m() { } }` draws NO TS1183 from the old
walker — the interface member parse never STORES a body (the TS1246
situation again), so the interface arm is de-facto dormant and migrated
as-is. (4) `checkAmbientRelativeModuleNames` (TS2436) — the old
non-recursive top-level-of-script-file loop became a ModuleDeclaration-enter
handler gated on `parent is SourceFile` + `!spineFileIsModule`. VERIFIED:
21 pins run against the OLD walkers first (19 green; the 2 widening pins
fail pre-migration as expected, proving them genuine); suite
10,470 → 10,491 (+21 Inv4SpineBatch7Test, 0 regressions); listAll error
lines IDENTICAL on ALL 8 profiles (517b vs 517a). BENCH-DRIFT episode
(round-493 rule vindicated): single-run bench read +11.7% then +16.1% ON
THE SAME BINARY — the box had degraded to 864 MB free after the session's
JVM churn (two suites + 16 listAll runs + a foreign project's 2.3 GB gradle
daemon); after `./gradlew --stop` + kotlin-daemon kill (6.3 GB available),
the INTERLEAVED 3-pair A/B measured batch-7 vs batch-6 at −461/−801/+408 ms
(mean −285 ms) = NEUTRAL, both binaries back at the ~26 s session-start
band. The two drift-inflated TSV rows (30.4 s / 35.3 s) are artifacts —
trust the interleaved verdict. Session total: SEVEN passes, 24 walker funs
(~1,004 lines) deleted, 7 init slots removed, suite +48. NEXT: INV.4(b)
batch 8 — re-measure --passTiming for the next cost-ordered candidates;
checkMixinClassConstructor stays (d).**

**Round 516 (2026-07-14) — INV.4(b) batch 4: the accessor-grammar family +
TS1014/TS1113/TS1246 migrated onto the spine; 17 walker functions (~733 lines)
deleted, 4 init slots removed.** (Session start: recovered from an OOM-killed
predecessor — round-515 work was all committed; nothing lost.) Four passes
migrated: (1) `checkSetterParameterCount` (the largest hand-walk family so far,
~290 lines / 6 functions carrying FOUR codes) split into three handlers —
`spineCheckGetAccessorGrammar` (TS1054, widened faithfully from
class-decl+objlit to class-EXPRESSION and interface/type-literal getters — the
old walk never checked class-expr getters at all), `spineCheckSetAccessorGrammar`
(TS1049 preserved across all old contexts + widened to interface/type-literal
setters; TS1095 widened from class declarations to class EXPRESSIONS — and
PROVABLY no further: the objlit accessor parse never PARSES a setter return
annotation and the interface parse drops it, a first-cut objlit pin caught
this), and `spineCheckAccessorPairVisibility` (TS2808, deliberately KEPT at the
ClassDeclaration gate — the old walker never checked class expressions; pinned
negative). (2) `checkRestParameterLast` (TS1014) — a second Parameter-enter
handler beside batch 2's `spineCheckRestParamLast` sibling: rest param not last
among the parent's real params fires at the `...` (B18.2 comma-recovery
suppression preserved); parent set = the old value positions + a faithful
widening to FunctionType/ConstructorType/type-literal methods (tsc
checkGrammarParameterList runs for every signature declaration — pinned);
GetAccessor parents stay excluded (old behavior, TS1054 owns that shape).
(3) `checkMultipleDefaults` (TS1113) — a trivial SwitchStatement-enter handler
with the old one-per-switch errorEmitted latch preserved (three-defaults pin);
widened to parameter-default initializers (pinned). (4)
`checkInterfacePropertyInitializers` (TS1246) — an InterfaceDeclaration-enter
handler; NOTE the parser owns the common `= init` shape (consumes without
storing + reports at the initializer's first char), so the checker handler
covers only initializer-CARRYING PropertyDeclarations (none of the current
type-member parse paths store one — the handler is the faithful migration of
the old walker's semantics, kept cheap). All handlers carry the old driver's
`!spineIsDts` gate. VERIFIED: suite 10,405 → 10,427 (+22 Inv4SpineBatch4Test,
0 regressions); listAll error lines IDENTICAL on ALL 8 profiles (vs the 515b
baselines; header path-form + timing lines only); bench row in band (compiler
26,738 ms self, +1.1% vs previous = noise). BATCH 5 same session: THREE more
passes migrated (~318 lines / 7 walker funs deleted, 3 init slots removed):
(1)+(2) `checkConstWithoutInitializer` (TS1155) + `checkDestructuringWithoutInitializer`
(TS1182/TS7031) as VariableDeclaration-enter handlers sharing the owner gate
(VariableStatement non-`declare`/non-ambient, or a plain `for(;;)` initializer;
for-in/for-of iteration vars excluded — legal without init) — the old walkers'
`isAmbient` threading was set ONLY by ModuleDeclaration descent and RESET by
every other recursion, so the parent-chain equivalent
(`spineInDeclareModuleChain`) walks up through ModuleBlock/ModuleDeclaration
ONLY and terminates non-ambient at any other ancestor kind; `emitTs1182IfMissingInit`
(the TS1182+TS7031 emission) retained; faithful widening: for-of/for-in BODIES
(the old walks had no ForOf/ForIn statement case at all — TS1155 pinned) +
arrow bodies. (3) `checkComputedPropertyNameLiteral` (TS1166/TS1169 + the
class-expression TS1206 decorator short-circuit) — PropertyDeclaration-enter
dispatching on parent (InterfaceDeclaration → TS1169, ClassDeclaration →
TS1166, TypeLiteral unchecked as before) + a ClassExpression-enter handler
(`spineCheckClassExprComputedProps`) DELIBERATELY position-gated to the old
reach (expression-statement position through void/paren wrappers — a var-init
class expr stays unchecked, pinned negative; per the round-42 TS1166
direction-of-emission history, widening THAT is a change to make on a signal);
`isLiteralLikeExpr` + `emitComputedPropNameNonLiteral` retained; faithful
widening: class declarations in arrow bodies (pinned). VERIFIED: suite
10,427 → 10,443 (+16 Inv4SpineBatch5Test, 0 regressions); listAll error lines
IDENTICAL on ALL 8 profiles (516b vs 516a); bench in band (26,871 ms self,
+0.5% = noise). Session total: SEVEN passes migrated, 24 walker functions
(~1,050 lines) deleted, 7 init slots removed, suite +38. NEXT: INV.4(b)
batch 6 — checkDuplicateModifiers (TS1030/TS1029, threads inAmbientContext +
atTopLevel — needs parent-walk equivalents), checkAmbientInitializers
(TS1039/TS1254/TS1066/TS1031, .d.ts-topLevelAmbient + class-expr descent),
checkSwitchCaseComparable (TS2678, block-scoped const tracking);
checkMixinClassConstructor stays (d) territory (TP-scope machinery).**

**Round 515 (2026-07-14) — INV.4(b) batch 2: TS2370 + the iterator/yield-star
prepass pair migrated onto the spine; 16 walker functions (~460 lines) deleted.**
(Session start: recovered from an OOM-killed predecessor — round-514 work was
all committed; lesson re-confirmed: never start a listAll JVM while the suite
runs.) Three passes migrated: (1) `checkNonArrayRestParameters` — the old
VALUE-position statement/expression walk pair and the TYPE-context annotation
walk collapsed into ONE `Parameter`-enter handler (`spineCheckRestParam`)
dispatching on the parameter's PARENT kind: FunctionDeclaration / Constructor /
ArrowFunction / FunctionExpression / method-of-{class, class-expr, interface,
objlit} → the keyword rule; FunctionType / ConstructorType /
method-of-TypeLiteral → the B71.2 optional-rest rule; anything else (accessors,
index sigs) → skip. Both rules widened faithfully (position-independent
per-signature tsc grammar — new visits: param default values, casts,
decorators; pinned both directions). (2)+(3) `checkIteratorMethodExtraParameters`
(TS2488/TS2504) + `checkAsyncYieldStarThenable` (TS1320) — the fused prepass
pattern: collection happens ON the spine (`spineCollectObjLitVar`,
VariableDeclaration enter gated to a VariableStatement parent; first-wins objlit
map + last-bad-wins BadIterInfo map, exactly the old maps' write semantics) and
iteration positions (for-of / spread / `yield*` operands) plus `yield*` thenable
candidates are BUFFERED and resolved at file END
(`spineResolveDeferredIterationChecks`) — use-before-declaration still matches
(the old passes collected in a full prepass first) with NO second walk. This is
the template for collect-then-scan walkers. TS1320's old statement-level-only
reachability widened to a nearest-function-like-ancestor async-generator gate
(`spineInAsyncGeneratorBody`; arrow/ctor/accessor boundaries return false —
none can be a generator). All three old passes had NO option gates; the
iterator/yield handlers keep the per-check `!spineIsDts` gates, TS2370 stays
ungated (covers .d.ts as before). Deleted: 16 functions (~460 lines — the two
TS2370 walk families, walkIterationPositions/walkIterPosStmt/walkIterPosExpr,
both collect prepasses, both scan walks); retained shared helpers:
nonArrayKeywordText (2 other consumers), BadIterInfo + badIteratorDisplay +
inferGeneratorYieldDisplay, objLiteralYieldsNonPromiseThenable +
isComputedSymbolMethod + singleReturnObjLiteral. VERIFIED: suite 10,375 →
10,396 (+21 Inv4SpineBatch2Test: keyword-rest across all five value-position
parent kinds, optional-rest in fn-type/type-literal/cast, use-before-decl
deferred resolution, async TS2504 via yield*, widening pins for param-default
arrows + class-property-initializer for-of + nested-if yield*, negative
controls for sync generators / data-property `then` / match-all fn-type
keyword rest); listAll error lines IDENTICAL on ALL 8 profiles (compiler +
services vs the round-514c baselines, the other six vs 513d3e); wall in band
(compiler 26.8 s vs 27.2 s baseline single-run). BATCH 3 same session:
checkForOfNonIterable (TS2495) + checkAbstractAccessorReturnTypes (TS7033)
migrated — TS2495's option gate (lib explicitly excludes es2015+/esnext, never
under noLib) computed once per run (`spineForOfNonIterableActive`), the
conservative verdict helper `checkForOfExprNonIterable` retained unchanged, the
handler on ForOfStatement enter (the old walk already descended statements AND
expressions, so widening is negligible — param defaults pinned); TS7033 as a
GetAccessor-enter handler with THREE preserved gates: noImplicitAny/strict
(`spineAbstractAccessorActive`), the `.js`/`.jsx` skip (deliberately NOT
`spineIsJsLike` — the old pass ran on .mjs/.cjs), and the
ClassDeclaration-PARENT gate (class-EXPRESSION members stay unchecked — a
bodyless accessor there is a parse-recovery shape; pinned negative). Faithful
widening pinned: a class DECLARATION nested in an arrow body (the old
statement walk had no expression descent). 6 more walker funs deleted + the
round-514 orphaned TS18045 KDoc swept. VERIFIED: suite 10,396 → 10,405 (+9
Inv4SpineBatch3Test, 0 regressions); listAll error lines IDENTICAL on ALL 8
profiles (vs the same-session 515a set); wall in band (compiler 26.5 s).
Session total: FIVE passes migrated, 22 walker functions (~600 lines)
deleted, suite +30. NEXT: INV.4(b) batch 4 — checkMixinClassConstructor is
TP-scope-stateful (likely (d) territory); re-consult the round-491
`--passTiming` table for the next most-mechanical tail passes.**

**Round 514 (2026-07-14) — INV.4 DECOMPOSED into (a)–(f) + INV.4(a) LANDED: the
single-pass check spine exists with its first migrated pass.** (Session start:
recovered from an OOM-killed predecessor — all round-513 work was verified
committed; nothing was lost.) Queue restructure first (`chore(queue)`): INV.4
split into (a) skeleton+pilot / (b) sub-100 ms tail batches / (c) the
name-resolution pair onto the INV.2(c) lexical tables / (d) mid-weight stateful
walkers / (e) the top-3 giants / (f) the per-node type cache + folded flow
narrowing, with the cross-cutting rules captured on the parent item: the spine
is ONE `pass("checkSpine")` at a FIXED init position (stable diagnostic sort
hides insertion-order deltas except exact 4-tuple ties — per-migration gates
decide); a spine handler sees ALL nodes so each migration decides
widen-vs-gate by the CLAUDE.md emission-direction rule; every migrated pass
gets local pins BEFORE migration (corpus pins emit bytes, not checker
diagnostics). INV.4(a) then landed: `spineWalkFile` (iterative enter/leave
preorder, explicit parallel stacks — same push-reversed convention as
indexSourceFile), `spineEnterNode`/`spineLeaveNode` `when` dispatch, spine
context fields pre-`init`, and the active-handler gate (all handlers off →
walk skipped entirely). Pilot migration: TS18045 accessor-modifier-vs-target —
the old walker's threaded `inAmbient` dissolved into a parent-chain ancestry
check, the class-parent gate keeps interface/type-literal members excluded
(they share ClassElement), and the old hand-walk's under-visits (class
EXPRESSIONS, arrow bodies) became visits — deliberate faithful widening for a
position-independent tsc grammar rule, pinned in both directions. Also fixed
two round-513 leftover warnings (always-true `!==` Intrinsic comparisons in
the NEW-EXPRESSION assignment-reduction arm — the predecessor session died
before its warning sweep). VERIFIED: suite 10,356 → 10,365 (+9
Inv4SpineAccessorModifierTest incl. the 10k-term iterative-walk sharp-signal
pin: TS18045 present AND TS2589 absent); `--listAll` byte-identical on
compiler AND services vs the round-513 baselines (46/46 diagnostics; only the
header's argv form differs); wall in band (compiler 25.8 s vs 26.1 s
baseline). INV.4(b) BATCH 1 landed same session: checkInvalidGlobalAugmentations
(TS2669/TS2670) + checkReservedWordInterfaceParams (TS7051/TS7006) became
spine handlers and their private walks were deleted. Both old walks descended
ONLY through module bodies, so reachability is reproduced as a module-chain
parent-walk gate — "every ancestor is ModuleBlock/ModuleDeclaration up to
SourceFile" — computing the threaded flags (insideRegularNamespace /
insideAmbientModule) in the SAME walk; this is the template for
module-scope-only walkers. The reserved-params handler deliberately does NOT
widen to function/class-nested interfaces (tsc does flag them, but that
behavior change wants a signal, not a migration side effect — noted in the
KDoc). Structural consequences: the spine walk is ALWAYS-ON from this batch
(the TS2669 handler is unconditional and covers .d.ts — the file-level dts
fast-skip lifted into per-handler gates), and checkSpine's file loop now
sets currentFileLocals per file (isTypeLikeParamName consults it; saved and
restored around the whole pass). Test-authoring note: TS7051 needs a
STRICT_MODE_RESERVED_WORD that also names an in-scope type — `string` is a
type keyword, NOT a reserved word, so the 7051 branch is near-unreachable
and is pinned via its negative gate instead. VERIFIED: suite 10,365 →
10,375 (+10 Inv4SpineBatch1Test); listAll byte-identical on compiler AND
services. THE MEASUREMENT LESSON: the 3-iteration bench read +5.2% — the
round-493 interleave rule applied (3 old/new pairs, same box, back-to-back)
split that into ~+1.0 s REAL walk cost + drift; the first-cut walk paid a
boxed ArrayList<Boolean> frame per node and a leave ROUND-TRIP per leaf.
Fixed in-session (primitive BooleanArray phase stack; leaf shortcut — leave
fires inline for childless nodes) → re-interleaved neutral within noise
(mean +124 ms over 3 pairs). Enter/leave SEQUENCE is unchanged (a leaf's
leave always fired before the next node), so handler semantics are
byte-identical. NEXT: INV.4(b) batch 2 —
checkNonArrayRestParameters (read its value-position walk first; two
differently-shaped walks) + the per-file-prepass pair
(checkIteratorMethodExtraParameters / checkAsyncYieldStarThenable, the
file-enter hook pattern).**

**Round 513 (2026-07-14) — INV.3(d)(v) deletion #1: the `moduleFileLocalVarNames`
value-leak ecology DELETED (the round-442/445/447/448/450/453/460 family).**
Removed: the field, the 1a2 builder, all seven consult bails (mam receiver-chain
walk, arg-loop root walk via `argRootIsLeakedModuleVar`, return-path, assignment
RHS + TARGET, var-decl alias inference, `arithOperandType` operand bail).
Post-retire every consult was hypothesized inert — the gates found exactly TWO
pre-retire accidental masks (the round-512 (iii) lesson class): (1) the suite
caught ModuleVarLeakAssignReturnTest's fixture genuinely erroring in tsc
(`Named ⊄ Target | undefined` — the destructured local now resolves CORRECTLY;
the bail had been suppressing a genuine error; fixture rewritten into a sharp
leak-kill pin). (2) the 8-profile A/B caught harness +2 TS2339 (fakesHosts.ts
`sys.vfs` on `System | FileSystem`) = TWO real narrowing gaps the bail masked —
it over-suppressed EVERY `sys`-rooted member chain program-wide because
compiler/sys.ts exports a module-level `let sys`. Fixed tsc-faithfully:
`resolveInstanceOfRhsType` resolves a ns-import-QUALIFIED class
(`sys instanceof vfs.FileSystem`) via `resolveNamespaceQualifiedSymbol` (both
branches narrow); `narrowByAssignmentRhs` gained the NEW-EXPRESSION
assignment-reduction arm (tsc getAssignmentReducedType — the 4th round-477
sibling); plus the DIR-RELATIVE resolver leg in `namespaceAliasMemberSymbol`'s
three arms (the THIRD instance of the round-511 lesson class, purely additive).
Pins: InstanceofNsQualifiedNarrowingTest (5). VERIFIED: suite 10,346 → 10,351
(0/3); ALL 8 profiles listAll byte-identical vs pre-deletion.
**Deletion #2 (same session, resumed after an OOM kill): the
`conflatedTypeAliasFiles` alias-shadow ecology DELETED** (rounds
443/444/447/464/468b/474/475/476/477): the field, the 1a3 builder, the mam
receiver bail (+ aliasOwnFileHasProp), `paramTypeIsLeakedConflatedAlias`,
`returnSourceSatisfiesFileLocalAliasBody`,
`objectLiteralMatchesConflatedFileLocalTypeAlias`,
`implicitAnyMemberAnnotationConflated`, the round-477 QualifiedName
per-file-view dispatch, `qualifiedAliasShadowedTarget` + its return-path
early-return, the round-474 keyof recovery (2 helpers), the round-464 negative
gate in typeNodeDefinitelyNonNullish, and isConflatedInterfaceRefNode's alias
arms. TWO pieces RE-KEYED onto their true conditions (both are NON-conflation
gaps the alias table happened to gate): (1)
`objectLiteralSatisfiesAugmentationMergedInterface` — fires on
augmentation-block `interface X` presence (the un-modeled cross-file
augmentation MEMBER merge); the gates caught a same-named-CLASS hole
(jsExportMemberMergedWithModuleAugmentation: `class Abcde { x }` merges
invisible-to-the-scan members → bail). (2)
`objectLiteralMatchesFileLocalAliasUnion` — a returned objlit vs a FILE-LOCAL
alias union name-covering some constituent (the round-438 nullish-strip-only
objlit gate makes REFERENCE-valued members' guard narrow-DOWNs invisible —
fixAddMissingMember.ts:410 `{ kind: InfoKind.Enum, token, parentDeclaration }`
vs `Info | undefined` was the sole 3-profile diff), WITH an explicit-value type
firewall (ConflatedAliasBodyOwnerContextTest's negative control caught the
name-only version over-suppressing `{ errors: "not an array" }`). Pins:
FileLocalAliasUnionReturnTest (3); suite + 8 profiles green.
**Deletions #3+#4 (same session): the `conflatedInterfaceFiles` chimera bails
AND the per-file-view core + enum subsets DELETED — INV.3(d)(v) COMPLETE, the
whole conflation ecology is gone** (~24k chars of Checker.kt): the five objlit
chimera helpers + all callers (ternary/var-decl/return/or-and-nested/ARG), the
three relation-entry twin bails + argIsConflatedTwin, the TS2430 view, the
heritage ctx threading, `conflatedPerFileInterfaceType`/`perFileInterfaceType`/
`conflatedPerFileViewForContext`/`resolveTypeAliasBodyWithOwnerContext`/
`collectConflatedRefNames`, `conflatedOwnerFile`/`conflatedCtxMissing`
(getTypeOfSymbol caches unconditionally again), `conflatedEnumFileSubsets` +
the exhaustiveness relaxation, and the 1a4/1a5 builders (reduced to
`moduleInterfaceNames`). The gates caught TWO more masked residuals, both
fixed re-keyed: (a) the 4 codefix-`Info` sites (useDefaultImport etc.) = the
round-445 INTERFACE flavor of the narrow-DOWN residue — the alias-union bridge
gained an interface leg GATED to `multiFileModuleTypeNames` (an ungated leg
over-suppressed 10 narrowing negative controls — reference-value exemption
must not apply to single-file interfaces); (b) **the `nodeTypes` bypass is NOT
conflation-specific**: the cache is STRUCTURALLY keyed and positions COLLIDE
across files, so two codefix files' identically-positioned `Info | undefined`
annotations shared one cached resolution → post-retire that leaks another
file's interface (fixForgottenThisPropertyAccess excess-'node' FP); restored
re-keyed as `isPerFileDependentRefNode` over type names declared in ≥2 module
files. Also reinstated re-keyed: the round-473 `A && objlit` falsy-remainder
TS2322 emitter (combineBinaryTypes deliberately skips the primitive→literal
falsy decomposition — `count && obj` is `0 | {…}` in tsc; the deleted
conflation block had been carrying this GENUINE-error emission, pinned by
ConflatedObjLitReturnArmsTest's negative control). Pins:
PerFileTypeNameCacheCollisionTest (2). VERIFIED: suite 10,356/0/3; ALL 8
profiles listAll byte-identical vs the pre-deletion baseline. **INV.3(d) and
the INV.3 arc are COMPLETE — next: INV.4 (single-pass check spine).**

**Round 512 (2026-07-14) — INV.3(d)(ii)+(iii)+(iv) ALL DONE; the retire branch is
MERGED TO MAIN: suite fully green, ALL 8 profiles byte-identical to pre-retire.**
Six commits. (ii) the last 6 corpus multi-file failures, one per-consult-flip family
each (union-discriminant objlit drill node-keyed — indirectDiscriminant; ns-import
static TS2339 + the DIR-RELATIVE legs in resolveAlias's ImportDeclaration AND
ImportSpecifier branches (the round-511 lesson class — the corpus test passed while
the path-shaped MainKt repro still failed, twice) — exportStarFromEmptyModule;
TS2749 file-keyed + the NEW `typeSideImportFallback` gate (a local `function
NodeLinks(this: NodeLinks)` shadowing a TYPE import stays type-eligible — the naive
flip manufactured TS2749×48 on both profiles) — allowImportClauses; the B585
contextual-display hops — allowJscheckJs; JSDoc ImportType own-specifier resolution
— checkJsdocTypeTag2; the TS2415 imported-base heritage flip —
declarationEmitPrivateSymbol2). (iii) the last 4 local pins: 2 resolver gaps (the
`export * as NS` re-publication arm in namespaceAliasMemberSymbol; the ns-member
objlit ctx root/sub-namespace flips) + 2 PRE-RETIRE ACCIDENTAL PASSES — the
conflated receiver used to resolve to a sibling interface and fire through
Interface-gated emitters; post-retire the correct anonymous resolution exposed two
genuine FNs, fixed tsc-faithfully (mam all-missing ALL-ANONYMOUS union TS2339, no
member chain; TS2345 `paramIsPlainObjectBag` gated to all-concrete member types —
the sys.ts B83.5 closure-param mis-binding FP'd until the gate). (iv) all three
profile-residual families: deprecate.ts `compareTo` (an anyType shadow now BAILS
mam — the outer-import fall-through was categorically wrong; pre-retire the merged
symbol's polluted flags dodged the string branch by accident); **server/harness
session.ts Diagnostic — the round-473 Identifier DISPATCH into
`conflatedPerFileInterfaceType` is REMOVED (the first (v) deletion): post-retire
the node-keyed resolution already yields clean per-file interfaces, and the view
minting had turned actively wrong — two instances of protocol.ts's SAME
`Diagnostic` declaration resolved their nested `DiagnosticRelatedInformation`
member in DIFFERENT files' views (probe: srcElem riFile=types.ts cat=
DiagnosticCategory vs tgtElem riFile=protocol.ts cat=string) and failed to relate;
an env-gated experiment proved removal restores the pre-retire baselines exactly**;
fourslashImpl `'array'` (namedUnionMemberCouldAcceptArray hops import aliases —
ImportSpecifier via resolveImportedSymbolGeneral, ImportEquals via resolveAlias).
VERIFIED: full suite green; **all 8 profiles listAll byte-identical vs a
main-worktree pre-retire build** (the (iv) exit gate). Local pins:
UnionDiscriminantModuleAliasTest(3), NamespaceImportStaticMemberTest(2, incl.
path-shaped), PostRetirePerFileConsultsTest(7), PostRetireProfileResidualsTest(4).
NEXT: (v) — delete the remaining conflation ecology walker-by-walker (the
Identifier dispatch removal is the template: measure each against all 8 profiles).

**Round 511 (2026-07-14) — INV.3(d)(i) DONE + (ii) 8/14 + (iii) 4 flips & 2 pin-updates
(all on branch `wip/inv3d-merge-retire`, rebased onto main; 4 commits): branch
failures 42 → 10.** (i) The ambiguous-constrained→foreign TP leg reverted
(declaration-identity kept) — 17 tests flipped incl. the WhileTrue/tsx collateral;
checker.ts:7358 re-solved inference-side (CallExpression args carrying a TypeParam
soft-skip at forReturnType sites, pinned by ForeignTpInferenceSoftSkipTest ×6).
(ii) Per-consult flips per the round-510 method: heritage/implements walkers
node-keyed + the B563 ownership-gate mirror (the double-TS2420 lesson: when a
walker's ownership gate is "the OTHER walker resolves it", both must consult the
SAME primitive), checkConstraintsForTypeArgs keyNode/presetSymbol (ImportType
resolves through its OWN specifier), checkTypeNameResolved's leftSym →
globalsForFile (the pass runs unscoped — currentFileLocals is null there), the mam
type-only-winner + ns-import value-side bail. **Critical find: the import hop
lacked a DIR-RELATIVE resolver leg — path-shaped extensionless imports never
hopped, so post-retire EVERY import-mediated type died on real on-disk projects
(repro: `const x: number = importedString` drew NOTHING); pre-retire the merge
masked it, and the tsc profiles' `.js` specifiers take the separate stripping leg,
so listAll byte-identity NEVER covered this class — the local direct-construction
pins (EnclosingImportIndexTest) were the only net that caught it. Lesson recorded
as a CLAUDE.md gotcha: profile byte-identity is NOT sufficient verification for
resolution changes; scratch-project MainKt repros are cheap and decisive.**
(iii) Two pin-updates to the post-retire contract (unindexed-copy degradation
nulls module-only names; the leak worklist assertion inverted — an emptied
worklist is the victory condition). Every commit gated on compiler AND services
`--listAll` byte-identical vs the round-510 captures. Remaining on the branch:
6 corpus (roots noted per-test in the (ii) item) + 4 local (all suspected REAL
suppressions — r7/r8 scratch repros exist for the ConflatedTypeAliasLeak pair) +
the (iv) profile residuals. Main untouched.

**Round 510 (2026-07-13) — INV.3(d) merge retire: BUILT AND PROFILE-VERIFIED on
branch `wip/inv3d-merge-retire` (033598b6); main untouched pending the
enumerated 36-failure worklist.** The retire (skip a MODULE file's module-only
top-level locals in the step-1 merge) landed with every consult flip the
empirical loop demanded, and reached **compiler AND services `--listAll`
BYTE-IDENTICAL vs pre-retire HEAD**. Method (the session's real product): (1)
naive full retire → 861 compiler FPs, dominated by SHARED names — module
interfaces riding same-named LIB globals (`Symbol`/`Node`/`Performance`),
whose importers free-ride on the merge because the general resolver can't
follow `.js` barrels → **the retire must stage by NAME CLASS: module-only
first, SHARED later** (predicate: lib-keys ∪ script-locals computed pre-merge,
kept as `mergeSharedKeepNames`); (2) module-only cut → 34 FPs; a
classifier-MISS stack-sampling probe (a miss on a retired name was a hit
pre-retire = exactly the at-risk traffic) + the Diagnostic-init emitter probe
traced every family to its consult: the shadow-ecology collision questions
(gain a `currentFileLocals` disjunct — imports no longer sit in globals),
checkMemberAccessMissing's bare-Identifier receiver, checkTypeArgCount's
enum-member gate, aliasUnionContainsNullishKeyword,
calleeDeclaredCtxParams/resolveClassCtorParamsForCtx,
computeImportedCalleeFunctionType's collision gate (globalsForFile),
getTypeFromTypeQuery, resolveTypeOfValueEntityName, resolveQualifiedName +
resolveQualifiedValueSymbol (the QualifiedName-root convention is untenable
post-retire), findVariadicTupleInTarget/arrayElementTypeNode; (3) services
flood (TS2339×5355!) rooted in ONE line: `mergeModuleAugmentations`' else-add
put an AUGMENTATION-ONLY stub into globals when the retired base missed, and
the 1b visibility delta then marked `SourceFile`/`Node` non-module-only so
every fast-path consult returned the stub — fixed by resolving `.js`
augmentation targets (`resolveModuleSpecifierRelativeJsAware`; the
locals-merge now fires, so augmentation members reach the target file's own
symbol) and gating the globals-add to FILELESS ambient targets; (4) the
remaining services families forced real per-file machinery:
`typeSideImportFallback` (import-TYPE shadowed by a same-named local VALUE —
utilities.ts's `SourceMapSource`; recovered via the ImportSpecifier's
nodeToSymbol-recorded alias), `namespaceAliasMemberSymbol` (dotted
`ts.server.X` chains + the round-479 `import * as NS; export { NS }` barrel
re-publication), and `ternaryBranchType` (a ternary condition narrows a
`??`/`||` branch's LEFT reference — deprecatedCompat's Version idiom). The
full suite then enumerated 36 failures (~11 from the foreign-TP
ambiguous-constrained leg — revert it, re-solve checker.ts:7358 on the
inference side; ~13 corpus multi-file; ~10 of the (c)-era local tests pinning
pre-retire semantics) + harness +5 / server +2 residuals — more than the
session budget, so per protocol the working state is preserved on the branch
with the worklist decomposed into queue sub-items (d)(i)–(v). Suite on main
UNCHANGED (10,316 / 0 / 3). NEXT: (d)(i)–(iv) on the branch, then merge, then
the (v) deletions.

### QUEUE — work top-to-bottom; promote unblockers per protocol

(Restored 2026-07-12, round 481 — the queue/backlog/inventory sections had been
swept into PLAN-PHASE-5-HISTORY.md by an over-eager session-note trim; they are
LIVE structure, not history. v1's offline-verifiable legs LANDED at round 481, so
M5 is now the active arc per the owner directive; the Post-v1 backlog below is the
"any TypeScript project" horizon and stays parked until the owner re-scopes. The
M1–M3 campaign items still unchecked in the history file (M2.2/M2.3/M3.1–M3.4/M1.12)
hit their re-scoped v1 acceptance bar — "the shapes tsc's source uses" — when the
burn-down reached zero real FPs; reviving their full-completeness form is a
backlog-horizon decision, not queue debt.)

**EP — Emit parity (owner-authorized 2026-07-12: "output parity, including reported errors").**
The offline v1 DoD checked emit COMPLETENESS (all files emitted, exit 0) but not
emit-BYTE parity with tsc. The round-483 emit diff (`scripts/emit-diff-tsc.sh`, xtsc
vs npm `tsc@6.0.3` on the `compiler` profile) found 8/78 byte-identical, 70/78
differing — but **none are miscompiles**; xtsc's output is semantically correct and
runnable. Three systematic families explain nearly all changed lines (sequenced
cheap-first to shrink the diff before tackling the hard cross-file one):

- [x] **EP.3 Logical/nullish-assignment downleveling** (`||=`/`&&=`/`??=` below
  ES2021). DONE round 484 (2026-07-12): `Transformer.downlevelLogicalAssignment` —
  `a ||= b` → `a || (a = b)` etc., with side-effecting property/element receivers
  captured into temps (`(_a = obj())[_b = key()] || (_a[_b] = 6)`, tsc-faithful temp
  naming). ~284 sites in the compiler profile. Gated `effectiveTarget < ES2021`;
  corpus has ZERO files exercising these operators so it's pinned by
  `LogicalAssignmentDownlevelTest` only. KNOWN RESIDUAL: a `??=` target BELOW ES2020
  keeps a native `??` (not further downleveled — ES2020 is the tested/dashboard
  target); close when a sub-ES2020 `??=` case appears.
- [ ] **EP.2 Multi-line expression printer formatting.** Match tsc's operator/`:`
  placement (line-end vs line-start) and indentation when wrapping long
  `||`/`&&`/ternary chains. Mechanical Emitter work, no cross-file dependency, but
  HIGHER corpus-regression risk (touches the printer that the green corpus pins) —
  do it with the emit-diff gate in place and verify the full suite after each step.
- [ ] **EP.1 Cross-module const-enum inlining** (highest impact, ~93% of the changed
  lines in files like utilities.js). xtsc inlines SAME-FILE const enums but keeps
  `mod.Enum.Member` for const enums imported across modules; tsc inlines to
  `VALUE /* Enum.Member */` (numeric AND string-valued). Needs the checker to resolve
  imported const-enum values whole-program. Biggest/hardest (cross-file), collapses
  most of the diff. NOTE: xtsc's form still RUNS (preserveConstEnums keeps the enum
  objects) — this is byte-fidelity, not correctness.
- [ ] **EP.0 Wire the emit-diff gate into the dashboard.** `scripts/emit-diff-tsc.sh`
  exists (reports identical/differing + family signals). Ideal reference is a tsc
  BUILT AT THE PINNED COMMIT (npm tsc adds version noise to the small residual tail,
  esp. emitHelpers.js helper bodies); decide whether to build+cache the pinned tsc or
  accept the version-stable family signals. Re-run after EP.2/EP.1 to track the diff
  shrinking.

Session note (round 484) has the full family breakdown + methodology.

**INV — the M5 architecture-inversion arc (re-scoped 2026-07-13, owner; supersedes
M5.1–M5.7 — mapping and full design in `docs/ARCHITECTURE-RETHINK.md`, READ IT FIRST).**
Ground rules for every INV item: corpus suite green + 8-profile FP floors unchanged +
`--listAll` byte-diff empty for behavior-preserving steps + a bench TSV row per landed
item; decompose into the smallest standalone suite-gated commits; micro-opt rounds
against the flat profile are CLOSED (only an INV.0-evidenced ≥5% single lever may
interrupt the arc).

- [x] **INV.0 Instrument the multiplier.** DONE round 491 (2026-07-13):
  `PassTiming.kt` + non-inline `pass(name) {}` around all 514 init dispatch calls +
  the three counters (`getTypeOfExpression` calls/distinct with per-pass attribution,
  `nodeTypes` cacheable/bypassed/hit, depth-0 flow walks at `flowWalkWithTripCheck`),
  behind the `--passTiming` CLI flag; off-mode byte-identical (listAll A/B + wall
  parity) + suite green (+7 local). The table (round-491 session note): checker-init
  = 83% of wall; top-3 passes 38.6% (property-access / assignability / call-types,
  458k of 595k getTypeOfExpression calls, 84k flow walks — 68% from
  checkPropertyAccess); 474 sub-100 ms passes sum 36.5% = the multiplication tail.
  That note's cost-ordered worklist IS the INV.4 migration order.
- [x] **INV.1 Concurrent front-end — the owner's Flow beachhead (owner-approved
  kotlinx-coroutines-core dependency, 2026-07-13).** Sub-steps: (a) DONE round 492 —
  the dep was already in commonMain; landed the `runCompilerPipeline` expect/actual
  seam (JVM `runBlocking`) + the import-graph crawl as a cold sequential Flow
  (`crawlImportGraph`, ProjectCompiler) with the load-bearing emission-order
  contract documented at the seam (suite +3, listAll A/B byte-identical); (b) DONE
  round 493 — read+decode on `Dispatchers.IO` (`pipelineIoDispatcher`
  expect/actual), extraction parse on `Dispatchers.Default`, bounded
  `flatMapMerge(16)` per frontier (`readAndScanBatch`); resolution + emission stay
  sequential per frontier so emission stays first-discovery order (the binder stays
  sequential; parser audited — no shared mutable state); (c) DONE round 493 —
  corpus green (+6 local) + 3× `--listAll` byte-identical vs the (a) binary; (d)
  DONE round 493 — interleaved A/B −0.8 s (~3%) on the compiler profile + bench
  TSV row.
- [x] **INV.1(e) Kill the double parse — reuse the crawl's parses in the core.**
  DONE round 494 (2026-07-13): `computeParserFlags` (the shared single source of
  truth for the option-derived `Parser` flags, used by the core's parse sites AND
  the crawl), `ParsedSource.preParsed` carrying `PreParsedFile(content, flags,
  sourceFile, diagnostics)`, and the core's multi-file site reusing an entry ONLY
  on an exact content+flags match (else re-parse — reuse is a pure optimization).
  Verified: suite +6 (Inv1PreParseReuseTest — sentinel-tree reuse proof + both
  mismatch gates + driver-path counters), `--listAll` byte-identical on compiler
  AND services, reuse fires 78/78 (`--passTiming` counters), interleaved wall A/B
  neutral within noise on both profiles (the parse leg is small next to the
  checker; the point is one canonical tree per file — the INV.2 enabler).
  CLAUDE.md gotcha: a new option-derived Parser argument must extend
  `ParserFlags`, never a parse site inline, or the match reuses a wrong tree.
- [x] **INV.2 Bind the world** — COMPLETE round 499 (all four sub-items landed;
  the tables' mass consumption is INV.4's migration). Decomposed round 494
  (facts verified in-code:
  `Node` is a sealed interface + ~138 data classes with single-interface supertypes
  `) : Expression/Node/TypeNode/Statement/Declaration/ClassElement`; there is NO
  generic child-walk anywhere; nodes have no parent/id fields; `Symbol.id` is a
  GLOBAL companion `nextId++` (Types.kt:116–127, the ~350-test reshuffle anchor);
  `nodeKey` is the cross-file-colliding `(pos<<32)|end`). Work the sub-items in
  order, one commit each:
  - [x] **INV.2(a) AST identity foundations.** DONE round 495 (2026-07-13):
    `NodeBase` (nodeId/parent, NOT implementing Node — preserves sealed-`when`
    exhaustiveness) + 138 supertype edits + `SourceFile.nodeCount`; canonical
    `forEachChild` (exhaustive sealed `when`) + iterative preorder
    `indexSourceFile` hooked into `Parser.parse()`. Pinned by the jvmTest
    reflection oracle (`ForEachChildOracleTest` — componentN diff over fixtures +
    all 78 real tsc sources) + `Inv2NodeIndexTest` (dense preorder / parent
    chains / copy-unindexed / 30k-chain-on-plain-thread). Suite +10 (10,228),
    `--listAll` byte-identical, wall neutral. Gotchas: NodeBase LUB trap +
    power-assert node-toString trap.
  - [x] **INV.2(b) Pilot consumer.** DONE round 496 (2026-07-13):
    `FlowGraph.flowAt` — nodeId arrays pre-computed from the finished map
    (preserves the nodeKey extent-ALIASING) + identity ownership check
    (synthesized/foreign nodes take the legacy path); 5 checker sites migrated;
    suite +3, listAll byte-identical, wall neutral. JFR verdict: getNode ≈6.7%
    of samples but nodeToFlow only ~4% of that slice (~0.3% wall) — mechanism
    validated; the mass-migration targets are the HOT maps (walk memos, INV.4
    per-node type cache), not more cold tables. `nodeTypes` rejected as pilot
    (structural cross-file keying — INV.5 territory).
  - [x] **INV.2(c) Full lexical binding, additive.** Scope symbols from a SEPARATE
    id space (never the global `nextId` sequence — the reshuffle hazard); existing
    `locals`/`globals` byte-unchanged; new tables unconsumed until INV.4.
    - (i) DONE round 497 (2026-07-13): function-like containers —
      `bindLexicalScopes` (Binder.kt) walks the whole tree iteratively after
      conventional binding, building per-nodeId `LexicalScope`s
      (`BinderResult.lexicalScopes`): SourceFile root aliases file locals,
      ModuleDeclaration aliases the merged exports (chained per dotted segment,
      the B512 rule), the 7 function-like kinds + static blocks get fresh tables
      (type params, params minus `this`, fn-expr self-name, body-top-level
      decls, `var`s hoisted from any block depth). `Symbol.scopeSymbol` mints
      ids ≤ −2; a delta-probe test pins zero global-id consumption. Suite +14
      (Inv2LexicalScopeTest), listAll byte-identical, interleaved wall
      position-balanced +0.8% (noise band).
    - (ii) DONE round 498 (2026-07-13, same session): block-scope containers —
      every Block that is not a function-like's immediate body, for/for-in/for-of
      headers, CatchClause (binds the catch variable, destructuring included),
      SwitchStatement standing in for tsc's CaseBlock (our AST has none — the
      switch EXPRESSION routes to the OUTER scope by hand) — plus class scopes
      (type params; named class-expression self-name; class decorators outer),
      interface/type-alias scopes (type params), and enum scopes (aliasing
      main-bound exports; nested enums bind scope-space members also published
      on the scope symbol's exports, gated `id ≤ −2` so main symbols stay
      untouched). Design dividend: the phase-(i) `isDirectBodyChild` gates for
      block-scoped declarations DISSOLVE into `scope.existing == null` (every
      fresh scope IS the correct nearest block-scope container); `var` gains the
      real `varHoistTarget` walk-up. Block-nested function declarations use
      strict/module semantics (bind to the block). Suite +6 (20 total in
      Inv2LexicalScopeTest — the phase-(i) negative controls flipped to
      positive location asserts), listAll byte-identical, interleaved wall ×6
      both orders neutral.
  - [x] **INV.2(d) B83.5 dissolution pilots.** DONE round 499 (2026-07-13): the
    canonical site — `checkPropertyAccessInStatement`'s ClassDeclaration branch —
    now resolves a block-scoped class via `lexicalScopeSymbol` (parent-chain walk
    over `currentLexicalScopes`, set per file in `checkPropertyAccess`; legacy
    transient synthesis kept as the unindexed-tree fallback). Fidelity proven:
    suite green, listAll byte-identical on compiler AND services; and the pilot
    FIXES a real FP — a block-level `interface B` + `class B` merge now
    contributes interface members to `this` (the transient class-only symbol
    could not see them; measured: the pre-pilot checker emitted a false TS2339).
    Candidate analysis: the other two `Symbol(SymbolFlags.Class, …)` syntheses
    are NOT B83.5 scope-binding shapes and stay — the B511 clodule recovery
    (the class symbol is main-bound then OVERWRITTEN by last-wins, so it is in
    neither table) and the classExpressionAssignment display synthesis (a
    ClassExpression is never a scope binding). Mass consumption of the tables
    (the ~59 synthesis sites, `buildNestedFunctionMap`, the per-pass scope
    machinery) is INV.4's migration proper.
- [ ] **INV.3 Per-file scoping** — decomposed round 500 (facts verified in-code:
  `perFileScope` EXISTS and is already consumed at 4 sites — the 17.32b–e flips
  (TS2663-vs-TS2301, TS2552 candidate pool, resolveExpressionToSymbol, file-root
  TS2304) — so the earlier "never consumed" note was stale; the remaining
  migration surface is ~400 keyed `globals` consults; import aliases free-ride on
  the conflation because the general `resolveAlias` cannot follow ESM-`.js`
  specifiers / `export *` barrels / NamespaceImports — the FLOW-ONLY resolvers
  can, and the general-fallback variant measured a TS2315×466 flood at round
  409). End state: module files resolve own-locals + imports + true globals;
  the `mergeSymbolTable` conflation is retired for module files; the conflation
  ecology is deleted. Also lays the cross-file value-resolution groundwork EP.1
  needs. Work the sub-items in order, one commit each:
  - [x] **INV.3(a) Instrument the conflation dependency.** DONE round 500
    (2026-07-13): `globals` constructed as `InstrumentedSymbolTable` under
    `--passTiming` (plain map otherwise — zero added code on the hottest map);
    every keyed lookup classified against the per-file visibility model
    (TRUE_GLOBAL / SHARED / OWN_LOCAL / CONFLATED / UNSCOPED — see
    `GlobalsLookupClass`) by a classifier installed after init step 1b, with
    per-name + per-pass conflated/unscoped tables in the dump. Measured
    (compiler / services profiles): 2.71M / 4.92M keyed lookups — 71% / 79%
    MISSES (globals probed as a maybe-fallback everywhere), ownLocal
    530k/703k (flips to per-file trivially), CONFLATED 157k/217k concentrated
    in 608/845 names (almost all `types.ts` type names reached through barrel
    imports; services adds the round-442 value-space leaks `parent`/`error`)
    and 14–15 passes with the top 3 = 95–96% of conflated traffic = INV.0's
    top-3 wall passes (checkPropertyAccess / checkCallExpressionTypes /
    checkTypeAssignability), SHARED only 2.9k/4.0k (the chimera ecology's
    cost is per-lookup bail checks, not hit volume), unscoped 71.8k/97.1k
    (checkUnresolvedNames + outside-dispatch). Worklist: (b)'s primitive must
    resolve barrel-imported TYPE names; (c) starts at the three hot passes.
    Suite +5 (Inv3GlobalsLookupTest), `--listAll` byte-identical (off-mode),
    bench row in band.
  - [x] **INV.3(b) Per-file resolution primitive.** COMPLETE round 502:
    - (i) DONE round 501 (2026-07-13): `lookupPerFile(fileName, name)`
      (internal, unconsumed by checker paths) — perFileScope lookup with an
      ImportSpecifier-alias local resolved onward through
      `resolveImportedSymbolGeneral` (the kind-AGNOSTIC generalization of the
      flow-only resolver skeleton: ESM-`.js` strip + `export *` barrels +
      renamed re-exports via the star walk's NamedExports arm + re-import
      hops; memoized `importedSymbolGeneralCache`; ADDITIVE — the three
      kind-specific legacy variants stay untouched, their per-decl
      kind-filter-then-continue semantics differ; never wired into
      `resolveAlias` per the round-409 flood gotcha). KEY TRAP hit and
      pinned: mergeSymbolTable FLAG pollution means an Alias flag cannot
      identify an import alias — a barrel-imported name's TARGET symbol
      acquires the Alias bit from the importing file's merge, so the hop
      test must be declaration-based (`isImportBindingDecl` — the
      isValueExport gotcha applied to alias hopping). Degradations
      documented in the KDoc: unresolvable import / default-import /
      `import * as ns` / `import =` aliases return the alias symbol itself
      (callers keep their existing handling — extend when a (c) flip needs
      them); null strictly means "no per-file meaning" (the conflation
      leak). Pinned by Inv3PerFileLookupTest (direct
      `Checker(options, binderResults)` construction — a first for local
      tests — asserting symbol IDENTITY with the declaring file's binder
      locals across direct-`.js`/barrel/renamed-re-export/own-local/
      script-global/lib shapes + the foreign-module-local null and
      alias-degradation negative controls).
    - (ii) DONE round 502 (2026-07-13): pilot consumer — the TS2315/TS2346
      heritage-base "not generic" gate (`checkTypeArgumentConstraints`, the
      smallest nonzero pass in the (a) conflated-by-pass table with DIRECT
      pass-local consults) resolves through the NEW
      `globalsForFile(fileName, name)`, THE (c) flip shape: return the
      merged-globals INSTANCE whenever the name has a per-file meaning (a
      non-module-only name, or a module-only name the file declares/imports
      — probed via `lookupPerFile`; substituting the primitive's return
      directly would change symbol identity for lib/script names), null
      exactly where the legacy consult leaked a foreign module file's local
      (suppression-only at this site: real tsc never emits TS2315 for an
      unresolvable base). Supporting infra always-on: init 1b2 became
      `computePerFileVisibility` — publishes `moduleOnlyGlobalNames`
      (module-file local names minus lib/script/augmentation-visible), the
      INV.3(a) classifier installs on top of the same sets. Both mirrored
      consult sites flipped together (kept-in-sync contract); the conflated
      branch never touches `globals`, so the `--passTiming` conflated
      tables keep measuring only UN-migrated traffic. MEASUREMENT LESSON
      for (c): the post-flip instrumented run shows the pass STILL at 11
      conflated with the total lookup count EXACTLY unchanged (2,711,601)
      — the pass's conflated traffic comes from DEEPER shared machinery
      (`checkConstraintsForTypeArgs` → `getTypeFromTypeNode`), not the
      direct pass-local consults, which measured ZERO conflated hits on
      the compiler profile. Per-PASS attribution ≠ per-SITE: a hot-pass
      (c) flip needs per-site reasoning about which consults inside the
      pass actually carry the conflated traffic. Suite +7
      (Inv3GlobalsForFileTest — both leak-kill tests FAIL on the pre-flip
      checker, verified via stash; five preservation controls pass on
      both); `--listAll` byte-identical on compiler AND services.
  - [x] **INV.3(c) Flip resolution families onto the primitive** — COMPLETE
    round 509 (all four sub-items landed; conflated 157k → 917, the residue
    being the INV.3(d)-scoped shadow ecology). Decomposed
    round 503 from a MEASURED per-site attribution (a temporary 1:200
    stack-sampling probe on the classifier's CONFLATED branch, ~790 samples,
    probe reverted — evidence in the round-503 session note). The guessed
    site list above was WRONG: `getTypeFromTypeReference`'s globals fallback
    measured ZERO conflated hits and `resolveTypeNameToSymbol`'s Identifier
    entry only ~1.2% — the actual distribution:
    **~82% is ONE family, the enum-discriminant/kind-domain narrowing
    machinery** (`kindDomainKeysFromTypeNode` → `enumSwitchKeysFromTypeNode` /
    `enumMemberKeysOfTypeNode` / `kindDomainTypeDeclSymbol` /
    `resolveEnumSymbolForDiscriminant`, reached from `narrowByCallPredicate`
    via `applyConditionNarrowing`, plus smaller entries from
    `filterUnionByEnumDiscriminant`/`resolveCallOverload`), which resolves
    type names read from FOREIGN AST nodes — types.ts's union-member `.kind`
    annotations — while `currentFileLocals` points at the CHECKING file
    (exactly the top conflated names: JSDocFunctionType / FunctionTypeNode /
    ConstructorTypeNode / MappedTypeNode / ConditionalTypeNode). The
    per-file-correct key there is the NODE'S OWNING FILE (tsc semantics: a
    types.ts annotation resolves in types.ts's scope), NOT
    `currentCheckFileName` — a naive `globalsForFile(currentCheckFileName,…)`
    flip would silently kill narrowing in files that don't import the name.
    The rest: `identifier.fallback` ~3.8k + `propAccess.objExpr` ~3k (tagged
    counts), `checkPrivateMemberAccess`, `getTypeOfIdentifier ←
    isCalleeResolvable`, `resolveFlowCalleeDecl ←
    flowCalleeMayHaveAssertEffects`, `computeRawTypeOfPropertyAccess ←
    getCalleeType`, `typeNodeDefinitelyNonNullish`, `pmrCheckAccess`,
    `mam.objectExpr`/`mam.recvSym` (~63 each). Sub-items, one commit each,
    every flip suite+listAll-gated on compiler AND services:
    - (i) DONE round 504 (2026-07-13): the node-keyed resolution primitive —
      `owningSourceFile(node)` (NodeWalk.kt: parent-chain walk to the
      SourceFile, null for unindexed `copy()`/synthesized/detached nodes,
      defensive hop bound) + `lookupPerFileForNode(node, name)` =
      `globalsForFile(owner.fileName, name)` with legacy-merged-consult
      degradation for ownerless nodes. Additive/unconsumed; pinned by
      Inv3NodeKeyedLookupTest (direct construction — a foreign-node
      annotation resolves under its OWNING file's visibility to the same
      merged instance; an owner without the name yields null (the leak);
      an importing owner keeps resolving; an unindexed copy degrades to
      legacy; lib names never nulled).
    - (ii) DONE round 505 (2026-07-13): the kind-domain/enum-discriminant
      family (~82% of conflated traffic) flipped onto the node-keyed
      primitive — `resolveEnumSymbolForDiscriminant`/`kindDomainTypeDeclSymbol`
      thread a `keyNode` (all 5 call sites), and the alias fallbacks in
      `enumSwitchKeysFromTypeNode`/`enumMemberKeysOfTypeNode` (incl. the
      round-477 import-alias fallback) consult `lookupPerFileForNode(node,
      name)`; `currentFileLocals` stays the first consult everywhere.
      Companion: `globalsForFile`'s proven-visible branch reads UNCLASSIFIED
      (`InstrumentedSymbolTable.getUnclassified`) under `--passTiming`, so a
      legitimate foreign-node hit — CONFLATED against the CHECKING file's
      locals — no longer pollutes the migration tables. Suite +5
      (Inv3KindDomainNodeKeyTest — leak-kill FAILS pre-flip via stash;
      4 preservation controls pass both sides); listAll byte-identical on
      compiler AND services.
    - (iii) **Flip the current-file-keyed value/callee sites** — these read
      names from the CURRENT file's own AST; node-keying by the name's
      IDENTIFIER node is the uniform shape (equals current-file keying for
      own nodes); suppression-only where the name classifies conflated.
      Phase 1 DONE round 506 (2026-07-13): the protected-member cluster
      (pw/pmr/pm, TS2445/TS2446 — `pmrCheckAccess`'s static consult, the
      ctor-init consult, and the `pwResolveClass`/`pmrResolveClass` funnels
      every heritage walker feeds) keys by the name Identifier via
      `lookupPerFileForNode` — the heritage walkers wrap a REAL indexed
      Identifier in a synthesized TypeReference, so keying by `typeName`
      (never the wrapper) needs zero signature changes; a fully-synthesized
      identifier (pmrLocalClass's from-text one) degrades to the legacy
      consult inside the primitive. Suite +5 (Inv3ProtectedNodeKeyTest —
      both leak-kill tests FAIL pre-flip via stash: the leaked resolution
      manufactured bogus TS2445 about a class the file never imports);
      listAll byte-identical on compiler AND services. Phase 2 DONE round
      507 (2026-07-13): the bare-Identifier VALUE/receiver/callee cluster —
      checkPrivateMemberAccess, getCalleeType's Identifier branch,
      resolveFlowCalleeDecl (+ the extracted currentFileNestedPredicateDecl
      preserving round-471 narrowing from the direct==null fallback too),
      resolveNamespaceMemberFnDecl, the three ns-fallback receiver
      resolvers (computeRawTypeOfPropertyAccess /
      resolvePropertyAccessToSymbol / propertyAccessChainIsNamespaceQualified),
      isCalleeResolvable, checkPropertyAccessAssignment's ns base, the two
      mam receiver consults, and the protected-ctor heritage walks
      (findEffectiveConstructorVisibility/classExtendsOrIs) — all keyed by
      the name's own Identifier node. Conflated 20,941 → 10,034 (−52%);
      suite +9 (Inv3ValueCalleeNodeKeyTest — 4 leak-kills FAIL pre-flip via
      stash); listAll byte-identical on compiler AND services; bench in
      band. Phase 3 DONE round 507b (2026-07-13) — (iii) COMPLETE:
      `getTypeOfIdentifier`'s globals fallback node-keyed (the round-442
      by-NAME dead-end does NOT reproduce per-FILE — imports resolve
      through the visibility probe to the same merged instance; pinned by
      Inv3IdentifierTypingNodeKeyTest incl. the import-driven
      initializer-inference control from the round-442 regression family),
      plus a fast path in `lookupPerFileForNode` (non-module-only names
      skip the parent walk — the fallback is ~2M calls/compile). Conflated
      10,034 → 6,165 (cumulative 20,941 → 6,165, −71%); `factory` gone;
      checkImplicitAnyParameters 2,608 → 171, checkUncalledFunctions 968 →
      189. Suite green 10,298 → 10,302 (+4); listAll byte-identical on
      compiler AND services; bench +4.0% single-run = the documented
      box-drift band (~126k parent walks ≈ negligible by construction).
      Residue ~6.2k = the (iv) type-position tail (types.ts type names
      reached via typeNodeDefinitelyNonNullish / resolveTypeNameToSymbol /
      getTypeFromBaseTypeExpression) + ~500 value-name lookups in the
      shadow-detection ecology (registerNestedGlobalShadow*/
      applyBodyLocalShadowing/shadowNestedFunctionNames ask "does a merged
      global collide" — they die with INV.3(d), do not flip them) + tiny
      tail sites (emitTs2345ForBareTpArgToConstrainedTpParam,
      getOverloadImplementationRelated, calleeReturnAnnotationForImplicitAny
      — fold into (iv)'s re-measure).
    - (iv) **Flip the type-position tail**. Leg 1 DONE round 508 (2026-07-13):
      `resolveTypeNameToSymbol`'s Identifier branch + `typeNodeDefinitelyNonNullish`'s
      two fallbacks flipped JOINTLY per the round-507c order constraint, with
      the two call-site trailing `?: globals[name]` fallbacks
      (`getTypeFromTypeReference`, `checkConstraintsInTypeNode`'s TS2315
      emitter) gated to QualifiedName — for Identifier names they were
      byte-redundant pre-flip and would silently RE-LEAK the node-keyed null
      post-flip (the trap now in the CLAUDE.md INV.3(c) entry). The full
      suite caught a REAL visibility gap the flip exposed:
      `lookupPerFileForNode` now grants a node inside a `declare module
      "<relative-spec>"` AUGMENTATION block the augmented module's direct
      named exports (the round-443 rule; the innermost string-named
      ModuleDeclaration is captured during the parent walk, unclassified
      under --passTiming) — without it the flip nulled `UnionType` inside
      services-style `declare module "./types.js"` blocks and this-predicate
      narrowing died (ThisPredicateNarrowingTest's augmentation pin).
      Test-design lesson: the ADDITIVE leak-kill direction is SHADOWED by
      any-degradation (an unresolvable callee annotation degrades the
      assigned reference to `any` — proven with a never-declared `Zorp`
      control — masking the TS18048/TS2322 consumers), so the flow
      observable uses the SUPPRESSION direction: a foreign UNIMPORTED
      NULLABLE alias return-annotation pre-flip types the reference as the
      leaked union and manufactures TS18048 on a closure-captured read;
      post-flip it degrades to any and the leaked TS18048 dies (tsc-faithful).
      Suite +9 (Inv3TypePositionNodeKeyTest — 3 leak-kills FAIL pre-flip via
      stash: the flow TS18048, annotation-position TS2322, TS2315; 6
      preservation controls pass both sides); `--listAll` byte-identical on
      compiler AND services. Leg 2 DONE round 509 (2026-07-13) — **(iv) and
      the whole (c) migration COMPLETE**: getTypeFromBaseTypeExpression's
      Identifier fallback (PropertyAccess last-segment fallback kept legacy —
      the QualifiedName convention), emitTs2345ForBareTpArgToConstrainedTpParam,
      getOverloadImplementationRelated (keyed by the overload DECL's own name
      node — a nested/foreign collision no longer hands TS2793 a wrong-file
      impl pointer), calleeReturnAnnotationForImplicitAny (the
      uniqueFunctionDeclByName fallback still covers program-wide-unique
      names). Suite +5 (Inv3TypePositionLeg2NodeKeyTest — 2 leak-kills FAIL
      pre-flip via stash: a leaked foreign heritage base grafting members
      manufactured TS2741 on `const d: D = {}`, a leaked foreign
      constrained-TP callee manufactured TS2345; 3 preservation controls);
      listAll byte-identical on compiler AND services. RE-MEASURE (compiler
      profile): CONFLATED 6,165 → **917** (−85%; from the pre-migration 157k
      → −99.4%), 97 names / 9 passes, top 318/284/273 — the residue is the
      deliberately-legacy shadow-detection ecology (`diag`/`clone`/`map`/
      `factory` collision questions) + tiny tails, i.e. INV.3(d)'s scope.
      INV.3(d) is UNLOCKED.
  - [ ] **INV.3(d) Retire the merge + delete the ecology.** Stop merging
    module-file locals into `globals`; delete `moduleFileLocalVarNames`,
    `conflatedTypeAliasFiles`, `conflatedInterfaceFiles`,
    `conflatedEnumFileSubsets`, the per-file interface views, and the chimera
    bails — walker-by-walker, each deletion suite- and listAll-gated (each
    removes hot-path work from `checkMemberAccessMissing`).
    **THE RETIRE IS MERGED TO MAIN (round 512): sub-items (i)–(iv) all DONE —
    suite fully green (10,346/0/3) and ALL 8 profiles byte-identical to the
    pre-retire baselines. Remaining: (v) the ecology deletions (the round-473
    Identifier dispatch is already deleted as the (iv) residual fix — its
    removal is what restored the server/harness baselines).** What the branch
    proved (measured round 510): the retire
    must be STAGED BY NAME CLASS — retire only MODULE-ONLY names; SHARED names
    (module local colliding with a lib/script global: `Symbol`/`Node`/
    `Performance` riding the lib names) must KEEP merging until every lib-name
    consumer resolves per-file (the naive full retire measured 861 compiler
    FPs, the module-only cut 34, each traced to an unflipped consult by the
    classifier-MISS stack-probe technique). Sub-items to finish it, in order:
    - (i) DONE round 511 (2026-07-14): the ambiguous-constrained→foreign leg
      REVERTED (declaration-IDENTITY leg kept) — flipped the whole TP family
      (17 tests: the 8 corpus TP pins + 3 local negative controls +
      tsxTypeArgumentPartialDefinitionStillErrors ×2 + WhileTrueDefiniteAssignTest
      ×4, the last two collateral of the over-aggressive classification);
      checker.ts:7358 re-solved at the INFERENCE side —
      `tryInferSingleTypeParamFromArgs` soft-skips a CallExpression arg whose
      type still carries a TypeParam at forReturnType sites (tpSawAnyArg →
      anyType, the pre-retire any-degradation behavior; round-468
      CallExpression gate keeps own-TP identifier args anchoring). Pinned by
      ForeignTpInferenceSoftSkipTest (6); compiler+services listAll
      byte-identical.
    - (ii) DONE round 512 — all 14 corpus multi-file failures fixed (the last 6:
      union-discriminant objlit drill node-keyed; ns-import static TS2339 +
      the dir-relative resolveAlias legs; TS2749 file-keyed with the
      typeSideImportFallback gate; the B585 contextual-display hops; the JSDoc
      ImportType own-specifier resolution; the TS2415 imported-base flip).
      Round-511 record follows:
      heritage/implements walkers node-keyed (interfaceDeclaration3,
      interfaceImplementation6 — incl. the B563 ownership-gate mirror that
      killed the double TS2420), checkConstraintsForTypeArgs keyNode +
      ImportType presetSymbol (divergentAccessorsTypes6,
      unmetTypeConstraintInImportCall), checkTypeNameResolved's leftSym →
      globalsForFile (augmentExportEquals1/2 + decoratorMetadataWithImport…7),
      the mam type-only-winner + namespace-import value-side bail
      (noCrashOnImportShadowing), **and the session's critical find: the
      import hop (`resolveImportedSymbolGeneral`) lacked the DIR-RELATIVE
      resolver leg, so path-shaped extensionless imports (`/proj/src/f1.ts` →
      `./lib`) never hopped and EVERY import-mediated type died on real
      on-disk projects — masked pre-retire by the merge, invisible to the
      `.js`-specifier tsc profiles; found via the EnclosingImportIndexTest
      pins + a MainKt scratch-repro matrix.** REMAINING 6 (per-test roots,
      each needs a probe dig): exportStarFromEmptyModule (X.A.r static
      TS2339 through a local-shadowed star chain),
      allowImportClausesToMergeWithTypes (TS2749 default-import-of-value used
      as type), allowJscheckJsTypeParameterNoCrash (display regression:
      `WatchHandler<any>` unfolds to the fn-type — alias display lost),
      checkJsdocTypeTagOnExportAssignment2 (JS `@type import("./a").Foo`
      excess-prop TS2353 — the JSDoc path's cross-file resolution),
      declarationEmitPrivateSymbolCausesVarDeclarationEmit2 (TS2415 with
      cross-file computed `[x]` private members),
      indirectDiscriminantAndExcessProperty (single-file module: TS2322
      member-vs-discriminant `"foo" | "bar"` — the objlit-member drill's
      resolution; NOT tryEmitObjectVsNamedUnionArg, whose anonymous
      constituents defer to the discriminant walker).
    - (iii) DONE round 512 — the last 4 were 2 real resolver gaps (the
      `export * as` arm in namespaceAliasMemberSymbol; the ns-member objlit ctx
      flips) + 2 pre-retire ACCIDENTAL PASSES fixed tsc-faithfully (all-missing
      all-anonymous union TS2339; primitive-vs-plain-object-bag TS2345).
      Round-511 record follows:
      (Inv3NodeKeyedLookupTest's unindexed-copy degradation → null for
      module-only names; Inv3GlobalsLookupTest's leak assertions inverted to
      the emptied-worklist victory condition); 3 more of the original 9
      flipped as REAL code fixes (EnclosingImportIndexTest ×2 +
      Inv3NodeKeyedLookupTest imports-keep-resolving via the dir-relative hop
      leg; ExtendsImplementsSameClassTest + NamespaceImportQualifiedTypeTest
      via the (ii) walker flips). REMAINING 4, all look like REAL
      suppressions to dig (scratch repros r7/r8 reproduce two):
      ConflatedTypeAliasLeakTest ×2 (own-file `type X` union TS2339 /
      own-file TS2345 both silent — receiver/param resolution in the alias's
      own file returns something unexpected post-retire),
      NamespaceQualifiedBaseInheritanceTest (export-star-as barrel base →
      TS2339 FP returned), BuilderChainAndNsMemberCtxTest (ns-member objlit
      contextual params → TS7006 FP returned).
    - (iv) DONE round 512 — all three residual families closed: deprecate.ts
      `compareTo` (an anyType shadow now BAILS mam instead of falling through
      to the outer import); session.ts protocol.Diagnostic (the round-473
      Identifier DISPATCH into conflatedPerFileInterfaceType REMOVED — the
      first (v) deletion, see the session note); fourslashImpl `'array'`
      (namedUnionMemberCouldAcceptArray hops import aliases). **Full 8-profile
      listAll A/B vs pre-retire main: ALL BYTE-IDENTICAL**; suite fully green;
      branch merged to main.
    - (v) DONE round 513 — ALL FOUR deletion groups landed (each suite- and
      8-profile-listAll-gated byte-identical): `moduleFileLocalVarNames` (+2
      masked narrowing gaps fixed), `conflatedTypeAliasFiles` (2 helpers
      re-keyed onto non-conflation conditions), `conflatedInterfaceFiles`
      objlit/relation chimera bails + TS2430/heritage view arms, and the
      per-file-view core (`conflatedPerFileInterfaceType`/`perFileInterfaceType`/
      owner-context threading) + `conflatedEnumFileSubsets`. SURVIVORS
      (deliberate): `moduleInterfaceNames`+`isLibPhantomMemberOfModuleInterface`
      (lib+module SHARED merges persist), `interfaceDeclsForCurrentFileView`
      discriminant reading, the re-keyed augmentation/alias-union bridges, the
      `A && objlit` falsy-remainder emitter, and the `nodeTypes` bypass re-keyed
      as `isPerFileDependentRefNode` on `multiFileModuleTypeNames` (the
      structural cache's cross-file position collisions are NOT
      conflation-specific — see the session note). **INV.3(d) is COMPLETE; the
      INV.3 arc is COMPLETE. NEXT: INV.4.**
- [ ] **INV.4 Single-pass check spine.** `checkSourceFileOnce` per-node dispatch;
  migrate walker families in INV.0's cost order — every migration deletes a full-tree
  pass and its private scope machinery. Once ONE authoritative walk state exists, land
  the two things that are unsound today: a per-node expression-type cache, and flow
  narrowing folded into reference typing once (collapsing the rounds-408–479
  per-consumer wiring). Decomposed round 514. Cross-cutting rules for every
  sub-item: (1) the spine is dispatched as ONE `pass("checkSpine")` at a FIXED
  init position (the earliest migrated pass's slot); passes migrating in from
  LATER positions move their emissions earlier in insertion order — the stable
  diagnostic sort (start→length→code→message) hides all but exact 4-tuple ties,
  and the per-migration corpus + listAll gates decide each case. (2) A spine
  handler sees ALL nodes: a hand-walk's accidental under-visits (arrow bodies,
  class/function expressions, initializers) become visits — per migrated pass,
  decide widen-vs-gate by the CLAUDE.md emission-direction rule (a
  position-independent tsc grammar rule widens faithfully; an FP-firewalled
  heuristic walker must reproduce its descent gates via parent-chain checks).
  (3) Every migrated pass with no local pins gets them BEFORE migration (the
  corpus pins emit bytes, not checker diagnostics — `.errors.txt` is disabled,
  so local tests are the primary under-emission gate). (4) Suite green +
  8-profile listAll + bench row per landed commit.
  - [x] **INV.4(a) Spine skeleton + pilot migration.** DONE round 514
    (2026-07-14): `checkSpine()` at the old checkAccessorModifierTarget slot —
    iterative enter/leave preorder walk per file (explicit parallel stacks;
    10k-chain pinned), per-file spine context fields declared BEFORE `init`,
    per-node `when` dispatch in `spineEnterNode`/`spineLeaveNode` (tsc
    checkSourceElement-style; plain private handler funs), active-handler
    gate skips the walk when every migrated handler is off (the profiles
    target ES2020 → pilot handler off → bench-neutral by construction).
    Pilot: TS18045 migrated — threaded `inAmbient` became an INV.2
    parent-chain ancestry check ([spineInAmbientContext]); the 78-line
    private walk deleted; coverage widened faithfully to class expressions /
    arrow bodies (position-independent grammar rule; both directions pinned).
    Suite +9 (Inv4SpineAccessorModifierTest), listAll byte-identical on
    compiler AND services (46/46; header-only argv difference), bench row in
    band. The leave hook is the scope-pop extension point — its pairing gets
    its first real pin when the first stateful migration lands.
  - [ ] **INV.4(b) Tail-pass batches.** Migrate the 474-pass sub-100 ms tail
    (7.3 s = 36.5% of checker-init, round-491 table) in batches of ~5–15 per
    commit, most-mechanical first (zero-typing grammar/AST-shape walkers with
    per-file prepasses moving to a file-enter hook); each batch deletes its
    walks. Re-measure `--passTiming` every few batches; stop batching a shape
    that resists (stateful scope machinery) and queue it for (c)/(d) instead.
    Batch 1 DONE round 514 (2026-07-14): checkInvalidGlobalAugmentations
    (TS2669/TS2670) + checkReservedWordInterfaceParams (TS7051/TS7006) —
    both old walks descended ONLY through module bodies, so reachability is
    reproduced as a module-chain parent-walk gate (the template for
    module-scope-only walkers); the reserved-params handler deliberately does
    NOT widen to function/class-nested interfaces (a behavior change to make
    on a signal, not as a migration side effect); currentFileLocals is now
    set per file in checkSpine's loop (isTypeLikeParamName consults it); the
    spine walk is ALWAYS-ON from this batch (the TS2669 handler is
    unconditional and covers .d.ts — the .d.ts fast-skip lifted into
    per-handler gates). Suite +10 (Inv4SpineBatch1Test), listAll
    byte-identical on compiler AND services. WALK-COST measurement
    (interleaved 3-pair A/B vs the pre-batch binary — the round-493 rule): the
    first-cut enter/leave walk cost a REAL +1.0 s median on the compiler
    profile (boxing ArrayList<Boolean> phase stack + a leave frame per LEAF);
    fixed same commit — primitive BooleanArray phase stack + leaf shortcut
    (leave fires inline for childless nodes, no re-push) → re-interleaved
    NEUTRAL within noise (pair deltas +861/−1063/+574 ms, mean +124 ms).
    Per-frame costs are the whole game in a walk that visits every node —
    the walk KDoc carries the warning. Batch 2 DONE round 515 (2026-07-14):
    checkNonArrayRestParameters (TS2370 — the two differently-shaped walks
    became ONE Parameter-enter handler dispatching on the parameter's PARENT
    kind: value-position parents get the keyword rule, type-position parents
    the optional-rest rule; both widened faithfully — position-independent
    per-signature grammar) + checkIteratorMethodExtraParameters
    (TS2488/TS2504) + checkAsyncYieldStarThenable (TS1320) — the prepass
    pair became spine COLLECTION (VariableDeclaration enter, VariableStatement
    parent gate) plus BUFFERED iteration positions/yield* candidates resolved
    at file END (spineResolveDeferredIterationChecks — preserves the old
    prepasses' use-before-decl semantics with NO extra walk; the template for
    collect-then-scan walkers). TS1320's statement-level-only reachability
    widened to a nearest-function-ancestor async-generator gate. 16 walker
    funs deleted (~460 lines), 3 init slots removed. Suite +21
    (Inv4SpineBatch2Test), listAll error lines identical on ALL 8 profiles,
    wall in band. Batch 3 DONE same round: checkForOfNonIterable (TS2495 —
    the per-run lib-exclusion gate became spineForOfNonIterableActive; the
    verdict helper checkForOfExprNonIterable retained unchanged) +
    checkAbstractAccessorReturnTypes (TS7033 — GetAccessor-enter handler;
    the ClassDeclaration-parent gate keeps class-EXPRESSION members
    unchecked; the `.js`/`.jsx` skip is deliberately NOT spineIsJsLike —
    the old pass ran on .mjs/.cjs); 6 more walker funs + the round-514
    orphaned TS18045 KDoc deleted. Suite +9 (Inv4SpineBatch3Test), listAll
    identical on ALL 8 profiles. Batch 4 DONE round 516 (2026-07-14):
    checkSetterParameterCount (TS1054/TS1049/TS1095 as Get/SetAccessor-enter
    handlers — TS1054/TS1049 widened faithfully to class expressions +
    interface/type-literal accessors, TS1095 widened exactly to class
    expressions (the objlit/interface parses never store a setter return
    annotation); TS2808 as a ClassDeclaration-enter pair check KEPT at the
    old ClassDeclaration-only gate) + checkRestParameterLast (TS1014 — a
    second Parameter-enter handler; widened to FunctionType/ConstructorType/
    type-literal methods per tsc checkGrammarParameterList; GetAccessor
    parents stay excluded) + checkMultipleDefaults (TS1113 —
    SwitchStatement-enter, one-per-switch latch preserved) +
    checkInterfacePropertyInitializers (TS1246 — InterfaceDeclaration-enter;
    the parser owns the common shape). 17 walker funs (~733 lines) deleted,
    4 init slots removed. Suite +22 (Inv4SpineBatch4Test), listAll identical
    on ALL 8 profiles, bench in band. Batch 5 DONE round 516 (same session):
    checkConstWithoutInitializer (TS1155) + checkDestructuringWithoutInitializer
    (TS1182/TS7031) as VariableDeclaration-enter handlers — shared owner gate
    (VariableStatement non-declare/non-ambient via spineInDeclareModuleChain,
    the parent-walk equivalent of the old isAmbient threading which reset at
    every non-module descent; or a for(;;) initializer; for-in/for-of
    excluded); emitTs1182IfMissingInit retained; for-of/for-in BODIES are a
    faithful widening (the old walks had no ForOf/ForIn case). Plus
    checkComputedPropertyNameLiteral (TS1166/TS1169 by PropertyDeclaration
    parent kind; TypeLiteral stays unchecked) + spineCheckClassExprComputedProps
    (the TS1206 legacy-decorator short-circuit, position-GATED to the old
    expression-statement-only reach — pinned negative). 7 walker funs
    (~318 lines) deleted, 3 init slots removed. Suite +16
    (Inv4SpineBatch5Test), listAll identical on ALL 8 profiles, bench in
    band. Batch 6 DONE round 517 (2026-07-14): checkDuplicateModifiers
    (TS1030/TS1029/TS1044 — statement-kind handlers over 10 node kinds; the
    threaded inAmbientContext + atTopLevel pair became ONE parent-chain walk,
    `spineDupModContext`, where the INNERMOST flag-deciding ancestor wins per
    flag — fn/member bodies reset ambient, Block decides atTopLevel=false,
    ModuleBlock resets it true — and any non-descended ancestor kind returns
    null = the old no-visit; checkModifiers/checkInvalidImportEqualsModifiers
    retained as FP-firewalled text heuristics, reach NOT widened per B69.6) +
    checkAmbientInitializers (TS1039/TS1254/TS1066/TS1031 — Enum/
    VariableStatement/ClassDeclaration enter handlers over
    `spineAmbientInitContext`; .d.ts top-level-ambient preserved at the
    SourceFile terminal; class-member/arrow bodies stay unreached — pinned
    negative, a signal-driven widening candidate; the B162 same-enum sibling
    scan reproduced via `spineSiblingStatements`) + checkSwitchCaseComparable
    (TS2678 — the per-statement-LIST const/annotated binding maps reproduced
    as a preceding-sibling scan at the SWITCH node,
    `spineSwitchSubjectBinding`; single-statement positions degrade to
    `listOf(stmt)` = the old fresh-map wraps). 9 walker funs (~453 lines)
    deleted, 3 init slots removed. Suite +27 (Inv4SpineBatch6Test, pins run
    against the OLD walkers first), listAll error lines identical on ALL 8
    profiles, bench in band. Batch 7 DONE same round: checkRestElementPropertyNames
    (TS2566 — pure-syntax, ObjectBindingPattern-enter handler; widened
    faithfully to catch-clause patterns, each nested pattern gets its own
    enter) + checkRestBindingPatternElements (TS1186/TS2493/TS2322 —
    `checkRestBindingParam` retained as the Parameter-dispatch core; widened
    to object-literal-method/class-expression params) +
    checkAmbientImplementation (TS1183 — the most intricate reach walk so
    far, `spineAmbientImplContext`: ambient fn/class-member bodies were never
    descended (own-declare → null + the [passedDeclBody] declare-module-above
    rule), while arrow/fn-expr/class-EXPRESSION-member/objlit-method bodies
    RESET ambient unconditionally (passedDeclBody cleared — the expression
    walk descended them with false even under ambient); statement containers
    position-checked (conditions/for-headers/switch-subjects/case-exprs
    unreached), expressions pass generically; interface arm is de-facto
    dormant — the parse drops interface method bodies, cf. the TS1246 note) +
    checkAmbientRelativeModuleNames (TS2436 — top-level-of-script-file gate =
    a SourceFile parent check). 15 walker funs (~551 lines) deleted, 4 init
    slots removed. Suite +21 (Inv4SpineBatch7Test — 19 pre-verified against
    the OLD walkers, 2 widening pins fail pre-migration as expected). Batch 8
    DONE round 518 (2026-07-14): the parameter-initializer family — SIX
    passes as three Parameter-enter handlers + one SetAccessor-enter handler:
    checkOptionalParamWithInitializer (TS1015 — the corpus-tuned requireType
    gate preserved: declarations need a type annotation or param-property
    modifier, arrow/fn-expr params fire regardless; interface/type-literal
    signatures and objlit/class-expr GET accessors stay excluded per the old
    reach) + checkOptionalBindingPatternParams (TS2463 — uniform
    owner-has-body gate per parent kind) + checkParamInitializerForbidden
    (TS2523/TS2524/TS2372/TS2502/TS18048 — walkParamInitForbidden + the
    binding-name walk + collectParamSelfRefs retained as the per-parameter
    core; the per-file code@pos dedup set became spineParamForbiddenEmitted;
    the walkParamForbiddenExprForFns nested-fn descent dissolves into
    per-Parameter enters; findParamSelfRef deleted as already-dead) +
    checkParameterInitializerInNonImpl (TS2371 — widened faithfully to EVERY
    FunctionType/ConstructorType position per tsc checkParameter (initializer
    + missing containing body); old reach was var annotations/aliases/casts
    only; accessors stay excluded) + checkSetAccessorInitializer/
    checkSetAccessorRestParameter (TS1052/TS1053 — parent gate widened from
    class declarations to class expressions + object literals per tsc
    checkGrammarAccessor; interface/type-literal setters excluded, a
    signal-driven candidate). 24 walker funs (~902 lines) deleted, 6 init
    dispatches removed. Suite +29 (Inv4SpineBatch8Test — 23 pre-verified
    against the OLD walkers, 6 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (518a vs 517b).
    Re-measured --passTiming (pre-batch): checker-init 21.6 s, spine 529 ms
    carrying 24 passes; this batch's six summed ~292 ms of old pass time.
    Batch 9 DONE same round: checkForInLhsTypeAnnotation (TS2404 —
    ForInStatement-enter; widened faithfully to arrow/fn-expr bodies the old
    statement walk never descended) + checkEmptyTypeArguments (TS1099 on
    calls/new — CallExpression/NewExpression-enter; the type-POSITION TS1099
    emitter sharing emitTS1099 is untouched; reportEmptyTypeArgs deleted as
    orphaned) + checkSetterReturns (TS2408 — SetAccessor-enter;
    checkSetterBodyReturns retained as the per-setter body scan, fn-boundary
    semantics unchanged; widened to await operands etc.) + checkWithStatements
    (TS1101/TS1300/TS2410 — WithStatement-enter; the threaded isInWith/isInAsync
    pair became ONE parent-chain walk: first WithStatement ancestor before any
    function-like boundary → inner-with suppression of TS1300/TS2410; nearest
    fn boundary's Async modifier decides TS1300, ARROWS still reset async to
    false (old behavior, tsc's AwaitContext would fire — signal-driven
    candidate, pinned negative); TS2410's balanced-paren span scan preserved;
    TS1101 gated on alwaysStrict != false via spineWithStrictActive). 16
    walker funs (~606 lines) deleted, 4 init slots removed. Suite +18
    (Inv4SpineBatch9Test — 14 pre-verified against the OLD walkers, 4 widening
    pins fail pre-migration as expected); listAll error lines IDENTICAL on ALL
    8 profiles (518b vs 518a). Batch 10 DONE round 519 (2026-07-14):
    checkParamInitForwardRef (TS2373 + the ES5 hoisted-body-var TS2454
    companion) — checkForwardRefsInParams (+ findForwardParamRefs /
    findForwardParamRefsInBlock / collectHoistedVarNamesFromStmts) retained
    as the per-function core, dispatched from spineCheckParamForwardRefs at
    every BODIED function-like's enter; widened faithfully to arrows /
    fn-exprs / objlit methods / class-EXPRESSION members
    (position-independent per-signature tsc grammar); bodyless signatures
    keep the old no-check (TS2371 territory), GetAccessor params stay
    unchecked (TS1054 territory). 2 walker funs (~70 lines) deleted, 1 init
    dispatch removed. Suite +14 (Inv4SpineBatch10Test — 10 pre-verified
    against the OLD walker, 4 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519a vs 518b). Batch 11
    DONE same round: the checkJumpTargets family (TS1104/TS1105/TS1107/
    TS1115/TS1116 + TS1344) — the threaded inIteration/inSwitch/labelNames/
    crossedFunctionBoundary flags became ONE parent-chain walk
    (spineCheckJumpTarget) mirroring tsc
    checkGrammarBreakOrContinueStatement's `while (current)` loop: first
    function-like ancestor → TS1107 (class static blocks now count — a
    faithful widening); a matching LabeledStatement resolves the jump, with
    tsc's isIterationStatement(lookInLabeledStatements=true) nested-label
    unwrap for labeled `continue` — a faithfulness FIX over the old
    immediate-child test (`L1: L2: for(;;){continue L1}` no longer
    false-fires TS1115); an iteration ancestor legalizes unlabeled jumps, a
    SwitchStatement legalizes unlabeled `break`, a ModuleBlock ancestor
    suppresses unlabeled `break` (the old inSwitch=true namespace rule);
    TS1344 label-on-declaration became a LabeledStatement-enter handler
    (widened to arrow-in-condition positions). 4 walker funs (~306 lines)
    deleted, 1 init dispatch removed; emitJumpDiagnostic /
    isDeclarationStatement retained as the per-jump core. Suite +18
    (Inv4SpineBatch11Test — 14 pre-verified against the OLD walker, 3
    widening + 1 faithfulness-fix pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519b vs 519a). Batch 12
    DONE same round: checkObjectLiteralModifiers (TS1042/TS1184) — the
    near-full-tree explicit-stack expression walk became a pure
    ObjectLiteralExpression-enter handler (spineCheckObjLitModifiers;
    OBJLIT_ACCESS_MODIFIERS companion-hosted per the init-order gotcha);
    nested literals get their own enters; parameter-default and
    spread-operand positions are faithful widenings. 3 walker funs
    (~206 lines) deleted, 1 init dispatch removed. Suite +10
    (Inv4SpineBatch12Test — 2 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519c vs 519b). Batch 13
    DONE round 520 (2026-07-14): checkDuplicateObjectLiteralProperties
    (TS1117/TS1118/TS2300 — [checkObjectLiteralDuplicates] retained as the
    per-literal core dispatched from the ObjectLiteralExpression enter; the
    destructuring-assignment-LHS skip became the came-from-child parent walk
    `spineObjLitInDestructuringLhs`: climb through pattern-position parents
    — object/array literals, a PropertyAssignment when the child is its
    INITIALIZER, spread positions — and skip iff a `=` BinaryExpression is
    reached with the climbed child as its LEFT; a ShorthandPropertyAssignment
    default VALUE terminates the climb, so `({q = {a,a}} = o)` is now checked
    — a tsc-faithful widening alongside ternary conditions, parameter
    defaults, and object-literal METHOD bodies) + checkReservedWordIdentifiers
    (TS1359 — checkAwaitParams retained, dispatched from every async
    function-like's enter; the enum void/await/yield name rule as an
    EnumDeclaration-enter handler; widenings: class property-initializer
    arrows, new-expression var initializers, var-init arrow expression
    bodies) — 6 walker funs (~370 lines incl. the already-dead reservedWords
    val) deleted, 2 init dispatches removed. Suite +23 (Inv4SpineBatch13Test
    — 16 pre-verified against the OLD walkers, 7 widening pins fail
    pre-migration as expected); listAll error lines IDENTICAL on ALL 8
    profiles (520a vs 519c). Batch 14 DONE same round:
    checkStrictModeReservedWords (TS1212/TS1213/TS1214/TS2480/TS18006 — the
    most stateful zero-typing walker yet): the threaded isStrict/
    isExpressionStrict/inClass/realStrict flags became ONE shared
    ancestor-chain context (`spineStrictReservedCtx`: collect the parent
    chain, walk it DOWN applying the old descent arms —
    Block/If/ForIn/ForOf/ModuleBlock/ModuleDeclaration transparent, a
    FunctionDeclaration entered ONLY under the strictness at ITS position
    with a "use strict" prologue upgrading realStrict for its subtree, a
    ClassDeclaration entered only through METHOD/CONSTRUCTOR members
    (auto-strict: inClass + both strictness flags forced), any other
    ancestor kind → null = the old no-visit); ten per-statement-kind
    handlers (var-statement incl. fn-expr-name/type-annot/class-expr-init
    legs, for-in/of header decls, fn decl, class decl incl. TS18006 +
    member params, interface, enum, import-equals, import bindings,
    namespace name, expression statement); per-file flags
    (spineStrictFile* — binding strictness by effectiveTarget, EXPRESSION
    strictness by RAW target, the explicitNonStrict suppression) computed
    in checkSpine's loop; the two strictReserved* instance flags moved to
    the pre-init spine block, assigned per position from the ctx. Reach
    deliberately NOT widened (corpus-tuned family — interfaceNaming1 /
    commonMissingSemicolons / constructorStaticParamName): while/do/for/
    switch/try bodies, accessor bodies, arrow/fn-expr bodies, and
    class-expression members stay unvisited, pinned negative as
    signal-driven widening candidates; the load-bearing reach QUIRK — fn
    bodies UNVISITED in non-strict files (no TS2480 for `let let` there) —
    is reproduced by the ctx walk and pinned. 3 walker funs (~250 lines)
    deleted, 1 init dispatch removed. Suite +25 (Inv4SpineBatch14Test —
    ALL 25 pre-verified against the OLD walker; a pure reach-preserving
    migration, no widenings); listAll error lines IDENTICAL on ALL 8
    profiles (520b vs 520a). NEXT batches: checkConflictMarkers (a text
    scan — may not need the spine); checkAwaitContext (stateful isAsync
    threading + module-top-level rules — decompose when reached);
    checkMixinClassConstructor is TP-scope-stateful — (d) territory;
    re-measure --passTiming to pick the next tail cohort.
  - [ ] **INV.4(c) The name-resolution pair.** checkUnresolvedNames (846 ms) +
    checkTypeUsedAsValue (734 ms): fold their private NameScope chains into
    spine-maintained authoritative lexical state backed by the INV.2(c)
    `lexicalScopes` tables (their planned mass consumption).
  - [ ] **INV.4(d) Mid-weight stateful walkers.** checkUncalledFunctionsInConditions
    (506 ms), checkArithmeticOperandTypes (271 ms), checkImplicitReturns,
    checkArgumentCounts, checkDefiniteAssignment, … — each moves its scope
    machinery onto the shared spine state; decompose per walker when reached.
  - [ ] **INV.4(e) The top-3 giants.** checkPropertyAccess (3.8 s) →
    checkTypeAssignability (2.2 s) → checkCallExpressionTypes (1.7 s) — one at
    a time, each with its own sub-plan when reached (together 38.6% of
    checker-init; 458k of 595k getTypeOfExpression calls).
  - [ ] **INV.4(f) The two unlocked soundness wins.** Once one authoritative
    walk state exists: the per-node expression-type cache (594,779 calls over
    ~221,844 distinct nodes = ×2.6 recompute), and flow narrowing folded into
    reference typing once (84,469 depth-0 walks, 68% from property access).
    Re-measure against the ≤10 s single-threaded compiler-profile target.
- [ ] **INV.5 Canonical types + explicit instantiation** (absorbs M5.2/M5.3). Intern
  unions/intersections by sorted member-id key; literal interning; explicit mapper
  objects replace the ambient `currentTypeAliasArgs`/TP-scope contexts; instantiated
  members cached ON the `Type.Reference` (delete `resolveGenericPropertyType`
  fresh-minting + its depth-4 OOM cap); `nodeTypes` keyed (node, mapper) — always
  valid; then open `canUseTypeEngine`'s generic gate and DELETE superseded pin walkers.
- [ ] **INV.6 Parallelism** (absorbs M5.4). Share-nothing checker workers per
  `docs/parallel-caching.md` (trivially partitionable once INV.4 gives a per-file
  check entry); parallel emit on Default + IO write sink; deterministic partition +
  merge via the existing diagnostic sort. Structured concurrency from INV.1.
- [ ] **INV.7 Productization** (absorbs M5.5/M5.6). Native re-enable (the big-input
  GC inversion should largely dissolve post INV.4/5); watch mode driven by a
  file-event Flow; `.tsbuildinfo`-style incremental reuse.

Numeric targets (proposed, doc § 6): post INV.4/5 single-threaded compiler profile
≤ 10 s (≈ JS tsc) + harness RSS ≤ 1 GB; post INV.6 compiler ≤ 5 s on 4 cores;
INV.7 stretch: native cold ≤ 2× tsgo.

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
