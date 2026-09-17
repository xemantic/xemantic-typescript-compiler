# Logical parity — the form-vs-meaning gate

*Owner directive, 2026-07-26 (round 716):*

> "Logical parity is important even if we don't reach byte-by-byte parity. If there
> are tests where we diverge but the logic stays the same, create a new test case and
> switch off the old one. The logical value of the compiler output at maximal
> performance should always be the deciding factor; byte-by-byte parity is secondary
> if it can be achieved without extra cost."

This document is the decision procedure that directive implies, the mechanism that
implements it, and the ledger of every baseline switched off under it.

---

## 1. What changed

Until round 716 the corpus gate was **byte parity**: a generated test compares our
output to pristine tsc's baseline character by character, and any diff is a
regression. That gate is why the corpus is green and it is not going away — but it
was also a *veto*, and it vetoed the wrong things. Repeatedly, a broad engine rule
was tried, measured against the corpus, found to change N baselines, and recorded in
CLAUDE.md or the archive as **"DEAD — regressed N tests"** — *without anyone checking
whether those N differed in meaning or only in shape*.

Under this policy the gate has two outcomes instead of one:

- a baseline that differs in **MEANING** is a hard regression, exactly as before;
- a baseline that differs only in **FORM** is a *candidate* for a declared
  divergence: pin the logic in a new local test, switch the old baseline off, and
  record why.

**Consequence for the archive.** Every "DEAD — regressed N tests" entry is now a
**lead**, not a verdict. Re-examining one means re-running the change and classifying
its N diffs; if they are all form, the change may be viable after all. Do not cite
such an entry as a reason not to try, and do not treat this as licence to re-land one
without redoing the classification.

**The cost clause matters too.** "Byte parity is secondary *if it can be achieved
without extra cost*" — so byte parity is still preferred when it is free. A
divergence needs a reason it is *worth* having: it unblocks a general rule, it
removes measurable work, it deletes a special case. "Our output happens to differ and
matching would be fiddly" is not one.

---

## 2. The decision procedure

Run this per differing baseline. It is not a formality — the burden is on the change.

### Step 0 — read the whole diff

```bash
rm -rf build/test-results/jvmTest && ./gradlew jvmTest --tests '*<TestName>*'
python3 scripts/dump_diff.py <testName>       # expected vs actual, side by side
```

Never classify from a summary or a count. Read every differing line.

### Step 1 — classify every differing line

A line is **MEANING** if any of these hold. One is enough; stop and treat the whole
case as a hard regression.

| meaning-level difference | why |
|---|---|
| a diagnostic present on one side and absent on the other | the compiler accepts or rejects a different set of programs |
| the same fact reported at a different **span** (start or length) | an editor underlines different code; the user is pointed elsewhere |
| a different diagnostic **code**, unless the two are documented synonyms for one condition | callers (editors, `--suppress`, our own dedup scans) key on the code |
| a displayed type denoting a different **set of values** — `string` vs `string \| number`, `T[]` vs `readonly T[]`, a widened literal | the type is the answer, not the rendering of it |
| emitted JS with different **runtime semantics** — evaluation order, associativity, which value is produced, whether a call or coercion happens, `this` binding, hoisting that is observable | the program means something else (see round 715: an erased cast dropped parentheses and `x + 1 as number) * 3` re-associated) |
| a `.d.ts` change that would make a **consumer** check differently | declaration output is an interface contract |
| a different count of *distinct* diagnostics | one report merged or split is a different answer, even if the text overlaps |

A line is **FORM** if it is one of these *and* nothing above applies:

| form-level difference | equivalence obligation |
|---|---|
| union or intersection **member order** in a displayed type | show the member SETS are equal |
| the **order of two diagnostics at the same position** with the same code | show both are present with identical spans |
| message **wording** for the same code at the same span | show the two chains assert the same fact |
| **elaboration shape** — nesting depth, how many related-information lines carry the same fact | show the top-level fact and the span are identical, and no *additional* fact is lost |
| quoting, whitespace, or parenthesisation *inside* a displayed type | show the denoted type is the same |
| emitted JS differing **syntactically** but provably not semantically | the highest bar in this table — state the argument explicitly; prefer to just match tsc |

