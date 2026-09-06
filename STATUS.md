# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **196,306** lines (191,070 when the metric was created; the (P18.9)-(P18.28) checker-parity arc ADDED ~5,200, which are fixes and pins, not extractions — the metric counts extraction progress and this arc made none) (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: FOUR
checker reads (the fourth, `instantiateTupleElements`, added by (P18.28)), one table write,
stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

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

**(P18.25) — A TUPLE'S ARRAY MEMBERS GET TYPES AND ITS CALLS GET CHECKED, AND DESTRUCTURED BINDINGS ARE MEASURED INTO (CHK.96), 17,611 → 17,664 / 0 / 3 (2026-09-05).**
**(CHK.94) LANDED.** Every `Array` member READ on a tuple was `any` and every call through one unchecked;
an interned `Array<union>` / `ReadonlyArray<union>` base (a rest slot INDEXED as tsc does, optional slots
joining `undefined`) is consulted on the miss path only, and the call, overload, argument and callback
paths followed for free — except two PRE-EXISTING array defects the typed members exposed and fixed: an
array-literal argument against a literal-union element was a false TS2769 (`(1 | 2)[].concat([1])` on
HEAD), and a union receiver's `.map` became a false TS2349 because tsc combines union signatures and
refuses only generic-vs-generic non-identical ones — the grid found the same ours-only TS2349 on tsc's own
`fourslashImpl.ts` and the refinement closes it. 128 measured cells went 46 → 83 matching tsgo with 0
regressions; 53 pins, 11 arms (one provably unobservable, recorded); core 16,175/0/3, corpus 8,837/0,
`cost_gate.py` exit 0, grid 7×`added=0 removed=0` + harness `removed=1`. Read-only recon measured
destructured bindings over ~120 cells: an OBJECT pattern's members are already typed at every reader but
the argument and TS2367 ones, every ARRAY pattern / default / nested / rest / contextual pattern parameter
is `any` everywhere — queued as the staged (CHK.96), correcting (CHK.46)'s entry.

**(P18.24) — CONST ASSERTIONS BECOME READ-ONLY (STAGE 2 OF (CHK.93), THE ITEM CLOSED), AND THE TUPLE-MEMBER AND BODY-LOCAL-ARGUMENT RESIDUES ARE MEASURED INTO (CHK.94)/(CHK.95), 17,573 → 17,611 / 0 / 3 (2026-09-05).**
**(CHK.93) stage 2 LANDED.** A const-context object's members are read-only (TS2540, TS2704 instead of a
pre-existing TS2790 double, `{ readonly v: "a"; }` display), a const array is a readonly tuple unless its
contextual type has a mutable array-like constituent (tsc's `isMutableArrayLikeType`, measured), the
`readonly [T, U]` type operator stops being a no-op, members of a readonly tuple fall to `ReadonlyArray`
(`push` → TS2339), TS4104 replaces TS2740 for readonly→mutable at every position — as pristine's TS2345
chain at an argument, where tsgo prints a bare TS4104 (the arc's third reference divergence) — and the
declared-type twin `declare const rt: readonly [1, 2]` went from 2 of 7 rows to 7 of 7. The grid found
the round's most important fix: B378's guard install put the guard's OWN predicate type into the
then-branch, "FP-safe" only while `readonly T[]` related to `T[]` — tsc's own `core.ts:1685` became a false
TS2345 — so it now installs the declared constituent that relates (tsc's `getNarrowedType`). The pin
walker `checkReadonlyTupleElaboration` is kept (20/22 codes reproduced under PassLab). 38 pins, 15 arms
RED; core 16,122/0/3, corpus 8,837/0, `cost_gate.py` exit 0, grid 8×`added=0 removed=0`. Read-only recon
measured 220 cells into two items: every `Array` member READ on a tuple is `any` (an interned
`Array<union>` base on the miss path is the seam; the corpus has zero baselines that can see it), and the
argument gate is silent for EVERY body-local scalar / `let` / annotated-primitive local, not only strings.
