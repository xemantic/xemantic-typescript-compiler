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

**Round 549c (2026-07-17) — INV.5(b2a): the simple alias-args installer family
flipped to the explicit mapper bridge.** Survey: only 7 real `currentTypeAliasArgs`
write sites exist (the other ~84 ambient writes are `currentTypeParamScope`) — 6 are
simple wrap-one-`getTypeFromTypeNode` installers, 1 is the complex
getTypeFromTypeReference alias substitution (~93.8k; conditional TP-scope shadow +
depth counter + objlit stack — deferred to (b2b)). The 6 flipped to
`getTypeFromTypeNodeWithMapper` via two new helpers `aliasMapper(bindings)`
(REPLACING shape) / `layeredAliasMapper(bindings)` (merge-onto-ambient shape):
vmInstantiate (~85.8k), narrowByCallPredicate's predicate-TP bind (~99.8k),
the TS2345 single-TP missing-member emitter (~131.2k), the overload objlit
instantiate pair (~131.4k), and tryEvaluateConditionalWithInfer's true-branch
resolve (~140.5k). Behavior-identical (the bridge installs the same ambient pair;
one site — the predicate-TP bind — gains a previously-missing try/finally restore
on throw). Gates: corpus 11,249/0; listAll ×8 error-line identical vs 548a.
Next: (b2b) the getTypeFromTypeReference alias-substitution flip, then the
tpScope installer families.

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

**Round 548 (2026-07-17) — INV.5(c) LANDED (option iii): context-KEYED
`nodeTypes` for context-bearing resolutions.** getTypeFromTypeNode's
context-bypass tail now consults `mappedNodeTypes` — keyed by NODE IDENTITY
(NodeCtxKey: `===` equality with a nodeId hash, so cross-file nodeId
collisions only share buckets, never results; unindexed nodes skip) + the
instantiation-context fingerprint (inferenceNamespaceStack symbol ids +
sorted tpScope name:id pairs + sorted aliasArgs name:id pairs) — under the
CONSERVATIVE soundness gate from the round-547b analysis: caching only when
the checking-file dimension is PINNED (currentFileLocals === the node's
OWN file's locals via owningSourceFile + fileResults); every other consult
keeps the legacy re-run. This kills the recompute multiplier for the
dominant in-file context-bearing resolutions (alias substitution, TP-scope
resolution) while structurally excluding the conflation-era
checking-file-dependence hazard; the old empty-context cache and its
isPerFileDependentRefNode bypass stay untouched. KNOWN COST FLAGGED: the
fingerprint sorts context maps per consult — wall +5.4% single-run
(late-session drift band overlaps; re-measure interleaved and cache the
fingerprint per-install if real). VERIFIED: suite 11,249/0; listAll SORTED
error lines IDENTICAL on ALL 8 profiles (548a vs 547a); bench 46 errors,
histogram unchanged. NEXT: re-run the giant probes once more (the (c)
cache may dissolve the declaredTypes-timing couplings), then (b2+) /
fingerprint perf.

**Round 547 (2026-07-17) — INV.5(b1) LANDED: the explicit-instantiation-
context bridge.** `InstantiationMapper` (aliasArgs + tpScope + an
inferenceNamespaceStack-depth fingerprint — the THIRD cacheable-gate
context, carried as a depth only since explicit callers never synthesize
namespace stacks) + `ambientMapper()` + `getTypeFromTypeNodeWithMapper(node,
mapper)` (installs the mapper's contexts around the ambient-reading
resolution core — identical to the legacy hand-rolled save-set-restore
installers; the recursion still reads the ambient until (b2+) completes
the threading). Pilot: checkTpListDefaults' TS2344 default/constraint
resolutions route through the bridge with the ambient capture
(install-idempotent — byte-identical by construction). NAME CLASH found
in-flight: a `TypeMapper` fun-interface already exists (the relation
engine's TP-substitution functional) — the value class is
`InstantiationMapper`. This unblocks (c) directly: the keyed-nodeTypes
re-key needs the mapper only AT THE CACHE CONSULT, with ambient-bridged
installers still in place. VERIFIED: suite 11,247 → 11,249 (+2
Inv5MapperBridgeTest); listAll SORTED error lines IDENTICAL on ALL 8
profiles (547a vs 545a). NEXT: INV.5(b2+) — flip installer families
(resolution-internal first) / or start (c) directly per the b1 insight.

**Round 545 (2026-07-17) — INV.5(a) LANDED: canonical union/intersection
identity.** `getUnionType`/`getIntersectionType` now INTERN by member-id key
(CheckerState.unionInternCache/intersectionInternCache — unions by their
already-sorted member order, intersections by IN-ORDER ids so `A & B` and
`B & A` keep distinct display order; direct `Type.Union(...)` builds
elsewhere stay fresh, less canonical but never wrong). Identical member
lists now share one instance and Type.id regardless of WHICH pass builds
the type first — the direct lever on the round-543 first-touch blocker,
and it multiplies relation-cache/reference-cache hits (an Array<union>
reference now interns across contexts because its arg id is canonical).
THE ONE EXPOSURE (found by the round-544 first attempt, root-caused this
round via the round-472 Diagnostic-init stack-trace probe): watch.ts:533 —
a TERNARY of array literals annotated with the variadic-tuple alias
DiagnosticAndArguments. Pre-interning the arms' Array references had
DISTINCT union-arg ids, so their union never collapsed and the B87.6b
array-VARIABLE-vs-tuple gate could not match; canonical identity collapses
the arms to ONE Array Reference and the gate fired. tsc contextually
tuple-types those arms exactly like a bare array literal, so the fix
extends the gate's existing literal exclusion to (nested, paren-wrapped)
ternaries of array literals (`ternaryOfArrayLiterals`) — the same
rationale, one clause. The round-544 hazard list's display fears did NOT
materialize (aliasDisplayMap survived untouched; pinned). VERIFIED: suite
11,243 → 11,247 (+4 Inv5UnionInterningTest incl. the minimized watch.ts
shape with nested ternary, the B87.6b positive control, and the
identical-alias display pin); listAll SORTED error lines IDENTICAL on ALL
8 profiles (545a vs 541a); bench 25,947 ms self / 46 errors, histogram
unchanged (−3.4%, the interning already pays for itself). NEXT: INV.5 —
re-run the round-542/543 giant-entanglement probes to measure what the
canonical identity dissolved, then (b) explicit mapper objects.

**Round 541 (2026-07-17) — INV.4(d) walkers 12+13: the ORDER-COUPLED pair
checkCommaOperatorUnused (TS2695) + checkNullishPredicates (B277
TS2871/TS2869 `??` nullish predicates + while/do TS2872/TS2873 truthiness;
~100 ms each) migrated onto the spine TOGETHER — their six recursion
walkers (checkCommaInStatements/-InStatement/-InExpr, npStmts/npStmt/npExpr)
are DELETED (~470 lines).** The first COUPLED-pair migration, and the
ordering contracts dissolve STRUCTURALLY instead of by slot bookkeeping:
the comma pass's TS2695 emits PRE-order (outer-before-inner along the left
spine) → its anchors dispatch at ENTERS; the np pass's `??` checks emit
POST-order (tsc's inner-before-outer, each node's check after its right
subtree) → its anchors dispatch at the BinaryExpression's LEAVE; the
while/do truthiness checks — which must follow the condition subtree's own
emissions but precede the body's — dispatch at the CONDITION node's leave;
and the documented same-position comma-before-np insertion order holds
BY CONSTRUCTION because enters precede leaves at every node (the legacy
"np must run AFTER comma" slot contract is retired). Each pass keeps its
own verbatim classifier — their reach DIFFERS subtly: comma walks tagged
templates / yield-delete-typeof-void operands / comma-lists but NOT
object-literal method bodies; np the reverse; both walk enum-member
initializers, for-header declarator initializers, and
shorthand-destructuring defaults. 10 pins (Inv4SpineBatch32Test, ALL
pre-verified on the OLD walkers first run) incl. the chained-`??`
double-emission (the outer left's semantics READ THROUGH the inner `??`'s
right operand — nullishSemanticsOf recursion) and the indirect-call
`(0, obj.m)()` exemption. VERIFIED: suite 11,233 → 11,243 (+10, 0
regressions, XML-verified); listAll error lines IDENTICAL on ALL 8 profiles
(541a vs 541sm; the joint slot-move pre-gate also corpus-green); bench 46
errors, histogram unchanged (−0.6%, in band). NEXT: INV.4(d) tail —
remaining ~100 ms walkers per the round-529 cost table, then INV.4(e) (the
three giants).

**Round 540 (2026-07-17) — INV.4(d) walker 11: checkNullUndefinedUsage (the
TS18050 null/undefined-literal usage pass + the for-of empty-`[]` TS2488
shape; ~130 ms tail) migrated onto the spine — the per-file driver and the
recursion walkers (checkNullUndefinedInStatements/-InStatement/-InExpr) are
DELETED (~230 lines).** A pure-anchor migration (the deleted walkers
installed NO ambient state) with one structural first: the classifier
carries the legacy `checkDepth ≤ maxCheckDepth` STATEMENT-frame cap as a
DEPTH-encoded status (ShortArray, kind*512 + depth; depth = ancestor
statement-frame count) — and crucially the depth increments ONLY at the
legacy per-statement frames: body Blocks the legacy dispatched WITHOUT a
frame (fn/member/arrow bodies, module blocks, try blocks) are CARRIER
blocks at the parent's depth, so their statements land exactly one frame
deeper, matching the single legacy increment. Anchors at
BinaryExpression (arithmetic/bitwise strict-gated operand checks with the
`+`-string exemptions; `in` operands in all modes), PropertyAccess/
ElementAccess (null-receiver), and ForOfStatement (bare-null iterable +
the empty-`[]` TS2488 pattern) enters. Reach quirks mirrored (12 pins,
Inv4SpineBatch31Test, ALL pre-verified on the OLD walker first run): `new`
expressions, as/angle casts, non-null assertions, await/yield/void/typeof/
delete/spread operands, tagged templates, comma lists, class EXPRESSIONS,
object-literal methods/accessors, and for-header DECLARATOR initializers
all unreached; switch subjects AND case expressions walked; objlit property
VALUES walked. VERIFIED: suite 11,221 → 11,233 (+12, 0 regressions,
XML-verified); listAll error lines IDENTICAL on ALL 8 profiles (540a vs
540sm; slot-move pre-gate also corpus-green); bench 46 errors, histogram
unchanged. NEXT: INV.4(d) tail — checkCommaOperatorUnused +
checkNullishPredicates (the documented same-position insertion-order pair —
migrate TOGETHER or comma-first), then the remaining ~100 ms walkers.

**Round 539 (2026-07-17) — INV.4(d) walker 10: checkAlwaysTruthy (the
TS2872/TS2873 always-truthy/falsy condition pass + TS1345 void-call
conditions, TS2845 enum-member truthiness, and the `!`-operand falsy check;
~160 ms tail) migrated onto the spine — the per-file driver and the
recursion walkers (checkAlwaysTruthyInStatements/-InStatement/-InExpr) are
DELETED (~230 lines), plus the B69.11 inArrowExprBody threading field.**
The lightest stateful shape since dupId: NO frames — both walk states
pull-derive per anchor from the ancestor chain: (1) the B69.11
inArrowExprBody flag (suppresses the numeric-literal `||`-left TS2872)
derives as "any ancestor edge crosses an arrow EXPRESSION body" — the
legacy flag was set at that edge and NEVER reset, so it survives into
nested function-body blocks (pinned: `() => (function(){ return 1 || y })()`
stays suppressed); (2) the if-else-chain prevTruthy state (the legacy
manual chain loop — TS2872 on an else-if condition fires only when a
PRECEDING chain condition is always-truthy) derives by walking the
elseStatement ancestor links. Anchors dispatch per chain NODE at
IfStatement enters (each else-if is its own dispatch, chain-position-aware),
While/Do/For/ternary/||-&&-binary/prefix-`!` enters. Reach quirks mirrored
(13 pins, Inv4SpineBatch30Test, ALL pre-verified on the OLD walker first
run): if/while/do and TERNARY condition SUB-expressions are checked only by
the whole-expression predicates (never walked — the CLAUDE.md
condition-scan gotcha), while FOR conditions are FULLY walked (the
asymmetry pinned); for initializers/incrementors and for-in/of and switch
SUBJECT expressions unreached; class-EXPRESSION member bodies reached
(unlike walker 9). One edge bug caught pre-verification by design review:
try blocks briefly classified through the carrier state whose arm lacked a
Block case (their statements would have been unreached) — fixed before any
run. VERIFIED: suite 11,208 → 11,221 (+13, 0 regressions, XML-verified);
listAll error lines IDENTICAL on ALL 8 profiles (539a vs 539sm; slot-move
pre-gate also corpus-green); bench 46 errors, histogram unchanged. NEXT:
INV.4(d) tail — checkNullUndefinedUsage / checkCommaOperatorUnused /
checkNullishPredicates (mind the np-walker's documented ORDER dependency on
checkCommaOperatorUnused's same-position insertion order).

**Round 538 (2026-07-17) — INV.4(d) walker 9: checkConstAssignment (the
TS2588/TS2628/TS2629/TS2630/TS2708 const-family pass + the B510 TS2540
readonly-write checks, TS2357 inc/dec targets, and the scanRegExpFull
TS1538/regex-grammar family riding the same walker; 170 ms in the round-529
cost table) migrated onto the spine — the per-file driver and the recursion
walkers (checkConstAssignmentInStatements/-InStatement/-InExpr) are DELETED
(~330 lines).** The most state-diverse (d) migration: (1) the
statement-ordered per-list constNames maps become PUSH-maintained frames
(SpineCaFrame; per-statement COLLECT step at direct statements of the top
LIST frame — const records 2588, a let/var of the same name REMOVES the
inherited entry) with the legacy ASYMMETRIC spawn rules pinned:
fn-decl/fn-expr/arrow-Block/IIFE-arrow-Block bodies get FRESH EMPTY maps
(an outer const reassigned inside a function body is NOT flagged —
bug-compat), Block/switch-clause/try/ModuleBlock/class-MEMBER bodies
inherit COPIES, an arrow EXPRESSION body SHARES the live map, and a
ForStatement pushes a header-consts overlay frame (safe to install
unconditionally: header const additions exist only when the initializer is
a declaration list, whose declarator initializers are unreached, so the
expression-form init sees an identical map). (2) The B510 class-this
context (currentClassForThis/currentThisMemberIsCtorDirect) pull-derives
per anchor from the ancestor chain — fn-expr rebinds (null), non-IIFE
arrows clear ctor-directness, IIFE-arrows AND nested fn-decls are
transparent (the latter bug-compat: the legacy arm never touched the
fields), the nearest class-DECL member decides (static → null; Constructor
→ ctor-direct; property initializers never). (3) The B116 fn-decl
param-typing install (populateParameterLocalTypes — fn DECLS only)
rebuilds pull-based per anchor from the enclosing fn-decl chain, memoized
per fn-decl nodeId, based at the PRE-SPINE resting
currentLocalTypes/currentParamBindingNames — installed around each
readonly-check emission only (never walk-wide), because a walk-wide
install would leak into OTHER spine handlers' ambient. Enter-dispatch is
safe here (checked first: no diagnostics-list probes in
checkReadonlyAssignmentTarget/scanRegExpFull — the round-537 hazard
absent). Reach quirks mirrored (19 pins, Inv4SpineBatch29Test, ALL
pre-verified on the OLD walker first run): for-header declarator
initializers, class EXPRESSIONS (entirely!), objlit methods/accessors,
await/yield/void/typeof/delete/comma/tagged-template operands all
unreached; an assignment BEFORE the const in the same list is not flagged
(statement order); for-of/for-in loop vars are never collected. VERIFIED:
suite 11,189 → 11,208 (+19, 0 regressions, XML-verified); listAll error
lines IDENTICAL on ALL 8 profiles (538a vs 538sm; slot-move pre-gate also
corpus-green); bench 26,817 ms self / 46 errors, histogram unchanged.
NEXT: INV.4(d) — the ~100–165 ms tail (checkAlwaysTruthy,
checkNullUndefinedUsage, …); decompose the next walker when reached.

**Round 537 (2026-07-17) — INV.4(d) walker 8: checkImplicitReturns (the
TS7030/TS2355/TS2366/TS2378/TS7023 + arrow concise-body TS2322 pass, 199 ms
in the round-529 cost table) migrated onto the spine — the per-file driver
and the recursion walkers (walkForImplicitReturns / walkStmtForImplicitReturns
/ walkExprForImplicitReturns) are DELETED (~140 lines), the five anchor
functions retained minus their trailing body recursion.** Reach is a 4-state
memoized classifier (`spineIrStatus` over `spineIrEdge` — STMT/EXPR/MEMBER/
NONE, the deleted arms verbatim, with the descent GATES encoded on the body
edges: generator bodies never descend, GetAccessor pos==-1 sentinel bodies
skip, arrow CONCISE bodies never descend); anchors dispatch under
`spineIrDispatch`'s per-dispatch ambient install (implicitReturnFlowGraph +
currentCheckFileName + the PRE-SPINE resting currentFileLocals/
currentFunctionParams captured at checkSpine entry — the legacy pass ran
outside the spine's per-file locals install, and
checkGetAccessorForImplicitReturn READS the resting currentFunctionParams
without setting it). THE ROUND'S LESSON — ANCHORS DISPATCH AT LEAVE, NOT
ENTER: the 17.135 TS2304/TS2314 suppression gates in
checkBodyForImplicitReturnCore probe the DIAGNOSTICS LIST at the return
annotation's position, and the annotation's own TS2304 is emitted by the
spine-hosted ures family at the ANNOTATION node's enter — after the fn's
enter. Enter-dispatch over-emitted TS2355 on exactly 2 corpus tests
(unknownSymbols1, genericRecursiveImplicitConstructorErrors3 — the corpus
caught what the 8 profiles could not, again); leave-dispatch sees the whole
subtree's emissions, exactly what the slot-moved pass saw. Slot-move
pre-gate landed separately (round-537 prep commit): corpus + listAll ×8
identical, and the pass stays BEFORE checkTypeAssignability whose
end-of-pass filter consumes this pass's TS7030s. Reach quirks pinned (19
pins, Inv4SpineBatch28Test, pre-verified on the OLD walker): generator
bodies, class-DECL Constructor/SetAccessor bodies and PropertyDeclaration
initializers (class-EXPR prop inits ARE reached), objlit SetAccessor
bodies, arrow concise bodies, return/throw expressions, if/while
conditions, and for headers all unreached; the legacy JS gate is .js/.jsx
ONLY (.mjs/.cjs stay checked — spineIsJsLike is the wrong predicate).
VERIFIED: suite 11,170 → 11,189 (+19, 0 regressions, XML-verified); listAll
error lines IDENTICAL on ALL 8 profiles (537b vs 537sm); bench errors
unchanged (46, histogram identical; wall +6.4% single-run = the documented
late-session box-drift band). NEXT: INV.4(d) walker 9 —
checkConstAssignment (170 ms), then the ~100–165 ms tail.

