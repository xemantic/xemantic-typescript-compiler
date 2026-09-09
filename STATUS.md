# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **196,797** lines (**−3,166 across (P18.53)-(P18.56)**, the first
sustained movement in the extraction direction since the metric was created; 191,070 when it was
created, and the (P18.9)-(P18.37) checker-parity arc ADDED ~5,200, which are fixes and pins, not
extractions). FIVE collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient
surface none for both — `TypeInstantiator` (four checker reads, one table write),
`NameResolver` (2,284 lines, 26 reads, no writes — the name-resolution seam COMPLETE over three
steps) and **`Relater`, 1,446 lines, the RELATION seam in one commit: 45 reads and 5 writes, the
largest ambient row of the arc and the design's own prediction, since § 6 puts
`getTypeOfSymbol`/`getTypeOfExpression` in Stage 3 for exactly that reason**, all stated in the
ledger. Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.56) — (INV.0) STEP 5: THE RELATER IS `Relater.kt`, AND THE 6,390-LINE REGION IS ONLY 1,256 LINES OF ALGORITHM, 18,489 / 0 / 3 (2026-09-09).**
`Checker.kt` **198,022 → 196,797**; `Relater.kt` 1,446; ledger row 7. **The census the item asked
for changed the shape of the work**: the seven named entry points span a ~6,390-line region and the
relater is **1,256 lines in five contiguous spans** — the rest is ELABORATION
(`getPropertyElaborationChain` 546, `getFunctionMismatchElaborationWorker` 407,
`checkExcessProperties` 231), which answers *what do we SAY about the failure*, a different seam.
**Deliberately NOT split** where 4b was: one mutually-recursive algorithm, so a mid-recursion cut
puts a `Checker` hop inside the hottest recursion for no verification benefit. **Delegation surface
SIX, not 363** — `checkTypeRelatedTo`'s 329 call sites are byte-unchanged behind a one-line hop, and
eight moved functions have no caller left. **THE MOST REUSABLE FINDING IS A RECEIPT FIX: the
`--passTiming` pass table is printed in DESCENDING WALL-TIME order**, so its row ORDER is a timing
artefact and the first comparison read **804 diff lines between two identical binaries**; sorted, and
with every ms-bearing or time-BUCKETED line dropped, **488 deterministic lines are byte-identical
against a REBUILT pristine HEAD** (all 420 per-pass rows, the 46 diagnostics, the emissions census,
the counters, globals lookups) with a same-binary control. **A second: `cost_gate.py`'s ±2% column is
not a statement about the change** — six counters read non-zero and pristine HEAD reads exactly the
same deltas, i.e. the recorded baseline is stale; grade a split against a rebuilt pristine and treat
the gate as the control. **A third: `isTypeAssignableTo` joins `getTypeOfExpression` as a
`PrintInlining` site that is NOT stable across processes** (proved by running arm A twice), leaving
`checkArgumentsAgainstSignature` the only one of § 10's three that can separate arms. The split
IMPROVED inlining for the third row running (`checkTypeRelatedTo`: 344 `too large` refusals → a hop
with ZERO). ab-interleaved **−11 ms (−0.04%) B-wins-3/6 NOISE-DOMINATED**; `new Relater` appears at
exactly ONE bytecode in the module. Verbatim proved twice by two methods for the fourth round
running. **5 pins, 5 arms, and TWO PINS MEASURED UNDISCRIMINATED AND RENAMED rather than claimed**: a
leak detector cannot work, because the `Relation` cache is probed ABOVE the comparison stack and
answers an identical pair before a stale key is consulted; and the `isDeeplyNested` bail is not the
only bound — disabling it entirely still terminates, since `maxRelationDepth` is a second ceiling.
cost_gate exit 0, huge_methods exit 0 (836 classes), warning-clean.

