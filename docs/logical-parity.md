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
_No baseline is currently switched off under the logical-parity policy._
<!-- END GENERATED LEDGER -->