**Round 536 (2026-07-17) — INV.4(d) walker 7: checkUseBeforeDeclaration (the
TS2448/TS2449/TS2450 TDZ pass + the TS2454 co-emit and static-init TS2729,
205 ms in the round-529 cost table) migrated onto the spine — the per-file
driver, the per-list activation (checkUBDInStatements), and the nested-scope
recursion walkers (checkUBDInStatement / checkUBDInExprForNested) are
DELETED (~195 lines).** The lightest stateful shape since the dupId walker:
reach is a 5-state memoized ancestor classifier (`spineUbdStatus` over
`spineUbdEdge` — STMT/EXPR/LIST/MEMBER/NONE, the deleted arms verbatim), the
per-LIST blockScopedDecls map (a pure WHOLE-list function: let/const/class/
enum/namespace decls minus hoisted fn/var names) is memoized per list owner
(`spineUbdListDecls`), the retained BOUNDED per-statement walk
`checkUBDForwardRefs` (which recurses if/labeled descent itself — nested
statements never re-anchor) runs at DIRECT statements of ACTIVATED lists,
and the For/ForIn/ForOf loop-header SELF-ref checks re-host at the loop
statements' enters (any UBD_STMT-reached loop, since the deleted recursion
ran them at every statement position, not just list members). ORDER
COUPLINGS, both resolved by slot placement and empirically gated: (1) the
producer sibling populateAmbientCyclicBaseClasses (the TS2449
ambient-cyclic suppression set consulted by emitTS2449) moved BEFORE the
spine; (2) emitTS2448 CO-EMITS TS2454, and
checkDefiniteAssignmentViaFlowGraph's position-dedup scan — which runs
right after the spine since round 534 — now SEES those co-emissions where
the legacy order hid them (UBD ran ~500 slots later); the slot-move
pre-gate (intact pass between the spine and the DA-flowgraph siblings)
measured error-line-identical listAll ×8 AND corpus-green, so the
interplay is inert. The cross-file leg
(checkCrossFileUseBeforeDeclaration) stays a separate pass at the spine
slot, preserving per-file-first order. Reach quirks pinned (33 pins,
Inv4SpineBatch27Test, ALL pre-verified on the OLD walker first run): the
ForwardRefs walk descends if/labeled ONLY — an UNBRACED if-body statement
IS forward-checked while a BRACED one is a fresh activation and is NOT;
while bodies / do-statements (condition AND body) / for headers / for-in/of
iterated expressions / try bodies / switch CLAUSE statements are never
forward-checked against the OUTER list's later declarations; async IIFE
self-refs exempt while plain IIFE self- and forward-refs fire (with body
shadowing suppression); const enums / declare-decls have no TDZ; a hoisted
var/function alongside the let disables the check; instance property
initializers are deferred while static ones fire TS2729+TS2449. VERIFIED:
suite 11,137 → 11,170 (+33, 0 regressions, XML-verified); listAll error
lines IDENTICAL on ALL 8 profiles (536a vs 535a); bench row recorded.
NEXT: INV.4(d) walker 8 — checkImplicitReturns (199 ms) / checkConstAssignment
(170 ms).

**Round 535 (2026-07-17) — INV.4(d) walker 6: checkArgumentCounts (the
TS2554/TS2555/TS2575 function-call arity pass, 230 ms in the round-529 cost
table) migrated onto the spine — the per-file driver and the recursion
walkers (checkArgCountInStatements / checkArgCountInStatement /
checkArgCountInExpr(Core)) are DELETED, plus the argCountDepth /
argCountFnDepth / argCountSuperCtor threading fields.** Two firsts in the
(d) series: (1) **reach is a memoized DEPTH classifier, not a boolean/state
one** — the legacy `argCountDepth` recursion counter (with its load-bearing
≤200 cap: `++argCountDepth > 200` silently prunes a subtree, the
anti-SOE guard for left-nested binary chains) is reproduced per node as an
Int over `spineArgEdge` (ShortArray memo, 0/1/depth+2 encoding); an
expression-position edge costs one frame (+1), a statement/list/member edge
carries the ambient value, and a BinaryExpression's RIGHT operand that is
itself a binary is ABSORBED (the legacy right-spine loop — no frame, so
right-leaning `**`/`=` chains never consume depth while left-nested chains
prune at 200; both pinned). (2) **the downward context is MAP-valued and
rebuilt PULL-based** — the funcParams/classCtorParams/fnDepth/superCtor
threading is a pure function of the ancestor chain (every list overlay
reads its WHOLE statement list, never a position-ordered prefix), so
`spineArgCtxAt` ascends to the nearest statement-LIST owner
(SourceFile/Block/ModuleBlock/switch clause), takes its per-owner memoized
`spineArgListCtx` (the checkArgCountInStatements activation: M1.11
ModuleBlock namespace collection + 17.126 nested-fn overlay + fnDepth-gated
var-shadow removal), and re-applies the transforming edges top-down
(fn-boundary minusParamShadowedNames + fnDepth++, class-DECL-constructor
superCtor rebind, for-of loop-name shadow). TRAP HIT AND PINNED IN THE
DESIGN COMMENT: `spineArgCtxAt` RE-ENTERS itself through `spineArgListCtx`
(each owner's incoming ctx ascends to the next owner up), so the shared
ascent buffer must be MARK-based, not clear()-based — the first cut
silently dropped the for-of loop-shadow edge (the one pin that caught it;
45/46 on first migrated run). ORDER COUPLING: the producer sibling
checkSpreadNonIterableIntoFixedArity moved BEFORE the spine (its
spreadNonIterableHandledCalls set is consulted by the TS2554 too-many arm);
checkCloduleTest2 (which REMOVES/EDITS TS2554 diagnostics at NewExpression
positions) sits at a later slot than both orders, so it needed no move.
Slot-move pre-gate: intact pass at the spine slot + SNI before = error-line
identical listAll ×8 (the pass is self-contained — own maps, reads only
AST + the SNI set — so no arith-style residue coupling). Bug-compat
asymmetries pinned (46 pins, Inv4SpineBatch26Test, ALL pre-verified on the
OLD walker): class-EXPRESSION member bodies get NEITHER the param shadow
NOR the fnDepth increment (params don't shadow; top-level body-locals don't
shadow) and a class-expression constructor never rebinds superCtor (a
nested `super(1)` vs a 2-param base is silent); `new` CALLEE expressions,
switch CASE expressions, param defaults, heritage arguments, enum member
initializers, objlit accessor bodies, shorthand destructuring defaults,
and computed names are unreached; the fnDepth-gated var-shadow removal is
nested-lists-only (a top-level `var f` redeclaration keeps the check).
VERIFIED: suite 11,091 → 11,137 (+46 Inv4SpineBatch26Test, 0
regressions, XML-verified); listAll error lines IDENTICAL on ALL 8 profiles
(535a vs 535base); bench row recorded. NEXT: INV.4(d) walker 7 —
checkUseBeforeDeclaration (205 ms) / checkImplicitReturns (199 ms).

**Round 534 (2026-07-16) — INV.4(d) walker 5: checkDefiniteAssignment (the
SET-based TS2454 pass, 241 ms in the round-529 cost table) migrated onto the
spine — the per-file driver and the two recursion walkers
(checkDefiniteAssignmentInNestedScopes / checkDefiniteAssignmentInExprContext,
~370 lines) are DELETED.** The first per-statement-LIST ordered walker with a
DOWNWARD leak context, and the design is a hybrid of the earlier templates:
(1) each legacy `checkDefiniteAssignmentInStatements` activation becomes a
CORE FRAME (SpineDaFrame — the statement-ordered `uninitialized` set, the
round-469 closure pre-scan, the per-statement B78.2 nested-leak snapshot)
pushed at its owner's enter (SourceFile with leak computation disabled +
fileLocals; fn-like BODY Blocks with params-as-preInitialized + the incoming
leak; Block-statements and ModuleBlocks fresh) and popped at its leave; the
legacy loop body (collect → closure-removal → checkUses → mark → nestedLeak)
runs at each DIRECT-child statement's enter (`parent === top.owner` — no
classifier needed for the step), reproducing the legacy check-then-recurse
emission order because children walk after the enter. (2) The deleted
recursion's reach is a memoized 10-STATE ancestor classifier
(spineDaStatus/spineDaEdge — STMT/EXPR/MEMBER × leak-flavor + CORE/PASS/
NONE/ROOT, the deleted arms verbatim): try/catch/finally blocks and switch
clauses are PASS (statements descend leak-dropped with NO frame — no
collect/checkUses/mark for those lists, pinned), if/loop/labeled descent and
Block/ModuleBlock cores drop the leak, fn-boundary + expression-context edges
carry it. (3) The downward leak SET is not threaded at all — a LEAK-flavored
status reads the TOP frame's per-statement `currentLeak` at core-spawn time,
sound because a leak-preserving path can never cross another core spawn (the
only core-spawning nodes are Blocks/ModuleBlocks, which all drop or consume
the leak). ORDER COUPLING RESOLVED BY SLOT PLACEMENT: the sibling
checkDefiniteAssignmentViaFlowGraph DEDUPS one-directionally against this
pass's emitted TS2454 positions, so it and checkTryCatchOnlyAssignedVarReads
moved to right AFTER the spine dispatch, preserving the legacy
set-pass-first order; the slot-move pre-gate (intact pass before the spine,
siblings after) measured error-line-identical listAll ×8. Reach quirks
pinned as negative controls (ALL 38/39 pins pre-verified on the OLD walker;
one pin fixed pre-verification: an `any`-typed heritage var is never
tracked): if-CONDITION arrows and throw-expression closures unreached;
class-DECLARATION property-initializer arrows unreached while
class-EXPRESSION ones ARE (the legacy ClassDeclaration-arm vs
ClassExpression-arm asymmetry); a statement-order-LATE `let` is absent from
an earlier class expression's leak; round-469 closure-assignment suppression
kills even the straight-line read; while-true definite assignment; the
arrow EXPRESSION body passes the leak through to a nested block-bodied
arrow. VERIFIED: suite 11,052 → 11,091 (+39 Inv4SpineBatch25Test, 0
regressions, XML-verified); listAll error lines IDENTICAL on ALL 8 profiles
(534a vs 533a); bench row recorded. NEXT: INV.4(d) walker 6 —
checkArgumentCounts (230 ms; the M1.11 lexical-shadowing overlays), then
checkUseBeforeDeclaration (205 ms).**

