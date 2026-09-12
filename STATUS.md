# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **192,433** lines (**−8,100 across (P18.53)-(P18.66)**; steps 10a-10d and 10b-iii are SEMANTIC changes and ADD 85, 86, 14, 33 and 139, not extractions, and the (CHK.\*) parity rounds since — (P18.68)/(P18.70)/(P18.71) — add a further ~446 for the same reason; 191,070 when
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

**(P18.80) — (CHK.134)(1): `f.call` / `f.apply` TYPED FROM THE RECEIVER'S OWN SIGNATURE — NO INFERENCE WAS NEEDED, AND THE GRID'S ONE ROW PER PROFILE WAS A MISSING OPTION, 18,854 / 0 / 3 (2026-09-12).**
tsc's `getPropertyOfType` miss augmentation now serves a function-shaped receiver: `call`
and `apply` are BUILT from the receiver's last signature (`CallableFunction`'s `T`/`A`/`R`
each have exactly one candidate, so nothing is inferred; `call` expands the parameters as
tsc's messages count them, `apply` is built per call from the argument count), plus the
`Function` members. **The grid read +1 row on every profile and the cause was an option this
compiler did not have**: tsc's own sources set `"strictBindCallApply": false` explicitly,
and `utilities.ts:11201`'s `stringReplace.call(s, "*", replacement)` is exactly tsc's
strict answer; `strictBindCallApply` now exists (flag if set, else `strict`) and the grid is
8×`added=0 removed=0` — it gates the OPTION, marked (strict, 14 sites resolved, 18 → 18)
exercised the synthesis. Every `call`/`apply` shape across 55 fixtures `missing → agree`,
zero ours-only; the parser had been DROPPING tuple labels (`TupleType.elementNames`) and
tsc's optional-tuple display landed with it. Ablation 31/4/15/9/2 RED over 45 pins, a5
reddening the compiler profile to 47 rows. cost_gate exit 0 (18/20 digit-identical;
`globals.lookups` +19 is the `Function` consult), huge_methods exit 0, warning-clean.
(CHK.134) stays open on `bind`.

