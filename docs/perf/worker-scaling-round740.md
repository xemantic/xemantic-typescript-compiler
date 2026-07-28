# (PERF.HW) — the worker-scaling probe, and why the item's premise was wrong

*Round 740, 2026-07-28. Compiler profile, `--noEmit`, `-Xmx4g`, one cold JVM per
run. No `src/` change: this is a measurement round on the committed binary at
`2f443adc`.*

The queue item asked one question — *does this VPS have real cores, so that M2
(parallel checking) is worth reviving?* — and named one unpark condition, "a host
with >= 8 real cores (re-run this exact probe first)".

**The cores are real. The item's unpark condition is still not met, and the reason
is not the one anybody expected: a *sequential* xtsc run already consumes 3.15 of
the 4 cores.** 79% of the machine is spoken for before the first worker is
created. That single number explains the whole scaling curve, explains round 666's
"w4 is flat", and makes ">= 8 cores" a necessary-but-insufficient unpark condition.

---

## 1. The box

| property | value | how |
|---|---|---|
| `nproc` | **4** | — |
| CPU | **AMD EPYC-Rome**, 2445 MHz | `/proc/cpuinfo` |
| topology | 4 distinct `core id`s, **1 thread sibling each — no SMT** | `topology/thread_siblings_list` |
| cgroup CPU cap | **none** (`cpu.max` absent) | `/sys/fs/cgroup/cpu.max` |
| **steal time** | **0.0 mean, 0 max over 72 vmstat samples** spanning the whole probe | `vmstat 5` |
| RAM | 7,751 MB total, ~5,600 MB available, **swap 0** | `free -m` |

**Steal time alone does not settle "are the cores real"** — a hard hypervisor
quota can be enforced without steal accounting. So the cores were tested directly,
with a tiny-working-set pure-CPU loop (no allocation, no I/O, no JIT):

| concurrency | per-job ms | aggregate throughput |
|---|---|---|
| 1 | 1,880 | 1.00x |
| 2 | 2,165–2,390 | 1.56x |
| 4 | 2,046–2,157 | **3.45x** |
| 8 | 3,750–4,129 | 3.61x (saturated, correct for 4 cores) |

**Verdict: four real, independent, unthrottled cores.** (The 2-way point is low
because a ~2 s job is dominated by process start; the 4-way and 8-way points are
the meaningful ones and they are textbook.)

---

## 2. The scaling table

3 reps, **round-robin interleaved across levels** (round 666 ran the levels in
blocks, which lets drift land on one level); `self` is the compiler's own reported
time, `user` is `/usr/bin/time -v` user CPU seconds.

| level | self ms (rep 1 / 2 / 3) | median | per-rep median delta | wins vs w1 | user CPU s | **cores used** | peak RSS | GC pause |
|---|---|---:|---:|:---:|---:|---:|---:|---:|
| **w1** | 27,126 / 26,356 / 27,904 | **27,126** | — | — | 85.6 | **3.15** | 822 MB | 0.47–0.50 s |
| **w2** | 23,961 / 24,458 / 24,452 | **24,452** | **−3,165 ms (−11.67%)** | **3/3** | 85.3 | 3.49 | 1,555 MB | 0.52–0.62 s |
| **w4** | 25,976 / 26,452 / 25,838 | **25,976** | −1,150 ms (−4.24%) | 2/3 | 92.6 | 3.57 | 1,705 MB | 0.67–0.75 s |
| **w8** | 32,184 / 32,212 / 33,310 | **32,212** | **+5,406 ms (+19.37%)** | **0/3** | 117.7 | 3.65 | 2,240 MB | 1.03–1.14 s |

**Drift band, re-derived rather than reused:** w1's own three reps sit at
**+-2.87% around their median** (peak-to-peak 5.7%).

**Read the win rates, not the medians.** w2 is a real win: 3/3, and the per-rep
deltas (−11.67 / −7.20 / −12.37%) never change sign. w4's per-rep deltas are
**−4.24 / +0.36 / −7.40%** — they *straddle zero*, so w4 is noise-dominated and
decides nothing except that it is **not better than w2**. w8 is a decisive
regression: 0/3, tight range +18.7…+22.2%.