**(P18.55) — (INV.0) STEP 4 IS COMPLETE: `NameResolver.kt` IS 2,284 LINES AND `Checker.kt` LOST 1,941, 18,484 / 0 / 3 (2026-09-09).**
4b-ii moved the namespace / heritage / type-name group (19 functions, 2 fields) VERBATIM, closing
the seam; ledger row 6. `Checker.kt` 198,781 → **198,022**; `Checker.<init>` 5,656 → 5,634.
**THE AMBIENT ROW GETS BETTER AS A FAMILY COMPLETES, AND THIS ROUND MEASURED IT**: 4b-ii ABSORBS
four of the reads rows 4 and 5 recorded, because those functions now live inside the collaborator
— net **26 checker reads, NO writes, for 2,284 lines**. So an intermediate row's ambient count is
the WORST that family will look, and the ledger should be read by FAMILY, not by row.
**The constructor-input trap bit a third time and cost nothing**, because (P18.54) put the rule in
the queue item: six more fields turned out to be declared below the construction site, where an
input captures null, so all became ambient reads. **A third JVM-name-mangling mechanism turned up**
— `SymbolFlags` is a VALUE class, so `lookupInEnclosingNamespaces` compiles as
`lookupInEnclosingNamespaces-bd7vo6s`; like `internal`'s `$<module>` suffix it makes a receipt grep
read zero rows and look like "never compiled". **RECEIPTS COVER THE WHOLE OF STEP 4**: all 420
per-pass `--passTiming` rows and the 46 diagnostics byte-identical between PRE-4a pristine and the
finished seam — one receipt for all 1,941 moved lines; PrintInlining ZERO refusals on every hop with
both STABLE standing hot sites identical to pristine; ab-interleaved +0.15% B-wins-4/6
NOISE-DOMINATED; cost_gate and huge_methods exit 0; warning-clean. Verbatim proved twice by two
methods for the third round running. Next per the design's Stage-0 order: the RELATER out of
`checkTypeRelatedTo` into the `TypeRelationCache.kt` seam row 2 already named.

**(P18.54) — (INV.0) STEP 4b-i: THE PER-FILE LOOKUP CORE JOINS `NameResolver.kt`, THE ITEM'S OWN HOIST IS UNSAFE, AND THE FILE THE ARC GROWS INTO WAS UNREVIEWABLE BY DIFF, 18,484 / 0 / 3 (2026-09-09).**
`Checker.kt` **199,405 → 198,781**; `NameResolver.kt` 669 → 1,418; ledger row 5. 20 functions and
11 fields moved VERBATIM — the per-file scope tables and their build passes, the INV.3(b)(ii)
visibility sets and their deferral, the probe funnel, the four consults, the (CHK.49) lib-value
recovery, the (BIND.1) owning-file probes and the four first-hit program scans. Ambient row:
**seven reads, no writes**, two of them intended BIDIRECTIONAL pairs. **The full 4b censused at
1,363 lines so it was SPLIT**; 4b-ii (namespace / qualified-name / heritage, ~780 lines) is what
remains of step 4. **THE ITEM'S OWN INSTRUCTION IS UNSAFE**: hoisting `libGlobals`'s declaration
above the construction site — which it asks for — would reorder `parseBuiltinLib()`, whose side
effect fills a field deliberately declared before it for the Kotlin init-order gotcha; that field
and one other became ambient reads instead. **THE SPLIT IMPROVED THE COMPILER'S HOTTEST LOOKUP**:
`lookupPerFileForNode` (~2M calls/self-compile) was `4 inline (hot) + 57 too large` as a monolith
and its 9-byte hop is now `57 inline + 41 inline (hot)` with ZERO refusals — row 1's finding on a
far bigger population — with a receipt trap attached, since Kotlin mangles an `internal` member's
JVM name and a grep for the source name reads zero rows. **AND THE FILE THIS ARC GROWS INTO WAS
RENDERING AS A BINARY BLOB**: `UNRESOLVED_MODULE_SPEC`'s literal NUL sat at byte 7,910, inside
git's 8,000-byte detection window, so 4a and 4b-i have NO line diff for it; fixed in `2db1c14ca`
with the compiled class BYTE-IDENTICAL as the receipt. Receipts: **all 420 per-pass `--passTiming`
rows and the 46 diagnostics byte-identical against PRE-4a pristine** (one receipt covering both
rows), all 17 named gate classes green, cost_gate exit 0, huge_methods exit 0 with
`Checker.<init>` 5,701 → 5,656, ab-interleaved −0.45% B-wins-3/6 NOISE-DOMINATED, warning-clean.

