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

**(P18.73) — (CHK.97) D2b: THE SILENCE THAT HID A TRUE POSITIVE, AND A DESIGN DECIDED BY *BUILDING* THE ALTERNATIVE, 18,688 / 0 / 3 (2026-09-11).**
**THREE LINES OF CODE**: the `>= 2` emit and the separate `>= 1` silence collapse into one
branch, because tsc's PASS 2 refuses for TWO reasons and only one of them is "more than one
overload set" — it also refuses on GENERIC INCOMPATIBILITY, where both references print the
identical TS2349 + chain and we were silent. **THE DESIGN QUESTION WAS SETTLED BY BUILDING
THE REJECTED ALTERNATIVE.** An instrumented binary that actually threads the refusal reason
out of `computeCombinedUnionSignatures` agrees with the recomputed `count` on **21 of 21**
reachable refusals, and structurally must; the thread is recorded as a refusal WITH ITS
NUMBER rather than as a preference. That census build was behaviour-neutral (18,679/0/3,
exactly the baseline), which is what makes its counts trustworthy. **TWO OF THE ITEM'S
CLAIMS WERE WRONG, BOTH TOWARD THE CHANGE LOOKING RISKIER THAN IT IS**: the surviving
silence's stated justification (`unionOfArraysFilterCall`) is FALSE — that shape never
reaches the branch at all, stage 2's array fallback answers it first — and
`overloadedMembers == 1` is reached **ZERO times** by the whole suite, all eight profiles,
cronstrue AND marked, so the widening cannot move a baseline and the pins are its only gate.
**RECEIPT: missing 8 → 0, ours-only 0, agree 12 → 15 — AND `text-diff` MOVES 1 → 6, WHICH
THE ROUND FLAGS RATHER THAN BURIES.** Five of the eight recovered rows land at the right
file, line, COLUMN and code with the wrong display, every one of them the same pre-existing
(CHK.130) defect (`ZzzA | (ZzzG)` for `ZzzA | ZzzG`); a fixture whose four rows differ only
in whether the member carries a property proves it is not a D2b defect, since the three that
do are byte-identical AGREE. So eight SILENT rows become three exact and five differing only
in parentheses — a meaning gain with a form residue, six instances louder because a
diagnostic that never fired could not display anything wrong. **THE GRID IS A CONTROL AND
THE CENSUS SAYS SO IN THE STRONGEST FORM YET**: zero union-callee combination refusals OF
ANY KIND on all eight profiles, cronstrue and marked. **ABLATION: 5 arms, EACH against the
FULL SUITE** (P18.72's own lesson), both controls; b5 proves last round's `>= 2` threshold
is load-bearing. **b4 is the interesting arm and is a refusal on SCOPE, not evidence**:
collapsing the whole `differ` tail is 0 RED on the full suite — evidence FOR it — and it was
still refused as (CHK.94) territory, with the number written into the branch comment so the
next round starts from a measurement. Grid 8×`added=0 removed=0`, cost_gate exit 0,
huge_methods exit 0 (844), warning-clean.

**(P18.72) — (CHK.97) D2: A BOTH-OVERLOADED UNION CALLEE REPORTS, AND THE SUPPRESSION STILL HIDING A SECOND ROW, 18,679 / 0 / 3 (2026-09-11).**
**THE FIX IS A SPLIT BY *REASON*, NOT A RETIREMENT.** One `if` was answering two different
facts: TWO OR MORE overloaded constituents is exactly where tsc SKIPS pass 2, so
`getUnionSignatures` answers the EMPTY list and TS2349 reports with the "Each member … has
signatures" chain — a DIAGNOSTIC; exactly ONE is the `unionOfArraysFilterCall` shape, where
tsc RUNS pass 2 — SILENT. **The chain sentence now has ONE home** shared with the generic
refusal, because a chain is the whole observable here and (PARITY.1) says the grid is blind
to a display divergence, so two copies would drift with nothing to notice. Receipt
**agree 3 → 11, missing 8 → 0, ours-only 0** — **and that number was CORRECTED within the
round by the sub-step below**: it was taken with a chain-blind instrument, and one of its
eleven AGREE rows is really a TEXT-DIFF, so re-taken chain-aware the seven fixtures read
**agree 10, text-diff 1, missing 0**. The verdict does not move; the receipt does.
**A COUNTDOWN PIN FIRED — THE SEVENTH IN EIGHT ROUNDS — AND ONLY THE *FULL* SUITE SAW IT**:
a pin asserting the silence this round removes, with its own KDoc saying "SILENT where tsc
reports TS2349". It surfaced from an ablation arm run against the full suite; the four
corpus guard letters the round was gating on miss it. **Corollary: a guard-letter subset is
not a substitute for the suite when an arm WIDENS an emission — a widening's victims are
pins, not baselines.** **THE GRID IS A CONTROL AND THE ROUND PROVES IT WITH A COUNT**: a
counting arm reads 0 hits on all eight profiles against 3 on the round's own fixture, so
`added=0` is inertness, not coverage. **THE INSTRUMENT IS BLIND TO WHAT THIS ROUND CHANGES**:
`scripts/ref_matrix.py` matched a diagnostic's FIRST LINE only, so a chain-only divergence
scored AGREE — the third distinct blindness found in that script in two rounds, and it had
passed a real one (`typeToString` parenthesizes a union member with exactly one call
signature, where both references print it bare; pre-existing and unowned, now (CHK.130)).
Closed as its own sub-step, verified in BOTH directions — a fixture on which all three arms
agree on the chain still reads AGREE, so the arms' differing print formats do not
false-positive — and it immediately re-graded one of this round's own fixtures. **THE ROUND'S LOAD-BEARING CLAIM IS TRUE OF THE PROFILES AND FALSE OF THE
LANGUAGE**: the ONE-overloaded suppression D2 KEPT is reachable and hides a true positive,
because PASS 2 also refuses on GENERIC INCOMPATIBILITY. The implementer STOPPED at the scope
line rather than pushing through — now **(D2b)**, measured and ready (0 RED on 1,425
baselines, reddening exactly the countdown pin already inverted) and **deliberately not
taken: an ablation arm is not an implemented fix with pins.** Ablation 5 arms with both
controls; a3 recorded as a REDUNDANT GUARD and structurally so; a2 recorded as a DEAD ARM
for the round's own pin set. Grid 8×`added=0 removed=0`, cost_gate exit 0, huge_methods exit
0 (844), warning-clean.

**(P18.71) — (CHK.97) STAGE 3 D4: THE NULLISH-UNION CALLEE'S ARGUMENT CHECK, AND A SUPPRESSION WHOSE GATE WAS TWO MECHANISMS, 18,669 / 0 / 3 (2026-09-11).**
One of (CHK.97)'s six stage-3 deliverables closed — **and all six were measured against
tsgo 7.0.2 and pristine 6.0.3 BEFORE one was picked**, with zero REF-SPLIT rows anywhere.
That ranking is most of the round's value: D4 had **11 MISSING rows and a real gate**,
D2/D3 three each with only a control, D5 one, **D1 exactly ZERO** — and D6 is BLOCKED on a
model change (`Signature.thisParameter`), named rather than attempted. **D1 IS REJECTED ON
A MEASUREMENT, NOT DEFERRED**: its only visible readers are `ReturnType<U>`/`Parameters<U>`,
and what the references print there is tsc's conditional-type DISTRIBUTION, not
`getUnionSignatures` — combining would be wrong in a NEW way across 35 readers. **THE ITEM'S
AXIS WAS WRONG AND A FIXTURE BUILT ON IT MEASURES NOTHING**: it says "`f?.(1)`'s argument
check", but `?.` is innocent — `g?.(1)` on a plain `Fn` already reported, while the
`?.`-free `if (zu) { zu(1) }` was silent. The population is a callee **TYPE**. **THE
REUSABLE DEFECT IS ONE NEITHER THE ITEM NOR THE BRIEF NAMED: a suppression's gate was two
mechanisms wearing one `if`** — the round-408 pre-pass conjoined "is this callee narrowable"
with "strip nullish for an optional call", and the second is a property of the CALL, not the
callee expression; for every other callee kind the nullish member survived into the
not-callable verdict as an **OURS-ONLY TS2349 on legal code, four of them, one per callee
kind**. Its sibling: **a `Boolean`-returning pre-pass can only spend a suppression by
CONSUMING the call**, so the narrowed value it had just computed had nowhere to go —
`ccetUnionCalleeChecks` now answers `Type?` (null = consumed), the argument-side mirror of
the RESULT-side strip stage 2 put in `getReturnTypeOfCallExpression`. **RECEIPT: agree
8 → 20, ours-only 4 → 0, missing 19 → 7**, both directions. **THE GRID IS A REAL GATE HERE
AND IT IS GREEN, WHICH IS THE INTERESTING PART** — unlike last round's two items, a
positive control counts **49-101 hand-offs per profile**, so hundreds of arguments never
before checked on tsc's own sources were checked and all are correct; (CHK.50)'s law did not
fire. **A LATENT PATH WAS PROBED RATHER THAN ARGUED**: `allCallable` answers true for
`any`/`errorType`, so an unresolved union member now hands its union back — measured, zero
ours-only rows. **THE SUITE XMLs HAD BEEN WIPED BY A LATER FILTERED `--tests` RUN** (the
results dir held 1,524 tests, which reads exactly like a suite that never ran), so every
gate was re-run or re-derived from the capture files rather than inherited — including a
`javap | grep -v 'line N:'` control proving the grid's AFTER binary is bytecode-identical to
the committed one. Ablation 6 arms / 17 pins with BOTH controls, no 0-RED arm; four residues
recorded in the pin KDoc and **not pinned** (no countdown pins). Two findings queued:
(CHK.129) an `as`-asserted callee loses its argument check entirely, and (CHK.128)
`arr?.[0]` on a nullish array union types as `any`. Grid 8×`added=0 removed=0`, cost_gate
exit 0 (no rebaseline, max `mapped.keyed` +1.18%), huge_methods exit 0 (844), warning-clean.

**(P18.70) — SIX (CHK.*) ITEMS, AND THE INSTRUMENT THAT WAS DROPPING ROWS, 18,652 / 0 / 3 (2026-09-11).**
Two (CHK.\*) items closed and **both queue items were wrong about their own size**, a
sixth round running. **(CHK.122)**: the item named ONE route for a missing `in`-guard
consult; measured against both references it is FOUR — flow, destructured, FILE-LEVEL
`const`, PARAMETER — and the last two never touch the `any` bail the route helpers
live on, so a per-route fix could not reach them. The consult went to the emission
FUNNEL. **The round's real decision was found by measuring**: with exhaustion
REFUSING, the profiles read `refused=5 exhausted=5` on services/server/harness, i.e.
every refusal on tsc's own sources was BLIND. It now DECLINES on exhaustion, so it
can suppress only on a POSITIVE finding — all eight profiles read `refused=0`, and
the residue is pinned AS a residue. **(CHK.119)**: the item says a `function` receiver
NEVER reports TS2339; it does, and **every row it emitted carried a display neither
reference produces**. Four expando WRITE forms were uncollected (element-access,
no-substitution-template, template-span, tagged) — each an ours-only FALSE POSITIVE —
and the display is now the SIGNATURE for a function with no expando. **The order of
those two fixes is load-bearing and is pinned.** **THE GRID IS A CONTROL FOR (CHK.119),
NOT A GATE, AND THE ROUND SAYS SO**: B431 emits ZERO rows on all eight profiles.
**THE INSTRUMENT FAILED TWICE, BOTH TIMES PLAUSIBLY** — `scripts/ref_matrix.py`
(new: the three-compiler adjudication every round rebuilds and discards) first parsed
only the REFERENCE row format and read our row set as empty (`missing=8` where we
emit all 8), then keyed on `(file, line, code)` so two rows of one code on one line
COLLAPSED. Both now refuse/resolve, plus TEXT-DIFF for a message divergence, which
(PARITY.1) says nothing else in the repo could see. Every conclusion drawn with the
broken key was re-run. **The Phase-18 WORK ORDER note was trimmed away a SECOND time**
by (P18.66) — restored, re-anchored under a `## QUEUE` heading, and
`scripts/check_plan_structure.py` now fails when it is gone. **(CHK.125)**: TS2394's predicate knew only `any`, so an ordinary
`f(string)/f(number)/f(unknown)` overload set was an ours-only FP — tsc's rule is TWO
rules (RETURN assignable in EITHER direction, PARAMETERS requiring the impl be wider),
and `unknown`/`never` are assignable in exactly one direction each, so the positions
disagree about four of eight combinations; ten cells measured, three of them negative
controls that still report. **(CHK.126)**: every silence-asserting pin in
`M04ExpandoSpineMigrationTest` re-measured — **ten countdowns, not the eight queued**,
renamed `residue - ` with their reference rows; **and three were made UNFALSIFIABLE by
this round's own (CHK.119) display change** (they keyed on `typeof Foo`, which an
expando-free function can no longer produce), fixed in the same session that broke them
and verified falsifiable rather than assumed. The audit found **(CHK.127)** — the
collector OVER-declares in the opposite direction from (CHK.119) — **and it was closed
in the same round**: twelve positions measured against both references, an object
literal is a HARD STOP (an array nested in an objlit value and an objlit nested in an
array are both refused, which rules out a rule about the immediate parent), and its own
residue pin from one commit earlier fired as designed. **(CHK.124) PARTLY CLOSED, AND ITS GATE IS THE FINDING**: B431 required a NESTED
read, so the item's headline shape — a FILE-LEVEL read, no nesting — was silent;
EX_TOP is now admitted. **The 8-profile grid is VACUOUS for this family, measured**
— a positive control reads **0 admissions on all eight profiles and 0 on cronstrue,
marked and a 600-file project** — so the CORPUS was the gate, and it caught a real
defect first try (a dangling dot's zero-width identifier grew a `Property ''…` row;
B431 was missing the empty-name guard its sibling has always had). Two residue pins
written earlier in the same session fired as designed and were converted. The
receiver-KIND half of (CHK.124) stays open and pinned as refusals.

