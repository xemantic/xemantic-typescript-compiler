# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **195,122** lines (191,070 when the metric was created; the (P18.9)-(P18.25) checker-parity arc ADDED ~2,400, which are fixes and pins, not extractions — the metric counts extraction progress and this arc made none) (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: three
checker reads, one table write, stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

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

**(P18.23) — CONST ASSERTIONS STOP BEING `any` (STAGE 1 OF (CHK.93)), AND THE NAME-RESOLUTION SEAM IS CENSUSED FOR (INV.0) STEP 4, 17,516 → 17,573 / 0 / 3 (2026-09-05).**
**(CHK.93) stage 1 LANDED.** Every object, array and enum-member `as const` was `any` (the `const`
type reference fell off the resolution ladder to `errorType`); now the assertion answers its operand's
const-context type, object members keep their literals with the context computed once, a const array
is a frozen tuple — which needed the relation rule tuples always needed (tuple → `Array<T>` by
elements, removing a pre-existing false TS2740 on every declared tuple against an array) and an
existence-only tuple-inherits-Array answer the grid demanded (`isArray(diag) ? diag.slice(1)` in tsc's
own utilities.ts) — TS1355 is tsc's `isValidConstAssertionArgument` in both spellings, and the
prerequisite literal-property-write false positive (`mo.v = "a"` against `v: "a"` reported `'string'`)
is closed first. 16 of 30 recon rows byte-identical to pristine; the readonly half (TS2540, TS4104,
`push` on a const tuple, `readonly [1, 2]` display) stays queued as stage 2. 57 pins, 12 arms all
RED; core 16,084/0/3, corpus 8,837/0, `cost_gate.py` exit 0 with no rebaseline, grid 8×`added=0
removed=0`. **(INV.0) step 4 censused by read-only recon**: the name-resolution surface partitioned
into a two-commit `NameResolver` extraction (~1,300 code lines), an ambient row of three reads and
no writes, eleven invariants each mapped to its pin classes — and tsgo's closure-struct
`NameResolver` identified as exactly the shape § 10 forbids.

**(P18.22) — A LOCAL INITIALIZED FROM AN ENUM MEMBER IS READ AT ITS FLOW TYPE AT EVERY READER, IN BOTH DIRECTIONS, 17,462 → 17,516 / 0 / 3 (2026-09-05).**
**(CHK.85)(b) LANDED and (CHK.85) is CLOSED** ((c) is the staged `as const` item (CHK.93), designed
by read-only recon over 32 measured rows). `let k = K.A; k = K.B; const w: K.B = k` was an ours-only
TS2322 and `if (k === K.B)` a lost TS2367: `narrowByAssignmentRhs` gains tsc's
`getAssignmentReducedType` over enum atoms (symbol lookup only), `isNarrowableTarget` admits an enum
target, the const symbol half keeps the member, the arith and ccet recorders record an
enum-initialized body local, and the TS2367 emitter reads the flow type through a new REPORTING walk
kind — the flow walk's stale-antecedent pass-through for an unclassified overwrite is sound for a
suppression consumer and a false positive for a reporting one (`classifier.ts`'s
`token = scanner.reScanTemplateToken()`). 49/49 recon rows and 60+ probe rows match both
references bar four form residues; a (CHK.91) pin was corrected (both references WIDEN `{ v: k }` for
a `const k = K.A`). 52 pins, 17 arms; core 16,027/0, corpus 8,837/0, `cost_gate.py` rebaselined
(`globals.lookups` +2.30% = 414 reporting walks, attributed), grid 8×`added=0 removed=0` after an
intermediate build's +3/+4 rows were closed by the reporting walk and a `let` widening.

**(P18.21) — AN OBJECT LITERAL'S ENUM MEMBER WIDENS THE WAY tsc WIDENS IT, FREE OF THE DISCRIMINATED-UNION LOSSES THAT REFUSED IT TWICE, 17,424 → 17,462 / 0 / 3 (2026-09-05).**
**(CHK.91) LANDED, closing (CHK.85)(a).** The (P18.18) arm was rebuilt ALONE to NAME its 22 grid
sites (tsbuildPublic returns and a `Map.set`, conditional returns in six services files, two
union-annotated declarations, one assignment), then landed as three pieces: widen only a FRESH
enum-member ACCESS (tsc widens no identifier, `const` local, `as` or shorthand — the arm did, two
false positives it had not reached), tsc's `isConstContext`, and the SOME-rule keep over the push
context ?: a PULL walking every `getContextualType` root. The ARGUMENT root reads the callee's RAW
parameter types: `cpaComputeArgCtxTypes` measured +2.9% `typeOfExpr.calls` (overload selection
typing every argument) and handed back a circular keep for `id({ v: K.A })`. `const w: K.A = o.v`
reports `Type 'K'`, `o.v = K.B` and `o.kind === K.B` lose their ours-only rows, every
discriminated-union position is byte-identical to HEAD. 38 pins, six arms (`as const` recorded as
unobservable); core 15,973/0, corpus 8,837/0, `cost_gate.py` exit 0 (`typeOfExpr.calls` −0.16%),
grid 8×`added=0 removed=0`. Read-only recon rewrote (CHK.85)(b) (MEANING both ways: six false
positives after a reassignment, seven lost `k === K.B` / body-local rows; four seams named) and
(CHK.92) (the optional-parameter display is tsc's `isRelatedTo` nullable strip, wrong here in both
directions; the `gU(1)` claim was false), and found the primitive mis-assignment probe blind on
both sides for an enum-member local.
