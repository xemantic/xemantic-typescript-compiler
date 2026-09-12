# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **192,433** lines (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
the metric was created, and the (P18.9)-(P18.37) checker-parity arc ADDED ~5,200 in between, which
are fixes and pins rather than extractions — so the file is now BELOW where the metric started
WITH that work still in it). TEN collaborators extracted: `TypeInterner`, `Relation`+`Ternary`
and **`LexicalScopeResolver`** — ambient surface NONE for all three — `TypeInstantiator`
(4 reads, 1 write), `NameResolver` (2,284 lines, 26 reads), `Relater` (1,446 lines),
`MemberResolver` (834 lines, 22 reads), `MemberNames` (771 lines, 4 reads / ZERO writes),
`EnumSemantics` (1,138 lines, 13 reads / ZERO writes) and `CaptureRecorder` (3,214 lines,
61 reads / 9 writes — the arc's largest MOVE and its largest ambient ROW, which is
`docs/INVERSION-DESIGN.md` § 2's "the answers are functions of walk-scoped state" as a number
rather than an argument), all stated in the ledger. The ambient TOTAL first fell at (P18.59),
which wired `Relater` and `MemberNames` to `EnumSemantics` DIRECTLY. **STAGE 0 IS NOW
DECIDED-DOWN rather than exhausted**: (P18.61) sized what is left — 50.6% of the file is check
passes, whose candidate collaborators census at 59-97 ambient reads (`cae*`: 97 reads for EIGHT
declarations) — and turned the arc toward Stage 3. Reference points: tsc ≈ 50k lines (one file),
tsgo 60,479 across 25 files. Contract: `docs/INVERSION-DESIGN.md` § 10; ledger:
`docs/inversion-ambient-ledger.md`.

**(P18.78) — (CHK.133)(a)+(c): `Signature.thisType` AND THE CALL-SITE TS2684 — "A PURE MODEL CHANGE" MOVED FIVE ROWS BEFORE ANY CONSUMER EXISTED, AND (CHK.97) CLOSES, 18,781 / 0 / 3 (2026-09-12).**
`Signature` now carries its `this:` pseudo-parameter (`thisType`), threaded through all 28
`Signature(` constructions, all four instantiators and `MemberResolver`; the model ALONE
closed a false TS2345 on every call of an interface/class method declared with `this` (the
parameter zip) and made `unionTypeCallSignatures5/6` byte-identical to pristine (the union
arm INTERSECTS members' `this` types, deduped by identity because `getIntersectionType`'s
anonymous-object exemption printed `B & B`). The call-site consumer (tsc's
`getSignatureApplicabilityError` `this` leg → TS2684 with a one-level chain) landed in the
same commit: **387-533 declared `this` parameters per profile and ZERO call sites reaching
the check** on every profile and library, so the grid is a GATE for the model and a CONTROL
for the emission. A display defect the sizing missed (`ZzzBox<T>` for `ZzzBox<number>`) is
fixed and gated by the 18 active `this:` baselines. Ablation over 29 pins: 19/1/1/2/15/8/1/2/
1/1 RED across nine arms. cost_gate exit 0 with `typeNode.bypassed` +0.49% (the declared
`this` resolutions, bounded by the census; not rebaselined), huge_methods exit 0 (845),
warning-clean. **(CHK.97) is CHECKED OFF** — every deliverable closed or rejected on a
measurement across (P18.71)-(P18.78). (CHK.133) stays open on (b) the relation's `this`
leg and the `.call/.apply/.bind` consumer.

