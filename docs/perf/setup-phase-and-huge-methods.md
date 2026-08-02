# (SETUP.1) the last unnamed region, and (JIT.1) the methods HotSpot never compiles

*Round 802. Tenth in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → `call-expression-attribution.md` (734) →
`argument-check-attribution.md` (735) → `narrow-walk-attribution.md` (736) →
`type-of-expression-attribution.md` (737) → `var-decl-attribution.md` (738) →
`front-end-attribution.md` (738 part 2) → `property-access-attribution.md`
(787–795) → `implicit-any-attribution.md` (798–800) → `bind-attribution.md`
(801) → here.*

> **HEADLINE — two results, and the second one is the larger.**
>
> **1. `outside-pass` is one function.** The 975 ms of checker-init that sat in
> no `pass()` wrapper — the last region in the compile that had never been
> named — is the ~15 setup statements at the top of `Checker.init`. Wrapped,
> the residue falls **975 → 144 ms**, and **`buildFileLocalTypeMaps` alone is
> 636 ms = 65% of the phase and 2.2% of the compile**: an eager
> `getTypeOfSymbol` over every file-level declaration in the program. There is
> **no lever landed here** — the honest verdict is a sized follow-up, because
> `getTypeOfSymbol` memoises and round 788's law says a deferral MOVES that
> work unless the symbols are never asked for at all.
>
> **2. Nineteen methods exceed HotSpot's `HugeMethodLimit` and are therefore
> NEVER JIT-COMPILED — and they include the five largest measured costs in the
> compiler AND `forEachChild`.** `checkMemberAccessMissingCore` is **46,567
> bytecodes**, 5.8× the 8,000-byte limit;
> `checkArgumentsAgainstSignatureCore` 23,890;
> `checkVarDeclAssignabilityCore` 19,296; `checkAssignmentExpressionCore`
> 18,100; `checkSingleCallExpressionTypesCore` 15,567; and the traversal
> primitive `forEachChild` 9,750. Running the profile with
> `-XX:-DontCompileHugeMethods` measures **−3.1%, B wins 4/4 pairs**, output
> identical at 46 errors. That is larger than everything rounds 798–801 landed
> combined, it needed no compiler change to find, and the *shippable* form of
> it is a mechanical split of thirteen functions.

---

## 1. `outside-pass` — what it was

`--passTiming` prints `checker-init total: X; N passes recorded, sum Y,
outside-pass X−Y`. That residue had been printed for ~300 rounds and never
attributed; round 801 finally quoted it (975 ms, 3.4% of the compile) as one of
the two regions left unopened.

It is not mysterious once looked at: `Checker.init` opens with ~15 setup
statements — the lib/global merges, the per-file visibility and scope tables,
the enum-value and import-reference passes, the file-local type maps — and only
then starts the `pass(...)`-wrapped dispatch. Two diagnostic retractions at the
very end of `init` are outside as well.

**The instrument is the wrapper itself.** Each statement is now
`pass("init:<name>") { … }`, which makes the partition **exhaustive by
construction** — the same property round 801's bind partition had, and for the
same reason: there is nothing to calibrate, because the residue is still
printed and must stay ~0. The cost is ~16 extra lambda invocations per compile
when instrumentation is off.

