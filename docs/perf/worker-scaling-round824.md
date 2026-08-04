# (M2)/(PERF.HW) — the worker-scaling re-probe on the 8-core box

*Round 824, 2026-08-04. Compiler profile, `--noEmit`, `-Xmx4g`, one cold JVM per
run, daemons stopped, nothing else on the box. No `src/` change: measurement only,
on the committed binary at `8306fe44`.*

Three results, in descending order of importance:

1. **`--workers N` IS A RACE.** Every parallel level produces a *different number of
   diagnostics from run to run* — w2 and w4 flicker 46/47, w8 flickers 46/56 — while
   sequential is 46 in 8 of 8 runs. Round 754's "`--workers 2/4` are byte-identical
   to sequential" was measured on too few runs, on a box that had ~0.85 free cores
   and therefore almost never actually ran the workers concurrently. **(PERF.HW.a)
   is not closed.**
2. **The JVM's own core tax is NOT fixed — it scaled with the box, 3.15 → 4.17
   cores.** Round 740's ">= 12 cores" unpark condition was expressed as "8 net of a
   *constant* ~3.2", and that constant is not constant.
3. **The 1.25x Amdahl cap is refuted by counterexample: w4 measures 1.343x**, 5/5
   wins, sign-consistent. The re-fitted infinite-worker floor is ~1.52-1.58x.

---

## 1. The box

| property | round 740 (old) | **round 824 (this box)** | how |
|---|---|---|---|
| `nproc` | 4 | **8** | — |
| CPU | AMD EPYC-Rome | AMD EPYC-Rome | `lscpu` |
| topology | 4 cores, no SMT | **8 cores, 1 thread each — no SMT** | `Thread(s) per core: 1` |
| cgroup CPU cap | none | **none** (`cpu.max` absent) | `/sys/fs/cgroup/cpu.max` |
| steal time | 0.0 / 0 over 72 samples | **0.000 mean, 0 max over 113 samples** | `vmstat 5` |
| RAM | 7,751 MB, swap 0 | **15,613 MB, swap 0** | `free -m` |
| JDK | — | OpenJDK **25.0.3** | `java -version` |

**JVM ergonomic thread pools, and why they matter (§ 3):** `CICompilerCountPerCPU`
is `true` by default, so `CICompilerCount` is **4** here against 3 on a 4-core box,
and `ParallelGCThreads` is **8** against 4. The JVM sizes its own overhead pools
from `nproc`.

---

## 2. The scaling ladder

5 reps x 4 levels, **rotated interleave** (rep *r* starts at level *r* mod 4, so no
level systematically runs first or last within a rep) — `self` is the compiler's own
reported time, `user` is `/usr/bin/time -v` user CPU. `seq` is *no* `--workers` flag,
i.e. the true sequential path, not `--workers 1`.

| level | self ms (rep 1..5) | **median** | sd | spread | **paired median delta** | **wins** | user CPU s | **cores** | peak RSS |
|---|---|---:|---:|---:|---:|:---:|---:|---:|---:|
| **seq** | 25,568 / 26,454 / 26,343 / 25,510 / 26,145 | **26,145** | 439 | 3.6% | — | — | 110.4 | **4.17** | 834 MB |
| **w2** | 20,450 / 20,708 / 21,324 / 21,565 / 21,616 | **21,324** | 525 | 5.5% | **-19.05%** | **5/5** | 105.9 | 5.02 | 1,296 MB |
| **w4** | 19,472 / 19,294 / 18,824 / 20,474 / 19,927 | **19,472** | 629 | 8.5% | **-23.84%** | **5/5** | 117.1 | 5.91 | 2,507 MB |
| **w8** | 21,351 / 21,605 / 21,860 / 20,572 / 22,342 | **21,605** | 656 | 8.2% | **-17.02%** | **5/5** | 142.6 | 6.44 | 2,223 MB |

**Read the win rates.** Every level's five per-rep deltas are **sign-consistent** —
they never straddle zero, which is what round 740's w4 did:

    w2:  -20.02 / -21.72 / -19.05 / -15.46 / -17.32 %
    w4:  -23.84 / -27.07 / -28.54 / -19.74 / -23.78 %
    w8:  -16.49 / -18.33 / -17.02 / -19.36 / -14.55 %

**Sanity anchor:** round 823 measured the cold JVM sequential median at 25,299 ms
(sd 867, spread 9.2%, n=5) on this box. This round's seq arm is 26,145 ms with a
*tighter* 3.6% spread — 3.3% apart, well inside either arm's band.

