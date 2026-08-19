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

## 1. The table — 318 ours-only rows over 79 fixtures, at round 942

The row counts are AT `967c2e53` (round 941's own before-arm) so the buckets stay
comparable; the right-hand column records what each has cost since.

| rows | % | bucket | cause class | exemplar | status |
|---:|---:|---|---|---|---|
| 89 | 23.9 | FP — type system / inference | **genuine FP** | `variadicTuples1` TS2322 ×15 + TS2345 ×14 | open |
| 59 | 15.8 | HARNESS — jsx configuration | **harness artefact** | `tsxLibraryManagedAttributes` TS2874 ×27 | not yet measured |
| 59 | 15.8 | PARSER GAP — unsupported syntax | **cascade** (from a parse failure) | `usingDeclarations*` (4 fixtures, 33 rows), `infer X extends` (17) | open, (CHK.14) |
| 42 | 11.3 | CONVENTION — strict-by-default | **deliberate divergence** | `keyofAndIndexedAccess` TS2564 ×17 | owner decision, (CHK.13) |
| 31 | 8.3 | PARSER RECOVERY on a malformed fixture | **cascade** | `mappedTypeProperties` (23 rows) | frozen subsystem |
| 27 | 7.2 | FP — computed keys / declaration emit | **genuine FP** | `indexSignatures1` TS1268 ×12 | open, (CHK.9)/(CHK.10) |
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

### 2.1 FP — type system / inference (89 rows, 38 groups)

Inference and relation modelling, dominated by one fixture. `variadicTuples1` (31) is
variadic tuple inference; `mappedTypesArraysTuples` (10), `conditionalTypes1/2` (8),
`keyRemappingKeyofResult` (4), `callOfConditionalTypeWithConcreteBranches` (4),
`correlatedUnions` (3), `mappedTypeRecursiveInference2` (3, TS7006) follow. Three rows in
`keyofAndIndexedAccessErrors` are a CODE divergence rather than an extra diagnostic — we
say TS2862 (`generic and can only be indexed for reading`) where pristine says TS2322 at
the same position, i.e. we refuse the write where pristine allows it and then rejects the
value. These are M3-scale modelling items, not surgical fixes.

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

## 4. What to take next

Ranked by (rows × confidence it is a genuine FP × smallness):

1. **`indexSignatures1` TS1268 ×12** — one fixture, one code, a decidable predicate
   ("does this parameter type reduce to string/number/symbol or a template-literal type",
   through aliases, unions and intersections). The (CHK.5)(e) axis. **(CHK.9)**
2. **`strictPropertyInitialization` TS2564 ×4** — definite assignment through
   `this[<late-bound key>] = …`. Small, and in the arc's own family. **(CHK.10)**
3. **`using` declarations (33 rows)** — a parser feature; the largest single cascade.
   `abstract new (…) => T` (6 rows) and `infer X extends` (17) are the same class.
   **(CHK.14)**
4. **The `instanceof` INTERSECTION tail (1 row here, but a general rule)** — tsc's
   `getNarrowedType` falls back to `t & candidate` when neither direction relates; ours
   answers the candidate alone, so the branch join widens. **(CHK.15)**
5. **The strict-by-default convention (42 rows)** — an owner-level decision, not a fix.
   **(CHK.13)**

~~Element-access discriminant narrowing~~ and ~~`Symbol.hasInstance` narrowing~~ are
CLOSED (round 942, § 3.4 / § 3.5).