**Compiler profile, median of 3 probe-free `--passTiming` runs** (daemons
stopped inside the measuring script; checker-init 24,688 / 24,348 / 24,495 ms,
i.e. within 1.3% of round 801's 24,806 — the box was quiet):

| row | ms (median) | share of the phase |
|---|---:|---:|
| **`init:buildFileLocalTypeMaps`** | **636** | **65%** |
| `init:trackAllImportReferences` | 89 | 9.1% |
| `init:computeAllEnumValues` | 36 | 3.7% |
| `init:computePerFileVisibility` | 10 | 1.0% |
| `init:buildPerFileScopes` | 7 | 0.7% |
| `init:mergeFileLocalsIntoGlobals` | 5 | 0.5% |
| `init:moduleTypeNameIndex` | 1.9 | |
| `init:collectUmdGlobalsAndModuleFiles` | 1.4 | |
| `init:evolvingArrayUseSiteWalks` | 0.5 | |
| `init:mergeModuleAugmentations` | 0.5 | |
| `init:wireGlobalArrayTypes` | 0.4 | |
| `init:mergeLibGlobals` | 0.3 | |
| `init:mergeSharedKeepNames`, `init:snapshotPreAugGlobalKeys`, `init:flowDisabledTs2454Retraction`, `init:tpTargetReturnDedup` | ≤ 0.1 each | |
| **residual `outside-pass`** | **144** | 15% |
| **total** | **~925** | (round 801 measured the whole row at 975) |

**Three readings.**

1. **The row is one function.** `buildFileLocalTypeMaps` is 636 ms — bigger
   than the whole of `bindLexicalScopes` (470) and more than every lever rounds
   798–801 landed put together. It is also the ONLY setup pass that does any
   type-system work (80 `getTypeOfExpression` calls attributed to it; every
   other setup pass records zero, which is pinned).
2. **Nothing else in the phase is worth a round.** Second place is 89 ms
   (0.3%), and eleven of the sixteen rows are under 2 ms. The phase is one
   eager type resolution and fourteen table builds.
3. **The 144 ms residue is now the bounded unknown**, down from 975. It is the
   `try`/`if` scaffolding of the init block plus the `pass()` machinery over 417
   dispatches, and at 0.5% of the compile it is below the ±2% band — recorded
   as a bound, not chased.

## 2. Why no lever was landed on the 636 ms

`buildFileLocalTypeMaps` calls `getTypeOfSymbol` for every file-level symbol
that is a function / class / interface / enum / type alias / import alias, plus
every annotated variable, in every file of the program. The obvious move is to
defer it — the map is a *lookup table for later passes*, exactly the "state
computed for a reader that may never come" shape rounds 788/798/800 have
mined three times.

**It is exactly the shape round 788's law refuses to let anyone price by
counting.** `getTypeOfSymbol` **memoises into `symbolTypes`**, so a deferral
does not delete the resolution — it moves it to whichever pass asks first. The
recoverable part is only the symbols that are **never asked at all**, and that
number is unmeasured. Round 801 lost a lever to precisely this (the suffix set:
row 53.5 → 0.9 ms, and then `created 1143, materialized 1143`).

So the queue item written for it (**(SETUP.2)**) states the measurement that
must come first — a `calls`-vs-`distinct` census over reads of `fileLocalTypes`
— and explicitly forbids pricing the deferral before that number exists.
Round 800's test is the model: `distinct` must fall FASTER than `calls` for the
work to be deleted rather than moved.

## 3. (JIT.1) — the methods HotSpot refuses to compile

### 3.1 The mechanism, and why no profile has ever shown it

HotSpot's `DontCompileHugeMethods` is a **product flag defaulting to `true`**,
and `HugeMethodLimit` is **8,000 bytecodes**. A method above that size is never
compiled by C1 or C2. It runs **in the interpreter for the entire process**, no
matter how hot it becomes.

This is invisible to every instrument this arc has used:

* `-XX:+PrintCompilation` prints **no** "too large" line — the compile is never
  *proposed*, so it is never *skipped*. Round 802 grepped an 11,796-line
  `PrintCompilation` log for `too large` and got **0**. **That grep is a dead
  control and is reported as dead**, exactly as round 801 reported
  `--flowScanBogus`: the absence of the string is not evidence of absence of the
  effect.
* A JFR profile attributes the cost to the method's **callees**, which are
  compiled — so the parent shows as a flat smear. § 1 of this document's
  predecessor calls the profile "flat" and reads that as "the cost is a
  multiplier, not a hotspot". A permanently-interpreted 46,000-byte method is
  one concrete mechanism for exactly that signature.
* Round 734 *did* suspect this and checked ONE function
  (`checkSingleCallExpressionTypesCore`'s core, 3,587 bytecodes — under the
  limit) and wrote down "check the bytecode size before theorising about the
  JIT". It never ran the census over the other 5,150 methods.

### 3.2 The census — static, deterministic, no run required

`scripts/huge_methods.py` (landed this round) parses `javap -c -p` over every
class and reports the last bytecode offset per method, which is what HotSpot
compares against the limit. Only real opcode lines are counted: a naive parse
picks up `lookupswitch` key lines and reports sizes in the billions.

**Over the limit: 19 of 13,910 methods across 578 classes.**

| bytecodes | class :: method | what it is |
|---:|---|---|
| **46,567** | `Checker :: checkMemberAccessMissingCore` | round 789's "largest leaf in the compile" |
| 28,991 | `Transformer :: transformToCommonJS` | emit mode only |
| **23,890** | `Checker :: checkArgumentsAgainstSignatureCore` | rounds 735/796/797's target |
| 21,535 | `TypeScriptCompiler :: compileParsedCore` | once per compile — irrelevant |
| **19,296** | `Checker :: checkVarDeclAssignabilityCore` | § 0.1's named (TYPE.2) |
| **18,100** | `Checker :: checkAssignmentExpressionCore` | (ENGINE.1)'s target |
| 16,233 | `Transformer :: transformClassBody` | emit mode only |
| **15,567** | `Checker :: checkSingleCallExpressionTypesCore` | (ENGINE.2)'s target |
| 13,694 | `CompilerOptionsKt :: applyDirective` | per compile — irrelevant |
| 12,935 | `Checker :: checkDuplicateDeclarations` | |
| 11,930 | `Checker :: tryInferSingleTypeParamFromArgs` | |
| 11,298 | `Checker :: <init>` | once — but it *contains* the whole dispatch |
| 10,928 | `Checker :: checkIndexSigInStatement` | |
| 10,339 | `Checker :: access$checkBigintPropertyNames$emit` | |
| **9,750** | **`NodeWalkKt :: forEachChild`** | **the traversal primitive of the entire compiler** |
| 9,743 | `Checker :: checkReturnAssignabilityCore` | |
| **9,062** | `Checker :: checkPropertyAccessInExpr` | the biggest spine-leave handler's payload |
| 8,934 | `Transformer :: transform` | emit mode only |
| **8,686** | `Checker :: ccetSpineEnter` | |

Just under the limit, and therefore one refactor away from falling over it:
`walkFunctionBodiesInExpr` 7,702, `cpaSpineLeave` 7,359, `ctaM3StmtAnchorCore`
7,245, `cpaSpineEnter` 6,941.

**`forEachChild` is the entry that should stop a reader.** It is 9,750
bytecodes — permanently interpreted — and it is the primitive that *every*
traversal in the compiler goes through: the check spine's own descent, and all
~400 tail passes, which round 801 measured at **2,962 ms of pure AST
traversal** and characterised as "a structural cost whose treatments are
already closed". One of the treatments was never considered, because nobody
knew the traversal primitive was running in the interpreter.

**Read that table against the attribution arc.** Rounds 787–800 opened
`checkAssignmentExpression`, `checkPropertyAccessInExpr`,
`checkSingleCallExpressionTypes`, `checkMemberAccessMissing` and
`checkArgumentsAgainstSignature` one at a time, partitioned each into named
sections, and found "no concentration — the cost is spread over the whole
function". **All five are on this list.** A uniformly interpreted function is
precisely a function with no concentration.

### 3.3 The A/B — one flag, no code change

Compiler profile, `--noEmit`, 4 interleaved pairs on one binary, daemons
stopped inside the script, nothing else running.

| pair | A (default) | B (`-XX:-DontCompileHugeMethods`) | Δ |
|---|---:|---:|---:|
| 1 | 25.600 s | 24.542 s | **−1.058** |
| 2 | 25.526 | 25.198 | **−0.328** |
| 3 | 25.370 | 24.768 | **−0.602** |
| 4 | 25.131 | 24.363 | **−0.768** |
| **median** | **25.448** | **24.655** | **−0.793 s = −3.1%** |

**B wins 4/4 pairs; every per-pair delta is negative.** Arm A's sd is 0.207 s
(**0.81%** of its mean), arm B's 0.361 s (**1.46%**). The protocol band for a
COLD interleaved A/B is ±2.0% (`ab-interleaved.sh`), which −3.1% clears; **but
arm B's spread is above the ~1% quietness criterion, so the SIGN is certain and
the MAGNITUDE is not tight** — read it as −3.1% ± ~1.5%, i.e. somewhere between
half and one and a half times the biggest thing this arc has landed.

**Correctness held**: every one of the eight runs reports `FAILED — 46
error(s)`, the compiler profile's expected count, on both arms.

### 3.4 What this does and does not license

* It does **not** license shipping a JVM flag. Changing how the CLI is launched
  is a build/packaging decision, and CLAUDE.md reserves those to the owner —
  queued as `BLOCKED-PENDING-USER` with a proposal.
* It **does** license the source-level fix, which needs no permission and helps
  every user with no flag at all: **split the over-limit methods** until each is
  under 8,000 bytecodes. They are already `*Core` functions whose bodies are
  long sequences of guarded emission blocks; splitting is mechanical, and the
  corpus suite plus the 8-profile grid is exactly the gate for it.
* The prize of the split is **bounded above by the flag A/B (−3.1%)** and is
  probably a little smaller, since a split introduces call boundaries the
  monolith did not have — though it also unlocks inlining decisions the JIT
  cannot make today. It is the first perf item in ~20 rounds whose measured
  prize exceeds the noise band by more than 2×.
* One caveat that must be stated: `-XX:-DontCompileHugeMethods` makes C2
  compile a 46,000-byte method, which costs compile TIME and code cache. The
  A/B says the trade is net-positive on a 25-second compile; it might not be on
  a 2-second one. The SPLIT does not have this problem, which is another reason
  to prefer it over the flag.

## 4. (JIT.1)(a) — `forEachChild` split, and it is worth **−3.93%** on its own

*Round 803.* The first sub-step landed: `forEachChild` (9,750 bytecodes) is now
three functions over **disjoint contiguous `NodeKind` ranges**, so each is still
one dense tableswitch.

| function | kind range | bytecodes |
|---|---|---:|
| `forEachChild` | `SOURCE_FILE` .. `COMMA_LIST_EXPRESSION` (0–69) | **4,353** |
| `forEachChildOfMemberOrType` | `PROPERTY_DECLARATION` .. `KEYWORD_TYPE_NODE` (70–102) | **2,728** |
| `forEachChildOfSupportingNode` | `PARAMETER` .. `JSX_FRAGMENT` (103–137) | **2,175** |

Census **19 → 18** methods over the limit. The ranges are not arbitrary: every
HOT kind (IDENTIFIER — 44.5% of all nodes — the literals, PROPERTY_ACCESS /
CALL / BINARY, the five statement anchors) stays in the ENTRY function and pays
no extra call, and the continuation is one compare plus one static call rather
than a fall-through chain, so **no kind pays two**. Every arm was moved
verbatim; the 138 arm bodies were diffed against the pre-split file
mechanically and are byte-identical apart from the cosmetic group comments.

### 4.1 The prize, measured directly rather than inferred

The queue item said to size this by re-running the flag A/B. There is a sharper
instrument: build the pre-split file into its own class dir and A/B **monolith
vs split**, same source otherwise, same JVM, no flags. Five interleaved pairs,
self-time, daemons stopped inside the script, nothing else running:

| pair | A (monolith) | B (split) | Δ |
|---|---:|---:|---:|
| 1 | 25.729 s | 24.800 s | −0.929 |
| 2 | 25.804 | 25.109 | −0.695 |
| 3 | 26.314 | 25.280 | −1.034 |
| 4 | 26.342 | 25.368 | −0.974 |
| 5 | 26.479 | 25.629 | −0.850 |
| **median** | **26.314** | **25.280** | **−1.034 s = −3.93%** |

**B wins 5/5 and every per-pair delta is negative.** Arm sds are 342 ms (1.30%)
and 308 ms (1.22%) — above the ~1% quietness criterion — **but the pairing is
what carries this one**: the five deltas span only 339 ms, i.e. **0.33× the
median delta**, because both arms drift upward together across the run and the
interleave cancels the drift. Compare round 802's flag A/B, where the deltas
spanned 730 ms against a 793 ms median. Errors identical at 46 on all twelve
runs.

**One split of one function measures larger than the whole family's flag A/B
(−3.1%), and that is not a contradiction**: the flag makes C2 compile a
46,567-byte method too, which costs compile time and code cache (§ 3.4 flagged
exactly this), whereas the split pays none of it. The two numbers were also
taken on different days on a box whose arm sd moved between 0.8% and 2.8%, so
they should not be subtracted from one another.

### 4.2 The item's own falsifier, run early — and it is honest but blunt

(JIT.1) says: after the sub-steps land, re-run the flag A/B; if
`-XX:-DontCompileHugeMethods` STILL moves the wall, the split did not capture
the effect. Run against the split binary, 5 pairs: **+0.08%, B wins 3/5,
per-pair deltas −408 / −2 / −916 / +1,220 / +1,248, spread 2,164 ms — the
driver's own verdict is NOISE-DOMINATED**, with arm sds 2.49% and 2.78% (the
box was measurably noisier than during the 4.1 run).

