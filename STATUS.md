# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **195,606** lines (**−4,357 across (P18.53)-(P18.58)**, the first
sustained movement in the extraction direction since the metric was created; 191,070 when it was
created, and the (P18.9)-(P18.37) checker-parity arc ADDED ~5,200, which are fixes and pins, not
extractions). SEVEN collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient
surface none for both — `TypeInstantiator` (4 reads, 1 write), `NameResolver` (2,284 lines,
26 reads, no writes — the seam COMPLETE over three steps), `Relater` (1,446 lines, 45 reads /
5 writes — the RELATION algorithm, the arc's largest row and § 6's own prediction),
`MemberResolver` (814 lines, 21 reads / 1 write, columns disjoint) and **`MemberNames`
(765 lines, **5 reads / ZERO writes** — the arc's CLEANEST row, because that family owns no
state and answers a SYNTACTIC question)**, all stated in the ledger. Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.58) — (INV.0) STEP 6b: THE MEMBER-NAME / LATE-BINDING FAMILY IS `MemberNames.kt`, THE ARC'S CLEANEST SEAM, 18,498 / 0 / 3 (2026-09-09).**
`Checker.kt` **196,176 → 195,606**; `MemberNames.kt` 765; ledger row 9. **Third extraction of the
session.** **FIVE ambient reads, ZERO writes** — against the relater's 45/5 and member
resolution's 21/1 — **and the reason generalises: this family owns NO STATE and answers a
SYNTACTIC question.** Four of the five reads belong to seams of their own (three enum-value
helpers, two AST helpers); rows 7 and 8 read the type system because they ARE the type system.
`fileResults` is a CONSTRUCTOR INPUT rather than a read, which is what takes the row from 6 to 5 —
rows 5/6's above-line-666 rule paying off. **`MemberResolver` was deliberately NOT rewired**: it
keeps calling `checker.getMemberName` / `declaredMemberName`, which the delegations serve anyway,
so the two collaborators carry no construction-ORDER dependency. **The split removed 61 `too
large` inlining refusals and added none** — the fifth row running (`getMemberName` `16 inline +
16 too large` → a hop reading `7 inline`, zero refusals; `computedLiteralKey` `20+20` → `6`).
**THE RECEIPT NOW SPANS FOUR BINARIES** — the same 488 deterministic `--passTiming` lines are
byte-identical for pre-step-5 pristine, step 5, step 6a and this: **one receipt over 2,509 moved
lines**, at one extra build for the whole session, because each round's capture is the next
round's pristine arm. **THE PINS ARE *AGREEMENT* PINS**, which is what this family needs: a
member's name is asked at REGISTRATION and again at RESOLUTION, and both known failures (round
935, (CHK.40)(c)) emit a CORRECT diagnostic beside a false one, so a pin asserting "it compiles"
passes on a broken binary — each pin instead reads the member back through a wrong target type and
asserts the TS2322 that names the resolved type AND the absence of TS2339 beside it. **Every one
of the 5 pins discriminates, the session's first round where that is true**; the hop-limit PAIR
brackets `LATE_BIND_ALIAS_HOPS` (a 2-hop alias chain late-binds, an 11-hop one does not) and
neither pin alone is evidence. **A naming trap worth carrying: `Checker.kt` already declares
EIGHT locals named `memberNames`**, so the collaborator field is `memberNamer` — a field of the
shadowed name compiles and broke the round's own structural check. ab-interleaved −182 ms
(−0.70%) B-wins-3/6 NOISE-DOMINATED; cost_gate exit 0, huge_methods exit 0 (838 classes),
warning-clean.

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
