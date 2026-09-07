# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **198,435** lines (191,070 when the metric was created; the (P18.9)-(P18.37) checker-parity arc ADDED ~5,200, which are fixes and pins, not extractions — the metric counts extraction progress and this arc made none) (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: FOUR
checker reads (the fourth, `instantiateTupleElements`, added by (P18.28)), one table write,
stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

**(P18.42) — AN INTERSECTION DEDUPES ITS CONSTITUENTS BY TYPE ID ((CHK.106)(b)), AND (a) IS BROADER THAN THE ITEM RECORDED, 18,179 → 18,185 / 0 / 3 (2026-09-07).**
**(CHK.106) CLOSED — one part fixed, three verified against both references.** (b) had MOVED since
the item was written: (CHK.101) closed its `| undefined` half and what remained was `BP & BP` vs
`BP`, an idempotent intersection — `getIntersectionType` flattened, dropped `unknown` and reduced
primitives but never DEDUPED, where tsc's `addTypeToIntersection` keys its set by type ID. **The
dedupe needed an exemption, and the exemption is an INTERNING DIVERGENCE rather than a rule**: an
unrestricted id-dedupe also collapses `{ p: number } & { p: number }`, which both references print
in full, because two separate type-literal NODES are two types in tsc and ONE interned type here on
the project path. That leaves `T1 & T1` (an alias to an anonymous body) unfixed, recorded rather
than bought — the only rule separating it reads `aliasDisplayMap`, which is FIRST-WINS during the
walk and would make the dedupe a function of resolution ORDER (round 776). **(a) is BROADER than
recorded and stays refused**: it names the loss through a CARRIER member, and a DIRECT `Fn<number>`
annotation loses the name too while a direct `Obj<number>` keeps it — still (INC.27)/(INC.29)'s
interning-key question. (c) verified closed by (CHK.100); (d) verified a reference divergence
(pristine `(2 | 1)[]`, tsgo and ours `(1 | 2)[]`). 6 pins; 3 arms of which **one is recorded BLIND
rather than redundant** — the unrestricted-dedupe arm reads 0 RED because `diagnose()` gives the two
anonymous literals distinct ids, so the guard's evidence is the project path and a pin that could
see it belongs in `-project`. Grid **8 × added=0 removed=0**, `cost_gate.py` exit 0,
`huge_methods.py` exit 0, build warning-clean.

