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

**Round 643 (2026-07-22) — (M0.4) twentieth tail-pass migration:
checkTypeParameterDefaults (TS2368 reserved TP names + TS2744
forward/self TP default references + the circularDefaultTypeParamCount
side-set; 150 ms at the round-641 table — the #20 per-file tail pass,
slot 59b) is SPLIT: the emissions are ON THE SPINE, the side-set write
stays pre-spine; the legacy driver + walkTParamDefaultsInStmts/-InStmt/
-InClassMember/-InExpr/-InType recursion (~250 lines) DELETED;
validateTParamDefaultsEmit (+ findForwardTParamRef) survives as the
anchor-called leaf.** The SPLIT-PRODUCER variant (new template move,
round-642's scope map held): a pass with ONE side-set whose consumer is
cross-file/earlier-in-file (the `Name` → `Name<any, ...>` no-args
display in formatTypeForDisplay) cannot ride the spine walk — the write
splits into the pre-spine producer populateCircularTpDefaults (still
slot 59b; COLLECTOR discipline, binderResults) while the emissions
anchor at the ten TP-list-bearing construct kinds (fn/class/interface/
alias declarations, arrow/fn-expr/class-expr/objlit+member methods,
FunctionType/ConstructorType) over the memoized BINARY reach classifier
spineTdStatus/spineTdEdge with a cheap non-empty-typeParameters
pre-gate. TWO structural firsts: (1) the producer FILTERS its
candidates through the SAME frozen classifier the spine emissions use —
one edge set serves both halves, no walker clone to drift; (2) the
candidate set is PARSE-RECORDED (SourceFile.typeAliasesWithTpDefaults,
the moduleSpecifiers pattern: appended at parseTypeAliasDeclaration
when any TP carries a default; a speculative-parse discard classifies
unreached via its DETACHED parent chain, so over-collection is
harmless) — the producer runs at 0.4 ms where the legacy row was
150 ms. Producer-scan lesson (measured, do not repeat): re-scanning the
tree for candidates via a forEachChild worklist costs MORE than the
walk it replaces (264 ms raw; 218 ms with an exact TypeNode-subtree
prune — type positions can never contain a legacy-reachable alias since
type-member bodies are parser-discarded) — parse-time recording is the
shape for future split producers. Frozen reach quirks pinned both
directions: if CONDITIONS, switch SUBJECT + case EXPRESSIONS, for-in/of
heads, expression-bodied arrow BODIES, objlit ACCESSORS (class accessor
bodies ARE walked), enum member initializers, heritage clauses,
call/new TYPE ARGUMENTS, TP constraint/default INTERIORS (a FunctionType
inside a TP default is never validated; findForwardTParamRef's own-TP
inner-scope skip pinned separately), computed names, static blocks, and
mapped/keyof interiors all silent; for-head initializer(decl-list AND
expression)/condition/incrementor, declare-namespace bodies (NO declare
gate, unlike ac), catch blocks, template spans, ternary CONDITIONS
(unlike ac), and As/Satisfies/TypeAssertion type positions reached.
Fully syntactic emissions — no ambient sandwich (the legacy
currentFileLocals install fed only the side-set write). The legacy
binderResults driver → the spine's partition view, gated
`--partitionCheck 2` EQUIVALENT ×8. Gates: 56 local pins
(M04TpDefaultsSpineMigrationTest) green against the LEGACY pass FIRST
(all 55 initial pins on the first run); suite 11,892 → 11,948/0;
`--listAll` ×8 byte-identical (sorted, non-time lines) vs the round-642
HEAD baseline; partitionCheck ×8 EQUIVALENT (46/46/46/46/46/46/46/94);
pass table 419 → 419 (checkTypeParameterDefaults 150 ms →
populateCircularTpDefaults 0.4 ms; checkSpine 19.9 s single-run —
in-band); warning-clean (--rerun-tasks, zero `w:`). M0.4 running total:
top TWENTY tail passes migrated. SESSION TAIL: the
checkExpandoFunctionNestedReads (#21, 99 ms — TS2339 for an
expando-function property read inside a NESTED function, B431)
slot-move pre-gate LANDED (moved intact from the B431 slot to the
post-spine slot; suite 11,948/0; listAll ×8 byte-identical vs the
migrated baseline; the only diagnostics-list consumer between the
slots, ctaPostFilters, touches TS2322/TS7030 and reads TS2304/TS2314 —
TS2339 is invisible to it). Scope map for the migrator (verified
against source): THREE per-file walks — (a) a TOP-LEVEL-only statement
scan building funcNames/nameCount/merged (candidates = uniquely-named
top-level fns not merged with any other decl kind); (b)
collectExpandoDecls, the file-scope `Foo.prop =` write collector
walking statements + expression positions (if/for/while/switch HEADS
and case EXPRESSIONS included) but NEVER descending into
function-likes; (c) visitExpandoStmt/-Expr, the read walk carrying TWO
downward values — the inNestedFn flag (flips true inside fn-like
bodies AND param defaults) and the ChainedNameSet shadow chain (param
names + fn-body locals; a fn-expr's own name too) — likely the UY/SR
status-carried-flag shape with per-boundary shadow levels (the
round-628 downward-SETS rebuild). NEXT session starts at that
migration; after it by cost: checkStrictModeIdentifiers 96 ms,
checkConstLiteralComparisons 95 ms, checkSuperInObjectLiterals
91 ms.**

**Round 642 (2026-07-22) — (M0.4) nineteenth tail-pass migration:
checkInvalidAssignmentTargets (TS2364 invalid assignment/
compound-assignment targets + the destructuring private-identifier
check; 105.8 ms at the round-641 table — the #19 per-file tail pass) is
ON THE SPINE; the legacy driver +
checkInvalidAssignInStatement(s)/-InExpr(Core) recursion (~220 lines)
DELETED; emitInvalidAssignAtBinary (+ isValidAssignmentTarget /
checkDestructuringPrivateIds) survives as the anchor-called leaf.** The
INT-depth classifier's SECOND application (the round-535 spineArgDepth
shape, as the round-641 tail note predicted): spineIaDepth reproduces
the legacy SHARED `checkDepth` counter per node — every
checkInvalidAssignInExpr call consumed one frame and bailed past
maxCheckDepth (200); statements and carrier positions inherit the
ambient counter unchanged, INCLUDING a statement list nested inside an
expression (arrow/fn-expr/objlit-method/class-EXPRESSION bodies inherit
the expression's elevated ambient — encoded uniformly as +1 on every
expression parent's outgoing edge, with the bail check applied at
expression-CALL edges only). Unlike spineArgEdge there is NO right-spine
absorption — both binary operands cost a frame, so deep chains prune at
200 (pinned at the exact boundary: fires under 200 nested parens, silent
under 201). Frozen quirks pinned both directions: for-heads walk
initializer-as-Expression AND condition AND incrementor while a for-head
DECLARATION-LIST initializer is never walked; the switch SUBJECT and
case EXPRESSIONS are not walked (clause statements only); objlit
methods/accessors + class-EXPRESSION members ARE walked; enum member
initializers, class heritage, computed property names, decorators
unreached; `<<=`/`>>=`/`>>>=`/`**=` sit OUTSIDE isAssignmentOperator
(frozen gap → silent). Anchors: assignment-operator BinaryExpressions
(operator pre-gate before the climb). Fully syntactic — no ambient
sandwich. The now-orphaned shared `checkDepth` counter (this pass was
its only mutator) is deleted from Checker + CheckerState. The legacy
binderResults driver → the spine's partition view, gated
`--partitionCheck 2` EQUIVALENT ×8. Gates: 35 local pins
(M04InvalidAssignSpineMigrationTest) green against the LEGACY pass FIRST
(all 35 on the first run); suite 11,857 → 11,892/0 — NOTE this pass
emits 16 diagnostics on the corpus census, so unlike rounds 637–641 the
corpus is a REAL behavior gate here, not just non-perturbation;
`--listAll` ×8 byte-identical (sorted) vs the round-641 slot-move
baseline; partitionCheck ×8 EQUIVALENT; pass table 420 → 419 (the row
gone; checkSpine 20.7 s single-run — in-band); warning-clean
(--rerun-tasks, zero `w:`). M0.4 running total: top NINETEEN tail passes
migrated. SESSION TAIL — the #20 row checkTypeParameterDefaults (150 ms,
slot 59b) PRODUCER scope map, VERIFIED against the source (no slot-move
landed — the pass is ALREADY a round-555 pre-spine hoist, so there is no
move to make; the migration itself is what needs the producer split):
the round-555/641 "materializes TypeParam .constraint/.default"
attribution is a MISNOMER for this pass — checkTpListDefaults (which
does materialize, via typeParamInternCache + getTypeFromTypeNode)
belongs to the SEPARATE checkGenericDefaultsValidation pass (B498,
its own later slot), while the 59b family
(checkTypeParameterDefaults → walkTParamDefaultsInStmt(s)/-InType/
-InExpr/-InClassMember → validateTParamDefaults, Checker.kt
78917–79157) contains ZERO type-resolution calls: it emits TS2368
(reserved TP name) + TS2744 (forward default ref) and writes exactly ONE
side-set — `circularDefaultTypeParamCount[sym.id]` (a TypeAliasDeclaration
whose TP defaults are circular; consumed by the no-args generic display
`Test` → `Test<any>`; the write consults currentFileLocals, which the
driver installs per file). The 59b comment's hoist rationale is the
side-set-before-display ordering, and the display consumer is
spine-anchored (cross-file + earlier-in-file consumption), so the
side-set write CANNOT ride the spine walk. Treatment (now precise):
SPLIT — a tiny pre-spine producer walking only to TypeAliasDeclarations
(any walked statement position) re-running the findForwardTParamRef
verdict to populate the side-set, and the TS2368/TS2744 emissions
migrate onto the spine (anchors = TP-list-bearing constructs incl.
FunctionType/ConstructorType TYPE positions and TypeLiteral members —
note walkTParamDefaultsInType descends union/intersection/array/tuple/
paren/type-args, a TYPE-side reach classifier like batch 5's; fully
syntactic, no ambient sandwich needed for the emissions since
validateTParamDefaults' currentFileLocals consult feeds only the
side-set write, which stays in the producer). After #20 by cost:
checkExpandoFunctionNestedReads 99 ms, checkStrictModeIdentifiers 96 ms,
checkConstLiteralComparisons 95 ms, checkSuperInObjectLiterals 91 ms.**

**Round 641 (2026-07-22) — (M0.4) eighteenth tail-pass migration:
checkSuperRefInRebindingScope (TS2660 `super` references inside
regular-function rebinding scopes; 113.1 ms at the round-639 table — the
#18 per-file tail pass) is ON THE SPINE; the legacy driver +
walkSuperRebindStmts/-Stmt/-Expr (~190 lines) DELETED;
emitTS2660InRebindingScope survives as the anchor-called leaf.** The
REBOUND-BOOLEAN-AS-STATUS variant (the round-640 tail note's
"frameless pull-based" guess held, as the UY generator-flag shape): the
walk's ONE downward boolean rides the classifier status —
SR_REBOUND/SR_CLEAR are the SAME walk carrying `rebound`, fn-decl/
fn-expr bodies RESET to SR_REBOUND, arrows/ModuleBlocks PRESERVE,
class-member bodies/prop initializers reset to SR_CLEAR via the
SR_MEMBER carrier (method/ctor/get/set/static-block bodies +
PropertyDeclaration initializers; only a nested fn inside re-rebinds).
Anchors: `super` Identifiers (the text pre-gate runs before the reach
climb — the name is rare); the frozen `super(...)` CALLEE skip (TS2337
territory) is the anchor's DIRECT-parent gate, so a PARENTHESIZED super
callee `(super)()` still fires exactly as legacy (which walked through
the parens). The legacy PropertyAccess/ElementAccess special-cased
emission AT the receiver reproduces as plain descent edges — the anchor
at the receiver super emits identically. Frozen quirks pinned both
directions: object literals skipped entirely (the sibling
checkSuperInObjectLiterals is position-DISJOINT — objlit member/value
supers draw nothing from EITHER pass in a plain fn, pinned); the
for-INITIALIZER is walked as a bare EXPRESSION only (a for-head
declaration-LIST initializer never walked) while for-condition/
incrementor are NOT; class EXPRESSIONS never walked; property-access
NAMES and catch variables unreached. Fully syntactic — no ambient
sandwich. The legacy binderResults driver → the spine's partition view,
gated `--partitionCheck 2` EQUIVALENT ×8; zero TS2660 on all 8 tsc
profiles → the listAll gate pins pure non-perturbation. Gates: 35 local
pins (M04SuperRebindSpineMigrationTest) green against the LEGACY pass
FIRST (34 on the first run; the for-head decl-list negative added from
the code reading); suite 11,822 → 11,857/0; `--listAll` ×8
byte-identical; partitionCheck ×8 EQUIVALENT; pass table 421 → 420 (the
row gone; checkSpine 20.9 s single-run — in-band for this box state);
warning-clean (--rerun-tasks, zero `w:`). M0.4 running total: top
EIGHTEEN tail passes migrated. SESSION TAIL: the
checkInvalidAssignmentTargets (#19, 105.8 ms — TS2364 invalid
assignment/compound-assignment targets + the destructuring
private-identifier check) slot-move pre-gate LANDED (moved intact from
slot 65 to the post-spine slot; suite 11,857/0; listAll ×8
byte-identical; no TS2364 dedup/scan consumers anywhere — the sole
other 2364 mention is a comment in the TS2454 walker). Scope map for
the migrator: fully syntactic (isValidAssignmentTarget +
checkDestructuringPrivateIds + expressionTrueEnd — no type caches, no
ambient); anchors = BinaryExpressions whose operator is an assignment
kind (the TS2364 emitters) — but note the pass descends into BOTH sides
of every binary VIA RECURSION GUARDED by the shared `checkDepth`
counter (checkInvalidAssignInExpr trips at maxCheckDepth), so deep
chains PRUNE — the round-535 INT-depth classifier (spineArgDepth shape)
is the likely reach form, with per-edge depth increments reproducing
checkDepth's consumption; edge quirks to pin: for-heads walk
initializer-as-Expression AND condition AND incrementor (unlike sr);
switch-case EXPRESSIONS are NOT walked (clauses' statements only);
objlit methods/accessors + class-EXPRESSION members ARE walked;
TaggedTemplate walks tag + spans; class-DECLARATION members walk
method/ctor/accessor bodies + prop initializers. NEXT session starts at
that migration; after it by cost (this round's table, single-run):
checkTypeParameterDefaults 150 ms (CAUTION — a PRODUCER: it
materializes TypeParam .constraint/.default fields consumed downstream,
per the round-555 hoist note; needs producer-aware treatment, not a
plain slot-move), checkExpandoFunctionNestedReads 99 ms,
checkStrictModeIdentifiers 96 ms, checkConstLiteralComparisons 95 ms,
checkSuperInObjectLiterals 91 ms.**

**Round 640 (2026-07-22) — (M0.4) seventeenth tail-pass migration:
checkUndefinedClassInterfaceName (TS2414 class / TS2427 interface /
TS2457 type-alias names in PREDEFINED_TYPE_NAMES + the piggy-backed
TS1163 yield-outside-generator walk; 123.9 ms — the #17 per-file tail
pass) is ON THE SPINE; the legacy driver + checkUndefinedNamesInStmts +
the walkYieldInStmts/-InStmt/-InExpr recursion (~280 lines) DELETED; the
emissions inlined as the spineUyEmitName/spineUyEmitYield leaves.** The
TWO-INTERLEAVED-WALKS variant (a new template move): the pass ran two
recursions with DISJOINT node sets — the NAME-check walk (statement
positions only; descends Block/ModuleBlock/if/loops/switch-clauses/
try/labeled but NEVER fn or class-member bodies, no expression descent)
and the yield walk (started ONLY at name-reached FunctionDeclaration
statements, tracking generator state through fn-decl/fn-expr/
objlit-method asteriskToken boundaries; arrows always non-generator) —
reproduced by ONE multi-state classifier (spineUyStatus/spineUyFold)
whose statuses carry the walk identity AND the downward state: UY_NAME
for name positions, UY_YGEN/UY_YNON with the generator flag AS the
status (the round-639 tail note's "3-state yield status" guess held),
UY_MEMBER bridging a yield-walked container's member to its
body/initializer with the frozen member filters encoded on the CONTAINER
edge (class DECLARATIONS walk method/ctor/accessor bodies + property
initializers; class EXPRESSIONS method/ctor ONLY; object literals
methods only — accessors never). The walks are disjoint by construction
(yield positions live inside some fn body, which the name walk never
crosses), so each node has a unique status and one ByteArray memo serves
both. Frozen quirks pinned both directions: a `class undefined {}`
inside a fn/method/arrow body is unchecked; top-level class METHOD
bodies and top-level fn-EXPRESSIONS are never yield-walked; a
for-INITIALIZER is never yield-walked while condition + incrementor are;
the legacy left-spine BinaryExpression fold reduces to plain left/right
edges (reach-equivalent). Anchors: class/interface/type-alias enters
with a PREDEFINED_TYPE_NAMES pre-gate BEFORE the reach climb (the names
are rare — anchors nearly free) + YieldExpression enters. Fully
syntactic — no ambient sandwich. The legacy binderResults driver → the
spine's partition view, gated `--partitionCheck 2` EQUIVALENT ×8; zero
TS2414/TS2427/TS2457/TS1163 on all 8 tsc profiles → the listAll gate
pins pure non-perturbation. Gates: 32 local pins
(M04UndefinedNameSpineMigrationTest) green against the LEGACY pass
FIRST; suite 11,790 → 11,822/0; `--listAll` ×8 byte-identical (time
header only; compiler-profile wall parity legacy 30.1 s vs migrated
29.9 s); partitionCheck ×8 EQUIVALENT; pass table 422 → 421 (the row
gone; checkSpine 21.3/21.6 s repeat runs — in-band for this session's
box state, wall parity per the listAll pair); warning-clean
(--rerun-tasks, zero `w:`). M0.4 running total: top SEVENTEEN tail
passes migrated. SESSION TAIL: the checkSuperRefInRebindingScope (#18,
113.1 ms at the round-639 table — TS2660 for `super` references inside
regular-function rebinding scopes) slot-move pre-gate LANDED (moved
intact from slot 27d to the post-spine slot; suite 11,822/0; listAll ×8
byte-identical; the sibling TS2660 emitter checkSuperInObjectLiterals is
position-DISJOINT by construction — this walker skips object literals
entirely — and nothing scans the code). Scope map for the migrator: the
`rebound` flag is ONE downward boolean — top-level starts true, fn-decl/
fn-expr bodies RESET to true, arrows PRESERVE, class-member bodies and
prop initializers reset to FALSE at the direct level (only a nested fn
inside re-rebinds), ModuleBlocks preserve; `super(...)` callees and
object literals are skipped (TS2337 / the objlit sibling own them); the
for-INITIALIZER is walked but for-condition/incrementor are NOT — likely
the round-625 frameless pull-based shape (anchors = the rare `super`
Identifiers, status = the folded rebound flag). NEXT session starts at
that migration; after it by cost: checkInvalidAssignmentTargets
105.8 ms.**

**Round 639 (2026-07-22) — (M0.4) sixteenth tail-pass migration:
checkEvolvingEmptyArrayImplicitAny (TS7034 at the decl + TS7005 at each
read of an un-annotated `let/var x = []` evolving array, round-316 family
incl. the single-array/branch-merge/snapshot push TS2345s; 103.2 ms — the
#16 per-file tail pass) is ON THE SPINE; the legacy binderResults driver +
evRecurseScopes (~35 lines) DELETED; evProcessScope + the whole
evUseStmt/evUseExpr simulation + the three push checks survive as the
anchor-called leaf.** The PER-LIST-OWNER variant (the round-627/634 shape,
first full application): each scope's statement LIST dispatches ONCE at
its owning SourceFile / Block / ModuleBlock ENTER (spineEvEnterNode) —
never per anchor — gated by the memoized MULTI-STATE reach classifier
spineEvStatus/spineEvFold reproducing the deleted evRecurseScopes arms
verbatim, including its LEVEL-SKIPPING quirks (the sharp pins): a REACHED
Block is itself a scope (bare/control-flow position), while try/catch/
finally clause Blocks and case/default clauses carry pass-through statuses
(EV_TRYCLAUSE/EV_CLAUSE — their statements are recursed for NESTED scopes
but the list never forms a scope, so a candidate declared DIRECTLY in a
try block or case clause never fires while a Block statement inside them
DOES); fn-declaration and class-DECLARATION member bodies reach via
EV_MEMBER→EV_SCOPE; arrow/fn-EXPRESSION bodies and class-EXPRESSION
member bodies are never scopes (the recursion had no expression descent).
Scope-map correction the pins caught: a dotted `namespace A.B` IS a scope
— the parser keeps ONE ModuleDeclaration with a direct ModuleBlock body,
so the `body as? ModuleBlock` arm matched it (the round-638 tail note
implied otherwise). Second correction: the round-638 "fully syntactic —
no ambient" guess was WRONG — Part 2 (evCheckSingleArrayPush) resolves
types (getTypeOfArrayLiteral / getTypeOfExpression / checkTypeRelatedTo),
so every dispatch runs under an ambient sandwich (spineEvDispatch):
resting currentFileLocals (the legacy slot ran FIRST after checkSpine,
whose finally restores the pre-spine value) + per-file
currentCheckFileName + a nulled currentFlowGraph (the round-630 co
precedent). The legacy driver → the spine's partition view, gated
`--partitionCheck 2` EQUIVALENT ×8 (the round-633 rule); the pass emits
ZERO TS7034/TS7005 on all 8 tsc profiles → the listAll gate pins pure
non-perturbation. Gates: 33 local pins (M04EvolvingArraySpineMigrationTest
— trigger A/B, concretizer gating, captured reads surviving an outer
concretizer, the five suppression shapes, the self-spread read, the reach
battery both directions, the three push checks, run gates) green against
the LEGACY pass FIRST; suite 11,757 → 11,790/0; `--listAll` ×8
byte-identical (time header only); partitionCheck ×8 EQUIVALENT; pass
table 423 → 422 (the row gone; checkSpine in-band); warning-clean
(--rerun-tasks, zero `w:`). M0.4 running total: top SIXTEEN tail passes
migrated (one skip: the cross-file checkCrossFileModuleAugmentationDuplicates).
SESSION TAIL: the checkUndefinedClassInterfaceName (#17, 123.9 ms at the
round-639 table — TS2414 class / TS2427 interface / TS2457 type-alias
names in PREDEFINED_TYPE_NAMES + the piggy-backed TS1163
yield-outside-generator walk) slot-move pre-gate LANDED (moved intact
from pre-spine slot 53 to the post-spine slot; suite 11,790/0; listAll
×8 byte-identical; it is the SOLE emitter of all four codes and nothing
scans them — order flip immaterial). Scope map for the migrator: TWO
interleaved reach shapes in one pass — the NAME-check recursion
(class/interface/type-alias names; descends Block/ModuleBlock/
if/loops/switch-clauses/try/labeled but NEVER fn or class-member bodies
— a `class undefined {}` inside a function body is unchecked, frozen)
and the yield walk (starts ONLY at the name-recursion's
FunctionDeclaration statements — top-level class METHOD bodies are
never yield-walked, frozen — then tracks generator state through
fn-decl/fn-expr/objlit-method asteriskToken boundaries with arrows
always non-generator, incl. a full expression descent + a left-spine
iterative BinaryExpression fold). Likely migrates as two coupled
classifiers: a binary name-check reach + a 3-state yield status
(GEN/NONGEN/unreached) carrying the generator flag as the status.
NEXT session starts at that migration; after it by cost:
checkSuperRefInRebindingScope 113.1 ms, checkInvalidAssignmentTargets
105.8 ms.**

**Round 638 (2026-07-22) — (M0.4) fifteenth tail-pass migration:
checkArgumentsCollision (TS2396 "Duplicate identifier 'arguments'…" for
an `arguments`-named param alongside a rest param at target < ES2015 +
TS1215 "Invalid use of 'arguments'…" for `arguments` param bindings in
module files; 116.8 ms — the #15 per-file tail pass) is ON THE SPINE;
the legacy driver + recursion (checkArgumentsCollision /
checkArgsCollisionInStatements / checkArgsCollisionInStatement /
checkArgsCollisionInExpr, ~130 lines) DELETED; the emission leaf
(checkArgsCollisionInParams) survives unchanged, anchor-called.** The
CONSTANT-CONTEXT variant — the simplest downward state of the migrated
passes: the only threaded value is the per-file isModule boolean
(= spineFileIsModule, already computed per file), so NO frames, NO ctx
memo, NO prepass; the per-construct declare/body gates re-derive at the
anchor from the construct node + its PARENT kind. Frozen asymmetries
pinned: a class-DECLARATION method/ctor param-checks iff body +
!class-declare and its set-accessors are body-walked but NEVER
param-checked, while class-EXPRESSION and object-literal members
param-check UNCONDITIONALLY; a FunctionDeclaration needs body +
!declare; arrows/fn-exprs always check. Anchors at fn-like enters
(FunctionDeclaration / MethodDeclaration / Constructor / SetAccessor /
ArrowFunction / FunctionExpression) with a cheap `arguments`-param-name
pre-gate BEFORE the reach climb (the name is rare — anchors nearly
free). Reach is the memoized binary classifier spineAcStatus/
spineAcEdge — a WIDER edge set than gIdx's (a fresh copy, not a reuse):
arrows / fn-exprs / class-EXPRESSION members (incl. property
initializers) / objlit members / template spans / typeof-await-yield-
void-delete operands / tagged-template tags+spans all descend, while
if/ternary CONDITIONS, for/while/do/switch HEADS, class-DECLARATION
property initializers, and declare-namespace bodies stay silent (pinned
both directions — the class-decl vs class-expr property-initializer
asymmetry is the sharp pair). The run-level dispatch gate
(target < ES2015 || any non-dts module file) becomes spineAcRunActive,
computed once at checkSpine entry. No ambient sandwich (purely
syntactic emission). The legacy binderResults driver → the spine's
partition view, `--partitionCheck 2` EQUIVALENT ×8; ZERO TS2396/TS1215
on all 8 tsc profiles → the listAll gate pins pure non-perturbation
(the corpus's 8 collision tests are the behavior gate). Gates: 25 local
pins (M04ArgsCollisionSpineMigrationTest) green against the LEGACY pass
FIRST; suite 11,732 → 11,757/0; `--listAll` ×8 byte-identical (time
header only); partitionCheck ×8 EQUIVALENT; pass table 424 → 423 (the
row gone; checkSpine 19,665 ms single-run — in-band); warning-clean
(--rerun-tasks, zero `w:`). M0.4 running total: top FIFTEEN tail passes
migrated. SESSION TAIL: the checkEvolvingEmptyArrayImplicitAny (#16,
103.2 ms — TS7034/TS7005 evolving empty-array implicit-any, the
round-316 family incl. the single-array/branch-merge/snapshot push
checks) slot-move pre-gate LANDED (moved intact from pre-spine slot
37a3 to the post-spine slot; suite 11,757/0; listAll ×8
byte-identical). Scope map for the migrator: a per-STATEMENT-LIST scope
pass — each scope (file / fn / method / accessor body, via
evRecurseScopes) processes its DIRECT VariableStatement candidates with
a whole-LIST simulation (evUseStmt over ALL statements of the list —
order-independent collection, per-candidate EvState) then runs the
three push checks per scope; no TS7034/TS7005 dedup consumers
(checkUninitializedLetCapturedReads emits the same codes but the
trigger-B split is gate-disjoint BY CONSTRUCTION — captured reads of
uninitialized lets vs this pass's same-scope reads — so the order flip
across the move is immaterial); likely migrates as per-LIST-owner
dispatch at scope-owner enters (the round-627/634 shape), not
per-anchor. PROCESS NOTE (a trap worth remembering): waiting for a
background suite by XML COUNT is unsound — `rm -rf
build/test-results/jvmTest/binary` keeps the previous run's 472 XMLs
and gradle deletes them only when the test task STARTS (after the
multi-minute compile), so a count-based wait "passed" on STALE results
and a `./gradlew --stop` issued then KILLED the in-flight compile
(empty classes dir → 8 ClassNotFoundException captures + a corrupted
in-progress-results-generic.bin needing a test-state wipe). Rule: wipe
the WHOLE build/test-results/jvmTest dir before a suite whose
completion is awaited by file checks, and never --stop/pkill while a
gradle client exists. NEXT session starts at the eafs migration.**

**Round 637 (2026-07-22) — (M0.4) fourteenth tail-pass migration:
checkGenericIndexWrite (TS2862 "Type 'T' is generic and can only be
indexed for reading." — index WRITES through a receiver typed as a
string/symbol-index-constrained type parameter, the gIdx walker;
117.3 ms — the #14 per-file tail pass) is ON THE SPINE; the legacy
driver + recursion (checkGenericIndexWrite / gIdxHandleBody /
gIdxScanStatements / gIdxScanStatement / gIdxCheckExpr, ~85 lines)
DELETED; emitTS2862IfGenericIndexWrite + the pure-AST helpers
(constrainedTpNames / bareTypeParamRefName / collectParamTpRefs /
collectTpLocalsMap) survive as anchor-called leaves.** The round-626
DOWNWARD-MAP variant, third application: the purely downward (tparams,
tpProps, refs) triple is a pure function of the ancestor chain — tparams
ACCUMULATE through class/fn boundaries (constrainedTpNames), refs
REBUILD at every fn-like boundary from collectParamTpRefs + the
body-WIDE collectTpLocalsMap prepass (NOT statement-ordered →
pull-based per-boundary rebuilds reproduce it; use-before-decl locals
still match, pinned), tpProps from the nearest enclosing class's
bare-TP property annotations (RESET by a nested FunctionDeclaration;
cleared for property initializers — both pinned) — so it rebuilds per
anchor with a per-boundary-child memo (spineGxCtxFor). Anchors are `=`
BinaryExpressions whose paren-unwrapped LHS is an
ElementAccessExpression (the cheap shape gate runs BEFORE the reach
climb); reach is the memoized binary classifier spineGxStatus/
spineGxEdge (the deleted arms verbatim, frozen — arrow/fn-EXPRESSION
bodies, class EXPRESSIONS, object literals, compound assignments, and
nested-fn refs inheritance all stay silent, pinned both directions).
Frozen quirk worth naming: the locals PREPASS's descent set is NARROWER
than the scan's — collectTpLocalsMap has no Switch/Try arms, so a
`let t: T` declared inside a switch case or try block never registers
(pinned) while an if-block local does. No ambient sandwich (the
emission is purely syntactic — source + string maps; no type caches
touched). The legacy binderResults driver → the spine's partition view,
gated `--partitionCheck 2` EQUIVALENT ×8 (the round-633 rule); the pass
emits ZERO TS2862 on all 8 tsc profiles, so the listAll gate pins pure
non-perturbation. Gates: 31 local pins
(M04GenericIndexWriteSpineMigrationTest — the emitter's
constraint/index/receiver gates, the ctx battery (tparams accumulation,
nested-fn refs reset, tpProps method/initializer/nested-fn split,
get-accessor locals-only refs, a nested class built through a
method-body chain), and the reach battery both directions) green
against the LEGACY pass FIRST; suite 11,701 → 11,732/0; `--listAll` ×8
byte-identical (time header only); partitionCheck ×8 EQUIVALENT; pass
table 425 → 424 (the row gone; checkSpine 19,714.4 ms single-run —
in the recent 19.3–20.5 s band); warning-clean (--rerun-tasks, zero
`w:`). M0.4 running total: top FOURTEEN tail passes migrated. SESSION
TAIL: the checkArgumentsCollision (#15, 116.8 ms — TS2396
arguments-vs-rest-param collision at target < ES2015 + TS1215
`arguments` bindings in module files) slot-move pre-gate LANDED (moved
intact with its run-level dispatch gate from pre-spine slot 42 to the
post-spine slot; suite 11,732/0; listAll ×8 byte-identical). Scope map
for the migrator: the SIMPLEST downward context yet — one
CONSTANT-per-file boolean (isModule) + per-construct declare gates; no
maps, no accumulation, no prepass; the walker descends
arrows/fn-exprs/class-exprs/objlit members/template spans and
typeof/await/yield/void/delete operands (a WIDER reach than gIdx — a
fresh edge set, not a reuse); param-list emissions anchor at fn-like
enters gated body-present + !declare (class members inherit the CLASS's
declare flag; get-accessors never param-check); the run-level dispatch
gate (target < ES2015 || any non-dts module file) becomes the
run-active flag; the sibling TS1215 emitter
checkModuleStrictModeInStatements covers NAME bindings only (its
FunctionDeclaration arm deliberately defers params to this pass) and
scans no lists. NEXT session starts at its migration; after it by cost:
checkEvolvingEmptyArrayImplicitAny 103.2 ms.**

**Round 636 (2026-07-22) — (M0.4) thirteenth tail-pass migration:
checkPropertyInitialization (TS2564 strict-property-initialization,
~99 ms — the #13 per-file tail pass) is ON THE SPINE; the normal-mode
pass dispatch is deleted, the recursion walkers SURVIVE for the B439
declarationOnly direct dispatch (the round-630 shared-walker rule:
spinePiEdge mirrors their arms and must stay IN SYNC — pointer KDoc on
both sides).** The MULTIPLICITY variant (new template move): the legacy
ClassDeclaration statement arm walks method/ctor/accessor member bodies
TWICE — once via checkClassPropertyInit's own nested recursion, once via
the arm's member loop — so a class nested in such a body has its TS2564s
emitted 2^depth times (the duplicates reach the output; no dedup exists
for fileName-carrying diagnostics — pinned ×2 and ×4), while
ClassExpression member bodies, static-block bodies, and property
initializers are single-walked. Reproduced as an INT-valued reach
classifier that returns a VISIT COUNT: spinePiMult is a bottom-up
ancestor climb multiplying per-edge factors {0, 1, 2} — every factor is
LOCAL to one parent→child edge, so no multi-state fold is needed; the
one context-dependent case (an arrow/fn-EXPRESSION body Block walks only
its DIRECT ExpressionStatement/ReturnStatement/VariableStatement
children) resolves by peeking at the Block's parent. Anchors
(ClassDeclaration/ClassExpression enters) repeat the split-out
checkClassPropertyInitEmit that many times; the legacy checkClassPropertyInit
= emit + nested recursion, byte-identical for the declarationOnly path.
No frames, no memo (anchors rare — the round-625 rule), no ambient
sandwich (the emission is syntactic — typeIncludesUndefined's Node
overload is a pure union scan, NOT type-resolving as the round-635 scope
map guessed — plus the pure getTypeParamInfo/resolveAlias memos only).
Pin surprise: the expression walker's Abstract-ClassExpression gate is
UNREACHABLE via parse (parseClassExpression never sets modifiers;
`= abstract class C {}` error-recovers `abstract` as an identifier with
the class re-parsed as a STATEMENT-level ClassDeclaration) — the gate is
kept frozen, the recovery shape pinned instead. Gates: 33 local pins
(M04PropertyInitSpineMigrationTest — the emitter's gates incl.
ctor-param properties / literal computed names / option gates, the reach
battery both directions (if/ternary conditions, for-in heads, enum
members, param defaults, nested arrow blocks unreached; namespace/
fn-body/switch/try/catch/template-span/objlit positions reached), and
the multiplicity pins: method-body ×2, two-level ×4, static-block ×1,
class-expr-body ×1, prop-initializer ×1) green against the LEGACY pass
first; suite 11,668 → 11,701/0; `--listAll` ×8 byte-identical (only the
wall-time header differs — zero TS2564 on all 8 profiles, so the gate
pins pure non-perturbation); partitionCheck ×8 EQUIVALENT (the legacy
driver iterated binderResults → the spine's partition view); pass table
426 → 425 (the row gone; checkSpine 20.2–20.5 s single runs — box
up-drift, the same-interleave whole-run walls are pre 29.70 s vs post
29.81 s = 0.35%); warning-clean (--rerun-tasks, zero `w:`). M0.4 running
total: top THIRTEEN tail passes migrated. SESSION TAIL: the
checkGenericIndexWrite (#14, 117.3 ms — TS2862 index-WRITE on a generic
type-parameter receiver, the gIdx walker) slot-move pre-gate LANDED
(moved intact from its pre-spine slot 64d4 to the post-spine slot; suite
11,701/0, listAll ×8 byte-identical). Scope map for the migrator: fully
SYNTACTIC + self-contained (constrainedTpNames/bareTypeParamRefName/
collectParamTpRefs/collectTpLocalsMap are pure AST reads — no type
caches, no ambient); no TS2862 dedup/scan consumers
(checkGenericRecordCastAccess emits the code but is gate-disjoint and
never scans the list); a DOWNWARD-context recursion — the (tparams,
tpProps, refs) triple REBUILDS at class/fn boundaries with a body-WIDE
locals PREPASS (collectTpLocalsMap over the whole body before the scan,
NOT statement-ordered → pull-based per-boundary levels reproduce it, the
round-626/628 shape); anchors are `=` BinaryExpressions with an
ElementAccess LHS. NEXT session starts at its migration; after it by
cost: checkArgumentsCollision 116.8 ms, checkEvolvingEmptyArrayImplicitAny
103.2 ms (checkCrossFileModuleAugmentationDuplicates 114.1 ms stays
SKIPPED — cross-file aggregation).**

**Round 635 (2026-07-21) — (M0.4) twelfth tail-pass migration:
checkProtectedMemberReadAccess (B446 — TS2445/TS2446 protected READ
access + the class-method WRITE check, ~103 ms — the #12 per-file tail
pass) is ON THE SPINE; the legacy driver + pmrScanContainer/
pmrProcessFunctionLike/pmrProcessNestedFn/pmrWalkStmt/pmrWalkExpr
(~120 lines) are DELETED.** The PUSH-BASED ORDER-DEPENDENT variant (the
round-531 arith pattern's first M0.4 application): the round-634 open
question resolved to "statement-order MUTATED" — pmrWalkStmt's
VariableStatement arm records `vars[nm] = pmrLocalClass(init)` AFTER
walking each initializer, the map is SHARED through Block/If/loop/ARROW
descents (recordings deliberately LEAK out of blocks) and COPIED at
nested fn-expr/fn-decl boundaries — so the downward state (vars +
this/lexical class + the in-class-method write gate) is push-maintained:
LIFO frames at processed-fn-like boundaries (fresh vars from params),
nested-fn boundaries (copied vars; this/lex/write-gate RESET), and
top-level ExpressionStatements (the per-file-setup topVars map installed
by INSTANCE — a recording inside a top-level IIFE body mutates it for
later top-level statements, the legacy by-reference behavior), popped at
owner LEAVES; per-declaration recordings fire at VariableDeclaration
LEAVES (the legacy walk-then-record order). Reach is the memoized
5-STATE classifier spinePmrStatus/spinePmrEdge (frozen verbatim):
CONTAINER_FILE/CONTAINER_NS carry the container scan — split because
only FILE-level ExpressionStatements are walked with topVars (a
namespace-level `c1.x;` is unreached, pinned) — and STMT/EXPR the walker
arms, with the write-LHS rule as an EDGE (`=` with a PropertyAccess LHS:
the LHS subtree is never read-walked; the accessibility check fires at
the BinaryExpression anchor under the frame-maintained pmrInClassMethod
gate). Emissions at PropertyAccessExpression enters (reads) +
BinaryExpression enters (class-method writes); pmrResolveClass/
pmrFindProtected*DeclaringClass/pmrLocalClass/pmrCheckAccess survive as
anchor-called leaves, pmrInClassMethod/pmrLexicalClass as the
frame-maintained state registers (verified pmr-exclusive by grep). No
ambient sandwich — the helpers resolve via the passed locals + the
node-keyed lookupPerFileForNode only (no type caches touched, so no
first-touch risk). The binderResults-iterating driver → the spine's
partition view, gated `--partitionCheck 2` EQUIVALENT ×8 (the round-633
rule); the pass emits ZERO diagnostics on all 8 tsc profiles (verified —
no TS2445/TS2446 in any capture), so the listAll gate pins pure
non-perturbation. Gates: 39 local pins (M04ProtectedReadSpineMigrationTest
— the TS2445/TS2446 emitters' gates, the this-param fallback incl. its
static exclusion, the write-gate arrow-inherit/nested-fn-reset pair, the
recording leak/copy/identity-chain semantics, and the reach battery both
directions: NewExpression args, switch bodies, for-of heads, objlit
values, class property initializers, nested classes, expression-bodied
container arrows, method reads of top-level vars all unreached) green
against the LEGACY pass first; suite 11,629 → 11,668/0; `--listAll` ×8
byte-identical; partitionCheck ×8 EQUIVALENT; pass table 427 → 426 (the
row gone; checkSpine 19.4–19.8 s repeat runs, in-band); warning-clean.
M0.4 running total: top TWELVE tail passes migrated. SESSION TAIL: the
checkPropertyInitialization (#13, ~99 ms — TS2564
strict-property-initialization) slot-move pre-gate LANDED (the pass
hoisted from its pre-spine slot 6 across the spine to the post-spine
slot; suite 11,668/0, listAll ×8 byte-identical). Scope map for the
migrator: STATELESS declare-gated statement recursion
(checkPropertyInitInStatements — skips `declare` classes/namespaces/fns,
recurses class member bodies + PROPERTY INITIALIZERS, blocks/ifs/loops/
switch/try, and expressions via checkPropertyInitInExpr for
ClassExpressions) + the per-class emission checkClassPropertyInit
(walker-local sets only, no ambient reads at the driver level) — the
round-533 stateless-classifier shape, but TYPE-RESOLVING (property
annotations + typeIncludesUndefined + lib-availability suppression).
TWO dispatches: the normal-mode pass (now post-spine, option-gated
!strictExplicitlyFalse && !strictPropertyInitializationExplicitlyFalse)
AND the B439 declarationOnly direct call — the declarationOnly walk
skips spineEnterNode, so the migration MUST keep the recursion walkers
alive for that path (the round-630 shared-walker rule: spine classifier
mirrors the surviving walker, stays in sync). Consumers: the only
TS2564 list op elsewhere is checkInKeywordTypeguard's whole-file
wipe-and-pin, which runs after both slots — no ordering hazard. NEXT
session starts at its migration.**

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

**PERF — the post-inversion performance arc (owner-approved 2026-07-20, round 618:
"proceed according to your recommendations"; measurements + rationale in the
round-618 session note and the rewritten docs/ARCHITECTURE-RETHINK.md § 6). Ground
rules: the INV rules unchanged, PLUS wall-clock claims are decided ONLY by
interleaved A/B medians — anything priced below the ±2% drift band folds into a
structural item instead of landing alone.**

- [x] **(M0.1) Tail triage — CLOSED round 620 with the deletion hypothesis doubly
  dead.** Phases (a)–(c) ran round 619 (PassLab facility, corpus census —
  artifact `docs/perf/pass-census-round619.txt`, now carrying a correction
  header); the (d) consumer trace (round 620) OVERTURNED the "23 census-silent
  → deletion-ready" verdict, which rested on two flaws: (i) the census records
  only net-POSITIVE deltas — wipe-and-pin walkers (removeAll+pinDiag, net 0),
  rewriters, retractors, and collectors are census-silent while load-bearing —
  and (ii) Phase B's suite green was a FALSE GREEN: Inv0PassTimingTest's
  cleanup assigned `PassTiming.disabledPasses = emptySet()`, re-enabling the
  lab's disables for every test class after 'I' (the whole generated corpus);
  fixed to save-and-restore. The honest disable experiment (fixed cleanup,
  `--rerun`) fails 26 tests: 20 of the 23 are corpus-pinned (incl. one LOCAL
  pin — Inv4SpineBatch27Test for checkCrossFileUseBeforeDeclaration —
  invisible to a corpus-only census). DELETED (the real pool, 3 pure adders):
  checkModuleNoneConflict (TS1148) + checkExportAssignmentInSystem (TS1218) —
  module `none`/`system` are tsgo-removed kinds, their corpus tests
  generator-skipped — and checkUnicodeSurrogatePairImportBinding
  (unicodeEscapesInNames02's TS1127/TS2305 now flow from the general
  scanner/module-member paths; its errors subtest stays green without it),
  plus orphaned helpers. Gates: full suite 11,379/0; `--listAll` ×8
  byte-identical pre-vs-post on all 8 profiles; build warning-clean. Net wall
  value ≈ nil — the whole ~6.2 s tail is pinned; (M0.4) migration carries the
  lever. LAB DISCIPLINE addenda: `build/pass-lab.txt` is NOT a Gradle input
  (always `--rerun` a lab experiment), and a lab-run verdict is unverified
  until the disable is proven active in the SAME JVM that ran the tests.
- [x] **(M0.2) kindId table dispatch — DONE round 621 (2026-07-20), three
  commits.** NodeBase.kindId (dense per-CLASS Int, stamped by each class's
  `init` block — survives `copy()`, unlike nodeId/parent) + NodeKind.kt (138
  dense consts + the sealed-exhaustive `nodeKindIdOf` compile gate);
  forEachChild → javap-verified tableswitch 0..137; the 3 hot checkSpine
  dispatchers (spineEnterNode terminal when / spineUResEnter /
  spineUResDispatch) + the 13 remaining per-node walker whens → kindId
  lookupswitch (~5 int compares over sparse arm subsets). ccetSpineEnter
  deliberately SKIPPED (5-arm when with `is Block -> when (parent) {` +
  a union-smart-cast-dependent multi-class arm; cost/benefit). MEASURED:
  interleaved A/B (5 pairs, compiler profile) A 31,747 → D 30,713 ms median =
  **−3.3%, D wins 4/5 pairs** — inside the priced 2–4%. Gates: suite 11,385/0
  (+6 NodeKindIdTest pins), listAll ×8 byte-identical at each commit,
  warning-clean. Lesson: the scripted conversion mis-cut FOUR two-line
  `if`-header arms into empty-if mangles — corpus caught 3, a structural scan
  (line ending `{` + dedented bare `}`) the 4th; see the session note.
- [ ] **(M0.3) Layout campaign** (JFR-evidenced ~15% of wall in HashMap+String
  equality with NO single hot map — structure-class work, one interleaved-A/B'd
  slice per commit): (i) name atomization (Identifier → Int atom at scan time;
  int-keyed scope/member maps; a globals-miss bitset — the 1.48M probes are 99%
  miss); (ii) NodeLinks/SymbolLinks record consolidation over per-file dense
  nodeId arrays (tsc's exact structure; symbol ids need per-worker dense spaces
  under INV.6 — node ids are per-file dense already); (iii)+(iv) **DONE round
  621: −3.9% wall (31,180 → 29,955 ms median, 5/5 pairs)** — `LongKeyMap`
  (open-addressing Long→V, EXACT packed-id keys, 0L sentinel) fast-paths the
  three intern caches' dominant shapes (null/empty/1-arg refs — null/empty
  pack alike, reproducing the old string key's `"id|"` conflation
  byte-exactly; 2-member unions/intersections; bigger shapes keep the string
  maps) + the `normalizePath` memo; (vi) **DONE round 622: −2.2% wall
  (30,364 → 29,697 ms median, post wins 5/5 pairs)** — `IntKeyMap`
  (open-addressing Int→V, `Int.MIN_VALUE` sentinel: symbol ids span the
  positive main space AND the ≤−2 INV.2(c) scope space, so 0/negative are
  legal keys) replaces `HashMap<Int, ·>` for symbolTypes/declaredTypes/
  symbolTargets, and `NarrowFlowMemo` (parallel int-key/int-depth/Type
  arrays, serve/overwrite depth rules byte-exact, pinned both directions in
  IntKeyMapTest) replaces the narrowing walks' per-invocation
  `MutableMap<Int, Pair<Int, Type>>` — a fresh map per depth-0 walk
  (~111k/compile) allocating a boxed key + `Pair` + map node per store on
  the hottest checker path; (vii) **DONE round 622: −2.6% wall (30,124 →
  29,351 ms median, wins 4/5 pairs)** — int-specialized `NarrowSeen`
  (open-addressing IntArray slots + tombstone removal — popToMark removes
  in reverse insertion order, which linear probing cannot slot-shift;
  EMPTY slots only from rehash, so present-id probes never meet EMPTY
  early — + IntArray add-log; was a double-boxing HashSet+ArrayList on
  every flow-node visit), pinned by a 60k-op randomized oracle vs the old
  form; (v) undo-log
  (the proven NarrowSeen mark/pop pattern) replacing HashMap(other) scope
  copies (putMapEntries 1.1%) — also reduces M1's epoch churn. Do NOT reach
  for a JVM-only map library (build-change guardrail + multiplatform);
  `LongKeyMap`/`IntKeyMap` are the in-repo reusable pieces for later slices
  (IntKeyMap values are non-null and never iterated — the compiler flags
  both constraints at any unsuitable conversion site); (viii) **DONE round
  623, measured NEUTRAL (−0.30% median over 10 interleaved pairs, post wins
  6/10 — below the drift band, NO wall claim)** — lazy/unboxed Parser line
  starts (the eager per-parse table was 5.3% of JFR self samples, only ever
  consumed by diagnostic line/col formatting), the
  `fileDeclaresNonGenericType` fileResults-index + `file|name` memo (was an
  un-memoized per-type-reference top-level statement scan — quadratic
  insurance for bigger projects), and ccetSpineEnter's kindId dispatch (the
  one dispatcher M0.2 skipped, now hand-converted). Landed as structural
  slices on the corpus + listAll ×8 byte-identity gates; the JFR lesson
  (counted-loop self-% is safepoint-bias-inflated + parallel-crawl savings
  don't move serial-dominated wall — A/B before believing any self entry)
  is in the round-623 session note.
- [ ] **(M0.4) Migrate the surviving pinned tail** into the spine (the documented
  migration-pattern zoo), cost-descending; retire dead migration scaffolding as
  it goes (emit-twice arms whose legacy side is gone, the dead m3
  truncation-mark blocks). Post-round-619 this carries the WHOLE tail lever
  (~6.2 s, all corpus-pinned — the deletion pool measured 59 ms): the worklist
  is the `--passTiming` cost table intersected with
  `docs/perf/pass-census-round619.txt` (top by cost at the round-624 HEAD
  table: checkObjectSpreadInvalidTypes 165.6 ms — **MIGRATED round 624**,
  checkArrayPushDiscriminatedUnionElements 138 ms — **MIGRATED round 624**,
  checkImplicitThis 127 ms — **MIGRATED round 625** (the frameless variant:
  a pass threading ONLY downward context — no statement-ordered state —
  migrates as a pure pull-based per-anchor ancestor fold, no frames, no
  leave hook, no memo when anchors are rare),
  checkFnTypedParamCalls 119 ms — **MIGRATED round 626** (the downward-MAP
  variant: FnParamCtx rebuilt-at-boundaries/accumulated-through-boundaries
  reproduces as the pull-based fold WITH a per-boundary-child ctx memo —
  anchors are every Identifier-callee call, too frequent for the round-625
  memo-free form — plus a memoized BINARY reach classifier: no multi-state
  statuses needed when every (parent kind, child slot) pair decides descent
  unambiguously),
  checkAbstractClassInstantiation 113 ms — **MIGRATED round 627** (the
  collector-prepass variant: four FILE-scoped collectors reproduce as
  per-file spine-setup state, not frames; the statement-LIST overlay
  (add-abstract-then-remove-shadowed, a pure function of the ancestor
  list-owner chain SourceFile/Block/ModuleBlock/CaseClause/DefaultClause)
  rebuilds pull-based per anchor with a per-owner memo; the
  `[A].map(cls => …)` callback-param typeof extension recovers on the
  anchor climb folded OUTERMOST-first — node coverage is identical
  between the legacy handled/unhandled branches, so the reach classifier
  needs no special case; no ambient sandwich — the emission reads no
  checker ambient),
  checkSymbolToStringConversions 108 ms — **MIGRATED round 628** (the
  downward-SETS variant: accumulate-only (symbolNames, tpNames) sets
  rebuild pull-based per anchor; the per-body whole-list locals PREPASS
  reproduces as per-boundary LEVELS with only fn bodies and ModuleBlocks
  as collection boundaries — inner Block/clause re-collects were always
  subsets; two reach edges differ from the fp/ai classifiers: case-clause
  and bare for-initializer EXPRESSIONS are reached),
  checkDefiniteAssignmentViaFlowGraph 105 ms — **MIGRATED round 629** (the
  FILE-END variant: a pass whose per-file body is a positional dedup scan
  over prior diagnostics + whole-file flow walks migrates as a dispatch in
  checkSpine's per-file loop AFTER spineWalkFile returns — never
  per-anchor — so the dedup scan sees the file's spine-emitted TS2454s;
  the walker family stays verbatim, the only ambient install is
  currentFlowGraph save/restore, and the B223 sibling stays at its own
  pass slot since it scans no prior diagnostics),
  checkSameTargetReferenceCastOverlap ~123 ms — **MIGRATED round 630** (the
  SHARED-WALKER variant: only the pass's whole-file driver is deleted — the
  walkTypeAssertionsInStmt/-InExpr recursion SURVIVES for the cast-overlap
  sibling passes, so the reach classifier mirrors the shared walker's arms
  and must stay IN SYNC with any future walker-arm change; the first
  TYPE-RESOLVING tail migration — per-anchor getTypeOfExpression/
  getTypeFromTypeNode/relation calls interleave into the spine walk, gated
  clean by corpus + listAll ×8; ambient sandwich = currentCheckFileName +
  a nulled currentFlowGraph around the emission pair),
  checkBindingPatternComputedIndexSig ~120 ms — **MIGRATED round 631** (the
  MULTI-ANCHOR-KIND variant: three emission families dispatch from one
  enter hook over seven anchor kinds, member-parameter emissions gated on
  the member's PARENT kind — objlit/class-EXPRESSION members emit,
  FunctionDeclaration/class-DECLARATION members never do; the reach
  classifier is a FROZEN copy of the deleted walker's arms, deliberately
  NOT shared with the surviving cast walker's spineCoEdge, which it
  matches except FunctionDeclaration parameter defaults; the TS2537
  emitters install the spine-entry RESTING currentFileLocals per emission
  — the legacy pass never installed it),
  checkConstEnumDiagnostics ~123 ms — **MIGRATED round 632** (the
  FILE-GATED variant: the legacy whole-file collectConstEnumDecls gate
  reproduces as per-file setup state — anchors inert in files without
  their own const enum; the TS2567 top-level merge scan rides setup; a
  resolution-CONDITIONAL walker descent (property/element-access bases
  skipped when the base IS a const enum) reproduces as an unconditional
  edge + an anchor-side parent pre-filter, exactly equivalent because
  neither branch can emit at a base — keeping the classifier purely
  structural), then
  checkNullTypeAssertionOverlap ~104 ms — **MIGRATED round 633** (the
  FLAG-ARM-LIFT variant: the `inNullCastOverlapPass`-gated emitters
  lift out of the SHARED walker onto the round-630 anchors —
  spineCoStatus/spineCoEdge reused verbatim; binderResults-iterating
  driver → the spine's partition view, gated `--partitionCheck 2`
  EQUIVALENT ×8), then
  SKIP checkCrossFileModuleAugmentationDuplicates (114 ms — CROSS-FILE
  aggregation, not per-file spine material), then
  checkProtectedMemberReadAccess ~103 ms — **MIGRATED round 635** (the
  PUSH-BASED ORDER-DEPENDENT variant, the round-531 arith pattern's first
  M0.4 application: a pass whose downward map is statement-order MUTATED
  (per-declaration `vars[nm] = …` recordings that LEAK through
  block/if/loop/arrow descents and COPY at nested-fn boundaries)
  reproduces as LIFO frames at fn-like boundaries + per-declaration
  recordings at VariableDeclaration LEAVES (the legacy walk-then-record
  order), with a 5-STATE reach classifier — CONTAINER_FILE/CONTAINER_NS
  split because only FILE-level ExpressionStatements are walked with the
  per-file topVars map, installed by INSTANCE so IIFE-body recordings
  persist across top-level statements; the `=`-LHS write skip is an edge
  (LHS subtree never read-walked, the write check fires at the
  BinaryExpression anchor under the frame-maintained pmrInClassMethod
  gate)), then
  checkPropertyInitialization ~99 ms — **MIGRATED round 636** (the
  MULTIPLICITY variant: the legacy ClassDeclaration statement arm
  double-walks member bodies — checkClassPropertyInit's nested recursion
  PLUS the arm's own member loop — so nested classes emit 2^depth
  duplicate TS2564s, reproduced by an INT-valued reach classifier
  returning a VISIT COUNT (spinePiMult: a bottom-up climb multiplying
  per-edge factors {0,1,2}; every factor local to one edge, no
  multi-state fold — the arrow/fn-expr partial-body restriction resolves
  by peeking at the Block's parent); the anchors repeat the split-out
  checkClassPropertyInitEmit that many times; the recursion walkers
  SURVIVE for the B439 declarationOnly dispatch — the round-630
  shared-walker rule, spinePiEdge mirrors them);
  checkGenericIndexWrite 117.3 ms — **MIGRATED round 637** (the
  DOWNWARD-MAP variant's third application: the (tparams, tpProps,
  refs) triple rebuilds pull-based per anchor with a per-boundary-child
  memo — tparams ACCUMULATE through class/fn boundaries, refs REBUILD
  per fn-like boundary from params + the body-WIDE collectTpLocalsMap
  prepass (whose descent is NARROWER than the scan's — switch/try
  locals uncollected, frozen + pinned), tpProps from the nearest
  enclosing class member (RESET by a nested FunctionDeclaration,
  cleared for property initializers); anchors are `=` binaries with a
  paren-unwrapped ElementAccess LHS; zero TS2862 on all 8 profiles →
  the listAll gate pins pure non-perturbation);
  checkArgumentsCollision 116.8 ms — **MIGRATED round 638** (the
  CONSTANT-CONTEXT variant, the simplest yet: the only downward value is
  the per-file isModule boolean, so no frames, no ctx memo — the
  per-construct declare/body gates re-derive at the anchor from the
  construct node + its parent kind (class-DECLARATION members need
  body + !class-declare and its set-accessors never param-check, while
  class-EXPRESSION/objlit members param-check unconditionally — frozen
  asymmetries, pinned); a WIDER reach than gIdx (arrows/fn-exprs/
  class-expr members/objlit members/template spans/typeof operands
  descend; if/ternary conditions, loop/switch heads, class-decl property
  initializers, declare-namespace bodies stay silent) = a fresh edge
  set; the run-level dispatch gate (target < ES2015 || any non-dts
  module file) becomes the run-active flag);
  checkEvolvingEmptyArrayImplicitAny 103.2 ms — **MIGRATED round 639** (the
  PER-LIST-OWNER variant: a per-STATEMENT-LIST scope pass dispatches each
  scope's list ONCE at its owning SourceFile/Block/ModuleBlock enter, gated
  by a multi-state reach classifier carrying the deleted evRecurseScopes'
  level-skipping quirks — try/catch/finally clause statements and
  case-clause statements recurse WITHOUT forming a scope list (a candidate
  declared directly there never fires) while a Block statement inside them
  IS a scope; arrow/fn-expr bodies and class EXPRESSIONS are never scopes;
  a dotted `namespace A.B` IS one (the parser keeps a direct ModuleBlock
  body — the scope map's "never" guess was wrong, caught by the pins);
  Part 2 is TYPE-RESOLVING → per-dispatch ambient sandwich of resting
  currentFileLocals + per-file currentCheckFileName + a nulled
  currentFlowGraph);
  checkUndefinedClassInterfaceName 123.9 ms — **MIGRATED round 640** (the
  TWO-INTERLEAVED-WALKS variant: a pass running two recursions with
  disjoint node sets — the statement-only name-check walk (never descends
  fn/class-member bodies) + the yield walk started at name-reached
  FunctionDeclarations — reproduces as ONE multi-state classifier whose
  statuses carry the walk identity AND the downward generator flag
  (UY_NAME / UY_YGEN / UY_YNON, plus UY_MEMBER bridging a yield-walked
  container's member to its body/initializer); the frozen member filters
  ride the container edges — class DECLARATIONS walk accessor bodies +
  prop initializers, class EXPRESSIONS method/ctor only, objlit members
  methods only, accessors never; the legacy left-spine BinaryExpression
  fold reduces to plain left/right edges, reach-equivalent; zero
  emissions on all 8 profiles → the listAll gate pins pure
  non-perturbation);
  checkSuperRefInRebindingScope 113.1 ms — **MIGRATED round 641** (the
  rebound-boolean-as-status variant: the walk's one downward boolean
  rides the classifier status — fn-decl/fn-expr bodies reset to rebound,
  arrows/ModuleBlocks preserve, class-member bodies/prop initializers
  reset to clear via a member-carrier status; the frozen `super(...)`
  CALLEE skip is the anchor's direct-parent gate so a parenthesized
  super callee still fires; object literals skipped entirely — the
  sibling checkSuperInObjectLiterals is position-disjoint);
  checkInvalidAssignmentTargets 105.8 ms — **MIGRATED round 642** (the
  INT-depth classifier's second application: the shared `checkDepth`
  frame counter reproduced per node with +1 on every expression parent's
  outgoing edge — statement lists nested inside expressions inherit the
  elevated ambient — and NO right-spine absorption, so deep chains prune
  at the 200 cap, pinned at the exact boundary; the orphaned checkDepth
  counter deleted from Checker + CheckerState);
  checkTypeParameterDefaults 150 ms — **MIGRATED round 643** (the
  SPLIT-PRODUCER variant + the first PARSE-RECORDED candidate set: a
  pass whose side-set write cannot ride the spine — cross-file/
  earlier-in-file display consumption — SPLITS: the TS2368/TS2744
  emissions anchor at the ten TP-list-bearing construct kinds over a
  binary reach classifier, and the pre-spine producer consumes
  SourceFile.typeAliasesWithTpDefaults (recorded at the parse site,
  moduleSpecifiers-style — no tree walk; 0.4 ms vs the legacy 150 ms
  row) FILTERED through the SAME classifier — one frozen edge set
  serves both halves, and a speculative-parse discard classifies
  unreached via its detached parent chain. Producer-scan lesson: a
  forEachChild worklist re-scan of the tree costs MORE than the legacy
  walk it replaces (264 ms raw, 218 ms TypeNode-pruned) — parse-time
  recording is the shape for future split producers);
  next per-file candidates by cost (round-642 table):
  checkExpandoFunctionNestedReads 99 ms — slot-move pre-gate LANDED
  round 643 (scope map in the round-643 session note; next session
  starts at its migration), then checkStrictModeIdentifiers
  96 ms, checkConstLiteralComparisons 95 ms, checkSuperInObjectLiterals
  91 ms (98 passes >20 ms carry 5.3 s of the 6.2 s). Migration protocol per
  pass (the round-624 template): slot-move pre-gate commit (intact pass to the
  post-spine slot, corpus + listAll ×8), then the migration commit (frames at
  the legacy copy edges, memoized reach classifier, per-dispatch ambient
  sandwich + pull-based TP rebuild, local pins, corpus + listAll ×8). A
  single-pass wall delta (~0.5%) is BELOW the drift band — the per-item
  evidence is the `--passTiming` table (the pass's row gone, checkSpine's row
  not inflated), not an interleaved A/B; A/B the ARC once several passes land.
- [ ] **(M1) Identity stability → revive the two memo designs** (the ≤15–20 s
  path; tsc's flow cache — per-(refKey, flowNode) over interned types — is the
  existence proof that the (f2) fold works once types are canonical).
  (a) attribute the epoch churn: 80k of 111k walks run at fresh epochs because
  the walk's own recordings bump the fences — split read-relevant vs
  record-only state, or fence per-map; (b) canonicalize narrowing outputs
  (filters over interned unions must yield interned results; literal interning;
  instantiated-member caching ON the Type.Reference, deleting
  resolveGenericPropertyType's fresh-minting); (c) re-attempt the (f2)
  per-(reference, flowNode) fold SHADOW-FIRST (the round-595 epoch
  infrastructure + shadow memo are the instruments), then (f1). Revert rules
  per the INV ground rules.
- [ ] **(M2) Parallel scaling Phase 1** — shared frozen collectors: compute the
  318 program-wide collectors once, freeze, share read-only (the immutability
  audit in docs/parallel-caching.md). Sequenced AFTER M1 (canonical types
  shrink the per-worker warmup that capped w4 flat). Honest cap: the
  4-core/7.7 GB box limits what scaling we can demonstrate locally.

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

- [x] **(cta-m3e) Lift the anchor-SIMPLE restriction — reproduce the legacy
  nested-dispatch localTypes recordings spine-side (queued round 570c with the
  design from the BarrelCheckDefinedReturnTest root-cause).** The blocker: legacy
  nested-scope dispatches RECORD into the shared `currentLocalTypes` and the spine
  frames have no reproduction, so an anchored statement after a switch/if/loop
  reads an incomplete map. Design notes (verified in-code round 570): (a) the leak
  is PER-ARM — switch clauses LEAK (clause dispatch shares the map), a NARROWING-
  wrapped if-then (extractNullNarrowing non-null — a pure function of the
  condition, callable at spine time) DISCARDS its recordings on restore, a
  non-narrowed if-then Block LEAKS (the Block arm copies varTypes but NOT
  currentLocalTypes), loop/try bodies leak via the same Block arm; (b) the
  mechanism: a RECORDING-ONLY sandwich at nested VariableStatement enters within
  an active fn frame — install the frame maps, run the real
  checkVarDeclAssignability under a diagnostics mark, truncate ALL its
  diagnostics (nested statements stay legacy-owned for emission), keep the map
  writes; skip inside narrowing-discarded regions; (c) spine statement-position
  Block/clause frames already model the map SHARING — the narrowed-if discard
  needs a COPIED-map frame rule keyed on extractNullNarrowing; (d) gates: the
  barrel repro shape as a local pin (switch-clause recording feeding a later
  anchored statement's member reduction), corpus + listAll ×8. Alternative if the
  recording-only sandwich disturbs first-touch caches: migrate the nested
  dispatchers' arms themselves (bigger). DONE round 571 — the recording-only
  sandwich landed clean (one extra invariant found: TS2563 trip-state suppression
  during recordOnly, CfaTooLargeBailTest); see the session note.
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
- [x] **INV.3 Per-file scoping — ARC COMPLETE round 513** ((a)-(d) all landed; checkbox reconciled round 612) — decomposed round 500 (facts verified in-code:
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
  - [x] **INV.3(d) Retire the merge + delete the ecology — COMPLETE round 513** (checkbox reconciled round 612; the body below records the full campaign). Stop merging
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
- [x] **INV.4 Single-pass check spine — CLOSED round 599** (see the round-599 note: migration + retirements banked −13% wall + ONE authoritative walk; the (f) memo/fold designs are measured dead-ends until INV.5 canonical types). `checkSourceFileOnce` per-node dispatch;
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
  - [x] **INV.4(e) The top-3 giants — COMPLETE round 592** (cta 586 / cpa 585 / ccet 592 all retired; checkbox reconciled round 612). checkPropertyAccess (3.66 s @ round-542
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
    **SUPERSEDED (rounds 555/556): the 542/543 conclusions above are STALE —
    the probe/slot-move scripts matched a COMMENT containing
    `pass("checkSpine")` and inserted the giant ~100 passes early (see the
    round-555 CLAUDE.md gotcha), so the "coupling chain" / "blocked on
    INV.5" findings were position artifacts (possibly compounded — the
    INV.5 (a)/(c)/(d1)/(e) landings since may also have genuinely
    order-freed some caches). At the CORRECT position, with exactly the two
    round-543 producers hoisted (landed round 555), ALL THREE giants
    slot-moved to the spine block corpus-green + listAll-×8-identical
    (landed round 556; legacy relative order g-cta → g-cpa → g-ccet
    preserved). g1a/slot-move pre-gates: DONE for all three. (g1b) DONE
    rounds 557/558 — 33 reach pins (Inv4SpineG1PinsTest statement arms,
    Inv4SpineG1PinsExprTest expression arms), all verified on the current
    walker.**
    **(g1c) DESIGN (round 559, from the g1b arm reads): the migration ORDER
    must be cta FIRST — the giants share a CROSS-PASS residue channel:
    checkPropertyAccess's driver does NOT reset currentLocalTypes per file,
    so it consumes checkTypeAssignability's recordings (round 542's
    noImplicitAnyForIn TS7053 finding: the `var k1 = x[i]` receiver type is
    cta residue). Migrating cpa into the spine FIRST would run its per-node
    work BEFORE the still-slot-resident cta → the residue disappears.
    Migrating cta first preserves cta-before-cpa; note per-node
    interleaving ≠ pass-after-pass for BACKWARD residue reads (a node
    consuming a LATER node's recording) — the pass-after-pass semantics let
    cpa see cta's COMPLETE final state incl. later files; audit any
    backward consumption during the cta migration (candidate remedy: the
    w2 own-recording template — each pass records what it consumes).
    Frame model per the INV.4(d) playbook: (1) per-dispatch ambient install
    of currentFlowGraph/currentLexicalScopes (NEVER walk-wide on the spine
    — the 78-test hazard; the legacy walk-wide set is reproduced by
    installing around every g1 emission); (2) fn-like scope copies
    (fn-decl/method/ctor/set-accessor/arrow/fn-expr) as push-frames at
    body enters (save map refs, install copies + populateParameterLocalTypes
    + applyBodyLocalShadowing/applyAmbiguousBlockScopedLocals), popped at
    leaves — GetAccessor bodies deliberately have NO scope copy (chunk-1
    pin); (3) contextualType as a kinded downward carrier at call-arg /
    objlit-property / arrow-body edges (the w3 template; cleared at
    fn-expr body and spread edges); (4) propertyAccessEnclosingNamespaces
    pushed at non-declare ModuleDeclaration enters; (5) enclosingClassType
    as a pull-derived member-chain context (null across fn-decl/fn-expr
    boundaries, KEPT through arrows — chunk-2 pins), with the this-param
    override at method enters; (6) inStaticClassMethod save/set/restore at
    class-member enters; (7) currentEnclosingEnum at EnumDeclaration
    enters; (8) reach quirks as classifier edges: for-INIT unreached,
    tagged-template spans unreached, interface bodies unreached,
    shorthand-property initializers unreached.**
    **(g2 = cpa DECOMPOSITION, queued round 576 — the cta migration (rounds
    560–576, m1..m3m) is COMPLETE for the emission surface; work these
    top-to-bottom, one commit each, mirroring the proven cta sequence):**
    - [x] **(cpa-m1) Legacy-side audit instrumentation** — DONE round 577. (the cta-m2a
      pattern): a test-only `cpaAuditRecord` at the top of
      checkPropertyAccessInStatement fingerprinting the threaded+ambient
      context per DIRECT statement — enclosingClassType (threaded param),
      currentLocalTypes/currentParamBindingNames/currentEnumConstrainedParams/
      currentShadowedNames (fn-boundary copies), inStaticClassMethod,
      propertyAccessEnclosingNamespaces depth, contextualType. FINGERPRINT
      HAZARD (scouted): cpa's currentLocalTypes maps name→Type, not strings
      like cta's varTypes — Type.id is resolution-order-sensitive between
      legacy-time and spine-time, so fingerprint by sorted name set +
      per-name typeToString (test-only cost), never by id.
    - [x] **(cpa-m2-prep) Close the residue channel legacy-side** — DONE
      round 578: per-file `currentLocalTypes` reset in the cpa driver + the
      element-access own-recording; corpus green + listAll ×8 byte-identical.
    - [x] **(cpa-m2) Spine-side frame skeleton** — COMPLETE round 580 (tier 2:
      unified edge-reach walker, arrow/fn-expr/ClassExpression frames,
      cpaCtxAt/cpaEctAt; full bidirectional audit equality).
      tier 1 (statements) DONE round 579 ((cpa-m2a): fn-decl/method/ctor/
      accessor frames, ns frames, loop-var overrides, per-decl-leave
      recordings, the immediate-position fingerprint gate); REMAINING
      (cpa-m2b): tier 2 — DESIGN COMPLETE (scouted round 579b, in-code):
      (i) arrow Block-body frames: 3-map copy + populate + shadowing +
      ambiguous + contextual param registration from ctx-at-arrow;
      ect/inStatic PRESERVED through arrows; (ii) fn-expr body frames:
      3-map copy + the fn-expr's OWN param semantics (annotated -> set,
      UN-annotated -> REMOVE from localTypes — not populate!) +
      destructured-name collection + contextual registration + shadowing +
      ambiguous; body walks with ect = NULL; (iii) ClassExpression member
      bodies: the tier-1 class-member frames extended to ClassExpression
      owners with a per-visit synthetic anon-class type (display
      "(Anonymous class)" — fingerprint-equal across fresh synthetics);
      (iv) ctx PULL-derivation cpaCtxAt(node): STOP-null at any statement
      edge; DEFINE at call-arg (the argCtxTypes computation: single-sig +
      B86.1b inference mapper + literal mapper; multi-sig strictSelect /
      every-overload-callable), objlit PropertyAssignment initializer
      (propCtx from ctx(O).members, non-any/error else null), SpreadAssignment
      (null), arrow EXPRESSION body (bodyCtx = single-sig return); INHERIT
      through paren/conditional/binary/array-literal/template-span/as/
      nonnull/prefix/postfix/await/spread AND NewExpression args (a legacy
      quirk: new's args inherit the OUTER ctx — no clearing); ctx is
      provably NULL at every statement dispatch (arrow Block bodies get
      bodyCtx=null; fn-exprs null explicitly); (v) the tier-2 chain test
      needs an expression-edge REACH classifier (the spineUResExprEdge
      pattern) — legacy expr-walk quirks: TaggedTemplate walks the TAG only
      (spans unreached), ForStatement INITIALIZER unreached (condition +
      incrementor reached), ForIn/ForOf initializer AND iterable expression
      unreached (ForOf's getTypeOfExpression is not a walk), decorators
      unreached, objlit METHOD bodies unreached (else -> {}),
      ShorthandPropertyAssignment unreached, CommaList unreached,
      arrow/fn-expr PARAM DEFAULTS unreached; statement-edge expression
      roots: Var initializers / ExprStmt / Return / If condition / While-Do
      condition / Switch subject + case exprs / Throw / With /
      ExportAssignment / Enum member inits / Class heritage + members.
      (the cta-m2b/m2c pattern — expect quirk-extraction cycles; the known
      quirks from the g1c design: GetAccessor bodies have NO scope copy,
      enclosingClassType is KEPT through arrows / nulled at fn-decl+fn-expr
      boundaries, contextualType clears before bodies, the pass is
      PASS-PRIVATE for currentParamBindingNames per the w2 lesson, and the
      driver does NOT reset currentLocalTypes per file — cpa consumes cta
      RESIDUE cross-file (round-542 noImplicitAnyForIn TS7053), which the
      frames must reproduce or own-record).
    - [x] **(cpa-m3…) Emission moves** — COMPLETE rounds 581-583; **(cpa-retire)
      LANDED round 585: the checkPropertyAccess legacy pass is DELETED** (the
      first giant off emit-twice; audit scaffolding removed with it).
    - [x] **(cta-retire) LANDED round 586: the checkTypeAssignability legacy
      pass is DELETED** (both migrated giants off emit-twice; audit
      scaffolding removed).
    **(g3 = ccet DECOMPOSITION, queued round 588 from the in-code scout —
    the LAST giant; mirror the twice-proven cpa sequence, one commit each):**
    - [x] **(ccet-m1) State-model scout — COMPLETE round 588b.** Additional
      facts: the expr walker has NO contextualType channel (plain recursion);
      arrow/fn-expr arms copy 2 maps (localTypes+paramBindings) + register
      own params anyType + Block-body shadowing; the ObjectLiteral arm does
      a SCOPED localTypes copy around member walks; EMISSIONS ARE
      PER-CALL-NODE (checkSingleCallExpressionTypes at CallExpressions,
      checkSingleNewExpressionTypes at NewExpressions) — so the m3 anchor is
      per-Call/New-node at ITS OWN LEAVE (the probe discipline), with frames
      supplying ambient; no emit-via-containing-walk ownership complication
      (nested-fn-body calls anchor at their own nodes under spine-maintained
      frames). DECISION: pins-first — NO fingerprint audit (CcetAnchorTest
      exactly-once pins + corpus/listAll gates; the audit pattern's quirk
      extraction is replaced by the gates, which caught all three cpa-m3a
      quirks anyway).
      ORIGINAL ITEM: **(ccet-m1) State-model scout completion + audit-or-pins decision.**
      Scouted so far (in-code, round 588): the driver resets currentLocalTypes
      per file since round 584 (residue-free); FunctionDeclaration arm copies
      currentLocalTypes + currentParamBindingNames AND pushes the fn's OWN
      TPs onto currentTypeParamScope (constraint materialization included),
      then populateParameterLocalTypes + applyCallTypesBodyLocalShadowing +
      shadowNestedFunctionNames (the M1.11 ecology — presence-only consults,
      the first-touch cache-poisoning hazard is documented in the helpers);
      ClassDeclaration arm pushes class TPs + resolves the class symbol via
      globals ?: inferenceNamespaceStack.last().exports; ModuleDeclaration
      pushes inferenceNamespaceStack via resolveModuleDeclNamespaceSymbol
      (DOTTED namespaces handled — unlike cpa's arm); the IfStatement arm
      does a SCOPED single-name union-narrowing override (save/write/restore
      around the then-walk); the VariableStatement arm ORDER-RECORDS
      annotated-callable + B98.r126 + callable-shadow entries. REMAINING to
      scout: the expr walker's arms (contextual channels?), the class-member
      dispatch, funcParams/currentFunctionParams overlay production, and
      currentEnclosingEnum/classForThis usage. DECISION POINT: rounds
      585/586 showed the audits end as deleted scaffolding — consider going
      pins-first (CcetAnchorTest exactly-once) + frame-skeleton-with-
      corpus-gates instead of the full fingerprint audit; the audit earned
      its keep on cta/cpa quirk EXTRACTION, so keep it only if the frame
      skeleton's first corpus gates diff untraceably.
    - [ ] **(ccet-m2) Spine-side frame skeleton — FULL SPEC (round 588c
      in-code read of every arm):** CcetFrame fields: localTypes(HashMap) +
      paramBindings(HashSet) [copied at fn-decl/method/ctor/contextual-fn
      boundaries + arrow/fn-expr expr-arms], tpScope+tpAst [fn-decl pushes
      OWN TPs with interning + constraint materialization; class arm pushes
      the DECLARED class type's TPs resolved via
      globals ?: inferenceNamespaceStack.last().exports; STATIC methods POP
      the class scope but mint FRESH TPs for their own typeParameters],
      superBaseSig/superBaseType [ctor gets both, method gets Type only —
      from the per-class baseResolution computed under the class TP scope],
      nsSymbol [ModuleDeclaration arm, NON-declare only, dotted-aware via
      resolveModuleDeclNamespaceSymbol], classSym [callWalkerClassStack
      push], the method-body `this` registration [instance methods:
      currentLocalTypes["this"] = getDeclaredTypeOfSymbol(classSym)],
      GetAccessor/SetAccessor bodies walk with NO copies. Var-arm ORDERED
      recordings (interleaved with initializer walks — the cta interleave
      lesson): callable-annotated + union-of-callables + literal-union +
      callable-shadow anyType; the B246 CONTEXTUAL fn-expr channel
      (FunctionType-annotated var + fn-expr/arrow init → params typed from
      the annotation with ?-undefined unions — a frame VARIANT, replaces
      the plain initializer walk); the If-arm SCOPED type-guard narrowing
      override (resolveUserTypeGuardNarrowing at the If enter, save/write/
      restore around the then — the cta-m3i narrowing-frame precedent);
      ForIn/ForOf withForLoopVarShadow around bodies. REACH QUIRKS (differ
      from BOTH prior giants): For-INITIALIZER expressions ARE walked
      (decl initializers + expression form); param DEFAULT initializers ARE
      walked at fn-decl/method/ctor arms (BEFORE the body frame — under the
      OUTER ambient); DoStatement walks body BEFORE condition;
      declare-module bodies are SKIPPED entirely (Declare gate — unlike
      cpa); DOTTED namespace bodies are RECURSED (unlike cpa);
      heritage expressions walk UNDER the class TP scope + class stack;
      objlit arm does a scoped localTypes copy. There is also a
      maxCheckDepth recursion guard (callTypeCheckDepth) at the statement
      dispatcher — reproduce as an int-valued reach cap if fidelity
      requires (the round-535 spineArgDepth precedent). LAST FACTS (588d):
      withForLoopVarShadow copies BOTH maps but ONLY when a loop-header
      binding name COLLIDES (in globals or currentLocalTypes, not already
      in paramBindings) — colliding names are REMOVED from localTypes +
      added to paramBindings; no collision → NO copy (share). Declare-module
      subtrees need a frame `dead` flag (anchors skip; children inherit).
      The If-arm narrowing + ForIn/ForOf shadows reproduce as scoped
      override frames with restore records at the body node's leave (the
      cpa loop-var-restore mechanism). ARROW/FN-EXPR frames push at the FN
      node's enter (the copies wrap BOTH body kinds — expression-body calls
      see the registered params too). Class frames push at ClassDeclaration
      enters (tpScope + classSym + the baseResolution pair computed under
      the class scope), maps SHARED; member-body frames derive from them.
      Implementation staging: (ccet-m2) frames always-on, gates must stay
      IDENTICAL (no emissions move yet — any diff is a first-touch
      coupling to bisect); then (ccet-m3) per-call anchors + marks + pins.
    - [x] **(ccet-m3) LANDED round 591** (merged; the gap-signature gate made
      the interleave FP order-free) + **(ccet-retire) LANDED round 592 — ALL
      THREE GIANTS OFF EMIT-TWICE.** (history: round 590 blocked state:) per-Call/New/TaggedTemplate anchors at
      leaves + the full per-edge reach classifier + legacy marks +
      CcetAnchorTest (8/8, incl. the static class-TP skip-gate pin) + the
      re-enabled decl recordings (the round-589 flip is MOOT under anchors:
      the legacy verdict truncates). Corpus GREEN (11,347/0). BLOCKER: ONE
      interleave FP — the cta return anchor at services.ts:1327 (the
      objectAllocator objlit vs ObjectAllocator) sees CCET-WARMED caches
      (per-node interleaving ≠ pass-after-pass, the round-559 warning) and
      resolves TP-carrying member types (`() => NodeObject<TKind>`) → a
      TS2322 the legacy order never produced (services/server/harness +1).
      A typeContainsForeignTypeParam construct-sig extension did NOT
      suppress (on the branch; possibly resolvedReturnType null at gate
      time, or a non-gate emitter). NEXT WINDOW: (1) identify the emitter
      with the round-472 Diagnostic-init probe keyed (2322, the 1327 start
      offset) on the services profile; (2) fix the gate's REACH or gate
      that emitter (order-free-verdict discipline, both cache states
      silent); (3) structural fallback: defer ccet anchors to a per-file
      second walk. Then merge the branch + gates + (ccet-retire).
      ORIGINAL: **(ccet-m3…) Emission moves** with the leave-dispatch discipline
      (cpa's probe lesson: anchor at statement/expression LEAVES) + the
      recorded-set truncation, then **(ccet-retire)** via the round-585
      experiment template (no-op the dispatch → gates → delete).
  - [x] **INV.4(f) CLOSED round 599 — both wins are measured dead-ends at
    the current cost structure** (f1 memo: the servable calls are cheap;
    f2 fold: confirm-once tax + epoch churn → noise); the real INV.4 win
    was the retirements (−13% wall) + ONE authoritative walk. Revive the
    memo designs after INV.5's canonical types. ORIGINAL: **The two unlocked soundness wins.** Once one authoritative
    walk state exists: the per-node expression-type cache (594,779 calls over
    ~221,844 distinct nodes = ×2.6 recompute), and flow narrowing folded into
    reference typing once (84,469 depth-0 walks, 68% from property access).
    Re-measure against the ≤10 s single-threaded compiler-profile target.
- [x] **INV.5 Canonical types + explicit instantiation — SUBSTANCE COMPLETE round 604** (interning (a), mapper flip (b2), context-keyed nodeTypes (c), budget (d1), generic gate + pin sweep (e) all landed; residuals are deferred/demoted/blocked: (bN) behind the frame redesign, (c2) cosmetic, (d2) hygiene — checkbox reconciled round 612) (absorbs M5.2/M5.3;
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
  - [x] **INV.5(b) Explicit mapper objects — installer flip COMPLETE round
    604 (b2a-b2d4): 87 write sites → 4; the survivors are the spine frame
    LIFO writers (restore-at-leave — not region-formable; the designed
    residual until frames carry mappers). (bN) ambient-field REMOVAL
    stays open behind that frame redesign.** Replace the ambient
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
    (b2b) DONE round 549d: the remaining 3
    aliasArgs installers flipped too — alias substitution ~93.8k,
    constraint-retry ~89.6k, mapped-type per-key ~140.4k; the aliasArgs
    ambient is now single-writer (the bridge); tpScope families next);
    (b2c/b2c'-''', rounds 550a-550d) DONE: ALL resolution-internal tpScope
    installers flipped to the REGION form (`withInstantiationContext(
    scopeMapper(...)) { ... }` — inline, non-local returns preserved):
    resolveGenericPropertyTypeWorker (outer + inner method scope),
    resolveBaseTypesLazy, resolveInterfaceMembersCore (sig + index), the
    getTypeOf* lazies, buildBaseConstructorSignatureForSuper,
    buildSignatureForFunctionLikeTypeNode, reresolveSigParamsUnderClassScope,
    getTypeFromTypeLiteral's method branch, checkConstraintsForTypeArgs.
    REMAINING (deliberately deferred): the walker-level installers (die
    with INV.4(e)), the dual-ambient-field installers
    (checkConstraintsInStatements + currentTypeParamDecls;
    checkMixinClassInStatements + mixinValueScope), the 84067 interleaved
    implicit-any site, and the paired pushFunctionTypeParamsScope; (bN)
    remove the ambient fields (blocked on those). NOTE (c) only needs the mapper AT THE CACHE CONSULT — it can
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
  - [x] **INV.5(d) — (d1) budget DONE round 552; (d2) DEMOTED to hygiene round 611 (checkbox reconciled round 612).**
    **(d2) DEMOTED round 611 (evidence-based): the round-598 depth-0
    attribution puts the ENTIRE relation family at ~927ms — the (d2)
    allocation redesign is no longer a perf lever (the levers are the
    walks + typeOfExpr, both blocked on canonical types). Remaining (d2)
    value is hygiene only: `resolvedPropertyTypes` caches under the
    first-touch ambient scope (a context-keying hole like the pre-548
    nodeTypes) and never caches null results. Re-open only if a
    correctness drift traces here.**
    Delete `resolveGenericPropertyType` fresh-minting + its depth-4 OOM cap
    (the per-recursion-level cache-miss gotcha). **(d1) DONE round 552: the
    depth-4 cap is DELETED — replaced by the per-top-level-relation
    instantiation budget + the param-side foreign-TP gate in
    tryEmitObjectVsNamedUnionArg (see the session note). Remaining: the
    member-table-on-reference allocation redesign ((d2), optional now that
    the budget bounds allocation) and the fresh-minting deletion.**
    **CAP-LIFT PROBE FALSIFIED (round 551, reverted): removing
    `relationDepth < 4` with (a)-interning + the (ref.id, prop.id) memo in
    place still KILLS performanceComparisonOfStructurallyIdentical-
    InterfacesWithGenericSignatures — the deep-stack thread dies after ~20 s
    (OOM → NPE at runWithDeepStack's result unwrap). The blowup is BREADTH,
    not depth: each comparison level mints genuinely NEW (target, args)
    references (growing arg shapes), so the memo never hits and the
    deeply-nested 5-occurrence heuristic (which fires at relation ENTRY)
    doesn't bound the per-level member/signature instantiation between
    bails. The real (d) fix is tsc-shaped: an instantiation-count budget
    (tsc's instantiationDepth/instantiationCount → TS2589) plus member
    tables cached ON the reference, NOT a cap lift. Keep the depth-4 cap
    until then.**
    **BUDGETED-LIFT PROBE (round 551b, also reverted): a per-top-level-
    relation budget of 2,000 fresh worker computations (reset at depth-0
    relation entry, consumed on memo miss, raw fallback on trip) TAMES the
    perf-bomb — corpus fully green 11,252/0 — but exposes exactly ONE new
    FP on all 8 profiles: program.ts:2924 TS2345 `(readonly Diagnostic[] |
    undefined)[]` ⊄ `T[][] | readonly (T | …)[]` (tsc's flatten<T> — the
    documented M3.1 masked gap: tsc infers T, we don't, and the old
    depth-≥4 trivial-pass masked it). A TP-free gate on DEEP substitution
    results does NOT kill it — the outcome flips inside the relation
    (target side), not at the substitution result. VERDICT: the cap
    deletion is blocked on generic inference (M3.1) / the (e)-era
    engine-opening work, not on allocation strategy — sequence (d) with
    (e), and consider a param-side foreign-TP bail at the call-arg
    emission as the enabling slice (corpus-gated; the round-431 gate
    family's rationale applies verbatim to un-inferred PARAM types).**
  - [x] **INV.5(e) Open `canUseTypeEngine`'s generic gate; delete superseded
    pin walkers** (suite-gated per deletion). DONE round 600: sweep verdict
    15/16 load-bearing, checkGenericFnTypeBipartition deleted. Then RETURN to INV.4(e).
    **FIRST HALF DONE round 553: the hasUnresolvedTypeParams skip is
    DELETED (corpus + listAll ×8 identical; the Box<T>-vs-Box<string>
    false negative now fires — Inv5GenericGateTest). Remaining: the
    pin-walker deletion sweep.**

- [x] **INV.6 Parallelism — Phase 0 CLOSED round 609** (6a-6d1: --workers 2 = −17% wall, output sorted-identical, all-8-profile partition equivalence; w4 flat at the per-worker redundancy ceiling — Phase 1 shared frozen collectors is the reopener, gated on an immutability audit; (6e) parallel emit deferred: emit workers would race the shared checker's lazy caches, and benches are --noEmit). Share-nothing checker workers per
  `docs/parallel-caching.md` (trivially partitionable once INV.4 gives a per-file
  check entry); parallel emit on Default + IO write sink; deterministic partition +
  merge via the existing diagnostic sort. Structured concurrency from INV.1.
  - [x] **(6a) The spine partition seam** — DONE round 605: `assignedFileNames`
    gates both spine per-file loops; sequential-equivalence contract pinned by
    SpinePartitionEquivalenceTest.
  - [x] **(6b) Profile-scale equivalence A/B** — DONE round 606:
    `--partitionCheck N` harness; EQUIVALENT on all 8 profiles (w=2) + the
    two stress profiles (w=4). Zero divergences — (6c) unblocked.
  - [x] **(6c) The parallel driver** — DONE rounds 607-608 (6c0 thread-local
    id sequences + deep-stack handoff; 6c1 runInDeepStackWorkers +
    `--workers N`). Measured: w2 −14% wall, w4 flat (per-worker redundant
    fixed cost — see the round-608 note); output sorted-identical to
    sequential.
  - [x] **(6d1) Widen the partitioned region** — DONE round 609: 193
    emission-pass loops on `checkedResults` (318 pure collectors stay
    program-wide); all-8-profile equivalent; w2 −17%, w4 flat. Deeper
    widening = Phase-1 shared frozen collectors (immutability audit) —
    queue that only after INV.5 canonical types or on a >4-core box.
  - [ ] **(6e) Parallel emit** on Default + IO write sink (INV.1's Flow
    foundation; no dashboard delta expected — benches are --noEmit).
- [ ] **INV.7 Productization** (absorbs M5.5/M5.6). Native re-enable (the big-input
  GC inversion should largely dissolve post INV.4/5); watch mode driven by a
  file-event Flow; `.tsbuildinfo`-style incremental reuse.
  - [x] **(INV.7c1) `--watch` minimal watch mode** — DONE round 613 (full
    rebuild per debounced change batch; fileEvents Flow expect/actual;
    end-to-end verified, 46ms warm rebuild). Incremental reuse is (7d).
  - [x] **(INV.7d1) Watch-mode incremental recheck** — DONE round 614
    (reverse-dependency closure over the INV.6 partition seam; full-rebuild
    bails for non-local changes; --watchVerify field gate; equivalence
    pinned by WatchIncrementalTest).
  - [x] **(INV.7d2) The shared-name residual bail** — DONE round 615
    (sharedNameFiles: lib-global KNOWN_GLOBALS ∪ script top-level names;
    bidirectional bail via eligibility + outcome validation; +2 pins).
    Real-lib names outside the curation stay on the --watchVerify net.
  - [x] **(INV.7d3) Cross-process `.tsbuildinfo` persistence** — DONE round
    617 (owner approved the generateBuildInfo build change 2026-07-19):
    `XTSC_BUILD_ID` (git sha, `.dirty`/`unknown` never persist nor reuse)
    stamps `tsconfig.xtsbuildinfo`; cold start hash-validates inputs (incl.
    every `.json` config read via RecordingVfs) and runs the (7d1) closure
    protocol for the changed set under `--incremental --noEmit`; new files
    caught by the outcome shape check. TsBuildInfoTest (+11).
  - [x] **(INV.7a) linuxX64 re-enabled** — DONE round 610: compiles/links/runs
    byte-correct (compiler profile = the exact 46-error floor, 196s debug
    binary; smoke 82ms). EpochMap/Set now composition (K/N HashMap is final).
  - [ ] **(INV.7b) Release binary + native bench row.** PARKED-BY-OWNER
    (round 617, 2026-07-19: "we can switch it off for now"). History:
    BLOCKED-ON-RESOURCES at round 610b — the optimizing link OOM-kills the
    daemon on the 7.7GB box (twice, incl. -Xmx5g + daemons stopped). If ever
    revived: re-attempt on a ≥16GB builder; the debug binary carries
    correctness meanwhile.

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