If a line fits neither table, it is MEANING by default. The tables are allowlists,
not a spectrum.

### Step 2 — pin the logic

Write a new local test in `src/commonTest/kotlin/` that pins **what the old baseline
was there to pin**, expressed as a fact rather than as bytes. If the baseline pinned
"this program reports TS2322 on the initialiser", assert the code and the span, not
the message text. If it pinned "the union displays these members", assert membership.

Follow the repo's test conventions (`diagnose(...)`, `assert`/`have` from
`com.xemantic.kotlin.test`, no message argument, backtick sentence names, and no AST
node inside a power-assert expression — see CLAUDE.md § Test assertion gotchas).

**Assert the sharp signal.** A test that would pass whether or not the fix works pins
nothing; four "fixes" in rounds 700–704 turned out inert and were caught only because
the probe was built to fail if the change worked.

### Step 3 — declare the divergence

In `build.gradle.kts`, add an entry to `logicalParityDivergences`:

```kotlin
LogicalParityDivergence(
    baseline = "someCase.errors.txt",     // exact file under tests/baselines/reference
    round = 717,
    pinnedBy = "SomeCaseLogicTest",       // the class written in step 2 — must exist
    reason = "Union members print in a different order (…). The member SET is " +
        "identical; the divergence is display order only, which our getUnionType " +
        "dedup no longer preserves after <change>.",
)
```

Then regenerate and gate as usual:

```bash
./gradlew generateTypeScriptTests        # rewrites the ledger below
rm -rf build/test-results/jvmTest && ./gradlew jvmTest
```

### Step 4 — write it up

The session note states the change, the diff, the classification, and the
equivalence argument **per case**. A count of switched-off baselines with no
per-case argument is exactly the failure mode this policy has to avoid.

---

## 3. The mechanism

`logicalParityDivergences` in `build.gradle.kts` is the **single source of truth**.
The generator consumes it and:

- emits the matching test with `@kotlin.test.Ignore` and the reason as a comment, so
  the case stays **visible as SKIPPED** in the suite — the skipped count moves, and a
  silently-dropped test cannot hide behind an unchanged total;
- **fails the build on a stale entry** — a declared baseline that matches no
  generated test means the ledger has rotted (renamed case, baseline gone, or the
  test was already skipped for a tsgo reason), and a rotted ledger is
  indistinguishable from a hidden regression;
- **fails the build when `pinnedBy` names no class** under `src/commonTest/kotlin`,
  which is what makes "replace it with a test pinning the logic" mechanical rather
  than aspirational;
- **rewrites the ledger below** from the declarations, so the table cannot drift from
  the build.

Commenting a test emission out, deleting a baseline, or widening a skip predicate to
make a red case disappear are all **not** this mechanism, and none of them is
allowed as a way to absorb a diff.

### What this is *not* for

`conformanceDeferredErrorBaselines` (also in `build.gradle.kts`) is a **different**
mechanism for a **different** situation: a case where we are genuinely *wrong* —
a MEANING-level gap — deferred with a queue item naming the missing behaviour. Do not
move an entry between the two lists to change how it reads. A gap is a gap.

| | logical-parity divergence | deferred error baseline |
|---|---|---|
| our output is | equivalent, differently shaped | wrong |
| requires | an equivalence argument + a logic-pinning test | a queue item naming the gap |
| ends when | never — it is a deliberate divergence | the gap is implemented |

---

## 4. Ledger

Every baseline switched off under this policy, generated from
`logicalParityDivergences`. Do not hand-edit the region below.

