# (WARM.1) — the first WARM per-pass attribution, and what it says about every COLD table on record

*Round 843, 2026-08-07. Twelfth in the sequence `dispatch-table.md` (732) →
`spine-leave-attribution.md` (733) → `call-expression-attribution.md` (734) →
`argument-check-attribution.md` (735) → `narrow-walk-attribution.md` (736) →
`type-of-expression-attribution.md` (737) → `var-decl-attribution.md` (738) →
`front-end-attribution.md` (738 part 2) → `property-access-attribution.md`
(787–795) → `implicit-any-attribution.md` (798–800) → `bind-attribution.md`
(801) → `setup-phase-and-huge-methods.md` (802–821) → here.*

> **HEADLINE — THREE THINGS, ONE OF WHICH IS A WARNING ABOUT THE OTHER ELEVEN
> DOCUMENTS IN THAT LIST.**
>
> **(1) The warm artifact moved and nobody noticed.** The record says the warm
> JVM steady state is **11,580 ms** (round 771, carried into round 773's
> `--serve` and into `docs/perf/aot-native-image.md` § 1/§ 2b). Today, same
> profile, it is **~7,030 ms** — median 7,143.2 and 6,916.7 over two
> independent BenchMain processes, and independently reproduced at **7.1–7.45 s**
> by a real `--serve` socket ladder. Over the same interval the COLD number
> moved 26,272 → **22,971 ms (−12.6%)** while the warm one moved **−39.5%**.
> The leading candidate is the (JIT.1) huge-method arc (rounds 802–821), whose
> benefit is a STEADY-STATE benefit by construction and which was only ever
> measured with the cold instrument. **That is a HYPOTHESIS, not a result —
> § 2 says exactly what would confirm it and states plainly that it was not run.**
>
> **(2) The instrument prices itself, and the price is not small warm.**
> `--passTiming` costs **~2,840 ms cold (+12.4%)** and **3,450–3,945 ms warm
> (+50–55%)**. It is roughly the same number of MILLISECONDS in both regimes,
> which makes it a modest distortion of a cold table and a *dominating* one of a
> warm table. **Every absolute ms in every attribution table in `docs/perf` is
> probe-inflated by this, and a cold SHARE is therefore not directly comparable
> to a warm SHARE.** § 3.
>
> **(3) Warm, the compile is MORE checker-bound, not less.** The front end warms
> **~3.8×** while the checker warms **~2.27×**, so checker-init goes
> **86.2–86.9% → 91.2–91.8%** of the instrumented wall — the opposite direction
> from what a reader of `ARCHITECTURE-RETHINK.md` § 0.1's cold 80/20 budget
> would assume. The narrowing tail *flattens* (the `>= 1 ms` bucket holds
> **47–50%** of narrowing cold and **32–34%** warm over an IDENTICAL 17,851
> walks and 583,779 arrivals), so "the extreme narrowing tail" is a COLD-JVM
> property. § 5.
>
> **Nothing was optimized this round.** This is an instrument and a correction.

---

## 1. How this was measured

One binary throughout (`XTSC_BUILD_ID=778faf2c…`, `./gradlew compileKotlinJvm
compileTestKotlinJvm`, 2m43s, BUILD SUCCESSFUL), one profile
(`build/bench/tsc-project-637d5746`, the compiler profile: **78 files, 46
errors, 856,962 spine nodes**), `--noEmit` throughout, this box (8 cores,
15.6 GB, **zero swap**), Gradle and Kotlin daemons stopped *inside the
measuring script* before the first sample (round 800's trap: a batch that
builds and then immediately measures reads every row ~270× too large, and no
internal consistency check can see it).

Four arms:

| arm | harness | n | what it is |
|---|---|---:|---|
| **cold, probe-free** | `MainKt --noEmit` | 3 | the anchor — the number a user gets |
| **cold, instrumented** | `MainKt --noEmit --passTiming` | 2 | every `docs/perf` table on record is this shape |
| **warm, probe-free** | `BenchMain` 3 warm-up + 8 measured | 2 processes × 8 | the steady state |
| **warm, instrumented** | `BenchMain`'s 4th argument | 2 (one per process) | **new — this document** |

The warm instrumented rebuild is the **12th** in-process rebuild, run AFTER the
measured loop so that no measured iteration pays for a probe, and it
self-falsifies: `BenchMain` aborts if the instrumented rebuild answers a
different files/errors than the measured loop did. Both processes reported
78/46 on all 12 rebuilds.

