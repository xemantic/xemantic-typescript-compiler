# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **196,976** lines (191,070 when the metric was created; the (P18.9)-(P18.30) checker-parity arc ADDED ~5,200, which are fixes and pins, not extractions — the metric counts extraction progress and this arc made none) (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: FOUR
checker reads (the fourth, `instantiateTupleElements`, added by (P18.28)), one table write,
stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

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

**(P18.27) — DESTRUCTURED BINDINGS GET THEIR TYPES (STAGE 1 OF (CHK.96)), AND CONTEXTUAL CALLBACK TYPING IS RE-MEASURED INTO (CHK.98), 17,717 → 17,792 / 0 / 3 (2026-09-06).**
**(CHK.96) stage 1 LANDED.** Every ARRAY / tuple pattern, default, nested pattern and pattern PARAMETER was
`any` at every reader and an object pattern `any` at the argument and TS2367 readers; a pure
`bindingElementType` (tsc's `getBindingElementTypeFromParentType`: slots, optional → `| undefined`, rest as a
sliced MUTABLE tuple, defaults joined with the subtype drop, unions lifted per constituent except tsc's
narrowed-symbol precondition the corpus found) now feeds seven plug points including the symbol half. The
grid forced FOUR root fixes on tsc's own sources: an optional property's `T["k"]` carries `undefined`,
mapped `-?` strips it, an uninferrable type-guard TP narrows to its constraint (a pre-existing false
positive), TP-carrying fn members are refused. 0 ours-only rows over ~120 cells; 75 pins, 9 arms with two
round-927 pairs recorded; core 16,303/0, corpus 8,837/0, `cost_gate.py` rebaselined with attribution
(+2.32% `typeOfExpr.calls`, 80% the ccet leave-time typing of 305 pattern initializers), grid
8×`added=0 removed=0`. Read-only recon measured contextual callback typing over 99 rows: (CHK.39) already
types a callback's parameters for the assignability reader and hover — CLAUDE.md's (CHK.30) entry was
STALE and is corrected — and the residue is the ccet ARGUMENT reader plus three property-access sources,
queued as (CHK.98) with two narrowing gaps (98b/98c) behind its union gate.

**(P18.26) — A BODY-LOCAL SCALAR CONST REACHES THE ARGUMENT GATE, AND UNION CALLEES ARE MEASURED INTO (CHK.97), 17,664 → 17,717 / 0 / 3 (2026-09-06).**
**(CHK.95) LANDED.** In every body context `const s = "a"; takeB(s)` was silent for every non-enum scalar
initializer, every `let` and every annotated primitive local (48 of 72 cells) while file level reported: a
SYNTACTIC scalar predicate in the ccet pre-scan (a const keeps the literal, a let widens — a counter arm
shows why it must be syntactic: admitting the conditional moves `typeOfExpr.calls`) and a primitive-
annotation arm at leave time, with a per-body name set so an annotated duplicate reads `any`. The mutable
boolean is REFUSED after measurement (tsc's `boolean` is `true | false` and narrows by assignment; ours
is an intrinsic, and the file-level twin is a pre-existing false positive). Two post-spine emitters that
double-emitted rows the gate now owns were deduped. 89 of 125 cells match both references, 30 named
residues; 53 pins, 7 arms; core 16,228/0, corpus 8,837/0, `cost_gate.py` exit 0 (`globals.lookups`
−0.31%), grid 8×`added=0 removed=0`. Read-only recon measured union callees (26 rows, five extracted
pristine fixtures): the call RESULT is `any` for EVERY union callee, three wrong-row families beside the
silences, and TS2349 where tsc says TS2722 — queued as (CHK.97) around tsc's two-pass `getUnionSignatures`
plus its array fallback.
