# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **197,347** lines (191,070 when the metric was created; the (P18.9)-(P18.32) checker-parity arc ADDED ~5,200, which are fixes and pins, not extractions — the metric counts extraction progress and this arc made none) (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: FOUR
checker reads (the fourth, `instantiateTupleElements`, added by (P18.28)), one table write,
stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.32) — A WEAK GUARD TARGET NARROWS ((CHK.98b), WHOSE DIAGNOSIS WAS WRONG), AND (CHK.98)(b)'s UNION GATE LIFTS, 17,963 → 17,981 / 0 / 3 (2026-09-06).**
**(CHK.98b) CLOSED — and the round's main finding is that the item misdiagnosed it.** Queued as
"NESTED-TERNARY predicate narrowing", it is neither about ternaries nor about the property-access
family: a plain `if`, a single ternary, `&&` and the nested ternary all fail identically, and the
guard narrows correctly the moment its TARGET declares one REQUIRED member. The axis is the
target's OPTIONALITY, and the mechanism is a round-480 ASYMMETRY — that round gave
`missingVsOptionalProvesNotSubtype` to the NEGATIVE guard filter and never to the POSITIVE one, so
the negative branch was right all along. The positive arm now mirrors tsc's
`getNarrowedType(assumeTrue)`, with a vetoed member falling to the existing narrow-DOWN arm so the
two together are tsc's `mapType`. **(CHK.98)(b)'s union gate LIFTED, with a three-binary receipt
rather than a green grid**: grid 8×`added=0 removed=0` and knip 51 → 51 byte-identical, while a
third binary (gate lifted, 98b reverted) reads **52** — the extra row being exactly the one the
item named, which is what proves the gate's population is live and the green is not vacuous; the 8
profiles carry ~26 such annotations in total and are closer to a control. **The item's knip number
49 is stale** (a REBUILT parent reads 51; 49 was the pre-(CHK.98) recon commit) — a recorded
baseline is a claim about a BUILD, not a commit, now shown for a library baseline too. 18 net pins,
2 arms (8 RED / 3 RED) with two negative controls recorded as non-discriminating BY CONSTRUCTION
rather than counted, and every arm carrying a pin-COUNT assertion after (P18.31)'s deleted-pins
hazard. Corpus 8,837/0, **`cost_gate.py` exit 0 at +0.00% on every counter**, `huge_methods.py`
exit 0. Residue pinned as a KNOWN GAP: a nullish union contextual parameter types correctly but the
property-access reader emits no TS18048 — a false NEGATIVE, which is why the lift is safe.

**(P18.31) — CONTEXTUAL PARAMETER TYPES REACH THE ARGUMENT AND PROPERTY-ACCESS READERS ((CHK.98)(a)/(b)/(c)), AND A SCRIPTED SPLICE THAT SILENTLY DELETED THREE PINS, 17,932 → 17,963 / 0 / 3 (2026-09-06).**
**(CHK.98)(a)/(b)/(c) LANDED.** The ccet ARGUMENT reader (TWO `anyType` sites, not the one the
item named — and `ccetObjlitMemberFrame` additionally had to COPY the `localTypes` map it was
SHARING with the enclosing frame), the PROPERTY-ACCESS readers (`cpaAnnotationCtx` at four sites
plus an objlit-METHOD arm, gated to a NON-union contextual parameter type), and the pull's exact
arms (Conditional, As/Satisfies, `=`, `this`, REST, the array-literal edge). Matrix **99 → 125
rows matched against both references, ours-only 7 → 2**. Three defects the item did not name
landed with it, including **(CHK.98c)** as (b)'s prerequisite — which is PRE-EXISTING on HEAD and
fires for a plain function-declaration parameter. **Five of the item's claims are measured wrong**,
the sharpest being its `typeNode.bypassed` **+31%** memo precondition: measured **+0.22%** with no
memo built, so the memo is not one. 31 new pins plus **five flipped from an absence assertion to a
value one** — two the item predicted, and two residues of EARLIER rounds found only because the
full suite ran; every flip re-verified here against tsgo 7.0.2 AND pristine 6.0.3 before being
accepted. **The round's instrument hazard is a THIRD way an arm reads a false zero**: a scripted
splice silently DELETED three pins, so two arms read `0 RED` while `git diff --shortstat` and a
per-arm `cmp` both passed — they test the source under ablation, never the pin POPULATION.
**`cost_gate.py` FAILED and was rebaselined WITH ATTRIBUTION** in the same commit: an arm disabling
only (a) reads exit 0, and (a) owns 83% of the `narrow.memoServed` rise (+2.29%) and 43% of
`mapped.hits` (+2.22%) — both cache-HIT counters rising faster than their own populations, because
a contextually-typed parameter becomes a NARROWABLE REFERENCE where an `any` one was not;
`spine.nodes` +0.00%, `output.errors` 46 → 46. Corpus 8,837/0, `huge_methods.py` exit 0, grid
8×`added=0 removed=0` with the BEFORE arm rebuilt in a directory no subagent wrote to. 19 arms
(a12 recorded as a redundant guard).