**The shape changed, not just the magnitude.** On the old box w2 was the only win
and w4 was flat; here **w4 is the best level** and even w8 — a decisive +19.4%
*regression* on 4 cores — is now a 17% win. Every level is faster than sequential.

### Where the total work goes

    user CPU:  seq 110.4 s -> w2 105.9 (-4%) -> w4 117.1 (+6%) -> w8 142.6 (+29%)

Qualitatively identical to round 740 (85.6 / 85.3 / 92.6 / 117.7): each worker
re-binds every file and runs all ~318 program-wide collectors, so worker count buys
division at the price of duplication. What changed is that the box now has enough
free cores to absorb the duplication up to w4 — the cores-used column rises
4.17 -> 5.02 -> 5.91 -> 6.44 and never reaches 8, i.e. **nothing here is saturated**;
w8's regression against w4 is the duplication, not a core ceiling.

---

## 3. The JVM core tax scaled with the box — the unpark condition's own unit moved

A "single-threaded" run consumed **3.15** of 4 cores on the old box. Here it consumes
**4.17** of 8 (per-rep 4.13 / 4.16 / 4.21 / 4.17 / 4.19 — a tight ±1%).

Attributed the same way round 740 did, by starving the JIT:

| configuration | self ms | user CPU s | cores |
|---|---:|---:|---:|
| baseline | 27,777 | 116.68 | **4.20** |
| `-XX:CICompilerCount=2` | 28,553 | 72.85 | **2.55** |