So the honest statement is *not* "the flag now does nothing": it is that **the
same instrument that returned 4/4 with every delta negative on the monolith
returns a straddling 3/5 on the split binary**, at a pair count where an effect
of round 802's size would have shown. It bounds what (b)–(e) still have on the
table as *not obviously large on this profile*, and it does not license
skipping them — 18 methods are still interpreted, including one at 5.8× the
limit.

### 4.3 What guards it now

* **`HugeMethodLimitTest`** (jvmTest) parses `NodeWalkKt`'s compiled class file
  and reads each method's `Code` attribute length — the same number `javap`
  prints and the same number HotSpot compares against the limit. Verified
  DISCRIMINATING: rebuilt against the pre-split file, **all three of its tests
  fail**.
* **`ForEachChildSplitTest`** (commonTest) pins what a size check cannot see and
  what `ForEachChildOracleTest` does NOT pin — the enumeration **ORDER** (the
  oracle compares child SETS) and both **seams** between the parts. Verified
  DISCRIMINATING against a second ablated binary carrying a boundary typo
  (`kind < KEYWORD_TYPE_NODE`) plus a swapped action order in the `PARAMETER`
  arm: the seam pin, the order pin and the whole-tree pin fail, alongside three
  pre-existing oracle tests.

Gate: suite **13,481 → 13,493 / 0 / 3**; 8-profile grid diffed BOTH directions
against the pre-change binary's captured output — **46/94/46/46/46/46/46/46, 0
added and 0 removed on all eight**; `--partitionCheck 2` **EQUIVALENT — 46**;
`cost_gate.py` **all 20 counters +0.00%** (a pure split moves no counter, and
that is the claim being verified).

