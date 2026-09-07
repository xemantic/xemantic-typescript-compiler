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

**(P18.43) — THE FLOW-JOIN SUBTYPE REDUCTION IS MEMOIZED, AND A 3.2× *WALL* REGRESSION EVERY COUNTER GATE WAS BLIND TO IS CLOSED ((PERF.1)), 18,185 → 18,188 / 0 / 3 (2026-09-07).**
**Warm A/B −57.7% and −58.9%, replicated in two batches** (16,981 → 7,176 ms, 16,584 → 6,817 ms,
`files/errors` 78/46 on every arm); cold CLI 35,893 → 26,740 ms (−25.5%). **The degradation the
bench series has carried since 2026-09-05 is ONE COMMIT, not refactoring drift**: `warm/tsc` is
0.36-0.44× for the rows before `9a49e44c2060` ((CHK.85)(b)) and 1.13-1.56× for all 25 rows since,
with tsc's own time ranging 7.29-13.81 s across the after-rows — and the **native AOT arm regressed
4.8× too**, so it is not a JIT or AOT-cache artifact. **Every deterministic counter is FLAT**
(`spine.nodes` bit-identical, `narrow.walks` +1.2%, and nothing above +4.5% even 25 rounds later),
because the cost is per-ARRIVAL and `cost_gate.py` counts LAUNCHES — the round-735 tail law, with
the (CHK.85)(b) note's own "414 reporting walks" as the count that hid ~90% of narrowing time.
**The mechanism was not the one the diff suggests**: `--narrowSections` reads `narrowByAssignmentRhs`
(the new enum arm's home, and this round's original target) **FLAT at 209.7 → 221.0 ms**, while
`getUnionType at a branch label` goes 368.7 → **20,384.7 ms** on +1.7% calls and `relations(depth0)`
1,235 → **14,512 ms**. (CHK.66)'s subtype reduction runs only when a member is FOREIGN to the
declaration — "free on almost every join" — and (CHK.85)(b)'s enum arm makes a branch answer a
MEMBER (`K.A`) where the declared type is the atomic enum `K`, so a whole class of joins fell onto
the quadratic path. Ablations attribute it exactly: forcing the free path is **−9.9 s with all 46
diagnostics unchanged**, skipping the enum sort is −0.8 s. **Fixed as a MEMO, not a predicate
change, because reading `K.A` as declared would disable the reduction a join genuinely needs**
(`K.A | K` must reduce to `K`); keyed `packIdPair(joined.id, declaredType.id)`, which is exact —
`getUnionType` interns by member-id list and `isTypeAssignableTo` is already id-cached. Post-fix
`relations(depth0)` is 1,226 ms, fully back to the pre-regression 1,235. **Fix (2), bounding the
reporting walk, is REFUSED on this round's own measurement** (the ≥1 ms tail is 306/2,520 ms against
the pre-regression 210/1,360 — the walk was never expensive, only the reduction it triggered was).
3 pins; the poisoned-memo ablation reddens **exactly P2**, the served ask, and one arm is recorded
**BLIND** rather than redundant (`anyForeign`'s early exit returns above the cache probe). Grid
**8 × added=0 removed=0**, `cost_gate.py` exit 0 (all counters within ±0.03%, no rebaseline),
`huge_methods.py` exit 0, build warning-clean.

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
