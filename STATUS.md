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

**(P18.82) — (CHK.98)(d): TS2556 FOR A NON-TUPLE SPREAD WAS WRONG IN BOTH DIRECTIONS, NOT MISSING — AND EVERY REMAINING (CHK.98) DELIVERABLE IS NOW MEASURED, 18,941 / 0 / 3 (2026-09-12).**
All four remaining deliverables were measured against both references before one was
picked: the `NewExpression` argument arm (21 missing, a control grid), TS2556 for a
non-tuple spread (**6 ours-only — four false positives on legal code and two wrong codes —
plus 20 missing**, a REAL gate at 45-46 arity verdicts per profile), the item's stage 2 (≥5
mechanisms) and (CHK.98b) (already closed). The spread one landed: tsc's tuple expansion and
`hasCorrectArity` spread clause at all three arity walkers, 6/6/20 → 22/0/4 with every
remaining row attributed to a pre-existing gap reproduced without a spread; 8 of 9 inactive
pristine TS2556 baselines match exactly. **The hazard the item did not name: the arity
walkers run under the FILE-LEVEL ambient**, so a resolver-classified operand produced a
false TS2556 on a body-local tuple shadowing a file-level array — operands are now
classified declaration-first, and that shape is the hazard pin. Ablation 21/2/7/6/5 RED over
134 pins; three countdown pins inverted. Grid 8×`added=0 removed=0` as a measured GATE,
marked/cronstrue byte-identical, cost_gate exit 0 (20/20 within +0.05% of rebuilt HEAD),
huge_methods exit 0, warning-clean. (CHK.98) stays open on the `new` arm and stage 2.

**(P18.81) — (CHK.134)(2): `f.bind` — THE BUILD SHAPE SUFFICED, THE LIB HAS TWO OVERLOADS NOT FIVE, AND A RE-BOUND FUNCTION'S `any` WAS THE ARITH RECORDER'S FIRST-TOUCH HAZARD, 18,907 / 0 / 3 (2026-09-12).**
`Checker.bindType` builds `bind`'s member per call from the receiver alone — `OmitThisParameter`
is the receiver itself when its `this` is absent/`unknown`/`any` (overloads and type
parameters KEPT, measured) else the erased last signature minus `this`; the variadic overload
splits the parameter list at the partial count — with no conditional type touched, and
`NewableFunction.bind` came free. The queue item's `A0..A3` quartet does not exist in any of
the three libs. **One signature wherever one decides the call**: handing the lib's PAIR over
typed a re-bound function `any`, so the pair is built only when overload 1 refuses — which
is exactly pristine's per-candidate TS2769 chain. Census: 5-24 `bind` sites per profile, ALL
refused as non-strict, 0 on every library, so the grid and libraries are controls. 52
fixtures: 60 agree / 1 ours-only / 14 missing, every missing row attributed to a
pre-existing general gap reproduced WITHOUT `bind` (a variable callee's TS2554, and
`spineArithRecordVarDecl`'s first-touch under an `any`-reading ambient — now a CLAUDE.md
gotcha). Ablation 43/26/6/8 RED over 127 pins. cost_gate exit 0 with 20/20 counters
digit-identical to the rebuilt HEAD, huge_methods exit 0, warning-clean. **(CHK.134) is
CHECKED OFF**; next is (CHK.98).