## 5. (JIT.1)(b) — `checkMemberAccessMissingCore`, 46,567 → eleven functions

*Round 804.* The largest method in the compiler, **5.8× the limit**, and round
789's "largest single leaf in the compile". Split into an entry plus ten helpers
along the level-R section boundaries round 789 had already committed (the
`CpaSections.atR` markers), so every helper holds one CONTIGUOUS run of the
original body and the execution order is unchanged.

| function | level-R sections | bytecodes |
|---|---|---:|
| `checkMemberAccessMissingCore` | `R_PRE`..`R_IDENT` + the `objectType` head | **6,425** |
| `cmamCheckLiteralAndNewReceiver` | `R_LITERAL`, `R_NEW` | 2,708 |
| `cmamCheckCallAndAccessReceiver` | `R_CALL`, `R_PAEA`, `R_STATIC` | 2,596 |
| `cmamGeneralReceiverType` | `R_OT_PRE`..`R_OT_IDENT` | 2,782 |
| `cmamCheckCastAndNamespaceReceiver` | `R_OT_PRE` | 611 |
| `cmamCheckUnionReceiverNarrowing` | `R_OT_UNION` | 3,550 |
| `cmamCheckNonIdentifierReceiver` | `R_OT_NONIDENT` | 1,342 |
| `cmamCheckIdentSymbolTypeGates` | `R_OT_IDENT`, first half | 1,385 |
| `cmamCheckIdentSymbolValueGates` | `R_OT_IDENT`, second half | 2,901 |
| `cmamCheckResolvedObjectType` | `R_TYPEGATE`..`R_POSTGATE` | 2,358 |
| `cmamEmitMissingProperty` | `R_PROP`, `R_LATEGATE`, `R_EMIT` | 2,472 |

Census **18 → 17**. **The eleven pieces sum to 29,130 against the monolith's
46,567** — the same source compiles to 37% less bytecode once it is not one
method, which is worth knowing before anyone reads a bytecode count as a cost
model: it is a THRESHOLD predicate and nothing else.

### 5.1 The move is mechanical, and that is the equivalence argument

All 1,918 moved lines were extracted by script and checked back against HEAD:
each of the **16 regions appears in the new file as a CONTIGUOUS, IN-ORDER run**,
identical modulo indentation and the return-signal rewrite. The accounting
closes exactly — 1,918 moved + 1 hoisted (`isPropertyAccessShape`, a pure
function of `keySuggestion` that both gate helpers read) + 16 brace/scaffold
lines = the 1,935-line original body.

What makes the rewrite exact is a property worth stating: **all 99 bare `return`s
in that function are whole-function returns.** Every other `return` occurrence in
the range is a `return@run` or lives inside the local `fun memberHasIt` — checked
by enumeration, not by eye — so "bare `return` at end of line" is a sound
mechanical selector for the signal to convert.

### 5.2 The one value that crossed a section boundary

The display-type override is assigned in the general receiver-type path and read
by the emission tail. It is now **RETURNED** (as the second half of a pair)
rather than stashed in a `Checker` field. A field would have needed round 791's
save/restore dance to stay correct under a nested invocation — and the RHS of
those assignments re-enters the checker, so "the outer write happens last" is not
guaranteed. The pair costs at most one allocation per surviving call (≤18,317 on
the compiler profile), which round 801 already measured as nothing.

**Round 791's invariant survives by construction**: the split introduces no new
mutable state, so the body still only appends to `diagnostics`, which is what
`cmamFlowSuppresses`' deferral needs. Round 792's whole-function pre-gate sits in
`checkMemberAccessMissing` and is untouched.