**(P18.77) — (CHK.97) D5: INFERENCE THROUGH A UNION-COMBINED SIGNATURE WAS BAILING ON AN INTERSECTION IT COULD NOT SEE, AND THE "ONE ROW" WAS SEVEN FAMILIES, 18,752 / 0 / 3 (2026-09-12).**
PASS 2 combines `(number[] | string[]).map` into `<U>(cb: ((v: number…) => U) & ((v: string…)
=> U)): U[]`, and every callback arm of the single-type-parameter inference demanded an
anonymous `Type.Object` — so the inference bailed whole and the call answered a raw `U[]`
that every reader silently refused as a foreign type parameter. **The first reading of the
mechanism was wrong and a stderr line settled it** (the gate PASSES, because a function
object's signatures are invisible to `typeMentionsTypeParam`; the candidate gatherer is what
finds nothing). Fix is a VIEW, not machinery: `inferenceParamType` presents such an
intersection as one anonymous function type carrying the existing `getIntersectedSignatures`
fold, memoized per intersection id. Four fixture families `missing → agree`, the PASS-2
pair fires (with a (CHK.132) sub-line), zero new ours-only rows. Ablation 12/4/1/0 RED over
67 pins — a4 a recorded redundant guard. **Two `TupleArrayMembersTest` pins were countdowns
on exactly this silence** and a `reduce` control was written wrong; all three found by the
68-class neighbour sweep, none by the fixtures. Grid 8×0/0 and both library arms are
measured CONTROLS (census `bound=0` everywhere real). cost_gate exit 0 (digit-identical to
pristine), huge_methods exit 0, warning-clean. (CHK.97) is open on D6 ALONE; its unblocker
`Signature.thisParameter` is now **(CHK.133)** at the top of the queue.

**(P18.76) — (CHK.97) D3, THE DIFFERING HALF: TS7006 THROUGH A UNION CONTEXTUAL TYPE, AND THE ARITY FILTER THE IDENTICAL HALF HAD LEFT OUT, 18,738 / 0 / 3 (2026-09-12).**
A union contextual type whose member signatures are NOT identical now leaves the arrow's
parameter implicitly `any` and reports TS7006/TS7031 through the EXISTING owner
(`checkParamsForImplicitAny`), as both references do. **The reach census ran BEFORE any
emission and read ZERO on all eight profiles, marked, cronstrue and the 2,400-file project**,
so the grid and library arms are measured CONTROLS — and it also showed (P18.75)'s
overloaded-member refusal firing 6× on one fixture, i.e. the emission was UNSOUND without
tsc's `getContextualCallSignature` arity filter, which was closed in the same sub-step
(`callableSignaturesForCtx(requiredParamCount)`, `signatureArityBelow`, several applicable
overloads folded through `getIntersectedSignatures`). Before → after: differing types/arity/
optionality `missing → agree`, the overloaded family 0/0/3 → 3/0/0, a new 16-file family
2/0/18 → 17/0/3, zero NEW ours-only rows. Five of (P18.75)'s own negative controls were
COUNTDOWNS and now assert the row. Ablation 16/3/15 RED over 39 pins; a `sig!!` on an
unresolvable callee passed every D3 fixture and was caught only by the 63-class neighbour
sweep (24 RED). cost_gate exit 0 (counters digit-identical to the pristine binary),
huge_methods exit 0, warning-clean. (CHK.97) stays open: D5, D6.

