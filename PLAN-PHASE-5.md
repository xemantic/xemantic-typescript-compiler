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

**Round 878 (2026-08-11) — (PERF.HW.d): THE PER-WORKER CENSUS. THE PARTITION IS NO LONGER THE
LIMITER — DUPLICATED PER-WORKER WORK IS, AND IT IS 40-45% OF THE WHOLE COMPILE, PER WORKER.**

Round 877 fixed a real imbalance and then reasoned that the residue was more of the same, quoting a
3.16x ceiling computed from source lengths. The census says otherwise, and the single observation that
kills the model is this: **at w4 the worker holding `checker.ts` ALONE costs 13,503 ms while a worker
holding 26 files costs 13,160 ms.** If assignment work dominated, those could not be 2.6% apart.

- **THE INSTRUMENT.** `FrontEnd.workerNanos` / `workerFiles` / `workerChars`, printed by `--frontEnd`
  as `slowest/mean` plus a row per worker. Worker `w` writes index `w` and no other, the arrays are
  sized before any thread starts, and nothing reads them until every worker has joined — race-free by
  construction rather than by luck, which is the standard this repo already holds a census to. Three
  stores per WORKER (not per file), maintained unconditionally so a reader need not arm the probe.

- **THE TABLE** (cold, compiler profile, `--frontEnd --workers N`):
  w2 — 15,530 / 15,512 ms, **100%**;
  w4 — 13,503 / 13,164 / 13,168 / 13,160 ms, **101%** (w0 = `checker.ts` alone, 3,151 k chars; the
  others 25-26 files and 2,275 k chars each);
  w6 — 15,885 vs ~11,650 ms, **128%** — the one level at which the indivisible giant binds.

- **THE DECOMPOSITION.** Fitting `worker = D + k x assigned chars` across the levels gives
  **D = 9.4-11.2 s** against a ~24.6 s sequential compile. D is the work every checker does regardless
  of `assignedFileNames`: the full re-bind of all 78 files plus the ~318 program-wide collectors.
  **That caps file-level parallelism near 2.4x no matter how many cores are added**, which is the
  plateau the ladder has been showing all along. Round 877's 3.16x was the ceiling of the ASSIGNMENT
  and the assignment is not what is left — a ceiling computed over the wrong term.

- **A SECOND, INDEPENDENT REASON NOT TO SPEND WORKERS PAST 4:** the SAME single file (`checker.ts`,
  nothing else assigned) costs **13,503 ms at w4 and 15,885 ms at w6**. Past 4 workers the run
  contends on 8 cores and the CRITICAL worker gets slower — an effect no partition can answer, and
  the mechanism behind the "N-growing overhead" both this ladder and round 826's showed as a rising
  fitted R. Peak RSS says the same: w1 1,393 MB, w4 1,468 MB (+5%), w6 2,445 MB (+75%).

- **THE EMIT BLIND SPOT, CLOSED FOR THE PARALLEL PATH.** CLAUDE.md records that `--noEmit` makes every
  instrument here blind to transform/emit, and every `--workers` capture ever taken in this project —
  including rounds 876 and 877 — was `--noEmit`. A full `--outDir` build at w1 and at w4 emits **78
  files each and `diff -r` reports no difference**. So the parallel path is now verified on the axis
  that had never been checked, not merely on diagnostics.

- **WHAT THIS MAKES THE NEXT ITEM, WITH A PRICE ON IT.** `docs/parallel-caching.md` Phase 1 — resolve
  the context-free, assignment-independent slice ONCE in the single-threaded prefix, freeze it, share
  it read-only across workers (its Tier 1: "build eagerly in a single-threaded phase, expose as
  read-only Map, share freely") — is worth up to D. That is the largest single number anywhere in the
  parallel path and it is now measured rather than argued. The obligation it carries is also known:
  Tier 3 (`Type.id` allocation, the intern caches, `symbolTypes`) is first-touch-ordered and must stay
  per-worker, so the work is to separate the two tiers, not to share more.

- **GATES.** Suite **14,261 -> 14,263** / 0 failures / 3 skipped = exactly the 2 new census pins.
  `cost_gate.py` +0.00% on all 20 counters. `huge_methods.py --fail-over 0`: 0 over the limit, 679
  classes. Warning-clean.

**Round 883 (2026-08-11) — (PERF.HW.i): ONE SHARED BIND FOR ALL WORKERS — **-5% WARM AT w4, REPLICATED
IN TWO BATCHES WITH THE ROTATION REVERSED, THE TWO ARMS' RUNS COMPLETELY SEPARATE** — AND IT REFUTES A
LAW THIS ARC DOCUMENTED TWO ROUNDS AGO.**

Stage 1 (round 882) established that on an all-module program the checker mutates zero binder-owned
`Symbol`s, so the N independent binds can become one. `--shareBind` does that: the program is bound
ONCE on the caller thread and every worker gets the same `BinderResult`s.

- **THE PREDICTION WAS WRITTEN DOWN FIRST AND IT WAS WRONG.** Rounds 879/880 concluded that concurrent
  duplicated work costs no wall (`wall = D + A/N` whether D runs in one worker or all N), so the KDoc
  said, before any measurement, that this would buy **zero** from the bind itself and that a win would
  instead be a measurement of the +37% contention term. It won.

- **THE MEASUREMENT.** `BenchMain <prof> 6 8 off noEmit workers4 shareBind`, one JVM per arm (round
  867), ABBA, two batches with the rotation REVERSED between them so the leading-position bias lands
  on the other arm the second time:
  batch 1 — on 3,364.2 / 3,435.2 against off 3,559.2 / 3,564.5 = **-162 ms (-4.6%)**;
  batch 2 — on 3,244.6 / 3,394.6 against off 3,491.8 / 3,557.4 = **-205 ms (-5.8%)**.
  **All four `on` runs are faster than all four `off` runs** — the ranges do not overlap — which is a
  stronger statement than either batch's median, and it is what round 858 asks for (one
  sign-consistent batch is not a result; the second batch is).

- **WHY THE LAW FAILED, AND THE REFINEMENT IT NEEDS.** "Concurrent duplication is free" assumes the
  concurrency is free, i.e. that cores are abundant. They are not: four binder threads run alongside
  four JIT compiler threads on 8 cores (CLAUDE.md's own note that a "single-threaded" run already
  occupies ~4.17 of them), so four simultaneous whole-program binds cost MORE wall than the 515 ms one
  bind costs, and replacing them with a single serial bind returns the difference. **So the law is
  `wall = D + A/N` only while N workers doing D concurrently costs the same wall as one — which is
  exactly the assumption the +37% per-worker overhead says is false here.**

- **SOUNDNESS, AND WHY IT IS OPT-IN.** Sharing is safe only while nothing merges a PROGRAM symbol into
  `globals`, which INV.3(d) guarantees for an all-module program and not otherwise; a program with
  global script files mutates binder output, and two checkers over one bind would then corrupt each
  other silently. Id safety is by construction: the shared bind runs on the CALLER thread, so its
  symbols come from the ordinary low sequence, below every worker's rebased slice (>= 1e9), and can
  collide with none of them. Correctness verified three ways — 3 `--listAll --workers 4 --shareBind`
  runs at 46 errors and digest `59d930db…`, identical to every other arm this session; a suite
  equivalence pin over an all-module fixture; and the full corpus.

- **WHAT WOULD MAKE IT DEFAULT, AND IT IS NOT DONE:** a SHAPE GATE that decides, before checking,
  whether any program symbol would merge into `globals`. The soundness predicate lives inside the
  checker's merge, so the gate has to reuse it rather than re-derive it — and a re-derived predicate
  that is wrong in the permissive direction corrupts silently. Queued, with the -5% attached.

- **GATES.** Suite **14,267 -> 14,268** / 0 failures / 3 skipped = exactly the 1 new pin.
  `cost_gate.py` +0.00% on all 20 counters. `huge_methods.py --fail-over 0`: 0 over the limit, 686
  classes. Warning-clean. The flag defaults OFF, so every other gate measures the unchanged path.

**Round 882 (2026-08-11) — (PERF.HW.h): STAGE 1 **CLOSED**, AND THE ANSWER INVERTS THE CODE'S OWN
COMMENT — ON AN ALL-MODULE PROGRAM THE CHECKER MUTATES **ZERO** OF THE 15,580 BINDER-OWNED `Symbol`s.**

Round 881 sized `mergeSingleSymbol` at 406 adoptions / 175 mutations and left the real question open:
that census watches ONE site, and `Checker.kt` has **150 others** — `flags` 4, `valueDeclaration` 30,
`members` 19, `exports` 23, `parent` 11, `declarations.add` 63 (`Symbol.target` is written **0** times;
it moved to the LinkStore side table). A grep cannot classify them, because it cannot tell a
binder-owned receiver from a checker-minted one.

- **THE INSTRUMENT IS THE OPPOSITE OF AN AUDIT.** `--bindMutationCheck` fingerprints every `Symbol`
  reachable from the `BinderResult`s — locals + `nodeToSymbol`, recursing through `members`/`exports`,
  identity-keyed (sound because `Symbol` is a plain class, not a `data class`, so round 471's
  deep-`hashCode` hazard does not apply) — immediately before the `Checker` constructor, which is where
  the whole check runs, and re-compares after. It sees every write site, including ones nobody grepped.

- **THE RESULT.** `binder Symbols checked 15580, changed 0` on every field, in the same run as
  `mergeSingleSymbol: adopts 406, mutates 175`. **Both are true because the merges land on LIB
  symbols** (`bindRealLibs` / `init:mergeLibGlobals`), which are in no `BinderResult`. Program-file
  binder output is ALREADY immutable with respect to the checker — the `PartitionCheck` comment that a
  worker "must never reuse an already-checked bind" is true of the lib tables, not of the program's.

- **THE ZERO HAS A POSITIVE CONTROL, WHICH IS THE ONLY REASON IT IS WORTH ANYTHING** (round 849: a zero
  from a blind instrument reads exactly like a real negative). The pin drives two GLOBAL SCRIPT files —
  no import, no export, so neither is a module — declaring the same name, which forces a merge onto a
  program symbol, and asserts `declarationsChanged > 0`. It passes.

- **THE SCOPE IS THE WHOLE CAVEAT AND IT IS NOT A FOOTNOTE.** The zero holds because every file on that
  profile is a MODULE and INV.3(d) keeps a module's locals out of `globals`, so nothing merges. A
  program with global script files DOES mutate binder output — that is what the control demonstrates.
  So sharing one bind across workers is sound **for an all-module program with no further work**, needs
  stage 2 first otherwise, and — the useful part — **the condition is cheap to test at runtime**, so a
  shared-bind path can be GATED ON THE PROGRAM'S SHAPE instead of blocked on the refactor. That gate is
  the recommended next step and is far smaller than stages 2-4.

- **GATES.** Suite **14,265 -> 14,267** / 0 failures / 3 skipped = exactly the 2 new pins (the positive
  control, plus a negative control that the arm is silent unarmed). `cost_gate.py` +0.00% on all 20
  counters. `huge_methods.py --fail-over 0`: 0 over the limit, 684 classes. Warning-clean.

**Round 881 (2026-08-11) — (PERF.HW.g): THE BIND-SHARING REFACTOR OPENED, AND STAGE 1's CENSUS SAYS THE
BLOCKER IS **406 OBJECTS**, NOT A SYMBOL GRAPH.**

Owner-directed (the alternative on the table was flipping the `--workers` default; the owner chose this
instead). Design record: `docs/parallel-bind-sharing.md`.

- **THE BLOCKER, EXACTLY.** `Checker.mergeSingleSymbol` has two branches and both touch binder-owned
  state. The `else` **ADOPTS** — `target[name] = symbol` puts the BINDER's own `Symbol` object into
  `globals`, which is why `PartitionCheck` documents that a worker must never reuse an already-checked
  bind: a bind is not read by a checker, it is CONSUMED by one. The `if` **MUTATES** it in place, and
  `declarations.addAll` is not idempotent, so a second checker over the same tables appends duplicates.

