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

**Round 864 (2026-08-09) — (WARM.11): THE 4.20% "FLOW WALK" IS **TWO** WALKS, AND THE SECOND ONE IS NOT A
FLOW WALK — `FlowGraph`'s nodeId SIDE TABLE, **162.3 ms**, VISITING **876,324** NODES TO ANSWER **168**
QUERIES. FILLED FROM THE NODES `recordFlow` WROTE INSTEAD: **172.99 -> 58.63 ms = 114.4 ms = 1.665% of a
warm rebuild, a 66.1% fall**.** `docs/perf/warm-flow-graph-attribution.md`.

- **THE COLD PARTITION STOPPED ONE LEVEL SHORT AND NAMED ITS REMAINDER AFTER WHAT IT EXPECTED TO FIND.**
  Round 801 subtracted the three B464 collectors from `BIND_FLOW` and called the remainder "the flow walk";
  round 859 carried that forward warm as 316.7 ms = 4.20%, the largest region outside `checkSpine`.
  Partitioned (residue **0.05%** — `build()` is four statements, so the split is exhaustive by
  construction): the flow-MINTING walk is **196.3 ms** and `FlowGraph`'s CONSTRUCTOR is **163.5**, of which
  **162.3** is a second whole-tree `forEachChild` traversal that boxes a `(pos,end)` `Long` per node and
  asks the flow map. **A residue is not a measurement of whatever you then call it** — round 758 says that
  about `checkSpine`'s "dispatch machinery, 42%", and here the residue was 57% the thing it was named after.

- **ROUND 788 ANSWERED BY CENSUS, BEFORE THE FIX AND NOT AFTER IT.** The table has ONE reader, `flowAt`,
  which falls back to the same map lookup for any node it does not own — so filling it from the RECORDED
  nodes is exactly equivalent and the only work it MOVES is the query-time lookup for an unrecorded node.
  Measured on the UNCHANGED binary: **176,935 `flowAt` calls, 168** of them on an in-tree node with an empty
  slot. **612,220 build-time lookups existed to spare 168 query-time ones; produced-to-consumed 0.0003, not
  1.000.** After the change the same census reads `map-fallback 168` — the identical set, on the other side
  of `flowAt`.

- **THE CENSUS ALSO SETTLED THE ALIASING QUESTION THE KDoc RAISES AND NOBODY HAD COUNTED**: `recordFlow`
  writes **262,404** keys with **0** collisions, and **1,700** in-tree nodes are nonetheless answered from a
  same-extent sibling's key. So aliasing is real on the READ side and absent on the WRITE side — which is
  why the fill RE-READS the finished map per entry instead of storing the flow it saw at record time, and
  why an implementation that stored `currentFlow` directly would be wrong on a program this profile does not
  contain (round 792).

- **MEASURED** (`BenchMain <proj> 3 8 frontend,frontend`, 2 processes x 2 draws per arm, all 8 instrumented
  rebuilds answering 78 files / 46 errors): **192.48 / 162.33 / 180.66 / 156.48 -> 65.49 / 51.48 / 66.31 /
  51.21 ms**. Round 846's first-draw-is-slowest law holds **4/4**; on second draws only the saving is
  **108.1 ms = 1.573%**, the conservative figure. Census flips **876,324 nodes @ 30% answered -> 262,404 @
  100%**. **The neighbouring MINT row also reads ~20 ms lower and that is NOT claimed** — the change can
  only ADD work there (one list append per record), so it is draw noise or the young-gen relief of 612,220
  fewer boxed `Long`s.

- **`--flowIndexLegacy` KEEPS BOTH FILLS IN ONE BINARY** (round 795: build the verify flag so it doubles as
  the instrument), which is also what makes the 8-profile grid stronger than a two-class-dir one here: the
  flag picks the pre-864 path inside the COMMITTED binary, so a stale class dir cannot make the arms agree.

- **A PRICED NEGATIVE IN THE SAME REGION: `mutableMapOf()` -> `HashMap()` on the flow map measures NOTHING**
  — the mint row reads **245.2 vs 236.2 ms** all-draw and **177.0 vs 187.5** on second draws, i.e. **-8.9 ms
  one way and +10.5 the other, the sign undecided**. 262,404 puts is not enough for `afterNodeInsertion` to
  clear a row whose first draws span 261-343 ms. **Reverted** rather than kept: an unmeasurable change to a
  narrowing-critical structure buys risk and nothing else, and round 801 kept its own zero-effect change
  only because it was one arm of a verifier. Third measured instance of *an operation count is not a cost*.

- **NOT ATTEMPTED, WITH THE ARITHMETIC: a per-node partition of the MINTING walk.** ~857,000 nodes x round
  850's warm 97-202 ns is **83-173 ms against a 196 ms row** — the instrument would BE the measurement.
  Anything inside that walk must be priced by counters plus an arm, which is exactly how the `HashMap`
  question above was decided.

- **ABLATION, FOUR FAULTS ONE AT A TIME (round 807), 120 pins per arm.** **M1** the arm made INERT -> **1
  RED**, uniquely the non-vacuity pin — with the flag inert the differential compares a binary against
  itself and passes forever, which is the blind-pin mechanism the pin exists for; **M2** the fill reads the
  WRONG KEY -> **10**, uniquely its own being the "answered by the map" pin, and it also reddens
  `FlowScanEquivalenceTest` / `NarrowableRootsPreTestTest` / `Inv2FlowLookupTest`, so a wrong flow answer is
  visible to the narrowing pins; **M3** the fill also claims the recorded node's PARENT -> **2**, and it is
  reported as having **no uniquely-its-own failure** (a strict subset of M2's set) — the dangerous direction
  is caught by the general net, not by a named pin; **M4** `recordFlow` stops listing `Identifier` nodes ->
  **0**, GREEN ON PURPOSE, because a missing slot degrades to the map fallback. M4 is the safety argument
  demonstrated rather than asserted: **completeness of the list is a speed property, and the correctness
  obligation is the other direction — never fill a slot for a node `recordFlow` did not write.**

- **GATES.** Suite 14,090 -> **14,094 / 0 failures / 3 skipped** (real XML parser over all four modules);
  `cost_gate.py` **+0.00% on all 20 counters**; `huge_methods.py --fail-over 0` **0 over the limit**, 655
  classes; 8-profile grid **`added=0 removed=0` in BOTH directions** on every profile (46x7 / 94, no
  truncated or empty capture); compiler-profile **emit tree byte-identical, 78 files**. Round-851 order
  throughout; the tree was committed before the ablation (round 789) and is clean after it. Commits
  `ae84496e`, `035446ea`, `2cee9cf5`, `538aac53`.

**Round 863 (2026-08-09) — (WARM.10): THE WHOLE-PROGRAM REGEX CLASS IS SWEPT SYSTEMATICALLY RATHER THAN
HIT BY ACCIDENT, AND THE ONE OFFENDER LEFT WAS ON THE **EMIT** PATH, WHERE NO INSTRUMENT IN THIS REPO
COULD SEE IT: `Transformer.transform`'s jsxRuntime pragma scan, **44.1 ms -> 1.66 ms = 42.5 ms = 0.53% of
a warm EMIT rebuild, a 96.2% fall.**** `docs/perf/whole-program-regex-census.md`.

- **THE CLASS, STATED SO MEMBERSHIP IS DECIDABLE.** `Pattern.compile` hands a pattern to `BnM.optimize`
  (Boyer-Moore) only when its root is a literal `Slice`, and `BnM.optimize` returns the node unchanged when
  that literal is **shorter than four characters**. So `\b…`, `(?m)^…`, an alternation, a character class, a
  lookaround **and a one-to-three-character literal** all produce a `Start` root whose `match` loops over
  every offset. A bare `^` WITHOUT `(?m)` is the exception — it compiles to `Begin` and is attempted once.
  Rounds 860 and 862 each found one member of this class by accident; this round enumerated it.

- **THE CENSUS IS THE ROUND'S FIRST DELIVERABLE AND IT IS COMPLETE.** All **110** `Regex` construction
  sites in `commonMain`, classified by SUBJECT (whole-file / whole-json / substring / small), by frequency
  and gate, and by literal prefix. **21 have a WHOLE-FILE subject; exactly ONE is both ungated and
  prefix-less on the compiler profile** — `Transformer.kt:488`, whose leading literal is a slash-star, TWO
  characters, run over the full text of every transformed file under **no gate whatsoever**.

- **IT WAS INVISIBLE FOR A STRUCTURAL REASON, NOT AN OVERSIGHT.** Round 738's `skipEmitOutputs` gate means
  `--noEmit` never enters `Transformer.transform`, so `BenchMain` (check-only in three hard-coded places),
  `cost_gate.py` and the `--noEmit --listAll` 8-profile grid are blind to it **at once**. The instrument had
  to be built before the defect could be priced — the same shape round 859 recorded for `FrontEnd` and round
  851 for the largest spine handler, one level further out: **it had no MODE, not just no tier name.**

- **BUILT: `BenchMain`'s EMIT mode** (5th argument, parsed by `parseEmitFlag`, split out because `main`
  compiles a whole project and no pin can run it). Its vocabulary is CLOSED — an unknown 5th argument is an
  error, never a silent `false`, because the damage is not a crash but a run that quietly measures the OTHER
  mode, and the two modes are different compiles (round 739). Plus `FrontEnd.TR_JSXPRAGMA`, one timestamp
  pair per FILE inside `TRANSFORM`, with the population census that separates "this row is big" from "this
  row is big and buys nothing".

- **MEASURED** (`BenchMain <proj> 3 8 frontend,frontend emit`, 2 processes x 2 draws per arm, all 8
  instrumented rebuilds answering 78 files / 46 errors): before **44.139 ms** (45.886 / 44.092 / 43.243 /
  43.336, spread 6.0%), after **1.662 ms** (1.711 / 1.665 / 1.637 / 1.637, spread 4.5%) — **42.48 ms =
  0.531%** of the before arm's warm emit wall, the row falling **96.2%**. Census bit-identical in both arms:
  **78 files, 9,977,097 characters, 0 pragmas found.** Cold the same row reads 62 ms, i.e. it warms 1.4x
  against `checkSpine`'s 3.27x — exactly the ratio round 859 measured for the other members of this class.

- **THE PARENT ROW CANNOT SEE THE SAVING, AND SAYING SO IS PART OF THE RESULT.**
  `Transformer.transform` reads 782 / 789 / 894 / 919 ms before and 860 / 862 / 889 / 948 after: a 17.5%
  draw spread with a ~14% BETWEEN-PROCESS effect inside the before arm alone, **3x the 42 ms being
  measured**, so a before/after comparison of the parent row would have reported the wrong SIGN. The
  sub-row is the measurement. Round 854's lesson about differencing a `--passTiming` row across arms, one
  level in.

- **THE CLASS IS NOW EXHAUSTED ON THIS PROFILE, AND THAT IS MEASURED.** A JFR discovery run of the same
  warm EMIT compile: **64 of 9,541 samples** carry a `java.util.regex` frame and **54 of those 64 are
  `Transformer.transform`**; the rest is `RealLibResolver.referencedLibNames` (7 samples ~ 6 ms ~ 0.07%),
  the include/exclude globs, `Parser.checkTripleSlashSelfReference` and `Transformer.generateModuleTempName`
  at one each. No fourth offender above the round's 0.2% floor; what was left is named. Used for DISCOVERY
  only — round 623's law stands, a JFR self-% is not a price. (Trap worth keeping: `jfr print` truncates its
  DISPLAY to five frames unless `--stack-depth` is passed, which reads exactly like "the JVM did not record
  the caller" — the first aggregation attributed 59 of 60 samples to `<truncated>`.)

- **ROUNDS 793, 801 AND 846, ANSWERED BEFORE THE SAVING WAS QUOTED.** No boundary vanishes — the probe is
  present in BOTH arms with 78 calls, so there is nothing to subtract. Nothing MOVES — the scan's only
  output is one boolean per file, the census reads 0 pragmas in both arms, and the emitted tree is
  byte-identical; this is the same value computed by a cheaper method, not a skip whose work reappears. And
  first-draw-is-slowest reproduces in 1 of 2 before-arm processes (the other pair inverts by 0.2%); dropping
  every first draw gives 42.06 ms = 0.526%, inside the quoted figure.

- **LANDED AS AN EXACT REWRITE, NOT A GATE** — round 860's law, for round 792's reason: a gate is a claim
  about where a construct may legally appear and these profiles cannot falsify one. `scanJsxRuntimePragmas`
  is anchored on the literal `@jsxRuntime` via `indexOf` (tsc's sources carry **0** of those in 9,977,097
  characters, so the scan is one linear sweep), `jsxRuntimePragmaRegex` stays LIVE as the specification a
  12,000-case differential holds it to, and the rewrite removes a regex-DIALECT hazard from `commonMain`.
  The two non-obvious terms: regex `\s` is **narrower** than `Char.isWhitespace()` (which also accepts
  `Character.isSpaceChar`, i.e. NBSP), and matches may not OVERLAP — a pragma whose closing star-slash is
  followed by another star offers the next candidate an opening slash-star that `findAll` never sees.

- **MOST OF THE CLASS IS GATED TO ZERO ON THIS PROFILE, NOT ABSENT, AND NOTHING WAS DONE ABOUT IT.** Nine
  whole-file prefix-less matchers cost nothing here only because tsc's own sources carry no `.js`, no
  `.d.ts`, no `resolveJsonModule` and a NodeNext module setting: on an `allowJs` project FOUR independent
  lazy JSDoc-block scans sweep the same text, on a `commonjs` project every file containing the word `await`
  gets a `(?m)^` sweep at every parse site, on a `@types` tree the UMD pattern returns. Per round 792 a
  profile that does not contain a shape cannot justify a change about it — they are recorded in the census
  so the next agent measuring a different project shape knows where to look, and not acted on.

- **ABLATION, SIX MISTAKES ONE AT A TIME (round 807), 26 pins per arm.** **M1** the non-overlap cursor
  dropped -> 2 RED, uniquely the overlap pin; **M2** `\s` widened to `Char.isWhitespace()` -> 2, uniquely
  the NBSP pin; **M3** the forward `\s+` weakened to `\s*` -> 2, uniquely the near-miss pin; **M4** the
  transformer takes the FIRST pragma instead of the LAST -> 1, uniquely `the last pragma in the file wins`;
  **M5** the harness flag defaults instead of failing -> 1, uniquely its own negative control; **M6** the
  scanner finds NOTHING -> 6, uniquely both END-TO-END positive controls. **Every mistake has a
  uniquely-its-own failure and no two failing sets coincide.** M6 exists because the differential battery is
  weakest against an all-empty scanner (which agrees with the oracle on every case the oracle also rejects),
  and it is what proves the positive controls are load-bearing rather than redundant guards. Stated plainly:
  **the battery itself never fails ALONE** — it is the general net, the only pin that would catch an
  unanticipated divergence, but it attributes nothing and is not claimed as coverage. M4 is the one that
  matters most, on round 862's pattern: taking the first pragma is silently WRONG rather than slow.