**This reproduces round 666 exactly** (seq 27,873 / w2 24,669 = −11.5% / w4 27,905
= flat) on a compile that is now check-only, and extends it with the w8 point the
item asked for.

---

## 3. The finding that reframes the item: a "single-threaded" run uses 3.15 cores

85.6 s of user CPU for a 27.1 s wall. The compile thread can be at most 1.00 of
that. Attribution, by starving each subsystem in turn:

| configuration | self ms | user CPU s | cores | attributed |
|---|---:|---:|---:|---|
| baseline | 26,641 | 84.5 | 3.17 | — |
| `-XX:CICompilerCount=2` | 26,824 | 62.8 | 2.34 | **JIT ≈ 21.7 s CPU ≈ 0.8–2.2 cores** |
| `-XX:ParallelGCThreads=1 -XX:ConcGCThreads=1` | 26,703 | 81.8 | 3.06 | GC ≈ 2.7 s CPU ≈ 0.11 cores |
| both | 27,780 | 62.9 | 2.27 | — |

**It is JIT, not GC.** C2 compiling a ~110k-line `Checker.kt` never finishes inside
a 27 s run, so the compiler threads are hot for the whole compile. **Self time is
flat across all four rows** (26.6–27.8 s), so the JIT threads are *not* stealing
from the compile thread — consistent with round 618's "JVM flag hunting is DEAD"
for wall time. They are, however, consuming the cores a worker would need.

**So the headroom for parallelism on this box is ~0.85 cores, not 3.**

### The whole curve follows from that one number

Every level saturates at the same **~3.6-core ceiling** (3.49 / 3.57 / 3.65). What
changes with worker count is not how much parallelism is obtained but **how much
total work is done** — because each worker re-binds every file and runs all ~318
program-wide collectors (round 666):

    user CPU:  w1 85.6 s  ->  w2 85.3 s (+0%)  ->  w4 92.6 s (+8%)  ->  w8 117.7 s (+37%)

**w2 divides work without adding any** (85.3 vs 85.6 s — the duplication is
absorbed by the ~0.85 free cores) and banks −11.7%. w4 and w8 add 8% and 37% more
CPU to a machine that has none left, and the extra converts directly into wall.

### It is not a JIT artifact — tested, negative

If w4's flatness were JIT threads crowding out workers, freeing them would fix it:

| | baseline | `-XX:CICompilerCount=2` |
|---|---:|---:|
| w2 | 24,305 ms | 24,323 ms (unchanged) |
| w4 | 25,870 ms (3.62 cores) | 25,574 ms (**3.28 cores**) |

~0.34 cores were demonstrably released at w4 and the wall did not move. **The w4
ceiling is the parallel design's own duplicated work, not core starvation.**

### And the 4-concurrent-process test, correctly interpreted

Four *independent* solo compiles run at once took 103–105 s each (3.85x solo),
aggregate throughput **1.03x** — which looks catastrophic until the 3.15 figure is
applied: 4 x 85.6 = 342 core-seconds on 4 cores has a **86 s floor**, and 105 s
against that floor is **82% parallel efficiency**. The box parallelises fine.
**What does not parallelise is our compile, because one copy of it already occupies
79% of the machine.**

---

## 4. The Amdahl fit, and where it breaks

Round 666's model, `seq = R + P` and `wN = R + P/N`:

| fitted from | P (divisible) | R (non-divisible) | P share |
|---|---:|---:|---:|
| w1 / w2 | 5,348 ms | 21,778 ms | **19.7%** |
| w1 / w4 | 1,533 ms | 25,593 ms | 5.7% |

**The two fits disagree by 3.5x**, which — per the rule fixed before the run —
means the model is contention-broken beyond w2 and only the w1/w2 fit is
meaningful. Its infinite-worker floor is 21,778 ms = **−19.7%, i.e. 1.25x, ever**.

Round 666 fitted 23%; this round fits 19.7%. **That difference is not resolvable at
this precision**: P is twice a delta whose own per-rep spread is 1,554 ms, so P
carries +-1.5 s = +-5.7 points. Stated so nobody reads a trend into it. (The prior
written down before the run — that removing emit from R should *raise* the
divisible share — is therefore untested, not falsified.)

