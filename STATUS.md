# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **194,650** lines (191,070 when the metric was created; the (P18.9)-(P18.23) checker-parity arc ADDED ~2,400, which are fixes and pins, not extractions — the metric counts extraction progress and this arc made none) (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: three
checker reads, one table write, stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

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

**(P18.20) — AN ENUM MEMBER STOPS RELATING TO A LITERAL IT DOES NOT EQUAL, A LITERAL ARGUMENT STOPS BEING INVISIBLE TO AN ENUM PARAMETER, AND THE (CHK.85) UNBLOCKER IS DESIGNED, 17,394 → 17,424 / 0 / 3 (2026-09-05).**
An orchestrated round: one implementation subagent owned Gradle; a read-only recon subagent
worked against a frozen snapshot of the HEAD binary. **(CHK.83) CLOSED.** `const l1: 5 = em` was
silent at every position because `isSimpleTypeRelatedTo`'s `EnumLiteral` leg accepted a member
against ANY number literal (`NumberLike ⊇ NumberLiteral`); it now relates by VALUE through the
tsc-value view, so an ambient opaque member relates to no literal. `fEnum(3)` at an argument, a
rest element, a return, an arrow body, the overload chain and object-literal members now reports
as both references do; the enum-union display puts `null`/`undefined` last. The overload-set
unification is STOPPED: `checkRecursiveFunctionTypes` pins four mechanisms, not one. **(CHK.91)
DESIGNED** — the (CHK.85)(a) unblocker: a FRESHNESS gate (tsc widens only an enum-member ACCESS
expression; the built arm widened `{ v: a }`, `{ v: k }` and shorthands too) plus a PULL-derived
contextual keep mirroring tsc's `getContextualType` roots, decided by `isLiteralOfContextualType`'s
SOME rule; the eleven losses are exactly the union-annotated declaration / nested / conditional
return / arrow body / assignment positions, where an emitter fires and the push field never
arrives. 30 pins, twelve arms all discriminating, one redundant guard retired by its own arm;
core 15,935/0, corpus 8,837/0, `cost_gate.py` exit 0, `huge_methods.py` exit 0, grid 8×`added=0
removed=0`. Residues queued as (CHK.92).

**(P18.19) — AN ENUM MEMBER KEEPS ITS ENUM AT A RETURN, THE TS2367 CATEGORY RULE TAKES ITS BASES, AND A STRING ENUM STOPS BEING A NUMBER, 17,369 → 17,394 / 0 / 3 predicted (2026-09-05).**
Two items landed whole and the third partly; every row reproduced against tsgo 7.0.2 AND
pristine 6.0.3 before any code was written, and **one reference DIVERGENCE was found and
decided a design**.
**(CHK.89)** — `function f(): K.A` read `type 'A'` against both references' `'K.A'`: the string
layer's base name is a `QualifiedName`'s LAST name, and it feeds both renderers. The qualifier is
an ENUM-MEMBER rule and nothing else (a namespaced INTERFACE prints `'I'` and a whole namespaced
enum `'Q'` in both references), and its one non-obvious guard came from the CORPUS —
`SymbolFlags.Enum` is `RegularEnum or ConstEnum` and the binder cascades `ConstEnum` onto a
`ConstEnumOnly` NAMESPACE, so a flag test rendered `Const.E` and silently disarmed the
rounds-745-749 same-string retry (`enumAssignmentCompat3`).
**(CHK.90)** — both halves: the category rule now takes tsc's `getBaseTypesIfUnrelated` base (the
widened pair is unrelated by CONSTRUCTION there, which is also why the (CHK.88) value rule beside
it must keep the ORIGINALS), and an ALL-STRING enum's OWN type answers the `"string"` category
where its MEMBER already did — the two halves of one enum disagreeing about their own flavour,
which is the silence at `s2 === 3` / `s2 === true`. The fixture is now byte-identical to both
references on all ten rows.
**(CHK.83)** — the `readonly` array PARAMETER landed (the ARRAY kind already admitted, missed
because `isArrayLikeType`'s `Type.Reference` leg names `"Array"` alone) and the ELABORATION
sub-line landed at all five union-source chain sites. **The PICKER is deliberately untouched: with
`"a" | 1` against `number[]`, tsgo names the FIRST constituent and pristine the LAST** — pristine is
the corpus's oracle, so only the rendering moved. INTERSECTION re-measured and still refused (its
single ours-only row reproduces at `harness/.../vpathUtil.ts:106:30`, and it additionally needs the
source-display allowlist); the generic `Type.Reference` family newly refused because its LICENCE
fails — `const d: ArrayLike<string> = s` and `Iterable<string>` are ours-only TS2322 at the
DECLARATION position.
25 pins, six arms (5/4/2/7/2/2 RED, one nested pair recorded), one provably-dead line removed with
its proof. Gates: core **840 / 15,905 / 0 / 3**, generated corpus **25 classes / 8,837 / 0**, the
seven other modules 0, `cost_gate.py` exit 0 with `output.errors` 46 and this round adding nothing,
`huge_methods.py --fail-over 0` exit 0, and the 8-profile grid **added=0 removed=0 everywhere** —
the real gate here, not a control.