<!-- BEGIN GENERATED LEDGER -->
| baseline | round | logic pinned by | why this is FORM, not MEANING |
|---|---:|---|---|
| `acceptableAlias1.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; tsc had no baseline for this case at all, so nothing was switched off that ever ran. |
| `accessorInferredReturnTypeErrorInReturnStatement.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `aliasInaccessibleModule.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; tsc had no baseline for this case at all, so nothing was switched off that ever ran. |
| `checkingObjectWithThisInNamePositionNoCrash.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `classExpressionWithDecorator1.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `constructorWithIncompleteTypeAnnotation.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `declarationEmitNameConflictsWithAlias.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; tsc had no baseline for this case at all, so nothing was switched off that ever ran. |
| `declarationEmitTypeofThisInClass.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; tsc had no baseline for this case at all, so nothing was switched off that ever ran. |
| `exportImportNonInstantiatedModule.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; tsc had no baseline for this case at all, so nothing was switched off that ever ran. |
| `interfaceMayNotBeExtendedWitACall.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `isolatedModulesExportImportUninstantiatedNamespace.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `jsxRuntimePragma(jsx=react-jsxdev).js` | 86 | `JsxDevRuntimeFileNameTest` | tsgo's HARNESS mounts a test's files on a virtual filesystem rooted at `/.src/`, so its baseline hoists `const _jsxFileName = "/.src/two.tsx"` where ours (and tsc 6's, byte-for-byte on this case) hoists `"two.tsx"`. The prefix is a property of where someone else's runner put the file, not of TypeScript 7 — no tsconfig, directive or source text produces it here — so it is not an implementable row. Everything the baseline tested about the dev runtime (the hoist, its per-file name, every jsxDEV call going through the binding, the fileName/lineNumber/columnNumber debug argument, and the classic pragma override) is pinned instead, with a negative control that no emitted path carries `/.src/`. See docs/tsgo-baselines.md § 3 and § 7 risk 2. |
| `manyCompilerErrorsInTheTwoFiles.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `missingCloseParenStatements(alwaysstrict=true).errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `noUnusedLocals_selfReference.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `reachabilityChecksNoCrash1.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `reverseMappedPartiallyInferableTypes.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `shorthandPropertyAssignmentsInDestructuring(target=es2015).errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `shorthandPropertyAssignmentsInDestructuring_ES6.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `superCallsInConstructor.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `withStatement.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |
| `withStatementErrors.errors.txt` | 86 | `TsgoHarnessSelfCheckBaselinesTest` | tsgo answers this case with its harness SELF-CHECK, not with an answer: `error TS-1: Pre-emit (N) and post-emit (M) diagnostic counts do not match!`. `TS-1` is no TypeScript code, and tsgo files the family under `submoduleTriaged` -- "known diffs that we intend to fix", group "checker order dependence creating diagnostic instability in API scenarios", whose own note reads "ANY test with a TS-1 indicates a problem". MEANING is preserved because there is no meaning in the file to follow; the tsc comparison this baseline used to make is reproduced verbatim in the pinning class, so no coverage is lost. |

**22 baseline(s) switched off.**
<!-- END GENERATED LEDGER -->

---

## 5. tsgo-pending

*(LEGACY.0a), 2026-09-12.* The corpus is pinned to tsgo 7.0.2's own baselines (the
`tsgo-port` sha, `typeScriptCommit` in `xemantic-typescript-compiler-core/build.gradle.kts`),
so a red corpus baseline is TypeScript 7's answer that this compiler does not produce
YET — a row to implement, not a divergence to argue. Such a row goes into
`tsgoPendingBaselines` beside `logicalParityDivergences`:

- the generator emits the subtest `@Ignore`d, exactly as it does for a divergence, so it
  stays visible as SKIPPED and counted (`tsgo-pending: N` in the build log);
- an entry naming no generated subtest FAILS the build (stale), and a baseline may never
  be in both lists;
- no `pinnedBy` class is required — nothing is pinned instead of the baseline; the entry is
  the queue, and it is deleted the round its family lands.

The ledger below is rewritten from the declarations by `generateTypeScriptTests`.