### 5.3 The prize: measured, and it is NOT there

Same instrument as round 803 — build the pre-split file into its own class dir
and interleave monolith vs split, no flags. Five pairs, self-time, box idle:

| pair | A (monolith) | B (split) | Δ |
|---|---:|---:|---:|
| 1 | 25.388 s | 24.717 s | −0.671 |
| 2 | 24.753 | 24.809 | +0.056 |
| 3 | 24.683 | 24.872 | +0.189 |
| 4 | 24.738 | 24.766 | +0.028 |
| 5 | 25.460 | 25.228 | −0.232 |
| **median** | **24.753** | **24.809** | **+0.056 s = +0.23%** |

**B wins 2/5. Per-pair deltas span 860 ms against a median delta of 28 ms — the
driver's own verdict is NOISE-DOMINATED.** Arm sds are 1.54% and 0.82%. Compare
round 803, whose five deltas spanned **0.33×** the median delta and were all
negative; here the spread is **31×** it.

**This is the honest result and it was predictable from round 803's own
falsifier.** After (a) landed, `-XX:-DontCompileHugeMethods` on the split binary
read +0.08%, 3/5, NOISE-DOMINATED — i.e. the instrument that had returned
4/4-all-negative on the monolith stopped returning a signal. That measurement
BOUNDED what (b)–(e) hold on this profile, and (b) has now come in at that bound.
Two readings, and the second is the one that generalises:

1. **`forEachChild` was special.** It is on the path of *every* traversal in the
   compiler; `checkMemberAccessMissingCore` runs 67,258 times, which is a large
   number of invocations but a small share of the interpreter's total work.
2. **The wall is not the only reason to do this.** The census is a THRESHOLD
   predicate: a method over the limit is *permanently* uncompilable, so its cost
   cannot improve with load, input size or JVM version. Landing (b) removes that
   property from the biggest method in the compiler for zero behaviour change,
   and the queue's (f) gate keeps it removed. A round that measures zero wall
   here has still bought a bound.

### 5.4 What guards it

* **`HugeMethodLimitTest`** gains 3 tests reading the compiled `Code` attribute
  length of `Checker` — the entry under the limit, all ten parts under it, and a
  shape check (10 parts, smallest > 400 bytecodes, sum > 20,000, i.e. the body
  was MOVED not deleted). **Verified DISCRIMINATING against the monolith class
  file**: none of the ten part names exist there and `checkMemberAccessMissingCore`
  reads 46,567, so all three fail.
* **`CmamSplitTest`** (commonTest, 18 pins) pins what a size check cannot see:
  one display per section — *the display is what distinguishes the sections from
  each other* — plus two SEAMS. **Verified DISCRIMINATING against an ablation
  carrying the two mistakes a mechanical split actually makes**: dropping the
  display-type override on the floor, and computing an emitting section's return
  signal but not honouring it. **Exactly the two seam pins fail**, and no
  per-section pin does — which is the sharpest possible statement that they pin
  different things.

Gate: suite **13,493 → 13,514 / 0 / 3**; 8-profile grid diffed BOTH directions
against the monolith binary — **46/46/46/46/46/46/46/94, 0 added and 0 removed on
all eight**; `--partitionCheck 2` **EQUIVALENT — 46**; `cost_gate.py` **all 20
counters +0.00%**.

## 6. (JIT.1)(c) — `checkPropertyAccessInExpr`, 9,062 → an entry plus four arms

*Round 805.* The cheapest remaining candidate, and the first split of a `when` that
is an **instanceof CHAIN** rather than a tableswitch.

| function | arm | bytecodes |
|---|---|---:|
| `checkPropertyAccessInExpr` | everything else, incl. every hot arm | **4,728** |
| `cpaExprFunctionExpression` | `is FunctionExpression` | 1,526 |
| `cpaExprArrowFunction` | `is ArrowFunction` | 1,357 |
| `cpaExprObjectLiteral` | `is ObjectLiteralExpression` | 850 |
| `cpaExprClassExpression` | `is ClassExpression` | 528 |

Census **17 → 16**. The five sum to **8,989 against the monolith's 9,062** — unlike
round 804's 46,567 → 29,130, splitting this one shrank nothing. A bytecode count is a
threshold predicate, not a cost model, in both directions.

**Why the shape differs from (a).** `forEachChild` is a dense `when (kindId)`
tableswitch, so round 803 had to split by contiguous key RANGE to keep each part one
switch, and had to keep the hot kinds in the entry so no kind paid two calls. Here
the dispatch is a linear `is X ->` chain: moving an arm's BODY leaves the chain's
order and length untouched, so **no expression kind pays an extra test**. The four
arms moved are simply the four LONG ones; the hot arms (PropertyAccess, Call, Binary,
the unwrappers, the `else` leaf) are two lines each and were never candidates.

**The equivalence, measured.** All 224 moved lines were extracted by script and
re-checked against HEAD — each of the four regions is a CONTIGUOUS, IN-ORDER run
identical modulo the 8-space dedent — and the entry function was *reconstructed* from
HEAD with the four blocks replaced by four one-line arms and compared: identical, 171
lines. Accounting closes exactly: 167 + 4 arms + 4 headers + 224 bodies + 4 closes =
the 399-line original. Two properties make it exact: the function contains **no
`return` at all**, and each moved arm saves and restores its own scope state entirely
within itself, so there is **no cross-boundary value** (round 804's returned pair was
not needed).