**Round 533 (2026-07-16) — INV.4(d) walker 4: checkDuplicateIdentifiers
(TS2300 family — 2300/2451/2374/2687/2717/2699/2502/7023/1061/…, 260 ms
zero-typing in the round-529 cost table) migrated onto the spine — the
recursive checkDuplicatesInStatement(s)/checkDuplicatesInExpr/
checkDuplicatesInClassElement walkers and the per-file pass driver are
DELETED (~215 lines).** The lightest (d) shape so far — the pass is STATELESS
(no threaded walk state; the two checkDuplicateDeclarations flags
isAmbientContext/inNamespaceBody derive at the anchor) and ZERO-TYPING (0
getTypeOfExpression calls in the 529 table), so the migration is a pure
boolean reach classifier + anchor dispatch: (1) `spineDupIdReached` over
`spineDupIdEdge` (the deleted walkers' arms verbatim, ByteArray memo per
file) gates per-kind emissions at node enters — fn/class/interface/alias/
enum/var-statement/module/export-decl/arrow/fn-expr anchors run the RETAINED
bounded leaf utilities (checkDuplicateTypeParams/Params,
checkDuplicatesInType(Member), the member-list checks,
checkDuplicateDeclarations) minus the body/initializer recursion, which the
spine edges own; class/objlit MEMBER emissions dispatch uniformly at the
member's own enter (a reached MethodDeclaration is a class OR objlit member
— both emitted TPs+params in the legacy loops; a reached SetAccessor/
Constructor is class-only since the objlit edge never admits accessors; a
GetAccessor emits nothing). (2) The per-file top-level scans ride checkSpine's
loop preserving the legacy within-file order: TS2502 self-typeof +
circular-inference at file ENTER (spineDupIdSetup), the file-level
declaration-group + merged-namespace/lib-shadow/cross-interface/prototype/
clodule scans after the walk (spineDupIdFinish). (3) The legacy pass ran with
currentFileLocals = null (its old slot set nothing); checkSpine sets it per
file — every scan is wrapped in a null install, which the
checkClassNamespacePrototypeConflict `currentFileLocals ?: globals` consult
makes load-bearing. SLOT-MOVE PRE-GATE: the intact pass moved from slot 2408
(before the giants) to the spine slot measured error-line-identical listAll
×8 — statelessness confirmed empirically, no residue coupling (unlike w2).
Reach quirks pinned as negative controls, all pre-verified on the OLD walker
(48/48 green first run): call arguments / binary operands / if-and-loop
CONDITIONS / case EXPRESSIONS / for-header initializers / param defaults /
class property initializers / object-literal ACCESSOR bodies all unreached;
duplicate-declaration GROUPS fire only at file level, FunctionDeclaration
bodies, and ModuleBlocks — an arrow/method body's `class X{} class X{}` is
legacy-silent and stays so. Also cleared the 6 pre-existing round-532
compiler warnings (2 redundant casts, 4 redundant else arms — the
warning-clean rule). VERIFIED: 48 pins (Inv4SpineBatch24Test) green on OLD
and NEW; suite 11,004 → 11,052 (0 regressions, XML-verified); listAll error
lines IDENTICAL on ALL 8 profiles (533a vs 532a); bench row recorded. NEXT:
INV.4(d) walker 5 — checkDefiniteAssignment 241 ms / checkArgumentCounts
230 ms, decompose when reached.**

**Round 532 (2026-07-16) — INV.4(d) walker 3: checkImplicitAnyParameters
(TS7005/TS7006/TS7008/TS7013/TS7019/TS7031/TS7032/TS7051, 272 ms in the
round-529 cost table) migrated onto the spine — the recursive
checkImplicitAnyInStatements/-InClassElement(Core)/-InExpr walkers and the
per-file pass driver are DELETED (~770 lines).** The first
DOWNWARD-CONTEXT-THREADING migration; the design extends the (d) template:
(1) the expression recursion's FIVE explicit context parameters
(contextuallyTyped / contextualType / ctxViaUnionWithPrimitive /
ctxAnnotation / ctxViaAssignment) become ONE immutable `SpineIanyCtx` value
push-maintained on the frames LIFO, defined at EXACTLY the edges the legacy
recursion passed explicit arguments over — the load-bearing rule is that
every REACHED expression-position edge must define the context (even to
null/all-defaults): a missed edge silently INHERITS the parent context where
the legacy call passed defaults. Kinded carrier values keep one-level-deep
special states off the general rule (kind 1 = call-args ctxProp computed
once per call like the legacy single `isCalleeResolvable` call; kind 2 =
destructure-default typed-keys; kind 3 = the object-literal enrichment —
fresh viaUnionWithPrimitive verdict + the lazily-shared computed-key
mapped-value arity, stored ON the ctx value so all the literal's props share
one computation). (2) The binary left-spine LOOP dissolves into PER-EDGE
rules with no chain-root machinery (unlike arith's leave-emissions): the
loop's per-operand actions depend only on each binary's own operator and the
inherited state — right operand: assignment-family ops run the
resolveAssignTargetCtxTypeForImplicitAny block (+ the implicitAnyCtxUnknowable
side channel) at the right child's enter, `||`/`??`/`&&`/comma inherit;
left operand: inherits for `||`/`??` only. viaUnion is inert with a null
type, so resetting both together on non-inherit edges is behavior-identical
to the legacy loop's viaUnion pass-through. (3) The statement-walk THREADED
flags pull-derive from parent chains gated by the reach classifier:
returnCtxAnnotation = the nearest fn-like boundary's annotation through
Block/If/For only (Constructor/SetAccessor → null); inAmbientContext = a
`declare` on the unbroken ModuleBlock/ModuleDeclaration chain (every other
parent kind resets, incl. plain Blocks — the legacy recursion did not pass
it there). (4) The three implicit-any scope stacks + ns stack +
enclosing-class-members stay the SAME checker fields, pushed at fn-BODY
edges / module-body edges / class-element enters and recorded at declarator
ENTERS (legacy recorded before walking the initializer, so `var f =
function(){ f(); }` sees itself); the eager shape-B annotation resolution is
mirrored at the declarator enter (stashed for the initializer edge) so
first-touch order stays close. (5) No ambient install wrapper: the pass
never consulted the shared walk fields deliberately, and the SLOT-MOVE
pre-gate (intact pass moved from slot 2180 past the 4 sibling TS7xxx passes
to the spine slot) measured error-line-identical listAll ×8 AND corpus-green
(11,004 incl. the new pins) — unlike w2 there is no giants-residue coupling
(the pass ran BEFORE the giants at its old slot). Reach quirks pinned as
negative controls, all pre-verified on the OLD walker: while/do/switch/try/
for-in/for-of bodies UNREACHED, for-headers/call CALLEES/conditional
CONDITIONS/as-cast operands/objlit accessors/class static blocks unwalked,
class-expression setters fire TS7032 even with a sibling getter (empty
legacy siblings), ambient-class private members unchecked. VERIFIED: 56 pins
(Inv4SpineBatch23Test) green on OLD and NEW; suite 10,948 → 11,004 (0
regressions, XML-verified); listAll error lines IDENTICAL on ALL 8 profiles
(532a vs 531b); bench row recorded. NEXT: INV.4(d) walker 4 —
checkDuplicateIdentifiers 260 ms (zero-typing), decompose when reached.**

**Round 531 (2026-07-16) — INV.4(d) walker 2: checkArithmeticOperandTypes
(TS2362/TS2363/TS2365/TS2447/TS2358/TS2848/TS2736 + the in/instanceof/equality
arms, 309 ms in the round-529 cost table) migrated onto the spine — the per-file
pass driver is DELETED; the recursive checkArithmeticInExpr/InStatement(s)
walkers are RETAINED solely as checkComputedDestructKey's expression utility
(their only remaining caller), with the per-declarator recording block extracted
to the shared `spineArithRecordVarDecl`.** The design EXTENDS the (d) template to
an ORDER-DEPENDENT stateful typing pass, three new lessons: (1) SLOT-MOVE
PRE-GATE — this was the first migrated pass that sat AFTER the three giants
(64d); moving the INTACT legacy pass to the spine slot produced EXACTLY ONE new
FP on all 8 profiles (builder.ts:2390 TS2322 — arrayToMap's `Path` key inference
degrading to `string`), bisected by a cheap probe to the currentParamBindingNames
LEAK (populateParameterLocalTypes adds TP-referencing-fn param names that the
giants used to see only AFTER their own runs; restoring the set post-pass =
error-line identical ×8, so the leak is the ONLY order coupling — cache
first-touch reordering across the giants is empirically inert). The migration
runs with a PASS-PRIVATE set (seeded at checkSpine entry, accumulating across
files like legacy, never published back). (2) STATE is PUSH-maintained, not
pull-rebuilt — the recordings are statement-ordered and deliberately LEAK across
blocks/if-branches/accessor bodies (bug-compat, pinned), so a position-independent
level rebuild is impossible; `spineArithEnterNode`/`spineArithLeaveNode` maintain
the per-file map copy, the typeof/truthy narrowing sets, and the B475 ctx frames
on ONE LIFO of (node, saved-value) frames — edge-triggered frames keyed on the
CHILD node (if-branches, &&-right, ternary arms, for-in body, `=`-objlit RHS,
property-value ctx), popped at that node's leave AFTER its own emissions run;
shared checker fields are touched only inside `spineArithInstalled` around each
emission/recording (currentLocalTypes/currentParamBindingNames swapped to the
pass-private structures, currentArithmeticFlowGraph per-dispatch, TP scope
nulled = the legacy pass-ambient). (3) The LEFT-SPINE FLATTEN reproduces as
chain-root LEAVE emission: checkBinaryOperatorTypes runs for every left-spine
member innermost-first at the chain ROOT's leave, where root = a reached binary
that is not `&&`/`=`-objlit (the two pre-flatten special branches) and not
left-ABSORBED into an enclosing flatten (`spineArithChainAbsorbed` — iterative
left-edge ancestor walk; absorbed iff ANY such ancestor has a non-special
operator; the flatten descends unconditionally, so an absorbed `&&` gets NO
truthy narrowing — a quirk that turned out MASKED by the round-453 flow-graph
layer, the one pin that flipped during pre-verification). (4) THE CORPUS CAUGHT
WHAT THE 8-PROFILE A/B COULD NOT — a RESIDUE dependence: qualify.ts regressed
(+1 FP TS2365 `'number' and 'I'` on a namespace-level `n + y`) because at the
OLD slot the pass INHERITED the TS2322 walk's currentLocalTypes residue — the
assignability walk records namespace/top-level vars (unannotated → widened
inferred at ~85406; annotated → FIRST-WINS at ~85431), so the namespace
`var y = m` → number landed first and blocked the later file-level `var y: I`;
the clean-base migration lost that, and `y` fell through the block-UNAWARE
fileLocalTypeMaps to the file-level `I`. Fix: the pass records the chain
ITSELF — a NAMESPACE-level unannotated identifier-init var whose initializer is
a pass-recorded primitive records the chained type (`spineArithRecordVarDecl`'s
new Identifier branch, ModuleBlock-gated: function-body recordings never
persisted into the old residue because checkFunctionBody save/restores, so the
gate IS the residue-faithful scope). Lesson: a migrating pass that CONSUMED
upstream residue needs the corpus suite run on the slot-move experiment too —
the tsc profiles carried zero instances of the shape. VERIFIED: 39 pins
(Inv4SpineBatch22Test; 37 pre-verified against the OLD walker + the qualify
shape + the fn-body negative control), suite 10,908 → 10,948 (0 regressions, XML-verified);
listAll error lines IDENTICAL on ALL 8 profiles (531b vs 530a); bench 26,081 ms
self / 46 errors, histogram unchanged (+0.5% vs 530, box-drift band). NEXT: INV.4(d) walker 3 — checkImplicitAnyParameters 272 ms /
checkDuplicateIdentifiers 260 ms (zero-typing), decompose when reached.**

**Round 530 (2026-07-16) — INV.4(d) walker 1: checkUncalledFunctionsInConditions
(TS2774/TS2801, 435 ms in the round-529 cost table) migrated onto the spine —
the recursive walkUncalledChecksInStatement(s)/walkUncalledChecksInExpression
walkers and the withUncalledScope/withUncalledBlockScope push/pop pair are
DELETED (~270 lines).** First (d)-class migration — the design extends the
(c)(iv) template to a TYPING pass with per-emission ambient state: (1) REACH is
a memoized BOOLEAN ancestor classifier (`spineUncalledReached` over
`spineUncalledEdge` — the deleted arms verbatim, incl. the quirks pinned in
Inv4SpineBatch21Test: switch subjects / case expressions / for headers / enum
initializers / param defaults unreached; try/catch/finally + switch-clause
statements walked WITHOUT a block scope; object-literal method and
class-EXPRESSION member bodies walked WITHOUT a param scope or this-type;
dotted-namespace bodies ARE walked — the parser keeps one ModuleDeclaration
with a dotted name and a ModuleBlock body, the one pin that flipped during
pre-verification). (2) SCOPE: the three legacy stacks (shadowed / typed-locals
/ this-type) are REBUILT pull-based at each emission from the anchor's
ancestor chain (`spineUncalledWithScopes`) with per-owner memoized levels
(`spineUncalledLevelFor`) — sound because the level collection is
position-independent (params + top-level body statements only) and each level
computes LAZILY under exactly the already-pushed OUTER levels, the state the
legacy walk had at its scope entry; the this-type chain SKIPS a class whose
STATIC METHOD body contains the anchor (the legacy temporary pop — static
property initializers and static accessors keep it, bug-compat). LAZY levels
mean functions containing no conditions never pay the collection's typing
calls (the legacy pass collected for every walked function — part of its
38,986 getTypeOfExpression calls). (3) Emissions dispatch at
If/While/Do/For/ExpressionStatement/ConditionalExpression/ArrowFunction
enters; the ArrowFunction expression-body case anchors the scope rebuild at
the BODY so the arrow's own param level is included (pinned by the
`arrow expression-body sees its OWN param scope` negative control). (4)
`currentFlowGraph`/`currentCheckFileName` save-set-restored around EACH
dispatch, never walk-wide (the currentArithmeticFlowGraph 78-test gotcha) —
note the DELETED pass used to LEAK last-file currentCheckFileName/
currentFileLocals to downstream passes; the 8-profile A/B confirms no
downstream pass depended on it. VERIFIED: 36 pins ALL green against the OLD
walker first; suite 10,872 → 10,908 (0 regressions); listAll error lines
IDENTICAL on ALL 8 profiles (530a vs 529a, re-verified from files after the
A/B script's 10-min background timeout truncated the first server run — an
orphaned java kept an open fd on the output file, kill + solo re-run);
bench 25,956 ms self / 46 errors unchanged (+1.3% vs the 529 single run, in
the box-drift band; RSS 1,224 → 1,049 MB). NEXT: INV.4(d) walker 2 —
checkArithmeticOperandTypes (309 ms), decompose when reached.**