**Both wall figures exclude JVM startup.** `MainKt`'s `time:` line brackets
`ProjectCompiler.build` (Main.kt:382/548) and `BenchMain`'s `ms` brackets the
same call, so the cold/warm ratio here is NOT flattered by process start. (The
`--serve` client wall in § 6 *does* include a small client JVM, ~200–250 ms;
it is quoted separately from the server's own figure for that reason.)

The strongest validity check available costs nothing and passed: **every
deterministic counter is bit-identical across all four cold and warm
instrumented runs** — 574,620 `getTypeOfExpression` calls, 17,851 flow-narrowing
walks, 583,779 arrivals, 42,766 memo serves, 856,962 spine nodes, 110,782
bypassed type-node resolutions, `walkMiss cold=17766 epochInvalidated=85`. The
warm runs do the *same work*; only the speed differs. Anything that moved,
moved because of the machine, not because of the compile.

---

## 2. § The artifact ladder, re-measured — and a −39.5% that no round claimed

| artifact | on record | round 843 | note |
|---|---:|---:|---|
| cold JVM one-shot | 26,272 ms (r771) | **22,971 ms** | −12.6%; median of 3 probe-free |
| warm JVM steady state | **11,580 ms** (r771/773) | **~7,030 ms** | **−39.5%**; median of 2 process-medians |
| `--serve` steady state | 11,907 ms (r773) | **7,100–7,447 ms** | client wall, incl. client JVM |
| GraalVM native image | 13,350 ms (r771) | *not re-measured* | superseded in standing by CI, § 0a of `aot-native-image.md` |
| Kotlin/Native release | 20,020 ms (r823) | *not re-measured* | |

Two cross-round caveats the reader must carry, both from CLAUDE.md's own rules:
round 771's numbers were taken on the **retired 4-core / 7.7 GB box** and these
on the current **8-core / 15.6 GB** one, and **the sequential self-compile
anchor is not stable across rounds at the ~10% level** (23,183 / 25,299 /
26,145 / 26,518 ms for the same code on the same profile). So the COLD row's
−12.6% is inside the known cross-round spread and should be read as "no change
demonstrated". **The WARM row's −39.5% is 4× that spread and is not
explainable that way.**

### 2.1 The hypothesis, labelled as one

**HYPOTHESIS (untested this round): the warm gain is (JIT.1).** Rounds 802–821
split **19 methods** that were over HotSpot's 8,000-bytecode
`HugeMethodLimit`, including `checkMemberAccessMissingCore` (46,567 bytecodes)
and `forEachChild` (9,750); the census is **0** as of round 821. A method over
that limit is **never compiled by C1 or C2** — it runs interpreted for the
whole process — so splitting it buys nothing until the JIT has had time to
compile the parts, i.e. **its entire benefit is realized in steady state**.

Every measurement the arc took was COLD. Its own summary says so: "the arc
bought ONE measured wall gain (−3.93%, B wins 5/5, round 803's `forEachChild`)
and landed everything else for the THRESHOLD"
(`setup-phase-and-huge-methods.md` § conclusion, and PLAN-PHASE-5.md's
(JIT.1) entry). Round 803's own falsifier, re-run against the
partially-split binary, read **+0.08%, B wins 3/5, driver verdict
NOISE-DOMINATED** (`setup-phase-and-huge-methods.md` :3021) — which is exactly
what a steady-state-only gain looks like through a cold instrument.

**What would confirm it: a WARM A/B of a pre-802 binary against HEAD**
(`scripts/ab-warm.sh <preSplitClassDir> <headClassDir> <pairs>`, ±1.0% band,
which a −39.5% effect would clear by a factor of ~40). **It was NOT run this
round.** Competing explanations that a warm A/B would also settle, and which
nothing here rules out: the box change (4→8 cores changes
`CICompilerCount` 3→4, so C2 has more capacity to reach peak); the ~20 landed
rounds between 773 and 843 that were not JIT work; and a JDK difference between
the two measurements.

Until that A/B runs, the honest statement is: **the warm artifact is ~7.0 s and
the reason is unattributed.**

### 2.2 CONFIRMED, round 845 — and the arc bought 1.5× on the warm artifact

**The A/B ran the next day and the hypothesis is CONFIRMED.** It was run on the
ISOLATED interval rather than against HEAD, which is what makes it an
attribution rather than a "everything since round 802" figure: arm A is
`93ff3195`, the immediate **parent** of the first split (`d194baca`) — census
landed, zero splits applied — and arm B is `d8ff69b5`, the arc's close. Every
`perf(…)` commit in `93ff3195..d8ff69b5` is a `(JIT.1)` split; the rest are
docs, split pins and bench chores; `git diff --name-only` across the interval
shows **zero** build/gradle/toml/properties changes; and both arms predate the
MOD module split, so layout and dependency set are identical. The arms were
verified in the BYTECODE before being measured: **A = exactly 19 methods over
8,000** (matching round 802's census), **B = 0**.

| pair | A (r802) | B (r821) | delta | pct |
|---:|---:|---:|---:|---:|
| 1 | 10,109.4 | 6,693.6 | −3,415.7 | −33.79% |
| 2 | 10,062.0 | 6,744.5 | −3,317.5 | −32.97% |
| 3 | 10,187.6 | 6,545.6 | −3,642.0 | −35.75% |
| 4 | 10,066.6 | 6,712.1 | −3,354.5 | −33.32% |

**A 10,088.0 ms (sd 0.58%) → B 6,702.9 ms (sd 1.31%); −3,385.1 ms = −33.56%,
B wins 4/4, speed-up 1.505×.** All 64 measured iterations in both arms answered
`files=78, errors=46` — the same program § 1 measured — and both per-iteration
series are flat 1→8, so neither arm is under-warmed.

**One caveat stated rather than buried:** arm B's sd of 1.31% exceeds this
repo's ~1% quiet-box threshold, and that rule says to discard the verdict.
It is overridden here explicitly, not ignored: the effect is **38× the larger
arm sd**, every pair independently clears it by ~35×, and dropping B's single
low outlier gives sd 0.38% and −33.46%. The threshold is calibrated for the
±1% regime; this is not that regime.

**The residual, batch 2 — r821 vs HEAD (3 pairs): 6,481.7 → 6,620.9 ms,
+2.15%, HEAD wins 0/3.** At 1.9× the arm sd that is not a demonstrated
regression; the correct reading is **zero warm compute movement between round
821 and today**. The same r821 binary read 6,702.9 in batch 1 and 6,481.7 in
batch 2 — a cross-batch drift 3× the within-batch band, which is precisely why
only within-batch paired deltas are quoted.

So § 2's −39.5% decomposes:

| segment | warm | attribution |
|---|---:|---|
| r771 → r802 | 11,580 → 10,088 ms | **~10%, NOT attributable to code** — spans the 4-core → 8-core box change |
| r802 → r821 | 10,088 → 6,703 ms | **−33.6%, (JIT.1), measured and 4/4 sign-consistent** |
| r821 → HEAD | 6,703 → 6,621 ms | ~0% |

**What this licenses the project to say, and it is not small:** rounds 802–821
bought **1.5× on the steady-state artifact** while their own cold instrument
read them as noise. Two standing consequences: the
`huge_methods.py --fail-over 0` ratchet is protecting the **daemon / `--serve` /
AOT** artifact far more than the CLI, so it is a **warm-path gate**; and any
future change that grows a hot method past 8,000 must be A/B'd **WARM**, because
the cold protocol demonstrably cannot see this class of effect at all.
Incidental and not quotable (n=1 smoke samples): cold read A 24.7 s vs B 21.7 s,
so the arc likely has a real but much smaller cold component too.

The reusable artifact is `WarmBench.java` — a reflection clone of `BenchMain`
that links against ANY arm's `ProjectCompiler` regardless of its `build(…)`
arity, which is what made a 40-round-apart warm A/B expressible at all.

---

## 3. § The instrument prices ITSELF — the central methodological warning

`BenchMain` prints `overheadMs` on the `instrumented` line precisely so this can
be read off, and the cold side is the probe-free anchor minus the instrumented
runs.

| regime | probe-free | instrumented | probe cost | as % |
|---|---:|---:|---:|---:|
| cold (median of 3 vs mean of 2) | 22,971 | 25,808.5 | **2,837.5 ms** | **+12.4%** |
| warm, process 1 | 7,143.2 | 11,088.2 | **3,945.0 ms** | **+55.2%** |
| warm, process 2 | 6,916.7 | 10,367.2 | **3,450.4 ms** | **+49.9%** |

> **CORRECTION TO THE ROUND'S OWN FRAMING.** The probe is *not* constant in
> absolute terms — measured, it is 2,840 ms cold and 3,450–3,945 ms warm, i.e.
> **21–39% MORE expensive warm**. The samples are thin (n=2 cold with a 773 ms
> spread between them; n=1 per warm arm), so this may be noise, but it must not
> be asserted away. What is robust and is the point: the probe cost is of the
> **same order in ms** in both regimes while the compile itself shrinks 3.3×, so
> **its SHARE quadruples**.

**Therefore, and this is the sentence to carry out of this document: every
absolute ms in every attribution table in `docs/perf` is probe-inflated, the
inflation is roughly the same number of milliseconds cold and warm, and so a
COLD table's SHARE is not comparable to a WARM table's SHARE.**

### 3.1 Where does the probe land? A deduction and an assumption

One part is a **deduction, not an assumption**: the warm instrumented front end
is 907.9 ms *in total* (§ 4), so it cannot possibly hold a 3,945 ms probe.
**At least 3,037 ms (process 1) / 2,537 ms (process 2) — ≥ 77% of the probe —
is inside checker-init.**

Whether it is inside `checkSpine` specifically is **NOT measured**. The natural
assumption is that most of it is: the per-node enter/leave timestamp pairs are
there, and so are the shadow memos and distinct-node sets the probe maintains at
the type-system call sites (`shadowMemo` 416,292 ops, `typeOfExpr repeats`
349,767 comparisons, `~224,853 distinct nodes` in a pos-keyed set), all of which
run dynamically under `checkSpine`. But note that 2,840 ms over 856,962 nodes is
**3,314 ns/node**, far more than a timestamp pair costs in situ (~86–92 ns,
round 734's differential), so the probe is dominated by the bookkeeping, not by
the boundaries — and nobody has attributed the bookkeeping.

So bracket it rather than assert it:

| assumption | cold `checkSpine` / checker-init | warm `checkSpine` / checker-init |
|---|---:|---:|
| **raw, uncorrected** | 81.0–81.5% | 82.0–83.3% (*rises*) |
| **(A)** whole probe inside `checkSpine` | 78.9% | **71.7–72.8%** (*falls*) |
| **(B)** probe spread proportionally | 81.0–81.5% | 82.0–83.3% (unchanged) |

Under (A), warm `checkSpine` is **~4.3–4.5 s of a ~6.0–6.2 s real
checker-init**. That is a **BOUND under a named assumption**, never a
measurement — and the fact that (A) and (B) disagree about the *direction* of
the change is the whole reason this section exists. The uncorrected reading
("`checkSpine`'s share rises warm") is the one a reader gets by default and is
the one least likely to be true.

The corresponding correction for the checker as a whole is more robust, because
it survives both assumptions:

| | cold | warm |
|---|---:|---:|
| checker-init / wall, raw | 86.2–86.9% | 91.2–91.8% |
| checker-init / wall, whole probe charged to the checker | **84.9%** | **87.0%** |

Both readings put the warm share higher. § 5(a) rests on this.

---

## 4. § The cold-vs-warm table

Both columns are `--passTiming` runs of the same binary on the same profile, so
both are probe-inflated by roughly the same absolute amount. **The RATIOS are
the trustworthy part; the absolutes are not.** Cold = `cold1.txt` / `cold2.txt`,
warm = `warm1.txt` / `warm2.txt`.

| row | cold 1 | cold 2 | warm 1 | warm 2 | **warm speed-up** |
|---|---:|---:|---:|---:|---:|
| wall (instrumented) | 26,195.0 | 25,422.0 | 11,088.2 | 10,367.2 | **2.41×** |
| **checker-init total** | 22,581.7 | 22,081.2 | 10,180.3 | 9,454.1 | **2.27×** |
| `checkSpine` | 18,409.4 | 17,886.2 | 8,483.0 | 7,753.4 | **2.24×** |
| the other 416 passes | 4,013.8 | 4,035.5 | 1,693.0 | 1,695.7 | 2.38× |
| outside-pass | 158.4 | 159.4 | 4.3 | 4.9 | *34.5×* ⚠️ |
| — SPINE `enter` | 10,449 | 10,154 | 4,181 | 3,882 | **2.56×** |
| — SPINE `leave` | 5,503 | 5,310 | 2,864 | 2,543 | **2.00×** |
| — SPINE `scope` | 50 | 50 | 43 | 41 | 1.19× ⚠️ |
| — SPINE unresolved-names | 1,186 | 1,184 | 570 | 537 | 2.14× |
| — SPINE `forEachChild` | 111 | 112 | 84 | 80 | 1.36× ⚠️ |
| `init:buildFileLocalTypeMaps` | 659.8 | 678.4 | 299.6 | 360.7 | 2.03× |
| flow-narrowing walks | 1,229 | 1,080 | 533 | 495 | 2.25× |
| `getTypeOfExpression` ⚠️ double-counts | 3,091 | 2,852 | 1,640 | 1,608 | 1.83× |
| relations (depth-0) | 653 | 621 | 300 | 290 | 2.16× |
| type-node resolution (depth-0) | 504 | 502 | 277 | 250 | 1.91× |
| member resolution (depth-0) | 107 | 97 | 55 | 48 | 1.98× |
| **front end** (wall − checker-init) | 3,613.3 | 3,340.8 | 907.9 | 913.1 | **3.82×** |

⚠️ on three rows, for three different reasons:

* **`outside-pass`** is a residual (init work inside no `pass()`), 158 ms cold
  and 4 ms warm. A 34× ratio on a residual of a residual is not a finding; it is
  most likely one-time class-init/setup work that a 12th in-process rebuild
  simply does not repeat. Do not quote it.
* **`scope` (1.19×) and `forEachChild` (1.36×)** are the two rows closest to
  being pure probe. `scope` is 50 ms over 856,962 nodes = **58 ns/node**, i.e.
  the same order as a timestamp pair's own cost, so its ratio is measuring the
  instrument, not the code. `forEachChild` at 111 ms is only ~130 ns/node.
* `getTypeOfExpression`'s "total incl. nested" **double-counts a subtree once
  per nesting level** (round 737). It is in the table because its RATIO is still
  informative; its absolute has never been a cost.

**The one row worth a second look on its own merits is the enter/leave split:
`spineEnterNode` warms 2.56× and `spineLeaveNode` warms 2.00×.** Both are giant
`when (kindId)` dispatchers over the same 856,962 nodes; the asymmetry says the
leave handlers' work is less JIT-recoverable than the enter handlers'. Round
733's `spine-leave-attribution.md` attributed 88.4% of the two hottest leave
handlers to the migrated passes' own checking work — which is exactly the kind
of megamorphic, branch-heavy code that gains least from warm-up. **This is an
observation, not a lever; nothing was measured against it.**

---

## 5. § The findings that survive, each stated as what it is

### (a) MEASURED — the compile becomes MORE checker-bound warm

The front end (crawl + read + parse + bind + module resolution + reporting)
warms **3.66–3.98×** while checker-init warms **2.22–2.34×**. So:

| | cold | warm |
|---|---:|---:|
| checker-init share of the instrumented wall | 86.2 / 86.9% | **91.8 / 91.2%** |
| front end share | 13.8 / 13.1% | **8.2 / 8.8%** |

Probe-corrected under the most adverse assumption (whole probe charged to the
checker) the direction survives: **84.9% → 87.0%** (§ 3.1).

Why it matters: `ARCHITECTURE-RETHINK.md` § 0.1 opens with a **cold** budget of
"checker-init 80 units, front end 20" (round 738 re-read the front end as 11),
and the whole staged plan is organised around that split. **Warm, the front end
is ~8% and shrinking faster than everything else** — so for the artifact this
project actually ships warm (`--serve`, and the AOT one for cold), front-end
work has an even smaller ceiling than the cold budget implies. This is a
*re-weighting* of § 0.1, not a contradiction of it; no line item in § 0.1
changes.

Mechanism is unmeasured, but the front end is where a JVM has the most to
recover: file I/O amortized by the page cache across 12 rebuilds, the scanner
and parser being small tight loops that C2 compiles early and completely, and
(INV.1(e)) `pre-parse reuse: reused 78, parsed fresh 0` in every run so both
regimes are already reusing trees identically.

### (b) PARTLY MEASURED — the cheapest, highest-count kinds warm fastest; but cost does NOT order the ratio

Per-kind `enter+leave` ns/node, top 12 by total ms (mean of the two runs per
regime; node counts are identical in all four runs):

| kindId | kind | nodes | cold ns/node | warm ns/node | ratio |
|---:|---|---:|---:|---:|---:|
| 34 | `IDENTIFIER` | 381,670 | 3,471 | 1,191 | **2.91×** |
| 44 | `PROPERTY_ACCESS_EXPRESSION` | 67,902 | 7,833 | 2,764 | **2.83×** |
| 22 | `FUNCTION_DECLARATION` | 8,910 | 64,853 | 22,702 | **2.86×** |
| 5 | `IF_STATEMENT` | 13,679 | 54,784 | 22,304 | 2.46× |
| 32 | `VARIABLE_DECLARATION` | 15,710 | 33,344 | 14,284 | 2.33× |
| 60 | `CONDITIONAL_EXPRESSION` | 4,506 | 43,251 | 18,875 | 2.29× |
| 3 | `VARIABLE_STATEMENT` | 14,712 | 135,196 | 59,210 | 2.28× |
| 46 | `CALL_EXPRESSION` | 52,509 | 60,326 | 27,323 | 2.21× |
| 4 | `EXPRESSION_STATEMENT` | 17,392 | 79,918 | 36,260 | 2.20× |
| 13 | `RETURN_STATEMENT` | 15,662 | 81,826 | 38,654 | 2.12× |
| 59 | `BINARY_EXPRESSION` | 38,454 | 30,778 | 14,768 | 2.08× |
| 1 | `BLOCK` | 24,613 | 66,488 | 32,166 | 2.07× |

**The two cheapest and most numerous kinds (`IDENTIFIER` at 3.5 µs over 381,670
nodes, `PROPERTY_ACCESS` at 7.8 µs over 67,902) do warm fastest, at ~2.85–2.9×.
Beyond that there is NO monotone relationship between cost and warm-up
ratio** — `FUNCTION_DECLARATION` at 64.9 µs warms 2.86×, as fast as the cheap
ones, while `BINARY_EXPRESSION` at 30.8 µs warms only 2.08×. Any statement of
the form "expensive kinds warm less" is refuted by two rows in its own table.

What IS sign-consistent across both run pairs is the **aggregate
concentration**:

| group | cold share of top-12 ms | warm share of top-12 ms |
|---|---:|---:|
| `IDENTIFIER` alone | 9.09 / 9.12% | **6.98 / 7.34%** |
| the five statement-anchor kinds (3, 4, 13, 5, 1) | 48.40 / 48.46% | **50.10 / 50.72%** |

So warm, the statement anchors hold *more* of the spine and identifiers hold
*less* — CLAUDE.md's "10% of the nodes, 40% of the spine" anchor concentration
is slightly **sharper** warm than cold. It is a small move (≈2 points) but it
goes the same way in both independent pairs. **It is a location, not a lever**
— the same caveat round 758 attached to the cold version of this observation,
which produced the dead (DISPATCH.1).

### (c) MEASURED — the narrowing tail is a COLD-JVM property

Identical populations in all four runs: **17,851 walks, 583,779 arrivals, 0
tripped, 42,766 memo serves.** The buckets are keyed by TIME, so they move when
the machine does and only when the machine does:

| bucket | cold 1 | cold 2 | warm 1 | warm 2 |
|---|---|---|---|---|
| `< 10 µs` | 6,776 / 33 ms | 7,271 / 36 ms | 9,843 / 45 ms | 10,234 / 45 ms |
| `< 100 µs` | 9,627 / 322 ms | 9,241 / 304 ms | 7,393 / 204 ms | 7,080 / 194 ms |
| `< 1 ms` | 1,314 / 277 ms | 1,203 / 250 ms | 578 / 123 ms | 504 / 116 ms |
| **`>= 1 ms`** | **134 / 635 ms** | **136 / 525 ms** | **37 / 189 ms** | **33 / 164 ms** |
| `>= 1 ms` share of bucket total | **50.1%** | **47.1%** | **33.7%** | **31.6%** |
| arrivals in the `>= 1 ms` bucket | 21,783 | 20,211 | 8,453 | 8,223 |

(Against the `time split: narrowWalks=` accumulator instead of the bucket sum
the same shares read 51.7 / 48.6% cold and 35.5 / 33.1% warm — the two
accumulators differ by ~3%; either denominator gives the same conclusion.)

**The tail POPULATION collapses ~4× (134 → 37 walks) and its share of narrowing
falls by a third, over identical work.** It is not that the tail got cheaper
relative to itself; it is that a 1 ms threshold selects a much smaller set once
everything runs 2.25× faster.

**What this does and does not affect on record.** Both the CLAUDE.md entry and
`argument-check-attribution.md` § 5 state round 735's finding as *"394 of 70,037
walks (0.56%) cost 1,485 ms = 47% of all narrowing and 4.9% of the compile"*,
with its three candidate causes measured FALSE. **That 47% is a COLD number and
today's cold equivalent is 47–50% — so round 735 replicates exactly, and is
correct as taken.** What is now known and was not stated is that **the same
compile warm reads 32–34%**, i.e. the phenomenon is partly an artifact of the
regime the measurement was taken in. Nothing in round 735 is retracted; a scope
qualifier is added.

Note also that the tail's *standing* was already reduced twice by later rounds
and this does not change that: round 736 landed the memo-depth fix and recorded
"the `>= 1 ms` tail is **gone as a distinct phenomenon**"
(`narrow-walk-attribution.md` § 7), and round 796 removed 91% of the walks.
Today's tail is 134 of 17,851 walks cold. **This section is a caveat on how the
number was obtained, not a re-opening of the direction.**

---

## 6. § The `--serve` ladder — an independent reproduction of ~7.0 s

One server, eight sequential `--daemon` requests over a Unix socket, `XTSC_AOT=off`:

| request | server-reported ms | client wall ms (incl. client JVM) |
|---:|---:|---:|
| 1 | 22,531 | 22,753 |
| 2 | 10,705 | 10,898 |
| 3 | 7,586 | 7,754 |
| 4 | 7,431 | 7,606 |
| 5 | 7,279 | 7,447 |
| 6 | 7,244 | 7,410 |
| 7 | 7,232 | 7,447 |
| 8 | **6,930** | **7,100** |

Request 1 (22,531 ms) reproduces the probe-free cold anchor (22,971 ms) and
requests 6–8 reproduce BenchMain's warm median (~7,030 ms) — **two independent
harnesses, one in-process and one across a socket, agreeing to within ~3%.**
The client JVM costs a stable **168–222 ms** on top of the server figure.
**Request 2 is still 1.5× the floor; requests 3+ are within 9% of it and 6–8 are
the steady state** — so a `--serve` A/B must discard at least two requests, not
one.

**Output identity was checked, not assumed.** Every request's diagnostics are
byte-identical to every other's and to the cold CLI's, modulo one line: the
`--daemon` client resolves the project argument to an absolute path, so the
`project:` echo differs (`build/bench/tsc-project-…` vs
`/home/claude/…/build/bench/tsc-project-…`). Diagnostics, counts and ordering
are identical.

### 6.1 The `errs=30` line in the ladder log is TRUNCATION, not a divergence — verified

`warm843b.sh` printed `errs=30` for every request, computed as
`grep -c 'error TS'`. **That is the CLI's 30-diagnostic print cap, and it is
NOT a client-server divergence.** The evidence, read out of `req1.txt`
directly:

```
  ... and 16 more error(s)          # req1.txt:38  — round 811's tell
time:    22515 ms
FAILED — 46 error(s)
```

30 printed + 16 suppressed = **46**, and the run's own summary line says
`FAILED — 46 error(s)`. The probe-free **cold** run prints the identical
`... and 16 more error(s)` at the identical line and also `grep -c`s to 30.
Both arms are truncated identically because `--listAll` was passed to neither.
**No P0.** (This is round 811's rule arriving from the other direction: refuse
any capture containing `... and N more error(s)` as a *comparison* input — but
recognise it as truncation before calling it a bug.)

---

## 7. § What this does NOT show

State these plainly rather than let a reader infer them away:

1. **No A/B was run, warm or cold. Nothing was optimized.** The −39.5% warm
   movement is a difference between two rounds' absolute figures on two
   different boxes, which CLAUDE.md's own rule ("only WITHIN-round paired
   deltas are quotable") says is not evidence of a cause. § 2.1's (JIT.1)
   hypothesis is a hypothesis.
2. **The probe's landing site is unmeasured.** Only the ≥77%-inside-checker-init
   bound in § 3.1 is a deduction; the `checkSpine` bracket is an assumption
   with its two ends spelled out, and they disagree about the *direction* of
   `checkSpine`'s warm share.
3. **One profile.** The compiler profile only (78 files). The 8-profile grid was
   not run; nothing here is known to hold on `services`/`server`/`harness`,
   which are 3–4× larger and where a warm-up cost amortizes differently.
4. **Thin samples for the probe price.** n=2 cold instrumented (spread 773 ms),
   n=1 instrumented rebuild per warm process. The warm probe-free medians are
   the well-sampled numbers (8 iterations × 2 processes).
5. **No tsc number was taken HERE.** `node` is not installed on this box, so any
   parity statement derived from *this document* must be labelled as DERIVED
   from the CI ratio series (`aot-native-image.md` § 0a: JVM arm 2.51× tsc
   check-only, native arm 1.04×) and not as a measurement taken here.
   **A parallel session settled it directly the same day and that is the number
   to cite** — commit `eb42b853`, four-way interleaved, 5 rounds, check-only,
   same 78-file profile: tsgo 903 ms, **xtsc warm daemon 3,322 ms**, **tsc 6.0.3
   4,489 ms**, xtsc cold 13,883 ms. So warm xtsc is **1.35× faster than tsc**,
   the first configuration on record in which this compiler beats the reference
   implementation on a real project, with tsgo still 3.7× ahead of both.
   **DO NOT MIX those absolutes with this document's.** That box is ~2× faster
   than this one (its cold 13,883 ms against this document's 22,971 ms); what
   the two rounds agree on is the RATIO — its 4.2× cold-to-warm against this
   round's 3.26× — and the qualitative conclusion.
6. **The warm regime is in-process rebuild, not incremental compile.** Each
   iteration is a complete fresh program (tsconfig load, glob, resolution,
   parse, bind, check). Round 772 measured retained program state worthless on
   this corpus (`export *` barrels ⇒ 77-of-78-file closures); nothing here
   revisits that.
7. **`--workers` was not exercised.** All figures are single-worker sequential.
8. **PROVENANCE: everything here was measured on commit `778faf2c`, before the
   MOD.1–MOD.6 module split landed upstream in parallel.** This session ran on a
   stale local `main`. So the § 6 ladder is the pre-split JDK-NIO server, not the
   ktor one now in `xemantic-typescript-compiler-daemon`; the ladder's SHAPE
   (steady from request 3, agreeing with the in-process harness to ~3%) should
   survive that move, but it has not been re-measured, and a future round that
   re-takes it should say which server it measured.

---

## 8. Reproducing

```bash
# 0. one build, then STOP THE DAEMONS — measuring beside a 4 GB idle Kotlin
#    daemon on a zero-swap box is round 800's ~270x trap.
./gradlew compileKotlinJvm compileTestKotlinJvm
./gradlew --stop; pkill -f 'KotlinCompile[D]aemon'; sleep 5; free -m

PROJ=$(ls -d build/bench/tsc-project-* | head -1)
CP="build/classes/kotlin/jvm/main:$(cat build/bench/cp.txt)"
CPW="build/classes/kotlin/jvm/main:build/classes/kotlin/jvm/test:$(cat build/bench/cp-warm.txt)"

# 1. COLD anchor, probe-free (x3) — the number a user actually gets
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit "$PROJ"

# 2. COLD instrumented (x2) — the shape of every existing docs/perf table
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt \
     --noEmit --passTiming "$PROJ"

# 3. WARM, probe-free median + ONE instrumented rebuild after it.
#    The 4th argument is the new part; omit it and BenchMain behaves as before.
java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 8 passTiming
#    -> {"iter":…}x8, {"summary":…,"medianMs":…},
#       {"instrumented":true,"ms":…,"overheadMs":…}, then the INV.0 table.
#    Run it TWICE, in two processes: one instrumented rebuild is one draw.

# 4. The --serve ladder (a real socket, not an in-process loop)
SOCK=/tmp/xtsc843.sock; rm -f "$SOCK"
XTSC_CP="$CP" XTSC_AOT=off java -Xmx4g -cp "$CP" \
    com.xemantic.typescript.compiler.server.XtscMainKt --serve --socket "$SOCK" &
for i in $(seq 8); do
  java -Xmx1g -cp "$CP" com.xemantic.typescript.compiler.server.XtscMainKt \
      --daemon --socket "$SOCK" --noEmit "$PROJ" > req$i.txt
done
# the server prints its OWN per-request ms; the client wall adds ~200 ms of client JVM.
# `grep -c 'error TS'` on a request capture reads 30 because the CLI truncates
# at 30 without --listAll (§ 6.1) — read the `FAILED — N error(s)` line instead.

# 5. what a NEXT round should run and this one did not:
#    a WARM A/B of a pre-(JIT.1) binary against HEAD (§ 2.1)
scripts/ab-warm.sh <preSplitClassDir> build/classes/kotlin/jvm/main 6
```

Raw logs for round 843 were kept in the session scratchpad and are not
committed: `cold1.txt` `cold2.txt` (cold instrumented), `coldfree1..3.txt`
(cold anchor), `warm1.txt` `warm2.txt` (warm, both arms), `serve843.log`
`req1..8.txt` (the ladder). Everything quoted above is derivable from a re-run
of the five commands.

---

## 9. Consequences recorded elsewhere in the same commit

* `docs/perf/aot-native-image.md` § 1 and § 2b — the `11,580 ms` warm row is
  struck-and-dated, with its `2.27×` ratio column left as taken (it is a
  within-round pair on the retired box) and a footnote giving today's
  equivalent, **3.27×**.
* `src/jvmMain/kotlin/server/CompileServer.kt` and
  `src/jvmMain/kotlin/server/XtscMain.kt` — KDoc only, same strike-and-date.
* `docs/ARCHITECTURE-RETHINK.md` § 0.1 and PLAN-PHASE-5.md's § 0.1-endgame
  framing block — dated pointers to § 5(a) of this document, since both are
  organised around a COLD budget.
* CLAUDE.md — one entry under *Measured dead-ends*, carrying the § 3 warning
  (the probe is ~3 s in absolute terms: 12% of a cold run, ~50% of a warm one,
  so only the RATIOS of a warm table are comparable to a cold one's).

---

## 10. § ROUND 846 — the probe was given TIERS, and both brackets in § 3.1 are now MEASURED

*Round 846, 2026-08-08. This section CORRECTS §§ 3, 3.1, 4 and 5(a) of this
document with a direct measurement in place of their deduction-plus-bracket.
It changes no conclusion's direction except one — § 4's warm speed-up column —
which it inverts.*

> **HEADLINE.** `--passTiming` now has three tiers (`rows` / `spine` / `full`);
> the `rows` tier keeps the whole per-pass table and costs **+0.25% cold and
> 0.0% warm**. With it: **the probe lands 99.5–99.8% inside checker-init and
> 101–109% inside `checkSpine`** (so § 3.1's bracket **(A)** is right and **(B)**
> is refuted); **warm `checkSpine` is 74.3% of checker-init, not the 81.8% the
> instrumented table reads**; **checker-init is 88.9% of the warm wall, not
> 91.7%**; and **`checkSpine` warms 3.46× while the other 416 passes warm
> 2.59× — the opposite order from § 4's table**, which the probe had compressed
> because it adds roughly the same milliseconds to a row that shrinks 3.5×.
> One new instrument fact: **the probe's OWN price is JIT-sensitive** — the
> first instrumented rebuild in a process costs 3,457 ms and the second 1,856 ms
> — so § 3's warm probe price (n=1 per process) is a first-draw figure and
> over-reads the steady state by ~1.9×.

### 10.1 The tiers

| tier | flags | what it keeps | what it drops |
|---|---|---|---|
| `rows` | `detail=false spineDetail=false` | the ~417 `pass()` rows, `checkerInitNanos`, emissions-by-pass | everything per-call; `checkSpine` runs the **production** spine walk |
| `spine` | `detail=false spineDetail=true` | + the SPINE sub-rows and the per-kind table | the per-call counters |
| `full` | (defaults) | everything — the pre-846 behaviour, bit for bit | — |

CLI: `--passTimingRows` / `--passTimingSpine`. `BenchMain`'s 4th argument takes
a comma-separated tier LIST (`rows,spine,full,rows,spine,full`), one
instrumented rebuild each, so the differential is taken on the SAME code inside
ONE warm process — round 734's law, never an empty-span loop.

### 10.2 What it cost to measure (the calibration)

**WARM** — 2 processes × (3 warm-up + 8 measured probe-free + 6 instrumented),
`overheadMs` against each process's own probe-free median (8 iterations):

| tier | 1st draw | 2nd draw | all 4 |
|---|---:|---:|---:|
| `rows` | −5.5 | −221.3 | **−113.4 ms** |
| `spine` | +231.6 | +440.5 | **+336.1 ms** |
| `full` | +3,457.3 | +1,856.2 | **+2,656.7 ms** |

**COLD** — 6 one-shot `MainKt` runs, `free/rows/full` × 2, interleaved:

| arm | wall (n=2) | vs probe-free |
|---|---:|---:|
| probe-free | 26,843.0 | — |
| `rows` | 26,910.5 | **+68 ms = +0.25%** |
| `full` | 29,460.5 | +2,618 ms = +9.75% |

**The `rows` tier is free in both regimes.** That is the whole point: the
per-pass table never cost anything; the ~2.6 s is the per-CALL bookkeeping.

**And the probe's own cost warms up.** In BOTH processes the `full` tier's
second draw is far cheaper than its first (−1,383 and −1,820 ms) while `rows`
and `spine` move ±220 ms in both directions. The probe's own code — the
distinct-keyed `HashSet`, the by-pass `HashMap`s, the shadow memos — has never
been JIT-compiled when the first instrumented rebuild runs. **§ 3's warm probe
price (3,450–3,945 ms, n=1 per process) is therefore a first-draw figure and
matches this round's first draw (3,457 ms) exactly; the steady-state price is
~1,856 ms.** A round that wants the probe's price must take at least two
instrumented rebuilds per process.

### 10.3 WHERE the probe lands — measured, not deduced

| | warm (full − rows) | cold (full − rows) |
|---|---:|---:|
| wall | +2,770.1 ms | +2,550 ms |
| **checker-init** | **+2,765.1 (99.8%)** | **+2,536 (99.5%)** |
| front end | +5.1 (0.2%) | +14 (0.5%) |
| **`checkSpine` row** | **+2,803.2 (101.2%)** | **+2,770 (108.6%)** |

§ 3.1's "≥ 77% is inside checker-init, and whether it is inside `checkSpine` is
NOT measured" is closed: **it is all of it, and it is all inside `checkSpine`.**
The >100% readings are the n=4/n=2 noise floor on a row of 5–21 s.

Internal consistency, and it is the strongest check available here: applying
bracket **(A)** (charge the whole probe to `checkSpine`) to this round's OWN
full-tier numbers gives `checkSpine` = **74.80%** of checker-init; the directly
measured `rows` tier says **74.27%**. Two independent instruments, 0.5 points
apart.

### 10.4 THE CORRECTED ATTRIBUTION (this is the deliverable)

Same binary, same 78-file compiler profile, same box, same round. `rows` tier
is n=4 warm / n=2 cold; `full` is the same runs' companion arm.

| | **cold, `rows`** | cold, `full` | **warm, `rows`** | warm, `full` | **cold→warm** |
|---|---:|---:|---:|---:|---:|
| wall | **26,910.5** | 29,460.5 | **8,083.0** | 10,853.1 | **3.33×** |
| checker-init | **23,246.6** | 25,782.7 | **7,184.9** | 9,950.0 | **3.24×** |
| `checkSpine` | **18,459.5** | 21,229.3 | **5,335.9** | 8,139.2 | **3.46×** |
| the other 416 passes | **4,787.1** | 4,553.4 | **1,849.0** | 1,810.8 | **2.59×** |
| front end | **3,663.9** | 3,677.8 | **898.0** | 903.1 | **4.08×** |
| checker-init / wall | **86.38%** | 87.52% | **88.89%** | 91.68% | |
| `checkSpine` / checker-init | **79.41%** | 82.34% | **74.27%** | 81.80% | |
| `checkSpine` / wall | **68.60%** | 72.06% | **66.01%** | 74.99% | |

Read the bold columns; the `full` columns are there only to show the size of
the distortion each corrected figure carries.

**Three corrections to this document, stated as such:**

1. **§ 3.1's disagreement is settled in favour of (A): warm `checkSpine`'s
   share FALLS.** 79.41% cold → **74.27%** warm, −5.1 points. The uncorrected
   reading (82.34 → 81.80%) says "flat" and § 3.1's raw cross-round reading said
   "rises"; both are the instrument.
2. **§ 5(a) SURVIVES but is smaller than raw.** checker-init's share of the wall
   really does rise warm — **86.38% → 88.89%, +2.5 points** (raw says +4.2, the
   § 3.1 "most adverse" bracket predicted +2.1). The front end is **11.1%** of a
   warm compile, not 8.3%.
3. **§ 4's warm speed-up column is INVERTED for its two biggest rows.** It reads
   `checkSpine` 2.24× against the tail passes' 2.38×, i.e. the spine warming
   *less* than the tail. Corrected: **`checkSpine` 3.46×, the tail 2.59×.**
   Mechanism: the probe adds ~2.6–2.8 s to `checkSpine` in BOTH regimes, which
   is ~15% of a cold row and ~52% of a warm one — so a constant additive term
   compresses the ratio of exactly the row it lands on. Any warm-up ratio
   quoted from a `full`-tier table is subject to this.

### 10.5 The warm budget, sized

The number a warm lever must be quoted against (rows tier, n=4, warm wall
8,083 ms — an absolute for THIS round only, per CLAUDE.md's cross-round rule):

| region | warm ms | % of warm wall |
|---|---:|---:|
| `checkSpine` | **5,335.9** | **66.0%** |
| — `spineEnterNode` | 3,121 | 38.6% |
| — `spineLeaveNode` | 1,553 | 19.2% |
| — unresolved-names | 500 | 6.2% |
| — `forEachChild` | 85 | 1.1% |
| — scope | 41 | 0.5% |
| the other 416 passes | 1,849.0 | 22.9% |
| — largest: `init:buildFileLocalTypeMaps` | 317.5 | 3.9% |
| — 2nd: `checkCrossFileModuleAugmentationDuplicates` | 90.7 | 1.1% |
| — 3rd: `checkUmdGlobalVsDeclareGlobalConst` | 85.6 | 1.1% |
| front end | 898.0 | 11.1% |

The SPINE sub-rows come from the `spine` tier and carry a **partition check**:
they sum to **5,299 ms against the `rows` tier's 5,335.9 ms `checkSpine` row —
99.3%**. The tier's own boundaries (513 ms = **599 ns/node** over 856,962 nodes)
sit on top of the enclosing row and outside the sub-rows, which is why the two
instruments agree. At the `full` tier the same sub-rows sum to 7,475 ms and the
enter:leave ratio moves from **2.01 to 1.55** — so § 4's "`spineEnterNode` warms
2.56× and `spineLeaveNode` 2.00×, the leave handlers are less JIT-recoverable"
is at least partly instrumental: the tier-3 bookkeeping lands harder on `leave`
(+71%) than on `enter` (+32%).

**Everything below `checkSpine` is probe-free.** Across the top-20 rows the
`full`/`rows` ratio is 0.63–1.35 with no sign, i.e. noise — the probe is not
spread over the 416 tail passes at all. Only `checkSpine` reads 1.53×.

### 10.6 Validity

* Every `full`-tier run in this round — 2 cold, 4 warm, across three JVMs —
  reports the same deterministic counters: **574,620 `getTypeOfExpression`
  calls, 224,853 distinct, 17,851 narrowing walks, 856,962 spine nodes**, the
  `cost_gate.py` baseline values. Every run of every tier answered **78 files,
  46 errors**; `BenchMain` aborts if an instrumented rebuild disagrees with the
  measured loop, and none did.
* Daemons were stopped inside the measuring script, between the build and the
  first sample (round 800).
* **What this does NOT show.** One profile (the 78-file compiler profile). No
  A/B, nothing optimized. The absolutes are not comparable to § 2/§ 4's
  (this round's warm probe-free median is 8.0–8.4 s against round 843's
  6.9–7.1 s — a ~15% cross-round drift on the same code, exactly the instability
  CLAUDE.md records); only the within-round shares and ratios above are quoted.
  n=4 warm / n=2 cold per tier, so a per-row figure below ~100 ms is not
  resolved.

### 10.7 Reproducing

```bash
./gradlew compileKotlinJvm compileTestKotlinJvm
./gradlew --stop; pkill -f 'KotlinCompile[D]aemon'; sleep 5; free -m
PROJ=$(ls -d build/bench/tsc-project-* | head -1)
CP=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:$(cat build/bench/cp.txt)
CPW=xemantic-typescript-compiler-core/build/classes/kotlin/jvm/main:xemantic-typescript-compiler-core/build/classes/kotlin/jvm/test:$(tr '\n' ':' < build/bench/cp-warm.txt)

# WARM — run TWICE, in two processes. The tier list is the differential.
java -Xmx4g -cp "$CPW" com.xemantic.typescript.compiler.bench.BenchMainKt \
     "$PROJ" 3 8 rows,spine,full,rows,spine,full

# COLD — interleave the arms, n>=2 each.
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit "$PROJ"
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --passTimingRows "$PROJ"
java -Xmx4g -cp "$CP" com.xemantic.typescript.compiler.MainKt --noEmit --passTiming "$PROJ"
```