**(P18.75) — (CHK.97) D3, THE IDENTICAL HALF: A UNION CONTEXTUAL SIGNATURE IS ANSWERED, AND THE BRIEF'S OWN FIXTURE NEVER REACHED THE ARM, 18,718 / 0 / 3 (2026-09-12).**
`callableSignaturesForCtx` refused a union contextual type at its SECOND callable member;
it now runs tsc's union arm of `getContextualSignature` — every member's one signature must
be `compareSignaturesIdentical` to the first with returns ignored, and the answer is the
first's parameters with the RETURNS unioned (`unionContextualSignature`, ~50 lines). Six
fixture families go `missing → agree` and the TWO ours-only TS7006 rows the refusal
manufactured are gone; the DIFFERING half (tsc's TS7006) stays silent and is recorded, as
are an OVERLOADED member (the helper has no arrow, so it cannot arity-filter as tsc does)
and a `Type.Reference` with lazy own signatures (the target fallback would read
`Cb<string> | Cb<number>` as identical). **The obvious fixture, `declare const zf: A | B;
zf((p) => …)`, never reaches the arm** — the argument gets the COMBINED callee signature's
parameter — so the whole matrix was re-cut through `take(cb: A | B)` and annotations. The
arm answers ZERO times on all eight profiles, `marked` and the 2,400-file project (14 on the
fixture), so the 8×`added=0 removed=0` grid is a measured CONTROL. Ablation 12/3/1 RED over 19
pins, a3 discriminated only by a NESTED differing pair. cost_gate exit 0 (all 20 counters
digit-identical to the pristine binary — the +1.18% is baseline staleness), huge_methods exit
0 (844), warning-clean. (CHK.97) stays open: D3-differing, D5, D6.

**(P18.74) — (CHK.130): THE PARENTHESES WERE ASKING ABOUT THE *SHAPE*, NOT ABOUT WHAT IS PRINTED — AND THE INSTRUMENT'S FOURTH BLINDNESS, 18,699 / 0 / 3 (2026-09-11).**
`typeToString` parenthesized a union member whose RESOLVED SHAPE is "one call-or-construct
signature and nothing else" — but `Type.Interface` and `Type.Reference` both EXTEND
`Type.Object`, so an interface with a call signature, an alias to a function type and a
generic instantiation all matched while PRINTING AS THEIR NAME. Parentheses exist so a
rendering can be reparsed inside a `|`; a name never needs them. The predicate now mirrors
`typeToString`'s own dispatch arm by arm and asks *is what we are about to print a bare arrow
form*. **A SECOND HALF WAS NOT IN THE ITEM: the interface case is ORDER-DEPENDENT**, because
a `Type.Interface`'s member tables are LAZY (round 833) — the same interface renders bare in
a plain TS2322 and parenthesized in a union-callee TS2349 whose own resolution has just
filled `callSignatures` in, which is why all six failing rows were TS2349 and why a TS2322
fixture showed the case as already correct. **THE AT-RISK ENUMERATION DECIDED THE ITEM AND
WAS DONE BEFORE ANY CODE**: over all 2,910 ACTIVE `.errors.txt` baselines, **7 carry a
parenthesised group next to a `|` and ZERO of those is a bare NAME** — so the remove direction
cannot move a baseline, and **no `LogicalParityDivergence` was needed or used.** **THE
ABLATION FOUND THE ASYMMETRY THAT MAKES IT SAFE**: parenthesize-nothing is 16 RED including
**4 corpus baselines**, so the corpus gates over-REMOVAL and not over-ADDITION — exactly what
the enumeration predicted from the other side; the two discriminating arms partition perfectly.
**TWO MORE COUNTDOWN PINS, THE EIGHTH AND NINTH IN NINE ROUNDS**, both our own defect
transcribed into an expectation, both now byte-identical to both references — per (CHK.114)
only the expectations changed, never the names. **AND THE INSTRUMENT WAS WRONG AGAIN, A
FOURTH TIME, IN THE WAY THAT MATTERS MOST**: `ref_matrix.py` folded "the references report
the same ROW and disagree about its MESSAGE" into AGREE — not adjudicable is the right
treatment and the wrong LABEL, since it inflates the prize and hides a divergence family. It
made a subagent report CLAUDE.md's (CHK.83) as CONTRADICTED when that law is exactly
reproducible (verified from raw bytes). New REF-SPLIT-MSG verdict; re-deriving this round's
own receipt with it moves `agree 21 → 19`. **Third round running in which re-taking a receipt
moved a number already written down.** (CHK.83) is not contradicted but POPULATION-SPECIFIC —
where the source is not generalized, both references name the FIRST constituent and we alone
name the last, 15 of 15 rows with the outer line byte-identical: now (CHK.132), and the
CLAUDE.md entry gained the clause that stops the next agent repeating the report. **The
(CHK.97) union-callee family is now byte-identical to both references across all 11 fixtures.**
Three outer-line residues remain, each measured and refused with a reason. Grid 8×`added=0
removed=0` and measured to be a control (416 rows, not one names a union). cost_gate exit 0,
huge_methods exit 0 (844), warning-clean.