**Round 529 (2026-07-16) — INV.4(c)(iv): checkTypeUsedAsValue onto the spine —
INV.4(c) COMPLETE; the family's recursive walkers + ScopeNameSet are DELETED
(~700 lines).** The last name-resolution-pair member (TS2693/TS2708/TS2689/
TS2585/TS18042, 734 ms in the round-520 table) migrated in ONE commit — much
smaller than checkUnresolvedNames because the design fits the family: (1) REACH
is a memoized 3-STATE ancestor classifier (`spineTavStatus` over `spineTavEdge`
— the deleted dispatch arms verbatim, incl. the corpus-tuned round-42
NON-descent into for/while/do/switch/try bodies, class accessors/EXPRESSIONS,
shorthand properties, objlit-method param defaults, and the
while-body-labeled-empty parse-recovery quirk); unlike batch 4's boolean memo
the verdict carries the plain-`=`-LHS TS2708 suppression as a status
(REACHED_NONS, minted on the Equals-left edge and inherited downward — the old
walker threaded an EMPTY namespace-set into the whole LHS subtree;
checkConstAssignment owns the assignment-target TS2708, pinned as
exactly-once). (2) SCOPE is pull-based memoized levels (`tavLevelAt`/
`tavLevelFor` keyed by owner nodeId) — the family's surveys are
position-independent (params all registered before any default walks per
B86.9, hoisted names list-wide), so none of the batch-1 lazy-staging machinery
is needed; the ONE order-sensitive spot (an objlit method's computed NAME is
checked in the OUTER scope, before the method's params exist) is a
came-from-child owner skip in the ascent. Level surveys fold the old
two-set-per-boundary structure into one level each (module survey + statement
hoist survey merged — emission-equivalent because every rule consults values
FIRST); typeOnly-gate ordering differences are invisible for the same reason.
(3) The file survey (TS18042 emission + currentForwardLibTypeNames + the
Array/Symbol/Promise lib synthesis) moved verbatim into `tavBuildFileRoot`,
built eagerly per file in checkSpine's loop. (4) TS2689 classifies at the
CLASS enter (`spineTavClassHeritage`) and marks `spineTavHeritageSkip` before
the heritage subtree walks — the deleted either/or (TS2689 OR the generic
expression walk) reproduced exactly; `tryClassifyExtendsInterface` retained,
re-keyed from ScopeNameSet onto the level chain (it now runs with
currentFileLocals = the CURRENT file per the spine convention — the old pass
ran with whatever the previous pass left; 8-profile A/B showed zero drift).
VERIFIED: 40 pins ALL green against the OLD walker first, then against the
migration (Inv4SpineBatch20Test — one pin flipped during pre-verification:
`N = 5` fires ONE TS2708 from checkConstAssignment, so the pin asserts
exactly-once, the no-double-emit invariant); suite 10,832 → 10,872 (0
regressions); listAll error lines IDENTICAL on ALL 8 profiles (529a vs 528a);
bench row recorded. NEXT: INV.4(d) — the mid-weight stateful walkers
(checkUncalledFunctionsInConditions 506 ms, checkArithmeticOperandTypes
335 ms, checkImplicitReturns, checkArgumentCounts, checkDefiniteAssignment),
decompose per walker when reached.**

**Round 528 (2026-07-16) — INV.4(c)(iii) batch 5: the TYPE walk swap — (c)(iii)
COMPLETE, the checkUnresolvedNames family's recursive walkers are ALL DELETED.**
checkUnresolvedInType(Core), the batch-4-retained checkUnresolvedInExpr(Core),
and the JSX attribute/child helpers (~660 lines) are gone; type positions
self-emit at their own enters. Design difference from batch 4: the expression
classifier reproduced ROOT positions statically, but the type family's ~31
dispatch sites carry heterogeneous gates (sig-dispatch checkTps/exprOwned
asymmetries, declare-class early return, JSDoc-typeFromJSDoc skip, interface
member kinds) — so the ROOTs are MARKED instead: every site that used to call
the walker now calls `spineUResMarkTypeRoot(type)` (a per-file nodeId ByteArray
+ an identity-list fallback for unindexed trees), which is sound because every
site runs at the owner's or a dominating child's enter — strictly before the
marked subtree walks — and keeps the sites the single source of truth.
`spineUResTypeChecked` walks ancestors over `spineUResTypeDescends` edges (the
deleted walker's recursion arms verbatim: TypeReference args / array / tuple /
union / intersection / parens / operator / indexed-access / mapped (constraint
via the TypeParameter edge — outer scope comes from the level's deferred
activation) / conditional / fn-type TPs+params+return / rest / optional /
named-tuple-member / type-literal member positions gated to TypeLiteral
parents; TypeQuery.exprName, InferType, ImportType, LiteralType, and the
always-empty TemplateLiteralType spans deliberately non-descend). Self-emitting
kinds in `spineUResDispatch`: TypeReference (checkTypeNameResolved +
checkTypeArgCount + checkUtilitySignatureConstraint + TS1099), IndexedAccessType
(checkIndexTypeValidity + checkBadArrayStringLiteralIndex), TypeQuery,
FunctionType/ConstructorType (checkUnusedDestructuredRenames), TypeLiteral (the
member computed-name emissions TS2690/TS2693/TS2464 in one batch at the
literal's enter). The last recursion-owned expression region — a TL member's
computed NAME — became an expression ROOT gated on `spineUResTypeChecked(tl)`
(`spineUResTypeLiteralMemberNameEdge`), which flips `exprOwned` true for
functions inside computed names so the existing fn-sig dispatch covers what the
retained walker's arrow/fn-expr arms used to; `checkJsxTagName` reduced to its
Identifier arm (property-access tags resolve their root identifier via the JSX
descent edges). The interface arm's inline methodScope/setScope constructions
dissolved into marks (the batch-1 maintained levels provide the identical lazy
staging, already audited per-Identifier). VERIFIED: suite 10,804 → 10,832 (+28
Inv4SpineBatch19Test — roots/descent/staging/computed-names/regions, ALL 28
verified identical on the OLD walker via `git stash` A/B — a pure
reach-preserving migration; 0 regressions); listAll error lines IDENTICAL
on ALL 8 profiles (528a vs 527a; header-only timing diffs); bench 24,884 ms self / 46 errors unchanged (-8.0% vs the 527 single run - direction consistent with deleting the double walk, magnitude within the box-drift band). NEXT: (c)(iv)
checkTypeUsedAsValue — the last piece of INV.4(c).

**Round 527 (2026-07-16) — INV.4(c)(iii) batch 4: the EXPRESSION walk swap —
expression positions self-emit on the spine.** The recursive expression
walker's reach is reproduced STATICALLY: `spineUResExprEdge(parent, child)`
classifies every edge as ROOT (a position the batch-2/3 arms called
checkUnresolvedInExpr on — statement expression slots, var/enum/class-member
initializers, class-member computed names + decorators, class-decl decorators
(outer scope via the deactivated level), param initializers/decorators per
owner kind, heritage types under class-decl/interface owners), DESCEND (an
edge the legacy walker recursed through — incl. objlit structure, arrow
param-defaults/expression bodies, class-EXPRESSION heritage chains, JSX
containers/attributes/property-access tags), or NONE (names, types, closing
JSX tags, for-in/of binding positions, type-literal member names);
`spineUResExprChecked(node)` walks the ancestor chain over those verdicts,
memoized per file by nodeId (a 6k-term binary chain stays linear — the
backfill writes the whole walked chain). Emissions dispatch per node kind in
spineUResDispatch (Identifier → checkIdentifierResolved; BinaryExpression →
NaN check + the destructuring-pattern marks laid at the assignment's enter,
strictly before the shorthand TS1312/TS18004 selection reads them;
ShorthandPropertyAssignment → the TS1312-vs-TS18004 branch; as/satisfies/
type-assertion target types + call/new type args → checkUnresolvedInType;
ClassExpression → TS2864/arity/heritage-typeArgs gated on exprChecked;
Jsx* → Identifier-tag intrinsic rule + factory check), all through
spineUResEmit so suppression/filter/currentFileLocals-null state applies at
the node's own position, with scopes provided by the batch-1 maintained level
chain by construction (switch-inactive, decorator-deactivation, method
pre-population views included). spineUResFnSigDispatch reduces to TYPE
positions with a `checkTps` flag reproducing the legacy asymmetry (fn-decls/
class-methods/arrows check TP constraints/defaults; fn-exprs/objlit-methods
only REGISTER TPs); arrow/fn-expr/objlit-method levels carry `exprOwned` so
functions inside recursion-owned regions keep the retained walker's arms.
TS2422's implements-bare-TP `continue` became the per-file
`spineUResHeritageSkip` nodeId set (registered at the class arm before the
heritage subtree walks). The recursive checkUnresolvedInExpr(Core) is
RETAINED solely for the type walker's TypeLiteral computed-property-name
positions (+ its internal recursion incl. the JSX helpers) — the types batch
deletes both together. VERIFIED: suite 10,767 → 10,804 (+37
Inv4SpineBatch18Test — ALL pre-verified against the OLD walker, a pure
reach-preserving migration incl. the legacy quirks pinned negative: fn-expr/
objlit-method TP constraints unchecked, objlit method computed names
unchecked, with-body/declare-fn suppression, for-in LHS unchecked,
property-access NAMES unchecked; 0 regressions); listAll error-line SETS
IDENTICAL on ALL 8 profiles (527a vs 526a; compiler raw order shifts 12 line
pairs — the moved emission positions); bench 27,057 ms self (−1.3% vs 526, in
band), error histograms unchanged on all 8 (46×7 + harness 94, all
env-legit). NEXT: (c)(iii) batch 5 — types (the checkUnresolvedInType
walkers; deletes the retained expression walker + JSX helpers with it), then
(c)(iv) checkTypeUsedAsValue.**

**Round 526 (2026-07-15) — INV.4(c)(iii) batch 3: the class-element walk swap —
`checkUnresolvedInClassElement` is DELETED (101 lines).** Class-member signature
emissions dispatch on the spine against the batch-1 maintained member levels
(identical construction to the legacy per-member scopes): property/method
DECORATORS + computed NAMES at the member's ENTER — the level is unpopulated at
that moment, which IS the legacy pre-registration view (B98.r111: a computed
method name must not see its own TPs/params); TP-constraint/param/return-type
positions at child enters via the shared `spineUResFnSigDispatch` (extracted
from the batch-2 FunctionDeclaration dispatch), with per-member-kind flags
reproducing each legacy arm's EXACT coverage — methods/constructors check param
decorators + initializers, function declarations initializers only, class
set-accessors param TYPES only, get-accessors just the return type; index
signatures at enter in the CLASS scope (the legacy arm used classScope — no
level). ALL dispatch gated to class decl/expr parents (`spineUResIsClassMember`)
— interface members stay with the batch-2 interface handler, objlit/type-literal
members with their still-legacy walkers (no double-emission; pinned). The member
loops are removed from spineUResClassDeclaration AND the expr walker's
ClassExpression arm (class-expression members ride the same dispatch — their
levels carry ctorParamNames). VERIFIED: suite 10,747 → 10,767 (+20
Inv4SpineBatch17Test — the B98.r111 emission pin, per-kind coverage pins incl.
set-param-TYPES-only and static-property-no-ctorParamNames, exactly-once counts
for class AND interface members, declare-class members staying silent; 0
regressions); listAll RAW-identical on 7/8 profiles, harness SET-identical with
the expected emission-order movement (member signature positions vs method-body
positions); bench 27,402 ms self (+6.1% = box drift — the same-procedure listAll
pair ran 526a FASTER, 27.3 s vs 525a's 28.3 s), 46 errors unchanged. NEXT:
(c)(iii) batch 4 — expressions (checkIdentifierResolved positions +
arrow/fn-expr/objlit signature dispatch), then types, then JSX.**

**Round 525 (2026-07-15) — INV.4(c)(iii) batch 2: the checkUnresolvedNames
STATEMENT-LEVEL walk swap — the recursive statement walkers are DELETED.**
`checkUnresolvedInStatements`/`checkUnresolvedInStatement(Core)` (~770 lines,
the family's driver + per-statement dispatcher) are gone; per-statement
dispatch lives in `spineUResDispatch` (called from spineWalkFile right after
spineUResEnter, so a statement's own maintained level — for headers, the
switch shared-clause scope, class/interface/enum/namespace scopes — is already
pushed). Mechanics: (1) simple statement kinds (var/expr/return/if/while/do/
for/for-in/for-of/switch-subject/case-expr/throw/with-expr/export/export=/
import-equals) check their IMMEDIATE expression/type children against
spineUResScope(), the still-legacy expression/type/class-element walkers doing
the recursion; (2) FunctionDeclaration signature positions dispatch at CHILD
enters inside spineUResOnDirectChild (TypeParameter child → constraint/default
with TPs-but-no-params, Parameter child → type/init with params, return-type
child → type) so the batch-1 lazy population provides the exact legacy
staging; (3) the class/interface arms moved verbatim into
spineUResClassDeclaration/spineUResInterfaceDeclaration (classScope = the
level spineUResEnter pushed — identical construction; outer scope via
spineUResOuterScope), the ImportEquals arm extracted verbatim as
checkUnresolvedInImportEquals. Under-visits reproduce as SUPPRESSED-REGION
levels (`UnresolvedSpineLevel.suppressed` + a counter): a WithStatement body
(pass-through level flipped at the statement child), a skipped
outside-function return (module files always; script files unless the
expression contains this/a call), and `declare` functions/classes (level
marked at dispatch; declare-class heritage keeps its TS2314 arity check
first); the declare-module body post-filter became the `filter2304` level
flag — `spineUResEmit` wraps EVERY dispatch site (suppressed → no-op; filter →
keep only TS2304/TS2552 from that batch — per-diagnostic, so slicing the
legacy one-shot filter per dispatch yields the identical set). spineUResEmit
also NULLS `currentFileLocals` around family emissions — the legacy pass ran
with it null (the INV.3(d)(ii) "unscoped class" note) while checkSpine sets it
per file; without the null, the strict-reserved heritage TS2702/TS2304 split
and the TS2833/TS2503 qualifier resolution would take different paths. The 10
statement descents in the expression/class-element walkers are CUT (arrow/
fn-expr/objlit-method/get/set bodies + class method/ctor/get/set/static-block
bodies) — the spine reaches nested bodies with the batch-1-audited maintained
scopes. `checkUnresolvedNames` remains ONLY as the declarationOnly-mode driver
(checkSpine doesn't run there): it walks each file with every non-family spine
handler disabled (`spineUResOnly`), deliberately NOT computing the type-lib
strip (the legacy declarationOnly path never ran checkTypeLibraryEntryPoints);
the normal-mode `pass("checkUnresolvedNames")` dispatch is removed. KNOWN
semantic deltas accepted (suite/listAll-arbitrated, none observed): statement
nesting no longer consumes checkDepth (the expression/type walkers keep their
own guards), and within-file emission ORDER shifts (object-literal/class
method bodies now emit after all signature-position checks of the enclosing
statement; params before return type) — this re-orders the TS2552
10-per-program suggestion budget within a file in principle. VERIFIED: suite
10,713 → 10,747 (+34 Inv4SpineBatch16Test — with-body/return/declare-region
suppression pins with negative controls, declare-module filter keep/drop,
TP-constraint-vs-params staging, ES5 hoist, switch shared-clause scope,
emitDeclarationOnly still reporting; 0 regressions); listAll error-line SETS
IDENTICAL on ALL 8 profiles (525a vs 524a — every file:line:col:code:message
equal; within-file PRINT order shifts per the emission-order note above, the
first (c)(iii) batch where raw-order byte-identity is deliberately traded —
the corpus suite gates the SORTED output byte-identical); bench 25,823 ms
self (−2.7% vs 524 — deleting the legacy walk pays back batch 1's doubled
scope maintenance), 46 errors unchanged (all env-legit offline artifacts).
NEXT: (c)(iii) batch 3 — the class-element walker's positions onto the spine
(member signature dispatch at child enters against the maintained member
levels), then expressions/types/JSX.**

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
  - [x] **INV.4(b) Tail-pass batches.** Migrate the 474-pass sub-100 ms tail
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
    profiles (520b vs 520a). --passTiming RE-MEASURE (round 520, post
    batch 14): checker-init 20.0 s (21.6 s pre-batch-8); spine 718 ms
    carrying ~34 migrated passes; 459 passes recorded (~55 dispatches
    removed since INV.0's 514); top-3 giants unchanged
    (checkPropertyAccess 3.53 s / checkTypeAssignability 2.33 s /
    checkCallExpressionTypes 2.06 s = 7.9 s); the next-biggest non-giant
    passes are EXACTLY the INV.4(c) pair — checkUnresolvedNames 744 ms +
    checkTypeUsedAsValue 739 ms — then the (d) cohort
    (checkUncalledFunctionsInConditions 454 ms, checkArithmeticOperandTypes
    335 ms, checkImplicitAnyParameters 279 ms); the remaining zero-typing
    tail is mostly sub-100 ms each (checkAwaitContext 93 ms — stateful
    isAsync threading + the TS1262 top-level prepass + the batch-8 TS2524
    param-default ownership boundary; decompose when reached, low yield).
    Batch 15 DONE round 521 (2026-07-14) — **(b) COMPLETE**: checkAwaitContext
    (TS1308/TS1103/TS2311/TS1262 — the threaded isAsync/enclosingFunc pair)
    became THREE rare-node enter handlers (spineCheckAwaitExpr /
    spineCheckForAwait / spineCheckAwaitCall) driven by ONE full parent-chain
    walk (`spineAwaitCtx`): the FIRST function-like boundary decides the flags
    (async modifier; the TS1356 related-info FuncRef — ctor/accessor/prop-init
    boundaries force sync), and EVERY chain step up to the SourceFile must be
    an old-walked position (parameter defaults are TS2524's, enum member
    initializers / computed names / static blocks / heritage / shorthand
    destructuring defaults / objlit ACCESSOR bodies stay unreached — pinned
    negative); ModuleDeclaration bodies are TRANSPARENT, preserving the
    namespace-inherits-module-asyncness quirk (pinned); the TS1262 top-level
    `await`-binding scan (checkTopLevelAwaitNames, retained) runs per module
    file from checkSpine's loop and sets the TS2311 suppression flag. 4 walker
    funs (~310 lines) deleted, 1 init dispatch removed. Suite +27
    (Inv4SpineBatch15Test — ALL pre-verified against the OLD walker; a pure
    reach-preserving migration); listAll error lines IDENTICAL on ALL 8
    profiles (521a vs 520b). Closure decisions: checkConflictMarkers STAYS an
    init pass (a per-file TEXT scan — the spine walks nodes; there is no walk
    to delete); checkMixinClassConstructor is TP-scope-stateful → (d). The
    remaining stateful walkers are (c)/(d) territory.
  - [x] **INV.4(c) The name-resolution pair** — COMPLETE round 529 (all four
    sub-items landed; both families' recursive walkers deleted).
    checkUnresolvedNames (846 ms) +
    checkTypeUsedAsValue (734 ms): fold their private NameScope chains into
    spine-maintained authoritative lexical state backed by the INV.2(c)
    `lexicalScopes` tables (their planned mass consumption). Decomposed round
    522 (facts verified in-code: the checkUnresolvedNames family is ~3,000
    lines — statement/class-element/expression/type/JSX walkers threading a
    `NameScope` chain whose content closely mirrors `lexicalScopes` (params,
    hoisted vars, block bindings, type params + constraints) plus per-file
    root extras (KNOWN_GLOBALS seeding, DOM/host @lib filtering, ambient-
    module-name exclusion, `declare global` handling, JS @typedef regex
    types) and walk-threaded flags (classContext / inFunction / hasArguments);
    checkTypeUsedAsValue is ~700 lines threading THREE ScopeNameSet chains
    (typeOnly/value/namespaceOnly) built from AST surveys — NOT symbol-shaped,
    and its reach is corpus-tuned per the round-42 over-emission gotcha (no
    loop/switch/try descent)). Sub-items, one commit each, every step suite-
    and 8-profile-listAll-gated:
    - [x] **(c)(i) Spine-maintained lexical scope state (infrastructure,
      always-on).** DONE round 522 (2026-07-15 — the checkbox was missed in
      that round's commit; see the round-522 session note for the full
      landing record). The walk maintains `spineCurrentScope` — push at a scope
      owner's enter (BEFORE its own handlers dispatch), pop after its leave —
      via a per-file nodeId→LexicalScope ARRAY built from
      `result.lexicalScopes` (the INV.2(b) boxing-avoidance trick; cleared by
      re-nulling only written ids); a SwitchStatement's scope is re-keyed
      onto its CLAUSE nodeIds at fill so the switch EXPRESSION stays in the
      outer scope (the binder's routing); function-body Blocks share the fn
      scope automatically (no map entry); decorator outer-scope routing is a
      documented deferred divergence (both the walk and the binder tables
      currently agree). `spineScopeLookup(name)` resolves symbols → existing
      → parent. Pinned by a test-only AUDIT mode (companion statics — tests
      cannot reach the Checker instance): every spine enter verifies the
      incremental scope against a parent-chain derivation, and identifier
      enters record `spineScopeLookup` resolutions into a trace the tests
      assert on (shadowing id splits, scope-space ids ≤ −2, switch-expression
      isolation, catch/enum/self-name/var-hoist shapes). Bench row (the walk
      gains one array probe per enter+leave).
    - [x] **(c)(ii) checkUnresolvedNames STATE swap.** DONE round 523
      (2026-07-15): the NameScope content queries (`has` / `isTypeParam` /
      `hasType` / `typeParamConstraintOf` / `hasLocalShadow` / the TS2552
      candidate pool) are hybrid — each NameScope carries `lex` (the binder
      [LexicalScope] a TRUSTED scope-owner site links; population SKIPPED
      when linked) and queries interleave the threaded sets with the lex
      levels each NameScope level introduced (`lex` down to `parent.lex`,
      preserving shadowing order). Trusted links: statement lists via a new
      `checkUnresolvedInStatements(owner)` param (Block / SourceFile / the
      FUNCTION node for fn bodies — body Blocks have no binder entry),
      for/for-in/for-of headers, catch, switch (binder keys the case scope
      by the switch nodeId — the expression is checked before linking, so
      no re-keying needed), class/class-expr/interface/type-alias TP scopes.
      Function SIGNATURE positions stay threaded (params/TPs) — the binder's
      flat fn table would leak body decls into param defaults (sub-ES2015
      pre-collect is the only path that may see them; pinned both ways).
      Untrusted levels skipped in queries: ModuleDeclaration (the walk's
      buildNamespaceScope is EXPORT-filtered; binder aliases ALL merged
      members), EnumDeclaration (EnumMember-filtered), SourceFile existing
      filtered by a per-file exclusion set (ambient external module names +
      the declare-global quirk); type-level scopes (mapped TP / infer /
      fn-TYPE params) stay threaded. Unindexed trees: every probe misses →
      legacy behavior by construction. Equivalence-gated: corpus green +
      8-profile listAll error-line-identical; walk-threaded flags stay
      threaded until (c)(iii).
    - [x] **(c)(iii) checkUnresolvedNames WALK swap.** Move the emission
      positions onto the spine (delete the ~15 recursive walkers); reach
      reproduced per the emission-direction rule (this family is (b)-class —
      direct emitters — so under-visits are reproduced via parent-chain
      gates, widenings only on a signal). Batch 1 DONE round 524 (2026-07-15):
      the spine maintains the family's NameScope chain (`spineUResStack` —
      lazy signature population / deferred-activation regions / decorator
      pre-population views reproduce the legacy walk's sequential-mutation
      order on the spine's fixed preorder; per-file ROOT shared via
      `unresolvedFileRootFor`, enabled by the `computeTypeLibResolution`
      split), audited per-Identifier against the legacy walk's scope
      fingerprints (Inv4UnresolvedSpineScopeTest, 2 deliberate-breakage
      sharpness probes). classContext / inFunction / hasArguments ride the
      maintained NameScope levels (no parent-chain re-derivation needed).
      Batch 2 DONE round 525 (2026-07-15): the STATEMENT-LEVEL walk swap —
      checkUnresolvedInStatements/InStatement(Core) DELETED; per-statement
      dispatch in spineUResDispatch against the maintained levels;
      FunctionDeclaration signature positions at child enters
      (lazy-population staging); the with-body / skipped-return /
      declare-fn+class under-visits as suppressed-region levels and the
      declare-module post-filter as the filter2304 level flag, both enforced
      by the spineUResEmit wrapper (which also nulls currentFileLocals — the
      legacy pass ran unscoped); the 10 statement descents in the
      expr/class-element walkers cut; checkUnresolvedNames retained only as
      the declarationOnly minimal driver (spineUResOnly). listAll gate:
      error-line SETS identical on all 8 profiles; within-file PRINT order
      shifts (emission order — the corpus suite gates the sorted output
      byte-identical). Batch 3 DONE round 526 (2026-07-15):
      checkUnresolvedInClassElement DELETED — class-member decorators/
      computed-names at member enter (the pre-population moment = the legacy
      B98.r111 view), TP/param/return positions via the shared
      spineUResFnSigDispatch with per-member-kind coverage flags, index
      signatures in the class scope; gated to class decl/expr parents
      (interface members stay with the batch-2 handler). Batch 4 DONE round
      527 (2026-07-16): the EXPRESSION walk swap — expression positions
      self-emit at their own enters, gated by `spineUResExprChecked` (a
      per-file nodeId-memoized ancestor walk over `spineUResExprEdge`
      ROOT/DESCEND/NONE verdicts reproducing the recursive walker's exact
      reach); NaN/shorthand/embedded-type/class-expr-heritage/JSX handlers
      dispatch per node kind; spineUResFnSigDispatch reduced to TYPE
      positions (checkTps flag = the legacy fn-expr/objlit-method
      no-constraint-check asymmetry); the TS2422 skip became the
      spineUResHeritageSkip nodeId set; arrow/fn-expr/objlit-method levels
      carry exprOwned so recursion-owned regions keep the retained walker.
      checkUnresolvedInExpr(Core) retained SOLELY for the type walker's
      TypeLiteral computed-name positions. Batch 5 DONE round 528
      (2026-07-16) — **(c)(iii) COMPLETE, all the family's recursive walkers
      are DELETED** (checkUnresolvedInType(Core), the retained
      checkUnresolvedInExpr(Core), the JSX attribute/child helpers — ~660
      lines): type positions self-emit at their own enters. Unlike batch 4's
      static classifier, the type ROOTs are MARKED — every dispatch site that
      called the walker now calls `spineUResMarkTypeRoot` (strictly before
      the marked subtree walks; the sites stay the single source of truth),
      and `spineUResTypeChecked` (per-file nodeId memo) walks ancestors over
      `spineUResTypeDescends` edges = the deleted walker's recursion arms
      (mapped-TP constraint / conditional-infer / fn-type / type-literal
      member staging comes from the batch-1 maintained levels). Self-emitting
      kinds: TypeReference (names + TS2314 + utility TS2344 + TS1099),
      IndexedAccessType, TypeQuery, FunctionType/ConstructorType (TS2842),
      TypeLiteral (member computed-name TS2690/TS2693/TS2464 in one batch at
      the literal's enter). The last recursion-owned expression region — a
      TL member's computed NAME — became an expression ROOT gated on
      `spineUResTypeChecked(typeLiteral)`, flipping `exprOwned` true there so
      the fn-sig dispatch covers what the retained walker's arms did.
      Verified: suite 10,804 → 10,832 (+28 Inv4SpineBatch19Test, ALL
      verified identical on the OLD walker via stash — pure
      reach-preserving; 0 regressions); listAll error lines IDENTICAL on
      ALL 8 profiles (528a vs 527a; header-only timing diffs); bench row
      recorded.
    - [x] **(c)(iv) checkTypeUsedAsValue.** DONE round 529 (2026-07-16): the
      recursive checkTypeAsValueInStatement(s)/checkTypeAsValueInExpr walkers
      + ScopeNameSet DELETED (~700 lines). Identifiers self-emit
      TS2693/TS2708 (+ the TS2585 forward-lib routing) at their enters, gated
      by `spineTavStatus` — a memoized 3-state ancestor-chain classifier over
      `spineTavEdge` (the deleted walker's exact dispatch arms, incl. the
      corpus-tuned NON-descent into for/while/do/switch/try bodies, class
      accessors/EXPRESSIONS, shorthand properties, and objlit-method param
      defaults; the plain-`=`-LHS TS2708 suppression is the REACHED_NONS
      status minted on the Equals-left edge — checkConstAssignment owns the
      assignment-target TS2708). The set chains stayed set-based as planned
      but became PULL-BASED memoized levels (`tavLevelAt`/`tavLevelFor` —
      the family's surveys are position-independent, so no batch-1-style
      lazy staging; the one order-sensitive spot, an objlit method's
      computed NAME seeing the OUTER scope, is a came-from-child owner
      skip). The file survey (TS18042 emission + currentForwardLibTypeNames
      included, verbatim) builds eagerly per file in checkSpine's loop
      (`tavBuildFileRoot`); TS2689 classifies at the CLASS enter and marks
      `spineTavHeritageSkip` before the heritage subtree walks (the deleted
      either/or: TS2689 OR the generic walk, never both). Suite
      10,832 → 10,872 (+40 Inv4SpineBatch20Test, ALL verified against the
      OLD walker first; 0 regressions); listAll error lines IDENTICAL on
      ALL 8 profiles (529a vs 528a); bench row recorded.
  - [x] **INV.4(d) Mid-weight stateful walkers.** COMPLETE round 541 (walkers
    1–13; the round-529 cost-ordered list is fully migrated — a fresh
    --passTiming table at round 542 shows the remaining non-giant tail is a
    flat sea of sub-160 ms mostly-stateless passes, none of them the
    scope-machinery shape this item targeted; they get absorbed
    opportunistically or superseded by (e)/(f)). Each walker moved its scope
    machinery onto the shared spine state; decompose per walker when reached.
    MEASURED cost order (round-529 --passTiming, post-(c): checker-init
    20.6 s; spine 2,247 ms carrying both name-resolution families + ~40 tail
    passes; giants unchanged 3.92/2.34/2.17 s):
    checkUncalledFunctionsInConditions 435 ms (38,986 getTypeOfExpression
    calls — a typing pass, not zero-typing), checkArithmeticOperandTypes
    309 ms (68,946 calls), checkImplicitAnyParameters 272 ms,
    checkDuplicateIdentifiers 260 ms (zero-typing), checkDefiniteAssignment
    241 ms, checkArgumentCounts 230 ms, checkUseBeforeDeclaration 205 ms,
    checkImplicitReturns 199 ms, checkConstAssignment 170 ms, then a long
    ~100–165 ms tail (checkAlwaysTruthy, checkNullUndefinedUsage, …).
    - (w1) DONE round 530 (2026-07-16): checkUncalledFunctionsInConditions
      (TS2774/TS2801) — the first (d)-class TYPING-pass migration; template
      extends (c)(iv): boolean reach classifier + PULL-BASED per-emission
      stack rebuild with per-owner memoized LAZY levels (functions with no
      conditions never pay the collection's typing calls), ambient state
      (currentFlowGraph/currentCheckFileName) save-set-restored around EACH
      dispatch never walk-wide. 36 pins (Inv4SpineBatch21Test) pre-verified
      on the OLD walker; suite 10,872 → 10,908; listAll error-line identical
      on ALL 8 profiles; ~270 walker lines deleted. See the round-530
      session note for the quirks pinned.
    - (w3) DONE round 532 (2026-07-16): checkImplicitAnyParameters
      (TS7005/TS7006/TS7008/TS7013/TS7019/TS7031/TS7032/TS7051) — the first
      DOWNWARD-CONTEXT-THREADING migration: the checkImplicitAnyInExpr
      recursion's five explicit context parameters (contextuallyTyped /
      contextualType / viaUnionWithPrimitive / ctxAnnotation / ctxViaAssignment)
      become ONE push-maintained SpineIanyCtx value with frames defined at
      EXACTLY the edges the legacy recursion passed arguments over (a missed
      edge silently LEAKS the parent context — every reached expression-position
      edge must define, even to null); the binary left-spine loop dissolves into
      per-edge rules (right operand by operator; left inherits for `||`/`??`
      only); returnCtxAnnotation + inAmbientContext pull-derive from parent
      chains; the three implicit-any scope stacks stay the same checker fields,
      pushed at body edges + recorded at declarator enters. No ambient install
      needed (slot-move A/B ×8 error-identical + corpus green pre-gated the
      move past the 4 sibling TS7xxx passes). 56 pins (Inv4SpineBatch23Test)
      ALL pre-verified on the OLD walker — incl. the reach quirks (while/do/
      switch/try/for-in/for-of bodies, call CALLEES, conditional CONDITIONS,
      as-casts, objlit accessors, static blocks all unreached) and the
      class-expression setter TS7032-with-sibling-getter bug-compat fire.
      The recursive walkers (checkImplicitAnyInStatements/-InClassElement(Core)/
      -InExpr) + the pass driver are DELETED (~770 lines); suite 10,948 →
      11,004; listAll error lines identical on ALL 8 profiles. See the
      round-532 session note.
    - (w2) DONE round 531 (2026-07-16): checkArithmeticOperandTypes — the
      first ORDER-DEPENDENT stateful migration (statement-ordered recordings
      that leak across blocks → PUSH-maintained frames on the spine, not the
      pull-based rebuild) and the first pass from AFTER the three giants
      (slot-move pre-gate found the currentParamBindingNames leak as the ONLY
      order coupling — kept pass-private now). Left-spine flatten = chain-root
      LEAVE emission; ambient install per emission/recording. The CORPUS caught
      a second, subtler coupling the profiles could not: the pass CONSUMED the
      TS2322 walk's namespace-level recording residue (qualify.ts) — reproduced
      as the pass's own ModuleBlock-gated identifier-init chain recording. 39
      pins (Inv4SpineBatch22Test); suite 10,908 → 10,948; listAll error-line
      identical on ALL 8 profiles; the pass driver deleted (the recursive
      walkers stay as checkComputedDestructKey's utility). See the round-531
      session note.
    - (w12+w13) DONE round 541 (2026-07-17): the ORDER-COUPLED pair
      checkCommaOperatorUnused (TS2695) + checkNullishPredicates (TS2871/
      TS2869 + while/do truthiness) migrated TOGETHER — the ordering
      contracts dissolve structurally (comma pre-order → ENTER anchors; np
      post-order → LEAVE anchors; while/do truthiness at the CONDITION's
      leave; same-position comma-first BY CONSTRUCTION since enters precede
      leaves — the legacy slot contract retired). Separate verbatim
      classifiers (their reach differs: objlit method bodies np-only;
      tagged-templates/yield/delete/typeof/comma-lists comma-only). 10 pins
      (Inv4SpineBatch32Test) pre-verified; suite 11,233 → 11,243; listAll
      ×8 identical; ~470 walker lines deleted. See the round-541 session
      note.
    - (w11) DONE round 540 (2026-07-17): checkNullUndefinedUsage (TS18050 +
      the for-of empty-[] TS2488 shape) — pure anchors, no ambient; the
      classifier carries the legacy checkDepth ≤ 200 STATEMENT-frame cap as
      a depth-encoded ShortArray status, with legacy frameless body Blocks
      as CARRIER blocks at the parent's depth. 12 pins (Inv4SpineBatch31Test)
      pre-verified; suite 11,221 → 11,233; listAll ×8 identical; ~230 walker
      lines deleted. See the round-540 session note.
    - (w10) DONE round 539 (2026-07-17): checkAlwaysTruthy (TS2872/TS2873 +
      TS1345/TS2845 + the `!`-operand falsy check) — frameless: both walk
      states pull-derive (the never-reset B69.11 inArrowExprBody flag; the
      if-else-chain prevTruthy via elseStatement ancestor links); per-chain-
      node dispatch at IfStatement enters. Condition-reach asymmetry pinned:
      if/while/do/ternary condition sub-exprs never walked, FOR conditions
      fully walked. 13 pins (Inv4SpineBatch30Test) pre-verified; suite
      11,208 → 11,221; listAll ×8 identical; ~230 walker lines + the
      threading field deleted. See the round-539 session note.
    - (w9) DONE round 538 (2026-07-17) — checkConstAssignment (TS2588/TS2628/TS2629/TS2630/TS2708 +
      TS2540 readonly writes + TS2357 inc/dec targets + scanRegExpFull's
      TS1538/regex-grammar family riding the same walker). SCOUTED
      (2026-07-17, in-code): the most stateful (d) walker yet — a w2+w5
      hybrid. (1) constNames is a statement-ordered LIVE MutableMap per
      activated list (collect const/class/enum/fn/ns THEN check, let/var
      REMOVES an inherited name) → DA-style core frames with per-statement
      collect steps at direct-child enters; spawn rules are ASYMMETRIC:
      Block/switch-clause/try-blocks/ModuleBlock/class-member bodies COPY
      the top frame's live map, FunctionDeclaration/fn-expr/arrow-Block/
      IIFE-arrow-Block bodies get a FRESH EMPTY map (an outer const is NOT
      flagged inside a fn body — bug-compat), SourceFile seeds from the
      program-wide sharedConsts overlay (script files only; module files
      empty). (2) The For header is an EDGE overlay: condition/incrementor/
      body see outer+header consts, the INIT EXPRESSION sees outer only.
      (3) currentClassForThis/currentThisMemberIsCtorDirect pull-derive from
      the ancestor chain: per-member staticness, Constructor→ctorDirect,
      property-initializer→ctorDirect=false, fn-expr NULLS the class, arrow
      keeps it with ctorDirect=false, and an IIFE-ARROW is TRANSPARENT to
      ctorDirect (the CallExpression arm's immediatelyInvokedArrowCallee).
      (4) FunctionDeclaration bodies install currentLocalTypes/
      currentParamBindingNames copies + populateParameterLocalTypes (B116 —
      fn DECLS only, not methods/fn-exprs/arrows) — cumulative through
      nested fn decls; per-anchor pull-rebuild with per-owner memo (w1
      template). (5) This is a TYPING pass (checkReadonlyAssignmentTarget
      resolves receiver types) — slot-move pre-gate with the CORPUS
      mandatory; check for diagnostics-list probes before choosing
      enter-vs-leave dispatch (the round-537 lesson). Anchors: assignment-op
      BinaryExpressions (left-spine loop — emissions are per-spine-node, at
      each binary's own reach), ++/-- Prefix/Postfix, RegularExpressionLiteralNode.
      LANDED as scouted (enter-dispatch — no diagnostics probes); 19 pins
      (Inv4SpineBatch29Test) pre-verified on the OLD walker; suite 11,189 →
      11,208; listAll ×8 identical; ~330 walker lines deleted. See the
      round-538 session note.
    - (w8) DONE round 537 (2026-07-17): checkImplicitReturns
      (TS7030/TS2355/TS2366/TS2378/TS7023 + arrow concise-body TS2322).
      SLOT-MOVE PRE-GATE LANDED AND VERIFIED (intact pass at the spine slot;
      corpus 11,170/0 + listAll ×8 error-line identical) — the ambient
      residue at the spine slot is proven equivalent, and the pass stays
      BEFORE checkTypeAssignability, whose end-of-pass filter suppresses
      TS7030 at its own TS2322 positions (it EXPECTS this pass's TS7030s to
      exist — do not move it past the giants). SCOUTED migration design
      (w1-template): 4-state reach classifier (STMT/EXPR/MEMBER/NONE) over
      walkStmtForImplicitReturns/walkExprForImplicitReturns arms; anchors at
      FunctionDeclaration/MethodDeclaration/GetAccessor/FunctionExpression/
      ArrowFunction enters (the retained check*ForImplicitReturn bodies
      minus their trailing walkForImplicitReturns recursion); per-dispatch
      ambient install of implicitReturnFlowGraph + currentCheckFileName +
      the PRE-SPINE resting currentFileLocals/currentFunctionParams
      (checkGetAccessorForImplicitReturn reads currentFunctionParams'
      RESTING value — it never sets it; capture both at checkSpine entry
      like spineArithBase). Per-file gate: !isDts && (checkJs || !(.js|.jsx))
      — NOTE .mjs/.cjs are NOT skipped by the legacy gate (spineIsJsLike is
      the wrong predicate). Sharp reach quirks to pin (verified in-code):
      GENERATOR bodies never descend (the anchors early-return before their
      trailing recursion); class-DECL Constructor/SetAccessor bodies and
      class-DECL PropertyDeclaration initializers unreached while class-EXPR
      prop inits ARE reached; objlit SetAccessor bodies unreached; arrow
      CONCISE (expression) bodies never descend (both annotated and not);
      return/throw/export= EXPRESSIONS and if/while conditions and for
      headers unreached in statement position; GetAccessor sentinel body
      (pos == -1) skips. LANDED: anchors dispatch at LEAVE (the 17.135
      TS2304/TS2314 diagnostics-list probes must see the annotation's own
      spine emissions — enter-dispatch over-emitted TS2355 on exactly 2
      corpus tests); 19 pins (Inv4SpineBatch28Test); suite 11,170 → 11,189;
      listAll ×8 identical; ~140 walker lines deleted. See the round-537
      session note.
    - (w7) DONE round 536 (2026-07-17): checkUseBeforeDeclaration (TS2448/
      TS2449/TS2450 + TS2454 co-emit + static-init TS2729) — 5-state reach
      classifier + per-list-owner memoized blockScopedDecls; the retained
      BOUNDED checkUBDForwardRefs walk anchors at DIRECT statements of
      activated lists (it recurses if/labeled itself — nested statements
      never re-anchor); loop-header self-ref checks re-host at For/ForIn/
      ForOf enters. TWO order couplings resolved by slot placement:
      populateAmbientCyclicBaseClasses (the TS2449 suppression-set producer)
      moved BEFORE the spine, and the TS2454 co-emits becoming visible to
      checkDefiniteAssignmentViaFlowGraph's dedup scan measured INERT
      (slot-move pre-gate: corpus green + listAll ×8 identical). Cross-file
      leg stays a separate pass at the spine slot. 33 pins
      (Inv4SpineBatch27Test) ALL pre-verified on the OLD walker first run;
      suite 11,137 → 11,170; listAll error-line identical on ALL 8 profiles;
      ~195 walker lines deleted. See the round-536 session note.
    - (w6) DONE round 535 (2026-07-17): checkArgumentCounts (TS2554/TS2555/
      TS2575) — the first DEPTH-valued reach classifier (the legacy
      argCountDepth recursion counter reproduced per edge, ≤200 cap; binary
      right-spine absorption = no depth) and the first MAP-valued pull-based
      downward context (funcParams/ctorParams/fnDepth/superCtor rebuilt at
      each emission from per-list-owner memoized levels — sound because every
      list overlay reads its WHOLE statement list). TRAP: a pull rebuild that
      RE-ENTERS itself through its own memoized levels must reuse its shared
      ascent buffer MARK-based, never clear()-based (the for-of loop-shadow
      edge silently dropped; one pin caught it). Producer sibling
      checkSpreadNonIterableIntoFixedArity moved BEFORE the spine. 46 pins
      (Inv4SpineBatch26Test) ALL pre-verified on the OLD walker; suite
      11,091 → 11,137; listAll error-line identical on ALL 8 profiles;
      ~650 walker lines + 3 threading fields deleted. See the round-535
      session note.
    - (w5) DONE round 534 (2026-07-16): checkDefiniteAssignment (the SET-based
      TS2454 pass) — the first per-statement-LIST ordered walker with a
      DOWNWARD leak context: legacy list activations become CORE FRAMES
      (pushed at SourceFile/fn-body/Block/ModuleBlock owners, per-statement
      steps at direct-child enters — the collect/checkUses/mark/nestedLeak
      loop body retained verbatim), the recursion walkers become a memoized
      10-state ancestor classifier (spineDaStatus/spineDaEdge), and the
      downward leak set is READ from the top frame's per-statement
      currentLeak via LEAK-flavored statuses (sound: leak-preserving paths
      never cross a core spawn). The flow-graph siblings (ViaFlowGraph
      dedups one-directionally against this pass) moved to right after the
      spine, preserving set-pass-first order; slot-move pre-gate ×8
      identical. 39 pins (Inv4SpineBatch25Test) pre-verified on the OLD
      walker; suite 11,052 → 11,091; listAll error-line identical on ALL 8
      profiles; ~370 walker lines deleted. See the round-534 session note.
    - (w4) DONE round 533 (2026-07-16): checkDuplicateIdentifiers (TS2300
      family) — the lightest (d) shape: STATELESS (the two
      checkDuplicateDeclarations flags derive at the anchor) and ZERO-TYPING,
      so the migration is a pure boolean reach classifier
      ([spineDupIdReached] over [spineDupIdEdge], the deleted
      checkDuplicatesInStatement(s)/InExpr/InClassElement arms verbatim) +
      anchor dispatch at node enters running the RETAINED bounded leaf
      utilities; class/objlit MEMBER emissions dispatch uniformly at the
      member's own enter (objlit edges never admit accessors, so a reached
      SetAccessor/Constructor is class-only). Per-file top-level scans ride
      checkSpine's loop in the legacy within-file order, each wrapped in a
      currentFileLocals=null install (the legacy pass ran with it null —
      checkClassNamespacePrototypeConflict's `?: globals` consult makes it
      load-bearing). Slot-move pre-gate: error-line-identical ×8 (no residue
      coupling). 48 pins (Inv4SpineBatch24Test) ALL pre-verified on the OLD
      walker first run; suite 11,004 → 11,052; listAll error-line identical
      on ALL 8 profiles; ~215 walker lines deleted. See the round-533
      session note.
  - [ ] **INV.4(e) The top-3 giants.** checkPropertyAccess (3.66 s @ round-542
    table) → checkTypeAssignability (2.62 s) → checkCallExpressionTypes
    (2.13 s) — one at a time (together ~38% of checker-init; 458k of 595k
    getTypeOfExpression calls). **g1 SUB-PLAN (scouted round 542, in-code):
    checkPropertyAccess's walker core is compact (checkPropertyAccessInStatement
    293 lines / 22 arms + checkPropertyAccessInExpr 414 lines / 26 arms —
    the mass is in the called emission machinery, retained as leaf
    utilities). State model per the (d) templates: (1) statement-ordered
    currentLocalTypes recordings (w2 arith shape — PUSH-maintained frames,
    PASS-PRIVATE on the spine per the w2 currentParamBindingNames lesson;
    the pass also does applyBodyLocalShadowing at fn-decl/arrow/fn-expr
    boundaries per the round-447 gotcha — those calls stay in the frame
    installs); (2) contextualType downward threading with clear-before-body
    edges (w3 iany shape — push ctx with frames at exactly the legacy
    assignment edges); (3) enclosingClassType threaded param + inStaticClassMethod
    (pull-derivable from the member chain); (4) propertyAccessEnclosingNamespaces
    (its OWN stack, deliberately separate from inferenceNamespaceStack per
    the two-stacks gotcha — push at ModuleDeclaration edges); (5) per-file
    ambient currentFileLocals/currentCheckFileName/currentFlowGraph/
    currentLexicalScopes (per-dispatch install, w1 discipline — NOTE
    currentFlowGraph walk-wide is the 78-test hazard, so install around
    emissions only). SUB-STEPS, one commit each: (g1a) slot-move pre-gate —
    move the intact pass from its slot to the spine slot; this REORDERS it
    before the other two giants, so expect residue coupling (the w2
    corpus-only lesson): listAll ×8 + FULL corpus mandatory; if the
    pre-gate diffs, bisect the coupling with restore-after-pass probes
    before any migration. (g1b) pins (~50, the largest batch yet — reach
    quirks per arm; pre-verify on OLD). (g1c) the migration. (g1d) after
    g1 lands, re-measure; g2/g3 decompose the same way when reached.**
    **g1a MEASURED (round 542, both experiment directions run and REVERTED —
    the working tree keeps the legacy giant order): the giants are
    order-entangled in BOTH directions, and the couplings are CORPUS-ONLY
    (all 8 profiles sorted-error-line-identical in both experiments).
    (1) checkPropertyAccess moved before checkTypeAssignability →
    noImplicitAnyForIn loses a TS7053: the element-access receiver's type
    (`var k1 = x[i]` → `{}`) comes from the assignability walk's
    currentLocalTypes RESIDUE — the w2 residue class; fix = the pass records
    its own receiver types (w2's own-recording template).
    (2) checkTypeAssignability moved to the spine slot →
    typeArgumentDefaultUsesConstraintOnCircularDefault's TS2353 display
    flips `Test<any>` → `Test` (aliasDisplayMap/declaredTypes first-touch)
    AND relationComplexityError gains 2 FP TS2322 (relation-cache/
    complexity-budget state) — CACHE first-touch couplings against the small
    passes between the spine and slot 64, each needing a root-cause before
    the giant can move. NEXT STEP for g1: bisect WHICH intermediate pass's
    first-touch the two failures depend on (binary-search the slot
    position), then either neutralize the dependency (pass-own state /
    explicit cache warm) or migrate the giant IN PLACE (dispatch from the
    spine but buffer emissions to the legacy slot — a new template).**
    **g1a BISECT COMPLETE (round 543) — STRATEGIC FINDING, the (e) tier is
    BLOCKED ON INV.5: three targeted probes pinned both g1a' couplings to
    exactly TWO small producer passes (checkTypeParameterDefaults — its
    first-touch of the circular-default alias caches the `Test<any>`
    display; checkTemplateUnionIntersectionComplexity — its TS2859
    complexity verdicts make the giant's relation SKIP the failing
    comparison), but applying the established producer-move pattern (both
    before the spine + the giant at the spine slot) dragged a coupling
    CHAIN: 5 NEW generic-family corpus failures
    (genericsWithoutTypeParameters1, genericRecursiveImplicitConstructor-
    Errors3, noTypeArgumentOnReturnType1, conflictingTypeParameterSymbol-
    Transfer, returnTypeTypeArguments) + a harness listAll diff — the moved
    producers have their OWN upstream first-touch dependencies. Buffered
    emission does not help either: the COMPUTATION (type resolution into
    shared caches) is what is order-sensitive, not the emission. CONCLUSION:
    the giants cannot migrate by slot manipulation while nodeTypes/
    declaredTypes/aliasDisplayMap/relation caches are first-touch-order-
    sensitive. The (e) tier's prerequisite is INV.5's cache re-keying
    (`nodeTypes` keyed (node, mapper) — always valid; canonical type
    identity), which makes resolution order-INSENSITIVE. RE-SEQUENCED:
    work INV.5 next; return to (e) when the caches are order-free. All
    probe edits REVERTED — the tree keeps the legacy giant order.**
  - [ ] **INV.4(f) The two unlocked soundness wins.** Once one authoritative
    walk state exists: the per-node expression-type cache (594,779 calls over
    ~221,844 distinct nodes = ×2.6 recompute), and flow narrowing folded into
    reference typing once (84,469 depth-0 walks, 68% from property access).
    Re-measure against the ≤10 s single-threaded compiler-profile target.
- [ ] **INV.5 Canonical types + explicit instantiation** (absorbs M5.2/M5.3;
  NOW THE ACTIVE ARC ITEM — the round-543 g1a bisect proved the INV.4(e)
  giants are blocked on exactly this: first-touch-order-sensitive shared
  caches). Decomposed round 544, one commit each, every step suite +
  listAll-×8 gated:
  - [x] **INV.5(a) Union/intersection interning.** DONE round 545 (see the session note — landed with the ternaryOfArrayLiterals gate extension after the round-544 near-miss). `getUnionType` (Checker.kt
    ~103k, "mints a fresh Type.Union(sorted) with a new id — does NOT
    intern") + `getIntersectionType` intern by sorted member-id key (the
    `referenceCache` pattern; preserves display member order by keeping the
    FIRST-built instance). Directly serves order-insensitivity: an interned
    union has the same id regardless of which pass builds it first. KNOWN
    HAZARDS (from the gotcha corpus): (1) aliasDisplayMap is id-keyed — an
    interned union SHARED across contexts must not receive one context's
    alias name (the singleton-intrinsic display-corruption hazard
    generalized; union alias display already has the structural
    `unionAliasStructural` map — union registrations in aliasDisplayMap may
    need to move there entirely); (2) the id-only dedup gotcha (duplicate
    structurally-identical members) is UNCHANGED by interning — do not
    conflate the two; (3) the round-424 structural wash-gate workaround
    stays correct (it stops RELYING on fresh ids but never assumed them);
    (4) relation-cache/cycle-stack behavior only gains hits (same-id
    identical pairs). Verify: suite + listAll ×8 + re-run the round-542/543
    probe experiments to measure how much of the giant entanglement
    dissolves.
    **FIRST ATTEMPT (round 544, REVERTED): a minimal interning of both
    canonical constructors (CheckerState caches by member-id key; unions by
    sorted order, intersections in-order) measured CORPUS 100% GREEN
    (11,243/0) with EXACTLY ONE new FP, identical on all 8 profiles —
    watch.ts:533:19 TS2322 `(string | DiagnosticMessage)[]` ⊄
    `DiagnosticAndArguments` (the round-446 VARIADIC-TUPLE alias family).
    Remarkably contained for a change canonicalizing every union in the
    program — the hazard list's display fears did NOT materialize; the one
    regression is a relation/suppression path keyed on union identity
    (candidates: a relation-cache FALSE shared across contexts, an id-keyed
    side channel hitting a shared instance, or the
    arrayLiteralSatisfiesTupleTarget suppression's engine fallback). NEXT:
    root-cause with a targeted probe (temporary Diagnostic-init stack-trace
    probe keyed on code=2322 + the watch.ts:533 start per the round-472
    recipe), fix the one path, re-land.**
    **PROBE RE-RUN (round 546, post-(a)): the g1a' couplings PERSIST under
    canonical union identity (both typeArgumentDefaultUsesConstraintOn-
    CircularDefault and relationComplexityError still fail with the giant at
    the spine slot; probe reverted). The residual first-touch sensitivity is
    NOT union-identity — it lives in declaredTypes/aliasDisplayMap
    resolution TIMING (the Test<any> display) and the relation/complexity
    verdict state — i.e. exactly the (b)/(c) territory (explicit mappers +
    keyed nodeTypes). The INV.5 sequencing holds; continue with (b).**
    **PROBE RE-RUN 2 (round 548b, post-(c)): both g1a' couplings STILL
    persist — the residual first-touch state is specifically (1)
    `declaredTypes` (SYMBOL-keyed alias resolutions — the Test<any>
    display; a different cache from nodeTypes) and (2) the TS2859
    relation/complexity verdict state. The giant unblock therefore needs a
    declaredTypes context-keying sibling of (c) plus a
    complexity-verdict-state audit — queue them as (c2)/(c3) when
    returning to the giants; the two probe tests
    (typeArgumentDefaultUsesConstraintOnCircularDefault,
    relationComplexityError) are the standing acceptance gate for any such
    step. Probe reverted.**
    **(c2) SCOUTED (round 549): the Test<any> coupling is a
    LAZY-MATERIALIZATION first-touch, not a cache-keying one —
    `Type.TypeParam.constraint`/`.default` are MUTABLE fields set at 8+
    scattered sites by whichever pass resolves the TP first (the
    typeParamInternCache shares the instance program-wide), so a no-args
    generic reference instantiates with defaults ONLY IF some earlier pass
    already materialized `.default`. DESIGN: EAGER TP materialization — one
    fixed init step (after globals merge, before any check pass) resolving
    every TypeParameter's constraint/default under its declaration's
    sibling-TP scope (the checkTpListDefaults scope-building pattern),
    making the fields order-free; the 8 lazy setters become no-ops
    (already-set guards) and eventually delete. Acceptance: the two probe
    tests + full gates.**
    **(c2) HYPOTHESIS FALSIFIED (round 549b, attempt REVERTED): a minimal
    eager top-level TP materialization (constraint+default fields filled at
    a fixed init point) did NOT dissolve the probe failure — the coupling's
    mechanism is the EFFECTIVE-default-via-constraint computation inside
    reference instantiation (the probe test's own name:
    typeArgumentDefaultUsesConstraintOnCircularDefault — tsc substitutes
    the CONSTRAINT when the default is circular), i.e. resolution-path
    state beyond the raw fields. Next root-cause step: instrument WHAT
    the legacy checkTpListDefaults slot changes that the later TS2353
    display consumes (candidate: the referenceCache entry for Test<any>
    minted during its constraint-relation checks, which the annotation
    resolution then reuses vs mints bare). Deferred behind (b2+)/other
    INV.5 work — the display-only coupling is cosmetic, not semantic.**
  - [ ] **INV.5(b) Explicit mapper objects.** Replace the ambient
    `currentTypeAliasArgs`/`currentTypeParamScope` instantiation contexts
    with an explicit mapper threaded through the resolution entry points —
    the enabler for (c). MEASURED SURFACE (round 546): 87 write sites in 34
    functions (top installers: checkCallTypesInStatement ×7,
    walkStmtsForTypeParamCasts ×6, checkReturnAssignability /
    resolveGenericPropertyTypeWorker / getTypeFromTypeReference /
    resolveInterfaceMembersCore / checkConstraintsInStatements ×4 each) +
    ~90 read sites inside the resolution family. DECOMPOSITION (bridge
    pattern — each step suite + listAll-×8 gated): (b1) a `TypeMapper`
    value (aliasArgs + tpScope + a stable fingerprint for cache keying) +
    an optional `mapper` param on `getTypeFromTypeNode`/
    `getTypeFromTypeReference` DEFAULTING to the ambient (behavior-
    identical bridge; the `cacheable` gate reads the param); (b2+) flip
    installer families to pass explicitly — (b2a) DONE round 549c: all 6
    simple aliasArgs installers flipped via aliasMapper/layeredAliasMapper
    (only the complex getTypeFromTypeReference substitution remains on the
    aliasArgs side = (b2b)); resolution-internal first
    (getTypeFromTypeReference's alias substitution,
    resolveGenericPropertyTypeWorker, resolveInterfaceMembersCore,
    getTypeOf* lazies), then the walker-level installers (call-types /
    constraints / return paths / tp-cast walkers); (bN) remove the ambient
    fields. NOTE (c) only needs the mapper AT THE CACHE CONSULT — it can
    start right after (b1) with ambient-bridged installers still in place
    (key = (nodeId, mapper.fingerprint); the context-bypass `cacheable`
    rule dies there).
  - [x] **INV.5(c) `nodeTypes` keyed (node, mapper) — LANDED round 548
    (option iii — the conservative pinned-checking-file gate; see the
    session note; widen the gate as INV.3(d) retires checking-file-dependent
    resolution, and cache the fingerprint per-install if the +5.4%
    single-run wall cost proves real).** Kills
    the context-bypass rule and the first-touch hazard class outright (the
    round-543 blocker). DESIGN (scouted round 547b — the surface is TINY,
    exactly 2 use sites inside getTypeFromTypeNode): a SECOND cache
    (`mappedNodeTypes`) for context-bearing resolutions keyed by an
    IDENTITY node key (=== equality with nodeId-based hashCode — cross-file
    nodeId collisions only share buckets, never results; unindexed nodes
    skip) + a context fingerprint (ns-stack symbol ids + sorted tpScope
    name:id pairs + sorted aliasArgs name:id pairs). The existing
    empty-context cache and its isPerFileDependentRefNode bypass stay
    untouched (identity keys make that hazard structurally impossible in
    the NEW cache). **SOUNDNESS CONSTRAINT (the reason this is not yet
    implemented): context-bearing resolutions ALSO depend on the CHECKING
    file — `currentFileLocals?.get ?: globals` consults are
    checking-file-keyed (the conflation ecology), so a fingerprint that
    excludes that dimension re-creates the first-touch disease inside the
    cache. Either (i) include a reliable checking-file identity in the
    fingerprint (currentCheckFileName is a stale-prone proxy — audit the
    setters first), or (ii) wait for INV.3(d)'s completion to eliminate
    checking-file-dependent resolution, or (iii) start with a
    CONSERVATIVE fingerprint that additionally requires
    currentFileLocals === the node's owning file's locals (node-keyed
    consult, cheap via owningSourceFile with a per-file memo) and skips
    caching otherwise.** Option (iii) is self-validating and incremental —
    preferred.
  - [ ] **INV.5(d) Instantiated members cached ON the `Type.Reference`.**
    Delete `resolveGenericPropertyType` fresh-minting + its depth-4 OOM cap
    (the per-recursion-level cache-miss gotcha).
  - [ ] **INV.5(e) Open `canUseTypeEngine`'s generic gate; delete superseded
    pin walkers** (suite-gated per deletion). Then RETURN to INV.4(e).

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
