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

## 4. Reproduction

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
```