**(P18.30) — THE ARRAY FALLBACK, INTERSECTED CALLBACK SIGNATURES AND THE OPTIONAL-CALL RESULT (STAGE 2 OF (CHK.97)), AND A `noImplicitAny` GATE THAT WAS INERT ON EVERY STRICT PROJECT, 17,916 → 17,932 / 0 / 3 (2026-09-06).**
**(CHK.97) stage 2 LANDED** — three of five deliverables: tsc's ARRAY FALLBACK (checker.ts:15949),
derived from the RECEIVER because a method type here has no parent symbol, which costs one extra
CALLABLE gate a signature-list route would not need; tsc's `getIntersectedSignatures` (:33085),
one `intersection` flag on `combineUnionParameters` wired through `callableSignaturesForCtx` and
`cpaComputeArgCtxTypes`, so a callback ARGUMENT of a union callee is typed and graded with a
wrong-typed USE rather than the TS7006 silence; and the OPTIONAL-call result. Matrix 55 → 61 of 73
pristine rows, ours-only unchanged at 3 (display only). **Three more of the item's claims are
measured wrong**: retiring the `≥2` suppression does NOT make r09 report (a SECOND suppression,
the `differ` branch's non-generic silence, sits above it); the `noImplicitAny` gate written as tsc
spells it is **inert on every `strict` project** — the field is not implied by `strict` here and
the repo-wide spelling is the disjunction (29 sites), caught only because the build measured ZERO
row movement, which a green suite/corpus/grid all look like too; and our `identityRelation` is
LENIENT for a function type nested in a signature parameter, which is what keeps the fallback out
of tuple-union `.filter`. **The round's own instrument hazard**: the implementation subagent ran
its grid in the same `build/bench` directory this session had snapshotted its BEFORE arm into, so
the captures were overwritten with the post-change binary — the grid script's `sha256sum` refusal
fired, and the arm was rebuilt from `git show HEAD:` into a directory the subagent never saw. 16
pins, 11 arms (9 discriminating; a2/a10 recorded as redundant guards on every reachable shape and
kept as tsc's own rules). Corpus 8,837/0, `cost_gate.py` exit 0 with no rebaseline,
`huge_methods.py` exit 0, grid 8×`added=0 removed=0`.

**(P18.29) — UNION CALLEES ARE COMBINED, NOT SILENTLY `any` (STAGE 1 OF (CHK.97)), AND FOUR OF THE ITEM'S OWN PREDICTIONS ARE MEASURED WRONG, 17,874 → 17,916 / 0 / 3 (2026-09-06).**
**(CHK.97) stage 1 LANDED.** The call RESULT was `any` for EVERY union callee and the argument
positions were judged off a CONCATENATED signature list; one home,
`combineUnionSignatures` (tsc's `getUnionSignatures`), now does PASS 1 and PASS 2 with the
helpers mirrored 1:1 and is memoized by the union's `Type.id` (exact, because INV.5(a) interns
unions by member-id list), feeding both return readers, a new construct arm (which kills the
false TS2351 on every union `new`) and the ccet hand-off to the ordinary argument gate, with the
nullish check moved ABOVE the union branch. Against pristine 6.0.3 the matrix goes **23 rows with
6 WRONG → 58 of 73 matched with ZERO ours-only**, every one of the 15 misses attributed. **Four of
the item's predictions are refuted by building it**: "the too-few TS2554 comes free" is FALSE
(TS2554 fires only for a function-DECLARATION callee, and a union callee is a variable by
construction — a pre-existing gap this round records), arm a2's predicted victims are answered
upstream by PASS 1, arm a3 as specified is undiscriminated, and arm a8 names stage-2 work. Three
re-homings were forced by measurement, one of which closed a PRE-EXISTING false positive
(`declare function h(...xs: 1[]); h(1)` was TS2345 on HEAD), and four hand-written pins turned
out to be pinning OUR divergences (3 × `TS2349 → TS2722` for a nullish callee, and a silence
assertion where both references report `'never'`) — all corrected to VALUE pins after
re-verifying against tsgo 7.0.2 and pristine directly. No double emission (all rows from
`checkSpine`; the walker ORDER is what buys it). 42 pins, **12 arms, every one discriminating**.
Corpus 8,837/0, `cost_gate.py` exit 0 with no rebaseline — combining every union callee costs
~0.00% on its own — `huge_methods.py` exit 0 (`ccetUnionCalleeChecks` SHRANK ~100 lines), grid
8×`added=0 removed=0`. Stage 2 stays open: the array fallback, `getCallSignaturesOfType`'s union
arm, callback contextual typing, the optional-call result and `this` params/TS2684.

**(P18.28) — AN OBJECT REST, ITERABLES, CONTEXTUAL PATTERN PARAMETERS AND THE DISCRIMINANT CARRY (STAGE 2 OF (CHK.96), THE ITEM CLOSED), AND THE THREE DEFECTS THE GATES FOUND, 17,792 → 17,874 / 0 / 3 (2026-09-06).**
**(CHK.96) stage 2 LANDED, and the round INHERITED an interrupted session's tree** — the
implementation was written and compiled with the gates unrun and one pin red, so the round's own
work is the gating and the three defects it turned up. An object REST reads tsc's `getRestType`
(members copied MUTABLE, `private`/`protected`/`#private` dropped always and methods/accessors
only under a CLASS, a generic source refusing); `[Symbol.iterator]` sources read through tsc's
fast and slow legs, which needed the instantiator to rebuild a TUPLE **as** a tuple — without it
`Map<K, V>`'s `MapIterator<[K, V]>` had no readable slots, silently; contextual pattern
parameters reach six readers; the pattern's implied contextual type widens every array-literal
element; and the destructured-discriminant carry implements `getNarrowedTypeOfSymbol` INVERTED
(each sibling's narrowed type filters the parent's constituents, since this checker narrows by
path strings). **The three defects: a blind pin** whose `none { "=>" }` guard contradicted its own
expected message (the compiler was right); **a corpus double-emission** — `destructuringUnspreadableIntoRest`
50 → 72 rows, `--passTiming` naming `checkObjectRestUnspreadableAccess` 44 against `checkSpine`
22, deduped in the walker because it runs SECOND (ablated: 1 of 82 pins RED); and **a grid
regression**, one ours-only TS2322 on the three profiles carrying `services.ts:3264`, where a
CONDITIONAL of array literals gets no implied contextual tuple — both reconstructions measured
wrong (the second needs flow-narrowed branch elements, which `getTypeOfExpression` never gives),
so the shape now refuses, as HEAD does, and is queued as (CHK.107). Corpus 8,837/0, core
16,385/0, `cost_gate.py` exit 0 with no rebaseline (`typeOfExpr.calls` +0.89%), `huge_methods.py`
exit 0, grid 8×`added=0 removed=0`.
