# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **198,100** lines (191,070 when the metric was created; the (P18.9)-(P18.37) checker-parity arc ADDED ~5,200, which are fixes and pins, not extractions — the metric counts extraction progress and this arc made none) (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: FOUR
checker reads (the fourth, `instantiateTupleElements`, added by (P18.28)), one table write,
stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.38) — AN ARRAY-LIKE *ARGUMENT* IS DECIDABLE AGAINST AN ARRAY-LIKE *PARAMETER* ((CHK.103) STAGE 2), AND FIVE OF THE ITEM'S SIX ROWS CARRY NO SPREAD, 18,110 → 18,136 / 0 / 3 (2026-09-07).**
**(CHK.103) stage 2 CLOSED; (CHK.108) queued with its mechanism named.** The item called its
residue a spread question and named a whole-literal array-to-array fallback as the seam. Measured,
**five of its six rows carry no spread at all** — `takeStrArr(nums)` with `nums: number[]` against
`(x: string[])` is silent here and reported by both references, and so are `Bar[]` → `Foo[]`,
`C2[]` → `C1[]`, `number[][]` → `string[][]` and both tuple shapes — so the gap is the ARGUMENT
reader's FP firewall having no array-vs-array gate, and the item's seam covers one row of six.
The licence is the DECLARATION position (as (CHK.83)'s was), so the decidability question is asked
one level down, of the ELEMENT pair, by `canUseTypeEngine` ITSELF — the rule cannot drift from the
position that licenses it, and `any[]` is refused in both directions with no rule of its own. On
the item's fixture ours goes **6 → 12 of the reference's 12 rows, all twelve byte-identical to
pristine**. **The grid found exactly ONE ours-only row on all eight profiles and it was a NARROWING
gap**: `Debug.assertEachNode(elements, isArrayBindingElement)` narrows by an `asserts nodes is
readonly U[]` signature, and `narrowByAssertCall`'s type-parameter recovery understood only a BARE
`U` — **an array OF a type parameter RESOLVES**, so it is neither `errorType` nor `anyType` and the
recovery's own gate never opened for it (the first attempt put the fix inside that gate and was
inert). **Two traps, both found with a probe rather than by reading**: `ternaryOfArrayLiterals`
SUBSUMES an `init !is ArrayLiteralExpression` test, so relaxing the `!is` alone did nothing through
a whole build cycle; and an EMPTY array literal IS tuple-like (tsc's empty tuple) where
`elements.any { … }` says false, which the full suite caught and no other instrument could. 26 pins,
all read from pristine; 9 arms, 8 discriminating, a3/a4 a round-927 PAIR and a7 recorded
NON-DISCRIMINATED with its reason rather than claimed. Grid **8 × added=0 removed=0** on the final
binary (a1's +1 row is the round's own positive control that the harness is live), `cost_gate.py`
exit 0 (largest delta **+0.03%**, no rebaseline), `huge_methods.py` exit 0, build warning-clean.

**(P18.37) — AN ARRAY LITERAL WITH A SPREAD GETS A REAL TYPE ((CHK.103) STAGE 1), AND THE ROUND-471 ARM IT WOKE WAS THE REGRESSION, 18,098 → 18,110 / 0 / 3 (2026-09-07, RECOVERED ROUND).**
**(CHK.103) stage 1 landed; stage 2 re-queued with its 6 residual rows named.** Every array literal
carrying a `...spread` was `any`, so `f([...xs])` went unchecked at every position and the var-decl
string layer printed `Type 'array'`. A spread now contributes its ITERATED element type (array-like
→ element type, tuple → element UNION, `string` → `string`, else the iteration type; REFUSED for a
union / intersection / type parameter / `any` — stated false negatives, never a guess), a const
context inlines a fixed tuple's slots, and the elaborator reports a spread at its own node. On the
item's population fixture ours goes **1 → 6 of the reference's 12 rows, all six byte-identical to
pristine `typescript@6.0.3`** — including the `readonly [number, string]` row where **tsgo 7.0.2
diverges** (TS4104) and pristine outranks it.
**THE ROUND WAS RECOVERED FROM AN INTERRUPTED SESSION, AND THAT IS THE FINDING.** The work sat
uncommitted with a `Checker.class` NEWER than its source and a complete-looking `grid-after`, so it
read as finished; the capture was in fact 4 minutes OLDER than the session's last fix, and re-running
the grid on the final binary produced a **byte-identical** result — that last fix was INERT and the
round as left would have shipped **a false TS2322 on 3 of the 8 profiles**. Cause: giving the shape a
type at all made ROUND 471's literal-preserving arm reachable for the first time, and **that arm bails
on any spread of its own** — it was built for tsc's own `invalidOperationsInPartialSemanticMode`
(`services.ts:~1560`, no spread) and its sibling `invalidOperationsInSyntacticMode` (`:1607`) is the
same shape WITH one, so the literal fell back to a path that widens each element to its base
primitive and unioned a bare `string` into `readonly (keyof LanguageService)[]`. The dead session had
patched the wrong layer; the landed fix is the spread contribution inside round 471's arm. Four
20-second probes settled what three readings had not. 12 pins, all read from pristine; grid
**8 × added=0 removed=0**; `cost_gate.py` exit 0 (`typeOfExpr.calls` **+0.07%** = 445 spread
expressions no longer skipped, rebaselined in this commit); `huge_methods.py --fail-over 0` exit 0.

**(P18.36) — A GENERIC INTERFACE'S FN-TYPED MEMBER STOPS BEING FROZEN AT FIRST TOUCH ((CHK.102)), AND THE FREEZER IS INV.5(c)'s CACHE, 18,076 → 18,098 / 0 / 3 (2026-09-07).**
**(CHK.102) CLOSED.** `interface Box<T> { f: (x: T) => T }` was ONE object shared by every
instantiation and frozen at first touch, so `Box<string>.f` read `(x: number) => number` after a
`Box<number>` was touched first — a false TS2345, a LOST one and the wrong display, **in both
declaration orders**. The two `substituteOuterTypeArgs*` helpers are now NON-MUTATING (round 465's
"mints FRESH objects, never mutates", applied to the one member of the family that kept the
in-place form), with a signature's own type parameters CLONED when the outer mapper moves a
constraint or default. **The item's mechanism is wrong in four places, found with an identity PROBE
rather than by reading**: the freezer is INV.5(c)'s context-keyed `mappedNodeTypes` cache and NOT
the `resolveReferenceMembers` seam the item named (never touched) — two instantiations of one
interface produce an identical fingerprint because the target's own `Type.TypeParam` is in scope
both times, so **17.39's KDoc precondition "rawType is always freshly allocated" has been FALSE
since INV.5(c) added a second cache below the bypass**; there is no `PropertySignature` node kind in
this parser at all; the grid is a control not because the shape is unreached but because a frozen
member never DECIDES a diagnostic there (the compiler profile makes **6,383 minting calls over 46
nodes asked with ≥2 distinct argument vectors**, the most-exposed being `lib.es5.d.ts`'s `Array<T>`
at 30); and two freezes go unnamed — a method's fn-typed PARAMETER, and an inner generic
signature's constraint, which survives a fix to the object half. 22 pins, every claim in BOTH
orders (one order is green on the frozen binary — the item's own trap, cleared); 5 arms, with a2/a3
a round-927 pair and **a5 exposing two BLIND pins** (discarding the mint yields the raw `T`, one
absence replacing another), recorded rather than claimed. Corpus 8,837/0, at-risk set 151/151
classes with coverage asserted from the XMLs, `cost_gate.py` exit 0 — minting ~6,400 fresh objects
per compile moves **no counter**, the repo's own "an allocation count is not a cost" on a fourth
instrument — `huge_methods.py` exit 0, grid 8×`added=0 removed=0`. No ambient read added to
`TypeInstantiator`; ledger row 3 stands at four.

**(P18.35) — THREE SHIPPED NARROWING DEFECTS CLOSE, AND (CHK.101)'s OWN DELIVERABLE IS BUILT, MEASURED CORRECT AND *REFUSED* ON GRID EVIDENCE, 18,043 → 18,076 / 0 / 3 (2026-09-07).**
**The item's deliverable (a) is REFUSED, and that is the round's most valuable output.** The
nullish-constituent emission was built, measured **correct on 20 of 20 reference rows**, and removed
entirely: the grid reads **+19 to +21 ours-only rows on EVERY profile** for its reader half. Correct
on every fixture and unlandable on real code is exactly what the 8-profile grid exists to catch, and
the refusal is verified by VALUE (the item's fixture reads parent = 2, final = 2). **What landed
instead are three OTHER shipped narrowing defects, two of them ours-only FALSE POSITIVES** —
verified here against both references: a loose `==`/`!=` against `null` now tests BOTH nullish
values (tsc's `TypeFacts.EQUndefinedOrNull`), a DEFAULTED parameter no longer sees `undefined` in
its body (`getNonUndefinedType`), and a logical assignment's VALUE is the surviving LHS ∪ RHS rather
than the whole declared LHS. **Four of the item's claims are measured wrong**: "the same pair
reports at a declaration and a return" held only because its fixture used a UNION target, which
`canUseTypeEngine` admits by its own line — for a non-union object target every position is silent
(20 reference rows against 2 of ours), so the stated seam covers under half the defect; its named
grid risk already narrows on the parent; and sub-part (c) is broader than stated. Only the mechanism
was right. **The four narrowing gaps that must close before (a) is re-attempted are now recorded
with reduced fixtures** — they were invisible until now precisely because (a)'s diagnostic is what
would expose them. 33 pins, 7 arms (one a measured redundant guard with a mechanism, two recorded
BLIND because their observing mechanism IS (a)); two of the round's own pins were wrong rather than
the compiler, expecting `'1'`/`'2'` where all three compilers print `'number'`. Corpus 8,837/0,
at-risk coverage asserted from the result XMLs (150 classes, 0 missing), `cost_gate.py` exit 0,
`huge_methods.py` exit 0, grid 8×`added=0 removed=0`.

**(P18.34) — A NAMESPACE-QUALIFIED ENUM MEMBER NARROWS ((CHK.100)), AND THE GRID'S *ADDED* ROW WAS THE POSITIVE CONTROL, 18,021 → 18,043 / 0 / 3 (2026-09-06).**
**(CHK.100) CLOSED.** `resolveEnumSymbolForQualifiedPath` is a dotted-path container descent
mirroring tsc's `resolveEntityName`, with a SINGLE segment delegated verbatim to the existing
resolver and the answer canonicalized exactly once — so round 425's split-key hazard is discharged
by construction rather than by care; `enumPathDeref` hops an `import * as ns` to the target module
FILE, because a namespace import's members live in the file's locals and behind its `export *`
barrels. 11 CLI fixtures against both references go **45 rows → 10**, all ten reference-agreeing.
**Three of the item's claims are measured wrong**: "both sides fail" (only the RHS readers do — arm
a2 reverting the annotation arm reads **0 RED over 22 pins**; kept for key-space agreement and
recorded as a measured redundant guard), "expect REMOVED rows on the profiles" (0 removed on all
eight — the 23 sites carry no diagnostic, so the class is LOST PRECISION there and the grid is a
control), and the four named readers are incomplete (a fifth owned an ours-only TS2366).
**The grid's ADDED row was the positive control**: with only the enum change in, three profiles
gained a row BECAUSE the discriminant began to narrow — which exposed a PRE-EXISTING root defect,
`checkPropertyAccessAssignment` having no flow narrowing at all where the var-decl, assignment and
return readers have had a suppression-only leg since rounds 410/438/456. Fixed at the root and
verified here against both references (the narrowed-to-`A` write is silent; the `"b"` twin still
reports), taking the grid to `added=0` everywhere. 22 pins, 7 arms, no round-927 pair (two legs of
one descent have DISJOINT red sets); two pins were repaired mid-round after reading 0 RED — BLIND,
not redundant. Corpus 8,837/0, `cost_gate.py` exit 0 at **+0.00% on every counter**,
`huge_methods.py` exit 0, grid 8×`added=0 removed=0`. The (P18.27) unblock is only HALF true: a
mutable `for-of` head now narrows, but a readonly head is still silent — and so is
`readonly string[]` with no enum anywhere, so that gap is independent of the discriminant.
