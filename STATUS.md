# Status

**Inversion shrinkage dashboard ((INV.0) owner metric, 2026-09-02 — update on every core
extraction):** `Checker.kt` **193,576** lines (191,070 when the metric was created; the (P18.9)-(P18.20) checker-parity arc ADDED ~2,400, which are fixes and pins, not extractions — the metric counts extraction progress and this arc made none) (was 191,155 at the metric's creation; +107 of those are
(INV.1)'s store hook and +192 (INV.2)'s companion channels, helpers and lens — ADDITIONS, not extractions;
3 collaborators extracted: `TypeInterner`, `Relation`+`Ternary` — ambient surface none
for both — and `TypeInstantiator`, whose ambient row is the first non-none one: three
checker reads, one table write, stated in the ledger). Reference points:
tsc ≈ 50k lines (one file), tsgo 60,479 across 25 files. Contract:
`docs/INVERSION-DESIGN.md` § 10; ledger: `docs/inversion-ambient-ledger.md`.

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

**(P18.18) — AN ENUM STOPS OVERLAPPING A LITERAL IT CANNOT HOLD, AN IMPOSSIBLE COMPARISON'S BRANCH BECOMES `never`, AND A PRIMITIVE ARGUMENT STOPS BEING INVISIBLE TO A COMPOSITE PARAMETER, 17,343 → 17,369 / 0 / 3 predicted (2026-09-05).**
Three of four items landed; every row reproduced against tsgo 7.0.2 AND pristine 6.0.3 before
any code was written, and the two references agree on all of them.
**(CHK.88)** — `ka === 1` with `ka: K.A` is `TS2367 … 'K.A' and '1'` in both references and was
silent here; the verdict needs the member VALUES (round 745's `enumKnownDomainValues`, whose
refusal for a *computed* enum is what keeps `declare enum` and a non-foldable member accepting
every literal), and the literal must be read off the AST because `getTypeOfExpression` answers the
base primitive for a literal node. **(CHK.87)** — the sibling NARROW, decided by (CHK.86)'s own
predicate so the branch it collapses is exactly the branch that reports; it DELETES rows, and
`typeOfExpr.calls` reads −0.22%. **(CHK.83)** — FOUR of five parameter kinds (ARRAY/tuple,
FUNCTION, ENUM, UNION); the licence is the DECLARATION position, which admits the same family and
matches both references row for row. **THREE of its four guards were found by a GATE, not by
reading**: a REST parameter (301-401 added rows per profile — the grid), an ARITY-mismatched call
(`couldNotSelectGenericOverload` — the corpus), and an OVERLOAD-SET parameter already owned by the
dedicated `checkRecursiveFunctionTypes` walker (`recursiveFunctionTypes` — the corpus, attributed
by `--passTiming`'s emissions census). INTERSECTION is refused with its measurement ((CHK.55)'s
law: overload selection keeps a branded-`Path` signature, so opening the gate reported an
ours-only row).
**(CHK.85) STOPPED A SECOND TIME, now with the blast radius its entry asked for — (a) was BUILT
and costs MEANING: +7 rows on every profile and +22 on harness, ALL discriminated-union selection
losses.** The widening RULE is right (its contextual keep is tsc's `isLiteralOfContextualType`);
what is missing is the CONTEXT reaching `getTypeOfObjectLiteral`. Two corrections: (a) is a lost
true positive AND an ADDED false positive (`o.v = K.B` is an ours-only TS2322), and the widening
is missing for ENUMS ONLY because a literal node already types as its base primitive; (c) is not
an enum question at all — `as const` is unmodelled for strings and arrays too, i.e. a FEATURE.
26 pins, **twelve arms** (7 / 1 / 1 / 1 / 2 / 2 / 5 / 1 / 1 / 1 / 1 / 1 RED, one nested and
recorded as such). Gates: whole core module **839 classes / 15,880 / 0** (a superset of the
682-file fixture-selected sweep, all 681 test classes confirmed present — 682 `--tests` patterns
were measured to take >20 min and produce no XML); generated corpus **25 classes / 8,837 / 0**;
externals 290/0; project 848/0; kir 159/0; lsp 58/0; `cost_gate.py` exit 0 (`output.errors` 46
unchanged, largest move `mapped.hits` +1.22%); `huge_methods.py` exit 0; 8-profile grid all
`added=0 removed=0` — the REAL gate here, not a control, since all three items move diagnostics.
Queued: (CHK.89) a return-position enum-member annotation losing its qualifier (`'A'` for
`'K.A'`, pre-existing), (CHK.90) the TS2367 CATEGORY rule missing `getBaseTypesIfUnrelated`, and
(CHK.83)'s own remainder.

**(P18.17) — `never` STOPS BEING UNASSIGNABLE AT ONE POSITION, AN IMPOSSIBLE ENUM COMPARISON STARTS REPORTING, AND A GENERALIZED ENUM PRINTS ITS NAMESPACE, 17,308 → 17,337 / 1 / 3 then 17,343 / 0 / 3 predicted (2026-09-05).**
Three of (P18.16)'s four residues closed, each reproduced against tsgo 7.0.2 AND pristine 6.0.3
before any code was written (the two references agree on every row; no divergence found).
**(CHK.84)** — the string-layer `isAssignableTo` had no BOTTOM-type rule, and only the RETURN
position could show it, because of that function's five call sites only that one adds an
identifier fallback below `inferSimpleExprType` (and the engine cannot fire, since it correctly
ACCEPTS a `never` source and an accepted relation does not early-return). **(CHK.86)**, the
diagnostic half — `comparabilityCategory` maps every numeric enum to `"number"`, so two enums of
one flavour read as the same category; the identity question is now asked separately and above
it, with tsc's `getBaseTypesIfUnrelated` deciding whether the members or their enums are printed.
**(PARITY.3)** — tsc computes the source display TWICE and the difference is the defect: a
GENERALIZED source is re-rendered fully qualified, which is why the same `Ns.Inner.I` prints
qualified at a `string` target and bare at a `never` one; scoped to a NAMESPACE chain, with an
ambient-module guard keeping (P18.14)'s refused `import("<path>")` form out by name and a
global-augmentation guard STOPPING the walk at `declare global` (which is not a container a
consumer can spell) while keeping any real namespace nested inside it.
**(CHK.85) STOPPED with its measurement — it is MEANING, not display**: a mutable object-literal
property must widen (`const o = { v: K.A }; const w: K.A = o.v` errors in both references and is
SILENT here — a lost true positive), a `let`'s read must answer the FLOW type, and an `as const`
property read is missing entirely; the object-literal half lives in `getTypeOfObjectLiteral`, i.e.
every literal's member types program-wide, so it is a design. Requeued: (CHK.85), plus (CHK.87)
the `never` NARROW half of (CHK.86) (a separate mechanism — `narrowByEquality` decides from the
other operand's SYNTAX and bails before any enum question) and (CHK.88) the enum-member-vs-numeric-
literal sibling (needs member VALUES, not enum identity). 35 pins, eight arms
(2 / 3 / 6 / 10 / 2 / 1 / 4 / 1 RED, one nested and recorded as such), one (P18.16) expectation
updated to the references' answer. **The corpus half of the at-risk enumeration was sound and the
HAND-WRITTEN half was not**: it selected classes by NAME, which cannot see a fixture, so
`DeclareGlobalAugmentationTest` — an enum declared inside `declare global` — went RED in the full
suite; censused, 488 core classes mention `enum`/`never` in their SOURCE and the name patterns
reached 149, missing 339. Re-run (PARITY.2)-style by FIXTURE: **485 classes / 5,169 tests / 0**.
Corpus at-risk 360 families / 1,481 subtests / **0 moved**; externals 290/0; project 848/0;
`cost_gate.py` exit 0 (`output.errors` 46 unchanged); `huge_methods.py` exit 0; 8-profile grid all
`added=0 removed=0`.

**(P18.16) — THE ENUM DISPLAY GENERALIZATION LANDS, AND ITS BLINDED PINS BECOME tsc-VERIFIABLE, 17,286 → 17,308 / 0 / 3 predicted (2026-09-04).**
(PARITY.2) closed. tsc's `getBaseTypeOfEnumLikeType` is wired, so an enum-member source now
generalizes to its parent enum at every target that cannot hold a top-level singleton, at all
six emitters plus declaration and assignment — byte-identical to tsgo 7.0.2 AND pristine 6.0.3
on every shape but two pre-existing ones (a namespace prefix, and an interned member union's
alias name). **The round is the conversion, and the population had to be re-derived to find
it**: the queue's grep-derived list said 13 classes, a mechanical run of all 170 core classes
whose fixtures declare an enum says 14 / 45, and the extra one expects a type ALIAS name that
no grep for a member rendering can see. Every converted probe moved to a `never` target — the
one target tsc suppresses the generalization for — which makes 45 (REL.2) narrowing assertions
verifiable against both references for the first time; three pins REFUSED conversion with their
reasons (the round-441 `never`-parameter arm discards a non-enum narrow; a `let`'s flow type
diverges) and two `string`-target twins were re-expected to the generalized enum rather than
moved. Corpus predicted from an enumeration of all 3,145 active baselines (135 declare an enum,
2 name a member in an assignability source, both at suppressing targets): 715 at-risk subtests
run, **0 moved, no `LogicalParityDivergence`**. Five ablation arms (20 / 3 / 73 / 9 / 0 RED),
the last a measured-redundant guard kept with its reason. Four residues queued: (CHK.84)
`never` unassignable at a RETURN position and nowhere else, (CHK.85) a mutable object-literal
property and a `let` not widening an enum member the way tsc does, (CHK.86) no TS2367 for an
impossible enum-vs-enum equality, (PARITY.3) the lost namespace prefix.
