# PLAN-PHASE-5 — Self-compile the TypeScript compiler, then performance

Owner directive (2026-07-03, re-scoping the 2026-07-02 *"fully compile any TypeScript
project"*): **fully compile the TypeScript compiler itself, then optimize
performance.** "Any TypeScript project" is the post-v1 horizon.

**v1 definition of done:** all 8 tsc-source profiles (compiler / tsc-cli / jsTyping /
deprecatedCompat / typingsInstallerCore / services / server / harness) at **zero false
positives**, all files emitted, zero crashes/hangs/OOMs — verifiable fully offline.
Byte-correct emit diffing against real tsc is the network-gated follow-up (needs
node + typescript installed). Then M5 (performance) completes the directive. Items
that do not block v1 (M2.4, M3.0, M3.5, all of M4) are parked in § "Post-v1 backlog"
near the bottom of this file — the top-to-bottom loop skips them until v1 lands.

This file is the **live queue** for Phase 17. `docs/history/PLAN-PHASE-4.md` (Phase 16 and earlier)
is archived state — its "Known architectural blockers" section remains the reference
material for the M3 items below; do not work its queue.

## Phase 17 — Self-compile the TypeScript compiler (M0–M5)

(Live session notes accumulate here, most recent first — same convention as Phase 16.)

**Round 824 (2026-08-04) — `--workers N` IS A *RACE*, ROUND 754 NEVER CLOSED
(PERF.HW.a), AND THE 1.25x AMDAHL CAP IS REFUTED BY COUNTEREXAMPLE. THE PERFORMANCE CASE
FOR (M2) UNPARKS; THE CORRECTNESS GATE DOES NOT.**

**THE HEADLINE IS CORRECTNESS AND IT OUTRANKS THE SCALING TABLE.** At a FIXED worker
count the diagnostic count varies run to run: **seq 46x8; w2 46x5 / 47x5; w4 46x3 / 47x3;
w8 46x1 / 56x6.** Divergences are strictly ADDITIVE (`added=N, removed=0` in every
capture — the mode never loses a true diagnostic, it invents false ones) and each
OUTCOME is byte-reproducible (the w8 56-set matches by `md5sum` across captures), but
**which outcome you get is not**. Two DISTINCT FP families: the w2/w4 extra is
`debug.ts:601:46` TS2345; the w8 extras are 10 lines that do NOT include it (7x TS2322 in
`transformers/declarations/diagnostics.ts`, plus `utilities.ts:10384` TS2344 and
`11808`/`11859` TS2322).

**ROUND 754 DID NOT CLOSE (PERF.HW.a) — ITS BYTE-IDENTITY WAS ONE DRAW OF A 50/50 COIN,
AND THIS ROUND REPRODUCED THE FALSE GREEN** (its own stage-A w2 `--listAll` came out at
46 and diffed byte-identically). On the old box, with ~0.85 free cores, workers were
effectively SERIALIZED, so the interleaving almost never occurred: **the upgrade did not
introduce the race, it made it OBSERVABLE.** `--partitionCheck` cannot diagnose it — a
static partition model cannot produce two answers for one worker count. The detector that
does: a COUNT DISTRIBUTION over >=5 runs (~110 s). (PERF.HW.a) is re-opened; round 754's
fix stands on its own merits, it just closed a different, real, deterministic bug.

**THE JVM CORE TAX IS NOT A CONSTANT — IT SCALES WITH `nproc`, WHICH REWRITES THE GATE
ITSELF. 4.17 of 8 cores** (per-rep +-1%), against 3.15 of 4 before, because
`CICompilerCountPerCPU` defaults TRUE: `CICompilerCount` 3->4, `ParallelGCThreads` 4->8,
JIT CPU ~21.7 -> **~43.8 s** (`-XX:CICompilerCount=2` drops the run 4.20 -> 2.55 cores
with self time FLAT, so round 618/740's "not a single-thread lever" reproduces). Round
740 wrote the unpark condition as ">=8 cores NET of the ~3.2 the JVM takes, i.e. >=12"
and justified it with "that tax is fixed per JVM — a larger host simply out-sizes it". It
does not grow with WORKER count (that part holds) but it grows with the HOST, so free
cores went 0.85 -> **3.83, not -> 4.85**. **And 3.83 SUFFICED: the answer arrived at 8,
not 12.** The condition was miscalibrated conservatively by extrapolating a constant from
one host.

**THE LADDER (self ms, n=5, rotated interleave, paired per-rep):** seq **26,145**
(sd 439, 3.6%) | w2 **21,324** (5.5%, **-19.05%**, 5/5) | **w4 19,472** (8.5%,
**-23.84%**, 5/5) | w8 **21,605** (8.2%, **-17.02%**, 5/5). Every level's five deltas are
SIGN-CONSISTENT — none straddles zero, which is exactly what round 740's w4 did. Seq is
3.3% from round 823's 25,299 ms anchor. Box verified clean: 8 real cores, no SMT, no
cgroup cap, **steal 0.000 mean / 0 max over 113 samples**. **THE SHAPE CHANGED, NOT JUST
THE MAGNITUDE: w4 is now the BEST level, and w8 — a +19.4% REGRESSION on 4 cores — is a
17% WIN.** Nothing saturates (cores peak 6.44 of 8), so w8's loss to w4 is the
per-worker duplication, not a core ceiling; user CPU 110 -> 106 -> 117 -> 143 s
reproduces round 740's duplication shape qualitatively.

**THE 1.25x CAP IS DEAD — REFUTED BY COUNTEREXAMPLE, NOT MERELY RE-FITTED: w4 MEASURED
1.343x.** Amdahl re-fits: seq/w2 P=36.9% -> floor **1.584x**; seq/w4 P=34.0% ->
**1.516x**; seq/w8 P=19.8% -> 1.248x. The w2 and w4 fits now AGREE within 8% where on 4
cores they disagreed **3.5x** — the model is coherent through w4 and breaks at w8. The w8
fit landing at 1.248x is a COINCIDENCE, not a confirmation of the old cap.

**VERDICT: two of round 740's three reasons for not attempting (M2) have failed** — the
ceiling more than doubled and there is now a machine to spend it on — **but the third is
still true and WORSE than believed: the mode is a RACE, not a fixed bug.** So (M2) stays
blocked on (PERF.HW.a): you cannot tell a correctness regression from a coin flip, and
step (b) has no trustworthy baseline until it closes. Step (b) is now worth ~1.5x rather
than ~1.25x, which materially changes whether it is worth attempting at all.

**WHAT DID NOT WORK:** an UNSORTED flicker diff manufactured three phantom `tracing.ts`
TS2591 "extras" that were pure line ORDERING (the sorted `comm` re-run: added=1,
removed=0); and a `vmstat` steal parse read a repeated HEADER row as data. **Stage C — 8
extra runs, ~3 min — is what turned "w8 is deterministically 56" into "w8 flickers
46/56"; the single stage-A capture per level would have shipped the wrong
classification, which is the SAME under-sampling error round 754 made.** Artifact:
`docs/perf/worker-scaling-round824.md` + the raw 24-run TSV. Commit `c9258486`.

**Round 823 (2026-08-04) — (INV.7b) IS DONE: THE RELEASE BINARY LINKS, IS BYTE-CORRECT
AND IS 1.26x. THE ROUND'S REAL PRODUCT IS THREE CORRECTIONS TO THE RECORD, ONE OF WHICH
WOULD HAVE PRICED A SHIPPING DECISION OFF THE WRONG BACKEND.**

`linkReleaseExecutableLinuxX64` at the committed 4g, daemons stopped, nothing else
running: **BUILD SUCCESSFUL in 8m53s**, binary **27,493,088 bytes (26.2 MiB)** against
62 MB debug. Memory sampled every 5 s over 107 samples: **peak system used 6,083 MB,
lowest available 9,530 MB, peak process RSS 4.40 GB** — nowhere near the OOM killer, and
unlike round 822's TEST link (8.5 GB RSS, far outside its heap) this one stays roughly
inside `-Xmx`. **Round 610b's BLOCKED-ON-RESOURCES is retired on evidence, with no
retries.** Correctness: `--noEmit --listAll` on the compiler profile, JVM vs native
release, sorted full-text diff **EMPTY** — both `FAILED — 46 error(s)`, 55 lines, and
neither capture carries round 811's `more error(s)` truncation tell. Bench, 5 interleaved
cold pairs, all 10 runs at 46 errors: **JVM cold median 25,299 ms (sd 867, spread 9.2%)
vs K/N release 20,045 ms (sd 783, spread 11.3%) = 1.26x, a 5.25 s saving**, reproducing
round 772's 1.21x.

**CORRECTION 1, AND THE EXPENSIVE ONE: THE 13.4 s NATIVE FIGURE IS THE GRAALVM
NATIVE-IMAGE, NOT KOTLIN/NATIVE.** It is § 2's `tsc` row from round 771, and round 775 is
not its provenance. **K/N release has only ever measured 21.8 s (round 772) and 20.0 s
(now).** So there are **FOUR artifact points, not three**: cold JVM ~25-26 s, warm
`--serve` 11.9 s, **GraalVM 13.4 s**, **K/N 20.0 s**. The § 0.1 framing block and this
item both carried the conflation, i.e. a shipping decision was one paste away from being
priced off a backend nobody had built. Both lines corrected in place.

**CORRECTION 2: ROUND 772 HAD ALREADY DONE THIS ITEM'S WORK AND NOBODY CHECKED IT OFF.**
Commit `9946edf5` linked a release binary at `-Xmx4g` on the OLD box and measured
21,787 ms. **The item then sat BLOCKED-ON-RESOURCES for 50 rounds against a blocker its
own arc had already cleared** — the queue was quoting round 610b's OOM while a later
round's commit contained the refutation. Grep the arc's own commits before inheriting a
blocker.

**CORRECTION 3: ROUND 772's "0.2% SPREAD" AOT-DETERMINISM CLAIM DOES NOT REPRODUCE.**
Five runs span 19.2-21.5 s = **11.3%, WIDER than the JVM's 9.2% in the same interleave**.
It was **n=3**, and the argument built on it — "what an AOT binary with no warm-up should
look like" — has no support. **An AOT binary's spread is not automatically tight, and
n=3 cannot show that it is.**

**ALSO MEASURED:** round 772's 8m05s link vs this round's 8m53s on twice the cores says
**the optimizing link is LLVM-bound, not core-bound — eight cores bought it nothing**.
And the 21.8 -> 20.0 s move could NOT be separated into "the box" vs "50 rounds of
compiler work"; it is not attributed. Gates: docs-only change, no source touched, so
`jvmTest` was deliberately not run and the 13,803 / 0 / 3 baseline is untouched by
construction. Commit `c5b12296`.

**Round 822 (2026-08-04) — THE BOX CHANGED, AND THE KOTLIN/NATIVE GATE IS LIVE AGAIN
AFTER 10 DAYS DARK. A FOURTH DRIFT AXIS EXISTS, IT IS THE ONLY ONE THAT IS NOT
SELF-ANNOUNCING, AND ONE TEST OF IT COST 4,414 OTHER TESTS.**

The owner upgraded the host 4 cores / 7.7 GB -> **8 cores / 15.6 GB** (swap still ZERO)
and re-authorized native builds, superseding the round-775 ban. Three owner decisions
were taken first and recorded at `9d0694b9`: **(JIT.2) no launcher flag** — the -3.1%
that motivated `-XX:-DontCompileHugeMethods` was measured when 19 methods sat over the
limit, and round 821 took that census to 0, so the flag now buys nothing and would only
make C2 compile a 46,000-byte method; what IS approved is **(JIT.2a), a round spent
MEASURING the JDK 25 AOT cache**. **(JIT.3) WON'T DO** — `HugeMethodLimitTest` already
runs the identical whole-program census inside `jvmTest`, so `check` is covered
transitively and wiring the script in too would run it twice for ~2 min a build.

**THE HEAP FLOOR IS 4g, AND IT WAS FOUND BY PROBING DOWN RATHER THAN INHERITING.**
Round 772 measured 6g on the old box and that number has been the stated requirement
since. K/N compiles inside the GRADLE daemon, so the binding setting is
`org.gradle.jvmargs`, which (BUILD.1) had cut to **1g** to make room for the 5g Kotlin
daemon on a 7.7 GB box — that 1g, not the hardware, is what had been starving native.
4g was tried first and succeeded, so **6g was never the floor**. Honest caveat carried
into the commit: 4g is *observed to succeed*, NOT proven minimal — 3g was never probed.
**What made round 772's link blocker dissolve is heap going DOWN, not up.** The margin
that matters is not the heap at all: the daemon reached **8.5 GB RSS against a 4g heap**,
because konan/LLVM allocates outside it.

**THE DRIFT WAS 17, NOT THE ~169 OF ROUND 682 — AND THE REASON GENERALIZES.** Axis 1
(illegal backtick characters) **17 violations in 7 files, every one the SAME character,
a comma**; zero `(`, `)`, `&`, `@`. Axis 2 (`kotlin.assert`) **0**. Axis 3 (JVM-only
stdlib) **0**. Axes 2 and 3 are structurally closed because agents copy the idiom of
neighbouring code; `,` survives because it is the one illegal character that occurs
naturally in English prose, and CLAUDE.md's actionable advice named *parentheses*. The
rule was ablation-confirmed still live (`Name contains illegal characters: ","`).

**THE FIND — A FOURTH AXIS, AND IT INVERTS THE GATE'S RISK PROFILE.** The three
documented axes are all COMPILE-TIME, so each announces itself as a build error. The
fourth compiles clean and **kills the test PROCESS at runtime**: a `commonTest` driving
multi-thousand-frame recursion overflows a native stack that cannot be caught —
`runWithDeepStack` is a pass-through on native and `StackOverflowError` is a never-thrown
stub, so the checker's `init` boundary guard (which on the JVM converts the overflow into
TS2589, the very invariant these tests pin) is INERT. Gradle reports
`Test running process exited unexpectedly` and **every alphabetically-later class
silently never runs — the first full run lost 4,414 of 13,689 tests to ONE test.**
Three pins moved to `src/jvmTest`, nothing weakened or deleted: `CfaTooLargeBailTest`,
`DeepExpressionChainTest` (whole classes — each deep pin is paired with its control),
and the 10k-chain pin extracted out of `Inv4SpineAccessorModifierTest`. **DEPTH IS NOT
THE PREDICATE**: 30,000-term chains, a 3,000-statement flow chain, a 60k-entry
`IntKeyMapTest` and the corpus's own 6,452-term `binderBinaryExpressionStress` all PASS
— the iterative walkers hold. What crashes is specifically what recurses.

**MEASURED:** `compileKotlinLinuxX64` 2m06s, `compileTestKotlinLinuxX64` 1m39s,
**`linuxX64Test` 25m33s then 21m01s at 13,689 / 0 / 3**. Peak system 12.5 GB, floor
available 3.14 GB — never near the 2 GB stop line. **JVM gate 13,803 / 0 / 3, exactly
baseline.** Commits `52c94887` (renames), `fdc0c84c` (the four-axis moves), `6e448818`
(heap), `a2ed6e41` (CLAUDE.md re-grade).

**WHAT DID NOT WORK, which is the reusable part.** (1) **A static sweep cannot find the
runtime crashers** — `repeat(N)`/`List(N)` greps miss `(1..10_000).joinToString` and
`repeat(terms - 1)`, and a plain "numeric literal >= 1000" scan is swamped because every
diagnostic code (2339, 18045, …) is >= 1000; what worked was a literal scan EXCLUDING
`code ==` lines, then running each candidate class natively. (2) **Finding runtime
crashers is inherently iterative — one crash hides all the later ones**, so two 20-25 min
full runs were burned before switching to targeted `--tests` runs at 4-36s each. (3) **A
`--tests` run can print `BUILD SUCCESSFUL in 1s` while UP-TO-DATE and prove NOTHING** —
grep for `Task :linuxX64Test UP-TO-DATE`; the final verification needed `--rerun`.
(4) `@Language` in commonTest is NOT a violation (JetBrains annotations ship a native
klib). (5) The run harness wrote its logs to the repo root because `dirname $0` resolves
to `.` after a `cd`, dirtying the tree mid-round.

**Round 821 (2026-08-03) — (JIT.1) IS COMPLETE. `tryInferSingleTypeParamFromArgs`
11,930 BYTECODES -> AN ENTRY AT 1,869 PLUS THREE `tisp*` HELPERS. CENSUS 1 -> 0. THE ONE
TARGET IN THE ARC THAT NEEDED A DATA-FLOW ANSWER RATHER THAN A CONTIGUITY ONE.**

- **Census re-measured at HEAD on a rebuilt binary first** (law 1): **1**, exactly as the
  round-820 handoff named it. After, measured the same way on the split binary: **0**.
  `KNOWN_OVER_LIMIT` is now empty and `CENSUS_RATCHET` is 0, in `huge_methods.py`,
  `HugeMethodLimitTest`, SESSION-PROMPT.md and CLAUDE.md.
- **WHY THIS ONE WAS DIFFERENT, AND WHAT REPLACED THE USUAL ARGUMENT.** Round 820 measured
  rather than guessed: the bytecodes are **FLAT** (largest 25-line window **449** of 11,930),
  **22.2% (2,643)** are INLINED stdlib bodies charged to their call sites, and ONE
  `for (tp in orderedTps)` loop holds **9,368** of them. So there is no region to lift, and
  the boundaries came from `scripts/tisp_split_analyze.py` — per-region READ/WRITE sets,
  LIVENESS, and an EXIT classification. Measured regions: **PASS1 3,109 / PASS2 5,470 /
  CONSTRAINT 1,566**.
- **THREE FACTS TURNED THAT TABLE INTO AN EXACT SPLIT, AND EACH IS REUSABLE.** (1) A mutated
  CONTAINER crosses a call boundary for free — `candidates` is append-only in both passes, so
  it is a `MutableList` PARAMETER and not one line of the moved text changes; only a REBIND
  forces a decision. (2) The one rebind that outlives its region, `tpSawAnyArg`, is
  **RETURNED, never fielded**. (3) A `Boolean?` return makes every one of the **22**
  whole-function `return null`s mean exactly what it meant, with `?: return null` at each
  call site — so **869 lines move VERBATIM** and the diff carries NO hand-edited control flow.
  No region holds a caller-targeting `continue`/`break` (measured **0/0/0**), which is what
  makes plain helpers legal here; round 819's target needed a one-iteration frame for exactly
  that reason.
- **THE ONE THING THAT IS NOT A PURE MOVE:** the local `data class Candidate` is hoisted to a
  private NESTED class, because a helper signature cannot name a class declared inside a
  function body. Parameter list unchanged; verified as its own check.
- **THE PARTS SUM TO LESS — A FIFTH MEASUREMENT, AND NO MECHANISM IS CLAIMED.** 1,869 + 3,054
  + 5,388 + 1,503 = **11,814 vs 11,930 (116 fewer, 0.97%)**. Each helper is 1.8-4.0% smaller
  than its region; the entry is 84 LARGER than the arithmetic residue, which is the three
  call sites' 23 arguments at ~3.6 bytes each. Round 816's boxing does not apply (no captured
  `var`); round 817's slot addressing is PLAUSIBLE and **was not measured**, so it is not
  claimed. Fifth round, fifth combination — the rule stands: measure yours.
- **EQUIVALENCE (round 805's five checks, plus two).** 316/422/131 lines VERBATIM at dedent
  4/4/8; the file **RECONSTRUCTS from HEAD byte for byte (10,257,330 chars)**, un-applied by
  pulling each body back to its call site rather than by re-running the applier; PARTITION 870
  removed / net +117 exactly as predicted; control-flow tokens equal per region (22/37, 20/31,
  6/0) with every jump measured to stay inside its region; free variables EQUAL each helper's
  parameter list (7/7/9); the `Candidate` hoist is HEAD's modulo `private`; each helper called
  EXACTLY ONCE. **Every argument is passed BY NAME** — `source`/`fileName` are both `String?`
  and `constraint`/`firstWidened` are both `Type`, so a positional permutation type-checks.
- **AN INSTRUMENT TRAP THE ANALYZER NOW CARRIES A CONTROL FOR:** a NAMED ARGUMENT
  (`fileName = fileName`, `fromObjLit = true`) is textually identical to an assignment and is
  told apart only by PARENTHESIS DEPTH — char-level, not a per-line regex. Its first run
  reported `fileName` and `fromObjLit` as rebinds.
- **DISCRIMINATION — 5 arms + a negative control, one mistake per build, predictions recorded
  BEFORE the runs.** Pins validated on the UNSPLIT binary first: **68 ran, exactly the 5
  size/ratchet pins failed**; on the split binary **68 ran, 0 failed**. Every arm reports
  `PINS RAN 68`, so none is vacuous (round 820's daemon-OOM `RAN 0` was guarded with
  `--stop` + a bracket `pkill` + a settle INSIDE the loop).
  A1 fresh `candidates` list — predicted 4, **actual 4**; A2 `source`/`fileName` permuted —
  predicted 1, **actual 1**; A3 `constraint`/`firstWidened` permuted — predicted 2, **actual
  2**; A4 the returned rebind dropped — predicted 1, **actual 1**; A5
  `mapperPairs = emptyList()` — predicted 1, **actual 1**; A6 NEGATIVE CONTROL (helper
  declarations reordered) — predicted 0, **actual 0**. **Six for six, control included.**
- **A3 MATCHED ITS COUNT AND NOT ITS MECHANISM, WHICH IS THE MORE USEFUL HALF.** The
  prediction was an INVERTED display; what happens is that NOTHING is emitted — with
  `firstWidened := Item` the B98.r118 gate's `isSimpleCheckableType` refuses, and with
  `constraint := string` the B98.r128 branch's `Type.Reference` test refuses too, so the leg
  falls to its `return null`. **A permuted argument can silence a whole leg, not just garble
  its output.**
- **WHAT DID NOT WORK, AND THE ONE THAT WAS RESCUED: A4 NEEDED A DIFFERENT CONSUMER, NOT A
  DIFFERENT MISTAKE.** The first A4 run failed its prediction — **predicted 1, actual 0** —
  because the pin read the any-arg fallback through an ARGUMENT POSITION: forcing
  `tpSawAnyArg` to `false` leaves the call's return as the BARE type parameter, and **a bare
  `Type.TypeParam` source relates to most targets**, so `needNumber(Debug.checkDefined(pos))`
  is silent either way. A second pin against an ARITHMETIC consumer (round 440's own shape,
  `Debug.checkDefined(end) - pos`) DOES discriminate it: the re-run reads **68 ran, 1 failed,
  and the failure is exactly that pin**. **The transferable rule: when a mistake is
  measurably inert, ask what the value FLOWS INTO before concluding the seam cannot be
  observed** — an un-inferred type parameter is invisible to the relation and visible to the
  arithmetic pass. Both pins stay; the argument-position one is labelled as
  non-discriminating so nobody later reads its silence as coverage.
- **ALSO DID NOT WORK, and it cost a build:** the position pin first asserted the diagnostic's
  `start` against a reconstructed source. `diagnose` prepends a directives line, and **`line`/
  `character` are resolved against the text WITH it while `start` is an offset into the text
  WITHOUT it** (17 characters apart for that fixture). That is HEAD's behaviour — it
  reproduced identically on the pre-split binary — so the pin now uses the (line, character)
  pair, which is the half the constraint helper computes from `source` and therefore the half
  a permutation destroys.
- **GATE.** Suite **13,791 -> 13,803 / 0 failures / 3 skipped** (+12: 9 `TispSplitTest` + 3
  `HugeMethodLimitTest` size pins), whole results dir wiped first, counted with the python
  XML parser. 8-profile grid diffed set-for-set BOTH directions against a purpose-built
  pre-split binary, identical direct `java --noEmit --listAll` command, absolute class dirs
  confirmed to DIFFER (`javap` finds the three `tisp*` helpers in one and none in the other),
  every capture non-empty and free of an `and N more error` marker — **46/46/46/46/46/46/46/94,
  0 added and 0 removed on all eight**. `--partitionCheck 2` **EQUIVALENT — 46**.
  **`cost_gate.py` all 20 counters +0.00%** — this is `Checker`, so that and the corpus are
  the gates that matter here. `compileKotlinJvm compileTestKotlinJvm --rerun-tasks`: **0 `w:`
  and 0 `e:`**. `huge_methods.py --fail-over 0` exits 0 (588 classes, 14,271 methods,
  **0 over the limit**). **No wall A/B, deliberately** — the family is bounded four times over
  and this lands for the threshold and the (f) gate.
- **(JIT.1) IS CLOSED — WHAT THE ARC COST AND WHAT IT BOUGHT.** Rounds **802-821**: 20 rounds,
  **19 methods** split, from `forEachChild` (9,750) and `checkMemberAccessMissingCore` (46,567,
  5.8x the limit) to a static initializer and, last, an inference function with no seams in it.
  It bought **one measured wall gain — -3.93%, B wins 5/5** (round 803, monolith vs split),
  the largest single measured improvement in this queue's history; **everything else landed
  for the THRESHOLD**, and that is the honest accounting. The case for those rounds is not a
  wall number: a method over 8,000 bytecodes is NEVER compiled by C1 or C2, so its cost cannot
  improve with load, input size, warm-up or JVM version, and **no other gate in this repo can
  see it** — the corpus measures meaning, `cost_gate.py`'s counters do not move, and
  `-XX:+PrintCompilation` prints nothing (the compile is never *proposed*, so never
  *skipped*). It also leaves two instruments: `scripts/huge_methods.py` (the census, now a
  ratchet at 0 in the round gate) and `HugeMethodLimitTest` (the same census inside the
  suite, failing on a NEW offender *and* on a stale entry) — the second paid for itself on
  its first run by finding `Checker.<clinit>`, which the script had been charging to a
  16-byte access bridge.
- **FOR THE NEXT AGENT.** (JIT.1) is done; the ratchet stays as a standing gate and **its job
  is now the opposite one — catching the FIRST method to cross 8,000 again** (`walkFunctionBodiesInExpr`
  **7,702**, `cpaSpineLeave` **7,359** and `ctaM3StmtAnchorCore` **7,245** are one edit under
  it). The queue's next unchecked perf items are **(SETUP.2)** (`buildFileLocalTypeMaps`, 636
  ms = 2.2% of the compile) and **(ENGINE.3)**; round 819's open lead — a COUNTED ablation for
  `tcjsMoveDetachedHeaderComments`, whose corpus zero bounds frequency but never existence —
  is still unclaimed.

**Round 820 (2026-08-03) — (JIT.1)(e) LANDED FOR `Checker.<clinit>`: 10,339 BYTECODES ->
3,156 PLUS SEVEN TOP-LEVEL `ckConst*` BUILDERS. CENSUS 2 -> 1. THE LAST SHAPE NOBODY IN
THIS ARC HAD SPLIT — A STATIC INITIALIZER.**

- **Census re-measured at HEAD on a rebuilt binary first** (law 1): **2**, named exactly as
  the round-819 handoff left them. After: **1**, measured the same way on the split binary.
  `Checker.<clinit>` **10,339 -> 3,156**.
- **WHAT IS ACTUALLY IN A `<clinit>`, AND WHAT IS FREE.** The companion declares **276**
  members and only **50** cost anything: a Kotlin `private const val` of a primitive/`String`
  carries a **`ConstantValue` attribute** and executes NO bytecode, so the ~200 dispatch tags
  (`URES_EDGE_ROOT`, `DA_STMT_LEAK`, `TAV_CONT`, …) are free. All 10,339 are `setOf`/`mapOf`
  literals. Regions MEASURED before the edit with the new `scripts/clinit_split_analyze.py`
  (which matches `javap`'s `  static {};` rendering — the thing round 817 found the census
  regex could not): `KNOWN_GLOBALS` **2,992** / `DOM_GLOBAL_NAMES` **1,368** /
  `KNOWN_GENERIC_BUILTINS` **787** / `LIB_MIN_TARGET` (the `mapOf` only) **671** /
  `VALUE_ONLY_GLOBALS` **553** / `KEYWORD_IDENTIFIERS` **497** / `NODE_BUILTIN_MODULES`
  **371** = 7,239 of 10,339 moved. The other 43 collection constants are all under 140.
- **THE FREQUENCY ARGUMENT IS THAT THERE ISN'T ONE, AND SAYING SO IS THE POINT.** A static
  initializer runs ONCE, at class load: no A/B in this repo can price it and `cost_gate.py`
  measured all 20 counters +0.00%. It lands for the (JIT.1)(f) ratchet. The JIT cliff this
  arc exists to close is a WHOLE-RUN interpreted penalty, and a method that executes once
  does not have one.
- **WHY TOP-LEVEL, AND THE ONE CONSTRAINT THAT DECIDES WHAT CAN MOVE.** A companion
  `private fun` is an instance method on `Checker$Companion`, which `<clinit>` would have to
  reach through the very static field it is installing; a top-level `private fun` is a plain
  `invokestatic` on `CheckerKt` (Kotlin adds a 3-byte `access$` bridge per builder, 21 bytes
  in all). The price: **a `private` companion member is INVISIBLE to a top-level function in
  the same file** — so `LIB_MIN_TARGET`, whose initializer ends `+ TYPED_ARRAY_NAMES.flatMap
  { … }.toMap()`, moved only its leading `mapOf(…)` and kept the tail in the companion. That
  boundary is arm A6.
- **THE PARTS SUM TO LESS AGAIN — 84 FEWER (0.81%) — AND NEITHER KNOWN MECHANISM APPLIES.**
  3,156 + 7,078 + 21 = 10,255 vs 10,339. Round 816's boxing needs a captured `var` (there is
  none) and round 817's slot addressing needs LOCALS (**a `<clinit>` of `putstatic`s has
  none**). Fourth round, fourth combination: **"the parts sum to less" is still not a law.**
- **EQUIVALENCE (round 805's five checks, in the shape a HOIST takes).** Bodies re-extracted
  from the NEW file at dedent 8 are byte-identical to HEAD's; the file **RECONSTRUCTS from
  HEAD byte for byte (10,258,399 chars)**; PARTITION `416 removed == 402 moved + 7 decl + 7
  close`; control flow is checked to be ABSENT (0 tokens per region) plus the literal
  ELEMENT COUNT per region (52/70/389/186/78/39/50) identical on both sides; free variables
  — no region reads a companion member or `this`; and a sixth check, each builder referenced
  exactly twice (declaration + one call site) with the property's declaration head unchanged.
- **DISCRIMINATION — pins validated on the UNSPLIT binary first (66 ran, exactly 5 failed and
  they are the 5 size/ratchet pins); 66 ran / 0 failed on the split. Each mistake alone, count
  PREDICTED first:** A1 `KNOWN_GLOBALS` <- DOM builder predicted 1, **2**; A2 `VALUE_ONLY` <-
  keyword builder predicted 1, **1**; A3 `KEYWORD_IDENTIFIERS` <- value-only builder predicted
  1, **2**; A4 `NODE_BUILTIN_MODULES` <- keyword builder predicted 1, **1**; A5
  `DOM_GLOBAL_NAMES` <- node-modules builder predicted 1, **1**; A6 the `TYPED_ARRAY_NAMES`
  TAIL dropped predicted 1, **1**; A7 NEGATIVE CONTROL (the seven builder DECLARATIONS
  reordered) predicted 0, **0**. Every arm `RAN 66`; **five of the six mistakes type-check
  (0 `e:`)**, which is exactly why the pins exist.
- **BOTH UNDER-PREDICTIONS HAVE ONE CAUSE, AND IT IS A RULE.** The extra failure in A1 and A3
  is the same pin (TS2749 on `parseInt`), because `parseInt` is a member of BOTH sets those
  arms substitute and `KNOWN_GLOBALS` is UPSTREAM of `isValueOnlyTypeRef`. **Predict from the
  PIN SUBJECT's membership in the set being substituted IN, not from which constant the pin is
  "about".** Two seams are STATED rather than ablated: the two `Map`-returning builders have
  unique types, so no substitution among the seven type-checks at their call sites.
- **WHAT DID NOT WORK.** A3 and A7 first returned `RAN 0` — `OutOfMemoryError: GC overhead
  limit exceeded` in `compileKotlinJvm`. Seven successive full `Checker.kt` compiles in one
  script with no `--stop` between them walk into BUILD.1's ceiling, because an idle Kotlin
  daemon keeps its heap. **A vacuous arm is not a result**: both were re-run with `--stop` +
  a bracket-pattern `pkill` + an 8 s settle before each build. Any batch over ~2 arms needs
  that hygiene INSIDE the loop.
- **GATE.** Suite **13,778 -> 13,791 / 0 failures / 3 skipped** (+13). 8-profile grid BOTH
  directions vs a purpose-built pre-split binary, class dirs confirmed to differ (14 `ckConst`
  methods vs 0), every capture non-empty — **46/46/46/46/46/46/46/94, 0 added and 0 removed**.
  `--partitionCheck 2` **EQUIVALENT — 46**. `cost_gate.py` **all 20 counters +0.00%**.
  `--rerun-tasks` **0 `w:` / 0 `e:`**. `huge_methods.py --fail-over 1` exits 0.
- **(JIT.1) IS AT ONE, AND THIS ROUND MEASURED THE LAST TARGET RATHER THAN ATTEMPTING IT.**
  `tryInferSingleTypeParamFromArgs` is **1,064 lines** and its bytecodes are FLAT — the
  largest 25-line window is **449** of 11,930 — with **22% (2,643) INLINED stdlib bodies**
  charged to their call sites, and its bulk is one `for (tp in orderedTps)` loop mutating
  `candidates`/`tpSawAnyArg`/`mapperPairs` across every candidate boundary. It needs a
  per-region read/write-set and liveness answer, not a contiguity one. **MEASUREMENT TRAP
  for whoever takes it: `Checker.kt` exceeds 65,536 lines and the `LineNumberTable` is a
  `u2`, so this function's lines WRAP** — they report as 49967-51030 and must have 65,536
  added back; un-corrected they land inside the `companion object` and look plausible.
- **STILL OPEN, NOT TAKEN: round 819's `tcjsMoveDetachedHeaderComments` counted ablation.**
  It did not fit beside seven ablation builds plus the full gate. The design stands: it needs
  a counter at the region's EMITTING branch (not at its entry), inert by default, read after
  a full corpus run — a corpus zero bounds frequency, never existence.
- Full derivation: `docs/perf/setup-phase-and-huge-methods.md` § 25.

**Round 819 (2026-08-03) — (JIT.1)(e) LANDED FOR `Transformer.transformToCommonJS`:
28,991 BYTECODES -> AN ENTRY AT 2,944 PLUS NINETEEN `tcjs*` HELPERS. CENSUS 3 -> 2, AND
EVERY `Transformer` ENTRY IS GONE. THE NEW SHAPE: A MOVED REGION THAT `continue`s THE
CALLER'S LOOP.**

- **The census was re-measured at HEAD on a rebuilt binary first** (law 1): **3** over the
  limit, named exactly as the round-818 handoff left them, `transformToCommonJS` **28,991**
  — the largest method in the compiler, 3.6x the limit. After: **2**, measured the same way
  on the binary built from the split source.
- **THE SHAPE NO EARLIER TARGET HAD.** The bulk of this function is
  `for (stmt in statementsToProcess) { when (stmt) { … seven arms … } }`, and two arms hold
  `continue`s that target THAT loop — **6 of the function's 27** (there are no `break`s):
  one in the `VariableStatement` arm (the `export const { x, ...rest }` object-rest path)
  and five in the `ImportDeclaration` arm. A `continue` cannot survive extraction into a
  member function. **The fix is a ONE-ITERATION FRAME**: the moved text runs inside
  `for (stmt in listOf(stmtIn)) { … }`, where `continue` means exactly what it meant before
  — abandon the rest of THIS statement — so the region moves VERBATIM, the control-flow
  census is unchanged (`continue`/`break` **27 == 27**), and because the frame's loop
  variable is `stmt` (the arm's own smart-cast subject) not one reference inside is
  rewritten either. The alternative — rewriting six deeply nested `continue`s to
  `return <holder>` — edits the moved text at exactly the sites hardest to verify.
- **THE INSTRUMENT THAT DECIDES WHICH REGIONS NEED THE FRAME EARNED ITS PLACE ON ITS FIRST
  RUN.** A brace-depth scan that asks, per `continue`, whether a loop was opened inside the
  region (`tcjs_split_verify.outer_continues`) said **five** in the import arm; the list I
  had derived by eye said four. A hand census of `continue`s looks complete and is not.
  The verify script now asserts, as a sixth check, that every region holding a caller-loop
  `continue` is framed and no other region is.
- **REGIONS MEASURED BEFORE THE EDIT** with `scripts/method_bytes_by_line.py` — 1,383 /
  2,285 / 1,182 / 923 / 322 / **4,451** / 449 / 425 / 415 / **2,756** / **2,372** / 745 /
  738 / 757 / 913 / 1,224 / **2,749** / 700 / 1,775 of the 28,991. Entry **2,944**, largest
  helper 4,335. Six small post-loop blocks (2,065 bytecodes) stay in the entry deliberately.
  **The frequency argument is irrelevant** — this runs once per FILE on the EMIT path, and
  every A/B in this arc is `--noEmit`.
- **ONLY ONE OF THE TWO BYTECODE-NEGATIVE MECHANISMS FIRES, AND THE NET IS SMALL.** 28,886
  vs 28,991 = **105 fewer (0.36%)**: 2-byte `aload/astore` 4,049 -> 3,979, 1-byte 409 ->
  536, and round 816's boxing mechanism measures **0 both sides** — every lambda this
  function captures a `var` into is an INLINE stdlib function, so no `Ref$*Ref` is ever
  allocated. Three rounds, three different combinations; **"the parts sum to less" is not a
  law** — at 19 call sites and 128 arguments the added call machinery nearly cancels the
  slot-addressing win.
- **EQUIVALENCE (round 805's five checks + the frame check).** Nineteen regions re-extracted
  from the NEW file VERBATIM (dedent 0/12/8); file RECONSTRUCTS from HEAD byte for byte
  (944,271 chars); PARTITION of 2,173 body lines, 1,907 moved, none dropped; `return`
  **1 + 10 == 11**, `continue`/`break` **27 == 27**; free variables per region == parameters
  + prologue, and all **128 arguments passed BY NAME** (74 are same-typed mutable containers
  a positional call could permute and still type-check).
- **DISCRIMINATION — pins validated on the UNSPLIT binary first (76 ran, exactly 5 failed
  and they are the 5 size/ratchet pins), 76 ran / 0 failed on the split. Each mistake alone,
  count PREDICTED first:** A1 ORDER (`tcjsRewriteExportMutations` after the entry's own
  direct-export rewrite) predicted 1, **1**; A2 CONTAINER IDENTITY (fresh
  `functionExportStubs`) predicted 3, **2**; A3 RETURN SIGNAL (import flag write-back
  dropped) predicted 3, **3**; A4 THE FRAME (`listOf(stmtIn, stmtIn)`) predicted 2, **1**;
  A5 DROPPED CALL (`tcjsMoveDetachedHeaderComments`) predicted 1, **0**; A6 NEGATIVE CONTROL
  (swap two pre-scans that read only `originalSourceFile`) predicted 0, **0**. Every arm
  `RAN 76` with 0 `e:`.
- **THE TWO MISSES, AND THE SEAM NOTHING DISCRIMINATES.** A2's third pin exports through an
  `export { f }` CLAUSE, so its stub is appended by the `ExportDeclaration` arm, not the
  `FunctionDeclaration` one — two arms write the same list and the prediction blamed the
  wrong producer. A4's second pin survives two iterations because the unused import's
  `continue` fires both times and the duplicate `require` is removed downstream; the frame
  is caught, but by ONE pin, through temp-var numbering. **A5 is undiscriminated by the pins
  AND by the whole corpus**: the full suite was re-run against that ablation and came back
  **13,778 / 0 failures**. Structural reason: `tcjsExtractEarlyPrePreamble` (the EARLY twin,
  before the hoist insertions) already handles every header-comment shape reachable here,
  including one whose import is later elided; the post-elision pass is a second chance for a
  shape where elision changes which statement is `result[1]`, and neither a probe in reach
  nor any of the 993 CommonJS emit baselines builds one. **That is a LEAD, not a licence to
  delete** — a corpus zero bounds frequency, never existence — and the honest next step is a
  COUNTED ablation of the region's emitting branch.
- **GATE.** Suite **13,752 -> 13,778 / 0 failures / 3 skipped** (+26: 23
  `TransformToCommonJsSplitTest` + 3 `HugeMethodLimitTest` size pins), python XML parser,
  whole results dir wiped first. 8-profile grid diffed set-for-set BOTH directions against a
  purpose-built pre-split binary, identical direct `java` command, absolute class dirs,
  class dirs confirmed to differ (0 `tcjs*` methods vs 20), every capture non-empty and free
  of an `and N more error` marker — **46/46/46/46/46/46/46/94, 0 added and 0 removed on all
  eight**. `--partitionCheck 2` **EQUIVALENT — 46**. `cost_gate.py` **all 20 counters
  +0.00%**. `compileKotlinJvm compileTestKotlinJvm --rerun-tasks`: **0 `w:` and 0 `e:`**.
  `huge_methods.py --fail-over 2` exits 0. **The gate that actually sees this function is
  the corpus EMIT baselines**: of the 5,692 `compiles to JavaScript matching` subtests,
  **993 have a CommonJS-shaped baseline**, partitioned by exactly the families these regions
  own — `exports.default` 116, `module.exports` 101, `__createBinding` 100, `__importStar`
  89, `__importDefault` 64, `__exportStar` 23, `__rest` 4 (that last is the
  `VariableStatement` arm's object-rest path, the branch holding its caller-loop
  `continue`). All passed. **No wall A/B was run and none should be.**
  Full derivation: `docs/perf/setup-phase-and-huge-methods.md` § 24.
- **FOR THE NEXT AGENT. (JIT.1) is at TWO, the ratchet is at 2, and every remaining target
  is a DIFFERENT KIND OF PROBLEM from the eleven this arc has solved:**
  `Checker.tryInferSingleTypeParamFromArgs` **11,930** needs a scripted DATA-FLOW answer —
  mutable locals cross every candidate boundary, so no contiguity argument settles it — and
  `Checker.<clinit>` **10,339** is a static initializer holding the class's object-level
  constants, shrinkable only by moving those initializers into helper methods it calls, and
  priceable by no A/B in this repo (it runs once, at class load). Also open, from this
  round: **is `tcjsMoveDetachedHeaderComments` reachable at all?** — counted ablation, not
  deletion.


**Round 818 (2026-08-03) — (JIT.1)(e) LANDED FOR `Transformer.transformClassBody`:
16,233 BYTECODES -> AN ENTRY AT 5,202 PLUS NINE `tcb*` HELPERS. CENSUS 4 -> 3 — AND IT IS
THE FIRST TARGET WHERE **BOTH** BYTECODE-NEGATIVE MECHANISMS FIRE AT ONCE.**

- **The census was re-measured at HEAD on a rebuilt binary first** (law 1): **4** over the
  limit, the four named exactly as the round-817 handoff left them, `transformClassBody`
  **16,233**. After: **3**, measured the same way on the binary built from the split source.
- **WHY THIS TARGET.** The prompt named it and the measurement agreed: `--top`-level
  attribution (`scripts/method_bytes_by_line.py`) shows it partitions into nine contiguous
  regions of 584-1,832 bytecodes with exactly ONE non-trivial coupling, i.e. round 817's
  recipe at 2x the size rather than `transformToCommonJS`'s 3.6x. `<clinit>` is a shape
  nobody here has split and no A/B can price; `tryInferSingleTypeParamFromArgs` still needs
  the data-flow answer.
- **THE SPLIT.** Entry **5,202** plus `tcbAllocatePrivateState` **1,716**,
  `tcbBuildOutputMembers` **1,604**, `tcbBuildTransformedConstructor` **1,317**,
  `tcbCaptureClassAlias` **1,236**, `tcbEmitAliasAndPrivateState` **1,178**,
  `tcbBuildInstanceInitializers` **1,130**, `tcbExtractComputedPropertyKeys` **1,050**,
  `tcbEmitStaticFieldTrailing` **919**, `tcbLowerAutoAccessors` **666**. Six regions move at
  dedent 0, three (inside the static-trailing `if`) at dedent 4; only one needs a prologue.
- **TWO SHAPES NO EARLIER TARGET HAD, AND BOTH ARE SCOPING, NOT CONTROL FLOW.** (i) A
  **LOCAL DATA CLASS** (`PrivateFieldInfo`) constructed by a moved region — un-nameable
  from a member function, so LIFTED to a private nested class; behaviour-free (it captures
  nothing and never escapes), and it is the only text change outside the extraction.
  (ii) A **LOCAL `fun` CALLED FROM BOTH SIDES OF A BOUNDARY** (`buildStaticBlockIife`,
  which closes over the two alias `var`s the split decides) — it can neither move nor be
  duplicated, so it is passed as a **function-typed parameter** (`::buildStaticBlockIife`),
  leaving the moved call site textually untouched. **The ORDER that makes that sound is
  enforced by NOTHING in the types** — it is this round's first ablation.
  `isCapturablePrivateMethod` needed none of it: both call sites are inside ONE region, so
  the local `fun` MOVED with them. **The test is where the call sites are, not what the
  construct is.**
- **THE PARTS SUM TO 16,018 AGAINST 16,233 — 215 FEWER — AND HERE BOTH MECHANISMS FIRE.**
  Round 816's boxing: boxed `var` reads inside the function **31 -> 11**. Round 817's
  local-slot addressing: 2-byte `aload`/`astore` **1,947 -> 1,850**, 1-byte **197 -> 254**.
  **The 11 boxed reads that REMAIN are the entry's two alias temps, still captured by the
  local `fun` — the same property that forced the function-typed parameter.** So the two
  mechanisms are not alternatives to pick between; measure both.
- **EQUIVALENCE IS A MEASUREMENT (round 805's five checks;
  `scripts/tcb_split_{analyze,apply,verify}.py`), all green:** nine regions re-extracted
  from the NEW file and compared VERBATIM; the file RECONSTRUCTS from HEAD byte for byte
  (**923,613 chars**); the accounting is a PARTITION (1,321 body lines, each claimed exactly
  once, 840 moved) with the ONE line that is neither kept nor moved — the lifted data class
  — named, asserted unique and asserted present in its new form; control flow bounded to
  the changed region on both sides, `return` **2 + 5 == 7**, `continue`/`break` **11 == 11**;
  free variables per region equal to the parameter list PLUS the declared prologue, every
  call site naming every argument.
- **THE FREE-VARIABLE MATCHER HAD TO BE REWRITTEN, NOT REUSED — TWICE OVER.** (i) Round
  817's binds `val x: T = <expr>` to the first token of the INITIALISER (its optional
  annotation group eats `x: T = `), so on this function it bound `if` and never bound
  `members`. (ii) A NAMED ARGUMENT (`name = …`, `initializer = …`, `modifiers = …`) is
  textually identical to a read of a same-named local, and `transformClassBody` has
  parameters called `name` and `modifiers` while the AST constructors it calls take
  arguments of exactly those names — unfiltered, every region reports capturing `name`.
  Both filters carry a positive control in BOTH directions.
- **PINS VALIDATED ON THE UNSPLIT BINARY FIRST — 60 ran, exactly 5 failed and they are the
  5 size/ratchet pins**, which must fail there. On the split binary **69 ran, 0 failed**.
  Every probe shape was read off the REAL compiler in a 1.2-second scratch project before a
  pin was written.
- **DISCRIMINATION 4 OF 4, each mistake alone on its own build, the COUNT predicted before
  each run — PLUS A NEGATIVE CONTROL AT ITS PREDICTED ZERO.** (1) The **ORDER seam**
  (capture moved after the two emit stages; the static block's `this` then never routes
  through the alias and the downlevelled arrow captures the OUTER `this`) — predicted 1,
  failed **1**. (2) The **LIST-IDENTITY seam** (a fresh `emittedStaticBlocks`; the caller's
  later loop skips what is recorded there, so every static block emits TWICE) — predicted 1,
  failed **1**. (3) The **RETURN-SIGNAL seam** (`constructorAdded` dropped; the constructor
  is emitted at its source position AND prepended) — predicted 1, failed **1**. (4) A
  **dropped call** (`tcbExtractComputedPropertyKeys`) — predicted 1, failed **1**.
  (5) **NEGATIVE CONTROL**: `heritageIn = transformedHeritage` instead of `finalHeritage` —
  nothing between the declaration and the call assigns it — predicted **0**, failed **0**.
  Every arm reports `RAN 10`, so no arm passed vacuously.
- **WHAT DID NOT WORK / WHAT COST TIME.** (i) The prologue itself produced TWO successive
  warnings on a warning-clean build — `var classTempVar: String? = null` is a redundant
  initializer (the moved region assigns it unconditionally), and the fix then drew
  `This 'var' … can be declared as 'val'`. Two extra builds; the lesson is that a prologue
  line is NEW code and gets the same scrutiny as the moved text, and that Kotlin's answer
  tells you something true about the region (it is unconditionally assigned, which is also
  why `heritageTempVar` and `finalHeritage` legitimately stay initialised `var`s).
  (ii) The first per-method boxing attribution keyed javap output by `line.strip()[:70]`
  and reported ZERO boxed reads in a method that has 31 — **the method's own name sits past
  character 70 of its signature**, so the truncated key did not contain it. Caught only
  because a whole-method slice disagreed; the rewritten pass asserts a key containing
  `transformClassBody(` exists before reporting. (iii) Time was lost to polling background
  builds instead of ending the turn — the CLAUDE.md rule is real and the loop is easy to
  fall into when each poll looks cheap.
- **SEAMS NOT DISCRIMINATED, stated rather than glossed.** `tcbEmitAliasAndPrivateState`
  and `tcbEmitStaticFieldTrailing` both append to `trailingStatements` and were NOT shown
  to be order-sensitive against each other; reaching a difference needs a class that has
  both a private-method brand AND a static field whose initializer forces the alias, and no
  probe in reach combines them. Likewise the three regions' `staticProperties` /
  `staticBlocks` arguments could be permuted between the two emit helpers only in shapes
  where both lists are non-empty and interleaved, which no pin here builds.
- **GATE.** Suite **13,739 -> 13,752 / 0 failures / 3 skipped** (+13: 10
  `TransformClassBodySplitTest` + 3 `HugeMethodLimitTest` size pins), python XML parser,
  whole results dir wiped first. 8-profile grid diffed set-for-set BOTH directions against a
  purpose-built pre-split binary, identical direct `java` command line, absolute class dirs,
  class dirs confirmed to differ (14 `tcb` mentions vs 0), every capture non-empty and free
  of an `and N more error` marker — **46/46/46/46/46/46/46/94, 0 added and 0 removed on all
  eight**. `--partitionCheck 2` **EQUIVALENT — 46**. `cost_gate.py` **all 20 counters
  +0.00%**. `compileKotlinJvm compileTestKotlinJvm --rerun-tasks`: **0 `w:` and 0 `e:`**.
  `huge_methods.py --fail-over 3` exits 0. **The gate that actually sees this function is
  the corpus EMIT baselines**: 5,692 `compiles to JavaScript matching` subtests run
  `transformClassBody` for every class they contain, and 55 are in the families these
  regions own (`parameterPropertyInConstructor1..4`,
  `parameterPropertyInConstructorWithPrologues`, `computedPropertyNameWithImportedKey`,
  `privateNameWeakMapCollision`, `controlFlowAutoAccessor1`,
  `classPropertyInferenceFromBroaderTypeConst`, …) — all passed. **No wall A/B was run and
  none should be**: this is the emit path and every A/B in the arc is `--noEmit`.
  Full derivation: `docs/perf/setup-phase-and-huge-methods.md` § 23.
- **FOR THE NEXT AGENT. (JIT.1) is at THREE, the ratchet is at 3:**
  `Transformer.transformToCommonJS` **28,991** (this round's recipe again, same emit path,
  1.8x the size — and the last one that is a contiguity argument),
  `Checker.tryInferSingleTypeParamFromArgs` **11,930** (still the only target needing a
  scripted DATA-FLOW answer), `Checker.<clinit>` **10,339** (a static initializer holding
  the class's object-level constants; it can only shrink by moving those initializers into
  helper methods it calls, and no A/B can price it — it runs once, at class load).
  **Tighten the ratchet by one as each lands — `HugeMethodLimitTest` fails on a stale
  entry, so it will insist.**


**Round 817 (2026-08-03) — (JIT.1)(f) LANDED: THE CENSUS IS A RATCHET, IN TWO PLACES —
AND ON ITS FIRST RUN THE SECOND INSTRUMENT FOUND THAT ONE OF THE FIVE NAMES HAD BEEN A
PHANTOM SINCE ROUND 802. PLUS (e) FOR `Transformer.transform`, 8,934 -> AN ENTRY AT 2,989
PLUS SEVEN HELPERS; CENSUS 5 -> 4.**

- **The census was re-measured at HEAD on a rebuilt binary first** (law 1): **5** over the
  limit, and the five named exactly as the round-816 handoff left them.

**PART A — (f), THE RATCHET.**

- **THE HONEST FORM IS A RATCHET, NOT A ZERO, AND IT SHIPS TODAY.** Round 802 found 19 over
  the limit by running a census for the first time in 800 rounds; nothing else can see this
  (the corpus measures meaning not cost, `cost_gate.py`'s counters do not move,
  `-XX:+PrintCompilation` prints nothing because the compile is never *proposed*). At a
  census of 5, all five named, `--fail-over 5` catches a NEW offender immediately.
  `--fail-over 0` is the END STATE, not a precondition.
- **WIRED IN TWO PLACES, DELIBERATELY.** (1) `python3 scripts/huge_methods.py --fail-over
  <census>` as a ROUND-GATE step beside `cost_gate.py` (CLAUDE.md + SESSION-PROMPT.md).
  (2) `HugeMethodLimitTest` runs the SAME whole-program census INSIDE the suite — it walks
  the compiled main output from a marker resource and parses every `Code` attribute length
  — so it cannot be forgotten, and it fails on a NEW offender AND on a STALE named entry.
  **That second direction is the tightening rule made mechanical**, and this round paid it:
  landing Part B made the suite demand the tightening before it would go green.
  **Gradle's `check` was deliberately NOT touched** — that is a build-system change and
  stays owner-gated as (JIT.3). The prompt's own guardrail, honoured rather than decided.
- **PROVEN TO FIRE — three arms, each its own build, because a gate that has never failed
  is not known to work.** Committed state **44 pins / 0 failed**; ratchet tightened by one
  **44 / EXACTLY 1**, the census pin; a stale named entry **44 / EXACTLY 1**, the
  named-offenders pin. And `--fail-over 4` exits 1 against a census of 5 while
  `--fail-over 5` exits 0.
- **THE FIND, AND IT IS THE ROUND'S MOST VALUABLE OUTPUT. `javap` renders a static
  initializer as `static {};` — with NO parameter list — so `huge_methods.py`'s
  method-header regex (which requires a `(`) never started a method there and charged every
  one of `Checker.<clinit>`'s 10,339 bytecodes to whatever method preceded it in the class
  file. That method is `access$checkBigintPropertyNames$emit`, whose real body is 16 BYTES
  — and which this queue has carried as a 10,339-bytecode SPLIT TARGET since round 802.**
  The census COUNT was right and one of its five NAMES was wrong for fourteen rounds; the
  real offender at that size had never been seen. Method count 14,001 -> 14,107 once the
  regex was fixed: **106 static initializers had never been counted at all.** Transferable:
  *read `Code` attribute lengths from the class file when the answer matters — a `javap`
  rendering is a parse away from the truth* — and *a second instrument that reaches the
  same number by a different route is worth building precisely because it can disagree.*

**PART B — (e) FOR `Transformer.transform`.**

- **THE SPLIT.** Entry **2,989** plus `tfCollectTopLevelNames` **1,367**,
  `tfCollectHelperStatements` **1,182**, `tfInjectTslibImport` **1,142**,
  `tfLiftLeadingComments` **663**, `tfElideInternalImportAliases` **636**,
  `tfWrapNoLibMetadataArgs` **406**, `tfInjectCreateRequireHeader` **385**. Region sizes
  were MEASURED before the edit with `scripts/method_bytes_by_line.py`.
- **WHY THIS TARGET.** The smallest of the five and the last cheap one: a straight pipeline
  of stages, each consuming the previous stage's list. `tryInferSingleTypeParamFromArgs`
  still needs a scripted data-flow answer and was not attempted; the other two Transformer
  methods are 2-3x larger.
- **THE FREQUENCY ARGUMENT, HONESTLY: it runs ONCE PER FILE (78 on the compiler profile),
  and it is on the EMIT path, so EVERY A/B in this arc is `--noEmit` and STRUCTURALLY BLIND
  to it. No wall A/B was run and none should be.** It lands for the threshold and for (f).
  **Its behavioural gate is the corpus suite's EMIT baselines** — a real gate, and the one
  that matters here — plus `TransformSplitTest`.
- **WHAT STAYS, BY ONE RULE.** The CommonJS and module:preserve branches hold all three
  whole-function `return`s, so leaving them in the entry buys round 813's property:
  **no helper needs a return signal at all**, hence none can fail to propagate one.
- **THE PARTS SUM TO 8,770 AGAINST 8,934 — 164 FEWER — AND ROUND 816's MECHANISM IS
  MEASURED ABSENT.** `transform` holds **0** `Ref$ObjectRef` references before and after
  (class total 86 either way), so the boxing story does not transfer. The measured cause is
  **LOCAL-SLOT ADDRESSING**: the monolith's ~60 live locals push almost every reference past
  slot 3 and pay the 2-byte `aload N`/`astore N`, while a helper's sit in slots 0-3 and take
  the 1-byte `aload_N`. Counted across entry + all seven helpers: **2-byte 841 -> 741
  (-100), 1-byte 219 -> 288 (+69)** — 100 of the 164 bytes, prologue/epilogue netting out
  the rest. Parameters cost nothing: Kotlin emits **no** `checkNotNullParameter` for a
  private method (count 0). **So there are now TWO measured reasons a split can be
  bytecode-negative, and the prior from one does not carry to the other** — check which
  applies before claiming either.
- **EQUIVALENCE IS A MEASUREMENT (round 805's five checks;
  `scripts/transform_split_{apply,verify}.py`), all green:** seven contiguous in-order
  regions re-extracted from the NEW file and compared VERBATIM at **dedent 0 for all
  seven** — every value-producing region moved WITH its own `val` declaration plus one
  added `return <name>` line, so **not one character of moved text is edited**; the file
  RECONSTRUCTS from HEAD byte for byte (**912,136 chars**); the accounting is a PARTITION
  (599 body lines, each claimed exactly once, 343 moved); control flow enumerated on both
  sides and BOUNDED to the changed region on both — `return` **3 + 5 == 8**,
  `continue`/`break` **0 == 0**; free variables per region equal to the parameter list
  exactly, in both directions, and every call site passes every argument BY NAME.
- **A NEW TRAP, CAUGHT BY A POSITIVE CONTROL RATHER THAN A FAILURE.** The alias elision
  contains `return@filter` — a LABELLED return belonging to a lambda — so the family's
  usual `(?<![@\w.])return\b` counts it on BOTH sides and would have read `+6` where the
  truth is `+5`. The verifier's regex excludes it and carries an explicit control asserting
  the regex does NOT match `return@filter`, so the exclusion cannot silently rot.
- **PINS VALIDATED ON THE UNSPLIT BINARY FIRST — 56 ran, exactly 5 failed and they are the
  5 size/ratchet pins**, which must fail there. So all 9 behavioural pins plus the CommonJS
  control describe HEAD, not the split.
- **DISCRIMINATION 3 OF 3, each mistake alone on its own build, the COUNT predicted before
  each run — PLUS A NEGATIVE CONTROL AT ITS PREDICTED ZERO.** (1) Swapping the
  `tfCollectHelperStatements` / `tfLiftLeadingComments` calls — **the ORDER seam nothing in
  the data flow enforces**, since both take `helpers` and neither returns it, and the lift
  reads the list BY VALUE so every helper body is silently lost — predicted 2, failed **2**,
  exactly the two named. (2) Handing `tfCollectTopLevelNames` a FRESH set instead of the
  caller's (the caller subtracts that very instance) — predicted exactly 1, failed **1**.
  (3) Dropping the `tfInjectCreateRequireHeader` call — predicted exactly 1, failed **1**.
  (4) **NEGATIVE CONTROL: moving `val isCjsFileName` INTO the helper** — a pure expression
  over a parameter the helper already has, with exactly ONE reader, inside the moved region
  — predicted **0**, failed **0**.
- **WHAT DID NOT WORK / WHAT COST TIME.** (i) The verifier's stripper positive control was
  written as `"tslib" not in <line>` and fired FALSELY: the stripper had correctly blanked
  the string but `tslibNames.add` keeps the bare word as an IDENTIFIER — the control must
  name the QUOTED form. Same class as rounds 815/816: an instrument whose input is not
  precisely bounded measures something else. (ii) A KDoc written for the createRequire
  helper contained `node*/nodenext` — and `*/` TERMINATES a KDoc; caught before the build,
  but it is exactly the CLAUDE.md trap. (iii) The `s9` createRequire probe needed `.mts` +
  `nodenext`; a `package.json` `"type": "module"` with a plain `.ts` file emitted CommonJS
  and reached the branch not at all. (iv) The first comment-lift probe put an async
  FunctionDeclaration first, which hits the `onlyAwaiter && firstOrigStmt is
  FunctionDeclaration` exception and does NOT lift — a pin written from it would have been
  blind. All four were caught by probing the REAL HEAD binary in a 1.3-second scratch
  project before a single pin was written, which is the cheapest step in this recipe.
- **SEAMS NOT DISCRIMINATED, stated rather than glossed.** `tfInjectTslibImport` takes
  `withHelpers` (post deep-metadata hoist) and could be handed `helpersAndTransformed`
  instead; no pin separates them, because reaching the difference needs
  `experimentalDecorators` + `emitDecoratorMetadata` + a deep qualified type AND
  `importHelpers` on an ESM module at once, and no corpus shape in reach combines them.
  Likewise `tfWrapNoLibMetadataArgs` / `tfInjectCreateRequireHeader` were not shown to
  commute or not.
- **GATE.** Suite **13,725 -> 13,739 / 0 failures / 3 skipped** (+14: 9 `TransformSplitTest`
  + 3 `HugeMethodLimitTest` size pins + 2 census-ratchet pins), python XML parser, whole
  results dir wiped first. 8-profile grid diffed set-for-set BOTH directions against a
  purpose-built pre-split binary, both arms on the IDENTICAL direct `java` command line
  with absolute class dirs, class dirs confirmed to differ (0 vs 7 `tf*` helpers), every
  capture checked non-empty and free of an `and N more error` marker —
  **46/46/46/46/46/46/46/94, 0 added and 0 removed on all eight**. `--partitionCheck 2`
  **EQUIVALENT — 46**. `cost_gate.py` **all 20 counters +0.00%**.
  `compileKotlinJvm compileTestKotlinJvm --rerun-tasks`: **0 `w:` and 0 `e:`**.
  Full derivation: `docs/perf/setup-phase-and-huge-methods.md` §§ 21-22.
- **FOR THE NEXT AGENT. (JIT.1) is at FOUR, the ratchet is at 4, and the list has changed
  shape:** `Transformer.transformToCommonJS` **28,991**, `Transformer.transformClassBody`
  **16,233**, `Checker.tryInferSingleTypeParamFromArgs` **11,930**, and
  `Checker.<clinit>` **10,339** — which is NOT `access$checkBigintPropertyNames$emit`, that
  entry was the phantom above. `<clinit>` is a shape nobody here has split: a static
  initializer holding the class's object-level constants, which can only shrink by moving
  those initializers into helper methods it calls, and which no `--noEmit` A/B can price
  either (it runs once, at class load). The two Transformer methods are the same emit-path
  recipe this round used, at 2-3x the size; `tryInferSingleTypeParamFromArgs` is still the
  only one needing a scripted data-flow answer rather than a contiguity argument.
  **Tighten the ratchet by one as each lands — the suite test fails on a stale entry, so
  it will insist.**


**Round 816 (2026-08-03) — (JIT.1)(e) LANDED FOR `TypeScriptCompiler.compileParsedCore`:
21,535 BYTECODES -> AN ENTRY AT 293 PLUS TEN HELPERS. CENSUS 6 -> 5 — AND IT IS THE FIRST
SPLIT IN THIS FAMILY WHOSE PARTS SUM TO **LESS** THAN THE MONOLITH.**

- **The census was re-measured at HEAD on a rebuilt binary first** (law 1): **6** over the
  limit, `compileParsedCore` **21,535** — the round-815 handoff reproduced exactly. The
  after-number was measured the same way on the binary built from the split source: **5**.
- **WHY THIS TARGET.** The handoff named it the cheapest next and it was, but for a reason
  worth stating: its shape is two MUTUALLY EXCLUSIVE arms behind one dispatch plus a
  straight prologue, so the two arms move WHOLE, all four whole-function `return`s go with
  them, and **no helper needs a return signal at all** (round 813's property). The
  Transformer three are on the EMIT path and `tryInferSingleTypeParamFromArgs` still needs
  the data-flow answer; neither was cheaper.
- **THE SPLIT.** Entry **293** plus `cpcCompileMultiFile` **5,111**,
  `cpcCheckEmitOptionConflicts` **3,012**, `cpcScanFiles` **2,651**,
  `cpcCheckModuleAndLibOptions` **1,894**, `cpcCheckProjectShapeOptions` **1,584**,
  `cpcBindAndCheck` **1,537**, `cpcCompileSingleFile` **1,488**, `cpcTransformAndEmit`
  **1,125**, `cpcCheckDeprecatedOptions` **1,004**, `cpcRequireOnlyOrphans` **595**.
- **THE NUMBER THAT REFRAMES THE TARGET: 20,294 AGAINST 21,535 — THE SPLIT REMOVED 1,241
  BYTECODES.** Eleven rounds have added between 10 and 99; this one SUBTRACTED, and the
  mechanism is the round's most transferable find. The monolith's first instruction is
  `new kotlin/jvm/internal/Ref$ObjectRef`: `var options` is captured by the worker lambdas
  of the `ParallelCheckMode.workers > 1` branch — a list of function VALUES, the one
  closure form Kotlin cannot inline away — so it was BOXED, and the disassembly carries
  **168 `getfield … Ref$ObjectRef.element` + 168 `checkcast CompilerOptions`**, ~6 bytes
  each. **~1,008 bytecodes, 4.7% of the method, existed only because one `var` was
  captured**, paid at reads spread over 1,780 lines. As a helper PARAMETER it is an
  immutable local again and the boxing is GONE (`Ref$ObjectRef` count across all eleven
  functions: **0**). Consequence: "the parts must sum to at least the monolith" is NOT a
  law, and `javap -c … | grep -c ObjectRef` is a one-line test worth running before
  hunting for size in what a function DOES.
- **THE BOUNDARIES WERE MEASURED, NOT ESTIMATED — new instrument,
  `scripts/method_bytes_by_line.py`.** It attributes every one of the 21,535 bytecodes to
  a source line through javap's `LineNumberTable`, so each region's size was known BEFORE
  the edit (rounds 807 and 810 each landed one extraction short/over and had to reason
  after the fact). Predicted 1,319 / 3,153 / 2,201 / 1,647 / 1,631 / 5,207 / 2,824 / 1,589
  / 1,180 / 729; built 4-18% smaller across the board, which is the boxing above.
  **The tool also answers a question nobody had asked: 4,938 of the 21,535 bytecodes —
  22.9% — are INLINED stdlib bodies** (`map`/`filter`/`let`/`run`/…), which carry
  SYNTHETIC line numbers past the end of the file and are charged here to their call site.
- **THE FREQUENCY ARGUMENT, HONESTLY: IRRELEVANT.** `compileParsedCore` runs ONCE per
  compile — once per corpus test, once per project build, once per `--watch` recheck. Its
  interpreted cost is one pass over ~21 k bytecodes plus 78 iterations of the file-scan
  loop on the compiler profile: microseconds. **No wall A/B was run and none should be**;
  this lands for the threshold and for (f). The cut criterion is SIZE, with one structural
  rule: the two dispatch arms are mutually exclusive, so a compile pays exactly one.
- **THE 18-PARAMETER PROBLEM AND THE RULE IT PRODUCES.** `cpcScanFiles`'s free-variable set
  is 18 names, three of them `MutableList<Pair<String, String>>` and four
  `MutableSet<String>`. **A positional call could permute two same-typed containers and
  still type-check** — silent, total, and invisible to the compiler. So every call site in
  this split passes every argument BY NAME, and `cpc_split_verify.py` check 5 asserts the
  named set equals the parameter set. The free-variable computation is scope-aware this
  time (a brace-stack simulation), because a textual matcher cannot tell `val file` in the
  single-file arm from `for (file in …)` in the multi-file one.
- **EQUIVALENCE IS A MEASUREMENT (round 805's five checks;
  `scripts/cpc_split_{analyze,apply,verify}.py`), all green:** ten contiguous in-order
  regions re-extracted from the NEW file and compared VERBATIM against HEAD (four at
  dedent 0, six at dedent 4); the new file **RECONSTRUCTED** from HEAD byte for byte —
  **287,974 chars**; the accounting is a PARTITION — every one of the 1,780 body lines
  claimed exactly once (33 kept, **1,740 moved**, 3 separator blanks dropped and asserted
  blank, 4 structural lines replaced by the dispatch); control-flow tokens enumerated on
  both sides and **bounded to the changed region on both** — `return` 30 -> 34 (+4, all
  four named), `continue` 22 -> 22, `break` 1 -> 1; free variables per region equal to the
  helper signatures.
- **PINS VALIDATED ON THE UNSPLIT BINARY FIRST — 58 ran, exactly 3 failed, and they are
  the three new SIZE pins**, which must fail there. So all 16 behavioural pins describe
  HEAD, not the split.
- **DISCRIMINATION 3 OF 3, each mistake alone on its own build, pin count confirmed —
  PLUS A NEGATIVE CONTROL.** Dropping the `cpcCheckModuleAndLibOptions` call fails **3**
  (its two arm pins plus the four-code pin); handing the multi-file arm `baseOptions`
  instead of `options` fails **exactly 1**, the `options` seam (a `package.json`
  `"type": "module"` program stops emitting ESM); a POSITIONAL `cpcScanFiles` call with
  `sourceEchoes`/`jsonOutputs` swapped fails **2** — and that is the mistake **no compiler
  can catch**. **The negative control — the project-shape run consulted FIRST — fails 0**,
  as predicted from the structural property (each run only appends to `diagnostics`, none
  reads it back, no two emit the same code).
- **WHAT DID NOT WORK.** (1) The verifier's first run reported ONE false failure —
  `cpcCompileMultiFile` read as 1,018 lines against a 422-line body — because its brace
  matcher ran on the RAW text and counted `{` inside template expressions and regex
  literals; it runs on the stripped text now. **Same class as round 815's two false
  failures: an instrument that does not bound or sanitize its input measures something
  else.** (2) The named-argument check then failed twice more, both times on itself: it
  counted the `val checker =` on a call's head line as a named argument, and after that
  fix it skipped the head line of the two ONE-LINE calls. (3) Nothing else — no build died
  this round; the daemons were stopped before each of the ten.
- **GATE.** Suite **13,706 -> 13,725 / 0 failures / 3 skipped** (+19: 16 `CpcSplitTest` +
  3 `HugeMethodLimitTest`), python XML parser, whole results dir wiped first. 8-profile
  grid diffed set-for-set BOTH directions against a purpose-built pre-split binary
  (`build/r816-pre`), both arms on the IDENTICAL direct `java` command line with absolute
  class dirs, class dirs confirmed to differ (0 vs 7 `cpcCompileMultiFile` entries) —
  **46/46/46/46/46/46/46/94, 0 added and 0 removed on all eight**. `--partitionCheck 2`
  **EQUIVALENT — 46**. `cost_gate.py` **all 20 counters +0.00%**.
  `compileKotlinJvm compileTestKotlinJvm --rerun-tasks`: **0 `w:` and 0 `e:`**.
  Full derivation: `docs/perf/setup-phase-and-huge-methods.md` § 20.
- **FOR THE NEXT AGENT.** (JIT.1) is at **5 over the limit**, and four of the five are
  either the Transformer or the hard one: `Transformer.transformToCommonJS` **28,991**,
  `Transformer.transformClassBody` **16,233**,
  `Checker.tryInferSingleTypeParamFromArgs` **11,930**,
  `access$checkBigintPropertyNames$emit` **10,339**, `Transformer.transform` **8,934**.
  **The Transformer three are on the EMIT path — every A/B in this arc is `--noEmit` and
  blind to them, so their gate is the corpus suite's emit baselines, which is a real gate
  and should be said so when they land.** **(f) is still one sub-item away** and is now the
  cheapest thing in the queue: `python3 scripts/huge_methods.py --fail-over 5` today,
  tightening as the last five go.

**Round 815 (2026-08-03) — (JIT.1)(e) LANDED FOR `applyDirective`: 13,694 BYTECODES ->
AN ENTRY AT 89 PLUS FOUR RUNS. CENSUS 7 -> 6, THE FIRST TARGET OUTSIDE `Checker`, AND THE
ONE WHOSE SIZE HAS THE LEAST TO DO WITH WHAT IT DOES — PLUS THE FAMILY'S FIRST NEGATIVE
CONTROL.**

- **The census was re-measured at HEAD on a rebuilt binary first** (law 1): **7** over the
  limit, `applyDirective` **13,694** — the round-814 handoff reproduced exactly. The
  after-number was measured the same way on the binary built from the split source: **6**.
- **THE SPLIT.** Entry **89** plus `applyDirectiveArms1` **2,240** (15 arms),
  `applyDirectiveArms2` **4,592** (22), `applyDirectiveArms3` **3,164** (22),
  `applyDirectiveArms4` **3,708** (26). The four sum to **13,704 against 13,694** — with
  the entry the split ADDED **99** bytecodes, an ELEVENTH confirmation that a bytecode
  count is a THRESHOLD predicate and not a cost model.
- **WHY CHOSEN: TRACTABILITY, STATED AS A REASON.** Of the three candidates the handoff
  named, `tryInferSingleTypeParamFromArgs` needs a data-flow answer and the bigint walker
  needs an anonymous object restructured; `applyDirective` is 116 lines of one `when (key)`
  with **no loop, no recursion, one `return`, and exactly one derived local**. It is also
  the one whose correctness reduces to a property a script can ASSERT rather than an
  argument a human has to make.
- **THE NUMBER THAT REFRAMES THE TARGET: ~160 BYTECODES PER ONE-LINE ARM.** Every arm is
  `options.copy(field = …)` on a **~150-field data class**, and Kotlin compiles a
  named-argument `copy` into a `copy$default` CALL SITE carrying the whole argument vector
  plus the default bitmasks. **So the size is the ARM COUNT times the DATA CLASS's FIELD
  COUNT** — this function was over HotSpot's limit while doing almost nothing, and `javap`
  rather than line count is the instrument for the class of `when`-over-a-wide-data-class
  that a reader would never suspect. Consequence for the next agent: **adding directives
  walks the limit back up at ~160 bytecodes each** (headroom per run 5,760 / 3,408 / 4,836
  / 4,292 = roughly 21-36 more arms apiece).
- **THE FREQUENCY ARGUMENT, HONESTLY: IRRELEVANT.** `applyDirective` runs once per
  directive per file. It is on no hot path, **no wall A/B was run and none should be**;
  this lands for the threshold and for sub-item (f). Rounds 807-811 chose what stays inline
  from a measured partition, 812-813 from a structural guard, 814 said its criterion was
  size alone — here the criterion is size AND the arms are interchangeable, so the
  partition is simply balanced by line count.
- **THE SEAM ANALYSIS IS THE ROUND'S REAL PRODUCT, BECAUSE THIS SHAPE HAS A SEAM IT
  PROVABLY DOES NOT HAVE.** The 85 arm keys are **pairwise DISTINCT** (the analyzer asserts
  it) and **no arm ever evaluates to `null`**, so a single `when` and a `?:`-chain over a
  partition select the same arm WHATEVER ORDER the runs are consulted in. The mistakes the
  shape does admit are exactly three: dropping a run, writing a run's fallthrough as
  `options` instead of `null` (which silently swallows every LATER run), and recomputing
  `boolValue` — the only value that crosses a boundary at all.
- **DISCRIMINATION 3 OF 3, EACH MISTAKE ALONE ON ITS OWN BUILD, PIN COUNT CONFIRMED — PLUS
  A NEGATIVE CONTROL.** Dropping `applyDirectiveArms3` fails **5** (the coverage pin, all
  three run-3 arm pins, and the `boolValue` pin, which names `checkjs`); making run 1's
  fallthrough `options` fails **12** (every run-2/3/4 pin plus the end-to-end directive
  pin, while run 1's own arms stay green — correctly); dropping the entry's `.lowercase()`
  fails **exactly 1**, the `boolValue` seam pin. **And the fourth build moved the
  run-1/run-2 boundary by one arm and failed 0** — the claim "the partition position is
  unobservable" was tested rather than asserted. **The transferable rule: when a split's
  correctness rests on a structural property, ablate that property's CONSEQUENCE and show
  the PREDICTED zero; a zero you predicted is evidence, a zero you discovered is a blind
  pin.**
- **EQUIVALENCE IS A MEASUREMENT (round 805's five checks;
  `scripts/applydirective_split_{analyze,apply,verify}.py`):** four contiguous in-order runs
  (28/28/28/26 lines) re-extracted from the NEW file and compared verbatim at **dedent 0**
  (each helper is a block body with `return when (key) {`, so no arm line is edited at
  all); the new file **RECONSTRUCTED** from HEAD byte for byte — **62,424 chars**;
  accounting closing exactly (110 arm lines moved; entry = signature + `boolValue` + four
  calls + the `?: options` tail + brace); control-flow tokens enumerated on both sides
  (1 `return` in HEAD, 5 in the new tree, 0 `continue`/`break` either side); and free
  variables re-asserted per run — **every run must read all four parameters**, because an
  unused parameter is a `w:` in this warning-clean build, which is a real constraint on
  how the arms may be partitioned.
- **PINS VALIDATED ON THE UNSPLIT BINARY FIRST — 16 ran, 0 failed.** The coverage pin
  carries its own positive control (the key list is asserted to be 85 long and
  duplicate-free), so an empty `unhandled` cannot be the round-814 empty-list kind of
  green.
- **WHAT DID NOT WORK.** (1) The verifier's first run reported two FALSE failures, both in
  the instrument: the reconstruction check compared a line LIST in which the doc comment is
  one element containing newlines against a list read from disk where it is six lines (fixed
  by comparing joined TEXT), and the control-flow census counted from the entry to EOF,
  picking up every later function in the file (fixed by bounding it at the last helper's
  closing brace). Both are the same class of error: **a whole-file check that does not
  bound its region measures the file, not the change.** (2) Nothing else failed — no build
  died this round, because the daemons were stopped before each one.
- **GATE.** Suite **13,687 -> 13,706 / 0 failures / 3 skipped** (+19: 16
  `ApplyDirectiveSplitTest` + 3 `HugeMethodLimitTest`), python XML parser, whole results
  dir wiped first. 8-profile grid diffed set-for-set BOTH directions against a
  purpose-built pre-split binary, **both arms on the IDENTICAL direct `java` command line
  with absolute class dirs** (no bench script involved, so round 811's `NOEMIT_ARGS`
  truncation cannot arise) and the differ refusing any empty, 0-line or `and N more error`
  capture; class dirs confirmed to differ (4 `applyDirectiveArms` entries vs 0) —
  **46/46/46/46/46/46/46/94, 0 added and 0 removed on all eight**. `--partitionCheck 2`
  **EQUIVALENT — 46**. `cost_gate.py` **every counter +0.00%**. No `w:` and no `e:` lines.
  Full derivation: `docs/perf/setup-phase-and-huge-methods.md` § 19.
- **FOR THE NEXT AGENT.** (JIT.1) is at **6 over the limit and NONE of them is in
  `Checker`'s hot path**: `Transformer.transformToCommonJS` **28,991**,
  `TypeScriptCompiler.compileParsedCore` **21,535**, `Transformer.transformClassBody`
  **16,233**, `Checker.tryInferSingleTypeParamFromArgs` **11,930**,
  `access$checkBigintPropertyNames$emit` **10,339**, `Transformer.transform` **8,934**.
  Cheapest next is almost certainly `compileParsedCore` (a straight pipeline of phases, the
  same shape as round 814's constructor but WITH returns, so its helpers need signals);
  `tryInferSingleTypeParamFromArgs` is still the one that needs a scripted data-flow answer
  rather than a contiguity argument. **(f) — wiring `huge_methods.py --fail-over 0` into
  the round gate — is one sub-item away.**


**Round 814 (2026-08-03) — (JIT.1)(d) LANDED FOR THE `Checker` CONSTRUCTOR: 11,298
BYTECODES -> AN ENTRY AT 5,538 PLUS TEN HELPERS. CENSUS 8 -> 7, AND THE `Checker` LIST IS
DOWN TO ONE PLUS ONE ODD ONE. THE FIRST TARGET WHOSE FREQUENCY ARGUMENT IS DEGENERATE —
AND MOST OF ITS RESIDUE IS NOT THE PASS SEQUENCE AT ALL.**

- **The census was re-measured at HEAD on a rebuilt binary first** (law 1): **8** over the
  limit, `Checker.<init>` **11,298** — the round-813 handoff reproduced exactly. The
  after-number was measured the same way on the binary built from the split source: **7**.
- **THE SPLIT.** Entry **5,538** plus `initSetupPasses` **185**,
  `initDeclarationOnlyPasses` **12** and `initCheckPasses1..8` **415 / 792 / 752 / 786 /
  804 / 799 / 661 / 588**. The eleven sum to **11,332 against 11,298** — the split ADDED
  34 bytecodes (the ten call sites), an ELEVENTH confirmation that a bytecode count is a
  THRESHOLD predicate and not a cost model.
- **THE FREQUENCY ARGUMENT IS DEGENERATE, AND SAYING SO IS THE POINT.** Rounds 807-811
  chose what stays in the entry from a MEASURED partition, 812/813 from a structural
  GUARD. **A constructor runs exactly once per compile and every input pays all of it** —
  nothing here is cold, nothing can be "moved because it is rarely reached", and the only
  cut criterion left is SIZE. The eight checking runs are contiguous slices of ~67 code
  lines each; the handoff's suspicion that this target "may have nothing to ablate" was
  half right, and the honest answer is in the seams (below), not in a frequency story.
- **THE NUMBER THAT REFRAMES THE TARGET: 5,538 OF THE ENTRY IS NOT THE PASS SEQUENCE.**
  It is the class's **494 property initializers**, which a JVM constructor cannot delegate
  to a helper — `private val x = …` compiles into `<init>` by definition. So the whole
  ~437-dispatch sequence is worth only ~5,760 bytecodes (~13 each; the `pass(…)` lambdas
  are separate methods because `pass` is deliberately non-inline), and `<init>` was over
  the limit chiefly because the two halves happened to sum past it. **Consequence for the
  next agent: the entry's remaining headroom, 2,462, is consumed by ADDING FIELDS, not by
  adding passes** — a new `pass(…)` line lands in a helper, a new `private val` lands in
  `<init>`. At ~11 bytecodes per field that is room for ~200 more.
- **AND THEREFORE THE PRIZE IS BOUNDED TO ZERO WALL, WHICH IS STATED RATHER THAN HOPED.**
  The constructor runs ONCE, and the loops that would have wanted OSR all live inside
  `pass` lambdas — i.e. in their own methods, JIT-eligible all along. **No wall A/B was
  run and none should be**: this lands for the threshold and for sub-item (f).
- **THE SHAPE: NO SIGNALS, NO PARAMETERS, NINE DEDENTS OF ZERO.** 443 body-level
  statements, **zero** `return`/`break`/`continue` at body level (a constructor cannot
  express one), no loops at body level, and exactly **two** body-level locals —
  `preAugmentationGlobalsKeys` (inside the setup prologue) and
  `shouldCheckDefiniteAssignment` (declared at the top of run 1 and read 400 lines later,
  still inside run 1, **which is why the first boundary sits where it does**). So
  cross-boundary values NONE and every helper is parameterless. Nine of the ten regions
  move at dedent 0, because the `if (!declarationOnly) { … }` body is written at exactly a
  private method body's indentation.
- **EQUIVALENCE IS A MEASUREMENT (round 805's five checks, all green;
  `scripts/init_split_{analyze,apply,verify}.py`):** ten contiguous in-order runs
  (133/29/572/218/231/241/229/181/147/80 lines) re-extracted from the NEW file and
  compared verbatim against HEAD; the `init` block **reconstructed** from HEAD —
  **IDENTICAL at 43 lines**; accounting closing exactly (2,094 = 33 kept + 2,061 moved;
  entry 43 = 33 + 10 call lines); control-flow tokens enumerated on both sides (15/15, all
  inside lambdas); free variables per region re-asserted as "every signature is `()`" PLUS
  "the ten call sites are in the regions' source order" — which is the property this
  target's correctness actually reduces to.
- **PINS VALIDATED ON THE UNSPLIT BINARY FIRST — 13 ran, 0 failed.**
- **DISCRIMINATION 3 OF 3, each mistake alone on its own build, pin count confirmed every
  time.** Moving `initCheckPasses8()` to the HEAD of the block fails **exactly 1** pin —
  its ORDER seam; hoisting `initCheckPasses1()` OUT of `if (!declarationOnly)` fails
  **2**; deleting the `initCheckPasses5()` call fails **exactly 1**, its own arm pin.
  **The ORDER seam is the transferable find: `checkBuiltinIterator`, the first pass of run
  8, RETRACTS a TS2339 that `checkSpine` (run 1) emitted** — a wipe-and-repin corpus
  walker is an order sensor for free, and R8 is full of them. The guard ablation's second
  failure is a knock-on and is recorded as one (with run 1 also running, the TS2304 is
  reported twice, so the arm pin's `count == 1` fails beside the seam pin).
- **WHAT DID NOT WORK.** (1) The first run-8 arm pin, `applyDomLibSuggestionRewrite`, is
  UNREACHABLE from a hand-written source: our checker emits **no diagnostic at all** for a
  property read on a member-less user interface (four shapes probed — `Element`, `Node`,
  `HTMLDivElement`, with and without `@lib`), so the TS2339 it rewrites never exists. It
  failed on the UNSPLIT binary — the cheap order working — and **the seam pin written
  beside it passed VACUOUSLY on an empty diagnostic list**, which is exactly what a
  positive control exists to catch; the replacement pair carries one. (2) The round's
  first `compileKotlinJvm` died after 11m21s with BUILD.1's `Not enough memory` (a stale
  4.3 GB idle Kotlin daemon), and the third ablation build died after 4m58s with
  `GC overhead limit exceeded`, tell `PINS RAN 0`; both recovered with `./gradlew --stop`
  plus a graceful bracket-pattern `pkill`, after which the same builds took 2m23s and
  1m30s. (3) A foreground `./gradlew` is not viable here at all — the tool's 2-minute
  ceiling kills the shell mid-task.
- **GATE.** Suite **13,671 -> 13,687 / 0 failures / 3 skipped** (+16: 13 `CtorSplitTest` +
  3 `HugeMethodLimitTest`), python XML parser, whole results dir wiped first. 8-profile
  grid diffed set-for-set BOTH directions against a purpose-built pre-split binary, class
  dirs confirmed to differ (447 `init*Passes` entries vs 0), every capture non-empty and
  non-truncated — **46/46/46/46/46/46/46/94, 0 added and 0 removed on all eight**.
  `--partitionCheck 2` **EQUIVALENT — 46**. `cost_gate.py` **all 20 counters +0.00%**. No
  `w:` and no `e:` lines. Full derivation:
  `docs/perf/setup-phase-and-huge-methods.md` § 18.
- **ONE FIX TO THE INSTRUMENT, worth knowing:** `HugeMethodLimitTest.methodCodeSizes` was
  last-wins by method NAME, and Kotlin emits a **72-byte synthetic `<init>` bridge** for
  default arguments beside the real constructor — so the map reported `<init>` as 72 and
  the limit pin would have passed vacuously. It now keeps the LARGEST entry per name,
  which also closes the same hole for overloads.
- **FOR THE NEXT AGENT.** (JIT.1) is at **7 over the limit** and the `Checker` list is
  down to **one plus one odd one**: `tryInferSingleTypeParamFromArgs` **11,930** — the
  hard one, two 300-400-line `for (i in params.indices)` bodies plus a 132-line constraint
  block with mutable locals (`candidates`, `tpSawAnyArg`) crossing every boundary, so it
  is the first target in this family that needs a real data-flow answer rather than a
  contiguity argument — and `access$checkBigintPropertyNames$emit` **10,339**, which is
  NOT the 8-line local `emit` its name suggests but the whole `checkBigintPropertyNames`
  per-file body the anonymous walker object closes over, so it is split by restructuring
  that walker. The non-`Checker` tail is unchanged: `Transformer.transformToCommonJS`
  **28,991**, `TypeScriptCompiler.compileParsedCore` **21,535**,
  `Transformer.transformClassBody` **16,233**, `CompilerOptionsKt.applyDirective`
  **13,694**, `Transformer.transform` **8,934** — the three Transformer ones are sub-item
  **(e)**, sized at 0.14-0.25%. **(f) — wiring `huge_methods.py --fail-over 0` into the
  round gate — becomes runnable once (d)/(e) land.**


**Round 813 (2026-08-03) — (JIT.1)(d) LANDED FOR `checkIndexSigInStatement`: 10,928
BYTECODES -> AN ENTRY AT 1,010 PLUS SEVEN `cis*` HELPERS. CENSUS 9 -> 8, AND THE `Checker`
LIST IS DOWN TO TWO PLUS ONE ODD ONE. FOUR SEAMS, FOUR DISCRIMINATED — BUT THE FIRST ONE
ONLY AFTER A PURPOSE-BUILT RETRY THAT REPLACED A BLIND PIN.**

- **The census was re-measured at HEAD on a rebuilt binary first** (law 1): **9** over the
  limit, `checkIndexSigInStatement` **10,928** — the round-812 handoff reproduced exactly.
  The after-number was measured the same way on the binary built from the split source: **8**.
- **THE SPLIT.** Entry **1,010** plus `cisCheckNamedInterfaceIndexValueConflict` **2,680**,
  `cisCheckAnonIndexValueConflict` **1,684**, `cisCheckNumericMethodsVsNumberIndex` **1,623**,
  `cisFindStringIndexSig` **1,504**, `cisCheckPropsVsStringIndex` **1,021**,
  `cisCheckMethodsVsPrimitiveStringIndex` **822** and
  `cisCheckNumericNamePropsVsNumberIndex` **359**. The eight sum to **10,703 against
  10,928** — a TENTH confirmation that a bytecode count is a THRESHOLD predicate and not a
  cost model. Headroom **6,990**, round 810's lesson with room to spare.
- **WHAT STAYS IN THE ENTRY IS WHAT EVERY INPUT PAYS — a GUARD again, not a probe (this
  target has no partition either), but a sharper one than round 812's because the guards
  are statement-KIND tests.** The entry keeps the `TypeAliasDeclaration` and
  `VariableStatement` branches (both `return`), the `when` whose `else` arm **`return`s for
  every statement kind that is not a class, an interface or a module**, the
  `ModuleDeclaration` recursion, the number- and string-index-signature lookups, the two
  guards and the "no usable string index type" early return — i.e. exactly what a class or
  interface WITHOUT a string index signature pays. Every moved region is behind one of
  those. As in round 812 this BOUNDS the moved population rather than pricing it, and that
  is said in the doc rather than implied.
- **THE SHAPE: NO RETURN SIGNALS AT ALL.** All 6 bare `return`s of the 543-line body are in
  the kept dispatch head; the only `return` tokens inside any moved region are three in a
  local `fun` that moves whole, and all 32 `continue`s are inside loops their own region
  owns (brace-matching census, not indentation). So unlike (f)/(g)/(h)/(c) no helper needs
  a `Boolean` protocol. **The ONE cross-boundary value** is the string index signature —
  HEAD seeded a `var` from the type's OWN members and let a base-class walk overwrite it —
  and `cisFindStringIndexSig` RETURNS it (round 804's rule; a field would need round 791's
  save/restore, and this function recurses through `ModuleDeclaration`).
- **ONE STRUCTURAL DETAIL WORTH COPYING.** Two regions hold a `when (stmt)` that is
  exhaustive only because of an enclosing `if (stmt is ClassDeclaration || stmt is
  InterfaceDeclaration)`. Moving the `if` STATEMENT (condition included) instead of its
  body keeps both verbatim and needs no invented `else` arm — and costs nothing, because
  at that point in the entry the condition is already true by construction.
- **EQUIVALENCE IS A MEASUREMENT (round 805's five checks, all green;
  `scripts/indexsig_split_{analyze,apply,verify}.py`):** seven contiguous in-order runs
  (38/32/57/126/46/51/95 lines) re-extracted from the NEW file and compared verbatim
  against HEAD; the entry **reconstructed** from HEAD — **IDENTICAL at 105 lines**;
  accounting closing exactly (543 = 98 kept + 445 moved; entry 105 = 98 + 7 call lines);
  every `return` and `continue` enumerated (6/32 on both sides); free variables per region,
  re-asserted against the signatures and the call sites. **A free-variable scan must not
  count `.members` as a read of the local `members`** — an unqualified `\bmembers\b` claims
  three regions need a parameter they never use, and an unused parameter is a warning here.
- **PINS VALIDATED ON THE UNSPLIT BINARY FIRST — 16 ran, 0 failed — which is the cheap
  order and caught TWO WRONG PINS before any code moved.** The type-alias branch `return`s
  BEFORE the string-index machinery, so `type T = { [s: string]: number; p: string }`
  reports no TS2411 at all (its own product is 17.159's TS1337, which needs the alias's
  type-parameter names); and TS2374 fires once per duplicate SIGNATURE, not once per type.
- **DISCRIMINATION 4 OF 4, each mistake alone on its own build, control first (49 pins ran,
  0 failed), every run's pin count confirmed.** `cisFindStringIndexSig` returning only the
  OWN signature -> **1** pin; the entry passing `null` for `numberIndexSig` -> **exactly 1**;
  passing `false` for `stringIndexTypeIsPrimitive` -> **2**; dropping the entry's
  `if (stringIndexTypeIsPrimitive)` guard -> **2**. The last two fail through round 812's
  mechanism unchanged — the mistake ADDS a diagnostic, so what sees it is a `none { … }`
  assertion and a `count == 1` assertion, never a count pin on the arm's own code.
- **THE ROUND'S TRANSFERABLE RESULT: A ZERO WAS A BLIND PIN, NOT A REDUNDANT GUARD, AND THE
  DIFF FOUND THE SHAPE.** The seam pin first written for the returned signature (an
  interface extending a class with `[s: string]: number`) stayed GREEN on the ablated
  binary — a sibling pass reports the same TS2411 for a PRIMITIVE inherited index type.
  Instead of recording the seam undiscriminated, the ablated binary was DIFFED against the
  committed one over eight inherited-index shapes; **exactly one line differs** — a method
  checked against an inherited CALLABLE string index value type. That is now the seam pin
  (it fails on the ablated binary and on nothing else, 1 of 17) and the old pin is renamed
  as an arm pin carrying a comment about what it cannot see. Rounds 810/811 recorded zeros
  after retries that failed; this is the first retry in the family that SUCCEEDED, and the
  instrument that made it cheap was a whole-file differential rather than a guessed shape.
- **WHAT DID NOT WORK.** (1) The blind seam pin above. (2) One ablation build died with the
  Kotlin daemon's `GC overhead limit exceeded` after 5m51s, whose only tell is
  `pins ran = 0`; a plain rebuild succeeded in 2m22s and the ablation then reported
  normally — round 808's rule again. (3) A first probe run reported the ablated arm as
  producing NOTHING, which reads exactly like a crashed binary: the two arms' classpaths
  were one absolute and one RELATIVE, and the probe `cd`s into the scratch project.
- **GATE.** Suite **13,651 -> 13,671 / 0 failures / 3 skipped** (+20: 17 `CisSplitTest` +
  3 `HugeMethodLimitTest`), python XML parser, whole results dir wiped first. 8-profile
  grid diffed set-for-set BOTH directions against a purpose-built pre-split binary, class
  dirs confirmed to differ (15 `cis*` entries vs 0), every capture non-empty and
  non-truncated — **46/46/46/46/46/46/46/94, 0 added and 0 removed on all eight**.
  `--partitionCheck 2` **EQUIVALENT — 46**. `cost_gate.py` **all 20 counters +0.00%**. No
  `w:` and no `e:` lines in any compile. **No wall A/B, deliberately** — the family is
  bounded four times over. Full derivation:
  `docs/perf/setup-phase-and-huge-methods.md` § 17.
- **FOR THE NEXT AGENT.** (JIT.1) is at **8 over the limit and the `Checker` list is down
  to two plus one odd one**: `tryInferSingleTypeParamFromArgs` **11,930** (the hard one —
  two 300-400 line `for (i in params.indices)` bodies plus a 132-line constraint block,
  with mutable locals `candidates`/`tpSawAnyArg` crossing every boundary), the `Checker`
  **constructor 11,298** (contiguous runs of ~417 `pass("init:…")` dispatches, no returns,
  no loops — moving statements OUT of `init` into a private method preserves order and is
  safe, ADDING a field is not), and `access$checkBigintPropertyNames$emit` **10,339**,
  which is NOT the 8-line local `emit` its name suggests but the whole
  `checkBigintPropertyNames` per-file body the anonymous walker object closes over, so it
  is split by restructuring that walker. **Neither of the two has a partition** (checked
  round 812). The constructor is the cheapest remaining and its seams are unusual: with no
  returns and no cross-boundary locals there may be nothing to ablate, in which case say so
  and let the suite be the pin. The non-`Checker` tail is unchanged:
  `Transformer.transformToCommonJS` **28,991**, `TypeScriptCompiler.compileParsedCore`
  **21,535**, `Transformer.transformClassBody` **16,233**, `CompilerOptionsKt.applyDirective`
  **13,694**, `Transformer.transform` **8,934** — the three Transformer ones are sub-item
  **(e)**, sized at 0.14-0.25%. **(f) — wiring `huge_methods.py --fail-over 0` into the
  round gate — becomes runnable once (d)/(e) land.**


**Round 812 (2026-08-03) — (JIT.1)(d) LANDED FOR `checkDuplicateDeclarations`: 12,935
BYTECODES -> AN ENTRY AT 2,801 PLUS FIVE `cdd*` HELPERS. CENSUS 10 -> 9. FIRST TARGET IN
THE ARC WITH NO COMMITTED PARTITION OF ANY KIND — AND THE FREQUENCY ARGUMENT CAME FROM A
GUARD RATHER THAN A PROBE, WHICH IS WEAKER AND IS SAID SO.**

- **The census was re-measured at HEAD on a rebuilt binary first** (law 1): **10** over the
  limit, `checkDuplicateDeclarations` **12,935** — the round-811 handoff reproduced exactly.
  The after-number was measured the same way on the binary built from the split source: **9**.
- **THE HANDOFF'S ADVICE WAS FOLLOWED AND CAME BACK EMPTY.** Round 811 said to grep for an
  existing probe object before budgeting a derivation. Done for all four remaining `Checker`
  targets: **none of them has a `*Sections` object, a `PassTiming` row or any probe** —
  `checkDuplicateDeclarations`, `tryInferSingleTypeParamFromArgs`, `checkIndexSigInStatement`
  and `checkBigintPropertyNames` are all probe-free. So the boundaries were derived from the
  shape, and the check that costs 60 seconds is still worth running: it is what made rounds
  808/809/811 cheap, and its answer here is a fact about the remaining tail, not a miss.
- **THE SPLIT.** Entry **2,801** plus `cddCheckImportBindings` **1,108**, `cddCheckMergedEnums`
  **955**, `cddCheckMergedTypeParameters` **2,468**, `cddCheckExportUniformity` **2,101** and
  `cddCheckValueRedeclarations` **3,483**. The six sum to **12,916 against 12,935** — a NINTH
  confirmation that a bytecode count is a THRESHOLD predicate and not a cost model. The entry
  keeps **5,199 bytecodes of headroom**, which is round 810's lesson applied.
- **WHAT STAYS IN THE ENTRY IS WHAT EVERY INPUT PAYS — decided by a GUARD, not a probe.** The
  entry keeps the collection loop over `statements` (the only part that scales with input
  SIZE), the `groupBy`, the `export=` check, the group-loop head including
  **`if (group.size < 2) continue`**, and the three-boolean `isDuplicate` tail. **Every moved
  region sits behind that guard** — behind a name declared at least TWICE in one scope — and
  four of the five behind a further kind predicate. This is a weaker instrument than rounds
  807-811's measured partitions and the doc says so: it BOUNDS the moved population rather
  than pricing it. It is also decisive in a way a cost table is not — no measurement can make
  a moved region run more often than the guard admits.
- **THE SHAPE PROBLEM, AND IT IS NEW: A LOOP BODY INSIDE A LOOP BODY.** Which loop a
  `continue` binds to decides whether it is a region exit (-> `return true`) or ordinary
  control flow (-> untouched), and **indentation is not evidence** — the region's own `for`
  loops are indented exactly like the blocks the outer `continue`s sit in. A brace-matching
  scan seeded from the function start (`scripts/dupdecl_split_analyze.py`) answers it: of the
  23 `continue`s, **7 bind to the group loop and all 7 are in the V region**; the eighth in
  that same region binds to `for (decl in group)` and is left alone. The function has **ZERO
  whole-function `return`s** — every `return` in 872 lines is a `return@` or inside a local
  `fun` — so no region needs an (f)-style RETURN token.
- **TWO THINGS THE ANALYSIS FIXED BEFORE ANY CODE MOVED.** `val hasInterface` looks like part
  of the TS2428 block it sits above and is read by the V region's TS2451 gates 200 lines
  later, so it STAYS in the entry; and `emitted2395` is the ONLY value that crosses a
  boundary and is RETURNED, never stashed in a field (round 804's rule). The local
  `data class DeclInfo` is hoisted to a private nested class because five signatures name it
  — the round's one non-mechanical edit, behaviour-free (it captures nothing and is still
  constructed only by the collection loop).
- **EQUIVALENCE IS A MEASUREMENT (round 805's five checks, all green;
  `scripts/dupdecl_split_{analyze,apply,verify}.py`):** five contiguous in-order runs
  (54/54/150/98/249 lines) re-extracted from the NEW file and compared verbatim against HEAD;
  the entry **reconstructed** from HEAD — **IDENTICAL at 278 lines**; accounting closing
  exactly (872 = 266 kept + 605 moved + 1 hoisted; new entry 278 = 266 + 12); every `return`
  and `continue` enumerated (0 bare returns both sides; HEAD's 23 continues = new 17 - 1
  replay + 7 signals); free variables per region.
- **DISCRIMINATION: 2 OF 3, each mistake ALONE on its own build, control first (45 pins ran,
  0 failed), every run's pin COUNT confirmed.** Discarding `cddCheckValueRedeclarations`'
  `true` fails **1** pin (its seam); ignoring `emitted2395` fails **1** (its seam). **Both
  fail through the same mechanism, and it is the transferable part: the failure mode of a
  dropped signal here is a SUPERSEDED check running anyway, so the seam pin asserts a code
  that must NOT appear** (`none { it.code == 2300 }`) while the arm pin asserts what must.
  A count pin on the arm's OWN code cannot see this class of mistake — both arm pins stayed
  green under both ablations, because the mistake ADDS a diagnostic instead of removing one.
- **THE THIRD SEAM IS PROVABLY UNDISCRIMINABLE, AND THE PROOF IS EXHAUSTIVE RATHER THAN A
  RETRY.** Flipping the block-scoped exit's `return true` to `return false` fails **0** pins,
  as predicted from the guards: the exit is reached only when `allBlockScoped` holds, and the
  only two things that can run after it are the
  `hasBlockScoped && (hasVar || hasFunc || hasClass || hasEnum)` block — whose condition
  `allBlockScoped` negates term by term — and the entry's `isDuplicate` tail, which needs
  `hasClass` or `hasVar`. Both are the complement of the predicate that reached the exit, so
  no input can observe it. Unlike round 811's two zeros, which needed a constructed shape to
  rule out, this one is closed by READING THE GUARDS — so no purpose-built retry was run, and
  the pin written for it is named as an ARM pin per the standing rule.
- **WHAT DID NOT WORK.** (1) The restore rebuild after the last ablation died with
  `Daemon compilation failed` / `Connection to the Kotlin daemon has been unexpectedly lost`,
  then wasted 3m56s in the `Compile without Kotlin daemon` fallback inside a 683 MiB Gradle
  daemon before failing — the round-811 `GC overhead limit exceeded` failure in a second
  costume. Recovery is the same: `./gradlew --stop` plus a graceful bracket-pattern
  `pkill -f 'KotlinCompile[D]aemon'`, then rebuild (2m26s, clean). **The fallback message is
  the tell — a build that says "Using fallback strategy" has already lost and will burn
  minutes proving it.** (2) A stale `build-head.done` marker from an earlier round in the
  shared scratchpad made a still-running build look finished, and the empty class dir read as
  a broken build rather than a running one; every marker this round is `r812-`-prefixed.
  (3) The first enum pin (`enum E { A = 1 } / enum E { B }`) emitted NOTHING — TS2432 needs
  BOTH declarations to omit the first member's initializer — and was caught by probing the
  shapes through the scratch-project CLI before writing a single pin, which is the cheap
  order.
- **GATE.** Suite **13,633 -> 13,651 / 0 failures / 3 skipped** (+18: 15 `CddSplitTest` + 3
  `HugeMethodLimitTest`), python XML parser, whole results dir wiped first. 8-profile grid
  diffed set-for-set BOTH directions against a purpose-built pre-split binary, class dirs
  confirmed to differ (`javap` finds the five `cdd*` helpers in one and none in the other) —
  **46/46/46/46/46/46/46/94, 0 added and 0 removed on all eight**. `--partitionCheck 2`
  **EQUIVALENT — 46**. `cost_gate.py` **all 20 counters +0.00%**. No `w:` and no `e:` lines in
  the compiles that produced the binaries. **No wall A/B, deliberately** — the family is
  bounded four times over. Full derivation:
  `docs/perf/setup-phase-and-huge-methods.md` § 16.
- **FOR THE NEXT AGENT.** (JIT.1) is at **9 over the limit and the `Checker` list is down to
  three**: `tryInferSingleTypeParamFromArgs` **11,930**, the `Checker` constructor **11,298**
  and `checkIndexSigInStatement` **10,928**, plus the odd one out,
  `access$checkBigintPropertyNames$emit` **10,339** — which is NOT the 8-line local `emit` its
  name suggests but the whole `checkBigintPropertyNames` per-file body the anonymous walker
  object closes over, so it is split by restructuring that walker, not by moving `emit`.
  **None of the four has a partition** (checked this round). Two have an obvious shape and no
  derivation cost: `checkIndexSigInStatement` is a straight sequence of self-contained blocks
  with a handful of carried locals (`members`, `numberIndexSig`, the `var stringIndexSig`
  that ONE block mutates — that mutation is the only cross-boundary value); and the
  **constructor's `pass("init:…")` wrappers are natural boundaries** — contiguous runs of
  ~417 dispatches, no returns, no loops (mind the CLAUDE.md rule that a `Checker` field
  declared below the `init` block is null throughout it: moving statements OUT of `init` into
  a private method called from `init` preserves order and is safe, ADDING a field is not).
  `tryInferSingleTypeParamFromArgs` is the hard one: two 300-400 line `for (i in params.indices)`
  bodies plus a 132-line constraint block, with mutable locals (`candidates`, `tpSawAnyArg`)
  crossing every boundary. The non-`Checker` tail is unchanged: `Transformer.transformToCommonJS`
  **28,991**, `TypeScriptCompiler.compileParsedCore` **21,535**, `Transformer.transformClassBody`
  **16,233**, `CompilerOptionsKt.applyDirective` **13,694**, `Transformer.transform` **8,934**
  — the three Transformer ones are sub-item **(e)**, sized at 0.14-0.25%. **(f) — wiring
  `huge_methods.py --fail-over 0` into the round gate — becomes runnable once (d)/(e) land.**


**TOP OF QUEUE (owner-requested 2026-07-26, round 684) — work this before PERF.**

- [ ] **(NATIVE.1) P0 — NATIVE xtsc HARD-CRASHES ON DEEPLY-NESTED INPUT, BECAUSE
  `runWithDeepStack` HAS NO NATIVE ACTUAL.** Found round 822 as a TEST problem and it is
  not one: `DeepStack.kt`'s native actual is a PASS-THROUGH, so the 256 MB deep-stack
  thread that the JVM pipeline runs on does not exist there, and `StackOverflowError` is a
  never-thrown stub — which makes the `init` boundary guard (the thing that converts an
  overflow into TS2589 instead of a crash) INERT on native. On the JVM a pathological
  input yields a diagnostic; on native the PROCESS DIES. This is a P0 by CLAUDE.md's own
  rule ("any crash/hang/OOM on any input is a P0"), and it is a PRODUCTION defect, not a
  test-harness one — it is only invisible today because the native artifact is not
  shipped.
  **THE FIX: a native `actual` for `runWithDeepStack` that runs the pipeline on a pthread
  created with an explicit large stack** (`pthread_attr_setstacksize`), mirroring what the
  JVM actual gets from `Thread(group, target, name, stackSize)`. Note the thread-handoff
  invariant that already applies on the JVM and will apply here: **Symbol/Type id sequences
  are THREAD-LOCAL (INV.6(6c0))** — the JVM actual captures the caller's counters, seeds
  the compile thread, and WRITES THE ADVANCED VALUES BACK on join; a native actual that
  skips the write-back restarts ids at 1 and collides with the singleton intrinsics'
  class-load-thread ids, which round 607 measured as 51 corpus failures whose `--listAll`
  output stays IDENTICAL. Do not implement this without reading that entry.
  **PRIZE:** correctness on native, plus it RETURNS `CfaTooLargeBailTest` to `commonTest`
  (round 822 moved it to `jvmTest` only because the process dies). **`DeepExpressionChainTest`
  can NEVER return** — it pins TS2589, which is unproducible on native by construction, so
  a green native suite is not evidence this is fixed.
  **VERIFY:** the deep pins run green natively from `commonTest`; and note round 822's
  measurement that **depth alone is not the predicate** — 30,000-term chains, a
  3,000-statement flow chain and the corpus's 6,452-term `binderBinaryExpressionStress`
  all pass natively TODAY because those walkers iterate. Build the repro from what
  actually RECURSES, not from what is merely large.

---

**THE § 0.1 ENDGAME, DECOMPOSED (round 802). READ THIS FRAMING BEFORE PICKING (JIT.1).**

§ 0.1 names ONE endgame — *"does the checking work itself get cheaper?"* — and prices it
(round 739) at **0.6–1.2% for the largest of three assignability sites**, with the
warning that removing the dedicated-walker layer *"trades the property that made the
corpus reachable"*. Round 802 measured something that changes what that sentence means.
**The five functions § 0.1's endgame is about — `checkMemberAccessMissingCore`,
`checkArgumentsAgainstSignatureCore`, `checkVarDeclAssignabilityCore`,
`checkAssignmentExpressionCore`, `checkSingleCallExpressionTypesCore` — are ALL above
HotSpot's 8,000-bytecode `HugeMethodLimit`, and so is `forEachChild`. HotSpot never
JIT-compiles them: they run in the interpreter for the entire compile.** So part of why
"the checking work" is expensive is not the *shape of the rules* at all — it is that the
functions holding them are too large to compile. `-XX:-DontCompileHugeMethods` measures
**−3.1%, B wins 4/4** with output identical at 46 errors, and the shippable form of that
is a **mechanical split**, which costs none of the scope trade § 0.1 warns about.

**So the decomposition below leads with (JIT.1), not with the engine question**, and it
keeps § 0.1's own endgame as (ENGINE.3)/(SCOPE.1) behind the price § 0.1 itself demands
("do not put the scope question to the owner until the two remaining sites are in").
Three standing facts a reader of § 0.1 must hold alongside it: **the budget in § 0.1 is a
COLD single-process budget**, and this arc already owns a warm artifact at **11.9 s**
(`--serve`, round 773) and an AOT one at **13.4 s** against the cold JVM's
26.5 s — so *parity is artifact-scoped*, and which artifact ships is the standing
(AOT.1) owner decision, not an engineering one. **CORRECTED round 823: that 13.4 s is the
GraalVM native-image (round 771), NOT Kotlin/Native — K/N release measures 20.0 s, 1.26×
a cold JVM. `docs/perf/aot-native-image.md` § 2b/§ 2c is the authority for all four points.**

- [x] **(JIT.1) COMPLETE at round 821 — the census is ZERO.** Split every method above
  HotSpot's `HugeMethodLimit` so it can be JIT-compiled at all. **Rounds 802–821: 19
  methods split, 46,567 bytecodes at the largest; the arc bought ONE measured wall gain
  (−3.93%, B wins 5/5, round 803's `forEachChild`) and landed everything else for the
  THRESHOLD — a method over 8,000 bytecodes is NEVER compiled by C1 or C2, so its cost
  cannot improve with load, input size, warm-up or JVM version, and no other gate in this
  repo can see that.** The standing residue is the (f) ratchet
  (`huge_methods.py --fail-over 0` + `HugeMethodLimitTest`), whose job from here is to
  fail the moment a NEW method crosses the limit. Original framing follows.
  **PRIZE AS ORIGINALLY MEASURED — upper bound −3.1% (793 ms), the largest measured
  prize in the queue.** Census: `scripts/huge_methods.py` (landed round 802) —
  **19 of 13,910 methods over 8,000 bytecodes**, listed in
  `docs/perf/setup-phase-and-huge-methods.md` § 3.2. The A/B is one flag on one binary:
  4 interleaved pairs, **A 25.448 s / B 24.655 s median, B wins 4/4, every per-pair delta
  negative**; arm A sd 0.81%, **arm B sd 1.46% — above the ~1% quietness criterion, so
  the SIGN is certain and the MAGNITUDE is ±~1.5%, not tight**. Cold interleaved band is
  ±2.0%, which −3.1% clears.
  **BLAST RADIUS: zero semantics, maximal surface.** A split moves code between methods
  and changes nothing else; but these are the hottest functions in the compiler, so the
  gate is the FULL one every time: suite, 8-profile grid BOTH directions,
  `--partitionCheck 2`, `cost_gate.py`.
  **FALSIFIER, stated once for the whole item:** after all sub-steps land, re-run the
  flag A/B. If `-XX:-DontCompileHugeMethods` STILL moves the wall, the split did not
  capture the effect and the remaining delta is C2 compiling something new rather than
  the interpreter being avoided — in which case (JIT.2) is the only route and the split
  is worth only what it measured on its own.
  **ROUND 803 UPDATE — (a) IS IN, AND IT MEASURED LARGER THAN THE WHOLE-FAMILY FLAG.**
  Census **19 → 18**. A monolith-vs-split A/B (a sharper instrument than the flag: two
  class dirs, same source otherwise, no flags) reads **−1,034 ms = −3.93%, B wins 5/5,
  five deltas spanning 339 ms = 0.33× the median delta**. Two corrections this hands to
  the remaining sub-steps: (1) **the "upper bound −3.1%" framing above is not a budget to
  divide up** — the flag makes C2 compile a 46,567-byte method, which costs compile time
  and code cache, so a split can and did beat it; (2) **the CAVEAT below is falsified for
  (a)** — the extra call boundary cost nothing measurable, because the range split keeps
  every hot kind in the entry function. The item's own falsifier was run early against the
  split binary: **+0.08%, B wins 3/5, spread 2,164 ms, driver verdict NOISE-DOMINATED** —
  i.e. the instrument that returned 4/4-all-negative on the monolith no longer returns a
  signal, which BOUNDS what (b)–(e) hold on this profile but does not license skipping
  them (18 methods are still interpreted, one at 5.8× the limit).
  **CAVEAT to carry into every sub-step:** a split adds call boundaries the monolith did
  not have, so a sub-step can legitimately measure NEGATIVE on its own while the set is
  positive. Do not judge a single split by wall time; judge it by the census
  (`--fail-over`) and measure the wall only over the accumulated set.
  - [x] **(a) DONE round 803 — `forEachChild` 9,750 → 4,353 / 2,728 / 2,175 across three
    range-keyed functions; −3.93%, B wins 5/5 (monolith vs split, 5 interleaved pairs).**
    New pins: `HugeMethodLimitTest` (reads the compiled `Code` attribute length — the only
    instrument in the repo that can see this) and `ForEachChildSplitTest` (enumeration
    ORDER + both seams; the reflection oracle pins the child SET only). Both verified
    discriminating against ablated binaries. **The transferable recipe for (b)–(e):** split
    by a CONTIGUOUS key range so each part stays one tableswitch, keep the hottest keys in
    the ENTRY function, and dispatch the continuations with one compare rather than a
    fall-through chain so no key pays two calls. Full derivation:
    `docs/perf/setup-phase-and-huge-methods.md` § 4.
  - [x] **(b) DONE round 804 — `checkMemberAccessMissingCore` 46,567 → an entry at 6,425
    plus ten `cmam*` helpers (611–3,550), split along round 789's committed level-R
    boundaries; census 18 → 17.** Round 791's invariant survives by construction (no new
    mutable state — the one cross-boundary value is RETURNED as a pair, not stashed in a
    field, which also avoids that round's save/restore). **The wall-clock prize is honestly
    ZERO**: monolith-vs-split, 5 pairs, **+0.23%, B wins 2/5, per-pair spread 860 ms against
    a 28 ms median delta, driver verdict NOISE-DOMINATED** — which is exactly the bound
    round 803's falsifier had already put on (b)–(e). New pins: `CmamSplitTest` (18, one
    display per section plus two seams) and `HugeMethodLimitTest` (+3); both verified
    discriminating (the monolith fails all 3 size pins; a two-mistake ablation fails exactly
    the 2 seam pins). Full derivation: `docs/perf/setup-phase-and-huge-methods.md` § 5.
  **ROUND 804 UPDATE — (b) IS IN, AND IT MEASURED NOTHING, WHICH IS THE USEFUL RESULT.**
  Census **18 → 17**. Read together with (a): the family has now been measured twice, at
  −3.93% for the traversal primitive and at NOISE for the biggest method, so **(c)–(e)
  should be landed for the THRESHOLD and the (f) gate, not for a wall number** — a method
  over the limit is permanently uncompilable and its cost cannot improve with load, input
  size or JVM version. **The one place a wall gain is still plausible and unmeasured is (e)**:
  every A/B in this arc is `--noEmit` and blind to the Transformer methods, while the
  published `bench-3way.sh` ratio is emit-mode on all three compilers.
  **ROUND 805 UPDATE — (c) IS STARTED AND (e) IS MEASURED.** Census **17 → 16**:
  `checkPropertyAccessInExpr` 9,062 → an entry at 4,728 plus `cpaExprFunctionExpression`
  1,526 / `cpaExprArrowFunction` 1,357 / `cpaExprObjectLiteral` 850 /
  `cpaExprClassExpression` 528. **Its `when` is an INSTANCEOF CHAIN, not a tableswitch**, so
  extracting arm BODIES changes no dispatch cost and (a)'s contiguous-range discipline does
  not apply; the four moved arms are simply the four long ones, and each is self-contained
  (no cross-boundary value, and the whole function contains no `return`). **No wall A/B was
  run for it, deliberately** — rounds 803/804 bounded the family twice and the round's budget
  went to (e). **(e) IS NO LONGER UNMEASURED: its prize is 0.14–0.25% of an EMIT-mode compile
  and inside the noise.** Measured on one binary: emit vs `--noEmit` = 28,060 vs 24,919 ms
  (the emit phase is **12.6%** on top of a check-only compile — higher than the 8.5% round 739
  recorded), and the whole-family flag buys **−1.14% in `--noEmit`** and **−1.17% in emit**,
  i.e. the same fraction in both modes, leaving −37 to −69 ms attributable to the three
  Transformer methods against a 402 ms per-pair spread. `transformToCommonJS` demonstrably
  RUNS on the profile (its `dist` output is CommonJS). **So land (e) for the THRESHOLD, not
  for a wall number**; caveats: 3 pairs is thin, and the flag also makes C2 compile a
  28,991-byte method, so it can under-read what a split would buy. **A third fact worth
  carrying: the whole-family flag now reads −1.14% in `--noEmit` against round 802's −3.1%
  on the pre-split binary — (a)+(b)+(c) have taken about two thirds off the instrument's own
  reading.**
  - [x] **(c) DONE round 811 — the sub-item is CLOSED.** Landed across five rounds:
    `checkPropertyAccessInExpr` 9,062 → 4,728 + four `cpaExpr*` arms (round 805, census
    17 → 16), `checkArgumentsAgainstSignatureCore` (807, see (f)),
    `checkVarDeclAssignabilityCore` (808), `checkAssignmentExpressionCore` (809, see (g)),
    and finally **`checkSingleCallExpressionTypesCore` 15,567 → an entry at 5,149 plus four
    `ccet*` helpers, one per contiguous run of the committed `CallSections` partition
    (round 734's (CALL.1)(a) instrument — the handoff said this target had none); census
    11 → 10.** Round 793's `ccetPrologueMayFire` gate STAYS in the entry, and what else
    stays is what the partition prices: `getCalleeType` (474 ms), the optional-member and
    any-bail gates (102 ms) and the single-signature branch (42.2% of all exits); what moved
    are the three branches the same exit census puts at 0.2% of invocations or less, plus
    the seven prologue walkers (zero firings). Equivalence by round 805's five checks
    (entry reconstruction IDENTICAL at 284 lines; cross-boundary values NONE). Pins
    `CcetSplitTest` (18) + `HugeMethodLimitTest` (+3). **Discrimination 2 of 4, and BOTH
    zeros survived a purpose-built retry** — the prologue's and the union branch's return
    signals are redundant guards on today's code, for reasons that are properties of the
    function (see the round-811 note). Full derivation:
    `docs/perf/setup-phase-and-huge-methods.md` § 15.
    **ROUND 805's OWED ABLATION IS PAID (round 806) — and its expectation was WRONG: ONE
    seam pin failed, not two.** The `enclosingClassType` mistake was caught; the dropped
    arrow-restore was NOT, because `withCpaFrameAmbient` reinstalls `currentLocalTypes` at
    every per-statement anchor and both restore pins read the NEXT STATEMENT. Both now read
    a LATER ARGUMENT OF THE SAME CALL, and a three-mistake re-run fails exactly the three
    seam pins and no arm pin. Nothing is owed here any more.
  - [x] **(d) DONE round 806 — `ccetSpineEnter` 8,686 → an entry at 2,474 plus
    `ccetEnterBlock` 2,848 / `ccetEnterClassDeclaration` 1,946 / `ccetEnterFunctionLike`
    1,328; census 16 → 15.** It runs at EVERY node of every file. Arm BODIES move, so the
    `when (node.kindId)` keeps all four keys and no kind pays an extra test; what STAYS is
    chosen by frequency (`MODULE_DECLARATION` plus the two trailing blocks). Equivalence
    measured by round 805's five checks, all green. Pins `CcetSpineEnterSplitTest` (10) and
    `HugeMethodLimitTest` (+3). **Honest limit: the trailing-blocks seam is NOT
    discriminated** — see the round-806 note. **The remaining (d) tail after round 813:**
    `tryInferSingleTypeParamFromArgs` 11,930, the `Checker` constructor 11,298,
    `access$checkBigintPropertyNames$emit` 10,339
    (`checkDuplicateDeclarations` 12,935 went at round 812;
    `checkIndexSigInStatement` 10,928 at round 813;
    `checkReturnAssignabilityCore` 9,743 at round 810).
    **ROUND 813 — `checkIndexSigInStatement` 10,928 → an entry at 1,010 plus SEVEN `cis*`
    helpers (359–2,680); census 9 → 8.** Second target with no committed partition, and the
    guard argument is sharper than round 812's because the guards are statement-KIND tests:
    the entry keeps the type-alias and variable-statement branches, the `when` whose `else`
    arm `return`s for every kind that is not a class/interface/module, the module recursion,
    the two index-signature lookups and the "no usable string index type" early return —
    every moved region sits behind one of those. **NO region contains a whole-function
    `return` and no `continue` escapes its region, so no helper needs a return signal at
    all**; the ONE cross-boundary value (the string index signature, a `var` HEAD's
    base-class walk mutated) is RETURNED. Moving each `if` STATEMENT rather than its body
    keeps two `when (stmt)`s exhaustive without an invented `else`, and costs nothing —
    their condition is already true by construction at that point. Equivalence by round
    805's five checks, all green (entry reconstruction IDENTICAL at 105 lines). Pins
    `CisSplitTest` (17) + `HugeMethodLimitTest` (+3). **Discrimination 4 of 4 — but the
    first only after a PURPOSE-BUILT RETRY**: the seam pin first written for the returned
    signature is BLIND (a sibling pass reports the same TS2411 for a primitive inherited
    index type), and diffing the ablated binary against the committed one over eight
    inherited-index shapes found the ONE line that is uniquely ours — a method against an
    inherited CALLABLE index value type. Full derivation:
    `docs/perf/setup-phase-and-huge-methods.md` § 17.
  - [x] **(e) DONE round 819 — the sub-item is CLOSED: every `Transformer`/front-end
    method is under the limit.** Landed across five rounds: `applyDirective` 13,694 → an
    entry at 89 plus four helpers (815), `TypeScriptCompiler.compileParsedCore` 21,535 →
    293 plus ten (816), `Transformer.transform` 8,934 → 2,989 plus seven (817),
    `Transformer.transformClassBody` 16,233 → 5,202 plus nine (818), and finally
    **`Transformer.transformToCommonJS` 28,991 → an entry at 2,944 plus nineteen `tcjs*`
    helpers (819, census 3 → 2)** — the largest method in the compiler and the first target
    whose regions `continue` the CALLER's loop, solved with a ONE-ITERATION FRAME
    (`for (stmt in listOf(stmtIn)) { … }`) that keeps all six such `continue`s verbatim.
    **No wall A/B was run for any of the five and none should be**: these are the EMIT path
    and every A/B in this arc is `--noEmit`; the (e) prize was measured at round 805 as
    0.14–0.25% of an emit-mode compile, i.e. inside the noise, so they land for the
    THRESHOLD and the (f) gate. Their behavioural gate is the corpus EMIT baselines — 993
    of the 5,692 `compiles to JavaScript matching` subtests carry a CommonJS-shaped
    baseline. Full derivations: `docs/perf/setup-phase-and-huge-methods.md` §§ 19–20, 22–24.
  - [x] **(g) DONE round 820 — `Checker.<clinit>` 10,339 → 3,156 plus seven top-level
    `ckConst*` builders; census 2 → 1.** The last shape in the arc no contiguity argument
    settles: a STATIC INITIALIZER. Only **50** of the companion's 276 members cost it
    anything — a `private const val` of a primitive/`String` carries a `ConstantValue`
    attribute and executes no bytecode — so all 10,339 are `setOf`/`mapOf` literals, and it
    shrinks only by hoisting them into functions it calls. **TOP-LEVEL functions**
    (`CheckerKt`, an `invokestatic`), not companion ones, which would have to be reached
    through the very static field `<clinit>` is installing; the price is that **a `private`
    companion member is invisible to a top-level function in the same file**, so
    `LIB_MIN_TARGET` moved only its leading `mapOf(…)` and kept the
    `+ TYPED_ARRAY_NAMES.flatMap { … }` tail. **The frequency argument is that there is
    none** — a static initializer runs once, at class load, so nothing here is priceable
    and no wall A/B was run. The risk this shape carries instead is a **WRONG-SET
    SUBSTITUTION**: five builders return `Set<String>`, so a mixed-up call site type-checks
    (5 of the 6 ablation arms compiled with 0 `e:`) and silently changes which names the
    checker believes exist. Discrimination 7 arms, 4 at the predicted count, 2
    UNDER-predicted for one shared reason (the TS2749 pin's subject `parseInt` is a member
    of both substituted sets, and `KNOWN_GLOBALS` is upstream of its consumer), plus a
    negative control at its predicted 0. Full derivation:
    `docs/perf/setup-phase-and-huge-methods.md` § 25.
  - [x] **(i) DONE round 821 — `tryInferSingleTypeParamFromArgs` 11,930 → an entry at
    1,869 plus `tispGatherAnchorCandidates` 3,054 / `tispGatherCallbackCandidates` 5,388 /
    `tispCheckConstraint` 1,503; census 1 → 0, WHICH CLOSES (JIT.1).** The only target in
    the arc that no contiguity argument settles — its bytecodes are FLAT (largest 25-line
    window 449 of 11,930; 22.2% are INLINED stdlib bodies charged to their call sites) and
    ONE `for (tp in orderedTps)` loop holds 9,368 of them. The seams came from a scripted
    DATA-FLOW analysis (`scripts/tisp_split_analyze.py`: per-region read/write sets,
    liveness, exit classification), and three facts made it exact rather than careful:
    a mutated CONTAINER (`candidates`, append-only) crosses a call boundary for free as a
    parameter; the ONE rebind that outlives its region (`tpSawAnyArg`) is RETURNED, never
    fielded; and a `Boolean?` return makes all 22 whole-function `return null`s mean what
    they meant, so **869 lines move VERBATIM** with no hand-edited control flow. No region
    holds a caller-targeting `continue`/`break` (measured 0/0/0), which is what makes plain
    helpers legal — round 819's target needed a frame for exactly that. The one non-move:
    the local `data class Candidate` is hoisted to a private nested class, because a helper
    signature cannot name a class declared inside a function body. Discrimination 5 arms +
    a negative control, 4 at the predicted count; **A4 (the returned rebind dropped) is
    UNDISCRIMINATED and stays recorded as such** — an un-inferred bare type parameter
    relates to most targets, so neither an argument-position nor an arithmetic pin can see
    it, and the shape's habitat is the self-compile profiles. Full derivation:
    `docs/perf/setup-phase-and-huge-methods.md` § 26.
  - [x] **(f) DONE round 807 — `checkArgumentsAgainstSignatureCore` 23,890 → an entry at
    7,173 plus THIRTEEN `caas*` helpers (456–2,792), one per contiguous run of the
    committed `ArgSections` partition; census 15 → 14.** **First split of a LOOP BODY, and
    that is the transferable part**: a `when` arm exits only by falling off the end, a loop
    body exits by `continue`, `break` and a whole-function `return`, none of which crosses a
    function boundary — so each region returns a signal (`CAAS_CONTINUE`/`CAAS_BREAK`/
    `CAAS_RETURN`/`CAAS_NONE`) the entry replays. Which token binds to the ARGUMENT loop and
    which to a nested one was decided by a **brace-matching parser over the
    string/comment-stripped source** (31 bind to the argument loop, 25 of them inside moved
    regions; both bare `return`s are whole-function) — indentation is not evidence, and only
    one direction of the mistake is compiler-caught. The hot rows stay inline per the
    measured partition: `L_ARGTYPE` (56.9% of the function), plus `L_PARAM`/`L_PRE`/`L_WEAK`
    and the `POST` row round 796's exit census says every invocation returns from.
    **Two extractions were needed:** moving the loop tail alone left the entry at **8,061**,
    61 bytecodes over, and `PRO`+`PRO2` (the eleven `tryEmit*` prologue gates) took it to
    7,173 — a split that lands "just over" is one extraction short, not a failed split.
    Equivalence by round 805's five checks, all green (entry reconstruction IDENTICAL at 366
    lines; **no cross-boundary value at all**). Pins `CaasSplitTest` (20) +
    `HugeMethodLimitTest` (+3). **Discrimination: five of six seams, and the sixth is
    recorded OPEN** — `caasTypeParamConstraintArg`'s trailing `CAAS_CONTINUE` dropped ALONE
    leaves every pin green, because `caasNonSimpleParamChecks`' own `CAAS_CONTINUE` catches
    the same argument one helper later; it is a redundant guard on today's code. Full
    derivation: `docs/perf/setup-phase-and-huge-methods.md` § 11.
  - [x] **(g) DONE round 809 — `checkAssignmentExpressionCore` 18,100 → an entry at 3,861
    plus NINE `cae*` helpers (453–2,867), one per contiguous run of the committed
    `CtaSections` **level-E** partition; census 13 → 12.** Second straight-line-sequence
    split, and the cheapest yet: the partition was already in the source, so nothing had to
    be measured to choose the boundaries. 38 bare `return`s, all whole-function; seven
    helpers return `Boolean`, two are `Unit`; **cross-boundary values NONE** (computed per
    region — `savedContextual`'s save/restore pair is what constrains the partition, and
    `b175RhsClassSym` is why `E_B175` and `E_B127` share a helper). Equivalence by round
    805's five checks, all green (entry reconstruction IDENTICAL at 345 lines). Pins
    `CaeSplitTest` (14) + `HugeMethodLimitTest` (+3). **Discrimination: 2 of 7, seven
    separate builds, and BOTH predictions were wrong** — the prototype seam was argued to
    discriminate and does not; `caeForeignTpTargetAndClassRhs` was argued redundant and is
    the round's sharpest single-pin result. Full derivation:
    `docs/perf/setup-phase-and-huge-methods.md` § 13.
  - [x] **(h) DONE round 810 — `checkReturnAssignabilityCore` 9,743 → an entry at 4,052
    plus `craGuardWalkers` 3,706 and `craElaborateReturnMismatch` 1,851, two contiguous
    regions of the committed `CtaSections` **level-C** partition; census 12 → 11.** The
    smallest over-limit `Checker` method (1.2x the limit), so the question was *how little*
    to move, and level C — a MEASURED partition — answers it: `C_ELAB` is **1 reach in a
    whole compiler self-compile** and `C_WALKERS` is the dedicated-walker FP firewall, while
    every row level C prices as engine work stays inline. **THE CARRY: a split that lands
    just UNDER the limit is one extraction short too** — `C_ELAB` alone left the entry at
    7,803, a 197-byte margin that the next edit would cross. Equivalence by round 805's five
    checks, all green (entry reconstruction IDENTICAL at 344 lines; **no cross-boundary
    value**). Pins `CraSplitTest` (13) + `HugeMethodLimitTest` (+3). **Discrimination: 1 of
    2, and the undiscriminated one survived a purpose-built retry** — everything after the
    elaboration is the LEGACY STRING TAIL, which emits for nothing the engine has already
    rejected, so no shape can discriminate that seam. Full derivation:
    `docs/perf/setup-phase-and-huge-methods.md` § 14.
  - **(c) The call core — CLOSED at round 811** (`checkSingleCallExpressionTypesCore`
    15,567 → 5,149 + four `ccet*` helpers; see the checked (c) entry above)
    (round 807 took `checkArgumentsAgainstSignatureCore` — see (f); **round 808 took
    `checkVarDeclAssignabilityCore` 19,296 → an entry at 3,535 plus seven `cvda*` helpers,
    one per contiguous run of the committed `CtaSections` level-B partition; census
    14 → 13**; **round 809 took `checkAssignmentExpressionCore` — see (g)**; **round 810
    took `checkReturnAssignabilityCore` from the (d) tail — see (h)**). Each already
    has a committed section partition from rounds 787–797, which
    IS a split plan. One method per commit. **Round 811 closed the sub-item, and corrected
    the claim that this last target had no committed partition: `CallSections` (round 734)
    partitions it into 16 sections with an exit census already published — so GREP FOR A
    PROBE OBJECT before budgeting a derivation.** The non-`Checker` tail is
    also still open and was never in this item's list: `Transformer.transformToCommonJS`
    28,991, `TypeScriptCompiler.compileParsedCore` 21,535, `Transformer.transformClassBody`
    16,233, `CompilerOptionsKt.applyDirective` 13,694, the `Checker` constructor 11,298,
    `access$checkBigintPropertyNames$emit` 10,339, `Transformer.transform` 8,934.
  - **(d) The tail of the list — IN PROGRESS, 1 `Checker` method left plus one odd one.**
    **Round 814 took the `Checker` CONSTRUCTOR 11,298 -> an entry at 5,538 plus ten
    helpers (`initSetupPasses`, `initDeclarationOnlyPasses`, `initCheckPasses1..8`);
    census 8 -> 7.** Two facts from it that generalise. (i) **A constructor's frequency
    argument is DEGENERATE** — it runs once and every input pays all of it, so the only
    cut criterion is SIZE; do not dress a contiguous slice as a cold-path argument.
    (ii) **5,538 of the entry is the class's 494 PROPERTY INITIALIZERS**, which `<init>`
    cannot delegate away, so the ~437-dispatch sequence was only ~5,760 bytecodes and the
    remaining headroom is consumed by ADDING FIELDS, not passes. The prize is bounded to
    zero wall (the loops all live in `pass` lambdas, already JIT-eligible) and no A/B was
    run. Seams: the ORDER of the runs (a wipe-and-repin corpus walker such as
    `checkBuiltinIterator` is an order sensor for free) and the `declarationOnly` guard —
    3 of 3 discriminated.
    **Still open in (d):**
    **Round 812 took `checkDuplicateDeclarations` 12,935 → an entry at 2,801 plus five
    `cdd*` helpers; census 10 → 9.** It was the first target in the arc with NO committed
    partition of any kind — a check run over all four remaining targets and worth carrying
    forward: **none of `tryInferSingleTypeParamFromArgs`, the `Checker` constructor,
    `checkIndexSigInStatement` or `checkBigintPropertyNames` has one either**, so each needs
    its boundaries from its own shape. Round 812's frequency argument came from a GUARD
    (`if (group.size < 2) continue`) rather than a probe, which bounds the moved population
    instead of pricing it; say so when a target has no partition. Still open:
    `tryInferSingleTypeParamFromArgs` 11,930 (the hard one — two 300–400 line
    `for (i in params.indices)` bodies plus a 132-line constraint block, with mutable
    locals crossing every boundary; **the first target that needs a real data-flow answer
    rather than a contiguity argument**), and
    `access$checkBigintPropertyNames$emit` 10,339 — which is **not** the 8-line local `emit`
    its name suggests but the whole per-file body the anonymous walker object closes over,
    so it is split by restructuring that walker. (The `Checker` constructor 11,298 went at
    round 814; `checkIndexSigInStatement` 10,928 at round 813; `checkReturnAssignabilityCore` 9,743
    went at round 810 — see (h); `checkPropertyAccessInExpr` 9,062 at round 805 and
    `ccetSpineEnter` 8,686 at round 806.) Also watch the four sitting
    JUST under the limit, one refactor from crossing it: `walkFunctionBodiesInExpr` 7,702,
    `cpaSpineLeave` 7,359, `ctaM3StmtAnchorCore` 7,245, `cpaSpineEnter` 6,941.
  - **(e) The non-`Checker` tail. `CompilerOptionsKt.applyDirective` 13,694 WENT AT
    ROUND 815** (an entry at 89 plus `applyDirectiveArms1..4` at 2,240 / 4,592 / 3,164 /
    3,708; census 7 → 6). **The finding to carry forward: its size had nothing to do with
    what it does.** 85 one-line `when (key)` arms, each an `options.copy(field = …)` on a
    ~150-field data class, which Kotlin compiles into a `copy$default` CALL SITE carrying
    the whole argument vector plus the default bitmasks — **~160 bytecodes per arm**. So a
    dispatch table's size is the ARM COUNT times the DATA CLASS's FIELD COUNT, `javap` is
    the instrument rather than line count, and **a future agent adding directives walks the
    limit back up at ~160 bytecodes each** (headroom per run: 5,760 / 3,408 / 4,836 /
    4,292 ≈ 21–36 more arms apiece). Round 815 also owes the family its first
    **NEGATIVE CONTROL**: the claim "the partition POSITION is unobservable, because the
    arm keys are pairwise distinct" was ABLATED (boundary moved by one arm → 0 pins fail)
    rather than asserted — when a split's correctness rests on a structural property,
    ablate the property's consequence and show the PREDICTED zero. Full derivation:
    `docs/perf/setup-phase-and-huge-methods.md` § 19.
    **`TypeScriptCompiler.compileParsedCore` 21,535 WENT AT ROUND 816** (an entry at 293
    plus ten helpers — four option-validation runs, the two dispatch arms, and four runs of
    the multi-file arm; census 6 → 5). **Three findings to carry.** (i) **The split is
    bytecode-NEGATIVE: 20,294 against 21,535, i.e. 1,241 FEWER** — the monolith's `var
    options` is captured by the non-inline worker lambdas of the `--workers` branch, so
    Kotlin BOXED it into a `Ref$ObjectRef` and charged **168 `getfield` + 168 `checkcast`
    pairs**, ~1,008 bytecodes, to reads spread over 1,780 lines; as a helper PARAMETER the
    boxing is gone. So "the parts must sum to at least the monolith" is not a law, and
    `javap -c … | grep -c ObjectRef` is worth running before hunting for size in what a
    function does. (ii) **The boundaries were MEASURED** by the new
    `scripts/method_bytes_by_line.py` (javap `LineNumberTable` → bytecodes per source
    line), which also shows **22.9% of the method is INLINED stdlib** carrying synthetic
    line numbers past EOF. (iii) **An 18-parameter helper with same-typed containers must
    be called with NAMED arguments** — a positional permutation of two
    `MutableList<Pair<String, String>>` type-checks and silently changes everything; the
    round's third ablation is that mistake, and it fails 2 pins.
    **ROUND 817 — `Transformer.transform` 8,934 → an entry at 2,989 plus SEVEN `tf*`
    helpers (385–1,367); census 5 → 4.** The first target in this arc on the EMIT path,
    so **no wall A/B was run and none should be** — every A/B here is `--noEmit` and
    structurally blind to it; the behavioural gate is the corpus suite's EMIT baselines
    plus `TransformSplitTest`. What stays is decided by one rule: both module-format
    branches hold all three whole-function `return`s, so keeping them in the entry buys
    round 813's property that no helper needs a return signal. **The parts sum to 8,770
    against 8,934 — 164 FEWER — and round 816's `Ref$ObjectRef` mechanism is measured
    ABSENT (0 in `transform` before and after). The cause is LOCAL-SLOT ADDRESSING**: the
    monolith's ~60 live locals push nearly every reference past slot 3 and pay the 2-byte
    `aload N`, while a helper's fit in slots 0–3 and take the 1-byte `aload_N` — counted
    2-byte 841 → 741 (−100), 1-byte 219 → 288 (+69), i.e. 100 of the 164, parameters
    costing nothing (Kotlin emits no `checkNotNullParameter` for a private method). **So
    there are TWO measured reasons a split can be bytecode-negative and neither prior
    transfers to the other.** Discrimination 3 of 3 with the count predicted before each
    run (order seam 2, set-identity seam 1, dropped call 1) plus a NEGATIVE CONTROL at
    its predicted 0. § 22.
    **ROUND 818 — `Transformer.transformClassBody` 16,233 → an entry at 5,202 plus NINE
    `tcb*` helpers (666–1,716); census 4 → 3.** Two shapes no earlier target in the arc
    had, and both are what a purely textual split cannot survive. (i) **A LOCAL DATA
    CLASS** (`PrivateFieldInfo`) constructed by a moved region: un-nameable from a member
    function, so it was LIFTED to a private nested class — the only text change outside
    the mechanical extraction, and behaviour-free because it captures nothing and never
    escapes. (ii) **A LOCAL `fun` CALLED FROM BOTH SIDES OF A BOUNDARY**
    (`buildStaticBlockIife`, which closes over two `var`s the split decides): it can
    neither move nor be duplicated, so it is passed as a FUNCTION-TYPED PARAMETER
    (`::buildStaticBlockIife`), which leaves the moved call site textually untouched —
    **and the ORDER that makes that sound is enforced by nothing in the types**, which is
    this round's first ablation. `isCapturablePrivateMethod` needed none of this: both its
    call sites are inside one region, so the local `fun` MOVED with them. **The parts sum
    to 16,018 against 16,233 — 215 FEWER — and this is the first target where BOTH
    bytecode-negative mechanisms fire at once**: boxed-`var` reads inside the function
    31 → 11 (round 816's; the 11 that remain are the entry's two alias temps, still
    captured by the local `fun` — exactly why they stay boxed) AND local-slot addressing
    2-byte 1,947 → 1,850 / 1-byte 197 → 254 (round 817's). Discrimination **4 of 4** with
    the count predicted before each run (order 1, list-identity 1, return-signal 1,
    dropped call 1) plus a NEGATIVE CONTROL at its predicted 0. § 23.
    **Still open in (e): `Transformer.transformToCommonJS` 28,991.** The Transformer three do not touch a
    `--noEmit` number at all, which is why every A/B in this arc is blind to them; their
    gate is the corpus suite's EMIT baselines, and they DO touch the published
    `bench-3way.sh` ratio, which is emit-mode on all three compilers (§ 0.2).
  - [x] **(f) DONE round 817 — the census is a RATCHET now, in TWO places, and it found
    a phantom in itself on its first run.** `python3 scripts/huge_methods.py
    --fail-over <census>` is a round-gate step beside `cost_gate.py` (CLAUDE.md,
    SESSION-PROMPT.md), and `HugeMethodLimitTest` runs the SAME whole-program census
    inside the suite so it cannot be forgotten — failing both on a NEW offender and on a
    STALE named entry, which is the tightening rule made mechanical. **Proven to fire,
    three arms, each its own build:** committed state 44 pins / 0 failed; ratchet
    tightened by one 44 / exactly 1 (the census pin); a stale entry 44 / exactly 1 (the
    named-offenders pin). `--fail-over 4` exits 1 against a census of 5; `--fail-over 5`
    exits 0. **THE FIND: `javap` renders a static initializer as `static {};` with NO
    parameter list, so the script's method-header regex never started a method there and
    charged all 10,339 of `Checker.<clinit>`'s bytecodes to the method preceding it —
    `access$checkBigintPropertyNames$emit`, a 16-BYTE ACCESS BRIDGE this queue has
    carried as a split target since round 802.** The count was right; one of the five
    NAMES was wrong for fourteen rounds. Method count 14,001 → 14,107: 106 static
    initializers had never been counted. `docs/perf/setup-phase-and-huge-methods.md`
    § 21. *Wiring it into Gradle's `check` is a build-system change and stays owner-gated
    → (JIT.3); this round deliberately did not decide that.*

- [ ] **(JIT.2) OWNER-DECIDED 2026-08-04: NO LAUNCHER FLAG; the APPROVED work is a round
  spent MEASURING the JDK 25 AOT cache.** The owner declined
  `-XX:-DontCompileHugeMethods` in the launcher defaults — (JIT.1)'s split already took
  the win with no flag, the census is 0, and forcing C2 to compile a 46,000-byte method
  costs compile time and code cache for a trade measured only on a 25-second compile.
  **So the live sub-item is (JIT.2a): measure the AOT cache (JEP 483 class loading +
  linking, JEP 515 method profiling) as a START-UP lever, orthogonal to everything this
  arc has measured.** It has never been measured here; it needs no per-OS build matrix
  (the cache is trained on the user's own machine). Measuring is approved outright;
  SHIPPING it is a packaging decision and comes back to the owner. Note the framing this
  arc already owns: parity is ARTIFACT-scoped — cold JVM 26.5 s, warm `--serve` 11.9 s,
  GraalVM native-image 13.4 s, Kotlin/Native release 20.0 s (round 823; the "native
  13.4 s" this line used to say was the GraalVM number, not K/N) — so the AOT cache is a
  FIFTH artifact point, and the number that
  matters is COLD start on a real profile, not a microbenchmark.
  Original proposal follows.
  Add `-XX:-DontCompileHugeMethods` to
  the application's default JVM arguments, and evaluate a JDK 24/25 **AOT cache**
  (JEP 483 class loading + linking, JEP 515 method profiling) as a second, orthogonal
  start-up lever — the cache is generated from a training run on the user's own machine,
  needs no per-OS build matrix, and has never been measured here. FOR: −3.1% measured for
  the first flag, for zero code change and zero behaviour change (46 errors on both arms);
  it also protects users on any build where a method has crept back over the limit.
  AGAINST: it makes C2 compile a 46,000-byte method, which costs compile time and code
  cache, and the trade was measured on a 25-second compile — it may be negative on a
  2-second one; and (JIT.1)'s split gets the same win with no flag, so shipping the flag
  is belt-and-braces rather than the fix. Answer needed on: (1) add the flag to the
  launcher defaults, yes/no; (2) approve a round spent MEASURING the AOT cache (measuring
  costs nothing but time; shipping it would come back here).

- [x] **(JIT.3) OWNER-DECIDED 2026-08-04: WON'T DO — leave the census out of Gradle
  `check`.** The reason it is not needed: `HugeMethodLimitTest` (round 817) runs the SAME
  whole-program `Code`-attribute census INSIDE `jvmTest`, and fails both on a new offender
  and on a stale `KNOWN_OVER_LIMIT` entry — so `check`, which depends on `jvmTest`, is
  already covered transitively. Wiring the script in as well would run the census twice
  and cost ~2 min of `javap` per build for no new signal. The standing arrangement:
  the suite test is the automatic gate, and `python3 scripts/huge_methods.py --fail-over 0`
  stays a round-protocol step the agent runs beside `cost_gate.py` when a round touches
  compiled code (it is the faster instrument when you want the census WITHOUT a suite run).
  Original proposal follows, for the record. **Proposal:** a method
  crossing 8,000 bytecodes is a silent, permanent, whole-run performance cliff that no
  existing gate can see (the corpus does not measure cost, `cost_gate.py`'s counters do
  not move, and `-XX:+PrintCompilation` prints nothing — round 802 grepped an 11,796-line
  log for `too large` and got 0). The census is static and takes ~2 minutes of `javap`.
  Answer needed on: add it to `check` (costs ~2 min per build), or leave it as a
  round-protocol step the agent runs by hand.

- [ ] **(SETUP.2) `buildFileLocalTypeMaps` is 636 ms (2.2% of the compile) — MEASURE THE
  NEVER-READ SHARE BEFORE DESIGNING ANYTHING.** Round 802's partition of `outside-pass`
  found the whole 975 ms row is ~15 setup statements of which THIS ONE is 65%; it eagerly
  `getTypeOfSymbol`s every file-level function / class / interface / enum / type alias /
  import alias and every annotated variable in the program.
  **PRIZE: unmeasured, and deliberately so.** It is bounded above by 636 ms and bounded
  much lower by **round 788's law: `getTypeOfSymbol` MEMOISES into `symbolTypes`, so
  deferring it MOVES the work to whichever pass asks first.** The recoverable part is only
  the symbols nothing ever asks for. Round 801 lost a lever to exactly this shape (the
  suffix set: row 53.5 → 0.9 ms, then `created 1143, materialized 1143`).
  **THE FIRST SUB-STEP IS A CENSUS, NOT A CHANGE**: count reads of `fileLocalTypes` per
  symbol and report `calls` vs `distinct`. **FALSIFIER: if `distinct` does not fall faster
  than `calls` under a deferral, the work moved and this item CLOSES** (round 800's test).
  **BLAST RADIUS: name resolution program-wide** — `getTypeOfIdentifier` reads this map in
  every pass — plus the TS2589/TS2615 emissions this function owns, which are position-
  sensitive. Gate: suite + 8-profile grid BOTH directions + `--partitionCheck 2`.

- [ ] **(ENGINE.3) Finish round 739's engine-rule price at the TWO remaining assignability
  sites — this is § 0.1's OWN precondition, in its own words: "do not put the scope
  question to the owner until they are in".** PRIZE: not a saving — a DECISION INPUT.
  Measured at site 1 of 3 (`docs/perf/engine-rule-price.md`): engine 483 ms (55.4%) vs
  dedicated-walker layer 326 ms (37.4%) = **0.67×, not the 14× the arc nearly quoted**,
  and **165 of the 326 is the weak-type rule, which MOVES into any replacement engine
  rather than vanishing** → 0.6–1.2% for that site. Scored predictions for the other two
  are already written down in that doc. **BLAST RADIUS: none — it is measurement only.**
  **FALSIFIER, and the reason to do it before (SCOPE.1): if sites 2 and 3 come in at the
  same order, the whole § 0.1 endgame is worth ~2–4%, which is LESS than (JIT.1) already
  measured, and the scope question should NOT be put to the owner at all.**

- [ ] **BLOCKED-PENDING-USER (SCOPE.1): the § 0.1 endgame proper — replace the ~1,046
  dedicated `check*`/`emit*`/`tryEmit*` walkers with general engine rules.** Guardrail —
  § 0.1 states plainly that this "is a SCOPE decision, not a perf task, and it trades the
  property that made the corpus reachable" (narrow verifiable walkers are what got the
  corpus to 100%; every broad engine attempt in this codebase regressed).
  **DO NOT RAISE THIS UNTIL (ENGINE.3) IS IN** — § 0.1 says so itself, and round 802's
  measurement makes the case weaker, not stronger: the largest concrete reason the
  checking work is slow turned out to be that it is not compiled, which is fixable without
  any scope trade. **Proposal, when the time comes:** put it to the owner as a
  cost/benefit with the three site prices attached, not as an architecture pitch.

---

- [x] **(SETUP.1) DONE round 802 — `outside-pass`, the last unnamed region in the
  compile, is ONE FUNCTION.** The 975 ms of checker-init inside no `pass()` wrapper is the
  ~15 setup statements at the top of `Checker.init` plus two end-of-init retractions. Each
  is now `pass("init:<name>")`, so the partition is **exhaustive by construction** (the
  residue is still printed and must stay ~0) at a cost of ~16 lambda invocations per
  compile. **The row falls 975 → 144 ms and `init:buildFileLocalTypeMaps` alone is 636 ms
  = 65% of the phase and 2.2% of the compile**; second place is 89 ms
  (`trackAllImportReferences`), then 36 ms (`computeAllEnumValues`), and eleven of the
  sixteen rows are under 2 ms. It is also the ONLY setup pass that does type-system work
  (80 `getTypeOfExpression` calls; every other records zero — pinned). **NO LEVER LANDED,
  BY DESIGN**: the obvious deferral is round 788's shape and `getTypeOfSymbol` memoises,
  so the census must come first — queued as (SETUP.2) with the falsifier written.
  Suite 13,476 → **13,481 / 0 / 3** (+5 `SetupPhasePartitionTest` pins).
  `docs/perf/setup-phase-and-huge-methods.md` § 1–2.

- [x] **(FRONT.2) DONE round 801 — `Binder.bind` is OPENED AND CLOSED, and the round's
  two candidate levers BOTH measured zero.** Step 1 re-derived the map (median of 3
  probe-free `--passTiming` runs, § 0's new 801 column) and it named its own target by
  ELIMINATION: the ~400 tail passes are 2,962 ms but **FLAT** (largest 75 ms = 0.26%;
  300 of them hold 11%; **only 2 of 400 do any type-system work and 0 narrow**), so the
  tail is ~400 pure traversals whose structural treatments — M0.4 and (DISPATCH.1) — are
  already measured and closed. That left the front end, and inside it **`bind`, 1,549 ms
  = 6.0% of the compile, never partitioned in 800 rounds**. `bind()` is three statements,
  so the partition is exhaustive BY CONSTRUCTION and costs 3 timestamp pairs per FILE —
  the first in this arc with no boundary-cost caveat at all. **`bindStatements` 31 /
  `bindLexicalScopes` ~470 (876,201 node pops) / `FlowGraphBuilder.build` ~1,050
  (236,587 flow nodes), residue −13 ms.** Level 2, three spans per CLOSURE:
  `collectReassignedNamesInRange` **275–444 ms over 2,014 closures**, the other two
  collectors 3 and 17 ms, and **~700 ms is the flow walk itself at 3.0 µs per flow node**.
  **LEVER A — hoist the `substring`, unbox the neighbour reads.** The census sized it
  exactly: **382,520 identifier occurrences classified, 15,331 recorded — 24×, i.e.
  367,189 needless `String`s**, plus a boxed `Char?` per context probe. Both arms on ONE
  binary, twice each, identical boundary counts: **fast 314/362 vs legacy 317/320 — ZERO.**
  *An allocation count is not a cost*, which is round 758's population-vs-frequency law
  one level down. **LEVER B — defer the suffix set.** Its one consumer is
  `root in flowNode.reassignedAfterNames`, reached only from a narrowing walk, and walks
  fell 75% since 758 — the (IANY.1) shape one phase earlier. `SuffixNameSet` collapsed
  the row **53.5 → 0.9 ms**, and then its own census answered round 788's law AGAINST it:
  **created 1143, materialized 1143** — every set is eventually asked, so the work is
  **MOVED into the checker, not deleted. NEUTRAL, and not quoted as a win.** **So `bind`
  joins `checkArgumentsAgainstSignature` (797) and the spine-leave handlers (733/799) as
  measured, bounded and CLOSED**; the largest thing left in it is a single-pass
  construction of a graph the checker requires. Suite 13,461 → **13,476 / 0 / 3** (+15
  pins; one of them CAUGHT lever B's false claim before the write-up did). Grid vs the
  real pre-change path in the same binary (`--flowEagerSet --flowScanLegacy`)
  **46/94/46/46/46/46/46/46, 0 added and 0 removed BOTH directions**; `--partitionCheck 2`
  **EQUIVALENT — 46**; cost gate **all 20 counters +0.00%**. **No wall A/B, deliberately.**
  **The positive control is DEAD ON THE PROFILE and says so**: `--flowScanBogus` reports 0
  divergences there (tsc's sources hold no `%=` in a scanned range) and fires only in the
  fixture — round 793's rule, honoured rather than hidden. Full derivation:
  `docs/perf/bind-attribution.md`.

- [x] **(IANY.1) DONE round 798 — `spineIanyEnterNode` is OPENED, and the gate it exposed
  removes 320 ms (~1.1%).** The last of round 732's six biggest spine handlers with no
  attribution round: 1,063 ms raw / 1,031 ms net over all 856,962 nodes, the round-532
  migration of `checkImplicitAnyParameters`. **42% of it defines a contextual state that
  nothing can read** — `spineIanyCtx` has no reader outside its own family and every
  reader sits INSIDE the subtree the state was defined for, so a CHILDLESS child
  (IDENTIFIER / STRING_LITERAL_NODE / NUMERIC_LITERAL_NODE, all empty under
  `forEachChild`) and a CALL whose arguments are ALL childless are both unobservable.
  Landed: `IanySections` + `--ianySections` (two spans per node, row classified after the
  span closes, boundary count a function of the node count alone) and `--ianyGateOff`
  (the pre-798 path in the same binary = the grid baseline), plus the coupled two-part
  gate. Grid **46x7/94, 0 added and 0 removed BOTH directions**, `--partitionCheck 2`
  EQUIVALENT, suite **13,449 / 0 / 3**, cost gate rebased (`typeOfExpr.calls` −7.63%).
  **STILL OPEN, sized**: ~500 ms sits in one row (`edge: child with a subtree`), and
  extending the gate needs a SUBTREE predicate ("can this subtree read a `kind = 0`
  state?") whose conservative form is quadratic under nesting — price the scan against
  the 500 ms that remains, not against the 320 this round removed
  (`docs/perf/implicit-any-attribution.md` § 8).
  **ROUND-799 FOLLOW-UP — the residue is SUB-PARTITIONED and the ablation-B gap is
  CLOSED** (`docs/perf/implicit-any-attribution.md` §§ 9–12): 506 ms over 451,292 calls,
  of which **249 ms is ONE arm** — the CALL/NEW ARGUMENT edge, 31,575 calls at **7.9 µs**
  — against 246 ns for the 249,471 edges that reach no arm at all. Landed there: the arm
  pre-gate `spineIanyEdgeHasArm` (one M0.2 tableswitch replacing 19 sequential `is`
  checks), **handler total 830 → 775 ms = Δ 55 ms (0.2%)**, provably a no-op, grid
  46×7/94 both directions, cost gate +0.00% on all 20. Ablation B (the scope-push
  exclusion removed) was run against the FULL corpus: **13,449 / 0 / 3, unchanged** — so
  the exclusion is **unfalsified by BOTH instruments and is kept ON ARGUMENT**, which the
  KDoc now says. **WHAT IS LEFT, with its obligation stated**: the 249 ms arm resolves a
  callee to decide one `typed` boolean read by five places; `rhsCanConsumeFnCtx` — the
  obvious reuse — is **UNSOUND** for it (it misses `ArrayLiteralExpression` elements and
  every NO-ARM parent, which do not redefine the state), and the sound predicate is
  BOUNDED rather than quadratic (it stops at the redefining arms) but must still be priced
  against 249 ms with round 788's cached-callee reappearance on top.
  **ROUND-800 CLOSE — THE ARM IS GATED AND THIS ITEM IS FINISHED**
  (`docs/perf/implicit-any-attribution.md` §§ 13–18). `spineIanyArgSubtreeMayRead` is the
  sound predicate: reader set = ARROW / FUNCTION_EXPRESSION / OBJECT_LITERAL, descending
  the inheriting arms and every no-arm parent, stopping at a nested CALL/NEW, defaulting
  to `true`, capped at 32 steps (0 hits) and costing **1.87 steps per edge**. Callee
  resolutions **20,812 → 1,439 (−93.1%)**, arm row 196/190 → 93/96 ms, **HANDLER TOTAL
  724/707 → 602/617 = Δ 106 ms (0.36%)**, 6.2× the within-arm spread. Round 788's law is
  ANSWERED, not assumed: `typeOfExpr.distinct` −6.18% against `calls` −4.21%, so 14,813
  nodes are typed NOWHERE in the compile and the work is deleted rather than moved. Grid
  46×7/94 both directions, `--partitionCheck 2` EQUIVALENT, suite **13,461 / 0 / 3**.
  **Two corrections recorded**: the "free" `typed = false` gate does not exist (15 of
  20,827 — at a reached argument edge the state is ALWAYS the call's own kind=1 frame),
  and **§ 11's second counter-shape is VACUOUS** — `spineIanyEdge`, the REACH classifier,
  has no `AsExpression` arm, so nothing below an `as` is walked and only the
  array-literal counter-shape is real. **NOTHING CONCENTRATED IS LEFT IN THIS HANDLER**:
  798+799+800 removed 481 ms of 1,031, and the residue is a flat ~200 ns/node floor.
  Re-derive § 0 before picking the next target.

- [x] **(ORDER.1) `formattingScanner.ts:311` is a PROGRAM-ORDER-dependent FP that round
  776 introduced and round 777 attributed — services / server / harness are 46 -> 47 /
  46 -> 47 / 94 -> 95 and have been since 2026-07-31.** The line is `TS2322: Type
  'SyntaxKind' is not assignable to type 'T'` at `tokenInfo.token.kind = container.kind`
  after `isToken(container)` — the round-762 `Token<TKind>` carrier family, where the
  property's type comes from the DECLARING generic interface. **Attribution is measured,
  not inferred**: rebuilding with only round 776's `ProjectCompiler.walk` sort reverted
  returns services to 46 with the line gone, and ablating round 777's own change leaves it
  in place. **Do NOT revert the crawl sort** — it is right, and it is what makes the COST.1
  counters a property of the project rather than of the filesystem; the defect is that a
  generic instantiation's verdict depends on which file touches a shared type node FIRST.
  The lesson for the gate: round 776 checked the compiler profile only, and this is on the
  other seven. **Diagnose with the round-762 method** — `propertyTypeOnCarrier` / a UNION of
  instantiations, and remember a single instantiation still answers correctly, so only the
  union reproduces it.
  **CLOSED round 778 — and the `propertyTypeOnCarrier` lead above is FALSIFIED.** The
  carrier is a bare DEFAULTED generic, which resolves to the RAW `Type.Interface`, not a
  `Type.Reference`, so `propertyTypeOnCarrier` degenerates to `getTypeOfSymbol(prop)`
  verbatim. The defect was one level down: `getTypeOfSymbol` persisted into `symbolTypes`
  even when a CALLER's instantiation context was installed — the context
  `getTypeFromTypeNodeCore` has always refused to cache a type NODE under — so the one
  member symbol every instantiation shares froze as `T` or as `any` depending on who
  touched it first. Gated the WRITE on the ambient context being empty
  (`symbolTypeContextIsEmpty`, mirroring three of `cacheable`'s four conjuncts); grid
  back to 46/46/46/46/46/46/46/94 with the sorted sets otherwise identical on all eight.
  Still open and stated in the note, not pinned: the bare read degrades to `any` rather
  than to the DEFAULT (`SyntaxKind`), in both orders.

- [ ] **(WIDEN.1) A `const` binding keeps its initializer's LITERAL type — PROMOTED round
  781 from (REL.2) cause (D) / (REL.4)(c), SIZED BY EXPERIMENT, and sub-step (a) LANDED.**
  tsc's rule is `getWidenedLiteralTypeForInitializer` (checker.ts:41455), a
  DECLARATION-FLAG gate: `NodeFlags.Constant || isDeclarationReadonly ? type :
  getWidenedLiteralType(type)`.
  **MEASURED SIZE, which retires the "blast radius is every `const` in the corpus" label
  the queue carried from round 762: ONE corpus baseline of 13,262 moved, and it moved
  because we were MISSING a real tsc rule (a const-assignment target is never
  assignability-checked), not because our output diverged in form.** No
  `LogicalParityDivergence` was needed. The reason the radius is so small is structural,
  and is the thing to know before touching this again: **`getTypeOfExpression` answers the
  BASE primitive for a literal NODE — this checker mints no fresh-literal expression type
  at all** — so the literal must be read off the AST (`literalTypeOfExpression`), and the
  gate belongs where `currentLocalTypes` is RECORDED, not in `widenType`.
  - **(a) DONE round 781 — the un-annotated `const` var-decl recording.** Gate in
    `checkVarDeclAssignabilityCore`'s un-annotated branch + `spineArithRecordVarDecl`
    (which runs later and would re-widen). Two exclusions, each found by measurement:
    an ENUM MEMBER still widens to its enum (skipping it cost `completions.ts:2239` —
    services/server 46 -> 47, harness 94 -> 95), and an ASSIGNMENT TARGET still reads the
    widened type via `widen1ConstLiteralTypeIds` (skipping it cost
    `constDeclarations-access2`). **Closes (REL.4)(c)**, verified on the real
    `ESDecorateClassElementContext` shape (1 error -> 0). 13 pins in
    `ConstLiteralTypeTest`, 6 discriminating against an ablated binary.
  - **(b) OPEN — the CALL-ARGUMENT position.** `const k = "method"; wantsKind(k)` still
    reports `Argument of type 'string'` on the fixed AND the ablated binary: the argument
    gate resolves its type by a path that does not read the recorded literal. **0 measured
    sites on all 8 profiles**, so do not widen this speculatively — take it only when a
    profile line or a (REL.2) re-measurement needs it.
  - **(c) OPEN — `readonly` class properties.** tsc's gate is `Constant ||
    isDeclarationReadonly`; only the `Constant` half landed. No measured site.
  - **(d) OPEN, and it is a DIVERGENCE we now own:** `const o = { kind: k }` infers
    `{ kind: "a" }` where tsc infers `{ kind: string }` (object-literal members are
    MUTABLE locations in tsc — the `as const` gotcha). Ours is NARROWER, so unlike the
    enum exclusion it CAN in principle manufacture an FP (`o.kind = "b"` is legal in tsc).
    Zero measured sites; recorded, not pinned (round 765's rule).
  - **ROUND-796 QUEUE HYGIENE — (b)/(c)/(d)'s "zero measured sites" RE-VERIFIED at HEAD,
    and the verification is stronger than a per-shape grep.** The 8-profile grid was
    captured at HEAD (46/46/46/46/46/46/46/94) and the **416 residual diagnostics across
    all eight profiles contain NO TS2322 and NO TS2345 at all** — the by-code census is
    TS2591×367 (`process`, the offline `@types/node` artifact), TS2304×24, TS2584×13,
    TS2503×6, TS7006×3, TS2339×2, TS2593×1. Literal widening can only ever manifest as an
    ASSIGNABILITY diagnostic (TS2322/TS2345/TS2353/TS2820), so all three open sub-steps are
    unobservable on the dashboard by construction, not merely unobserved: (b) would need a
    TS2345 at a call argument, (c) a TS2322 at a `readonly` member, and (d) — the
    divergence we own, where OURS is narrower — could only ever ADD a TS2322. **The item's
    own instruction therefore still holds unchanged: do not widen this speculatively; take
    it when a profile line or a (REL.2) re-measurement needs it.** (Re-verify the same way:
    the by-code census of the grid is one `grep -o "error TS[0-9]*" | sort | uniq -c`.)
  Back-pointers: **(REL.4)(c)** — CLOSED by (a). **(REL.2)** — its remaining worklist is
  labelled 3x cause (D), but round 781 read the real sources and **at least two of the
  three are not literal widening at all** (`formattingScanner.ts:113` initialises from a
  CALL, `importFixes.ts:1127` assigns enum members inline); re-classify behind the (REL.2)
  scaffold before crediting (WIDEN.1) with them.

- [x] **(REL.4) LANDED round 782 — argument checking is ON for a call whose callee is a
  member of an IMPORTED namespace, at a measured cost of ZERO new diagnostics.** The change
  is the two call sites this item named from round 767 on:
  `computeRawTypeOfPropertyAccess`'s namespace fallback follows a `SymbolFlags.Alias`
  receiver, via the barrel-aware `resolveImportedNamespaceSymbol` (the general
  `resolveAlias` resolves **0** of the compiler profile's 4,383 alias receivers, and per
  round 409 must NEVER be taught ESM `.js` + `export *` — the TS2315 flood). Each step
  alone is measurably INERT; only the pair moves anything. It turned on argument checking
  for **1,127** previously-unchecked `PropertyAccess` callees on compiler and **1,551** on
  services. Unscaffolded 8-profile grid **46/46/46/46/46/46/46/94**, set-for-set identical
  to the pre-flip grid on all eight; suite 13,289 / 0 / 3; cost gate exit 0, rebaselined
  (`typeOfExpr.calls` +0.46% and siblings — the newly checked population, ~2.9 typed
  expressions per newly checked callee).
  **THE INHERITED "RESIDUAL IS EMPTY" WAS STALE — FOR THE SIXTH CONSECUTIVE ROUND — AND THE
  REASON IS METHODOLOGICAL, NOT ARITHMETIC.** Measured cold, the flip cost **harness
  94 -> 95**: `projectServiceStateLogger.ts:412`, a 16th cause-(a) `Debug.assertNever` site.
  Rounds 777/779/780 could not have seen it: **every scaffolded price in this arc was
  measured on compiler and services ONLY**, and that file is in `src/harness`. Round 767,
  the one round that scaffolded all eight, did see it inside its harness 94 -> 121. **The
  price history for the record: 46 -> 66 / 71 (round 767), 46 -> 52 / 56 (round 778 state),
  46 -> 48 / 49 (round 779), 46 -> 47 / 47 (round 780), harness 94 -> 95 (round 782),
  zero (round 782 after (a)'s 16th site).** A residual is empty only over the population you
  measured; a two-profile scaffold is not the dashboard.
  - **(a) CLOSED round 782 — all 16. `Debug.assertNever(x)` whose argument does not narrow
    to `never`** (8 round 768, 3 round 769, 3 round 770, 1 round 777, 2 round 780, the last
    1 round 782). **CAUSE 7, round 782 — a FUNCTION-BODY-SCOPED enum narrowed in no
    direction at all**, `projectServiceStateLogger.ts:412` (harness only, which is why the
    two-profile scaffold of rounds 777-780 could not see it). B83.5: the binder never binds
    a block-scoped declaration, so such an enum is in neither the file's locals (round 425's
    scope) nor a namespace's exports (round 769's) and `resolveEnumSymbolForDiscriminant`
    answered null. Fixed by consulting round 748's scope-space table
    (`lexicalTypeSymbolForNode`) — **FIRST, not as a miss-fallback, which is the opposite of
    round 769's additive rule and deliberately so**: `resolveTypeNameToSymbol` already
    prefers the scope-space symbol, so preferring the file-level one here would key a
    SHADOWING enum through two `Symbol` instances, i.e. the round-425 split. **Still open,
    recorded not pinned: the VALUE-position twin** — a block-scoped enum shadowing a
    file-level one of the same name FPs TS2339 on its member accesses
    (`Property 'X' does not exist on type 'typeof Shadowed'`), unchanged by this round, zero
    measured sites on all 8 profiles. Earlier state of (a):
    compiler 48 -> 47, services 49 -> 47 at round 780. **The two "probably not narrowable" stragglers
    were ordinary narrowing gaps, and the recon that called them unnarrowable read the SHAPE
    instead of asking whether the flow read ever happened.** `moduleSpecifiers.ts:1411`
    (`allowedEndings[0]`): an element access IS a reference everywhere else in the checker —
    `getReferencePath` since round 461, a flow node from `Flow.kt`'s own arm, a path-STRING
    walk — but the four flow-reading arms of the argument gate tested
    `arg is Identifier || arg is PropertyAccessExpression`, so it fell to the raw-type tail.
    `stringCompletions.ts:386` (`Extension | undefined`): the switch `default:` union filter
    subtracted neither a nullish constituent covered by `case undefined:` (the member test is
    a string/number/bigint/boolean-literal predicate, so `undefinedType` never matched) nor a
    whole-ENUM constituent (one member-LESS `Type.Object`, where tsc has a member union to
    peel) — fixed by identity-matching the nullish case and by REPLACING the enum constituent
    with `enumMinusMembers`. **Still open, recorded not pinned: `getTypeOfElementAccess`
    applies no flow narrowing at all** (its property-access twin has since 17.34d), so a
    plain read `a[0]` after `if (typeof a[0] === "string")` still answers the declared type —
    a read site every element access passes through, and no profile line needs it.
    The closed 8 were THREE causes, all
    "the subtraction stops one member short of `never`": the LAST member (a single member
    type has nothing to subtract from — so a PARTIAL chain narrowed and a COMPLETE one did
    not, i.e. round 767's "only the FIRST/innermost subtracts" was backwards), an
    enum-member case in a UNION subject (the `Type.Union` `default:` arm filtered only
    literal NODES — and a PROPERTY-ACCESS subject is this same cause), and the round-462
    `n !== neverType` discard round 765 recorded and left unchased.
    **Cause (4) CLOSED round 769** — an enum DECLARED inside a `namespace` was invisible to
    `resolveEnumSymbolForDiscriminant` (file-level name lookup only), which blinded EVERY
    narrowing direction; a strictly-ADDITIVE enclosing-namespace fallback closes
    `parser.ts:2941`/`3485` (`ParsingContext`) and `findAllReferences.ts:1973`
    (`SpecialSearchKind`). The `"symId#member"` key-space widening was probed FIRST, round
    750/751's shape: of ~15k (compiler) / ~23k (services) resolver calls, **0** names
    resolve BOTH ways (the widened space is DISJOINT, so no existing key can move) and
    **0** member-parent disagreements — before AND after the flip, the latter on the
    population the flip itself enlarges (2 -> 130). Still open on that cause and NOT taken:
    the qualified `N.A.X` direction from OUTSIDE the namespace (`enumMemberTypeOfExpr`
    requires `pa.expression is Identifier`) — **0 measured sites** on all 8 profiles, so it
    would be an unmeasured widening of the same key space.
    **Cause (5) CLOSED round 770** — a POST-SWITCH fall-through did not narrow at all
    (`checker.ts:11536`, `37648`). The defect was in the BINDER: leaving a `default`-less
    switch, the post-switch flow carried only a FlowCondition chain asserting "every case
    EXPRESSION is falsy", which is the truth for the `switch (true) { case <cond>: }` idiom
    ONLY — a discriminant switch's case expression is a VALUE, so the chain narrowed
    nothing. Fixed as tsc encodes it: a switch-clause flow with an EMPTY clause range
    (`createFlowSwitchClause(…, 0, 0)`, read as `clauseStart === clauseEnd` ⇒ default),
    layered ON TOP of the chain so every `switch (true)` answer is untouched; three checker
    readers treat an empty range as "no case matched". Round 768's "NOT enum-specific,
    flow-graph work" label was exactly right — it fixes enums, discriminated unions and
    `typeof` tags alike. **The scaffolded price was NOT re-measured; the PREDICTION is
    compiler 56 -> 54 / services 60 -> 58** (both lines are in `src/compiler`), and it must
    be re-measured behind the scaffold before being quoted.
    **Cause (6) CLOSED round 777, and it was ONE site, not two.** `declarations.ts:1739`
    had already been closed by round 770 (it is that round's third KDoc shape; it is absent
    from the scaffolded output entirely), so only `nodeFactory.ts:7112` remained — subject
    `HasModifiers & HasDecorators`, an INTERSECTION with no member list to peel. tsc has
    one for free: `getIntersectionType` distributes `X & (A | B)` at CONSTRUCTION and
    `getReducedType` drops the discriminant-disjoint combinations, so the subject IS the
    7-member union `HasDecorators` before narrowing runs. Fixed as an ON-DEMAND VIEW
    (`distributedNarrowingType`) consulted by `narrowByCallPredicate` and ADOPTED ONLY
    WHERE IT SUBTRACTS, so identity and display are untouched wherever a guard does not
    apply. **Cause (7) is what remains** — see (a) above.
  - **(b) CLOSED round 779 — compiler 52 -> 48, services 56 -> 49, all 11 lines, no new
    line. AND THE LABEL BELOW WAS WRONG: the family is TWO defects and only one is
    inference, so "M3.1" over-priced it — the whole fix is ~15 lines and Blocker #2's arc
    was not touched.** (b1), **3 of 4 compiler lines** (`checker.ts:12576`,
    `esDecorators.ts:1093`, `esnext.ts:605`): `assertIsDefined<T>(value: T)` has a BARE
    type-parameter parameter and T was inferred CORRECTLY; the FP came from
    `tryEmitOptionalMemberArgVsRequiredNamedTs2345`, whose premise ("the parameter
    independently REQUIRES this named type") is self-referential when the named type is one
    we inferred from the very argument being judged. Gated by an AST predicate
    (`paramDeclaredTypeIsOwnTypeParam`) — it MUST read the AST, since an instantiated
    Signature carries no `typeParameters` and its parameter symbols hold the substituted
    type. (b2), **1 compiler / 4 services** (`utilities.ts:2386`,
    `fixClassIncorrectlyImplementsInterface.ts:71`, `importFixes.ts:2093`/`2113`): the
    stated defect, and a three-line relaxation of round 428's nullable-union anchor, which
    stripped the source's nullish constituents only when exactly ONE non-nullish member
    survived. Still open, recorded not pinned: the (b1) gate is BARE-TP only — a nested
    mention (`f<T>(x: Array<T>)`) is the same class of error with **0 measured sites**.
  - **(c) CLOSED round 781 by (WIDEN.1)(a) — and the feared arc was one baseline wide.**
    The message was `Type '{ kind: string; … }' is not assignable to type
    'ESDecorateClassElementContext'`: the object literal's `kind` came from a 5-arm
    `const kind = … ? "getter" : … : Debug.fail()` whose literal union had widened to
    `string`. Verified on a scratch project carrying the REAL member list and the REAL
    conditional chain — 1 error before, **0** after. The "blast radius is every `const` in
    the corpus" label is retired: the measured radius was ONE corpus baseline, which was a
    genuine missing tsc rule rather than a form divergence. See (WIDEN.1) above.
  **THE RESIDUAL IS EMPTY AND THE FLIP IS LANDED (round 782), MEASURED AT ZERO NEW LINES
  ACROSS ALL EIGHT PROFILES** — after (a)'s 16th site, which only the eight-profile
  measurement could reveal. The (REL.2) global enum -> MEMBER rule was EXACTLY ADDITIVE with
  this (+1 compiler / +5 services, the same five lines, measured both ways), so it is
  unaffected by the order in which the two landed.

- [x] **(REL.3) FIXED round 761 — and its headline claim was FALSIFIED in the same
  round.** The defect was real: `findInheritedBaseRef` enqueued a `Type.Reference`'s
  target's `baseTypes` RAW, and those are written in the TARGET's type-parameter
  scope, so `interface S extends Fwd<number>` (where `Fwd<T> extends Box<T>`)
  resolved `.v` to the bare parameter `T` — **not to `any`**, as round 760 reported;
  its read-into-`string` probe cannot tell an unconstrained TP from `any`. 9 of 10
  broken shapes fixed (forwarded / wrapping / extra / reordered TPs, class chains,
  method signatures, nested args, 3 hops); 8 pins in
  `HeritageTypeArgumentSecondHopTest`. **But it fires ZERO times on all 8 profiles**
  (shadow A/B counter), because tsc writes
  `export type AbstractKeyword = ModifierToken<SyntaxKind.AbstractKeyword>` — a type
  ALIAS, which takes the working `resolveGenericPropertyType` path. Probed on the
  real `types.ts`: `AbstractKeyword.kind` is `SyntaxKind.AbstractKeyword`, correct,
  and always was. **So (REL.3) is NOT the root cause of `completions.ts:2237` and
  does not block (REL.2)** — that claim is withdrawn. Leftover: a type-ALIAS
  intermediate (`type ABox<T> = Box<T>; interface S extends ABox<number>`) still
  degrades to `any`, in `getTypeFromBaseTypeExpression`; unqueued, no known consumer.

- [x] **(REL.2) CLOSED round 784 — the enum → MEMBER relation direction is LANDED and
  the flip is SET-FOR-SET FREE on all eight profiles.** `checkTypeRelatedToCore` decides
  enum-vs-member VACUOUSLY (both sides are member-less `Type.Object`s), so `K` is
  assignable to `K.A`; round 746 left it deliberately and said so in a comment.
  The rule was WRITTEN, COMPILED, MEASURED AND REVERTED this round: it is correct
  and it costs **compiler 46 → 52, services 47 → 57**, because the vacuous `true`
  is masking that many flow-narrowing gaps. **So the unit of work is the narrowing
  features, not the relation rule** — land these first, then the rule is free:
  (i) a type guard used as a TERNARY CONDITION (`parser.ts:2629`, `3762`×2);
  (ii) `===` enum narrowing across an `||` (`parser.ts:8444`, `8728`);
  (iii) `SyntaxKind` → `CommentKind` (`scanner.ts:905`).
  Round 760's `enumMemberDomainProvesNotSubtype` is the site-local stand-in and
  should be DELETED when this lands.
  **Round 762 ENUMERATED the six and they are FOUR causes; the labels (i)/(ii)/(iii)
  above are all WRONG and are superseded by the table below.** `completions.ts:2237`
  was a FOURTH cause (a guard target's enum member lost through generic
  instantiation), is FIXED (`88386a1d`), and was never one of the six — so the grid
  is now clean of non-env-legit lines and this item no longer owns a live defect.
  Re-measured with the global rule scaffolded in: the price is UNCHANGED at
  compiler 46 -> 52 / services 46 -> 57, and the full unmasked worklist is:
  - **(B) `===` / `switch` does not narrow against an enum MEMBER at all** —
    `parser.ts:8444`, `8728`, `completions.ts:2234`, `2239`. The `||` in round 760's
    label is incidental: a plain single `if (kk === K.A)` fails too, while `1|2|3`
    on `=== 1` narrows. Site: `narrowByEquality`'s `literalTypeOfExpression(other)`
    does not resolve an enum-member reference. Contained; needs (C) to have a union
    to filter.
  - **(C) an enum type is never decomposed into its member union** —
    `parser.ts:2629`, `3762`x2. The ternary in round 760's label is incidental: the
    same guard narrows in an `if`, and narrows in a ternary when the reference is
    declared `K.A | K.B | K.C` rather than `K`. Any constituent-filtering narrowing
    on an enum-declared reference has nothing to filter.
  - **(D) `const` initialisers do not preserve LITERAL types** — `scanner.ts:905`,
    `importFixes.ts:1127`, `1162`, `formattingScanner.ts:113`. **The largest share,
    4 of 11, and NOT enum-specific**: `const x = "a"; const y: "a" = x;` already
    reports *Type 'string' is not assignable to type '"a"'*. This is tsc's
    fresh/widening literal-type machinery — **an ARC (blast radius: every `const` in
    the corpus), not a queue item**; promote it as its own M3 item before (REL.2).
    **PROMOTED AND LANDED round 781 as (WIDEN.1) — and the "blast radius" premise was
    FALSE: one corpus baseline, not every `const`. BUT the four sites listed on this line
    were RE-READ in the real sources and at least two are not literal widening at all
    (`formattingScanner.ts:113` initialises from a CALL; `importFixes.ts:1127` assigns
    enum members inline) — re-classify them behind the scaffold before assuming they
    closed.**
  Order: (B)+(C) together (they share the `narrowUnionByLiteral` consumer), then (D)
  as an arc, then the global rule, then delete the stand-in veto.
  **None of (B)/(C)/(D) is observable on the dashboard until the global rule lands** —
  they are masked by the vacuous `true`, so each must be gated on LOCAL pins plus a
  scaffolded re-measurement, not on a grid delta.
  **Round 763 LANDED (B)+(C) and corrected both labels again. The scaffolded price is
  now compiler 46 -> 47 and services 46 -> 52** (was 46 -> 52 / 46 -> 57); the five
  `parser.ts` lines are CLOSED. Corrections that matter to whoever takes the rest:
  - **(C) was never a decomposition.** An enum's own type is a member-LESS
    `Type.Object` — neither a `Type.Union` nor a `Type.Interface` — so it fell through
    the call-argument NARROWABILITY GATE and the flow read was never attempted.
    `narrowByCallPredicate`'s single-type arm was always right. One clause fixed it.
  - **(B) does not need (C).** `k === K.A` failed even on a reference declared
    `K.A | K.B | K.C`. Fixed via `enumMemberTypeOfExpr` plus enum arms in
    `isLiteralAssignableToMember` / `areLiteralTypesEquivalent`, and a separate
    `narrowBySwitchClause` arm for a bare-enum switch subject.
  - **`completions.ts:2234`/`2239` are NOT (B)** and did not close: 2234 is a property
    read on a union of generic instantiations (`Modifier.kind` answers `SyntaxKind`,
    the constraint of `Token<TKind>`), 2239 is generic inference through `tryCast`
    losing `KeywordSyntaxKind`. Both are the (REL.3)/round-762 family, not narrowing.
  - **STILL OPEN, and the remaining worklist is 6 lines:** 4 x cause (D)
    (`scanner.ts:905`, `importFixes.ts:1127`/`1162`, `formattingScanner.ts:113`) plus
    the two `completions.ts` property-read/inference lines above.
  **Round 764 made the (C) arm a SECOND CHANCE** — it walks only when the declared enum
  does NOT already satisfy the parameter, which returns 3,403 of round 763's 3,406 added
  flow walks (`narrow.walks` 74,729 -> 71,326) with the grid byte-identical. It tightens
  automatically when the global rule lands: enum -> MEMBER is vacuously true TODAY, so a
  `K.A` parameter takes the skip path now and will take the walk path then.
  - **The NEGATIVE direction LANDED round 764** (`k !== K.A`, the else-branch of
    `k === K.A`, and a type guard's FALSE branch): `enumMemberTypesOf` +
    `enumMinusMembers` decompose the enum and subtract, so a bare enum now answers
    `K.B | K.C | K.D` where it answered `K`. It really does change DISPLAY, and it
    reached no baseline: the rule FIRES 14 times on the compiler profile and 31 on
    services (measured with a temporary counter, since removed — 5 of the compiler's
    are `SyntaxKind` at 395/396) with the grid and all 20 cost counters bit-identical.
    Still open in that direction: a switch `default:` clause (`narrowBySwitchClause`
    bails on a `DefaultClause` before narrowing — not enum-specific), and an
    `asserts k is K.A | K.B` call on a bare enum (`narrowByAssertCall`, own gate).
  - **CLOSED round 766, and round 765's diagnosis of it is WITHDRAWN.** The flow read was
    never declined: round 441's `never`-parameter arm always performed it and DISCARDED any
    result that was not exactly `never`. The enum case now keeps a proper member subset,
    behind the same round-746 owner rule as the other two directions — it cannot manufacture
    a diagnostic (nothing non-`never` is assignable to `never`, so only the DISPLAY moves).
    Gate population measured before widening: of 26,432 reference arguments on the compiler
    profile the arm is reached **5** times, of which **0** are enum-flavored — so the fix is
    narrow by construction AND fires **0 times on all 8 profiles**; 14 pins are its evidence.
  - **AND THE REASON FOR THAT ZERO IS THE ROUND-766 FINDING, WHICH IS THE NEXT UNIT OF WORK:
    `checkArgumentsAgainstSignature` DOES NOT RUN FOR A CALL WHOSE CALLEE IS A MEMBER OF AN
    IMPORTED NAMESPACE.** Isolated with three probes: a same-file `namespace Debug` reaches
    the argument gate, a plain exported function imported from another file reaches it, an
    exported `namespace Debug` imported from another file does NOT — the argument is not
    even counted, in both the direct-import and the `export * from` barrel forms. **That is
    1,108 `Debug.<member>(…)` calls on the compiler profile and 1,518 on services whose
    arguments are never checked** (441 `Debug.assert`, 313 `Debug.checkDefined`, 110
    `Debug.fail`, 62 `Debug.assertNever`), and it is why rounds 764-766's arms all measure 0
    there. NOT a general resolution failure — the flow-walk assert path DOES resolve a
    namespace member. **SIZED round 767 — see (REL.4) below.** The mechanism is TWO missing
    steps (the namespace fallback tests the receiver for `SymbolFlags.Module`, which an
    alias never carries; and the GENERAL `resolveAlias` cannot follow an ESM `.js` specifier
    into an `export *` barrel and must never be taught to — round 409's TS2315 flood). The
    price of both is **compiler 46 -> 66, services 46 -> 71, server 46 -> 72, harness
    94 -> 121**; ~75% of the new lines are one narrowing family. NOT taken.
    **Round 768 closed 8 of that family, re-pricing the flip at compiler 46 -> 58,
    services 46 -> 63, server 46 -> 64, harness 94 -> 113; round 769 closed 3 more (the
    namespace-scoped enum), re-pricing it again at compiler 46 -> 56, services 46 -> 60**
    — see (REL.4)(a).
  - **Round 765 RE-PRICED the global rule (scaffolded, measured, reverted — NOT landed):
    compiler 46 -> 47 UNCHANGED, services 46 -> 52 becomes 46 -> 51, and the worklist is
    5 lines, not 6 — `importFixes.ts:1162` has CLOSED** without being on anyone's list.
    The remaining five: `scanner.ts:905` (cause D; the ONLY compiler-profile line, so that
    profile is one line from the rule being free), `importFixes.ts:1127` and
    `formattingScanner.ts:113` (cause D), `completions.ts:2234`/`2239` (the property-read /
    generic-inference family, not narrowing). **(D) is now 3 of 5 and is still an arc.**
  - **Round 765 CLOSED both of those, and the "not enum-specific" label above is
    WITHDRAWN — the switch one is enum-specific and is one arm.** Round 425's
    `DefaultClause` arm has narrowed a literal-union subject all along (measured on the
    unmodified build and now PINNED); the gap was its `t !is Type.Union` branch, where a
    bare enum's member-LESS `Type.Object` had only round-460b's exhaustion test. The
    round-764 subtraction goes after that test, over the cases of the WHOLE switch. The
    assert one is an ORDER question: the non-union tail asks `t <: targetType`, which is
    vacuously true for an enum against its own members, so it returned `t`;
    `narrowByCallPredicate` asks the other direction first, and the enum case now gets
    its own arm rather than reordering the general test. Both share
    `enumTargetsAreOwnMembers` (the round-746 owner rule, factored out of
    `enumMinusMembers`) so the positive and negative directions cannot drift apart.
    **Both arms fire ZERO times on the profiles** — the grid is a no-regression gate for
    them, not evidence; 11 local pins plus the corpus are the evidence.
  - **Round 765 SETTLED the consumer question round 764 left open, and the answer is
    (b): the narrowed type IS consumed — by a real relation question — and every
    consumer agrees.** Measured with a consumer-side differential (scaffolding, since
    removed): compiler **4 relation consults, 0 differ**; services/server/harness **10,
    0 differ**; zero property lookups and zero displays on all four. The trace names the
    reason and it is not luck — **the target of every consultation is the ENUM ITSELF**
    (`SyntaxKind`, `StructureIsReused`, `UsingKind`, `NameValidationResult`,
    `ScriptElementKind`), and a sub-union of an enum's members relates to that enum by
    union membership, so the two answers cannot differ. The remaining 8-of-12 (compiler)
    and 15-of-22 (services) distinct products are never delivered and never read — that
    share is inert. **So the rule must NOT be deleted**: it is verdict-neutral only
    because enum -> MEMBER is still vacuous, i.e. because of the very leniency this item
    exists to close; when the global rule lands, a member-union target starts rejecting
    the whole enum and the subtracted type is what saves those sites.
  **ROUND 783 RE-MEASURED THE PRICE ON ALL EIGHT PROFILES, RE-CLASSIFIED THE WORKLIST,
  AND CLOSED THREE OF ITS FOUR CAUSES. THE RULE IS WRITTEN AND SCAFFOLDED IN THE TREE
  BEHIND `REL2_ENUM_TO_MEMBER` (the one switch in `Checker.kt` that is deliberately
  OFF) — do not re-write it, flip it.** Raw price against
  `46/46/46/46/46/46/46/94`: **project 46 -> 49, tsc/jsTyping/deprecatedCompat/
  typingsInstallerCore 46 -> 47, services/server 46 -> 51, harness 94 -> 99** — SEVEN
  distinct lines and FOUR causes, not the five lines round 765 recorded. Every label on
  the round-765 worklist above is superseded:
  - **(D') a `const` initialised from an enum MEMBER widens to the whole enum** —
    `scanner.ts:905` (ALL EIGHT profiles), `program.ts:1366`, `program.ts:3542`.
    **CLOSED round 783** by retiring (WIDEN.1)(a)'s deliberate enum exclusion, which was
    load-bearing only because of (F) below.
  - **(E) an object-literal property value in a TERNARY branch cannot see a flow
    narrow** (`ctxObj` is null there, so the round-438/462/468 acceptance can never
    fire) — `importFixes.ts:1127`, `formattingScanner.ts:113`. **CLOSED round 783** with
    a third acceptance that is monotone by construction rather than by relation test.
  - **(F) an indexed access `T["p"]` on a generic instantiation answers `any`** (the
    member symbol is shared and its cached type is globally `any` — round 761) —
    `completions.ts:2239`. **CLOSED round 783** with `propertyTypeOnCarrier`.
  - **(G) STILL OPEN, and it is the WHOLE residual: one line, `completions.ts:2234`, on
    services/server/harness.** `computeRawTypeOfPropertyAccess` flow-narrows a receiver
    only when its raw type is ALREADY a `Type.Union`, so `isModifier(node)`'s
    narrow-DOWN from `Node` is never attempted and `node.kind` answers `SyntaxKind`.
    **This is NOT the round-762 property-read family the item claimed** — measured,
    `Modifier["kind"]` and a `Modifier`-typed parameter's `.kind` both resolve correctly
    now. The two candidate fixes: give `extractNullNarrowing` a CALL-PREDICATE arm (it
    has only nullish / `typeof` / truthiness arms today, so no type-guard call ever
    reaches `currentLocalTypes` — a general gap worth more than this line), or a SECOND
    CHANCE at the property-read site gated on the answer being an enum's own type (cheap
    to write, but it fires on every `node.kind` in tsc's sources, so price the flow
    walks before landing it).
  **ROUND 784 CLOSED (G) AND LANDED THE FLIP.** `REL2_ENUM_TO_MEMBER` is now an ABLATION
  switch, not a scaffold. The grid was re-captured at HEAD first and then re-run with the
  flip: **`46/46/46/46/46/46/46/94`, set-for-set identical on all eight**; suite 13,309 ->
  13,323 / 0 / 3; cost gate exit 0, no rebaseline.
  - **(G)'s fix is `propertyTypeFromNarrowedReceiver`, a SECOND CHANCE at the RETURN
    rejecting path** — it narrows the RECEIVER (not the path) and re-resolves the property
    on it, consulted only after the raw type has already been rejected and adopted only when
    it makes the return relate. `narrow.walks` +3 of 71,690. **Neither of the item's two
    candidate fixes was right:** the `extractNullNarrowing` arm was unnecessary (the narrow
    is computed correctly and simply never asked for — requeued on its own merits as
    (NARROW.1)), and a second chance at the property-READ site would have been the primary
    path, i.e. the expensive one this avoids.
  - **`enumMemberDomainProvesNotSubtype` is DELETED.** `kindDomainKeysExceed` and
    `kindDomainProvesNotSubtype` were checked and were ALREADY deleted in round 753 — they
    survive only as comment prose, so no deletion was credited. **`typeGuardMemberDisjoint`
    is still live at 8 call sites and is NOT claimed dead**: CLAUDE.md's "probably
    unobservable post-(REL.1)(b)" is a measurement still owed, and a reader's consumer must
    be checked before it is spent.
  - The round-764 subtractive narrow was NOT deleted, per its own warning: it was
    verdict-neutral only while the vacuous `true` stood, and it is load-bearing now.
  - **NOT TAKEN, RECORDED:** the same second chance is missing at the VAR-DECL/assignment
    path (`checkVarDeclAssignabilityCore`'s symmetric narrowing block), so
    `const k: ModifierKind = node.kind` after the guard still reports the declared type.
    Zero measured sites on all eight profiles.

- [x] **(NARROW.1) CLOSED round 785 — the call-predicate arm is LANDED and is FREE on all
  eight profiles.** `extractNullNarrowing` now has a type-guard CALL arm gated by
  `NARROW1_CALL_PREDICATE`, delegating its verdict wholesale to `narrowByCallPredicate`, so
  `if (isFoo(x)) { … }` records the narrowed type into `currentLocalTypes` for the whole
  then-branch. Grid re-captured at HEAD first (`46/46/46/46/46/46/46/94`) and re-run with
  the arm: **set-for-set identical on all eight, in BOTH directions (0 added, 0 removed)** —
  and NOT vacuously, the arm fires **630 / 896 / 916** times on compiler / services /
  harness. Suite 13,323 -> **13,334 / 0 / 3** (+11 = this round's pins; zero corpus baselines
  moved). Cost gate: `typeNode.bypassed` +2.09%, rebaselined with its mechanism.
  - **IT REMOVES TWO FALSE-POSITIVE CLASSES.** A RETURN and an ASSIGNMENT inside a guarded
    branch had no `getNarrowedTypeForReference` opt-in, so both emitted TS2322 on code tsc
    accepts. The var-decl initializer never did — it already opts in — which is why the
    obvious probe shape is silent on both binaries and cost two probe rounds to route around.
  - **NO TRUE POSITIVE IS LOST, IN EITHER DIRECTION.** The only pre-existing pin that moved
    is round 784's `NarrowedReceiverPropTest` mismatch control, and it moved in MESSAGE only:
    same code, same position, source type now the narrowed `'string | boolean'` instead of
    the declared `'string | number | boolean'` — which is tsc's own wording, so the pin was
    corrected rather than switched off. No `LogicalParityDivergence` was needed or used.
  - **THE FEARED PERF SHAPE DID NOT MATERIALIZE:** `narrow.walks` +59 of 71,690 (+0.08%).
    The arm resolves a predicate, it does not launch a flow walk.
  - **STILL OPEN, RECORDED NOT PINNED:** the ELSE branch gets no subtractive narrow (the
    helper feeds the then-branch frame only), and the subject must be a bare Identifier that
    is already a tracked local — a `this.p` / property-path subject is not narrowed.

- [x] **(REL.1) ARC COMPLETE round 753** — (a) round 741, (b0) round 742, (b) round 744,
  (c) steps 1-5 rounds 745-753. Five enum walkers retired (`checkEnumLiteralAssignments`,
  `checkEnumToEnumAssignments`, `checkNamespaceEnumUnionAssignments`, B463's TS2416 piece B,
  `checkEnumAsgInFunctionScopes`), six discriminant-key readers moved onto the type path, and
  the `.kind` DOMAIN veto family deleted. The compiler profile held at 46 byte-identical
  through every one of the ~15 commits. **Two things the arc did NOT deliver, recorded so
  they are not re-planned as if they had:** the AST key helpers
  (`enumMemberKeysOfTypeNode`/`enumSwitchKeysFromTypeNode`) survive with live consumers, and
  the `annotatedOnly` restriction on `discriminantKeysOfMember` is still in place because
  dropping it is measurably a no-op until inference preserves an unannotated property's
  member type (round 751).
  **(REL.1) Enum-member types do not discriminate in the relation AT ALL —
  discovered round 729 while the `Exclude` distribution fix was being measured, NOT
  fixed there.** The one-line repro is
  `declare enum SK { A, B }; const k: SK.A = SK.B` — we emit NOTHING; tsc reports
  TS2322. Consequence: two AST interfaces that differ only in
  `readonly kind: SK.Identifier` vs `readonly kind: SK.PrivateIdentifier` read as
  MUTUALLY ASSIGNABLE, so a sibling node type is a structural subtype of every other
  sibling. That is invisible as long as nothing acts on it, which is exactly why it
  surfaced only when `Exclude<T, U>` started filtering: the filter promptly dropped
  `Identifier` out of `Exclude<PropertyName, PrivateIdentifier>` and invented two
  false positives (factory/utilities.ts:1056/1061). Round 729 closed THOSE by
  applying round 472's `.kind` DOMAIN veto (`kindDomainKeysExceed`) inside
  `evaluateConditional` — a per-site patch over a general gap, and the third such
  patch in this family (`kindDomainProvesNotSubtype` at the narrowing sites is the
  first two). **The real fix is a `Type` for an enum MEMBER that is distinct from
  the enum's own type, so the relation can reject a sibling by itself.** Blast
  radius is the reason it is a separate item: every enum-typed comparison in the
  corpus goes through this, TS2322/TS2367/TS2345 baselines included, and the
  existing kind-domain readers (`enumMemberKeysOfTypeNode`,
  `enumSwitchKeysFromTypeNode`, `discriminantPropAnnotation`) exist precisely
  because the relation could not answer. Decompose before starting; expect the
  per-site patches to become deletable once it lands, which is the measurable win.

  **DECOMPOSED AND SIZED, ROUND 740. IT IS A SESSION, NOT AN ARC — the measured blast
  radius is ONE corpus baseline.** (Fix deliberately NOT attempted; the probe below was
  reverted and the tree is clean.)

  **ROOT CAUSE, LOCATED — it is not "the relation is lenient", it is `anyType`.**
  `getTypeFromTypeReference` (Checker.kt:102093) reduces `SK.A` to the BARE member name
  `"A"` via `getTypeReferenceLastName`, resolves it through `resolveQualifiedName` to the
  enum's `exports["A"]` — a `SymbolFlags.EnumMember` symbol — and
  `getDeclaredTypeOfSymbolWorker` (:102387) **has no branch for that flag**, so it falls
  through to `else -> anyType` (:102509) and is cached in `declaredTypes[symbol.id]`.
  `kind: SK.A` and `kind: SK.B` are therefore *the same `Type` instance*. Corollary:
  **`TypeFlags.EnumLiteral` (Type.kt:55) is SET NOWHERE** — all ~11 read sites are dead,
  **including the widening rule `if (sf.hasAny(EnumLiteral) && tf.hasAny(Enum)) return
  true` at Checker.kt:143100, which is already written and waiting for a flag to exist.**

  **BLAST RADIUS — MEASURED, not guessed.** A throwaway 3-edit probe (an `EnumMember`
  branch minting a distinct `Type.Object(Object or EnumLiteral)` per member symbol —
  already interned by `declaredTypes[symbol.id]`; the enum's own type flagged
  `Object or Enum` so the dead rule fires; an enum-literal disjointness rule at the top
  of `checkTypeRelatedToCore`) was built, measured and reverted:
  - **Corpus: 12,927 tests, `1` failure** — `enumAssignmentCompat5`. Not "every enum-typed
    comparison"; ONE.
  - **The one failure is the MISSING leg, not the added one:** 4 spurious
    `TS2322: Type 'number' is not assignable to type 'A'`. A numeric enum member type must
    stay assignable FROM `number`, and from a numeric literal equal to the member's value
    (`let a: E.A = 0` is legal because `A === 0`; `a = 2` is not; `Computed.A = 1` is not,
    because a computed member has no literal — all three already in that baseline).
  - **Compiler profile: 46 -> 52 (+6).** Same family: `Extension.Dts` (a STRING enum
    member) not assignable to `string`; plus knock-ons where a union no longer collapses
    now that its enum members are distinct (`Partial<CreateSourceFileOptions> | ESNext |
    CommonJS`) and two generic/overload cascades.
  - **No perf cost:** probe profile self 26,192 ms against a 26.5-27.1 s baseline band.
  So the entire measured gap is **ONE rule family: enum member <-> its base primitive.**

  **DECOMPOSITION — three sub-steps, each landable alone and suite-gated.**
  - **(a) Mint the type, change no answer. DONE round 741** — corpus 12,940 / 0 / 8,
    compiler profile `--listAll` byte-identical at 46 (whole output, not just the count),
    cost gate max +0.40% and rebaselined in the same commit. `getDeclaredTypeOfSymbolWorker`
    has an `EnumMember` branch ([getDeclaredTypeOfEnumMember]) minting
    `Type.Object(Object or EnumLiteral)` interned on
    `"<canonicalEnumSymbol.id>#<memberName>"`; the enum's own type is
    `Type.Object(Object or Enum)`; `TypeFlags.EnumLiteral` finally has a writer and the
    rule at :143100 finally fires. **The base-primitive legs are VALUE-BLIND on purpose**
    — the value judgement (`let a: E.A = 2` where `A === 0`) is still
    `checkEnumLiteralAssignments`', and the relation co-emitting it is precisely the 4
    spurious TS2322 the round-740 probe measured. Both latent hazards closed. The one
    knock-on was NOT union collapse but `typeof x === "object"` classifying a member as an
    object — see `isEnumFlavoredObjectType`.
  - **(b0) Type the enum-member ACCESS EXPRESSION as the member. DONE round 742** —
    corpus 12,949 / 0 / 8 (+9 pins), profile back to 46, cost gate +0.35% then +0.00%.
    `enumMemberAccessType` (a targeted branch in `computeRawTypeOfPropertyAccess`, NOT a
    member table on the enum's type — that would make the enum a structurally non-empty
    relation TARGET) plus widening back to the enum in THREE places: `widenType`,
    `checkVarDeclAssignabilityCore`'s own inline literal-widener (which is what the TS2322
    assignment check reads as the declared type), and `widenEnumMemberTypes` distributing
    over a union. Six more classifier sites onto `isEnumFlavoredObjectType`; the two
    arithmetic classifiers answer per MEMBER. **Three pre-existing bugs the `any` had been
    masking, all fixed at the root**: `isComparableType` not resolving a TYPE PARAMETER's
    constraint; the arithmetic pass's first-wins recording refusing a nested body's shadow
    of an ENCLOSING body's binding (`spineArithInheritedName`); and the two widening paths.
    Member types print QUALIFIED now.
  - **(b) Let the relation reject. DONE round 744** — corpus 12,971 / 0 / 3, compiler profile
    `--listAll` BYTE-IDENTICAL at 46, **ALL FIVE `@Ignore`s in `EnumMemberRelationTest` ON**
    (which is why the skipped count fell 8 → 3). One rule in `checkTypeRelatedToCore` ("two
    enum-member types relate only when they are the same member"), verdict via the STRUCTURAL
    `enumMemberTypesAreSameMember` — never identity, because `canonicalEnumSymbol` can only
    canonicalize through `globals[name]` and INV.3(d) retired that merge for module-only names,
    so identity declares `SyntaxKind.StringLiteral` disjoint from itself. Landed WITH it, per
    the plan: the round-459 AST key gate in `signatureAcceptsArgs` RETIRED (its three original
    pins keep passing unchanged = the ablation evidence), a CONSTRAINT check for round 481's
    bare-`Type.TypeParam` lenience, the deletion of (a)'s string TARGET half, and a retraction
    in B266 (`checkNsEnumUnionOne`) of the general TS2322 it now co-emits with on three of
    `enumLiteralAssignableToEnumInsideUnion`'s five lines — only B266's DISPLAY is tsc's (a
    fully-covered member set collapses to the bare enum name), so when (c) retires B266 that
    collapse rule must move into the union display with it.
    - **THE TWO BLOCKING FPs WERE BOTH ENUM-FREE AND PRE-EXISTING**, landed on main as their own
      commits BEFORE (b), each ablation-verified against pristine main and each +0.00% on all 20
      cost counters: `declarations.ts:846` was a type guard on a bare TYPE PARAMETER REPLACING it
      with the candidate instead of intersecting (`9a8088a5`), and `utilities.ts:4175` was an
      intersection source carrying a union not DISTRIBUTING (`c1ed5cd5`). **Neither is what its
      message said** — see the round-744 session note for the two traps and the 1.3-second
      scratch-project CLI loop that made the bisection affordable.
    - **CLEARED round 743, at the root, on main — do not re-investigate:** `checker.ts:7997`
      was the B136 concrete-overload swap re-picking an overload the type-based loop had
      already rejected (`214e8cf1`); `parser.ts:2494` was overload selection being unable to
      see an `asserts` narrow, which lives only in the flow graph (`190d34b7`).
    - **NEGATIVE RESULT, do not re-spend a session on it:** the "one member splits into several
      `Type` instances because `canonicalEnumSymbol` cannot canonicalize a module-scoped enum
      post-INV.3(d)" hypothesis is FALSE — the structural verdict left the profile at the SAME
      FPs. It is kept anyway: strictly more correct and free.
  - **(c) Delete the scaffolding — STEP 1 DONE round 745: the value-aware rule landed and
    `checkEnumLiteralAssignments` (B203) is RETIRED with it, in one commit.** Corpus
    12,982 / 0 / 3 (+11 pins), profile at 46 diffed line-by-line, cost gate +0.00% on all 20
    counters. `numericLiteralFitsEnum` applies tsc's rule at both enum legs of
    `isSimpleTypeRelatedTo`: the wide `number` still relates to any numeric enum or member
    (bit-flag compatibility), a numeric LITERAL must equal the member's value, and an enum
    whose domain is not fully known keeps accepting everything — `enumValueDomainIsComplete`,
    whose third clause is tsc's *computed* enum: **a member with no initializer in an AMBIENT
    non-const enum has NO value in tsc**, so retiring B203 also removed an FP it had on
    `declare enum D { X, Y }; let d: D = 7`. Two supporting splits were forced by measurement,
    NOT by design: the source must reach the relation as a `Type.NumberLiteral`
    (`enumNumericLiteralSource`, restricted to that type by construction — admitting enums to
    `propTypeContainsLiteral` instead cost 4 profile FPs on `x = undefined!`), and the message
    keeps the literal via `ts2322KeepsSourceLiteral`. An enum-member TARGET also prints
    QUALIFIED now.
    - **STEP 2 DONE round 746: `checkEnumToEnumAssignments` (B425) and
      `checkNamespaceEnumUnionAssignments` (B266) BOTH RETIRE, in one commit with the rule.**
      Profile 46 diffed line-by-line, cost gate +0.00% on all 20 counters, +18 pins.
      `enumTypesRelation` is tsc's `isEnumTypeRelatedTo` and it lives in
      `checkTypeRelatedToCore`, NOT `isSimpleTypeRelatedTo` — every enum-flavored type is a
      member-less `Type.Object`, so a `false` in the fast path only falls through to the
      structural engine, which relates two empty objects vacuously (measured: the rule in the
      fast path changed nothing). It returns a `EnumRelFailure?` because the ELABORATION needs
      the same walk. Two display rules moved with the passes, each SPLIT rather than widened:
      `enumCollisionQualifiedDisplays` (tsc's `getTypeNamesForErrorDisplay` — same string on
      both sides ⇒ requalify both, gated to enum-flavored pairs) and `enumUnionTargetDisplay`
      (B266's: members print qualified, a consecutive full-coverage run collapses to the bare
      enum name, non-enum constituents sort first). The per-MEMBER domain trap is
      `enumMemberEntries`: an ambient non-const member with no initializer has NO tsc value,
      and reading our auto-numbers instead would reject a program tsc accepts.
      - **THE FINDING: a value-aware relation makes EVERY dedicated enum walker co-emit.** The
        suite caught two the ablations did not predict — B583
        (`checkEnumAsgInFunctionScopes`, all six of `enumAssignmentCompat6` duplicated) and
        B463's TS2416 (`enumAssignmentCompat7`) — resolved by the round-744 RETRACTION, not by
        more deletion, because each still owns the only correct DISPLAY
        (`import("f").DiagnosticCategory`; `(param: second.E) => void`). **B583 and B463 are
        the next retirements in this family, and both are now blocked on a display rule rather
        than on a verdict** — the state B266 was in when this round started. Qualifying an enum
        nested inside a rendered FUNCTION type is the concrete blocker for B463.
    - **STEP 3 DONE round 747: B463's TS2416 (`encmCheckClassesAndOverloads` piece B) RETIRES
      with the display rule. B583 does NOT — and round 746's read of it is CORRECTED.** Profile
      46 diffed line-by-line, 3,910 filtered tests over the six generated classes carrying every
      TS2416 baseline, 0 failures, +8 pins. The rule is `enumDisplayFullyQualified` (tsc's
      `TypeFormatFlags.UseFullyQualifiedType` restricted to enum names, consulted by
      `typeToString` at ANY depth so an enum inside a rendered FUNCTION type qualifies) plus
      `enumQualifiedRelationDisplays`, the `getTypeNamesForErrorDisplay` retry for a pair that is
      not itself enum-flavored — SPLIT from round 746's `enumCollisionQualifiedDisplays` because
      that one is fed annotation TEXT, and self-gating because a pair with no enum re-renders to
      the same string. Applied at BOTH relation errors of the chain (tsc retries at each). Piece
      B, `typeNodeDisplayOrNull` and BOTH halves of round 746's guard pair are deleted in the
      same commit; pieces A (TS2339) and C (TS2394) stay — the general path still emits nothing
      for them. Retiring it also removes a DIVERGENCE: B463 qualified UNCONDITIONALLY
      (`EnumAsgInfo.qualifiedDisplay`), printing `(param: third.Other) => void` where tsc prints
      `(param: Other) => void`.
      - **B583 IS BLOCKED ON RESOLUTION, NOT ON A DISPLAY RULE — see (REL.1)(d).** Ablated, the
        relation reproduces SIX of `enumAssignmentCompat6`'s eight byte-for-byte and emits
        NOTHING for the other two, because an `enum` declared in a function/arrow body is never
        bound (B83.5) and is invisible in TYPE position. `import("f").DiagnosticCategory` is the
        SECOND blocker, behind that. Measured ownership: over the same 3,904 tests, B583
        PassLab-disabled fails exactly ONE — `enumAssignmentCompat6`, its two `f.ts` lines.
        Both halves pinned in `EnumShadowedInFunctionScopeTest`.
    - **HISTORY — REDIRECTED round 744 BY ABLATION. The first deletion was NOT
    any of the three AST-only passes; it was the VALUE-AWARE disjointness rule.** Round 740's inventory called those three "100% artifacts,
    deletable"; a PassLab census (`build/pass-lab.txt`, `disable <pass>`, ZERO recompile — run it
    whole-suite for the failing set, then per-walker through the scratch-project CLI for
    attribution) measures every one of them uniquely load-bearing:
    `checkEnumToEnumAssignments` owns **all 12** of `enumAssignmentCompat3`,
    `checkEnumLiteralAssignments` **all 3** of `enumAssignmentCompat5`, and
    `checkNamespaceEnumUnionAssignments` **2 of 5** of `enumLiteralAssignableToEnumInsideUnion`.
    All three are the same gap: **the relation is VALUE-BLIND**, which step (a) chose
    deliberately (`let a: E.A = 2` where `A === 0`, `Computed.A = 1`, `E.A = 0` vs `F.A = 0`).
    `enumMemberTypeIsStringValued` already resolves a member's `ConstantValue` through the
    canonical enum, which is the whole input such a rule needs.
    - **None of the twelve AST key-space helpers is orphaned yet** (checked by reference count
      after the round-459 retire — `enumMemberKeysOfTypeNode` / `enumMemberKeyOfExpr` keep other
      consumers), so there is no free deletion to take first.
    - **~~STEP 3 (next), and round 746 re-ordered it~~ — DONE for B463 (round 747, see the STEP 3
      bullet above). The B583 half of this bullet was WRONG: it is not a display problem.**
      Kept for the history of the misread.
    - **STEP 4 DONE round 748 — a function-body-scoped `enum` IS resolvable in type position, and
      B583's blocker MOVES FROM RESOLUTION TO DISPLAY.** Profile `--listAll` BYTE-IDENTICAL at 46
      (diffed sorted line-by-line against pristine `7ab9b215`), 4,427 filtered tests / 0 failures,
      +4 pins. **SIZED BEFORE IT WAS WRITTEN, and the sizing is the reusable artifact:** a static
      census over all 6,455 corpus sources and the 78 tsc-compiler sources finds only **2 corpus
      files and 2 profile sites** where a block-scoped enum is used in TYPE position — corpus
      baselines changed: **ZERO**. `enumAssignmentCompat6` is the SHADOWING mode;
      `unusedLocalsInMethod4` and both profile sites (`debug.ts`'s `Connection`, `program.ts`'s
      `SeenPackageName`) are the silent-`any` mode, and neither profile site emits anything new
      once resolved. `lexicalTypeSymbolForNode` is consulted FIRST in `resolveTypeNameToSymbol`'s
      Identifier branch (innermost-first ancestor walk over the owning file's INV.2(c)
      `lexicalScopes`); **SCOPE-SPACE ONLY (`scope.symbols`, never `existing`) is the containment
      argument** — `declareLexical` skips any name the main binder bound, so a conventionally-bound
      name cannot be reached, and the innermost-first order IS the shadowing rule for free.
      `computeAllEnumValues` gained a scope-space leg (else the value-aware relation gets a
      value-less enum, which since round 745 accepts everything); `lexicalBlockScopedEnumNames`
      is the one-HashSet-probe perf gate. **ABLATION: with B583 disabled the general path went
      from emitting NOTHING to emitting both verdicts at both correct positions**, printing
      `Type 'DiagnosticCategory' is not assignable to type 'DiagnosticCategory'` — i.e. B583 is
      now in the state B266 was in before round 746. **DELIBERATELY NOT GENERALISED**: the other
      B83.5 kinds are **62x more numerous** in type position (interface 51 corpus files, type 41,
      class 33, vs enum's 2), so widening is its own sizing round; a negative-control pin holds a
      block-scoped `interface` unresolved on purpose. Pins in
      `FunctionScopedEnumTypePositionTest` (3 of 4 fail on unmodified `7ab9b215`, the third in
      the OTHER direction — the one that proves the ORDER changed).
    - **STEP 4b DONE round 749 — `checkEnumAsgInFunctionScopes` (B583) RETIRES with the
      `import("<base>")` display rule, in one commit. FIFTH enum pass replaced in this arc.**
      Profile `--listAll` BYTE-IDENTICAL at 46 (diffed sorted line-by-line), 8,978 filtered tests
      over the 19 generated classes carrying every TS2416-bearing or enum-named baseline, 0
      failures, +6 pins. **The display decomposed into ONE part** (B266's had three, B463's two):
      `enumTypeQualifiedDisplay`'s container walk continues past the namespaces into the FILE —
      `enumModuleImportPrefix` is tsc's `getFullyQualifiedName` reaching the source-file module
      symbol, which `symbolToString` renders `import("f")`. **The gate is round 746's
      `enumCollisionQualifiedDisplays`, REUSED UNCHANGED** — the first round in this arc needing no
      new gate, because a shadowed enum produces exactly the same-string pair it tests for. The
      condition is tsc's transcribed: a symbol has a `parent` only inside a container's `exports`,
      so top-level + `export`ed + module-syntax file, and a function-body declaration (scope-space
      symbols included) stays bare. **ABLATION: with the pass's RETRACTION removed, all EIGHT of
      `enumAssignmentCompat6` DUPLICATED byte-identically** — B583 wiped the general TS2322 at its
      own position before re-adding its own, so counting alone could never have shown the
      co-emission. **The `declare namespace` value-KIND split it was also credited with turned out
      to be the relation's already** (`enumMemberEntries`, round 746): the byte-identical diff is
      over the WHOLE test, `ambients` lines included. **NARROWED ON PURPOSE, a known divergence:**
      an EXPORTED namespace of a module file is `import("f").ns.E` in tsc and stays `ns.E` here —
      the file step is taken only when the namespace walk found nothing; no baseline asks for it.
      **NOT FIXED, measured:** the var-decl TS2322 site has neither the collision retry nor the
      enum elaboration, so `let z: DC = x` in the shadowing body prints `Type 'DC' is not
      assignable to type 'DC'` with no chain — a pre-existing gap B583 never covered either, and a
      sized round of its own (every var-decl assignability message flows through that site).
      Rule pins in `EnumModuleQualifiedDisplayTest` (3 fail on unmodified `d92ebe6a`, 3 controls
      pass on both); retirement pins stay in `EnumShadowedInFunctionScopeTest`, passing on both.
    - **~~STEP 4 (was next)~~ — THE B583 UNBLOCKER: make a function-body-scoped `enum` RESOLVABLE
      IN TYPE POSITION.** DONE, see above. Round 747 measured that with B583 ablated the relation reproduces six of
      `enumAssignmentCompat6`'s eight diagnostics byte-for-byte and emits NOTHING for the other
      two, because an `enum` declared inside a function or arrow body is never conventionally
      bound (B83.5) and `computeAllEnumValues` never reaches it either (it walks `result.locals`
      + namespace exports). Two failure modes, and the second is why this cannot be a
      "when the lookup misses" fallback: a UNIQUE name resolves to nothing (annotation → `any`,
      every check silent, and NO TS2304 because the INV.4(c)(iii) family finds the name through
      the INV.2(c) lexical scopes, which the type resolver does not consult), while a name that
      SHADOWS an outer one resolves to the OUTER symbol — a wrong answer, not a missing one.
      The INV.2(c) lexical binder DOES declare such an enum (`declareLexical` + a scope-space
      symbol whose `exports` are filled by `bindLexicalEnumMembers`), so the ingredients exist;
      what is missing is a POSITION-aware type-name resolution that prefers it, plus enum-value
      computation for a scope-space enum symbol. Decompose before starting. Only AFTER that does
      B583's display rule (`import("<base>").<Name>` for an enum shadowed by a module-scoped one,
      plus the `declare namespace` value-KIND split) become the remaining blocker. Target pinned
      byte-exactly by `EnumShadowedInFunctionScopeTest`; measured ownership is ONE corpus test
      (`enumAssignmentCompat6`, its two `f.ts` lines) over 3,904 filtered tests.
    - **STEP 5 (last, and now the ONLY remaining (c) sub-step) — `discriminantPropAnnotation`.
      ASSESSED round 749, not attempted. A SAFE DECOMPOSITION EXISTS and it is three steps,
      the first of which is provably behavior-preserving.** The standing risk is unchanged —
      `enumMemberKeysOfTypeNode`/`enumMemberKeyOfExpr` key on
      `resolveEnumSymbolForDiscriminant(...).id` while the round-741 type interning
      (`enumMemberTypes`) keys on `canonicalEnumSymbol(...).id`, the two spaces are COMPARED
      against each other inside `filterUnionByEnumDiscriminant` (member keys vs case keys), and
      a mismatch shows up as narrowing that silently stops matching. The decomposition removes
      that risk by reconciling the spaces BEFORE flipping any reader:
      - **(5a) Normalize the key space, change no reader. DONE round 750 — and the risk it
        existed to remove was ALREADY ABSENT.** Profile `--listAll` byte-identical at 46 (diffed
        sorted line-by-line against pristine `de24c764`), 740 local + 4,669 generated corpus
        tests / 0 failures, +6 pins. **Round 749's premise was FALSE and must not be
        re-derived:** `resolveEnumSymbolForDiscriminant` (:110652) already returns
        `canonicalEnumSymbol(target)` at BOTH return sites, so the AST readers and the round-741
        type interning were the SAME space; the flip as specified would have wrapped an
        already-canonical symbol. **A census found the key space had SIX producers, two of them
        unnamed by round 749** — `enumSwitchKeysFromTypeNode` (:89855, the bare-enum-name
        expansion) and `enumSwitchKeysFromType` (:89900, the RESOLVED-TYPE producer) — alongside
        `enumMemberKeyOfExpr`, `enumMemberKeysOfTypeNode`, `getDeclaredTypeOfEnumMember`'s
        `enumMemberTypes` interning and the exhaustive-switch `neverType` coverage probe
        (:108250). All six canonicalized independently, so they AGREED incidentally with nothing
        making a seventh inherit it. What landed is therefore the invariant made STRUCTURAL:
        `enumDiscriminantKey(enumSym, member)` is the only mint, all six sites call it, and the
        interning site passes the RAW `owner` (one canonicalizing hop is correct; two rely on an
        unpinned idempotence). **THE MINT-EQUALITY PROBE, run first: 153 distinct incoming enum
        `Symbol` instances on the compiler profile, 153 already canonical, 0 redirected** — that
        zero is the licence for 5b to compare the two spaces. Pins in
        `EnumDiscriminantKeySpaceTest`; all six pass on unmodified `de24c764` too, which is the
        CORRECT result for a step with no behaviour delta — a pin failing there would mean 5a had
        changed an answer it must not. **Recorded, not patched:** `canonicalEnumSymbol` memoizes
        per `sym.id` a decision that reads MUTABLE state (`globals[name]`, `enumValues[global.id]
        == null`), so a first touch before an enum's values are computed freezes the
        non-canonical answer for that instance — 0/153 on the profile, but 5b's producer touches
        enums from a different phase, so check it there.
      - **(5b) IN PROGRESS — 1 of 5 call sites flipped. `filterUnionByEnumDiscriminant` DONE
        round 751, and THE PROBE CAME BACK CLEAN.** Profile `--listAll` byte-identical at 46
        (diffed sorted line-by-line), 5,901 generated corpus tests + 1,476 enum/narrowing/
        discriminant tests / 0 failures, +5 pins. **THE PROBE, run first as instructed:
        198 distinct (property, key-set) sightings over the whole compiler profile — 198
        AGREE, 0 mismatched, 0 where the type path lost a key the AST path had, 0 where it
        gained one**, over 5 discriminant properties (`kind` 173, `type` 19, `operator` 3,
        `token` 2, `keywordToken` 1) and 7 enum symbols, all cross-file. The instrument was
        FALSIFIED in the same run (a perturbed key set reported MISMATCH-REACHABLE), so the
        AGREE verdict is a measurement rather than a silence. **THE MEMOIZATION HAZARD WAS
        MEASURED DIRECTLY AND IS ABSENT (0 divergences at every mint) — and note that round
        750's number could NOT have shown it: a `canonicalEnumSymbol` entry frozen to the
        non-canonical answer reports `canonical(sym) === sym`, exactly like an
        already-canonical one, so "153 canonical, 0 redirected" is also what a fully-frozen
        cache prints. Recompute with the memo BYPASSED and diff — that is the only probe that
        can see it, and it is the one to re-run for each remaining flip.** What landed:
        `enumDiscriminantKeysOfType` (the seventh producer — keys an `EnumLiteral` type as
        `enumDiscriminantKey(memberSym.parent, memberSym.name)` through the single round-750
        mint, distributing over a union) consulted by `discriminantKeysOfMember` type-first
        with the annotation walk as fallback, both starting from the SAME property symbol via
        `discriminantPropSymbol` (extracted from `discriminantPropAnnotation`
        behaviour-preservingly) — so the flip is a re-derivation of one answer, not a second
        lookup free to drift. **RESTRICTED to an annotated property**, per the standing rule.
        **THE ABLATION IS THE REAL EVIDENCE, since a re-derivation is byte-identical by
        construction: with the AST fallback CUT OUT ENTIRELY the profile stayed byte-identical
        at 46**, i.e. at this site the annotation walk is ALREADY DEAD on the compiler profile
        — which is what (5c) needs. It is kept for corpus shapes type resolution cannot answer
        (chiefly `p: typeof X` for a top-level const string → widened `string`, while the
        annotation still yields a `lit:s:` key). Pins in `EnumDiscriminantTypeReadTest`, 4 of 5
        failing on unmodified `db730b95`; the witness is a PARENTHESIZED annotation, which the
        AST reader has no arm for (so it yields no keys and the member is silently KEPT).
        **MEASURED, and it re-sizes the plan: the `annotatedOnly` restriction is currently
        UNOBSERVABLE — every unannotated enum-member property reaches the reader ALREADY
        WIDENED to the enum (`Type 'Kind'` vs `Type 'Kind.Alpha'` for the annotated sibling),
        so dropping it is a NO-OP today and buys nothing until inference preserves the member
        type.** An assignability probe CANNOT establish this (`Kind` is leniently assignable to
        `Kind.Alpha` here) — read the PRINTED type name.
      - **(5b) ROUND 752 FLIPPED FOUR MORE, IN THREE COMMITS. ONE READER REMAINS.** All
        byte-identical at 46, 8,173 generated + 630 local tests / 0 failures, +9 pins.
        (1) `e717aba2` the exhaustive-switch gate (`unionDiscriminantKeysOfTypeCore`) — 213/213
        AGREE, fallback ABLATED byte-identical; **the probe forced the shape: an
        enum-member-only reader went TYPEBLIND on 9 sightings (a bare `kind: SyntaxKind`
        expanding to 396 members), so the type path must be the AST pair's arm-for-arm twin —
        `enumSwitchKeysFromType` + `enumDiscriminantKeysOfType`.** Round 751's negative control
        inverted as predicted. (2) `3d8119dc` the `neverType` default-clause gate — 4/4 AGREE,
        per-sighting ablation showed the type half alone reproduces all 4; pin non-vacuity
        ATTRIBUTED by building `e717aba2` with only that site unflipped. (3) `6c988338`
        `kindDomainKeysOfType` (207/207) and `discriminantKindKeys` (738/738) — **clean but
        UNOBSERVABLE: three shapes built to expose a before/after are silent on the pristine
        build too, because both readers are VETOES over the structural relation and
        (REL.1)(a)/(b) gave the relation that ability.** Their pins are declared
        non-discriminating regression guards. Memo-bypass probe re-run each time: 0 divergences.
      - **(5b) COMPLETE round 753 — the LAST reader, the objlit discriminant-candidate filter
        (`selectUnionMemberByObjLitDiscriminant`), is type-first.** 292 sightings, 292 AGREE,
        0 mismatched, 0 blind, 0 gained; instrument falsified; memo-bypass 342 mints / 0
        divergences; profile byte-identical at 46. **AND ITS ANNOTATION FALLBACK STAYS — the
        one site of the six where it is still load-bearing, and round 752's prediction was
        TESTED IN BOTH DIRECTIONS rather than trusted.** Cutting it leaves the compiler
        profile byte-identical at 46 (the same signal that cleared the five earlier sites),
        but that is a fact about the profile: the round-475 `TypeQuery` shape
        (`kind: typeof CloseTag` over a top-level `const`) resolves to the WIDENED `string`
        and carries no key while `enumMemberKeysOfTypeNode` still yields `lit:s:close` — the
        probe reports it TYPEBLIND with a real decision difference (`ast=true type=false`)
        and an ablated build emits a wrong-constituent TS2353. Docstring premise corrected.
        Pins in `ObjLitDiscriminantTypeReadTest`, 1 of 6 failing on unmodified `bdc6abb1`.
        **THE PIN SHAPE HAD TO BE INVERTED: a failed selection here is not silence** — both
        consumers fall back to the whole union and the nested-excess consumer reads the
        property off the FIRST constituent, so the union order is reversed on purpose and the
        pins assert ABSENCE in a reachable branch. Written the natural way round, all of them
        pass on both builds.
      - **(5c) COMPLETE round 753 — the `.kind` DOMAIN veto family is DELETED, -194 lines.**
        `kindDomainProvesNotSubtype`, `kindDomainKeysExceed`, `kindDomainKeysOfType` (+ its
        Type.id memo), `kindDomainKeysFromTypeNode`, `kindDomainTypeDeclSymbol`,
        `ifaceKindDomainKeys` and `discriminantPropAnnotation`, with all three call sites —
        the union negative-guard branch, the single-type negative-guard `never` gate, and
        round 729's `evaluateConditional` patch. **THE ABLATION WAS INSTRUMENTED TO COUNT
        WHAT IT SUPPRESSED, and that is the evidence rather than the byte-identity: the veto
        FIRED 11,667 times on the compiler profile (of 40,648 consultations) and changed no
        output.** `kindDomainKeysExceed` is the single funnel for all three sites, so forcing
        it to `false` reproduces the deletion exactly. An ablation that never fires cannot
        tell dead code from load-bearing code — (5b)'s objlit fallback is the counter-example
        from the same round. Round 729's OWN pin (`DistributiveConditionalTypeTest.a sibling
        AST interface is not excluded just because it is structurally compatible`) passes
        without the patch, which is the specific evidence for that call site.
        **SCOPE CORRECTION: the "AST key helpers" in the line above STAY.**
        `enumMemberKeysOfTypeNode`/`enumSwitchKeysFromTypeNode` still have live consumers (the
        exhaustive-switch coverage readers, and `discriminantKeysOfMember`'s fallback measured
        load-bearing at (5b)); only `discriminantPropAnnotation` became unreachable.
        `discriminantKindKeys`/`typeGuardMemberDisjoint` survive as round 752 predicted.
        **Landed as ONE commit, not three: each deletion orphans the next and the build is
        warning-clean, so splitting would need throwaway `@Suppress("unused")`.** Three pin
        classes (`KindDomainTypeReadTest`, `NegativeGuardKindDomainTest`,
        `KindDomainMemoConsistencyTest`) were UPDATED, not deleted — they always stated
        narrowing invariants, so they now pin the relation alone.
      - **THE DETECTOR, so the failure is loud rather than silent**: a key-space mismatch presents
        as narrowing that no longer fires, i.e. TS2339 FPs on the compiler profile — so the
        profile's 46 is the gate. Sharper, and cheap: instrument both producers for ONE run to
        assert the flipped key equals the legacy key at every mint (the `--verifyMappedCache`
        probe is the precedent). **Round 750 ran it for the six existing producers — 153 incoming
        enum symbols, 0 redirections — so it is a proven-cheap instrument; the mint is now a
        single function, which is where the 5b version belongs.**
    - `discriminantPropAnnotation` (:110431) has FIVE call sites woven through switch-narrowing
      and type-guard filtering — `filterUnionByEnumDiscriminant`, `kindDomainKeysOfType`,
      `discriminantKindKeys`, the exhaustive-switch `neverType` gate, and the objlit
      discriminant-candidate filter — and ALL FIVE consume it the same way, through
      `enumMemberKeysOfTypeNode`, whose key space is `"<enumSymbolId>#<member>"`. **Its
      docstring's premise is now FALSE** ("the resolved property type is `anyType` for an
      enum-member" — (a)/(b0) fixed that), so the replacement is mechanical in shape: read the
      property's TYPE and key an `EnumLiteral` as
      `"<canonicalEnumSymbol(parent).id>#<name>"`. **The risk is NOT the read, it is the KEY
      SPACE**: `enumMemberKeysOfTypeNode` keys on `resolveEnumSymbolForDiscriminant`'s symbol
      id while the type interning keys on `canonicalEnumSymbol`, and the same keys are minted
      independently by `enumMemberKeyOfExpr` and `literalDiscriminantKeyOfType`. All producers
      must flip together or narrowing silently stops matching — still a multi-step replacement,
      still the riskiest part of (c). Then `kindDomainProvesNotSubtype` /
      `kindDomainKeysExceed` (including the round-729 `evaluateConditional` patch).

  **WHICH CONSUMERS BECOME DELETABLE** (census round 740, all line numbers in Checker.kt).
  **FALSIFIED IN PART, round 744 — read the (c) sub-step above before trusting the first
  bullet:** the three AST-only passes are NOT artifacts of the missing member TYPE, they are
  artifacts of the missing member VALUE, and ablation measures all three still uniquely
  load-bearing with (b) landed. The rest of the census is untested.
  - **~~100% artifacts, deletable~~ (see above):** `discriminantPropAnnotation`,
    `kindDomainProvesNotSubtype`, `kindDomainKeysExceed`, `checkEnumLiteralAssignments`,
    `checkNamespaceEnumUnionAssignments`, `checkEnumToEnumAssignments`.
  - ~~**AST-side machinery that only computes what a `Type` would carry** — deletable with
    their consumers~~ **— SETTLED round 753, and the census was right about four of twelve.**
    DELETED: `kindDomainTypeDeclSymbol`, `kindDomainKeysFromTypeNode`, `ifaceKindDomainKeys`,
    `kindDomainKeysOfType` (with the veto family that consumed them). **SURVIVING with live
    consumers, do NOT re-plan these as deletable:** `enumMemberKeysOfTypeNode` and
    `enumSwitchKeysFromTypeNode` (the exhaustive-switch coverage readers, and
    `discriminantKeysOfMember`'s fallback — measured still load-bearing at the objlit site on
    the round-475 `TypeQuery` shape); `typeGuardMemberDisjoint` and `discriminantKindKeys`
    (eight narrowing call sites); `filterUnionByEnumDiscriminant`, `enumMemberKeyOfExpr`,
    `enumSwitchKeysFromType`, `literalDiscriminantKeyOfType` (all still consumed — the key
    space itself is not scaffolding, only the AST *route into* it was). The general lesson
    matches round 744's: a census entry is a lead, and only an instrumented ablation settles
    it.
  - **SURVIVES, in modified form — do NOT plan to delete it:** `canonicalEnumSymbol`
    :109783. The duplicate-`Symbol`-instance problem it solves (the same enum arriving as
    the merged global, a file-local, and a barrel-resolved alias) is INDEPENDENT of the
    relation; a naive per-symbol mint produces two non-equal types for the same member and
    reproduces the catastrophe its doc records at :109775. Step (a) must intern on the
    canonical symbol, or compare structurally (enum name + member name + value) as tsc's
    `isEnumTypeRelatedTo` does.
  - **Orthogonal, not caused by this:** `resolveImportedEnumSymbol` :109798 (the barrel
    `_namespaces/ts.js` module-resolution hop) stays either way.

  **TWO LATENT HAZARDS that go LIVE the moment the member type is distinct** (both in
  `getTypeFromTypeReference`, both invisible today because everything collapses to `any`):
  :102125/:102129 look `SK.A` up in `currentTypeParamScope`/`currentTypeAliasArgs` under
  the **bare name `"A"`**, so an in-scope type parameter named `A` captures it; and :102132
  falls back to `globals["A"]` on qualified-resolution failure, binding `SK.A` to an
  unrelated global type named `A`. Step (a) must key on the QUALIFIED name.

  **PIN LANDED (round 740):** `src/commonTest/kotlin/EnumMemberRelationTest.kt` — four
  `@Ignore`d currently-failing expectations naming this item (so the gap stays visible in
  the skipped count) plus **four NOT-ignored positive controls** that must keep passing
  throughout: member widens to its enum, member assignable to itself, `number` -> numeric
  member, string member -> `string`. Those last two are precisely the shapes the probe
  over-rejected, i.e. they are the FP firewall for step (a).

  **PINS EXTENDED (round 741), and one of round 740's was VACUOUS.** `take(Ext.Dts)` —
  the "string member -> `string`" control — passes with or without the member type,
  because an enum-member EXPRESSION types as the ENUM and rides the pre-existing
  `isStringEnumObjectType` rule; it never reaches a member type. The four added leg pins
  annotate through `declare const d: Ext.Dts` / `declare const a: E.A` instead and were
  verified non-vacuous against an ablation build. A fifth pins the `typeof === "object"`
  knock-on, and a sixth (`@Ignore`d) records the one place step (a) is knowingly more
  lenient than tsc.


- [x] **(CATCH.1) Defensive-`catch` audit — DONE round 685, six batches: 193 of
  Checker.kt's 197 removed as dead residue, 3 kept with stated reasons, 1 real
  bug found and fixed, and the 20 sites OUTSIDE Checker.kt audited and found to
  be a different population that should NOT be removed.** Owner flagged
  `Checker.kt`'s `val app = try { getApparentType(localType) } catch (_: Exception)
  { null }` as a code smell and asked what else looks like it. **The census:** 218
  `catch` sites in `src/commonMain`, **197 in Checker.kt**, every one the same
  shape — swallow and return a default: 84 `null`/`return null`, 57
  `return`/`continue`/`return@…`, 26 `false`/`true`, 9 empty or fall-through, ~14
  type-valued (`anyType`, `errorType`, `"any"`, `Ts2403Cmp.UNKNOWN`). **Why they
  are residue rather than design:** git blame on the flagged site shows it was
  born `catch (_: Throwable)` (round 351) in the era of inline
  `StackOverflowError` guards, and the 2026-07-04 sweep (3b950156) narrowed all
  135 such sites to `Exception` **mechanically** — so this guard no longer catches
  the thing it was written for (SOE is an `Error`), and no named exception is
  documented for what it wraps. That sweep's own CLAUDE.md entry says removing the
  catches ENTIRELY is "a separate, per-site root-cause effort — do not do it
  blind"; this item IS that effort, done in gated batches rather than blind.
  **Method** (repeat per batch, one commit each): (a) pick a batch whose guarded
  expression is a small, near-total helper — start with the `getApparentType` /
  `getPropertyOfType` cluster the owner pointed at; (b) DELETE the try/catch,
  keeping the expression; (c) gate with the full corpus suite **plus `--listAll`
  ×8** (a swallowed exception's default can be corpus-invisible but profile-live);
  (d) classify each site by the result — **byte-identical ⇒ dead residue, delete
  it; now crashes ⇒ a real modelling bug**, so file it as its own queue item with
  the stack trace and RESTORE the catch for that site only, with a comment naming
  the exception it actually absorbs. **Record the ledger** (sites removed / bugs
  found per batch) in the session note; a batch that finds a bug has paid for
  itself even if the catch goes back. **Do NOT** blanket-remove, and do NOT
  re-widen any of these to `Throwable` — the `Exception` narrowing is what lets an
  `Error` reach the init boundary guard (→ TS2589) instead of becoming wrong
  output. Expect this to run over several rounds; ~200 sites is the population,
  not the target for one session.
  **Batch ledger.** *(1) round 685 — `getApparentType`/`getPropertyOfType`, 30
  sites removed, 0 restored, byte-identical on corpus + `--listAll` ×8 ⇒ all dead
  residue; 1 bug found and fixed (unguarded type-param constraint recursion →
  stack overflow on `<T extends U, U extends T>`). Checker.kt 198 → 168.
  (2) round 685 — `getTypeOfSymbol` (16) / `resolveStructuredTypeMembers` (6), 22
  removed, 0 restored, 0 bugs; byte-identical the same way. Checker.kt 168 → 146.
  These two are deep resolvers, but each already carries the guard the catches
  stood in for — a per-symbol in-progress sentinel (B202.1) and the heritage cycle
  guard — so the catches were a redundant outer layer.
  (3) round 685 — `getTypeFromTypeNode` (39), 0 restored, 0 bugs. Checker.kt
  146 → 107. Its B202.2 sentinel covers only the CACHEABLE path, so the pins drive
  the cache-BYPASSING contexts (type-param scope / inference namespace / alias
  args), where the alias depth bail is the protection instead.
  (4) round 685 — `getTypeOfExpression` (40), 0 restored, 0 bugs. Checker.kt
  107 → 67. No sentinel and none needed: it is a kind dispatcher over a finite
  acyclic tree, delegating to guarded resolvers and iterative walkers, so only a
  DECLARATION cycle can recurse — which the pins drive.
  (5) round 685 — the SINGLE-LINE tail (34: relation engine, type printer, alias
  and heritage resolution, widening, `getTypeOfIdentifier`, singletons), 0
  restored, 0 bugs. Checker.kt 67 → **33** (commonMain 218 → 53 over five
  batches). **One site KEPT by judgement, with a comment**: the `Parser(...)` in
  `resolveRequireModuleShape`, whose input is arbitrary external `.json` file
  content — "the corpus did not crash" is weaker evidence for an unbounded
  external input than for a compiler-internal path.*
  (6) round 685 — the 28 MULTI-LINE blocks, hand-spliced (one exact
  whole-construct swap per site with an asserted occurrence count, because a
  scripted multi-line rewrite is the documented mangle hazard); ten collapsed to
  something simpler than the original. Checker.kt 33 → **3**.*
  **CLOSING VERDICT.** Checker.kt's 197: **193 removed, 0 restored, 3 kept** —
  the SOE boundary guard (load-bearing per the SOE doctrine), the `FriBail`
  control-flow catch (never defensive), and the `Parser(...)` on external `.json`
  content (kept on the evidence asymmetry). **One bug found and fixed.**
  **The 20 sites outside Checker.kt are NOT the same population and were left
  alone deliberately** — audited this round: Vfs's 3 are filesystem I/O (a missing
  or unreadable file must yield null, not crash); Parser's 2 guard parsing of
  externally-sourced JSDoc type text; TsBuildInfo / TsConfigLoader /
  ModuleResolver name their exception (`SerializationException`,
  `IllegalArgumentException`) over external JSON; Transformer's one names
  `NumberFormatException`; Emitter's and Flow's "catch" greps are comments and
  emit-helper source strings. Every one either guards an external input or names
  what it absorbs — which is exactly what the residue did not do. The item's
  premise ("~200 sites, all the same shape") holds for Checker.kt alone.*
  **Method addendum from batch 1:** write the batch's corner-case pins FIRST and
  run them against unmodified HEAD — the pins, not the removal, are what find
  bugs, and the HEAD run tells you whether a failure is pre-existing or yours.
  **Rule of thumb from batch 2:** grep the guarded helper for its OWN cycle
  guard / in-progress sentinel first; where one exists the call-site catch is
  redundant by construction and the batch is very likely byte-neutral.
  Next up: the two deep resolvers `getTypeFromTypeNode` (39) and
  `getTypeOfExpression` (41) in small slices, plus the ~30-site singleton tail.

**PERF — the post-inversion performance arc (owner-approved 2026-07-20, round 618:
"proceed according to your recommendations"; measurements + rationale in the
round-618 session note and the rewritten docs/ARCHITECTURE-RETHINK.md § 6). Ground
rules: the INV rules unchanged, PLUS wall-clock claims are decided ONLY by
interleaved A/B medians — anything priced below the ±2% drift band folds into a
structural item instead of landing alone.**

**ROUND-716 RE-SCOPE (owner: "do anything needed … we are free to completely
redesign this project, if the performance gain is on the horizon"). The arc's
diagnosis was wrong and is corrected in docs/ARCHITECTURE-RETHINK.md § 0 — READ IT
FIRST. Headline: the type system is 5.0 s of an 18 s compile (28%); the dispatch
and handler machinery is ~7.6 s (42%); the entire context-cache prize INV.5(c)
exists for is 68 ms. Work (DISPATCH.1) before any further cache/identity work.**

**WORK ORDER (round 716, after the owner's four decisions). The protocol says
top-to-bottom, and the order below IS deliberate — read this before picking:**
**(PARITY.1)** and **(COST.1)** first: both are cheap, and they are what make the
rest safe — PARITY.1 removes the byte-gate veto that priced out general-engine work
(and unblocks LIB.1's ~30 baselines), COST.1 stops the campaign silently
re-accumulating the very overhead it exists to remove. Then **(LIB.1)**, the
silent-wrong-answer fix the owner asked for. Then **(DISPATCH.1)**, the measured perf
lever and the prerequisite for reviving the M0.4 tail migration. **(PERF.HW)** is
opportunistic — run it only with spare budget; it must not preempt DISPATCH.1.

- [x] **(PARITY.1) DONE round 717 — the policy is now a MECHANISM, not a habit.**
  (a) `docs/logical-parity.md`: the owner directive, the form-vs-meaning decision
  procedure as two ALLOWLIST tables (7 meaning axes / 6 form axes, each form axis
  carrying its equivalence obligation; anything in neither table is MEANING by
  default), the four-step per-case procedure, and the generated ledger. (b) The
  mechanism: a `LogicalParityDivergence(baseline, round, pinnedBy, reason)` in
  build.gradle.kts's `logicalParityDivergences` is the SINGLE source of truth — the
  generator emits that subtest `@kotlin.test.Ignore`d with the reason inline (so it
  stays VISIBLE as skipped: a silently-dropped test cannot hide behind an unchanged
  total), rewrites the ledger region in the doc, and FAILS the build on either of the
  two rot modes — a baseline matching no generated test, or a `pinnedBy` class that
  does not exist under src/commonTest. Keyed by baseline FILE name because that is
  exactly one generated subtest (bare/parameterized × errors/emit), all four emission
  sites wired. Self-tested all three paths (valid entry → `@Ignore` + ledger row;
  stale baseline → build fails; missing `pinnedBy` → build fails), then reverted to
  the empty list, which is the healthy state. **Gate: with no entries the generated
  corpus is BYTE-IDENTICAL** (diff -r of the whole generated tree, before vs after),
  so the mechanism costs nothing until used; suite 12,765/0/3 unchanged. (c) is a
  STANDING rule rather than a deliverable, and is written into the doc § 1: every
  "DEAD — regressed N tests" entry in CLAUDE.md and the archive is now a LEAD, and
  re-examining one means re-running the change and classifying its N diffs.
  **The judgement worth keeping:** a form-only diff is a *candidate*, not an
  entitlement — the owner's cost clause ("byte parity is secondary *if it can be
  achieved without extra cost*") means byte parity is still preferred where it is
  free, so a divergence needs a reason it is WORTH having.
- [ ] ~~(PARITY.1) Adopt the logical-parity policy in the gate (original)~~ —
  **owner directive 2026-07-26, and the single biggest unblock in this arc.** "Logical parity is
  important even if we don't reach byte-by-byte parity. If there are tests where we
  diverge but the logic stays the same, create a new test case and switch off the old
  one. The logical value of the compiler output at maximal performance should always
  be the deciding factor; byte-by-byte parity is secondary if it can be achieved
  without extra cost." **What this changes:** a corpus baseline that differs only in
  FORM (union member order, an equivalent message, an equivalent elaboration shape)
  stops being a veto — replace it with a test pinning the LOGIC and disable the old
  one, recording the divergence and why it is equivalent. A baseline differing in
  MEANING is still a hard regression. **Do (a) FIRST, it is cheap and it is what
  makes the rest safe:** (a) add `docs/logical-parity.md` — the form-vs-meaning
  decision procedure, the disable mechanism, and a running LEDGER of every
  switched-off baseline with its justification (an unlogged disable is
  indistinguishable from hiding a regression, so the ledger IS the control); (b)
  extend the generator/harness so a case can be marked logically-divergent with a
  reason string rather than commented out; (c) as engine work proceeds, re-examine
  the "DEAD — regressed N tests" entries in CLAUDE.md and the archive — many were
  never checked for whether the N were form or meaning, so each is now a LEAD.
  **Do NOT** use this to wave through a diff you have not read: the burden is
  demonstrating equivalence per case, in the note.

- [x] **(COST.1) DONE round 717 — `scripts/cost_gate.py`, and the determinism check
  caught a racy counter on its first use.** Runs the compiler profile with
  `--passTiming`, extracts 20 deterministic counters, diffs them against the tracked
  `docs/perf/cost-counters.txt`, fails above ±2% (per-counter), and exits nonzero so it
  drops into the round gate next to the suite. `--update` rebaselines, `--from-log`
  re-parses an existing run (free re-scoring, and how the rebaseline below was done),
  `--tolerance` tunes the bar. Coverage: the front end (pre-parse reuse), the spine
  (nodes walked), the type system (getTypeOfExpression calls/distinct/outside-init,
  narrowing walks, memo serves), type-node resolution (cacheable/hits/bypassed, the
  INV.5(c) mapped cache, fingerprint builds), name resolution (globals
  lookups/conflated/misses) — **plus the compiler's ANSWER (error count, program file
  count), because a cost drop that changes the output is not a win and the gate has
  to be able to see that.** Four counters baseline at ZERO
  (`ctxFingerprint.builds`, `globals.conflated`, `narrow.walksOutsideInit`,
  `preparse.fresh`) and are therefore tripwires: any nonzero value is flagged.
  **Baseline at 41bedb73:** errors 46, spine.nodes 856,962, typeOfExpr 696,933 calls
  over 250,057 distinct nodes, narrowWalks 69,903 (40,546 memo-served), typeNode
  210,397 cacheable / 89,883 bypassed, globals 1,377,511 lookups at 98.9% miss.
  **THE FINDING — the AST census is racy, and it is exactly what (DISPATCH.1) was
  told to derive its table from.** Two runs of the same binary: every counter
  bit-identical EXCEPT the `indexSourceFile` node census, 857,350 vs 854,550
  (−0.33%). `indexSourceFile` runs on the crawl's concurrent parse threads
  (`readAndScanBatch`, Dispatchers.Default, FRONTEND_CONCURRENCY in flight) and
  `PassTiming.nodeKindHistogram` is a plain HashMap, so increments are lost and the
  census always undercounts. Instrumentation-only (no production impact), but it
  means the census is sound for "which kinds dominate" and NOT sound as an exact
  per-kind population. Excluded from the gate (a nondeterministic row teaches people
  to ignore the gate) and warned about at the source in PassTiming.kt; DISPATCH.1's
  derivation needs an exact census — see the note on that item.
- [ ] ~~(COST.1) Enforce the cost gate (original)~~ — **owner-approved 2026-07-26
  ("yes, I want to enforce it, to counter performance regressions").** Round 713 added ~72k
  `getTypeOfExpression` calls (+11.5%, ≈70–200 ms) for one conformance diagnostic and
  nothing noticed, because the round gates are the corpus and `--listAll` and neither
  sees cost. Over 200 rounds that is how ~118 handler consultations per node
  accumulate. **Make it mechanical, not a habit:** a script that runs the compiler
  profile with `--passTiming`, extracts the DETERMINISTIC counters
  (`getTypeOfExpression` calls, `narrowWalks`, `spineNodes`, per-kind enter totals,
  `typeNodeCacheable`/`bypassed`), writes them to a tracked file, and DIFFS against
  the committed baseline — failing loudly above a threshold (start ±2%, tune once
  there is history). Counters, not wall time: they are load-independent, which is the
  whole point (a laptop shows ±13% wall). Wire it into the round protocol next to the
  suite run, and record the baseline in the same commit as any accepted increase,
  with the justification.

- [x] **(BUILD.1) DONE round 720 — owner approved the heap raise; the settled figure is
  5g for the Kotlin daemon plus a 2g→1g cut to Gradle's own, and the from-scratch
  compile now completes in ~6.5 min.** The measured ladder: 2g GC-thrashes forever
  (looks exactly like a hang — zero class files, because Kotlin writes output only at
  the end), 3g dies mid-compile, 4g fails in ~6 min with an explicit `Not enough memory
  to run compilation`, 5g succeeds. Gradle's daemon had to shrink because 5g + 2g
  oversubscribed the 7.7 GB box and the kernel killed the compile daemon mid-run with
  nothing in any log. **Both earlier diagnoses were wrong and are corrected in place:**
  round 719 blamed a local bench loop (those commits are `github-actions[bot]`, remote),
  and rounds 718–719 read "40-minute compiles" that were really ~6-minute ones being
  restarted — the agent's own polling was preempting its builds, so the sleeps never ran.
  Launch long builds DETACHED (`nohup … &`) and wait on ONE timer. Original item below.
- [ ] ~~(BUILD.1) BLOCKED-PENDING-USER: raise the Kotlin daemon heap (original)~~ — **a cold compile
  does not fit in the inherited `-Xmx2g` and HANGS instead of failing.** Measured
  round 717, and it cost that round ~30 minutes. `gradle.properties` sets
  `org.gradle.jvmargs=-Xmx2g`, which the Kotlin compile daemon inherits. An
  INCREMENTAL compile fits; a COLD one does not — and the failure mode is not an
  OutOfMemoryError, it is a GC death spiral that looks exactly like a hang: 350% CPU,
  RSS pinned at the ceiling, `stime` ~5 s against 3,000 s of user time, and **zero
  class files** (Kotlin's backend writes output only at the end, so there is no
  partial progress to read). It ran 14 minutes with no progress; the same build with
  `-Dkotlin.daemon.jvmargs=-Xmx3g` took **2m 33s**. **How you get there:** the
  documented memory ritual before a self-compile — `./gradlew --stop && pkill -9 -f
  KotlinCompileDaemon` — is what makes the next build cold, so the trap is reachable
  from the instructions themselves. **ESCALATED round 718 — this is now the binding
  constraint on the edit-test loop, not a nuisance.** That round diagnosed and wrote a
  complete fix and then could NOT LAND IT, because no compile would finish: `-Xmx2g`
  hung (14 min, zero classes), `-Xmx3g` ran 16 minutes and the daemon died and
  restarted from scratch, and only `-Xmx4g` got the main compile through. Four cold
  compiles were burned in one session. The work is parked on
  `wip/round718-required-minus-optional` purely for want of a gate.
  **REFINED round 719 — the distinction that actually matters is INCREMENTAL vs
  FROM-SCRATCH, not the heap number.** A retry at 4 g on a quiet box sat in
  `compileKotlinJvm` for 40+ minutes with the daemon showing REAL WORK (same PID,
  utime 210 s → 277 s over 2.5 min, RSS 2.38 GB under a 4 GB ceiling, stime ~1.5 s) —
  not the round-717 pinned-at-ceiling spiral. Round 717's **2m 33s** figure for "the
  same cold compile" is NOT comparable: Gradle re-executing the task does not force a
  non-incremental Kotlin compile, so that one ran against warm caches. A genuinely
  from-scratch compile of ~110k lines of Checker.kt plus a 566 KB generated lib file
  simply costs tens of minutes here. (An earlier version of this entry blamed a
  competing local bench loop; that was wrong — the `chore(bench)` commits are authored
  by `github-actions[bot]` and run in remote CI.)
  **So the operational rule comes first, and it is free:** do NOT hard-kill the Kotlin
  daemon (`pkill -9`), because that is what converts a 2-minute incremental compile
  into a 40-minute from-scratch one — the documented memory ritual is the trap.
  **PROPOSAL (owner decision, build-system change = Guardrail):** add
  `kotlin.daemon.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=512m` to gradle.properties, so
  that when a from-scratch compile IS unavoidable it does not thrash at 2 g. Cost: up
  to 2 GB more resident during a compile on a 7.7 GB box, which means a compile and a
  4 g self-compile can no longer overlap. Cost: up to
  2 GB more resident during a compile on a 7.7 GB box — which means a compile and a
  4 g self-compile can no longer overlap, and the memory ritual becomes mandatory
  BEFORE a bench run rather than before a build. That trade is worth stating plainly:
  today the ritual is what CAUSES the hang, and a round can lose an hour to it.
  **A second option worth the owner's consideration:** a bigger box. This one is
  7.7 GB / ~4 cores, the corpus suite takes 7 minutes, a cold compile 3–15, and
  (PERF.HW) already wants ≥8 real cores to answer the parallel-scaling question.
  Workaround until then, recorded in CLAUDE.md: pass
  `-Dkotlin.daemon.jvmargs=-Xmx4g` on the command line.

- [x] **(LIB.1) — DONE. (a) round 730, (b) round 731, (c) round 756.**
  **(c) LANDED round 756, and it took the ZERO-CORPUS-IMPACT design.** TS6046 is now
  raised from `Resolution.unknownNames` — recorded by `bindRealLibs` (the only place
  that KNOWS an entry resolved to no lib file) and emitted by a new
  `pass("checkLibOption")`, one diagnostic per bad entry, message enumerating the valid
  names exactly as tsc does and NOT naming the offender. Position follows tsc's two
  forms: `options.tsconfigOptionPositions["lib"]` for a tsconfig-driven build, file-less
  otherwise (as `tsc --lib bogus` is). **The corpus cannot move by construction** — it
  runs the embedded `BUILTIN_LIB_SOURCE` and never resolves real libs — and that is a
  PIN, not a comment (`control - the embedded lib path stays silent so no corpus
  baseline can move`). *Do not widen it to a raw `options.lib` × `libMap` check: that
  reaches all 259 `@lib:` cases and needs the full (PARITY.1) judgement.* The defect it
  closes: `"lib": ["esnext.arary"]` resolved to NOTHING, the program was checked
  against no lib at all, and not one word was said. **The trap (b) paid for applies
  here too and the control obeys it**: "the good lib name produces no error" passes
  just as happily when the good lib resolved to nothing, so the control is a MEMBER
  probe (`Screen.definitelyNotAMember` must still be TS2339). Verified against
  unmodified HEAD: the three targets FAIL, both controls PASS.
  Pins: `LibOptionUnknownNameTest` (5). Gates: compiler profile `--listAll`
  **byte-identical at 46**; filtered `*LibOption*`/`*RealLib*`/`*Inv0*`/`*Cta*`
  **120 / 0**; corpus slice `_L`/`_M`/`_N` **1,075 / 0**.
  Round-731 background: `Resolution.unavailable` is empty for every resolution
  (pinned), so the "requested but unshipped" case (c) was originally written for is
  gone; an unavailable lib can now only mean a pin bump outran the generator, which
  argues for a build error rather than a user diagnostic.
  Original title: Ship the DOM/webworker libs and stop real builds silently running
  UNCHECKED — owner-approved 2026-07-26 ("yes, please fix it"), PROMOTED out of the
  post-v1 backlog because it is a silent wrong answer, not a missing feature.**
  **(a) IS DONE (round 730): the flip LANDED and it is FREE.** `projectDefaults()`
  (CompilerOptions.kt) now starts both project entry points — `TsConfigLoader.load`
  and `ProjectCompiler.build`'s bare-source-file path — with `useRealLibs = true`;
  the CONSTRUCTOR default stays false so the corpus path is untouched, and a
  tsconfig may still opt out with `"useRealLibs": false`. Gated by a four-arm ×
  8-profile measurement (the table is in the round-730 session note): the embedded
  and real arms are IDENTICAL code for code (46/46/46/46/46/46/46/94), and under
  `types: ["node"]` the real arm is strictly BETTER (server 18 → 13, harness 48 →
  43). The +5 that measurement first found on services/server/harness were ONE
  lib-free defect — a `this` pseudo-parameter counted into `minArgumentCount` while
  dropped from `parameters`, so a `this`-carrying function type was not assignable
  to ITSELF — now fixed at all 19 signature builders.
  **(b) IS DONE (round 731): the DOM / webworker / scripthost sets are SHIPPED** —
  all 108 `src/lib` files, 566 KB → 3.71 MB, plus the `*.generated` →
  distributed-name mapping in `keyToDistFileName`. Proven by MEMBER probes
  (`RealLibsDomTest`: unknown member reported, member type honoured, method arity
  enforced, on all three host sets), every target verified failing on unmodified
  HEAD. **The cost was in the BUILD, not the compiler:** the payload is 3.14 MB
  (not the ~1 MB estimated; `dom.generated` alone is 2.35 MB, 66% MDN comments)
  and emitting it as one generated file OOMs the Kotlin daemon at the BUILD.1 5 GB
  pin after 7m34s — so the emission is split over 16 `RealLibFilesPart*.kt`, after
  which a COLD compile takes 2m25s, i.e. CHEAPER than the old single-file shape at
  a sixth of the payload. Dashboard unmoved (profiles pin `"lib": ["es2020"]`),
  corpus unmoved (still the embedded lib), cost gate +0.00% on every counter.
  **(c)'s zero-risk half landed here too:** `RealLibResolverTest` now pins that
  every `libMap` name and every `ScriptTarget` default resolves with `unavailable`
  AND `unknownNames` empty, and that every distributed lib file name round-trips
  through `distFileNameToKey`/`keyToDistFileName` unchanged (which is what the
  `lib.dom.generated.d.ts` bug above would have tripped).
  **REMAINING: (c)'s user-facing half — and it SHRANK.** With everything shipped,
  `Resolution.unavailable` is EMPTY for every resolution (pinned), so the
  "requested but unshipped" case (c) was written for no longer exists — a
  non-empty `unavailable` can now only mean a pin bump outran the generator, which
  argues for a build error, not a user diagnostic. What is left for the user is
  `Resolution.unknownNames` (a `lib` entry not in `libMap` at all — **zero
  consumers today**; tsc reports TS6046). **Its corpus risk depends entirely on
  where it is emitted, and that is (c)'s real design decision:** the corpus runs
  the EMBEDDED lib and never consults `RealLibResolver`, so a diagnostic raised
  from the real-lib resolution path moves ZERO baselines, while one raised from a
  raw `options.lib` × `libMap` check reaches all 259 `@lib:` cases. See the
  round-731 note.
  THE DEFECT (measured rounds 687–688, FIXED in (b) above): `RealLibFiles` shipped no
  `dom.generated`/`dom.iterable.generated`/`webworker*`, so `"lib": ["dom"]` records
  the file in `Resolution.unavailable`, which **nothing outside RealLibs.kt ever
  consumes** — no diagnostic, no failure. Consequence on a 3-line program:
  `HTMLElement` resolves, `document` resolves, and `e.definitelyNotAMember` on an
  `HTMLElement` parameter **compiles CLEAN**. A browser project gets a green build
  with its DOM code entirely untyped. **Worse, and the real root:** `useRealLibs`
  defaults FALSE and NOTHING in the project path sets it (`ProjectCompiler` /
  `TsConfigLoader` never do; the only writer is a test directive), so **every real
  build — all 8 dashboard profiles included — runs on the curated embedded
  `BUILTIN_LIB_SOURCE`** and the whole real-lib machinery is test-only. The owner has
  now authorised the generation change the round-688 note left owner-gated.
  **(a) IS NOW MEASURED (round 717) — the answer is affordable, and the number is 35.**
  No code was needed: every `compilerOptions` key flows through `applyDirective`, so
  `"useRealLibs": true` in the bench project's tsconfig flips the whole real-lib path
  on. Four arms of the `compiler` profile, `--noEmit --listAll`:
  | libs | `types` | errors | composition |
  |---|---|---:|---|
  | embedded | `[]` | 46 | the dashboard number — ALL env-legit |
  | real | `[]` | 81 | +33 node globals (`process`/`global`), +35 real |
  | real | `["node"]` | 48 | 13 env + **35 real** |
  | embedded | `["node"]` | **13** | 13 env (TS2591 only), nothing else |
  So the real-lib switch costs **exactly 35 checker FPs** — TS2722 ×11 ("Cannot
  invoke an object which is possibly 'undefined'" — a narrowing gap on lib members
  the curated lib declared non-optional), TS2322 ×8, TS2345 ×4, TS2344 ×4, TS2339 ×4,
  TS2349 ×2, TS2769, TS2739 — and **no measurable wall time** (28.7 s, inside the
  band). Two corollaries worth having: (i) today's "46 FPs, env-legit only" is
  13 stub-residue + 33 node globals, confirmed by arm D collapsing to 13; (ii) the
  embedded lib is quietly MORE PERMISSIVE than the real one, which is what makes the
  silent-unchecked defect possible in the first place.
  **DECISION for (a), on that evidence: a real project build should use the REAL
  libs** — the mismatch is the root defect, the cost is bounded and enumerable rather
  than open-ended, and it buys faithfulness on every future project. Sequencing:
  burn the 35 down FIRST (they are ordinary FP work, TS2722 being over a third of
  them and probably one narrowing shape), THEN flip the default, so the dashboard
  never goes red. Re-measure services/server/harness before flipping — this is the
  `compiler` profile only, and the bigger profiles will have their own deltas.
  Raw logs: the four arms were run at 8100a78e; reproduce by adding
  `"useRealLibs": true` to `build/bench/tsc-project-*/tsconfig.json`.
  **(a1) LANDED round 720 — 11 of the 35 gone; real-lib FPs 35 → 24, and the fix is
  FREE (every cost counter unchanged). Corpus 12,765 → 12,770 / 0 / 3 (+5 pins), the
  embedded-lib profile still 46.** THE DEFECT: `Parser.kt`'s mapped-type modifier
  scan records `-?` as a plain `?`, so `Required<T> = { [P in keyof T]-?: T[P] }`
  behaves exactly like `Partial<T>` — inverted. `-readonly` got its own flag in M1.10;
  the `?` analogue was never done. THE MECHANISM IS NOT THE OBVIOUS ONE: TS2722 does
  not look at the member TYPE for `| undefined` (the codebase deliberately never adds
  it — the emitter says so), it gates on `isOptionalProperty(propSym)`, and a
  homomorphic mapped member CARRIES ITS SOURCE DECLARATION for related info, so the
  source's `?` is what it sees. The fix mirrors M1.10 exactly: a
  `mappedRequiredMemberIds` side-channel, probed in `isOptionalProperty` only when the
  declaration says optional (preserving the documented hot-path property).
  **Two dead ends worth not repeating, both caught by CONTROLS:** hand-rolling the
  mapped types locally (`type MyRequired<T> = { [P in keyof T]-?: T[P] }`) does not
  reproduce anything — we emit NOTHING for user-defined mapped types, so the controls
  came back empty and the target assertions passed vacuously; and asserting
  assignability through `Partial` measures an axis we do not model. The live repro
  needs `@useRealLibs` plus TS2722 assertions. Verified against unmodified HEAD: the
  target fails, the control passes.
  **Also learned:** the embedded lib declares NO utility types at all, which is why
  the whole family is invisible on the default path — `Required<…>` is an unresolved
  name degrading to `any`, and `any` is silent. That is the LIB.1 defect in miniature.
  **(a2) NEGATIVE RESULT round 721 — do NOT re-run this one.** The three TS2322 whose
  source and target print identically (`NodeArray<T>` → `NodeArray<T>`;
  `WatchCompilerHostOfFilesAndCompilerOptions<T>` → a union it is the first member of)
  are NOT a generic-self-assignability bug: five probes covering self-return,
  constrained self-return, member-of-union and return-through-a-local all pass, with a
  live control. Pinned as `GenericSelfAssignabilityTest`. **The surviving lead is
  symbol-keyed members**, because these FPs exist only under real libs and the same run
  reports TS2739 "missing `[Symbol.iterator]`, `[Symbol.toStringTag]` from `Set<T>`" —
  and `NodeArray<T> extends ReadonlyArray<T>`, which carries exactly those in the real
  lib but not in the curated one. Probe that under `@useRealLibs`.
  **(a3) LANDED round 723 — computed well-known-symbol keys in an object literal are
  members now; real-lib FPs 24 → 22.** `getTypeOfObjectLiteral` named a computed key via
  `computedLiteralKey(n) ?: continue`, which accepts only string/numeric literals — so
  `[Symbol.iterator]: …` was DROPPED and the target then reported it missing. New
  `computedSymbolKey` names a DOTTED path (`Symbol.iterator`) as `[Symbol.iterator]`,
  matching how symbol members are named everywhere else (it is what TS2739 prints);
  wired into BOTH the PropertyAssignment and MethodDeclaration branches — the method
  form did not handle computed names at all. Deliberately NOT applied to a bare `[foo]`
  dynamic key, since naming that would let any literal satisfy a symbol-keyed target —
  pinned by its own control. **It fixed one MORE than predicted:** TS2739 ×1 → 0 AND
  TS2322 8 → 7, so a dropped member was failing an assignability check too. Corpus
  12,775 → 12,781 / 0 / 3. **COST.1 tripped and was accepted:** `mapped.keyed` +3.12%,
  `mapped.hits` +6.66% (+744 keyed lookups, +372 hits, ≈1.6 ms) — the direct price of
  typing members we previously discarded, and the hits rose faster than the keys, so the
  added lookups are mostly the cheap kind. Rebaselined in the landing commit.
  **(a5) LANDED round 725 — the TS2344 family is gone; real-lib FPs 22 → 18 (arm C
  35 → 31, both sides MEASURED), and the fix is FREE (all 20 cost counters +0.00%).
  Corpus 12,781 → 12,788 / 0 / 3 (+7 pins), embedded-lib profile still 46.** THE DEFECT:
  the real lib declares `NonNullable<T> = T & {}`, so a `Visitor<NonNullable<TIn>, …>`
  type argument resolves to `Intersection[TypeParam(TIn), {}]` — and
  `checkConstraintsForTypeArgs` applies the constraint chain only to a BARE
  `Type.TypeParam` arg (and to a `Type.Union` arg since 440-b), never through an
  intersection. Round 724's instrumentation-free hypothesis ("we compare against the RAW
  constraint `Node | undefined` and just need to strip the nullish part") is DISPROVEN by
  the case where TIn is already non-null: it failed identically, because the constituent's
  constraint is not consulted AT ALL. The relation cannot cover it either — the engine has
  no TypeParam-source-via-constraint rule on purpose (round 456 measured adding one as
  net-zero and reverted), so this is the THIRD arm of a per-emission-site rule that
  already had two. THE FIX: `intersectionSatisfiesViaTypeParamConstraint`, evaluated only
  inside the already-failing branch (hence zero cost), takes each TypeParam constituent's
  constraint (`T ⊆ constraint(T)`) and compares it; a SEPARATELY GATED second step drops
  the constraint's nullish members when an `{}` constituent is present, since `X & {}` is
  tsc's non-nullish marker. Non-TypeParam constituents are left to `checkTypeRelatedTo`,
  which already does "some constituent relates" plus its merged-contradiction guard.
  **THE TRAP, pinned by four negative controls:** the blanket
  `argType is Type.Intersection -> continue` passes every target case and silently deletes
  a real diagnostic class — controls cover an intersection with no type parameter
  (`A & B`), `NonNullable` of an unconstrained parameter, `NonNullable` of a parameter
  constrained to an unrelated type, and a nullable-constrained parameter used WITHOUT
  `NonNullable` (which proves the strip is tied to the `& {}` marker, not applied to every
  constraint). The four fixed sites are parser.ts:3491/3492 and visitorPublic.ts:124/144.
  **REMAINING 18, by code:** TS2322 ×7, TS2345 ×4, TS2339 ×4, TS2349 ×2, TS2769 ×1 —
  the TS2322 group is the one with two eliminated hypotheses already ((a2), (a4)); per
  that note, dump the actual relation failure rather than guess a third time.
  **(a6) LANDED round 726 — the TS2322 family is mostly gone; real-lib FPs 18 → 14 (arm C
  31 → 27, both sides MEASURED, TS2322 ×7 → ×3, no other code moved). Corpus 12,788 →
  12,795 / 0 / 3 (+7 pins), cost gate PASSES (largest counter −0.68%, an improvement),
  embedded-lib profile still 46.** THE DEFECT, found by DUMPING the relation as the note
  above demanded: `getTypeFromTypeReference` built a `Type.Reference` only when EVERY type
  argument resolved, and otherwise returned `getDeclaredTypeOfSymbol(Iface)` — the RAW OPEN
  GENERIC, which carries its own type parameter and relates to no `Type.Reference`. A
  return annotation is resolved with `currentTypeParamScope == null`, so the function's own
  `T` came back errorType and the annotation silently became the open generic — hence the
  identical-looking `NodeArray<T>` → `NodeArray<T>`: the display renders the ANNOTATION,
  the comparison used the RAW GENERIC. THE FIX mirrors tsc (whose `errorType` is
  Any-flagged and instantiates regardless): substitute `any` for unresolved argument
  positions and instantiate anyway; TS2304 already reports the name. Sites: parser.ts:3583,
  watchPublic.ts:371/383, utilities.ts:12378. **THE PROBE TRAP, and why (a2)/(a4) missed
  it:** the degradation is invisible unless the interface reaches its TP through a GENERIC
  BASE (`extends ReadonlyArray<T>`); a flat `interface Box<T> { v: T }` relates to its own
  raw form anyway, so a pin built on one is silent before AND after — verified by running
  the pin against unmodified HEAD and getting byte-identical output. Three negative
  controls guard the fix (a resolvable-but-wrong argument still errors, an unresolvable one
  masks neither an unrelated source nor missing members). **REMAINING 14:** TS2345 ×4,
  TS2339 ×4, TS2322 ×3, TS2349 ×2, TS2769 ×1 — the three TS2322 are three DISTINCT causes
  (parser.ts:3558 an `Intersection[TP & {}]` type ARGUMENT, utilities.ts:4258 an `Exclude<…>`
  conditional, program.ts:1366 an object literal vs `Partial<…>`), so pick the next family
  by cause, not by histogram height.
  **(a7) LANDED round 727 — the TS2339 family is gone; real-lib FPs 14 → 10 (arm C 27 →
  23, both sides MEASURED, TS2339 ×4 → 0, no other code moved). Corpus 12,795 → 12,799 /
  0 / 3 (+4 pins), cost gate PASSES (largest counter −0.68%, an improvement),
  embedded-lib profile still 46.** THE DEFECT: `narrowByCallPredicate`'s SINGLE-TYPE
  positive branch compared the reference against the guard target AS A WHOLE
  (`candidate <: t ? candidate : t <: candidate ? t : candidate`) — so when the target is
  a UNION and neither whole-union relation holds, it handed back the ENTIRE candidate
  union. tsc's `getNarrowedType` never does that: it `mapType`s over the CANDIDATE,
  keeping per constituent whichever of `t`/`c` is the subtype and dropping `c` when
  neither direction relates. Live shape: esDecorators.ts `visitAssignmentElement`'s
  `isAssignmentExpression(node, true) && isNamedEvaluation(node, …)`, where
  `NamedEvaluation` is a 9-member union of unrelated `X & {…}` intersections — every
  later `node.left`/`node.right`/`node.operatorToken` resolved on that union's tiny
  common property set. THE FIX filters the candidate constituents, reached ONLY on the
  previously-`else` path (both relating branches stay byte-identical) and falling back to
  the whole union when nothing survives. Sites: esDecorators.ts:2066/2069/2070/2071.
  **(a8) LANDED round 727 (same session) — the TS2345 group too; real-lib FPs 10 → 7 (arm
  C 23 → 20, both sides MEASURED, TS2345 ×4 → ×1, no other code moved). Corpus 12,799 →
  12,804 / 0 / 3 (+5 pins), cost gate byte-identical to the (a7) run, embedded-lib profile
  still 46.** THE DEFECT: a CONSTRUCT SIGNATURE's own type parameter escaped into the
  new-expression's type. The real lib declares
  `interface SetConstructor { new <T = any>(values?: readonly T[] | null): Set<T> }`, so
  `new Set()` yielded `Set<T>` — the raw signature TP — and
  `(state.hasCalledUpdateShapeSignature ||= new Set()).add(path)` resolved `.add` on the
  UNION `Set<Path> | Set<T>`, where the B516 union-of-callables rule CORRECTLY intersects
  the two parameters into `Path & T`. The combining rule was never the bug; the leaked TP
  was. THE FIX substitutes an uninferred signature TP's DECLARED DEFAULT (what TypeScript
  specifies), only for TPs that HAVE one and only at the return reference's top level — a
  defaultless TP keeps today's behaviour. This is the construct-signature analogue of the
  B56.1 rule already applied to a generic CLASS callee, which cannot fire here because a
  constructor interface carries no type parameters of its own. Sites: builderState.ts:396/457,
  resolutionCache.ts:1109. **WHY IT IS LOW-RISK ON THE CORPUS:** the EMBEDDED lib declares
  `SetConstructor { new(): Set<any> }` — non-generic — so no corpus baseline can move
  through `Set`/`Map` at all; the pins therefore declare their own constructor interface.
  **REMAINING 7:** TS2322 ×3, TS2349 ×2, TS2769 ×1, TS2345 ×1 — the last TS2345 is
  utilities.ts:12082, a lone exhaustive-switch `assertType<never>(node)`, i.e. a
  full-switch-narrowing question with nothing in common with the three just fixed.
  **(a9)/(a10)/(a11) LANDED round 728 — THREE causes in one session; real-lib FPs 7 → 3
  (arm C 20 → 16, both sides MEASURED: TS2349 ×2 → 0, TS2769 ×1 → 0, TS2322 ×3 → ×2, no
  other code moved). Corpus 12,804 → 12,822 / 0 / 3 (+18 pins), cost gate PASSES with the
  largest counter −0.68%, embedded-lib profile still 46.** (a9) TS2349 ×2: the B516
  combining gate required every parameter to be REQUIRED, so a trailing optional
  (`forEach(cb, thisArg?)`) sent the union down the "none of those signatures are
  compatible" path; tsc's `combineSignaturesOfUnionMembers` takes the longest parameter
  list, intersects position-wise and maxes the minArgumentCount. Only three corpus
  baselines pin that message and all survive on other gates. (a10) TS2322 ×1: the mapped
  materializer never recorded the plain `?`, so `Partial<T>`'s member of a required source
  property stayed required — round 718's `-?` defect in the mirror, marked by a
  `SymbolFlags` BIT because that arm is the hot one. (a11) TS2769 ×1: an object literal's
  string-literal property widens to `string`, and overload resolution compares each
  candidate against the RAW argument type while the single-signature path contextually
  types it — the `overloadingOnConstants2` rule now has a per-property analogue, evaluated
  only inside the already-failing branch.
  **(a12)/(a13) LANDED round 729 — THE BURN-DOWN IS FINISHED: real-lib FPs 3 → 0 (arm C
  16 → 13, both sides MEASURED; the 13 that remain are ALL env-legit TS2591). Corpus
  12,822 → 12,842 / 0 / 3 (+20 pins), cost gate PASSES UNCHANGED TO THE DIGIT at every
  step, embedded-lib profile still 46.**
  (a12) parser.ts:3558 — **round 728's TYPE-PARAMETER NAME COLLISION reading is
  DISPROVEN.** One dump at the inference-time constraint check shows both arms of that
  four-case matrix failing IDENTICALLY (`Isect[TP(T)#38[c=NodeX], {m:1}]` vs `NodeX`,
  under either name); the renamed variant was only ACCIDENTALLY clean, its equally
  un-inferred return type swallowed downstream. The cause is round 725's rule at a THIRD
  site: `tryInferSingleTypeParamFromArgs` asked "does the inferred candidate satisfy the
  constraint" with a bare `checkTypeRelatedTo`, and on failure bails WHOLESALE — so the
  callee's return type comes back UN-INSTANTIATED and the mismatch surfaces as a TS2322
  whose two sides print nearly the same text. That display is what misled two rounds.
  (a13) utilities.ts:4258 AND utilities.ts:12082, ONE cause — **`Exclude<T, U>` was an
  IDENTITY FUNCTION.** The distribution loop evaluated each constituent's branch under the
  UNSHIFTED alias-argument map, so `T` in a branch still meant the whole union and every
  non-matching constituent handed it all back. The `assertType<never>` site round 728
  rated least tractable and failed to reproduce twice was never a narrowing question:
  `HasInferredType` is built with `Exclude`. Only a NAKED type parameter distributes,
  which bounds the rebinding. **TWO COMPANION DEFECTS, both surfaced by the first arm-C
  measurement of that change and neither optional:** enum-member types do not discriminate
  in our relation at all, so a working `Exclude` DROPPED sibling AST interfaces
  (`Exclude<PropertyName, PrivateIdentifier>` lost `Identifier` — two brand-new FPs at
  factory/utilities.ts:1056/1061), closed by applying round 472's `.kind` DOMAIN veto
  inside the conditional evaluator; and a USER alias shadowing a lib one lost to
  `firstOrNull`, so a local `type Omit` resolved through the lib body (its own pin had
  been passing only because Exclude was inert). **The enum-member relation gap itself is
  NOT fixed — it is queued as (REL.1) at the top of the queue.**
  **ALSO LANDED round 729, not on this list:** round 725's rule now has all THREE arms —
  `checkCallTypeArgConstraints` (the EXPLICIT `f<NonNullable<U>>(…)` site round 728 found
  in passing) shares the same helper as the type-reference and inference sites.
  **(a14) LANDED round 730 — the measurement AND the flip.** The four-arm × 8-profile
  table is in the round-730 session note and is the baseline every future round compares
  against. The bigger profiles carried exactly 5 non-env diagnostics, all TS2322 and all
  ONE cause: `buildSignatureForFunctionLikeTypeNode` counted the `this` pseudo-parameter
  into `minArgumentCount` while `getParameterSymbols` dropped it from `parameters`, so
  `minArgumentCount` EXCEEDED `parameters.size` and every arity gate read the signature as
  "target provides too few arguments" — a `this`-carrying function type was not assignable
  to ITSELF (tell: the self-contradictory "Expected 3 or more, but got 3"). The same
  builder also zipped the surviving symbols POSITIONALLY against the declaration list,
  shifting every parameter type by one. Round 460 had fixed both at the function-
  DECLARATION site only; the arity rule is now one `requiredParameterCount` helper at all
  19 builders (16 were counting `this`). Invisible under the embedded lib (it declares no
  `this` parameters); under real libs it made every `Array`/`ReadonlyArray` member taking a
  `thisArg` mutually non-assignable, and through them `SortedArray<T>` vs
  `SortedReadonlyArray<T>` and `T[][]` vs `any[][]`. **NOT predicted:** the second symptom's
  chain blamed `concat(...)` — a message chain names where the structural walk STOPPED, not
  what broke. Cost gate rebaselined: `mapped.keyed` +3.81% is the price of resolving the
  real lib's mapped utility types (the embedded lib declares none), `typeNode.bypassed`
  −7.74% is an improvement, `spine.nodes`/`globals.lookups`/`output.errors` unmoved.
  **NEXT for this item: (b) and (c) below — both now unblocked by the flip.**
  **(a4) SECOND HYPOTHESIS ELIMINATED:** an interface extending `ReadonlyArray<T>` IS
  self-assignable (live control). With (a2), both explanations for the parser.ts:3583 /
  watchPublic.ts:371 TS2322 are closed — do NOT guess at generic identity a third time;
  dump the actual relation failure for one of them instead.
  **ORDER — the original (a) framing, now answered above:** (a) decide what a real
  project build uses for libs at all (the embedded lib is a curated subset; the shipped real
  libs are unreachable outside tests — that mismatch is the root, and it is a design
  choice); (b) ship the DOM/webworker/scripthost sets (changes real-lib generation in
  build.gradle.kts, ~1 MB of generated source); (c) report a user-REQUESTED lib that
  is unavailable — **`Resolution.unavailable` is NOT the right key** (a `full` default
  lib transitively references DOM/host files, so an ordinary target-default resolution
  has a non-empty `unavailable` and must stay silent); it needs a new
  `unavailableRequested` field, and a working implementation is in the round-688
  reflog. **TRAP that wasted a round:** the control "does `HTMLElement` resolve?"
  PASSES while everything is broken — when an unknown name degrades to `any`, name
  resolution proves nothing. The decisive control is a MEMBER probe
  (`e.notAMember` must error). **CORPUS IMPACT, now unblocked by (PARITY.1):** 259
  corpus cases carry `@lib:`, of which 23 request `dom` plus webworker×4 and others,
  all currently green because we silently ignore the request; reporting on the
  embedded path moves ~30 baselines. Under the byte gate that blocked this item;
  under logical parity, judge each as form-vs-meaning and re-pin.

- [x] **(DISPATCH.1) Per-kind handler dispatch table — DERIVED AND FALSIFIED
  (round 732). Steps (a) DONE; (b)/(c)/(d) CLOSED — do NOT land the dispatch as
  specified.** The table was derived by instrumentation, not guessed, and
  verified by running the WHOLE corpus suite with it applied (12,882 / 0 / 3)
  plus a byte-identical compiler-profile `--listAll`. **The measured prize is
  883 ms of an 18.5 s spine — an UPPER bound, inflated by the probe's own
  `when(h)` indirection; production-realistic is ~100-300 ms (0.3-1%), against
  the 1.0-2.5 s / 6-14% this item predicted.** The item's own falsification
  clause therefore applies: *"If a landed slice measures below the drift band
  AND the per-kind counters do not fall, the premise is wrong — say so and
  stop."* Consultations do fall (59/node -> 21.65/node, 64% removed) — and
  **64% of the consultations are 4.8% of the time.**
  **THE MECHANISM OF THE ERROR (for the next estimate):** round 716 inferred
  consultation overhead from "IDENTIFIER costs 2,746 ns/node and almost no
  handler wants an identifier". In fact 22 of the 59 handlers ACT at an
  identifier — the ones keyed on PARENT edges, FRAME-owner identity and nodeId
  REGISTRIES cannot be closed by the node's own kind, and they are also the
  expensive ones. The "skip `spineEnterNode` for bare Identifiers ->
  byte-identical" probe skipped real work the compiler profile happens not to
  need; it never measured consultation.
  **WHAT LANDED AND STAYS:** `SpineDispatch.kt` (the opt-in `--dispatchProbe` /
  `--dispatchGated` harness, behaviour-free when off), the by-id twins
  `spineEnterHandlerById`/`spineLeaveHandlerById`, `spineEnterKindDispatch`,
  the three named `spineCtaM3*Anchor` handlers, 23 `SpineDispatch.work()`
  probes, and `SpineDispatchProbeTest`. The derived table, its per-handler
  soundness justification, the OPEN list and the reproduction steps are in
  **`docs/perf/dispatch-table.md`** — read that before proposing anything
  shaped like this again.

- [x] **(SPINE.1) Attribute and shrink `cpaSpineLeave` + `ccetSpineLeave`** —
  **step (a) DONE round 733, and it FALSIFIES the item; step (b) must NOT be
  landed as specified.** The intra-handler attribution (`--spineSections`,
  compiler profile, 856,962 nodes) shows the item's premise is wrong on both
  counts. (1) These handlers are NOT "legacy-parity frame bookkeeping":
  **88.4% of their time (7,241 of 8,195 ms) is the cpa and ccet passes' OWN
  checking work** — `checkPropertyAccessInExpr` and
  `checkSingleCallExpressionTypes` inside the frame-ambient block. The ambient
  install+restore is 360 ms and the whole non-work scaffolding ~950 ms. (2)
  The named target, the ancestor climbs, is **176 ms** (`cpaM2ChainOk` 77 +
  `cpaM2StmtPosition` 8 + `ccetM3ChainOk` 91) — the 1-3 s prediction is wrong
  by 6-17x, and § 0's law forbids the memo (932 ns per climb over mean
  ancestor depth 6). The parent-kindId dispatch axis prices at <=60 ms. Full
  per-section table, the work/scaffolding split and reproduction steps:
  **`docs/perf/spine-leave-attribution.md`** — read it before proposing
  anything shaped like this again. LANDED: `SpineSections` + `--spineSections`
  (opt-in, behaviour-free when off) and `SpineSectionProbeTest`. NOT landed:
  any optimisation — every candidate measures below the 560 ms drift band of a
  28 s compile.

- [x] **(CALL.1) Attribute INSIDE `checkSingleCallExpressionTypes` — 2.9 s,
  53.6 us per CallExpression, the largest per-node cost measured anywhere in
  this compiler (round 733).** It is a **920-line straight-line function with
  18 `diagnostics.add` sites and 7 `run{}` blocks, executed in full for every
  one of the program's 52,413 call expressions** (2,931 ms over 52,413
  anchors, minus a 2.3 us ambient install). Unlike the cpa anchors — which
  walk a statement subtree, so tens of us is expected — this is per-NODE, and
  ~10% of a ~28 s compile sits in one function. **STEP (a): attribute by
  section, do not guess.** The `SpineSections` harness from round 733 is the
  model: split the function's top-level regions with a running timestamp
  (opt-in, behaviour-free when off, pinned by its own test) and separate the
  type-system calls (`getCalleeType`, `getCallSignaturesOfType`,
  `checkArgumentsAgainstSignature`, `checkTypeRelatedTo`) from the per-call
  PRE-work each emission site does before it knows whether it will fire
  (`getLineAndCharacterOfPosition` x22, `expressionTrueEnd` x16,
  `typeToString` x12, the `cjsDefaultNsShapes`-style per-call map builds).
  **EXPECTED VALUE, stated so a future round can falsify it:** if the
  never-firing emission sites' pre-work dominates, hoisting it behind a cheap
  pre-test removes **1-2 s**; if the cost is in signature resolution and
  argument relations, it is genuine type-system work and the lever is the
  relation engine (M3.1), not this function — say so and stop.
  **A CAUTION carried forward from rounds 732 and 733:** both of those rounds
  predicted a lever from a plausible reading of an aggregate and were wrong by
  5x and 6-17x respectively. Price the population BEFORE building anything;
  counters decide, `scripts/ab-interleaved.sh` medians AND win rate confirm.
  Gate: corpus suite + `--listAll` + `cost_gate.py`.
  **>>> DONE round 734. THE MEASUREMENT CHOSE BRANCH B, by 4x. <<<**
  **78% of the function is type-system work** (2,007 of 2,564 ms raw):
  `checkArgumentsAgainstSignature` **1,357 ms (53%)**, `getCalleeType`
  **474 ms (18%)**, the TS2793 impl probe 101, `checkArgumentsAgainstOverloads`
  53, `getCallSignaturesOfType` 19. **Branch A is disproved twice over.**
  STATICALLY: all 22 `getLineAndCharacterOfPosition`, all 17
  `expressionTrueEnd` and all 11 `typeToString` sites are DOWNSTREAM of the
  emission decision (16 literally inside `if (length > 0) {`) — there is no
  pre-gate work to hoist, the gates already ARE the cheap pre-test the item
  proposed adding. DYNAMICALLY: everything non-type-system totals **557 ms**,
  ~70 ms of it the probe, so the theoretical maximum prize is **~490 ms = 1.6%
  of a 30.5 s compile — inside the +-2% drift band (~610 ms), i.e. smaller than
  the noise that would have to measure it.** NOTHING was landed but the
  harness. Full derivation, the exit profile (half of all 52,413 invocations
  are discarded at the any/error bail AFTER `getCalleeType` has run; the
  240-line `signatures.isEmpty()` branch is never entered on this profile) and
  the two calibration artifacts: **`docs/perf/call-expression-attribution.md`**
  — read it before proposing anything shaped like this again. LANDED:
  `CallSections` + `--callSections` (opt-in, behaviour-free when off) and
  `CallSectionProbeTest`. Follow-on: **(CALL.2)** below.

- [x] **(CALL.2) DONE round 735 — the attribution landed with
  `docs/perf/argument-check-attribution.md` (checkbox reconciled round 754; the
  ">>> DONE round 735 <<<" verdict below had been written without ticking the box).
  Attribute INSIDE `checkArgumentsAgainstSignature` — 1,357 ms
  over 22,145 calls = 61 us each, now the largest single measured cost in this
  compiler (round 734).** It is a **1,534-line function** — larger than the one
  (CALL.1) attributed — and it is 53% of `checkSingleCallExpressionTypes`,
  which is itself ~10% of the compile. **STEP (a): attribute by section, do not
  guess** — the `CallSections` harness generalises (it needs only new section
  constants), and its two calibration traps are recorded in
  `docs/perf/call-expression-attribution.md` § 2: a boundary costs ~90 ns
  measured DIFFERENTIALLY (N sections vs the same code as 1 span), while a
  back-to-back empty span reads 3x that because a `repeat` loop's back-edge
  safepoint poll — and, even unrolled, an invocation's first timestamp read —
  attracts stop-the-world attribution. **The split to price: argument TYPE
  computation vs RELATION work.** Whole-compile counters for the same run put
  `getTypeOfExpression` at 3,911 ms / 701,736 calls (recompute x2.7) and
  relations at depth 0 at only 699 ms, so the prior is that most of the 61 us
  is arg-type computation rather than `checkTypeRelatedTo` — **state that as
  the falsifiable expectation and let the measurement decide.** Secondary, from
  the same round: `getCalleeType` is 474 ms and **half its results are
  discarded** at the any/error bail three sections later (26,496 of 52,413) —
  ask whether that verdict is knowable more cheaply than by resolving; this is
  NOT a caching question (ARCHITECTURE-RETHINK § 0 closed those). **CAUTION
  carried forward from rounds 732, 733 and 734:** all three predicted a lever
  from a plausible reading of an aggregate and were wrong by 5x, 6-17x and
  >=2x. Price the population BEFORE building anything; counters decide,
  `scripts/ab-interleaved.sh` medians AND win rate confirm. Gate: corpus suite
  + `--listAll` + `cost_gate.py`.
  **>>> DONE round 735. THE PRIOR HOLDS BY 48x — AND ITS EVIDENCE MISNAMED THE
  MECHANISM. <<<** The split the item asked for: argument TYPE computation
  **924 ms** of the function's 1,624 ms raw, against **19 ms** for the whole
  `checkTypeRelatedTo`+TS2345 section (**10 ms** for the relation call itself).
  But the prior reasoned from `getTypeOfExpression`, and inside this function
  that is only **196 ms (12%)** while **flow narrowing is 600 ms (37%)** —
  9,615 walks at 62 us, of which the B469 union-argument site is 284 ms/2,339
  walks and the M3.4 site 316 ms/7,271. **So (CALL.2) does NOT reach section 0.1
  stage 3 (the `getTypeOfExpression` x2.7 recompute): this function types each
  argument exactly ONCE and makes 5.3% of the compile's calls at the
  compile-mean cost, so it is not a recompute site. It reaches stage 4.**
  Secondary answers: 86% of the narrowing walks (8,299) return the INPUT type,
  worth at most 237 ms; and only 10,146 of 38,247 loop iterations (27%) reach
  the assignability check at all, yet all 37,379 pay the full `argType`
  computation because every intervening block consumes it. NOTHING was landed
  but the harness. Full derivation, the exit profile, the three disproved
  mechanisms and the calibration mode:
  **`docs/perf/argument-check-attribution.md`**. LANDED: `ArgSections` +
  `--argSections`/`--argSectionsCoarse` (opt-in, behaviour-free when off), the
  compile-wide narrow-walk histogram in `PassTiming`, and
  `ArgSectionProbeTest`. Follow-on: **(CALL.3)** below.

- [x] **(CALL.3) Attribute INSIDE a monster narrowing walk — DONE round 736,
  and it LANDED THE ARC'S FIRST WIN: `-4.53%` median, B wins 6/6, outside the
  +-2% band by 2.3x.** Both numbers the item demanded were measured first.
  (i) A `>= 1 ms` walk arrives at **1,900 flow nodes but only 214 DISTINCT**
  ones — revisit factor **8.85 against 1.48** for a typical walk; the tail is
  the same small graph walked nine times, not a bigger graph. (ii) The
  per-arrival split: **51% of the whole narrowing population is
  `applyConditionNarrowing`** (1,412 ms / 759,784 calls / 1,858 ns), and the
  tail's arrivals are 6.3x costlier because of their MIX — `FlowCondition` 41%
  of tail arrivals vs 18% overall, `FlowBranchLabel` 22% vs 9%, cheap
  `FlowCall` pass-throughs 57% -> 19%. **THE FIX:** `NarrowFlowMemo.served`
  required `depth <= storedDepth`, costing 631,585 recomputes compile-wide
  (426,753 at `FlowCondition`); an entry now carries `hi` = the max depth its
  own subtree reached and also serves when `depth + (hi - storedDepth) <
  NARROW_MAX_DEPTH`, which is the exact condition under which a fresh
  computation cannot trip and therefore provably reproduces the value. Result:
  invocations -55%, arrivals -26% with DISTINCT UNCHANGED, the `>= 1 ms` tail
  429 -> 230 walks and its arrivals -96%. Suite **12,910 / 0 / 3**, `--listAll`
  byte-identical, four cost counters FELL and were rebaselined. LANDED:
  `NarrowSections`/`NarrowProbe` + `--narrowSections{,Coarse}` (opt-in,
  behaviour-free when off), the memo height disjunct in both walker mirrors,
  `IntKeyMapTest` height pins and `NarrowMemoDepthTest`. Full derivation, the
  soundness argument and the two priced-and-rejected candidates:
  **`docs/perf/narrow-walk-attribution.md`**. Follow-on: **(CALL.4)** below.

- [x] **(CALL.4) DONE round 755 — CLOSED AS A MEASUREMENT, and the item's OWN defining
  number had halved while it sat in the queue.** Round 736's "33,307 genuinely-narrowing
  calls at 21,708 ns = 723 ms" is now **21,970 at 20,085 ns = 441 ms**: the per-call cost
  is stable (-7.5%), the COUNT fell **34%** while total calls ROSE 2.5% — downstream of
  (REL.1)/round 754 changing what the declared types are. **The split: 80% of a narrowing
  call is `narrowByCallPredicate`** (351 ms over 23,138 calls at 15,181 ns), then
  `narrowByEquality` 50, `narrowByTruthiness` 16, everything else <= 6; **the dispatcher's
  own residue is 9 ms = 2%**. Replicated across two independent timing runs (the 80% moves
  1.2 points while small rows move +/-25%). **NOTHING LANDED, correctly**: the whole
  population is **441 ms = 1.6%** against a band **re-derived this session at +/-2.0%**
  (5 null pairs: median -0.05%, range [-526, +569] on 26,778 ms) — *smaller than one A/B
  pair's noise* — and a perfect memo over the whole leaf is capped at 1.75%, in-band before
  it costs anything. The 80% is type-predicate RESOLUTION, i.e. M3.1 work, not machinery.
  **Two corrections to round 736**: the rejected "does this condition mention the name"
  pre-test is also **UNSOUND** (99,002 identity calls are the aliased-condition path, whose
  point is a condition that does not mention the reference; 1,873 of them narrow), and the
  identity majority is not free dispatch (73% of its 454 ms is inside three leaves that
  resolve something). Census oddities worth knowing: `instanceof` and `in` are **38 and 42
  invocations in the entire compile**, and the `=` arm is reached only by OTHER references
  walking past. LANDED: `NarrowSections` `C_*` rows + arm census + `--narrowSectionsDeep`
  (opt-in, behaviour-free when off) and `NarrowSectionProbeTest` (18 pins, arm mirror pinned
  shape by shape). `--listAll` byte-identical with the probe at its deepest; filtered suite
  504 / 0 / 0. Full derivation: **`docs/perf/condition-narrowing-attribution.md`**.
  The original item text follows.

  ORIGINAL: **(CALL.4) `applyConditionNarrowing`'s 33,307 genuinely-narrowing calls
  at 21,708 ns each — the largest unattributed per-call number this arc has
  produced (round 736).** After (CALL.3) the function is 333,031 calls / ~1,016
  ms, of which 93% return their input unchanged for **468 ms raw (~410 ms net,
  1.3%)** and the remaining 7% carry the rest. **Do NOT re-propose the "does
  this condition mention the name" pre-test — round 736 priced it at ~410 ms,
  INSIDE the band, before the pre-test's own cost** (the identity calls are the
  CHEAP tail at 949 ns against 21,708 ns; § 0's law again, in a shape that is
  not a cache). The open question is what the 21,708 ns IS: split
  `narrowByEquality` / `narrowByInstanceOf` / `narrowByInOperator` /
  `narrowByCallPredicate` / `narrowByTruthiness` and the `getReferencePath`
  string building they all key on, using the `NarrowSections` harness (new
  section constants only). **Note the size honestly before starting: the whole
  narrowing population is now ~723 ms of genuinely-narrowing work = 2.4% of the
  compile, only just outside the band** — so a partial win here is in-band and
  the item may well end as a measurement. (The "attribute the 701,463
  `getTypeOfExpression` calls BY CALLER" leg that used to hang off this item is
  DONE — see (TYPE.1) below.) Gate: corpus suite + `--listAll` +
  `cost_gate.py` + `scripts/ab-interleaved.sh` medians AND win rate.

- [x] **(TYPE.1) Attribute the 701,463 `getTypeOfExpression` calls BY CALLER —
  DONE round 737, and it STRIKES § 0.1 stage 3.** Stage 3's mechanism
  ("several handlers independently type the same node") is **CONFIRMED and
  pervasive** — 177 initiating sites, **45.2% of the 254,069 typed nodes carry
  more than one origin** (modal three, max 17), 75.8% of calls land on them,
  and the ×2.76 factor decomposes as **2.05× cross-handler × 1.34× recursion**
  with per-caller factors of 1.00–1.11 (no handler re-types alone). **Its size
  is wrong by 3.2×**: a PERFECT per-node cache saves **823 ms (2.9%)**,
  single-visit discipline **670 ms (2.3%)**, the largest handler-pair merge
  **166 ms (0.58%)**, the SOUND memo **46 ms** — against a ±2% band of ~590 ms.
  **NOTHING LANDED**, correctly. Two corrections forced: "3,911 ms" is a DOUBLE
  COUNT (`typeOfExprNanos` charges a subtree once per nesting level; the true
  total is **2,439 ms = 8.5% of checker-init**), and **74.4% of the calls are
  OUTERMOST**, so recursion never was the explanation. § 0's law in a
  cache-free shape: the four biggest co-occurrence pairs by COUNT are 141,388
  repeats worth 71 ms (0.5 µs each), the biggest by TIME is 2,603 repeats worth
  166 ms (64 µs each). LANDED: `--typeOfExprCallers` + the
  `captureCallerFrames` expect/actual (JVM `StackWalker`, native `""`), the
  outermost-only walk with inherited origin, the co-occurrence masks, the
  single-visit and PERFECT-cache prize meters, and
  `TypeOfExprCallerAttributionTest`. Suite **12,916 / 0 / 3**, `--listAll`
  byte-identical, cost gate all 20 counters +0.00%. Full derivation:
  **`docs/perf/type-of-expression-attribution.md`**. Follow-on: **(TYPE.2)**.

- [x] **(TYPE.3) Open `walkFunctionBodiesInExpr` — DONE round 756, CLOSED AS A
  MEASUREMENT. The last unopened region the attribution arc pointed at.** Round 738's
  181 ms reproduces **exactly** (28,940 openings, 6,280 ns each — all three digits),
  because its population is AST shape and not declared types. Partitioned by a new
  **level D** in `CtaSections` — the first RECURSIVE partition in the arc, so it could
  not use levels A-C's `depth != 1` shape (that charges the whole descent to the
  dispatch row); `beginD` hands the caller's running row back and `endD` reopens it, so
  every row is SELF time and the rows sum to the total. **Net of a 168 ns/boundary
  charge: the walk itself 65 ms (36%), `calleeDeclaredCtxParams` 49 ms (27%),
  `checkFunctionBody` for arrows 49 ms (27%) and for function expressions 16 ms (9%)** —
  sum 179 vs 181 measured independently, and level D's outermost invocations are
  **28,940 = level A's `A_WALKFN` openings exactly**, a cross-check from the other side.
  **So `checkFunctionBody` — the work the function is NAMED for — is 36% of it.** The
  walk visits **199,131 nodes to reach 1,510 function-like ones (0.76%)**, only **636**
  of which have a body to check; the bodies cost **61-131 us each**, so round 738's
  aside ("the bodies it walks are mostly already walked") is wrong in mechanism — **the
  walk spends more finding them than checking them.** **THE CENSUS FOUND A FALSE
  NEGATIVE** (see (FN.1) below) and two zero-population findings: the whole B150/B585
  object-literal-method machinery is JS-gated and **never runs** on tsc's `.ts` source
  despite 755 objlit arm entries (a `.js` control lights it, so the zeros measure the
  input), and **`invocationsDOutside == 0`** — the walker has exactly one live entry
  point, the spine anchor. **NOTHING LANDED: 181 ms = 0.68% of the compile, largest row
  0.24%, against a ±2.0% band — deleting the whole walker is a third of one noise band.**
  Priced and rejected: a "does any argument have a function shape" pre-test skipping the
  29,787 unconditional callee resolutions that exist for 636 bodies = **49 ms = 0.18%**,
  and unproved sound. Full derivation:
  **`docs/perf/walk-function-bodies-attribution.md`**.

- [x] **(FN.1) FALSE NEGATIVE found by round 756's arm census — FIXED round 757, and
  its motivating number was 146x too big.** `walkFunctionBodiesInExpr`'s `ArrowFunction`
  arm was `(expr.body as? Block)?.let { … }`: for an EXPRESSION body it walked nothing
  and did not descend either, so a nested function body never reached
  `checkFunctionBody`. It now descends under the arrow's OWN parameter/type-parameter
  scope (zero-parameter arrows skip the install) — the scope is the FP firewall, since
  the nested `checkFunctionBody` inherits `currentLocalTypes`/`varTypes`. Round 756's two
  gap pins were FLIPPED, not deleted, and joined by a two-sided scope discriminator;
  **four fixtures fail on unmodified `2f728c1e` and pass after, four controls are
  identical on both**. **THE FINDING: the profile is byte-identical at 46 and all 8,837
  corpus baselines are unchanged, because the 874 expression-bodied arrows contain
  exactly SIX block bodies between them** (arm census 374 → 380 block arrows, 262 → 262
  function expressions) and all six are clean. The "874 of 1,510 (58%)" this item quoted
  is *how often the arm is taken*, not *what is behind it*. **No baseline moved, so no
  `LogicalParityDivergence` was needed.** Cost: new `D_ARROW_EXPR_SCOPE` row = 8 ms over
  707 installs, +3.5% nodes walked, **≈10 ms ≈ 0.04% of the compile** (the walker goes
  0.68% → ~0.72%). Addendum:
  **`docs/perf/walk-function-bodies-attribution.md` § 11**.

- [x] **(AUDIT.1) Audit the attribution arc's own numbers — DONE round 758.
  57 load-bearing claims classified POPULATION / FREQUENCY / TIME / RESIDUAL:
  40 stand, 11 stale-or-weak, 4 falsified, 2 unverified. THREE of the four
  falsified are a FREQUENCY spent as a POPULATION, and every one SHRANK.**
  `IDENTIFIER` 44.5% of nodes vs **8.4%** of the spine (**5.3x**);
  `getCalleeType`'s "half thrown away" 50.6% of calls vs **8-10%** of the time
  (**6.2x**, 1,452 ns vs 16,491 ns per resolution — this closes (CALL.1) § 6);
  874 arrows → six bodies (**146x**, landed round 757). A fourth is a RESIDUAL
  given a NAME: § 0.1's "remove ALL dispatch overhead → 66 units" should read
  `100 → ~99`, **~34x**. Also fixed a measurement bug — `--passTiming`'s
  per-kind table printed `enter+leave` and summed ENTER ONLY; corrected it now
  reproduces round 732's independent `--dispatchProbe` numbers to 0.2-5%.
  **"Single digits remain" SURVIVES; no parked item is revived.** Artifact:
  **`docs/perf/claim-audit-round758.md`**; § 0/§ 0.1 + four artifacts corrected
  in place.

- [x] **(AUDIT.2) Split `argType` by exit class — DONE round 759. THE CLAIM IS
  TRUE AND UNDERSTATED, AND IT IS § 0's LAW'S FIRST COUNTER-EXAMPLE.** The 72%
  that never reach the relation carry **89%** of the argument-typing time, at
  **22,604 ns each against 7,134 ns** for the 28% that do — **3.2× the wrong
  way**. Round 758's recorded prediction ("< 40%") and round 759's own (35%) are
  both **FALSIFIED by ~2.5×, in the same direction**, because both applied "what
  you could skip cheaply was already cheap" where the exit predicate (a property
  of the PARAMETER) and the cost (a property of the ARGUMENT) do not share a
  cause. Mechanism measured, not residual: `getTypeOfExpression` **8,252 ns**
  non-relating vs **1,344 ns** relating (6.1×), and **82% of the narrowing
  walks** are non-relating. **No lever anyway** — *paying for `argType` is not
  wasting it*: eleven blocks below consume the value and the relation is the
  cheapest consumer at 19 ms. The one arguably-skippable subset is 275 ms of
  narrowing = **1.0%, half a band**. Also: the row's defining number had fallen
  **924 → 689 ms (−25%)** while the item sat in the queue.

- [x] **(AUDIT.3) Price the globals-lookup population — DONE round 759.
  36–71 ms = 0.13–0.26%; § 0's asserted ≲0.2% is the right order and the last
  asserted-never-measured population in that section closes.** A nested span
  could not do it (an ~89 ns timestamp pair around a ~37 ns probe), so the
  instrument AMPLIFIES instead: `--globalsAmp r` brackets `r` reads under one
  pair, and two values of `r` cancel the pair's cost. Measured: **cold ~37 ns,
  warm 9.1 ns** (three independent slopes within 7%), 961,420 bracketed lookups.
  DCE ruled out **arithmetically** — the sink is an exact multiple of the hit
  count at every `r` — and the sink's 17,928 hits independently reproduce the
  classifier's 98.1% miss rate.

- [x] **(TYPE.2) Attribute inside `checkVarDeclAssignability` /
  `spineCtaM3StatementAnchor` — DONE round 738. BOTH PRIORS FALSE; the second
  by 65x, and the function is not what its name says.** Measured inside:
  **flow narrowing is 1 ms of 872 ms (0.11%)** and the **relation 13 ms
  (1.5%)** — prior (i) wrong by ~200x, prior (ii) by ~65x (round 735 found the
  SAME relation prior wrong by 48x one function over; it is now falsified in
  BOTH of the compiler's largest assignability sites). What is actually there:
  **12,960 of 15,116 invocations (86%) never reach an assignability check** —
  they are UNANNOTATED declarations that only type an initializer and record it,
  **405 ms = 46% of the function**. Two populations: 12,960 unannotated at
  **34 us** each, 1,881 annotated at **227 us** each (round 737's 36 us was
  their mean). **The handler is 2,363 ms and 85% of it is four callees' own
  work** (`checkVarDeclAssignability` 891, `checkReturnAssignability` 615,
  `checkAssignmentExpression` 318, `walkFunctionBodiesInExpr` 181); the
  eligibility gate + parent climbs over ALL 856,976 nodes are **194 ms
  (212 ns/node)** and the ambient scaffolding 158 ms — together 1.2% of the
  compile. **NOTHING LANDED**: the only candidate lever (hoist the unannotated
  branch above the ~18-walker prologue) is worth **~0**, because every prologue
  walker already bails on `decl.type ?: return false`; the prologue's 265 ms is
  spent on the 1,881 ANNOTATED decls and is **14x the relation it exists to
  correct** — the first price tag on section 0.1's "endgame" paragraph. LANDED:
  `CtaSections` + `--ctaSections{,Coarse}` (opt-in, behaviour-free when off;
  level A opens on the HANDLER so the eligibility gate is a ROW, not an
  unmeasured remainder) and `CtaSectionProbeTest`. Suite **12,923 / 0 / 3**,
  `--listAll` byte-identical in all three modes, cost gate all 20 counters
  +0.00%. Full derivation: **`docs/perf/var-decl-attribution.md`**. The original
  item text follows.

  ORIGINAL: **the third-largest spine handler, 2,900 ms, and
  no round has opened it (pointed at by round 737's by-caller table).**
  `checkVarDeclAssignability:29166` under `ctaM3StmtAnchor` is the **largest
  single expression-typing origin in the compiler**: 33,653 calls, 11,933
  top-level typings, 431 ms of typing at **36 µs per initializer**, factor 1.05
  — expensive, not redundant. Its typing is only ~15% of the enclosing
  handler's 2,900 ms (round 732's per-handler table), so ~2,470 ms is
  unattributed and the handler is ~10% of a 28.7 s checker-init — **the largest
  un-opened single target left**. Method: round 735's verbatim — split the
  function into a wrapper + `…Core`, add partition rows plus a `Coarse` mode for
  the differential calibration, and price the sections; round 736's lesson adds
  "look for a memo or a condition failing one level down", not only for raw
  work. **State a falsifiable expectation first.** Two priors worth testing:
  (i) that the 36 µs per initializer is mostly flow narrowing, as round 735
  found for the argument path (37%) — if so it is (CALL.4)'s population, not a
  new one; (ii) that the ~2,470 ms outside the typing is the assignability
  RELATION — round 735 found that term to be 1.2% on the argument path, i.e.
  the same prior was wrong by 48× one function over. Gate: corpus suite +
  `--listAll` + `cost_gate.py` + `ab-interleaved.sh` medians AND win rate if
  anything lands.**

- [x] **(FRONT.1) The first front-end attribution — DONE round 738, and it landed the
  arc's largest win, `-11.42%` median with B winning 6/6.** Section 0.1 stage 5's
  "front end, ~20%, unprofiled" is **11.0%**; the OTHER 9.2% of that region was
  `Transformer.transform` + `Emitter.emit` running under `--noEmit` and discarding
  its output (2,623 ms of 31,235). Gated by a NEW `CompilerOptions.skipEmitOutputs`
  set only by `ProjectCompiler` — deliberately NOT `options.noEmit`, which 440 corpus
  tests set as a directive. **A SCOPE correction, not an algorithmic speed-up: real
  `tsc --noEmit` does not emit either.** ~~so every published xtsc-vs-tsc `--no-emit`
  ratio compared our check+emit against tsc's check-only — the honest gap is ~2.15x,
  not 2.4x.~~ **RETRACTED round 739: the CI 3-way emits on ALL THREE sides, so the
  2.4x was already like-for-like and this gate does not move it.**
  The front end proper has NO lever: crawl WALL 1,683 ms (5.4%, and it
  already contains reading+decoding+PARSING all 9,977,097 chars, 16 in flight), core
  parse loop **0 ms** (78/78 pre-parses reused), bind 1,622 ms (5.2%), config 102 ms,
  `extractRelativeImports` 17 ms. LANDED: `FrontEnd` + `--frontEnd` (opt-in),
  `skipEmitOutputs`, `SkipEmitOutputsTest` (4 pins incl. the directive negative
  control). Suite **12,927 / 0 / 3**, `--listAll` byte-identical, cost gate 18/20 at
  +0.00% with two globals counters FALLING 9% (rebaselined same commit). Full
  derivation: **`docs/perf/front-end-attribution.md`**.

- [x] **(BENCH.1) DONE round 739 — and the premise it was queued on was FALSE.** The item
  assumed the published 2.4x compared our check+emit against tsc's check-only. It did not:
  `bench-3way.sh` runs xtsc, tsc AND tsgo **with emit**, so the CI ratio was already
  like-for-like and `skipEmitOutputs` (which fires only under `--noEmit`) cannot move it.
  **Round 738's "~2.15x" is retracted everywhere it was written** — it applied our own emit
  fraction while implicitly taking tsc's as zero, which is the ratio's floor, not its value.
  **The real mismatch is the other one and it was still open: § 0.1's budget model is a
  `--noEmit` compile compared against an EMIT-mode ratio.** Measured (same binary, 4
  interleaved pairs, compiler profile): check-only 26,896 ms vs emit 29,194 ms, **emit work =
  2,298 ms = 8.5% of a check-only compile**, B slower 4/4. **The check-only ratio has never
  been measured on either side** (the bench never ran tsc with `--noEmit`); it is bounded by
  `R_ck = R_emit x 0.921 / (1 - s_tsc)` => **>= 2.21x, and > 2.4x as soon as tsc's emit costs
  more than 7.9% of its run**. LANDED: `bench-3way.sh` measures BOTH modes on all three
  compilers (`--modes`), the LOC parse bug is fixed (every archived report published
  THROUGHPUT as its LOC count), `bench-history/README.md` is restructured with a marked
  two-mode table over a labelled pre-739 archive, and all 8 `bench/*.tsv` profiles are
  re-baselined check-only with a MODE-DISCONTINUITY block at the boundary. Honest published
  ratio: **2.28x (median of 340 CI runs) / 2.40x (last 30), EMIT mode**; per-row spread
  1.87x-2.72x because xtsc is one cold run against tsc's median of three. Full derivation:
  `docs/ARCHITECTURE-RETHINK.md` § 0.2.

- [x] **(CALL.5) DONE round 796 — PROMOTED to the top of PERF by the round-796 agent as
  the next perf item (back-pointer: (ENGINE.2h), round 795, whose closing sentence made
  `checkArgumentsAgainstSignature` — 1,353–1,442 ms over 23,214 calls, the ENCLOSING
  region of the three rows rounds 793–795 removed — the largest single measured cost
  left). Two deliverables: (a) an EXIT CENSUS over the round-735 `ArgSections` partition,
  (b) the lever it named.**
  - **(a) THE EXIT CENSUS, and it costs no boundary.** `leftIn` derived an exit profile by
    DIFFERENCING adjacent rows' `calls` — blind to the two `return`s inside the loop, to
    the prologue's returns, and unable to say what an exiting iteration had already PAID
    for. The census records the row already open at boundaries the partition was already
    crossing, so the ON boundary count is unchanged (448,473 either way) and a
    before/after ROW comparison stays valid (round 793). Four partition checks print
    EXACT. Two findings `leftIn` could not give: **every one of the 23,494 invocations
    returns from `POST`** (the thirteen dedicated prologue walkers, 103 ms net, fire ZERO
    times), and **15,637 of 39,593 iterations (39%) leave at the one
    `!isSimpleCheckableType` `continue` carrying 633 ms of the 911 ms argType row (69%)
    and 409 ms of the 503 ms narrowing (81%)** — round 759's 90/10 split reproduced and
    LOCALISED to a single exit.
  - **(b) THE LEVER: the already-relates pre-gate, which COLLECTS ROUND 764's DEBT.** That
    round gave the ENUM narrowing arm a second-chance shape and declined to generalise, in
    a comment naming its creditor ("deliberately enum-ONLY: the
    Interface/`unknown`/`string`/`number` arms below are corpus-pinned"). Round 783's rule
    says re-test such an exclusion; it is payable, because both remaining arms exist to
    SUPPRESS a TS2345 the wide type would cause, so the pins that motivated them all live
    in the population the gate KEEPS. **Refuses 8,905 of 9,823 walks (91%)** — B469
    1,916/2,455, M3.4 6,989/7,368 — for a measured **259 ms** on the `L_ARGTYPE` row
    (identical boundary count in both arms, both arms run TWICE per round 795's law 3;
    295 ms on the whole partition) = **0.95–1.1%**. `narrow.walks` **−32.2%**.
  - **THE EQUIVALENCE IS NOT AIRTIGHT BY ARGUMENT AND WAS MEASURED**: the narrowed type is
    read by ~11 blocks below the arm, and the census counts **787 / 1,134 / 1,138**
    refusals per compiler/services/harness profile that WOULD have substituted a different
    type. Everything downstream is nevertheless unchanged: grid 46×7/94 with 0 added and 0
    removed BOTH directions, `--partitionCheck 2` EQUIVALENT, corpus 13,435/0/3.
    Round 790's FREE complement control reports 578/902/927, so the zeros are not a dead
    instrument. Full derivation: `docs/perf/argument-check-attribution.md` §§ 10–18.
  - **WHAT IS LEFT IN THIS FUNCTION, for whoever picks it up.** After the gate it is
    ~1,240 ms. `L_ARGTYPE` is still ~600 ms, of which `getTypeOfExpression(arg)` is 175
    and the KEPT narrowing 148 (953 walks at 155 µs — the expensive tail, and round 736
    already closed the memo failure behind it). `INFER` is 124 ms, the thirteen
    never-firing prologue walkers 103, `L_WEAK` 82, `L_NOTSIMPLE` 87. **No remaining row
    is above half a noise band**, so the next unit here is a level-S sub-partition of
    `L_ARGTYPE`'s `getTypeOfExpression` by ARGUMENT KIND (the 15,637 that exit at
    `L_NOTSIMPLE` are arrows and callbacks typed under an installed contextual type,
    round 759), not another gate. **ROUND 797 DID THAT AND THE PARENTHESIS WAS FALSE —
    see (CALL.6) below.**

- [x] **(CALL.6) DONE round 797 — THE LEVEL-S SUB-PARTITION LANDED, ROUND 796's
  HYPOTHESIS ABOUT ITS OWN BIGGEST POPULATION IS FALSIFIED BY 14x, AND THE HONEST
  VERDICT IS *NO LEVER*.** The `L_ARGTYPE` row is split by a 14-way classification of
  the ARGUMENT (plus the contextual-install column and a KIND x EXIT-ROW cross-tab), and
  two new sub-measures (`N_ARM_CHAIN`, `N_GATE_REL`) close the row to a named residue.
  Like round 796's exit census it adds **no boundary**; five partition checks print
  EXACT.
  - **THE FALSIFICATION.** The 15,640 iterations leaving at `!isSimpleCheckableType` are
    **48% bare Identifier + 32% PropertyAccess (80% of the iterations, 89% of the
    497 ms)**; arrows + function expressions are **527 = 3.4% of the iterations and 3.2%
    of the cost**, and a contextual type is installed for **575 of 39,036 iterations
    (1.5%)**. *A parameter that is not simple-checkable sounds like a callback and is
    usually an INTERFACE, whose arguments are ordinary names.*
  - **THE ITEM'S OWN QUESTION, ANSWERED.** `getTypeOfExpression` by kind: PropertyAccess
    (11.2 us) + Call (23.0 us) are **73% of the row over 26.6% of the iterations**;
    Identifier — 46.5% of all iterations — is **0.8 us** and 7% of it.
  - **THE NUMBER THAT CHANGES THE MAP: round 796's own gate relation is 131-137 ms over
    9,823 calls (13.3 us each), not the "~70 ms" it estimated by subtraction.** Still
    net-positive (it removes ~390 ms of walking), and un-cheapenable:
    `checkTypeRelatedToCore` already short-circuits on `source === target`, and round
    796 measured only 416 of 8,905 refusals as bailing at
    `getReferencePath`/`getFlowAt`.
  - **TWO ASIDES WORTH THE CROSS-TAB.** 66% of the 10,946 iterations that reach the
    assignability relation carry a LITERAL argument (22 ms of argType between them) —
    the check the function exists for runs mostly on trivia; and **22% of all iterations
    type an identifier to `any` and discard it** (8,574, 99 ms), which is a modelling
    signal, not a perf one.
  - **NO LEVER, AS A BOUND.** The row is ~600 ms = 2.2% of a check-only compile and
    decomposes into ~200 ms irreducible type resolution, ~135 ms of the narrowing tail
    round 736 already closed the memo behind, ~131 ms of gate that buys 390, ~24 ms of
    literal preservation, and a residue that IS the probe (7 nested timestamp reads x
    the harness's own 423 ns in-situ figure = 2.96 us against a measured 3.3 us).
    Nothing is above half a noise band. Suite 13,435 -> **13,441 / 0 / 3** (+6 pins, 4
    discriminating across two complementary ablations run separately); cost gate **20/20
    at +0.00%**, `--partitionCheck 2` EQUIVALENT — 46, production/ON/COARSE `--listAll`
    identical. **No grid and no A/B, deliberately: nothing production executes changed.**
    Full derivation: `docs/perf/argument-check-attribution.md` sections 19-26.

- [x] **(ENGINE.1) CLOSED round 786 — SITE 3 IS PARTITIONED, E3 IS NOW A MEASUREMENT
  RATHER THAN A BOUND, AND THE OWNER-FACING NUMBER IS 613 ms = 2.2% OF A CHECK-ONLY
  COMPILE.** A level-E partition of `checkAssignmentExpression` (27 rows, wrapper/`…Core`
  split, exit census, `COARSE` counterpart) cross-checks against level A's independent
  `A: checkAssignmentExpression` row to **0.6%** (462 ms vs 465 ms) and splits
  **engine 284 ms (72.0%) / dedicated-walker layer 110 ms (27.9%) / bookkeeping 0.4 ms** —
  the layer is **0.39% of the compile and 0.39x the engine work** (site 1: 1.21% / 0.67x,
  site 2: 0.66% / 0.40x). **SCORES, all three sites now in: E1 HOLDS at all three
  (37.4 / 28.4 / 27.9%); E2 FAILS at both sites that could test it (4.5x, 5.6x — the 14x
  was an artifact of site 1's relation being 2.2% of its function); E3 HOLDS BY
  MEASUREMENT — 326 + 177 + 110 = 613 ms = 2.2%, landing INSIDE round 755's 605-630 ms
  estimate; E4 HOLDS at all three (site 3's largest group is strictFunctionTypes /
  declared-variance / freshness, all of which tsc holds inside `isRelatedTo`, so they
  MOVE).** **Plainly deletable across the three sites: ~29 ms = 0.1%** (site 2's legacy
  string checker + site 3's). **Three things only site 3 could say: 61% of its 17,179
  invocations (10,432) do nothing at all** — the eligibility test lives INSIDE the function,
  unlike sites 1 and 2 whose callers pre-filter, and only a wrapper-opened partition sees it
  (~13 ms, a shape not a lever); **the TS2322 elaboration is 0.4 ms over 3,791 reaches**,
  the third and strongest confirmation that the emission machinery is free on a codebase
  that type-checks; and **"compute the SOURCE type" is the largest row at all three sites**
  (41% here), i.e. the irreducible part. **THE CALIBRATION IS WEAK AND IS REPORTED AS A
  BOUND**: three runs per mode give ON 462/460/469 and COARSE 445/420/426, so Δ = 36 ms is
  only **1.4x the larger (COARSE) spread of 25 ms** — per CLAUDE.md that is not a figure,
  and the boundary cost is **144-472 ns**; what rescues the round is that the walker share
  reads 30.3 / 27.9 / 26.4% across that whole interval, because boundaries distribute by
  REACH and reach is near-uniform through the identifier partition. **NOTHING WAS LANDED
  BUT THE HARNESS** — every candidate lever inside the three sites is below the +-2.0% band.
  Suite 13,334 -> **13,342 / 0 / 3** (+8 pins), 8-profile grid
  **46/46/46/46/46/46/46/94**, `--listAll` identical in all three probe modes, cost gate
  **all 20 counters +0.00%**. Full derivation: `docs/perf/engine-rule-price.md` §§ 5b-8.
  The round-755 text follows.

  **ANSWERED FOR THE DECISION, round 755 — site 2 measured, E1-E4 scored, ONLY SITE 3's
  PARTITION LEFT (and E3 is already closed by a bound it cannot break).**
  `checkReturnAssignability` was partitioned into 15 rows (a new level C in
  `CtaSections`, its own index space so the level-A/B pins are untouched; wrapper +
  `...Core` so every early return closes its row). **Cross-check: level C totals 741 ms
  over 10,119 invocations against level A's independent `A: checkReturnAssignability`
  row at 740 ms** — two partitions from opposite sides of the same call agreeing to 0.1%.
  Calibrated differentially (ON 144,179 boundaries / 741 ms vs COARSE 10,119 / 642 ms =
  **739 ns per boundary**, consistent with round 733's in-situ ~900 ns and NOT with the
  86-89 ns per read). **Net of probe: engine 446 ms (71.6%), dedicated-walker layer
  177 ms (28.4%), bookkeeping 0** => the layer is **0.66% of a 26,778 ms compile** and
  **0.40x the engine work** (site 1: 1.21%, 0.67x). **SCORES: E1 HOLDS** (28.4%, inside
  the predicted 25-50%); **E2 FAILS** (177/39 = **4.5x**, not the predicted >=10x — the
  14x was an artifact of site 1's relation being 2.2% of its function; here it is 6.3%);
  **E3 HOLDS BY A BOUND** (sites 1+2 = 503 ms, site 3's ENTIRE function is 373 ms, so the
  three-site layer cannot exceed **876 ms = 3.3%**; at the 28-37% both measured sites show
  the honest figure is **~605-630 ms = 2.3%**); **E4 HOLDS AT BOTH** (site 1 the weak-type
  rule, site 2 excess-property checking — tsc implements both inside `checkTypeRelatedTo`,
  so both MOVE rather than delete). **THE OWNER-FACING NUMBER: deleting the layer on the
  three largest assignability sites is worth ~2.3% of a check-only compile, bounded at
  3.3%, against a band re-derived the same session at +/-2.0% — between one and two noise
  bands, and it trades the property that made a byte-identical corpus reachable.** Two
  findings site 1 could not give: the **218-line TS2322 elaboration runs ONCE in the whole
  compile** (a property of a clean profile, not of the code), and **the legacy string
  checker is not a rare fallback — 8,587 of 10,119 invocations (85%) exit inside it**,
  running the entire engine block and then re-checking through the string path, for
  **15 ms**. **WHAT IS LEFT: site 3 (`checkAssignmentExpression`, 1,427 lines, 373 ms)
  needs the same level-D partition** — it cannot change the conclusion, only sharpen it.
  Full derivation: **`docs/perf/engine-rule-price.md`** §§ 5-8. The original item text
  follows.

  ORIGINAL: **(ENGINE.1) Price the dedicated-walker layer on two more sites — IN PROGRESS,
  round 739 did the part that needed no measurement and it already overturns the 14x.**
  **The 14x does not survive contact with its OWN site.** 265/19 compares the firewall
  walkers against the final relation call ALONE, and that call is 2.2% of the function
  it lives in; a general rule engine must still resolve the target node, compute the
  source type, infer unannotated initializers and narrow. Re-classifying round 738's
  own level-B rows by "would a general engine also do this": **engine work 483 ms
  (55.4%), dedicated-walker layer 326 ms (37.4%), bookkeeping 54 ms** — so the layer is
  **0.67x the engine work, not 14x it**, and on this site it is **326 ms of a 26,896 ms
  check-only compile = 1.21%**. Deletable is LESS: the weak-type rule is 165 ms = half
  the layer and is real TypeScript semantics tsc implements inside `checkTypeRelatedTo`,
  so it MOVES rather than vanishes => honest range **0.6-1.2%** for this site. **Method
  correction the two remaining sites must adopt** (else their numbers are not
  comparable): report the layer as ms and as a share of the COMPILE, never as a ratio
  against the relation; and split it into "re-implements a rule tsc also has" (moves)
  vs "corrects our own relation" (deletes). **A grep-based census will NOT work:
  `checkReturnAssignability` (802 lines) has ZERO `tryEmit*` calls — its firewall is
  inline `if (...) return` guards — while `checkAssignmentExpression` (1,427 lines) has
  11.** Both need a real intra-function partition (rounds 735/738's method); round 739
  deliberately did not start it thin. Scored predictions E1-E4 are written down in
  `docs/perf/engine-rule-price.md` § 4 — score them.

- [x] **(ENGINE.2) CLOSED round 787 — THE PATH IS 3,815 ms = 13.7% OF A CHECK-ONLY
  COMPILE AND ITS DEDICATED-WALKER LAYER IS 207 ms = 0.74%, i.e. 0.088x THE ENGINE
  WORK.** Level P (recursive, hand-back shape, windowed to `cpaSpineLeave`: 399,336
  invocations in-window, **0 outside**) + level Q + a timestamp-free CENSUS mode.
  **Engine `checkMemberAccessMissing` 2,364 ms (91.9%) / firewall 207 ms (8.0%)**,
  against 0.67x / 0.40x / 0.39x at (ENGINE.1)'s three sites — **the site that holds
  the mass has by far the smallest layer share**, which is the answer to § 0.1's
  endgame scope question. Four-site layer total: **820 ms = 2.9%**. **The layer is ONE
  walker**: of eight firewall probes running at all 66,747 property accesses, seven
  cost **0-25 ms COMBINED**; B464 costs 272 ms. **E4 holds a fourth time** — all eight
  emit diagnostics tsc emits, so the layer MOVES and plainly deletable is **~0 ms**.
  **Predictions 2 hit / 1 undecided / 2 missed; G3 (the hoped-for lever) FALSIFIED by
  17x at 18 ms.** **G4's first reading, 2.35x, was an ARTIFACT of per-file `nodeId`
  reuse** — keyed by (file, nodeId) it is exactly **1.000**, nothing is visited twice.
  Calibration reported as a BOUND (Δ 304 ms = 389 ns, bracket 198-579, Δ/spread 2.8x),
  and the classification is shown not to hinge on it. Suite 13,342 -> **13,351 / 0 / 3**
  (+9 pins), grid **46/46/46/46/46/46/46/94**, `--listAll` identical in all four modes,
  cost gate **all 20 counters +0.00%**. Follow-on: **(ENGINE.2b)**. Full derivation:
  `docs/perf/property-access-attribution.md`. The original item text follows.

  ORIGINAL: **Price the dedicated-walker layer on the site that actually holds the
  mass — the PROPERTY-ACCESS path, `cpaSpineLeave` -> `checkPropertyAccessInExpr` ->
  `checkSinglePropertyAccess`. PROMOTED round 787 as the next stage of
  `docs/ARCHITECTURE-RETHINK.md` § 0.1's endgame paragraph.** (ENGINE.1) answered the
  owner-facing scope question on the three largest ASSIGNABILITY sites and measured the
  layer at **613 ms = 2.2%**. But those three sites are 1,417 ms of a 27.9 s compile
  between them, and the largest single block of checking work in this compiler is
  somewhere else and has never been opened: `cpaSpineLeave`'s two anchor rows measure
  **4,449 ms at HEAD** (3,179 anchor-stmt + 1,270 owner-cond; round 733 read 4,653 —
  stable, re-measured round 787 before promotion per CLAUDE.md's re-measure law), i.e.
  **~16% of the compile in ONE recursive walker plus its per-property-access leaf**.
  Round 733 attributed the HANDLER and stopped at "88.4% is the pass's own checking
  work"; its ccet twin was then opened three times over ((CALL.1)/(CALL.2)/(CALL.3))
  and its cta sibling twice ((TYPE.2)/(ENGINE.1)) — **the cpa side never was.**
  (a) Partition it: a new `CpaSections` with a RECURSIVE level P over
  `checkPropertyAccessInExpr` (round 756's hand-back shape — `beginP` returns the
  caller's row, `endP` reopens it, so every row is SELF time) windowed to the three
  `cpaSpineLeave` anchor blocks so its total is directly comparable to the 4,449 ms,
  plus a level Q over `checkSinglePropertyAccess` splitting its five `emitTs*`/`check*`
  firewall probes from `checkMemberAccessMissing`, the TS2339 ENGINE. Opt-in,
  behaviour-free when off, `COARSE` counterpart for the differential, own pins.
  (b) Classify with (ENGINE.1)'s method, which is now mandatory for comparability:
  report the layer in **ms and as a share of the COMPILE**, never as a ratio against
  the relation, and split it into "re-implements a rule tsc also has" (MOVES into any
  replacement engine) vs "corrects our own resolution" (DELETES).
  **PREDICTIONS, scored (write them down so they can be falsified):**
  **G1 — the leaf dominates the traversal:** `checkSinglePropertyAccess` +
  `checkSingleElementAccess` are **>= 50%** of the 4,449 ms, and the `when` dispatch +
  pure pass-through arms (the walk itself) are **< 10%** — consultation is not the
  expense, for the fifth independent time.
  **G2 — E1's band does NOT hold here:** the firewall layer inside
  `checkSinglePropertyAccess` is **20-40%**, i.e. at or below the low end of
  (ENGINE.1)'s measured 27.9-37.4%, because this site's engine call
  (`checkMemberAccessMissing`) is a full property RESOLUTION rather than the 2.2-6.3%
  relation call the assignability sites end in.
  **G3 — the scope bookkeeping is the one candidate lever:** the ArrowFunction /
  FunctionExpression arms copy three `EpochMap`/`EpochSet`s and run
  `populateParameterLocalTypes` + `applyBodyLocalShadowing` +
  `applyAmbiguousBlockScopedLocals` per function node. Predicted **>= 300 ms**, which
  would be the first candidate above a half-band this arc has found in six rounds. If
  it comes in under 150 ms, say so and stop.
  **G4 — the walk does not duplicate itself:** `checkSinglePropertyAccess` invocations
  / distinct `PropertyAccessExpression` nodeIds is **1.0 +- 0.05** (the anchors are
  disjoint by construction via `cpaM3MarkAnchored`). A ratio > 1.2 would be a
  structural finding worth more than the rest of the item.
  Gate: corpus suite + 8-profile `--listAll` grid + `cost_gate.py`; `ab-warm.sh`
  medians AND win rate if anything lands. **A count is not a measure; price the
  population before building the fix.**

- [x] **(ENGINE.2b) DONE round 788 — BOTH LEVERS LANDED, and the two of them answer
  DIFFERENTLY. (b) is a clean elimination: B464's `closureStarts` scan is 138 ms -> 4 ms
  over an IDENTICAL 15,483 queries, verified 0 mismatches against the replaced scan on
  compiler AND harness (41,602 queries) and pinned by a test that carries the scan as
  its own reference implementation. (a) is real but its ms do not all survive: the
  pre-gate skips 49,003 of 51,967 calls (94.3%) and four cost counters fall 2.75-5.09%,
  yet the timed row falls 109 ms of the 265 predicted — because a resolution the
  function performs is CACHED, so skipping it MOVES the work to the next asker rather
  than deleting it. Round 787's law gains its mirror image: AN AGGREGATE THAT IS
  SKIPPABLE IS NOT THEREBY RECOVERABLE.** The predicate needed a FOURTH node kind the
  probe had missed (`ArrayLiteralExpression` — `getTypeOfArrayLiteral` reads
  `contextualType` for its element context), and the order hazard was FALSIFIED rather
  than argued: a temporary probe kept the old behaviour and counted every read of a
  value the gate would have suppressed — compiler 0 of 49,003, harness 1 of 71,840 and
  that one provably inert (`{} | undefined` is a `Type.Union`, so the reader returns on
  the next line). **The WARM A/B decides NOTHING**: median -0.66% (B faster), B wins
  2/3, arm sd 0.71%/0.42% (admissible box) but per-pair spread 270 ms against an 80 ms
  delta -> `VERDICT: NOISE-DOMINATED`. So the 403 ms is NOT confirmed at the wall; the
  deterministic counters are the whole measured result. Suite 13,351 -> **13,371 / 0 / 3**
  (+20 pins, 15 discriminating), grid **46/46/46/46/46/46/46/94** set-for-set both
  directions on all eight, `--partitionCheck 2` EQUIVALENT, cost gate rebaselined with
  the mechanism named. Full derivation: `docs/perf/property-access-attribution.md` § 5b.
  The original item text follows.

  ORIGINAL: **The two levers (ENGINE.2) priced but did not land — 403 ms = 1.44%
  together, and this is the FIRST candidate above a half-band the perf arc has found in
  seven rounds.** Neither clears the +-2.0% COLD band alone, so **decide both with
  `scripts/ab-warm.sh` (+-1.0%), on a box nobody is touching, and discard any run whose
  printed per-arm sd exceeds ~1%.** Take them as two separate commits, each
  independently gated, so a regression is attributable.
  **(a) `cpaComputeArgCtxTypes` — 265 ms computed for calls where nothing can read the
  result.** `contextualType` is read by exactly three arms of
  `checkPropertyAccessInExpr` (ArrowFunction / FunctionExpression /
  ObjectLiteralExpression), and only **2,020 of 51,967 calls (3.9%)** have an argument
  subtree containing one; those hold **67 ms of the 332**. **THE PRIZE IS ALREADY
  MEASURED — do not re-derive it, and do not multiply a count by a mean cost** (the
  populations differ 6x per call: 5.3 us vs 33 us). The pre-gate must beat 5.1 us per
  call, which a bounded subtree scan does easily; the probe-only
  `cpaArgumentsCanConsumeContext` is a working conservative predicate to start from,
  but a per-node precomputed flag is likely cheaper. **THE RISK IS NOT PERFORMANCE, IT
  IS BEHAVIOUR**: the function calls `getTypeOfIdentifier`,
  `resolveStructuredTypeMembers` and `tryInferSingleTypeParamFromArgs`, all of which
  mutate resolution caches, and round 754 proved a resolution-ORDER accident can decide
  a verdict. **Gate on the full suite AND all eight profiles set-for-set, not on the
  compiler profile alone** — round 782's "residual is empty" was stale for six
  consecutive rounds precisely because every price in that arc was measured on compiler
  and services only.
  **(b) B464's innermost-closure lookup — a 138 ms O(closures-in-file) LINEAR SCAN.**
  `emitTs18048ForClosureCapturedUndefinedReceiver` finds the innermost closure
  containing the receiver by scanning `graph.closureStarts` — **15,483 reaching
  invocations at 8.9 us each, 46% of the walker**. The round-489 pre-gate already does
  its job (only 15,483 of 66,747 get past it; only **466** ever launch the flow walk),
  so what is left is the scan itself. Answer it from the INV.2(a) parent chain (walk up
  to the first Arrow/FunctionExpression and match it against `closureStarts` by
  identity/position) or from a position-sorted per-file index built once. **This is not
  a cache** — § 0's law does not apply to replacing an O(n) scan with an O(depth) walk.
  Rounds 482 and 489 both optimised AROUND this scan without replacing it; read their
  notes before starting.

- [x] **(ENGINE.2c) DONE round 789 — AND THE PICTURE INVERTS ONE LEVEL DOWN. At levels P
  and Q the dedicated-walker firewall was 8.0% and the engine 91.9%; INSIDE that engine
  the firewall is 67% and the property lookup it defends is 0.2% (6 ms). The three
  flow-graph suppression blocks are 1,505 ms = 57% of the function and ~5.4% of a
  check-only compile, they launch 22,270 flow walks — 31% of every narrowing walk the
  compiler does — and they buy 886 suppressions, 95% of whose payers exit further down
  the function without the answer being consulted. H1 and H2 were both FALSIFIED, in the
  same direction, by extrapolating (ENGINE.1)'s assignability-site prior to a site of a
  different shape.** Full derivation: `docs/perf/property-access-attribution.md` §§ 10-16.
  The original item body follows.
- [x] **(ENGINE.2c) OPEN `checkMemberAccessMissing` — THE LARGEST UNOPENED LEAF IN THE
  COMPILE (2,292 ms gross = 8.2%, re-measured at HEAD round 789 before this item was
  written).** Round 787 partitioned the property-access path two levels deep and stopped
  where the mass is: level Q's engine row is ONE function of ~1,965 lines called 66,747
  times at **34.3 us each**, and no round has ever looked inside it. Every other row of
  levels P and Q is now under 400 ms; this one is 6x the next. Law 6 (a handler's nanos
  are its work, not its scaffolding) says attribute INSIDE before proposing anything.
  **DELIVERABLE: level R** — a source-order partition of `checkMemberAccessMissing`
  (~20 rows) with an EXIT CENSUS (which row each of the 66,747 calls returns from), a
  `COARSE` counterpart for the differential calibration, and the write-up in
  `docs/perf/property-access-attribution.md`. **The classification to produce** is the
  same three-way one the arc has used at four sites: ENGINE (resolve the receiver, look
  the property up) / dedicated-walker FIREWALL (a receiver-shape special case that
  suppresses or re-words) / traversal.
  **PREDICTIONS, scored afterwards.**
  **H1 — the receiver-TYPE computation dominates: the `objectType` block is >= 50% of
  the function.** (ENGINE.1) found "compute the SOURCE type" the largest row at all
  three assignability sites (41% at site 3), and here the analogous input occupies 848
  of the 1,965 lines. **Falsified below 35%.**
  **H2 — the pre-type firewall is thin: the ten receiver-shape blocks that run BEFORE
  the type is computed (shadowed names, three flow-graph blocks, string/regex/objlit
  receivers, New/Call/PropertyAccess receivers) cost < 20% combined**, mirroring level
  Q where seven of eight firewall probes cost 0-25 ms together. **Falsified at >= 30%.**
  **H3 — the exit census is TOP-HEAVY: >= 60% of the 66,747 calls return before
  `getPropertyOfType` is ever reached.** If true the function's cost is dominated by
  computing a type in order to discard it, and the lever is a cheaper gate rather than a
  faster lookup. **Falsified below 40%.**
  **H4 (the lever) — some gate that is CHEAP to evaluate sits AFTER expensive work, and
  hoisting it is worth >= 150 ms.** Named candidates, both of which today run only after
  the whole receiver type exists: `propName in RUNTIME_PROPERTIES` and
  `isEnumFlavoredObjectType(objectType)`. **Falsified if the census shows < 150 ms
  upstream of every hoistable gate — in which case say so and stop.**
  **H5 (the wiring control) — the emission tail (spelling suggestion, `typeToString`,
  message construction) is ~0 ms**, because 46 diagnostics fire program-wide. Round
  786's 0.4 ms TS2322-elaboration row is the precedent; a non-trivial number here means
  the partition is mis-wired, not that the tail is expensive.
  **SCORED round 789: H3 and H5 HIT, H1 and H2 FALSIFIED (both by extrapolating
  (ENGINE.1)'s assignability-site prior to a site of a different shape), H4 hit in the
  OPPOSITE direction — the finding is not a cheap gate placed late but an EXPENSIVE gate
  placed early.**
  **Law 4 applies before anything lands**: if a candidate skips a CACHED resolution, its
  bill moves to the next asker — price the skip by landing it and re-reading the
  partition, never by the census alone. Gate: corpus suite + the 8-profile `--listAll`
  grid diffed set-for-set BOTH directions + `--partitionCheck 2` + `cost_gate.py`.

- [x] **(ENGINE.2d)(a) DONE round 790 — the round-425 loop-entry retry is SKIPPED when the
  plain walk provably made it redundant, which is 88.7% of the time. Measured: the retry
  row 528 ms / 21,384 walks -> 90 ms / 2,408; its containing R_FLOW row 1,548 -> 1,111 ms;
  `narrow.walks` 71,377 -> 52,401, a drop of EXACTLY the 18,976 skipped retries and 26.6%
  of all flow narrowing the compiler performs. NO counter rose, so law 2 (round 788's
  "skippable is not recoverable") does not bite here — as the item predicted, and that
  prediction is now a HIT.** Equivalence was falsified empirically before it was claimed:
  `--verifyLoopRetry` keeps the pre-gate behaviour and compares at `Type`-INSTANCE
  granularity — compiler 18,976 / services 24,290 / harness 24,681 skippable calls,
  **0 type-diffs and 0 verdict-diffs across all 67,947** — and `--verifyLoopRetryAll` is
  the positive control over the complement population, which does diverge. Grid
  46/46/46/46/46/46/46/94 with 0 added and 0 removed both directions; `--partitionCheck 2`
  EQUIVALENT; suite 13,389 / 0 / 3 (+9 pins, 7 discriminating). Warm A/B: B wins 3/3, every
  delta outside the band, but arm A's sd 2.47% > the 1% quietness criterion, so the SIGN is
  confirmed and the MAGNITUDE is the partition's 438 ms, not the wall's 696. Derivation:
  `docs/perf/property-access-attribution.md` sections 17-22.

- [x] **(ENGINE.2d)(b) DONE round 791 — the three suppression blocks are DEFERRED to the
  emission, and the apparatus now runs 57 times per compile instead of 67,067. Measured:
  the `R_FLOW` row 1,132 -> 49 ms, the plain narrowing walk 794 ms / 22,270 calls ->
  4 ms / 47, level R total 2,686 -> 1,702 ms (-984, more than the 781 priced, because
  the deferral also removes the `getTypeOfExpression` and the round-489 pre-gate in
  front of the walks). `narrow.walks` 52,401 -> 27,249 (-48.0%) and NO counter rose, so
  law 2 does not bite here either.** Round 790's "no counter can falsify it" was
  premised on the deferral having to decide, per emission site, whether the suppression
  applies — it does not: the blocks only ever `return`, so the deferral has to undo the
  BODY, and the body's whole effect is 42 `diagnostics.add` calls plus 7 in its helpers
  and **nothing else** (grep-verified: no retraction, no side-set write, no ambient
  install, no read of `diagnostics`). "Run the body, then remove what it appended at or
  after the blocks' old position" is therefore equivalent by construction, with no site
  enumerated and a NEW site covered automatically. The retraction FLOOR is the blocks'
  old position, not the function entry — the one emission above them (the
  intersection-reduction `never` TS2339) was never theirs to suppress. The residual
  hazard, cache-mutation ORDER, was MEASURED: `--verifyDeferSuppression` evaluates the
  predicate at both positions, honours the eager verdict (so the run reproduces the
  pre-change binary) and compares at `Type`-INSTANCE granularity — **compiler 67,067 /
  services 92,174 / harness 109,622, 0 type-diffs and 0 verdict-diffs across all
  268,863** — with `--verifyDeferSuppressionBogus` as the shipped positive control
  (type-diff 19,864, verdict-diff 1,164). Grid 46/46/46/46/46/46/46/94 with 0 added and
  0 removed both directions; `--partitionCheck 2` EQUIVALENT; suite 13,399 / 0 / 3
  (+10 pins, 8 discriminating across two complementary ablations). Derivation:
  `docs/perf/property-access-attribution.md` sections 23-29.

- [x] **(ENGINE.2f) ANSWERED round 794: the substitution landed, ~30 ms (0.11%), and the
  item's veto on the SKIP was analytically right and empirically unnecessary.** Re-measured
  at HEAD first (law 1): the retry still reaches **1,859 calls for 66 ms**, of which **916
  (49%)** are loop-free. Split into its own sub-measure (`N_U_RETRY_LF`, same boundary count
  in both arms, so no round-793 boundary correction) it reads **32 ms → 2 ms**; the row's
  `count × mean` extrapolation happened to be right, but the row that says so is the one to
  quote. **NOT skipped, SUBSTITUTED** — the consumer's first test is the identity
  `loopNarrowed !== rawForNarrowing`, and a loop-free repeat crossing a branch join mints a
  FRESH equal union, from which the block suppresses **61** times per compiler-profile
  compile. `--verifyUnionRetry` re-walks and HONOURS the re-walk (so it reproduces the
  pre-change binary and IS the grid baseline) and compares at `Type`-INSTANCE, MEMBER-ID-SET
  and VERDICT granularity: **compiler 916 / services 1,191 / harness 1,313, 0 diffs of any
  kind across 3,420** — instance-diff 0, i.e. the mirror returns the very same instance.
  `--verifyUnionRetryAll` is round 790's free complement control and reports **149 / 101 /
  118** diffs, so the zero is not a dead instrument. `narrow.walks` **27,256 → 26,340 = −916
  exactly** and nothing rose (law 2 checked). **NO A/B was run: 0.11% is an order of
  magnitude below the warm band, so a wall verdict would be noise wearing a sign.** Grid
  46/46/46/46/46/46/46/94 with 0 added and 0 removed both directions; `--partitionCheck 2`
  EQUIVALENT; suite 13,412 → 13,419 (+7 pins, 2 discriminating against fault A). **THE
  NEGATIVE RESULT, worth more than the fix: the SKIP the item forbade is indistinguishable
  from the substitution on all 8 profiles AND all 13,419 baselines** — the 61 suppressions it
  loses are downstream-redundant (the retry's fold strictly implies the elaboration's
  `memberHasIt`, so `missingMembers` is empty a few lines later). Ship the provable form
  anyway: it costs the same 2 ms, and "nothing we ran noticed" is not equivalence.
  Derivation: `docs/perf/property-access-attribution.md` §§ 36-42.

- [x] **(ENGINE.2h) ANSWERED round 795: LANDED, and the item's own arithmetic was wrong in
  the helpful direction — the probe's single reader is reached ZERO times on the compiler
  profile (the "57 emissions" was round 793's count for a different site), so the deferral
  removes all 23,214 evaluations and keeps none.** Re-measured at HEAD first (law 1): the
  row is **81 ms raw / 72 net**, not 94. Quoted as a RANGE, **79-132 ms (0.3-0.5%)**,
  because the row (one boundary pair per invocation) and the same-boundary differential
  (the whole `SINGLE_SIG` span with the eager evaluation restored on the SAME binary, 2
  runs per arm: 1,634 -> 1,502 ms) disagree by that much, and the differential's Delta is
  only 3x its within-arm spread. **No A/B was run** — 0.3-0.5% is an order of magnitude
  inside the warm band. Round 791's shape with a trivial obligation (ONE reader, found by
  one grep) and one real check: the argument loop's `contextualType` install is restored
  ~1,300 lines above the emission block, so the ambient state at the deferred position is
  the ambient state at the eager one. Round 788's thunk does not transfer — nothing is
  stored, the force is inside the same invocation. Equivalence: `--verifyImplRelated`
  honours the EAGER verdict and `--verifyImplRelatedAll` is round 790's free complement —
  **compiler 23,214 / services 34,891 / harness 37,318 comparisons, 0 diffs** at
  `Diagnostic` granularity; `--verifyImplRelatedBogus` reads **0 on all three** because the
  `allArgumentsMatch` gate never declines on a tsc source tree, so the live falsifier is
  the in-process pin (round 793's wall, from the other side). Law 2 checked:
  `globals.lookups` -0.47%, `globals.misses` -0.48%, everything else +-0.00%, **nothing
  rose** — what is removed is an un-cached AST scan (`binderResults` x top-level statements
  per method-callee call), not a memoized resolution; census: 23,214 evaluations, 3,660
  name lookups, 27 candidates, **1** `allArgumentsMatch`. Grid 46/46/46/46/46/46/46/94 with
  0 added and 0 removed both directions against the real HEAD binary; `--partitionCheck 2`
  EQUIVALENT; cost gate exit 0, no rebaseline; suite 13,419 -> **13,427** (+8 pins, 4
  discriminating across two complementary ablations). **THE CALL-EXPRESSION PROLOGUE FAMILY
  IS CLOSED** — round 793's inventory named one live candidate and this was it. Derivation:
  `docs/perf/call-expression-attribution.md` §§ 14-19. The original item body follows.

  ORIGINAL: **(ENGINE.2h) THE TS2793 "IMPLEMENTATION WOULD HAVE SUCCEEDED" PROBE IS COMPUTED
  EAGERLY AT EVERY SINGLE-SIGNATURE CALL — 94 ms over 23,214 — FOR A RELATED-INFO MESSAGE
  ONLY AN EMISSION CONSUMES.** Re-measured at HEAD round 793 (`--callSections`, nested row
  `of which TS2793 impl-would-have-succeeded probe`): `getOverloadImplementationRelated` +
  `getImplementationSignature` + `allArgumentsMatch` run before
  `checkArgumentsAgainstSignature`, and the `implRelated` diagnostic they build is attached
  only where an argument diagnostic actually fires — 57 emissions in the whole compiler
  profile. **This is round 791's DEFERRAL shape, not round 792's gate shape, and the
  difference decides the instrument**: there is no cheap refutation to compute, so the fix
  is to pass a thunk / recompute at the emission site, and the hazard is that
  `allArgumentsMatch` types arguments — evaluated later it sees a different ambient state
  (round 754). So it needs 791's verifier: evaluate BOTH eagerly and deferred, honour the
  EAGER verdict so the run reproduces the pre-change binary, and compare at `Diagnostic`
  granularity, with a bogus positive control. **94 ms = 0.34%, so this is a
  cheap-if-you-are-already-there item, not a headline** — and round 788's rejected lazy
  `contextualType` thunk is the precedent to read first, because the reason it failed
  (the thunk had to survive ~14 save/restore sites in a different dynamic scope) does NOT
  apply here (the force site is inside the same invocation) but the shape is identical.
  Gate as (ENGINE.2g): corpus suite + the 8-profile grid diffed BOTH directions +
  `--partitionCheck 2` + `cost_gate.py`.

- [x] **(ENGINE.2g) ANSWERED round 793: the PROPOSITION form does NOT generalise, the KEY
  form does. Landed: a pre-gate on the `checkSingleCallExpressionTypes` PROLOGUE — seven
  dedicated walkers that run at every call expression in the program and fire ZERO times
  on the compiler profile — refusing 98.1% of 52,413 invocations (98.1% of services'
  69,555, 97.6% of harness's 78,724) with 0 firings in 196,431 refused calls and all 18
  profile firings in the kept complement.** The seven emit FIVE different claims
  (TS2345/TS18048/TS2339/TS2349/TS2754), so no single refutation can kill them the way
  "the property resolves" killed `checkMemberAccessMissing`'s 42; what they share is a
  KEY — each is reachable only for a narrow syntactic shape of the CALLEE. **The
  transferable method is "one cheap question in front of many emissions", not "one
  proposition".** Prize: the prologue reads 219 ms as ONE span but contains six
  intermediate probe boundaries production never pays, so the production figure is
  **123–191 ms** and the landed rows fell **261 → 66 ms raw**; net **≈105–165 ms =
  0.4–0.6%**. **Law 2 was checked and does NOT bite** — `typeOfExpr.calls` −1.86%
  (−12,310) and nothing rose, although the one resolution skipped (B216's
  `getTypeOfExpression(recv)`) is re-asked by `getCalleeType` a few lines below.
  Equivalence is by construction for the emissions (each leg is the walker's own first
  gate) and the B216 leg additionally proves the SIDE-EFFECT bound: `resolveStructured
  TypeMembers`/`getIndexedAccessType` both sit BELOW the string-literal-argument test, so
  a refused call could only ever have performed that one type read. Grid
  46/46/46/46/46/46/46/94 with 0 added and 0 removed both directions; `--partitionCheck 2`
  EQUIVALENT; suite 13,405 → 13,412 (+7 pins, 5 discriminating across two complementary
  ablations run separately). Warm A/B B wins 2/2, median −126 ms (−1.15%), **but both arm
  sds exceed the 1% quietness criterion so the magnitude is the partition's**. Derivation:
  `docs/perf/call-expression-attribution.md` §§ 9-13. **The re-measured inventory the
  round produced, for whoever picks up the next one: B464 171 ms and `cpaComputeArgCtxTypes`
  242 ms are ALREADY pre-gated (rounds 489/788); `checkPrivateMemberAccess` 44 ms and the
  optional-property TS18048 walker 50 ms are keyed on the receiver KIND, complementary to
  B464's rather than shared, so one classification cannot serve both; the one live
  candidate left is the TS2793 `implRelated` probe (94 ms over 23,214), which is round
  791's DEFERRAL shape, not this round's gate shape.** The original item body follows.

  ORIGINAL: **(ENGINE.2g) THE PRE-GATE GENERALISES — ASK WHERE ELSE A DEDICATED-WALKER FIREWALL
  IS ANSWERABLE BY ONE CHEAP QUESTION.** Round 792's gate works because every emission of
  `checkMemberAccessMissing` asserts the SAME proposition (a property is absent), so one
  refutation kills all 42 of them. Level Q's table has other multi-emission engines
  (`emitTs18048 closure-captured receiver` 181 ms, `checkPrivateMemberAccess` 49 ms) and
  level P's `cpaComputeArgCtxTypes` is 248 ms — **re-measure first (law 1), then ask each
  one whether its emissions share a proposition a cheap probe can refute.** The round-792
  method is the template: probe that HONOURS NOTHING + a falsifier column + a bogus
  control, then the corpus decides, because a profile-measured zero bounds a hazard's
  frequency and not its existence (7 baselines failed under a gate whose skip set showed
  0 emissions on three profiles).

- [x] **(ENGINE.2e) RE-OPEN LEVEL R ON THE POST-(b) SHAPE — DONE round 792. The partition
  was re-derived (top rows: resolved-symbol 280, union narrowing 271, identifier special
  cases 159, pre 129 ms net), a level-S sub-partition opened all four and found ~80 ms of
  in-row levers TOTAL (0.3%), and the lever that landed is one level up: a whole-function
  pre-gate skipping 31% of the calls for a net ~440 ms. The original item body follows.**
  Law 1 applies to this item as much as to any: (b) removed
  the row that was 42% of it, so the remaining rows' SHARES are all wrong and the next
  target must be re-derived, not read off section 12. The rows that were behind the
  suppression apparatus and are now the top of the table: `type = resolved-symbol branch`
  296 ms / 58,547 closes with 40,308 exits, `type = union-receiver narrowing` 287 ms /
  62,615, `identifier-receiver special cases` 190 ms / 67,067, `pre` 151 ms, `shadowed-name
  receivers` 73 ms. **The structural question (b) answers for the flow blocks is open for
  ALL of them**: the exit census says 86% of calls leave before the property lookup and the
  emission tail is reached 0 times, so anything computed above an exit and consulted only
  below one is a candidate for the same treatment — and the retraction wrapper is now
  built, so a second deferral costs only the predicate. Re-measure first, then pick.
  Gate as (b): corpus suite + the 8-profile grid diffed BOTH directions +
  `--partitionCheck 2` + `cost_gate.py`.

- [x] **(ENGINE.2d)(b) — the round-790 item body follows, superseded by the entry above.**
  Of the block's original 1,219 ms,
  (a) took 438 with a LOCAL two-walker equivalence; what is left is the structural claim
  that the apparatus is in the wrong PLACE. Re-measure before starting (law 1): at HEAD
  after (a) the R_FLOW row is 1,111 ms, of which the plain walk is 756 over 22,270 calls,
  and **94.6% of those calls exit in the receiver-type resolved-symbol branch with the
  walk's answer consulted by nothing, while 0 reach the property lookup.** The lever is to
  DEFER the suppression to the ~20 emission sites it defends.
  **Why (a) does NOT calibrate (b), which is the round-790 lesson to carry.** (a) was
  provable because two walkers differ in ONE arm and the second is a pure REPEAT of the
  first, so its correctness argument is a two-way diff and its hazard is falsifiable by a
  counter that compares the two answers. (b) has neither: its correctness argument is a
  20-way case analysis over intervening emission sites, and no counter can falsify it,
  because the counter would have to know what those emissions WOULD have said. **So (b)
  needs a different instrument, not more of round 790's** — the candidate shape is to
  compute the suppression LAZILY (a memoized thunk evaluated at each emission site) so
  that the "~20 sites" question becomes a mechanical "every `diagnostics.add` in this
  function consults the thunk" rather than a case analysis, at which point the deferral is
  behaviour-preserving by construction. The walker-restricted exit census already bounds
  how much would then actually be evaluated: of the 22,270 walkers, **0 reach the property
  lookup and 77 reach the index-signature gates**, against 21,064 that exit in the
  receiver-type resolved-symbol branch.
  Gate as (a): corpus suite + the 8-profile grid diffed set-for-set BOTH directions +
  `--partitionCheck 2` + `cost_gate.py`.

- [x] **(ENGINE.2d) SUPERSEDED by the (a)/(b) split above — the original item body follows.
  (a) LANDED round 790; (b) re-scoped with its price re-measured.** THE TWO CANDIDATES LEVEL R EXPOSED, PRICED AND ORDERED — the smaller
  one is nearly free to prove, the larger one is the biggest single lever the arc has
  measured.** Both live in `checkMemberAccessMissing`'s flow-suppression row (round 789,
  `docs/perf/property-access-attribution.md` §§ 12-13). **Do (a) first**: it is a
  provable local equivalence, and its outcome calibrates whether (b)'s much larger prize
  is real or merely moves.
  **(a) THE ROUND-425 LOOP-ENTRY RETRY: 488 ms over 21,384 walks for AT MOST 28
  suppressions — >= 17 ms per suppression.** Block 1 runs `getNarrowedTypeForReference`
  and then, whenever that did not suppress, `getNarrowedTypeForReferenceFollowLoopEntry`.
  The counts make the price EXACT rather than inferred: the retry runs iff the plain walk
  did not suppress, so 22,270 - 21,384 = **886 plain suppressions**, and the
  walker-restricted exit census reports **914** walkers leaving the row in total, so
  everything after the plain walk accounts for <= 28. **The equivalence to prove:** the
  two walkers are line-by-line mirrors (CLAUDE.md's walker-mirror invariant) whose ONLY
  behavioural difference is the `FlowLoopLabel` arm — plain washes to the declared type,
  the retry follows `antecedents[0]`. So **a plain walk that provably crossed no
  `FlowLoopLabel` cannot be improved on by the retry**, and the retry can be skipped.
  **The three ways this goes wrong, all to be handled conservatively (unknown => run the
  retry):** the plain walk can TRUNCATE (`narrowWalkTruncated` already exists, reuse it);
  it can be served by EITHER memo (round 664's inter-walk memo, round 736's
  `NarrowFlowMemo`), in which case no traversal happened and no loop label was observed;
  and a served subtree can hide one. **MEASURE THE YIELD BEFORE BUILDING IT** — the gate
  is worth 488 ms only if loop-free walks are common, and tsc's checker is loop-dense; a
  counter on the plain walk's loop-label arm answers it in one run, and if the loop-free
  share is under ~30% say so and stop.
  **(b) THE WHOLE SUPPRESSION BLOCK IS IN THE WRONG PLACE: 1,219 ms, and 94.6% of the
  calls that pay for it exit before any emission site.** Of 22,270 walkers, 914 suppress,
  **21,064 exit in the receiver-type resolved-symbol branch**, and **0 reach the property
  lookup**. The apparatus runs at the TOP of the function; the emission it defends is at
  the BOTTOM, reached 77 times out of 67,258. The lever is to DEFER it — evaluate the
  suppression only where a TS2339 is about to be emitted.
  **The two hazards, both real and both already characterised by this arc.** (i) The
  intervening code EMITS (the New/Call/namespace/enum-member/cast blocks all emit and
  return), so a naive deferral lets through diagnostics the gate used to suppress — every
  emission site between the two positions must be covered, and there are ~20. (ii) It
  changes resolution-cache mutation ORDER, which round 754 showed can decide a verdict;
  falsify it EMPIRICALLY as round 788 did, not by inspection. **The prediction to write
  down first:** unlike round 788's cached resolution, a narrowing walk's result is memoed
  but round 735 measured 99.9% of walks COLD, so the bill should NOT move to another
  asker and the recovered time should approach the priced time. **If it does not, that is
  the more valuable finding** — it would mean round 788's law generalises past caches.

- [x] **(PERF.HW) DONE round 740 — the cores are REAL, and the question was the
  wrong one: a SEQUENTIAL run already consumes 3.15 of the 4 cores.** Artifact:
  `docs/perf/worker-scaling-round740.md`.
  **The box:** `nproc` 4, AMD EPYC-Rome @ 2445 MHz, 4 distinct `core id`s with ONE
  thread sibling each (**no SMT**), **no cgroup `cpu.max`**, steal **0.0 mean / 0
  max over 72 vmstat samples**. Steal alone does not settle it (a hard quota need
  not be accounted as steal), so the cores were tested directly with a
  tiny-working-set pure-CPU loop: **1.00x / 1.56x / 3.45x / 3.61x at 1/2/4/8-way**
  — four real, independent, unthrottled cores.
  **The table** (compiler profile, `--noEmit`, -Xmx4g, 3 reps, **round-robin
  interleaved across levels** rather than round 666's blocks; drift band re-derived
  from w1's own reps at **+-2.87%**):

  | level | median self | per-rep median delta | wins vs w1 | user CPU | cores |
  |---|---:|---:|:---:|---:|---:|
  | w1 | 27,126 ms | — | — | 85.6 s | **3.15** |
  | w2 | 24,452 ms | **-11.67%** | **3/3** | 85.3 s | 3.49 |
  | w4 | 25,976 ms | -4.24% (deltas STRADDLE ZERO = undecided) | 2/3 | 92.6 s | 3.57 |
  | w8 | 32,212 ms | **+19.37%** | **0/3** | 117.7 s | 3.65 |

  Reproduces round 666 (seq 27,873 / w2 24,669 / w4 flat) and adds the w8 point.
  **THE EXPLANATION, never measured before: 85.6 s of USER CPU for a 27.1 s wall.**
  Attributed by starvation — `-XX:CICompilerCount=2` -> 2.34 cores / 62.8 s user
  (**JIT ~21.7 s of CPU**), `-XX:ParallelGCThreads=1 -XX:ConcGCThreads=1` -> 3.06
  (**GC only ~2.7 s**): C2 compiling a ~110k-line `Checker.kt` never finishes inside
  a 27 s run. **Self time is FLAT across all four configurations (26.6-27.8 s)** —
  the JIT is not stealing from the compile thread (round 618 holds), it is consuming
  the cores a WORKER would need, leaving **~0.85 free**. Every level saturates at the
  same **~3.6-core ceiling**; what changes with worker count is TOTAL WORK (user CPU
  +0% / +8% / +37%), because each worker re-binds every file and runs all ~318
  collectors. **Not a JIT artifact — tested, negative:** freeing ~0.34 cores at w4
  left the wall unmoved (25,870 -> 25,574). Four INDEPENDENT concurrent compiles ran
  3.85x slower each (aggregate 1.03x) — which is **82% parallel efficiency** once
  3.15 is applied (4 x 85.6 core-seconds on 4 cores floors at 86 s; measured 105 s).
  **The box parallelises fine; our compile does not, because one copy already
  occupies 79% of it.**
  **Amdahl:** the w1/w2 fit gives P = 5,348 ms divisible (**19.7%**), R = 21,778 ms,
  infinite-worker floor **-19.7% (1.25x) ever**; the w1/w4 fit gives 5.7%, a 3.5x
  disagreement, so per the rule fixed before the run the model is contention-broken
  beyond w2. 19.7% vs round 666's 23% is NOT resolvable (P is twice a delta whose
  per-rep spread is 1,554 ms).
  **Is shrinking the 77% duplicated term worth attempting? NOT NOW, three reasons:**
  (1) the ceiling is 1.25x even if the duplication vanished; (2) there is no machine
  here to spend it on; (3) the mode is INCORRECT — see the next item.
  Predictions **5 of 6** (P5 falsified: w8 peak RSS 2,240 MB fits -Xmx4g easily, GC
  1.1 s of the 5.4 s regression — **no level was skipped for want of RAM**).

- [ ] **(PERF.HW.a) RE-OPENED ROUND 824 ON EVIDENCE — `--workers N` IS A RACE, AND ROUND
  754's BYTE-IDENTITY WAS UNDER-SAMPLED.** Artifact: `docs/perf/worker-scaling-round824.md`
  § 5. On the 8-core box, at a FIXED worker count, the diagnostic COUNT varies run to run:
  **w2 46/47 (5/5 split over 10 runs), w4 46/47 (3/3 over 6), w8 46/56 (1/6 over 7)**,
  against sequential **46 in 8 of 8**. Divergences are strictly ADDITIVE (added N, removed
  0 in every capture) and each outcome is byte-reproducible — the w8 56-set matches by
  `md5sum` across two captures — but which outcome you get is not. The w2/w4 extra is one
  line (`debug.ts:601:46` TS2345); the **w8 extras are a DIFFERENT family of 10 that does
  not contain it** (7x TS2322 in `transformers/declarations/diagnostics.ts`, plus
  `utilities.ts:10384` TS2344 and `11808`/`11859` TS2322). **`--partitionCheck` cannot
  diagnose this** — a static partition model cannot produce two answers for one worker
  count. **Why 754 read clean: with ~0.85 free cores the old box effectively serialized
  the workers, and one `--listAll` capture is one draw of a 50/50 coin — round 824's own
  stage-A w2 run came out at 46 and diffed byte-identically, reproducing the false green.**
  The upgrade did not introduce the race, it made it observable. **Detector: the error
  count over >= 5 runs per level (~110 s), never a single capture.** This blocks (M2)(b)
  and any `--workers` wall-time claim: you cannot tell a correctness regression from a
  coin flip. Round 754's fix (`defaultedInstantiationOfOpenGeneric`) stands on its own
  merits — it closed a real, deterministic, single-threaded bug; it just was not this one.
  Round 754's text follows.
  **(PERF.HW.a) round 754 — `--workers 2` and `--workers 4` both reach 46 and are
  byte-identical to sequential; `--partitionCheck 2` is EQUIVALENT on all eight profiles
  (`cac22abd`). THE ITEM'S OWN CLASSIFICATION WAS WRONG AND THAT IS THE FINDING: it is NOT
  the round-609 partition-collector class.** `--partitionCheck 2` did reproduce the
  divergence sequentially, as the item predicted it might — but a probe printing source
  type, target type and relation verdict in both views showed the two views resolve the
  SAME two types and disagree only on the VERDICT. No collector is starved, because there
  is no cross-file context involved: a reference that omits the type arguments of a generic
  whose every parameter has a default resolved to the RAW `Type.Interface`, whose members
  keep the un-substituted `T`, and nothing relates a `Type.Reference` to that. The
  sequential run was right BY ACCIDENT — a resolution-ORDER accident had cached that
  member as `any`. **A two-file scratch project reproduces the false positive with no
  partitioning at all**, which is what falsifies the round-609 reading outright. Fixed by
  normalising an open all-defaulted generic to its defaulted instantiation at the relation
  boundary (`defaultedInstantiationOfOpenGeneric` in `checkTypeRelatedToCore`) — NOT in
  `getTypeFromTypeReference`, where tsc fills it: filling at resolution costs 8 baseline
  lines of `typeVariableConstraintedToAliasNotAssignableToUnion` because a bare
  `TableClass` and an explicit `TableClass<any>` become the same interned instance and
  `aliasDisplayMap` excludes `Type.Reference`. Cost gate +0.00% on 19 of 20 counters.
  The **wall-time** prerequisite this item was queued to unblock is now met, but (PERF.HW)'s
  own verdict stands unchanged: the box saturates at ~3.6 cores and a sequential run
  already uses 3.15, so there is still no parallel wall-time claim to make here.
  Original text follows.
  **(PERF.HW.a) `--workers N` IS NOT BEHAVIOUR-PRESERVING — found by the round-740
  probe, NOT fixed there.** Sequential emits **46** diagnostics on the compiler
  profile; **every** parallel level emits **62**. The 16 extras are one family in one
  file — `src/compiler/utilities.ts:11349..11410`, TS2322 *"Type
  `EvaluatorResult<number>` is not assignable to type `EvaluatorResult`"* (and the
  `<string>` instantiations). Classification: **identical at w2, w4 AND w8** (so not
  a count-dependent partitioning effect) and **deterministic across reps** (so not a
  race) — the round-609 signature, i.e. a program-wide COLLECTOR iterating the INV.6
  partition view (`checkedResults`) instead of `binderResults`, so a partition worker
  never sees the context that suppresses it. **`--partitionCheck N` is the existing
  harness for exactly this and should reproduce it sequentially** (one run: if
  `--partitionCheck 2` also diverges, the bug is the partition MODEL and is
  debuggable single-threaded; if it does not, the bug is in the parallel path's fresh
  per-worker bind). This is a prerequisite for ANY future `--workers` wall-time
  claim, and it is cheap relative to M2 itself. No v1 impact — `--workers` is opt-in
  and off by default.

- [ ] ~~(cache/identity work of any shape)~~ — **CLOSED round 716 by measurement,
  do NOT re-open without new evidence.** (1) The context-bypassed resolution
  population is **68 ms** total (31,571 outermost calls @ 2.2 µs) — 0.35% of the
  compile. (2) Widening the round-548 INV.5(c) gate lifts hits 23% → 46% and
  measures **+28% wall** (6 interleaved pairs); memoizing the fingerprint (builds
  53,765 → 13,293) still measures **+11.9%**. (3) Pure identity keying (tsc's
  mapper-object shape) gets **4.1%** hits, because the context maps are
  re-allocated per install rather than reused per region. (4) The widened key also
  exposed that the context fingerprint is INCOMPLETE — 1,269 shape-different
  serves, all lib generic signatures (`(value: T, …)` served where
  `(value: Declaration, …)` was correct), i.e. the substitution input is ambient
  state captured by none of nsStack/tpScope/aliasArgs; that would have to be fixed
  BEFORE any widening, for a prize of 68 ms. Third independent confirmation of the
  round-659/665 law: **the cacheable population is the cheap tail.**

- [x] **(M0.1) Tail triage — CLOSED round 620 with the deletion hypothesis doubly
  dead.** Phases (a)–(c) ran round 619 (PassLab facility, corpus census —
  artifact `docs/perf/pass-census-round619.txt`, now carrying a correction
  header); the (d) consumer trace (round 620) OVERTURNED the "23 census-silent
  → deletion-ready" verdict, which rested on two flaws: (i) the census records
  only net-POSITIVE deltas — wipe-and-pin walkers (removeAll+pinDiag, net 0),
  rewriters, retractors, and collectors are census-silent while load-bearing —
  and (ii) Phase B's suite green was a FALSE GREEN: Inv0PassTimingTest's
  cleanup assigned `PassTiming.disabledPasses = emptySet()`, re-enabling the
  lab's disables for every test class after 'I' (the whole generated corpus);
  fixed to save-and-restore. The honest disable experiment (fixed cleanup,
  `--rerun`) fails 26 tests: 20 of the 23 are corpus-pinned (incl. one LOCAL
  pin — Inv4SpineBatch27Test for checkCrossFileUseBeforeDeclaration —
  invisible to a corpus-only census). DELETED (the real pool, 3 pure adders):
  checkModuleNoneConflict (TS1148) + checkExportAssignmentInSystem (TS1218) —
  module `none`/`system` are tsgo-removed kinds, their corpus tests
  generator-skipped — and checkUnicodeSurrogatePairImportBinding
  (unicodeEscapesInNames02's TS1127/TS2305 now flow from the general
  scanner/module-member paths; its errors subtest stays green without it),
  plus orphaned helpers. Gates: full suite 11,379/0; `--listAll` ×8
  byte-identical pre-vs-post on all 8 profiles; build warning-clean. Net wall
  value ≈ nil — the whole ~6.2 s tail is pinned; (M0.4) migration carries the
  lever. LAB DISCIPLINE addenda: `build/pass-lab.txt` is NOT a Gradle input
  (always `--rerun` a lab experiment), and a lab-run verdict is unverified
  until the disable is proven active in the SAME JVM that ran the tests.
- [x] **(M0.2) kindId table dispatch — DONE round 621 (2026-07-20), three
  commits.** NodeBase.kindId (dense per-CLASS Int, stamped by each class's
  `init` block — survives `copy()`, unlike nodeId/parent) + NodeKind.kt (138
  dense consts + the sealed-exhaustive `nodeKindIdOf` compile gate);
  forEachChild → javap-verified tableswitch 0..137; the 3 hot checkSpine
  dispatchers (spineEnterNode terminal when / spineUResEnter /
  spineUResDispatch) + the 13 remaining per-node walker whens → kindId
  lookupswitch (~5 int compares over sparse arm subsets). ccetSpineEnter
  deliberately SKIPPED (5-arm when with `is Block -> when (parent) {` +
  a union-smart-cast-dependent multi-class arm; cost/benefit). MEASURED:
  interleaved A/B (5 pairs, compiler profile) A 31,747 → D 30,713 ms median =
  **−3.3%, D wins 4/5 pairs** — inside the priced 2–4%. Gates: suite 11,385/0
  (+6 NodeKindIdTest pins), listAll ×8 byte-identical at each commit,
  warning-clean. Lesson: the scripted conversion mis-cut FOUR two-line
  `if`-header arms into empty-if mangles — corpus caught 3, a structural scan
  (line ending `{` + dedented bare `}`) the 4th; see the session note.
- [x] **(M0.3) CLOSED round 670 — the three landed slices were the arc's most
  reliable wins (−3.9%, −2.6%, −2.2%); the three REMAINING ones are priced
  below the drift band or are multi-session structural work, so none may land
  alone under the PERF ground rules.** Pricing, done this round rather than
  assumed: (i)'s cheap half — the globals-miss short-circuit — is worth
  **≲0.2%**. The probe claim is exactly right (measured live: **1,234,034
  globals lookups, 1,219,892 misses = 98.9%**), but 1.22M skipped HashMap
  probes at a realistic 20–40 ns each is only **25–50 ms of a ~28 s compile**
  (0.09–0.17%; even a generous 100 ns gives 0.44%). (v)'s undo-log is the same
  size — JFR put it at 1.1% of samples, and round 623 established that a JFR
  self-% is not a wall price. (ii) NodeLinks/SymbolLinks consolidation and
  (i)'s FULL form (Identifier → Int atom at scan time + int-keyed scope/member
  maps) are the only pieces that could clear ±2%, and both are multi-session
  structural changes touching the binder and every map. NOTE they are the same
  CLASS as the arc's winners — those replaced allocation-heavy per-call
  structures on hot paths (LongKeyMap/IntKeyMap/NarrowSeen), and atomization
  removes String hashing/equality from hot map traffic — so if perf work ever
  resumes, full atomization is the one lever left worth sizing. It must be
  PRICED first (the arc's rule): instrument the actual time in the map
  operations it would replace, do NOT trust the JFR "~15% in HashMap+String
  equality" figure that opened this item. Original item text follows.
  ORIGINAL: Layout campaign** (JFR-evidenced ~15% of wall in HashMap+String
  equality with NO single hot map — structure-class work, one interleaved-A/B'd
  slice per commit): (i) name atomization (Identifier → Int atom at scan time;
  int-keyed scope/member maps; a globals-miss bitset — the 1.48M probes are 99%
  miss); (ii) NodeLinks/SymbolLinks record consolidation over per-file dense
  nodeId arrays (tsc's exact structure; symbol ids need per-worker dense spaces
  under INV.6 — node ids are per-file dense already); (iii)+(iv) **DONE round
  621: −3.9% wall (31,180 → 29,955 ms median, 5/5 pairs)** — `LongKeyMap`
  (open-addressing Long→V, EXACT packed-id keys, 0L sentinel) fast-paths the
  three intern caches' dominant shapes (null/empty/1-arg refs — null/empty
  pack alike, reproducing the old string key's `"id|"` conflation
  byte-exactly; 2-member unions/intersections; bigger shapes keep the string
  maps) + the `normalizePath` memo; (vi) **DONE round 622: −2.2% wall
  (30,364 → 29,697 ms median, post wins 5/5 pairs)** — `IntKeyMap`
  (open-addressing Int→V, `Int.MIN_VALUE` sentinel: symbol ids span the
  positive main space AND the ≤−2 INV.2(c) scope space, so 0/negative are
  legal keys) replaces `HashMap<Int, ·>` for symbolTypes/declaredTypes/
  symbolTargets, and `NarrowFlowMemo` (parallel int-key/int-depth/Type
  arrays, serve/overwrite depth rules byte-exact, pinned both directions in
  IntKeyMapTest) replaces the narrowing walks' per-invocation
  `MutableMap<Int, Pair<Int, Type>>` — a fresh map per depth-0 walk
  (~111k/compile) allocating a boxed key + `Pair` + map node per store on
  the hottest checker path; (vii) **DONE round 622: −2.6% wall (30,124 →
  29,351 ms median, wins 4/5 pairs)** — int-specialized `NarrowSeen`
  (open-addressing IntArray slots + tombstone removal — popToMark removes
  in reverse insertion order, which linear probing cannot slot-shift;
  EMPTY slots only from rehash, so present-id probes never meet EMPTY
  early — + IntArray add-log; was a double-boxing HashSet+ArrayList on
  every flow-node visit), pinned by a 60k-op randomized oracle vs the old
  form; (v) undo-log
  (the proven NarrowSeen mark/pop pattern) replacing HashMap(other) scope
  copies (putMapEntries 1.1%) — also reduces M1's epoch churn. Do NOT reach
  for a JVM-only map library (build-change guardrail + multiplatform);
  `LongKeyMap`/`IntKeyMap` are the in-repo reusable pieces for later slices
  (IntKeyMap values are non-null and never iterated — the compiler flags
  both constraints at any unsuitable conversion site); (viii) **DONE round
  623, measured NEUTRAL (−0.30% median over 10 interleaved pairs, post wins
  6/10 — below the drift band, NO wall claim)** — lazy/unboxed Parser line
  starts (the eager per-parse table was 5.3% of JFR self samples, only ever
  consumed by diagnostic line/col formatting), the
  `fileDeclaresNonGenericType` fileResults-index + `file|name` memo (was an
  un-memoized per-type-reference top-level statement scan — quadratic
  insurance for bigger projects), and ccetSpineEnter's kindId dispatch (the
  one dispatcher M0.2 skipped, now hand-converted). Landed as structural
  slices on the corpus + listAll ×8 byte-identity gates; the JFR lesson
  (counted-loop self-% is safepoint-bias-inflated + parallel-crawl savings
  don't move serial-dominated wall — A/B before believing any self entry)
  is in the round-623 session note.
- [x] **(M0.4) CLOSED at 35 passes by the round-659 arc measurement — see
  (M0.4-AB) for the number and the verdict. NOT a wall-clock lever: 75% of a
  migrated pass's cost reappears inside checkSpine (the 35 deleted rows summed
  3,146 ms; checkSpine grew +2,358 ms), the interleaved arc A/B is +0.24% on
  compiler / −1.6% on harness = inside the drift band, and finishing the
  remaining ~90 rows would buy ~1.1 s (~4%) for ~90 rounds. Do NOT migrate
  another tail pass for performance; migrate one only when it is on the path of
  another change, and keep this item's migration-pattern zoo as the reference
  for HOW (it is complete and each shape is documented below).** The original
  item text, and the per-round record of all 35 migrations, follows —
  Migrate the surviving pinned tail into the spine (the documented
  migration-pattern zoo), cost-descending; retire dead migration scaffolding as
  it goes (emit-twice arms whose legacy side is gone, the dead m3
  truncation-mark blocks). Post-round-619 this carries the WHOLE tail lever
  (~6.2 s, all corpus-pinned — the deletion pool measured 59 ms): the worklist
  is the `--passTiming` cost table intersected with
  `docs/perf/pass-census-round619.txt` (top by cost at the round-624 HEAD
  table: checkObjectSpreadInvalidTypes 165.6 ms — **MIGRATED round 624**,
  checkArrayPushDiscriminatedUnionElements 138 ms — **MIGRATED round 624**,
  checkImplicitThis 127 ms — **MIGRATED round 625** (the frameless variant:
  a pass threading ONLY downward context — no statement-ordered state —
  migrates as a pure pull-based per-anchor ancestor fold, no frames, no
  leave hook, no memo when anchors are rare),
  checkFnTypedParamCalls 119 ms — **MIGRATED round 626** (the downward-MAP
  variant: FnParamCtx rebuilt-at-boundaries/accumulated-through-boundaries
  reproduces as the pull-based fold WITH a per-boundary-child ctx memo —
  anchors are every Identifier-callee call, too frequent for the round-625
  memo-free form — plus a memoized BINARY reach classifier: no multi-state
  statuses needed when every (parent kind, child slot) pair decides descent
  unambiguously),
  checkAbstractClassInstantiation 113 ms — **MIGRATED round 627** (the
  collector-prepass variant: four FILE-scoped collectors reproduce as
  per-file spine-setup state, not frames; the statement-LIST overlay
  (add-abstract-then-remove-shadowed, a pure function of the ancestor
  list-owner chain SourceFile/Block/ModuleBlock/CaseClause/DefaultClause)
  rebuilds pull-based per anchor with a per-owner memo; the
  `[A].map(cls => …)` callback-param typeof extension recovers on the
  anchor climb folded OUTERMOST-first — node coverage is identical
  between the legacy handled/unhandled branches, so the reach classifier
  needs no special case; no ambient sandwich — the emission reads no
  checker ambient),
  checkSymbolToStringConversions 108 ms — **MIGRATED round 628** (the
  downward-SETS variant: accumulate-only (symbolNames, tpNames) sets
  rebuild pull-based per anchor; the per-body whole-list locals PREPASS
  reproduces as per-boundary LEVELS with only fn bodies and ModuleBlocks
  as collection boundaries — inner Block/clause re-collects were always
  subsets; two reach edges differ from the fp/ai classifiers: case-clause
  and bare for-initializer EXPRESSIONS are reached),
  checkDefiniteAssignmentViaFlowGraph 105 ms — **MIGRATED round 629** (the
  FILE-END variant: a pass whose per-file body is a positional dedup scan
  over prior diagnostics + whole-file flow walks migrates as a dispatch in
  checkSpine's per-file loop AFTER spineWalkFile returns — never
  per-anchor — so the dedup scan sees the file's spine-emitted TS2454s;
  the walker family stays verbatim, the only ambient install is
  currentFlowGraph save/restore, and the B223 sibling stays at its own
  pass slot since it scans no prior diagnostics),
  checkSameTargetReferenceCastOverlap ~123 ms — **MIGRATED round 630** (the
  SHARED-WALKER variant: only the pass's whole-file driver is deleted — the
  walkTypeAssertionsInStmt/-InExpr recursion SURVIVES for the cast-overlap
  sibling passes, so the reach classifier mirrors the shared walker's arms
  and must stay IN SYNC with any future walker-arm change; the first
  TYPE-RESOLVING tail migration — per-anchor getTypeOfExpression/
  getTypeFromTypeNode/relation calls interleave into the spine walk, gated
  clean by corpus + listAll ×8; ambient sandwich = currentCheckFileName +
  a nulled currentFlowGraph around the emission pair),
  checkBindingPatternComputedIndexSig ~120 ms — **MIGRATED round 631** (the
  MULTI-ANCHOR-KIND variant: three emission families dispatch from one
  enter hook over seven anchor kinds, member-parameter emissions gated on
  the member's PARENT kind — objlit/class-EXPRESSION members emit,
  FunctionDeclaration/class-DECLARATION members never do; the reach
  classifier is a FROZEN copy of the deleted walker's arms, deliberately
  NOT shared with the surviving cast walker's spineCoEdge, which it
  matches except FunctionDeclaration parameter defaults; the TS2537
  emitters install the spine-entry RESTING currentFileLocals per emission
  — the legacy pass never installed it),
  checkConstEnumDiagnostics ~123 ms — **MIGRATED round 632** (the
  FILE-GATED variant: the legacy whole-file collectConstEnumDecls gate
  reproduces as per-file setup state — anchors inert in files without
  their own const enum; the TS2567 top-level merge scan rides setup; a
  resolution-CONDITIONAL walker descent (property/element-access bases
  skipped when the base IS a const enum) reproduces as an unconditional
  edge + an anchor-side parent pre-filter, exactly equivalent because
  neither branch can emit at a base — keeping the classifier purely
  structural), then
  checkNullTypeAssertionOverlap ~104 ms — **MIGRATED round 633** (the
  FLAG-ARM-LIFT variant: the `inNullCastOverlapPass`-gated emitters
  lift out of the SHARED walker onto the round-630 anchors —
  spineCoStatus/spineCoEdge reused verbatim; binderResults-iterating
  driver → the spine's partition view, gated `--partitionCheck 2`
  EQUIVALENT ×8), then
  SKIP checkCrossFileModuleAugmentationDuplicates (114 ms — CROSS-FILE
  aggregation, not per-file spine material), then
  checkProtectedMemberReadAccess ~103 ms — **MIGRATED round 635** (the
  PUSH-BASED ORDER-DEPENDENT variant, the round-531 arith pattern's first
  M0.4 application: a pass whose downward map is statement-order MUTATED
  (per-declaration `vars[nm] = …` recordings that LEAK through
  block/if/loop/arrow descents and COPY at nested-fn boundaries)
  reproduces as LIFO frames at fn-like boundaries + per-declaration
  recordings at VariableDeclaration LEAVES (the legacy walk-then-record
  order), with a 5-STATE reach classifier — CONTAINER_FILE/CONTAINER_NS
  split because only FILE-level ExpressionStatements are walked with the
  per-file topVars map, installed by INSTANCE so IIFE-body recordings
  persist across top-level statements; the `=`-LHS write skip is an edge
  (LHS subtree never read-walked, the write check fires at the
  BinaryExpression anchor under the frame-maintained pmrInClassMethod
  gate)), then
  checkPropertyInitialization ~99 ms — **MIGRATED round 636** (the
  MULTIPLICITY variant: the legacy ClassDeclaration statement arm
  double-walks member bodies — checkClassPropertyInit's nested recursion
  PLUS the arm's own member loop — so nested classes emit 2^depth
  duplicate TS2564s, reproduced by an INT-valued reach classifier
  returning a VISIT COUNT (spinePiMult: a bottom-up climb multiplying
  per-edge factors {0,1,2}; every factor local to one edge, no
  multi-state fold — the arrow/fn-expr partial-body restriction resolves
  by peeking at the Block's parent); the anchors repeat the split-out
  checkClassPropertyInitEmit that many times; the recursion walkers
  SURVIVE for the B439 declarationOnly dispatch — the round-630
  shared-walker rule, spinePiEdge mirrors them);
  checkGenericIndexWrite 117.3 ms — **MIGRATED round 637** (the
  DOWNWARD-MAP variant's third application: the (tparams, tpProps,
  refs) triple rebuilds pull-based per anchor with a per-boundary-child
  memo — tparams ACCUMULATE through class/fn boundaries, refs REBUILD
  per fn-like boundary from params + the body-WIDE collectTpLocalsMap
  prepass (whose descent is NARROWER than the scan's — switch/try
  locals uncollected, frozen + pinned), tpProps from the nearest
  enclosing class member (RESET by a nested FunctionDeclaration,
  cleared for property initializers); anchors are `=` binaries with a
  paren-unwrapped ElementAccess LHS; zero TS2862 on all 8 profiles →
  the listAll gate pins pure non-perturbation);
  checkArgumentsCollision 116.8 ms — **MIGRATED round 638** (the
  CONSTANT-CONTEXT variant, the simplest yet: the only downward value is
  the per-file isModule boolean, so no frames, no ctx memo — the
  per-construct declare/body gates re-derive at the anchor from the
  construct node + its parent kind (class-DECLARATION members need
  body + !class-declare and its set-accessors never param-check, while
  class-EXPRESSION/objlit members param-check unconditionally — frozen
  asymmetries, pinned); a WIDER reach than gIdx (arrows/fn-exprs/
  class-expr members/objlit members/template spans/typeof operands
  descend; if/ternary conditions, loop/switch heads, class-decl property
  initializers, declare-namespace bodies stay silent) = a fresh edge
  set; the run-level dispatch gate (target < ES2015 || any non-dts
  module file) becomes the run-active flag);
  checkEvolvingEmptyArrayImplicitAny 103.2 ms — **MIGRATED round 639** (the
  PER-LIST-OWNER variant: a per-STATEMENT-LIST scope pass dispatches each
  scope's list ONCE at its owning SourceFile/Block/ModuleBlock enter, gated
  by a multi-state reach classifier carrying the deleted evRecurseScopes'
  level-skipping quirks — try/catch/finally clause statements and
  case-clause statements recurse WITHOUT forming a scope list (a candidate
  declared directly there never fires) while a Block statement inside them
  IS a scope; arrow/fn-expr bodies and class EXPRESSIONS are never scopes;
  a dotted `namespace A.B` IS one (the parser keeps a direct ModuleBlock
  body — the scope map's "never" guess was wrong, caught by the pins);
  Part 2 is TYPE-RESOLVING → per-dispatch ambient sandwich of resting
  currentFileLocals + per-file currentCheckFileName + a nulled
  currentFlowGraph);
  checkUndefinedClassInterfaceName 123.9 ms — **MIGRATED round 640** (the
  TWO-INTERLEAVED-WALKS variant: a pass running two recursions with
  disjoint node sets — the statement-only name-check walk (never descends
  fn/class-member bodies) + the yield walk started at name-reached
  FunctionDeclarations — reproduces as ONE multi-state classifier whose
  statuses carry the walk identity AND the downward generator flag
  (UY_NAME / UY_YGEN / UY_YNON, plus UY_MEMBER bridging a yield-walked
  container's member to its body/initializer); the frozen member filters
  ride the container edges — class DECLARATIONS walk accessor bodies +
  prop initializers, class EXPRESSIONS method/ctor only, objlit members
  methods only, accessors never; the legacy left-spine BinaryExpression
  fold reduces to plain left/right edges, reach-equivalent; zero
  emissions on all 8 profiles → the listAll gate pins pure
  non-perturbation);
  checkSuperRefInRebindingScope 113.1 ms — **MIGRATED round 641** (the
  rebound-boolean-as-status variant: the walk's one downward boolean
  rides the classifier status — fn-decl/fn-expr bodies reset to rebound,
  arrows/ModuleBlocks preserve, class-member bodies/prop initializers
  reset to clear via a member-carrier status; the frozen `super(...)`
  CALLEE skip is the anchor's direct-parent gate so a parenthesized
  super callee still fires; object literals skipped entirely — the
  sibling checkSuperInObjectLiterals is position-disjoint);
  checkInvalidAssignmentTargets 105.8 ms — **MIGRATED round 642** (the
  INT-depth classifier's second application: the shared `checkDepth`
  frame counter reproduced per node with +1 on every expression parent's
  outgoing edge — statement lists nested inside expressions inherit the
  elevated ambient — and NO right-spine absorption, so deep chains prune
  at the 200 cap, pinned at the exact boundary; the orphaned checkDepth
  counter deleted from Checker + CheckerState);
  checkTypeParameterDefaults 150 ms — **MIGRATED round 643** (the
  SPLIT-PRODUCER variant + the first PARSE-RECORDED candidate set: a
  pass whose side-set write cannot ride the spine — cross-file/
  earlier-in-file display consumption — SPLITS: the TS2368/TS2744
  emissions anchor at the ten TP-list-bearing construct kinds over a
  binary reach classifier, and the pre-spine producer consumes
  SourceFile.typeAliasesWithTpDefaults (recorded at the parse site,
  moduleSpecifiers-style — no tree walk; 0.4 ms vs the legacy 150 ms
  row) FILTERED through the SAME classifier — one frozen edge set
  serves both halves, and a speculative-parse discard classifies
  unreached via its detached parent chain. Producer-scan lesson: a
  forEachChild worklist re-scan of the tree costs MORE than the legacy
  walk it replaces (264 ms raw, 218 ms TypeNode-pruned) — parse-time
  recording is the shape for future split producers);
  checkExpandoFunctionNestedReads 99 ms — **MIGRATED round 644** (the
  file-gated + pull-based-shadow combination: the write collector runs
  at per-file SETUP — it never descends function-likes, so the
  double-walk of top-level expression code is bounded and the anchors
  emit inline against the COMPLETE declared map, no buffering; the
  ChainedNameSet shadow chain rebuilds pull-based per anchor — every
  fn-like ancestor of a reached anchor was entered through its walked
  interior, so each contributes its layer; anchors pre-gate on the
  candidate-receiver TEXT, so the memo-free rare-anchor rule applies);
  checkStrictModeIdentifiers 96 ms — **MIGRATED round 645** (the
  MODE-ROUTED variant: the first pass whose SourceFile root edges
  route by a per-file MODE decided at setup — module/strict/fn-local —
  and whose statuses carry the walk IDENTITY across two interleaved
  families: the strict emission walk and the fn-local SEARCHING walk,
  with prologue-tested flips at fn-body edges; the module top-level
  specials continue INTO the strict walk at initializer/body edges;
  class subtrees unreached by construction — the legacy class-element
  walk ran with an EMPTIED restricted set, so it could never emit; the
  `var eval` TS2300/TS6203 pair rides the VariableStatement anchor);
  checkConstLiteralComparisons 95 ms — **MIGRATED round 646** (the
  SINGLE-ADDING-ARM variant: a downward-MAP pass where only ONE arm
  ADDS entries — the for-init const-literal transform; the whole-list
  shadow prepass and fn-param boundaries only REMOVE — needs no
  per-boundary memo: the map is empty at any anchor without a
  ForStatement ancestor whose const init adds one of the anchor's
  operand names, so a cheap parent-climb pre-filter guards the precise
  memo-free reach+scope fold; the legacy left-spine binary iteration
  dissolves into plain left/right edges);
  checkSuperInObjectLiterals 91 ms — **MIGRATED round 647** (the
  boolean-as-status shape's second application with OBJLIT anchors: the
  legacy ObjectLiteralExpression arm SPLITS — its per-property EMISSION
  half becomes the anchor-called emitObjLitSuperProperties running the
  bounded findObjLitSuperRefs leaves, while its walk-continuation half
  dissolves into classifier edges (objlit method/accessor bodies →
  SU_VALID via the SU_OMEMBER carrier; a PropertyAssignment initializer
  is a plain PRESERVE edge — the legacy fn-expr/arrow initializer
  dispatch reproduces exactly on the general FunctionExpression-resets/
  ArrowFunction-preserves arms); the classHasExtends boolean rides the
  CARRIER CHOICE (SU_CMEMBER_EXT/SU_CMEMBER_NOEXT), not a separate
  channel; anchors pre-gate on the emission shape before the memoized
  climb);
  checkTypeParamStrictSubtypeCast 93.7 ms — **MIGRATED round 648** (the
  FOLD-THROUGH variant: the first classifier reusing ANOTHER pass's edge
  set — TC_SHARED hands off to spineCoEdge; pull-based TP-scope layering
  rebuild with method-param typing; the B402 empty-objlit local set as a
  per-list-memoized union over enclosing TPC lists);
  checkDeleteOperator 86.8 ms — **MIGRATED round 649** (a straight
  template application: binary reach classifier over the deleted walker
  arms, one per-file isStrict setup boolean, resting-currentFileLocals +
  null-flow sandwich with currentCheckFileName deliberately untouched);
  checkConstructorParamInInitializers 85.5 ms — **MIGRATED round 650** (the
  multi-state class-anchored reach classifier: CP_STMT/CP_EXPR reproduce
  the two deleted routing walks, CP_ABODY the restricted arrow/fn-expr body
  — its three permitted statement kinds handed straight to CP_STMT, which
  descends them to CP_EXPR identically to the legacy inline loop, so no
  extra restricted-body statuses — and CP_MEMBER the class-member conduit
  carrying the DECLARATION-vs-EXPRESSION descent asymmetry, member bodies +
  property initializers for a class DECL, property initializers only for a
  class EXPR; fully syntactic, no ambient sandwich);
  checkAbstractMemberContext 81.6 ms — **MIGRATED round 651** (the
  AMBIENT-CLIMB variant: a downward BOOLEAN that is a pure function of the
  ancestor chain need not ride the classifier status (round 641) NOR a
  frame stack — it is re-derived by a SEPARATE cheaper ancestor climb
  (spineAbInAmbient), halving the status space to AB_STMT/AB_EXPR/AB_MEMBER;
  sound because `inAmbient` is monotone (`|| Declare in modifiers` at
  ClassDeclaration/ModuleDeclaration, pass-through everywhere else) and the
  ONLY walked edges out of those two kinds are into member BODIES / the
  MODULE BLOCK, so for a REACHED node "some `declare` class/module ancestor
  exists" IS the threaded OR — the climb must therefore run only AFTER the
  reach check passes; one AB_MEMBER conduit serves both class DECLARATIONS
  and class EXPRESSIONS since Ab recurses member BODIES only, never property
  initializers, so there is no DECL/EXPR asymmetry to encode; four
  deliberate divergences from the same-shaped round-650 CP fold, each pinned
  both directions: NO declare-skip anywhere (the flag suppresses only the
  EMISSION), arrow/fn-expr Block bodies are the FULL statement walk (a class
  DECLARATION in an arrow body IS reached), and the switch SUBJECT and
  ternary CONDITION ARE walked);
  checkImplicitAnyYieldExpressions 107.2 ms — **MIGRATED round 652** (the
  ANCHOR-SIDE-GATE variant of the round-641 boolean-as-status shape: the
  downward `inGen` boolean rides the status — it is RESET by every nested
  function-like, so it is NOT monotone and round 651's ambient climb does
  NOT apply — while a frozen EMISSION SKIP whose condition is decidable
  from the ANCHOR's OWN parent chain (the round-479 discarded-result rule:
  a statement-position `yield x;`, parens transparent, draws nothing) is
  re-expressed as a four-line paren-climb AT THE ANCHOR instead of a reach
  state, which would have doubled the status space; ONE arm set serves both
  deleted walks since statement and expression node classes are disjoint —
  no walk-identity channel; IY_MEMBER carries class member bodies AND
  property initializers, both → IY_NON, so no DECL/EXPR asymmetry and class
  EXPRESSIONS are never walked);
  checkAbstractMemberAccessInConstructor 68.4 ms — **MIGRATED round 653**
  (the SPLIT-AT-THE-RE-ENTRY-BOUNDARY variant: a pass whose per-anchor
  leaf can RE-ENTER the pass on a nested anchor splits at that boundary
  and KEEPS the routing walkers alive — the spine reproduces the
  ROOT-driven reach, the surviving recursion the LEAF-driven reach, and
  the two compose to the legacy multiplicity (a class expression in a
  PROCESSED constructor is processed TWICE) with no INT-valued round-636
  classifier; the round-630 sync rule applies to the survivors. Second
  move: the legacy VariableStatement NAME OVERRIDE is recovered
  ANCHOR-side from the parent declaration's classifier status — round
  652's anchor-side gate applied to a NAME, since that arm's reach is
  identical to the plain initializer edge. Reach is PURELY STRUCTURAL —
  the routing walk threads no downward value and the emission walk's
  inDeferredFn lives inside the surviving leaf, so neither a status
  channel nor an ancestor climb is needed; the file-scoped classMap
  prepass rides setup);
  checkIncDecTypeParamOperands 68.3 ms — **MIGRATED round 654** (the
  STRUCTURAL-TWIN variant, the cheapest migration class: when the next
  tail pass is a structural twin of an already-migrated one — here round
  637's checkGenericIndexWrite, whose own source comment says it mirrors
  THIS pass's scope threading — the migration is a TRANSCRIPTION of the
  twin's shape (same boundary-child set: fn-decl/method/ctor/accessor
  BODIES + class-property INITIALIZERS; same pull-based per-anchor ctx
  memoized per boundary child; same memoized binary reach classifier),
  and the whole cost is (a) diffing the two legacy walkers' arm sets and
  (b) pinning the differences — here exactly TWO expression arms
  (TypeAssertion + satisfies casts are transparent to this walk, absent
  from gx's). The downward triple is gx's with SETS instead of maps:
  tparams accumulate, tpProps rebuild from the nearest enclosing class
  DECLARATION (reset by a nested FunctionDeclaration), tpLocals rebuild
  per fn-like BODY from the body-wide prepass);
  checkConflictMarkers 67.8 ms — **NOT SPINE MATERIAL, OPTIMIZED IN PLACE
  round 654 tail** (a pure per-file SOURCE-TEXT scan with no AST walk at
  all: nothing to fold into the spine, cost INTRINSIC, lever ALGORITHMIC.
  A marker is meaningful only at a LINE START, so the scan now hops line
  starts via `indexOf('\n')` instead of testing every character —
  67.8 → 26.5 ms, 2.6×; the intermediate four-`indexOf(marker)` form was
  REJECTED at 45.1 ms because `=`/`<`/`>` false-start on nearly every
  line. The pass keeps its own slot; gated by 9 new pins + the ACTIVE
  generated conflictMarker* `.errors.txt` subtests + listAll ×8);
  checkImplicitAnyNewExpressions 66.9 ms — **MIGRATED round 655** (the
  NO-DOWNWARD-VALUE variant, the simplest class: when the deleted
  recursion's parameter list is CONSTANT — every recursive call passes
  the arguments it received — there is no ctx rebuild, no frames, no
  leave hook and no status channel; the whole migration is the round-649
  spineDelStatus shape with a different edge set. Two per-migrator
  notes: the ambient install is the FILE's own binder locals because the
  legacy DRIVER installed them itself (unlike the resting-locals
  captures of rounds 624/625/631/649), and the arm diff against the
  same-shaped `del` classifier is real — objlit method/accessor bodies,
  `for`-head DECL-LIST initializers and switch case EXPRESSIONS are
  walked here and not there);
  checkArgumentsInClassFieldInitializers 82.9 ms — **MIGRATED round 656**
  (the round-640 TWO-INTERLEAVED-WALKS variant's second application, with
  one template refinement: when two interleaved walks share MOST of their
  arms — here ~30 of ~45, every shared arm having an identical child set
  whose child simply KEEPS the parent's status — write the fold keyed on
  the node KIND and branch on `pStatus` only inside the differing arms,
  so the walk identity "rides along" a pass-through arm (`-> pStatus`)
  instead of duplicating 30 arms under an outer `when (pStatus)`; round
  640's outer-status form is right only when the two walks' node sets are
  near-DISJOINT. Three statuses: AF_ROUTE (class-finding), AF_EMIT
  (inside a property initializer / static block, where the `arguments`
  Identifier anchor fires), AF_MEMBER (the class/objlit member conduit
  whose member KIND picks the resuming walk); the five reach asymmetries
  — EMISSION-only if/loop heads + switch subject + case expressions,
  EMISSION-only objlit method/accessor bodies, EMISSION-only arrow
  parameter defaults, a ClassExpression `declare`-gated in ROUTING and
  UNGATED in EMISSION, ROUTING-only namespaces and `export =` — are the
  whole risk surface and are pinned both directions; multiplicity 1
  everywhere, fully syntactic, NO ambient install at all);
  checkArrayToClassCastOverlap 72.5 ms — **MIGRATED round 657** (the FOLD-IN
  class, the cheapest there is: the pass OWNED NO WALK — it only DROVE the
  SHARED walkTypeAssertionsInStmt/-InExpr recursion with its emitter as the
  callback, and a sibling driving the SAME walker was already on the spine
  (round 630), so CO_REACHED IS its reach by construction and the whole
  migration is one leaf call added to that arm + the driver deleted. No
  classifier, no edge diff, no frames/ctx/status/memo. Two placement details
  carry the correctness: the leaf goes LAST in the arm because its legacy slot
  ran after the round-630/632 passes (insertion order at a shared position),
  and the legacy ambient needs no new install — checkSpine's per-file loop
  already sets the file's binder locals and the shared arm installs
  currentCheckFileName. BEFORE picking any next tail pass, grep its driver for
  a shared-walker call: a fold-in is orders of magnitude less work);
  checkTypeParamTypedOps 71.0 ms — **MIGRATED round 658** (the round-635
  PUSH-BASED ORDER-DEPENDENT variant, and the first whose downward context
  includes a TYPE-SYSTEM AMBIENT rather than only plain data: `tpVars` is
  MUTATED in statement order, LEAKS through block/if/loop/try/namespace
  descents and REBUILDS at every fn-like body from its own parameters, so it
  rides a LIFO of frames at exactly the legacy new-map/new-scope boundaries —
  while the legacy `withInternedTpScope` REGION, which a spine migration
  cannot hold open across nodes, is reproduced by CAPTURING its result: run
  it at the boundary for its interning + constraint-materialization side
  effects, read currentTypeParamScope/currentTypeParamAstForOps from inside
  the block, carry the pair on the frame and install it around each dispatch
  only. The VariableStatement two-loop order — record all declarations, then
  emit on the initializers — reproduces as a recording dispatch at that
  statement's ENTER. Reach is unusually NARROW: `for` heads, `switch`, object
  and array literals, templates, all four cast forms, await/yield,
  typeof/void/delete operands, spreads, comma chains and — the big one —
  ARROW and function-EXPRESSION bodies have NO arm, and on the class side only
  method/ctor/accessor BODIES are reached);
  next per-file candidates by cost (round-656 table, the migrated rows
  gone): **checkVarHoistRedeclaration 68.9 ms**,
  checkCallTypeArgCount 66.2 ms,
  checkIllegalSuperCallsInNestedFunctions 62.7 ms,
  checkTypeArgumentConstraints 62.7 ms, checkSpreadPropertyOverrides
  62.5 ms
  (checkCrossFileModuleAugmentationDuplicates, now 109.7 ms, stays
  SKIP — cross-file aggregation, not per-file spine material; the tail is
  now VERY FLAT — no per-file row above 73 ms, so per-pass wall value is
  small and the remaining ~90 passes >20 ms carry the residual ~4.3 s). Migration protocol per
  pass (the round-624 template): slot-move pre-gate commit (intact pass to the
  post-spine slot, corpus + listAll ×8), then the migration commit (frames at
  the legacy copy edges, memoized reach classifier, per-dispatch ambient
  sandwich + pull-based TP rebuild, local pins, corpus + listAll ×8). A
  single-pass wall delta (~0.5%) is BELOW the drift band — the per-item
  evidence is the `--passTiming` table (the pass's row gone, checkSpine's row
  not inflated), not an interleaved A/B; A/B the ARC once several passes land.
- [x] **(M0.4-AB) ARC MEASUREMENT PAID — round 659. VERDICT: STOP the arc at 35
  passes; (M1) is next.** The number the arc owed since round 624 is in, and it
  says the migration is NOT a wall-clock lever. Method as queued: pre-arc binary
  `4b0dfcc7` (round-623 HEAD) vs HEAD `e9d8279d`, both class dirs kept, NO
  recompile between measurements, alternating within-pair order.
  **compiler profile, 6 interleaved pairs: pre median 28,945 ms → post 29,015 ms
  = +0.24%, post wins 3/6** (per-pair deltas −667…+1,190 ms — the noise spread
  is ~4% of total, an order of magnitude above the effect). **harness profile,
  2 pairs: 40,256 → 39,605 = −1.6%, post wins 2/2.** So the true effect is a
  SMALL gain somewhere in 0–2%, entirely inside the ±2% drift band the ground
  rules refuse to land on.
  **THE MECHANISM, measured (this is the transferable part): 75% of a migrated
  pass's cost REAPPEARS INSIDE checkSpine.** Same-run `--passTiming` both sides:
  the 35 deleted rows summed **3,146 ms**, while checkSpine grew
  **18,896 → 21,253 = +2,358 ms**. The tail was NOT redundant traversal that a
  single walk eliminates — it is per-node work that a single walk still has to
  do, now as ~35 `when (kindId)` dispatches plus memoized ancestor-climb reach
  classifiers on EVERY node of EVERY file. The multiplication moved from "N
  walks over the tree" to "N dispatches per node", which is the same order.
  **THE RATE ARITHMETIC that closes the arc:** the residual is ~25% of migrated
  cost. The remaining tail is ~90 rows >20 ms ≈ 4.3 s, so finishing it buys
  ~25% × 4.3 s ≈ **1.1 s ≈ 4% of wall — for ~90 single-pass rounds.** (M1)
  targets ≤15–20 s from ~29 s = **30–45%**. The arc stops here; the 35 landed
  migrations keep their real value (they are behaviour-preserving, they deleted
  ~8 k lines of walker recursion, and the spine is now the single place per-node
  checks live), but no further pass is migrated FOR PERFORMANCE. Migrate one
  only when it is on the path of another change. Bench TSV rows carry both
  medians locally (`bench/` is gitignored — the round-659 session note is the
  durable record and carries every per-pair number).
- [x] **(M1) COMPLETE (rounds 660–665) — banked 0.83 s (−2.93%), which is the
  arc's only live win; the rest of the advertised prize was never there.**
  Ledger: an original "≤15–20 s path" (30–45%), retired at round 660 for a
  measured ~3.3 s, corrected to ~2.5 s at round 662 when a key collision was
  found in the instrument, of which round 664's live dependency-keyed flow-walk
  memo banked 0.83 s and round 665 showed the remaining ~1.1 s expression half
  was a 35× over-estimate (real value 30 ms). What survives as reusable
  machinery: the tagged epoch (`bumpExprEpoch`), the dependency-keyed live walk
  memo, and three shadow classifiers that made every one of those corrections
  cheap. Original framing, retained for context. Realistic prize ~3.3 s of ~29 s = 11–13% — NOT the
  "≤15–20 s path" this item used to claim (that figure was never measured; it
  is retired).** Ceiling arithmetic, from the round-660 `--passTiming` run:
  narrowWalks cost 3,942 ms over 111,248 walks ≈ 35 µs/walk, so a PERFECT walk
  memo saves the 1,000 ms of already-identical repeats plus ~34.2k × 35 µs
  ≈ 1.2 s → ~2.2 s; the getTypeOfExpression shadow memo could serve 149,742 of
  484,628 calls (31%) ≈ 1.1 s. Both together ≈ 3.3 s. Still the biggest single
  lever left (3× the whole remaining M0.4 tail), but size the work to it.
  - [x] **(a) DONE round 660 — attribution instrumented, and the item's premise
    was WRONG.** Every fence bump is now tagged (`bumpExprEpoch(src)` →
    `epochBumps`) and the walk probe's `walkMiss` is split cold vs
    epoch-invalidated with a result comparison + blame tag. (1) Of 80,034
    misses, **45,476 (57%) are COLD** — a first sighting of that reference, so
    no fence design recovers them; the old "80k walks run at fresh epochs"
    framing conflated cold with churn. (2) But the fence IS far too coarse: of
    the 34,558 invalidated repeats **99.6% recompute to an IDENTICAL result**
    (only 133 differ). (3) The coarseness is NOT noise, so **"fence per map"
    will not fix it**: meanEpochDelta is 218 (the fence moves ~218× between two
    walks of one reference) and blame concentrates in currentLocalTypes swaps
    (67%) + currentFlowGraph swaps (29%) = 96% — the spine's per-scope and
    per-file installs, i.e. GENUINE state changes. ALSO LANDED: no-op guards on
    all 13 fenced setters (`if (field !== v)`), which remove 1.46 M pure no-op
    bumps (28% of fence traffic) and drop meanEpochDelta to 154 — but recover
    only ~200 of the 34.5k invalidated repeats (0.6%), which is finding (3)
    measured from the other side. Kept: correct, sharpens the blame table, and
    the live memo will need it. (The epoch is PROBE-ONLY today — read only under
    `--passTiming` — so none of this can change compiler behaviour.)
  - [x] **(b) DEPENDENCY-KEYED validity — SOUND, gate MET (rounds 661–662).**
    Each memo entry records the FlowGraph identity plus the Type INSTANCE bound
    to the reference's ROOT NAME in currentLocalTypes / narrowedDeclaredTypes,
    and a repeat is served while those match — so a swap to a DIFFERENT scope map
    that still binds the root to the SAME instance is not an invalidation, which
    is the population the global fence discarded. Shadow numbers with the
    CORRECTED key (round 662): **serve 41,389, all identical, `depServeWrong` =
    0**, cold 69,790, invalidated 69 (localType 68, localType+narrowed 1 — the
    graph identity never invalidates alone). Round 661's 65,575/165 is
    SUPERSEDED: those 165 were a KEY COLLISION between three walk functions over
    11 call sites with different starting types and paths, not a dependency gap,
    so `flowWalkWithTripCheck` now takes a `kind` tag plus an `inputId` folding
    the starting type id with the path hash. **(b1) is therefore closed WITHOUT
    the read-set recorder** — the walk's dependencies ARE name-enumerable, and
    the recorder would have been solving a problem that did not exist. Prize
    correction: ~34 µs/walk × 41,389 = **~1.4 s** for the walk half (not the
    ~2.2 s rounds 660/661 reported off the coarse key), so M1's total lands at
    **~2.5 s ≈ 8–9%** with typeOfExpr's ~1.1 s.
    - [x] **(b2) DROPPED round 663 — measured, and the reachable prize is
      ~0.1 s.** The re-measure-before-investing instruction paid off. The
      expression memo's whitelist (Intrinsic/Interface/Reference) silently
      excludes ~134 k of ~618 k getTypeOfExpression calls (22%) — obj 102,102,
      unions ~29 k, Intersection 1,719 — precisely because those kinds are
      "freshly minted per call", which IS the non-canonical-output problem. Of
      those excluded calls, **62,949 are same-epoch STRUCTURAL repeats** (only
      347 genuinely differ), so interning would make them servable. But the
      by-kind split is the deciding number: **obj = 47,629 (76%)**, unions
      ≈ 12.7 k, Intersection 495. Object-type freshness is DELIBERATELY
      load-bearing (the round-435 freshObjLitRange relation machinery — the
      whitelist comment says so explicitly), so the 47.6 k / ~0.34 s half is
      not available without reopening relation semantics; the safely internable
      union+intersection part is ~13.2 k ≈ **~0.1 s**. Against the ~1.4 s live
      walk memo that is already sound at zero wrong serves, that is not worth
      the risk — so (b2) is dropped and (c) goes straight to the live memo.
      Revisit only if union interning becomes desirable for another reason
      (INV.5 canonical types would subsume it).
  - [x] **(c) LIVE — landed round 664 at −2.93% wall (−833 ms), the arc's
    first measured win.** `flowWalkWithTripCheck` serves from a memo keyed
    `(reference nodeId, fileHash, walkKind, inputId)` carrying the dependencies
    the walk read (FlowGraph instance + the Type instances bound to the
    reference's ROOT NAME in currentLocalTypes / narrowedDeclaredTypes).
    Interleaved A/B, 6 pairs, alternating order, no recompile between
    measurements: pre median 28,433 ms → post 27,600 ms, **−833 ms = −2.93%,
    post wins 5/6**. Instrumented: walks executed 111,248 → 69,859 (40,542
    served), narrowWalks 3,791 → 2,756 ms. The net is SMALLER than the ~1.4 s
    the shadow predicted because the memo pays key+dependency lookup on ALL
    walks to skip 37% — worth remembering when sizing the next memo: shadow
    "servable time" is an upper bound, not a forecast. All three round-663
    hazards handled (never store a tripped walk; `Any?` + cast sound because
    walkKind is in the key; shadow classification retained under
    `--passTiming`). Gates: suite 12,507/0; `--listAll` ×8 byte-identical on all
    eight profiles — no diagnostic moves anywhere, including no TS2563 drift;
    `--partitionCheck 2` EQUIVALENT ×8; warning-clean.
  - [x] **(d) DEAD before it was built — round 665 measured the would-save at
    30 ms, not ~1.1 s.** The measure-before-building instruction round 664 wrote
    into this item is what caught it. Instrument: decide with EXACTLY the live
    test (a confirmed shadow entry at the current epoch), decide BEFORE the core
    runs, and accumulate the core time of the OUTERMOST servable call only.
    Result: **30 ms over 71,310 outermost served calls ≈ 0.42 µs each = 0.12%**
    of a ~24 s compile — and a live memo would pay per-call overhead on ~618 k
    calls to collect it, so it must LOSE. WHY the round-660 estimate was 35×
    off: it multiplied the shadow's 149,742 hits by a MEAN call cost, but the
    servable population is the CHEAP TAIL (trivial identifiers/literals whose
    resolution is already cached) while the expensive calls — fresh minting,
    narrowing, relation work — are precisely the non-instance-stable ones the
    whitelist excludes. Applying an aggregate mean to a non-uniform population
    is the same error class as round 662's key collision. It also EXPLAINS the
    documented round-596 dead-end (a live per-node expression memo measured 1–3%
    SLOWER interleaved): that was observed but unexplained, and 30 ms is the
    explanation. Do not revive without a NEW mechanism that makes the expensive
    calls servable — canonical types (INV.5) would be that mechanism, not a
    better fence.
- [x] **(M2) SIZED round 666 and PARKED as not-locally-demonstrable — the box,
  not the design, is the binding constraint.** Probed BEFORE writing code (the
  discipline round 665 asked for), using the `--workers N` share-nothing mode
  that already exists (INV.6(6c1)). Compiler profile, 2 reps each:
  **seq 27,873 ms | w2 24,669 (−11.5%) | w4 27,905 (+0.1%)** — w2 helps, w4 is
  flat, exactly the "w4 flat" the item recorded, and STILL flat after M1's memo.
  Solving seq-vs-w2 as `seq = R + P`, `w2 = R + P/2`: only **P ≈ 6.4 s (23%)
  divides**, with **R ≈ 21.5 s (77%) non-divisible**, so the infinite-worker
  floor is ~21.5 s = a 23% best case even before contention. WHY, from the code:
  each worker does `sourceList.map { workerBinder.bind(it) }` — a FULL re-bind of
  EVERY file — and then builds a full `Checker` whose ~318 program-wide
  collectors all run; only the per-file spine is narrowed by
  `assignedFileNames`. So the duplicated-per-worker term is the whole of R, and
  Phase 1 (compute the collectors once, freeze, share) attacks at most the
  non-spine part of checker-init, measured at **~3.3 s** (checker-init 24.2 s −
  checkSpine 20.9 s + outside-pass). On 4 saturated cores that is ~2.5 s of CPU
  reclaimed but no wall win — w4 is already contention-bound, which is why it
  regresses against w2. VERDICT: the work is sound and would matter on a bigger
  machine, but on 4 cores / 7.7 GB it cannot be demonstrated, and this arc's rule
  is not to land unmeasurable perf work. ~~What would change the verdict: a host
  with ≥8 real cores (re-run this exact probe first)~~ **UNPARK CONDITION REWRITTEN
  ROUND 740 (PERF.HW) — "≥8 real cores" is NECESSARY BUT INSUFFICIENT.** The probe
  was re-run and measured the thing this item never checked: **a SEQUENTIAL run
  already consumes 3.15 of the 4 cores** (85.6 s user CPU / 27.1 s wall; ~2.2 cores
  of it JIT, 0.11 GC), so only ~0.85 cores are free and every worker level saturates
  at the same ~3.6-core ceiling. The measured requirement is therefore **≥8 cores
  *net of* the ~3.2 the JVM's own JIT/GC consume during a ~27 s cold run, i.e.
  realistically ≥12** — and that tax is **FIXED per JVM** (it does not grow with
  worker count), so a larger host simply out-sizes it. Revival order, unchanged in
  spirit but now with numbers behind it: **(a) close the `--workers` correctness
  divergence (PERF.HW.a) — CLOSED round 754, `--workers 2/4` now byte-identical to
  sequential at 46 and `--partitionCheck 2` EQUIVALENT on all eight profiles; (b)
  shrink R — the full per-worker re-bind is still the single biggest identified
  duplication; (c) only then re-probe, on a ≥12-core host.** The prize is capped at
  **1.25x** by the w1/w2 Amdahl fit regardless.
  **ROUND 824 RE-PROBED ON THE 8-CORE BOX AND FALSIFIED THREE OF THIS ITEM'S CLAIMS —
  the PERFORMANCE case unparks, the CORRECTNESS gate does not.** Artifact:
  `docs/perf/worker-scaling-round824.md`. (1) **The 1.25x cap is refuted by
  counterexample: w4 MEASURES 1.343x** (−23.84%, **5/5 wins**, per-rep deltas
  sign-consistent), and the seq/w2 and seq/w4 Amdahl fits now AGREE (36.9% / 34.0%
  divisible, floor **~1.52–1.58x**) where on 4 cores they disagreed 3.5x — the 1.25x was
  an artifact of a box with 0.85 free cores, not a property of the design. The model
  breaks at w8 (19.8%, floor 1.248x); that near-match to the old cap is a coincidence,
  not a confirmation. (2) **"w4 is flat" is gone — w4 is now the BEST level** and even w8
  (+19.4% regression on 4 cores) is a −17.0% win; nothing is saturated (cores used
  4.17 / 5.02 / 5.91 / 6.44 of 8). (3) **The ">= 12 cores" unpark condition was
  MISCALIBRATED because its unit is not a constant**: the JVM's own tax SCALES with
  `nproc` (`CICompilerCountPerCPU` is true — `CICompilerCount` 3 → 4, `ParallelGCThreads`
  4 → 8, JIT CPU ~21.7 s → ~43.8 s), so a sequential run takes **4.17 of 8 cores, not the
  extrapolated 3.2**; free cores went 0.85 → 3.83, and 3.83 sufficed. The answer arrived
  at 8, not 12. **What still blocks (M2): step (a) — `--workers N` is a RACE
  (see (PERF.HW.a), re-opened round 824), so no wall-time figure here is a claim and
  step (b) has no trustworthy baseline to measure against.** Step (b) is now worth
  ~1.5x rather than ~1.25x, which materially changes whether it is worth attempting —
  once (a) closes.

**EP — Emit parity (owner-authorized 2026-07-12: "output parity, including reported errors").**
The offline v1 DoD checked emit COMPLETENESS (all files emitted, exit 0) but not
emit-BYTE parity with tsc. The round-483 emit diff (`scripts/emit-diff-tsc.sh`, xtsc
vs npm `tsc@6.0.3` on the `compiler` profile) found 8/78 byte-identical, 70/78
differing — but **none are miscompiles**; xtsc's output is semantically correct and
runnable. Three systematic families explain nearly all changed lines (sequenced
cheap-first to shrink the diff before tackling the hard cross-file one):

- [x] **EP.3 Logical/nullish-assignment downleveling** (`||=`/`&&=`/`??=` below
  ES2021). DONE round 484 (2026-07-12): `Transformer.downlevelLogicalAssignment` —
  `a ||= b` → `a || (a = b)` etc., with side-effecting property/element receivers
  captured into temps (`(_a = obj())[_b = key()] || (_a[_b] = 6)`, tsc-faithful temp
  naming). ~284 sites in the compiler profile. Gated `effectiveTarget < ES2021`;
  corpus has ZERO files exercising these operators so it's pinned by
  `LogicalAssignmentDownlevelTest` only. KNOWN RESIDUAL: a `??=` target BELOW ES2020
  keeps a native `??` (not further downleveled — ES2020 is the tested/dashboard
  target); close when a sub-ES2020 `??=` case appears.
- [x] **EP.2 CLOSED — every live sub-item landed (2a, 2b, 2d/e/f, 2g, 2h) and
  2c was SKIPPED-BY-OWNER; checkbox reconciled round 687. RE-SCOPED round 673
  by classifying the residual: it is NOT mostly formatting.** Every differing hunk in the 47 remaining files was
  classified (1,335 hunks total): **482 residual qualified access**, 173 other,
  **128 whitespace/wrap only**. So formatting is under 10% of the residual and
  the const-enum family — supposedly 96% closed — still dominates. Three
  distinct sub-targets, in value order:
  - [x] **EP.2a DONE round 674 — 128 → 1.** `emitArrayLiteral` re-emits each
    element's same-line trailing comments after `emitExpression(element)`,
    guarded by `element !is NumericLiteralNode` because a numeric literal
    already emits its own; `StringLiteralNode` does the same and was NOT
    excluded, so string-valued const enums printed their label twice (hence only
    `Extension.*` showed it). BOTH array branches carry the guard — I patched
    the MULTILINE one first and the repro did not change, which is what pointed
    at the single-line branch where the real trigger was; both fixed. An
    emitter probe proved the NODE held exactly ONE comment, localising the fault
    away from the transformer in one run. Measured: double-comments 128 → 1,
    total differing hunks 1,335 → 1,307, byte-identical files unchanged at 31/78
    (those hunks live in files that still differ for other reasons — hunk-level
    and file-level progress are different measurements). Gates: 6 pins
    (ArrayLiteralConstEnumCommentTest — they COUNT occurrences, since a
    substring check passes on doubled output, plus a negative control that a
    genuine source comment still survives); suite 12,526/0 with every JS
    baseline byte-exact despite touching the printer. RESIDUAL: 1 occurrence of
    a different shape, worth a look when convenient.
  - [ ] ~~EP.2a (original)~~ — **THE DOUBLE-COMMENT DEFECT (128 occurrences) — do this first,
    it is malformed output, not a cosmetic.** We emit
    `".jsx" /* Extension.Jsx */ /* Extension.Jsx */` where tsc emits one
    comment. REPRO IS THREE LINES (saved at `scratchpad/dblcomment`): a const
    enum imported cross-module, then `export const arr = [Ext.Cts, Ext.Cjs]`
    emits each element's label TWICE while a plain `const one = Ext.Cts` is
    correct — so the ARRAY-LITERAL element path transforms the element twice.
    Real source shape: checker.ts:2550
    `fileExtensionIsOneOf(fileName, [Extension.Cts, Extension.Cjs])`.
  - [x] **EP.2b DONE round 675 — it was HEX literals; gap 675 → 70 reads.**
    `CharacterCodes` resisted while `SymbolFlags`/`Extension` inlined from the
    same types.ts because those are decimal/string-valued and CharacterCodes is
    almost entirely hex. All THREE const-enum evaluators parsed with
    `text.toDoubleOrNull()` — decimal-only (Kotlin takes a hex FLOAT `0x1.8p3`
    but not a hex INTEGER `0x7F`), so the member became silently un-inlinable
    with no error at all. Fixed with one shared `tsNumericLiteralToDouble`
    (Types.kt: hex/binary/octal + `_` separators, rejects BigInt) wired into the
    Transformer's same-file collector, the Checker's `literalConstantValue`, and
    the Checker's `evaluateEnumInitializer` (the cross-module `enumValues`
    table). Fixing the first two left cross-module broken — the same-file repro
    went green while the direct-import one did not, which is how the third site
    surfaced. Measured: const-enum reads 17,443 → **18,048** (tsc 18,118),
    byte-identical files unchanged at 31/78. Gates: 8 pins
    (HexConstEnumInliningTest — all three paths, binary/octal, negative+zero
    guard, the parser across bases, plus BigInt/garbage negative controls since
    a wrong value would silently corrupt emitted constants); suite 12,534/0;
    `--listAll` ×8 byte-identical, which matters here because the change alters
    enum VALUES. RESIDUAL: 70 reads, plus `tracing.Phase.Bind` (const enum
    behind a namespace) and one import-elision difference.
  - [ ] ~~EP.2b (original)~~ — **The 675-read const-enum residual, dominated by
    `CharacterCodes` (638 of the qualified-access occurrences).** Why that one
    enum resists while SymbolFlags/Extension inline is the question to answer
    first — it is declared in types.ts like the others, so the difference is
    likely in how its members are reached or valued (it is large and
    char-code-valued). Also visible: `tracing.Phase.Bind` (a const enum behind
    a namespace) and one import-elision difference (`ts_js_1.version` vs
    `version` in builder.js).
  - [x] **EP.2d/e/f DONE round 677 — the const-enum family is CLOSED at 18,118
    inlined reads vs tsc's 18,118.** Three unrelated causes, each found by the
    gate: (2d) parameter DEFAULT VALUES were never transformed at ES2018+ —
    `flattenRestParameters` returned the parameters raw from its early return,
    and it owns the plain FunctionDeclaration branch, function/arrow
    expressions and constructors, so every default there skipped
    `transformExpression` wholesale (invisible to the corpus, whose emit tests
    sit mostly BELOW that threshold); (2e) a const enum nested in a NAMESPACE
    did not inline through a barrel — the star closure yields the namespace and
    the binder flags a const-enum-only namespace `ConstEnum`, so the flag test
    passed while the id-keyed `enumValues` lookup missed (`descendToConstEnum`);
    (2f) COMPUTED initializers did not fold in the same-file collector —
    `const enum Connection { Up = 1 << 0, …, UpDown = Up | Down }` in tsc's
    debug.ts, worth 25 of that file's 121 reads. 2f surfaced only because 2e did
    NOT move the count: re-running the gate with `--keep` and counting per file
    beat a confident wrong model, the third time this arc (cf. 669, 672). The
    numeric operator table now lives once in `tsFoldNumericBinary`. Gates: suite
    12,566/0/3 (+32 pins), `--listAll` ×8 unchanged (46×7, harness 94).
  - [x] **EP.2g DONE round 678 — the const-enum family is closed FOR REAL
    (true gap 34 → 0; byte-identical 31 → 32).** A const enum reached through a
    VARIABLE whose declared type is the namespace (`export let tracing: typeof
    tracingEnabled | undefined`, used as `tracing.Phase.Bind`) never inlined:
    resolution stops at the variable, tsc goes through the type.
    `namespaceBehindTypeofVariable` follows the `typeof` annotation, wired into
    both the import and direct paths; the variable keeps its runtime identity.
    **This also CORRECTS round 677's "closed at parity" claim** — the script's
    family-1 counter requires a NUMERIC value, so string-valued enums are
    invisible to it on both sides. Measure with a per-file count of all
    `/* X.Y */` comments. Suite 12,573/0/3, `--listAll` ×8 unchanged.
  - [x] **EP.2h DONE round 678 — the 32 "extra blank line" hunks were an
    ordinary printer defect, not part of the formatting subsystem.**
    `emitInnerComments` wrote a newline after a `//` comment and then the next
    comment wrote a second for its `hasPrecedingNewLine`, so consecutive line
    comments gained a blank between them (tsc keeps a comment block before an
    `else if` adjacent). Four lines, byte-identical to tsc on the repro
    including the source-has-a-blank case. Hunks **368 → 302**, add-a-line
    family **32 → 0** (also cleared 34 entangled CONTENT hunks), byte-identical
    **32 → 33**/78. Suite 12,579/0/3, all JS baselines byte-exact.
  - [x] **EP.2c SKIPPED-BY-OWNER (round 678, 2026-07-25).** Asked explicitly and
    the answer was "skip — move on": byte-parity is NOT a v1 exit criterion
    (v1 = zero FPs + all files emitted + zero crashes, all three already met),
    so a multi-round printer subsystem with no v1 impact is not where rounds
    should go. The emit arc therefore CLOSES at **33/78 byte-identical**, with
    families 1 (const enums) and 3 (logical-assign) at full parity and the two
    genuine defects found along the way (EP.2a double comment, EP.2h blank
    line) fixed. If ever revived, the residual and its shape are recorded
    below — do not re-derive it. After rounds 677–678 the residual is
    **302 hunks: 266 CONTENT** (the ternary/binary wrap-and-indent structure —
    the genuine subsystem), **35 indent-only**, **1 collapsed wrap**. Nothing
    cheap remains: the two shapes that looked separable (EP.2a's double comment,
    EP.2h's blank line) were both ordinary defects and are fixed. Classified:
    **78 same line count but different continuation INDENT DEPTH**, differing in
    BOTH directions (checker.js has us indenting 4 too many in one wrapped `&&`
    chain and 4 too few in another — no single constant fixes it); **47 where
    tsc has MORE lines** because we COLLAPSE a wrap it keeps (binder.ts's
    `const name = isComputedName ? A` / `    : B ? C` / `    : D` — tsc
    reproduces the SOURCE's line structure with `:` at line start); **7 where we
    ADD a wrap** tsc does not. All three need the emitter to model tsc's
    line-breaking AND indent decisions for wrapped binary/ternary expressions —
    source-structure preservation for expressions, analogous to the existing
    `multiLine` flags on object/array literals but much broader. SIZING: 132 of
    1,307 residual hunks (~10%), few files would flip to byte-identical on its
    own (they carry other diffs), and it is the highest corpus-regression risk
    in the codebase — this printer is pinned by all 12,534 tests. If it goes
    ahead: one rule per commit, full suite after each, gate re-run to confirm
    the diff SHRINKS.
  - [ ] ~~EP.2c (original)~~ — **Multi-line expression formatting** — the original item, now
    known to be ~128 hunks: tsc puts a wrapped ternary's `:` at LINE START
    (`? [...]` / newline / `: [`) where xtsc trails it. Highest
    corpus-regression risk (this is the printer the 12,520-test corpus pins),
    lowest count — so it goes LAST, one placement rule per commit, full suite
    after each, and re-run the gate to confirm the diff SHRINKS.
  SUPERSEDED NOTE: — **WAS UNBLOCKED round 672**
  (the emit-diff gate is live, which its own text required), and it is now the
  LARGEST remaining emit-parity family: 47/78 files still differ while the
  const-enum family is 96% closed, so most of that residual is formatting. The
  shape is visible in the utilities.js diff — tsc puts a wrapped ternary's `:`
  at LINE START (`? [...]` / newline / `: [`) where xtsc trails it at line end.
  Corpus-regression risk is real (this is the printer the 12,520-test corpus
  pins), so: one placement rule per commit, full suite after each, and re-run
  the gate to confirm the diff SHRINKS rather than merely changes.
  SUPERSEDED NOTE: — **WAS BLOCKED OFFLINE
  (round 667).** Its own text requires "the emit-diff gate in place", and that
  gate needs a reference tsc: this box has **no `node`, no `npx`, no `tsc`, and
  no `tsc.js` anywhere** (the bench-history tsc/tsgo columns come from CI, not
  locally). Do not start EP.2 here — without the gate there is no way to know
  whether a printer change moves the diff toward or away from tsc, and the
  printer is exactly what the green corpus pins. Revive when a reference tsc is
  available (see EP.0).
- [x] **EP.1 DONE round 669 — and its dashboard premise is falsified too.** The
  barrel hop now inlines: `1 /* Kind.B */`, `"x" /* Names.X */`,
  `0 /* B.Kind.A */`, with the import elided and no `__importStar` helper —
  tsc's exact shape. Cause was the same as EP.1a's: both const-enum entry points
  (`resolveConstEnumMemberAccess`, `isConstEnumAlias`) reach the enum through
  `resolveAlias`/`resolveNamePath`, which walk `symbol.exports`, and a star
  re-export never populates the barrel's own export table.
  `constEnumSymbolThroughStars` follows the target module's star closure and
  returns a symbol ONLY when it carries `SymbolFlags.ConstEnum` —
  const-enum-only by construction, so it can never feed a general type
  resolution, which is what keeps it clear of the `resolveAlias` star dead-end
  (TS2315 ×466). **MEASURED, not assumed: ZERO effect on the tsc profiles.**
  Before AND after, the emitted `compiler` dist has 1,663 numeric + 18 string
  inlines and **0 residual `ts_N.X.Y`** — identical (verified by stash/rebuild
  precisely so the 1,681 inlines would not be mis-attributed to this change). So
  EP.1's "highest impact, ~93% of the changed lines" sizing is stale exactly like
  its premise was: the tsc profile already inlined everything, and the barrel gap
  is a shape those profiles never hit. Kept because the gap was real (repro +
  pins) — general-correctness value for the post-v1 "any project" horizon, NOT a
  dashboard win. Gates: 7 pins (BarrelConstEnumInliningTest, incl. a two-barrel
  chain and two negative controls for regular enums and
  preserveConstEnums/isolatedModules); suite 12,520/0 with every JS baseline
  byte-exact; `--listAll` ×8 byte-identical.
- [x] **EP.0 DONE round 672 — the gate is LIVE (owner authorised the network
  install).** Node v24.18.0 + `typescript@6.0.3` under `build/tools`
  (gitignored; tarball, not apt — no system mutation). Run it with
  `scripts/emit-diff-tsc.sh --ref-tsc build/tools/tsc-ref/node_modules/.bin/tsc`
  (put `build/tools/node/bin` on PATH first). The reference is npm tsc 6.0.3
  against a pinned repo whose package.json says 6.0.0, so the three FAMILY
  counts are trustworthy (version-stable behaviours) while the small residual
  tail carries version noise — building tsc at the pinned commit remains the
  ideal and is still open. Its FIRST RUN earned its keep by falsifying round
  669 (see EP.1). Baseline at round 672: **31/78 byte-identical**, const-enum
  reads 17,443 vs tsc 18,118, logical-assign 15 vs 15.
  SUPERSEDED NOTE: — **WAS BLOCKED OFFLINE
  (round 667): there is no reference tsc on this box** (no node/npx/tsc/tsc.js;
  `scripts/emit-diff-tsc.sh` exists but cannot run). Unblocking needs either a
  network install of node + `typescript`, or building tsc at the pinned commit —
  both outside the offline envelope, so this is a user-gated decision, not
  agent work. Until then EP progress is limited to what the CORPUS and local
  pins can gate (EP.1/EP.1a qualify; EP.2 does not).

Session note (round 484) has the full family breakdown + methodology.

**INV — the M5 architecture-inversion arc (re-scoped 2026-07-13, owner; supersedes
M5.1–M5.7 — mapping and full design in `docs/ARCHITECTURE-RETHINK.md`, READ IT FIRST).**
Ground rules for every INV item: corpus suite green + 8-profile FP floors unchanged +
`--listAll` byte-diff empty for behavior-preserving steps + a bench TSV row per landed
item; decompose into the smallest standalone suite-gated commits; micro-opt rounds
against the flat profile are CLOSED (only an INV.0-evidenced ≥5% single lever may
interrupt the arc).

- [x] **(cta-m3e) Lift the anchor-SIMPLE restriction — reproduce the legacy
  nested-dispatch localTypes recordings spine-side (queued round 570c with the
  design from the BarrelCheckDefinedReturnTest root-cause).** The blocker: legacy
  nested-scope dispatches RECORD into the shared `currentLocalTypes` and the spine
  frames have no reproduction, so an anchored statement after a switch/if/loop
  reads an incomplete map. Design notes (verified in-code round 570): (a) the leak
  is PER-ARM — switch clauses LEAK (clause dispatch shares the map), a NARROWING-
  wrapped if-then (extractNullNarrowing non-null — a pure function of the
  condition, callable at spine time) DISCARDS its recordings on restore, a
  non-narrowed if-then Block LEAKS (the Block arm copies varTypes but NOT
  currentLocalTypes), loop/try bodies leak via the same Block arm; (b) the
  mechanism: a RECORDING-ONLY sandwich at nested VariableStatement enters within
  an active fn frame — install the frame maps, run the real
  checkVarDeclAssignability under a diagnostics mark, truncate ALL its
  diagnostics (nested statements stay legacy-owned for emission), keep the map
  writes; skip inside narrowing-discarded regions; (c) spine statement-position
  Block/clause frames already model the map SHARING — the narrowed-if discard
  needs a COPIED-map frame rule keyed on extractNullNarrowing; (d) gates: the
  barrel repro shape as a local pin (switch-clause recording feeding a later
  anchored statement's member reduction), corpus + listAll ×8. Alternative if the
  recording-only sandwich disturbs first-touch caches: migrate the nested
  dispatchers' arms themselves (bigger). DONE round 571 — the recording-only
  sandwich landed clean (one extra invariant found: TS2563 trip-state suppression
  during recordOnly, CfaTooLargeBailTest); see the session note.
- [x] **INV.0 Instrument the multiplier.** DONE round 491 (2026-07-13):
  `PassTiming.kt` + non-inline `pass(name) {}` around all 514 init dispatch calls +
  the three counters (`getTypeOfExpression` calls/distinct with per-pass attribution,
  `nodeTypes` cacheable/bypassed/hit, depth-0 flow walks at `flowWalkWithTripCheck`),
  behind the `--passTiming` CLI flag; off-mode byte-identical (listAll A/B + wall
  parity) + suite green (+7 local). The table (round-491 session note): checker-init
  = 83% of wall; top-3 passes 38.6% (property-access / assignability / call-types,
  458k of 595k getTypeOfExpression calls, 84k flow walks — 68% from
  checkPropertyAccess); 474 sub-100 ms passes sum 36.5% = the multiplication tail.
  That note's cost-ordered worklist IS the INV.4 migration order.
- [x] **INV.1 Concurrent front-end — the owner's Flow beachhead (owner-approved
  kotlinx-coroutines-core dependency, 2026-07-13).** Sub-steps: (a) DONE round 492 —
  the dep was already in commonMain; landed the `runCompilerPipeline` expect/actual
  seam (JVM `runBlocking`) + the import-graph crawl as a cold sequential Flow
  (`crawlImportGraph`, ProjectCompiler) with the load-bearing emission-order
  contract documented at the seam (suite +3, listAll A/B byte-identical); (b) DONE
  round 493 — read+decode on `Dispatchers.IO` (`pipelineIoDispatcher`
  expect/actual), extraction parse on `Dispatchers.Default`, bounded
  `flatMapMerge(16)` per frontier (`readAndScanBatch`); resolution + emission stay
  sequential per frontier so emission stays first-discovery order (the binder stays
  sequential; parser audited — no shared mutable state); (c) DONE round 493 —
  corpus green (+6 local) + 3× `--listAll` byte-identical vs the (a) binary; (d)
  DONE round 493 — interleaved A/B −0.8 s (~3%) on the compiler profile + bench
  TSV row.
- [x] **INV.1(e) Kill the double parse — reuse the crawl's parses in the core.**
  DONE round 494 (2026-07-13): `computeParserFlags` (the shared single source of
  truth for the option-derived `Parser` flags, used by the core's parse sites AND
  the crawl), `ParsedSource.preParsed` carrying `PreParsedFile(content, flags,
  sourceFile, diagnostics)`, and the core's multi-file site reusing an entry ONLY
  on an exact content+flags match (else re-parse — reuse is a pure optimization).
  Verified: suite +6 (Inv1PreParseReuseTest — sentinel-tree reuse proof + both
  mismatch gates + driver-path counters), `--listAll` byte-identical on compiler
  AND services, reuse fires 78/78 (`--passTiming` counters), interleaved wall A/B
  neutral within noise on both profiles (the parse leg is small next to the
  checker; the point is one canonical tree per file — the INV.2 enabler).
  CLAUDE.md gotcha: a new option-derived Parser argument must extend
  `ParserFlags`, never a parse site inline, or the match reuses a wrong tree.
- [x] **INV.2 Bind the world** — COMPLETE round 499 (all four sub-items landed;
  the tables' mass consumption is INV.4's migration). Decomposed round 494
  (facts verified in-code:
  `Node` is a sealed interface + ~138 data classes with single-interface supertypes
  `) : Expression/Node/TypeNode/Statement/Declaration/ClassElement`; there is NO
  generic child-walk anywhere; nodes have no parent/id fields; `Symbol.id` is a
  GLOBAL companion `nextId++` (Types.kt:116–127, the ~350-test reshuffle anchor);
  `nodeKey` is the cross-file-colliding `(pos<<32)|end`). Work the sub-items in
  order, one commit each:
  - [x] **INV.2(a) AST identity foundations.** DONE round 495 (2026-07-13):
    `NodeBase` (nodeId/parent, NOT implementing Node — preserves sealed-`when`
    exhaustiveness) + 138 supertype edits + `SourceFile.nodeCount`; canonical
    `forEachChild` (exhaustive sealed `when`) + iterative preorder
    `indexSourceFile` hooked into `Parser.parse()`. Pinned by the jvmTest
    reflection oracle (`ForEachChildOracleTest` — componentN diff over fixtures +
    all 78 real tsc sources) + `Inv2NodeIndexTest` (dense preorder / parent
    chains / copy-unindexed / 30k-chain-on-plain-thread). Suite +10 (10,228),
    `--listAll` byte-identical, wall neutral. Gotchas: NodeBase LUB trap +
    power-assert node-toString trap.
  - [x] **INV.2(b) Pilot consumer.** DONE round 496 (2026-07-13):
    `FlowGraph.flowAt` — nodeId arrays pre-computed from the finished map
    (preserves the nodeKey extent-ALIASING) + identity ownership check
    (synthesized/foreign nodes take the legacy path); 5 checker sites migrated;
    suite +3, listAll byte-identical, wall neutral. JFR verdict: getNode ≈6.7%
    of samples but nodeToFlow only ~4% of that slice (~0.3% wall) — mechanism
    validated; the mass-migration targets are the HOT maps (walk memos, INV.4
    per-node type cache), not more cold tables. `nodeTypes` rejected as pilot
    (structural cross-file keying — INV.5 territory).
  - [x] **INV.2(c) Full lexical binding, additive.** Scope symbols from a SEPARATE
    id space (never the global `nextId` sequence — the reshuffle hazard); existing
    `locals`/`globals` byte-unchanged; new tables unconsumed until INV.4.
    - (i) DONE round 497 (2026-07-13): function-like containers —
      `bindLexicalScopes` (Binder.kt) walks the whole tree iteratively after
      conventional binding, building per-nodeId `LexicalScope`s
      (`BinderResult.lexicalScopes`): SourceFile root aliases file locals,
      ModuleDeclaration aliases the merged exports (chained per dotted segment,
      the B512 rule), the 7 function-like kinds + static blocks get fresh tables
      (type params, params minus `this`, fn-expr self-name, body-top-level
      decls, `var`s hoisted from any block depth). `Symbol.scopeSymbol` mints
      ids ≤ −2; a delta-probe test pins zero global-id consumption. Suite +14
      (Inv2LexicalScopeTest), listAll byte-identical, interleaved wall
      position-balanced +0.8% (noise band).
    - (ii) DONE round 498 (2026-07-13, same session): block-scope containers —
      every Block that is not a function-like's immediate body, for/for-in/for-of
      headers, CatchClause (binds the catch variable, destructuring included),
      SwitchStatement standing in for tsc's CaseBlock (our AST has none — the
      switch EXPRESSION routes to the OUTER scope by hand) — plus class scopes
      (type params; named class-expression self-name; class decorators outer),
      interface/type-alias scopes (type params), and enum scopes (aliasing
      main-bound exports; nested enums bind scope-space members also published
      on the scope symbol's exports, gated `id ≤ −2` so main symbols stay
      untouched). Design dividend: the phase-(i) `isDirectBodyChild` gates for
      block-scoped declarations DISSOLVE into `scope.existing == null` (every
      fresh scope IS the correct nearest block-scope container); `var` gains the
      real `varHoistTarget` walk-up. Block-nested function declarations use
      strict/module semantics (bind to the block). Suite +6 (20 total in
      Inv2LexicalScopeTest — the phase-(i) negative controls flipped to
      positive location asserts), listAll byte-identical, interleaved wall ×6
      both orders neutral.
  - [x] **INV.2(d) B83.5 dissolution pilots.** DONE round 499 (2026-07-13): the
    canonical site — `checkPropertyAccessInStatement`'s ClassDeclaration branch —
    now resolves a block-scoped class via `lexicalScopeSymbol` (parent-chain walk
    over `currentLexicalScopes`, set per file in `checkPropertyAccess`; legacy
    transient synthesis kept as the unindexed-tree fallback). Fidelity proven:
    suite green, listAll byte-identical on compiler AND services; and the pilot
    FIXES a real FP — a block-level `interface B` + `class B` merge now
    contributes interface members to `this` (the transient class-only symbol
    could not see them; measured: the pre-pilot checker emitted a false TS2339).
    Candidate analysis: the other two `Symbol(SymbolFlags.Class, …)` syntheses
    are NOT B83.5 scope-binding shapes and stay — the B511 clodule recovery
    (the class symbol is main-bound then OVERWRITTEN by last-wins, so it is in
    neither table) and the classExpressionAssignment display synthesis (a
    ClassExpression is never a scope binding). Mass consumption of the tables
    (the ~59 synthesis sites, `buildNestedFunctionMap`, the per-pass scope
    machinery) is INV.4's migration proper.
- [x] **INV.3 Per-file scoping — ARC COMPLETE round 513** ((a)-(d) all landed; checkbox reconciled round 612) — decomposed round 500 (facts verified in-code:
  `perFileScope` EXISTS and is already consumed at 4 sites — the 17.32b–e flips
  (TS2663-vs-TS2301, TS2552 candidate pool, resolveExpressionToSymbol, file-root
  TS2304) — so the earlier "never consumed" note was stale; the remaining
  migration surface is ~400 keyed `globals` consults; import aliases free-ride on
  the conflation because the general `resolveAlias` cannot follow ESM-`.js`
  specifiers / `export *` barrels / NamespaceImports — the FLOW-ONLY resolvers
  can, and the general-fallback variant measured a TS2315×466 flood at round
  409). End state: module files resolve own-locals + imports + true globals;
  the `mergeSymbolTable` conflation is retired for module files; the conflation
  ecology is deleted. Also lays the cross-file value-resolution groundwork EP.1
  needs. Work the sub-items in order, one commit each:
  - [x] **INV.3(a) Instrument the conflation dependency.** DONE round 500
    (2026-07-13): `globals` constructed as `InstrumentedSymbolTable` under
    `--passTiming` (plain map otherwise — zero added code on the hottest map);
    every keyed lookup classified against the per-file visibility model
    (TRUE_GLOBAL / SHARED / OWN_LOCAL / CONFLATED / UNSCOPED — see
    `GlobalsLookupClass`) by a classifier installed after init step 1b, with
    per-name + per-pass conflated/unscoped tables in the dump. Measured
    (compiler / services profiles): 2.71M / 4.92M keyed lookups — 71% / 79%
    MISSES (globals probed as a maybe-fallback everywhere), ownLocal
    530k/703k (flips to per-file trivially), CONFLATED 157k/217k concentrated
    in 608/845 names (almost all `types.ts` type names reached through barrel
    imports; services adds the round-442 value-space leaks `parent`/`error`)
    and 14–15 passes with the top 3 = 95–96% of conflated traffic = INV.0's
    top-3 wall passes (checkPropertyAccess / checkCallExpressionTypes /
    checkTypeAssignability), SHARED only 2.9k/4.0k (the chimera ecology's
    cost is per-lookup bail checks, not hit volume), unscoped 71.8k/97.1k
    (checkUnresolvedNames + outside-dispatch). Worklist: (b)'s primitive must
    resolve barrel-imported TYPE names; (c) starts at the three hot passes.
    Suite +5 (Inv3GlobalsLookupTest), `--listAll` byte-identical (off-mode),
    bench row in band.
  - [x] **INV.3(b) Per-file resolution primitive.** COMPLETE round 502:
    - (i) DONE round 501 (2026-07-13): `lookupPerFile(fileName, name)`
      (internal, unconsumed by checker paths) — perFileScope lookup with an
      ImportSpecifier-alias local resolved onward through
      `resolveImportedSymbolGeneral` (the kind-AGNOSTIC generalization of the
      flow-only resolver skeleton: ESM-`.js` strip + `export *` barrels +
      renamed re-exports via the star walk's NamedExports arm + re-import
      hops; memoized `importedSymbolGeneralCache`; ADDITIVE — the three
      kind-specific legacy variants stay untouched, their per-decl
      kind-filter-then-continue semantics differ; never wired into
      `resolveAlias` per the round-409 flood gotcha). KEY TRAP hit and
      pinned: mergeSymbolTable FLAG pollution means an Alias flag cannot
      identify an import alias — a barrel-imported name's TARGET symbol
      acquires the Alias bit from the importing file's merge, so the hop
      test must be declaration-based (`isImportBindingDecl` — the
      isValueExport gotcha applied to alias hopping). Degradations
      documented in the KDoc: unresolvable import / default-import /
      `import * as ns` / `import =` aliases return the alias symbol itself
      (callers keep their existing handling — extend when a (c) flip needs
      them); null strictly means "no per-file meaning" (the conflation
      leak). Pinned by Inv3PerFileLookupTest (direct
      `Checker(options, binderResults)` construction — a first for local
      tests — asserting symbol IDENTITY with the declaring file's binder
      locals across direct-`.js`/barrel/renamed-re-export/own-local/
      script-global/lib shapes + the foreign-module-local null and
      alias-degradation negative controls).
    - (ii) DONE round 502 (2026-07-13): pilot consumer — the TS2315/TS2346
      heritage-base "not generic" gate (`checkTypeArgumentConstraints`, the
      smallest nonzero pass in the (a) conflated-by-pass table with DIRECT
      pass-local consults) resolves through the NEW
      `globalsForFile(fileName, name)`, THE (c) flip shape: return the
      merged-globals INSTANCE whenever the name has a per-file meaning (a
      non-module-only name, or a module-only name the file declares/imports
      — probed via `lookupPerFile`; substituting the primitive's return
      directly would change symbol identity for lib/script names), null
      exactly where the legacy consult leaked a foreign module file's local
      (suppression-only at this site: real tsc never emits TS2315 for an
      unresolvable base). Supporting infra always-on: init 1b2 became
      `computePerFileVisibility` — publishes `moduleOnlyGlobalNames`
      (module-file local names minus lib/script/augmentation-visible), the
      INV.3(a) classifier installs on top of the same sets. Both mirrored
      consult sites flipped together (kept-in-sync contract); the conflated
      branch never touches `globals`, so the `--passTiming` conflated
      tables keep measuring only UN-migrated traffic. MEASUREMENT LESSON
      for (c): the post-flip instrumented run shows the pass STILL at 11
      conflated with the total lookup count EXACTLY unchanged (2,711,601)
      — the pass's conflated traffic comes from DEEPER shared machinery
      (`checkConstraintsForTypeArgs` → `getTypeFromTypeNode`), not the
      direct pass-local consults, which measured ZERO conflated hits on
      the compiler profile. Per-PASS attribution ≠ per-SITE: a hot-pass
      (c) flip needs per-site reasoning about which consults inside the
      pass actually carry the conflated traffic. Suite +7
      (Inv3GlobalsForFileTest — both leak-kill tests FAIL on the pre-flip
      checker, verified via stash; five preservation controls pass on
      both); `--listAll` byte-identical on compiler AND services.
  - [x] **INV.3(c) Flip resolution families onto the primitive** — COMPLETE
    round 509 (all four sub-items landed; conflated 157k → 917, the residue
    being the INV.3(d)-scoped shadow ecology). Decomposed
    round 503 from a MEASURED per-site attribution (a temporary 1:200
    stack-sampling probe on the classifier's CONFLATED branch, ~790 samples,
    probe reverted — evidence in the round-503 session note). The guessed
    site list above was WRONG: `getTypeFromTypeReference`'s globals fallback
    measured ZERO conflated hits and `resolveTypeNameToSymbol`'s Identifier
    entry only ~1.2% — the actual distribution:
    **~82% is ONE family, the enum-discriminant/kind-domain narrowing
    machinery** (`kindDomainKeysFromTypeNode` → `enumSwitchKeysFromTypeNode` /
    `enumMemberKeysOfTypeNode` / `kindDomainTypeDeclSymbol` /
    `resolveEnumSymbolForDiscriminant`, reached from `narrowByCallPredicate`
    via `applyConditionNarrowing`, plus smaller entries from
    `filterUnionByEnumDiscriminant`/`resolveCallOverload`), which resolves
    type names read from FOREIGN AST nodes — types.ts's union-member `.kind`
    annotations — while `currentFileLocals` points at the CHECKING file
    (exactly the top conflated names: JSDocFunctionType / FunctionTypeNode /
    ConstructorTypeNode / MappedTypeNode / ConditionalTypeNode). The
    per-file-correct key there is the NODE'S OWNING FILE (tsc semantics: a
    types.ts annotation resolves in types.ts's scope), NOT
    `currentCheckFileName` — a naive `globalsForFile(currentCheckFileName,…)`
    flip would silently kill narrowing in files that don't import the name.
    The rest: `identifier.fallback` ~3.8k + `propAccess.objExpr` ~3k (tagged
    counts), `checkPrivateMemberAccess`, `getTypeOfIdentifier ←
    isCalleeResolvable`, `resolveFlowCalleeDecl ←
    flowCalleeMayHaveAssertEffects`, `computeRawTypeOfPropertyAccess ←
    getCalleeType`, `typeNodeDefinitelyNonNullish`, `pmrCheckAccess`,
    `mam.objectExpr`/`mam.recvSym` (~63 each). Sub-items, one commit each,
    every flip suite+listAll-gated on compiler AND services:
    - (i) DONE round 504 (2026-07-13): the node-keyed resolution primitive —
      `owningSourceFile(node)` (NodeWalk.kt: parent-chain walk to the
      SourceFile, null for unindexed `copy()`/synthesized/detached nodes,
      defensive hop bound) + `lookupPerFileForNode(node, name)` =
      `globalsForFile(owner.fileName, name)` with legacy-merged-consult
      degradation for ownerless nodes. Additive/unconsumed; pinned by
      Inv3NodeKeyedLookupTest (direct construction — a foreign-node
      annotation resolves under its OWNING file's visibility to the same
      merged instance; an owner without the name yields null (the leak);
      an importing owner keeps resolving; an unindexed copy degrades to
      legacy; lib names never nulled).
    - (ii) DONE round 505 (2026-07-13): the kind-domain/enum-discriminant
      family (~82% of conflated traffic) flipped onto the node-keyed
      primitive — `resolveEnumSymbolForDiscriminant`/`kindDomainTypeDeclSymbol`
      thread a `keyNode` (all 5 call sites), and the alias fallbacks in
      `enumSwitchKeysFromTypeNode`/`enumMemberKeysOfTypeNode` (incl. the
      round-477 import-alias fallback) consult `lookupPerFileForNode(node,
      name)`; `currentFileLocals` stays the first consult everywhere.
      Companion: `globalsForFile`'s proven-visible branch reads UNCLASSIFIED
      (`InstrumentedSymbolTable.getUnclassified`) under `--passTiming`, so a
      legitimate foreign-node hit — CONFLATED against the CHECKING file's
      locals — no longer pollutes the migration tables. Suite +5
      (Inv3KindDomainNodeKeyTest — leak-kill FAILS pre-flip via stash;
      4 preservation controls pass both sides); listAll byte-identical on
      compiler AND services.
    - (iii) **Flip the current-file-keyed value/callee sites** — these read
      names from the CURRENT file's own AST; node-keying by the name's
      IDENTIFIER node is the uniform shape (equals current-file keying for
      own nodes); suppression-only where the name classifies conflated.
      Phase 1 DONE round 506 (2026-07-13): the protected-member cluster
      (pw/pmr/pm, TS2445/TS2446 — `pmrCheckAccess`'s static consult, the
      ctor-init consult, and the `pwResolveClass`/`pmrResolveClass` funnels
      every heritage walker feeds) keys by the name Identifier via
      `lookupPerFileForNode` — the heritage walkers wrap a REAL indexed
      Identifier in a synthesized TypeReference, so keying by `typeName`
      (never the wrapper) needs zero signature changes; a fully-synthesized
      identifier (pmrLocalClass's from-text one) degrades to the legacy
      consult inside the primitive. Suite +5 (Inv3ProtectedNodeKeyTest —
      both leak-kill tests FAIL pre-flip via stash: the leaked resolution
      manufactured bogus TS2445 about a class the file never imports);
      listAll byte-identical on compiler AND services. Phase 2 DONE round
      507 (2026-07-13): the bare-Identifier VALUE/receiver/callee cluster —
      checkPrivateMemberAccess, getCalleeType's Identifier branch,
      resolveFlowCalleeDecl (+ the extracted currentFileNestedPredicateDecl
      preserving round-471 narrowing from the direct==null fallback too),
      resolveNamespaceMemberFnDecl, the three ns-fallback receiver
      resolvers (computeRawTypeOfPropertyAccess /
      resolvePropertyAccessToSymbol / propertyAccessChainIsNamespaceQualified),
      isCalleeResolvable, checkPropertyAccessAssignment's ns base, the two
      mam receiver consults, and the protected-ctor heritage walks
      (findEffectiveConstructorVisibility/classExtendsOrIs) — all keyed by
      the name's own Identifier node. Conflated 20,941 → 10,034 (−52%);
      suite +9 (Inv3ValueCalleeNodeKeyTest — 4 leak-kills FAIL pre-flip via
      stash); listAll byte-identical on compiler AND services; bench in
      band. Phase 3 DONE round 507b (2026-07-13) — (iii) COMPLETE:
      `getTypeOfIdentifier`'s globals fallback node-keyed (the round-442
      by-NAME dead-end does NOT reproduce per-FILE — imports resolve
      through the visibility probe to the same merged instance; pinned by
      Inv3IdentifierTypingNodeKeyTest incl. the import-driven
      initializer-inference control from the round-442 regression family),
      plus a fast path in `lookupPerFileForNode` (non-module-only names
      skip the parent walk — the fallback is ~2M calls/compile). Conflated
      10,034 → 6,165 (cumulative 20,941 → 6,165, −71%); `factory` gone;
      checkImplicitAnyParameters 2,608 → 171, checkUncalledFunctions 968 →
      189. Suite green 10,298 → 10,302 (+4); listAll byte-identical on
      compiler AND services; bench +4.0% single-run = the documented
      box-drift band (~126k parent walks ≈ negligible by construction).
      Residue ~6.2k = the (iv) type-position tail (types.ts type names
      reached via typeNodeDefinitelyNonNullish / resolveTypeNameToSymbol /
      getTypeFromBaseTypeExpression) + ~500 value-name lookups in the
      shadow-detection ecology (registerNestedGlobalShadow*/
      applyBodyLocalShadowing/shadowNestedFunctionNames ask "does a merged
      global collide" — they die with INV.3(d), do not flip them) + tiny
      tail sites (emitTs2345ForBareTpArgToConstrainedTpParam,
      getOverloadImplementationRelated, calleeReturnAnnotationForImplicitAny
      — fold into (iv)'s re-measure).
    - (iv) **Flip the type-position tail**. Leg 1 DONE round 508 (2026-07-13):
      `resolveTypeNameToSymbol`'s Identifier branch + `typeNodeDefinitelyNonNullish`'s
      two fallbacks flipped JOINTLY per the round-507c order constraint, with
      the two call-site trailing `?: globals[name]` fallbacks
      (`getTypeFromTypeReference`, `checkConstraintsInTypeNode`'s TS2315
      emitter) gated to QualifiedName — for Identifier names they were
      byte-redundant pre-flip and would silently RE-LEAK the node-keyed null
      post-flip (the trap now in the CLAUDE.md INV.3(c) entry). The full
      suite caught a REAL visibility gap the flip exposed:
      `lookupPerFileForNode` now grants a node inside a `declare module
      "<relative-spec>"` AUGMENTATION block the augmented module's direct
      named exports (the round-443 rule; the innermost string-named
      ModuleDeclaration is captured during the parent walk, unclassified
      under --passTiming) — without it the flip nulled `UnionType` inside
      services-style `declare module "./types.js"` blocks and this-predicate
      narrowing died (ThisPredicateNarrowingTest's augmentation pin).
      Test-design lesson: the ADDITIVE leak-kill direction is SHADOWED by
      any-degradation (an unresolvable callee annotation degrades the
      assigned reference to `any` — proven with a never-declared `Zorp`
      control — masking the TS18048/TS2322 consumers), so the flow
      observable uses the SUPPRESSION direction: a foreign UNIMPORTED
      NULLABLE alias return-annotation pre-flip types the reference as the
      leaked union and manufactures TS18048 on a closure-captured read;
      post-flip it degrades to any and the leaked TS18048 dies (tsc-faithful).
      Suite +9 (Inv3TypePositionNodeKeyTest — 3 leak-kills FAIL pre-flip via
      stash: the flow TS18048, annotation-position TS2322, TS2315; 6
      preservation controls pass both sides); `--listAll` byte-identical on
      compiler AND services. Leg 2 DONE round 509 (2026-07-13) — **(iv) and
      the whole (c) migration COMPLETE**: getTypeFromBaseTypeExpression's
      Identifier fallback (PropertyAccess last-segment fallback kept legacy —
      the QualifiedName convention), emitTs2345ForBareTpArgToConstrainedTpParam,
      getOverloadImplementationRelated (keyed by the overload DECL's own name
      node — a nested/foreign collision no longer hands TS2793 a wrong-file
      impl pointer), calleeReturnAnnotationForImplicitAny (the
      uniqueFunctionDeclByName fallback still covers program-wide-unique
      names). Suite +5 (Inv3TypePositionLeg2NodeKeyTest — 2 leak-kills FAIL
      pre-flip via stash: a leaked foreign heritage base grafting members
      manufactured TS2741 on `const d: D = {}`, a leaked foreign
      constrained-TP callee manufactured TS2345; 3 preservation controls);
      listAll byte-identical on compiler AND services. RE-MEASURE (compiler
      profile): CONFLATED 6,165 → **917** (−85%; from the pre-migration 157k
      → −99.4%), 97 names / 9 passes, top 318/284/273 — the residue is the
      deliberately-legacy shadow-detection ecology (`diag`/`clone`/`map`/
      `factory` collision questions) + tiny tails, i.e. INV.3(d)'s scope.
      INV.3(d) is UNLOCKED.
  - [x] **INV.3(d) Retire the merge + delete the ecology — COMPLETE round 513** (checkbox reconciled round 612; the body below records the full campaign). Stop merging
    module-file locals into `globals`; delete `moduleFileLocalVarNames`,
    `conflatedTypeAliasFiles`, `conflatedInterfaceFiles`,
    `conflatedEnumFileSubsets`, the per-file interface views, and the chimera
    bails — walker-by-walker, each deletion suite- and listAll-gated (each
    removes hot-path work from `checkMemberAccessMissing`).
    **THE RETIRE IS MERGED TO MAIN (round 512): sub-items (i)–(iv) all DONE —
    suite fully green (10,346/0/3) and ALL 8 profiles byte-identical to the
    pre-retire baselines. Remaining: (v) the ecology deletions (the round-473
    Identifier dispatch is already deleted as the (iv) residual fix — its
    removal is what restored the server/harness baselines).** What the branch
    proved (measured round 510): the retire
    must be STAGED BY NAME CLASS — retire only MODULE-ONLY names; SHARED names
    (module local colliding with a lib/script global: `Symbol`/`Node`/
    `Performance` riding the lib names) must KEEP merging until every lib-name
    consumer resolves per-file (the naive full retire measured 861 compiler
    FPs, the module-only cut 34, each traced to an unflipped consult by the
    classifier-MISS stack-probe technique). Sub-items to finish it, in order:
    - (i) DONE round 511 (2026-07-14): the ambiguous-constrained→foreign leg
      REVERTED (declaration-IDENTITY leg kept) — flipped the whole TP family
      (17 tests: the 8 corpus TP pins + 3 local negative controls +
      tsxTypeArgumentPartialDefinitionStillErrors ×2 + WhileTrueDefiniteAssignTest
      ×4, the last two collateral of the over-aggressive classification);
      checker.ts:7358 re-solved at the INFERENCE side —
      `tryInferSingleTypeParamFromArgs` soft-skips a CallExpression arg whose
      type still carries a TypeParam at forReturnType sites (tpSawAnyArg →
      anyType, the pre-retire any-degradation behavior; round-468
      CallExpression gate keeps own-TP identifier args anchoring). Pinned by
      ForeignTpInferenceSoftSkipTest (6); compiler+services listAll
      byte-identical.
    - (ii) DONE round 512 — all 14 corpus multi-file failures fixed (the last 6:
      union-discriminant objlit drill node-keyed; ns-import static TS2339 +
      the dir-relative resolveAlias legs; TS2749 file-keyed with the
      typeSideImportFallback gate; the B585 contextual-display hops; the JSDoc
      ImportType own-specifier resolution; the TS2415 imported-base flip).
      Round-511 record follows:
      heritage/implements walkers node-keyed (interfaceDeclaration3,
      interfaceImplementation6 — incl. the B563 ownership-gate mirror that
      killed the double TS2420), checkConstraintsForTypeArgs keyNode +
      ImportType presetSymbol (divergentAccessorsTypes6,
      unmetTypeConstraintInImportCall), checkTypeNameResolved's leftSym →
      globalsForFile (augmentExportEquals1/2 + decoratorMetadataWithImport…7),
      the mam type-only-winner + namespace-import value-side bail
      (noCrashOnImportShadowing), **and the session's critical find: the
      import hop (`resolveImportedSymbolGeneral`) lacked the DIR-RELATIVE
      resolver leg, so path-shaped extensionless imports (`/proj/src/f1.ts` →
      `./lib`) never hopped and EVERY import-mediated type died on real
      on-disk projects — masked pre-retire by the merge, invisible to the
      `.js`-specifier tsc profiles; found via the EnclosingImportIndexTest
      pins + a MainKt scratch-repro matrix.** REMAINING 6 (per-test roots,
      each needs a probe dig): exportStarFromEmptyModule (X.A.r static
      TS2339 through a local-shadowed star chain),
      allowImportClausesToMergeWithTypes (TS2749 default-import-of-value used
      as type), allowJscheckJsTypeParameterNoCrash (display regression:
      `WatchHandler<any>` unfolds to the fn-type — alias display lost),
      checkJsdocTypeTagOnExportAssignment2 (JS `@type import("./a").Foo`
      excess-prop TS2353 — the JSDoc path's cross-file resolution),
      declarationEmitPrivateSymbolCausesVarDeclarationEmit2 (TS2415 with
      cross-file computed `[x]` private members),
      indirectDiscriminantAndExcessProperty (single-file module: TS2322
      member-vs-discriminant `"foo" | "bar"` — the objlit-member drill's
      resolution; NOT tryEmitObjectVsNamedUnionArg, whose anonymous
      constituents defer to the discriminant walker).
    - (iii) DONE round 512 — the last 4 were 2 real resolver gaps (the
      `export * as` arm in namespaceAliasMemberSymbol; the ns-member objlit ctx
      flips) + 2 pre-retire ACCIDENTAL PASSES fixed tsc-faithfully (all-missing
      all-anonymous union TS2339; primitive-vs-plain-object-bag TS2345).
      Round-511 record follows:
      (Inv3NodeKeyedLookupTest's unindexed-copy degradation → null for
      module-only names; Inv3GlobalsLookupTest's leak assertions inverted to
      the emptied-worklist victory condition); 3 more of the original 9
      flipped as REAL code fixes (EnclosingImportIndexTest ×2 +
      Inv3NodeKeyedLookupTest imports-keep-resolving via the dir-relative hop
      leg; ExtendsImplementsSameClassTest + NamespaceImportQualifiedTypeTest
      via the (ii) walker flips). REMAINING 4, all look like REAL
      suppressions to dig (scratch repros r7/r8 reproduce two):
      ConflatedTypeAliasLeakTest ×2 (own-file `type X` union TS2339 /
      own-file TS2345 both silent — receiver/param resolution in the alias's
      own file returns something unexpected post-retire),
      NamespaceQualifiedBaseInheritanceTest (export-star-as barrel base →
      TS2339 FP returned), BuilderChainAndNsMemberCtxTest (ns-member objlit
      contextual params → TS7006 FP returned).
    - (iv) DONE round 512 — all three residual families closed: deprecate.ts
      `compareTo` (an anyType shadow now BAILS mam instead of falling through
      to the outer import); session.ts protocol.Diagnostic (the round-473
      Identifier DISPATCH into conflatedPerFileInterfaceType REMOVED — the
      first (v) deletion, see the session note); fourslashImpl `'array'`
      (namedUnionMemberCouldAcceptArray hops import aliases). **Full 8-profile
      listAll A/B vs pre-retire main: ALL BYTE-IDENTICAL**; suite fully green;
      branch merged to main.
    - (v) DONE round 513 — ALL FOUR deletion groups landed (each suite- and
      8-profile-listAll-gated byte-identical): `moduleFileLocalVarNames` (+2
      masked narrowing gaps fixed), `conflatedTypeAliasFiles` (2 helpers
      re-keyed onto non-conflation conditions), `conflatedInterfaceFiles`
      objlit/relation chimera bails + TS2430/heritage view arms, and the
      per-file-view core (`conflatedPerFileInterfaceType`/`perFileInterfaceType`/
      owner-context threading) + `conflatedEnumFileSubsets`. SURVIVORS
      (deliberate): `moduleInterfaceNames`+`isLibPhantomMemberOfModuleInterface`
      (lib+module SHARED merges persist), `interfaceDeclsForCurrentFileView`
      discriminant reading, the re-keyed augmentation/alias-union bridges, the
      `A && objlit` falsy-remainder emitter, and the `nodeTypes` bypass re-keyed
      as `isPerFileDependentRefNode` on `multiFileModuleTypeNames` (the
      structural cache's cross-file position collisions are NOT
      conflation-specific — see the session note). **INV.3(d) is COMPLETE; the
      INV.3 arc is COMPLETE. NEXT: INV.4.**
- [x] **INV.4 Single-pass check spine — CLOSED round 599** (see the round-599 note: migration + retirements banked −13% wall + ONE authoritative walk; the (f) memo/fold designs are measured dead-ends until INV.5 canonical types). `checkSourceFileOnce` per-node dispatch;
  migrate walker families in INV.0's cost order — every migration deletes a full-tree
  pass and its private scope machinery. Once ONE authoritative walk state exists, land
  the two things that are unsound today: a per-node expression-type cache, and flow
  narrowing folded into reference typing once (collapsing the rounds-408–479
  per-consumer wiring). Decomposed round 514. Cross-cutting rules for every
  sub-item: (1) the spine is dispatched as ONE `pass("checkSpine")` at a FIXED
  init position (the earliest migrated pass's slot); passes migrating in from
  LATER positions move their emissions earlier in insertion order — the stable
  diagnostic sort (start→length→code→message) hides all but exact 4-tuple ties,
  and the per-migration corpus + listAll gates decide each case. (2) A spine
  handler sees ALL nodes: a hand-walk's accidental under-visits (arrow bodies,
  class/function expressions, initializers) become visits — per migrated pass,
  decide widen-vs-gate by the CLAUDE.md emission-direction rule (a
  position-independent tsc grammar rule widens faithfully; an FP-firewalled
  heuristic walker must reproduce its descent gates via parent-chain checks).
  (3) Every migrated pass with no local pins gets them BEFORE migration (the
  corpus pins emit bytes, not checker diagnostics — `.errors.txt` is disabled,
  so local tests are the primary under-emission gate). (4) Suite green +
  8-profile listAll + bench row per landed commit.
  - [x] **INV.4(a) Spine skeleton + pilot migration.** DONE round 514
    (2026-07-14): `checkSpine()` at the old checkAccessorModifierTarget slot —
    iterative enter/leave preorder walk per file (explicit parallel stacks;
    10k-chain pinned), per-file spine context fields declared BEFORE `init`,
    per-node `when` dispatch in `spineEnterNode`/`spineLeaveNode` (tsc
    checkSourceElement-style; plain private handler funs), active-handler
    gate skips the walk when every migrated handler is off (the profiles
    target ES2020 → pilot handler off → bench-neutral by construction).
    Pilot: TS18045 migrated — threaded `inAmbient` became an INV.2
    parent-chain ancestry check ([spineInAmbientContext]); the 78-line
    private walk deleted; coverage widened faithfully to class expressions /
    arrow bodies (position-independent grammar rule; both directions pinned).
    Suite +9 (Inv4SpineAccessorModifierTest), listAll byte-identical on
    compiler AND services (46/46; header-only argv difference), bench row in
    band. The leave hook is the scope-pop extension point — its pairing gets
    its first real pin when the first stateful migration lands.
  - [x] **INV.4(b) Tail-pass batches.** Migrate the 474-pass sub-100 ms tail
    (7.3 s = 36.5% of checker-init, round-491 table) in batches of ~5–15 per
    commit, most-mechanical first (zero-typing grammar/AST-shape walkers with
    per-file prepasses moving to a file-enter hook); each batch deletes its
    walks. Re-measure `--passTiming` every few batches; stop batching a shape
    that resists (stateful scope machinery) and queue it for (c)/(d) instead.
    Batch 1 DONE round 514 (2026-07-14): checkInvalidGlobalAugmentations
    (TS2669/TS2670) + checkReservedWordInterfaceParams (TS7051/TS7006) —
    both old walks descended ONLY through module bodies, so reachability is
    reproduced as a module-chain parent-walk gate (the template for
    module-scope-only walkers); the reserved-params handler deliberately does
    NOT widen to function/class-nested interfaces (a behavior change to make
    on a signal, not as a migration side effect); currentFileLocals is now
    set per file in checkSpine's loop (isTypeLikeParamName consults it); the
    spine walk is ALWAYS-ON from this batch (the TS2669 handler is
    unconditional and covers .d.ts — the .d.ts fast-skip lifted into
    per-handler gates). Suite +10 (Inv4SpineBatch1Test), listAll
    byte-identical on compiler AND services. WALK-COST measurement
    (interleaved 3-pair A/B vs the pre-batch binary — the round-493 rule): the
    first-cut enter/leave walk cost a REAL +1.0 s median on the compiler
    profile (boxing ArrayList<Boolean> phase stack + a leave frame per LEAF);
    fixed same commit — primitive BooleanArray phase stack + leaf shortcut
    (leave fires inline for childless nodes, no re-push) → re-interleaved
    NEUTRAL within noise (pair deltas +861/−1063/+574 ms, mean +124 ms).
    Per-frame costs are the whole game in a walk that visits every node —
    the walk KDoc carries the warning. Batch 2 DONE round 515 (2026-07-14):
    checkNonArrayRestParameters (TS2370 — the two differently-shaped walks
    became ONE Parameter-enter handler dispatching on the parameter's PARENT
    kind: value-position parents get the keyword rule, type-position parents
    the optional-rest rule; both widened faithfully — position-independent
    per-signature grammar) + checkIteratorMethodExtraParameters
    (TS2488/TS2504) + checkAsyncYieldStarThenable (TS1320) — the prepass
    pair became spine COLLECTION (VariableDeclaration enter, VariableStatement
    parent gate) plus BUFFERED iteration positions/yield* candidates resolved
    at file END (spineResolveDeferredIterationChecks — preserves the old
    prepasses' use-before-decl semantics with NO extra walk; the template for
    collect-then-scan walkers). TS1320's statement-level-only reachability
    widened to a nearest-function-ancestor async-generator gate. 16 walker
    funs deleted (~460 lines), 3 init slots removed. Suite +21
    (Inv4SpineBatch2Test), listAll error lines identical on ALL 8 profiles,
    wall in band. Batch 3 DONE same round: checkForOfNonIterable (TS2495 —
    the per-run lib-exclusion gate became spineForOfNonIterableActive; the
    verdict helper checkForOfExprNonIterable retained unchanged) +
    checkAbstractAccessorReturnTypes (TS7033 — GetAccessor-enter handler;
    the ClassDeclaration-parent gate keeps class-EXPRESSION members
    unchecked; the `.js`/`.jsx` skip is deliberately NOT spineIsJsLike —
    the old pass ran on .mjs/.cjs); 6 more walker funs + the round-514
    orphaned TS18045 KDoc deleted. Suite +9 (Inv4SpineBatch3Test), listAll
    identical on ALL 8 profiles. Batch 4 DONE round 516 (2026-07-14):
    checkSetterParameterCount (TS1054/TS1049/TS1095 as Get/SetAccessor-enter
    handlers — TS1054/TS1049 widened faithfully to class expressions +
    interface/type-literal accessors, TS1095 widened exactly to class
    expressions (the objlit/interface parses never store a setter return
    annotation); TS2808 as a ClassDeclaration-enter pair check KEPT at the
    old ClassDeclaration-only gate) + checkRestParameterLast (TS1014 — a
    second Parameter-enter handler; widened to FunctionType/ConstructorType/
    type-literal methods per tsc checkGrammarParameterList; GetAccessor
    parents stay excluded) + checkMultipleDefaults (TS1113 —
    SwitchStatement-enter, one-per-switch latch preserved) +
    checkInterfacePropertyInitializers (TS1246 — InterfaceDeclaration-enter;
    the parser owns the common shape). 17 walker funs (~733 lines) deleted,
    4 init slots removed. Suite +22 (Inv4SpineBatch4Test), listAll identical
    on ALL 8 profiles, bench in band. Batch 5 DONE round 516 (same session):
    checkConstWithoutInitializer (TS1155) + checkDestructuringWithoutInitializer
    (TS1182/TS7031) as VariableDeclaration-enter handlers — shared owner gate
    (VariableStatement non-declare/non-ambient via spineInDeclareModuleChain,
    the parent-walk equivalent of the old isAmbient threading which reset at
    every non-module descent; or a for(;;) initializer; for-in/for-of
    excluded); emitTs1182IfMissingInit retained; for-of/for-in BODIES are a
    faithful widening (the old walks had no ForOf/ForIn case). Plus
    checkComputedPropertyNameLiteral (TS1166/TS1169 by PropertyDeclaration
    parent kind; TypeLiteral stays unchecked) + spineCheckClassExprComputedProps
    (the TS1206 legacy-decorator short-circuit, position-GATED to the old
    expression-statement-only reach — pinned negative). 7 walker funs
    (~318 lines) deleted, 3 init slots removed. Suite +16
    (Inv4SpineBatch5Test), listAll identical on ALL 8 profiles, bench in
    band. Batch 6 DONE round 517 (2026-07-14): checkDuplicateModifiers
    (TS1030/TS1029/TS1044 — statement-kind handlers over 10 node kinds; the
    threaded inAmbientContext + atTopLevel pair became ONE parent-chain walk,
    `spineDupModContext`, where the INNERMOST flag-deciding ancestor wins per
    flag — fn/member bodies reset ambient, Block decides atTopLevel=false,
    ModuleBlock resets it true — and any non-descended ancestor kind returns
    null = the old no-visit; checkModifiers/checkInvalidImportEqualsModifiers
    retained as FP-firewalled text heuristics, reach NOT widened per B69.6) +
    checkAmbientInitializers (TS1039/TS1254/TS1066/TS1031 — Enum/
    VariableStatement/ClassDeclaration enter handlers over
    `spineAmbientInitContext`; .d.ts top-level-ambient preserved at the
    SourceFile terminal; class-member/arrow bodies stay unreached — pinned
    negative, a signal-driven widening candidate; the B162 same-enum sibling
    scan reproduced via `spineSiblingStatements`) + checkSwitchCaseComparable
    (TS2678 — the per-statement-LIST const/annotated binding maps reproduced
    as a preceding-sibling scan at the SWITCH node,
    `spineSwitchSubjectBinding`; single-statement positions degrade to
    `listOf(stmt)` = the old fresh-map wraps). 9 walker funs (~453 lines)
    deleted, 3 init slots removed. Suite +27 (Inv4SpineBatch6Test, pins run
    against the OLD walkers first), listAll error lines identical on ALL 8
    profiles, bench in band. Batch 7 DONE same round: checkRestElementPropertyNames
    (TS2566 — pure-syntax, ObjectBindingPattern-enter handler; widened
    faithfully to catch-clause patterns, each nested pattern gets its own
    enter) + checkRestBindingPatternElements (TS1186/TS2493/TS2322 —
    `checkRestBindingParam` retained as the Parameter-dispatch core; widened
    to object-literal-method/class-expression params) +
    checkAmbientImplementation (TS1183 — the most intricate reach walk so
    far, `spineAmbientImplContext`: ambient fn/class-member bodies were never
    descended (own-declare → null + the [passedDeclBody] declare-module-above
    rule), while arrow/fn-expr/class-EXPRESSION-member/objlit-method bodies
    RESET ambient unconditionally (passedDeclBody cleared — the expression
    walk descended them with false even under ambient); statement containers
    position-checked (conditions/for-headers/switch-subjects/case-exprs
    unreached), expressions pass generically; interface arm is de-facto
    dormant — the parse drops interface method bodies, cf. the TS1246 note) +
    checkAmbientRelativeModuleNames (TS2436 — top-level-of-script-file gate =
    a SourceFile parent check). 15 walker funs (~551 lines) deleted, 4 init
    slots removed. Suite +21 (Inv4SpineBatch7Test — 19 pre-verified against
    the OLD walkers, 2 widening pins fail pre-migration as expected). Batch 8
    DONE round 518 (2026-07-14): the parameter-initializer family — SIX
    passes as three Parameter-enter handlers + one SetAccessor-enter handler:
    checkOptionalParamWithInitializer (TS1015 — the corpus-tuned requireType
    gate preserved: declarations need a type annotation or param-property
    modifier, arrow/fn-expr params fire regardless; interface/type-literal
    signatures and objlit/class-expr GET accessors stay excluded per the old
    reach) + checkOptionalBindingPatternParams (TS2463 — uniform
    owner-has-body gate per parent kind) + checkParamInitializerForbidden
    (TS2523/TS2524/TS2372/TS2502/TS18048 — walkParamInitForbidden + the
    binding-name walk + collectParamSelfRefs retained as the per-parameter
    core; the per-file code@pos dedup set became spineParamForbiddenEmitted;
    the walkParamForbiddenExprForFns nested-fn descent dissolves into
    per-Parameter enters; findParamSelfRef deleted as already-dead) +
    checkParameterInitializerInNonImpl (TS2371 — widened faithfully to EVERY
    FunctionType/ConstructorType position per tsc checkParameter (initializer
    + missing containing body); old reach was var annotations/aliases/casts
    only; accessors stay excluded) + checkSetAccessorInitializer/
    checkSetAccessorRestParameter (TS1052/TS1053 — parent gate widened from
    class declarations to class expressions + object literals per tsc
    checkGrammarAccessor; interface/type-literal setters excluded, a
    signal-driven candidate). 24 walker funs (~902 lines) deleted, 6 init
    dispatches removed. Suite +29 (Inv4SpineBatch8Test — 23 pre-verified
    against the OLD walkers, 6 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (518a vs 517b).
    Re-measured --passTiming (pre-batch): checker-init 21.6 s, spine 529 ms
    carrying 24 passes; this batch's six summed ~292 ms of old pass time.
    Batch 9 DONE same round: checkForInLhsTypeAnnotation (TS2404 —
    ForInStatement-enter; widened faithfully to arrow/fn-expr bodies the old
    statement walk never descended) + checkEmptyTypeArguments (TS1099 on
    calls/new — CallExpression/NewExpression-enter; the type-POSITION TS1099
    emitter sharing emitTS1099 is untouched; reportEmptyTypeArgs deleted as
    orphaned) + checkSetterReturns (TS2408 — SetAccessor-enter;
    checkSetterBodyReturns retained as the per-setter body scan, fn-boundary
    semantics unchanged; widened to await operands etc.) + checkWithStatements
    (TS1101/TS1300/TS2410 — WithStatement-enter; the threaded isInWith/isInAsync
    pair became ONE parent-chain walk: first WithStatement ancestor before any
    function-like boundary → inner-with suppression of TS1300/TS2410; nearest
    fn boundary's Async modifier decides TS1300, ARROWS still reset async to
    false (old behavior, tsc's AwaitContext would fire — signal-driven
    candidate, pinned negative); TS2410's balanced-paren span scan preserved;
    TS1101 gated on alwaysStrict != false via spineWithStrictActive). 16
    walker funs (~606 lines) deleted, 4 init slots removed. Suite +18
    (Inv4SpineBatch9Test — 14 pre-verified against the OLD walkers, 4 widening
    pins fail pre-migration as expected); listAll error lines IDENTICAL on ALL
    8 profiles (518b vs 518a). Batch 10 DONE round 519 (2026-07-14):
    checkParamInitForwardRef (TS2373 + the ES5 hoisted-body-var TS2454
    companion) — checkForwardRefsInParams (+ findForwardParamRefs /
    findForwardParamRefsInBlock / collectHoistedVarNamesFromStmts) retained
    as the per-function core, dispatched from spineCheckParamForwardRefs at
    every BODIED function-like's enter; widened faithfully to arrows /
    fn-exprs / objlit methods / class-EXPRESSION members
    (position-independent per-signature tsc grammar); bodyless signatures
    keep the old no-check (TS2371 territory), GetAccessor params stay
    unchecked (TS1054 territory). 2 walker funs (~70 lines) deleted, 1 init
    dispatch removed. Suite +14 (Inv4SpineBatch10Test — 10 pre-verified
    against the OLD walker, 4 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519a vs 518b). Batch 11
    DONE same round: the checkJumpTargets family (TS1104/TS1105/TS1107/
    TS1115/TS1116 + TS1344) — the threaded inIteration/inSwitch/labelNames/
    crossedFunctionBoundary flags became ONE parent-chain walk
    (spineCheckJumpTarget) mirroring tsc
    checkGrammarBreakOrContinueStatement's `while (current)` loop: first
    function-like ancestor → TS1107 (class static blocks now count — a
    faithful widening); a matching LabeledStatement resolves the jump, with
    tsc's isIterationStatement(lookInLabeledStatements=true) nested-label
    unwrap for labeled `continue` — a faithfulness FIX over the old
    immediate-child test (`L1: L2: for(;;){continue L1}` no longer
    false-fires TS1115); an iteration ancestor legalizes unlabeled jumps, a
    SwitchStatement legalizes unlabeled `break`, a ModuleBlock ancestor
    suppresses unlabeled `break` (the old inSwitch=true namespace rule);
    TS1344 label-on-declaration became a LabeledStatement-enter handler
    (widened to arrow-in-condition positions). 4 walker funs (~306 lines)
    deleted, 1 init dispatch removed; emitJumpDiagnostic /
    isDeclarationStatement retained as the per-jump core. Suite +18
    (Inv4SpineBatch11Test — 14 pre-verified against the OLD walker, 3
    widening + 1 faithfulness-fix pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519b vs 519a). Batch 12
    DONE same round: checkObjectLiteralModifiers (TS1042/TS1184) — the
    near-full-tree explicit-stack expression walk became a pure
    ObjectLiteralExpression-enter handler (spineCheckObjLitModifiers;
    OBJLIT_ACCESS_MODIFIERS companion-hosted per the init-order gotcha);
    nested literals get their own enters; parameter-default and
    spread-operand positions are faithful widenings. 3 walker funs
    (~206 lines) deleted, 1 init dispatch removed. Suite +10
    (Inv4SpineBatch12Test — 2 widening pins fail pre-migration as expected);
    listAll error lines IDENTICAL on ALL 8 profiles (519c vs 519b). Batch 13
    DONE round 520 (2026-07-14): checkDuplicateObjectLiteralProperties
    (TS1117/TS1118/TS2300 — [checkObjectLiteralDuplicates] retained as the
    per-literal core dispatched from the ObjectLiteralExpression enter; the
    destructuring-assignment-LHS skip became the came-from-child parent walk
    `spineObjLitInDestructuringLhs`: climb through pattern-position parents
    — object/array literals, a PropertyAssignment when the child is its
    INITIALIZER, spread positions — and skip iff a `=` BinaryExpression is
    reached with the climbed child as its LEFT; a ShorthandPropertyAssignment
    default VALUE terminates the climb, so `({q = {a,a}} = o)` is now checked
    — a tsc-faithful widening alongside ternary conditions, parameter
    defaults, and object-literal METHOD bodies) + checkReservedWordIdentifiers
    (TS1359 — checkAwaitParams retained, dispatched from every async
    function-like's enter; the enum void/await/yield name rule as an
    EnumDeclaration-enter handler; widenings: class property-initializer
    arrows, new-expression var initializers, var-init arrow expression
    bodies) — 6 walker funs (~370 lines incl. the already-dead reservedWords
    val) deleted, 2 init dispatches removed. Suite +23 (Inv4SpineBatch13Test
    — 16 pre-verified against the OLD walkers, 7 widening pins fail
    pre-migration as expected); listAll error lines IDENTICAL on ALL 8
    profiles (520a vs 519c). Batch 14 DONE same round:
    checkStrictModeReservedWords (TS1212/TS1213/TS1214/TS2480/TS18006 — the
    most stateful zero-typing walker yet): the threaded isStrict/
    isExpressionStrict/inClass/realStrict flags became ONE shared
    ancestor-chain context (`spineStrictReservedCtx`: collect the parent
    chain, walk it DOWN applying the old descent arms —
    Block/If/ForIn/ForOf/ModuleBlock/ModuleDeclaration transparent, a
    FunctionDeclaration entered ONLY under the strictness at ITS position
    with a "use strict" prologue upgrading realStrict for its subtree, a
    ClassDeclaration entered only through METHOD/CONSTRUCTOR members
    (auto-strict: inClass + both strictness flags forced), any other
    ancestor kind → null = the old no-visit); ten per-statement-kind
    handlers (var-statement incl. fn-expr-name/type-annot/class-expr-init
    legs, for-in/of header decls, fn decl, class decl incl. TS18006 +
    member params, interface, enum, import-equals, import bindings,
    namespace name, expression statement); per-file flags
    (spineStrictFile* — binding strictness by effectiveTarget, EXPRESSION
    strictness by RAW target, the explicitNonStrict suppression) computed
    in checkSpine's loop; the two strictReserved* instance flags moved to
    the pre-init spine block, assigned per position from the ctx. Reach
    deliberately NOT widened (corpus-tuned family — interfaceNaming1 /
    commonMissingSemicolons / constructorStaticParamName): while/do/for/
    switch/try bodies, accessor bodies, arrow/fn-expr bodies, and
    class-expression members stay unvisited, pinned negative as
    signal-driven widening candidates; the load-bearing reach QUIRK — fn
    bodies UNVISITED in non-strict files (no TS2480 for `let let` there) —
    is reproduced by the ctx walk and pinned. 3 walker funs (~250 lines)
    deleted, 1 init dispatch removed. Suite +25 (Inv4SpineBatch14Test —
    ALL 25 pre-verified against the OLD walker; a pure reach-preserving
    migration, no widenings); listAll error lines IDENTICAL on ALL 8
    profiles (520b vs 520a). --passTiming RE-MEASURE (round 520, post
    batch 14): checker-init 20.0 s (21.6 s pre-batch-8); spine 718 ms
    carrying ~34 migrated passes; 459 passes recorded (~55 dispatches
    removed since INV.0's 514); top-3 giants unchanged
    (checkPropertyAccess 3.53 s / checkTypeAssignability 2.33 s /
    checkCallExpressionTypes 2.06 s = 7.9 s); the next-biggest non-giant
    passes are EXACTLY the INV.4(c) pair — checkUnresolvedNames 744 ms +
    checkTypeUsedAsValue 739 ms — then the (d) cohort
    (checkUncalledFunctionsInConditions 454 ms, checkArithmeticOperandTypes
    335 ms, checkImplicitAnyParameters 279 ms); the remaining zero-typing
    tail is mostly sub-100 ms each (checkAwaitContext 93 ms — stateful
    isAsync threading + the TS1262 top-level prepass + the batch-8 TS2524
    param-default ownership boundary; decompose when reached, low yield).
    Batch 15 DONE round 521 (2026-07-14) — **(b) COMPLETE**: checkAwaitContext
    (TS1308/TS1103/TS2311/TS1262 — the threaded isAsync/enclosingFunc pair)
    became THREE rare-node enter handlers (spineCheckAwaitExpr /
    spineCheckForAwait / spineCheckAwaitCall) driven by ONE full parent-chain
    walk (`spineAwaitCtx`): the FIRST function-like boundary decides the flags
    (async modifier; the TS1356 related-info FuncRef — ctor/accessor/prop-init
    boundaries force sync), and EVERY chain step up to the SourceFile must be
    an old-walked position (parameter defaults are TS2524's, enum member
    initializers / computed names / static blocks / heritage / shorthand
    destructuring defaults / objlit ACCESSOR bodies stay unreached — pinned
    negative); ModuleDeclaration bodies are TRANSPARENT, preserving the
    namespace-inherits-module-asyncness quirk (pinned); the TS1262 top-level
    `await`-binding scan (checkTopLevelAwaitNames, retained) runs per module
    file from checkSpine's loop and sets the TS2311 suppression flag. 4 walker
    funs (~310 lines) deleted, 1 init dispatch removed. Suite +27
    (Inv4SpineBatch15Test — ALL pre-verified against the OLD walker; a pure
    reach-preserving migration); listAll error lines IDENTICAL on ALL 8
    profiles (521a vs 520b). Closure decisions: checkConflictMarkers STAYS an
    init pass (a per-file TEXT scan — the spine walks nodes; there is no walk
    to delete); checkMixinClassConstructor is TP-scope-stateful → (d). The
    remaining stateful walkers are (c)/(d) territory.
  - [x] **INV.4(c) The name-resolution pair** — COMPLETE round 529 (all four
    sub-items landed; both families' recursive walkers deleted).
    checkUnresolvedNames (846 ms) +
    checkTypeUsedAsValue (734 ms): fold their private NameScope chains into
    spine-maintained authoritative lexical state backed by the INV.2(c)
    `lexicalScopes` tables (their planned mass consumption). Decomposed round
    522 (facts verified in-code: the checkUnresolvedNames family is ~3,000
    lines — statement/class-element/expression/type/JSX walkers threading a
    `NameScope` chain whose content closely mirrors `lexicalScopes` (params,
    hoisted vars, block bindings, type params + constraints) plus per-file
    root extras (KNOWN_GLOBALS seeding, DOM/host @lib filtering, ambient-
    module-name exclusion, `declare global` handling, JS @typedef regex
    types) and walk-threaded flags (classContext / inFunction / hasArguments);
    checkTypeUsedAsValue is ~700 lines threading THREE ScopeNameSet chains
    (typeOnly/value/namespaceOnly) built from AST surveys — NOT symbol-shaped,
    and its reach is corpus-tuned per the round-42 over-emission gotcha (no
    loop/switch/try descent)). Sub-items, one commit each, every step suite-
    and 8-profile-listAll-gated:
    - [x] **(c)(i) Spine-maintained lexical scope state (infrastructure,
      always-on).** DONE round 522 (2026-07-15 — the checkbox was missed in
      that round's commit; see the round-522 session note for the full
      landing record). The walk maintains `spineCurrentScope` — push at a scope
      owner's enter (BEFORE its own handlers dispatch), pop after its leave —
      via a per-file nodeId→LexicalScope ARRAY built from
      `result.lexicalScopes` (the INV.2(b) boxing-avoidance trick; cleared by
      re-nulling only written ids); a SwitchStatement's scope is re-keyed
      onto its CLAUSE nodeIds at fill so the switch EXPRESSION stays in the
      outer scope (the binder's routing); function-body Blocks share the fn
      scope automatically (no map entry); decorator outer-scope routing is a
      documented deferred divergence (both the walk and the binder tables
      currently agree). `spineScopeLookup(name)` resolves symbols → existing
      → parent. Pinned by a test-only AUDIT mode (companion statics — tests
      cannot reach the Checker instance): every spine enter verifies the
      incremental scope against a parent-chain derivation, and identifier
      enters record `spineScopeLookup` resolutions into a trace the tests
      assert on (shadowing id splits, scope-space ids ≤ −2, switch-expression
      isolation, catch/enum/self-name/var-hoist shapes). Bench row (the walk
      gains one array probe per enter+leave).
    - [x] **(c)(ii) checkUnresolvedNames STATE swap.** DONE round 523
      (2026-07-15): the NameScope content queries (`has` / `isTypeParam` /
      `hasType` / `typeParamConstraintOf` / `hasLocalShadow` / the TS2552
      candidate pool) are hybrid — each NameScope carries `lex` (the binder
      [LexicalScope] a TRUSTED scope-owner site links; population SKIPPED
      when linked) and queries interleave the threaded sets with the lex
      levels each NameScope level introduced (`lex` down to `parent.lex`,
      preserving shadowing order). Trusted links: statement lists via a new
      `checkUnresolvedInStatements(owner)` param (Block / SourceFile / the
      FUNCTION node for fn bodies — body Blocks have no binder entry),
      for/for-in/for-of headers, catch, switch (binder keys the case scope
      by the switch nodeId — the expression is checked before linking, so
      no re-keying needed), class/class-expr/interface/type-alias TP scopes.
      Function SIGNATURE positions stay threaded (params/TPs) — the binder's
      flat fn table would leak body decls into param defaults (sub-ES2015
      pre-collect is the only path that may see them; pinned both ways).
      Untrusted levels skipped in queries: ModuleDeclaration (the walk's
      buildNamespaceScope is EXPORT-filtered; binder aliases ALL merged
      members), EnumDeclaration (EnumMember-filtered), SourceFile existing
      filtered by a per-file exclusion set (ambient external module names +
      the declare-global quirk); type-level scopes (mapped TP / infer /
      fn-TYPE params) stay threaded. Unindexed trees: every probe misses →
      legacy behavior by construction. Equivalence-gated: corpus green +
      8-profile listAll error-line-identical; walk-threaded flags stay
      threaded until (c)(iii).
    - [x] **(c)(iii) checkUnresolvedNames WALK swap.** Move the emission
      positions onto the spine (delete the ~15 recursive walkers); reach
      reproduced per the emission-direction rule (this family is (b)-class —
      direct emitters — so under-visits are reproduced via parent-chain
      gates, widenings only on a signal). Batch 1 DONE round 524 (2026-07-15):
      the spine maintains the family's NameScope chain (`spineUResStack` —
      lazy signature population / deferred-activation regions / decorator
      pre-population views reproduce the legacy walk's sequential-mutation
      order on the spine's fixed preorder; per-file ROOT shared via
      `unresolvedFileRootFor`, enabled by the `computeTypeLibResolution`
      split), audited per-Identifier against the legacy walk's scope
      fingerprints (Inv4UnresolvedSpineScopeTest, 2 deliberate-breakage
      sharpness probes). classContext / inFunction / hasArguments ride the
      maintained NameScope levels (no parent-chain re-derivation needed).
      Batch 2 DONE round 525 (2026-07-15): the STATEMENT-LEVEL walk swap —
      checkUnresolvedInStatements/InStatement(Core) DELETED; per-statement
      dispatch in spineUResDispatch against the maintained levels;
      FunctionDeclaration signature positions at child enters
      (lazy-population staging); the with-body / skipped-return /
      declare-fn+class under-visits as suppressed-region levels and the
      declare-module post-filter as the filter2304 level flag, both enforced
      by the spineUResEmit wrapper (which also nulls currentFileLocals — the
      legacy pass ran unscoped); the 10 statement descents in the
      expr/class-element walkers cut; checkUnresolvedNames retained only as
      the declarationOnly minimal driver (spineUResOnly). listAll gate:
      error-line SETS identical on all 8 profiles; within-file PRINT order
      shifts (emission order — the corpus suite gates the sorted output
      byte-identical). Batch 3 DONE round 526 (2026-07-15):
      checkUnresolvedInClassElement DELETED — class-member decorators/
      computed-names at member enter (the pre-population moment = the legacy
      B98.r111 view), TP/param/return positions via the shared
      spineUResFnSigDispatch with per-member-kind coverage flags, index
      signatures in the class scope; gated to class decl/expr parents
      (interface members stay with the batch-2 handler). Batch 4 DONE round
      527 (2026-07-16): the EXPRESSION walk swap — expression positions
      self-emit at their own enters, gated by `spineUResExprChecked` (a
      per-file nodeId-memoized ancestor walk over `spineUResExprEdge`
      ROOT/DESCEND/NONE verdicts reproducing the recursive walker's exact
      reach); NaN/shorthand/embedded-type/class-expr-heritage/JSX handlers
      dispatch per node kind; spineUResFnSigDispatch reduced to TYPE
      positions (checkTps flag = the legacy fn-expr/objlit-method
      no-constraint-check asymmetry); the TS2422 skip became the
      spineUResHeritageSkip nodeId set; arrow/fn-expr/objlit-method levels
      carry exprOwned so recursion-owned regions keep the retained walker.
      checkUnresolvedInExpr(Core) retained SOLELY for the type walker's
      TypeLiteral computed-name positions. Batch 5 DONE round 528
      (2026-07-16) — **(c)(iii) COMPLETE, all the family's recursive walkers
      are DELETED** (checkUnresolvedInType(Core), the retained
      checkUnresolvedInExpr(Core), the JSX attribute/child helpers — ~660
      lines): type positions self-emit at their own enters. Unlike batch 4's
      static classifier, the type ROOTs are MARKED — every dispatch site that
      called the walker now calls `spineUResMarkTypeRoot` (strictly before
      the marked subtree walks; the sites stay the single source of truth),
      and `spineUResTypeChecked` (per-file nodeId memo) walks ancestors over
      `spineUResTypeDescends` edges = the deleted walker's recursion arms
      (mapped-TP constraint / conditional-infer / fn-type / type-literal
      member staging comes from the batch-1 maintained levels). Self-emitting
      kinds: TypeReference (names + TS2314 + utility TS2344 + TS1099),
      IndexedAccessType, TypeQuery, FunctionType/ConstructorType (TS2842),
      TypeLiteral (member computed-name TS2690/TS2693/TS2464 in one batch at
      the literal's enter). The last recursion-owned expression region — a
      TL member's computed NAME — became an expression ROOT gated on
      `spineUResTypeChecked(typeLiteral)`, flipping `exprOwned` true there so
      the fn-sig dispatch covers what the retained walker's arms did.
      Verified: suite 10,804 → 10,832 (+28 Inv4SpineBatch19Test, ALL
      verified identical on the OLD walker via stash — pure
      reach-preserving; 0 regressions); listAll error lines IDENTICAL on
      ALL 8 profiles (528a vs 527a; header-only timing diffs); bench row
      recorded.
    - [x] **(c)(iv) checkTypeUsedAsValue.** DONE round 529 (2026-07-16): the
      recursive checkTypeAsValueInStatement(s)/checkTypeAsValueInExpr walkers
      + ScopeNameSet DELETED (~700 lines). Identifiers self-emit
      TS2693/TS2708 (+ the TS2585 forward-lib routing) at their enters, gated
      by `spineTavStatus` — a memoized 3-state ancestor-chain classifier over
      `spineTavEdge` (the deleted walker's exact dispatch arms, incl. the
      corpus-tuned NON-descent into for/while/do/switch/try bodies, class
      accessors/EXPRESSIONS, shorthand properties, and objlit-method param
      defaults; the plain-`=`-LHS TS2708 suppression is the REACHED_NONS
      status minted on the Equals-left edge — checkConstAssignment owns the
      assignment-target TS2708). The set chains stayed set-based as planned
      but became PULL-BASED memoized levels (`tavLevelAt`/`tavLevelFor` —
      the family's surveys are position-independent, so no batch-1-style
      lazy staging; the one order-sensitive spot, an objlit method's
      computed NAME seeing the OUTER scope, is a came-from-child owner
      skip). The file survey (TS18042 emission + currentForwardLibTypeNames
      included, verbatim) builds eagerly per file in checkSpine's loop
      (`tavBuildFileRoot`); TS2689 classifies at the CLASS enter and marks
      `spineTavHeritageSkip` before the heritage subtree walks (the deleted
      either/or: TS2689 OR the generic walk, never both). Suite
      10,832 → 10,872 (+40 Inv4SpineBatch20Test, ALL verified against the
      OLD walker first; 0 regressions); listAll error lines IDENTICAL on
      ALL 8 profiles (529a vs 528a); bench row recorded.
  - [x] **INV.4(d) Mid-weight stateful walkers.** COMPLETE round 541 (walkers
    1–13; the round-529 cost-ordered list is fully migrated — a fresh
    --passTiming table at round 542 shows the remaining non-giant tail is a
    flat sea of sub-160 ms mostly-stateless passes, none of them the
    scope-machinery shape this item targeted; they get absorbed
    opportunistically or superseded by (e)/(f)). Each walker moved its scope
    machinery onto the shared spine state; decompose per walker when reached.
    MEASURED cost order (round-529 --passTiming, post-(c): checker-init
    20.6 s; spine 2,247 ms carrying both name-resolution families + ~40 tail
    passes; giants unchanged 3.92/2.34/2.17 s):
    checkUncalledFunctionsInConditions 435 ms (38,986 getTypeOfExpression
    calls — a typing pass, not zero-typing), checkArithmeticOperandTypes
    309 ms (68,946 calls), checkImplicitAnyParameters 272 ms,
    checkDuplicateIdentifiers 260 ms (zero-typing), checkDefiniteAssignment
    241 ms, checkArgumentCounts 230 ms, checkUseBeforeDeclaration 205 ms,
    checkImplicitReturns 199 ms, checkConstAssignment 170 ms, then a long
    ~100–165 ms tail (checkAlwaysTruthy, checkNullUndefinedUsage, …).
    - (w1) DONE round 530 (2026-07-16): checkUncalledFunctionsInConditions
      (TS2774/TS2801) — the first (d)-class TYPING-pass migration; template
      extends (c)(iv): boolean reach classifier + PULL-BASED per-emission
      stack rebuild with per-owner memoized LAZY levels (functions with no
      conditions never pay the collection's typing calls), ambient state
      (currentFlowGraph/currentCheckFileName) save-set-restored around EACH
      dispatch never walk-wide. 36 pins (Inv4SpineBatch21Test) pre-verified
      on the OLD walker; suite 10,872 → 10,908; listAll error-line identical
      on ALL 8 profiles; ~270 walker lines deleted. See the round-530
      session note for the quirks pinned.
    - (w3) DONE round 532 (2026-07-16): checkImplicitAnyParameters
      (TS7005/TS7006/TS7008/TS7013/TS7019/TS7031/TS7032/TS7051) — the first
      DOWNWARD-CONTEXT-THREADING migration: the checkImplicitAnyInExpr
      recursion's five explicit context parameters (contextuallyTyped /
      contextualType / viaUnionWithPrimitive / ctxAnnotation / ctxViaAssignment)
      become ONE push-maintained SpineIanyCtx value with frames defined at
      EXACTLY the edges the legacy recursion passed arguments over (a missed
      edge silently LEAKS the parent context — every reached expression-position
      edge must define, even to null); the binary left-spine loop dissolves into
      per-edge rules (right operand by operator; left inherits for `||`/`??`
      only); returnCtxAnnotation + inAmbientContext pull-derive from parent
      chains; the three implicit-any scope stacks stay the same checker fields,
      pushed at body edges + recorded at declarator enters. No ambient install
      needed (slot-move A/B ×8 error-identical + corpus green pre-gated the
      move past the 4 sibling TS7xxx passes). 56 pins (Inv4SpineBatch23Test)
      ALL pre-verified on the OLD walker — incl. the reach quirks (while/do/
      switch/try/for-in/for-of bodies, call CALLEES, conditional CONDITIONS,
      as-casts, objlit accessors, static blocks all unreached) and the
      class-expression setter TS7032-with-sibling-getter bug-compat fire.
      The recursive walkers (checkImplicitAnyInStatements/-InClassElement(Core)/
      -InExpr) + the pass driver are DELETED (~770 lines); suite 10,948 →
      11,004; listAll error lines identical on ALL 8 profiles. See the
      round-532 session note.
    - (w2) DONE round 531 (2026-07-16): checkArithmeticOperandTypes — the
      first ORDER-DEPENDENT stateful migration (statement-ordered recordings
      that leak across blocks → PUSH-maintained frames on the spine, not the
      pull-based rebuild) and the first pass from AFTER the three giants
      (slot-move pre-gate found the currentParamBindingNames leak as the ONLY
      order coupling — kept pass-private now). Left-spine flatten = chain-root
      LEAVE emission; ambient install per emission/recording. The CORPUS caught
      a second, subtler coupling the profiles could not: the pass CONSUMED the
      TS2322 walk's namespace-level recording residue (qualify.ts) — reproduced
      as the pass's own ModuleBlock-gated identifier-init chain recording. 39
      pins (Inv4SpineBatch22Test); suite 10,908 → 10,948; listAll error-line
      identical on ALL 8 profiles; the pass driver deleted (the recursive
      walkers stay as checkComputedDestructKey's utility). See the round-531
      session note.
    - (w12+w13) DONE round 541 (2026-07-17): the ORDER-COUPLED pair
      checkCommaOperatorUnused (TS2695) + checkNullishPredicates (TS2871/
      TS2869 + while/do truthiness) migrated TOGETHER — the ordering
      contracts dissolve structurally (comma pre-order → ENTER anchors; np
      post-order → LEAVE anchors; while/do truthiness at the CONDITION's
      leave; same-position comma-first BY CONSTRUCTION since enters precede
      leaves — the legacy slot contract retired). Separate verbatim
      classifiers (their reach differs: objlit method bodies np-only;
      tagged-templates/yield/delete/typeof/comma-lists comma-only). 10 pins
      (Inv4SpineBatch32Test) pre-verified; suite 11,233 → 11,243; listAll
      ×8 identical; ~470 walker lines deleted. See the round-541 session
      note.
    - (w11) DONE round 540 (2026-07-17): checkNullUndefinedUsage (TS18050 +
      the for-of empty-[] TS2488 shape) — pure anchors, no ambient; the
      classifier carries the legacy checkDepth ≤ 200 STATEMENT-frame cap as
      a depth-encoded ShortArray status, with legacy frameless body Blocks
      as CARRIER blocks at the parent's depth. 12 pins (Inv4SpineBatch31Test)
      pre-verified; suite 11,221 → 11,233; listAll ×8 identical; ~230 walker
      lines deleted. See the round-540 session note.
    - (w10) DONE round 539 (2026-07-17): checkAlwaysTruthy (TS2872/TS2873 +
      TS1345/TS2845 + the `!`-operand falsy check) — frameless: both walk
      states pull-derive (the never-reset B69.11 inArrowExprBody flag; the
      if-else-chain prevTruthy via elseStatement ancestor links); per-chain-
      node dispatch at IfStatement enters. Condition-reach asymmetry pinned:
      if/while/do/ternary condition sub-exprs never walked, FOR conditions
      fully walked. 13 pins (Inv4SpineBatch30Test) pre-verified; suite
      11,208 → 11,221; listAll ×8 identical; ~230 walker lines + the
      threading field deleted. See the round-539 session note.
    - (w9) DONE round 538 (2026-07-17) — checkConstAssignment (TS2588/TS2628/TS2629/TS2630/TS2708 +
      TS2540 readonly writes + TS2357 inc/dec targets + scanRegExpFull's
      TS1538/regex-grammar family riding the same walker). SCOUTED
      (2026-07-17, in-code): the most stateful (d) walker yet — a w2+w5
      hybrid. (1) constNames is a statement-ordered LIVE MutableMap per
      activated list (collect const/class/enum/fn/ns THEN check, let/var
      REMOVES an inherited name) → DA-style core frames with per-statement
      collect steps at direct-child enters; spawn rules are ASYMMETRIC:
      Block/switch-clause/try-blocks/ModuleBlock/class-member bodies COPY
      the top frame's live map, FunctionDeclaration/fn-expr/arrow-Block/
      IIFE-arrow-Block bodies get a FRESH EMPTY map (an outer const is NOT
      flagged inside a fn body — bug-compat), SourceFile seeds from the
      program-wide sharedConsts overlay (script files only; module files
      empty). (2) The For header is an EDGE overlay: condition/incrementor/
      body see outer+header consts, the INIT EXPRESSION sees outer only.
      (3) currentClassForThis/currentThisMemberIsCtorDirect pull-derive from
      the ancestor chain: per-member staticness, Constructor→ctorDirect,
      property-initializer→ctorDirect=false, fn-expr NULLS the class, arrow
      keeps it with ctorDirect=false, and an IIFE-ARROW is TRANSPARENT to
      ctorDirect (the CallExpression arm's immediatelyInvokedArrowCallee).
      (4) FunctionDeclaration bodies install currentLocalTypes/
      currentParamBindingNames copies + populateParameterLocalTypes (B116 —
      fn DECLS only, not methods/fn-exprs/arrows) — cumulative through
      nested fn decls; per-anchor pull-rebuild with per-owner memo (w1
      template). (5) This is a TYPING pass (checkReadonlyAssignmentTarget
      resolves receiver types) — slot-move pre-gate with the CORPUS
      mandatory; check for diagnostics-list probes before choosing
      enter-vs-leave dispatch (the round-537 lesson). Anchors: assignment-op
      BinaryExpressions (left-spine loop — emissions are per-spine-node, at
      each binary's own reach), ++/-- Prefix/Postfix, RegularExpressionLiteralNode.
      LANDED as scouted (enter-dispatch — no diagnostics probes); 19 pins
      (Inv4SpineBatch29Test) pre-verified on the OLD walker; suite 11,189 →
      11,208; listAll ×8 identical; ~330 walker lines deleted. See the
      round-538 session note.
    - (w8) DONE round 537 (2026-07-17): checkImplicitReturns
      (TS7030/TS2355/TS2366/TS2378/TS7023 + arrow concise-body TS2322).
      SLOT-MOVE PRE-GATE LANDED AND VERIFIED (intact pass at the spine slot;
      corpus 11,170/0 + listAll ×8 error-line identical) — the ambient
      residue at the spine slot is proven equivalent, and the pass stays
      BEFORE checkTypeAssignability, whose end-of-pass filter suppresses
      TS7030 at its own TS2322 positions (it EXPECTS this pass's TS7030s to
      exist — do not move it past the giants). SCOUTED migration design
      (w1-template): 4-state reach classifier (STMT/EXPR/MEMBER/NONE) over
      walkStmtForImplicitReturns/walkExprForImplicitReturns arms; anchors at
      FunctionDeclaration/MethodDeclaration/GetAccessor/FunctionExpression/
      ArrowFunction enters (the retained check*ForImplicitReturn bodies
      minus their trailing walkForImplicitReturns recursion); per-dispatch
      ambient install of implicitReturnFlowGraph + currentCheckFileName +
      the PRE-SPINE resting currentFileLocals/currentFunctionParams
      (checkGetAccessorForImplicitReturn reads currentFunctionParams'
      RESTING value — it never sets it; capture both at checkSpine entry
      like spineArithBase). Per-file gate: !isDts && (checkJs || !(.js|.jsx))
      — NOTE .mjs/.cjs are NOT skipped by the legacy gate (spineIsJsLike is
      the wrong predicate). Sharp reach quirks to pin (verified in-code):
      GENERATOR bodies never descend (the anchors early-return before their
      trailing recursion); class-DECL Constructor/SetAccessor bodies and
      class-DECL PropertyDeclaration initializers unreached while class-EXPR
      prop inits ARE reached; objlit SetAccessor bodies unreached; arrow
      CONCISE (expression) bodies never descend (both annotated and not);
      return/throw/export= EXPRESSIONS and if/while conditions and for
      headers unreached in statement position; GetAccessor sentinel body
      (pos == -1) skips. LANDED: anchors dispatch at LEAVE (the 17.135
      TS2304/TS2314 diagnostics-list probes must see the annotation's own
      spine emissions — enter-dispatch over-emitted TS2355 on exactly 2
      corpus tests); 19 pins (Inv4SpineBatch28Test); suite 11,170 → 11,189;
      listAll ×8 identical; ~140 walker lines deleted. See the round-537
      session note.
    - (w7) DONE round 536 (2026-07-17): checkUseBeforeDeclaration (TS2448/
      TS2449/TS2450 + TS2454 co-emit + static-init TS2729) — 5-state reach
      classifier + per-list-owner memoized blockScopedDecls; the retained
      BOUNDED checkUBDForwardRefs walk anchors at DIRECT statements of
      activated lists (it recurses if/labeled itself — nested statements
      never re-anchor); loop-header self-ref checks re-host at For/ForIn/
      ForOf enters. TWO order couplings resolved by slot placement:
      populateAmbientCyclicBaseClasses (the TS2449 suppression-set producer)
      moved BEFORE the spine, and the TS2454 co-emits becoming visible to
      checkDefiniteAssignmentViaFlowGraph's dedup scan measured INERT
      (slot-move pre-gate: corpus green + listAll ×8 identical). Cross-file
      leg stays a separate pass at the spine slot. 33 pins
      (Inv4SpineBatch27Test) ALL pre-verified on the OLD walker first run;
      suite 11,137 → 11,170; listAll error-line identical on ALL 8 profiles;
      ~195 walker lines deleted. See the round-536 session note.
    - (w6) DONE round 535 (2026-07-17): checkArgumentCounts (TS2554/TS2555/
      TS2575) — the first DEPTH-valued reach classifier (the legacy
      argCountDepth recursion counter reproduced per edge, ≤200 cap; binary
      right-spine absorption = no depth) and the first MAP-valued pull-based
      downward context (funcParams/ctorParams/fnDepth/superCtor rebuilt at
      each emission from per-list-owner memoized levels — sound because every
      list overlay reads its WHOLE statement list). TRAP: a pull rebuild that
      RE-ENTERS itself through its own memoized levels must reuse its shared
      ascent buffer MARK-based, never clear()-based (the for-of loop-shadow
      edge silently dropped; one pin caught it). Producer sibling
      checkSpreadNonIterableIntoFixedArity moved BEFORE the spine. 46 pins
      (Inv4SpineBatch26Test) ALL pre-verified on the OLD walker; suite
      11,091 → 11,137; listAll error-line identical on ALL 8 profiles;
      ~650 walker lines + 3 threading fields deleted. See the round-535
      session note.
    - (w5) DONE round 534 (2026-07-16): checkDefiniteAssignment (the SET-based
      TS2454 pass) — the first per-statement-LIST ordered walker with a
      DOWNWARD leak context: legacy list activations become CORE FRAMES
      (pushed at SourceFile/fn-body/Block/ModuleBlock owners, per-statement
      steps at direct-child enters — the collect/checkUses/mark/nestedLeak
      loop body retained verbatim), the recursion walkers become a memoized
      10-state ancestor classifier (spineDaStatus/spineDaEdge), and the
      downward leak set is READ from the top frame's per-statement
      currentLeak via LEAK-flavored statuses (sound: leak-preserving paths
      never cross a core spawn). The flow-graph siblings (ViaFlowGraph
      dedups one-directionally against this pass) moved to right after the
      spine, preserving set-pass-first order; slot-move pre-gate ×8
      identical. 39 pins (Inv4SpineBatch25Test) pre-verified on the OLD
      walker; suite 11,052 → 11,091; listAll error-line identical on ALL 8
      profiles; ~370 walker lines deleted. See the round-534 session note.
    - (w4) DONE round 533 (2026-07-16): checkDuplicateIdentifiers (TS2300
      family) — the lightest (d) shape: STATELESS (the two
      checkDuplicateDeclarations flags derive at the anchor) and ZERO-TYPING,
      so the migration is a pure boolean reach classifier
      ([spineDupIdReached] over [spineDupIdEdge], the deleted
      checkDuplicatesInStatement(s)/InExpr/InClassElement arms verbatim) +
      anchor dispatch at node enters running the RETAINED bounded leaf
      utilities; class/objlit MEMBER emissions dispatch uniformly at the
      member's own enter (objlit edges never admit accessors, so a reached
      SetAccessor/Constructor is class-only). Per-file top-level scans ride
      checkSpine's loop in the legacy within-file order, each wrapped in a
      currentFileLocals=null install (the legacy pass ran with it null —
      checkClassNamespacePrototypeConflict's `?: globals` consult makes it
      load-bearing). Slot-move pre-gate: error-line-identical ×8 (no residue
      coupling). 48 pins (Inv4SpineBatch24Test) ALL pre-verified on the OLD
      walker first run; suite 11,004 → 11,052; listAll error-line identical
      on ALL 8 profiles; ~215 walker lines deleted. See the round-533
      session note.
  - [x] **INV.4(e) The top-3 giants — COMPLETE round 592** (cta 586 / cpa 585 / ccet 592 all retired; checkbox reconciled round 612). checkPropertyAccess (3.66 s @ round-542
    table) → checkTypeAssignability (2.62 s) → checkCallExpressionTypes
    (2.13 s) — one at a time (together ~38% of checker-init; 458k of 595k
    getTypeOfExpression calls). **g1 SUB-PLAN (scouted round 542, in-code):
    checkPropertyAccess's walker core is compact (checkPropertyAccessInStatement
    293 lines / 22 arms + checkPropertyAccessInExpr 414 lines / 26 arms —
    the mass is in the called emission machinery, retained as leaf
    utilities). State model per the (d) templates: (1) statement-ordered
    currentLocalTypes recordings (w2 arith shape — PUSH-maintained frames,
    PASS-PRIVATE on the spine per the w2 currentParamBindingNames lesson;
    the pass also does applyBodyLocalShadowing at fn-decl/arrow/fn-expr
    boundaries per the round-447 gotcha — those calls stay in the frame
    installs); (2) contextualType downward threading with clear-before-body
    edges (w3 iany shape — push ctx with frames at exactly the legacy
    assignment edges); (3) enclosingClassType threaded param + inStaticClassMethod
    (pull-derivable from the member chain); (4) propertyAccessEnclosingNamespaces
    (its OWN stack, deliberately separate from inferenceNamespaceStack per
    the two-stacks gotcha — push at ModuleDeclaration edges); (5) per-file
    ambient currentFileLocals/currentCheckFileName/currentFlowGraph/
    currentLexicalScopes (per-dispatch install, w1 discipline — NOTE
    currentFlowGraph walk-wide is the 78-test hazard, so install around
    emissions only). SUB-STEPS, one commit each: (g1a) slot-move pre-gate —
    move the intact pass from its slot to the spine slot; this REORDERS it
    before the other two giants, so expect residue coupling (the w2
    corpus-only lesson): listAll ×8 + FULL corpus mandatory; if the
    pre-gate diffs, bisect the coupling with restore-after-pass probes
    before any migration. (g1b) pins (~50, the largest batch yet — reach
    quirks per arm; pre-verify on OLD). (g1c) the migration. (g1d) after
    g1 lands, re-measure; g2/g3 decompose the same way when reached.**
    **g1a MEASURED (round 542, both experiment directions run and REVERTED —
    the working tree keeps the legacy giant order): the giants are
    order-entangled in BOTH directions, and the couplings are CORPUS-ONLY
    (all 8 profiles sorted-error-line-identical in both experiments).
    (1) checkPropertyAccess moved before checkTypeAssignability →
    noImplicitAnyForIn loses a TS7053: the element-access receiver's type
    (`var k1 = x[i]` → `{}`) comes from the assignability walk's
    currentLocalTypes RESIDUE — the w2 residue class; fix = the pass records
    its own receiver types (w2's own-recording template).
    (2) checkTypeAssignability moved to the spine slot →
    typeArgumentDefaultUsesConstraintOnCircularDefault's TS2353 display
    flips `Test<any>` → `Test` (aliasDisplayMap/declaredTypes first-touch)
    AND relationComplexityError gains 2 FP TS2322 (relation-cache/
    complexity-budget state) — CACHE first-touch couplings against the small
    passes between the spine and slot 64, each needing a root-cause before
    the giant can move. NEXT STEP for g1: bisect WHICH intermediate pass's
    first-touch the two failures depend on (binary-search the slot
    position), then either neutralize the dependency (pass-own state /
    explicit cache warm) or migrate the giant IN PLACE (dispatch from the
    spine but buffer emissions to the legacy slot — a new template).**
    **g1a BISECT COMPLETE (round 543) — STRATEGIC FINDING, the (e) tier is
    BLOCKED ON INV.5: three targeted probes pinned both g1a' couplings to
    exactly TWO small producer passes (checkTypeParameterDefaults — its
    first-touch of the circular-default alias caches the `Test<any>`
    display; checkTemplateUnionIntersectionComplexity — its TS2859
    complexity verdicts make the giant's relation SKIP the failing
    comparison), but applying the established producer-move pattern (both
    before the spine + the giant at the spine slot) dragged a coupling
    CHAIN: 5 NEW generic-family corpus failures
    (genericsWithoutTypeParameters1, genericRecursiveImplicitConstructor-
    Errors3, noTypeArgumentOnReturnType1, conflictingTypeParameterSymbol-
    Transfer, returnTypeTypeArguments) + a harness listAll diff — the moved
    producers have their OWN upstream first-touch dependencies. Buffered
    emission does not help either: the COMPUTATION (type resolution into
    shared caches) is what is order-sensitive, not the emission. CONCLUSION:
    the giants cannot migrate by slot manipulation while nodeTypes/
    declaredTypes/aliasDisplayMap/relation caches are first-touch-order-
    sensitive. The (e) tier's prerequisite is INV.5's cache re-keying
    (`nodeTypes` keyed (node, mapper) — always valid; canonical type
    identity), which makes resolution order-INSENSITIVE. RE-SEQUENCED:
    work INV.5 next; return to (e) when the caches are order-free. All
    probe edits REVERTED — the tree keeps the legacy giant order.**
    **SUPERSEDED (rounds 555/556): the 542/543 conclusions above are STALE —
    the probe/slot-move scripts matched a COMMENT containing
    `pass("checkSpine")` and inserted the giant ~100 passes early (see the
    round-555 CLAUDE.md gotcha), so the "coupling chain" / "blocked on
    INV.5" findings were position artifacts (possibly compounded — the
    INV.5 (a)/(c)/(d1)/(e) landings since may also have genuinely
    order-freed some caches). At the CORRECT position, with exactly the two
    round-543 producers hoisted (landed round 555), ALL THREE giants
    slot-moved to the spine block corpus-green + listAll-×8-identical
    (landed round 556; legacy relative order g-cta → g-cpa → g-ccet
    preserved). g1a/slot-move pre-gates: DONE for all three. (g1b) DONE
    rounds 557/558 — 33 reach pins (Inv4SpineG1PinsTest statement arms,
    Inv4SpineG1PinsExprTest expression arms), all verified on the current
    walker.**
    **(g1c) DESIGN (round 559, from the g1b arm reads): the migration ORDER
    must be cta FIRST — the giants share a CROSS-PASS residue channel:
    checkPropertyAccess's driver does NOT reset currentLocalTypes per file,
    so it consumes checkTypeAssignability's recordings (round 542's
    noImplicitAnyForIn TS7053 finding: the `var k1 = x[i]` receiver type is
    cta residue). Migrating cpa into the spine FIRST would run its per-node
    work BEFORE the still-slot-resident cta → the residue disappears.
    Migrating cta first preserves cta-before-cpa; note per-node
    interleaving ≠ pass-after-pass for BACKWARD residue reads (a node
    consuming a LATER node's recording) — the pass-after-pass semantics let
    cpa see cta's COMPLETE final state incl. later files; audit any
    backward consumption during the cta migration (candidate remedy: the
    w2 own-recording template — each pass records what it consumes).
    Frame model per the INV.4(d) playbook: (1) per-dispatch ambient install
    of currentFlowGraph/currentLexicalScopes (NEVER walk-wide on the spine
    — the 78-test hazard; the legacy walk-wide set is reproduced by
    installing around every g1 emission); (2) fn-like scope copies
    (fn-decl/method/ctor/set-accessor/arrow/fn-expr) as push-frames at
    body enters (save map refs, install copies + populateParameterLocalTypes
    + applyBodyLocalShadowing/applyAmbiguousBlockScopedLocals), popped at
    leaves — GetAccessor bodies deliberately have NO scope copy (chunk-1
    pin); (3) contextualType as a kinded downward carrier at call-arg /
    objlit-property / arrow-body edges (the w3 template; cleared at
    fn-expr body and spread edges); (4) propertyAccessEnclosingNamespaces
    pushed at non-declare ModuleDeclaration enters; (5) enclosingClassType
    as a pull-derived member-chain context (null across fn-decl/fn-expr
    boundaries, KEPT through arrows — chunk-2 pins), with the this-param
    override at method enters; (6) inStaticClassMethod save/set/restore at
    class-member enters; (7) currentEnclosingEnum at EnumDeclaration
    enters; (8) reach quirks as classifier edges: for-INIT unreached,
    tagged-template spans unreached, interface bodies unreached,
    shorthand-property initializers unreached.**
    **(g2 = cpa DECOMPOSITION, queued round 576 — the cta migration (rounds
    560–576, m1..m3m) is COMPLETE for the emission surface; work these
    top-to-bottom, one commit each, mirroring the proven cta sequence):**
    - [x] **(cpa-m1) Legacy-side audit instrumentation** — DONE round 577. (the cta-m2a
      pattern): a test-only `cpaAuditRecord` at the top of
      checkPropertyAccessInStatement fingerprinting the threaded+ambient
      context per DIRECT statement — enclosingClassType (threaded param),
      currentLocalTypes/currentParamBindingNames/currentEnumConstrainedParams/
      currentShadowedNames (fn-boundary copies), inStaticClassMethod,
      propertyAccessEnclosingNamespaces depth, contextualType. FINGERPRINT
      HAZARD (scouted): cpa's currentLocalTypes maps name→Type, not strings
      like cta's varTypes — Type.id is resolution-order-sensitive between
      legacy-time and spine-time, so fingerprint by sorted name set +
      per-name typeToString (test-only cost), never by id.
    - [x] **(cpa-m2-prep) Close the residue channel legacy-side** — DONE
      round 578: per-file `currentLocalTypes` reset in the cpa driver + the
      element-access own-recording; corpus green + listAll ×8 byte-identical.
    - [x] **(cpa-m2) Spine-side frame skeleton** — COMPLETE round 580 (tier 2:
      unified edge-reach walker, arrow/fn-expr/ClassExpression frames,
      cpaCtxAt/cpaEctAt; full bidirectional audit equality).
      tier 1 (statements) DONE round 579 ((cpa-m2a): fn-decl/method/ctor/
      accessor frames, ns frames, loop-var overrides, per-decl-leave
      recordings, the immediate-position fingerprint gate); REMAINING
      (cpa-m2b): tier 2 — DESIGN COMPLETE (scouted round 579b, in-code):
      (i) arrow Block-body frames: 3-map copy + populate + shadowing +
      ambiguous + contextual param registration from ctx-at-arrow;
      ect/inStatic PRESERVED through arrows; (ii) fn-expr body frames:
      3-map copy + the fn-expr's OWN param semantics (annotated -> set,
      UN-annotated -> REMOVE from localTypes — not populate!) +
      destructured-name collection + contextual registration + shadowing +
      ambiguous; body walks with ect = NULL; (iii) ClassExpression member
      bodies: the tier-1 class-member frames extended to ClassExpression
      owners with a per-visit synthetic anon-class type (display
      "(Anonymous class)" — fingerprint-equal across fresh synthetics);
      (iv) ctx PULL-derivation cpaCtxAt(node): STOP-null at any statement
      edge; DEFINE at call-arg (the argCtxTypes computation: single-sig +
      B86.1b inference mapper + literal mapper; multi-sig strictSelect /
      every-overload-callable), objlit PropertyAssignment initializer
      (propCtx from ctx(O).members, non-any/error else null), SpreadAssignment
      (null), arrow EXPRESSION body (bodyCtx = single-sig return); INHERIT
      through paren/conditional/binary/array-literal/template-span/as/
      nonnull/prefix/postfix/await/spread AND NewExpression args (a legacy
      quirk: new's args inherit the OUTER ctx — no clearing); ctx is
      provably NULL at every statement dispatch (arrow Block bodies get
      bodyCtx=null; fn-exprs null explicitly); (v) the tier-2 chain test
      needs an expression-edge REACH classifier (the spineUResExprEdge
      pattern) — legacy expr-walk quirks: TaggedTemplate walks the TAG only
      (spans unreached), ForStatement INITIALIZER unreached (condition +
      incrementor reached), ForIn/ForOf initializer AND iterable expression
      unreached (ForOf's getTypeOfExpression is not a walk), decorators
      unreached, objlit METHOD bodies unreached (else -> {}),
      ShorthandPropertyAssignment unreached, CommaList unreached,
      arrow/fn-expr PARAM DEFAULTS unreached; statement-edge expression
      roots: Var initializers / ExprStmt / Return / If condition / While-Do
      condition / Switch subject + case exprs / Throw / With /
      ExportAssignment / Enum member inits / Class heritage + members.
      (the cta-m2b/m2c pattern — expect quirk-extraction cycles; the known
      quirks from the g1c design: GetAccessor bodies have NO scope copy,
      enclosingClassType is KEPT through arrows / nulled at fn-decl+fn-expr
      boundaries, contextualType clears before bodies, the pass is
      PASS-PRIVATE for currentParamBindingNames per the w2 lesson, and the
      driver does NOT reset currentLocalTypes per file — cpa consumes cta
      RESIDUE cross-file (round-542 noImplicitAnyForIn TS7053), which the
      frames must reproduce or own-record).
    - [x] **(cpa-m3…) Emission moves** — COMPLETE rounds 581-583; **(cpa-retire)
      LANDED round 585: the checkPropertyAccess legacy pass is DELETED** (the
      first giant off emit-twice; audit scaffolding removed with it).
    - [x] **(cta-retire) LANDED round 586: the checkTypeAssignability legacy
      pass is DELETED** (both migrated giants off emit-twice; audit
      scaffolding removed).
    **(g3 = ccet DECOMPOSITION, queued round 588 from the in-code scout —
    the LAST giant; mirror the twice-proven cpa sequence, one commit each):**
    - [x] **(ccet-m1) State-model scout — COMPLETE round 588b.** Additional
      facts: the expr walker has NO contextualType channel (plain recursion);
      arrow/fn-expr arms copy 2 maps (localTypes+paramBindings) + register
      own params anyType + Block-body shadowing; the ObjectLiteral arm does
      a SCOPED localTypes copy around member walks; EMISSIONS ARE
      PER-CALL-NODE (checkSingleCallExpressionTypes at CallExpressions,
      checkSingleNewExpressionTypes at NewExpressions) — so the m3 anchor is
      per-Call/New-node at ITS OWN LEAVE (the probe discipline), with frames
      supplying ambient; no emit-via-containing-walk ownership complication
      (nested-fn-body calls anchor at their own nodes under spine-maintained
      frames). DECISION: pins-first — NO fingerprint audit (CcetAnchorTest
      exactly-once pins + corpus/listAll gates; the audit pattern's quirk
      extraction is replaced by the gates, which caught all three cpa-m3a
      quirks anyway).
      ORIGINAL ITEM: **(ccet-m1) State-model scout completion + audit-or-pins decision.**
      Scouted so far (in-code, round 588): the driver resets currentLocalTypes
      per file since round 584 (residue-free); FunctionDeclaration arm copies
      currentLocalTypes + currentParamBindingNames AND pushes the fn's OWN
      TPs onto currentTypeParamScope (constraint materialization included),
      then populateParameterLocalTypes + applyCallTypesBodyLocalShadowing +
      shadowNestedFunctionNames (the M1.11 ecology — presence-only consults,
      the first-touch cache-poisoning hazard is documented in the helpers);
      ClassDeclaration arm pushes class TPs + resolves the class symbol via
      globals ?: inferenceNamespaceStack.last().exports; ModuleDeclaration
      pushes inferenceNamespaceStack via resolveModuleDeclNamespaceSymbol
      (DOTTED namespaces handled — unlike cpa's arm); the IfStatement arm
      does a SCOPED single-name union-narrowing override (save/write/restore
      around the then-walk); the VariableStatement arm ORDER-RECORDS
      annotated-callable + B98.r126 + callable-shadow entries. REMAINING to
      scout: the expr walker's arms (contextual channels?), the class-member
      dispatch, funcParams/currentFunctionParams overlay production, and
      currentEnclosingEnum/classForThis usage. DECISION POINT: rounds
      585/586 showed the audits end as deleted scaffolding — consider going
      pins-first (CcetAnchorTest exactly-once) + frame-skeleton-with-
      corpus-gates instead of the full fingerprint audit; the audit earned
      its keep on cta/cpa quirk EXTRACTION, so keep it only if the frame
      skeleton's first corpus gates diff untraceably.
    - [x] **(ccet-m2) LANDED round 589 — box checked round 671 after verifying
      in code** (`ccetSpineEnter` / `ccetSpineFileReset` are called
      unconditionally from spineEnterNode and the per-file loop, so the frames
      are always-on; its dependent (ccet-m3) landed round 591 and
      (ccet-retire) round 592, which could not have happened otherwise). The
      two in-code "inert until the anchors land" comments were stale and are
      corrected. Spec retained below for reference. FULL SPEC (round 588c
      in-code read of every arm):** CcetFrame fields: localTypes(HashMap) +
      paramBindings(HashSet) [copied at fn-decl/method/ctor/contextual-fn
      boundaries + arrow/fn-expr expr-arms], tpScope+tpAst [fn-decl pushes
      OWN TPs with interning + constraint materialization; class arm pushes
      the DECLARED class type's TPs resolved via
      globals ?: inferenceNamespaceStack.last().exports; STATIC methods POP
      the class scope but mint FRESH TPs for their own typeParameters],
      superBaseSig/superBaseType [ctor gets both, method gets Type only —
      from the per-class baseResolution computed under the class TP scope],
      nsSymbol [ModuleDeclaration arm, NON-declare only, dotted-aware via
      resolveModuleDeclNamespaceSymbol], classSym [callWalkerClassStack
      push], the method-body `this` registration [instance methods:
      currentLocalTypes["this"] = getDeclaredTypeOfSymbol(classSym)],
      GetAccessor/SetAccessor bodies walk with NO copies. Var-arm ORDERED
      recordings (interleaved with initializer walks — the cta interleave
      lesson): callable-annotated + union-of-callables + literal-union +
      callable-shadow anyType; the B246 CONTEXTUAL fn-expr channel
      (FunctionType-annotated var + fn-expr/arrow init → params typed from
      the annotation with ?-undefined unions — a frame VARIANT, replaces
      the plain initializer walk); the If-arm SCOPED type-guard narrowing
      override (resolveUserTypeGuardNarrowing at the If enter, save/write/
      restore around the then — the cta-m3i narrowing-frame precedent);
      ForIn/ForOf withForLoopVarShadow around bodies. REACH QUIRKS (differ
      from BOTH prior giants): For-INITIALIZER expressions ARE walked
      (decl initializers + expression form); param DEFAULT initializers ARE
      walked at fn-decl/method/ctor arms (BEFORE the body frame — under the
      OUTER ambient); DoStatement walks body BEFORE condition;
      declare-module bodies are SKIPPED entirely (Declare gate — unlike
      cpa); DOTTED namespace bodies are RECURSED (unlike cpa);
      heritage expressions walk UNDER the class TP scope + class stack;
      objlit arm does a scoped localTypes copy. There is also a
      maxCheckDepth recursion guard (callTypeCheckDepth) at the statement
      dispatcher — reproduce as an int-valued reach cap if fidelity
      requires (the round-535 spineArgDepth precedent). LAST FACTS (588d):
      withForLoopVarShadow copies BOTH maps but ONLY when a loop-header
      binding name COLLIDES (in globals or currentLocalTypes, not already
      in paramBindings) — colliding names are REMOVED from localTypes +
      added to paramBindings; no collision → NO copy (share). Declare-module
      subtrees need a frame `dead` flag (anchors skip; children inherit).
      The If-arm narrowing + ForIn/ForOf shadows reproduce as scoped
      override frames with restore records at the body node's leave (the
      cpa loop-var-restore mechanism). ARROW/FN-EXPR frames push at the FN
      node's enter (the copies wrap BOTH body kinds — expression-body calls
      see the registered params too). Class frames push at ClassDeclaration
      enters (tpScope + classSym + the baseResolution pair computed under
      the class scope), maps SHARED; member-body frames derive from them.
      Implementation staging: (ccet-m2) frames always-on, gates must stay
      IDENTICAL (no emissions move yet — any diff is a first-touch
      coupling to bisect); then (ccet-m3) per-call anchors + marks + pins.
    - [x] **(ccet-m3) LANDED round 591** (merged; the gap-signature gate made
      the interleave FP order-free) + **(ccet-retire) LANDED round 592 — ALL
      THREE GIANTS OFF EMIT-TWICE.** (history: round 590 blocked state:) per-Call/New/TaggedTemplate anchors at
      leaves + the full per-edge reach classifier + legacy marks +
      CcetAnchorTest (8/8, incl. the static class-TP skip-gate pin) + the
      re-enabled decl recordings (the round-589 flip is MOOT under anchors:
      the legacy verdict truncates). Corpus GREEN (11,347/0). BLOCKER: ONE
      interleave FP — the cta return anchor at services.ts:1327 (the
      objectAllocator objlit vs ObjectAllocator) sees CCET-WARMED caches
      (per-node interleaving ≠ pass-after-pass, the round-559 warning) and
      resolves TP-carrying member types (`() => NodeObject<TKind>`) → a
      TS2322 the legacy order never produced (services/server/harness +1).
      A typeContainsForeignTypeParam construct-sig extension did NOT
      suppress (on the branch; possibly resolvedReturnType null at gate
      time, or a non-gate emitter). NEXT WINDOW: (1) identify the emitter
      with the round-472 Diagnostic-init probe keyed (2322, the 1327 start
      offset) on the services profile; (2) fix the gate's REACH or gate
      that emitter (order-free-verdict discipline, both cache states
      silent); (3) structural fallback: defer ccet anchors to a per-file
      second walk. Then merge the branch + gates + (ccet-retire).
      ORIGINAL: **(ccet-m3…) Emission moves** with the leave-dispatch discipline
      (cpa's probe lesson: anchor at statement/expression LEAVES) + the
      recorded-set truncation, then **(ccet-retire)** via the round-585
      experiment template (no-op the dispatch → gates → delete).
  - [x] **INV.4(f) CLOSED round 599 — both wins are measured dead-ends at
    the current cost structure** (f1 memo: the servable calls are cheap;
    f2 fold: confirm-once tax + epoch churn → noise); the real INV.4 win
    was the retirements (−13% wall) + ONE authoritative walk. Revive the
    memo designs after INV.5's canonical types. ORIGINAL: **The two unlocked soundness wins.** Once one authoritative
    walk state exists: the per-node expression-type cache (594,779 calls over
    ~221,844 distinct nodes = ×2.6 recompute), and flow narrowing folded into
    reference typing once (84,469 depth-0 walks, 68% from property access).
    Re-measure against the ≤10 s single-threaded compiler-profile target.
- [x] **INV.5 Canonical types + explicit instantiation — SUBSTANCE COMPLETE round 604** (interning (a), mapper flip (b2), context-keyed nodeTypes (c), budget (d1), generic gate + pin sweep (e) all landed; residuals are deferred/demoted/blocked: (bN) behind the frame redesign, (c2) cosmetic, (d2) hygiene — checkbox reconciled round 612) (absorbs M5.2/M5.3;
  NOW THE ACTIVE ARC ITEM — the round-543 g1a bisect proved the INV.4(e)
  giants are blocked on exactly this: first-touch-order-sensitive shared
  caches). Decomposed round 544, one commit each, every step suite +
  listAll-×8 gated:
  - [x] **INV.5(a) Union/intersection interning.** DONE round 545 (see the session note — landed with the ternaryOfArrayLiterals gate extension after the round-544 near-miss). `getUnionType` (Checker.kt
    ~103k, "mints a fresh Type.Union(sorted) with a new id — does NOT
    intern") + `getIntersectionType` intern by sorted member-id key (the
    `referenceCache` pattern; preserves display member order by keeping the
    FIRST-built instance). Directly serves order-insensitivity: an interned
    union has the same id regardless of which pass builds it first. KNOWN
    HAZARDS (from the gotcha corpus): (1) aliasDisplayMap is id-keyed — an
    interned union SHARED across contexts must not receive one context's
    alias name (the singleton-intrinsic display-corruption hazard
    generalized; union alias display already has the structural
    `unionAliasStructural` map — union registrations in aliasDisplayMap may
    need to move there entirely); (2) the id-only dedup gotcha (duplicate
    structurally-identical members) is UNCHANGED by interning — do not
    conflate the two; (3) the round-424 structural wash-gate workaround
    stays correct (it stops RELYING on fresh ids but never assumed them);
    (4) relation-cache/cycle-stack behavior only gains hits (same-id
    identical pairs). Verify: suite + listAll ×8 + re-run the round-542/543
    probe experiments to measure how much of the giant entanglement
    dissolves.
    **FIRST ATTEMPT (round 544, REVERTED): a minimal interning of both
    canonical constructors (CheckerState caches by member-id key; unions by
    sorted order, intersections in-order) measured CORPUS 100% GREEN
    (11,243/0) with EXACTLY ONE new FP, identical on all 8 profiles —
    watch.ts:533:19 TS2322 `(string | DiagnosticMessage)[]` ⊄
    `DiagnosticAndArguments` (the round-446 VARIADIC-TUPLE alias family).
    Remarkably contained for a change canonicalizing every union in the
    program — the hazard list's display fears did NOT materialize; the one
    regression is a relation/suppression path keyed on union identity
    (candidates: a relation-cache FALSE shared across contexts, an id-keyed
    side channel hitting a shared instance, or the
    arrayLiteralSatisfiesTupleTarget suppression's engine fallback). NEXT:
    root-cause with a targeted probe (temporary Diagnostic-init stack-trace
    probe keyed on code=2322 + the watch.ts:533 start per the round-472
    recipe), fix the one path, re-land.**
    **PROBE RE-RUN (round 546, post-(a)): the g1a' couplings PERSIST under
    canonical union identity (both typeArgumentDefaultUsesConstraintOn-
    CircularDefault and relationComplexityError still fail with the giant at
    the spine slot; probe reverted). The residual first-touch sensitivity is
    NOT union-identity — it lives in declaredTypes/aliasDisplayMap
    resolution TIMING (the Test<any> display) and the relation/complexity
    verdict state — i.e. exactly the (b)/(c) territory (explicit mappers +
    keyed nodeTypes). The INV.5 sequencing holds; continue with (b).**
    **PROBE RE-RUN 2 (round 548b, post-(c)): both g1a' couplings STILL
    persist — the residual first-touch state is specifically (1)
    `declaredTypes` (SYMBOL-keyed alias resolutions — the Test<any>
    display; a different cache from nodeTypes) and (2) the TS2859
    relation/complexity verdict state. The giant unblock therefore needs a
    declaredTypes context-keying sibling of (c) plus a
    complexity-verdict-state audit — queue them as (c2)/(c3) when
    returning to the giants; the two probe tests
    (typeArgumentDefaultUsesConstraintOnCircularDefault,
    relationComplexityError) are the standing acceptance gate for any such
    step. Probe reverted.**
    **(c2) SCOUTED (round 549): the Test<any> coupling is a
    LAZY-MATERIALIZATION first-touch, not a cache-keying one —
    `Type.TypeParam.constraint`/`.default` are MUTABLE fields set at 8+
    scattered sites by whichever pass resolves the TP first (the
    typeParamInternCache shares the instance program-wide), so a no-args
    generic reference instantiates with defaults ONLY IF some earlier pass
    already materialized `.default`. DESIGN: EAGER TP materialization — one
    fixed init step (after globals merge, before any check pass) resolving
    every TypeParameter's constraint/default under its declaration's
    sibling-TP scope (the checkTpListDefaults scope-building pattern),
    making the fields order-free; the 8 lazy setters become no-ops
    (already-set guards) and eventually delete. Acceptance: the two probe
    tests + full gates.**
    **(c2) HYPOTHESIS FALSIFIED (round 549b, attempt REVERTED): a minimal
    eager top-level TP materialization (constraint+default fields filled at
    a fixed init point) did NOT dissolve the probe failure — the coupling's
    mechanism is the EFFECTIVE-default-via-constraint computation inside
    reference instantiation (the probe test's own name:
    typeArgumentDefaultUsesConstraintOnCircularDefault — tsc substitutes
    the CONSTRAINT when the default is circular), i.e. resolution-path
    state beyond the raw fields. Next root-cause step: instrument WHAT
    the legacy checkTpListDefaults slot changes that the later TS2353
    display consumes (candidate: the referenceCache entry for Test<any>
    minted during its constraint-relation checks, which the annotation
    resolution then reuses vs mints bare). Deferred behind (b2+)/other
    INV.5 work — the display-only coupling is cosmetic, not semantic.**
  - [x] **INV.5(b) Explicit mapper objects — installer flip COMPLETE round
    604 (b2a-b2d4): 87 write sites → 4; the survivors are the spine frame
    LIFO writers (restore-at-leave — not region-formable; the designed
    residual until frames carry mappers). (bN) ambient-field REMOVAL
    stays open behind that frame redesign.** Replace the ambient
    `currentTypeAliasArgs`/`currentTypeParamScope` instantiation contexts
    with an explicit mapper threaded through the resolution entry points —
    the enabler for (c). MEASURED SURFACE (round 546): 87 write sites in 34
    functions (top installers: checkCallTypesInStatement ×7,
    walkStmtsForTypeParamCasts ×6, checkReturnAssignability /
    resolveGenericPropertyTypeWorker / getTypeFromTypeReference /
    resolveInterfaceMembersCore / checkConstraintsInStatements ×4 each) +
    ~90 read sites inside the resolution family. DECOMPOSITION (bridge
    pattern — each step suite + listAll-×8 gated): (b1) a `TypeMapper`
    value (aliasArgs + tpScope + a stable fingerprint for cache keying) +
    an optional `mapper` param on `getTypeFromTypeNode`/
    `getTypeFromTypeReference` DEFAULTING to the ambient (behavior-
    identical bridge; the `cacheable` gate reads the param); (b2+) flip
    installer families to pass explicitly — (b2a) DONE round 549c: all 6
    simple aliasArgs installers flipped via aliasMapper/layeredAliasMapper
    (b2b) DONE round 549d: the remaining 3
    aliasArgs installers flipped too — alias substitution ~93.8k,
    constraint-retry ~89.6k, mapped-type per-key ~140.4k; the aliasArgs
    ambient is now single-writer (the bridge); tpScope families next);
    (b2c/b2c'-''', rounds 550a-550d) DONE: ALL resolution-internal tpScope
    installers flipped to the REGION form (`withInstantiationContext(
    scopeMapper(...)) { ... }` — inline, non-local returns preserved):
    resolveGenericPropertyTypeWorker (outer + inner method scope),
    resolveBaseTypesLazy, resolveInterfaceMembersCore (sig + index), the
    getTypeOf* lazies, buildBaseConstructorSignatureForSuper,
    buildSignatureForFunctionLikeTypeNode, reresolveSigParamsUnderClassScope,
    getTypeFromTypeLiteral's method branch, checkConstraintsForTypeArgs.
    REMAINING (deliberately deferred): the walker-level installers (die
    with INV.4(e)), the dual-ambient-field installers
    (checkConstraintsInStatements + currentTypeParamDecls;
    checkMixinClassInStatements + mixinValueScope), the 84067 interleaved
    implicit-any site, and the paired pushFunctionTypeParamsScope; (bN)
    remove the ambient fields (blocked on those). NOTE (c) only needs the mapper AT THE CACHE CONSULT — it can
    start right after (b1) with ambient-bridged installers still in place
    (key = (nodeId, mapper.fingerprint); the context-bypass `cacheable`
    rule dies there).
  - [x] **INV.5(c) `nodeTypes` keyed (node, mapper) — LANDED round 548
    (option iii — the conservative pinned-checking-file gate; see the
    session note; widen the gate as INV.3(d) retires checking-file-dependent
    resolution, and cache the fingerprint per-install if the +5.4%
    single-run wall cost proves real).** Kills
    the context-bypass rule and the first-touch hazard class outright (the
    round-543 blocker). DESIGN (scouted round 547b — the surface is TINY,
    exactly 2 use sites inside getTypeFromTypeNode): a SECOND cache
    (`mappedNodeTypes`) for context-bearing resolutions keyed by an
    IDENTITY node key (=== equality with nodeId-based hashCode — cross-file
    nodeId collisions only share buckets, never results; unindexed nodes
    skip) + a context fingerprint (ns-stack symbol ids + sorted tpScope
    name:id pairs + sorted aliasArgs name:id pairs). The existing
    empty-context cache and its isPerFileDependentRefNode bypass stay
    untouched (identity keys make that hazard structurally impossible in
    the NEW cache). **SOUNDNESS CONSTRAINT (the reason this is not yet
    implemented): context-bearing resolutions ALSO depend on the CHECKING
    file — `currentFileLocals?.get ?: globals` consults are
    checking-file-keyed (the conflation ecology), so a fingerprint that
    excludes that dimension re-creates the first-touch disease inside the
    cache. Either (i) include a reliable checking-file identity in the
    fingerprint (currentCheckFileName is a stale-prone proxy — audit the
    setters first), or (ii) wait for INV.3(d)'s completion to eliminate
    checking-file-dependent resolution, or (iii) start with a
    CONSERVATIVE fingerprint that additionally requires
    currentFileLocals === the node's owning file's locals (node-keyed
    consult, cheap via owningSourceFile with a per-file memo) and skips
    caching otherwise.** Option (iii) is self-validating and incremental —
    preferred.
  - [x] **INV.5(d) — (d1) budget DONE round 552; (d2) DEMOTED to hygiene round 611 (checkbox reconciled round 612).**
    **(d2) DEMOTED round 611 (evidence-based): the round-598 depth-0
    attribution puts the ENTIRE relation family at ~927ms — the (d2)
    allocation redesign is no longer a perf lever (the levers are the
    walks + typeOfExpr, both blocked on canonical types). Remaining (d2)
    value is hygiene only: `resolvedPropertyTypes` caches under the
    first-touch ambient scope (a context-keying hole like the pre-548
    nodeTypes) and never caches null results. Re-open only if a
    correctness drift traces here.**
    Delete `resolveGenericPropertyType` fresh-minting + its depth-4 OOM cap
    (the per-recursion-level cache-miss gotcha). **(d1) DONE round 552: the
    depth-4 cap is DELETED — replaced by the per-top-level-relation
    instantiation budget + the param-side foreign-TP gate in
    tryEmitObjectVsNamedUnionArg (see the session note). Remaining: the
    member-table-on-reference allocation redesign ((d2), optional now that
    the budget bounds allocation) and the fresh-minting deletion.**
    **CAP-LIFT PROBE FALSIFIED (round 551, reverted): removing
    `relationDepth < 4` with (a)-interning + the (ref.id, prop.id) memo in
    place still KILLS performanceComparisonOfStructurallyIdentical-
    InterfacesWithGenericSignatures — the deep-stack thread dies after ~20 s
    (OOM → NPE at runWithDeepStack's result unwrap). The blowup is BREADTH,
    not depth: each comparison level mints genuinely NEW (target, args)
    references (growing arg shapes), so the memo never hits and the
    deeply-nested 5-occurrence heuristic (which fires at relation ENTRY)
    doesn't bound the per-level member/signature instantiation between
    bails. The real (d) fix is tsc-shaped: an instantiation-count budget
    (tsc's instantiationDepth/instantiationCount → TS2589) plus member
    tables cached ON the reference, NOT a cap lift. Keep the depth-4 cap
    until then.**
    **BUDGETED-LIFT PROBE (round 551b, also reverted): a per-top-level-
    relation budget of 2,000 fresh worker computations (reset at depth-0
    relation entry, consumed on memo miss, raw fallback on trip) TAMES the
    perf-bomb — corpus fully green 11,252/0 — but exposes exactly ONE new
    FP on all 8 profiles: program.ts:2924 TS2345 `(readonly Diagnostic[] |
    undefined)[]` ⊄ `T[][] | readonly (T | …)[]` (tsc's flatten<T> — the
    documented M3.1 masked gap: tsc infers T, we don't, and the old
    depth-≥4 trivial-pass masked it). A TP-free gate on DEEP substitution
    results does NOT kill it — the outcome flips inside the relation
    (target side), not at the substitution result. VERDICT: the cap
    deletion is blocked on generic inference (M3.1) / the (e)-era
    engine-opening work, not on allocation strategy — sequence (d) with
    (e), and consider a param-side foreign-TP bail at the call-arg
    emission as the enabling slice (corpus-gated; the round-431 gate
    family's rationale applies verbatim to un-inferred PARAM types).**
  - [x] **INV.5(e) Open `canUseTypeEngine`'s generic gate; delete superseded
    pin walkers** (suite-gated per deletion). DONE round 600: sweep verdict
    15/16 load-bearing, checkGenericFnTypeBipartition deleted. Then RETURN to INV.4(e).
    **FIRST HALF DONE round 553: the hasUnresolvedTypeParams skip is
    DELETED (corpus + listAll ×8 identical; the Box<T>-vs-Box<string>
    false negative now fires — Inv5GenericGateTest). Remaining: the
    pin-walker deletion sweep.**

- [x] **INV.6 Parallelism — Phase 0 CLOSED round 609** (6a-6d1: --workers 2 = −17% wall, output sorted-identical, all-8-profile partition equivalence; w4 flat at the per-worker redundancy ceiling — Phase 1 shared frozen collectors is the reopener, gated on an immutability audit; (6e) parallel emit deferred: emit workers would race the shared checker's lazy caches, and benches are --noEmit). Share-nothing checker workers per
  `docs/parallel-caching.md` (trivially partitionable once INV.4 gives a per-file
  check entry); parallel emit on Default + IO write sink; deterministic partition +
  merge via the existing diagnostic sort. Structured concurrency from INV.1.
  - [x] **(6a) The spine partition seam** — DONE round 605: `assignedFileNames`
    gates both spine per-file loops; sequential-equivalence contract pinned by
    SpinePartitionEquivalenceTest.
  - [x] **(6b) Profile-scale equivalence A/B** — DONE round 606:
    `--partitionCheck N` harness; EQUIVALENT on all 8 profiles (w=2) + the
    two stress profiles (w=4). Zero divergences — (6c) unblocked.
  - [x] **(6c) The parallel driver** — DONE rounds 607-608 (6c0 thread-local
    id sequences + deep-stack handoff; 6c1 runInDeepStackWorkers +
    `--workers N`). Measured: w2 −14% wall, w4 flat (per-worker redundant
    fixed cost — see the round-608 note); output sorted-identical to
    sequential.
  - [x] **(6d1) Widen the partitioned region** — DONE round 609: 193
    emission-pass loops on `checkedResults` (318 pure collectors stay
    program-wide); all-8-profile equivalent; w2 −17%, w4 flat. Deeper
    widening = Phase-1 shared frozen collectors (immutability audit) —
    queue that only after INV.5 canonical types or on a >4-core box.
  - [ ] **(6e) Parallel emit** on Default + IO write sink (INV.1's Flow
    foundation; no dashboard delta expected — benches are --noEmit).
- [x] **INV.7 Productization — CLOSED for queue purposes (checkbox reconciled
  round 687): 7a/7c1/7d1/7d2/7d3 all landed and the only remaining child, (7b)
  release binary + native bench row, is PARKED-BY-OWNER.** (absorbs M5.5/M5.6). Native re-enable (the big-input
  GC inversion should largely dissolve post INV.4/5); watch mode driven by a
  file-event Flow; `.tsbuildinfo`-style incremental reuse.
  - [x] **(INV.7c1) `--watch` minimal watch mode** — DONE round 613 (full
    rebuild per debounced change batch; fileEvents Flow expect/actual;
    end-to-end verified, 46ms warm rebuild). Incremental reuse is (7d).
  - [x] **(INV.7d1) Watch-mode incremental recheck** — DONE round 614
    (reverse-dependency closure over the INV.6 partition seam; full-rebuild
    bails for non-local changes; --watchVerify field gate; equivalence
    pinned by WatchIncrementalTest).
  - [x] **(INV.7d2) The shared-name residual bail** — DONE round 615
    (sharedNameFiles: lib-global KNOWN_GLOBALS ∪ script top-level names;
    bidirectional bail via eligibility + outcome validation; +2 pins).
    Real-lib names outside the curation stay on the --watchVerify net.
  - [x] **(INV.7d3) Cross-process `.tsbuildinfo` persistence** — DONE round
    617 (owner approved the generateBuildInfo build change 2026-07-19):
    `XTSC_BUILD_ID` (git sha, `.dirty`/`unknown` never persist nor reuse)
    stamps `tsconfig.xtsbuildinfo`; cold start hash-validates inputs (incl.
    every `.json` config read via RecordingVfs) and runs the (7d1) closure
    protocol for the changed set under `--incremental --noEmit`; new files
    caught by the outcome shape check. TsBuildInfoTest (+11).
  - [x] **(INV.7a) linuxX64 re-enabled** — DONE round 610: compiles/links/runs
    byte-correct (compiler profile = the exact 46-error floor, 196s debug
    binary; smoke 82ms). EpochMap/Set now composition (K/N HashMap is final).
  - [x] **(INV.7b) Release binary + native bench row** — DONE round 823 on the
    8-core / 15.6 GB box, full write-up `docs/perf/aot-native-image.md` § 2c.
    `linkReleaseExecutableLinuxX64` at the committed 4g: BUILD SUCCESSFUL in
    8m53s, 26.2 MiB binary, peak system used 6,083 MB against 9,530 MB still
    available — never near the OOM-killer, so round 610b's BLOCKED-ON-RESOURCES
    is retired on evidence. Output **byte-identical to the JVM** at the exact
    46-error floor (sorted `--listAll` diff empty). Bench, 5 interleaved cold
    pairs, all 10 runs at 46 errors: JVM **25,299 ms** median (sd 867, 9.2%
    spread) vs native **20,045 ms** (sd 783, 11.3%) = **1.26×**, reproducing
    round 772's 1.21×. **NOTE the queue's own misquote, corrected in § 2c: the
    "native 13.4 s" artifact point cited above and at (JIT.2a) is the GraalVM
    native-image number, NOT Kotlin/Native** — K/N release has only ever
    measured 21.8 s (round 772) and 20.0 s (round 823). Verdict unchanged:
    K/N is a REACH artifact (no JVM anywhere, corpus runs natively), never a
    speed one; GraalVM stays the speed path and (AOT.1) stays owner-gated.
    Also NOT reproduced: round 772's "0.2% spread" AOT-determinism claim,
    which was n=3.

Numeric targets (proposed, doc § 6): post INV.4/5 single-threaded compiler profile
≤ 10 s (≈ JS tsc) + harness RSS ≤ 1 GB; post INV.6 compiler ≤ 5 s on 4 cores;
INV.7 stretch: native cold ≤ 2× tsgo.

### Post-v1 backlog — the "any TypeScript project" horizon (UNPARKED round 679)

**UNPARKED 2026-07-25 (round 679).** v1 was declared at round 481 and was
RE-VERIFIED at HEAD this round, 200 rounds later: all 8 profiles exit 0, emit
EVERY input file (81/81, 312/312, 84/84, 78/78, 274/274, 252/252, 80/80,
88/88), zero crash frames, and every one of the 140 diagnostics is a missing
Node ambient (`process`/`Buffer`/`require`/`NodeJS`/`console`) under a
`"types": []` tsconfig — i.e. config/env artifacts, not compiler faults. With
EP.2c skipped by the owner and the remaining M5/INV items parked or
zero-value on this box, **this section is now the live queue**.

**SUPERSEDED 2026-07-26 (round 716) — THIS SECTION IS NO LONGER FIRST.** Owner
directive: "do anything needed … to increase the performance", followed by "how
should we proceed to match the tsc performance on a single thread". The PERF
section above is the live queue again, and **(DISPATCH.1) is the top unchecked
item**; work it before anything here. This section stays OPEN and unparked — it
is not cancelled, and it holds the only known SILENT-WRONG-ANSWER defect in the
codebase (M2.4: with `"lib": ["dom"]` a browser project's DOM code compiles
CLEAN and entirely unchecked) plus the "real project" gaps (declaration emit,
sourcemaps, JSX, nodenext). **The trade being made is explicit: matching tsc's
speed is being prioritised over making the compiler usable on non-tsc projects.**
Revisit when the perf arc reaches its staged target or stalls.

(Historical note: the loop was to skip this section until v1 landed. It landed
at 481; the section stayed parked ~200 rounds because nothing re-read the
condition. Worth remembering as a queue-hygiene failure mode in its own right.)

- [x] **M4.8 DONE round 680 — `/// <reference path|types>` pulls files into the
  program.** Resolution-KIND confusion: the parser recorded directives into
  `moduleSpecifiers`, which the crawl resolves as MODULE specifiers, but a
  `path=` target is a file path relative to the referencing file and a `types=`
  target is a type-root package. Split onto `SourceFile.referencedPaths` /
  `referencedTypes`; the crawl resolves each correctly and TRANSITIVELY. TS6053
  needed no change (the checker asks whether the target is in the program, so it
  goes silent exactly when resolution succeeds — pinned both ways). Measured with
  `@types/node`: program 79 → 146, TS2591 43 → 13. Dashboard untouched (all 8
  profiles identical in errors AND program size); suite 12,598/0/3 (+19 pins).
- [x] **M4.9 DONE round 686 — 30 → 13 on the `"types": ["node"]` profile, and
  every survivor is the env-legit TS2591 class** (a file using
  `require`/`process` without importing node types — the same class the eight
  dashboard profiles carry by design). ONE cause behind the whole residual:
  `mergeModuleAugmentations` published every export of a FILELESS `declare module
  "spec"` into `globals`. Right for an AUGMENTATION (globals is its only
  visibility channel); wrong for the identical syntax in a SCRIPT `.d.ts`, which
  DECLARES the ambient module — those members are reachable only through an
  import of the specifier. The damage was not a stray name but a WRONG WINNER:
  the published member outranked a file's own import alias, so tsc's sys.ts
  resolved its own `WatchOptions` to `@types/node`'s `fs.WatchOptions` and every
  downstream check disagreed with the source. Gating on the declaring file being
  an external module (tsc's own augmentation-vs-declaration distinction;
  `moduleFiles` is already populated before this pass) cleared TS2353×7,
  TS2339×3, TS2322×2, TS2345, TS7006, TS1345, TS2709 and TS2558 at a stroke.
  **Found by discrimination, not search:** a four-file repro, then a probe type
  declared ONLY inside the ambient module — it drew TS2304 (not in the TS2304
  walker's scope) while its MEMBERS resolved (in the type-position scope), which
  located the split in one run. Gates: suite 12,651/0/3 (+4 pins), `--listAll` ×8
  byte-identical (the dashboard's `"types": []` keeps it off this path).
  Round-681 part 1 (below) landed `skipLibCheck` and the parameter-shadows-
  namespace bail. **A NINTH dashboard profile for `"types": ["node"]` is still
  worth adding** — do NOT alter the existing eight.
- [ ] ~~M4.9 (part 1, round 681)~~ — Landed:
  `skipLibCheck` is now honoured (it was parsed and never consulted — TS7008×15
  + TS7010×2 were being reported against DefinitelyTyped's own declaration
  files), and a PARAMETER now shadows a same-named namespace that reached
  globals from an ambient module body (TS2339×18 → 3; tsc's
  `formatJSDocLink(link: …)` vs `fs.d.ts`'s `export namespace link`). REMAINING
  on that profile: 13 TS2591 (`require`/`process` where the file references node
  types without importing them), **7 TS2353** (`fs.WatchOptions` vs the
  compiler's own `WatchOptions` in an object literal — the next-largest
  cluster), 3 TS2339, plus TS2322×2/TS7006/TS2709/TS2558/TS2345/TS1345
  singletons. Repro: copy the profile tsconfig with `"types": ["node"]`
  (fixture gitignored at `build/bench/tsc-project-637d5746/node_modules/@types/node`).
  Consider a NINTH dashboard profile to track it — do NOT alter the existing
  eight, whose `"types": []` is deliberate.
- [ ] ~~M4.9 (original)~~ — **The gaps `@types/node` exposes once it loads** (found round 680,
  directly downstream of M4.8). With `"types": ["node"]` on the compiler profile
  the missing-ambient errors mostly clear (TS2591 43 → 13) and what remains is
  REAL, previously masked by the unresolved names: **TS2339×18** (e.g.
  `Property 'kind' does not exist on type 'typeof link'`), **TS7008×15**
  (implicitly-any members), **TS2353×7** (`'watchFile' does not exist in type
  'WatchOptions'` — our `fs.WatchOptions` vs the compiler's own `WatchOptions`),
  TS2322×2, TS7010×2, TS7006, TS2709. Reproduce by copying the profile tsconfig
  with `"types": ["node"]` (fixture already at
  `build/bench/tsc-project-637d5746/node_modules/@types/node`, gitignored).
  Consider adding it as a NINTH dashboard profile so the numbers are tracked —
  but do NOT change the existing eight, whose `"types": []` is deliberate.
- [ ] ~~M4.8 (original)~~ — **`/// <reference path|types="…" />` must ADD files to the program**
  (found round 679; the single highest-impact gap for "any TypeScript project").
  Our handling — `TypeScriptCompiler.kt` ~2168, gated on
  `includeReferencePathDeps`, i.e. `outFile` only — merely ORDERS files ALREADY
  in `allTsFileNames`. tsc's `processReferencedFiles` **pulls the referenced
  file into the program**. Consequence, measured: `@types/node`'s `index.d.ts`
  is 64 `/// <reference path>` lines and little else, with `globals.d.ts`
  declaring `var process` and `namespace NodeJS` — so enabling
  `"types": ["node"]` on the compiler profile took the program from 78 to just
  **79** files and left all 46 diagnostics standing. Every real Node project is
  affected the same way. Fixture already installed (gitignored) at
  `build/bench/tsc-project-637d5746/node_modules/@types/node`; the probe config
  was a temporary `tsconfig.node.json` (deleted — recreate by copying the
  profile tsconfig with `"types": ["node"]`). The dashboard tsconfig
  deliberately keeps `"types": []` and our handling of THAT is correct per tsc
  semantics — do not "fix" the baseline; add a separate profile if one is wanted.
- [ ] ~~M2.4 DOM libs~~ — **SUPERSEDED round 716 by (LIB.1) at the top of the queue** (owner: "yes, please fix it"; the owner-gated lib-shipping decision it was blocked on is now granted). Body kept for its measurements.
- [ ] ~~M2.4 (original)~~ — RE-SCOPED round 687 by measurement: the premise is wrong
  and there is a SILENT-WRONG-ANSWER bug underneath it.** The item asked to
  measure dom.generated.d.ts's parse/bind cost. That cost is **not measurable
  because the DOM libs are NOT SHIPPED**: `RealLibFiles` contains no
  `dom.generated` / `dom.iterable.generated` / `webworker*` entry (its only "dom"
  occurrences are `/// <reference lib="dom" />` lines inside OTHER libs' text).
  **What `"lib": ["dom"]` does today:** `RealLibResolver.resolve` records the file
  in `Resolution.unavailable` and the final `ordered` list filters it out —
  and `Resolution.unavailable` is **never consumed outside RealLibs.kt**, so
  nothing is reported. Measured consequence on a 3-line program: `HTMLElement`
  resolves, `document` resolves, and `e.definitelyNotAMember` on an `HTMLElement`
  parameter compiles **CLEAN** — i.e. a browser project gets a green build with
  its DOM code entirely unchecked. (Without `dom` in `lib` the same name draws
  TS2552 "Did you mean 'HTMLLIElement'?", because DOM names are in KNOWN_GLOBALS
  for the TS2304 walker — which is why adding `dom` LOOKS like it worked.)
  **Round 688 CORRECTION — follow-up (i) was attempted and REVERTED as dead
  code, which uncovered the bigger fact: `useRealLibs` defaults to FALSE and
  NOTHING in the project path turns it on** (`ProjectCompiler`/`TsConfigLoader`
  never set it; the only writer is the `usereallibs` test directive). So the
  entire real-lib machinery — `RealLibResolver`, `RealLibSnapshots`,
  `Checker.bindRealLibs`, and `Resolution.unavailable` with it — is exercised
  ONLY by tests that opt in. **Every real project build, including all eight
  dashboard profiles, runs on the EMBEDDED `BUILTIN_LIB_SOURCE`.** A diagnostic
  wired into `bindRealLibs` therefore never executes; it was implemented, seen
  not to fire, and reverted rather than landed. Two further facts the attempt
  established, both needed by whoever picks this up: **(a) `unavailable` must not
  be the key** — a `full` default lib (`lib.d.ts`, `lib.es2020.full.d.ts`)
  transitively references the DOM/host files, so an ordinary target-default
  resolution has a non-empty `unavailable` and must stay silent; only a name the
  USER wrote is reportable, which needs a new field, not the existing one (a
  working `unavailableRequested` implementation is in the round-688 reflog if
  wanted). **(b) the corpus blocks the embedded-path fix**: 259 corpus cases
  carry `@lib:`, of which **23 request `dom`** plus `webworker`×4,
  `webworker.iterable`×2, `webworker.asynciterable`, `scripthost`,
  `esnext.temporal`, `esnext.intl` — all unshipped, all currently GREEN, so
  reporting on the embedded path breaks ~30 baselines that were generated by a
  real tsc which HAS those libs.
  **So the real follow-ups are, in order:** (i) **decide what real project builds
  should use for libs at all** — the embedded lib is a curated subset while the
  shipped real libs are unreachable outside tests; that mismatch is the root, and
  it is a design decision, not a patch; (ii) **ship the DOM/webworker/scripthost
  sets** — changes the real-lib GENERATION in build.gradle.kts and adds ~1 MB of
  generated source, so **owner-gated**; (iii) only then is the original
  parse/bind cost question answerable, and only then can an unshipped-lib
  diagnostic be both correct and reachable.
  **Method note worth keeping:** the first control I ran — "does `HTMLElement`
  resolve with `dom` in lib?" — PASSED, and a clean 5-pair interleaved A/B then
  showed the cost inside the noise band. Both were measuring nothing. When an
  unknown name degrades to `any`, name resolution proves nothing; the control
  that decides is a **MEMBER probe** (`e.notAMember` must error).
- [ ] **M3.0 Conformance generator extension — INFRASTRUCTURE DONE round 690; FOUR
  categories adopted (round 695); the remaining categories are measured, not guessed.**
  **Round-695 redness table** — twelve candidate categories added to the allowlist in ONE
  suite run (+236 tests, 91 failures), then all but the tractable ones reverted. Failures
  per category, so a future round can pick by cost instead of re-measuring:
  `es6/defaultParameters` **0** · `es6/restParameters` **1** · `expressions/commaOperator`
  **2** · `expressions/asOperator` 5 · `types/any` 6 · `types/conditional` 8 ·
  `types/nonPrimitive` 9 · `statements/labeledStatements` 9 · `types/typeAliases` 9 ·
  `expressions/contextualTyping` 9 · `expressions/typeSatisfaction` 12 ·
  `expressions/optionalChaining` 21. The first three were adopted; the rest are each a
  round's worth of gap work. Two caveats worth carrying: `statements/labeledStatements`
  is 9 failures from only 8 files (proportionally the reddest), and its failures include
  **JS-emit** subtests, which `conformanceDeferredErrorBaselines` cannot defer — an emit
  gap must be FIXED before that category can land. Measuring a batch this way costs one
  ~7-minute run and is much cheaper than adopting a category and discovering it is red.
  Extend `generateTypeScriptTests` with a
  per-category allowlist for `tests/cases/conformance/` (keep all tsgo set-B
  filters). Each category lands only when its failures are triaged into queue
  items — never leave a category half-red without notes. Owner approval
  (2026-07-02) stands.
  **Verified round 689 (do NOT re-derive):**
  1. **The sources ARE readable offline.** `typescript-repo` is a BLOBLESS partial
     clone (`remote.origin.partialclonefilter = blob:none`, `promisor = true`) and
     its sparse checkout lists only `tests/cases/compiler` +
     `tests/baselines/reference` — so this looked network-gated. It is not: a
     `git cat-file -p HEAD:tests/cases/conformance/…` probe returns content, so
     the needed blobs are already local.
  2. **Baselines need no work** — the sparse checkout already takes the WHOLE
     `tests/baselines/reference`, which is flat and holds the conformance ones.
  3. **The variant-baseline convention is ALREADY implemented.** Conformance uses
     `name(target=es5).errors.txt`; the generator's `computeVariations` /
     `paramBaselineName` produce exactly `name(key=value).ext`.
  4. **ZERO basename collisions** between ALL of conformance and the 6,537
     compiler cases, so the generated flat backtick function names need no
     disambiguation.
  5. **Sizing for the M3.1-matching categories:** `expressions/functions` **7
     files** (the right first category), `types/typeParameters` 46,
     `types/typeRelationships` 263.
  **The three edits:** (a) `sparsePaths` (build.gradle.kts ~271) += the
  allowlisted category dirs; (b) the `testFiles` collection (~547) currently
  `testsDir.listFiles { flat }` must also walk the allowlisted conformance dirs
  RECURSIVELY (categories have subdirs); (c) the generated bodies hardcode
  `Path("${'$'}typeScriptCasesDir/<name>.ts")` where `typeScriptCasesDir` is
  `tests/cases/compiler` (TypeScriptTestSupport.kt:38) — a conformance case needs
  its own path, so emit a per-file relative path or add a second constant.
- [x] **(M3.0-gap-1) DONE round 692 — `arrowFunctionContexts` passes and is
  un-deferred.** Three defects, one per round-690 triage line. The TS2403 ×2 FALSE
  POSITIVE was a generic arrow mistyped `<T>(n: T) => any` (round 691: the arrow's
  own type parameters were interned only when the `Signature` was built, i.e.
  after the return had been inferred). The two MISSING codes landed this round:
  TS18033 fired only for a STRING-typed computed member, so a function-valued one
  (`enum E { x = () => 4 }`) drew nothing — extended to a syntactically
  arrow/function-expression initializer, FP-safe by construction since such a
  member can never satisfy the numeric domain; and the TS2332 walker skipped arrow
  bodies alongside function bodies, but an ARROW DOES NOT REBIND `this`, so
  `(() => this).length` in an enum initializer is just as illegal — the descent
  emits TS2332 only, because the reference baseline has no companion TS2683 for
  the arrow-nested form.
- [ ] **(M3.0-gap-2) PARKED round 714 — everything worth having from this case has
  shipped; the case itself stays deferred by DECISION, not by omission.** Fixed across
  rounds 693/704/706/707: the over-emitted TS7019/TS7006 (IIFE parameters are
  contextually typed, so tsc reports nothing for them), the contextual TYPING itself
  (from the call's arguments, in `populateParameterLocalTypes`), and all three TS18048 —
  including the pure-`undefined` reference case, which also fixed the literal-vs-reference
  boundary against TS18050. Round 713 additionally closed the argument-context TS7006
  hole the case exposed, under noImplicitAny.
  **Why it will not un-defer:** its remaining TS7006 ×2 are on argument arrows in a file
  whose only directive is `@strictNullChecks` — pure-default mode, where the full
  implicit-any walker is deliberately OFF and the narrow default-mode walker covers one
  shape on purpose. Closing it requires broadening that walker, which is the change
  recorded as having regressed ~19 tests. Not worth it for one conformance case; revisit
  only if the default-mode walker is broadened for its own reasons.
  ORIGINAL: **the FALSE-POSITIVE half is FIXED (round 693); the missing codes remain.** tsc
  contextually types an IIFE's parameters from the call ARGUMENTS, so it reports
  no implicit-any for them even when the call passes none; we emitted TS7019 ×3 +
  TS7006 ×2. `isImmediatelyInvokedFunctionParam` (owner walked up through
  parentheses to a CallExpression whose unwrapped callee is that function)
  suppresses both, in BOTH emitters — the general parameter walker AND the
  dedicated rest-parameter walker, which carries its own TS7019 and TS7006 and is
  the one live for these shapes.
  **ROUND 704: the parameters ARE now typed from the arguments** (in
  `populateParameterLocalTypes`, per the round-694 finding — `((a) => a.nope)("x")`
  reports TS2339 on `string`), with two pieces still open. **(i)** Only ARROWS are
  typed; a function EXPRESSION IIFE is not, and the site responsible is NOT the
  no-contextual-annotation branch that blanket-registers `any` for a callback's own
  parameters (deferring there was measured inert). A limitation pin records this.
  **(ii)** The typed parameters now produce the RIGHT analysis with the WRONG code:
  `((j?) => j + 1)(12)` reports **TS2365** ("Operator '+' cannot be applied to types
  'number | undefined' and '1'") where tsc reports **TS18048** ('j' is possibly
  'undefined') — the documented round-415 hazard, where a union carrying `undefined`
  fails the arithmetic operand classifier. tsc checks possibly-undefined FIRST, so the
  fix is a nullish-operand rule ahead of TS2362/2363/2365 in the arithmetic pass.
  That is what still keeps the case deferred.
  **ROUND 706: the rule LANDED — direction confirmed against a SECOND reference baseline
  (`circularOptionalityRemoval` reports TS18048 for `x > 0` with `x: number | undefined`,
  as `contextuallyTypedIifeStrict` does for `j + 1`), and the two TS2362 baselines that
  also mention "possibly undefined" were checked and are unrelated (their operand is a
  `delete` expression, a boolean). The nine local pins were updated to expect TS18048,
  their intent unchanged, and one paired positive control strengthened to exclude TS18048
  too.** **ROUND 707 closed the `k`/`o` half too** — a REFERENCE typed exactly `undefined`
  now reports TS18048 like a union does (the arithmetic walker's strictNullChecks early
  return deferred those to TS18050, which is right only for the LITERAL operand). All
  three TS18048 of the case now fire at the baseline's positions. **What remains is only
  the two TS7006** for the INNER function's parameter in `(f => f(12))(i => i)`.
  **ROUND 708 probed it and the framing "argument arrows are not reached" is WRONG** —
  four contrasted shapes under `@strictNullChecks: true`: `take(i => i)` against an
  annotated `(x: number) => number` parameter is correctly SILENT; `anyCb(j => j)`
  against an `any` parameter correctly FIRES, so the walker does reach an argument
  arrow and does emit there; `(f => f(12))(k => k)` is silent (the gap); and — the
  surprise — a plain `function plain(m) { return m; }` is ALSO silent in the same file.
  That last one is not about IIFEs or arguments at all, so the next round should start
  by settling the GATE question (which shapes emit TS7006 under which options, and why
  a top-level function declaration's parameter differs from a callback's here) before
  touching the IIFE case. Do not assume the callee-typing path is at fault.
  **ROUND 710 CORRECTS ROUND 709: the two-walker split is DELIBERATE and documented, so
  "unify the gates" is the wrong instruction — do not follow it.** `checkImplicitAny
  DefaultVarFunctions` runs ONLY in pure-default mode and covers ONE shape
  (`var v = <arrow|fn-expr>` with an untyped parameter), because the full
  `checkImplicitAnyParameters` walker is gated on noImplicitAny/strict for a MEASURED
  reason: broadening it regressed ~19 tests (FunctionDeclaration params, type-annotation
  walking, ambient TS7005/7008, JS files, object-literal contextual-typing gaps). The two
  are mutually exclusive by construction so they never double-emit.
  **What survives from round 709 as a real finding** is narrower and still worth fixing:
  `anyCb(j => j)` (an arrow argument against an `any` parameter) is reported in
  pure-default mode but NOT under noImplicitAny — turning the stricter option ON loses a
  diagnostic, which cannot be right whatever the walker split is. And gap-2's
  `(f => f(12))(k => k)` is uncovered in BOTH modes. So the target is a COVERAGE hole in
  `checkImplicitAnyParameters` (argument arrows whose callee parameter provides no
  contextual type), not the gates.
  **ROUND 711 located it exactly: a CONTRACT MISMATCH.** The argument edge is built as
  `SpineIanyCtx(kind = 1, typed = isCalleeResolvable(node.expression))` (~53248), i.e. it
  uses "can I resolve the callee NAME" as a proxy for "does this argument have a
  contextual type". Those come apart in precisely the two shapes that are missing:
  `anyCb(j => j)` — the callee resolves, so `typed = true` suppresses, but its parameter
  is `any` and therefore supplies NO contextual signature, which is why tsc reports it;
  and `(f => f(12))(k => k)` — the callee is a parenthesized ARROW, so
  `isCalleeResolvable` returns its default `true` and suppresses again.
  **The fix is to consult the callee's PARAMETER TYPE at the argument's position** (no
  contextual signature when it is `any`, unresolved, or not function-shaped) rather than
  the callee's resolvability. Note `isCalleeResolvable` also has a deliberate B182 arm
  (a LIB_MIN_TARGET-dropped method has no contextual signature) — the same idea, applied
  to one case; this generalises it. **Gate carefully:** broadening this walker is the
  change documented as having regressed ~19 tests, so expect the corpus to arbitrate,
  and run `--listAll` ×8 as well since callback parameters are everywhere in tsc's own
  source. Round 709's framing below is kept only to mark the
  **ROUND 712 IMPLEMENTED IT AND REVERTED — two narrowings are already spent, start
  from them.** The edge change is small and works: at the argument consumer (~53370) the
  index IS available (`p.arguments.indexOfFirst { it === node }`), so `typed` becomes
  `callCtx.typed && !calleeParamIsPositivelyAny(p, idx)`; with it all three target shapes
  fire under noImplicitAny — including gap-2's `(f => f(12))(k => k)` — and
  `take(i => i)` stays silent. **(1) Our resolved `anyType` is NOT tsc's `any`:** deciding
  on the RESOLVED parameter type red-lined three corpus baselines
  (contextualPropertyOfGenericFilteringMappedType,
  contextualTypeFunctionObjectPropertyIntersection, normalizedIntersectionTooComplex),
  because a generic or mapped annotation we cannot resolve lands on `anyType` too and
  those DO have contextual types — the test must be SYNTACTIC (the annotation is literally
  the `any` keyword, or absent), which makes the corpus green. **(2) The EMBEDDED LIB's
  `any`s are placeholders:** with the syntactic rule the PROFILES gain FPs (46 → 47,
  harness 94 → 98) on `.replace(/\./g, s => s.substring(1))` and
  `JSON.stringify(f, (_, v) => …)`, since our lib simplifies those callback signatures
  where tsc states them precisely. Excluding the builtin-lib decl sets is the right
  direction and is precedented (the TS2554 lib gate), but the exclusion I wrote did NOT
  catch the `.replace` site — establish first which set holds a resolved lib METHOD's
  parameter for a PropertyAccess callee, then it should land.
  correction. ORIGINAL (WRONG): **the two TS7006 emitters have INVERTED option gates.** Same four shapes, two configs:
  | shape | strictNullChecks only | + noImplicitAny |
  | `take(i => i)` (annotated context) | silent (right) | silent (right) |
  | `anyCb(j => j)` (`any` parameter) | **FIRES** | **SILENT** |
  | `(f => f(12))(k => k)` | silent | silent |
  | `function plain(m) {}` | **SILENT** | **FIRES** |
  Turning `noImplicitAny` ON switches OFF the emitter that was firing, and vice versa —
  so no single configuration reports both shapes, and `anyCb(j => j)` going silent under
  noImplicitAny is a plain bug (tsc reports it). Relevant context for whoever fixes this:
  TS7006 fires BY DEFAULT in the corpus — 12 of 22 sampled TS7006 baselines have no
  `@noImplicitAny`/`@strict` directive at all — so the default-on convention
  (`!strictExplicitlyFalse`) is the one that matches the reference, and the
  `noImplicitAny || strict` gate is the odd one out. Unify the two gates on the
  default-on convention FIRST, re-gate, and only then look at the IIFE shape; it may
  well fall out, since the conformance case sets only `@strictNullChecks: true`.
  ROUND 705's framing, kept for the reasoning: **the rule works — but it collides with
  NINE LOCAL PINS, and resolving that collision is a decision, not a patch.** The rule (a possibly-undefined
  check ahead of TS2362/TS2363/TS2365 in the three arithmetic emitters, strictNullChecks
  only, plain references only, `any`/`unknown` excluded) turns `((j?) => j + 1)(12)` into
  the TS18048 the reference baseline wants. The CORPUS stays green — but nine hand-written
  pins in ArithmeticAmpAmpNarrowingTest, ArithmeticReassignmentNarrowingTest,
  Inv4SpineBatch22Test and NonNullArithmeticOperandTest assert that a maybe-undefined
  operand fires **TS2362**, e.g. `negative control - genuinely maybe-undefined operand
  still fires TS2362`. **The evidence says those pins encode OUR old behaviour rather than
  tsc's:** the `contextuallyTypedIifeStrict` reference baseline reports TS18048 for exactly
  this shape (`j: number | undefined`, `j + 1`), and the corpus is green either way, so it
  does not discriminate. Their INTENT — "narrowing did not apply, so it still fires" — is
  preserved by TS18048; only the code changes. So the next round should update those nine
  to expect TS18048, having first confirmed the direction against one more real baseline,
  and then re-gate. The rule was reverted rather than landed with nine red pins.
  **Still missing after it, for the record:** `k`/`o` (an optional parameter with NO
  corresponding argument types as `undefined`, and nothing fires for it yet) and the two
  TS7006 for the INNER function's parameter in `(f => f(12))(i => i)`.
  **ORIGINAL REMAINING:** the reference's **TS18048 ×3** ('j'/'k'/'o' possibly undefined, from optional IIFE
  parameters under strictNullChecks) and **TS7006 ×2** (lines 28–29 — the INNER
  function's parameter in `(f => f(12))(i => i)`, which tsc genuinely reports)
  do not fire.
  **Round 694 established WHERE the hook must go, by writing it in the wrong place
  first.** Typing the parameters in `getTypeOfArrowFunction` (next to
  `applyContextualParameterTypes`, writing `symbolTypes[param.id]`) is
  UNOBSERVABLE: `((a) => a.nope)("x")` still reports nothing, because the BODY
  walkers do not read `symbolTypes` for parameters — they read `currentLocalTypes`,
  filled by **`populateParameterLocalTypes`**, which records a parameter ONLY when
  it carries an ANNOTATION (`if (paramType != null && paramName is Identifier)`).
  So an un-annotated parameter is invisible to them no matter what the signature
  says. That implementation was written, measured, and REVERTED rather than landed.
  **The real change is therefore in `populateParameterLocalTypes`** (or wherever
  else a walker derives parameter locals): record an argument-derived type for an
  un-annotated parameter whose owner is an IIFE callee — reusing
  `immediatelyInvokingCall`-style parent-walking, which round 693 already proved
  out. Expect a WIDE blast radius: it gives types to parameters that were `any`
  everywhere, in ~26 call sites' worth of walkers, so it needs the corpus and the
  `--listAll` ×8 gate and probably its own round.
- [ ] **(M3.0-gap-3) `commaOperatorOtherInvalidOperation` — (A) and (B1) are DONE
  (rounds 697/700/701); only (B2) remains, so the case stays deferred.** What is left is
  the second TS2322, `var result: T1 = (x, y)` — TypeParam-vs-TypeParam, blocked by the
  relation's "two unconstrained type parameters always relate" leniency, whose correct
  form was measured in round 695 at exactly 2 corpus tests (both masking an
  un-substituted class type parameter in a member) — plus `canUseTypeEngine` refusing a
  TypeParam-vs-concrete pair, which is what keeps `var s: string = x` silent even though
  the relation already answers correctly. ORIGINAL TEXT follows.
  Two missing TS2322, both from the same root: `function foo(x: number, y: string)
  { return x, y; }` must infer the return type `string` (so `var r: number = foo(...)`
  errors), and `var result: T1 = (x, y)` — with `x: T1`, `y: T2` — must report
  `Type 'T2' is not assignable to type 'T1'` plus the "could be instantiated with an
  arbitrary type" chain line and a TS2208 related info at the `T2` declaration.
  We already emit the case's other two diagnostics (TS2454 ×2), so this is additive.
  **(A) IS DONE (round 697)** — `inferReturnTypeFromBody` gained a Comma arm typing the
  right operand from the OWNING function's parameter annotations (`commaReturnOperandType`);
  corpus green, all 8 profiles byte-identical, +6 pins. Only (B) remains, so the case
  stays deferred.
  **Round 695 isolated both halves — read this before starting, two of the obvious
  routes are already excluded.** A five-line probe (`function baz(...): string` beside
  the inferred `foo`, and a `var direct: T1 = y` beside the comma one) splits the case:
  **(A)** the comma itself is only half the story — `combineBinaryTypes` ALREADY types
  a comma as its right operand (`SyntaxKind.Comma -> getTypeOfExpression(right)`), and
  the annotated `baz` errors correctly, so what is missing is
  `inferReturnTypeFromBody`, whose `BinaryExpression` arm has no Comma case. Note its
  deliberate conservatism: its `Identifier` arm returns null for anything but
  `true`/`false`, because it runs in the CALLER's scope, where resolving a callee's
  parameter by name would hit the documented shadowing hazard. So a Comma arm cannot
  just call `getTypeOfExpression(right)` — the honest fix types the right operand
  against the OWNING function's parameter annotations (reachable via the body's
  `parent`), which also fixes the more general `return <param>` gap.
  **(B)** is NOT a comma problem at all — `var direct: T1 = y` (no comma) is equally
  silent — and, measured, it is **not the TypeParam-vs-TypeParam relation either**:
  making two unconstrained type parameters relate only when their names match left the
  case silent, so the emission is suppressed UPSTREAM (the round-431e foreign-TP source
  gate on the var-decl path is the prime suspect — `T2` is a TypeParam in the source).
  Start there, not in the relation engine.
  **Measured cost of the correct relation rule, recorded so nobody re-runs it:** exactly
  **2** corpus tests (`inferFromGenericFunctionReturnTypes1`/`2`), both the same
  `Type 'SetOf<B>' is not assignable to type 'SetOf<B>'` shape — identical display, so
  the leniency is masking an UN-SUBSTITUTED class type parameter in a member (`_store:
  A[]` substituted on one side only). Restricting the strict rule to top-level
  comparisons (`relationComparisonStack.size <= 1`) dodges both regressions, but buys
  nothing while (B)'s real blockers stand.
  **(B)'s real blockers, found by marker probe (round 695 tail) — TWO of them, and
  neither is the relation.** A four-case probe (`f1<T>(x: T) { var s: string = x }`,
  `f2<T1,T2>(y: T2) { var r: T1 = y }`, an array variant, and a fully concrete control)
  printing `typeToString` of both sides plus `canUseTypeEngine`/`checkTypeRelatedTo`
  at `checkVarDeclAssignability`'s gate reports:
  **(B1) a type-parameter annotation on a function-BODY variable resolves to `any`** —
  `var r: T1` gives `tgt=any` (and `var r2: T1[]` gives `any[]`) while the PARAMETER
  annotation `y: T2` resolves correctly, because a parameter is resolved while building
  the signature with the type parameters in `currentTypeParamScope` and a body variable
  annotation is not. So no relation could ever fail here — the same class of bug as
  round 691's generic arrow, one scope level out.
  **(B2) `canUseTypeEngine` refuses a TypeParam-vs-concrete pair** — for `var s: string
  = x` the relation ALREADY returns the correct `false` (`foreign=false`, `rel=false`),
  but `canUse=false` means the emission never runs. tsc reports TS2322 there.
  Fix (B1) first (it is the one that makes `T1` a real type at all); (B2) then decides
  whether the correct verdict is allowed to be emitted. Both have M3.1-flavoured blast
  radius — body variables annotated with type parameters stop being `any` — so each
  wants the corpus and `--listAll` ×8, and the round-431e foreign-TP gate is what should
  keep un-inferred callee TPs out of the new emissions.
  `typeParams` threading is NOT a suspect: the probe shows it arriving correctly
  (`tp=[T]`, `tp=[T1, T2]`) and the foreign-TP gate not firing.
  **(B1)'s ONE-LINE fix is known and was measured (round 696, attempt 1, reverted) —
  do this as ONE change with the chain-parity work below, never alone.** The cta frame
  ALREADY computes the type-parameter scope (`CtaFrame.fnTpScope`, built beside
  `fnTpDecls` at frame-build time) and **never reads it** — `grep fnTpScope` returns its
  declaration and its single write. The per-statement dispatch installs
  `currentTypeParamDecls = frame.fnTpDecls` but not the scope, so annotations resolved
  during that dispatch see no type parameters. Adding
  `currentTypeParamScope = frame.fnTpScope ?: <saved>` to the same save/install/restore
  sandwich works — probe: `var r: T1` goes `any` → `T1`, `var r2: T1[]` → `T1[]`.
  **Its measured cost: 27 corpus tests, and the classification is the useful part** —
  of ~32 changed baseline lines, **~29 are REMOVED `'T' could be instantiated with an
  arbitrary type which could be unrelated to 'null'/'undefined'` chain lines**, i.e. the
  emission survives and only its chain is lost. Mechanism: with `T` resolving to a real
  `Type.TypeParam`, these `return null`-in-a-generic shapes stop falling through to the
  STRING fallback `emitTS2322(..., typeParams)`, which adds that chain when
  `targetBaseName in typeParams` (Checker.kt ~149892), and are handled by a type-engine
  emitter that does not. The var-decl (~95363) and assignment (~98644) paths already
  have the `tt is Type.TypeParam` chain block; the return path's engine emitter is the
  one to give parity. Only **3** lines were additions: one chain-FORM flip
  (constraint-form → arbitrary-form, `errorMessagesIntersectionTypes03`) and two genuinely
  NEW diagnostics (`Type 'Q' is not assignable to type 'InferBecauseWhyNot<Q>'`,
  `Type 'any[]' is not assignable to type 'T'`) that need their own verdict.
  **Attempt 2 (round 696, also reverted) took it from 27 failures to FOUR — the recipe
  below is ~5 minutes of re-typing, so start there rather than re-deriving.**
  *Edit 1 — the scope install:* in the cta per-statement dispatch sandwich (beside
  `currentTypeParamDecls = frame.fnTpDecls ?: emptyMap()`), save `currentTypeParamScope`,
  set it to `frame.fnTpScope ?: <saved>`, restore it in the same `finally` as
  `currentTypeParamDecls`.
  *Edit 2 — chain parity* in `checkReturnAssignability`'s engine emitter, inserted
  immediately BEFORE its "B60.6f (mirror): TS2208 related info" block: when
  `chain.isEmpty() && targetType is Type.TypeParam`, add the constraint form
  (`'<src>' is assignable to the constraint of type '<T>', but …`) when the constraint
  is non-null AND `checkTypeRelatedTo(sourceType, constraint)` AND
  `!anonymousObjectHasExcessVsConstraint(...)`, else the arbitrary form — exactly the
  block the var-decl (~95363) and assignment (~98644) paths carry. This alone clears
  **23 of the 27**.
  **Attempt 3 (round 698) took it to THREE, and named the mechanism behind the last
  two. Add to the recipe:** in the new chain block, the constraint must be treated as
  absent when it is `anyType` **OR `errorType`** — an unconstrained `<T>` arrives here
  with an UNRESOLVED constraint, and errorType DISPLAYS as `'any'` (B58.1), which is
  what made `declFileGenericType` read as `constraint 'any'`. That one guard fixes
  residual (a). Remaining: (b), (c), (d) below.
  **(c) and (d) are DOUBLE EMISSIONS, not false positives — the baselines contain both
  diagnostics, our error COUNT grows by one.** The `Diagnostic`-init stack-trace probe
  named the other emitter for (c): the dedicated pin walker
  **`checkDeeplyNestedMappedTypes`**, which exists precisely because the engine could
  not produce that diagnostic — and its display is the CORRECT one
  (`{ level1: { level2: { foo: string; }; }; }[]`) while the engine renders the source
  as `any[]`, because the case's `Input`/`Output` mapped aliases resolve to any. So the
  engine does NOT supersede the walker here and the walker must not be deleted. Note
  the ORDER, which decides the fix: the engine (cta anchor) emits FIRST and the walker
  later, so a "has anything already reported here?" probe in the engine cannot see it —
  the retraction has to live in the WALKER (documented precedent: a later pass that
  retracts/edits an earlier pass's diagnostics, cf. checkCloduleTest2 removing TS2554 at
  NewExpression positions). (d) was not probed but shows the identical signature
  (baseline has the diagnostic; our count goes 8 → 9), so expect another dedicated
  walker and the same disposition.
  **Attempt 4 (round 699) got the corpus to ZERO with the whole change — and then the
  PROFILES killed it. This is the real blocker; read it before touching (B1) again.**
  Corpus 12,731 / 0 / 3 with all four residuals fixed (see the completed recipe below),
  but `--listAll` ×8 went 46 → **49 on every profile** (harness 94 → 97): three NEW
  false positives, the same three everywhere, all in `compiler/utilitiesPublic.ts`:
  `Type 'Node | undefined' is not assignable to type 'T | undefined'` (777),
  `Type 'JSDocTag | undefined' …` (1280), `Type 'JSDocTag[]' is not assignable to type
  'readonly T[]'` (1285). **All three are TYPE-GUARD-DRIVEN GENERIC INFERENCE:**
  `getFirstJSDocTag<T extends JSDocTag>(…, predicate: (tag: JSDocTag) => tag is T)`
  returns `find(tags, predicate)`, `getAllJSDocTags` returns
  `getJSDocTags(node).filter(predicate)`, and `tryCast`-shaped code returns
  `nodeTest(node) ? node : undefined`. tsc binds the callee's own type parameter to the
  CALLER's `T` through the `tag is T` predicate, so the sources are `T | undefined` /
  `readonly T[]`; we bind the concrete `JSDocTag` and therefore see a mismatch. These
  were invisible while the return annotation resolved to `any` — resolving the target is
  what exposes them. **v1's dashboard is at ZERO real FPs, so this cannot land until
  they are gone.** Two ways forward: make guard-driven inference bind the caller's type
  parameter (independently valuable — round 430 already built "TP-from-PREDICATE
  binding" for exactly this family, so start by finding why it yields `JSDocTag` here),
  or add a TARGET-side companion to the round-431e foreign-TP gate. Prefer the first:
  a target-side gate must still let `function f<T>(): T { return null; }` error, which
  the corpus pins, so it would be a heuristic in the place heuristics are most likely to
  silently lose real errors.
  **THE COMPLETED RECIPE (corpus-green; all four residuals fixed):** edits 1 and 2 as
  described, plus — (a) treat `anyType` OR `errorType` as "no constraint"; (b) report
  the APPARENT constraint by following the interned chain to its first non-TypeParam
  link and, when that yields nothing usable, following the DECLARATION's constraint
  chain by name and resolving its first concrete link (factored as
  `apparentConstraintOfTypeParam`, needed by the ASSIGNMENT path too — that is where
  `errorMessagesIntersectionTypes03`'s `V extends U extends A` is decided, not the
  return path); (c)+(d) register every return-path engine TS2322 in a pre-`init` list
  and, at the end of `init`, drop it by IDENTITY if another TS2322 shares its position —
  dedicated pin walkers run after the spine and own some of these positions with better
  displays, so the engine cannot probe for them at emission time.
  **The FOUR residuals, each already diagnosed:**
  (a) `declFileGenericType` — `export function F5<T>(): T { return null; }` is
  UNCONSTRAINED, yet the interned TypeParam arrives with `constraint == anyType`, so the
  new block picks the constraint form where tsc uses the arbitrary one. Fix: treat an
  `any` constraint as unconstrained (the sibling TS2208 block right below already has an
  "effectively unconstrained" notion, for the self-circular case).
  (b) `errorMessagesIntersectionTypes03` — the reverse: tsc wants the CONSTRAINT form
  (`'A & B' is assignable to the constraint of type 'V'…`) and we produce the arbitrary
  one. Round 698 narrowed the cause: the constraint does not RESOLVE (same errorType
  situation as (a)), so no relation can be run and the engine has no `'A'` to print —
  which is exactly why the old string fallback got it right, reading the constraint
  TEXT out of `currentTypeParamDecls` (`emitTS2322`'s B60.6c path). The fix is to give
  the engine block the same syntactic fallback: when the RESOLVED constraint is
  unusable, take the declaration's constraint node text and decide the form the way
  B60.6c does, rather than dropping to the arbitrary form.
  (c) `deeplyNestedMappedTypes` and (d) `conditionalTypeAssignabilityWhenDeferred` are
  genuinely NEW emissions, not chain problems — `Type 'any[]' is not assignable to type
  'T'` and `Type 'Q' is not assignable to type 'InferBecauseWhyNot<Q>'`. Both are targets
  that only became checkable once the scope resolved them, and both are M3-depth (a
  mapped-type return and a DEFERRED conditional type, which tsc relates under rules we
  do not model). Expect these two to need a gate of their own — the round-431e foreign-TP
  gate is the family precedent — and note they are also the two most likely to appear on
  the profiles, so `--listAll` ×8 is mandatory before landing.
- [x] **(M3.0-gap-4) DONE (rounds 702/703) — `readonlyRestParameters` passes and is
  un-deferred.** Two rules, both narrower than they first look. **TS2556:** an unbounded
  array spread into a fixed-arity call cannot be arity-checked, so tsc rejects it — with
  four narrowings that each came from a red test rather than from reasoning (a TUPLE
  spread is legal; an ARRAY LITERAL spread is legal, tsc counting `...[6, 7]` as two
  arguments; spreading INTO a rest parameter is legal; and an already-too-many call
  reports the COUNT instead). A rest parameter's type does not resolve in the arg-count
  pass, so the operand is classified from its ANNOTATION when the resolved type is
  unavailable, which also handles `readonly string[]` for free. **TS2554:** a rest
  parameter annotated with a fixed TUPLE has fixed arity, and a tuple-typed spread
  argument contributes its element count. The trap that made round 702's first attempt
  inert: the excess anchor is an ARGUMENT INDEX, not the expanded count —
  `emitTS2554TooMany` opens with `if (firstExcessIdx >= args.size) return`, so passing a
  count of 2 with 2 arguments returned silently.
- [ ] **M3.5 Per-file scopes** (Blocker #3: stop merging all file locals into
  `globals`; per-file scope construction with explicit import visibility). Revisit
  before v1 ONLY if dashboard FPs trace to cross-file scope conflation on tsc sources.
- [ ] **M4.1 Full nodenext resolution**: package.json `exports`/`imports` maps,
  symlink/realpath (pnpm layouts), `typesVersions`, package self-references. (The tsc
  repo itself uses relative imports + @types — unused for v1.)
- [ ] **M4.2 Real declaration emitter.** `.d.ts` output for arbitrary code (the corpus
  strips most `.d.ts` sections, so almost none exists today; `declaration: true` is
  table stakes for "any project"). Test bed: conformance decl baselines + self-compile
  d.ts diffing. Pull into v1 only if the owner defines "fully compile tsc" to include
  declaration output.
- [ ] **M4.3 JSX end-to-end** (`jsx: react-jsx`/`react`/`preserve` transforms on real
  React-shaped code).
- [ ] **M4.4 Sourcemaps — the parenthetical "inline maps exist" is STALE (checked
  round 695): NOTHING generates map content.** `grep sourceMappingURL` over
  `src/commonMain` hits only `TypeScriptCompiler.kt`'s option-conflict validation
  (TS5053 for `mapRoot`/`sourceMap` with `inlineSourceMap`), and `Emitter.kt` has
  no mappings emitter at all. `BaselineFormatter` takes `sourceMap`/
  `inlineSourceMap`/`sourceRoot`/`mapRoot` parameters, which is presumably where
  the belief came from — those shape the BASELINE layout, not the output. So this
  is a full implementation (segment tracking through the transformer, VLQ
  encoding, the `//# sourceMappingURL=` trailer, sidecar `.js.map` writing), not
  the small "also write the file" task the entry implied.
- [ ] **M4.5 Decision point**: project references / composite / incremental scope
  (tsgo supports them; needed for large monorepos — decide build vs defer with owner).
- [ ] **M4.6 `package.json "type": "module"` module-format detection in
  `ProjectCompiler`** (found compiling zod, 2026-07-07): under `module: NodeNext`
  with a `"type": "module"` package.json, real tsc emits ESM but we emit CJS — the
  `collectPackageJsonTypes` machinery exists only for the multi-file TEST-source path
  and is not wired into the on-disk project pipeline. Repro: zod (see M4.7); the
  emitted CJS only runs in a `"type": "commonjs"` context. Unused for v1 (the
  tsc-source bench project has no package.json → CJS default is correct there).
- [ ] **M4.7 zod as a second dashboard profile** (validated 2026-07-07, round 432
  session note): shallow-clone `github.com/colinhacks/zod`, compile
  `packages/zod/src` (107 files, ~31k LOC) via a `tsconfig.xtsc.json` extending zod's
  real `.configs/tsconfig.base.json` (strict, exactOptionalPropertyTypes,
  noUnusedLocals, NodeNext), include `src/**/*.ts`, exclude tests/benchmarks — real
  tsc 6.0.3 reports 0 errors on it, so every xtsc diagnostic is an FP. Baseline
  2026-07-07: 1,665 FPs (top: TS7006×447 contextual params, TS2694×284 namespace
  members via `export *` barrels, TS7029×211 switch-fallthrough, TS2344×182), 0
  crashes, all 107 files emit, output passes a runtime smoke test. Complements the
  tsc-source profiles: stresses generic method chaining + noFallthroughCasesInSwitch,
  which tsc's own source doesn't.

### Offline asset inventory (verified 2026-07-02)

- `typescript-repo` object DB is complete (sparse checkout, full objects): any
  `src/**` path extractable via `git archive HEAD <path>`; `src/lib/` holds the 110
  real lib `.d.ts` files; `tests/cases/conformance/` holds 5,907 `.ts`/`.tsx` cases.
- Node/tsc/tsgo are NOT currently installed — differential testing (M0 optional) and
  real `@types/node` (M1.3) wait for network.
- The benchmark project cache lives under `build/bench/` (cheap to rebuild); results
  TSVs under `bench/` (gitignored, machine-specific).