- **THE CENSUS (`--mergeCensus`, counters on `FrontEnd`).** On the compiler profile:
  **adopts 406, mutates 175, of which 164 reach an adopted symbol, declarations appended 175** —
  against the ~105 k symbols a worker mints. **The reason is already in the tree**: INV.3(d) retired
  the merge for module-only names, so only genuinely global names reach `globals` at all and every
  module file's locals are never merged, never adopted, never mutated. So stage 2 (copy on adoption)
  is a surgical change to one `else` branch and stage 3 touches 164 sites' worth of state — **far
  smaller than the design doc's own § 3 warning implies**, though § 3 still applies in full, because
  the risk was never the size of the population but that those 406 are exactly the objects whose
  identity `globals` hands out (id-keyed caches, INV.3(c)'s merged-instance invariant,
  `canonicalEnumSymbol`'s frozen verdicts).

- **WHAT THE CENSUS DOES NOT ANSWER, AND STAGE 1 IS NOT CLOSED UNTIL IT DOES.** It watches
  `mergeSingleSymbol` alone. Whether the checker mutates a binder-owned `Symbol` anywhere ELSE is a
  separate question and a bind cannot be shared until it is answered no.

- **THE PRICE, RESTATED SO NOBODY RE-DERIVES IT OPTIMISTICALLY** (`docs/parallel-bind-sharing.md` § 2):
  sharing alone buys **zero wall** — round 879/880's law, a per-worker fixed cost hoisted to the serial
  prefix leaves `F + A/N`. The prize is (a) parallelising the single shared bind, **~386 ms at N=4**,
  available only once it is shared, and (b) an UNMEASURED fraction of the +37% contention term
  (~526 ms at N=4) from four workers no longer each building a full symbol graph. **13-29% of a warm
  rebuild, of which only (a) is currently justified by measurement.**

- **TWO TRAPS PAID FOR IN THIS ROUND.** The adopted-id set was first declared beside its own helper
  ~9,600 lines down and was therefore **null throughout every init pass** — CLAUDE.md's documented
  Kotlin ordering trap, whose tell is an impossible NPE on a non-nullable `val` (here from
  `bindRealLibs`); it now sits next to `globals`. And the new flag went into the usage text and
  `ACCEPTED_FLAGS` in the same edit, because round 874 showed an undocumented flag fails
  `CliModeRestoreTest` BEFORE its `ledger.restore()` and leaves every swept mode armed for the rest of
  the test JVM.

- **GATES.** Suite **14,263 -> 14,265** / 0 failures / 3 skipped = exactly the 2 new pins (the census is
  reached and its branches are consistent; plus a negative control that the counters do not move when
  it is not armed — round 849's law, a zero from an unreached hook reads like a real negative).
  `cost_gate.py` +0.00% on all 20 counters. `huge_methods.py --fail-over 0`: 0 over the limit, 681
  classes. Warning-clean.

**Round 880 (2026-08-11) — (PERF.HW.f): THE PARALLEL PATH IS DECOMPOSED AND ITS FLOOR IS NAMED.
`wall(N) = 1,447 ms + 7,717/N` WARM. THE 1,447 IS A **PER-WORKER FIXED COST THAT SHARING CANNOT
REDUCE** — ONLY DOING LESS WORK CAN. GC AND THE JIT ARE BOTH MEASURED AND BOTH ACQUITTED.**

Round 879 left one number to explain: a worker appeared to run its own assigned work ~50% slower than
the same work runs sequentially. Three candidate causes, taken in order of cheapness.

- **GC: ACQUITTED.** `-Xlog:gc` totals 734 ms over 32 events at w1 (3.1% of the wall) and 1,338 ms over
  36 at w4 (9.0%) — real, roughly doubled, and nowhere near the effect. The decisive test is that it is
  **heap-INSENSITIVE**: `-Xmx4g` 14,896 ms against `-Xmx8g` **14,865 ms**, a 0.2% difference. Doubling
  the heap of an allocation-bound workload does something; this one does not notice.
- **JIT / core saturation: ACQUITTED, and this was the leading hypothesis.** CLAUDE.md records that a
  "single-threaded" xtsc run already occupies ~4.17 of 8 cores because `CICompilerCountPerCPU` gives 4
  compiler threads, so cold, 4 workers plus 4 JIT threads oversubscribe — which would make the effect a
  COLD artefact. It is not: WARM, after six rebuilds, the per-worker rows still read w0 **3,892 ms**
  (1 file, 3,151 k chars) against 3,157-3,310 for 25-26 files and 2,275 k chars each.
- **WHAT IT ACTUALLY IS.** Fitting those two warm points — `worker = fixed + r x assigned chars` —
  gives **r = 0.776 ms per k-char** and **fixed = 1,447 ms**. Against the sequential run (7,078 ms
  instrumented, bind 515) the same fit gives a pure rate of **0.565 ms per k-char**, so the per-char
  contention overhead is **+37%**, not 50%; the rest of round 879's apparent 50% was the fixed term
  being mistaken for a rate. The fixed 1,447 ms is the re-bind (**515 ms**) plus ~**930 ms** of
  program-wide checker work every worker performs regardless of assignment.

**THE FLOOR, AND WHY `docs/parallel-caching.md` PHASE 1 CANNOT MOVE IT.** `wall(N) = 1,447 + 7,717/N`
warm: N=4 predicts 3,376 ms against 3,035-3,232 measured, and N -> infinity gives **1,447 ms, a 4.35x
ceiling** that contention will keep well out of reach. **A per-worker fixed cost cannot be shared away**
— round 879's arithmetic applies to it exactly: hoisting F to the serial prefix leaves `wall = F + A/N`,
identical. Resolving the lib slice once and freezing it (Phase 1's proposal) therefore buys **zero
wall**, however redundant the N copies look. The only things that move the floor are (i) making the work
partition-scoped, or (ii) doing less of it absolutely — and (ii) is ordinary single-thread optimisation,
i.e. the (WARM.*) arc, not a concurrency lever at all.

**SO THE CONCURRENCY ARC IS AT ITS USEFUL LIMIT ON THIS PROGRAM**: warm w4 is **1.95x** and the
remaining parallel headroom is bounded by a floor whose two components are a re-bind that needs the
`Checker`-does-not-mutate-binder-output work (parallel-caching.md's Phase 0/2, a large architectural
item) and ~930 ms of program-wide passes that would have to be made partition-scoped one at a time.
Neither is a scheduling change; both should be priced against the same instruments before being started.
No source change this round — measurements only, so no gates were required.

**Round 879 (2026-08-11) — (PERF.HW.e) RETRACTED BEFORE IT WAS BUILT, AND ROUND 878's D IS WRONG. THE
TAIL PASSES ARE ALREADY PARTITION-SCOPED; HOISTING THEM WOULD HAVE COST ~2.4 s. THE REAL LOSS IS THAT A
WORKER RUNS ITS OWN WORK ~50% SLOWER THAN THE SAME WORK RUNS SEQUENTIALLY.**

The item below said: the ~400 tail passes are program-wide, every worker runs all of them, so run them
once in the sequential prefix and share. Every clause was checked before anything was built, and the
conclusion does not survive. Nothing was landed this round; that is the result.

- **THE ARITHMETIC THAT SHOULD HAVE COME FIRST.** Duplicated work that is CONCURRENT does not cost
  wall — `wall = D + A/N` whether D runs in one worker or in all N — so hoisting D to the SERIAL
  prefix gives `wall = D + A/N` too: unchanged at best, and strictly worse to the extent the hoisted
  work was already partition-scoped. CLAUDE.md states the general form ("the duplication is concurrent,
  so it costs CPU and RSS, not WALL") and round 788 states the law ("skipping a cached resolution MOVES
  the work"). The entry reasoned from a COUNT of duplicated passes and never asked what removing them
  returns — round 758's error, one region over.

- **THE MEASUREMENT** (PassLab, no recompile — `disable` for all 445 non-spine non-init passes).
  Sequential 24,600 -> 20,258 ms, so the tail is **4,342 ms** of a sequential compile. At w4, ABBA x2:
  ON 15,723 / 16,190, OFF 12,398 / 14,098 — **~2.7 s** returned for removing 4.3 s of work, with the
  OFF arm spreading 12.8%, so 1.3-2.7 s. A fully-duplicated tail would return 4.3 s and a fully
  partition-scoped one ~1.1 s; the per-worker rows say which: with the tail off, w0 (1 file) drops
  1,194 ms while w1 (26 files) drops 3,651 — the tail is ~**1.1 s duplicated per worker plus ~98 ms per
  ASSIGNED FILE**. **Hoisting it would have added 4.3 s to the serial prefix to save ~1.9 s per worker:
  a net ~2.4 s LOSS.**

- **ROUND 878's D = 9.4-11.2 s IS RETRACTED.** That fit took its points from DIFFERENT worker levels,
  and contention differs between them (the same single file costs 13,503 ms at w4 and 15,885 ms at w6),
  so the cross-level slope absorbed the contention and dumped it into the intercept. Fitting WITHIN one
  level, on the quieter tail-off run, gives **D ~ 3.7 s**, and "each worker redoes 40-45% of the whole
  compile" is wrong. What survives is the OBSERVATION it was drawn from — a worker holding one file
  costs nearly what a worker holding 26 costs — whose cause is the tail's per-file term plus
  contention, not a large duplicated core.

- **WHAT THE NUMBERS DO SAY, AND IT IS A BIGGER TARGET THAN THE ONE RETRACTED.** `checkSpine` is
  essentially fully partition-scoped: with the tail off, w0 and w1 spend 10.5 and 7.7 s of non-bind
  work on 3,151 k and 2,275 k assigned chars, a ratio of 1.36 against a char ratio of 1.385.
  Sequentially the spine runs at 2.02 ms per k-char, so w0's 3,151 k should be ~6.4 s, plus ~1.8 s of
  bind = ~8.2 s, against **12.3 s measured**. **Each worker runs its own assigned work ~50% slower than
  the same work runs sequentially** — that gap, not duplication, is the largest loss in the parallel
  path, it is what makes the fitted R rise with N in every ladder taken here, and it is what four
  workers each building a complete symbol+type graph would predict (peak RSS 1,393 -> 1,468 MB at w4,
  2,445 at w6).

- **SO THE NEXT ITEM IS ALLOCATION/CONTENTION, NOT SHARING — AND IT MUST BE MEASURED BEFORE IT IS
  DESIGNED**: GC time and allocation rate per worker level (`-Xlog:gc`, or JFR allocation events),
  then decide. Do NOT re-queue a "share the collectors" item off a count of `binderResults` loops: 321
  of them is a fact about the source and says nothing about what removing them returns.

The retracted entry follows, kept so the reasoning that failed is on the record.

**(PERF.HW.e) — RETRACTED, SEE ABOVE. RUN THE PROGRAM-WIDE TAIL PASSES ONCE, NOT ONCE PER WORKER.**

Follow-on scoping for round 878's D (9.4-11.2 s of duplicated per-worker work). Counted in
`Checker.kt`: **321 loops over `binderResults`** (program-wide — every worker runs all of them) against
**190 over `checkedResults`** (the INV.6 partition view, assignment-scoped). Sequential `--passTiming`
on the compiler profile: `checkSpine` **20,128 ms**, then a FLAT tail — `init:buildFileLocalTypeMaps`
443, `init:trackAllImportReferences` 104, and everything else 94 ms down to 48 ms.

The arithmetic that scopes the work: the tail is ~4.5 s of a ~24.6 s compile and **every worker runs
all of it**, the per-worker re-bind is a further ~1.4-1.8 s, and those two do not sum to D — so a
program-wide REMAINDER inside `checkSpine` carries the rest. Three components, in the order their
price is known:

1. **The ~400 tail passes (~4.5 s, in EVERY worker).** Round 801 already characterised them: flat,
   largest 75 ms, and "only 2 of 400 call `getTypeOfExpression` while none narrows — they are
   traversals, not type-system work". A traversal of an immutable program is Tier 1 by
   `docs/parallel-caching.md`'s taxonomy, i.e. exactly what that document says to build once in a
   single-threaded phase and share read-only. Running them once would take ~4.5 s out of every
   worker's D.
2. **The per-worker re-bind (~1.4-1.8 s).** Blocked by the stated reason `Checker` init mutates the
   symbols it is given; unblocking it is the immutability work, not a scheduling change.
3. **The program-wide remainder inside `checkSpine`.** Unmeasured — the first job is to find it, since
   the spine is supposed to be the partition-scoped half.

**The obligation on (1), and it is per-pass, not global:** a pass may be hoisted only if it is a pure
function of the frozen program. Two things disqualify one — reading Tier-3 first-touch state
(`Type.id` allocation, the intern caches, `symbolTypes`), and emitting diagnostics whose SET depends on
`assignedFileNames` rather than merely being filtered by it. So this is a classification job over the
321 collector loops with a per-pass gate, not a switch to flip; the verification is the one already in
use — byte-identical `--listAll` at every worker level plus an emit-mode `diff -r`.

**Round 877 (2026-08-11) — (PERF.HW.c): THE WORKER PARTITION WAS THE LIMITER, AND IT WAS A ONE-LINE
`i % workers == w`.**

Round 876's warm ladder plateaued at ~1.6x and its Amdahl fit gave R = 0.39 / 0.54 / 0.53 / 0.57 at
N = 2/4/6/8 — R rising with N, which is not a serial residue at all but an N-growing overhead. The
census that explains it needs no profiler: the compiler profile's 78 files span three orders of
magnitude and **`checker.ts` is 3,151,774 of 9,977,169 chars = 31.6% of the whole program**, so
round-robin over the crawl's sorted order gave the heaviest bucket **1.90x / 2.28x / 3.32x** the mean
at 4 / 6 / 8 workers.

- **A PARTITION IMBALANCE IS A CEILING, NOT A TAX.** The wall of a parallel phase is the SLOWEST
  worker, so max/mean IS the reciprocal of the best achievable speedup: **2.10x / 2.63x / 2.41x**
  before a single line of checker work is considered. That is also the whole explanation of a result
  this project has recorded three times and never explained — **w8 being worse than w4** (round 826
  cold: 1.242x vs 1.361x). It was never about cores or memory bandwidth; at w8 one bucket drew
  `checker.ts` AND its ordinary share.

- **WHAT LANDED.** `balancedFilePartition`: files in descending size, each to the currently lightest
  bucket — longest-processing-time-first, which is within 4/3 of optimal for any input. On this
  profile it takes the ceiling to **3.16x at every level >= 4**, and 3.16x is then the REAL ceiling of
  file-level parallelism here, since one file being 31.6% of the input means no assignment whatever
  beats 1/0.316. Raising it further is not a scheduling problem — it needs sub-file parallelism.

- **MEASURED, WARM** (`BenchMain <prof> 6 8 off noEmit workers<N>`, one JVM per level, round 867):
  w4 **4,112.1 -> 3,232.1 ms**, i.e. **1.531x -> 1.947x** against the 6,293.7 ms sequential median;
  w6 3,846.3 -> 3,478.9, i.e. 1.636x -> 1.809x. The two arms' ranges do not overlap at w4
  (3,067-3,748 against 3,808-4,459). **The optimum moved from 6 back to 4** — balancing removed the
  reason to spend more workers.

- **MEASURED, COLD** (median of the standard capture): w2 21,254 -> 17,004, w4 19,980 -> **15,438**,
  w8 23,562 -> 20,022 ms. Round 876's change moved nothing cold and this one moves 23%, which is the
  expected asymmetry: a JIT-warming effect can be paid back by the ramp, a partition cannot.

- **THE tsgo ANCHOR, TAKEN IN THE SAME SESSION ON THE SAME BOX** rather than inferred from
  cross-round ratios: tsgo 7.0.0-dev.20260707.2, `--noEmit -p <profile>`, 5 runs, median **1,740 ms**
  (65 of its own diagnostics — a different compiler's completeness, not a correctness comparison).
  The gap goes **3.62x** (warm sequential) -> **2.36x** (w4 round-robin) -> **1.86x** (w4 balanced).
  Said plainly: tsgo pays full process startup inside its 1,740 ms while xtsc's 3,232 ms is a warm
  in-JVM rebuild, so this comparison already flatters xtsc, and the cold-CLI gap is ~8.9x.

- **TWO PROPERTIES THE CODE DEPENDS ON, BOTH PINNED.** (i) The order is TOTAL — size descending,
  ties on `fileName` — so the assignment is a pure function of the program; a partition that depended
  on iteration or scheduling order would make diagnostics depend on it, and byte-identical output is
  this project's entire verification method. (ii) Every file lands in exactly one bucket: a dropped
  file is a silently MISSING diagnostic, which no baseline would catch on a fixture the partition
  happens to cover. `BalancedFilePartitionTest` asserts coverage, disjointness, bucket count,
  order-independence (forward, reversed and re-sorted inputs agree), the equal-size tie case, and the
  balance claim as a strict inequality against the round-robin it replaced.

- **SOURCE LENGTH IS A PROXY AND IS DOCUMENTED AS ONE.** It carries no information about which files
  are type-heavy; it is used because it is exact, free (the text is already in hand) and available
  BEFORE any checking happens, which a true cost measure is not. A wrong proxy costs balance, never
  correctness — so a better weight (node count, or a recorded per-file check time) is a pure
  improvement whenever someone wants it.

- **CORRECTNESS.** 10 `--listAll` captures at w1/w2/w4/w8 — all 46 errors, all md5
  `59d930db849399aea5e03e25fedb8e4e`, identical to both pre-change arms.

- **GATES.** Suite **14,255 -> 14,261** / 0 failures / 3 skipped = exactly the 6 new pins.
  `cost_gate.py` +0.00% on all 20 counters. `huge_methods.py --fail-over 0`: 0 over the limit, 679
  classes. Warning-clean.

**Round 876 (2026-08-11) — (PERF.HW.b): THE SEQUENTIAL BIND A PARALLEL COMPILE WAS PAYING FOR AND
NOBODY READ — AND THE INSTRUMENT THAT MAKES WARM CONCURRENCY MEASURABLE AT ALL.**

`cpcBindAndCheck` opened with `val binderResults = parsedSourceFiles.values.map { binder.bind(it) }` and
only THEN branched on `ParallelCheckMode.workers > 1`. That value is read in the sequential branch and in
no other place: the parallel branch builds one `Binder` per worker and binds the whole program again on
each worker thread — which it must, since `Checker` init mutates the symbols it is handed, so N checkers
cannot share one bind. A `--workers` compile therefore paid a whole extra whole-program `Binder.bind`,
serially, before any worker started.

- **WHY NO GATE COULD SEE IT.** The redundant bind's `BinderResult`s were DROPPED, so it changed no
  diagnostic, no emitted byte and no deterministic counter — `cost_gate.py` reads +0.00% on this change
  in both directions, correctly, because the gate exercises the SEQUENTIAL path where the change is a
  strict no-op. The only observable was the wall, and 515 ms warm sits under the spread of a parallel
  compile. **So the pin is a count, not a time**: `FrontEnd.sequentialFileBinds`, assigned once per
  compile from the caller thread (never from a worker — race-free by construction) and asserted exactly:
  2 for a two-file sequential compile, **0** for the same compile at `workers = 4`. Round 868's law —
  an assertion over a timed region is a coin flip, one over a recorded count is a fact.

- **THE PRICE, MEASURED WARM, WHICH IS THE ONLY REGIME WHERE IT IS A PRICE.** `BenchMain <prof> 6 8
  frontend` (sequential): median rebuild **6,293.7 ms**, `bind (all program files)` **515 ms**.
  `BenchMain <prof> 6 8 frontend noEmit workers4`: median **4,112.1 ms**, bind row **0 ms**, front end
  17 ms = 0% of the total. Both 78 files / 46 errors. That is **1.531x** for w4 warm, where round 826
  measured **1.361x** cold — and the deleted 515 ms is ~11% of the warm parallel rebuild.

- **THE COLD ARM MEASURED NO GAIN AND IS RECORDED AS SUCH.** Ten `--listAll` runs per arm: w4 medians
  19,518 (before) vs 19,980 ms (after), w2 21,315 vs 21,254, w8 23,332 vs 23,562 — two NON-interleaved
  batches, so round 858's law applies and the honest reading is "no effect this instrument can see".
  The mechanism worth stating: cold, the discarded bind was also the thing that WARMED `Binder` /
  `FlowGraphBuilder` for the workers, which now meet that code cold on four threads at once. This is
  (JIT.1) in its sharpest form — a cold A/B is blind to a warm-path effect, and here it is worse than
  blind, because it can show the removal of real work as a wash.

- **CORRECTNESS.** 20 `--listAll` captures — w4 x5, w2 x2, w8 x2, w1 x1, on both binaries — every one
  46 errors and every one md5 `59d930db849399aea5e03e25fedb8e4e` under the documented
  `grep 'error TS' | sort` recipe. That is also the `--workers` distribution gate CLAUDE.md requires
  (>= 5 runs per level; one capture is a coin flip).

- **THE UNBLOCKER, AND IT OUTLIVES THIS FIX.** Every `--workers` figure in this repo (rounds 740, 824,
  826) is COLD; every warm figure in `docs/perf` is SEQUENTIAL. The two regimes had never met, so no
  concurrency change could be priced in the regime the daemon actually runs in. `BenchMain` now takes a
  6th argument `workers<N>` and `ab-warm.sh` a `WORKERS` env knob. Both set the level ONCE per process,
  deliberately: round 867's shared-branch-profile law is sharper here than anywhere it was written
  about, since two worker levels in one JVM share every compiled method in the binder and the checker,
  so whichever ran first writes the profile the other is compiled against. Comparing LEVELS is two
  invocations, never two arms of one run. The argument is `workers<N>` rather than a bare integer for
  the round-863 reason: a bare number in slot 6 is what a shifted argument list produces, and a harness
  that accepted it would publish a parallel median under a run everyone reads as sequential.

- **NOT DONE, AND NEXT.** No paired warm A/B against the pre-change binary — what is measured is the
  attribution (a 515 ms serial row going to zero, plus the two warm medians), which is not a paired
  delta. That is the next iteration's first job, and the harness for it now exists.

- **GATES.** Suite **14,252 -> 14,255** / 0 failures / 3 skipped over all five modules = exactly the 3
  new `ParallelSequentialBindSkipTest` pins. `cost_gate.py` +0.00% on all 20 counters.
  `huge_methods.py --fail-over 0`: 0 over the limit, 677 classes. Warning-clean build.

**MOD.7 (2026-08-10) — OWNER-DIRECTED MODULE SPLIT: THE GRAALVM IMAGE STOPS CARRYING A DAEMON IT CAN NEVER
BE.** The image was built by `:xemantic-typescript-compiler-daemon`, so a one-shot binary dragged ktor-network,
slf4j and `-api` through closed-world analysis. A new module `:xemantic-typescript-compiler-cli` holds a LEAN
entry point and the `nativeImage` task; **jvmRuntimeClasspath 23 → 15 modules** (gone: `io.ktor:ktor-network` /
`ktor-io` / `ktor-utils` + their `-jvm` variants, `org.slf4j:slf4j-api`, `:…-api`).

- **THE HAZARD IS NOT "MISSING FEATURE", IT IS A SILENT WRONG SUCCESS, AND THAT IS WHY THE ENTRY POINT IS NOT
  `…compiler.MainKt`.** The compiler's argument loop ends in `else -> if (!a.startsWith("-")) o.project = a`,
  so an unknown flag is ignored while its VALUE becomes the project: round 840 measured the image answering
  `--serve --socket /tmp/x.sock` by binding no socket, compiling the socket path, **emitting 173 files and
  exiting 0**. `…compiler.cli.MainKt` therefore REFUSES `--serve` / `--daemon` / **`--socket`** — the third one
  because alone it is the same failure minus the flag that makes it obvious — with exit **2** (the value of
  `XTSC_REFUSED`, duplicated rather than imported because `-api` exports ktor) and delegates everything else to
  `runCli` verbatim.

- **THE THREE SHIPPED ENTRY POINTS NOW DELIBERATELY DISAGREE**, so round 840(b)'s "pin them equal" shape had to
  be INVERTED rather than kept: `scripts/xtsc` + `scripts/xtsc-aot` stay on the dispatcher (they have a daemon
  to reach), the image is the lean CLI, and neither may be the bare `MainKt`.
  `AotCacheGuardTest.the native image is built from the server dispatcher` is replaced by
  `…is not built from this module` (no CODE line there may mention `nativeImage`), and the CLI module's
  `NativeImageEntryPointTest` is **strictly stronger than the pin it succeeds** — it RESOLVES the class named in
  `build.gradle.kts` and checks for a `public static main`, which a string comparison cannot.

- **FOUR SINGLE-MISTAKE ABLATIONS, EVERY ARM A DISTINCT RED SET** (round 807's rule; the harness was committed
  first, round 789). A1 delete the refusal → **6** pins, exactly the refusal ones, negative controls green;
  A2 image entry ← dispatcher → **3** (including `the named entry point exists and is startable`, which fails by
  ClassNotFound — the reflective check earning its place); A3 re-add `api(project(":…-api"))` → **2**, the two
  absence pins; A4 re-add a `nativeImage` line to the daemon build → **1**, uniquely its own.

- **TWO PASSTHROUGHS FOR THE PGO WORK THAT FOLLOWS:** `-PnativeImageArgs="…"` (extra `native-image` arguments,
  before the main class) and `-PnativeImageOutput=name` (so an instrumented and a final image coexist in
  `build/native/`). Both `inputs.property`, and the name also selects the declared `outputs.file`.

- **SAID RATHER THAN IMPLIED.** kotlinx-serialization is **NOT** removed and `LeanClasspathTest` asserts it is
  PRESENT so the claim cannot rot — the core parses tsconfig.json with it. Nothing native was built or measured:
  GraalVM is not on this box, so image size, build time and run time are unknown, and the owner's own PGO run is
  the first execution of the new entry point. What IS newly moot is round 840(b)'s `UnixDomainSocketAddress`
  closed-world question — nothing on this image's classpath opens a socket.

- **GATES: suite 14,234 → 14,252 / 0 failures / 3 skipped** over all five modules (`xml.etree`, wiped results
  dir), the +18 exactly the new module's pins and the daemon unchanged at 66 (one pin replaced by one).
  Warning-clean, verified with a forced recompile (`--rerun-tasks`), which also cost three warnings' worth of
  lesson: `by tasks.registering` is deprecated in Gradle 9.6, so the moved task uses `tasks.register`.
  `cost_gate.py` / `huge_methods.py` **not run and not required** — no `commonMain`/`jvmMain` change in the core.
  `nativeImage` itself was **not run** (owner instruction: no GraalVM builds or benchmarks on this box); verified
  by `--dry-run` with both new properties, and unqualified `./gradlew nativeImage` now resolves to exactly one
  task.

**Round 875 (2026-08-09) — (WARM.22): THE INV.4 REACH MACHINERY, CENSUSED AS ONE POPULATION AND **PRICED
NEGATIVE IN EVERY MECHANISM IT CONTAINS**. Round 874 § 29 handed this over as ONE DESIGN QUESTION rather than a
candidate list — ~338 ms = 5.5% of a warm rebuild over 43 classifiers whose largest is 0.86%, so only a change
to the SHARED mechanism could clear the bar. The three candidate answers are now measured rather than argued,
and none of them survives. `docs/perf/reach-machinery.md`.

- **(A) THE CENSUS FIRST (round 801's order) — the instrument § 29 commissioned.** `ReachCensus`
  (`--reachCensus`, `BenchMain` tier `reach`) counts consults / memo hits / ascents / **EDGE EVALUATIONS** per
  classifier, injected at the two anchors every classifier carries verbatim by `scripts/round875_instrument.py`,
  **which also GENERATES the id table from the same scan** so the counters and the names cannot drift. Per warm
  rebuild: **1,909,715 consultations** (2.23 classifiers per node), **1,740,677 ascents**, **3,324,977 edge
  evaluations**, **memo hit rate 8%**. Deterministic on every rebuild of every process, which is its own
  falsifier. `Tav` reads 15,887 against round 874's 381,670 — a free positive control that the census is
  reading a live post-fix binary.

- **(B) 23 OF THE 43 CLASSIFIERS HAVE A MEMO HIT RATE OF EXACTLY 0%, AND THAT IS NOT A DEFECT.** A classifier
  is consulted **at most once per node** — its handler dispatches once — so a node's own status is never asked
  twice and the memo can NEVER answer the query it was written for. Its only job is to TERMINATE AN ASCENT, and
  it does that well: `folds/consult` is **1.74**, not the tree depth. Anyone reading "8% hit rate" as a cache to
  fix should stop there.

- **(C) THE INSTRUMENT WAS WRONG FIRST AND A PIN SAID SO.** The first injection put the fold counter at the tail
  (`folds += chain.size`), which is right for the 36 classifiers whose ascent pushes every node and wrong for
  the five whose ascent evaluates the edge and pushes only when it CONTINUES — there `chain.size` is one short
  per ascent. `folds >= misses` failed on its first run; the counter moved to the edge call itself. **The wrong
  instrument read 2,956,401 edge evaluations, the right one 3,324,977** — a 12% under-count, in the direction
  that would have made this round's negative verdict look stronger than it is.

- **(D) (a) PUSH-BASED STATUS MAINTAINED BY THE WALK — NEGATIVE BY ARITHMETIC, 11.1x.** The obvious answer (the
  spine already knows the path, so keep each classifier's status on a per-depth stack) must compute a status for
  EVERY classifier at EVERY node it descends through, because it cannot know which handler will ask: 43 x
  856,962 = **36.9 M** edge evaluations against the pull scheme's **3.32 M**. Sixteen of the 43 are consulted
  under 1,000 times a rebuild and one is consulted ONCE. At 13.3 ns that is **+447 ms on a family that costs
  361**. The census also shows the pull scheme is already most of a push one: an ascent typically finds the
  parent memoized, which is the state a push scheme would have kept.

- **(E) (c) THE `kindId` TABLESWITCH CONVERSION IS WHAT A STATIC READING WOULD HAVE COMMITTED AN ARC TO, AND IT
  IS DEAD.** Every edge predicate is a `when (parent) { is X -> }` LINEAR `instanceof` chain — `spineAiEdge` 119
  arms, `spineNaEdge` 114, `spineSyEdge` 111, `spineFpEdge` 106, down to 41 — `NodeBase.kindId` exists for
  exactly this dispatch (M0.2) and round 803 split `forEachChild` on that key for **-3.93%, 5/5 pairs**. Three
  million evaluations a rebuild through chains of tens of type tests is a textbook prize. **`--reachAmp N`
  measures 13.3 ns per evaluation** (`r` EXTRA evaluations of the SAME edge on the SAME (parent, child) under
  ONE pair, two values of `r` cancelling the boundary — round 759), so the ENTIRE compute of every edge
  predicate in the family is **3,324,977 x 13.3 ns = 44 ms = 0.67%** — and the decisive part is a RATIO:
  **106 arms costs the SAME as 49** (Ce 11.5 / 13.3 / 14.7, Fp 10.6 / 13.5 / 13.9 / 13.2 ns across two
  processes, ABBA-rotated). The slope in arms is indistinguishable from zero, so a tableswitch cannot take most
  of even the 0.67%.

- **(F) THE FIT'S OWN FALSIFIER DID ITS JOB, AND THE ARITHMETIC ONE TOO.** Seven of the eight r-pairs imply a
  timestamp-pair boundary of **73-132 ns**, inside round 850's independently measured 97-202 ns; the eighth
  implies **1.7 ns**, physically impossible, so that draw is discarded by a criterion that has nothing to do
  with the answer it would have given. And `ampCalls == r * ampBrackets` with `ampSink % r == 0` (each bracket
  contributes 0 or `r`, so a hoisted call breaks the identity) read OK in all eight.

- **(G) (b) PACKING THE 43 PER-FILE nodeId-KEYED MEMOS IS THE ONLY SURVIVOR AND IS STILL <= ~0.8% — QUEUED WITH
  THAT NUMBER ATTACHED.** At 2.23 classifiers per node a transposition (one row of 43 statuses per node) lets
  that many consultations share a cache line instead of taking that many misses, and it removes **36.9 MB** of
  `ByteArray` allocated and zeroed per rebuild. Ceiling is the ~200 ms of Status/ascent/fold/memo bookkeeping,
  of which memo cache misses are at most half. Against that it rewrites the memo access of ALL 43 classifiers,
  every one of which decides whether a diagnostic is CONSIDERED.

- **(H) THE EQUIVALENCE PINS WERE BLIND, AND THE ABLATION IS WHAT FOUND IT (round 813, on schedule).** A5
  forced the const-ENUM classifier to answer UNREACHED whenever the census is armed — the exact hazard this
  family has — and reddened **NOTHING**, because that pass emits nothing on the fixture. The fixture now carries
  one deliberate TS2588 and one TS2693, the equivalence pins assert both codes are PRESENT rather than only that
  two lists agree (two empty lists agree), and the arm targets the classifiers that own them. Separately, A1 and
  A2 first reddened the SAME single pin and a ninth pin was added to separate them rather than counting the
  coincidence as coverage (round 869's rule).

- **(I) A PROCESS NOTE WORTH THE GOTCHA IT EARNED.** The Bash tool caps a command at 10 minutes whatever
  timeout is requested, so the first seven-arm ablation sweep was KILLED mid-arm and left the ablated
  `Checker.kt` in the tree with no marker — round 805's hazard exactly, and the tell was `git status` showing a
  file the round had already committed. Ablation sweeps now run a few arms per invocation.

- **(J) GATES.** Suite **14,229 / 0 failures / 3 skipped** over all four modules (`xml.etree`) = round 874's
  14,220 + the 9 new pins, exactly. `cost_gate.py` **+0.00% on all 20 counters** (46 errors / 78 files);
  `huge_methods.py --fail-over 0` **0 over the limit**, 677 classes. **No 8-profile grid**, and the reason is
  stated rather than assumed: every injected line is guarded by `ReachCensus.on`, which the negative-control pin
  and its own ablation arm (A7) both police, and the two equivalence pins compare full diagnostic-code lists
  with the census armed and disarmed, non-vacuously in both directions. Commits `a637c6eb`, `f4911304` and the
  pin/doc commits.

**Round 874 (2026-08-09) — (WARM.21): THE WARM LEAF PROFILE RE-TAKEN A THIRD TIME. BY ROWS THE VEIN IS
EXHAUSTED — NOTHING NEW REPLICATES ABOVE 1% AND ROWS 21-60 ARE ALL 19-32 ms. BY **FAMILIES** IT IS NOT, AND
THAT READING IS THE ROUND'S REAL INSTRUMENT: the largest thing in the warm compile is the INV.4 migration's
own REACH MACHINERY, **458.8 ms = 6.95% over 66 owners** of which the biggest is 0.57%. Its one coherent
sub-family is TAKEN: the TAV pass runs **381,670 times to emit ZERO diagnostics**, 99% of what it reaches
cannot emit, and a per-file name-candidate gate takes its controlled row **156-159 -> 34-43 ms = 121 ms
(1.84%)**. `docs/perf/warm-leaf-profile.md` §§ 21-29.

- **(A) VALIDITY FIRST, AND IT PASSED ON ALL FOUR PRIOR FIXES.** Same recipe, same window, two processes,
  round 868's and 870's dumps re-aggregated with the same committed script. `checkSpine` INCLUSIVE
  **74.09%/74.05%** against the known ~74% (the `--stack-depth` trap's sanity check). Then:
  `computeTypeParamInfo` **99.9 -> 11.7 ms** (round 870 predicted a 15 ms residue), the four round-868 star
  walks and round-869 copy pushers still at 0.00%. **Round 871's parse cache needed a DIFFERENT reading and
  is the sharpest of the four**: the crawl parses on worker threads, which this instrument filters out by
  design, so it is invisible in the compile-thread table — counted over ALL threads, samples whose stack
  contains `Parser` go **251/285 -> 265/282 -> 0/0**. Not one sample in 16,413.

- **(B) THE ROW READING SAYS "DONE", AND IT WOULD HAVE MISSED THE ROUND.** Nothing NEW is above the 1% floor;
  `ciaMutualFnDecls$resolve` fails replication for the SECOND round running (1.56% vs 0.48% on one binary);
  `objectLiteralSatisfiesAugmentationMergedInterface` has sat at 0.70% / 46 ms through three rounds. So the
  table was aggregated by MECHANISM instead (`scripts/round874_compare.py`), which is what round 868's (C1)
  had been — three rows that were one barrel search — caught then only because they were adjacent and shared
  a prefix. **INV.4 reach classifiers 458.8 ms, scope frame copies 299.8, `cta*` 264.7, name resolution
  213.5, flow build 211.0.** The reach machinery is not checking work: it answers "would the deleted walker
  have visited this node?", and round 732 already said a per-kind dispatch table cannot remove it.

- **(C) THE CENSUS, WHICH DECIDED THE SHAPE (round 801's order).** Per warm rebuild: **381,670** dispatches
  (44.5% of all nodes), **695,014** reach hops, **798,020** parent hops each testing seven node classes,
  **467,085** chain probes — and **`emitted 0`**. **151,137 of 152,636 reached identifiers (99.0%) are
  INERT**: their name is in no visible level's typeOnly/nsOnly set and is not a type keyword, and this pass
  owns only TS2693 and TS2708, both of which require one. **`emitted 0` is also round 793 arriving on
  schedule** — the OFF arm's obvious falsifier (the error count moves) is DEAD on the very profile the prize
  is measured on, because tsc's own sources never use a type as a value.

- **(D) THE WALL IS DISCARDED, NOT QUOTED.** `plain` vs `tavoff` ABBA x4 read 6,766 vs 6,642 ms — a 124 ms
  median with a per-arm sd of **3.0%** and **2 of 4 pairs the wrong way**, round 840(c)'s reference case. The
  price is a CONTROLLED ROW: one pair per dispatched identifier, the gate returning early INSIDE it, both
  arms in ONE binary (`--tavGateOff`, round 795), so the boundary count is identical and cancels. Late draws,
  two processes, arms rotated: **159 -> 38** and **156 -> 34 ms**, i.e. **121 and 122 ms, 0.8% apart** =
  **1.84%** of a 6,597 ms rebuild. The profile's independent INCLUSIVE reading was 139.6 ms, 13% away.

- **(E) WHAT LANDED, AND WHY A SUPERSET IS THE SAFE SHAPE.** `spineTavCandidates` is every name that could
  produce either diagnostic ANYWHERE in the file. Four sources, exhaustive over `tavBuildLevel`: the eager
  file root's typeOnly+nsOnly, `TYPE_ONLY_KEYWORDS` (folded in so the gate is ONE probe), type-PARAMETER
  names, and a module block's interface/type-alias/namespace names. The last two are collected by the spine
  at `TypeParameter`/`ModuleBlock` ENTER — **keyed on the node KIND, so completeness is structural**, where a
  collector keyed on syntactic POSITIONS would be the fragile version. **`tavModuleLevel`'s filters are
  deliberately NOT reproduced**: dropping a filter widens the set, and a wider gate can only let more
  identifiers through to an unchanged pass. ORDER holds because every node this pass reaches lies inside a
  body, a parameter, a heritage expression or an expression subtree, and `forEachChild` visits
  `typeParameters` before all of them — two pins assert that rather than leaving it as an argument. Row
  CONTROLLED: dispatches 381,670 both arms, **gate refused 365,784 = 95.8%**, hops 695,014 -> 24,058 and
  798,020 -> 9,773, probes 467,085 -> 2,720, emitted 0 -> 0.

- **(F) THE GRID NEEDED ITS OWN CONTROL, FOR THE SAME REASON (C) DID.** A one-binary grid is stronger than a
  two-class-dir one, but two do-nothing arms would agree trivially on a pass that emits nothing. So both arms
  run with `--frontEnd` and every profile records its census line: the gated arm refuses **365,784 / 365,829
  / 366,581 / 366,085 / 367,689 / 493,491 / 525,581 / 562,693** and the ungated arm refuses **0**, with the
  script FAILING if a gated arm refuses under 1,000 or if an arm's reported `gateOff` disagrees with its
  flag. **added=0 removed=0 on all eight, both directions.**

- **(G) PINS + ABLATION.** `TavCandidateGateTest`, 15 pins, all over diagnostic CODES. EIGHT single-mistake
  ablations, one arm per invocation, each dry-run for a real diff and reverted before the next, on a
  committed tree: A1 root typeOnly dropped (4 red), A2 root nsOnly (2), A3 type-parameter names (4), A4 a
  module block's interfaces (3), A5 its type aliases (2), A6 its nested namespaces (2), A7 the keyword set
  (2), A8 the collector never called (8). Every arm reddens, **every red set is distinct**, and **A8's is
  exactly the union of A3-A6's** — the structural check that the spine hook feeds those four sources and
  nothing else. A5/A6 first reddened only their own pin; the equivalence pin's source list was EXTENDED
  rather than the coincidence being counted as coverage (round 869's rule). **Two pins are reported as
  UN-ABLATED**: every arm here makes the gate NARROWER, and a narrower gate can only lose an emission, never
  invent one, so `negative control - an ordinary value name emits neither diagnostic` and the TS2689 pin
  guard a direction no mistake in this construction can take.

- **(H) A CASCADE WORTH THE GOTCHA IT EARNED.** The first full-suite run came back with the test JVM CRASHED
  after 1,237 tests and ten failures in six unrelated classes, including `ArrayIndexOutOfBoundsException`
  inside census `ArrayList`s — a data-race signature. One cause: the two NEW CLI flags were not in the usage
  text, and `CliModeRestoreTest` asserts that BEFORE its `ledger.restore()`, so every mode the sweep armed
  stayed armed for the rest of the JVM. Documenting the flags fixed all ten.

- **(I) GATES.** Suite **14,220 / 0 / 3 skipped** over all four modules (`xml.etree`) = 14,205 + the 15 new
  pins, exactly. Core `commonMain` DID change, so both ran: `cost_gate.py` **+0.00% on all 20 counters** (46
  errors / 78 files) and `huge_methods.py --fail-over 0` **0 over the limit**, 672 classes. No daemon left
  running. Commits `9d8b4ee8`, `49e4795e`, and the docs commit.

- **(J) THE ARC'S CLOSING STATEMENT** is written into the doc (§ 29) rather than left implicit: three takes,
  four fixes, **~800 ms off a warm rebuild that started at ~7.8 s**; what the instrument cannot see (worker
  threads, non-replicating leaves, shares that are shares of WALL not of a rebuild, and that it is never a
  price); and the successor question, which is no longer candidate-hunting — **the remaining ~338 ms of reach
  machinery is one design question** (a per-node status array, or a push-based status maintained by the walk
  that already knows the path), and the next instrument is either a census of that family as ONE population
  or `warm-spine-attribution.md`'s per-handler table.

**Round 873 (2026-08-09) — (SERVE.2): THE FIRST DELIBERATE SWEEP OF THE CLI-vs-DAEMON ANSWER BOUNDARY.
**31 cells, 45 invocation pairs**; the whole SEQUENCE axis was green on the first run, and the four cells that
were not are ONE root cause — **a request never said where it was typed** — plus a hang the sweep tripped over:
`xtsc /nonexistent` crawls the entire filesystem, and under `--serve` that wedges the daemon for good.
`docs/serve-parity.md`.

- **(A) WHY, AND WHY A MATRIX.** Two divergences had surfaced BY ACCIDENT and neither by a test: round 848's
  fifteen process-global mode flags, and round 872's daemon-served compile exiting **0** where the CLI exits 1.
  So the axes were enumerated instead of guessed: outcome (clean / type error / syntax error / unresolved
  import / empty / emitting / `--noEmit`) x invocation FORM (absolute, relative, `-p`, `.`, **no path at all**,
  a bare `.ts`, `--workers 4` with no path, `--help`, a missing path) x observable (exit code, stdout, stderr,
  and the whole emitted TREE by sha256) — plus the axis a one-shot CLI structurally cannot have: repeat,
  A-B-A, mode-then-plain, fail-then-pass, refused-then-normal, **edit-then-request**.

- **(B) THE DEFECT, AND IT IS THE ROUND'S POINT.** `CompileRequest` carried `args` and NOTHING ELSE, while the
  daemon runs in another directory and **a JVM cannot change its own cwd**. Both clients had grown the same
  heuristic — *"rewrite an argument that names something existing here"* — which cannot work, because a client
  does not parse the compiler's options and so cannot tell a project path from the `4` in `--workers 4`.
  Measured, not argued: **`xtsc --daemon --noEmit` in a project full of errors compiled the DAEMON's own
  project and exited 0**, and **`--outDir out` wrote the user's compiled `a.js` into the daemon's directory**
  while the client's tree got nothing. The fix is `CompileRequest.workingDirectory` (**protocol version 2**,
  and the bump is load-bearing — a v1 daemon would answer a v2 request against its own cwd) installed as
  `SystemVfs.workingDirectory` for the duration of one request, install-and-restore on the one compile thread
  exactly like round 848's ledger. That is THE funnel: `ProjectCompiler.build` absolutizes both the project
  path and a `--outDir` override through `Vfs.resolveAbsolute` before doing anything with either, so one
  variable moves the question to the only place that HAS the option table. Both clients' rewriting is deleted
  and the native client is finally the strict pass-through its KDoc always claimed.

- **(C) THE HANG THE SWEEP TRIPPED OVER, WHICH NO CELL WAS LOOKING FOR.** `form-missing-project` never
  returned and every later cell then reported "not served by the daemon". `xtsc /nonexistent` resolves its
  config path to that missing file, takes `dirname` — **`/`** — and, because an unreadable config fell back to
  the default everything-glob, walks the WHOLE FILESYSTEM: **over 30 minutes of CPU** before it was killed,
  having emitted TS5083 first and gone walking anyway. The CLI merely hangs; the daemon serves sequentially on
  one thread by design, so it is wedged **for good** and every later client blocks with no timeout to notice.
  A config that does not EXIST now includes nothing; one that exists and does not PARSE keeps the default.

- **(D) THE DRIVER IS BUILT SO A GREEN SWEEP MEANS SOMETHING.** `--selftest` feeds the comparator a synthetic
  divergence on each of the four observables and refuses to sweep if any is missed; every daemon-arm step
  requires the server's own `request N served` count to advance by exactly one AND the dispatcher's
  in-process fallback message to be absent — without which the likeliest instrument failure (the daemon died,
  so `--daemon` compiled in-process and the arms agreed trivially) would read as parity; a cell that raises or
  times out is ERROR, never PASS, and a timeout RESTARTS the daemon so one wedge cannot void the rest. The
  daemon runs in a DECOY project, so a path resolved against the server's cwd is visible rather than
  accidentally right. **And the "this cell may differ" marker was DELETED**: the ablation caught it absorbing
  an unrelated exit-code divergence into its own documented excuse and printing as KNOWN.

- **(E) POSITIVE CONTROL, ONE MISTAKE AT A TIME (round 807).** A1 = round 872's exit-code bug reintroduced:
  **20 of 31** cells redden. A2 = this round's fix removed: **7 of 31**, exactly the cwd-dependent forms and
  emit cells and nothing else. A3 = a stale-answer daemon: **2 of 31**, `seq-edit-between-requests` and
  `seq-edit-adds-file`, which is the sequence axis's own uniquely-its-own failure — nothing else in the matrix
  can tell a daemon that re-runs a request from one that only appears to. Each arm built, swept and reverted
  before the next, tree committed first.

- **(F) WHAT WAS GREEN, STATED AS A RESULT.** Round 848's ledger holds under `--passTiming` and `--workers 4`
  followed by a plain request; round 871's cross-request parse cache is correct across an edit, a revert and
  an added file; a refused, a syntax-error and a nonexistent-project request all leave the daemon answering
  normally. 31/31 after the fixes.

- **(G) GATES.** Suite **14,205 / 0 failures / 3 skipped** over all four modules = round 872's 14,188 + the 17
  new pins, exactly. Core `commonMain` DID change, so both gates ran: `cost_gate.py` **every counter +0.00%**
  (46 errors / 78 files, so it really compiled the profile) and `huge_methods.py --fail-over 0` **0 over the
  limit**. No daemon left running.

**Round 872 (2026-08-09) — (WARM.20): THE 279 ms CLIENT JVM IS **7.0 ms** OF KOTLIN/NATIVE, THE "THIN
CLIENT" WAS NEVER ABOUT THE COMPILER JAR, AND SWAPPING THE ARMS FOUND A CI FALSE-GREEN.** On the 3-file
project an editor actually generates, a request goes **369 -> 105 ms**. `docs/perf/warm-serve-request-attribution.md` § 10.

- **(A) WHAT EXISTED, CHECKED RATHER THAN ASSUMED (round 857's rule).** The `-client` module has shipped
  since MOD.4 with a jvm target, opt-in `linuxX64`/`macosArm64` executables and a GraalVM task — and
  **nothing had ever invoked any of it**. Its `clientLib` staging is not wired into `assemble` (the daemon's
  `xtscLib` is), no native binary had been built, and `scripts/xtsc` reached only `XtscMainKt`. The K/N
  client links in **1m23s** to a 3.26 MB binary; the GraalVM arm cannot be built on this box (no GraalVM).

- **(B) THE ARM TABLE, against ONE warm daemon with round 871's constant-time refused request so the compile
  cancels out.** 12 reps, interleaved and rotated, `EPOCHREALTIME` not `date +%s%N` (the fast arms are
  single-digit ms and `date` is a fork+exec per timestamp): fork+exec floor **0.9**, **native 7.0**,
  thin JVM+AOT **105.2**, fat JVM+AOT **277.1**, thin JVM **278.1**, JVM dispatcher **286.9 ms** — round
  871's 279 ms reproduced. **The prediction that failed: "thin client" named the DEPENDENCY EDGE, and the
  edge is worth 3%** (287 -> 278). Class loading is lazy, so the dispatcher never touches the 5.6 MB
  compiler jar when the daemon answers; the cost is the JVM. What the edge is worth is that it makes a
  NATIVE binary affordable, and that is worth the other 97%. Both AOT arms verified loading from the cache
  (`-Xlog:class+load`, 1,702 / 2,657 classes) — so the fat arm's ~0 gain is a result, not an uncached run.

- **(C) WHAT IT BUYS, AT BOTH ENDS OF THE RANGE, both arms through the LAUNCHER.** 3-file project
  **369.2 -> 105.3 ms** (72-74% of the wait); compiler profile 7,195 -> 6,881 ms, which is INSIDE its own
  +-5% spread and is NOT claimed as a measured effect — the fixed cost is what was measured, and 279 ms of
  7,150 is 3.9% by arithmetic. Same errors and same digest on both projects (`8ccb2942`, `4090b73e`).

- **(D) SHIPPED: the native arm with a JVM FALLBACK, which is the load-bearing half.** The client depends on
  `-api` and cannot compile, so it exits `XTSC_CLIENT_UNAVAILABLE` (3) — documented to mean *the request
  never ran*, never *it ran and found errors*, which is exactly what makes re-running it on the JVM arm
  safe. Fresh checkout, `clean`, any platform with no binary: `xtsc` still works, and four pins assert it.
  `XTSC_SOCKET` is named explicitly because the client honours it and the dispatcher does not — deriving the
  path in bash would be a THIRD derivation, which the both-peers-agree invariant forbids.

- **(E) TWO DEFECTS FELL OUT, AND THEY ARE THE ROUND'S REAL YIELD.** (1) A `--daemon` compile **served by a
  daemon** exited **0** on a failing project where the one-shot CLI and the in-process fallback both exit 1
  — `runAsClient` propagated `XTSC_REFUSED` and dropped every other code, so `xtsc`'s answer again depended
  on whether a daemon happened to be running and CI read a failing compile as a pass. `ExitCodeParityTest`
  pins the code the server puts IN the response, which was always right; the defect was one layer up.
  (2) The arm's first build let the client **auto-spawn** a daemon — `AotCacheGuardTest` failed on the
  missing "no compile server on" message and **a daemon was left running after the suite**. `--no-spawn`
  now makes the swap a latency change and nothing else.

- **(F) GATES.** Suite **14,188 / 0 / 3 skipped** over all four modules = round 871's 14,165 + the 23 new
  pins, exactly. `cost_gate.py` and `huge_methods.py` **not required and not run** — nothing in core's
  `commonMain` changed (daemon `jvmMain` + `jvmTest` + shell). Piped invocation returns on the shipped path
  and on the client's own spawn path (MOD.5's `dup2`). 8-arm single-mistake ablation, every arm with its own
  failing set; two honest notes recorded (A2's second red is one mistake with two consequences; A5 is
  invisible to the real-client pin and is discriminated by the argument pin).

**Round 871 (2026-08-09) — (WARM.19): THE FIRST MEASUREMENT IN THIS ARC TAKEN THROUGH THE ARTIFACT THAT
SHIPS. A `--serve` REQUEST PAYS **2 ms** OVER AN IN-PROCESS REBUILD; THE **279 ms** A CLIENT SEES IS A FRESH
CLIENT JVM; AND WHAT A REQUEST RE-DID ACROSS REQUESTS WAS **78 FILE READS + 78 PARSES**, IDENTICALLY FOR AN
IDENTICAL REQUEST AND A ONE-FILE-CHANGED ONE. The parse half is now shared: **133 ms = 1.9%**, and the editor
workload parses **one file per edit** instead of 78. `docs/perf/warm-serve-request-attribution.md`.

- **(A) THE GAP, AND WHY ROUND 843 DID NOT CLOSE IT.** Every warm number rounds 843-870 produced came from
  `BenchMain`, an IN-PROCESS repeated rebuild. Round 843 observed a real `--serve` ladder reading 7.10-7.45 s
  against BenchMain's 7.14/6.92 s and concluded they agree — **a comparison of TOTALS, not an attribution**,
  taken before this arc removed ~15% of the rebuild. Three NESTED brackets the shipping binary already prints
  (client wall > server `elapsedMs` > the compiler's own `time:`) settle it: `server - compiler` — everything
  `runCli` does around the build, i.e. argument parse, the round-848 mode ledger, diagnostic formatting,
  stdout capture and the JSON encode — is **1-3 ms warm** on every request of three ladders.

- **(B) THE 279 ms IS THE CLIENT, ISOLATED RATHER THAN DIFFERENCED.** `--watch` is refused by
  `CompileServer.respondTo` in constant time, so a refused request's client wall IS the client-side cost with
  the compile subtracted BY CONSTRUCTION: **279 ms median**, of which the bash launcher is **9 ms**. ~270 ms
  is a fresh client JVM — **3.9% of the 7.15 s a user waits**, paid outside the server, and exactly what the
  native thin client named in `XtscMain`'s KDoc exists to remove.

- **(C) THE PER-REQUEST CENSUS, AND THE LINE THAT IS THE FINDING.** `--frontEnd` from inside a request, 8 warm
  draws over 2 daemon processes: config **3 ms**, crawl WALL **153 ms (2.2%)**, bind **385 ms (5.5%)**, check
  **6,450 ms (92%)**, post 3 ms. And on EVERY request: `files read: 78 (9977097 chars)   core parse loop: 78
  reused / 0 fresh`. **`78 reused` is INV.1(e) working WITHIN the request; `files read: 78` is the crawl doing
  all 78 parses again** — `preParsed` is a local of `build()`. The LIB parses have survived across requests
  since M2.1(c) (`RealLibSnapshots`); the program's own never did. **The one-file-changed ladder produced
  IDENTICAL counters**, which is the prize stated as a fact: the daemon workload is 77/78 unchanged and the
  compiler was treating it as 0/78.

- **(D) PRICED BY AMPLIFICATION, BECAUSE THE CENSUS CANNOT.** The crawl is a concurrent pipeline whose
  read+parse CPU sums to **6-9x its wall** and it carries a fixed floor no parse elimination touches. New
  `--parseAmp N` performs N extra parses per file inside the same span; two processes, rotations
  `0,1,2,3,3,2,1,0` and `3,2,1,0,0,1,2,3`: **crawl(r) = 180 + 128.3r** and **171 + 137.7r** — one parse round
  is **133 ms**, floor **33-52 ms**. Falsifier is ARITHMETIC and passed on every draw (sink = r x 4,530
  statements exactly). **1.9% of a warm request, above the floor — which is what justified building anything.**

- **(E) WHAT LANDED, AND WHY ITS INVALIDATION CANNOT GO STALE.** `CrawlParseCache` (commonMain, own file):
  `path -> PreParsedFile`, served only when the CONTENT and the `ParserFlags` also match — INV.1(e)'s own gate
  hoisted from a per-build local to a process-global map. The crawl reads every file every request anyway
  (this removes none of that read CPU), so the bytes are in hand before the question is asked: **different
  bytes are a different key; there is no mtime, size or stat anywhere in it.** Sharing a tree is sound on three
  properties, none new: the parse is a pure function of `(source, fileName, flags)` (`internSalt` is
  `fileName.hashCode()`), the AST is written only by `indexSourceFile`, and **`RealLibSnapshots` has relied on
  exactly this since M2.1(c)** — the parse is shared, the BIND is not. Memory is bounded at one entry per path
  and it was MEASURED, not argued: a 7-edit ladder reports **78 paths held** at every request. `lookup` is
  read-only on the concurrent workers, `store` runs only in the single-threaded post-frontier fold (round 825).

- **(F) THE CAPTURE, THREE INSTRUMENTS.** Controlled row with BLOCKED arms in one daemon (`--parseCacheOff`):
  crawl **138 -> 14 ms**. Against the pre-change binary: **153 -> 24 ms**. The amplifier: **133 ms**. So
  **122-133 ms = 1.8-1.9%**. **A trap worth the line: a single OFF request dropped into a run of ON ones reads
  896 ms**, because the parser has not run for several requests and is no longer hot — interleave these two
  arms request-by-request and the OFF arm is inflated by a factor of six. No whole-request A/B is quoted (the
  wall's draw spread is +-5%; rounds 858 and 869 s 13 both measured that two batches cannot separate drift
  from an effect this size). After: **77 hit / 1 miss per request** on the editor ladder.

- **(G) THE GRID IS A DAEMON GRID, ON PURPOSE.** A one-shot CLI performs exactly ONE `build`, so it can never
  register a cache hit and a two-class-dir grid of two CLI binaries would be evidence of nothing. Instead the
  8 dashboard profiles were each compiled TWICE THROUGH ONE DAEMON — request 1 all-MISS (the pre-change
  behaviour), request 2 all-HIT — **added=0 removed=0 on all eight**, crawl falling 1,084->96, 763->23,
  274->11, 197->28, 113->28, 203->47, 278->38, 288->43 ms. Cross-PROJECT reuse fell out and was observed: the
  profiles are nested subsets, so `tsc-cli`'s FIRST request already reports 78 hits and the daemon ends holding
  1,249 paths.

- **(H) PINS + ABLATION, WITH THE ROUND'S SHARPEST FINDING IN IT.** `CrawlParseCacheTest`, 15 pins; 7 ablations,
  one arm per invocation, each reverted before the next, every arm dry-run first for a real diff. A1 (content
  compare dropped) 4 red, A2 (flags) 1, A3 (content compare becomes a LENGTH compare) 2, A4 (path not in the
  key) 3, A5 (OFF arm not off on READ) 1, A6 (on WRITE) 2, A7 (driver never stores) 4. **A1 and A3 initially
  shared a red set** — the "byte difference" pin used a same-length edit — and were separated by making it a
  different-length one. **And A1 reddened three COUNTER pins and NEITHER edit pin, which is a finding about the
  compiler rather than a weak fixture: the core re-checks content at `ParsedSource` (INV.1(e)), so a mis-keyed
  hit there degrades to a redundant parse and a CORRECT type-check. The place with no second gate is the
  CRAWL, which has already used the stale tree's `moduleSpecifiers` to decide WHICH FILES EXIST** — an 11th
  pin was cut for exactly that ("an edit that adds an import changes which files the crawl reaches", with an
  explicit tsconfig file list so the program is decided by the crawl and not by the glob), and A1 now reddens
  it. The wrong-answer path is a MISSING FILE, not a wrong type.

- **(I) GATES.** Suite **14,165 / 0 / 3** over all four modules (`xml.etree`) = 14,150 + the 15 new pins.
  `cost_gate.py` **+0.00% on all 20 counters**, twice. `huge_methods.py --fail-over 0` 0 over the limit.
  8-profile daemon grid added=0 removed=0. 33 ladder requests over 3 daemon processes, every one 46 errors,
  digest `84bbe7f0`. Commits `ccfb1bb7`, `bae03124`, `589b32fe`, `07eb9f9a`.

**Round 870 (2026-08-09) — (WARM.17): THE WARM LEAF PROFILE RE-TAKEN ON THE POST-868/869 BINARY — THE TWO
FIXED FAMILIES ARE **GONE FROM ITS OWN TABLE** (-410 ms and -113 ms, the second agreeing with round 869's
amplifier to 13%), NOTHING NEW ROSE INTO THEIR PLACE, AND ITS TOP SURVIVING CANDIDATE IS TAKEN: the
`getTypeParamInfo` memo's MISS stops re-deciding which symbols are namespaces — **2,992,718 -> 2,277
symbols scanned, 82 -> 15 ms**, over an IDENTICAL population.
`docs/perf/warm-leaf-profile.md` §§ 14-20.

- **(A) THE VALIDITY CHECK IS THE ROUND'S FIRST DELIVERABLE, AND IT PASSED.** Same recipe, same window,
  two processes, and — the thing that makes the two tables comparable at all — **round 868's own dumps
  re-aggregated with the SAME script**, now committed as `scripts/leaf_owner_profile.py` (it REFUSES a
  `jfr print` dump whose deepest stack is 5 frames, filters to the compile thread, and charges stdlib
  leaves to the nearest non-stdlib OWNER — round 868's three traps, as code). `checkSpine` INCLUSIVE
  reads **75.28%/74.34%** against this arc's independently known ~74%, which is the sanity check the
  `--stack-depth` trap demands. Then: `computeExportedFnDeclsThroughStars` 2.79/2.50% -> **0.24/0.18%**,
  `computeExportedVarDeclThroughStars` 1.56/1.31% -> **0.26/0.16%**, `resolveBarrelStarTarget` and
  `spineOsPushCopy` -> **0.00%**, and BOTH replacements appear where they should (`buildStarExportIndex`
  0.04/0.00%, `AnnScopeStack` 0.49/0.35%). By family, in ms/rebuild: the barrel search **471 -> 61**, the
  two annotation frames **153 -> 40**. That second figure is a genuine cross-instrument agreement —
  round 869 priced those two families by AMPLIFICATION at **129.7 ms** and this profile, which knows
  nothing of that measurement, reads **113.3 ms**.

- **(B) THE DENOMINATOR TRAP, WHICH IS HOW THIS TABLE WOULD HAVE BEEN MISREAD.** The JFR window is a
  fixed 90 s of steady state, so the sample count is a constant (~8,000 both rounds) and **a share is a
  share of WALL TIME, not of a rebuild** — an unchanged per-rebuild cost READS HIGHER once the compile
  got faster, by exactly 7,766/7,068 = **1.099**. Every cross-round row is therefore quoted in ms/rebuild.
  Read that way, **six of round 868's top-20 rows are gone and NOTHING NEW rose into their place**: the
  rows that "moved up" (`ctaFnBodyFrame`, `ctaSpineEnter`, `lookupPerFileForNode`) are the same ms against
  a smaller denominator. The shape is also unchanged — HashMap/HashSet **26.6/27.3%** of compile-thread
  samples, and `java.util.regex` **0.04/0.13%**, i.e. round 863's "the class is exhausted on this
  profile" survives two rounds of change.

- **(C) THE CANDIDATE, AND WHY IT AND NOT THE BIGGER ONE.** After discarding every already-attributed row,
  `computeTypeParamInfo` is the largest that replicates (**1.47/1.35%**) — and round 868's own table
  already carried it as "NEW - memoized, so this is the MISS population" without pricing it.
  `ciaMutualFnDecls$resolve` is BIGGER in run 1 and is DISCARDED: **1.40% against 0.38% on the same
  binary** is a 3.7x spread, and a share that does not replicate is not a share.
  `objectLiteralSatisfiesAugmentationMergedInterface` replicates cleanly and is simply too small (0.66%).

- **(D) PRICED BY AN INDEPENDENT INSTRUMENT (`FrontEnd.TPI`), NOT BY THE PROFILE.** One timestamp pair per
  MISS plus a census; **deterministic to the unit over two processes** (82,316,551 and 82,273,289 ns,
  0.05% apart, every count identical). `calls 28663, misses 1077 (3.8%), 82 ms; file probes 45906, ns
  symbols scanned 2992718 (2778/miss), of which module 2184; answered 880, null 197`. **82 ms = 1.16% of
  a warm rebuild**, agreeing with the profile's 99.9 ms to 18%. **The memo is not the problem — it
  answers 96.2% of the questions.** The whole cost is one of the miss's three lookups: "is this name
  exported by some namespace anywhere in the program?", answered by iterating EVERY entry of EVERY file's
  `locals`. **2,992,718 symbols iterated to reach 2,184 module-flagged ones — 99.93% of the scan
  re-decides a question that is a property of the binder tables and not of the name being asked.**

- **(E) WHAT LANDED, AND THE CONTROLLED ROW.** `buildModuleSymbolScanIndex` (commonMain, `internal`, its
  own file so every rule is pinned directly) returns the module symbols in the scan's exact order —
  `binderResults` order, then each file's `locals` insertion order, **duplicates preserved**, because one
  `Symbol` instance really is reachable from two files' `locals` (`mergeModuleAugmentations` creates
  exactly that) and the scan probed it once per table. The `exports` table is deliberately NOT indexed: it
  is a `var` the checker's own merging writes to, so the probe stays live. **Freezing rule:** the
  module-symbol SET is settled by init pass 1b, long before any checking pass, and `getTypeParamInfo`'s
  memo has frozen whole ANSWERS over the same tables since round 481 — so caching the LIST is strictly
  weaker than an assumption this function has relied on for 389 rounds; it is nonetheless built LAZILY at
  the first miss, so its disagreement window is contained in the memo's. **Row CONTROLLED (round 793):**
  calls 28,663 / misses 1,077 / file probes 45,906 / module probes 2,184 / answered 880 / null 197 are
  IDENTICAL on both arms to the unit, and only the scan width moves — **2,992,718 -> 2,277 (1,314x)**,
  **82 -> 15 ms**.

- **(F) THE DECISION, SAID PLAINLY IN BOTH DIRECTIONS.** The CANDIDATE priced at **1.16%**, above the
  decision floor, which is what justified building anything; the CAPTURE is **67 ms = 0.95%**, and the
  15 ms residue is loop 1's 45,906 per-file hash probes plus the live `exports` probes — **0.21%, below
  the floor, deliberately NOT taken**. No whole-rebuild A/B is quoted: rounds 858 and 869 § 13 both
  measured that a two-batch `ab-warm.sh` cannot separate drift from an effect of this size, and a
  controlled row needs no help.

- **(G) PINS + ABLATION.** `ModuleSymbolScanIndexTest`, 11 pins, all over strings/ints/identity (never a
  `Symbol` or an AST node). NINE single-mistake ablations, one arm per invocation, each reverted before
  the next, on a committed tree, every arm dry-run first for a real diff and a clean revert: A1 gate reads
  `ValueModule` only, A2 `NamespaceModule` only, A3 no gate at all, A4 file order reversed, A5 within-file
  order sorted by name, A6 duplicates dropped by INSTANCE, A7 by NAME, A8 only the first module symbol per
  file, A9 the index carries a COPY of the symbol. **A1/A2 are a deliberate PAIR** — CLAUDE.md records
  that `SymbolFlags.Module` is the UNION of the two and that either half alone compiles and silently loses
  the other, so a single "the gate is wrong" arm would have been satisfied by either. **A6 initially left
  every pin GREEN, and the honest reading was that the fixture set could not express it**: the binder
  gives each file its own symbol, so nothing built through it puts one instance into two `locals` tables
  — an 11th pin was cut that builds that state by hand, and A6 now reddens it and nothing else (round
  869's rule). **Two things reported rather than claimed:** `an empty program has an empty index` is
  structurally UN-ABLATABLE (every arm maps empty to empty), and **A5 and A8 share a red set** — different
  defects that one pin catches, so one discriminated position, not two. 10 of the 11 pins redden.

- **(H) GATES.** Suite **14,150 / 0 / 3** over all four modules (`xml.etree`) = 14,139 + the 11 new pins,
  exactly. `cost_gate.py` **+0.00% on all 20 counters**. `huge_methods.py --fail-over 0` exit 0, census 0.
  **8-profile grid with BOTH arms rebuilt from source and diffed in both directions: added=0 removed=0 on
  all eight**, 8 distinct captures, compiler digest `59d930db…`. Round 853's positive control both ways:
  the AFTER dir holds `ModuleSymbolScanIndexKt` and the BEFORE dir does not (665 vs 664 classes).

**Round 869 (2026-08-09) — (WARM.16): ROUND 868's COPY CANDIDATE IS PRICED — THE WHOLE PER-SCOPE
WHOLE-MAP COPY FAMILY IS **205 ms = 2.80%** OF A WARM REBUILD, AND THE TWO FAMILIES WHERE THE FALSE-POSITIVE
HAZARD IS STRUCTURALLY ABSENT (**129.7 ms = 1.74%**) NOW COPY NOTHING: **3,088,334 ENTRIES -> 34,454 UNDO
RECORDS OVER AN IDENTICAL POPULATION.** THE OTHER CANDIDATE, `ArrayList.clear`, IS LEFT EXPLICITLY
UNPRICED. `docs/perf/warm-leaf-profile.md` §§ 9-13.

- **(A) THE CENSUS CAME FIRST, AND IT IS THE WHOLE DECISION (round 801).** 159,630 scope pushes copy
  **5,834,632 map entries to serve 89,307 writes — 1.5%**. A copy costs O(size); an undo log costs
  O(writes). Six families, deterministic to the unit on all 24 instrumented rebuilds:
  `spineOs` 34,155 pushes / 1,841,284 entries / mean 53.9 / 17,600 writes; `spinePd` 21,674 / 1,247,050 /
  57.5 / 16,854; `CtaFrame.varTypes` 30,433 / 1,145,523 / 37.6 / **2,564**; `CtaFrame` localTypes+
  declNodes+shadowed 9,525 / 1,089,527 / 114.3; `EpochMap` 28,828 / 471,726 / 16.3 / 44,320; `EpochSet`
  35,015 / 39,522 / 1.1 / 7,969. **One row's `writes` is reported as `n/a`, not as 0** — the `CtaFrame`
  local family mutates through a plain `HashMap` installed into `currentLocalTypes`, which the `EpochMap`
  hook cannot see, so that zero is UN-INSTRUMENTED and calling it a finding would be round 849's mistake.

- **(B) THE PRICE IS AN AMPLIFICATION, BECAUSE A BOUNDARY WOULD BE THE MEASUREMENT.** At a mean copy of
  16-114 entries one warm probe boundary (97-202 ns, round 850) exceeds the quantity, so `copyAmp = r`
  performs `r` EXTRA copies at every censused site and takes **no timestamp pair anywhere**: the answer is
  the slope of the WHOLE-REBUILD wall and two values of `r` cancel the base algebraically. Falsifier is
  ARITHMETIC — `ampSink == r x entries`, exact on every rebuild (`49,413,344 = 16 x 3,088,334`). **Design
  detail that mattered: ABBA rotation.** The first instrumented rebuild in a process is the slowest draw —
  a 5-for-5 law in this arc, worth up to **15%** here (`r=0` read 8,082 ms in one cycle and 6,872 ms in the
  next) — so an un-rotated ladder puts that bias entirely on whichever arm runs first and flattens the
  slope. Whole family **205.3 ms = 2.80%**; `copyAmpKinds` restricted to the two annotation families
  **129.7 ms = 1.74% [1.46-2.02%]**, measured on the binary that still HAS them rather than as the
  difference of two slopes. The +-16% bracket is said out loud: what it supports is "above the 1% floor at
  both ends", not a third decimal. Round 868's profile put `putMapEntries` at 5.4% inclusive (~400 ms) —
  same order, factor 2, in the direction the amplifier's own caveat predicts (an amplified copy dies in
  eden; a production copy is retained for its frame).

- **(C) WHICH TWO, AND WHY NOT THE BIGGEST.** The classification is by whether copy semantics can be
  replaced PROVABLY. The `spineOs`/`spinePd` annotation frames are strict LIFO, never remove a key, and
  all three consumers (`spread2698CheckOperand`, `rest2700Check`, the `pddu*` pair) only LOOK UP,
  synchronously, inside one spine node's handler. The other four are `currentLocalTypes` — where a wrong
  scope does not crash but silently resolves a name to an outer binding (the `applyBodyLocalShadowing` FP
  class) — or `CtaFrame.varTypes`, which is deliberately SHARED at some frames and copied at others. The
  two taken are the ones where the hazard is structurally ABSENT, not argued away.

- **(D) WHAT LANDED.** `AnnScopeStack`: one live map plus an undo log; `push` records a mark, `put`
  appends the key's pre-write value (`null` = absent, unambiguous for a `TypeNode`), `pop` replays this
  frame's slice **in REVERSE** — which is what makes a repeated write to one key correct with no per-frame
  "already shadowed" set, since the last restore applied to a key is its first record. Its contents equal
  the copy chain's at every moment, so the replacement is equivalence by construction. **Row CONTROLLED
  (round 793):** pushes and writes are IDENTICAL on both arms to the unit (34,155/17,600 and
  21,674/16,854) and only the copy width moves, **3,088,334 -> 0**, undo records 0 -> 34,454. And the
  amplifier is the change's own falsifier: re-run on the AFTER binary it reads **+5.5 ms over r = 0..16**
  with `ampSink 0` — nothing left to amplify, so the copies are gone rather than merely uncounted.

- **(E) PINS + ABLATION.** `AnnScopeStackTest`, 11 pins, all over strings and ints (never a `TypeNode`,
  whose power-assert rendering is its whole subtree). NINE single-mistake ablations, one arm per
  invocation, each reverted before the next, on a committed tree: A1 `pop` restores nothing, A2 `pop`
  replays FORWARD, A3 `put` records the NEW value, A4 `put` persists with no frame open, A5 `push` records
  mark 0, A6 `pop` leaves a key the frame INTRODUCED, A7 `topOwner` answers the OUTERMOST frame, A8 `push`
  starts the scope EMPTY, A9 `reset` keeps the entries (a cross-FILE annotation leak). **Every arm reddens
  and every one of the 11 pins is reddened by at least one arm** — A8/A9 were cut precisely because two
  pins had no failure after the first seven, and round 868's rule is to RECORD an un-ablated pin rather
  than count it; cutting an arm was cheaper than the disclaimer. **A1 and A3 share a red set and are
  reported as ONE defect in two spellings, not as two arms of coverage.** The driver caught its own
  mis-edit once (`APPLY FAILED`, no build run) — which is what rounds 855/856 added the per-arm
  `git diff --shortstat` and the array default for.

- **(F) WHAT DID NOT WORK.** **The whole-rebuild `ab-warm.sh` A/B, and it is DISCARDED rather than
  quoted.** Batch 1: **-330 ms (-4.42%), B wins 2/2**, both pairs sign-consistent. Batch 2: **-77 ms
  (-1.04%), 1/2**, driver verdict `NOISE-DOMINATED`. Per-arm sd 1.40% and 3.47%, above the 1.0%
  quiet-box threshold in both batches. That is round 840(c) and round 858's reference case exactly — a
  sign-consistent paired batch is not a result — and had only batch 1 run, this note would be claiming
  -4.4% for a change whose controlled instrument says 1.74%. **(C3) `ArrayList.clear` was NOT priced and
  deliberately not acted on**: it is round 623's exact bias class. One structural fact was established
  instead — **47** `ArrayList<Node>` ascent buffers, **97** `chain.clear()` sites, i.e. a PAIR per
  classifier, and since every filling path ends with the trailing clear the LEADING one always runs on an
  already-empty list where the null-out loop does not execute — so its samples are spread over twice as
  many sites as there is work. Queued as (WARM.18) with the instrument it needs named; `CtaFrame.varTypes`
  (the worst ratio of the six, 0.22%, and a `LinkedHashMap` with zero order consumers) as (WARM.17), renumbered (WARM.19) in round 870.

- **(G) GATES.** Suite **14,139 / 0 / 3** over all four modules (`xml.etree`), = 14,128 + the 11 new pins,
  exactly. `cost_gate.py` **+0.00% on all 20 counters**. `huge_methods.py --fail-over 0` exit 0.
  **8-profile grid with BOTH arms rebuilt from source and diffed in both directions: added=0 removed=0 on
  all eight**, 8 distinct captures, compiler digest `59d930db…` (the recipe CLAUDE.md records). Positive
  control both ways: the AFTER class dir has `AnnScopeStack` and the BEFORE dir does not (665 -> 664
  classes: two frame classes out, one stack class in).

**Round 868 (2026-08-09) — (WARM.15): THE FIRST **LEAF-LEVEL** WARM PROFILE OF THIS COMPILER, AND ITS
BEST NEW CANDIDATE LANDED — THE `export *` BARREL WALKS GO **672 -> 91 ms** WARM (9.0% -> 1.2% of a warm
rebuild). PLUS: THE FLAKY `PostCheckerPartitionTest` IS RE-CUT AS A DETERMINISTIC ORDERING.**
`docs/perf/warm-leaf-profile.md`.

- **(A) THE FLAKY PIN, FINISHED PROPERLY.** Round 867's suite read 14,120 / 1 / 3 and recorded the one
  failure as flaky. It was `residue * 4 < post` — a wall-clock RATIO over a **272 us** region, i.e. not a
  pin but a coin flip; one stop-the-world pause in the residue window is all it takes, and a pin that
  cries wolf is worse than no pin because the day it is right it is already being ignored.
  `FrontEnd.close` already holds both timestamps of the span it closes, so it now records
  **`firstAt`/`lastAt` per section** (ON path only), and the partition is asserted STRUCTURALLY: each
  block records exactly once, `nanos == lastAt - firstAt` to the nanosecond, every block NESTS inside the
  region it decomposes, and the siblings are ORDERED and DISJOINT. Every comparison is a fact about a
  monotonic clock. **Stated in both directions in the KDoc, because the swap is not free:** it protects
  MORE on structure (the ratio could see neither a block OVERLAPPING its sibling nor one ESCAPING its
  region, whenever the region was big enough to absorb the arithmetic) and LESS on one thing (an
  unattributed GAP of real work opened between two blocks — bounding a gap means bounding time, which is
  exactly what cannot be done here). `sum <= outer` survives: it is guaranteed by nesting, hence
  structural rather than statistical. Pass count: **6/6 consecutive FULL-SUITE runs at 14,120 / 0 / 3**
  (not isolation — the flake only ever showed under the full suite).

- **(B) WHY A LEAF PROFILE, AND WHAT IT MAY NOT SAY.** Every instrument this arc owns is a PASS or
  SECTION probe: it finds only cost somebody thought to bracket, which is why rounds 860-864's four wins
  were all wrongly-shaped work INSIDE a named region, and why anything diffuse is invisible to all of
  them. A JFR `ExecutionSample` profile is the complement and pays for it by being unable to price
  anything: round 623 eliminated a 5.3%-of-samples leaf here and measured **-0.3%**. So the doc's
  headline says it in as many words — **a leaf is a CANDIDATE; it becomes a finding only after an
  independent instrument prices it** — and the round obeyed it.

- **THE INSTRUMENT'S OWN TRAP, WHICH COST THE FIRST PASS: `jfr print` TRUNCATES EVERY STACK TO ITS TOP 5
  FRAMES, SILENTLY, AND `scripts/aggregate_jfr.py` DOES NOT PASS `--stack-depth`.** Its "INCLUSIVE by
  method" table is therefore inclusive-within-five-frames: `checkSpine` read **15.6%** against this arc's
  74%, and ten minutes went into reconciling a number that was an artifact. With `--stack-depth 512` the
  deepest compile-thread stack is 171 frames and the table is real. Second trap: ~3% of samples are the
  JFR recorder thread and the crawl's coroutine workers — filter to `xtsc-deep-stack`. **Third, and the
  reason every table in the doc is by OWNER and not by leaf: leaf attribution is NOT stable across
  processes** — `HashMap.getNode` reads 9.66% in one and 3.70% in the other on the SAME binary, because
  C2 inlined it in the second. Charging stdlib work to its nearest non-stdlib caller replicates to a few
  tenths of a percent.

- **WHAT THE PROFILE SAYS ABOUT THE SHAPE.** 26-27% of warm compile-thread samples are inside a hash
  map/set, 6% string, 4% list — a bound on what any single map-shaped fix can be worth, not a lever.
  **`java.util.regex` is 0.0% — not one sample in 16,036**, which confirms round 863's "the class is
  exhausted on this profile" with an instrument that was not built to look for it. Two rows this file's
  own history predicted did NOT appear: `computeLineStarts` (round 623's refuted lever) is not in the top
  200, and `LinkedHashMap.afterNodeInsertion` (round 483's ~5%) is ONE sample in 16,036.

- **THE CANDIDATE THAT WAS TAKEN, AND ITS INDEPENDENT PRICE.** After discarding every row that is
  already-attributed checking work, the largest new one is a family of four mutually recursive `export *`
  walks — **5.45%/4.80% self, 7.81%/7.60% inclusive** — entered from `getCalleeType`,
  `importedTopLevelVarAnnotationType`, `checkCircularInferenceImplicitAny` and
  `computeImportedSymbolGeneral`. A counter+span census (`FrontEnd.STAR`, re-entrancy-counted so a walk
  of any depth costs one timestamp pair) priced it at **672 ms = 9.0% of a warm rebuild**: 8,754
  outermost walks, 290,117 file visits, **25,291,521 top-level statements scanned — 2,889 per question
  asked**, and 28% of the walks answer nothing after visiting 33 files each. The profile's inclusive
  share over the same rebuild is 570-585 ms: two instruments, two mechanisms, agreeing within 15%.

- **THE FIX IS AN INDEX, AND THE ROW COMPARISON IS CONTROLLED.** `StarExportIndex` computes per file,
  once, the four immutable facts the walks re-derived per visit (exported functions by name, exported
  variables first-wins, exported interface names, descendable re-export edges with targets resolved).
  Because it changes no boundary and no population, the before/after row is valid under round 793's law:
  **walks 8,754, visits 290,117, answered 6,298, null 2,456 are IDENTICAL on both arms** and only the
  scan width moves, **25,291,521 -> 638,464 (39.6x)**. Row **672 -> 91 ms**. Whole-rebuild corroboration
  `ab-warm.sh`, two batches x two rotated pairs: **-426 ms (-5.58%)** and **-304 ms (-4.15%)**, B wins
  **4/4**, every pair sign-consistent — quoted as corroboration, not as the number, because the per-arm
  sd is 1.2-1.9% on n=2 (above the quiet-box threshold; said out loud rather than silently, per (JIT.1)).

- **PINS + ABLATION.** `buildStarExportIndex` was made a top-level `internal` function taking its
  resolver as a PARAMETER specifically so every rule is pinned directly rather than through a compile
  that would hide a wrong gate behind an unrelated diagnostic. `StarExportIndexTest` (8 pins, all over
  scalars/strings — never an AST node, whose power-assert rendering is its whole subtree). Seven
  single-mistake ablations, each reverted before the next, **seven discriminated**: A1 export gate on
  functions, A2 variables last-wins, A3 `export * as ns` admitted, A4 edge list reversed, A5 export gate
  on interfaces, A6 only the last overload kept, A7 binding patterns admitted. **One pin is recorded as
  UN-ablated rather than claimed** — `an unresolvable specifier contributes no edge` cannot be broken
  here because `ReExport.target` is non-null, so the invariant is type-enforced. **A3's first form was a
  COMPILE ERROR and the driver printed `ran 0, failed 0`** — indistinguishable from "the mistake changed
  nothing" (round 808); it was re-cut as a compiling mistake, and the driver prints a per-arm
  `git diff --shortstat` plus the test count for exactly that reason.

- **WHAT DID NOT WORK / WAS NOT DONE.** The first JFR pass was discarded whole (the `--stack-depth`
  trap). **No per-line attribution is quoted**, although it is what shaped the fix: `Checker.kt` is
  178,013 lines, so a `LineNumberTable` line needs +65,536 OR +131,072, and INLINED stdlib appears at
  synthetic lines past EOF — the single largest line bucket in the raw data was one of those, and a
  number that needs three guesses to decode is not reportable. `ObjectAllocationSample` was recorded and
  NOT reported: round 801 already measured that an allocation count is not a cost here (367,189 `String`
  allocations removed = 0 ms), so the table would have been a column nobody may act on. (C2) the
  per-scope map copies and (C3) `ArrayList.clear` were left unpriced and queued as **(WARM.16)**, with
  the instrument each needs named.

- **GATES.** Suite **14,120 / 0 / 3** over all four modules (`xml.etree`), and 6/6 green runs for the
  re-cut pin. `cost_gate.py` **+0.00% on all 20 counters** — twice, once per deliverable.
  `huge_methods.py --fail-over 0` exit 0. **8-profile grid RUN BOTH DIRECTIONS with both arms rebuilt
  from source: added=0 removed=0 on all eight** (deliverable (B) changes `commonMain` behaviour, so it is
  not vacuous this time; deliverable (A)'s only edit sits after `close`'s `if (mode != ON) return` and
  its grid would have been).

**Round 867 (2026-08-09) — (WARM.14): `s_p` MEASURED, AND THE DISPATCH-TABLE QUESTION SETTLED AT ~1%.**
The production cost of one handler consultation that is entered and immediately declines is
**`s_p` = 2.286 ns [envelope 2.148-2.512]**, so **`R` = 32.0 M x `s_p` = 73.2 ms [68.7-80.4] = 1.06% of a
warm rebuild [1.00-1.17%], 1.47% of the `checkSpine` row.** Round 866's `[0, 352] ms` becomes `[69, 80]`,
and its 187 ms point estimate — the `d = d'` uniform-tax corner — was **2.6x too high**. The lever is
REAL and MARGINAL. `docs/perf/dispatch-table.md` § 9.

- **WHAT WAS AMPLIFIED, AND WHY THAT POPULATION.** Round 759's amplification, one level up: per node, `r`
  extra passes over **exactly the (handler, kind) pairs the derived table would SKIP** — the
  `enterTable`/`leaveTable` complement as a `Long` bitmask — all under ONE bracket, so `p(r) = boundary +
  r * (skeleton + S * s_p)` and two `r` values cancel the boundary algebraically. A **CONTROL arm**
  (negative `reps`) runs the identical loop with every consultation suppressed, which cancels the
  skeleton as well. Not "all 59 consultations": the prize is by definition what a table stops consulting.
  **The population is MEASURED rather than inherited and lands on § 8's numbers to the unit — 856,962
  bracketed nodes, 32,006,965 would-consult slots, S = 37.3493 against round 732's 37.35** — and its
  weighting needs no argument, because the bracket runs at every node of the real compile.
- **THE FALSIFICATION IS ARITHMETIC, NOT A PLAUSIBLE SLOPE (round 759's law), AND IT MATTERS HERE MORE
  THAN USUALLY: a rejecting consultation is exactly what a JIT can prove side-effect-free and DELETE, and
  that failure reads as `s_p ~ 0`, i.e. it CONFIRMS the closed direction for the wrong reason.** Four
  checks, green on **48/48 rebuilds** across both batches: `consults == r x expected` EXACTLY at every `r`
  (256,055,720 = 8 x 32,006,965; 3,072,668,640 = 96 x 32,006,965), with `expected` counted by a different
  code path (a `countOneBits` of the masks) from the one the passes count with; the control at **0**
  consultations over a population `> 0`; **`spineAmpPass` at 1,429 bytecodes = 4.4x HotSpot's 325-byte
  `FreqInlineSize`, so it cannot be inlined into the `r` loop and cross-iteration hoisting is
  STRUCTURALLY impossible** (a 307-byte leave-only second method was merged in for exactly that reason);
  and the control arm as a floor the real arm clears by 7.5x. Every rebuild also answered 78 files / 46
  errors.
- **THREE INDEPENDENT `r`-PAIRS, AGREEING TO 2.4%** (batch 2, `r` = 16/48/96, per-arm sd 2.0-5.0%):
  (16,48) -> 2.253 ns, (48,96) -> 2.308, (16,96) -> 2.286. **And a SECOND BATCH of a DIFFERENT DESIGN
  replicates it**: batch 1 (`r` = 8/16/32) reads 2.462 / 2.162 / 2.262, median **2.262** against batch 2's
  **2.286** — 1% apart, which is round 840(c)'s requirement met by construction rather than by draw.
- **BATCH 1's DESIGN WAS WRONG AND ITS OWN NUMBERS SAY SO — A REUSABLE TRAP.** It ran BOTH arms in ONE
  process and read a per-arm sd of **16-38%**, because **the two arms share one compiled `spineAmpPass`**:
  they differ only in the mask they pass it, so whichever runs first writes the branch profile the other
  is compiled against, and an arm whose consultation branches were profiled as NEVER TAKEN pays an
  uncommon trap for each of them (`p4`'s controls were the batch's cheapest and its reals its dearest, by
  2x). Batch 2 gives each arm its own JVM, discards a leading throwaway amp rebuild (the uninstrumented
  loop never touches `spineAmpPass`, so the first amp tier is the one that warms it), and widens the
  spread. Per-arm sd falls to 2-5%.
- **THE DECISION, OUT LOUD.** `R` clears the 1% floor and only just — the envelope's low end is AT 1.00%.
  So (DISPATCH.1) is **not confirmed closed warm and is not a 2.7% prize**: it is a ~1% lever, real by
  this arc's standards and marginal by its measurement standards (`ab-warm.sh`'s band is +-1.0%, so a
  landed table could not be confirmed by the harness that would judge it). **Nothing was built.**
  (WARM.13) re-opens as **(WARM.13b)**, an implementation item, with one number that changes its outlook:
  **the CONTROL arm is a direct measurement of the proposed per-kind `Long`-bitmask dispatch skeleton — a
  mask load plus 59 register-resident bit tests — at 13.16 ns per node = ~11.3 ms whole-compile, 15% of
  the prize.** Round 866's `+715 ms` GATED tax was the `IntArray` walk, the 46-arm tableswitch and the
  extra frame; it was never the idea. Net ~62-73 ms. The 8,000-bytecode cliff stays a hard constraint on
  the `when (kindId)` shape (-33.6% warm, round 845) and is the second reason to prefer the bitmask.
- **THE BIAS THE INSTRUMENT CANNOT REACH, NAMED RATHER THAN IMPLIED — this round's `d`.** The amplified
  consultation happens at `spineAmpPass`'s call site, not at `spineEnterNode`'s. A census says **36 of the
  59 handlers are under `FreqInlineSize`** (median 257 bytecodes) and 23 are over it, so for a third of
  the population both sites must call while for the rest the two sites' inlining decisions could differ,
  in an unknown direction. A second, SIGNED bias: the amplified slot pays one bit test (~0.22 ns, from the
  control arm's 13.16 ns / 59 slots) that production does not, pushing `s_p` toward the low end.
- **PINS + ABLATION (one mistake at a time, round 807).** `SpineAmpProbeTest` (5) + `BenchAmpTierTest`
  (5). Five single-mistake ablations, each reverted before the next: an INVERTED skip mask -> only `the
  skip mask is exactly the complement of the dispatch table`; a loop pinned to one iteration -> only `the
  real arm performs exactly reps times the would-consult population`; a control arm handed the REAL masks
  -> only `the control arm suppresses every consultation over the same population`; `tierStop`'s
  disarm deleted -> `a following tier is not silently amplified` (+1); the arm's sign dropped from the
  tier parser -> `the tier parser reads the amplification factor and the arm's sign` (+2). **One pin did
  NOT discriminate and is recorded as such rather than claimed: `amplification does not change what the
  compiler answers` stayed GREEN under the inverted mask** — re-running the KEPT handlers is idempotent
  on that fixture too — so it is an equivalence guarantee, not a seam pin.
- **GATES.** Suite **14,120 / 1 / 3** over all four modules (`xml.etree`); the one failure is
  `PostCheckerPartitionTest`'s `residue * 4 < post` ratio over a **272 us** region, **green in isolation**
  and structurally unreachable from a spine-prologue change — a timing-flaky pin, not a regression.
  `cost_gate.py` **+0.00% on all 20 counters**; `huge_methods.py --fail-over 0` **exit 0** (the new
  `spineAmpPass` is 1,429 bytecodes, 5.6x under the limit and 4.4x over `FreqInlineSize`, which is the
  point). **The 8-profile grid is VACUOUS BY CONSTRUCTION this round and was not run:** the only
  `commonMain` behaviour change is gated on `SpineAmp.reps != 0`, which is 0 in every run that does not
  pass `--spineAmp`, and the cost gate's own compiler-profile compile is unchanged at 46 errors.