---

## 5. `--workers N` IS NOT BEHAVIOUR-PRESERVING (found by this probe)

Sequential emits **46** diagnostics on the compiler profile; **every** parallel
level emits **62**. The 16 extras are one family in one file:

    src/compiler/utilities.ts:11349..11410 (16 sites)
    error TS2322: Type 'EvaluatorResult<number>' is not assignable to type 'EvaluatorResult'.
                  (and the <string> instantiations)

Properties that classify it: **identical at w2, w4 and w8** (not a count-dependent
partitioning effect), **deterministic across reps** (not a race), and it is a
`<T>`-with-default-type-argument comparison. That is the round-609 signature — a
program-wide *collector* iterating the INV.6 partition view (`checkedResults`)
instead of `binderResults`, so a partition worker never sees the context that
suppresses it. `--partitionCheck N` is the existing harness for exactly this.

**Consequence for the table above: it compares a correct sequential run against a
diverging parallel one.** The 16 extra emissions are negligible in cost, so the
timings stand — but no wall-time number from `--workers` can be a v1 claim until
this is closed.

---

## 6. Verdict

**Are the cores real?** Yes — 4 physical, no SMT, no cgroup cap, zero steal, 3.45x
measured 4-way scaling on a pure-CPU load.

**Is the box capable of showing a parallel gain?** Barely. It has ~0.85 free cores
during a compile, which is precisely enough for w2's −11.7% and nothing more.

**Is shrinking the 77% duplicated term worth attempting?** **Not now, and not on
this box.** Three independent reasons, in order of how hard they are to remove:

1. **The ceiling is 1.25x even if R shrank to nothing that a worker duplicates** —
   the w1/w2 fit says 80.3% of the run does not divide. Twenty percent is not
   nothing next to an arc whose last eight rounds found nothing above ~5%, but it
   is the *whole* prize, and it needs every item below to land first.
2. **There is no machine here to spend it on.** 3.15 of 4 cores are consumed by one
   sequential run. Even a perfectly divisible checker would find ~0.85 cores free.
3. **The mode is incorrect** (§ 5). That is a prerequisite, not a detail.

**The unpark condition must be rewritten.** ">= 8 real cores" is necessary but
insufficient: the measured requirement is >= 8 cores *net of the ~3.2 the JVM's own
JIT and GC consume during a ~27 s cold run*, i.e. realistically **>= 12 cores** —
and, unlike the workers, that JIT overhead is **fixed per JVM and does not grow
with worker count**, so it is a constant tax that a larger host simply out-sizes.
On a 12-core host, w4 would have ~8.8 genuinely free cores and the w1/w2 fit's
1.25x would be reachable.

**Recommended order if M2 is ever revived** (unchanged in spirit from round 666,
now with the numbers behind it): (a) close the `--workers` divergence, (b) shrink R
— the full per-worker re-bind is still the single largest identified duplication —
(c) only then re-run this probe, on a >= 12-core host.

---

## 7. Predictions, scored

Stated in full before any measurement.

| | prediction | outcome |
|---|---|---|
| **P1** | w1 rep spread <= +-3% of its median | **HELD** — +-2.87% |
| **P2** | w2 is a 8–15% win, >= 2/3 reps | **HELD** — −11.7%, 3/3 |
| **P3** | w4 within +-4% of w1 (flat), not better than w2 | **HELD** (marginally) — −4.24% median, per-rep range straddles zero; decisively not better than w2 |
| **P4** | w8 worse than w4 and w1 by >= 5% | **HELD** — +19.4%, 0/3 |
| **P5** | w8 does not fit `-Xmx4g` without GC thrash | **FALSIFIED** — peak RSS 2,240 MB, GC 1.1 s of a 5.4 s regression (~20% of it). **No level was skipped for want of RAM.** |
| **P6** | cores real, only 4, M2 stays parked | **HELD** — but for an unpredicted reason: the cores are real *and* already occupied by our own JVM |

**5 of 6.** The miss (P5) and the surprise (3.15 cores) point the same way: memory
was never the constraint on this box, and CPU was constrained by something nobody
had measured rather than by the core count everybody had been arguing about.
