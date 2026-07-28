# (FRONT.1) — the first attribution of the front end, and the 9% nobody was looking at

*Round 738, part 2. Seventh in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → `call-expression-attribution.md` (734) →
`argument-check-attribution.md` (735) → `narrow-walk-attribution.md` (736) →
`type-of-expression-attribution.md` (737) → `var-decl-attribution.md` (738 part
1) → here. Derived by instrumentation (`FrontEnd`, opt-in `--frontEnd`),
verified by the whole corpus suite, a byte-identical profile `--listAll`, a cost
gate, and an interleaved A/B.*

> **HEADLINE — § 0.1's stage 5 said "the front end, ~20%, unprofiled". The front
> end is 11.0%, and the OTHER 9.2% of that unmeasured region was
> `Transformer.transform` + `Emitter.emit` producing JavaScript that a
> `--noEmit` build immediately threw away.** `noEmit` was consulted only where
> outputs are WRITTEN, so the compile core transformed and emitted all 78
> program files regardless: **2,623 ms of a 31,235 ms compile (8.4%)**.
> Gating it produced the arc's largest landed win — **−11.42% median, B wins
> 6/6, per-pair range [−4,099, −2,773] ms** — with `--listAll` byte-identical
> and 18 of 20 cost counters unmoved (two FELL by 9% and were rebaselined:
> the Transformer's own `globals` queries).
>
> **This is a SCOPE correction, not an algorithmic speed-up, and it must be
> reported as one.** Real `tsc --noEmit` does not run its emitter either.
> ~~So every published xtsc-vs-tsc `--no-emit` ratio before this compared our
> check+emit against tsc's check-only. The 2.4× gap was measured against a
> compile doing ~9% of work the baseline was not doing; the honest
> single-thread figure is ~2.15×.~~ **RETRACTED, round 739 — there was no
> published `--no-emit` ratio.** `bench-3way.sh` ran xtsc, tsc AND tsgo with
> emit, so the 2.4× was already like-for-like and this gate (which fires only
> under `--noEmit`) does not move it. The check-only ratio has never been
> measured on either side; see `docs/ARCHITECTURE-RETHINK.md` § 0.2 for what
> each script runs, the measured 8.5% emit share, and the resulting bound.
>
> **And the front end proper has no lever in it.** 11.0% splits as crawl 5.4%
> (which already contains ALL reading, decoding and parsing of 9,977,097
> characters), bind 5.2%, config 0.3%, `extractRelativeImports` 0.05%, and the
> core's own parse loop **0 ms — 78 of 78 pre-parses are reused**. There is no
> 20% here to attack.

---

## 1. What was built

`FrontEnd` in `src/commonMain/kotlin/SpineDispatch.kt` plus spans in
`ProjectCompiler` (config, crawl, per-file read/pre-parse) and
`TypeScriptCompiler` (core parse, imports, bind, check, post, transform, emit).

The phases are per-FILE, not per-node — 78 files against an 89 ns timestamp read
— so no ON-vs-COARSE calibration counterpart is needed and none was built. Two
things did need care:

* **The crawl is concurrent.** `readAndScanBatch` reads on the IO dispatcher and
  parses on `Dispatchers.Default` with `FRONTEND_CONCURRENCY = 16` in flight, so
  a `+=` from the worker lambdas would race exactly as
  `PassTiming.nodeKindHistogram` does (round 717 measured that race at −0.33%).
  Instead each flow element carries its own read and parse nanos back on its
  `CrawledFile`, and the **single-threaded** collector sums them after
  `toList()`. Race-free and exact.
* **Those per-file sums are ELAPSED, not CPU.** The spans bracket a
  `withContext(...)`, which suspends, so a file's "parse" time includes waiting
  for a dispatcher slot. That is why the pre-parse sum (17,958 ms) is 10.7× the
  crawl's WALL (1,683 ms): the ratio is the effective in-flight concurrency, not
  a cost. **Only the crawl WALL is a wall-clock price**; the two sub-sums are
  labelled as sums and must not be added into any total. The report excludes
  them from its own total for this reason.

Round 623's warning applies in the other direction and is why this was worth
doing at all: a JFR self-% is not a wall-clock price (`computeLineStarts` showed
5.3% of samples and eliminating it measured −0.3%). Every row here is a wall
span around a named phase.

## 2. The map — compiler profile, before the fix

31,235 ms wall; 31,174 ms accounted for by the phases.

| phase | ms | share | calls |
|---|---:|---:|---:|
| config load + `@types` + root glob | 102 | 0.3% | 1 |
| **import-graph crawl (WALL)** | **1,683** | **5.4%** | 1 |
| — read + decode (elapsed sum, see § 1) | *988* | | 78 |
| — pre-parse (elapsed sum, see § 1) | *17,958* | | 78 |
| core parse loop (FRESH parses only) | **0** | **0%** | 0 |
| `extractRelativeImports` (×2 per file) | 17 | 0.05% | 78 |
| bind (all program files) | 1,622 | 5.2% | 1 |
| checker construct + `getDiagnostics` | 24,872 | 79.7% | 1 |
| **post-checker (transform/emit/tails)** | **2,876** | **9.2%** | 1 |
| — `Transformer.transform` | **2,211** | 7.1% | 78 |
| — `Emitter.emit` | **412** | 1.3% | 78 |
| **FRONT END** (config + crawl + parse + imports + bind) | **3,425** | **11.0%** | |

**Three readings.**

1. **The front end is 11.0%, not 20%.** § 0.1's budget line ("checker-init is 80
   of them; the front end … is the other 20") was an inference from "the checker
   dominates every JFR", and half of the residual turned out not to be front-end
   work at all.
2. **The parse is already free.** 78 of 78 files are parsed once, during the
   crawl, concurrently, and the compile core reuses every one of them
   (INV.1(e)). The core's own parse loop measured **0 ms**. Reading, decoding and
   parsing 9,977,097 characters costs **1,683 ms of wall in total**, and that
   figure already includes the module resolution the crawl does between
   frontiers.
3. **Bind is 5.2% and is the only front-end row that is neither trivial nor
   already parallel.** It is a single sequential pass over 78 files, so it is
   also the term that would have to shrink before parallel checking pays (M2
   measured 77% of a worker's time as per-worker duplication, and a fresh bind is
   most of it).

## 3. The finding — a `--noEmit` build was emitting

`options.noEmit` reached exactly one place: `ProjectCompiler.build`'s
`val written = if (noEmit || config.options.noEmit) emptyList() else
writeOutputs(...)`. The compile core never saw it, so Phase 3 —
`Transformer.transform` then `Emitter.emit`, per program file — ran in full and
its output was dropped one frame up.

**Verified before changing anything:** the Phase-3 loop contributes **no
diagnostics** (there is no `diagnostics.add` or `diagnostics.remove` anywhere
between the checker and the `CompilationResult`); it only fills `jsOutputMap`.
So skipping it is diagnostic-neutral by construction, not by luck.

### The gate, and why it is NOT `options.noEmit`

`noEmit` is also a corpus DIRECTIVE, and **440 tests set `@noEmit: true`**. Their
baselines were produced by a core that still emits, so gating on `options.noEmit`
would change 440 behaviours in one step. The change therefore adds a separate
`CompilerOptions.skipEmitOutputs`, set **only** by `ProjectCompiler` from its own
`noEmit` parameter — i.e. only by `xtsc --noEmit`, the type-check-only CI mode.
No existing caller's behaviour moves, by construction rather than by testing.
`SkipEmitOutputsTest`'s fourth test is the negative control: a directive-driven
`noEmit` must keep emitting, and it fails if someone later "simplifies" the gate.

## 4. The measurement

`scripts/ab-interleaved.sh /tmp/xtsc_A /tmp/xtsc_B 6`, A = HEAD, B = the gate.

| pair | A | B | delta |
|---:|---:|---:|---:|
| 1 | 30,428 | 27,008 | −3,420 |
| 2 | 31,714 | 27,615 | −4,099 |
| 3 | 31,701 | 28,504 | −3,197 |
| 4 | 31,641 | 28,868 | −2,773 |
| 5 | 30,860 | 27,615 | −3,245 |
| 6 | 30,730 | 27,746 | −2,984 |

**Median A = 31,250 ms, B = 27,680 ms, −3,570 ms = −11.42%. B wins 6/6.**
Per-pair delta median −3,221 ms, spread 1,326 ms. The band is ±2% ≈ ±590 ms, so
this is outside it by 6×.

The A/B delta (3,570 ms) exceeds the phase measurement (2,623 ms) by ~950 ms.
Two contributions, neither separately measured: the emit builds and discards
~10 MB of output strings, so the saving includes allocation and GC that no span
brackets; and the post-checker residue itself fell 2,876 → 182 ms, i.e. the
ordering work between the loop and the result was also partly emit-driven.

Post-fix `--frontEnd`: post-checker **182 ms (0.6%)**, everything else unmoved.

## 5. What this means for § 0.1's staged plan — the whole plan, honestly

Three of the five stages now have measurements, and a fourth's premise is void.

| stage | claimed | measured | status |
|---|---|---|---|
| 1. (DISPATCH.1) per-kind handler table | 11–19% | **4.8% upper bound, ~100–300 ms realistic** (round 732); this round's per-handler gate row is 194 ms over 857 k nodes (212 ns each) — a third confirmation | **≈0.3–1%** |
| 2. resume the M0.4 tail migration | ~14% **after stage 1** | not measured; **its stated basis is void** — it was priced as "stage 1 retroactively unlocks it", and stage 1 does not deliver | **premise gone** |
| 3. cut the `getTypeOfExpression` recompute | ~9% | ceiling **2.9%**, sound residue **0.16%** (round 737) | **STRUCK** |
| 4. flow narrowing | 18% of init | round 736 landed **−4.53%**; (CALL.4) residue ~2.4%, mostly in-band | **the only stage that paid** |
| 5. front end | 20% | **11.0%**, with no lever in it; the other 9.2% was discarded emit, now landed at −11.4% | **measured, and it was not what it said** |

**The "~1.4–1.7× of today" claim is not supported.** It assumed stages 1 and 2
together were worth ~25–33% and stage 3 another ~9%. Measured, stages 1+3 are
worth ~1–4% combined, stage 2's premise is gone, stage 4 delivered 4.5% and has
~2% left, and stage 5's 20% was 11% of irreducible front end plus a scope error.
**The staged plan's remaining honest value is single digits, not 40–70%.**

### The realistic remaining route to single-thread parity

After this round the compiler profile is ~27.7 s and **88% of it is the
checker**. Seven consecutive intra-function attributions have found the same
shape: **the cost is spread over hundreds of dedicated walkers, each individually
0.1–1%, and there is no remaining single lever above the ±2% band inside the
checker.** Every prediction of one has come in 3–65× too small. Three moves
remain, and only the first can change the constant:

1. **The architecture change § 0.1's own "endgame" paragraph names** — replacing
   ~1,005 `check*` walkers with general engine rules. This round put the first
   price on one instance of it: on the var-decl path the FP-firewall prologue is
   **265 ms against the 19 ms relation it exists to correct, 14×**. Extrapolating
   that ratio is exactly the kind of inference this arc keeps falsifying, so it
   should be measured on two or three more sites before it is believed — but it
   is the only shape left that is structurally large. It is a **scope decision**:
   it trades the property (narrow, verifiable walkers) that made the byte-identical
   corpus reachable in the first place.
2. **Parallelism**, which is now cheaper than M2 measured: a worker's duplicated
   term includes the bind, and the bind is 5.2%. Still needs ≥8 real cores —
   see (PERF.HW), still unmeasured on this box.
3. **Accept the gap.** ~~~2.15×.~~ Corrected round 739: the published (emit-mode,
   like-for-like) ratio is **2.28× over 340 CI runs, 2.40× over the last 30**, and
   the check-only ratio this arc's numbers belong to is **unmeasured, bounded below
   by 2.21×, and probably higher** (§ 0.2 of `docs/ARCHITECTURE-RETHINK.md`). Which,
   stated plainly, is ~2.3× a mature compiler that has had a decade of tuning, on a
   JVM, with a byte-identical corpus gate.

## 6. What did NOT work / was not attempted

* **Nothing in the front end is worth optimising.** The largest row is the crawl
  at 5.4%, and it already overlaps read, decode and parse across 16 in-flight
  files; the core re-parses nothing. Bind at 5.2% is a single sequential pass and
  is the only candidate, and it is in-band.
* **`computeLineStarts` was not re-examined.** Round 623 measured its elimination
  at −0.3% (neutral) and identified the 5.3% JFR self-share as counted-loop
  safepoint bias. Nothing here contradicts that.
* **The read/pre-parse sub-sums are not a target.** They are elapsed-with-suspension,
  not CPU (§ 1); the only real number is the crawl wall.

## 7. Verification

* Full corpus suite: **12,927 tests, 0 failures, 3 skipped** (12,923 + 4 new
  `SkipEmitOutputsTest` pins).
* Compiler profile `--listAll`: byte-identical before and after the gate
  (46 errors, identical sorted lines).
* `scripts/cost_gate.py`: 18 of 20 counters **+0.00%**; `globals.lookups`
  1,262,583 → 1,148,611 (**−9.03%**) and `globals.misses` 1,244,751 → 1,131,357
  (**−9.11%**) FELL — the Transformer's own checker queries, no longer made —
  and were rebaselined in the same commit.
* Interleaved A/B: § 4.

## 8. Reproducing

```bash
scripts/bench-compile-tsc.sh --project compiler --no-emit --no-log   # once
CP=$(cat build/bench/cp-cache.txt)
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --frontEnd build/bench/tsc-project-*
```
