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
| `arrayCast.errors.txt` | F6 top code differs (tsgo TS2353 / ours TS2352); layer `submoduleAccepted`. tsgo: arrayCast.ts(3,23): error TS2353: Object literal may only specify known properties, and 'foo' does not exist in type '{ id: number; }'. \| ours: arrayCast.ts(3,23): error TS2352: Conversion of type '{ foo: string; }[]' to type '{ id: number; }[]' may be a mistake because neither type sufficient |
| `arrayIterationLibES5TargetDifferent(nolib=true,target=es2015).errors.txt` | F8 span/column only; layer `submoduleAccepted`. tsgo: error TS5053: Option 'lib' cannot be specified with option 'noLib'. \| ours: error TS5053: Option 'lib' cannot be specified with option 'noLib'. |
| `arrayIterationLibES5TargetDifferent(nolib=true,target=esnext).errors.txt` | F8 span/column only; layer `submoduleAccepted`. tsgo: error TS5053: Option 'lib' cannot be specified with option 'noLib'. \| ours: error TS5053: Option 'lib' cannot be specified with option 'noLib'. |
| `assigningFromObjectToAnythingElse.errors.txt` | F6 top code differs (tsgo TS2322 / ours TS2696); layer `submoduleAccepted`. tsgo: assigningFromObjectToAnythingElse.ts(3,1): error TS2322: Type 'Object' is not assignable to type 'RegExp'. \| ours: assigningFromObjectToAnythingElse.ts(3,1): error TS2696: The 'Object' type is assignable to very few other types. Did you mean to use the 'any' type i |
| `asyncArrowInClassES5(target=es2015).js` | JS emit; layer `submoduleAccepted`. tsgo: (none) \| ours: var _a; |
| `augmentExportEquals2.js` | JS emit; layer `submoduleAccepted`. tsgo: //// [file3.ts] \| ours: //// [file1.js] |
| `augmentedTypesVar.js` | JS emit; layer `submoduleAccepted`. tsgo: (none) \| ours: var x5; |
| `awaitInNonAsyncFunction.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> F8 span/width: the CODE and TEXT agree and only the anchor or squiggle length differs; layer `submoduleAccepted`. tsgo: !!! related TS1356 awaitInNonAsyncFunction.ts:13:7: Did you mean to mark this function as 'async'? \| ours: !!! related TS1356 awaitInNonAsyncFunction.ts:13:28: Did you mean to mark this function as 'async'? |
| `bigintWithLib.errors.txt` | ours emits EXTRA rows tsgo does not; layer `submoduleAccepted`. tsgo: bigintWithLib.ts(4,1): error TS2350: Only a void function can be called with the 'new' keyword. \| ours: Type 'number' is not assignable to type 'bigint'. |
| `bitwiseCompoundAssignmentOperators.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> F8 span/width: the CODE and TEXT agree and only the anchor or squiggle length differs; layer `submoduleAccepted`. tsgo: bitwiseCompoundAssignmentOperators.ts(3,3): error TS2447: The '^=' operator is not allowed for boolean types. Consider using '!==' instead. \| ours: bitwiseCompoundAssignmentOperators.ts(3,1): error TS2447: The '^=' operator is not allowed for boolean types. Consider using '!==' instead. |
| `blockScopedBindingsInDownlevelGenerator(target=es2015).errors.txt` | F6 top code differs (tsgo TS5102 / ours TS5101); layer `submoduleAccepted`. tsgo: error TS5102: Option 'downlevelIteration' has been removed. Please remove it from your configuration. \| ours: error TS5101: Option 'downlevelIteration' is deprecated and will stop functioning in TypeScript 7.0. Specify compilerOption '"ignoreDeprecations": "6. |
| `classFieldSuperNotAccessibleJs.errors.txt` | F6 top code differs (tsgo TS2339,TS7053 / ours TS2855); layer `submoduleAccepted`. tsgo: index.js(7,14): error TS2339: Property 'justProp' does not exist on type 'YaddaBase'. \| ours: index.js(26,22): error TS2855: Class field 'justProp' defined by the parent class is not accessible in the child class via super. |
| `coAndContraVariantInferences5.errors.txt` | NEW in the tsgo-port baselines: a TS2322 on a contravariant callback property (`onChange: (status: Thing \| null) => void` against `(key: KeyT) => void`) that generic inference does not reach here; not an ordering row. |
| `commonMissingSemicolons.errors.txt` | F6 top code differs (tsgo TS2552 / ours TS2304); layer `submoduleAccepted`. tsgo: commonMissingSemicolons.ts(16,8): error TS2552: Cannot find name 'myConst3'. Did you mean 'myConst1'? \| ours: commonMissingSemicolons.ts(16,8): error TS2304: Cannot find name 'myConst3'. |
| `commonjsAccessExports.errors.txt` | TS2683-residue: the TS2683 row is CORRECT since (LEGACY.0b step 7); what is left is TS7009 for a PROPERTY-ACCESS callee (`new exports.Cls()` where `exports.Cls = function(){}`), and that gap is GENERAL rather than JS-specific — measured, we are silent for `new O.m()` and `new N.f()` in a plain .ts file too where tsgo reports both. `checkNewExprImplicitAny` demands `callee is Identifier`; the fix is to decide TS7009 from the callee TYPE (tsgo: the resolved signature's declaration is not a constructor / construct signature / constructor type), which is its own family. tsgo: /a.js(12,18): error TS7009 \| ours: nothing |
| `complexRecursiveCollections.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> chain CONTENT: a different chain message is chosen, not a different spelling of the same one; layer `submoduleAccepted`. tsgo: The types returned by 'map(...).size' are incompatible between these types. \| ours: The types of 'map(...).size' are incompatible between these types. |
| `contextualReturnTypeOfIIFE2.errors.txt` | F1 tsgo REPORTS where we are silent (a NEW errors baseline); layer `submoduleAccepted`. tsgo: contextualReturnTypeOfIIFE2.ts(5,9): error TS2339: Property 'bar' does not exist on type '() => void'. \| ours: nothing |
| `controlFlowInstanceof.errors.txt` | TS2683-residue: the `uglify.js(5,23)` TS2683 row is CORRECT since (LEGACY.0b step 7); the residue is THREE other mechanisms and none is implicit-`this`. Ours-only: controlFlowInstanceof.ts(20,7) TS2339 `Property 'add' does not exist on type 'Promise<any> \| Set<number>'` (an `instanceof` narrow that tsgo resolves to `Set<number>`) and controlFlowInstanceof.ts(105,5) TS2721 `Cannot invoke an object which is possibly 'null'`; missing: uglify.js(9,7) TS2339 `Property 'val' does not exist on type '{}'`. layer `submoduleAccepted`. |
| `declarationEmitExpandoPropertyPrivateName.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> F8 span/width: the CODE and TEXT agree and only the anchor or squiggle length differs; layer `submoduleAccepted`. tsgo: ~~~~~~~~~~~ \| ours: ~~~~~ |
| `declarationFileNoCrashOnExtraExportModifier.errors.txt` | F6 top code differs (tsgo - / ours TS2300); layer `submoduleAccepted`. tsgo: ==== input.ts (1 errors) ==== \| ours: input.ts(6,14): error TS2300: Duplicate identifier 'Sub'. |
| `declareModifierOnImport1.js` | JS emit; layer `submoduleAccepted`. tsgo: (none) \| ours: var a = b; |
| `duplicateIdentifierRelatedSpans1.errors.txt` | F2-residue: the 6203-vs-6204 selection for an N-WAY CROSS-FILE duplicate. tsgo's `addDuplicateDeclarationError` (checker.go:14158) decides leading-vs-follow-on from the diagnostic's EXISTING related list — empty gives TS6203, non-empty TS6204 — and we decide it by index. That rule reproduces the ACTIVE `promiseDefinitionTest` / `recursiveComplicatedClasses` shape `[6203,6204,6204,…]` (ONE symbol with N declarations, so one call), and NOT the all-TS6203 shape these three want (N separate FILES, i.e. several merge calls accreting onto one diagnostic through `lookupOrIssueError`, which by that rule would give 6204 from the second on). tsgo's source and its own baselines do not reconcile here, so the rule must be read off the BASELINES; exposure is 54 active TS6203 and 7 active TS6204 rows. |
| `duplicateIdentifierRelatedSpans_moduleAugmentation.errors.txt` | F2-residue: the 6203-vs-6204 selection for an N-WAY CROSS-FILE duplicate. tsgo's `addDuplicateDeclarationError` (checker.go:14158) decides leading-vs-follow-on from the diagnostic's EXISTING related list — empty gives TS6203, non-empty TS6204 — and we decide it by index. That rule reproduces the ACTIVE `promiseDefinitionTest` / `recursiveComplicatedClasses` shape `[6203,6204,6204,…]` (ONE symbol with N declarations, so one call), and NOT the all-TS6203 shape these three want (N separate FILES, i.e. several merge calls accreting onto one diagnostic through `lookupOrIssueError`, which by that rule would give 6204 from the second on). tsgo's source and its own baselines do not reconcile here, so the rule must be read off the BASELINES; exposure is 54 active TS6203 and 7 active TS6204 rows. |
| `dynamicNamesErrors.errors.txt` | F2-residue: a LATE-BOUND computed member name (`[c0]`, a const-initialised key). (LEGACY.0b) step 8 gave the class and interface walkers tsgo's report-at-every-declaration rule, which closed the fixture's plain-name groups; these four rows are gated out one level earlier by `memberNameIsBinderVisible` (round 938, (CHK.5)(b)), read off pristine, where TS2300 is the BINDER's check and a late-bound key never reaches it. TypeScript 7 has a FOURTH TS2300 emitter for exactly this — `lateBindMember`, checker.go:15962 — so the gate, not the report-at-every-declaration rule, is what is left. Its blast radius is every computed member name, not this family. |
| `elidedJSImport1.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> type DISPLAY: the code, span and sentence agree and only a rendered TYPE differs; layer `submoduleAccepted`. tsgo: caller.js(2,8): error TS18042: 'TruffleContract' is a type and cannot be imported in JavaScript files. Use 'import("@truffle/contract")' in a JSDoc ty \| ours: caller.js(2,8): error TS18042: 'TruffleContract' is a type and cannot be imported in JavaScript files. Use 'import("@truffle/contract").TruffleContrac |
| `emitBOM.js` | JS emit; layer `submoduleAccepted`. tsgo: //// [emitBOM.js] \| ours: emitBOM.js(1,2): error TS1127: Invalid character. |
| `es6ExportEqualsInterop.errors.txt` | RECLASSIFIED (LEGACY.0b step 3) F6 -> pin-walker + type DISPLAY: this baseline's 31 diagnostics (the nine TS2497 rows included) are re-emitted VERBATIM by the dedicated walker `checkEs6ExportEqualsInteropPin`, so step 3's deletion of every TS2497 EMITTER does not reach it; closing the row means retiring that walker AND typing `import * as ns` of an `export = <fn>` module as its synthetic default. Layer `submoduleAccepted`. tsgo: main.ts(56,4): error TS2339: Property 'a' does not exist on type '{ default: () => any; }'. \| ours: same row on type '() => any', plus nine TS2497 rows tsgo does not have. |
| `esModuleInteropTslibHelpers.errors.txt` | F6 top code differs (tsgo TS2354 / ours -); layer `submoduleAccepted`. tsgo: file.ts(1,1): error TS2354: This syntax requires an imported helper but module 'tslib' cannot be found. \| ours: ==== file.ts (0 errors) ==== |
| `excessPropertyCheckWithUnions.errors.txt` | F6 top code differs (tsgo TS2353 / ours TS2322); layer `submoduleAccepted`. tsgo: excessPropertyCheckWithUnions.ts(64,9): error TS2353: Object literal may only specify known properties, and 'b' does not exist in type 'AN'. \| ours: excessPropertyCheckWithUnions.ts(64,9): error TS2322: Type '{ kind: "A"; n: { a: string; b: string; }; }' is not assignable to type 'AB'. |
| `expandoFunctionNestedAssigments.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> type DISPLAY: the code, span and sentence agree and only a rendered TYPE differs; layer `submodule`. tsgo: expandoFunctionNestedAssigments.ts(7,23): error TS2339: Property 'inNestedFunction' does not exist on type '{ (): void; inVariableInit: number; bla: { \| ours: expandoFunctionNestedAssigments.ts(7,23): error TS2339: Property 'inNestedFunction' does not exist on type 'typeof Foo'. |
| `exportAsNamespace_augment.errors.txt` | F2-residue: the 6203-vs-6204 selection for an N-WAY CROSS-FILE duplicate. tsgo's `addDuplicateDeclarationError` (checker.go:14158) decides leading-vs-follow-on from the diagnostic's EXISTING related list — empty gives TS6203, non-empty TS6204 — and we decide it by index. That rule reproduces the ACTIVE `promiseDefinitionTest` / `recursiveComplicatedClasses` shape `[6203,6204,6204,…]` (ONE symbol with N declarations, so one call), and NOT the all-TS6203 shape these three want (N separate FILES, i.e. several merge calls accreting onto one diagnostic through `lookupOrIssueError`, which by that rule would give 6204 from the second on). tsgo's source and its own baselines do not reconcile here, so the rule must be read off the BASELINES; exposure is 54 active TS6203 and 7 active TS6204 rows. |
| `exportAssignmentMembersVisibleInAugmentation.errors.txt` | F6 top code differs (tsgo TS4060 / ours TS2304,TS2664); layer `submoduleTriaged`. tsgo: /a.ts(3,26): error TS4060: Return type of exported function has or is using private name 'T'. \| ours: /a.ts(2,16): error TS2664: Invalid module name in augmentation, module 'foo' cannot be found. |
| `expressionWithJSDocTypeArguments.errors.txt` | F6 top code differs (tsgo TS1110 / ours TS17019,TS17020,TS8020); layer `submoduleAccepted`. tsgo: expressionWithJSDocTypeArguments.ts(9,22): error TS1110: Type expected. \| ours: expressionWithJSDocTypeArguments.ts(9,21): error TS8020: JSDoc types can only be used inside documentation comments. |
| `gettersAndSettersErrors.js` | JS emit; layer `submoduleAccepted`. tsgo: set Goo(v) { } // error - setters must not specify a return type \| ours: set Goo(v): string { } // error - setters must not specify a return type |
| `importAssertionsDeprecatedIgnored.errors.txt` | F1 tsgo REPORTS where we are silent (a NEW errors baseline); layer `submoduleAccepted`. tsgo: /a.ts(2,35): error TS2880: Import assertions have been replaced by import attributes. Use 'with' instead of 'assert'. \| ours: nothing |
| `importDeclWithDeclareModifier.js` | JS emit; layer `submoduleAccepted`. tsgo: export {}; \| ours: export var a = x.c; |
| `importDeclWithExportModifierAndExportAssignment.js` | JS emit; layer `submoduleAccepted`. tsgo: Object.defineProperty(exports, "__esModule", { value: true }); \| ours: module.exports = x; |
| `importTypeAssertionDeprecation.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> F8 span/width: the CODE and TEXT agree and only the anchor or squiggle length differs; layer `submoduleAccepted`. tsgo: /main.ts(1,30): error TS2880: Import assertions have been replaced by import attributes. Use 'with' instead of 'assert'. \| ours: /main.ts(1,38): error TS2880: Import assertions have been replaced by import attributes. Use 'with' instead of 'assert'. |
| `importTypeAssertionDeprecationIgnored.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> F8 span/width: the CODE and TEXT agree and only the anchor or squiggle length differs; layer `submoduleAccepted`. tsgo: /main.ts(2,30): error TS2880: Import assertions have been replaced by import attributes. Use 'with' instead of 'assert'. \| ours: /main.ts(2,38): error TS2880: Import assertions have been replaced by import attributes. Use 'with' instead of 'assert'. |
| `incompatibleExports1.errors.txt` | F6 top code differs (tsgo - / ours TS2309); layer `submoduleAccepted`. tsgo: ==== incompatibleExports1.ts (1 errors) ==== \| ours: incompatibleExports1.ts(4,5): error TS2309: An export assignment cannot be used in a module with other exported elements. |
| `incorrectRecursiveMappedTypeConstraint.errors.txt` | F0 rows tsgo emits that ours does not; layer `submoduleTriaged`. tsgo: !!! related TS2751 incorrectRecursiveMappedTypeConstraint.ts:3:10: Circularity originates in type at this location. \| ours: nothing |
| `intTypeCheck.errors.txt` | F6 top code differs (tsgo TS2322 / ours TS2696); layer `submoduleAccepted`. tsgo: intTypeCheck.ts(99,5): error TS2322: Type 'Object' is not assignable to type 'i1'. \| ours: intTypeCheck.ts(99,5): error TS2696: The 'Object' type is assignable to very few other types. Did you mean to use the 'any' type instead? |
| `interfaceMergeWithNonGenericTypeArguments.errors.txt` | F6 top code differs (tsgo - / ours TS2346); layer `submoduleAccepted`. tsgo: ==== interfaceMergeWithNonGenericTypeArguments.ts (1 errors) ==== \| ours: interfaceMergeWithNonGenericTypeArguments.ts(6,3): error TS2346: Call target does not contain any signatures. |
| `invariantGenericErrorElaboration.errors.txt` | F7 diagnostic COUNT changed; layer `submoduleAccepted`. tsgo: Type 'Constraint<Runtype<any>>' is not assignable to type 'Constraint<Num>'. \| ours: Property 'tag' is missing in type 'Runtype<any>' but required in type 'Num'. |
| `isolatedDeclarationsAddUndefined.errors.txt` | F6 top code differs (tsgo TS9025 / ours TS9011); layer `submoduleAccepted`. tsgo: file2.ts(4,27): error TS9025: Declaration emit for this parameter requires implicitly adding undefined to its type. This is not supported with --isola \| ours: file2.ts(4,38): error TS9011: Parameter must have an explicit type annotation with --isolatedDeclarations. |
| `isolatedDeclarationsAllowJs.errors.txt` | F6 top code differs (tsgo TS9010 / ours -); layer `submodule`. tsgo: file2.js(1,12): error TS9010: Variable must have an explicit type annotation with --isolatedDeclarations. \| ours: ==== file2.js (0 errors) ==== |
| `jsDeclarationEmitExportedClassWithExtends.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> source-echo PATH: the annotated-source header spells a doubled separator; layer `submoduleAccepted`. tsgo: ==== node_modules/lit-element/development/lit-element.d.ts (0 errors) ==== \| ours: ==== node_modules/lit-element/development//lit-element.d.ts (0 errors) ==== |
| `jsEnumCrossFileExport.errors.txt` | F1 tsgo REPORTS where we are silent (a NEW errors baseline); layer `submodule`. tsgo: enumDef.js(14,20): error TS1003: Identifier expected. \| ours: nothing |
| `jsEnumTagOnObjectFrozen.errors.txt` | F6 top code differs (tsgo TS2749 / ours -); layer `submodule`. tsgo: index.js(17,16): error TS2749: 'Thing' refers to a value, but is being used as a type here. Did you mean 'typeof Thing'? \| ours: ==== index.js (1 errors) ==== |
| `jsExpandoObjectDefineProperty.errors.txt` | F1 tsgo REPORTS where we are silent (a NEW errors baseline); layer `submodule`. tsgo: index.js(3,17): error TS2339: Property 'inspectedWindow' does not exist on type '{}'. \| ours: nothing |
| `jsExportAssignmentNonMutableLocation.errors.txt` | F1 tsgo REPORTS where we are silent (a NEW errors baseline); layer `submodule`. tsgo: file.js(4,1): error TS2309: An export assignment cannot be used in a module with other exported elements. \| ours: nothing |
| `jsExportMemberMergedWithModuleAugmentation.errors.txt` | F6 top code differs (tsgo TS2671,TS2749 / ours TS2741); layer `submodule`. tsgo: /index.ts(3,16): error TS2671: Cannot augment module './test' because it resolves to a non-module entity. \| ours: /index.ts(11,7): error TS2741: Property 'x' is missing in type '{ b: string; }' but required in type 'Abcde'. |
| `jsExportMemberMergedWithModuleAugmentation2.errors.txt` | F6 top code differs (tsgo TS2671 / ours TS2300); layer `submodule`. tsgo: /index.ts(3,16): error TS2671: Cannot augment module './test' because it resolves to a non-module entity. \| ours: /index.ts(4,16): error TS2300: Duplicate identifier 'a'. |
| `jsExportMemberMergedWithModuleAugmentation3.errors.txt` | F1 tsgo REPORTS where we are silent (a NEW errors baseline); layer `submodule`. tsgo: /x.js(1,16): error TS2339: Property 'x' does not exist on type 'typeof import("/y")'. \| ours: nothing |
| `jsExtendsImplicitAny.errors.txt` | F6 top code differs (tsgo TS8026 / ours TS2314); layer `submodule`. tsgo: /b.js(5,17): error TS8026: Expected A<T> type arguments; provide these with an '@extends' tag. \| ours: /b.js(4,15): error TS2314: Generic type 'A<T>' requires 1 type argument(s). |
| `jsFileCompilationBindDeepExportsAssignment.errors.txt` | F6 top code differs (tsgo TS2304 / ours TS2339); layer `submodule`. tsgo: a.js(1,1): error TS2304: Cannot find name 'exports'. \| ours: a.js(1,9): error TS2339: Property 'a' does not exist on type 'typeof import("a")'. |
| `jsdocFunctionClassPropertiesDeclaration.errors.txt` | TS2683-residue: the three TS2683 rows and the TS7009 row are CORRECT since (LEGACY.0b step 7); the residue is that a JSDoc `@param {number \| undefined} x` tag does not TYPE the parameter it names, so we add three ours-only rows tsgo does not have — /a.js(5,21) and (5,24) TS7006 `Parameter 'x'/'y' implicitly has an 'any' type.` plus /a.js(5,17) TS7023 `'Foo' implicitly has return type 'any'…`. JSDoc parameter typing is its own family. layer `submodule`. |
| `jsdocIllegalTags.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> F8 span/width: the CODE and TEXT agree and only the anchor or squiggle length differs; layer `submodule`. tsgo: /a.js(2,9): error TS1092: Type parameters cannot appear on a constructor declaration. \| ours: /a.js(2,19): error TS1092: Type parameters cannot appear on a constructor declaration. |
| `jsdocImportTypeNodeNamespace.errors.txt` | F6 top code differs (tsgo TS2694 / ours TS2352); layer `submodule`. tsgo: Main.js(2,49): error TS2694: Namespace '"GeometryType"' has no exported member 'default'. \| ours: Main.js(2,21): error TS2352: Conversion of type 'string' to type 'typeof _default' may be a mistake because neither type sufficiently overlaps with th |
| `jsdocParameterParsingInfiniteLoop.errors.txt` | F6 top code differs (tsgo TS1005 / ours TS1110,TS2304,TS7014); layer `submodule`. tsgo: example.js(3,19): error TS1005: '}' expected. \| ours: example.js(3,11): error TS7014: Function type, which lacks return-type annotation, implicitly has an 'any' return type. |
| `jsdocRestParameter.errors.txt` | F6 top code differs (tsgo TS2554 / ours TS2345); layer `submodule`. tsgo: /a.js(8,6): error TS2554: Expected 1 arguments, but got 2. \| ours: /a.js(7,3): error TS2345: Argument of type 'number[]' is not assignable to parameter of type 'number'. |
| `jsdocTypeNongenericInstantiationAttempt.errors.txt` | F6 top code differs (tsgo TS2749 / ours -); layer `submodule`. tsgo: index8.js(4,12): error TS2749: 'fn' refers to a value, but is being used as a type here. Did you mean 'typeof fn'? \| ours: ==== index8.js (1 errors) ==== |
| `jsdocTypedefNoCrash.errors.txt` | F1 tsgo REPORTS where we are silent (a NEW errors baseline); layer `submodule`. tsgo: export.js(3,5): error TS1003: Identifier expected. \| ours: nothing |
| `jsdocTypedefNoCrash2.errors.txt` | F6 top code differs (tsgo TS1003 / ours TS2451); layer `submodule`. tsgo: export.js(4,5): error TS1003: Identifier expected. \| ours: export.js(1,13): error TS2451: Cannot redeclare block-scoped variable 'foo'. |
| `maximum10SpellingSuggestions.errors.txt` | F6 top code differs (tsgo TS2552 / ours TS2304); layer `submoduleAccepted`. tsgo: maximum10SpellingSuggestions.ts(5,1): error TS2552: Cannot find name 'bob'. Did you mean 'blob'? \| ours: maximum10SpellingSuggestions.ts(5,1): error TS2304: Cannot find name 'bob'. |
| `methodSignatureHandledDeclarationKindForSymbol.errors.txt` | F2-residue: a CROSS-DECLARATION interface MERGE (`interface Foo` declared twice), so it is served by `checkCrossInterfacePropertyConflict` and not by either walker (LEGACY.0b) step 8 changed. tsgo reports TS2300 at BOTH `bold(): string` and `bold: string` and NO TS2717, for the step-8 reason one function over: method-vs-property is a binder merge CONFLICT, so the property gets a fresh symbol and `checkVariableLikeDeclaration`'s secondary-declaration branch never runs. We emit the TS2717 and neither TS2300. The merge path is otherwise CORRECT (property-vs-property across two declarations is TS2717 alone in tsgo too — measured), so the delta is the differing-KIND case alone. |
| `misspelledJsDocTypedefTags.errors.txt` | F1 tsgo REPORTS where we are silent (a NEW errors baseline); layer `submodule`. tsgo: a.js(4,59): error TS1003: Identifier expected. \| ours: nothing |
| `mixinPrivateAndProtected.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> type DISPLAY: the code, span and sentence agree and only a rendered TYPE differs; layer `submoduleAccepted`. tsgo: The intersection 'mixB.(Anonymous class) & A' was reduced to 'never' because property 'pvt' exists in multiple constituents and is private in some. \| ours: The intersection 'mixB<typeof A>.(Anonymous class) & A' was reduced to 'never' because property 'pvt' exists in multiple constituents and is private i |
| `moduleElementsInWrongContext.js` | JS emit; layer `submoduleAccepted`. tsgo: var I = M; \| ours: nothing |
| `moduleElementsInWrongContext2.js` | JS emit; layer `submoduleAccepted`. tsgo: var I = M; \| ours: nothing |
| `module_augmentExistingVariable.js` | JS emit; layer `submoduleAccepted`. tsgo: (none) \| ours: var console; |
| `mutuallyRecursiveCallbacks.errors.txt` | F7 diagnostic COUNT changed; layer `submoduleAccepted`. tsgo: Type 'Foo<unknown>' is not assignable to type 'Bar<{}>'. \| ours: Types of parameters 'bar' and 'foo' are incompatible. |
| `nameCollisions.js` | JS emit; layer `submoduleAccepted`. tsgo: (none) \| ours: let x; |
| `namespaceDisambiguationInUnion.errors.txt` | RECLASSIFIED (LEGACY.0b step 9) ORDER -> CHAIN-PICKER: the union's own display is now CORRECT (`Foo.Yep \| Bar.Yep`, verified) and the whole residue is one chain sub-line — tsgo names `"bar.yep"` and we name `"foo.yep"`. tsc's `typeRelatedToSomeType` reports a union TARGET with no discriminant match against its LAST constituent; the var-decl chain here picks the first. `findBestUnionConstituent` already keeps the LAST on a tie ((LEGACY.0a)), so this chain does not go through it — that is the gap. |
| `nestedGlobalNamespaceInClass.js` | JS emit; layer `submoduleAccepted`. tsgo: (none) \| ours: var global; |
| `noInferUnionExcessPropertyCheck1.errors.txt` | ORDER-model (re-measured (LEGACY.0b) step 9): the residue is the order of two ANONYMOUS constituents — a function type and an object type — and their DECLARATION positions give the opposite of tsgo's answer (row 23 is `(() => { x: string; }) \| { x: string; }` where the object's declaration, `T`'s constraint, is the EARLIER node), so it is not `compareSymbols`. Rows 7/15 need the other half: `NoInfer<T>` is a `Substitution` type in tsc (bit 24, after `Object`'s bit 20) where this model represents it as its own argument with an alias display. NOT served by the TS2353 walker step 9 ordered — a `FunctionType` constituent makes that one bail. |
| `noParameterReassignmentIIFEAnnotated.errors.txt` | JSDoc: an OURS-ONLY TS8029. (LEGACY.0b) F6a is CLOSED for this row -- its TS2740 leaf now matches tsgo byte for byte -- and what is left is a different family: `@param {...unknown} rest` on a zero-parameter function that reads `arguments`. A VARIADIC JSDoc param IS an array type, so tsc's `It would match 'arguments' if it had an array type` rung must not fire; our emitter does not test the tag's variadic-ness. tsgo reports nothing here. Layer `submoduleAccepted`. tsgo: index.js(6,42): error TS2740 (which we now match) \| ours: that row PLUS index.js(3,28): error TS8029: JSDoc '@param' tag has name 'rest', but there is no parameter with that name. |
| `nodeNextPackageSelfNameWithOutDir.errors.txt` | F6 top code differs (tsgo TS2307 / ours -); layer `submoduleAccepted`. tsgo: index.ts(1,21): error TS2307: Cannot find module '@this/package' or its corresponding type declarations. \| ours: ==== index.ts (0 errors) ==== |
| `nodeNextPackageSelfNameWithOutDirDeclDir.errors.txt` | F6 top code differs (tsgo TS2307 / ours -); layer `submoduleAccepted`. tsgo: index.ts(1,21): error TS2307: Cannot find module '@this/package' or its corresponding type declarations. \| ours: ==== index.ts (0 errors) ==== |
| `overloadOnConstNoAnyImplementation2.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> type DISPLAY: the code, span and sentence agree and only a rendered TYPE differs; layer `submoduleAccepted`. tsgo: overloadOnConstNoAnyImplementation2.ts(18,9): error TS2345: Argument of type '(x: 'bye') => number' is not assignable to parameter of type '(x: "hi") \| ours: overloadOnConstNoAnyImplementation2.ts(18,9): error TS2345: Argument of type '(x: "bye") => number' is not assignable to parameter of type '(x: "hi") |
| `overloadOnConstNoStringImplementation2.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> type DISPLAY: the code, span and sentence agree and only a rendered TYPE differs; layer `submoduleAccepted`. tsgo: overloadOnConstNoStringImplementation2.ts(18,9): error TS2345: Argument of type '(x: 'bye') => number' is not assignable to parameter of type '(x: "hi \| ours: overloadOnConstNoStringImplementation2.ts(18,9): error TS2345: Argument of type '(x: "bye") => number' is not assignable to parameter of type '(x: "hi |
| `parameterPropertyInConstructor2.errors.txt` | F2-residue: a constructor PARAMETER PROPERTY in an OVERLOAD signature. tsgo puts parameter properties in the same per-container name table as ordinary members (`checkPropertyOrAccessor(param, 1, false)`) and walks EVERY constructor including body-less overloads, so `constructor(public names: string);` at (3,24) and its implementation's `public names` at (4,24) are both TS2300. `checkDuplicateClassMembers` has no Constructor arm at all — the (4,24) row we do emit comes from another site — so closing this means MERGING parameter properties into that table, which also makes `{ p: number; constructor(public p: string) }` TS2300-at-both plus TS2403 (measured), i.e. a change with its own blast radius and its own double-emission question. |
| `pathsValidation5.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> summary ORDER; re-confirmed at step 9 that it is NOT a union order and shares no mechanism with the ORDER family: the TS5090 wording matches and the ONLY difference is where a `tsconfig.json` row sorts against a source file's in the summary — tsgo lists `src/main.ts(1,8): TS2882` FIRST and we list it last. Changing it reorders the summary of every multi-file baseline, so it needs its own round. |
| `prettyContextNotDebugAssertion.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> F8 span/width: the CODE and TEXT agree and only the anchor or squiggle length differs; layer `submoduleAccepted`. tsgo: ?[7m ?[0m ?[91m~?[0m \| ours: ?[7m ?[0m ?[91m?[0m |
| `prettyFileWithErrorsAndTabs.errors.txt` | ours emits EXTRA rows tsgo does not; layer `submoduleAccepted`. tsgo: !!! error TS2322: Type 'number' is not assignable to type 'string'. \| ours: nothing |
| `pushTypeGetTypeOfAlias.errors.txt` | F6 top code differs (tsgo TS2309 / ours TS2303); layer `submodule`. tsgo: bar.js(1,1): error TS2309: An export assignment cannot be used in a module with other exported elements. \| ours: bar.js(2,1): error TS2303: Circular definition of import alias 'blah'. |
| `readonlyTupleAndArrayElaboration.errors.txt` | F6 top code differs (tsgo TS4104 / ours TS2345); layer `submoduleAccepted`. tsgo: readonlyTupleAndArrayElaboration.ts(10,20): error TS4104: The type 'readonly [3, 4]' is 'readonly' and cannot be assigned to the mutable type '[number \| ours: readonlyTupleAndArrayElaboration.ts(10,20): error TS2345: Argument of type 'readonly [3, 4]' is not assignable to parameter of type '[number, number]' |
| `recursivelyExpandingUnionNoStackoverflow.errors.txt` | F6 top code differs (tsgo - / ours TS2589); layer `submoduleAccepted`. tsgo: ==== recursivelyExpandingUnionNoStackoverflow.ts (1 errors) ==== \| ours: recursivelyExpandingUnionNoStackoverflow.ts(3,10): error TS2589: Type instantiation is excessively deep and possibly infinite. |
| `regularExpressionCharacterClassRangeOrder.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> F8 span/width: the CODE and TEXT agree and only the anchor or squiggle length differs; layer `submoduleAccepted`. tsgo: regularExpressionCharacterClassRangeOrder.ts(7,4): error TS1517: Range out of order in character class. \| ours: regularExpressionCharacterClassRangeOrder.ts(7,5): error TS1517: Range out of order in character class. |
| `regularExpressionWithNonBMPFlags.errors.txt` | RECLASSIFIED (LEGACY.0b step 2) F9 -> F8 span/width: the CODE and TEXT agree and only the anchor or squiggle length differs; layer `submoduleAccepted`. tsgo: ~ \| ours: ~~ |
| `reverseMappedTypeIntersectionConstraint.errors.txt` | ORDER-model — and NOT a union order at all (re-measured (LEGACY.0b) step 9): all four rows are the MEMBER order inside one anonymous object display, and every one of them is tsgo's alphabetical-by-property-name (`{ anotherField; field }`, `{ nested; prop }`, `{ invoke; types }`). A reverse-mapped type's members carry no declarations in tsc and so list by NAME; ours carry the source literal's declarations and list by position. Needs a reverse-mapped MARK on the type — sorting every anonymous object's members by name is a whole-corpus change. |
| `sourceMapValidationVarInDownLevelGenerator(target=es2015).errors.txt` | F6 top code differs (tsgo TS5102 / ours TS5101); layer `submoduleAccepted`. tsgo: error TS5102: Option 'downlevelIteration' has been removed. Please remove it from your configuration. \| ours: error TS5101: Option 'downlevelIteration' is deprecated and will stop functioning in TypeScript 7.0. Specify compilerOption '"ignoreDeprecations": "6. |
| `thisInObjectJs.js` | JS emit; layer `submoduleAccepted`. tsgo: export {}; \| ours: export {}; |
| `tslibMissingHelper.errors.txt` | F6 top code differs (tsgo TS2343 / ours -); layer `submoduleAccepted`. tsgo: /package2/index.ts(2,16): error TS2343: This syntax requires an imported helper named '__awaiter' which does not exist in 'tslib'. Consider upgrading \| ours: ==== /package2/index.ts (0 errors) ==== |
| `tslibMultipleMissingHelper.errors.txt` | F6 top code differs (tsgo TS2343 / ours -); layer `submoduleAccepted`. tsgo: /package1/other.ts(2,23): error TS2343: This syntax requires an imported helper named '__awaiter' which does not exist in 'tslib'. Consider upgrading \| ours: ==== /package1/other.ts (1 errors) ==== |
| `typeParameterDiamond4.errors.txt` | ORDER-model (measured (LEGACY.0b) step 9): `T \| Top \| U`. NOT "type parameters cannot be ordered" — the comparator orders a type-parameter union correctly inside ONE function scope (`Zed \| Alpha` renders `Alpha \| Zed`, byte-identical to tsgo, in both written orders). The variable is an ENCLOSING function's type parameter: this display follows the WRITTEN annotation order exactly (rewriting the fixture as `U \| T \| Top` renders `U \| T \| Top`), and the same union is degraded enough elsewhere that the ordinary var-decl reader emits NOTHING for `Zed \| Alpha` when `Zed` comes from an enclosing scope. So the ORDER row sits on a RESOLUTION gap, not on the comparator. |
| `typeParameterWithInvalidConstraintType.errors.txt` | F0 rows tsgo emits that ours does not; layer `submoduleTriaged`. tsgo: !!! related TS2751 typeParameterWithInvalidConstraintType.ts:4:17: Circularity originates in type at this location. \| ours: nothing |
| `unicodeEscapesInNames02(target=es2015).errors.txt` | RECLASSIFIED (LEGACY.0b step 3) F8 span -> baseline-FORMATTER column counting: the TS1127 span itself is now TypeScript 7's one character and the reported (line,column) of all four rows is byte-correct; what still differs is where the `~` PRINTS. The annotated source line holds an ASTRAL character (`_?` style, U+102A7), and the reference's baseline formatter pads the squiggle line by CODEPOINT where `BaselineFormatter` pads by UTF-16 unit, so every squiggle after the astral character sits one column right — the TS2305 rows shift with it. A formatter change, not a diagnostic one, and it reaches every baseline whose annotated source carries a surrogate pair; layer `submoduleAccepted`. tsgo squiggle: ` ~` \| ours: ` ~`. |
| `uniqueSymbolJs.errors.txt` | F6 top code differs (tsgo TS1268,TS2749 / ours TS1337); layer `submodule`. tsgo: a.js(5,18): error TS1268: An index signature parameter type must be 'string', 'number', 'symbol', or a template literal type. \| ours: a.js(5,18): error TS1337: An index signature parameter type cannot be a literal type or generic type. Consider using a mapped object type instead. |
| `unusedTypeParameters_templateTag2.errors.txt` | F6 top code differs (tsgo TS2339 / ours TS6133); layer `submodule`. tsgo: /a.js(2,3): error TS6205: All type parameters are unused. \| ours: /a.js(3,4): error TS6133: 'V' is declared but its value is never read. |
| `unusedVariablesWithUnderscoreInBindingElement.errors.txt` | F6b unused-local GROUPING ((LEGACY.0b) step 3 diagnosis, NOT the ANCHOR half it landed): TypeScript 7 groups an ARRAY binding pattern into TS6198 as it does an OBJECT one and RECURSES into nested patterns, so one outer row replaces every inner one; an `_` element counts as USED unless it is an object-pattern shorthand (checker.go `reportUnusedBindingElements` / `isUnreferencedVariableDeclaration`). ORIGINAL: F6 top code differs (tsgo - / ours TS6133); layer `submoduleAccepted`. tsgo: unusedVariablesWithUnderscoreInBindingElement.ts(14,11): error TS6198: All destructured elements are unused. \| ours: unusedVariablesWithUnderscoreInBindingElement.ts(14,12): error TS6133: 'a3' is declared but its value is never read. |
| `unusedVariablesWithUnderscoreInForOfLoop.errors.txt` | F6b unused-local GROUPING ((LEGACY.0b) step 3 diagnosis, NOT the ANCHOR half it landed): TypeScript 7 groups an ARRAY binding pattern into TS6198 as it does an OBJECT one and RECURSES into nested patterns, so one outer row replaces every inner one; an `_` element counts as USED unless it is an object-pattern shorthand (checker.go `reportUnusedBindingElements` / `isUnreferencedVariableDeclaration`). ORIGINAL: F6 top code differs (tsgo TS6198 / ours TS6133); layer `submoduleAccepted`. tsgo: unusedVariablesWithUnderscoreInForOfLoop.ts(19,16): error TS6198: All destructured elements are unused. \| ours: unusedVariablesWithUnderscoreInForOfLoop.ts(19,17): error TS6133: 'a' is declared but its value is never read. |

**103 baseline(s) pending.**
<!-- END GENERATED TSGO-PENDING -->