**(P18.41) — A CONDITIONAL OF ARRAY LITERALS UNDER AN ARRAY PATTERN TYPES EACH BRANCH AT ITS OWN FLOW POSITION ((CHK.107)), AND THE GRID THE ITEM CALLED THE GATE IS A CONTROL, 18,172 → 18,179 / 0 / 3 (2026-09-07).**
**(CHK.107) CLOSED, 1 → 6 of the reference's 6 rows.** `const [s, e] = typeof por === "number" ?
[por, undefined] : [por.pos, por.end]` read both leaves as `any`. tsc pushes the pattern's implied
contextual type into BOTH branches, so the source is `[number, undefined] | [number, number]` and
`bindingElementType`'s union arm gives `number` / `number | undefined`; the reconstruction is a
UNION of the branch tuples with **each element read AT ITS OWN FLOW POSITION** — the one thing
(CHK.96) stage 2 could not do, since `getTypeOfExpression` never flow-narrows and an un-narrowed
branch tuple reads slot 0 as `number | { pos: number; end: number; }`. The narrowing is a PARAMETER
of `arrayLiteralAsDestructuringTuple`, so the plain array-literal path is untouched. **The item's
gate claim is measured wrong and saying so is the point**: it predicts `removed=1` at
`services.ts:3264` and the grid is `added=0 removed=0` on all eight — the refusal made both leaves
`any`, which is SILENT, and that site's leaves feed a `RefactorContext` whose members are exactly
`number` and `number | undefined`, so neither arm has a wrong-typed USE to report. The grade is the
fixture, whose shape is `getRefactorContext` verbatim. 7 pins plus the stage-2 REFUSAL pin inverted;
3 arms all discriminating, one of them REDESIGNED after reading the same red set as another
(refusing every conditional is refusing the mechanism). `cost_gate.py` exit 0, `huge_methods.py`
exit 0, build warning-clean.

**(P18.40) — TS2454 FOR AN `if` JOIN, AND THE TS2448 CO-EMIT'S RULE WAS THE *TYPE* AND NOT CONST-NESS ((CHK.105)), 18,157 → 18,172 / 0 / 3 (2026-09-07).**
**(CHK.105) CLOSED for (a) and the `if` join; (CHK.110) queued with all three residues attributed.**
B78.1 read the co-emit rule as CONST-NESS off `typeGuardNarrowsIndexedAccessOfKnownProperty10`,
whose const is `any`-typed — what suppresses tsc's TS2454 there is tsc's `assumeInitialized` on
`AnyOrUnknown | Void`, and with an ordinary type BOTH references report TS2448 **and** TS2454. **Two
hand-written pins in this repo were pinning that wrong answer and are repaired here.** The corpus
then found the other half of `assumeInitialized` this population reaches: a CLASS STATIC INITIALIZER
is a different control-flow container from the declaration (tsc's `isOuterVariable`), where both
references report TS2448 alone. For the join, `markAssignments` scanned both branches of an `if`
unconditionally; the lattice it needed was already written — round 450's `daWalkStmt`, built for
`while (true)` — and is now consulted per variable, CONSERVATIVE TO REMOVE, so only what the walk
can prove changes. **The grid found the one guard the naive form needs and it is a tsc BINDER rule**:
the flow is unreachable after a call to a never-returning function, so an unassigned CALL statement
bails (two ours-only rows on three profiles at `fixPropertyOverrideAccessor.ts:83` without it) — as
a `DaState` FLAG, because in round 450's caller a bail means "do NOT remove" and would ADD
diagnostics. **A third deliverable was BUILT AND REVERTED**: requiring a `default` before a switch
removal costs two ours-only rows on ALL EIGHT profiles at `checker.ts:38141`, an exhaustive
default-less switch tsc proves and we cannot. **Three of the item's claims are wrong** — its "3 lost
TS2345" rows are the (CHK.63)-adjacent ARGUMENT-reader gap for a BODY-LOCAL source (the TYPE is
already exact at the declaration position), its population is 4 rows not 7, and the join is lost in
`markAssignments`, not in the set pass it names. 13 pins + 2, 5 arms all discriminating, grid
**8 × added=0 removed=0**, `cost_gate.py` exit 0, `huge_methods.py` exit 0, build warning-clean.

**(P18.39) — CALLING A LITERAL-TYPED OR OBJECT-TYPED VALUE IS TS2349 ((CHK.104)), AND THE OBJECT ARM NEEDED TWO GUARDS THE ITEM DID NOT NAME, 18,136 → 18,157 / 0 / 3 (2026-09-07).**
**(CHK.104) CLOSED, 7 → 19 of the reference's 22 rows; (CHK.109) queued.** The primitive arm read
`calleeType is Type.Intrinsic`, i.e. exactly the WIDENED half of the population: `let s = "a"`
reported and `const s = "a"` did not, nor did a number/bigint literal, an annotated literal const or
parameter, a template literal, an `as const`, an enum MEMBER, a body-local or a `never`; the object
arm fired only for a syntactic `new X()`, so a class instance, an interface-typed value, an array
and an object literal's type were all silent. A literal's callability is decided by the same wrapper
its base primitive's is (a wider gate, no new decision); the object half is (CHK.45)'s rule —
positive evidence the member table is complete. **The item under-counted its own population (15
lost rows, not 12) and named NEITHER guard the object arm needs**: a DUPLICATE IDENTIFIER makes the
callee's TYPE not the whole story (the binder's `canMerge` refuses Variable+Function, so this reader
got the VARIABLE's type while the call was checked against the FUNCTION's signature —
`errorElaboration`), and `tryEmitUncallableTypeArgs` OWNS the same row for an explicit-type-argument
call and runs AFTER the spine (`untypedFunctionCallsWithTypeParameters1` printed it twice; the
`--passTiming` emissions-by-pass census named both emitters in one run and the dedupe went into the
pass that runs SECOND). **A pre-existing FORM divergence closed on the way past**: `getApparentType`
covers String/Number/Boolean and not `bigint`/`symbol`, so `sym()` printed `Type 'symbol'` for both
references' `Type 'Symbol'`. Two open decisions were settled by measurement: the heritage refusal is
keyed on `extends` alone (an `implements` clause adds nothing to an instance type), and `never` is
admitted because both references report it and the grid is what licenses it. 21 pins, all read from
pristine; 7 arms, ALL discriminating, plus one arm recorded as NOT ablated with its reason. Grid
**8 × added=0 removed=0** on the final binary, `cost_gate.py` exit 0 (largest delta **+0.03%**),
`huge_methods.py` exit 0, build warning-clean.

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