**JIT ≈ 43.8 s of CPU here, against ≈ 21.7 s on the 4-core box** — it roughly
doubled, because `CICompilerCountPerCPU` sized the compiler pool from `nproc`. Self
time is again flat across the two configurations (27.8 vs 28.6 s, a +2.8% delta
inside the arm's own spread), reproducing round 618/740: **the JIT threads are not
stealing from the compile thread, they are occupying cores a worker would want.**

**So round 740's framing is wrong in its load-bearing clause.** It wrote the
requirement as ">= 8 cores *net of the ~3.2 the JVM's own JIT and GC consume*, i.e.
realistically >= 12", and justified it with "that tax is **fixed per JVM** and does
not grow with worker count, so a larger host simply out-sizes it". The tax does not
grow with *worker count* — that part holds — but it **does grow with the host**:
doubling the cores raised it 3.15 -> 4.17. Free cores during a sequential compile
therefore went 0.85 -> 3.83, not 0.85 -> 4.85.

**And 3.83 free cores turned out to be enough.** The condition demanded 12 and the
answer arrived at 8; it was miscalibrated in the conservative direction, and the
reason is that it extrapolated a *constant* from a single host.

---

## 4. The Amdahl re-fit — the 1.25x cap is dead

Model `seq = R + P`, `wN = R + P/N`, on the self-ms medians:

| fitted from | P (divisible) | R (non-divisible) | P share | infinite-worker floor |
|---|---:|---:|---:|---:|
| seq / w2 | 9,642 ms | 16,503 ms | **36.9%** | **1.584x** |
| seq / w4 | 8,897 ms | 17,248 ms | **34.0%** | **1.516x** |
| seq / w8 | 5,189 ms | 20,956 ms | 19.8% | 1.248x |

**The w2 and w4 fits now agree within 8%** (36.9 vs 34.0). On the old box the w2 and
w4 fits disagreed by **3.5x** (19.7% vs 5.7%), which was the tell that the model was
contention-broken beyond w2. It is now coherent through w4 and breaks at w8 — the
same diagnostic rule, one level further out.

**The cap is refuted directly, not just re-fitted: w4 *measured* 1.343x, which
exceeds the old fitted ceiling of 1.25x.** The 1.25x was an artifact of a box with
0.85 free cores, not a property of the parallel design.

*Coincidence worth flagging so nobody misreads it as confirmation:* the seq/w8 fit
lands at 1.248x, numerically almost exactly round 740's cap. It is the
contention-broken fit here, and it is not evidence for anything.

---

## 5. `--workers N` IS A RACE — and this is the round's headline

Sequential emitted **46** on every one of 8 runs. No parallel level is stable:

| level | runs | error counts observed | extras vs sequential |
|---|---:|---|---|
| **seq** | 8 | **46 x 8** | — |
| **w2** | 10 | **46 x 5, 47 x 5** | 1 line, always the same |
| **w4** | 6 | **46 x 3, 47 x 3** | 1 line, always the same |
| **w8** | 7 | **46 x 1, 56 x 6** | 10 lines, byte-identical set across captures |

**The w2/w4 extra** is one diagnostic:

    src/compiler/debug.ts:601:46 - error TS2345: Argument of type 'NodeArray<Node>'
      is not assignable to parameter of type 'ModuleDeclaration | Node'.

**The w8 extras are a DIFFERENT family** — 10 lines, and **`debug.ts:601` is not
among them**: 7x TS2322 in `src/compiler/transformers/declarations/diagnostics.ts`
(207, 310, 416, 424, 557, 579, 587) plus `utilities.ts:10384` TS2344 and
`utilities.ts:11808` / `11859` TS2322. Two independent w8 captures of the 56-set are
byte-identical (`md5sum` match), so each *outcome* is reproducible; which outcome you
get is not.

Properties that classify it:

- **Non-deterministic at a fixed worker count** — that is what makes it a race, and
  it is exactly what distinguishes it from the round-740 divergence (which was
  *deterministic* and identical at w2/w4/w8, and was correctly diagnosed and fixed at
  round 754 as a resolution-ORDER accident).
- **Strictly ADDITIVE.** Every capture diffed against the sequential baseline shows
  `added = N, removed = 0`. The parallel mode never *loses* a true diagnostic; it
  invents false ones. That bounds the severity but does not make it safe.
- **Not a `--partitionCheck` model bug by construction** — the same worker count
  gives different answers, so no static partition model can explain it.
- **The count depends on the level**, so it is not one single shared bug: w8 reaches
  a family w2/w4 never do, presumably because more workers means more interleavings.

**No capture carries the round-811 `... and N more error(s)` truncation tell**, so
every diff above is over a complete `--listAll` capture.

### Why round 754 concluded byte-identity

Round 754's claim is not fraudulent, it is under-sampled — and this round reproduced
the trap: **this round's own stage-A `--listAll` run at w2 came out at 46 and diffed
byte-identically to sequential.** One draw of a coin that lands 50/50. On the old box
the odds were far worse than 50/50 in the *other* direction: with ~0.85 free cores the
workers were effectively serialized, so the interleaving that triggers this almost
never occurred. **The upgrade did not introduce the race; it made it observable.**

**Consequence: every timing number in § 2 compares a correct sequential run against a
sometimes-diverging parallel one.** The extra diagnostics are 1 or 10 lines out of
46 and cost nothing measurable, so the timings stand as timings — but no `--workers`
wall-time figure can be a v1 claim while the mode is non-deterministic.

---

## 6. Verdict

**Does (M2) unpark on this host?** **The performance case unparks; the correctness
gate does not.**

- The measured prize more than doubled: **1.343x at w4, 5/5 wins**, with an Amdahl
  floor of ~1.52-1.58x if the duplicated 64% were attacked — against round 740's
  "1.25x ever, and unreachable on this box".
- Every reason round 740 gave for *not* attempting it has now failed except one.
  (1) "The ceiling is 1.25x" — refuted by counterexample. (2) "There is no machine
  here to spend it on" — there is: 3.83 free cores, nothing saturated even at w8.
  (3) "The mode is incorrect" — **still true, and now worse than it was thought to
  be: it is a race, not a fixed bug.**

**So the blocking item is (PERF.HW.a) again, re-opened on evidence.** A
non-deterministic checker cannot be shipped, benchmarked as a claim, or used as the
baseline for the R-shrinking work (b) — you cannot tell a correctness regression from
a coin flip. The revival order is unchanged in shape and the first step is the same
one, for a different reason than in 2026-07:

1. **Close the race.** Diagnose with repeated fixed-worker-count runs, not with
   `--partitionCheck` (a static partition model cannot produce two answers).
2. **Shrink R** — the per-worker full re-bind and the ~318 program-wide collectors
   are still the identified duplication, and are now worth ~1.5x rather than ~1.25x.
3. **Re-probe.** On this host, with this ladder.

**Methodological note for whoever does step 1:** an error *count* is a sufficient
detector here and is nearly free — 5 runs at one worker level took ~110 s and
separated 46 from 47. Any future `--workers` claim must report a distribution over
several runs, never a single capture.

---

*Raw per-run data (24 ladder runs: stage, rep, level, wall/self ms, errors, user and
sys CPU, peak RSS) in `worker-scaling-round824.tsv` beside this file. Gates: no
`src/` change, so `jvmTest` is not applicable and the 13,803 / 0 / 3 baseline is
untouched by construction.*