**Guarded by** `CpaExprSplitTest` (9 pins: one ARM pin per helper, plus four SEAM pins
— the arrow's and the function expression's parameter scopes restored on return, the
function-expression body walked with `enclosingClassType = null`, the enclosing class
restored after a class expression — plus a five-deep nesting pin that every helper
recurses back into the entry) and `HugeMethodLimitTest` (+3, reading `Checker`'s
compiled `Code` attribute lengths). **Discrimination is only partly measured**:
ablation A is established from the pre-split class files (`checkPropertyAccessInExpr`
9,062 and no `cpaExpr*` methods, so all three size pins fail), but the two-mistake
ablation for the SEAM pins was not built — it is owed.

Gate: suite **13,514 → 13,526 / 0 / 3**; 8-profile grid BOTH directions against a
purpose-built pre-split binary, every capture confirmed non-empty first —
**46/46/46/46/46/46/46/94, 0 added and 0 removed**; `--partitionCheck 2`
**EQUIVALENT — 46**; `cost_gate.py` **all 20 counters +0.00%**.

## 7. (JIT.1)(e) — the emit path, sized at last

Every A/B in this arc runs `--noEmit`, so none of them can see
`Transformer.transformToCommonJS` (**28,991**, 3.6× the limit), `transformClassBody`
(16,233) or `transform` (8,934). Round 805 measured it, on ONE binary (the round-805
split), compiler profile, self time.

**First, the path is REACHED.** The profile is `module: NodeNext`, and its emitted
`dist/compiler/core.js` is CommonJS (155 `require(`/`exports.` occurrences). This was
checked rather than assumed — round 793's rule about controls that are dead where the
prize is measured.

**The emit phase costs 12.6%**, 3 interleaved pairs:

| | run 1 | run 2 | run 3 | median |
|---|---:|---:|---:|---:|
| emit | 28,062 | 27,945 | 28,060 | **28,060** |
| `--noEmit` | 25,295 | 24,919 | 24,442 | **24,919** |
| Δ | +2,767 | +3,026 | +3,618 | **+3,141 ms = +12.6%** |

The three deltas span 851 ms = **0.27×** the median delta. Note this is HIGHER than
the 8.5% CLAUDE.md carries from round 739; quote 12.6% with the mode and the date.

**But the flag buys the same fraction in BOTH modes**, which is what isolates the emit
path — run on the same binary, 3 pairs each:

| mode | default | `-XX:-DontCompileHugeMethods` | Δ | wins |
|---|---:|---:|---:|---:|
| `--noEmit` | 24,524 | 24,245 | **−279 ms = −1.14%** | 3/3 |
| emit | 27,106 | 26,790 | **−316 ms = −1.17%** | 3/3 |

**The difference — what the three Transformer methods cost by being interpreted — is
−37 ms (medians) to −69 ms (median of per-pair deltas): 0.14–0.25% of an emit-mode
compile, against a 402 ms per-pair spread in the emit arm, i.e. 5.8–10.9× the effect.
NOISE-DOMINATED.** So **(e) is to be landed for the THRESHOLD, exactly like (c) and
(d), not for a wall number.** Two caveats stated once: three pairs is thin, and the
flag also makes C2 *compile* a 28,991-byte method (compile time and code cache), so it
can UNDER-read what a split would buy — this is a bound, not a proof of zero.

**A second result nobody asked for.** The whole-family flag is now worth **−1.14% in
`--noEmit`**, against round 802's **−3.1%** on the pre-split binary. Three splits have
taken roughly two thirds off the instrument's own reading, on the same profile and box
class — the closest thing this family has to a cumulative measurement, and consistent
with (a) having been the large one.

## 8. (JIT.1)(c)'s owed ablation, paid — and one of the two seam pins did not discriminate

*Round 806.* Round 805 stated plainly that ablation B was not run. Run now, with
exactly the two mistakes it named — `cpaExprArrowFunction`'s three scope RESTORE
lines dropped, and `enclosingClassType` threaded into the function-expression
body instead of the literal `null`. **Expected two failures; got one.**

| pin | verdict |
|---|---|
| `FunctionExpression seam - the body is walked with no enclosing class type` | **FAIL** (as predicted) |
| `ArrowFunction seam - the arrow's parameter scope is restored after the arm returns` | **pass** — the pin is blind |

**The mechanism is `withCpaFrameAmbient`.** It is the per-statement anchor
install in `cpaSpineLeave`, and it saves and restores `currentLocalTypes` around
**every** statement dispatch. So a restore dropped inside an arm is erased at the
statement boundary, and both restore pins were reading the outer binding from the
**next statement** — the one position that provably cannot see the leak.

A leak is observable only WITHIN one statement. Measured over a probe carrying
four shapes (next statement / later object-literal property / comma operator /
later call argument): **4 errors on a correct binary, 1 on the ablated one** —
the last three discriminate, the first does not. Both pins now read the outer
binding from a **later argument of the same call**.

**Re-run after the fix, with three mistakes (both restores dropped plus the
`enclosingClassType` thread): exactly the three seam pins fail and no arm pin
does.** That is the sharpest available statement that the arm pins and the seam
pins pin different things.

The transferable law: **a seam pin must be validated against the mistake it
names, because an ambient reinstall upstream can make a whole class of seams
unobservable** — and "the pin is green on the broken binary" is the only way to
find out.

## 9. (JIT.1)(d) — `ccetSpineEnter`, 8,686 → an entry plus three arm helpers

*Round 806.* Cheapest remaining candidate, and the one that runs most often:
`ccetSpineEnter` is called at **every node of every file**.

| function | arm | bytecodes |
|---|---|---:|
| `ccetSpineEnter` | `MODULE_DECLARATION` + both trailing blocks | **2,474** |
| `ccetEnterBlock` | `BLOCK` (5 parent-kind sub-arms) | 2,848 |
| `ccetEnterClassDeclaration` | `CLASS_DECLARATION` | 1,946 |
| `ccetEnterFunctionLike` | `ARROW_FUNCTION`, `FUNCTION_EXPRESSION` | 1,328 |

