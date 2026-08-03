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

## 12. (JIT.1)(c) — `checkVarDeclAssignabilityCore`, 19,296 → an entry plus seven helpers

*Round 808.* The largest `Checker` method left after round 807, and the first
split of a **straight-line statement sequence** — round 804's shape, but with the
partition markers doing the choosing rather than a probe's section list.

| function | region of the committed `CtaSections` level-B partition | bytecodes |
|---|---|---:|
| `checkVarDeclAssignabilityCore` | `B_BINDPAT`, `B_RECORD`, `B_TARGET`, `B_SRCTYPE`, `B_NARROW`, `B_RELATION`, `B_TAIL` | **3,535** |
| `cvdaElaborateMismatch` | `B_ELAB` body | 4,147 |
| `cvdaPrologueWalkers` | `B_PRO1` + `B_WEAK` + `B_PRO2` + `B_PRO3` | 3,103 |
| `cvdaPostRelationGates` | `B_POST` | 3,065 |
| `cvdaEarlyInitGates` | `B_NUIA` + `B_PRE2` | 1,811 |
| `cvdaMidGates` | `B_MID` | 1,514 |
| `cvdaNestedInitTargets` | `B_NESTED` | 1,251 |
| `cvdaRecordInferredLocalType` | `B_UNANNOT` body | 392 |