- **GATES.** Suite 14,072 -> **14,090 / 0 failures / 3 skipped** (real XML parser over all four modules);
  `cost_gate.py` **+0.00% on all 20 counters** at every sub-step; `huge_methods.py --fail-over 0` **0 over
  the limit**, 653 classes; 8-profile grid **`added=0 removed=0` in both directions** from two class dirs
  (652 vs 653, round 853's positive control) — run as a CONTROL, because it is structurally blind to a value
  that reaches no diagnostic — and the gate that CAN see it, **EMIT mode `diff -r` of the compiler profile,
  78 files from each arm, IDENTICAL**. Round-851 order throughout; the before arm was rebuilt in the
  FOREGROUND with `git status` checked either side (round 805). Commits `fa2e5f27`, `49256c56`, `9c7425a2`.

**Round 862 (2026-08-09) — (WARM.8)(c) LANDED, AND THE QUEUE ITEM WAS THE SMALLER HALF OF ITS OWN
PRIZE: `cpcRequireOnlyOrphans` GOES 138.7 ms -> 0.0005 ms AND THE WHOLE POST-CHECKER REGION
141.9 -> 2.84 ms = 139.1 ms = 1.82% OF A WARM REBUILD, 98.0% OF THE REGION — OF WHICH 136.4 ms
(1.79%) IS AN EXACT REWRITE THAT HELPS **EMIT** MODE IDENTICALLY AND ONLY 2.3 ms (0.03%) IS THE
CHECK-ONLY GATE THE ITEM NAMED.** `docs/perf/warm-tail-attribution.md` § 13.

- **THE ONE-CONSUMER CLAIM HELD, RE-ESTABLISHED RATHER THAN INHERITED.** Four `grep` hits for the
  value (declaration, call, `return`, one use) and **one** write site for `jsOutputMap`, inside
  `cpcTransformAndEmit`, whose loop iterates `emptyList()` under `skipEmitOutputs` — so the census is
  provably dead work in a check-only compile. The gate is `CompilerOptions.skipEmitOutputs`, never
  `options.noEmit`, and `SkipEmitOutputsTest` was read before writing it.

- **BUT THE ROUND TURNED ON THE ITEM'S OWN OPEN QUESTION** ("whether the walk itself can be made
  cheap … is a second, larger question this round did not price"). It is not a second question: it
  is the SAME 1.7%, available in both modes. Sub-partitioned into its three per-FILE scans (three
  abutting level-3 `FrontEnd` blocks that sum to the block EXACTLY), the function is **87.36% one
  regex** — the `declare … require` probe, 121.15 ms — against 12.13% for the `import("…")` scan and
  0.269% for the statement walk. Census, bit-identical in all four draws: **78 files, 9,977,097
  characters, 0 accepted.**

- **THE MECHANISM IS A `java.util.regex` FACT, AND IT COMES WITH ITS OWN CONTROLLED COMPARISON.**
  `\bdeclare\s+…` begins with a ZERO-WIDTH ASSERTION, so `Pattern.compile` cannot hand it to
  `BnM.optimize` (which needs a leading literal `Slice`) and it is attempted at every one of the
  9,977,097 positions. The sibling pattern in the SAME loop over the SAME text, `import\s*\(…`,
  begins with a literal and costs **a seventh as much**. Second time in three rounds that the most
  expensive thing in a region is a whole-program regex matching nothing (round 860 was the first).

- **WHAT LANDED — two equivalences and then the gate, three commits.** (a) `925d4d24`:
  `containsDeclareRequire`, an exact hand-written scan anchored on the literal `require` (571
  occurrences, one linear sweep plus 571 constant-time rejections), with `declareRequireRegex` kept
  LIVE as the specification — round 860's law, an exact rewrite rather than a *gate*, since a gate is
  a legality claim the profiles cannot falsify (round 792); the same commit DEFERS the `import("…")`
  pass behind the candidate sets, which is sound because `staticallyReferenced` enters the final
  filter only as `it !in staticallyReferenced`, beside `(it in requireReached || it in
  nsInternalImportTargets)`. (b) `2926050a`: the gate itself.

- **THE MEASUREMENT** (`BenchMain <proj> 3 8 frontend,frontend`, 2 processes x 2 draws per arm, all
  12 instrumented rebuilds answering 78 files / 46 errors). HEAD **138.68 ms** (draws 136.53 /
  129.32 / 145.66 / 143.20, spread 11.8%); after (a) **2.310 ms** (2.278 / 2.389 / 2.284 / 2.289,
  spread 4.8%); after (b) **0.0005 ms**. `POST` 141.94 -> 5.59 -> 2.84 ms. **Round 793:** the only
  boundaries that disappear are `ORPH_IMPORTTYPE`'s 78, worth 7.6-15.8 us at round 850's warm
  97-202 ns — five thousandths of a percent of the saving. **Round 801:** nothing MOVES and the ratio
  is 0/1, not 1.000 — the deferred set is a local `val` with one reader, the gated set's one reader
  gets an empty map, and the rewrite skips nothing at all. **Round 846:** the first-draw-is-slowest
  law reproduces **2/2 in the before arm** and is invisible once the row is 2 ms; dropping first
  draws moves the before mean to 136.26 ms and the saving to 1.79%, inside the quoted figure.

- **THE WARM A/B IS QUOTED AND SET ASIDE, which is the rule this arc wrote for itself.**
  `scripts/ab-warm.sh /tmp/r862-before-main /tmp/r862-after-main 3` printed verbatim: `VERDICT: WIN
  of 2.3% (B wins 3/3) — outside the +/-1.0% warm band. Warm-only: it is a steady-state COMPUTE
  claim, not a cold-CLI claim.` Median -158 ms (-2.32%), 3/3 sign-consistent, bracketing the rows'
  138.7 ms — but **arm A's printed sd is 2.76%** against arm B's 0.46%, and round 774's rule discards
  an arm over ~1% however clean its median. Direction confirmed, magnitude not; the rows are the
  evidence.

- **WHAT DID NOT WORK / WHAT THE ROUND CANNOT SAY.** (1) The 8-profile grid, which the round plan
  made mandatory, is **structurally blind to this change** and was run as a control: the value
  reaches nothing but the list of emitted JS files and a `--noEmit --listAll` capture has none. It
  is green (`added=0 removed=0`, both directions, 46x7 / 94, two class dirs at 651 vs 652 classes),
  and the gate that can actually see the change is the EMIT one — 78 files from each arm, `diff -r`
  identical. (2) **No cold measurement was taken**; round 859's finding that regexes warm worst in
  the tail (0.85-1.05x) makes the ~121 ms likely to be roughly the same cost cold, but that is an
  inference. (3) **The emit-mode saving was not TIMED**, only shown to be the same code path with the
  same empty candidate sets. (4) The gate's own value collapsed from the queued 1.72% to 0.03% once
  (a) landed under it; it was kept anyway because the two savings have DIFFERENT shapes — (a)'s
  depends on the program carrying no `declare … require`, and on a program that does carry the shape
  pass 2 runs over every file and the gate is what a `--noEmit` build saves.

- **ABLATION, ONE MISTAKE AT A TIME (round 807), 20 pins per arm.** **M1** the scanner drops its
  `require\b` test -> **3 RED**, all in `DeclareRequireScanTest`; **M2** the gate made inert -> **1**,
  the census-skip pin; **M3** the gate widened to `options.noEmit` (round 738's mistake) -> **1**, its
  own negative control; **M4** pass 2 skipped ALWAYS -> **2**, the behaviour pin (`a file also reached
  by a typeof-import is still emitted`) and the partition pin. **Four disjoint failing sets, every
  mistake with a uniquely-its-own failure, no redundant guard.** M4 is the one that matters: skipping
  pass 2 unconditionally is silently WRONG, not merely slow — it drops a live file from the emit.

- **GATES.** Suite 14,061 -> 14,062 -> 14,070 -> **14,072 / 0 failures / 3 skipped** (real XML parser
  over all four modules); `cost_gate.py` **+0.00% on all 20 counters** at every sub-step;
  `huge_methods.py --fail-over 0` **0 over the limit**, 652 classes. Round-851 order throughout, and
  the before-arm was rebuilt IN THE FOREGROUND with `git status` checked before and after (round
  805). Commits `ccd0de2b`, `925d4d24`, `2926050a`, `366d5bd4`.

**Round 861 (2026-08-08) — (WARM.9): THE LAST TAIL PASS OVER 1% IS PRICED WARM AND IT IS A
NEGATIVE — 85.6 ms = 1.09%, NOT THE ~1.68% PROJECTED, AND THE NUMBER IS AN UPPER BOUND THE
INSTRUMENT CANNOT TIGHTEN.** Round 859 found exactly one tail pass over 1% warm
(`init:buildFileLocalTypeMaps`, 3.56%) and priced its deletable half by projecting round 829's cold
population share onto the warm row — explicitly labelling it arithmetic, not measurement. This round
replaced it with a measurement. `docs/perf/warm-tail-attribution.md` § 11.

- **BUILT: `BenchMain`'s `fltm` tier, and it is the only tier that arms TWO probes.** `--fltmCensus`
  had existed since round 829 and had never been run in a warm process for the reason round 859 gave
  about `FrontEnd` and round 851 gave about the largest spine handler — **the instrument had no tier
  name.** The tier arms the census AND `PassTiming`'s `rows`, because the census prices a
  SUB-population of a pass whose own row it does not measure: taken from two rebuilds, prize-over-row
  would be a cross-draw ratio against a row whose warm draw spread round 859 recorded as 41%. Paired,
  every ratio is within-rebuild, and they come out 4-6x tighter than either operand
  (direct-wall/row 92.1/96.4/92.3/96.3%; deletable/row 36.0/36.2/34.2/36.8%).

- **THE MEASUREMENT.** Two processes, tier order ROTATED, two draws per tier each = 4 census draws /
  8 row draws; all 8 instrumented rebuilds answered 78 files / 46 errors. Row: mean **253.5 ms**,
  median 243.5, spread 48% — but **the maximum of each tier in each process is that tier's FIRST
  draw, 2/2**, which is round 846's "the probe's own cost warms up" appearing in a pass row rather
  than a wall; dropping the first draws gives 237.1 ms at 16%. Census (bit-identical counts across
  all four draws): direct-resolve wall **225.4 ms = 94.3% of the row**, FULL deletable **91.25 ms**,
  `decl`-branch deletable **85.6 ms = 35.8% of the row = 1.086% of the warm wall** (per-draw
  1.00-1.15%). The absolute spreads are 14-18% and **the SHARE's spread is 5.9%** — round 854's law
  reproducing on a third instrument.

- **WHY THE PROJECTION OVERSHOT 1.54x — three deflations, all general.** 47.1% is a COUNT share of
  the resolves while the ms share of the direct-resolve wall is 40.5% (x0.860); that wall is only
  94.3% of the pass ROW, the rest being the 78-file loop, the flags tests, the map writes and the
  bail save/restore (x0.943); and 6.0% of the deletable ms is the `typealias` branch, which is the
  TS2589/TS2615 depth-bail DETECTOR — not resolving it deletes a diagnostic rather than deferring one
  (x0.940). Product x0.762: 1.68% -> 1.28%, and the rest is the row's own cross-round drift.

- **WHAT DID NOT WORK, i.e. what the round was unable to settle and says so.** The plan allowed
  implementing if the prize cleared ~1%. It clears it by 0.09%, and **the decision is still a
  negative, for a reason that would hold at 1.5%: the census cannot bound its own upper bound.** Its
  MOVE test (round 788) is keyed on SYMBOLS, and the pass's cost is not symbol-level — its own report
  says `getTypeOfSymbol` entries during the pass are **14,580** against **12,738** direct ones, so
  the 12,738 resolutions make **1,842** nested symbol asks between them and reach only **434**
  symbols they did not start from. Their 225 ms is therefore `getTypeFromTypeNode` / member
  resolution / type interning, **none of which is symbol-keyed**, so "never asked again" is tested at
  the one granularity that carries almost none of the work. Round 829 printed that line and nobody
  read it this way. **Settling it needs a REPLAY ablation** (record the deletable `file|name` keys in
  one rebuild, skip exactly those in the next, demand byte-identical output) — which was scoped,
  costed and deliberately NOT attempted here, because on its own it is a round.

- **AND NO INSTRUMENT IN THIS REPO COULD DEFEND THE CHANGE.** `ab-warm.sh`'s band is +-1.0% and the
  effect is 1.09% (round 860's 1.17% produced two batches that disagreed). Round 860 was defensible
  on ROWS because a 50 ms row went to 0.3 ms against a 14-19% draw spread; here the row would fall
  253 -> ~168 ms, 34% against a 16-48% spread, and since a lazy rewrite MOVES cost between rows the
  row is the wrong denominator anyway — `checkerInitNanos`'s draw spread over these 8 rebuilds is
  **11.0%, 8.7x the effect**. Two supporting readings, free from the same data: the deletable
  resolutions are the CHEAP ones (**15.2 us** each against **19.9 us** for the rest — round
  758/759's law, predicate and cost sharing a cause), and the census's population counts are
  **identical to round 829's cold ones** across 32 rounds and two JIT regimes, only the read site
  moving (16,043 -> 16,183 calls, 278,355 -> 280,408 misses).

- **THE PIN WAS ABLATED, because it had a discrimination problem `BenchFrontEndTierTest` does not.**
  `FrontEnd`'s recording entry points self-gate (`if (mode != ON) return`), so recording through them
  proves the arm; `FltmCensus`'s do NOT — every hook in `Checker` reads
  `if (FltmCensus.on) FltmCensus.noteX(...)`, i.e. **the guard is at the CALL SITE**, and a fixture
  calling `noteX` directly would record with the arm deleted (round 807's blind-pin mechanism). The
  fixture reproduces the call-site idiom verbatim. Ablated on a clean tree after the harness was
  committed (round 789): deleting `FltmCensus.on = true` from `tierBegin` reddens **2 of 5** pins,
  and they are the two naming that arm; the pass-rows pin correctly stays green because it names the
  other probe the tier arms.

- **GATES.** Suite **14,056 / 0 failures / 3 skipped** (counted with a real XML parser over all four
  modules' `*/build/test-results/jvmTest/*.xml`); `cost_gate.py` **+0.00% on all 20 counters**;
  `huge_methods.py --fail-over 0` **0 over the limit**, 651 classes. Round-851 order throughout:
  every gradle-invoking step before the daemon stop. Round-853 positive control: the test class dir
  holds `BenchFltmTierTest.class`, a class that did not exist before this round. **The 8-profile grid
  was NOT run and is vacuous by construction — nothing under `commonMain` changed** (the only code
  added is a `commonTest` tier and its pin), which `cost_gate.py`'s 20 unchanged counters and the
  unchanged 78 files / 46 errors independently confirm. Commit `3909ae55`.

- **SECOND DELIVERABLE — (WARM.8) ATTRIBUTED, AND IT IS ONE FUNCTION.** Round 859's post-checker
  tails (143.2 ms = 1.90%, warming 1.27x, no probe below them) are **97.6% `cpcRequireOnlyOrphans`**:
  **130.4 ms = 1.72% of a warm rebuild** with a **4.1% draw spread**, the tightest number in the warm
  arc, measured over 4 draws in 2 processes. Two levels of abutting `FrontEnd` blocks, **residue
  0 ms at both levels in every draw**; everything else after the checker sums to **~3.1 ms**. **THE
  PRIOR WAS WRONG BY 800x** — the obvious suspect was `topologicalSort` over 78 barrel-connected
  files, which reads **0.17 ms** (and `hasCycle` + the dep map, 0.01 ms); the second partition level
  exists precisely so that prior could not become a sentence in a document. The cost is a
  whole-program AST walk added for a two-file corpus fixture (`moduleResolutionWithRequire`).
  `PostCheckerPartitionTest` drives a real multi-file `ProjectCompiler` build, because the invariant
  is the boundary PLACEMENT and a dropped `close` is invisible to every output, to `cost_gate.py` and
  to the corpus. Gates re-run per sub-step: suite **14,060** then **14,061 / 0 / 3**, `cost_gate.py`
  **+0.00% on all 20** both times, `huge_methods.py --fail-over 0` **0 over the limit** both times.
  Commits `61194621`, `9eedc04b`.

- **NEXT.** **(WARM.8)(c)**, queued with its equivalence already proved BY CONSTRUCTION rather than
  by census: `cpcRequireOnlyOrphans` has exactly ONE consumer, which under `--noEmit` reads a map
  that round 738's emit gate leaves empty — so `if (options.skipEmitOutputs) emptySet() else ...`
  deletes 1.72% of a warm check-only rebuild, needs no round-788 census and no round-793 boundary
  subtraction, and is invisible to the corpus fixtures it exists for because they run WITH emit. It
  is a check-only lever and moves the CI emit ratio by zero; say so when landing it.

**Round 860 (2026-08-08) — (WARM.7) LANDED: THE DUPLICATED 10-MEGABYTE UMD SCAN IS GONE, 96.4 ms
-> 4.7 ms = 1.17% OF A WARM REBUILD — AND THE HALF ROUND 859 SAID "NEEDS A GATE" NEEDED NO GATE AT
ALL.** The first optimizing round of the warm arc, executing round 859's own recommendation.
`docs/perf/warm-tail-attribution.md` § 10 is the permanent record, appended to the document that
priced the candidate so that predicted and actual sit together.

- **WHAT LANDED, IN TWO INDEPENDENTLY GATED COMMITS.** **(a) `5462fa75`** — both
  `checkUmdGlobalVsDeclareGlobalConst` and `checkCrossFileModuleAugmentationDuplicates` read one
  per-FILE memo (`Checker.umdExportAsNamespaceOccurrences`) instead of each compiling and running
  its own `(?m)^[ \t]*export[ \t]+as[ \t]+namespace…` over the full text of every checked file.
  The shared value is the NARROWEST thing both consume — the group-1 identifier and its offset —
  and it is a pure function of the file text, consulting no ambient state, so it cannot be the
  program-ORDER dependency of rounds 754/776/778. **(b) `c9b693cd`** — the matcher itself is
  replaced by a hand-written EXACT equivalent, anchored on the literal `namespace` via
  `String.indexOf` (494 occurrences in 9,977,097 characters, so one linear sweep plus 494
  constant-time rejections).
- **THE ROWS, which are the primary evidence** (`BenchMain <proj> 3 8 rows,rows`, one process and
  two instrumented draws per arm, three arms from one session, daemons stopped). Arm A = HEAD
  `213292cb`: `checkCrossFileModuleAugmentationDuplicates` **50.1 ms**,
  `checkUmdGlobalVsDeclareGlobalConst` **46.3 ms**, together **96.4 ms = 1.233%** of arm A's warm
  wall (7,821 ms) — **round 859's 98.2 ms / 1.30% replicates on a different day and build.** After
  (a): **54.2 / 0.6 = 54.9 ms**, saving **41.5 ms = 0.531%**. After (b): **4.3 / 0.3 = 4.7 ms**,
  saving **91.7 ms = 1.173%**.
- **AND THE ROW THAT MOVES AFTER (a) IS THE OTHER ONE — a pass-order fact round 859 could not have
  stated.** `checkCrossFileModuleAugmentationDuplicates` is init step 73h and
  `checkUmdGlobalVsDeclareGlobalConst` is 73j, so the memo is FILLED by the former and SERVED to the
  latter: the second row collapses to 0.6 ms while the first is unchanged. Anyone reading "run the
  scan once, ~49 ms" would have watched the wrong row.
- **THE `.d.ts` GATE WAS DECLINED ON ITS OWN TERMS, AND THAT IS THE ROUND'S TRANSFERABLE RESULT.**
  Round 859 named it and deliberately left its soundness open. It is a claim about where the
  construct may legally APPEAR, and round 792's law is that a profile with **0 `.d.ts` files among
  its 78** cannot falsify such a claim — round 792's own pre-gate measured 0 emitting calls in a
  22,187-call skip set and still killed 7 corpus baselines. The substring filter that IS sound
  (`namespace`) was already priced at zero by round 859. So the matcher was replaced rather than
  gated: an exact rewrite makes no legality claim at all, is differentially pinned against the
  pattern it replaces, and additionally removes a regex-DIALECT hazard from `commonMain` (the engine
  behind `kotlin.text.Regex` is `java.util.regex` on the JVM and a different one on Native).
- **THE PIN THAT WAS BLIND, AND WHY — the round's CLAUDE.md entry.** The first discrimination check
  read pass B's positive control as GREEN against a binary whose pass B was PassLab-disabled.
  `PassLab.ensureLoaded()` lives in `runWithDeepStack`, "the one funnel every JVM compile crosses" —
  and a direct `Checker(options, results)` construction, which is the documented multi-file test
  harness, crosses it for exactly nothing. So the fixture ran UN-ABLATED whenever JUnit happened to
  execute it before the first `diagnose()` in the same class. The helper now runs through
  `runWithDeepStack`, which is also what production does, and the ablation then reddens **exactly
  one** pin for pass B and **two** for pass A, with every other pin green (round 807, one mistake at
  a time). A separate injected fault — dropping the line-start anchor from the new scanner — reddens
  exactly the differential pin and the hand-written negative control.
- **ROUNDS 793 AND 788, ANSWERED BEFORE THE SAVING WAS QUOTED.** 793 needs no subtraction here:
  both passes still exist and are still wrapped in `pass(...)`, and the boundary census is IDENTICAL
  in all three arms — **834 pass-row lines** (417 × 2 draws) with `calls = 1` on each UMD row. 788 is
  answered by produced-vs-consumed: 78 productions serving 156 consumptions after (a), with no third
  consumer to move the work to — the other two `export as namespace` readers use DIFFERENT patterns
  and **their rows are flat across all three arms** (0.0–0.1 and 0.2–0.3 ms), which is that argument
  in measured form. Per-arm partition check: pass rows sum to 99.71% / 99.68% / 99.65% of
  `checkerInitNanos`.
- **THE A/B DECIDES NOTHING, AND BOTH BATCHES ARE QUOTED RATHER THAN THE FRIENDLIER ONE.** Batch 1:
  `VERDICT: NOISE-DOMINATED … This run decides NOTHING in either direction` (n=2, −0.75%, B wins
  1/2, one pair at +1.63%). Batch 2, same two binaries: `VERDICT: WIN of 3.0% (B wins 3/3) — outside
  the +/-1.0% warm band` (n=3, −3.03%). **Neither is quotable and the second least of all**: all
  four arm sds are **1.55–1.85%**, above CLAUDE.md's ~1% discard threshold, and 3.0% is 2.6× what
  the rows measure — round 840(c)'s shape exactly. Two batches that disagree is the correct outcome
  for a 1.17% effect on a ±1.0% instrument on a box that was not quiet. **The arm-level walls and
  `checkerInitNanos` must not be read as the saving in either direction** — arm B1's wall median is
  HIGHER than arm A's and arm A's `checkerInitNanos` is 568 ms above arm B2's; both are draw noise
  around a ~10% spread.
- **WHAT DID NOT WORK / WAS NOT DONE.** No in-situ span was put around each `findAll` (round 859's
  suggested first step): the per-PASS rows already isolate the scan to within the 4.7 ms residue, so
  the span would have added boundaries without adding resolution — but that residue was therefore
  never sub-partitioned and is only PRESUMED to be the two passes' AST walks. No cold A/B (cold the
  two passes are 0.38%, so the saving is below the cold band by construction — this is a warm-regime
  lever by design). n=2 instrumented draws per arm, sufficient only because the effect is ~99% of
  the rows it acts on. One profile, `--noEmit`, sequential; a project that actually CONTAINS
  `export as namespace` makes these passes emit rather than scan-and-return.
- **GATES, per sub-step, one at a time, every gradle step before the daemon stop (round 851).**
  (a): suite **14,049 / 0 / 3 skipped** (859's 14,040 + 9 new pins), `cost_gate.py` **+0.00% on all
  20 counters**, `huge_methods.py --fail-over 0` **651 classes / 14,573 methods / 0 over the limit**,
  8-profile grid **added=0 removed=0 on every profile**, no capture truncated or empty. (b): suite
  **14,051 / 0 / 3 skipped**, `cost_gate.py` **+0.00% on all 20**, `huge_methods.py` **14,578
  methods / 0 over**, grid **added=0 removed=0 on all 8** against the same round-859 before-arm.
  Both builds warning-clean. Round-853 positive control on the measurement: `UmdExportAsNamespaceKt`
  is ABSENT from arm A's class dir and present in B1/B2, whose copies differ by md5.

**Round 859 (2026-08-08) — (WARM.6): THE TAIL IS FLAT WARM TOO, WHICH IS THE NEGATIVE THIS ROUND
WAS COMMISSIONED TO FIND — BUT ITS WARM-UP RATIO IS NOT UNIFORM, AND AT THE BOTTOM OF IT SIT TWO
PASSES RUNNING THE SAME 10-MEGABYTE REGEX THAT MATCHES NOTHING.** The premise was that the tail's
share MORE THAN DOUBLES warm (10.4% cold → 22.9%, round 846) while nobody had ever checked whether
round 801's cold flatness survived. It does. `docs/perf/warm-tail-attribution.md` is the permanent
record; every figure below is a within-round share or ratio, never a cross-round absolute.

- **THE HEADLINE NEGATIVE.** Warm, the ~416 tail passes are **1,530 ms = 20.3%** of the artifact and
  **exactly ONE clears 1%** — `init:buildFileLocalTypeMaps` at **268.4 ms = 3.56%**. The second is
  **50.0 ms = 0.66%**, and **344 of the 416 are under 5 ms each**, summing 325 ms. Round 801's cold
  "largest 75 ms = 0.26%" rescales and holds. There is no warm-only giant in the tail. Round 846's
  central ratios also replicate on a fresh build and the post-858 dependency tail: `checkSpine`
  **3.27×** against the tail's **2.67×** (846 read 3.46× / 2.59×).
- **AND THE FINDING, WHICH IS ROUND 847's LAW ONE LEVEL DOWN.** Over the 72 tail passes ≥ 5 ms the
  warm/cold ratio spans **0.85× to 5.68×**, median 2.90×. The two at the bottom —
  `checkUmdGlobalVsDeclareGlobalConst` (**0.85×**, i.e. genuinely SLOWER warm: cold 41.6/43.3 against
  warm 47.2/48.1/56.5/48.0, non-overlapping) and `checkCrossFileModuleAugmentationDuplicates`
  (**1.05×**) — are together **98.2 ms = 1.30% of the warm artifact against 0.38% cold, a 3.4× share
  increase.** Cold they are two unremarkable rows in a flat tail; warm they are the 2nd and 3rd
  largest passes outside `checkSpine`.
- **THE MECHANISM, AND IT IS NOT A GUESS ABOUT A HOT LINE.** Both run the SAME
  `(?m)^[ \t]*export[ \t]+as[ \t]+namespace…` `java.util.regex` over all **9,977,097** characters.
  A standalone JVM running that exact pattern over that exact text measures **84 ms cold →
  53.9–59.4 ms in steady state** — MORE than either pass's whole warm row (50.0 / 48.3 ms) — and
  finds **0 hits**: tsc's own sources contain no `export as namespace` at all, so the compiler reads
  ten megabytes twice per compile to emit nothing. **The control is already in the file**:
  `checkExportAsNamespaceSelfCycle` runs essentially the same pattern and measures **0.0 ms in every
  draw**, because a `.d.ts` test and an `export = X` lookup sit ABOVE its `findAll`. Queued as
  **(WARM.7)** — dedup is the risk-free ~0.65%; the `.d.ts` gate would return the full 1.30% (this
  profile has **0 `.d.ts` files among its 78**) but its SOUNDNESS is a correctness question this
  round deliberately did not settle.
- **A CORRECTION TO ROUND 846: THE RESIDUAL IS NOT THE FRONT END.** 846 priced it as `wall −
  checkerInitNanos` = 11.1%. With the `FrontEnd` probe in the SAME warm process: front end proper
  **663 ms = 8.79%**, post-checker tails **143 ms = 1.90%** — and **806.4 against the residual's
  812.7 = a 99.2% partition check between two probes sharing no code, inside one process.** They
  warm **4.38×** and **1.27×**; the 1.9% has the worst ratio of any region measured and NO probe
  below it (**(WARM.8)**). The cold equivalent of that check does NOT hold (3,085 vs 3,515) because
  there the two arms are different JVMs — a cross-process residual is not a partition check and is
  not quoted as one.
- **INSIDE THE FRONT END, WHAT STAYS WARM IS THE FLOW GRAPH, NOT THE I/O.** config **41×**, crawl
  **7.52×**, `extractRelativeImports` **17.5×**, `bindLexicalScopes` **6.44×** all collapse;
  `FlowGraphBuilder.build` warms **2.73×** and its walk is **316.7 ms = 4.20% of the warm
  artifact** — the largest single region outside `checkSpine`, at 1.34 µs/node over 236,587 nodes
  against round 801's cold 3.0. Round 801 measured and CLOSED that region; its warm SHARE is
  higher (3.30% → 4.20%), which is a reason to re-read the closure, not to re-derive it.
- **BUILT: a `frontend` tier for `BenchMain`.** The `FrontEnd` probe (round 738; its bind level,
  round 801) had never been run inside a warm process for exactly the reason round 851 gave about
  the largest spine handler — **it had no tier name.** It needs no `*coarse` twin (per-FILE spans,
  78 files, microseconds against a ~900 ms region) and measured free in both regimes (−356 / −612 /
  +70 / +271 ms against its process median, straddling zero, same as `rows`). `BenchFrontEndTierTest`
  (4 pins) is built to fail **if the tier were INERT** — the fixture records through
  `FrontEnd.addCrawlFile`/`close` INSIDE `measureTier`'s build lambda and asserts the recorded
  `4242 chars` and the bind row are in the text, so dropping the arm from `tierBegin`, or reordering
  the disarm before the dump, reddens it; a negative control asserts the same calls are no-ops off
  the tier.
- **WHAT DID NOT WORK / WAS NOT DONE, stated up front.** The two slow passes were **not**
  sub-partitioned with an in-situ probe — the attribution rests on the standalone scan costing more
  than each whole row plus both passes containing it, and (WARM.7)'s first step is that span, not an
  edit. `init:buildFileLocalTypeMaps`'s warm prize (round 829's deletable 47.1% projected onto 3.56%
  = ~1.68%) is an ARITHMETIC PROJECTION of a population share onto a ms row, not a measurement, and
  its warm draw spread is **41%**, the widest in the top 12. No `spine` tier was taken, no A/B was
  run, nothing was optimized, and no `commonMain` code changed.
- **GATES (all three, one at a time, all BEFORE the daemon stop — round 851).** Suite
  **14,040 / 0 failures / 3 skipped** (core 13,963 + api 27 + client 18 + daemon 32, counted with
  `xml.etree` over all four modules' XMLs); `cost_gate.py` **+0.00% on all 20 counters**;
  `huge_methods.py --fail-over 0` **649 classes / 14,567 methods / 0 over the limit**. The build was
  warning-clean. The measuring script carries a round-853 positive control — it aborts unless the
  test class dir holds `BenchFrontEndTierTest.class`, a class that did not exist before this round,
  so a stale directory cannot satisfy it.

**Round 858 (2026-08-08) — THE THIRD "HARNESS LOADS THE WRONG ARTIFACT" AUDIT, AND THIS TIME THE
ANSWER IS THAT THE MEASUREMENTS SURVIVE. THE WARM ARC STANDS; `build/bench/cp.txt` NOW HAS ZERO
READERS; AND BATCH 2 KILLED THIS ROUND'S OWN HEADLINE NUMBER.** Round 857 left an explicit
loose end — `cp.txt` is the pre-split, pre-bump dependency list and four instruments were said to
read it. Full audit, then the decisive experiment, then a guard whose every check has a uniquely
its-own ablation failure. `docs/perf/classpath-readers-audit.md` is the permanent record.

- **THE PER-READER AUDIT (the five-minute answer that decides everything after it).**
  `ab-interleaved.sh` **SOUND** — always resolved FRESH through the gradle init script, never read
  `cp.txt`. `cost_gate.py` **SOUND** — resolves fresh (round 853's fix). `ab-warm.sh` **SOUND
  TODAY, GUARD INCOMPLETE** — its `cp-warm.txt` cache content is CURRENT (2.4.10 / 0.9.1 / 1.11.0)
  and its guard currently fails anyway (cache Aug 1, build files Aug 7), so it has been resolving
  fresh; but the guard compared the cache only against the **module's** `build.gradle.kts`, and the
  versions live in `gradle/libs.versions.toml` — **it was blind to exactly the change that produces
  a stale tail.** `aot-draw-variance.sh` **BROKEN** — `<core jar>:$(cat cp.txt)`, and it measures
  `XtscMainKt`, a **daemon** class absent from that classpath: verified `ClassNotFoundException`,
  dead since the split like `scripts/xtsc` was until round 857. **It fails LOUDLY, so it could
  never have produced a wrong number — only no number.**
- **THE DOCUMENTED "PRE-SPLIT REFUSAL" DOES NOT GUARD `cp.txt` AND NEVER DID.** It greps the INIT
  SCRIPT, not the cache, and in `ab-warm.sh` it sits inside the resolve branch — so a cache hit
  skips it entirely. Round 857's parenthetical credited it with protection it does not provide;
  checking whether a refusal FIRES, rather than that it exists, is the whole lesson.
- **ALSO FOUND, and it is the only thing that touched a published number:** the committed
  `round847-warm-spine.sh` / `round849-warm-sections.sh` ran their **WARM** arms on the current tail
  and their **COLD** arms on the stale one, so every cold/warm RATIO those rounds published compared
  two different dependency tails.
- **THE DECISIVE EXPERIMENT, and the part that matters most: BEHAVIOUR IS IDENTICAL.** Same binary,
  same profile, only the tail swapped, rotated interleave. All 12 timed compiles answered `46
  errors`, and **all four `--listAll` digests are `59d930db…`** (the round-841 lineage), 46 lines.
  Both tails also LINK — verified by running the compiler on each — so this was never a crash risk.
- **AND THE TIMING CLAIM DIED IN BATCH 2, WHICH IS THE ROUND'S BEST RESULT.** Batch 1: stale 23,633
  → fresh 24,420 ms, **+3.63%, fresh slower 3/3**, both arm sds under 1% — a textbook sign-consistent
  sweep, and I was one batch away from writing it up as a real 3.6% and "correcting" two rounds with
  it. Batch 2: **+0.33%, 2/3**, deltas +79 / −184 / +317 straddling zero. The mechanism is in the
  table — the **fresh** arm moved 24,420 → 23,754 ms between batches, **−2.7% on a byte-identical
  configuration** — so batch 1's drift simply landed on one arm. **Round 840(c)'s rule, second
  confirmation this session and the first where following it REVERSED the conclusion.**
- **SO, PLAINLY: the warm arc STANDS and nothing needs re-taking.** 845/846/848/850/851 never
  touched the stale tail; 847/849's cold arms did, but the difference is behaviourally nil and not a
  demonstrated timing effect. **Round 845's `(JIT.1)` −33.6%, 4/4, 1.505× is untouched by
  construction** — it built both arms in a throwaway worktree from pre-split commits, so its
  dependencies were identical and neither cp file was involved. A per-kind/per-handler SHARE is a
  ratio inside one arm and is immune regardless; only cross-regime multipliers were ever exposed.
- **THE GUARD.** `scripts/lib/dep-classpath.sh` — one shared resolver, refusing a cache unless it is
  non-empty, newer than **every** build-definition input (`libs.versions.toml` first), and names only
  entries that still exist; every refusal prints why. All readers rewired; `aot-draw-variance.sh`
  additionally gets round 857's staged-lib-dir treatment (its classpath ORDER is hashed into the AOT
  fingerprint). **`build/bench/cp.txt` now has ZERO readers**, pinned source-level.
- **ABLATION — three arms, each failing EXACTLY ONE uniquely-its-own pin** (baseline 6/6 green;
  harness committed first, round 789). Drop `libs.versions.toml` from the inputs → only the
  libs-versions pin fails **while the module-build-file pin stays GREEN**, which is precisely the
  state `ab-warm.sh` was in: a guard that passes and cannot see the real change. Drop the
  existence loop → only the missing-jar pin. Drop the non-empty test → only the empty-cache pin.
- **GATES: full suite green (see STATUS.md).** `cost_gate.py` / `huge_methods.py` not required —
  nothing under `commonMain` changed; the round is scripts plus one jvmTest class.

**Round 857 (2026-08-08) — (MOD.7) CLOSED: THE SHIPPED AOT CACHE IS RETRAINED AGAINST THE
POST-SPLIT LAYOUT, AND THE DEV LAUNCHER HAD BEEN INOPERABLE SINCE THE SPLIT.** The module
split moved everything the fingerprint binds: the classpath went **8 → 14 jars** (ktor ×3,
slf4j, `-api`, `-daemon`, plus stdlib 2.4.0 → 2.4.10, kotlinx-io 0.9.0 → 0.9.1,
serialization 1.9.0 → 1.11.0), the ORDER changed (first entry is now `annotations-23.0.0`,
not the core jar), and the fingerprint went `73c2f5feb9c0f857` → **`086a6cb1ae5b4203`**, so
the shipped cache read `SKIP no-cache-file` — fail-safe, and therefore silent, for as long
as the debt stood.

- **The launcher could not run AT ALL, and that is the finding a retrain would have hidden.**
  MOD.4b replaced the hand-listed dev classpath with the staged
  `…-daemon/build/install/lib` (`xtscLib` Sync), and **nothing had run `assemble` since the
  split** — `scripts/xtsc` died with `cannot resolve a classpath … or build this repo first`.
  Correct behaviour (a loud refusal, not a wrong answer), but it means a fresh checkout or a
  `clean` leaves the launcher inoperable until `./gradlew assemble`, which no doc said.
- **Trained after the last build** (round 842's rule): 30.0 s, 54,816,768 B, self-verified,
  `pruned 1 stale cache(s)`. **Proved USED, not present**: `-Xlog:class+load=info` shows
  **955 of 960 `com.xemantic` classes with `source: shared objects file`**, `Checker` among
  them, with `XTSC_AOT_VERBOSE=1` printing `aot USE …` on every timed run.
- **The guard re-verified END TO END on the new classpath**, not just by pins: with
  `Checker.class` removed from a copy of the core jar, unguarded-cached **compiled and
  reported `FAILED — 2 error(s)`** (type-checking against a class the jar no longer holds),
  unguarded-plain died `NoClassDefFoundError`, and **guarded `scripts/xtsc` read
  `SKIP no-cache-file` and died the same honest death**. `AotCacheGuardTest` **13/13**; the
  one-mistake ablation (manifest comparison deleted, on a COPY of `scripts/` via
  `XTSC_TEST_LAUNCHER`) fails **exactly one** pin, `a mutated classpath entry is refused`.
- **THE LADDER, ARM AND MODE NAMED** — shipped JVM launcher arm, check-only mode, sequential,
  compiler profile, 4 rotated pairs: **median 24,082 → 15,160 ms, paired median −9,035 ms,
  ratio 1.600×, cached faster 4/4.** All 8 runs 46 errors and ONE digest,
  `84bbe7f0a60d81c40349527a068b8647` — the round-841/853 `grid838.sh` lineage, so the
  compiler profile is byte-stable r817 → r857 and is now witnessed through the shipped jar
  launcher for the first time.
- **A COMMITTED HARNESS THE SPLIT HAD BROKEN, FIXED AND GIVEN A DETECTOR.**
  `scripts/aot-corpus-suite.sh` built its AOT prefix as `<core jar>:$(cat build/bench/cp.txt)`
  — post-split not a prefix of the trained classpath, so the JVM would have declined the
  cache and **both arms would have run uncached, agreed, and proved nothing** (round 842
  § 13.3's trap, in committed code; it is also the exact hand-assembled-classpath mistake
  MOD.4b deleted from the launcher). It now reads the staged lib dir with the launcher's own
  `find | LC_ALL=C sort`, and a served-class count of **zero is now a hard failure**. Re-run:
  646 classes, **13,950 run / 2 failed / 3 ignored in BOTH arms, per-class diff EMPTY**,
  955 of 5,313 classes from the cache, 2:31 → 2:18. The 2 failures are `HugeMethodLimitTest`'s
  classpath-layout pins, green under Gradle.
- **A SIXTH DIGEST LINEAGE, AND ITS CAUSE IS THE SORT.** This round's harness first reported
  `4b9635d6…`/`bcb1512a…` for the *same* 46 lines: it sorted in Python (code-point order)
  where every recorded lineage sorts in the shell (locale collation). The recipe includes the
  COLLATION, not just the `grep` and the `sed` — `docs/perf/aot-cache.md` § 11/§ 14.5.
- **Left where it was found:** `build/bench/cp.txt` is still the pre-split, pre-bump
  dependency list that `ab-*.sh` / `cost_gate.py` / `aot-draw-variance.sh` all read, and
  `cost_gate.py` has still not run since the split. Neither is an AOT question and neither
  was touched. Full detail: `docs/perf/aot-cache.md` § 14.

**Round 856 (2026-08-08) — (NARROW.2)(f2) CLOSED: ROUND 855's THREE UNRUN GATES ARE GREEN, AND
ITS TEN PINS ARE NOW ABLATION-VERIFIED — ALL FOUR ARMS LOAD-BEARING, BUT ONLY **6 OF 10** PINS
HAVE A UNIQUELY-THEIR-OWN FAILURE.** A pure verification round; no design work, no compiler code
changed. Round 855 landed a probe-only change and stated plainly that it had run neither the
suite, nor `cost_gate.py`, nor `huge_methods.py`, nor its own ablation — this round is that
paperwork, and it found one thing worth keeping.

**THE THREE GATES, one at a time, never beside each other.**

```
suite          14,030 / 0 failures / 3 skipped   (core 13,953 + api 27 + client 18 + daemon 32)
cost_gate.py   all 20 counters +0.00%            exit 0
huge_methods   649 classes / 14,567 methods      0 OVER THE LIMIT, exit 0
```

The suite count is exactly the queue's prediction (14,020 + 10 new pins), so no reconciliation was
needed. **The cost gate's zero is the load-bearing reading and it is a FALSIFIABLE one**: round
855's inventory is `PassTiming.detailed`-gated, and the queue's instruction was that a NON-zero
delta here would mean the gate LEAKS — probe machinery executing in a production compile — i.e. a
real regression rather than a rebaseline. It reads +0.00% on all 20, and the run is against the
round-855 binary rather than a round-853-style frozen one: the gate's own log prints
`XTSC_BUILD_ID=ae9779db…` = HEAD with `compileKotlinJvm UP-TO-DATE` off the suite's compile. Largest
method is `walkFunctionBodiesInExpr` at 7,702 bytecodes — 298 of headroom under HotSpot's limit.

**THE ABLATION RAN, AND THE FIRST THING VERIFIED WAS THAT IT DISPATCHES.** Round 855's harness
printed `complete; tree restored` while doing nothing, so a clean sweep from it proves nothing
until an arm is seen to apply and redden something. Both halves were checked before any arm was
trusted: the bug form `"${@:-A1 A2 A3 A4}"` expands to the single word `[A1 A2 A3 A4]` (hence
`unknown arm` four times), the committed array default expands to four; and each arm's `apply` was
dry-run against the tree, each producing a real distinct one- or two-line edit that reverted clean.

| arm | the one mistake | red | uniquely its own |
|-----|-----------------|-----|------------------|
| A1 | drop `is FlowCondition ->` from `narrowableRoots` | 1 of 14 | `POSITIVE CONTROL - the same imported name JOINS the set once a condition mentions it` |
| A2 | drop `is FlowAssignment ->` | 3 of 14 | `POSITIVE CONTROL - an IMPORTED name is NOT in the set`; `THE FINDING - a name that is merely DECLARED is in the set`; `a locally declared any root is never refused` |
| A3 | stop recording the pre-test span (`preNanos = 0L`) | 1 of 14 | `the probe HONOURS NOTHING …` |
| A4 | remove the `detailed` gate — collect the inventory unconditionally | 1 of 14 | `negative control - off the probe the graph carries no inventory at all` |

**So all four ablated guards are load-bearing and each is discriminated by a pin no other arm
moves.** A2 is the round-855 finding restated as a failure: deleting the assignment arm is what
takes a merely-DECLARED name out of the set, and it simultaneously breaks the imported-name
control's own control (`"param" in roots`) and the consumer census — three pins, one mechanism.

**THE PART WORTH KEEPING: 4 OF THE 10 NEW PINS HAVE NO UNIQUELY-THEIR-OWN FAILURE, AND ONE OF THEM
IS A REDUNDANT GUARD WHOSE NAME CLAIMED OTHERWISE (round 807).** `a name occurring in a condition
is in the narrowable set` uses `declare const cond` as its subject — a `VariableDeclaration`, which
mints a `FlowAssignment` whose subtree contains the name — so `cond` is in the set through the
ASSIGNMENT arm and arm A1 leaves the pin GREEN. It never tested the condition arm. **That is the
round-855 structural finding biting its own pin**, which is the neatest possible confirmation of it:
the declaration that makes a name exist is itself one of the narrowing nodes the set is built from,
so a pin whose subject is declared in its own fixture cannot isolate any single arm. Renamed to say
so. The other three are recorded rather than renamed, because their names are true statements of
what they assert: the switch/assert pin targets the `FlowSwitchClause`/`FlowCall` arms, for which
this harness has no arm (A2 is indirect evidence for them — it deletes the parameter route and the
pin holds); `SOUNDNESS - an opening the flow DID narrow is never refused` has a subject reaching the
set through BOTH ablated arms, so only a combined ablation could move it and that cannot attribute;
and the disabled-counters control watches a `PassTiming.enabled` gate no arm touches. A fifth
rename went the other way: `the probe HONOURS NOTHING` is reddened by A3 through its `preNanos > 0L`
assertion, not through the honours-nothing half its name advertised, so the name now states the span.

**LANDED:** ablation-status KDoc on all five affected pins plus two renames (no assertion changed;
14 of 14 green after), commit `362bc791`. `scripts/round855-ablate.sh` needed no fix — round 855
had already corrected its own bug; this round only proved the correction dispatches.


**Round 855 (2026-08-08) — (NARROW.2)(f) CLOSED AS A MEASURED NEGATIVE: THE PRE-TEST REFUSES
**0 OF 14,117** OPENINGS, THE REASON IS STRUCTURAL RATHER THAN A TUNING FAILURE, AND THE
PREDICATE COSTS **150–211 ms** TO BUY NOTHING.** Round 854 priced round 852's narrowed-`any`
opening at 1.91% of a warm rebuild with 85.6% of it spent on receivers the flow never narrows,
and stopped — queueing (f) with the instruction to census the candidate pre-test BEFORE building
it, because 1.63% is a CEILING (a perfect oracle) while the concrete design is coarser. That
instruction is the whole value of this round: the design looked obviously worth ~1% and is worth
exactly nothing, and one probe run said so for the price of one build.

**THE CENSUS, cold, compiler profile, probe HONOURING NOTHING** (the predicate is evaluated, the
verdict recorded, and the walk runs anyway — so the population is bit-identical to round 854's and
every rep prints the profile's usual 46 diagnostics):

```
cmamNarrowedAny (e): openings=14117 narrowed=1345 accepted=1051  walkOnly=385/406/360 ms
cmamAnyPreTest  (f): refused=0 (noPath=0) kept=14117
                     refusedNarrowed=0 refusedAccepted=0 keptNarrowed=1345
                     refusedSpan=0ms refusedWalkOnly=0ms  preTestCost=211/162/150 ms
```

Re-taken on the COMMITTED binary after the pins went green (2 reps): identical — `refused=0`,
`openings=14117 narrowed=1345 accepted=1051`, `preTestCost=168/128 ms`, 46 diagnostics.

**THE STRUCTURAL REASON, which is the part that must survive this round.** The candidate was a
per-FILE set of the root names any narrowing node could narrow, consulted before the walk. But
**every `VariableDeclaration`, `Parameter` and `BindingElement` mints a `FlowAssignment`**, and
that node's subtree contains the declared name — so **every root DECLARED in a file is in that
file's narrowable set BY CONSTRUCTION**, narrowed or not, including a `declare const g: any` that
nothing ever tests. The set can only refuse a root with NO declaration in the file at all (an
import, a cross-file ambient), and tsc's own `any` receivers are locals and parameters. **There is
no coarser or finer variant to try: the declaration that makes a name exist is itself one of the
narrowing nodes the set is built from.** Do not re-propose a name-keyed narrowability set here.

**A NEGATIVE WITH A PRICE ATTACHED.** `preTestCost` — the pre-test's own wall, measured at every
opening — is **150–211 ms cold against a `walkOnly` of 360–406 ms** for the entire population it
was meant to shrink. Most of it is the one-off `narrowableRoots()` construction per file, which a
shipped gate pays exactly as the probe does. Even at a hypothetical 100% yield the arithmetic
would be marginal; at 0% it is a pure loss. **Go/no-go was ~70%; measured 0%.**

**THE INSTRUMENT IS ALIVE — round 790's complement population refuses.** A zero reads the same
from a sound skip and from a dead probe, so the refusing population had to be exhibited. Through
the project CLI on a two-file project, on the same class dir that produced the profile reading:
`refused=1` for an imported root, falling to `refused=0` when one `if (imported)` is added. In
suite, this is pinned at the SET level.

**WHAT THE THREE EARLY PIN FAILURES TURNED OUT TO MEAN — the diagnosis is worth as much as the
verdict: THE PINS WERE WRONG, the implementation and the design reading were not.** The first
`NarrowableRootsPreTestTest` draft tried to exhibit the refusing population through `diagnose()`,
which is SINGLE-FILE. A single-file fixture's `import` does not resolve, so the receiver is not
`anyType`, so **no opening runs at all** — the failing subexpression was `openings > 0`, reading
`0`, in both positive controls, and the third pin failed downstream of the same cause. The
refusing population is *inexpressible* through that harness. The fix was not to weaken the pins
but to **split them by level**: the SET is now unit-pinned directly (`FlowGraphBuilder().build(…)
.narrowableRoots()` — membership for a condition/switch/assert root, membership for a merely
DECLARED root = the finding, NON-membership for an imported root = the positive control, and its
disappearance when a condition mentions that root = the control's own control), while the CONSUMER
is census-pinned on the populations `diagnose()` can reach. 10 pins, all green alongside round
854's 4. **The general lesson, and it has bitten before (round 806, in the other direction): a
fixture shape validated at one harness is not portable to another — `diagnose()` and the project
CLI do not have the same expressive power, and "the pin failed" is a claim about the fixture until
the failing subexpression is read.**

**ROUND 854's 1.63% REMAINS A CEILING THIS DESIGN CANNOT REALISE.** (NARROW.2)(c)'s cost therefore
stands as **measured and accepted**, not as an open regression: it is 1.91% of a warm rebuild, it
bought two conformance cases (`types/any` 3 failing of 9 → 1) with a 0/0 eight-profile grid and a
clean corpus, and the waste inside it is **not addressable by asking whether the name is narrowable
anywhere in the file**. A future attempt needs a *path-and-position* oracle — does any narrowing
node lie on THIS reference's flow path — which is the walk itself. That is the honest statement of
where this ends.

**LANDED:** `FlowGraph.narrowableRoots()` + the mint-time inventory in `FlowGraphBuilder`, both
`PassTiming.detailed`-gated so a production compile keeps no inventory and the accessor answers
`null` = "unknown, refuse nothing" (never an EMPTY set, which would mean "refuse everything");
`PassTiming.cmamAnyPre*` (7 counters + a `--passTiming` row); `Checker.cmamPreTestMayNarrow` called
as a probe only; `NarrowableRootsPreTestTest` (10 pins); `scripts/round855-ablate.sh`;
`docs/perf/narrowed-any-opening-price.md` § 4b. Commits `bc2495a7`, `d7461d4c`.

**WHAT THIS ROUND DID *NOT* RUN, stated rather than implied.** (1) **The ablation did not execute.**
`scripts/round855-ablate.sh` was committed before use (round 789) and then lost its own run to a
bash bug — `"${@:-A1 A2 A3 A4}"` expands the default as ONE word, so `apply` fell through to its
`unknown arm` branch for every arm while the script still printed `complete; tree restored`. The
tree was never corrupted and nothing was mis-recorded, but **the 10 new pins are NOT yet
verified-discriminating** and must not be quoted as such. The bug is fixed (an array default) and
the four arms are specified in the script: drop the `FlowCondition` arm, drop the `FlowAssignment`
arm, stop recording the pre-test span, remove the `detailed` gate. (2) **The full suite, `cost_gate.py`
and `huge_methods.py` were not run this round.** The change is probe-only and `PassTiming.detailed`-
gated, so a production compile's behaviour and counters should be untouched — but "should" is not a
gate reading. **Both are the next round's first task, before anything else**; see the queue entry.

**Round 854 (2026-08-08) — (NARROW.2)(e) CLOSED AS OUTCOME (c): ROUND 852's +79% NARROWING WALKS
ARE **1.91% OF A WARM REBUILD** AND **A QUARTER OF ALL NARROWING WORK IN THE COMPILER**, AND
**85.6% OF IT IS SPENT ON `any` RECEIVERS THE FLOW NEVER NARROWS.** The first priced POSITIVE
after six consecutive priced negatives — and the round deliberately stops at the measurement, with
the implementation queued as (NARROW.2)(f) carrying a CEILING rather than a promise.**

**WHY THE OBVIOUS INSTRUMENT WAS ABANDONED IN THE FIRST TEN MINUTES.** The queue said to difference
`--passTiming`'s `narrowWalk` row between an ablated build and HEAD. That row reads **1,423 /
1,460 / 1,602 ms across three runs of the SAME binary** (±6–9%, i.e. ±90–150 ms), which is the same
order as the object being weighed — so an arm difference would have been noise dressed as an
answer, and one rebuild per arm would have been spent finding that out. The span was taken **where
the cost is incurred** instead: one timestamp pair around `cmamNarrowedAnyReceiverType`'s call to
`getNarrowedTypeForReference`, plus the delta of `narrowWalkNanos` across the same call (the walk
body ALONE, excluding the tier-3 shadow bookkeeping that exists only under the probe), plus the
produced-vs-consumed counts round 801 demands *before* any timing is read as a prize.

**THE POPULATION — deterministic, bit-identical across all six instrumented runs:**
`openings=14117  narrowed=1345 (9.5%)  accepted=1051 (7.4%)`. **`openings` matches round 853's
`+14,110` walk delta**, so the ablation-attributed counter and this census are one population
measured two ways — the cross-check that makes the rest of the round quotable. On this profile
those 1,051 accepted receivers emit **nothing** (round 852's grid is 0/0, byte-verified by 853);
what they buy is the conformance result, which this profile does not contain.

**THE PRICE.** Cold (3 reps): `span` **435 ms**, `walkOnly` **381 ms**, against a whole
`narrowWalks` row of **1,451 ms** — the opening is **26.3% of every narrowing walk the compiler
performs**, and that RATIO (26.3 / 26.7 / 25.9%) is far steadier than either absolute, which is how
it should be quoted. Warm, the shipping artifact's regime (round 843), `BenchMain <proj> 2 5 full`,
probe-free median rebuild **7,664 ms**, every iteration 78 files / 46 errors: `walkOnly` **146 ms =
1.91%**, `span` 185 ms = 2.41%, and the opening is **24.5% of warm narrowing** against 26.3% cold.
Two regimes, one answer — including the wasted share, **85.6% warm vs 85.4% cold**.

**THE VERDICT IS (c), AND THE TWO STANDING LAWS BOTH HAD TO BE CHECKED RATHER THAN CITED.**
(1) **Round 788 does not rescue it:** `narrow.memoServed` moved **42,766 → 42,867, i.e. +101**, for
+14,110 launched walks — nobody else was going to ask, so the work is ADDED and deleting it deletes
it rather than deferring it to the next asker. (2) **Round 759's law runs the OTHER way here.**
"What you could skip cheaply is what was already cheap" holds only when the exit predicate and the
cost share a cause; here the predicate is *did the flow narrow* and the cost is *how far the walk
traversed*, and the **90.5% that answer `any` carry 85.4% of the cost**. Count share and cost share
coincide for once — that is exactly the assumption that cost rounds 758 and 759 their predictions,
in the opposite direction, so it was measured.

**WHAT WAS DELIBERATELY NOT DONE, AND WHY IT IS NOT THE RECON-ONLY FAILURE.** No pre-test was
built. The prize is a **CEILING of 1.63% warm** — what a *perfect* oracle returns — while the
concrete design (a per-file set of root names reachable by any narrowing flow node) is **coarser**:
a root narrowed *somewhere* in the file but not on *this* path still walks, and nothing measured
today says what fraction of the 12,772 it would actually refuse. Against a ±1.0% warm band that
gap decides the whole item, and it is answerable by a probe that HONOURS NOTHING (the round-792/793
`--cmamPreGate` shape) for one build. Landing a real gate on this function without that number
would also be walking into round 792's law head-first: a whole-function pre-gate on
`checkMemberAccessMissing` measured **0 emitting calls in a 22,187-call skip set on this very
profile** and still killed **7 corpus baselines**. (f) carries the census instruction, the
soundness argument, the ~70% go/no-go threshold and the memo re-check.

**WHAT LANDED:** `PassTiming.cmamAny*` (a `detailed`-gated span + walk-only sub-span + the three
counts, printed by `--passTiming`), `scripts/round854-narrow-price.sh` (committed BEFORE anything
else, round 789), `NarrowedAnyCensusTest` (4 pins: the accepted population, the REFUSED population
— which is invisible in the compiler's output and is the whole point — the span's partition
checks, and a disabled-run negative control), and
`docs/perf/narrowed-any-opening-price.md`.

**ABLATION — THREE ARMS, ONE MISTAKE AT A TIME (round 807), harness committed before use (789),
and all three DISCRIMINATE with disjoint-enough failing sets to be three seams rather than one.**
`A1` drop the `accepted` increment → **1 red** (only the pin that asserts a receiver type was
produced); `A2` drop the whole `noteCmamAnyOpening` → **3 red**, i.e. every counter-reading pin,
with the disabled-run control correctly staying GREEN; `A3` invert the `narrowed` predicate →
**2 red**, the two population pins, which assert `narrowed > 0` and `narrowed == 0` respectively
and so can only both fail to an inversion. `scripts/round854-ablate.sh` restores the source from an
EXIT trap and refuses an arm where no test ran (round 808's `GC overhead` arm, which reads exactly
like "the mistake changed nothing").

**GATES.** Suite **14,020 / 0 failures / 3 skipped** (14,016 + the 4 new pins). `cost_gate.py`
**all 20 counters +0.00%** — and this is now a FALSIFIABLE zero, since round 853 wired the gate to
the real binary; the census is counter-neutral by construction (`detailed`-gated). `huge_methods.py
--fail-over 0` **exit 0**, census 649 classes / 14,547 methods / **0 over the limit**. No 8-profile
grid: nothing in this round can change a diagnostic, and manufacturing captures to "check" that
would be the recon-only failure in a different costume (round 853's own rule).

**Round 853 (2026-08-08) — RECORD INTEGRITY. THE GRID's BLAST RADIUS IS *ONE* CLAIM AND IT STANDS;
THE DIGEST THAT LOOKED LIKE THE SMOKING GUN IS A FOURTH RECIPE LINEAGE — BUT AUDITING THE *OTHER*
INSTRUMENTS FOUND THE SAME STALE BINARY INSIDE `cost_gate.py`, WHICH MEANS THE COST GATE HAS BEEN
BLIND SINCE MOD.3 AND ROUND 852 REALLY COST **+79% NARROWING WALKS**. No compiler code changed.**

**THE QUESTION.** Round 852 found `scripts/grid838.sh` reading a `build/bench/xtsc-classpath.txt`
whose only class dir was the ROOT project's pre-module-split leftover, and closed with *"any grid
digest recorded between the module split and this round should be treated as unverified."* This
round establishes which claims that actually touches and re-takes the ones that matter.

**THE ENUMERATION (from the record, not from memory).** MOD.3 landed 2026-08-07 18:17 UTC; the
stale root class dir was last written 2026-08-07 23:39 UTC — 589 classes against core's 649, no
`ModeLedger`, no `LibTypeCensus`, so a **pre-848** compiler. Of the rounds since the split, exactly
**one** quoted a profile capture: **round 848**. Rounds 843–847 and 849–851 gated on the suite,
`cost_gate.py`, `huge_methods.py` and warm A/Bs, and `ab-warm.sh`/`ab-interleaved.sh` resolve their
classpath freshly through the `xtscPrintJvmRuntimeClasspath` init script and already refuse a
pre-split file (round MOD.3) — **`grid838.sh` is the only reader of that file in the tree**
(`grep`ed across `scripts/`, `build.gradle.kts` and every module script). Round 845's own note
already says "the 8-profile grid was not run". So: one affected claim, and it is the one the
prompt suspected.

**THE RE-MEASUREMENT — ROUND 848's 14 ARMS, AND THEY STAND.** `scripts/round853-serve1-arms.sh`
(committed BEFORE any ablation, round 789) re-runs the sweep with two guards: the module-name check
on the classpath, and a **positive control on the binary itself** — `ModeLedger`, round 848's own
class, must be present in the class dir under test, which is precisely what the stale dir failed.
Baseline ×2 plus all 14 arms on the compiler profile: **every one 46 lines, `trunc=0`,
`added=0 removed=0`, md5 `84bbe7f0…`** — `--flowScanLegacy`, `--flowScanBogus`, `--flowEagerSet`,
`--argNarrowGateOff`, `--dispatchGated`, `--ianyGateOff`, `--ianyArgGateOff`, `--cmamPreGate`,
`--ccetPreGate`, `--verifyDeferSuppression`, `--verifyUnionRetry`, `--verifyLoopRetry`,
`--verifyImplRelated`, `--workers 4`. **`--flowScanBogus`, whose job is to corrupt the scanner, is
byte-identical on a binary that provably honours the flag.** Round 848's three-way classification
is a finding, not an artifact, and its suite-level half (`FlowScan.bogus` defaulted true → 13,902
tests, exactly 1 failure) was never in doubt — it is a different instrument that never touched the
classpath file.

**THE DIGEST WAS NOT THE SMOKING GUN — IT WAS A FOURTH LINEAGE.** Round 848 recorded `4090b73e…`
where this round's verified capture prints `84bbe7f0…`, which reads exactly like two different
binaries. Hashing the ONE verified capture six ways settles it: `s#.*/src/#src/#` + `sort`
reproduces **`4090b73e…` exactly**. Round 841 named three live lineages; there are **four**
(`docs/perf/aot-cache.md` § 11.5 is the table). Round 848's number is exonerated, not retracted.

**AND THE STALENESS COULD NOT HAVE CHANGED THE ANSWER ANYWAY — measured, not argued.** Running the
stale pre-848 root class dir against the compiler profile prints the **same 46 lines and the same
`84bbe7f0…`** as today's binary. So rounds 848–852 moved nothing this profile can see, exactly as
each of them claimed, and round 852's own "0/0 at all three steps" is corroborated from a direction
it could not measure from. A bonus that costs nothing: the same capture reproduces
**`59d930db…`**, the round-826/836–840 lineage — so the compiler profile's diagnostics are
**byte-stable from round 817 to round 853**, ~36 rounds.

**THE GUARD DISCRIMINATES (one mistake at a time, round 807).** Re-introducing exactly the stale
path — the root class dir substituted for core's in `xtsc-classpath.txt` — makes `grid838.sh` exit
**1** with `stale (pre-module-split)` **before launching any JVM**; restoring the good file lets it
proceed to `java`. Positive and negative control in one pair. The stale root `build/classes` tree
is now **deleted** (nothing in the build produces it — the root has no `src/`, and the only
reference left is a comment in `grid838.sh`), so a future stale file fails loudly instead of
silently compiling.

**AND THEN RUNNING THE GATES FOUND THE SAME BUG IN BOTH OF THEM — WHICH IS THE ROUND'S REAL
DELIVERABLE, BECAUSE ONE OF THEM IS THE INSTRUMENT THAT EXISTS TO NOTICE CHECKER DRIFT.** Deleting
the root leftover made `huge_methods.py` die with `no class files` — it censused
`REPO/build/classes/…`, the pre-split path. Worse, `cost_gate.py`'s `resolve_classpath()`
**PREPENDS** that same root dir to the resolved classpath, and it has to prepend *something*
because `jvmRuntimeClasspath` resolves DEPENDENCIES only — so the leftover, first on the classpath,
**was the compiler every gate run has loaded since MOD.3**. That is why every round in the window
reported `+0.00% on all 18 counters`: a frozen binary cannot drift. Both are fixed to resolve the
`-core` module and both carry a positive control (`huge_methods.py` refuses the legacy path BY
NAME; `cost_gate.py` requires `MainKt` under the module dir). A third drift fell out of the same
stone: `exit 1 when the compile finds errors` (d5ed6276, tsc semantics) means a correctly-wired
cost gate returns 1 on every dashboard profile — the frozen binary predated it, so the run is now
judged by the presence of the counter block rather than by `rc`.

**THE COUNTERS HAD NOT STOOD STILL, AND THE ABLATION SAYS WHOSE THEY ARE.** Correctly wired, HEAD
against the round-838 baseline: **`narrow.walks` 17,851 → 31,961 (+79.04%)**, `globals.lookups`
+4.93%, `globals.misses` +4.99%, `typeNode.cacheHits` +4.10%, `typeNode.cacheable` +2.72%,
`typeOfExpr.calls` +0.53% (reproduced exactly on a second run — these counters are deterministic).
**One mistake at a time:** revert round 852's `Checker.kt` hunk alone, rebuild, re-run — **+0.00%
on all 18**. So the whole delta is **(NARROW.2)(c)**, and rounds 839–851 genuinely moved nothing
(their `+0.00%` claims are true, they were just unfalsifiable at the time). Mechanism, read off
round 852's own design: `cmamNarrowedAnyReceiverType` is keyed on *a narrow HAPPENED*, so the walk
is LAUNCHED to answer that, over the `any`-receiver population the walker used to bail on. Round
852's diagnostic results are untouched; only "it was free" is retracted. Baseline `--update`d in
the same commit as this justification, per COST.1, and the price is queued as **(NARROW.2)(e)** —
this is the round-713 failure class (`+11.5% getTypeOfExpression for one diagnostic, no gate
noticed`), caught this time only because the instrument was being audited.
**The JIT half STANDS on the other instrument:** the fixed census reads 649 classes / 14,532
methods / **0 over the limit**, and `HugeMethodLimitTest` — which locates the classes from a marker
resource on the TEST classpath and so was never fooled — has been running the same whole-program
census inside every green suite. CLAUDE.md's "the suite test is the SECOND instrument on purpose"
paid for itself a second time.

**WHAT THIS ROUND DELIBERATELY DID NOT DO.** No re-run of the 8-profile grid for rounds 843–851 —
none of them quoted one, and manufacturing captures to "check" claims that were never made would be
the recon-only failure in a different costume. No attempt to REDUCE (NARROW.2)(c)'s narrowing-walk
cost: pricing it in wall time and deciding whether the opening can be narrowed is a design round,
and taking it as a side effect of an audit is exactly what the queue entry for (c) forbade for (d).

**TOP OF QUEUE (owner-requested 2026-07-26, round 684) — work this before PERF.**

- [x] **(NARROW.2)(a) — CLOSED round 838. An `instanceof` whose RHS is a CONSTRUCTOR VALUE
  now narrows.** `resolveInstanceOfRhsType` required `SymbolFlags.Class`, so it answered
  only for a class DECLARATION; every ambient constructor in the lib is
  `interface Error { … }` + `declare var Error: ErrorConstructor`, so `Error`/`Date`/
  `RegExp`/`Map`/`Set`/`Promise` narrowed NOTHING in either branch while a user-written
  class narrowed fine. tsc's `getInstanceType` (the `prototype` property, then a lone
  construct signature's return), bounded — no `[Symbol.hasInstance]`, no `mapType` over a
  union RHS, no erasure, and a null (no-narrow) fallback where tsc uses `emptyObjectType`.
  `InstanceOfConstructorValueNarrowingTest`, 7 pins. Round 837's framing ("no `instanceof`
  arm in `extractNullNarrowing`") is RETRACTED — the arm's absence is not what blocked it.

- [x] **(NARROW.2)(b) — CLOSED round 838. An `any` subject survives a NEGATIVE narrowing
  branch.** Round 837 reported a narrow "leaking out of its `if`"; there is no leak. Both
  single-type negative branches (a type-predicate guard's, and `narrowByInstanceOf`'s)
  decided "the subject IS the target, so the false branch is impossible" with the
  ASSIGNABLE relation, and `any` is assignable to everything — so the else branch was
  `never`, the flow join of (`Foo`, `never`) produced `Foo`, and it accumulated across
  sibling guards. `anyNegativeBranchSurvives` (`TypeFlags.Any`, i.e. `any` plus the
  `error`/`unresolved` sentinels). `AnyNegativeNarrowingBranchTest`, 7 pins.

- [x] **(NARROW.2)(c) — CLOSED round 852. `checkMemberAccessMissing` reads a NARROWED
  receiver whose declared type is `any`; the `Object`/`Function` exemption landed; and
  `instanceof` now narrows an `any` subject at all, which round 838 had not noticed was
  missing.** THE PREDICTED FP STORM DID NOT ARRIVE: the eight-profile grid moved 0 added / 0
  removed at each of the three steps, against one rebuilt before-arm, and the corpus was
  clean too (14,016 / 0 / 3). Two mechanisms explain it and a next widening should be argued
  against both — the opening is keyed on "a narrow HAPPENED and produced a concrete
  `Type.Object`", a far smaller population than "the receiver is `any`"; and the new
  population inherits `cmamCheckResolvedObjectType`'s whole existing firewall rather than
  bypassing it. **The entry's premise is CORRECTED: (c) was the sole blocker for TWO of the
  three `types/any` cases, not three.** `narrowExceptionVariableInCatchClause` and
  `narrowFromAnyWithInstanceof` now match their baselines line for line;
  `narrowFromAnyWithTypePredicate` is 3 of 4, its residual being TS2349 on a `{}`-narrowed
  CALLEE — the CALL path, where `getCalleeType`'s `any` bail is 48.4% of every call-expression
  invocation (round 851), i.e. a separate widening over a much larger population, queued as
  (NARROW.2)(d) below. `AnyReceiverNarrowingTest`, 15 pins, nine one-at-a-time ablations of
  which three came back undiscriminated and are recorded as such. Original framing follows.
  It is the SOLE
  remaining blocker for all three `types/any` narrowing cases — re-measured after (a) and
  (b) landed, all three still emit nothing (`docs/conformance-worklist.md` § types/any).
  Every diagnostic those fixtures assert is a TS2551/TS2339/TS2349 on the narrowed
  receiver, and the walker is silent for an `any` receiver **by construction**, which is
  also its FP firewall: tsc's own sources use a narrowed-`any` receiver constantly, so this
  widens the most FP-sensitive walker in the compiler over exactly the shape the eight
  dashboard profiles are full of. Round 792's lesson applies with full force — a
  dashboard-profile zero bounds a hazard's FREQUENCY, never its existence, and that round's
  whole-function pre-gate measured 0 emitting calls in a 22,187-call skip set and still
  killed 7 corpus baselines. **Protocol: run the 8-profile grid at EVERY step, not only at
  the end, and expect the corpus — not the grid — to be the instrument that finds the
  regression.** tsc's exemption is `isTypeAny(type) && (predicate.type === globalObjectType
  || globalFunctionType)`, i.e. a guard onto `Object`/`Function` leaves an `any` subject
  `any`; `narrowFromAnyWithTypePredicate` pins both halves of that in one fixture.

- [ ] **(NARROW.2)(d) — let the CALL path read a NARROWED `any` CALLEE (TS2349 / TS2351).
  Round 852's residual, and a HIGHER-population widening than (c) was; do not take it as a
  side effect either.** The one remaining line of `narrowFromAnyWithTypePredicate` is `x()`
  where a `x is {}` guard has narrowed `x`: tsc emits TS2349 *"This expression is not
  callable."* + the chain *"Type '{}' has no call signatures."* on the CALLEE. `getCalleeType`
  bails on an `any` callee exactly as `cmamGeneralReceiverType` bailed on an `any` receiver,
  and **round 851 measured that bail as 48.4% of every call-expression invocation on the
  compiler profile** — against which (c)'s population (an `any` receiver that a guard actually
  narrowed to a concrete `Type.Object`) was tiny. **What round 852 learned that transfers:**
  key the opening on *a narrow HAPPENED and produced a concrete type*, never on *the callee is
  `any`*; check whether the downstream emitter's own gates (here the round-479
  `isGlobalFunctionType` escape and the `core is NewExpression` restriction at the general
  TS2349 site) already firewall the new population, because in (c) they did all the work; and
  run the grid at every step even though in (c) it never moved — it is the cheap arm, the
  corpus is the expensive one, and round 792's law is still the reason to run both.
  **Prize: one conformance line, and `types/any` 1 failing of 9 → 0.** That is a SMALL prize
  against that population, so price the FP risk before building anything.
  **ROUND-853 ADDITION, and it changes this item's shape: price the COUNTERS before building,
  not only the FPs.** (c) cost **+79.04% `narrow.walks`** (17,851 → 31,961) — measured only at
  round 853, because the gate that should have said so was loading a frozen binary. (d) opens a
  population round 851 measured at **48.4% of every call-expression invocation**, i.e. an order
  of magnitude larger, on the same "launch the walk to find out whether a narrow happened"
  mechanism. Run `cost_gate.py` on the PROTOTYPE, before the pins and before the grid.

- [x] **(NARROW.2)(e) — CLOSED ROUND 854 AS OUTCOME (c), REAL AND AVOIDABLE, WITH THE PRIZE
  MEASURED AND CAPPED: the opening costs `walkOnly` **146 ms = 1.91% of a 7,664 ms warm rebuild**
  (cold 381 ms) and is **26.3% of every narrowing walk the compiler performs** — and **85.6% of
  that is spent on `any` receivers the flow never narrows** (12,772 of 14,117 openings, **125 ms
  warm = 1.63%**), which by construction cannot produce a diagnostic.** Census (deterministic,
  bit-identical over six runs): `openings=14117 narrowed=1345 (9.5%) accepted=1051 (7.4%)` —
  and `openings` matches round 853's `+14,110` walk delta, so the two instruments measure one
  population. **Round 788's law does NOT rescue it**: `narrow.memoServed` moved only
  42,766 → 42,867 (+101) for +14,110 walks, so the work is ADDED, not moved, and deleting it
  deletes it. **Round 759's law runs the OTHER way here and had to be measured, not assumed**:
  the 90.5% that answer `any` carry 85.4% of the cost, because the predicate reads *did the flow
  narrow* while the cost is *how far the walk traversed*. The instrument is
  `PassTiming.cmamAny*` (a `detailed`-gated span + the produced-vs-consumed counts, printed by
  `--passTiming`), pinned by `NarrowedAnyCensusTest`; full tables in
  `docs/perf/narrowed-any-opening-price.md`. Implementation queued as **(NARROW.2)(f)** — and
  note the ceiling caveat there before building anything. Original framing follows.
  **PRICE (NARROW.2)(c)'s +79% NARROWING WALKS IN WALL TIME, AND DECIDE
  WHETHER THE OPENING CAN BE CHEAPENED. Round 853 opened this; nobody has yet spent a wall-clock
  measurement on it.** The counters (round 853, correctly-wired gate, ablation-attributed to
  round 852's `Checker.kt` hunk alone): `narrow.walks` **17,851 → 31,961 (+79.04%)**,
  `globals.lookups` +4.93%, `globals.misses` +4.99%, `typeNode.cacheHits` +4.10%,
  `typeNode.cacheable` +2.72%, `typeOfExpr.calls` +0.53%. **A counter is not a cost** (round 801:
  367,189 removed `String` allocations measured 0 ms) — and the counter that moved is the one
  round 735 attributed at **1,485 ms = 4.9% of the compile for 394 tail walks**, so the honest
  range here is "possibly free, possibly ~1%". **Do the cheap thing first:** `--passTiming`'s
  `narrowWalk` row on HEAD vs the reverted hunk, which is one rebuild each and answers whether
  anything further is warranted; only then consider narrowing the opening (e.g. a cheaper
  pre-test than launching a walk to discover a narrow happened). **Do not revert (c) for this** —
  it closed two conformance cases with a 0/0 grid and a clean corpus.

- [x] **(NARROW.2)(f2) — CLOSED ROUND 856. Suite **14,030 / 0 / 3** (exactly the predicted
  14,020 + 10 pins), `cost_gate.py` **all 20 counters +0.00%** on a binary verified to be HEAD
  (`XTSC_BUILD_ID=ae9779db…`, not a round-853-style frozen one) — so the `detailed` gate does NOT
  leak — and `huge_methods.py --fail-over 0` **0 over the limit** (649 classes / 14,567 methods;
  largest `walkFunctionBodiesInExpr` 7,702, 298 of headroom). **The ablation ran: all four arms
  load-bearing, each discriminated by a pin no other arm moves** (A1 → 1 red, A2 → 3, A3 → 1,
  A4 → 1), after first proving the harness dispatches (the round-855 bug form expands to the one
  word `[A1 A2 A3 A4]`; the committed array default to four, and every arm's edit was dry-run and
  reverted). **6 of the 10 new pins have a uniquely-their-own failure; the other 4 are recorded as
  undiscriminated rather than claimed** — and one of them, `a name occurring in a condition is in
  the narrowable set`, is a REDUNDANT GUARD (round 807) for the round-855 reason itself: its
  `declare const` subject mints a `FlowAssignment`, so A1 leaves it green and it never tested the
  condition arm. Renamed, along with the A3 pin whose name omitted the span it actually pins.
  Commit `362bc791`. Original framing follows.**
  ORIGINAL: FINISH ROUND 855's PAPERWORK: the gates and the ablation it did not run.
  DO THIS FIRST; it is ~30 minutes of wall time and no design work.** Round 855 landed a
  probe-only, `PassTiming.detailed`-gated change (`bc2495a7`, `d7461d4c`) and then ran out of
  round before its gates. (a) **`rm -rf` all four modules' `build/test-results/jvmTest` then
  `./gradlew jvmTest`**, count per module with `xml.etree` — baseline **14,020 / 0 / 3** plus the
  **10** new `NarrowableRootsPreTestTest` pins ⇒ expect **14,030 / 0 / 3**. (b) `python3
  scripts/cost_gate.py` and `python3 scripts/huge_methods.py --fail-over 0` — both should be
  unchanged (`+0.00%`, census 0), because a production compile keeps no inventory and pays a
  not-taken branch per flow-node mint; **a non-zero counter delta here means the `detailed` gate
  leaks and is a real regression, not a rebaseline**. (c) `scripts/round855-ablate.sh` (its
  arg-default bug is fixed) — four arms, one mistake at a time: drop the `FlowCondition` arm, drop
  the `FlowAssignment` arm, zero the pre-test span, remove the `detailed` gate. **Until (c) runs,
  the 10 pins are unverified and must not be cited as discriminating**; any arm that comes back
  green is a redundant guard to be RENAMED, not a pin to be claimed (round 807).

- [x] **(NARROW.2)(f) — CLOSED ROUND 855 AS A MEASURED NEGATIVE. The candidate pre-test refuses
  **0 of 14,117** openings on the compiler profile and COSTS 150–211 ms to do it.** Censused as a
  probe honouring nothing, so the population stayed bit-identical to round 854's
  (`openings=14117 narrowed=1345 accepted=1051`, 46 diagnostics) and the reading is directly
  comparable: `refused=0 noPath=0 kept=14117 refusedNarrowed=0 refusedAccepted=0 keptNarrowed=1345
  refusedWalkOnly=0ms preTestCost=211/162/150 ms`, re-taken on the committed binary as
  `refused=0 … preTestCost=168/128 ms`. Go/no-go was ~70%. **THE REASON IS STRUCTURAL AND KILLS
  THE WHOLE FAMILY: every `VariableDeclaration` / `Parameter` / `BindingElement` mints a
  `FlowAssignment` whose subtree contains the declared name, so EVERY root declared in a file is
  in that file's narrowable set BY CONSTRUCTION — the set can only refuse a root with no
  declaration in the file (an import), and tsc's `any` receivers are locals and parameters. Do not
  re-propose a name-keyed narrowability set.** The instrument was shown alive on the same binary
  (two-file CLI: `refused=1`, → `0` on adding one `if (imported)`), and that control is pinned at
  the SET level because `diagnose()` is single-file and structurally cannot express the refusing
  population (an unresolved import is not `any`, so no opening runs — measured `openings=0`, which
  is what three first-draft pins were really reporting). **Round 854's 1.63% stays a CEILING no
  per-file name-keyed predicate can realise, so (NARROW.2)(c)'s 1.91% is accepted as measured, not
  carried as an open regression**; a future attempt needs a path-and-position oracle, which is the
  walk itself. `docs/perf/narrowed-any-opening-price.md` § 4b. Original framing follows.
  **REFUSE THE `any` RECEIVERS THE FLOW WAS NEVER GOING TO NARROW, WITHOUT
  LAUNCHING A WALK TO FIND OUT. Round 854 measured the prize and it is a CEILING of 1.63% of a
  warm rebuild — census the pre-test BEFORE implementing it, or this is a noise-band change with
  FP risk attached.** The object: `cmamNarrowedAnyReceiverType` calls
  `getNarrowedTypeForReference` **14,117** times per compiler-profile compile; **12,772 (90.5%)**
  come back with the declared `any` and carry **85.4% of the cost** (325 ms cold / 125 ms warm).
  **THE CANDIDATE PREDICATE:** a per-FILE set of the identifier texts occurring in every narrowing
  flow node's expression — `FlowCondition.expression`, `FlowAssignment.node`, `FlowCall.node`,
  `FlowSwitchClause.switchStatement.expression`, `FlowArrayMutation.node` — computed once in
  `FlowGraph`'s `init` (which already walks the tree for `flowById`) and consulted as
  `flowPathRoot(path) in currentFlowGraph.narrowableRoots` before the walk.
  **SOUNDNESS:** `narrowTypeFromFlow` matches a dotted PATH against those expressions and the
  path's ROOT is an `Identifier` that must occur in one of them, so a name in none of them cannot
  be narrowed by any of them; collecting every identifier text is a conservative superset, and a
  per-FILE set also covers `FlowStart.outerFlow` (a closure reading its enclosing scope's narrow).
  **THE THING THAT DECIDES WHETHER TO BUILD IT, AND IT IS CHEAP:** the predicate is COARSER than
  the oracle — a root narrowed *somewhere* in the file but not on *this* path still walks — so
  land it FIRST as a probe that HONOURS NOTHING (the `--cmamPreGate` / `--ccetPreGate` shape,
  rounds 792/793) and count how many of the 12,772 it refuses and how much of the 325/125 ms sits
  behind them. **Half of them lands at ~0.8% warm, i.e. inside the ±1.0% band — do not build the
  real gate on a probe reading below ~70%.** Round 792's law applies to the gate itself when it
  does land: a whole-function pre-gate on THIS function measured 0 emitting calls in a 22,187-call
  skip set on this very profile and still killed 7 corpus baselines, so the corpus is the
  instrument, not the grid. **And re-check `narrow.memoServed` after the gate**: it moved only
  +101 for +14,110 walks today (so the work is genuinely deletable), but a gate that skips a walk
  whose memo entry a LATER asker would have been served by converts a deletion into a move
  (round 788).

- [x] **(NATIVE.1) FIXED ROUND 827 — the native `runWithDeepStack` actual now runs the
  pipeline on a 256 MB pthread instead of the default 8 MB main stack (a 32x margin), with
  the thread-local id handoff EVIDENCED by observed ids (seed 191 -> 196 -> write-back 196,
  intrinsics identical across the boundary) and pinned by `DeepStackHandoffTest`, verified
  discriminating by one-at-a-time ablation. 256 MB is lazily committed (max RSS ~108 MB),
  so it costs nothing on a zero-swap box. `CfaTooLargeBailTest` returned to commonTest.
  REMAINING AND DELIBERATE: an overflow is now ~32x RARER, NOT CATCHABLE — `StackOverflowError`
  is still a never-thrown stub natively and the `init` boundary guard is still inert, so
  `DeepExpressionChainTest` can never return and a deep enough shape still kills the process.**
  Original framing follows. P0 — NATIVE xtsc HARD-CRASHES ON DEEPLY-NESTED INPUT, BECAUSE
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

**WARM-JVM ARC (round 843, owner directive: "prioritize profiling-based performance improvements
on the warmed-up JVM"). The premise is measured, not assumed: no warm attribution existed before
round 843, and the ladder it re-measured moved 40%. `docs/perf/warm-jvm-attribution.md`.**

- [x] **(WARM.1)(b) — CONFIRMED round 845: −33.6%, 4/4, 1.505× on the warm artifact, measured on
  the ISOLATED r802-parent → r821-close interval (arms verified at 19-over-limit vs 0 in the
  bytecode). `huge_methods.py --fail-over 0` is a WARM-PATH gate; grow a hot method past 8,000 and
  you must A/B it with `ab-warm.sh`, because the cold protocol is structurally blind to the class.
  Original framing follows.** (b) — CONFIRM OR KILL THE (JIT.1) HYPOTHESIS WITH A WARM A/B OF A
  PRE-802 BINARY AGAINST HEAD.** Round 843 measured warm improving 39.5% against cold's 12.6% over the same
  interval and named the huge-method split arc as the leading cause, explicitly as a HYPOTHESIS.
  It is cheap to settle and it decides how the whole (JIT) family is valued from here: if
  confirmed, **rounds 802-821 bought roughly three times what they were credited with**, and the
  `huge_methods.py --fail-over 0` ratchet is protecting the SERVER artifact far more than the CLI.
  **Protocol:** two class dirs (build `git show <pre-802 sha>` into one), `scripts/ab-warm.sh`,
  ±1.0% band, box untouched — and note round 843's own finding that the warm band in ms is now
  **±70 ms, not ±114**, because the denominator shrank. **Trap to avoid:** the pre-802 binary must
  be measured with the SAME probe setting as HEAD, and the probe now costs ~50% of a warm run.

- [x] **(WARM.1)(c) — CLOSED ROUND 846: `--passTiming` now has three TIERS and the `rows` tier is FREE (+0.25% cold, 0.0% warm), so the warm per-pass table's absolutes are now trustworthy.** The probe lands **99.5-99.8% in checker-init and 101-109% in `checkSpine`** (measured, both regimes), so § 3.1's bracket **(A)** is right and **(B)** refuted: **warm `checkSpine` is 74.27% of checker-init (cold 79.41%) — its share FALLS**, checker-init is 88.89% of the warm wall and the front end 11.1%. Warm budget: `checkSpine` 5,336 ms = 66.0% (enter 3,121 / leave 1,553), the other 416 passes 1,849 = 22.9%, front end 898 = 11.1%. Two corrections a future round must carry: **§ 4's warm speed-up column is INVERTED** for its two biggest rows (`checkSpine` really warms 3.46× and the tail 2.59×, not 2.24× vs 2.38× — a constant additive probe compresses the ratio of the row it lands on), and **the probe's own price warms up** (first instrumented rebuild in a process 3,457 ms, second 1,856 ms), so round 843's n=1-per-process figure is a first draw. Superseded original text: ~~RE-TAKE THE ATTRIBUTION WITHOUT THE INSTRUMENT DOMINATING IT.~~ The warm
  table's absolutes are inflated ~1.55× by `--passTiming` itself and only >=77% of that is even
  localized (to checker-init; the split inside is unmeasured, and the two bracketings disagree on
  whether `checkSpine`'s warm share falls to 72% or rises to 83%). Until that is resolved **no warm
  lever can be sized**, which is the blocker on every item in this arc. Cheapest route first:
  price the probe's own sub-costs by ABLATION (the `getTypeOfExpressionDistinct` set is the prime
  suspect — 574,620 calls into a distinct-keyed set — but round 801's law applies, an allocation
  count is not a cost), or add a probe MODE that keeps the pass rows and drops the per-call
  counters, calibrated DIFFERENTIALLY per round 734, never by an empty-span loop.

- [x] **(WARM.2) — CLOSED ROUND 844 AS A MEASURED NEGATIVE: sharing the bound LIB state across
  daemon requests is worth 8.65 ms = 0.13% of a warm rebuild, ceiling 0.20%.** Priced before
  anything was designed. It does not merely MOVE (round 788 does not apply — the type caches are
  per-`Checker`), so the number is real and it is simply small: `parseCache` already harvested the
  expensive half, leaving a top-level bind over body-less `.d.ts` for 185 global names. Hazards
  measured rather than theorized: **175 merge collisions per compile, 20 mutating a lib symbol**
  (`Symbol`, `ImportAttributes`), a lower bound, plus an id-ORDER shift of ~185 whose failure mode
  is byte-identical output (round 607). Do not re-open; `RealLibSnapshots`' "binding is
  deliberately per-consumer" KDoc is right and now costs a known 4.9 ms warm.

- [x] **(WARM.3) — CLOSED ROUND 849 AS A MEASURED NEGATIVE: the per-request re-derivation of lib
  TYPES is 71 outermost mint spans costing 1–6 ms of a 7,139–7,316 ms warm rebuild = 0.01–0.08%**,
  an order of magnitude below even (WARM.2)'s 0.13%. Priced before anything was designed, with the
  round-801 produced-vs-consumed ratio taken FIRST: **119 lib mints produce 15,932 consumptions
  (133.9 : 1)**, so the derivation is already fully amortized WITHIN one request and sharing across
  requests could only ever delete the production side. Lib mints are 0.80% of all mints while being
  20× more expensive each (93,464 vs 4,606 ns/span) — round 716's law by COUNT, not by unit cost.
  **AND THE SAME RUN RETIRES THE WHOLE DIRECTION, not just the lib slice: the entire mint boundary,
  lib plus user code, is 38–66 ms warm = 0.5–0.9%**, so no cache at `getDeclaredTypeOfSymbol` /
  `resolveStructuredTypeMembers` can clear the ±1.0% warm band whatever it is keyed on. **The
  hazard analysis this entry demanded (Type ids, interning, round 778's first-touch instantiation
  context, whose failure mode is byte-identical output) is MOOT at this price — do not re-open it.**
  Instrument: `LibTypeCensus` + `--libTypeCensus` + `BenchMain`'s `libtypes` tier, pinned by
  `LibTypeCensusTest`. Full table and method: `docs/perf/lib-type-rederivation.md`.

- [x] **(WARM.7) — DONE, ROUND 860. THE DUPLICATED UMD SCAN IS GONE, AND THE HALF THAT "NEEDED A
  GATE" NEEDED NONE: 96.4 ms -> 4.7 ms, a saving of 91.7 ms = 1.17% of a warm rebuild.** Both
  `checkUmdGlobalVsDeclareGlobalConst` and `checkCrossFileModuleAugmentationDuplicates` ran their own
  copy of the same `java.util.regex` over all 9,977,097 characters, matching zero times. **(a)** they
  now share one per-FILE memo whose value is a pure function of the file text (so it cannot be the
  program-ORDER dependency of rounds 754/776/778) — **41.5 ms = 0.53%**, and the row that moves is
  the LATER pass (73j serves from the memo 73h filled), which round 859 could not have said. **(b)**
  the matcher itself is replaced by a hand-written EXACT equivalent anchored on `namespace` via
  `indexOf` — a further **50.2 ms**, total **1.17%**. **The `.d.ts` gate was declined on its own
  terms, which is the round's transferable result:** it is a claim about where the construct may
  legally APPEAR, and round 792's law is that a profile with 0 `.d.ts` files cannot falsify such a
  claim; the sound substring filter was already priced at zero. An exact rewrite makes no legality
  claim at all, is differentially pinned against the pattern it replaces (kept live as the oracle,
  207,360-string battery, names AND offsets), and additionally removes a regex-DIALECT hazard in
  `commonMain`. Round 793 needs no subtraction — both passes still exist and the boundary census is
  identical in all three arms (834 rows, `calls=1`); round 788 is answered by produced-vs-consumed
  (78 productions serving 156 consumptions; the two other UMD readers use different patterns and
  their rows are flat across arms). **The A/B decides nothing and is reported as such** — two
  `ab-warm.sh` batches DISAGREE (`NOISE-DOMINATED, 1/2, -0.75%` then `WIN of 3.0%, 3/3`) and all four
  arm sds are 1.55-1.85%, i.e. above CLAUDE.md's ~1% discard threshold; the ROWS are the evidence.
  Gates per sub-step: suite 14,049 then 14,051 / 0 failures / 3 skipped, `cost_gate.py` +0.00% on all
  20 counters both times, `huge_methods.py --fail-over 0` 0 over the limit, 8-profile grid
  added=0 removed=0 on every profile both times. Commits `5462fa75`, `c9b693cd`.
  `docs/perf/warm-tail-attribution.md` § 10.

- [x] **(WARM.9) — DONE, ROUND 861. `init:buildFileLocalTypeMaps` PRICED WARM: THE DELETABLE
  POPULATION IS 85.6 ms = 1.09% OF A WARM REBUILD, NOT THE ~1.68% ROUND 859 PROJECTED — THE ITEM
  STAYS CLOSED, AND THE REASON IS NOT THE 1.09%.** Round 859 left the only tail pass over 1% priced
  by an ARITHMETIC PROJECTION (47.1% of 3.56%) and said so; this replaces it with a measurement.
  Built: `BenchMain`'s `fltm` tier — round 829's `--fltmCensus` had never been run warm because, like
  `FrontEnd` before round 859, **it had no tier name** — and it is the ONE tier that arms two probes
  (the census AND `PassTiming`'s `rows`), because prize-over-row taken from two rebuilds would be a
  cross-draw ratio against a row whose warm spread is 41%; paired, the ratios are 4-6x tighter than
  their operands. Two processes, tier order ROTATED, 4 census draws / 8 row draws. **Measured: the
  pass row is 253.5 ms mean (237.1 excluding each process's first instrumented rebuild, which is the
  MAXIMUM of its tier in both processes — round 846's law showing up in a pass row); the
  direct-resolve wall is 94.3% of it; the `decl` branch's deletable slice is 35.8% of it = 85.6 ms =
  1.086% of the warm wall (per-draw 1.00-1.15%).** The projection overshot **1.54x** in three named
  deflations: 47.1% is a COUNT share while the ms share of the direct-resolve wall is 40.5% (x0.860),
  that wall is only 94.3% of the ROW (x0.943), and 6.0% of the deletable ms is the `typealias`
  TS2589/TS2615 detector, which deletion would DELETE a diagnostic rather than defer one (x0.940).
  **The census's population counts are bit-identical to round 829's COLD ones across 32 rounds and
  two JIT regimes** (12,738 / 4,161 / 1,499 / 8,577 / 6,008), only the read site moving
  (16,043 -> 16,183 calls, 278,355 -> 280,408 misses). **THE DECISION, and it would be the same at
  1.5%: the 85.6 ms is an UPPER BOUND whose deduction this census is structurally unable to
  measure** — its own report says the pass makes 12,738 direct `getTypeOfSymbol` calls and only
  **1,842** nested symbol asks, reaching just 434 symbols it did not start from, so the 225 ms lives
  in `getTypeFromTypeNode` / member resolution / interning, **none of it symbol-keyed and none of it
  visible to `askedLater`**; "never asked again" is tested at the one granularity carrying almost
  none of the work. Supporting: the deletable resolutions are the CHEAP ones (15.2 us vs 19.9 us,
  round 758/759's law), and **no instrument here could defend the change** — the warm A/B band is
  +-1.0%, and unlike round 860 the ROWS cannot substitute, because a lazy rewrite moves cost between
  rows and `checkerInitNanos`'s own draw spread over these 8 rebuilds is 11.0%, **8.7x the effect**.
  Round 829's three structural objections (296,591 read-site lookups, the round-754/776/778 program
  ORDER hazard, the per-key bail save/restore a lazy read site must reproduce) are untouched.
  Reviving it needs an instrument that can answer the TYPE-level move question plus a replay
  ablation (record the deletable `file|name` keys, skip exactly those next rebuild, demand
  byte-identical output) — together a round, separately neither. Ablation of the new pin: deleting
  the arm from `tierBegin` reddens 2 of 5, and they are the two naming that arm. Gates: suite
  14,056 / 0 failures / 3 skipped, `cost_gate.py` +0.00% on all 20 counters, `huge_methods.py
  --fail-over 0` 0 over the limit. Nothing under `commonMain` changed, so the 8-profile grid is
  vacuous by construction and was not run. Commit `3909ae55`.
  `docs/perf/warm-tail-attribution.md` § 11.

- [x] **(WARM.8) — DONE, ROUND 861. THE POST-CHECKER TAILS ARE ONE FUNCTION: `cpcRequireOnlyOrphans`
  is **130.4 ms = 1.72% of a warm rebuild and 97.6% of the whole region**, with a **4.1% draw
  spread** — the tightest number in the warm arc.** Round 859 sized the region at 143.2 ms = 1.90%
  with the worst warm-up ratio it measured (1.27x) and NO probe below it. Two levels of abutting
  `FrontEnd` blocks (residue **0 ms at both levels, in every draw**) put everything else after the
  checker at **~3.1 ms total**: the post-check diagnostic/`removeAll` chain 1.9 ms,
  `collectCrossFileNamespaceExports` 0.8, emit prep 0.2, `topologicalSort` **0.17**, `hasCycle` +
  dep-map **0.01**, output selection 0.03. **THE PRIOR WAS WRONG BY 800x** — the obvious suspect was
  the topological sort over 78 barrel-connected files, and the second level exists precisely to stop
  that prior becoming a sentence. `cpcRequireOnlyOrphans` implements tsc's
  `moduleResolutionWithRequire` rule (a `.ts` reached only by a bare untyped `require('./x')` is not
  a program file), so it walks every statement of every program file; its gate
  (`hasExplicitFilenames && tsFileNames.size > 1`) passes for any real multi-file project. Pinned by
  `PostCheckerPartitionTest`, which drives a real multi-file `ProjectCompiler` build because the
  invariant is the PLACEMENT — a dropped `close` is invisible to every output, to `cost_gate.py` and
  to the corpus. Gates: suite 14,060 then 14,061 / 0 failures / 3 skipped, `cost_gate.py` +0.00% on
  all 20 counters both times, `huge_methods.py --fail-over 0` 0 over the limit both times. Commits
  `61194621`, `9eedc04b`. `docs/perf/warm-tail-attribution.md` § 12.

- [x] **(WARM.11) — DONE, ROUND 864. THE 4.20% "FLOW WALK" IS TWO WALKS, AND THE SECOND IS
  `FlowGraph`'s nodeId SIDE TABLE: **172.99 -> 58.63 ms = 114.4 ms = 1.665% of a warm rebuild, a
  66.1% fall in the row**.** Round 859 measured `FlowGraphBuilder.build`'s residue at 316.7 ms =
  4.20% warm — the largest region outside `checkSpine` — and round 801 had closed the region COLD
  without asking what the residue contains. Partitioned (residue 0.05%, exhaustive by construction
  since `build()` is four statements): the flow-MINTING walk **196.3 ms**, the `FlowGraph`
  CONSTRUCTOR **163.5**, of which **162.3** is a second whole-tree `forEachChild` traversal boxing a
  `(pos,end)` `Long` per node — **876,324 nodes visited, 70% of them answered `null`**. The table has
  ONE reader, `flowAt`, whose fallback IS that same map lookup, so filling it from the **262,404**
  RECORDED nodes is exactly equivalent; round 788's question was then answered by census on the
  UNCHANGED binary — of **176,935** `flowAt` calls, **168** would move to the fallback, i.e.
  produced-to-consumed **0.0003, not 1.000**, a DELETION. Landed with `--flowIndexLegacy` keeping
  both fills in one binary (round 795), which is also what makes the 8-profile grid stronger than a
  two-class-dir one. Priced negative in the same region: `mutableMapOf()` -> `HashMap()` on the flow
  map measures **-8.9 ms one way and +10.5 the other, sign undecided**, and was REVERTED. Not
  attempted, with the arithmetic: a per-node partition of the minting walk would cost 83-173 ms of
  boundary against a 196 ms row. Ablation four faults one at a time, 120 pins per arm: **M1** (arm
  inert) 1 RED uniquely the non-vacuity pin, **M2** (wrong key) 10 uniquely the "answered by the map"
  pin, **M3** (an extra, wrong entry) 2 with **no uniquely-its-own failure — reported as caught by
  the general net**, **M4** (entries dropped) **0, green on purpose**, because a missing slot
  degrades to the map fallback: completeness of the list is a SPEED property and the correctness
  obligation is the other direction. Gates: suite **14,094 / 0 / 3**, `cost_gate.py` +0.00% on all
  20, `huge_methods.py --fail-over 0` 0 over the limit, grid `added=0 removed=0` both directions on
  all 8 profiles, emit tree byte-identical at 78 files. Commits `ae84496e`, `035446ea`, `2cee9cf5`, `538aac53`.
  `docs/perf/warm-flow-graph-attribution.md`.

- [x] **(WARM.10) — DONE, ROUND 863. THE WHOLE-PROGRAM REGEX CLASS IS SWEPT, AND THE LAST OFFENDER WAS ON
  THE EMIT PATH: `Transformer.transform`'s jsxRuntime pragma scan, **44.1 ms -> 1.66 ms = 42.5 ms = 0.53%
  of a warm EMIT rebuild, a 96.2% fall**.** Rounds 860 and 862 each hit this class by accident, so it was
  promoted and enumerated. Membership is decidable: `BnM.optimize` gives Boyer-Moore only to a pattern whose
  root is a literal `Slice` of **>= 4** characters, so `\b`, `(?m)^`, an alternation, a character class and a
  1-3 character literal all mean every offset of the text is attempted. The census
  (`docs/perf/whole-program-regex-census.md`) covers all **110** `Regex` sites in `commonMain` by SUBJECT and
  by prefix: **21 are WHOLE-FILE and exactly ONE is both ungated and prefix-less here** — its literal is a
  slash-star, TWO characters, over the full text of every transformed file. **It was invisible because
  round 738's emit gate makes `--noEmit` skip the transformer entirely**, so `BenchMain`, `cost_gate.py` and
  the `--noEmit --listAll` grid were blind at once; the round had to BUILD the instrument (an `emit` mode for
  `BenchMain` with a closed flag vocabulary, plus `FrontEnd.TR_JSXPRAGMA` and its census) before it could
  price anything. Measured 2 processes x 2 draws: 44.139 -> 1.662 ms over 9,977,097 characters finding **0**
  pragmas in both arms; the PARENT row cannot see it (17.5% draw spread, ~14% between-process effect = 3x the
  effect) and that is recorded. Landed as an EXACT rewrite anchored on `@jsxRuntime`, the pattern kept live as
  the oracle of a 12,000-case differential; the two non-obvious terms are that regex `\s` is narrower than
  `Char.isWhitespace()` and that matches may not overlap. Class exhausted on this profile per a JFR discovery
  run (64 of 9,541 samples reach `java.util.regex`, **54 of them this site**); `RealLibResolver.referencedLibNames`
  (~6 ms) left, and the nine members gated to zero here are recorded rather than acted on (round 792). Ablation
  six mistakes one at a time, 26 pins per arm: M1 2 RED, M2 2, M3 2, M4 1, M5 1, M6 6 — every mistake with a
  uniquely-its-own failure, and the battery recorded as the general net rather than claimed as attribution.
  Gates: suite **14,090 / 0 / 3**, `cost_gate.py` +0.00% on all 20, `huge_methods.py --fail-over 0` 0 over the
  limit, grid `added=0 removed=0` both directions (control), EMIT `diff -r` 78 files IDENTICAL (the real gate).
  Commits `fa2e5f27`, `49256c56`, `9c7425a2`. `docs/perf/whole-program-regex-census.md`.

- [x] **(WARM.8)(c) — DONE, ROUND 862, AND THE QUEUE ITEM WAS THE SMALLER HALF: `cpcRequireOnlyOrphans`
  goes **138.7 ms -> 0.0005 ms** and the whole post-checker region **141.9 -> 2.84 ms = 98.0% of the
  region, 139.1 ms = 1.82% of a warm rebuild** — of which **136.4 ms (1.79%) is an EXACT rewrite
  that helps EMIT mode identically** and only **2.3 ms (0.03%)** is the check-only gate this item
  named.** The one-consumer argument was re-established, not inherited, and held (four `grep` hits
  for the value; ONE write site for `jsOutputMap`, in `cpcTransformAndEmit`, whose loop iterates
  `emptyList()` under `skipEmitOutputs`). The round's turn came from answering the item's own open
  question ("whether the walk itself can be made cheap … a second, larger question"): sub-partitioned
  into its three per-file scans, the function is **87.36% one regex** —
  `\bdeclare\s+(?:const|var|let|function)\s+require\b`, which begins with a zero-width assertion,
  so `java.util.regex` gives it no Boyer-Moore prefix search and attempts it at **every one of
  9,977,097 positions**, where it accepts **0 of 78 files** (the sibling `import\s*\(…` over the same
  text, which HAS a literal prefix, costs a seventh as much). Landed as an exact hand-written scan
  anchored on the literal `require` plus a deferral of the `import("…")` pass behind the candidate
  sets, then the gate on top. Ablation, one mistake at a time: **M1** (drop `require\b`) 3 RED,
  **M2** (gate inert) 1, **M3** (gate widened to `options.noEmit`) 1, **M4** (pass 2 always skipped)
  2 — four disjoint failing sets, no redundant guard. Gates: suite **14,072 / 0 / 3**, `cost_gate.py`
  +0.00% on all 20 at every sub-step, `huge_methods.py --fail-over 0` 0 over the limit, 8-profile
  grid **added=0 removed=0 both directions** from two class dirs (651 vs 652 classes), and the EMIT
  tree byte-identical at 78 files. `ab-warm.sh` printed `WIN of 2.3% (B wins 3/3)` and is set aside:
  arm A's sd is 2.76%. Commits `ccd0de2b`, `925d4d24`, `2926050a`, `366d5bd4`.
  `docs/perf/warm-tail-attribution.md` § 13.

- [x] **(WARM.6) — DONE, ROUND 859. THE TAIL IS FLAT WARM TOO — round 801's cold verdict survives
  the regime change, which was not known.** The ~416 tail passes are 1,530 ms = 20.3% of the warm
  artifact and **exactly ONE clears 1%** (`init:buildFileLocalTypeMaps`, 268.4 ms = 3.56%); the
  second is 0.66% and **344 of the 416 are under 5 ms**, summing 325 ms. Round 846's ratios
  replicate on a fresh build and a fresh dependency tail (`checkSpine` 3.27× vs the tail 2.67×,
  against 3.46×/2.59×). **But the ratio is NOT uniform — 0.85× to 5.68× over the 72 passes ≥ 5 ms,
  median 2.90× — and its bottom is (WARM.7).** Also: **the front end is 8.79%, not 11.1%** — round
  846's residual is front end 663 ms + post-checker tails 143 ms, and the two instruments agree to
  **99.2% inside one process**; they warm 4.38× and 1.27× respectively. Inside it the warm cost is
  the FLOW GRAPH, not the I/O: config 41×, crawl 7.52×, `bindLexicalScopes` 6.44× all collapse,
  while `FlowGraphBuilder.build` warms 2.73× and its walk is **4.20% of the warm artifact**, the
  largest region outside `checkSpine` (round 801 closed it cold at 3.30%; the share RISES). Built:
  `BenchMain`'s `frontend` tier — the `FrontEnd` probe had never been run warm because it had no
  tier name — pinned by `BenchFrontEndTierTest`, whose fixture RECORDS through the probe's own entry
  points so it reddens if the tier were inert. Gates: suite 14,040/0, `cost_gate.py` +0.00% on all
  20, `huge_methods.py` 0 over the limit. `docs/perf/warm-tail-attribution.md`.

- [x] **(WARM.5) — DONE, ROUND 851, AND IT CLOSES THE WARM ARC. The call path is
  `checkSingleCallExpressionTypes` = ~618 ms = 8.4% of a warm rebuild, and it reads
  94% CHECKING / 6% dedicated-walker layer — the fourth independent site to give that
  answer (round 850: cta 94/6, cpa 98/2; round 733: 88.4% cold). The largest
  non-checking object in it is the round-793 seven-walker prologue at 0.23%. SIXTH
  consecutive priced negative; `docs/perf/warm-call-attribution.md` § 6 is the arc's
  closing statement. Three findings a later round should not re-derive: the exit
  census reads 0 EMISSIONS of 52,413 invocations on this profile (all 8.4% is
  verification — NOT a deletion argument, round 792); round 734's "half of
  getCalleeType is wasted" is refuted by 12× (the 48.4% that bail cost 425 ns each
  against 9,004, i.e. 4.1% of the row); and this probe reaches
  `checkArgumentsAgainstSignature` at 4.02% against round 850's independent 4.37% —
  92% agreement between two instruments sharing no code. The probe already existed
  (`CallSections`, CALL.1); what it needed was a COARSE twin, an exit census, and
  `BenchMain` tiers. Original text follows.** Original: partition the other ~60% of `ccetSpineLeave`, the largest warm handler.
  Round 850 measured `checkArgumentsAgainstSignature` at 4.37% of the warm artifact against the
  handler's 10.8% (round 847), so **~60% of the single biggest object in a warm rebuild — callee
  resolution, overload selection, the round-793 call prologue — has no section probe in either
  regime.** It is the only place in the top four where a warm table can still show something new;
  everything else in the top four is now attributed and priced (`docs/perf/warm-intra-handler.md`).
  Build it as a `CcetSections` with a `*coarse` twin (same shape as the three existing probes, so
  `BenchMain` needs only two more tier names), open it on the WRAPPER (round 786), and carry round
  850's two calibration rules: the boundary is **97–202 ns warm, not the cold 501 ns**, and if the
  partition is LAYERED the differential must divide the OUTERMOST level's Δ by ALL extra
  boundaries. Add the exit census in the same pass — it costs no new boundary (round 796) and it is
  what round 850's `arg` table shows is worth having (91% of argument typing serves arguments that
  never reach the relation). **Prize is unknown and may well be another priced negative; say so up
  front.** While in `BenchMain`, fix the round-850 label defect: dump the report BEFORE clearing
  each probe's `mode`, so a `*coarse` arm stops printing `mode: ON`.

- [x] **(WARM.4)(b) — DONE, ROUND 850. The three warm tables are in
  `docs/perf/warm-intra-handler.md`; the verdict is STRUCTURAL (`spineCtaM3StatementAnchor` is 94%
  its four checking calls / 6% scaffolding, `checkPropertyAccessInExpr` 98% / 2%, reproducing round
  733's cold 88.4% and tighter), and NO candidate clears the ±1.0% warm band — the best is the nine
  pre-emission probes in `checkSinglePropertyAccess` at 0.99%, handed on as a measured candidate.
  Two corrections this entry needs read back into it: the "48.7% of the warm artifact" below is
  48.7% of the warm SPINE = 29.0% of the artifact, and since `arg` is only 40% of `ccetSpineLeave`
  what the three probes directly attribute is 18.75%. The warm boundary is 97–202 ns — and the
  NESTED partitions make the obvious estimator overstate it by 1.7–2.2×. Original text follows.** Round 847 measured that a handler's warm SHARE is not
  its cold share — the top two spine handlers SWAP between regimes — and that a probe boundary is
  ~1.85× more expensive cold than warm. **Every intra-handler section table on record
  (`CtaSections`, `CpaSections`, `ArgSections`) is a COLD one-shot**, so none can be read as a warm
  attribution until re-taken. `BenchMain` now accepts `cta`/`cpa`/`arg` tiers plus a `*coarse` twin
  each — the COARSE twin is what prices the boundary DIFFERENTIALLY inside one warm process (round
  734: never an empty-span loop). Targets: handler #1 `ccetSpineLeave` **18.2%** (reached via
  `arg`), #2 `spineCtaM3StatementAnchor` **17.7%**, #3 `cpaSpineLeave` **12.8%** — together 48.7%
  of the warm artifact. **START AT `bash scripts/round849-warm-sections.sh 2`**; phase 1 already
  validated that script's build → cost-gate → JIT-gate → daemon-stop → measure path. Carry this
  calibration warning: round 849's in-situ empty pair read **96–209 ns warm against 501 ns cold**,
  so every cold table's `net` column subtracts a boundary 2.4–5.2× too large for the warm regime.
  Print the partition checks (sub-rows must sum to the independently measured parent row — round
  847 got 99.3% and 102.6%), and add an EXIT CENSUS where the partition already crosses a boundary
  (round 796: zero new boundaries, and strictly stronger than differencing adjacent call counts).

- [x] **(SERVE.1) — CLOSED ROUND 848. THE ARGUMENT LOOP NO LONGER LEAKS MODES ACROSS SERVER
  REQUESTS — a `ModeLedger` save/restore around the whole of `runCli`, covering FIFTEEN flags (the
  six named below plus eight the audit found, among them `SpineDispatch.mode == GATED` and both
  pre-gate probes). Measured aside that corrects this entry: on the compiler profile all 14 arms
  are byte-identical INCLUDING the deliberately-corrupting `--flowScanBogus`, and with
  `FlowScan.bogus` defaulted true the 13,902-test core suite reports exactly ONE failure — its own
  equivalence pin. So "several of these change what the compiler decides" is true by CONSTRUCTION
  and unconfirmed by either instrument; the leak with a measured, always-visible effect is
  `PartitionCheck.reportLines`, re-printed by every later request. Original text follows.**
  Round 843 closed the `PassTiming`/`FltmCensus`/`GlobalsAmp` half; **`CpaSections.verify*`,
  `FlowScan.legacy`/`eagerSet`, `IanySections.*GateOff`, `ArgNarrowGate.mode`,
  `ParallelCheckMode.workers` and `PartitionCheck.workers` are still unrestored**, and unlike the
  timing flags several of these CHANGE WHAT THE COMPILER DECIDES. In the one-shot CLI that is
  invisible (the process exits); under `--serve` a single `--workers 4` or `--flowScanLegacy`
  request silently reconfigures every later request on that server — which is a correctness
  hazard, and `--workers` specifically was a race until round 825. **Each flag needs its own
  read-site check before restoring it** (a mode whose default is not the field's initial value
  must be restored to the value it had, not to a guessed default — round 619's lesson, where
  assigning a default back re-enabled the lab's disables for every alphabetically-later test).
  Pin the invariant the way round 843 did: read the flag OBJECTS after a request, not the
  response text.

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
**CORRECTED AGAIN round 841 — and this one changes the SIZE of the gap this whole section
is organised around.** The `13.4 s` is a 3-run measurement on the RETIRED 4-core box, and
it stopped being the best evidence on 2026-07-30, when commit `a1ff6033` made CI build the
GraalVM image on every push and run it as a fourth arm in BOTH modes. **75 CI rows × 2
modes = 150 native/tsc ratios: median `1.02×` tsc** (check-only 1.04×, emit 1.01×; 110/150
within 1.10×; latest row `e355a990bfaa` reads 1.00× check-only and **0.97× — faster than
tsc — on emit**). So "we are ~2.4× off parity" is true of the **JVM cold arm only** (CI
check-only median 2.51×, latest 2.41×); *the same compiler as a native image is already at
parity*. Nothing in § 0.1's COLD-JVM budget model changes — but any sentence that reads the
2.4× as a property of the compiler rather than of the artifact is now wrong, and this
section, (JIT.2a) and (ENGINE.3)/(SCOPE.1) all contain such sentences.
`docs/perf/aot-native-image.md` **§ 0a** is the authority for the native arm from here.
**ROUND-843 POINTER (2026-08-07), and it touches two sentences of this block.** The
warm artifact quoted above as **11.9 s** is now **~7.0 s** — BenchMain medians 7.14 /
6.92 s and a real `--serve` ladder at 7.10–7.45 s, against a re-measured cold anchor of
22,971 ms; the cause is **unattributed**, with the (JIT.1) arc the leading hypothesis
precisely because its benefit is a STEADY-STATE one that only a cold instrument ever
measured (round 803's own falsifier read NOISE-DOMINATED after (a)). The confirming warm
A/B of a pre-802 binary against HEAD was **not run**. And the standing fact this block
states — *"the budget in § 0.1 is a COLD single-process budget"* — now has a measured
counterpart: warm, the checker's share of the wall RISES (86.2% → 91.8%) because the
front end warms ~3.8× against the checker's ~2.27×. `docs/perf/warm-jvm-attribution.md`
is the authority for the warm arm from here, and its § 3 is the methodological warning
that every absolute ms in `docs/perf` is `--passTiming`-inflated by ~3 s — 12% of a cold
run, ~50% of a warm one — so only RATIOS travel between the two regimes.

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

- [x] **(JIT.2) DONE round 828 — (JIT.2a) MEASURED: the JDK 25 AOT cache is worth
  **1.638×** on the compiler profile (22,223 → 13,565 ms self, 6/6 wins, per-pair spread
  924 ms against an 8,388 ms median delta) and **2.36×** on a small project, with
  diagnostics byte-identical (54 lines, one md5, 46 errors on all 20 runs).**
  `docs/perf/aot-cache-round828.md` is the write-up. Three findings that change the framing:
  (1) **it is not a START-UP lever** — `wall − self` moves only ~28 ms, the other 8.6 s is
  inside the compile, because JEP 515 hands C2 the recorded profiles at start-up; the
  tiny-trained third arm is the control that proves it (5.9% with the class-loading half
  alone, 39.0% with profiles). (2) **The dump refuses an exploded class directory**, so
  every A/B script in `scripts/` is structurally unable to carry an AOT arm — see the
  CLAUDE.md entry. (3) **The JVM never invalidates a stale cache**: a class deleted from the
  jar still runs from the cache (`NoClassDefFoundError` without it), so invalidation is
  ours to build. **Residue, explicitly not measured:** emit mode, the other 7 profiles, the
  corpus suite under a cache, cross-JDK/cross-machine cache portability, and a cache trained
  under `--workers 4`. The launcher-flag half of this item stays DECLINED. The shipping
  decision is queued below as (JIT.2b).

- [x] **(JIT.2b) DONE round 832 — OWNER-DECIDED 2026-08-05: BUILD THE INVALIDATION, THEN
  SHIP.** The owner approved the packaging work Guardrails would otherwise gate, in the
  shape round 828 recommended: bind the cache to the artifact's checksum, regenerate at
  install, delete on upgrade. **Landed: `scripts/xtsc` (guarded launcher), `scripts/xtsc-aot`
  (`train`/`status`/`manifest`/`clean`), `scripts/xtsc-aot-lib.sh` (the fingerprint and the
  decision), `AotCacheGuardTest` (8 pins).** The contract is FAIL SAFE with no opt-out: a
  `-XX:AOTCache=` is passed only when a recomputed manifest — sha-256 of every classpath
  entry in order, the JDK build, OS/arch, main class, a launcher-version constant, plus the
  cache file's own size and digest — matches the stored one byte for byte; anything else
  runs uncached. Nothing consults an mtime. **Verified:** round 828's stale-jar scenario now
  exits 1 with `NoClassDefFoundError` where the unguarded run exits 0 printing
  `OK — 0 errors`; the pin discriminates under a one-mistake ablation (1 of 8 fails, reading
  `USE …`); `--listAll` diff EMPTY at round 828's own md5; **1.68× survives the guard**
  (3/3 pairs) at a measured ~80 ms check. Round 828's stderr claim is corrected — the JVM
  warnings are on **stdout** and are silenced by `-Xlog:aot*=off:stdout` +
  `-Xlog:aot*=error:stderr` (genuine AOT errors verified to survive). Design, contract and
  residual risks: `docs/perf/aot-cache.md`. ~~**Residue: the corpus suite has never been run
  THROUGH a cached JVM; emit mode, `--serve`, `--workers N` … are untested**~~ — **CLOSED
  round 839 (`docs/perf/aot-cache.md` § 7): emit (78 output files sha256-identical, 1.49×),
  the whole 13,900-test suite through a cached JVM (per-class results diff EMPTY, 922
  classes proven served from the cache), `--workers 2/4/8` (20 cached + 9 uncached runs, one
  md5 for all 29) and `--serve` (halves the FIRST request, worth nothing warm). NO
  cached-vs-uncached divergence anywhere.** **Residue that REMAINS: a cache trained under
  emit (~800 ms, ~5% of a cached emit run) or under `--workers 4`; `--watch`/`--incremental`;
  the other seven profiles; ~~`scripts/xtsc` cannot reach `--serve` at all (its main class is
  `MainKt`, the dispatcher is `server.XtscMainKt`)~~ — **CLOSED round 840 as (AOT.4)(a)**;
  and no distribution exists yet to call `train` from an installer.**

- [x] **(AOT.4)(a) DONE round 840 — the launcher reaches the server/daemon dispatcher.**
  `scripts/xtsc` and `scripts/xtsc-aot` ran `…compiler.MainKt`, so `--serve` and `--daemon`
  were unreachable through the command users are told to run — round 839 had to invoke `java`
  by hand to measure the server arm, and the warm request it measured (~7.3 s against ~13.6 s
  for a cached one-shot) was a path the shipped launcher could not take. Both scripts now name
  `…compiler.server.XtscMainKt`, whose `main` is a strict superset (it dispatches the two
  modes and otherwise delegates to `…compiler.main(args)` verbatim, so a one-shot run gains
  only two class loads). **BOTH had to swap together**: the main class is the `mainclass`
  field of the AOT fingerprint, so a half-swap trains under a fingerprint the launcher never
  looks up and every launch then silently runs uncached — which is what the two new
  `AotCacheGuardTest` pins (now 10) exist to catch. `XTSC_AOT_LAUNCHER_VERSION` deliberately
  NOT bumped (`mainclass` is its own field; the constant is for command-line changes no other
  field records). The fingerprint change is a designed invalidation and is fail-safe: round
  839's shipped cache reads `SKIP no-cache-file`, exit 0, plus the retrain hint. Verified end
  to end through the launcher (`--serve` served two requests in 1,541 / 218 ms; `--daemon`
  output diff-identical to the one-shot run; a retrained 37,457,920-byte cache runs at 487 ms
  with 795 of 795 `com.xemantic` classes served from the shared objects file). Ablated one
  mistake at a time over a copied `scripts/` dir via `XTSC_TEST_LAUNCHER`; only
  `the launcher reaches the server dispatcher` is uniquely discriminating and the other pin
  says so in its KDoc. `scripts/aot-corpus-suite.sh`'s both-arms failure baseline moves
  10 → 12 (the new pins read the classpath LAYOUT). `docs/perf/aot-cache.md` § 8.

- [x] **(AOT.4)(b) DONE round 840(b) — OWNER-APPROVED 2026-08-06; the native image is built
  from the server dispatcher. THE SOURCE CHANGE IS LANDED AND PINNED; THE IMAGE ITSELF HAS
  NEVER BEEN BUILT FROM IT (no GraalVM on this box), so read the "still unverified" list as
  the live part of this entry.** The owner lifted the Guardrails block for exactly this
  property; every other `build.gradle.kts` change stays gated.
  The approval was RELAYED through the orchestrating session rather than witnessed directly
  here; recorded because Guardrails turn on exactly that, and the change is one revertible
  string.
  **THE GAP, MEASURED RATHER THAN ARGUED.** The stale 2026-07-30 binary at `build/native/xtsc`
  (built from `…compiler.MainKt`) was run before the swap:
  `xtsc --serve --socket /tmp/r840b.sock` bound **no socket**, silently took the socket path
  as the PROJECT (`project: /tmp/r840b.sock`, `TS5083 Cannot read file`), compiled 174 files,
  **emitted 173 output files** and exited **0**. `--daemon` behaved identically. So the gap
  was not merely "the mode is unreachable": it is a silent wrong success that also WRITES.
  **WHAT LANDED.** `nativeImageMainClass` → `com.xemantic.typescript.compiler.server.XtscMainKt`
  (a strict superset — `--serve`, `--daemon`, else `com.xemantic.typescript.compiler.main(args)`
  verbatim), plus two comment fixes in the same block: the tracing-agent example now names the
  real entry point, and the "ZERO application reflection" claim is re-justified for the server
  path instead of being left to stand on the old one. `compileTsProject` (a dev `JavaExec`)
  is deliberately still `MainKt`.
  **WHAT WAS READ, NOT GUESSED, ABOUT THE REST OF THE TASK.** `inputs.property("mainClass",
  nativeImageMainClass)` is already declared, so the swap *is* an input change and Gradle will
  rebuild rather than call the stale binary up to date. The `native-image` invocation passes
  only `-cp`, `-o`, `--no-fallback` and `-J-Xmx` — no `-H:` options, no
  `--initialize-at-build-time`, no `--enable-native-access` — so nothing else in the task is
  entry-point-coupled. The reachability metadata is 18 kotlinx-coroutines entries and is
  classpath-picked-up, independent of the main class.
  **THE ONE GraalVM RISK THAT COULD BE SETTLED WITHOUT A BUILD, WAS.** `CompileServer` uses
  kotlinx-serialization, the obvious closed-world hazard. It is not one:
  `javap -c CompileServer.class` shows all four serializer resolutions are the compiler-plugin
  intrinsic (`invokevirtual …$Companion.serializer()`), with **no** reflective
  `SerializersKt.serializer(KType)` anywhere — and serialization was ALREADY reachable from
  the old entry point regardless (`TsConfigLoader`/`TsBuildInfo`/`ModuleResolver` parse JSON),
  so the swap adds two generated serializers of a shape already in the image, not a framework.
  **PIN: +1 in `AotCacheGuardTest` (now 11)** — `the native image is built from the server
  dispatcher`, reading the `val nativeImageMainClass` line of `build.gradle.kts` (anchored on
  that name so `compileTsProject`'s own `MainKt` cannot fool it) and asserting it EQUALS
  `scripts/xtsc`'s `XTSC_MAIN_CLASS`. Agreement, not a hardcoded literal, because the two pins
  then compose: `the launcher reaches the server dispatcher` EXECUTES the launcher and so
  proves that class really dispatches, and this one propagates the property to the image.
  **Ablated on a clean tree after committing** (round 789's rule): reverting the constant to
  `…compiler.MainKt` fails this pin and **only** this pin of the 11.
  `scripts/aot-corpus-suite.sh`'s both-arms baseline moves **12 → 13** (it reads a FILE, so it
  needs Gradle's exploded dir exactly as the other main-class pins do).
  **STILL UNVERIFIED — the honest list, and the reason this is not a closed subject.**
  **RE-ADJUDICATED round 841 against the CI bench row at this very commit
  (`bench-history/runs/20260806T234303Z-e7d933d24a48.md`): three of the five are SETTLED,
  and the ones that are not are the socket ones. The list is annotated in place rather than
  rewritten.**
  ~~(1) `./gradlew nativeImage` has **never been run** against the dispatcher: GraalVM is not
  installed here (`native-image` absent from PATH, `GRAALVM_HOME` unset, no `/opt/graalvm*`),
  so the image may not even BUILD.~~ **SETTLED — IT BUILDS AND RUNS.** `bench.yml` builds the
  image from source on every push (GraalVM CE for JDK 25, `continue-on-error`, so a failure
  publishes `—`), and the row AT `e7d933d2` has a full native arm: **check-only 14.38 s
  (1.06× tsc) / emit 16.08 s (0.99× tsc), 46 errors in both**, matching the JVM arm's count.
  Closed-world analysis therefore accepts `server.XtscMainKt`, and the one-shot path through
  the dispatcher is correct. *This was answerable the same day at zero cost; the round wrote
  the unknown down without reading its own bench row.*
  (2) `--serve`/`--daemon` have **never run on a native image at all**, before or after.
  **STILL OPEN — CI exercises a one-shot compile only.**
  (3) Whether native-image's closed-world analysis needs help with
  `UnixDomainSocketAddress` / `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` — no
  metadata was added for it, because adding config that cannot be tested is worse than naming
  the gap. If the image builds and `--serve` fails at run time, that is the first place to
  look. **STILL OPEN, and now the SHARPEST remaining item: (1) proved the image builds, so a
  socket failure could only be a RUN-time one, exactly where this bullet predicted.**
  ~~(4) Image SIZE and BUILD TIME move by an unmeasured amount and direction.~~ **Effectively
  settled for BUILD TIME — CI's ~2 min budget still covers it, on every push. SIZE is still
  unrecorded (the workflow does not publish it).**
  ~~(5) The 13,350 ms figure quoted for the native image throughout the docs was measured on a
  `MainKt` image and has not been re-taken.~~ **SUPERSEDED round 841 for a bigger reason than
  the entry point: the figure is a retired 4-core box's 3 runs, and CI has published 75 rows
  × 2 modes since 2026-07-30 — native/tsc median `1.02×`. `docs/perf/aot-native-image.md`
  § 0a is the authority.**
  **Whoever next has a GraalVM box should run
  (AOT.4)(a)'s end-to-end verification against the native binary** — `--serve` serving two
  requests, `--daemon` output diff-identical to the one-shot run — and only then treat the
  native thin-client story as real. **That remains exactly right, and is now the WHOLE of
  what is open here.**

- [x] **(AOT.4)(c) — CLOSED round 841 as a queue item; its two substantive halves are
  DONE and its remainder is re-queued as (AOT.5) below.** *Checkbox reconciled round 841
  (2026-08-07): this item stood `- [ ]` while both of the things it was actually queued to
  decide had landed — (3) emit at round 840(c) (SHIPPED, `train` now emits) and (3)'s
  `--workers` half at round 840(d) (MEASURED AND REJECTED, `train` stays sequential,
  pinned). What was left under this checkbox was a list of unmeasured MODES, which is not
  the same work and should not be read as live.* Both write-ups are kept immediately below
  because they are the item's result; the residue is (AOT.5).
  Original framing follows: "the AOT residue that (a) did NOT close", carried forward from
  round 839's list plus what round 840 added — ~~(1) `--serve`/`--daemon` under a cache;
  (2) a cache trained with `--serve`~~ (moved to (AOT.5)); ~~(3) a cache trained under
  **emit**~~ — **DONE round 840(c)**, see below; ~~the `--workers 4` half of (3)~~ —
  **DONE round 840(d): MEASURED AND NOT SHIPPED**, see below; ~~(4) `--watch`/`--incremental`;
  (5) the other seven dashboard profiles; (6) the shipped `~/.cache` cache not retrained~~
  (all moved to (AOT.5)). None of these is a correctness hazard — the contract fails safe.

  **(3) EMIT — DONE round 840(c), `docs/perf/aot-cache.md` § 9. `train` now emits.** Measured
  over 54 whole-project compiles in three batches (paired, within-rep, rotated, box quiet and
  unwatched): an emit-trained cache is **−1,132 ms / −5.4% on an emitting compile, 14/15
  paired runs**, and **a wash on `--noEmit`** (10/15 wins, median −150 ms = −0.8%, per-arm
  medians 16,687 vs 16,738 ms). The whole win is the emit tail — **3,148 → 2,216 ms**, against
  round 839's predicted ~800 ms — so the missing Transformer/Emitter profile was exactly the
  cause it named. Correctness: all 54 runs one diagnostics md5 (46 errors), all 30 emitting
  runs one whole-tree sha256 over 78 files. **The enabling change is a new `--outDir`
  override** (a training run must never write into the user's project); the cache is 54.1 vs
  51.2 MB and training costs +1.5 s. The fingerprint deliberately does NOT record the training
  workload, so existing caches stay valid — merely ~5% slower on emit until retrained.
  Pins: `ProjectOutDirTest` (4), `AotCacheGuardTest.the trainer trains with emit into a
  throwaway directory`. **NOT measured: a project whose emit is a larger share than the tsc
  profile's ~13%, and `--outDir` under `--incremental`/`--watch` (not threaded there).**

  **(3, `--workers` half) — DONE round 840(d), `docs/perf/aot-cache.md` § 10: A NULL, AND A
  NET LOSS. `train` STAYS SEQUENTIAL.** Two caches differing only in training worker count
  (both emitting), 60 `--noEmit --listAll` compiles over the compiler profile in **two
  independent 5-rep rotated batches** at seq/w4/w8. Paired within-rep, the batches agree at
  every level: a `--workers 4`-trained cache is **−112 ms (−0.9%, 8/10) on a w4 compile** —
  inside the band — **+545 ms (+3.5%, 2/10 wins) on the SEQUENTIAL one**, and −732 ms
  (−4.7%, 8/10) on w8, a level nobody should use. The default path loses, so nothing ships;
  `AotCacheGuardTest.the trainer trains sequentially` forbids the one-word edit. **The
  transferable half: round 840(c)'s "train under the mode you run" is a claim about ONE
  DIMENSION AT A TIME, not a law** — emit was free because the emitter profile was *absent*,
  while worker count is the same code under a different thread structure, so the shared
  profile is a trade. Two round-839 § 7.3 readings also re-measured under rotation: **cached
  w4 remains the fastest configuration (1.290×, 10/10)**, but **"w8 under a cache is worse
  than cached sequential" does NOT reproduce** (15,483 vs 15,761 ms, 6/10 — 839's ladder was
  blocked, not interleaved); the surviving statement is that w8 buys *nothing* over
  sequential under a cache where uncached it bought 1.13×. Correctness: all 60 runs 46 errors
  and one md5 (`59d930db…`), i.e. 10 runs per worker level. **NOT measured: `--workers 2/8`
  training, training-run variance (one training run per arm; batch 2 reused both caches), an
  emitting workload under either cache, and the other seven profiles.**

- [ ] **(AOT.5) — the AOT cache's UNMEASURED MODES. LOW PRIORITY; measurement/packaging,
  not a defect.** *(a) and (f) DONE round 842 — see the session note and
  `docs/perf/aot-cache.md` § 12; (b)–(e) remain open and are listed below unchanged.* *Split out of (AOT.4)(c) at round 841 so that a closed item stops
  carrying live work and this list stops being read as urgent.* Nothing here is a
  correctness hazard: the manifest contract fails SAFE (a mismatched fingerprint degrades
  to a normal uncached run), so the worst outcome in every line below is "slower than it
  could be". Ordered by what a user would notice first:
  - (a) ~~**the shipped `~/.cache` cache has not been retrained** since round 840's
    fingerprint change~~ — **DONE round 842**: retrained, self-verifies `USE`, and it
    **pruned 1 stale cache** — the § 2 "delete on upgrade" path observed working on a real
    fingerprint change rather than in a fixture. **AND IT IS NOT A DURABLE STATE, which
    round 842 found the hard way (§ 13.3): `build/libs/*.jar` is NOT byte-reproducible** —
    `./gradlew jvmJar --rerun` with no source change at all yields a different sha256 and a
    different SIZE (5,639,210 -> 5,639,200 B) — so every build, including the session's own
    suite run, invalidates the cache by fingerprint. Fail-safe, never wrong, but **train
    AFTER the last build** and expect to retrain on a dev box constantly.
  - (b) ~~**`--serve`/`--daemon` UNDER a cache, through the launcher**~~ — **DONE round 842,
    § 13.** 32 server requests, all 46 errors / one md5 / **zero in-process fallbacks** (the
    column that matters: `--daemon` compiles in-process when no server answers, so a request
    that never reached the server still prints correct diagnostics). Three rotated server
    pairs, paired: **request 1 is 1.66× (3/3), request 2 is still 1.32× (3/3), requests 3-4
    are a wash.** So round 839's "halves the first request, worth nothing warm" reproduces
    through the shipped launcher AND is refined — the cache shortens the C2 warm-up RAMP by
    about one whole request, which its first/warm-only sampling could not see. The levers
    overlap: a server serving more than two requests gets nothing from the cache.
  - (c) a cache **trained with `--serve`** in the workload, and whether the two extra
    dispatcher class loads shift any timing (argued away by inspection, never measured).
  - (d) **`--watch`/`--incremental`** under a cache — note `--outDir` is deliberately NOT
    threaded through either, so (c)'s training change does not reach them.
  - (e) the **other seven dashboard profiles** (everything measured is the 78-file compiler
    profile), and an **emitting workload under a `--workers`-trained cache**.
  - (f) ~~**training-run VARIANCE** — every result so far uses ONE training run per arm~~
    — **DONE round 842, and it was right to be called out: the draw is worth up to 2.4%
    (+322 ms paired, 10/12) between two caches trained by an IDENTICAL command**, the
    ordering replicates across batches, and cache SIZE does not predict it. 168 compiles,
    five draws, `docs/perf/aot-cache.md` § 12. It overturns no DECISION — § 9's shipped
    emit win (−5.4%) is >3× the term and § 10's rejected `--workers 4` arm was a null on
    the path it was supposed to help — but it costs § 10's **+3.5%** its precision, and it
    strikes § 10.3's "+82 KB" supporting argument outright (five identical-command draws
    span 208 KB). **New standing rule: an A/B on a TRAINING configuration needs ≥2
    independently trained caches per arm — two batches of runs replicate the measurement
    draw and cannot touch the training one.**

- [x] **(JIT.2-ORIGINAL, superseded by the two entries above — kept for the record, NOT
  live work) OWNER-DECIDED 2026-08-04: NO LAUNCHER FLAG; the APPROVED work was a round
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
  **ROUND-841 CORRECTION to the GraalVM figure in that list: `13.4 s` is the retired
  4-core box's 3-run number. CI has measured the image on every push since 2026-07-30 —
  75 rows × 2 modes, native/tsc median `1.02×` (check-only 11.20 s, emit 13.43 s). The
  artifact-scoped framing this bullet states is not just true, it is stronger than it
  reads: the native artifact is at tsc PARITY. `docs/perf/aot-native-image.md` § 0a.**
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

- [x] **(ENGINE.3) CLOSED round 830 — AND IT CLOSES AS A *NEGATIVE RECOMMENDATION*.
  THE ITEM BODY WAS STALE: sites 2 and 3 were measured at rounds 755 and 786, and a
  FOURTH site (property access, the one holding the mass) at round 787. What no round
  had produced is the AGGREGATE, and round 830 took it WITHIN ONE ROUND ON ONE BINARY
  (13 runs, rotated, 3 reps/arm) because the published total was assembled across three
  binaries and CLAUDE.md forbids comparing absolute ms across rounds.**
  **FOUR-SITE DEDICATED-WALKER LAYER = 757 ms = 3.13% of a 24,205 ms check-only compile**
  (bracket 703–924 ms = 2.90–3.82%): site 1 **286 ms / 37.6%**, site 2 **168 / 27.2%**,
  site 3 **94 / 23.5%**, site 4 **209 / 20.1%**. The three published sites REPLICATE to
  about a point. **Site 4 moved 8.0% -> 20.1% with its firewall UNCHANGED (209 vs 207 ms)
  — the ENGINE fell 2,364 -> 830 ms because (ENGINE.2b/2d/2e/2f) landed; every ms taken
  out of the engine raises the layer's SHARE without changing what deleting it buys.**
  **Plainly deletable, all four sites: 22 ms = 0.09%**; E4 holds at ALL FOUR (the largest
  group at every site is a rule tsc also implements, so it MOVES). E1 holds at only 2 of 4
  (biased high), E2 fails again (19.1x / 6.2x / 3.8x), E3 holds at every netting.
  **VERDICT: do NOT put § 0.1's scope question to the owner — see (SCOPE.1) below.**
  What did NOT work: **three of the four ON-vs-COARSE differentials did not resolve**
  (level B NEGATIVE, Δ −64 ms against a 124 ms spread; level C Δ = +1 ms; level E
  Δ/spread 0.80x; only level Q usable at 308 ns, 2.15x) — the escape was to carry the
  boundary as a SENSITIVITY PARAMETER and show the verdict is identical across it; and a
  first aggregate taken from RAW rows read 3.82% and would have failed E3, because level
  Q's nine rows carry identical close counts so the untaxed reading inflates each small
  firewall row by ~20.6 ms. **No `src/` change.** `docs/perf/engine-rule-price.md` §§ 9-11.
  ORIGINAL: **(ENGINE.3) Finish round 739's engine-rule price at the TWO remaining
  assignability sites — this is § 0.1's OWN precondition, in its own words: "do not put
  the scope question to the owner until they are in".** PRIZE: not a saving — a DECISION INPUT.
  Measured at site 1 of 3 (`docs/perf/engine-rule-price.md`): engine 483 ms (55.4%) vs
  dedicated-walker layer 326 ms (37.4%) = **0.67×, not the 14× the arc nearly quoted**,
  and **165 of the 326 is the weak-type rule, which MOVES into any replacement engine
  rather than vanishing** → 0.6–1.2% for that site. Scored predictions for the other two
  are already written down in that doc. **BLAST RADIUS: none — it is measurement only.**
  **FALSIFIER, and the reason to do it before (SCOPE.1): if sites 2 and 3 come in at the
  same order, the whole § 0.1 endgame is worth ~2–4%, which is LESS than (JIT.1) already
  measured, and the scope question should NOT be put to the owner at all.**

- [x] **(SCOPE.1) CLOSED UNRAISED (round 830) — DO NOT RE-RAISE THIS WITH THE OWNER.**
  (ENGINE.3) was its gate and its gate answered NO. Measured on one binary in one round,
  the dedicated-walker layer on the four largest checking sites in the compiler is
  **757 ms = 3.13%** of a check-only compile (bracket 2.90–3.82%), of which **~22 ms
  (0.09%) is plainly deletable** — the largest group at *every one of the four sites* is a
  rule tsc also implements and would MOVE into the replacement engine rather than vanish.
  Against a **±2.0% cold A/B drift band** the entire prize is one to two noise bands and
  the deletable part is 1/20th of one; **(JIT.1), already landed, measured −3.93% (5/5)
  from splitting `forEachChild` alone**, i.e. one mechanical method split beat the upper
  bound of the whole endgame with no scope trade at all. The change would trade the
  property that made a byte-identical 13,816-test corpus reachable (every broad engine
  attempt in this codebase regressed — global variance analysis alone cost ~263, round
  336). **A future agent proposing this must first read `docs/perf/engine-rule-price.md`
  §§ 9-11 and produce a NEW measurement that contradicts it; the "1,046 walkers" figure is
  a line count, not a price, and the measured shares FALL as the site gets bigger
  (37.6 / 27.2 / 23.5 / 20.1%).** Residue stated explicitly: the layer in the other ~1,040
  functions is unmeasured — but the other large dedicated-walker population, the ~400 tail
  passes, was measured FLAT (2,962 ms, largest 75 ms = 0.26%) and NOT removable
  (round 620: 3 of 23 census-silent passes deletable; round 659's migration A/B +0.24%).
  ORIGINAL: **BLOCKED-PENDING-USER (SCOPE.1): the § 0.1 endgame proper — replace the ~1,046
  dedicated `check*`/`emit*`/`tryEmit*` walkers with general engine rules.** Guardrail —
  § 0.1 states plainly that this "is a SCOPE decision, not a perf task, and it trades the
  property that made the corpus reachable" (narrow verifiable walkers are what got the
  corpus to 100%; every broad engine attempt in this codebase regressed).

---

- [x] **(SETUP.2) DONE round 829 — CENSUSED AND CLOSED ON PRICE. The pass makes 8.5
  `getTypeOfSymbol` calls per map entry anybody reads, and the recoverable slice is
  under 1% of the compile.** `--fltmCensus` (new, behaviour-free when off) measured the
  funnel on the compiler profile: **12,738 direct resolves -> 4,161 entries stored
  (8,577 = 67.3% resolve to `any`/`errorType` and store nothing) -> 1,499 ever read**,
  with the single read site at **calls=16,043 / distinct=1,499 (10.7x)** and **278,355
  misses (94.6%)**. Round 788's law measured both ways: **47.4%** of the never-read
  entries have their symbol asked again anyway (MOVE), and over the whole population
  **6,009 of 12,738 = 47.1% are never read AND never asked again** = **235–304 ms,
  38–40% of the row**. So this is NOT round 801's "created 1143, materialized 1143" —
  about half really evaporates. **It closes on PRICE:** per branch the deferrable part is
  `decl` alone, **199–252 ms = 0.8–1.0% of a ~25 s compile — below the ±2.0% cold A/B
  band and at the floor of the ±1.0% warm one**, against name resolution program-wide
  plus a WHICH-pass-resolves-first program-order dependency (rounds 754/776/778).
  **And the biggest-looking slice is a trap: the `typealias` branch is 99.7% "never
  needed" by the map criterion and is the TS2589/TS2615 depth-bail DETECTOR** — the bail
  is observable only while `getTypeOfSymbol` runs, so skipping it deletes a diagnostic
  rather than deferring one. **What did not work: the first census keyed the deletable
  population on entries that were STORED and was wrong by 4× (1,399 / 49–59 ms), because
  the 67.3% that store nothing were invisible to it.** Residue: the ~200–250 ms `decl`
  slice is left unclaimed on price, not on possibility.
  Suite 13,812 -> **13,816 / 0 / 3** (+4 `FltmCensusTest` pins).
  `docs/perf/setup-phase-and-huge-methods.md` § 27.

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

- [x] **(PERF.HW.a) CLOSED ROUND 825 ON 48 RUNS — the race was a SINGLETON ID-SPACE
  COLLISION: every worker received the SAME id base, so `TypeKt`'s static initializer
  minted the 19 shared intrinsics from whichever worker won the class-init race, AFTER
  that worker had rebased — measured `anyType=1000000005` INSIDE three of four workers'
  ranges. Fixed with a DISJOINT per-worker slice (`base + i*stride`), which makes any
  lazily-initialized shared singleton harmless, plus `forceIntrinsicTypeInit()` on the
  caller thread. 48 post-fix runs, one md5, identical to sequential; suite 13,808/0/3.
  Round 824's framing follows.** RE-OPENED ROUND 824 ON EVIDENCE — `--workers N` IS A RACE, AND ROUND
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
  **ROUND 826 CORRECTS THE TWO SENTENCES ABOVE, on the ladder re-taken against the
  FIXED binary (`docs/perf/worker-scaling-round826.md`, n=6 rotated, 24/24 runs at 46
  errors, one md5).** (a) closed at round 825, so nothing blocks (M2) any more — and
  **the race was DEFLATING the parallel arms**, so round 824's figures were
  conservative: w2 1.305x / **w4 1.361x** / w8 1.242x, 6/6 sign-consistent wins per
  level (paired deltas w4 −23.84% → **−27.10%**). **But "step (b) is worth ~1.5x" is
  WRONG and is retracted.** The ~1.55x is the seq/w4 Amdahl *asymptote*, i.e. what
  infinite workers would give if the per-worker duplication were free; **w4 at 1.361x
  is already the measured optimum and w8 REGRESSES to 1.242x**, so the delta step (b)
  can address is 1.361x → **at most 1.546x** (unreachable, N→∞) and **realistically
  ~1.448x** — ≈1.0–1.6 s of a 23.2 s compile, 4–7%. The structural reason: the
  per-worker re-bind and the ~318 collectors run CONCURRENTLY, so they cost CPU
  (+27.6% at w8) and RSS (2,234 vs 808 MB) but only ~1x WALL, and cores never saturate
  (6.63 of 8). **Recommendation: do NOT attempt (b) for wall time** — re-queue it as a
  MEMORY item if parallel mode is ever to become the default. Also retracted from the
  block above: "the seq/w2 and seq/w4 fits agree, the model is coherent through w4" —
  the re-take gives 46.7% vs 35.3%, and the fitted divisible share falls monotonically
  with N, which is an N-growing overhead no 2-parameter fit can express at any level.

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

- [x] **INV.6 Parallelism — Phase 0 CLOSED round 609** (6a-6d1: --workers 2 = −17% wall, output sorted-identical, all-8-profile partition equivalence; w4 flat at the per-worker redundancy ceiling — Phase 1 shared frozen collectors is the reopener, gated on an immutability audit; (6e) parallel emit deferred: emit workers would race the shared checker's lazy caches, and ~~benches are --noEmit~~ *[round 841: the benches have run BOTH modes since 2026-07-30; the deferral now rests on the race plus a ~1.6 s ceiling — see (6e)]*). Share-nothing checker workers per
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
    foundation; ~~no dashboard delta expected — benches are --noEmit~~).
    **RE-COSTED round 841 — the stale half of that rationale and the priced
    half.** The premise is gone: the bench has published an **emit arm on every
    row since (BENCH.1)/round 739** (CI since 2026-07-30, commit `a1ff6033`),
    and the latest row measures emit at **2.30× tsc** (75-row median 2.25×), so
    a dashboard delta IS now observable in principle. The *price*, however, is
    what actually keeps this low: round 840(c) measured the emit tail on the
    78-file compiler profile at **2,139 ms uncached / 2,216 ms cached** of a
    ~30 s emitting compile, so **perfect 4-way emit parallelism is worth
    ~1.6 s of emit mode and exactly 0% of check-only** — the mode the whole
    perf arc profiles, and the mode `ab-interleaved.sh` / `cost_gate.py` run.
    Against that, the hazard INV.6's own header names is unchanged and is not
    endorsed here: **emit workers would race the shared checker's lazy caches**
    (`getTypeOfSymbol`'s `symbolTypes`, the interning tables — the same class of
    problem as the round-825 `--workers` id-space race, which was silent in
    every output). So: rationale corrected, priority unchanged, and the reason
    for the priority is now the ~1.6 s ceiling rather than a bench that no
    longer works the way the note said.
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
    measured 21.8 s (round 772) and 20.0 s (round 823). *(Round 841: that
    GraalVM number is itself now superseded as an authority — CI has measured
    the image on every push since 2026-07-30, median 11.20 s check-only /
    13.43 s emit, `1.02×` tsc. `aot-native-image.md` § 0a.)* Verdict unchanged:
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
is not cancelled, and it holds ~~the only known SILENT-WRONG-ANSWER defect in the
codebase (M2.4: with `"lib": ["dom"]` a browser project's DOM code compiles
CLEAN and entirely unchecked) plus~~ the "real project" gaps (declaration emit,
sourcemaps, JSX, nodenext). **The trade being made is explicit: matching tsc's
speed is being prioritised over making the compiler usable on non-tsc projects.**
Revisit when the perf arc reaches its staged target or stalls.

**CORRECTED round 841 (2026-08-07) — THE STRUCK CLAUSE HAS BEEN FALSE SINCE ROUND
731, i.e. for 110 rounds, AND IT IS ONE OF THE STANDING ARGUMENTS FOR UNPARKING
THIS SECTION, SO IT MATTERED.** The M2.4 defect was closed by **(LIB.1)(a) at
round 730 and (LIB.1)(b) at round 731** — the queue item in the PERF section
above reads `- [x] **(LIB.1) — DONE. (a) round 730, (b) round 731, (c) round
756.**`. The struck text was TRUE when written (round 716) and went stale 14
rounds later; nothing re-read it, which is the same queue-hygiene failure mode
this section's own closing parenthesis already records about itself. Verified at
HEAD by
reading the artifacts, not the notes: `CompilerOptions.kt:644` is
`internal fun projectDefaults(): CompilerOptions = CompilerOptions(useRealLibs = true)`,
so both project entry points start with the real libs (the CONSTRUCTOR default
stays `false`, which is what keeps the corpus on the embedded lib); and
`generateRealLibSources` embeds **every** `src/lib/*.d.ts` at the pinned commit —
`dom.generated`, `dom.iterable.generated`, `dom.asynciterable.generated` are all
present in `libNames`. So `"lib": ["dom"]` now resolves to real declarations, and
(LIB.1)(c) additionally made a lib name that resolves to *nothing* raise TS6046
instead of silently checking against no lib at all. **This section no longer
contains any known silent-wrong-answer defect** — its remaining content is the
"real project" feature gaps listed above, which is a materially weaker reason to
unpark it. Whoever next weighs unparking should weigh it on those gaps alone.
*(M2.4's own line further down already reads `SUPERSEDED round 716 by (LIB.1)`;
only this header went unreconciled.)*

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
  **ROUND-831 RE-MEASUREMENT — THE TABLE IS UNCHANGED AFTER 135 ROUNDS, SO STOP
  RE-MEASURING IT AND TREAT IT AS CURRENT.** All nine remaining categories were adopted
  at once (+199 tests → 14,015 / **87** failed / 3 skipped; **0 of the pre-existing 13,816
  regressed**, verified by mapping every failure name back to its source corpus), then
  reverted. Per-category failing SUBTESTS: `asOperator` **4** · `any` **6** ·
  `conditional` **8** · `nonPrimitive` **9** · `labeledStatements` **9** ·
  `typeAliases` **9** · `contextualTyping` **9** · `typeSatisfaction` **12** ·
  `optionalChaining` **21**. **Every number is identical to round 695's except
  `asOperator` 5 → 4** — i.e. rounds 696-830 (the perf arc) moved exactly one conformance
  subtest, which is the expected result of a perf arc and is also why a future round
  should NOT spend a suite run re-measuring this.
  **WHAT ROUND 831 ADDS THAT 695 DID NOT RECORD, AND IT IS THE PART THAT DECIDES
  TRACTABILITY: the failing-CASE count and the EMIT-vs-ERRORS split.** 87 subtests come
  from **74 distinct cases**, and `conformanceDeferredErrorBaselines` defers only
  `.errors.txt`, so a case with a failing **JS-emit** subtest blocks its category outright.
  Format is `category: failing cases / total files (cases with an emit failure)` —
  `asOperator` 4/9 (**1**) · `any` 6/9 (**0**) · `conditional` 7/10 (**2**) ·
  `labeledStatements` 5/8 (**4**) · `nonPrimitive` 9/16 (**0**) · `typeAliases` 9/15
  (**0**) · `contextualTyping` 9/17 (**0**) · `typeSatisfaction` 10/16 (**0**) ·
  `optionalChaining` 15/25 (**12**).
  **CONSEQUENCE — the two obvious routes are both closed, which is why round 831 adopted
  nothing.** (1) **No category is green**, so there is no "narrow the adoption to what
  passes" increment: the floor is `asOperator` at 4 failing cases. (2) **Five categories
  (`any`, `nonPrimitive`, `typeAliases`, `contextualTyping`, `typeSatisfaction` — 43
  cases) have ZERO emit failures**, so they are deferrable in principle — but deferring
  43 cases is exactly the mass-parking this item's own rule forbids ("not a place to park
  a fresh failure — triage first, queue it, then add"; the live deferred set holds **2**
  entries, each with a triaged paragraph). A category lands by FIXING its gaps.
  **ROUND-833 UPDATE — `satisfies` CHECKING LANDED; `typeSatisfaction` 12 -> 11 SUBTESTS
  (`typeSatisfactionWithDefaultExport` GREEN), STILL NOT ADOPTABLE, AND THE CATEGORY IS
  RE-COSTED UPWARD.** The general `satisfies` check (TS1360 + object-literal
  excess) is in. The 11 residual `typeSatisfaction` subtests are **three OTHER gaps in a
  `satisfies` costume**, so the 0-emit-failure count overstated tractability: **(i) 4
  subtests need `Record`/`Partial`, which the EMBEDDED lib does not declare at all**
  (`propertyNameFulfillment`, `propNameConstraining`, `propertyValueConformance3`,
  `contextualTyping2` — the target degrades to `any` and every check correctly declines;
  a LIB gap, not a checker gap); **(ii) 5 need property-VALUE conformance against an index
  signature** (`propertyValueConformance1`/`2`, two config variants each); **(iii) 2 need
  contextual typing to flow THROUGH the operator** (`typeSatisfaction` line 15's FP and
  `vacuousIntersection`'s display), the second additionally wanting an OBJECT-LITERAL arm
  in `literalTypeOfExpression` — a broad change touching every var-decl/return/arg
  literal-preservation site, designed this round and deliberately NOT landed.
  `errorLocations1` is deeper still. **ALSO RE-COSTED: round 831's "cheapest target"
  reading of `asOperator` is misleading** — it can never go green on TS2352 work alone,
  because `asOperatorASI` is a parser/ASI EMIT failure and one emit failure blocks a
  category outright; the general TS2352 rule buys exactly ONE subtest for an M3.1-depth
  change in the repo's most crowded walker family. **The per-subtest missing-code histogram
  over the 62 error-subtests (round 833, `xml.etree` over the nine-category arm):**
  TS2322 x59 · TS2345 x55 · TS2344 x18 · TS7031 x16 · TS2790 x16 · TS2339 x12 · TS2353 x10 ·
  TS2456 x8 · TS18047 x7 · TS1360 x6; over-emitted: TS2322 x33 · TS2352 x8 · TS1005 x8 ·
  TS2304 x6 · TS1123 x6 · TS2403 x4. **The `nonPrimitive` family (9 cases, 0 emit) is the
  best-shaped remaining target: `object` is a `Type.Intrinsic` with exactly ONE relation
  rule** (`isSimpleTypeRelatedTo`'s NonPrimitive target leg) **and no others anywhere** —
  a bare type parameter is wrongly assignable to it, `object`-as-source is undecided, and
  property access on it is unchecked; exposure is bounded to code that writes the `object`
  keyword.
  **ROUND-834 UPDATE — THE `object` RELATION RULE LANDED; `nonPrimitive` 9 -> 7 FAILING
  CASES (`nonPrimitiveInFunction` + `nonPrimitiveInGeneric` GREEN), STILL NOT ADOPTABLE,
  AND THE CATEGORY IS RE-COSTED UPWARD — THE SAME SHAPE AS 833's `typeSatisfaction`
  RESULT.** The one rule was tsc's rule written as its NEGATION: `!hasAny(Primitive|Null|
  Undefined|Void)` versus tsc's positive `s & Object && t & NonPrimitive`, and they differ
  because **`TypeFlags.Primitive` omits every LITERAL bit** — literal types, a bare
  `Type.TypeParam`, `unknown` and the instantiable types all satisfied `object` silently,
  which even produced an FP one type away from any `object` (a conditional
  `T[P] extends V | object` answering TRUE for a numeric literal). Landed: the inverted
  leg, a `TypeParam`-source-by-CONSTRAINT arm, a `canUseTypeEngine` opening for a
  `TypeParam` source, and `isArgCheckableType` at two argument gates — all keyed on the
  target's `NonPrimitive` flag. **The 7 residual cases need SIX more sub-steps, and TWO OF
  THEM ARE NOT `object` WORK:** `nonPrimitiveConstraintOfIndexAccessType` (10 diffs, we
  emit nothing) is pure `string -> T[P]` INDEXED-ACCESS assignability;
  `nonPrimitiveAndTypeVariables` (1) is blocked by the GENERAL `TypeParam -> TypeParam`
  lenience plus a union display-ORDER divergence (tsc sorts by type id, we keep source
  order). The rest are `object`-keyed but each its own sub-step: `nonPrimitiveNarrow` (3)
  and `nonPrimitiveStrictNull` (12) want flow narrowing on `object` (`typeof x ===
  'number'` to `never`, plus 8 TS1804x nullish-access); `nonPrimitiveAccessProperty` (2)
  wants TS2339 on an `object` receiver, inside the repo's most FP-sensitive walker;
  `nonPrimitiveUnionIntersection` (4) wants `getIntersectionType` REDUCTION (`object &
  string` -> `never`, `object & {}` -> `object`) plus excess-property checking through such
  an intersection — bounded but it moves intersection identity and display;
  **`nonPrimitiveAssignError` (1) is the only cheap one left**, a message shape (TS2741
  with the source displayed as its apparent `{}` + TS2728, where we print TS2322).
  **ROUND-836 UPDATE — THE WORKLIST IS NOW PRICED PER CASE, INCLUDING EMIT, IN
  `docs/conformance-worklist.md`; STOP PRICING CATEGORIES BY THEIR FAILURE COUNT.** Round 836
  measured all nine categories case by case, off the suite: `git archive` the category out of
  the blobless clone, replicate the generator's own subtest algebra in a throwaway `main`, and
  diff both the `.errors.txt` SUMMARY and each `//// [name.js]` section. 40 seconds for 124
  cases against ~12 minutes for a suite arm, and it yields the per-case diff a suite arm does
  not. **The headline: 124 cases, 79 error subtests, 65 with a diagnostic diff, 12 with a
  JS-emit diff — and the emit column is the thing that decides adoptability.** Four categories
  (`asOperator`, `optionalChaining`, `conditional`, `labeledStatements`) carry an emit-red case
  and are vetoed outright no matter what the checker learns. By MECHANISM rather than category:
  ~20 subtests are M3.1/M3.2 inference-and-relation, ~7 are parser/ASI/error-recovery, 12 are
  JS emit, 4 are EMBEDDED-LIB gaps (`Record`/`Partial`), ~9 are flow narrowing, and ~12 are
  bounded single-purpose checks — the last bucket being the only one where a round buys whole
  cases. **`types/any` is now the best-shaped target and `types/typeAliases` the flattest:**
  `any` is six small cases with no emit veto, three of which are ONE mechanism (narrowing FROM
  `any` through `instanceof` / a type predicate, observable only as a `did you mean` TS2551);
  `typeAliases` is nine cases of 2-7 diffs each with no emit veto, but spread over six
  unrelated mechanisms, of which `typeAliasesForObjectTypes` (TS2300) + `typeAliasesDoNotMerge`
  (TS2395) are one pair (a type alias participating in the duplicate/merged-declaration checks).
  **WHAT ROUND 836 LANDED: tsc's OPTIONAL-CHAIN leg of `checkReferenceExpression`** — TS2777 /
  TS2778 / TS2779 / TS2780 / TS2781, none of which existed. `propertyAccessChain.3` and
  `elementAccessChain.3` (22 diagnostics each) now match their baselines exactly, order
  included; both cases remain blocked by their own `?.`-downlevel EMIT subtest, which is the
  survey's whole point — the errors half of a case can go green while the case does not.
  **ROUND-835 UPDATE — `nonPrimitiveAssignError` IS GREEN; `nonPrimitive` 7 -> 6, AND THE
  CHEAP END OF THE CATEGORY IS NOW EXHAUSTED.** The cause was not a message-formatting
  choice: tsc has NO `object`-specific elaboration, it compares
  `getApparentType(source)` = `emptyObjectType`, so `propertiesRelatedTo` runs against an
  empty member table and reports the missing property with the source shown as `{}`. Ours
  kept `object` as a `Type.Intrinsic` and `collectMissingProperties` requires a
  `Type.Object` on both sides, so the missing set was empty BY CONSTRUCTION. Landed as one
  source-flag-keyed predicate (`nonPrimitiveMissingPropSource`) at the var-decl and
  assignment elaborations only — deliberately NOT inside `getApparentType`, whose ~40
  consumers ask a different question. The relation VERDICT is unchanged, so the change can
  neither add nor remove a diagnostic; all 8 profiles are `added=0 removed=0` md5-for-md5.
  **THE STANDING WORKLIST IS NOW 6 CASES AND NONE OF THEM IS A MESSAGE SHAPE**, in the
  round-835 judgement of cheapest-first: (1) `nonPrimitiveAccessProperty` (2 diffs) — TS2339
  on an `object` receiver; its `var { destructuring } = a` half wants the SAME apparent-type
  substitution round 835 introduced (tsc prints `'{}'` at the destructure and `'object'` at
  the property access, which is not an inconsistency but two different report sites), while
  the property-access half is a `checkMemberAccessMissing` change with real exposure — 21
  `: object` annotations in the compiler profile alone, incl. `lookupTable: object` and
  `readJson(): object`. (2) `nonPrimitiveUnionIntersection` (4) — intersection REDUCTION.
  (3) `nonPrimitiveNarrow` (3) + `nonPrimitiveStrictNull` (12) — flow narrowing on `object`.
  (4) `nonPrimitiveConstraintOfIndexAccessType` (10) — generic indexed-access assignability,
  i.e. tightening exactly what the foreign-TP gate exists to keep loose. (5)
  `nonPrimitiveAndTypeVariables` (1) — `TypeParam -> TypeParam` lenience PLUS a union
  display-order divergence, so a correct relation fix alone still leaves it red.
  **THE GENERAL LESSON NOW HOLDS FOR TWO CATEGORIES IN A ROW: the 0-emit-failure count
  overstates tractability, because a category's name describes its FIXTURES, not its gaps
  — price a category by its per-CASE diffs before committing a round to it.**
  **ROUND 831's ORIGINAL PRICING OF `asOperator` (4 cases), kept for the record:** `asOperator2` (`23 as string`) and `asOperatorContextualType`
  (`(v => v) as (x: number) => string`) both need a **general TS2352 comparability rule**,
  and today TS2352 is a family of ~10 dedicated narrow walkers (`emitTS2352IfNullCast`,
  `IfSameTargetMismatch`, `IfFunctionReturnMismatch`, `IfArrayToClassMismatch`,
  `IfTypeParamStrictSubtypeCast`, `IfEmptyObjectCastToTypeParam`, …) with **no general
  rule at all** — an M3.1-depth relation change wanting the corpus plus `--listAll` ×8, not
  a surgical fix. `asOperatorAmbiguity` needs TS2339 through `y[0].m` where `y: A<B>[]`
  (element access + generic member resolution), and `asOperatorASI` is a **parser/ASI**
  gap: tsc restarts the statement at a newline-leading `as`, emitting
  `var x = 10;` / ``as `Hello world`;`` where we swallow the `as` into an assertion.
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