<!-- BEGIN GENERATED TSGO-PENDING -->
| baseline | the TypeScript 7 row still to implement |
|---|---|
| `argumentsReferenceInFunction1_Js.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> type DISPLAY: the code, span and sentence agree and only a rendered TYPE differs; layer `submoduleAccepted`. tsgo: index.js(13,29): error TS2345: Argument of type 'IArguments' is not assignable to parameter of type '[f?: any]'. \| ours: index.js(13,29): error TS2345: Argument of type 'IArguments' is not assignable to parameter of type '[f?: any, ...any[]]'. |
| `asyncArrowInClassES5(target=es2015).js` | JS emit; layer `submoduleAccepted`. tsgo: (none) \| ours: var _a; |
| `augmentExportEquals2.js` | JS emit; layer `submoduleAccepted`. tsgo: //// [file3.ts] \| ours: //// [file1.js] |
| `classFieldSuperNotAccessibleJs.errors.txt` | NARROWED to ONE ROW (LEGACY.0b) step 25 — our answer is a SUBSET of tsgo's, byte-identical on the five rows we emit. (P18.131)'s J1 gave the property-access funnel a JavaScript class EXPANDO MEMBER MODEL ([Checker.jsClassAccessAdmitted]), so a `this.`/`super.` receiver whose class chain is modellable is decidable: the three TS2339 rows at (7,14), (26,22) and (29,22) now fire at tsgo's positions with tsgo's messages, and the two CORRECT TS2855 rows for `roots` and `foo` stay because the model REFUSES the access for a name that IS an expando member. What is left is J3 and it is NOT a JavaScript question: `this['literalElementAccess'];` is TS7053 in tsgo — a two-line chain anchored at the WHOLE element access — where our element funnel would give TS2339 at the index, and the SAME divergence reproduces in a `.ts` file, so opening the element funnel would propagate a wrong code and span into a second file kind. tsgo: index.js(9,9): error TS7053: Element implicitly has an 'any' type because expression of type '"literalElementAccess"' can't be used to index type 'YaddaBase'. \| ours: (nothing) |
| `coAndContraVariantInferences5.errors.txt` | NEW in the tsgo-port baselines: a TS2322 on a contravariant callback property (`onChange: (status: Thing \| null) => void` against `(key: KeyT) => void`) that generic inference does not reach here; not an ordering row. |
| `commonjsAccessExports.errors.txt` | TS2683-residue: the TS2683 row is CORRECT since (LEGACY.0b step 7); what is left is TS7009 for a PROPERTY-ACCESS callee (`new exports.Cls()` where `exports.Cls = function(){}`), and that gap is GENERAL rather than JS-specific — measured, we are silent for `new O.m()` and `new N.f()` in a plain .ts file too where tsgo reports both. `checkNewExprImplicitAny` demands `callee is Identifier`; the fix is to decide TS7009 from the callee TYPE (tsgo: the resolved signature's declaration is not a constructor / construct signature / constructor type), which is its own family. tsgo: /a.js(12,18): error TS7009 \| ours: nothing |
| `complexRecursiveCollections.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> chain CONTENT: a different chain message is chosen, not a different spelling of the same one; layer `submoduleAccepted`. tsgo: The types returned by 'map(...).size' are incompatible between these types. \| ours: The types of 'map(...).size' are incompatible between these types. |
| `contextualReturnTypeOfIIFE2.errors.txt` | F1 tsgo REPORTS where we are silent (a NEW errors baseline); layer `submoduleAccepted`. tsgo: contextualReturnTypeOfIIFE2.ts(5,9): error TS2339: Property 'bar' does not exist on type '() => void'. \| ours: nothing |
| `controlFlowInstanceof.errors.txt` | TS2683-residue: the `uglify.js(5,23)` TS2683 row is CORRECT since (LEGACY.0b step 7); the residue is THREE other mechanisms and none is implicit-`this`. Ours-only: controlFlowInstanceof.ts(20,7) TS2339 `Property 'add' does not exist on type 'Promise<any> \| Set<number>'` (an `instanceof` narrow that tsgo resolves to `Set<number>`) and controlFlowInstanceof.ts(105,5) TS2721 `Cannot invoke an object which is possibly 'null'`; missing: uglify.js(9,7) TS2339 `Property 'val' does not exist on type '{}'`. layer `submoduleAccepted`. |
| `elidedJSImport1.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> type DISPLAY: the code, span and sentence agree and only a rendered TYPE differs; layer `submoduleAccepted`. tsgo: caller.js(2,8): error TS18042: 'TruffleContract' is a type and cannot be imported in JavaScript files. Use 'import("@truffle/contract")' in a JSDoc ty \| ours: caller.js(2,8): error TS18042: 'TruffleContract' is a type and cannot be imported in JavaScript files. Use 'import("@truffle/contract").TruffleContrac |
| `es6ExportEqualsInterop.errors.txt` | RECLASSIFIED (LEGACY.0b step 3) F6 -> pin-walker + type DISPLAY: this baseline's 31 diagnostics (the nine TS2497 rows included) are re-emitted VERBATIM by the dedicated walker `checkEs6ExportEqualsInteropPin`, so step 3's deletion of every TS2497 EMITTER does not reach it; closing the row means retiring that walker AND typing `import * as ns` of an `export = <fn>` module as its synthetic default. Layer `submoduleAccepted`. tsgo: main.ts(56,4): error TS2339: Property 'a' does not exist on type '{ default: () => any; }'. \| ours: same row on type '() => any', plus nine TS2497 rows tsgo does not have. |
| `esModuleInteropTslibHelpers.errors.txt` | F6 top code differs (tsgo TS2354 / ours -); layer `submoduleAccepted`. tsgo: file.ts(1,1): error TS2354: This syntax requires an imported helper but module 'tslib' cannot be found. \| ours: ==== file.ts (0 errors) ==== |
| `expandoFunctionNestedAssigments.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> type DISPLAY: the code, span and sentence agree and only a rendered TYPE differs; layer `submodule`. tsgo: expandoFunctionNestedAssigments.ts(7,23): error TS2339: Property 'inNestedFunction' does not exist on type '{ (): void; inVariableInit: number; bla: { \| ours: expandoFunctionNestedAssigments.ts(7,23): error TS2339: Property 'inNestedFunction' does not exist on type 'typeof Foo'. |
| `exportAssignmentMembersVisibleInAugmentation.errors.txt` | F6 top code differs (tsgo TS4060 / ours TS2304,TS2664); layer `submoduleTriaged`. tsgo: /a.ts(3,26): error TS4060: Return type of exported function has or is using private name 'T'. \| ours: /a.ts(2,16): error TS2664: Invalid module name in augmentation, module 'foo' cannot be found. |
| `expressionWithJSDocTypeArguments.errors.txt` | F6 top code differs (tsgo TS1110 / ours TS17019,TS17020,TS8020); layer `submoduleAccepted`. tsgo: expressionWithJSDocTypeArguments.ts(9,22): error TS1110: Type expected. \| ours: expressionWithJSDocTypeArguments.ts(9,21): error TS8020: JSDoc types can only be used inside documentation comments. |
| `importDeclWithExportModifierAndExportAssignment.js` | JS emit; layer `submoduleAccepted`. tsgo: Object.defineProperty(exports, "__esModule", { value: true }); \| ours: module.exports = x; |
| `incorrectRecursiveMappedTypeConstraint.errors.txt` | F0 rows tsgo emits that ours does not: a related TS2751 `Circularity originates in type at this location.` on incorrectRecursiveMappedTypeConstraint.ts:3:10. REFUSED (LEGACY.0b) step 19 and it must stay refused: the divergence LAYER is `submoduleTriaged` — the `.diff` for this baseline lives in testdata/baselines/reference/submoduleTriaged/compiler/, whose header reads "known diffs that we intend to fix", i.e. a tsgo DEFECT. (The base `.errors.txt` under submodule/ is the adopted baseline every case has; it is NOT the layer, and reading it as one is what made this row look targetable.) tsgo attaches it at TS2313 x2. \| ours: nothing |
| `invariantGenericErrorElaboration.errors.txt` | F7 diagnostic COUNT changed; layer `submoduleAccepted`. tsgo: Type 'Constraint<Runtype<any>>' is not assignable to type 'Constraint<Num>'. \| ours: Property 'tag' is missing in type 'Runtype<any>' but required in type 'Num'. |
| `jsDeclarationEmitExportedClassWithExtends.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> source-echo PATH: the annotated-source header spells a doubled separator; layer `submoduleAccepted`. tsgo: ==== node_modules/lit-element/development/lit-element.d.ts (0 errors) ==== \| ours: ==== node_modules/lit-element/development//lit-element.d.ts (0 errors) ==== |
| `jsEnumCrossFileExport.errors.txt` | RE-SIZED (LEGACY.0b step 14): the two enumDef.js TS1003 rows now MATCH ((P18.99) M2); what remains is (a) the two index.js TS2749 rows on a QUALIFIED expando name (`Host.UserMetrics.Action`), which tsgo resolves through the JSDoc-namespace declarations a `@typedef {…} Host.UserMetrics.Bargh` creates (a plain expando is TS2503 there, measured) — unmodelled here — and (b) the baseline RENDERING of the (14,21) row, whose width-1 range is the NEWLINE: tsgo's harness prints an empty squiggle line, the next source line and the message after it, where ours prints a `~` at column 21. |
| `jsExpandoObjectDefineProperty.errors.txt` | F1 tsgo REPORTS where we are silent (a NEW errors baseline); layer `submodule`. tsgo: index.js(3,17): error TS2339: Property 'inspectedWindow' does not exist on type '{}'. \| ours: nothing |
| `jsExportMemberMergedWithModuleAugmentation.errors.txt` | PARTIAL since (P18.121): the TS2671 row now MATCHES tsgo, at /index.ts(3,16), and the TS2741 it used to carry is gone — the augmentation is refused rather than merged. The residue is a SECOND mechanism this row needs and its sibling (…Augmentation2, closed) did not: an import from a CJS `module.exports = {objLit}` JS file binds a VALUE ONLY, so tsgo reports /index.ts(11,10) TS2749 `'Abcde' refers to a value, but is being used as a type here. Did you mean 'typeof Abcde'?` where we still resolve `Abcde` as a type and report /index.ts(11,20) TS2353 `Object literal may only specify known properties, and 'b' does not exist in type 'Abcde'.` The fixture's own comment states the rule ("the type meaning from /test.js does not propagate through the object literal export"). Needs the value-only-import change plus its TS2353 suppression; NOT an augmentation question. |
| `jsdocFunctionClassPropertiesDeclaration.errors.txt` | TS2683-residue: the three TS2683 rows and the TS7009 row are CORRECT since (LEGACY.0b step 7); the residue is that a JSDoc `@param {number \| undefined} x` tag does not TYPE the parameter it names, so we add three ours-only rows tsgo does not have — /a.js(5,21) and (5,24) TS7006 `Parameter 'x'/'y' implicitly has an 'any' type.` plus /a.js(5,17) TS7023 `'Foo' implicitly has return type 'any'…`. JSDoc parameter typing is its own family. layer `submodule`. |
| `jsdocImportTypeNodeNamespace.errors.txt` | F6 top code differs (tsgo TS2694 / ours TS2352); layer `submodule`. tsgo: Main.js(2,49): error TS2694: Namespace '"GeometryType"' has no exported member 'default'. \| ours: Main.js(2,21): error TS2352: Conversion of type 'string' to type 'typeof _default' may be a mistake because neither type sufficiently overlaps with th |
| `jsdocParameterParsingInfiniteLoop.errors.txt` | F6 top code differs (tsgo TS1005 / ours TS1110,TS2304,TS7014); layer `submodule`. tsgo: example.js(3,19): error TS1005: '}' expected. \| ours: example.js(3,11): error TS7014: Function type, which lacks return-type annotation, implicitly has an 'any' return type. |
| `jsdocRestParameter.errors.txt` | F6 top code differs (tsgo TS2554 / ours TS2345); layer `submodule`. tsgo: /a.js(8,6): error TS2554: Expected 1 arguments, but got 2. \| ours: /a.js(7,3): error TS2345: Argument of type 'number[]' is not assignable to parameter of type 'number'. |
| `mixinPrivateAndProtected.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> type DISPLAY: the code, span and sentence agree and only a rendered TYPE differs; layer `submoduleAccepted`. tsgo: The intersection 'mixB.(Anonymous class) & A' was reduced to 'never' because property 'pvt' exists in multiple constituents and is private in some. \| ours: The intersection 'mixB<typeof A>.(Anonymous class) & A' was reduced to 'never' because property 'pvt' exists in multiple constituents and is private i |
| `mutuallyRecursiveCallbacks.errors.txt` | F7 diagnostic COUNT changed; layer `submoduleAccepted`. tsgo: Type 'Foo<unknown>' is not assignable to type 'Bar<{}>'. \| ours: Types of parameters 'bar' and 'foo' are incompatible. |
| `namespaceDisambiguationInUnion.errors.txt` | RECLASSIFIED (LEGACY.0b step 9) ORDER -> CHAIN-PICKER: the union's own display is now CORRECT (`Foo.Yep \| Bar.Yep`, verified) and the whole residue is one chain sub-line — tsgo names `"bar.yep"` and we name `"foo.yep"`. tsc's `typeRelatedToSomeType` reports a union TARGET with no discriminant match against its LAST constituent; the var-decl chain here picks the first. `findBestUnionConstituent` already keeps the LAST on a tie ((LEGACY.0a)), so this chain does not go through it — that is the gap. |
| `noInferUnionExcessPropertyCheck1.errors.txt` | ORDER-model (re-measured (LEGACY.0b) step 9): the residue is the order of two ANONYMOUS constituents — a function type and an object type — and their DECLARATION positions give the opposite of tsgo's answer (row 23 is `(() => { x: string; }) \| { x: string; }` where the object's declaration, `T`'s constraint, is the EARLIER node), so it is not `compareSymbols`. Rows 7/15 need the other half: `NoInfer<T>` is a `Substitution` type in tsc (bit 24, after `Object`'s bit 20) where this model represents it as its own argument with an alias display. NOT served by the TS2353 walker step 9 ordered — a `FunctionType` constituent makes that one bail. |
| `noParameterReassignmentIIFEAnnotated.errors.txt` | JSDoc: an OURS-ONLY TS8029. (LEGACY.0b) F6a is CLOSED for this row -- its TS2740 leaf now matches tsgo byte for byte -- and what is left is a different family: `@param {...unknown} rest` on a zero-parameter function that reads `arguments`. A VARIADIC JSDoc param IS an array type, so tsc's `It would match 'arguments' if it had an array type` rung must not fire; our emitter does not test the tag's variadic-ness. tsgo reports nothing here. Layer `submoduleAccepted`. tsgo: index.js(6,42): error TS2740 (which we now match) \| ours: that row PLUS index.js(3,28): error TS8029: JSDoc '@param' tag has name 'rest', but there is no parameter with that name. |
| `overloadOnConstNoAnyImplementation2.errors.txt` | REFUSED with a measurement (LEGACY.0b step 18): the mechanism is tsgo's type-NODE REUSE, not quote preservation. tsgo prints the SOURCE TEXT of a parameter's written annotation whenever the rendered signature's declaration is a function-like with a BODY (arrow / function expression / an inferred `const`), and renders structurally otherwise — measured: an interface MethodSignature (`overloadOnConstInheritance2`, ACTIVE and GREEN, source `(x: 'bar')` rendered `(x: "bar")`), a FunctionTypeNode annotation (`declare const h: (x: 'e') => number` renders `"e"`) and an instantiated generic alias (`Func<T,U>`) all render structurally. Reuse is VERBATIM, so it also keeps a type ALIAS unresolved (`(x: Al)` where we print `(x: "zz")`), a keyword alias (`(x: Nm)` for `number`) and a generic spelling (`Array<string>`), and even a backslash escape (`'it\'s'`). COST: `typeToString` renders from a `Type` and has neither the declaring file's source nor a tight end for a `TypeNode` (only `AsExpression` carries `tightEnd`), so it is a display-layer change; exposure is 186 tsgo errors baselines rendering an annotated-parameter signature, 94 of them subtests in the live generated tree (66 with a one-parameter signature), all currently GREEN and gated by the corpus alone ((PARITY.1)). tsgo: Argument of type '(x: 'bye') => number' \| ours: '(x: "bye") => number'; both sides' chain sub-lines are double-quoted in tsgo too. |
| `overloadOnConstNoStringImplementation2.errors.txt` | REFUSED with a measurement (LEGACY.0b step 18): the mechanism is tsgo's type-NODE REUSE, not quote preservation. tsgo prints the SOURCE TEXT of a parameter's written annotation whenever the rendered signature's declaration is a function-like with a BODY (arrow / function expression / an inferred `const`), and renders structurally otherwise — measured: an interface MethodSignature (`overloadOnConstInheritance2`, ACTIVE and GREEN, source `(x: 'bar')` rendered `(x: "bar")`), a FunctionTypeNode annotation (`declare const h: (x: 'e') => number` renders `"e"`) and an instantiated generic alias (`Func<T,U>`) all render structurally. Reuse is VERBATIM, so it also keeps a type ALIAS unresolved (`(x: Al)` where we print `(x: "zz")`), a keyword alias (`(x: Nm)` for `number`) and a generic spelling (`Array<string>`), and even a backslash escape (`'it\'s'`). COST: `typeToString` renders from a `Type` and has neither the declaring file's source nor a tight end for a `TypeNode` (only `AsExpression` carries `tightEnd`), so it is a display-layer change; exposure is 186 tsgo errors baselines rendering an annotated-parameter signature, 94 of them subtests in the live generated tree (66 with a one-parameter signature), all currently GREEN and gated by the corpus alone ((PARITY.1)). tsgo: Argument of type '(x: 'bye') => number' \| ours: '(x: "bye") => number'; both sides' chain sub-lines are double-quoted in tsgo too. |
| `pathsValidation5.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> summary ORDER; re-confirmed at step 9 that it is NOT a union order and shares no mechanism with the ORDER family: the TS5090 wording matches and the ONLY difference is where a `tsconfig.json` row sorts against a source file's in the summary — tsgo lists `src/main.ts(1,8): TS2882` FIRST and we list it last. Changing it reorders the summary of every multi-file baseline, so it needs its own round. MEASURED (LEGACY.0b step 15): tsgo's rule is pure path order (`ast.CompareDiagnostics`), and adopting it moves SEVEN green baselines whose tsconfig rows stay first — all seven are baseUrl / moduleResolution=node cases tsgo 7 does not run (no baseline under typescript-go-repo/testdata), so their order is a (LEGACY.1) question, not a rule. |
| `reverseMappedTypeIntersectionConstraint.errors.txt` | ORDER-model — and NOT a union order at all (re-measured (LEGACY.0b) step 9): all four rows are the MEMBER order inside one anonymous object display, and every one of them is tsgo's alphabetical-by-property-name (`{ anotherField; field }`, `{ nested; prop }`, `{ invoke; types }`). A reverse-mapped type's members carry no declarations in tsc and so list by NAME; ours carry the source literal's declarations and list by position. Needs a reverse-mapped MARK on the type — sorting every anonymous object's members by name is a whole-corpus change. |
| `typeParameterDiamond4.errors.txt` | ORDER-model (measured (LEGACY.0b) step 9): `T \| Top \| U`. NOT "type parameters cannot be ordered" — the comparator orders a type-parameter union correctly inside ONE function scope (`Zed \| Alpha` renders `Alpha \| Zed`, byte-identical to tsgo, in both written orders). The variable is an ENCLOSING function's type parameter: this display follows the WRITTEN annotation order exactly (rewriting the fixture as `U \| T \| Top` renders `U \| T \| Top`), and the same union is degraded enough elsewhere that the ordinary var-decl reader emits NOTHING for `Zed \| Alpha` when `Zed` comes from an enclosing scope. So the ORDER row sits on a RESOLUTION gap, not on the comparator. |
| `typeParameterWithInvalidConstraintType.errors.txt` | F0 rows tsgo emits that ours does not: a related TS2751 `Circularity originates in type at this location.` on typeParameterWithInvalidConstraintType.ts:4:17. REFUSED (LEGACY.0b) step 19 and it must stay refused: the divergence LAYER is `submoduleTriaged` — the `.diff` for this baseline lives in testdata/baselines/reference/submoduleTriaged/compiler/, whose header reads "known diffs that we intend to fix", i.e. a tsgo DEFECT. (The base `.errors.txt` under submodule/ is the adopted baseline every case has; it is NOT the layer, and reading it as one is what made this row look targetable.) tsgo attaches it at TS2313. \| ours: nothing |

**37 baseline(s) pending.**
<!-- END GENERATED TSGO-PENDING -->
