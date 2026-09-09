# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **196,172** lines (**−3,791 across (P18.53)-(P18.57)**, the first
sustained movement in the extraction direction since the metric was created; 191,070 when it was
created, and the (P18.9)-(P18.37) checker-parity arc ADDED ~5,200, which are fixes and pins, not
extractions). SIX collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient
surface none for both — `TypeInstantiator` (four checker reads, one table write),
`NameResolver` (2,284 lines, 26 reads, no writes — the name-resolution seam COMPLETE over three
steps), `Relater` (1,446 lines, the RELATION seam in one commit, 45 reads / 5 writes — the
largest ambient row of the arc and § 6's own prediction) and **`MemberResolver` (814 lines,
21 reads / 1 write with the columns DISJOINT), which makes the relater the arc's OUTLIER
rather than its trend**, all stated in the ledger. Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.57) — (INV.0) STEP 6a: MEMBER RESOLUTION IS `MemberResolver.kt`, AND THE ITEM'S OPEN QUESTION IS ANSWERED, 18,493 / 0 / 3 (2026-09-09).**
`Checker.kt` **196,797 → 196,172**; `MemberResolver.kt` 814; ledger row 8. **Second extraction of
the session.** The queue item asked whether the seam is the table builders *without*
`getTypeOfSymbol` — **it is**, and the census said so before any code moved: this family reads it
at ONE site, so a seam that took it would have had to take the whole checker, which is exactly why
§ 6 puts it in Stage 3. **A much better-shaped seam than the relater, which makes row 7 the arc's
OUTLIER rather than its trend**: one contiguous span against five, 657 lines against 1,256, **21
ambient reads against 45**, **1 write against 5** — and the columns are DISJOINT here, the single
write being (INC.23)'s truncation flag, a pure OUT channel. **There is NO cheap absorption, the
opposite of row 7 — ZERO of 22 members lack another caller in `Checker.kt`** (row 7 had seven), so
this row shrinks only by extracting its NEIGHBOURS. **THE RECEIPT IS NOW TRANSITIVE ACROSS THREE
BINARIES**: the same 488 deterministic `--passTiming` lines are byte-identical for pre-step-5
pristine, step 5 and step 6a — one receipt over **1,882 moved lines**, at no extra build, because
the step-5 capture taken earlier in the session IS this round's pristine arm. **A sharper form of
the JVM-mangling trap, hit twice in one run: widening a member to `internal` AS PART OF THE SPLIT
mangles a site a PREVIOUS round's receipt was reading** — `getTypeOfExpression` went private →
internal here and the unmangled grep reads 574 rows before and **0** after, which looks exactly
like a site that stopped being compiled. The split improved inlining for the fourth row running
(`resolveStructuredTypeMembers`, 244 call sites: **189 `too large` refusals → ZERO** at the hop);
ab-interleaved +38 ms (+0.15%) B-wins-3/6 NOISE-DOMINATED; `new MemberResolver` at exactly one
bytecode. **4 pins, 3 arms, one DEAD BY CONSTRUCTION and recorded as such** — deleting the
`mrProbeDepth--` changes nothing because that counter only moves under `--passTiming`, which no
test enables; the arm that forces the B202.1 cycle break never to refuse reddens its pin with the
mechanism verbatim in the message (`TS2589 … at (0,0)`, and the real circular-base row gone).
cost_gate exit 0, huge_methods exit 0 (837 classes), warning-clean.

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
