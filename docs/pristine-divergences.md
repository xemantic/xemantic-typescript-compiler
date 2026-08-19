# Where this compiler still diverges from PRISTINE tsc

**Instrument:** `scripts/pristine_sweep.py` — our binary run over pristine tsc's OWN
inputs, differenced `(file, line, code)` against pristine's OWN `.errors.txt`.
**Classifier:** `scripts/pristine_triage.py` — the cause-class rules below, in one
reviewable place, so a next round re-runs them against a fresh sweep instead of
re-deriving ~150 `(fixture, code)` groups by hand.

**Population:** 630 fixture stems carrying a computed member key (the ERE is in the
script), 611 with recoverable source. Round 940 chose it; round 941 keeps it unchanged so
the counts stay comparable.

**OURS-ONLY is the number that matters**: a diagnostic WE emit and pristine does not,
i.e. a candidate false positive. PRISTINE-ONLY is dominated by checks this compiler does
not implement and is reported for context only.

---

## 0. The instrument was wrong about 30% of its own rows

Round 941's first product is a correction. Round 940 read **397** ours-only rows; **121 of
them (30.5%) were the sweep's own configuration**, not the compiler's answers. Three
separate defects, each failing in the reassuring direction — a phantom divergence looks
exactly like a real one:

| # | defect | how it lied | size |
|---|---|---|---|
| (a) | `extract_sources` fell back to `tests/cases` whenever no EXACT `<stem>.js` baseline existed — which is every multi-variation case (`<stem>(target=es2015).js`) | the case file still carries the `// @target:` harness directives that tsc STRIPS, so every line number was the baseline's **plus the directive count**, and every row read as a divergence in BOTH directions | 27 of 630 fixtures; `commonMissingSemicolons` alone contributed **42** phantom rows and `classUsedBeforeInitializedVariables` **6** |
| (b) | directives were read from the EXTRACTED text | the `.js` baseline echoes the source verbatim **but strips the directive comments**, so a fixture whose source came from a baseline recovered ZERO directives | `decoratorsOnComputedProperties` read **10** phantom TS1166 (no `@experimentalDecorators`); `jsxElementType` **46 → 22** (no `@jsx`) |
| (c) | a missing case file meant no directives at all | the baseline's own VARIATION SUFFIX is the last surviving record of the option it was compiled under | `derivedClassSuperProperties` compiled at the esnext default, where tsc's TS2376 rule is switched off entirely |

Fixed by: preferring a baseline source over the case file and stripping harness directives
from the case fallback (`pristine_oracle._strip_harness_directives`); reading directives
from `tests/cases` and passing them into the scratch `tsconfig` VERBATIM (`TsConfigLoader`
routes every `compilerOptions` key through the same `applyDirective` the corpus harness
uses, so nothing has to be re-derived in Python); and recovering `(key=value)` from the
baseline variation name when the case file is absent.

**And an ALIGNMENT ORACLE now guards (a) permanently**: each fixture's reconstructed input
is compared line-for-line against pristine's own `==== file ====` annotation, and the
verdict (`aligned` / `misaligned` / `unknown`) is recorded per fixture. One fixture is
`misaligned` today (`classMemberWithMissingIdentifier2`); every other row in this table
has been read against a source pristine itself would recognise.

Round 940 also forced `"strict": false` on every fixture. Round 941 honours the fixture's
own `@strict`, which is what surfaced bucket 2 below (**+97 rows**).

**Net: 397 (round 940 instrument) → 373 (round 941 instrument), at the same commit.**
The two numbers are not comparable row-for-row; only same-instrument arms are.

---

## 0b. A DIAGNOSTIC ARM for the strict-family convention — and the guard it needed

`pristine_sweep.py --tsc-strict-default` injects `strict: false` into a fixture that names
no strict-family directive, i.e. reproduces **tsc's own default**, which this compiler
deliberately inverts ((CHK.13): every strict-family check fires unless `strict: false` is
EXPLICIT). Differencing that arm against the canonical run says how many ours-only rows are
the CONVENTION rather than a modelling gap. Round 943 measured **318 → 272: 47 rows removed
and 1 added**.

