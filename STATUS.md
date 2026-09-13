# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **194,425** lines (**+1,992 at (P18.85)**, which is tsc's stable type ordering wired into `getUnionType` plus its display half — a SEMANTIC change, and it also adds an ELEVENTH file, `StableTypeOrdering.kt` (634 lines), a pure comparator with ZERO ambient surface; earlier: **192,433** (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.87) — (LEGACY.0b) STEP 2: THE "FREE WINS", AND F9 WAS A FIRST-DIFFERING-*LINE* LABEL RATHER THAN A FAMILY, 19,100 / 0 / 267 (2026-09-13).**
Pending 285 → **242** and skipped 310 → **267**, both −43 — and that agreement IS the
receipt, since the build fails on a stale ledger entry, so a green suite after deleting 43
entries proves 43 tsgo rows now pass. **F9 "wording" was assigned by each row's FIRST
DIFFERING LINE, which is not a family**: 13 of 53 are wording (in four unrelated mechanisms,
one of them an elaboration TypeScript 7 does not have at all — 0 tsgo baselines against 12
tsc ones), and 40 were reclassified in place, 23 of them into span/width. Two findings beat
the 13: seven of those span rows share F4's anchor mechanism (tsgo anchors unused-local on
the NAME, tsc on the statement), and two duplicate-identifier rows are not a wording swap at
all — tsgo picks the leading message from the error's existing related list where we pick by
index. **F4 landed 26/26** and the sizing was wrong about where it lives: the population is
type PARAMETERS served by two dedicated emitters, and a code-only change closes just 14 —
the span moves to the type-parameter NODE and TS7 keeps one grouping (TS6205 over the whole
list). **F5 landed 4/4 and settles (LEGACY.1)'s wording question**: an option TS7 DELETED
from its table is simply unknown (TS5023, no ladder, so no directive silences it) while
every option it KEEPS but refuses still says TS5102/TS5108 — so (LEGACY.1) step (k)'s plan
is CONFIRMED, and its stated blocker for moving `simulatedVersion` to `"7.0"` is gone (the
four rows it cited are now tsgo baselines saying TS5102, and moving would close two pending
rows rather than redden anything). Ablation 2 / 98 / 6 RED, the 98 including 5 pins failing
in OPPOSITE directions. Grid 8×`added=0 removed=0` with a split verdict — a control for the
display half and for F4 (no profile sets `noUnusedLocals`), a real gate for F5 and the
elaboration. cost_gate all 20 counters +0.00%, huge_methods exit 0, warning-clean.
**(P18.86) — (LEGACY.0b) STEP 1: THE CORPUS READS tsgo's OWN BASELINES, AND A `.diff` CLASSIFIES A *FILE* WHERE A FAILURE CLASSIFIES WHAT *WE* GOT WRONG, 19,082 / 0 / 310 (2026-09-13).**
`cloneTypeScriptGoRepo` pins tsgo at tag `typescript/v7.0.2` and the four baseline lookups
now choose per subtest between tsgo's checked-in output and tsc's, through a three-way
fallback whose four bucket counts are **asserted together** (`adopted=8,765` / `new=23` /
`deleted=9` / `kept-tsc=87`) because a wrong fallback is SILENT — it removes a subtest
rather than failing one. **The guard fired twice and caught an off-by-one**: `new` is 23,
not the design's 24, because (0a)'s pin move already carried
`coAndContraVariantInferences5` a round early. Corpus 8,838 → **8,852**; the red set is
**289** with two controls that make it attributable (all 289 from the tsgo root, all 289
carrying a `.diff` layer), disposed as 268 pending (285 with (0a)'s) + 22 divergences (21
TS-1 + 1 `/.src/`), so the suite is 0 failed with 310 visible skips. **The per-family
ranking moved materially from the design's** (F10 45→4, F9 11→53, F7 43→4) for a reason
worth carrying: a `.diff` classifies a FILE, a failure classifies what WE got wrong. Seven
predictions refuted, the sharpest being that a NEGATIVE diagnostic code is not impossible
here — `checkPreEmitCountMismatchPins` synthesizes `TS-1` deliberately, so the round's first
invariant pin went red against the real binary. Ledgering the 21 TS-1 rows cost 16 real tsc
comparisons, which were **paid back verbatim** in `TsgoHarnessSelfCheckBaselinesTest` (net
coverage change zero). cost_gate all 20 counters +0.00% (no `commonMain` touched, so the
grid is unaffected by construction), huge_methods exit 0, warning-clean. **Left open for a
decision before (0b-3)**: a fourth "harness artifact ⇒ fall back to tsc" arm would preserve
those 17 subtests with no ledger at all.
**(P18.85) — (LEGACY.0a): THE CORPUS IS PINNED TO tsgo's `tsgo-port` SHA, AND tsc's STABLE TYPE ORDERING IS AN *INTERNING* ORDER RATHER THAN A DISPLAY ONE, 19,045 / 0 / 20 (2026-09-13).**
`typeScriptCommit` moves to `4d4f005c` (tsgo 7.0.2's `_submodules/TypeScript`); the corpus
generates 8,838 subtests and the first run read **29 red, exactly the sizing's
22 / 2 / 2 / 2 / 1**. tsc's `compareTypes` (checker.ts:53856) is reproduced in
`StableTypeOrdering.kt` and wired into `getUnionType` — **display-only was measured
insufficient**, because the first-failing chain constituent, a `Pick<A|B,K>` intersection,
the TS2339 sub-line and the suggestion tie-break all read the INTERNAL member list. Two
facts the brief lacked: **TypeScript 7 reordered `TypeFlags`** (so `NEW_BIT` remaps per bit
and `Zeta | void` prints `void | Zeta` on both references), and pristine 6.0.3 under
`--stableTypeOrdering` equals tsgo on 21/21 fixture rows. Twelve reds closed through the
engine, six were hand-written expectations re-measured against tsgo, and **17 survive as a
new `tsgoPendingBaselines` list** — `@Ignore`d, visible, counted, stale-checked, and
deliberately NOT `LogicalParityDivergence` (these are rows to IMPLEMENT, not divergences to
keep, so no `pinnedBy`). The sizing predicted ≤4 residue; 17 survive because most ordering
rows never pass through `typeToString(Type.Union)` at all. Ablation 13 RED (comparator
reverted) / 5 RED (name key dropped). Grid 8×`added=0 removed=0`, marked 18→18, cronstrue
1→1, huge_methods exit 0, warning-clean. **cost_gate REBASELINED with attribution**: the
+2.13% printed against the stale baseline is +1.31% on a rebuilt pristine parent, so this
change's own effect is `typeNode.bypassed` +0.81% — the interning-order change means
`Foo | Bar` and `Bar | Foo` now intern to ONE union. (LEGACY.0) stays open on (0b).
**(P18.84) — (CHK.98) STAGE 2: `Promise.then`, A NAMESPACE-IMPORT CALLEE AND PREDICATE `filter` — TWO INSTANTIATIONS THAT NO-OP'D A UNION-WRAPPED FUNCTION TYPE, AND THE ITEM CLOSES, 19,028 / 0 / 3 (2026-09-12).**
The first round measured against tsgo 7.0.2 ALONE (owner directive the same day). A
callback passed to `then`/`catch` was untyped because the lib parameter is
`((value: T) => …) | undefined | null` and BOTH the generic-member resolver's parameter
branch and `instantiateContextualParamType` no-op'd a union-wrapped function type — the
nullish strip everyone suspected was never the loss (a marker showed the pull receiving
`params=[T]`). A namespace-import callee's pull now falls to `resolveNamespaceQualifiedSymbol`
where the access answered `any`, refusing a lexically shadowed root SYNTACTICALLY (an
`any`-annotated parameter is registered nowhere). Predicate `filter`'s loss was an
inline-arrow leg missing from `predicateTargetTypeOfGuardExpr` — (P18.77)'s "lib `filter`
is a `MethodSignature`" claim refuted — and tsc 5.5's inferred `typeof` predicate landed
with it, closing a pre-existing false positive. `reduce(cb, {} as Record<…>)` is not
contextual typing: `Record<K, V>` resolves to bare `any`, now (CHK.135). Promise family
0/0/12 → 9/0/3, namespace 1/0/4 → 5/0/0, filter 1/0/5 → 6/0/1; the instantiator arm fires
1,159-2,455 times per profile with the grid at 8×0/0 (a GATE, green). Eleven ablation arms,
one found dead on the first pin set and repaired. cost_gate exit 0 (within +0.05% of
rebuilt HEAD), huge_methods exit 0, warning-clean. **(CHK.98) is CHECKED OFF**; the queue's
head is now (LEGACY.0), the owner's corpus re-pin.

**(P18.83) — (CHK.98)(i): THE `NewExpression` ARGUMENT ARM — THE CONSTRUCT SIDE WAS THE CONTROL AND THE CALL SIDE'S FREE-TYPE-PARAMETER RULE WAS THE GATE, 18,986 / 0 / 3 (2026-09-12).**
A callback passed to a constructor now gets its parameter types through the call arm's own
core (`ctxArgTypesFromSignatures`), with tsc's rules for a `new`: a class callee's OWN
constructors first (`MemberResolver` stores construct signatures inherited-FIRST, so an
unfiltered `sigs[0]` was the base's), explicit type arguments as a positional mapper,
overloads adopted by arity, and — for BOTH call-likes — tsc's first-pass answer for a
type parameter no other argument mentions: `default ?: constraint ?: unknown`. "Refuse an
uninferable `T`" was the wrong shape for that case and measurement said so; refusal stays
right only where an argument MENTIONS the parameter and inference fails (the hazard,
pinned twice). **Found and fixed inside the arm**: class constructor parameter symbols are
typed LAZILY under the first asker's scope, so `seed: T` read `any` until the annotations
were resolved under the class's own scope. Census: the `new` arm resolves 4 sites across all
eight profiles (a control); the shared free-TP rule fires 1,031-2,169 times per profile on
the CALL side (the gate) and moved the grid by nothing. `new` set 11/2/45/2 → 41/2/13/4, the
call twins 7/1/7/1 → 10/1/4/1, zero REF-SPLIT. Ablation 28/2/6/2 RED over 45 pins. Grid
8×0/0, libraries byte-identical, cost_gate exit 0 (`typeOfExpr.calls` +0.83% vs rebuilt
HEAD, the (P18.31) cache-hit pattern, not rebaselined), huge_methods exit 0, warning-clean.
(CHK.98) stays open on its stage-2 rows.