Census **16 → 15**. The four sum to **8,596 against the monolith's 8,686** —
like round 805's split and unlike round 804's 46,567 → 29,130, this one shrank
nothing. Third independent confirmation that a bytecode count is a THRESHOLD
predicate and not a cost model.

**Shape.** The dispatch is a `when (node.kindId)`, so extracting arm BODIES
leaves all four keys and the switch itself untouched and no kind pays an extra
test — (a)'s contiguous-range discipline applies only when the switch itself has
to be cut. What stays in the entry is chosen by frequency, not by size:
`MODULE_DECLARATION` is 17 lines, and the two trailing blocks (the ForIn/ForOf
loop-var shadow and the If-arm type-guard override) run for **every** node.

**Equivalence, measured.** Each of the three moved regions, re-extracted from the
new file, is a CONTIGUOUS, IN-ORDER run of HEAD modulo a 4- or 8-space dedent;
the entry function reconstructed from HEAD with the three blocks replaced by
three one-line arms is IDENTICAL at 80 lines; the accounting closes exactly
(80 = 246 − 162 moved − 7 scaffold + 3 arms); the function's only `return` is the
prologue's `spineIsDts || spineIsJsLike` guard, which stays in the entry, and
there is none inside the moved arms; and no helper returns a value — each arm's
only effect is the shared `ccetFrames` push, which is what the LATER
`ccetSpineEnter` and `ccetSpineLeave` calls read and pop.

**Guarded by** `CcetSpineEnterSplitTest` (10 pins, every one observing the frame
through a CALL ARGUMENT because the ccet pass's emission is
`checkSingleCallExpressionTypes` — a property read would be answered by the cpa
pass and could not discriminate) and `HugeMethodLimitTest` (+3).

**Discrimination, measured — established, but NOT pin-by-mistake.** Two ablations
were run. Against the combined one (the `ClassDeclaration` helper's frame push
dropped **and** the `BLOCK` arm turned into an early `return`), **three pins
fail**: both `ClassDeclaration` arm pins and the method-body `this` pin. Against
the early-`return` mistake **alone**, the **same three** fail — so the class is
sensitive to that mistake, but not through the seam pin written for it, and the
frame-push mistake has no failure that is uniquely its own.

**What is NOT discriminated, stated plainly: the trailing-blocks seam.** The pin
written for it (an `if` with a user type guard and a Block then-statement,
asserting the narrowed `A`) stays green on the early-`return` binary — that
narrowing is supplied redundantly by another pass. A replacement using the OTHER
trailing block, the ForIn/ForOf loop-var shadow, **discriminated under the
real-lib CLI (1 error on the broken binary, 0 on a correct one) and did NOT
reproduce under `diagnose()`, which uses the embedded lib** — so it was not
adopted. The lesson is the round-730 lib-split one arriving in a new place: **a
shape validated with the project CLI is not automatically a valid pin, because
the two paths do not use the same lib.**

Gate: suite **13,526 → 13,539 / 0 / 3**; 8-profile grid diffed set-for-set BOTH
directions against a purpose-built pre-split binary, every capture confirmed
non-empty first — **46/46/46/46/46/46/46/94, 0 added and 0 removed**;
`--partitionCheck 2` **EQUIVALENT — 46**; `cost_gate.py` **all 20 counters
+0.00%**; build warning-clean. **No wall A/B, deliberately** — the family is
bounded three times over (§§ 4.2, 5.3, 7) and this lands for the threshold.

## 10. Reproduction

```bash
# the census — static, no run
python3 scripts/huge_methods.py --top 20

# the partition
java -Xmx4g -cp "build/classes/kotlin/jvm/main:$(cat build/bench/cp.txt)" \
  com.xemantic.typescript.compiler.MainKt --noEmit --passTiming build/bench/tsc-project-* \
  | grep -E 'checker-init total|  init:'

# the flag A/B (alternate the arms; stop the Gradle and Kotlin daemons first)
java -Xmx4g                             -cp "$CP" …MainKt --noEmit "$PROJ"
java -Xmx4g -XX:-DontCompileHugeMethods -cp "$CP" …MainKt --noEmit "$PROJ"

# a SPLIT's own prize (round 803's instrument — sharper than the flag A/B):
# build the pre-split file into its own class dir, then interleave the two dirs.
git show <pre-split-sha>:src/commonMain/kotlin/NodeWalk.kt > src/commonMain/kotlin/NodeWalk.kt
./gradlew compileKotlinJvm && cp -r build/classes/kotlin/jvm/main /tmp/xtsc_unsplit
git checkout src/commonMain/kotlin/NodeWalk.kt && ./gradlew compileKotlinJvm
scripts/ab-interleaved.sh /tmp/xtsc_unsplit build/classes/kotlin/jvm/main 5
```

## 11. (JIT.1)(f) — `checkArgumentsAgainstSignatureCore`, 23,890 → an entry plus thirteen helpers

*Round 807.* The largest remaining `Checker` method, and the first split of a
**loop body** rather than a `when` dispatch.