**(P18.53) — (INV.0) STEP 4a: THE NAME/MODULE-RESOLUTION LEAF BECOMES `NameResolver.kt`, AND TWO OF THE FOUR § 10 INSTRUMENTS NEED A SAME-BINARY CONTROL, 18,477 → 18,484 / 0 / 3 (2026-09-09).**
The owner chose **(INV.0)** — the WORK ORDER's tail — so the (CHK.\*) lane is parked and the
shrinkage metric moves the right way for the first time in ~26 rounds: `Checker.kt`
**199,963 → 199,405**, `NameResolver.kt` 669, ledger row 4. Fifteen functions and three fields
moved VERBATIM — the alias ladder, the specifier ladder, two scope probes and the checker-local
symbol-target link store — as a final class built once per `Checker`, every surviving call site a
one-line delegation. **The verbatim claim is proved twice by two methods** (a reverse-transform
`diff` and an independent multiset check) rather than asserted. Ambient row: **fourteen checker
reads, no writes**; the four functions whose only readers moved with them got no hop and were made
`private`, so the collaborator's public surface (11) equals the delegation count by construction.
**THE REUSABLE FINDING IS METHODOLOGICAL.** The counter receipt should be the standard for a split
and is stronger than the gate: **all 420 per-pass `--passTiming` rows and the 46 diagnostics are
byte-identical against a rebuilt pristine HEAD**. The only section that moves is the **node-kind
histogram**, and the SAME BINARY run twice moves it MORE (70 differing lines A-vs-A against 64
A-vs-B) — the documented crawl-worker race, arriving in a channel nobody had diffed. And
**`getTypeOfExpression`'s PrintInlining row is NOT stable across processes**: arm A read
`1 inline (hot) + 372 too large`, arm B `382 too large`, and arm A's SECOND run reproduced arm B
exactly — so ledger rows 1 and 3's "row-for-row identical across arms" was recorded without this
control. Every delegation hop reads `inline`/`inline (hot)` with ZERO refusals; ab-interleaved
−0.57% B-wins-2/6 NOISE-DOMINATED; `NameResolver` is never an allocated type. Three deviations
from the item, each backed by a grep, including one FORCED by the warning-clean rule. 7 pins;
3 arms, all discriminating uniquely, with the remaining four pins recorded as positive controls
rather than claimed as coverage. Next: **step 4b**, the scope side — censused this session at
~1,270 lines, and several of 4a's ambient reads disappear once it lands.

**(P18.52) — STATIC BLOCKS ESCAPE, PARAMETER DEFAULTS AND DECORATORS ARE REACHED ((CHK.115)), AND THE DECORATOR FAMILY IS *TWO OPPOSITE MECHANISMS*, 18,437 → 18,477 / 0 / 3 (2026-09-08).**
43 fixtures, and **pristine 6.0.3 and tsgo 7.0.2 agreed on every one**. **(a) removes an ours-only
FALSE POSITIVE**: a static block's assignments now escape into the enclosing flow — tsc's binder says
so literally (`isImmediatelyInvoked = <IIFE> || node.kind === ClassStaticBlockDeclaration`) — **but a
static PROPERTY INITIALIZER, which also runs at class-evaluation time, does NOT escape**, because tsc
gives an initialized `PropertyDeclaration` its own control-flow container. The item did not state that
boundary. The escape had to be the full `markAssignments` LATTICE, not a scan: a conditional and a
`try` must not escape while a `while (true) { … break }` must. **(b) is NINE rows, not one** — all five
parameter-default spellings plus an object-literal method, a binding-pattern element default, an arrow
nested in a default and a class-expression static block in a default. **(c) is two opposite mechanisms
wearing one syntax**: a MEMBER decorator answers to the LEAK, a CLASS decorator to the LIVE set,
because a `ClassDeclaration` is not a control-flow container in tsc — one rule is wrong for one of
them, and both directions are pinned. **A new ours-only row was manufactured and caught only by the
final full reference sweep**: under STANDARD decorators a parameter decorator is TS1206 and both
references stop there, so an ungated walk added a TS2454 beside it — no profile carries the shape, so
neither the grid nor the corpus could see it. 40 pins; 13 arms — **a6 is the mask/closure pair**
(edits `SpineDispatch.kt` only, so `Checker.class` is unchanged and `spine_closure_audit.py` FAILS
under it), **a10 and a14 were each DEAD ALONE** behind an early return and an `any` gate, so a10b and
the a11+a14 pair are what discriminate, and **11 pins are never RED by construction** — they are
positive controls asserting today's conservatism, reddenable only by an arm that makes the change more
aggressive, which a3/a12/a13 are. Three residues queued as (CHK.116), including that static blocks are
flow-ORDERED and one leak set per class cannot express it. Grid **8 × added=0 removed=0** re-run
independently; corpus 10,344/0, `spine_closure_audit.py` exit 0, `cost_gate.py` exit 0,
`huge_methods.py` exit 0, build warning-clean.
