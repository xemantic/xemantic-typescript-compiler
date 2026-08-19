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

## 1. The table — 373 ours-only rows over 84 fixtures, at `967c2e53`

| rows | % | bucket | cause class | exemplar |
|---:|---:|---|---|---|
| 89 | 23.9 | FP — type system / inference | **genuine FP** | `variadicTuples1` TS2322 ×15 + TS2345 ×14 |
| 59 | 15.8 | HARNESS — jsx configuration | **harness artefact** | `tsxLibraryManagedAttributes` TS2874 ×27 |
| 59 | 15.8 | PARSER GAP — unsupported syntax | **cascade** (from a parse failure) | `usingDeclarations*` (4 fixtures, 33 rows), `infer X extends` (17) |
| 42 | 11.3 | CONVENTION — strict-by-default | **deliberate divergence** | `keyofAndIndexedAccess` TS2564 ×17 |
| 31 | 8.3 | PARSER RECOVERY on a malformed fixture | **cascade** | `mappedTypeProperties` (23 rows) |
| 27 | 7.2 | FP — computed keys / declaration emit | **genuine FP** | `indexSignatures1` TS1268 ×12 |
| 27 | 7.2 | FP — narrowing / control flow | **genuine FP** | `typeGuardNarrowsIndexedAccessOfKnownProperty1` (11 rows) |
| 26 | 7.0 | **FIXED round 941** — private-identifier target gate | **genuine FP** | `strictPropertyInitialization` TS18028 ×16 |
| 13 | 3.5 | **FIXED round 941** — super-call statement scan | **genuine FP** | `derivedClassSuperProperties` TS2376 ×13 |

**Cause-class totals: genuine FP 182 (48.8%) · cascade 90 (24.1%) · harness artefact 59
(15.8%) · deliberate convention 42 (11.3%).** 39 of the 182 are closed by this round.

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

### 2.7 FP — narrowing / control flow (27 rows, 13 groups)

* `typeGuardNarrowsIndexedAccessOfKnownProperty1` (11) — discriminated-union narrowing
  through an ELEMENT ACCESS (`s['kind']`), which `getTypeOfElementAccess` does not apply
  (CLAUDE.md records the same gap).
* `Symbol.hasInstance` narrowing (`typeGuardsWithInstanceOfBySymbolHasInstance` 5,
  `controlFlowInstanceofWithSymbolHasInstance` 6) — unmodelled, so the receiver stays
  un-narrowed and every member read is TS2339.
* `neverAsDiscriminantType` (2), `symbolProperty57/61` (3).

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

---

## 4. What to take next

Ranked by (rows × confidence it is a genuine FP × smallness):

1. **`indexSignatures1` TS1268 ×12** — one fixture, one code, a decidable predicate
   ("does this parameter type reduce to string/number/symbol or a template-literal type",
   through aliases, unions and intersections). The (CHK.5)(e) axis.
2. **`strictPropertyInitialization` TS2564 ×4** — definite assignment through
   `this[<late-bound key>] = …`. Small, and in the arc's own family.
3. **Element-access discriminant narrowing (11 rows)** — `getTypeOfElementAccess` applies
   no narrowing; CLAUDE.md already records the gap and its consumers.
4. **`using` declarations (33 rows)** — a parser feature; the largest single cascade.
5. **`Symbol.hasInstance` narrowing (11 rows)** — a self-contained narrowing rule.
6. **The strict-by-default convention (42 rows)** — an owner-level decision, not a fix.
