# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **197,743** lines (191,070 when the metric was created; the (P18.9)-(P18.35) checker-parity arc ADDED ~5,200, which are fixes and pins, not extractions — the metric counts extraction progress and this arc made none) (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: FOUR
checker reads (the fourth, `instantiateTupleElements`, added by (P18.28)), one table write,
stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

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

**(P18.33) — AN EXPORTED DESTRUCTURING IS AN EXPORT ((CHK.99)), AND FOUR OF THE ITEM'S SIX SITES WERE WRONG, 17,981 → 18,021 / 0 / 3 (2026-09-06).**
**(CHK.99) CLOSED.** `bindingPatternNames` — the checker-side mirror of
`Binder.bindVariableDeclarationName`, i.e. tsc's rule that every leaf of an exported pattern is an
export — at four name-set sites. An `Identifier` answers itself, so it is a DROP-IN, which is the
arithmetic reason `cost_gate.py` reads **+0.00% on every counter**. The item's fixture goes 26 → 16
rows against 17 in both references: ten false TS2305 and a false TS2339 on `typeof NS` gone, and a
barrel import GAINED a true TS2345 it had been losing. **Four of the item's six sites were wrong**:
the `typeof NS` line it names is a different walker's set (the real site it never names), the
`nsImportTargets` site is an unrelated decl-emit walker, **`varDecls` must NOT be changed** — its
consumer reads `d.type`, so a registered leaf mistypes an ANNOTATED exported pattern, proven by an
arm and inert on every non-collision fixture (the first guard pin was blind and had to spell the
collision out) — and "`import * as A; A.p` is `any`" is a general namespace-import gap equally true
of a plain `export const`, so it cannot discriminate the fix. **Two lost diagnostics the item did
not mention also close**: TS2308 and TS2484. The harness split is the point: population is **0 on
all eight profiles** (re-derived here, so the grid is a CONTROL rather than coverage) and the class
is structurally invisible to the corpus, so the 40 pins run across THREE harnesses — 11 direct
`Parser`, 12 `diagnose()`, and 17 in `-project` through `ProjectCompiler` + a `Vfs` for the
cross-file half — with every positive pin a VALUE pin. 9 arms all discriminating; 8 controls
recorded as non-discriminating rather than counted. Corpus 8,837/0, `-project` 848 → 865/0,
`huge_methods.py` exit 0, grid 8×`added=0 removed=0`.

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