**(P18.79) — (CHK.133)(b): THE RELATION'S `this` LEG — ONE PREDICATE, THREE ELABORATION SITES, AND A BIVARIANCE RULE THAT WAS AN UNDER-APPROXIMATION, 18,809 / 0 / 3 (2026-09-12).**
`Relater.signatureThisTypesRelated` is tsc's `compareSignaturesRelated` `this` leg (a source
`this` other than `void` must relate to the target's; contravariant, or either direction
where `strictVariance` is off), consulted by the verdict AND by all three elaboration sites
so the chain line cannot contradict it — and `checkPropertyAccessAssignment` had no callable
elaboration at all. **The existing `bivariantParams` ("both sides MethodDeclaration") is an
under-approximation of tsc's TARGET-kind rule and reused verbatim was a false positive** on
a function value into a method-signature member; the `this` leg takes the target's kind, the
parameter leg is untouched. Census: 2,639-3,110 reached per profile, ALL a signature related
to ITSELF (the lib's type-parameter `this`), zero refusals, zero real comparisons of two
different concrete `this` types anywhere — the queue's "likely a REAL gate" refuted, grid a
CONTROL. Fourteen fixture families `missing → agree`, zero REF-SPLIT, every emitting row
byte-identical to pristine including chain nesting; `looseThisTypeInFunctions:21` byte-
identical. Ablation 17/5/1/16 RED over 28 pins. cost_gate exit 0 with all 20 counters
digit-identical to the rebuilt HEAD, huge_methods exit 0, warning-clean. **(CHK.133) is
CHECKED OFF**; `.call/.apply/.bind` sized read-only as a NEW mechanism (member-miss
augmentation with `CallableFunction` + inference through a `this`-typed lib signature;
81/18/12 sites on the compiler profile, so a REAL gate) and queued as **(CHK.134)**.

**(P18.78) — (CHK.133)(a)+(c): `Signature.thisType` AND THE CALL-SITE TS2684 — "A PURE MODEL CHANGE" MOVED FIVE ROWS BEFORE ANY CONSUMER EXISTED, AND (CHK.97) CLOSES, 18,781 / 0 / 3 (2026-09-12).**
`Signature` now carries its `this:` pseudo-parameter (`thisType`), threaded through all 28
`Signature(` constructions, all four instantiators and `MemberResolver`; the model ALONE
closed a false TS2345 on every call of an interface/class method declared with `this` (the
parameter zip) and made `unionTypeCallSignatures5/6` byte-identical to pristine (the union
arm INTERSECTS members' `this` types, deduped by identity because `getIntersectionType`'s
anonymous-object exemption printed `B & B`). The call-site consumer (tsc's
`getSignatureApplicabilityError` `this` leg → TS2684 with a one-level chain) landed in the
same commit: **387-533 declared `this` parameters per profile and ZERO call sites reaching
the check** on every profile and library, so the grid is a GATE for the model and a CONTROL
for the emission. A display defect the sizing missed (`ZzzBox<T>` for `ZzzBox<number>`) is
fixed and gated by the 18 active `this:` baselines. Ablation over 29 pins: 19/1/1/2/15/8/1/2/
1/1 RED across nine arms. cost_gate exit 0 with `typeNode.bypassed` +0.49% (the declared
`this` resolutions, bounded by the census; not rebaselined), huge_methods exit 0 (845),
warning-clean. **(CHK.97) is CHECKED OFF** — every deliverable closed or rejected on a
measurement across (P18.71)-(P18.78). (CHK.133) stays open on (b) the relation's `this`
leg and the `.call/.apply/.bind` consumer.

**(P18.77) — (CHK.97) D5: INFERENCE THROUGH A UNION-COMBINED SIGNATURE WAS BAILING ON AN INTERSECTION IT COULD NOT SEE, AND THE "ONE ROW" WAS SEVEN FAMILIES, 18,752 / 0 / 3 (2026-09-12).**
PASS 2 combines `(number[] | string[]).map` into `<U>(cb: ((v: number…) => U) & ((v: string…)
=> U)): U[]`, and every callback arm of the single-type-parameter inference demanded an
anonymous `Type.Object` — so the inference bailed whole and the call answered a raw `U[]`
that every reader silently refused as a foreign type parameter. **The first reading of the
mechanism was wrong and a stderr line settled it** (the gate PASSES, because a function
object's signatures are invisible to `typeMentionsTypeParam`; the candidate gatherer is what
finds nothing). Fix is a VIEW, not machinery: `inferenceParamType` presents such an
intersection as one anonymous function type carrying the existing `getIntersectedSignatures`
fold, memoized per intersection id. Four fixture families `missing → agree`, the PASS-2
pair fires (with a (CHK.132) sub-line), zero new ours-only rows. Ablation 12/4/1/0 RED over
67 pins — a4 a recorded redundant guard. **Two `TupleArrayMembersTest` pins were countdowns
on exactly this silence** and a `reduce` control was written wrong; all three found by the
68-class neighbour sweep, none by the fixtures. Grid 8×0/0 and both library arms are
measured CONTROLS (census `bound=0` everywhere real). cost_gate exit 0 (digit-identical to
pristine), huge_methods exit 0, warning-clean. (CHK.97) is open on D6 ALONE; its unblocker
`Signature.thisParameter` is now **(CHK.133)** at the top of the queue.

**(P18.76) — (CHK.97) D3, THE DIFFERING HALF: TS7006 THROUGH A UNION CONTEXTUAL TYPE, AND THE ARITY FILTER THE IDENTICAL HALF HAD LEFT OUT, 18,738 / 0 / 3 (2026-09-12).**
A union contextual type whose member signatures are NOT identical now leaves the arrow's
parameter implicitly `any` and reports TS7006/TS7031 through the EXISTING owner
(`checkParamsForImplicitAny`), as both references do. **The reach census ran BEFORE any
emission and read ZERO on all eight profiles, marked, cronstrue and the 2,400-file project**,
so the grid and library arms are measured CONTROLS — and it also showed (P18.75)'s
overloaded-member refusal firing 6× on one fixture, i.e. the emission was UNSOUND without
tsc's `getContextualCallSignature` arity filter, which was closed in the same sub-step
(`callableSignaturesForCtx(requiredParamCount)`, `signatureArityBelow`, several applicable
overloads folded through `getIntersectedSignatures`). Before → after: differing types/arity/
optionality `missing → agree`, the overloaded family 0/0/3 → 3/0/0, a new 16-file family
2/0/18 → 17/0/3, zero NEW ours-only rows. Five of (P18.75)'s own negative controls were
COUNTDOWNS and now assert the row. Ablation 16/3/15 RED over 39 pins; a `sig!!` on an
unresolvable callee passed every D3 fixture and was caught only by the 63-class neighbour
sweep (24 RED). cost_gate exit 0 (counters digit-identical to the pristine binary),
huge_methods exit 0, warning-clean. (CHK.97) stays open: D5, D6.