Census **14 → 13**. The eight sum to **18,951 against the monolith's 19,296** —
a fifth independent confirmation that a bytecode count is a THRESHOLD predicate
and not a cost model (only round 804's 46,567 → 29,130 ever shrank).

**What stays in the entry is what every declaration pays.** `B_BINDPAT` (the
destructuring-pattern branch and the `name !is Identifier` bail), `B_RECORD` (the
`varTypes` / `currentLocalTypes` / `currentLocalDeclTypeNodes` writes every
annotated declaration makes), `B_TARGET` (the one `getTypeFromTypeNode` call),
`B_SRCTYPE` + `B_NARROW` (the contextual-literal preservation and the flow
narrow, i.e. the source type itself), `B_RELATION` (the `canUseTypeEngine` +
`checkTypeRelatedTo` pair) and `B_TAIL` (the legacy string fallback). Everything
that moved is a gate that fires for one shape and ends the check.

### 12.1 The shape problem, and the one value that crosses

A `when` arm exits by falling off the end (rounds 803/805/806); a loop body exits
by `continue`/`break`/`return` (round 807). This body is a statement sequence
punctuated by **~40 early `return`s**, none of which can cross a function
boundary. Four regions therefore return `Boolean` (`true` = "I emitted, the
caller must return") and the entry replays them as `if (…) return`. Two regions
(`B_UNANNOT`, `B_ELAB`) held blocks that returned UNCONDITIONALLY, so their bare
`return`s stay bare and the caller returns straight after the call — and for
`B_UNANNOT` that seam is **compiler-enforced**, because the entry's
`typeAnnotation` is smart-cast non-null immediately below the call site.

**Cross-boundary values: exactly one.** `nestedMissingEmitted` is written in
`B_NESTED` and read ~480 lines later by the `B_ELAB` gate, so
`cvdaNestedInitTargets` returns `Boolean?` — `null` meaning "the caller must
return", otherwise the flag. Round 804's rule decides the shape: a `Checker`
field would need round 791's save/restore dance, because the blocks either side
re-enter the checker and "the outer write happens last" is not guaranteed.

### 12.2 Equivalence, measured (round 805's five checks)

1. every moved line re-extracted from the NEW file and checked back against
   HEAD: **seven contiguous, in-order runs**, identical modulo the 4-space dedent
   of the two block interiors and the return-signal rewrite;
2. the entry function **reconstructed** from HEAD with the seven regions replaced
   by their call sites: **IDENTICAL, 222 lines**;
3. accounting closes exactly — HEAD body 1,541 = kept 211 + moved 1,330; new
   entry 222 = kept 211 + 11 lines of call site;
4. every `return` enumerated by a comment-stripped token scan; the only local
   function in the range (`fun isAnonFn`, in `B_MID`) is expression-bodied and
   contains none, and no `return@checkVarDeclAssignabilityCore` exists;
5. free variables computed per region rather than guessed, which is what keeps
   the build warning-clean (an unused parameter is a warning here).

### 12.3 Discrimination — two seams of five, ablated ONE AT A TIME

`CvdaSplitTest` (14 pins) plus `HugeMethodLimitTest` (+3). Round 807's law was
followed literally: **five deliberate mistakes, five separate builds**, never
combined. Control first — the committed binary, 32 pins, 0 failed.

| mistake | pins failed | verdict |
|---|---|---|
| M1 `cvdaPrologueWalkers`' `true` dropped | 2 — the prologue seam and the ordering pin | **DISCRIMINATED** |
| M2 `nestedMissingEmitted` forced `false` (the `null` signal still honoured) | 1 — **exactly** the nested seam | **DISCRIMINATED, sharply** |
| M3 `cvdaEarlyInitGates`' `true` dropped | 0 | **NOT DISCRIMINATED** |
| M4 `cvdaMidGates`' `true` dropped | 0 | **NOT DISCRIMINATED** |
| M5 `cvdaPostRelationGates`' `true` dropped | 0 | **NOT DISCRIMINATED** |

M2 failing its own pin and *nothing else* is the sharpest available statement
that the arm pins and the seam pins pin different things — and it is the one that
matters most, since `nestedMissingEmitted` is the only value crossing a boundary.

**Why M3/M4/M5 are green, and it is a property of the FUNCTION, not of the
pins.** Dropping one of those `return`s does not delete the emission — the helper
still runs and still emits; only the early exit is lost. **Every later emitter in
this body is itself conditioned on the relation verdict** (`canUse &&
!isAssignable` for the `B_ELAB` elaboration, `isAssignable` for most of `B_POST`),
and the shapes those three regions own are exactly the ones where the relation
either PASSES or `canUseTypeEngine` declines — so the later gates refuse anyway
and nothing doubles. On today's code those three signals are **redundant guards**,
the same finding round 807 recorded for `caasTypeParamConstraintArg`'s trailing
`CAAS_CONTINUE`. They are kept because the monolith had them; a future rule that
makes a later gate fire unconditionally would make them load-bearing again, and
this table is the record that no pin would notice today.

Two further seams need no pin at all: `cvdaRecordInferredLocalType`'s caller
`return` is **compiler-enforced** (`typeAnnotation` is smart-cast non-null
immediately below), and `cvdaElaborateMismatch`'s trailing `return` was never
moved — it stayed in the entry.

### 12.4 Gate

Suite **13,562 → 13,579 / 0 failures / 3 skipped**; 8-profile grid diffed
set-for-set BOTH directions against a purpose-built pre-split binary, every
capture confirmed non-empty first (and the two class dirs checked to differ, with
`cvda*` methods present in one and absent in the other) — **46/46/46/46/46/46/46/94,
0 added and 0 removed on all eight**; `--partitionCheck 2` **EQUIVALENT — 46**;
`cost_gate.py` **all 20 counters +0.00%**; build warning-clean. **No wall A/B,
deliberately** — the family is bounded four times over (§§ 4.2, 5.3, 7 and round
804) and this lands for the threshold.

**A process note worth carrying.** Two of the seven builds this round died with
`java.lang.OutOfMemoryError: GC overhead limit exceeded` in the Kotlin daemon —
a run of back-to-back `compileKotlinJvm` invocations accretes daemon heap until
BUILD.1's 5 GB stops being enough. The tell is a `jvmTest` run that reports **0
pins ran** (the test task never started), which reads exactly like "the ablation
compiled and changed nothing". **Check the ablation's build log for `BUILD
SUCCESSFUL` before recording a zero**, and stop the daemons between long ablation
batches.

## 13. (JIT.1)(g) — `checkAssignmentExpressionCore`, 18,100 → an entry plus nine helpers

*Round 809.* The largest `Checker` method left after round 808, and the second
split of a **straight-line statement sequence** — but the first one where the
partition being followed is a *different* level of the same probe object
(`CtaSections` **level E**, round 786's own instrument, already in the source).

| function | region of the committed `CtaSections` level-E partition | bytecodes |
|---|---|---:|
| `checkAssignmentExpressionCore` | head, `E_RECURSE`, `E_ARGS`, `E_FIRE1`, `E_IDLIT`, `E_TTRESOLVE`, `E_SRCTYPE`, `E_NARROW`, `E_RELATION`, `E_POST`, `E_LITTAIL`, `E_PA` | **3,861** |
| `caeUnionAndMissingPropertyGuards` | `E_UNION` + `E_B175` + `E_B127` | 2,867 |
| `caeElaborateMismatch` | `E_ELAB` | 2,803 |
| `caeModuleAliasAndLibPairShapes` | `E_MODULE` + `E_B236` | 1,704 |
| `caeLegacyDeclaredStringPath` | `E_DECLSTR` | 1,626 |
| `caeIndexSigAndSignatureGuards` | `E_MID` + `E_SIGS` + `E_OBJLIT` | 1,608 |
| `caePrototypeMemberAssign` | `E_PROTO` body | 1,319 |
| `caeForeignTpTargetAndClassRhs` | `E_FTP` + `E_CTORID` | 674 |
| `caeThisPropertyAssign` | `E_THIS` | 557 |
| `caeElementAccessAssign` | `E_ELEM` | 453 |

Census **13 → 12**. The ten sum to **17,472 against the monolith's 18,100** — a
sixth independent confirmation that a bytecode count is a THRESHOLD predicate and
not a cost model (only round 804's 46,567 → 29,130 ever shrank).

**What stays in the entry is what every input pays, and here that is measurable
rather than argued.** Round 786's level-E partition says 10,432 of the 17,179
invocations (61%) exit in the entry row because the expression is not an `=`
`BinaryExpression` — the eligibility test lives INSIDE this function, so unlike
sites 1 and 2 it pays for its own dispatch. The four rows that carry the cost —
the SOURCE type (160 ms net), flow narrowing (59), the identifier-target guards
(33), `canUseTypeEngine`+`checkTypeRelatedTo` (20) — all stay inline, as do the
three `tryEmit*` gates every `=` pays and the one-line `x.prop = value` arm
(31 ms). **Everything that moved is a gate that fires for one shape and ends the
check**, and the two largest moved regions are the two the same partition prices
at essentially nothing: `E_ELAB` is **0.4 ms over 3,791 reaches** and the
`E_UNION`/`E_B175`/`E_B127` guard cluster ~9 ms.

### 13.1 The shape, and why there is no cross-boundary value at all

38 bare `return`s, every one a whole-function return — established by a
comment-stripped token scan, not by eye (the other 24 `return` occurrences in the
range are `return@run` ×16 and `return@dataProp` ×8, all inside blocks that move
whole). Seven regions therefore return `Boolean` (`true` = "I emitted, the caller
must return") and the entry replays them as `if (…) return`; `caeThisPropertyAssign`
and `caeElementAccessAssign` contain no `return` at all and are `Unit`.

**Cross-boundary values: none** — and that was computed, not assumed. Every
`val`/`var` in the function was listed with its brace depth, and for each region
the in-scope prior declarations were intersected with the identifiers the region
uses. The six the entry keeps (`target`, `targetType`, `typeAnnotation`, `tt`,
`sourceType`, `canUse`/`isAssignable`) are passed as parameters; every other local
a region declares is dead by that region's end, including three that *look* like
they escape and do not: the outer `rhs` (shadowed by a new `val rhs` inside
`E_B175`), and `b175RhsClassSym`, which is read by `E_B127`'s gate — which is
exactly why those two rows are in the SAME helper.

One boundary that constrains the partition and is worth naming: `savedContextual`
is set at the top of `E_SRCTYPE` and restored at the end of `E_NARROW`, so no
region may straddle that pair. Both rows stay in the entry.

### 13.2 Equivalence, measured (round 805's five checks)

1. every moved line re-extracted from the NEW file and checked back against HEAD:
   **nine contiguous, in-order runs**, identical modulo the dedent and the
   return-signal rewrite;
2. the entry function **reconstructed** from HEAD with the nine regions replaced
   by their call sites: **IDENTICAL, 345 lines**;
3. accounting closes exactly — HEAD body 1,477 = kept 335 + moved 1,142; new entry
   345 = kept 335 + 10 lines of call site;
4. every `return` enumerated: **38 = 15 that stayed + 23 that became `return true`**,
   plus the 7 the call sites replay = the 22 bare `return`s the new entry contains;
5. free variables computed PER REGION rather than guessed — which is what keeps
   the build warning-clean, since an unused parameter is a warning here.

A tooling note that cost the round its first twenty minutes and will cost the
next agent the same: **a Kotlin string/comment stripper written for brace matching
must treat `'` as a CHAR LITERAL (`'x'` / `'\n'` / `'\uXXXX'`), never as "scan to
the next apostrophe"** — Checker.kt contains raw-string regexes with `'` inside a
character class (`(["\'])`, `[^'"]+`), and a naive scanner desynchronises there and
then reports the function's body as 25,660 lines instead of 1,477. The
length-preservation invariant (every stripped line the same length as its
original) is the cheap check that catches it.

### 13.3 Discrimination — ablated ONE AT A TIME, seven separate builds

`CaeSplitTest` (14 pins) plus `HugeMethodLimitTest` (+3). Round 807's law was
followed literally: **seven deliberate mistakes, seven separate builds**, never
combined, each one "the entry ignores this helper's return signal"
(`if (caeX(…)) return` → `caeX(…)`). Control first — the committed binary, 35
pins, 0 failed.

| mistake — the entry ignores this helper's `true` | pins failed | verdict |
|---|---|---|
| `caePrototypeMemberAssign` | 0 | **NOT DISCRIMINATED** |
| `caeModuleAliasAndLibPairShapes` | 0 | **NOT DISCRIMINATED** |
| `caeForeignTpTargetAndClassRhs` | 1 — **exactly** its own arm pin | **DISCRIMINATED, sharply** |
| `caeIndexSigAndSignatureGuards` | 0 | **NOT DISCRIMINATED** |
| `caeUnionAndMissingPropertyGuards` | 0 | **NOT DISCRIMINATED** |
| `caeElaborateMismatch` | 3 — its arm pin, the elaboration seam pin, the ordering pin | **DISCRIMINATED** |
| `caeLegacyDeclaredStringPath` | 0 | **NOT DISCRIMINATED** |

Every one of the seven runs reported **14 pins ran**, so none of the zeros is
round 808's dead-build artefact.

**Two of seven, and the round's real result is that the PREDICTION was wrong in
BOTH directions.** Before the ablations, `caePrototypeMemberAssign`'s signal was
argued to discriminate (drop it and the target-kind dispatch reaches the
`else if (target is PropertyAccessExpression)` arm, which "must" re-emit) and
`caeForeignTpTargetAndClassRhs`'s was argued redundant (`canUseTypeEngine` skips
class-instance-vs-constructor comparisons, so "nothing downstream can fire").
Both are false: `checkPropertyAccessAssignment` stays silent for an
`X.prototype.p` target, and the class-value path does change what the tail
reports. **So the reading of the downstream gates is a way to CHOOSE which seams
to pin, never a substitute for running the ablation** — which is the same lesson
rounds 806 and 807 reached by two other mechanisms.

Why the other four are green is the round-808 mechanism unchanged: dropping the
`return` does not delete the emission, the helper still runs and still emits, and
every later emitter in this body is conditioned on the relation verdict, which
refuses these shapes anyway. On today's code they are **redundant guards**; they
are kept because the monolith had them, and this table is the record that no pin
would notice if a future rule made them load-bearing again.

The seam pin written for `caePrototypeMemberAssign` is **renamed** in
`CaeSplitTest` to say what it actually tests (a single-emission guard against a
future second emitter), per the standing rule that an undiscriminating pin must
not be left carrying a name that claims otherwise.

**A process cost worth quoting for the next batch.** The driver stopped the
Gradle daemon and gracefully killed the Kotlin daemon between ablations — which
is what round 808's OOM demands, and which by CLAUDE.md's own BUILD.1 entry
forces a COLD `compileKotlinJvm` every time. Seven ablations therefore cost
**~8 minutes each rather than ~2.5**. That is the right trade (round 808 lost two
builds to the OOM and could not tell them from clean ablations), but it must be
budgeted: a seven-mistake batch is an hour.

### 13.4 Gate

Suite **13,579 → 13,596 / 0 failures / 3 skipped**; 8-profile grid diffed
set-for-set BOTH directions against a purpose-built pre-split binary, every
capture confirmed non-empty first and the two class dirs checked to differ (`cae*`
methods present in one, absent in the other) — **46/46/46/46/46/46/46/94, 0 added
and 0 removed on all eight**; `--partitionCheck 2` **EQUIVALENT — 46**;
`cost_gate.py` **all 20 counters +0.00%**; no `w:` lines in the compile that
produced the binary. **No wall A/B, deliberately** — the family is bounded four
times over (§§ 4.2, 5.3, 7 and round 804) and this lands for the threshold.

## 14. (JIT.1)(h) — `checkReturnAssignabilityCore`, 9,743 → an entry plus two helpers

*Round 810.* The smallest over-limit `Checker` method — only **1.2×** the limit,
against (f)'s 3.0× and (g)'s 2.3× — and the first one where the question was not
*how* to partition but *how little* to move.

| function | region of the committed `CtaSections` level-C partition | bytecodes |
|---|---|---:|
| `checkReturnAssignabilityCore` | head, `C_GEN`, `C_FIRE1`, `C_TUPLE`, `C_TARGET`, `C_CONDBR`, `C_CTX`, `C_SRCTYPE`, `C_NARROW`, `C_FTP`, `C_RELATION`, `C_MIDGUARD`, `C_STRTAIL` | **4,052** |
| `craGuardWalkers` | `C_WALKERS` | 3,706 |
| `craElaborateReturnMismatch` | `C_ELAB` | 1,851 |

Census **12 → 11**. The three sum to **9,609 against 9,743** — a seventh
confirmation that a bytecode count is a THRESHOLD predicate and not a cost model.

**The partition was already committed, and so was the ARGUMENT for which regions
to move.** Level C (§ 5) is a *measured* partition, and its two cheapest rows are
exactly the two largest blocks:

* `C_ELAB` — the 218-line TS2322 elaboration — is **1 reach in a whole compiler
  self-compile**. § 5 already called this out: the block every reader assumes is
  expensive runs once, because tsc's own sources produce no TS2322 at a return.
* `C_WALKERS` is the FP-firewall guard cluster the same partition classifies as
  the dedicated-walker layer (118 ms net of a 741 ms function, 9,340 reaches).

Everything level C prices as ENGINE work stays inline: the SOURCE type (219 ms),
flow narrowing (115), `checkConditionalReturnBranches` (46), `canUseTypeEngine` +
`checkTypeRelatedTo` (39), the TARGET type (20).

**A margin note the next agent should copy.** `C_ELAB` alone took the entry to
**7,803** — under the limit, and 197 bytecodes is not a margin: the entry would
cross back on the next edit and the guard pin would fail on somebody else's
commit. Round 807's rule ("a split that lands just over is one extraction short")
has a mirror: *a split that lands just under is one extraction short too.*

### 14.1 The shape, and the one thing that constrains the partition

`C_WALKERS` is a run of guard blocks each ending in a bare `return`, so it returns
`Boolean` (`true` = "a guard fired — it has either emitted or proved the return
legal — and the caller must return") and the entry replays it as `if (…) return`.
Its 20 `return@run` labels are internal to blocks that move whole and never
crossed the boundary. `C_ELAB` ends in an UNCONDITIONAL `return`, so it is `Unit`
and its call site returns unconditionally after it.

**Cross-boundary values: none**, computed rather than assumed. Every `val`/`var`
in the function was listed with its brace depth (20 of them) and intersected per
region against the identifiers that region uses. The only local either region
declares that outlives a statement is `effObjTarget`, declared at what was line
98789 and dead by 98840 — both inside `C_WALKERS`. One pair constrains the
partition and both its rows stay in the entry: `savedContextual`/`useCtx` are set
in `C_CTX` and read in `C_SRCTYPE`.

### 14.2 Equivalence, measured (round 805's five checks)

1. both moved runs re-extracted from the NEW file and checked back against HEAD:
   **two contiguous, in-order runs** of 281 and 217 lines, identical modulo the
   dedent and the `return` → `return true` rewrite (which was undone before the
   comparison);
2. the entry **reconstructed** from HEAD with the two regions replaced by their
   call sites: **IDENTICAL, 344 lines**;
3. accounting closes exactly — HEAD body 826 = kept 328 + 281 + 217;
4. every `return` enumerated: HEAD 53 = 33 bare + 20 `@run`; the new entry has
   **16 bare = 14 kept + 2 at the call sites**, `craGuardWalkers` 15 (14 rewritten
   + the added `return false`) and `craElaborateReturnMismatch` 5;
5. free variables computed per region, which is what keeps the build
   warning-clean (an unused parameter is a warning here).

The `return` rewrite was applied by locating matches on the STRING/COMMENT-
STRIPPED line and splicing at that offset in the raw line, with an assertion that
nothing but whitespace follows the token — so a `return` inside a comment or a
string cannot be rewritten, and a line carrying two of them fails loudly.

### 14.3 Discrimination — 1 of 2, and the second survived a purpose-built retry

`CraSplitTest` (13 pins) plus `HugeMethodLimitTest` (+3). Control first: 13 pins
ran, 0 failed. Each mistake was injected ALONE, on its own build, and every run
was confirmed to have RUN 13 pins (round 808's dead-build tell).

| mistake | pins failed | verdict |
|---|---|---|
| the entry ignores `craGuardWalkers`' `true` | **3** — the three property-level pins | **DISCRIMINATED** |
| the entry drops its `return` after `craElaborateReturnMismatch` | 0 | **NOT DISCRIMINATED** (twice) |

**What the first one teaches is that the pin that looked like the seam was not.**
The excess-property pin (`return { a: 1, b: 2 }` against `{ a: number }`) stays
GREEN under the mistake — an object literal with an excess property still relates
STRUCTURALLY, so the relation adds nothing. The pins that fail are the property-
MISMATCH ones, where the relation really does reach the same literal and append
its coarse whole-value TS2322 at the `return`.

**The second was re-attempted, not merely recorded.** The first run left every pin
green; the suspicion was that the legacy string tail (`C_STRTAIL`) simply could
not type the sources the arm pins used, so the TS2739 pin was rewritten from a
`declare const src: {}` source to a PARAMETER source (`function f(src: S): P`), a
shape whose type the string path holds in `varTypes` and whose engine emission is
a TS2739 the string path would follow with a TS2322 — a diff no dedup could hide.
It made no difference: **0 pins failed again.** So the finding is about the
function, not the pins — *the only thing after that return is a legacy
double-check that emits for nothing the engine has already rejected*, which is § 5
point 2 seen from the other side (85% of invocations exit inside the string tail
and it is worth 15 ms). On today's code the return is a redundant guard; it is
kept because the monolith had it, and the pin that was written for it is
**renamed** to say what it actually tests, per the standing rule.

### 14.4 Gate

Suite **13,596 → 13,612 / 0 failures / 3 skipped**; 8-profile grid diffed
set-for-set BOTH directions against a purpose-built pre-split binary, with the two
class dirs confirmed to differ (`javap` finds 3 `cra*` entries in one and 0 in the
other) and every capture confirmed non-empty — **46/46/46/46/46/46/46/94, 0 added
and 0 removed on all eight**; `--partitionCheck 2` **EQUIVALENT — 46**;
`cost_gate.py` **all 20 counters +0.00%**; no `w:` and no `e:` lines in the
compile that produced the binary. **No wall A/B, deliberately** — the family is
bounded four times over (§§ 4.2, 5.3, 7 and round 804) and this lands for the
threshold and the (f) falsifier.

## 15. (JIT.1)(c) — `checkSingleCallExpressionTypesCore`, 15,567 → an entry plus four helpers

*Round 811.* The last method of sub-item (c), the largest `Checker` method over
the limit anywhere, and the one the queue flagged as having **no committed
`*Sections` partition**. It has one: round 734's `CallSections` — the
(CALL.1)(a) instrument — partitions this exact function into 16 sections, with
an exit census and per-section costs already published in
`call-expression-attribution.md` §§ 3–4. Nothing had to be measured to choose
the boundaries.

| function | region of the committed `CallSections` partition | bytecodes |
|---|---|---:|
| `checkSingleCallExpressionTypesCore` | head, `CALLEE_TYPE`, `OPT_MEMBER`, `EARLY_GATES`, `CALL_SIGS`, `SINGLE_SIG`, `OVERLOADS` | **5,149** |
| `ccetUnionCalleeChecks` | `UNION_CALLEE` | 3,402 |
| `ccetNoCallSignatureDiagnostics` | `NO_SIGS` | 2,773 |
| `ccetExplicitTypeArguments` | `TYPE_ARGS` | 2,118 |
| `ccetPrologueWalkers` | `B216` .. `SUPER` | 2,068 |

Census **11 → 10**. The five sum to **15,510 against 15,567** — an eighth
confirmation that a bytecode count is a THRESHOLD predicate and not a cost model
(only round 804's 46,567 → 29,130 ever shrank).

### 15.1 What stays inline is what the partition PRICES

The exit census (§ 3 of `call-expression-attribution.md`, 52,413 invocations)
and the per-section table decide it, in both directions:

* **stays** — `getCalleeType` (474 ms, every invocation), the TS2722
  optional-member gate and the TS2347 / null-callee / any-bail cluster (102 ms,
  every invocation), `getCallSignaturesOfType`, the **single-signature branch**
  (1,560 ms and 42.2% of all exits) and the overload branch (3,640 exits);
* **moves** — the union-callee branch (**31** of 52,413 exits), the
  `signatures.isEmpty()` branch (**entered 0 times** on the compiler profile,
  and its `binderResults × top-level statements` scan with it), the
  explicit-type-argument branch (**101** exits), and the seven prologue walkers,
  which are 253 ms as one span with **zero firings** and which round 793's
  pre-gate already refuses for ~98% of call expressions.

**The constraint the queue attached to this target is honoured by
construction:** `ccetPrologueMayFire` — and the whole `if (runPrologue)` test —
**stays in the entry**; only the block it guards moved. A walker moved outside
that gate would silently never run, and the tell would be a corpus baseline
losing a diagnostic with no code change near the walker.

### 15.2 The shape, and the equivalence

Three regions return `Boolean` (`true` = "the caller must return"); the
`signatures.isEmpty()` branch returned UNCONDITIONALLY, so its helper is `Unit`,
its five internal `return`s stay bare, and the call site returns straight after
it. Round 805's five checks, all green:

1. all four moved runs re-extracted from the NEW file and compared against HEAD:
   **four contiguous, in-order runs** (131 / 224 / 240 / 122 lines), identical
   modulo the dedent and the return-signal rewrite;
2. the entry **reconstructed** from HEAD with the four regions replaced by their
   call sites: **IDENTICAL, 284 lines**;
3. accounting closes exactly — HEAD body 996 = kept 279 + moved 717; new entry
   284 = kept 279 + 5 lines of call site;
4. every `return` enumerated: HEAD 32 bare (plus 34 lines carrying a labeled
   `return@`), of which 10 stayed, 7 + 8 + 2 became `return true` and 5 stayed
   bare inside the `Unit` helper; the new entry has **14 = 10 kept + 4 at the
   call sites**;
5. free variables computed per region — `prologueT`/`calleeExpr` for the
   prologue, `calleeExpr`/`calleeType` for the two union-ish regions,
   `typeArgs`/`signatures` for the type-argument one. **Cross-boundary values:
   none.**

A tooling note that cost this round twenty minutes and generalises past this
file: **a Kotlin string/comment stripper must walk `${ … }` template expressions
with a brace counter, because a template may embed further string literals**
(`"Property '${key.text}' does not exist…"` is harmless, but
`"${f("x")}"`-shaped code is not). A scanner that stops at the next quote
desynchronises there and then blanks **40,000 lines**, which reads as "the
function has no returns and no free variables" — the round-809 length-preservation
check does NOT catch it, because blanking preserves length. The cheap catch is a
positive control: a known declaration inside the range must survive stripping.

### 15.3 Discrimination — 2 of 4, and both zeros survived a purpose-built retry

`CcetSplitTest` (18 pins) plus `HugeMethodLimitTest` (+3). Each mistake alone,
on its own build, control first (18 pins ran, 0 failed), every run's pin count
confirmed.

| mistake | pins failed | verdict |
|---|---|---|
| the entry drops its `return` after `ccetNoCallSignatureDiagnostics` | **6** | **DISCRIMINATED** |
| the entry ignores `ccetExplicitTypeArguments`' `true` | **2** — its own seam pin and the ordering pin | **DISCRIMINATED** |
| the entry ignores `ccetPrologueWalkers`' `true` | 0, **twice** | **NOT DISCRIMINATED** |
| the entry ignores `ccetUnionCalleeChecks`' `true` | 0, **twice** | **NOT DISCRIMINATED** |

**The two zeros are properties of the FUNCTION, and the retries are what
establish that.** The prologue's `super` shapes cannot discriminate by
construction — `getCalleeType("super")` answers `anyType`, so an entry that ran
on would bail at the any/error gate two sections later — so the retry used the
one walker whose continuation reaches a real signature (`reduce<U>` with a
`keyof` callback parameter, which the explicit-type-argument path would check
again against the instantiated lib signature). Green. The union's case (b)
cannot double **by construction** either: `getCallSignaturesOfType` concatenates
the constituents' signatures, so a union with any callable member has a NON-empty
list and the duplicate emitter — which lives in the `signatures.isEmpty()`
branch — is unreachable; the retry therefore used B516's combined signature, the
one case that leaves a non-empty list and still emits. Green as well.

Both are redundant guards on today's code, kept because the monolith had them.
The four pins written for them are **renamed as arm pins**, per the standing
rule, and the class doc carries the table.

**A process cost, again.** Two of the round's six ablation builds died with the
Kotlin daemon's `GC overhead limit exceeded` — one after **13m40s** — whose only
tell is `pins ran=0`. The driver records the count for every run, which is what
kept a dead build from being read as a clean ablation; recovery is `./gradlew
--stop` plus `pkill -f 'KotlinCompile[D]aemon'` (never `-9`).

### 15.4 Gate

Suite **13,612 → 13,633 / 0 failures / 3 skipped**; 8-profile grid diffed
set-for-set BOTH directions against a purpose-built pre-split binary, with the
class dirs confirmed to differ (`javap` finds the four `ccet*` helpers in one
and none in the other) — **46/46/46/46/46/46/46/94, 0 added and 0 removed on all
eight**; `--partitionCheck 2` **EQUIVALENT — 46**; `cost_gate.py` **all 20
counters +0.00%**; no `w:` and no `e:` lines in the compile that produced the
binary. **No wall A/B, deliberately** — the family is bounded four times over
(§§ 4.2, 5.3, 7 and round 804) and this lands for the threshold and the (f) gate.

**One trap in the grid harness, worth fixing before the next round reuses it.**
Arm B was first captured through `scripts/bench-compile-tsc.sh` with `--listAll`
patched into `NOEMIT_ARGS` — and the very next line of that script,
`[[ $NO_EMIT -eq 1 ]] && NOEMIT_ARGS=(--noEmit)`, overwrites it. The capture was
silently TRUNCATED at 30 of 46 diagnostics ("… and 16 more error(s)"), so the
grid read **0 added / 16 removed on every profile** — a regression that did not
exist. The arms are now run identically (direct `java … --noEmit --listAll`) and
the differ REFUSES any capture containing `and N more error`, alongside round
804's non-empty check.

## 16. (JIT.1)(d) — `checkDuplicateDeclarations`, 12,935 → an entry plus five helpers

*Round 812.* The largest `Checker` method left over the limit, and the first
target in the arc with **no committed partition of any kind** — the queue's
standing advice ("grep for an existing probe object FIRST") was followed and came
back empty: no `*Sections` object, no `PassTiming` row, no probe. The boundaries
had to be derived from the function's own shape, and the shape gives them.

| function | region of the body | bytecodes |
|---|---|---:|
| `checkDuplicateDeclarations` | the collection loop, the `export=` check, `groupBy`, the group-loop head and the `isDuplicate` tail | **2,801** |
| `cddCheckImportBindings` | TS2300 for duplicate `import =` / import bindings + 17.127's default-import TS2395 | 1,108 |
| `cddCheckMergedEnums` | TS2432 + cross-declaration member TS2300 | 955 |
| `cddCheckMergedTypeParameters` | TS2428 | 2,468 |
| `cddCheckExportUniformity` | TS2395 + TS2434 | 2,101 |
| `cddCheckValueRedeclarations` | TS2393, TS2813/TS2814, TS2323, the TS2451/TS2300 block-scoped cluster | 3,483 |

Census **10 → 9**. The six sum to **12,916 against 12,935** — a ninth
confirmation that a bytecode count is a THRESHOLD predicate and not a cost model
(only round 804's 46,567 → 29,130 ever shrank). The entry keeps **5,199 bytecodes
of headroom**, which is round 810's lesson applied: a split landing just under
the limit is one extraction short.

### 16.1 What stays in the entry is what EVERY input pays — decided by a guard, not a probe

With no partition to read, the frequency argument is STRUCTURAL and it is exact:

* the entry keeps the collection loop over `statements` (165 lines, a `when` over
  16 statement kinds) — **every statement list in the program pays it**, and it
  is the only part of the function that is a function of the input's SIZE;
* it keeps `decls.groupBy { it.name }`, the `export=` duplicate check, and the
  group loop's own head — including **`if (group.size < 2) continue`**;
* **every moved region sits behind that guard**, i.e. behind a name declared at
  least twice in one scope, and four of the five are behind a further kind
  predicate (`hasEnum`, `hasInterface`, an `import` in the group, …);
* the `isDuplicate` tail stays, because it reads `hasClass`/`classCount`/
  `namespaceVarAllowed` — all computed in the entry — and is three boolean tests.

This is a weaker instrument than rounds 807–811's measured partitions and it is
worth saying so: it bounds the moved population by a GUARD rather than pricing
it. But the guard is decisive in a way a cost table is not — a group of one is
the overwhelming majority of every scope, and no measurement can make the moved
regions run more often than the guard admits.

### 16.2 The shape problem: a loop body inside a loop body

This is the first target whose regions live in a **nested** loop, and it brings a
question rounds 803–811 never had to ask: **which loop does each `continue` bind
to?** A `continue` that binds to the outer `for ((_, group) in byName)` is an
exit from the region and must become a return signal; one that binds to an inner
`for (decl in group)` is ordinary control flow and must be left alone. The two
are INDISTINGUISHABLE by indentation — the region's own `for` loops are indented
exactly like the blocks the outer `continue`s sit in.

`scripts/dupdecl_split_analyze.py` answers it with a brace-matching scan seeded
from the function start (so a region's braces are matched against their real
enclosing context) and reports, per region, the innermost enclosing LOOP header
of every `continue`. Result: of the 23 `continue`s in the function, **7 bind to
the group loop and all 7 are inside the V region**; the eighth `continue` in that
same region binds to `for (decl in group)` and is untouched. The function has
**zero whole-function `return`s** — every `return` in its 872 lines is a
`return@` or lives inside a local `fun` — so, unlike (f), no region needs a
`RETURN` token.

Two further facts the analysis fixed before any code moved:

* **`val hasInterface` STAYS in the entry.** It is declared immediately above the
  TS2428 block and looks like part of it, but the V region's TS2451 gates read it
  200 lines later. Moving it with the block would have forced either a second
  cross-boundary value or a silent recomputation.
* **`emitted2395` is the ONLY value that crosses a boundary**, and it is
  RETURNED, not stashed in a `Checker` field (round 804's rule: a field would
  need round 791's save/restore to survive a nested invocation).

The local `data class DeclInfo` is hoisted to a private nested class, because
five helper signatures name it. That is the round's only non-mechanical edit and
it is behaviour-free: the class captures nothing and is still constructed only by
the collection loop.

### 16.3 Equivalence, measured (round 805's five checks)

`scripts/dupdecl_split_{analyze,apply,verify}.py`, all green:

1. all five moved runs re-extracted from the NEW file and compared against HEAD:
   **five contiguous, in-order runs** (54 / 54 / 150 / 98 / 249 lines), identical
   modulo the dedent and the `continue` → `return true` rewrite;
2. the entry **reconstructed** from HEAD with the regions replaced by their call
   sites and the hoisted line removed: **IDENTICAL, 278 lines**;
3. accounting closes exactly — HEAD body 872 = kept 266 + moved 605 + 1 hoisted;
   new entry 278 = kept 266 + 12 lines of call site;
4. every `return` and every `continue` enumerated: **0 bare returns** on both
   sides, and HEAD's 23 `continue`s = the new tree's 17 − 1 replay (the V call
   site's own `continue`) + 7 signals;
5. free variables computed per region — no region needs a value the call site
   does not already hold, and `hasInterface`/`classCount` were the two that
   looked free and are not (one stays in the entry, one is shadowed inside the
   region by its own declaration).

The stripper is round 811's, with its `${ … }` brace-counting template walk and
its positive control (three known declarations inside the range must survive
stripping — a length check cannot see a desynchronised scanner, because blanking
preserves length).

### 16.4 Discrimination — 2 of 3, and the third is provably undiscriminable

`CddSplitTest` (15 pins) plus `HugeMethodLimitTest` (+3). Each mistake alone, on
its own build, control first (45 pins ran, 0 failed), every run's pin count
confirmed.

| mistake | pins failed | verdict |
|---|---|---|
| the entry discards `cddCheckValueRedeclarations`' `true` | **1** — its seam pin | **DISCRIMINATED** |
| the entry ignores `emitted2395` | **1** — its seam pin | **DISCRIMINATED** |
| the block-scoped exit returns `false` instead of `true` | 0 | **NOT DISCRIMINATED — and no shape can** |

Both discriminating mistakes fail through the same mechanism and it is worth
naming, because it is what made the seams testable at all: **the failure mode of
a dropped signal here is a SUPERSEDED check running anyway**, so the pin asserts a
diagnostic that must NOT appear (`none { it.code == 2300 }`) while a companion
arm pin asserts what must. `export class C {} class C {}` and
`class D {} class D {} function D() {}` both leave `hasClass && classCount >= 2`
true, which is exactly the `isDuplicate` tail's condition — so an entry that runs
on adds a TS2300 per declaration. Note that only ONE pin failed in each case: the
arm pins for TS2813/TS2814 and TS2395 keep passing, because the mistake ADDS a
diagnostic rather than removing one. A count pin on the arm's own code cannot see
this class of mistake, which is why the seam pins are written on a different code.

**The third is not a measurement failure, it is a property of the code, and the
proof is exhaustive rather than a retry.** The exit is taken only when
`allBlockScoped` holds, i.e. `!hasVar && !hasFunc && !hasClass && !hasEnum &&
!hasInterface && !hasNamespace2 && !hasImport`. Exactly two things can run after
it: the `hasBlockScoped && (hasVar || hasFunc || hasClass || hasEnum)` block —
whose condition `allBlockScoped` negates term by term — and the entry's
`isDuplicate` tail, which needs `hasClass` or `hasVar`. Both are the complement
of the predicate that reached the exit, so no input can observe the signal. A
purpose-built retry was therefore not attempted: unlike round 811's two zeros,
which needed a constructed shape to rule out, this one is closed by reading the
guards. It is a redundant guard on today's code, kept because the monolith had
it, and its pin is named as an ARM pin per the standing rule.

### 16.5 Gate

Census re-measured at HEAD on a rebuilt binary first — **10** over the limit,
`checkDuplicateDeclarations` **12,935**, reproducing the round-811 handoff
exactly; the after-number was measured the same way on the binary built from the
split source — **9**. Suite **13,633 → 13,651 / 0 failures / 3 skipped** (+18:
15 `CddSplitTest` + 3 `HugeMethodLimitTest`), python XML parser, whole results
dir wiped first. 8-profile grid diffed set-for-set BOTH directions against a
purpose-built pre-split binary, class dirs confirmed to differ (`javap` finds the
five `cdd*` helpers in one and none in the other) — **46/46/46/46/46/46/46/94, 0
added and 0 removed on all eight**; `--partitionCheck 2` **EQUIVALENT — 46**;
`cost_gate.py` **all 20 counters +0.00%**; no `w:` and no `e:` lines in any of the
three compiles. **No wall A/B, deliberately** — the family is bounded four times
over (§§ 4.2, 5.3, 7 and round 804) and this lands for the threshold and the (f)
gate.

## 17. (JIT.1)(d) — `checkIndexSigInStatement`, 10,928 → an entry plus seven helpers

*Round 813.* The second target with **no committed partition of any kind** (round
812 checked all four remaining `Checker` targets and found none), and the one the
handoff called the easiest of them: a straight sequence of self-contained blocks
whose only cross-boundary value is a `var` one block mutates.

| function | region of the body | bytecodes |
|---|---|---:|
| `checkIndexSigInStatement` | the dispatch head, the two index-signature lookups, the guards, the early `return`s | **1,010** |
| `cisCheckNamedInterfaceIndexValueConflict` | B98.r128b's TS2413 + B272's primitive pair | 2,680 |
| `cisCheckAnonIndexValueConflict` | B98.r20's TS2413 for anonymous index values | 1,684 |
| `cisCheckNumericMethodsVsNumberIndex` | B272's TS2411 for numeric-named methods | 1,623 |
| `cisFindStringIndexSig` | the own-then-inherited `[s: string]: T` lookup | 1,504 |
| `cisCheckPropsVsStringIndex` | the general TS2411 property loop | 1,021 |
| `cisCheckMethodsVsPrimitiveStringIndex` | 16.4ez's TS2411 for methods vs a primitive index | 822 |
| `cisCheckNumericNamePropsVsNumberIndex` | 17.191's numeric-name property loop | 359 |

Census **9 → 8**. The eight sum to **10,703 against the monolith's 10,928** — a
tenth confirmation that a bytecode count is a THRESHOLD predicate and not a cost
model (only round 804's 46,567 → 29,130 ever shrank). The entry keeps **6,990
bytecodes of headroom**, which is round 810's lesson applied with room to spare.

### 17.1 What stays in the entry is what EVERY input pays — a guard, not a probe

With no partition to read, the frequency argument is structural, and here it is
sharper than round 812's because the guards are *kind* tests on the statement:

* the entry keeps the `TypeAliasDeclaration` branch and the `VariableStatement`
  branch, both of which `return`;
* it keeps the `when` that decides whether the statement has members at all —
  **every other statement kind `return`s in its `else` arm**, so the entire rest
  of the function is behind "this statement is a class or an interface";
* it keeps the `ModuleDeclaration` recursion, the `numberIndexSig` and
  `stringIndexSig` lookups (whose results two and three helpers read), the
  `numberIndexType != null` guard, the `stringIndexTypeIsPrimitive` guard, and
  the early `return` for "no usable string index type" — **which is what every
  class or interface WITHOUT a string index signature pays and nothing more**;
* every moved region is behind one of those.

Like round 812 this BOUNDS the moved population rather than pricing it, and the
same caveat applies: it is a weaker instrument than rounds 807–811's measured
partitions. It is also decisive in the same way — no measurement can make a
moved region run more often than its guard admits.

### 17.2 The shape, and the one value that crosses

The analysis (`scripts/indexsig_split_analyze.py`, round 811's length-preserving
stripper plus round 812's brace-matching `continue` census) reports, per region:

* **no region contains a whole-function `return`.** All 6 bare `return`s in the
  543-line body are in the kept dispatch head; the only other `return` tokens in
  any moved region are three inside `fun nonEmptyUserIface`, a local function that
  moves whole. So — unlike (f), (g), (h) and (c) — **no helper needs a return
  signal at all**, and the split introduces no `Boolean` protocol.
* **no `continue` escapes its region**: all 32 are inside loops the region owns.
* **exactly one local crosses a boundary** — the string index signature. HEAD
  declared it `var`, seeded it from the type's OWN members and let a base-class
  walk overwrite it; `cisFindStringIndexSig` RETURNS it instead, per round 804's
  rule (a `Checker` field would need round 791's save/restore, and this function
  recurses through `ModuleDeclaration`).

Two smaller boundaries are values the entry computes and a helper reads:
`numberIndexSig` (read by the anonymous-value check as its `effNumberSig` seed)
and `stringIndexTypeIsPrimitive` (read by the general property loop, where it is
what makes that loop DEFER methods instead of double-reporting them).

A detail worth recording because it removed the only non-mechanical edit this
split would otherwise have needed: **two regions contain a `when (stmt)` that is
exhaustive only because of an enclosing `if (stmt is ClassDeclaration || stmt is
InterfaceDeclaration)`.** Moving the *body* would have forced an `else` arm into
each; moving the **whole `if` statement, condition included**, keeps them verbatim
— and costs nothing, because at that point in the entry the condition is already
true by construction (the `members` `when` returned for every other kind).

### 17.3 Equivalence, measured (round 805's five checks)

`scripts/indexsig_split_{analyze,apply,verify}.py`, all green:

1. all seven moved runs re-extracted from the NEW file and compared against HEAD:
   **seven contiguous, in-order runs** (38 / 32 / 57 / 126 / 46 / 51 / 95 lines),
   identical modulo a uniform dedent (4 for two of them, 0 for the rest);
2. the entry **reconstructed** from HEAD with the regions replaced by their call
   sites: **IDENTICAL, 105 lines**;
3. accounting closes exactly — HEAD body 543 = kept 98 + moved 445; new entry
   105 = kept 98 + 7 call lines;
4. every `return` and `continue` enumerated on both sides: **6 bare returns and
   32 continues in HEAD, 6 and 32 in the new tree**;
5. free variables computed PER REGION and then re-asserted against what the
   helper signatures and the call sites actually name. The check that matters
   here is a small one: a free-variable scan must not count `.members` as a read
   of the local `members` — an unqualified `\bmembers\b` reports three regions as
   needing a parameter they never use, and an unused parameter is a build warning
   in this project.

### 17.4 Discrimination — 4 of 4, and the first one only after a purpose-built retry

`CisSplitTest` (17 pins) plus `HugeMethodLimitTest` (+3). **All 16 original pins
were validated on the UNSPLIT binary first — 16 ran, 0 failed** — so they state
HEAD's behaviour rather than the split's; that run also caught two wrong pins
before any code moved (see § 17.5). Control on the split binary: 49 pins ran, 0
failed. Each mistake alone, on its own build, pin count confirmed every time.

| mistake | pins failed | verdict |
|---|---|---|
| `cisFindStringIndexSig` returns only the OWN signature | **1** — its seam pin | **DISCRIMINATED** (after a retry) |
| the entry passes `null` for `numberIndexSig` | **1** — **exactly** its seam pin | **DISCRIMINATED, sharply** |
| the entry passes `false` for `stringIndexTypeIsPrimitive` | **2** | **DISCRIMINATED** |
| the entry's `if (stringIndexTypeIsPrimitive)` guard dropped | **2** | **DISCRIMINATED** |

**The first one is the round's transferable result.** The seam pin written for
it — an interface extending a class that declares `[s: string]: number`, with a
`string` property — stayed **GREEN on the ablated binary**, i.e. it was blind:
a sibling pass reports the same TS2411 for a PRIMITIVE inherited index type.
Rather than record the seam as undiscriminated, the ablated binary was **diffed
against the committed one over eight inherited-index shapes** (interface-extends-
class, class-extends-class, interface-extends-interface, `implements`, inherited
primitive vs a method, a numeric property, an inherited CALLABLE index, an
inherited index PAIR). **Exactly one line differs**: a method checked against an
inherited **callable** string index value type. Seven of the eight are supplied
redundantly by a sibling pass and only that one is uniquely ours. The pin is now
that shape, it fails on the ablated binary and on nothing else, and the old pin is
renamed as an arm pin carrying a comment about what it does not discriminate.

The last two fail through round 812's mechanism unchanged: **the failure mode of
a dropped value here is a SUPERSEDED check running anyway**, so what sees it is a
`none { … }` assertion (a method WITH parameters must stay silent while the string
index type is primitive) and a `count == 1` assertion — a count pin on the arm's
own code cannot.

### 17.5 What did not work

* Two of the sixteen pins failed on the unsplit control and were wrong, not the
  compiler: the type-alias branch `return`s **before** the string-index
  machinery, so `type T = { [s: string]: number; p: string }` reports no TS2411
  at all (the branch's own product is 17.159's TS1337, which needs the alias's
  type-parameter names — a sharper pin for it); and TS2374 fires **once per
  duplicate signature**, not once per type. Both were caught by running the pins
  against HEAD before the split, which is the cheap order.
* One ablation build died with the Kotlin daemon's **`GC overhead limit
  exceeded`** after 5m51s. Its only tell is `pins ran = 0`; a plain rebuild
  succeeded in 2m22s and the ablation then reported normally. Round 808's rule
  again: **check the build succeeded before recording a zero.**

### 17.6 Gate

Census re-measured at HEAD on a rebuilt binary first — **9** over the limit,
`checkIndexSigInStatement` **10,928**, reproducing the round-812 handoff exactly;
the after-number measured the same way on the binary built from the split source
— **8**. Suite **13,651 → 13,671 / 0 failures / 3 skipped** (+20: 17
`CisSplitTest` + 3 `HugeMethodLimitTest`), python XML parser, whole results dir
wiped first. 8-profile grid diffed set-for-set BOTH directions against a
purpose-built pre-split binary, class dirs confirmed to differ (`javap` finds 15
`cis*` entries in one and 0 in the other), every capture checked non-empty and
non-truncated — **46/46/46/46/46/46/46/94, 0 added and 0 removed on all eight**;
`--partitionCheck 2` **EQUIVALENT — 46**; `cost_gate.py` **all 20 counters
+0.00%**; no `w:` and no `e:` lines in the compiles that produced the binaries.
**No wall A/B, deliberately** — the family is bounded four times over (§§ 4.2,
5.3, 7 and round 804) and this lands for the threshold and the (f) gate.

**The `Checker` list is now two, plus one odd one.** `tryInferSingleTypeParamFromArgs`
**11,930** (two 300–400-line `for (i in params.indices)` bodies plus a 132-line
constraint block, with mutable locals crossing every boundary — the hard one), the
`Checker` **constructor 11,298** (contiguous runs of its ~417 `pass("init:…")`
dispatches; no returns, no loops; moving statements OUT of `init` into a private
method preserves order and is safe, ADDING a field is not), and
`access$checkBigintPropertyNames$emit` **10,339**, which is not the 8-line local
`emit` its name suggests but the whole per-file body the anonymous walker object
closes over.

## 18. (JIT.1)(d) — the `Checker` constructor, 11,298 → an entry plus ten helpers

*Round 814.* The cheapest remaining `Checker` target, and the one whose shape
breaks the pattern the previous seven rounds established.

| function | region of the `init` body | bytecodes |
|---|---|---:|
| `Checker.<init>` | the `try`/`catch` boundary, `PassTiming.noteInitStart`/`noteInitEnd`, the two `declarationOnly` branches, ten call sites — **and the class's 494 property initializers** | **5,538** |
| `initCheckPasses5` | `checkOptionalParamNullishArithmetic` .. `checkCircularClassBaseViaDefaultTypeArg` (67 dispatches) | 804 |
| `initCheckPasses6` | `checkCircularBaseTypeReferences` .. `checkCallTypeArgCount` (61) | 799 |
| `initCheckPasses2` | `checkJsDocTypedefIndexSignature` .. `checkDefaultImports` (61) | 792 |
| `initCheckPasses4` | `checkUninitializedLetCapturedReads` .. `checkDestructuringDefaultTypeMismatches` (63) | 786 |
| `initCheckPasses3` | `checkNamespaceImportSyntheticDefaultCall` .. `checkCrossNamespaceClassHeritageUBD` (55) | 752 |
| `initCheckPasses7` | `checkReverseMappedExcessProps` .. `checkConditionalTypeAssignabilityDeferred` (41) | 661 |
| `initCheckPasses8` | `checkBuiltinIterator` .. `init:tpTargetReturnDedup` (49) | 588 |
| `initCheckPasses1` | `checkUnusedDeclarations` .. `checkJSDocTypedefTags` (24) | 415 |
| `initSetupPasses` | the (SETUP.1) prologue, `checkLibOption` .. `init:buildFileLocalTypeMaps` (14) | 185 |
| `initDeclarationOnlyPasses` | the body of `if (declarationOnly)` (1) | 12 |

Census **8 → 7**, and the `Checker` list is down to **two**. The eleven sum to
**11,332 against the monolith's 11,298** — the split ADDED 34 bytecodes (the ten
call sites), an eleventh confirmation that a bytecode count is a THRESHOLD
predicate and not a cost model.

### 18.1 Two things about this target that are not true of any earlier one

**(a) The frequency argument is DEGENERATE, and pretending otherwise would be a
lie.** Every round from 807 to 813 chose what stays in the entry by asking which
regions every input pays for — a measured partition (807–811) or a structural
guard (812, 813). A constructor runs **exactly once per compile and every input
pays all of it**. Nothing here is cold, nothing can be "moved because it is
rarely reached", and the only cut criterion left is SIZE. The eight checking runs
are therefore contiguous slices of roughly equal code-line count, and the doc says
so rather than dressing an arbitrary cut as an argument.

**(b) 5,538 of the entry's bytecodes are not the pass sequence at all.** They are
the class's **494 property initializers**, which a JVM constructor cannot delegate
to a helper: `private val x = …` compiles into `<init>` by definition. So the
whole ~437-dispatch sequence is worth only ~5,760 bytecodes (~13 each — the
`pass("name") { … }` lambdas are separate methods, because `pass` is deliberately
non-inline), and the constructor was over the limit chiefly because the two
halves happened to sum past it. The consequence for a future agent: **the entry's
headroom, 2,462 bytecodes, is consumed by ADDING FIELDS, not by adding passes** —
a new `pass(…)` line lands in a helper, a new `private val` lands in `<init>`. At
~11 bytecodes per field that is room for roughly 200 more.

A corollary worth stating because it bounds the prize honestly: the constructor
runs once, and its inline work was never the loop-bearing part (the loops all live
inside `pass` lambdas, i.e. in their own methods, already JIT-eligible). **This
split therefore buys no wall time and none was measured.** It lands for the
threshold and for sub-item (f), the `--fail-over 0` gate.

### 18.2 The shape, and why no helper needs a signal

`scripts/init_split_analyze.py` (round 811's length-preserving stripper plus a
depth walk seeded at the `init {` line) reports:

* **443 body-level statements**, of which `return`/`break`/`continue`: **zero**.
  A constructor body cannot contain a bare `return` that skips the rest, so no
  helper needs an (f)-style `Boolean` protocol and none was invented.
* **no loops at body level** — every `for` in those 2,094 lines is inside a
  `pass(…)` lambda, so nothing binds a `continue` across a region boundary
  (round 812's whole shape problem is absent here).
* **exactly two body-level locals**: `preAugmentationGlobalsKeys` (declared and
  read only inside the setup prologue) and `shouldCheckDefiniteAssignment`
  (declared at the top of run 1, read 400 lines later — still inside run 1, which
  is **why the first boundary sits where it does**). So **cross-boundary values:
  none**, and every helper is parameterless — which the verifier asserts rather
  than assumes.

Nine of the ten regions move with **dedent 0**: the `if (!declarationOnly) { … }`
body is written at the same indentation as the `try` body, which is exactly a
private method's body indentation. Only the `declarationOnly` branch dedents (4).

### 18.3 Equivalence, measured (round 805's five checks)

`scripts/init_split_{analyze,apply,verify}.py`, all green:

1. all ten moved runs re-extracted from the NEW file and compared against HEAD:
   **ten contiguous, in-order runs** (133 / 29 / 572 / 218 / 231 / 241 / 229 /
   181 / 147 / 80 lines), identical modulo the uniform dedent;
2. the `init` block **reconstructed** from HEAD with the regions replaced by their
   call sites: **IDENTICAL, 43 lines**;
3. accounting closes exactly — HEAD `init` 2,094 = kept 33 + moved 2,061; new
   entry 43 = kept 33 + 10 call lines;
4. `return`/`break`/`continue` enumerated on both sides: 15 and 15 (all of them
   inside `pass` lambdas; **0 at body level**);
5. free variables per region — none, re-asserted as "every helper signature is
   `()`" plus "the ten call sites appear in the regions' source order", which is
   the property this target's correctness actually reduces to.

### 18.4 Discrimination — the seams a pure sequence still has

`CtorSplitTest` (13 pins) plus `HugeMethodLimitTest` (+3). **All 13 pins were
validated on the UNSPLIT binary first — 13 ran, 0 failed.**

A split with no cross-boundary values and no control flow has only two things it
can get wrong — the ORDER of the runs and which side of the `declarationOnly`
guard they sit on — and each was ablated ALONE, on its own build, with the pin
count confirmed to have RUN every time.

| mistake | pins failed | verdict |
|---|---|---|
| `initCheckPasses8()` moved to the HEAD of the block (run 8 no longer last) | **1** — **exactly** its seam pin | **DISCRIMINATED, sharply** |
| `initCheckPasses1()` hoisted OUT of `if (!declarationOnly)` | **2** | **DISCRIMINATED** |
| the `initCheckPasses5()` call deleted | **1** — its arm pin | **DISCRIMINATED** |

* **ORDER.** `checkBuiltinIterator` is the FIRST pass of run 8 and RETRACTS every
  TS2339 of a file matching its gate — a diagnostic `checkSpine` (run 1) emitted.
  It can only do that while run 8 runs after run 1: reorder them and the
  retraction runs against a list that does not yet hold the diagnostic, so the
  TS2339 survives. The pin has its own positive control (the same source WITHOUT
  the gate string must report the TS2339), so it is not a vacuous `none { … }` —
  which matters here because the pin it replaced failed exactly that way (§ 18.5).
* **THE `declarationOnly` GUARD.** The eight checking runs stay inside
  `if (!declarationOnly)`. The pin compiles an `emitDeclarationOnly` project and
  asserts that a name-resolution error is still reported (the declarationOnly run
  ran) while an unused local is NOT (run 1 did not). The second failure under this
  ablation is a knock-on and is expected: with run 1 also running, the TS2304 is
  reported TWICE, so the arm pin's `count == 1` fails alongside the seam pin.
* **THE ARM PINS BIND TO THEIR RUN.** Deleting one call site (run 5) fails
  **exactly one** pin — the TS2456 circular-type-alias arm — and nothing else, so
  the one-arm-per-helper set really does localise a dropped call.

The full arm set is one diagnostic only a pass in that run produces: TS6046 /
TS2304-under-emitDeclarationOnly / TS6133 / TS2307 / TS1185 / TS1108 / TS2456 /
TS2729 / TS7022 / the retraction.

### 18.5 What did not work

* **The first choice of run-8 arm pin, `applyDomLibSuggestionRewrite`, is
  unreachable from a hand-written source.** It rewrites a TS2339 whose receiver is
  an EMPTY user-declared DOM stub (`interface Element {}`) into TS2812 — but our
  checker emits **no diagnostic at all** for a property read on a member-less
  interface, on four shapes probed (`Element`/`Node`/`HTMLDivElement`, with and
  without `@lib`). The pin failed on the UNSPLIT binary, which is the cheap order
  working exactly as round 813 described; the seam pin written beside it
  (`none { it.code == 2339 }`) passed **vacuously** on an empty diagnostic list,
  which is the failure mode a positive control exists to catch. Both were replaced
  by the `checkBuiltinIterator` retraction pair above.
* A foreground `./gradlew` invocation is not viable in this harness: the tool's
  2-minute ceiling kills the shell mid-task. Every Gradle run this round was
  `nohup setsid … &` with a `.done` marker.
* The first `compileKotlinJvm` of the round died after 11m21s with BUILD.1's
  `Not enough memory to run compilation`, and the third ablation build died after
  4m58s with the Kotlin daemon's `GC overhead limit exceeded` — the same failure
  in its two costumes, four rounds running. Its only tell in an ablation is
  **`PINS RAN 0`**, which is indistinguishable from a clean ablation; recovery is
  `./gradlew --stop` plus a graceful bracket-pattern
  `pkill -f 'KotlinCompile[D]aemon'` (never `-9`), after which the same build
  succeeded in ~1m30s.

### 18.6 Gate

Census re-measured at HEAD on a rebuilt binary first — **8** over the limit,
`Checker.<init>` **11,298**, reproducing the round-813 handoff exactly; the
after-number measured the same way on the binary built from the split source —
**7**. Suite **13,671 → 13,687 / 0 failures / 3 skipped** (+16: 13 `CtorSplitTest`
+ 3 `HugeMethodLimitTest`), python XML parser, whole results dir wiped first.
8-profile grid diffed set-for-set BOTH directions against a purpose-built
pre-split binary, class dirs confirmed to differ (`javap` finds 447
`init*Passes` entries in one and 0 in the other), every capture checked non-empty
and non-truncated — **46/46/46/46/46/46/46/94, 0 added and 0 removed on all
eight**; `--partitionCheck 2` **EQUIVALENT — 46**; `cost_gate.py` **all 20
counters +0.00%**; no `w:` and no `e:` lines in the compiles that produced the
binaries. **No wall A/B, deliberately** — see § 18.1(b).

**The `Checker` list is now ONE, plus one odd one.**
`tryInferSingleTypeParamFromArgs` **11,930** — two 300–400-line
`for (i in params.indices)` bodies plus a 132-line constraint block, with mutable
locals crossing every boundary, so it is the first target in the family that
needs a real data-flow answer rather than a contiguity argument — and
`access$checkBigintPropertyNames$emit` **10,339**, which is not the 8-line local
`emit` its name suggests but the whole per-file body the anonymous walker object
closes over.

## 19. (JIT.1)(e) — `applyDirective`, 13,694 → an entry at 89 plus four helpers

*Round 815.* The first target outside `Checker`, and the one whose SIZE has the
least to do with what it does.

| function | region of the body | bytecodes |
|---|---|---:|
| `applyDirective` | `boolValue`, four call sites, the `?: options` tail | **89** |
| `applyDirectiveArms2` | `"noimplicitthis"` .. `"esmoduleinterop"` (22 arms) | 4,592 |
| `applyDirectiveArms4` | `"noresolve"` .. `"capturesuggestions"` (26 arms) | 3,708 |
| `applyDirectiveArms3` | `"allowjs"` .. `"nofallthroughcasesinswitch"` (22 arms) | 3,164 |
| `applyDirectiveArms1` | `"target"` .. `"noimplicitreturns"` (15 arms) | 2,240 |

Census **7 → 6**. The four sum to **13,704** against the monolith's **13,694**,
so with the 89-byte entry the split **ADDED 99 bytecodes** — the eleventh
confirmation in this family that a bytecode count is a THRESHOLD predicate and
not a cost model. Headroom on the largest run: **3,408**.

### 19.1 Why it was over the limit, and what that implies for the remaining targets

`applyDirective` is 116 lines: one `when (key)` over **85 String-constant arms**,
each of them a single `options.copy(field = …)`. There is no loop, no recursion,
no nesting and one `return`. It is over HotSpot's limit anyway, because
**`CompilerOptions` is a ~150-field data class and Kotlin compiles a named-argument
`copy` into a `copy$default` CALL SITE carrying the entire argument vector plus
the default bitmasks** — measured **~160 bytecodes per arm**, for source that
reads as one line.

So the size here is **the arm count times the data class's field count**, and
two things follow. (1) A future agent adding directives walks the limit back up
at ~160 bytecodes each: the runs have 5,760 (arms1) / 3,408 (arms2) / 4,836
(arms3) / 4,292 (arms4) of headroom, i.e. room for roughly 21–36 new arms apiece
before another cut is needed. (2) The same multiplication applies to any `when` that copies a wide
data class per arm, whether or not it looks big — **`javap`, not line count, is
the instrument.**

### 19.2 The frequency argument, and the seam this shape does NOT have

`applyDirective` is called once per directive per file — it is not on any hot
path, and no wall claim is made or should be. Like round 814's constructor, this
lands for the **threshold** and for sub-item (f).

What is worth recording is the seam analysis, because this target is the first
whose partition is **provably order-insensitive**:

* the 85 arm keys are **pairwise DISTINCT** (`applydirective_split_analyze.py`
  asserts it), so a single `when` over all of them and a `?:`-chain over a
  partition of them select the same arm whatever order the runs are consulted
  in;
* **no arm evaluates to `null`** — every arm is `options` or `options.copy(…)`,
  including the two that answer `options` unchanged when `ScriptTarget`/
  `ModuleKind` refuse the value — so `?:` cannot skip a matched arm;
* therefore the mistakes this shape admits are exactly three: **dropping a run
  from the chain**, **writing a run's fallthrough as `options` instead of
  `null`** (which silently swallows every LATER run), and **recomputing
  `boolValue`** instead of taking the entry's. `boolValue` is the only value
  that crosses a boundary at all.

### 19.3 Equivalence, measured (round 805's five checks)

`scripts/applydirective_split_{analyze,apply,verify}.py`, all green:

1. four contiguous, in-order runs (28 / 28 / 28 / 26 lines) re-extracted from
   the NEW file and compared **verbatim against HEAD at dedent 0** — each helper
   is written as a block body with `return when (key) {`, so the arms keep their
   original 8-space indentation and no line is edited at all;
2. the new file **RECONSTRUCTED** from HEAD by the apply step and compared byte
   for byte: **identical, 62,424 chars**;
3. accounting closes exactly — 110 arm lines moved; the entry is the signature +
   `boolValue` + four call lines + `?: options` + the brace;
4. control-flow tokens enumerated on both sides: **1 `return` in HEAD, 5 in the
   new tree** (one per function), **0 `continue`/`break` either side**;
5. free variables computed per run and re-asserted against the helper
   signatures: every run reads `options`, `value` AND `boolValue`, which is what
   keeps the warning-clean build warning-clean — an unused parameter is a `w:`
   here, so the partition is not free to put all the boolean arms in one run.

### 19.4 Discrimination — 3 of 3, plus a NEGATIVE CONTROL that measured the claim

`ApplyDirectiveSplitTest` (16 pins) plus `HugeMethodLimitTest` (+3). **All 16
were validated on the UNSPLIT binary first — 16 ran, 0 failed.** Each mistake
alone, on its own build, pin count confirmed every time.

| mistake | pins failed | verdict |
|---|---|---|
| the chain drops `applyDirectiveArms3` | **5** — coverage + all three run-3 arms + `boolValue` (it names `checkjs`) | **DISCRIMINATED, attributable to the run** |
| run 1's fallthrough is `options`, not `null` | **12** — every run-2/3/4 pin, coverage, and the end-to-end directive pin | **DISCRIMINATED** |
| the entry drops `.lowercase()` | **1** — **exactly** the `boolValue` seam pin | **DISCRIMINATED, sharply** |
| *(negative control)* the run-1/run-2 boundary moved by one arm | **0** | **UNOBSERVABLE, as predicted** |

**The fourth row is the round's transferable result.** Earlier rounds in this
family (812's third seam, 813's first, 815's own predecessors at §§ 14.3 and
15.3) each hit a seam they could not discriminate and had to argue around; here
the claim "the partition POSITION is unobservable" is not an excuse
for a missing pin, it is a **prediction that was tested**: moving
`"noimplicitreturns"` from the end of run 1 to the head of run 2 changes nothing
any pin can see, exactly because the keys are distinct and both runs are
consulted. **When a split's correctness rests on a structural property, ablate
the property's CONSEQUENCE and show the zero, rather than recording "no seam
here".** A zero that was predicted in advance is evidence; a zero that was
discovered is a blind pin.

### 19.5 Gate

Census re-measured at HEAD on a rebuilt binary first — **7** over the limit,
`applyDirective` **13,694**, reproducing the round-814 handoff exactly; the
after-number measured the same way on the binary built from the split source —
**6**. Suite **13,687 → 13,706 / 0 failures / 3 skipped** (+19: 16
`ApplyDirectiveSplitTest` + 3 `HugeMethodLimitTest`), python XML parser, whole
results dir wiped first. 8-profile grid diffed set-for-set BOTH directions
against a purpose-built pre-split binary, both arms running the IDENTICAL direct
`java` command line with absolute class dirs (no bench script, so round 811's
`NOEMIT_ARGS` truncation cannot arise), class dirs confirmed to differ (4
`applyDirectiveArms` entries vs 0), every capture checked non-empty, non-vacuous
and free of an `and N more error` marker — **46/46/46/46/46/46/46/94, 0 added
and 0 removed on all eight**; `--partitionCheck 2` **EQUIVALENT — 46**;
`cost_gate.py` **every counter unchanged, +0.00%**; no `w:` and no `e:` lines in
the compile that produced the binary. **No wall A/B, deliberately** — the family
is bounded four times over (§§ 4.2, 5.3, 7 and round 804), this function runs
once per directive, and it lands for the threshold and the (f) gate.

**The list is now six, and none of them is in `Checker`'s hot path.**
`Transformer.transformToCommonJS` **28,991**,
`TypeScriptCompiler.compileParsedCore` **21,535**,
`Transformer.transformClassBody` **16,233**,
`Checker.tryInferSingleTypeParamFromArgs` **11,930** (the hard one — two
300–400-line `for (i in params.indices)` bodies plus a 132-line constraint block
with mutable locals crossing every boundary, still the only target needing a real
data-flow answer), `access$checkBigintPropertyNames$emit` **10,339** (not the
8-line local `emit` its name suggests but the whole per-file body the anonymous
walker object closes over), and `Transformer.transform` **8,934**.

## 20. (JIT.1)(e) — `compileParsedCore`, 21,535 → an entry at 293 plus ten helpers

*Round 816.* The compiler's own top-level pipeline, and the first split in this
family whose parts sum to **less** than the monolith.

| function | region of the body | bytecodes |
|---|---|---:|
| `cpcCompileMultiFile` | the multi-file arm, minus its own four runs | 5,111 |
| `cpcCheckEmitOptionConflicts` | TS5069/TS5066/TS5052, `outDir`-vs-`rootDir`, TS6059, TS5009 | 3,012 |
| `cpcScanFiles` | phase 1 — the per-file parse/scan that fills the program tables | 2,651 |
| `cpcCheckModuleAndLibOptions` | TS5070/TS5071/TS5052/TS5053/TS5095/TS5110 and the `noLib` globals | 1,894 |
| `cpcCheckProjectShapeOptions` | TS6054/TS5055/TS5056 — the whole-program shape checks | 1,584 |
| `cpcBindAndCheck` | phase 2 — bind every file, run the checker (or the INV.6 workers) | 1,537 |
| `cpcCompileSingleFile` | the single-file arm | 1,488 |
| `cpcTransformAndEmit` | phase 3 — the transform + emit loop | 1,125 |
| `cpcCheckDeprecatedOptions` | TS5101/TS5102/TS5103/TS5107/TS5108 | 1,004 |
| `cpcRequireOnlyOrphans` | the `require`-only orphan census | 595 |
| `compileParsedCore` | the option head, ten calls, the dispatch | **293** |

Census **6 → 5**. The ten helpers sum to **20,001**, and with the entry the split
is **20,294 against the monolith's 21,535 — 1,241 bytecodes FEWER**. Every
previous round in this family added between 10 and 99; § 20.1 says why this one
subtracted, and it is the round's most transferable finding.

### 20.1 What actually drove the size: one captured `var`, 168 boxed reads

`javap -c` on the pre-split binary shows the method's very first instruction is
`new kotlin/jvm/internal/Ref$ObjectRef`. Kotlin boxes a **mutable local that a
non-inline closure captures**, and `compileParsedCore` has exactly one:
`var options`, reassigned once by the NodeNext `package.json` scan and captured
by the worker lambdas of the `ParallelCheckMode.workers > 1` branch — a
`(0 until workers).map { { … Checker(options, …) } }`, i.e. a list of function
VALUES, which is the one closure form the compiler cannot inline away.

The census of the monolith's disassembly:

| instruction | count |
|---|---:|
| `new … Ref$ObjectRef` | 1 |
| `putfield … Ref$ObjectRef.element` | 2 |
| `getfield … Ref$ObjectRef.element` | **168** |
| `checkcast … CompilerOptions` | **168** |

So **~1,008 bytecodes — 4.7% of the method — existed only because one `var` was
captured**, paid six bytes at a time at reads spread over 1,780 lines. In the
split every helper takes `options` as a PARAMETER, which is an immutable local
again: the boxing disappears (`Ref$ObjectRef` count across all eleven functions
is **0**) and every one of those reads is a plain `aload`.

Two things follow for the next agent. **(a) A split can be bytecode-NEGATIVE,
so "the parts must sum to at least the monolith" is not a law** — the
`HugeMethodLimitTest` share-check for this target asserts `> 18000` against a
21,535 original for exactly that reason. **(b) Before hunting for size in what a
function DOES, check what it CAPTURES**: `javap -c … | grep -c ObjectRef` is a
one-line test, and a single non-inline closure over a `var` taxes every read of
that variable in the whole function. Round 815 found a dispatch table's size to
be arm-count × field-count; this is the same class of surprise from the other
direction.

### 20.2 The boundaries were MEASURED, not estimated

`scripts/method_bytes_by_line.py` (new this round) attributes every bytecode of
one method to a source line through javap's `LineNumberTable`, so each candidate
region's size is known BEFORE the edit. Rounds 807 and 810 each landed one
extraction short (8,061, 61 over) or a hair under (7,803, a 197-byte margin) and
had to reason about it afterwards; here the predicted sizes were 1,319 / 3,153 /
2,201 / 1,647 / 1,631 / 5,207 / 2,824 / 1,589 / 1,180 / 729 and the built ones
came out uniformly 4–18% SMALLER — the boxing above, plus lower local-slot
indices.

The tool has one property a reader must know: **Kotlin inline functions
(`map`, `filter`, `let`, `run`, …) are expanded into the caller and carry
SYNTHETIC line numbers past the end of the file** (the JSR-45 `SMAP` maps them
back). For `compileParsedCore` that is **4,938 of 21,535 bytecodes — 22.9% of
the method is code that is not in its source at all**. Those bytes are charged
to the last real line before them, i.e. to the inlining call site, which is the
attribution a split needs.

### 20.3 The frequency argument, honestly: irrelevant

`compileParsedCore` runs **once per compile** — once per corpus test, once per
project build, once per `--watch` recheck. Its interpreted cost is one pass over
~21 k bytecodes plus 78 iterations of the file-scan loop on the compiler
profile: microseconds. **No wall A/B was run and none should be.** Like rounds
814 and 815 this lands for the THRESHOLD and for sub-item (f). What the split
buys that a benchmark cannot see is that the pipeline is now made of methods
HotSpot will compile if a future caller makes them hot — and 1,241 fewer
bytecodes to interpret on the way.

The cut criterion is therefore SIZE, with one structural rule: the two arms of
the single-vs-multi dispatch are mutually exclusive, so a compile pays exactly
one of them.

### 20.4 The shape, and why no helper needs a signal

`scripts/cpc_split_analyze.py`:

* the body is **1,780 lines**: an option-validation prologue of ~750, then an
  `if (single) … else …` whose arms are 129 and 894 lines;
* `options` is assigned in exactly **two** places, both in the head that stays
  in the entry; `emitted5070` is the only other `var` and it is read 6 lines
  from where it is written, inside one region;
* **the two arms move WHOLE**, so all four whole-function `return`s (941, 1043
  in the single arm; 1079, 1936 in the multi arm) go with them. Every other
  `return` in the body — 26 of them — belongs to a local `fun` that moves with
  its region. **No helper needs a return signal at all** (round 813's property);
* all 22 `continue`s and the single `break` bind to a loop inside their own
  region (checked with a brace stack seeded at the function head, not by
  indentation — round 812);
* the only values that cross a boundary are RETURNED: the `Checker` from
  `cpcBindAndCheck` and the `Set<String>` from `cpcRequireOnlyOrphans`. Nothing
  is stashed in a field, so round 791's save/restore hazard cannot arise.

### 20.5 The 18-parameter problem, and the rule it produces

`cpcScanFiles`'s free-variable set is **18 names**, of which three are
`MutableList<Pair<String, String>>` and four are `MutableSet<String>`. A
POSITIONAL call site could permute two same-typed containers and still
type-check — a silent, total behaviour change with nothing in the compiler to
catch it. **So every call site in this split passes every argument by NAME**,
and `cpc_split_verify.py` check 5 asserts that the named set equals the
parameter set. § 20.7's A3 row is that claim ablated.

This is where the scope-aware free-variable computation earns its keep: the
earlier scripts in this family matched declaration names textually, which cannot
tell `val file` in the single-file arm from `for (file in …)` in the multi-file
one. Here the scopes are simulated with a brace stack, so "visible at the
region" means what Kotlin means by it; the compiler then enforces the answer in
both directions, since an unused parameter is a `w:` in this warning-clean
build.

### 20.6 Equivalence, measured (round 805's five checks)

`scripts/cpc_split_{analyze,apply,verify}.py`, all green:

1. ten contiguous, in-order regions re-extracted from the NEW file and compared
   **verbatim** against HEAD — the four prologue runs at dedent 0, the six
   arm/pipeline runs at dedent 4;
2. the new file **RECONSTRUCTED** from HEAD by the apply step and compared byte
   for byte: **identical, 287,974 chars**;
3. the accounting is a PARTITION of the body: every one of the 1,780 lines is
   claimed exactly once — 33 kept in the entry, **1,740 moved**, 3 separator
   blanks dropped (asserted blank), 4 structural lines replaced by the dispatch;
4. control-flow tokens enumerated on both sides, **bounded to the changed region
   on both** (round 815's lesson): `return` 30 → 34 (+4: two dispatch arms,
   `return checker`, `return requireOnlyOrphans`), `continue` 22 → 22, `break`
   1 → 1;
5. free variables computed per region equal each helper's parameter list, and
   every call site names every argument.

### 20.7 Discrimination — 3 of 3, plus a negative control, control first

`CpcSplitTest` (16 pins) plus `HugeMethodLimitTest` (+3). **All 16 were validated
on the UNSPLIT binary first: 58 pins ran, and exactly 3 failed — the three new
SIZE pins, which must fail there.** So every behavioural pin describes HEAD, not
the split. Each mistake was then made alone, on its own build, with the pin count
confirmed every time.

| mistake | pins failed | verdict |
|---|---|---|
| the `cpcCheckModuleAndLibOptions` call is dropped | **3** — both of that run's arm pins (TS5053, TS5110) and the four-code pin | **DISCRIMINATED, attributable to the run** |
| the multi-file arm is handed `baseOptions`, not `options` | **1** — **exactly** the `options` seam (a `package.json` `"type": "module"` program stops emitting ESM) | **DISCRIMINATED, sharply** |
| `cpcScanFiles` called POSITIONALLY with `sourceEchoes`/`jsonOutputs` swapped | **2** — the echo-order pin and the per-file JS-output count | **DISCRIMINATED** |
| *(negative control)* the project-shape run consulted FIRST | **0** | **UNOBSERVABLE, as predicted** |

The third row is the one worth carrying. It is a mistake **no compiler can
catch**: both parameters are `MutableList<Pair<String, String>>`, so the
positional call type-checks, and the resulting compile silently echoes nothing
and emits the echoes as JSON outputs. The defence is not a test but the call
convention — every argument passed by name — and the test is what notices if the
convention lapses.

The fourth row follows round 815's rule: the claim "the four validation runs are
order-independent" rests on a structural property (each run only APPENDS to
`diagnostics`; none reads it back; no two emit the same code), so the property's
CONSEQUENCE was ablated and the predicted zero was shown, rather than recording
"no order seam here".

### 20.8 Gate

Census re-measured at HEAD on a rebuilt binary first — **6** over the limit,
`compileParsedCore` **21,535**, reproducing the round-815 handoff exactly; the
after-number measured the same way on the binary built from the split source —
**5**. Suite **13,706 → 13,725 / 0 failures / 3 skipped** (+19: 16 `CpcSplitTest`
+ 3 `HugeMethodLimitTest`), python XML parser, whole results dir wiped first.
8-profile grid diffed set-for-set BOTH directions against `build/r816-pre`, a
purpose-built PRE-SPLIT binary, both arms on the IDENTICAL direct `java` command
line with absolute class dirs, class dirs confirmed to differ (0 vs 7
`cpcCompileMultiFile` entries), every capture checked non-empty and free of an
`and N more error` marker — **46/46/46/46/46/46/46/94, 0 added and 0 removed on
all eight**. `--partitionCheck 2` **EQUIVALENT — 46**. `cost_gate.py` **all 20
counters +0.00%**. `compileKotlinJvm compileTestKotlinJvm --rerun-tasks`: **0
`w:` and 0 `e:` lines**. **No wall A/B, deliberately** — see § 20.3.

**The list is now five, and four of them are the Transformer or the hard one.**
`Transformer.transformToCommonJS` **28,991**, `Transformer.transformClassBody`
**16,233**, `Checker.tryInferSingleTypeParamFromArgs` **11,930** (still the only
target needing a real data-flow answer), `access$checkBigintPropertyNames$emit`
**10,339**, `Transformer.transform` **8,934**. The Transformer three are on the
EMIT path, which every `--noEmit` A/B in this arc is blind to; their gate is the
corpus suite's emit baselines.

---

## 21. (JIT.1)(f) — the census becomes a RATCHET, and finds a phantom in itself

Round 817. Two things landed: the gate that stops this family growing again, and
the last cheap split. The gate came first, because it is what makes the rest
unnecessary to rediscover.

### 21.1 The honest form of the gate is a ratchet, not a zero

Round 802 found **19** methods over HotSpot's `HugeMethodLimit` by running a
census for the first time in 800 rounds. Nothing else could have found them: the
corpus measures meaning and not cost, `cost_gate.py`'s counters do not move, and
`-XX:+PrintCompilation` prints nothing at all (the compile is never *proposed*,
so it is never *skipped*). Fourteen rounds of splitting later the census stood at
**5**, all five known and named — so `--fail-over 0` was not available, but
`--fail-over 5` was, **today**, and it catches a NEW offender the moment it
appears. `0` is the end state, not a precondition.

It is wired in two places, deliberately:

* `python3 scripts/huge_methods.py --fail-over 5` as a ROUND-GATE step beside
  `cost_gate.py` (CLAUDE.md, SESSION-PROMPT.md). Wiring it into Gradle's `check`
  is a build-system change and remains owner-gated as (JIT.3) — this round did
  not decide that on the owner's behalf;
* `HugeMethodLimitTest` runs the same whole-program census INSIDE the suite, so
  it cannot be forgotten. It walks the compiled main output from a marker
  resource, parses every `Code` attribute length, and fails on a NEW offender AND
  on a STALE entry — i.e. when a split has landed and the ratchet was not
  tightened. That second direction is the tightening rule made mechanical.

**Proven to fire, three arms, each its own build:** the committed state 44 pins /
0 failed; the ratchet tightened by one 44 / **exactly 1**, the census pin; a
stale named entry 44 / **exactly 1**, the named-offenders pin. And
`--fail-over 4` exits 1 against a census of 5 while `--fail-over 5` exits 0. A
gate that has never failed is not known to work.

### 21.2 THE SECOND INSTRUMENT PAID FOR ITSELF ON ITS FIRST RUN

The suite census immediately reported a method the script had never listed:
`Checker.<clinit>`, **10,340** bytecodes.

`javap` renders a static initializer as `static {};` — **with no parameter
list**. `huge_methods.py`'s method-header regex requires a `(`, so it never
started a method there, and every one of `<clinit>`'s bytecodes was charged to
whatever method happened to precede it in the class file. That method is
`Checker.access$checkBigintPropertyNames$emit`, whose real body is **16 bytes**
(`aload`×8, one `invokestatic`, `return`) — and which this queue has carried as a
**10,339-bytecode split target since round 802**.

So the census count was right and one of its five NAMES was wrong, for fourteen
rounds. Method count moved 14,001 → 14,107 once the regex was fixed: **106 static
initializers had never been counted at all.**

The transferable rule: **read `Code` attribute lengths from the class file when
the answer matters; a `javap` rendering is a parse away from the truth** — and a
second instrument that reaches the same number by a different route is worth
building precisely because it can disagree.

## 22. (JIT.1)(e) — `Transformer.transform`, 8,934 → an entry at 2,989 plus seven helpers

### 22.1 The shape, and what stays

`transform` is a straight pipeline: reset the per-file fields, pre-pass the
top-level names, transform the statements, then a sequence of stages each
consuming the previous stage's list. Seven regions moved, sizes MEASURED before
the edit with `scripts/method_bytes_by_line.py`:

| region | HEAD lines | measured | helper |
|---|---|---|---|
| top-level name pre-pass | 526–620 | 1,272 | `tfCollectTopLevelNames` 1,367 |
| helper-statement list | 701–740 | 1,050 | `tfCollectHelperStatements` 1,182 |
| leading-comment lift | 751–785 | 550 | `tfLiftLeadingComments` 663 |
| ESM tslib import | 874–948 | 1,071 | `tfInjectTslibImport` 1,142 |
| internal alias elision | 956–976 | 283 | `tfElideInternalImportAliases` 636 |
| noLib metadata wrap | 983–1006 | 275 | `tfWrapNoLibMetadataArgs` 406 |
| createRequire header | 1013–1065 | 389 | `tfInjectCreateRequireHeader` 385 |

**What stays is chosen by one structural rule:** the CommonJS and module:preserve
branches hold all three whole-function `return`s, so leaving them in the entry
buys round 813's property — **no helper needs a return signal at all**, and
therefore no helper can fail to propagate one.

Every region moves at **dedent 0**, and every value-producing region moves WITH
its own `val` declaration plus one added `return <name>` line, so not one
character of the moved text is edited.

### 22.2 The parts sum to LESS — for a DIFFERENT reason than round 816's

8,770 against 8,934: **164 fewer**. Round 816's mechanism is measured ABSENT
here — `transform` contains **0** `Ref$ObjectRef` references before and after,
and the class total is 86 either way.

The measured cause is **local-slot addressing**. The monolith has ~60 live
locals, so almost every reference is past slot 3 and pays the 2-byte
`aload N` / `astore N` form; inside a helper the same values sit in slots 0–3 and
take the 1-byte `aload_N`. Counted across the entry and all seven helpers:

    2-byte forms   841 -> 741   (-100)
    1-byte forms   219 -> 288   (+69)

i.e. 100 of the 164 bytes, with prologue/epilogue netting out the rest.
Parameters cost nothing: Kotlin emits **no** `checkNotNullParameter` for a
private method (count 0).

**So there are now TWO measured reasons a split can be bytecode-negative, and the
prior from one does not carry to the other.** Check which applies before claiming
either.

### 22.3 Seams, and the negative control

Discrimination 3 of 3, each mistake alone on its own build, the failure COUNT
predicted before the run:

* **the ORDER seam** — swap the `tfCollectHelperStatements` and
  `tfLiftLeadingComments` calls. Both take `helpers` and neither returns it, so
  the swap TYPE-CHECKS; the lift reads the list by value, so every helper body is
  silently lost. Nothing in the data flow enforces the order. Predicted 2, failed
  **2**, exactly the two named;
* **the SET-IDENTITY seam** — hand `tfCollectTopLevelNames` a fresh set instead of
  the caller's, which the caller then subtracts from `topLevelTypeOnlyNames`. A
  name that is both a type and a value (`interface X {}` beside `const X = 1`)
  stops being exported. Predicted exactly 1, failed **1**;
* **a dropped call** — `tfInjectCreateRequireHeader`. Predicted exactly 1, failed
  **1**;
* **NEGATIVE CONTROL** — move `val isCjsFileName` INTO the helper. It is a pure
  expression over a parameter the helper already has, with exactly ONE reader,
  inside the moved region. Predicted **0**, failed **0**.

Pins were validated on the UNSPLIT binary first: 56 ran, exactly 5 failed and
they are the 5 size/ratchet pins, which must fail there.

### 22.4 Gate

Suite **13,725 → 13,739 / 0 failures / 3 skipped**. 8-profile grid diffed
set-for-set BOTH directions against a purpose-built pre-split binary, identical
direct `java` command, absolute class dirs, class dirs confirmed to differ (0 vs
7 `tf*` helpers) — **46/46/46/46/46/46/46/94, 0 added and 0 removed on all
eight**. `--partitionCheck 2` **EQUIVALENT — 46**. `cost_gate.py` **all 20
counters +0.00%**. `--rerun-tasks` build **0 `w:` / 0 `e:`**.

**No wall A/B was run and none should be.** This method is on the EMIT path and
every A/B in this arc is `--noEmit`, so the instrument is structurally blind to
it; the behavioural gate is the corpus suite's EMIT baselines plus
`TransformSplitTest`.

### 22.5 The list is now FOUR

`Transformer.transformToCommonJS` **28,991**, `Transformer.transformClassBody`
**16,233**, `Checker.tryInferSingleTypeParamFromArgs` **11,930** (still the only
target needing a real data-flow answer), and `Checker.<clinit>` **10,339** — the
one this round discovered, and a shape nobody in this arc has split yet: a static
initializer, whose contents are the class's `object`-level constants, and which
can only shrink by moving those initializers into helper methods it calls.

## 23. (JIT.1)(e) — `Transformer.transformClassBody`, 16,233 → an entry at 5,202 plus nine helpers

Round 818. The second largest method in the compiler, and the first target in
the arc whose obstacles were *scoping* rather than control flow.

### 23.1 The two shapes that block a mechanical split

Everything in this family so far moved contiguous text into a private method and
handed values back. This function has two constructs that make that illegal, and
they are worth naming because any large Kotlin function can hold them:

* **A LOCAL DATA CLASS.** `data class PrivateFieldInfo(…)` is declared inside the
  body and CONSTRUCTED by one of the regions that must move. Its type cannot be
  named from a member function, so the region cannot move while it is local. It
  is LIFTED to a private nested data class — the only text change outside the
  mechanical extraction, and behaviour-free: it captures nothing and never
  escapes the transform.
* **A LOCAL `fun` CALLED FROM BOTH SIDES OF A BOUNDARY.**
  `buildStaticBlockIife` closes over `classTempVar`/`heritageTempVar` — the very
  values the split decides — and is called both inside the moved static-trailing
  loop and after it. It can neither move nor be duplicated, so it is passed as a
  **function-typed parameter** (`::buildStaticBlockIife`), which leaves the moved
  call site `buildStaticBlockIife(member)` textually untouched. **The ORDER that
  makes this sound is enforced by nothing in the types**: the capture stage must
  run first so the caller's vars hold their final values before the reference is
  ever invoked. That is this round's first ablation.

`isCapturablePrivateMethod` needed none of this treatment: both of its call sites
are inside ONE region, so the local `fun` simply moved with them. **The test is
where the call sites are, not what the construct is.**

### 23.2 The regions, measured before the edit

Sizes from `scripts/method_bytes_by_line.py`, HEAD line numbers:

| region | HEAD lines | measured | helper | after |
|---|---|---|---|---|
| auto-accessor downlevel | 11392–11452 | 584 | `tcbLowerAutoAccessors` | 666 |
| computed-key extraction | 11544–11625 | 1,112 | `tcbExtractComputedPropertyKeys` | 1,050 |
| private state allocation | 11712–11813 | 1,832 | `tcbAllocatePrivateState` | 1,716 |
| instance initializers | 11950–12051 | 1,161 | `tcbBuildInstanceInitializers` | 1,130 |
| the constructor | 12054–12145 | 1,338 | `tcbBuildTransformedConstructor` | 1,317 |
| the output member list | 12155–12319 | 1,677 | `tcbBuildOutputMembers` | 1,604 |
| class-alias/heritage capture | 12372–12430 | 1,259 | `tcbCaptureClassAlias` | 1,236 |
| the alias+private comma stmt | 12432–12515 | 1,264 | `tcbEmitAliasAndPrivateState` | 1,178 |
| static field trailing | 12517–12609 | 979 | `tcbEmitStaticFieldTrailing` | 919 |

Entry **5,202**. Six regions move at dedent 0 and three (inside the
static-trailing `if`) at dedent 4. Only `tcbCaptureClassAlias` needs a PROLOGUE —
three `var`s it decides and returns in a `ClassAliasCapture` holder.

**The frequency argument, honestly: irrelevant.** `transformClassBody` runs once
per CLASS, on the EMIT path, and every A/B in this arc is `--noEmit` and
structurally blind to it. It lands for the threshold and for (JIT.1)(f).

### 23.3 The parts sum to LESS — and here BOTH mechanisms fire at once

16,018 against 16,233: **215 fewer**. Rounds 816 and 817 each found one cause and
each measured the other absent. This target has both:

    boxed `var` reads inside the function   31 -> 11   (round 816's mechanism)
    2-byte aload/astore                  1,947 -> 1,850 (round 817's mechanism)
    1-byte aload_N/astore_N                197 ->   254

The 11 boxed reads that REMAIN are the entry's two alias temps, still captured by
the local `fun buildStaticBlockIife` — which is exactly why they must stay boxed,
and is the same property that forced the function-typed parameter. So the two
mechanisms are not alternatives to choose between: **measure both.**

**An instrument trap inside that measurement, caught by a control.** The first
per-method attribution keyed the javap output by `line.strip()[:70]` and reported
that `transformClassBody` held ZERO boxed reads while a whole-method slice
reported 31. The method's own name sits PAST character 70 of its signature, so
the truncated key did not contain it and the filter dropped it. The rewritten
pass asserts a key containing `transformClassBody(` exists before it reports
anything. Same family as rounds 815–817: an instrument whose input is not
precisely bounded measures something else.

### 23.4 Equivalence, measured (round 805's five checks)

`scripts/tcb_split_{analyze,apply,verify}.py`, all green: nine contiguous in-order
regions re-extracted from the NEW file and compared VERBATIM (dedent 0 for six,
4 for three); the file RECONSTRUCTS from HEAD byte for byte (923,613 chars); the
accounting is a PARTITION (1,321 body lines, each claimed exactly once, 840
moved) with the ONE line that is neither kept nor moved — the lifted data class —
named, asserted unique, and asserted present in its new form; control flow
enumerated on both sides and BOUNDED to the changed region — `return` **2 + 5 ==
7**, `continue`/`break` **11 == 11**; free variables per region equal to the
parameter list plus the declared prologue, and every call site passes every
argument BY NAME.

**The free-variable matcher had to be rewritten, not reused.** Round 817's binds
`val x: T = <expr>` to the first token of the INITIALISER (its optional
annotation group eats `x: T = `), so on this function it bound `if` and never
bound `members`. And a NAMED ARGUMENT (`name = …`, `initializer = …`,
`modifiers = …`) is textually indistinguishable from a read of a same-named
local — `transformClassBody` has parameters called `name` and `modifiers`, and
the AST constructors it calls take arguments of exactly those names, so an
unfiltered matcher reports every region as capturing `name`. Both filters carry a
positive control in BOTH directions.

### 23.5 Discrimination — 4 of 4, plus a negative control at its predicted zero

Pins validated on the UNSPLIT binary first: **60 ran, exactly 5 failed and they
are the 5 size/ratchet pins**, which must fail there. So all ten behavioural pins
describe HEAD, not the split. On the split binary: **69 ran, 0 failed.**

Each mistake alone, on its own build, the count PREDICTED before the run:

* **the ORDER seam** — move the `tcbCaptureClassAlias` call after the two emit
  stages. It type-checks (all three vars are declared above), and then the static
  block's `this` is never routed through the alias, so the downlevelled arrow
  captures the OUTER `this`. Predicted 1, failed **1**;
* **the LIST-IDENTITY seam** — hand `tcbEmitStaticFieldTrailing` a fresh
  `emittedStaticBlocks`. The caller's later loop skips what is recorded there, so
  every static block is emitted TWICE. Predicted 1, failed **1**;
* **the RETURN-SIGNAL seam** — drop `tcbBuildOutputMembers`' `constructorAdded`
  answer; the constructor is then emitted at its source position AND prepended.
  Predicted 1, failed **1**;
* **a dropped call** — `tcbExtractComputedPropertyKeys`. Predicted 1, failed **1**;
* **NEGATIVE CONTROL** — pass `heritageIn = transformedHeritage` instead of
  `finalHeritage`. Nothing between the declaration and the call assigns
  `finalHeritage`, so the two are the same value. Predicted **0**, failed **0**.

Every arm reports `RAN 10`, so no arm is a vacuous pass.

### 23.6 Gate

Suite **13,739 → 13,752 / 0 failures / 3 skipped** (+13: 10 `TransformClassBodySplitTest`
+ 3 `HugeMethodLimitTest` size pins), whole results dir wiped first, counted with
the python XML parser. 8-profile grid diffed set-for-set BOTH directions against a
purpose-built pre-split binary, identical direct `java` command, absolute class
dirs, class dirs confirmed to differ (14 `tcb` mentions vs 0), every capture
checked non-empty and free of an `and N more error` marker —
**46/46/46/46/46/46/46/94, 0 added and 0 removed on all eight**.
`--partitionCheck 2` **EQUIVALENT — 46**. `cost_gate.py` **all 20 counters
+0.00%**. `--rerun-tasks` build **0 `w:` / 0 `e:`** — after two warnings that the
prologue itself produced (`= null` redundant, then `var` that could be `val`; the
moved region assigns that temp unconditionally, which is why both fired).
`huge_methods.py --fail-over 3` exits 0.

**The behavioural gate that matters here is the corpus EMIT baselines**, since no
`--noEmit` instrument can see this function: the suite carries **5,692
`compiles to JavaScript matching` subtests**, every one of which runs
`transformClassBody` for every class it contains, and **55** of them are in the
families this split's regions own — `parameterPropertyInConstructor1..4`,
`parameterPropertyInConstructorWithPrologues`, `computedPropertyNameWithImportedKey`,
`privateNameWeakMapCollision`, `controlFlowAutoAccessor1`,
`classPropertyInferenceFromBroaderTypeConst` among them. All passed.

### 23.7 The list is now THREE

`Transformer.transformToCommonJS` **28,991**,
`Checker.tryInferSingleTypeParamFromArgs` **11,930**, and `Checker.<clinit>`
**10,339**. `transformToCommonJS` is this round's recipe again at 1.8× the size
and on the same emit path; `tryInferSingleTypeParamFromArgs` still needs a
scripted DATA-FLOW answer rather than a contiguity argument; `<clinit>` is a
static initializer whose content is the class's object-level constants, and can
only shrink by moving those initializers into helper methods it calls.

## 24. (JIT.1)(e) — `Transformer.transformToCommonJS`, 28,991 → an entry at 2,944 plus nineteen helpers

Round 819. The LARGEST method in the compiler, 3.6× the limit, and the first
target in the arc whose regions **continue the caller's loop**.

### 24.1 The shape that blocks a mechanical split: a moved region that `continue`s

Everything in this family so far moved contiguous text that could only fall off
its own end. `transformToCommonJS`'s bulk is

    for (stmt in statementsToProcess) { when (stmt) { … seven arms … } }

and two of those arms hold `continue`s that target THAT loop — 6 of the
function's 27 (there are no `break`s): one in the
`VariableStatement` arm (the `export const { x, ...rest }` object-rest path,
which builds a comma expression and abandons the rest of the arm) and **five** in
the `ImportDeclaration` arm (an import whose bindings are referenced nowhere, a
namespace import with an empty name, a namespace import of a type-only module,
and two combined default+named/namespace forms with nothing used). A `continue`
cannot survive extraction into a member function, and rewriting it to `return`
would be an edit to the moved text at six deeply nested sites.

**The ONE-ITERATION FRAME.** Those two helpers wrap the moved region in

    for (stmt in listOf(stmtIn)) { <the arm, verbatim> }

A single-element loop makes `continue` mean exactly what it meant before —
abandon the rest of THIS statement's processing — so the region moves verbatim,
the control-flow token census is unchanged on both sides (`continue`/`break`
27 == 27), and the frame's loop variable is `stmt`, which is what the arm's
smart-cast subject was already called, so not one reference inside is rewritten
either. **What the types do NOT say is that the list has one element**, which is
this round's own ablation (§ 24.5).

The instrument that decides WHICH regions need the frame is a brace-depth scan
that remembers, per `continue`, whether a loop was opened inside the region
(`tcjs_split_verify.outer_continues`) — and it earned its place on its first run:
the hand-built list I had derived by eye said four in the import arm, and the
scan said five. **A hand census of `continue`s is exactly the sort of thing that
looks complete and is not**; the verify script now asserts that every region
holding a caller-loop `continue` is framed and that no other region is.

### 24.2 The regions, measured before the edit

Sizes from `scripts/method_bytes_by_line.py`, HEAD line numbers. 24.2% of the
method is INLINED stdlib bodies charged to their call sites.

| region | HEAD lines | measured | helper |
|---|---|---:|---|
| module shape + preamble | 1396–1465 | 1,383 | `tcjsDetectModuleShape` |
| declared-name pre-scan | 1533–1689 | 2,285 | `tcjsCollectDeclaredNames` |
| reference/namespace pre-scan | 1691–1761 | 1,182 | `tcjsCollectNamespaceExports` |
| export-clause pre-scan | 1763–1835 | 923 | `tcjsCollectExportClauses` |
| prologue directives | 1837–1869 | 322 | `tcjsSplitPrologueDirectives` |
| **arm** `VariableStatement` | 1876–2249 | **4,451** | `tcjsTransformVariableStatement` *(framed)* |
| **arm** `FunctionDeclaration` | 2253–2291 | 449 | `tcjsTransformFunctionDeclaration` |
| **arm** `ClassDeclaration` | 2295–2325 | 425 | `tcjsTransformClassDeclaration` |
| **arm** `ExportAssignment` | 2329–2375 | 415 | `tcjsTransformExportAssignment` |
| **arm** `ImportDeclaration` | 2379–2631 | 2,756 | `tcjsTransformImportDeclaration` *(framed)* |
| **arm** `ExportDeclaration` | 2635–2837 | 2,372 | `tcjsTransformExportDeclaration` |
| **arm** `else` | 2841–2918 | 745 | `tcjsTransformOtherStatement` |
| early pre-preamble extraction | 2981–3032 | 738 | `tcjsExtractEarlyPrePreamble` |
| hoisted vars + stubs | 3063–3118 | 757 | `tcjsPrependHoistedVars` |
| export-mutation rewrites | 3137–3180 | 913 | `tcjsRewriteExportMutations` |
| internal alias names | 3231–3253 | 1,224 | `tcjsCollectInternalAliasNames` |
| import elision | 3255–3394 | 2,749 | `tcjsElideUnusedImports` |
| detached header comments | 3396–3439 | 700 | `tcjsMoveDetachedHeaderComments` |
| helper + prologue insertion | 3441–3559 | 1,775 | `tcjsInsertHelpersAndPrologue` |

Entry **2,944**; largest helper `tcjsTransformVariableStatement` **4,335**. Six
small post-loop blocks (2,065 bytecodes: the dynamic-import rewrite, the
re-export placement, the default-export reordering, the void0 chain, the rename
application and the direct-export identifier rewrite) stay in the entry
deliberately — they are where the entry's remaining reads live, and moving them
would buy margin nobody needs.

**The frequency argument, honestly: irrelevant.** `transformToCommonJS` runs once
per FILE on the EMIT path, and every A/B in this arc is `--noEmit`. It lands for
the threshold and for (JIT.1)(f).

### 24.3 The parts sum to LESS — and here only ONE of the two mechanisms fires

28,886 against 28,991: **105 fewer**, i.e. 0.36%.

    boxed `var` reads (round 816's mechanism)   0 ->    0
    2-byte aload/astore (round 817's)       4,049 -> 3,979
    1-byte aload_N/astore_N                   409 ->   536

**Round 816's mechanism is measured ABSENT, and the reason is worth stating**:
every lambda `transformToCommonJS` captures a `var` into (`filter`, `takeWhile`,
`any`, `map`) is an INLINE stdlib function, so Kotlin never allocates a
`Ref$*Ref` — the boxing round 816 measured needs a NON-inline lambda. Round 818
found both mechanisms at once and warned that neither prior transfers; this
round is the third distinct combination in three rounds.

What is new here is the SIZE of the net: at nineteen call sites and 128
arguments the added call machinery very nearly cancels the slot-addressing win.
**So "the parts sum to less" is not a law either** — it is a small residual whose
sign depends on how many arguments the boundaries carry.

### 24.4 Equivalence, measured (round 805's five checks, plus a sixth)

`scripts/tcjs_split_{analyze,apply,verify}.py`, all green: nineteen contiguous
in-order regions re-extracted from the NEW file and compared VERBATIM (dedent 0
for twelve body-level regions, 12 for the five plain arms, 8 for the two framed
ones); the file RECONSTRUCTS from HEAD byte for byte (944,271 chars); the
accounting is a PARTITION of the 2,173 body lines with every line claimed exactly
once and 1,907 moved; control flow enumerated on both sides and BOUNDED to the
changed region — `return` **1 + 10 == 11**, `continue`/`break` **27 == 27**; free
variables per region equal the parameter list plus the declared prologue, and
every one of the 128 arguments is passed BY NAME (74 of them are same-typed
mutable containers a positional call could permute and still type-check).

**The sixth check is the frame's**: every region holding a caller-loop
`continue` is framed, no other region is, the frame's iterable is `listOf(<a
parameter>)`, and the six caller-loop `continue`s are all inside one.

### 24.5 Discrimination — and two seams the pins do NOT catch, stated

Pins validated on the UNSPLIT binary first: **76 ran, exactly 5 failed and they
are the 5 size/ratchet pins**, which must fail there. So all 23 behavioural pins
describe HEAD. On the split binary: **76 ran, 0 failed.** Every arm below is one
mistake, alone, on its own build, with the failure count PREDICTED first.

| arm | mistake | predicted | actual |
|---|---|---:|---:|
| A1 | **ORDER** — `tcjsRewriteExportMutations` moved after the entry's own direct-export identifier rewrite | 1 | **1** ✔ |
| A2 | **CONTAINER IDENTITY** — `tcjsTransformFunctionDeclaration` handed a fresh `functionExportStubs` | 3 | **2** |
| A3 | **RETURN SIGNAL** — the import arm's flag write-back dropped | 3 | **3** ✔ |
| A4 | **THE FRAME** — `listOf(stmtIn, stmtIn)` on the import arm | 2 | **1** |
| A5 | **DROPPED CALL** — `tcjsMoveDetachedHeaderComments` | 1 | **0** |
| A6 | **NEGATIVE CONTROL** — swap two pre-scans that read only `originalSourceFile` | 0 | **0** ✔ |

Every arm reports `RAN 76`, so no arm is a vacuous pass, and every arm compiled
with 0 `e:`.

**A2 and A4 were over-predicted, and both misses are informative.** A2's third
predicted pin exports its function through an `export { realFn }` CLAUSE, so its
stub is appended by the `ExportDeclaration` arm, not the `FunctionDeclaration`
one — the two arms write to the same list and my prediction attributed the stub
to the wrong producer. A4's second predicted pin asserts that a wholly unused
import emits nothing and a used one emits exactly one `require`; running the arm
twice still emits nothing for the unused one (the `continue` fires both times)
and the duplicate `require` for the used one is removed downstream, so only the
temp-numbering pin saw it. **The frame ablation IS caught — but by one pin, not
two, and only through the temp-var numbering.**

**A5 IS AN UNDISCRIMINATED SEAM, AND THE FULL CORPUS DOES NOT DISCRIMINATE IT
EITHER.** Dropping `tcjsMoveDetachedHeaderComments`' call left all 76 pins green,
so the whole 13,778-test suite was run against that ablation: **13,778 / 0
failures**. The structural reason is that `tcjsExtractEarlyPrePreamble` — the
EARLY twin, which runs before the hoist insertions — already handles every
header-comment shape reachable here, including one whose import is subsequently
elided; the post-elision pass is a second chance for a shape where elision
changes which statement is `result[1]`, and neither a probe in reach nor any of
the 993 CommonJS emit baselines builds one. **This is a LEAD, not a licence to
delete it**: a corpus zero bounds a shape's frequency, never its existence
(CLAUDE.md, round 792), and the honest next step is a counted ablation (how often
does the region's emitting branch fire?), not a deletion.

### 24.6 Gate

Suite **13,752 → 13,778 / 0 failures / 3 skipped** (+26: 23
`TransformToCommonJsSplitTest` + 3 `HugeMethodLimitTest` size pins), whole
results dir wiped first, counted with the python XML parser. 8-profile grid
diffed set-for-set BOTH directions against a purpose-built pre-split binary,
identical direct `java` command, absolute class dirs, class dirs confirmed to
differ (0 `tcjs*` methods vs 20), every capture non-empty and free of an
`and N more error` marker — **46/46/46/46/46/46/46/94, 0 added and 0 removed on
all eight**. `--partitionCheck 2` **EQUIVALENT — 46**. `cost_gate.py` **all 20
counters +0.00%**. `compileKotlinJvm compileTestKotlinJvm --rerun-tasks`: **0
`w:` and 0 `e:`**. `huge_methods.py --fail-over 2` exits 0.

**The gate that actually sees this function is the corpus EMIT baselines.** Of
the 5,692 `compiles to JavaScript matching` subtests, **993 have a
CommonJS-shaped baseline** — i.e. they run `transformToCommonJS` end to end — and
they partition by the exact families these regions own: `exports.default` 116,
`module.exports` 101, `__createBinding` 100, `__importStar` 89, `__importDefault`
64, `__exportStar` 23, `__rest` 4 (that last one is the `VariableStatement` arm's
object-rest path, the branch that holds its caller-loop `continue`). All passed.

### 24.7 The list is now TWO

`Checker.tryInferSingleTypeParamFromArgs` **11,930** and `Checker.<clinit>`
**10,339**. Every `Transformer` entry is gone, and with it every target in this
arc that a contiguity argument could settle: the first needs a scripted DATA-FLOW
answer (mutable locals cross every candidate boundary) and the second is a static
initializer whose content is the class's object-level constants, shrinkable only
by moving those initializers into helper methods it calls — and priceable by no
A/B in this repo, since it runs once, at class load.

---

## 25. (JIT.1)(e) — `Checker.<clinit>`, 10,339 → 3,156 plus seven top-level builders

Round 820. The last shape in this arc that no contiguity argument settles, and
the only one whose target is not a function anybody wrote: a **static
initializer**.

### 25.1 What is actually in a Kotlin `<clinit>`, and what is not

`Checker`'s companion object declares **276** members. Only **50** of them cost
`<clinit>` anything at all, and the reason is a detail of the class-file format
rather than of Kotlin: a `private const val` of a primitive or `String` type is
emitted as a `static final` field carrying a **`ConstantValue` attribute**, which
the JVM installs without executing a single bytecode. The ~200 `const val`
dispatch tags in that companion (`URES_EDGE_ROOT`, `DA_STMT_LEAK`, `TAV_CONT`, …)
are therefore free.

All 10,339 bytecodes are the **collection** constants — `setOf`/`mapOf` over
hundreds of string literals, each element an `ldc` + `aastore` pair into a
`vararg` array. `scripts/clinit_split_analyze.py` attributes them per property
through the `LineNumberTable`, exactly as `method_bytes_by_line.py` does for a
normal method, but matching `javap`'s `  static {};` rendering — which has **no
parameter list**, and is why round 802's census never started a method there
(§ 21.2).

| property | HEAD lines | measured | builder |
|---|---|---:|---|
| `KNOWN_GLOBALS` | 50484–50652 | **2,992** | `ckConstKnownGlobals` |
| `DOM_GLOBAL_NAMES` | 50676–50747 | **1,368** | `ckConstDomGlobalNames` |
| `KNOWN_GENERIC_BUILTINS` | 50795–50835 | **787** | `ckConstKnownGenericBuiltins` |
| `LIB_MIN_TARGET` (the `mapOf` only) | 50863–50930 | **671** | `ckConstLibMinTargetBase` |
| `VALUE_ONLY_GLOBALS` | 50755–50783 | **553** | `ckConstValueOnlyGlobals` |
| `KEYWORD_IDENTIFIERS` | 50435–50459 | **497** | `ckConstKeywordIdentifiers` |
| `NODE_BUILTIN_MODULES` | 50212–50223 | **371** | `ckConstNodeBuiltinModules` |

7,239 of the 10,339 moved. The 43 remaining collection constants are all under
140 bytecodes each and were left alone: the entry lands at **3,156**, i.e. 39% of
the limit, and moving more would buy margin nobody needs.

**The frequency argument, honestly: there is none.** A static initializer runs
ONCE, at class load, so this is unmeasurable by every A/B in this repo and by
`cost_gate.py` (which measured **all 20 counters +0.00%**). It lands for the
(JIT.1)(f) ratchet and for nothing else. Saying so is the point: the JIT cliff
this arc exists to close is a *whole-run interpreted* penalty, and a method that
executes once does not have one.

### 25.2 Why the builders are TOP-LEVEL, and the one constraint that decides what can move

A companion `private fun` compiles to an **instance method on
`Checker$Companion`**, which `<clinit>` would have to reach through the very
static `Companion` field it is in the middle of installing. A top-level private
function compiles to a static method on the file class `CheckerKt`, so each call
site is a plain `invokestatic` with no receiver and no initialisation order to
reason about. (Kotlin adds a 3-byte `access$ckConst*` bridge per builder, because
the caller is a different class — 21 bytes for all seven.)

The price of that choice is the **one structural constraint on this split**: a
Kotlin `private` companion member is NOT visible to a top-level function in the
same file. Six of the seven regions read nothing at all; `LIB_MIN_TARGET`'s
initializer is

    mapOf( … ) + TYPED_ARRAY_NAMES.flatMap { ta -> … }.toMap()

and `TYPED_ARRAY_NAMES` is such a member — so **only the leading `mapOf(…)`
literal moved and the tail stayed in the companion**. That is not a compromise to
apologise for, it is the seam this round's arm A6 ablates.

### 25.3 The parts sum to LESS — a fourth measurement, and a mechanism that is neither of the two known ones

    entry            10,339 -> 3,156
    seven builders                7,078
    access$ bridges                  21
    ------------------------------------
    total            10,339 -> 10,255   (84 fewer, 0.81%)

Round 816's mechanism (boxed `var` capture into a non-inline lambda) cannot apply
— a `<clinit>` of constant literals captures nothing. Round 817's (2-byte
`aload N` → 1-byte `aload_N`) cannot apply either — **a static initializer of
`putstatic`s has no locals at all**. So the third and fourth rounds in a row
produce a different combination, and here the residue is small and is the
array-build bookkeeping around each `setOf`/`mapOf` call, not a systematic
addressing win. **"The parts sum to less" is still not a law; measure yours, and
do not carry a mechanism across shapes.**

### 25.4 Equivalence, measured (round 805's five checks, in the shape a hoist takes)

`scripts/clinit_split_{analyze,apply,verify}.py`, all green:

 1. **VERBATIM** — each builder body re-extracted from the NEW file and
    re-indented by 8 is byte-identical to HEAD's text inside the property, and
    each builder's declared return type and `setOf(`/`mapOf(` head are HEAD's;
 2. **RECONSTRUCTION** — un-applying the split reproduces HEAD's `Checker.kt`
    **byte for byte (10,258,399 chars)**, which is also what proves nothing else
    in the file moved;
 3. **PARTITION** — 416 removed lines, no overlap, `416 == 402 moved + 7
    declaration + 7 closing`;
 4. **CONTROL FLOW** — a literal has none, so the check is that it HAS none (0
    `return`/`continue`/`break`/`if`/`when`/`for`/`while` tokens per region) plus
    the **element count** of each literal (top-level commas at the builder's own
    depth): 52 / 70 / 389 / 186 / 78 / 39 / 50, identical on both sides. A hoist
    that silently dropped a member would survive a diff of the wrong region and
    fail this;
 5. **FREE VARIABLES** — no moved region references a companion member or `this`.
    The census this runs against is bounded to the `companion object` block, and
    it carries a control in **both directions**: it must contain `KNOWN_GLOBALS`
    and must NOT contain the stdlib infix `to`. Its first run failed exactly that
    control — an unbounded indent-8 scan of a 176k-line file collects 3,212
    "members" and reports `to` as one of them;
 6. **CALLED EXACTLY ONCE** — each builder name occurs exactly twice in the file
    (declaration + one call site), and every property's declaration head is
    unchanged from HEAD.

### 25.5 Discrimination — and the substitution that fires THREE pins, not one

Pins validated on the UNSPLIT binary first: **66 ran, exactly 5 failed and they
are the 5 size/ratchet pins**, which must fail there. So all 10 behavioural pins
describe HEAD. On the split binary: **66 ran, 0 failed.**

Each arm is ONE mistake, alone, on its own build, with the failure count
PREDICTED before the run.

| arm | mistake | predicted | actual |
|---|---|---:|---:|
| A1 | `KNOWN_GLOBALS` gets `ckConstDomGlobalNames()` | 1 | **2** |
| A2 | `VALUE_ONLY_GLOBALS` gets `ckConstKeywordIdentifiers()` | 1 | **1** ✔ |
| A3 | `KEYWORD_IDENTIFIERS` gets `ckConstValueOnlyGlobals()` | 1 | **2** |
| A4 | `NODE_BUILTIN_MODULES` gets `ckConstKeywordIdentifiers()` | 1 | **1** ✔ |
| A5 | `DOM_GLOBAL_NAMES` gets `ckConstNodeBuiltinModules()` | 1 | **1** ✔ |
| A6 | the `+ TYPED_ARRAY_NAMES.flatMap { … }` TAIL dropped | 1 | **1** ✔ |
| A7 | **NEGATIVE CONTROL** — the seven builder DECLARATIONS reordered | 0 | **0** ✔ |

Every arm reports `RAN 66`, so no arm is a vacuous pass. **Five of the six
mistakes type-check** — the compiler reports `0 e:` for A1–A5 — which is the
whole reason these pins exist.

**BOTH UNDER-PREDICTIONS HAVE THE SAME CAUSE, AND IT IS A RULE FOR THE NEXT
SWAP-STYLE ABLATION.** The extra failure in A1 and in A3 is the same pin, *value
global used as a type is TS2749*, whose subject is `parseInt` — and `parseInt` is
a member of **both** sets those two arms substitute. Its consumer,
`isValueOnlyTypeRef`, reads `name in KNOWN_GLOBALS && name !in
VALUE_ONLY_GLOBALS`, so `KNOWN_GLOBALS` is UPSTREAM of it: A1 replaces
`KNOWN_GLOBALS` with a set that does not hold `parseInt` (`DOM_GLOBAL_NAMES` is a
strict 186-name subset of the 385, and `parseInt` is one of the 199 dropped), so
the name stops resolving at all and the pin's diagnostic changes code rather than
disappearing. **When arms swap sets, predict from the PIN SUBJECT's membership in
the set being substituted IN, not only from which constant the pin is "about".**

**Two seams stated rather than ablated.** `ckConstKnownGenericBuiltins` returns
`Map<String, Pair<Int, String>>` and `ckConstLibMinTargetBase`
`Map<String, ScriptTarget>`, so no substitution among the seven type-checks for
either — the only mistake reachable at those two call sites is a hand-edited
body, which the VERBATIM and element-count checks (§ 25.4) already refuse. Their
pins (TS2314's arity + display name, TS2550's `es2022` message) are live and
green, but nothing in this round put them in front of a wrong binary.

**WHAT DID NOT WORK.** Two of the seven arms — A3 and A7 — first came back
`RAN 0` with `e: java.lang.OutOfMemoryError: GC overhead limit exceeded` in
`compileKotlinJvm`: seven successive full `Checker.kt` compiles in one script,
with no `--stop` between them, walk straight into BUILD.1's 5 GB ceiling because
an idle Kotlin daemon keeps its heap. **A vacuous arm is not a result** — both
were re-run with `./gradlew --stop` plus a bracket-pattern
`pkill -f 'KotlinCompile[D]aemon'` and an 8-second settle before each build, and
only then did they report. Any batch of more than ~2 arms needs that hygiene
in the loop, not just at the end.

### 25.6 Gate

Suite **13,778 → 13,791 / 0 failures / 3 skipped** (+13: 10
`ClinitConstantHoistTest` + 3 `HugeMethodLimitTest` size pins), whole results dir
wiped first, counted with the python XML parser. 8-profile grid diffed set-for-set
BOTH directions against a purpose-built pre-split binary, identical direct `java`
command, absolute class dirs, class dirs confirmed to differ (**14 `ckConst`
methods in `CheckerKt` vs 0**), every capture non-empty and free of an `and N more
error` marker — **46/46/46/46/46/46/46/94, 0 added and 0 removed on all eight**.
`--partitionCheck 2` **EQUIVALENT — 46**. `cost_gate.py` **all 20 counters
+0.00%**. `compileKotlinJvm compileTestKotlinJvm --rerun-tasks`: **0 `w:` and 0
`e:`**. `huge_methods.py --fail-over 1` exits 0.

**No wall A/B was run and none should be** — see § 25.1.

### 25.7 (JIT.1) IS AT ONE, AND THE LAST ONE IS THE ONE NOBODY HAS SOLVED

`Checker.tryInferSingleTypeParamFromArgs` **11,930** is the whole remaining
census. This round measured it rather than attempting it, and the measurement is
why it was not attempted:

* its body is **1,064 lines** (115503–116566 at HEAD), and its bytecodes are
  **flat** — bucketed into 25-line windows the largest window is **449** of
  11,930, i.e. there is no region a contiguity argument can lift out;
* **2,643 of the 11,930 (22%) are INLINED stdlib bodies** carrying synthetic line
  numbers past the end of the file, so they are charged to their call sites and
  spread further still;
* the shape is a parameter-gate loop with whole-function `return`s, then
  `for (tp in orderedTps) { … }` holding essentially everything, with
  `candidates` / `tpSawAnyArg` / `mapperPairs` mutated across every candidate
  boundary. **That is a DATA-FLOW problem** — per-region read/write sets and
  liveness — not the contiguity problem every other target in this arc was.

**A measurement trap for whoever takes it:** `Checker.kt` exceeds 65,536 lines
and the `LineNumberTable` is a `u2`, so this function's line numbers WRAP — they
are reported as 49967–51030 and must have 65,536 added back. Un-corrected they
land inside the `companion object` (49544–51796) and look entirely plausible.