**It needed a guard, for round 941's defect (c) one directive over.** The first run injected
the default wherever the directives were silent — but **an absent directive is evidence only
when the case file is in this clone**; a MISSING case file is not evidence of anything.
`strictPropertyInitialization` has no case file here and **its own baseline carries 20
TS2564**, so pristine plainly had the flag ON, and the unguarded arm deleted four GENUINE
false positives ((CHK.10)'s) from the count. The arm now injects only where
`po.case_index()` has the fixture.

**And the reading needs a second control even then**, because every one of the 47 belongs to
a fixture with no case file: *does pristine's own baseline carry that code ANYWHERE in the
fixture?* For 19 of the 21 fixtures the answer is zero — with `keyofAndIndexedAccess`'s
seventeen uninitialised class fields that is conclusive — while
`strictPropertyInitialization` (7) and `conditionalTypes1` (15) say the opposite and are
excluded. **Net: the convention is 46 rows, not 42** — the four extra are TS2683
(`noImplicitThis`), TS7019 (`noImplicitAny`) and a `strictNullChecks` TS2322, codes the
top-level classifier cannot recognise. **(CHK.10) is CONFIRMED genuine** by the same test.

---

## 1. The table — 318 ours-only rows over 79 fixtures, at round 942 (**313 over 78** after round 944)

The row counts are AT `967c2e53` (round 941's own before-arm) so the buckets stay
comparable; the right-hand column records what each has cost since.

| rows | % | bucket | cause class | exemplar | status |
|---:|---:|---|---|---|---|
| 89 | 23.9 | FP — type system / inference | **83 genuine FP · 6 convention** | `variadicTuples1` TS2322 ×15 + TS2345 ×14 | **SUB-TRIAGED round 943** — 68 of the 83 are MODELLING; 2 fixed |
| 59 | 15.8 | HARNESS — jsx configuration | **harness artefact** | `tsxLibraryManagedAttributes` TS2874 ×27 | not yet measured |
| 59 | 15.8 | PARSER GAP — unsupported syntax | **cascade** (from a parse failure) | `usingDeclarations*` (4 fixtures, 33 rows), `infer X extends` (17) | open, (CHK.14) |
| 42 | 11.3 | CONVENTION — strict-by-default | **deliberate divergence** | `keyofAndIndexedAccess` TS2564 ×17 | owner decision, (CHK.13) |
| 31 | 8.3 | PARSER RECOVERY on a malformed fixture | **cascade** | `mappedTypeProperties` (23 rows) | frozen subsystem |
| 27 | 7.2 | FP — computed keys / declaration emit | **genuine FP** | `indexSignatures1` TS1268 ×12 | (CHK.9) CLOSED round 945; (CHK.10) open |
| 27 | 7.2 | FP — narrowing / control flow | **genuine FP** | `typeGuardNarrowsIndexedAccessOfKnownProperty1` (11 rows) | **16 of 27 FIXED round 942** |
| 26 | 7.0 | **FIXED round 941** — private-identifier target gate | **genuine FP** | `strictPropertyInitialization` TS18028 ×16 | closed |
| 13 | 3.5 | **FIXED round 941** — super-call statement scan | **genuine FP** | `derivedClassSuperProperties` TS2376 ×13 | closed |

**Cause-class totals: genuine FP 182 (48.8%) · cascade 90 (24.1%) · harness artefact 59
(15.8%) · deliberate convention 42 (11.3%).** 39 of the 182 were closed by round 941 and a
further 16 by round 942, leaving **318** rows over **79** fixtures.

**No ACTIVE-baseline row appears anywhere in the table.** The population is by construction
the fixtures the generated suite does NOT gate: a row here is a shape whose baseline is
either absent (pristine was silent) or not generated, which is exactly why the corpus is
green while these rows exist.

---

## 2. Bucket by bucket

### 2.1 FP — type system / inference (89 rows, 38 groups) — SUB-TRIAGED, round 943

Round 941 filed 89 rows under one placeholder label. Every row has now been re-verified
against pristine's own answer and read against the fixture's own source; the rules are
`scripts/pristine_triage.py`'s `SUB_BUCKETS` (so the split is re-runnable against a fresh
sweep, not re-derived by hand) and the summary is:

| # | sub-family | rows | mechanism | cause class | tractability |
|---|---|---:|---|---|---|
| S1 | variadic tuple types | 30 | `getTupleType` types a `RestType` element as a PLAIN element, so **`[...T]` IS `[T]`** | genuine FP | **MODELLING** |
| S3 | contextual typing through a mapped / conditional type | 14 | a callback parameter gets no contextual type, so TS7006 / TS2345 | genuine FP | **MODELLING** |
| S2 | recursive conditional / mapped types over tuples | 13 | the instantiation-depth bail (TS2589) and a deferred conditional that never evaluates | genuine FP | **MODELLING** |
| S10 | residue — one mechanism each | 11 | ten singletons, listed below | genuine FP | **MODELLING** |
| S4 | the strict-family default in another costume | 6 | TS2683 (`noImplicitThis`), TS7019 (`noImplicitAny`) and three TS2322 that are `strictNullChecks` | **deliberate convention** | owner decision, (CHK.13) |
| S6 | lib availability at the DEFAULT target | 5 -> **2** | `libFeatureAvailable` read the RAW `ES3` default; tsc's `getEmitScriptTarget` defaults an unset target to the LATEST | genuine FP | **FIXED round 944** (CHK.17); the 2 left were never a cascade |
| S5 | `keyof` of an intersection / index signature / remapped mapped type | 4 | `keyof (X & T)` loses `keyof T` and the index signature's `string \| number` | genuine FP | MODELLING |
| S7 | write through a generic indexed access | 3 | we say TS2862 where pristine says TS2322 — same position, both reject | **form** | MEDIUM, (CHK.18) |
| S8 | an alias type parameter shadowed in the TS2344 walker | 2 | the walker resolved type ARGUMENTS with no type-parameter scope | genuine FP | **FIXED round 943** |
| S9 | a function-body type ALIAS is not bound | 1 | B83.5 in type position — the lib's `Omit` wins over a local one | genuine FP | MEDIUM, (CHK.19) |

**Cause-class within the bucket: genuine FP 83, deliberate convention 6.** Of the 83,
**68 (82%) are MODELLING** — four type-system capabilities this compiler does not have —
so the honest headline is that the largest bucket is mostly a FEATURE LIST, not a defect
list.

**The ten S10 singletons**, each its own mechanism, none with a second row behind it:
`conditionalTypes2` ×2 (a distributive conditional's relation both ways),
`discriminateWithOptionalProperty2` ×2 (`exactOptionalPropertyTypes` discrimination),
`keyofAndIndexedAccess` ×2 (a `T[K]` write and an explicit type argument to a
descriptor-shaped generic), `ramdaToolsNoInfinite2` TS2344 (an `export type Boolean` in a
sibling ambient module loses to the LIB's `Boolean` — an INV.3 resolution row, not a
type-system one), `expandoFunctionExpressionsWithDynamicNames2`,
`mappedTypeIndexedAccessConstraint`, `numericEnumMappedType`, `templateLiteralTypes1`.

**S1, in one sentence, because it is a third of the bucket.** `getTupleType` maps a
`RestType` element with `is RestType -> getTypeFromTypeNode(elem.type)` — the same arm a
plain element gets — so **`[...T]` is built as the one-element tuple `[T]`**. The fixture's
own section header says what the missing rules are (“for a generic type `T`, `[...T]` is
assignable to `T`, `T` is assignable to `readonly [...T]`, and `T` is assignable to `[...T]`
when `T` is constrained to a mutable array or tuple type”), and the same absence explains
its `keyof [...T]`, its spread-argument arity rows and the whole `curry` inference section.
This is TypeScript 4.0's variadic tuples: a FEATURE, not a defect, and it should be queued
as one. The probe is three lines —
`function f<T extends unknown[]>(t: T, m: [...T]) { t = m }` reports
`Type '[T]' is not assignable to type 'T'`.

### 2.2 HARNESS — jsx configuration (59 rows, 17 groups)

`tsxLibraryManagedAttributes` (27 × TS2874) and `jsxElementType` (22) dominate. The sweep
now defaults `jsx: react` for a `.tsx` input whose case file is missing (a direction that
can only REMOVE an ours-only row), which turned 30 rows of TS17004 into TS2874/TS7026 —
i.e. the JSX namespace still is not resolved the way the real harness resolves it. Treat
this bucket as **not yet measured** rather than as a compiler finding: it needs the
fixture's real `@filename` layout and jsx-runtime shims, which this clone does not carry.

### 2.3 PARSER GAP — unsupported syntax (59 rows, 29 groups)

`using` / `await using` declarations (33 rows over four fixtures), `infer X extends`
(17 over `inferTypes1` / `inferTypesWithExtends1`), `accessor` fields under ES decorators
(3), `privateIndexer2` (3). Every row is a CASCADE: a parse failure followed by TS2304 /
TS2693 for names the failed parse never bound. One parser feature closes each group.

### 2.4 CONVENTION — strict-by-default (42 rows, 23 groups)

TS2564 / TS2454 (and TS7010, not in this population) fire **unless `@strict: false` is
EXPLICIT** in this compiler — `Checker.kt`'s dispatch reads `!options.strictExplicitlyFalse`
— where tsc requires `strict` (or the individual flag) to be ON. So a project with no
`strict` in its tsconfig gets `Property 'x' has no initializer …` from us and nothing from
tsc. This is a deliberate, corpus-driven design decision, not an oversight; it is recorded
here because it is the single largest *systematic* divergence that a real-world user would
meet, and because it is invisible to the corpus (whose fixtures set the directive).

### 2.5 PARSER RECOVERY on a malformed fixture (31 rows, 14 groups)

`mappedTypeProperties` (23) is a mapped type with extra members — deliberately illegal —
and our recovery cascades into TS1005 / TS1128 / TS2362 / TS2363 where pristine's produces
a different shape. Parser error-recovery is a frozen subsystem (CLAUDE.md); these are
form-of-recovery divergences, not missing checks.

### 2.6 FP — computed keys / declaration emit (27 rows, 10 groups)

The residue of the (CHK.5)/(CHK.7) arc.
* **`indexSignatures1` TS1268 ×12** — an index-signature parameter whose type is a branded
  string alias, or a union/intersection of template-literal types, is legal in pristine and
  refused by us. Two of the twelve are a code divergence (pristine says TS1337 there).
* **`strictPropertyInitialization` TS2564 ×4** — `class C { [a]: number; constructor() { this[a] = 1 } }`:
  pristine sees the definite assignment through the ELEMENT ACCESS with a late-bound key,
  we do not. This is a genuine FP hiding inside a bucket-2.4-shaped row, which is why the
  classifier exempts that fixture by name.
* TS2741 for a missing late-bound member (`symbolProperty52`,
  `contextualComputedNonBindablePropertyType`) — the (CHK.7)(ii) modelling item.
* TS2307 / TS2304 in declaration-emit fixtures (7 rows) — a module the sweep's extraction
  cannot materialise plus two genuinely unresolved shadowed `infer` names.

### 2.7 FP — narrowing / control flow (27 rows → **11**, round 942 closed 16)

* ~~`typeGuardNarrowsIndexedAccessOfKnownProperty1` (11)~~ — **CLOSED round 942, 11 → 0**;
  see § 3.4.
* `Symbol.hasInstance` narrowing: `typeGuardsWithInstanceOfBySymbolHasInstance`
  **CLOSED, 5 → 0** (§ 3.5, and pristine-only 8 → 7 — a true positive GAINED);
  `controlFlowInstanceofWithSymbolHasInstance` **7, UNCHANGED and MIS-BUCKETED** — round
  941's queue entry read it as a `Symbol.hasInstance` fixture and **6 of its 7 rows are a
  PARSER GAP**, `abstract new (...args: any) => infer U` inside a conditional type
  (TS1005 ×3 / TS1068 ×2 / TS1128, plus the TS2355 / TS2564 / TS2304 they cascade into).
  Its one genuine narrowing row is line 26, an `instanceof` whose positive branch answers
  the CANDIDATE where tsc answers `t & candidate` — queued as (CHK.15).
* `neverAsDiscriminantType` (2), `symbolProperty57` (1), `symbolProperty61` (2).

---

## 3. Closed by round 941

### 3.1 TS2376 — a `super` call need not be FIRST (13 rows)

Our rule was "`super()` must be the first non-prologue statement". tsc's
(`checkConstructorDeclaration` + `nodeImmediatelyReferencesSuperOrThis` +
`isThisContainerOrFunctionBlock`) walks the constructor's own statement list until EITHER
the super call OR the first statement that immediately references `this`/`super`, and only
the second outcome is an error — so any number of statements may precede `super()` as long
as none of them touches `this` in the constructor's own `this` scope. The walk stops at an
arrow function, a function declaration/expression, a property declaration, and at a
method-like BODY (tsc's "a `Block` whose parent is a Constructor / MethodDeclaration /
GetAccessor / SetAccessor").

**The bound is the interesting half**: a method-like body stops the walk, its NAME does
not, so `get [this.propName]() {}` before `super()` IS still TS2376 — pristine
`derivedClassSuperProperties` lines 281 and 323. A first cut that skipped every member
name silently lost both rows while every "this is no longer an error" pin stayed green;
only the pristine sweep's PRISTINE-ONLY column showed it.

Measured: ours-only 13 → 0, and pristine-only 20 → 19 (one row GAINED, none lost).

### 3.2 TS18028 — the private-identifier target gate reads the target the user ASKED FOR (26 rows)

`CompilerOptions.target` defaults to `ES3` while tsc's `getEmitScriptTarget` defaults an
unset `target` to the latest standard, so a raw `target <= ES5` read made every `#field`
in a project with no `target` an error. The gate is now
`options.targetExplicitlySet && options.target <= ScriptTarget.ES5` — **not**
`effectiveTarget`, which maps an explicit ES5 up to ES2015 and would drop the true
positive an explicit `@target: es5` must keep.

The corpus is structurally blind to both sides: `usesUnsupportedOption` skips every
explicit es3/es5 config, so no ACTIVE baseline exercises this gate at all.

### 3.3 The ablation — four arms, one mistake at a time

`scripts/round941-ablate.py`, each arm applied to and restored from a sha256-verified
snapshot (never `git checkout`), diffed against the SNAPSHOT rather than HEAD, each
asserting `ran 21` so a dead build or an empty filter reads as a failure. **Two arms per
fix by design**: one removes the fix, one removes its BOUND, because a "this is now
silent" pin cannot tell a correct refusal from a disabled check.

| arm | the injected mistake | red | what it proves |
|---|---|---|---|
| A1 | TS2376: require `super()` to be the first non-prologue statement again | **8** | every nested-function-like shape, plus the `{ this: 1 }` member-name control |
| A2 | TS2376's BOUND: skip EVERY member name, computed ones included | **2** | the two "a computed NAME using `this` is still TS2376" pins — the exact defect the first cut shipped and the sweep caught |
| A3 | TS18028: read the raw `target` again | **1** | the unset-target pin |
| A4 | TS18028's BOUND: use `effectiveTarget < ES2015` | **2** | the explicit es3/es5 positive controls |

The four red sets are DISJOINT. **Eight of the 21 pins are green in all four arms and are
recorded as regression guards rather than claimed as discriminators** (round 807): the
parenthesized-`super()` pin and the prologue-directive pin (A1 keeps both mechanisms), the
three TS2376 positive controls (`this`, `super.m()`, a parameter property), the
no-initialized-property control, and the two "an explicit ES2015/ESNext target is silent"
pins.

---

## 3b. Closed by round 942 — (CHK.11) and (CHK.12), 16 rows

Both families are the same sentence one level down: **tsc's `isMatchingReference` compares
references by SYMBOL and ours compares the path STRINGS `getReferencePath` builds.**

### 3.4 Element-access discriminant narrowing (11 rows, `typeGuardNarrowsIndexedAccessOfKnownProperty1`)

`switch (s["kind"])` narrowed nothing while `switch (s.kind)` narrowed correctly. FOUR
mechanisms, each measured against pristine's own baseline (that fixture has NO
`.errors.txt`, i.e. pristine is SILENT for all of it) and re-read against `tsgo 7.0.2`:

| # | mechanism | rows it owned |
|---|---|---|
| 1 | `singleLevelDiscriminantSegment` — the switch's discriminant reader accepts `name[seg]` beside `name.seg` | the `switch (s['dash-ok'])` group |
| 2 | `getTypeOfElementAccess` flow-narrows its UNION RECEIVER (B1.1's gate, which its dotted twin has always had) | `z[1]` read as `string \| number` against a `number` target |
| 3 | `getReferencePath` normalises an identifier-spellable string index onto the DOTTED segment | `s[0]["sub"].under["shape"]` — the fixture mixes both spellings inside ONE expression |
| 4 | `requiredEnumSwitchKeys` + `paramMemberChainType` accept an element-access discriminant and a multi-segment receiver | the two TS2366 "function lacks ending return statement" |

A FIFTH was written, measured INERT and REMOVED: the 17.34d half — narrowing the access's
own union RESULT, the symmetric line to `getTypeOfPropertyAccess`. Its ablation arm
reddened **none** of the round's 21 pins and no probe could be built where it fires
(`if (typeof h[0] === "string") { … h[0] }` still reports the declared `string | number`
with it in place, because the `typeof` guard does not reach an element-access reference at
all). A flow walk with no consultation that can observe it is CLAUDE.md's round-887 shape;
the receiver narrowing is what the fixture actually needed.

Mechanism 3 is the one with a blast radius, and it is the tsc-correct direction: `x["a"]`
and `x.a` are one reference. A non-spellable index (`"dash-ok"`, `0`, `"0"`) keeps round
461's bracket encoding, so no path can collide with a dotted segment, and both
`flowPathRoot` and `pathPrefixOf` already split on `[`.

**Measured: 11 ours-only rows → 0.**

### 3.5 `[Symbol.hasInstance]` narrowing (5 rows, `typeGuardsWithInstanceOfBySymbolHasInstance`)

Round 838's `instanceTypeOfConstructorValue` named this leg as its one deliberate
omission. `instanceof` now asks the RHS type for a `[Symbol.hasInstance]` method whose
return is a non-`asserts` TYPE PREDICATE over parameter 0, and uses its target — which is
what answers the three shapes `prototype` and the construct signatures cannot: a GENERIC
construct signature (`new <T>(): B<T>` + `value is B<any>`), SEVERAL construct signatures
(`value is C1 | C2`), and one returning a union.

Two rules were read off pristine's baseline and confirmed against tsgo:

* **A usable predicate DECIDES.** `value is any` narrows NOTHING and must not fall through
  to the construct signature — pristine reports `string | F` at lines 142/143 with a
  perfectly good `new (): any` sitting right there.
* **An `instanceof` stays `checkDerived = true` even when the candidate came from a
  predicate.** So a UNION candidate is DISTRIBUTED and its narrow-down direction is the
  NOMINAL base-chain test, not assignability: `C1 | A` narrowed by `C1 | C2` is **C1**
  (`A` is structurally a supertype of both candidates, and the assignability form mapped it
  onto the whole union and then reported `bar1` missing on `C2`), while `B0 | string`
  narrowed by `D1 extends B0` is still **D1**. Scoped to a union candidate, so round 425's
  single-candidate arm — whose `tracker instanceof SymbolTrackerImpl` case depends on the
  assignability form — is byte-identical.

The member name is `[Symbol.hasInstance]`: round 723's `computedSymbolKey` names a
well-known-symbol member by its bracketed dotted text, so the lookup is a plain
`getPropertyOfType`. A `static [Symbol.hasInstance]` on a CLASS declaration is out of
scope — `resolveInstanceOfRhsType` answers a class from its declared type before reaching
here — and is recorded rather than guessed at.

**Measured: 5 ours-only rows → 0, and pristine-only 8 → 7 (a true positive GAINED).**

### 3.6 The queue entry was wrong about its own second fixture

(CHK.12) was written as "11 rows over two fixtures". The second,
`controlFlowInstanceofWithSymbolHasInstance`, is **7 rows and 6 of them are a PARSER GAP**
(`abstract new (...args: any) => infer U`), not narrowing at all. That is the third round
running in which re-measuring each row against pristine BEFORE building anything moved the
scope — and it cost one command.

---

## 3c. Closed by round 943 — (CHK.16), 2 rows and a general rule

**A DECLARATION'S OWN TYPE PARAMETERS WERE NOT IN SCOPE FOR THE TS2344 CONSTRAINT WALKER,
so a parameter shadowed by a same-named file-level type was judged as that type.**

`checkConstraintsInStatements` pushed them for a `FunctionDeclaration` (round 82 — and its
comment names this exact defect: "would see `I<T>` resolve T to the global `class T` if any
… and emit FP TS2344"), for a type ALIAS only when the body was an `ImportType` (B98a's
narrow gate), and for a class or an interface never. `withDeclTypeParamScope` is now the one
site and all three branches use it, heritage clauses included.

Pristine `conditionalTypes1` is two ours-only TS2344 from exactly that: its `interface A`
(line 309) against `type And<A extends boolean, B extends boolean> = If<A, B, false>`
(line 171). **The 138-line distance is why nothing smaller found it** — every hand-written
reduction of that fixture is silent, and the bisection that located it deleted the file's
TAIL, not its head.

**BOTH directions were wrong, so the fix ADDS diagnostics as well as removing them**: an
alias/class/interface parameter that genuinely violates its callee's constraint — an
UNCONSTRAINED one included — was silent, because the walker judged the shadow or nothing at
all. `type Loose<Q> = Box<Q>` with `interface Box<S extends string>` now reports TS2344 as
pristine does. Over the 611-fixture population that gained **no** ours-only row anywhere.

The type RESOLUTION path never had the defect — `getTypeFromTypeReference` answers
`Wrap<"x">` correctly with the same interface in scope — which is what bounds the change to
the walker and why no emitted type, display or narrowing can move.

**Measured: ours-only 318 → 316, pristine-only 775 → 775, zero fixtures regressed,
8-profile grid added=0 removed=0 on all eight, suite 15,235 → 15,248 with no baseline moved.**

All three gates were measured at `Checker.kt` sha256 `d1ae7270…`; the committed source
differs from that arm by exactly one comment character (a KDoc's "140 lines below" corrected
to "138"), and reversing that line reproduces the hash byte for byte.

**The first cut fixed only the ALIAS branch, and the pin class caught it**: a "regression
guard — an interface declaration's own type parameter was never affected" pin went RED,
which is how the class/interface half was found. A regression guard that fails is a finding.

---

## 3d. Closed by round 944 — (CHK.17), 3 rows and a systematic real-world false positive

**LIB AVAILABILITY WAS DECIDED FROM `CompilerOptions.target`, WHOSE `ES3` ZERO VALUE IS
INDISTINGUISHABLE FROM "THE USER NAMED NO TARGET".** tsc has ONE such notion,
`getEmitScriptTarget` (`utilities.ts`: `(target === ES3 ? undefined : target) ??
LatestStandard`), and `getDefaultLibFileName` picks the default lib from it — so an unset
`target` loads `lib.es2025.full.d.ts` there and loaded `lib.d.ts` here. A project with no
`target` in its tsconfig was therefore told `Cannot find name 'AsyncIterableIterator'. Do
you need to change your target library?` and had every later-lib interface member filtered
out from under it.

`CompilerOptions.libTarget` (unset -> ES2024, explicit -> itself, `es5` included) is now the
one input to `libFeatureAvailable`, `libProvidesGlobalAt` and the lib-SET resolution in
`bindRealLibs` / `RealLibSnapshots.prewarmParsedLibFiles`. **Not `effectiveTarget`**: it maps
an explicit `es3`/`es5` UP to ES2015 and would delete that program's genuine TS2550/TS2583 —
round 941 met the identical fork at TS18028 and refused it for the same reason.

**Measured: ours-only 316 -> 313, pristine-only 775 -> 775, zero fixtures regressed** —
`uniqueSymbols` 221 (TS2583), `uniqueSymbolsDeclarations` 217 (TS2583),
`intersectionTypeInference3` 12 (TS2550, `Array.from`).

**THE ENTRY THAT SAID THIS WAS DELIBERATE WAS SAYING IT WAS INVISIBLE.** CLAUDE.md recorded
the raw-`ES3` reading as a design decision confined away from the corpus. What is true is
that **the corpus never tested either answer**: of the case files that reach a lib-availability
mechanism at all, **0 of 55** touching a `LIB_MIN_TARGET` member name, **0 of the ~30**
referencing a `LIB_GLOBAL_INTRODUCING` global, and **0 of the 26** whose baseline carries an
`and N more` member count omit `@target`/`@lib`. That is why the suite is 15,262/0 with NO
baseline moved, and it was stated as a prediction before the run rather than read off it.

### 3d.1 The DOWNLEVEL half — CLOSED round 945, and its queue entry had the sign BACKWARDS

Round 944 queued (CHK.21) as a FALSE-NEGATIVE item on the strength of four pristine-only
TS2488 rows (`for-of16` x2, `for-of29`, `iteratorSpreadInArray10`) that the
`if (target < ES2015 && !downlevelIteration) return` gates were assumed to be suppressing.
**They are not.** Round 945's first act was to run those three fixtures' shapes at an
EXPLICIT `es2015` and `esnext` target, where every one of those gates is wide open:

| fixture shape | our answer at `es5` | at unset | at `es2015` | at `esnext` | pristine |
|---|---|---|---|---|---|
| `for (v of new MyStringIterator)` (a `[Symbol.iterator]()` returning a non-Iterator `this`) | silent | silent | **silent** | **silent** | TS2488 x2 |
| `for (var v of iterableWithOptionalIterator)` (an OPTIONAL `[Symbol.iterator]?()`) | silent | silent | **silent** | **silent** | TS2488 |
| `[...new SymbolIterator]` | silent | silent | **silent** | **silent** | TS2488 |

A gate that is open and still silent is not a gate. Those four rows are a **check this
compiler does not implement at any target** — "does the for-of / spread operand's type have
a `[Symbol.iterator]()` whose RETURN is a valid Iterator" — and they are re-filed as
**(CHK.22)**, which is a modelling item, not a target one.

**What the downlevel gates ARE is the mirror family: a FALSE-POSITIVE one, invisible to
both instruments.** The raw `options.target` defaults to `ES3`, so a project whose tsconfig
names no `target` was compiled as if it were being downleveled to ES3, and collected six
diagnostics pristine does not emit. Measured on one 14-line file:

| code | shape | ours at unset, BEFORE | ours at unset, AFTER | pristine at its default |
|---|---|---|---|---|
| TS1250 | `{ function f() {} }` in strict mode | fires | silent | silent |
| TS1501 | `/a/y` | fires | silent | silent |
| TS1503 | `/(?<nm>a)/` | fires | silent | silent |
| TS2659 | `super.` in an object-literal method | fires | silent | silent |
| TS2737 | `1n` | fires | silent | silent |
| TS18045 | `accessor p = 1` | fires | silent | silent |

The pristine oracle for the last column is the baseline corpus itself: **every** TS1250 (7
baselines), TS1501 (24), TS1503 (4), TS2396 (8), TS2659 (2), TS2737 (4), TS18045 (5) and
TS2802 (10) comes from a fixture with an EXPLICIT `@target`. Pristine never emits a
downlevel-gated diagnostic at its default — which is what `getEmitScriptTarget`'s
`target === ES3 ? undefined : target ?? LatestStandard` says it must do.

**THE FIX.** `libTarget` was renamed **`defaultedTarget`** (it no longer names its only
consumer) and is now read by the 23 downlevel-gate lines as well as the four lib ones.
Three raw-`options.target` sites are DELIBERATELY left alone, and the KDoc says why: the
two strict-mode determinations (`spineDelIsStrict`, `spineStrictFileIsExprStrict`) are
`target >= ES2015 || <other disjuncts>`, a mis-transcription of tsc's nested rule that is
correct only while the raw target reads ES3 — flipping them makes every file strict — and
`checkOperationsAvailableOnPromisedType` is a per-fixture baseline pin rather than a
semantic gate.

**WHY NEITHER INSTRUMENT SAW IT, AND A CAVEAT THE NEXT SWEEP READER NEEDS.** The corpus
cannot see it: **6,436 of the 6,573 case files in this clone name an explicit `@target`**
(TypeScript pinned them when it dropped ES5), and of the 213 that do not, a
before/after sweep moved **zero rows in either direction**. The 630-fixture sweep cannot
see it either, for a reason worth recording: its "304 fixtures with no `@target`" is mostly
an ARTEFACT of the sparse clone — those fixtures' case files are absent, so no directives
are recovered and the sweep compiles them at an unset target it invented. A genuine
no-`@target` population has to be built from case files that EXIST, and that is what the
213-fixture arm is.

What did move: 21 hand-written pins from the M0.4 spine-migration rounds, which had relied
on the ES3 default to open the gate for them (one was even named "at default target"). They
were re-pointed at an explicit `@target: es5` through `CompilerTestSupport.DOWNLEVEL_ES5`,
which restores the exact population each was written to measure. **No corpus baseline
moved.**

---

## 4. What to take next

Ranked by (rows × confidence it is a genuine FP × smallness). **Round 943's sub-triage
changes this list**: the biggest bucket is now known to be four MODELLING items, so the
small work left is elsewhere.

1. ~~`indexSignatures1` TS1268 ×12~~ — **CLOSED round 945**, 12 -> 0 with two true positives
   gained. tsc's `isValidIndexKeyType` accepts an INTERSECTION that is not generic and has
   SOME valid constituent — i.e. every BRANDED string — and its generic test reads the whole
   resolved type, which is why `[key: T | number]` is TS1337 and not TS1268. **(CHK.9)**
2. **`strictPropertyInitialization` TS2564 ×4** — definite assignment through
   `this[<late-bound key>] = …`. Small, in the arc's own family, and **CONFIRMED genuine by
   round 943's strict-default arm** (§ 0b: that fixture's own baseline carries 20 TS2564, so
   pristine had the flag ON and the rows are not the convention). **(CHK.10)**
3. ~~Lib availability at the DEFAULT target~~ — **CLOSED round 944, § 3d**; ~~the DOWNLEVEL
   half~~ — **CLOSED round 945, § 3d.1**, where its filed sign turned out to be backwards
   (an FP family invisible to both instruments, not the FN one the four TS2488 rows
   suggested). Those four rows are now **(CHK.22)**: the for-of/spread operand's
   `[Symbol.iterator]()` RETURN is never checked, at any target.
4. **`using` declarations (33 rows)** — a parser feature; the largest single cascade.
   `abstract new (…) => T` (6 rows) and `infer X extends` (17) are the same class.
   **(CHK.14)**
5. **The `instanceof` INTERSECTION tail (1 row here, but a general rule)** — tsc's
   `getNarrowedType` falls back to `t & candidate` when neither direction relates; ours
   answers the candidate alone, so the branch join widens. **(CHK.15)**
6. **`t[k] = v` through a generic indexed access — TS2862 where pristine says TS2322**
   (3 rows). Both compilers reject; the code and the message differ, because our
   "generic and can only be indexed for reading" rule does not notice that the receiver's
   CONSTRAINT supplies a writable index signature. **(CHK.18)**
7. **A function-body type ALIAS is not bound** (1 row, B83.5 in type position) — the lib's
   two-parameter `Omit` wins over a local one-parameter `Omit` and we report TS2314.
   Round 748 closed the same gap for `enum` via `lexicalTypeSymbolForNode`. **(CHK.19)**
8. **The strict-by-default convention (46 rows, up from 42)** — an owner-level decision, not
   a fix. **(CHK.13)**

**And the four MODELLING items the sub-triage names** (68 rows, § 2.1), which are features
rather than defects and should be scheduled as such: variadic tuple types **(CHK.20)** (30),
contextual typing through a mapped/conditional type (14), recursive conditional/mapped types
over tuples (13), `keyof` of an intersection / index signature / remapped mapped type (4).

~~Element-access discriminant narrowing~~ and ~~`Symbol.hasInstance` narrowing~~ are
CLOSED (round 942, § 3.4 / § 3.5); ~~the alias/class/interface type-parameter shadow in the
TS2344 walker~~ is CLOSED (round 943, § 3c).