| function | region of the committed `ArgSections` partition | bytecodes |
|---|---|---:|
| `checkArgumentsAgainstSignatureCore` | INFER, L_PARAM, **L_ARGTYPE**, L_PRE, L_WEAK, POST | **7,173** |
| `caasTailGatesAndRelation` | L_TAILGATE + L_RELATION | 2,792 |
| `caasNonSimpleParamChecks` | L_NOTSIMPLE | 2,689 |
| `caasNullishArgGates` | L_NULLISH | 2,386 |
| `caasObjLitPerPropertyMismatch` | L_OBJLIT, per-property block | 2,061 |
| `caasArgKindAndIndexSignature` | L_ARGKIND | 1,109 |
| `caasWalkerArgChecks` | L_WALKERS | 1,057 |
| `caasTypeParamConstraintArg` | L_TYPEPARAM | 976 |
| `caasObjectLiteralVsTypeParam` | L_OBJLIT_TP | 952 |
| `caasObjLitMissingRequired` | L_OBJLIT, missing-required block | 643 |
| `caasObjLitProtoOverride` | L_OBJLIT, prototype-override block | 564 |
| `caasObjectLiteralVsObjectParam` | the rest of L_OBJLIT | 456 |
| `caasPrologueWalkers` | PRO | — |
| `caasSingleTypeParamWalkers` | PRO2 | — |

Census **15 → 14**. Like (c) and (d) and unlike (b), the split shrank nothing:
the parts sum to roughly the monolith. Fourth independent confirmation that a
bytecode count is a THRESHOLD predicate and not a cost model.

**What stays in the entry is chosen from the measured partition, not by size.**
`L_ARGTYPE` is 56.9% of the function (§ 4 of `argument-check-attribution.md`),
round 796's exit census says **every** invocation returns from `POST`, and
`L_PARAM`/`L_PRE`/`L_WEAK` are the per-iteration prologue every argument pays —
so all six stay inline and only the low-frequency tail moves.

**Two extractions were needed to cross the line, and that is the transferable
number.** Moving the whole loop tail left the entry at **8,061** — 61 bytecodes
over. `PRO` and `PRO2` (the eleven `tryEmit*` prologue gates, each an
`if (…) return`, extracted as two `Boolean`-returning helpers) took it to 7,173.
A split that lands "just over" is not a failed split; it is one extraction short.

### 11.1 The shape problem this one has and (a)–(d) did not

A `when` arm's only exit is falling off the end. A loop body exits by
`continue`, `break` and — twice here — a whole-function `return`, and **none of
the three can cross a function boundary**. Each moved region therefore returns a
signal (`CAAS_CONTINUE` / `CAAS_BREAK` / `CAAS_RETURN` / `CAAS_NONE`) that the
entry's call site replays.

Which `continue`/`break` binds to the ARGUMENT loop and which to a nested one is
the whole correctness question, and it was answered by a **brace-matching parser
over the string/comment-stripped source**, not by indentation: **31 bind to the
argument loop** (6 stay in the entry, 25 move), the rest to one of four nested
`for` loops that move with their bodies. The two bare `return`s are both
whole-function. One direction of this is compiler-enforced — an outer-binding
`continue` left unconverted is `break and continue are only allowed inside a
loop` — the other is not, which is why the parser and not the eyeball.

### 11.2 Equivalence, measured (round 805's five checks)

1. every moved line re-extracted from the NEW file and checked back against
   HEAD: **twelve contiguous, in-order runs**, identical modulo the dedent;
2. the entry function **reconstructed** from HEAD with the regions replaced by
   the call sites: **IDENTICAL, 366 lines**;
3. accounting closes exactly — HEAD body 1,714 = kept 313 + moved 1,401; new
   entry 366 = kept 313 + 53 lines of call site;
4. every `return` enumerated (11 prologue, 2 in-loop, both whole-function);
5. **cross-boundary values: none.** Every loop-body local is read only inside
   the region that declares it, so no helper returns a value except the three
   `Boolean` sub-helpers of the object-literal block.

### 11.3 Discrimination — five of six seams, and the sixth is honestly open

`CaasSplitTest` (20 pins) plus `HugeMethodLimitTest` (+3). Six deliberate
mistakes were injected TOGETHER, one per helper, and **exactly five of the six
intended pins failed, and no arm pin did**: the excess-property `CAAS_BREAK`,
the prototype-override `CAAS_RETURN`, the per-property `CAAS_RETURN`, the
non-simple `CAAS_CONTINUE`, the TS2345 `CAAS_BREAK`. (A seventh pin — the
post-loop rest-argument check — also failed, a knock-on of the dropped relation
`BREAK`, which is the expected coupling and not a false pin.)

**The sixth is NOT discriminated, and re-running the mistake ALONE is what
established that.** `caasTypeParamConstraintArg`'s trailing `CAAS_CONTINUE`
dropped by itself leaves every pin GREEN, because `caasNonSimpleParamChecks`'
own `CAAS_CONTINUE` catches the same argument one helper later. It is a
**redundant guard on today's code**. The replacement pin written for it
(`q<T extends string>(t: T); q(1)` reported once, naming the constraint) fires
only when BOTH signals are lost, and says so in its own comment.

The lesson, which is round 806's with a new mechanism: **a seam pin can be blind
not because the leak is erased upstream but because a LATER guard makes the same
decision.** Ablate one mistake at a time, or a combined ablation will credit a
pin with discrimination it does not have — round 807's first ablation did
exactly that.

### 11.4 Gate

Suite **13,539 → 13,562 / 0 failures / 3 skipped**; 8-profile grid diffed
set-for-set BOTH directions against a purpose-built pre-split binary, every
capture confirmed non-empty and non-vacuous first — **46/46/46/46/46/46/46/94,
0 added and 0 removed on all eight**; `--partitionCheck 2` **EQUIVALENT — 46**;
`cost_gate.py` **all 20 counters +0.00%**; build warning-clean. **No wall A/B,
deliberately** — the family is bounded four times over (§§ 4.2, 5.3, 7 and round
804) and this lands for the threshold.
